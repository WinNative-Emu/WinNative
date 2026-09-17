#ifndef WNC_COLOR_H
#define WNC_COLOR_H
/*
 * HDR10 output on the Wayland backend (opt-in) — the compositor half of HDR_RECON.md Phase A (round 1)
 * plus HDR-aware composition (round 2, hdr_compose.h), implemented in wl_color_mgmt.c. The game half
 * already ships: Mesa's Wayland WSI in our Turnip is a wp_color_manager_v1 client and exposes
 * VK_COLOR_SPACE_HDR10_ST2084_EXT as soon as a compositor advertises BT.2020 + ST 2084; DXVK takes it
 * with DXVK_HDR=1 (the app exports it when the setting is on).
 *
 * THE GATE. Nothing here exists for a session unless all of these hold when the compositor starts:
 *   - the container's / game's "HDR output" setting is on, or BANNER_WAYLAND_HDR=1 in their
 *     environment (which overrides the setting either way);
 *   - the display the game is on lists HDR10 among its supported HDR types (read by the app from
 *     android.view.Display — a property of the connector, not of the device);
 *   - display layers with dataspace control (ASurfaceTransaction_setBufferDataSpace, Android 10+)
 *     and the zero-copy global (banner_ahb_v1): the HDR frame (or the composed HDR picture) is shown
 *     on the game's own display layer, tagged BT2020_PQ.
 * In game, the drawer's HDR output switch (wnc_color_set_output) flips between that and the same
 * frames tone-mapped to SDR, live; the gate itself never changes during a session.
 * A closed gate advertises nothing — no colour-management global, no 10-bit dma-buf formats — so the
 * session is byte-for-byte what it was before this file existed, and the session log says why.
 * BANNER_WAYLAND_HDR=force skips the display check only (testing the negotiation on an SDR panel;
 * SurfaceFlinger then tone-maps the layer, and says so in its composition type).
 *
 * WHAT AN OPEN GATE OFFERS (exactly the subset Mesa binds, HDR_RECON.md §2.2): wp_color_manager_v1
 * version 1 with the perceptual intent, the parametric creator + mastering-display metadata,
 * BT.2020 primaries and the ST 2084 (PQ) transfer function; zwp_linux_dmabuf_v1 adds AB30/XB30
 * (A2B10G10R10, the only 10-bit layout our zero-copy WSI can put in a gralloc buffer). A surface's
 * image description is double-buffered on wl_surface.commit; its frames go onto the game's display
 * layer tagged BT2020_PQ with the game's SMPTE 2086 / CTA-861.3 metadata (sc_layer.c).
 *
 * Compositor thread unless noted.
 */
#include <stdint.h>
#include <wayland-server.h>

/* ADataSpace values (NDK <android/data_space.h>) the layer path emits. */
#define WNC_ADATASPACE_UNKNOWN    0
#define WNC_ADATASPACE_BT2020_PQ  163971072 /* STANDARD_BT2020 | TRANSFER_ST2084 | RANGE_FULL = 0x09c60000 */
#define WNC_ADATASPACE_BT2020_HLG 168165376 /* STANDARD_BT2020 | TRANSFER_HLG | RANGE_FULL */

/* One image description as the program made it, and what it becomes on a display layer. Immutable:
 * a changed description is a new record with a new identity. */
struct wnc_color {
    uint32_t identity;                   /* the id the `ready` event carried (never 0) */
    uint32_t primaries, tf;              /* wp_color_manager_v1 named values */
    int32_t dataspace;                   /* ADataSpace on a display layer; 0 = none (treated as sRGB) */
    int has_st2086;                      /* SMPTE ST 2086: mastering display primaries + luminance */
    float red[2], green[2], blue[2], white[2]; /* CIE 1931 xy */
    float max_lum, min_lum;              /* nits */
    int has_cta861;                      /* CTA-861.3 */
    float max_cll, max_fall;             /* nits; 0 = not given */
    char text[256];                      /* the session log's one-line summary of it */
};

/* ---- app -> compositor (JNI, any thread) */
/* mode 0 = off, 1 = BANNER_WAYLAND_HDR=1, 2 = BANNER_WAYLAND_HDR=force (testing). source names where
 * the switch came from; dxvk_hdr = DXVK_HDR=1 is in the game's environment; zero_copy_forced = the
 * app turned zero-copy presentation on for this session because HDR needs it. Before the start. */
void wnc_color_set_request(int mode, const char *source, int dxvk_hdr, int zero_copy_forced);
/* The display the game is on, as android.view.Display reports it. Before the start (it feeds the
 * gate) and again whenever it changes (logged; the gate is decided once per session). */
void wnc_color_set_display(int id, const char *name, const char *formats, int hdr10, float max_lum,
                              float max_avg, float min_lum, int ratio_available, float ratio, int api);
/* One reading of Display.getHdrSdrRatio() (API 34+; < 0 = not available). listener = it came from
 * the display's ratio listener rather than the app's periodic sampler. */
void wnc_color_ratio_sample(float ratio, int listener);
/* Milliseconds since an HDR frame last went onto a display layer; -1 = none this session. */
int wnc_color_last_frame_age_ms(void);
/* -1 = the compositor has not decided yet, 0 = closed, 1 = open. */
int wnc_color_gate_state(void);
/* The session is ending: write the summary line ("HDR on screen: …"). */
void wnc_color_session_end(void);
/* 1 while HDR frames are really on screen: a frame tagged BT2020_PQ reached a display layer or the HDR
 * swapchain in the last 1.5 s AND, where the display reports an HDR/SDR ratio, its last reading is
 * above 1.01 (the HUD badge). */
