# Linux Proton sources and integration

Reviewed Bannerlator branch `feat/linux-gamescope-runtime` at `e58a2befdd8f5b7ce0e42f81dbbf920194a74ae4`, cloned into `/tmp/bannerlator-review.s2FtH5/Bannerlator`.

Bannerlator's `LinuxProtons.java` reads `https://raw.githubusercontent.com/The412Banner/winlator-contents/main/linux-protons.json`. The catalog points directly to upstream release archives, not Bannerlator-hosted binaries:

- GE: `GloriousEggroll/proton-ge-custom`, `GE-Proton11-7-aarch64.tar.gz`, 645,786,140 bytes.
- CachyOS: `CachyOS/proton-cachyos`, `proton-cachyos-11.0-20260703-slr-arm64.tar.xz`, 339,912,088 bytes.

Both files were downloaded and verified against the upstream SHA-512 assets. Their native Wine executables are ARM64 ELF under `files/bin-arm64/wine`. Both `toolmanifest.vdf` files declare `/proton %verb%` and require Steam Linux Runtime app 4185400.

Bannerlator downloads and verifies in Android, stages archives in `compatibilitytools.d/.bannerlator-download`, and writes requests to `root/.bl-proton-extra`. Its session invokes `bannerlator-proton-extra` to unpack before starting Steam. `bannerlator-steam-compat.adopt_extras` preserves the original manifest, wraps the Proton entry point without the `require_tool_appid` dependency, and protects the tool's internal name from default compatibility remapping. The original dependency invokes pressure-vessel/user namespaces, unavailable inside this Android runtime. Its catalog reports downloaded archives as pending until a session processes them.

WinNative now discovers the latest three stable ARM64 releases from each official repository through GitHub's releases API, requiring the release asset's SHA-256 digest. It does not offer x86_64 archives or mix these builds into Android Wine/Proton component catalogs. Installed older builds remain listed through their local metadata.

Settings → Stores → Linux Client → Linux Proton builds exposes download, progress, cancellation, retry and removal using dialog pane navigation. Installation requires the Linux runtime. Downloads continue with the existing keep-alive service when the dialog closes. Each archive is size/hash checked, unpacked into a private staging directory, checked for contained paths/links and ARM64 Wine, and renamed into `root/.local/share/Steam/compatibilitytools.d`. Failed/cancelled work removes its staging files. The original archives remain hosted by GE and CachyOS.

The shipped `winnative-steam-compat` registers managed extra builds at session startup, saves the original tool manifest, uses WinNative's existing launch wrapper and retains the upstream compatibility tool name. Default mapping protects those names, including CachyOS's `proton-` prefix. Restart the Linux client after installation, then select a build per game under Steam → Properties → Compatibility. The rootfs update already preserves `root`, including all these installed tools.

This does not redistribute or rebuild Proton. Runtime registration is tested separately from game compatibility, which still requires an ARM64 device and a real Steam session.

## Verification

The PUBG debug app and instrumentation APK build successfully. AVD tests installed and removed the actual GE-Proton 11-7 ARM64 and CachyOS 11.0 (20260703) ARM64 archives, checked executable permissions and metadata, and rejected traversal and x86 packages without leaving partial installations. A larger 16 GB AVD was used after the initial 6 GB AVD ran out of space during GE extraction; cleanup removed the failed partial tree.

The live catalog was checked in the app against both upstream repositories. All four new strings exist in all 23 locales, and the dialog uses `DialogPaneNav` with the existing navigable popup actions. Large removals run off the UI thread, rename to an owned hidden directory first, and clean up interrupted removals on refresh.

The Python registration test verifies that both tool manifests lose the unavailable container dependency, originals are preserved, registration is idempotent, and CachyOS per-game selection survives default remapping. Both native Wine executables ran `--version` under QEMU with the original Linux rootfs and reported Wine 11.0 (Staging/CachyOS).
