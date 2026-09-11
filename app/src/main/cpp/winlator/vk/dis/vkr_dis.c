#include "vkr_dis.h"

#include "../vk_dispatch.h"
#include "shaders/dis_luma_r16_comp.spv.h"
#include "shaders/dis_luma_r32_comp.spv.h"
#include "shaders/dis_gradient_comp.spv.h"
#include "shaders/dis_inverse_search_comp.spv.h"
#include "shaders/dis_propagate_comp.spv.h"
#include "shaders/dis_densify_comp.spv.h"
#include "shaders/dis_interpolate_comp.spv.h"
#include "shaders/dis_vr_prep_comp.spv.h"
#include "shaders/dis_vr_d1_comp.spv.h"
#include "shaders/dis_vr_d2_comp.spv.h"
#include "shaders/dis_vr_w_comp.spv.h"
#include "shaders/dis_vr_coef_comp.spv.h"
#include "shaders/dis_vr_sor_comp.spv.h"
#include "shaders/dis_vr_add_comp.spv.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <android/log.h>

#define DIS_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VkrDis", __VA_ARGS__)
#define DIS_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "VkrDis", __VA_ARGS__)

#define DIS_LOCAL_SIZE 8u
#define DIS_PATCH_STRIDE 3u
#define DIS_MIN_EXTENT 16u

// Shorter-side resolution of the flow pyramid. The presets are 180 / 252 / 360,
// which on a 720-tall frame are the old 25% / 35% / 50%. The flow field is smooth
// at the scale that matters for interpolation, and the pyramid is where nearly
// all of this chain's time goes, so the smallest usable one is the default.
#define DIS_DEFAULT_FLOW_MIN_SIDE 180u
#define DIS_FLOW_MIN_SIDE_FLOOR 64u
#define DIS_FLOW_MIN_SIDE_CEIL 1080u
#define DIS_MAX_LEVELS 8u
#define DIS_MAX_DESCRIPTOR_WRITES 256u

// Number of input-frame slots. Must exceed VK_FRAMES_IN_FLIGHT: with two frames
// in flight and only two slots, frame N blits into the very slot frame N-1 is
// still reading as its "prev" input, and the descriptor sets frame N-1 recorded
// get rewritten underneath it. Both hazards only fire once the GPU falls behind
// the CPU, which is why they stayed invisible at 30 fps and corrupted 60 fps.
#define DIS_SLOTS 3u

// Spatial propagation is a jump-flood stand-in for OpenCV's sequential scan, and
// each step costs 4 dispatches over the level's whole sparse grid. It earns that
// cost on the coarse levels, where the flow is still sparse and unreliable; by
// the fine levels the coarse-to-fine initialisation is already close and a step
// there costs 4x what the same step costs one level up. Spend the budget at the
// top of the pyramid: level counts are indexed by distance from the coarsest.
#define DIS_PROP_STEPS_MAX 4u

// Source-rate tracking. Smoothing and the settle threshold mirror the LSFG
// pacer so the two engines behave alike.
#define DIS_SRC_SMOOTHING 0.15f
#define DIS_SRC_STALE_NS 500000000ull
#define DIS_MIN_RATE_SAMPLES 12u

// Variational refinement (SOR) iteration counts and constants. Matches the
// OpenCV defaults used by the reference implementation.
#define DIS_VR_ALPHA 20.0f
#define DIS_VR_DELTA 5.0f
#define DIS_VR_GAMMA 10.0f
#define DIS_VR_OMEGA 1.6f
#define DIS_VR_ZETA 0.1f
#define DIS_VR_EPS 0.001f

// SOR descriptor set layout: 8 samplers + 2 storage images.
// Shape of the shared compute set layout, and how many of them exist per
// pyramid level: gradient, inverse search, densify, the two propagation
// ping-pong sets, and luminance. The descriptor pool is sized from these.
#define DIS_SET_SAMPLERS 5u
#define DIS_SET_STORAGE 1u
#define DIS_SHARED_SETS_PER_LEVEL 6u
#define DIS_VR_SHARED_SETS 7u
#define DIS_VR_SAMPLER_BINDINGS 8u
#define DIS_VR_STORAGE_BINDINGS 2u
#define DIS_VR_FIRST_STORAGE 8u

typedef struct {
    VkImage image;
    VkDeviceMemory memory;
    VkExtent2D extent;
    VkFormat format;
    uint32_t mip_levels;
} DisImage;

typedef struct {
    VkPipeline pipeline;
} DisPass;

typedef struct {
    VkWriteDescriptorSet w[DIS_MAX_DESCRIPTOR_WRITES];
    VkDescriptorImageInfo img[DIS_MAX_DESCRIPTOR_WRITES];
    uint32_t count;
} DisBatch;

struct VkrDis {
    VkDevice device;
    VkPhysicalDevice physical_device;
    VkPhysicalDeviceMemoryProperties mem_props;

    uint32_t flow_min_side;
    uint32_t target_fps;
    float refresh_rate;

    VkExtent2D built_extent;       // flow resolution (content, scaled)
    VkExtent2D built_full_extent;  // composite resolution (container)
    VkrDisContentRect content;     // live sub-rect of the composite, in composite pixels
    uint32_t built_min_side;
    VkFormat built_format;
    uint32_t levels;
    bool built;
    bool unavailable;
    // Cleared by every resource build; the next command buffer transitions all of
    // DIS's images out of UNDEFINED before anything touches them.
    bool layouts_primed;
    // The audit needs the guest frame format, which only arrives with the first
    // prepare, so it runs there rather than at create time - once.
    bool formats_audited;
    // Set when the device cannot filter the R32G32_SFLOAT flow in the sampler,
    // which makes the interpolation pass do the bilinear itself.
    bool manual_flow_filter;
    bool debug_flow;

    DisImage color[DIS_SLOTS];        // full-res input frames (interpolation)
    DisImage flow_color[DIS_SLOTS];   // scaled input frames (flow pyramid)
    DisImage grad;
    // Single-channel luminance pyramid, rebuilt each frame for the slot just
    // ingested. Search, propagation and densify read only luminance; giving them
    // a plane of it halves the bytes each of their millions of fetches moves and
    // takes the dot product out of their inner loops.
    DisImage flow_luma[DIS_SLOTS];
    VkFormat luma_format;
    DisImage flow_sparse[DIS_MAX_LEVELS];     // per-level sparse flow (OpenCV grid)
    DisImage flow_sparse_b[DIS_MAX_LEVELS];   // per-level ping-pong buffer
    DisImage flow_dense;
    DisImage interp_out;      // full-res interpolated output

    // Variational refinement (SOR) intermediates, all at the finest flow level.
    DisImage vr_prep;         // (averagedI, Iz)
    DisImage vr_d1;           // (Ix, Iy, Ixz, Iyz)
    DisImage vr_d2;           // (Ixx, Ixy, Iyy, 0)
    DisImage vr_A;            // (a11, a12, a22, 0)
    DisImage vr_B;            // (b1, b2)
    DisImage vr_wt;           // smoothness weight
    DisImage vr_dw[2];        // SOR ping-pong increment
    DisImage flow_refined;    // refined flow (normalized), SOR output

    VkImageView view_color[DIS_SLOTS];
    VkImageView view_flow_color[DIS_SLOTS][DIS_MAX_LEVELS];
    VkImageView view_flow_luma[DIS_SLOTS][DIS_MAX_LEVELS];
    VkImageView view_grad[DIS_MAX_LEVELS];
    VkImageView view_sparse[DIS_MAX_LEVELS];
    VkImageView view_sparse_b[DIS_MAX_LEVELS];
    VkImageView view_dense[DIS_MAX_LEVELS];
    VkImageView view_interp_out;
    VkImageView view_vr_prep;
    VkImageView view_vr_d1;
    VkImageView view_vr_d2;
    VkImageView view_vr_A;
    VkImageView view_vr_B;
    VkImageView view_vr_wt;
    VkImageView view_vr_dw[2];
    VkImageView view_flow_refined;

    VkSampler sampler;

    VkDescriptorSetLayout set_layout;
    VkPipelineLayout pipeline_layout;
    VkDescriptorPool pool;
    // Sets whose bindings name a specific input slot exist once per slot and are
    // written once at build time, never per frame. That removes the per-frame
    // UpdateDescriptorSets entirely (it was rewriting up to 256 descriptors every
    // frame) and with it the write-while-pending hazard.
    VkDescriptorSet luma_sets[DIS_SLOTS][DIS_MAX_LEVELS];
    VkDescriptorSet grad_sets[DIS_SLOTS][DIS_MAX_LEVELS];
    VkDescriptorSet inverse_sets[DIS_SLOTS][DIS_MAX_LEVELS];
    VkDescriptorSet densify_sets[DIS_SLOTS][DIS_MAX_LEVELS];
    VkDescriptorSet prop_ab_sets[DIS_SLOTS][DIS_MAX_LEVELS];
    VkDescriptorSet prop_ba_sets[DIS_SLOTS][DIS_MAX_LEVELS];
    VkDescriptorSet interp_sets[DIS_SLOTS];

    // SOR (variational refinement) resources.
    VkDescriptorSetLayout vr_set_layout;
    VkPipelineLayout vr_pipeline_layout;
    VkDescriptorSet vr_prep_sets[DIS_SLOTS];
    VkDescriptorSet vr_d1_set;
    VkDescriptorSet vr_d2_set;
    VkDescriptorSet vr_w_set;
    VkDescriptorSet vr_coef_set;
    VkDescriptorSet vr_sor_ab_set;   // reads dw[0], writes dw[1]
    VkDescriptorSet vr_sor_ba_set;   // reads dw[1], writes dw[0]
    VkDescriptorSet vr_add_set;

    DisPass pass_luma;
    DisPass pass_gradient;
    DisPass pass_inverse;
    DisPass pass_propagate;
    DisPass pass_densify;
    DisPass pass_interp;
    DisPass pass_vr_prep;
    DisPass pass_vr_d1;
    DisPass pass_vr_d2;
    DisPass pass_vr_w;
    DisPass pass_vr_coef;
    DisPass pass_vr_sor;
    DisPass pass_vr_add;

    uint64_t frame_count;
    int prev_idx;
    int next_idx;
    uint32_t active_slot;
    uint32_t last_generations;

    // Source rate is measured from the compositor's count of guest frames, NOT
    // from how often this module is called. Each compositor iteration presents
    // 1 + generations images, so the call rate is a function of the generation
    // count: deriving the rate from it made the planner feed back on itself.
    // With 60 fps into a 120 Hz panel that loop has zero headroom, so any hitch
    // drove the measured rate toward 30 and the planner faithfully turned a
    // 60->120 request into a real 30->120 one. 30->60 never showed it because
    // the loop stayed guest-limited with a full 60 Hz of slack.
    uint64_t src_sample_ns;
    uint64_t src_last_frames;
    float src_frame_accum;
    float src_time_accum;
    float src_interval;      // seconds per source frame
    uint32_t src_samples;

    // Smoothed desired (refresh) rate so a throttle step ramps the generation
    // count instead of jumping it.
    float smoothed_desired;

    // Hysteresis state for the generation count: a single source-interval
    // outlier must not flicker the count (e.g. 1 <-> 2 at 60->120).
    int planned_gen;
    uint32_t gen_high_streak;
    uint32_t gen_low_streak;
};

typedef struct {
    float lesser;
    float upper;
    float normVal;
} DisGradientPC;

typedef struct {
    int level;
    int coarseLevel;
} DisInversePC;

typedef struct {
    // Distance to the four neighbours this pass scores. One pass covers all four
    // directions, so the old per-direction offset vector is gone.
    int dist;
} DisPropPC;

typedef struct {
    float t;
    int debugMode;
} DisInterpPC;

typedef struct {
    float alpha2;
    float eps2;
} DisVrWPC;

