package com.winlator.cmod.runtime.display.framegen;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.container.Shortcut;
import com.winlator.cmod.runtime.display.renderer.VulkanRenderer;
import com.winlator.cmod.runtime.display.ui.FrameRating;
import com.winlator.cmod.runtime.display.ui.MangoHudView;
import com.winlator.cmod.runtime.display.ui.XServerSurfaceView;
import com.winlator.cmod.shared.android.RefreshRateUtils;

/**
 * Owns LSFG / DIS / system frame-generation state and apply/save logic.
 * Extracted from {@code XServerDisplayActivity} to shrink the god-class without
 * changing public Activity APIs used by the rest of the app.
 *
 * Drop-in: same package tree as the app; Activity holds one instance and delegates.
 */
public final class FrameGenerationController {
    private static final String TAG = "FrameGenerationController";

    private static final long SYSTEM_FRAME_GEN_POLL_MS = 2000L;
    private static final int SYSTEM_FRAME_GEN_IDLE_PROBES = 3;
    private static final int[] DIS_FLOW_MIN_SIDES = {180, 252, 360};
    public static final int DIS_FRAME_GEN_SCALE_DEFAULT = 180;

    /** Minimal surface the controller needs from the hosting Activity. */
    public interface Host {
        Activity activity();
        Shortcut shortcut();
        Container container();
        XServerSurfaceView xServerView();
        FrameRating frameRating();
        MangoHudView mangoHud();
        Handler mainHandler();
        int runtimeFpsLimit();
        boolean isDestroyedOrFinishing();
        void renderDrawerMenu();
        void applyPreferredRefreshRate();
    }

    private final Host host;

    private boolean frameGenEnabled;
    private int frameGenMultiplier = 2;
    private int frameGenTargetRate;
    private int frameGenFlowScale = 70;
    private String frameGenCachePath;
    private float frameGenRefreshRate;

    private boolean disFrameGenEnabled;
    private int disFrameGenScale = DIS_FRAME_GEN_SCALE_DEFAULT;
    private int disFrameGenTargetFps;
    private boolean disFrameGenDebugFlow;

    private SystemFrameGenMonitor systemFrameGenMonitor;
    private boolean systemFrameGenSupported;
    private boolean systemFrameGenHudEnabled;
    private boolean systemFrameGenProbeRunning;
    private int systemFrameGenMultiplier = 1;
    private int systemFrameGenIdleProbes;
    private Runnable systemFrameGenPollRunnable;
    private String systemFrameGenSignal = "";

    private final FrameRating.OutputFrameSource frameGenOutputSource =
            new FrameRating.OutputFrameSource() {
                @Override
                public long getPresentedFrameCount() {
                    VulkanRenderer renderer = renderer();
                    return renderer != null ? renderer.getPresentedFrameCount() : 0L;
                }

                @Override
                public long getGeneratedFrameCount() {
                    VulkanRenderer renderer = renderer();
                    return renderer != null ? renderer.getGeneratedFrameCount() : 0L;
                }
            };

    public FrameGenerationController(Host host) {
        this.host = host;
    }

    // region state accessors (drawer / HUD still need these)

    public boolean isFrameGenEnabled() { return frameGenEnabled; }
    public void setFrameGenEnabled(boolean v) { frameGenEnabled = v; }

    public int getFrameGenMultiplier() { return frameGenMultiplier; }
    public void setFrameGenMultiplier(int v) { frameGenMultiplier = clampFrameGenMultiplier(v); }

    public int getFrameGenTargetRate() { return frameGenTargetRate; }
    public void setFrameGenTargetRate(int v) { frameGenTargetRate = Math.max(0, v); }

    public int getFrameGenFlowScale() { return frameGenFlowScale; }
    public void setFrameGenFlowScale(int v) { frameGenFlowScale = clampFrameGenFlowScale(v); }

    public String getFrameGenCachePath() { return frameGenCachePath; }
    public float getFrameGenRefreshRate() { return frameGenRefreshRate; }

    public boolean isDisFrameGenEnabled() { return disFrameGenEnabled; }
    public void setDisFrameGenEnabled(boolean v) { disFrameGenEnabled = v; }

    public int getDisFrameGenScale() { return disFrameGenScale; }
    public void setDisFrameGenScale(int v) { disFrameGenScale = clampDisFrameGenScale(v); }

