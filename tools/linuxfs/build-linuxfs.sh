#!/bin/bash
# Assembles the Linux runtime (files/linuxfs on the device) from Arch Linux ARM packages:
# the base rootfs, gamescope with Xwayland and Mesa, the XFCE desktop on labwc, and the WinNative
# session scripts from overlay/. No Valve software is included; winnative-steam-install fetches the
# native arm64 Steam client from Valve at first use.
#
#   tools/linuxfs/build-linuxfs.sh <work dir> <output.tar.zst>
#
# Needs curl, tar, zstd, ar, python3, proot, qemu-aarch64-static, aarch64-linux-gnu-gcc/g++, meson >= 1.5 and ninja.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
work=${1:?work dir}
out=${2:?output tarball}
mirror=http://mirror.archlinuxarm.org/aarch64
base_url=http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz
seeds=(gamescope mesa vulkan-freedreno xorg-xwayland xorg-xhost xorg-xrandr vulkan-tools wayland-utils
  mesa-utils foot unzip dbus libpulse nss libnm curl ca-certificates fontconfig freetype2
  bash coreutils grep sed gawk which findutils glib2 libglvnd ibus libxcomposite libxdamage libxrandr
  libxtst libxi ttf-dejavu openal libvdpau lsof labwc xfce4-session xfce4-panel xfdesktop thunar
  xfce4-terminal xfce4-settings xfce4-appfinder mousepad adwaita-icon-theme
  # An app build from before the desktop starts this file manager in its place.
  pcmanfm)

mkdir -p "$work/db" "$work/pkgs" "$work/rootfs"
cd "$work"

for repo in core extra alarm; do
  [ -s "db/$repo.db" ] || curl -fsSLo "db/$repo.db" "$mirror/$repo/$repo.db"
  mkdir -p "db/x_$repo"
  tar -xzf "db/$repo.db" -C "db/x_$repo"
done

# The package closure over the repository databases. Arch Linux ARM keeps %DEPENDS% in a
# separate `depends` file, and several dependencies are virtual names (libseat, sdl2,
# xorg-server-xwayland) satisfied through %PROVIDES%.
python3 - "${seeds[@]}" > pkglist.txt <<'PY'
import os, sys, collections
pkgs, provides = {}, collections.defaultdict(list)
def strip(d):
    for op in (">=", "<=", "==", ">", "<", "="):
        if op in d: return d.split(op)[0]
    return d
for repo in ("core", "extra", "alarm"):
    base = os.path.join("db", "x_" + repo)
    for entry in os.listdir(base):
        fields, key = {}, None
        for name in ("desc", "depends"):
            path = os.path.join(base, entry, name)
            if not os.path.exists(path): continue
            for line in open(path, encoding="utf-8", errors="replace"):
                line = line.rstrip("\n")
                if line.startswith("%") and line.endswith("%"): key = line.strip("%"); fields[key] = []
                elif line == "": key = None
                elif key: fields[key].append(line)
        n = fields.get("NAME", [None])[0]
        if not n: continue
        rec = {"repo": repo, "file": fields["FILENAME"][0],
               "depends": [strip(d) for d in fields.get("DEPENDS", [])],
               "provides": [strip(p) for p in fields.get("PROVIDES", [])]}
        pkgs[n] = rec
        provides[n].append(n)
        for p in rec["provides"]: provides[p].append(n)
seen, queue, missing = set(), list(sys.argv[1:]), []
while queue:
    want = queue.pop()
    real = want if want in pkgs else (provides.get(want) or [None])[0]
    if real is None: missing.append(want); continue
    if real in seen: continue
    seen.add(real)
    queue.extend(pkgs[real]["depends"])
if missing: sys.exit("unresolved: " + " ".join(missing))
for n in sorted(seen): print(pkgs[n]["repo"] + "/" + pkgs[n]["file"])
PY
echo "$(wc -l < pkglist.txt) packages"

