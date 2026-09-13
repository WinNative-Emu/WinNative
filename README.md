<p align="center">
  <img src="logo.png" alt="WinNative" width="500">
</p>
<p align="center">
    <a href="https://discord.gg/uhTkvGfakU">
        <img src="https://img.shields.io/discord/1358831699814912141?color=5865F2&label=WinNative&logo=discord&logoColor=white"
            alt="Discord">
    </a>
</p>

## WinNative: A Community Built Windows Emulation App for Android

**WinNative** is an advanced, high-performance Windows (x86_64) emulation environment for Android.
It bridges the gap between desktop gaming and mobile by unifying the best technologies from
**Winlator Bionic** and **Pluvia**.

Designed for enthusiasts and power users, WinNative delivers the full Winlator experience while
making it easy to connect your Steam, Epic, and GOG game libraries — and it runs classic console
games alongside them.

| | |
| --- | --- |
| **Install** | [Releases](https://github.com/WinNative-Emu/WinNative/releases) |
| **Build from source** | [docs/BUILDING.md](docs/BUILDING.md) |
| **Credits & licenses** | [CREDITS.md](CREDITS.md) · [EMULATOR_CREDITS.md](EMULATOR_CREDITS.md) |
| **Chat** | [Discord](https://discord.gg/uhTkvGfakU) |

---

### Installation

1. **Download** the latest APK from [Releases](https://github.com/WinNative-Emu/WinNative/releases).
2. **Pick a variant.** All four are the same app with a different package name:

   | Variant | What it's for |
   | --- | --- |
   | `Vanilla` | Standard package name, for side-loading with other forks |
   | `Ludashi` | Forces max GPU **and** CPU clocks on some devices (performance-mode trigger) |
   | `Antutu` | Forces max GPU clocks on most devices (benchmark spoof) |
   | `Pubg` | PUBG package name, which unlocks some Game Booster advanced features |

3. **Set up.** Launch the app, allow the ImageFS to install, then add games manually or sync your
   library.

---

### Retro Console Support

Retro games live in the same Library and launch just like PC games, but run on an embedded
libretro backend instead of Wine.

| System | Core | ROM extensions |
| --- | --- | --- |
| NES | FCEUmm | `.nes` `.unf` `.unif` |
| SNES | Snes9x | `.smc` `.sfc` `.swc` `.fig` |
| Game Boy / Color | Gambatte | `.gb` `.gbc` |
| Game Boy Advance | mGBA | `.gba` |
| Genesis / Mega Drive, Master System, Game Gear | Genesis Plus GX | `.gen` `.md` `.smd` `.sms` `.gg` |
| Nintendo 64 | Mupen64Plus-Next | `.n64` `.z64` `.v64` |
| GameCube / Wii | Dolphin | `.gcm` `.rvz` `.gcz` `.iso` `.wbfs` `.wad` |
| PlayStation | Beetle PSX | `.cue` `.chd` `.pbp` `.m3u` `.iso` |
| PlayStation 2 | ARMSX2 (PCSX2 fork) | `.iso` `.chd` `.cso` `.bin` |

**How to use:** in the Library, tap **Add Custom Game** and pick a ROM instead of an `.exe`.
WinNative detects the console and adds it to your Library. **Play** launches it with on-screen
touch controls and gamepad support; the in-game menu (Back button or on-screen **MENU**) has
save/load state, reset and fast-forward.

Cores are **not** bundled in the APK — download them once from **Settings → Retro → Download
console cores**. PlayStation and PlayStation 2 BIOS files are imported from the same screen.
PS2 online play works through the emulated DEV9 adapter (the in-game **Online** tab), and
GameCube/Wii multiplayer uses Dolphin's own NetPlay.

---

### Frame Generation

WinNative can interpolate extra frames between the ones your game actually renders. Interpolation
runs **on the Android side**, inside WinNative's own Vulkan compositor rather than inside the Wine
container, so it works with any graphics API Wine can drive — DXVK, WineD3D or native Vulkan.

There are **two engines**, both in the **FG** tab of the session drawer. Pick one — they are
mutually exclusive, because the compositor drives a single interpolator per frame, and each keeps
its own settings so switching between them does not disturb the other.

| Engine | Needs | Character |
| --- | --- | --- |
| **Lossless Scaling (LSFG)** | Your own copy of Lossless Scaling on Steam | The 25-shader chain from Lossless Scaling, ported to Vulkan |
| **DIS** | Nothing — ships with the APK | Dense Inverse Search optical flow, fully open source |

**LSFG setup.** You must own [Lossless Scaling](https://store.steampowered.com/) on Steam. Its
shaders are not redistributable, so nothing ships with the APK: WinNative reads them out of your
own `Lossless.dll`, translates them from DXBC to SPIR-V once, and caches the result. The DLL is
parsed as data and never executed. Sign in to Steam, install Lossless Scaling, then open
**Container Settings → Frame Generation** — the DLL is found automatically, or use **Select
Lossless.dll…**. The LSFG half of the **FG** tab stays disabled until the shaders import.

**DIS setup.** None. Turn it on in the **FG** tab and it runs.

| Control | Engine | What it does |
| --- | --- | --- |
| Generate Frames | Both | Master toggle |
| Multiplier | LSFG | 2× / 3× / 4× — generated frames per rendered frame |
| Adaptive Target | LSFG | Aim for an output rate (60/90/120/144/165) instead of a fixed multiplier |
| Flow Scale | LSFG | 25–100%, resolution of the optical-flow pyramid; lower is cheaper and softer |
| Resolution Scale | DIS | Fast / Balance / Quality — 180 / 252 / 360 pixels on the frame's **shorter** side |
| Target FPS | DIS | Max refresh rate, or a specific one (60/90/120/144/165) |
| Show flow | DIS | Debug view: renders the estimated motion field instead of the frame |
| FPS Limiter | Both | Caps the game's own frame rate from 15 fps upward |

DIS states its Resolution Scale in pixels rather than as a percentage on purpose: a fixed pixel
budget costs the same on a 720p container and a 1440p one, whereas the same percentage would cost
four times as much on the larger container without telling the search anything more about the
motion.

**What to expect.** Frame generation costs **one extra frame of input latency** — interpolating
between two frames means holding the newer one back. It also needs spare display refresh:
generated frames occupy vblanks, so WinNative sizes the multiplier against your panel's refresh
rate and the game's actual frame rate, and will hand back generated frames rather than take real
ones from the game. A game already running near your panel's refresh rate has nothing to gain.
Pairing a multiplier with an FPS limiter that divides the refresh rate evenly (120 Hz with a
60 fps cap at 2×, or 40 at 3×) gives the most even pacing.

---

### Building

See **[docs/BUILDING.md](docs/BUILDING.md)** for requirements, the clone command (submodules and
Git LFS are both required), Gradle tasks, the four flavors, and how the retro console cores are
built and bundled outside this repository.

---

### Contributing

We welcome community contributions! Feel free to open a pull request for bug fixes, driver
updates, UI improvements, or anything else you'd like to add.

Please match the existing code style and ensure any AI-assisted code is thoroughly reviewed and
tested before submission.

---

### Credits

WinNative stands on work by **brunodev85** (Winlator), **Pipetto-crypto** (Winlator Bionic), the
**Pluvia**/**GameNative** community, the **Mesa3D** team, **Mr. Goldberg** and **Detanup01**
(Goldberg Steam Emulator), **Filippo Scognamiglio** (LibretroDroid) and the **libretro** core
authors, the **ARMSX2**, **PCSX2** and **Dolphin** teams, **PancakeTAS** (lsfg-vk),
**Camille LaVey** of the **Eden Emulator Project** (the Vulkan LSFG port this one derives from),
**qwertypower** of **DEVAR Entertainment LLC** (the open-source DIS engine), **OpenCV** and
Till Kroeger (the DIS algorithm), **DXVK** (the `dxbc` translator), and **The412Banner**
(DirectAudio, and with it microphone support).

That list is a summary, not the attribution itself. **[CREDITS.md](CREDITS.md)** carries the full
acknowledgments, including exactly which files came from which upstream project, and
**[EMULATOR_CREDITS.md](EMULATOR_CREDITS.md)** carries the per-component license table and the
GPL corresponding-source statement.

WinNative is released under the **GNU General Public License v3.0** — see [LICENSE](LICENSE).
