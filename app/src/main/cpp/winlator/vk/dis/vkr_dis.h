#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "../vk_dispatch.h"

#ifdef __cplusplus
extern "C" {
#endif

#define VKR_DIS_MAX_GENERATIONS 3u
#define VKR_DIS_MAX_TARGETS 7u

typedef struct VkrDis VkrDis;

VkrDis* vkr_dis_create(VkDevice device, VkPhysicalDevice physical_device);
void vkr_dis_destroy(VkrDis* dis);

void vkr_dis_configure(VkrDis* dis, uint32_t target_rate, float flow_scale,
                       float refresh_rate, float source_rate);

void vkr_dis_set_refresh_rate(VkrDis* dis, float refresh_rate);

// Explicit frame multiplier (2..4). 0 restores the target/source-rate based plan.
void vkr_dis_set_multiplier(VkrDis* dis, uint32_t multiplier);

void vkr_dis_set_guest_extent(VkrDis* dis, uint32_t width, uint32_t height);

bool vkr_dis_needs_rebuild(const VkrDis* dis, uint32_t width, uint32_t height, VkFormat format);

bool vkr_dis_prepare(VkrDis* dis, uint32_t width, uint32_t height, VkFormat format);

uint32_t vkr_dis_plan(VkrDis* dis, uint32_t capacity, uint64_t source_frames);

void vkr_dis_process(VkrDis* dis, VkCommandBuffer cmd, VkImage source,
                     VkImageView fullres_view_cur, VkImageView fullres_view_prev,
                     uint32_t width, uint32_t height, VkRect2D content_rect,
                     uint32_t generations);

void vkr_dis_generate_into(VkrDis* dis, VkCommandBuffer cmd, uint32_t generation,
                           uint32_t target_index, VkImage target_image, VkImageView target_view,
                           uint32_t width, uint32_t height);

void vkr_dis_forget_targets(VkrDis* dis);

void vkr_dis_set_debug_mode(VkrDis* dis, uint32_t mode);

// True once the chain has a valid flow field to visualize (needs a previous/current pair).
bool vkr_dis_debug_ready(const VkrDis* dis);

void vkr_dis_reset(VkrDis* dis);

#ifdef __cplusplus
}
#endif
