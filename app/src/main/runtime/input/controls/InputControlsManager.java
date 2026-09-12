package com.winlator.cmod.runtime.input.controls;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.util.JsonReader;
import android.util.Log;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.app.config.DeviceProfileSettings;
import com.winlator.cmod.app.config.SettingsConfig;
import com.winlator.cmod.shared.android.AppUtils;
import com.winlator.cmod.shared.io.FileUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

public class InputControlsManager {
  private static final int ASSET_PROFILE_SYNC_REVISION = 13;
  private static final String ASSET_PROFILES_DIR = "inputcontrols/profiles";
  public static final int LAST_BUILTIN_PROFILE_ID = 8;
  public static final int VIRTUAL_GAMEPAD_BUILTIN_ID = 3;
  public static final int GAMEHUB_LAYOUT_BUILTIN_ID = 7;
  // Retired bundled layout ids whose installed copies are removed on sync.
  private static final int[] RETIRED_PROFILE_IDS = {4, 5};
  // Bundled layouts replaced in this revision: reinstall pristine copies once.
  private static final int[] REFRESHED_PROFILE_IDS = {8};

  private final Context context;
  private ArrayList<ControlsProfile> profiles;
  private int maxProfileId;
  private boolean profilesLoaded = false;

  public static boolean isBuiltinProfile(ControlsProfile profile) {
    return profile != null && profile.id <= LAST_BUILTIN_PROFILE_ID;
  }

  public InputControlsManager(Context context) {
    this.context = context;
  }

  public static File getProfilesDir(Context context) {
    File profilesDir = new File(context.getFilesDir(), "profiles");
    if (!profilesDir.isDirectory()) profilesDir.mkdir();
    return profilesDir;
  }

  public static File getBackupsDir(Context context) {
    File backupsDir = new File(context.getFilesDir(), "profile_backups");
    if (!backupsDir.isDirectory()) backupsDir.mkdir();
    return backupsDir;
  }

  public static File getBackupFile(Context context, int id) {
    return new File(getBackupsDir(context), "controls-" + id + ".icp");
  }

  public static void backupProfile(Context context, int id) {
    File working = ControlsProfile.getProfileFile(context, id);
    if (working.isFile()) FileUtils.copy(working, getBackupFile(context, id));
  }

  public boolean canResetProfile(ControlsProfile profile) {
    return profile != null && getBackupFile(context, profile.id).isFile();
  }

  /**
   * Restores {@code profile} to its pristine snapshot (the layout it had when first installed,
   * downloaded or imported) and returns the reloaded profile. Returns the unchanged profile if no
   * snapshot exists. Built-in profiles are editable in place — this is how a user undoes edits.
   */
  public ControlsProfile resetProfile(ControlsProfile profile) {
    if (profile == null) return null;
    File backup = getBackupFile(context, profile.id);
    if (!backup.isFile()) return profile;
    File working = ControlsProfile.getProfileFile(context, profile.id);
    FileUtils.copy(backup, working);
    ControlsProfile refreshed = loadProfile(context, working);
    if (refreshed == null) return profile;
    if (profiles != null) {
      int index = profiles.indexOf(profile);
      if (index != -1) profiles.set(index, refreshed);
    }
    return refreshed;
  }

  public ArrayList<ControlsProfile> getProfiles() {
    return getProfiles(false);
  }

  public ArrayList<ControlsProfile> getProfiles(boolean ignoreTemplates) {
    if (!profilesLoaded) loadProfiles(false);
    if (ignoreTemplates) {
      ArrayList<ControlsProfile> filteredProfiles = new ArrayList<>();
      for (ControlsProfile profile : profiles)
        if (!profile.isTemplate()) filteredProfiles.add(profile);
      return filteredProfiles;
    }
    return profiles;
  }

  private static String assetProfilesDir(String deviceToken) {
    if (deviceToken == null || deviceToken.isEmpty()) return ASSET_PROFILES_DIR;
    return ASSET_PROFILES_DIR + "-" + deviceToken;
  }

  private static String[] listAssetProfiles(AssetManager assetManager, String dir) {
    String[] names;
    try {
      names = assetManager.list(dir);
    } catch (IOException e) {
      return null;
    }
    if (names == null) return null;
    ArrayList<String> icps = new ArrayList<>();
    for (String name : names) if (name.toLowerCase(Locale.ROOT).endsWith(".icp")) icps.add(name);
    return icps.isEmpty() ? null : icps.toArray(new String[0]);
  }

  private static boolean isPristine(File workingFile, File backupFile) {
    if (!workingFile.isFile()) return true;
    if (!backupFile.isFile()) return false;
    return FileUtils.contentEquals(workingFile, backupFile);
  }

  public void resyncAssetProfiles() {
    profilesLoaded = false;
    loadProfiles(false);
  }

