#!/usr/bin/env python3
"""
gen_forwarders.py — emit SteamAPI_ISteam*_<Method> flat-C forwarders.

Inputs:
  app/src/main/cpp/wn-libsteamclient/src/isteam_stubs.cpp

Output:
  steam_api_bridge_flat.c — one C function per virtual method per
  ISteam*Stub class. Each forwarder takes `void* self` as its first
  arg, casts vtable slot N to the right signature, and tail-calls.

Approach:
  1. Locate each `class ISteam<Name>Stub {` block and its matching `};`.
  2. Walk the body line-by-line. Accumulate text into a buffer until
     we hit `(...)` matched parens — that's one virtual declaration.
     Reset buffer at every `;` or `}` at depth 0 to keep multi-line
     bodies from confusing the accumulator.
  3. Each `virtual ret name(args)` we see increments the slot counter
     (sequential, matches SDK ABI ordering).
"""

from __future__ import annotations
import os
import re
import sys
from pathlib import Path

LSC_SRC_DIR = Path(__file__).resolve().parent.parent / "wn-libsteamclient" / "src"
# Sources walked for `virtual` decls. Each file's classes get parsed
# in source order; class-name → forwarder-prefix mapping below.
SOURCES = [
    LSC_SRC_DIR / "isteam_stubs.cpp",
    LSC_SRC_DIR / "isteam_client.cpp",
]
# Map source class name (with or without Stub suffix) to the
# SteamAPI_<X>_<Method> prefix that the SDK exports the flat-C
# under. Classes not in the map use the class name as-is (stripping
# `Stub` and `Impl` suffixes).
CLASS_NAME_OVERRIDE = {
    "ISteamClientImpl": "ISteamClient",
}
OUT = Path(__file__).resolve().parent / "steam_api_bridge_flat.c"


def find_classes(text: str) -> list[tuple[str, int, int]]:
    """Return [(class_name, body_start, body_end), ...]. Bodies span
    the open `{` (exclusive) to the matching `}` (exclusive).

    Matches `class ISteam<X>Stub`, `class ISteam<X>Impl`, and bare
    `class ISteam<X>` — different files use different suffix
    conventions, but they all represent the same SDK interfaces."""
    result = []
    pattern = r"^class\s+(ISteam[A-Za-z0-9]*?)(?:Stub|Impl)?\s*\{"
    seen_classes = set()
    for m in re.finditer(pattern, text, re.MULTILINE):
        cls = m.group(1)
        cls = CLASS_NAME_OVERRIDE.get(cls + "Impl", CLASS_NAME_OVERRIDE.get(cls + "Stub", cls))
        if cls in seen_classes:
            continue
        seen_classes.add(cls)
        start = m.end()  # right after the opening {
        depth = 1
        i = start
        while i < len(text) and depth > 0:
            ch = text[i]
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        result.append((cls, start, i))
    return result


def parens_balanced(s: str) -> int:
    """Return depth at end. 0 = balanced, >0 = unclosed, <0 = extra )."""
    depth = 0
    for ch in s:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
    return depth


VIRTUAL_PROBE = re.compile(r"\bvirtual\s+")
# Capture: returnType (possibly multi-token), methodName, args (the
# substring between balanced parens). We require `virtual` to start
# the declaration; everything up to the first `(` is the return-type +
# method-name; everything between the first balanced `(...)` is args.
VIRTUAL_HEAD_RE = re.compile(
    r"""^virtual\s+
        (?P<head>[A-Za-z_][^()]*?[A-Za-z0-9_*&\s])\s*\(
    """,
    re.VERBOSE,
)


