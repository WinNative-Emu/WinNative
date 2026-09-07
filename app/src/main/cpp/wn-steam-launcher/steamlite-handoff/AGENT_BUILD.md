# Building the agent

MinGW-w64, x86_64 PE (runs in-Wine under Proton arm64ec + FEX, or box64):

```
x86_64-w64-mingw32-g++-posix -std=c++17 -O2 -static -static-libgcc -static-libstdc++ \
  -Wl,--subsystem,windows -I. -o steam.exe main.cpp clean_shutdown.cpp \
  -ladvapi32 -lkernel32 -luser32
x86_64-w64-mingw32-strip steam.exe
```

The `-posix` thread model is required (clean_shutdown uses std::thread). Stage the
result as `C:\Program Files (x86)\Steam\steam.exe` in the prefix.

## Vtable offsets (client-build sensitive)

The `IClientEngine`/`IClientUser` vtable slots are hardcoded (see the top of
`main.cpp`): `GetIClientUser @0x40`, `LogOn @0x08`, `BLoggedOn @0x20`,
`GetSteamID @0x50`, `SetLoginToken @0x1C0`. These match a specific `steamclient64.dll`
build — use a client offset-matched to these, or re-derive the offsets from the
client you ship (OpenSteamworks headers cover the `IClientEngine v005` layout).

## Runtime env

`WN_STEAM_TOKEN`, `WN_STEAM_USERNAME`, `WN_STEAM_STEAMID`, `WN_STEAM_APPID`,
`WN_STEAM_GAMEEXE_FILE` (spec file), plus `PROTON_DISABLE_LSTEAMCLIENT=1`. See
`../VAC_RECIPE.md`.
