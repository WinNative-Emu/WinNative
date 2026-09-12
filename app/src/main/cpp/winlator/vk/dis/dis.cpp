// SPDX-License-Identifier: GPL-3.0-or-later

#include "dis.hpp"

#include <cstring>
#include <utility>

namespace dis {

uint32_t GroupCount(uint32_t size, uint32_t tile_shift) {
    return (size + (1u << tile_shift) - 1u) >> tile_shift;
}

static VkPhysicalDeviceMemoryProperties g_memory_props{};

void SetDeviceMemoryProperties(const VkPhysicalDeviceMemoryProperties& props) {
    g_memory_props = props;
}

static uint32_t FindMemoryType(uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < g_memory_props.memoryTypeCount; i++) {
        if (!(bits & (1u << i))) continue;
        if ((g_memory_props.memoryTypes[i].propertyFlags & want) == want) return i;
    }
    return UINT32_MAX;
}

DisImage::DisImage(VkDevice device_, VkExtent2D extent_, VkFormat format_,
                   VkImageUsageFlags usage, VkImageLayout initial_layout)
    : device{device_},
      extent{std::max(1u, extent_.width), std::max(1u, extent_.height)},
      format{format_} {
    VkImageCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = format;
    ci.extent = {extent.width, extent.height, 1};
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = usage;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.initialLayout = initial_layout;
    if (vkd.CreateImage(device, &ci, nullptr, &image) != VK_SUCCESS) {
        image = VK_NULL_HANDLE;
        return;
    }

    VkMemoryRequirements req;
    vkd.GetImageMemoryRequirements(device, image, &req);

    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = FindMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX || vkd.AllocateMemory(device, &ai, nullptr, &memory) != VK_SUCCESS) {
        Release();
        return;
    }
    vkd.BindImageMemory(device, image, memory, 0);

    VkImageViewCreateInfo vi{};
    vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image = image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = format;
    vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vi.subresourceRange.levelCount = 1;
    vi.subresourceRange.layerCount = 1;
    if (vkd.CreateImageView(device, &vi, nullptr, &view) != VK_SUCCESS) {
        view = VK_NULL_HANDLE;
        Release();
    }
}

DisImage::~DisImage() {
    Release();
}

DisImage::DisImage(DisImage&& other) noexcept
    : device{other.device}, image{other.image}, view{other.view}, memory{other.memory},
      extent{other.extent}, format{other.format} {
    other.device = VK_NULL_HANDLE;
    other.image = VK_NULL_HANDLE;
    other.view = VK_NULL_HANDLE;
    other.memory = VK_NULL_HANDLE;
}

DisImage& DisImage::operator=(DisImage&& other) noexcept {
    if (this != &other) {
        Release();
        device = other.device;
        image = other.image;
        view = other.view;
        memory = other.memory;
        extent = other.extent;
        format = other.format;
        other.device = VK_NULL_HANDLE;
        other.image = VK_NULL_HANDLE;
        other.view = VK_NULL_HANDLE;
        other.memory = VK_NULL_HANDLE;
    }
    return *this;
}

void DisImage::Release() {
    if (device == VK_NULL_HANDLE) return;
    if (view) vkd.DestroyImageView(device, view, nullptr);
    if (image) vkd.DestroyImage(device, image, nullptr);
    if (memory) vkd.FreeMemory(device, memory, nullptr);
    view = VK_NULL_HANDLE;
    image = VK_NULL_HANDLE;
    memory = VK_NULL_HANDLE;
}

DisPass::DisPass(VkDevice device_, const uint32_t* spirv, size_t spirv_size,
                 const std::vector<VkDescriptorSetLayoutBinding>& bindings,
                 VkShaderStageFlagBits stage, VkPushConstantRange push)
    : device{device_} {
    descriptor_set_layout = CreateDescriptorSetLayout(device, bindings);
    if (descriptor_set_layout == VK_NULL_HANDLE) {
        Release();
        return;
    }

    VkPipelineLayoutCreateInfo pl{};
    pl.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pl.setLayoutCount = 1;
    pl.pSetLayouts = &descriptor_set_layout;
    pl.pushConstantRangeCount = push.size > 0 ? 1u : 0u;
    pl.pPushConstantRanges = push.size > 0 ? &push : nullptr;
    if (vkd.CreatePipelineLayout(device, &pl, nullptr, &pipeline_layout) != VK_SUCCESS) {
        Release();
        return;
    }

    VkShaderModuleCreateInfo sm{};
    sm.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    sm.codeSize = spirv_size;
    sm.pCode = spirv;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkd.CreateShaderModule(device, &sm, nullptr, &module) != VK_SUCCESS) {
        Release();
        return;
    }

    VkPipelineShaderStageCreateInfo ss{};
    ss.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    ss.stage = stage;
    ss.module = module;
    ss.pName = "main";

    VkComputePipelineCreateInfo cp{};
    cp.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    cp.stage = ss;
    cp.layout = pipeline_layout;
    if (vkd.CreateComputePipelines(device, VK_NULL_HANDLE, 1, &cp, nullptr, &pipeline) !=
        VK_SUCCESS) {
        pipeline = VK_NULL_HANDLE;
    }
    vkd.DestroyShaderModule(device, module, nullptr);
    if (pipeline == VK_NULL_HANDLE) Release();
}

DisPass::~DisPass() {
    Release();
}

