#ifndef SC_LAYER_H
#define SC_LAYER_H
/*
 * Layer mode (BANNER_WAYLAND_ZERO_COPY=1): the scene is handed to SurfaceFlinger as a small,
 * deliberately ordered SET of Android display layers — ASurfaceControl children of the
 * compositor's SurfaceView — instead of being blitted into the compositor's own swapchain.
 *
 *     app window ......... Compose UI, the in-game drawer, the HUD, the on-screen controls and
 *                          the pointer arrow: ordinary Android views, ALWAYS above everything
 *                          below (the SurfaceView is not Z-on-top, so its whole subtree is under
 *                          the window's own content). None of the layers below take input:
 *                          an ASurfaceControl has no input channel, so touch and mouse keep
 *                          reaching the SurfaceView exactly as they did.
 *     +-- SurfaceView ..... the compositor's Vulkan swapchain; black while layer mode is up
 *          +-- z=1 "wnc_wayland_game" ...... the one fullscreen game window
 *          +-- z=2 "wnc_wayland_overlay" ... at most one window drawn ABOVE the game
 *
 * TWO layers is the hard cap (SC_LAYER_COUNT), and deliberately so: HWC composes only a few
 * layers before SurfaceFlinger falls back to GPU client composition, which would throw away the
 * whole benefit. The count is logged when the second layer first goes up.
 *
 * MEASURED on the Pocket FIT (Adreno 750, portrait panel + landscape session, so every layer is
 * ROT_90 + scaled), `dumpsys android.hardware.graphics.composer3.IComposer/default`: one layer is
 * `composition: DEVICE/DEVICE`, effects on the layer keep it DEVICE/DEVICE — but a SECOND layer
 * flips the whole frame to `DEVICE/CLIENT`. It does not come back when the overlay goes away.
 * The overlay layer is retired rather than hidden for that reason, but the fallback outlives it.
 * The likely mechanism is the DPU's rotator budget (one rotated+scaled layer), not the layer count
 * as such, so a device or orientation needing no rotation may well take both on the DPU. Even in
 * client composition the overlay layer is not a loss — SurfaceFlinger does the one blit the
 * compositor would have done — but the hardware-composition win is only real for one layer.
 *
 * COMPOSITION RECOVERY (sc_layer.c, swap_sc_begin): retiring the overlay arms the game layer, and
 * the NEXT frame is presented on a brand-new SurfaceControl while the old one is hidden and
 * unparented in the SAME transaction. SurfaceFlinger applies a transaction atomically, so no
 * composited frame is ever missing the game — no black frame, no dropped frame beyond the layer
 * creation itself. One `layer` line is written when it happens.
 *
 * ⚠️ It does NOT restore hardware composition on this panel, and that was measured, not assumed
 * (2026-09-14, Wizardry on the game layer + Wine's Task Manager on the overlay, reproduced twice):
 *   - two layers                                  -> DEVICE/CLIENT
 *   - overlay retired + fresh game SurfaceControl -> still DEVICE/CLIENT, 24 s later too
 *   - layer path dropped entirely and re-created  -> still DEVICE/CLIENT
 *   - drawer opened and closed, no second window  -> DEVICE/DEVICE (control: the drawer is not the cause)
 *   - HOME + resume                               -> DEVICE/DEVICE
 * HOME + resume re-creates the app's whole window and SurfaceView (`VRI[XServerDisplayActivity]#0`
 * becomes `#4`), not just this child layer — so the sticky state belongs to the parent surface or
 * the display. Re-creating the parent would cost a real black frame and a swapchain rebuild, which
 * is worse than the few percent of GPU that client composition costs. The cure that actually keeps
 * the win is PREVENTION, and it IS implemented: sc_layer_overlay_affordable() declines the second
 * layer when the game layer is both rotated and scaled, and the window above the game goes down the
 * copy path instead (what the pre-phase-4 code did) — one blit SurfaceFlinger would have done
 * anyway, and the session keeps DEVICE composition throughout. Where a second layer costs nothing,
 * nothing changes.
 *
 * What can be on the game layer, cheapest first:
 *   - the game's own gralloc buffer (ahb_swapchain.c, true zero-copy: no copy anywhere);
 *   - one blit of the game's frame into a compositor-allocated AHardwareBuffer (sc_layer_present);
 *   - with screen effects on, the compositor pass's result blitted into such a buffer
 *     (sc_layer_present_pass) — the game STAYS on its layer while a Look is applied, and the
 *     scene -> output mapping is done by the display (setGeometry) instead of a second GPU blit.
 * The compositor never alpha-blends (it composes with blits, which overwrite), so every layer is
 * marked OPAQUE and the overlay layer is cropped to the window it carries: the picture is the
 * same as the copy path's, pixel for pixel.
 *
 * Compositor thread only, except the SurfaceFlinger OnComplete callbacks (binder threads), which
 * only touch the pools under g_lock. sc_layer_hide*() is safe at any time.
 */