def parse_virtuals(body: str) -> list[dict]:
    """Walk class body, emit one entry per virtual declaration in
    source order. Bodies of inline-defined methods (`{ ... }`) are
    skipped — we only care about the declaration line(s) up to the
    closing `)` of the arg list, then jump past the body."""
    out = []
    i = 0
    n = len(body)
    slot = 0
    while i < n:
        # Find next `virtual\s+`
        m = VIRTUAL_PROBE.search(body, i)
        if m is None:
            break
        v_start = m.start()
        # Sometimes "virtual" appears in a comment — check by looking
        # backwards to the previous newline for `//`.
        line_start = body.rfind("\n", 0, v_start) + 1
        prefix = body[line_start:v_start]
        if "//" in prefix or "/*" in prefix:
            i = m.end()
            continue
        # Find the opening `(` of the arg list — must be at depth 0
        # (no preceding `<` < ... yet, declaration text only).
        j = v_start
        paren_open = body.find("(", j)
        if paren_open == -1:
            break
        # The head is body[v_start:paren_open]
        head = body[v_start:paren_open]
        # Now find the matching `)` for this `(`.
        depth = 0
        k = paren_open
        while k < n:
            ch = body[k]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        if k >= n:
            break
        args_str = body[paren_open + 1:k]
        # Parse head: split off the trailing identifier as method name
        head = head.strip()
        # Expect head to start with `virtual\s+`
        head = re.sub(r"^virtual\s+", "", head, count=1)
        head = head.strip()
        # Last identifier token is the method name; everything else is the return type
        name_match = re.search(r"([A-Za-z_][A-Za-z0-9_]*)$", head)
        if not name_match:
            i = k + 1
            continue
        method = name_match.group(1)
        ret = head[:name_match.start()].strip()
        # Filter out things that look like keywords / non-types
        if not ret or ret in {"static", "explicit"}:
            i = k + 1
            continue
        args = split_args(args_str)
        out.append({
            "slot": slot,
            "ret": ret,
            "name": method,
            "args": [normalize_arg(a, j) for j, a in enumerate(args)],
        })
        slot += 1
        # Skip past the closing `)` of the args.
        i = k + 1
        # If the next non-space char is `{`, skip the entire body
        # (matching braces). Otherwise (decl ends with `;`), no skip
        # needed.
        while i < n and body[i] in " \t\r\n":
            i += 1
        if i < n and body[i] == "{":
            depth = 1
            i += 1
            while i < n and depth > 0:
                ch = body[i]
                if ch == "{":
                    depth += 1
                elif ch == "}":
                    depth -= 1
                i += 1
    return out


def split_args(raw: str) -> list[str]:
    out, depth, buf = [], 0, []
    for ch in raw:
        if ch in "<(":
            depth += 1
        elif ch in ">)":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(buf).strip())
            buf = []
        else:
            buf.append(ch)
    if buf:
        out.append("".join(buf).strip())
    return [a for a in out if a]


def normalize_arg(arg: str, idx: int) -> tuple[str, str]:
    arg = arg.strip()
    eq = arg.find("=")
    if eq != -1:
        arg = arg[:eq].strip()
    if not arg:
        return ("void", f"_a{idx}")
    # If arg ends with `*` or `&`, no name. If arg contains only
    # type tokens (no trailing identifier after a space), no name.
    # Heuristic: split on whitespace; if last token is identifier and
    # ALSO appears in the SDK as a type, treat as type — else name.
    m = re.match(r"^(.*?)([A-Za-z_][A-Za-z0-9_]*)\s*$", arg)
    if not m:
        return (arg, f"_a{idx}")
    prefix, tail = m.group(1).rstrip(), m.group(2)
    primitives = {
        "void", "bool", "char", "short", "int", "long", "float",
        "double", "size_t", "uint", "ulong", "ushort",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t",
        "int8_t", "int16_t", "int32_t", "int64_t",
    }
    sdk_types = {
        "HSteamPipe", "HSteamUser", "EResult", "AppId_t",
        "CSteamID", "ISteamFriends", "HAuthTicket", "RTime32",
        "HServerListRequest", "HServerQuery",
        "AccountID_t", "CGameID", "DepotId_t", "SteamAPICall_t",
        "PublishedFileId_t", "UGCFileWriteStreamHandle_t",
        "UGCHandle_t", "UGCQueryHandle_t", "UGCUpdateHandle_t",
        "FriendsGroupID_t", "SteamLeaderboard_t",
        "SteamLeaderboardEntries_t", "ScreenshotHandle",
        "PingLocation_t", "SteamNetConnection_t",
        "SteamNetworkingMessage_t", "HSteamNetConnection",
        "HSteamListenSocket", "HSteamNetPollGroup",
        "SteamNetworkingPOPID", "SteamNetworkingMicroseconds",
        "InputHandle_t", "ControllerHandle_t",
        "ParticipantID_t", "PartyBeaconID_t",
        "SteamItemDef_t", "SteamItemInstanceID_t",
        "SteamAPIWarningMessageHook_t",
        "ManifestId_t", "AccountType_t", "ClientUnifiedMessageHandle",
        "BREAKPAD_HANDLE", "intptr_t", "ptrdiff_t",
        "PartyBeaconID_t", "CCallResult", "CCallback",
        "GameSearchErrorCode_t", "SteamErrMsg", "SteamAPIWarningMessageHook_t",
    }
    # Anything starting with E (enum) or T_t (typedef) heuristic:
    is_type_word = (
        tail in primitives
        or tail in sdk_types
        or tail.endswith("_t")
        or (len(tail) >= 2 and tail[0] == "E" and tail[1].isupper())
        or (len(tail) >= 2 and tail[0] == "C" and tail[1].isupper() and "Steam" in tail)
        or (len(tail) >= 2 and tail[0] == "I" and tail[1].isupper() and "Steam" in tail)
    )
    if not prefix:
        # Just one token: must be the type
        return (tail, f"_a{idx}")
    if is_type_word:
        # tail is type; full string IS the type
        return (arg, f"_a{idx}")
    return (prefix, tail)


