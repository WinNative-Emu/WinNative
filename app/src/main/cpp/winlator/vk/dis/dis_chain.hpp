// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>

#include "dis.hpp"

namespace dis {

// Vulkan compute port of the WebGL2 DIS implementation in dis_flow_webgl2_2.html
// (OpenCV dis_flow.cpp + variational_refinement.cpp).
class DisChain {
public:
    DisChain(VkDevice device, VkPhysicalDevice physical_device);
    ~DisChain();

    DisChain(const DisChain&) = delete;
    DisChain& operator=(const DisChain&) = delete;

    bool Build(VkExtent2D flow_extent, VkExtent2D target_extent);
    bool Valid() const { return valid; }

    // Builds the current frame's pyramid and, once a previous frame exists, computes the flow
    // between the previous and current composite. content_rect is the letterboxed game area in
    // swapchain pixels: only that region is read, has its flow computed, and is interpolated.
    void Process(VkCommandBuffer cmd, VkImage source, VkImageView fullres_view_cur,
                 VkImageView fullres_view_prev, VkRect2D content_rect, VkExtent2D target_extent);

    // Warps prev/cur along the flow field at timestamp (generation+1)/(generation_count+1) and
    // writes the result into target_image.
    void GenerateInto(VkCommandBuffer cmd, uint32_t generation, uint32_t generation_count,
                      VkImage target_image, VkImageView target_view, VkExtent2D target_extent);

    void ForgetTargets();

    void SetDebugMode(int32_t mode) { debug_mode = mode; }

    [[nodiscard]] uint64_t FrameCount() const { return frame_count; }

    struct Level {
        bool active{false};
        uint32_t index{0};
        VkExtent2D extent{};
        VkExtent2D sparse{};
        DisImage U;
        DisImage S0, S1;
        DisImage ST, ST2;
        DisImage d1;
        DisImage A, B;
        DisImage wt;
        DisImage dW0, dW1;
    };

private:
    void DestroyResources();

    VkDevice device{VK_NULL_HANDLE};
    VkPhysicalDevice physical_device{VK_NULL_HANDLE};

    VkDescriptorPool descriptor_pool{VK_NULL_HANDLE};
    VkSampler sampler{VK_NULL_HANDLE};

    DisPass gray_pass;
    DisPass down_pass;
    DisPass sobel_pass;
    DisPass structure_pass;
    DisPass propagate_pass;
    DisPass descent_pass;
    DisPass densify_pass;
    DisPass vr_prepare_pass;
    DisPass vr_weights_pass;
    DisPass vr_coeffs_pass;
    DisPass vr_sor_pass;
    DisPass vr_add_resize_pass;
    DisPass interp_pass;

    uint32_t num_levels{0};
    uint32_t coarsest_level{0};
    uint32_t finest_level{0};
    VkExtent2D flow_extent{};
    VkExtent2D target_extent{};
    VkRect2D content_rect{};

    // Double-buffered luminance/gradient pyramids (prev/cur).
    std::array<std::array<DisImage, DIS_MAX_LEVELS>, 2> pyr_img;
    std::array<std::array<DisImage, DIS_MAX_LEVELS>, 2> pyr_grad;

    std::array<Level, DIS_MAX_LEVELS> levels;
    DisImage flow_full;

    // Borrowed full-resolution frame views (the presenter's composite ring), sampled by the
    // interpolation pass so the displayed frame keeps native detail.
    std::array<VkImageView, 2> fullres_views{};

    using SetPair = std::array<VkDescriptorSet, 2>;

    std::array<VkDescriptorSet, 2> gray_sets{};
    std::array<std::array<VkDescriptorSet, DIS_MAX_LEVELS>, 2> down_sets{};
    std::array<std::array<VkDescriptorSet, DIS_MAX_LEVELS>, 2> sobel_sets{};
    std::array<std::array<VkDescriptorSet, DIS_MAX_LEVELS>, 2> structure_sets{};
    std::array<std::array<SetPair, DIS_MAX_LEVELS>, 2> propagate_sets{};
    std::array<std::array<SetPair, DIS_MAX_LEVELS>, 2> descent_sets{};
    std::array<std::array<SetPair, DIS_MAX_LEVELS>, 2> densify_sets{};
    std::array<std::array<VkDescriptorSet, DIS_MAX_LEVELS>, 2> vr_prepare_sets{};
    std::array<std::array<VkDescriptorSet, 2>, DIS_MAX_LEVELS> vr_weight_sets{};
    std::array<std::array<SetPair, DIS_MAX_LEVELS>, 2> vr_coeff_sets{};
    std::array<std::array<VkDescriptorSet, 2>, DIS_MAX_LEVELS> vr_sor_sets{};
    std::array<std::array<VkDescriptorSet, 2>, DIS_MAX_LEVELS> vr_add_sets{};
    std::array<std::array<VkDescriptorSet, DIS_MAX_TARGETS>, 2> interp_sets{};

    uint64_t frame_count{0};
    uint64_t last_count{0};
    int32_t debug_mode{0};
    bool valid{false};
};

} // namespace dis
