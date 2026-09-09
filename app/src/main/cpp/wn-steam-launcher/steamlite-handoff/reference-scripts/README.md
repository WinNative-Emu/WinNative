# Reference setup scripts

These are the concrete on-device scripts we used to perform the recipe by hand. They are
**test-environment specific** (paths point at our test container/prefix), included to
show the exact sequence — read them as a spec, not as drop-in code.

- `m0_setup.py` — stage the client + agent, set the login env, point the shortcut at the
  agent (login-only proof).
- `m2_setup.py` — add `WN_STEAM_APPID` + the game-exe spec (game launch).
- `m2b_setup.py` — symlink the game into `steamapps\common` under its canonical name,
  which is what makes `LaunchApp` return NoError → a **secure** (`-steam`) launch.

The sequence that matters (framework-agnostic):

1. Client + agent → prefix `C:\Program Files (x86)\Steam\`.
2. Game → `steamapps\common\<InstallDir>` (symlink ok) + `appmanifest_<appid>.acf`.
3. Per-game spec file (exe path + appId).
4. Env: `PROTON_DISABLE_LSTEAMCLIENT=1` + `WN_STEAM_*`.
5. Repoint the launch to run the agent as `steam.exe` with the spec.

No credentials are embedded: the scripts read the user's own refresh token from the
app's own storage at runtime.
