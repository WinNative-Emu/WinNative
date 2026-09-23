package com.winlator.cmod.runtime.content;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.runtime.linux.LinuxRuntime;
import com.winlator.cmod.shared.io.FileUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;
import org.json.JSONException;

public final class DriverPackages {
  public enum Platform {
    ANDROID,
    LINUX
  }

  public static final String BUNDLED = "@bundled";
  public static final String LEGACY = "@legacy";
  private static final String SELECTED = "linux_driver_selected";
  private static final long MAX_BYTES = 128L * 1024 * 1024;

  public static final class Installed {
    public final String id;
    public final Platform platform;

    public Installed(String id, Platform platform) {
      this.id = id;
      this.platform = platform;
    }
  }

  private DriverPackages() {}

  public static File linuxDirectory(Context context) {
    return new File(context.getFilesDir(), "contents/linux-drivers");
  }

  public static List<File> linuxDrivers(Context context) {
    List<File> result = new ArrayList<>();
    File[] directories = linuxDirectory(context).listFiles();
    if (directories != null)
      for (File directory : directories) {
        if (!directory.getName().startsWith(".")
            && new File(directory, "meta.json").isFile()
            && new File(directory, LinuxRuntime.DRIVER_ICD).isFile()) result.add(directory);
      }
    return result;
  }

  public static String selectedLinux(Context context) {
    String selected =
        PreferenceManager.getDefaultSharedPreferences(context).getString(SELECTED, null);
    if (BUNDLED.equals(selected)) return BUNDLED;
    if (LEGACY.equals(selected) && LinuxRuntime.downloadedDriverVersion(context) > 0) return LEGACY;
    if (selected != null
        && linuxDrivers(context).stream().anyMatch(file -> file.getName().equals(selected)))
      return selected;
    return LinuxRuntime.downloadedDriverVersion(context) > LinuxRuntime.TURNIP_BUILD
        ? LEGACY
        : BUNDLED;
  }

  public static void selectLinux(Context context, String id) {
    boolean valid =
        BUNDLED.equals(id)
            || (LEGACY.equals(id) && LinuxRuntime.downloadedDriverVersion(context) > 0)
            || linuxDrivers(context).stream().anyMatch(file -> file.getName().equals(id));
    if (!valid) throw new IllegalArgumentException("Unknown Linux driver");
    PreferenceManager.getDefaultSharedPreferences(context).edit().putString(SELECTED, id).apply();
    com.winlator.cmod.runtime.linux.LinuxDriverChoices.update(context);
  }

  public static File selectedLinuxIcd(Context context) {
    return linuxIcd(context, selectedLinux(context));
  }

  /**
   * The driver an entry runs on. Nothing saved means the entry never chose one, so it gets the
   * driver chosen for the app - the one the Drivers page calls active - and a container made
   * before this setting existed keeps the driver it was running on. A name that was saved and is
   * now gone is a choice that cannot be honoured, and that falls back to the bundled Mesa rather
   * than to someone else's driver.
   */
  public static File selectedLinuxIcd(Context context, String selection) {
    if (selection == null || selection.isEmpty()) return selectedLinuxIcd(context);
    if (selection.equalsIgnoreCase("System")) return linuxIcd(context, BUNDLED);
    return linuxIcd(context, linuxIdForSelection(context, selection));
  }

  private static File linuxIcd(Context context, String id) {
    if (BUNDLED.equals(id)) return null;
    File directory =
        LEGACY.equals(id)
            ? LinuxRuntime.driverDir(context)
            : new File(linuxDirectory(context), id);
    File manifest = new File(directory, LinuxRuntime.DRIVER_ICD);
    try {
      if (!manifest.isFile() || manifest.length() > 65536) return null;
      JSONObject icd = new JSONObject(new String(Files.readAllBytes(manifest.toPath()),
          java.nio.charset.StandardCharsets.UTF_8)).getJSONObject("ICD");
      String path = icd.getString("library_path");
      File library = new File(path);
      if (!library.isAbsolute()) library = new File(directory, path);
      if (library.isFile() && library.canRead()) return manifest;
    } catch (IOException | JSONException error) {
      android.util.Log.w("DriverPackages", "Cannot read Linux driver " + id, error);
    }
    android.util.Log.w("DriverPackages", "Linux driver unavailable; using bundled Mesa: " + id);
    return null;
  }

  public static List<String> linuxSelectionEntries(Context context) {
    List<String> entries = new ArrayList<>();
    entries.add(LinuxRuntime.TURNIP_VERSION);
    if (LinuxRuntime.downloadedDriverVersion(context) > 0) {
      entries.add(linuxName(context, LEGACY));
    }
    for (File driver : linuxDrivers(context)) entries.add(linuxName(context, driver.getName()));
    return entries;
  }

