package com.winlator.cmod.runtime.input.controls;

import java.nio.ByteBuffer;

public class GamepadState {
  public float thumbLX = 0;
  public float thumbLY = 0;
  public float thumbRX = 0;
  public float thumbRY = 0;
  public float triggerL = 0;
  public float triggerR = 0;
  public final boolean[] dpad = new boolean[4];
  public short buttons = 0;

  public static final int BUTTON_A = 0;
  public static final int BUTTON_B = 1;
  public static final int BUTTON_X = 2;
  public static final int BUTTON_Y = 3;
  public static final int BUTTON_L1 = 4;
  public static final int BUTTON_R1 = 5;
  public static final int BUTTON_SELECT = 6;
  public static final int BUTTON_START = 7;
  public static final int BUTTON_L3 = 8;
  public static final int BUTTON_R3 = 9;
  public static final int BUTTON_L2 = 10;
  public static final int BUTTON_R2 = 11;
  public static final int BUTTON_GUIDE = 12;

  public static final int BUTTON_DPAD_UP = 13;
  public static final int BUTTON_DPAD_DOWN = 14;
  public static final int BUTTON_DPAD_LEFT = 15;
  public static final int BUTTON_DPAD_RIGHT = 16;

  public byte getPovHat() {
    byte povHat = -1;
    if (dpad[0] && dpad[1]) povHat = 1;
    else if (dpad[1] && dpad[2]) povHat = 3;
    else if (dpad[2] && dpad[3]) povHat = 5;
    else if (dpad[3] && dpad[0]) povHat = 7;
    else if (dpad[0]) povHat = 0;
    else if (dpad[1]) povHat = 2;
    else if (dpad[2]) povHat = 4;
    else if (dpad[3]) povHat = 6;
    return povHat;
  }

  public void writeTo(ByteBuffer buffer) {
    buffer.putShort(buttons);
    buffer.put(getPovHat());
    buffer.putShort((short) (thumbLX * Short.MAX_VALUE));
    buffer.putShort((short) (thumbLY * Short.MAX_VALUE));
    buffer.putShort((short) (thumbRX * Short.MAX_VALUE));
    buffer.putShort((short) (thumbRY * Short.MAX_VALUE));
    buffer.put((byte) (triggerL * 255));
    buffer.put((byte) (triggerR * 255));
  }

  public void setPressed(int buttonIdx, boolean pressed) {
    int flag = 1 << buttonIdx;
    if (pressed) {
      buttons |= flag;
    } else buttons &= ~flag;
  }

  public boolean isPressed(int buttonIdx) {
    return (buttons & (1 << buttonIdx)) != 0;
  }

  public boolean isButtonPressed(int buttonCode) {
    if (buttonCode == BUTTON_DPAD_UP) return dpad[0];
    if (buttonCode == BUTTON_DPAD_RIGHT) return dpad[1];
    if (buttonCode == BUTTON_DPAD_DOWN) return dpad[2];
    if (buttonCode == BUTTON_DPAD_LEFT) return dpad[3];
    if (buttonCode == BUTTON_GUIDE) return false;

    return isPressed(buttonCode);
  }

  public byte getDPadX() {
    return (byte) (dpad[1] ? 1 : (dpad[3] ? -1 : 0));
  }

  public byte getDPadY() {
    return (byte) (dpad[0] ? -1 : (dpad[2] ? 1 : 0));
  }

  public void reset() {
    thumbLX = 0;
    thumbLY = 0;
    thumbRX = 0;
    thumbRY = 0;
    triggerL = 0;
    triggerR = 0;
    buttons = 0;
    java.util.Arrays.fill(dpad, false);
  }

  public void copy(GamepadState other) {
    this.thumbLX = other.thumbLX;
    this.thumbLY = other.thumbLY;
    this.thumbRX = other.thumbRX;
    this.thumbRY = other.thumbRY;
    this.triggerL = other.triggerL;
    this.triggerR = other.triggerR;
    this.buttons = other.buttons;
    System.arraycopy(other.dpad, 0, this.dpad, 0, 4);
  }
}
