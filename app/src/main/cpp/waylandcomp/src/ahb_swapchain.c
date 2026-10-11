/* Zero-copy game frames: banner_ahb_v1 + the game's AHardwareBuffers on the sc_layer SurfaceControl.
 * See ahb_swapchain.h and ZERO_COPY_SPIKE.md. */
#define _GNU_SOURCE
#include "ahb_swapchain.h"
#include "color_mgmt.h"
#include "desktop_ext.h"
#include "sc_layer.h"
#include "vk_present.h"
#include "ahb-v1-server-protocol.h"
#include <android/hardware_buffer.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define MOD_QCOM_COMPRESSED 0x0500000000000001ULL /* DRM_FORMAT_MOD_QCOM_COMPRESSED (UBWC) */

/* linux/dma-buf.h (not in the NDK sysroot's older headers): sync_file export / import. */
struct wnc_dma_buf_sync_file { uint32_t flags; int32_t fd; };
#define WNC_DMA_BUF_SYNC_READ 1u
#define WNC_DMA_BUF_IOCTL_EXPORT_SYNC_FILE _IOWR('b', 2, struct wnc_dma_buf_sync_file)
#define WNC_DMA_BUF_IOCTL_IMPORT_SYNC_FILE _IOW('b', 3, struct wnc_dma_buf_sync_file)

extern volatile int g_zero_copy;

/* One per wl_buffer the game attached an AHardwareBuffer to. Lives while the wl_buffer exists or
 * the buffer is still on the layer / held by SurfaceFlinger, whichever is longer. */
struct ahb_buf {
    uint64_t id;                        /* the token sc_layer.c hands back */
    struct dmabuf_buffer *b;            /* one reference held for the life of this record */
    AHardwareBuffer *ahb;               /* our reference to the game's buffer */
    int w, h;
    uint32_t stride, image_count;
    uint32_t format;                    /* AHARDWAREBUFFER_FORMAT_* gralloc gave it (10-bit for HDR10) */
    uint64_t modifier;
    struct wl_client *client;
    struct wl_resource *resource;       /* the wl_buffer; NULL once the client destroyed it */
    struct wl_listener resource_destroy;
    int on_layer;                       /* set on the SurfaceControl (or not yet released by SurfaceFlinger) */
    int release_pending;                /* the surface let go of it while on the layer */
    int64_t deferred_ns;                /* when it did (the perf line: how long the display kept it) */
    struct surface *surface;            /* for the paced release; NULL = release at once */
    struct wl_event_source *fence_src;  /* fallback: waiting the release fence in the event loop */
    int fence_fd;
    struct wl_list link;
};

static struct wl_list g_bufs;
static struct wl_list g_clients;             /* bound banner_ahb_v1 resources (wl_resource links) */
static uint64_t g_next_id = 1;
static struct wl_event_loop *g_loop;
static unsigned g_stat_zero_copy;
static int g_export_failed_logged, g_import_failed_logged;
static int g_advertised;                     /* the global exists */
static int g_mode_sent = -1;                 /* the mode clients were last told (-1 = none yet) */
static struct surface *g_announced;          /* "presenting X without a copy" said for this surface */
static volatile int64_t g_last_zero_copy_ns; /* when a frame last went on the layer without a copy */

/* SurfaceFlinger's callback thread -> compositor thread: released tokens + their fences. */
struct released { uint64_t id; int fd; };
static pthread_mutex_t g_rel_lock = PTHREAD_MUTEX_INITIALIZER;
static struct released *g_rel;
static int g_rel_n, g_rel_cap;
static int g_rel_pipe[2] = {-1, -1};

/* Which swapchains were announced (one log line per swapchain, not per image). */
struct chain_seen { struct wl_client *client; int w, h; uint32_t image_count; uint64_t modifier; uint32_t format; };
static struct chain_seen g_last_chain;

