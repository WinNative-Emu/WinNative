/*
 * Clipboard: wl_data_device_manager (core, v3) and zwlr_data_control_manager_v1 (v1), selection
 * only — drag and drop is refused. Both protocols share one selection:
 *
 *   owner CLIENT  a program's wl_data_source / zwlr_data_control_source_v1 (its mime list)
 *   owner HOST    text the app pushed from Android's clipboard (offered under the text mimes)
 *   owner NONE    nothing
 *
 * Every selection change is announced to every data-control device (they see the selection
 * regardless of focus, as the protocol says) and to the wl_data_devices of the client holding
 * keyboard focus; a client also gets the current selection when its keyboard focus arrives.
 *
 * winewayland prefers zwlr_data_control (it then runs the clipboard from the desktop process
 * without needing keyboard focus) and tags its own offers with application/x.winewayland.tag
 * so it can ignore them when they come back; we pass mime lists through untouched.
 *
 * Guest → Android: when a program sets a selection with a text mime, the compositor asks for it
 * once over a pipe on the event loop (non-blocking, 1 MiB cap, 3 s timeout) and hands the text
 * to the app (wnc_on_clipboard_text → ClipboardManager). Android → guest: the app's text
 * becomes the HOST selection; receive requests are answered with a non-blocking writer.
 */
#define _GNU_SOURCE 1
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include "desktop_ext.h"
#include "wlr-data-control-unstable-v1-server-protocol.h"

#define CLIP_MAX_BYTES (1024 * 1024)
#define CLIP_READ_TIMEOUT_MS 3000
#define CLIP_WRITE_TIMEOUT_MS 5000
#define MAX_MIMES 64

static struct wl_display *g_disp;

enum owner { OWNER_NONE, OWNER_CLIENT, OWNER_HOST };
static enum owner g_owner;
static uint32_t g_gen;                          /* bumps on every selection change */

struct source {                                 /* a client's data source (either protocol) */
    struct wl_resource *res;
    int is_control;
    char *mimes[MAX_MIMES];
    int nmimes;
};
static struct source *g_src;                    /* current CLIENT selection */

static char *g_host_text;                       /* current HOST selection */
static size_t g_host_len;
static const char *const k_text_mimes[] = {
    "text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "TEXT", "STRING", NULL
};

struct device {
    struct wl_resource *res;
    int is_control;
    struct wl_list link;
};
static struct wl_list g_devices;
static struct wl_client *g_kb_client;           /* keyboard focus holder */

struct offer {
    struct wl_resource *res;
    int is_control;
    uint32_t gen;                               /* the selection this offer describes */
};

static char *g_last_guest_text;                 /* echo guard: the last text handed to Android */
static size_t g_last_guest_len;

/* ------------------------------------------------------------------ helpers */

static int is_text_mime(const char *m) {
    for (int i = 0; k_text_mimes[i]; i++) if (!strcmp(m, k_text_mimes[i])) return 1;
    return 0;
}

static const char *source_text_mime(const struct source *s) {
    for (int i = 0; k_text_mimes[i]; i++)
        for (int j = 0; j < s->nmimes; j++)
            if (!strcmp(s->mimes[j], k_text_mimes[i])) return s->mimes[j];
    return NULL;
}

static void source_send_send(struct source *s, const char *mime, int fd) {
    if (s->is_control) zwlr_data_control_source_v1_send_send(s->res, mime, fd);
    else wl_data_source_send_send(s->res, mime, fd);
}

static void source_send_cancelled(struct source *s) {
    if (s->is_control) zwlr_data_control_source_v1_send_cancelled(s->res);
    else wl_data_source_send_cancelled(s->res);
}

/* ------------------------------------------------------------------ host → client writer */

struct writer {
    int fd;
    char *data;
    size_t len, off;
    struct wl_event_source *src, *timer;
};

static void writer_free(struct writer *w) {
    if (w->src) wl_event_source_remove(w->src);
    if (w->timer) wl_event_source_remove(w->timer);
    close(w->fd);
    free(w->data);
    free(w);
}

static int writer_pump(struct writer *w) {
    while (w->off < w->len) {
        ssize_t n = write(w->fd, w->data + w->off, w->len - w->off);
        if (n > 0) { w->off += (size_t)n; continue; }
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return 0;   /* wait */
        if (n < 0 && errno == EINTR) continue;
        break;                                                             /* gone */
    }
    return 1;
}

