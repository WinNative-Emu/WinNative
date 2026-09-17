/* Layer mode: the scene on a small set of ASurfaceControl layers — see sc_layer.h and
 * ZERO_COPY_SPIKE.md. The ASurfaceControl/ASurfaceTransaction API is dlsym'd from libandroid.so
 * (API 29+), the same way the X11 renderers' scanout code does it, so the library still loads on
 * older devices and the prototype stays off every other path. */
#define _GNU_SOURCE
#include "sc_layer.h"
#include "ahb_swapchain.h"
#include "color_mgmt.h"
#include "vk_present.h"
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/rect.h>
#include <dlfcn.h>
#include <errno.h>
#include <math.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define FOURCC(a, b, c, d) \
    ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))
#define DRM_ABGR8888 FOURCC('A', 'B', '2', '4')   /* R,G,B,A in memory = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM */
#define DRM_ABGR2101010 FOURCC('A', 'B', '3', '0') /* = AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM (VK A2B10G10R10) */
#define AHB_RGBA8 AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM
#define AHB_RGB10A2 AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM
#define MOD_LINEAR 0ULL
#define MOD_QCOM_COMPRESSED 0x0500000000000001ULL /* DRM_FORMAT_MOD_QCOM_COMPRESSED (UBWC) */

/* The gralloc buffer handle behind an AHardwareBuffer (AOSP cutils/native_handle.h, which the NDK
 * does not ship; the layout is a stable ABI: fds first, then ints). */
struct wnc_native_handle { int version; int numFds; int numInts; int data[]; };

/* ---- libandroid SurfaceControl API (dlsym) ---------------------------------------------------- */

typedef struct ASurfaceControl ASurfaceControl;
typedef struct ASurfaceTransaction ASurfaceTransaction;
typedef struct ASurfaceTransactionStats ASurfaceTransactionStats;
typedef void (*sc_complete_fn)(void *context, ASurfaceTransactionStats *stats);

/* <android/hdr_metadata.h> (NDK, API 29), declared here like the rest of this table: stable ABI. */
struct wnc_color_xy { float x, y; };
struct wnc_hdr_smpte2086 {
    struct wnc_color_xy displayPrimaryRed, displayPrimaryGreen, displayPrimaryBlue, whitePoint;
    float maxLuminance, minLuminance;
};
struct wnc_hdr_cta861_3 { float maxContentLightLevel, maxFrameAverageLightLevel; };

static struct {
    ASurfaceControl *(*createFromWindow)(ANativeWindow *, const char *);
    void (*release)(ASurfaceControl *);
    ASurfaceTransaction *(*txCreate)(void);
    void (*txDelete)(ASurfaceTransaction *);
    void (*txApply)(ASurfaceTransaction *);
    void (*setBuffer)(ASurfaceTransaction *, ASurfaceControl *, AHardwareBuffer *, int);
    void (*setZOrder)(ASurfaceTransaction *, ASurfaceControl *, int32_t);
    void (*setVisibility)(ASurfaceTransaction *, ASurfaceControl *, int8_t);
    void (*setGeometry)(ASurfaceTransaction *, ASurfaceControl *, const ARect *, const ARect *, int32_t);
    void (*setBufferTransparency)(ASurfaceTransaction *, ASurfaceControl *, int8_t);
    void (*reparent)(ASurfaceTransaction *, ASurfaceControl *, ASurfaceControl *);
    void (*setOnComplete)(ASurfaceTransaction *, void *, sc_complete_fn);
    int (*prevReleaseFence)(ASurfaceTransactionStats *, ASurfaceControl *);
    /* Display frame-rate vote for the layer (API 30 / 31). Optional: absent on older Android, where
     * the layer simply carries no vote and the app's surface vote is all there is. */
    void (*setFrameRate)(ASurfaceTransaction *, ASurfaceControl *, float, int8_t);
    void (*setFrameRateStrategy)(ASurfaceTransaction *, ASurfaceControl *, float, int8_t, int8_t);
    /* Colour (API 29), optional: only an HDR session ever calls them (color_mgmt.h's gate needs
     * setBufferDataSpace; the metadata calls are sent when present). */
    void (*setBufferDataSpace)(ASurfaceTransaction *, ASurfaceControl *, int32_t);
    void (*setHdrMetadata_smpte2086)(ASurfaceTransaction *, ASurfaceControl *, const struct wnc_hdr_smpte2086 *);
    void (*setHdrMetadata_cta861_3)(ASurfaceTransaction *, ASurfaceControl *, const struct wnc_hdr_cta861_3 *);
    /* Not in the public NDK headers (vndk/hardware_buffer.h) but exported by libnativewindow.so on
     * every device; Mesa's Android WSI calls it for every gralloc buffer it imports. */
    const void *(*getNativeHandle)(const AHardwareBuffer *);
    /* API 35: the HDR headroom this layer asks the display for (0 = no preference). Round 2e asks
     * explicitly for every PQ layer - some phones only boost HDR when a layer asks. */
    void (*setDesiredHdrHeadroom)(ASurfaceTransaction *, ASurfaceControl *, float);
    int state; /* 0 = untried, 1 = loaded, -1 = unavailable */
} api;

#define ASC_VISIBILITY_HIDE 0
#define ASC_VISIBILITY_SHOW 1
#define ASC_TRANSPARENCY_OPAQUE 2

static int load_api(void) {
    if (api.state) return api.state == 1 ? 0 : -1;
    api.state = -1;
    void *nw = dlopen("libnativewindow.so", RTLD_NOW | RTLD_NOLOAD);
    if (!nw) nw = dlopen("libnativewindow.so", RTLD_NOW);
    api.getNativeHandle = nw ? dlsym(nw, "AHardwareBuffer_getNativeHandle") : NULL;
    if (!api.getNativeHandle) {
        wnc_log("layer", "unavailable: libnativewindow.so has no AHardwareBuffer_getNativeHandle");
        return -1;
    }
    void *lib = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
    if (!lib) lib = dlopen("libandroid.so", RTLD_NOW);
    if (!lib) { wnc_log("layer", "unavailable: dlopen(libandroid.so): %s", dlerror()); return -1; }
#define SYM(field, name) api.field = dlsym(lib, name)
    SYM(createFromWindow, "ASurfaceControl_createFromWindow");
    SYM(release, "ASurfaceControl_release");
    SYM(txCreate, "ASurfaceTransaction_create");
    SYM(txDelete, "ASurfaceTransaction_delete");
    SYM(txApply, "ASurfaceTransaction_apply");
    SYM(setBuffer, "ASurfaceTransaction_setBuffer");
    SYM(setZOrder, "ASurfaceTransaction_setZOrder");
    SYM(setVisibility, "ASurfaceTransaction_setVisibility");
    SYM(setGeometry, "ASurfaceTransaction_setGeometry");
    SYM(setBufferTransparency, "ASurfaceTransaction_setBufferTransparency");
    SYM(reparent, "ASurfaceTransaction_reparent");
    SYM(setOnComplete, "ASurfaceTransaction_setOnComplete");
    SYM(prevReleaseFence, "ASurfaceTransactionStats_getPreviousReleaseFenceFd");
    SYM(setFrameRate, "ASurfaceTransaction_setFrameRate");
    SYM(setFrameRateStrategy, "ASurfaceTransaction_setFrameRateWithChangeStrategy");
    SYM(setBufferDataSpace, "ASurfaceTransaction_setBufferDataSpace");
    SYM(setHdrMetadata_smpte2086, "ASurfaceTransaction_setHdrMetadata_smpte2086");
    SYM(setHdrMetadata_cta861_3, "ASurfaceTransaction_setHdrMetadata_cta861_3");
    SYM(setDesiredHdrHeadroom, "ASurfaceTransaction_setDesiredHdrHeadroom");
#undef SYM
    if (!api.createFromWindow || !api.release || !api.txCreate || !api.txDelete || !api.txApply ||
        !api.setBuffer || !api.setZOrder || !api.setVisibility || !api.setGeometry ||
        !api.setBufferTransparency || !api.reparent || !api.setOnComplete || !api.prevReleaseFence) {
        wnc_log("layer", "unavailable: libandroid.so lacks part of the ASurfaceControl API (Android 10+)");
        return -1;
    }
    api.state = 1;
    return 0;
}

