# App integration — wiring the agent into the launch path

This is how we hooked the recipe into an Android Wine/Proton launcher. Map the steps to
your own launch pipeline; nothing here is framework-specific.

## Design: decouple login from game launch

The agent runs as a headless `steam.exe` replacement. Two viable shapes:

- **Resident (what we ship):** the agent logs in, then **parks** (keeps the pipe/user
  alive, pumps callbacks) and does `LaunchApp` itself, watching the child; on the game's
  exit it logs off cleanly and reaps. One process owns the whole session.
- **Split:** the agent logs in + parks + signals "ready"; the app launches the game
  separately with `-steam`. Works too, but needs a ready-signal channel.

We recommend resident — it's simplest and avoids a second IPC channel.

## Ordered launch orchestration

When the user launches a Steam game in "real Steam" mode:

1. **Branch on mode.** Only do the below for genuine-Steam shortcuts the user opted into
   (a per-shortcut flag). Non-Steam / emulator launches stay exactly as they are.
2. **Un-emulate.** Ensure the game's OWN genuine `steam_api(64).dll` is in place (undo
   any prior emulator swap).
3. **Stage the client** into the prefix `C:\Program Files (x86)\Steam\`
   (`steamclient64.dll` + `steamclient.dll` + `tier0_s*/vstdlib_s*` + `steam.exe` = the
   agent) and `steamservice.*` into `Common Files\Steam\`.
4. **Register the game** under `steamapps\common\<InstallDir>` + write
   `appmanifest_<appid>.acf` (see VAC_RECIPE §3). Symlink the real install dir to the
   canonical name if they differ.
5. **Write the per-game spec** file (VAC_RECIPE §4).
6. **Set env:** `PROTON_DISABLE_LSTEAMCLIENT=1` + `WN_STEAM_TOKEN/USERNAME/STEAMID/APPID`
   + `WN_STEAM_GAMEEXE_FILE`. Token by env only; never log it (redact any env dump).
7. **Launch the agent as `steam.exe`** with the spec as its argument. It logs in, does
   the secure `LaunchApp`, and runs the game with `-steam`.
8. **Teardown** on game exit: the agent logs off + reaps (avoids `AlreadyRunning 0x10`
   on the next launch). Fall back to your offline path if login/launch fails.

## Where the token comes from

Mint the user's own refresh token with your existing CM/JavaSteam login and pass it via
`WN_STEAM_TOKEN`. It is a credential — keep it out of logs and out of any command line
(env only). If you dump the launch environment to a log for debugging, run it through a
redactor that strips long tokens / JWTs first.

## Per-game UX (optional, how we surface it)

We show a small "launch method" chooser the first time a Steam game is launched: the
real-Steam path (online / VAC) vs the offline emulator path (kept as a fallback), with a
"remember for this game" option. The chosen mode is stored per-shortcut and read at
launch. Not required — a global setting works too.

## Gotchas we hit

- **Command-line escaping:** passing the exe path as an argument tripped over
  backslash/space escaping. A spec **file** (read by the agent) sidesteps all of it.
- **32-bit games** (e.g. L4D2) need the 32-bit `steamclient.dll` present too.
- **Canonical install-dir name:** `LaunchApp` matches the app's real `installdir`. If
  the on-disk folder differs, the symlink + the `installdir` key in the manifest must
  both use the canonical name.
- **First-run services:** the agent installs `steamservice`; make sure the prefix user
  is consistent between the agent and the game (same session).
