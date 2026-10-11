/* HDR composition: the encode pass over the mixed scene - see hdr_compose.h. Compositor thread. */
#define _POSIX_C_SOURCE 200809L
#include "hdr_compose.h"
#include "vk_loader.h"
#include "vk_present.h" /* wnc_log, vkp_gpu_name */
#include <stdio.h>
#include <string.h>

/* SPIR-V, pre-compiled (glslangValidator -V hdr_encode.frag --vn hdr_encode_code) and committed next
 * to the GLSL, like every other shader of the compositor: the NDK build compiles no shaders. The quad
 * is the effect chain's upscale.vert (same bytes, from app/src/main/cpp/winlator). */
/* The headers define plain (external) arrays and effects_chain.c includes upscale_vert.h too: this
 * translation unit gets its own copy under another name, so the link sees no duplicate symbol. */
#define upscale_vert_code hdrc_upscale_vert_code
#include "upscale_vert.h"
#undef upscale_vert_code
#include "hdr_encode_frag.h"

#define MIXED_FORMAT VK_FORMAT_A2B10G10R10_UNORM_PACK32

struct pc_encode { float ndc[4]; float params[4]; float rects[HDRC_MAX_RECTS][4]; }; /* 128 bytes */

static VkDevice g_dev;
static int g_ready;                 /* 0 = not built, 1 = ok, -1 = unavailable (said once) */
static VkSampler g_sampler;
static VkDescriptorSetLayout g_dsl;
static VkPipelineLayout g_pl;
static VkDescriptorPool g_dpool;
static VkShaderModule g_vert, g_frag;

/* One render pass + pipeline per output format: the HDR picture is 10-bit, frame generation's scene
 * 8-bit - or FP16 where the engine takes it (vk_present.c FG_HDR_FMT). */
#define NFMT 3
static struct { VkFormat fmt; VkRenderPass rp; VkPipeline pipe; } g_out[NFMT] = {
    {VK_FORMAT_A2B10G10R10_UNORM_PACK32}, {VK_FORMAT_R8G8B8A8_UNORM}, {VK_FORMAT_R16G16B16A16_SFLOAT}};

/* The mixed image's view + descriptor, and each output image's view + framebuffer: kept while the
 * image handle (and size) stays the same, rebuilt when vk_present re-creates the image. */
static struct { VkImage img; int w, h; VkImageView view; VkDescriptorSet ds; } g_src;
#define NOUT 3
static struct { VkImage img; VkFormat fmt; int w, h; VkImageView view; VkFramebuffer fb; } g_dst[NOUT];
static int g_dst_next;

void hdrc_bind_device(VkDevice dev, const VkPhysicalDeviceMemoryProperties *memprops) {
    (void)memprops;
    g_dev = dev;
}

static void src_release(void) {
    if (g_src.ds) g_vk.FreeDescriptorSets(g_dev, g_dpool, 1, &g_src.ds);
    if (g_src.view) g_vk.DestroyImageView(g_dev, g_src.view, NULL);
    memset(&g_src, 0, sizeof(g_src));
}

static void dst_release(int i) {
    if (g_dst[i].fb) g_vk.DestroyFramebuffer(g_dev, g_dst[i].fb, NULL);
    if (g_dst[i].view) g_vk.DestroyImageView(g_dev, g_dst[i].view, NULL);
    memset(&g_dst[i], 0, sizeof(g_dst[i]));
}

void hdrc_forget_image(VkImage img) {
    if (!g_dev || !img) return;
    if (g_src.img == img) src_release();
    for (int i = 0; i < NOUT; i++) if (g_dst[i].img == img) dst_release(i);
}