/* ---- display frame-rate vote (VRR / refresh-rate matching) ------------------------------------ */
/* The app votes a panel cadence with Surface.setFrameRate on the compositor's SurfaceView, but a
 * zero-copy game's frames never touch that surface - they go straight onto the GAME layer - so
 * SurfaceFlinger needs the same vote there or the layer's cadence is invisible to it. The vote
 * belongs to the layer the game is actually presenting on and to no other: the overlay layer
 * carries a window that updates on its own (slow) schedule and is explicitly voted 0, so it never
 * drags the panel. The rate is set from the app thread and applied on the compositor thread with
 * the next transaction, so no transaction is ever created off-thread. 0 = no vote (panel free). */
#define ASC_FRAME_RATE_COMPAT_DEFAULT 0 /* == ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_DEFAULT */
#define ASC_CHANGE_FRAME_RATE_ALWAYS  1 /* == ANATIVEWINDOW_CHANGE_FRAME_RATE_ALWAYS */

static _Atomic float g_fps_want; /* what the app asked for (the game's cadence) */

void sc_layer_set_frame_rate(float fps) {
    atomic_store(&g_fps_want, fps > 0.0f ? fps : 0.0f);
}

/* ---- the layers and their buffer pools -------------------------------------------------------- */

/* Five for the game layer: one on screen, one queued, and the ones SurfaceFlinger has not reported
 * back yet (its OnComplete for a transaction arrives only after the NEXT frame is presented), with
 * room to spare, so a free buffer is nearly always one the display already let go of. With three,
 * a late callback left none free ("no free layer buffer … frame dropped"). */
#define POOL_MAX 5

struct slot {
    AHardwareBuffer *ahb;
    struct vkp_image *img;      /* the AHB imported into the compositor's Turnip as a blit target */
    int w, h;
    uint32_t fmt;               /* AHARDWAREBUFFER_FORMAT_*: RGBA8888, or RGBA1010102 for an HDR picture */
    int release_fd;             /* SurfaceFlinger's release fence: signals when it stopped reading; -1 = none */
    int busy;                   /* set on the layer, or still referenced by SurfaceFlinger */
};

/* One Android display layer: its SurfaceControl, its own buffer pool and its own lifetime. The
 * z-order is fixed by the id (game below, overlay above); nothing is ever re-ordered at runtime. */
struct layer {
    const char *name;
    int32_t z;
    int pool_n;                 /* buffers in this layer's pool (the game cycles more) */
    struct slot slots[POOL_MAX];
    ASurfaceControl *sc;
    ANativeWindow *win;         /* the output window sc was created on */
    int shown;
    int cur_slot;               /* pool slot of the buffer on the layer, -1 = none / not ours */
    void *cur_token;            /* zero-copy: the game's buffer on the layer (NULL = a pool slot) */
    ARect geo_src, geo_dst;
    int geo_valid;
    int alloc_tier;             /* how pool buffers are asked of gralloc (TIER_*), lowered on failure */
    uint64_t pool_modifier;
    int first_logged;
    int64_t drop_logged_ns;
    unsigned drops_unlogged;    /* frames dropped since the last "no free layer buffer" line */
    /* The last pool layout written to the log, so a line is written per change, not per buffer. */
    int logged_w, logged_h, logged_tier;
    uint32_t logged_fmt;
    uint64_t logged_mod;
    int votes_rate;             /* 1: this layer carries the game's cadence (the game layer) */
    float fps_applied;          /* the vote the live SurfaceControl already carries (-1 = none yet) */
    int recreate_pending;       /* composition recovery: swap this layer's SurfaceControl for a fresh
                                 * one inside the next frame's transaction (see swap_sc_begin) */
    int32_t ds_applied;         /* dataspace the live SurfaceControl carries; -1 = never set on it
                                 * (untouched, today's UNKNOWN), see apply_colour */
    uint32_t md_applied;        /* identity of the image description whose metadata it carries, 0 = none */
    float hr_applied;           /* desired HDR headroom the live SurfaceControl carries; < 0 = never set */
};

static struct layer g_layers[SC_LAYER_COUNT];
static void apply_frame_rate(ASurfaceTransaction *tx, struct layer *l);
static void coverage_text(const struct layer *l, char *out, size_t n);
static int g_layers_ready;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER; /* pools + callback state */
static int g_pending_cb;                                   /* OnComplete callbacks not yet delivered */
static unsigned g_stat_layer_frames;                             /* frames put on a layer through our own buffers */
static int g_two_logged;
/* A small buffer set on a layer when it is hidden or retired, so SurfaceFlinger replaces (and
 * releases, with a fence) the game's buffer instead of holding it while the layer is invisible. */
static AHardwareBuffer *g_blank;

static void layers_init(void) {
    if (g_layers_ready) return;
    g_layers_ready = 1;
    g_layers[SC_LAYER_GAME] = (struct layer){.name = "wnc_wayland_game", .z = 1, .pool_n = POOL_MAX,
                                             .cur_slot = -1, .votes_rate = 1, .fps_applied = -1.0f, .hr_applied = -1.0f,
                                             .ds_applied = -1};
    g_layers[SC_LAYER_OVERLAY] = (struct layer){.name = "wnc_wayland_overlay", .z = 2, .pool_n = 3,
                                                .cur_slot = -1, .votes_rate = 0, .fps_applied = -1.0f,
                                                .hr_applied = -1.0f, .ds_applied = -1};
    for (int i = 0; i < SC_LAYER_COUNT; i++)
        for (int j = 0; j < POOL_MAX; j++) g_layers[i].slots[j].release_fd = -1;
}

static struct layer *layer_of(int id) {
    layers_init();
    return (id >= 0 && id < SC_LAYER_COUNT) ? &g_layers[id] : NULL;
}

struct complete_ctx {
    ASurfaceControl *sc;
    int layer;                  /* which layer's pool prev_slot belongs to */
    int prev_slot;              /* pool buffer this transaction replaced: free once its release fence is known */
    void *prev_token;           /* or the game's buffer it replaced: ahb_swapchain gets its release fence */
    int retire;                 /* release sc after this transaction (hide + reparent) */
};

static int64_t now_ns(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

/* Binder thread. */
static void on_complete(void *context, ASurfaceTransactionStats *stats) {
    struct complete_ctx *ctx = context;
    pthread_mutex_lock(&g_lock);
    if (ctx->layer >= 0 && ctx->layer < SC_LAYER_COUNT && ctx->prev_slot >= 0 && ctx->prev_slot < POOL_MAX) {
        struct slot *s = &g_layers[ctx->layer].slots[ctx->prev_slot];
        int fd = (stats && ctx->sc) ? api.prevReleaseFence(stats, ctx->sc) : -1;
        if (s->release_fd >= 0) close(s->release_fd);
        s->release_fd = fd;
        s->busy = 0;
    }
    if (g_pending_cb > 0) g_pending_cb--;
    pthread_mutex_unlock(&g_lock);
    if (ctx->prev_token) {
        int fd = (stats && ctx->sc) ? api.prevReleaseFence(stats, ctx->sc) : -1;
        ahb_swapchain_layer_released(ctx->prev_token, fd); /* takes the fd */
    }
    if (ctx->retire && ctx->sc) api.release(ctx->sc);
    free(ctx);
}

/* `sc` is the SurfaceControl the REPLACED buffer sits on, which is not always l->sc: during a
 * composition-recovery swap the outgoing buffer belongs to the old SurfaceControl, and both its
 * release fence and its release must be taken from that one. */
static int add_complete_on(ASurfaceTransaction *tx, struct layer *l, ASurfaceControl *sc,
                           int prev_slot, void *prev_token, int retire) {
    struct complete_ctx *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) return -1;
    ctx->sc = sc; ctx->layer = (int)(l - g_layers); ctx->prev_slot = prev_slot;
    ctx->prev_token = prev_token; ctx->retire = retire;
    pthread_mutex_lock(&g_lock);
    g_pending_cb++;
    pthread_mutex_unlock(&g_lock);
    api.setOnComplete(tx, ctx, on_complete);
    return 0;
}

static int add_complete(ASurfaceTransaction *tx, struct layer *l, int prev_slot, void *prev_token, int retire) {
    return add_complete_on(tx, l, l->sc, prev_slot, prev_token, retire);
}