#include <stddef.h>
#include <stdint.h>
#include <android/hardware_buffer.h>

struct vkp_image;
struct vkp_draw;
struct wnc_color;

/* The layers, bottom first. The z-order is the array order (z = id + 1). */
enum sc_layer_id { SC_LAYER_GAME = 0, SC_LAYER_OVERLAY = 1, SC_LAYER_COUNT = 2 };

/* 1 when this device has the SurfaceControl API the layers need (Android 10+, libnativewindow's
 * getNativeHandle); the reason is logged (tag `layer`) the first time it is missing. */
int sc_layer_available(void);

/* One-shot: does the kernel export a sync_file from the game's dma-buf (DMA_BUF_IOCTL_EXPORT_SYNC_FILE)?
 * Decides where a zero-copy acquire fence can come from; result goes to the session log. */
void sc_layer_probe_dmabuf_fd(int fd);

/* HDR (color_mgmt.h): can a display layer be told its buffer's colour encoding here
 * (ASurfaceTransaction_setBufferDataSpace, Android 10+)? Part of the HDR gate. */
int sc_layer_can_tag_hdr(void);
/* Which of the colour calls this libandroid has, for the gate's log line. */
void sc_layer_hdr_symbols(char *out, size_t size);

/* COLOUR on the game layer: `color` is the image description of the frame being presented (NULL =
 * none, i.e. sRGB). A layer that was never tagged is never touched (no setBufferDataSpace call at all:
 * a session without an HDR description behaves exactly as before); once tagged, a later untagged frame
 * puts the layer back to UNKNOWN. SMPTE 2086 / CTA-861.3 metadata travel with the dataspace. */

/* GAME layer: show `src` (the fullscreen window's imported frame, scene-sized) on it, placed
 * through the current fullscreen mode / alignment mapping. 0 = shown (or deliberately dropped: no
 * free buffer), -1 = the layer path is unavailable this frame and the caller must draw the old way.
 * The copy into the layer buffer is 8-bit: an HDR frame keeps its tag (its colours stay right), and
 * loses precision. */
int sc_layer_present(struct vkp_image *src, int scene_w, int scene_h, const struct wnc_color *color);

/* GAME layer, screen effects on: run the compositor pass (composite `draws` + the effects chain,
 * vkp_pass_begin) and put its result on the layer. Same return values as sc_layer_present. */
int sc_layer_present_pass(const struct vkp_draw *draws, int n, int scene_w, int scene_h);

/* GAME layer, HDR (hdr_compose.h): the whole scene - `draws`, hf->is_hdr marking the HDR ones - composed
 * into one 10-bit PQ BT.2020 picture with the effects applied (vkp_pass_begin_hdr), copied into a
 * 10-bit layer buffer (8-bit where gralloc refuses those) and shown tagged with `color` (the topmost HDR
 * draw's description). The overlay layer is not used: the picture already holds what is above the game.
 * Same return values as sc_layer_present. */
struct vkp_hdr_frame;
int sc_layer_present_hdr_scene(const struct vkp_draw *draws, int n, const struct vkp_hdr_frame *hf,
                               int scene_w, int scene_h, const struct wnc_color *color);

