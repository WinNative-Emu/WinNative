package com.winlator.cmod.runtime.display.wayland;

import android.content.Context;

import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.container.Shortcut;
import com.winlator.cmod.runtime.content.ContentsManager;
import com.winlator.cmod.runtime.system.GPUInformation;
import com.winlator.cmod.runtime.wine.WineInfo;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Decides whether a session can run on the embedded Wayland compositor. A Wine/Proton install is
 * Wayland-capable when it ships winewayland.so and the Wayland Turnip driver the guest renders on;
 * the device must have an Adreno GPU, since the compositor imports the game's frames through Turnip.
 * The bundled main Proton is never capable. Verdicts are cached per wine identifier.
 */
public final class WineWaylandSupport {
    private WineWaylandSupport() {}

    private static final Map<String, Boolean> cache = new HashMap<>();
    private static volatile Boolean adrenoGpu;

    public static boolean isAdrenoDevice(Context context) {
        Boolean known = adrenoGpu;
        if (known == null) {
            boolean adreno;
            try {
                adreno = GPUInformation.isAdrenoGPU(context.getApplicationContext());
            } catch (Throwable t) {
                adreno = false;
            }
            adrenoGpu = known = adreno;
        }
        return known;
    }

    public static boolean isWaylandCapable(WineInfo wineInfo) {
        if (wineInfo == null || wineInfo.path == null || wineInfo.path.isEmpty()) return false;
        if (WineInfo.isMainWineVersion(wineInfo.identifier())) return false;
        return isWaylandCapable(wineInfo.identifier(), wineInfo.path);
    }

    public static boolean isWaylandCapable(Context context, ContentsManager contentsManager, String identifier) {
        if (identifier == null || identifier.isEmpty() || WineInfo.isMainWineVersion(identifier)) return false;
        synchronized (cache) {
            Boolean cached = cache.get(identifier);
            if (cached != null) return cached;
        }
        try {
            return isWaylandCapable(WineInfo.fromIdentifier(context, contentsManager, identifier));
        } catch (Exception e) {
            return false;
        }
    }

    /** Builds a ContentsManager on a cache miss; call off the main thread when possible. */
    public static boolean isWaylandCapable(Context context, String identifier) {
        if (identifier == null || identifier.isEmpty() || WineInfo.isMainWineVersion(identifier)) return false;
        synchronized (cache) {
            Boolean cached = cache.get(identifier);
            if (cached != null) return cached;
        }
        try {
            ContentsManager contentsManager = new ContentsManager(context);
            contentsManager.syncContents();
            return isWaylandCapable(context, contentsManager, identifier);
        } catch (Exception e) {
            return false;
        }
    }

    /** The device and the given wine version together allow a Wayland session. */
    public static boolean isAvailable(Context context, String wineIdentifier) {
        return isAdrenoDevice(context) && isWaylandCapable(context, wineIdentifier);
    }

    /** The container selected Wayland and can drive it. */
    public static boolean runsOnWayland(Context context, Container container) {
        if (container == null) return false;
        return container.isWaylandBackend() && isAvailable(context, container.getWineVersion());
    }

    /** The shortcut's own choice, else the container's, gated the same way. */
    public static boolean runsOnWayland(Context context, Shortcut shortcut) {
        if (shortcut == null || shortcut.container == null) return false;
        String backend = shortcut.getSettingExtra(Container.EXTRA_DISPLAY_BACKEND, shortcut.container.getDisplayBackend());
        String wineVersion = shortcut.getSettingExtra("wineVersion", shortcut.container.getWineVersion());
        return Container.DISPLAY_BACKEND_WAYLAND.equals(backend) && isAvailable(context, wineVersion);
    }

    /** Drops every cached verdict; call after a wine/proton install or removal. */
    public static void invalidate() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private static boolean isWaylandCapable(String cacheKey, String installPath) {
        synchronized (cache) {
            Boolean cached = cache.get(cacheKey);
            if (cached != null) return cached;
        }
        File winewayland = new File(installPath, "lib/wine/aarch64-unix/winewayland.so");
        if (!winewayland.isFile()) winewayland = new File(installPath, "lib/wine/x86_64-unix/winewayland.so");
        File waylandTurnip = new File(installPath, "lib/libvulkan_freedreno_wayland.so");
        boolean capable = winewayland.isFile() && waylandTurnip.isFile();
        synchronized (cache) {
            cache.put(cacheKey, capable);
        }
        return capable;
    }
}
