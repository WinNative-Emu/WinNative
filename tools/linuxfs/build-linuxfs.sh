#!/bin/bash
# Assembles the Linux runtime (files/linuxfs on the device) from Arch Linux ARM packages:
# the base rootfs, gamescope with Xwayland and Mesa, a file manager, and the WinNative session
# scripts from overlay/. No Valve software is included; winnative-steam-install fetches the
# native arm64 Steam client from Valve at first use.
#
#   tools/linuxfs/build-linuxfs.sh <work dir> <output.tar.zst>
#
# Needs curl, tar, zstd and python3 on the build host; nothing runs from the rootfs.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
work=${1:?work dir}
out=${2:?output tarball}
mirror=http://mirror.archlinuxarm.org/aarch64
base_url=http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz
seeds=(gamescope mesa vulkan-freedreno xorg-xwayland xorg-xhost xorg-xrandr vulkan-tools wayland-utils
  mesa-utils foot pcmanfm unzip dbus libpulse nss libnm curl ca-certificates fontconfig freetype2
  bash coreutils grep sed gawk which findutils glib2 libglvnd ibus libxcomposite libxdamage libxrandr
  libxtst libxi)

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

chmod -R u+w rootfs
cp -a "$here/overlay/." rootfs/
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

tar -C rootfs -cf - . | zstd -T0 -19 -o "$out" --force
ls -la "$out"
