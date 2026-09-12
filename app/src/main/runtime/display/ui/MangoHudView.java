package com.winlator.cmod.runtime.display.ui;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.runtime.system.CPUStatus;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Locale;

/**
 * Mango Style HUD: performance overlay with a classic table layout (accent label
 * column, white right-aligned values with small units), frametime graph and
 * 1%/0.1% lows. Frame events are ring-buffered with zero allocation; text is
 * formatted at 2 Hz on a private stats thread into reusable buffers, so the
 * present path stays free of formatting, logging and garbage.
 */
public class MangoHudView extends View {
  public static final String PREF_ENABLED = "mango_hud_enabled";
  private static final String PREF_ELEMENTS = "mango_hud_elements2";
  private static final String PREF_POS_X = "mango_hud_position_x";
  private static final String PREF_POS_Y = "mango_hud_position_y";
  private static final String PREF_HAS_POSITION = "mango_hud_has_position";
  private static final String PREF_SCALE = "mango_hud_scale";
  private static final String PREF_LOCKED = "mango_hud_locked";
  private static final String PREF_ALPHA = "mango_hud_alpha";
  private static final String PREF_BG_ALPHA = "mango_hud_bg_alpha";
  private static final float DEFAULT_ALPHA = 1.0f;
  private static final float DEFAULT_BG_ALPHA = 0.7f;
  public static final float SCALE_MIN = 0.5f;
  public static final float SCALE_MAX = 1.5f;

  // Element indices (bitmask in PREF_ELEMENTS).
  public static final int EL_GPU_LOAD = 0;
  public static final int EL_GPU_TEMP = 1;
  public static final int EL_CPU_LOAD = 2;
  public static final int EL_CPU_TEMP = 3;
  public static final int EL_RAM = 4;
  public static final int EL_BATTERY = 5;
  public static final int EL_LOWS = 6;
  public static final int EL_GRAPH = 7;
  public static final int EL_ENGINE = 8;
  public static final int EL_VRAM = 9;
  public static final int EL_CPU_MHZ = 10;
  public static final int EL_GPU_CLOCK = 11;
  public static final int EL_CORES = 12;
  public static final int EL_NET = 13;
  public static final int EL_SWAP = 14;
  public static final int EL_RES = 15;
  public static final int EL_WINE = 16;
  public static final int EL_DURATION = 17;
  public static final int EL_CLOCK = 18;
  public static final int EL_THROTTLE = 19;
  public static final int ELEMENT_COUNT = 20;
  // Original ten elements plus both clock cells on; the rest opt-in.
  private static final int DEFAULT_ELEMENTS_MASK = 0xFFF;

  // Overlay palette; accents pass through vivid() for punchier labels.
  private static final int C_BG = 0x00020202;
  private static final int C_TEXT = 0xFFFFFFFF;
  private static final int C_GPU = vivid(0xFF2E9762);
  private static final int C_CPU = vivid(0xFF2E97CB);
  private static final int C_VRAM = vivid(0xFFAD64C1);
  private static final int C_RAM = vivid(0xFFC26693);
  private static final int C_BAT = vivid(0xFFFF9078);
  private static final int C_ENGINE = vivid(0xFFEB5B5B);
  private static final int C_NET = vivid(0xFFE07B85);
  private static final int C_GRAPH = 0xFF00FF00;
  private static final int C_OUTLINE = 0xFF000000;
  private static final int C_FPS_GEN = 0xFF00E5FF;
  private static final int C_DIVISOR = 0xFF9E9E9E;

  private static int vivid(int color) {
    float[] hsv = new float[3];
    android.graphics.Color.colorToHSV(color, hsv);
    hsv[1] = Math.min(1f, hsv[1] * 1.45f);
    hsv[2] = Math.min(1f, hsv[2] * 1.2f);
    return android.graphics.Color.HSVToColor(0xFF, hsv);
  }
  private static final int C_THROTTLE_WARN = 0xFFFDFD09;
  private static final int C_THROTTLE_HOT = 0xFFB22222;
  private static final String[] THROTTLE_TEXT = {
    "", "throttle: light", "throttle: moderate", "throttle: severe",
    "throttle: critical", "throttle: emergency", "throttle: shutdown"
  };

  // Double-tap snap targets; the settings slider sets any value between MIN and MAX.
  private static final float[] SCALE_STEPS = {0.735f, 0.98f, 1.225f, 1.5f};
  public static final float DEFAULT_SCALE = 0.735f;
  private static final float BASE_TEXT_DP = 14f;
  private static final long TICK_MS = 500L;
  private static final long HIDDEN_TICK_MS = 2000L;
  private static final long FALLBACK_SUPPRESSION_NS = 2000000000L;
  private static final long FPS_WINDOW_NS = 1000000000L;
  private static final long IDLE_TIMEOUT_NS = 1500000000L;
  private static final long GRAPH_REDRAW_MS = 100L;
  private static final int STAMP_CAPACITY = 1024;
  private static final int LOWS_CAPACITY = 5000;
  private static final int GRAPH_SAMPLES = 200;
  private static final float GRAPH_CEIL_MS = 50f;
  private static final int MAX_OUTPUT_SAMPLES = 8;
  private static final long OUTPUT_WINDOW_NS = 1000000000L;
  private static final long OUTPUT_MIN_SPAN_NS = 250000000L;

  private final SharedPreferences preferences;
  private final BatteryManager batteryManager;
  private final IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
  private final float density;

  // ── frame rings, guarded by frameLock; written on the present thread ──
  private final Object frameLock = new Object();
  private final long[] stampsNano = new long[STAMP_CAPACITY];
  private int stampsStart, stampsCount;
  private final float[] lowsMs = new float[LOWS_CAPACITY];
  private int lowsIndex, lowsCount;
  private final float[] graphMs = new float[GRAPH_SAMPLES];
  private int graphIndex, graphCount;
  private long lastFrameNano, lastPrimaryNano;
  private float currentMs;
  private volatile long lastGraphInvalidate;

  private volatile FrameRating.OutputFrameSource outputFrameSource;
  private volatile boolean frameGenActive;
  private final long[] outputSampleNano = new long[MAX_OUTPUT_SAMPLES];
  private final long[] outputSampleTotal = new long[MAX_OUTPUT_SAMPLES];
  private final long[] outputSampleGenerated = new long[MAX_OUTPUT_SAMPLES];
  private int outputSamplesStart, outputSamplesCount;
  private float outputFps;
  private boolean outputGenerating;
  private boolean frameGenShowing;
  private String arrowGlyph = "->";

  // ── formatted cells + layout, guarded by uiLock ──
  private final Object uiLock = new Object();
  private final StringBuilder sbGpuLoad = new StringBuilder(8);
  private final StringBuilder sbGpuTemp = new StringBuilder(8);
  private final StringBuilder sbCpuLoad = new StringBuilder(8);
  private final StringBuilder sbCpuTemp = new StringBuilder(8);
  private final StringBuilder sbVram = new StringBuilder(8);
  private final StringBuilder sbRam = new StringBuilder(8);
  private final StringBuilder sbRamPct = new StringBuilder(8);
  private final StringBuilder sbBatPct = new StringBuilder(8);
  private final StringBuilder sbBatW = new StringBuilder(8);
  private final StringBuilder sbBatTemp = new StringBuilder(8);
  private final StringBuilder sbFps = new StringBuilder(8);
  private final StringBuilder sbFpsIn = new StringBuilder(8);
  private final StringBuilder sbMs = new StringBuilder(8);
  private final StringBuilder sbAvg = new StringBuilder(8);
  private final StringBuilder sbLow1 = new StringBuilder(8);
  private final StringBuilder sbLow01 = new StringBuilder(8);
  private final StringBuilder sbMinMax = new StringBuilder(24);
  private final StringBuilder sbCpuMhz = new StringBuilder(8);
  private final StringBuilder sbGpuClk = new StringBuilder(8);
  private final StringBuilder sbNetRx = new StringBuilder(8);
  private final StringBuilder sbNetTx = new StringBuilder(8);
  private final StringBuilder sbSwap = new StringBuilder(8);
  private final StringBuilder sbRes = new StringBuilder(24);
  private final StringBuilder sbDuration = new StringBuilder(20);
  private final StringBuilder sbClock = new StringBuilder(8);
  private final StringBuilder[] sbCorePct;
  private final StringBuilder[] sbCoreMhz;
  private final String[] coreLabels;
  private final int coreCount;
  private String engineBase = "VULKAN";
  private String engineVersion = "";
  private String resolutionText = "";
  private String wineText = "";
  private final boolean[] elements = new boolean[ELEMENT_COUNT];
  private float scaleFactor = DEFAULT_SCALE;
  private boolean locked;
  private float textAlpha = DEFAULT_ALPHA;
  private float bgAlpha = DEFAULT_BG_ALPHA;
  private int panelW = 1, panelH = 1;
  private float textSize, smallSize, rowH, smallRowH, baseline, smallBaseline;
  private float pad, charW, smallCharW, labelColW, fpsLabelColW, graphH;

