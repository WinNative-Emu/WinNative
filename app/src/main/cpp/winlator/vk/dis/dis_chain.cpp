// SPDX-License-Identifier: GPL-3.0-or-later

#include "dis_chain.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#include "shaders/dis_gray_comp.spv.h"
#include "shaders/dis_downsample_comp.spv.h"
#include "shaders/dis_sobel_comp.spv.h"
#include "shaders/dis_structure_comp.spv.h"
#include "shaders/dis_propagate_comp.spv.h"
#include "shaders/dis_descent_comp.spv.h"
#include "shaders/dis_densify_comp.spv.h"
#include "shaders/dis_vr_prepare_comp.spv.h"
#include "shaders/dis_vr_weights_comp.spv.h"
#include "shaders/dis_vr_coeffs_comp.spv.h"
#include "shaders/dis_vr_sor_comp.spv.h"
#include "shaders/dis_vr_add_resize_comp.spv.h"
#include "shaders/dis_interp_comp.spv.h"

namespace dis {

namespace {

constexpr uint32_t TILE_SHIFT = 3; // 8x8 workgroup

// Algorithm parameters, matching dis_flow_webgl2_2.html defaults.
constexpr uint32_t PSZ = 8;
constexpr uint32_t PSTR = 4;
constexpr uint32_t FINEST = 2;
constexpr uint32_t GD_ITERS = 16;
constexpr uint32_t INNER_ITERS = GD_ITERS / 2;
constexpr uint32_t PROP_STEPS = 4;
constexpr uint32_t FP_ITERS = 5;
constexpr uint32_t SOR_ITERS = 5;
constexpr float ALPHA2 = 20.0f * 0.5f;
constexpr float DELTA2 = 5.0f * 0.5f;
constexpr float GAMMA2 = 10.0f * 0.5f;
constexpr float ZETA2 = 0.1f * 0.1f;
constexpr float EPS2 = 0.001f * 0.001f;
constexpr float OMEGA = 1.6f;
constexpr float DEBUG_MAG = 8.0f;

struct GrayPC {
    float dstSize[2];
    float rectOffset[2];
    float rectScale[2];
};

struct StructurePC {
    int32_t sparse[2];
    int32_t stride;
    int32_t psz2;
};

struct PropPC {
    float size[2];
    int32_t off[2];
    int32_t stride;
};

struct DescentPC {
    float size[2];
    int32_t stride;
    int32_t iters;
};

struct DensifyPC {
    float size[2];
    int32_t sparse[2];
    int32_t stride;
};

struct VrPreparePC {
    float size[2];
};

struct VrWeightsPC {
    float alpha2;
    float eps2;
};

struct VrCoeffsPC {
    float size[2];
    float delta2;
    float gamma2;
    float zeta2;
    float eps2;
};

struct VrSorPC {
    float omega;
    int32_t parity;
};

struct VrAddPC {
    float scale;
};

struct InterpPC {
    float rectOffset[2];
    float rectScale[2];
    float invFlowSize[2];
    float t;
    float mag;
    int32_t debugMode;
};

VkDescriptorSetLayoutBinding Binding(uint32_t binding, VkDescriptorType type,
                                     VkShaderStageFlags stage = VK_SHADER_STAGE_COMPUTE_BIT) {
    VkDescriptorSetLayoutBinding b{};
    b.binding = binding;
    b.descriptorType = type;
    b.descriptorCount = 1;
    b.stageFlags = stage;
    return b;
}

void ImageBarrier(VkCommandBuffer cmd, VkImage image, VkAccessFlags src_access,
                  VkAccessFlags dst_access, VkPipelineStageFlags src_stage,
                  VkPipelineStageFlags dst_stage) {
    VkImageMemoryBarrier b{};
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.srcAccessMask = src_access;
    b.dstAccessMask = dst_access;
    b.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image;
    b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    b.subresourceRange.levelCount = 1;
    b.subresourceRange.layerCount = 1;
    vkd.CmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, nullptr, 0, nullptr, 1, &b);
}

void ComputeToComputeBarrier(VkCommandBuffer cmd, VkImage image, VkAccessFlags src_access,
                             VkAccessFlags dst_access) {
    ImageBarrier(cmd, image, src_access, dst_access, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
}

// The interp target (composite ring / storage-capable swapchain image) is fully overwritten every
// generation, so discard its previous contents rather than tracking its layout across frames.
void DiscardImage(VkCommandBuffer cmd, VkImage image) {
    VkImageMemoryBarrier b{};
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.srcAccessMask = 0;
    b.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image;
    b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    b.subresourceRange.levelCount = 1;
    b.subresourceRange.layerCount = 1;
    vkd.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
}

void ClearImage(VkCommandBuffer cmd, VkImage image) {
    VkClearColorValue clear{};
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.levelCount = 1;
    range.layerCount = 1;

    ImageBarrier(cmd, image, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                 VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                 VK_PIPELINE_STAGE_TRANSFER_BIT);
    vkd.CmdClearColorImage(cmd, image, VK_IMAGE_LAYOUT_GENERAL, &clear, 1, &range);
    ImageBarrier(cmd, image, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                 VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
}

const DisImage& LevelS(const DisChain::Level& level, uint32_t index) {
    return index == 0 ? level.S0 : level.S1;
}

const DisImage& LevelDW(const DisChain::Level& level, uint32_t index) {
    return index == 0 ? level.dW0 : level.dW1;
}

} // namespace

DisChain::DisChain(VkDevice device_, VkPhysicalDevice physical_device_)
    : device{device_}, physical_device{physical_device_} {}

DisChain::~DisChain() {
    DestroyResources();
}

void DisChain::DestroyResources() {
    if (device == VK_NULL_HANDLE) return;

    if (sampler) vkd.DestroySampler(device, sampler, nullptr);
    if (descriptor_pool) vkd.DestroyDescriptorPool(device, descriptor_pool, nullptr);

    sampler = VK_NULL_HANDLE;
    descriptor_pool = VK_NULL_HANDLE;

    gray_sets = {};
    down_sets = {};
    sobel_sets = {};
    structure_sets = {};
    propagate_sets = {};
    descent_sets = {};
    densify_sets = {};
    vr_prepare_sets = {};
    vr_weight_sets = {};
    vr_coeff_sets = {};
    vr_sor_sets = {};
    vr_add_sets = {};
    interp_sets = {};

    for (auto& slot : pyr_img) slot = {};
    for (auto& slot : pyr_grad) slot = {};
    for (auto& level : levels) level = Level{};
    flow_full = DisImage();
    fullres_views = {};
    content_rect = {};

    valid = false;
}

bool DisChain::Build(VkExtent2D flow_extent_, VkExtent2D target_extent_) {
    DestroyResources();

    if (flow_extent_.width == 0 || flow_extent_.height == 0) return false;

    VkPhysicalDeviceMemoryProperties props{};
    vkd.GetPhysicalDeviceMemoryProperties(physical_device, &props);
    SetDeviceMemoryProperties(props);

    flow_extent = flow_extent_;
    target_extent = target_extent_;

    // Level count as in dis_flow_webgl2_2.html:
    // coarsest = min(floor(log2(max/(4*psz))+0.5), floor(log2(min/psz))), clamped to 0..6.
    const double maxd = static_cast<double>(std::max(flow_extent.width, flow_extent.height));
    const double mind = static_cast<double>(std::min(flow_extent.width, flow_extent.height));
    int32_t coarsest = static_cast<int32_t>(
        std::min(std::floor(std::log2(maxd / (4.0 * PSZ)) + 0.5),
                 std::floor(std::log2(mind / static_cast<double>(PSZ)))));
    if (coarsest < 0) coarsest = 0;
    if (coarsest > 6) coarsest = 6;
    if (coarsest >= static_cast<int32_t>(DIS_MAX_LEVELS)) {
        coarsest = static_cast<int32_t>(DIS_MAX_LEVELS) - 1;
    }
    int32_t finest = std::min(static_cast<int32_t>(FINEST), coarsest);

    coarsest_level = static_cast<uint32_t>(coarsest);
    finest_level = static_cast<uint32_t>(finest);
    num_levels = coarsest_level + 1;

    descriptor_pool = CreateDescriptorPool(device, 1024);
    if (descriptor_pool == VK_NULL_HANDLE) return false;

    sampler = CreateSampler(device);
    if (sampler == VK_NULL_HANDLE) return false;

    // Compute passes.
    gray_pass = DisPass(device, dis_gray_comp, dis_gray_comp_size,
                        {Binding(0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                         Binding(1, VK_DESCRIPTOR_TYPE_SAMPLER),
                         Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)},
                        VK_SHADER_STAGE_COMPUTE_BIT,
                        {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(GrayPC)});
    down_pass = DisPass(device, dis_downsample_comp, dis_downsample_comp_size,
                        {Binding(0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                         Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)});
    sobel_pass = DisPass(device, dis_sobel_comp, dis_sobel_comp_size,
                         {Binding(0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                          Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)});
    structure_pass = DisPass(device, dis_structure_comp, dis_structure_comp_size,
                             {Binding(0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                              Binding(1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                              Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                              Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                              Binding(4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)},
                             VK_SHADER_STAGE_COMPUTE_BIT,
                             {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(StructurePC)});
    propagate_pass = DisPass(device, dis_propagate_comp, dis_propagate_comp_size,
                             {Binding(0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                              Binding(1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                              Binding(2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                              Binding(3, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                              Binding(4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)},
                             VK_SHADER_STAGE_COMPUTE_BIT,
                             {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(PropPC)});
    descent_pass = DisPass(device, dis_descent_comp, dis_descent_comp_size,
                           {Binding(0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                            Binding(1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                            Binding(2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                            Binding(3, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                            Binding(4, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                            Binding(5, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                            Binding(6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)},
                           VK_SHADER_STAGE_COMPUTE_BIT,
                           {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(DescentPC)});
    densify_pass = DisPass(device, dis_densify_comp, dis_densify_comp_size,
                           {Binding(0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                            Binding(1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                            Binding(2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                            Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)},
                           VK_SHADER_STAGE_COMPUTE_BIT,
                           {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(DensifyPC)});
    vr_prepare_pass = DisPass(device, dis_vr_prepare_comp, dis_vr_prepare_comp_size,
                              {Binding(0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                               Binding(1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                               Binding(2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                               Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)},
                              VK_SHADER_STAGE_COMPUTE_BIT,
                              {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(VrPreparePC)});
    vr_weights_pass = DisPass(device, dis_vr_weights_comp, dis_vr_weights_comp_size,
                              {Binding(0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                               Binding(1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                               Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)},
                              VK_SHADER_STAGE_COMPUTE_BIT,
                              {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(VrWeightsPC)});
    vr_coeffs_pass = DisPass(device, dis_vr_coeffs_comp, dis_vr_coeffs_comp_size,
                             {Binding(0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                              Binding(1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                              Binding(2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                              Binding(3, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                              Binding(4, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                              Binding(5, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                              Binding(6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                              Binding(7, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)},
                             VK_SHADER_STAGE_COMPUTE_BIT,
                             {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(VrCoeffsPC)});
    vr_sor_pass = DisPass(device, dis_vr_sor_comp, dis_vr_sor_comp_size,
                          {Binding(0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                           Binding(1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                           Binding(2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                           Binding(3, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                           Binding(4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)},
                          VK_SHADER_STAGE_COMPUTE_BIT,
                          {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(VrSorPC)});
    vr_add_resize_pass = DisPass(device, dis_vr_add_resize_comp, dis_vr_add_resize_comp_size,
                                 {Binding(0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                                  Binding(1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                                  Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)},
                                 VK_SHADER_STAGE_COMPUTE_BIT,
                                 {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(VrAddPC)});
    interp_pass = DisPass(device, dis_interp_comp, dis_interp_comp_size,
                          {Binding(0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                           Binding(1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                           Binding(2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                           Binding(3, VK_DESCRIPTOR_TYPE_SAMPLER),
                           Binding(4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)},
                          VK_SHADER_STAGE_COMPUTE_BIT,
                          {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(InterpPC)});

    if (!gray_pass.Valid() || !down_pass.Valid() || !sobel_pass.Valid() ||
        !structure_pass.Valid() || !propagate_pass.Valid() || !descent_pass.Valid() ||
        !densify_pass.Valid() || !vr_prepare_pass.Valid() || !vr_weights_pass.Valid() ||
        !vr_coeffs_pass.Valid() || !vr_sor_pass.Valid() || !vr_add_resize_pass.Valid() ||
        !interp_pass.Valid()) {
        return false;
    }

    const VkImageUsageFlags gray_usage =
        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
    const VkImageUsageFlags grad_usage =
        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
    const VkImageUsageFlags work_usage =
        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
    const VkImageUsageFlags clear_usage =
        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    for (uint32_t slot = 0; slot < 2; slot++) {
        for (uint32_t lvl = 0; lvl < num_levels; lvl++) {
            VkExtent2D e{std::max(1u, flow_extent.width >> lvl),
                         std::max(1u, flow_extent.height >> lvl)};
            pyr_img[slot][lvl] =
                DisImage(device, e, VK_FORMAT_R16_SFLOAT, gray_usage);
            if (!pyr_img[slot][lvl].Valid()) return false;
            if (lvl >= finest_level) {
                pyr_grad[slot][lvl] =
                    DisImage(device, e, VK_FORMAT_R16G16_SFLOAT, grad_usage);
                if (!pyr_grad[slot][lvl].Valid()) return false;
            }
        }
    }

    for (uint32_t lvl = finest_level; lvl < num_levels; lvl++) {
        Level& L = levels[lvl];
        L.active = true;
        L.index = lvl;
        L.extent = {std::max(1u, flow_extent.width >> lvl),
                    std::max(1u, flow_extent.height >> lvl)};
        const uint32_t ws = L.extent.width >= PSZ ? 1 + (L.extent.width - PSZ) / PSTR : 1;
        const uint32_t hs = L.extent.height >= PSZ ? 1 + (L.extent.height - PSZ) / PSTR : 1;
        L.sparse = {ws, hs};

        L.U = DisImage(device, L.extent, VK_FORMAT_R32G32_SFLOAT, clear_usage);
        L.S0 = DisImage(device, L.sparse, VK_FORMAT_R32G32_SFLOAT, work_usage);
        L.S1 = DisImage(device, L.sparse, VK_FORMAT_R32G32_SFLOAT, work_usage);
        L.ST = DisImage(device, L.sparse, VK_FORMAT_R32G32B32A32_SFLOAT, work_usage);
        L.ST2 = DisImage(device, L.sparse, VK_FORMAT_R32_SFLOAT, work_usage);
        L.d1 = DisImage(device, L.extent, VK_FORMAT_R32G32B32A32_SFLOAT, work_usage);
        L.A = DisImage(device, L.extent, VK_FORMAT_R32G32B32A32_SFLOAT, work_usage);
        L.B = DisImage(device, L.extent, VK_FORMAT_R32G32_SFLOAT, work_usage);
        L.wt = DisImage(device, L.extent, VK_FORMAT_R32_SFLOAT, work_usage);
        L.dW0 = DisImage(device, L.extent, VK_FORMAT_R32G32_SFLOAT, clear_usage);
        L.dW1 = DisImage(device, L.extent, VK_FORMAT_R32G32_SFLOAT, clear_usage);

        if (!L.U.Valid() || !L.S0.Valid() || !L.S1.Valid() || !L.ST.Valid() || !L.ST2.Valid() ||
            !L.d1.Valid() || !L.A.Valid() || !L.B.Valid() || !L.wt.Valid() || !L.dW0.Valid() ||
            !L.dW1.Valid()) {
            return false;
        }
    }

    flow_full = DisImage(device, flow_extent, VK_FORMAT_R32G32_SFLOAT, clear_usage);
    if (!flow_full.Valid()) return false;

    // Descriptor sets. gray/interp are written at frame time; everything else is static.
    for (uint32_t slot = 0; slot < 2; slot++) {
        gray_sets[slot] = AllocateDescriptorSet(device, descriptor_pool, gray_pass.SetLayout());
        for (uint32_t lvl = 0; lvl < num_levels; lvl++) {
            if (lvl >= 1) {
                down_sets[slot][lvl] =
                    AllocateDescriptorSet(device, descriptor_pool, down_pass.SetLayout());
                WriteDescriptorSet(device, down_sets[slot][lvl],
                                   {{VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                     pyr_img[slot][lvl - 1].View(), VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                     pyr_img[slot][lvl].View(), VK_NULL_HANDLE}});
            }
            if (lvl >= finest_level) {
                sobel_sets[slot][lvl] =
                    AllocateDescriptorSet(device, descriptor_pool, sobel_pass.SetLayout());
                WriteDescriptorSet(device, sobel_sets[slot][lvl],
                                   {{VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                     pyr_img[slot][lvl].View(), VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                     pyr_grad[slot][lvl].View(), VK_NULL_HANDLE}});
            }
        }
    }

    for (uint32_t slot = 0; slot < 2; slot++) {
        const uint32_t other = 1 - slot;
        for (uint32_t lvl = finest_level; lvl < num_levels; lvl++) {
            Level& L = levels[lvl];
            const VkImageView img_prev = pyr_img[other][lvl].View();
            const VkImageView img_cur = pyr_img[slot][lvl].View();
            const VkImageView grad_prev = pyr_grad[other][lvl].View();

            structure_sets[slot][lvl] =
                AllocateDescriptorSet(device, descriptor_pool, structure_pass.SetLayout());
            WriteDescriptorSet(device, structure_sets[slot][lvl],
                               {{VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, grad_prev, VK_NULL_HANDLE},
                                {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, L.U.View(), VK_NULL_HANDLE},
                                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, L.ST.View(), VK_NULL_HANDLE},
                                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, L.ST2.View(), VK_NULL_HANDLE},
                                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, L.S0.View(), VK_NULL_HANDLE}});

            vr_prepare_sets[slot][lvl] =
                AllocateDescriptorSet(device, descriptor_pool, vr_prepare_pass.SetLayout());
            WriteDescriptorSet(device, vr_prepare_sets[slot][lvl],
                               {{VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, img_prev, VK_NULL_HANDLE},
                                {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, img_cur, VK_NULL_HANDLE},
                                {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, L.U.View(), VK_NULL_HANDLE},
                                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, L.d1.View(), VK_NULL_HANDLE}});

            for (uint32_t src = 0; src < 2; src++) {
                propagate_sets[slot][lvl][src] =
                    AllocateDescriptorSet(device, descriptor_pool, propagate_pass.SetLayout());
                WriteDescriptorSet(device, propagate_sets[slot][lvl][src],
                                   {{VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, img_prev, VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, img_cur, VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, grad_prev, VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                     LevelS(L, src).View(), VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                     LevelS(L, 1 - src).View(), VK_NULL_HANDLE}});

                descent_sets[slot][lvl][src] =
                    AllocateDescriptorSet(device, descriptor_pool, descent_pass.SetLayout());
                WriteDescriptorSet(device, descent_sets[slot][lvl][src],
                                   {{VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, img_prev, VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, img_cur, VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, grad_prev, VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                     LevelS(L, src).View(), VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, L.ST.View(), VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, L.ST2.View(), VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                     LevelS(L, 1 - src).View(), VK_NULL_HANDLE}});

                densify_sets[slot][lvl][src] =
                    AllocateDescriptorSet(device, descriptor_pool, densify_pass.SetLayout());
                WriteDescriptorSet(device, densify_sets[slot][lvl][src],
                                   {{VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                     LevelS(L, src).View(), VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, img_prev, VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, img_cur, VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, L.U.View(), VK_NULL_HANDLE}});
            }

            for (uint32_t dw = 0; dw < 2; dw++) {
                vr_coeff_sets[slot][lvl][dw] =
                    AllocateDescriptorSet(device, descriptor_pool, vr_coeffs_pass.SetLayout());
                WriteDescriptorSet(device, vr_coeff_sets[slot][lvl][dw],
                                   {{VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, L.d1.View(), VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                     LevelDW(L, dw).View(), VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, L.U.View(), VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, L.wt.View(), VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, img_prev, VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, img_cur, VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, L.A.View(), VK_NULL_HANDLE},
                                    {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, L.B.View(), VK_NULL_HANDLE}});
            }
        }
    }

    for (uint32_t lvl = finest_level; lvl < num_levels; lvl++) {
        Level& L = levels[lvl];
        for (uint32_t dw = 0; dw < 2; dw++) {
            vr_weight_sets[lvl][dw] =
                AllocateDescriptorSet(device, descriptor_pool, vr_weights_pass.SetLayout());
            WriteDescriptorSet(device, vr_weight_sets[lvl][dw],
                               {{VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, L.U.View(), VK_NULL_HANDLE},
                                {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                 LevelDW(L, dw).View(), VK_NULL_HANDLE},
                                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, L.wt.View(), VK_NULL_HANDLE}});

            vr_sor_sets[lvl][dw] =
                AllocateDescriptorSet(device, descriptor_pool, vr_sor_pass.SetLayout());
            WriteDescriptorSet(device, vr_sor_sets[lvl][dw],
                               {{VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, L.A.View(), VK_NULL_HANDLE},
                                {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, L.B.View(), VK_NULL_HANDLE},
                                {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, L.wt.View(), VK_NULL_HANDLE},
                                {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                 LevelDW(L, dw).View(), VK_NULL_HANDLE},
                                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                 LevelDW(L, 1 - dw).View(), VK_NULL_HANDLE}});

            const VkImageView dst = (lvl > finest_level)
                ? levels[lvl - 1].U.View()
                : flow_full.View();
            vr_add_sets[lvl][dw] =
                AllocateDescriptorSet(device, descriptor_pool, vr_add_resize_pass.SetLayout());
            WriteDescriptorSet(device, vr_add_sets[lvl][dw],
                               {{VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, L.U.View(), VK_NULL_HANDLE},
                                {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                 LevelDW(L, dw).View(), VK_NULL_HANDLE},
                                {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, dst, VK_NULL_HANDLE}});
        }
    }

    for (uint32_t slot = 0; slot < 2; slot++) {
        for (uint32_t target = 0; target < DIS_MAX_TARGETS; target++) {
            interp_sets[slot][target] =
                AllocateDescriptorSet(device, descriptor_pool, interp_pass.SetLayout());
        }
    }

    frame_count = 0;
    last_count = 0;
    valid = true;
    return true;
}

void DisChain::Process(VkCommandBuffer cmd, VkImage source, VkImageView fullres_view_cur,
                       VkImageView fullres_view_prev, VkRect2D content_rect_in,
                       VkExtent2D target_extent) {
    if (!valid) return;

    const uint32_t cur = static_cast<uint32_t>(frame_count % 2);
    const uint32_t prev = 1 - cur;
    last_count = frame_count;

    fullres_views[cur] = fullres_view_cur;
    fullres_views[prev] = fullres_view_prev;

    // Sanitize the content rectangle: empty means full frame; clamp to the target.
    VkRect2D rect = content_rect_in;
    if (rect.extent.width == 0 || rect.extent.height == 0) {
        rect = VkRect2D{{0, 0}, target_extent};
    } else {
        int32_t x0 = std::max(0, rect.offset.x);
        int32_t y0 = std::max(0, rect.offset.y);
        int32_t x1 = std::min(static_cast<int32_t>(target_extent.width),
                              rect.offset.x + static_cast<int32_t>(rect.extent.width));
        int32_t y1 = std::min(static_cast<int32_t>(target_extent.height),
                              rect.offset.y + static_cast<int32_t>(rect.extent.height));
        if (x1 <= x0 || y1 <= y0) {
            rect = VkRect2D{{0, 0}, target_extent};
        } else {
            rect.offset = {x0, y0};
            rect.extent = {static_cast<uint32_t>(x1 - x0), static_cast<uint32_t>(y1 - y0)};
        }
    }
    content_rect = rect;

    // The composite was just rendered as a color attachment; make it visible to compute sampling.
    ImageBarrier(cmd, source, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                 VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    // 1. Luminance pyramid (content rectangle only).
    WriteDescriptorSet(device, gray_sets[cur],
                       {{VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, fullres_view_cur, VK_NULL_HANDLE},
                        {VK_DESCRIPTOR_TYPE_SAMPLER, VK_NULL_HANDLE, sampler},
                        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, pyr_img[cur][0].View(), VK_NULL_HANDLE}});
    {
        GrayPC pc{};
        pc.dstSize[0] = static_cast<float>(flow_extent.width);
        pc.dstSize[1] = static_cast<float>(flow_extent.height);
        pc.rectOffset[0] = static_cast<float>(rect.offset.x) / static_cast<float>(target_extent.width);
        pc.rectOffset[1] = static_cast<float>(rect.offset.y) / static_cast<float>(target_extent.height);
        pc.rectScale[0] = static_cast<float>(rect.extent.width) / static_cast<float>(target_extent.width);
        pc.rectScale[1] = static_cast<float>(rect.extent.height) / static_cast<float>(target_extent.height);
        gray_pass.BindCompute(cmd, gray_sets[cur]);
        gray_pass.Push(cmd, VK_SHADER_STAGE_COMPUTE_BIT, sizeof(pc), &pc);
        vkd.CmdDispatch(cmd, GroupCount(flow_extent.width, TILE_SHIFT),
                        GroupCount(flow_extent.height, TILE_SHIFT), 1);
        ComputeToComputeBarrier(cmd, pyr_img[cur][0].Handle(), VK_ACCESS_SHADER_WRITE_BIT,
                                VK_ACCESS_SHADER_READ_BIT);
    }

    for (uint32_t lvl = 1; lvl < num_levels; lvl++) {
        down_pass.BindCompute(cmd, down_sets[cur][lvl]);
        const VkExtent2D e = pyr_img[cur][lvl].Extent();
        vkd.CmdDispatch(cmd, GroupCount(e.width, TILE_SHIFT), GroupCount(e.height, TILE_SHIFT), 1);
        ComputeToComputeBarrier(cmd, pyr_img[cur][lvl].Handle(), VK_ACCESS_SHADER_WRITE_BIT,
                                VK_ACCESS_SHADER_READ_BIT);
    }

    for (uint32_t lvl = finest_level; lvl < num_levels; lvl++) {
        sobel_pass.BindCompute(cmd, sobel_sets[cur][lvl]);
        const VkExtent2D e = pyr_grad[cur][lvl].Extent();
        vkd.CmdDispatch(cmd, GroupCount(e.width, TILE_SHIFT), GroupCount(e.height, TILE_SHIFT), 1);
        ComputeToComputeBarrier(cmd, pyr_grad[cur][lvl].Handle(), VK_ACCESS_SHADER_WRITE_BIT,
                                VK_ACCESS_SHADER_READ_BIT);
    }

    if (frame_count >= 1) {
        for (int32_t li = static_cast<int32_t>(coarsest_level);
             li >= static_cast<int32_t>(finest_level); --li) {
            const uint32_t i = static_cast<uint32_t>(li);
            Level& L = levels[i];

            if (i == coarsest_level) {
                ClearImage(cmd, L.U.Handle());
            }

            // Structure tensor + initial sparse flow. The set keyed by `cur` binds the
            // previous frame's image and gradient (img/grad at slot prev), matching the
            // reference's G0 = pyr0.grad.
            structure_pass.BindCompute(cmd, structure_sets[cur][i]);
            {
                StructurePC pc{};
                pc.sparse[0] = static_cast<int32_t>(L.sparse.width);
                pc.sparse[1] = static_cast<int32_t>(L.sparse.height);
                pc.stride = static_cast<int32_t>(PSTR);
                pc.psz2 = static_cast<int32_t>(PSZ / 2);
                structure_pass.Push(cmd, VK_SHADER_STAGE_COMPUTE_BIT, sizeof(pc), &pc);
                vkd.CmdDispatch(cmd, GroupCount(L.sparse.width, TILE_SHIFT),
                                GroupCount(L.sparse.height, TILE_SHIFT), 1);
            }
            ComputeToComputeBarrier(cmd, L.ST.Handle(), VK_ACCESS_SHADER_WRITE_BIT,
                                    VK_ACCESS_SHADER_READ_BIT);
            ComputeToComputeBarrier(cmd, L.ST2.Handle(), VK_ACCESS_SHADER_WRITE_BIT,
                                    VK_ACCESS_SHADER_READ_BIT);
            ComputeToComputeBarrier(cmd, L.S0.Handle(), VK_ACCESS_SHADER_WRITE_BIT,
                                    VK_ACCESS_SHADER_READ_BIT);

            // Two outer scans (forward/backward) of propagation + Gauss-Newton descent.
            uint32_t sIdx = 0;
            for (uint32_t outer = 0; outer < 2; outer++) {
                const int32_t sgn = outer == 0 ? -1 : 1;
                int32_t d = 1;
                for (uint32_t k = 0; k < PROP_STEPS; k++, d *= 2) {
                    propagate_pass.BindCompute(cmd, propagate_sets[cur][i][sIdx]);
                    PropPC pc{};
                    pc.size[0] = static_cast<float>(L.extent.width);
                    pc.size[1] = static_cast<float>(L.extent.height);
                    pc.off[0] = sgn * d;
                    pc.off[1] = 0;
                    pc.stride = static_cast<int32_t>(PSTR);
                    propagate_pass.Push(cmd, VK_SHADER_STAGE_COMPUTE_BIT, sizeof(pc), &pc);
                    vkd.CmdDispatch(cmd, GroupCount(L.sparse.width, TILE_SHIFT),
                                    GroupCount(L.sparse.height, TILE_SHIFT), 1);
                    sIdx ^= 1;
                    ComputeToComputeBarrier(cmd, LevelS(L, sIdx).Handle(),
                                            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
                }

                descent_pass.BindCompute(cmd, descent_sets[cur][i][sIdx]);
                DescentPC pc{};
                pc.size[0] = static_cast<float>(L.extent.width);
                pc.size[1] = static_cast<float>(L.extent.height);
                pc.stride = static_cast<int32_t>(PSTR);
                pc.iters = static_cast<int32_t>(INNER_ITERS);
                descent_pass.Push(cmd, VK_SHADER_STAGE_COMPUTE_BIT, sizeof(pc), &pc);
                vkd.CmdDispatch(cmd, GroupCount(L.sparse.width, TILE_SHIFT),
                                GroupCount(L.sparse.height, TILE_SHIFT), 1);
                sIdx ^= 1;
                ComputeToComputeBarrier(cmd, LevelS(L, sIdx).Handle(),
                                        VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
            }

            // Densify into the level flow.
            densify_pass.BindCompute(cmd, densify_sets[cur][i][sIdx]);
            {
                DensifyPC pc{};
                pc.size[0] = static_cast<float>(L.extent.width);
                pc.size[1] = static_cast<float>(L.extent.height);
                pc.sparse[0] = static_cast<int32_t>(L.sparse.width);
                pc.sparse[1] = static_cast<int32_t>(L.sparse.height);
                pc.stride = static_cast<int32_t>(PSTR);
                densify_pass.Push(cmd, VK_SHADER_STAGE_COMPUTE_BIT, sizeof(pc), &pc);
                vkd.CmdDispatch(cmd, GroupCount(L.extent.width, TILE_SHIFT),
                                GroupCount(L.extent.height, TILE_SHIFT), 1);
            }
            ComputeToComputeBarrier(cmd, L.U.Handle(), VK_ACCESS_SHADER_WRITE_BIT,
                                    VK_ACCESS_SHADER_READ_BIT);

            // Variational refinement.
            ClearImage(cmd, L.dW0.Handle());
            uint32_t dw = 0;
            vr_prepare_pass.BindCompute(cmd, vr_prepare_sets[cur][i]);
            {
                VrPreparePC pc{};
                pc.size[0] = static_cast<float>(L.extent.width);
                pc.size[1] = static_cast<float>(L.extent.height);
                vr_prepare_pass.Push(cmd, VK_SHADER_STAGE_COMPUTE_BIT, sizeof(pc), &pc);
                vkd.CmdDispatch(cmd, GroupCount(L.extent.width, TILE_SHIFT),
                                GroupCount(L.extent.height, TILE_SHIFT), 1);
            }
            ComputeToComputeBarrier(cmd, L.d1.Handle(), VK_ACCESS_SHADER_WRITE_BIT,
                                    VK_ACCESS_SHADER_READ_BIT);

            for (uint32_t f = 0; f < FP_ITERS; f++) {
                vr_weights_pass.BindCompute(cmd, vr_weight_sets[i][dw]);
                {
                    VrWeightsPC pc{};
                    pc.alpha2 = ALPHA2;
                    pc.eps2 = EPS2;
                    vr_weights_pass.Push(cmd, VK_SHADER_STAGE_COMPUTE_BIT, sizeof(pc), &pc);
                    vkd.CmdDispatch(cmd, GroupCount(L.extent.width, TILE_SHIFT),
                                    GroupCount(L.extent.height, TILE_SHIFT), 1);
                }
                ComputeToComputeBarrier(cmd, L.wt.Handle(), VK_ACCESS_SHADER_WRITE_BIT,
                                        VK_ACCESS_SHADER_READ_BIT);

                vr_coeffs_pass.BindCompute(cmd, vr_coeff_sets[cur][i][dw]);
                {
                    VrCoeffsPC pc{};
                    pc.size[0] = static_cast<float>(L.extent.width);
                    pc.size[1] = static_cast<float>(L.extent.height);
                    pc.delta2 = DELTA2;
                    pc.gamma2 = GAMMA2;
                    pc.zeta2 = ZETA2;
                    pc.eps2 = EPS2;
                    vr_coeffs_pass.Push(cmd, VK_SHADER_STAGE_COMPUTE_BIT, sizeof(pc), &pc);
                    vkd.CmdDispatch(cmd, GroupCount(L.extent.width, TILE_SHIFT),
                                    GroupCount(L.extent.height, TILE_SHIFT), 1);
                }
                ComputeToComputeBarrier(cmd, L.A.Handle(), VK_ACCESS_SHADER_WRITE_BIT,
                                        VK_ACCESS_SHADER_READ_BIT);
                ComputeToComputeBarrier(cmd, L.B.Handle(), VK_ACCESS_SHADER_WRITE_BIT,
                                        VK_ACCESS_SHADER_READ_BIT);

                for (uint32_t s = 0; s < SOR_ITERS; s++) {
                    for (int32_t parity = 0; parity < 2; parity++) {
                        vr_sor_pass.BindCompute(cmd, vr_sor_sets[i][dw]);
                        VrSorPC pc{};
                        pc.omega = OMEGA;
                        pc.parity = parity;
                        vr_sor_pass.Push(cmd, VK_SHADER_STAGE_COMPUTE_BIT, sizeof(pc), &pc);
                        vkd.CmdDispatch(cmd, GroupCount(L.extent.width, TILE_SHIFT),
                                        GroupCount(L.extent.height, TILE_SHIFT), 1);
                        dw ^= 1;
                        ComputeToComputeBarrier(cmd, LevelDW(L, dw).Handle(),
                                                VK_ACCESS_SHADER_WRITE_BIT,
                                                VK_ACCESS_SHADER_READ_BIT);
                    }
                }
            }

            // Refined flow -> next finer level's U (scale 2) or flow_full (scale 2^finest).
            const bool to_flow_full = (i == finest_level);
            const VkExtent2D dst_extent = to_flow_full ? flow_extent : levels[i - 1].extent;
            const VkImage dst_image = to_flow_full ? flow_full.Handle() : levels[i - 1].U.Handle();

            vr_add_resize_pass.BindCompute(cmd, vr_add_sets[i][dw]);
            VrAddPC addPc{};
            addPc.scale = to_flow_full
                ? std::pow(2.0f, static_cast<float>(finest_level))
                : 2.0f;
            vr_add_resize_pass.Push(cmd, VK_SHADER_STAGE_COMPUTE_BIT, sizeof(addPc), &addPc);
            vkd.CmdDispatch(cmd, GroupCount(dst_extent.width, TILE_SHIFT),
                            GroupCount(dst_extent.height, TILE_SHIFT), 1);
            ComputeToComputeBarrier(cmd, dst_image, VK_ACCESS_SHADER_WRITE_BIT,
                                    VK_ACCESS_SHADER_READ_BIT);
        }
    } else {
        ClearImage(cmd, flow_full.Handle());
    }

    frame_count++;
}

void DisChain::GenerateInto(VkCommandBuffer cmd, uint32_t generation, uint32_t generation_count,
                            VkImage target_image, VkImageView target_view,
                            VkExtent2D target_extent) {
    if (!valid) return;

    const uint32_t cur = static_cast<uint32_t>(last_count % 2);
    const uint32_t prev = 1 - cur;

    VkDescriptorSet set = interp_sets[cur][generation];
    WriteDescriptorSet(device, set,
                       {{VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, fullres_views[prev], VK_NULL_HANDLE},
                        {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, fullres_views[cur], VK_NULL_HANDLE},
                        {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, flow_full.View(), VK_NULL_HANDLE},
                        {VK_DESCRIPTOR_TYPE_SAMPLER, VK_NULL_HANDLE, sampler},
                        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, target_view, VK_NULL_HANDLE}});

    InterpPC ipc{};
    ipc.rectOffset[0] = static_cast<float>(content_rect.offset.x) / static_cast<float>(target_extent.width);
    ipc.rectOffset[1] = static_cast<float>(content_rect.offset.y) / static_cast<float>(target_extent.height);
    ipc.rectScale[0] = static_cast<float>(content_rect.extent.width) / static_cast<float>(target_extent.width);
    ipc.rectScale[1] = static_cast<float>(content_rect.extent.height) / static_cast<float>(target_extent.height);
    ipc.invFlowSize[0] = 1.0f / static_cast<float>(flow_extent.width);
    ipc.invFlowSize[1] = 1.0f / static_cast<float>(flow_extent.height);
    ipc.t = static_cast<float>(generation + 1) / static_cast<float>(generation_count + 1);
    ipc.mag = DEBUG_MAG;
    ipc.debugMode = debug_mode;

    DiscardImage(cmd, target_image);

    interp_pass.BindCompute(cmd, set);
    interp_pass.Push(cmd, VK_SHADER_STAGE_COMPUTE_BIT, sizeof(ipc), &ipc);
    vkd.CmdDispatch(cmd, GroupCount(target_extent.width, TILE_SHIFT),
                    GroupCount(target_extent.height, TILE_SHIFT), 1);

    ImageBarrier(cmd, target_image, VK_ACCESS_SHADER_WRITE_BIT,
                 VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT,
                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                 VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT);
}

void DisChain::ForgetTargets() {
    // The interp descriptor sets are rewritten for every generation; nothing to invalidate.
}

} // namespace dis
