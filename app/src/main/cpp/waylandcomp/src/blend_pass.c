/* Alpha composition: one surface drawn source-over onto the scene - see blend_pass.h. Compositor thread. */
#define _POSIX_C_SOURCE 200809L
#include "blend_pass.h"
#include "vk_loader.h"
#include "vk_present.h" /* wnc_log, vkp_gpu_name */
#include <string.h>

/* SPIR-V, pre-compiled (glslangValidator -V blend.vert --vn blend_vert_code, the same for blend.frag)
 * and committed next to the GLSL: the NDK build compiles no shaders. */
#include "blend_vert.h"
#include "blend_frag.h"

struct pc_blend { float dst[4]; float src[4]; };

static VkDevice g_dev;
static int g_ready;                 /* 0 = not built, 1 = ok, -1 = unavailable (said once) */
static VkSampler g_sampler;
static VkDescriptorSetLayout g_dsl;
static VkPipelineLayout g_pl;
static VkDescriptorPool g_dpool;
static VkShaderModule g_vert, g_frag;
static unsigned g_frame = 1;

/* One render pass + pipeline per target format: the screen swapchain's, the scene image's, the HDR
 * composition's. */
#define NFMT 4
static struct { VkFormat fmt; VkRenderPass rp; VkPipeline pipe; } g_out[NFMT];

#define NSRC 32
static struct { VkImage img; VkImageView view; VkDescriptorSet ds; unsigned frame; } g_src[NSRC];
#define NDST 12
static struct { VkImage img; VkFormat fmt; int w, h; VkImageView view; VkFramebuffer fb; unsigned frame; } g_dst[NDST];

void blendp_bind_device(VkDevice dev) { g_dev = dev; }

void blendp_begin_frame(void) { g_frame++; }

static void src_release(int i) {
    if (g_src[i].ds) g_vk.FreeDescriptorSets(g_dev, g_dpool, 1, &g_src[i].ds);
    if (g_src[i].view) g_vk.DestroyImageView(g_dev, g_src[i].view, NULL);
    memset(&g_src[i], 0, sizeof(g_src[i]));
}

static void dst_release(int i) {
    if (g_dst[i].fb) g_vk.DestroyFramebuffer(g_dev, g_dst[i].fb, NULL);
    if (g_dst[i].view) g_vk.DestroyImageView(g_dev, g_dst[i].view, NULL);
    memset(&g_dst[i], 0, sizeof(g_dst[i]));
}

void blendp_forget_image(VkImage img) {
    if (!g_dev || !img) return;
    for (int i = 0; i < NSRC; i++) if (g_src[i].img == img) src_release(i);
    for (int i = 0; i < NDST; i++) if (g_dst[i].img == img) dst_release(i);
}

static VkShaderModule shader(const uint32_t *code, size_t size) {
    VkShaderModule m = VK_NULL_HANDLE;
    VkShaderModuleCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, .codeSize = size, .pCode = code};
    if (g_vk.CreateShaderModule(g_dev, &ci, NULL, &m) != VK_SUCCESS) return VK_NULL_HANDLE;
    return m;
}

/* The target keeps what the blits put there (LOAD) and stays in TRANSFER_DST_OPTIMAL for the blits
 * that follow. The dependencies order the pass between them. */
