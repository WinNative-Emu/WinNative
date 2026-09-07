# SteamLite VAC handoff — what WinNative adopted

The material in this folder is the **SteamLite VAC handoff**, contributed by
**The412Banner** (Bannerlator) and published at
<https://github.com/The412Banner/winlator-contents> (release `steamlite-v1`).

Their agent is itself a derivative of WinNative's `wn-steam-launcher` (GPL-3.0), and
they handed the work back so WinNative could offer the same VAC-secured online play.
Everything here is GPL-3.0 and the attribution in `AGENT_NOTICE.md` is theirs — keep it.

Device-proven by them on Adreno 750 / Proton 11.0-2-arm64ec against Left 4 Dead 2 (550),
Team Fortress 2 (440) and Counter-Strike: Source (240).

## Adopted into WinNative

**1. Canonical `installdir` for the secure launch** — `VAC_RECIPE.md` §3 and the
"Canonical install-dir name" gotcha in `APP_INTEGRATION.md`.

`IClientAppManager::LaunchApp` only returns `NoError` when the app is registered under
`steamapps\common\<InstallDir>` with a matching `appmanifest_<appid>.acf`, where
`<InstallDir>` is Steam's canonical install-folder name. WinNative was using the
on-disk folder name for both the symlink and the manifest's `installdir` key, which
diverges for custom install locations and for apps that fall back to the store name.
When it diverged, `LaunchApp` failed with `EAppUpdateError=18` and the launcher fell
back to `CreateProcess`, which starts the game `-insecure` — VAC-secured servers then
refuse the connection.

Changed:
- `SteamUtils.createAppManifest` resolves the link name and the `installdir` key from
  `SteamService.getAppDirName(appInfo)`.
- `WineUtils.ensureSteamappsCommonSymlink` takes an explicit canonical link name.
- `XServerDisplayActivity` passes it at every Steam call site.

**2. Per-game spec file** — `VAC_RECIPE.md` §4 and the "Command-line escaping" gotcha.

The game exe was passed to `steam.exe` as `argv[1]`, so paths containing spaces or
backslashes had to survive Wine command-line quoting. The agent now accepts a spec file
(line 1 = exe path, line 2 = optional appId override) via `argv[1]` or
`WN_STEAM_GAMEEXE_FILE`, and falls back to treating `argv[1]` as the exe path directly.
WinNative writes `C:\wn-steam-game.spec` per launch and passes that instead.

## Fixed on top of the handoff

**Spec-file detection.** The handoff's `argv[1]` handling calls `fopen` on the argument
and treats any file that opens as a spec. A bare game-exe path — the documented
back-compat form, and the fallback WinNative takes when the spec cannot be written —
opens successfully, so the first line of the PE was parsed as the game path (`exe=MZ...`).
Verified under Wine 9.0 against the built agent. The first line must now look like an
absolute Windows path (`X:\`, `X:/` or a UNC prefix) before the argument is accepted as a
spec; anything else falls through to `argv[1]` as the exe path, which is what their
comment describes.

**Redist staging.** `WineUtils.ensureSteamappsCommonSymlink` re-copies the game's
`_CommonRedist` into `Steamworks Shared` on every call, so creating the canonical link as
a second call doubled that copy on each launch. The method now creates both link names in
one pass and stages the redists once.

## Already present in WinNative, confirmed against the recipe

- `PROTON_DISABLE_LSTEAMCLIENT=1` plus `WINEDLLOVERRIDES=lsteamclient=`, and physical
  removal of `lsteamclient.dll` from `system32`/`syswow64` (`VAC_RECIPE.md` §1).
- The `IClientEngine v005` login flow: `SetLoginToken` + `LogOn` on the private vtable,
  callback 101 / `BLoggedOn` polling (§2) — this is the code they derived from.
- The genuine Valve client set staged into `C:\Program Files (x86)\Steam\`, 32-bit
  `steamclient.dll` included (§5), and `steamservice` under `Common Files\Steam\`.
- Leaving the game's own genuine `steam_api(64).dll` in place in Plan W (§5).

## Not adopted

Their agent's login-only "M1" resident mode. It exists for their decoupled design, where
the app launches the game separately from the parked client. WinNative uses the resident
shape their `APP_INTEGRATION.md` recommends — one process logs in, calls `LaunchApp` and
watches the child — so that branch would never be entered here.
