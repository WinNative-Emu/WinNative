#ifndef VK_IMPORT_H
#define VK_IMPORT_H
#include <stdint.h>
/*
 * Import a received dmabuf into the compositor's own Vulkan (Turnip) device as a
 * VkImage and bind its memory. This is the M4-import de-risk: if we can import +
 * bind, sampling/blitting it to screen is standard Vulkan. Non-fatal: returns 0
 * on success, negative on failure, and logs details. Does NOT consume `fd`
 * (dups internally).
 */
int vk_import_dmabuf(int fd, uint32_t drm_format, uint64_t modifier,
                     int w, int h, uint32_t stride, uint32_t offset);
#endif