static VkRenderPass make_rp(VkFormat fmt) {
    VkAttachmentDescription att = {.format = fmt, .samples = VK_SAMPLE_COUNT_1_BIT,
                                   .loadOp = VK_ATTACHMENT_LOAD_OP_LOAD, .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
                                   .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
                                   .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
                                   .initialLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                   .finalLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL};
    VkAttachmentReference ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub = {.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS, .colorAttachmentCount = 1,
                                .pColorAttachments = &ref};
    VkSubpassDependency deps[2] = {
        {.srcSubpass = VK_SUBPASS_EXTERNAL, .dstSubpass = 0,
         .srcStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
         .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
         .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
         .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT},
        {.srcSubpass = 0, .dstSubpass = VK_SUBPASS_EXTERNAL,
         .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
         .dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
         .srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
         .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT |
                          VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT}};
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
    /* The target's alpha is left alone: the screen is opaque whatever is drawn onto it. */
    VkPipelineColorBlendAttachmentState ba = {
        .blendEnable = VK_TRUE, .srcColorBlendFactor = VK_BLEND_FACTOR_ONE,
        .dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, .colorBlendOp = VK_BLEND_OP_ADD,
        .srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE, .dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
        .alphaBlendOp = VK_BLEND_OP_ADD,
        .colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT};
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

static void objects_destroy(void) {
    if (g_vert) g_vk.DestroyShaderModule(g_dev, g_vert, NULL);
    if (g_frag) g_vk.DestroyShaderModule(g_dev, g_frag, NULL);
    if (g_dpool) g_vk.DestroyDescriptorPool(g_dev, g_dpool, NULL);
    if (g_pl) g_vk.DestroyPipelineLayout(g_dev, g_pl, NULL);
    if (g_dsl) g_vk.DestroyDescriptorSetLayout(g_dev, g_dsl, NULL);
    if (g_sampler) g_vk.DestroySampler(g_dev, g_sampler, NULL);
    g_vert = g_frag = VK_NULL_HANDLE; g_dpool = VK_NULL_HANDLE; g_pl = VK_NULL_HANDLE;
    g_dsl = VK_NULL_HANDLE; g_sampler = VK_NULL_HANDLE;
}

static int objects_ensure(void) {
    if (g_ready) return g_ready == 1 ? 0 : -1;
    g_ready = -1;
    if (!g_dev || !g_vk.CreateGraphicsPipelines) goto fail;
    VkSamplerCreateInfo sci = {.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO, .magFilter = VK_FILTER_LINEAR,
                               .minFilter = VK_FILTER_LINEAR, .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
                               .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                               .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                               .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE};
    if (g_vk.CreateSampler(g_dev, &sci, NULL, &g_sampler) != VK_SUCCESS) goto fail;
    VkDescriptorSetLayoutBinding b = {.binding = 0, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                      .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT};
    VkDescriptorSetLayoutCreateInfo dli = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
                                           .bindingCount = 1, .pBindings = &b};
    if (g_vk.CreateDescriptorSetLayout(g_dev, &dli, NULL, &g_dsl) != VK_SUCCESS) goto fail;
    VkPushConstantRange pcr = {.stageFlags = VK_SHADER_STAGE_VERTEX_BIT, .offset = 0, .size = sizeof(struct pc_blend)};
    VkPipelineLayoutCreateInfo pli = {.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, .setLayoutCount = 1,
                                      .pSetLayouts = &g_dsl, .pushConstantRangeCount = 1, .pPushConstantRanges = &pcr};
    if (g_vk.CreatePipelineLayout(g_dev, &pli, NULL, &g_pl) != VK_SUCCESS) goto fail;
    VkDescriptorPoolSize ps = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, NSRC};
    VkDescriptorPoolCreateInfo dpi = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
                                      .flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT, .maxSets = NSRC,
                                      .poolSizeCount = 1, .pPoolSizes = &ps};
    if (g_vk.CreateDescriptorPool(g_dev, &dpi, NULL, &g_dpool) != VK_SUCCESS) goto fail;
    if (!(g_vert = shader(blend_vert_code, sizeof(blend_vert_code)))) goto fail;
    if (!(g_frag = shader(blend_frag_code, sizeof(blend_frag_code)))) goto fail;
    g_ready = 1;
    wnc_log("gpu", "alpha composition ready on %s: translucent surfaces are blended over what is under them",
            vkp_gpu_name());
    return 0;
fail:
    objects_destroy();
    wnc_log("error", "gpu: the alpha composition pass could not be built on this driver; translucent surfaces "
               "are drawn opaque this session");
    return -1;
}