    public int getDisFrameGenTargetFps() { return disFrameGenTargetFps; }
    public void setDisFrameGenTargetFps(int v) { disFrameGenTargetFps = Math.max(0, v); }

    public boolean isDisFrameGenDebugFlow() { return disFrameGenDebugFlow; }
    public void setDisFrameGenDebugFlow(boolean v) { disFrameGenDebugFlow = v; }

    public boolean isSystemFrameGenSupported() { return systemFrameGenSupported; }
    public boolean isSystemFrameGenHudEnabled() { return systemFrameGenHudEnabled; }
    public int getSystemFrameGenMultiplier() { return systemFrameGenMultiplier; }

    public FrameRating.OutputFrameSource getFrameGenOutputSource() {
        return frameGenOutputSource;
    }

    // endregion

    public void initSystemFrameGenSupport() {
        systemFrameGenSupported = SystemFrameGenDetector.isVendorDevice();
        if (systemFrameGenSupported) {
            Log.i(TAG, "Vendor frame generation possible on this device");
            refreshSystemFrameGenState();
        }
    }

    public void applyFrameGenerationSettings(VulkanRenderer renderer, Container container) {
        if (renderer == null) return;
        Activity activity = host.activity();

        String containerValue = container != null ? container.getExtra("frameGen", "0") : "0";
        String containerMultiplier = container != null ? container.getExtra("frameGenMultiplier", "2") : "2";
        String containerTargetRate = container != null ? container.getExtra("frameGenTargetRate", "0") : "0";
        String containerFlowScale = container != null ? container.getExtra("frameGenFlowScale", "70") : "70";

        frameGenEnabled = "1".equals(getFrameGenSetting("frameGen", containerValue));
        frameGenMultiplier = clampFrameGenMultiplier(
                parseSettingInt(getFrameGenSetting("frameGenMultiplier", containerMultiplier), 2));
        frameGenTargetRate = Math.max(0,
                parseSettingInt(getFrameGenSetting("frameGenTargetRate", containerTargetRate), 0));
        frameGenFlowScale = clampFrameGenFlowScale(
                parseSettingInt(getFrameGenSetting("frameGenFlowScale", containerFlowScale), 70));

        if (frameGenEnabled) {
            int result = com.winlator.cmod.feature.library.LosslessAutoImport.INSTANCE.sync(activity).getResult();
            if (result != com.winlator.cmod.feature.library.LosslessAutoImport.RESULT_READY) {
                Log.i(TAG, "Lossless shader sync at launch: result=" + result);
            }
        } else if (!com.winlator.cmod.runtime.display.lsfg.LosslessScaling.isInstalled(activity)) {
            new Thread(() -> {
                int discovery = com.winlator.cmod.feature.library.LosslessAutoImport.INSTANCE
                        .sync(activity).getResult();
                Log.i(TAG, "Lossless shader discovery (frame generation off): result=" + discovery);
                activity.runOnUiThread(() -> {
                    if (frameGenCachePath != null || host.isDestroyedOrFinishing()) return;
                    java.io.File found = com.winlator.cmod.runtime.display.lsfg.LosslessScaling
                            .resolveCacheFile(activity, true);
                    if (found != null) frameGenCachePath = found.getAbsolutePath();
                });
            }, "LosslessDiscovery").start();
        }

        java.io.File cache = com.winlator.cmod.runtime.display.lsfg.LosslessScaling
                .resolveCacheFile(activity, true);
        frameGenCachePath = cache != null ? cache.getAbsolutePath() : null;
        if (frameGenCachePath == null) {
            if (frameGenEnabled) {
                Log.w(TAG, "frameGen requested but no Lossless shader cache");
            }
            frameGenEnabled = false;
        }

        applyFrameGeneration(renderer);
    }

    public void applyFrameGeneration(VulkanRenderer renderer) {
        if (renderer == null) return;

        if (!frameGenEnabled || frameGenCachePath == null) {
            renderer.setFrameGenerationEnabled(false);
            syncFrameGenerationHud();
            return;
        }

        renderer.setFrameGenerationShaders(frameGenCachePath);
        float refreshRate = applyFrameGenerationDisplayMode();
        renderer.setFrameGenerationMode(frameGenMultiplier, frameGenTargetRate, frameGenFlowScale);
        frameGenRefreshRate = refreshRate;
        renderer.setFrameGenerationRefreshRate(refreshRate);
        renderer.setFrameGenerationEnabled(true);
        syncFrameGenerationHud();
        Log.i(TAG, "Frame generation on: multiplier=" + frameGenMultiplier
                + " targetRate=" + frameGenTargetRate + " flowScale=" + frameGenFlowScale
                + " refreshRate=" + refreshRate);
    }

