package com.winlator.cmod.runtime.display.wayland;

import android.content.Context;

import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.container.Shortcut;
import com.winlator.cmod.runtime.content.ContentsManager;
import com.winlator.cmod.runtime.system.GPUInformation;
import com.winlator.cmod.runtime.wine.WineInfo;

import android.util.Log;


import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Decides whether a session can run on the embedded Wayland compositor. A Wine/Proton install is
 * Wayland-capable when it ships winewayland.so and the Wayland Turnip driver the guest renders on;
 * the device must have an Adreno GPU, since the compositor imports the game's frames through Turnip.
 * The bundled main Proton is never capable. Verdicts are cached per wine identifier.
 *
 * The files cannot be copied in from another Proton: winewayland.so is a Wine unixlib bound to the
 * win32u it was built against, so a foreign copy loads and connects but creates no window, leaving
 * a black screen. The Proton has to ship its own.
 */
public final class WineWaylandSupport {
    private WineWaylandSupport() {}

    private static final String TAG = "WineWaylandSupport";
    private static final Map<String, Boolean> cache = new HashMap<>();
    private static volatile Boolean adrenoGpu;

    private static final String UNIX_DRIVER = "lib/wine/aarch64-unix/winewayland.so";
    private static final String WAYLAND_TURNIP = "lib/libvulkan_freedreno_wayland.so";
    private static final String ICD_DIR = "share/vulkan/icd.d";
    private static final String TURNIP_PREFIX = "libvulkan_freedreno_wayland";
    public static final String MANIFEST_PREFIX = "wayland_turnip";


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

    /** The manifest path winewayland is pointed at for a driver variant ("" for the plain driver). */
    public static File manifestFor(File wineRoot, String variant) {
        String suffix = variant == null || variant.isEmpty() ? "" : "_" + variant;
        return new File(wineRoot, ICD_DIR + "/" + MANIFEST_PREFIX + suffix + ".json");
    }

    /**
     * Writes one Vulkan ICD manifest per bundled Wayland Turnip under WinNative's own names, so the
     * guest driver is addressed without the donor's branding. Existing files are left alone.
     */
    public static boolean writeManifests(File wineRoot) {
        File[] turnips = new File(wineRoot, "lib").listFiles((dir, name) ->
                name.startsWith(TURNIP_PREFIX) && name.endsWith(".so"));
        if (turnips == null || turnips.length == 0) return false;
        File icdDir = new File(wineRoot, ICD_DIR);
        if (!icdDir.isDirectory() && !icdDir.mkdirs()) return false;
        for (File turnip : turnips) {
            String rest = turnip.getName().substring(TURNIP_PREFIX.length(), turnip.getName().length() - 3);
            String variant = rest.startsWith("_") ? rest.substring(1) : rest;
            File manifest = manifestFor(wineRoot, variant);
            if (manifest.isFile()) continue;
            String json = "{\n    \"ICD\": {\n        \"api_version\": \"1.4.0\",\n"
                    + "        \"library_arch\": \"64\",\n"
                    + "        \"library_path\": \"../../../lib/" + turnip.getName() + "\"\n"
                    + "    },\n    \"file_format_version\": \"1.0.1\"\n}\n";
            File tmp = new File(icdDir, manifest.getName() + ".part");
            try {
                Files.write(tmp.toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Files.move(tmp.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Log.e(TAG, "cannot write " + manifest, e);
                tmp.delete();
                return false;
            }
        }
        return true;
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
        // aarch64 only. An x86_64 layer's winewayland.so loads and drives the compositor under
        // Box64, but it cannot dlopen the aarch64 Wayland Turnip the game has to present through,
        // so the session would come up as a desktop that never renders a frame.
        File winewayland = new File(installPath, UNIX_DRIVER);
        File waylandTurnip = new File(installPath, WAYLAND_TURNIP);
        boolean capable = winewayland.isFile() && waylandTurnip.isFile();
        synchronized (cache) {
            cache.put(cacheKey, capable);
        }
        return capable;
    }
}
