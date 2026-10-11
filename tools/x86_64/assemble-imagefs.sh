#!/usr/bin/env bash
# Assemble a BASE x86_64 imagefs from the x86_64 Termux sysroot that WinNative-Emu/proton-wine builds
# Wine against (termuxfs-x86_64.tar).
#
# STATUS: experimental, never run against a live app. The arm64 imagefs.tzst is a Git LFS object that
# has not been inspected, so what else it carries is unknown. This keeps everything the sysroot
# provides, drops development-only material, optionally adds extra libraries, checks the result
# against tools/x86_64/imagefs-required.txt and writes a report. Missing paths only fail the script
# when STRICT=1.
#
# Usage: assemble-imagefs.sh <termuxfs-x86_64.tar> <out-dir> [extra-libs-dir]

set -euo pipefail

sysroot_tar=${1:?usage: $0 <termuxfs-x86_64.tar> <out-dir> [extra-libs-dir]}
out_dir=${2:?usage: $0 <termuxfs-x86_64.tar> <out-dir> [extra-libs-dir]}
extra_dir=${3:-}
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
required_list=${REQUIRED_LIST:-$here/imagefs-required.txt}
prefix='data/data/com.termux/files/usr'

mkdir -p "$out_dir"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
root="$work/imagefs"

echo "==> extracting usr/ from $(basename "$sysroot_tar")"
tar -xf "$sysroot_tar" -C "$work" "$prefix"
mkdir -p "$root"
mv "$work/$prefix" "$root/usr"

echo "==> dropping development-only material"
rm -rf "$root/usr/include" "$root/usr/share/man" "$root/usr/share/doc" "$root/usr/share/info" \
  "$root/usr/share/aclocal" "$root/usr/lib/pkgconfig" "$root/usr/share/pkgconfig" "$root/usr/lib/cmake"
find "$root/usr" \( -name '*.a' -o -name '*.la' \) -type f -delete
mkdir -p "$root/tmp" "$root/etc"

if [ -n "$extra_dir" ] && [ -d "$extra_dir" ]; then
  echo "==> adding extra libraries from $extra_dir"
  find "$extra_dir" -type f -name '*.so*' -exec cp -v {} "$root/usr/lib/" \;
fi

echo "==> checking required paths"
lines=()
missing=0
while IFS= read -r raw; do
  path=${raw%%#*}
  path=$(printf '%s' "$path" | xargs)
  [ -n "$path" ] || continue
  if [ -e "$root/$path" ] || [ -L "$root/$path" ]; then
    lines+=("- [x] \`$path\`")
  else
    lines+=("- [ ] **missing** \`$path\`")
    missing=$((missing + 1))
  fi
done < "$required_list"

{
  echo "### x86_64 imagefs report"
  echo
  echo "Unpacked size: $(du -sh "$root" | cut -f1)"
  echo
  printf '%s\n' "${lines[@]}"
  echo
  echo "$missing required path(s) missing. See tools/x86_64/imagefs-required.txt for where each comes from."
} | tee "$out_dir/imagefs-report.txt"

echo "==> packing imagefs-x86_64.tzst"
tar --owner=0 --group=0 --numeric-owner -C "$root" -cf - . | zstd -T0 -19 -q -f -o "$out_dir/imagefs-x86_64.tzst"
ls -lh "$out_dir/imagefs-x86_64.tzst"

if [ "${STRICT:-0}" = 1 ] && [ "$missing" -gt 0 ]; then
  echo "STRICT=1 and $missing required path(s) missing" >&2
  exit 1
fi