    public void syncFrameGenerationHud() {
        boolean ourFrameGen = (frameGenEnabled && frameGenCachePath != null) || disFrameGenEnabled;
        boolean systemFrameGen = !ourFrameGen && systemFrameGenHudEnabled;
        boolean active = ourFrameGen || systemFrameGen;

        FrameRating.OutputFrameSource source;
        if (ourFrameGen || frameGenEnabled || disFrameGenEnabled) {
            source = frameGenOutputSource;
        } else if (systemFrameGen) {
            source = ensureSystemFrameGenMonitor();
        } else {
            source = null;
        }

        if (systemFrameGen) {
            SystemFrameGenMonitor monitor = ensureSystemFrameGenMonitor();
            if (monitor.isRunning()) {
                monitor.setMultiplier(systemFrameGenMultiplier);
            } else {
                monitor.start(systemFrameGenMultiplier);
            }
        } else if (systemFrameGenMonitor != null) {
            systemFrameGenMonitor.stop();
        }

        FrameRating frameRating = host.frameRating();
        if (frameRating != null) {
            frameRating.setOutputFrameSource(source);
            frameRating.setFrameGenerationActive(active);
        }
        MangoHudView mangoHud = host.mangoHud();
        if (mangoHud != null) {
            mangoHud.setOutputFrameSource(source);
            mangoHud.setFrameGenerationActive(active);
        }
    }

    public SystemFrameGenMonitor ensureSystemFrameGenMonitor() {
        if (systemFrameGenMonitor == null) {
            systemFrameGenMonitor = new SystemFrameGenMonitor(
                    () -> {
                        VulkanRenderer r = renderer();
                        return r != null ? r.getPresentedFrameCount() : 0L;
                    },
                    () -> {
                        Display display = getDisplayCompat();
                        return display != null ? display.getRefreshRate() : 0f;
                    },
                    System::nanoTime);
        }
        return systemFrameGenMonitor;
    }

    public void refreshSystemFrameGenState() {
        if (!systemFrameGenSupported || systemFrameGenProbeRunning) return;
        systemFrameGenProbeRunning = true;
        new Thread(() -> {
            SystemFrameGenState state;
            try {
                state = SystemFrameGenDetector.detect();
            } catch (Exception e) {
                Log.w(TAG, "System frame generation probe failed", e);
                state = null;
            }
            SystemFrameGenState result = state;
            host.activity().runOnUiThread(() -> {
                systemFrameGenProbeRunning = false;
                if (host.isDestroyedOrFinishing() || result == null) return;
                applySystemFrameGenState(result);
            });
        }, "SystemFrameGenProbe").start();
    }

    public void applySystemFrameGenState(SystemFrameGenState state) {
        if (!state.getSignal().equals(systemFrameGenSignal)) {
            systemFrameGenSignal = state.getSignal();
            Log.i(TAG, "System frame generation signal: "
                    + (systemFrameGenSignal.isEmpty() ? "none" : systemFrameGenSignal)
                    + " multiplier=" + state.getMultiplier());
        }
        if (state.getActive()) {
            systemFrameGenIdleProbes = 0;
            systemFrameGenMultiplier = state.getMultiplier();
        } else {
            systemFrameGenIdleProbes++;
        }

        boolean settled = state.getActive() || systemFrameGenIdleProbes >= SYSTEM_FRAME_GEN_IDLE_PROBES;
        if (!settled) return;
        if (!state.getActive()) systemFrameGenMultiplier = state.getMultiplier();

        if (systemFrameGenHudEnabled == state.getActive()) {
            syncFrameGenerationHud();
            return;
        }
        systemFrameGenHudEnabled = state.getActive();
        syncFrameGenerationHud();
        host.applyPreferredRefreshRate();
    }

