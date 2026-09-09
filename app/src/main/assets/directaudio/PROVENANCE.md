# DirectAudio driver binaries — provenance

The `.zip` files in this directory are **unmodified release artifacts** downloaded
verbatim from the upstream project — the shipped bytes match the checksums below.

**On-device modification (LGPL-2.1 §2 disclosure).** At install time WinNative
renames one entry in the unixlib's dynamic section: `winedirectaudio.so`'s
`DT_NEEDED` string `libaaudio.so` is rewritten in place to `libwaudio.so` (same
length). `libaaudio.so` is a public Android soname that bionic force-resolves from
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
- **Release:** `directaudio-v1.3.2` (tag), source commit `2101085`
- **License:** LGPL-2.1-or-later — full text in `COPYING`, notices in `NOTICE`,
  contributors in `AUTHORS` (all three copied verbatim from the upstream repo).

## Bundled artifacts

| File | SHA-256 |
| --- | --- |
| `directaudio-wine10-arm64ec-sdk28.zip` | `fa5d1196d8f942ed68b75e980fc31f0d094657b828164a895e033082d7e9474f` |
| `directaudio-wine10-arm64ec-sdk35.zip` | `efb7af3f40556bb25da7fca16b0fed14e8f6b3fe5ab9c64999d21ad3ce67add3` |
| `directaudio-wine11-arm64ec-sdk28.zip` | `b3811f306f04b12233e5f797bc3ca16d2b89168513c7d76e1a5993b40d806cac` |
| `directaudio-wine11-arm64ec-sdk35.zip` | `4b88204685ebf925e7fdb9023d18b9af35140ae13d61667ab4b88b927bcce6a7` |

Each archive is the complete 3-file driver set:

```
aarch64-windows/winedirectaudio.drv   arm64ec PE  — loaded for 64-bit guest games
i386-windows/winedirectaudio.drv      32-bit PE   — loaded for wow64 guest games
aarch64-unix/winedirectaudio.so       bionic unixlib — the AAudio backend
```

`wine10` matches Proton 10.0-4; `wine11` serves Proton 11.0-1 / 11.0-3 / 11.0-5 /
11.0-6 (ABI-interchangeable). `sdk28` is built for 4 KB kernel pages, `sdk35` for
16 KB — `DirectAudioDriver.pageSizeTag()` picks between them at runtime.

## Source availability (LGPL-2.1 §6)

The corresponding source for these binaries is the upstream repository above at tag
`directaudio-v1.3.2`. Because the driver links Wine's LGPL mmdevapi internals, the
Wine tree it was built against forms part of the corresponding source; see the
upstream `INTEGRATION.md` for the build recipe. The driver is loaded dynamically by
Wine's own loader and can be replaced by dropping a different build into the
container's `lib/wine/` directories, which satisfies the relinking requirement.

To refresh these artifacts:

```sh
gh release download directaudio-v1.3.2 --repo The412Banner/directaudio \
   --pattern 'directaudio-wine1*-arm64ec-sdk*.zip' \
   --dir app/src/main/assets/directaudio
```
