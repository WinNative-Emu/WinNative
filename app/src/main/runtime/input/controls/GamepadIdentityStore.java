package com.winlator.cmod.runtime.input.controls;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.InputDevice;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.runtime.display.winhandler.WinHandler;
import com.winlator.cmod.shared.io.FileUtils;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class GamepadIdentityStore {
  public static final int DEFAULT_VENDOR_ID = 0x045E; // Microsoft
  public static final int DEFAULT_PRODUCT_ID = 0x028E; // Xbox 360 Controller
  public static final int SONY_VENDOR_ID = 0x054C; // Sony
  public static final int DS4_PRODUCT_ID = 0x05C4; // Dualshock 4

  private static final String TAG = "GamepadIdentityStore";
  private static final int MAX_SLOTS = WinHandler.MAX_CONTROLLERS;
  private static final int EVENT_MINOR_BASE = 64;
  private static final int MAX_NAME_LENGTH = 80;

  private static File udevDataDir;
  private static Map<Integer, ExternalController> controllers;
  private static Map<Integer, Integer> deviceToSlot;

  public static String formatId(int id) {
    return String.format(Locale.US, "%04x", id & 0xFFFF);
  }

  public static String getDefaultName(int slot) {
    return "Xbox 360 Controller (" + slot + ")";
  }

  public static File getUdevDataFile(File udevDir, int slot) {
    return new File(udevDir, "c13:" + (EVENT_MINOR_BASE + slot));
  }

  public static void configurePreAssignedControllers(Map<Integer,ExternalController> whandlerControllers, Map<Integer, Integer> whandlerDeviceToSlot) {
    controllers = whandlerControllers;
    deviceToSlot = whandlerDeviceToSlot;
  }

  public static synchronized void configureSlot(int slot, File udevDir) {
    udevDataDir = udevDir;
    if (udevDataDir != null) {
      String vendor = formatId(DEFAULT_VENDOR_ID);
      String product = formatId(DEFAULT_PRODUCT_ID);
      String name = getDefaultName(slot);

      // Find a device id bound to this slot if any
      for (Map.Entry<Integer, Integer> entry : deviceToSlot.entrySet()) {
        if (entry.getValue() == slot) {
          int deviceId = entry.getKey();
          ExternalController controller = controllers.getOrDefault(deviceId, null);
          if (controller != null && controller.isUseRealIdentity())
          {
            InputDevice device = InputDevice.getDevice(controller.getDeviceId());
            name = sanitizeName(device.getName());
            vendor = formatId(device.getVendorId());
            product = formatId(device.getProductId());

            // AYN Thor specific hack, for some reason the Thor rewrites all vendor and product ids
            // to 2020:0111, so use the device name to workaround that here
            if (vendor.equals("2020") && product.equals("0111")) {
              if (name.contains("XBOX") || name.contains("Xbox")) { // Matches Xbox One controllers and XBOX 360
                vendor = formatId(DEFAULT_VENDOR_ID);
                product = formatId(DEFAULT_PRODUCT_ID);
              } else if (name.contains("PLAYSTATION") || name.contains("DualShock")) { // Matches PLAYSTATION(R)3 Controller and DS4/5
                vendor = formatId(SONY_VENDOR_ID);
                product = formatId(DS4_PRODUCT_ID);
              }
            }

            Log.d(TAG, "Published gamepad identity for slot "
                    + slot + ": " + name + " (" + vendor + ":" + product + ")");
          }
        }
      }

      String symlink = "input/by-id/usb-WinNative_Generic_HID_Gamepad_" + slot + "-event-joystick";
      String content =
              "I:" + slot + "\n" +
                      "N:input/event" + slot + "\n" +
                      "S:" + symlink + "\n" +
                      "E:DEVNAME=/dev/input/event" + slot + "\n" +
                      "E:ID_INPUT=1\n" +
                      "E:ID_INPUT_JOYSTICK=1\n" +
                      "E:ID_BUS=usb\n" +
                      "E:ID_VENDOR=WinNative\n" +
                      "E:ID_VENDOR_ID=" + vendor + "\n" +
                      "E:ID_MODEL=Generic_HID_Gamepad_" + slot + "\n" +
                      "E:ID_MODEL_ID=" + product + "\n" +
                      "E:ID_SERIAL=WinNative_Generic_HID_Gamepad_" + slot + "\n" +
                      "E:NAME=\"" + name + "\"\n" +
                      "E:TAGS=:uaccess:\n";

      File udevData = GamepadIdentityStore.getUdevDataFile(udevDataDir, slot);
      FileUtils.writeString(udevData, content);
    }
  }

  public static synchronized void reset() {
    if (udevDataDir != null) {
      for (int slot = 0; slot < MAX_SLOTS; slot++) {
        clearSlotLocked(slot);
      }
    }
    udevDataDir = null;
    controllers = null;
    deviceToSlot = null;
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
      Log.d(TAG, "Published gamepad identity for slot "
              + slot + ": " + name + " (" + vendor + ":" + product + ")");
    }
  }

  private static void clearSlotLocked(int slot) {
    writeIdentityLocked(
        slot, formatId(DEFAULT_VENDOR_ID), formatId(DEFAULT_PRODUCT_ID), getDefaultName(slot));
  }

  private static boolean writeIdentityLocked(int slot, String vendor, String product, String name) {
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
