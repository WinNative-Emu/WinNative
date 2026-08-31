package com.winlator.cmod.runtime.input.controls;

import android.util.Log;
import android.view.InputDevice;
import com.winlator.cmod.shared.io.FileUtils;
import java.io.File;
import java.util.Locale;

/**
 * Publishes the real identity (name, vendor ID, product ID) of the physical controller bound to
 * each fake input slot, for the "Force External Gamepad Identity" option.
 *
 * <p>The slot's udev entry is the only place the identity lives: libudev-based enumeration in the
 * guest already reads it, and the native ioctl hooks parse the same file, so the two can never
 * disagree. Rewriting it as pads come and go is what makes hotplug work without relaunching.
 */
public final class GamepadIdentityStore {
  public static final int DEFAULT_VENDOR_ID = 0x045E; // Microsoft
  public static final int DEFAULT_PRODUCT_ID = 0x028E; // Xbox 360 Controller

  private static final String TAG = "GamepadIdentityStore";
  private static final int MAX_SLOTS = 4;
  private static final int EVENT_MINOR_BASE = 64;
  private static final int MAX_NAME_LENGTH = 80;

  private static File udevDataDir;

  private GamepadIdentityStore() {}

  public static String formatId(int id) {
    return String.format(Locale.US, "%04x", id & 0xFFFF);
  }

  public static String getDefaultName(int slot) {
    return "Xbox 360 Controller (" + slot + ")";
  }

  /** Disables publishing and restores every slot to the default Xbox 360 spoof. */
  public static synchronized void reset() {
    if (udevDataDir != null) {
      for (int slot = 0; slot < MAX_SLOTS; slot++) {
        clearSlotLocked(slot);
      }
    }
    udevDataDir = null;
  }

  /** Re-publishes the identity for one slot; call whenever its bound device may have changed. */
  public static synchronized void refreshSlot(int slot, InputDevice slotDevice) {
    if (udevDataDir == null) {
      return;
    }
    refreshSlotLocked(slot, slotDevice);
  }

  private static void refreshSlotLocked(int slot, InputDevice device) {
    if (device == null) {
      clearSlotLocked(slot);
      return;
    }

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
    File udevData = new File(udevDataDir, "c13:" + (EVENT_MINOR_BASE + slot));
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
