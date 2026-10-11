/* Screen-effect chain — see effects_chain.h. Compositor thread unless noted. */
#define _POSIX_C_SOURCE 200809L
#include "effects_chain.h"
#include "vk_loader.h"
#include "vk_present.h" /* wnc_log */
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* The X11 Vulkan renderer's SPIR-V, pre-compiled by app/src/main/cpp/winlator/gen_shaders.sh
 * (glslangValidator --vn) and committed next to the GLSL: the NDK build compiles no shaders, so
 * there is no build-time tooling for CI to break. Same bytes as the X11 chain runs. */
#include "upscale_vert.h"
#include "sgsr_frag.h"
#include "sgsr_quality_frag.h"
#include "nis_frag.h"
#include "fsr_easu_frag.h"
#include "fsr_rcas_frag.h"
#include "cas_frag.h"
#include "color_frag.h"
#include "fxaa_frag.h"
#include "toon_frag.h"
#include "hdr_frag.h"
#include "ntsc_frag.h"
#include "crt_frag.h"
#include "deband_frag.h"

#define FX_FORMAT VK_FORMAT_R8G8B8A8_UNORM /* the default: every SDR frame */
/* The chain's target format and the scene's view format. R8G8B8A8 always, except while an HDR scene
 * runs through the chain (hdr_compose.h): then the scene and the targets are A2B10G10R10, so a PQ
 * picture keeps 10 bits through every pass. A change rebuilds the chain's objects once
 * (vkp_effects_set_formats). */
static VkFormat g_fx_fmt = FX_FORMAT, g_scene_fmt = FX_FORMAT;
#define FX_MAX_DIM 4096 /* a FILL-mapped scene can exceed the panel; cap the chain resolution */

/* ------------------------------------------------------------------ settings */

struct fx_settings {
    int scaling;        /* 0..8 */
    int sharp_pct;      /* 0..100, the upscaler's own slider */
    int cas_on, cas_pct;
    int hdr_on;
    int deband_on, deband_pct; /* 0..200 */
    int color_on;       /* derived: grade not neutral */
    float brightness, contrast, gamma, saturation; /* shader units (already mapped/clamped) */
    float b_raw, c_raw, s_raw;                     /* slider units, for the log */
    int fxaa, toon, crt, ntsc;
    char look[48];
};

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static struct fx_settings g_set = {
    .scaling = 0, .sharp_pct = 75, .cas_on = 0, .cas_pct = 60, .deband_pct = 100,
    .gamma = 1.0f, .saturation = 1.0f, .s_raw = 100.0f, .look = "Off"};
static volatile int g_changed;
static struct fx_settings g_cur; /* the compositor thread's per-frame snapshot */
static int g_cur_valid;

static void set_begin(void) { pthread_mutex_lock(&g_lock); }
static void set_end(void) { g_changed = 1; pthread_mutex_unlock(&g_lock); }

void vkp_effects_set_scaling(int mode) {
    if (mode < 0 || mode > 8) mode = 0;
    set_begin(); g_set.scaling = mode; set_end();
}
void vkp_effects_set_upscale_sharpness(int pct) {
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    set_begin(); g_set.sharp_pct = pct; set_end();
}
void vkp_effects_set_cas(int enabled, int pct) {
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    set_begin(); g_set.cas_on = enabled ? 1 : 0; g_set.cas_pct = pct; set_end();
}
void vkp_effects_set_hdr(int enabled) { set_begin(); g_set.hdr_on = enabled ? 1 : 0; set_end(); }
void vkp_effects_set_deband(int enabled, int pct) {
    if (pct < 0) pct = 0;
    if (pct > 200) pct = 200;
    set_begin(); g_set.deband_on = enabled ? 1 : 0; g_set.deband_pct = pct; set_end();
}
static float clampf(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }
void vkp_effects_set_screen(float brightness, float contrast, float gamma, float saturation,
                            int fxaa, int toon, int crt, int ntsc) {
    /* Same mapping as VulkanRendererContext::setColorGrade (GL ColorEffect clamps). */
    int on = !(brightness == 0.0f && contrast == 0.0f && gamma == 1.0f && saturation == 100.0f);
    set_begin();
    g_set.color_on = on;
    g_set.b_raw = brightness; g_set.c_raw = contrast; g_set.s_raw = saturation;
    g_set.brightness = clampf(brightness / 100.0f, -1.0f, 1.0f);
    g_set.contrast = clampf(contrast / 100.0f, 0.0f, 2.0f);
    g_set.saturation = clampf(saturation / 100.0f, 0.0f, 2.0f);
    g_set.gamma = clampf(gamma, 0.1f, 5.0f);
    g_set.fxaa = fxaa ? 1 : 0; g_set.toon = toon ? 1 : 0; g_set.crt = crt ? 1 : 0; g_set.ntsc = ntsc ? 1 : 0;
    set_end();
}
void vkp_effects_set_look(const char *name) {
    set_begin();
    snprintf(g_set.look, sizeof(g_set.look), "%s", name && *name ? name : "Custom");
    set_end();
}

