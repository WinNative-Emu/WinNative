/*
 * wp_color_manager_v1 (wayland-protocols staging color-management-v1, version 1) — the subset Mesa's
 * Wayland WSI binds — plus the HDR gate, the per-surface image descriptions and the session log's
 * HDR evidence. See color_mgmt.h for the design and HDR_RECON.md for why it is shaped this way.
 *
 * Strictness policy. A protocol error disconnects the client, and the client here is a GAME, so
 * errors are only raised where the protocol leaves no choice and a conforming client can never hit
 * them: a request gated on a feature we did not advertise, a property set twice, an incomplete
 * parameter set, a description that is not ready, an intent we did not advertise. Everything a
 * conforming client CAN produce on a bad day is accepted and logged instead: out-of-range HDR
 * metadata is dropped (Android gets none rather than nonsense), a second colour-management object
 * for the same surface replaces the first, a request on an inert object is ignored.
 */
#define _GNU_SOURCE
#include "color_mgmt.h"
#include "desktop_ext.h"
#include "ahb_swapchain.h"
#include "sc_layer.h"
#include "color-management-v1-server-protocol.h"
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

extern volatile int g_zero_copy;

#define TAG "color"

/* AHARDWAREBUFFER_FORMAT_* (android/hardware_buffer.h), for the log only. */
const char *wnc_ahb_format_name(uint32_t f) {
    static char other[32];
    switch (f) {
    case 1: return "RGBA8888 (8-bit)";
    case 2: return "RGBX8888 (8-bit)";
    case 3: return "RGB888 (8-bit)";
    case 4: return "RGB565";
    case 0x16: return "RGBA16F (16-bit float)";
    case 0x2b: return "RGBA1010102 (10-bit)";
    default: snprintf(other, sizeof(other), "format %#x", f); return other;
    }
}

