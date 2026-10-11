# Retro console support

WinNative runs classic console games alongside your PC library. Retro games live in the same
Library and launch just like PC games, but run on an embedded libretro backend instead of Wine.

## Supported systems

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

## Getting started

1. **Download the cores.** They are not bundled in the APK. Fetch them once from
   **Settings → Retro → Download console cores**; the app verifies the bundle before installing
   it. Because the cores live outside the APK, a core update does not need an app update.
2. **Import BIOS files** if you want PlayStation or PlayStation 2 games — same screen.
3. **Add a game.** In the Library, tap **Add Custom Game** and pick a ROM instead of an `.exe`.
   WinNative detects the console from the file and adds the game to your Library.
4. **Play.** Launches with on-screen touch controls and physical gamepad support. The in-game
   menu (Back button, or the on-screen **MENU**) offers save/load state, reset and fast-forward.

## Online play

- **PlayStation 2** — through the emulated DEV9 network adapter, configured in the in-game
  **Online** tab.
- **GameCube / Wii** — through Dolphin's own native NetPlay engine.

## Credits

The cores and the libretro host are other people's work: **LibretroDroid** by Filippo
Scognamiglio, the individual **libretro** core authors, **ARMSX2** (a fork of **PCSX2**) and
**Dolphin**. See [CREDITS.md](../CREDITS.md) for attribution and
[EMULATOR_CREDITS.md](../EMULATOR_CREDITS.md) for the per-component license table.