static const char *scaling_name(int m) {
    static const char *n[] = {"None", "Linear", "Nearest", "SGSR", "FSR", "FSR Fit", "Sharpen", "NIS", "SGSR HQ"};
    return (m >= 0 && m <= 8) ? n[m] : "?";
}
static int spatial_mode(int m) { return m >= 3 && m <= 8; }
static int cas_active(const struct fx_settings *s) { return s->cas_on && s->cas_pct > 0; }
static int effect_count(const struct fx_settings *s) {
    return s->fxaa + s->toon + s->color_on + cas_active(s) + s->hdr_on + s->ntsc + s->crt + s->deband_on;
}

static void log_settings(const struct fx_settings *s) {
    char line[400];
    int n;
    if (!spatial_mode(s->scaling) && !effect_count(s)) {
        wnc_log("effects", "all off: scaling=%s (plain blit)", scaling_name(s->scaling));
        return;
    }
    n = snprintf(line, sizeof(line), "scaling=%s", scaling_name(s->scaling));
    if (spatial_mode(s->scaling)) n += snprintf(line + n, sizeof(line) - n, " %d%%", s->sharp_pct);
    if (cas_active(s)) n += snprintf(line + n, sizeof(line) - n, ", CAS on %d%%", s->cas_pct);
    else n += snprintf(line + n, sizeof(line) - n, ", CAS off");
    n += snprintf(line + n, sizeof(line) - n, ", Look=\"%s\"", s->look);
    if (s->color_on)
        n += snprintf(line + n, sizeof(line) - n, ", colour b=%+.0f c=%+.0f g=%.2f s=%.0f%%",
                      s->b_raw, s->c_raw, s->gamma, s->s_raw);
    if (s->fxaa) n += snprintf(line + n, sizeof(line) - n, ", FXAA on");
    if (s->toon) n += snprintf(line + n, sizeof(line) - n, ", Toon on");
    if (s->hdr_on) n += snprintf(line + n, sizeof(line) - n, ", HDR on");
    if (s->ntsc) n += snprintf(line + n, sizeof(line) - n, ", NTSC on");
    if (s->crt) n += snprintf(line + n, sizeof(line) - n, ", CRT on");
    if (s->deband_on) n += snprintf(line + n, sizeof(line) - n, ", Deband on %d%%", s->deband_pct);
    (void)n;
    wnc_log("effects", "%s", line);
}

int vkp_effects_sync(void) {
    if (!g_changed && g_cur_valid) return 0;
    pthread_mutex_lock(&g_lock);
    g_changed = 0;
    g_cur = g_set;
    pthread_mutex_unlock(&g_lock);
    g_cur_valid = 1;
    log_settings(&g_cur);
    return 1;
}

int vkp_effects_active(void) {
    vkp_effects_sync();
    return spatial_mode(g_cur.scaling) || effect_count(&g_cur) > 0;
}

VkFilter vkp_effects_blit_filter(void) {
    vkp_effects_sync();
    return g_cur.scaling == 2 ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
}

/* ------------------------------------------------------------------ push constants
 * Byte-identical to VulkanRendererContext.h: vec4 ndc first (upscale.vert), the rest fragment-only. */
struct pc_sgsr  { float ndc[4]; float vp[4]; float edge; };        /* also NIS: vp + sharpness */
struct pc_easu  { float ndc[4]; uint32_t con0[4], con1[4], con2[4], con3[4]; float outW, outH; };
struct pc_rcas  { float ndc[4]; uint32_t con[4]; float outW, outH; };
struct pc_res   { float ndc[4]; float res[2]; float extra; float pad; }; /* fxaa/toon/hdr/crt (res), cas (sharpness), ntsc (frameCount), deband (strength) */
struct pc_color { float ndc[4]; float b, c, g, s; };
#define PC_MAX_SIZE sizeof(struct pc_easu) /* 88 bytes, the largest */

static uint32_t packf(float f) { uint32_t u; memcpy(&u, &f, 4); return u; }

