#ifndef HDR_COMPOSE_H
#define HDR_COMPOSE_H
/*
 * HDR composition (round 2 of HDR_RECON.md's Phase B): when an HDR game cannot be alone on its own
 * display layer - screen effects on, frame generation on, a window above it on a display that cannot
 * compose a second layer, a windowed game, a frame without a gralloc buffer - the whole scene is put
 * into ONE encoding instead of being blitted into an 8-bit sRGB target as it stands (which shows the
 * game's PQ frames washed out).
 *
 * vk_present.c blits every draw 1:1 into a 10-bit "mixed" image (each pixel still in its own
 * encoding) and hdrc_encode() runs hdr_encode.frag over it into the output image:
 *   HDRC_OUT_PQ  - PQ BT.2020 (SDR pixels placed at the SDR reference white): goes onto the game's
 *                  display layer tagged BT2020_PQ (sc_layer.c), or into an HDR10 swapchain;
 *   HDRC_OUT_SDR - sRGB BT.709 with the HDR pixels tone-mapped: for a present that cannot carry HDR.
 * Which encoding a pixel is in comes from the draws' rectangles (the topmost draw decides).
 *
 * Compositor thread. Objects are built lazily on the first use; if the driver refuses them, the
 * encode is unavailable for the session (logged once) and the caller falls back.
 */
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#define HDRC_MAX_RECTS 6

enum hdrc_mode { HDRC_OUT_PQ = 0, HDRC_OUT_SDR = 1 };

struct hdrc_params {
    enum hdrc_mode mode;
    float sdr_white_nits;       /* SDR reference white inside the HDR picture (203) */
    float peak_nits;            /* HDR content peak for the tone-map (max CLL / mastering max, else 1000) */
    int count;                  /* rects, top draw first */
    unsigned hdr_mask;          /* bit i: rects[i] is an HDR draw */
    float rects[HDRC_MAX_RECTS][4]; /* scene pixels x0, y0, x1, y1 */
};

/* Called from dev_init (device up). */
void hdrc_bind_device(VkDevice dev, const VkPhysicalDeviceMemoryProperties *memprops);
/* Device teardown. */
void hdrc_destroy(void);
/* vk_present is about to destroy `img` (a resize of the mixed / scene / picture image): drop the view,
 * descriptor and framebuffer cached for it, so a later image that happens to reuse the handle value is
 * never drawn through a view of the destroyed one. */
void hdrc_forget_image(VkImage img);

/* Record the encode: `mixed` (w x h, VK_FORMAT_A2B10G10R10_UNORM_PACK32, in TRANSFER_DST_OPTIMAL after
 * the composite blits) -> `out` (w x h, out_fmt, created with COLOR_ATTACHMENT + SAMPLED usage), which
 * is left in TRANSFER_DST_OPTIMAL - the layout vkp_effects_run() takes its scene in. 0 = recorded,
 * -1 = unavailable (nothing recorded; `mixed` untouched). */
int hdrc_encode(VkCommandBuffer cmd, VkImage mixed, VkImage out, VkFormat out_fmt, int w, int h,
                const struct hdrc_params *p);

#endif