  public static String selectedLinuxName(Context context) {
    return linuxName(context, selectedLinux(context));
  }

  private static String linuxName(Context context, String selected) {
    if (BUNDLED.equals(selected)) return LinuxRuntime.TURNIP_VERSION;
    if (LEGACY.equals(selected)) {
      String name =
          FileUtils.readString(
              new File(LinuxRuntime.driverDir(context), LinuxRuntime.DRIVER_NAME_FILE));
      return name == null ? LinuxRuntime.TURNIP_VERSION : name.trim();
    }
    try {
      return new JSONObject(
              new String(
                  Files.readAllBytes(
                      new File(linuxDirectory(context), selected + "/meta.json").toPath()),
                  java.nio.charset.StandardCharsets.UTF_8))
          .getString("name");
    } catch (Exception error) {
      return LinuxRuntime.TURNIP_VERSION;
    }
  }

  private static String linuxIdForSelection(Context context, String selection) {
    if (selection.equals(LinuxRuntime.TURNIP_VERSION) || selection.equals(BUNDLED)) return BUNDLED;
    if (selection.equals(LEGACY) || selection.equals(linuxName(context, LEGACY))) return LEGACY;
    for (File driver : linuxDrivers(context)) {
      String id = driver.getName();
      if (selection.equals(id) || selection.equals(linuxName(context, id))) return id;
    }
    return BUNDLED;
  }

  public static synchronized void removeLinux(Context context, String id) {
    if (BUNDLED.equals(id)) return;
    boolean selected = id.equals(selectedLinux(context));
    if (LEGACY.equals(id)) FileUtils.delete(LinuxRuntime.driverDir(context));
    else
      for (File file : linuxDrivers(context)) if (file.getName().equals(id)) FileUtils.delete(file);
    if (selected) selectLinux(context, BUNDLED);
  }

