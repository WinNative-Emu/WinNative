#ifndef BLEND_PASS_H
#define BLEND_PASS_H
/*
 * Alpha composition. The scene is composed with blits, which replace what is under them; a surface
 * with translucent pixels (gamescope's overlay and notification planes over a game, a toolkit's
 * shadowed popup) is drawn through this pass instead, source-over with premultiplied alpha.
 *
 * Compositor thread. Objects are built on the first use; if the driver refuses them the pass is
 * unavailable for the session (logged once) and the caller keeps blitting.
 */
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

/* Called from dev_init (device up). */
void blendp_bind_device(VkDevice dev);
/* A source or target image is about to be destroyed: drop what is cached for it. Frames are fenced,
 * so nothing submitted still uses it. */
void blendp_forget_image(VkImage img);
/* A new command buffer is being recorded: what the previous one used may be recycled again. */
void blendp_begin_frame(void);

/* Record one surface over `dst`. `dst` is in TRANSFER_DST_OPTIMAL before and after. `src` was created
 * with SAMPLED usage and is in GENERAL (src_general) or TRANSFER_SRC_OPTIMAL, and is left there.
 * `blit` is the draw as the blit path maps it. 0 = recorded, -1 = unavailable (nothing recorded). */
int blendp_draw(VkCommandBuffer cmd, VkImage src, VkFormat src_fmt, int src_w, int src_h, int src_general,
                VkImage dst, VkFormat dst_fmt, int dst_w, int dst_h, const VkImageBlit *blit);

#endif