/* The stand-in buffer for a hidden/retired layer (allocated once, black, shared by both layers). */
static AHardwareBuffer *blank_buffer(void) {
    if (g_blank) return g_blank;
    AHardwareBuffer_Desc d = {
        .width = 16, .height = 16, .layers = 1, .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
                 AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY};
    if (AHardwareBuffer_allocate(&d, &g_blank) != 0 || !g_blank) { g_blank = NULL; return NULL; }
    void *p = NULL;
    if (AHardwareBuffer_lock(g_blank, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY, -1, NULL, &p) == 0 && p) {
        AHardwareBuffer_Desc got; AHardwareBuffer_describe(g_blank, &got);
        memset(p, 0, (size_t)got.stride * 4 * 16);
        AHardwareBuffer_unlock(g_blank, NULL);
    }
    return g_blank;
}

/* Take the current buffer (pool slot or the game's) off the layer by putting the blank one on it,
 * so SurfaceFlinger releases it with a fence through this transaction's callback. */
static void replace_with_blank(ASurfaceTransaction *tx, struct layer *l) {
    AHardwareBuffer *b = blank_buffer();
    if (b) api.setBuffer(tx, l->sc, b, -1);
}

/* Everything a SurfaceControl needs on its way out, as transaction ops: give the buffer back (so
 * SurfaceFlinger releases the game's, with a fence), hide it, unparent it. */
static void retire_ops(ASurfaceTransaction *tx, ASurfaceControl *sc) {
    AHardwareBuffer *b = blank_buffer();
    if (b) api.setBuffer(tx, sc, b, -1);
    api.setVisibility(tx, sc, ASC_VISIBILITY_HIDE);
    api.reparent(tx, sc, NULL);
}

/* Hide + detach one layer; the SurfaceControl is released from the transaction's callback (the
 * buffer on it stays referenced by SurfaceFlinger until then). */
static void retire_sc(struct layer *l) {
    if (!l->sc) return;
    ASurfaceTransaction *tx = api.txCreate();
    if (tx) {
        retire_ops(tx, l->sc);
        if (add_complete(tx, l, l->cur_slot, l->cur_token, 1) != 0) api.release(l->sc);
        api.txApply(tx);
        api.txDelete(tx);
    } else {
        if (l->cur_token) ahb_swapchain_layer_released(l->cur_token, -1);
        api.release(l->sc);
    }
    wnc_log("layer", "%s: SurfaceControl retired (window %p)", l->name, (void *)l->win);
    l->sc = NULL; l->win = NULL;
    l->shown = 0; l->cur_slot = -1; l->cur_token = NULL; l->geo_valid = 0;
    l->fps_applied = -1.0f; /* the next SurfaceControl carries no vote until it is re-applied */
    l->recreate_pending = 0; /* a fresh SurfaceControl is coming anyway */
    l->ds_applied = -1; l->md_applied = 0; l->hr_applied = -1.0f; /* ...and no colour tag either */
}

/* Wait (bounded) for SurfaceFlinger to finish with every pool buffer of every layer, then free
 * the pools. */
static void drain_and_free_pools(void) {
    int64_t deadline = now_ns() + 300000000LL;
    for (;;) {
        pthread_mutex_lock(&g_lock);
        int pending = g_pending_cb;
        pthread_mutex_unlock(&g_lock);
        if (!pending || now_ns() > deadline) break;
        usleep(2000);
    }
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < SC_LAYER_COUNT; i++) {
        for (int j = 0; j < POOL_MAX; j++) {
            struct slot *s = &g_layers[i].slots[j];
            if (s->release_fd >= 0) { struct pollfd p = {.fd = s->release_fd, .events = POLLIN}; poll(&p, 1, 100); close(s->release_fd); }
            if (s->img) vkp_image_destroy(s->img);
            if (s->ahb) AHardwareBuffer_release(s->ahb);
            memset(s, 0, sizeof(*s));
            s->release_fd = -1;
        }
        g_layers[i].first_logged = 0;
        g_layers[i].logged_w = g_layers[i].logged_h = 0; /* the next pool's layout is logged again */
    }
    pthread_mutex_unlock(&g_lock);
}

/* Native-handle sniff, the same one Mesa's u_gralloc fallback uses (u_gralloc_fallback.c): a QTI
 * gralloc private_handle_t carries the magic 'gmsm' as its first int and the UBWC flag
 * (PRIV_FLAGS_UBWC_ALIGNED, 0x08000000) in the next one. Returns 0 when the layout is unknown. */
static int sniff_modifier(const struct wnc_native_handle *h, uint64_t *mod) {
    const uint32_t gmsm = ('g' << 24) | ('m' << 16) | ('s' << 8) | 'm';
    if (!h || h->numFds < 1 || h->numInts < 2) return 0;
    if ((uint32_t)h->data[h->numFds] != gmsm) return 0;
    *mod = (h->data[h->numFds + 1] & 0x08000000) ? MOD_QCOM_COMPRESSED : MOD_LINEAR;
    return 1;
}

static int g_alloc_failed; /* set by alloc_slot when gralloc or the import refused a buffer */

/* How a layer's pool buffers are asked of gralloc, best first; a layer only ever moves down.
 *   TIER_UBWC   GPU render target + sampled + composer overlay + AHARDWAREBUFFER_USAGE_VENDOR_0 (bit 28):
 *               QTI gralloc's GRALLOC_USAGE_PRIVATE_ALLOC_UBWC. Without it QTI gralloc allocates LINEAR even
 *               for a GPU render target (gr_allocator.cpp IsUBwcEnabled: an explicit UBWC format, the
 *               private UBWC bit or CLIENT_TARGET, and no CPU bit). Whether gralloc really compressed the
 *               buffer is read back from its handle (sniff_modifier), never assumed.
 *   TIER_PLAIN  the same without the vendor bit (a gralloc that refuses unknown bits; what we asked before).
 *   TIER_LINEAR plus a CPU bit, so the layout is known to be linear even when the handle is unreadable. */
enum { TIER_UBWC, TIER_PLAIN, TIER_LINEAR };
#define WNC_AHB_USAGE_VENDOR_UBWC (1ULL << 28) /* AHARDWAREBUFFER_USAGE_VENDOR_0 */

static const char *tier_name(int tier) {
    return tier == TIER_UBWC ? "asked for UBWC" : tier == TIER_PLAIN ? "no UBWC request" : "linear by request (CPU bit)";
}

