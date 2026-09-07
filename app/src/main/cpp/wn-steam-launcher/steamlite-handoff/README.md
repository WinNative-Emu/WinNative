# SteamLite — VAC online-multiplayer recipe (handoff for WinNative)

A complete, working recipe + headless agent for **real Steam online multiplayer on
VAC-secured servers**, running inside Wine/Proton on Android (arm64ec + FEX, also
box64). No emulator, no DRM bypass — a genuine Steam login and a genuine, VAC-eligible
game launch.

**Device-proven:** Adreno-750 / Proton 11.0-2-arm64ec, on **Left 4 Dead 2 (550)**,
**Team Fortress 2 (440)**, and **Counter-Strike: Source (240)** — each reached real
VAC-secured servers via a genuine account login.

## Why this is for you

The headless agent here is a **clean-room derivative of WinNative's own GPL
`wn-steam-launcher`** (the `IClientEngine v005` driver: `SetLoginToken` + `LogOn` on
the private vtable). We extended it and worked out the last mile to VAC, and it
inherits **GPL-3.0** — so this is your code, improved, handed back so WinNative can
offer the same VAC online-MP capability.

## The one insight (the missing last mile)

WinNative already logs the genuine client in. The piece that unlocks **VAC** is the
**secure launch**:

- Launch the game through **`IClientAppManager::LaunchApp(appId)`** — it appends
  `-steam` and produces a VAC-eligible session.
- A direct `CreateProcess` of the game exe is **insecure**: the game comes up with
  `-insecure` and VAC-secured servers refuse the connection.
- `LaunchApp` only returns `NoError` when the game is registered under
  `steamapps\common\<installdir>` **with an `appmanifest_<appid>.acf`**. Register it,
  and the launch is secure.

Everything else (real refresh-token login → real session ticket) WinNative already
does; that ticket is what VAC accepts at the auth layer, provided the launch is secure
and the game keeps its **own genuine `steam_api(64).dll`** (no emulator swap).

## What's inside

| Path | What |
|---|---|
| `VAC_RECIPE.md` | The make-or-break technical recipe: env vars, secure `LaunchApp`, the per-game spec, the login flow. |
| `APP_INTEGRATION.md` | How to wire it into the app's game-launch path (staging, env, agent-as-steam.exe, teardown). |
| `agent/` | The headless agent source (`main.cpp` + `clean_shutdown.*`, GPL-3.0) + build notes. |
| `reference-scripts/` | Concrete on-device setup scripts that perform the exact steps (register in `steamapps\common`, write the manifest + spec, set env, repoint the launch). |

## License / attribution

- The **agent** (`agent/`) is **GPL-3.0**, derived from WinNative's `wn-steam-launcher`.
  Keep the attribution in `agent/NOTICE.md`.
- **No Valve binaries are included here.** The genuine Valve Steam client DLLs are
  Valve's property — source your matched set as you already do; the agent loads them
  from `C:\Program Files (x86)\Steam\` at runtime.

## Quick verification checklist (what "working" looks like)

1. Genuine `steamclient64.dll` loads (not the `lsteamclient` shim) — see
   `PROTON_DISABLE_LSTEAMCLIENT=1` below.
2. Agent log shows `CLIENTENGINE_INTERFACE_VERSION005 → non-null`, `LogOn → EResult 1`,
   callback `101 SteamServersConnected`, `Steam_BLoggedOn = true`.
3. `LaunchApp(appId)` returns `EAppUpdateError = 0 (NoError)` and the game process
   comes up with `-steam` (NOT `-insecure`).
4. In-game server browser → Internet → a **VAC-secured** server → connection holds.