  private final Paint bgPaint = new Paint();
  private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint graphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float[] graphLines = new float[(GRAPH_SAMPLES - 1) * 4];
  private final float[] graphSnapshot = new float[GRAPH_SAMPLES];

  // ── stats thread state ──
  private volatile HandlerThread statsThread;
  private volatile Handler statsHandler;
  private final float[] lowsScratch = new float[LOWS_CAPACITY];
  private CPUStatus.AppCpuSample prevCpuSample;
  private boolean cpuWarmedUp;
  private int slowTickParity;
  private int gpuLoad = -1, gpuTemp = -1, cpuLoad = -1, cpuTemp = -1, batteryPct = -1, ramPct = -1, batTempC = -1;
  private float ramGib = -1f, vramGib = -1f, batteryWatts = -1f;
  private String[] gpuTempPaths;
  private String vramPath;
  private int vramMode = -1; // -1 probe, 1 kgsl own-pid sum, 2 single file, 3 own-process graphics
  private String gpuClkPath;
  private boolean gpuClkSearched;
  private short[] coreMaxMhz;
  private int cpuMhz = -1, gpuClkMhz = -1, netRxKbs = -1, netTxKbs = -1, throttleStatus;
  private float swapGib = -1f;
  private final int[] corePct;
  private final int[] coreMhz;
  private long prevRxBytes = -1, prevTxBytes = -1, prevNetMs;
  private final long sessionStartMs = SystemClock.elapsedRealtime();
  // Self-reposting like FrameRating's loop: runs while attached, independent of frame events; near-free while hidden.
  private final Runnable tickRunnable = new Runnable() {
    @Override
    public void run() {
      Handler handler = statsHandler;
      if (handler == null) return;
      boolean visible = getVisibility() == VISIBLE;
      if (visible) tick();
      handler.postDelayed(this, visible ? TICK_MS : HIDDEN_TICK_MS);
    }
  };