static int64_t now_ns(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static const char *primaries_name(uint32_t p) {
    switch (p) {
    case WP_COLOR_MANAGER_V1_PRIMARIES_SRGB: return "BT.709/sRGB";
    case WP_COLOR_MANAGER_V1_PRIMARIES_BT2020: return "BT.2020";
    case WP_COLOR_MANAGER_V1_PRIMARIES_DISPLAY_P3: return "Display P3";
    case WP_COLOR_MANAGER_V1_PRIMARIES_DCI_P3: return "DCI-P3";
    case WP_COLOR_MANAGER_V1_PRIMARIES_ADOBE_RGB: return "Adobe RGB";
    default: return "other primaries";
    }
}

static const char *tf_name(uint32_t tf) {
    switch (tf) {
    case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ: return "ST 2084 (PQ)";
    case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_HLG: return "HLG";
    case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_SRGB: return "sRGB";
    case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_EXT_LINEAR: return "extended linear";
    case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_GAMMA22: return "gamma 2.2";
    case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_BT1886: return "BT.1886";
    default: return "other transfer function";
    }
}

static const char *dataspace_name(int32_t ds) {
    switch (ds) {
    case WNC_ADATASPACE_BT2020_PQ: return "BT2020_PQ";
    case WNC_ADATASPACE_BT2020_HLG: return "BT2020_HLG";
    case WNC_ADATASPACE_UNKNOWN: return "UNKNOWN (sRGB)";
    default: return "other";
    }
}

/* ---------------------------------------------------------------- request (app) + gate */

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER; /* g_req, g_hdr (app threads + compositor) */

static struct {
    int mode;                       /* 0 off, 1 on, 2 forced */
    char source[64];
    int dxvk_hdr, zero_copy_forced;
    int display_known;
    int display_id, hdr10, ratio_available, api;
    char display_name[96], formats[96];
    float max_lum, max_avg, min_lum, ratio;
    float highest_ratio;            /* Display.getHighestHdrSdrRatio() (Android 16+), < 0 = not reported */
} g_req = {.highest_ratio = -1.0f};

static _Atomic int g_gate = -1;     /* -1 undecided, 0 closed, 1 open */
static char g_gate_why[320];        /* the closed gate's reason (for the summary) */
static _Atomic int g_output_on = 1; /* the drawer's HDR output switch (per session, starts on) */

void wnc_color_set_request(int mode, const char *source, int dxvk_hdr, int zero_copy_forced) {
    pthread_mutex_lock(&g_mu);
    g_req.mode = mode < 0 ? 0 : mode > 2 ? 2 : mode;
    snprintf(g_req.source, sizeof(g_req.source), "%s", source ? source : "environment");
    g_req.dxvk_hdr = dxvk_hdr ? 1 : 0;
    g_req.zero_copy_forced = zero_copy_forced ? 1 : 0;
    pthread_mutex_unlock(&g_mu);
    atomic_store(&g_output_on, 1); /* the drawer's switch is per session and starts on */
}

void wnc_color_set_display(int id, const char *name, const char *formats, int hdr10, float max_lum,
                              float max_avg, float min_lum, int ratio_available, float ratio, int api) {
    pthread_mutex_lock(&g_mu);
    int was_known = g_req.display_known, was_hdr10 = g_req.hdr10, was_id = g_req.display_id;
    g_req.display_known = 1;
    g_req.display_id = id;
    snprintf(g_req.display_name, sizeof(g_req.display_name), "%s", name ? name : "?");
    snprintf(g_req.formats, sizeof(g_req.formats), "%s", formats ? formats : "unknown");
    g_req.hdr10 = hdr10 ? 1 : 0;
    g_req.max_lum = max_lum; g_req.max_avg = max_avg; g_req.min_lum = min_lum;
    g_req.ratio_available = ratio_available ? 1 : 0;
    g_req.ratio = ratio;
    g_req.api = api;
    pthread_mutex_unlock(&g_mu);
    /* The gate is decided once, when the compositor starts: a display that changes under a running
     * session is recorded, and what it means is said out loud. */
    if (was_known && atomic_load(&g_gate) >= 0 && (was_hdr10 != (hdr10 ? 1 : 0) || was_id != id)) {
        if (atomic_load(&g_gate) == 1 && !hdr10)
            wnc_log(TAG, "the game is now on \"%s\" (display %d), which reports no HDR10: HDR frames stay tagged "
                       "BT2020_PQ and SurfaceFlinger tone-maps them for this display (the offer to games is fixed "
                       "for the session)", name ? name : "?", id);
        else if (atomic_load(&g_gate) == 0 && hdr10)
            wnc_log(TAG, "the game is now on \"%s\" (display %d), which reports HDR10 - HDR stays off for this "
                       "session (the gate is decided when the compositor starts); relaunch to use it", name ? name : "?", id);
    }
}

int wnc_color_gate_state(void) { return atomic_load(&g_gate); }
int wnc_color_hdr_open(void) { return atomic_load(&g_gate) == 1; }

/* ---------------------------------------------------------------- HDR evidence (summary) */

static struct {
    unsigned descs;                 /* HDR image descriptions made ready */
    unsigned applied;               /* commits that made one current on a surface */
    char applied_who[160];          /* the last surface that got one */
    uint64_t layer_frames, layer_10bit, layer_copy8, copy_frames; /* layer_frames = every frame shown AS HDR */
    uint64_t composed, swapchain, tonemapped;
    uint64_t tm_off;                /* of `tonemapped`: while the drawer's HDR output switch was off */
    unsigned output_offs;           /* times the switch was turned off */
    unsigned win_layer, win_copy, win_copy8, win_zc, win_composed, win_swapchain, win_tonemapped;
    char copy_reason[160];
    char layer_fmt[48];
    int ratio_n, win_ratio_n, ratio_live_n;
    float ratio_min, ratio_max, ratio_last, win_ratio_min, win_ratio_max, ratio_logged;
    float ratio_live_max;           /* highest reading taken WHILE HDR frames were on screen (the verdict's) */
    int64_t ratio_logged_ns, ratio_periodic_ns;
    /* Live headroom. The display may grant HDR frames no headroom at all while they are on screen - seen
     * on the Fold while the screen was being RECORDED (Android turns HDR headroom off for a recording,
     * which is SDR), and plausibly with the brightness slider at maximum (SDR white already at the panel's
     * limit) - so "the ratio rose once" is not enough for a reader at minute 3: time with HDR frames on
     * screen, the part of it with the ratio above 1, and the current no-headroom streak. */
    int64_t prev_sample_ns; int prev_live; float prev_ratio;
    int64_t live_ns, headroom_ns;
    int64_t nohead_since_ns;        /* HDR frames on screen and ratio <= 1.01 since then (0 = not now) */
    int nohead_said;                /* the streak's "no headroom" line is written */
    char verdict[512];
} g_hdr;
static _Atomic int64_t g_last_frame_ns;
static _Atomic int64_t g_last_tm_ns;    /* the last HDR frame shown tone-mapped to SDR */

/* What the device says beside the headroom (the app, non-root APIs; wnc_color_env_*). The Fold showed
 * three ways to lose HDR headroom with HDR frames on screen - a screen recording, heat under load, and
 * (plausibly) the brightness slider at maximum - so every no-headroom line carries this. Under g_mu. */
static struct {
    int known;
    int thermal;            /* PowerManager THERMAL_STATUS_* (0 none .. 6 shutdown), -1 unknown */
    float headroom;         /* PowerManager.getThermalHeadroom(10), < 0 = not available */
    int brightness;         /* Settings.System.SCREEN_BRIGHTNESS (0..255), -1 unknown */
    int bmode;              /* SCREEN_BRIGHTNESS_MODE: 1 automatic, 0 manual, -1 unknown */
    float requested;        /* the last HDR headroom asked for (layer or screen surface): > 0 the ratio,
                             * 0 none asked, -1 the API is missing on this Android (< 15) */
} g_env = {0, -1, -1.0f, -1, -1, 0.0f};

static const char *thermal_name(int t) {
    switch (t) {
    case 0: return "NONE"; case 1: return "LIGHT"; case 2: return "MODERATE"; case 3: return "SEVERE";
    case 4: return "CRITICAL"; case 5: return "EMERGENCY"; case 6: return "SHUTDOWN"; default: return "?";
    }
}

/* "thermal MODERATE (headroom 0.83), brightness 180/255 manual". Caller holds g_mu. */
static void env_text_locked(char *out, size_t n) {
    char t[48], b[40];
    if (g_env.thermal < 0) snprintf(t, sizeof(t), "thermal ?");
    else if (g_env.headroom >= 0.0f) snprintf(t, sizeof(t), "thermal %s (headroom %.2f)", thermal_name(g_env.thermal),
                                              g_env.headroom);
    else snprintf(t, sizeof(t), "thermal %s", thermal_name(g_env.thermal));
    if (g_env.brightness < 0) snprintf(b, sizeof(b), "brightness ?");
    else snprintf(b, sizeof(b), "brightness %d/255%s", g_env.brightness,
                  g_env.bmode == 1 ? " auto" : g_env.bmode == 0 ? " manual" : "");
    snprintf(out, n, "%s, %s", t, b);
}

/* What the evidence says about headroom lost with HDR frames on screen. A screenshot or a screen recording
 * is not detected in this build (that needs extra permissions), so it is named as a possibility when
 * nothing the app can measure explains the loss. Caller holds g_mu. */
static void nohead_causes_locked(char *out, size_t n, int brief) {
    const char *parts[2];
    char hot[48];
    int np = 0;
    if (g_env.thermal >= 2) {
        snprintf(hot, sizeof(hot), "the device is hot (thermal %s)", thermal_name(g_env.thermal));
        parts[np++] = hot;
    }
    /* Maximum brightness only counts when it is MANUAL: the Fold kept 3.00 of headroom at 255 auto. */
    if (g_env.brightness >= 250 && g_env.bmode == 0) parts[np++] = "brightness at maximum (manual)";
    if (np == 1) snprintf(out, n, "likely %s", parts[0]);
    else if (np == 2) snprintf(out, n, "likely %s and %s", parts[0], parts[1]);
    else if (g_env.known) {
        char req[48], hi[48];
        if (g_env.requested > 0.0f) snprintf(req, sizeof(req), "%.1fx", g_env.requested);
        else snprintf(req, sizeof(req), "%s", g_env.requested < 0.0f ? "not possible below Android 15" : "none");
        if (g_req.highest_ratio > 0.0f) snprintf(hi, sizeof(hi), "%.2f", g_req.highest_ratio);
        else snprintf(hi, sizeof(hi), "not reported");
        if (brief)
            snprintf(out, n, "no visible cause (a screenshot or recording? no HDR boost for apps?) - asked %s, highest "
                     "ratio %s", req, hi);
        else
            snprintf(out, n, "no cause the app can see (not hot, brightness below maximum): a screenshot or screen "
                     "recording, or this phone does not boost HDR from apps (the requested headroom was %s, the "
                     "display's highest ratio is %s)", req, hi);
    } else
        snprintf(out, n, "a screenshot or screen recording, heat, or manual brightness at maximum");
}

/* ---- the explicit HDR headroom request (API 35; sc_layer.c for the game layer, the app for the screen
 * surface): content peak / SDR white, capped at the display's highest ratio when it reports one. */
static _Atomic int g_screen_hr_x1000;       /* the screen surface's wanted headroom x1000 (HDR10 swapchain) */
static _Atomic int64_t g_screen_hr_ns;      /* the last HDR10-swapchain frame */
static char g_screen_hr_why[200];           /* under g_mu */

static float peak_of(const struct wnc_color *c, float disp_max, const char **src) {
    if (c->max_cll > 0.0f) { *src = "max CLL"; return c->max_cll; }
    if (c->has_st2086 && c->max_lum > 0.0f) { *src = "mastering max"; return c->max_lum; }
    if (disp_max > 0.0f) { *src = "the display's peak"; return disp_max; }
    *src = "assumed"; return 1000.0f;
}

float wnc_color_desired_headroom(const struct wnc_color *c, char *why, size_t n) {
    if (!c || !c->dataspace) { if (why && n) why[0] = 0; return 0.0f; }
    pthread_mutex_lock(&g_mu);
    const float disp_max = g_req.max_lum, highest = g_req.highest_ratio;
    pthread_mutex_unlock(&g_mu);
    const char *src;
    const float peak = peak_of(c, disp_max, &src), white = wnc_color_sdr_white();
    if (highest > 0.0f && highest <= 1.01f) {
        if (why && n) snprintf(why, n, "the display reports its highest HDR/SDR ratio as %.2f: no app can get a "
                               "boost here, so nothing is asked", highest);
        return 0.0f;
    }
    float r = peak / (white > 0.0f ? white : 203.0f);
    const int capped = highest > 1.0f && r > highest;
    if (capped) r = highest;
    if (r < 1.0f) r = 1.0f;
    if (why && n) {
        if (capped) snprintf(why, n, "content peak %.0f nits (%s) / SDR %.0f, capped at the display's highest ratio %.2f",
                             peak, src, white, highest);
        else snprintf(why, n, "content peak %.0f nits (%s) / SDR %.0f%s", peak, src, white,
                      highest > 0.0f ? "" : "; the display reports no highest ratio");
    }
    return r;
}

void wnc_color_note_headroom_request(float ratio) {
    pthread_mutex_lock(&g_mu);
    g_env.requested = ratio;
    pthread_mutex_unlock(&g_mu);
}

void wnc_color_set_highest_ratio(float ratio) {
    pthread_mutex_lock(&g_mu);
    const float was = g_req.highest_ratio;
    g_req.highest_ratio = ratio > 0.0f ? ratio : -1.0f;
    pthread_mutex_unlock(&g_mu);
    if (ratio > 0.0f && (was < 0.0f || was != ratio) && atomic_load(&g_gate) == 1)
        wnc_log(TAG, "the display's highest HDR/SDR ratio is %.2f%s", ratio,
                   ratio <= 1.01f ? " - it reports no HDR boost at all: no app can raise HDR highlights above SDR white here"
                                  : " (the most HDR headroom it can give)");
}

float wnc_color_screen_headroom(char *why, size_t n) {
    const int64_t t = atomic_load(&g_screen_hr_ns);
    if (!t || now_ns() - t > 1500000000LL) { if (why && n) why[0] = 0; return 0.0f; }
    if (why && n) {
        pthread_mutex_lock(&g_mu);
        snprintf(why, n, "%s", g_screen_hr_why);
        pthread_mutex_unlock(&g_mu);
    }
    return atomic_load(&g_screen_hr_x1000) / 1000.0f;
}

int wnc_color_last_frame_age_ms(void) {
    int64_t t = atomic_load(&g_last_frame_ns);
    if (!t) return -1;
    int64_t age = (now_ns() - t) / 1000000LL;
    return age > 0x7fffffff ? 0x7fffffff : (int)age;
}

/* The one-line answer a tester quotes. Caller holds g_mu. */
static void verdict_locked(char *out, size_t size) {
    int gate = atomic_load(&g_gate);
    if (gate < 0) { snprintf(out, size, "not decided yet (the compositor has not started)"); return; }
    if (gate == 0) { snprintf(out, size, "no, because %s", g_gate_why); return; }
    if (g_hdr.layer_frames) {
        const char *who = g_hdr.applied_who[0] ? g_hdr.applied_who : "the game";
        /* Only the paths that carried frames, so the ratio, the share and the device evidence still fit
         * the log line. */
        char paths[200] = "";
        size_t pp = 0;
        const uint64_t zc_other = g_hdr.layer_frames - g_hdr.layer_10bit - g_hdr.layer_copy8 - g_hdr.composed -
                                  g_hdr.swapchain;
        const struct { uint64_t n; const char *what; } path[] = {
            {g_hdr.layer_10bit, "10-bit zero-copy"}, {zc_other, "zero-copy"}, {g_hdr.layer_copy8, "8-bit layer copy"},
            {g_hdr.composed, "composed"}, {g_hdr.swapchain, "HDR10 swapchain"}};
        for (unsigned i = 0; i < sizeof(path) / sizeof(path[0]); i++)
            if (path[i].n && path[i].n <= g_hdr.layer_frames && pp < sizeof(paths) - 40)
                pp += (size_t)snprintf(paths + pp, sizeof(paths) - pp, "%s%llu %s", pp ? ", " : "",
                                       (unsigned long long)path[i].n, path[i].what);
        if (g_hdr.tonemapped && pp < sizeof(paths) - 60)
            pp += (size_t)snprintf(paths + pp, sizeof(paths) - pp, "; %llu more tone-mapped (%llu with HDR output off)",
                                   (unsigned long long)g_hdr.tonemapped, (unsigned long long)g_hdr.tm_off);
        char frames[340];
        snprintf(frames, sizeof(frames), "%llu frames of %s shown as BT2020_PQ (%s)",
                 (unsigned long long)g_hdr.layer_frames, who, paths);
        char env[128] = "", causes[256] = "";
        if (g_env.known) env_text_locked(env, sizeof(env));
        /* Brief: the verdict has to fit one log line with everything else in it. */
        if (g_hdr.nohead_said) nohead_causes_locked(causes, sizeof(causes), 1);
        /* Only readings taken while HDR frames were on screen count: the ratio says what the display did
         * with THEM, not with whatever else was up at another moment. And the share of that time with any
         * headroom, plus where it stands now: a ratio that rose once and then fell to 1.00 for minutes is
         * not the same answer. */
        char share[384] = "";
        const long long live_s = (long long)(g_hdr.live_ns / 1000000000LL);
        if (g_hdr.live_ns >= 1000000000LL)
            snprintf(share, sizeof(share), "; headroom above 1.00 for %d%% of the HDR time (%lld of %lld s), now %.2f%s%s"
                     "%s%s%s",
                     (int)(100.0 * (double)g_hdr.headroom_ns / (double)g_hdr.live_ns + 0.5),
                     (long long)(g_hdr.headroom_ns / 1000000000LL), live_s, g_hdr.ratio_last,
                     g_hdr.nohead_said ? " - no headroom now: " : "", g_hdr.nohead_said ? causes : "",
                     env[0] ? " [" : "", env, env[0] ? "]" : "");
        /* The display's own ceiling (Android 16+) and what was asked for, where known. */
        char ceil[80] = "", req[48] = "";
        if (g_req.highest_ratio > 0.0f) snprintf(ceil, sizeof(ceil), ", highest possible %.2f", g_req.highest_ratio);
        if (g_env.requested > 0.0f) snprintf(req, sizeof(req), ", %.1fx requested", g_env.requested);
        else if (g_env.requested < 0.0f) snprintf(req, sizeof(req), ", no request possible below Android 15");
        if (g_hdr.ratio_live_n && g_hdr.ratio_live_max > 1.01f)
            snprintf(out, size, "yes - %s; the display's HDR/SDR ratio rose to %.2f while they were on screen "
                     "(1.00 = SDR only%s%s)%s", frames, g_hdr.ratio_live_max, g_hdr.nohead_said ? "" : ceil,
                     g_hdr.nohead_said ? "" : req, share);
        else if (g_hdr.ratio_live_n)
            snprintf(out, size, "tagged but NOT confirmed - %s, but the display's HDR/SDR ratio stayed at %.2f while they "
                     "were on screen (%s%s%s): Android gave them no HDR headroom - %s%s%s%s", frames,
                     g_hdr.ratio_live_max, g_req.highest_ratio > 0.0f ? "" : "highest ratio not reported",
                     ceil[0] ? ceil + 2 : "", g_hdr.nohead_said ? "" : req, g_hdr.nohead_said ? causes : "a screenshot or "
                     "screen recording? heat? brightness at maximum (manual)? HDR off for this display?",
                     env[0] ? " [" : "", env, env[0] ? "]" : "");
        else if (g_hdr.ratio_n)
            snprintf(out, size, "tagged, not measured - %s, but no HDR/SDR ratio reading was taken while they were on "
                     "screen (highest reading otherwise %.2f)", frames, g_hdr.ratio_max);
        else
            snprintf(out, size, "yes by the tag only - %s (this display reports no HDR/SDR ratio to confirm it)", frames);
        return;
    }
    if (g_hdr.applied || g_hdr.descs) {
        if (g_hdr.tonemapped && g_hdr.tm_off == g_hdr.tonemapped)
            snprintf(out, size, "no - HDR output was switched off in the drawer whenever %s showed HDR: its %llu HDR "
                     "frames were shown tone-mapped to SDR - correct colours, no HDR brightness (switch it on to see HDR)",
                     g_hdr.applied_who[0] ? g_hdr.applied_who : "the game", (unsigned long long)g_hdr.tonemapped);
        else if (g_hdr.tonemapped)
            snprintf(out, size, "no - %s's HDR frames were shown tone-mapped to SDR (%llu frames: %llu with frame "
                     "generation through a screen surface that offers no HDR10 swapchain, %llu with HDR output switched "
                     "off in the drawer) - correct colours, no HDR brightness",
                     g_hdr.applied_who[0] ? g_hdr.applied_who : "the game", (unsigned long long)g_hdr.tonemapped,
                     (unsigned long long)(g_hdr.tonemapped - g_hdr.tm_off), (unsigned long long)g_hdr.tm_off);
        else if (g_hdr.copy_frames)
            snprintf(out, size, "no, because %s asked for HDR but all %llu of its HDR frames went through the "
                     "compositor's 8-bit SDR copy (%s) and were shown without tone mapping",
                     g_hdr.applied_who[0] ? g_hdr.applied_who : "the game", (unsigned long long)g_hdr.copy_frames,
                     g_hdr.copy_reason[0] ? g_hdr.copy_reason : "no display layer");
        else
            snprintf(out, size, "no, because %s made an HDR image description but no frame of it was ever shown "
                     "(the swapchain may have failed after that - see the DXVK log)",
                     g_hdr.applied_who[0] ? g_hdr.applied_who : "a program");
        return;
    }
    snprintf(out, size, "no, because no program asked for HDR: the colour manager was offered but nothing set an HDR "
             "image description (is DXVK_HDR=1 set, and is HDR switched on in the game's own display options?)");
}

/* Log the verdict when it changed (force = log it even if not). */
static void log_verdict(int force) {
    char v[512];
    pthread_mutex_lock(&g_mu);
    verdict_locked(v, sizeof(v));
    int changed = strcmp(v, g_hdr.verdict) != 0;
    if (changed) snprintf(g_hdr.verdict, sizeof(g_hdr.verdict), "%s", v);
    pthread_mutex_unlock(&g_mu);
    if (changed || force) wnc_log(TAG, "HDR on screen: %s", v);
}

void wnc_color_frame_shown(const struct wnc_color *c, int path, uint32_t ahb_format) {
    if (!c || !c->dataspace) return;
    int first8 = 0;
    pthread_mutex_lock(&g_mu);
    switch (path) {
    case WNC_HDR_ZERO_COPY:
        if (ahb_format == 0x2b) g_hdr.layer_10bit++;
        g_hdr.win_zc++;
        snprintf(g_hdr.layer_fmt, sizeof(g_hdr.layer_fmt), "%s", wnc_ahb_format_name(ahb_format));
        break;
    case WNC_HDR_LAYER_COPY:
        if (!g_hdr.layer_copy8) first8 = 1;
        g_hdr.layer_copy8++; g_hdr.win_copy8++;
        snprintf(g_hdr.layer_fmt, sizeof(g_hdr.layer_fmt), "RGBA8888 layer copy");
        break;
    case WNC_HDR_COMPOSED:
        g_hdr.composed++; g_hdr.win_composed++;
        snprintf(g_hdr.layer_fmt, sizeof(g_hdr.layer_fmt), "composed %s", wnc_ahb_format_name(ahb_format));
        break;
    case WNC_HDR_SWAPCHAIN:
        g_hdr.swapchain++; g_hdr.win_swapchain++;
        snprintf(g_hdr.layer_fmt, sizeof(g_hdr.layer_fmt), "HDR10 swapchain");
        atomic_store(&g_screen_hr_ns, now_ns());
        break;
    default: /* WNC_HDR_TONEMAPPED: shown, but not as HDR */
        g_hdr.tonemapped++; g_hdr.win_tonemapped++;
        if (!atomic_load(&g_output_on)) g_hdr.tm_off++;
        pthread_mutex_unlock(&g_mu);
        atomic_store(&g_last_tm_ns, now_ns());
        return;
    }
    g_hdr.layer_frames++;
    g_hdr.win_layer++;
    pthread_mutex_unlock(&g_mu);
    atomic_store(&g_last_frame_ns, now_ns());
    if (path == WNC_HDR_SWAPCHAIN) {
        /* The screen surface's headroom request (the app applies it): worked out again when the
         * description changes, else once a second. */
        static uint32_t ident;
        static int64_t calc_ns;
        const int64_t t = now_ns();
        if (c->identity != ident || t - calc_ns > 1000000000LL) {
            char why[200];
            const float r = wnc_color_desired_headroom(c, why, sizeof(why));
            ident = c->identity; calc_ns = t;
            pthread_mutex_lock(&g_mu);
            snprintf(g_screen_hr_why, sizeof(g_screen_hr_why), "%s", why);
            pthread_mutex_unlock(&g_mu);
            atomic_store(&g_screen_hr_x1000, (int)(r * 1000.0f + 0.5f));
        }
    }
    if (first8)
        wnc_log(TAG, "HDR frames are reaching the display layer through the compositor's 8-bit layer copy (the game's "
                   "buffer is not a gralloc buffer this frame): colours stay correct (the frame keeps its BT2020_PQ tag), "
                   "precision drops to 8 bits (banding possible)");
}

void wnc_color_frame_copied(const char *who, const char *reason) {
    int first = 0;
    pthread_mutex_lock(&g_mu);
    g_hdr.copy_frames++;
    g_hdr.win_copy++;
    if (reason && strcmp(reason, g_hdr.copy_reason)) {
        snprintf(g_hdr.copy_reason, sizeof(g_hdr.copy_reason), "%s", reason);
        first = 1;
    }
    pthread_mutex_unlock(&g_mu);
    if (first)
        wnc_log(TAG, "HDR frames of %s go through the compositor's 8-bit SDR copy now, because %s: shown WITHOUT "
                   "tone mapping (washed out) until that changes", who ? who : "a window", reason ? reason : "?");
}

void wnc_color_ratio_sample(float ratio, int listener) {
    if (atomic_load(&g_gate) != 1) return;
    int64_t t = now_ns();
    int age = wnc_color_last_frame_age_ms();
    int live = age >= 0 && age < 1500;
    int log_it = 0, periodic = 0, nohead_now = 0, head_back = 0;
    float was;
    char env[128] = "", causes[256] = "";
    pthread_mutex_lock(&g_mu);
    was = g_hdr.ratio_logged;
    if (ratio > 0.0f) {
        /* Time with HDR frames on screen, credited to the reading that stood over it. */
        if (g_hdr.prev_sample_ns && g_hdr.prev_live) {
            int64_t dt = t - g_hdr.prev_sample_ns;
            if (dt > 0 && dt < 3000000000LL) {
                g_hdr.live_ns += dt;
                if (g_hdr.prev_ratio > 1.01f) g_hdr.headroom_ns += dt;
            }
        }
        g_hdr.prev_sample_ns = t; g_hdr.prev_live = live; g_hdr.prev_ratio = ratio;
        if (live && ratio <= 1.01f) {
            if (!g_hdr.nohead_since_ns) g_hdr.nohead_since_ns = t;
            if (!g_hdr.nohead_said && t - g_hdr.nohead_since_ns >= 5000000000LL) g_hdr.nohead_said = nohead_now = 1;
        } else {
            if (live && g_hdr.nohead_said) head_back = 1;
            g_hdr.nohead_since_ns = 0; g_hdr.nohead_said = 0;
        }
        if (!g_hdr.ratio_n || ratio < g_hdr.ratio_min) g_hdr.ratio_min = ratio;
        if (!g_hdr.ratio_n || ratio > g_hdr.ratio_max) g_hdr.ratio_max = ratio;
        if (!g_hdr.win_ratio_n || ratio < g_hdr.win_ratio_min) g_hdr.win_ratio_min = ratio;
        if (!g_hdr.win_ratio_n || ratio > g_hdr.win_ratio_max) g_hdr.win_ratio_max = ratio;
        g_hdr.ratio_n++; g_hdr.win_ratio_n++;
        g_hdr.ratio_last = ratio;
        if (live) {
            if (!g_hdr.ratio_live_n || ratio > g_hdr.ratio_live_max) g_hdr.ratio_live_max = ratio;
            g_hdr.ratio_live_n++;
        }
        /* A change is logged at once (at most 4 lines a second); while HDR frames are on screen the
         * steady value is restated every 5 s, so a tester's log shows it holding, not just moving. */
        float moved = ratio - g_hdr.ratio_logged;
        if (moved < 0.0f) moved = -moved;
        if ((g_hdr.ratio_n == 1 || moved >= 0.02f) && t - g_hdr.ratio_logged_ns > 250000000LL)
            log_it = 1;
        else if (live && t - g_hdr.ratio_periodic_ns > 5000000000LL)
            log_it = periodic = 1;
        if (log_it) { g_hdr.ratio_logged = ratio; g_hdr.ratio_logged_ns = t; g_hdr.ratio_periodic_ns = t; }
        if (nohead_now || (periodic && live && ratio <= 1.01f)) {
            env_text_locked(env, sizeof(env));
            nohead_causes_locked(causes, sizeof(causes), 0);
        }
    }
    pthread_mutex_unlock(&g_mu);
    if (nohead_now)
        wnc_log(TAG, "no HDR headroom for 5 s while HDR frames are on screen (display HDR/SDR ratio %.2f): %s - Android "
                   "drops HDR headroom while the screen is recorded and when the device is hot, and some phones never "
                   "boost HDR for apps; HDR highlights look no brighter until that ends [%s]", ratio, causes, env);
    if (head_back)
        wnc_log(TAG, "HDR headroom is back: display HDR/SDR ratio %.2f with HDR frames on screen", ratio);
    if (!log_it) return;
    char when[64];
    if (age < 0) snprintf(when, sizeof(when), "none yet this session");
    else snprintf(when, sizeof(when), "%s, last one %d ms ago", live ? "yes" : "no", age);
    if (periodic)
        wnc_log(TAG, "display HDR/SDR ratio %.2f (steady; HDR frames on screen: %s)%s%s%s%s%s", ratio, when,
                   live && ratio <= 1.01f ? " - no HDR headroom: " : "", live && ratio <= 1.01f ? causes : "",
                   env[0] ? " [" : "", env, env[0] ? "]" : "");
    else if (was > 0.0f)
        wnc_log(TAG, "display HDR/SDR ratio %.2f (was %.2f; HDR frames on screen: %s) [%s]", ratio, was, when,
                   listener ? "display listener" : "sampler");
    else
        wnc_log(TAG, "display HDR/SDR ratio %.2f (first reading; HDR frames on screen: %s) [%s]", ratio, when,
                   listener ? "display listener" : "sampler");
}

void wnc_color_stats_tick(void) {
    if (atomic_load(&g_gate) != 1) return;
    char line[512];
    int any;
    pthread_mutex_lock(&g_mu);
    any = g_hdr.win_layer || g_hdr.win_copy || g_hdr.win_tonemapped; /* the ratio alone is logged when it moves */
    if (any) {
        char ratio[256] = "no HDR/SDR ratio reading", env[128] = "", causes[256] = "";
        if (g_env.known) env_text_locked(env, sizeof(env));
        if (g_hdr.nohead_said) nohead_causes_locked(causes, sizeof(causes), 1);
        if (g_hdr.win_ratio_n)
            snprintf(ratio, sizeof(ratio), "display HDR/SDR ratio %.2f-%.2f (now %.2f)%s%s", g_hdr.win_ratio_min,
                     g_hdr.win_ratio_max, g_hdr.ratio_last, g_hdr.nohead_said ? " - NO headroom: " : "",
                     g_hdr.nohead_said ? causes : "");
        snprintf(line, sizeof(line), "HDR last 10 s: %u frames shown as BT2020_PQ (zero-copy %u, 8-bit layer copy %u, "
                 "composed picture %u, HDR10 swapchain %u; last buffer %s) | %u tone-mapped to SDR | %u washed-out "
                 "copies | %s%s%s",
                 g_hdr.win_layer, g_hdr.win_zc, g_hdr.win_copy8, g_hdr.win_composed, g_hdr.win_swapchain,
                 g_hdr.layer_fmt[0] ? g_hdr.layer_fmt : "-", g_hdr.win_tonemapped, g_hdr.win_copy, ratio,
                 env[0] ? " | " : "", env);
    }
    g_hdr.win_layer = g_hdr.win_copy = g_hdr.win_copy8 = 0;
    g_hdr.win_zc = g_hdr.win_composed = g_hdr.win_swapchain = g_hdr.win_tonemapped = 0;
    g_hdr.win_ratio_n = 0;
    pthread_mutex_unlock(&g_mu);
    if (any) wnc_log(TAG, "%s", line);
    log_verdict(0);
}

int wnc_color_hdr_state(void) {
    if (atomic_load(&g_gate) != 1) return 0;
    int age = wnc_color_last_frame_age_ms();
    if (age < 0 || age >= 1500) return 0;
    pthread_mutex_lock(&g_mu);
    int confirmed = !g_hdr.ratio_n || g_hdr.ratio_last > 1.01f; /* no ratio on this display: the tag is all there is */
    int nohead = g_hdr.nohead_said;
    pthread_mutex_unlock(&g_mu);
    return confirmed ? 1 : nohead ? 2 : 0;
}

int wnc_color_hdr_on_screen(void) { return wnc_color_hdr_state() == 1; }

static _Atomic int g_sdr_white_x100 = 20300;
void wnc_color_set_sdr_white(float nits) {
    if (!(nits >= 10.0f && nits <= 2000.0f)) return;
    atomic_store(&g_sdr_white_x100, (int)(nits * 100.0f + 0.5f));
    wnc_log(TAG, "SDR content inside an HDR picture is placed at %.0f nits (BANNER_WAYLAND_HDR_SDR_NITS)", nits);
}
float wnc_color_sdr_white(void) { return atomic_load(&g_sdr_white_x100) / 100.0f; }

int wnc_color_requested(void) {
    pthread_mutex_lock(&g_mu);
    int m = g_req.mode;
    pthread_mutex_unlock(&g_mu);
    return m != 0;
}

int wnc_color_output(void) { return atomic_load(&g_output_on); }

void wnc_color_env_sample(int thermal, float headroom, int brightness, int bmode) {
    if (atomic_load(&g_gate) != 1) return;  /* evidence for the HDR lines only */
    char msg[2][200];
    int nmsg = 0;
    pthread_mutex_lock(&g_mu);
    const int first = !g_env.known;
    if (!first && thermal != g_env.thermal) {
        char hr[32] = "";
        if (headroom >= 0.0f) snprintf(hr, sizeof(hr), ", headroom %.2f", headroom);
        snprintf(msg[nmsg++], sizeof(msg[0]), "thermal status now %s (was %s%s)%s", thermal_name(thermal),
                 thermal_name(g_env.thermal), hr, thermal >= 2 ? " - Android lowers HDR headroom when the device is hot" : "");
    }
    if (!first && (brightness != g_env.brightness || bmode != g_env.bmode))
        snprintf(msg[nmsg++], sizeof(msg[0]), "screen brightness now %d/255 %s (was %d/255 %s)", brightness,
                 bmode == 1 ? "auto" : bmode == 0 ? "manual" : "?", g_env.brightness,
                 g_env.bmode == 1 ? "auto" : g_env.bmode == 0 ? "manual" : "?");
    g_env.thermal = thermal;
    g_env.headroom = headroom;
    g_env.brightness = brightness;
    g_env.bmode = bmode;
    g_env.known = 1;
    if (first) {
        char e[128];
        env_text_locked(e, sizeof(e));
        snprintf(msg[nmsg++], sizeof(msg[0]), "device evidence beside the HDR/SDR headroom: %s (thermal status and "
                 "brightness changes are logged as they happen)", e);
    }
    pthread_mutex_unlock(&g_mu);
    for (int i = 0; i < nmsg; i++) wnc_log(TAG, "%s", msg[i]);
}

int wnc_color_tonemapped_on_screen(void) {
    if (atomic_load(&g_gate) != 1) return 0;
    int64_t t = atomic_load(&g_last_tm_ns);
    return t && now_ns() - t < 1500000000LL;
}

void wnc_color_set_output(int on) {
    on = on ? 1 : 0;
    if (atomic_load(&g_gate) != 1) {
        /* The drawer only shows the switch in sessions whose gate is open; say so if it ever gets here. */
        wnc_log(TAG, "HDR output switch (%s) ignored: HDR is not open in this session", on ? "on" : "off");
        return;
    }
    if (atomic_exchange(&g_output_on, on) == on) return;
    char who[160];
    int age = wnc_color_last_frame_age_ms(), tm = wnc_color_tonemapped_on_screen();
    pthread_mutex_lock(&g_mu);
    if (!on) g_hdr.output_offs++;
    snprintf(who, sizeof(who), "%s", g_hdr.applied_who[0] ? g_hdr.applied_who : "no HDR program yet");
    pthread_mutex_unlock(&g_mu);
    if (on)
        wnc_log(TAG, "HDR output switched ON in the drawer: HDR frames go to the display as HDR again from the next "
                   "frame (%s; %s)", who, tm ? "they were being tone-mapped to SDR until now" : "none were on screen just now");
    else
        wnc_log(TAG, "HDR output switched OFF in the drawer: HDR frames are tone-mapped to SDR from the next frame "
                   "(%s; %s). The game is not told - it keeps rendering HDR; its own HDR setting and DXVK_HDR are "
                   "untouched", who, age >= 0 && age < 1500 ? "HDR frames were on screen" : "no HDR frames on screen just now");
    wnc_request_redraw(); /* a paused game commits nothing: show the change now */
}

static _Atomic int g_session_ended;

void wnc_color_session_end(void) {
    if (atomic_exchange(&g_session_ended, 1)) return;
    if (atomic_load(&g_gate) < 0) return;
    pthread_mutex_lock(&g_mu);
    int asked = g_req.mode != 0;
    pthread_mutex_unlock(&g_mu);
    if (asked) log_verdict(1); /* nobody asked for HDR: no summary to write */
}

/* ---------------------------------------------------------------- image descriptions */

struct cm_desc {
    int refs;                       /* protocol objects + surfaces (pending / current) */
    struct wnc_color c;
};

static uint32_t g_next_identity = 1;

static struct cm_desc *desc_ref(struct cm_desc *d) { if (d) d->refs++; return d; }
static void desc_unref(struct cm_desc *d) { if (d && --d->refs <= 0) free(d); }

/* The parametric creator's collected properties. 0 / has_* = 0 means unset. */
struct cm_params {
    uint32_t tf, primaries;
    int has_mprim; int32_t mprim[8];
    int has_mlum; uint32_t mlum_min, mlum_max;   /* min is cd/m² * 10000, max is cd/m² */
    int has_cll; uint32_t cll;
    int has_fall; uint32_t fall;
};

static void image_description_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void image_description_get_information(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_V1_ERROR_NO_INFORMATION,
                           "get_information is not allowed on this image description");
}
static const struct wp_image_description_v1_interface image_description_impl = {
    .destroy = image_description_destroy_req,
    .get_information = image_description_get_information,
};
static void image_description_resource_destroy(struct wl_resource *r) {
    desc_unref(wl_resource_get_user_data(r));
}

