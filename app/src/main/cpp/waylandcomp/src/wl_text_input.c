/*
 * Text input: zwp_text_input_manager_v3 (advertised at version 1), the host input method
 * bridge winewayland.drv uses for IME text (our soft keyboard).
 *
 * Focus model. winewayland enables its text input as soon as our `enter` names a surface and
 * routes every IME update to that surface's window — inside that window's process (the update
 * list is per process). Keyboard focus in this compositor sits on the desktop surface (the
 * desktop owner's process), so text input can't follow it; it follows wnc_ime_target():
 * the program window last clicked, else the topmost program window. Clicking into notepad
 * moves the text-input focus to notepad's process, which is where the edit control lives.
 *
 * Protocol state. Requests between commits are pending; `commit` bumps the serial and applies
 * them (enable resets the state as the protocol says). We answer with `done(serial)` only when
 * we sent events (preedit/commit strings), never for every commit.
 *
 * Host side. wnc_on_text_input(enabled, program, cursor rect in scene pixels) tells the app
 * when a program starts/stops accepting IME text or moves its caret (Wine reports the rect only
 * when an edit control positions its IME composition window, via ImmSetCompositionWindow).
 * Typed text arrives through text_input_host_*: preedit_string / commit_string + done.
 * delete_surrounding_text is NOT used: winewayland's handler for it is empty, so deletions
 * become Backspace/Delete key presses on the wl_keyboard path instead.
 */
#define _GNU_SOURCE 1
#include <stdlib.h>
#include <string.h>
#include "desktop_ext.h"
#include "text-input-unstable-v3-server-protocol.h"

#ifndef KEY_BACKSPACE
#define KEY_BACKSPACE 14
#endif
#ifndef KEY_DELETE
#define KEY_DELETE 111
#endif

static struct wl_display *g_disp;

struct text_input {
    struct wl_resource *res;
    struct wl_client *client;
    struct wl_list link;
    struct wl_resource *entered;                /* surface we sent enter for, or NULL */
    uint32_t serial;                            /* commits received */
    /* pending, applied on commit */
    int p_enable, p_disable, p_rect_set, p_content_set;
    int p_rect[4];
    uint32_t p_hint, p_purpose;
    /* current */
    int enabled;
    int rect[4];
    uint32_t hint, purpose;
    int has_preedit;                            /* we sent a non-empty preedit last */
};
static struct wl_list g_inputs;
static struct wl_resource *g_target;            /* surface text input is focused on */

/* What the app was last told. */
static int g_told_enabled, g_told_rect[4];
static struct text_input *g_told_ti;

static const char *ti_program(struct text_input *ti) { return wnc_client_name(ti->client); }

/* The enabled text input focused on the current target, if any. */
static struct text_input *active_input(void) {
    struct text_input *ti;
    if (!g_target) return NULL;
    wl_list_for_each(ti, &g_inputs, link)
        if (ti->entered == g_target && ti->enabled) return ti;
    return NULL;
}

static void tell_app(void) {
    struct text_input *ti = active_input();
    int enabled = ti != NULL, rect[4] = {0, 0, 0, 0};
    if (ti) {
        int ox = 0, oy = 0;
        wnc_surface_scene_origin(ti->entered, &ox, &oy);
        rect[0] = ti->rect[0] + ox; rect[1] = ti->rect[1] + oy;
        rect[2] = ti->rect[2]; rect[3] = ti->rect[3];
    }
    if (enabled == g_told_enabled && ti == g_told_ti && !memcmp(rect, g_told_rect, sizeof(rect))) return;
    if (enabled && (!g_told_enabled || ti != g_told_ti))
        wnc_log("text-input", "text input enabled by %s (cursor rect %d,%d %dx%d)", ti_program(ti),
                   rect[0], rect[1], rect[2], rect[3]);
    else if (enabled)
        wnc_log("text-input", "cursor rect from %s: %d,%d %dx%d", ti_program(ti), rect[0], rect[1], rect[2], rect[3]);
    else
        wnc_log("text-input", "text input disabled%s%s", g_told_ti ? " by " : "", g_told_ti ? ti_program(g_told_ti) : "");
    g_told_enabled = enabled;
    g_told_ti = ti;
    memcpy(g_told_rect, rect, sizeof(rect));
    wnc_on_text_input(enabled, enabled ? ti_program(ti) : "", rect[0], rect[1], rect[2], rect[3]);
}

/* ------------------------------------------------------------------ focus */