void hdrc_destroy(void) {
    if (!g_dev) return;
    src_release();
    for (int i = 0; i < NOUT; i++) dst_release(i);
    for (int i = 0; i < NFMT; i++) {
        if (g_out[i].pipe) g_vk.DestroyPipeline(g_dev, g_out[i].pipe, NULL);
        if (g_out[i].rp) g_vk.DestroyRenderPass(g_dev, g_out[i].rp, NULL);
        g_out[i].pipe = VK_NULL_HANDLE; g_out[i].rp = VK_NULL_HANDLE;
    }
    if (g_vert) g_vk.DestroyShaderModule(g_dev, g_vert, NULL);
    if (g_frag) g_vk.DestroyShaderModule(g_dev, g_frag, NULL);
    if (g_dpool) g_vk.DestroyDescriptorPool(g_dev, g_dpool, NULL);
    if (g_pl) g_vk.DestroyPipelineLayout(g_dev, g_pl, NULL);
    if (g_dsl) g_vk.DestroyDescriptorSetLayout(g_dev, g_dsl, NULL);
    if (g_sampler) g_vk.DestroySampler(g_dev, g_sampler, NULL);
    g_vert = g_frag = VK_NULL_HANDLE; g_dpool = VK_NULL_HANDLE; g_pl = VK_NULL_HANDLE;
    g_dsl = VK_NULL_HANDLE; g_sampler = VK_NULL_HANDLE;
    g_ready = 0;
    g_dev = VK_NULL_HANDLE;
}

static VkShaderModule shader(const uint32_t *code, size_t size) {
    VkShaderModule m = VK_NULL_HANDLE;
    VkShaderModuleCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, .codeSize = size, .pCode = code};
    if (g_vk.CreateShaderModule(g_dev, &ci, NULL, &m) != VK_SUCCESS) return VK_NULL_HANDLE;
    return m;
}

/* The render pass: one colour target written whole (DONT_CARE), left in TRANSFER_DST_OPTIMAL for the
 * effects chain / the copies that follow. The dependencies order it after whoever read or wrote the
 * target last (last frame's blit or chain) and before whoever samples, blits or copies it next. */
static VkRenderPass make_rp(VkFormat fmt) {
    VkAttachmentDescription att = {.format = fmt, .samples = VK_SAMPLE_COUNT_1_BIT,
                                   .loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE, .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
                                   .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
                                   .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
                                   .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                                   .finalLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL};
    VkAttachmentReference ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub = {.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS, .colorAttachmentCount = 1,
                                .pColorAttachments = &ref};
    VkSubpassDependency deps[2] = {
        {.srcSubpass = VK_SUBPASS_EXTERNAL, .dstSubpass = 0,
         .srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT |
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
         .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
         .srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
         .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT},
        {.srcSubpass = 0, .dstSubpass = VK_SUBPASS_EXTERNAL,
         .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
         .dstStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT |
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
         .srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
         .dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT}};
    VkRenderPassCreateInfo rci = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO, .attachmentCount = 1,
                                  .pAttachments = &att, .subpassCount = 1, .pSubpasses = &sub,
                                  .dependencyCount = 2, .pDependencies = deps};
    VkRenderPass rp = VK_NULL_HANDLE;
    if (g_vk.CreateRenderPass(g_dev, &rci, NULL, &rp) != VK_SUCCESS) return VK_NULL_HANDLE;
    return rp;
}

static VkPipeline make_pipe(VkRenderPass rp) {
    VkPipelineShaderStageCreateInfo st[2] = {
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_VERTEX_BIT,
         .module = g_vert, .pName = "main"},
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_FRAGMENT_BIT,
         .module = g_frag, .pName = "main"}};
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
                                       .renderPass = rp, .subpass = 0};
    VkPipeline p = VK_NULL_HANDLE;
    if (g_vk.CreateGraphicsPipelines(g_dev, VK_NULL_HANDLE, 1, &pi, NULL, &p) != VK_SUCCESS) return VK_NULL_HANDLE;
    return p;
}

