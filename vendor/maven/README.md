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

## `org.libsdl.android:SDL3` — `3.4.16`

The official SDL 3.4.16 Android release AAR, used for the optional **Steam Controller** support.
SDL's HIDAPI Steam drivers are what read the 2015 and 2026 Steam Controllers over Bluetooth LE and
USB; see `SteamControllerBackend` and `app/src/main/cpp/steamctrl/steam_controller_bridge.cpp`.
SDL publishes this AAR only as a GitHub release asset, not to a Maven repository, so it is
vendored here.

- `SDL3-3.4.16.aar` is byte-identical to the AAR inside the upstream release asset
  `SDL3-devel-3.4.16-android.zip` from
  <https://github.com/libsdl-org/SDL/releases/tag/release-3.4.16>.
  AAR sha256 `03710fc7b49cc070551446841a843840fabf2aaaaa3043cd5739292a55e4e61c`
  (recorded in `SDL3-3.4.16.aar.sha256`).
- `SDL3-3.4.16.pom` is hand-written (packaging `aar`, no dependencies) — upstream ships no POM.
- The AAR carries SDL's Java classes (`org.libsdl.app.*`, including `HIDDeviceManager`, which is
  how SDL reaches Bluetooth/USB HID on Android) plus a prefab package. `libsteamctrl.so` links
  `SDL3::SDL3` through prefab (`buildFeatures.prefab` in `app/build.gradle`), and that link is also
  what packages `libSDL3.so` into the APK — the AAR has no `jni/` folder.
- Consumed as `implementation 'org.libsdl.android:SDL3:3.4.16@aar'` (`@aar` because there is no
  Gradle module metadata upstream).
- License: **zlib** (`META-INF/LICENSE.txt` inside the AAR). See `EMULATOR_CREDITS.md`.

The AAR's prefab metadata records NDK r28; WinNative builds with r27 and prefab accepts it. Only
`arm64-v8a` is extracted, since that is the single ABI in `abiFilters`.

### How to update

1. Download the new `SDL3-devel-<ver>-android.zip` from the SDL releases page and verify it
   against the release notes.
2. Copy the AAR out unmodified into `org/libsdl/android/SDL3/<ver>/`, alongside a matching
   `.pom` and an `.aar.sha256`.
3. Bump the version in `app/build.gradle`.
4. Delete the old version directory.
5. Build and run before merging — `libsteamctrl.so` links against it directly.