  public static synchronized Installed install(
      Context context, Uri uri, String asset, Platform required) throws Exception {
    File staging = Files.createTempDirectory(context.getCacheDir().toPath(), "driver-").toFile();
    try {
      Set<String> entries = new HashSet<>();
      long total = 0;
      try (InputStream input = context.getContentResolver().openInputStream(uri)) {
        if (input == null) throw new IOException("Cannot read driver");
        try (ZipInputStream zip = new ZipInputStream(input)) {
          ZipEntry entry;
          byte[] buffer = new byte[16384];
          while ((entry = zip.getNextEntry()) != null) {
            String name = entry.getName();
            if (!safeName(name) || entry.isDirectory() || !entries.add(name) || entries.size() > 32)
              throw new IOException("Invalid driver archive");
            File output = new File(staging, name);
            try (java.io.OutputStream stream = Files.newOutputStream(output.toPath())) {
              int count;
              while ((count = zip.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BYTES) throw new IOException("Driver archive too large");
                stream.write(buffer, 0, count);
              }
            }
          }
        }
      }
      File metadata = new File(staging, "meta.json");
      if (!metadata.isFile() || metadata.length() > 65536)
        throw new IOException("Missing driver metadata");
      JSONObject meta =
          new JSONObject(
              new String(
                  Files.readAllBytes(metadata.toPath()), java.nio.charset.StandardCharsets.UTF_8));
      String name = meta.getString("name").trim();
      String library = meta.getString("libraryName");
      if (!safeName(name) || name.startsWith("@") || !safeName(library) || !library.endsWith(".so"))
        throw new IOException("Invalid driver name");
      byte[] libraryBytes = Files.readAllBytes(new File(staging, library).toPath());
      Platform platform = identify(libraryBytes);
      String expectedHash = meta.optString("librarySha256", "");
      if (!expectedHash.isEmpty()) {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(libraryBytes);
        StringBuilder actualHash = new StringBuilder();
        for (byte value : hash)
          actualHash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!expectedHash.equalsIgnoreCase(actualHash.toString()))
          throw new IOException("Driver checksum mismatch");
      }
      if (required != null && platform != required)
        throw new IOException("Driver belongs to " + platform);
      String declared = meta.optString("platform", "");
      if (!declared.isEmpty() && !declared.equalsIgnoreCase(platform.name()))
        throw new IOException("Driver platform mismatch");
      if (platform == Platform.ANDROID && meta.optInt("minApi", 0) > Build.VERSION.SDK_INT)
        throw new IOException("Driver requires newer Android");
      for (String entry : entries) {
        if (entry.endsWith(".so")
            && identify(Files.readAllBytes(new File(staging, entry).toPath())) != platform)
          throw new IOException("Mixed driver platforms");
      }
      meta.put("platform", platform.name().toLowerCase(java.util.Locale.ROOT));
      if (asset != null) meta.put("sourceAsset", asset);
      Files.write(
          metadata.toPath(), meta.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      File root =
          platform == Platform.LINUX
              ? linuxDirectory(context)
              : new File(context.getFilesDir(), "contents/adrenotools");
      if (!root.isDirectory() && !root.mkdirs())
        throw new IOException("Cannot create driver directory");
      File target = new File(root, name);
      if (target.exists()) throw new IOException("Driver already installed");
      if (platform == Platform.LINUX) {
        JSONObject icd =
            new JSONObject()
                .put("file_format_version", "1.0.0")
                .put(
                    "ICD",
                    new JSONObject()
                        .put("api_version", "1.4.0")
                        .put("library_path", new File(target, library).getAbsolutePath()));
        Files.write(
            new File(staging, LinuxRuntime.DRIVER_ICD).toPath(),
            icd.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      if (!staging.renameTo(target)) throw new IOException("Cannot install driver");
      if (platform == Platform.LINUX) selectLinux(context, name);
      else com.winlator.cmod.runtime.system.GraphicsDriverCatalog.invalidate();
      return new Installed(name, platform);
    } finally {
      FileUtils.delete(staging);
    }
  }

  private static boolean safeName(String name) {
    return !name.isEmpty()
        && !name.startsWith(".")
        && !name.contains("/")
        && !name.contains("\\")
        && name.length() <= 180
        && name.chars().noneMatch(character -> character < 32);
  }

  public static Platform identify(byte[] elf) throws IOException {
    try {
      ByteBuffer data = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN);
      if (elf.length < 64
          || data.getInt(0) != 0x464c457f
          || elf[4] != 2
          || elf[5] != 1
          || data.getShort(16) != 3
          || data.getShort(18) != 183) throw new IOException("Expected ARM64 shared library");
      long headers = data.getLong(32);
      int headerSize = Short.toUnsignedInt(data.getShort(54));
      int count = Short.toUnsignedInt(data.getShort(56));
      if (headerSize < 56 || count > 4096) throw new IOException("Invalid ELF headers");
      List<long[]> loads = new ArrayList<>();
      long dynamicOffset = -1, dynamicSize = 0;
      for (int i = 0; i < count; i++) {
        int offset = checked(headers + (long) i * headerSize, 56, elf.length);
        int type = data.getInt(offset);
        long fileOffset = data.getLong(offset + 8),
            virtualAddress = data.getLong(offset + 16),
            size = data.getLong(offset + 32);
        checked(fileOffset, size, elf.length);
        if (type == 1) loads.add(new long[] {virtualAddress, fileOffset, size});
        if (type == 2) {
          dynamicOffset = fileOffset;
          dynamicSize = size;
        }
      }
      if (dynamicOffset < 0) throw new IOException("Missing ELF dependencies");
      long strings = -1;
      List<Long> needed = new ArrayList<>();
      for (long offset = dynamicOffset; offset + 16 <= dynamicOffset + dynamicSize; offset += 16) {
        long tag = data.getLong((int) offset), value = data.getLong((int) offset + 8);
        if (tag == 0) break;
        if (tag == 5) strings = value;
        if (tag == 1) needed.add(value);
      }
      long stringOffset = -1;
      for (long[] load : loads)
        if (strings >= load[0] && strings - load[0] < load[2])
          stringOffset = load[1] + strings - load[0];
      if (stringOffset < 0) throw new IOException("Missing ELF string table");
      boolean android = false, linux = false;
      for (long index : needed) {
        int start = checked(stringOffset + index, 1, elf.length), end = start;
        while (end < elf.length && elf[end] != 0 && end - start < 256) end++;
        if (end == elf.length || elf[end] != 0) throw new IOException("Invalid ELF dependency");
        String dependency =
            new String(elf, start, end - start, java.nio.charset.StandardCharsets.UTF_8);
        android |= dependency.equals("libc.so");
        linux |= dependency.equals("libc.so.6");
      }
      if (android == linux) throw new IOException("Cannot identify driver ABI");
      return linux ? Platform.LINUX : Platform.ANDROID;
    } catch (IndexOutOfBoundsException | ArithmeticException error) {
      throw new IOException("Invalid ELF", error);
    }
  }

  private static int checked(long offset, long length, int size) throws IOException {
    if (offset < 0 || length < 0 || offset > size || length > size - offset)
      throw new IOException("Invalid ELF bounds");
    return (int) offset;
  }
}