  private void copyAssetProfilesIfNeeded() {
    InputControlsManager.getProfilesDir(context);

    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
    int newVersion = AppUtils.getVersionCode(context);
    int oldVersion = preferences.getInt("inputcontrols_app_version", 0);
    int oldSyncRevision = preferences.getInt("inputcontrols_asset_sync_revision", 0);
    String deviceToken = DeviceProfileSettings.assetProfilesToken(context);
    String oldDeviceToken = preferences.getString("inputcontrols_asset_device_profile", null);
    boolean deviceChanged = !deviceToken.equals(oldDeviceToken);
    if (oldVersion == newVersion
        && oldSyncRevision >= ASSET_PROFILE_SYNC_REVISION
        && !deviceChanged) return;

    for (int id : RETIRED_PROFILE_IDS) {
      ControlsProfile.getProfileFile(context, id).delete();
      getBackupFile(context, id).delete();
    }
    if (oldVersion != newVersion || oldSyncRevision < ASSET_PROFILE_SYNC_REVISION) {
      for (int id : REFRESHED_PROFILE_IDS) {
        ControlsProfile.getProfileFile(context, id).delete();
      }
    }

    AssetManager assetManager = context.getAssets();
    String assetDir = assetProfilesDir(deviceToken);
    String[] assetFiles = listAssetProfiles(assetManager, assetDir);
    if (assetFiles == null && !assetDir.equals(ASSET_PROFILES_DIR)) {
      assetDir = ASSET_PROFILES_DIR;
      assetFiles = listAssetProfiles(assetManager, assetDir);
    }
    if (assetFiles == null) return;

    for (String assetFile : assetFiles) {
      String assetPath = assetDir + "/" + assetFile;
      ControlsProfile originProfile;
      try (InputStream inStream = assetManager.open(assetPath)) {
        originProfile = loadProfile(context, inStream);
      } catch (IOException e) {
        continue;
      }
      if (originProfile == null) continue;
      File workingFile = ControlsProfile.getProfileFile(context, originProfile.id);
      File backupFile = getBackupFile(context, originProfile.id);
      if (isPristine(workingFile, backupFile)) FileUtils.copy(context, assetPath, workingFile);
      FileUtils.copy(context, assetPath, backupFile);
    }

    preferences
        .edit()
        .putInt("inputcontrols_app_version", newVersion)
        .putInt("inputcontrols_asset_sync_revision", ASSET_PROFILE_SYNC_REVISION)
        .putString("inputcontrols_asset_device_profile", deviceToken)
        .apply();
  }

  public void loadProfiles(boolean ignoreTemplates) {
    File profilesDir = InputControlsManager.getProfilesDir(context);
    copyAssetProfilesIfNeeded();

    ArrayList<ControlsProfile> profiles = new ArrayList<>();
    File[] files = profilesDir.listFiles();
    if (files != null) {
      for (File file : files) {
        ControlsProfile profile = loadProfile(context, file);
        if (profile != null) {
          if (!ignoreTemplates || !profile.isTemplate()) {
            profiles.add(profile);
          }
          maxProfileId = Math.max(maxProfileId, profile.id);
        }
      }
    }

    Collections.sort(profiles);
    this.profiles = profiles;
    profilesLoaded = true;
  }

  public ControlsProfile createProfile(String name) {
    if (!profilesLoaded) loadProfiles(false);
    int newId = ++maxProfileId;
    ControlsProfile profile = new ControlsProfile(context, newId);
    profile.setName(name);
    profile.save();
    backupProfile(context, newId);
    profiles.add(profile);
    return profile;
  }

  public ControlsProfile duplicateProfile(ControlsProfile source) {
    String newName;
    for (int i = 1; ; i++) {
      newName = source.getName() + " (" + i + ")";
      boolean found = false;
      for (ControlsProfile profile : profiles) {
        if (profile.getName().equals(newName)) {
          found = true;
          break;
        }
      }
      if (!found) break;
    }

    int newId = ++maxProfileId;
    File newFile = ControlsProfile.getProfileFile(context, newId);

    try {
      String jsonStr = FileUtils.readString(ControlsProfile.getProfileFile(context, source.id));
      JSONObject data = new JSONObject(jsonStr != null ? jsonStr : "{}");
      data.put("id", newId);
      data.put("name", newName);
      if (data.has("template")) data.remove("template");
      FileUtils.writeString(newFile, data.toString());
    } catch (JSONException e) {
    }

    backupProfile(context, newId);
    ControlsProfile profile = loadProfile(context, newFile);
    profiles.add(profile);
    return profile;
  }

  public void removeProfile(ControlsProfile profile) {
    File file = ControlsProfile.getProfileFile(context, profile.id);
    if (file.isFile() && file.delete()) {
      profiles.remove(profile);
      File backup = getBackupFile(context, profile.id);
      if (backup.isFile()) backup.delete();
    }
  }

