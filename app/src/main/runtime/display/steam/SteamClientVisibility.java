package com.winlator.cmod.runtime.display.steam;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.feature.stores.steam.utils.PrefManager;
import com.winlator.cmod.runtime.compat.SteamBridge;
import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import com.winlator.cmod.runtime.wine.WineRegistryEditor;
import com.winlator.cmod.shared.io.FileUtils;
import com.winlator.cmod.shared.io.TarCompressorUtils;

import java.io.File;
import java.util.Locale;

/**
 * Steam client directory visibility, registry hide/restore, shared stores, ColdClient sidecar,
 * and Bionic ActiveProcess registry helpers.
 * Extracted from XServerDisplayActivity. Activity keeps thin delegating wrappers.
 */
public final class SteamClientVisibility {
    private static final String TAG = "SteamClientVisibility";

    private static final String STEAM_REGISTRY_KEY = "Software\\Valve\\Steam";
    private static final String STEAM_ROOT_PATH = "C:\\Program Files (x86)\\Steam";
    private static final String STEAM_EXE_PATH = STEAM_ROOT_PATH + "\\steam.exe";
    private static final String STEAM_USER_REGISTRY_BACKUP_FILE = "steam_registry_backup.reg";
    private static final String STEAM_SYSTEM_REGISTRY_BACKUP_FILE = "steam_system_registry_backup.reg";
    public static final String STEAM_CLIENT_STORE_RELATIVE_PATH = ".shared/steam-client-store";
    public static final String COLDCLIENT_STORE_RELATIVE_PATH = ".shared/coldclient-store";
    private static final String PREVIOUS_STEAM_CLIENT_STORE_RELATIVE_PATH = ".steam-client-store";
    private static final String PREVIOUS_CONTAINER_STEAM_CLIENT_STORE_RELATIVE_PATH = ".wine/.steam-client-store";
    private static final String LEGACY_STEAM_CLIENT_STORE_RELATIVE_PATH = ".wine/drive_c/WinNative/SteamClient";

    private static final String[] STEAM_SYSTEM_REGISTRY_KEYS = new String[] {
            "Software\\Classes\\steam",
            "Software\\Wow6432Node\\Valve\\Steam"
    };
    private static final String[] STEAM_REGISTRY_LINE_PATTERNS = new String[] {
            "\"sourcemodinstallpath\"",
            "\"steamexe\"",
            "\"steampath\"",
            "\"steamclientdll\"",
            "\"steamclientdll64\"",
            "winnative\\\\steamclient",
            "winnative/steamclient",
            ".shared\\\\steam-client-store",
            ".shared/steam-client-store",
            "steamclient_loader_x64.exe",
            "steamclient_loader_x86.exe",
            "steamclient_loader_x32.exe"
    };

    private final Context context;
    private Container container;
    private ImageFs imageFs;
    private volatile String appliedSteamClientVisibility;