/* An image description object that will never be ready (output / preferred descriptions are not
 * implemented in round 1; nothing we serve asks for them). */
static void make_failed_description(struct wl_client *c, struct wl_resource *parent, uint32_t id, const char *what) {
    struct wl_resource *r = wl_resource_create(c, &wp_image_description_v1_interface, wl_resource_get_version(parent), id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &image_description_impl, NULL, image_description_resource_destroy);
    wp_image_description_v1_send_failed(r, WP_IMAGE_DESCRIPTION_V1_CAUSE_OPERATING_SYSTEM,
                                        "not implemented by this compositor");
    wnc_log(TAG, "%s asked for %s: not implemented in this build, answered 'failed'", wnc_client_name(c), what);
}

static void params_create(struct wl_client *client, struct wl_resource *r, uint32_t id) {
    struct cm_params *p = wl_resource_get_user_data(r);
    if (!p->tf || !p->primaries) {
        wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_INCOMPLETE_SET,
                               "transfer function and primaries must both be set");
        return;
    }
    struct cm_desc *d = calloc(1, sizeof(*d));
    if (!d) { wl_client_post_no_memory(client); return; }
    struct wnc_color *c = &d->c;
    c->identity = g_next_identity++;
    if (!g_next_identity) g_next_identity = 1;
    c->primaries = p->primaries;
    c->tf = p->tf;
    if (p->primaries == WP_COLOR_MANAGER_V1_PRIMARIES_BT2020 && p->tf == WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ)
        c->dataspace = WNC_ADATASPACE_BT2020_PQ;
    else if (p->primaries == WP_COLOR_MANAGER_V1_PRIMARIES_BT2020 && p->tf == WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_HLG)
        c->dataspace = WNC_ADATASPACE_BT2020_HLG;

    /* HDR metadata, kept only when it makes sense. The PQ defaults stand in for what was not given
     * (0.005 - 10000 cd/m², the target volume = the primary one); CTA-861.3 treats 0 as unknown. */
    char dropped[160] = "";
    float mlum_min = 0.005f, mlum_max = 10000.0f;
    if (p->has_mlum) {
        float mn = p->mlum_min / 10000.0f, mx = (float)p->mlum_max;
        if (mx > mn && mx > 0.0f) { mlum_min = mn; mlum_max = mx; }
        else snprintf(dropped + strlen(dropped), sizeof(dropped) - strlen(dropped), " mastering luminance %.4f-%.0f (max <= min),", mn, mx);
    }
    if (p->has_mprim || p->has_mlum) {
        c->has_st2086 = 1;
        if (p->has_mprim) {
            c->red[0] = p->mprim[0] / 1e6f; c->red[1] = p->mprim[1] / 1e6f;
            c->green[0] = p->mprim[2] / 1e6f; c->green[1] = p->mprim[3] / 1e6f;
            c->blue[0] = p->mprim[4] / 1e6f; c->blue[1] = p->mprim[5] / 1e6f;
            c->white[0] = p->mprim[6] / 1e6f; c->white[1] = p->mprim[7] / 1e6f;
        } else { /* the container's own primaries (BT.2020, D65) */
            c->red[0] = 0.708f; c->red[1] = 0.292f; c->green[0] = 0.170f; c->green[1] = 0.797f;
            c->blue[0] = 0.131f; c->blue[1] = 0.046f; c->white[0] = 0.3127f; c->white[1] = 0.3290f;
        }
        c->min_lum = mlum_min;
        c->max_lum = mlum_max;
    }
    float cll = p->has_cll ? (float)p->cll : 0.0f, fall = p->has_fall ? (float)p->fall : 0.0f;
    if (cll > 0.0f && (cll <= mlum_min || cll > mlum_max)) {
        snprintf(dropped + strlen(dropped), sizeof(dropped) - strlen(dropped), " max CLL %.0f (outside %.4f-%.0f),", cll, mlum_min, mlum_max);
        cll = 0.0f;
    }
    if (fall > 0.0f && (fall <= mlum_min || fall > mlum_max || (cll > 0.0f && fall > cll))) {
        snprintf(dropped + strlen(dropped), sizeof(dropped) - strlen(dropped), " max FALL %.0f (outside the range or above max CLL),", fall);
        fall = 0.0f;
    }
    if (cll > 0.0f || fall > 0.0f) { c->has_cta861 = 1; c->max_cll = cll; c->max_fall = fall; }

    int n = snprintf(c->text, sizeof(c->text), "%s, %s", primaries_name(c->primaries), tf_name(c->tf));
    if (c->has_st2086 && n < (int)sizeof(c->text))
        n += snprintf(c->text + n, sizeof(c->text) - (size_t)n,
                      "; mastering R %.4f,%.4f G %.4f,%.4f B %.4f,%.4f W %.4f,%.4f, %.4f-%.0f nits%s",
                      c->red[0], c->red[1], c->green[0], c->green[1], c->blue[0], c->blue[1], c->white[0], c->white[1],
                      c->min_lum, c->max_lum, p->has_mprim ? "" : " (container primaries)");
    if (n < (int)sizeof(c->text))
        n += snprintf(c->text + n, sizeof(c->text) - (size_t)n, "; max CLL %s%.0f, max FALL %s%.0f",
                      c->max_cll > 0.0f ? "" : "unknown ", c->max_cll, c->max_fall > 0.0f ? "" : "unknown ", c->max_fall);
    if (!c->has_st2086 && !c->has_cta861 && n < (int)sizeof(c->text))
        snprintf(c->text + n, sizeof(c->text) - (size_t)n, " (no HDR metadata: the game has not called vkSetHdrMetadataEXT)");

    struct wl_resource *out = wl_resource_create(client, &wp_image_description_v1_interface,
                                                 wl_resource_get_version(r), id);
    if (!out) { free(d); wl_client_post_no_memory(client); return; }
    d->refs = 1;
    wl_resource_set_implementation(out, &image_description_impl, d, image_description_resource_destroy);
    /* Sent from inside the request: Mesa blocks its swapchain on this event (it dispatches its queue
     * until ready/failed arrives), and the event loop flushes it before it sleeps again. */
    wp_image_description_v1_send_ready(out, c->identity);
    if (c->dataspace) {
        pthread_mutex_lock(&g_mu);
        g_hdr.descs++;
        pthread_mutex_unlock(&g_mu);
    }
    wnc_log(TAG, "image description #%u from %s: %s -> ready (on a display layer: dataspace %s)", c->identity,
               wnc_client_name(client), c->text, dataspace_name(c->dataspace));
    if (dropped[0]) {
        size_t l = strlen(dropped);
        if (l && dropped[l - 1] == ',') dropped[l - 1] = 0;
        wnc_log(TAG, "image description #%u: dropped HDR metadata that breaks the protocol's rules:%s "
                   "(Android gets the rest)", c->identity, dropped);
    }
    wl_resource_destroy(r); /* create is the creator's destructor */
}