  public synchronized ControlsProfile importProfile(JSONObject data) {
    try {
      if (!profilesLoaded || profiles == null) {
        loadProfiles(false);
      }
      String profileName = data.optString("name", "").trim();
      if (profileName.isEmpty()) {
        Log.e("ICManager", "importProfile: data missing 'name' field: " + data.toString());
        return null;
      }

      ControlsProfile existingProfile = findProfileByName(profileName);
      int targetId = existingProfile != null ? existingProfile.id : ++maxProfileId;
      File targetFile = ControlsProfile.getProfileFile(context, targetId);
      data.put("id", targetId);
      FileUtils.writeString(targetFile, data.toString());
      backupProfile(context, targetId);
      ControlsProfile newProfile = loadProfile(context, targetFile);

      if (newProfile == null) {
        Log.e("ICManager", "importProfile: loadProfile returned null for " + targetFile.getPath());
        // If writing was successful, still return a basic profile object
        newProfile = new ControlsProfile(context, targetId);
        newProfile.setName(data.optString("name", "Imported Profile"));
      }

      int foundIndex = existingProfile != null ? profiles.indexOf(existingProfile) : -1;

      if (foundIndex != -1) {
        profiles.set(foundIndex, newProfile);
      } else profiles.add(newProfile);
      return newProfile;
    } catch (JSONException e) {
      Log.e("ICManager", "importProfile: JSONException", e);
      return null;
    }
  }

  private ControlsProfile findProfileByName(String name) {
    String normalizedName = normalizeProfileName(name);
    for (ControlsProfile profile : profiles) {
      // Never match a bundled built-in profile: importing a legacy ICP whose name collides with
      // a built-in (e.g. a Winlator "Xbox Controller.icp") must create a fresh, visible profile
      // rather than silently overwriting the read-only asset — which would land it on a hidden
      // legacy slot and make the import appear to do nothing.
      if (isBuiltinProfile(profile)) continue;
      if (normalizeProfileName(profile.getName()).equals(normalizedName)) {
        return profile;
      }
    }
    return null;
  }

  private String normalizeProfileName(String name) {
    if (name == null) return "";
    String trimmed = name.trim();
    if (trimmed.toLowerCase(Locale.ROOT).endsWith(".icp")) {
      trimmed = trimmed.substring(0, trimmed.length() - 4);
    }
    return trimmed.trim().toLowerCase(Locale.ROOT);
  }

  public File exportProfile(ControlsProfile profile) {
    File destination;
    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
    String winlatorPath = sp.getString("winlator_path_uri", null);
    if (winlatorPath != null) {
      Uri winlatorUri = Uri.parse(winlatorPath);
      destination =
          new File(
              FileUtils.getFilePathFromUri(context, winlatorUri),
              "profiles/" + profile.getName() + ".icp");
    } else {
      destination =
          new File(SettingsConfig.DEFAULT_WINLATOR_PATH, "profiles/" + profile.getName() + ".icp");
    }
    FileUtils.copy(ControlsProfile.getProfileFile(context, profile.id), destination);
    MediaScannerConnection.scanFile(
        context, new String[] {destination.getAbsolutePath()}, null, null);
    return destination.isFile() ? destination : null;
  }

  public static ControlsProfile loadProfile(Context context, File file) {
    try {
      return loadProfile(context, new FileInputStream(file));
    } catch (FileNotFoundException e) {
      return null;
    }
  }

  public static ControlsProfile loadProfile(Context context, InputStream inStream) {
    try (JsonReader reader =
        new JsonReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))) {
      int profileId = 0;
      String profileName = null;
      float cursorSpeed = Float.NaN;
      int fieldsRead = 0;

      reader.beginObject();
      while (reader.hasNext()) {
        String name = reader.nextName();

        if (name.equals("id")) {
          profileId = reader.nextInt();
          fieldsRead++;
        } else if (name.equals("name")) {
          profileName = reader.nextString();
          fieldsRead++;
        } else if (name.equals("cursorSpeed")) {
          cursorSpeed = (float) reader.nextDouble();
          fieldsRead++;
        } else {
          if (fieldsRead == 3) break;
          reader.skipValue();
        }
      }

      ControlsProfile profile = new ControlsProfile(context, profileId);
      if (profileName != null) {
        profile.setName(profileName);
        profile.setCursorSpeed(cursorSpeed);
        reader.close();
        return profile;
      }
      reader.close();
      return null;
    } catch (IOException e) {
      return null;
    }
  }

  public ControlsProfile getProfile(int id) {
    for (ControlsProfile profile : getProfiles()) if (profile.id == id) return profile;
    return null;
  }
}