static int alloc_slot(struct layer *l, struct slot *s, int w, int h, uint32_t fmt) {
    g_alloc_failed = 0;
    while (l->alloc_tier <= TIER_LINEAR) {
        const int tier = l->alloc_tier;
        AHardwareBuffer_Desc d = {
            .width = (uint32_t)w, .height = (uint32_t)h, .layers = 1,
            .format = fmt,
            /* GPU render target (the blit writes it) + sampled (SurfaceFlinger's GPU fallback reads it) +
             * composer overlay (the display scans it out). See the tiers above for the rest. */
            .usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                     AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
                     (tier == TIER_UBWC ? WNC_AHB_USAGE_VENDOR_UBWC : 0) |
                     (tier == TIER_LINEAR ? AHARDWAREBUFFER_USAGE_CPU_READ_RARELY : 0)};
        AHardwareBuffer *ahb = NULL;
        if (AHardwareBuffer_allocate(&d, &ahb) != 0 || !ahb) {
            if (tier < TIER_LINEAR) {
                wnc_log("layer", "%s: gralloc refused a %dx%d pool buffer (%s): trying %s", l->name, w, h,
                           tier_name(tier), tier_name(tier + 1));
                l->alloc_tier = tier + 1;
                continue;
            }
            wnc_log("error", "layer: %s: AHardwareBuffer_allocate %dx%d (format %#x) failed", l->name, w, h, fmt);
            g_alloc_failed = 1;
            return -1;
        }
        AHardwareBuffer_Desc got; AHardwareBuffer_describe(ahb, &got);
        const struct wnc_native_handle *nh = api.getNativeHandle(ahb);
        uint64_t mod = MOD_LINEAR;
        if (!sniff_modifier(nh, &mod)) {
            /* Not a handle we can read: the only layout we can assume is linear, and only if the buffer
             * was asked for with a CPU bit (gralloc must not have compressed it). */
            if (tier < TIER_LINEAR) {
                AHardwareBuffer_release(ahb);
                l->alloc_tier = TIER_LINEAR;
                wnc_log("layer", "%s: gralloc handle layout unknown (%d fds, %d ints): using linear pool buffers",
                           l->name, nh ? nh->numFds : -1, nh ? nh->numInts : -1);
                continue;
            }
            mod = MOD_LINEAR;
        }
        int fd = (nh && nh->numFds > 0) ? nh->data[0] : -1;
        /* The test create: vkp_image_import_dmabuf creates the image with gralloc's explicit layout, so a
         * layout the compositor's Turnip refuses falls back a tier instead of drawing garbage. */
        struct vkp_image *img = fd >= 0 ? vkp_image_import_dmabuf(fd, fmt == AHB_RGB10A2 ? DRM_ABGR2101010 : DRM_ABGR8888,
                                                                  mod, w, h, got.stride * 4, 0, 1) : NULL;
        if (!img) {
            wnc_log("layer", "%s: import of a %s %dx%d pool buffer (stride %u px) into the compositor's Turnip failed",
                       l->name, mod == MOD_QCOM_COMPRESSED ? "UBWC" : "linear", w, h, got.stride);
            AHardwareBuffer_release(ahb);
            if (tier < TIER_LINEAR) { l->alloc_tier = tier + 1; continue; }
            g_alloc_failed = 1;
            return -1;
        }
        s->ahb = ahb; s->img = img; s->w = w; s->h = h; s->fmt = fmt; s->release_fd = -1; s->busy = 0;
        l->pool_modifier = mod;
        if (l->logged_w != w || l->logged_h != h || l->logged_fmt != fmt || l->logged_mod != mod ||
            l->logged_tier != tier) {
            l->logged_w = w; l->logged_h = h; l->logged_fmt = fmt; l->logged_mod = mod; l->logged_tier = tier;
            wnc_log("layer", "%s: pool buffers %dx%d%s: %s (%s), stride %u px, up to %d buffers (gralloc handle %d fds / %d ints)",
                       l->name, w, h, fmt == AHB_RGB10A2 ? " 10-bit RGBA1010102" : "",
                       mod == MOD_QCOM_COMPRESSED ? "UBWC (QCOM_COMPRESSED)" : "linear", tier_name(tier),
                       got.stride, l->pool_n, nh ? nh->numFds : -1, nh ? nh->numInts : -1);
        }
        return 0;
    }
    g_alloc_failed = 1;
    return -1;
}

/* 1 when a sync_file has signalled (the display has stopped reading that buffer). */
static int fence_signalled(int fd) {
    struct pollfd p = {.fd = fd, .events = POLLIN};
    int r;
    do { r = poll(&p, 1, 0); } while (r < 0 && errno == EINTR);
    return r > 0;
}

/* A slot SurfaceFlinger has handed back, holding a w x h buffer of AHB format fmt. -1 = none free (or
 * the buffer could not be made). A free slot whose release fence has already signalled is preferred;
 * otherwise the fence of the first free one is handed back in *wait_fd for the copy to wait on ON THE
 * GPU (vkp_blit_image / vkp_pass_copy_to consume it), so the compositor thread no longer blocks here.
 * Only a driver without VK_KHR_external_semaphore_fd still waits on the CPU, bounded, as before.
 * *wait_fd is -1 whenever nothing needs waiting for (and on every failure). */
static int take_free_slot(struct layer *l, int w, int h, uint32_t fmt, int *wait_fd) {
    int idx = -1, pending = -1, fd = -1;
    *wait_fd = -1;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < l->pool_n; i++) {
        if (i == l->cur_slot || l->slots[i].busy) continue;
        const int rf = l->slots[i].release_fd;
        if (rf < 0 || fence_signalled(rf)) { idx = i; break; }
        if (pending < 0) pending = i;
    }
    if (idx < 0) idx = pending;
    if (idx >= 0) { fd = l->slots[idx].release_fd; l->slots[idx].release_fd = -1; }
    pthread_mutex_unlock(&g_lock);
    if (idx < 0) return -1;
    if (fd >= 0 && fence_signalled(fd)) { close(fd); fd = -1; }
    struct slot *s = &l->slots[idx];
    if (s->ahb && (s->w != w || s->h != h || s->fmt != fmt)) {
        /* Size or format change: this slot is free, so it can be replaced at once (the display keeps its
         * own reference to the old buffer for as long as it reads it; the new one needs no wait). */
        if (fd >= 0) { close(fd); fd = -1; }
        vkp_image_destroy(s->img); AHardwareBuffer_release(s->ahb);
        memset(s, 0, sizeof(*s)); s->release_fd = -1;
    }
    if (fd >= 0 && !vkp_can_wait_sync_fd()) {
        struct pollfd p = {.fd = fd, .events = POLLIN};
        int r;
        do { r = poll(&p, 1, 100); } while (r < 0 && errno == EINTR);
        if (r == 0) {
            /* Still read by the display: keep its fence with it (the next use waits again), drop this frame. */
            pthread_mutex_lock(&g_lock);
            if (s->release_fd < 0) s->release_fd = fd; else close(fd);
            pthread_mutex_unlock(&g_lock);
            return -1;
        }
        close(fd);
        fd = -1;
    }
    if (!s->ahb && alloc_slot(l, s, w, h, fmt) != 0) {
        if (fd >= 0) close(fd);
        return -1;
    }
    *wait_fd = fd;
    return idx;
}

/* ---- public --------------------------------------------------------------------------------- */

int sc_layer_available(void) { return load_api() == 0; }

void sc_layer_probe_dmabuf_fd(int fd) {
    static int done;
    if (done || fd < 0) return;
    done = 1;
    struct { uint32_t flags; int32_t fd; } exp = {.flags = 1u /* DMA_BUF_SYNC_READ */, .fd = -1};
    /* DMA_BUF_IOCTL_EXPORT_SYNC_FILE = _IOWR('b', 2, struct dma_buf_export_sync_file) */
    if (ioctl(fd, _IOWR('b', 2, exp), &exp) == 0) {
        wnc_log("layer", "kernel exports sync_file fences from the game's dma-buf: zero-copy acquire fences can come from the buffer itself");
        if (exp.fd >= 0) close(exp.fd);
    } else {
        wnc_log("layer", "DMA_BUF_IOCTL_EXPORT_SYNC_FILE on the game's dma-buf failed (%s): acquire fences must be exported by the game's driver (sync_fd) instead",
                   strerror(errno));
    }
}

/* One layer's SurfaceControl on the current output window (created on first use, re-created after
 * a window change). -1 = no window / no API. */
static int ensure_sc(struct layer *l) {
    if (load_api() != 0) return -1;
    ANativeWindow *win = vkp_window();
    if (!win) return -1;
    if (l->sc && l->win != win) retire_sc(l);
    if (!l->sc) {
        l->sc = api.createFromWindow(win, l->name);
        if (!l->sc) { wnc_log("error", "layer: ASurfaceControl_createFromWindow(%s) failed", l->name); return -1; }
        l->win = win;
        l->ds_applied = -1; l->md_applied = 0; l->hr_applied = -1.0f;
        ASurfaceTransaction *tx = api.txCreate();
        if (tx) {
            api.setZOrder(tx, l->sc, l->z);
            api.setVisibility(tx, l->sc, ASC_VISIBILITY_HIDE);
            apply_frame_rate(tx, l);
            api.txApply(tx); api.txDelete(tx);
        }
        wnc_log("layer", "SurfaceControl \"%s\" created as a child of the screen surface (z=%d)", l->name, (int)l->z);
    }
    return 0;
}

/* Geometry of a w x h buffer covering the scene, through the same mapping the blit path and the
 * app's touch mapping use. 1 = r filled, 0 = nothing of it is on screen, -1 = no mapping. */
static int layer_geometry(int w, int h, int scene_w, int scene_h, int r[8]) {
    if (vkp_update_map(scene_w, scene_h) != 0) return -1;
    return vkp_map_rect(w, h, scene_w, scene_h, r) ? 1 : 0;
}

/* The GAME layer's last placement, kept across SurfaceControl retires and swaps (l->geo_valid is
 * per-SurfaceControl and is cleared by both). This is the src -> dst the display actually works
 * with, and it is what decides whether a second layer is affordable — see overlay_affordable(). */
static ARect g_game_src, g_game_dst;
static int g_game_geo_known;