    public SteamClientVisibility(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Call when container/imageFs are ready (after activateContainer). */
    public void bind(Container container, ImageFs imageFs) {
        this.container = container;
        this.imageFs = imageFs;
    }

    public void setSteamClientVisibility(boolean visible) {
        setSteamClientVisibility(visible, false);
    }

    public void setSteamClientVisibility(boolean visible, boolean coldClientMode) {
        if (container == null) return;
        String requested = container.id + ":" + visible + ":" + coldClientMode;
        if (requested.equals(appliedSteamClientVisibility)) {
            Log.d(TAG,
                    "Steam client visibility already applied this session (" + requested + "), skipping");
            return;
        }
        appliedSteamClientVisibility = requested;
        updateSteamDirectoryVisibility(visible, coldClientMode);
        updateSteamRegistryVisibility(visible);
    }

    public void updateSteamDirectoryVisibility(boolean visible) {
        updateSteamDirectoryVisibility(visible, false);
    }

    public void updateSteamDirectoryVisibility(boolean visible, boolean coldClientMode) {
        if (container == null) return;

        File steamLink = new File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam");
        File pristineSteamStore = getSharedSteamStore();
        File coldClientStore = getSharedColdClientStore();
        File target = coldClientMode ? coldClientStore : pristineSteamStore;
        File previousSteamStore = new File(imageFs.getRootDir(), PREVIOUS_STEAM_CLIENT_STORE_RELATIVE_PATH);
        File previousContainerSteamStore = new File(container.getRootDir(), PREVIOUS_CONTAINER_STEAM_CLIENT_STORE_RELATIVE_PATH);
        File legacySteamStore = new File(container.getRootDir(), LEGACY_STEAM_CLIENT_STORE_RELATIVE_PATH);

        try {
            moveSteamDirectoryIntoBackingStore(steamLink, pristineSteamStore);
            migrateLegacySteamStoreIfNeeded(previousSteamStore, pristineSteamStore);
            migrateLegacySteamStoreIfNeeded(previousContainerSteamStore, pristineSteamStore);
            migrateLegacySteamStoreIfNeeded(legacySteamStore, pristineSteamStore);

            if (visible) {
                if (!target.exists()) {
                    target.mkdirs();
                }
                if (steamLink.exists()) {
                    FileUtils.delete(steamLink);
                }
                FileUtils.symlink(target, steamLink);
                Log.d(TAG,
                        "Steam symlink → " + (coldClientMode ? "coldclient-store" : "steam-client-store")
                                + " at " + steamLink.getAbsolutePath());
            } else {
                if (steamLink.exists()) {
                    FileUtils.delete(steamLink);
                    Log.d(TAG, "Removed visible Steam root for non-Steam launch");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating Steam directory visibility", e);
        }
    }

    public File getSharedSteamStore() {
        if (imageFs != null) {
            return new File(imageFs.getRootDir(), STEAM_CLIENT_STORE_RELATIVE_PATH);
        }
        return new File(context.getFilesDir(), "imagefs/" + STEAM_CLIENT_STORE_RELATIVE_PATH);
    }

    public File getSharedColdClientStore() {
        if (imageFs != null) {
            return new File(imageFs.getRootDir(), COLDCLIENT_STORE_RELATIVE_PATH);
        }
        return new File(context.getFilesDir(), "imagefs/" + COLDCLIENT_STORE_RELATIVE_PATH);
    }

    public boolean ensureColdClientStore() {
        File cstore = getSharedColdClientStore();
        File loader = new File(cstore, "steamclient_loader_x64.exe");
        File stub = new File(cstore, "steamclient64.dll");
        if (loader.exists() && loader.length() > 0 && stub.exists() && stub.length() > 0) {
            return true;
        }

        if (!SteamBridge.ensureColdClientSupportReady(this)) {
            Log.w(TAG, "ensureColdClientStore: experimental-drm.tzst not available");
            return false;
        }
        File expFile = new File(context.getFilesDir(), "experimental-drm.tzst");
        if (!expFile.exists()) {
            Log.w(TAG, "ensureColdClientStore: experimental-drm.tzst missing from filesDir");
            return false;
        }

        cstore.mkdirs();
        try {
            com.winlator.cmod.shared.io.TarCompressorUtils.extract(
                    com.winlator.cmod.shared.io.TarCompressorUtils.Type.ZSTD,
                    expFile, imageFs.getRootDir(), null);
            Log.d(TAG,
                    "ensureColdClientStore: extracted experimental-drm.tzst into coldclient sidecar");
        } catch (Exception e) {
            Log.e(TAG, "ensureColdClientStore: extraction failed", e);
            return false;
        }

        return loader.exists() && stub.exists();
    }

    public void migrateLegacySteamStoreIfNeeded(File legacySteamStore, File steamStore) {
        if (legacySteamStore == null || steamStore == null || !legacySteamStore.exists()) return;

        File parentDir = steamStore.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        if (!steamStore.exists()) {
            if (legacySteamStore.renameTo(steamStore)) {
                Log.d(TAG, "Migrated legacy Steam backing store to hidden location");
                return;
            }

            if (!steamStore.mkdirs()) {
                Log.w(TAG, "Failed to create hidden Steam backing store during legacy migration");
                return;
            }
        }

        if (!steamStore.isDirectory()) {
            Log.w(TAG, "Hidden Steam backing store is not a directory");
            return;
        }

        if (!FileUtils.copy(legacySteamStore, steamStore)) {
            Log.w(TAG, "Failed to copy legacy Steam backing store into hidden location");
            return;
        }

        if (FileUtils.delete(legacySteamStore)) {
            Log.d(TAG, "Removed legacy Windows-visible Steam backing store");
        } else {
            Log.w(TAG, "Failed to remove legacy Windows-visible Steam backing store");
        }
    }

    public void moveSteamDirectoryIntoBackingStore(File steamLink, File steamStore) {
        if (steamLink == null || steamStore == null) return;
        if (!steamLink.exists() || FileUtils.isSymlink(steamLink)) return;

        File parentDir = steamStore.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        if (!steamStore.exists()) {
            if (steamLink.renameTo(steamStore)) {
                Log.d(TAG, "Migrated Steam directory to backing store: " + steamStore.getAbsolutePath());
                return;
            }
            Log.w(TAG, "Failed to rename Steam directory into backing store, falling back to copy");
        }

        if (!steamStore.exists() && !steamStore.mkdirs()) {
            Log.w(TAG, "Unable to create Steam backing store: " + steamStore.getAbsolutePath());
            return;
        }

        if (!steamStore.isDirectory()) {
            Log.w(TAG, "Steam backing store is not a directory: " + steamStore.getAbsolutePath());
            return;
        }

        if (!FileUtils.copy(steamLink, steamStore)) {
            Log.w(TAG, "Failed to copy Steam directory contents into backing store");
            return;
        }

        if (FileUtils.delete(steamLink)) {
            Log.d(TAG, "Collapsed visible Steam directory into backing store");
        } else {
            Log.w(TAG, "Failed to remove visible Steam directory after backing-store copy");
        }
    }

    public void updateSteamRegistryVisibility(boolean visible) {
        if (container == null) return;
        File userRegFile = new File(container.getRootDir(), ".wine/user.reg");
        File systemRegFile = new File(container.getRootDir(), ".wine/system.reg");
        File userBackupFile = new File(container.getRootDir(), ".wine/" + STEAM_USER_REGISTRY_BACKUP_FILE);
        File systemBackupFile = new File(container.getRootDir(), ".wine/" + STEAM_SYSTEM_REGISTRY_BACKUP_FILE);
        if (!visible) {
            try {
                forceHideSteamRegistry(userRegFile, userBackupFile, STEAM_REGISTRY_KEY);
                forceHideSteamRegistry(systemRegFile, systemBackupFile, STEAM_SYSTEM_REGISTRY_KEYS);
            } catch (Exception e) {
                Log.e(TAG, "Error updating Steam registry visibility", e);
            }
            return;
        }

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            if (visible) {
                restoreRegistrySubtrees(userRegFile, userBackupFile, STEAM_REGISTRY_KEY);
                restoreRegistrySubtrees(systemRegFile, systemBackupFile, STEAM_SYSTEM_REGISTRY_KEYS);
                registryEditor.removeKey(STEAM_REGISTRY_KEY, true);
                String backupContent = userBackupFile.isFile() ? FileUtils.readString(userBackupFile) : null;
                if (backupContent != null && !backupContent.trim().isEmpty()) {
                    if (registryEditor.appendRawContent(backupContent)) {
                        Log.d(TAG, "Restored Steam registry subtree from backup");
                    } else {
                        Log.w(TAG, "Failed to restore Steam registry subtree from backup");
                    }
                } else {
                    registryEditor.setCreateKeyIfNotExist(true);
                    registryEditor.setStringValue(STEAM_REGISTRY_KEY, "SteamExe", STEAM_EXE_PATH);
                    registryEditor.setStringValue(STEAM_REGISTRY_KEY, "SteamPath", STEAM_ROOT_PATH);
                    registryEditor.setStringValue(STEAM_REGISTRY_KEY, "InstallPath", STEAM_ROOT_PATH);

                    String autoLoginUser = PrefManager.INSTANCE.getUsername();
                    if (autoLoginUser != null && !autoLoginUser.isEmpty()) {
                        registryEditor.setStringValue(STEAM_REGISTRY_KEY, "AutoLoginUser", autoLoginUser);
                    } else {
                        registryEditor.removeValue(STEAM_REGISTRY_KEY, "AutoLoginUser");
                    }
                    Log.d(TAG, "Created default Steam registry subtree");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating Steam registry visibility", e);
        }
    }

    public void forceHideSteamRegistry(File registryFile, File backupFile, String... keys) {
        java.util.concurrent.locks.ReentrantLock registryLock =
                WineRegistryEditor.lockFor(registryFile);
        registryLock.lock();
        try {
            forceHideSteamRegistryLocked(registryFile, backupFile, keys);
        } finally {
            registryLock.unlock();
        }
    }

    public void forceHideSteamRegistryLocked(File registryFile, File backupFile, String... keys) {
        String rawRegistry = FileUtils.readString(registryFile);
        if (rawRegistry == null) rawRegistry = "";

        String backupContent = extractRegistrySubtrees(rawRegistry, keys);
        if (!backupContent.trim().isEmpty()) {
            FileUtils.writeString(backupFile, backupContent.trim() + "\n");
            Log.d(TAG, "Backed up Steam registry subtrees from " + registryFile.getName());
        }

        String sanitizedRegistry = sanitizeSteamRegistryContent(rawRegistry, keys);
        FileUtils.writeString(registryFile, sanitizedRegistry);
        Log.d(TAG, "Force-sanitized Steam registry state in " + registryFile.getName());
    }

    public String sanitizeSteamRegistryContent(String registryContent, String... keys) {
        String sanitized = removeRegistrySubtrees(registryContent, keys);
        return scrubRegistryLinePatterns(sanitized, STEAM_REGISTRY_LINE_PATTERNS);
    }

    public String scrubRegistryLinePatterns(String content, String... patterns) {
        if (content == null || content.isEmpty() || patterns == null || patterns.length == 0) {
            return content != null ? content : "";
        }
        String[] lines = content.split("\n", -1);
        StringBuilder rebuilt = new StringBuilder();
        for (String line : lines) {
            boolean remove = false;
            String normalizedLine = line.toLowerCase(Locale.ROOT);
            for (String pattern : patterns) {
                if (normalizedLine.contains(pattern)) {
                    remove = true;
                    break;
                }
            }
            if (!remove) {
                rebuilt.append(line).append('\n');
            }
        }
        return rebuilt.toString();
    }

    public void hideRegistrySubtrees(File registryFile, File backupFile, String... keys) {
        java.util.concurrent.locks.ReentrantLock registryLock =
                WineRegistryEditor.lockFor(registryFile);
        registryLock.lock();
        try {
            hideRegistrySubtreesLocked(registryFile, backupFile, keys);
        } finally {
            registryLock.unlock();
        }
    }

    public void hideRegistrySubtreesLocked(File registryFile, File backupFile, String... keys) {
        String rawRegistry = FileUtils.readString(registryFile);
        if (rawRegistry == null) rawRegistry = "";

        String backupContent = extractRegistrySubtrees(rawRegistry, keys);
        if (!backupContent.trim().isEmpty()) {
            FileUtils.writeString(backupFile, backupContent.trim() + "\n");
            Log.d(TAG, "Backed up Steam registry subtrees from " + registryFile.getName());
        }

        String strippedRegistry = removeRegistrySubtrees(rawRegistry, keys);
        if (!strippedRegistry.equals(rawRegistry)) {
            FileUtils.writeString(registryFile, strippedRegistry);
            Log.d(TAG, "Removed Steam registry subtrees from " + registryFile.getName());
        } else {
            Log.d(TAG, "Steam registry subtrees already hidden in " + registryFile.getName());
        }
    }

    public void restoreRegistrySubtrees(File registryFile, File backupFile, String... keys) {
        java.util.concurrent.locks.ReentrantLock registryLock =
                WineRegistryEditor.lockFor(registryFile);
        registryLock.lock();
        try {
            restoreRegistrySubtreesLocked(registryFile, backupFile, keys);
        } finally {
            registryLock.unlock();
        }
    }

    public void restoreRegistrySubtreesLocked(File registryFile, File backupFile, String... keys) {
        String rawRegistry = FileUtils.readString(registryFile);
        if (rawRegistry == null) rawRegistry = "";

        String strippedRegistry = removeRegistrySubtrees(rawRegistry, keys);
        if (!strippedRegistry.equals(rawRegistry)) {
            FileUtils.writeString(registryFile, strippedRegistry);
        }

        if (!backupFile.isFile()) return;
        String backupContent = FileUtils.readString(backupFile);
        if (backupContent == null || backupContent.trim().isEmpty()) return;

        String merged = FileUtils.readString(registryFile);
        if (merged == null) merged = "";
        if (!merged.endsWith("\n") && !merged.isEmpty()) merged += "\n";
        merged += backupContent.trim() + "\n";
        FileUtils.writeString(registryFile, merged);
    }

    public String extractRegistrySubtrees(String registryContent, String... keys) {
        if (registryContent == null || registryContent.isEmpty() || keys == null || keys.length == 0) {
            return "";
        }

        StringBuilder extracted = new StringBuilder();
        for (String key : keys) {
            String subtree = extractRegistrySubtree(registryContent, key);
            if (subtree != null && !subtree.trim().isEmpty()) {
                if (extracted.length() > 0 && extracted.charAt(extracted.length() - 1) != '\n') {
                    extracted.append('\n');
                }
                extracted.append(subtree.trim()).append('\n');
            }
        }
        return extracted.toString();
    }

    public String removeRegistrySubtrees(String registryContent, String... keys) {
        String updated = registryContent != null ? registryContent : "";
        if (keys == null) return updated;
        for (String key : keys) {
            updated = removeRegistrySubtree(updated, key);
        }
        return updated;
    }

    public String extractRegistrySubtree(String registryContent, String key) {
        if (registryContent == null || registryContent.isEmpty() || key == null || key.isEmpty()) {
            return "";
        }

        String escapedKey = key.replace("\\", "\\\\");
        String prefix = "[" + escapedKey;
        StringBuilder extracted = new StringBuilder();
        boolean capturing = false;
        String[] lines = registryContent.split("\n", -1);
        for (String line : lines) {
            if (line.startsWith("[")) {
                if (capturing && !line.startsWith(prefix)) {
                    break;
                }
                if (!capturing && line.startsWith(prefix)) {
                    capturing = true;
                }
            }
            if (capturing) {
                extracted.append(line).append('\n');
            }
        }
        return extracted.toString();
    }

    public String removeRegistrySubtree(String registryContent, String key) {
        if (registryContent == null || registryContent.isEmpty() || key == null || key.isEmpty()) {
            return registryContent != null ? registryContent : "";
        }

        String escapedKey = key.replace("\\", "\\\\");
        String prefix = "[" + escapedKey;
        StringBuilder rebuilt = new StringBuilder();
        boolean capturing = false;
        String[] lines = registryContent.split("\n", -1);
        for (String line : lines) {
            if (line.startsWith("[")) {
                if (capturing && !line.startsWith(prefix)) {
                    capturing = false;
                }
                if (!capturing && line.startsWith(prefix)) {
                    capturing = true;
                }
            }
            if (!capturing) {
                rebuilt.append(line).append('\n');
            }
        }
        return rebuilt.toString();
    }

    public void writeBionicActiveProcessRegistry() {
        try {
            long steamId64 = com.winlator.cmod.feature.stores.steam.utils
                    .PrefManager.INSTANCE.getSteamUserSteamId64();
            int accountId = (int) (steamId64 & 0xFFFFFFFFL);
            File userReg = new File(container.getRootDir(), ".wine/user.reg");
            int steamPid = android.os.Process.myPid();
            try (com.winlator.cmod.runtime.wine.WineRegistryEditor editor =
                         new com.winlator.cmod.runtime.wine.WineRegistryEditor(userReg)) {
                editor.setCreateKeyIfNotExist(true);
                String key = "Software\\Valve\\Steam\\ActiveProcess";
                editor.setDwordValue(key, "ActiveUser", accountId);
                editor.setDwordValue(key, "pid", steamPid);
                editor.setStringValue(key, "SteamClientDll",
                        "C:\\windows\\syswow64\\lsteamclient.dll");
                editor.setStringValue(key, "SteamClientDll64",
                        "C:\\windows\\system32\\lsteamclient.dll");
                editor.setStringValue(key, "Universe", "Public");
            }
            Log.d(TAG,
                    "Bionic: wrote ActiveProcess registry (ActiveUser=" + accountId
                            + " pid=" + steamPid + ")");
        } catch (Exception e) {
            Log.e(TAG, "Bionic: ActiveProcess registry write failed", e);
        }
    }

    public boolean installBionicSteamPathOverlay(Container container, File bionicSteamDir) {
        try {
            File sharedStore = getSharedSteamStore();
            if (!sharedStore.isDirectory()) {
                Log.w(TAG,
                        "installBionicSteamPathOverlay: shared steam-client-store missing at "
                                + sharedStore.getAbsolutePath()
                                + " — falling back to bridge-in-system32 only (game may fail to "
                                + "find steamclient64.dll because stock steam_api64.dll searches "
                                + "SteamPath first)");
                return false;
            }
            File bridge64Src = new File(container.getRootDir(),
                    ".wine/drive_c/windows/system32/lsteamclient.dll");
            File bridge32Src = new File(container.getRootDir(),
                    ".wine/drive_c/windows/syswow64/lsteamclient.dll");
            if (!bridge64Src.exists()) {
                Log.w(TAG,
                        "installBionicSteamPathOverlay: bridge missing at "
                                + bridge64Src.getAbsolutePath());
                return false;
            }
            java.nio.file.Path bionicPath = bionicSteamDir.toPath();
            if (java.nio.file.Files.isSymbolicLink(bionicPath)) {
                java.nio.file.Files.delete(bionicPath);
            }
            if (!bionicSteamDir.exists()) {
                bionicSteamDir.mkdirs();
            }
            File[] storeEntries = sharedStore.listFiles();
            int symlinkedCount = 0;
            if (storeEntries != null) {
                for (File entry : storeEntries) {
                    String name = entry.getName();
                    if (name.equalsIgnoreCase("steamclient.dll")
                            || name.equalsIgnoreCase("steamclient64.dll")
                            || name.equalsIgnoreCase("steamapps")) {
                        continue;
                    }
                    File dest = new File(bionicSteamDir, name);
                    if (dest.exists() || java.nio.file.Files.isSymbolicLink(dest.toPath())) {
                        continue;
                    }
                    java.nio.file.Files.createSymbolicLink(
                            dest.toPath(), entry.toPath().toAbsolutePath());
                    ++symlinkedCount;
                }
            }
            File dest64 = new File(bionicSteamDir, "steamclient64.dll");
            FileUtils.copy(bridge64Src, dest64);
            File dest32 = new File(bionicSteamDir, "steamclient.dll");
            if (bridge32Src.exists()) {
                FileUtils.copy(bridge32Src, dest32);
            } else {
                FileUtils.copy(bridge64Src, dest32);
            }
            Log.d(TAG,
                    "installBionicSteamPathOverlay: " + symlinkedCount + " store entries"
                            + " symlinked, bridge written as steamclient64.dll ("
                            + dest64.length() + "B) + steamclient.dll ("
                            + dest32.length() + "B)");
            return true;
        } catch (Exception e) {
            Log.e(TAG,
                    "installBionicSteamPathOverlay failed", e);
            return false;
        }
    }

    public void clearBionicActiveProcessRegistry() {
        try {
            File userReg = new File(container.getRootDir(), ".wine/user.reg");
            if (!userReg.exists()) return;
            try (com.winlator.cmod.runtime.wine.WineRegistryEditor editor =
                         new com.winlator.cmod.runtime.wine.WineRegistryEditor(userReg)) {
                String key = "Software\\Valve\\Steam\\ActiveProcess";
                editor.removeValue(key, "SteamClientDll");
                editor.removeValue(key, "SteamClientDll64");
                editor.removeValue(key, "ActiveUser");
                editor.removeValue(key, "pid");
                editor.removeValue(key, "Universe");
            }
            Log.d(TAG, "Cleared Bionic ActiveProcess registry redirector");
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear Bionic ActiveProcess registry", e);
        }
    }

}
