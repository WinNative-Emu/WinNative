#ifndef VK_PRESENT_H
#define VK_PRESENT_H
#include <stdint.h>
#include <android/native_window.h>
/*
 * Android-surface render backend for the embedded Wayland compositor.
 * Owns a Turnip VkDevice + a swapchain on the SurfaceView's ANativeWindow. Each
 * client buffer becomes an image (a dmabuf from winewayland's Vulkan WSI is
 * imported zero-copy; a wl_shm buffer is copied into a host-visible image), and
 * every frame blits the whole scene — desktop, windows, subsurfaces — in order.
 */

// Set the Turnip driver to load (adrenotools). Call before the first frame.
// NULL args -> the backend falls back to the system libvulkan (dmabuf import will
// likely fail — Adreno lacks drm_format_modifier). driver_path ends with '/'.
void vk_present_set_driver(const char *driver_path, const char *library_name,
                           const char *native_lib_dir);

// Set/replace the output window (from Surface via ANativeWindow_fromSurface; the backend
// owns the reference from then on). NULL = the surface is gone. Callable from any thread
// and never blocks: the request is applied by the compositor thread (vkp_render /
// vkp_apply_window_request), which tears the old swapchain down and releases the old window.
void vk_present_set_window(ANativeWindow *window);
/* Compositor thread: apply a pending window change now. Returns 1 if the window changed. */
int vkp_apply_window_request(void);

/* How the scene is mapped onto the output (the app's Container.FULLSCREEN_* / ALIGN_* values,
 * mirrored 1:1 from ViewTransformation.java so touch input, which is mapped by the app with
 * that class, lands on the same pixels). Callable from any thread, applies on the next frame. */
enum vkp_scale_mode { VKP_MODE_OFF = 0, VKP_MODE_FIT = 1, VKP_MODE_STRETCH = 2, VKP_MODE_FILL = 3,
                      VKP_MODE_INTEGER = 4 };
enum vkp_align { VKP_ALIGN_CENTER = 0, VKP_ALIGN_TOP = 1, VKP_ALIGN_BOTTOM = 2 };
void vk_present_set_scale_mode(int mode, int alignment);
/* Output pixel (0..output size) -> scene pixel through the current mapping (compositor thread).
 * Returns 0 before the first frame has established a mapping (sx/sy untouched). */
int vkp_output_to_scene(double ox, double oy, double *sx, double *sy);
/* Output size in pixels (0x0 before the first swapchain). */
void vkp_output_size(int *w, int *h);

struct vkp_image;

/* DRM format modifiers this backend knows the memory layout of (both single-plane on Adreno):
 * LINEAR, and QCOM_COMPRESSED = UBWC (drm_fourcc.h: fourcc_mod_code(QCOM = 0x05, 1)). */
#define VKP_MOD_LINEAR          0x0000000000000000ULL
#define VKP_MOD_QCOM_COMPRESSED 0x0500000000000001ULL
#define VKP_MOD_INVALID         0x00ffffffffffffffULL
/* "linear", "qcom_compressed", or "modifier 0x…" for anything else (static buffer). */
const char *vkp_modifier_name(uint64_t modifier);
/* The modifiers a dma-buf of this DRM fourcc can be imported with as a blit source, asked of the
 * renderer's own driver (VkDrmFormatModifierPropertiesListEXT, each confirmed for a dma-buf-backed
 * TRANSFER_SRC image with vkGetPhysicalDeviceImageFormatProperties2). Only LINEAR and
 * QCOM_COMPRESSED are ever returned (the ones this file can describe a plane layout for); other
 * modifiers the driver reports are logged once. Returns the count, 0 when the device is not up
 * (LINEAR is then the only safe assumption). Compositor thread. */
int vkp_dmabuf_modifiers(uint32_t drm_format, uint64_t *out, int max);

// Import a dmabuf (single plane). NULL on failure. The image aliases the buffer, so
// later client frames rendered into the same buffer show up without re-importing.
struct vkp_image *vkp_image_from_dmabuf(int fd, uint32_t drm_format, uint64_t modifier,
                                        int w, int h, uint32_t stride, uint32_t offset);