/* GAME layer, zero-copy (ahb_swapchain.c): show the game's own w x h AHardwareBuffer, gated by
 * acquire_fd (a sync_file the layer waits on before reading; owned by the callee, -1 = none).
 * token identifies the buffer: once a later transaction replaces it (or the layer is hidden or
 * retired), ahb_swapchain_layer_released(token, release_fd) reports SurfaceFlinger's release fence
 * for it. Presenting the token already on the layer only updates the placement. 0 = on the layer,
 * 1 = nothing of it is on screen (layer hidden, the buffer was not taken), -1 = unavailable this
 * frame (the caller draws the old way). `color` / `ahb_format`: the frame's image description (NULL =
 * none) and the buffer's AHARDWAREBUFFER_FORMAT_*, for the dataspace and the HDR evidence. */
int sc_layer_present_ahb(AHardwareBuffer *ahb, int w, int h, int acquire_fd, void *token,
                         int scene_w, int scene_h, const struct wnc_color *color, uint32_t ahb_format);

/* Vote a panel refresh rate for the layer the game presents on (VRR / refresh-rate matching), the
 * same rate and compatibility the app votes on its own surface with Surface.setFrameRate; 0 = no
 * vote. Needed because a zero-copy game's frames go onto the GAME layer and never reach the app's
 * surface, so the surface vote alone does not describe the game's cadence to SurfaceFlinger. Only
 * the game layer ever carries it: the overlay layer is explicitly voted 0, so a window that redraws
 * once a second can never hold (or drop) the panel at the game's cadence. Callable from any thread
 * at any time (it only stores the rate); the compositor applies it with the layer's next
 * transaction, and re-applies it whenever a SurfaceControl is re-created. A no-op on devices whose
 * libandroid has no ASurfaceTransaction_setFrameRate. */
void sc_layer_set_frame_rate(float fps);

/* Is a SECOND display layer worth raising on THIS display? Ask before committing the game to its
 * layer, not after: on a display where the answer is no, the whole scene must go down the copy path
 * from the start, or the game layer would be shown and hidden again on every frame.
 *
 * The answer is computed from what the layer path knows — the rotation the presentation engine
 * applies to everything we hand it (VkSurfaceCapabilitiesKHR::currentTransform) and the game
 * layer's own src -> dst rectangles — and never from a device or panel allowlist. ROTATED AND
 * SCALED is the combination measured to cost hardware composition (below); either alone is fine.
 * The reason is written to the session log once, and again if the answer changes. */
int sc_layer_overlay_affordable(void);

/* OVERLAY layer: show `src` (one window's imported frame) above the game layer, at the placement
 * `geo` = {src x0,y0,x1,y1 in image pixels, dst x0,y0,x1,y1 in output pixels} from vkp_map_draw.
 * 0 = shown or dropped, -1 = unavailable (the caller must fall back to the copy path). */
int sc_layer_present_overlay(struct vkp_image *src, const int geo[8]);

/* The scene is not a single fullscreen window this frame: hide every layer that is up. */
void sc_layer_hide(void);
/* Only the overlay layer: nothing is above the game any more. The game keeps its layer, but its
 * SurfaceControl is swapped for a fresh one on the next frame (composition recovery, above — which
 * on this panel does not in fact win hardware composition back; see the measurement there). */
void sc_layer_hide_overlay(void);

/* The output window changed or went away (compositor thread, from vkp_apply_window_request). */
void sc_layer_window_gone(void);

/* Frames the compositor put on a layer through one of its own buffers (a blit or the effects
 * pass) since the last call — the zero-copy frames ahb_swapchain.c counts are NOT included.
 * For the 10 s summary. */
unsigned sc_layer_frames_take(void);
/* Frames dropped since the last call because every layer buffer was still held by the display (the
 * 10 s perf line; the "no free layer buffer" log line itself is rate-limited). */
unsigned sc_layer_drops_take(void);

#endif