static int objects_ensure(void) {
    if (g_ready) return g_ready == 1 ? 0 : -1;
    g_ready = -1;
    if (!g_dev || !g_vk.CreateGraphicsPipelines) goto fail;
    VkSamplerCreateInfo sci = {.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO, .magFilter = VK_FILTER_NEAREST,
                               .minFilter = VK_FILTER_NEAREST, .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
                               .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                               .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                               .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE};
    if (g_vk.CreateSampler(g_dev, &sci, NULL, &g_sampler) != VK_SUCCESS) goto fail;
    VkDescriptorSetLayoutBinding b = {.binding = 0, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                      .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT};
    VkDescriptorSetLayoutCreateInfo dli = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
                                           .bindingCount = 1, .pBindings = &b};
    if (g_vk.CreateDescriptorSetLayout(g_dev, &dli, NULL, &g_dsl) != VK_SUCCESS) goto fail;
    VkPushConstantRange pcr = {.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                               .offset = 0, .size = sizeof(struct pc_encode)};
    VkPipelineLayoutCreateInfo pli = {.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, .setLayoutCount = 1,
                                      .pSetLayouts = &g_dsl, .pushConstantRangeCount = 1, .pPushConstantRanges = &pcr};
    if (g_vk.CreatePipelineLayout(g_dev, &pli, NULL, &g_pl) != VK_SUCCESS) goto fail;
    VkDescriptorPoolSize ps = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2};
    VkDescriptorPoolCreateInfo dpi = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
                                      .flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT, .maxSets = 2,
                                      .poolSizeCount = 1, .pPoolSizes = &ps};
    if (g_vk.CreateDescriptorPool(g_dev, &dpi, NULL, &g_dpool) != VK_SUCCESS) goto fail;
    if (!(g_vert = shader(hdrc_upscale_vert_code, sizeof(hdrc_upscale_vert_code)))) goto fail;
    if (!(g_frag = shader(hdr_encode_code, sizeof(hdr_encode_code)))) goto fail;
    for (int i = 0; i < NFMT; i++) {
        if (!(g_out[i].rp = make_rp(g_out[i].fmt))) goto fail;
        if (!(g_out[i].pipe = make_pipe(g_out[i].rp))) goto fail;
    }
    g_ready = 1;
    wnc_log("color", "HDR composition ready on %s: scenes that mix HDR and SDR content are put into one "
               "encoding (PQ BT.2020, or tone-mapped sRGB) instead of being shown washed out", vkp_gpu_name());
    return 0;
fail:
    {
        VkDevice keep = g_dev;
        hdrc_destroy();
        g_dev = keep;
    }
    g_ready = -1;
    wnc_log("error", "color: the HDR composition pass could not be built on this driver; HDR scenes that cannot "
               "stay on the game's own layer are shown the old way (washed out) this session");
    return -1;
}

static VkImageView make_view(VkImage img, VkFormat fmt) {
    VkImageView v = VK_NULL_HANDLE;
    VkImageViewCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = img,
                                .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = fmt,
                                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    if (g_vk.CreateImageView(g_dev, &ci, NULL, &v) != VK_SUCCESS) return VK_NULL_HANDLE;
    return v;
}

static int src_ensure(VkImage img, int w, int h) {
    if (g_src.img == img && g_src.w == w && g_src.h == h && g_src.ds) return 0;
    src_release();
    if (!(g_src.view = make_view(img, MIXED_FORMAT))) return -1;
    VkDescriptorSetAllocateInfo ai = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
                                      .descriptorPool = g_dpool, .descriptorSetCount = 1, .pSetLayouts = &g_dsl};
    if (g_vk.AllocateDescriptorSets(g_dev, &ai, &g_src.ds) != VK_SUCCESS) { g_src.ds = VK_NULL_HANDLE; src_release(); return -1; }
    VkDescriptorImageInfo ii = {.sampler = g_sampler, .imageView = g_src.view,
                                .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet wr = {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = g_src.ds, .dstBinding = 0,
                               .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                               .pImageInfo = &ii};
    g_vk.UpdateDescriptorSets(g_dev, 1, &wr, 0, NULL);
    g_src.img = img; g_src.w = w; g_src.h = h;
    return 0;
}

static int fmt_slot(VkFormat fmt) {
    for (int i = 0; i < NFMT; i++) if (g_out[i].fmt == fmt) return i;
    return -1;
}