typedef struct {
    float delta2;
    float gamma2;
    float zeta2;
    float eps2;
} DisVrCoefPC;

typedef struct {
    float omega;
    int parity;
} DisVrSorPC;

static uint64_t dis_now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static uint32_t dis_find_memory_type(VkrDis* d, uint32_t bits, VkMemoryPropertyFlags props) {
    for (uint32_t i = 0; i < d->mem_props.memoryTypeCount; i++) {
        if ((bits & (1u << i)) &&
            (d->mem_props.memoryTypes[i].propertyFlags & props) == props) {
            return i;
        }
    }
    return UINT32_MAX;
}

// A plain execution+memory dependency, with no image or layout attached. Every
// hand-off inside the flow chain is compute-writes -> compute-reads on images
// that are already in GENERAL, so there is no transition to express and naming
// individual images only makes the driver track more than it needs to. One of
// these replaces what used to be up to two image barriers per dispatch.
static void dis_compute_barrier(VkCommandBuffer cmd) {
    VkMemoryBarrier mb;
    memset(&mb, 0, sizeof(mb));
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkd.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
}

static void dis_barrier(VkCommandBuffer cmd, VkImage image, VkImageLayout from, VkImageLayout to,
                        VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage,
                        VkAccessFlags src_access, VkAccessFlags dst_access) {
    VkImageMemoryBarrier b;
    memset(&b, 0, sizeof(b));
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.srcAccessMask = src_access;
    b.dstAccessMask = dst_access;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image;
    b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    b.subresourceRange.levelCount = VK_REMAINING_MIP_LEVELS;
    b.subresourceRange.layerCount = 1;
    vkd.CmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, NULL, 0, NULL, 1, &b);
}

// Every image DIS owns, so the layout priming below and the teardown stay in
// step. Returns how many entries were filled.
static uint32_t dis_collect_images(VkrDis* d, DisImage** out, uint32_t cap) {
    uint32_t n = 0;
    #define DIS_PUSH(img) do { if (n < cap) out[n++] = (img); } while (0)
    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        DIS_PUSH(&d->color[s]);
        DIS_PUSH(&d->flow_color[s]);
        DIS_PUSH(&d->flow_luma[s]);
    }
    DIS_PUSH(&d->grad);
    for (uint32_t l = 0; l < DIS_MAX_LEVELS; l++) {
        DIS_PUSH(&d->flow_sparse[l]);
        DIS_PUSH(&d->flow_sparse_b[l]);
    }
    DIS_PUSH(&d->flow_dense);
    DIS_PUSH(&d->interp_out);
    DIS_PUSH(&d->vr_prep);
    DIS_PUSH(&d->vr_d1);
    DIS_PUSH(&d->vr_d2);
    DIS_PUSH(&d->vr_A);
    DIS_PUSH(&d->vr_B);
    DIS_PUSH(&d->vr_wt);
    DIS_PUSH(&d->vr_dw[0]);
    DIS_PUSH(&d->vr_dw[1]);
    DIS_PUSH(&d->flow_refined);
    #undef DIS_PUSH
    return n;
}

// One barrier per freshly built image, all in a single CmdPipelineBarrier, to
// move them from UNDEFINED to the GENERAL layout the rest of the chain assumes.
// Runs once per build rather than once per frame.
#define DIS_MAX_OWNED_IMAGES 40u

static void dis_prime_layouts(VkrDis* d, VkCommandBuffer cmd) {
    if (d->layouts_primed) return;

    DisImage* imgs[DIS_MAX_OWNED_IMAGES];
    const uint32_t n = dis_collect_images(d, imgs, DIS_MAX_OWNED_IMAGES);

    VkImageMemoryBarrier bars[DIS_MAX_OWNED_IMAGES];
    uint32_t count = 0;
    for (uint32_t i = 0; i < n; i++) {
        if (!imgs[i]->image) continue;
        VkImageMemoryBarrier* b = &bars[count++];
        memset(b, 0, sizeof(*b));
        b->sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b->srcAccessMask = 0;
        b->dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
                           VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        b->oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        b->newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b->srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b->dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b->image = imgs[i]->image;
        b->subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        b->subresourceRange.levelCount = VK_REMAINING_MIP_LEVELS;
        b->subresourceRange.layerCount = 1;
    }
    if (count == 0) return;

    vkd.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           0, 0, NULL, 0, NULL, count, bars);
    d->layouts_primed = true;
}

// R16F halves the bytes every search and propagation fetch moves against the
// R32F fallback, and against the RGBA8 colour they used to read it is a quarter.
// It is not a format Vulkan guarantees for storage images, though, so the choice
// is made from what the device actually reports. Luminance here spans 0..255,
// where half precision resolves about an eighth of a level - orders below the
// thresholds any of the tuned constants care about.
static VkFormat dis_pick_luma_format(VkrDis* d) {
    // The plane is written as a storage image and then sampled bilinearly by the
    // search, propagation and densify passes, so all three bits are needed. R16F
    // is preferred for the halved bandwidth; R32F is the fallback. Neither is
    // guaranteed - R16F storage and 32-bit float filtering are both optional - so
    // both are tried and UNDEFINED is returned if neither qualifies, which the
    // audit then reports.
    const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
                                      VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                      VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    const VkFormat candidates[2] = {VK_FORMAT_R16_SFLOAT, VK_FORMAT_R32_SFLOAT};
    for (uint32_t i = 0; i < 2; i++) {
        VkFormatProperties fp;
        memset(&fp, 0, sizeof(fp));
        vkd.GetPhysicalDeviceFormatProperties(d->physical_device, candidates[i], &fp);
        if ((fp.optimalTilingFeatures & need) == need) {
            if (i != 0) DIS_LOGI("R16F unusable for the luminance plane; using R32F");
            return candidates[i];
        }
    }
    return VK_FORMAT_UNDEFINED;
}

static bool dis_create_image(VkrDis* d, DisImage* out, uint32_t w, uint32_t h, VkFormat format,
                             uint32_t mip_levels, VkImageUsageFlags usage) {
    memset(out, 0, sizeof(*out));
    out->extent.width = w;
    out->extent.height = h;
    out->format = format;
    out->mip_levels = mip_levels;

    VkImageCreateInfo ic;
    memset(&ic, 0, sizeof(ic));
    ic.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ic.imageType = VK_IMAGE_TYPE_2D;
    ic.format = format;
    ic.extent.width = w;
    ic.extent.height = h;
    ic.extent.depth = 1;
    ic.mipLevels = mip_levels;
    ic.arrayLayers = 1;
    ic.samples = VK_SAMPLE_COUNT_1_BIT;
    ic.tiling = VK_IMAGE_TILING_OPTIMAL;
    ic.usage = usage;
    ic.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    // VK_IMAGE_LAYOUT_GENERAL is not a legal initialLayout - the spec allows only
    // UNDEFINED or PREINITIALIZED here. Most drivers quietly treat it as
    // UNDEFINED, which is what the rest of this file assumed when it went
    // straight to GENERAL->GENERAL barriers on a brand new image. Turnip does
    // not, and that is why rebuilding these resources mid-session used to take
    // the process down. The layout is now established explicitly, once, by
    // dis_prime_layouts on the first command buffer after a build.
    ic.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkd.CreateImage(d->device, &ic, NULL, &out->image) != VK_SUCCESS) return false;

    VkMemoryRequirements mr;
    vkd.GetImageMemoryRequirements(d->device, out->image, &mr);
    uint32_t type = dis_find_memory_type(d, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type == UINT32_MAX) return false;
    VkMemoryAllocateInfo ai;
    memset(&ai, 0, sizeof(ai));
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = type;
    if (vkd.AllocateMemory(d->device, &ai, NULL, &out->memory) != VK_SUCCESS) return false;
    vkd.BindImageMemory(d->device, out->image, out->memory, 0);
    return true;
}

static void dis_destroy_image(VkrDis* d, DisImage* img) {
    if (img->image) vkd.DestroyImage(d->device, img->image, NULL);
    if (img->memory) vkd.FreeMemory(d->device, img->memory, NULL);
    memset(img, 0, sizeof(*img));
}

static bool dis_create_view(VkrDis* d, VkImage image, VkFormat format, uint32_t base_level,
                            uint32_t level_count, VkImageView* out) {
    VkImageViewCreateInfo vi;
    memset(&vi, 0, sizeof(vi));
    vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image = image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = format;
    vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vi.subresourceRange.baseMipLevel = base_level;
    vi.subresourceRange.levelCount = level_count;
    vi.subresourceRange.layerCount = 1;
    return vkd.CreateImageView(d->device, &vi, NULL, out) == VK_SUCCESS;
}

static void dis_destroy_view(VkrDis* d, VkImageView* view) {
    if (*view) vkd.DestroyImageView(d->device, *view, NULL);
    *view = VK_NULL_HANDLE;
}

static VkPipeline dis_create_compute_pipeline_with_layout(VkrDis* d, const uint32_t* code,
                                                           size_t code_size, VkPipelineLayout layout,
                                                           const VkSpecializationInfo* spec) {
    VkShaderModuleCreateInfo smi;
    memset(&smi, 0, sizeof(smi));
    smi.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smi.codeSize = code_size;
    smi.pCode = code;
    VkShaderModule sm;
    if (vkd.CreateShaderModule(d->device, &smi, NULL, &sm) != VK_SUCCESS) return VK_NULL_HANDLE;

    VkPipelineShaderStageCreateInfo stage;
    memset(&stage, 0, sizeof(stage));
    stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stage.module = sm;
    stage.pName = "main";
    stage.pSpecializationInfo = spec;

    VkComputePipelineCreateInfo pci;
    memset(&pci, 0, sizeof(pci));
    pci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pci.stage = stage;
    pci.layout = layout;

    VkPipeline pipeline;
    VkResult res = vkd.CreateComputePipelines(d->device, VK_NULL_HANDLE, 1, &pci, NULL, &pipeline);
    vkd.DestroyShaderModule(d->device, sm, NULL);
    return res == VK_SUCCESS ? pipeline : VK_NULL_HANDLE;
}

static VkPipeline dis_create_compute_pipeline(VkrDis* d, const uint32_t* code, size_t code_size) {
    return dis_create_compute_pipeline_with_layout(d, code, code_size, d->pipeline_layout, NULL);
}

