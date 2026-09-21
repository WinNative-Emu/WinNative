#!/usr/bin/env bash
#
# update-gbe-fork.sh — refresh the bundled ColdClient / gbe_fork emulator.
#
# Rebuilds app/src/main/assets/experimental-drm.tzst from the latest (or a
# pinned) gbe_fork Windows release published at:
#   https://github.com/Detanup01/gbe_fork/releases
#
# The asset is intentionally a PINNED, checked-in binary — the build does NOT
# fetch it. Run this script deliberately to bump the version, review the diff,
# and commit. That keeps builds reproducible and the 3rd-party binary auditable.
#
# Usage:
#   tools/update-gbe-fork.sh              # update to the newest release
#   tools/update-gbe-fork.sh release-2026_05_16   # pin a specific tag
#
# Requires: curl, 7z (p7zip), tar, zstd, python3
set -euo pipefail

REPO="Detanup01/gbe_fork"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET="$ROOT/app/src/main/assets/experimental-drm.tzst"
VERSION_FILE="$ROOT/tools/gbe_fork.version"

# MN-4: pin the legacy DRM stub. tools/StubDRM64.dll is a checked-in binary (NOT part of the
# gbe_fork download), so a gbe_fork bump can't swap it — but a bad merge, tampering, or
# corruption could. Validating its hash here turns any silent change into a loud build failure
# instead of shipping a different DLL that may reintroduce the SteamStub regression the legacy
# stub avoids. Update STUBDRM64_SHA256 deliberately if you ever replace the stub.
STUBDRM64_DLL="$ROOT/tools/StubDRM64.dll"
STUBDRM64_SHA256="b3c69278e405c84cf4f1b264bae028707cddb6009da9df2f4979b1b3d2d30852"

for bin in curl 7z tar zstd python3; do
    command -v "$bin" >/dev/null 2>&1 || { echo "ERROR: '$bin' not found in PATH" >&2; exit 1; }
done

TAG="${1:-}"
if [ -z "$TAG" ]; then
    echo "Querying latest $REPO release..."
    TAG="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
        | python3 -c 'import sys,json; print(json.load(sys.stdin)["tag_name"])')"
fi
echo "Target gbe_fork release: $TAG"

URL="https://github.com/$REPO/releases/download/$TAG/emu-win-release.7z"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Downloading $URL ..."
curl -fsSL -o "$WORK/emu.7z" "$URL"
7z x -o"$WORK/emu" "$WORK/emu.7z" >/dev/null

SRC="$WORK/emu/release/steamclient_experimental"
[ -d "$SRC" ] || { echo "ERROR: steamclient_experimental/ missing in release" >&2; exit 1; }

# Stage the ColdClient files under the Wine-prefix layout the extractor expects.
DST="$WORK/stage/home/xuser/.wine/drive_c/Program Files (x86)/Steam"
mkdir -p "$DST/extra_dlls"
cp "$SRC/steamclient.dll"            "$DST/"
cp "$SRC/steamclient64.dll"          "$DST/"
cp "$SRC/steamclient_loader_x64.exe" "$DST/"
cp "$SRC/steamclient_loader_x86.exe" "$DST/"
cp "$SRC/GameOverlayRenderer.dll"    "$DST/"
cp "$SRC/GameOverlayRenderer64.dll"  "$DST/"
# Runtime DRM patcher: bundle the legacy StubDRM64.dll (checked into tools/)
# instead of gbe_fork's modern steamclient_extra. The modern patcher is
# "experimental" and regressed SteamStub games (verified on Monster Hunter
# Stories — "Application load error 3:0000065432"); the legacy stub works.
# MN-4: verify the pinned hash before bundling — fail loudly on any unexpected change.
if command -v sha256sum >/dev/null 2>&1; then
    actual_stub_sha="$(sha256sum "$STUBDRM64_DLL" | awk '{print $1}')"
else
    actual_stub_sha="$(shasum -a 256 "$STUBDRM64_DLL" | awk '{print $1}')"
fi
if [ "$actual_stub_sha" != "$STUBDRM64_SHA256" ]; then
    echo "ERROR: tools/StubDRM64.dll sha256 mismatch — refusing to bundle an unexpected DRM stub." >&2
    echo "  expected: $STUBDRM64_SHA256" >&2
    echo "  actual:   $actual_stub_sha" >&2
    echo "If you intentionally changed the stub, update STUBDRM64_SHA256 in this script." >&2
    exit 1
fi
cp "$STUBDRM64_DLL" "$DST/extra_dlls/"

# Deterministic tar (sorted, zeroed owner) + zstd, matching the asset format.
( cd "$WORK/stage" && tar --numeric-owner --owner=0 --group=0 --sort=name -cf "$WORK/drm.tar" home )
zstd -19 -q -f -o "$ASSET" "$WORK/drm.tar"

# The "+stubdrm" suffix records that this asset bundles the legacy StubDRM64
# patcher rather than gbe_fork's steamclient_extra — and makes the runtime
# version-gate (SteamClientManager) re-extract on update.
echo "${TAG}+stubdrm" > "$VERSION_FILE"
echo
echo "Updated: $ASSET ($(stat -c%s "$ASSET") bytes)"
echo "Pinned:  $VERSION_FILE -> $TAG"
echo "Review the diff and commit."
