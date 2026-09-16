package com.winlator.cmod.runtime.content;

/* Components data layer: loads, merges, installs, and removes local and remote content packages. */

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import com.winlator.cmod.shared.io.FileUtils;
import com.winlator.cmod.shared.io.TarCompressorUtils;
import com.winlator.cmod.shared.util.OnExtractFileListener;
import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ContentsManager {
  public static final String PROFILE_NAME = "profile.json";
  public static final String REMOTE_PROFILES =
      "https://raw.githubusercontent.com/nicholasx417/WinNative-Components/refs/heads/main/contents.json";
  private static final long EXTRACTION_PROGRESS_INTERVAL_MS = 120L;
  private static final String REMOTE_ALIAS_PREFIX = "remote_profile_alias_";
  public static final String[] DXVK_TRUST_FILES = {
    "${system32}/d3d8.dll",
    "${system32}/d3d9.dll",
    "${system32}/d3d10.dll",
    "${system32}/d3d10_1.dll",
    "${system32}/d3d10core.dll",
    "${system32}/d3d11.dll",
    "${system32}/dxgi.dll",
    "${syswow64}/d3d8.dll",
    "${syswow64}/d3d9.dll",
    "${syswow64}/d3d10.dll",
    "${syswow64}/d3d10_1.dll",
    "${syswow64}/d3d10core.dll",
    "${syswow64}/d3d11.dll",
    "${syswow64}/dxgi.dll"
  };
  public static final String[] VKD3D_TRUST_FILES = {
    "${system32}/d3d12core.dll",
    "${system32}/d3d12.dll",
    "${syswow64}/d3d12core.dll",
    "${syswow64}/d3d12.dll"
  };
  public static final String[] BOX64_TRUST_FILES = {"${bindir}/box64"};
  public static final String[] WOWBOX64_TRUST_FILES = {"${system32}/wowbox64.dll"};
  public static final String[] FEXCORE_TRUST_FILES = {
    "${system32}/libwow64fex.dll", "${system32}/libarm64ecfex.dll"
  };
  private Map<String, String> dirTemplateMap;
  private Map<ContentProfile.ContentType, List<String>> trustedFilesMap;

  private SharedPreferences preferences;

  public enum InstallFailedReason {
    ERROR_NOSPACE,
    ERROR_BADTAR,
    ERROR_NOPROFILE,
    ERROR_BADPROFILE,
    ERROR_MISSINGFILES,
    ERROR_EXIST,
    ERROR_UNTRUSTPROFILE,
    ERROR_UNKNOWN
  }

  public enum ContentDirName {
    CONTENT_MAIN_DIR_NAME("contents"),
    CONTENT_WINE_DIR_NAME("wine"),
    CONTENT_DXVK_DIR_NAME("dxvk"),
    CONTENT_VKD3D_DIR_NAME("vkd3d"),
    CONTENT_BOX64_DIR_NAME("box64");

    private String name;

    ContentDirName(String name) {
      this.name = name;
    }

    @NonNull @Override
    public String toString() {
      return name;
    }
  }

  private final Context context;

  private HashMap<ContentProfile.ContentType, List<ContentProfile>> profilesMap;

  private ArrayList<ContentProfile> remoteProfiles;

  public ContentsManager(Context context) {
    this.context = context;
    this.preferences = context.getSharedPreferences("contents_manager_prefs", Context.MODE_PRIVATE);
  }

  // Method to mark the graphics driver as installed
  public void setGraphicsDriverInstalled(String driverVersion, boolean installed) {
    preferences.edit().putBoolean("graphics_driver_installed_" + driverVersion, installed).apply();
  }

  public interface OnInstallFinishedCallback {
    void onFailed(InstallFailedReason reason, Exception e);

    void onSucceed(ContentProfile profile);
  }

  /**
   * Listener for extraction progress updates. Called on the background thread during extraction.
   */
  public interface OnExtractionProgressListener {
    void onProgress(int filesExtracted, String currentFileName);

    default boolean prefersByteProgress() {
      return false;
    }

    default void onByteProgress(long bytesExtracted) {}
  }

  public void setRemoteProfiles(String json) {
    try {
      remoteProfiles = new ArrayList<>();
      JSONArray content = new JSONArray(json);
      for (int i = 0; i < content.length(); i++) {
        try {
          JSONObject object = content.getJSONObject(i);
          ContentProfile remoteProfile = new ContentProfile();
          remoteProfile.remoteUrl = object.getString("remoteUrl");
          remoteProfile.type = ContentProfile.ContentType.getTypeByName(object.getString("type"));
          remoteProfile.verName = object.getString("verName");
          remoteProfile.verCode = object.getInt("verCode");
          remoteProfile.isOfficial =
              parseOfficialFlag(object.opt(ContentProfile.MARK_OFFICIAL));
          remoteProfiles.add(remoteProfile);
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
    } catch (JSONException e) {
      e.printStackTrace();
    }
    syncContents();
  }

  /**
   * Interprets the optional "official" marker. Accepts a string ("1"/"true"/"yes"), a number
   * (non-zero), or a boolean so the contents.json author can use whichever form is convenient.
   */
  private static boolean parseOfficialFlag(Object value) {
    if (value == null) return false;
    if (value instanceof Boolean) return (Boolean) value;
    if (value instanceof Number) return ((Number) value).intValue() != 0;
    String s = value.toString().trim();
    return s.equals("1") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes");
  }

  public void syncContents() {
    profilesMap = new HashMap<>();

    // Ensure all content types are initialized in the profilesMap
    for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
      profilesMap.put(type, new LinkedList<>());
    }

    for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
      Map<String, ContentProfile> mergedProfiles = new LinkedHashMap<>();

      // Load local profiles
      File typeFile = getContentTypeDir(context, type);
      File[] fileList = typeFile.listFiles();
      if (fileList != null) {
        for (File file : fileList) {
          if (!file.isDirectory()) {
            continue;
          }

          ContentProfile profile = null;
          File proFile = new File(file, PROFILE_NAME);
          if (proFile.exists() && proFile.isFile()) {
            profile = readProfile(proFile);
            if (profile == null) {
              Log.w("ContentsManager", "Invalid local profile at: " + proFile.getAbsolutePath());
            }
          }

          if (profile == null) {
            profile = createInstalledFallbackProfile(type, file);
          }

          if (profile != null) {
            profile.isInstalled = true;
            mergedProfiles.put(getProfileKey(profile), profile);
            Log.d("ContentsManager", "Local profile loaded: " + profile.verName);
          }
        }
      }

      // Add remote profiles for this type
      if (remoteProfiles != null) {
        for (ContentProfile remote : remoteProfiles) {
          if (remote.type == type) {
            remote.isInstalled = isInstalled(context, remote);

            String aliasProfileKey = getRemoteProfileAlias(remote.remoteUrl);
            if (aliasProfileKey != null) {
              ContentProfile aliasedProfile = mergedProfiles.get(aliasProfileKey);
              if (aliasedProfile != null && aliasedProfile.isInstalled) {
                mergeRemoteMetadata(aliasedProfile, remote);
                continue;
              }
            }

            String profileKey = getProfileKey(remote);
            ContentProfile existingProfile = mergedProfiles.get(profileKey);
            if (existingProfile == null) {
              mergedProfiles.put(profileKey, remote);
              Log.d("ContentsManager", "Remote profile added: " + remote.verName);
            } else {
              mergeRemoteMetadata(existingProfile, remote);
            }
          }
        }
      }

      profilesMap.put(type, new LinkedList<>(mergedProfiles.values()));
    }
  }

  public void extraContentFile(Uri uri, OnInstallFinishedCallback callback) {
    extraContentFile(uri, callback, null);
  }

  public void extraContentFile(
      Uri uri, OnInstallFinishedCallback callback, OnExtractionProgressListener progressListener) {
    cleanTmpDir(context);

    File file = getTmpDir(context);

    final int[] fileCount = {0};
    final long[] extractedBytes = {0L};
    final long[] lastProgressUpdateMs = {0L};
    OnExtractFileListener extractListener =
        createExtractionProgressListener(
            progressListener, fileCount, extractedBytes, lastProgressUpdateMs);

    boolean ret;
    TarCompressorUtils.Type primaryType = detectCompressionType(context, uri);
    TarCompressorUtils.Type fallbackType =
        (primaryType == TarCompressorUtils.Type.ZSTD)
            ? TarCompressorUtils.Type.XZ
            : TarCompressorUtils.Type.ZSTD;

    ret = TarCompressorUtils.extract(primaryType, context, uri, file, extractListener);
    if (!ret) {
      fileCount[0] = 0;
      extractedBytes[0] = 0L;
      lastProgressUpdateMs[0] = 0L;
      ret = TarCompressorUtils.extract(fallbackType, context, uri, file, extractListener);
    }
    if (!ret) {
      callback.onFailed(InstallFailedReason.ERROR_BADTAR, null);
      return;
    }

    File proFile = new File(file, PROFILE_NAME);
    if (!proFile.exists()) {
      callback.onFailed(InstallFailedReason.ERROR_NOPROFILE, null);
      return;
    }

    ContentProfile profile = readProfile(proFile);
    if (profile == null) {
      callback.onFailed(InstallFailedReason.ERROR_BADPROFILE, null);
      return;
    }

    String imagefsPath = context.getFilesDir().getAbsolutePath() + "/imagefs";
    for (ContentProfile.ContentFile contentFile : profile.fileList) {
      File tmpFile = new File(file, contentFile.source);
      if (!tmpFile.exists()
          || !tmpFile.isFile()
          || !isSubPath(file.getAbsolutePath(), tmpFile.getAbsolutePath())) {
        callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
        return;
      }

      String realPath = getPathFromTemplate(contentFile.target);
      if (!isSubPath(imagefsPath, realPath)
          || isSubPath(ContentsManager.getContentDir(context).getAbsolutePath(), realPath)
          || realPath.contains("dosdevices")) {
        callback.onFailed(InstallFailedReason.ERROR_UNTRUSTPROFILE, null);
        return;
      }
    }

    if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
        || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
      File bin = new File(file, profile.wineBinPath);
      File lib = new File(file, profile.wineLibPath);
      File cp = new File(file, profile.winePrefixPack);

      if (!bin.exists()
          || !bin.isDirectory()
          || !lib.exists()
          || !lib.isDirectory()
          || !cp.exists()
          || !cp.isFile()) {
        callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
        return;
      }
    }

    callback.onSucceed(profile);
  }

  private static OnExtractFileListener createExtractionProgressListener(
      OnExtractionProgressListener progressListener,
      int[] fileCount,
      long[] extractedBytes,
      long[] lastProgressUpdateMs) {
    if (progressListener == null) return null;

    if (progressListener.prefersByteProgress()) {
      return new OnExtractFileListener() {
        @Override
        public File onExtractFile(File destination, long size) {
          return destination;
        }

        @Override
        public boolean mapsExtractedFiles() {
          return false;
        }

        @Override
        public boolean reportsExtractedBytesOnly() {
          return true;
        }

        @Override
        public void onExtractedBytes(long size) {
          if (size <= 0) return;
          extractedBytes[0] += size;
          progressListener.onByteProgress(extractedBytes[0]);
        }
      };
    }

    return (destination, size) -> {
      fileCount[0]++;
      long now = System.currentTimeMillis();
      if (fileCount[0] <= 3 || now - lastProgressUpdateMs[0] >= EXTRACTION_PROGRESS_INTERVAL_MS) {
        progressListener.onProgress(fileCount[0], destination.getName());
        lastProgressUpdateMs[0] = now;
      }
      return destination;
    };
  }

  public void finishInstallContent(ContentProfile profile, OnInstallFinishedCallback callback) {
    File installPath = getInstallDir(context, profile);
    if (installPath.exists()) {
      callback.onFailed(InstallFailedReason.ERROR_EXIST, null);
      return;
    }

    if (!installPath.mkdirs()) {
      callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
      return;
    }

    if (!getTmpDir(context).renameTo(installPath)) {
      callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
    }

    repairInstalledContentPermissions(profile);
    com.winlator.cmod.runtime.display.wayland.WineWaylandSupport.invalidate();
    callback.onSucceed(profile);
  }

  public void repairInstalledContentPermissions(ContentProfile profile) {
    repairInstalledContentPermissions(context, profile);
  }

  public static void repairInstalledContentPermissions(Context context, ContentProfile profile) {
    if (context == null || profile == null) return;

    File installDir = getInstallDir(context, profile);
    if (!installDir.isDirectory()) return;

    switch (profile.type) {
      case CONTENT_TYPE_WINE:
      case CONTENT_TYPE_PROTON:
        repairWineRuntimePermissions(installDir, profile);
        break;
      case CONTENT_TYPE_BOX64:
        chmodIfRegularFile(new File(installDir, "box64"), 0755);
        break;
      default:
        break;
    }
  }

  public static void repairWineRuntimePermissions(File installDir, ContentProfile profile) {
    if (installDir == null || profile == null) return;

    File binDir = new File(installDir, profile.wineBinPath);
    if (!binDir.isDirectory()) return;

    File[] binaries = binDir.listFiles();
    if (binaries == null) return;

    for (File file : binaries) {
      chmodIfRegularFile(file, 0755);
    }
  }

  private static void chmodIfRegularFile(File file, int mode) {
    if (file == null || !file.isFile()) return;
    FileUtils.chmod(file, mode);
  }

  public ContentProfile readProfile(File file) {
    try {
      ContentProfile profile = new ContentProfile();
      String profileStr = FileUtils.readString(file);
      JSONObject profileJSONObject = new JSONObject(profileStr != null ? profileStr : "{}");
      String typeName = profileJSONObject.getString(ContentProfile.MARK_TYPE);
      String verName = profileJSONObject.getString(ContentProfile.MARK_VERSION_NAME);
      int verCode = profileJSONObject.getInt(ContentProfile.MARK_VERSION_CODE);
      String desc = profileJSONObject.getString(ContentProfile.MARK_DESC);

      JSONArray fileJSONArray = profileJSONObject.getJSONArray(ContentProfile.MARK_FILE_LIST);
      List<ContentProfile.ContentFile> fileList = new ArrayList<>();
      for (int i = 0; i < fileJSONArray.length(); i++) {
        JSONObject contentFileJSONObject = fileJSONArray.getJSONObject(i);
        ContentProfile.ContentFile contentFile = new ContentProfile.ContentFile();
        contentFile.source = contentFileJSONObject.getString(ContentProfile.MARK_FILE_SOURCE);
        contentFile.target = contentFileJSONObject.getString(ContentProfile.MARK_FILE_TARGET);
        fileList.add(contentFile);
      }
      if (typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_WINE.toString())
          || typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString())) {
        JSONObject wineJSONObject = profileJSONObject.getJSONObject(ContentProfile.MARK_WINE);
        profile.wineLibPath = wineJSONObject.getString(ContentProfile.MARK_WINE_LIBPATH);
        profile.wineBinPath = wineJSONObject.getString(ContentProfile.MARK_WINE_BINPATH);
        profile.winePrefixPack = wineJSONObject.getString(ContentProfile.MARK_WINE_PREFIX_PACK);
      }

      profile.type = ContentProfile.ContentType.getTypeByName(typeName);
      profile.verName = verName;
      profile.verCode = verCode;
      profile.desc = desc;
      profile.fileList = fileList;
      profile.isOfficial = parseOfficialFlag(profileJSONObject.opt(ContentProfile.MARK_OFFICIAL));
      profile.isInstalled = isInstalled(context, profile);
      return profile;
    } catch (Exception e) {
      return null;
    }
  }

  public List<ContentProfile> getProfiles(ContentProfile.ContentType type) {
    if (profilesMap != null) return profilesMap.get(type);
    return null;
  }

  public static File getInstallDir(Context context, ContentProfile profile) {
    return new File(
        getContentTypeDir(context, profile.type), profile.verName + "-" + profile.verCode);
  }

  public static boolean isInstalled(Context context, ContentProfile profile) {
    return profile != null
        && profile.type != null
        && profile.verName != null
        && getInstallDir(context, profile).exists();
  }

  public static boolean hasInstalledRuntimes(Context context) {
    ContentsManager contentsManager = new ContentsManager(context);
    contentsManager.syncContents();
    List<ContentProfile> wineProfiles =
        contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE);
    if (wineProfiles != null) {
      for (ContentProfile profile : wineProfiles) {
        if (profile.isInstalled) {
          Log.d("ContentsManager", "Installed runtime found: " + profile.verName);
          return true;
        }
      }
    }
    List<ContentProfile> protonProfiles =
        contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON);
    if (protonProfiles != null) {
      for (ContentProfile profile : protonProfiles) {
        if (profile.isInstalled) {
          Log.d("ContentsManager", "Installed runtime found: " + profile.verName);
          return true;
        }
      }
    }
    int wineCount = wineProfiles != null ? wineProfiles.size() : 0;
    int protonCount = protonProfiles != null ? protonProfiles.size() : 0;
    Log.d(
        "ContentsManager",
        "No installed runtimes found (wine profiles: "
            + wineCount
            + ", proton profiles: "
            + protonCount
            + ")");
    return false;
  }

  private static TarCompressorUtils.Type detectCompressionType(Context context, Uri uri) {
    try {
      byte[] header = new byte[6];
      int read;
      if (uri.toString().startsWith("/") || "file".equalsIgnoreCase(uri.getScheme())) {
        String path = uri.getPath();
        if (path == null) path = uri.toString();
        try (java.io.FileInputStream fis = new java.io.FileInputStream(path)) {
          read = fis.read(header);
        }
      } else {
        try (java.io.InputStream is = context.getContentResolver().openInputStream(uri)) {
          if (is == null) return TarCompressorUtils.Type.XZ;
          read = is.read(header);
        }
      }
      if (read >= 6
          && header[0] == (byte) 0xFD
          && header[1] == '7'
          && header[2] == 'z'
          && header[3] == 'X'
          && header[4] == 'Z'
          && header[5] == 0x00) {
        return TarCompressorUtils.Type.XZ;
      }
      if (read >= 4
          && (header[0] & 0xFF) == 0x28
          && (header[1] & 0xFF) == 0xB5
          && (header[2] & 0xFF) == 0x2F
          && (header[3] & 0xFF) == 0xFD) {
        return TarCompressorUtils.Type.ZSTD;
      }
    } catch (Exception ignored) {
    }
    return TarCompressorUtils.Type.XZ;
  }

  public static File getContentDir(Context context) {
    return new File(context.getFilesDir(), ContentDirName.CONTENT_MAIN_DIR_NAME.toString());
  }

  public static File getContentTypeDir(Context context, ContentProfile.ContentType type) {
    return new File(getContentDir(context), type.toString());
  }

  public static File getTmpDir(Context context) {
    return new File(context.getFilesDir(), "tmp/" + ContentDirName.CONTENT_MAIN_DIR_NAME);
  }

  public static File getSourceFile(Context context, ContentProfile profile, String path) {
    return new File(getInstallDir(context, profile), path);
  }

  public static void cleanTmpDir(Context context) {
    File file = getTmpDir(context);
    FileUtils.delete(file);
    file.mkdirs();
  }

  public List<ContentProfile.ContentFile> getUnTrustedContentFiles(ContentProfile profile) {
    createTrustedFilesMap();
    List<ContentProfile.ContentFile> files = new ArrayList<>();
    for (ContentProfile.ContentFile contentFile : profile.fileList) {
      if (!trustedFilesMap
          .get(profile.type)
          .contains(
              Paths.get(getPathFromTemplate(contentFile.target))
                  .toAbsolutePath()
                  .normalize()
                  .toString())) files.add(contentFile);
    }
    return files;
  }

  private boolean isSubPath(String parent, String child) {
    return Paths.get(child)
        .toAbsolutePath()
        .normalize()
        .startsWith(Paths.get(parent).toAbsolutePath().normalize());
  }

  private void createDirTemplateMap() {
    if (dirTemplateMap == null) {
      dirTemplateMap = new HashMap<>();
      String imagefsPath = context.getFilesDir().getAbsolutePath() + "/imagefs";
      String drivecPath = imagefsPath + "/home/xuser/.wine/drive_c";
      dirTemplateMap.put("${libdir}", imagefsPath + "/usr/lib");
      dirTemplateMap.put("${system32}", drivecPath + "/windows/system32");
      dirTemplateMap.put("${syswow64}", drivecPath + "/windows/syswow64");
      dirTemplateMap.put("${bindir}", imagefsPath + "/usr/bin");
      dirTemplateMap.put("${sharedir}", imagefsPath + "/usr/share");
    }
  }

  private void createTrustedFilesMap() {
    if (trustedFilesMap == null) {
      trustedFilesMap = new HashMap<>();
      for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
        List<String> pathList = new ArrayList<>();
        trustedFilesMap.put(type, pathList);

        String[] paths =
            switch (type) {
              case CONTENT_TYPE_DXVK -> DXVK_TRUST_FILES;
              case CONTENT_TYPE_VKD3D -> VKD3D_TRUST_FILES;
              case CONTENT_TYPE_BOX64 -> BOX64_TRUST_FILES;
              case CONTENT_TYPE_WOWBOX64 -> WOWBOX64_TRUST_FILES;
              case CONTENT_TYPE_FEXCORE -> FEXCORE_TRUST_FILES;
              default -> new String[0];
            };
        for (String path : paths)
          pathList.add(
              Paths.get(getPathFromTemplate(path)).toAbsolutePath().normalize().toString());
      }
    }
  }

  private String getPathFromTemplate(String path) {
    createDirTemplateMap();
    String realPath = path;
    for (String key : dirTemplateMap.keySet()) {
      realPath = realPath.replace(key, dirTemplateMap.get(key));
    }
    return realPath;
  }

  public void removeContent(ContentProfile profile) {
    FileUtils.delete(getInstallDir(context, profile));
    forgetRemoteProfileAliases(profile);
    syncContents();
    com.winlator.cmod.runtime.display.wayland.WineWaylandSupport.invalidate();
  }

  private void forgetRemoteProfileAliases(ContentProfile profile) {
    String profileKey = getProfileKey(profile);
    SharedPreferences.Editor editor = preferences.edit();
    for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
      if (entry.getKey().startsWith(REMOTE_ALIAS_PREFIX)
          && profileKey.equals(entry.getValue())) {
        editor.remove(entry.getKey());
      }
    }
    editor.apply();
  }

  public static String getEntryName(ContentProfile profile) {
    return profile.type.toString() + '-' + profile.verName + '-' + profile.verCode;
  }

  public void registerRemoteProfileAlias(String remoteUrl, ContentProfile profile) {
    if (remoteUrl == null || remoteUrl.isEmpty() || profile == null) {
      return;
    }
    preferences
        .edit()
        .putString(getRemoteAliasPreferenceKey(remoteUrl), getProfileKey(profile))
        .apply();
  }

  private static String getProfileKey(ContentProfile profile) {
    return getProfileKey(profile.type, profile.verName, profile.verCode);
  }

  private static String getProfileKey(
      ContentProfile.ContentType type, String verName, int verCode) {
    return type + "|" + verName + "|" + verCode;
  }

  private void mergeRemoteMetadata(ContentProfile localProfile, ContentProfile remoteProfile) {
    if (localProfile.remoteUrl == null) {
      localProfile.remoteUrl = remoteProfile.remoteUrl;
    }
    if (remoteProfile.isOfficial) {
      localProfile.isOfficial = true;
    }
    if (remoteProfile.isInstalled) {
      localProfile.isInstalled = true;
    }
  }

  public boolean isRemoteUrlInstalled(String remoteUrl) {
    String profileKey = getRemoteProfileAlias(remoteUrl);
    if (profileKey == null) return false;
    if (isProfileKeyInstalled(profileKey)) return true;
    preferences.edit().remove(getRemoteAliasPreferenceKey(remoteUrl)).apply();
    return false;
  }

  private boolean isProfileKeyInstalled(String profileKey) {
    int firstSeparator = profileKey.indexOf('|');
    int lastSeparator = profileKey.lastIndexOf('|');
    if (firstSeparator < 0 || firstSeparator == lastSeparator) return false;

    ContentProfile.ContentType type =
        ContentProfile.ContentType.getTypeByName(profileKey.substring(0, firstSeparator));
    if (type == null) return false;

    String verName = profileKey.substring(firstSeparator + 1, lastSeparator);
    String verCode = profileKey.substring(lastSeparator + 1);
    return new File(getContentTypeDir(context, type), verName + "-" + verCode).exists();
  }

  private String getRemoteProfileAlias(String remoteUrl) {
    if (remoteUrl == null || remoteUrl.isEmpty()) {
      return null;
    }
    return preferences.getString(getRemoteAliasPreferenceKey(remoteUrl), null);
  }

  private String getRemoteAliasPreferenceKey(String remoteUrl) {
    return REMOTE_ALIAS_PREFIX + remoteUrl;
  }

  private ContentProfile createInstalledFallbackProfile(
      ContentProfile.ContentType type, File installDir) {
    String directoryName = installDir.getName();
    int splitIndex = directoryName.lastIndexOf('-');
    if (splitIndex <= 0 || splitIndex >= directoryName.length() - 1) {
      Log.w(
          "ContentsManager",
          "Skipping unreadable installed content directory: " + installDir.getAbsolutePath());
      return null;
    }

    try {
      ContentProfile profile = new ContentProfile();
      profile.type = type;
      profile.verName = directoryName.substring(0, splitIndex);
      profile.verCode = Integer.parseInt(directoryName.substring(splitIndex + 1));
      profile.isInstalled = true;
      return profile;
    } catch (NumberFormatException e) {
      Log.w(
          "ContentsManager",
          "Skipping unreadable installed content directory: " + installDir.getAbsolutePath(),
          e);
      return null;
    }
  }

  public ContentProfile getProfileByEntryName(String entryName) {
    if (entryName == null) return null;
    int firstDashIndex = entryName.indexOf('-');
    int lastDashIndex = entryName.lastIndexOf('-');

    if (firstDashIndex == -1 || firstDashIndex == lastDashIndex) return null;

    try {
      String typeName = entryName.substring(0, firstDashIndex);
      String versionName = entryName.substring(firstDashIndex + 1, lastDashIndex);
      String versionCode = entryName.substring(lastDashIndex + 1);

      List<ContentProfile> profiles =
          profilesMap.get(ContentProfile.ContentType.getTypeByName(typeName));
      if (profiles != null) {
        for (ContentProfile profile : profiles) {
          if (versionName.equals(profile.verName)
              && Integer.parseInt(versionCode) == profile.verCode) return profile;
        }
      }
    } catch (Exception e) {
    }

    return null;
  }

  public boolean profileHasUnixLibs(ContentProfile profile) {
    if (profile == null) return false;
    return dirContainsSharedObject(getInstallDir(context, profile));
  }

  public boolean fexcoreVersionHasUnixLibs(String fexcoreVersion) {
    if (fexcoreVersion == null || fexcoreVersion.isEmpty()) return false;
    return profileHasUnixLibs(getProfileByEntryName("fexcore-" + fexcoreVersion));
  }

  private static boolean dirContainsSharedObject(File dir) {
    if (dir == null) return false;
    File[] files = dir.listFiles();
    if (files == null) return false;
    for (File file : files) {
      if (file.isDirectory()) {
        if (dirContainsSharedObject(file)) return true;
      } else if (isSharedObject(file.getName())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSharedObject(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.endsWith(".so") || lower.contains(".so.");
  }

  public void removeAppliedUnixLibs(ContentProfile profile) {
    if (profile == null || profile.fileList == null) return;
    for (ContentProfile.ContentFile contentFile : profile.fileList) {
      if (!isSharedObject(new File(contentFile.target).getName())) continue;
      File targetFile = new File(getPathFromTemplate(contentFile.target));
      if (targetFile.exists() && targetFile.delete()) {
        Log.i("ContentsManager", "UnixLibs: removed " + targetFile.getName());
      }
    }
  }

  public void copyUnixLibsToDir(ContentProfile profile, File destDir) {
    if (profile == null || profile.fileList == null) return;
    for (ContentProfile.ContentFile contentFile : profile.fileList) {
      String name = new File(contentFile.target).getName();
      if (!isSharedObject(name)) continue;
      File sourceFile = new File(getInstallDir(context, profile), contentFile.source);
      if (!sourceFile.exists()) continue;
      File destFile = new File(destDir, name);
      FileUtils.copy(sourceFile, destFile);
      FileUtils.chmod(destFile, 0771);
    }
  }

  public void deleteUnixLibsFromDir(ContentProfile profile, File destDir) {
    if (profile == null || profile.fileList == null) return;
    for (ContentProfile.ContentFile contentFile : profile.fileList) {
      String name = new File(contentFile.target).getName();
      if (!isSharedObject(name)) continue;
      File destFile = new File(destDir, name);
      if (destFile.exists()) destFile.delete();
    }
  }

  public boolean applyContent(ContentProfile profile) {
    if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_WINE
        && profile.type != ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
      for (ContentProfile.ContentFile contentFile : profile.fileList) {
        File targetFile = new File(getPathFromTemplate(contentFile.target));
        File sourceFile = new File(getInstallDir(context, profile), contentFile.source);

        targetFile.delete();
        FileUtils.copy(sourceFile, targetFile);

        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_BOX64
            || isSharedObject(targetFile.getName())) {
          FileUtils.chmod(targetFile, 0771);
        }
      }
    } else {
      // TODO: do nothing?
    }
    return true;
  }
}