static int64_t now_ns(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static struct ahb_buf *find_buf(uint64_t id) {
    struct ahb_buf *ab;
    wl_list_for_each(ab, &g_bufs, link)
        if (ab->id == id) return ab;
    return NULL;
}

static void buf_free(struct ahb_buf *ab) {
    if (ab->resource) { wl_list_remove(&ab->resource_destroy.link); ab->resource = NULL; }
    if (ab->fence_src) { wl_event_source_remove(ab->fence_src); ab->fence_src = NULL; }
    if (ab->fence_fd >= 0) { close(ab->fence_fd); ab->fence_fd = -1; }
    wl_list_remove(&ab->link);
    *wnc_dmabuf_ahb_slot(ab->b) = NULL;
    AHardwareBuffer_release(ab->ahb);
    wnc_dmabuf_unref(ab->b);
    free(ab);
}

/* Free once neither the client (wl_buffer) nor the display (layer) refers to it. */
static void maybe_free(struct ahb_buf *ab) {
    if (!ab->resource && !ab->on_layer && !ab->fence_src) buf_free(ab);
}

static void on_buffer_resource_destroyed(struct wl_listener *l, void *data) {
    struct ahb_buf *ab = wl_container_of(l, ab, resource_destroy);
    wl_list_remove(&ab->resource_destroy.link);
    ab->resource = NULL;
    ab->release_pending = 0; /* nothing left to release to */
    maybe_free(ab);
}

/* ---- release: SurfaceFlinger is done with the buffer -> the game's next acquire waits, then wl_buffer.release */

static void send_deferred_release(struct ahb_buf *ab) {
    if (ab->release_pending && ab->resource)
        wnc_release_buffer(ab->surface, ab->resource, ab->surface != NULL, ab->deferred_ns);
    ab->release_pending = 0;
    ab->surface = NULL;
}

static int on_fence_readable(int fd, uint32_t mask, void *data) {
    struct ahb_buf *ab = data;
    wl_event_source_remove(ab->fence_src);
    ab->fence_src = NULL;
    close(ab->fence_fd);
    ab->fence_fd = -1;
    send_deferred_release(ab);
    maybe_free(ab);
    return 0;
}

/* Compositor thread. Puts the display's release fence into the dma-buf so the game's driver waits
 * on it before rendering into the buffer again (Mesa exports every fence of the dma-buf as the
 * acquire semaphore), then gives the buffer back. If the kernel refuses the import, the release
 * itself waits for the fence instead. */
static void handle_released(uint64_t id, int fd) {
    struct ahb_buf *ab = find_buf(id);
    if (!ab) { if (fd >= 0) close(fd); return; }
    ab->on_layer = 0;
    if (fd >= 0) {
        struct wnc_dma_buf_sync_file imp = {.flags = WNC_DMA_BUF_SYNC_READ, .fd = fd};
        int dmabuf_fd = wnc_dmabuf_fd(ab->b);
        if (dmabuf_fd >= 0 && ioctl(dmabuf_fd, WNC_DMA_BUF_IOCTL_IMPORT_SYNC_FILE, &imp) == 0) {
            close(fd);
        } else {
            if (!g_import_failed_logged) {
                g_import_failed_logged = 1;
                wnc_log("layer", "zero-copy: DMA_BUF_IOCTL_IMPORT_SYNC_FILE failed (%s): releases wait for the display's fence here instead",
                           strerror(errno));
            }
            /* Wait it out in the event loop (a sync_file is readable once signalled). */
            struct pollfd p = {.fd = fd, .events = POLLIN};
            if (poll(&p, 1, 0) > 0) {
                close(fd);
            } else {
                ab->fence_fd = fd;
                ab->fence_src = wl_event_loop_add_fd(g_loop, fd, WL_EVENT_READABLE, on_fence_readable, ab);
                if (!ab->fence_src) { close(fd); ab->fence_fd = -1; }
                else return; /* released from on_fence_readable */
            }
        }
    }
    send_deferred_release(ab);
    maybe_free(ab);
}

static int on_release_pipe(int fd, uint32_t mask, void *data) {
    char buf[64];
    while (read(fd, buf, sizeof(buf)) > 0) {}
    for (;;) {
        struct released r;
        pthread_mutex_lock(&g_rel_lock);
        if (g_rel_n == 0) { pthread_mutex_unlock(&g_rel_lock); break; }
        r = g_rel[0];
        memmove(g_rel, g_rel + 1, (size_t)(--g_rel_n) * sizeof(*g_rel));
        pthread_mutex_unlock(&g_rel_lock);
        handle_released(r.id, r.fd);
    }
    return 0;
}

/* SurfaceFlinger's callback thread. */
void ahb_swapchain_layer_released(void *token, int release_fd) {
    pthread_mutex_lock(&g_rel_lock);
    if (g_rel_n == g_rel_cap) {
        int cap = g_rel_cap ? g_rel_cap * 2 : 16;
        struct released *n = realloc(g_rel, (size_t)cap * sizeof(*n));
        if (!n) { pthread_mutex_unlock(&g_rel_lock); if (release_fd >= 0) close(release_fd); return; }
        g_rel = n; g_rel_cap = cap;
    }
    g_rel[g_rel_n++] = (struct released){(uint64_t)(uintptr_t)token, release_fd};
    pthread_mutex_unlock(&g_rel_lock);
    if (g_rel_pipe[1] >= 0) { char c = 1; if (write(g_rel_pipe[1], &c, 1) < 0) {} }
}

/* ---- present */

int ahb_swapchain_has_ahb(const struct dmabuf_buffer *b) {
    return b && *wnc_dmabuf_ahb_slot((struct dmabuf_buffer *)b) != NULL;
}

int ahb_swapchain_present(struct dmabuf_buffer *b, struct surface *s, int scene_w, int scene_h) {
    struct ahb_buf *ab = b ? *wnc_dmabuf_ahb_slot(b) : NULL;
    if (!ab) return -1;
    int dmabuf_fd = wnc_dmabuf_fd(b);
    /* Acquire fence: the game's render fence, which its driver put into the dma-buf before the
     * commit (Mesa's implicit sync). The display waits on it, not the CPU. */
    int acquire = -1;
    struct wnc_dma_buf_sync_file exp = {.flags = WNC_DMA_BUF_SYNC_READ, .fd = -1};
    if (dmabuf_fd >= 0 && ioctl(dmabuf_fd, WNC_DMA_BUF_IOCTL_EXPORT_SYNC_FILE, &exp) == 0 && exp.fd >= 0) {
        acquire = exp.fd;
    } else if (dmabuf_fd >= 0) {
        if (!g_export_failed_logged) {
            g_export_failed_logged = 1;
            wnc_log("layer", "zero-copy: DMA_BUF_IOCTL_EXPORT_SYNC_FILE failed (%s): waiting for each frame on the CPU instead",
                       strerror(errno));
        }
        struct pollfd p = {.fd = dmabuf_fd, .events = POLLIN}; /* readable = the writers are done */
        int r;
        do { r = poll(&p, 1, 100); } while (r < 0 && errno == EINTR);
    }
    int r = sc_layer_present_ahb(ab->ahb, ab->w, ab->h, acquire, (void *)(uintptr_t)ab->id, scene_w, scene_h,
                                 s ? wnc_surface_color(s) : NULL, ab->format);
    if (r < 0) return -1;
    if (r == 1) return 0; /* nothing of it on screen: not on the layer, nothing to release later */
    if (!ab->on_layer) {
        ab->on_layer = 1;
        g_stat_zero_copy++;
        g_last_zero_copy_ns = now_ns();
    }
    if (s && g_announced != s) {
        char name[160];
        g_announced = s;
        wnc_surface_describe(s, name, sizeof(name));
        wnc_log("layer", "zero-copy: presenting %s without a copy", name);
    }
    return 0;
}

int ahb_swapchain_last_frame_age_ms(void) {
    int64_t t = g_last_zero_copy_ns;
    if (!t) return -1;
    int64_t age = (now_ns() - t) / 1000000LL;
    return age > INT32_MAX ? INT32_MAX : (int)age;
}

int ahb_swapchain_defer_release(struct dmabuf_buffer *b, struct wl_resource *buffer, struct surface *s, int paced) {
    struct ahb_buf *ab = b ? *wnc_dmabuf_ahb_slot(b) : NULL;
    if (!ab || !ab->on_layer || !buffer || ab->resource != buffer) return 0;
    ab->release_pending = 1;
    ab->deferred_ns = now_ns();
    ab->surface = paced ? s : NULL;
    return 1;
}

void ahb_swapchain_surface_gone(struct surface *s) {
    struct ahb_buf *ab;
    wl_list_for_each(ab, &g_bufs, link)
        if (ab->surface == s) ab->surface = NULL;
}

unsigned ahb_swapchain_stats_take(void) {
    unsigned n = g_stat_zero_copy;
    g_stat_zero_copy = 0;
    return n;
}

int ahb_swapchain_advertised(void) { return g_advertised; }

uint32_t ahb_swapchain_ahb_format(const struct dmabuf_buffer *b) {
    struct ahb_buf *ab = b ? *wnc_dmabuf_ahb_slot((struct dmabuf_buffer *)b) : NULL;
    return ab ? ab->format : 0;
}

/* ---- banner_ahb_v1 */

static void ahb_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }

static void ahb_attach(struct wl_client *c, struct wl_resource *r, struct wl_resource *buffer, int32_t sock,
                       uint32_t width, uint32_t height, uint32_t stride, uint32_t mod_hi, uint32_t mod_lo,
                       uint32_t image_count) {
    struct dmabuf_buffer *b = wnc_dmabuf_from_resource(buffer);
    uint64_t modifier = ((uint64_t)mod_hi << 32) | mod_lo;
    if (!b) {
        wnc_log("layer", "zero-copy: %s attached an AHardwareBuffer to a non-dma-buf wl_buffer, ignored", wnc_client_name(c));
        close(sock);
        return;
    }
    void **slot = wnc_dmabuf_ahb_slot(b);
    if (*slot) { close(sock); return; } /* once per buffer */

    /* The handle was written into the socket before the request was sent; a short wait is just
     * insurance against a slow sender. */
    struct pollfd p = {.fd = sock, .events = POLLIN};
    int pr;
    do { pr = poll(&p, 1, 1000); } while (pr < 0 && errno == EINTR);
    AHardwareBuffer *ahb = NULL;
    int rc = pr > 0 ? AHardwareBuffer_recvHandleFromUnixSocket(sock, &ahb) : -1;
    close(sock);
    if (rc != 0 || !ahb) {
        wnc_log("layer", "zero-copy: receiving %s's AHardwareBuffer failed (%s)", wnc_client_name(c),
                   pr > 0 ? "recvHandleFromUnixSocket" : "nothing on the socket");
        return;
    }
    AHardwareBuffer_Desc d;
    AHardwareBuffer_describe(ahb, &d);
    int bw, bh;
    wnc_dmabuf_size(b, &bw, &bh);
    if ((int)d.width != bw || (int)d.height != bh || (int)width != bw || (int)height != bh) {
        wnc_log("layer", "zero-copy: %s's AHardwareBuffer is %ux%u but its wl_buffer %dx%d, ignored",
                   wnc_client_name(c), d.width, d.height, bw, bh);
        AHardwareBuffer_release(ahb);
        return;
    }

    struct ahb_buf *ab = calloc(1, sizeof(*ab));
    if (!ab) { AHardwareBuffer_release(ahb); wl_client_post_no_memory(c); return; }
    ab->id = g_next_id++;
    ab->b = b;
    wnc_dmabuf_ref(b);
    ab->ahb = ahb;
    ab->w = bw; ab->h = bh;
    ab->stride = d.stride ? d.stride : stride;
    ab->image_count = image_count;
    ab->format = d.format;
    ab->modifier = modifier;
    ab->client = c;
    ab->resource = buffer;
    ab->resource_destroy.notify = on_buffer_resource_destroyed;
    wl_resource_add_destroy_listener(buffer, &ab->resource_destroy);
    ab->fence_fd = -1;
    wl_list_insert(g_bufs.prev, &ab->link);
    *slot = ab;

    if (g_last_chain.client != c || g_last_chain.w != bw || g_last_chain.h != bh ||
        g_last_chain.image_count != image_count || g_last_chain.modifier != modifier || g_last_chain.format != d.format) {
        g_last_chain = (struct chain_seen){c, bw, bh, image_count, modifier, d.format};
        wnc_log("layer", "zero-copy: AHB swapchain from %s (%u images, %dx%d, %s, %s, stride %u px)", wnc_client_name(c),
                   image_count, bw, bh, wnc_ahb_format_name(d.format), modifier == MOD_QCOM_COMPRESSED ? "UBWC (QCOM_COMPRESSED)"
                                       : modifier == 0 ? "linear" : "unknown modifier", ab->stride);
    }
}