/* Mirrors ffx_fsr1.h FsrEasuCon / FsrRcasCon exactly as the X11 renderer packs them. */
static void easu_con(struct pc_easu *e, float inW, float inH, float outW, float outH) {
    e->con0[0] = packf(inW / outW);            e->con0[1] = packf(inH / outH);
    e->con0[2] = packf(0.5f * inW / outW - 0.5f); e->con0[3] = packf(0.5f * inH / outH - 0.5f);
    e->con1[0] = packf(1.0f / inW);  e->con1[1] = packf(1.0f / inH);
    e->con1[2] = packf(1.0f / inW);  e->con1[3] = packf(-1.0f / inH);
    e->con2[0] = packf(-1.0f / inW); e->con2[1] = packf(2.0f / inH);
    e->con2[2] = packf(1.0f / inW);  e->con2[3] = packf(2.0f / inH);
    e->con3[0] = packf(0.0f);        e->con3[1] = packf(4.0f / inH);
    e->con3[2] = 0;                  e->con3[3] = 0;
}
static void rcas_con(struct pc_rcas *r, float scale01) {
    r->con[0] = packf(scale01); r->con[1] = 0; r->con[2] = 0; r->con[3] = 0;
}

/* ------------------------------------------------------------------ Vulkan objects */

struct fx_target {
    VkImage img; VkDeviceMemory mem; VkImageView view; VkFramebuffer fb; VkDescriptorSet ds;
    int w, h;
};

static VkDevice g_dev;
static VkPhysicalDevice g_pd;
static VkPhysicalDeviceMemoryProperties g_mp;
static int g_ready;          /* 0 = objects not built, 1 = ok, -1 = failed (chain disabled, said once) */
static VkRenderPass g_rp;
static VkDescriptorSetLayout g_dsl;
static VkPipelineLayout g_pl;
static VkDescriptorPool g_dpool;
static VkSampler g_sampler;  /* linear, clamp-to-edge: what the X11 chain samples its targets with */
enum { P_SGSR, P_SGSR_HQ, P_NIS, P_EASU, P_RCAS, P_CAS, P_COLOR, P_FXAA, P_TOON, P_HDR, P_NTSC, P_CRT, P_DEBAND, P_COUNT };
static VkPipeline g_pipe[P_COUNT];
static struct fx_target g_t[2], g_mid; /* ping-pong pair + the EASU output (FSR only) */
/* The scene image belongs to vk_present; only its view + descriptor live here. */
static struct { VkImage img; VkImageView view; VkDescriptorSet ds; int w, h; } g_scene;
static uint32_t g_ntsc_frame;

void vkp_effects_bind_device(VkDevice dev, VkPhysicalDevice pd,
                             const VkPhysicalDeviceMemoryProperties *memprops) {
    g_dev = dev; g_pd = pd; g_mp = *memprops;
}

static int memtype(uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < g_mp.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (g_mp.memoryTypes[i].propertyFlags & want) == want) return (int)i;
    return -1;
}

static VkDescriptorSet alloc_ds(VkImageView view) {
    VkDescriptorSet ds = VK_NULL_HANDLE;
    VkDescriptorSetAllocateInfo ai = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
                                      .descriptorPool = g_dpool, .descriptorSetCount = 1, .pSetLayouts = &g_dsl};
    if (g_vk.AllocateDescriptorSets(g_dev, &ai, &ds) != VK_SUCCESS) return VK_NULL_HANDLE;
    VkDescriptorImageInfo ii = {.sampler = g_sampler, .imageView = view,
                                .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet wr = {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = ds, .dstBinding = 0,
                               .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                               .pImageInfo = &ii};
    g_vk.UpdateDescriptorSets(g_dev, 1, &wr, 0, NULL);
    return ds;
}

static VkImageView make_view(VkImage img, VkFormat fmt) {
    VkImageView v = VK_NULL_HANDLE;
    VkImageViewCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = img,
                                .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = fmt,
                                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    if (g_vk.CreateImageView(g_dev, &ci, NULL, &v) != VK_SUCCESS) return VK_NULL_HANDLE;
    return v;
}

static void target_destroy(struct fx_target *t) {
    if (t->ds) g_vk.FreeDescriptorSets(g_dev, g_dpool, 1, &t->ds);
    if (t->fb) g_vk.DestroyFramebuffer(g_dev, t->fb, NULL);
    if (t->view) g_vk.DestroyImageView(g_dev, t->view, NULL);
    if (t->img) g_vk.DestroyImage(g_dev, t->img, NULL);
    if (t->mem) g_vk.FreeMemory(g_dev, t->mem, NULL);
    memset(t, 0, sizeof(*t));
}

/* A chain target: rendered into (colour attachment), sampled by the next pass, and blitted to
 * the swapchain when it ends up holding the result. Frames are fenced, so a resize never races
 * the GPU. 0 = ok. */
