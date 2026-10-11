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
  With DirectAudio chosen, Windows games bypass it for AAudio through the app
  (`docs/direct-audio-integration.md`, GameScope sessions).
- **Settings** for a GameScope container or a shortcut in one show only what a Linux session
  reads: name, container, screen size, refresh rate, FPS limit, audio (PulseAudio or DirectAudio),
  the FEX preset, variables, controls and networking. The Steam launcher, ReShade, Windows
  components, drives, Wine and the emulator pickers are hidden - Proton (ARM64) carries its own
  FEX for 64-bit and 32-bit games, so the FEXCore/Box64/WoWBox64 builds installed in the app are
  never loaded, while a FEX preset is environment and does reach it. Variables with no reader in a
  Linux session (`EnvVarsView.GAMESCOPE_UNUSED`) are dropped from the list; `SteamDeck` is offered
  but off, since it makes games pick Deck presets and a 1280x800 layout.
- **Sign-in** follows the Steam store: `LinuxSteamLogin` writes the store's refresh token into the
  client's `local.vdf` the way the client stores it (AES-256, key SHA-256 of the account name, IV
  ahead as an ECB block, under CRC-32 of the name + "1") with `loginusers.vdf` and `AutoLoginUser`,
  unless the client already remembers that account. Signing the store out removes the same entries.
- **First launch.** The app's compositor imports gamescope's frames with a bionic Turnip loaded
  through adrenotools; on the system Vulkan driver the import fails (`could not import GPU frames`
  in `wayland-*.log`) and the session is black. A user who went straight to Linux Client had no
  such driver, so the installer now fetches one (`WN-Turnip`, pinned and checked by SHA-256) when
  Drivers holds none, and counts it in `isInstalled`, so the button repairs an existing install.
  A Linux session that cannot start - no runtime, no driver, a GPU that is not Adreno, an install
  in progress - says so in a dialog that closes it, and the compositor is not started without its
  driver: it keeps the driver it started with for the life of the process.
- **A session that never starts.** One that dies within 20 s with an error status says so in a
  dialog instead of a black screen. The case that led to it (a Poco F6 on HyperOS 3, kernel 6.1):
  `execve("/usr/bin/env"): Function not implemented`, then `ptrace(PEEKDATA): I/O error`, the
  same with `PROOT_NO_SECCOMP=1`. `proot -v 9` shows why - the first `execve` comes from proot's
  own bionic child with heap pointers tagged in the top byte (`0xb400007b80852f10`), and that
  kernel will not read a tagged address for a tracer, so the path is unreadable and the call is
  answered EFAULT. `tracee/mem.c` strips the tag before every peek and poke. proot is given only
  the options this tree has - it exits on one it does not know, before any session starts.
  Upstream's `-i uid:gid` is one this tree does not have: passing it ended every session on every
  device with `proot error: unknown option '-i'.` until it was taken out. Identity is answered
  instead in `tracee/seccomp.c`, reached from Android's own SIGSYS and from the ENOSYS restart in
  `syscall/exit.c`, not from proot's trace filter - so it is not in `proot_sysnums` and does not
  belong there. `LinuxRuntimeTest.passesOnlyOptionsThisProotAccepts` runs the packaged binary with
  the real option list; a list of accepted options kept by hand is what let `-i` ship.
- **What a session log has to carry.** The build that wrote it, proot's own options, and a signal
  named rather than left as a number, since `137` reads like a chosen exit code when it means the
  session was killed from outside. The guest command is left out: the container's environment
  rides in it, and a user may have typed anything there.
- **A session that stops on its own.** Any non-zero end nobody asked for is said in a dialog, not
  only one inside the first 20 s. Leaving silently is what a crash looks like from the outside,
  and it leaves the one person who can send the log with no reason to go and find it. `137` names
  Android's limit on background processes, which is the likeliest cause of a mid-session death.
