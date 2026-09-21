package com.winlator.cmod.runtime.display.wayland;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;

/**
 * Names the graphics API a Wayland session runs on, for the HUD.
 *
 * On X11 the Vulkan wrapper publishes _MESA_DRV_ENGINE_NAME on the game's window and the HUD reads
 * the name from there. A Wayland session has neither half of that: the game has no X11 window, and
 * winewayland.drv repoints VK_ICD_FILENAMES at the bundled Turnip, so the wrapper never loads. The
 * container's own processes still say the same thing - whichever translation layer the game runs on
 * is mapped into it - so read it from there instead.
 */
public final class WaylandRendererProbe {
    /** What the HUD shows before anything is known, and the answer for a native Vulkan title. */
    public static final String VULKAN = "Vulkan";

    private WaylandRendererProbe() {}

    /**
     * The renderer of the container rooted at {@code containerRoot}, or null while no process has
     * loaded one yet. Callers keep asking: a Steam session maps the store's own Direct3D before the
     * game maps its, so the answer can rise from DXVK to VKD3D once the game is up.
     *
     * <p>{@code gamePid} is the process behind the window the compositor is showing. Reading that
     * one process answers the question outright; without it every process on the device has to be
     * read instead, which is about a megabyte of /proc a tick.
     */
    public static String probe(File containerRoot, int gamePid) {
        if (containerRoot == null) return null;
        // Matched on the container directory's name, not its full path: the same directory is
        // reachable as both /data/data/<pkg> and /data/user/0/<pkg>, and maps reports whichever
        // path the process opened it through.
        String marker = "/" + containerRoot.getName() + "/.wine/drive_c";

        if (gamePid > 0) {
            int rank = scan(new File("/proc/" + gamePid, "maps"), marker);
            // Nothing yet (the process is still starting, or it has already gone): fall through to
            // the sweep rather than report a renderer the session is not on.
            if (rank != 0) return name(rank);
        }

        File[] entries = new File("/proc").listFiles();
        if (entries == null) return null;
        int best = 0;
        for (File entry : entries) {
            if (!isPid(entry.getName())) continue;
            best = Math.max(best, scan(new File(entry, "maps"), marker));
            if (best == VKD3D) break;
        }
        return name(best);
    }

    /* Every Proton maps its PE modules from here, whichever prefix the game runs in. */
    private static final String PROTON_MARKER = "/lib/wine/";

    /**
     * The renderer of the game a Linux session is running, or null while none is. The window the
     * compositor shows there is gamescope's, which says nothing about the game behind it, so the
     * Proton processes are read instead.
     */
    public static String probeLinuxSession() {
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return null;
        int best = 0;
        for (File entry : entries) {
            if (!isPid(entry.getName())) continue;
            best = Math.max(best, scan(new File(entry, "maps"), PROTON_MARKER));
            if (best == VKD3D) break;
        }
        return name(best);
    }

    /* Ranked so the highest-level translation layer in the container wins, as the X11 window
     * scoring does. d3d12 outranks the rest because a VKD3D title maps dxgi as well. */
    private static final int VULKAN_RANK = 1, OPENGL = 2, DXVK = 3, WINED3D = 4, VKD3D = 5;

    /** The best rank in one process, or 0 if it is not this container's or maps no renderer. */
    private static int scan(File maps, String marker) {
        int rank = 0;
        boolean ours = false;
        try (BufferedReader reader = new BufferedReader(new FileReader(maps), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!ours && line.contains(marker)) ours = true;
                int slash = line.lastIndexOf('/');
                if (slash < 0) continue;
                String file = line.substring(slash + 1).toLowerCase(Locale.ROOT);
                if (!file.endsWith(".dll")) continue;
                if (file.startsWith("d3d12")) rank = Math.max(rank, VKD3D);
                else if (file.equals("wined3d.dll")) rank = Math.max(rank, WINED3D);
                else if (file.startsWith("d3d8") || file.startsWith("d3d9")
                        || file.startsWith("d3d10") || file.startsWith("d3d11")
                        || file.equals("dxgi.dll")) rank = Math.max(rank, DXVK);
                else if (file.equals("opengl32.dll")) rank = Math.max(rank, OPENGL);
                else if (file.equals("winevulkan.dll")) rank = Math.max(rank, VULKAN_RANK);
            }
        } catch (IOException | RuntimeException e) {
            // /proc reads race with process exit, and other apps' processes are not ours to read.
            return 0;
        }
        return ours ? rank : 0;
    }

    private static String name(int rank) {
        switch (rank) {
            case VKD3D: return "VKD3D";
            case WINED3D: return "WineD3D";
            case DXVK: return "DXVK";
            case OPENGL: return "OpenGL";
            case VULKAN_RANK: return VULKAN;
            default: return null;
        }
    }

    private static boolean isPid(String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < '0' || name.charAt(i) > '9') return false;
        }
        return true;
    }
}
