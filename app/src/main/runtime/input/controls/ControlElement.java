package com.winlator.cmod.runtime.input.controls;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.CornerPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import androidx.core.graphics.ColorUtils;
import com.winlator.cmod.runtime.display.winhandler.MouseEventFlags;
import com.winlator.cmod.runtime.display.xserver.XServer;
import com.winlator.cmod.runtime.input.ui.InputControlsView;
import com.winlator.cmod.runtime.input.ui.TouchpadView;
import com.winlator.cmod.shared.math.Mathf;
import com.winlator.cmod.shared.ui.CubicBezierInterpolator;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ControlElement {
  public static final float STICK_DEAD_ZONE = 0.15f;
  private static final float ADAPTIVE_REACH = 1.0f;
  public static final float DPAD_DEAD_ZONE = 0.3f;
  public static final float STICK_SENSITIVITY = 3.0f;
  public static final float STICK_CROSS_ZONE = 0.3f;
  public static final float TRACKPAD_MIN_SPEED = 0.8f;
  public static final float TRACKPAD_MAX_SPEED = 20.0f;
  public static final byte TRACKPAD_ACCELERATION_THRESHOLD = 4;
  public static final short BUTTON_MIN_TIME_TO_KEEP_PRESSED = 300;

  public enum Type {
    BUTTON,
    D_PAD,
    RANGE_BUTTON,
    STICK,
    TRACKPAD,
    RADIAL_MENU;

    public static String[] names() {
      Type[] types = values();
      String[] names = new String[types.length];
      for (int i = 0; i < types.length; i++) names[i] = types[i].name().replace("_", "-");
      return names;
    }
  }

  public enum Shape {
    CIRCLE,
    RECT,
    ROUND_RECT,
    SQUARE;

    public static String[] names() {
      Shape[] shapes = values();
      String[] names = new String[shapes.length];
      for (int i = 0; i < shapes.length; i++) names[i] = shapes[i].name().replace("_", " ");
      return names;
    }
  }

  public enum Range {
    FROM_A_TO_Z(26),
    FROM_0_TO_9(10),
    FROM_F1_TO_F12(12),
    FROM_NP0_TO_NP9(10);
    public final byte max;

    Range(int max) {
      this.max = (byte) max;
    }

    public static String[] names() {
      Range[] ranges = values();
      String[] names = new String[ranges.length];
      for (int i = 0; i < ranges.length; i++) names[i] = ranges[i].name().replace("_", " ");
      return names;
    }
  }

  private final InputControlsView inputControlsView;
  private Type type = Type.BUTTON;
  private Shape shape = Shape.CIRCLE;
  private Binding[] bindings = {Binding.NONE, Binding.NONE, Binding.NONE, Binding.NONE};
  private float scale = 1.0f;
  private float opacity = 1.0f;
  private short x;
  private short y;
  private boolean selected = false;
  private boolean toggleSwitch = false;
  private boolean swipeable = true;
  private boolean radialMenuExpanded = false;
  private int activeRadialBindingIndex = -1;
  private boolean isRadialBindingCurrentlyHeld = false;
  private boolean wasExpandedOnDown = false;
  private int currentPointerId = -1;
  private int pressGeneration = 0;
  private boolean adaptiveShifted = false;
  private final Rect boundingBox = new Rect();
  private final Path path = new Path();
  private Path[] paths;
  private boolean[] states = new boolean[4];
  private boolean boundingBoxNeedsUpdate = true;
  private String text = "";
  private byte iconId;
  private Range range;
  private byte orientation;
  private PointF currentPosition;
  private PointF trackpadOrigin;
  private int customColor = -1;
  private String displayTextCache;
  private String[] bindingTextCache;
  private RangeScroller scroller;
  private CubicBezierInterpolator interpolator;
  private Object touchTime;

  public ControlElement(InputControlsView inputControlsView) {
    this.inputControlsView = inputControlsView;
  }

  private void reset() {
    scroller = null;

    if (type == Type.STICK) {
      bindings[0] = Binding.NONE;
      bindings[1] = Binding.NONE;
      bindings[2] = Binding.NONE;
      bindings[3] = Binding.NONE;
    } else if (type == Type.D_PAD) {
      bindings[0] = Binding.NONE;
      bindings[1] = Binding.NONE;
      bindings[2] = Binding.NONE;
      bindings[3] = Binding.NONE;
    } else if (type == Type.TRACKPAD) {
      bindings[0] = Binding.NONE;
      bindings[1] = Binding.NONE;
      bindings[2] = Binding.NONE;
      bindings[3] = Binding.NONE;
    } else if (type == Type.RANGE_BUTTON) {
      scroller = new RangeScroller(inputControlsView, this);
    } else if (type == Type.RADIAL_MENU) {
      if (bindings.length < 3) setBindingCount(3);
    }

    text = "";
    iconId = 0;
    range = null;
    boundingBoxNeedsUpdate = true;
    radialMenuExpanded = false;
    paths = null;
    invalidateLabelCache();
  }

  public Type getType() {
    return type;
  }

  public void setType(Type type) {
    this.type = type;
    reset();
  }

  public int getBindingCount() {
    return bindings.length;
  }

  public void setBindingCount(int bindingCount) {
    int oldLength = bindings.length;
    bindings = Arrays.copyOf(bindings, bindingCount);
    if (bindingCount > oldLength) {
      Arrays.fill(bindings, oldLength, bindingCount, Binding.NONE);
    }
    states = new boolean[bindingCount];
    boundingBoxNeedsUpdate = true;
    paths = null;
    invalidateLabelCache();
  }

  public Shape getShape() {
    return shape;
  }

  public void setShape(Shape shape) {
    this.shape = shape;
    boundingBoxNeedsUpdate = true;
  }

  public Range getRange() {
    return range != null ? range : Range.FROM_A_TO_Z;
  }

  public void setRange(Range range) {
    this.range = range;
  }

  public byte getOrientation() {
    return orientation;
  }

  public void setOrientation(byte orientation) {
    this.orientation = orientation;
    boundingBoxNeedsUpdate = true;
  }

  public boolean isToggleSwitch() {
    return toggleSwitch;
  }

  public void setToggleSwitch(boolean toggleSwitch) {
    this.toggleSwitch = toggleSwitch;
  }

  public boolean isSwipeable() {
    return swipeable;
  }

  public void setSwipeable(boolean swipeable) {
    this.swipeable = swipeable;
  }

  public float getOpacity() {
    return opacity;
  }

  public void setOpacity(float opacity) {
    this.opacity = opacity;
  }

  public boolean isRadialMenuExpanded() {
    return radialMenuExpanded;
  }

  public void setRadialMenuExpanded(boolean radialMenuExpanded) {
    this.radialMenuExpanded = radialMenuExpanded;
    paths = null;
  }

  public int getCustomColor() {
    return customColor;
  }

  public void setCustomColor(int customColor) {
    this.customColor = customColor;
    this.boundingBoxNeedsUpdate = true;
  }

  public Binding[] getBindings() {
    return bindings;
  }

  public Binding getBindingAt(int index) {
    return index < bindings.length ? bindings[index] : Binding.NONE;
  }

  public void setBindingAt(int index, Binding binding) {
    if (index >= bindings.length) {
      int oldLength = bindings.length;
      bindings = Arrays.copyOf(bindings, index + 1);
      Arrays.fill(bindings, oldLength, bindings.length, Binding.NONE);
      states = new boolean[bindings.length];
      boundingBoxNeedsUpdate = true;
    }
    bindings[index] = binding;
    paths = null;
    invalidateLabelCache();
  }

  public void setBinding(Binding binding) {
    Arrays.fill(bindings, binding);
    paths = null;
    invalidateLabelCache();
  }

  public float getScale() {
    return scale;
  }

  public void setScale(float scale) {
    this.scale = scale;
    boundingBoxNeedsUpdate = true;
    paths = null;
  }

  public short getX() {
    return x;
  }

  public void setX(int x) {
    this.x = (short) x;
    boundingBoxNeedsUpdate = true;
    paths = null;
    if (currentPointerId == -1) currentPosition = null;
  }

  public short getY() {
    return y;
  }

  public void setY(int y) {
    this.y = (short) y;
    boundingBoxNeedsUpdate = true;
    paths = null;
    if (currentPointerId == -1) currentPosition = null;
  }

  public boolean isSelected() {
    return selected;
  }

  public void setSelected(boolean selected) {
    this.selected = selected;
    if (type == Type.RADIAL_MENU) {
      this.radialMenuExpanded = selected;
      this.paths = null;
    }
  }

  public String getText() {
    return text;
  }

  public void setText(String text) {
    this.text = text != null ? text : "";
    invalidateLabelCache();
  }

  public byte getIconId() {
    return iconId;
  }

  public void setIconId(int iconId) {
    this.iconId = (byte) iconId;
  }

  public Rect getBoundingBox() {
    if (boundingBoxNeedsUpdate) computeBoundingBox();
    // Position and size always come from the ICP profile. VisualStyle (ORIGINAL vs GAMEHUB) only
    // changes how an element is drawn, never where it sits — GameHub layouts ship as their own ICP.
    return boundingBox;
  }

  /** Trigger/bumper silhouette for this element when drawn in the GameHub style, or null. */
  private GameHubLayout.RenderShape gameHubTriggerShape() {
    return GameHubLayout.triggerShapeFor(GameHubLayout.roleFor(this));
  }

  private Rect computeBoundingBox() {
    int snappingSize = inputControlsView.getSnappingSize();
    int halfWidth = 0;
    int halfHeight = 0;

    switch (type) {
      case BUTTON:
        switch (shape) {
          case RECT:
          case ROUND_RECT:
            halfWidth = snappingSize * 4;
            halfHeight = snappingSize * 2;
            break;
          case SQUARE:
            halfWidth = (int) (snappingSize * 2.5f);
            halfHeight = (int) (snappingSize * 2.5f);
            break;
          case CIRCLE:
            halfWidth = snappingSize * 3;
            halfHeight = snappingSize * 3;
            break;
        }
        break;
      case D_PAD:
        {
          halfWidth = snappingSize * 7;
          halfHeight = snappingSize * 7;
          break;
        }
      case TRACKPAD:
      case STICK:
        {
          halfWidth = snappingSize * 6;
          halfHeight = snappingSize * 6;
          break;
        }
      case RANGE_BUTTON:
        {
          halfWidth = snappingSize * ((bindings.length * 4) / 2);
          halfHeight = snappingSize * 2;

          if (orientation == 1) {
            int tmp = halfWidth;
            halfWidth = halfHeight;
            halfHeight = tmp;
          }
          break;
        }
      case RADIAL_MENU:
        {
          halfWidth = snappingSize * 3;
          halfHeight = snappingSize * 3;
          break;
        }
    }
halfWidth *= scale;
halfHeight *= scale;
boundingBox.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
boundingBoxNeedsUpdate = false;
return boundingBox;
}

  private String getDisplayText() {
    if (displayTextCache != null) return displayTextCache;
    String result;
    // Per-element text always wins (user explicit override).
    if (text != null && !text.isEmpty()) {
      result = text;
    } else {
      Binding binding = getBindingAt(0);
      String bumper = bumperTriggerLabel(binding);
      if (bumper != null) {
        result = bumper;
      } else {
        String s = binding.toString().replace("NUMPAD ", "NP").replace("BUTTON ", "");
        if (s.length() > 7) {
          String[] parts = s.split(" ");
          StringBuilder sb = new StringBuilder();
          for (String part : parts) sb.append(part.charAt(0));
          result = (binding.isMouse() ? "M" : "") + sb;
        } else result = s;
      }
    }
    displayTextCache = result;
    return result;
  }

  private void invalidateLabelCache() {
    displayTextCache = null;
    bindingTextCache = null;
  }

  /** Returns the per-element customColor, or {@code -1} if unset (fall back to theme accent). */
  private int resolveAccentColor() {
    return customColor;
  }

  /** Bumper/trigger labels follow the standard gamepad naming; null for every other binding. */
  private static String bumperTriggerLabel(Binding binding) {
    if (binding == null) return null;
    switch (binding) {
      case GAMEPAD_BUTTON_L1:
        return "LB";
      case GAMEPAD_BUTTON_R1:
        return "RB";
      case GAMEPAD_BUTTON_L2:
        return "LT";
      case GAMEPAD_BUTTON_R2:
        return "RT";
      default:
        return null;
    }
  }

  private String getBindingShortText(int index) {
    String[] cache = bindingTextCache;
    if (cache != null && index < cache.length && cache[index] != null) return cache[index];
    Binding binding = getBindingAt(index);
    String bumper = bumperTriggerLabel(binding);
    String result;
    if (bumper != null) {
      result = bumper;
    } else {
      String text = binding.toString().replace("NUMPAD ", "NP").replace("BUTTON ", "").replace("KEY_", "").replace("GAMEPAD_", "");
      if (text.length() > 6) {
        String[] parts = text.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) if (!part.isEmpty()) sb.append(part.charAt(0));
        result = (binding.isMouse() ? "M" : "") + sb;
      } else {
        result = text.replace("_", " ");
      }
    }
    if (cache == null || cache.length != bindings.length) {
      cache = new String[bindings.length];
      bindingTextCache = cache;
    }
    if (index < cache.length) cache[index] = result;
    return result;
  }

  private static float getTextSizeForWidth(Paint paint, String text, float desiredWidth) {
    final byte testTextSize = 48;
    paint.setTextSize(testTextSize);
    return testTextSize * desiredWidth / paint.measureText(text);
  }

  private static final String[][] rangeTextCache = new String[Range.values().length][];

  private static String getRangeTextForIndex(Range range, int index) {
    if (index < 0 || index >= range.max) return computeRangeText(range, index);
    String[] cache = rangeTextCache[range.ordinal()];
    if (cache == null) {
      cache = new String[range.max];
      rangeTextCache[range.ordinal()] = cache;
    }
    String cached = cache[index];
    if (cached == null) {
      cached = computeRangeText(range, index);
      cache[index] = cached;
    }
    return cached;
  }

  private static String computeRangeText(Range range, int index) {
    String text = "";
    switch (range) {
      case FROM_A_TO_Z:
        text = String.valueOf((char) (65 + index));
        break;
      case FROM_0_TO_9:
        text = String.valueOf((index + 1) % 10);
        break;
      case FROM_F1_TO_F12:
        text = "F" + (index + 1);
        break;
      case FROM_NP0_TO_NP9:
        text = "NP" + ((index + 1) % 10);
        break;
    }
    return text;
  }

  private boolean isEngaged() {
    return currentPointerId != -1 || (toggleSwitch && selected);
  }

  // Shared draw caches. Drawing happens only on the UI thread, so static temps are safe.
  private static Shader bloomShader;
  private static Shader edgeShadeShader;
  private static final Matrix shaderMatrix = new Matrix();
  private static final RectF tempRect = new RectF();
  private PorterDuffColorFilter cachedAccentFilter;
  private int cachedAccentFilterColor = 1;
  private CornerPathEffect cachedCornerEffect;
  private float cachedCornerRadius = -1f;

  private static Shader getBloomShader() {
    if (bloomShader == null) {
      bloomShader =
          new RadialGradient(
              0f, 0f, 1f,
              new int[] {0x8CFFFFFF, 0x3EFFFFFF, 0x00FFFFFF},
              new float[] {0f, 0.6f, 1f},
              Shader.TileMode.CLAMP);
    }
    return bloomShader;
  }

  private static Shader getEdgeShadeShader() {
    if (edgeShadeShader == null) {
      edgeShadeShader =
          new RadialGradient(0f, 0f, 1f, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
    }
    return edgeShadeShader;
  }

  private static void placeShader(Shader shader, float cx, float cy, float r) {
    shaderMatrix.reset();
    shaderMatrix.postScale(r, r);
    shaderMatrix.postTranslate(cx, cy);
    shader.setLocalMatrix(shaderMatrix);
  }

  private PorterDuffColorFilter accentFilter(int color) {
    if (cachedAccentFilterColor != color) {
      cachedAccentFilter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
      cachedAccentFilterColor = color;
    }
    return cachedAccentFilter;
  }

  private CornerPathEffect cornerEffect(float radius) {
    if (cachedCornerEffect == null || cachedCornerRadius != radius) {
      cachedCornerEffect = new CornerPathEffect(radius);
      cachedCornerRadius = radius;
    }
    return cachedCornerEffect;
  }

  private int resolveThemedAccent() {
    int c = resolveAccentColor();
    return c != -1 ? c : inputControlsView.getAccentTheme().accent;
  }

  private void beginBloom(Paint paint, float cx, float cy, float r, int accent, float alpha) {
    Shader bloom = getBloomShader();
    placeShader(bloom, cx, cy, r);
    paint.setStyle(Paint.Style.FILL);
    paint.setShader(bloom);
    paint.setColorFilter(accentFilter(accent));
    paint.setAlpha((int) (255 * alpha));
  }

  private static void endBloom(Paint paint) {
    paint.setShader(null);
    paint.setColorFilter(null);
  }

  public void draw(Canvas canvas) {
    if (isAdaptiveHidden()) return;
    VisualStyle style = inputControlsView.getVisualStyle();
    if (style == VisualStyle.GAMEHUB) {
      drawGameHub(canvas);
      return;
    }
    if (style == VisualStyle.SHADOW) {
      drawShadow(canvas);
      return;
    }
    if (style == VisualStyle.RETICLE) {
      drawReticle(canvas);
      return;
    }
    if (style == VisualStyle.NEON) {
      drawNeon(canvas);
      return;
    }
    if (style == VisualStyle.LUMINA) {
      drawLumina(canvas);
      return;
    }
    if (style != VisualStyle.ORIGINAL) {
      drawClean(canvas, style);
      return;
    }
    int snappingSize = inputControlsView.getSnappingSize();
    Paint paint = inputControlsView.getPaint();
    float effectiveOpacity = inputControlsView.isEditMode() ? Math.max(0.15f, opacity) : opacity;
    int accent = resolveAccentColor();
    if (accent == -1) {
      AccentTheme theme = inputControlsView.getAccentTheme();
      if (theme != AccentTheme.MONO) accent = theme.accent;
    }
    int primaryColor = accent != -1
        ? ColorUtils.setAlphaComponent(accent, (int) (Math.min(1.0f,
            inputControlsView.getOverlayOpacity() * 2.0f) * 255))
        : inputControlsView.getPrimaryColor();
    int alpha = (int) (Color.alpha(primaryColor) * effectiveOpacity);
    primaryColor = ColorUtils.setAlphaComponent(primaryColor, alpha);
    int fillColor = ColorUtils.setAlphaComponent(primaryColor, (int) (70 * effectiveOpacity));

    int highlightAlpha = (int) (255 * inputControlsView.getOverlayOpacity());
    int secondaryColor = ColorUtils.setAlphaComponent(inputControlsView.getSecondaryColor(), highlightAlpha);

    paint.setColor(
        (selected && accent == -1) ? secondaryColor : primaryColor);
    paint.setStyle(Paint.Style.STROKE);
    float strokeWidth = snappingSize * 0.25f;
    paint.setStrokeWidth(strokeWidth);
    Rect boundingBox = getBoundingBox();

    switch (type) {
      case BUTTON:
        {
          float cx = boundingBox.centerX();
          float cy = boundingBox.centerY();

          if (isEngaged()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fillColor);
            switch (shape) {
              case CIRCLE:
                canvas.drawCircle(cx, cy, boundingBox.width() * 0.5f, paint);
                break;
              case RECT:
                canvas.drawRect(boundingBox, paint);
                break;
              case ROUND_RECT:
                {
                  float r = boundingBox.height() * 0.5f;
                  canvas.drawRoundRect(
                      boundingBox.left,
                      boundingBox.top,
                      boundingBox.right,
                      boundingBox.bottom,
                      r,
                      r,
                      paint);
                  break;
                }
              case SQUARE:
                {
                  float r = snappingSize * 0.75f * scale;
                  canvas.drawRoundRect(
                      boundingBox.left,
                      boundingBox.top,
                      boundingBox.right,
                      boundingBox.bottom,
                      r,
                      r,
                      paint);
                  break;
                }
            }
          }

          paint.setStyle(Paint.Style.STROKE);
          paint.setColor(
              (selected && accent == -1)
                  ? secondaryColor
                  : primaryColor);
          paint.setStrokeWidth(strokeWidth);

          switch (shape) {
            case CIRCLE:
              canvas.drawCircle(cx, cy, boundingBox.width() * 0.5f, paint);
              break;
            case RECT:
              canvas.drawRect(boundingBox, paint);
              break;
            case ROUND_RECT:
              {
                float radius = boundingBox.height() * 0.5f;
                canvas.drawRoundRect(
                    boundingBox.left,
                    boundingBox.top,
                    boundingBox.right,
                    boundingBox.bottom,
                    radius,
                    radius,
                    paint);
                break;
              }
            case SQUARE:
              {
                float radius = snappingSize * 0.75f * scale;
                canvas.drawRoundRect(
                    boundingBox.left,
                    boundingBox.top,
                    boundingBox.right,
                    boundingBox.bottom,
                    radius,
                    radius,
                    paint);
                break;
              }
          }

          if (iconId > 0) {
            drawIcon(
                canvas, cx, cy, boundingBox.width(), boundingBox.height(), iconId, true,
                accent != -1 ? accentFilter(accent) : inputControlsView.getColorFilter());
          } else {
            String text = getDisplayText();
            paint.setTextSize(
                Math.min(
                    getTextSizeForWidth(paint, text, boundingBox.width() - strokeWidth * 2),
                    snappingSize * 2 * scale));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(primaryColor);
            canvas.drawText(text, x, (y - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
          }
          break;
        }
      case RADIAL_MENU:
        {
          float cx = boundingBox.centerX();
          float cy = boundingBox.centerY();
          float radius = boundingBox.width() * 0.5f;

          if (radialMenuExpanded && bindings.length > 0 && radius > 0) {
            float innerRadius = radius + snappingSize * 0.5f;
            float outerRadius = boundingBox.width() + (snappingSize * scale);
            float angleStep = 360.0f / bindings.length;

            if (paths == null || paths.length != bindings.length) {
              paths = new Path[bindings.length];
              RectF outerRect = new RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius);
              RectF innerRect = new RectF(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius);

              for (int i = 0; i < bindings.length; i++) {
                float startAngle = -90.0f + i * angleStep;
                paths[i] = new Path();
                paths[i].arcTo(outerRect, startAngle, angleStep, true);
                paths[i].arcTo(innerRect, startAngle + angleStep, -angleStep, false);
                paths[i].close();
              }
            }

            if (paths != null && paths.length == bindings.length) {
              for (int i = 0; i < bindings.length; i++) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(i == activeRadialBindingIndex ? secondaryColor : fillColor);
                canvas.drawPath(paths[i], paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(primaryColor);
                canvas.drawPath(paths[i], paint);

                float middleAngle = (float) Math.toRadians(-90.0f + i * angleStep + angleStep * 0.5f);
                float labelRadius = (innerRadius + outerRadius) * 0.5f;
                float labelX = (float) (cx + Math.cos(middleAngle) * labelRadius);
                float labelY = (float) (cy + Math.sin(middleAngle) * labelRadius);

                String label = getBindingShortText(i);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(snappingSize * 1.2f * scale);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(label, labelX, labelY - ((paint.descent() + paint.ascent()) * 0.5f), paint);
              }
            }
          }

          paint.setStyle(Paint.Style.STROKE);
          paint.setColor(
              (selected && accent == -1)
                  ? secondaryColor
                  : primaryColor);
          canvas.drawCircle(cx, cy, radius, paint);

          drawIcon(
              canvas, cx, cy, boundingBox.width(), boundingBox.height(),
              iconId > 0 ? iconId : 34, true,
              accent != -1 ? accentFilter(accent) : inputControlsView.getColorFilter());
          break;
        }
      case D_PAD:
        {
          float cx = boundingBox.centerX();
          float cy = boundingBox.centerY();
          float offsetX = snappingSize * 2 * scale;
          float offsetY = snappingSize * 3 * scale;
          float start = snappingSize * scale;
          path.reset();

          path.moveTo(cx, cy - start);
          path.lineTo(cx - offsetX, cy - offsetY);
          path.lineTo(cx - offsetX, boundingBox.top);
          path.lineTo(cx + offsetX, boundingBox.top);
          path.lineTo(cx + offsetX, cy - offsetY);
          path.close();

          path.moveTo(cx - start, cy);
          path.lineTo(cx - offsetY, cy - offsetX);
          path.lineTo(boundingBox.left, cy - offsetX);
          path.lineTo(boundingBox.left, cy + offsetX);
          path.lineTo(cx - offsetY, cy + offsetX);
          path.close();

          path.moveTo(cx, cy + start);
          path.lineTo(cx - offsetX, cy + offsetY);
          path.lineTo(cx - offsetX, boundingBox.bottom);
          path.lineTo(cx + offsetX, boundingBox.bottom);
          path.lineTo(cx + offsetX, cy + offsetY);
          path.close();

          path.moveTo(cx + start, cy);
          path.lineTo(cx + offsetY, cy - offsetX);
          path.lineTo(boundingBox.right, cy - offsetX);
          path.lineTo(boundingBox.right, cy + offsetX);
          path.lineTo(cx + offsetY, cy + offsetX);
          path.close();

          canvas.drawPath(path, paint);
          break;
        }
      case RANGE_BUTTON:
        {
          Range range = getRange();
          int oldColor = paint.getColor();
          float radius = snappingSize * 0.75f * scale;
          float elementSize = scroller.getElementSize();
          float minTextSize = snappingSize * 2 * scale;
          float scrollOffset = scroller.getScrollOffset();
          byte[] rangeIndex = scroller.getRangeIndex();
          path.reset();

          if (orientation == 0) {
            float lineTop = boundingBox.top + strokeWidth * 0.5f;
            float lineBottom = boundingBox.bottom - strokeWidth * 0.5f;
            float startX = boundingBox.left;
            canvas.drawRoundRect(
                startX,
                boundingBox.top,
                boundingBox.right,
                boundingBox.bottom,
                radius,
                radius,
                paint);

            canvas.save();
            path.addRoundRect(
                startX,
                boundingBox.top,
                boundingBox.right,
                boundingBox.bottom,
                radius,
                radius,
                Path.Direction.CW);
            canvas.clipPath(path);
            startX -= scrollOffset % elementSize;

            for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
              int index = i % range.max;
              paint.setStyle(Paint.Style.STROKE);
              paint.setColor(oldColor);

              if (startX > boundingBox.left && startX < boundingBox.right)
                canvas.drawLine(startX, lineTop, startX, lineBottom, paint);
              String text = getRangeTextForIndex(range, index);

              if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(primaryColor);
                paint.setTextSize(
                    Math.min(
                        getTextSizeForWidth(paint, text, elementSize - strokeWidth * 2),
                        minTextSize));
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(
                    text,
                    startX + elementSize * 0.5f,
                    (y - ((paint.descent() + paint.ascent()) * 0.5f)),
                    paint);
              }
              startX += elementSize;
            }

            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(oldColor);
            canvas.restore();
          } else {
            float lineLeft = boundingBox.left + strokeWidth * 0.5f;
            float lineRight = boundingBox.right - strokeWidth * 0.5f;
            float startY = boundingBox.top;
            canvas.drawRoundRect(
                boundingBox.left,
                startY,
                boundingBox.right,
                boundingBox.bottom,
                radius,
                radius,
                paint);

            canvas.save();
            path.addRoundRect(
                boundingBox.left,
                startY,
                boundingBox.right,
                boundingBox.bottom,
                radius,
                radius,
                Path.Direction.CW);
            canvas.clipPath(path);
            startY -= scrollOffset % elementSize;

            for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
              paint.setStyle(Paint.Style.STROKE);
              paint.setColor(oldColor);

              if (startY > boundingBox.top && startY < boundingBox.bottom)
                canvas.drawLine(lineLeft, startY, lineRight, startY, paint);
              String text = getRangeTextForIndex(range, i);

              if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(primaryColor);
                paint.setTextSize(
                    Math.min(
                        getTextSizeForWidth(paint, text, boundingBox.width() - strokeWidth * 2),
                        minTextSize));
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(
                    text,
                    x,
                    startY + elementSize * 0.5f - ((paint.descent() + paint.ascent()) * 0.5f),
                    paint);
              }
              startY += elementSize;
            }

            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(oldColor);
            canvas.restore();
          }
          break;
        }
      case STICK:
        {
          int cx = boundingBox.centerX(); // Fixed outer circle center
          int cy = boundingBox.centerY(); // Fixed outer circle center
          int oldColor = paint.getColor();

          // Draw the outer circle (base of the stick)
          canvas.drawCircle(cx, cy, boundingBox.height() * 0.5f, paint);

          // Draw the inner thumbstick (current position based on gyroscope movement)
          float thumbstickX = getCurrentPosition().x;
          float thumbstickY = getCurrentPosition().y;

          short thumbRadius = (short) (snappingSize * 3.5f * scale);
          int engagedAlpha = isEngaged() ? 120 : 50;
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(ColorUtils.setAlphaComponent(primaryColor, engagedAlpha));
          canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius, paint); // Draw thumbstick

          // Draw the thumbstick border
          paint.setStyle(Paint.Style.STROKE);
          paint.setColor(oldColor);
          canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius + strokeWidth * 0.5f, paint);
          break;
        }

      case TRACKPAD:
        {
          float radius = boundingBox.height() * 0.15f;
          canvas.drawRoundRect(
              boundingBox.left,
              boundingBox.top,
              boundingBox.right,
              boundingBox.bottom,
              radius,
              radius,
              paint);
          float offset = strokeWidth * 2.5f;
          float innerStrokeWidth = strokeWidth * 2;
          float innerHeight = boundingBox.height() - offset * 2;
          radius =
              (innerHeight / boundingBox.height()) * radius
                  - (innerStrokeWidth * 0.5f + strokeWidth * 0.5f);
          paint.setStrokeWidth(innerStrokeWidth);
          canvas.drawRoundRect(
              boundingBox.left + offset,
              boundingBox.top + offset,
              boundingBox.right - offset,
              boundingBox.bottom - offset,
              radius,
              radius,
              paint);
          break;
        }
    }
  }

  /** Builds an outward-pointing arrow triangle for D-pad direction {@code dir} (0=up..3=left). */
  private static void buildDpadArrowPath(Path p, float ax, float ay, float t, int dir) {
    p.reset();
    if (dir == 0) {
      p.moveTo(ax, ay - t);
      p.lineTo(ax + 0.85f * t, ay + 0.65f * t);
      p.lineTo(ax - 0.85f * t, ay + 0.65f * t);
    } else if (dir == 1) {
      p.moveTo(ax + t, ay);
      p.lineTo(ax - 0.65f * t, ay + 0.85f * t);
      p.lineTo(ax - 0.65f * t, ay - 0.85f * t);
    } else if (dir == 2) {
      p.moveTo(ax, ay + t);
      p.lineTo(ax - 0.85f * t, ay - 0.65f * t);
      p.lineTo(ax + 0.85f * t, ay - 0.65f * t);
    } else {
      p.moveTo(ax - t, ay);
      p.lineTo(ax + 0.65f * t, ay - 0.85f * t);
      p.lineTo(ax + 0.65f * t, ay + 0.85f * t);
    }
    p.close();
  }

  private float cleanCornerRadius(Rect bb) {
    switch (shape) {
      case ROUND_RECT:
        return bb.height() * 0.5f;
      case SQUARE:
        return Math.min(bb.width(), bb.height()) * 0.28f;
      case RECT:
        return bb.height() * 0.22f;
      default:
        return 0f;
    }
  }

  private void drawCleanBody(Canvas canvas, Paint paint, Rect bb, float inset) {
    if (shape == Shape.CIRCLE) {
      canvas.drawCircle(bb.exactCenterX(), bb.exactCenterY(), bb.width() * 0.5f - inset, paint);
    } else {
      float r = Math.max(2f, cleanCornerRadius(bb) - inset);
      canvas.drawRoundRect(
          bb.left + inset, bb.top + inset, bb.right - inset, bb.bottom - inset, r, r, paint);
    }
  }

  /** SLATE/HALO/GLINT — flat graphite bodies with the accent carried by outline, indicators and labels. */
  private void drawClean(Canvas canvas, VisualStyle style) {
    int snappingSize = inputControlsView.getSnappingSize();
    Paint paint = inputControlsView.getPaint();
    float effectiveOpacity = inputControlsView.isEditMode() ? Math.max(0.15f, opacity) : opacity;
    float overlayOpacity = inputControlsView.getOverlayOpacity();
    float dim = overlayOpacity <= 0.4f
        ? 0.28f + (overlayOpacity - 0.1f) * (0.5f / 0.3f)
        : 0.78f + (overlayOpacity - 0.4f) * (0.22f / 0.6f);
    float a = Mathf.clamp(dim, 0f, 1f) * effectiveOpacity;
    boolean engaged = isEngaged();
    Rect boundingBox = getBoundingBox();
    int accent = resolveThemedAccent();
    int bodyColor = Color.argb((int) (150 * a), 0x14, 0x18, 0x1F);
    float hairline = Math.max(1.5f, snappingSize * 0.09f * scale);
    boolean halo = style == VisualStyle.HALO;
    boolean glint = style == VisualStyle.GLINT;

    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStrokeCap(Paint.Cap.BUTT);

    int frameColor;
    float frameWidth;
    if (halo) {
      frameColor = ColorUtils.setAlphaComponent(accent, (int) ((engaged ? 250 : 215) * a));
      frameWidth = hairline * (engaged ? 2.8f : 1.9f);
    } else if (glint) {
      frameColor = engaged
          ? ColorUtils.setAlphaComponent(accent, (int) (245 * a))
          : Color.argb((int) (55 * a), 255, 255, 255);
      frameWidth = hairline * (engaged ? 1.8f : 1f);
    } else {
      frameColor = ColorUtils.setAlphaComponent(accent, (int) ((engaged ? 240 : 150) * a));
      frameWidth = hairline * (engaged ? 1.6f : 1f);
    }
    if (selected && resolveAccentColor() == -1) {
      frameColor =
          ColorUtils.setAlphaComponent(inputControlsView.getSecondaryColor(), (int) (235 * a));
    }
    float frameInset = halo ? hairline * 2.5f : 0f;
    int labelColor = (halo || engaged)
        ? Color.argb((int) (240 * a), 255, 255, 255)
        : ColorUtils.setAlphaComponent(accent, (int) (235 * a));
    ColorFilter iconTint =
        (halo || engaged) ? inputControlsView.getColorFilter() : accentFilter(accent);

    switch (type) {
      case BUTTON: {
        float cx = boundingBox.centerX();
        float cy = boundingBox.centerY();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(bodyColor);
        drawCleanBody(canvas, paint, boundingBox, 0f);
        if (engaged) {
          if (!halo && !glint) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (105 * a)));
            drawCleanBody(canvas, paint, boundingBox, 0f);
          }
          float bloomRadius = Math.min(boundingBox.width(), boundingBox.height()) * 0.52f;
          beginBloom(paint, cx, cy, bloomRadius, accent, a * 0.9f);
          drawCleanBody(canvas, paint, boundingBox, 0f);
          endBloom(paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(frameWidth);
        paint.setColor(frameColor);
        drawCleanBody(canvas, paint, boundingBox, frameInset);

        if (glint && !engaged) {
          paint.setStrokeCap(Paint.Cap.ROUND);
          paint.setStrokeWidth(hairline * 1.9f);
          paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (220 * a)));
          if (shape == Shape.CIRCLE) {
            tempRect.set(
                boundingBox.left + hairline,
                boundingBox.top + hairline,
                boundingBox.right - hairline,
                boundingBox.bottom - hairline);
            canvas.drawArc(tempRect, 62, 56, false, paint);
          } else {
            float lineY = boundingBox.bottom - hairline * 2.2f;
            float halfLength = boundingBox.width() * 0.21f;
            canvas.drawLine(cx - halfLength, lineY, cx + halfLength, lineY, paint);
          }
          paint.setStrokeCap(Paint.Cap.BUTT);
        }

        if (iconId > 0) {
          paint.setColor(labelColor);
          drawIcon(canvas, cx, cy, boundingBox.width(), boundingBox.height(), iconId, true, iconTint);
        } else {
          String label = getDisplayText();
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(labelColor);
          paint.setTextSize(
              Math.min(
                  getTextSizeForWidth(paint, label, boundingBox.width() - hairline * 4),
                  snappingSize * 2 * scale));
          paint.setTextAlign(Paint.Align.CENTER);
          canvas.drawText(label, x, (y - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
        }
        break;
      }
      case D_PAD: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float base = Math.min(boundingBox.width(), boundingBox.height());
        float half = base * 0.5f;
        float arm = base * 0.17f;
        path.reset();
        path.moveTo(cx - arm, cy - half);
        path.lineTo(cx + arm, cy - half);
        path.lineTo(cx + arm, cy - arm);
        path.lineTo(cx + half, cy - arm);
        path.lineTo(cx + half, cy + arm);
        path.lineTo(cx + arm, cy + arm);
        path.lineTo(cx + arm, cy + half);
        path.lineTo(cx - arm, cy + half);
        path.lineTo(cx - arm, cy + arm);
        path.lineTo(cx - half, cy + arm);
        path.lineTo(cx - half, cy - arm);
        path.lineTo(cx - arm, cy - arm);
        path.close();

        paint.setPathEffect(cornerEffect(base * 0.09f));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(bodyColor);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(frameWidth);
        paint.setColor(frameColor);
        canvas.drawPath(path, paint);
        paint.setPathEffect(null);

        boolean hasStates = engaged && states.length >= 4;
        for (int i = 0; i < 4; i++) {
          int dx = i == 1 ? 1 : i == 3 ? -1 : 0;
          int dy = i == 2 ? 1 : i == 0 ? -1 : 0;
          boolean hot = hasStates && states[i];
          if (hot) {
            float bx = cx + dx * half * 0.55f;
            float by = cy + dy * half * 0.55f;
            beginBloom(paint, bx, by, base * 0.24f, accent, a);
            canvas.drawCircle(bx, by, base * 0.24f, paint);
            endBloom(paint);
          }
          float ax = cx + dx * half * 0.62f;
          float ay = cy + dy * half * 0.62f;
          buildDpadArrowPath(path, ax, ay, base * 0.055f, i);
          paint.setStyle(Paint.Style.FILL);
          if (halo) {
            paint.setColor(hot
                ? ColorUtils.setAlphaComponent(accent, (int) (240 * a))
                : Color.argb((int) (190 * a), 255, 255, 255));
          } else {
            paint.setColor(ColorUtils.setAlphaComponent(accent, (int) ((hot ? 250 : 220) * a)));
          }
          canvas.drawPath(path, paint);
        }

        if (glint) {
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(hairline * 1.9f);
          paint.setStrokeCap(Paint.Cap.ROUND);
          float tickInset = hairline * 1.6f;
          float span = arm * 0.45f;
          for (int i = 0; i < 4; i++) {
            boolean hot = hasStates && states[i];
            paint.setColor(ColorUtils.setAlphaComponent(accent, (int) ((hot ? 245 : 170) * a)));
            if (i == 0) {
              canvas.drawLine(cx - span, cy - half + tickInset, cx + span, cy - half + tickInset, paint);
            } else if (i == 1) {
              canvas.drawLine(cx + half - tickInset, cy - span, cx + half - tickInset, cy + span, paint);
            } else if (i == 2) {
              canvas.drawLine(cx - span, cy + half - tickInset, cx + span, cy + half - tickInset, paint);
            } else {
              canvas.drawLine(cx - half + tickInset, cy - span, cx - half + tickInset, cy + span, paint);
            }
          }
          paint.setStrokeCap(Paint.Cap.BUTT);
        }
        break;
      }
      case STICK: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float r = boundingBox.height() * 0.5f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int) (120 * a), 0x14, 0x18, 0x1F));
        canvas.drawCircle(cx, cy, r, paint);

        float thumbX = getCurrentPosition().x;
        float thumbY = getCurrentPosition().y;
        float thumbRadius = snappingSize * 3.5f * scale;

        if (engaged) {
          beginBloom(paint, thumbX, thumbY, thumbRadius * 1.6f, accent, a * 0.8f);
          canvas.drawCircle(thumbX, thumbY, thumbRadius * 1.6f, paint);
          endBloom(paint);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(frameWidth);
        paint.setColor(frameColor);
        canvas.drawCircle(cx, cy, r - frameInset, paint);

        float guideWidth = Math.max(2f, snappingSize * 0.14f * scale);
        paint.setStrokeWidth(guideWidth);
        if (glint && !engaged) {
          paint.setStrokeCap(Paint.Cap.ROUND);
          paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (110 * a)));
          float arcRadius = r - hairline;
          tempRect.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius);
          canvas.drawArc(tempRect, 62, 56, false, paint);
          paint.setStrokeCap(Paint.Cap.BUTT);
        } else {
          paint.setColor(ColorUtils.setAlphaComponent(accent, (int) ((engaged ? 220 : 110) * a)));
          canvas.drawCircle(cx, cy, r * 0.58f, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int) (200 * a), 0x1C, 0x22, 0x2B));
        canvas.drawCircle(thumbX, thumbY, thumbRadius, paint);
        if (glint) {
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(hairline);
          paint.setColor(Color.argb((int) (55 * a), 255, 255, 255));
          canvas.drawCircle(thumbX, thumbY, thumbRadius, paint);
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (240 * a)));
          canvas.drawCircle(thumbX, thumbY, Math.max(2.5f, snappingSize * 0.3f), paint);
        } else {
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(hairline * (halo ? 2.2f : 1.4f));
          paint.setColor(ColorUtils.setAlphaComponent(accent, (int) ((engaged ? 245 : 150) * a)));
          canvas.drawCircle(thumbX, thumbY, thumbRadius, paint);
        }
        break;
      }
      case TRACKPAD: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float radius = boundingBox.height() * 0.18f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(bodyColor);
        canvas.drawRoundRect(
            boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
            radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(frameWidth);
        paint.setColor(frameColor);
        if (halo) {
          float in = frameInset;
          float fr = Math.max(2f, radius - in);
          canvas.drawRoundRect(
              boundingBox.left + in, boundingBox.top + in,
              boundingBox.right - in, boundingBox.bottom - in,
              fr, fr, paint);
        } else {
          canvas.drawRoundRect(
              boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
              radius, radius, paint);
        }
        if (glint && !engaged) {
          paint.setStrokeCap(Paint.Cap.ROUND);
          paint.setStrokeWidth(hairline * 1.9f);
          paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (220 * a)));
          float lineY = boundingBox.bottom - hairline * 2.2f;
          float halfLength = boundingBox.width() * 0.175f;
          canvas.drawLine(cx - halfLength, lineY, cx + halfLength, lineY, paint);
          paint.setStrokeCap(Paint.Cap.BUTT);
        }

        float s = Math.min(boundingBox.width(), boundingBox.height()) * 0.45f;
        float glyphW = 0.42f * s;
        float glyphH = 0.62f * s;
        float glyphRadius = glyphW * 0.5f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, s * 0.035f));
        paint.setColor(ColorUtils.setAlphaComponent(accent, (int) ((engaged ? 220 : 110) * a)));
        canvas.drawRoundRect(
            cx - glyphW * 0.5f, cy - glyphH * 0.5f, cx + glyphW * 0.5f, cy + glyphH * 0.5f,
            glyphRadius, glyphRadius, paint);
        canvas.drawLine(cx, cy - 0.30f * glyphH, cx, cy - 0.02f * glyphH, paint);
        break;
      }
      case RANGE_BUTTON: {
        Range range = getRange();
        float rr = (orientation == 0 ? boundingBox.height() : boundingBox.width()) * 0.45f;
        float elementSize = scroller.getElementSize();
        float minTextSize = snappingSize * 2 * scale;
        float scrollOffset = scroller.getScrollOffset();
        byte[] rangeIndex = scroller.getRangeIndex();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(bodyColor);
        canvas.drawRoundRect(
            boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
            rr, rr, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(frameWidth);
        paint.setColor(frameColor);
        canvas.drawRoundRect(
            boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
            rr, rr, paint);

        canvas.save();
        path.reset();
        path.addRoundRect(
            boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
            rr, rr, Path.Direction.CW);
        canvas.clipPath(path);

        int pressedIndex =
            currentPointerId != -1 && !scroller.isScrolling() ? scroller.getBindingIndex() : -1;
        int dividerColor = Color.argb((int) (45 * a), 255, 255, 255);
        float dividerWidth = Math.max(1f, snappingSize * 0.06f);
        int hotTextColor = Color.argb((int) (240 * a), 255, 255, 255);
        int cellTextColor = ColorUtils.setAlphaComponent(accent, (int) (225 * a));

        if (orientation == 0) {
          float lineTop = boundingBox.top + boundingBox.height() * 0.25f;
          float lineBottom = boundingBox.bottom - boundingBox.height() * 0.25f;
          float startX = boundingBox.left - (scrollOffset % elementSize);

          for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
            int index = i % range.max;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dividerWidth);
            paint.setColor(dividerColor);
            if (startX > boundingBox.left && startX < boundingBox.right)
              canvas.drawLine(startX, lineTop, startX, lineBottom, paint);

            if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
              boolean hot = index == pressedIndex;
              if (hot) {
                drawCleanHotCell(
                    canvas, paint, style, accent, a, hairline,
                    startX, boundingBox.top, startX + elementSize, boundingBox.bottom);
              }
              String cellText = getRangeTextForIndex(range, index);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(hot ? hotTextColor : cellTextColor);
              paint.setTextSize(
                  Math.min(
                      getTextSizeForWidth(paint, cellText, elementSize - hairline * 4),
                      minTextSize));
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(
                  cellText,
                  startX + elementSize * 0.5f,
                  (y - ((paint.descent() + paint.ascent()) * 0.5f)),
                  paint);
            }
            startX += elementSize;
          }
        } else {
          float lineLeft = boundingBox.left + boundingBox.width() * 0.25f;
          float lineRight = boundingBox.right - boundingBox.width() * 0.25f;
          float startY = boundingBox.top - (scrollOffset % elementSize);

          for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dividerWidth);
            paint.setColor(dividerColor);
            if (startY > boundingBox.top && startY < boundingBox.bottom)
              canvas.drawLine(lineLeft, startY, lineRight, startY, paint);

            if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
              boolean hot = i % range.max == pressedIndex;
              if (hot) {
                drawCleanHotCell(
                    canvas, paint, style, accent, a, hairline,
                    boundingBox.left, startY, boundingBox.right, startY + elementSize);
              }
              String cellText = getRangeTextForIndex(range, i % range.max);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(hot ? hotTextColor : cellTextColor);
              paint.setTextSize(
                  Math.min(
                      getTextSizeForWidth(paint, cellText, boundingBox.width() - hairline * 4),
                      minTextSize));
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(
                  cellText,
                  x,
                  startY + elementSize * 0.5f - ((paint.descent() + paint.ascent()) * 0.5f),
                  paint);
            }
            startY += elementSize;
          }
        }
        canvas.restore();
        break;
      }
      case RADIAL_MENU: {
        float cx = boundingBox.centerX();
        float cy = boundingBox.centerY();
        float radius = boundingBox.width() * 0.5f;

        if (radialMenuExpanded && bindings.length > 0 && radius > 0) {
          float innerRadius = radius + snappingSize * 0.5f;
          float outerRadius = boundingBox.width() + (snappingSize * scale);
          float angleStep = 360.0f / bindings.length;

          if (paths == null || paths.length != bindings.length) {
            paths = new Path[bindings.length];
            RectF outerRect = new RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius);
            RectF innerRect = new RectF(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius);

            for (int i = 0; i < bindings.length; i++) {
              float startAngle = -90.0f + i * angleStep;
              paths[i] = new Path();
              paths[i].arcTo(outerRect, startAngle, angleStep, true);
              paths[i].arcTo(innerRect, startAngle + angleStep, -angleStep, false);
              paths[i].close();
            }
          }

          if (paths != null && paths.length == bindings.length) {
            for (int i = 0; i < bindings.length; i++) {
              boolean active = i == activeRadialBindingIndex;
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(
                  active ? ColorUtils.setAlphaComponent(accent, (int) (80 * a)) : bodyColor);
              canvas.drawPath(paths[i], paint);

              paint.setStyle(Paint.Style.STROKE);
              paint.setStrokeWidth(hairline);
              paint.setColor(Color.argb((int) (50 * a), 255, 255, 255));
              canvas.drawPath(paths[i], paint);

              float middleAngle = (float) Math.toRadians(-90.0f + i * angleStep + angleStep * 0.5f);
              float labelRadius = (innerRadius + outerRadius) * 0.5f;
              float labelX = (float) (cx + Math.cos(middleAngle) * labelRadius);
              float labelY = (float) (cy + Math.sin(middleAngle) * labelRadius);

              String label = getBindingShortText(i);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(active
                  ? Color.argb((int) (240 * a), 255, 255, 255)
                  : ColorUtils.setAlphaComponent(accent, (int) (215 * a)));
              paint.setTextSize(snappingSize * 1.2f * scale);
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(label, labelX, labelY - ((paint.descent() + paint.ascent()) * 0.5f), paint);
            }
          }
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(bodyColor);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(frameWidth);
        paint.setColor(frameColor);
        canvas.drawCircle(cx, cy, radius - frameInset, paint);

        paint.setColor(labelColor);
        drawIcon(
            canvas, cx, cy, boundingBox.width(), boundingBox.height(),
            iconId > 0 ? iconId : 34, true, iconTint);
        break;
      }
    }
    paint.setStrokeJoin(Paint.Join.MITER);
    paint.setStrokeCap(Paint.Cap.BUTT);
  }

  private void drawCleanHotCell(
      Canvas canvas, Paint paint, VisualStyle style, int accent, float a, float hairline,
      float left, float top, float right, float bottom) {
    if (style == VisualStyle.GLINT) {
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(hairline * 2.5f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (240 * a)));
      float cx = (left + right) * 0.5f;
      float halfBar = (right - left) * 0.275f;
      float lineY = bottom - (bottom - top) * 0.12f;
      canvas.drawLine(cx - halfBar, lineY, cx + halfBar, lineY, paint);
      paint.setStrokeCap(Paint.Cap.BUTT);
      return;
    }
    tempRect.set(left + 4, top + 5, right - 4, bottom - 5);
    float r = tempRect.height() * 0.5f;
    if (style == VisualStyle.HALO) {
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(hairline * 1.6f);
      paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (235 * a)));
    } else {
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (70 * a)));
    }
    canvas.drawRoundRect(tempRect, r, r, paint);
  }

  private static void buildChamferRectPath(Path p, float l, float t, float r, float b, float c) {
    p.reset();
    p.moveTo(l + c, t);
    p.lineTo(r - c, t);
    p.lineTo(r, t + c);
    p.lineTo(r, b - c);
    p.lineTo(r - c, b);
    p.lineTo(l + c, b);
    p.lineTo(l, b - c);
    p.lineTo(l, t + c);
    p.close();
  }

  /** Flat-top hexagon (vertices every 60 degrees starting at 0). */
  private static void buildHexagonPath(Path p, float cx, float cy, float r) {
    p.reset();
    for (int i = 0; i < 6; i++) {
      double angle = Math.toRadians(60 * i);
      float px = cx + (float) (r * Math.cos(angle));
      float py = cy + (float) (r * Math.sin(angle));
      if (i == 0) p.moveTo(px, py);
      else p.lineTo(px, py);
    }
    p.close();
  }

  /** One faceted D-pad arm plate, built for "up" then rotated into place. */
  private static void buildReticleArmPath(
      Path p, float cx, float cy, float half, float armHalf, float gap, float chamfer, int dir) {
    p.reset();
    p.moveTo(cx - armHalf + chamfer, cy - half);
    p.lineTo(cx + armHalf - chamfer, cy - half);
    p.lineTo(cx + armHalf, cy - half + chamfer);
    p.lineTo(cx + armHalf, cy - gap - armHalf);
    p.lineTo(cx, cy - gap);
    p.lineTo(cx - armHalf, cy - gap - armHalf);
    p.lineTo(cx - armHalf, cy - half + chamfer);
    p.close();
    if (dir != 0) {
      shaderMatrix.setRotate(90f * dir, cx, cy);
      p.transform(shaderMatrix);
    }
  }

  /** RETICLE — open hairline linework with faceted frames and accent brackets that extend while pressed. */
  private void drawReticle(Canvas canvas) {
    int snappingSize = inputControlsView.getSnappingSize();
    Paint paint = inputControlsView.getPaint();
    float effectiveOpacity = inputControlsView.isEditMode() ? Math.max(0.15f, opacity) : opacity;
    float overlayOpacity = inputControlsView.getOverlayOpacity();
    float dim = overlayOpacity <= 0.4f
        ? 0.28f + (overlayOpacity - 0.1f) * (0.5f / 0.3f)
        : 0.78f + (overlayOpacity - 0.4f) * (0.22f / 0.6f);
    float a = Mathf.clamp(dim, 0f, 1f) * effectiveOpacity;
    boolean engaged = isEngaged();
    Rect boundingBox = getBoundingBox();
    int accent = resolveThemedAccent();
    if (selected && resolveAccentColor() == -1) {
      accent = ColorUtils.setAlphaComponent(inputControlsView.getSecondaryColor(), 255);
    }
    float hairline = Math.max(1.5f, snappingSize * 0.09f * scale);
    float bracket = hairline * 2.2f;
    int lineColor = Color.argb((int) (185 * a), 255, 255, 255);
    int scrimColor = Color.argb((int) (24 * a), 0, 0, 0);
    int accentIdle = ColorUtils.setAlphaComponent(accent, (int) (220 * a));
    int accentFull = ColorUtils.setAlphaComponent(accent, (int) (250 * a));
    int labelColor = engaged ? Color.argb((int) (245 * a), 255, 255, 255) : accentIdle;
    int ghostFill = ColorUtils.setAlphaComponent(accent, (int) (46 * a));
    float minTextSize = snappingSize * 1.9f * scale;
    paint.setStrokeCap(Paint.Cap.BUTT);
    paint.setStrokeJoin(Paint.Join.MITER);

    switch (type) {
      case BUTTON: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float w = boundingBox.width();
        float h = boundingBox.height();
        if (shape == Shape.CIRCLE) {
          float r = w * 0.5f - hairline;
          buildHexagonPath(path, cx, cy, r);
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(scrimColor);
          canvas.drawPath(path, paint);
          if (engaged) {
            paint.setColor(ghostFill);
            canvas.drawPath(path, paint);
          }
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(hairline);
          paint.setColor(lineColor);
          canvas.drawPath(path, paint);
          if (engaged) {
            buildHexagonPath(path, cx, cy, r - hairline * 3f);
            paint.setColor(accentFull);
            canvas.drawPath(path, paint);
          }
          float tick = r * (engaged ? 0.30f : 0.18f);
          paint.setStrokeWidth(bracket * (engaged ? 1.3f : 1f));
          paint.setColor(engaged ? accentFull : accentIdle);
          canvas.drawLine(cx - r, cy, cx - r - tick, cy, paint);
          canvas.drawLine(cx + r, cy, cx + r + tick, cy, paint);
        } else {
          float chamfer = shape == Shape.SQUARE
              ? snappingSize * 0.9f * scale
              : h * 0.35f;
          buildChamferRectPath(
              path, boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
              chamfer);
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(scrimColor);
          canvas.drawPath(path, paint);
          if (engaged) {
            paint.setColor(ghostFill);
            canvas.drawPath(path, paint);
          }
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(hairline);
          paint.setColor(lineColor);
          canvas.drawPath(path, paint);
          float len = Math.min(w, h) * (engaged ? 0.34f : 0.22f);
          paint.setStrokeWidth(engaged ? hairline * 3f : bracket);
          paint.setColor(engaged ? accentFull : accentIdle);
          if (shape == Shape.SQUARE) {
            drawCornerBrackets(canvas, paint, boundingBox, len);
          } else {
            canvas.drawLine(
                boundingBox.left, boundingBox.bottom - chamfer,
                boundingBox.left + chamfer, boundingBox.bottom, paint);
            canvas.drawLine(
                boundingBox.left, boundingBox.top + chamfer,
                boundingBox.left + chamfer, boundingBox.top, paint);
            canvas.drawLine(
                boundingBox.right, boundingBox.bottom - chamfer,
                boundingBox.right - chamfer, boundingBox.bottom, paint);
            canvas.drawLine(
                boundingBox.right, boundingBox.top + chamfer,
                boundingBox.right - chamfer, boundingBox.top, paint);
          }
        }
        if (iconId > 0) {
          drawIcon(
              canvas, cx, cy, w, h, iconId, true,
              engaged ? inputControlsView.getColorFilter() : accentFilter(accent));
        } else {
          String label = getDisplayText();
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(labelColor);
          paint.setLetterSpacing(0.08f);
          paint.setTextSize(
              Math.min(getTextSizeForWidth(paint, label, w * 0.62f), minTextSize));
          paint.setTextAlign(Paint.Align.CENTER);
          canvas.drawText(label, cx, (cy - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
          paint.setLetterSpacing(0f);
        }
        break;
      }
      case D_PAD: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float base = Math.min(boundingBox.width(), boundingBox.height());
        float half = base * 0.5f;
        float armHalf = base * 0.15f;
        float gap = base * 0.10f;
        float chamfer = armHalf * 0.56f;
        boolean hasStates = engaged && states.length >= 4;
        for (int i = 0; i < 4; i++) {
          boolean hot = hasStates && states[i];
          buildReticleArmPath(path, cx, cy, half, armHalf, gap, chamfer, i);
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(hot ? ghostFill : scrimColor);
          canvas.drawPath(path, paint);
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(hairline);
          paint.setColor(lineColor);
          canvas.drawPath(path, paint);
          int dx = i == 1 ? 1 : i == 3 ? -1 : 0;
          int dy = i == 2 ? 1 : i == 0 ? -1 : 0;
          float pos = gap + 0.62f * (half - gap);
          float chevSize = base * 0.045f;
          paint.setStrokeWidth(bracket);
          paint.setColor(hot ? Color.argb((int) (250 * a), 255, 255, 255) : accentIdle);
          int chevrons = hot ? 2 : 1;
          for (int k = 0; k < chevrons; k++) {
            float off = pos + (k - (chevrons - 1) * 0.5f) * chevSize * 2.4f;
            float ax = cx + dx * off;
            float ay = cy + dy * off;
            if (dy != 0) {
              canvas.drawLine(ax - chevSize, ay - dy * chevSize, ax, ay + dy * chevSize, paint);
              canvas.drawLine(ax + chevSize, ay - dy * chevSize, ax, ay + dy * chevSize, paint);
            } else {
              canvas.drawLine(ax - dx * chevSize, ay - chevSize, ax + dx * chevSize, ay, paint);
              canvas.drawLine(ax - dx * chevSize, ay + chevSize, ax + dx * chevSize, ay, paint);
            }
          }
        }
        float dm = base * 0.045f;
        path.reset();
        path.moveTo(cx, cy - dm);
        path.lineTo(cx + dm, cy);
        path.lineTo(cx, cy + dm);
        path.lineTo(cx - dm, cy);
        path.close();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(hairline);
        paint.setColor(accentIdle);
        canvas.drawPath(path, paint);
        break;
      }
      case STICK: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float r = boundingBox.height() * 0.5f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(scrimColor);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(hairline);
        paint.setColor(lineColor);
        canvas.drawCircle(cx, cy, r, paint);
        PointF pos = getCurrentPosition();
        float dxp = pos.x - cx;
        float dyp = pos.y - cy;
        int hotTick = -1;
        if (engaged && (dxp != 0f || dyp != 0f)) {
          hotTick = Math.abs(dxp) >= Math.abs(dyp) ? (dxp >= 0 ? 1 : 3) : (dyp >= 0 ? 2 : 0);
        }
        float tickLen = snappingSize * 0.8f * scale;
        paint.setStrokeWidth(bracket);
        for (int i = 0; i < 4; i++) {
          int dx = i == 1 ? 1 : i == 3 ? -1 : 0;
          int dy = i == 2 ? 1 : i == 0 ? -1 : 0;
          paint.setColor(i == hotTick ? accentFull : accentIdle);
          canvas.drawLine(
              cx + dx * (r - tickLen * 0.5f), cy + dy * (r - tickLen * 0.5f),
              cx + dx * (r + tickLen * 0.5f), cy + dy * (r + tickLen * 0.5f), paint);
        }
        tempRect.set(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f);
        paint.setStrokeWidth(hairline);
        paint.setColor(engaged ? accentFull : accentIdle);
        for (int i = 0; i < 8; i++) {
          canvas.drawArc(tempRect, i * 45f - 9f, 18f, false, paint);
        }
        if (engaged) {
          paint.setColor(accentFull);
          canvas.drawLine(cx, cy, pos.x, pos.y, paint);
        }
        float thumbR = snappingSize * 2.2f * scale;
        buildHexagonPath(path, pos.x, pos.y, thumbR);
        if (engaged) {
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (90 * a)));
          canvas.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(hairline);
        paint.setColor(engaged ? accentFull : accentIdle);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(lineColor);
        canvas.drawCircle(pos.x, pos.y, hairline, paint);
        break;
      }
      case TRACKPAD: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float chamfer = boundingBox.height() * 0.22f;
        buildChamferRectPath(
            path, boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
            chamfer);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(scrimColor);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(hairline);
        paint.setColor(lineColor);
        canvas.drawPath(path, paint);
        float len = Math.min(boundingBox.width(), boundingBox.height()) * 0.22f;
        paint.setStrokeWidth(bracket);
        paint.setColor(accentIdle);
        drawCornerBrackets(canvas, paint, boundingBox, len);
        float gap = hairline * 2.2f;
        float cross = snappingSize * 1.2f * scale;
        paint.setStrokeWidth(hairline);
        paint.setColor(engaged ? accentFull : accentIdle);
        canvas.drawLine(cx - cross, cy, cx - gap, cy, paint);
        canvas.drawLine(cx + gap, cy, cx + cross, cy, paint);
        canvas.drawLine(cx, cy - cross, cx, cy - gap, paint);
        canvas.drawLine(cx, cy + gap, cx, cy + cross, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, hairline, paint);
        break;
      }
      case RANGE_BUTTON: {
        Range range = getRange();
        float chamfer = (orientation == 0 ? boundingBox.height() : boundingBox.width()) * 0.30f;
        float elementSize = scroller.getElementSize();
        float scrollOffset = scroller.getScrollOffset();
        byte[] rangeIndex = scroller.getRangeIndex();
        buildChamferRectPath(
            path, boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
            chamfer);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(scrimColor);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(hairline);
        paint.setColor(lineColor);
        canvas.drawPath(path, paint);

        canvas.save();
        canvas.clipPath(path);
        int pressedIndex =
            currentPointerId != -1 && !scroller.isScrolling() ? scroller.getBindingIndex() : -1;
        int dividerColor = Color.argb((int) (70 * a), 255, 255, 255);
        int hotTextColor = Color.argb((int) (245 * a), 255, 255, 255);
        paint.setLetterSpacing(0.08f);

        if (orientation == 0) {
          float lineTop = boundingBox.top + boundingBox.height() * 0.225f;
          float lineBottom = boundingBox.bottom - boundingBox.height() * 0.225f;
          float startX = boundingBox.left - (scrollOffset % elementSize);
          for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
            int index = i % range.max;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(hairline * 0.8f);
            paint.setColor(dividerColor);
            if (startX > boundingBox.left && startX < boundingBox.right)
              canvas.drawLine(startX, lineTop, startX, lineBottom, paint);
            if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
              boolean hot = index == pressedIndex;
              if (hot) {
                paint.setStrokeWidth(hairline * 2.5f);
                paint.setColor(accentFull);
                float uy = boundingBox.bottom - boundingBox.height() * 0.15f;
                canvas.drawLine(
                    startX + elementSize * 0.2f, uy, startX + elementSize * 0.8f, uy, paint);
              }
              String cellText = getRangeTextForIndex(range, index);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(hot ? hotTextColor : accentIdle);
              paint.setTextSize(
                  Math.min(
                      getTextSizeForWidth(paint, cellText, elementSize - snappingSize * 0.5f),
                      minTextSize));
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(
                  cellText,
                  startX + elementSize * 0.5f,
                  (y - ((paint.descent() + paint.ascent()) * 0.5f)),
                  paint);
            }
            startX += elementSize;
          }
        } else {
          float lineLeft = boundingBox.left + boundingBox.width() * 0.225f;
          float lineRight = boundingBox.right - boundingBox.width() * 0.225f;
          float startY = boundingBox.top - (scrollOffset % elementSize);
          for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
            int index = i % range.max;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(hairline * 0.8f);
            paint.setColor(dividerColor);
            if (startY > boundingBox.top && startY < boundingBox.bottom)
              canvas.drawLine(lineLeft, startY, lineRight, startY, paint);
            if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
              boolean hot = index == pressedIndex;
              if (hot) {
                paint.setStrokeWidth(hairline * 2.5f);
                paint.setColor(accentFull);
                float ux = boundingBox.right - boundingBox.width() * 0.15f;
                canvas.drawLine(
                    ux, startY + elementSize * 0.2f, ux, startY + elementSize * 0.8f, paint);
              }
              String cellText = getRangeTextForIndex(range, index);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(hot ? hotTextColor : accentIdle);
              paint.setTextSize(
                  Math.min(
                      getTextSizeForWidth(paint, cellText, boundingBox.width() - snappingSize * 0.5f),
                      minTextSize));
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(
                  cellText,
                  x,
                  startY + elementSize * 0.5f - ((paint.descent() + paint.ascent()) * 0.5f),
                  paint);
            }
            startY += elementSize;
          }
        }
        paint.setLetterSpacing(0f);
        canvas.restore();
        break;
      }
      case RADIAL_MENU: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float radius = boundingBox.width() * 0.5f;

        if (radialMenuExpanded && bindings.length > 0 && radius > 0) {
          float innerRadius = radius + snappingSize * 0.5f;
          float outerRadius = boundingBox.width() + (snappingSize * scale);
          float angleStep = 360.0f / bindings.length;

          if (paths == null || paths.length != bindings.length) {
            paths = new Path[bindings.length];
            RectF outerRect = new RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius);
            RectF innerRect = new RectF(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius);
            for (int i = 0; i < bindings.length; i++) {
              float startAngle = -90.0f + i * angleStep;
              paths[i] = new Path();
              paths[i].arcTo(outerRect, startAngle, angleStep, true);
              paths[i].arcTo(innerRect, startAngle + angleStep, -angleStep, false);
              paths[i].close();
            }
          }

          if (paths != null && paths.length == bindings.length) {
            for (int i = 0; i < bindings.length; i++) {
              boolean active = i == activeRadialBindingIndex;
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(active
                  ? ColorUtils.setAlphaComponent(accent, (int) (70 * a))
                  : scrimColor);
              canvas.drawPath(paths[i], paint);
              paint.setStyle(Paint.Style.STROKE);
              paint.setStrokeWidth(hairline);
              paint.setColor(lineColor);
              canvas.drawPath(paths[i], paint);

              float middleAngle = (float) Math.toRadians(-90.0f + i * angleStep + angleStep * 0.5f);
              float labelRadius = (innerRadius + outerRadius) * 0.5f;
              float labelX = (float) (cx + Math.cos(middleAngle) * labelRadius);
              float labelY = (float) (cy + Math.sin(middleAngle) * labelRadius);
              String label = getBindingShortText(i);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(active ? Color.argb((int) (240 * a), 255, 255, 255) : accentIdle);
              paint.setTextSize(snappingSize * 1.2f * scale);
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(label, labelX, labelY - ((paint.descent() + paint.ascent()) * 0.5f), paint);
            }
          }
        }

        buildHexagonPath(path, cx, cy, radius - hairline);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(engaged ? ghostFill : scrimColor);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(hairline);
        paint.setColor(lineColor);
        canvas.drawPath(path, paint);
        drawIcon(
            canvas, cx, cy, boundingBox.width(), boundingBox.height(),
            iconId > 0 ? iconId : 34, true,
            engaged ? inputControlsView.getColorFilter() : accentFilter(accent));
        break;
      }
    }
  }

  /** L-shaped corner brackets hugging the four corners of {@code bb}. */
  private static void drawCornerBrackets(Canvas canvas, Paint paint, Rect bb, float len) {
    canvas.drawLine(bb.left, bb.top, bb.left + len, bb.top, paint);
    canvas.drawLine(bb.left, bb.top, bb.left, bb.top + len, paint);
    canvas.drawLine(bb.right, bb.top, bb.right - len, bb.top, paint);
    canvas.drawLine(bb.right, bb.top, bb.right, bb.top + len, paint);
    canvas.drawLine(bb.left, bb.bottom, bb.left + len, bb.bottom, paint);
    canvas.drawLine(bb.left, bb.bottom, bb.left, bb.bottom - len, paint);
    canvas.drawLine(bb.right, bb.bottom, bb.right - len, bb.bottom, paint);
    canvas.drawLine(bb.right, bb.bottom, bb.right, bb.bottom - len, paint);
  }

  private static final int NEON_SCRIM = 0xFF0A0A16;
  private static final float[] hsvTemp = new float[3];

  /** Contrasting partner color for the neon accent (hue rotated 150 degrees). */
  private static int neonSecondary(int accent) {
    Color.colorToHSV(accent, hsvTemp);
    hsvTemp[0] = (hsvTemp[0] + 150f) % 360f;
    return Color.HSVToColor(Color.alpha(accent), hsvTemp);
  }

  /** Layered-stroke glow around {@code p}: wide faint halos under a crisp core line. */
  private void neonStrokePath(
      Canvas canvas, Paint paint, Path p, int color, float core, float a, boolean pressed) {
    paint.setStyle(Paint.Style.STROKE);
    paint.setColor(ColorUtils.setAlphaComponent(color, (int) (18 * a)));
    paint.setStrokeWidth(core * 4.2f);
    canvas.drawPath(p, paint);
    paint.setColor(ColorUtils.setAlphaComponent(color, (int) (36 * a)));
    paint.setStrokeWidth(core * 2.6f);
    canvas.drawPath(p, paint);
    paint.setColor(ColorUtils.setAlphaComponent(color, (int) (66 * a)));
    paint.setStrokeWidth(core * 1.6f);
    canvas.drawPath(p, paint);
    if (pressed) {
      paint.setColor(ColorUtils.setAlphaComponent(color, (int) (26 * a)));
      paint.setStrokeWidth(core * 6f);
      canvas.drawPath(p, paint);
    }
    paint.setColor(ColorUtils.setAlphaComponent(color, (int) (235 * a)));
    paint.setStrokeWidth(core);
    canvas.drawPath(p, paint);
    if (pressed) {
      paint.setColor(Color.argb((int) (235 * a), 255, 255, 255));
      paint.setStrokeWidth(core * 0.55f);
      canvas.drawPath(p, paint);
    }
  }

  /** NEON — hollow chamfered wireframes with a layered-stroke glow that ignites white-hot on press. */
  private void drawNeon(Canvas canvas) {
    int snappingSize = inputControlsView.getSnappingSize();
    Paint paint = inputControlsView.getPaint();
    float effectiveOpacity = inputControlsView.isEditMode() ? Math.max(0.15f, opacity) : opacity;
    float overlayOpacity = inputControlsView.getOverlayOpacity();
    float dim = overlayOpacity <= 0.4f
        ? 0.28f + (overlayOpacity - 0.1f) * (0.5f / 0.3f)
        : 0.78f + (overlayOpacity - 0.4f) * (0.22f / 0.6f);
    float a = Mathf.clamp(dim, 0f, 1f) * effectiveOpacity;
    boolean engaged = isEngaged();
    Rect boundingBox = getBoundingBox();
    int accent = resolveThemedAccent();
    if (selected && resolveAccentColor() == -1) {
      accent = ColorUtils.setAlphaComponent(inputControlsView.getSecondaryColor(), 255);
    }
    int secondary = neonSecondary(accent);
    float core = Math.max(1.5f, snappingSize * 0.14f * scale);
    int scrimColor = ColorUtils.setAlphaComponent(NEON_SCRIM, (int) (61 * a));
    int washColor = ColorUtils.setAlphaComponent(accent, (int) (33 * a));
    int labelColor = engaged
        ? Color.argb((int) (245 * a), 255, 255, 255)
        : ColorUtils.setAlphaComponent(accent, (int) (235 * a));
    float minTextSize = snappingSize * 2 * scale;
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);

    switch (type) {
      case BUTTON: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float w = boundingBox.width();
        float h = boundingBox.height();
        if (shape == Shape.CIRCLE) {
          float r = w * 0.5f - core;
          buildChamferRectPath(path, cx - r, cy - r, cx + r, cy + r, r * 0.586f);
        } else {
          float chamfer = shape == Shape.SQUARE
              ? snappingSize * 0.8f * scale
              : shape == Shape.RECT ? h * 0.25f : h * 0.30f;
          buildChamferRectPath(
              path, boundingBox.left + core, boundingBox.top + core,
              boundingBox.right - core, boundingBox.bottom - core, chamfer);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(scrimColor);
        canvas.drawPath(path, paint);
        if (engaged) {
          paint.setColor(washColor);
          canvas.drawPath(path, paint);
        }
        neonStrokePath(canvas, paint, path, accent, core, a, engaged);
        if (shape == Shape.CIRCLE) {
          float r = w * 0.5f - core;
          tempRect.set(
              cx - r - core * 1.8f, cy - r - core * 1.8f,
              cx + r + core * 1.8f, cy + r + core * 1.8f);
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(core * 0.7f);
          paint.setColor(ColorUtils.setAlphaComponent(secondary, (int) (150 * a)));
          canvas.drawArc(tempRect, -80f, 60f, false, paint);
        }
        if (iconId > 0) {
          drawIcon(
              canvas, cx, cy, w, h, iconId, true,
              engaged ? inputControlsView.getColorFilter() : accentFilter(accent));
        } else {
          String label = getDisplayText();
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(labelColor);
          paint.setTextSize(
              Math.min(getTextSizeForWidth(paint, label, w * 0.62f), minTextSize));
          paint.setTextAlign(Paint.Align.CENTER);
          canvas.drawText(label, cx, (cy - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
        }
        break;
      }
      case D_PAD: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float base = Math.min(boundingBox.width(), boundingBox.height());
        float half = base * 0.5f - core;
        float arm = base * 0.17f;
        float ch = base * 0.075f;
        path.reset();
        path.moveTo(cx - arm, cy - half + ch);
        path.lineTo(cx - arm + ch, cy - half);
        path.lineTo(cx + arm - ch, cy - half);
        path.lineTo(cx + arm, cy - half + ch);
        path.lineTo(cx + arm, cy - arm);
        path.lineTo(cx + half - ch, cy - arm);
        path.lineTo(cx + half, cy - arm + ch);
        path.lineTo(cx + half, cy + arm - ch);
        path.lineTo(cx + half - ch, cy + arm);
        path.lineTo(cx + arm, cy + arm);
        path.lineTo(cx + arm, cy + half - ch);
        path.lineTo(cx + arm - ch, cy + half);
        path.lineTo(cx - arm + ch, cy + half);
        path.lineTo(cx - arm, cy + half - ch);
        path.lineTo(cx - arm, cy + arm);
        path.lineTo(cx - half + ch, cy + arm);
        path.lineTo(cx - half, cy + arm - ch);
        path.lineTo(cx - half, cy - arm + ch);
        path.lineTo(cx - half + ch, cy - arm);
        path.lineTo(cx - arm, cy - arm);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(scrimColor);
        canvas.drawPath(path, paint);
        neonStrokePath(canvas, paint, path, accent, core, a, false);

        boolean hasStates = engaged && states.length >= 4;
        float chev = base * 0.043f;
        for (int i = 0; i < 4; i++) {
          boolean hot = hasStates && states[i];
          int dx = i == 1 ? 1 : i == 3 ? -1 : 0;
          int dy = i == 2 ? 1 : i == 0 ? -1 : 0;
          float ax = cx + dx * half * 0.60f;
          float ay = cy + dy * half * 0.60f;
          paint.setStyle(Paint.Style.STROKE);
          if (hot) {
            paint.setStrokeWidth(core * 2.8f);
            paint.setColor(ColorUtils.setAlphaComponent(secondary, (int) (90 * a)));
            if (dy != 0) {
              canvas.drawLine(ax - chev, ay - dy * chev, ax, ay + dy * chev, paint);
              canvas.drawLine(ax + chev, ay - dy * chev, ax, ay + dy * chev, paint);
            } else {
              canvas.drawLine(ax - dx * chev, ay - chev, ax + dx * chev, ay, paint);
              canvas.drawLine(ax - dx * chev, ay + chev, ax + dx * chev, ay, paint);
            }
          }
          paint.setStrokeWidth(core);
          paint.setColor(hot
              ? Color.argb((int) (245 * a), 255, 255, 255)
              : ColorUtils.setAlphaComponent(secondary, (int) (190 * a)));
          if (dy != 0) {
            canvas.drawLine(ax - chev, ay - dy * chev, ax, ay + dy * chev, paint);
            canvas.drawLine(ax + chev, ay - dy * chev, ax, ay + dy * chev, paint);
          } else {
            canvas.drawLine(ax - dx * chev, ay - chev, ax + dx * chev, ay, paint);
            canvas.drawLine(ax - dx * chev, ay + chev, ax + dx * chev, ay, paint);
          }
        }
        float dm = base * 0.055f;
        path.reset();
        path.moveTo(cx, cy - dm);
        path.lineTo(cx + dm, cy);
        path.lineTo(cx, cy + dm);
        path.lineTo(cx - dm, cy);
        path.close();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(core * 0.7f);
        paint.setColor(ColorUtils.setAlphaComponent(secondary, (int) (100 * a)));
        canvas.drawPath(path, paint);
        break;
      }
      case STICK: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float r = boundingBox.height() * 0.5f - core;
        path.reset();
        path.addCircle(cx, cy, r, Path.Direction.CW);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ColorUtils.setAlphaComponent(NEON_SCRIM, (int) (46 * a)));
        canvas.drawPath(path, paint);
        neonStrokePath(canvas, paint, path, accent, core * 0.8f, a, false);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(core * 0.8f);
        paint.setColor(ColorUtils.setAlphaComponent(secondary, (int) (165 * a)));
        float tick = snappingSize * 0.5f * scale;
        canvas.drawLine(cx, cy - r - tick, cx, cy - r + tick, paint);
        canvas.drawLine(cx, cy + r - tick, cx, cy + r + tick, paint);
        canvas.drawLine(cx - r - tick, cy, cx - r + tick, cy, paint);
        canvas.drawLine(cx + r - tick, cy, cx + r + tick, cy, paint);
        paint.setStrokeWidth(Math.max(1f, core * 0.5f));
        paint.setColor(ColorUtils.setAlphaComponent(secondary, (int) (150 * a)));
        canvas.drawCircle(cx, cy, r * 0.55f, paint);
        PointF pos = getCurrentPosition();
        float thumbR = snappingSize * 2.6f * scale;
        path.reset();
        path.addCircle(pos.x, pos.y, thumbR, Path.Direction.CW);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(engaged ? washColor : scrimColor);
        canvas.drawPath(path, paint);
        neonStrokePath(canvas, paint, path, accent, core * 0.8f, a, engaged);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int) (240 * a), 255, 255, 255));
        canvas.drawCircle(pos.x, pos.y, core, paint);
        break;
      }
      case TRACKPAD: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float chamfer = boundingBox.height() * 0.20f;
        buildChamferRectPath(
            path, boundingBox.left + core, boundingBox.top + core,
            boundingBox.right - core, boundingBox.bottom - core, chamfer);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(scrimColor);
        canvas.drawPath(path, paint);
        neonStrokePath(canvas, paint, path, accent, core, a, engaged);
        float dm = snappingSize * 1.1f * scale;
        path.reset();
        path.moveTo(cx, cy - dm);
        path.lineTo(cx + dm, cy);
        path.lineTo(cx, cy + dm);
        path.lineTo(cx - dm, cy);
        path.close();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(core * 0.7f);
        paint.setColor(ColorUtils.setAlphaComponent(secondary, (int) ((engaged ? 220 : 150) * a)));
        canvas.drawPath(path, paint);
        break;
      }
      case RANGE_BUTTON: {
        Range range = getRange();
        float chamfer = (orientation == 0 ? boundingBox.height() : boundingBox.width()) * 0.30f;
        float elementSize = scroller.getElementSize();
        float scrollOffset = scroller.getScrollOffset();
        byte[] rangeIndex = scroller.getRangeIndex();
        buildChamferRectPath(
            path, boundingBox.left + core, boundingBox.top + core,
            boundingBox.right - core, boundingBox.bottom - core, chamfer);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(scrimColor);
        canvas.drawPath(path, paint);
        neonStrokePath(canvas, paint, path, accent, core, a, false);

        canvas.save();
        canvas.clipPath(path);
        int pressedIndex =
            currentPointerId != -1 && !scroller.isScrolling() ? scroller.getBindingIndex() : -1;
        int dividerColor = ColorUtils.setAlphaComponent(accent, (int) (80 * a));
        int hotTextColor = Color.argb((int) (245 * a), 255, 255, 255);
        int cellTextColor = ColorUtils.setAlphaComponent(accent, (int) (220 * a));
        int hotFill = ColorUtils.setAlphaComponent(secondary, (int) (41 * a));

        if (orientation == 0) {
          float lineTop = boundingBox.top + boundingBox.height() * 0.2f;
          float lineBottom = boundingBox.bottom - boundingBox.height() * 0.2f;
          float startX = boundingBox.left - (scrollOffset % elementSize);
          for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
            int index = i % range.max;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, core * 0.4f));
            paint.setColor(dividerColor);
            if (startX > boundingBox.left && startX < boundingBox.right)
              canvas.drawLine(startX, lineTop, startX, lineBottom, paint);
            if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
              boolean hot = index == pressedIndex;
              if (hot) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(hotFill);
                canvas.drawRect(
                    startX, boundingBox.top, startX + elementSize, boundingBox.bottom, paint);
              }
              String cellText = getRangeTextForIndex(range, index);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(hot ? hotTextColor : cellTextColor);
              paint.setTextSize(
                  Math.min(
                      getTextSizeForWidth(paint, cellText, elementSize - snappingSize * 0.5f),
                      minTextSize));
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(
                  cellText,
                  startX + elementSize * 0.5f,
                  (y - ((paint.descent() + paint.ascent()) * 0.5f)),
                  paint);
            }
            startX += elementSize;
          }
        } else {
          float lineLeft = boundingBox.left + boundingBox.width() * 0.2f;
          float lineRight = boundingBox.right - boundingBox.width() * 0.2f;
          float startY = boundingBox.top - (scrollOffset % elementSize);
          for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
            int index = i % range.max;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, core * 0.4f));
            paint.setColor(dividerColor);
            if (startY > boundingBox.top && startY < boundingBox.bottom)
              canvas.drawLine(lineLeft, startY, lineRight, startY, paint);
            if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
              boolean hot = index == pressedIndex;
              if (hot) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(hotFill);
                canvas.drawRect(
                    boundingBox.left, startY, boundingBox.right, startY + elementSize, paint);
              }
              String cellText = getRangeTextForIndex(range, index);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(hot ? hotTextColor : cellTextColor);
              paint.setTextSize(
                  Math.min(
                      getTextSizeForWidth(paint, cellText, boundingBox.width() - snappingSize * 0.5f),
                      minTextSize));
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(
                  cellText,
                  x,
                  startY + elementSize * 0.5f - ((paint.descent() + paint.ascent()) * 0.5f),
                  paint);
            }
            startY += elementSize;
          }
        }
        canvas.restore();
        break;
      }
      case RADIAL_MENU: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float radius = boundingBox.width() * 0.5f;

        if (radialMenuExpanded && bindings.length > 0 && radius > 0) {
          float innerRadius = radius + snappingSize * 0.5f;
          float outerRadius = boundingBox.width() + (snappingSize * scale);
          float angleStep = 360.0f / bindings.length;

          if (paths == null || paths.length != bindings.length) {
            paths = new Path[bindings.length];
            RectF outerRect = new RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius);
            RectF innerRect = new RectF(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius);
            for (int i = 0; i < bindings.length; i++) {
              float startAngle = -90.0f + i * angleStep;
              paths[i] = new Path();
              paths[i].arcTo(outerRect, startAngle, angleStep, true);
              paths[i].arcTo(innerRect, startAngle + angleStep, -angleStep, false);
              paths[i].close();
            }
          }

          if (paths != null && paths.length == bindings.length) {
            for (int i = 0; i < bindings.length; i++) {
              boolean active = i == activeRadialBindingIndex;
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(active
                  ? ColorUtils.setAlphaComponent(secondary, (int) (55 * a))
                  : scrimColor);
              canvas.drawPath(paths[i], paint);
              paint.setStyle(Paint.Style.STROKE);
              paint.setStrokeWidth(Math.max(1f, core * 0.5f));
              paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (110 * a)));
              canvas.drawPath(paths[i], paint);

              float middleAngle = (float) Math.toRadians(-90.0f + i * angleStep + angleStep * 0.5f);
              float labelRadius = (innerRadius + outerRadius) * 0.5f;
              float labelX = (float) (cx + Math.cos(middleAngle) * labelRadius);
              float labelY = (float) (cy + Math.sin(middleAngle) * labelRadius);
              String label = getBindingShortText(i);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(active
                  ? Color.argb((int) (240 * a), 255, 255, 255)
                  : ColorUtils.setAlphaComponent(accent, (int) (215 * a)));
              paint.setTextSize(snappingSize * 1.2f * scale);
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(label, labelX, labelY - ((paint.descent() + paint.ascent()) * 0.5f), paint);
            }
          }
        }

        path.reset();
        path.addCircle(cx, cy, radius - core, Path.Direction.CW);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(engaged ? washColor : scrimColor);
        canvas.drawPath(path, paint);
        neonStrokePath(canvas, paint, path, accent, core, a, engaged);
        drawIcon(
            canvas, cx, cy, boundingBox.width(), boundingBox.height(),
            iconId > 0 ? iconId : 34, true,
            engaged ? inputControlsView.getColorFilter() : accentFilter(accent));
        break;
      }
    }
    paint.setStrokeCap(Paint.Cap.BUTT);
    paint.setStrokeJoin(Paint.Join.MITER);
  }

  private static final int LUMINA_RIM = 0xFFC7D0DA;
  private static final int LUMINA_TEXT = 0xFFF4F7FA;
  private static Shader luminaBodyShader;
  private static Shader luminaShadeShader;
  private static Shader luminaSpecularShader;
  private static Shader luminaDomeShader;

  private static Shader getLuminaBodyShader() {
    if (luminaBodyShader == null) {
      luminaBodyShader = new android.graphics.LinearGradient(
          0f, 0f, 0f, 1f, 0xFF2E343C, 0xB512151A, Shader.TileMode.CLAMP);
    }
    return luminaBodyShader;
  }

  private static Shader getLuminaShadeShader() {
    if (luminaShadeShader == null) {
      luminaShadeShader = new android.graphics.LinearGradient(
          0f, 0f, 0f, 1f,
          new int[] {0x00000000, 0x00000000, 0x4D000000},
          new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
    }
    return luminaShadeShader;
  }

  private static Shader getLuminaSpecularShader() {
    if (luminaSpecularShader == null) {
      luminaSpecularShader = new android.graphics.LinearGradient(
          0f, 0f, 0f, 1f,
          new int[] {0xFFFFFFFF, 0x00FFFFFF, 0x00FFFFFF},
          new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
    }
    return luminaSpecularShader;
  }

  private static Shader getLuminaDomeShader() {
    if (luminaDomeShader == null) {
      luminaDomeShader = new RadialGradient(
          0f, 0f, 1f,
          new int[] {0x64FFFFFF, 0xB92E343C, 0xE112151A},
          new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
    }
    return luminaDomeShader;
  }

  /** Draws the frosted plate passes (body gradient + bottom shade) for the element's shape. */
  private void luminaGlassBody(Canvas canvas, Paint paint, Rect bb, float a) {
    paint.setStyle(Paint.Style.FILL);
    placeShader(getLuminaBodyShader(), bb.left, bb.top, Math.max(1, bb.height()));
    paint.setShader(getLuminaBodyShader());
    paint.setAlpha((int) (170 * a));
    drawCleanBody(canvas, paint, bb, 0f);
    placeShader(getLuminaShadeShader(), bb.left, bb.top, Math.max(1, bb.height()));
    paint.setShader(getLuminaShadeShader());
    paint.setAlpha((int) (255 * a));
    drawCleanBody(canvas, paint, bb, 0f);
    paint.setShader(null);
  }

  /** LUMINA — frosted glass plates: gradient body, silver hairline rim, top specular, accent light on press. */
  private void drawLumina(Canvas canvas) {
    int snappingSize = inputControlsView.getSnappingSize();
    Paint paint = inputControlsView.getPaint();
    float effectiveOpacity = inputControlsView.isEditMode() ? Math.max(0.15f, opacity) : opacity;
    float overlayOpacity = inputControlsView.getOverlayOpacity();
    float dim = overlayOpacity <= 0.4f
        ? 0.28f + (overlayOpacity - 0.1f) * (0.5f / 0.3f)
        : 0.78f + (overlayOpacity - 0.4f) * (0.22f / 0.6f);
    float a = Mathf.clamp(dim, 0f, 1f) * effectiveOpacity;
    boolean engaged = isEngaged();
    Rect boundingBox = getBoundingBox();
    int custom = resolveAccentColor();
    int accent = resolveThemedAccent();
    if (selected && custom == -1) {
      accent = ColorUtils.setAlphaComponent(inputControlsView.getSecondaryColor(), 255);
    }
    float hairline = Math.max(1.5f, snappingSize * 0.11f * scale);
    int rimIdle = custom != -1 ? ColorUtils.blendARGB(LUMINA_RIM, custom, 0.25f) : LUMINA_RIM;
    int rimColor = engaged
        ? ColorUtils.setAlphaComponent(accent, (int) (235 * a))
        : ColorUtils.setAlphaComponent(rimIdle, (int) (150 * a));
    int labelColor = engaged
        ? Color.argb((int) (250 * a), 255, 255, 255)
        : ColorUtils.setAlphaComponent(accent, (int) (230 * a));
    float minTextSize = snappingSize * 2 * scale;

    switch (type) {
      case BUTTON: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        luminaGlassBody(canvas, paint, boundingBox, a);
        if (engaged) {
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (85 * a)));
          drawCleanBody(canvas, paint, boundingBox, 0f);
          beginBloom(paint, cx, cy, Math.min(boundingBox.width(), boundingBox.height()) * 0.58f,
              accent, a);
          drawCleanBody(canvas, paint, boundingBox, 0f);
          endBloom(paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(hairline * 1.2f);
        paint.setColor(rimColor);
        drawCleanBody(canvas, paint, boundingBox, 0f);
        placeShader(getLuminaSpecularShader(), boundingBox.left, boundingBox.top,
            Math.max(1, boundingBox.height()));
        paint.setShader(getLuminaSpecularShader());
        paint.setStrokeWidth(hairline * 1.3f);
        paint.setAlpha((int) (150 * a));
        drawCleanBody(canvas, paint, boundingBox, hairline);
        paint.setShader(null);
        if (iconId > 0) {
          drawIcon(
              canvas, cx, cy, boundingBox.width(), boundingBox.height(), iconId, true,
              engaged ? inputControlsView.getColorFilter() : accentFilter(accent));
        } else {
          String label = getDisplayText();
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(labelColor);
          paint.setTextSize(
              Math.min(
                  getTextSizeForWidth(paint, label, boundingBox.width() - snappingSize * 0.6f),
                  minTextSize));
          paint.setTextAlign(Paint.Align.CENTER);
          canvas.drawText(label, cx, (cy - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
        }
        break;
      }
      case D_PAD: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float base = Math.min(boundingBox.width(), boundingBox.height());
        float half = base * 0.5f;
        float arm = base * 0.17f;
        path.reset();
        path.moveTo(cx - arm, cy - half);
        path.lineTo(cx + arm, cy - half);
        path.lineTo(cx + arm, cy - arm);
        path.lineTo(cx + half, cy - arm);
        path.lineTo(cx + half, cy + arm);
        path.lineTo(cx + arm, cy + arm);
        path.lineTo(cx + arm, cy + half);
        path.lineTo(cx - arm, cy + half);
        path.lineTo(cx - arm, cy + arm);
        path.lineTo(cx - half, cy + arm);
        path.lineTo(cx - half, cy - arm);
        path.lineTo(cx - arm, cy - arm);
        path.close();
        paint.setPathEffect(cornerEffect(base * 0.09f));
        paint.setStyle(Paint.Style.FILL);
        placeShader(getLuminaBodyShader(), boundingBox.left, cy - half, Math.max(1f, base));
        paint.setShader(getLuminaBodyShader());
        paint.setAlpha((int) (170 * a));
        canvas.drawPath(path, paint);
        placeShader(getLuminaShadeShader(), boundingBox.left, cy - half, Math.max(1f, base));
        paint.setShader(getLuminaShadeShader());
        paint.setAlpha((int) (255 * a));
        canvas.drawPath(path, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(hairline * 1.2f);
        paint.setColor(rimColor);
        canvas.drawPath(path, paint);
        paint.setPathEffect(null);

        boolean hasStates = engaged && states.length >= 4;
        float chev = base * 0.05f;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        for (int i = 0; i < 4; i++) {
          boolean hot = hasStates && states[i];
          int dx = i == 1 ? 1 : i == 3 ? -1 : 0;
          int dy = i == 2 ? 1 : i == 0 ? -1 : 0;
          if (hot) {
            float bx = cx + dx * half * 0.6f;
            float by = cy + dy * half * 0.6f;
            beginBloom(paint, bx, by, base * 0.26f, accent, a);
            canvas.drawCircle(bx, by, base * 0.26f, paint);
            endBloom(paint);
          }
          float ax = cx + dx * half * 0.62f;
          float ay = cy + dy * half * 0.62f;
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(Math.max(2f, snappingSize * 0.16f * scale));
          paint.setColor(hot
              ? Color.argb((int) (250 * a), 255, 255, 255)
              : ColorUtils.setAlphaComponent(accent, (int) (160 * a)));
          if (dy != 0) {
            canvas.drawLine(ax - chev, ay - dy * chev * 0.4f, ax, ay + dy * chev, paint);
            canvas.drawLine(ax + chev, ay - dy * chev * 0.4f, ax, ay + dy * chev, paint);
          } else {
            canvas.drawLine(ax - dx * chev * 0.4f, ay - chev, ax + dx * chev, ay, paint);
            canvas.drawLine(ax - dx * chev * 0.4f, ay + chev, ax + dx * chev, ay, paint);
          }
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);
        break;
      }
      case STICK: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float r = boundingBox.height() * 0.5f;
        paint.setStyle(Paint.Style.FILL);
        placeShader(getLuminaBodyShader(), cx - r, cy - r, Math.max(1f, r * 2f));
        paint.setShader(getLuminaBodyShader());
        paint.setAlpha((int) (95 * a));
        canvas.drawCircle(cx, cy, r, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        float bezel = Math.max(2f, snappingSize * 0.3f * scale);
        paint.setStrokeWidth(bezel);
        paint.setColor(ColorUtils.setAlphaComponent(LUMINA_RIM, (int) (55 * a)));
        canvas.drawCircle(cx, cy, r * 0.97f, paint);
        tempRect.set(cx - r * 0.97f, cy - r * 0.97f, cx + r * 0.97f, cy + r * 0.97f);
        paint.setColor(Color.argb((int) (110 * a), 255, 255, 255));
        canvas.drawArc(tempRect, 150f, 180f, false, paint);
        paint.setStrokeWidth(hairline * 1.3f);
        paint.setColor(ColorUtils.setAlphaComponent(accent, (int) ((engaged ? 210 : 95) * a)));
        canvas.drawCircle(cx, cy, r * 0.55f, paint);
        PointF pos = getCurrentPosition();
        float thumbR = snappingSize * 3.5f * scale;
        paint.setStyle(Paint.Style.FILL);
        placeShader(getLuminaDomeShader(),
            pos.x - thumbR * 0.35f, pos.y - thumbR * 0.38f, Math.max(1f, thumbR * 1.6f));
        paint.setShader(getLuminaDomeShader());
        paint.setAlpha((int) (235 * a));
        canvas.drawCircle(pos.x, pos.y, thumbR, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(hairline * 1.2f);
        paint.setColor(engaged
            ? ColorUtils.setAlphaComponent(accent, (int) (235 * a))
            : ColorUtils.setAlphaComponent(rimIdle, (int) (170 * a)));
        canvas.drawCircle(pos.x, pos.y, thumbR, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int) (128 * a), 255, 255, 255));
        canvas.drawCircle(pos.x - thumbR * 0.35f, pos.y - thumbR * 0.38f, thumbR * 0.14f, paint);
        break;
      }
      case TRACKPAD: {
        luminaGlassBody(canvas, paint, boundingBox, a);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(hairline * 1.2f);
        paint.setColor(rimColor);
        drawCleanBody(canvas, paint, boundingBox, 0f);
        float len = Math.min(boundingBox.width(), boundingBox.height()) * 0.08f;
        float ins = Math.min(boundingBox.width(), boundingBox.height()) * 0.06f;
        paint.setStrokeWidth(hairline);
        paint.setColor(engaged
            ? ColorUtils.setAlphaComponent(accent, (int) (200 * a))
            : ColorUtils.setAlphaComponent(LUMINA_RIM, (int) (90 * a)));
        canvas.drawLine(boundingBox.left + ins, boundingBox.top + ins, boundingBox.left + ins + len, boundingBox.top + ins, paint);
        canvas.drawLine(boundingBox.left + ins, boundingBox.top + ins, boundingBox.left + ins, boundingBox.top + ins + len, paint);
        canvas.drawLine(boundingBox.right - ins, boundingBox.top + ins, boundingBox.right - ins - len, boundingBox.top + ins, paint);
        canvas.drawLine(boundingBox.right - ins, boundingBox.top + ins, boundingBox.right - ins, boundingBox.top + ins + len, paint);
        canvas.drawLine(boundingBox.left + ins, boundingBox.bottom - ins, boundingBox.left + ins + len, boundingBox.bottom - ins, paint);
        canvas.drawLine(boundingBox.left + ins, boundingBox.bottom - ins, boundingBox.left + ins, boundingBox.bottom - ins - len, paint);
        canvas.drawLine(boundingBox.right - ins, boundingBox.bottom - ins, boundingBox.right - ins - len, boundingBox.bottom - ins, paint);
        canvas.drawLine(boundingBox.right - ins, boundingBox.bottom - ins, boundingBox.right - ins, boundingBox.bottom - ins - len, paint);
        break;
      }
      case RANGE_BUTTON: {
        Range range = getRange();
        float rr = (orientation == 0 ? boundingBox.height() : boundingBox.width()) * 0.45f;
        float elementSize = scroller.getElementSize();
        float scrollOffset = scroller.getScrollOffset();
        byte[] rangeIndex = scroller.getRangeIndex();
        paint.setStyle(Paint.Style.FILL);
        placeShader(getLuminaBodyShader(), boundingBox.left, boundingBox.top, Math.max(1, boundingBox.height()));
        paint.setShader(getLuminaBodyShader());
        paint.setAlpha((int) (170 * a));
        canvas.drawRoundRect(
            boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, rr, rr, paint);
        placeShader(getLuminaShadeShader(), boundingBox.left, boundingBox.top, Math.max(1, boundingBox.height()));
        paint.setShader(getLuminaShadeShader());
        paint.setAlpha((int) (255 * a));
        canvas.drawRoundRect(
            boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, rr, rr, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(hairline * 1.2f);
        paint.setColor(rimColor);
        canvas.drawRoundRect(
            boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, rr, rr, paint);

        canvas.save();
        path.reset();
        path.addRoundRect(
            boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
            rr, rr, Path.Direction.CW);
        canvas.clipPath(path);
        int pressedIndex =
            currentPointerId != -1 && !scroller.isScrolling() ? scroller.getBindingIndex() : -1;
        int dividerColor = ColorUtils.setAlphaComponent(LUMINA_RIM, (int) (60 * a));
        float dividerWidth = Math.max(1f, snappingSize * 0.06f);
        int hotTextColor = Color.argb((int) (250 * a), 255, 255, 255);
        int cellTextColor = ColorUtils.setAlphaComponent(LUMINA_TEXT, (int) (205 * a));

        if (orientation == 0) {
          float lineTop = boundingBox.top + boundingBox.height() * 0.25f;
          float lineBottom = boundingBox.bottom - boundingBox.height() * 0.25f;
          float startX = boundingBox.left - (scrollOffset % elementSize);
          for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
            int index = i % range.max;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dividerWidth);
            paint.setColor(dividerColor);
            if (startX > boundingBox.left && startX < boundingBox.right)
              canvas.drawLine(startX, lineTop, startX, lineBottom, paint);
            if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
              boolean hot = index == pressedIndex;
              if (hot) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (240 * a)));
                float uy = boundingBox.bottom - boundingBox.height() * 0.16f;
                tempRect.set(
                    startX + elementSize * 0.225f, uy,
                    startX + elementSize * 0.775f, uy + hairline * 2.2f);
                canvas.drawRoundRect(tempRect, hairline, hairline, paint);
              }
              String cellText = getRangeTextForIndex(range, index);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(hot ? hotTextColor : cellTextColor);
              paint.setTextSize(
                  Math.min(
                      getTextSizeForWidth(paint, cellText, elementSize - snappingSize * 0.5f),
                      minTextSize));
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(
                  cellText,
                  startX + elementSize * 0.5f,
                  (y - ((paint.descent() + paint.ascent()) * 0.5f)),
                  paint);
            }
            startX += elementSize;
          }
        } else {
          float lineLeft = boundingBox.left + boundingBox.width() * 0.25f;
          float lineRight = boundingBox.right - boundingBox.width() * 0.25f;
          float startY = boundingBox.top - (scrollOffset % elementSize);
          for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
            int index = i % range.max;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dividerWidth);
            paint.setColor(dividerColor);
            if (startY > boundingBox.top && startY < boundingBox.bottom)
              canvas.drawLine(lineLeft, startY, lineRight, startY, paint);
            if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
              boolean hot = index == pressedIndex;
              if (hot) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (240 * a)));
                float ux = boundingBox.right - boundingBox.width() * 0.16f;
                tempRect.set(
                    ux, startY + elementSize * 0.225f,
                    ux + hairline * 2.2f, startY + elementSize * 0.775f);
                canvas.drawRoundRect(tempRect, hairline, hairline, paint);
              }
              String cellText = getRangeTextForIndex(range, index);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(hot ? hotTextColor : cellTextColor);
              paint.setTextSize(
                  Math.min(
                      getTextSizeForWidth(paint, cellText, boundingBox.width() - snappingSize * 0.5f),
                      minTextSize));
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(
                  cellText,
                  x,
                  startY + elementSize * 0.5f - ((paint.descent() + paint.ascent()) * 0.5f),
                  paint);
            }
            startY += elementSize;
          }
        }
        canvas.restore();
        break;
      }
      case RADIAL_MENU: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float radius = boundingBox.width() * 0.5f;

        if (radialMenuExpanded && bindings.length > 0 && radius > 0) {
          float innerRadius = radius + snappingSize * 0.5f;
          float outerRadius = boundingBox.width() + (snappingSize * scale);
          float angleStep = 360.0f / bindings.length;

          if (paths == null || paths.length != bindings.length) {
            paths = new Path[bindings.length];
            RectF outerRect = new RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius);
            RectF innerRect = new RectF(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius);
            for (int i = 0; i < bindings.length; i++) {
              float startAngle = -90.0f + i * angleStep;
              paths[i] = new Path();
              paths[i].arcTo(outerRect, startAngle, angleStep, true);
              paths[i].arcTo(innerRect, startAngle + angleStep, -angleStep, false);
              paths[i].close();
            }
          }

          if (paths != null && paths.length == bindings.length) {
            for (int i = 0; i < bindings.length; i++) {
              boolean active = i == activeRadialBindingIndex;
              paint.setStyle(Paint.Style.FILL);
              if (active) {
                paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (90 * a)));
              } else {
                placeShader(getLuminaBodyShader(), cx - outerRadius, cy - outerRadius,
                    Math.max(1f, outerRadius * 2f));
                paint.setShader(getLuminaBodyShader());
                paint.setAlpha((int) (150 * a));
              }
              canvas.drawPath(paths[i], paint);
              paint.setShader(null);
              paint.setStyle(Paint.Style.STROKE);
              paint.setStrokeWidth(hairline);
              paint.setColor(ColorUtils.setAlphaComponent(LUMINA_RIM, (int) (80 * a)));
              canvas.drawPath(paths[i], paint);

              float middleAngle = (float) Math.toRadians(-90.0f + i * angleStep + angleStep * 0.5f);
              float labelRadius = (innerRadius + outerRadius) * 0.5f;
              float labelX = (float) (cx + Math.cos(middleAngle) * labelRadius);
              float labelY = (float) (cy + Math.sin(middleAngle) * labelRadius);
              String label = getBindingShortText(i);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(active
                  ? Color.argb((int) (250 * a), 255, 255, 255)
                  : ColorUtils.setAlphaComponent(LUMINA_TEXT, (int) (205 * a)));
              paint.setTextSize(snappingSize * 1.2f * scale);
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(label, labelX, labelY - ((paint.descent() + paint.ascent()) * 0.5f), paint);
            }
          }
        }

        luminaGlassBody(canvas, paint, boundingBox, a);
        if (engaged) {
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (85 * a)));
          canvas.drawCircle(cx, cy, radius, paint);
          beginBloom(paint, cx, cy, radius, accent, a);
          canvas.drawCircle(cx, cy, radius, paint);
          endBloom(paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(hairline * 1.2f);
        paint.setColor(rimColor);
        canvas.drawCircle(cx, cy, radius, paint);
        drawIcon(
            canvas, cx, cy, boundingBox.width(), boundingBox.height(),
            iconId > 0 ? iconId : 34, true,
            engaged ? inputControlsView.getColorFilter() : accentFilter(accent));
        break;
      }
    }
  }

  private static final int SHADOW_BODY = 0xFF0A1422;
  private static final int SHADOW_EDGE = 0xFF84A9D4;

  private void shadowSoftRect(
      Canvas canvas, Paint paint, float l, float t, float r, float b, float radius,
      boolean active, int accent, float a, int snappingSize) {
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(Math.max(1f, snappingSize * 0.55f * scale));
    paint.setColor(ColorUtils.setAlphaComponent(accent, (int) ((active ? 64 : 16) * a)));
    canvas.drawRoundRect(l, t, r, b, radius, radius, paint);
    paint.setStrokeWidth(Math.max(1f, snappingSize * 0.22f * scale));
    paint.setColor(ColorUtils.setAlphaComponent(SHADOW_EDGE, (int) ((active ? 70 : 28) * a)));
    canvas.drawRoundRect(l, t, r, b, radius, radius, paint);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(active
        ? ColorUtils.setAlphaComponent(accent, (int) (190 * a))
        : ColorUtils.setAlphaComponent(SHADOW_BODY, (int) (135 * a)));
    canvas.drawRoundRect(l, t, r, b, radius, radius, paint);
    float inset = Math.max(1f, snappingSize * 0.35f * scale);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(Math.max(1f, snappingSize * 0.08f * scale));
    paint.setColor(Color.argb((int) ((active ? 58 : 24) * a), 255, 255, 255));
    canvas.drawRoundRect(
        l + inset, t + inset, r - inset, b - inset,
        Math.max(1f, radius - inset), Math.max(1f, radius - inset), paint);
  }

  private void shadowSoftCircle(
      Canvas canvas, Paint paint, float cx, float cy, float r,
      boolean active, int accent, float a, int snappingSize) {
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(Math.max(1f, snappingSize * 0.55f * scale));
    paint.setColor(ColorUtils.setAlphaComponent(accent, (int) ((active ? 72 : 18) * a)));
    canvas.drawCircle(cx, cy, r, paint);
    paint.setStrokeWidth(Math.max(1f, snappingSize * 0.2f * scale));
    paint.setColor(ColorUtils.setAlphaComponent(SHADOW_EDGE, (int) ((active ? 74 : 30) * a)));
    canvas.drawCircle(cx, cy, r, paint);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(active
        ? ColorUtils.setAlphaComponent(accent, (int) (190 * a))
        : ColorUtils.setAlphaComponent(SHADOW_BODY, (int) (140 * a)));
    canvas.drawCircle(cx, cy, r, paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(Math.max(1f, snappingSize * 0.08f * scale));
    paint.setColor(Color.argb((int) ((active ? 44 : 18) * a), 255, 255, 255));
    canvas.drawCircle(cx, cy, r * 0.88f, paint);
  }

  /** SHADOW — soft dark layered-stroke bodies that flood with the accent while pressed; four-tile D-pad. */
  private void drawShadow(Canvas canvas) {
    int snappingSize = inputControlsView.getSnappingSize();
    Paint paint = inputControlsView.getPaint();
    float effectiveOpacity = inputControlsView.isEditMode() ? Math.max(0.15f, opacity) : opacity;
    float overlayOpacity = inputControlsView.getOverlayOpacity();
    float dim = overlayOpacity <= 0.4f
        ? 0.28f + (overlayOpacity - 0.1f) * (0.5f / 0.3f)
        : 0.78f + (overlayOpacity - 0.4f) * (0.22f / 0.6f);
    float a = Mathf.clamp(dim, 0f, 1f) * effectiveOpacity;
    boolean engaged = isEngaged();
    Rect boundingBox = getBoundingBox();
    int accent = resolveThemedAccent();
    if (selected && resolveAccentColor() == -1) {
      accent = ColorUtils.setAlphaComponent(inputControlsView.getSecondaryColor(), 255);
    }
    int labelColor = engaged
        ? Color.argb((int) (245 * a), 255, 255, 255)
        : ColorUtils.setAlphaComponent(accent, (int) (235 * a));
    float minTextSize = snappingSize * 2 * scale;

    switch (type) {
      case BUTTON: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        switch (shape) {
          case CIRCLE:
            shadowSoftCircle(
                canvas, paint, cx, cy, boundingBox.width() * 0.5f, engaged, accent, a, snappingSize);
            break;
          case SQUARE:
            shadowSoftRect(
                canvas, paint, boundingBox.left, boundingBox.top, boundingBox.right,
                boundingBox.bottom, snappingSize * 1.05f * scale, engaged, accent, a, snappingSize);
            break;
          default:
            shadowSoftRect(
                canvas, paint, boundingBox.left, boundingBox.top, boundingBox.right,
                boundingBox.bottom, boundingBox.height() * 0.45f, engaged, accent, a, snappingSize);
            break;
        }
        if (iconId > 0) {
          drawIcon(
              canvas, cx, cy, boundingBox.width(), boundingBox.height(), iconId, true,
              engaged ? inputControlsView.getColorFilter() : accentFilter(accent));
        } else {
          String label = getDisplayText();
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(labelColor);
          paint.setTextSize(
              Math.min(
                  getTextSizeForWidth(paint, label, boundingBox.width() - snappingSize * 0.6f),
                  minTextSize));
          paint.setTextAlign(Paint.Align.CENTER);
          canvas.drawText(label, cx, (cy - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
        }
        break;
      }
      case D_PAD: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float base = Math.min(boundingBox.width(), boundingBox.height());
        float piece = base * 0.30f;
        float dist = piece * 1.15f;
        float c = piece * 0.13f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ColorUtils.setAlphaComponent(SHADOW_BODY, (int) (60 * a)));
        canvas.drawRoundRect(cx - c, cy - c, cx + c, cy + c, c * 0.45f, c * 0.45f, paint);
        boolean hasStates = engaged && states.length >= 4;
        for (int i = 0; i < 4; i++) {
          int dx = i == 1 ? 1 : i == 3 ? -1 : 0;
          int dy = i == 2 ? 1 : i == 0 ? -1 : 0;
          float px = cx + dx * dist;
          float py = cy + dy * dist;
          boolean hot = hasStates && states[i];
          shadowSoftRect(
              canvas, paint, px - piece * 0.5f, py - piece * 0.5f, px + piece * 0.5f,
              py + piece * 0.5f, piece * 0.28f, hot, accent, a, snappingSize);
          buildDpadArrowPath(path, px, py, piece * 0.22f, i);
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(ColorUtils.setAlphaComponent(accent, (int) ((hot ? 250 : 220) * a)));
          canvas.drawPath(path, paint);
        }
        break;
      }
      case STICK: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float r = boundingBox.height() * 0.5f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ColorUtils.setAlphaComponent(SHADOW_BODY, (int) ((engaged ? 155 : 118) * a)));
        canvas.drawCircle(cx, cy, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, snappingSize * 0.2f * scale));
        paint.setColor(ColorUtils.setAlphaComponent(SHADOW_EDGE, (int) ((engaged ? 62 : 26) * a)));
        canvas.drawCircle(cx, cy, r * 0.96f, paint);
        paint.setStrokeWidth(Math.max(1f, snappingSize * 0.5f * scale));
        paint.setColor(ColorUtils.setAlphaComponent(accent, (int) ((engaged ? 72 : 20) * a)));
        canvas.drawCircle(cx, cy, r * 0.98f, paint);
        paint.setStrokeWidth(Math.max(1f, snappingSize * 0.22f * scale));
        paint.setColor(ColorUtils.setAlphaComponent(accent, (int) ((engaged ? 235 : 205) * a)));
        canvas.drawCircle(cx, cy, r * 0.52f, paint);
        PointF pos = getCurrentPosition();
        float thumbRadius = snappingSize * 3.5f * scale;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ColorUtils.setAlphaComponent(SHADOW_BODY, (int) (190 * a)));
        canvas.drawCircle(pos.x, pos.y, thumbRadius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, snappingSize * 0.12f * scale));
        paint.setColor(ColorUtils.setAlphaComponent(SHADOW_EDGE, (int) (45 * a)));
        canvas.drawCircle(pos.x, pos.y, thumbRadius * 0.96f, paint);
        break;
      }
      case TRACKPAD: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        shadowSoftRect(
            canvas, paint, boundingBox.left, boundingBox.top, boundingBox.right,
            boundingBox.bottom, boundingBox.height() * 0.18f, engaged, accent, a, snappingSize);
        float glyph = Math.min(boundingBox.width(), boundingBox.height()) * 0.45f;
        float gw = glyph * 0.42f;
        float gh = glyph * 0.62f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, glyph * 0.035f));
        paint.setColor(ColorUtils.setAlphaComponent(
            engaged ? Color.WHITE : accent, (int) ((engaged ? 220 : 95) * a)));
        canvas.drawRoundRect(
            cx - gw * 0.5f, cy - gh * 0.5f, cx + gw * 0.5f, cy + gh * 0.5f,
            gw * 0.5f, gw * 0.5f, paint);
        canvas.drawLine(cx, cy - gh * 0.30f, cx, cy - gh * 0.02f, paint);
        break;
      }
      case RANGE_BUTTON: {
        Range range = getRange();
        float rr = (orientation == 0 ? boundingBox.height() : boundingBox.width()) * 0.45f;
        float elementSize = scroller.getElementSize();
        float scrollOffset = scroller.getScrollOffset();
        byte[] rangeIndex = scroller.getRangeIndex();
        shadowSoftRect(
            canvas, paint, boundingBox.left, boundingBox.top, boundingBox.right,
            boundingBox.bottom, rr, false, accent, a, snappingSize);

        canvas.save();
        path.reset();
        path.addRoundRect(
            boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
            rr, rr, Path.Direction.CW);
        canvas.clipPath(path);

        int pressedIndex =
            currentPointerId != -1 && !scroller.isScrolling() ? scroller.getBindingIndex() : -1;
        int dividerColor = ColorUtils.setAlphaComponent(SHADOW_EDGE, (int) (35 * a));
        float dividerWidth = Math.max(1f, snappingSize * 0.06f);
        int hotTextColor = Color.argb((int) (245 * a), 255, 255, 255);
        int cellTextColor = ColorUtils.setAlphaComponent(accent, (int) (225 * a));

        if (orientation == 0) {
          float lineTop = boundingBox.top + boundingBox.height() * 0.25f;
          float lineBottom = boundingBox.bottom - boundingBox.height() * 0.25f;
          float startX = boundingBox.left - (scrollOffset % elementSize);
          for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
            int index = i % range.max;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dividerWidth);
            paint.setColor(dividerColor);
            if (startX > boundingBox.left && startX < boundingBox.right)
              canvas.drawLine(startX, lineTop, startX, lineBottom, paint);
            if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
              boolean hot = index == pressedIndex;
              if (hot) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (150 * a)));
                canvas.drawRect(
                    startX, boundingBox.top, startX + elementSize, boundingBox.bottom, paint);
              }
              String cellText = getRangeTextForIndex(range, index);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(hot ? hotTextColor : cellTextColor);
              paint.setTextSize(
                  Math.min(
                      getTextSizeForWidth(paint, cellText, elementSize - snappingSize * 0.5f),
                      minTextSize));
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(
                  cellText,
                  startX + elementSize * 0.5f,
                  (y - ((paint.descent() + paint.ascent()) * 0.5f)),
                  paint);
            }
            startX += elementSize;
          }
        } else {
          float lineLeft = boundingBox.left + boundingBox.width() * 0.25f;
          float lineRight = boundingBox.right - boundingBox.width() * 0.25f;
          float startY = boundingBox.top - (scrollOffset % elementSize);
          for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
            int index = i % range.max;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dividerWidth);
            paint.setColor(dividerColor);
            if (startY > boundingBox.top && startY < boundingBox.bottom)
              canvas.drawLine(lineLeft, startY, lineRight, startY, paint);
            if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
              boolean hot = index == pressedIndex;
              if (hot) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (150 * a)));
                canvas.drawRect(
                    boundingBox.left, startY, boundingBox.right, startY + elementSize, paint);
              }
              String cellText = getRangeTextForIndex(range, index);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(hot ? hotTextColor : cellTextColor);
              paint.setTextSize(
                  Math.min(
                      getTextSizeForWidth(paint, cellText, boundingBox.width() - snappingSize * 0.5f),
                      minTextSize));
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(
                  cellText,
                  x,
                  startY + elementSize * 0.5f - ((paint.descent() + paint.ascent()) * 0.5f),
                  paint);
            }
            startY += elementSize;
          }
        }
        canvas.restore();
        break;
      }
      case RADIAL_MENU: {
        float cx = boundingBox.exactCenterX();
        float cy = boundingBox.exactCenterY();
        float radius = boundingBox.width() * 0.5f;

        if (radialMenuExpanded && bindings.length > 0 && radius > 0) {
          float innerRadius = radius + snappingSize * 0.5f;
          float outerRadius = boundingBox.width() + (snappingSize * scale);
          float angleStep = 360.0f / bindings.length;

          if (paths == null || paths.length != bindings.length) {
            paths = new Path[bindings.length];
            RectF outerRect = new RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius);
            RectF innerRect = new RectF(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius);
            for (int i = 0; i < bindings.length; i++) {
              float startAngle = -90.0f + i * angleStep;
              paths[i] = new Path();
              paths[i].arcTo(outerRect, startAngle, angleStep, true);
              paths[i].arcTo(innerRect, startAngle + angleStep, -angleStep, false);
              paths[i].close();
            }
          }

          if (paths != null && paths.length == bindings.length) {
            for (int i = 0; i < bindings.length; i++) {
              boolean active = i == activeRadialBindingIndex;
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(active
                  ? ColorUtils.setAlphaComponent(accent, (int) (150 * a))
                  : ColorUtils.setAlphaComponent(SHADOW_BODY, (int) (135 * a)));
              canvas.drawPath(paths[i], paint);
              paint.setStyle(Paint.Style.STROKE);
              paint.setStrokeWidth(Math.max(1f, snappingSize * 0.12f));
              paint.setColor(ColorUtils.setAlphaComponent(SHADOW_EDGE, (int) (40 * a)));
              canvas.drawPath(paths[i], paint);

              float middleAngle = (float) Math.toRadians(-90.0f + i * angleStep + angleStep * 0.5f);
              float labelRadius = (innerRadius + outerRadius) * 0.5f;
              float labelX = (float) (cx + Math.cos(middleAngle) * labelRadius);
              float labelY = (float) (cy + Math.sin(middleAngle) * labelRadius);
              String label = getBindingShortText(i);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(active
                  ? Color.argb((int) (240 * a), 255, 255, 255)
                  : ColorUtils.setAlphaComponent(accent, (int) (215 * a)));
              paint.setTextSize(snappingSize * 1.2f * scale);
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(label, labelX, labelY - ((paint.descent() + paint.ascent()) * 0.5f), paint);
            }
          }
        }

        shadowSoftCircle(canvas, paint, cx, cy, radius, engaged, accent, a, snappingSize);
        drawIcon(
            canvas, cx, cy, boundingBox.width(), boundingBox.height(),
            iconId > 0 ? iconId : 34, true,
            engaged ? inputControlsView.getColorFilter() : accentFilter(accent));
        break;
      }
    }
  }

  /**
   * GameHub visual style — dark translucent glass body, light white rim, brighter rim & inner glow
   * when pressed, soft outer shadow. Used when the user picks the "GameHub" style.
   *
   * <p>Geometry (positions, bounding boxes, sticks, dpad arms, radial menu paths) is reused from
   * the original code; only the paint properties differ.
   */
  private void drawGameHub(Canvas canvas) {
    int snappingSize = inputControlsView.getSnappingSize();
    Paint paint = inputControlsView.getPaint();
    float effectiveOpacity = inputControlsView.isEditMode() ? Math.max(0.15f, opacity) : opacity;
    float overlayOpacity = inputControlsView.getOverlayOpacity();
    boolean engaged = isEngaged();
    Rect boundingBox = getBoundingBox();

    int custom = resolveAccentColor();
    AccentTheme theme = inputControlsView.getAccentTheme();
    int accent = custom != -1 ? custom : (theme != AccentTheme.MONO ? theme.accent : -1);
    boolean hasAccent = accent != -1;

    // Anchored at 40% default; steeper below, gentle to full above.
    float gameHubDim = overlayOpacity <= 0.4f
        ? 0.28f + (overlayOpacity - 0.1f) * (0.5f / 0.3f)
        : 0.78f + (overlayOpacity - 0.4f) * (0.22f / 0.6f);
    int fillAlpha = (int) (90 * gameHubDim * effectiveOpacity);
    int strokeAlpha = (int) (150 * gameHubDim * effectiveOpacity);
    int pressedFillAlpha = (int) (60 * gameHubDim * effectiveOpacity);
    int pressedStrokeAlpha = (int) (220 * gameHubDim * effectiveOpacity);
    int textAlpha = (int) (255 * gameHubDim * effectiveOpacity);
    int glassEdgeAlpha = (int) (75 * gameHubDim * effectiveOpacity);
    // The edge shade is modulated by the body fill's alpha so the vignette keeps its subtle weight.
    int glassShadeAlpha = glassEdgeAlpha * fillAlpha / 255;
    int pressedGlassShadeAlpha = glassEdgeAlpha * pressedFillAlpha / 255;

    int fillColor = Color.argb(fillAlpha, 0, 0, 0);
    int strokeColor = hasAccent
        ? ColorUtils.setAlphaComponent(accent, Math.max(strokeAlpha, 110))
        : Color.argb(strokeAlpha, 255, 255, 255);
    int pressedFillBase = hasAccent ? accent : Color.WHITE;
    int pressedFillColor = ColorUtils.setAlphaComponent(pressedFillBase, pressedFillAlpha);
    int pressedStrokeColor = hasAccent
        ? ColorUtils.setAlphaComponent(accent, Math.max(pressedStrokeAlpha, 160))
        : Color.argb(pressedStrokeAlpha, 255, 255, 255);
    int textColor = hasAccent
        ? ColorUtils.setAlphaComponent(accent, textAlpha)
        : Color.argb(textAlpha, 255, 255, 255);

    if (selected && !hasAccent) {
      int highlightAlpha = (int) (255 * overlayOpacity);
      strokeColor = ColorUtils.setAlphaComponent(inputControlsView.getSecondaryColor(), highlightAlpha);
    }

    float strokeWidth = Math.max(2f, snappingSize * 0.18f);
    paint.setStrokeWidth(strokeWidth);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStrokeCap(Paint.Cap.ROUND);

    switch (type) {
      case BUTTON: {
        float cx = boundingBox.centerX();
        float cy = boundingBox.centerY();
        GameHubLayout.RenderShape triggerShape = gameHubTriggerShape();
        boolean isTrigger = triggerShape != null;

        if (isTrigger) {
          GameHubLayout.buildTriggerPath(
              path, triggerShape,
              boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom);
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(fillColor);
          canvas.drawPath(path, paint);
          if (engaged) {
            paint.setColor(pressedFillColor);
            canvas.drawPath(path, paint);
          }
          drawGameHubGlassOnPath(
              canvas, paint, path, cx, cy,
              Math.max(boundingBox.width(), boundingBox.height()) * 0.5f,
              engaged ? pressedGlassShadeAlpha : glassShadeAlpha);
          paint.setStyle(Paint.Style.STROKE);
          paint.setColor(engaged ? pressedStrokeColor : strokeColor);
          canvas.drawPath(path, paint);
        } else {
          drawGameHubShape(canvas, paint, boundingBox, fillColor, true);
          if (engaged) drawGameHubShape(canvas, paint, boundingBox, pressedFillColor, true);
          drawGameHubGlassShape(
              canvas, paint, boundingBox, engaged ? pressedGlassShadeAlpha : glassShadeAlpha);
          paint.setStyle(Paint.Style.STROKE);
          paint.setColor(engaged ? pressedStrokeColor : strokeColor);
          drawGameHubShape(canvas, paint, boundingBox, 0, false);
        }

        if (iconId > 0) {
          drawIcon(
              canvas, cx, cy, boundingBox.width(), boundingBox.height(), iconId, true,
              hasAccent ? accentFilter(accent) : inputControlsView.getColorFilter());
        } else {
          String label = getDisplayText();
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(textColor);
          paint.setTextSize(
              Math.min(
                  getTextSizeForWidth(paint, label, boundingBox.width() - strokeWidth * 2),
                  snappingSize * 2 * scale));
          paint.setTextAlign(Paint.Align.CENTER);
          paint.setFakeBoldText(true);
          canvas.drawText(label, cx, (cy - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
          paint.setFakeBoldText(false);
        }
        break;
      }
      case STICK: {
        int cx = boundingBox.centerX();
        int cy = boundingBox.centerY();
        float ringRadius = boundingBox.height() * 0.5f;

        // Outer ring — solid translucent dark fill matching the button fill alpha so the
        // joystick shadowing reads with the same weight as the rest of the controls.
        int ringFillAlpha = fillAlpha;
        int ringFill = Color.argb(ringFillAlpha, 0, 0, 0);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ringFill);
        canvas.drawCircle(cx, cy, ringRadius, paint);

        if (glassShadeAlpha > 0) {
          placeShader(getEdgeShadeShader(), cx, cy, ringRadius);
          paint.setShader(getEdgeShadeShader());
          paint.setStyle(Paint.Style.FILL);
          paint.setAlpha(glassShadeAlpha);
          canvas.drawCircle(cx, cy, ringRadius, paint);
          paint.setShader(null);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(engaged ? pressedStrokeColor : strokeColor);
        canvas.drawCircle(cx, cy, ringRadius - strokeWidth * 0.5f, paint);

        float thumbX = engaged ? getCurrentPosition().x : cx;
        float thumbY = engaged ? getCurrentPosition().y : cy;
        float thumbRadius = ringRadius * 0.48f;
        int thumbFillAlpha = (int) ((engaged ? 100 : 77) * gameHubDim * effectiveOpacity);
        int thumbFill = hasAccent
            ? ColorUtils.setAlphaComponent(accent, thumbFillAlpha)
            : Color.argb(thumbFillAlpha, 255, 255, 255);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(thumbFill);
        canvas.drawCircle(thumbX, thumbY, thumbRadius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(engaged ? pressedStrokeColor : strokeColor);
        canvas.drawCircle(thumbX, thumbY, thumbRadius - strokeWidth * 0.5f, paint);
        break;
      }
      case D_PAD: {
        float cx = boundingBox.centerX();
        float cy = boundingBox.centerY();

        float radius = Math.min(boundingBox.width(), boundingBox.height()) * 0.5f;
        float[] arrowCenter = new float[2];
        float arrowGradR = radius * 0.5f;
        for (int side = 0; side < 4; side++) {
          path.reset();
          GameHubLayout.buildDpadArrow(path, side, cx, cy, radius);
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(fillColor);
          canvas.drawPath(path, paint);
          if (engaged) {
            paint.setColor(pressedFillColor);
            canvas.drawPath(path, paint);
          }
          if (glassEdgeAlpha > 0) {
            GameHubLayout.dpadArrowCenter(side, cx, cy, radius, arrowCenter);
            drawGameHubGlassOnPath(
                canvas, paint, path, arrowCenter[0], arrowCenter[1], arrowGradR,
                engaged ? pressedGlassShadeAlpha : glassShadeAlpha);
          }
        }
        GameHubLayout.buildDpadArrows(path, cx, cy, radius);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(engaged ? pressedStrokeColor : strokeColor);
        canvas.drawPath(path, paint);
        break;
      }
      case TRACKPAD: {
        float radius = boundingBox.height() * 0.18f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fillColor);
        canvas.drawRoundRect(
            boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
            radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(engaged ? pressedStrokeColor : strokeColor);
        canvas.drawRoundRect(
            boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
            radius, radius, paint);
        break;
      }
      case RADIAL_MENU: {
        float cx = boundingBox.centerX();
        float cy = boundingBox.centerY();
        float radius = boundingBox.width() * 0.5f;

        if (radialMenuExpanded && bindings.length > 0 && radius > 0) {
          float innerRadius = radius + snappingSize * 0.5f;
          float outerRadius = boundingBox.width() + (snappingSize * scale);
          float angleStep = 360.0f / bindings.length;

          if (paths == null || paths.length != bindings.length) {
            paths = new Path[bindings.length];
            RectF outerRect = new RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius);
            RectF innerRect = new RectF(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius);

            for (int i = 0; i < bindings.length; i++) {
              float startAngle = -90.0f + i * angleStep;
              paths[i] = new Path();
              paths[i].arcTo(outerRect, startAngle, angleStep, true);
              paths[i].arcTo(innerRect, startAngle + angleStep, -angleStep, false);
              paths[i].close();
            }
          }

          if (paths != null && paths.length == bindings.length) {
            for (int i = 0; i < bindings.length; i++) {
              boolean isSegmentEngaged = i == activeRadialBindingIndex;
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(isSegmentEngaged ? pressedFillColor : fillColor);
              canvas.drawPath(paths[i], paint);

              drawGameHubGlassOnPath(
                  canvas, paint, paths[i], cx, cy, outerRadius,
                  isSegmentEngaged ? pressedGlassShadeAlpha : glassShadeAlpha);

              paint.setStyle(Paint.Style.STROKE);
              paint.setColor(isSegmentEngaged ? pressedStrokeColor : strokeColor);
              canvas.drawPath(paths[i], paint);

              float middleAngle = (float) Math.toRadians(-90.0f + i * angleStep + angleStep * 0.5f);
              float labelRadius = (innerRadius + outerRadius) * 0.5f;
              float labelX = (float) (cx + Math.cos(middleAngle) * labelRadius);
              float labelY = (float) (cy + Math.sin(middleAngle) * labelRadius);

              String label = getBindingShortText(i);
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(textColor);
              paint.setTextSize(snappingSize * 1.2f * scale);
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(label, labelX, labelY - ((paint.descent() + paint.ascent()) * 0.5f), paint);
            }
          }
        }

        drawGameHubShape(canvas, paint, boundingBox, fillColor, true);
        if (engaged) drawGameHubShape(canvas, paint, boundingBox, pressedFillColor, true);
        drawGameHubGlassShape(
            canvas, paint, boundingBox, engaged ? pressedGlassShadeAlpha : glassShadeAlpha);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(engaged ? pressedStrokeColor : strokeColor);
        drawGameHubShape(canvas, paint, boundingBox, 0, false);

        drawIcon(
            canvas, cx, cy, boundingBox.width(), boundingBox.height(),
            iconId > 0 ? iconId : 34, true,
            hasAccent ? accentFilter(accent) : inputControlsView.getColorFilter());
        break;
      }
      case RANGE_BUTTON: {
        Range range = getRange();
        float radius = snappingSize * 0.75f * scale;
        float elementSize = scroller.getElementSize();
        float minTextSize = snappingSize * 2 * scale;
        float scrollOffset = scroller.getScrollOffset();
        byte[] rangeIndex = scroller.getRangeIndex();
        path.reset();

        drawGameHubShape(canvas, paint, boundingBox, fillColor, true, Shape.ROUND_RECT);
        if (engaged) drawGameHubShape(canvas, paint, boundingBox, pressedFillColor, true, Shape.ROUND_RECT);
        drawGameHubGlassShape(
            canvas, paint, boundingBox,
            engaged ? pressedGlassShadeAlpha : glassShadeAlpha, Shape.ROUND_RECT);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(engaged ? pressedStrokeColor : strokeColor);
        drawGameHubShape(canvas, paint, boundingBox, 0, false, Shape.ROUND_RECT);

        canvas.save();
        path.addRoundRect(
            boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
            radius, radius, Path.Direction.CW);
        canvas.clipPath(path);

        if (orientation == 0) {
          float lineTop = boundingBox.top + strokeWidth * 0.5f;
          float lineBottom = boundingBox.bottom - strokeWidth * 0.5f;
          float startX = boundingBox.left - (scrollOffset % elementSize);

          for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
            int index = i % range.max;
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(strokeColor);
            if (startX > boundingBox.left && startX < boundingBox.right)
              canvas.drawLine(startX, lineTop, startX, lineBottom, paint);
            String text = getRangeTextForIndex(range, index);
            if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(textColor);
              paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, elementSize - strokeWidth * 2), minTextSize));
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(text, startX + elementSize * 0.5f, (boundingBox.centerY() - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
            }
            startX += elementSize;
          }
        } else {
          float lineLeft = boundingBox.left + strokeWidth * 0.5f;
          float lineRight = boundingBox.right - strokeWidth * 0.5f;
          float startY = boundingBox.top - (scrollOffset % elementSize);

          for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(strokeColor);
            if (startY > boundingBox.top && startY < boundingBox.bottom)
              canvas.drawLine(lineLeft, startY, lineRight, startY, paint);
            String text = getRangeTextForIndex(range, i);
            if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
              paint.setStyle(Paint.Style.FILL);
              paint.setColor(textColor);
              paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, boundingBox.width() - strokeWidth * 2), minTextSize));
              paint.setTextAlign(Paint.Align.CENTER);
              canvas.drawText(text, boundingBox.centerX(), startY + elementSize * 0.5f - ((paint.descent() + paint.ascent()) * 0.5f), paint);
            }
            startY += elementSize;
          }
        }
        canvas.restore();
        break;
      }
    }
    paint.setStrokeJoin(Paint.Join.MITER);
    paint.setStrokeCap(Paint.Cap.BUTT);
  }

  private void drawGameHubShape(Canvas canvas, Paint paint, Rect bb, int color, boolean fill) {
    drawGameHubShape(canvas, paint, bb, color, fill, shape);
  }

  private void drawGameHubShape(Canvas canvas, Paint paint, Rect bb, int color, boolean fill, Shape overrideShape) {
    if (fill) {
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(color);
    }
    int snappingSize = inputControlsView.getSnappingSize();
    switch (overrideShape) {
      case CIRCLE:
        canvas.drawCircle(bb.centerX(), bb.centerY(), bb.width() * 0.5f, paint);
        break;
      case RECT:
        canvas.drawRect(bb, paint);
        break;
      case ROUND_RECT: {
        float r = bb.height() * 0.5f;
        canvas.drawRoundRect(bb.left, bb.top, bb.right, bb.bottom, r, r, paint);
        break;
      }
      case SQUARE: {
        float r = snappingSize * 0.85f * scale;
        canvas.drawRoundRect(bb.left, bb.top, bb.right, bb.bottom, r, r, paint);
        break;
      }
    }
  }

  private void drawGameHubGlassShape(Canvas canvas, Paint paint, Rect bb, int edgeAlpha) {
    drawGameHubGlassShape(canvas, paint, bb, edgeAlpha, shape);
  }

  private void drawGameHubGlassShape(Canvas canvas, Paint paint, Rect bb, int edgeAlpha, Shape overrideShape) {
    if (edgeAlpha <= 0) return;
    float cx = bb.exactCenterX();
    float cy = bb.exactCenterY();
    float gradR = Math.max(bb.width(), bb.height()) * 0.5f;
    placeShader(getEdgeShadeShader(), cx, cy, gradR);
    paint.setShader(getEdgeShadeShader());
    paint.setStyle(Paint.Style.FILL);
    paint.setAlpha(edgeAlpha);
    int snappingSize = inputControlsView.getSnappingSize();
    switch (overrideShape) {
      case CIRCLE:
        canvas.drawCircle(cx, cy, bb.width() * 0.5f, paint);
        break;
      case RECT:
        canvas.drawRect(bb, paint);
        break;
      case ROUND_RECT: {
        float r = bb.height() * 0.5f;
        canvas.drawRoundRect(bb.left, bb.top, bb.right, bb.bottom, r, r, paint);
        break;
      }
      case SQUARE: {
        float r = snappingSize * 0.85f * scale;
        canvas.drawRoundRect(bb.left, bb.top, bb.right, bb.bottom, r, r, paint);
        break;
      }
    }
    paint.setShader(null);
  }

  private void drawGameHubGlassOnPath(
      Canvas canvas, Paint paint, Path path, float cx, float cy, float gradR, int edgeAlpha) {
    if (edgeAlpha <= 0 || gradR <= 0) return;
    placeShader(getEdgeShadeShader(), cx, cy, gradR);
    paint.setShader(getEdgeShadeShader());
    paint.setStyle(Paint.Style.FILL);
    paint.setAlpha(edgeAlpha);
    canvas.drawPath(path, paint);
    paint.setShader(null);
  }

  private static final Rect iconSrcRect = new Rect();
  private static final Rect iconDstRect = new Rect();

  private void drawIcon(Canvas canvas, float cx, float cy, float width, float height, int iconId) {
    drawIcon(canvas, cx, cy, width, height, iconId, true);
  }

  private void drawIcon(Canvas canvas, float cx, float cy, float width, float height, int iconId, boolean automargin) {
    drawIcon(canvas, cx, cy, width, height, iconId, automargin, inputControlsView.getColorFilter());
  }

  private void drawIcon(Canvas canvas, float cx, float cy, float width, float height, int iconId, boolean automargin, ColorFilter tint) {
    Bitmap icon = inputControlsView.getIcon((byte) iconId);
    if (icon == null) return;
    Paint paint = inputControlsView.getPaint();
    paint.setColorFilter(tint);
    int margin = automargin ? (int) (inputControlsSize() * (shape == Shape.CIRCLE || shape == Shape.SQUARE ? 2.0f : 1.0f) * scale) : 0;
    int halfSize = (int) ((Math.min(width, height) - margin) * 0.5f);

    iconSrcRect.set(0, 0, icon.getWidth(), icon.getHeight());
    iconDstRect.set(
        (int) (cx - halfSize),
        (int) (cy - halfSize),
        (int) (cx + halfSize),
        (int) (cy + halfSize));
    canvas.drawBitmap(icon, iconSrcRect, iconDstRect, paint);
    paint.setColorFilter(null);
  }

  private int inputControlsSize() {
    return inputControlsView.getSnappingSize();
  }

  public JSONObject toJSONObject() {
    try {
      JSONObject elementJSONObject = new JSONObject();
      elementJSONObject.put("type", type.name());
      elementJSONObject.put("shape", shape.name());
      elementJSONObject.put("customColor", customColor);

      JSONArray bindingsJSONArray = new JSONArray();
      for (Binding binding : bindings) bindingsJSONArray.put(binding.name());

      elementJSONObject.put("bindings", bindingsJSONArray);
      elementJSONObject.put("scale", Float.valueOf(scale));
      if (opacity < 1.0f) elementJSONObject.put("opacity", Float.valueOf(opacity));
      elementJSONObject.put("x", (float) x / inputControlsView.getMaxWidth());
      elementJSONObject.put("y", (float) y / inputControlsView.getMaxHeight());
      elementJSONObject.put("toggleSwitch", toggleSwitch);
      elementJSONObject.put("swipeable", swipeable);
      elementJSONObject.put("text", text);
      elementJSONObject.put("iconId", iconId);

      if (type == Type.RANGE_BUTTON && range != null) {
        elementJSONObject.put("range", range.name());
        if (orientation != 0) elementJSONObject.put("orientation", orientation);
      }
      return elementJSONObject;
    } catch (JSONException e) {
      return null;
    }
  }

  private boolean isAdaptiveStick() {
    return type == Type.STICK
        && inputControlsView.isAdaptiveJoysticks()
        && !inputControlsView.isEditMode();
  }

  private boolean isAdaptiveHidden() {
    return isAdaptiveStick() && !isEngaged();
  }

  private boolean acceptsTouchAt(float x, float y) {
    if (!isAdaptiveStick()) return containsPoint(x, y);
    Rect box = getBoundingBox();
    float reach = box.width() * ADAPTIVE_REACH;
    if (Mathf.distance((float) box.centerX(), (float) box.centerY(), x, y) > reach) return false;
    return !inputControlsView.isPointOverOtherElement(this, x, y);
  }

  private void shiftAdaptiveOrigin(float x, float y) {
    Rect box = getBoundingBox();
    int half = box.width() / 2;
    int cx = Math.round(Mathf.clamp(x, half, inputControlsView.getWidth() - half));
    int cy = Math.round(Mathf.clamp(y, half, inputControlsView.getHeight() - half));
    box.offset(cx - box.centerX(), cy - box.centerY());
    adaptiveShifted = true;
  }

  private void restoreAdaptiveOrigin() {
    if (!adaptiveShifted) return;
    adaptiveShifted = false;
    computeBoundingBox();
  }

  public boolean containsPoint(float x, float y) {
    if (type == Type.RADIAL_MENU && radialMenuExpanded) {
      float outerRadius = boundingBox.width() + (inputControlsView.getSnappingSize() * scale);
      return Mathf.distance((float) boundingBox.centerX(), (float) boundingBox.centerY(), x, y) < outerRadius;
    }
    return getBoundingBox().contains((int) (x + 0.5f), (int) (y + 0.5f));
  }

  private boolean isKeepButtonPressedAfterMinTime() {
    Binding binding = getBindingAt(0);
    return !toggleSwitch
        && (binding == Binding.GAMEPAD_BUTTON_L3 || binding == Binding.GAMEPAD_BUTTON_R3);
  }

  private void dispatchButtonBinding(boolean pressed) {
    // Fire every configured binding slot, in slot order, skipping NONE and duplicates.
    Binding[] ordered = new Binding[bindings.length];
    int count = 0;
    for (int i = 0; i < bindings.length; i++) {
      Binding binding = bindings[i];
      if (binding == Binding.NONE) continue;
      boolean dup = false;
      for (int k = 0; k < count; k++) if (ordered[k] == binding) { dup = true; break; }
      if (!dup) ordered[count++] = binding;
    }
    // Press keeps slot order; release reverses when the toggle is on (combo release order).
    if (!pressed && inputControlsView.isReverseBindingOrder()) {
      for (int i = count - 1; i >= 0; i--) inputControlsView.handleInputEvent(ordered[i], false);
    } else {
      for (int i = 0; i < count; i++) inputControlsView.handleInputEvent(ordered[i], pressed);
    }
  }

  public boolean isCapturing(int pointerId) {
    return currentPointerId == pointerId;
  }

  public boolean isSwipeTarget() {
    if (type == Type.RADIAL_MENU) return true;
    return (type == Type.BUTTON || type == Type.D_PAD) && swipeable;
  }

  public boolean handleTouchDown(int pointerId, float x, float y) {
    if (currentPointerId == -1 && acceptsTouchAt(x, y)) {
      if (type != Type.RANGE_BUTTON && type != Type.RADIAL_MENU) {
        boolean hasBinding = false;
        for (Binding binding : bindings) {
          if (binding != Binding.NONE) {
            hasBinding = true;
            break;
          }
        }
        if (!hasBinding) return false;
      }

      currentPointerId = pointerId;
      if (isAdaptiveStick()) shiftAdaptiveOrigin(x, y);
      if (type == Type.BUTTON) {
        pressGeneration++;
        if (isKeepButtonPressedAfterMinTime()) touchTime = System.currentTimeMillis();
        if (!toggleSwitch || !selected) {
          dispatchButtonBinding(true);
        }
        inputControlsView.invalidate();
        return true;
      } else if (type == Type.RADIAL_MENU) {
        wasExpandedOnDown = radialMenuExpanded;
        if (!radialMenuExpanded) {
          radialMenuExpanded = true;
          paths = null;
          isRadialBindingCurrentlyHeld = false;
        } else {
          activeRadialBindingIndex = getRadialBindingIndexAt(x, y);
          boolean isInsideRadius = isPointerInsideRadialMenuRadius(x, y);
          
          if (activeRadialBindingIndex != -1) {
            Binding binding = getBindingAt(activeRadialBindingIndex);
            if (isInsideRadius) {
              inputControlsView.handleInputEvent(binding, true);
              isRadialBindingCurrentlyHeld = true;
            } else if (binding != Binding.NONE) {
              inputControlsView.handleInputEvent(binding, true);
              inputControlsView.postDelayed(() -> inputControlsView.handleInputEvent(binding, false), 30);
            }
          } else if (Mathf.distance((float) boundingBox.centerX(), (float) boundingBox.centerY(), x, y) < boundingBox.width() * 0.5f) {
            radialMenuExpanded = false;
            paths = null;
            isRadialBindingCurrentlyHeld = false;
          }
        }
        inputControlsView.invalidate();
        return true;
      } else if (type == Type.RANGE_BUTTON) {
        scroller.handleTouchDown(x, y);
        inputControlsView.invalidate();
        return true;
      } else {
        if (type == Type.TRACKPAD) {
          if (currentPosition == null) currentPosition = new PointF();
          currentPosition.set(x, y);
          if (trackpadOrigin == null) trackpadOrigin = new PointF();
          trackpadOrigin.set(x, y);
        }
        inputControlsView.invalidate();
        return handleTouchMove(pointerId, x, y);
      }
    } else return false;
  }

  public boolean handleTouchMove(int pointerId, float x, float y) {
    if (pointerId == currentPointerId && type == Type.BUTTON) {
      return true;
    }

    if (pointerId == currentPointerId && type == Type.RADIAL_MENU && radialMenuExpanded) {
      int index = getRadialBindingIndexAt(x, y);
      boolean isInsideRadius = isPointerInsideRadialMenuRadius(x, y);

      if (index != activeRadialBindingIndex) {
        if (activeRadialBindingIndex != -1 && isRadialBindingCurrentlyHeld) {
          inputControlsView.handleInputEvent(getBindingAt(activeRadialBindingIndex), false);
          isRadialBindingCurrentlyHeld = false;
        }

        activeRadialBindingIndex = index;

        if (activeRadialBindingIndex != -1) {
          Binding binding = getBindingAt(activeRadialBindingIndex);
          if (isInsideRadius) {
            inputControlsView.handleInputEvent(binding, true);
            isRadialBindingCurrentlyHeld = true;
          } else if (binding != Binding.NONE) {
            inputControlsView.handleInputEvent(binding, true);
            inputControlsView.postDelayed(() -> inputControlsView.handleInputEvent(binding, false), 30);
          }
        }
      } else if (isInsideRadius != isRadialBindingCurrentlyHeld) {
        if (activeRadialBindingIndex != -1) {
          Binding binding = getBindingAt(activeRadialBindingIndex);
          if (isInsideRadius) {
            inputControlsView.handleInputEvent(binding, true);
            isRadialBindingCurrentlyHeld = true;
          } else {
            inputControlsView.handleInputEvent(binding, false);
            isRadialBindingCurrentlyHeld = false;
          }
        }
      }

      inputControlsView.invalidate();
      return true;
    }

    if (pointerId == currentPointerId
        && (type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD)) {
      float deltaX, deltaY;
      Rect boundingBox = getBoundingBox();
      float radius = boundingBox.width() * 0.5f;
      TouchpadView touchpadView = inputControlsView.getTouchpadView();

      if (type == Type.TRACKPAD) {
        if (currentPosition == null) currentPosition = new PointF();
        float[] deltaPoint =
            touchpadView.computeDeltaPoint(currentPosition.x, currentPosition.y, x, y);
        deltaX = deltaPoint[0];
        deltaY = deltaPoint[1];
        currentPosition.set(x, y);
      } else {
        float localX = x - boundingBox.left;
        float localY = y - boundingBox.top;
        float offsetX = localX - radius;
        float offsetY = localY - radius;

        float distance = Mathf.lengthSq(radius - localX, radius - localY);
        if (distance > radius * radius) {
          float angle = (float) Math.atan2(offsetY, offsetX);
          offsetX = (float) (Math.cos(angle) * radius);
          offsetY = (float) (Math.sin(angle) * radius);
        }

        deltaX = Mathf.clamp(offsetX / radius, -1, 1);
        deltaY = Mathf.clamp(offsetY / radius, -1, 1);
      }

      if (type == Type.STICK) {
        if (currentPosition == null) currentPosition = new PointF();
        currentPosition.x = boundingBox.left + deltaX * radius + radius;
        currentPosition.y = boundingBox.top + deltaY * radius + radius;
        Binding firstBinding = getBindingAt(0);
        if (firstBinding.isGamepad()) {
          float magnitude = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
          float finalX = 0;
          float finalY = 0;

          if (magnitude > STICK_DEAD_ZONE) {
            float normalizedX = deltaX / magnitude;
            float normalizedY = deltaY / magnitude;
            float scaledMagnitude = Math.max(0, magnitude - 0.01f) * STICK_SENSITIVITY;
            scaledMagnitude = Math.min(scaledMagnitude, 1.0f);
            finalX = normalizedX * scaledMagnitude;
            finalY = normalizedY * scaledMagnitude;
          }

          inputControlsView.handleStickInput(firstBinding, finalX, finalY);
          for (byte i = 0; i < 4; i++) {
            this.states[i] = true;
          }
        } else {
          float adjDeltaX = (Math.abs(deltaX) < Math.abs(deltaY) * STICK_CROSS_ZONE) ? 0 : deltaX;
          float adjDeltaY = (Math.abs(deltaY) < Math.abs(deltaX) * STICK_CROSS_ZONE) ? 0 : deltaY;
          final boolean[] states = {
            adjDeltaY <= -STICK_DEAD_ZONE,
            adjDeltaX >= STICK_DEAD_ZONE,
            adjDeltaY >= STICK_DEAD_ZONE,
            adjDeltaX <= -STICK_DEAD_ZONE
          };

          for (byte i = 0; i < 4; i++) {
            float value = i == 1 || i == 3 ? deltaX : deltaY;
            Binding binding = getBindingAt(i);
            boolean state = binding.isMouseMove() ? (states[i] || states[(i + 2) % 4]) : states[i];
            inputControlsView.handleInputEvent(binding, state, value);
            this.states[i] = state;
          }
        }

        inputControlsView.invalidateControlElement(this);
      } else if (type == Type.TRACKPAD) {
        Binding firstBinding = getBindingAt(0);
        if (firstBinding.isGamepad()) {
          if (trackpadOrigin == null) trackpadOrigin = new PointF(x, y);
          float offsetX = x - trackpadOrigin.x;
          float offsetY = y - trackpadOrigin.y;
          float distance = (float) Math.sqrt(offsetX * offsetX + offsetY * offsetY);
          float finalX = 0;
          float finalY = 0;
          if (distance > 0) {
            float magnitude = Math.min(distance / radius, 1.0f);
            if (magnitude > STICK_DEAD_ZONE) {
              float scaled = (magnitude - STICK_DEAD_ZONE) / (1.0f - STICK_DEAD_ZONE);
              finalX = (offsetX / distance) * scaled;
              finalY = (offsetY / distance) * scaled;
            }
          }
          inputControlsView.handleStickInput(firstBinding, finalX, finalY);
          for (byte i = 0; i < 4; i++) {
            this.states[i] = true;
          }
        } else {
          final boolean[] states = {
            deltaY <= -TRACKPAD_MIN_SPEED,
            deltaX >= TRACKPAD_MIN_SPEED,
            deltaY >= TRACKPAD_MIN_SPEED,
            deltaX <= -TRACKPAD_MIN_SPEED
          };

          int cursorDx = 0;
          int cursorDy = 0;

          for (byte i = 0; i < 4; i++) {
            float value = (i == 1 || i == 3 ? deltaX : deltaY);
            Binding binding = getBindingAt(i);
            if (Math.abs(value) > TouchpadView.CURSOR_ACCELERATION_THRESHOLD)
              value *= TouchpadView.CURSOR_ACCELERATION;
            if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
              cursorDx = Mathf.roundPoint(value);
            } else if (binding == Binding.MOUSE_MOVE_UP || binding == Binding.MOUSE_MOVE_DOWN) {
              cursorDy = Mathf.roundPoint(value);
            } else {
              inputControlsView.handleInputEvent(binding, states[i], value);
              this.states[i] = states[i];
            }
          }

          if (cursorDx != 0 || cursorDy != 0) {
            XServer xServer = inputControlsView.getXServer();
            if (xServer.isRelativeMouseMovement()) {
              xServer.updatePointerForDisplayDelta(cursorDx, cursorDy);
              xServer.getWinHandler().mouseMoveDelta(cursorDx, cursorDy);
            } else inputControlsView.getXServer().injectPointerMoveDelta(cursorDx, cursorDy);
          }
        }
      } else {
        final boolean[] states = {
          deltaY <= -DPAD_DEAD_ZONE,
          deltaX >= DPAD_DEAD_ZONE,
          deltaY >= DPAD_DEAD_ZONE,
          deltaX <= -DPAD_DEAD_ZONE
        };

        boolean statesChanged = false;
        for (byte i = 0; i < 4; i++) {
          float value = i == 1 || i == 3 ? deltaX : deltaY;
          Binding binding = getBindingAt(i);
          boolean state = binding.isMouseMove() ? (states[i] || states[(i + 2) % 4]) : states[i];
          inputControlsView.handleInputEvent(binding, state, value);
          if (this.states[i] != state) statesChanged = true;
          this.states[i] = state;
        }
        if (statesChanged) inputControlsView.invalidate();
      }

      return true;
    } else if (pointerId == currentPointerId && type == Type.RANGE_BUTTON) {
      scroller.handleTouchMove(x, y);
      return true;
    } else return false;
  }

  public boolean handleTouchUp(int pointerId, float x, float y) {
    if (pointerId != currentPointerId) return false;

    if (type == Type.BUTTON) {
      if (isKeepButtonPressedAfterMinTime() && touchTime != null) {
        long held = System.currentTimeMillis() - (long) touchTime;
        long delay = Math.max(0L, BUTTON_MIN_TIME_TO_KEEP_PRESSED - held);
        final int generation = ++pressGeneration;
        inputControlsView.postDelayed(
            () -> {
              if (generation != pressGeneration) return;
              dispatchButtonBinding(false);
              inputControlsView.invalidate();
            },
            delay);
        touchTime = null;
      } else {
        if (!toggleSwitch || selected) {
          dispatchButtonBinding(false);
        }
        if (toggleSwitch) selected = !selected;
      }
      inputControlsView.invalidate();
    } else if (type == Type.RADIAL_MENU) {
      if (activeRadialBindingIndex != -1) {
        if (isRadialBindingCurrentlyHeld) {
           inputControlsView.handleInputEvent(getBindingAt(activeRadialBindingIndex), false);
        }
        
        activeRadialBindingIndex = -1;
        isRadialBindingCurrentlyHeld = false;
        radialMenuExpanded = false;
        paths = null;
      } else {
        if (wasExpandedOnDown) {
          radialMenuExpanded = false;
          paths = null;
        }
      }
      inputControlsView.invalidate();
    } else if (type == Type.RANGE_BUTTON
        || type == Type.D_PAD
        || type == Type.STICK
        || type == Type.TRACKPAD) {
      for (byte i = 0; i < states.length; i++) {
        if (states[i]) inputControlsView.handleInputEvent(getBindingAt(i), false);
        states[i] = false;
      }

      if (type == Type.RANGE_BUTTON) {
        scroller.handleTouchUp();
      }
      if (type == Type.STICK) {
        Binding firstBinding = getBindingAt(0);
        if (firstBinding.isGamepad()) {
          inputControlsView.handleStickInput(firstBinding, 0.0f, 0.0f);
        }
        currentPosition = null;
        restoreAdaptiveOrigin();
      }
      if (type == Type.TRACKPAD) {
        Binding firstBinding = getBindingAt(0);
        if (firstBinding.isGamepad()) {
          inputControlsView.handleStickInput(firstBinding, 0.0f, 0.0f);
        }
        currentPosition = null;
        trackpadOrigin = null;
      }

      inputControlsView.invalidate();
    }

    currentPointerId = -1;
    return true;
  }

  private int getRadialBindingIndexAt(float x, float y) {
    if (bindings.length == 0) return -1;
    int snappingSize = inputControlsView.getSnappingSize();
    float cx = boundingBox.centerX();
    float cy = boundingBox.centerY();
    float radius = boundingBox.width() * 0.5f;
    float innerRadius = radius + snappingSize * 0.5f;

    float distance = Mathf.distance((float) cx, (float) cy, x, y);
    if (distance >= innerRadius) {
      float angle = (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
      if (angle < 0) angle += 360;
      angle = (angle + 90) % 360;

      int index = (int) (angle / (360.0f / bindings.length));
      return (index >= 0 && index < bindings.length) ? index : -1;
    }
    return -1;
  }

  private boolean isPointerInsideRadialMenuRadius(float x, float y) {
    int snappingSize = inputControlsView.getSnappingSize();
    float cx = boundingBox.centerX();
    float cy = boundingBox.centerY();
    float outerRadius = boundingBox.width() + (snappingSize * scale);
    float distance = Mathf.distance((float) cx, (float) cy, x, y);
    return distance <= outerRadius;
  }

  private void handleRadialMenuClick(float x, float y) {
    int index = getRadialBindingIndexAt(x, y);
    if (index != -1) {
      Binding binding = getBindingAt(index);
      if (binding != Binding.NONE) {
        radialMenuExpanded = false;
        paths = null;
        inputControlsView.handleInputEvent(binding, true);
        inputControlsView.postDelayed(() -> inputControlsView.handleInputEvent(binding, false), 30);
      }
    }
  }

  public boolean handleTouchUp(int pointerId) {
    return handleTouchUp(pointerId, 0, 0);
  }

  public PointF getCurrentPosition() {
    if (currentPosition == null) {
      currentPosition = new PointF(x, y); // Initialize to the center (same as outer circle)
    }
    return currentPosition;
  }

  // New setter for current position to allow resetting
  public void setCurrentPosition(float x, float y) {
    if (currentPosition == null) {
      currentPosition = new PointF();
    }
    currentPosition.set(x, y);
    inputControlsView.invalidate();
  }
}