static bool dis_create_pipelines(VkrDis* d) {
    VkDescriptorSetLayoutBinding bindings[6];
    memset(bindings, 0, sizeof(bindings));
    for (uint32_t i = 0; i < 5; i++) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    bindings[5].binding = 5;
    bindings[5].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[5].descriptorCount = 1;
    bindings[5].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo li;
    memset(&li, 0, sizeof(li));
    li.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    li.bindingCount = 6;
    li.pBindings = bindings;
    if (vkd.CreateDescriptorSetLayout(d->device, &li, NULL, &d->set_layout) != VK_SUCCESS) {
        return false;
    }

    VkPushConstantRange pcr;
    memset(&pcr, 0, sizeof(pcr));
    pcr.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pcr.offset = 0;
    pcr.size = 32;

    VkPipelineLayoutCreateInfo pli;
    memset(&pli, 0, sizeof(pli));
    pli.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pli.setLayoutCount = 1;
    pli.pSetLayouts = &d->set_layout;
    pli.pushConstantRangeCount = 1;
    pli.pPushConstantRanges = &pcr;
    if (vkd.CreatePipelineLayout(d->device, &pli, NULL, &d->pipeline_layout) != VK_SUCCESS) {
        return false;
    }

    // Derived, not guessed. A pool allocation reserves EVERY binding the set's
    // layout declares, not the ones that get written, so each shared set costs a
    // full DIS_SET_SAMPLERS whatever the pass actually reads. Hard-coded numbers
    // here have now been wrong twice: once 18 samplers short of a single slot,
    // and again when the luminance pass added a sixth set per level and pushed
    // the request to 815 out of 768. The whole failure is silent - the allocation
    // returns OUT_OF_POOL_MEMORY, create returns NULL, and frame generation just
    // stops existing - so the counts are computed from the same constants that
    // drive dis_allocate_sets, and anything added there moves these with it.
    const uint32_t shared_sets = DIS_SLOTS * DIS_MAX_LEVELS * DIS_SHARED_SETS_PER_LEVEL
                               + DIS_SLOTS;   /* + the interpolation set per slot */
    const uint32_t vr_sets = DIS_SLOTS        /* vr_prep per slot */
                           + DIS_VR_SHARED_SETS;
    const uint32_t total_sets = shared_sets + vr_sets;

    VkDescriptorPoolSize sizes[2];
    memset(sizes, 0, sizeof(sizes));
    sizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    sizes[0].descriptorCount = shared_sets * DIS_SET_SAMPLERS
                             + vr_sets * DIS_VR_SAMPLER_BINDINGS;
    sizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    sizes[1].descriptorCount = shared_sets * DIS_SET_STORAGE
                             + vr_sets * DIS_VR_STORAGE_BINDINGS;
    VkDescriptorPoolCreateInfo pci;
    memset(&pci, 0, sizeof(pci));
    pci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    pci.maxSets = total_sets;
    pci.poolSizeCount = 2;
    pci.pPoolSizes = sizes;
    if (vkd.CreateDescriptorPool(d->device, &pci, NULL, &d->pool) != VK_SUCCESS) {
        return false;
    }

    // SOR (variational refinement) descriptor set layout: more samplers and
    // storage images than the 5+1 layout used by the DIS passes.
    VkDescriptorSetLayoutBinding vr_bindings[DIS_VR_SAMPLER_BINDINGS + DIS_VR_STORAGE_BINDINGS];
    memset(vr_bindings, 0, sizeof(vr_bindings));
    for (uint32_t i = 0; i < DIS_VR_SAMPLER_BINDINGS; i++) {
        vr_bindings[i].binding = i;
        vr_bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        vr_bindings[i].descriptorCount = 1;
        vr_bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    for (uint32_t i = 0; i < DIS_VR_STORAGE_BINDINGS; i++) {
        vr_bindings[DIS_VR_SAMPLER_BINDINGS + i].binding = DIS_VR_FIRST_STORAGE + i;
        vr_bindings[DIS_VR_SAMPLER_BINDINGS + i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        vr_bindings[DIS_VR_SAMPLER_BINDINGS + i].descriptorCount = 1;
        vr_bindings[DIS_VR_SAMPLER_BINDINGS + i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }

    VkDescriptorSetLayoutCreateInfo vr_li;
    memset(&vr_li, 0, sizeof(vr_li));
    vr_li.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    vr_li.bindingCount = DIS_VR_SAMPLER_BINDINGS + DIS_VR_STORAGE_BINDINGS;
    vr_li.pBindings = vr_bindings;
    if (vkd.CreateDescriptorSetLayout(d->device, &vr_li, NULL, &d->vr_set_layout) != VK_SUCCESS) {
        return false;
    }

    VkPushConstantRange vr_pcr;
    memset(&vr_pcr, 0, sizeof(vr_pcr));
    vr_pcr.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    vr_pcr.offset = 0;
    vr_pcr.size = 32;

    VkPipelineLayoutCreateInfo vr_pli;
    memset(&vr_pli, 0, sizeof(vr_pli));
    vr_pli.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    vr_pli.setLayoutCount = 1;
    vr_pli.pSetLayouts = &d->vr_set_layout;
    vr_pli.pushConstantRangeCount = 1;
    vr_pli.pPushConstantRanges = &vr_pcr;
    if (vkd.CreatePipelineLayout(d->device, &vr_pli, NULL, &d->vr_pipeline_layout) != VK_SUCCESS) {
        return false;
    }

    // The storage format qualifier is baked into the SPIR-V, so the variant has to
    // match the image the plane is actually created with. Only the one in use is
    // built; the other blob is dead weight in the binary and nothing more.
    d->pass_luma.pipeline = d->luma_format == VK_FORMAT_R16_SFLOAT
        ? dis_create_compute_pipeline(d, dis_luma_r16_comp, dis_luma_r16_comp_size)
        : dis_create_compute_pipeline(d, dis_luma_r32_comp, dis_luma_r32_comp_size);
    d->pass_gradient.pipeline = dis_create_compute_pipeline(d, dis_gradient_comp, dis_gradient_comp_size);
    d->pass_inverse.pipeline = dis_create_compute_pipeline(d, dis_inverse_search_comp, dis_inverse_search_comp_size);
    d->pass_propagate.pipeline = dis_create_compute_pipeline(d, dis_propagate_comp, dis_propagate_comp_size);
    d->pass_densify.pipeline = dis_create_compute_pipeline(d, dis_densify_comp, dis_densify_comp_size);
    // The interpolation pass is specialized on how the flow can be filtered, so
    // the branch is compiled out rather than taken per pixel.
    const VkBool32 manual_filter = d->manual_flow_filter ? 1u : 0u;
    VkSpecializationMapEntry spec_entry;
    memset(&spec_entry, 0, sizeof(spec_entry));
    spec_entry.constantID = 0;
    spec_entry.offset = 0;
    spec_entry.size = sizeof(manual_filter);
    VkSpecializationInfo spec;
    memset(&spec, 0, sizeof(spec));
    spec.mapEntryCount = 1;
    spec.pMapEntries = &spec_entry;
    spec.dataSize = sizeof(manual_filter);
    spec.pData = &manual_filter;
    d->pass_interp.pipeline = dis_create_compute_pipeline_with_layout(
        d, dis_interpolate_comp, dis_interpolate_comp_size, d->pipeline_layout, &spec);

    d->pass_vr_prep.pipeline = dis_create_compute_pipeline_with_layout(d, dis_vr_prep_comp, dis_vr_prep_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_vr_d1.pipeline = dis_create_compute_pipeline_with_layout(d, dis_vr_d1_comp, dis_vr_d1_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_vr_d2.pipeline = dis_create_compute_pipeline_with_layout(d, dis_vr_d2_comp, dis_vr_d2_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_vr_w.pipeline = dis_create_compute_pipeline_with_layout(d, dis_vr_w_comp, dis_vr_w_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_vr_coef.pipeline = dis_create_compute_pipeline_with_layout(d, dis_vr_coef_comp, dis_vr_coef_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_vr_sor.pipeline = dis_create_compute_pipeline_with_layout(d, dis_vr_sor_comp, dis_vr_sor_comp_size, d->vr_pipeline_layout, NULL);
    d->pass_vr_add.pipeline = dis_create_compute_pipeline_with_layout(d, dis_vr_add_comp, dis_vr_add_comp_size, d->vr_pipeline_layout, NULL);

    if (!d->pass_gradient.pipeline || !d->pass_inverse.pipeline || !d->pass_propagate.pipeline ||
        !d->pass_densify.pipeline || !d->pass_interp.pipeline ||
        !d->pass_vr_prep.pipeline || !d->pass_vr_d1.pipeline || !d->pass_vr_d2.pipeline ||
        !d->pass_vr_w.pipeline || !d->pass_vr_coef.pipeline || !d->pass_vr_sor.pipeline ||
        !d->pass_vr_add.pipeline) {
        return false;
    }
    return true;
}

static bool dis_create_sampler(VkrDis* d) {
    VkSamplerCreateInfo si;
    memset(&si, 0, sizeof(si));
    si.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    si.magFilter = VK_FILTER_LINEAR;
    si.minFilter = VK_FILTER_LINEAR;
    si.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    si.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.minLod = 0.0f;
    si.maxLod = (float)DIS_MAX_LEVELS;
    return vkd.CreateSampler(d->device, &si, NULL, &d->sampler) == VK_SUCCESS;
}

// Resolution of the flow pyramid for a given content rect: fit the SHORTER side
// to min_side and let the longer one follow, so the pyramid keeps the content's
// aspect ratio on a fixed pixel budget. Never upscale - on a frame already
// shorter than the target there is nothing to gain from inventing pixels for the
// patch search to chew on.
//
// Split out from vkr_dis_prepare so it can be exercised without a device.
static void dis_flow_extent(uint32_t min_side, uint32_t content_w, uint32_t content_h,
                            uint32_t* out_w, uint32_t* out_h) {
    const uint32_t minor = content_w < content_h ? content_w : content_h;
    if (minor == 0 || min_side == 0 || min_side >= minor) {
        *out_w = content_w;
        *out_h = content_h;
        return;
    }
    const double k = (double)min_side / (double)minor;
    *out_w = (uint32_t)((double)content_w * k + 0.5);
    *out_h = (uint32_t)((double)content_h * k + 0.5);
}

static uint32_t dis_levels_for(uint32_t w, uint32_t h) {
    uint32_t levels = 1;
    while ((w >> levels) >= 16 && (h >> levels) >= 16 && levels < DIS_MAX_LEVELS) {
        levels++;
    }
    return levels;
}

// OpenCV sparse grid: number of 8x8 patches at stride DIS_PATCH_STRIDE that fit
// fully inside a level of the given extent (ws = 1 + floor((w - 8) / stride)).
static uint32_t dis_sparse_extent(uint32_t extent) {
    return extent > 8u ? 1u + (extent - 8u) / DIS_PATCH_STRIDE : 1u;
}

// Propagation steps for one pyramid level, indexed by distance from the coarsest.
// A flat 4 steps everywhere meant 16 dispatches per level; at the finest level of
// a 1280x720 flow that is a 425x238 grid evaluating two 64-tap patch SSDs per
// cell per dispatch, which on its own outweighed every other pass in the chain.
static uint32_t dis_prop_steps_for(uint32_t level, uint32_t levels, uint32_t floor_steps) {
    static const uint32_t profile[DIS_PROP_STEPS_MAX] = {4u, 3u, 2u, 1u};
    const uint32_t from_coarse = (levels - 1u) - level;
    const uint32_t base = from_coarse < DIS_PROP_STEPS_MAX ? profile[from_coarse] : 1u;
    return base > floor_steps ? base : floor_steps;
}

// How hard to work on the flow, chosen from the number of frames being generated.
//
// Two reasons point the same way. A higher multiplier means the guest is running
// slower, so there is proportionally more wall clock between source frames to
// spend - at 30 -> 120 there are 33 ms per source frame against 16.7 at
// 60 -> 120. And every error in the flow is then shown on three synthetic frames
// instead of one, so removing it is worth proportionally more. The top tier costs
// roughly 1.8x the work of the bottom one while having 2x the time for it, so the
// per-frame budget loosens rather than tightens.
//
// The variational refinement is what turns a locally noisy flow into a coherent
// one, which is exactly the wobble that shows up as warping at high multipliers;
// OpenCV's reference runs it at 5 fixed-point iterations and 5 SOR sweeps, so
// even the top tier here stays below the reference.
typedef struct {
    uint32_t vr_fixed_point;
    uint32_t vr_sor;
    uint32_t prop_floor;
} DisRefine;

static DisRefine dis_refine_for(uint32_t generations) {
    if (generations >= 3u) {
        const DisRefine r = {2u, 5u, 2u};
        return r;
    }
    if (generations == 2u) {
        const DisRefine r = {2u, 4u, 1u};
        return r;
    }
    const DisRefine r = {1u, 3u, 1u};
    return r;
}

static void dis_batch_flush(VkrDis* d, DisBatch* b) {
    if (b->count == 0) return;
    vkd.UpdateDescriptorSets(d->device, b->count, b->w, 0, NULL);
    b->count = 0;
}

// Writing every slot's sets in one go exceeds DIS_MAX_DESCRIPTOR_WRITES, so the
// batch drains itself when full rather than silently overrunning its arrays.
static void dis_batch_sampled(VkrDis* d, DisBatch* b, VkDescriptorSet set, uint32_t binding,
                              VkImageView view, VkSampler sampler) {
    if (b->count == DIS_MAX_DESCRIPTOR_WRITES) dis_batch_flush(d, b);
    VkDescriptorImageInfo* info = &b->img[b->count];
    memset(info, 0, sizeof(*info));
    info->sampler = sampler;
    info->imageView = view;
    info->imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet* w = &b->w[b->count];
    memset(w, 0, sizeof(*w));
    w->sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    w->dstSet = set;
    w->dstBinding = binding;
    w->descriptorCount = 1;
    w->descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    w->pImageInfo = info;
    b->count++;
}

static void dis_batch_storage(VkrDis* d, DisBatch* b, VkDescriptorSet set, uint32_t binding,
                              VkImageView view) {
    if (b->count == DIS_MAX_DESCRIPTOR_WRITES) dis_batch_flush(d, b);
    VkDescriptorImageInfo* info = &b->img[b->count];
    memset(info, 0, sizeof(*info));
    info->imageView = view;
    info->imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet* w = &b->w[b->count];
    memset(w, 0, sizeof(*w));
    w->sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    w->dstSet = set;
    w->dstBinding = binding;
    w->descriptorCount = 1;
    w->descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    w->pImageInfo = info;
    b->count++;
}

// Writes every descriptor set for every input slot. Called once when resources
// are built (the renderer has already waited out all in-flight frames by then),
// never per frame: a slot's bindings depend only on the slot index, so they are
// constant for the lifetime of the resources.
static void dis_write_all_descriptors(VkrDis* d) {
    DisBatch b;
    memset(&b, 0, sizeof(b));
    const uint32_t L = d->levels;
    const uint32_t coarse = L - 1;

    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        const uint32_t next = s;
        const uint32_t prev = (s + DIS_SLOTS - 1u) % DIS_SLOTS;

        for (uint32_t l = 0; l < L; l++) {
            // The luma pass is the only consumer of the colour pyramid in the
            // search chain; everything after it reads the plane it writes.
            dis_batch_sampled(d, &b, d->luma_sets[s][l], 0, d->view_flow_color[next][l], d->sampler);
            dis_batch_storage(d, &b, d->luma_sets[s][l], 5, d->view_flow_luma[next][l]);

            dis_batch_sampled(d, &b, d->grad_sets[s][l], 0, d->view_flow_luma[prev][l], d->sampler);
            dis_batch_storage(d, &b, d->grad_sets[s][l], 5, d->view_grad[l]);

            dis_batch_sampled(d, &b, d->inverse_sets[s][l], 0, d->view_flow_luma[prev][l], d->sampler);
            dis_batch_sampled(d, &b, d->inverse_sets[s][l], 1, d->view_flow_luma[next][l], d->sampler);
            dis_batch_sampled(d, &b, d->inverse_sets[s][l], 2, d->view_grad[l], d->sampler);
            dis_batch_sampled(d, &b, d->inverse_sets[s][l], 3,
                              d->view_dense[l + 1 < L ? l + 1 : coarse], d->sampler);
            // binding 4 is the shader's lastFlowMap, which it no longer reads: the
            // coarsest level starts from zero rather than from a temporal prior.
            // The binding still has to resolve, so point it at a live image.
            dis_batch_sampled(d, &b, d->inverse_sets[s][l], 4, d->view_dense[coarse], d->sampler);
            dis_batch_storage(d, &b, d->inverse_sets[s][l], 5, d->view_sparse[l]);

            dis_batch_sampled(d, &b, d->prop_ab_sets[s][l], 0, d->view_flow_luma[prev][l], d->sampler);
            dis_batch_sampled(d, &b, d->prop_ab_sets[s][l], 1, d->view_flow_luma[next][l], d->sampler);
            dis_batch_sampled(d, &b, d->prop_ab_sets[s][l], 2, d->view_sparse[l], d->sampler);
            dis_batch_storage(d, &b, d->prop_ab_sets[s][l], 5, d->view_sparse_b[l]);

            dis_batch_sampled(d, &b, d->prop_ba_sets[s][l], 0, d->view_flow_luma[prev][l], d->sampler);
            dis_batch_sampled(d, &b, d->prop_ba_sets[s][l], 1, d->view_flow_luma[next][l], d->sampler);
            dis_batch_sampled(d, &b, d->prop_ba_sets[s][l], 2, d->view_sparse_b[l], d->sampler);
            dis_batch_storage(d, &b, d->prop_ba_sets[s][l], 5, d->view_sparse[l]);

            dis_batch_sampled(d, &b, d->densify_sets[s][l], 0, d->view_sparse[l], d->sampler);
            dis_batch_sampled(d, &b, d->densify_sets[s][l], 1, d->view_flow_luma[prev][l], d->sampler);
            dis_batch_sampled(d, &b, d->densify_sets[s][l], 2, d->view_flow_luma[next][l], d->sampler);
            dis_batch_storage(d, &b, d->densify_sets[s][l], 5, d->view_dense[l]);
        }

        dis_batch_sampled(d, &b, d->interp_sets[s], 0, d->view_color[prev], d->sampler);
        dis_batch_sampled(d, &b, d->interp_sets[s], 1, d->view_color[next], d->sampler);
        // The flow is stored normalized, so it is resolution independent: the
        // interpolation pass samples the refined flow at its own scale and lets
        // the sampler interpolate. Materialising a full-res copy first wrote 5.9 MB
        // per frame, and sampling that instead of a 180 KB image thrashed the
        // texture cache for a result that was, if anything, slightly blurrier.
        dis_batch_sampled(d, &b, d->interp_sets[s], 2, d->view_flow_refined, d->sampler);
        dis_batch_storage(d, &b, d->interp_sets[s], 5, d->view_interp_out);

        dis_batch_sampled(d, &b, d->vr_prep_sets[s], 0, d->view_flow_color[prev][0], d->sampler);
        dis_batch_sampled(d, &b, d->vr_prep_sets[s], 1, d->view_flow_color[next][0], d->sampler);
        dis_batch_sampled(d, &b, d->vr_prep_sets[s], 2, d->view_dense[0], d->sampler);
        dis_batch_storage(d, &b, d->vr_prep_sets[s], DIS_VR_FIRST_STORAGE, d->view_vr_prep);
        dis_batch_storage(d, &b, d->vr_prep_sets[s], DIS_VR_FIRST_STORAGE + 1, d->view_vr_dw[0]);
    }

    // Slot-independent sets below: their bindings name no input frame.
    dis_batch_sampled(d, &b, d->vr_d1_set, 0, d->view_vr_prep, d->sampler);
    dis_batch_storage(d, &b, d->vr_d1_set, DIS_VR_FIRST_STORAGE, d->view_vr_d1);

    dis_batch_sampled(d, &b, d->vr_d2_set, 0, d->view_vr_d1, d->sampler);
    dis_batch_storage(d, &b, d->vr_d2_set, DIS_VR_FIRST_STORAGE, d->view_vr_d2);

    dis_batch_sampled(d, &b, d->vr_w_set, 0, d->view_dense[0], d->sampler);
    dis_batch_sampled(d, &b, d->vr_w_set, 1, d->view_vr_dw[0], d->sampler);
    dis_batch_storage(d, &b, d->vr_w_set, DIS_VR_FIRST_STORAGE, d->view_vr_wt);

    dis_batch_sampled(d, &b, d->vr_coef_set, 0, d->view_vr_prep, d->sampler);
    dis_batch_sampled(d, &b, d->vr_coef_set, 1, d->view_vr_d1, d->sampler);
    dis_batch_sampled(d, &b, d->vr_coef_set, 2, d->view_vr_d2, d->sampler);
    dis_batch_sampled(d, &b, d->vr_coef_set, 3, d->view_vr_dw[0], d->sampler);
    dis_batch_sampled(d, &b, d->vr_coef_set, 4, d->view_dense[0], d->sampler);
    dis_batch_sampled(d, &b, d->vr_coef_set, 5, d->view_vr_wt, d->sampler);
    dis_batch_storage(d, &b, d->vr_coef_set, DIS_VR_FIRST_STORAGE, d->view_vr_A);
    dis_batch_storage(d, &b, d->vr_coef_set, DIS_VR_FIRST_STORAGE + 1, d->view_vr_B);

    dis_batch_sampled(d, &b, d->vr_sor_ab_set, 0, d->view_vr_A, d->sampler);
    dis_batch_sampled(d, &b, d->vr_sor_ab_set, 1, d->view_vr_B, d->sampler);
    dis_batch_sampled(d, &b, d->vr_sor_ab_set, 2, d->view_vr_wt, d->sampler);
    dis_batch_sampled(d, &b, d->vr_sor_ab_set, 3, d->view_vr_dw[0], d->sampler);
    dis_batch_storage(d, &b, d->vr_sor_ab_set, DIS_VR_FIRST_STORAGE, d->view_vr_dw[1]);

    dis_batch_sampled(d, &b, d->vr_sor_ba_set, 0, d->view_vr_A, d->sampler);
    dis_batch_sampled(d, &b, d->vr_sor_ba_set, 1, d->view_vr_B, d->sampler);
    dis_batch_sampled(d, &b, d->vr_sor_ba_set, 2, d->view_vr_wt, d->sampler);
    dis_batch_sampled(d, &b, d->vr_sor_ba_set, 3, d->view_vr_dw[1], d->sampler);
    dis_batch_storage(d, &b, d->vr_sor_ba_set, DIS_VR_FIRST_STORAGE, d->view_vr_dw[0]);

    dis_batch_sampled(d, &b, d->vr_add_set, 0, d->view_dense[0], d->sampler);
    dis_batch_sampled(d, &b, d->vr_add_set, 1, d->view_vr_dw[0], d->sampler);
    dis_batch_storage(d, &b, d->vr_add_set, DIS_VR_FIRST_STORAGE, d->view_flow_refined);

    dis_batch_flush(d, &b);
}

static void dis_destroy_views(VkrDis* d) {
    for (uint32_t s = 0; s < DIS_SLOTS; s++) dis_destroy_view(d, &d->view_color[s]);
    dis_destroy_view(d, &d->view_interp_out);
    dis_destroy_view(d, &d->view_vr_prep);
    dis_destroy_view(d, &d->view_vr_d1);
    dis_destroy_view(d, &d->view_vr_d2);
    dis_destroy_view(d, &d->view_vr_A);
    dis_destroy_view(d, &d->view_vr_B);
    dis_destroy_view(d, &d->view_vr_wt);
    dis_destroy_view(d, &d->view_vr_dw[0]);
    dis_destroy_view(d, &d->view_vr_dw[1]);
    dis_destroy_view(d, &d->view_flow_refined);
    for (uint32_t l = 0; l < DIS_MAX_LEVELS; l++) {
        for (uint32_t s = 0; s < DIS_SLOTS; s++) {
            dis_destroy_view(d, &d->view_flow_color[s][l]);
            dis_destroy_view(d, &d->view_flow_luma[s][l]);
        }
        dis_destroy_view(d, &d->view_grad[l]);
        dis_destroy_view(d, &d->view_sparse[l]);
        dis_destroy_view(d, &d->view_sparse_b[l]);
        dis_destroy_view(d, &d->view_dense[l]);
    }
}

static void dis_destroy_images(VkrDis* d) {
    dis_destroy_views(d);
    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        dis_destroy_image(d, &d->color[s]);
        dis_destroy_image(d, &d->flow_color[s]);
        dis_destroy_image(d, &d->flow_luma[s]);
    }
    dis_destroy_image(d, &d->grad);
    for (uint32_t l = 0; l < DIS_MAX_LEVELS; l++) {
        dis_destroy_image(d, &d->flow_sparse[l]);
        dis_destroy_image(d, &d->flow_sparse_b[l]);
    }
    dis_destroy_image(d, &d->flow_dense);
    dis_destroy_image(d, &d->interp_out);
    dis_destroy_image(d, &d->vr_prep);
    dis_destroy_image(d, &d->vr_d1);
    dis_destroy_image(d, &d->vr_d2);
    dis_destroy_image(d, &d->vr_A);
    dis_destroy_image(d, &d->vr_B);
    dis_destroy_image(d, &d->vr_wt);
    dis_destroy_image(d, &d->vr_dw[0]);
    dis_destroy_image(d, &d->vr_dw[1]);
    dis_destroy_image(d, &d->flow_refined);
}

static bool dis_create_resources(VkrDis* d, uint32_t w, uint32_t h, uint32_t full_w,
                                 uint32_t full_h, VkFormat format) {
    dis_destroy_images(d);
    d->layouts_primed = false;

    const uint32_t L = d->levels;
    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        if (!dis_create_image(d, &d->color[s], full_w, full_h, format, 1,
                              VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                  VK_IMAGE_USAGE_TRANSFER_DST_BIT)) return false;
        if (!dis_create_image(d, &d->flow_color[s], w, h, format, L,
                              VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                  VK_IMAGE_USAGE_TRANSFER_DST_BIT)) return false;
        if (!dis_create_image(d, &d->flow_luma[s], w, h, d->luma_format, L,
                              VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    }
    if (!dis_create_image(d, &d->grad, w, h, VK_FORMAT_R32G32_SFLOAT, L,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    for (uint32_t l = 0; l < L; l++) {
        const uint32_t spw = dis_sparse_extent(w >> l);
        const uint32_t sph = dis_sparse_extent(h >> l);
        // Four channels, not two: .z carries the SSD already measured for the
        // vector in .xy, so a propagation pass need not score it again. These
        // grids are a ninth of the level's texel count, so the extra channels
        // cost a few tens of kilobytes in total.
        if (!dis_create_image(d, &d->flow_sparse[l], spw, sph, VK_FORMAT_R32G32B32A32_SFLOAT, 1,
                              VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
        if (!dis_create_image(d, &d->flow_sparse_b[l], spw, sph, VK_FORMAT_R32G32B32A32_SFLOAT, 1,
                              VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    }
    if (!dis_create_image(d, &d->flow_dense, w, h, VK_FORMAT_R32G32_SFLOAT, L,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->interp_out, full_w, full_h, VK_FORMAT_R8G8B8A8_UNORM, 1,
                          VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)) {
        return false;
    }

    // Variational refinement intermediates (finest flow level only).
    if (!dis_create_image(d, &d->vr_prep, w, h, VK_FORMAT_R32G32_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->vr_d1, w, h, VK_FORMAT_R32G32B32A32_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->vr_d2, w, h, VK_FORMAT_R32G32B32A32_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->vr_A, w, h, VK_FORMAT_R32G32B32A32_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->vr_B, w, h, VK_FORMAT_R32G32_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->vr_wt, w, h, VK_FORMAT_R32_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->vr_dw[0], w, h, VK_FORMAT_R32G32_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->vr_dw[1], w, h, VK_FORMAT_R32G32_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!dis_create_image(d, &d->flow_refined, w, h, VK_FORMAT_R32G32_SFLOAT, 1,
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) return false;

    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        if (!dis_create_view(d, d->color[s].image, format, 0, 1, &d->view_color[s])) return false;
    }
    for (uint32_t l = 0; l < L; l++) {
        for (uint32_t s = 0; s < DIS_SLOTS; s++) {
            if (!dis_create_view(d, d->flow_color[s].image, format, l, 1,
                                 &d->view_flow_color[s][l])) return false;
            if (!dis_create_view(d, d->flow_luma[s].image, d->luma_format, l, 1,
                                 &d->view_flow_luma[s][l])) return false;
        }
        if (!dis_create_view(d, d->grad.image, VK_FORMAT_R32G32_SFLOAT, l, 1, &d->view_grad[l])) return false;
        if (!dis_create_view(d, d->flow_sparse[l].image, VK_FORMAT_R32G32B32A32_SFLOAT, 0, 1, &d->view_sparse[l])) return false;
        if (!dis_create_view(d, d->flow_sparse_b[l].image, VK_FORMAT_R32G32B32A32_SFLOAT, 0, 1, &d->view_sparse_b[l])) return false;
        if (!dis_create_view(d, d->flow_dense.image, VK_FORMAT_R32G32_SFLOAT, l, 1, &d->view_dense[l])) return false;
    }
    if (!dis_create_view(d, d->interp_out.image, VK_FORMAT_R8G8B8A8_UNORM, 0, 1, &d->view_interp_out)) return false;
    if (!dis_create_view(d, d->vr_prep.image, VK_FORMAT_R32G32_SFLOAT, 0, 1, &d->view_vr_prep)) return false;
    if (!dis_create_view(d, d->vr_d1.image, VK_FORMAT_R32G32B32A32_SFLOAT, 0, 1, &d->view_vr_d1)) return false;
    if (!dis_create_view(d, d->vr_d2.image, VK_FORMAT_R32G32B32A32_SFLOAT, 0, 1, &d->view_vr_d2)) return false;
    if (!dis_create_view(d, d->vr_A.image, VK_FORMAT_R32G32B32A32_SFLOAT, 0, 1, &d->view_vr_A)) return false;
    if (!dis_create_view(d, d->vr_B.image, VK_FORMAT_R32G32_SFLOAT, 0, 1, &d->view_vr_B)) return false;
    if (!dis_create_view(d, d->vr_wt.image, VK_FORMAT_R32_SFLOAT, 0, 1, &d->view_vr_wt)) return false;
    if (!dis_create_view(d, d->vr_dw[0].image, VK_FORMAT_R32G32_SFLOAT, 0, 1, &d->view_vr_dw[0])) return false;
    if (!dis_create_view(d, d->vr_dw[1].image, VK_FORMAT_R32G32_SFLOAT, 0, 1, &d->view_vr_dw[1])) return false;
    if (!dis_create_view(d, d->flow_refined.image, VK_FORMAT_R32G32_SFLOAT, 0, 1, &d->view_flow_refined)) return false;

    vkr_dis_reset(d);
    dis_write_all_descriptors(d);
    return true;
}

static bool dis_alloc(VkrDis* d, VkDescriptorSetLayout layout, uint32_t count,
                      VkDescriptorSet* out) {
    VkDescriptorSetLayout layouts[8];
    if (count > 8) return false;
    for (uint32_t i = 0; i < count; i++) layouts[i] = layout;
    VkDescriptorSetAllocateInfo ai;
    memset(&ai, 0, sizeof(ai));
    ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool = d->pool;
    ai.descriptorSetCount = count;
    ai.pSetLayouts = layouts;
    const VkResult res = vkd.AllocateDescriptorSets(d->device, &ai, out);
    if (res != VK_SUCCESS) {
        // Worth a line: every way this fails ends with frame generation quietly
        // absent, with nothing else in the log to say why.
        DIS_LOGW("DIS descriptor allocation failed (%d) asking for %u sets", (int)res, count);
        return false;
    }
    return true;
}

static bool dis_allocate_sets(VkrDis* d) {
    for (uint32_t s = 0; s < DIS_SLOTS; s++) {
        for (uint32_t l = 0; l < DIS_MAX_LEVELS; l++) {
            VkDescriptorSet sets[6];
            if (!dis_alloc(d, d->set_layout, 6, sets)) return false;
            d->grad_sets[s][l] = sets[0];
            d->inverse_sets[s][l] = sets[1];
            d->densify_sets[s][l] = sets[2];
            d->prop_ab_sets[s][l] = sets[3];
            d->prop_ba_sets[s][l] = sets[4];
            d->luma_sets[s][l] = sets[5];
        }
        if (!dis_alloc(d, d->set_layout, 1, &d->interp_sets[s])) return false;
        if (!dis_alloc(d, d->vr_set_layout, 1, &d->vr_prep_sets[s])) return false;
    }

    VkDescriptorSet vr_sets[7];
    if (!dis_alloc(d, d->vr_set_layout, 7, vr_sets)) return false;
    d->vr_d1_set = vr_sets[0];
    d->vr_d2_set = vr_sets[1];
    d->vr_w_set = vr_sets[2];
    d->vr_coef_set = vr_sets[3];
    d->vr_sor_ab_set = vr_sets[4];
    d->vr_sor_ba_set = vr_sets[5];
    d->vr_add_set = vr_sets[6];
    return true;
}

static void dis_dispatch(VkrDis* d, VkCommandBuffer cmd, VkPipeline pipeline, VkDescriptorSet set,
                         uint32_t w, uint32_t h) {
    vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pipeline_layout, 0, 1, &set,
                              0, NULL);
    vkd.CmdDispatch(cmd, (w + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                    (h + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);
}

static void dis_blit_rect(VkCommandBuffer cmd,
                          VkImage src, int32_t sx, int32_t sy, uint32_t sw, uint32_t sh,
                          VkImage dst, int32_t dx, int32_t dy, uint32_t dw, uint32_t dh,
                          VkFilter filter) {
    VkImageBlit blit;
    memset(&blit, 0, sizeof(blit));
    blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.srcSubresource.layerCount = 1;
    blit.srcOffsets[0].x = sx;
    blit.srcOffsets[0].y = sy;
    blit.srcOffsets[1].x = sx + (int32_t)sw;
    blit.srcOffsets[1].y = sy + (int32_t)sh;
    blit.srcOffsets[1].z = 1;
    blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.dstSubresource.layerCount = 1;
    blit.dstOffsets[0].x = dx;
    blit.dstOffsets[0].y = dy;
    blit.dstOffsets[1].x = dx + (int32_t)dw;
    blit.dstOffsets[1].y = dy + (int32_t)dh;
    blit.dstOffsets[1].z = 1;
    vkd.CmdBlitImage(cmd, src, VK_IMAGE_LAYOUT_GENERAL, dst, VK_IMAGE_LAYOUT_GENERAL, 1, &blit,
                     filter);
}

static void dis_blit_mip(VkCommandBuffer cmd, VkImage img, uint32_t src_level, uint32_t dst_level,
                         uint32_t src_w, uint32_t src_h, uint32_t dst_w, uint32_t dst_h) {
    VkImageBlit blit;
    memset(&blit, 0, sizeof(blit));
    blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.srcSubresource.mipLevel = src_level;
    blit.srcSubresource.layerCount = 1;
    blit.srcOffsets[1].x = (int32_t)src_w;
    blit.srcOffsets[1].y = (int32_t)src_h;
    blit.srcOffsets[1].z = 1;
    blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.dstSubresource.mipLevel = dst_level;
    blit.dstSubresource.layerCount = 1;
    blit.dstOffsets[1].x = (int32_t)dst_w;
    blit.dstOffsets[1].y = (int32_t)dst_h;
    blit.dstOffsets[1].z = 1;
    vkd.CmdBlitImage(cmd, img, VK_IMAGE_LAYOUT_GENERAL, img, VK_IMAGE_LAYOUT_GENERAL, 1, &blit,
                     VK_FILTER_LINEAR);
}


// ---------------------------------------------------------------------------
// Format capability audit
//
// Most of what DIS asks for is in the spec's mandatory table and cannot be
// missing. Two things are not, and both would fail quietly rather than loudly:
// storage writes to R16_SFLOAT (the luminance plane, already handled by picking
// R32F instead), and LINEAR FILTERING OF 32-BIT FLOAT FORMATS. The dense flow is
// R32G32_SFLOAT and the interpolation pass samples it with textureLod, so
// without that filter bit the warp reads undefined values and every generated
// frame is garbage - on a driver that does not simply refuse the sampler.
//
// Adreno reports it. Whether a given Mali, PowerVR or Xclipse part does is not
// something to assume, so it is asked rather than hoped for, once, at create
// time, and the answer is printed either way.
// ---------------------------------------------------------------------------

static const char* dis_format_name(VkFormat f) {
    switch (f) {
        case VK_FORMAT_R16_SFLOAT: return "R16_SFLOAT";
        case VK_FORMAT_R32_SFLOAT: return "R32_SFLOAT";
        case VK_FORMAT_R32G32_SFLOAT: return "R32G32_SFLOAT";
        case VK_FORMAT_R32G32B32A32_SFLOAT: return "R32G32B32A32_SFLOAT";
        case VK_FORMAT_R8G8B8A8_UNORM: return "R8G8B8A8_UNORM";
        case VK_FORMAT_UNDEFINED: return "none";
        default: return "format";
    }
}

static void dis_missing_features(VkFormatFeatureFlags missing, char* out, size_t cap) {
    out[0] = '\0';
    const struct { VkFormatFeatureFlags bit; const char* name; } names[] = {
        {VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT, "STORAGE_IMAGE"},
        {VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT, "SAMPLED_IMAGE"},
        {VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT, "SAMPLED_IMAGE_FILTER_LINEAR"},
        {VK_FORMAT_FEATURE_TRANSFER_SRC_BIT, "TRANSFER_SRC"},
        {VK_FORMAT_FEATURE_TRANSFER_DST_BIT, "TRANSFER_DST"},
    };
    for (uint32_t i = 0; i < sizeof(names) / sizeof(names[0]); i++) {
        if (!(missing & names[i].bit)) continue;
        if (out[0]) strncat(out, "+", cap - strlen(out) - 1);
        strncat(out, names[i].name, cap - strlen(out) - 1);
    }
    if (!out[0]) strncat(out, "none", cap - 1);
}

static bool dis_audit_formats(VkrDis* d) {
    const VkFormatFeatureFlags STORE = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
    const VkFormatFeatureFlags READ = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    const VkFormatFeatureFlags FILTER = VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;

    if (d->luma_format == VK_FORMAT_UNDEFINED) {
        DIS_LOGW("DIS needs a single-channel float plane it can both write and filter; "
                 "neither R16_SFLOAT nor R32_SFLOAT qualifies on this device");
        return false;
    }

    const struct {
        VkFormat format;
        VkFormatFeatureFlags need;
        const char* purpose;
    } reqs[] = {
        // Storage and plain reads are all mandatory in the spec's table, so these
        // cannot realistically fail - they are checked so that a driver which
        // does fail says which one, instead of producing an empty screen.
        {VK_FORMAT_R32G32_SFLOAT, STORE | READ, "optical flow"},
        {VK_FORMAT_R32G32B32A32_SFLOAT, STORE | READ, "sparse flow and refinement"},
        {VK_FORMAT_R32_SFLOAT, STORE | READ, "refinement weights"},
        {VK_FORMAT_R8G8B8A8_UNORM, STORE | READ, "interpolated output"},
        // Already chosen against exactly these bits, so this only restates it.
        {d->luma_format, STORE | READ | FILTER, "luminance plane"},
    };

    char line[512];
    line[0] = '\0';
    bool ok = true;

    for (uint32_t i = 0; i < sizeof(reqs) / sizeof(reqs[0]); i++) {
        VkFormatProperties fp;
        memset(&fp, 0, sizeof(fp));
        vkd.GetPhysicalDeviceFormatProperties(d->physical_device, reqs[i].format, &fp);
        const VkFormatFeatureFlags missing = reqs[i].need & ~fp.optimalTilingFeatures;

        char entry[96];
        snprintf(entry, sizeof(entry), "%s%s=%s", line[0] ? " " : "",
                 dis_format_name(reqs[i].format), missing ? "MISSING" : "ok");
        strncat(line, entry, sizeof(line) - strlen(line) - 1);

        if (missing) {
            char names[256];
            dis_missing_features(missing, names, sizeof(names));
            DIS_LOGW("DIS needs %s on %s for %s, and this device does not report it",
                     names, dis_format_name(reqs[i].format), reqs[i].purpose);
            ok = false;
        }
    }

    // Filtering the flow is the one requirement with an answer other than yes or
    // no. Linear filtering of 32-bit floats is optional and some mobile parts
    // filter only 16-bit ones, so when it is absent the interpolation pass does
    // the bilinear itself from texel fetches instead of DIS refusing to run. Four
    // fetches and two mixes per pixel, once per generated frame.
    VkFormatProperties flow_fp;
    memset(&flow_fp, 0, sizeof(flow_fp));
    vkd.GetPhysicalDeviceFormatProperties(d->physical_device, VK_FORMAT_R32G32_SFLOAT, &flow_fp);
    d->manual_flow_filter = (flow_fp.optimalTilingFeatures & FILTER) == 0;

    DIS_LOGI("DIS format support: %s | flow filtering: %s", line,
             d->manual_flow_filter ? "in shader (driver cannot filter R32G32_SFLOAT)"
                                   : "sampler");
    return ok;
}

VkrDis* vkr_dis_create(VkDevice device, VkPhysicalDevice physical_device) {
    if (device == VK_NULL_HANDLE || physical_device == VK_NULL_HANDLE) return NULL;

    VkrDis* d = (VkrDis*)calloc(1, sizeof(VkrDis));
    d->device = device;
    d->physical_device = physical_device;
    d->flow_min_side = DIS_DEFAULT_FLOW_MIN_SIDE;
    d->target_fps = 0;
    d->refresh_rate = 0.0f;
    vkd.GetPhysicalDeviceMemoryProperties(physical_device, &d->mem_props);
    // Both must precede dis_create_pipelines: the audit settles which luma
    // variant is built and how the interpolation pass is specialized.
    d->luma_format = dis_pick_luma_format(d);
    if (!dis_audit_formats(d)) {
        DIS_LOGW("DIS cannot run on this device's format support; frame generation stays off");
        vkr_dis_destroy(d);
        return NULL;
    }

    if (!dis_create_sampler(d) || !dis_create_pipelines(d)) {
        DIS_LOGW("DIS shaders could not be built; frame generation stays off");
        vkr_dis_destroy(d);
        return NULL;
    }
    if (!dis_allocate_sets(d)) {
        DIS_LOGW("DIS descriptor sets could not be allocated; frame generation stays off");
        vkr_dis_destroy(d);
        return NULL;
    }
    DIS_LOGI("DIS frame generation ready");
    return d;
}

void vkr_dis_destroy(VkrDis* d) {
    if (!d) return;
    dis_destroy_images(d);
    if (d->sampler) vkd.DestroySampler(d->device, d->sampler, NULL);
    if (d->pass_luma.pipeline) vkd.DestroyPipeline(d->device, d->pass_luma.pipeline, NULL);
    if (d->pass_gradient.pipeline) vkd.DestroyPipeline(d->device, d->pass_gradient.pipeline, NULL);
    if (d->pass_inverse.pipeline) vkd.DestroyPipeline(d->device, d->pass_inverse.pipeline, NULL);
    if (d->pass_propagate.pipeline) vkd.DestroyPipeline(d->device, d->pass_propagate.pipeline, NULL);
    if (d->pass_densify.pipeline) vkd.DestroyPipeline(d->device, d->pass_densify.pipeline, NULL);
    if (d->pass_interp.pipeline) vkd.DestroyPipeline(d->device, d->pass_interp.pipeline, NULL);
    if (d->pass_vr_prep.pipeline) vkd.DestroyPipeline(d->device, d->pass_vr_prep.pipeline, NULL);
    if (d->pass_vr_d1.pipeline) vkd.DestroyPipeline(d->device, d->pass_vr_d1.pipeline, NULL);
    if (d->pass_vr_d2.pipeline) vkd.DestroyPipeline(d->device, d->pass_vr_d2.pipeline, NULL);
    if (d->pass_vr_w.pipeline) vkd.DestroyPipeline(d->device, d->pass_vr_w.pipeline, NULL);
    if (d->pass_vr_coef.pipeline) vkd.DestroyPipeline(d->device, d->pass_vr_coef.pipeline, NULL);
    if (d->pass_vr_sor.pipeline) vkd.DestroyPipeline(d->device, d->pass_vr_sor.pipeline, NULL);
    if (d->pass_vr_add.pipeline) vkd.DestroyPipeline(d->device, d->pass_vr_add.pipeline, NULL);
    if (d->pool) vkd.DestroyDescriptorPool(d->device, d->pool, NULL);
    if (d->pipeline_layout) vkd.DestroyPipelineLayout(d->device, d->pipeline_layout, NULL);
    if (d->vr_pipeline_layout) vkd.DestroyPipelineLayout(d->device, d->vr_pipeline_layout, NULL);
    if (d->set_layout) vkd.DestroyDescriptorSetLayout(d->device, d->set_layout, NULL);
    if (d->vr_set_layout) vkd.DestroyDescriptorSetLayout(d->device, d->vr_set_layout, NULL);
    free(d);
}

void vkr_dis_configure(VkrDis* d, uint32_t flow_min_side, uint32_t target_fps, float refresh_rate) {
    if (!d) return;
    uint32_t side = flow_min_side < DIS_FLOW_MIN_SIDE_FLOOR ? DIS_FLOW_MIN_SIDE_FLOOR
                  : (flow_min_side > DIS_FLOW_MIN_SIDE_CEIL ? DIS_FLOW_MIN_SIDE_CEIL
                                                            : flow_min_side);
    d->flow_min_side = side;
    d->target_fps = target_fps;
    d->refresh_rate = refresh_rate > 0.0f ? refresh_rate : 0.0f;
}

void vkr_dis_set_debug_flow(VkrDis* d, bool debug_flow) {
    if (!d) return;
    d->debug_flow = debug_flow;
}

bool vkr_dis_needs_rebuild(const VkrDis* d, uint32_t width, uint32_t height, VkFormat format,
                           VkrDisContentRect content) {
    if (!d || d->unavailable) return false;
    // Only the content SIZE forces a rebuild; every image DIS owns is sized from
    // it. A rect that merely moved is handled by re-aiming the ingest blit, which
    // costs nothing and avoids tearing down resources the Turnip driver is known
    // to dislike having rebuilt mid-session.
    return !d->built || d->built_full_extent.width != width ||
           d->built_full_extent.height != height || d->built_format != format ||
           d->built_min_side != d->flow_min_side ||
           d->content.width != content.width || d->content.height != content.height;
}

bool vkr_dis_prepare(VkrDis* d, uint32_t width, uint32_t height, VkFormat format,
                     VkrDisContentRect content) {
    if (!d || d->unavailable) return false;
    if (width == 0 || height == 0 || format == VK_FORMAT_UNDEFINED) return false;

    // Fall back to the whole composite if the caller could not name a sub-rect.
    if (content.width < DIS_MIN_EXTENT || content.height < DIS_MIN_EXTENT ||
        content.x < 0 || content.y < 0 ||
        (uint32_t)content.x + content.width > width ||
        (uint32_t)content.y + content.height > height) {
        content.x = 0;
        content.y = 0;
        content.width = width;
        content.height = height;
    }
    d->content = content;

    uint32_t w, h;
    dis_flow_extent(d->flow_min_side, content.width, content.height, &w, &h);
    if (w < DIS_MIN_EXTENT) w = DIS_MIN_EXTENT;
    if (h < DIS_MIN_EXTENT) h = DIS_MIN_EXTENT;

    const uint32_t levels = dis_levels_for(w, h);

    if (d->built && d->built_extent.width == w && d->built_extent.height == h &&
        d->built_full_extent.width == width && d->built_full_extent.height == height &&
        d->built_format == format && d->built_min_side == d->flow_min_side && d->levels == levels &&
        d->content.width == content.width && d->content.height == content.height) {
        // Same geometry, possibly a different offset: just re-aim the crop.
        d->content.x = content.x;
        d->content.y = content.y;
        return true;
    }

    if (!d->formats_audited) {
        d->formats_audited = true;
        // The guest frame format only becomes known here. It is warped bilinearly
        // by the interpolation pass and downsampled by blit to build the pyramid.
        const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                          VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT |
                                          VK_FORMAT_FEATURE_TRANSFER_SRC_BIT |
                                          VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
        VkFormatProperties fp;
        memset(&fp, 0, sizeof(fp));
        vkd.GetPhysicalDeviceFormatProperties(d->physical_device, format, &fp);
        const VkFormatFeatureFlags missing = need & ~fp.optimalTilingFeatures;
        if (missing) {
            char names[256];
            dis_missing_features(missing, names, sizeof(names));
            DIS_LOGW("DIS needs %s on the guest frame format (%d) and this device does not "
                     "report it; frame generation stays off", names, (int)format);
            d->unavailable = true;
            return false;
        }
    }

    d->levels = levels;

    if (!dis_create_resources(d, w, h, content.width, content.height, format)) {
        DIS_LOGW("DIS resource build failed at %ux%u; frame generation unavailable", w, h);
        dis_destroy_images(d);
        d->unavailable = true;
        return false;
    }

    d->built_extent.width = w;
    d->built_extent.height = h;
    d->built_full_extent.width = width;
    d->built_full_extent.height = height;
    d->built_format = format;
    d->built_min_side = d->flow_min_side;
    d->built = true;
    // The slot ring now points at images that were just created, so the frame
    // this would call "prev" holds nothing. Restart the ring: vkr_dis_plan needs
    // two ingested frames before it will ask for any generation, which is exactly
    // the warm-up a rebuild needs. The measured guest rate is deliberately kept -
    // the geometry changed, the frame rate did not.
    d->frame_count = 0;
    d->prev_idx = 0;
    d->next_idx = 0;
    d->active_slot = 0;
    d->last_generations = 0;
    DIS_LOGI("DIS resources built at %ux%u (flow min side %u, %u levels); content rect "
             "%dx%d+%d+%d inside a %ux%u composite",
             w, h, d->flow_min_side, levels, (int)content.width, (int)content.height,
             (int)content.x, (int)content.y, width, height);
    return true;
}

// Folds one observation of the compositor's guest-frame counter into a smoothed
// estimate of the interval between source frames. This is the only rate the
// planner may key off: it is independent of how many frames DIS itself emits.
static void dis_track_source(VkrDis* d, uint64_t now, uint64_t source_frames) {
    if (d->src_sample_ns == 0) {
        d->src_sample_ns = now;
        d->src_last_frames = source_frames;
        return;
    }

    const uint64_t dt = now - d->src_sample_ns;
    if (dt == 0) return;
    d->src_sample_ns = now;

    const uint64_t drawn =
        source_frames > d->src_last_frames ? source_frames - d->src_last_frames : 0;
    d->src_last_frames = source_frames;

    // A long gap means the guest stalled or the window was occluded; the old
    // average says nothing useful about what comes next.
    if (dt > DIS_SRC_STALE_NS) {
        d->src_frame_accum = 0.0f;
        d->src_time_accum = 0.0f;
        d->src_interval = 0.0f;
        d->src_samples = 0;
        return;
    }

    const float elapsed = (float)dt * 1.0e-9f;
    d->src_frame_accum += ((float)drawn - d->src_frame_accum) * DIS_SRC_SMOOTHING;
    d->src_time_accum += (elapsed - d->src_time_accum) * DIS_SRC_SMOOTHING;
    d->src_interval =
        d->src_frame_accum > 0.01f ? d->src_time_accum / d->src_frame_accum : 0.0f;
    if (d->src_samples < DIS_MIN_RATE_SAMPLES) d->src_samples++;
}

uint32_t vkr_dis_plan(VkrDis* d, uint32_t capacity, uint64_t source_frames) {
    if (!d || d->unavailable || !d->built) return 0;
    if (capacity > VKR_DIS_MAX_GENERATIONS) capacity = VKR_DIS_MAX_GENERATIONS;

    const uint64_t now = dis_now_ns();
    dis_track_source(d, now, source_frames);

    if (capacity == 0 || d->frame_count < 2 || d->src_samples < DIS_MIN_RATE_SAMPLES ||
        d->src_interval <= 0.0f) {
        d->planned_gen = 0;
        d->gen_high_streak = 0;
        d->gen_low_streak = 0;
        return 0;
    }

    const float source_rate = 1.0f / d->src_interval;
    float desired = d->target_fps > 0 ? (float)d->target_fps : d->refresh_rate;
    if (d->refresh_rate > 0.0f && desired > d->refresh_rate) desired = d->refresh_rate;
    if (desired <= 0.0f) return 0;

    // Smooth only the desired (refresh) rate, so a thermal throttle step ramps
    // the generation count instead of jumping it.
    if (d->smoothed_desired <= 0.0f) {
        d->smoothed_desired = desired;
    } else {
        d->smoothed_desired += (desired - d->smoothed_desired) * 0.25f;
    }
    const float eff_desired = d->smoothed_desired;

    if (eff_desired <= source_rate) {
        d->planned_gen = 0;
        d->gen_high_streak = 0;
        d->gen_low_streak = 0;
        return 0;
    }

    // How many outputs fit inside one source interval. eff_desired is already
    // clamped to the refresh rate, so flooring here is by itself what stops the
    // panel being over-driven, and every generated frame is guaranteed a vblank
    // of its own. Rounding instead of flooring would ask for a frame the panel
    // cannot show; a separate headroom test would be redundant and, expressed as
    // a hard threshold, actively harmful - it read zero the moment the measured
    // rate crossed 60.5 fps on a 120 Hz panel and switched generation off
    // entirely for a guest sitting right on 60.
    //
    // The epsilon widens the band around an exact integer ratio, which is where a
    // solid 60 fps guest lives. Erring high costs at most a couple of percent of
    // overshoot, absorbed by the acquire loop delivering fewer frames than
    // planned; erring low turns the feature off outright.
    int raw = (int)(eff_desired / source_rate + 0.05f) - 1;
    if (raw < 0) raw = 0;
    if (raw > (int)capacity) raw = (int)capacity;

    // Hysteresis: ramp up after two consecutive higher readings, ramp down after
    // three consecutive lower readings, so transient jitter does not make the
    // generation count flicker between adjacent values.
    if (raw > d->planned_gen) {
        d->gen_low_streak = 0;
        d->gen_high_streak++;
        if (d->gen_high_streak >= 2) {
            d->planned_gen = raw;
            d->gen_high_streak = 0;
        }
    } else if (raw < d->planned_gen) {
        d->gen_high_streak = 0;
        d->gen_low_streak++;
        if (d->gen_low_streak >= 3) {
            d->planned_gen = raw;
            d->gen_low_streak = 0;
        }
    } else {
        d->gen_high_streak = 0;
        d->gen_low_streak = 0;
    }

    return (uint32_t)d->planned_gen;
}

void vkr_dis_process(VkrDis* d, VkCommandBuffer cmd, VkImage source, uint32_t width,
                     uint32_t height, uint32_t generations) {
    if (!d || !d->built || d->unavailable) return;

    // Rate tracking lives in vkr_dis_plan, which is fed the guest frame counter.
    // Timing the interval between these calls would measure the compositor loop,
    // and the loop rate is a consequence of the generation count, not an input to
    // it.
    d->last_generations = generations;

    // First command buffer after a build: nothing has established a layout for
    // these images yet.
    dis_prime_layouts(d, cmd);

    const DisRefine refine = dis_refine_for(generations);
    const uint32_t L = d->levels;
    const uint32_t coarse = L - 1;
    const uint32_t w = d->built_extent.width;
    const uint32_t h = d->built_extent.height;
    // full_w/full_h are the CONTENT size, not the composite size: every image
    // DIS owns is content-sized, and the source sub-rect is cropped on ingest.
    const uint32_t full_w = d->content.width;
    const uint32_t full_h = d->content.height;
    const int32_t cx = d->content.x;
    const int32_t cy = d->content.y;
    (void)width; (void)height;

    // Round-robin over DIS_SLOTS rather than a two-slot ping-pong: with two
    // frames in flight, the slot frame N wants to write is the one frame N-1 is
    // still reading as its "prev".
    const uint32_t slot = (uint32_t)(d->frame_count % DIS_SLOTS);
    DisImage* full_dst = &d->color[slot];
    DisImage* flow_dst = &d->flow_color[slot];

    // Blit the source into the full-res slot (interpolation) ...
    dis_barrier(cmd, full_dst->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
    dis_blit_rect(cmd, source, cx, cy, full_w, full_h,
                  full_dst->image, 0, 0, full_w, full_h, VK_FILTER_LINEAR);
    dis_barrier(cmd, full_dst->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);

    // ... and into the scaled flow slot, then build its mip pyramid.
    dis_barrier(cmd, flow_dst->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
    dis_blit_rect(cmd, source, cx, cy, full_w, full_h,
                  flow_dst->image, 0, 0, w, h, VK_FILTER_LINEAR);
    for (uint32_t l = 1; l < L; l++) {
        dis_barrier(cmd, flow_dst->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        dis_blit_mip(cmd, flow_dst->image, l - 1, l, w >> (l - 1), h >> (l - 1), w >> l, h >> l);
    }
    dis_barrier(cmd, flow_dst->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);

    d->next_idx = (int)slot;
    d->prev_idx = (int)((slot + DIS_SLOTS - 1u) % DIS_SLOTS);
    d->active_slot = slot;
    d->frame_count++;

    // Part of the ingest, not of the estimate: the luminance plane for this slot
    // has to exist for as long as the slot does, because the NEXT frame reads it
    // as its "prev". Leaving it below the early-out meant a frame that generated
    // nothing wrote no luminance, and the first frame to generate afterwards
    // matched against whatever was left in that slot three frames ago. One fetch
    // and one store per texel, about 1% of the frame's texture work, in exchange
    // for taking the dot product out of the 16-65 million fetches that follow and
    // halving the bytes each of them moves.
    for (uint32_t l = 0; l < L; l++) {
        const uint32_t lw = w >> l;
        const uint32_t lh = h >> l;
        vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_luma.pipeline);
        vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pipeline_layout, 0, 1,
                                  &d->luma_sets[slot][l], 0, NULL);
        vkd.CmdDispatch(cmd, (lw + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                        (lh + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);
    }

    dis_compute_barrier(cmd);

    // Everything above is ingest: it keeps the slot ring fed so the next frame
    // has a "prev" to interpolate from, and it has to run every frame. Everything
    // below estimates the flow, and the only consumer of that flow is
    // vkr_dis_generate_into - which returns immediately when no frames are being
    // generated. A guest already sitting at the panel's refresh rate asks for
    // zero generations for minutes at a time, and until now it paid for the full
    // pyramid, search and refinement on every one of those frames to produce a
    // result nothing read. When generation resumes, that frame asks for a
    // non-zero count and computes the flow normally, so nothing is stale.
    if (generations == 0 && !d->debug_flow) return;

    DisGradientPC gpc;
    gpc.lesser = 3.0f;
    gpc.upper = 10.0f;
    gpc.normVal = 1.0f / (2.0f * 10.0f + 4.0f * 3.0f);

    // Gradient at every level.
    for (uint32_t l = 0; l < L; l++) {
        const uint32_t lw = w >> l;
        const uint32_t lh = h >> l;
        vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_gradient.pipeline);
        vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pipeline_layout, 0, 1,
                                  &d->grad_sets[slot][l], 0, NULL);
        vkd.CmdPushConstants(cmd, d->pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(gpc), &gpc);
        vkd.CmdDispatch(cmd, (lw + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                        (lh + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);
    }

    dis_compute_barrier(cmd);

    // Inverse search + densify, coarse-to-fine.
    for (uint32_t li = 0; li < L; li++) {
        const uint32_t l = coarse - li;  // from coarse down to 0
        const uint32_t lw = w >> l;
        const uint32_t lh = h >> l;
        const uint32_t spw = dis_sparse_extent(lw);
        const uint32_t sph = dis_sparse_extent(lh);

        DisInversePC ipc;
        ipc.level = (int)l;
        ipc.coarseLevel = (int)coarse;
        vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_inverse.pipeline);
        vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pipeline_layout, 0, 1,
                                  &d->inverse_sets[slot][l], 0, NULL);
        vkd.CmdPushConstants(cmd, d->pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(ipc), &ipc);
        vkd.CmdDispatch(cmd, (spw + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                        (sph + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);

        dis_compute_barrier(cmd);

        // Spatial propagation: doubling-distance candidate exchange, ping-ponging
        // between the two sparse flow buffers. Each pass now scores all four
        // neighbours at that distance, so one dispatch does what used to take
        // four - two directions x two axes - with a pipeline barrier between
        // every one of them.
        //
        // That mattered more than it looks. These grids are tiny: at the coarse
        // levels a couple of hundred texels, three workgroups. The cost was
        // almost entirely launch and barrier overhead, and propagation alone was
        // two thirds of every dispatch in the frame. The number of candidate
        // evaluations is unchanged and the texture traffic per candidate is
        // halved, because the 8x8 reference block is now fetched once for four
        // candidates instead of once for one.
        //
        // The ping-pong has to land back in flow_sparse[l], which densify reads,
        // so the pass count is rounded up to even. The appended pass, when there
        // is one, repeats distance 1 - a local cleanup round, which is the useful
        // one to end on anyway.
        uint32_t prop_passes = dis_prop_steps_for(l, L, refine.prop_floor);
        const uint32_t prop_doubling = prop_passes;
        if (prop_passes & 1u) prop_passes++;

        for (uint32_t k = 0; k < prop_passes; k++) {
            VkDescriptorSet prop_set =
                (k & 1u) ? d->prop_ba_sets[slot][l] : d->prop_ab_sets[slot][l];
            DisPropPC ppc;
            ppc.dist = k < prop_doubling ? (int)(1u << k) : 1;
            vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_propagate.pipeline);
            vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pipeline_layout, 0,
                                      1, &prop_set, 0, NULL);
            vkd.CmdPushConstants(cmd, d->pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                                 sizeof(ppc), &ppc);
            vkd.CmdDispatch(cmd, (spw + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                            (sph + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);

            dis_compute_barrier(cmd);
        }

        dis_dispatch(d, cmd, d->pass_densify.pipeline, d->densify_sets[slot][l], lw, lh);

        dis_compute_barrier(cmd);
    }

    // Variational refinement (SOR) at the finest level: smooths the dense flow
    // and extrapolates it to the borders, fixing the edge artifacts left by the
    // coarse patch-based flow.
    const uint32_t gw = (w + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE;
    const uint32_t gh = (h + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE;

    vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_vr_prep.pipeline);
    vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                              &d->vr_prep_sets[slot], 0, NULL);
    vkd.CmdDispatch(cmd, gw, gh, 1);
    dis_compute_barrier(cmd);

    vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_vr_d1.pipeline);
    vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                              &d->vr_d1_set, 0, NULL);
    vkd.CmdDispatch(cmd, gw, gh, 1);
    dis_compute_barrier(cmd);

    vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_vr_d2.pipeline);
    vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                              &d->vr_d2_set, 0, NULL);
    vkd.CmdDispatch(cmd, gw, gh, 1);
    dis_compute_barrier(cmd);

    for (uint32_t k = 0; k < refine.vr_fixed_point; k++) {
        DisVrWPC wpc;
        wpc.alpha2 = DIS_VR_ALPHA * 0.5f;
        wpc.eps2 = DIS_VR_EPS * DIS_VR_EPS;
        vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_vr_w.pipeline);
        vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                                  &d->vr_w_set, 0, NULL);
        vkd.CmdPushConstants(cmd, d->vr_pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                             sizeof(wpc), &wpc);
        vkd.CmdDispatch(cmd, gw, gh, 1);
        dis_compute_barrier(cmd);

        DisVrCoefPC cpc;
        cpc.delta2 = DIS_VR_DELTA * 0.5f;
        cpc.gamma2 = DIS_VR_GAMMA * 0.5f;
        cpc.zeta2 = DIS_VR_ZETA * DIS_VR_ZETA;
        cpc.eps2 = DIS_VR_EPS * DIS_VR_EPS;
        vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_vr_coef.pipeline);
        vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                                  &d->vr_coef_set, 0, NULL);
        vkd.CmdPushConstants(cmd, d->vr_pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                             sizeof(cpc), &cpc);
        vkd.CmdDispatch(cmd, gw, gh, 1);
        dis_compute_barrier(cmd);

        for (uint32_t s = 0; s < refine.vr_sor; s++) {
            DisVrSorPC spc;
            spc.omega = DIS_VR_OMEGA;
            spc.parity = 0;
            vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_vr_sor.pipeline);
            vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0,
                                      1, &d->vr_sor_ab_set, 0, NULL);
            vkd.CmdPushConstants(cmd, d->vr_pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                                 sizeof(spc), &spc);
            vkd.CmdDispatch(cmd, gw, gh, 1);
            dis_compute_barrier(cmd);

            spc.parity = 1;
            vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0,
                                      1, &d->vr_sor_ba_set, 0, NULL);
            vkd.CmdPushConstants(cmd, d->vr_pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                                 sizeof(spc), &spc);
            vkd.CmdDispatch(cmd, gw, gh, 1);
            dis_compute_barrier(cmd);
        }
    }

    vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_vr_add.pipeline);
    vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->vr_pipeline_layout, 0, 1,
                              &d->vr_add_set, 0, NULL);
    vkd.CmdDispatch(cmd, gw, gh, 1);

    dis_compute_barrier(cmd);
}

// Shared by the generated frames and by the debug view of a real frame. t is
// where between prev and next to land; in debug mode it is ignored, since the
// pass paints the flow field rather than a warp of the image.
static void dis_render_into(VkrDis* d, VkCommandBuffer cmd, float t, int debug_mode,
                            VkImage target_image, uint32_t width, uint32_t height,
                            VkImage base_image) {
    const uint32_t w = d->content.width;
    const uint32_t h = d->content.height;

    DisInterpPC ipc;
    ipc.t = t;
    ipc.debugMode = debug_mode;

    vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pass_interp.pipeline);
    vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d->pipeline_layout, 0, 1,
                              &d->interp_sets[d->active_slot], 0, NULL);
    vkd.CmdPushConstants(cmd, d->pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(ipc), &ipc);
    vkd.CmdDispatch(cmd, (w + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE,
                    (h + DIS_LOCAL_SIZE - 1) / DIS_LOCAL_SIZE, 1);

    dis_barrier(cmd, d->interp_out.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);

    dis_barrier(cmd, target_image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                VK_ACCESS_TRANSFER_WRITE_BIT);

    // Map the content rect from composite space into the target.
    const uint32_t comp_w = d->built_full_extent.width;
    const uint32_t comp_h = d->built_full_extent.height;
    const float tsx = comp_w > 0 ? (float)width / (float)comp_w : 1.0f;
    const float tsy = comp_h > 0 ? (float)height / (float)comp_h : 1.0f;
    const int32_t tx = (int32_t)((float)d->content.x * tsx + 0.5f);
    const int32_t ty = (int32_t)((float)d->content.y * tsy + 0.5f);
    uint32_t tw = (uint32_t)((float)w * tsx + 0.5f);
    uint32_t th = (uint32_t)((float)h * tsy + 0.5f);
    if (tw == 0) tw = 1;
    if (th == 0) th = 1;
    if ((uint32_t)tx + tw > width) tw = width - (uint32_t)tx;
    if ((uint32_t)ty + th > height) th = height - (uint32_t)ty;

    // Carry over only what the interpolated content will not cover: the letterbox
    // strips. Blitting the whole composite first and then painting 80% of it over
    // again was the single largest write in the chain - at 1024x720 content on a
    // 2400x1080 panel with three generated frames it threw away 6.2 Mpx a frame.
    //
    // The four strips are expressed in composite space and mapped with the same
    // tx/ty/tw/th as the content, so strips and content tile the target exactly:
    // no seam, no double write. Left and right span full height, top and bottom
    // only the content's columns, so the corners are written once.
    if (base_image != VK_NULL_HANDLE) {
        const int32_t cx = d->content.x;
        const int32_t cy = d->content.y;
        const int32_t cr = cx + (int32_t)w;
        const int32_t cb = cy + (int32_t)h;
        const int32_t trx = tx + (int32_t)tw;
        const int32_t tby = ty + (int32_t)th;
        const int32_t src[4][4] = {
            {0,  0,  cx,                    (int32_t)comp_h},
            {cr, 0,  (int32_t)comp_w - cr,  (int32_t)comp_h},
            {cx, 0,  (int32_t)w,            cy},
            {cx, cb, (int32_t)w,            (int32_t)comp_h - cb},
        };
        const int32_t dst[4][4] = {
            {0,   0,   tx,                     (int32_t)height},
            {trx, 0,   (int32_t)width - trx,   (int32_t)height},
            {tx,  0,   (int32_t)tw,            ty},
            {tx,  tby, (int32_t)tw,            (int32_t)height - tby},
        };
        bool any_strip = false;
        for (uint32_t i = 0; i < 4; i++) {
            if (src[i][2] <= 0 || src[i][3] <= 0 || dst[i][2] <= 0 || dst[i][3] <= 0) continue;
            dis_blit_rect(cmd, base_image, src[i][0], src[i][1],
                          (uint32_t)src[i][2], (uint32_t)src[i][3],
                          target_image, dst[i][0], dst[i][1],
                          (uint32_t)dst[i][2], (uint32_t)dst[i][3], VK_FILTER_LINEAR);
            any_strip = true;
        }
        if (any_strip) {
            dis_barrier(cmd, target_image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        }
    }

    dis_blit_rect(cmd, d->interp_out.image, 0, 0, w, h,
                  target_image, tx, ty, tw, th, VK_FILTER_LINEAR);

    dis_barrier(cmd, d->interp_out.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);
}

void vkr_dis_generate_into(VkrDis* d, VkCommandBuffer cmd, uint32_t generation,
                           uint32_t target_index, VkImage target_image, VkImageView target_view,
                           uint32_t width, uint32_t height, VkImage base_image) {
    (void)target_index;
    (void)target_view;
    if (!d || !d->built || d->unavailable) return;
    if (d->last_generations == 0) return;

    const float t = (float)(generation + 1) / (float)(d->last_generations + 1);
    dis_render_into(d, cmd, t, d->debug_flow ? 1 : 0, target_image, width, height, base_image);
}

void vkr_dis_debug_into(VkrDis* d, VkCommandBuffer cmd, VkImage target_image, uint32_t width,
                        uint32_t height) {
    if (!d || !d->built || d->unavailable || !d->debug_flow) return;
    // Needs a prev and a next in the slot ring, the same as an interpolated frame.
    if (d->frame_count < 2) return;
    // Real frames used to go to the panel untouched while only the generated ones
    // were replaced by the flow field, so the display alternated between the game
    // and the debug view at the generation ratio - which reads as a flicker, not
    // as a visualisation. Painting the real frame too makes the view steady.
    dis_render_into(d, cmd, 0.5f, 1, target_image, width, height, VK_NULL_HANDLE);
}

void vkr_dis_forget_targets(VkrDis* d) {
    (void)d;
}

void vkr_dis_reset(VkrDis* d) {
    if (!d) return;
    d->frame_count = 0;
    d->last_generations = 0;
    d->prev_idx = 0;
    d->next_idx = 0;
    d->active_slot = 0;
    d->src_sample_ns = 0;
    d->src_last_frames = 0;
    d->src_frame_accum = 0.0f;
    d->src_time_accum = 0.0f;
    d->src_interval = 0.0f;
    d->src_samples = 0;
    d->smoothed_desired = 0.0f;
    d->planned_gen = 0;
    d->gen_high_streak = 0;
    d->gen_low_streak = 0;
}