static int target_ensure(struct fx_target *t, int w, int h, const char *what) {
    if (t->img && t->w == w && t->h == h) return 0;
    target_destroy(t);
    VkImageCreateInfo ii = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D,
                            .format = g_fx_fmt, .extent = {(uint32_t)w, (uint32_t)h, 1}, .mipLevels = 1,
                            .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
                            .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                                     VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                            .sharingMode = VK_SHARING_MODE_EXCLUSIVE, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
    VkResult r = g_vk.CreateImage(g_dev, &ii, NULL, &t->img);
    if (r != VK_SUCCESS) { wnc_log("error", "effects: %s image %dx%d: vkCreateImage %d", what, w, h, (int)r); return -1; }
    VkMemoryRequirements req;
    g_vk.GetImageMemoryRequirements(g_dev, t->img, &req);
    int idx = memtype(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (idx < 0) idx = memtype(req.memoryTypeBits, 0);
    VkMemoryAllocateInfo mai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = req.size,
                                .memoryTypeIndex = (uint32_t)(idx < 0 ? 0 : idx)};
    r = g_vk.AllocateMemory(g_dev, &mai, NULL, &t->mem);
    if (r != VK_SUCCESS) {
        wnc_log("error", "effects: %s image %dx%d (%llu bytes): vkAllocateMemory %d", what, w, h,
                   (unsigned long long)req.size, (int)r);
        target_destroy(t); return -1;
    }
    g_vk.BindImageMemory(g_dev, t->img, t->mem, 0);
    if (!(t->view = make_view(t->img, g_fx_fmt))) { target_destroy(t); return -1; }
    VkFramebufferCreateInfo fi = {.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, .renderPass = g_rp,
                                  .attachmentCount = 1, .pAttachments = &t->view, .width = (uint32_t)w,
                                  .height = (uint32_t)h, .layers = 1};
    if (g_vk.CreateFramebuffer(g_dev, &fi, NULL, &t->fb) != VK_SUCCESS) { target_destroy(t); return -1; }
    if (!(t->ds = alloc_ds(t->view))) { target_destroy(t); return -1; }
    t->w = w; t->h = h;
    return 0;
}

static void scene_release(void) {
    if (g_scene.ds) g_vk.FreeDescriptorSets(g_dev, g_dpool, 1, &g_scene.ds);
    if (g_scene.view) g_vk.DestroyImageView(g_dev, g_scene.view, NULL);
    memset(&g_scene, 0, sizeof(g_scene));
}

static int scene_ensure(VkImage img, int w, int h) {
    if (g_scene.img == img && g_scene.w == w && g_scene.h == h && g_scene.ds) return 0;
    scene_release();
    if (!(g_scene.view = make_view(img, g_scene_fmt))) return -1;
    if (!(g_scene.ds = alloc_ds(g_scene.view))) { scene_release(); return -1; }
    g_scene.img = img; g_scene.w = w; g_scene.h = h;
    return 0;
}

static VkShaderModule shader(const uint32_t *code, size_t size) {
    VkShaderModule m = VK_NULL_HANDLE;
    VkShaderModuleCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, .codeSize = size, .pCode = code};
    if (g_vk.CreateShaderModule(g_dev, &ci, NULL, &m) != VK_SUCCESS) return VK_NULL_HANDLE;
    return m;
}

/* One full-quad post pipeline: upscale.vert + the effect's fragment shader, opaque, dynamic
 * viewport/scissor — the X11 renderer's createPostPipeline. */
static VkPipeline post_pipeline(VkShaderModule vert, const uint32_t *frag_code, size_t frag_size, const char *name) {
    VkShaderModule frag = shader(frag_code, frag_size);
    if (!frag) { wnc_log("error", "effects: %s: vkCreateShaderModule failed", name); return VK_NULL_HANDLE; }
    VkPipelineShaderStageCreateInfo st[2] = {
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_VERTEX_BIT, .module = vert, .pName = "main"},
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_FRAGMENT_BIT, .module = frag, .pName = "main"}};
    VkPipelineVertexInputStateCreateInfo vi = {.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia = {.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
                                                 .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP};
    VkDynamicState dyn[2] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds = {.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
                                           .dynamicStateCount = 2, .pDynamicStates = dyn};
    VkPipelineViewportStateCreateInfo vp = {.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
                                            .viewportCount = 1, .scissorCount = 1};
    VkPipelineRasterizationStateCreateInfo rs = {.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
                                                 .polygonMode = VK_POLYGON_MODE_FILL, .cullMode = VK_CULL_MODE_NONE,
                                                 .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE, .lineWidth = 1.0f};
    VkPipelineMultisampleStateCreateInfo ms = {.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
                                               .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT};
    VkPipelineColorBlendAttachmentState ba = {.blendEnable = VK_FALSE, .colorWriteMask = 0xF};
    VkPipelineColorBlendStateCreateInfo cb = {.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
                                              .attachmentCount = 1, .pAttachments = &ba};
    VkGraphicsPipelineCreateInfo pi = {.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, .stageCount = 2,
                                       .pStages = st, .pVertexInputState = &vi, .pInputAssemblyState = &ia,
                                       .pViewportState = &vp, .pRasterizationState = &rs, .pMultisampleState = &ms,
                                       .pColorBlendState = &cb, .pDynamicState = &ds, .layout = g_pl,
                                       .renderPass = g_rp, .subpass = 0};
    VkPipeline p = VK_NULL_HANDLE;
    VkResult r = g_vk.CreateGraphicsPipelines(g_dev, VK_NULL_HANDLE, 1, &pi, NULL, &p);
    g_vk.DestroyShaderModule(g_dev, frag, NULL);
    if (r != VK_SUCCESS) { wnc_log("error", "effects: %s: vkCreateGraphicsPipelines %d", name, (int)r); return VK_NULL_HANDLE; }
    return p;
}