static int on_writer_writable(int fd, uint32_t mask, void *data) {
    struct writer *w = data;
    if (mask & (WL_EVENT_ERROR | WL_EVENT_HANGUP) || writer_pump(w)) writer_free(w);
    return 0;
}

static int on_writer_timeout(void *data) {
    struct writer *w = data;
    wnc_log("clipboard", "a program never read the clipboard text it asked for; giving up");
    writer_free(w);
    return 0;
}

static void writer_start(int fd, const char *data, size_t len) {
    struct writer *w = calloc(1, sizeof(*w));
    if (!w) { close(fd); return; }
    w->fd = fd;
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
    if (len && !(w->data = malloc(len))) { close(fd); free(w); return; }
    if (len) memcpy(w->data, data, len);
    w->len = len;
    if (writer_pump(w)) { writer_free(w); return; }
    struct wl_event_loop *loop = wl_display_get_event_loop(g_disp);
    w->src = wl_event_loop_add_fd(loop, fd, WL_EVENT_WRITABLE, on_writer_writable, w);
    w->timer = wl_event_loop_add_timer(loop, on_writer_timeout, w);
    if (w->timer) wl_event_source_timer_update(w->timer, CLIP_WRITE_TIMEOUT_MS);
}

/* ------------------------------------------------------------------ client → host reader */

struct reader {
    int fd;
    char *buf;
    size_t len;
    const char *mime;                           /* points into the source's list */
    struct wl_event_source *src, *timer;
};
static struct reader *g_reader;

static void reader_free(struct reader *r) {
    if (r->src) wl_event_source_remove(r->src);
    if (r->timer) wl_event_source_remove(r->timer);
    close(r->fd);
    free(r->buf);
    free(r);
    if (g_reader == r) g_reader = NULL;
}

static void reader_finish(struct reader *r) {
    if (r->len > 0) {
        char *copy = malloc(r->len + 1);
        if (copy) {
            memcpy(copy, r->buf, r->len);
            copy[r->len] = 0;
            free(g_last_guest_text);
            g_last_guest_text = copy;
            g_last_guest_len = r->len;
            wnc_log("clipboard", "guest copied %zu bytes (%s)", r->len, r->mime);
            wnc_on_clipboard_text(copy, (int)r->len);
        }
    } else {
        wnc_log("clipboard", "guest copied an empty text; Android clipboard left alone");
    }
    reader_free(r);
}

static int on_reader_readable(int fd, uint32_t mask, void *data) {
    struct reader *r = data;
    for (;;) {
        if (r->len >= CLIP_MAX_BYTES) {
            wnc_log("clipboard", "guest text exceeds %d bytes; dropped", CLIP_MAX_BYTES);
            reader_free(r);
            return 0;
        }
        size_t room = CLIP_MAX_BYTES - r->len, chunk = room < 65536 ? room : 65536;
        char *nb = realloc(r->buf, r->len + chunk);
        if (!nb) { reader_free(r); return 0; }
        r->buf = nb;
        ssize_t n = read(fd, r->buf + r->len, chunk);
        if (n > 0) { r->len += (size_t)n; continue; }
        if (n == 0) { reader_finish(r); return 0; }                          /* EOF */
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            if (mask & WL_EVENT_HANGUP) { reader_finish(r); return 0; }
            return 0;
        }
        if (errno == EINTR) continue;
        reader_free(r);
        return 0;
    }
}

static int on_reader_timeout(void *data) {
    struct reader *r = data;
    wnc_log("clipboard", "timed out reading the guest's clipboard text (%zu bytes so far)", r->len);
    reader_free(r);
    return 0;
}

