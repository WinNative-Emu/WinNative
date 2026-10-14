# gamescope patches carried by the app

The Linux runtime's own gamescope (Arch Linux ARM's 3.16.29-1 package, same build options) with the
patches below, staged from the apk over `/usr/local/bin/gamescope` at each session start
(`LinuxRuntime.SESSION_FILES`); `/usr/local/bin` comes first on the session's PATH, and the runtime
image itself is never touched. `winnative-session` falls back to the runtime's `/usr/bin/gamescope`
when this build cannot load (a runtime installed before the libraries it is linked against), and
says which one runs in the session log's `== gamescope:` line.

The patch set and the build are DroidDeck's (Droid-Deck/DroidDeck `tools/gamescope`). The shipped
binary is its published build, `gamescope.tzst` of Droid-Deck/DroidDeck-Components release
`gamescope-3.16.29-p9`, sha256 `7123a37bdbcd0d76d4bd1d7490e5d2017221e036395fdd48c1e92458594ca769`.
Every library it needs is one the published WinNative runtime ships (`runtime-sonames.txt`, the
runtime's `/usr/lib`), the same 34 the stock binary needs, and it loads against that runtime with
every symbol bound (`LD_BIND_NOW=1`).

To rebuild: `docker run --rm -v "$PWD:/work" -w /work menci/archlinuxarm:base-devel bash
tools/gamescope/build-in-arch.sh` on an arm64 host, then copy `out/usr/local/bin/gamescope` to
`app/src/main/assets/linuxfs/usr/local/bin/gamescope`. Regenerate `runtime-sonames.txt` from the
runtime's `/usr/lib` whenever a new runtime is published.

- `0002-steamcompmgr-fallback-appid-focus.patch` - Armada (armada-os/armada), verbatim.
- `0009-fix-arm64-steam-night-mode.patch` - Armada, verbatim: the ARM64 client packs the
  night-mode property differently; the slider did nothing.
- `0019-steamcompmgr-arm64-virtual-white.patch` - Armada, verbatim: the colour-temperature
  slider's (x, y) arrives as one 64-bit element from the ARM64 client; y is recovered from x.
- `0020-color-p3-red-is-wide-gamut.patch` - Armada, verbatim.
- `0100-realtime-queue-and-gamepad-cursor.patch` - DroidDeck: realtime-priority Vulkan queues on
  request (`GAMESCOPE_FORCE_VULKAN_REALTIME=1`, off by default) without CAP_SYS_NICE, which proot
  never has; and the gamepad-driven cursor sprite following the X pointer that XTest moves (it sat
  frozen).
- `0110-wayland-backend-touch.patch` - DroidDeck: the nested Wayland backend binds `wl_touch` when
  the host seat offers it and hands each finger to wlserver's touch path. Inert here: the app's
  compositor offers a pointer and a keyboard, and sends a tap as a pointer.
- `0111-wayland-pointer-warps-in-passthrough.patch` - DroidDeck: in Passthrough (Big Picture's touch
  mode) the nested pointer's motion moved nothing, so a click landed wherever the pointer had last
  been. The motion now always warps the real pointer as well.
- `0112-restore-iconified-game-on-resume.patch` - DroidDeck: the Steam menu takes input without
  changing the focus window, and a fullscreen wine game minimizes itself when it loses input, so
  after Resume it stayed minimized (a black screen with its caption in the top-left corner). The
  window goes back to NormalState before input returns to it. `GAMESCOPE_RESTORE_FOCUS_WINDOW` on
  the root window asks for the same restore from outside (`winnative-session`'s resume watcher).
- `0114-take-override-redirect-from-mapnotify.patch` - upstream (ValveSoftware/gamescope 3829340),
  verbatim; drop it once the runtime's gamescope includes it. Wine makes a game's window managed
  before mapping it, which sends no X event, so gamescope kept it override-redirect and out of
  `GAMESCOPE_FOCUSABLE_WINDOWS`, and the client kept its loading screen over the running game.
