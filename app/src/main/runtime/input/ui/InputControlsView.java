package com.winlator.cmod.runtime.input.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.R;
import com.winlator.cmod.runtime.display.XServerDisplayActivity;
import com.winlator.cmod.runtime.display.winhandler.MouseEventFlags;
import com.winlator.cmod.runtime.display.winhandler.WinHandler;
import com.winlator.cmod.runtime.display.xserver.Pointer;
import com.winlator.cmod.runtime.display.xserver.XServer;
import com.winlator.cmod.runtime.input.controls.AccentTheme;
import com.winlator.cmod.runtime.input.controls.Binding;
import com.winlator.cmod.runtime.input.controls.ControlElement;
import com.winlator.cmod.runtime.input.controls.ControlsProfile;
import com.winlator.cmod.runtime.input.controls.ExternalController;
import com.winlator.cmod.runtime.input.controls.ExternalControllerBinding;
import com.winlator.cmod.runtime.input.controls.GamepadState;
import com.winlator.cmod.runtime.input.controls.InputControlsManager;
import com.winlator.cmod.runtime.input.controls.VisualStyle;
import com.winlator.cmod.shared.math.Mathf;
import java.io.IOException;
import java.io.InputStream;
import java.util.Timer;
import java.util.TimerTask;

import com.winlator.cmod.runtime.display.renderer.VulkanRenderer;

