# Emulator & Library Credits

WinNative's retro-console features are built on open-source emulators and libraries.
This project is distributed under the **GNU General Public License v3.0** (see [LICENSE](LICENSE)).
In compliance with the GPL and the other licenses below, the corresponding source code
for every GPL/copyleft component is available from the upstream projects linked here,
and their copyright and license notices are preserved.

## PlayStation 2

PS2 games are recognized and imported into the library. PS2 emulation is built on
**ARMSX2** (a GPL-3.0 fork of PCSX2) and is in active development.

| Component | Role | License | Source |
| --- | --- | --- | --- |
| ARMSX2 | PS2 emulation + RetroAchievements | GPL-3.0 | https://github.com/ARMSX2/ARMSX2 |
| PCSX2 | Upstream project ARMSX2 is derived from | GPL-3.0 | https://github.com/pcsx2/pcsx2 |

## GameCube / Wii

GameCube and Wii games are recognized and imported into the library. GC/Wii emulation is
built on an embedded build of **Dolphin**, rendered on Vulkan and driven entirely by
WinNative (Dolphin's own UI is stripped). Local and online multiplayer use Dolphin's own
native NetPlay engine.

| Component | Role | License | Source |
| --- | --- | --- | --- |
| Dolphin | GameCube/Wii emulation + NetPlay | GPL-2.0-or-later | https://github.com/dolphin-emu/dolphin |

## Audio

The DirectAudio audio driver is a native Wine → Android AAudio mmdevapi driver by
**The412Banner**. WinNative ships his release binaries **unmodified**; checksums, the source
offer and the verbatim license files are in `app/src/main/assets/directaudio/`.

| Component | Role | License | Source |
| --- | --- | --- | --- |
| DirectAudio | Wine → Android AAudio audio driver, incl. microphone capture | LGPL-2.1-or-later | https://github.com/The412Banner/directaudio |

## Bundled libretro cores

Each core is shipped as an unmodified `arm64-v8a` build and loaded through LibretroDroid.

| System | Core | License | Source |
| --- | --- | --- | --- |
| Game Boy / Color | Gambatte | GPL-2.0 | https://github.com/libretro/gambatte-libretro |
| Game Boy Advance | mGBA | MPL-2.0 | https://github.com/libretro/mgba |
| Genesis / Master System / Game Gear | Genesis Plus GX | Genesis Plus GX License (non-commercial) | https://github.com/libretro/Genesis-Plus-GX |
| NES | FCEUmm | GPL-2.0 | https://github.com/libretro/libretro-fceumm |
| Nintendo 64 | ParaLLEl N64 | GPL-2.0 | https://github.com/libretro/parallel-n64 |
| Nintendo 64 | Mupen64Plus-Next | GPL-2.0 | https://github.com/libretro/mupen64plus-libretro-nx |
| PlayStation | Beetle PSX (mednafen_psx) | GPL-2.0 | https://github.com/libretro/beetle-psx-libretro |
| SNES | Snes9x | Snes9x License (non-commercial) | https://github.com/libretro/snes9x |

### Also evaluated for PlayStation

| Component | License | Source |
| --- | --- | --- |
| SwanStation | GPL-3.0 | https://github.com/libretro/swanstation |

## Frame generation

Frame generation is a port of the Lossless Scaling compute chain to Vulkan. WinNative did not
port it from scratch: it derives from **Camille LaVey**'s port in the **Eden Emulator Project**,
which in turn derives from **lsfg-vk**. Both are GPL-3.0-or-later, and both copyright notices are
preserved in the header of every file that carries their work.

| Component | Role | License | Source |
| --- | --- | --- | --- |
| Camille LaVey (Eden Emulator Project) | The Vulkan frame generation chain WinNative's port is derived from | GPL-3.0-or-later | https://git.eden-emu.dev/eden-emu/eden |
| lsfg-vk | The original Vulkan reimplementation, which the Eden port derives from | GPL-3.0-or-later | https://github.com/PancakeTAS/lsfg-vk |
| DXVK (`dxbc`) | Shader translator, used when only DXBC shaders are available | zlib/libpng | https://github.com/doitsujin/dxvk |

The chain layout, the pyramid stages (`lsfg_mipmaps`, `lsfg_alpha`, `lsfg_beta`, `lsfg_gamma`,
`lsfg_delta`, `lsfg_generate`), the Vulkan resource and barrier helpers (`lsfg_common`), the
generation pacer (`lsfg_pacer`) and shader module loading (`lsfg_shaders`) all come from that
lineage. WinNative's own additions are shader extraction from an installed copy of Lossless
Scaling (`lsfg_dll`), DXBC translation (`lsfg_dxbc`), the JNI surface (`lsfg_jni`), driver
probing (`lsfg_probe`) and compositor integration (`vkr_lsfg`).

The frame generation shaders themselves are **not** redistributed. They are read at runtime from
the user's own Lossless Scaling installation, which they must own separately on Steam.

## Frontend, achievements, and supporting libraries

| Component | Role | License | Source |
| --- | --- | --- | --- |
| LibretroDroid | libretro frontend the retro backend is built on | GPL-3.0 | https://github.com/Swordfish90/LibretroDroid |
| LibretroDroid (WinNative fork) | the build WinNative ships, carrying the RetroAchievements, netplay and SGSR changes | GPL-3.0 | https://github.com/WinNative-Emu/LibretroDroid/tree/winnative |
| Oboe | Audio output | Apache-2.0 | https://github.com/google/oboe |
| rcheevos | RetroAchievements client library | MIT | https://github.com/RetroAchievements/rcheevos |
| Snapdragon Game Super Resolution (SGSR) | Upscaling shader | BSD-3-Clause | https://github.com/quic/snapdragon-gsr |
| Winlator | Windows-on-Android base this project forks | GPL-3.0 | https://github.com/brunodev85/winlator |

## Source availability

WinNative is released under the GPL-3.0. As required by that license and by the GPL-2.0
cores above, the complete corresponding source for every copyleft component is obtainable
from the repositories linked in this document, and the bundled license texts are retained
in the source tree of the repository that builds each component.

The libretro frontend is no longer built from source in this repository. WinNative links
against `libretrodroid.aar`, published from the `winnative` branch of
https://github.com/WinNative-Emu/LibretroDroid, which is where its GPL-3.0 source, the
rcheevos `LICENSE` and `SGSR_LICENSE` texts now live. The exact build WinNative ships is
pinned by release tag and SHA-256 in `tools/libretrodroid.version`, so the corresponding
source for any given APK is the commit that tag was built from.