#define PARAMS_ONCE(field, what)                                                                      \
    do {                                                                                              \
        if (field) {                                                                                  \
            wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_ALREADY_SET,       \
                                   what " already set");                                             \
            return;                                                                                   \
        }                                                                                             \
    } while (0)

static void params_set_tf_named(struct wl_client *c, struct wl_resource *r, uint32_t tf) {
    struct cm_params *p = wl_resource_get_user_data(r);
    PARAMS_ONCE(p->tf, "transfer function");
    if (tf != WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ) {
        wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_INVALID_TF,
                               "transfer function %u was not advertised", tf);
        return;
    }
    p->tf = tf;
}
static void params_set_tf_power(struct wl_client *c, struct wl_resource *r, uint32_t eexp) {
    wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_UNSUPPORTED_FEATURE,
                           "set_tf_power is not supported");
}
static void params_set_primaries_named(struct wl_client *c, struct wl_resource *r, uint32_t primaries) {
    struct cm_params *p = wl_resource_get_user_data(r);
    PARAMS_ONCE(p->primaries, "primaries");
    if (primaries != WP_COLOR_MANAGER_V1_PRIMARIES_BT2020) {
        wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_INVALID_PRIMARIES_NAMED,
                               "primaries %u were not advertised", primaries);
        return;
    }
    p->primaries = primaries;
}
static void params_set_primaries(struct wl_client *c, struct wl_resource *r, int32_t rx, int32_t ry, int32_t gx,
                                 int32_t gy, int32_t bx, int32_t by, int32_t wx, int32_t wy) {
    wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_UNSUPPORTED_FEATURE,
                           "set_primaries is not supported");
}
static void params_set_luminances(struct wl_client *c, struct wl_resource *r, uint32_t mn, uint32_t mx, uint32_t ref) {
    wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_UNSUPPORTED_FEATURE,
                           "set_luminances is not supported");
}
static void params_set_mastering_display_primaries(struct wl_client *c, struct wl_resource *r, int32_t rx, int32_t ry,
                                                   int32_t gx, int32_t gy, int32_t bx, int32_t by, int32_t wx, int32_t wy) {
    struct cm_params *p = wl_resource_get_user_data(r);
    PARAMS_ONCE(p->has_mprim, "mastering display primaries");
    p->has_mprim = 1;
    p->mprim[0] = rx; p->mprim[1] = ry; p->mprim[2] = gx; p->mprim[3] = gy;
    p->mprim[4] = bx; p->mprim[5] = by; p->mprim[6] = wx; p->mprim[7] = wy;
}
static void params_set_mastering_luminance(struct wl_client *c, struct wl_resource *r, uint32_t mn, uint32_t mx) {
    struct cm_params *p = wl_resource_get_user_data(r);
    PARAMS_ONCE(p->has_mlum, "mastering luminance");
    p->has_mlum = 1; p->mlum_min = mn; p->mlum_max = mx;
}
static void params_set_max_cll(struct wl_client *c, struct wl_resource *r, uint32_t v) {
    struct cm_params *p = wl_resource_get_user_data(r);
    p->has_cll = v != 0; p->cll = v;
}
static void params_set_max_fall(struct wl_client *c, struct wl_resource *r, uint32_t v) {
    struct cm_params *p = wl_resource_get_user_data(r);
    p->has_fall = v != 0; p->fall = v;
}
static const struct wp_image_description_creator_params_v1_interface params_impl = {
    .create = params_create,
    .set_tf_named = params_set_tf_named,
    .set_tf_power = params_set_tf_power,
    .set_primaries_named = params_set_primaries_named,
    .set_primaries = params_set_primaries,
    .set_luminances = params_set_luminances,
    .set_mastering_display_primaries = params_set_mastering_display_primaries,
    .set_mastering_luminance = params_set_mastering_luminance,
    .set_max_cll = params_set_max_cll,
    .set_max_fall = params_set_max_fall,
};
static void params_resource_destroy(struct wl_resource *r) { free(wl_resource_get_user_data(r)); }

