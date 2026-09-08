package com.winlator.cmod.runtime.system;

import android.content.Context;
import java.util.Locale;

public abstract class GPUInformation {

  public static boolean isAdrenoGPU(Context context) {
    return getRenderer(null, context).toLowerCase(Locale.ROOT).contains("adreno");
  }

  public static boolean isDriverSupported(String driverName, Context context) {
    if (!isAdrenoGPU(context) && !driverName.equals("System")) return false;

    String renderer = getRenderer(driverName, context);

    return !renderer.toLowerCase(Locale.ROOT).contains("unknown");
  }

  public static native String getVulkanVersion(String driverName, Context context);

  public static native int getVendorID(String driverName, Context context);

  public static native String getRenderer(String driverName, Context context);

  public static native String[] enumerateExtensions(String driverName, Context context);

  static {
    System.loadLibrary("winlator");
  }
}
