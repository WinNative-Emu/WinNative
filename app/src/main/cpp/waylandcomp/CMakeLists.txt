# Embedded Wayland compositor: runs a libwayland-server compositor in-process so games launched
# through Wine's winewayland.drv present into it. Ported from Bannerlator (The412Banner); see
# docs/WAYLAND-DISPLAY.md and CREDITS.md. Protocol glue is pre-generated in generated/ so no host
# wayland-scanner is needed at build time.
add_library(bannerwayland SHARED
        src/compositor.c
        src/vk_present.c
        src/effects_chain.c
        src/vk_loader.c
        src/sc_layer.c
        src/ahb_swapchain.c
        src/waylandcomp_jni.c
        src/banner_ext.c
        src/wl_clipboard.c
        src/wl_text_input.c
        src/wl_toplevel_icon.c
        src/wl_color_mgmt.c
        src/hdr_compose.c
        src/framegen_bridge.c
        src/framegen_jni.c
        src/framegen_engine_stub.c
        generated/color-management-v1-protocol.c
        generated/xdg-shell-protocol.c
        generated/linux-dmabuf-v1-protocol.c
        generated/viewporter-protocol.c
        generated/banner-desktop-v1-protocol.c
        generated/presentation-time-protocol.c
        generated/pointer-constraints-unstable-v1-protocol.c
        generated/relative-pointer-unstable-v1-protocol.c
        generated/wlr-data-control-unstable-v1-protocol.c
        generated/text-input-unstable-v3-protocol.c
        generated/xdg-toplevel-icon-v1-protocol.c
        generated/banner-ahb-v1-protocol.c
)

target_include_directories(bannerwayland PRIVATE
        generated
        prebuilt/include
        src
        # Committed SPIR-V of the screen-effect chain (src/effects_chain.c, src/hdr_compose.c).
        shaders
        ${CMAKE_CURRENT_SOURCE_DIR}/../adrenotools/include
)

# The frame-generation engines of the X11 renderer are not shared with this library in WinNative:
# src/framegen_engine_stub.c reports every engine unavailable, so the compositor never generates.
target_compile_definitions(bannerwayland PRIVATE BANNER_WAYLAND_FRAMEGEN_STUB=1)

add_library(wayland-server SHARED IMPORTED)
set_target_properties(wayland-server PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/lib/libwayland-server.so)

# Vulkan is loaded at runtime through adrenotools (Turnip), never linked: the compositor needs the
# driver's dma-buf import path, which the system Adreno driver lacks.
target_link_libraries(bannerwayland
        wayland-server
        adrenotools
        dl
        log
        android
)
