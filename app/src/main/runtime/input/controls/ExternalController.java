package com.winlator.cmod.runtime.input.controls;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import androidx.annotation.Nullable;
import androidx.core.view.InputDeviceCompat;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.runtime.display.XServerDisplayActivity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ExternalController {
  public static final byte IDX_BUTTON_A = 0;
  public static final byte IDX_BUTTON_B = 1;
  public static final byte IDX_BUTTON_L1 = 4;
  public static final byte IDX_BUTTON_L2 = 10;
  public static final byte IDX_BUTTON_L3 = 8;
  public static final byte IDX_BUTTON_R1 = 5;
  public static final byte IDX_BUTTON_R2 = 11;
  public static final byte IDX_BUTTON_R3 = 9;
  public static final byte IDX_BUTTON_SELECT = 6;
  public static final byte IDX_BUTTON_START = 7;
  public static final byte IDX_BUTTON_X = 2;
  public static final byte IDX_BUTTON_Y = 3;
  public static final byte TRIGGER_IS_BUTTON = 0;
  public static final byte TRIGGER_IS_AXIS = 1;
  public static final byte TRIGGER_IS_BOTH = 2;
  // Sentinel: user never picked a trigger type -> auto-detect.
  private static final int TRIGGER_TYPE_UNSET = -1;
  public static final HashMap<Byte, Byte> buttonMappings = new HashMap<>();
  private XServerDisplayActivity activity;
  private Context context;
  private String id;
  private String name;
  private int deviceId = -1;
  private byte triggerType = TRIGGER_IS_AXIS;
  // Device exposes an analog trigger axis; gates the analog path so a stale "as button"
  // pref can't kill an analog pad's triggers. Default true = historical behavior.
  private boolean hasAnalogTriggerAxis = true;
  private final ArrayList<ExternalControllerBinding> controllerBindings = new ArrayList<>();
  public final GamepadState state = new GamepadState();
  public final GamepadState remappedState = new GamepadState();
  private boolean triggerLPressedViaButton = false;
  private boolean triggerRPressedViaButton = false;

  // Configurable deadzone, sensitivity, and inversion settings
  private float deadzoneLeft = 0.1f;
  private float deadzoneRight = 0.1f;
  private float sensitivityLeft = 1.0f;
  private float sensitivityRight = 1.0f;
  private boolean invertLeftX = false;
  private boolean invertLeftY = false;
  private boolean invertRightX = false;
  private boolean invertRightY = false;
  private boolean useSquareDeadzoneLeft = false;

  private SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener =
      new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
          if (key == null) return;
          switch (key) {
            case PreferenceKeys.DEADZONE_LEFT:
              deadzoneLeft = getFloatPref(sharedPreferences, PreferenceKeys.DEADZONE_LEFT, 0.1f);
              break;
            case PreferenceKeys.DEADZONE_RIGHT:
              deadzoneRight = getFloatPref(sharedPreferences, PreferenceKeys.DEADZONE_RIGHT, 0.1f);
              break;
            case PreferenceKeys.SENSITIVITY_LEFT:
              sensitivityLeft =
                  getFloatPref(sharedPreferences, PreferenceKeys.SENSITIVITY_LEFT, 1.0f);
              break;
            case PreferenceKeys.SENSITIVITY_RIGHT:
              sensitivityRight =
                  getFloatPref(sharedPreferences, PreferenceKeys.SENSITIVITY_RIGHT, 1.0f);
              break;
            case PreferenceKeys.INVERT_LEFT_X:
              invertLeftX = sharedPreferences.getBoolean(PreferenceKeys.INVERT_LEFT_X, false);
              break;
            case PreferenceKeys.INVERT_LEFT_Y:
              invertLeftY = sharedPreferences.getBoolean(PreferenceKeys.INVERT_LEFT_Y, false);
              break;
            case PreferenceKeys.INVERT_RIGHT_X:
              invertRightX = sharedPreferences.getBoolean(PreferenceKeys.INVERT_RIGHT_X, false);
              break;
            case PreferenceKeys.INVERT_RIGHT_Y:
              invertRightY = sharedPreferences.getBoolean(PreferenceKeys.INVERT_RIGHT_Y, false);
              break;
            case PreferenceKeys.SQUARE_DEADZONE_LEFT:
              useSquareDeadzoneLeft =
                  sharedPreferences.getBoolean(PreferenceKeys.SQUARE_DEADZONE_LEFT, false);
              break;
            case PreferenceKeys.TRIGGER_TYPE:
              {
                int pref =
                    sharedPreferences.getInt(PreferenceKeys.TRIGGER_TYPE, TRIGGER_TYPE_UNSET);
                triggerType = pref == TRIGGER_TYPE_UNSET ? autoTriggerType() : (byte) pref;
              }
              break;
          }
        }
      };

  private static float getFloatPref(SharedPreferences prefs, String key, float defaultValue) {
    try {
      return prefs.getFloat(key, defaultValue);
    } catch (ClassCastException e) {
      // Migration: old int-based value stored as percentage
      try {
        int intVal = prefs.getInt(key, -1);
        if (intVal != -1) {
          float floatVal = intVal / 100.0f;
          prefs.edit().putFloat(key, floatVal).apply();
          return floatVal;
        }
      } catch (ClassCastException e2) {
        // ignore
      }
      return defaultValue;
    }
  }

  private void loadPreferences() {
    if (context == null) return;

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    this.deadzoneLeft = getFloatPref(prefs, PreferenceKeys.DEADZONE_LEFT, 0.1f);
    this.deadzoneRight = getFloatPref(prefs, PreferenceKeys.DEADZONE_RIGHT, 0.1f);
    this.sensitivityLeft = getFloatPref(prefs, PreferenceKeys.SENSITIVITY_LEFT, 1.0f);
    this.sensitivityRight = getFloatPref(prefs, PreferenceKeys.SENSITIVITY_RIGHT, 1.0f);
    this.invertLeftX = prefs.getBoolean(PreferenceKeys.INVERT_LEFT_X, false);
    this.invertLeftY = prefs.getBoolean(PreferenceKeys.INVERT_LEFT_Y, false);
    this.invertRightX = prefs.getBoolean(PreferenceKeys.INVERT_RIGHT_X, false);
    this.invertRightY = prefs.getBoolean(PreferenceKeys.INVERT_RIGHT_Y, false);
    this.useSquareDeadzoneLeft = prefs.getBoolean(PreferenceKeys.SQUARE_DEADZONE_LEFT, false);
    // Honor an explicit choice; else auto-detect. Runs per instance via setContext.
    this.hasAnalogTriggerAxis = deviceHasAnalogTriggerAxis();
    int triggerTypePref = prefs.getInt(PreferenceKeys.TRIGGER_TYPE, TRIGGER_TYPE_UNSET);
    this.triggerType =
        triggerTypePref == TRIGGER_TYPE_UNSET ? autoTriggerType() : (byte) triggerTypePref;
  }

  /** Default trigger mode (no explicit choice): derived from capability. */
  private byte autoTriggerType() {
    return hasAnalogTriggerAxis ? TRIGGER_IS_AXIS : TRIGGER_IS_BUTTON;
  }

  /** True if the device reports an analog trigger axis; true (historical default) when uninspectable. */
  private boolean deviceHasAnalogTriggerAxis() {
    int id = getDeviceId();
    InputDevice device = id >= 0 ? InputDevice.getDevice(id) : null;
    if (device == null) {
      return true;
    }
    return device.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null
        || device.getMotionRange(MotionEvent.AXIS_RTRIGGER) != null
        || device.getMotionRange(MotionEvent.AXIS_GAS) != null
        || device.getMotionRange(MotionEvent.AXIS_BRAKE) != null;
  }

  public String getName() {
    return this.name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getId() {
    return this.id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public byte getTriggerType() {
    return triggerType;
  }

  public void setTriggerType(byte mode) {
    this.triggerType = mode;
  }

  public void setContext(Context context) {
    this.context = context;
    if (context != null) {
      loadPreferences();
      SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
      prefs.registerOnSharedPreferenceChangeListener(prefChangeListener);
    }
  }

  public void unregisterListener() {
    if (context != null) {
      SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
      prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener);
    }
  }

  public int getDeviceId() {
    if (this.deviceId == -1) {
      int[] deviceIds = InputDevice.getDeviceIds();
      int length = deviceIds.length;
      int i = 0;
      while (true) {
        if (i < length) {
          int deviceId = deviceIds[i];
          InputDevice device = InputDevice.getDevice(deviceId);
          if (device == null || !device.getDescriptor().equals(this.id)) {
            i++;
          } else {
            this.deviceId = deviceId;
            break;
          }
        } else {
          break;
        }
      }
    }
    return this.deviceId;
  }

  public boolean isConnected() {
    for (int deviceId : InputDevice.getDeviceIds()) {
      InputDevice device = InputDevice.getDevice(deviceId);
      if (device != null && device.getDescriptor().equals(this.id)) {
        return true;
      }
    }
    return false;
  }

  public ExternalControllerBinding getControllerBinding(int keyCode) {
    Iterator<ExternalControllerBinding> it = this.controllerBindings.iterator();
    while (it.hasNext()) {
      ExternalControllerBinding controllerBinding = it.next();
      if (controllerBinding.getKeyCodeForAxis() == keyCode) {
        return controllerBinding;
      }
    }
    return null;
  }

  public ExternalControllerBinding getControllerBindingAt(int index) {
    return this.controllerBindings.get(index);
  }

  public void addControllerBinding(ExternalControllerBinding controllerBinding) {
    if (getControllerBinding(controllerBinding.getKeyCodeForAxis()) == null) {
      this.controllerBindings.add(controllerBinding);
    }
  }

  public int getPosition(ExternalControllerBinding controllerBinding) {
    return this.controllerBindings.indexOf(controllerBinding);
  }

  public void removeControllerBinding(ExternalControllerBinding controllerBinding) {
    this.controllerBindings.remove(controllerBinding);
  }

  public void setButtonMapping(byte originalButton, byte mappedButton) {
    buttonMappings.put(Byte.valueOf(originalButton), Byte.valueOf(mappedButton));
  }

  public byte getMappedButton(byte originalButton) {
    byte mappedButton =
        buttonMappings
            .getOrDefault(Byte.valueOf(originalButton), Byte.valueOf(originalButton))
            .byteValue();
    return mappedButton;
  }

  public int getControllerBindingCount() {
    return this.controllerBindings.size();
  }

  public JSONObject toJSONObject() throws JSONException {
    try {
      if (this.controllerBindings.isEmpty()) {
        return null;
      }
      JSONObject controllerJSONObject = new JSONObject();
      controllerJSONObject.put("id", this.id);
      controllerJSONObject.put("name", this.name);
      JSONArray controllerBindingsJSONArray = new JSONArray();
      Iterator<ExternalControllerBinding> it = this.controllerBindings.iterator();
      while (it.hasNext()) {
        ExternalControllerBinding controllerBinding = it.next();
        controllerBindingsJSONArray.put(controllerBinding.toJSONObject());
      }
      controllerJSONObject.put("controllerBindings", controllerBindingsJSONArray);
      return controllerJSONObject;
    } catch (JSONException e) {
      return null;
    }
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (obj instanceof ExternalController) {
      ExternalController other = (ExternalController) obj;
      if (this.id == null || other.id == null) {
        return this.id == other.id;
      }
      return other.id.equals(this.id);
    }
    return super.equals(obj);
  }

  private void processJoystickInput(MotionEvent event, int historyPos) {
    boolean z = false;
    this.state.thumbLX = getCenteredAxis(event, 0, historyPos);
    this.state.thumbLY = getCenteredAxis(event, 1, historyPos);
    this.state.thumbRX = getCenteredAxis(event, 11, historyPos);
    this.state.thumbRY = getCenteredAxis(event, 14, historyPos);
    if (historyPos == -1) {
      float axisX = getCenteredAxis(event, 15, historyPos);
      float axisY = getCenteredAxis(event, 16, historyPos);
      this.state.dpad[0] = axisY == -1.0f && Math.abs(this.state.thumbLY) < 0.15f;
      this.state.dpad[1] = axisX == 1.0f && Math.abs(this.state.thumbLX) < 0.15f;
      this.state.dpad[2] = axisY == 1.0f && Math.abs(this.state.thumbLY) < 0.15f;
      boolean[] zArr = this.state.dpad;
      if (axisX == -1.0f && Math.abs(this.state.thumbLX) < 0.15f) {
        z = true;
      }
      zArr[3] = z;
    }
  }

  private void processTriggerButton(MotionEvent event) {
    // Read both possible trigger axes and use the larger value.
    // DualSense uses AXIS_BRAKE(23)/AXIS_GAS(22) while Xbox uses
    // AXIS_LTRIGGER(17)/AXIS_RTRIGGER(18).
    // Some controllers report tiny noise on the unused axis, so comparing == 0.0f is unreliable.
    float l = Math.max(event.getAxisValue(17), event.getAxisValue(23));
    float r = Math.max(event.getAxisValue(18), event.getAxisValue(22));
    this.state.triggerL = l;
    this.state.triggerR = r;
    this.state.setPressed(10, l > 0.9f);
    this.state.setPressed(11, r > 0.9f);
  }

  public boolean isXboxController() {
    InputDevice device = InputDevice.getDevice(getDeviceId());
    if (device == null) {
      return false;
    }
    int vendorId = device.getVendorId();
    return vendorId == 1118;
  }

  private void processXboxTriggerButton(MotionEvent event) {
    float l = Math.max(event.getAxisValue(17), event.getAxisValue(23));
    float r = Math.max(event.getAxisValue(18), event.getAxisValue(22));
    if (l > 0.0f) {
      this.state.triggerL = 1.0f;
      this.state.setPressed(10, true);
    } else {
      this.state.triggerL = 0.0f;
      this.state.setPressed(10, false);
    }
    if (r > 0.0f) {
      this.state.triggerR = 1.0f;
      this.state.setPressed(11, true);
    } else {
      this.state.triggerR = 0.0f;
      this.state.setPressed(11, false);
    }
  }

  public boolean updateStateFromMotionEvent(MotionEvent event) {
    if (isJoystickDevice(event)) {
      // Capability-gated, not mode-gated: button-only pads skip this (the 0-read would
      // clobber the digital key state); analog pads always run it.
      if (hasAnalogTriggerAxis) {
        processTriggerButton(event);
      }
      int historySize = event.getHistorySize();
      for (int i = 0; i < historySize; i++) {
        processJoystickInput(event, i);
      }
      processJoystickInput(event, -1);
      return true;
    }
    return false;
  }

  public boolean updateStateFromKeyEvent(KeyEvent event) {
    boolean z = false;
    boolean pressed = event.getAction() == 0;
    int keyCode = event.getKeyCode();
    int buttonIdx = getButtonIdxByKeyCode(keyCode);
    if (buttonIdx != -1) {
      if (buttonIdx == 10 || buttonIdx == 11) {
        if (triggerType == TRIGGER_IS_AXIS) {
          // Axis mode: analog path owns the value; ignore the digital key.
          return false;
        }
        // Non-axis: set the button bit; synthesize analog only when there's no axis to own it.
        this.state.setPressed(buttonIdx, pressed);
        if (!hasAnalogTriggerAxis) {
          if (buttonIdx == 10) {
            this.state.triggerL = pressed ? 1.0f : 0.0f;
          } else {
            this.state.triggerR = pressed ? 1.0f : 0.0f;
          }
        }
        return true;
      }
      this.state.setPressed(buttonIdx, pressed);
      return true;
    }
    switch (keyCode) {
      case 19:
        this.state.dpad[0] = pressed && Math.abs(this.state.thumbLY) < 0.15f;
        break;
      case 20:
        boolean[] zArr = this.state.dpad;
        if (pressed && Math.abs(this.state.thumbLY) < 0.15f) {
          z = true;
        }
        zArr[2] = z;
        break;
      case 21:
        boolean[] zArr2 = this.state.dpad;
        if (pressed && Math.abs(this.state.thumbLX) < 0.15f) {
          z = true;
        }
        zArr2[3] = z;
        break;
      case 22:
        boolean[] zArr3 = this.state.dpad;
        if (pressed && Math.abs(this.state.thumbLX) < 0.15f) {
          z = true;
        }
        zArr3[1] = z;
        break;
    }
    return true;
  }

  public static ArrayList<ExternalController> getControllers() {
    int[] deviceIds = InputDevice.getDeviceIds();
    ArrayList<ExternalController> controllers = new ArrayList<>();
    for (int i = deviceIds.length - 1; i >= 0; i--) {
      InputDevice device = InputDevice.getDevice(deviceIds[i]);
      if (isGameController(device)) {
        ExternalController controller = new ExternalController();
        controller.setId(device.getDescriptor());
        controller.setName(device.getName());
        controllers.add(controller);
      }
    }
    return controllers;
  }

  public static ExternalController getController(String id) {
    Iterator<ExternalController> it = getControllers().iterator();
    while (it.hasNext()) {
      ExternalController controller = it.next();
      if (controller.getId().equals(id)) {
        return controller;
      }
    }
    return null;
  }

  public static ExternalController getController(int deviceId) {
    int[] deviceIds = InputDevice.getDeviceIds();
    for (int i = deviceIds.length - 1; i >= 0; i--) {
      if (deviceIds[i] == deviceId || deviceId == 0) {
        InputDevice device = InputDevice.getDevice(deviceIds[i]);
        if (isGameController(device)) {
          ExternalController controller = new ExternalController();
          controller.setId(device.getDescriptor());
          controller.setName(device.getName());
          controller.deviceId = deviceIds[i];
          return controller;
        }
      }
    }
    return null;
  }

  public static boolean isGameController(InputDevice device) {
    if (device == null) {
      return false;
    }
    String name = device.getName();
    if (name != null) {
      String lowerName = name.toLowerCase(Locale.ROOT);
      if (lowerName.contains("uinput-fpc") || lowerName.contains("goodix_fp") || lowerName.contains("uinput-")) {
        return false;
      }
    }
    int sources = device.getSources();
    if (device.isVirtual()) {
      return false;
    }
    return (sources & InputDeviceCompat.SOURCE_GAMEPAD) == 1025
        || ((sources & InputDeviceCompat.SOURCE_JOYSTICK) == 16777232 && (sources & 8194) == 0);
  }

  public static String getPhysicalDeviceIdentifier(InputDevice device) {
    if (device == null) return "";
    String descriptor = device.getDescriptor();
    if (descriptor != null && !descriptor.isEmpty()) return descriptor;

    return String.format(
        Locale.US, "%s:%d:%d", device.getName(), device.getVendorId(), device.getProductId());
  }

  public float getCenteredAxis(MotionEvent event, int axis, int historyPos) {
    if (axis == 15 || axis == 16) {
      float value = event.getAxisValue(axis);
      if (Math.abs(value) == 1.0f) {
        return value;
      }
      return 0.0f;
    }
    InputDevice device = event.getDevice();
    InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
    if (range == null) {
      return 0.0f;
    }
    float flat = range.getFlat();
    float value2 = historyPos < 0 ? event.getAxisValue(axis) : event.getHistoricalAxisValue(axis, historyPos);
    if (Math.abs(value2) <= flat) {
      return 0.0f;
    }
    if ((axis == 0 || axis == 1 || axis == 11 || axis == 14) && Math.abs(value2) >= 0.15f) {
      return value2;
    }
    return 0.0f;
  }

  public static boolean isJoystickDevice(MotionEvent event) {
    return (event.getSource() & InputDeviceCompat.SOURCE_JOYSTICK) == 16777232
        && event.getAction() == 2;
  }

  public static int getButtonIdxByKeyCode(int keyCode) {
    switch (keyCode) {
      case 96:
        return 0;
      case 97:
        return 1;
      case 99:
        return 2;
      case 100:
        return 3;
      case 102:
        return 4;
      case 103:
        return 5;
      case 104:
        return 10;
      case 105:
        return 11;
      case 106:
        return 8;
      case 107:
        return 9;
      case 108:
        return 7;
      case 109:
        return 6;
      case 19:
        return 13;
      case 20:
        return 14;
      case 21:
        return 15;
      case 22:
        return 16;
      default:
        return -1;
    }
  }

  public static int getButtonIdxByName(String name) {
    switch (name) {
      case "A":
        return IDX_BUTTON_A;
      case "B":
        return IDX_BUTTON_B;
      case "X":
        return IDX_BUTTON_X;
      case "Y":
        return IDX_BUTTON_Y;
      case "L1":
        return IDX_BUTTON_L1;
      case "R1":
        return IDX_BUTTON_R1;
      case "SELECT":
        return IDX_BUTTON_SELECT;
      case "START":
        return IDX_BUTTON_START;
      case "L3":
        return IDX_BUTTON_L3;
      case "R3":
        return IDX_BUTTON_R3;
      case "L2":
        return IDX_BUTTON_L2;
      case "R2":
        return IDX_BUTTON_R2;
      default:
        return -1;
    }
  }
}
