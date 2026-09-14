package com.winlator.cmod.runtime.input.controls;

import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

public class FakeInputWriter {
  public static final short ABS_BRAKE = 10;
  public static final short ABS_GAS = 9;
  public static final short ABS_HAT0X = 16;
  public static final short ABS_HAT0Y = 17;
  public static final short ABS_RX = 3;
  public static final short ABS_RY = 4;
  public static final short ABS_X = 0;
  public static final short ABS_Y = 1;
  private static final int EVENT_SIZE = 24;
  private static final int MAX_FAKE_INPUT_SLOTS = 4;
  private static final int RING_CAPACITY_EVENTS = 4096;
  private static final int RING_HEADER_SIZE = 64;
  private static final int RING_SIZE = RING_HEADER_SIZE + (RING_CAPACITY_EVENTS * EVENT_SIZE);
  private static final int RING_MAGIC = 0x46494252; // FIBR
  private static final int RING_VERSION = 2;
  private static final int RING_MAGIC_OFFSET = 0;
  private static final int RING_VERSION_OFFSET = 4;
  private static final int RING_EVENT_SIZE_OFFSET = 8;
  private static final int RING_CAPACITY_OFFSET = 12;
  private static final int RING_WRITE_SEQ_OFFSET = 16;
  private static final int RING_GENERATION_OFFSET = 24;
  // Authoritative absolute-state snapshot the native reader replays as a full
  // keyframe to heal any delta-stream desync. Written under a seqlock
  // (RING_SNAPSHOT_SEQ_OFFSET: odd = write in progress).
  private static final int RING_SNAPSHOT_SEQ_OFFSET = 32;
  private static final int RING_SNAPSHOT_BUTTONS_OFFSET = 40;
  private static final int RING_SNAPSHOT_AXES_OFFSET = 44; // short[8]
  private static final int RING_RESYNC_SEQ_OFFSET = 60;
  private static final String RING_DIR_NAME = "fakeinput-rings";
  public static final short EV_ABS = 3;
  public static final short EV_KEY = 1;
  public static final short EV_MSC = 4;
  public static final short EV_SYN = 0;
  private static final int MAX_EVENTS_PER_UPDATE = 32;
  private static final int BUFFER_SIZE = MAX_EVENTS_PER_UPDATE * EVENT_SIZE;
  public static final short MSC_SCAN = 4;
  public static final short SYN_REPORT = 0;
  private static final String TAG = "FakeInputWriter";
  private static final Object RING_LOCK = new Object();
  private static final RingSlot[] RING_SLOTS = new RingSlot[MAX_FAKE_INPUT_SLOTS];

  static {
    System.loadLibrary("winlator");
  }

  private static native void nativeStoreFence();
  private final File eventFile;
  private final int slot;
  private int prevHatX;
  private int prevHatY;
  private int prevThumbLX;
  private int prevThumbLY;
  private int prevThumbRX;
  private int prevThumbRY;
  private int prevTriggerL;
  private int prevTriggerR;
  public static final short BTN_A = 304;
  public static final short BTN_B = 305;
  public static final short BTN_X = 307;
  public static final short BTN_Y = 308;
  public static final short BTN_TL = 310;
  public static final short BTN_TR = 311;
  public static final short BTN_SELECT = 314;
  public static final short BTN_START = 315;
  public static final short BTN_THUMBL = 317;
  public static final short BTN_THUMBR = 318;
  private static final short[] BUTTON_MAP = {
    BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR, BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR
  };
  private boolean isOpen = false;
  private volatile boolean destroyed = false;
  private final boolean[] prevButtonStates = new boolean[12];
  private boolean hasChanges = false;
  // If a publish fails after prev* state has already advanced, the next frame
  // must re-emit the whole state once; otherwise an unchanged control would stay
  // silent and the ring snapshot would never catch up.
  private boolean pendingFullResend = false;
  private boolean publishedStateNeutral = true;
  private boolean forceResend = false;
  private final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

  public FakeInputWriter(String fakeInputPath, int slot) {
    this.slot = slot;
    this.eventFile = new File(fakeInputPath, NotificationCompat.CATEGORY_EVENT + slot);
    this.buffer.order(ByteOrder.LITTLE_ENDIAN);
  }

  private static final class RingSlot {
    ByteBuffer data;
    File ringFile;
    FileChannel ringChannel;
    RandomAccessFile ringRaf;
    String exportPath;
    long generation;
    boolean active;
    boolean everActivated;
  }

