# Android and Linux driver review

Reviewed against WinNative `feature/wayland-gamescope` at `fd74ab12` and WinNative-Emu/Drivers main on 2026-09-21. Linux implementation and release recipes live on [Drivers feature/linux-drivers](https://github.com/WinNative-Emu/Drivers/tree/feature/linux-drivers).

## What runs where

| Consumer | Driver ABI | Installation and selection |
| --- | --- | --- |
| Android compositor, Android emulators and the existing Android graphics paths | ARM64 Android bionic, Adrenotools | `files/contents/adrenotools/<name>`; existing container/emulator graphics selectors |
| GameScope, Linux applications and games inside the Linux runtime | ARM64 Linux glibc, KGSL plus Wayland/X11 WSI | `files/contents/linux-drivers/<name>`; Settings → Drivers → Linux → Select |

GameScope uses both sides: Linux renders through its glibc driver; the host compositor remains an Android process with its own Android driver. An Android ZIP cannot replace the Linux ICD library. Both can be called `libvulkan_freedreno.so`; filename alone does not identify their ABI.

The earlier Bannerlator/Banners-Turnip Wayland work is another bionic build with Wayland WSI. Its source notes remain in the prior workspace at `/home/max/Build/Claude/Wayland/proton-wine/android/wayland-deps/TURNIP.md`. Those eight bionic variants are not the glibc driver shipped by this GameScope branch.

## Linux source and original build

The tracked recipe is `tools/linuxfs/build-turnip.sh`. It downloads [Mesa 26.2.2](https://archive.mesa3d.org/mesa-26.2.2.tar.xz), verifies SHA-256 `eeb29ca7e56cfaa8e8a79538dcf834e3b18e501c31bef5145e959ea437cc4216`, extracts it and applies `tools/linuxfs/turnip/*.patch`:

* `kgsl-drm-node.patch`: reports KGSL device major/minor numbers for the compositor's dma-buf feedback and advertises `VK_EXT_physical_device_drm` for that device.
* `kgsl-no-calibrated-timestamps.patch`: removes the unimplemented KGSL GPU timestamp callback, guards its caller and disables calibrated timestamps/present timing when that callback is unavailable.

The build uses `aarch64-linux-gnu-gcc/g++`, Meson and Ninja, against the Arch Linux ARM rootfs assembled by `tools/linuxfs/build-linuxfs.sh`. Key options are `vulkan-drivers=freedreno`, `freedreno-kmds=msm,kgsl`, `platforms=wayland,x11`, no Gallium/OpenGL/EGL/GLX/LLVM, release optimization. The result is stripped before packaging. The rootfs starts with Arch packages, including Mesa and GameScope; the custom KGSL Turnip replaces Arch's MSM-only Vulkan driver. This change updates Turnip, not the other Mesa libraries or GameScope itself.

Original local inputs and intermediate outputs still exist:

* `/home/max/Build/Claude/Wayland/turnip/mesa-26.2.2`, `cross-aarch64.ini`, `setup.log`, `build/`.
* `/home/max/Build/Claude/Wayland/linuxfs/work/turnip/mesa-26.2.2`, `cross.ini`, `build/`, `libvulkan_freedreno.so`.
* `/home/max/Build/Claude/Wayland/linuxfs/work/rootfs` supplies the original sysroot. Its Meson records confirm the options above.

Commit `8b0cd7d2` introduced the app-bundled library at `app/src/main/assets/linuxfs/usr/lib/libvulkan_freedreno.so`. It identifies itself as Mesa 26.2.2, has build ID `b60cd6d31c6bf4f951a78e1279f30e9653a8316e`, and SHA-256 `7a390576bacb603ab7c17c0924a0b63e8f1307ed3413dd20cbbbdb70dc8f53f1`. The prior WinNative workspace holds identical bytes. The leftover `linuxfs/work/turnip/libvulkan_freedreno.so` differs (`0fa4747c91df906570e5ab0a23be67c6d17e0b03501e6c9cad06ce0312a794bb`), so it is not evidence of an exact reproducible rebuild of the bundled binary.

`LinuxRuntime.syncSessionFiles` refreshes the bundled library in the rootfs at session startup. Previously a newer `linux-turnip.tar.zst` plus `linux-turnip.json` from [Components/Assets](https://github.com/WinNative-Emu/Components/releases/tag/Assets) could override it through `files/linux-driver`. Linux Client still supports that channel. Settings now exposes that legacy driver as a separate entry and preserves its previous version-based default until the user explicitly selects a driver. A selected custom package survives runtime refreshes; removing it selects bundled Mesa.

## Android builds and distribution

[WinNative-Emu/Drivers](https://github.com/WinNative-Emu/Drivers) uses `build_wn_turnip.sh` to clone [Mesa main](https://gitlab.freedesktop.org/mesa/mesa) once, then build both variants from that same commit. `build_turnip.sh` uses NDK r26d, API 34, the Android platform and KGSL. Each release contains `WN-Turnip-<version>-{b,p}_Axxx.zip`, with `meta.json` and `libvulkan_freedreno.so`.

Common patches add the A810/A829 GMEM workaround and IDs, A825 support, A7xx quirks/UBWC hint, gralloc fixes, the IMapper5 backend, and Android UBWC swapchain usage. Balanced adjusts the GMEM bandwidth multiplier from 11 to 10. The old draw-call threshold anchor is absent in current Mesa and explicitly skipped. Performance currently uses the same bandwidth multiplier and adds `KGSL_CONTEXT_PWR_CONSTRAINT`, `KGSL_CMDBATCH_PWR_CONSTRAINT`, PWR_MAX at queue creation, and refresh every 1000 submissions. It requests maximum clocks; thermal limits and kernel policy still apply.

`verify_patches.sh` checks logs, but some anchor drift is only warned about. The Linux recipe instead fails on unrecognized patch drift, excluding only the explicitly retired draw-call threshold.

`.github/workflows/build.yml` runs Wednesdays at 12:00 UTC (`0 12 * * 3`). Scheduled runs publish; manual runs default to drafts; pushes/PRs build previews. Published Android assets can also be mirrored to `nicholasx417/WinNative-Components`, tag `WinNative-Turnip`, when `COMPONENTS_TOKEN` exists. Before this change Settings used that mirror by default. New Settings defaults include the direct Drivers repository plus the existing mirror for Android; Linux uses the direct Drivers repository. Existing saved Android repository choices remain intact and the restore-defaults action adds missing defaults.

## New Linux build and package contract

`Drivers/build_linux_turnip.sh` builds `WN-Linux-Turnip-<version>-{b,p}_Axxx.zip` from one fresh Mesa main commit. It reuses the WN GPU and power-tuning scripts, excludes Android gralloc/AHB patches, and applies the Linux KGSL fixes in `linux/patch_mesa.py`. Native CI builds on Ubuntu 24.04 ARM64; local cross builds accept `LINUX_SYSROOT`. The script preserves each applied source diff, patch log and Mesa SHA. The ZIP metadata records `platform=linux`, `architecture=aarch64`, `libc=glibc`, variant, Mesa source/SHA, recipe commit and library SHA-256.

The Linux series starts at `WN Linux Turnip 0.1.0-b` and `WN Linux Turnip 0.1.0-p`, with `WN-Linux-Turnip-0.1.0-{b,p}_Axxx.zip` archives. It increments the patch version after published stable Linux releases independently of Android; preview builds and drafts do not advance it. Metadata keeps the Mesa version separately from the WN package version.

The app unpacks into a unique temporary directory with size/path checks, inspects the ELF64/AArch64 DT_NEEDED entries (`libc.so` versus `libc.so.6`), verifies an optional library digest and platform metadata, then atomically installs into the correct store. An incorrect destination choice is corrected and shown in a localized message. Contradictory metadata, unsupported architecture, mixed libraries, unsafe paths and duplicates are rejected. Linux ICD paths are generated on the device. Android-only importer calls reject Linux packages.

The release feed separates WN Linux assets by the reserved `WN-Linux-` prefix. Local imports are classified from their contents. New controls use the existing pane navigation registry, and all new strings are present in every existing locale.

The Linux workflow uses the same Wednesday 12:00 UTC schedule and separate `linux-v...` tags; Linux releases do not replace Android as GitHub's latest release. GitHub only executes schedules from the default branch. Keeping the recipe exclusively on a feature branch therefore requires a default-branch scheduler or merging the workflow before automatic weekly execution is active.

## Verification

On 2026-09-21 both Linux variants built from Mesa `5ff61a7646d29b54c324af0a60aa3bfb5cdd24d1` (`26.3.0-devel`). Package checks verified distinct binaries, matching Mesa commits, ARM64/glibc ABI, Wayland/X11 entry points and PWR_MAX only in performance. Both libraries load with all dynamic symbols resolved against the original rootfs under QEMU. Native Ubuntu ARM64 CI also built both variants successfully. The public `linux-v0.1.0` release contains `WN-Linux-Turnip-0.1.0-b_Axxx.zip` and `WN-Linux-Turnip-0.1.0-p_Axxx.zip`. The native build/release workflow run `35666533240` passed, and the in-app Linux catalog discovered the release and downloaded and installed both variants successfully. All four AVD tests passed, and actual Settings imports were verified with both wrong destination choices; the app corrected each destination and displayed the driver in the correct list.

`./gradlew :app:assemblePubgDebug :app:assemblePubgDebugAndroidTest` builds the app and instrumentation APK. AVD API 35 supports ARM64 translation and runs the actual PUBG APK. `DriverPackagesTest` tests real Android/Linux ZIP installation, selection, removal fallback, duplicates, wrong metadata and malformed/traversal packages. `DriversScreenTest` exercises the platform controls through `SettingsNavBridge`, the install destination dialog, and captures screenshots.

For these device tests, place real ZIPs at the app-private `files/driver-tests/{android,linux-b,linux-p}.zip` using `adb shell run-as com.tencent.ig`. Run:

```sh
adb shell am instrument -w \
  -e class com.winlator.cmod.runtime.content.DriverPackagesTest,com.winlator.cmod.feature.settings.DriversScreenTest \
  com.tencent.ig.test/com.winlator.cmod.StoreUiTestRunner
```

AVD validates the manager and UI, not Adreno/KGSL execution, frame pacing, power behavior or game compatibility. Those need a physical Adreno device. The existing bundled 26.2.2 library remains the fallback rather than silently replacing it with an untested-on-hardware driver.
