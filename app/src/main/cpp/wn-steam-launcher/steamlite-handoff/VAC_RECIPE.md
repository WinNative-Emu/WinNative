# VAC recipe — the make-or-break details

The whole path in one line: **genuine client + genuine login + game registered as
installed + secure `LaunchApp` (`-steam`) + the game's own genuine `steam_api` = a real
Valve-signed session ticket a VAC-secured server accepts.**

## 1. Load the GENUINE client, not the shim

Proton's `ntdll` intercepts `LoadLibrary("steamclient64.dll")` and redirects to its
`lsteamclient` shim. That shim can't drive a real login. Disable it:

```
PROTON_DISABLE_LSTEAMCLIENT=1
# belt-and-suspenders: WINEDLLOVERRIDES append ";lsteamclient="
```

With this set, the agent's `LoadLibrary` gets the real `steamclient64.dll`.

## 2. Log in (the WinNative v005 flow — unchanged)

```
LoadLibrary("steamclient64.dll")
CreateInterface("CLIENTENGINE_INTERFACE_VERSION005")   -> IClientEngine
Steam_CreateGlobalUser(&pipe, &user)
GetIClientUser(pipe, user, "CLIENTUSER_INTERFACE_VERSION001") -> IClientUser
SetLoginToken(refreshToken, accountName)               // vtable slot 56 @0x1C0
LogOn(steamID)                                         // vtable slot 1  @0x08
// poll callbacks: 101 SteamServersConnected / 102 ConnectFailure / 103 Disconnected
// + BLoggedOn() @0x20 until true
```

The refresh token is the user's own (mint it with your CM/JavaSteam login). Pass secrets
by **environment**, never on the command line (keeps them out of `/proc/<pid>/cmdline`):

| Env var | Meaning |
|---|---|
| `WN_STEAM_TOKEN` | refresh token (the login) |
| `WN_STEAM_USERNAME` | account name |
| `WN_STEAM_STEAMID` | steamID64 |
| `WN_STEAM_APPID` | app to launch (0 ⇒ login only, no launch) |
| `WN_STEAM_GAMEEXE_FILE` | path to the per-game spec file (§4) |

## 3. Register the game as INSTALLED (this is what makes the launch SECURE)

`LaunchApp` only works on an app Steam believes is installed under its library:

1. Ensure the game lives at (or is symlinked to)
   `C:\Program Files (x86)\Steam\steamapps\common\<InstallDir>\`, where `<InstallDir>`
   is Steam's canonical install-folder name for that appId. The real on-disk folder may
   have a different name — a **symlink** under the canonical name is enough.
2. Write `steamapps\appmanifest_<appid>.acf`:

```
"AppState"
{
    "appid"      "550"
    "Universe"   "1"
    "name"       "Left 4 Dead 2"
    "StateFlags" "4"                 // 4 = fully installed
    "installdir" "Left 4 Dead 2"
}
```

With this present, `IClientAppManager::LaunchApp(appId)` returns
`EAppUpdateError = 0 (NoError)` and launches the game with `-steam`. Without it, the
agent must fall back to `CreateProcess`, which is **insecure** (`-insecure`) — VAC
servers reject it. This registration step is the single most important delta for VAC.

## 4. Per-game spec (self-contained launch)

So one agent binary serves every game, pass a tiny spec file (path in
`WN_STEAM_GAMEEXE_FILE`, or `argv[1]`):

```
C:\Program Files (x86)\Steam\steamapps\common\Left 4 Dead 2\left4dead2.exe
550
```

Line 1 = the game exe under `steamapps\common`; line 2 = the appId (overrides
`WN_STEAM_APPID`). The agent reads this, `LaunchApp`s the appId, and watches the child.

## 5. The game keeps its OWN genuine steam_api

Do **not** swap in an emulator's `steam_api(64).dll`. The game's genuine
`steam_api` connects to the running genuine client over the Steam IPC pipe and calls
`GetAuthSessionTicket()` → a real Valve-signed ticket with a real gameconnect token.
That is what the VAC module validates. (Note: 32-bit games such as L4D2 need the 32-bit
`steamclient.dll` present alongside the 64-bit one so the 32-bit `steam_api` can
bootstrap the IPC connection.)

## 6. Environment/arch notes

- Proven on Proton **arm64ec + FEX** (`HODLL=libwow64fex.dll`) and box64. box64 is not
  required — arm64ec works.
- `steamservice.exe` is installed by the agent on first run; keep it under
  `C:\Program Files (x86)\Common Files\Steam\`.
- Ceiling: **VAC only.** Kernel-mode anti-cheat (EAC/BattlEye kernel) is out of scope.

## 7. Failure signatures (fast triage)

| Symptom | Cause |
|---|---|
| Agent shows a Button/#32769 dialog then exits, no login | `lsteamclient` shim still active → set `PROTON_DISABLE_LSTEAMCLIENT=1`. |
| Game runs but shows `-insecure` / can't join VAC servers | Launched via `CreateProcess`, not `LaunchApp` → register `steamapps\common` + manifest (§3). |
| `LaunchApp` returns `EAppUpdateError=18` (not installed) | Missing/incorrect `appmanifest` or `installdir` name mismatch. |
| Login `LogOn` fails / no `SteamServersConnected` | Bad/expired refresh token, or wrong client build vs the agent's hardcoded vtable offsets (use an offset-matched client). |