  public MangoHudView(Context context) {
    super(context);
    this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
    this.batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
    this.density = getResources().getDisplayMetrics().density;
    this.coreCount = Math.min(Runtime.getRuntime().availableProcessors(), 10);
    this.corePct = new int[coreCount];
    this.coreMhz = new int[coreCount];
    this.sbCorePct = new StringBuilder[coreCount];
    this.sbCoreMhz = new StringBuilder[coreCount];
    this.coreLabels = new String[coreCount];
    for (int i = 0; i < coreCount; i++) {
      sbCorePct[i] = new StringBuilder(8);
      sbCoreMhz[i] = new StringBuilder(8);
      coreLabels[i] = "C" + i;
      corePct[i] = -1;
      coreMhz[i] = -1;
    }

    int mask = preferences.getInt(PREF_ELEMENTS, DEFAULT_ELEMENTS_MASK);
    for (int i = 0; i < ELEMENT_COUNT; i++) elements[i] = (mask & (1 << i)) != 0;
    this.scaleFactor = clampScale(preferences.getFloat(PREF_SCALE, DEFAULT_SCALE));
    this.locked = preferences.getBoolean(PREF_LOCKED, false);
    this.textAlpha = preferences.getFloat(PREF_ALPHA, DEFAULT_ALPHA);
    this.bgAlpha = preferences.getFloat(PREF_BG_ALPHA, DEFAULT_BG_ALPHA);

    // Stock mono by file: font packs and app themes can reroute the "monospace" alias for raw Paints.
    Typeface mono;
    try {
      mono = Typeface.create(Typeface.createFromFile("/system/fonts/DroidSansMono.ttf"), Typeface.BOLD);
    } catch (Exception e) {
      mono = Typeface.create("monospace", Typeface.BOLD);
    }
    bgPaint.setColor(C_BG);
    valuePaint.setTypeface(mono);
    valuePaint.setColor(C_TEXT);
    labelPaint.setTypeface(mono);
    smallPaint.setTypeface(mono);
    smallPaint.setColor(C_TEXT);
    outlinePaint.setTypeface(mono);
    outlinePaint.setColor(C_OUTLINE);
    outlinePaint.setStyle(Paint.Style.STROKE);
    graphPaint.setColor(C_GRAPH);
    graphPaint.setStyle(Paint.Style.STROKE);
    if (valuePaint.hasGlyph("→")) arrowGlyph = "→";

    setLayoutParams(new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    synchronized (uiLock) {
      applyScaleLocked();
      applyAlphasLocked();
      computeLayoutLocked();
    }
    setVisibility(VISIBLE);
  }

  public static boolean[] elementsFromPrefs(SharedPreferences preferences) {
    int mask = preferences.getInt(PREF_ELEMENTS, DEFAULT_ELEMENTS_MASK);
    boolean[] out = new boolean[ELEMENT_COUNT];
    for (int i = 0; i < ELEMENT_COUNT; i++) out[i] = (mask & (1 << i)) != 0;
    return out;
  }

  public static void saveElement(SharedPreferences preferences, int index, boolean enabled) {
    if (index < 0 || index >= ELEMENT_COUNT) return;
    int mask = preferences.getInt(PREF_ELEMENTS, DEFAULT_ELEMENTS_MASK);
    mask = enabled ? mask | (1 << index) : mask & ~(1 << index);
    preferences.edit().putInt(PREF_ELEMENTS, mask).apply();
  }

  public static float alphaFromPrefs(SharedPreferences preferences) {
    return preferences.getFloat(PREF_ALPHA, DEFAULT_ALPHA);
  }

  public static float bgAlphaFromPrefs(SharedPreferences preferences) {
    return preferences.getFloat(PREF_BG_ALPHA, DEFAULT_BG_ALPHA);
  }

  public static void saveAlpha(SharedPreferences preferences, float alpha) {
    preferences.edit().putFloat(PREF_ALPHA, alpha).apply();
  }

  public static void saveBgAlpha(SharedPreferences preferences, float alpha) {
    preferences.edit().putFloat(PREF_BG_ALPHA, alpha).apply();
  }

  public void setTextAlphaValue(float alpha) {
    synchronized (uiLock) {
      textAlpha = alpha;
      applyAlphasLocked();
    }
    postInvalidate();
  }

  public void setBackgroundAlphaValue(float alpha) {
    synchronized (uiLock) {
      bgAlpha = alpha;
      applyAlphasLocked();
    }
    postInvalidate();
  }

  public void setElementEnabled(int index, boolean enabled) {
    if (index < 0 || index >= ELEMENT_COUNT) return;
    synchronized (uiLock) {
      elements[index] = enabled;
      computeLayoutLocked();
    }
    post(() -> {
      requestLayout();
      invalidate();
    });
  }

  public void setEngineName(String name) {
    if (name == null || name.isEmpty()) return;
    String upper = name.toUpperCase(Locale.US);
    if (upper.length() > 16) upper = upper.substring(0, 16);
    // Base name draws full-size; anything after the first space (version) draws
    // small so a long engine string doesn't widen the panel.
    int space = upper.indexOf(' ');
    synchronized (uiLock) {
      if (space > 0) {
        engineBase = upper.substring(0, space);
        engineVersion = upper.substring(space + 1).trim();
      } else {
        engineBase = upper;
        engineVersion = "";
      }
      computeLayoutLocked();
    }
    post(() -> {
      requestLayout();
      invalidate();
    });
  }

  /** Drop accumulated frametimes so loading screens and menus stay out of the averages. */
  public void resetMetrics() {
    synchronized (frameLock) {
      lowsIndex = 0;
      lowsCount = 0;
    }
  }

  public void setOutputFrameSource(FrameRating.OutputFrameSource source) {
    if (this.outputFrameSource == source) return;
    this.outputFrameSource = source;
    synchronized (frameLock) {
      resetOutputSamplesLocked();
    }
  }

  public void setFrameGenerationActive(boolean active) {
    if (this.frameGenActive == active) return;
    this.frameGenActive = active;
    synchronized (frameLock) {
      resetOutputSamplesLocked();
    }
  }

  /** Static per-session rows: game resolution and wine/proton build. */
  public void setSessionInfo(String resolution, String wineVersion) {
    synchronized (uiLock) {
      if (resolution != null) resolutionText = resolution.length() > 16 ? resolution.substring(0, 16) : resolution;
      if (wineVersion != null) wineText = wineVersion.length() > 28 ? wineVersion.substring(0, 28) : wineVersion;
      computeLayoutLocked();
    }
    post(() -> {
      requestLayout();
      invalidate();
    });
  }

  public void setHudVisible(boolean visible) {
    if (visible) {
      synchronized (frameLock) {
        resetFrameDataLocked();
      }
      setVisibility(VISIBLE);
      bringToFront();
      // Populate immediately instead of waiting out a hidden-interval delay.
      Handler handler = statsHandler;
      if (handler != null) {
        handler.removeCallbacks(tickRunnable);
        handler.post(tickRunnable);
      }
    } else {
      setVisibility(GONE);
    }
  }

  /** Present-path hook: one call per game frame; ring writes only, no allocation. */
  public void recordGameFrame(boolean primarySource) {
    if (getVisibility() != VISIBLE) return;
    long nowNano = System.nanoTime();
    synchronized (frameLock) {
      if (primarySource) {
        if (lastPrimaryNano == 0 || nowNano - lastPrimaryNano >= FALLBACK_SUPPRESSION_NS) {
          resetFrameDataLocked();
        }
        lastPrimaryNano = nowNano;
      } else if (lastPrimaryNano > 0 && nowNano - lastPrimaryNano < FALLBACK_SUPPRESSION_NS) {
        return;
      }

      if (lastFrameNano != 0) {
        float ms = (nowNano - lastFrameNano) / 1000000.0f;
        if (ms > 0f && ms < 1000f) {
          currentMs = ms;
          lowsMs[lowsIndex] = ms;
          lowsIndex = (lowsIndex + 1) % LOWS_CAPACITY;
          if (lowsCount < LOWS_CAPACITY) lowsCount++;
          graphMs[graphIndex] = ms;
          graphIndex = (graphIndex + 1) % GRAPH_SAMPLES;
          if (graphCount < GRAPH_SAMPLES) graphCount++;
        }
      }
      lastFrameNano = nowNano;

      int index = (stampsStart + stampsCount) % STAMP_CAPACITY;
      if (stampsCount == STAMP_CAPACITY) {
        stampsStart = (stampsStart + 1) % STAMP_CAPACITY;
      } else {
        stampsCount++;
      }
      stampsNano[index] = nowNano;
    }

    if (elements[EL_GRAPH]) {
      long time = SystemClock.elapsedRealtime();
      if (time - lastGraphInvalidate >= GRAPH_REDRAW_MS) {
        lastGraphInvalidate = time;
        postInvalidate();
      }
    }
  }

  private void resetFrameDataLocked() {
    stampsStart = 0;
    stampsCount = 0;
    lowsIndex = 0;
    lowsCount = 0;
    graphIndex = 0;
    graphCount = 0;
    lastFrameNano = 0;
    currentMs = 0f;
    resetOutputSamplesLocked();
  }

  private void resetOutputSamplesLocked() {
    outputSamplesStart = 0;
    outputSamplesCount = 0;
    outputFps = 0f;
    outputGenerating = false;
  }

  private void sampleOutputFramesLocked(long nowNano) {
    FrameRating.OutputFrameSource source = outputFrameSource;
    if (!frameGenActive || source == null) {
      resetOutputSamplesLocked();
      return;
    }

    long total = source.getPresentedFrameCount();
    long generated = source.getGeneratedFrameCount();
    int index = (outputSamplesStart + outputSamplesCount) % MAX_OUTPUT_SAMPLES;
    if (outputSamplesCount == MAX_OUTPUT_SAMPLES) {
      outputSamplesStart = (outputSamplesStart + 1) % MAX_OUTPUT_SAMPLES;
      index = (outputSamplesStart + outputSamplesCount - 1) % MAX_OUTPUT_SAMPLES;
    } else {
      outputSamplesCount++;
    }
    outputSampleNano[index] = nowNano;
    outputSampleTotal[index] = total;
    outputSampleGenerated[index] = generated;

    if (outputSamplesCount < 2) return;

    int baseline = outputSamplesStart;
    for (int i = 0; i < outputSamplesCount - 1; i++) {
      int candidate = (outputSamplesStart + i) % MAX_OUTPUT_SAMPLES;
      if (nowNano - outputSampleNano[candidate] >= OUTPUT_WINDOW_NS) {
        baseline = candidate;
      } else {
        break;
      }
    }

    long elapsedNano = nowNano - outputSampleNano[baseline];
    long frames = total - outputSampleTotal[baseline];
    if (elapsedNano < OUTPUT_MIN_SPAN_NS || frames < 0) return;
    outputFps = (frames * 1000000000.0f) / elapsedNano;
    outputGenerating = generated > outputSampleGenerated[baseline];
  }

  // ── stats thread ─────────────────────────────────────────────────

  private void startStats() {
    if (statsThread != null) return;
    statsThread = new HandlerThread("MangoHudStats");
    statsThread.start();
    statsHandler = new Handler(statsThread.getLooper());
    statsHandler.post(tickRunnable);
  }

  private void stopStats() {
    Handler handler = statsHandler;
    HandlerThread thread = statsThread;
    statsHandler = null;
    statsThread = null;
    if (handler != null) handler.removeCallbacksAndMessages(null);
    if (thread != null) {
      thread.quitSafely();
    }
    prevCpuSample = null;
    cpuWarmedUp = false;
  }

  private void tick() {
    long nowNano = System.nanoTime();
    float fps;
    float ms;
    int lowsN;
    float outFps;
    boolean showOutput;
    float graphMin = 0f, graphMax = 0f;
    synchronized (frameLock) {
      // Trim the rolling FPS window here so the present path stays write-only.
      long oldest = nowNano - FPS_WINDOW_NS;
      while (stampsCount > 0 && stampsNano[stampsStart] < oldest) {
        stampsStart = (stampsStart + 1) % STAMP_CAPACITY;
        stampsCount--;
      }
      boolean idle = lastFrameNano == 0 || nowNano - lastFrameNano > IDLE_TIMEOUT_NS;
      if (idle || stampsCount <= 1) {
        fps = 0f;
      } else {
        long first = stampsNano[stampsStart];
        long last = stampsNano[(stampsStart + stampsCount - 1) % STAMP_CAPACITY];
        fps = last > first ? (stampsCount - 1) * 1000000000.0f / (last - first) : 0f;
      }
      ms = idle ? 0f : currentMs;
      sampleOutputFramesLocked(nowNano);
      outFps = outputFps;
      showOutput = frameGenActive && outputGenerating && outputFps > 0f && !idle;
      lowsN = lowsCount;
      System.arraycopy(lowsMs, 0, lowsScratch, 0, lowsN);
      for (int i = 0; i < graphCount; i++) {
        float v = graphMs[i];
        if (i == 0) {
          graphMin = graphMax = v;
        } else {
          if (v < graphMin) graphMin = v;
          if (v > graphMax) graphMax = v;
        }
      }
    }

    // 1%/0.1% lows: percentile over the last 10k frametimes, converted to FPS.
    float avgFps = 0f, low1Fps = 0f, low01Fps = 0f;
    if (elements[EL_LOWS] && lowsN > 0) {
      Arrays.sort(lowsScratch, 0, lowsN);
      float sum = 0f;
      for (int i = 0; i < lowsN; i++) sum += lowsScratch[i];
      if (sum > 0f) avgFps = lowsN * 1000.0f / sum;
      low1Fps = percentileLowFps(lowsScratch, lowsN, 0.01f);
      low01Fps = percentileLowFps(lowsScratch, lowsN, 0.001f);
    }

    boolean slowTick = (slowTickParity++ & 1) == 0;
    if (elements[EL_GPU_LOAD]) readGpuLoad();
    if (elements[EL_CPU_LOAD]) readCpuLoad();
    if (elements[EL_CPU_MHZ] || elements[EL_CORES]) readCpuClocks();
    if (slowTick) {
      if (elements[EL_GPU_TEMP]) gpuTemp = readGpuTempC();
      if (elements[EL_CPU_TEMP]) cpuTemp = CPUStatus.getCpuTempC();
      if (elements[EL_GPU_CLOCK]) readGpuClock();
      if (elements[EL_VRAM]) readVram();
      if (elements[EL_RAM]) readRam();
      if (elements[EL_SWAP]) readSwap();
      if (elements[EL_NET]) readNet();
      if (elements[EL_BATTERY]) readBattery();
      if (elements[EL_THROTTLE]) readThrottle();
    }

    synchronized (uiLock) {
      frameGenShowing = showOutput;
      if (showOutput) {
        formatInt(sbFpsIn, Math.round(fps));
        formatInt(sbFps, Math.round(outFps));
      } else {
        formatInt(sbFps, Math.round(fps));
      }
      formatMs(sbMs, ms);
      formatInt(sbGpuLoad, gpuLoad);
      formatInt(sbGpuTemp, gpuTemp);
      formatInt(sbCpuLoad, cpuLoad);
      formatInt(sbCpuTemp, cpuTemp);
      formatTenths(sbVram, vramGib);
      formatTenths(sbRam, ramGib);
      formatInt(sbRamPct, ramPct);
      formatInt(sbBatPct, batteryPct);
      formatInt(sbBatTemp, batTempC);
      formatTenths(sbBatW, batteryWatts);
      formatInt(sbAvg, Math.round(avgFps));
      formatInt(sbLow1, Math.round(low1Fps));
      formatInt(sbLow01, Math.round(low01Fps));
      formatInt(sbCpuMhz, cpuMhz);
      formatInt(sbGpuClk, gpuClkMhz);
      formatNetCell(sbNetRx, netRxKbs);
      formatNetCell(sbNetTx, netTxKbs);
      formatTenths(sbSwap, swapGib);
      if (elements[EL_CORES]) {
        for (int i = 0; i < coreCount; i++) {
          formatInt(sbCorePct[i], corePct[i]);
          formatInt(sbCoreMhz[i], coreMhz[i]);
        }
      }
      if (elements[EL_RES]) {
        sbRes.setLength(0);
        sbRes.append(resolutionText);
        float hz = getDisplayRefreshRate();
        if (hz > 0f) {
          sbRes.append(" @ ").append(Math.round(hz)).append("Hz");
        }
      }
      if (elements[EL_DURATION]) {
        formatDuration(sbDuration, SystemClock.elapsedRealtime() - sessionStartMs);
      }
      if (elements[EL_CLOCK]) {
        formatClock(sbClock);
      }
      sbMinMax.setLength(0);
      sbMinMax.append("min:");
      appendTenths(sbMinMax, graphMin);
      sbMinMax.append(" max:");
      appendTenths(sbMinMax, graphMax);
      computeLayoutLocked();
    }

    int w = panelW, h = panelH;
    post(() -> {
      if (getMeasuredWidth() != w || getMeasuredHeight() != h) requestLayout();
      invalidate();
    });
  }

  private static float percentileLowFps(float[] sortedAsc, int n, float p) {
    int idx = (int) (p * n) - 1;
    if (idx < 0) idx = 0;
    float ft = sortedAsc[n - 1 - idx];
    return ft > 0f ? 1000.0f / ft : 0f;
  }

  // ── system stat readers (silent; sysfs paths mirror FrameRating) ──

  private void readGpuLoad() {
    int load = -1;
    try {
      File gpubusy = new File("/sys/class/kgsl/kgsl-3d0/gpubusy");
      if (gpubusy.exists() && gpubusy.canRead()) {
        try (BufferedReader reader = new BufferedReader(new FileReader(gpubusy))) {
          String line = reader.readLine();
          if (line != null) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2) {
              long busy = Long.parseLong(parts[0]);
              long total = Long.parseLong(parts[1]);
              if (total > 0) load = (int) ((100 * busy) / total);
            }
          }
        }
      }
      if (load < 0) {
        String[] paths = {
          "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
          "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
          "/sys/class/misc/mali0/device/utilisation",
          "/sys/kernel/gpu/gpu_busy"
        };
        for (String path : paths) {
          File f = new File(path);
          if (f.exists() && f.canRead()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
              String line = reader.readLine();
              if (line != null) {
                load = Integer.parseInt(line.trim().replaceAll("[^0-9]", ""));
                break;
              }
            } catch (Exception ignored) {
            }
          }
        }
      }
    } catch (Exception ignored) {
    }
    if (load >= 0) gpuLoad = Math.min(load, 100);
  }

  private int readGpuTempC() {
    String[] paths = gpuTempPaths;
    if (paths == null) {
      paths = discoverGpuTempPaths();
      gpuTempPaths = paths;
    }
    for (String path : paths) {
      try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
        String line = reader.readLine();
        if (line != null) {
          int raw = Integer.parseInt(line.trim());
          int celsius = raw > 1000 ? (raw + 500) / 1000 : raw;
          if (celsius >= 1 && celsius <= 150) return celsius;
        }
      } catch (Exception ignored) {
      }
    }
    return -1;
  }

  private static String[] discoverGpuTempPaths() {
    java.util.ArrayList<String> found = new java.util.ArrayList<>();
    File kgsl = new File("/sys/class/kgsl/kgsl-3d0/temp");
    if (kgsl.exists() && kgsl.canRead()) found.add(kgsl.getAbsolutePath());
    File[] roots = {new File("/sys/class/thermal"), new File("/sys/devices/virtual/thermal")};
    for (File root : roots) {
      File[] zones = root.listFiles(
          (dir, name) -> name.startsWith("thermal_zone") && new File(dir, name).isDirectory());
      if (zones == null) continue;
      for (File zone : zones) {
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(zone, "type")))) {
          String type = reader.readLine();
          if (type != null && type.trim().toLowerCase(Locale.US).contains("gpu")) {
            File temp = new File(zone, "temp");
            if (temp.exists() && temp.canRead()) found.add(temp.getAbsolutePath());
          }
        } catch (Exception ignored) {
        }
      }
    }
    return found.toArray(new String[0]);
  }

  private void readCpuLoad() {
    try {
      CPUStatus.AppCpuSample sample = CPUStatus.readAppCpuSample();
      int pct = -1;
      if (sample != null) {
        if (prevCpuSample != null) pct = sample.percentSince(prevCpuSample);
        prevCpuSample = sample;
      }
      if (pct >= 0) {
        cpuLoad = pct;
        cpuWarmedUp = true;
      } else if (!cpuWarmedUp) {
        int freq = CPUStatus.getClockFreqLoadPercent();
        if (freq >= 0) cpuLoad = freq;
      }
    } catch (Exception e) {
      cpuLoad = -1;
    }
  }

  private void readRam() {
    try {
      ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
      ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
      am.getMemoryInfo(mi);
      long used = mi.totalMem - mi.availMem;
      ramGib = used / 1073741824.0f;
      ramPct = mi.totalMem > 0 ? (int) (100 * used / mi.totalMem) : -1;
    } catch (Exception e) {
      ramGib = -1f;
      ramPct = -1;
    }
  }

  /** Best-effort GPU memory in use: per-pid kgsl sum, global counters, else own-process graphics stat. */
  private void readVram() {
    if (vramMode == -1) {
      vramMode = 3;
      // Dir listing may be SELinux-denied while direct per-pid opens still work.
      if (readOwnPidsGpuMem() >= 0) {
        vramMode = 1;
      } else {
        String[] candidates = {
          "/sys/class/kgsl/kgsl/page_alloc",
          "/sys/class/kgsl/kgsl-3d0/page_alloc",
          "/sys/kernel/gpu/gpu_memory",
          "/proc/mali/memory_usage"
        };
        for (String path : candidates) {
          if (readLongFile(new File(path)) > 0) {
            vramPath = path;
            vramMode = 2;
            break;
          }
        }
      }
    }
    long bytes = -1;
    if (vramMode == 1) {
      bytes = readOwnPidsGpuMem();
    } else if (vramMode == 2) {
      bytes = readLongFile(new File(vramPath));
    }
    if (bytes < 0) {
      // Own-process graphics allocations — always readable, never blank.
      try {
        android.os.Debug.MemoryInfo mi = new android.os.Debug.MemoryInfo();
        android.os.Debug.getMemoryInfo(mi);
        String kb = mi.getMemoryStat("summary.graphics");
        if (kb != null) bytes = Long.parseLong(kb) * 1024L;
      } catch (Exception ignored) {
      }
    }
    vramGib = bytes >= 0 ? bytes / 1073741824.0f : -1f;
  }

  /** Sum GPU memory of every process in our uid via direct kgsl per-pid paths. */
  private static long readOwnPidsGpuMem() {
    String[] names = new File("/proc").list();
    if (names == null) return -1;
    int myUid = android.os.Process.myUid();
    long total = 0;
    boolean any = false;
    for (String name : names) {
      if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
      try {
        if (android.system.Os.stat("/proc/" + name).st_uid != myUid) continue;
      } catch (Exception e) {
        continue;
      }
      long v = readLongFile(new File("/sys/class/kgsl/kgsl/proc/" + name + "/gpumem_mapped"));
      if (v >= 0) {
        total += v;
        any = true;
      }
    }
    return any ? total : -1;
  }

  private static long readLongFile(File f) {
    // No pre-checks: SELinux can fail access() on sysfs while the open succeeds.
    try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
      String line = reader.readLine();
      if (line != null) {
        String digits = line.trim().replaceAll("[^0-9]", "");
        if (!digits.isEmpty()) return Long.parseLong(digits);
      }
    } catch (Exception ignored) {
    }
    return -1;
  }

  private void readCpuClocks() {
    try {
      short[] speeds = CPUStatus.getCurrentClockSpeeds();
      if (speeds == null || speeds.length == 0) return;
      if (coreMaxMhz == null) {
        coreMaxMhz = new short[speeds.length];
        for (int i = 0; i < speeds.length; i++) coreMaxMhz[i] = CPUStatus.getMaxClockSpeed(i);
      }
      long sum = 0;
      int counted = 0;
      for (int i = 0; i < speeds.length; i++) {
        if (speeds[i] > 0) {
          sum += speeds[i];
          counted++;
        }
        if (i < coreCount) {
          coreMhz[i] = speeds[i] > 0 ? speeds[i] : -1;
          corePct[i] = speeds[i] > 0 && i < coreMaxMhz.length && coreMaxMhz[i] > 0
              ? Math.min(100, speeds[i] * 100 / coreMaxMhz[i]) : -1;
        }
      }
      cpuMhz = counted > 0 ? (int) (sum / counted) : -1;
    } catch (Exception e) {
      cpuMhz = -1;
    }
  }

  private void readGpuClock() {
    if (!gpuClkSearched) {
      gpuClkSearched = true;
      java.util.ArrayList<String> candidates = new java.util.ArrayList<>(Arrays.asList(
          "/sys/class/kgsl/kgsl-3d0/gpuclk",
          "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
          "/sys/class/kgsl/kgsl-3d0/gpu_clock",
          "/sys/class/kgsl/kgsl-3d0/clock_mhz",
          "/sys/kernel/gpu/gpu_clock",
          // Devfreq listing may be SELinux-denied; try known Adreno node names directly.
          "/sys/class/devfreq/kgsl-3d0/cur_freq",
          "/sys/class/devfreq/3d00000.qcom,kgsl-3d0/cur_freq",
          "/sys/class/devfreq/2c00000.qcom,kgsl-3d0/cur_freq",
          "/sys/class/devfreq/5000000.qcom,kgsl-3d0/cur_freq",
          "/sys/class/devfreq/5900000.qcom,kgsl-3d0/cur_freq"));
      File devfreqRoot = new File("/sys/class/devfreq");
      File[] nodes = devfreqRoot.listFiles();
      if (nodes != null) {
        for (File node : nodes) {
          String name = node.getName().toLowerCase(Locale.US);
          if (name.contains("kgsl") || name.contains("gpu") || name.contains("mali")) {
            candidates.add(new File(node, "cur_freq").getAbsolutePath());
          }
        }
      }
      for (String path : candidates) {
        if (readLongFile(new File(path)) > 0) {
          gpuClkPath = path;
          break;
        }
      }
    }
    if (gpuClkPath == null) {
      gpuClkMhz = -1;
      return;
    }
    long raw = readLongFile(new File(gpuClkPath));
    if (raw <= 0) {
      gpuClkMhz = -1;
    } else if (raw > 10000000) {
      gpuClkMhz = (int) (raw / 1000000); // Hz
    } else if (raw > 10000) {
      gpuClkMhz = (int) (raw / 1000); // kHz
    } else {
      gpuClkMhz = (int) raw; // already MHz
    }
  }

  private void readNet() {
    try {
      long rx = TrafficStats.getTotalRxBytes();
      long tx = TrafficStats.getTotalTxBytes();
      long now = SystemClock.elapsedRealtime();
      if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
        netRxKbs = -1;
        netTxKbs = -1;
        return;
      }
      if (prevRxBytes >= 0 && now > prevNetMs) {
        long elapsed = now - prevNetMs;
        netRxKbs = (int) ((rx - prevRxBytes) * 1000 / elapsed / 1024);
        netTxKbs = (int) ((tx - prevTxBytes) * 1000 / elapsed / 1024);
      }
      prevRxBytes = rx;
      prevTxBytes = tx;
      prevNetMs = now;
    } catch (Exception e) {
      netRxKbs = -1;
      netTxKbs = -1;
    }
  }

  private void readSwap() {
    try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
      long swapTotalKb = -1, swapFreeKb = -1;
      String line;
      while ((line = reader.readLine()) != null && (swapTotalKb < 0 || swapFreeKb < 0)) {
        if (line.startsWith("SwapTotal:")) {
          swapTotalKb = parseMeminfoKb(line);
        } else if (line.startsWith("SwapFree:")) {
          swapFreeKb = parseMeminfoKb(line);
        }
      }
      swapGib = swapTotalKb >= 0 && swapFreeKb >= 0
          ? (swapTotalKb - swapFreeKb) / 1048576.0f : -1f;
    } catch (Exception e) {
      swapGib = -1f;
    }
  }

  private static long parseMeminfoKb(String line) {
    String digits = line.replaceAll("[^0-9]", "");
    return digits.isEmpty() ? -1 : Long.parseLong(digits);
  }

  private void readThrottle() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      throttleStatus = 0;
      return;
    }
    try {
      PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
      int status = pm != null ? pm.getCurrentThermalStatus() : 0;
      throttleStatus = Math.max(0, Math.min(status, THROTTLE_TEXT.length - 1));
    } catch (Exception e) {
      throttleStatus = 0;
    }
  }

  private float getDisplayRefreshRate() {
    try {
      android.view.Display display = getDisplay();
      return display != null ? display.getRefreshRate() : -1f;
    } catch (Exception e) {
      return -1f;
    }
  }

  private void readBattery() {
    try {
      int pct = batteryManager != null
          ? batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
      batteryPct = pct > 0 ? pct : -1;
      long currentUa = batteryManager != null
          ? batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) : 0;
      float amps = -1f;
      if (currentUa != 0 && currentUa != Long.MIN_VALUE) {
        long abs = Math.abs(currentUa);
        amps = abs < 20000 ? abs / 1000.0f : abs / 1000000.0f;
      }
      Intent intent = getContext().registerReceiver(null, batteryFilter);
      int mv = intent != null ? intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) : 0;
      batteryWatts = mv > 0 && amps > 0f ? (mv / 1000.0f) * amps : -1f;
      int tenths = intent != null ? intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) : 0;
      batTempC = tenths > 0 ? tenths / 10 : -1;
    } catch (Exception e) {
      batteryPct = -1;
      batteryWatts = -1f;
      batTempC = -1;
    }
  }

  // ── allocation-free formatting into reusable builders ──

  private static void formatInt(StringBuilder sb, int value) {
    sb.setLength(0);
    if (value < 0) {
      sb.append('-');
      return;
    }
    sb.append(value);
  }

  private static void formatTenths(StringBuilder sb, float value) {
    sb.setLength(0);
    if (value < 0f) {
      sb.append('-');
      return;
    }
    appendTenths(sb, value);
  }

  private static void formatMs(StringBuilder sb, float ms) {
    sb.setLength(0);
    if (ms <= 0f) {
      sb.append('-');
    } else if (ms >= 100f) {
      sb.append(Math.round(ms));
    } else {
      appendTenths(sb, ms);
    }
  }

  private static void appendTenths(StringBuilder sb, float value) {
    if (value < 0f) value = 0f;
    int tenths = Math.round(value * 10f);
    sb.append(tenths / 10).append('.').append(tenths % 10);
  }

  /** KB/s up to 4 digits, then whole MB/s (unit glyph stays "K"/"M" via the suffix cell). */
  private static void formatNetCell(StringBuilder sb, int kbs) {
    sb.setLength(0);
    if (kbs < 0) {
      sb.append('-');
    } else if (kbs > 9999) {
      sb.append(kbs / 1024).append('M');
    } else {
      sb.append(kbs);
    }
  }

  private static void formatDuration(StringBuilder sb, long elapsedMs) {
    sb.setLength(0);
    long totalSec = elapsedMs / 1000;
    sb.append("elapsed ");
    appendTwoDigits(sb, (int) (totalSec / 3600));
    sb.append(':');
    appendTwoDigits(sb, (int) ((totalSec / 60) % 60));
    sb.append(':');
    appendTwoDigits(sb, (int) (totalSec % 60));
  }

  private static void formatClock(StringBuilder sb) {
    sb.setLength(0);
    long nowMs = System.currentTimeMillis();
    int offset = java.util.TimeZone.getDefault().getOffset(nowMs);
    long dayMin = ((nowMs + offset) / 60000) % 1440;
    appendTwoDigits(sb, (int) (dayMin / 60));
    sb.append(':');
    appendTwoDigits(sb, (int) (dayMin % 60));
  }

  private static void appendTwoDigits(StringBuilder sb, int value) {
    if (value < 10) sb.append('0');
    sb.append(value);
  }

  // ── layout & drawing ─────────────────────────────────────────────

  private void applyScaleLocked() {
    float scale = scaleFactor;
    textSize = BASE_TEXT_DP * density * scale;
    smallSize = textSize * 0.55f;
    valuePaint.setTextSize(textSize);
    labelPaint.setTextSize(textSize);
    smallPaint.setTextSize(smallSize);
    outlinePaint.setStrokeWidth(Math.max(1.5f, textSize * 0.08f));
    graphPaint.setStrokeWidth(Math.max(1.5f, textSize * 0.07f));
    Paint.FontMetrics fm = valuePaint.getFontMetrics();
    rowH = (fm.descent - fm.ascent) * 1.02f;
    baseline = -fm.ascent;
    Paint.FontMetrics sfm = smallPaint.getFontMetrics();
    smallRowH = (sfm.descent - sfm.ascent) * 1.1f;
    smallBaseline = -sfm.ascent;
    pad = textSize * 0.25f;
    charW = valuePaint.measureText("0");
    smallCharW = smallPaint.measureText("0");
    graphH = textSize * 2.0f;
  }

  private void applyAlphasLocked() {
    int bg = Math.round(Math.max(0f, Math.min(bgAlpha, 1f)) * 255f);
    bgPaint.setColor((bg << 24) | (C_BG & 0x00FFFFFF));
    int text = Math.round(Math.max(0f, Math.min(textAlpha, 1f)) * 255f);
    valuePaint.setAlpha(text);
    graphPaint.setAlpha(text);
    outlinePaint.setAlpha(text);
  }

  private int textAlphaInt() {
    return Math.round(Math.max(0f, Math.min(textAlpha, 1f)) * 255f);
  }

  // Per-cell value reservation: 3 chars for bounded ints (%/°C), 4 for the rest.
  private float statCellW(int valueChars, int unitChars) {
    return charW * valueChars + smallCharW * (unitChars + 0.2f);
  }

  private float fgInputCellW() {
    return charW * (4 + arrowGlyph.length() + 1.0f);
  }

  private void computeLayoutLocked() {
    labelColW = charW * 4.2f;
    fpsLabelColW = labelColW;
    if (elements[EL_ENGINE]) {
      float engineW = valuePaint.measureText(engineBase);
      if (!engineVersion.isEmpty()) {
        engineW += smallCharW * 0.3f + smallPaint.measureText(engineVersion);
      }
      fpsLabelColW = Math.max(labelColW, engineW + charW * 0.4f);
    }
    float w = 0f;
    int rows = 0;
    int smallRows = 0;
    if (elements[EL_GPU_LOAD] || elements[EL_GPU_TEMP] || elements[EL_GPU_CLOCK]) {
      rows++;
      float rw = labelColW
          + (elements[EL_GPU_LOAD] ? statCellW(3, 1) : 0f)
          + (elements[EL_GPU_TEMP] ? statCellW(3, 2) : 0f)
          + (elements[EL_GPU_CLOCK] ? statCellW(4, 3) : 0f);
      w = Math.max(w, rw);
    }
    if (elements[EL_CPU_LOAD] || elements[EL_CPU_TEMP] || elements[EL_CPU_MHZ]) {
      rows++;
      float rw = labelColW
          + (elements[EL_CPU_LOAD] ? statCellW(3, 1) : 0f)
          + (elements[EL_CPU_TEMP] ? statCellW(3, 2) : 0f)
          + (elements[EL_CPU_MHZ] ? statCellW(4, 3) : 0f);
      w = Math.max(w, rw);
    }
    if (elements[EL_CORES]) {
      rows += coreCount;
      w = Math.max(w, labelColW + statCellW(3, 1) + statCellW(4, 3));
    }
    if (elements[EL_VRAM]) {
      rows++;
      w = Math.max(w, labelColW + statCellW(4, 3));
    }
    if (elements[EL_RAM]) {
      rows++;
      w = Math.max(w, labelColW + statCellW(4, 3) + statCellW(3, 1));
    }
    if (elements[EL_SWAP]) {
      rows++;
      w = Math.max(w, labelColW + statCellW(4, 3));
    }
    if (elements[EL_NET]) {
      rows++;
      w = Math.max(w, labelColW + statCellW(4, 2) + statCellW(4, 2));
    }
    if (elements[EL_BATTERY]) {
      rows++;
      w = Math.max(w, labelColW + statCellW(3, 1) + statCellW(3, 2) + statCellW(4, 1));
    }
    rows++; // FPS row is the HUD core, always shown
    w = Math.max(
        w,
        fpsLabelColW
            + (frameGenShowing ? fgInputCellW() : 0f)
            + statCellW(4, 3)
            + statCellW(4, 2));
    if (elements[EL_LOWS]) {
      rows += 3;
      w = Math.max(w, labelColW + statCellW(4, 3));
    }
    // Small rows: only value-stable strings contribute to width so the panel never relayouts mid-session.
    if (elements[EL_RES] && !resolutionText.isEmpty()) {
      smallRows++;
      w = Math.max(w, smallCharW * (resolutionText.length() + 8));
    }
    if (elements[EL_WINE] && !wineText.isEmpty()) {
      smallRows++;
      w = Math.max(w, smallCharW * wineText.length());
    }
    if (elements[EL_DURATION]) {
      smallRows++;
      w = Math.max(w, smallCharW * 16);
    }
    if (elements[EL_CLOCK]) smallRows++;
    if (elements[EL_THROTTLE] && throttleStatus > 0) smallRows++;
    w = Math.max(w, charW * 13f);
    float h = rows * rowH + smallRows * smallRowH;
    if (elements[EL_GRAPH]) {
      h += smallRowH + graphH + pad * 0.5f;
    }
    panelW = (int) Math.ceil(w + pad * 2);
    panelH = (int) Math.ceil(h + pad * 2);
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    synchronized (uiLock) {
      setMeasuredDimension(panelW, panelH);
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    synchronized (uiLock) {
      canvas.drawRect(0, 0, panelW, panelH, bgPaint);
      float y = pad;
      if (elements[EL_GPU_LOAD] || elements[EL_GPU_TEMP] || elements[EL_GPU_CLOCK]) {
        float x = drawLabel(canvas, "GPU", C_GPU, y);
        if (elements[EL_GPU_LOAD]) x = drawStatCell(canvas, sbGpuLoad, "%", x, y, 3);
        if (elements[EL_GPU_TEMP]) x = drawStatCell(canvas, sbGpuTemp, "°C", x, y, 3);
        if (elements[EL_GPU_CLOCK]) drawStatCell(canvas, sbGpuClk, "MHz", x, y, 4);
        y += rowH;
      }
      if (elements[EL_CPU_LOAD] || elements[EL_CPU_TEMP] || elements[EL_CPU_MHZ]) {
        float x = drawLabel(canvas, "CPU", C_CPU, y);
        if (elements[EL_CPU_LOAD]) x = drawStatCell(canvas, sbCpuLoad, "%", x, y, 3);
        if (elements[EL_CPU_TEMP]) x = drawStatCell(canvas, sbCpuTemp, "°C", x, y, 3);
        if (elements[EL_CPU_MHZ]) drawStatCell(canvas, sbCpuMhz, "MHz", x, y, 4);
        y += rowH;
      }
      if (elements[EL_CORES]) {
        for (int i = 0; i < coreCount; i++) {
          float x = drawLabel(canvas, coreLabels[i], C_CPU, y);
          x = drawStatCell(canvas, sbCorePct[i], "%", x, y, 3);
          drawStatCell(canvas, sbCoreMhz[i], "MHz", x, y, 4);
          y += rowH;
        }
      }
      if (elements[EL_VRAM]) {
        float x = drawLabel(canvas, "VRAM", C_VRAM, y);
        drawStatCell(canvas, sbVram, "GiB", x, y, 4);
        y += rowH;
      }
      if (elements[EL_RAM]) {
        float x = drawLabel(canvas, "RAM", C_RAM, y);
        x = drawStatCell(canvas, sbRam, "GiB", x, y, 4);
        drawStatCell(canvas, sbRamPct, "%", x, y, 3);
        y += rowH;
      }
      if (elements[EL_SWAP]) {
        float x = drawLabel(canvas, "SWP", C_RAM, y);
        drawStatCell(canvas, sbSwap, "GiB", x, y, 4);
        y += rowH;
      }
      if (elements[EL_NET]) {
        float x = drawLabel(canvas, "NET", C_NET, y);
        x = drawStatCell(canvas, sbNetRx, "K↓", x, y, 4);
        drawStatCell(canvas, sbNetTx, "K↑", x, y, 4);
        y += rowH;
      }
      if (elements[EL_BATTERY]) {
        float x = drawLabel(canvas, "BAT", C_BAT, y);
        x = drawStatCell(canvas, sbBatPct, "%", x, y, 3);
        // Temp sits in the same column as the GPU/CPU temps.
        x = drawStatCell(canvas, sbBatTemp, "°C", x, y, 3);
        drawStatCell(canvas, sbBatW, "W", x, y, 4);
        y += rowH;
      }
      {
        float x;
        if (elements[EL_ENGINE]) {
          drawLabel(canvas, engineBase, C_ENGINE, y);
          if (!engineVersion.isEmpty()) {
            float vxs = pad + valuePaint.measureText(engineBase) + smallCharW * 0.3f;
            drawOutlinedSmall(
                canvas, engineVersion, 0, engineVersion.length(), vxs, y + baseline, C_ENGINE);
          }
          x = pad + fpsLabelColW;
        } else {
          x = pad + labelColW;
        }
        if (frameGenShowing) {
          x = drawFgInputCell(canvas, x, y);
          x = drawStatCell(canvas, sbFps, "FPS", x, y, 4, C_FPS_GEN);
        } else {
          x = drawStatCell(canvas, sbFps, "FPS", x, y, 4);
        }
        drawStatCell(canvas, sbMs, "ms", x, y, 4);
        y += rowH;
      }
      if (elements[EL_LOWS]) {
        float x = drawLabel(canvas, "AVG", C_TEXT, y);
        drawStatCell(canvas, sbAvg, "FPS", x, y, 4);
        y += rowH;
        x = drawLabel(canvas, "1%", C_TEXT, y);
        drawStatCell(canvas, sbLow1, "FPS", x, y, 4);
        y += rowH;
        x = drawLabel(canvas, "0.1%", C_TEXT, y);
        drawStatCell(canvas, sbLow01, "FPS", x, y, 4);
        y += rowH;
      }
      if (elements[EL_RES] && !resolutionText.isEmpty()) {
        drawOutlinedSmall(canvas, sbRes, 0, sbRes.length(), pad, y + smallBaseline, C_TEXT);
        y += smallRowH;
      }
      if (elements[EL_WINE] && !wineText.isEmpty()) {
        drawOutlinedSmall(canvas, wineText, 0, wineText.length(), pad, y + smallBaseline, C_ENGINE);
        y += smallRowH;
      }
      if (elements[EL_DURATION]) {
        drawOutlinedSmall(canvas, sbDuration, 0, sbDuration.length(), pad, y + smallBaseline, C_TEXT);
        y += smallRowH;
      }
      if (elements[EL_CLOCK]) {
        drawOutlinedSmall(canvas, sbClock, 0, sbClock.length(), pad, y + smallBaseline, C_TEXT);
        y += smallRowH;
      }
      if (elements[EL_THROTTLE] && throttleStatus > 0) {
        String text = THROTTLE_TEXT[throttleStatus];
        int color = throttleStatus >= 3 ? C_THROTTLE_HOT : C_THROTTLE_WARN;
        drawOutlinedSmall(canvas, text, 0, text.length(), pad, y + smallBaseline, color);
        y += smallRowH;
      }
      if (elements[EL_GRAPH]) {
        float sy = y + smallBaseline;
        drawOutlinedSmall(canvas, "Frametime", 0, 9, pad, sy, C_ENGINE);
        float mmW = sbMinMax.length() * smallCharW;
        drawOutlinedSmall(canvas, sbMinMax, 0, sbMinMax.length(), panelW - pad - mmW, sy, C_TEXT);
        y += smallRowH + pad * 0.5f;
        drawGraph(canvas, pad, y, panelW - pad * 2, graphH);
      }
    }
  }

  private float drawLabel(Canvas canvas, CharSequence text, int color, float rowTop) {
    float y = rowTop + baseline;
    outlinePaint.setTextSize(textSize);
    canvas.drawText(text, 0, text.length(), pad, y, outlinePaint);
    labelPaint.setColor(color);
    labelPaint.setAlpha(textAlphaInt());
    canvas.drawText(text, 0, text.length(), pad, y, labelPaint);
    return pad + labelColW;
  }

  private float drawStatCell(
      Canvas canvas, StringBuilder value, String unit, float x, float rowTop, int valueChars) {
    float y = rowTop + baseline;
    float vx = x + charW * valueChars - value.length() * charW;
    outlinePaint.setTextSize(textSize);
    canvas.drawText(value, 0, value.length(), vx, y, outlinePaint);
    canvas.drawText(value, 0, value.length(), vx, y, valuePaint);
    float ux = x + charW * valueChars + smallCharW * 0.1f;
    drawOutlinedSmall(canvas, unit, 0, unit.length(), ux, y, C_TEXT);
    return x + statCellW(valueChars, unit.length());
  }

  private float drawStatCell(
      Canvas canvas,
      StringBuilder value,
      String unit,
      float x,
      float rowTop,
      int valueChars,
      int valueColor) {
    valuePaint.setColor(valueColor);
    valuePaint.setAlpha(textAlphaInt());
    float next = drawStatCell(canvas, value, unit, x, rowTop, valueChars);
    valuePaint.setColor(C_TEXT);
    valuePaint.setAlpha(textAlphaInt());
    return next;
  }

  private float drawFgInputCell(Canvas canvas, float x, float rowTop) {
    float y = rowTop + baseline;
    float vx = x + charW * 4 - sbFpsIn.length() * charW;
    outlinePaint.setTextSize(textSize);
    canvas.drawText(sbFpsIn, 0, sbFpsIn.length(), vx, y, outlinePaint);
    canvas.drawText(sbFpsIn, 0, sbFpsIn.length(), vx, y, valuePaint);
    float ax = x + charW * 4.5f;
    canvas.drawText(arrowGlyph, 0, arrowGlyph.length(), ax, y, outlinePaint);
    labelPaint.setColor(C_DIVISOR);
    labelPaint.setAlpha(textAlphaInt());
    canvas.drawText(arrowGlyph, 0, arrowGlyph.length(), ax, y, labelPaint);
    return x + fgInputCellW();
  }

  private void drawOutlinedSmall(Canvas canvas, CharSequence text, int start, int end,
      float x, float y, int color) {
    outlinePaint.setTextSize(smallSize);
    canvas.drawText(text, start, end, x, y, outlinePaint);
    smallPaint.setColor(color);
    smallPaint.setAlpha(textAlphaInt());
    canvas.drawText(text, start, end, x, y, smallPaint);
  }

  private void drawGraph(Canvas canvas, float left, float top, float width, float height) {
    int count;
    synchronized (frameLock) {
      count = graphCount;
      for (int i = 0; i < count; i++) {
        graphSnapshot[i] = graphMs[(graphIndex - count + i + GRAPH_SAMPLES) % GRAPH_SAMPLES];
      }
    }
    if (count < 2) return;
    float stepX = width / (GRAPH_SAMPLES - 1);
    float startX = left + (GRAPH_SAMPLES - count) * stepX;
    int segments = 0;
    float prevX = startX;
    float prevY = graphY(graphSnapshot[0], top, height);
    for (int i = 1; i < count; i++) {
      float cx = startX + i * stepX;
      float cy = graphY(graphSnapshot[i], top, height);
      int base = segments * 4;
      graphLines[base] = prevX;
      graphLines[base + 1] = prevY;
      graphLines[base + 2] = cx;
      graphLines[base + 3] = cy;
      segments++;
      prevX = cx;
      prevY = cy;
    }
    canvas.drawLines(graphLines, 0, segments * 4, graphPaint);
  }

  private static float graphY(float ms, float top, float height) {
    if (ms > GRAPH_CEIL_MS - 0.1f) ms = GRAPH_CEIL_MS - 0.1f;
    if (ms < 0f) ms = 0f;
    return top + height - (ms / GRAPH_CEIL_MS) * height;
  }

  // ── touch: drag to move, double-tap to cycle size ──

  private int activePointerId = -1;
  private float touchOffsetX, touchOffsetY, downRawX, downRawY;
  private float lastTapX, lastTapY;
  private long downTime, lastTapTime;
  private boolean dragging;
  private static final long LOCK_HOLD_MS = 3500L;
  private final Runnable lockRunnable = new Runnable() {
    @Override
    public void run() {
      locked = true;
      activePointerId = -1;
      saveLocked(preferences, true);
      performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    }
  };
  private static final float TAP_SLOP = 20f;
  private static final float DOUBLE_TAP_SLOP = 48f;
  private static final long TAP_MS = 300L;

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (locked) return false;
    if (event.getPointerCount() > 1) {
      activePointerId = -1;
      removeCallbacks(lockRunnable);
      return false;
    }
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        activePointerId = event.getPointerId(0);
        touchOffsetX = getX() - event.getRawX();
        touchOffsetY = getY() - event.getRawY();
        downRawX = event.getRawX();
        downRawY = event.getRawY();
        downTime = SystemClock.elapsedRealtime();
        dragging = false;
        bringToFront();
        postDelayed(lockRunnable, LOCK_HOLD_MS);
        return true;
      case MotionEvent.ACTION_MOVE:
        if (activePointerId != -1) {
          if (Math.abs(event.getRawX() - downRawX) > TAP_SLOP
              || Math.abs(event.getRawY() - downRawY) > TAP_SLOP) {
            dragging = true;
          }
          if (dragging) {
            removeCallbacks(lockRunnable);
            setX(event.getRawX() + touchOffsetX);
            setY(event.getRawY() + touchOffsetY);
            clampToParentBounds();
          }
          return true;
        }
        break;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        removeCallbacks(lockRunnable);
        if (activePointerId != -1) {
          long now = SystemClock.elapsedRealtime();
          if (dragging) {
            clampToParentBounds();
            preferences.edit()
                .putBoolean(PREF_HAS_POSITION, true)
                .putFloat(PREF_POS_X, getX())
                .putFloat(PREF_POS_Y, getY())
                .apply();
            lastTapTime = 0;
          } else if (now - downTime < 400) {
            boolean nearLastTap = Math.abs(downRawX - lastTapX) < DOUBLE_TAP_SLOP * density
                && Math.abs(downRawY - lastTapY) < DOUBLE_TAP_SLOP * density;
            if (lastTapTime != 0 && now - lastTapTime < TAP_MS && nearLastTap) {
              lastTapTime = 0;
              cycleScale();
            } else {
              lastTapTime = now;
              lastTapX = downRawX;
              lastTapY = downRawY;
            }
          }
          activePointerId = -1;
          return true;
        }
        return false;
    }
    return false;
  }

  /** Double-tap: snap up to the next preset (wrapping), persisting so the settings slider follows. */
  private void cycleScale() {
    synchronized (uiLock) {
      scaleFactor = nextPresetAbove(scaleFactor);
      preferences.edit().putFloat(PREF_SCALE, scaleFactor).apply();
      applyScaleLocked();
      computeLayoutLocked();
    }
    requestLayout();
    invalidate();
    post(this::clampToParentBounds);
  }

  private static float nextPresetAbove(float current) {
    for (float step : SCALE_STEPS) {
      if (step > current + 0.01f) return step;
    }
    return SCALE_STEPS[0];
  }

  private static float clampScale(float scale) {
    return Math.max(SCALE_MIN, Math.min(scale, SCALE_MAX));
  }

  public static boolean lockedFromPrefs(SharedPreferences preferences) {
    return preferences.getBoolean(PREF_LOCKED, false);
  }

  public static void saveLocked(SharedPreferences preferences, boolean locked) {
    preferences.edit().putBoolean(PREF_LOCKED, locked).apply();
  }

  /** Locked: touches fall through to the controls underneath; unlock from HUD settings. */
  public void setLockedValue(boolean value) {
    this.locked = value;
    if (value) {
      removeCallbacks(lockRunnable);
      activePointerId = -1;
    }
  }

  public static float scaleFromPrefs(SharedPreferences preferences) {
    return preferences.getFloat(PREF_SCALE, DEFAULT_SCALE);
  }

  public static void saveScale(SharedPreferences preferences, float scale) {
    preferences.edit().putFloat(PREF_SCALE, scale).apply();
  }

  public void setScaleValue(float scale) {
    synchronized (uiLock) {
      scaleFactor = clampScale(scale);
      applyScaleLocked();
      computeLayoutLocked();
    }
    post(() -> {
      requestLayout();
      invalidate();
      clampToParentBounds();
    });
  }

  private void clampToParentBounds() {
    View parentView = (View) getParent();
    if (parentView == null || parentView.getWidth() <= 0 || parentView.getHeight() <= 0
        || getWidth() <= 0 || getHeight() <= 0) {
      return;
    }
    float maxX = Math.max(0f, parentView.getWidth() - getWidth());
    float maxY = Math.max(0f, parentView.getHeight() - getHeight());
    setX(Math.max(0f, Math.min(getX(), maxX)));
    setY(Math.max(0f, Math.min(getY(), maxY)));
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    setElevation(1000.0f);
    if (preferences.getBoolean(PREF_HAS_POSITION, false)) {
      post(() -> {
        setX(preferences.getFloat(PREF_POS_X, getX()));
        setY(preferences.getFloat(PREF_POS_Y, getY()));
        clampToParentBounds();
      });
    }
    // Attached ⇒ loop running: a rendered view is always attached, so the HUD can never sit on screen dead.
    startStats();
  }

  @Override
  protected void onDetachedFromWindow() {
    removeCallbacks(lockRunnable);
    stopStats();
    super.onDetachedFromWindow();
  }
}
