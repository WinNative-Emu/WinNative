# DirectAudio driver binaries — provenance

The `.zip` files in this directory are **unmodified release artifacts** downloaded
verbatim from the upstream project — the shipped bytes match the checksums below.

**On-device modification (LGPL-2.1 §2 disclosure).** At install time WinNative
renames one entry in the unixlib's dynamic section: `winedirectaudio.so`'s
`DT_NEEDED` string `libaaudio.so` is rewritten in place to `libwaudio.so` (same
length). This applies to every bundled set, including the system-thread one.
`libaaudio.so` is a public Android soname that bionic force-resolves from
the system namespace, whose framework closure the Wine unixlib namespace cannot
link, so the stock unixlib fails to load. `libwaudio.so` is a small AAudio bridge
supplied by WinNative (its own code, not derived from this driver) that forwards
the 27 `AAudio_*` entry points to the real `/system/lib64/libaaudio.so` through a
platform-linked namespace. The corresponding source for a driver that needs no such
rename — the AAudio bridge folded into the unixlib itself — is at
https://github.com/WinNative-Emu/directaudio (branch `winnative/self-contained-aaudio`).

- **Project:** DirectAudio — native Wine → Android AAudio mmdevapi driver
- **Author:**  The412Banner <205237651+The412Banner@users.noreply.github.com>
- **Source:**  https://github.com/The412Banner/directaudio
- **Release:** `directaudio-v1.4.0` (tag), source commit `8cc1227`
- **License:** LGPL-2.1-or-later — full text in `COPYING`, notices in `NOTICE`,
  contributors in `AUTHORS` (all three copied verbatim from the upstream repo).

## Bundled artifacts

| File | SHA-256 |
| --- | --- |
| `directaudio-wine10-arm64ec-sdk28.zip` | `61ad4c8eb772fe90b2c7afcb8bf2e03307e36f42a1d3ee0bf1d59af00adfa48d` |
| `directaudio-wine10-arm64ec-sdk35.zip` | `5690824f562cbeb398d22a6542bcda14636bbc28d92c61ed6e6576375ebf40e3` |
| `directaudio-wine11-arm64ec-sdk28.zip` | `2a353160857da8af4bc5e65b3a58d8aaf3b3e0c05d28a94636885e45a24be314` |
| `directaudio-wine11-arm64ec-sdk35.zip` | `2c1c26e08162c433ba72542c5a60a1a0bafdb596be671b332c2cc65170ebbbcd` |
| `directaudio-wine11-systhread-arm64ec-sdk28.zip` | `30382cadbabc7e5d926db08166140f16e7e74eddbd2eb6d2baab0a8fae56099b` |
| `directaudio-wine11-systhread-arm64ec-sdk35.zip` | `6c7550ab656b65a6bc5902387e6f55ff809080a9c3228d7458609f045f78fb64` |

Each archive is the complete 3-file driver set:

```
aarch64-windows/winedirectaudio.drv   arm64ec PE  — loaded for 64-bit guest games
i386-windows/winedirectaudio.drv      32-bit PE   — loaded for wow64 guest games
aarch64-unix/winedirectaudio.so       bionic unixlib — the AAudio backend
```

The three interfaces and the layers each one was built for (as listed in the
upstream 1.4.0 release notes):

| Set | Built in | Layers |
| --- | --- | --- |
| `wine10` | `proton_10.0` | Proton 10.0-4, GE-Proton 10.0-34 |
| `wine11` | `ge-proton11-bionic` | Proton 11.0-1 / 11.0-2, GE-Proton 11.0-3 / 5 / 6 / 7 / 7.1 |
| `wine11-systhread` | `proton_11.0-cachyos-20261005` | Proton-CachyOS 11.0-20260703 / 11.0-20261005 |

`sdk28` is built for 4 KB kernel pages, `sdk35` for 16 KB —
`DirectAudioDriver.pageSizeTag()` picks between them at runtime.

## Picking between the builds