// Same, choosing the role: as_blit_dst = 0 imports a client frame (blit source), 1 imports a
// buffer this backend blits INTO (layer mode's AHardwareBuffer pool, see sc_layer.h).
struct vkp_image *vkp_image_import_dmabuf(int fd, uint32_t drm_format, uint64_t modifier,
                                          int w, int h, uint32_t stride, uint32_t offset,
                                          int as_blit_dst);
int vkp_image_is_dmabuf(const struct vkp_image *img);

// Create a host-visible image and copy BGRA/XRGB8888 pixels into it. NULL on failure.
struct vkp_image *vkp_image_create_shm(int w, int h);
void vkp_image_upload_shm(struct vkp_image *img, const void *data, int stride);

int vkp_image_width(const struct vkp_image *img);
int vkp_image_height(const struct vkp_image *img);
void vkp_image_destroy(struct vkp_image *img);

// One scene draw: the src rectangle of an image (image pixels) scaled into the dst
// rectangle (scene pixels). The scene is mapped onto the output by the scale mode.
struct vkp_draw {
    struct vkp_image *img;
    float sx, sy, sw, sh;
    int dx, dy, dw, dh;
    int blend;                /* translucent: composed over what is under it (blend_pass.h) */
};

// The GPU the renderer runs on ("Adreno (TM) 750"), empty before the device is up.
const char *vkp_gpu_name(void);

// 0 if the renderer can create images (device up), -1 otherwise.
int vkp_ready(void);
/* Whether an output window is attached (or requested); without one vkp_render() draws nothing. */
int vkp_has_window(void);
/* 1 once the Vulkan device was lost: nothing is presented any more (the session must restart). */
int vkp_device_lost(void);

// Clear to black, blit the draws in order (first = bottom) and present.
// Returns 0 on success, -1 if nothing could be presented (no window yet, etc.).
int vkp_render(int scene_w, int scene_h, const struct vkp_draw *draws, int n);
/* A black frame with no compositor pass (no effects, no frame generation), whatever is armed: the
 * base surface under an HDR game that keeps its display layer (compositor.c). */
int vkp_render_plain(int scene_w, int scene_h);
/* Layer mode's base surface: make sure it shows a plain black frame under the display layers. A frame
 * is presented only when the base is not already black on the current swapchain (entering layer mode,
 * after a copy-path / frame-generation frame, after a swapchain rebuild); otherwise nothing is drawn
 * or presented at all. 0 = the base is black, -1 = no output. Compositor thread. */
int vkp_base_black(int scene_w, int scene_h);

/* ---- perf counters (the 10 s `perf` line, compositor.c) ----
 * Everything the compositor thread spends in the driver, since the last vkp_perf_take(). Times in ns. */
struct vkp_perf {
    unsigned acquires, presents, waits;
    int64_t acquire_ns, acquire_max_ns;   /* vkAcquireNextImageKHR */
    int64_t present_ns, present_max_ns;   /* vkQueuePresentKHR */
    int64_t wait_ns, wait_max_ns;         /* vkWaitForFences on the compositor's own work */
    unsigned base_presents;               /* black frames presented under the display layers */
    unsigned base_kept;                   /* layer frames that kept the black frame already there */
    unsigned gpu_release_waits;           /* layer-buffer release fences waited for on the GPU */
};
void vkp_perf_take(struct vkp_perf *out);
/* 1 when a release fence (sync_file) can be handed to the GPU as a wait (VK_KHR_external_semaphore_fd):
 * the layer pool then reuses a buffer the display is still reading without blocking on the CPU. */
int vkp_can_wait_sync_fd(void);

/* ---- HDR composition (hdr_compose.h) ----
 * An HDR game that cannot be alone on its display layer: the scene is composed into ONE encoding. */
struct wnc_color;
struct vkp_hdr_frame {
    const unsigned char *is_hdr; /* per draw: 1 = its image holds PQ BT.2020 (the surface's description) */
    float peak_nits;             /* the HDR content's peak, for a tone-map (max CLL / mastering max; 0 = 1000) */
    int tonemap;                 /* 1 = the drawer's HDR output switch is off: tone-map to SDR, never PQ */
    const struct wnc_color *color; /* the topmost HDR draw's description: an HDR10 swapchain's metadata */
};
/* vkp_render for a scene with HDR draws (frame generation): composed into PQ and presented through an
 * HDR10 swapchain where the surface offers one (and hf->tonemap is 0), else tone-mapped into the ordinary
 * one. *how = 1 HDR10, 2 tone-mapped, 0 the HDR pass was unavailable (shown the old way). Same return as
 * vkp_render. */
