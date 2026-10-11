#!/bin/bash
# Builds DirectAudio's unixlib for a Linux session: The412Banner's driver, unmodified, against the
# headers of Valve's Wine 11, with wn_aaudio_client.c standing in for Android's libaaudio. The two
# PE halves are not built here - they hold no platform code, and the app takes them from the
# upstream release it already ships (app/src/main/assets/directaudio).
#
#   tools/linuxfs/directaudio/build-directaudio.sh <work dir>   -> <work dir>/winedirectaudio.so
#
# Needs git, widl (wine's IDL compiler, any recent version) and aarch64-linux-gnu-gcc.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
work=${1:?work dir}
driver_repo=https://github.com/The412Banner/directaudio
driver_tag=directaudio-v1.3.2
wine_repo=https://github.com/ValveSoftware/wine
wine_branch=experimental_11.0
wine_commit=c1928082ac141b7f03982eacce980a4fe4840d0b

mkdir -p "$work"
cd "$work"
[ -d driver ] || git -c advice.detachedHead=false clone -q --depth 1 --branch "$driver_tag" "$driver_repo" driver
if [ ! -d wine ]; then
  git clone -q --depth 1 --filter=blob:none --sparse --branch "$wine_branch" "$wine_repo" wine
  git -C wine sparse-checkout set include dlls/mmdevapi
fi
# The unixlib's call table has to be the one Valve's mmdevapi walks; a branch that has moved on
# is fetched back to the commit this was proven against.
if [ "$(git -C wine rev-parse HEAD)" != "$wine_commit" ]; then
  git -C wine fetch -q --depth 1 origin "$wine_commit"
  git -C wine checkout -q "$wine_commit"
fi

# The driver includes "../mmdevapi/unixlib.h", as it does inside a Wine tree.
rm -rf src gen
mkdir -p src/winedirectaudio.drv src/mmdevapi gen
cp driver/directaudio.c src/winedirectaudio.drv/
cp wine/dlls/mmdevapi/unixlib.h src/mmdevapi/

cflags=(-fPIC -O2 -fvisibility=hidden -DWINE_UNIX_LIB -D__WINESRC__ -D_GNU_SOURCE
  -I"$here/include" -Igen -Iwine/include -I"$here/../../../app/src/main/cpp/wnaudiohook")
# Wine generates its COM headers from IDL at build time; make the ones the driver reaches.
for _ in $(seq 1 64); do
  missing=$(aarch64-linux-gnu-gcc "${cflags[@]}" -E src/winedirectaudio.drv/directaudio.c 2>&1 >/dev/null |
    sed -n 's/.*fatal error: \(.*\)\.h: No such file.*/\1/p' | head -1 || true)
  [ -n "$missing" ] || break
  [ -f "wine/include/$missing.idl" ] || { echo "no header $missing.h" >&2; exit 1; }
  widl -Iwine/include -h -o "gen/$missing.h" "wine/include/$missing.idl"
done

aarch64-linux-gnu-gcc "${cflags[@]}" -Wno-attributes -c -o directaudio.o src/winedirectaudio.drv/directaudio.c
aarch64-linux-gnu-gcc "${cflags[@]}" -Wall -Wextra -c -o client.o "$here/wn_aaudio_client.c"
# Like Wine's own drivers the unixlib names ntdll.so as a dependency, which the loader satisfies
# with the one Wine already has loaded; an empty library of that name stands in for it here.
mkdir -p stub
aarch64-linux-gnu-gcc -shared -Wl,-soname,ntdll.so -o stub/ntdll.so -x c /dev/null
aarch64-linux-gnu-gcc -shared -Wl,-soname,winedirectaudio.so -Wl,--allow-shlib-undefined -Wl,--no-as-needed \
  -o winedirectaudio.so directaudio.o client.o -Lstub -l:ntdll.so -lpthread
aarch64-linux-gnu-strip winedirectaudio.so
entries=$(aarch64-linux-gnu-readelf -sW winedirectaudio.so | awk '$8 == "__wine_unix_call_funcs" { print $3 / 8 }')
echo "winedirectaudio.so: $entries unixlib entries"
ls -la winedirectaudio.so
