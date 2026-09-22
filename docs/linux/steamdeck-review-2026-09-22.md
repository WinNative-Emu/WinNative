# SteamDeck comparison — 2026-09-22

Compared WinNative `980ae6a2` with The412Banner/SteamDeck default branch at `2cbd015d80ec3320eb34cfa1b83d5c63240ae192` (fetched from GitHub). Source: https://github.com/The412Banner/SteamDeck/tree/2cbd015d80ec3320eb34cfa1b83d5c63240ae192

## Applied

| Difference | WinNative correction |
| --- | --- |
| SteamDeck passes `-i <app uid>:<app uid>` to proot. WinNative did not emulate identity calls. | Enable the same identity emulation while retaining the app's numeric identity. This allows Xwayland's child setup to complete where Android refuses setuid/setgid. |
| SteamDeck puts the optional `PROOT_NO_SECCOMP=1` in proot's host environment. WinNative's environment editor only sent it into the guest. | Route this specific override to the host. Leave acceleration enabled by default; `0`, `false`, and `off` do not disable it. |
| SteamDeck checks an imported Vulkan manifest's referenced library and falls back when unreadable. | Validate imported ICD JSON and its library with the JSON parser, including escaped separators and relative paths. Fall back to bundled Mesa without modifying the saved choice. An unknown/removed selection also falls back to bundled Mesa instead of another global driver. |

The identity difference is relevant to the Odin report's failure before any guest window. It is a concrete missing startup behavior, but the supplied report lacks the Linux process log needed to attribute that device's failure to it.

## Already present or intentionally different

- The ten shared preload C files are functionally equivalent after normalizing project prefixes, comments, and local fork-handler names. No additional syscall fixes were found there.
- SteamDeck's GE/CachyOS wrappers remove pressure-vessel dependencies and restore input-preload ordering, as WinNative already does. They do not supply an additional GE/CachyOS startup shim missing from WinNative's shared launcher. WinNative retains its stricter DirectAudio compatibility preparation.
- `TU_DEBUG=sysmem` and `ZINK_DESCRIPTORS=lazy` are in the container default and reach the guest. `PROTON_USE_XALIA` was not offered anywhere; it is now one of the known variables, unset by default, so Proton's own gate is reachable without typing the name. No device-specific workaround is enabled globally by this change.
- SteamDeck optionally reapplies CPU affinity to the client and separately applies a mask to Proton games. This is performance tuning, not a demonstrated cause of the Odin early exit. It was not imposed on WinNative's sessions.
- SteamDeck's additional `space.c` redirects free-space queries for a whole SD library whose `steamapps/common` is bound to the card. WinNative binds individual game directories, potentially from different locations. Copying this shim would report the wrong filesystem for that layout.
- SteamDeck prefers a runtime-shipped proot/talloc pair. WinNative packages proot and its loader with the APK. No binary replacement was made without evidence that WinNative's packaged build is faulty.
- SteamDeck's orphan sweep kills every other process under its app UID. WinNative supports retained sessions and other app-owned services, so that sweep is not safe to copy indiscriminately.
- SteamDeck additionally creates crash-surviving session bundles with scrubbed Steam logs. WinNative now captures launcher stderr from process start and exposes Steam logs through its existing manager, but does not have the same bundle/redaction implementation. This remains a diagnostics difference, not a boot fix applied here.

## Verification

Identity emulation is not a command line option here. Upstream proot takes `-i uid:gid`; the bundled build has no such option and answers `setuid`, `setgid`, `setresuid`, `setresgid`, `setreuid` and `setregid` from its seccomp handler instead, granting an id the process already holds and refusing any other. A host proot cannot stand in for that, so the check belongs on device: `LinuxRuntimeTest.passesOnlyOptionsThisProotAccepts` runs the packaged binary with the real option list and fails if it reports an option it does not know.

The PUBG debug and instrumentation APKs build. AVD tests cover proot argument construction, opt-in host environment routing, missing/malformed imported drivers, launcher output and argument handling, compositor driver gating, and Components navigation in portrait/landscape. The existing Python Proton regression suite also passes.

On the Odin, install the new APK and start a fresh Linux session. If it still exits early, collect `linux-session-*.log`. Try `PROOT_NO_SECCOMP=1` only as a separate diagnostic run when that log suggests syscall failures. For a Proton game failure, include `steam-<appid>.log` and the exact game/build; compilation and AVD testing cannot establish ARM64/KGSL gameplay success.
