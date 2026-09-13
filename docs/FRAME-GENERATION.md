# Frame generation

WinNative can interpolate extra frames between the ones your game actually renders.
Interpolation runs **on the Android side**, inside WinNative's own Vulkan compositor rather than
inside the Wine container, so it works with any graphics API Wine can drive — DXVK, WineD3D or
native Vulkan alike.

There are **two engines**, both in the **FG** tab of the session drawer. Pick one — they are
mutually exclusive, because the compositor drives a single interpolator per frame, and each keeps
its own settings so switching between them does not disturb the other.

| Engine | Needs | Character |
| --- | --- | --- |
| **Lossless Scaling (LSFG)** | Your own copy of Lossless Scaling on Steam | The 25-shader chain from Lossless Scaling, ported to Vulkan |
| **DIS** | Nothing — ships with the APK | Dense Inverse Search optical flow, fully open source |

## Lossless Scaling (LSFG)

**You must own [Lossless Scaling](https://store.steampowered.com/) on Steam.** Its shaders are
not redistributable, so nothing ships with the APK. WinNative reads them out of your own copy of
`Lossless.dll`, translates them from DXBC to SPIR-V once, and caches the result in app storage.
The DLL is parsed as data and never executed.

**Setup:** sign in to Steam, install Lossless Scaling, then open **Container Settings → Frame
Generation**. WinNative finds the DLL automatically from your Steam library; if it can't, use
**Select Lossless.dll…** to point at it. The LSFG half of the **FG** tab stays disabled until
the shaders import successfully.

## DIS

**Nothing to buy, nothing to import.** DIS is a complete open-source Dense Inverse Search frame
generator built into WinNative — twelve compute shaders that ship with the APK — so it works on
a fresh install with no Steam account and no `Lossless.dll`. Turn it on in the **FG** tab and it
runs.

## Controls

| Control | Engine | What it does |
| --- | --- | --- |
| Generate Frames | Both | Master toggle |
| Multiplier | LSFG | 2× / 3× / 4× — generated frames per rendered frame |
| Adaptive Target | LSFG | Aim for a specific output rate (60/90/120/144/165) instead of a fixed multiplier |
| Flow Scale | LSFG | 25–100%, resolution of the optical-flow pyramid; lower is cheaper and softer |
| Resolution Scale | DIS | Fast / Balance / Quality — 180 / 252 / 360 pixels on the frame's **shorter** side |
| Target FPS | DIS | Max refresh rate, or a specific one (60/90/120/144/165) |
| Show flow | DIS | Debug view: renders the estimated motion field instead of the frame |
| FPS Limiter | Both | Caps the game's own frame rate, from 15 fps upward |

DIS states its Resolution Scale in pixels rather than as a percentage on purpose: a fixed pixel
budget costs the same on a 720p container and a 1440p one, whereas the same percentage would cost
four times as much on the larger container without telling the search anything more about the
motion. Lower is cheaper; higher tracks small or fast-moving detail better.

## What to expect

Frame generation costs **one extra frame of input latency** — interpolating between two frames
means holding the newer one back. It also needs spare display refresh: generated frames occupy
vblanks, so WinNative sizes the multiplier against your panel's refresh rate and the game's actual
frame rate, and will hand back generated frames rather than take real ones from the game. A game
already running near your panel's refresh rate has nothing to gain. Pairing a multiplier with an
FPS limiter that divides the refresh rate evenly (120 Hz with a 60 fps cap at 2×, or 40 at 3×)
gives the most even pacing.

## Credits

Both engines are other people's work. The LSFG chain derives from **Camille LaVey**'s Vulkan port
in the Eden Emulator Project, which derives from **PancakeTAS**'s lsfg-vk; the DIS engine was
contributed by **qwertypower** of DEVAR Entertainment LLC. See [CREDITS.md](../CREDITS.md) for
the full attribution, including which source files came from where.
