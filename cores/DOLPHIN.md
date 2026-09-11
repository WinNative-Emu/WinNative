# Dolphin (GameCube / Wii)

> **Packaging note (current):** GameCube and Wii use the embedded Dolphin core by default.
> It is built in [WinNative-Emu/dolphin](https://github.com/WinNative-Emu/dolphin) on the
> `winnative` branch, where `winnative-emucore.yml` publishes `libmain.so`. The app downloads
> it in the [Retro-Consoles](https://github.com/WinNative-Emu/Retro-Consoles) bundle and loads
> it from `files/retro/bundle/cores/`; it is not committed to the APK or Git LFS. See
> [DOLPHIN_EMBED.md](DOLPHIN_EMBED.md) for the embedded-core build and patch details.

## Libretro fallback

The older libretro route remains available only when the embedded Dolphin path is disabled.
It was previously a hand-placed libretro buildbot nightly committed to
`app/src/main/jniLibs/arm64-v8a/`, which is why `RetroCoreManager.resolveCore` used to
special-case it to `nativeLibraryDir`. That special case is gone.

Sys data (game INIs, fonts) is under `app/src/main/assets/retro/dolphin-emu/Sys` and
copied to app files on first launch (`RetroCoreManager.ensureDolphinSys`).

User data (saves, memcards, `Dolphin.ini`) lives under `files/retro/saves/User`
(`ensureDolphinUser` creates `GC/{USA,EUR,JAP}/Card A|B` and pins SlotA GCI folder).

## Graphics / Adreno

dolphin-libretro renders through **libretrodroid GLES** (system Adreno GLES 3.x).

WinNative **Turnip / adrenotools** Vulkan drivers (`contents/adrenotools`,
`graphics_driver/*`) are for Wine containers and PS2 Vulkan — they are **not**
loaded for this core. Preferring system Adreno GLES is correct for libretro.

## Performance defaults (`RetroCoreOptions`)

| Option | Value | Why |
|--------|-------|-----|
| `dolphin_cpu_core` | `4` (JITARM64) | ~5–10× vs Cached Interpreter |
| `dolphin_main_cpu_thread` | `disabled` | Dual core SIGSEGV in Adreno GLES mid-game |
| `dolphin_efb_scale` | `1` | Native IR |
| `dolphin_efb_to_texture` / XFB | `enabled` | Avoid EFB/XFB→RAM copies |
| `dolphin_dsp_hle` | `enabled` | Fast audio DSP |
| `dolphin_fastmem` / arena | `disabled` | RWX/mmap SIGSEGV under Android SELinux + Adreno |
| `dolphin_shader_compilation_mode` | `0` (sync) | Async skip → flicker/stale frames on GLES |
| `dolphin_skip_dupe_frames` / Early XFB | off | Ghost/old-frame blending on Adreno |
| Audio | larger Oboe buffer | `preferLowLatencyAudio=false` for Dolphin |

If a title hangs on memcard check, try Dual Core **off** for that game.

Upstream core nightlies: https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/dolphin_libretro_android.so.zip  
Source reference: https://github.com/dolphin-emu/dolphin and https://github.com/libretro/dolphin

Local rebuild of the core (optional, heavy): clone libretro/dolphin and follow its Android
libretro NDK build; install the resulting `.so` with the `lib` prefix above.
