package com.winlator.cmod.runtime.display.xserver;

import android.util.SparseArray;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

public class CursorManager extends XResourceManager {
  private final SparseArray<Cursor> cursors = new SparseArray<>();
  private final DrawableManager drawableManager;

  public CursorManager(DrawableManager drawableManager) {
    this.drawableManager = drawableManager;
  }

  public Cursor getCursor(int id) {
    return cursors.get(id);
  }

  public Cursor createCursor(int id, short x, short y, Pixmap sourcePixmap, Pixmap maskPixmap) {
    if (cursors.indexOfKey(id) >= 0) return null;
    Drawable drawable =
        drawableManager.createDrawable(
            0,
            sourcePixmap.drawable.width,
            sourcePixmap.drawable.height,
            sourcePixmap.drawable.visual);
    Cursor cursor =
        new Cursor(
            id,
            x,
            y,
            drawable,
            sourcePixmap.drawable,
            maskPixmap != null ? maskPixmap.drawable : null);
    cursors.put(id, cursor);
    triggerOnCreateResourceListener(cursor);
    return cursor;
  }

  private static final String[] FALLBACK_ARROW = {
    "X          ",
    "XX         ",
    "X.X        ",
    "X..X       ",
    "X...X      ",
    "X....X     ",
    "X.....X    ",
    "X......X   ",
    "X.......X  ",
    "X........X ",
    "X.....XXXXX",
    "X..X..X    ",
    "X.X X..X   ",
    "XX  X..X   ",
    "X    X..X  ",
    "     X..X  ",
    "      XX   "
  };

  public Cursor createFallbackArrowCursor(int id) {
    if (cursors.indexOfKey(id) >= 0) return null;
    short width = (short) FALLBACK_ARROW[0].length();
    short height = (short) FALLBACK_ARROW.length;
    ByteBuffer pixels =
        ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
    IntBuffer data = pixels.asIntBuffer();
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        char c = FALLBACK_ARROW[y].charAt(x);
        data.put(y * width + x, c == 'X' ? 0xff000000 : c == '.' ? 0xffffffff : 0);
      }
    }
    return addArgbCursor(id, 0, 0, width, height, pixels);
  }

  /**
   * libXcursor hands over premultiplied ARGB32 pixels, while cursor drawables are blended with
   * straight alpha, so the alpha is divided back out here.
   */
  public Cursor createArgbCursor(
      int id, int hotX, int hotY, short width, short height, ByteBuffer argb) {
    if (cursors.indexOfKey(id) >= 0) return null;
    if (width <= 0 || height <= 0 || argb.capacity() < width * height * 4) return null;

    ByteBuffer pixels =
        ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
    IntBuffer src = argb.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
    IntBuffer dst = pixels.asIntBuffer();
    boolean visible = false;
    for (int i = 0; i < width * height; i++) {
      int pixel = src.get(i);
      int alpha = pixel >>> 24;
      if (alpha == 0) {
        dst.put(i, 0);
        continue;
      }
      visible = true;
      dst.put(i, alpha == 0xff ? pixel : (alpha << 24) | unpremultiply(pixel, alpha));
    }
    Cursor cursor = addArgbCursor(id, hotX, hotY, width, height, pixels);
    // A fully transparent cursor is how games hide the pointer, so it must not become an arrow.
    cursor.setVisible(visible);
    return cursor;
  }

  private static int unpremultiply(int pixel, int alpha) {
    int red = Math.min(0xff, (((pixel >> 16) & 0xff) * 0xff + alpha - 1) / alpha);
    int green = Math.min(0xff, (((pixel >> 8) & 0xff) * 0xff + alpha - 1) / alpha);
    int blue = Math.min(0xff, ((pixel & 0xff) * 0xff + alpha - 1) / alpha);
    return (red << 16) | (green << 8) | blue;
  }

  private Cursor addArgbCursor(
      int id, int hotX, int hotY, short width, short height, ByteBuffer pixels) {
    Drawable drawable =
        drawableManager.createDrawable(IDGenerator.generate(), width, height, (byte) 32);
    drawable.drawImage(
        (short) 0,
        (short) 0,
        (short) 0,
        (short) 0,
        width,
        height,
        (byte) 32,
        pixels,
        width,
        height);
    Cursor cursor =
        new Cursor(
            id,
            Math.max(0, Math.min(hotX, width - 1)),
            Math.max(0, Math.min(hotY, height - 1)),
            drawable,
            null,
            null);
    cursors.put(id, cursor);
    triggerOnCreateResourceListener(cursor);
    return cursor;
  }

  public void freeCursor(int id) {
    triggerOnFreeResourceListener(cursors.get(id));
    cursors.remove(id);
  }

  private static boolean isEmptyMaskImage(Drawable maskImage) {
    IntBuffer maskData = maskImage.getData().asIntBuffer();
    boolean result = true;
    for (int i = 0; i < maskData.capacity(); i++) {
      if (maskData.get(i) != 0x000000) {
        result = false;
        break;
      }
    }
    return result;
  }

  public void recolorCursor(
      Cursor cursor,
      byte foreRed,
      byte foreGreen,
      byte foreBlue,
      byte backRed,
      byte backGreen,
      byte backBlue) {
    if (cursor.maskImage != null) {
      boolean visible = !isEmptyMaskImage(cursor.maskImage);
      cursor.setVisible(visible);
      if (visible)
        cursor.cursorImage.drawAlphaMaskedBitmap(
            foreRed,
            foreGreen,
            foreBlue,
            backRed,
            backGreen,
            backBlue,
            cursor.sourceImage,
            cursor.maskImage);
    }
  }
}
