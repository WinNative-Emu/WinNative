# Building WinNative

## Requirements

| Tool | Version |
| --- | --- |
| Android Studio | Current stable |
| JDK | 17 (not 21 — required by the Android Gradle Plugin) |
| [Git LFS](https://git-lfs.com) | Any |
| Android SDK | compileSdk 35 |
| NDK | `27.3.13750724` — only needed for the native shims and any core built from source |
| CMake | 3.22.1 — same |

## Clone

Submodules and LFS objects are both required. A plain `git clone` produces a tree that will not
build.

```bash
git clone --recursive https://github.com/WinNative-Emu/WinNative.git
cd WinNative
git lfs pull                          # fetches imagefs
git submodule update --init --recursive
```

## Build

**Android Studio:** open the `WinNative` directory, let Gradle sync, then **Build > Build APK(s)**.

**Command line:**

```bash
./gradlew assembleStandardDebug
```

On Windows use `.\gradlew.bat`. Substitute the flavor name for a branded build, for example
`./gradlew assemblePubgDebug`.

## Flavors

All four flavors build the same app. They differ only in application id, which is what lets a
device's game booster or benchmark detection treat WinNative as a known title, and what lets
several forks sit side by side.

| Flavor | Application id | Why |
| --- | --- | --- |
| `standard` | `com.winnative.cmod` | Standard package name for side-loading alongside other forks |
| `ludashi` | `com.ludashi.benchmark` | Forces both max GPU and CPU clocks on some devices (performance-mode trigger) |
| `antutu` | `com.antutu.ABenchMark` | Forces max GPU clocks on most devices (AnTuTu benchmark spoof) |
| `pubg` | `com.tencent.ig` | PUBG package name, which unlocks some game-booster advanced features |

Gradle tasks follow the usual `assemble<Flavor><BuildType>` pattern, and output lands in
`app/build/outputs/apk/<flavor>/<buildType>/`.

## Retro console cores

The APK carries no retro console cores. Each core is built from its own fork under the
[WinNative-Emu](https://github.com/WinNative-Emu) org, and
[Retro-Consoles](https://github.com/WinNative-Emu/Retro-Consoles) packs every core plus the
Dolphin and ARMSX2 runtime data into one `retro-consoles.tzst`. The app downloads and verifies
it on demand from **Settings > Retro > Download console cores**, so a core update does not need
an app release.

To change a core, change its fork and re-run the Retro-Consoles bundle workflow. Nothing in this
repository needs to be rebuilt for a core-only change.

The `:armsx2` and `:dolphin` Gradle modules in this repository are the Android host front-ends
for the PlayStation 2 and GameCube/Wii emulators, not the emulators themselves. How the fork
produces each native core is documented in [`armsx2/UPSTREAM.md`](../armsx2/UPSTREAM.md) and
[`cores/DOLPHIN_EMBED.md`](../cores/DOLPHIN_EMBED.md).

## The libretro frontend

WinNative links against a prebuilt `libretrodroid.aar` rather than building the frontend here.
It is published from the `winnative` branch of
[WinNative-Emu/LibretroDroid](https://github.com/WinNative-Emu/LibretroDroid), and the exact
build shipped by any given APK is pinned by release tag and SHA-256 in
`tools/libretrodroid.version`. See [EMULATOR_CREDITS.md](../EMULATOR_CREDITS.md) for the
corresponding-source statement that goes with it.
