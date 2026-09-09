# SteamLite agent — source

Our own clean-room headless Steam agent for Bannerlator's Real-Steam (VAC) launch mode.

**Derivation / license:** `main.cpp` + `clean_shutdown.cpp`/`.h` are derived from
**WinNative** (`app/src/main/cpp/wn-steam-launcher/`), which is licensed **GPL-3.0**.
This code therefore inherits GPL-3.0. Keep attribution to the WinNative project.

**Our changes (vs the WinNative base):**
- Login-only ("M0") slice for on-device proof: bypass the `argv[1]` game-exe gate
  (was `return 1`) so it proceeds to log in without a game to launch, and return
  right after the login poll (skipping WinNative's `LaunchApp`/game-launch tail),
  matching our **decoupled** design (agent parks as the resident client; Bannerlator
  launches the game separately).

**Build:** MinGW-w64 x86_64 PE (runs in-Wine under Proton arm64ec + FEX):
```
x86_64-w64-mingw32-g++-posix -std=c++17 -O2 -static -static-libgcc -static-libstdc++ \
  -Wl,--subsystem,windows -I. -o steam.exe main.cpp clean_shutdown.cpp \
  -ladvapi32 -lkernel32 -luser32
```

**Runtime env:** `WN_STEAM_TOKEN` / `WN_STEAM_USERNAME` / `WN_STEAM_STEAMID` (login),
`WN_STEAM_APPID` (optional; when 0 → skip Steam LaunchApp), plus
`PROTON_DISABLE_LSTEAMCLIENT=1` (so the genuine PE `steamclient64.dll` loads instead
of Proton's lsteamclient shim). Reads the genuine Valve DLLs from
`C:\Program Files (x86)\Steam\`; sources them at runtime — never bundle.

**M0 proven 2026-08-27:** logs the genuine client to `SteamServersConnected` +
`Steam_BLoggedOn=true` on the Bannerlator stack.