int wnc_color_hdr_on_screen(void);
/* The HUD / drawer state: 0 = no HDR frames on screen (or not confirmed yet), 1 = HDR frames on screen
 * with headroom (as wnc_color_hdr_on_screen), 2 = HDR frames on screen but the display has given
 * them NO headroom (ratio <= 1.01) for 5 s or more - the brightness slider at maximum, or a screen
 * recording (Android turns HDR headroom off while the screen is recorded). */
int wnc_color_hdr_state(void);
/* Nits SDR content is placed at inside an HDR picture (default 203, BT.2408); any thread. */
void wnc_color_set_sdr_white(float nits);
float wnc_color_sdr_white(void);
/* The HDR session was asked for (BANNER_WAYLAND_HDR / the setting resolved on or force) — known before
 * the compositor starts, so the Vulkan instance can enable VK_EXT_swapchain_colorspace for it. */
int wnc_color_requested(void);
/* The drawer's live "HDR output" switch, per session, starting ON. On = HDR frames go to the display as
 * HDR; off = the SAME frames are composed tone-mapped to SDR (hdr_compose.h) - the game is told nothing
 * and keeps rendering HDR (DXVK_HDR and the colour-manager offer were decided at launch). Compositor
 * thread (the app posts it through wnc_host_hdr_output); logs the flip and redraws. */
void wnc_color_set_output(int on);
/* 1 = HDR output on (any thread). */
int wnc_color_output(void);
/* 1 while an HDR game's frames are being shown tone-mapped to SDR (the last one < 1.5 s ago); any thread. */
int wnc_color_tonemapped_on_screen(void);
/* Device evidence beside the HDR/SDR headroom (the app, non-root APIs; any thread; HDR sessions only):
 * PowerManager thermal status (0..6, -1 unknown) + getThermalHeadroom(10) (< 0 = not available),
 * Settings.System SCREEN_BRIGHTNESS (0..255, -1 unknown) + SCREEN_BRIGHTNESS_MODE (1 auto, 0 manual).
 * Changes of status and brightness are logged; every no-headroom line, the 10 s line and the verdict
 * carry the last values. */
void wnc_color_env_sample(int thermal, float headroom, int brightness, int bmode);

/* ---- the explicit HDR headroom request (Android 15+: ASurfaceTransaction_setDesiredHdrHeadroom on the game
 * layer, SurfaceView.setDesiredHdrHeadroom on the screen surface). Some phones only boost HDR when asked. */
/* The ratio to ask for an HDR frame of `c`: content peak (max CLL, else mastering max, else the display's
 * peak) / SDR white (203), capped at the display's highest ratio when it reports one; 0 = ask nothing
 * (not HDR, or the display reports it cannot boost). `why` gets the one-line reason. Any thread. */
float wnc_color_desired_headroom(const struct wnc_color *c, char *why, size_t n);
/* What was last asked for (> 0 the ratio, 0 nothing, -1 the API is missing): for the no-headroom lines. */
void wnc_color_note_headroom_request(float ratio);
/* Display.getHighestHdrSdrRatio() (Android 16+; <= 0 = not reported). Any thread; logged when it arrives. */
void wnc_color_set_highest_ratio(float ratio);
/* The headroom the SCREEN surface should ask for (frames through the HDR10 swapchain in the last 1.5 s),
 * 0 = none; `why` gets the reason. The app polls it and applies it. Any thread. */
float wnc_color_screen_headroom(char *why, size_t n);

/* ---- compositor.c -> here */
/* Decide the gate (call after ahb_swapchain_init) and create the global when it is open. */
void wnc_color_init(struct wl_display *display);
/* The gate is open: the 10-bit dma-buf formats may be advertised, descriptions may exist. */
int wnc_color_hdr_open(void);
/* wl_surface.commit: the pending image description (if any request touched it) becomes current. */
void wnc_color_commit(struct wl_resource *surface);
/* The surface's current image description; NULL = none (sRGB, today's default). */
const struct wnc_color *wnc_color_of(struct wl_resource *surface);
/* A program disconnected: if it presented HDR, its part of the summary is written now. */
void wnc_color_client_gone(struct wl_client *client);
/* The 10 s summary tick (one `color` line when anything HDR happened in the window). */
void wnc_color_stats_tick(void);
/* A frame of an HDR-described surface went through the compositor's own 8-bit sRGB pass this scene
 * (who = the window, reason = why it did not get the display layer). Shown untone-mapped. */
void wnc_color_frame_copied(const char *who, const char *reason);

/* ---- sc_layer.c / compositor.c -> here: a NEW frame of an HDR-described surface was shown, and how */
enum wnc_hdr_path {
    WNC_HDR_ZERO_COPY = 0,   /* the game's own gralloc buffer on its display layer, tagged */
    WNC_HDR_LAYER_COPY,      /* one 8-bit copy of the game's frame on its layer, tagged */
    WNC_HDR_COMPOSED,        /* the whole scene composed into one PQ picture on the game layer (hdr_compose.h) */
    WNC_HDR_SWAPCHAIN,       /* composed into PQ and presented through an HDR10 swapchain (frame generation) */
    WNC_HDR_TONEMAPPED,      /* composed and tone-mapped to SDR (HDR output switched off, or a present that
                                 * cannot carry HDR) - NOT HDR */
};
/* ahb_format = the buffer's AHARDWAREBUFFER_FORMAT_* where one exists (0 otherwise). */
void wnc_color_frame_shown(const struct wnc_color *c, int path, uint32_t ahb_format);
/* "RGBA1010102 (10-bit)" etc. for an AHARDWAREBUFFER_FORMAT_* value (static buffer). */
const char *wnc_ahb_format_name(uint32_t format);

#endif