static void objects_destroy(void) {
    if (!g_dev) return;
    scene_release();
    target_destroy(&g_t[0]); target_destroy(&g_t[1]); target_destroy(&g_mid);
    for (int i = 0; i < P_COUNT; i++) { if (g_pipe[i]) g_vk.DestroyPipeline(g_dev, g_pipe[i], NULL); g_pipe[i] = VK_NULL_HANDLE; }
    if (g_dpool) g_vk.DestroyDescriptorPool(g_dev, g_dpool, NULL);
    if (g_pl) g_vk.DestroyPipelineLayout(g_dev, g_pl, NULL);
    if (g_dsl) g_vk.DestroyDescriptorSetLayout(g_dev, g_dsl, NULL);
    if (g_sampler) g_vk.DestroySampler(g_dev, g_sampler, NULL);
    if (g_rp) g_vk.DestroyRenderPass(g_dev, g_rp, NULL);
    g_dpool = VK_NULL_HANDLE; g_pl = VK_NULL_HANDLE; g_dsl = VK_NULL_HANDLE; g_sampler = VK_NULL_HANDLE; g_rp = VK_NULL_HANDLE;
    g_ready = 0;
}

void vkp_effects_destroy(void) { objects_destroy(); g_dev = VK_NULL_HANDLE; }

void vkp_effects_set_formats(VkFormat scene_fmt, VkFormat target_fmt) {
    if (target_fmt != g_fx_fmt) {
        /* The render pass, the pipelines and the targets are built for one format: start over. */
        if (g_ready) objects_destroy();
        g_fx_fmt = target_fmt;
        g_scene_fmt = scene_fmt;
        if (g_dev)
            wnc_log("effects", "chain now works in %s (%s)",
                       target_fmt == FX_FORMAT ? "8-bit RGBA"
                       : target_fmt == VK_FORMAT_R16G16B16A16_SFLOAT ? "FP16 RGBA" : "10-bit RGB",
                       target_fmt == FX_FORMAT ? "SDR frames"
                       : target_fmt == VK_FORMAT_R16G16B16A16_SFLOAT ? "the HDR picture for frame generation"
                       : "the HDR composition's picture: PQ, or tone-mapped SDR with HDR output off");
    } else if (scene_fmt != g_scene_fmt) {
        scene_release();
        g_scene_fmt = scene_fmt;
    }
}

/* Build every device object once, on the first active frame. 0 = ok, -1 = the chain is
 * unavailable for this session (logged once; frames fall back to the plain blit). */
static int objects_ensure(void) {
    if (g_ready) return g_ready == 1 ? 0 : -1;
    if (!g_dev || !g_vk.CreateGraphicsPipelines) { g_ready = -1; wnc_log("error", "effects: no device for the chain"); return -1; }
    /* Render pass: one colour target, overwritten whole (DONT_CARE), left SHADER_READ_ONLY for the
     * next pass. The dependencies order this pass after whoever read the target last (previous
     * pass / previous frame's blit) and before whoever samples or blits it next. */
    VkAttachmentDescription att = {.format = g_fx_fmt, .samples = VK_SAMPLE_COUNT_1_BIT,
                                   .loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE, .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
                                   .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE, .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
                                   .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED, .finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkAttachmentReference ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub = {.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS, .colorAttachmentCount = 1, .pColorAttachments = &ref};
    VkSubpassDependency deps[2] = {
        {.srcSubpass = VK_SUBPASS_EXTERNAL, .dstSubpass = 0,
         .srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
         .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
         .srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT,
         .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT},
        {.srcSubpass = 0, .dstSubpass = VK_SUBPASS_EXTERNAL,
         .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
         .dstStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
         .srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
         .dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT}};
    VkRenderPassCreateInfo rci = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO, .attachmentCount = 1, .pAttachments = &att,
                                  .subpassCount = 1, .pSubpasses = &sub, .dependencyCount = 2, .pDependencies = deps};
    if (g_vk.CreateRenderPass(g_dev, &rci, NULL, &g_rp) != VK_SUCCESS) goto fail;

    VkSamplerCreateInfo sci = {.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO, .magFilter = VK_FILTER_LINEAR,
                               .minFilter = VK_FILTER_LINEAR, .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
                               .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                               .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                               .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE};
    if (g_vk.CreateSampler(g_dev, &sci, NULL, &g_sampler) != VK_SUCCESS) goto fail;

    VkDescriptorSetLayoutBinding b = {.binding = 0, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                      .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT};
    VkDescriptorSetLayoutCreateInfo dli = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, .bindingCount = 1, .pBindings = &b};
    if (g_vk.CreateDescriptorSetLayout(g_dev, &dli, NULL, &g_dsl) != VK_SUCCESS) goto fail;
    VkPushConstantRange pcr = {.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, .offset = 0, .size = PC_MAX_SIZE};
    VkPipelineLayoutCreateInfo pli = {.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, .setLayoutCount = 1, .pSetLayouts = &g_dsl,
                                      .pushConstantRangeCount = 1, .pPushConstantRanges = &pcr};
    if (g_vk.CreatePipelineLayout(g_dev, &pli, NULL, &g_pl) != VK_SUCCESS) goto fail;
    VkDescriptorPoolSize ps = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 8};
    VkDescriptorPoolCreateInfo dpi = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
                                      .flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT, .maxSets = 8,
                                      .poolSizeCount = 1, .pPoolSizes = &ps};
    if (g_vk.CreateDescriptorPool(g_dev, &dpi, NULL, &g_dpool) != VK_SUCCESS) goto fail;

    VkShaderModule vert = shader(upscale_vert_code, sizeof(upscale_vert_code));
    if (!vert) goto fail;
