# Vendored Maven artifacts

A small in-repo **Maven repository** for third-party artifacts we do not want to depend on an
external server for. It is wired up in `settings.gradle` with an `exclusiveContent` block, so the
groups below are *only* ever resolved from here and never looked up remotely:

```groovy
exclusiveContent {
    forRepository {
        maven { url = uri("${rootDir}/vendor/maven") }
    }
    filter { includeGroup 'org.libsdl.android' }
}
```

Do **not** edit any file in this tree by hand — the checksums must match the bytes.

## `org.libsdl.android:SDL3` — `3.4.16-winnative.1`

The Steam Controller backend uses this arm64 Android build. It has a separate
`org.winnative.steam` Java/JNI package and `libSDL3steam.so` so it cannot collide
with the older SDL integration shipped by ARMSX2.

`tools/sdl/steam-bluetooth.patch` serializes Bluetooth callbacks with shutdown,
ignores callbacks from old connections, disconnects failed or timed-out GATT
operations with bounded reconnection attempts, waits for the newer controller's
MTU negotiation before enabling notifications, and preserves the final byte of
output reports. Both the Java classes
and native library are built from SDL's `release-3.4.16` source archive. SDL's zlib
license is retained in the AAR.

Rebuild using JDK 21, Python 3.12+, CMake, Ninja, Android SDK 35 and NDK 27.3.13750724:

```sh
python3 tools/sdl/build_android_transport.py --sdk "$ANDROID_HOME"
```

The script verifies the source archive and original AAR checksums, applies the
patch, relocates Java and JNI names, builds the arm64 native library with 16 KB
page alignment, and packages the artifact. The generated SHA-256 is stored beside
it. The app links it through the existing SDL3 prefab target.

The original `3.4.16/` artifact is retained as the reproducible packaging input.
It is byte-identical to the AAR in SDL's
[official Android release](https://github.com/libsdl-org/SDL/releases/tag/release-3.4.16):
SHA-256 `03710fc7b49cc070551446841a843840fabf2aaaaa3043cd5739292a55e4e61c`.
The app depends only on `3.4.16-winnative.1`.

After changing the patch, rebuild the AAR, run the controller and Bluetooth tests,
and build the APK to check DEX and native library packaging. Do not edit AAR bytes
or checksum files manually.
