#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "../vk_dispatch.h"

#ifdef __cplusplus
extern "C" {
#endif

#define VKR_DIS_MAX_GENERATIONS 3u

typedef struct VkrDis VkrDis;

// The sub-rectangle of the composite that actually holds guest pixels, in
// composite coordinates. When the container's aspect does not match the panel's,
// the scene viewport is letterboxed inside the composite and everything outside
// it is clear colour. Those bars are perfectly static, so flow estimated across
// their boundary is not merely useless but harmful: the boundary is a strong
// contrast edge that never moves, and the patch search happily locks onto it and
// drags the flow of the moving content next to it towards zero.
typedef struct VkrDisContentRect {
    int32_t x;
    int32_t y;
    uint32_t width;
    uint32_t height;
} VkrDisContentRect;

VkrDis* vkr_dis_create(VkDevice device, VkPhysicalDevice physical_device);
void vkr_dis_destroy(VkrDis* dis);

// flow_min_side is the processing resolution of the optical flow, given as the
// length of the frame's SHORTER side; the longer side follows from the aspect
// ratio. Expressed this way the pyramid is a fixed pixel count whatever the
// container's resolution, so a preset behaves the same on 720p and on 1440p. As
// a percentage it did not: the same setting cost four times as much on the
// larger container while telling the search nothing extra about the motion.
void vkr_dis_configure(VkrDis* dis, uint32_t flow_min_side, uint32_t target_fps,
                       float refresh_rate);

void vkr_dis_set_debug_flow(VkrDis* dis, bool debug_flow);

// content matters here: toggling stretch-to-fill changes the live sub-rect
// without touching the composite extent, so a check that ignored it left DIS
// estimating flow inside the old letterboxed rectangle after the picture had
// already been stretched over the whole screen.
bool vkr_dis_needs_rebuild(const VkrDis* dis, uint32_t width, uint32_t height,
                           VkFormat format, VkrDisContentRect content);

// width/height are the full composite extent; content names the live sub-rect
// inside it. Everything DIS estimates and interpolates happens in content space.
bool vkr_dis_prepare(VkrDis* dis, uint32_t width, uint32_t height, VkFormat format,
                     VkrDisContentRect content);

uint32_t vkr_dis_plan(VkrDis* dis, uint32_t capacity, uint64_t source_frames);

void vkr_dis_process(VkrDis* dis, VkCommandBuffer cmd, VkImage source,
                     uint32_t width, uint32_t height, uint32_t generations);

// base_image is the full composite for this frame. It is copied to the target
// first so anything outside the content rect - the letterbox bars - comes from a
// real frame rather than being left undefined; the interpolated content is then
// written over the part that moves.
void vkr_dis_generate_into(VkrDis* dis, VkCommandBuffer cmd, uint32_t generation,
                           uint32_t target_index, VkImage target_image,
                           VkImageView target_view, uint32_t width, uint32_t height,
                           VkImage base_image);

// Paints the estimated flow field into a real, non-generated frame. The debug
// view replaced only the generated frames, so the panel alternated between the
// game and the visualisation at the generation ratio and read as a flicker.
void vkr_dis_debug_into(VkrDis* dis, VkCommandBuffer cmd, VkImage target_image,
                        uint32_t width, uint32_t height);

void vkr_dis_forget_targets(VkrDis* dis);

void vkr_dis_reset(VkrDis* dis);

#ifdef __cplusplus
}
#endif