/* ---------------------------------------------------------------- per-surface state */

/* One per wl_surface that ever had a colour-management object. Lives until the wl_surface goes (a
 * swapchain rebuild destroys the object and makes a new one for the same surface). */
struct cm_surf {
    struct wl_resource *surface;        /* the wl_surface */
    struct wl_listener surface_destroy;
    struct wl_resource *owner;          /* the live wp_color_management_surface_v1, NULL = none */
    struct cm_desc *pending, *current;
    int pending_dirty;
};

static void cm_surface_destroyed(struct wl_listener *l, void *data);

static struct cm_surf *surf_of(struct wl_resource *surface) {
    if (!surface) return NULL;
    struct wl_listener *l = wl_resource_get_destroy_listener(surface, cm_surface_destroyed);
    if (!l) return NULL;
    struct cm_surf *cs = wl_container_of(l, cs, surface_destroy);
    return cs;
}

static void describe_surface(struct wl_resource *surface, char *out, size_t size) {
    struct surface *s = surface ? wl_resource_get_user_data(surface) : NULL;
    if (s) wnc_surface_describe(s, out, size);
    else snprintf(out, size, "a closed window");
}

static void cm_surface_destroyed(struct wl_listener *l, void *data) {
    struct cm_surf *cs = wl_container_of(l, cs, surface_destroy);
    wl_list_remove(&cs->surface_destroy.link);
    if (cs->owner) wl_resource_set_user_data(cs->owner, NULL); /* inert from now on */
    desc_unref(cs->pending);
    desc_unref(cs->current);
    free(cs);
}

