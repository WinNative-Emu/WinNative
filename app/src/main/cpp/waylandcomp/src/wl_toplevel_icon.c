/*
 * xdg_toplevel_icon_manager_v1 (version 1): window icons are accepted and dropped. The scene
 * has no title bars to draw them in; winewayland only stops complaining that the global is
 * missing. We send no icon_size (any size is fine) and `done` at bind, as the protocol asks.
 */
#include <stdlib.h>
#include "desktop_ext.h"
#include "xdg-toplevel-icon-v1-server-protocol.h"

static void icon_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void icon_set_name(struct wl_client *c, struct wl_resource *r, const char *name) {}
static void icon_add_buffer(struct wl_client *c, struct wl_resource *r, struct wl_resource *buffer, int32_t scale) {}
static const struct xdg_toplevel_icon_v1_interface icon_impl = {
    .destroy = icon_destroy, .set_name = icon_set_name, .add_buffer = icon_add_buffer,
};

static void mgr_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void mgr_create_icon(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *icon = wl_resource_create(c, &xdg_toplevel_icon_v1_interface, wl_resource_get_version(r), id);
    if (!icon) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(icon, &icon_impl, NULL, NULL);
}
static void mgr_set_icon(struct wl_client *c, struct wl_resource *r, struct wl_resource *toplevel, struct wl_resource *icon) {}
static const struct xdg_toplevel_icon_manager_v1_interface mgr_impl = {
    .destroy = mgr_destroy, .create_icon = mgr_create_icon, .set_icon = mgr_set_icon,
};
static void bind_icon_manager(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &xdg_toplevel_icon_manager_v1_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &mgr_impl, NULL, NULL);
    xdg_toplevel_icon_manager_v1_send_done(r);
}

void toplevel_icon_init(struct wl_display *display) {
    wl_global_create(display, &xdg_toplevel_icon_manager_v1_interface, 1, NULL, bind_icon_manager);
}
