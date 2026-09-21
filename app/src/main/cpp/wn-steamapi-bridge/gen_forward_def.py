#!/usr/bin/env python3
"""
gen_forward_def.py — emit a MinGW .def file that PE-export-forwards
the entire gbe_fork (Goldberg Emulator Fork) SteamAPI surface to a
sibling `original_steam_api64.dll`, EXCEPT for the matchmaking calls
that we want to handle ourselves.

How PE forwards work:
  An export entry in .edata can be a "forwarder" — a string of the
  form `ModuleName.ExportName` instead of an RVA. When LoadLibrary +
  GetProcAddress hits a forwarder, the Windows loader auto-loads the
  named module + resolves the named export, returning that pointer
  transparently. The caller doesn't see the indirection.

  MinGW .def syntax: `NewName = TargetModule.TargetExport`. The
  resulting PE has its export-table entries pointing at the
  forwarder string, not at any code in our bridge.

The hybrid plan:
  - We are `steam_api64.dll` in the game's install dir.
  - We forward ~99% of exports to `original_steam_api64.dll` (gbe_fork
    renamed at install time).
  - We provide our OWN implementations for the OVERRIDE_NAMES set
    below — matchmaking-family entry points that route through our
    libsteamclient.so (via lsteamclient.dll → real CMClient state).
  - Result: gbe_fork handles SteamAPI_Init, every non-matchmaking
    flat-C call (which is most of them); we handle lobby list /
    create / join → real Steam lobbies.

Inputs:
  /tmp/gbe_real.txt — line-per-export list extracted earlier from
    the gbe_fork DLL via objdump (see header in build.sh).

Output:
  steam_api_bridge.def — MinGW .def file consumed by build.sh.
"""

from __future__ import annotations
import sys
from pathlib import Path

EXPORTS_LIST = Path("/tmp/gbe_real.txt")
OUT = Path(__file__).resolve().parent / "steam_api_bridge.def"

# Exports our bridge implements itself (NOT forwarded to gbe_fork).
# Start narrow: only matchmaking + the lifecycle hooks that need to
# discover our overrides. Expand carefully — every name here needs a
# corresponding C implementation in steam_api_bridge.c or a code-gen
# pass. Anything in this set but missing a C impl produces a link
# error.
#
# Callback lifecycle hooks (task #163) — we own these so we can
# dual-dispatch: gbe_fork's CCallback queue + our libsteamclient.so's
# pending-callback queue (where matchmaking responses land via
# push_call_result). Initial impl just passes through to gbe_fork;
# the libsteamclient.so drain comes incrementally.
OVERRIDE_NAMES = {
    "SteamAPI_RegisterCallback",
    "SteamAPI_UnregisterCallback",
    "SteamAPI_RegisterCallResult",
    "SteamAPI_UnregisterCallResult",
    "SteamAPI_RunCallbacks",
    # Flat-C ISteamClient accessors for matchmaking. Steamworks.NET
    # (Unity P/Invoke) calls these directly. Owning these two exports
    # lets P/Invoke callers reach our libsteamclient.so matchmaking
    # pointer without touching any vtable. Forest's C++ inline path
    # (SteamClient()->GetISteamMatchmaking) does NOT come through
    # here, so these are dead code on Forest's path — see note below.
    "SteamAPI_ISteamClient_GetISteamMatchmaking",
    "SteamAPI_ISteamClient_GetISteamMatchmakingServers",
    # Lifecycle + SteamClient overrides — route the C++ inline path
    # SteamClient()->GetISteamMatchmaking() through the wine PE bridge
    # (steamclient64.dll) instead of gbe_fork. Our impl dual-inits gbe
    # (for non-matchmaking forwarded exports) AND the wine bridge (for
    # matchmaking / P2P). See steam_api_bridge_lifecycle.c.
    "SteamClient",
    "SteamAPI_Init",
    "SteamAPI_InitSafe",
    "SteamAPI_InitFlat",
    "SteamAPI_Shutdown",
    "SteamAPI_IsSteamRunning",
    "SteamAPI_GetHSteamPipe",
    "SteamAPI_GetHSteamUser",
    "SteamAPI_RestartAppIfNecessary",
    # Steam Launcher: bare global matchmaking accessors. The Steamworks SDK
    # C++ header `isteammatchmaking.h` defines `inline SteamMatchmaking()`
    # as a `STEAM_DEFINE_USER_INTERFACE_ACCESSOR` macro that compiles
    # to an extern "C" call to the steam_api64.dll!SteamMatchmaking
    # export. Forest's C++ inline path (and most Steamworks games')
    # `SteamMatchmaking()->CreateLobby(...)` lands here. Forwarding
    # this export to gbe_fork sent every Forest matchmaking call into
    # gbe's LAN-broadcast emulator — explaining the "lobby visible on
    # LAN, invisible on Internet" diagnostic. Owning the bare globals
    # so they route to Valve's real in-process steamclient64.dll (see
    # steam_api_bridge_steamclient.c).
    "SteamMatchmaking",
    "SteamMatchmakingServers",
    "SteamAPI_SteamMatchmaking_v009",
    "SteamAPI_SteamMatchmakingServers_v002",
    # NOTE: SteamClient + SteamAPI_Init wrapper attempt (commits
    # 7ce950c and following) crashed Forest. Forest takes the C++
    # inline SteamMatchmaking() path which compiles to
    # SteamClient()->vtable[10], so to redirect it we'd have to own
    # SteamClient and either patch gbe's vtable (corrupted gbe's
    # bootstrap) or return a wrapper (Forest's downstream consumer
    # hit kernelbase unwind even with an Init gate that deferred
    # wrapper activation past gbe's init). Both attempts crashed with
    # the same kernelbase epilogue AV. Likely root cause: our
    # ISteamMatchmakingStub v009 vtable doesn't exactly match what
    # Forest expects — needs verification against the real SDK header
    # before another attempt.
}