#define MK(slot, code, name) do { g_pipe[slot] = post_pipeline(vert, code, sizeof(code), name); if (!g_pipe[slot]) { g_vk.DestroyShaderModule(g_dev, vert, NULL); goto fail; } } while (0)
    MK(P_SGSR, sgsr_code, "sgsr");
    MK(P_SGSR_HQ, sgsr_quality_code, "sgsr_quality");
    MK(P_NIS, nis_code, "nis");
    MK(P_EASU, fsr_easu_code, "fsr_easu");
    MK(P_RCAS, fsr_rcas_code, "fsr_rcas");
    MK(P_CAS, cas_code, "cas");
    MK(P_COLOR, color_code, "color");
    MK(P_FXAA, fxaa_code, "fxaa");
    MK(P_TOON, toon_code, "toon");
    MK(P_HDR, hdr_code, "hdr");
    MK(P_NTSC, ntsc_code, "ntsc");
    MK(P_CRT, crt_code, "crt");
    MK(P_DEBAND, deband_code, "deband");
#undef MK
    g_vk.DestroyShaderModule(g_dev, vert, NULL);
    g_ready = 1;
    wnc_log("effects", "chain ready: 13 passes (SGSR, SGSR HQ, NIS, FSR EASU+RCAS, CAS, colour, FXAA, Toon, HDR, NTSC, CRT, deband) on %s", vkp_gpu_name());
    return 0;
fail:
    objects_destroy();
    g_ready = -1;
    wnc_log("error", "effects: the screen-effect chain could not be built on this driver; effects are off for this session");
    return -1;
}

/* ------------------------------------------------------------------ recording */

static void barrier(VkCommandBuffer cmd, VkImage img, VkImageLayout from, VkImageLayout to,
                    VkPipelineStageFlags src_stage, VkAccessFlags src_access,
                    VkPipelineStageFlags dst_stage, VkAccessFlags dst_access) {
    VkImageMemoryBarrier b = {.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = from, .newLayout = to,
                              .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                              .image = img, .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
                              .srcAccessMask = src_access, .dstAccessMask = dst_access};
    g_vk.CmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, NULL, 0, NULL, 1, &b);
}

/* One pass: sample `src` (a descriptor in SHADER_READ_ONLY_OPTIMAL), draw the full quad into `dst`. */
static void pass(VkCommandBuffer cmd, VkPipeline p, struct fx_target *dst, VkDescriptorSet src,
                 const void *pc, uint32_t pcsz) {
    VkRenderPassBeginInfo rpi = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, .renderPass = g_rp, .framebuffer = dst->fb,
                                 .renderArea = {{0, 0}, {(uint32_t)dst->w, (uint32_t)dst->h}}};
    VkViewport vp = {0, 0, (float)dst->w, (float)dst->h, 0, 1};
    VkRect2D sc = {{0, 0}, {(uint32_t)dst->w, (uint32_t)dst->h}};
    g_vk.CmdBeginRenderPass(cmd, &rpi, VK_SUBPASS_CONTENTS_INLINE);
    g_vk.CmdSetViewport(cmd, 0, 1, &vp);
    g_vk.CmdSetScissor(cmd, 0, 1, &sc);
    g_vk.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, p);
    g_vk.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g_pl, 0, 1, &src, 0, NULL);
    g_vk.CmdPushConstants(cmd, g_pl, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, pcsz, pc);
    g_vk.CmdDraw(cmd, 4, 1, 0, 0);
    g_vk.CmdEndRenderPass(cmd);
}