static int out_slot(VkFormat fmt) {
    int i;
    for (i = 0; i < NFMT && g_out[i].pipe; i++) if (g_out[i].fmt == fmt) return i;
    if (i == NFMT) return -1;
    VkRenderPass rp = make_rp(fmt);
    VkPipeline pipe = rp ? make_pipe(rp) : VK_NULL_HANDLE;
    if (!pipe) {
        if (rp) g_vk.DestroyRenderPass(g_dev, rp, NULL);
        return -1;
    }
    g_out[i].fmt = fmt; g_out[i].rp = rp; g_out[i].pipe = pipe;
    return i;
}

static VkImageView make_view(VkImage img, VkFormat fmt) {
    VkImageView v = VK_NULL_HANDLE;
    VkImageViewCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = img,
                                .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = fmt,
                                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    if (g_vk.CreateImageView(g_dev, &ci, NULL, &v) != VK_SUCCESS) return VK_NULL_HANDLE;
    return v;
}

/* The slot to recycle: a free one, else the one unused for longest - never one this command buffer
 * already refers to. -1 = every slot is in this frame. */
#define OLDEST(arr, n, out) do { \
        unsigned age_ = 0; \
        (out) = -1; \
        for (int k_ = 0; k_ < (n); k_++) { \
            if (!(arr)[k_].img) { (out) = k_; break; } \
            if ((arr)[k_].frame != g_frame && g_frame - (arr)[k_].frame > age_) { age_ = g_frame - (arr)[k_].frame; (out) = k_; } \
        } \
    } while (0)

static VkDescriptorSet src_ensure(VkImage img, VkFormat fmt, VkImageLayout layout) {
    int i;
    for (i = 0; i < NSRC; i++)
        if (g_src[i].img == img) { g_src[i].frame = g_frame; return g_src[i].ds; }
    OLDEST(g_src, NSRC, i);
    if (i < 0) return VK_NULL_HANDLE;
    src_release(i);
    if (!(g_src[i].view = make_view(img, fmt))) return VK_NULL_HANDLE;
    VkDescriptorSetAllocateInfo ai = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
                                      .descriptorPool = g_dpool, .descriptorSetCount = 1, .pSetLayouts = &g_dsl};
    if (g_vk.AllocateDescriptorSets(g_dev, &ai, &g_src[i].ds) != VK_SUCCESS) {
        g_src[i].ds = VK_NULL_HANDLE;
        src_release(i);
        return VK_NULL_HANDLE;
    }
    VkDescriptorImageInfo ii = {.sampler = g_sampler, .imageView = g_src[i].view, .imageLayout = layout};
    VkWriteDescriptorSet wr = {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = g_src[i].ds, .dstBinding = 0,
                               .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                               .pImageInfo = &ii};
    g_vk.UpdateDescriptorSets(g_dev, 1, &wr, 0, NULL);
    g_src[i].img = img; g_src[i].frame = g_frame;
    return g_src[i].ds;
}

static VkFramebuffer dst_ensure(VkImage img, VkFormat fmt, int w, int h, VkRenderPass rp) {
    int i;
    for (i = 0; i < NDST; i++) {
        if (g_dst[i].img != img) continue;
        if (g_dst[i].fmt == fmt && g_dst[i].w == w && g_dst[i].h == h) { g_dst[i].frame = g_frame; return g_dst[i].fb; }
        if (g_dst[i].frame == g_frame) return VK_NULL_HANDLE;
        break;
    }
    if (i == NDST) OLDEST(g_dst, NDST, i);
    if (i < 0) return VK_NULL_HANDLE;
    dst_release(i);
    if (!(g_dst[i].view = make_view(img, fmt))) return VK_NULL_HANDLE;
    VkFramebufferCreateInfo fi = {.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, .renderPass = rp,
                                  .attachmentCount = 1, .pAttachments = &g_dst[i].view, .width = (uint32_t)w,
                                  .height = (uint32_t)h, .layers = 1};
    if (g_vk.CreateFramebuffer(g_dev, &fi, NULL, &g_dst[i].fb) != VK_SUCCESS) {
        g_dst[i].fb = VK_NULL_HANDLE;
        dst_release(i);
        return VK_NULL_HANDLE;
    }
    g_dst[i].img = img; g_dst[i].fmt = fmt; g_dst[i].w = w; g_dst[i].h = h; g_dst[i].frame = g_frame;
    return g_dst[i].fb;
}