- **Ending a session.** proot gets a SIGTERM and 1.2 s before it is killed, so its own
  `--kill-on-exit` can take its tracees down; a tracee that outlives its tracer gets `ENOSYS` from
  every call proot used to answer and spins. The kernel is the backstop either way -
  `PTRACE_O_EXITKILL` is in proot's default options. Inside the session the same rule holds for
  every background job `winnative-session` starts: each one is killed and waited for before
  `reap_orphans`. Steam mode's heartbeat was not, and that is a hang, not a leak - it outlives the
  script by up to an hour, gamescope's reaper waits for it, gamescope is proot's root tracee, so
  proot never exits, `Process.waitFor()` never returns and the activity never leaves the black
  screen. The sweep is no second chance: it matches `ppid == $PPID`, so a job this shell still
  owns is not yet an orphan it can see.
- **HDR.** The compositor's `wp_color_manager_v1` is only advertised once the HDR gate opens, and
  the app always asks for `HDR_MODE_OFF`, so it is never reached. Before that changes, note that
  `get_information` answers every request with a protocol error and the output's preferred
  description is a failed one: gamescope asks for both in one breath, and the error ends its
  connection. A real description has to come first.
- **proot's memory transfers.** Paths and buffers move between proot and a traced program with
  `process_vm_readv`/`process_vm_writev`, one call each, instead of a `ptrace` call per eight
  bytes; a transfer the kernel refuses (an unmapped or read-only page) falls back to `ptrace`.
  Strings are read in 1 KiB pieces so none crosses into an unmapped page.
- **gamescope's refresh rate.** `-r` is the frame limit when one is set and otherwise the panel's
  rate (`WN_REFRESH`); without it gamescope advertises 60 Hz and games cap themselves there.
- **Child process restrictions.** Android 12 and later kill an app's background child processes
  past thirty-two, and a session is well past that: killing proot ends it. The wizard says so once,
  when the Linux client download starts, and offers Developer options where Android 14 and later
  carry the switch; `ChildProcessRestrictions` reads
  `settings_enable_monitor_phantom_procs` to know whether the device still needs it, and on
  Android 12 and 13 it gives the `adb` line instead, since only adb can set it there.
- **The environment a Linux session reads.** `EnvVarsView.forGamescope` is applied to the
  container's variables as the session starts, not only when one is created, so a container made
  before it was a Linux one is read the same way as one made after. `TU_DEBUG` is left as it is:
  `sysmem` reads like a frame cost - it keeps a render pass out of tile memory - but an A/B on the
  RedMagic (Palworld in-world, same spot, GPU 91-93%) put it at 42.8 fps against 41.3 without, so
  the reasoning loses to the measurement.
- **The frame rate the session votes.** The game's layer is posted with the cadence the session is
  aiming for - the frame limit when one is set, otherwise the panel's rate - through
  `ASurfaceTransaction_setFrameRate`. It had been posted with a vote of zero every session, which
  leaves the system to infer the cadence from the rate already being achieved; on a phone whose
  vendor picks clocks from that, a session that has been slowed reads as a session that wants to be.
- **Sharing logs.** The Steam client writes the token that signs its account in to its own
  connection log, and Logs Manager hands those files to whoever the user is sending them to.
  `LogManager.copyShareable` is what every export goes through - both archives and a single file -
  and it copies the client's logs a line at a time with any token replaced. The app's own logs are
  copied unchanged.
- **Setup wizard.** Its second page (after access, before components) offers the Linux Steam
  Client and the Windows one. The Linux card runs `LinuxClientInstaller` - the same install and
  the same state Settings > Stores shows - and carries on while the user goes through the other
  pages; the Windows card moves on to the components and the container that make it up.
- **Logs.** The compositor's `wayland-*.log`, `linux-session.log` and the Steam client's own logs
  (`~/.local/share/Steam/logs`) are part of the Logs Manager (Settings > Debug): listed, shared,
  downloaded and deleted with the rest, under `gamescope/` and `steam-client/` in an archive. As a
  session starts, `LogManager` keeps the nine newest logs of each type - a type is the name without
  its start time - so that with the session's own there are never more than ten.
- **Network page.** The client builds Settings > Internet and its connection indicator on
  NetworkManager, which it looks for on the D-Bus system bus as it starts; without one it leaves
  out the calls its own interface then makes ("StartScanningForNetworks is not a function") and
  shows no connection. The network is Android's, so the session runs a system bus of its own
  (`system-bus.conf`) with `winnative-netmanager` on it: one connection named Android, up while
  Android has a route out, under the address the app was given. It scans and joins nothing.