static void cm_surface_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }

static void cm_surface_set_image_description(struct wl_client *c, struct wl_resource *r,
                                             struct wl_resource *desc_res, uint32_t intent) {
    struct cm_surf *cs = wl_resource_get_user_data(r);
    struct cm_desc *d = desc_res ? wl_resource_get_user_data(desc_res) : NULL;
    if (!cs) {
        wnc_log(TAG, "%s set an image description on a colour-management object whose surface is gone: ignored",
                   wnc_client_name(c));
        return;
    }
    if (!d) {
        wl_resource_post_error(r, WP_COLOR_MANAGEMENT_SURFACE_V1_ERROR_IMAGE_DESCRIPTION,
                               "the image description is not ready");
        return;
    }
    if (intent != WP_COLOR_MANAGER_V1_RENDER_INTENT_PERCEPTUAL) {
        wl_resource_post_error(r, WP_COLOR_MANAGEMENT_SURFACE_V1_ERROR_RENDER_INTENT,
                               "rendering intent %u was not advertised", intent);
        return;
    }
    desc_unref(cs->pending);
    cs->pending = desc_ref(d);
    cs->pending_dirty = 1;
}

static void cm_surface_unset_image_description(struct wl_client *c, struct wl_resource *r) {
    struct cm_surf *cs = wl_resource_get_user_data(r);
    if (!cs) return;
    desc_unref(cs->pending);
    cs->pending = NULL;
    cs->pending_dirty = 1;
}