# Map C++ types onto C-compatible types for the bridge PE.
def c_type(t: str) -> str:
    t = t.strip()
    # strip leading const, but keep const* as const-pointer
    t = re.sub(r"\bconst\b", "", t).strip()
    t = re.sub(r"\s+", " ", t)
    if not t or t == "void":
        return "void"
    if t == "bool":
        return "int"  # MSVC bool ABI 1 byte; the cast preserves real layout
    if "*" in t or "&" in t:
        return "void*"
    primitive_map = {
        "char": "char",
        "short": "short",
        "int": "int",
        "long": "long",
        "size_t": "size_t",
        "uint8_t": "uint8_t", "uint16_t": "uint16_t",
        "uint32_t": "uint32_t", "uint64_t": "uint64_t",
        "int8_t": "int8_t", "int16_t": "int16_t",
        "int32_t": "int32_t", "int64_t": "int64_t",
        "float": "float", "double": "double",
        "unsigned": "unsigned",
        "unsigned int": "unsigned int",
        "unsigned long": "unsigned long",
        "unsigned short": "unsigned short",
        "uint": "unsigned int",
    }
    if t in primitive_map:
        return primitive_map[t]
    # All other types (enums, Steam handle typedefs, CSteamID, etc.)
    # are integer-sized on x86_64 Windows ABI per Steamworks conventions.
    return "uint64_t"


def emit_forwarder(cls: str, m: dict, emitted_names: set) -> str | None:
    slot = m["slot"]
    ret = c_type(m["ret"])
    name = m["name"]
    fwd_name = f"SteamAPI_{cls}_{name}"
    # Dedup overloads — SDK exports one symbol per overload by appending
    # _0, _1, ... but our stubs collapse overloads. We emit the first
    # only; symbols beyond the first get name-suffix.
    base = fwd_name
    suffix = 0
    while fwd_name in emitted_names:
        suffix += 1
        fwd_name = f"{base}_{suffix}"
    emitted_names.add(fwd_name)
    cargs = [(c_type(t), n) for (t, n) in m["args"]]
    # Sanitize parameter names — avoid C keywords + collisions.
    seen = {"self"}
    fixed = []
    for (ct, an) in cargs:
        an2 = an
        if an2 in seen or an2 in {"int", "long", "char", "register", "auto", "default", "new"}:
            an2 = f"_p{len(fixed)}"
        seen.add(an2)
        fixed.append((ct, an2))
    cargs = fixed
    if cargs:
        params = ", ".join(f"{t} {n}" for (t, n) in cargs)
        sig_params = ", ".join(t for (t, _) in cargs)
        call_args = "self, " + ", ".join(n for (_, n) in cargs)
    else:
        params = ""
        sig_params = ""
        call_args = "self"
    body = []
    body.append(f"WN_STEAMAPI_EXPORT {ret} {fwd_name}(void* self{', ' + params if params else ''}) {{")
    if ret == "void":
        body.append(f"    if (self == NULL) return;")
    elif ret == "void*":
        body.append(f"    if (self == NULL) return NULL;")
    else:
        body.append(f"    if (self == NULL) return 0;")
    body.append(f"    void** vt = *(void***)self;")
    body.append(f"    typedef {ret} (*Fn)(void*{', ' + sig_params if cargs else ''});")
    body.append(f"    {'return ' if ret != 'void' else ''}((Fn)vt[{slot}])({call_args});")
    body.append("}")
    return "\n".join(body)


def main() -> int:
    parsed: dict[str, list[dict]] = {}
    for src in SOURCES:
        if not src.exists():
            print(f"WARNING: {src} not found, skipping", file=sys.stderr)
            continue
        text = src.read_text(encoding="utf-8", errors="replace")
        classes = find_classes(text)
        for cls, s, e in classes:
            if cls in parsed:
                continue  # first file wins
            body = text[s:e]
            ms = parse_virtuals(body)
            parsed[cls] = ms

    total = sum(len(v) for v in parsed.values())
    print(f"[gen_forwarders] parsed {len(parsed)} classes, {total} virtual methods", file=sys.stderr)
    for cls in sorted(parsed):
        print(f"  {cls}: {len(parsed[cls])}", file=sys.stderr)

    emitted = set()
    lines = [
        "/* AUTO-GENERATED by gen_forwarders.py — DO NOT EDIT. */",
        "",
        "#include <windows.h>",
        "#include <stdint.h>",
        "#include <stddef.h>",
        "",
        "#define WN_STEAMAPI_EXPORT __declspec(dllexport)",
        "",
    ]
    for cls in sorted(parsed):
        lines.append(f"/* === {cls} === {len(parsed[cls])} method(s) === */")
        for m in parsed[cls]:
            fwd = emit_forwarder(cls, m, emitted)
            if fwd is not None:
                lines.append(fwd)
        lines.append("")
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"[gen_forwarders] wrote {OUT} ({OUT.stat().st_size} bytes, {len(emitted)} exports)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