public class InputControlsView extends View {
  public static final float DEFAULT_OVERLAY_OPACITY = 0.4f;
  public static final String EXTRA_ADAPTIVE_JOYSTICKS = "adaptiveJoysticks";
  private static final byte MOUSE_WHEEL_DELTA = 120;
  private boolean editMode = false;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();
  private final ColorFilter colorFilter =
      new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN);
  private final Point cursor = new Point();
  private boolean readyToDraw = false;
  private boolean moveCursor = false;
  private int snappingSize;
  private float offsetX;
  private float offsetY;
  private ControlElement selectedElement;
  private ControlsProfile profile;
  private float overlayOpacity = DEFAULT_OVERLAY_OPACITY;
  private TouchpadView touchpadView;
  private XServer xServer;
  private final Bitmap[] icons = new Bitmap[64];
  private Timer mouseMoveTimer;
  private volatile float mouseMoveOffsetX = 0f;
  private volatile float mouseMoveOffsetY = 0f;
  private boolean showTouchscreenControls = false;
  private boolean adaptiveJoysticks = false;
  private VisualStyle visualStyle = VisualStyle.SLATE;
  private AccentTheme accentTheme = AccentTheme.CYAN;
  private InputControlsManager inputControlsManager;

  private Handler timeoutHandler; // Reference to the activity's timeout handler
  private Runnable hideControlsRunnable; // Runnable to hide the controls

  private SharedPreferences preferences;
  private final SparseArray<ControlElement> activeTouchElements = new SparseArray<>();

  private ControlElement stickElement;

  private boolean focusOnStick = false; // A flag to determine if we are focusing on the stick

  private boolean batchingUpdates = false;

  private final Rect clipBounds = new Rect();

  public boolean isBatchingUpdates() {
    return batchingUpdates;
  }

  public boolean isFocusedOnStick() {
    return focusOnStick;
  }

  public void setFocusOnStick(boolean focus) {
    this.focusOnStick = focus;
    invalidate(); // Redraw the view with the new focus setting
  }

  public void setInputControlsManager(InputControlsManager inputControlsManager) {
    this.inputControlsManager = inputControlsManager;
  }

  @SuppressLint("ResourceType")
  public InputControlsView(Context context) {
    super(context);
    setClickable(true);
    setFocusable(true);
    setFocusableInTouchMode(true);
    requestFocus(); // Add this line to request focus
    setBackgroundColor(0x00000000);
    setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));
    setLayoutParams(
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    preferences = PreferenceManager.getDefaultSharedPreferences(this.getContext());
  }

  @SuppressLint("ResourceType")
  public InputControlsView(Context context, Handler timeoutHandler, Runnable hideControlsRunnable) {
    super(context);
    this.timeoutHandler = timeoutHandler; // Store the reference to timeout handler
    this.hideControlsRunnable =
        hideControlsRunnable; // Store the reference to the hide controls runnable
    setClickable(true);
    setFocusable(true);
    setFocusableInTouchMode(true);
    requestFocus(); // Add this line to request focus
    setBackgroundColor(0x00000000);
    setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));
    setLayoutParams(
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    preferences = PreferenceManager.getDefaultSharedPreferences(this.getContext());
  }

  public InputControlsView(Context context, boolean focusOnStick) {
    super(context);
    setClickable(true);
    setFocusable(true);
    setFocusableInTouchMode(true);
    requestFocus(); // Add this line to request focus
    setBackgroundColor(0x00000000);
    setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));

    // If focusOnStick is true, adjust the layout params to match the stick element size
    if (focusOnStick) {
      setLayoutParams(
          new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    } else {
      setLayoutParams(
          new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    preferences = PreferenceManager.getDefaultSharedPreferences(this.getContext());
  }

  public void setEditMode(boolean editMode) {
    this.editMode = editMode;
    invalidate();
  }

  public boolean isEditMode() {
    return editMode;
  }

  public void setOverlayOpacity(float overlayOpacity) {
    this.overlayOpacity = overlayOpacity;
    invalidate();
  }

  public float getOverlayOpacity() {
    return overlayOpacity;
  }

  private boolean reverseBindingOrder = false;

  public boolean isReverseBindingOrder() {
    return reverseBindingOrder;
  }

  public void setReverseBindingOrder(boolean reverseBindingOrder) {
    this.reverseBindingOrder = reverseBindingOrder;
  }

  public VisualStyle getVisualStyle() {
    return visualStyle;
  }

  public void setVisualStyle(VisualStyle style) {
    this.visualStyle = style != null ? style : VisualStyle.SLATE;
    invalidate();
  }

  public AccentTheme getAccentTheme() {
    return accentTheme;
  }

  public void setAccentTheme(AccentTheme theme) {
    this.accentTheme = theme != null ? theme : AccentTheme.CYAN;
    invalidate();
  }

  public InputControlsManager getInputControlsManager() {
    return inputControlsManager;
  }

  public int getSnappingSize() {
    return snappingSize;
  }

  @Override
  protected synchronized void onDraw(Canvas canvas) {
    int width, height;

    if (stickElement != null && isFocusedOnStick()) {
      // If focusing on the stick, set width and height to the stick's bounding box size
      Rect boundingBox = stickElement.getBoundingBox();
      width = boundingBox.width();
      height = boundingBox.height();
    } else {
      // Default behavior for full screen
      width = getWidth();
      height = getHeight();
    }

    if (width == 0 || height == 0) {
      readyToDraw = false;
      return;
    }

    snappingSize = width / 100;
    readyToDraw = true;

    if (editMode) {
      drawGrid(canvas);
      drawCursor(canvas);
    }

    if (stickElement != null) {
      // Draw only the stick element if focus mode is active
      stickElement.draw(canvas);
    }

    if (profile != null && (showTouchscreenControls || editMode) && !isFocusedOnStick()) {
      if (!profile.isElementsLoaded()) profile.loadElements(this);
      boolean clipped = !editMode && canvas.getClipBounds(clipBounds);
      for (ControlElement element : profile.getElements()) {
        if (clipped && !intersectsDamage(element, clipBounds)) continue;
        element.draw(canvas);
      }
    }

    super.onDraw(canvas);
  }

  private boolean intersectsDamage(ControlElement element, Rect damage) {
    Rect box = element.getBoundingBox();
    int padding = elementDamagePadding();
    return damage.intersects(
        box.left - padding, box.top - padding, box.right + padding, box.bottom + padding);
  }

  private int elementDamagePadding() {
    return Math.max(getSnappingSize() * 4, 32);
  }

  public void resetStickPosition() {
    if (stickElement != null) {
      Rect boundingBox = stickElement.getBoundingBox();
      float centerX = boundingBox.centerX();
      float centerY = boundingBox.centerY();

      stickElement.setCurrentPosition(centerX, centerY); // Reset to the center of the bounding box
      invalidate(); // Redraw the stick in the centered position
    }
  }

  public void initializeStickElement(float x, float y, float scale) {
    stickElement = new ControlElement(this);
    stickElement.setType(ControlElement.Type.STICK); // Set type to STICK
    stickElement.setX((int) x);
    stickElement.setY((int) y);
    stickElement.setScale(scale);
    invalidate(); // Force the view to redraw with the stick
  }

  public void updateStickPosition(float x, float y) {
    if (stickElement != null) {
      stickElement.getCurrentPosition().x = x; // Update the thumbstick's position
      stickElement.getCurrentPosition().y = y; // Update the thumbstick's position
      invalidate(); // Redraw the view
    }
  }

  public ControlElement getStickElement() {
    return stickElement;
  }

  private void drawGrid(Canvas canvas) {
    paint.setStyle(Paint.Style.FILL);
    paint.setStrokeWidth(snappingSize * 0.0625f);

    // Background color from theme
    int bgColor = 0xFF12121B;
    canvas.drawColor(bgColor);

    paint.setAntiAlias(false);
    // Grid line color from theme (outline)
    int gridColor =
        androidx.core.content.ContextCompat.getColor(getContext(), R.color.settings_outline);
    paint.setColor(gridColor);

    int width = getMaxWidth();
    int height = getMaxHeight();

    for (int i = 0; i < width; i += snappingSize) {
      canvas.drawLine(i, 0, i, height, paint);
      canvas.drawLine(0, i, width, i, paint);
    }

    float cx = Mathf.roundTo(width * 0.5f, snappingSize);
    float cy = Mathf.roundTo(height * 0.5f, snappingSize);

    // Snapping center line color
    int snappingColor =
        androidx.core.content.ContextCompat.getColor(
            getContext(), R.color.settings_popup_surface_edge);
    paint.setColor(snappingColor);

    for (int i = 0; i < width; i += snappingSize * 2) {
      canvas.drawLine(cx, i, cx, i + snappingSize, paint);
      canvas.drawLine(i, cy, i + snappingSize, cy, paint);
    }

    paint.setAntiAlias(true);
  }

  private void drawCursor(Canvas canvas) {
    paint.setStyle(Paint.Style.FILL);
    paint.setStrokeWidth(snappingSize * 0.0625f);
    paint.setColor(0xffc62828);

    paint.setAntiAlias(false);
    canvas.drawLine(0, cursor.y, getMaxWidth(), cursor.y, paint);
    canvas.drawLine(cursor.x, 0, cursor.x, getMaxHeight(), paint);

    paint.setAntiAlias(true);
  }

  public synchronized boolean addElement() {
    if (editMode && profile != null) {
      ControlElement element = new ControlElement(this);
      element.setX(cursor.x);
      element.setY(cursor.y);
      profile.addElement(element);
      profile.save();
      selectElement(element);
      return true;
    } else return false;
  }

  public synchronized boolean removeElement() {
    if (editMode && selectedElement != null && profile != null) {
      profile.removeElement(selectedElement);
      selectedElement = null;
      profile.save();
      invalidate();
      return true;
    } else return false;
  }

  public ControlElement getSelectedElement() {
    return selectedElement;
  }

  private synchronized void deselectAllElements() {
    selectedElement = null;
    if (profile != null) {
      for (ControlElement element : profile.getElements()) element.setSelected(false);
    }
  }

  private void selectElement(ControlElement element) {
    deselectAllElements();
    if (element != null) {
      selectedElement = element;
      selectedElement.setSelected(true);
    }
    invalidate();
  }

  public synchronized ControlsProfile getProfile() {
    return profile;
  }

  public synchronized void setProfile(ControlsProfile profile) {
    releaseActiveTouchElements();
    if (profile != null) {
      this.profile = profile;
      deselectAllElements();
    } else this.profile = null;
    activeTouchElements.clear();
    invalidate();
  }

  public boolean isAdaptiveJoysticks() {
    return adaptiveJoysticks;
  }

  public void setAdaptiveJoysticks(boolean adaptiveJoysticks) {
    if (this.adaptiveJoysticks == adaptiveJoysticks) return;
    this.adaptiveJoysticks = adaptiveJoysticks;
    invalidate();
  }

  public boolean isPointOverOtherElement(ControlElement self, float x, float y) {
    if (profile == null) return false;
    for (ControlElement element : profile.getElements()) {
      if (element != self && element.containsPoint(x, y)) return true;
    }
    return false;
  }

  public boolean isShowTouchscreenControls() {
    return showTouchscreenControls;
  }

  public void setShowTouchscreenControls(boolean showTouchscreenControls) {
    if (this.showTouchscreenControls == showTouchscreenControls) {
      return;
    }
    if (!showTouchscreenControls) {
      releaseActiveTouchElements();
    }
    this.showTouchscreenControls = showTouchscreenControls;
    invalidate();
  }

  private void dispatchUnhandledTouch(MotionEvent event) {
    if (touchpadView != null) {
      touchpadView.onTouchEvent(event);
    }
  }

  public int getPrimaryColor() {
    return Color.argb((int) (overlayOpacity * 255), 255, 255, 255);
  }

  public int getSecondaryColor() {
    return Color.argb((int) (overlayOpacity * 255), 2, 119, 189);
  }

  private synchronized ControlElement intersectElement(float x, float y) {
    if (profile != null) {
      for (ControlElement element : profile.getElements()) {
        if (element.containsPoint(x, y)) return element;
      }
    }
    return null;
  }

  public Paint getPaint() {
    return paint;
  }

  public Path getPath() {
    return path;
  }

  public ColorFilter getColorFilter() {
    return colorFilter;
  }

  public TouchpadView getTouchpadView() {
    return touchpadView;
  }

  public void setTouchpadView(TouchpadView touchpadView) {
    this.touchpadView = touchpadView;
  }

  public XServer getXServer() {
    return xServer;
  }

  public void setXServer(XServer xServer) {
    this.xServer = xServer;
    createMouseMoveTimer();
  }

  private void releaseActiveTouchElements() {
    for (int i = 0; i < activeTouchElements.size(); i++) {
      int activePointerId = activeTouchElements.keyAt(i);
      ControlElement activeElement = activeTouchElements.valueAt(i);
      if (activeElement != null) {
        activeElement.handleTouchUp(activePointerId);
      }
    }
    activeTouchElements.clear();
  }

  // Release captures whose pointer is no longer reported (missed UP/CANCEL).
  private void releaseStaleCaptures(MotionEvent event) {
    boolean removedAny = false;
    for (int i = activeTouchElements.size() - 1; i >= 0; i--) {
      int capturedId = activeTouchElements.keyAt(i);
      boolean stillDown = false;
      for (int p = 0, count = event.getPointerCount(); p < count; p++) {
        if (event.getPointerId(p) == capturedId) {
          stillDown = true;
          break;
        }
      }
      if (!stillDown) {
        ControlElement element = activeTouchElements.valueAt(i);
        if (element != null) element.handleTouchUp(capturedId);
        activeTouchElements.removeAt(i);
        removedAny = true;
      }
    }
    if (removedAny) syncCapturedPointers();
  }

  public void cancelContinuousMouseMove() {
    mouseMoveOffsetX = 0f;
    mouseMoveOffsetY = 0f;
  }

  public synchronized void cancelActiveTouches() {
    releaseActiveTouchElements();
    cancelContinuousMouseMove();
  }

  public int getMaxWidth() {
    return (int) Mathf.roundTo(getWidth(), snappingSize);
  }

  @Override
  protected void onDetachedFromWindow() {
    cancelContinuousMouseMove();
    if (mouseMoveTimer != null) {
      mouseMoveTimer.cancel();
      mouseMoveTimer = null;
    }
    super.onDetachedFromWindow();
  }

  public int getMaxHeight() {
    return (int) Mathf.roundTo(getHeight(), snappingSize);
  }

  private void createMouseMoveTimer() {
    if (xServer == null) return;
    WinHandler winHandler = xServer.getWinHandler();
    if (mouseMoveTimer == null && profile != null) {
      final float cursorSpeed = profile.getCursorSpeed();
      mouseMoveTimer = new Timer();
      mouseMoveTimer.schedule(
          new TimerTask() {
            @Override
            public void run() {
              if (getContext() instanceof XServerDisplayActivity && ((XServerDisplayActivity)getContext()).isInputSuspended()) return;
              if (mouseMoveOffsetX != 0 || mouseMoveOffsetY != 0) {                int dx = (int) (mouseMoveOffsetX * cursorSpeed * 20);
                int dy = (int) (mouseMoveOffsetY * cursorSpeed * 20);
                if (xServer.isRelativeMouseMovement()) {
                  xServer.updatePointerForDisplayDelta(dx, dy);
                  winHandler.mouseMoveDelta(dx, dy);
                } else {
                  xServer.injectPointerMoveDelta(dx, dy);
                }
                if (xServer.getRenderer() != null) xServer.getRenderer().requestRenderCoalesced(VulkanRenderer.WAKE_INPUTVIEW);
              }
            }
          },
          0,
          1000 / 60); // 60 FPS
    }
  }

  //    private void processJoystickInput(ExternalController controller) {
  //        ExternalControllerBinding controllerBinding;
  //        final int[] axes = {MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z,
  // MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};
  //        final float[] values = {controller.state.thumbLX, controller.state.thumbLY,
  // controller.state.thumbRX, controller.state.thumbRY, controller.state.getDPadX(),
  // controller.state.getDPadY()};
  //
  //        for (byte i = 0; i < axes.length; i++) {
  //            if (Math.abs(values[i]) > ControlElement.STICK_DEAD_ZONE) {
  //                controllerBinding =
  // controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i],
  // Mathf.sign(values[i])));
  //                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(),
  // true, values[i]);
  //            }
  //            else {
  //                controllerBinding =
  // controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], (byte)
  // 1));
  //                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(),
  // false, values[i]);
  //                controllerBinding =
  // controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i],
  // (byte)-1));
  //                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(),
  // false, values[i]);
  //            }
  //        }
  //    }

  private final HashMap<String, int[]> lastAxisSign = new HashMap<>();

  private void processJoystickInput(ExternalController controller) {
    final int[] axes = {
      MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
      MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
      MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
    };
    final float[] values = {
      controller.state.thumbLX,
      controller.state.thumbLY,
      controller.state.thumbRX,
      controller.state.thumbRY,
      controller.state.getDPadX(),
      controller.state.getDPadY()
    };

    int[] lastSigns = lastAxisSign.get(controller.getId());
    if (lastSigns == null) {
      lastSigns = new int[axes.length];
      lastAxisSign.put(controller.getId(), lastSigns);
    }

    for (int i = 0; i < axes.length; i++) {
      float value = values[i];
      int currentSign = Math.abs(value) > ControlElement.STICK_DEAD_ZONE ? (int) Mathf.sign(value) : 0;
      int lastSign = lastSigns[i];

      if (currentSign != lastSign) {
        if (lastSign != 0) {
          int oldKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], (byte) lastSign);
          ExternalControllerBinding oldBinding = controller.getControllerBinding(oldKeyCode);
          if (oldBinding != null) {
            handleInputEvent(controller, oldBinding.getBinding(), false, 0, false);
          }
        }

        if (currentSign != 0) {
          int newKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], (byte) currentSign);
          ExternalControllerBinding newBinding = controller.getControllerBinding(newKeyCode);
          if (newBinding != null) {
            handleInputEvent(controller, newBinding.getBinding(), true, value, false);
          }
        }
        lastSigns[i] = currentSign;
      } else if (currentSign != 0) {
        // Sign hasn't changed, but deflection might have.
        // If it's a mouse move binding, we MUST update the offset for speed.
        int keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], (byte) currentSign);
        ExternalControllerBinding binding = controller.getControllerBinding(keyCode);
        if (binding != null && binding.getBinding().isMouseMove()) {
          handleInputEvent(controller, binding.getBinding(), true, value, false);
        }
      }
    }

    processTriggerInput(controller, controller.state.triggerL, KeyEvent.KEYCODE_BUTTON_L2, false);
    processTriggerInput(controller, controller.state.triggerR, KeyEvent.KEYCODE_BUTTON_R2, false);

    WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
    if (winHandler != null) {
      winHandler.sendGamepadState(controller);
      if (xServer != null && xServer.getRenderer() != null) xServer.getRenderer().requestRenderCoalesced(VulkanRenderer.WAKE_INPUTVIEW);
    }
  }

  private void processTriggerInput(
      ExternalController controller, float value, int keyCode, boolean sendUpdate) {
    ExternalControllerBinding binding = controller.getControllerBinding(keyCode);
    if (binding != null) {
      boolean isPressed = value > ControlElement.STICK_DEAD_ZONE;
      if (isPressed) {
        handleInputEvent(controller, binding.getBinding(), true, value, sendUpdate);
      } else {
        handleInputEvent(controller, binding.getBinding(), false, 0, sendUpdate);
      }
    }
  }

  //    @Override
  //    public boolean onGenericMotionEvent(MotionEvent event) {
  //        if (!editMode && profile != null) {
  //            ExternalController controller = profile.getController(event.getDeviceId());
  //            if (controller != null && controller.updateStateFromMotionEvent(event)) {
  //                ExternalControllerBinding controllerBinding;
  //                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2);
  //                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(),
  // controller.state.isPressed(ExternalController.IDX_BUTTON_L2));
  //
  //                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2);
  //                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(),
  // controller.state.isPressed(ExternalController.IDX_BUTTON_R2));
  //
  //                processJoystickInput(controller);
  //                return true;
  //            }
  //        }
  //        return super.onGenericMotionEvent(event);
  //    }

  @Override
  public boolean dispatchGenericMotionEvent(MotionEvent event) {
    return super.dispatchGenericMotionEvent(event);
  }

  @Override
  public boolean onGenericMotionEvent(MotionEvent event) {
    if (!editMode && profile != null) {
      ExternalController controller = profile.getController(event.getDeviceId());

      if (controller != null) {
        controller.updateStateFromMotionEvent(event);
        ExternalControllerBinding controllerBinding;

        controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2);
        if (controllerBinding != null) {
          handleInputEvent(
              controller,
              controllerBinding.getBinding(),
              controller.state.isPressed(ExternalController.IDX_BUTTON_L2));
        }

        controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2);
        if (controllerBinding != null) {
          handleInputEvent(
              controller,
              controllerBinding.getBinding(),
              controller.state.isPressed(ExternalController.IDX_BUTTON_R2));
        }

        processJoystickInput(controller);
        return true;
      }
    }
    return super.onGenericMotionEvent(event);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (getContext() instanceof XServerDisplayActivity && ((XServerDisplayActivity)getContext()).isInputSuspended()) return true;

    boolean hapticsEnabled = preferences.getBoolean("touchscreen_haptics_enabled", false);

    if (editMode && readyToDraw) {
      switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
          {
            float x = event.getX();
            float y = event.getY();

            ControlElement element = intersectElement(x, y);
            moveCursor = true;
            if (element != null) {
              offsetX = x - element.getX();
              offsetY = y - element.getY();
              moveCursor = false;
            }

            if (moveCursor) cursor.set(Math.round(x), Math.round(y));
            selectElement(element);
            break;
          }
        case MotionEvent.ACTION_MOVE:
          {
            if (selectedElement != null) {
              selectedElement.setX(Math.round(event.getX() - offsetX));
              selectedElement.setY(Math.round(event.getY() - offsetY));
              invalidate();
            } else if (moveCursor) {
              cursor.set(Math.round(event.getX()), Math.round(event.getY()));
              invalidate();
            }
            break;
          }
        case MotionEvent.ACTION_UP:
          {
            if (selectedElement != null && profile != null) profile.save();
            if (moveCursor)
              cursor.set(Math.round(event.getX()), Math.round(event.getY()));
            invalidate();
            break;
          }
      }
    }

    if (!editMode && profile != null) {
      if (!showTouchscreenControls) {
        dispatchUnhandledTouch(event);
        return true;
      }

      int actionIndex = event.getActionIndex();
      int pointerId = event.getPointerId(actionIndex);
      int actionMasked = event.getActionMasked();
      boolean eventHandled = false;

      switch (actionMasked) {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN:
          {
            float x = event.getX(actionIndex);
            float y = event.getY(actionIndex);

            if (stickElement != null && stickElement.handleTouchDown(pointerId, x, y)) {
              eventHandled = true;
              activeTouchElements.put(pointerId, stickElement);
            }

            if (!eventHandled) {
              for (ControlElement element : profile.getElements()) {
                if (element.handleTouchDown(pointerId, x, y)) {
                  eventHandled = true;
                  activeTouchElements.put(pointerId, element);

                  if (hapticsEnabled) triggerTouchHaptic();
                  break;
                }
              }
            }
            syncCapturedPointers();
            if (!eventHandled) dispatchUnhandledTouch(event);
            break;
          }
        case MotionEvent.ACTION_MOVE:
          {
            batchingUpdates = true;
            boolean anyControlHandled = false;
            boolean unhandledPointerExists = false;
            boolean capturesChanged = false;

            for (byte i = 0, count = (byte) event.getPointerCount(); i < count; i++) {
              int movePointerId = event.getPointerId(i);
              float x = event.getX(i);
              float y = event.getY(i);

              ControlElement activeElement = activeTouchElements.get(movePointerId);
              boolean swipeAllowed =
                  touchpadView == null
                      || touchpadView.getScreenTouchMode() != TouchpadView.MODE_MAP_TO_RIGHT_STICK;
              boolean pointerHandled = false;

              if (swipeAllowed
                  && activeElement != null
                  && activeElement.isCapturing(movePointerId)
                  && (activeElement.getType() == ControlElement.Type.RADIAL_MENU
                      || activeElement.getType() == ControlElement.Type.D_PAD)
                  && !activeElement.containsPoint(x, y)) {
                for (ControlElement element : profile.getElements()) {
                  if (element.isSwipeTarget() && element.handleTouchDown(movePointerId, x, y)) {
                    activeElement.handleTouchUp(movePointerId, x, y);
                    activeTouchElements.put(movePointerId, element);
                    activeElement = element;
                    pointerHandled = true;
                    capturesChanged = true;
                    if (hapticsEnabled) triggerTouchHaptic();
                    break;
                  }
                }
              }

              if (!pointerHandled) {
                pointerHandled =
                    activeElement != null && activeElement.handleTouchMove(movePointerId, x, y);
              }

              if (swipeAllowed
                  && activeElement != null
                  && activeElement.getType() == ControlElement.Type.BUTTON
                  && !activeElement.isCapturing(movePointerId)) {
                for (ControlElement element : profile.getElements()) {
                  if (element.isSwipeTarget() && element.handleTouchDown(movePointerId, x, y)) {
                    activeTouchElements.put(movePointerId, element);
                    pointerHandled = true;
                    capturesChanged = true;
                    if (hapticsEnabled) triggerTouchHaptic();
                    break;
                  }
                }
              }

              if (!pointerHandled && activeElement == null) {
                if (stickElement != null && stickElement.handleTouchMove(movePointerId, x, y)) {
                  activeTouchElements.put(movePointerId, stickElement);
                  pointerHandled = true;
                  capturesChanged = true;
                }

                if (!pointerHandled) {
                  for (ControlElement element : profile.getElements()) {
                    if (element.handleTouchMove(movePointerId, x, y)) {
                      activeTouchElements.put(movePointerId, element);
                      pointerHandled = true;
                      capturesChanged = true;
                      break;
                    }
                  }
                }
              }

              if (pointerHandled) anyControlHandled = true;
              else unhandledPointerExists = true;
            }

            batchingUpdates = false;
            WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
            if (anyControlHandled && winHandler != null) {
              winHandler.sendGamepadState();
            }

            if (capturesChanged) syncCapturedPointers();
            if (unhandledPointerExists) dispatchUnhandledTouch(event);
            break;
            }        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
          {
            ControlElement activeElement = activeTouchElements.get(pointerId);
            float x = event.getX(actionIndex);
            float y = event.getY(actionIndex);
            if (activeElement != null) {
              eventHandled = activeElement.handleTouchUp(pointerId, x, y);
              activeTouchElements.remove(pointerId);
            } else {
              if (stickElement != null && stickElement.handleTouchUp(pointerId, x, y)) {
                eventHandled = true;
              }

              if (!eventHandled) {
                for (ControlElement element : profile.getElements()) {
                  if (element.handleTouchUp(pointerId, x, y)) {
                    eventHandled = true;
                    break;
                  }
                }
              }
            }
            syncCapturedPointers();
            if (!eventHandled) dispatchUnhandledTouch(event);
            break;
          }
        case MotionEvent.ACTION_CANCEL:
          {
            releaseActiveTouchElements();
            dispatchUnhandledTouch(event);
            syncCapturedPointers();
            break;
          }
      }

      releaseStaleCaptures(event);
    }
    return true;
  }

  private void triggerTouchHaptic() {
    Vibrator vibrator;
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
      VibratorManager vibratorManager = getContext().getSystemService(VibratorManager.class);
      vibrator = vibratorManager != null ? vibratorManager.getDefaultVibrator() : null;
    } else {
      vibrator = getContext().getSystemService(Vibrator.class);
    }
    if (vibrator != null && vibrator.hasVibrator()) {
      vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
    }
  }

  private void syncCapturedPointers() {
    if (touchpadView != null) {
      Set<Integer> pointerIds = new HashSet<>();
      for (int i = 0; i < activeTouchElements.size(); i++) pointerIds.add(activeTouchElements.keyAt(i));
      touchpadView.setPointerIdsToIgnore(pointerIds);
    }
  }

  public void invalidateControlElement(ControlElement element) {
    if (element == null) return;

    Rect dirtyRect = element.getBoundingBox();
    int padding = elementDamagePadding();
    postInvalidateOnAnimation(
        dirtyRect.left - padding,
        dirtyRect.top - padding,
        dirtyRect.right + padding,
        dirtyRect.bottom + padding);
  }

  public boolean onKeyEvent(KeyEvent event) {
    if (getContext() instanceof XServerDisplayActivity && ((XServerDisplayActivity)getContext()).isInputSuspended()) return false;
    if (profile != null && event.getRepeatCount() == 0) {
      ExternalController controller = profile.getController(event.getDeviceId());
      if (controller != null) {
        ExternalControllerBinding controllerBinding =
            controller.getControllerBinding(event.getKeyCode());
        if (controllerBinding != null) {
          int action = event.getAction();

          if (action == KeyEvent.ACTION_DOWN) {
            handleInputEvent(controller, controllerBinding.getBinding(), true);
          } else if (action == KeyEvent.ACTION_UP) {
            handleInputEvent(controller, controllerBinding.getBinding(), false);
          }
          return true;
        }
      }
    }
    return false;
  }

  public void handleInputEvent(Binding binding, boolean isActionDown) {
    handleInputEvent(null, binding, isActionDown, 0);
  }

  public void handleInputEvent(
      ExternalController controller, Binding binding, boolean isActionDown) {
    handleInputEvent(controller, binding, isActionDown, 0);
  }

  public void handleStickInput(Binding firstBinding, float deltaX, float deltaY) {
    handleStickInput(firstBinding, deltaX, deltaY, !batchingUpdates);
  }

  public void handleStickInput(
      Binding firstBinding, float deltaX, float deltaY, boolean sendUpdate) {
    if (profile == null || !firstBinding.isGamepad()) return;

    GamepadState state = profile.getGamepadState();
    WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;

    boolean isLeftStick =
        firstBinding == Binding.GAMEPAD_LEFT_THUMB_UP
            || firstBinding == Binding.GAMEPAD_LEFT_THUMB_DOWN
            || firstBinding == Binding.GAMEPAD_LEFT_THUMB_LEFT
            || firstBinding == Binding.GAMEPAD_LEFT_THUMB_RIGHT;

    if (isLeftStick) {
      state.thumbLX = deltaX;
      state.thumbLY = deltaY;
    } else {
      state.thumbRX = deltaX;
      state.thumbRY = deltaY;
    }

    if (winHandler != null && sendUpdate) {
      winHandler.sendGamepadState();
      if (xServer != null && xServer.getRenderer() != null) xServer.getRenderer().requestRenderCoalesced(VulkanRenderer.WAKE_INPUTVIEW);
    }
  }

  public void handleInputEvent(Binding binding, boolean isActionDown, float offset) {
    handleInputEvent(null, binding, isActionDown, offset);
  }

  public void handleInputEvent(
      ExternalController controller, Binding binding, boolean isActionDown, float offset) {
    handleInputEvent(controller, binding, isActionDown, offset, !batchingUpdates);
  }

  public void handleInputEvent(
      ExternalController controller,
      Binding binding,
      boolean isActionDown,
      float offset,
      boolean sendUpdate) {
    if (binding == Binding.NONE) return;
    WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
    if (binding.isGamepad()) {
      if (profile == null && controller == null) return;
      GamepadState state =
          (controller != null) ? controller.remappedState : profile.getGamepadState();
      boolean stateChanged = false;

      int buttonIdx = binding.ordinal() - Binding.GAMEPAD_BUTTON_A.ordinal();
      if (buttonIdx <= ExternalController.IDX_BUTTON_R2) {
        if (buttonIdx == ExternalController.IDX_BUTTON_L2) {
          float value = isActionDown ? (offset != 0 ? offset : 1.0f) : 0f;
          stateChanged = Float.compare(state.triggerL, value) != 0;
          state.triggerL = value;
        } else if (buttonIdx == ExternalController.IDX_BUTTON_R2) {
          float value = isActionDown ? (offset != 0 ? offset : 1.0f) : 0f;
          stateChanged = Float.compare(state.triggerR, value) != 0;
          state.triggerR = value;
        } else {
          stateChanged = state.isPressed(buttonIdx) != isActionDown;
          if (stateChanged) state.setPressed(buttonIdx, isActionDown);
        }
      } else if (binding == Binding.GAMEPAD_LEFT_THUMB_UP
          || binding == Binding.GAMEPAD_LEFT_THUMB_DOWN) {
        float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
        float value = isActionDown ? (binding == Binding.GAMEPAD_LEFT_THUMB_UP ? -val : val) : 0;
        stateChanged = Float.compare(state.thumbLY, value) != 0;
        state.thumbLY = value;
      } else if (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT
          || binding == Binding.GAMEPAD_LEFT_THUMB_RIGHT) {
        float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
        float value = isActionDown ? (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT ? -val : val) : 0;
        stateChanged = Float.compare(state.thumbLX, value) != 0;
        state.thumbLX = value;
      } else if (binding == Binding.GAMEPAD_RIGHT_THUMB_UP
          || binding == Binding.GAMEPAD_RIGHT_THUMB_DOWN) {
        float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
        float value = isActionDown ? (binding == Binding.GAMEPAD_RIGHT_THUMB_UP ? -val : val) : 0;
        stateChanged = Float.compare(state.thumbRY, value) != 0;
        state.thumbRY = value;
      } else if (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT
          || binding == Binding.GAMEPAD_RIGHT_THUMB_RIGHT) {
        float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
        float value =
            isActionDown ? (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT ? -val : val) : 0;
        stateChanged = Float.compare(state.thumbRX, value) != 0;
        state.thumbRX = value;
      } else if (binding == Binding.GAMEPAD_DPAD_UP
          || binding == Binding.GAMEPAD_DPAD_RIGHT
          || binding == Binding.GAMEPAD_DPAD_DOWN
          || binding == Binding.GAMEPAD_DPAD_LEFT) {
        int dpadIndex = binding.ordinal() - Binding.GAMEPAD_DPAD_UP.ordinal();
        stateChanged = state.dpad[dpadIndex] != isActionDown;
        state.dpad[dpadIndex] = isActionDown;
      }

      if (winHandler != null && sendUpdate && stateChanged) {
        if (controller != null) winHandler.sendGamepadState(controller);
        else winHandler.sendGamepadState();
        if (xServer != null && xServer.getRenderer() != null) xServer.getRenderer().requestRenderCoalesced(VulkanRenderer.WAKE_INPUTVIEW);
      }
    } else {
      if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
        mouseMoveOffsetX =
            isActionDown
                ? (offset != 0 ? offset : (binding == Binding.MOUSE_MOVE_LEFT ? -1 : 1))
                : 0;
        if (isActionDown) createMouseMoveTimer();
      } else if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
        mouseMoveOffsetY =
            isActionDown ? (offset != 0 ? offset : (binding == Binding.MOUSE_MOVE_UP ? -1 : 1)) : 0;
        if (isActionDown) createMouseMoveTimer();
      } else {
        Pointer.Button pointerButton = binding.getPointerButton();
        if (isActionDown) {
          if (pointerButton != null) {
            xServer.injectPointerButtonPress(pointerButton);
          } else xServer.injectKeyPress(binding.keycode);
        } else {
          if (pointerButton != null) {
            xServer.injectPointerButtonRelease(pointerButton);
          } else xServer.injectKeyRelease(binding.keycode);
        }
      }
    }
  }

  public Bitmap getIcon(byte id) {
    if (id < 0 || id >= icons.length) return null;
    if (icons[id] == null) {
      Context context = getContext();
      try (InputStream is = context.getAssets().open("inputcontrols/icons/" + id + ".png")) {
        icons[id] = BitmapFactory.decodeStream(is);
      } catch (IOException e) {
      }
    }
    return icons[id];
  }
}