static const struct wp_color_management_surface_v1_interface cm_surface_impl = {
    .destroy = cm_surface_destroy_req,
    .set_image_description = cm_surface_set_image_description,
    .unset_image_description = cm_surface_unset_image_description,
};

/* Destroying the object does what unset_image_description does (double-buffered, like it). */
static void cm_surface_resource_destroy(struct wl_resource *r) {
    struct cm_surf *cs = wl_resource_get_user_data(r);
    if (!cs || cs->owner != r) return;
    cs->owner = NULL;
    desc_unref(cs->pending);
    cs->pending = NULL;
    cs->pending_dirty = 1;
}

void wnc_color_commit(struct wl_resource *surface) {
    if (atomic_load(&g_gate) != 1) return;
    struct cm_surf *cs = surf_of(surface);
    if (!cs || !cs->pending_dirty) return;
    cs->pending_dirty = 0;
    if (cs->pending == cs->current) return;
    struct cm_desc *old = cs->current;
    cs->current = desc_ref(cs->pending);
    char who[160];
    describe_surface(surface, who, sizeof(who));
    if (cs->current) {
        wnc_log(TAG, "%s: image description #%u now applies to its frames (%s; perceptual intent)", who,
                   cs->current->c.identity, cs->current->c.text);
        if (cs->current->c.dataspace) {
            pthread_mutex_lock(&g_mu);
            g_hdr.applied++;
            snprintf(g_hdr.applied_who, sizeof(g_hdr.applied_who), "%s", who);
            pthread_mutex_unlock(&g_mu);
            if (!g_zero_copy)
                wnc_log(TAG, "%s: zero-copy presentation is OFF, so its HDR frames are composed into the HDR picture "
                           "(one extra pass per frame) - switch Zero-copy presentation on for the direct path", who);
        }
    } else if (old) {
        wnc_log(TAG, "%s: image description #%u removed - its frames are sRGB again", who, old->c.identity);
    }
    desc_unref(old);
}

const struct wnc_color *wnc_color_of(struct wl_resource *surface) {
    if (atomic_load(&g_gate) != 1) return NULL;
    struct cm_surf *cs = surf_of(surface);
    return cs && cs->current ? &cs->current->c : NULL;
}

/* ---------------------------------------------------------------- output + feedback (minimal) */

static void cm_output_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void cm_output_get_image_description(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    make_failed_description(c, r, id, "the output's image description");
}
static const struct wp_color_management_output_v1_interface cm_output_impl = {
    .destroy = cm_output_destroy_req,
    .get_image_description = cm_output_get_image_description,
};

static void cm_feedback_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void cm_feedback_get_preferred(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    make_failed_description(c, r, id, "a surface's preferred image description");
}
static const struct wp_color_management_surface_feedback_v1_interface cm_feedback_impl = {
    .destroy = cm_feedback_destroy_req,
    .get_preferred = cm_feedback_get_preferred,
    .get_preferred_parametric = cm_feedback_get_preferred,
};

/* ---------------------------------------------------------------- wp_color_manager_v1 */

static void cm_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }

static void cm_get_output(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *output) {
    struct wl_resource *o = wl_resource_create(c, &wp_color_management_output_v1_interface, wl_resource_get_version(r), id);
    if (!o) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(o, &cm_output_impl, NULL, NULL);
}

static void cm_get_surface(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surface) {
    struct wl_resource *o = wl_resource_create(c, &wp_color_management_surface_v1_interface, wl_resource_get_version(r), id);
    if (!o) { wl_client_post_no_memory(c); return; }
    struct cm_surf *cs = surf_of(surface);
    if (!cs) {
        cs = calloc(1, sizeof(*cs));
        if (!cs) { wl_resource_destroy(o); wl_client_post_no_memory(c); return; }
        cs->surface = surface;
        cs->surface_destroy.notify = cm_surface_destroyed;
        wl_resource_add_destroy_listener(surface, &cs->surface_destroy);
    }
    char who[160];
    describe_surface(surface, who, sizeof(who));
    if (cs->owner) {
        /* The protocol says surface_exists; a game would be disconnected for it. The newer object wins. */
        wnc_log(TAG, "%s: a second colour-management object for the same surface - the newer one takes over "
                   "(protocol error surface_exists not raised)", who);
        wl_resource_set_user_data(cs->owner, NULL);
    }
    cs->owner = o;
    wl_resource_set_implementation(o, &cm_surface_impl, cs, cm_surface_resource_destroy);
    wnc_log(TAG, "%s: colour-management surface created by %s (its Vulkan swapchain asks for a colour space "
               "other than sRGB)", who, wnc_client_name(c));
}

static void cm_get_surface_feedback(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surface) {
    struct wl_resource *o = wl_resource_create(c, &wp_color_management_surface_feedback_v1_interface,
                                               wl_resource_get_version(r), id);
    if (!o) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(o, &cm_feedback_impl, NULL, NULL);
}

static void cm_create_icc_creator(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    wl_resource_post_error(r, WP_COLOR_MANAGER_V1_ERROR_UNSUPPORTED_FEATURE, "ICC image descriptions are not supported");
}

