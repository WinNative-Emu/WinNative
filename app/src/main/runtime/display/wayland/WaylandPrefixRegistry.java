package com.winlator.cmod.runtime.display.wayland;

import android.util.Log;

import com.winlator.cmod.runtime.wine.WineRegistryEditor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sets and removes string values in a Wine registry file at the text level. A missing key is
 * appended as its own block, which wineserver accepts anywhere in the file, so no parent key has to
 * exist first. Used for the handful of values a Wayland session needs in user.reg.
 */
public final class WaylandPrefixRegistry {
    private static final String TAG = "WaylandPrefixRegistry";

    private final File file;
    private final List<String> lines = new ArrayList<>();
    private boolean modified;

    private WaylandPrefixRegistry(File file) {
        this.file = file;
    }

    public interface Edit {
        void apply(WaylandPrefixRegistry reg);
    }

    public static void edit(File file, Edit edit) {
        if (!file.isFile()) return;
        WineRegistryEditor.withFileLock(file, () -> {
            WaylandPrefixRegistry reg = new WaylandPrefixRegistry(file);
            try {
                reg.lines.addAll(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.e(TAG, "cannot read " + file, e);
                return;
            }
            edit.apply(reg);
            if (reg.modified) reg.save();
        });
    }

    public String get(String key, String name) {
        int start = keyStart(key);
        if (start < 0) return null;
        int index = valueIndex(start, name);
        if (index < 0) return null;
        String line = lines.get(index);
        int eq = line.indexOf("\"=\"");
        if (eq < 0 || !line.endsWith("\"")) return null;
        return unescape(line.substring(eq + 3, line.length() - 1));
    }

    public void set(String key, String name, String value) {
        String entry = "\"" + escape(name) + "\"=\"" + escape(value) + "\"";
        int start = keyStart(key);
        if (start < 0) {
            long now = System.currentTimeMillis();
            long ticks1601To1970 = 86400L * (369 * 365 + 89) * 10000000L;
            long time = now * 10000L + ticks1601To1970;
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) lines.add("");
            lines.add("[" + escape(key) + "] " + (now / 1000));
            lines.add(String.format(Locale.ENGLISH, "#time=%x", time));
            lines.add(entry);
            lines.add("");
            modified = true;
            return;
        }
        int index = valueIndex(start, name);
        if (index >= 0) {
            if (lines.get(index).equals(entry)) return;
            lines.set(index, entry);
        } else {
            lines.add(keyEnd(start), entry);
        }
        modified = true;
    }

    public void remove(String key, String name) {
        int start = keyStart(key);
        if (start < 0) return;
        int index = valueIndex(start, name);
        if (index < 0) return;
        lines.remove(index);
        modified = true;
    }

    private int keyStart(String key) {
        String header = "[" + escape(key) + "]";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(header) && (line.length() == header.length() || line.charAt(header.length()) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    /** Index just past the last value line of the key block starting at start. */
    private int keyEnd(int start) {
        int end = start + 1;
        int last = end;
        while (end < lines.size() && !lines.get(end).startsWith("[")) {
            if (!lines.get(end).isEmpty()) last = end + 1;
            end++;
        }
        return last;
    }

    private int valueIndex(int start, String name) {
        String prefix = "\"" + escape(name) + "\"=";
        for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("["); i++) {
            if (lines.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }

    private void save() {
        File tmp = new File(file.getParentFile(), file.getName() + ".wayland.tmp");
        try {
            Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.e(TAG, "cannot write " + file, e);
            tmp.delete();
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