static void send_leave(struct text_input *ti) {
    if (!ti->entered) return;
    zwp_text_input_v3_send_leave(ti->res, ti->entered);
    ti->entered = NULL;
    ti->enabled = 0;                            /* the protocol disables on leave */
    ti->has_preedit = 0;
}

void wnc_text_input_refocus(void) {
    struct wl_resource *t = wnc_ime_target();
    struct text_input *ti;
    if (t == g_target) return;
    wl_list_for_each(ti, &g_inputs, link)
        if (ti->entered && ti->entered != t) send_leave(ti);
    g_target = t;
    if (t) {
        struct wl_client *c = wl_resource_get_client(t);
        wl_list_for_each(ti, &g_inputs, link) {
            if (ti->client != c || ti->entered == t) continue;
            ti->entered = t;
            zwp_text_input_v3_send_enter(ti->res, t);
        }
    }
    tell_app();
    wl_display_flush_clients(g_disp);
}

void wnc_text_input_surface_gone(struct wl_resource *surface) {
    struct text_input *ti;
    wl_list_for_each(ti, &g_inputs, link)
        if (ti->entered == surface) { ti->entered = NULL; ti->enabled = 0; ti->has_preedit = 0; }
    if (g_target == surface) g_target = NULL;
    if (g_told_ti && g_told_ti->entered == NULL && g_told_enabled) tell_app();
}

/* ------------------------------------------------------------------ requests */

static void ti_res_destroy(struct wl_resource *r) {
    struct text_input *ti = wl_resource_get_user_data(r);
    if (!ti) return;
    wl_list_remove(&ti->link);
    if (g_told_ti == ti) { g_told_ti = NULL; g_told_enabled = 0; memset(g_told_rect, 0, sizeof(g_told_rect));
                           wnc_on_text_input(0, "", 0, 0, 0, 0); }
    free(ti);
}

static void ti_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void ti_enable(struct wl_client *c, struct wl_resource *r) {
    struct text_input *ti = wl_resource_get_user_data(r);
    if (ti) { ti->p_enable = 1; ti->p_disable = 0; }
}
static void ti_disable(struct wl_client *c, struct wl_resource *r) {
    struct text_input *ti = wl_resource_get_user_data(r);
    if (ti) { ti->p_disable = 1; ti->p_enable = 0; }
}
static void ti_set_surrounding_text(struct wl_client *c, struct wl_resource *r, const char *text, int32_t cursor, int32_t anchor) {}
static void ti_set_text_change_cause(struct wl_client *c, struct wl_resource *r, uint32_t cause) {}
static void ti_set_content_type(struct wl_client *c, struct wl_resource *r, uint32_t hint, uint32_t purpose) {
    struct text_input *ti = wl_resource_get_user_data(r);
    if (ti) { ti->p_hint = hint; ti->p_purpose = purpose; ti->p_content_set = 1; }
}
static void ti_set_cursor_rectangle(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {
    struct text_input *ti = wl_resource_get_user_data(r);
    if (!ti) return;
    ti->p_rect[0] = x; ti->p_rect[1] = y; ti->p_rect[2] = w; ti->p_rect[3] = h;
    ti->p_rect_set = 1;
}
static void ti_commit(struct wl_client *c, struct wl_resource *r) {
    struct text_input *ti = wl_resource_get_user_data(r);
    if (!ti) return;
    ti->serial++;
    if (ti->p_enable) {
        ti->enabled = ti->entered != NULL;      /* enable only counts while focused */
        memset(ti->rect, 0, sizeof(ti->rect));
        ti->hint = 0; ti->purpose = 0;
        ti->has_preedit = 0;
    }
    if (ti->p_disable) { ti->enabled = 0; ti->has_preedit = 0; }
    if (ti->p_rect_set) memcpy(ti->rect, ti->p_rect, sizeof(ti->rect));
    if (ti->p_content_set) { ti->hint = ti->p_hint; ti->purpose = ti->p_purpose; }
    ti->p_enable = ti->p_disable = ti->p_rect_set = ti->p_content_set = 0;
    tell_app();
}
static void ti_set_available_actions(struct wl_client *c, struct wl_resource *r, struct wl_array *a) {}
static void ti_show_input_panel(struct wl_client *c, struct wl_resource *r) {}
static void ti_hide_input_panel(struct wl_client *c, struct wl_resource *r) {}

static const struct zwp_text_input_v3_interface ti_impl = {
    .destroy = ti_destroy, .enable = ti_enable, .disable = ti_disable,
    .set_surrounding_text = ti_set_surrounding_text, .set_text_change_cause = ti_set_text_change_cause,
    .set_content_type = ti_set_content_type, .set_cursor_rectangle = ti_set_cursor_rectangle,
    .commit = ti_commit, .set_available_actions = ti_set_available_actions,
    .show_input_panel = ti_show_input_panel, .hide_input_panel = ti_hide_input_panel,
};

static void tim_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void tim_get_text_input(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *seat) {
    struct text_input *ti = calloc(1, sizeof(*ti));
    if (!ti) { wl_client_post_no_memory(c); return; }
    ti->res = wl_resource_create(c, &zwp_text_input_v3_interface, wl_resource_get_version(r), id);
    if (!ti->res) { free(ti); wl_client_post_no_memory(c); return; }
    ti->client = c;
    wl_resource_set_implementation(ti->res, &ti_impl, ti, ti_res_destroy);
    wl_list_insert(&g_inputs, &ti->link);
    if (g_target && wl_resource_get_client(g_target) == c) {
        ti->entered = g_target;
        zwp_text_input_v3_send_enter(ti->res, g_target);
    }
}
static const struct zwp_text_input_manager_v3_interface tim_impl = {
    .destroy = tim_destroy, .get_text_input = tim_get_text_input,
};
static void bind_text_input_manager(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &zwp_text_input_manager_v3_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &tim_impl, NULL, NULL);
}

