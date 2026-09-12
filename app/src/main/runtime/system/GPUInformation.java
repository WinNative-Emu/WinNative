package com.winlator.cmod.runtime.system;

import android.content.Context;
import java.util.Locale;

public abstract class GPUInformation {
  private static final Object PROBE_LOCK = new Object();

  public static boolean isAdrenoGPU(Context context) {
    return getRenderer(null, context).toLowerCase(Locale.ROOT).contains("adreno");
  }

  public static boolean isDriverSupported(String driverName, Context context) {
    if (!isAdrenoGPU(context) && !driverName.equals("System")) return false;

    String renderer = getRenderer(driverName, context);

    return !renderer.toLowerCase(Locale.ROOT).contains("unknown");
  }

  public static String getVulkanVersion(String driverName, Context context) {
    synchronized (PROBE_LOCK) {
      return getVulkanVersionNative(driverName, context);
    }
  }

  public static int getVendorID(String driverName, Context context) {
    synchronized (PROBE_LOCK) {
      return getVendorIDNative(driverName, context);
    }
  }

  public static String getRenderer(String driverName, Context context) {
    synchronized (PROBE_LOCK) {
      return getRendererNative(driverName, context);
    }
  }

  public static String[] enumerateExtensions(String driverName, Context context) {
    synchronized (PROBE_LOCK) {
      return enumerateExtensionsNative(driverName, context);
    }
  }

  private static native String getVulkanVersionNative(String driverName, Context context);

  private static native int getVendorIDNative(String driverName, Context context);

  private static native String getRendererNative(String driverName, Context context);

  private static native String[] enumerateExtensionsNative(String driverName, Context context);

  static {
    System.loadLibrary("winlator");
  }
}