The builds differ only in the **mmdevapi unixlib ABI** they were compiled
against. That ABI is observable in the shipped binary: the `__wine_unix_call_funcs`
table in `aarch64-unix/winedirectaudio.so` holds **36** entries in the `wine10`
build and **37** in both `wine11` builds. Every Proton ships `winealsa.so` and
`winepulse.so` with the same table, so `DirectAudioDriver` reads the entry count
out of the Proton layer and compares it with each candidate build, rather than
parsing a version out of the profile name. Measured:

| Proton (arm64ec) | `winealsa.so` entries | imports `PsCreateSystemThread` | build selected |
| --- | --- | --- | --- |
| 9.0 (bundled) | 36 | no | `wine10` |
| 10.0-4 | 36 | no | `wine10` |
| 11.0-x, incl. our 11.0-2 | 37 | no | `wine11` |
| CachyOS 11.0-20261005 | 37 | yes | `wine11-systhread` |

**The entry count cannot tell `wine11` from `wine11-systhread`.** The system-thread
interface replaces `main_loop` + `timer_loop` with `main_loop_start` +
`main_loop_stop`, so both tables are 37 entries long while entries 3–9 are shifted by
one. A classic driver loaded into a system-thread layer therefore passes the count
check but has every call from `get_endpoint_ids` on routed to the wrong function:
mmdevapi registers an endpoint with an empty device name (`0,` instead of `0,aaudio`)
and faults while enumerating the next one. The interface is decided by the layer's
own unixlibs instead: a system-thread layer's `winepulse.so` / `winealsa.so` import
`PsCreateSystemThread`, a classic one does not. A system-thread layer is only ever
served the `wine11-systhread` set, and any other layer is only ever served the classic
sets (`wine11`, then `wine10`).

Note the `wine10`/`wine11` names are historical: the wine10 build also serves
Proton 9.0. A Proton whose table matches no candidate build is refused and the
launch falls back to the default audio driver.

The `.drv` PE halves carry no export table in the wine11 builds. That is **normal
for Wine 11** — its own `winealsa.drv` and `winepulse.drv` are byte-for-byte the
same shape (identical `.text`/`.rdata`/`.pdata` sizes, importing only
`ucrtbase.dll` and `kernel32.dll`), because in Wine 11 the `.drv` is a generic
unixlib host and all the driver logic lives in the `.so`. Do not read the missing
exports as a broken artifact.

## Source availability (LGPL-2.1 §6)

The corresponding source for these binaries is the upstream repository above at tag
`directaudio-v1.4.0`. Because the driver links Wine's LGPL mmdevapi internals, the
Wine tree it was built against forms part of the corresponding source; see the
upstream `INTEGRATION.md` for the build recipe. The driver is loaded dynamically by
Wine's own loader and can be replaced by dropping a different build into the
container's `lib/wine/` directories, which satisfies the relinking requirement.

To refresh these artifacts (the pattern covers the `wine10`, `wine11` and
`wine11-systhread` sets):

```sh
gh release download directaudio-v1.4.0 --repo The412Banner/directaudio \
   --pattern 'directaudio-wine*-arm64ec-sdk*.zip' \
   --dir app/src/main/assets/directaudio
```

## The GameScope build (`linux/winedirectaudio.so`)

Built by WinNative from release `directaudio-v1.3.2` (`directaudio.c` unmodified) for
aarch64 glibc, against the headers of `ValveSoftware/wine` at
`c1928082ac141b7f03982eacce980a4fe4840d0b`, and linked with WinNative's own
`tools/linuxfs/directaudio/wn_aaudio_client.c` in place of Android's `libaaudio`. The complete
corresponding source and the script that reproduces the binary are in this repository under
`tools/linuxfs/directaudio/` (LGPL-2.1 §6); the driver's source is fetched by that script from the
upstream tag. This binary was not rebuilt for 1.4.0.

| File | SHA-256 |
| --- | --- |
| `linux/winedirectaudio.so` | `089b83f2d099c4e7d20c1e556676174ff2a5a883a58e60093f09b07516059fd5` |
