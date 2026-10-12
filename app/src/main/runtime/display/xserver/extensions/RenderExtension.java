package com.winlator.cmod.runtime.display.xserver.extensions;

import static com.winlator.cmod.runtime.display.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.Log;
import android.util.SparseArray;
import com.winlator.cmod.runtime.display.connector.XInputStream;
import com.winlator.cmod.runtime.display.connector.XOutputStream;
import com.winlator.cmod.runtime.display.connector.XStreamLock;
import com.winlator.cmod.runtime.display.xserver.Cursor;
import com.winlator.cmod.runtime.display.xserver.Drawable;
import com.winlator.cmod.runtime.display.xserver.IDGenerator;
import com.winlator.cmod.runtime.display.xserver.XClient;
import com.winlator.cmod.runtime.display.xserver.XLock;
import com.winlator.cmod.runtime.display.xserver.XServer;
import com.winlator.cmod.runtime.display.xserver.errors.BadIdChoice;
import com.winlator.cmod.runtime.display.xserver.errors.BadImplementation;
import com.winlator.cmod.runtime.display.xserver.errors.XRequestError;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * The cursor subset of X RENDER: libXcursor, which winex11 loads, only hands over full-colour ARGB
 * cursors when the server advertises RENDER, otherwise games get two-colour core cursors. Only the
 * requests libXcursor uses are implemented (QueryVersion, QueryPictFormats, CreatePicture,
 * FreePicture, CreateCursor, CreateAnimCursor). Wine's own GDI use of RENDER is kept off per prefix
 * with "ClientSideWithRender"="N", so nothing else is expected. Registered through
 * {@link XServer#enableRenderCursors()}.
 */
public class RenderExtension implements Extension {
  public static final byte MAJOR_OPCODE = -108;
  private static final String TAG = "RenderExtension";
  private static final int MAX_FAILURES = 8;
  private static final int MAX_PICTURE_PIXELS = 512 * 512;

  private static final int FORMAT_A1 = IDGenerator.generate();
  private static final int FORMAT_RGB24 = IDGenerator.generate();
  private static final int FORMAT_ARGB32 = IDGenerator.generate();

  private static abstract class ClientOpcodes {
    private static final byte QUERY_VERSION = 0;
    private static final byte QUERY_PICT_FORMATS = 1;
    private static final byte CREATE_PICTURE = 4;
    private static final byte FREE_PICTURE = 7;
    private static final byte CREATE_CURSOR = 27;
    private static final byte CREATE_ANIM_CURSOR = 31;
  }

  /** Pixels snapshotted at CreatePicture time: libXcursor frees the pixmap before CreateCursor. */
  private static class Picture {
    final short width;
    final short height;
    final ByteBuffer argb;

    Picture(short width, short height, ByteBuffer argb) {
      this.width = width;
      this.height = height;
      this.argb = argb;
    }
  }

  private final XServer xServer;
  private final SparseArray<Picture> pictures = new SparseArray<>();
  private int failures = 0;

  public RenderExtension(XServer xServer) {
    this.xServer = xServer;
  }

  @Override
  public String getName() {
    return "RENDER";
  }

  @Override
  public byte getMajorOpcode() {
    return MAJOR_OPCODE;
  }

  @Override
  public byte getFirstErrorId() {
    return 0;
  }

  @Override
  public byte getFirstEventId() {
    return 0;
  }

  @Override
  public int getNumEvents() {
    return 0;
  }

  @Override
  public int getNumErrors() {
    return 0;
  }

  @Override
  public void setFirstEventId(byte id) {}

  @Override
  public void setFirstErrorId(byte id) {}

  private boolean safeMode() {
    return failures >= MAX_FAILURES;
  }

  private void noteFailure(String what, Throwable t) {
    failures++;
    Log.w(
        TAG,
        what
            + " failed ("
            + failures
            + "/"
            + MAX_FAILURES
            + ")"
            + (safeMode() ? " - plain arrow cursors for the rest of this session" : ""),
        t);
  }

  private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream)
      throws IOException {
    inputStream.skip(8);
    try (XStreamLock lock = outputStream.lock()) {
      outputStream.writeByte(RESPONSE_CODE_SUCCESS);
      outputStream.writeByte((byte) 0);
      outputStream.writeShort(client.getSequenceNumber());
      outputStream.writeInt(0);
      outputStream.writeInt(0);
      outputStream.writeInt(11);
      outputStream.writePad(16);
    }
  }

  private static void writeFormat(
      XOutputStream outputStream,
      int id,
      int depth,
      int redShift,
      int greenShift,
      int blueShift,
      int alphaShift,
      int alphaMask,
      boolean rgb) {
    outputStream.writeInt(id);
    outputStream.writeByte((byte) 1); // PictTypeDirect
    outputStream.writeByte((byte) depth);
    outputStream.writePad(2);
    outputStream.writeShort((short) redShift);
    outputStream.writeShort((short) (rgb ? 0xff : 0));
    outputStream.writeShort((short) greenShift);
    outputStream.writeShort((short) (rgb ? 0xff : 0));
    outputStream.writeShort((short) blueShift);
    outputStream.writeShort((short) (rgb ? 0xff : 0));
    outputStream.writeShort((short) alphaShift);
    outputStream.writeShort((short) alphaMask);
    outputStream.writeInt(0); // colormap
  }

  private void queryPictFormats(XClient client, XInputStream inputStream, XOutputStream outputStream)
      throws IOException {
    // 3 formats (28 bytes each); 1 screen (8) with depths 1, 24 and 32 (8 each, one 8-byte visual
    // on the depth-32 root); 1 subpixel order (4).
    final int formatCount = 3, depthCount = 3, visualCount = 1;
    final int bytes = formatCount * 28 + 8 + depthCount * 8 + visualCount * 8 + 4;
    int rootVisual = xServer.pixmapManager.visual.id;
    try (XStreamLock lock = outputStream.lock()) {
      outputStream.writeByte(RESPONSE_CODE_SUCCESS);
      outputStream.writeByte((byte) 0);
      outputStream.writeShort(client.getSequenceNumber());
      outputStream.writeInt(bytes / 4);
      outputStream.writeInt(formatCount);
      outputStream.writeInt(1);
      outputStream.writeInt(depthCount);
      outputStream.writeInt(visualCount);
      outputStream.writeInt(1);
      outputStream.writePad(4);

      writeFormat(outputStream, FORMAT_A1, 1, 0, 0, 0, 0, 0x1, false);
      writeFormat(outputStream, FORMAT_RGB24, 24, 16, 8, 0, 0, 0, true);
      writeFormat(outputStream, FORMAT_ARGB32, 32, 16, 8, 0, 24, 0xff, true);

      outputStream.writeInt(depthCount);
      outputStream.writeInt(FORMAT_ARGB32); // fallback
      for (int depth : new int[] {1, 24}) {
        outputStream.writeByte((byte) depth);
        outputStream.writePad(1);
        outputStream.writeShort((short) 0);
        outputStream.writePad(4);
      }
      outputStream.writeByte((byte) 32);
      outputStream.writePad(1);
      outputStream.writeShort((short) 1);
      outputStream.writePad(4);
      outputStream.writeInt(rootVisual);
      outputStream.writeInt(FORMAT_ARGB32);

      outputStream.writeInt(0); // SubPixelUnknown
    }
  }

  private void createPicture(XClient client, XInputStream inputStream) throws XRequestError {
    int pictureId = inputStream.readInt();
    int drawableId = inputStream.readInt();
    inputStream.readInt(); // format
    inputStream.readInt(); // value-mask
    client.skipRequest(); // attribute values: none of them matter for a cursor source

    if (!client.isValidResourceId(pictureId)) throw new BadIdChoice(pictureId);

    Picture picture = new Picture((short) 0, (short) 0, null);
    try (XLock lock =
        xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
      Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
      if (drawable != null
          && drawable.width > 0
          && drawable.height > 0
          && drawable.width * drawable.height <= MAX_PICTURE_PIXELS) {
        synchronized (drawable.renderLock) {
          if (drawable.getData() != null) {
            ByteBuffer argb =
                drawable.getImage((short) 0, (short) 0, drawable.width, drawable.height);
            picture = new Picture(drawable.width, drawable.height, argb);
          }
        }
      }
    } catch (RuntimeException e) {
      // An unknown or oversized drawable becomes an empty picture: its cursor is the arrow.
      noteFailure("CreatePicture", e);
    }
    synchronized (pictures) {
      pictures.put(pictureId, picture);
    }
  }

  private void freePicture(XInputStream inputStream) {
    int pictureId = inputStream.readInt();
    synchronized (pictures) {
      pictures.remove(pictureId);
    }
  }

  private Picture getPicture(int id) {
    synchronized (pictures) {
      return pictures.get(id);
    }
  }

  private void makeCursor(XClient client, int cursorId, Picture picture, int hotX, int hotY)
      throws XRequestError {
    if (!client.isValidResourceId(cursorId)) throw new BadIdChoice(cursorId);
    Cursor cursor = null;
    try (XLock lock =
        xServer.lock(
            XServer.Lockable.PIXMAP_MANAGER,
            XServer.Lockable.DRAWABLE_MANAGER,
            XServer.Lockable.CURSOR_MANAGER)) {
      if (!safeMode() && picture != null && picture.argb != null) {
        try {
          cursor =
              xServer.cursorManager.createArgbCursor(
                  cursorId, hotX, hotY, picture.width, picture.height, picture.argb.duplicate());
          if (cursor == null) throw new BadIdChoice(cursorId);
        } catch (RuntimeException e) {
          noteFailure("CreateCursor " + picture.width + "x" + picture.height, e);
        }
      }
      if (cursor == null) cursor = xServer.cursorManager.createFallbackArrowCursor(cursorId);
    }
    if (cursor == null) throw new BadIdChoice(cursorId);
    client.registerAsOwnerOfResource(cursor);
  }

  private void createCursor(XClient client, XInputStream inputStream) throws XRequestError {
    int cursorId = inputStream.readInt();
    Picture picture = getPicture(inputStream.readInt());
    int hotX = inputStream.readUnsignedShort();
    int hotY = inputStream.readUnsignedShort();
    makeCursor(client, cursorId, picture, hotX, hotY);
  }

  private void createAnimCursor(XClient client, XInputStream inputStream) throws XRequestError {
    int cursorId = inputStream.readInt();
    int frameCount = client.getRemainingRequestLength() / 8;
    int firstFrame = frameCount > 0 ? inputStream.readInt() : 0;
    client.skipRequest(); // remaining frames + delays: shown as the first frame, static

    Picture picture = null;
    int hotX = 0, hotY = 0;
    try (XLock lock =
        xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
      Cursor first = xServer.cursorManager.getCursor(firstFrame);
      if (first != null && first.cursorImage != null) {
        Drawable image = first.cursorImage;
        synchronized (image.renderLock) {
          if (image.getData() != null) {
            picture =
                new Picture(
                    image.width,
                    image.height,
                    image.getImage((short) 0, (short) 0, image.width, image.height));
          }
        }
        hotX = first.hotSpotX;
        hotY = first.hotSpotY;
      }
    } catch (RuntimeException e) {
      noteFailure("CreateAnimCursor", e);
    }
    makeCursor(client, cursorId, picture, hotX, hotY);
  }

  @Override
  public void handleRequest(
      XClient client, XInputStream inputStream, XOutputStream outputStream)
      throws IOException, XRequestError {
    int opcode = client.getRequestData();
    try {
      switch (opcode) {
        case ClientOpcodes.QUERY_VERSION:
          queryVersion(client, inputStream, outputStream);
          break;
        case ClientOpcodes.QUERY_PICT_FORMATS:
          queryPictFormats(client, inputStream, outputStream);
          break;
        case ClientOpcodes.CREATE_PICTURE:
          createPicture(client, inputStream);
          break;
        case ClientOpcodes.FREE_PICTURE:
          freePicture(inputStream);
          break;
        case ClientOpcodes.CREATE_CURSOR:
          createCursor(client, inputStream);
          break;
        case ClientOpcodes.CREATE_ANIM_CURSOR:
          createAnimCursor(client, inputStream);
          break;
        default:
          throw new BadImplementation();
      }
    } catch (RuntimeException e) {
      // Never let a RENDER bug drop the game's X connection: report it as an X error instead.
      noteFailure("RENDER request " + opcode, e);
      throw new BadImplementation();
    }
  }
}