static void apply_geometry(ASurfaceTransaction *tx, struct layer *l, const int r[8]) {
    ARect srcR = {r[0], r[1], r[2], r[3]}, dstR = {r[4], r[5], r[6], r[7]};
    if (l == &g_layers[SC_LAYER_GAME]) { g_game_src = srcR; g_game_dst = dstR; g_game_geo_known = 1; }
    if (!l->geo_valid || memcmp(&srcR, &l->geo_src, sizeof(srcR)) || memcmp(&dstR, &l->geo_dst, sizeof(dstR))) {
        api.setGeometry(tx, l->sc, &srcR, &dstR, 0 /* no transform: the DPU scales, never rotates */);
        l->geo_src = srcR; l->geo_dst = dstR; l->geo_valid = 1;
        char cov[96] = "";
        if (l->ds_applied > 0) coverage_text(l, cov, sizeof(cov));
        wnc_log("layer", "%s geometry: buffer %d,%d-%d,%d -> screen %d,%d-%d,%d%s%s%s", l->name,
                   r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], cov[0] ? " (HDR layer " : "", cov, cov[0] ? ")" : "");
    }
}

/* Add the vote to tx when it differs from what this layer carries (compositor thread). Only the
 * layer the game presents on carries the game's rate; every other layer is voted 0, so a slow
 * overlay window can never hold the panel at the game's cadence (or the game's at the overlay's). */
static void apply_frame_rate(ASurfaceTransaction *tx, struct layer *l) {
    float want = l->votes_rate ? atomic_load(&g_fps_want) : 0.0f;
    if (want == l->fps_applied) return;
    if (!api.setFrameRateStrategy && !api.setFrameRate) return;
    if (api.setFrameRateStrategy) {
        /* ALWAYS, like the app's own surface vote: the seamless-only default is ignored by a panel
         * sitting at its peak rate, which is exactly the case we need to move. */
        api.setFrameRateStrategy(tx, l->sc, want, ASC_FRAME_RATE_COMPAT_DEFAULT, ASC_CHANGE_FRAME_RATE_ALWAYS);
    } else {
        api.setFrameRate(tx, l->sc, want, ASC_FRAME_RATE_COMPAT_DEFAULT);
    }
    int first = l->fps_applied < 0.0f;
    l->fps_applied = want;
    if (want > 0.0f) wnc_log("layer", "display frame-rate vote on %s: %.2f Hz", l->name, want);
    else if (!first) wnc_log("layer", "display frame-rate vote on %s cleared (panel runs free)", l->name);
}

/* ---- colour (HDR, color_mgmt.h) -------------------------------------------------------------
 * The layer is told what its buffer's pixels MEAN — the dataspace (BT2020_PQ for an HDR10 frame) and
 * the game's mastering / content-light metadata — in the same transaction as the buffer, so the
 * display never shows an HDR frame decoded as sRGB or the other way round. A layer that has never been
 * tagged is never touched: sessions without an HDR description make no colour call at all. */
/* How much of the screen the layer's picture covers ("covers 80% of the screen (1920x1080 of 2400x1080)"):
 * some phones only switch to HDR for an HDR layer above a minimum area. Empty when unknown. */
static void coverage_text(const struct layer *l, char *out, size_t n) {
    int ow = 0, oh = 0;
    vkp_output_size(&ow, &oh);
    if (!l->geo_valid || ow <= 0 || oh <= 0) { if (n) out[0] = 0; return; }
    int w = l->geo_dst.right - l->geo_dst.left, h = l->geo_dst.bottom - l->geo_dst.top;
    if (w < 0) w = 0;
    if (h < 0) h = 0;
    const double pct = 100.0 * ((double)w * h) / ((double)ow * oh);
    snprintf(out, n, "covers %.0f%% of the screen (%dx%d of %dx%d)", pct > 100.0 ? 100.0 : pct, w, h, ow, oh);
}

/* API 35: ask the display for HDR headroom explicitly while the layer carries an HDR frame, and clear the
 * request (0 = no preference) when it stops. Only on change; a layer never tagged is never touched. */
static void apply_headroom(ASurfaceTransaction *tx, struct layer *l, const struct wnc_color *c) {
    static int said_missing;
    if (!api.setDesiredHdrHeadroom) {
        if (c && c->dataspace && !said_missing) {
            said_missing = 1;
            wnc_color_note_headroom_request(-1.0f);
            wnc_log("color", "HDR headroom request not available on %s (Android < 15 has no "
                       "ASurfaceTransaction_setDesiredHdrHeadroom): the layer relies on Android's default", l->name);
        }
        return;
    }
    const float want = (c && c->dataspace) ? wnc_color_desired_headroom(c, NULL, 0) : 0.0f;
    if (l->hr_applied < 0.0f && want == 0.0f && !(c && c->dataspace)) return; /* never asked: leave it be */
    if (l->hr_applied >= 0.0f && fabsf(want - l->hr_applied) < 0.005f) return;
    char why[200] = "";
    if (c && c->dataspace) wnc_color_desired_headroom(c, why, sizeof(why));
    api.setDesiredHdrHeadroom(tx, l->sc, want);
    const int first = l->hr_applied < 0.0f;
    l->hr_applied = want;
    wnc_color_note_headroom_request(want);
    if (want > 0.0f)
        wnc_log("color", "requested HDR headroom %.1fx on %s (%s)", want, l->name, why);
    else if (c && c->dataspace)
        wnc_log("color", "no HDR headroom requested on %s: %s", l->name, why);
    else if (!first)
        wnc_log("color", "HDR headroom request on %s cleared (no preference): the frame on the layer is not HDR", l->name);
}

static void apply_colour(ASurfaceTransaction *tx, struct layer *l, const struct wnc_color *c) {
    int32_t want = c ? c->dataspace : WNC_ADATASPACE_UNKNOWN;
    uint32_t md = (c && c->dataspace) ? c->identity : 0;
    if (!api.setBufferDataSpace) return;
    if (l->ds_applied < 0 && want == WNC_ADATASPACE_UNKNOWN) return;   /* never tagged: leave it be */
    apply_headroom(tx, l, want ? c : NULL);
    if (want == l->ds_applied && md == l->md_applied) return;
    api.setBufferDataSpace(tx, l->sc, want);
    struct wnc_hdr_smpte2086 st;
    struct wnc_hdr_cta861_3 cta;
    int st_on = c && c->dataspace && c->has_st2086, cta_on = c && c->dataspace && c->has_cta861;
    if (st_on) {
        st = (struct wnc_hdr_smpte2086){
            .displayPrimaryRed = {c->red[0], c->red[1]}, .displayPrimaryGreen = {c->green[0], c->green[1]},
            .displayPrimaryBlue = {c->blue[0], c->blue[1]}, .whitePoint = {c->white[0], c->white[1]},
            .maxLuminance = c->max_lum, .minLuminance = c->min_lum};
    }
    if (cta_on) cta = (struct wnc_hdr_cta861_3){.maxContentLightLevel = c->max_cll, .maxFrameAverageLightLevel = c->max_fall};
    /* NULL clears what a previous description set: metadata never outlives the frame it belongs to. */
    if (api.setHdrMetadata_smpte2086) api.setHdrMetadata_smpte2086(tx, l->sc, st_on ? &st : NULL);
    if (api.setHdrMetadata_cta861_3) api.setHdrMetadata_cta861_3(tx, l->sc, cta_on ? &cta : NULL);
    if (want) {
        char cov[96];
        coverage_text(l, cov, sizeof(cov));
        wnc_log("color", "%s: dataspace %s (%#x) set on the display layer for image description #%u%s%s; SMPTE 2086 "
                   "%s, CTA-861.3 %s [%s]", l->name, want == WNC_ADATASPACE_BT2020_PQ ? "BT2020_PQ" : "HDR",
                   (unsigned)want, c->identity, cov[0] ? ", " : "", cov,
                   st_on ? (api.setHdrMetadata_smpte2086 ? "sent" : "not supported by this Android") : "none given",
                   cta_on ? (api.setHdrMetadata_cta861_3 ? "sent" : "not supported by this Android") : "none given",
                   c->text);
    }
    else
        wnc_log("color", "%s: dataspace back to UNKNOWN (sRGB), HDR metadata cleared - the frame on the layer is "
                   "not an HDR frame", l->name);
    l->ds_applied = want;
    l->md_applied = md;
}

int sc_layer_can_tag_hdr(void) { return load_api() == 0 && api.setBufferDataSpace != NULL; }

