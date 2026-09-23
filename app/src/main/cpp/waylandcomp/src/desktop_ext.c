/*
 * Host → compositor message queue plus the init of the extension modules.
 *
 * The app's threads (UI thread, clipboard listener) hand text to the compositor through
 * wnc_host_* ; the messages are queued under a mutex and a pipe byte wakes the wl event
 * loop, which drains them on the compositor thread — the only thread that may touch
 * wl_resources.
 */
#define _GNU_SOURCE 1
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <android/log.h>
#include "desktop_ext.h"
#include "ahb_swapchain.h"
#include "color_mgmt.h"

enum host_kind { HOST_CLIPBOARD = 1, HOST_TEXT_COMMIT, HOST_TEXT_PREEDIT, HOST_TEXT_DELETE, HOST_ZERO_COPY, HOST_HDR_OUTPUT };

struct host_msg {
    enum host_kind kind;
    char *text;
    size_t len;
    int a, b;
    struct host_msg *next;
};

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static struct host_msg *g_head, *g_tail;
static int g_wake[2] = {-1, -1};

static void host_post(enum host_kind kind, const char *text, size_t len, int a, int b) {
    struct host_msg *m = calloc(1, sizeof(*m));
    if (!m) return;
    m->kind = kind;
    m->a = a; m->b = b;
    if (text && len) {
        if (!(m->text = malloc(len + 1))) { free(m); return; }
        memcpy(m->text, text, len);
        m->text[len] = 0;
        m->len = len;
    }
    pthread_mutex_lock(&g_mu);
    if (g_tail) g_tail->next = m; else g_head = m;
    g_tail = m;
    pthread_mutex_unlock(&g_mu);
    if (g_wake[1] >= 0) {
        char c = 1;
        ssize_t n = write(g_wake[1], &c, 1);
        (void)n;
    }
}

void wnc_host_clipboard_text(const char *utf8, int len) {
    host_post(HOST_CLIPBOARD, utf8, len > 0 ? (size_t)len : 0, 0, 0);
}
void wnc_host_text_commit(const char *utf8, int len) {
    host_post(HOST_TEXT_COMMIT, utf8, len > 0 ? (size_t)len : 0, 0, 0);
}
void wnc_host_text_preedit(const char *utf8, int len, int cursor_begin, int cursor_end) {
    host_post(HOST_TEXT_PREEDIT, utf8, len > 0 ? (size_t)len : 0, cursor_begin, cursor_end);
}
void wnc_host_text_delete(int before, int after) {
    host_post(HOST_TEXT_DELETE, NULL, 0, before, after);
}
void wnc_host_zero_copy(int on, int live) {
    host_post(HOST_ZERO_COPY, NULL, 0, on, live);
}
void wnc_host_hdr_output(int on) {
    host_post(HOST_HDR_OUTPUT, NULL, 0, on, 0);
}

static int on_wake(int fd, uint32_t mask, void *data) {
    char buf[64];
    while (read(fd, buf, sizeof(buf)) > 0) {}
    for (;;) {
        pthread_mutex_lock(&g_mu);
        struct host_msg *m = g_head;
        if (m) { g_head = m->next; if (!g_head) g_tail = NULL; }
        pthread_mutex_unlock(&g_mu);
        if (!m) break;
        switch (m->kind) {
        case HOST_CLIPBOARD:
            clipboard_host_text(m->text, m->len);   /* takes the buffer */
            m->text = NULL;
            break;
        case HOST_TEXT_COMMIT:
            text_input_host_commit(m->text ? m->text : "", m->len);
            break;
        case HOST_TEXT_PREEDIT:
            text_input_host_preedit(m->text ? m->text : "", m->len, m->a, m->b);
            break;
        case HOST_TEXT_DELETE:
            text_input_host_delete(m->a, m->b);
            break;
        case HOST_ZERO_COPY:
            ahb_swapchain_set_mode(m->a, m->b);
            break;
        case HOST_HDR_OUTPUT:
            wnc_color_set_output(m->a);
            break;
        }
        free(m->text);
        free(m);
    }
    wl_display_flush_clients(wnc_get_display());
    return 0;
}

void wnc_ext_init(struct wl_display *display) {
    struct wl_event_loop *loop = wl_display_get_event_loop(display);
    if (pipe2(g_wake, O_CLOEXEC | O_NONBLOCK) == 0) {
        wl_event_loop_add_fd(loop, g_wake[0], WL_EVENT_READABLE, on_wake, NULL);
        pthread_mutex_lock(&g_mu);
        int queued = g_head != NULL;            /* posted before the loop existed (clipboard refresh) */
        pthread_mutex_unlock(&g_mu);
        if (queued) { char c = 1; ssize_t n = write(g_wake[1], &c, 1); (void)n; }
    } else
        __android_log_print(ANDROID_LOG_ERROR, "WNWayland", "host queue pipe failed");
    clipboard_init(display);
    text_input_init(display);
    toplevel_icon_init(display);
}
