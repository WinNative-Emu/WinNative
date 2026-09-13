<p align="center">
  <img src="logo.png" alt="WinNative" width="360">
</p>

# Credits & Acknowledgments

WinNative is a community project built on other people's work. This page is the full
attribution for everything WinNative is built from, including the parts we did not write.
Per-component license tables for the retro-console and emulator side live in
[EMULATOR_CREDITS.md](EMULATOR_CREDITS.md).

WinNative is distributed under the **GNU General Public License v3.0** ([LICENSE](LICENSE)).
Copyright and license notices are preserved in every file that carries upstream work, and the
corresponding source for every copyleft component is available from the projects linked here.

---

## Foundations

- **Original Winlator** by [brunodev85](https://github.com/brunodev85/winlator)
- **Winlator Bionic** by [Pipetto-crypto](https://github.com/Pipetto-crypto/winlator)
- **Pluvia** features by the [Pluvia](https://github.com/oxters168/Pluvia) /
  [GameNative](https://github.com/utkarshdalal/GameNative) community
- **Mesa/Turnip** contributions by the [Mesa3D](https://www.mesa3d.org/) team
- **Goldberg Steam Emulator** by [Mr. Goldberg](https://gitlab.com/Mr_Goldberg/goldberg_emulator),
  maintained by [Detanup01](https://github.com/Detanup01/gbe_fork)

## Retro console emulation

- **LibretroDroid** by [Filippo Scognamiglio](https://github.com/Swordfish90/LibretroDroid)
  (GPL-3.0) — the embedded libretro host for retro console support
- **libretro / RetroArch** and the individual core authors, built from source:
  [FCEUmm](https://github.com/libretro/libretro-fceumm),
  [Snes9x](https://github.com/libretro/snes9x),
  [Gambatte](https://github.com/libretro/gambatte-libretro),
  [mGBA](https://github.com/libretro/mgba),
  [Genesis Plus GX](https://github.com/libretro/Genesis-Plus-GX),
  [Mupen64Plus-Next](https://github.com/libretro/mupen64plus-libretro-nx),
  [Beetle PSX](https://github.com/libretro/beetle-psx-libretro)
- **ARMSX2** by the [ARMSX2](https://github.com/ARMSX2/ARMSX2) team (GPL-3.0) — the PlayStation 2
  core, a fork of **[PCSX2](https://github.com/pcsx2/pcsx2)** (GPL-3.0), built from source into
  `libemucore`. PS2 online play uses PCSX2's DEV9 network adapter
- **Dolphin** by the [Dolphin team](https://github.com/dolphin-emu/dolphin) (GPL-2.0-or-later) —
  the GameCube and Wii core, including its native NetPlay engine

## Frame generation

- **lsfg-vk** by [PancakeTAS](https://github.com/PancakeTAS/lsfg-vk) (GPL-3.0-or-later) — the
  original Vulkan reimplementation of the Lossless Scaling frame generation chain
- **LSFG frame generation** by **Camille LaVey** of the
  [Eden Emulator Project](https://git.eden-emu.dev/eden-emu/eden) (GPL-3.0-or-later) — the Vulkan
  port of that chain that WinNative's frame generation is derived from.
  See [what came from Camille LaVey's Eden port](#frame-generation--what-came-from-camille-laveys-eden-port)
- **DIS optical flow frame generation** by **qwertypower**
  ([DEVAR Entertainment LLC](https://devar.ai/)) (GPL-3.0) — a complete open-source implementation
  of a Dense Inverse Search frame generator, the second frame generation engine in WinNative and
  the one that needs no shaders from anywhere else.
  See [the fully open-source engine](#dis-frame-generation--the-fully-open-source-engine)
- **DIS optical flow** — the algorithm and its reference implementation come from
  [OpenCV](https://github.com/opencv/opencv) (`DISOpticalFlow`,
  [LICENSE](https://github.com/opencv/opencv/blob/5.x/LICENSE)), which adopted Till Kroeger's
  original [OF_DIS](https://github.com/tikroeger/OF_DIS)
- **DXVK** by [Philip Rebohle and contributors](https://github.com/doitsujin/dxvk) (zlib/libpng) —
  the `dxbc` shader translator, vendored at `app/src/main/cpp/thirdparty/dxbc` to convert the
  frame generation shaders to SPIR-V
- **Lossless Scaling** (Steam) — the source of the LSFG frame generation shaders. They are read
  from the user's own installed copy at runtime; none are redistributed with WinNative

## Audio

- **DirectAudio** by [The412Banner](https://github.com/The412Banner/directaudio)
  (LGPL-2.1-or-later) — the native Wine → Android AAudio audio driver, and the only audio path in
  WinNative that carries a working microphone.
  See [what came from The412Banner's driver](#directaudio--what-came-from-the412banners-driver)

---

## Frame generation — what came from Camille LaVey's Eden port

WinNative's frame generation exists because **Camille LaVey**, working in the
[Eden Emulator Project](https://git.eden-emu.dev/eden-emu/eden), had already solved the hard
part: getting the Lossless Scaling compute chain running correctly on Vulkan, on mobile GPUs.
The port here started from that work and still carries it. The Eden Emulator Project copyright
notices are preserved in every file that derives from it, under GPL-3.0-or-later.

Derived from Camille LaVey's Eden port (jointly with **[lsfg-vk](https://github.com/PancakeTAS/lsfg-vk)**,
which that port was in turn ported from):

| Source file | What it provides |
| --- | --- |
| `lsfg_chain.*` | The shape of the whole chain — which of the 25 shaders run, in what order, and what each stage feeds the next |
| `lsfg_mipmaps.*` | The flow pyramid the rest of the chain is built on |
| `lsfg_alpha.*` | Per-level feature extraction, including the batched-barrier dispatch pattern the rest of the chain follows |
| `lsfg_beta.*` | The coarse flow estimate the refinement stages start from |
| `lsfg_gamma.*` | Coarse-to-fine flow refinement, one instance per pyramid level |
| `lsfg_delta.*` | The extra refinement and detail passes on the finest levels |
| `lsfg_generate.*` | The final warp that produces the interpolated frame |
| `lsfg_common.*` | The Vulkan plumbing all of the above sit on — image, sampler and buffer wrappers, the barrier builder, the descriptor writer, and the pass/pipeline helper |

Derived from the Eden port specifically:

| Source file | What it provides |
| --- | --- |
| `lsfg_pacer.*` | Deciding how many frames to generate per real frame |
| `lsfg_shaders.*` | Turning the extracted shader blobs into Vulkan shader modules |

Getting the descriptor layouts, barrier placement and dispatch geometry of a 25-shader chain
right is not something you arrive at by reading the shaders; it is the part that takes the
debugging. Camille LaVey did that work, and this port would not have been possible without it.

What WinNative added on top is the Windows and Android side of it: reading the shader blobs out
of a user's own Lossless Scaling install (`lsfg_dll.*`), translating them when only DXBC is
available (`lsfg_dxbc.*`), the JNI surface (`lsfg_jni.*`), driver probing (`lsfg_probe.*`), and
wiring the chain into WinNative's compositor and swapchain (`vkr_lsfg.*`).

## DIS frame generation — the fully open-source engine

WinNative's second frame generator is a complete open-source implementation of **Dense Inverse
Search** optical flow, contributed by **qwertypower** (DEVAR Entertainment LLC) under GPL-3.0.

Unlike the Lossless Scaling path it depends on nothing the user has to own or install. The whole
chain ships with the APK as twelve compute shaders and runs in the same Vulkan compositor, so
frame generation is available on a fresh install with no Steam account and no `Lossless.dll`.

The algorithm is DIS, and its reference implementation is OpenCV's `DISOpticalFlow`
([OpenCV LICENSE](https://github.com/opencv/opencv/blob/5.x/LICENSE)). OpenCV in turn adopted
Till Kroeger's original [OF_DIS](https://github.com/tikroeger/OF_DIS). What is original here is
the Vulkan compute realisation of it — the pyramid, the descriptor and barrier layout, the
sparse-to-dense step and the pacing — built to run inside a mobile compositor at frame rate.

| Stage | Shaders |
| --- | --- |
| Pyramid and gradients | `dis_gradient` |
| Patch inverse search, coarse to fine | `dis_inverse_search`, `dis_propagate` |
| Sparse grid to dense flow | `dis_densify` |
| Variational refinement | `dis_vr_prep`, `dis_vr_d1`, `dis_vr_d2`, `dis_vr_w`, `dis_vr_coef`, `dis_vr_sor`, `dis_vr_add` |
| Warp to the in-between frame | `dis_interpolate` |

The engine sits behind its own settings — a Fast / Balance / Quality flow resolution and an
optional target frame rate — kept separate from the Lossless Scaling ones, and the two engines
are mutually exclusive because the compositor drives one interpolator per frame.

## DirectAudio — what came from The412Banner's driver

WinNative's microphone support exists because **The412Banner** wrote
**[DirectAudio](https://github.com/The412Banner/directaudio)**, a native Wine → Android AAudio
mmdevapi driver, and then completed its capture half. Nothing in that driver is WinNative's
work. We ship his release binaries unmodified and add only the host-side plumbing that selects
and configures them.

Wine has no AAudio backend upstream. DirectAudio is an original driver whose structure is
modelled on Wine's `winecoreaudio.drv`, with the CoreAudio device layer replaced by Android
AAudio — no PulseAudio daemon and no ALSA server anywhere in the path. That is also the reason
it is the only one of WinNative's three audio stacks that can carry a microphone at all: the
ALSA aserver protocol has no capture verb (its guest plugin refuses a non-playback PCM
outright), and the bundled PulseAudio ships no source module, so any capture endpoint winepulse
advertises would record silence.

| Component | What it provides |
| --- | --- |
| `winedirectaudio.drv` (arm64ec + i386 PE) | The mmdevapi driver the guest game loads |
| `winedirectaudio.so` (bionic unixlib) | The AAudio backend both PE halves share |
| Render mixer | One shared AAudio output stream, per-voice mixing, adaptive buffering with decay, and a dead-callback watchdog |
| Capture path *(v1.3.2)* | One shared AAudio `INPUT` stream (48 kHz / float / stereo, `VOICE_COMMUNICATION` preset for platform echo-cancel, noise-suppress and auto-gain), opened lazily on the first capture stream and started only on the first `Start` |

What WinNative added is only the host half: picking the driver build that matches the
container's Wine ABI and the device's kernel page size, overlaying the three files onto the
Proton layer, writing the Wine registry key that selects the driver, requesting `RECORD_AUDIO`,
and exposing the microphone opt-in per container and per shortcut.

The microphone is **off by default and per-container**, which is the driver's own design rather
than caution on our part: a capture endpoint a game can enumerate but not open makes titles that
probe the microphone while loading abandon audio init and boot to a black screen (God of War and
DiRT 3, device-proven upstream). With the gate off the driver is byte-identical to its
render-only build. WinNative additionally withholds the gate when `RECORD_AUDIO` has not been
granted, so a denied permission degrades to render-only rather than to an unopenable endpoint.

DirectAudio is licensed **LGPL-2.1-or-later** and remains so. The bundled binaries, their
checksums, the upstream source offer and the verbatim `COPYING`, `NOTICE` and `AUTHORS` files
live in [`app/src/main/assets/directaudio/`](app/src/main/assets/directaudio/). Integration
notes are in [`docs/direct-audio-integration.md`](docs/direct-audio-integration.md).

> DirectAudio by The412Banner (https://github.com/The412Banner/directaudio)