static VkFramebuffer dst_ensure(VkImage img, VkFormat fmt, int w, int h, VkRenderPass rp) {
    for (int i = 0; i < NOUT; i++)
        if (g_dst[i].img == img && g_dst[i].fmt == fmt && g_dst[i].w == w && g_dst[i].h == h && g_dst[i].fb)
            return g_dst[i].fb;
    int i = g_dst_next;
    g_dst_next = (g_dst_next + 1) % NOUT;
    dst_release(i);
    if (!(g_dst[i].view = make_view(img, fmt))) return VK_NULL_HANDLE;
    VkFramebufferCreateInfo fi = {.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, .renderPass = rp,
                                  .attachmentCount = 1, .pAttachments = &g_dst[i].view, .width = (uint32_t)w,
                                  .height = (uint32_t)h, .layers = 1};
    if (g_vk.CreateFramebuffer(g_dev, &fi, NULL, &g_dst[i].fb) != VK_SUCCESS) { g_dst[i].fb = VK_NULL_HANDLE; dst_release(i); return VK_NULL_HANDLE; }
    g_dst[i].img = img; g_dst[i].fmt = fmt; g_dst[i].w = w; g_dst[i].h = h;
    return g_dst[i].fb;
}

int hdrc_encode(VkCommandBuffer cmd, VkImage mixed, VkImage out, VkFormat out_fmt, int w, int h,
                const struct hdrc_params *p) {
    if (!mixed || !out || w <= 0 || h <= 0 || !p || objects_ensure() != 0) return -1;
    int slot = fmt_slot(out_fmt);
    if (slot < 0) return -1;
    if (src_ensure(mixed, w, h) != 0) return -1;
    VkFramebuffer fb = dst_ensure(out, out_fmt, w, h, g_out[slot].rp);
    if (!fb) return -1;

    /* The composite blits wrote the mixed image; the pass samples it. */
    VkImageMemoryBarrier b = {.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                              .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                              .newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                              .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                              .image = mixed, .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
                              .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT, .dstAccessMask = VK_ACCESS_SHADER_READ_BIT};
    g_vk.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                            0, 0, NULL, 0, NULL, 1, &b);

    struct pc_encode pc;
    memset(&pc, 0, sizeof(pc));
    pc.ndc[0] = -1.0f; pc.ndc[1] = -1.0f; pc.ndc[2] = 1.0f; pc.ndc[3] = 1.0f;
    int count = p->count < 0 ? 0 : p->count > HDRC_MAX_RECTS ? HDRC_MAX_RECTS : p->count;
    unsigned mask = p->hdr_mask & ((1u << HDRC_MAX_RECTS) - 1u);
    pc.params[0] = p->sdr_white_nits > 0.0f ? p->sdr_white_nits : 203.0f;
    pc.params[1] = p->peak_nits > 0.0f ? p->peak_nits : 1000.0f;
    pc.params[2] = p->mode == HDRC_OUT_SDR ? 1.0f : 0.0f;
    pc.params[3] = (float)count + 16.0f * (float)mask;
    for (int i = 0; i < count; i++) memcpy(pc.rects[i], p->rects[i], sizeof(pc.rects[i]));

    VkRenderPassBeginInfo rpi = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, .renderPass = g_out[slot].rp,
                                 .framebuffer = fb, .renderArea = {{0, 0}, {(uint32_t)w, (uint32_t)h}}};
    VkViewport vp = {0, 0, (float)w, (float)h, 0, 1};
    VkRect2D sc = {{0, 0}, {(uint32_t)w, (uint32_t)h}};
    g_vk.CmdBeginRenderPass(cmd, &rpi, VK_SUBPASS_CONTENTS_INLINE);
    g_vk.CmdSetViewport(cmd, 0, 1, &vp);
    g_vk.CmdSetScissor(cmd, 0, 1, &sc);
    g_vk.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g_out[slot].pipe);
    g_vk.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g_pl, 0, 1, &g_src.ds, 0, NULL);
    g_vk.CmdPushConstants(cmd, g_pl, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc);
    g_vk.CmdDraw(cmd, 4, 1, 0, 0);
    g_vk.CmdEndRenderPass(cmd);
    return 0;
}