DisPass::DisPass(DisPass&& other) noexcept
    : device{other.device}, descriptor_set_layout{other.descriptor_set_layout},
      pipeline_layout{other.pipeline_layout}, pipeline{other.pipeline} {
    other.device = VK_NULL_HANDLE;
    other.descriptor_set_layout = VK_NULL_HANDLE;
    other.pipeline_layout = VK_NULL_HANDLE;
    other.pipeline = VK_NULL_HANDLE;
}

DisPass& DisPass::operator=(DisPass&& other) noexcept {
    if (this != &other) {
        Release();
        device = other.device;
        descriptor_set_layout = other.descriptor_set_layout;
        pipeline_layout = other.pipeline_layout;
        pipeline = other.pipeline;
        other.device = VK_NULL_HANDLE;
        other.descriptor_set_layout = VK_NULL_HANDLE;
        other.pipeline_layout = VK_NULL_HANDLE;
        other.pipeline = VK_NULL_HANDLE;
    }
    return *this;
}

void DisPass::Release() {
    if (device == VK_NULL_HANDLE) return;
    if (pipeline) vkd.DestroyPipeline(device, pipeline, nullptr);
    if (pipeline_layout) vkd.DestroyPipelineLayout(device, pipeline_layout, nullptr);
    if (descriptor_set_layout) vkd.DestroyDescriptorSetLayout(device, descriptor_set_layout, nullptr);
    pipeline = VK_NULL_HANDLE;
    pipeline_layout = VK_NULL_HANDLE;
    descriptor_set_layout = VK_NULL_HANDLE;
}

void DisPass::BindCompute(VkCommandBuffer cmd, VkDescriptorSet set) const {
    vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    if (set != VK_NULL_HANDLE) {
        vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_layout, 0, 1, &set, 0,
                                  nullptr);
    }
}

void DisPass::Push(VkCommandBuffer cmd, VkShaderStageFlags stage, uint32_t size,
                   const void* data) const {
    vkd.CmdPushConstants(cmd, pipeline_layout, stage, 0, size, data);
}

VkDescriptorSetLayout CreateDescriptorSetLayout(VkDevice device,
                                                const std::vector<VkDescriptorSetLayoutBinding>& bindings) {
    VkDescriptorSetLayoutCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    ci.bindingCount = static_cast<uint32_t>(bindings.size());
    ci.pBindings = bindings.data();
    VkDescriptorSetLayout layout = VK_NULL_HANDLE;
    vkd.CreateDescriptorSetLayout(device, &ci, nullptr, &layout);
    return layout;
}

VkDescriptorPool CreateDescriptorPool(VkDevice device, uint32_t max_sets) {
    const VkDescriptorType types[] = {
        VK_DESCRIPTOR_TYPE_SAMPLER,
        VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
    };
    VkDescriptorPoolSize sizes[3];
    for (uint32_t i = 0; i < 3; i++) {
        sizes[i].type = types[i];
        sizes[i].descriptorCount = 4096;
    }

    VkDescriptorPoolCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    ci.maxSets = max_sets;
    ci.poolSizeCount = 3;
    ci.pPoolSizes = sizes;

    VkDescriptorPool pool = VK_NULL_HANDLE;
    vkd.CreateDescriptorPool(device, &ci, nullptr, &pool);
    return pool;
}

VkDescriptorSet AllocateDescriptorSet(VkDevice device, VkDescriptorPool pool,
                                      VkDescriptorSetLayout layout) {
    if (pool == VK_NULL_HANDLE || layout == VK_NULL_HANDLE) return VK_NULL_HANDLE;
    VkDescriptorSetAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool = pool;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &layout;
    VkDescriptorSet set = VK_NULL_HANDLE;
    vkd.AllocateDescriptorSets(device, &ai, &set);
    return set;
}

void WriteDescriptorSet(VkDevice device, VkDescriptorSet set,
                        const std::vector<DisSampledBinding>& bindings) {
    if (set == VK_NULL_HANDLE) return;

    std::vector<VkDescriptorImageInfo> infos(bindings.size());
    std::vector<VkWriteDescriptorSet> writes(bindings.size());

    for (size_t i = 0; i < bindings.size(); i++) {
        infos[i] = VkDescriptorImageInfo{};
        infos[i].sampler = bindings[i].sampler;
        infos[i].imageView = bindings[i].view;
        infos[i].imageLayout = bindings[i].view != VK_NULL_HANDLE ? VK_IMAGE_LAYOUT_GENERAL
                                                                  : VK_IMAGE_LAYOUT_UNDEFINED;

        writes[i] = VkWriteDescriptorSet{};
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = set;
        writes[i].dstBinding = static_cast<uint32_t>(i);
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = bindings[i].type;
        writes[i].pImageInfo = &infos[i];
    }

    vkd.UpdateDescriptorSets(device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
}

VkSampler CreateSampler(VkDevice device) {
    VkSamplerCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    ci.magFilter = VK_FILTER_LINEAR;
    ci.minFilter = VK_FILTER_LINEAR;
    ci.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    ci.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    ci.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    ci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    ci.minLod = 0.0f;
    ci.maxLod = 0.0f;
    VkSampler sampler = VK_NULL_HANDLE;
    vkd.CreateSampler(device, &ci, nullptr, &sampler);
    return sampler;
}

} // namespace dis