static void src_layout(VkCommandBuffer cmd, VkImage img, VkImageLayout from, VkImageLayout to) {
    int sampling = to == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkImageMemoryBarrier b = {.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = from, .newLayout = to,
                              .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                              .image = img, .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
                              .srcAccessMask = sampling ? VK_ACCESS_TRANSFER_READ_BIT : VK_ACCESS_SHADER_READ_BIT,
                              .dstAccessMask = sampling ? VK_ACCESS_SHADER_READ_BIT : VK_ACCESS_TRANSFER_READ_BIT};
    g_vk.CmdPipelineBarrier(cmd, sampling ? VK_PIPELINE_STAGE_TRANSFER_BIT : VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                            sampling ? VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : VK_PIPELINE_STAGE_TRANSFER_BIT,
                            0, 0, NULL, 0, NULL, 1, &b);
}

int blendp_draw(VkCommandBuffer cmd, VkImage src, VkFormat src_fmt, int src_w, int src_h, int src_general,
                VkImage dst, VkFormat dst_fmt, int dst_w, int dst_h, const VkImageBlit *blit) {
    if (!src || !dst || src_w <= 0 || src_h <= 0 || dst_w <= 0 || dst_h <= 0 || objects_ensure() != 0) return -1;
    int slot = out_slot(dst_fmt);
    if (slot < 0) return -1;
    VkImageLayout sampled = src_general ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkDescriptorSet ds = src_ensure(src, src_fmt, sampled);
    if (!ds) return -1;
    VkFramebuffer fb = dst_ensure(dst, dst_fmt, dst_w, dst_h, g_out[slot].rp);
    if (!fb) return -1;

    const VkOffset3D *d = blit->dstOffsets, *s = blit->srcOffsets;
    int x0 = d[0].x < d[1].x ? d[0].x : d[1].x, x1 = d[0].x < d[1].x ? d[1].x : d[0].x;
    int y0 = d[0].y < d[1].y ? d[0].y : d[1].y, y1 = d[0].y < d[1].y ? d[1].y : d[0].y;
    if (x0 < 0) x0 = 0;
    if (y0 < 0) y0 = 0;
    if (x1 > dst_w) x1 = dst_w;
    if (y1 > dst_h) y1 = dst_h;
    if (x1 <= x0 || y1 <= y0) return 0;

    struct pc_blend pc = {
        .dst = {2.0f * d[0].x / dst_w - 1.0f, 2.0f * d[0].y / dst_h - 1.0f,
                2.0f * d[1].x / dst_w - 1.0f, 2.0f * d[1].y / dst_h - 1.0f},
        .src = {(float)s[0].x / src_w, (float)s[0].y / src_h, (float)s[1].x / src_w, (float)s[1].y / src_h}};

    if (!src_general) src_layout(cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, sampled);
    VkRenderPassBeginInfo rpi = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, .renderPass = g_out[slot].rp,
                                 .framebuffer = fb,
                                 .renderArea = {{x0, y0}, {(uint32_t)(x1 - x0), (uint32_t)(y1 - y0)}}};
    VkViewport vp = {0, 0, (float)dst_w, (float)dst_h, 0, 1};
    g_vk.CmdBeginRenderPass(cmd, &rpi, VK_SUBPASS_CONTENTS_INLINE);
    g_vk.CmdSetViewport(cmd, 0, 1, &vp);
    g_vk.CmdSetScissor(cmd, 0, 1, &rpi.renderArea);
    g_vk.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g_out[slot].pipe);
    g_vk.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g_pl, 0, 1, &ds, 0, NULL);
    g_vk.CmdPushConstants(cmd, g_pl, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(pc), &pc);
    g_vk.CmdDraw(cmd, 4, 1, 0, 0);
    g_vk.CmdEndRenderPass(cmd);
    if (!src_general) src_layout(cmd, src, sampled, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    return 0;
}
