package com.winlator.cmod.runtime.display.steam;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.feature.stores.steam.utils.SteamUtils;
import com.winlator.cmod.runtime.container.Shortcut;
import com.winlator.cmod.shared.io.FileUtils;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;

/**
 * Goldberg/steam_api DLL replace, inject, restore, and steam_interfaces generation.
 * Extracted from XServerDisplayActivity.
 */
public final class SteamApiDllManager {
    private static final String TAG = "SteamApiDllManager";

    private final Context context;
    private Shortcut shortcut;

    public SteamApiDllManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void bind(Shortcut shortcut) {
        this.shortcut = shortcut;
    }

    public void generateSteamInterfacesFromDll(File dir, File dllFile) {
        File interfacesFile = new File(dir, "steam_interfaces.txt");
        if (interfacesFile.exists()) return;

        if (!dllFile.exists()) return;

        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(dllFile.toPath());
            java.util.TreeSet<String> interfaces = new java.util.TreeSet<>();

            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                int ch = b & 0xFF;
                if (ch >= 0x20 && ch <= 0x7E) {
                    sb.append((char) ch);
                } else {
                    if (sb.length() >= 10) {
                        String candidate = sb.toString();
                        if (candidate.matches("^Steam[A-Za-z]+[0-9]{3}$")) {
                            interfaces.add(candidate);
                        }
                    }
                    sb.setLength(0);
                }
            }
            if (sb.length() >= 10) {
                String candidate = sb.toString();
                if (candidate.matches("^Steam[A-Za-z]+[0-9]{3}$")) {
                    interfaces.add(candidate);
                }
            }