/* Ask the current client source for its text, once. */
static void read_client_text(void) {
    const char *mime;
    int p[2];
    if (g_reader) reader_free(g_reader);
    if (!g_src || !(mime = source_text_mime(g_src))) return;
    if (pipe2(p, O_CLOEXEC) != 0) return;
    fcntl(p[0], F_SETFL, fcntl(p[0], F_GETFL) | O_NONBLOCK);  /* our end only; the client writes blocking */
    struct reader *r = calloc(1, sizeof(*r));
    if (!r) { close(p[0]); close(p[1]); return; }
    r->fd = p[0];
    r->mime = mime;
    struct wl_event_loop *loop = wl_display_get_event_loop(g_disp);
    r->src = wl_event_loop_add_fd(loop, p[0], WL_EVENT_READABLE, on_reader_readable, r);
    r->timer = wl_event_loop_add_timer(loop, on_reader_timeout, r);
    if (r->timer) wl_event_source_timer_update(r->timer, CLIP_READ_TIMEOUT_MS);
    g_reader = r;
    source_send_send(g_src, mime, p[1]);
    close(p[1]);
    wl_display_flush_clients(g_disp);
}

/* ------------------------------------------------------------------ offers */

static void offer_res_destroy(struct wl_resource *r) { free(wl_resource_get_user_data(r)); }

static void offer_receive(struct wl_client *c, struct wl_resource *r, const char *mime, int32_t fd) {
    struct offer *o = wl_resource_get_user_data(r);
    if (!o || o->gen != g_gen) { close(fd); return; }               /* stale offer */
    if (g_owner == OWNER_HOST) {
        if (is_text_mime(mime)) {
            wnc_log("clipboard", "Android clipboard → guest (%zu bytes) for %s", g_host_len, wnc_client_name(c));
            writer_start(fd, g_host_text, g_host_len);
        } else {
            close(fd);
        }
    } else if (g_owner == OWNER_CLIENT && g_src) {
        source_send_send(g_src, mime, fd);                          /* libwayland dups the fd */
        close(fd);
        wl_display_flush_clients(g_disp);
    } else {
        close(fd);
    }
}
static void offer_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void offer_accept(struct wl_client *c, struct wl_resource *r, uint32_t serial, const char *mime) {}
static void offer_finish(struct wl_client *c, struct wl_resource *r) {}
static void offer_set_actions(struct wl_client *c, struct wl_resource *r, uint32_t a, uint32_t p) {}

static const struct wl_data_offer_interface data_offer_impl = {
    .accept = offer_accept, .receive = offer_receive, .destroy = offer_destroy,
    .finish = offer_finish, .set_actions = offer_set_actions,
};
static const struct zwlr_data_control_offer_v1_interface control_offer_impl = {
    .receive = offer_receive, .destroy = offer_destroy,
};

/* Tell one device what the selection is now (a fresh offer, or nothing). */
static void device_send_selection(struct device *d) {
    struct wl_client *c = wl_resource_get_client(d->res);
    if (g_owner == OWNER_NONE) {
        if (d->is_control) zwlr_data_control_device_v1_send_selection(d->res, NULL);
        else wl_data_device_send_selection(d->res, NULL);
        return;
    }
    struct offer *o = calloc(1, sizeof(*o));
    if (!o) return;
    o->is_control = d->is_control;
    o->gen = g_gen;
    if (d->is_control) {
        o->res = wl_resource_create(c, &zwlr_data_control_offer_v1_interface, 1, 0);
        if (!o->res) { free(o); return; }
        wl_resource_set_implementation(o->res, &control_offer_impl, o, offer_res_destroy);
        zwlr_data_control_device_v1_send_data_offer(d->res, o->res);
    } else {
        o->res = wl_resource_create(c, &wl_data_offer_interface, wl_resource_get_version(d->res), 0);
        if (!o->res) { free(o); return; }
        wl_resource_set_implementation(o->res, &data_offer_impl, o, offer_res_destroy);
        wl_data_device_send_data_offer(d->res, o->res);
    }
    if (g_owner == OWNER_CLIENT && g_src) {
        for (int i = 0; i < g_src->nmimes; i++) {
            if (d->is_control) zwlr_data_control_offer_v1_send_offer(o->res, g_src->mimes[i]);
            else wl_data_offer_send_offer(o->res, g_src->mimes[i]);
        }
    } else {
        for (int i = 0; k_text_mimes[i]; i++) {
            if (d->is_control) zwlr_data_control_offer_v1_send_offer(o->res, k_text_mimes[i]);
            else wl_data_offer_send_offer(o->res, k_text_mimes[i]);
        }
    }
    if (d->is_control) zwlr_data_control_device_v1_send_selection(d->res, o->res);
    else wl_data_device_send_selection(d->res, o->res);
}

/* The selection changed: every data-control device hears it; wl_data_devices only if their
 * client holds keyboard focus. */
