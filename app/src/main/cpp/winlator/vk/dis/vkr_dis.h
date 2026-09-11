#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "../vk_dispatch.h"

#ifdef __cplusplus
extern "C" {
#endif

#define VKR_DIS_MAX_GENERATIONS 3u

typedef struct VkrDis VkrDis;

typedef struct VkrDisContentRect {
    int32_t x;
    int32_t y;
    uint32_t width;
    uint32_t height;
} VkrDisContentRect;

VkrDis* vkr_dis_create(VkDevice device, VkPhysicalDevice physical_device);
void vkr_dis_destroy(VkrDis* dis);

void vkr_dis_configure(VkrDis* dis, uint32_t flow_min_side, uint32_t target_fps,
                       float refresh_rate);

void vkr_dis_set_debug_flow(VkrDis* dis, bool debug_flow);

bool vkr_dis_needs_rebuild(const VkrDis* dis, uint32_t width, uint32_t height,
                           VkFormat format, VkrDisContentRect content);

bool vkr_dis_prepare(VkrDis* dis, uint32_t width, uint32_t height, VkFormat format,
                     VkrDisContentRect content);

uint32_t vkr_dis_plan(VkrDis* dis, uint32_t capacity, uint64_t source_frames);

void vkr_dis_process(VkrDis* dis, VkCommandBuffer cmd, VkImage source,
                     uint32_t width, uint32_t height, uint32_t generations);

void vkr_dis_generate_into(VkrDis* dis, VkCommandBuffer cmd, uint32_t generation,
                           uint32_t target_index, VkImage target_image,
                           VkImageView target_view, uint32_t width, uint32_t height,
                           VkImage base_image);

void vkr_dis_debug_into(VkrDis* dis, VkCommandBuffer cmd, VkImage target_image,
                        uint32_t width, uint32_t height);

void vkr_dis_forget_targets(VkrDis* dis);

void vkr_dis_reset(VkrDis* dis);

#ifdef __cplusplus
}
#endif