void sc_layer_hdr_symbols(char *out, size_t size) {
    if (load_api() != 0) { snprintf(out, size, "no display layers"); return; }
    snprintf(out, size, "setBufferDataSpace %s, setHdrMetadata_smpte2086 %s, setHdrMetadata_cta861_3 %s, "
             "setDesiredHdrHeadroom %s", api.setBufferDataSpace ? "yes" : "NO",
             api.setHdrMetadata_smpte2086 ? "yes" : "no", api.setHdrMetadata_cta861_3 ? "yes" : "no",
             api.setDesiredHdrHeadroom ? "yes (asked for per HDR frame: content peak / SDR white)" : "no (Android < 15)");
}

/* Once, when a second layer first goes up: HWC only composes a few layers before SurfaceFlinger
 * falls back to GPU client composition, so the count is deliberately capped and said out loud. */
static void log_layer_count(void) {
    if (g_two_logged) return;
    g_two_logged = 1;
    wnc_log("layer", "%d display layers in use: \"%s\" (z=%d) and \"%s\" (z=%d) above it, both children of the "
               "screen surface; the app's own views (drawer, HUD, pointer) stay above both. %d is the cap - more "
               "would push SurfaceFlinger into GPU client composition",
               SC_LAYER_COUNT, g_layers[SC_LAYER_GAME].name, (int)g_layers[SC_LAYER_GAME].z,
               g_layers[SC_LAYER_OVERLAY].name, (int)g_layers[SC_LAYER_OVERLAY].z, SC_LAYER_COUNT);
}

/* ---- composition recovery ---------------------------------------------------------------------
 * When the overlay is retired and the scene is one fullscreen window again, the game layer is
 * marked for a swap and the swap rides the NEXT frame: a fresh SurfaceControl is created here, the
 * frame is put on it, and the old one is hidden and unparented IN THE SAME TRANSACTION. Because
 * SurfaceFlinger applies a transaction atomically there is never a composited frame with neither
 * layer on it — no black frame, and no dropped frame beyond the one layer creation. The outgoing
 * buffer is released through the OLD SurfaceControl's callback, which also releases it.
 *
 * ⚠️ MEASURED 2026-09-14, and the news is bad: on the Pocket FIT this does NOT bring hardware
 * composition back. The swap fires 6 ms after the overlay goes, SurfaceFlinger really does hand out
 * a new layer (its id changes), the game never drops a frame — and the composer still reports
 * `DEVICE/CLIENT` 24 s later. Neither does dropping the layer path entirely and re-creating the
 * SurfaceControl after a gap. The ONE thing that clears it is HOME + resume, which re-creates the
 * app's whole window and SurfaceView (`VRI[XServerDisplayActivity]#0` becomes `#4`) — so the sticky
 * client-composition state belongs to the PARENT surface (or the display), not to this child layer.
 * The phase-4 note that "only re-creating the GAME layer's SurfaceControl clears it" was inferred
 * from HOME + resume and is wrong; see sc_layer.h. The swap is kept because it is free and correct
 * and the mechanism may differ on hardware that does not rotate every layer — but do not claim it
 * restores DEVICE composition, and do not log as if it did.
 *
 * Returns the old SurfaceControl (the caller must add retire_ops for it to the same transaction and
 * name it in add_complete_on), or NULL when no swap is due. Compositor thread. */
static ASurfaceControl *swap_sc_begin(struct layer *l) {
    if (!l->recreate_pending) return NULL;
    l->recreate_pending = 0;
    if (!l->sc) return NULL;                 /* nothing to swap; ensure_sc made a fresh one already */
    ANativeWindow *win = vkp_window();
    if (!win || win != l->win) return NULL;  /* the window changed: ensure_sc re-creates it anyway */
    ASurfaceControl *fresh = api.createFromWindow(win, l->name);
    if (!fresh) {
        wnc_log("error", "layer: %s: composition recovery could not create a new SurfaceControl", l->name);
        return NULL;
    }
    ASurfaceControl *old = l->sc;
    l->sc = fresh;
    /* These describe the SurfaceControl, not the layer: the new one carries none of them yet.
     * cur_slot / cur_token are deliberately NOT cleared — they name the buffer still on the OLD
     * SurfaceControl, which is what the caller passes to add_complete_on as the one being replaced. */
    l->shown = 0;
    l->geo_valid = 0;
    l->fps_applied = -1.0f;
    l->ds_applied = -1; l->md_applied = 0; l->hr_applied = -1.0f;
    wnc_log("layer", "composition recovery: %s got a fresh SurfaceControl now that nothing is above "
               "the game (measured on this panel: hardware composition does NOT return from this alone)", l->name);
    return old;
}

/* The transaction that puts pool slot `idx` of layer `l` on screen at `r`, as `color` (NULL = no
 * description). 0 = applied. */
static int present_slot(struct layer *l, int idx, const int r[8], const struct wnc_color *color) {
    struct slot *s = &l->slots[idx];
    ASurfaceTransaction *tx = api.txCreate();
    if (!tx) return -1;
    ASurfaceControl *old = swap_sc_begin(l); /* this frame carries the recovery swap, if one is due */
    if (old) api.setZOrder(tx, l->sc, l->z);
    /* The blit was waited for on the CPU, so no acquire fence is needed (-1). */
    api.setBuffer(tx, l->sc, s->ahb, -1);
    /* The compositor composes with blits, which overwrite: nothing is ever alpha-blended on the
     * copy path either, so every layer is opaque and the picture matches it pixel for pixel. */
    api.setBufferTransparency(tx, l->sc, ASC_TRANSPARENCY_OPAQUE);
    apply_geometry(tx, l, r);
    apply_frame_rate(tx, l);
    apply_colour(tx, l, color);
    if (!l->shown) api.setVisibility(tx, l->sc, ASC_VISIBILITY_SHOW);
    if (old) retire_ops(tx, old);
    if (add_complete_on(tx, l, old ? old : l->sc, l->cur_slot, l->cur_token, old ? 1 : 0) != 0 && old)
        api.release(old); /* no callback to retire it from (out of memory): let it go here */
    pthread_mutex_lock(&g_lock);
    s->busy = 1;
    pthread_mutex_unlock(&g_lock);
    api.txApply(tx);
    api.txDelete(tx);
    l->cur_slot = idx;
    l->cur_token = NULL;
    if (!l->shown) { l->shown = 1; wnc_log("layer", "%s: layer shown", l->name); }
    g_stat_layer_frames++;
    return 0;
}

/* No free buffer this frame: the display still holds all of them. Counted for the 10 s perf line, and
 * logged at most every 30 s with the number of frames dropped since the last line. */
static unsigned g_pool_drops;
static void log_drop(struct layer *l) {
    g_pool_drops++;
    l->drops_unlogged++;
    int64_t t = now_ns();
    if (!l->drop_logged_ns || t - l->drop_logged_ns > 30000000000LL) {
        wnc_log("layer", "%s: no free layer buffer (display still holds all %d): %u frame%s dropped%s", l->name,
                   l->pool_n, l->drops_unlogged, l->drops_unlogged == 1 ? "" : "s",
                   l->drop_logged_ns ? " since the last such line" : "");
        l->drop_logged_ns = t;
        l->drops_unlogged = 0;
    }
}

unsigned sc_layer_drops_take(void) {
    unsigned n = g_pool_drops;
    g_pool_drops = 0;
    return n;
}

