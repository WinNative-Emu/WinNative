package com.winlator.cmod.shared.android;

import android.os.Build;
import com.winlator.cmod.BuildConfig;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which CPU architecture this process runs as, and a best-effort hint about the platform.
 *
 * <p>WinNative ships for arm64, where Windows (x86_64) software is translated by Box64 or FEXCore.
 * On an x86_64 Android (Android-x86/BlissOS, Windows Subsystem for Android, ChromeOS, the Android
 * Emulator) the guest already matches the CPU, so Wine runs natively and no translator is
 * involved. Code that has to choose between the two asks this class rather than reading {@link
 * Build#SUPPORTED_ABIS} directly, because the device's preferred ABI is not necessarily the ABI of
 * this process: an arm64-only APK installed on an x86_64 device still runs as arm64 (translated by
 * the platform), and only the ABI of the process matters for which of our native libraries loaded.
 */
public final class HostPlatform {
  public static final String ABI_ARM64 = "arm64-v8a";
  public static final String ABI_X86_64 = "x86_64";

  private static volatile String processAbi;

  private HostPlatform() {}

  /**
   * The ABI our native libraries were loaded for. Mirrors the package manager's choice: the first
   * ABI the device prefers that this APK actually contains ({@code BuildConfig.WN_ABIS}).
   */
  public static String processAbi() {
    String abi = processAbi;
    if (abi == null) {
      abi = pickProcessAbi(Build.SUPPORTED_ABIS, BuildConfig.WN_ABIS);
      processAbi = abi;
    }
    return abi;
  }

  /** True when this process is x86_64, so an x86_64 Wine runs with no CPU translation. */
  public static boolean isX86_64() {
    return ABI_X86_64.equals(processAbi());
  }

  public static boolean isArm64() {
    return ABI_ARM64.equals(processAbi());
  }

  /**
   * Best-effort hint that this is Windows Subsystem for Android. Use it to tailor guidance and
   * defaults only; never to gate functionality. Confirmed on a live WSA: model "Subsystem for
   * Android(TM)", manufacturer "Microsoft Corporation", device "windows_x86_64", ABI list
   * "x86_64,arm64-v8a,x86,armeabi-v7a,armeabi". Brand and product were not captured; {@link
   * #describe()} logs every value.
   */
  public static boolean isWindowsSubsystemForAndroid() {
    return looksLikeWsa(Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.PRODUCT);
  }

  /**
   * CPU name in Mesa's per-architecture Vulkan ICD manifests (Meson's {@code host_machine.cpu()}):
   * the wrapper driver installs {@code wrapper_icd.aarch64.json} or {@code wrapper_icd.x86_64.json}.
   */
  public static String vulkanIcdCpuName() {
    return icdCpuNameFor(processAbi());
  }

  /** One line for logs and bug reports. */
  public static String describe() {
    return "abi="
        + processAbi()
        + " deviceAbis="
        + String.join(",", Build.SUPPORTED_ABIS)
        + " apkAbis="
        + BuildConfig.WN_ABIS
        + " model="
        + Build.MODEL
        + " manufacturer="
        + Build.MANUFACTURER
        + " brand="
        + Build.BRAND
        + " device="
        + Build.DEVICE
        + " product="
        + Build.PRODUCT
        + " sdk="
        + Build.VERSION.SDK_INT
        + " wsaHint="
        + isWindowsSubsystemForAndroid();
  }

  // -- Pure decision logic, kept free of android.* reads so it can be unit tested on the JVM. --

  /**
   * The first of {@code deviceAbis} (in the device's order of preference) that {@code apkAbisCsv}
   * lists. Falls back to the device's first ABI when the APK's list is unknown or shares nothing
   * with the device, and to an empty string when the device reports no ABIs at all.
   */
  static String pickProcessAbi(String[] deviceAbis, String apkAbisCsv) {
    if (deviceAbis == null || deviceAbis.length == 0) return "";
    if (apkAbisCsv == null || apkAbisCsv.trim().isEmpty()) return deviceAbis[0];
    Set<String> apk = new HashSet<>();
    for (String abi : apkAbisCsv.split(",")) {
      String trimmed = abi.trim();
      if (!trimmed.isEmpty()) apk.add(trimmed);
    }
    for (String abi : deviceAbis) {
      if (apk.contains(abi)) return abi;
    }
    return deviceAbis[0];
  }

  static String icdCpuNameFor(String abi) {
    return ABI_X86_64.equals(abi) ? "x86_64" : "aarch64";
  }

  static boolean looksLikeWsa(
      String model, String manufacturer, String brand, String device, String product) {
    if (lower(model).contains("subsystem for android")) return true;
    if (lower(device).startsWith("windows") || lower(product).startsWith("windows")) return true;
    return lower(manufacturer).contains("microsoft") && lower(brand).equals("windows");
  }

  private static String lower(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }
}
