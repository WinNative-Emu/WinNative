// SPDX-License-Identifier: GPL-3.0-or-later
//
// DIS (Dense Inverse Search) optical-flow frame interpolation, a Vulkan compute port of the
// WebGL2 implementation in dis_flow_webgl2_2.html (OpenCV dis_flow.cpp + variational
// refinement). Self-contained: shaders are authored in GLSL and compiled at build time, so no
// external shader payload (e.g., Lossless.dll) is required.

#pragma once

#include <array>
#include <cstdint>
#include <vector>

#include "../vk_dispatch.h"

#define DIS_MAX_LEVELS 8u
#define DIS_MAX_TARGETS 7u

namespace dis {

class DisImage {
public:
    DisImage() = default;
    DisImage(VkDevice device, VkExtent2D extent, VkFormat format, VkImageUsageFlags usage,
             VkImageLayout initial_layout = VK_IMAGE_LAYOUT_GENERAL);
    ~DisImage();

    DisImage(const DisImage&) = delete;
    DisImage& operator=(const DisImage&) = delete;
    DisImage(DisImage&& other) noexcept;
    DisImage& operator=(DisImage&& other) noexcept;

    [[nodiscard]] VkImage Handle() const { return image; }
    [[nodiscard]] VkImageView View() const { return view; }
    [[nodiscard]] VkExtent2D Extent() const { return extent; }
    [[nodiscard]] VkFormat Format() const { return format; }
    [[nodiscard]] bool Valid() const { return image != VK_NULL_HANDLE; }

private:
    void Release();

    VkDevice device{VK_NULL_HANDLE};
    VkImage image{VK_NULL_HANDLE};
    VkImageView view{VK_NULL_HANDLE};
    VkDeviceMemory memory{VK_NULL_HANDLE};
    VkExtent2D extent{};
    VkFormat format{VK_FORMAT_UNDEFINED};
};

class DisPass {
public:
    DisPass() = default;
    DisPass(VkDevice device, const uint32_t* spirv, size_t spirv_size,
            const std::vector<VkDescriptorSetLayoutBinding>& bindings,
            VkShaderStageFlagBits stage = VK_SHADER_STAGE_COMPUTE_BIT,
            VkPushConstantRange push = {});
    ~DisPass();

    DisPass(const DisPass&) = delete;
    DisPass& operator=(const DisPass&) = delete;
    DisPass(DisPass&& other) noexcept;
    DisPass& operator=(DisPass&& other) noexcept;

    [[nodiscard]] VkDescriptorSetLayout SetLayout() const { return descriptor_set_layout; }
    [[nodiscard]] VkPipeline Pipeline() const { return pipeline; }
    [[nodiscard]] VkPipelineLayout PipelineLayout() const { return pipeline_layout; }
    [[nodiscard]] bool Valid() const { return pipeline != VK_NULL_HANDLE; }

    void BindCompute(VkCommandBuffer cmd, VkDescriptorSet set) const;
    void Push(VkCommandBuffer cmd, VkShaderStageFlags stage, uint32_t size, const void* data) const;

private:
    void Release();

    VkDevice device{VK_NULL_HANDLE};
    VkDescriptorSetLayout descriptor_set_layout{VK_NULL_HANDLE};
    VkPipelineLayout pipeline_layout{VK_NULL_HANDLE};
    VkPipeline pipeline{VK_NULL_HANDLE};
};

struct DisSampledBinding {
    VkDescriptorType type;
    VkImageView view;
    VkSampler sampler;
};

void SetDeviceMemoryProperties(const VkPhysicalDeviceMemoryProperties& props);

VkDescriptorSetLayout CreateDescriptorSetLayout(VkDevice device,
                                                const std::vector<VkDescriptorSetLayoutBinding>&);
VkDescriptorPool CreateDescriptorPool(VkDevice device, uint32_t max_sets);
VkDescriptorSet AllocateDescriptorSet(VkDevice device, VkDescriptorPool pool,
                                      VkDescriptorSetLayout layout);
void WriteDescriptorSet(VkDevice device, VkDescriptorSet set,
                        const std::vector<DisSampledBinding>& bindings);
VkSampler CreateSampler(VkDevice device);

uint32_t GroupCount(uint32_t size, uint32_t tile_shift);

} // namespace dis
