#!/bin/bash
# Cross-builds Mesa's Turnip Vulkan driver for the Linux runtime with the KGSL backend, against
# the Arch Linux ARM rootfs build-linuxfs.sh assembled, and applies turnip/*.patch.
#
#   tools/linuxfs/build-turnip.sh <work dir> <rootfs>      -> <work dir>/libvulkan_freedreno.so
#
# Needs aarch64-linux-gnu-gcc/g++, meson >= 1.5, ninja, python3 with mako and pyyaml.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
work=$(mkdir -p "${1:?work dir}" && cd "$1" && pwd)
rootfs=$(cd "${2:?rootfs}" && pwd)
version=26.2.2
sha256=eeb29ca7e56cfaa8e8a79538dcf834e3b18e501c31bef5145e959ea437cc4216
src=$work/mesa-$version

cd "$work"
if [ ! -f "$src/.patched" ]; then
  [ -s "mesa-$version.tar.xz" ] || curl -fsSLo "mesa-$version.tar.xz" "https://archive.mesa3d.org/mesa-$version.tar.xz"
  echo "$sha256  mesa-$version.tar.xz" | sha256sum -c --quiet
  rm -rf "$src" && tar -xJf "mesa-$version.tar.xz"
  for p in "$here"/turnip/*.patch; do
    patch -d "$src" -p1 < "$p"
  done
  touch "$src/.patched"
fi

cat > cross.ini <<EOF
[binaries]
c = 'aarch64-linux-gnu-gcc'
cpp = 'aarch64-linux-gnu-g++'
ar = 'aarch64-linux-gnu-ar'
strip = 'aarch64-linux-gnu-strip'
pkg-config = 'pkg-config'

[properties]
sys_root = '$rootfs'
pkg_config_libdir = '$rootfs/usr/lib/pkgconfig:$rootfs/usr/share/pkgconfig'

[built-in options]
c_args = ['--sysroot=$rootfs']
cpp_args = ['--sysroot=$rootfs']
c_link_args = ['--sysroot=$rootfs', '-L$rootfs/usr/lib']
cpp_link_args = ['--sysroot=$rootfs', '-L$rootfs/usr/lib']

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

[ -f build/build.ninja ] || meson setup build "$src" --cross-file cross.ini --buildtype release \
  -Dvulkan-drivers=freedreno -Dfreedreno-kmds=msm,kgsl -Dgallium-drivers= -Dplatforms=wayland,x11 \
  -Dopengl=false -Dgbm=disabled -Dglx=disabled -Degl=disabled -Dllvm=disabled -Dvulkan-layers= -Dtools=
ninja -C build src/freedreno/vulkan/libvulkan_freedreno.so
aarch64-linux-gnu-strip -o libvulkan_freedreno.so build/src/freedreno/vulkan/libvulkan_freedreno.so
ls -la libvulkan_freedreno.so