/* ------------------------------------------------------------------ host side (compositor thread) */

static size_t utf8_chars(const char *s, size_t len) {
    size_t n = 0;
    for (size_t i = 0; i < len; i++) if (((unsigned char)s[i] & 0xC0) != 0x80) n++;
    return n;
}

/* Byte offset of UTF-8 character index `chars` (negative = end). */
static int32_t utf8_offset(const char *s, size_t len, int chars) {
    if (chars < 0) return (int32_t)len;
    size_t i = 0;
    while (i < len && chars > 0) {
        i++;
        while (i < len && ((unsigned char)s[i] & 0xC0) == 0x80) i++;
        chars--;
    }
    return (int32_t)i;
}

void text_input_host_commit(const char *utf8, size_t len) {
    struct text_input *ti = active_input();
    if (!ti) {
        wnc_log("text-input", "typed text (%zu chars) dropped: no program accepts text input", utf8_chars(utf8, len));
        return;
    }
    if (ti->has_preedit) { zwp_text_input_v3_send_preedit_string(ti->res, "", 0, 0); ti->has_preedit = 0; }
    zwp_text_input_v3_send_commit_string(ti->res, utf8);
    zwp_text_input_v3_send_done(ti->res, ti->serial);
    wnc_log("text-input", "committed %zu chars to %s", utf8_chars(utf8, len), ti_program(ti));
}

void text_input_host_preedit(const char *utf8, size_t len, int cursor_begin, int cursor_end) {
    struct text_input *ti = active_input();
    if (!ti) return;
    if (!len && !ti->has_preedit) return;
    zwp_text_input_v3_send_preedit_string(ti->res, len ? utf8 : "",
                                          utf8_offset(utf8, len, cursor_begin), utf8_offset(utf8, len, cursor_end));
    zwp_text_input_v3_send_done(ti->res, ti->serial);
    ti->has_preedit = len > 0;
}

void text_input_host_delete(int before, int after) {
    if (before <= 0 && after <= 0) return;
    if (before > 64) before = 64;
    if (after > 64) after = 64;
    struct text_input *ti = active_input();
    if (ti && ti->has_preedit) {                /* deleting inside a composition: clear it first */
        zwp_text_input_v3_send_preedit_string(ti->res, "", 0, 0);
        zwp_text_input_v3_send_done(ti->res, ti->serial);
        ti->has_preedit = 0;
    }
    for (int i = 0; i < before; i++) { wnc_inject_key(KEY_BACKSPACE, 1); wnc_inject_key(KEY_BACKSPACE, 0); }
    for (int i = 0; i < after; i++) { wnc_inject_key(KEY_DELETE, 1); wnc_inject_key(KEY_DELETE, 0); }
    wnc_log("text-input", "deleted %d before / %d after the caret (as key presses)", before, after);
}

void text_input_init(struct wl_display *display) {
    g_disp = display;
    wl_list_init(&g_inputs);
    wl_global_create(display, &zwp_text_input_manager_v3_interface, 1, NULL, bind_text_input_manager);
}