    public void startSystemFrameGenPolling() {
        if (!systemFrameGenSupported || systemFrameGenPollRunnable != null) return;
        systemFrameGenPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (host.isDestroyedOrFinishing()) return;
                SystemFrameGenDetector.invalidate();
                refreshSystemFrameGenState();
                host.mainHandler().postDelayed(this, SYSTEM_FRAME_GEN_POLL_MS);
            }
        };
        host.mainHandler().postDelayed(systemFrameGenPollRunnable, SYSTEM_FRAME_GEN_POLL_MS);
    }

    public void stopSystemFrameGenPolling() {
        if (systemFrameGenPollRunnable == null) return;
        host.mainHandler().removeCallbacks(systemFrameGenPollRunnable);
        systemFrameGenPollRunnable = null;
    }

    public void applyFrameGenerationLive() {
        VulkanRenderer renderer = renderer();
        applyFrameGeneration(renderer);
        applyDisFrameGeneration(renderer);
        if ((!frameGenEnabled || frameGenCachePath == null) && !disFrameGenEnabled) {
            host.applyPreferredRefreshRate();
        }
        saveFrameGenerationSettings();
        saveDisFrameGenerationSettings();
        host.renderDrawerMenu();
    }

    public void saveFrameGenerationSettings() {
        Shortcut shortcut = host.shortcut();
        Container container = host.container();
        if (shortcut != null) {
            boolean overridden = saveFrameGenOverride("frameGen", frameGenEnabled ? "1" : "0", "0");
            overridden |= saveFrameGenOverride("frameGenMultiplier",
                    String.valueOf(frameGenMultiplier), "2");
            overridden |= saveFrameGenOverride("frameGenTargetRate",
                    String.valueOf(frameGenTargetRate), "0");
            overridden |= saveFrameGenOverride("frameGenFlowScale",
                    String.valueOf(frameGenFlowScale), "70");
            if (overridden) shortcut.putExtra("use_container_defaults", "0");
            shortcut.saveData();
        } else if (container != null) {
            container.putExtra("frameGen", frameGenEnabled ? "1" : "0");
            container.putExtra("frameGenMultiplier", String.valueOf(frameGenMultiplier));
            container.putExtra("frameGenTargetRate", String.valueOf(frameGenTargetRate));
            container.putExtra("frameGenFlowScale", String.valueOf(frameGenFlowScale));
            container.saveData();
        }
    }

    public void applyDisFrameGenerationSettings(VulkanRenderer renderer, Container container) {
        if (renderer == null) return;

        String containerValue = container != null ? container.getExtra("disFrameGen", "0") : "0";
        String scaleDefault = String.valueOf(DIS_FRAME_GEN_SCALE_DEFAULT);
        String containerScale =
                container != null ? container.getExtra("disFrameGenScale", scaleDefault) : scaleDefault;
        String containerTarget = container != null ? container.getExtra("disFrameGenTargetFps", "0") : "0";

        disFrameGenEnabled = "1".equals(getFrameGenSetting("disFrameGen", containerValue));
        disFrameGenScale = clampDisFrameGenScale(
                parseSettingInt(getFrameGenSetting("disFrameGenScale", containerScale),
                        DIS_FRAME_GEN_SCALE_DEFAULT));
        disFrameGenTargetFps = Math.max(0,
                parseSettingInt(getFrameGenSetting("disFrameGenTargetFps", containerTarget), 0));

        if (disFrameGenEnabled && frameGenEnabled) {
            frameGenEnabled = false;
            applyFrameGeneration(renderer);
        }

        applyDisFrameGeneration(renderer);
    }

    public void applyDisFrameGeneration(VulkanRenderer renderer) {
        if (renderer == null) return;

        if (!disFrameGenEnabled) {
            renderer.setDisFrameGenerationEnabled(false);
            syncFrameGenerationHud();
            return;
        }

        float refreshRate = applyDisFrameGenerationDisplayMode();
        renderer.setDisFrameGenerationScale(disFrameGenScale);
        renderer.setDisFrameGenerationTargetFps(disFrameGenTargetFps);
        renderer.setDisDebugFlow(disFrameGenDebugFlow);
        renderer.setFrameGenerationRefreshRate(refreshRate);
        renderer.setDisFrameGenerationEnabled(true);
        syncFrameGenerationHud();
        Log.i(TAG, "DIS frame generation on: scale=" + disFrameGenScale
                + " targetFps=" + disFrameGenTargetFps + " refreshRate=" + refreshRate);
    }

    public void saveDisFrameGenerationSettings() {
        Shortcut shortcut = host.shortcut();
        Container container = host.container();
        if (shortcut != null) {
            boolean overridden = saveFrameGenOverride("disFrameGen", disFrameGenEnabled ? "1" : "0", "0");
            overridden |= saveFrameGenOverride("disFrameGenScale",
                    String.valueOf(disFrameGenScale), String.valueOf(DIS_FRAME_GEN_SCALE_DEFAULT));
            overridden |= saveFrameGenOverride("disFrameGenTargetFps", String.valueOf(disFrameGenTargetFps), "0");
            if (overridden) shortcut.putExtra("use_container_defaults", "0");
            shortcut.saveData();
        } else if (container != null) {
            container.putExtra("disFrameGen", disFrameGenEnabled ? "1" : "0");
            container.putExtra("disFrameGenScale", String.valueOf(disFrameGenScale));
            container.putExtra("disFrameGenTargetFps", String.valueOf(disFrameGenTargetFps));
            container.saveData();
        }
    }

    public float applyFrameGenerationDisplayMode() {
        Window window = host.activity().getWindow();
        if (window == null) return 0f;

        WindowManager.LayoutParams params = window.getAttributes();
        if (!frameGenEnabled) {
            if (params.preferredDisplayModeId != 0) {
                params.preferredDisplayModeId = 0;
                window.setAttributes(params);
            }
            return 0f;
        }

        Display display = getDisplayCompat();
        if (display == null) return 0f;

        Display.Mode active = display.getMode();
        int wanted;
        int runtimeFpsLimit = host.runtimeFpsLimit();
        if (frameGenTargetRate > 0) {
            wanted = frameGenTargetRate;
        } else if (runtimeFpsLimit > 0) {
            wanted = frameGenMultiplier * runtimeFpsLimit;
        } else {
            wanted = Integer.MAX_VALUE;
        }

        Display.Mode best = null;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() != active.getPhysicalWidth()
                    || mode.getPhysicalHeight() != active.getPhysicalHeight()) {
                continue;
            }
            if (best == null || betterFrameGenMode(mode, best, wanted, runtimeFpsLimit)) best = mode;
        }
        if (best == null) return active.getRefreshRate();
        if (best.getModeId() == params.preferredDisplayModeId && params.preferredRefreshRate == 0f) {
            return best.getRefreshRate();
        }

        params.preferredDisplayModeId = best.getModeId();
        params.preferredRefreshRate = 0f;
        window.setAttributes(params);
        Log.i(TAG, "Frame generation display mode: wanted "
                + (wanted == Integer.MAX_VALUE ? "highest" : wanted + "Hz")
                + ", selected " + Math.round(best.getRefreshRate()) + "Hz (mode "
                + best.getModeId() + ") fpsLimit=" + runtimeFpsLimit + " cadenceOk="
                + (runtimeFpsLimit <= 0
                        || RefreshRateUtils.isFrameCadenceCompatible(
                                best.getRefreshRate(), runtimeFpsLimit)));
        return best.getRefreshRate();
    }

    public float applyDisFrameGenerationDisplayMode() {
        Window window = host.activity().getWindow();
        if (window == null) return 0f;

        WindowManager.LayoutParams params = window.getAttributes();
        if (!disFrameGenEnabled) {
            if (params.preferredDisplayModeId != 0) {
                params.preferredDisplayModeId = 0;
                window.setAttributes(params);
            }
            return 0f;
        }

        Display display = getDisplayCompat();
        if (display == null) return 0f;

        Display.Mode active = display.getMode();
        int wanted = disFrameGenTargetFps > 0 ? disFrameGenTargetFps : Integer.MAX_VALUE;

        Display.Mode best = null;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() != active.getPhysicalWidth()
                    || mode.getPhysicalHeight() != active.getPhysicalHeight()) {
                continue;
            }
            if (best == null || betterDisFrameGenMode(mode, best, wanted)) best = mode;
        }
        if (best == null) return active.getRefreshRate();
        if (best.getModeId() == params.preferredDisplayModeId && params.preferredRefreshRate == 0f) {
            return best.getRefreshRate();
        }

        params.preferredDisplayModeId = best.getModeId();
        params.preferredRefreshRate = 0f;
        window.setAttributes(params);
        Log.i(TAG, "DIS frame generation display mode: wanted "
                + (wanted == Integer.MAX_VALUE ? "highest" : wanted + "Hz")
                + ", selected " + Math.round(best.getRefreshRate()) + "Hz (mode "
                + best.getModeId() + ")");
        return best.getRefreshRate();
    }

    public void syncFrameGenerationRefreshRate() {
        boolean lsfg = frameGenEnabled && frameGenCachePath != null;
        if (!lsfg && !disFrameGenEnabled) return;

        Display display = getDisplayCompat();
        if (display == null) return;

        float active = display.getMode().getRefreshRate();
        if (active <= 0f || Math.abs(active - frameGenRefreshRate) < 0.5f) return;

        Log.i(TAG, "Frame generation panel changed: "
                + Math.round(frameGenRefreshRate) + "Hz -> " + Math.round(active) + "Hz");
        frameGenRefreshRate = active;
        VulkanRenderer renderer = renderer();
        if (renderer != null) renderer.setFrameGenerationRefreshRate(active);
    }

    /** Used by Activity.applyPreferredRefreshRate when LSFG is active. */
    public boolean isLsfgActive() {
        return frameGenEnabled && frameGenCachePath != null;
    }

    public boolean isAnyFrameGenActive() {
        return isLsfgActive() || disFrameGenEnabled;
    }

    public void onDestroy() {
        stopSystemFrameGenPolling();
        if (systemFrameGenMonitor != null) {
            systemFrameGenMonitor.stop();
            systemFrameGenMonitor = null;
        }
    }

    // region private helpers

    private VulkanRenderer renderer() {
        XServerSurfaceView view = host.xServerView();
        return view != null ? view.getRenderer() : null;
    }

    private String getFrameGenSetting(String key, String containerValue) {
        Shortcut shortcut = host.shortcut();
        if (shortcut == null) return containerValue;
        return shortcut.getSettingExtra(key, containerValue);
    }

    private String frameGenContainerValue(String key, String fallback) {
        Shortcut shortcut = host.shortcut();
        Container base = shortcut != null ? shortcut.container : host.container();
        return base != null ? base.getExtra(key, fallback) : fallback;
    }

    private boolean saveFrameGenOverride(String key, String value, String fallback) {
        Shortcut shortcut = host.shortcut();
        if (shortcut == null) return false;
        if (value.equals(frameGenContainerValue(key, fallback))) {
            shortcut.putExtra(key, null);
            return false;
        }
        shortcut.putExtra(key, value);
        return true;
    }

    private Display getDisplayCompat() {
        Activity activity = host.activity();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Display d = activity.getDisplay();
            if (d != null) return d;
        }
        WindowManager wm = activity.getWindowManager();
        return wm != null ? wm.getDefaultDisplay() : null;
    }

    private static boolean betterFrameGenMode(Display.Mode candidate,
                                              Display.Mode current, int wanted,
                                              int fpsLimit) {
        float a = candidate.getRefreshRate();
        float b = current.getRefreshRate();
        boolean aMeets = a + 0.5f >= wanted;
        boolean bMeets = b + 0.5f >= wanted;
        if (aMeets != bMeets) return aMeets;
        if (!aMeets) return a > b;
        if (fpsLimit > 0) {
            boolean aCadence = RefreshRateUtils.isFrameCadenceCompatible(a, fpsLimit);
            boolean bCadence = RefreshRateUtils.isFrameCadenceCompatible(b, fpsLimit);
            if (aCadence != bCadence) return aCadence;
        }
        return a < b;
    }

    private static boolean betterDisFrameGenMode(Display.Mode candidate,
                                                 Display.Mode current, int wanted) {
        float a = candidate.getRefreshRate();
        float b = current.getRefreshRate();
        boolean aMeets = a + 0.5f >= wanted;
        boolean bMeets = b + 0.5f >= wanted;
        if (aMeets != bMeets) return aMeets;
        if (!aMeets) return a > b;
        return a < b;
    }

    public static int clampDisFrameGenScale(int value) {
        int minSide = (value > 0 && value <= 100) ? value * 720 / 100 : value;
        int best = DIS_FRAME_GEN_SCALE_DEFAULT;
        int bestDelta = Integer.MAX_VALUE;
        for (int candidate : DIS_FLOW_MIN_SIDES) {
            int delta = Math.abs(candidate - minSide);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = candidate;
            }
        }
        return best;
    }

    public static int clampFrameGenMultiplier(int value) {
        return Math.max(2, Math.min(4, value));
    }

    public static int clampFrameGenFlowScale(int value) {
        return Math.max(25, Math.min(100, value));
    }

    public static int parseSettingInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // endregion
}
