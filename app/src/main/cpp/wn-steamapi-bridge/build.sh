#!/bin/bash
# build.sh — cross-compile the bridge DLL and stage it into assets/.
# Standalone (no gradle/ndk); needs x86_64-w64-mingw32-gcc on PATH.
set -euo pipefail

cd "$(dirname "$0")"
OUT="build/steam_api64.dll"
ASSET_DIR="../../assets/wnsteam/steampipe"
ASSET="$ASSET_DIR/steam_api64.dll"
GBE_SOURCE="../../../../../References/WinNative/app/src/main/assets/steampipe/steam_api64.dll"

# Refresh gbe_fork export list → /tmp/gbe_real.txt (input to gen_forward_def.py).
if [ -f "$GBE_SOURCE" ]; then
    x86_64-w64-mingw32-objdump -p "$GBE_SOURCE" 2>/dev/null \
        | awk '/\[Ordinal\/Name Pointer\] Table/,/^$/' \
        | awk 'NR>1 && /\[/{print $NF}' \
        > /tmp/gbe_real.txt
fi

# Generate forwarders, .def forwards, and matchmaking override stubs.
python3 gen_forwarders.py
python3 gen_forward_def.py
python3 gen_overrides.py

mkdir -p build

# Hybrid bridge: our ~55 matchmaking overrides + .def forwards (~1200 exports)
# → original_steam_api64.dll. Omit steam_api_bridge_flat.c — gbe_fork covers the
# flat-C path and compiling it would duplicate the .def export definitions.
x86_64-w64-mingw32-gcc -shared -O2 -fvisibility=hidden \
    -o "$OUT" \
    steam_api_bridge_overrides.c \
    steam_api_bridge_callbacks.c \
    steam_api_bridge_steamclient.c \
    steam_api_bridge_lifecycle.c \
    steam_api_bridge.def \
    -static-libgcc -lkernel32 -luser32 \
    -Wl,--enable-stdcall-fixup \
    -Wl,--kill-at

echo "[build.sh] PE built: $(ls -la "$OUT" | awk '{print $5}') bytes"

mkdir -p "$ASSET_DIR"
cp "$OUT" "$ASSET"
echo "[build.sh] Staged: $ASSET"

x86_64-w64-mingw32-objdump -p "$OUT" \
    | awk '/^\[Ordinal\/Name Pointer\] Table/{f=1;next} f && /^$/{exit} f{print}' \
    | head -40