  public static void prepareRingSlots(File fakeInputDir, int slotCount) {
    int boundedSlotCount = Math.max(0, Math.min(slotCount, MAX_FAKE_INPUT_SLOTS));
    synchronized (RING_LOCK) {
      for (int slot = 0; slot < boundedSlotCount; slot++) {
        ensureRingSlotLocked(slot, fakeInputDir);
      }
    }
  }

  public static String getRingEnv(File fakeInputDir) {
    synchronized (RING_LOCK) {
      prepareRingSlotsLocked(fakeInputDir, MAX_FAKE_INPUT_SLOTS);
      return buildRingEnvLocked();
    }
  }

  public static void releaseAllRingSlots() {
    synchronized (RING_LOCK) {
      for (int slot = 0; slot < RING_SLOTS.length; slot++) {
        releaseRingSlotLocked(slot);
      }
    }
  }

  private static String buildRingEnvLocked() {
    StringBuilder builder = new StringBuilder();
    for (int slot = 0; slot < RING_SLOTS.length; slot++) {
      RingSlot ringSlot = RING_SLOTS[slot];
      if (ringSlot == null || ringSlot.data == null || ringSlot.exportPath == null) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append(';');
      }
      builder.append(slot).append('=').append(ringSlot.exportPath);
    }
    return builder.toString();
  }

  private static File getRingDir(File fakeInputDir) {
    if (fakeInputDir == null) {
      return null;
    }
    File inputDir = fakeInputDir.getAbsoluteFile();
    File parent = inputDir.getParentFile();
    return new File(parent != null ? parent : inputDir, RING_DIR_NAME);
  }

  private static File getRingFile(File fakeInputDir, int slot) {
    File ringDir = getRingDir(fakeInputDir);
    return ringDir != null ? new File(ringDir, "ring" + slot) : null;
  }

  private static String getCanonicalOrAbsolutePath(File file) {
    try {
      return file.getCanonicalPath();
    } catch (IOException e) {
      return file.getAbsolutePath();
    }
  }

  private static void initializeRingHeader(ByteBuffer data) {
    data.order(ByteOrder.LITTLE_ENDIAN);
    data.putInt(RING_MAGIC_OFFSET, RING_MAGIC);
    data.putInt(RING_VERSION_OFFSET, RING_VERSION);
    data.putInt(RING_EVENT_SIZE_OFFSET, EVENT_SIZE);
    data.putInt(RING_CAPACITY_OFFSET, RING_CAPACITY_EVENTS);
    data.putLong(RING_WRITE_SEQ_OFFSET, 0L);
    data.putLong(RING_GENERATION_OFFSET, 0L);
    data.putLong(RING_SNAPSHOT_SEQ_OFFSET, 0L);
    data.putInt(RING_SNAPSHOT_BUTTONS_OFFSET, 0);
    for (int i = 0; i < 8; i++) {
      data.putShort(RING_SNAPSHOT_AXES_OFFSET + (i * 2), (short) 0);
    }
    data.putInt(RING_RESYNC_SEQ_OFFSET, 0);
  }

  private static void releaseRingSlotLocked(int slot) {
    RingSlot ringSlot = RING_SLOTS[slot];
    if (ringSlot == null) {
      return;
    }
    ringSlot.data = null;
    if (ringSlot.ringChannel != null) {
      try {
        ringSlot.ringChannel.close();
      } catch (IOException ignored) {
      }
      ringSlot.ringChannel = null;
    }
    if (ringSlot.ringRaf != null) {
      try {
        ringSlot.ringRaf.close();
      } catch (IOException ignored) {
      }
      ringSlot.ringRaf = null;
    }
    if (ringSlot.ringFile != null && ringSlot.ringFile.exists()) {
      ringSlot.ringFile.delete();
    }
    RING_SLOTS[slot] = null;
  }

  private static void prepareRingSlotsLocked(File fakeInputDir, int slotCount) {
    int boundedSlotCount = Math.max(0, Math.min(slotCount, MAX_FAKE_INPUT_SLOTS));
    for (int slot = 0; slot < boundedSlotCount; slot++) {
      ensureRingSlotLocked(slot, fakeInputDir);
    }
  }

  private static RingSlot ensureRingSlotLocked(int slot, File fakeInputDir) {
    if (slot < 0 || slot >= MAX_FAKE_INPUT_SLOTS || fakeInputDir == null) {
      return null;
    }

    File desiredRingFile = getRingFile(fakeInputDir, slot);
    if (desiredRingFile == null) {
      return null;
    }
    desiredRingFile = desiredRingFile.getAbsoluteFile();
    RingSlot existing = RING_SLOTS[slot];
    if (existing != null && existing.data != null) {
      if (existing.ringFile != null && existing.ringFile.equals(desiredRingFile)) {
        return existing;
      }
      releaseRingSlotLocked(slot);
    }

    RingSlot fileRingSlot = createFileRingSlotLocked(slot, desiredRingFile);
    if (fileRingSlot != null) {
      RING_SLOTS[slot] = fileRingSlot;
      return fileRingSlot;
    }
    return null;
  }

  private static RingSlot createFileRingSlotLocked(int slot, File ringFile) {
    File ringDir = ringFile.getParentFile();
    if (ringDir == null || (!ringDir.exists() && !ringDir.mkdirs())) {
      Log.e(TAG, "Failed to create fake input ring directory for slot " + slot);
      return null;
    }

    RandomAccessFile raf = null;
    FileChannel channel = null;
    try {
      raf = new RandomAccessFile(ringFile, "rw");
      raf.setLength(RING_SIZE);
      channel = raf.getChannel();
      ByteBuffer data = channel.map(FileChannel.MapMode.READ_WRITE, 0, RING_SIZE);
      initializeRingHeader(data);

      RingSlot ringSlot = new RingSlot();
      ringSlot.data = data;
      ringSlot.ringFile = ringFile.getAbsoluteFile();
      ringSlot.ringRaf = raf;
      ringSlot.ringChannel = channel;
      ringSlot.exportPath = getCanonicalOrAbsolutePath(ringFile);
      Log.i(TAG, "Created fake input file ring for slot " + slot + ": " + ringSlot.exportPath);
      return ringSlot;
    } catch (IOException e) {
      Log.e(TAG, "Failed to create fake input file ring for slot " + slot + ": " + e.getMessage());
      if (channel != null) {
        try {
          channel.close();
        } catch (IOException ignored) {
        }
      }
      if (raf != null) {
        try {
          raf.close();
        } catch (IOException ignored) {
        }
      }
      return null;
    }
  }

  private RingSlot ensureRingSlot() {
    synchronized (RING_LOCK) {
      return ensureRingSlotLocked(this.slot, this.eventFile.getParentFile());
    }
  }

  private boolean activateRingSlot() {
    RingSlot ringSlot = ensureRingSlot();
    if (ringSlot == null || ringSlot.data == null) {
      return false;
    }
    synchronized (ringSlot) {
      if (!ringSlot.active) {
        if (ringSlot.everActivated) {
          ringSlot.generation++;
        } else {
          ringSlot.everActivated = true;
        }
        ringSlot.data.putLong(RING_WRITE_SEQ_OFFSET, 0L);
        clearSnapshotLocked(ringSlot.data);
        ringSlot.data.putLong(RING_GENERATION_OFFSET, ringSlot.generation);
        ringSlot.active = true;
        Log.d(
            TAG,
            "Activated fake input ring for slot " + this.slot + " generation=" + ringSlot.generation);
      }
    }
    return true;
  }

  private void deactivateRingSlot() {
    RingSlot ringSlot;
    synchronized (RING_LOCK) {
      ringSlot =
          this.slot >= 0 && this.slot < RING_SLOTS.length ? RING_SLOTS[this.slot] : null;
    }
    if (ringSlot == null) {
      return;
    }
    synchronized (ringSlot) {
      if (ringSlot.active && ringSlot.data != null) {
        ringSlot.generation++;
        ringSlot.data.putLong(RING_WRITE_SEQ_OFFSET, 0L);
        clearSnapshotLocked(ringSlot.data);
        ringSlot.data.putLong(RING_GENERATION_OFFSET, ringSlot.generation);
        Log.i(
            TAG,
            "Deactivated fake input ring for slot "
                + this.slot
                + " generation="
                + ringSlot.generation);
      }
      ringSlot.active = false;
    }
  }

  private boolean flushBufferToRing() {
    RingSlot ringSlot = ensureRingSlot();
    if (ringSlot == null || ringSlot.data == null) {
      return false;
    }

    // Raw byte copy: endianness is irrelevant since we never interpret
    // multi-byte fields out of the source here.
    ByteBuffer source = this.buffer.duplicate();
    synchronized (ringSlot) {
      ByteBuffer ring = ringSlot.data;
      long writeSeq = ring.getLong(RING_WRITE_SEQ_OFFSET);
      int sourceLimit = source.limit();
      while (source.remaining() >= EVENT_SIZE) {
        int eventIndex = (int) (writeSeq % RING_CAPACITY_EVENTS);
        int targetOffset = RING_HEADER_SIZE + (eventIndex * EVENT_SIZE);
        // Bulk-copy one event (EVENT_SIZE bytes) instead of byte-by-byte.
        // Bound the source to a single event, position the ring at the slot,
        // then let put(ByteBuffer) move the whole block. Mutating the ring's
        // Java position is harmless: the native reader uses a raw pointer, not
        // this position. API-26 safe (no JDK 13+ absolute bulk put).
        source.limit(source.position() + EVENT_SIZE);
        ring.position(targetOffset);
        ring.put(source);
        source.limit(sourceLimit);
        writeSeq++;
      }
      // Publish the resulting absolute state. prev* now hold the post-update
      // values, i.e. exactly the state the events just written transition to.
      writeSnapshotLocked(ring);
      nativeStoreFence();
      ring.putLong(RING_WRITE_SEQ_OFFSET, writeSeq);
    }
    return true;
  }

  // Publishes the full absolute controller state for the native reader to replay
  // as a keyframe. seqlock: bump to odd, write fields, bump to even, with
  // store-store fences so the reader's acquire loads see consistent payloads.
  private void writeSnapshotLocked(ByteBuffer ring) {
    int buttons = 0;
    for (int i = 0; i < BUTTON_MAP.length; i++) {
      if (this.prevButtonStates[i]) {
        buttons |= (1 << i);
      }
    }
    long seq = ring.getLong(RING_SNAPSHOT_SEQ_OFFSET);
    ring.putLong(RING_SNAPSHOT_SEQ_OFFSET, seq + 1); // odd: write in progress
    nativeStoreFence();
    ring.putInt(RING_SNAPSHOT_BUTTONS_OFFSET, buttons);
    // Axis order must match the native kSnapshotAxisCodes:
    // X, Y, RX, RY, GAS(=triggerR), BRAKE(=triggerL), HAT0X, HAT0Y.
    ring.putShort(RING_SNAPSHOT_AXES_OFFSET, clampShort(this.prevThumbLX));
    ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 2, clampShort(this.prevThumbLY));
    ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 4, clampShort(this.prevThumbRX));
    ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 6, clampShort(this.prevThumbRY));
    ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 8, clampShort(this.prevTriggerR));
    ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 10, clampShort(this.prevTriggerL));
    ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 12, clampShort(this.prevHatX));
    ring.putShort(RING_SNAPSHOT_AXES_OFFSET + 14, clampShort(this.prevHatY));
    nativeStoreFence();
    ring.putLong(RING_SNAPSHOT_SEQ_OFFSET, seq + 2); // even: write complete
  }

  private static short clampShort(int value) {
    if (value > Short.MAX_VALUE) {
      return Short.MAX_VALUE;
    }
    if (value < Short.MIN_VALUE) {
      return Short.MIN_VALUE;
    }
    return (short) value;
  }

  private static void clearSnapshotLocked(ByteBuffer ring) {
    long seq = ring.getLong(RING_SNAPSHOT_SEQ_OFFSET);
    ring.putLong(RING_SNAPSHOT_SEQ_OFFSET, seq + 1);
    nativeStoreFence();
    ring.putInt(RING_SNAPSHOT_BUTTONS_OFFSET, 0);
    for (int i = 0; i < 8; i++) {
      ring.putShort(RING_SNAPSHOT_AXES_OFFSET + (i * 2), (short) 0);
    }
    nativeStoreFence();
    ring.putLong(RING_SNAPSHOT_SEQ_OFFSET, seq + 2);
  }

  private boolean flushBuffer() {
    return flushBufferToRing();
  }

  public synchronized boolean open() {
    if (this.destroyed) {
      return false;
    }
    if (this.isOpen) {
      return true;
    }
    try {
      this.eventFile.getParentFile().mkdirs();
      if (!this.eventFile.exists()) {
        this.eventFile.createNewFile();
      }
      if (!activateRingSlot()) {
        if (this.eventFile.exists()) {
          this.eventFile.delete();
        }
        Log.e(TAG, "Failed to open fake input mmap ring: " + this.eventFile.getAbsolutePath());
        return false;
      }
      this.isOpen = true;
      this.pendingFullResend = true;
      Log.i(TAG, "Opened fake input: " + this.eventFile.getAbsolutePath());
      return true;
    } catch (IOException e) {
      Log.e(TAG, "Failed to open: " + e.getMessage());
      return false;
    }
  }

  public synchronized void close() {
    this.isOpen = false;
  }

  public synchronized void requestFullResend() {
    this.pendingFullResend = true;
    requestRingResync();
  }

  private void requestRingResync() {
    RingSlot ringSlot = ensureRingSlot();
    if (ringSlot == null || ringSlot.data == null) {
      return;
    }
    synchronized (ringSlot) {
      ByteBuffer ring = ringSlot.data;
      nativeStoreFence();
      ring.putInt(RING_RESYNC_SEQ_OFFSET, ring.getInt(RING_RESYNC_SEQ_OFFSET) + 1);
    }
  }

  private boolean isPublishedStateNeutral() {
    for (int i = 0; i < BUTTON_MAP.length; i++) {
      if (this.prevButtonStates[i]) return false;
    }
    return this.prevThumbLX == 0
        && this.prevThumbLY == 0
        && this.prevThumbRX == 0
        && this.prevThumbRY == 0
        && this.prevTriggerL == 0
        && this.prevTriggerR == 0
        && this.prevHatX == 0
        && this.prevHatY == 0;
  }

  public synchronized void reset() {
    if (this.isOpen || open()) {
      this.buffer.clear();
      this.hasChanges = false;
      for (int i = 0; i < BUTTON_MAP.length; i++) {
        if (this.prevButtonStates[i]) {
          this.prevButtonStates[i] = false;
          writeEvent((short) 4, (short) 4, BUTTON_MAP[i]);
          writeEvent((short) 1, BUTTON_MAP[i], 0);
        }
      }
      if (this.prevThumbLX != 0) {
        this.prevThumbLX = 0;
        writeEvent((short) 3, (short) 0, 0);
      }
      if (this.prevThumbLY != 0) {
        this.prevThumbLY = 0;
        writeEvent((short) 3, (short) 1, 0);
      }
      if (this.prevThumbRX != 0) {
        this.prevThumbRX = 0;
        writeEvent((short) 3, (short) 3, 0);
      }
      if (this.prevThumbRY != 0) {
        this.prevThumbRY = 0;
        writeEvent((short) 3, (short) 4, 0);
      }
      if (this.prevTriggerL != 0) {
        this.prevTriggerL = 0;
        writeEvent((short) 3, (short) 10, 0);
      }
      if (this.prevTriggerR != 0) {
        this.prevTriggerR = 0;
        writeEvent((short) 3, (short) 9, 0);
      }
      if (this.prevHatX != 0) {
        this.prevHatX = 0;
        writeEvent((short) 3, (short) 16, 0);
      }
      if (this.prevHatY != 0) {
        this.prevHatY = 0;
        writeEvent((short) 3, (short) 17, 0);
      }
      if (this.hasChanges) {
        writeEvent((short) 0, (short) 0, 0);
        this.buffer.flip();
        if (!flushBuffer()) {
          Log.e(TAG, "Reset write error: fake input mmap ring unavailable");
          this.pendingFullResend = true;
        }
        Log.i(TAG, "Reset fake input to neutral state: " + this.eventFile.getAbsolutePath());
        return;
      }
      Log.i(TAG, "Reset fake input to neutral state: " + this.eventFile.getAbsolutePath());
    }
  }

  public synchronized void softRelease() {
    reset();
    close();
    Log.i(TAG, "Soft released fake input: " + this.eventFile.getAbsolutePath());
  }

  public synchronized void destroy() {
    this.destroyed = true;
    reset();
    close();
    deactivateRingSlot();
    if (this.eventFile != null && this.eventFile.exists()) {
      boolean deleted = this.eventFile.delete();
      Log.i(
          TAG,
          "Deleted fake input discovery node: "
              + this.eventFile.getAbsolutePath()
              + " ("
              + deleted
              + ")");
    }
  }

  private void writeEvent(short type, short code, int value) {
    long timeMs = System.currentTimeMillis();
    this.buffer.putLong(timeMs / 1000);
    this.buffer.putLong((timeMs % 1000) * 1000);
    this.buffer.putShort(type);
    this.buffer.putShort(code);
    this.buffer.putInt(value);
    this.hasChanges = true;
  }

  private void writeButton(int i, boolean z) {
    if (i < 0 || i >= BUTTON_MAP.length) {
      return;
    }
    if (!this.forceResend && this.prevButtonStates[i] == z) {
      return;
    }
    this.prevButtonStates[i] = z;
    writeEvent((short) 4, (short) 4, BUTTON_MAP[i]);
    writeEvent((short) 1, BUTTON_MAP[i], z ? 1 : 0);
  }

  private void writeAxis(short code, int value, int[] prevRef, int index) {
    if (prevRef[index] == value) {
      return;
    }
    prevRef[index] = value;
    writeEvent((short) 3, code, value);
  }

  public synchronized void writeGamepadState(GamepadState state) throws IOException {
    int hatX;
    if (!this.isOpen && !open()) {
      return;
    }
    // Keep the ring delta-first for latency. Full event frames are only used as
    // a one-shot repair after a failed publish; normal open/overflow recovery is
    // handled by the native snapshot keyframe.
    this.forceResend = this.pendingFullResend;
    this.pendingFullResend = false;
    if (this.forceResend) {
      Log.d(TAG, "Re-emitting full gamepad state after failed publish for slot " + this.slot);
    }
    this.buffer.clear();
    this.hasChanges = false;
    for (int i = 0; i < 10; i++) {
      writeButton(i, state.isPressed((byte) i));
    }
    int rx = (int) (state.thumbRX * 32767.0f);
    int ry = (int) (state.thumbRY * 32767.0f);
    int lx = (int) (state.thumbLX * 32767.0f);
    int ly = (int) (state.thumbLY * 32767.0f);
    int tl = (int) (state.triggerL * 255.0f);
    int tr = (int) (state.triggerR * 255.0f);

    // The fake evdev ring is event-queue semantics, so unchanged axes normally
    // stay silent; forceResend overrides that to emit a complete keyframe.
    if (this.forceResend || rx != this.prevThumbRX) {
      this.prevThumbRX = rx;
      writeEvent((short) 3, (short) 3, rx);
    }
    if (this.forceResend || ry != this.prevThumbRY) {
      this.prevThumbRY = ry;
      writeEvent((short) 3, (short) 4, ry);
    }
    if (this.forceResend || lx != this.prevThumbLX) {
      this.prevThumbLX = lx;
      writeEvent((short) 3, (short) 0, lx);
    }
    if (this.forceResend || ly != this.prevThumbLY) {
      this.prevThumbLY = ly;
      writeEvent((short) 3, (short) 1, ly);
    }
    if (this.forceResend || tl != this.prevTriggerL) {
      this.prevTriggerL = tl;
      writeEvent((short) 3, (short) 10, tl);
    }
    if (this.forceResend || tr != this.prevTriggerR) {
      this.prevTriggerR = tr;
      writeEvent((short) 3, (short) 9, tr);
    }

    int hatY = 1;
    if (state.dpad[3]) {
      hatX = -1;
    } else {
      hatX = state.dpad[1] ? 1 : 0;
    }
    if (state.dpad[0]) {
      hatY = -1;
    } else if (!state.dpad[2]) {
      hatY = 0;
    }
    if (this.forceResend || hatX != this.prevHatX) {
      this.prevHatX = hatX;
      writeEvent((short) 3, (short) 16, hatX);
    }
    if (this.forceResend || hatY != this.prevHatY) {
      this.prevHatY = hatY;
      writeEvent((short) 3, (short) 17, hatY);
    }
    if (this.hasChanges) {
      writeEvent((short) 0, (short) 0, 0);
      this.buffer.flip();
      if (!flushBuffer()) {
        Log.e(TAG, "Gamepad write error: fake input mmap ring unavailable");
        // Couldn't publish; re-assert the whole state on the next frame.
        this.pendingFullResend = true;
      }
      boolean neutral = isPublishedStateNeutral();
      if (neutral && !this.publishedStateNeutral) {
        requestRingResync();
      }
      this.publishedStateNeutral = neutral;
    }
    this.forceResend = false;
  }
}
