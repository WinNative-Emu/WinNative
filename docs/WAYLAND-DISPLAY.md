# Wayland display server

WinNative can run a session on an embedded Wayland compositor instead of its X server. Wine's
`winewayland.drv` connects to the compositor over a socket, the game's Vulkan swapchain is shared
as dma-buf frames, and the compositor blits them to the screen through Turnip. The X server is
still created for input bookkeeping but never draws.

The compositor (`app/src/main/cpp/waylandcomp`) and the session design were written by
[The412Banner](https://github.com/The412Banner) for
[Bannerlator](https://github.com/The412Banner/Bannerlator) and ported here; see
[CREDITS.md](../CREDITS.md).

## Choosing the display server

- **Container Settings → Display → Display Server** sets the container default (X11 or Wayland).
- **Shortcut Settings → Display → Display Server** follows the container until it is changed and
  saved for that shortcut; only that shortcut is affected. Setting it back to the container's
  value removes the override again.
- The choice is stored as the `displayBackend` extra (`x11` or `wayland`).

Wayland can only be selected, and is only used at launch, when both hold:

1. The device has an Adreno GPU. The compositor imports the game's frames with Turnip through
   adrenotools; Mali and Xclipse devices stay on X11.
2. The selected Wine/Proton ships `lib/wine/aarch64-unix/winewayland.so` (or the x86_64-unix
   variant) and `lib/libvulkan_freedreno_wayland.so`, the Wayland Turnip the game renders on.
   WinNative writes its own Vulkan ICD manifests for the bundled Turnips as
   `share/vulkan/icd.d/wayland_turnip[_variant].json` and points winewayland at the chosen one
   through `BANNER_WAYLAND_VK_ICD`, so the donor's manifest names are never relied on.
   The stock WinNative Proton ships only `winex11.so`, so the Display Server dropdown stays on
   X11 with it. Install a Wayland layer from the Contents screen, for example Banner's
   `proton-11.0-2.1-arm64ec-wayland-v16.wcp` from the Bannerlator Wayland pre-releases
   (installs as `Proton-11.0-2.1-arm64ec-16`), then pick it as the container's Proton. The
   dropdown enables as soon as the selected Proton passes the check.
3. Or borrow the files. Once one Wayland Proton is installed, any other arm64ec Proton with the
   same Wine major version can use it as a donor: the Display Server row offers Wayland and says
   which Proton the files come from. Saving Wayland copies winewayland.so, winewayland.drv, the
   Wayland Turnips, the Wayland client libraries, the bundled Mesa EGL
   and gallium libraries and the xkb data into the selected Proton on a
   worker thread and shows a toast when done. A launch that finds the files still missing starts
   the copy, runs on X11 that time, and says so. winewayland.so is a Wine unixlib, so a donor
   from a different Wine major version is never used.

When a shortcut or container asks for Wayland and either condition fails, the session starts on
X11 and a toast says so. Before Wine launches on Wayland the launcher creates the runtime
directory, extracts the xkb keymap into it and waits up to eight seconds for the compositor's
`wayland-0` socket, so Wine never starts against a socket that does not exist yet. The check is cached per Wine version and refreshed when contents are
installed or removed.

## What a Wayland session changes

- `WAYLAND_DISPLAY=wayland-0` and `XDG_RUNTIME_DIR=<app files>/.wayland-rt` are exported to the
  guest; `DISPLAY` is not. The Proton's `lib/` directory is placed first on `LD_LIBRARY_PATH`
  because `winewayland.so` links the Wayland client libraries with unversioned sonames.
- The prefix registry gets `Software\Wine\Drivers\Graphics = wayland` and Wine's `shell` desktop
  is seeded at the container size; an X11 launch restores both. `winex11.drv` is disabled
  through `WINEDLLOVERRIDES` for the session so Wine falls through to winewayland.
- `BANNER_WAYLAND_VK_VARIANT` selects the bundled Wayland Turnip by GPU generation (`a7xx` for
  Adreno 710/720/722, `a8xx` for Adreno 8xx). A value set in the container or shortcut
  environment wins, as does `BANNER_WAYLAND_VK_ICD=<path to icd.json>`.
- `GALLIUM_THREAD=0` is exported unless the user set it: Mesa's threaded GL context has a helper
  thread that crashes outside Wine's signal handling.
- Touch, on-screen controls, keyboard and mouse still go through the X server's input path and
  are mirrored to the compositor; relative mouse input and pointer locks are delivered as deltas.
  The soft keyboard commits text through `zwp_text_input_v3`; the clipboard is synced both ways.
- The FPS limiter, fullscreen stretch and the FPS HUD follow the session's normal controls.

## Environment switches

| Variable | Effect |
|---|---|
| `BANNER_WAYLAND_ZERO_COPY=1` | Present a fullscreen game window on its own display layer without a copy (also exports `BANNER_WSI_AHB=1`). |
| `BANNER_WAYLAND_UBWC=0` | Advertise linear buffers only; default advertises UBWC (compressed) layouts. |
| `BANNER_WAYLAND_NO_RENDER_NODE=1` | Debug: advertise no DRM device in the dma-buf feedback. |
| `BANNER_WAYLAND_VK_VARIANT` / `BANNER_WAYLAND_VK_ICD` | Game-side Turnip selection, see above. |

Session logs are written to `Android/data/<package>/files/wayland-logs/`, one file per session.

## Lifecycle

The compositor thread is started once per process and kept; a session begins when the display
activity attaches its surface and ends when the guest has been terminated. Ending a session
disconnects every client, drops the screen surface and resets the compositor's per-session
state, so the next launch, X11 or Wayland, starts clean. A background session that is reattached
to a new activity keeps the compositor running and only receives the new surface.

The compositor's own Turnip driver is the one selected in the graphics driver settings at the
time of the first Wayland launch in a process; the game's driver is chosen per launch.