# Matchmaking surface — the whole ISteamMatchmaking flat-C family.
# These are the entry points Forest hits when the user clicks
# MULTIPLAYER → "loading lobbies".
MATCHMAKING_PREFIXES = (
    "SteamAPI_ISteamMatchmaking_",
    # MatchmakingServers (server browser) — same story.
    "SteamAPI_ISteamMatchmakingServers_",
)


def main() -> int:
    if not EXPORTS_LIST.exists():
        print(f"ERROR: {EXPORTS_LIST} missing — run: "
              f"x86_64-w64-mingw32-objdump -p $GBE_FORK | "
              f"awk '/Name Pointer.*Table/,/^$/' | awk '/\\[/{{print $NF}}' "
              f"> /tmp/gbe_real.txt", file=sys.stderr)
        return 1

    exports = [
        ln.strip() for ln in EXPORTS_LIST.read_text().splitlines()
        if ln.strip()
    ]

    # Compute the override set: matchmaking-family exports.
    overrides = set(OVERRIDE_NAMES)
    for e in exports:
        for p in MATCHMAKING_PREFIXES:
            if e.startswith(p):
                overrides.add(e)
                break

    # Sanity-check: at least the lobby-list call should be in there.
    must_override = {
        "SteamAPI_ISteamMatchmaking_RequestLobbyList",
        "SteamAPI_ISteamMatchmaking_CreateLobby",
        "SteamAPI_ISteamMatchmaking_JoinLobby",
        "SteamAPI_ISteamMatchmaking_LeaveLobby",
        "SteamAPI_ISteamMatchmaking_GetLobbyByIndex",
    }
    missing = must_override - set(exports)
    if missing:
        print(f"WARNING: gbe_fork doesn't export {missing} — bridge "
              f"may need additional plumbing", file=sys.stderr)

    forwards = [e for e in exports if e not in overrides]

    lines = [
        "; AUTO-GENERATED by gen_forward_def.py — DO NOT EDIT.",
        ";",
        "; PE export forwarders → original_steam_api64.dll. The companion",
        "; gbe_fork (renamed at install time) provides every non-matchmaking",
        "; SteamAPI function. Our bridge's .text section only defines the",
        "; matchmaking overrides listed under EXPORTS without an `=`.",
        ";",
        "LIBRARY steam_api64",
        "EXPORTS",
    ]
    # Forwards first (alphabetical for readability)
    for e in sorted(forwards):
        lines.append(f"    {e} = original_steam_api64.{e}")
    # Then the overrides
    lines.append("    ; --- overrides (our own impls in steam_api_bridge_overrides.c) ---")
    for e in sorted(overrides):
        lines.append(f"    {e}")

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"[gen_forward_def] {len(forwards)} forwards + {len(overrides)} overrides → {OUT}",
          file=sys.stderr)
    print(f"[gen_forward_def] override names:", file=sys.stderr)
    for e in sorted(overrides):
        print(f"  {e}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