static const struct banner_ahb_v1_interface ahb_impl = {
    .destroy = ahb_destroy,
    .attach = ahb_attach,
};

static void on_client_resource_destroyed(struct wl_resource *r) {
    wl_list_remove(wl_resource_get_link(r));
}

static void bind_ahb(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    static struct wl_client *last_named;
    if (ver > 2) ver = 2;
    struct wl_resource *r = wl_resource_create(c, &banner_ahb_v1_interface, (int)ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &ahb_impl, NULL, on_client_resource_destroyed);
    wl_list_insert(&g_clients, wl_resource_get_link(r));
    /* The mode travels with the bind, so the client's registry roundtrip already has it when it
     * decides about its first swapchain. */
    if (ver >= WNC_AHB_V1_MODE_SINCE_VERSION) banner_ahb_v1_send_mode(r, g_zero_copy ? 1u : 0u);
    /* One line per program, not per surface-format query (each binds its own). */
    if (last_named != c) {
        last_named = c;
        wnc_log("layer", "zero-copy: %s bound banner_ahb_v1 version %u (%s)", wnc_client_name(c), ver,
                   ver >= 2 ? "follows the live switch" : "version 1: decides from its launch environment only");
    }
}

void ahb_swapchain_set_mode(int on, int live) {
    on = on ? 1 : 0;
    g_zero_copy = on;
    if (!g_advertised) {
        if (live) wnc_log("layer", "zero-copy switched %s from the drawer, but no display layer is available on this device: no change",
                             on ? "on" : "off");
        return;
    }
    if (g_mode_sent == on) return;
    g_mode_sent = on;
    int told = 0;
    struct wl_resource *r;
    wl_resource_for_each(r, &g_clients) {
        if (wl_resource_get_version(r) >= WNC_AHB_V1_MODE_SINCE_VERSION) {
            banner_ahb_v1_send_mode(r, (uint32_t)on);
            told++;
        }
    }
    /* The next chain and the next fullscreen frame get announced again. */
    memset(&g_last_chain, 0, sizeof(g_last_chain));
    g_announced = NULL;
    if (live)
        wnc_log("layer", "zero-copy switched %s from the drawer: %d bound program%s told to rebuild their swapchains%s",
                   on ? "on" : "off", told, told == 1 ? "" : "s",
                   on ? "; frames go on the display layer once the new gralloc swapchain is up"
                      : "; gralloc frames still in flight stay on the layer (or take the copy path) until then");
    else
        wnc_log("layer", "zero-copy: %s at launch (%s)", on ? "on" : "off",
                   on ? "BANNER_WAYLAND_ZERO_COPY=1: games present their own gralloc buffers on the display layer"
                      : "the drawer's Zero-copy presentation switch turns it on live");
    if (live) wl_display_flush_clients(wnc_get_display());
    wnc_request_redraw();
}

void ahb_swapchain_init(struct wl_display *display) {
    wl_list_init(&g_bufs);
    wl_list_init(&g_clients);
    if (!sc_layer_available()) {
        wnc_log("layer", "zero-copy: no display layer on this device (see the line above): banner_ahb_v1 not advertised");
        return;
    }
    g_loop = wl_display_get_event_loop(display);
    if (pipe2(g_rel_pipe, O_CLOEXEC | O_NONBLOCK) != 0) {
        wnc_log("error", "zero-copy: release pipe creation failed (%s); zero-copy presents disabled", strerror(errno));
        g_rel_pipe[0] = g_rel_pipe[1] = -1;
        return;
    }
    wl_event_loop_add_fd(g_loop, g_rel_pipe[0], WL_EVENT_READABLE, on_release_pipe, NULL);
    if (!wl_global_create(display, &banner_ahb_v1_interface, 2, NULL, bind_ahb)) {
        wnc_log("error", "zero-copy: banner_ahb_v1 global creation failed");
        return;
    }
    g_advertised = 1;
    wnc_log("layer", "zero-copy: banner_ahb_v1 version 2 advertised (games built for it present their own gralloc buffers while the switch is on)");
    ahb_swapchain_set_mode(g_zero_copy, 0);
}