- **Which Protons run.** Only an ARM64 Proton can: arm64ec Wine with FEX inside it for the game's
  x86 code. Valve's own entry for it stacks the arm64 Steam Linux Runtime underneath, which is
  pressure-vessel and needs user namespaces an Android app never gets - hence `winnative-proton`,
  the same depot without that line. The x86-64 Protons (Experimental, 10, 9, hotfix, GE) run whole
  under Valve's FEX-Emu tool inside the x86 runtime, which needs namespaces and mounts twice over;
  a game pinned to any of them is moved to `winnative-proton` as a session starts. Native Linux
  x86 titles go the same FEX-Emu way and do not run. Valve names a Proton of its own for thousands
  of titles at a priority above the client's default, and installing one has the client fetch
  that x86 Proton, its runtime and FEX; so `LinuxSteamShortcuts` sets every game the store's
  account owns to `winnative-proton` before the client can install it. The anti-cheat runtimes
  a game brings (BattlEye, EasyAntiCheat) are Linux apps to the client, which would fetch the
  scout and soldier runtimes to run them in; `winnative-steam-compat` sets those to the tool as
  well, so they depend on nothing. None of FEX-Emu, either x86 runtime or an x86 Proton is
  needed: games run with all of them uninstalled.
- **Epic games.** They are non-Steam games of the client like the others, but Epic signs a game in
  with an exchange code that lasts five minutes and is spent on first use, so a shortcut cannot
  carry one. The entry names the game in its launch options (`WN_EPIC`), `winnative-launch` hands
  such a game to `winnative-epic-launch`, and that asks `LinuxEpicTokens` in the app - loopback
  only, behind a secret written into the runtime for that session alone - for the command line as
  the game starts. A DRM title's ownership token is written into the runtime and read back through
  Proton's `Z:`, which needs no prefix. A game whose sign-in cannot be had still starts.
- **Steam libraries.** `LinuxSteamLibrary.prepare` gives the client one library folder per place
  the app keeps Steam games: the download folder chosen in Settings > Stores (labelled
  `WinNative`), every connected external drive, and any other folder holding an installed game
  (the app's own storage after Move to Internal, an old download folder). Each is
  `/mnt/winnative-lib/<sha1 of the host folder, 12 hex>`, bound from `<folder>/.winnative-steam`
  with the folder itself bound over its `steamapps/common`, so a game the client installs lands in
  that folder beside the app's own downloads, and `steamapps/downloading` sits on the same
  filesystem as `common`, which Steam needs to finish a download by renaming. Prefixes and shader
  caches stay on the app's own storage (`/mnt/winnative/steamapps/compatdata` and `shadercache`,
  bound into every library): shared storage can hold neither a symlink nor a Wine prefix, and one
  shared directory keeps a game's saves when it moves between libraries. The folders are listed in
  `/etc/winnative/steam-libraries`, which `winnative-steam-library` registers in
  `steamapps/libraryfolders.vdf`, dropping the app's folders that are no longer listed (a drive that
  was removed) and leaving the user's own. A manifest follows its game when the game is moved,
  so the client never keeps one for a folder that has gone; the old `/mnt/winnative` library of
  earlier builds is retired the same way. After a session `adoptClientInstalls` records what the
  client installed, at its real path, and every Steam entry of the game follows a move.
- **A store game with no shortcut.** The library writes a shortcut for one only once it has been
  played, so an installed GOG or Epic game is also taken from the store's own records
  (`LinuxGogGames`, `LinuxEpicTokens.installedGames`) under the same id a shortcut would give it.
  GOG records where a game was installed but not what to run: that is the primary play task of the
  `goggame-<id>.info` the installer leaves beside it.
- **Non-Steam games.** `LinuxSteamShortcuts` keeps the library's custom, itch.io, GOG and Epic entries in
  the client's `userdata/<account>/config/shortcuts.vdf` (binary VDF), each mapped to
  `winnative-proton` in `CompatToolMapping`. The entries it wrote are listed in
  `winnative-shortcuts` beside the file, so ones the user added in the client are left alone and
  one whose game left the library is removed - at once when no client is running, else at the next
  session start. Such an entry in the GameScope container launches as
  `steam://rungameid/<appid << 32 | 0x02000000>`, which the session script sends down `steam.pipe`
  once the interface has loaded: given as an argument it is launched within a second of sign-in,
  before the interface answers the launch's prompts, and hangs at `ShowInterstitials`. The Proton
  prefix (`compatdata/<appid>`, about 370 MB) holds the saves and is not removed with the entry.
