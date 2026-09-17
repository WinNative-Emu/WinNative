# gamescope and Linux programs

This is the working plan for running Linux programs - AppImages, shell launchers, native Linux
game builds, eventually the Linux Steam client - inside WinNative, with Valve's
[gamescope](https://github.com/ValveSoftware/gamescope) available as the session compositor for
them. It records what gamescope is, what WinNative already has, what is missing, and the order the
work goes in. Sources for the gamescope facts are the tree at master `4004d0b` (2026-09-16).

## What gamescope is

A micro-compositor. It is a Wayland compositor built on wlroots that hosts one Xwayland server, takes
the game's frames, composites them with Vulkan compute, and hands the result to whatever is under it.
The pieces, by source file:

| Piece | File | What it does |
|---|---|---|
| Window manager and pacing | `src/steamcompmgr.cpp` (10.8k lines) | X11 focus and window rules, the frame limiter, resolution spoofing, and spawning the game as its child |
| Compositor server | `src/wlserver.cpp` | The wlroots `wl_display`, the Xwayland server(s), xdg-shell v3 for Wayland-native clients, keyboard/pointer/touch delivery |
| Renderer | `src/rendervulkan.cpp` | Vulkan device setup, dma-buf import of client buffers, the compositing compute shaders, FSR 1 / NIS / SGSR / integer upscaling, HDR |
| Backends | `src/Backends/{DRM,SDL,Wayland,Headless,OpenVR}Backend.cpp` | Where the composited frame goes: a KMS display (embedded mode), an SDL or Wayland window of a parent compositor (nested mode), nowhere (headless), a VR overlay |
| WSI layer | `layer/` | A Vulkan implicit layer the game loads; it feeds the limiter and HDR metadata back to gamescope |
| Protocols | `protocol/` | Thirteen private Wayland protocols between the layer/game and gamescope |

Facts that shape the plan:

- **Xwayland is mandatory.** `wlserver_init()` starts at least one `wlr_xwayland_server` and blocks
  until it is up. Wayland-native clients work too, but only get `WAYLAND_DISPLAY` if gamescope is run
  with `--expose-wayland`; by default it is blanked so a game cannot reach the parent compositor.
- **The child is exec'd by gamescope.** Everything after `--` becomes its child with `DISPLAY` set to
  the nested Xwayland and `ENABLE_GAMESCOPE_WSI=1`; when gamescope exits it kills its children.
- **The nested `wayland` backend is the only fit here.** `drm` needs KMS, `sdl` needs a desktop, and
  `headless` draws nothing. As a Wayland client it demands these globals from the parent and
  refuses to start without all of them: `wl_compositor` v4, `wl_subcompositor`, `wl_shm`,
  `xdg_wm_base`, `zwp_linux_dmabuf_v1` v3+, `wp_viewporter`, `wp_presentation`,
  `zwp_relative_pointer_manager_v1`, `zwp_pointer_constraints_v1`.
- **Vulkan needs**, unconditionally: `VK_KHR_external_memory_fd`, `VK_EXT_external_memory_dma_buf`,
  `VK_KHR_external_semaphore_fd`, `VK_EXT_robustness2`; for the nested swapchain
  `VK_KHR_swapchain_mutable_format`, `VK_KHR_present_id`, `VK_KHR_present_wait`. Missing ones abort.
- **Build deps** (meson): wlroots 0.20 with Xwayland, libdrm (DRM backend only), libliftoff (DRM only),
  SDL2 (SDL only), pixman, libudev, libinput, libseat (through wlroots `session=enabled`), libdecor,
  luajit, xkbcommon, glslang at build time, and the X client libraries
  (`x11 xdamage xcomposite xcursor xrender xext xfixes xxf86vm xtst xres xmu xi`). No x86-only code;
  Arch Linux ARM ships an aarch64 package.
- License BSD-2-Clause.

## What WinNative has today

- **One bionic rootfs** (`files/imagefs`: Android libc, `usr/lib/libc.so`), Wine arm64ec on FEX or
  x86_64 Wine on Box64. There is no glibc, no `/bin/sh`, and no way to run a Linux ELF. Every
  library entry is `Exec=wine ...` or `Exec=retro:<system>`.
- **A proot source tree** (`app/src/main/cpp/proot`) that is not in `CMakeLists.txt`, and an
  `XvfbInstaller` for a "sniper-arm64" rootfs that nothing calls. They are the remains of an
  earlier Linux Steam attempt, not a working path.
- **The embedded Wayland compositor** (`app/src/main/cpp/waylandcomp`) already exports every global
  gamescope's Wayland backend requires, plus `wp_color_manager_v1`, `zwp_text_input_v3` and
  `xdg_toplevel_icon_v1`, and it already imports client dma-bufs and presents through Turnip.
- **A Wayland-capable Turnip** (native ICD, `libwayland-client`) in the container that exports
  dma-bufs through `/dev/dri/renderD128`. That is the same extension set gamescope's renderer asks
  for, so on this device the graphics side is not the blocker it would be on a phone that hides
  `/dev/dri`.
- **The library** (this branch): the Add dialog accepts Windows executables, Linux executables
  (`.AppImage .sh .run .bin .elf .x86_64 .x86 .aarch64 .arm64`) and console ROMs; every entry
  carries a Game / App type (`library_type` extra) that the Games and Applications filters honour;
  Linux entries are written with `runtime=linux` and `Exec=linux:native`, and launching one reports
  that the Linux runtime is not installed yet. A `.bin` is treated as a console image first, as it
  was before. Files with no extension are not offered yet.

## What "a gamescope option" means

Three layers, each usable on its own:

1. **A Linux runtime.** A glibc arm64 rootfs, separate from the Wine imagefs, in which a Linux ELF
   can be exec'd as the app's own uid with no root. This is a new container kind, not a Wine
   container.
2. **gamescope on the compositor.** gamescope built for aarch64 with the DRM/SDL/OpenVR/PipeWire
   backends disabled, run as a Wayland client of `libwnwayland.so` with `--expose-wayland`, hosting
   Xwayland for X11 programs. It is a normal `xdg_toplevel` to our compositor; nothing in the
   compositor's rendering path changes.
3. **The app around them.** A Linux container type, shortcut settings for Linux entries, and the
   gamescope switch with its options (upscaler, frame limit, game resolution vs output resolution).

## Plan

### Phase 0 - library groundwork (this branch)

Done: file picking, the type selector and filter, the `runtime=linux` shortcut shape, the launch seam
in `LinuxApps.launch`.

### Phase 1 - Linux runtime

Decide and build the rootfs execution model. Two candidates:

- **proot** (ptrace, in tree). No patching of the rootfs; every syscall pays a ptrace round trip,
  which is the wrong cost for a game, and `PTRACE_O_TRACESECCOMPHARDENING` interactions on newer
  Android need checking. Right for bring-up, wrong for shipping games.
- **Prefixed glibc** (the Termux `glibc-packages` approach): a glibc built with the rootfs path as
  its prefix, so `/usr/lib` resolves without a chroot. No per-syscall cost. Every package in the
  rootfs must come from that build, so it is a package repository, not a tarball.

Research before choosing: whether Valve's arm64 Steam Linux Runtime (`sniper`) images are
redistributable and whether they carry a prefixable glibc; what Arch Linux ARM's `gamescope`
package pulls in transitively; AppImage handling (no FUSE on Android, so
`APPIMAGE_EXTRACT_AND_RUN=1`); and which of the imagefs pieces (PulseAudio, the sysvshm shim,
`libredirect`) the Linux side reuses.

Deliverable: a `linuxfs` under `files/`, a launcher component beside
`GuestProgramLauncherComponent` that execs `sh` and an AppImage with `WAYLAND_DISPLAY=wayland-0`
pointed at our compositor, and a Wayland-native Vulkan program (`vkcube` on Wayland) on screen.
That milestone does not need gamescope.

### Phase 2 - gamescope

Cross-build for aarch64 with
`-Ddrm_backend=disabled -Dsdl2_backend=disabled -Denable_openvr_support=false -Dpipewire=disabled -Dinput_emulation=disabled -Davif_screenshots=disabled -Drt_cap=disabled`
and wlroots 0.20 as a static subproject. Things to verify on device, in order:

1. Turnip reports `VK_KHR_swapchain_mutable_format`, `VK_KHR_present_id` and
   `VK_KHR_present_wait` on a Wayland surface of our compositor (Mesa enables `present_wait` when
   the compositor has `wp_presentation`, which ours does).
2. Xwayland starts inside the rootfs with our xkb keymap and `-rootless`.
3. libinput/libseat are link-time only in this configuration; if wlroots' session code still opens
   a seat, build wlroots with `session=disabled` and patch the one gamescope check that insists.
4. gamescope's `renderer_get_drm_fd` hands the render node fd to Xwayland clients so games get
   dma-buf, not `wl_shm`.

Deliverable: a Linux game under `gamescope --backend wayland --expose-wayland -W <w> -H <h> -- <game>`
with FSR upscaling and the frame limiter working, in the same session window as everything else.

### Phase 3 - Linux Steam

The Linux Steam client is i386 + x86_64; on arm64 Valve's own path is FEX. That means an x86 glibc
sub-rootfs for FEX inside the Linux runtime, the Steam bootstrap, and gamescope as the session
compositor the way Steam Deck's gaming mode does it (`gamescope -e -- steam -tenfoot`). Research:
FEX's rootfs requirements against our runtime, the unofficial native arm64 client's state
(regressed to ARMv8.1 LSE in 2026, per steam-for-linux#13288), and whether the existing
`wn-steam-*` pieces (real-Steam login, `steamwebhelper` handling) carry over.

### Phase 4 - app integration

A `Container` kind for Linux with its own settings dialog (the Wine dialog is wrong for it);
`ShortcutSettingsComposeDialog` routed by `LinuxApps.isLinuxShortcut` the way it is for retro
entries; icon extraction from an AppImage's `.DirIcon`; extensionless ELF detection in the picker
(needs the listing moved off the UI thread first); the gamescope options in the drawer; and the
Linux runtime as a downloadable content profile, not an asset in the APK.

## Risks

- ptrace cost if proot stays past bring-up.
- Android's seccomp policy for app processes versus what glibc, Xwayland and gamescope call.
- `/dev/dri` visibility differs per device; the compositor already reports the case where there is
  none, and the Linux side must fail the same way rather than crash.
- gamescope is 50k lines of C++ that expects a desktop; each build option we turn off is one fewer
  place it can assume one.