static void broadcast_selection(void) {
    struct device *d;
    g_gen++;
    wl_list_for_each(d, &g_devices, link) {
        if (d->is_control || (g_kb_client && wl_resource_get_client(d->res) == g_kb_client))
            device_send_selection(d);
    }
    wl_display_flush_clients(g_disp);
}

void wnc_clipboard_keyboard_focus(struct wl_client *client) {
    struct device *d;
    if (client == g_kb_client) return;
    g_kb_client = client;
    if (!client) return;
    wl_list_for_each(d, &g_devices, link)
        if (!d->is_control && wl_resource_get_client(d->res) == client) device_send_selection(d);
}

/* ------------------------------------------------------------------ sources */

static void source_res_destroy(struct wl_resource *r) {
    struct source *s = wl_resource_get_user_data(r);
    if (!s) return;
    if (g_src == s) {
        g_src = NULL;
        g_owner = OWNER_NONE;
        if (g_reader) reader_free(g_reader);
        wnc_log("clipboard", "%s withdrew its clipboard contents", wnc_client_name(wl_resource_get_client(r)));
        broadcast_selection();
    }
    for (int i = 0; i < s->nmimes; i++) free(s->mimes[i]);
    free(s);
}

static void source_offer(struct wl_client *c, struct wl_resource *r, const char *mime) {
    struct source *s = wl_resource_get_user_data(r);
    if (!s || s->nmimes >= MAX_MIMES) return;
    if (!(s->mimes[s->nmimes] = strdup(mime))) return;
    s->nmimes++;
}
static void source_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void source_set_actions(struct wl_client *c, struct wl_resource *r, uint32_t actions) {}

static const struct wl_data_source_interface data_source_impl = {
    .offer = source_offer, .destroy = source_destroy, .set_actions = source_set_actions,
};
static const struct zwlr_data_control_source_v1_interface control_source_impl = {
    .offer = source_offer, .destroy = source_destroy,
};