            if (!interfaces.isEmpty()) {
                StringBuilder content = new StringBuilder();
                for (String iface : interfaces) {
                    content.append(iface).append("\n");
                }
                FileUtils.writeString(interfacesFile, content.toString());
                Log.d(TAG, "Generated steam_interfaces.txt with " + interfaces.size() + " interfaces in " + dir.getName());
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to generate steam_interfaces.txt from " + dllFile.getName(), e);
        }
    }

    public void injectSteamApiIfMissing(File gameDir, String appDirPath, String language,
            boolean isOffline, boolean useSteamInput, String ticketBase64, java.util.List<String> backupPaths) {
        Log.w(TAG, "No steam_api DLLs found in game directory — injecting Goldberg steam_api next to game exe");
        try {
            String exePath = null;
            if (shortcut != null) {
                exePath = shortcut.getExtra("launch_exe_path");
            }
            File gameExe = null;
            if (exePath != null && !exePath.isEmpty()) {
                File candidate = new File(exePath);
                if (!candidate.isAbsolute()) candidate = new File(gameDir, exePath);
                if (candidate.exists()) gameExe = candidate;
            }
            if (gameExe == null) {
                File[] rootFiles = gameDir.listFiles();
                if (rootFiles != null) {
                    for (File f : rootFiles) {
                        if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".exe")
                                && !f.getName().toLowerCase(Locale.ROOT).contains("crash")
                                && !f.getName().toLowerCase(Locale.ROOT).contains("unins")
                                && !f.getName().toLowerCase(Locale.ROOT).contains("redist")) {
                            gameExe = f;
                            break;
                        }
                    }
                }
            }

            if (gameExe != null && gameExe.exists()) {
                File exeDir = gameExe.getParentFile();
                boolean isX64 = isExe64Bit(gameExe);
                String dllName = isX64 ? "steam_api64.dll" : "steam_api.dll";
                String assetName = isX64 ? "steampipe/steam_api64.dll" : "steampipe/steam_api.dll";
                String stubAsset = isX64 ? "steampipe/steamclient64.dll" : "steampipe/steamclient.dll";
                String stubName = isX64 ? "steamclient64.dll" : "steamclient.dll";

                File targetDll = new File(exeDir, dllName);
                if (!targetDll.exists()) {
                    try (InputStream is = context.getAssets().open(assetName);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(targetDll)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) >= 0) fos.write(buf, 0, len);
                    }
                    // Empty .orig means restore should delete this injected DLL.
                    new File(targetDll.getAbsolutePath() + ".orig").createNewFile();
                    Log.d(TAG,
                            "Injected Goldberg " + dllName + " next to " + gameExe.getName());
                }

                File stubFile = new File(exeDir, stubName);
                if (!stubFile.exists()) {
                    try (InputStream is = context.getAssets().open(stubAsset);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(stubFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) >= 0) fos.write(buf, 0, len);
                    }
                    Log.d(TAG,
                            "Injected steamclient stub " + stubName + " next to " + gameExe.getName());
                }

                // Some games bypass search order with LoadLibrary("Steam\\steamclient64.dll").
                File gameSteamDir = new File(exeDir, "Steam");
                if (gameSteamDir.exists() && gameSteamDir.isDirectory()) {
                    File embeddedClient = new File(gameSteamDir, stubName);
                    if (embeddedClient.exists()) {
                        File backupClient = new File(gameSteamDir, stubName + ".orig");
                        if (!backupClient.exists()) {
                            FileUtils.copy(embeddedClient, backupClient);
                        }
                        
                        embeddedClient.delete();
                        try (InputStream is = context.getAssets().open(stubAsset);
                             java.io.FileOutputStream fos = new java.io.FileOutputStream(embeddedClient)) {
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = is.read(buf)) >= 0) fos.write(buf, 0, len);
                        }
                        Log.w(TAG, "Intercepted explicit embedded Steam client: " + embeddedClient.getAbsolutePath());
                        
                        if (backupPaths != null && appDirPath != null) {
                            String relPath = backupClient.getAbsolutePath();
                            if (relPath.startsWith(appDirPath)) {
                                relPath = relPath.substring(appDirPath.length());
                                if (relPath.startsWith("/")) relPath = relPath.substring(1);
                            }
                            backupPaths.add(relPath);
                        }
                        
                        SteamUtils.writeCompleteSettingsDir(gameSteamDir,
                                Integer.parseInt(shortcut.getExtra("app_id")),
                                language, isOffline, useSteamInput, ticketBase64);
                    }
                }

                SteamUtils.writeCompleteSettingsDir(exeDir,
                        Integer.parseInt(shortcut.getExtra("app_id")),
                        language, isOffline, useSteamInput, ticketBase64);

                if (backupPaths != null && appDirPath != null) {
                    String relPath = targetDll.getAbsolutePath();
                    if (relPath.startsWith(appDirPath)) {
                        relPath = relPath.substring(appDirPath.length());
                        if (relPath.startsWith("/")) relPath = relPath.substring(1);
                    }
                    backupPaths.add(relPath);
                }
            } else {
                Log.w(TAG, "Could not find game exe to inject steam_api DLL");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject steam_api DLL for no-DLL game", e);
        }
    }

    public void replaceSteamApiDlls(File gameDir, String appDirPath, String language,
            boolean isOffline, boolean useSteamInput, String ticketBase64) {
        if (gameDir == null || !gameDir.exists()) return;

        java.util.List<String> backupPaths = new java.util.ArrayList<>();
        replaceSteamApiDllsRecursive(gameDir, appDirPath, language, isOffline,
                useSteamInput, ticketBase64, backupPaths);

        // Games without steam_api*.dll need an injected hook next to the exe.
        if (backupPaths.isEmpty()) {
            injectSteamApiIfMissing(gameDir, appDirPath, language, isOffline, useSteamInput, ticketBase64, backupPaths);
        }

        if (!backupPaths.isEmpty()) {
            try {
                java.util.Collections.sort(backupPaths);
                File origPathFile = new File(appDirPath, "orig_dll_path.txt");
                FileUtils.writeString(origPathFile, android.text.TextUtils.join(System.lineSeparator(), backupPaths));
                Log.d(TAG, "Wrote " + backupPaths.size() + " DLL backup paths to orig_dll_path.txt");
            } catch (Exception e) {
                Log.w(TAG, "Failed to write orig_dll_path.txt", e);
            }
        }
    }

    public boolean hasSteamApiDllInTree(File dir) {
        if (dir == null || !dir.exists()) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.isDirectory()) {
                if (!file.getName().equals("steam_settings") && hasSteamApiDllInTree(file)) return true;
            } else {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (name.equals("steam_api.dll") || name.equals("steam_api64.dll")) return true;
            }
        }
        return false;
    }

    public boolean isExe64Bit(File exeFile) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(exeFile, "r")) {
            raf.seek(0x3C);
            int peOffset = Integer.reverseBytes(raf.readInt());
            raf.seek(peOffset + 4);
            int machine = Short.reverseBytes(raf.readShort()) & 0xFFFF;
            return machine == 0x8664 || machine == 0xAA64;
        } catch (Exception e) {
            Log.w(TAG, "Could not determine exe architecture, assuming x64", e);
            return true;
        }
    }

    public void replaceSteamApiDllsRecursive(File dir, String appDirPath, String language,
            boolean isOffline, boolean useSteamInput, String ticketBase64,
            java.util.List<String> backupPaths) {
        if (dir == null || !dir.exists()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        boolean hasSteamDll = false;
        for (File file : files) {
            if (file.isDirectory()) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!name.equals("steam_api.dll") && !name.equals("steam_api64.dll")) continue;

            hasSteamDll = true;
            String assetName = name.equals("steam_api64.dll")
                    ? "steampipe/steam_api64.dll"
                    : "steampipe/steam_api.dll";

            try {
                generateSteamInterfacesFromDll(dir, file);

                File backup = new File(file.getParent(), file.getName() + ".orig");
                if (!backup.exists()) {
                    FileUtils.copy(file, backup);
                    Log.d(TAG, "Backed up original: " + file.getName() + " as .orig");
                }
                String relPath = backup.getAbsolutePath();
                if (relPath.startsWith(appDirPath)) {
                    relPath = relPath.substring(appDirPath.length());
                    if (relPath.startsWith("/")) relPath = relPath.substring(1);
                }
                backupPaths.add(relPath);

                file.delete();
                file.createNewFile();
                try (InputStream is = context.getAssets().open(assetName);
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) >= 0) fos.write(buf, 0, len);
                }
                Log.d(TAG, "Replaced " + file.getName() + " at " + file.getAbsolutePath());

                // Experimental steam_api DLLs need matching steamclient stubs.
                String stubAsset = name.equals("steam_api64.dll")
                        ? "steampipe/steamclient64.dll"
                        : "steampipe/steamclient.dll";
                String stubName = name.equals("steam_api64.dll")
                        ? "steamclient64.dll"
                        : "steamclient.dll";
                File stubFile = new File(dir, stubName);
                if (!stubFile.exists()) {
                    try (InputStream stubIs = context.getAssets().open(stubAsset);
                         java.io.FileOutputStream stubFos = new java.io.FileOutputStream(stubFile)) {
                        byte[] stubBuf = new byte[8192];
                        int stubLen;
                        while ((stubLen = stubIs.read(stubBuf)) >= 0) stubFos.write(stubBuf, 0, stubLen);
                    }
                    Log.d(TAG, "Copied steamclient stub " + stubName + " next to " + file.getName());
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to replace " + file.getName(), e);
            }
        }

        if (hasSteamDll) {
            SteamUtils.writeCompleteSettingsDir(dir,
                    Integer.parseInt(shortcut.getExtra("app_id")),
                    language, isOffline, useSteamInput, ticketBase64);
        }

        for (File file : files) {
            if (file.isDirectory() && !file.getName().equals("steam_settings")) {
                replaceSteamApiDllsRecursive(file, appDirPath, language, isOffline,
                        useSteamInput, ticketBase64, backupPaths);
            }
        }
    }

    public void setupSteamSettingsForAllDirs(File dir, int appId, String language,
            boolean isOffline, boolean useSteamInput, String ticketBase64) {
        if (dir == null || !dir.exists()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        boolean hasSteamDll = false;
        for (File file : files) {
            if (!file.isDirectory()) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (name.equals("steam_api.dll") || name.equals("steam_api64.dll")) {
                    hasSteamDll = true;
                }
            }
        }

        if (hasSteamDll) {
            SteamUtils.writeCompleteSettingsDir(dir, appId, language, isOffline, useSteamInput, ticketBase64);
        }

        for (File file : files) {
            if (file.isDirectory() && !file.getName().equals("steam_settings")) {
                setupSteamSettingsForAllDirs(file, appId, language, isOffline, useSteamInput, ticketBase64);
            }
        }
    }

    // Backfill steamclient stubs for older steam_api replacements.
    public void copySteamclientStubs(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                if (!file.getName().equals("steam_settings")) copySteamclientStubs(file);
                continue;
            }
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!name.equals("steam_api.dll") && !name.equals("steam_api64.dll")) continue;

            String stubAsset = name.equals("steam_api64.dll")
                    ? "steampipe/steamclient64.dll" : "steampipe/steamclient.dll";
            String stubName = name.equals("steam_api64.dll")
                    ? "steamclient64.dll" : "steamclient.dll";
            File stubFile = new File(dir, stubName);
            if (!stubFile.exists()) {
                try (InputStream is = context.getAssets().open(stubAsset);
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(stubFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) >= 0) fos.write(buf, 0, len);
                    Log.d(TAG, "Copied missing steamclient stub " + stubName + " to " + dir.getAbsolutePath());
                } catch (Exception e) {
                    Log.e(TAG, "Failed to copy steamclient stub " + stubName, e);
                }
            }
        }
    }

    // Restore real steam_api DLLs when leaving Goldberg for ColdClient.
    public void restoreSteamApiDlls(File gameDir) {
        if (gameDir == null || !gameDir.exists()) return;

        File[] files = gameDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                if (!file.getName().equals("steam_settings")) {
                    restoreSteamApiDlls(file);
                }
            } else {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (name.equals("steam_api.dll.orig") || name.equals("steam_api64.dll.orig")) {
                    try {
                        String originalName = file.getName().substring(0, file.getName().length() - ".orig".length());
                        File target = new File(file.getParent(), originalName);

                        if (target.exists()) target.delete();
                        if (file.length() == 0) {
                            // 0-byte .orig means delete the injected DLL.
                            Log.d(TAG, "Removed injected target " + originalName);
                        } else {
                            FileUtils.copy(file, target);
                        }

                        String stubName = name.equals("steam_api64.dll.orig")
                                ? "steamclient64.dll" : "steamclient.dll";
                        File stub = new File(file.getParent(), stubName);
                        if (stub.exists() && stub.length() < 200_000) {
                            stub.delete();
                            Log.d(TAG, "Removed steamclient stub " + stubName);
                        }

                        Log.d(TAG, "Restored original " + originalName + " from .orig backup");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to restore " + file.getName(), e);
                    }
                }
            }
        }
    }

    public void generateSteamInterfacesFile(File dir, String dllName) {
        File interfacesFile = new File(dir, "steam_interfaces.txt");
        if (interfacesFile.exists()) return;

        File dllToScan = new File(dir, dllName + ".orig");
        if (!dllToScan.exists()) {
            dllToScan = new File(dir, dllName + ".original");
        }
        if (!dllToScan.exists()) {
            dllToScan = new File(dir, dllName);
        }
        if (!dllToScan.exists()) return;

        generateSteamInterfacesFromDll(dir, dllToScan);
    }

    private static String getCanonicalPathOrAbsolute(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception e) {
            return file.getAbsolutePath();
        }
    }
}