- **The HUD** counts a game frame per commit of gamescope's window, not per plane: gamescope
  presents through a toplevel and a synchronized subsurface for each layer, and the game moves
  between them. The renderer name is read from the Proton processes' mapped modules
  (`WaylandRendererProbe.probeLinuxSession`), since the window itself is always gamescope's.
  Frame generation runs in the app's compositor, so its output rate needs nothing from gamescope.
- **The WN button** on the Virtual Gamepad is the pad's guide button (`BTN_MODE`): Steam opens
  its menu on it. It is an ordinary control element (`GAMEPAD_BUTTON_GUIDE`, icon 40) and is only
  drawn in Linux sessions and the editor.
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
gamescope, the session script, and the packaged proot binaries.

**Installing.** Settings > Stores > Steam > Linux Client (`LinuxClientInstaller`) installs the
runtime and then the Steam client in one go. The runtime is the `linuxfs.tar.zst` asset of the
`Assets` release on WinNative-Emu/Components (zstd 19, 128 MiB window), checked against the
SHA-256 in `linuxfs.json` and unpacked by the app's native extractor into a staging directory that
replaces `files/linuxfs` only once it is complete; a runtime being replaced keeps its `root` home.
`build-linuxfs.sh` writes both files; upload them, plus the package list, to that release. The
client steps are the ones below, done by the app with a progress bar; Valve's zips carry a short
prefix Android's own zip reader refuses, so they are read with Commons Compress.

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

On the NP06J (Adreno 840, Android 16, app uid 10516), 2026-09-17:

- The proot rootfs runs as the app's own uid. Android's app seccomp policy traps `setuid`,
  `setgid`, `setreuid`, `setregid`, `setfsuid`, `setfsgid` and `get_robust_list`; proot answers
  the id calls itself (a process without privileges may only take an id it already holds), and
  `libwnsession.so` answers `get_robust_list` in the process (see below).
