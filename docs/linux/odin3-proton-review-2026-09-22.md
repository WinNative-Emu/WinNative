# Odin 3 and Linux Proton review — 2026-09-22

## Evidence and limits

The supplied Wayland log starts a compositor on Adreno 830 at 12:50:55 and reports zero guest windows. The application log reports a nonzero Linux session exit at 12:51:09, triggering `LinuxSessionUnavailable`. It does not contain the child process output or exit status. This establishes early Linux session termination, not a confirmed Steam crash or a specific GPU fault.

The `26.2.2` Android-driver warning uses the Linux Mesa version in the Android renderer's driver lookup. It is not proof of which driver the separate Wayland compositor loaded. The `libjpeg.so` namespace errors arise from optional vendor-driver dependency preloads; there is no supplied native crash trace establishing them as fatal.

An Odin 3 reproduction still needs its Linux session log. The x86_64 AVD verifies Android installation, UI, and process-launch behavior; it cannot verify ARM64 Steam, KGSL rendering, or Windows gameplay.

## Corrections

- Require an installed Turnip library for the Android Wayland compositor. Previously any installed Android driver's metadata satisfied the Linux install gate, and the compositor could select a proprietary Qualcomm driver. Linux Mesa selection remains separate.
- Launch proot with a structured argument list. Preserve empty arguments, spaces, and quotes. Capture stdout/stderr from the host process start, before the guest script can redirect output. Record launch failures and exit status in a dated Linux session log.
- Use one shared Proton launch helper for Valve, GE, and CachyOS. Existing downloaded tools are updated by the registrar at the next session start. Keep their own Wine and FEX files intact.
- Prepare DirectAudio for the selected Proton, checking its Wine unix-call table before installing the driver. Update the selected prefix and first-launch templates; remove stale DirectAudio overrides for incompatible builds. The old wrapper checked a nonexistent `directaudio/lib/wine` directory, while staging uses `directaudio/aarch64-unix` and architecture siblings.
- Update generated wrappers/manifests atomically, only when content changes. Stop deleting/recreating the default compatibility tool every fifteen seconds. During live refresh, leave Steam's saved configuration alone and stop periodically editing audio registries while games run.
- Populate per-title mappings on the first creation of CompatToolMapping, not only subsequent launches. Preserve selected adopted GE/CachyOS tool names.
- Enable Proton logs by default for these Linux sessions, respecting an explicit PROTON_LOG setting, and place them alongside the session log.
- Synchronize the APK scripts and runtime-build overlay so a runtime rebuild cannot restore the older launch implementation.

## Bannerlator comparison

Reviewed `The412Banner/Bannerlator`, branch `feat/linux-gamescope-runtime`, commit `7b012b0913b51d315059072e257e810382de6c57`, fetched from GitHub on 2026-09-22.

Its latest source/documentation includes the Turnip compositor fallback correction, seccomp-probe removal, and mappings for owned games/anti-cheat runtimes. WinNative already removed the seccomp probe and maps anti-cheat runtimes. This change corrects its incomplete Turnip gate. The newer owned-game mapping is a download-selection improvement; it does not establish the cause of the supplied early session exit.

Its third-party Proton wrappers remove the Steam Linux Runtime dependency to avoid pressure-vessel's unavailable user namespaces and prepend the input interposer. WinNative retains both behaviors. Bannerlator's progress log reports successful GTA V Legacy gameplay on GE 11-7, but also unresolved GE/CachyOS failures with GTA V Enhanced. It explicitly reports that swapping its own FEX into GE broke exception handling. No such component mixing is introduced here.

Reference: https://github.com/The412Banner/Bannerlator/tree/7b012b0913b51d315059072e257e810382de6c57

## Validation

- PUBG debug APK and instrumentation APK assembled successfully.
- Five Python regression tests cover source/asset parity, stable manifests, preserved tool choices, first-run mapping, live-refresh configuration preservation, compatible/incompatible DirectAudio preparation, argument forwarding, and exit status.
- AVD installed and removed the actual `GE-Proton11-7-aarch64.tar.gz` and `proton-cachyos-11.0-20260703-slr-arm64.tar.xz` archives; both archive test runs passed, including traversal/x86 rejection.
- AVD launcher tests verify argument preservation, early stderr capture, exit status, and failure reporting when the executable cannot start.
- Components navigation tested in portrait and landscape; landscape screenshot inspected. No UI strings or navigation structure changed.

## Device retest

Install the new PUBG debug APK, close any retained Linux session, and start Steam again so the runtime scripts and installed Proton wrappers refresh. Select the downloaded GE/CachyOS ARM64 entry in the game's Steam Compatibility settings and launch it. If startup still fails, export the dated `linux-session-*.log`; for a game failure also include `steam-<appid>.log`. Include the game and Proton version. These logs are needed before claiming the Odin failure or gameplay is fixed end to end.
