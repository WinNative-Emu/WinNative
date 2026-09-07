package com.winlator.cmod.runtime.display;
import com.winlator.cmod.runtime.content.ContentProfile;
import com.winlator.cmod.runtime.content.ContentsManager;
import com.winlator.cmod.runtime.system.ProcessHelper;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Pure stateless helpers split out of XServerDisplayActivity (behavior-identical).
final class XServerDisplayUtils {
    private XServerDisplayUtils() {}

    // Builds a WINEDEBUG value enabling only the chosen message classes on the
    // chosen channels. "-all" first zeroes every class so unchosen ones (notably
    // trace) stay off, then each "class+channel" turns one class on.
    static String buildWineDebug(String classesCsv, String channelsCsv) {
        java.util.List<String> classes = splitCsv(classesCsv);
        java.util.List<String> channels = splitCsv(channelsCsv);
        if (classes.isEmpty() || channels.isEmpty()) return "-all";
        StringBuilder sb = new StringBuilder("-all");
        for (String channel : channels) {
            for (String cls : classes) {
                sb.append(',').append(cls).append('+').append(channel);
            }
        }
        return sb.toString();
    }

    static java.util.List<String> splitCsv(String value) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (value == null) return out;
        for (String part : value.split(",")) {
            String token = part.trim();
            if (!token.isEmpty()) out.add(token);
        }
        return out;
    }

    static boolean isSteamExeRunning() {
        for (String detail : ProcessHelper.listRunningWineProcessDetails()) {
            if (detail.toLowerCase().contains("steam.exe")) return true;
        }
        return false;
    }

    static boolean wnLauncherLogContains(File log, String marker) {
        if (log == null || !log.isFile()) return false;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(log)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(marker)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    static int clampSGSRUpscaleMode(int mode) {
        return SGSRResolutionUtils.clampUpscaleMode(mode);
    }

    static int normalizeSGSRShortcutUpscaleMode(int mode) {
        return SGSRResolutionUtils.normalizeShortcutUpscaleMode(mode);
    }

    static float clampHudAlpha(float v) {
        return Math.max(0.1f, Math.min(1.0f, v));
    }

    static String resTierLabel(int shortSide) {
        switch (shortSide) {
            case 2160: return "4K";
            case 1440: return "2K";
            case 1080: return "1080p";
            case 720:  return "720p";
            default:   return shortSide + "p";
        }
    }

    static int tierShortForLabel(String label) {
        switch (label) {
            case "4K":    return 2160;
            case "2K":    return 1440;
            case "1080p": return 1080;
            case "720p":  return 720;
            default:      return 0;
        }
    }

    // Quality preset → bits-per-pixel·frame, then bitrate, clamped to a sane window.
    static int recordBitrate(int w, int h, int fps, int quality) {
        double bpp;
        switch (quality) {
            case 0:  bpp = 0.035; break; // Performance
            case 1:  bpp = 0.075; break; // Balance
            default: bpp = 0.15;  break; // Quality
        }
        long bps = (long) (w * (long) h * fps * bpp);
        return (int) Math.max(2_000_000L, Math.min(bps, 80_000_000L));
    }

    static String contentVersionIdentifier(ContentProfile profile) {
        String entryName = ContentsManager.getEntryName(profile);
        int firstDash = entryName.indexOf('-');
        return firstDash >= 0 ? entryName.substring(firstDash + 1) : entryName;
    }

    static boolean safeEquals(String a, String b) {
        return a != null && a.equals(b);
    }

    static String findDelimitedWrapper(String value, String prefix) {
        if (value == null) return null;
        for (String part : value.split(";")) {
            if (part.startsWith(prefix)) return part;
        }
        return null;
    }

    static boolean hasSelectedVkd3dVersion(String version) {
        return version != null && !version.isEmpty() && !version.equalsIgnoreCase("None");
    }

    static boolean hasSelectedDxvkWrapper(String dxvkWrapper) {
        if (dxvkWrapper == null) return false;
        String version = dxvkWrapper.startsWith("dxvk-")
                ? dxvkWrapper.substring("dxvk-".length())
                : dxvkWrapper;
        return !version.trim().isEmpty() && !version.equalsIgnoreCase("None");
    }

    static int compareVersion(String varA, String varB) {
        int[] a = parseSemverLoose(varA);
        int[] b = parseSemverLoose(varB);

        if (a[0] != b[0]) return a[0] - b[0];
        if (a[1] != b[1]) return a[1] - b[1];
        return a[2] - b[2];
    }

    static final Pattern SEMVER_LOOSE =
            Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    static int[] parseSemverLoose(String s) {
        if (s == null) return new int[]{0, 0, 0};

        Matcher m = SEMVER_LOOSE.matcher(s);

        String g1 = null, g2 = null, g3 = null;
        while (m.find()) {
            g1 = m.group(1);
            g2 = m.group(2);
            g3 = m.group(3);
        }

        if (g1 == null || g2 == null) {
            return new int[]{0, 0, 0};
        }

        int major = safeParseInt(g1);
        int minor = safeParseInt(g2);
        int patch = safeParseInt(g3);
        return new int[]{major, minor, patch};
    }

    static int safeParseInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** Base file name of a Windows/Unix exe path (handles both '\\' and '/' separators). */
    static String exeBaseName(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        return normalized.trim();
    }

    private static final int EXE_STEM_MIN_LENGTH = 2;

    private static final String[] EXE_HANDOFF_SUFFIXES = {
        ".original", ".unpacked",
        "-win64-shipping", "-win32-shipping", "-shipping",
        "_win64", "_win32", "-win64", "-win32", "_win", "-win",
        "_amd64", "-amd64", "_x64", "_x86", "-x64", "-x86",
        "_64", "_32", "-64", "-32",
        "_dx12", "_dx11", "_dx9", "-dx12", "-dx11",
        "_vulkan", "-vulkan", "_opengl", "-opengl",
        "_launcher", "-launcher", "launcher",
        "_game", "-game", "game",
        "_app", "-app",
        "64", "32",
    };

    static String exeStem(String path) {
        String name = exeBaseName(path).toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".exe")) name = name.substring(0, name.length() - 4);
        boolean stripped = true;
        while (stripped && !name.isEmpty()) {
            stripped = false;
            for (String suffix : EXE_HANDOFF_SUFFIXES) {
                if (name.length() <= suffix.length()) continue;
                if (name.length() - suffix.length() < EXE_STEM_MIN_LENGTH) continue;
                if (!name.endsWith(suffix)) continue;
                name = name.substring(0, name.length() - suffix.length());
                stripped = true;
                break;
            }
        }
        return name;
    }

    private static final String[] ARCH_TAGS = { "_win64", "_win32", "_x64", "_x86", "_64", "_32" };

    static String preferLauncherExe(String relativeExe, String gameInstallPath) {
        if (relativeExe == null || relativeExe.isEmpty()
                || gameInstallPath == null || gameInstallPath.isEmpty()) return relativeExe;
        String normalized = relativeExe.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String dir = slash >= 0 ? normalized.substring(0, slash + 1) : "";
        String base = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (!base.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) return relativeExe;
        String stem = base.substring(0, base.length() - 4);
        for (String tag : ARCH_TAGS) {
            if (!stem.toLowerCase(java.util.Locale.ROOT).endsWith(tag)) continue;
            String launcher = stem.substring(0, stem.length() - tag.length()) + ".exe";
            String candidate = dir + launcher;
            if (new java.io.File(gameInstallPath, candidate).isFile()) return candidate;
            break;
        }
        return relativeExe;
    }

    static boolean sameExeFamily(String a, String b) {
        String baseA = exeBaseName(a);
        String baseB = exeBaseName(b);
        if (baseA.isEmpty() || baseB.isEmpty()) return false;
        if (baseA.equalsIgnoreCase(baseB)) return true;
        String stemA = exeStem(baseA);
        String stemB = exeStem(baseB);
        return !stemA.isEmpty() && stemA.equals(stemB);
    }
}