while read -r entry; do
  file=${entry#*/}
  # A mirror error page is not a package; fetch again rather than fail at extraction.
  if ! tar -tf "pkgs/$file" >/dev/null 2>&1; then
    rm -f "pkgs/$file"
    curl -fsSLo "pkgs/$file" "$mirror/$entry"
    tar -tf "pkgs/$file" >/dev/null
  fi
done < pkglist.txt

[ -s base.tar.gz ] || curl -fsSLo base.tar.gz "$base_url"

rm -rf rootfs && mkdir rootfs
# The base tarball is owned by root:root with device nodes; extracted unprivileged it becomes
# the build user's, which is what proot presents on the device anyway.
tar -xzf base.tar.gz -C rootfs --no-same-owner --no-same-permissions --exclude=dev 2>/dev/null || true
while read -r entry; do
  file=${entry#*/}
  tar -xf "pkgs/$file" -C rootfs --no-same-owner --no-same-permissions \
    --exclude=.PKGINFO --exclude=.MTREE --exclude=.INSTALL --exclude=.BUILDINFO --exclude=.CHANGELOG
done < pkglist.txt

chmod -R u+rwX rootfs
# Arch's Turnip only knows the msm DRM kernel driver; Android reaches the Adreno through KGSL.
# build-turnip.sh cross-builds Mesa's Turnip with the KGSL backend against this rootfs.
"$here/build-turnip.sh" "$work/turnip" "$work/rootfs"
install -m 755 "$work/turnip/libvulkan_freedreno.so" rootfs/usr/lib/libvulkan_freedreno.so
printf '{\n    "ICD": {\n        "api_version": "1.4.0",\n        "library_path": "/usr/lib/libvulkan_freedreno.so"\n    },\n    "file_format_version": "1.0.0"\n}\n' \
  > rootfs/usr/share/vulkan/icd.d/freedreno_icd.json
rm -f rootfs/usr/share/vulkan/icd.d/nvidia_icd.json

# Steam's arm64 UI (steamui.so, vgui2_s.so) still links GTK 2, which Arch no longer packages;
# Debian's build links only sonames the rootfs has, so its two libraries are enough.
gtk2_deb=libgtk2.0-0t64_2.24.33-7_arm64.deb
gtk2_sha=28b2f1622197443f07f25a93e03db1a964184946ac12f501b8221c895026d0ca
[ -s "pkgs/$gtk2_deb" ] || curl -fsSLo "pkgs/$gtk2_deb" "http://deb.debian.org/debian/pool/main/g/gtk+2.0/$gtk2_deb"
echo "$gtk2_sha  pkgs/$gtk2_deb" | sha256sum -c --quiet
rm -rf gtk2 && mkdir gtk2 && (cd gtk2 && ar x "../pkgs/$gtk2_deb" && tar -xf data.tar.*)
for n in gtk gdk; do
  install -m 755 "gtk2/usr/lib/aarch64-linux-gnu/lib$n-x11-2.0.so.0.2400.33" rootfs/usr/lib/
  ln -sfn "lib$n-x11-2.0.so.0.2400.33" "rootfs/usr/lib/lib$n-x11-2.0.so.0"
done

cp -a "$here/overlay/." rootfs/
# Preloaded into every session process: what the kernel or the app sandbox withholds, answered
# in the process itself; see preload/*.c.
mkdir -p rootfs/usr/local/lib
aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -pthread -o rootfs/usr/local/lib/libwnsession.so "$here"/preload/*.c -ldl
# The same fake evdev layer Wine sessions use, built against glibc: controllers reach Steam and
# SDL through /dev/input nodes backed by the app's input rings.
aarch64-linux-gnu-g++ -shared -fPIC -O2 -Wall -Wno-attributes -Wno-nonnull-compare -pthread \
  -o rootfs/usr/local/lib/libwninput.so "$here/../../app/src/main/cpp/winlator/fakeinput.cpp" -ldl
aarch64-linux-gnu-strip rootfs/usr/local/lib/libwnsession.so rootfs/usr/local/lib/libwninput.so
# The app ships the same two, the Vulkan driver and the session's scripts, and refreshes them into
# an installed rootfs at every session start, so a build and the rootfs it boots never disagree
# about them.
install -Dm644 rootfs/usr/lib/libvulkan_freedreno.so "$here/../../app/src/main/assets/linuxfs/usr/lib/libvulkan_freedreno.so"
install -Dm644 rootfs/usr/local/lib/libwnsession.so "$here/../../app/src/main/assets/linuxfs/usr/local/lib/libwnsession.so"
install -Dm644 rootfs/usr/local/lib/libwninput.so "$here/../../app/src/main/assets/linuxfs/usr/local/lib/libwninput.so"
for script in "$here"/overlay/usr/local/bin/winnative-*; do
  install -Dm644 "$script" "$here/../../app/src/main/assets/linuxfs/usr/local/bin/$(basename "$script")"
done
mkdir -p rootfs/dev rootfs/proc rootfs/sys rootfs/tmp rootfs/root rootfs/run/user
chmod 1777 rootfs/tmp
# The dynamic loader takes its search path from here; ldconfig cannot run without the target CPU.
printf '/usr/local/lib\n/usr/lib\n/usr/lib32\n' > rootfs/etc/ld.so.conf
rm -f rootfs/etc/ld.so.cache
# Xwayland and Steam want a machine id and a resolver.
rm -f rootfs/etc/machine-id rootfs/etc/resolv.conf
head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n' > rootfs/etc/machine-id
printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > rootfs/etc/resolv.conf
printf 'root:x:0:0:root:/root:/bin/bash\n' > rootfs/etc/passwd
printf 'root:x:0:\n' > rootfs/etc/group

# What pacman's hooks would have built: the mime database, pixbuf loader and icon caches,
# GSettings schemas and the font cache. Only these run from the rootfs, under qemu.
proot -q "$(command -v qemu-aarch64-static)" -r rootfs -w / -b /dev -b /proc /bin/bash -c '
  export PATH=/usr/bin:/bin
  update-mime-database /usr/share/mime
  gdk-pixbuf-query-loaders --update-cache
  glib-compile-schemas /usr/share/glib-2.0/schemas
  fc-cache -f
  for d in /usr/share/icons/*/; do [ -f "$d/index.theme" ] && gtk-update-icon-cache -q -t -f "$d"; done
  rm -rf /root/.cache' >/dev/null

# The build's version, newest highest: the installer offers a newer archive to a runtime carrying
# an older one. The same number goes into the archive's description below.
version=$(date -u +%Y%m%d%H%M)
mkdir -p rootfs/etc/winnative
echo "$version" > rootfs/etc/winnative/version

# Level 19 with a 128 MiB window: the same libraries recur across the tree, and the app's decoder
# accepts that window by default, so the archive shrinks while unpacking stays as fast as zstd is.
tar -C rootfs -cf - . | zstd -T0 -19 --long=27 -o "$out" --force
# What the app's installer checks before it unpacks: the archive's digest and size, and the bytes
# it writes, counting a hard-linked file once as tar stores it.
unpacked=$(find rootfs -type f -printf '%i %s\n' | sort -u | awk '{ total += $2 } END { print total }')
printf '{\n  "version": %s,\n  "sha256": "%s",\n  "size": %s,\n  "unpacked": %s\n}\n' \
  "$version" "$(sha256sum "$out" | cut -d' ' -f1)" "$(stat -c %s "$out")" "$unpacked" > "${out%.tar.zst}.json"
ls -la "$out" "${out%.tar.zst}.json"
