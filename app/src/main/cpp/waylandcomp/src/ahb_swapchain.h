#ifndef AHB_SWAPCHAIN_H
#define AHB_SWAPCHAIN_H
/*
 * Zero-copy game frames (layer mode, BANNER_WAYLAND_ZERO_COPY=1 at launch, the drawer's "Zero-copy
 * presentation" switch live): the guest half of the design in ZERO_COPY_SPIKE.md. Our Wayland Turnip
 * (banners-turnip-wayland, patches/wayland/wnc_ahb_wsi.py) binds the private global banner_ahb_v1
 * (version 2), which this compositor advertises on every session where a display layer is possible,
 * and follows its `mode` event: while the mode is on, each swapchain the game creates has its images
 * allocated as gralloc AHardwareBuffers, shared as dma-bufs through zwp_linux_dmabuf_v1 as before (so
 * the blit path still works) and handed to us once per wl_buffer over a socketpair
 * (banner_ahb_v1.attach). When such a buffer is the one fullscreen frame, it goes straight onto the
 * sc_layer SurfaceControl: SurfaceFlinger / the display scan out the game's own buffer, no copy
 * anywhere. A mode flip (ahb_swapchain_set_mode, from the drawer through the host queue) is
 * broadcast to every bound client; a client whose live swapchain was built for the other mode
 * retires it (VK_ERROR_OUT_OF_DATE_KHR) so the program rebuilds it, and until then its buffers keep
 * presenting as before: an attached AHardwareBuffer stays with its wl_buffer whatever the mode is.
 *
 * Fences stay implicit, in the dma-buf itself: Mesa imports the render fence into the dma-buf
 * before it commits, we export it as the layer's acquire fence (DMA_BUF_IOCTL_EXPORT_SYNC_FILE),
 * and import SurfaceFlinger's release fence back (DMA_BUF_IOCTL_IMPORT_SYNC_FILE) before sending
 * wl_buffer.release, so Mesa's acquire (wsi_create_sync_for_dma_buf_wait) waits for the display.
 *
 * Compositor thread unless noted. Without a usable display layer (sc_layer_available() == 0) the
 * global is not created and nothing here runs.
 */
#include <stddef.h>
#include <stdint.h>
#include <wayland-server.h>

struct dmabuf_buffer;
struct surface;

/* ---- compositor.c -> ahb_swapchain.c */
/* Create the banner_ahb_v1 global (when a display layer is possible at all) and the release queue. */
void ahb_swapchain_init(struct wl_display *display);
/* Zero-copy on/off, live: sets g_zero_copy, tells every bound client (banner_ahb_v1.mode) and logs
 * it (live = 1: a drawer switch mid-session; 0: the launch default). Compositor thread. */
void ahb_swapchain_set_mode(int on, int live);
/* Milliseconds since a game frame was last put on the layer without a copy (-1 = never). Any thread:
 * the drawer's status line. */
int ahb_swapchain_last_frame_age_ms(void);
/* 1 if the buffer carries an AHardwareBuffer from the game. */
int ahb_swapchain_has_ahb(const struct dmabuf_buffer *b);
/* Show b (the topmost fullscreen frame, shown by surface s) on the layer without a copy.
 * 0 = done (or the same frame is already up), -1 = unavailable (draw the old way). */
int ahb_swapchain_present(struct dmabuf_buffer *b, struct surface *s, int scene_w, int scene_h);
/* Surface s lets go of buffer b (its wl_buffer `buffer`, NULL if the client destroyed it); paced =
 * the limiter's cadence applies. Returns 1 when the buffer is on the layer: the release is sent
 * from here once SurfaceFlinger's release fence is known. 0 = the caller releases as usual. */
int ahb_swapchain_defer_release(struct dmabuf_buffer *b, struct wl_resource *buffer, struct surface *s, int paced);
/* s is being destroyed: forget it (its deferred releases still go out, unpaced). */
void ahb_swapchain_surface_gone(struct surface *s);
/* Zero-copy frames since the last call (the 10 s summary). */
unsigned ahb_swapchain_stats_take(void);
/* 1 when the banner_ahb_v1 global exists (a display layer is possible): part of the HDR gate. */
int ahb_swapchain_advertised(void);
/* The AHARDWAREBUFFER_FORMAT_* of the game's buffer behind b, 0 when it has none. */
uint32_t ahb_swapchain_ahb_format(const struct dmabuf_buffer *b);

/* ---- sc_layer.c -> ahb_swapchain.c (SurfaceFlinger's callback thread): the layer let go of the
 * buffer behind `token`; release_fd (owned by the callee, -1 = none) signals when the display is
 * done reading it. */
void ahb_swapchain_layer_released(void *token, int release_fd);

/* ---- ahb_swapchain.c -> compositor.c (hooks) */
struct dmabuf_buffer *wnc_dmabuf_from_resource(struct wl_resource *buffer);
int wnc_dmabuf_fd(const struct dmabuf_buffer *b);                     /* plane 0's dma-buf */
void wnc_dmabuf_size(const struct dmabuf_buffer *b, int *w, int *h);
void **wnc_dmabuf_ahb_slot(struct dmabuf_buffer *b);                  /* this module's per-buffer state */
void wnc_dmabuf_ref(struct dmabuf_buffer *b);
void wnc_dmabuf_unref(struct dmabuf_buffer *b);
/* Give a wl_buffer back to its client now (paced = 0) or on the FPS limiter's cadence (s != NULL).
 * since_ns = when the compositor let go of it (CLOCK_MONOTONIC; the perf line's release latency). */
void wnc_release_buffer(struct surface *s, struct wl_resource *buffer, int paced, int64_t since_ns);
/* Redraw the scene on the next tick (the present path may have changed). */
void wnc_request_redraw(void);
/* "<title>" (program) of the window a surface belongs to, for the log. */
void wnc_surface_describe(const struct surface *s, char *out, size_t size);
/* The surface's current image description (color_mgmt.h), NULL = none / HDR gate closed. */
struct wnc_color;
const struct wnc_color *wnc_surface_color(const struct surface *s);

#endif