static void create_source(struct wl_client *c, uint32_t id, int is_control, uint32_t ver) {
    struct source *s = calloc(1, sizeof(*s));
    if (!s) { wl_client_post_no_memory(c); return; }
    s->is_control = is_control;
    s->res = wl_resource_create(c, is_control ? &zwlr_data_control_source_v1_interface
                                              : &wl_data_source_interface, ver, id);
    if (!s->res) { free(s); wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(s->res, is_control ? (const void *)&control_source_impl
                                                      : (const void *)&data_source_impl,
                                   s, source_res_destroy);
}

/* A device set (or cleared) the selection. */
static void set_selection(struct wl_client *c, struct wl_resource *source_res) {
    struct source *s = source_res ? wl_resource_get_user_data(source_res) : NULL;
    if (s && s == g_src) return;
    if (g_src && g_src != s) source_send_cancelled(g_src);
    if (g_reader) reader_free(g_reader);
    free(g_host_text); g_host_text = NULL; g_host_len = 0;
    g_src = s;
    g_owner = s ? OWNER_CLIENT : OWNER_NONE;
    if (s) {
        char list[200] = "";
        for (int i = 0; i < s->nmimes && strlen(list) < 150; i++) {
            if (!strncmp(s->mimes[i], "application/x.winewayland", 25)) continue;
            if (*list) strncat(list, ", ", sizeof(list) - strlen(list) - 1);
            strncat(list, s->mimes[i], sizeof(list) - strlen(list) - 1);
        }
        wnc_log("clipboard", "selection set by %s (%s)", wnc_client_name(c), *list ? list : "no mime types");
    } else {
        wnc_log("clipboard", "%s cleared the clipboard", wnc_client_name(c));
    }
    broadcast_selection();
    read_client_text();
}

/* ------------------------------------------------------------------ devices */

static void device_res_destroy(struct wl_resource *r) {
    struct device *d = wl_resource_get_user_data(r);
    if (!d) return;
    wl_list_remove(&d->link);
    free(d);
}

static void device_start_drag(struct wl_client *c, struct wl_resource *r, struct wl_resource *source,
                              struct wl_resource *origin, struct wl_resource *icon, uint32_t serial) {
    wnc_log("clipboard", "%s started a drag and drop; not supported, cancelled", wnc_client_name(c));
    if (source) {
        struct source *s = wl_resource_get_user_data(source);
        if (s && s != g_src) source_send_cancelled(s);
    }
}
static void device_set_selection(struct wl_client *c, struct wl_resource *r, struct wl_resource *source, uint32_t serial) {
    set_selection(c, source);
}
static void device_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void control_device_set_selection(struct wl_client *c, struct wl_resource *r, struct wl_resource *source) {
    set_selection(c, source);
}
static void control_device_set_primary(struct wl_client *c, struct wl_resource *r, struct wl_resource *source) {
    /* No primary selection on Android; a source given here just gets cancelled. */
    if (source) {
        struct source *s = wl_resource_get_user_data(source);
        if (s && s != g_src) source_send_cancelled(s);
    }
}

static const struct wl_data_device_interface data_device_impl = {
    .start_drag = device_start_drag, .set_selection = device_set_selection, .release = device_release,
};
static const struct zwlr_data_control_device_v1_interface control_device_impl = {
    .set_selection = control_device_set_selection, .destroy = device_release,
    .set_primary_selection = control_device_set_primary,
};

static void create_device(struct wl_client *c, uint32_t id, int is_control, uint32_t ver) {
    struct device *d = calloc(1, sizeof(*d));
    if (!d) { wl_client_post_no_memory(c); return; }
    d->is_control = is_control;
    d->res = wl_resource_create(c, is_control ? &zwlr_data_control_device_v1_interface
                                              : &wl_data_device_interface, ver, id);
    if (!d->res) { free(d); wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(d->res, is_control ? (const void *)&control_device_impl
                                                      : (const void *)&data_device_impl,
                                   d, device_res_destroy);
    wl_list_insert(&g_devices, &d->link);
    /* Data-control devices see the selection at once; wl_data_devices when focus arrives. */
    if (is_control || (g_kb_client && c == g_kb_client)) device_send_selection(d);
}

/* ------------------------------------------------------------------ managers */

static void dm_create_source(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    create_source(c, id, 0, wl_resource_get_version(r));
}
static void dm_get_device(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *seat) {
    create_device(c, id, 0, wl_resource_get_version(r));
}
static void dm_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_data_device_manager_interface dm_impl = {
    .create_data_source = dm_create_source, .get_data_device = dm_get_device, .release = dm_release,
};
static void bind_data_device_manager(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_data_device_manager_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &dm_impl, NULL, NULL);
}

static void cm_create_source(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    create_source(c, id, 1, 1);
}
static void cm_get_device(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *seat) {
    create_device(c, id, 1, wl_resource_get_version(r));
}
static void cm_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct zwlr_data_control_manager_v1_interface cm_impl = {
    .create_data_source = cm_create_source, .get_data_device = cm_get_device, .destroy = cm_destroy,
};
static void bind_data_control_manager(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &zwlr_data_control_manager_v1_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &cm_impl, NULL, NULL);
}

/* ------------------------------------------------------------------ host side */

/* Android's clipboard text (compositor thread, via the host queue). Takes the buffer. */
void clipboard_host_text(char *utf8, size_t len) {
    if (len && g_last_guest_text && len == g_last_guest_len && !memcmp(utf8, g_last_guest_text, len)) {
        free(utf8);                                 /* our own copy coming back: not a change */
        return;
    }
    if (len && g_owner == OWNER_HOST && g_host_len == len && !memcmp(utf8, g_host_text, len)) {
        free(utf8);
        return;
    }
    if (g_src) { source_send_cancelled(g_src); g_src = NULL; }
    if (g_reader) reader_free(g_reader);
    free(g_host_text);
    g_host_text = len ? utf8 : NULL;
    if (!len) free(utf8);
    g_host_len = len;
    g_owner = len ? OWNER_HOST : OWNER_NONE;
    wnc_log("clipboard", len ? "Android clipboard → guest (%zu bytes)" : "Android clipboard cleared (%zu bytes)", len);
    broadcast_selection();
}

void clipboard_init(struct wl_display *display) {
    g_disp = display;
    wl_list_init(&g_devices);
    wl_global_create(display, &wl_data_device_manager_interface, 3, NULL, bind_data_device_manager);
    wl_global_create(display, &zwlr_data_control_manager_v1_interface, 1, NULL, bind_data_control_manager);
}