int sc_layer_present_ahb(AHardwareBuffer *ahb, int w, int h, int acquire_fd, void *token, int scene_w, int scene_h,
                         const struct wnc_color *color, uint32_t ahb_format) {
    struct layer *l = layer_of(SC_LAYER_GAME);
    int r[8];
    if (!ahb || !token || ensure_sc(l) != 0) goto unavailable;
    int g = layer_geometry(w, h, scene_w, scene_h, r);
    if (g < 0) goto unavailable;
    if (g == 0) { if (acquire_fd >= 0) close(acquire_fd); sc_layer_hide(); return 1; }
    if (token == l->cur_token && l->shown && !l->recreate_pending) {
        /* The same frame again (the scene was redrawn for another reason): the display already
         * has it; only the placement may have changed (or, rarely, its description). */
        if (acquire_fd >= 0) close(acquire_fd);
        ASurfaceTransaction *tx = api.txCreate();
        if (tx) {
            apply_geometry(tx, l, r); apply_frame_rate(tx, l); apply_colour(tx, l, color);
            api.txApply(tx); api.txDelete(tx);
        }
        return 0;
    }
    ASurfaceTransaction *tx = api.txCreate();
    if (!tx) goto unavailable;
    ASurfaceControl *old = swap_sc_begin(l); /* this frame carries the recovery swap, if one is due */
    if (old) api.setZOrder(tx, l->sc, l->z);
    api.setBuffer(tx, l->sc, ahb, acquire_fd); /* the transaction owns the fence */
    api.setBufferTransparency(tx, l->sc, ASC_TRANSPARENCY_OPAQUE);
    apply_geometry(tx, l, r);
    apply_frame_rate(tx, l);
    apply_colour(tx, l, color);
    if (!l->shown) api.setVisibility(tx, l->sc, ASC_VISIBILITY_SHOW);
    if (old) retire_ops(tx, old);
    /* Same buffer, new SurfaceControl: it is NOT free, so no release is reported for it — the swap
     * moved it rather than taking it off screen. */
    if (add_complete_on(tx, l, old ? old : l->sc, l->cur_slot,
                        l->cur_token == token ? NULL : l->cur_token, old ? 1 : 0) != 0) {
        api.txDelete(tx); /* the fence went with the transaction */
        if (old) api.release(old);
        l->ds_applied = -1; l->md_applied = 0; l->hr_applied = -1.0f; /* the colour tag went with it too: re-send it next time */
        return -1;
    }
    api.txApply(tx);
    api.txDelete(tx);
    l->cur_slot = -1;
    l->cur_token = token;
    if (!l->shown) { l->shown = 1; wnc_log("layer", "%s: layer shown", l->name); }
    if (!l->first_logged) { l->first_logged = 1; vkp_signal_first_frame(); }
    if (color && color->dataspace) wnc_color_frame_shown(color, WNC_HDR_ZERO_COPY, ahb_format);
    return 0;
unavailable:
    if (acquire_fd >= 0) close(acquire_fd);
    return -1;
}

int sc_layer_present(struct vkp_image *src, int scene_w, int scene_h, const struct wnc_color *color) {
    struct layer *l = layer_of(SC_LAYER_GAME);
    if (!src || ensure_sc(l) != 0) return -1;
    int sw = vkp_image_width(src), sh = vkp_image_height(src);
    int r[8];
    int g = layer_geometry(sw, sh, scene_w, scene_h, r);
    if (g < 0) return -1;
    if (g == 0) { sc_layer_hide(); return 0; } /* nothing of it is on screen */

    int wait_fd;
    int idx = take_free_slot(l, sw, sh, AHB_RGBA8, &wait_fd);
    if (idx < 0) { log_drop(l); return 0; }
    if (vkp_blit_image(src, l->slots[idx].img, wait_fd) != 0) return -1;
    if (present_slot(l, idx, r, color) != 0) return -1;
    if (color && color->dataspace) wnc_color_frame_shown(color, WNC_HDR_LAYER_COPY, AHB_RGBA8);
    if (!l->first_logged) {
        l->first_logged = 1;
        wnc_log("layer", "presenting %dx%d game frames on their own SurfaceControl layer (%s pool, %d buffers); "
                   "HUD and pointer stay Android views above it", sw, sh,
                   l->pool_modifier == MOD_QCOM_COMPRESSED ? "UBWC" : "linear", l->pool_n);
        vkp_signal_first_frame();
    }
    return 0;
}

int sc_layer_present_pass(const struct vkp_draw *draws, int n, int scene_w, int scene_h) {
    struct layer *l = layer_of(SC_LAYER_GAME);
    int rw = 0, rh = 0, r[8];
    if (!draws || n <= 0 || ensure_sc(l) != 0) return -1;
    if (vkp_update_map(scene_w, scene_h) != 0) return -1;
    /* Compose + run the effects chain first: the chain's result size (a scaling mode resizes to
     * the scene's mapped output size) decides how big the layer buffer has to be. */
    if (vkp_pass_begin(scene_w, scene_h, draws, n, &rw, &rh) != 0) return -1;
    if (!vkp_map_rect(rw, rh, scene_w, scene_h, r)) { vkp_pass_abort(); sc_layer_hide(); return 0; }
    int wait_fd;
    int idx = take_free_slot(l, rw, rh, AHB_RGBA8, &wait_fd);
    if (idx < 0) { vkp_pass_abort(); log_drop(l); return 0; }
    if (vkp_pass_copy_to(l->slots[idx].img, wait_fd) != 0) return -1;
    if (present_slot(l, idx, r, NULL) != 0) return -1; /* the effects chain's result is 8-bit sRGB */
    if (!l->first_logged) {
        l->first_logged = 1;
        wnc_log("layer", "presenting %dx%d frames on their own SurfaceControl layer (%s pool, %d buffers); "
                   "HUD and pointer stay Android views above it", rw, rh,
                   l->pool_modifier == MOD_QCOM_COMPRESSED ? "UBWC" : "linear", l->pool_n);
        vkp_signal_first_frame();
    }
    return 0;
}

/* ---- the HDR picture (hdr_compose.h) ------------------------------------------------------------
 * An HDR game that cannot be alone on its layer: the WHOLE scene, composed into one 10-bit PQ BT.2020
 * picture with the effects applied, goes onto the game layer - one layer, tagged BT2020_PQ with the
 * game's metadata, so the display composes it as HDR (and a window above the game never needs the
 * second layer that costs this panel its hardware composition). 10-bit buffers; if gralloc or the
 * import refuses those, 8-bit ones carry the same tagged picture (colours right, precision less).
 * With the drawer's HDR output switch off (hf->tonemap) the same picture is composed tone-mapped to
 * sRGB instead and goes on the ordinary 8-bit buffers, UNtagged - an SDR frame like any other. */
static uint32_t g_hdr_pool_fmt = AHB_RGB10A2;

int sc_layer_present_hdr_scene(const struct vkp_draw *draws, int n, const struct vkp_hdr_frame *hf,
                               int scene_w, int scene_h, const struct wnc_color *color) {
    struct layer *l = layer_of(SC_LAYER_GAME);
    int rw = 0, rh = 0, r[8];
    if (!draws || n <= 0 || !hf || ensure_sc(l) != 0) return -1;
    if (vkp_update_map(scene_w, scene_h) != 0) return -1;
    if (vkp_pass_begin_hdr(scene_w, scene_h, draws, n, hf, &rw, &rh) != 0) return -1;
    if (!vkp_map_rect(rw, rh, scene_w, scene_h, r)) { vkp_pass_abort(); sc_layer_hide(); return 0; }
    const int tm = hf->tonemap;
    const uint32_t fmt = tm ? AHB_RGBA8 : g_hdr_pool_fmt;
    g_alloc_failed = 0;
    int wait_fd;
    int idx = take_free_slot(l, rw, rh, fmt, &wait_fd);
    if (idx < 0 && g_alloc_failed && fmt == AHB_RGB10A2) {
        g_hdr_pool_fmt = AHB_RGBA8;
        wnc_log("color", "%s: this device will not make a 10-bit layer buffer (RGBA1010102): the HDR picture goes "
                   "on 8-bit buffers instead (still tagged BT2020_PQ; less precision)", l->name);
        idx = take_free_slot(l, rw, rh, g_hdr_pool_fmt, &wait_fd);
    }
    if (idx < 0) { vkp_pass_abort(); log_drop(l); return 0; }
    if (vkp_pass_copy_to(l->slots[idx].img, wait_fd) != 0) return -1;
    if (present_slot(l, idx, r, tm ? NULL : color) != 0) return -1; /* tone-mapped = plain sRGB: no tag */
    if (color && color->dataspace)
        wnc_color_frame_shown(color, tm ? WNC_HDR_TONEMAPPED : WNC_HDR_COMPOSED, tm ? AHB_RGBA8 : g_hdr_pool_fmt);
    static int said = -1;
    if (said != tm) {
        said = tm;
        if (tm)
            wnc_log("color", "tone-mapped picture on the game's display layer: %dx%d, %s 8-bit buffers, untagged "
                       "(sRGB) - HDR output is switched off", rw, rh,
                       l->pool_modifier == MOD_QCOM_COMPRESSED ? "UBWC" : "linear");
        else {
            char cov[96];
            coverage_text(l, cov, sizeof(cov));
            wnc_log("color", "HDR picture on its own display layer: %dx%d, %s %s buffers, tagged %s%s%s", rw, rh,
                       l->pool_modifier == MOD_QCOM_COMPRESSED ? "UBWC" : "linear",
                       g_hdr_pool_fmt == AHB_RGB10A2 ? "10-bit" : "8-bit",
                       color && color->dataspace == WNC_ADATASPACE_BT2020_PQ ? "BT2020_PQ" : "HDR",
                       cov[0] ? ", " : "", cov);
        }
    }
    if (!l->first_logged) { l->first_logged = 1; vkp_signal_first_frame(); }
    return 0;
}