static const float FULL_NDC[4] = {-1.0f, -1.0f, 1.0f, 1.0f};
#define SET_NDC(pc) memcpy((pc).ndc, FULL_NDC, sizeof(FULL_NDC))

VkImage vkp_effects_run(VkCommandBuffer cmd, VkImage scene, int scene_w, int scene_h,
                        int out_w, int out_h, int *res_w, int *res_h) {
    const struct fx_settings *s;
    vkp_effects_sync();
    s = &g_cur;
    *res_w = scene_w; *res_h = scene_h;

    /* The chain's resolution: the scene's mapped size (X11 runs its effects at the output extent,
     * with the scene already scaled by the base sampler). Clamped so a FILL-mapped 4K scene does
     * not allocate beyond reason; the final blit resizes the remainder. */
    int cw = out_w > 0 ? out_w : scene_w, ch = out_h > 0 ? out_h : scene_h;
    if (cw > FX_MAX_DIM) cw = FX_MAX_DIM;
    if (ch > FX_MAX_DIM) ch = FX_MAX_DIM;
    if (cw < 1) cw = 1;
    if (ch < 1) ch = 1;
    /* Spatial upscalers only have work when the scene is shown larger than it is (the X11
     * renderer's "render below display" gate); Sharpen (RCAS) runs at any size. */
    const int upscaling = cw > scene_w || ch > scene_h;
    int spatial = 0;
    if (spatial_mode(s->scaling) && (s->scaling == 6 || upscaling)) spatial = s->scaling;
    const int nfx = effect_count(s);
    const int resize = cw != scene_w || ch != scene_h;
    /* Passes that write the ping-pong pair: the scaling/resize stage (if any) + the effects.
     * Two targets are needed once any pass reads what an earlier one wrote. */
    const int npasses = ((spatial || resize) ? 1 : 0) + nfx;

    if ((spatial || nfx) && objects_ensure() == 0 && scene_ensure(scene, scene_w, scene_h) == 0 &&
        target_ensure(&g_t[0], cw, ch, "chain") == 0 && (npasses < 2 || target_ensure(&g_t[1], cw, ch, "chain") == 0) &&
        (!(spatial == 4 || spatial == 5) || target_ensure(&g_mid, cw, ch, "EASU") == 0)) {
        /* `cur` = the image the next pass samples; `cur_ds` its descriptor. Every pass writes the
         * target that is not being read (the pair alternates; the scene is read only first). */
        VkDescriptorSet cur_ds;
        struct fx_target *cur = NULL;   /* NULL = the scene image */
#define FREE_TARGET() ((cur == &g_t[0]) ? &g_t[1] : &g_t[0])
        const float sharp01 = (float)s->sharp_pct / 100.0f;

        /* ---- stage 1: scaling (scene -> chain res) */
        if (spatial) {
            barrier(cmd, scene, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            struct fx_target *dst = FREE_TARGET();
            if (spatial == 3 || spatial == 8 || spatial == 7) {
                struct pc_sgsr pc = {0};
                SET_NDC(pc);
                pc.vp[0] = 1.0f / (float)scene_w; pc.vp[1] = 1.0f / (float)scene_h;
                pc.vp[2] = (float)scene_w;        pc.vp[3] = (float)scene_h;
                /* SGSR EdgeSharpness 0.5 (slider 0) .. 4.5 (slider 100); NIS takes the slider as is. */
                pc.edge = spatial == 7 ? sharp01 : 0.5f + sharp01 * 4.0f;
                pass(cmd, g_pipe[spatial == 3 ? P_SGSR : spatial == 8 ? P_SGSR_HQ : P_NIS], dst, g_scene.ds, &pc, sizeof(pc));
            } else if (spatial == 6) {
                /* Sharpen: RCAS fetches the scene 1:1 (outputSize = scene size) while the quad
                 * covers the chain target = nearest scale + sharpen, as the X11 renderer does. */
                struct pc_rcas pc = {0};
                SET_NDC(pc);
                rcas_con(&pc, sharp01);
                pc.outW = (float)scene_w; pc.outH = (float)scene_h;
                pass(cmd, g_pipe[P_RCAS], dst, g_scene.ds, &pc, sizeof(pc));
            } else { /* FSR (4) / FSR Fit (5): EASU scene -> mid, RCAS mid -> dst, both at chain res.
                      * Fill vs fit is the Fullscreen Mode's business here (the mapping), so both
                      * run the same EASU+RCAS pair. */
                struct pc_easu e = {0};
                struct pc_rcas r = {0};
                SET_NDC(e);
                easu_con(&e, (float)scene_w, (float)scene_h, (float)cw, (float)ch);
                e.outW = (float)cw; e.outH = (float)ch;
                pass(cmd, g_pipe[P_EASU], &g_mid, g_scene.ds, &e, sizeof(e));
                SET_NDC(r);
                rcas_con(&r, sharp01);
                r.outW = (float)cw; r.outH = (float)ch;
                pass(cmd, g_pipe[P_RCAS], dst, g_mid.ds, &r, sizeof(r));
            }
            cur = dst; cur_ds = dst->ds;
        } else if (resize) {
            /* No spatial pass: a plain filtered resize to the chain resolution first (Linear or
             * Nearest per the scaling mode), so the effects run at output resolution like X11. */
            struct fx_target *dst = FREE_TARGET();
            barrier(cmd, scene, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            barrier(cmd, dst->img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
            VkImageBlit bl = {.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, .srcOffsets = {{0, 0, 0}, {scene_w, scene_h, 1}},
                              .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, .dstOffsets = {{0, 0, 0}, {cw, ch, 1}}};
            g_vk.CmdBlitImage(cmd, scene, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst->img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                              1, &bl, vkp_effects_blit_filter());
            barrier(cmd, dst->img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            cur = dst; cur_ds = dst->ds;
        } else {
            /* Scene already at output size: the first effect samples it directly. */
            barrier(cmd, scene, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            cur = NULL; cur_ds = g_scene.ds;
        }

        /* ---- stage 2: the effects, in the X11 renderer's locked order
         *      FXAA -> Toon -> Colour -> CAS -> HDR -> NTSC -> CRT -> Debanding (terminal dither).
         * Each writes the free ping-pong target; the one just read becomes free. */
        enum { FX_FXAA, FX_TOON, FX_COLOR, FX_CAS, FX_HDR, FX_NTSC, FX_CRT, FX_DEBAND };
        int order[8], n = 0;
        if (s->fxaa) order[n++] = FX_FXAA;
        if (s->toon) order[n++] = FX_TOON;
        if (s->color_on) order[n++] = FX_COLOR;
        if (cas_active(s)) order[n++] = FX_CAS;
        if (s->hdr_on) order[n++] = FX_HDR;
        if (s->ntsc) order[n++] = FX_NTSC;
        if (s->crt) order[n++] = FX_CRT;
        if (s->deband_on) order[n++] = FX_DEBAND;
        for (int i = 0; i < n; i++) {
            struct fx_target *dst = FREE_TARGET();
            struct pc_res pr = {0};
            SET_NDC(pr);
            pr.res[0] = (float)cw; pr.res[1] = (float)ch;
            switch (order[i]) {
            case FX_FXAA: pass(cmd, g_pipe[P_FXAA], dst, cur_ds, &pr, sizeof(pr)); break;
            case FX_TOON: pass(cmd, g_pipe[P_TOON], dst, cur_ds, &pr, sizeof(pr)); break;
            case FX_COLOR: {
                struct pc_color pc = {0};
                SET_NDC(pc);
                pc.b = s->brightness; pc.c = s->contrast; pc.g = s->gamma; pc.s = s->saturation;
                pass(cmd, g_pipe[P_COLOR], dst, cur_ds, &pc, sizeof(pc));
                break;
            }
            case FX_CAS: pr.extra = (float)s->cas_pct / 100.0f; pass(cmd, g_pipe[P_CAS], dst, cur_ds, &pr, sizeof(pr)); break;
            case FX_HDR: pass(cmd, g_pipe[P_HDR], dst, cur_ds, &pr, sizeof(pr)); break;
            case FX_NTSC:
                /* cos/sin(chromaPhase*0.5) has period 4 in the counter: mod 4 keeps the shimmer exact. */
                pr.extra = (float)(g_ntsc_frame++ & 3u);
                pass(cmd, g_pipe[P_NTSC], dst, cur_ds, &pr, sizeof(pr));
                break;
            case FX_CRT: pass(cmd, g_pipe[P_CRT], dst, cur_ds, &pr, sizeof(pr)); break;
            case FX_DEBAND: pr.extra = (float)s->deband_pct / 100.0f; pass(cmd, g_pipe[P_DEBAND], dst, cur_ds, &pr, sizeof(pr)); break;
            }
            cur = dst; cur_ds = dst->ds;
        }

#undef FREE_TARGET
        if (cur) {
            /* The result: written by the last render pass, now for the output blit. */
            barrier(cmd, cur->img, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            *res_w = cur->w; *res_h = cur->h;
            return cur->img;
        }
        /* Nothing ran after all (cannot happen with nfx > 0): the scene was moved to
         * SHADER_READ_ONLY above; bring it to TRANSFER_SRC for the blit. */
        barrier(cmd, scene, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        return scene;
    }

    /* Nothing to do (a spatial mode with no room to upscale and no effect), or the chain is
     * unavailable: present the scene as composited. */
    barrier(cmd, scene, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
    return scene;
}