- gamescope 3.16.29 starts as a client of `libwnwayland.so`, drops its libdecor frame once the
  compositor confirms `xdg_toplevel.set_fullscreen`, and delivers input: it holds two
  `wl_pointer`/`wl_keyboard` pairs (one on its input thread), so the compositor sends seat events
  to every such object of a client. pcmanfm shows, its menus open on tap, GTK icons render
  (glycin's bwrap sandbox needs `/proc/sys/kernel/overflowuid`, which is faked).
- **The GPU.** Arch's Turnip cannot open the GPU (msm only); Termux's 24.2.6 KGSL build rejects
  the Adreno 840; Mesa 26.2.2 built with `-Dfreedreno-kmds=msm,kgsl` drives it (vkcube at 121 fps
  through the compositor). `/dev/dri` is not visible to an app process at all (not even `stat`), and
  neither is `/proc/net` nor `NETLINK_SOCK_DIAG`: only `/dev/kgsl-3d0` is. gamescope refuses to
  offer `linux-dmabuf` without a DRM node from `VK_EXT_physical_device_drm`, so the runtime
  presents the KGSL device as `/dev/dri/renderD<minor>` (a proot bind) with the sysfs entries
  libdrm reads under `/sys/dev/char/<maj>:<min>`, and the Turnip patch reports that device as both
  nodes. Consumers then run PRIME ioctls on it, which KGSL cannot answer; `libwnsession.so`
  keeps an fd/handle table for `drmPrimeFDToHandle` and friends on that device. With that, Xwayland
  runs glamor on Zink and every Vulkan client under gamescope gets a swapchain; the compositor log
  shows gamescope presenting through dma-buf.
- **Steam.** The arm64 client installs, updates itself (exit code 42 = restart, as `steam.sh`
  handles), loads `steamui.so` and `vgui2_s.so` (GTK 2 from Debian, `openal`, `libvdpau`), shows its
  update window on the GPU, passes its System V semaphore and robust-mutex checks, connects to
  Steam's network, and launches `steamwebhelper`; the gamepad UI comes up and is usable.
- **Steam's IPC peer check.** The client identifies a websocket peer by running
  `lsof -P -F upnR -i TCP@127.0.0.1:<port>` (`GetIPCConnectionDetails`), which cannot work in the
  app sandbox (`/proc/net` and `sock_diag` are denied). `libwnsession.so` records every loopback
  port a session process binds, connects or accepts, with its peer, and answers that lsof from the
  record. The client splits the `n` line on `->` and requires two halves, takes the port from the
  left one, requires `u` to be its own uid, discards a record whose `R` is its own pid as a leaked
  fd, and finally accepts the peer if it is the client itself or a descendant; `Checked: %d/%d` is
  peer pid over the client's pid, so `Checked: 0/<pid>` meant no pid had been parsed at all.
- libdrm-based checks: `drmGetDevice2` on the presented node reports a platform device
  `kgsl-3d0`; Turnip reports `hasRender=1` for it.

On the RedMagic (Adreno 840), 2026-09-20:

- **The desktop.** gamescope shows one program at a time and cannot hold a panel and its windows,
  so `winnative-session desktop` runs XFCE 4.20 on labwc (`WLR_BACKENDS=wayland`, pixman renderer)
  as a client of the app's compositor. A nested wlroots compositor takes modifiers from
  `wl_keyboard.modifiers` alone, which the compositor now sends; without it Shift never reached a
  program on the desktop.
- **Taps.** Touchscreen mode hands a touch to the compositor (`nativeSendPointer`), which maps it
  through the scale mode's letterbox; the view's own stretch landed taps some 35 px off. The Steam
  client sets `STEAM_TOUCH_CLICK_MODE` to 4 (passthrough), in which gamescope's Wayland backend
  turns a nested pointer's motion into touch motion without a touch down, so the cursor never
  moves and a click lands where it was left. gamescope's Wayland backend takes no `wl_touch`
  either, so the session holds the mode at 0 (hover): the position warps the cursor and the
  button clicks.
- **Terminals.** glibc 2.42+ uses the termios2 ioctls (`TCGETS2`, `TCSETS*2`); Android's SELinux
  policy allows an app only the older ones on a pty, so `isatty` failed everywhere (bash without
  a prompt, `stty: Permission denied`). proot's seccomp filter traces those four requests by
  argument and rewrites them; every other ioctl is allowed before the syscall list.
- **Updates.** `linuxfs.json` carries `version` (the build's UTC time, also in
  `/etc/winnative/version`). The installer offers a newer runtime, Proton (`proton-arm64.json`,
  once it carries a version) or Turnip (`linux-turnip.json` + `linux-turnip.tar.zst` holding
  `libvulkan_freedreno.so` and `name`; not published until a driver newer than
  `LinuxRuntime.TURNIP_BUILD` exists). An update keeps `/root`, `/mnt/winnative`,
  `/opt/winnative-proton` and `/etc/machine-id`: the client stayed signed in and nine prefixes
  came through byte for byte.

## Not yet done

- Processes a desktop program double-forks are reparented to the app (the subreaper) and stay
  zombies until the session's cleanup sweep; they are only reaped then.

- Xwayland's swapchain through the gamescope WSI layer still fails once at startup
  (`CreateSwapchainKHR failed with VK_ERROR_INITIALIZATION_FAILED`) and recovers; harmless so far.
- `steam-runtime-launcher-service` is not shipped for arm64; Steam disables it and goes on.
- Shortcut Settings still shows the Wine pages for a Linux entry; a Linux settings page is owed.
- Extensionless ELFs in the picker; AppImage icons.
- gamescope logs `Changed refresh` on every presentation-feedback event because the compositor's
  reported refresh jitters around 120 Hz; sending a fixed refresh would quiet it.

## Risks

- ptrace cost if proot stays past bring-up.
- Android's seccomp policy for app processes versus what glibc, Xwayland and gamescope call.
- `/dev/dri` visibility differs per device; the compositor already reports the case where there is
  none, and the Linux side must fail the same way rather than crash.
- gamescope is 50k lines of C++ that expects a desktop; each build option we turn off is one fewer
  place it can assume one.