int vkp_render_hdr(int scene_w, int scene_h, const struct vkp_draw *draws, int n,
                   const struct vkp_hdr_frame *hf, int *how);
/* vkp_pass_begin for a scene with HDR draws: composed into a 10-bit picture - PQ BT.2020, or sRGB
 * tone-mapped when hf->tonemap - the effects run on it in 10-bit, the result (rw x rh, A2B10G10R10) is
 * left for vkp_pass_copy_to() into a layer buffer (a blit: 10-bit or 8-bit). -1 = not possible (nothing
 * recorded). */
int vkp_pass_begin_hdr(int scene_w, int scene_h, const struct vkp_draw *draws, int n,
                       const struct vkp_hdr_frame *hf, int *rw, int *rh);

/* ---- layer mode helpers (sc_layer.c; compositor thread) ---- */
/* Whole-image copy of src into dst (a blit-destination dmabuf image), waited for on the CPU.
 * wait_fd: a sync_file the copy must wait for before writing dst (the display's release fence of that
 * buffer), -1 = none. Always consumed (handed to the GPU, or waited for and closed). */
int vkp_blit_image(struct vkp_image *src, struct vkp_image *dst, int wait_fd);
/* Refresh the scene -> output mapping for this scene size without presenting (creates the
 * swapchain if needed, since the mapping is in output pixels). 0 = mapping valid. */
int vkp_update_map(int scene_w, int scene_h);
/* Map a draw through the current mapping: out = {src x0,y0,x1,y1 (image px), dst x0,y0,x1,y1
 * (output px)}, clipped like the blit path. 0 = nothing of it is visible. */
int vkp_map_draw(const struct vkp_draw *d, int out[8]);
/* The same for a bare w x h buffer shown over the whole scene (no vkp_image: zero-copy layers). */
int vkp_map_rect(int img_w, int img_h, int scene_w, int scene_h, int out[8]);
/* The output window frames go to (NULL = none); compositor thread. */
/* Degrees the display rotates every layer we present by (0/90/180/270), -1 before the swapchain
 * exists. From VkSurfaceCapabilitiesKHR::currentTransform, i.e. what the presentation engine says
 * it does — never a device allowlist. The layer path uses it to decide whether a SECOND display
 * layer is affordable on this display (sc_layer_present_overlay). */
int vkp_surface_rotation_degrees(void);

ANativeWindow *vkp_window(void);
/* Fire the one-shot first-frame notification (layer mode presents outside vkp_render). */
void vkp_signal_first_frame(void);

/* ---- the compositor pass into a layer buffer (sc_layer.c; compositor thread) ----
 * Screen effects on the game's own Android layer: compose `draws` into the scene image and run the
 * effects chain WITHOUT presenting, then copy the result into a gralloc layer buffer. Three steps
 * because the chain's result size (a scaling mode resizes to the scene's mapped output size) is
 * only known once the chain has run, and the layer buffer is allocated from it.
 * begin: 0 = a pass is in progress, its result is *rw x *rh; -1 = not possible (draw the old way).
 * Exactly one of copy_to (blit into dst, submit, wait; 0 = done) / abort (drop it unsubmitted)
 * must follow a successful begin. The chain deliberately runs with NO swapchain image acquired -
 * see the comment in vk_present.c. Frame generation is not run here (see WAYLAND_RUNTIME.md). */
int vkp_pass_begin(int scene_w, int scene_h, const struct vkp_draw *draws, int n, int *rw, int *rh);
/* wait_fd: as for vkp_blit_image (consumed in every case). */
int vkp_pass_copy_to(struct vkp_image *dst, int wait_fd);
void vkp_pass_abort(void);

// Session log (compositor.c): one line to Download/Wayland-logs and logcat.
void wnc_log(const char *tag, const char *fmt, ...) __attribute__((format(printf, 2, 3)));

#endif
