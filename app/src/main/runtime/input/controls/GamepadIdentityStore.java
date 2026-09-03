package com.winlator.cmod.runtime.input.controls;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.InputDevice;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.runtime.display.winhandler.WinHandler;
import com.winlator.cmod.shared.io.FileUtils;
import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class GamepadIdentityStore {
  public static final int DEFAULT_VENDOR_ID = 0x045E; // Microsoft
  public static final int DEFAULT_PRODUCT_ID = 0x028E; // Xbox 360 Controller

  private static final String TAG = "GamepadIdentityStore";
  private static final int MAX_SLOTS = WinHandler.MAX_CONTROLLERS;
  private static final int EVENT_MINOR_BASE = 64;
  private static final int MAX_NAME_LENGTH = 80;

  private static File udevDataDir;

  public static String formatId(int id) {
    return String.format(Locale.US, "%04x", id & 0xFFFF);
  }

  public static String getDefaultName(int slot) {
    return "Xbox 360 Controller (" + slot + ")";
  }

  public static File getUdevDataFile(File udevDir, int slot) {
    return new File(udevDir, "c13:" + (EVENT_MINOR_BASE + slot));
  }

  public static synchronized void configure(File udevDir) {
    udevDataDir = udevDir;
  }

  public static synchronized void reset() {
    if (udevDataDir != null) {
      for (int slot = 0; slot < MAX_SLOTS; slot++) {
        clearSlotLocked(slot);
      }
    }
    udevDataDir = null;
  }

  public static synchronized void refreshSlot(int slot, ExternalController slotDevice) {
    if (udevDataDir == null) {
      return;
    }
    refreshSlotLocked(slot, slotDevice);
  }

  private static void refreshSlotLocked(int slot, ExternalController controller) {
    // No pad in the slot (or the virtual on-screen one), or a pad the user did not opt in:
    // either way the slot keeps the Xbox 360 identity every game already recognizes.
    if (controller == null || !controller.isUseRealIdentity()) {
      clearSlotLocked(slot);
      return;
    }

    InputDevice device = InputDevice.getDevice(controller.getDeviceId());
    String name = sanitizeName(device.getName());
    String vendor = formatId(device.getVendorId());
    String product = formatId(device.getProductId());
    if (writeIdentityLocked(slot, vendor, product, name)) {
      Log.d(
          TAG,
          "Published gamepad identity for slot "
              + slot
              + ": "
              + name
              + " ("
              + vendor
              + ":"
              + product
              + ")");
    }
  }

  private static void clearSlotLocked(int slot) {
    writeIdentityLocked(
        slot, formatId(DEFAULT_VENDOR_ID), formatId(DEFAULT_PRODUCT_ID), getDefaultName(slot));
  }

  /**
   * Rewrites just the identity fields of the slot's udev entry, leaving the rest of it (discovery
   * tags, device node, symlink) untouched.
   */
  private static boolean writeIdentityLocked(
      int slot, String vendor, String product, String name) {
    if (udevDataDir == null) {
      return false;
    }
    File udevData = getUdevDataFile(udevDataDir, slot);
    if (!udevData.isFile()) {
      return false;
    }

    StringBuilder content = new StringBuilder();
    for (String line : FileUtils.readLines(udevData)) {
      if (line.startsWith("E:ID_VENDOR_ID=")) {
        line = "E:ID_VENDOR_ID=" + vendor;
      } else if (line.startsWith("E:ID_MODEL_ID=")) {
        line = "E:ID_MODEL_ID=" + product;
      } else if (line.startsWith("E:NAME=")) {
        line = "E:NAME=\"" + name + "\"";
      }
      content.append(line).append('\n');
    }
    if (!FileUtils.writeString(udevData, content.toString())) {
      Log.w(TAG, "Failed to publish gamepad identity for slot " + slot);
      return false;
    }
    return true;
  }

  // The name lands in udev's quoted, newline-delimited NAME field, so strip anything that would
  // break that parser on either side.
  private static String sanitizeName(String name) {
    if (name == null) {
      return "";
    }
    String sanitized = name.replaceAll("[\\r\\n\"]", " ").trim();
    return sanitized.length() > MAX_NAME_LENGTH
        ? sanitized.substring(0, MAX_NAME_LENGTH).trim()
        : sanitized;
  }
}
