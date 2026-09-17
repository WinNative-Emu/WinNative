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

## What WayLandIE shows

[WayLandIE](https://github.com/AstroCODEsky/WayLandIE) (2 commits, 2026-06) is the closest existing
run at this: gamescope and native arm64 Steam on Android. Its shape is the shape planned above,
which is worth knowing before building it.

- **Root is only for the rootfs, not the display.** The display is an unprivileged app
  (`io.waylandie.display`) presenting through `SurfaceControl` and `AHardwareBuffer`; it asks for no
  special permissions. `chroot`/`lxc` backends need `su`; `proot` is the documented rootless
  backend, with the caveat that dma-buf under proot is "experimental" and Steam/FEX/Proton "may be
  poor" from the ptrace overhead. Root buys reliable device nodes and speed, not screen access.
- **The compositor is a small custom libwayland-server** (no wlroots) that only accepts dma-buf
  client buffers and forwards the fds to the app. `libwnwayland.so` already is that, with more
  protocols.
- **gamescope runs nested in it, as a Wayland client**, exactly as Phase 2 proposes:

  ```
  gamescope --backend wayland -f -e --expose-wayland --xwayland-count 2 --keep-alive \
    --prefer-vk-device 5143:44050a31 -W 2688 -H 1216 -w 2688 -h 1216 -r 144 -o 144 \
    [--max-scale 1] [--force-windows-fullscreen] -- <session child>
  ```

  with `VK_ICD_FILENAMES=.../freedreno_icd.aarch64.json` (Turnip),
  `GAMESCOPE_DRM_RENDER_NODE=/dev/dri/renderD128`, `WLR_DRM_DEVICES=/dev/dri/renderD128`,
  `GAMESCOPE_FORCE_GENERAL_QUEUE=1`, and OpenGL routed through Zink
  (`MESA_LOADER_DRIVER_OVERRIDE=zink`, `LIBGL_KOPPER_DRI2=true`) because Steam's CEF needs GL.
- **Input is bridged from Android**, not libinput: touch and keys go over the socket and are
  injected as `wl_seat` events, with an XTEST fallback for X11 clients. No seatd, udev or evdev.
  Same as our compositor's seat.
- **Audio is PulseAudio** over a unix socket (`PULSE_LATENCY_MSEC=20`). We ship libpulse already.
- **Steam is the native arm64 client** (`steamrtarm64/steam -gamepadui`, CEF forced to
  ANGLE-on-Vulkan), with x86-64 games through Proton11ARM + FEX (`FEX_APP_CONFIG`,
  `PROTON_USE_NTSYNC=1`). No box64. One game is named as working: Metro: Last Light Redux.
- **What it does not give us:** no gamescope/wlroots/Xwayland build recipe, no packages, no pinned
  versions - `gamescope` must already be in `PATH`, and its bundle is exported from the author's own
  container and marked non-redistributable. No published logs, benchmarks or reproductions.

Takeaways for the phases: Phase 1 should plan for chroot-quality device nodes without root, which
argues for the prefixed-glibc model over proot; Phase 2's command line and env are settled; Phase 3
can target native arm64 Steam first (Valve's `steamrtarm64`) and needs Zink for CEF, which means a
Mesa GL build in the rootfs beside Turnip.

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

## How it is wired

**Choosing it.** Container Settings and Shortcut Settings have a **Runtime** row above Display Server:
*Wine* (the default) or *Gamescope*. It is stored as the `runtime` extra (`wine` / `gamescope`), and a
shortcut overrides its container the same way Display Server does, so one library entry can move
between the two without touching the container. Choosing Gamescope pins Display Server to Wayland;
gamescope is a client of the compositor and has nothing to draw on otherwise.

**Booting.** `XServerDisplayActivity.resolveDisplayBackend` reads the runtime first. For Gamescope
it starts the compositor as for any Wayland session (same Turnip, same output size, same input
seat) and `setupLinuxSession` replaces the Wine launcher with `LinuxProgramLauncherComponent`,
which execs proot from the native library directory:

```
libproot.so --kill-on-exit -r files/linuxfs -w /root
  -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random -b /proc/self/fd:/dev/fd ...
  -b files -b cache -b <XDG_RUNTIME_DIR> -b imagefs -b /storage/emulated/0
  -b cache/shm:/dev/shm -b etc/winnative/empty:/sys/fs/selinux
  -b etc/winnative/proc/<x>:/proc/<x>          (only where Android denies the real file)
  /usr/bin/env -i HOME=/root PATH=... WAYLAND_DISPLAY=wayland-0 XDG_RUNTIME_DIR=<same host path>
     GAMESCOPE_DRM_RENDER_NODE=/dev/dri/renderD128 GAMESCOPE_FORCE_GENERAL_QUEUE=1
     MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink LIBGL_KOPPER_DRI2=true
     VK_ICD_FILENAMES=<rootfs freedreno icd> PULSE_SERVER=unix:<imagefs pulse socket> <user env>
  gamescope --backend wayland --expose-wayland -f -W <w> -H <h> -w <w> -h <h> [-r <fps>] [-e]
  -- /usr/local/bin/winnative-session <mode> [arg]
```

Host paths are bound at their own paths on purpose: the compositor socket, the PulseAudio socket
and the user's storage need no translation on either side, and proot never touches the fds a
dma-buf travels in. `-e` is added for the Steam mode only.

**What runs** is decided by `linuxSessionArgs` from the library entry:

| Entry | `winnative-session` | Notes |
|---|---|---|
| Boot from Edit Containers (no shortcut) | `desktop` | pcmanfm under gamescope's Xwayland: the file explorer |
| Linux entry (`runtime=linux`) | `run <path>` | AppImage with `APPIMAGE_EXTRACT_AND_RUN=1` (no FUSE), `.sh` through bash, else exec |
| Steam entry (`game_source=STEAM`) | `steam steam://rungameid/<id>` | the native arm64 client, `-gamepadui`, after `winnative-steam-install` |
| A Windows entry | refused | the launch reports it and asks for Runtime = Wine |

The session ends when gamescope exits; the activity exits with it. A background session stays
reattachable through `SessionKeepAliveService`, which now records that the environment is a Linux
one so a reattach resolves to gamescope and not Wine.

**The runtime** is `files/linuxfs`, assembled by `tools/linuxfs/build-linuxfs.sh` from Arch Linux
ARM packages (gamescope 3.16.29, Xwayland 24.1, Mesa 26.2 with Turnip and Zink, pcmanfm, foot,
PulseAudio and X client libraries, ibus and glib for steamwebhelper) plus the `overlay/` scripts
and fake `/proc` files. It contains no Valve software. `LinuxRuntime.isInstalled` checks for
gamescope, the session script, and the packaged proot binaries. Installing it into the app is not
yet wired (see below).

**Steam.** `winnative-steam-install` reads Valve's `steam_client_publicbeta_linuxarm64` manifest
from the client-update CDN, downloads the `*_all` and `*_linuxarm64_linuxarm64` zips (sha256
checked), unpacks them into `~/.local/share/Steam`, writes `package/beta = publicbeta`, and
repairs the zip entries Valve packs with backslash separators. The `*_linuxarm64_linuxarm64`
components are the native aarch64 client (`steamrtarm64/steam`, verified as an aarch64 ELF);
the `*_linuxarm64` and `*_steamrt_linuxarm64` components in the same manifest are the x86 client
and its pressure-vessel runtime and are skipped. Valve's own `steam.sh` has no arm64 branch, so
the client is started directly. Proton and FEX are Steam depots the client fetches itself. The
client is downloaded from Valve at first use and never redistributed.

**proot** is the tree at `app/src/main/cpp/proot` (a Termux-derived build without the extension
layer), now in the CMake build as `libproot.so` and `libproot-loader.so`. Changes made for this:
the loader is linked freestanding at `LOADER_ADDRESS` as upstream does; `statx` is translated
(glibc uses it for stat); `PROOT_NO_SECCOMP` disables the seccomp accelerator for debugging. Its
SIGSYS handler is what lets glibc survive Android's zygote filter (`set_robust_list`, `rseq`
return `ENOSYS` instead of killing the process). `targetSdk 28` is load-bearing: it keeps the app
in `untrusted_app_27`, the last domain allowed to exec a file under `files/`.

## Verified so far

- gamescope 3.16.29 and Xwayland 24.1.13 from the rootfs start under proot + qemu-user on the
  build machine with no missing libraries.
- The whole launch line - proot with the bindings above, `env -i`, `winnative-session desktop` -
  runs from the rootfs under qemu-user as a client of a nested sway on the build machine:
  gamescope binds the parent's globals, reports "Initted Wayland backend", enumerates its dma-buf
  formats, and only stops at `vkAllocateMemory` on lavapipe-under-qemu, which has no device memory
  to give. The rig is `scratchpad/host-test.sh`; weston 13 (seat 7) and cage (no pointer
  constraints) cannot host it.
- **gamescope requires `wl_seat` version 8 or newer** from the parent; it refuses the input
  objects otherwise. The compositor advertised 5 and now advertises 9, sending
  `wl_pointer.axis_value120` to version 8+ pointers in place of `axis_discrete`.
- The Steam manifest parser resolves 17 components; the client zips download from
  `client-update.fastly.steamstatic.com/<file>`.
- The device (NP06J, Adreno) exposes `/dev/kgsl-3d0` and a world-readable `/dev/dri/renderD128`.

## Not yet done

- **Installing the runtime.** `linuxfs.tar.zst` has no download or import path in the app yet;
  for now it is extracted by hand into `files/linuxfs`. It should become a content profile.
- **Turnip in the rootfs is Arch's msm build.** The device's GPU is reached through KGSL; the
  compositor's Turnip is a KGSL build. Whether Arch's `vulkan-freedreno` finds the GPU through
  `/dev/dri/renderD128` on this device is the first thing to test on hardware; if not, a glibc
  Turnip built with `-Dfreedreno-kmds=msm,kgsl` goes into the rootfs.
- **Nothing has run on the device yet.** The launch line is WayLandIE's, which is known to work
  on Adreno, but the compositor here is ours.
- Shortcut Settings still shows the Wine pages for a Linux entry; a Linux settings page is owed.
- Extensionless ELFs in the picker; AppImage icons.

## Risks

- ptrace cost if proot stays past bring-up.
- Android's seccomp policy for app processes versus what glibc, Xwayland and gamescope call.
- `/dev/dri` visibility differs per device; the compositor already reports the case where there is
  none, and the Linux side must fail the same way rather than crash.
- gamescope is 50k lines of C++ that expects a desktop; each build option we turn off is one fewer
  place it can assume one.
