package com.winlator.cmod.feature.shortcuts;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.app.config.SettingsConfig;
import com.winlator.cmod.runtime.container.ContainerManager;
import com.winlator.cmod.runtime.container.Shortcut;
import com.winlator.cmod.shared.io.FileUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/** Writes shortcuts as standalone .desktop files (plus icon) into the configured export folder. */
public final class FrontendExporter {
  private static final String TAG = "FrontendExporter";

  private FrontendExporter() {}

  public static File resolveExportDir(Context context) {
    if (context == null) return null;
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    String uriString = prefs.getString("shortcuts_export_path_uri", null);
    File dir;
    if (uriString != null) {
      String resolved = FileUtils.getFilePathFromUri(context, Uri.parse(uriString));
      if (resolved == null || resolved.isEmpty()) return null;
      dir = new File(resolved);
    } else {
      dir = new File(SettingsConfig.DEFAULT_SHORTCUT_EXPORT_PATH);
    }
    if (!dir.exists() && !dir.mkdirs()) return null;
    return dir;
  }

  public static File exportOne(Context context, Shortcut shortcut) {
    return exportOne(context, shortcut, (String) null);
  }

  public static File exportOne(Context context, Shortcut shortcut, String displayName) {
    File dir = resolveExportDir(context);
    if (dir == null) return null;
    return exportOne(context, shortcut, dir, displayName);
  }

  public static File exportOne(Context context, Shortcut shortcut, File dir, String displayName) {
    if (dir == null || shortcut == null || shortcut.file == null || !shortcut.file.isFile()) {
      return null;
    }
    try {
      // Persist uuid / container_id so the launch can resolve this game.
      shortcut.genUUID();
      if (shortcut.getExtra("container_id").isEmpty()) {
        shortcut.putExtra("container_id", String.valueOf(shortcut.container.id));
        shortcut.saveData();
      }

      String resolvedName =
          (displayName != null && !displayName.trim().isEmpty()) ? displayName.trim() : null;
      if (resolvedName != null && !resolvedName.equals(shortcut.getExtra("frontend_export_name"))) {
        shortcut.putExtra("frontend_export_name", resolvedName);
        shortcut.saveData();
      }
      String baseName =
          sanitizeFileName(resolvedName != null ? resolvedName : FileUtils.getBasename(shortcut.file.getPath()));

      String iconPath = null;
      File iconDst = new File(dir, baseName + ".png");
      File iconSrc = resolveIconFile(context, shortcut);
      if (iconSrc != null && iconSrc.isFile()) {
        FileUtils.copy(iconSrc, iconDst);
        iconPath = iconDst.getAbsolutePath();
      } else {
        android.graphics.Bitmap art = shortcut.getCoverArt();
        if (art != null && FileUtils.saveBitmapToFile(art, iconDst)) {
          iconPath = iconDst.getAbsolutePath();
        }
      }
      if (iconPath == null) Log.w(TAG, "No icon source resolved for: " + shortcut.name);

      File out = new File(dir, baseName + ".desktop");
      FileUtils.writeString(out, buildDesktopContent(shortcut.file, iconPath, resolvedName));
      return out;
    } catch (Exception e) {
      Log.e(TAG, "Failed to export shortcut: " + shortcut.name, e);
      return null;
    }
  }

  public static int exportAll(Context context) {
    File dir = resolveExportDir(context);
    if (dir == null) return 0;
    ArrayList<Shortcut> shortcuts;
    try {
      shortcuts = new ContainerManager(context).loadShortcuts();
    } catch (Exception e) {
      Log.e(TAG, "Failed to load shortcuts for export", e);
      return 0;
    }
    int count = 0;
    for (Shortcut shortcut : shortcuts) {
      String exportName = shortcut.getExtra("frontend_export_name");
      if (exportName == null || exportName.isEmpty()) exportName = shortcut.getExtra("custom_name");
      String displayName = (exportName != null && !exportName.isEmpty()) ? exportName : null;
      if (exportOne(context, shortcut, dir, displayName) != null) count++;
    }
    return count;
  }

  private static String sanitizeFileName(String name) {
    String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("[\\x00-\\x1F]", "");
    safe = safe.replaceAll("[ .]+$", "");
    return safe.isEmpty() ? "game" : safe;
  }

  private static File resolveIconFile(Context context, Shortcut shortcut) {
    String[] candidates = {
      shortcut.getExtra("customLibraryIconPath"),
      shortcut.getExtra("customLibraryGridArtPath"),
      shortcut.getExtra("customLibraryCarouselArtPath"),
      shortcut.getExtra("customLibraryHeroArtPath"),
      shortcut.getExtra("customLibraryListArtPath"),
      shortcut.getCustomCoverArtPath(),
      shortcut.getExtra("customCoverArtPath"),
    };
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isEmpty()) {
        File file = new File(candidate);
        if (file.isFile()) return file;
      }
    }
    File storeArt = resolveStoreArtFile(context, shortcut);
    if (storeArt != null) return storeArt;
    if (shortcut.iconFile != null && shortcut.iconFile.isFile()) return shortcut.iconFile;
    return null;
  }

  private static File resolveStoreArtFile(Context context, Shortcut shortcut) {
    String source = shortcut.getExtra("game_source");
    String store;
    String gameId;
    if ("STEAM".equalsIgnoreCase(source)) {
      store = "steam";
      gameId = shortcut.getExtra("app_id");
    } else if ("EPIC".equalsIgnoreCase(source)) {
      store = "epic";
      gameId = shortcut.getExtra("app_id");
    } else if ("GOG".equalsIgnoreCase(source)) {
      store = "gog";
      gameId = shortcut.getExtra("gog_id");
    } else {
      return null;
    }
    if (gameId == null || gameId.isEmpty()) return null;

    File dir =
        new File(context.getFilesDir(), "library_artwork_cache/" + store + "/" + safeName(gameId));
    File[] files = dir.listFiles();
    if (files == null) return null;

    String[] preferred = {"library_capsule_", "capsule_", "cover_", "header_", "small_capsule_", "hero_"};
    for (String prefix : preferred) {
      for (File file : files) {
        if (file.isFile() && file.getName().startsWith(prefix) && isImageFile(file)) return file;
      }
    }
    for (File file : files) {
      if (file.isFile() && isImageFile(file)) return file;
    }
    return null;
  }

  private static boolean isImageFile(File file) {
    String name = file.getName().toLowerCase(Locale.ROOT);
    return name.endsWith(".jpg")
        || name.endsWith(".jpeg")
        || name.endsWith(".png")
        || name.endsWith(".webp")
        || name.endsWith(".gif");
  }

  private static String safeName(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static String buildDesktopContent(File source, String iconPath, String displayName) {
    StringBuilder out = new StringBuilder();
    boolean inExtra = false;
    for (String line : FileUtils.readLines(source)) {
      String trimmed = line.trim();
      if (trimmed.startsWith("[")) {
        inExtra = trimmed.equals("[Extra Data]");
        out.append(line).append("\n");
        if (!inExtra && trimmed.equals("[Desktop Entry]")) {
          if (displayName != null) out.append("Name=").append(displayName).append("\n");
          if (iconPath != null) out.append("Icon=").append(iconPath).append("\n");
        }
        continue;
      }
      if (!inExtra && displayName != null && trimmed.startsWith("Name=")) continue;
      if (!inExtra && iconPath != null && trimmed.startsWith("Icon=")) continue;
      out.append(line).append("\n");
    }
    return out.toString();
  }
}