/* ---- is a SECOND display layer affordable on this display? -----------------------------------
 * MEASURED on the Pocket FIT (2026-09-14, three times): while a second layer is up, this hardware
 * composer hands the WHOLE frame back to the GPU (`DEVICE/CLIENT`), and nothing short of re-creating
 * the app's window brings it back — so on this display the overlay layer costs more than it saves,
 * every time, for the rest of the session. Where it costs nothing it is still the better path (the
 * game keeps copy-free frames with a window on top), so this is a GATE, not a removal.
 *
 * The gate is read from what the layer path actually knows, never from a device or panel list:
 *   - rotation: vkp_surface_rotation_degrees(), i.e. VkSurfaceCapabilitiesKHR::currentTransform —
 *     what the presentation engine says it does to every layer we hand it;
 *   - scale: the GAME layer's own src -> dst rectangles, the ones passed to setGeometry.
 * Rotated AND scaled is the combination that was measured to cost composition (the DPU's rotator
 * has to take a scaled source); either alone, or neither, is left as it was.
 *
 * Why not just measure the composition type after raising the layer and back out if it comes back
 * CLIENT — which would beat predicting? Because no app can read it. The composition type lives in
 * the Composer HAL; ASurfaceTransactionStats exposes latch time and fences and nothing else, and
 * the only place the value is published is `dumpsys android.hardware.graphics.composer3.IComposer`,
 * which needs android.permission.DUMP and string-parsing. It would also arrive at least a frame
 * late, so backing out would itself be the visible change we are avoiding. Hence the rule. */
static int overlay_affordable(int *deg, int sw[2], int dw[2]) {
    *deg = vkp_surface_rotation_degrees();
    sw[0] = sw[1] = dw[0] = dw[1] = 0;
    if (!g_game_geo_known) return 1;    /* the game has never been placed: nothing to weigh against */
    if (*deg <= 0) return 1;            /* unknown (-1) or upright: leave today's behaviour alone */
    sw[0] = g_game_src.right - g_game_src.left; sw[1] = g_game_src.bottom - g_game_src.top;
    dw[0] = g_game_dst.right - g_game_dst.left; dw[1] = g_game_dst.bottom - g_game_dst.top;
    if (sw[0] <= 0 || sw[1] <= 0) return 1;
    return !(dw[0] != sw[0] || dw[1] != sw[1]); /* rotated AND scaled -> not affordable */
}

/* -1 = not decided yet, 0 = raising the overlay, 1 = declining it. Logged on every change, so a
 * tester's log says why they are seeing the copy path instead of two layers — and says it again if
 * the placement changes the answer (e.g. a fullscreen mode that stops scaling the game). */
static int g_overlay_declined = -1;

int sc_layer_overlay_affordable(void) {
    layers_init();
    int deg, sr[2], ds[2];
    int ok = overlay_affordable(&deg, sr, ds);
    int want = ok ? 0 : 1;
    if (want != g_overlay_declined) {
        int first = g_overlay_declined < 0;
        g_overlay_declined = want;
        if (want)
            wnc_log("layer", "overlay layer declined: this display rotates every layer %d° and the game "
                       "layer is scaled %dx%d -> %dx%d, and on that combination a second layer drops the "
                       "whole frame to GPU composition for the rest of the session (measured). The window "
                       "above the game goes on the copy path instead - same picture, one blit.",
                       deg, sr[0], sr[1], ds[0], ds[1]);
        else if (!first)
            wnc_log("layer", "overlay layer allowed again: the game layer is no longer both rotated and "
                       "scaled, so a second display layer costs nothing here");
    }
    return ok;
}

int sc_layer_present_overlay(struct vkp_image *src, const int geo[8]) {
    struct layer *l = layer_of(SC_LAYER_OVERLAY);
    if (!src || !geo) return -1;
    /* The caller decides with sc_layer_overlay_affordable() BEFORE it commits the game to a layer;
     * this is only a guard so the layer can never go up behind that decision's back. */
    if (!sc_layer_overlay_affordable()) return -1;
    if (ensure_sc(l) != 0) return -1;
    int sw = vkp_image_width(src), sh = vkp_image_height(src);
    if (sw <= 0 || sh <= 0) return -1;
    int wait_fd;
    int idx = take_free_slot(l, sw, sh, AHB_RGBA8, &wait_fd);
    if (idx < 0) { log_drop(l); return 0; }
    /* The window is copied into the layer buffer 1:1; `geo` crops it and places it, so the layer
     * is exactly the window's rectangle on screen and nothing else is blended anywhere. */
    if (vkp_blit_image(src, l->slots[idx].img, wait_fd) != 0) return -1;
    if (present_slot(l, idx, geo, NULL) != 0) return -1;
    if (!l->first_logged) {
        l->first_logged = 1;
        log_layer_count();
    }
    return 0;
}

/* Hide one layer: the buffer comes off with it (a hidden layer would keep the game's buffer - or
 * the pool slot - referenced, and the game needs it back to keep presenting the other way). */
static void hide_layer(struct layer *l) {
    if (!l->sc || !l->shown || api.state != 1) return;
    ASurfaceTransaction *tx = api.txCreate();
    if (!tx) return;
    replace_with_blank(tx, l);
    api.setVisibility(tx, l->sc, ASC_VISIBILITY_HIDE);
    add_complete(tx, l, l->cur_slot, l->cur_token, 0);
    api.txApply(tx); api.txDelete(tx);
    l->shown = 0; l->cur_slot = -1; l->cur_token = NULL;
}

void sc_layer_hide(void) {
    layers_init();
    int said = g_layers[SC_LAYER_GAME].shown || g_layers[SC_LAYER_OVERLAY].sc;
    /* Top down, so nothing of the scene is ever uncovered for a frame. The overlay layer is let go
     * of entirely rather than hidden (same reason as sc_layer_hide_overlay: a live second
     * SurfaceControl keeps SurfaceFlinger composing on the GPU); the game layer keeps its
     * SurfaceControl, since it goes up and down with every effects/frame-generation toggle. */
    if (g_layers[SC_LAYER_OVERLAY].sc) sc_layer_hide_overlay();
    if (g_layers[SC_LAYER_GAME].shown) hide_layer(&g_layers[SC_LAYER_GAME]);
    if (said) wnc_log("layer", "layers hidden (scene is not a single fullscreen window)");
}

void sc_layer_hide_overlay(void) {
    layers_init();
    struct layer *l = &g_layers[SC_LAYER_OVERLAY];
    if (!l->sc) return;
    /* RETIRED, not just hidden, so the overlay layer lives exactly as long as the window above the
     * game: a live second SurfaceControl is what puts SurfaceFlinger into GPU client composition
     * on this hardware (see sc_layer.h), and a hidden one is still live. Measured caveat: letting
     * it go does NOT by itself bring hardware composition back - on the Pocket FIT the fallback
     * outlives the overlay and only clears when the GAME layer's SurfaceControl is re-created
     * (HOME + resume). Retiring is still the right thing; it just is not the whole cure. The pool
     * buffers stay allocated for the next window. */
    if (l->shown) hide_layer(l);
    retire_sc(l);
    wnc_log("layer", "%s: gone (nothing is above the game any more)", l->name);
    /* ...and that is the half the measurement said is not enough: arm the game layer's
     * SurfaceControl swap, which the next frame performs (swap_sc_begin). */
    if (g_layers[SC_LAYER_GAME].sc) g_layers[SC_LAYER_GAME].recreate_pending = 1;
}

void sc_layer_window_gone(void) {
    if (api.state != 1) return;
    layers_init();
    for (int i = SC_LAYER_COUNT - 1; i >= 0; i--) retire_sc(&g_layers[i]);
    drain_and_free_pools();
}

unsigned sc_layer_frames_take(void) {
    unsigned n = g_stat_layer_frames;
    g_stat_layer_frames = 0;
    return n;
}