static void cm_create_parametric_creator(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct cm_params *p = calloc(1, sizeof(*p));
    if (!p) { wl_client_post_no_memory(c); return; }
    struct wl_resource *o = wl_resource_create(c, &wp_image_description_creator_params_v1_interface,
                                               wl_resource_get_version(r), id);
    if (!o) { free(p); wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(o, &params_impl, p, params_resource_destroy);
}

static void cm_create_windows_scrgb(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    wl_resource_post_error(r, WP_COLOR_MANAGER_V1_ERROR_UNSUPPORTED_FEATURE, "windows_scrgb is not supported");
}

static const struct wp_color_manager_v1_interface cm_impl = {
    .destroy = cm_destroy_req,
    .get_output = cm_get_output,
    .get_surface = cm_get_surface,
    .get_surface_feedback = cm_get_surface_feedback,
    .create_icc_creator = cm_create_icc_creator,
    .create_parametric_creator = cm_create_parametric_creator,
    .create_windows_scrgb = cm_create_windows_scrgb,
};

static void bind_cm(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    static struct wl_client *last_named;
    struct wl_resource *r = wl_resource_create(c, &wp_color_manager_v1_interface, ver > 1 ? 1 : (int)ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &cm_impl, NULL, NULL);
    /* All at bind, then done: Mesa reads them in the registry roundtrip that follows the bind and
     * lists VK_COLOR_SPACE_HDR10_ST2084_EXT from exactly this pair (bt2020 + st2084_pq). Only what
     * the display layer can honour is offered. */
    wp_color_manager_v1_send_supported_intent(r, WP_COLOR_MANAGER_V1_RENDER_INTENT_PERCEPTUAL);
    wp_color_manager_v1_send_supported_feature(r, WP_COLOR_MANAGER_V1_FEATURE_PARAMETRIC);
    wp_color_manager_v1_send_supported_feature(r, WP_COLOR_MANAGER_V1_FEATURE_SET_MASTERING_DISPLAY_PRIMARIES);
    wp_color_manager_v1_send_supported_primaries_named(r, WP_COLOR_MANAGER_V1_PRIMARIES_BT2020);
    wp_color_manager_v1_send_supported_tf_named(r, WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ);
    wp_color_manager_v1_send_done(r);
    /* One line per program: Mesa binds again for every surface-format query. */
    if (last_named != c) {
        last_named = c;
        wnc_log(TAG, "%s bound wp_color_manager_v1 version %u (offered: perceptual intent; parametric descriptions "
                   "with mastering metadata; BT.2020 primaries; ST 2084 PQ) - its Vulkan driver can now list "
                   "VK_COLOR_SPACE_HDR10_ST2084_EXT", wnc_client_name(c), ver > 1 ? 1u : ver);
    }
}

void wnc_color_client_gone(struct wl_client *client) {
    if (atomic_load(&g_gate) != 1 || atomic_load(&g_session_ended)) return;
    /* A program that presented HDR has left: say where the session stands now, while it is fresh. */
    const char *name = wnc_client_name(client);
    int mine;
    pthread_mutex_lock(&g_mu);
    mine = g_hdr.applied_who[0] && name && strstr(g_hdr.applied_who, name) != NULL;
    pthread_mutex_unlock(&g_mu);
    if (mine) log_verdict(1);
}

/* ---------------------------------------------------------------- the gate */

void wnc_color_init(struct wl_display *display) {
    char why[320] = "";
    int mode, dxvk_hdr, zc_forced, known, hdr10, id, ratio_avail, api;
    char name[96], formats[96], source[64];
    float max_lum, max_avg, min_lum, ratio;
    pthread_mutex_lock(&g_mu);
    mode = g_req.mode; dxvk_hdr = g_req.dxvk_hdr; zc_forced = g_req.zero_copy_forced;
    known = g_req.display_known; hdr10 = g_req.hdr10; id = g_req.display_id;
    ratio_avail = g_req.ratio_available; api = g_req.api;
    snprintf(name, sizeof(name), "%s", g_req.display_name);
    snprintf(formats, sizeof(formats), "%s", g_req.formats);
    snprintf(source, sizeof(source), "%s", g_req.source);
    max_lum = g_req.max_lum; max_avg = g_req.max_avg; min_lum = g_req.min_lum; ratio = g_req.ratio;
    pthread_mutex_unlock(&g_mu);

    char disp[256];
    if (known)
        snprintf(disp, sizeof(disp), "\"%s\" (display %d, Android API %d) reports HDR types %s, peak %.0f nits, HDR/SDR "
                 "ratio %s", name, id, api, formats, max_lum, ratio_avail ? "available" : "not available");
    else
        snprintf(disp, sizeof(disp), "the app could not read the display's HDR capability");

    /* What asked for HDR, in the words the user knows it by: the editors' setting, or the env override. */
    char asked[128];
    if (mode == 2) snprintf(asked, sizeof(asked), "BANNER_WAYLAND_HDR=force (%s)", source);
    else if (strstr(source, "env")) snprintf(asked, sizeof(asked), "BANNER_WAYLAND_HDR=1 (%s)", source);
    else snprintf(asked, sizeof(asked), "HDR output is on (%s)", source);

    int layer_ok = sc_layer_can_tag_hdr();
    int ahb_ok = ahb_swapchain_advertised();
    char off_why[128];
    if (strstr(source, "env")) snprintf(off_why, sizeof(off_why), "BANNER_WAYLAND_HDR=0 in the %s overrides the setting", source);
    else snprintf(off_why, sizeof(off_why), "the HDR output setting is off");
    if (mode == 0)
        snprintf(why, sizeof(why), "HDR output is off (%s)", off_why);
    else if (mode == 1 && !known)
        snprintf(why, sizeof(why), "%s but %s", asked, disp);
    else if (mode == 1 && !hdr10)
        snprintf(why, sizeof(why), "%s but this display cannot show HDR10: %s", asked, disp);
    else if (!layer_ok)
        snprintf(why, sizeof(why), "this Android has no display layers with dataspace control "
                 "(ASurfaceControl + ASurfaceTransaction_setBufferDataSpace, Android 10+)");
    else if (!ahb_ok)
        snprintf(why, sizeof(why), "zero-copy presentation is unavailable here (banner_ahb_v1 not advertised), and an "
                 "HDR frame is only honest on the game's own display layer");

    if (why[0]) {
        snprintf(g_gate_why, sizeof(g_gate_why), "%s", why);
        atomic_store(&g_gate, 0);
        if (mode) {
            wnc_log(TAG, "HDR gate CLOSED: %s. Nothing is advertised (no wp_color_manager_v1, no 10-bit dma-buf "
                       "formats): games see an SDR display, exactly as without the switch", why);
            if (dxvk_hdr)
                wnc_log(TAG, "DXVK_HDR=1 is set: DXGI will still claim an HDR display that no swapchain can get here "
                           "(games that check fall back to SDR; some show washed-out colours) - remove it on this display");
            log_verdict(1);
        } else if (dxvk_hdr || (known && hdr10)) {
            /* Off, and silent unless it is worth a line: a display that could show HDR10, or a DXVK
             * switch that promises games an HDR display they cannot get. */
            wnc_log(TAG, "HDR output off for this session (%s)%s%s", off_why,
                       (known && hdr10) ? " - this display lists HDR10, so switching HDR output on for this game would "
                                          "offer it to games" : "",
                       dxvk_hdr ? " - but DXVK_HDR=1 is set, so DXGI claims an HDR display the game cannot get a "
                                  "swapchain for" : "");
        }
        return;
    }

    if (!wl_global_create(display, &wp_color_manager_v1_interface, 1, NULL, bind_cm)) {
        snprintf(g_gate_why, sizeof(g_gate_why), "creating the wp_color_manager_v1 global failed");
        atomic_store(&g_gate, 0);
        wnc_log("error", "color: wp_color_manager_v1 global creation failed - HDR gate closed");
        log_verdict(1);
        return;
    }
    atomic_store(&g_gate, 1);
    char syms[160];
    sc_layer_hdr_symbols(syms, sizeof(syms));
    wnc_log(TAG, "HDR gate %s: %s and %s. Offering games HDR10: wp_color_manager_v1 version 1 (BT.2020 primaries "
               "+ ST 2084 PQ, parametric descriptions with mastering metadata) and 10-bit AB30/XB30 dma-buf formats; HDR "
               "frames go on the game's own display layer as BT2020_PQ (%s)",
               mode == 2 ? "FORCED OPEN (testing)" : "OPEN", asked, disp, syms);
    if (mode == 2 && !hdr10)
        wnc_log(TAG, "BANNER_WAYLAND_HDR=force on a display without HDR10 - testing only: SurfaceFlinger will tone-map "
                   "the game layer for this panel (expect GPU/CLIENT composition for it), nothing looks HDR");
    if (zc_forced)
        wnc_log(TAG, "zero-copy presentation turned on for this session: the HDR game's own 10-bit frames go straight "
                   "to its display layer (the best path; anything that needs the compositor gets the composed HDR picture)");
    if (!dxvk_hdr)
        wnc_log(TAG, "DXVK_HDR=1 is not in the game's environment: DXVK games will not see an HDR display in DXGI "
                   "(dxgi.enableHDR in a dxvk.conf does the same) - add DXVK_HDR=1 to the container's or shortcut's "
                   "environment variables");
    (void)max_avg; (void)min_lum;
}
