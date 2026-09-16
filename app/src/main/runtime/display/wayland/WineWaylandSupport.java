package com.winlator.cmod.runtime.display.wayland;

import android.content.Context;

import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.container.Shortcut;
import com.winlator.cmod.runtime.content.ContentsManager;
import com.winlator.cmod.runtime.system.GPUInformation;
import com.winlator.cmod.runtime.wine.WineInfo;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.winlator.cmod.runtime.content.ContentProfile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a session can run on the embedded Wayland compositor. A Wine/Proton install is
 * Wayland-capable when it ships winewayland.so and the Wayland Turnip driver the guest renders on;
 * the device must have an Adreno GPU, since the compositor imports the game's frames through Turnip.
 * The bundled main Proton is never capable. Verdicts are cached per wine identifier.
 *
 * An arm64ec Proton that lacks the files can borrow them from an installed Wayland Proton of the
 * same Wine major version: winewayland.so is a Wine unixlib and only matches the Wine it was built
 * against. The copy lands inside the target Proton so every later check sees a capable install.
 */
public final class WineWaylandSupport {
    private WineWaylandSupport() {}

    private static final String TAG = "WineWaylandSupport";
    private static final Map<String, Boolean> cache = new HashMap<>();
    private static final Set<String> copying = new HashSet<>();
    private static volatile Boolean adrenoGpu;

    private static final String[] BORROWED_LIBS = {
        "libwayland-client.so", "libwayland-egl.so", "libxkbcommon.so", "libxkbregistry.so"
    };
    private static final String UNIX_DRIVER = "lib/wine/aarch64-unix/winewayland.so";
    private static final String WAYLAND_TURNIP = "lib/libvulkan_freedreno_wayland.so";

    public interface CopyListener {
        void onDone(boolean ok, String donorName, String targetName);
    }

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

    /** The installed Wayland Proton whose files the given wine identifier could borrow, or null. */
    public static WineInfo findDonor(Context context, String targetIdentifier) {
        WineInfo target = resolve(context, targetIdentifier);
        if (target == null || !target.isArm64EC() || isWaylandCapable(target)) return null;
        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();
        return findDonor(context, contentsManager, target);
    }

    public static WineInfo findDonor(Context context, ContentsManager contentsManager, WineInfo target) {
        if (target == null || !target.isArm64EC()) return null;
        List<ContentProfile> profiles = new ArrayList<>();
        List<ContentProfile> protons = contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON);
        List<ContentProfile> wines = contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE);
        if (protons != null) profiles.addAll(protons);
        if (wines != null) profiles.addAll(wines);
        for (ContentProfile profile : profiles) {
            if (!profile.isInstalled) continue;
            String entry = ContentsManager.getEntryName(profile);
            WineInfo donor;
            try {
                donor = WineInfo.fromIdentifier(context, contentsManager, entry);
            } catch (Exception e) {
                continue;
            }
            if (donor == null || donor.path == null || donor.path.equals(target.path)) continue;
            if (!donor.isArm64EC() || !isWaylandCapable(donor)) continue;
            if (!majorVersion(donor.version).equals(majorVersion(target.version))) continue;
            return donor;
        }
        return null;
    }

    /** Copies the Wayland driver, its Turnips and client libraries from donor into target. */
    public static boolean borrowWaylandFiles(WineInfo donor, WineInfo target) {
        if (donor == null || target == null || donor.path == null || target.path == null) return false;
        String key = target.identifier();
        synchronized (copying) {
            if (!copying.add(key)) return false;
        }
        try {
            File src = new File(donor.path);
            File dst = new File(target.path);
            if (!new File(dst, "lib/wine/aarch64-unix").isDirectory()) return false;
            for (String lib : BORROWED_LIBS) {
                if (!copyFile(new File(src, "lib/" + lib), new File(dst, "lib/" + lib))) return false;
            }
            File[] turnips = new File(src, "lib").listFiles((dir, name) ->
                    name.startsWith("libvulkan_freedreno_wayland_") && name.endsWith(".so"));
            if (turnips != null) {
                for (File turnip : turnips) {
                    if (!copyFile(turnip, new File(dst, "lib/" + turnip.getName()))) return false;
                }
            }
            for (String drv : new String[] {"lib/wine/aarch64-windows/winewayland.drv", "lib/wine/i386-windows/winewayland.drv"}) {
                File from = new File(src, drv);
                if (from.isFile() && !copyFile(from, new File(dst, drv))) return false;
            }
            File xkb = new File(src, "share/X11/xkb");
            File xkbDst = new File(dst, "share/X11/xkb");
            if (xkb.isDirectory() && !xkbDst.isDirectory() && !copyTree(xkb, xkbDst)) return false;
            if (!copyFile(new File(src, WAYLAND_TURNIP), new File(dst, WAYLAND_TURNIP))) return false;
            if (!copyFile(new File(src, UNIX_DRIVER), new File(dst, UNIX_DRIVER))) return false;
            Log.i(TAG, "borrowed Wayland files from " + donor.identifier() + " into " + target.identifier());
            return true;
        } finally {
            synchronized (copying) {
                copying.remove(key);
            }
            invalidate();
        }
    }

    /** Runs the borrow on a worker thread when the target lacks the files; reports on the main thread. */
    public static void borrowWaylandFilesAsync(Context context, String targetIdentifier, CopyListener listener) {
        Context app = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            WineInfo target = resolve(app, targetIdentifier);
            if (target == null || isWaylandCapable(target)) return;
            WineInfo donor = findDonor(app, targetIdentifier);
            if (donor == null) return;
            boolean ok = borrowWaylandFiles(donor, target);
            if (listener != null) {
                new Handler(Looper.getMainLooper()).post(() ->
                        listener.onDone(ok, donor.identifier(), target.identifier()));
            }
        }, "wayland-borrow");
        worker.setDaemon(true);
        worker.start();
    }

    private static WineInfo resolve(Context context, String identifier) {
        if (identifier == null || identifier.isEmpty() || WineInfo.isMainWineVersion(identifier)) return null;
        try {
            ContentsManager contentsManager = new ContentsManager(context);
            contentsManager.syncContents();
            return WineInfo.fromIdentifier(context, contentsManager, identifier);
        } catch (Exception e) {
            return null;
        }
    }

    private static String majorVersion(String version) {
        if (version == null) return "";
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
        return version.substring(0, end);
    }

    private static boolean copyFile(File from, File to) {
        if (!from.isFile()) {
            Log.w(TAG, "borrow: missing " + from);
            return false;
        }
        File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        File tmp = new File(parent, to.getName() + ".part");
        try {
            Files.copy(from.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "borrow: copy failed for " + from, e);
            tmp.delete();
            return false;
        }
    }

    private static boolean copyTree(File from, File to) {
        File[] children = from.listFiles();
        if (children == null) return false;
        if (!to.isDirectory() && !to.mkdirs()) return false;
        for (File child : children) {
            File target = new File(to, child.getName());
            if (child.isDirectory()) {
                if (!copyTree(child, target)) return false;
            } else if (!copyFile(child, target)) {
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
        File winewayland = new File(installPath, UNIX_DRIVER);
        if (!winewayland.isFile()) winewayland = new File(installPath, "lib/wine/x86_64-unix/winewayland.so");
        File waylandTurnip = new File(installPath, WAYLAND_TURNIP);
        boolean capable = winewayland.isFile() && waylandTurnip.isFile();
        synchronized (cache) {
            cache.put(cacheKey, capable);
        }
        return capable;
    }
}
