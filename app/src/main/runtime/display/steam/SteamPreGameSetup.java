package com.winlator.cmod.runtime.display.steam;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.feature.stores.steam.SteamClientManager;
import com.winlator.cmod.feature.stores.steam.enums.Marker;
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils;
import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.container.Shortcut;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import com.winlator.cmod.runtime.display.environment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.runtime.wine.WineInfo;
import com.winlator.cmod.runtime.wine.WineUtils;
import com.winlator.cmod.shared.io.FileUtils;
import com.winlator.cmod.shared.io.TarCompressorUtils;

import java.io.File;
import java.util.Locale;

/**
 * Pre-game setup: Mono, Gecko, redistributables, Steamless DRM unpack.
 * Extracted from XServerDisplayActivity.
 */
public final class SteamPreGameSetup {
    private static final String TAG = "SteamPreGameSetup";

    public interface Host {
        Context context();
        Container container();
        Shortcut shortcut();
        ImageFs imageFs();
        WineInfo wineInfo();
        String host.resolveSteamGameInstallPath(int appId);
        String host.resolveShortcutSteamExecutablePath(String gameInstallPath);
        boolean steamCloudHandledByAgent();
    }

    private final Host host;
    private final Context context;

    public SteamPreGameSetup(Host host) {
        this.host = host;
        this.context = host.context().getApplicationContext();
    }

    private static final class SteamExecutableInfo {
        final String relativePath;
        final File file;
        SteamExecutableInfo(String relativePath, File file) {
            this.relativePath = relativePath;
            this.file = file;
        }
    }

    public void runPreGameSetup(GuestProgramLauncherComponent launcher,
                                  boolean needsUnpacking, boolean unpackFiles) {
        boolean monoReady = installMonoIfNeeded(launcher);

        installGeckoIfNeeded(launcher);

        installRedistributablesIfNeeded(launcher);

        if (!unpackFiles) {
            Log.d(TAG,
                    "Skipping Steamless: 'Unpack Files' shortcut toggle is OFF");
            return;
        }
        if (!monoReady) {
            Log.w(TAG, "Skipping Steamless — Mono not installed yet, will retry next launch");
            return;
        }
        if (isSteamUnpackAlreadyHandled()) {
            Log.d(TAG, "Skipping Steamless/unpack check; executable already handled");
            return;
        }
        if (doesUnpackedExeExist()) {
            ensureUnpackedExeActive();
        } else {
            runSteamlessOnExe(launcher);
        }
    }

    public boolean isSteamUnpackAlreadyHandled() {
        if (host.shortcut() == null || !"STEAM".equals(host.shortcut().getExtra("game_source"))) return false;
        try {
            int appId = Integer.parseInt(host.shortcut().getExtra("app_id"));
            String gameInstallPath = host.resolveSteamGameInstallPath(appId);
            if (gameInstallPath == null || gameInstallPath.isEmpty()) return false;

            SteamExecutableInfo executableInfo = resolveSteamExecutableInfo(appId, gameInstallPath);
            if (executableInfo == null) return false;

            File unpackedExe = new File(gameInstallPath, executableInfo.relativePath + ".unpacked.exe");
            File originalExe = new File(gameInstallPath, executableInfo.relativePath + ".original.exe");
            if (MarkerUtils.INSTANCE.hasMarker(gameInstallPath, Marker.STEAM_DRM_PATCHED)
                    && unpackedExe.exists()
                    && originalExe.exists()) {
                ensureUnpackedExeActive();
                return true;
            }

            File checkedMarker = new File(gameInstallPath, Marker.STEAM_DRM_UNPACK_CHECKED.getFileName());
            String expectedSignature = buildSteamUnpackSignature(executableInfo);
            String actualSignature = checkedMarker.exists() ? FileUtils.readString(checkedMarker) : null;
            return expectedSignature.equals(actualSignature);
        } catch (Exception e) {
            Log.w(TAG, "Steamless handled-state check failed", e);
            return false;
        }
    }

    public void markSteamUnpackChecked(int appId, String gameInstallPath, String executablePath) {
        try {
            File exe = new File(gameInstallPath, executablePath.replace('\\', '/'));
            if (!exe.exists()) return;
            SteamExecutableInfo executableInfo =
                    new SteamExecutableInfo(executablePath.replace('\\', '/'), exe);
            File checkedMarker = new File(gameInstallPath, Marker.STEAM_DRM_UNPACK_CHECKED.getFileName());
            FileUtils.writeString(checkedMarker, buildSteamUnpackSignature(executableInfo));
        } catch (Exception e) {
            Log.w(TAG, "Failed to write Steamless checked marker", e);
        }
    }

    private SteamExecutableInfo resolveSteamExecutableInfo(int appId, String gameInstallPath) {
        String executablePath = host.resolveShortcutSteamExecutablePath(gameInstallPath);
        if (executablePath == null || executablePath.isEmpty()) {
            executablePath = host.container().getExecutablePath();
        }
        if (executablePath == null || executablePath.isEmpty()) {
            executablePath = com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.getInstalledExe(appId);
        }
        if (executablePath == null || executablePath.isEmpty()) return null;

        String relativePath = executablePath.replace('\\', '/');
        File exe = new File(gameInstallPath, relativePath);
        if (!exe.exists()) return null;
        return new SteamExecutableInfo(relativePath, exe);
    }

    public String buildSteamUnpackSignature(SteamExecutableInfo executableInfo) {
        return executableInfo.relativePath + "\n"
                + executableInfo.file.length() + "\n"
                + executableInfo.file.lastModified() + "\n";
    }

    public void ensureUnpackedExeActive() {
        if (host.shortcut() == null || !"STEAM".equals(host.shortcut().getExtra("game_source"))) return;
        try {
            int appId = Integer.parseInt(host.shortcut().getExtra("app_id"));
            String gameInstallPath = host.resolveSteamGameInstallPath(appId);
            if (gameInstallPath == null || gameInstallPath.isEmpty()) return;

            String executablePath = host.resolveShortcutSteamExecutablePath(gameInstallPath);
            if (executablePath == null || executablePath.isEmpty()) {
                executablePath = host.container().getExecutablePath();
            }
            if (executablePath == null || executablePath.isEmpty()) {
                executablePath = com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.getInstalledExe(appId);
            }
            if (executablePath == null || executablePath.isEmpty()) return;

            String unixPath = executablePath.replace('\\', '/');
            File exe = new File(gameInstallPath, unixPath);
            File unpackedExe = new File(gameInstallPath, unixPath + ".unpacked.exe");
            File originalExe = new File(gameInstallPath, unixPath + ".original.exe");

            // Mode switches can restore the original; file-size checks are unreliable here.
            if (unpackedExe.exists() && originalExe.exists()) {
                java.nio.file.Files.copy(unpackedExe.toPath(), exe.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Log.d(TAG, "Restored unpacked exe (was reverted by mode switch)");
            }
        } catch (Exception e) {
            Log.w(TAG, "ensureUnpackedExeActive failed", e);
        }
    }

    public boolean doesUnpackedExeExist() {
        if (host.shortcut() == null || !"STEAM".equals(host.shortcut().getExtra("game_source"))) return false;
        try {
            int appId = Integer.parseInt(host.shortcut().getExtra("app_id"));
            String gameInstallPath = host.resolveSteamGameInstallPath(appId);
            if (gameInstallPath == null || gameInstallPath.isEmpty()) return false;

            String executablePath = host.resolveShortcutSteamExecutablePath(gameInstallPath);
            if (executablePath == null || executablePath.isEmpty()) {
                executablePath = host.container().getExecutablePath();
            }
            if (executablePath == null || executablePath.isEmpty()) {
                executablePath = com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.getInstalledExe(appId);
            }
            if (executablePath == null || executablePath.isEmpty()) return false;

            String unixPath = executablePath.replace('\\', '/');
            File unpackedExe = new File(gameInstallPath, unixPath + ".unpacked.exe");
            return unpackedExe.exists();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean installMonoIfNeeded(GuestProgramLauncherComponent launcher) {
        // Any installed Mono is kept as-is; prefix repair clears the marker to force a reinstall.
        String installedVersion = host.container().getExtra("mono_version", null);
        if (installedVersion != null) {
            Log.d(TAG, "Mono v" + installedVersion + " already installed in container " + host.container().id + ", skipping");
            return true;
        }
        if (hasInstalledComponentPrefix("mono")) {
            Log.d(TAG, "Mono already installed via components in container " + host.container().id + ", skipping");
            return true;
        }

        String winePath = host.wineInfo() != null ? host.wineInfo().path : null;

        String requiredVersion = SteamClientManager.detectRequiredMonoVersion(context, winePath);
        if (requiredVersion == null) {
            Log.w(TAG, "Could not detect required Mono version, skipping");
            return false;
        }

        String monoWinePath = SteamClientManager.getMonoMsiWinePath(context, winePath);
        if (monoWinePath == null) {
            Log.w(TAG, "Mono MSI not available (no internet?), will retry next launch");
            return false;
        }

        // The MSI actually resolved may be a fallback version; record what really got installed.
        String actualVersion = requiredVersion;
        java.util.regex.Matcher monoMsiMatcher =
                java.util.regex.Pattern.compile("wine-mono-(\\d+\\.\\d+\\.\\d+)").matcher(monoWinePath);
        if (monoMsiMatcher.find()) actualVersion = monoMsiMatcher.group(1);
        if (!actualVersion.equals(requiredVersion)) {
            Log.w(TAG, "Mono fallback: required v" + requiredVersion
                    + " but installing v" + actualVersion + " (" + monoWinePath + ")");
        }

        try {
            Log.d(TAG, "Installing Wine Mono v" + actualVersion
                    + " (" + monoWinePath + ") in container " + host.container().id + "...");
            String monoCmd = "wine msiexec /i " + monoWinePath + " && wineserver -k";
            launcher.execShellCommand(monoCmd);
            host.container().putExtra("mono_installed", "true");
            host.container().putExtra("mono_version", actualVersion);
            host.container().saveData();
            Log.d(TAG, "Mono v" + actualVersion + " installed in container " + host.container().id);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Mono msiexec failed, will retry next launch", e);
            return false;
        }
    }

    public boolean hasInstalledComponentPrefix(String prefix) {
        for (String name : com.winlator.cmod.runtime.content.component.ComponentInstaller
                .installedComponents(host.container())) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    public void installGeckoIfNeeded(GuestProgramLauncherComponent launcher) {
        String installedGecko = host.container().getExtra("gecko_version", null);
        if (installedGecko != null) {
            Log.d(TAG, "Gecko v" + installedGecko + " already installed in container "
                    + host.container().id + ", skipping");
            return;
        }
        if (hasInstalledComponentPrefix("gecko")) {
            Log.d(TAG, "Gecko already installed via components in container "
                    + host.container().id + ", skipping");
            return;
        }
        String geckoVersion = SteamClientManager.GECKO_VERSION;

        java.util.List<String> geckoWinePaths = SteamClientManager.getGeckoMsiWinePaths(context);
        if (geckoWinePaths.size() < 2) {
            Log.w(TAG, "Gecko MSIs not available (no internet?), will retry next launch");
            return;
        }

        try {
            Log.d(TAG, "Installing Wine Gecko v" + geckoVersion
                    + " in container " + host.container().id + "...");
            StringBuilder geckoCmd = new StringBuilder();
            for (String p : geckoWinePaths) {
                if (geckoCmd.length() > 0) geckoCmd.append(" && ");
                geckoCmd.append("wine msiexec /i ").append(p);
            }
            geckoCmd.append(" && wineserver -k");
            launcher.execShellCommand(geckoCmd.toString());
            host.container().putExtra("gecko_version", geckoVersion);
            host.container().saveData();
            Log.d(TAG, "Gecko v" + geckoVersion + " installed in container " + host.container().id);
        } catch (Exception e) {
            Log.w(TAG, "Gecko msiexec failed, will retry next launch", e);
        }
    }

    // Installs _CommonRedist once per game/container.
    public void installRedistributablesIfNeeded(GuestProgramLauncherComponent launcher) {
        if (host.shortcut() == null || !"STEAM".equals(host.shortcut().getExtra("game_source"))) return;
        if (host.steamCloudHandledByAgent()) {
            Log.i(TAG,
                    "Redistributables skipped here — the Steam Launcher agent runs the app's "
                            + "installscript.vdf entries inside the prefix, honours their "
                            + "hasrunkey, and reads installer exit codes");
            return;
        }

        int appId;
        try {
            appId = Integer.parseInt(host.shortcut().getExtra("app_id"));
        } catch (Exception e) {
            return;
        }

        String redistKey = "redist_" + appId;
        String redistInstalled = host.container().getExtra(redistKey, "false");
        if ("true".equals(redistInstalled)) {
            Log.d(TAG, "Redistributables for appId=" + appId
                    + " already installed in container " + host.container().id + ", skipping");
            return;
        }

        String gameInstallPath = host.resolveSteamGameInstallPath(appId);
        if (gameInstallPath == null || gameInstallPath.isEmpty()) return;

        File commonRedistDir = new File(gameInstallPath, "_CommonRedist");
        if (!commonRedistDir.exists() || !commonRedistDir.isDirectory()) {
            Log.d(TAG, "No _CommonRedist found for appId=" + appId
                    + " at " + commonRedistDir.getPath());
            host.container().putExtra(redistKey, "true");
            host.container().saveData();
            return;
        }

        Log.d(TAG, "Installing redistributables for appId=" + appId
                + " in container " + host.container().id + "...");

        int installed = 0;
        try {
            File[] categories = commonRedistDir.listFiles();
            if (categories != null) {
                for (File category : categories) {
                    if (!category.isDirectory()) continue;
                    File[] versions = category.listFiles();
                    if (versions == null) continue;
                    for (File versionDir : versions) {
                        if (!versionDir.isDirectory()) continue;
                        File[] exes = versionDir.listFiles((dir, name) ->
                                name.toLowerCase(Locale.ROOT).endsWith(".exe"));
                        if (exes == null || exes.length == 0) continue;

                        for (File exe : exes) {
                            String exeName = exe.getName().toLowerCase(Locale.ROOT);
                            if (exeName.startsWith("unins") || exeName.equals("detect.exe")) continue;

                            String winPath = WineUtils.getWindowsPath(host.container(), exe.getAbsolutePath());

                            try {
                                Log.d(TAG, "Running redistributable: " + winPath);
                                String cmd;
                                if (exeName.contains("dxsetup")) {
                                    cmd = "wine \"" + winPath + "\" /silent";
                                } else if (exeName.contains("vc_redist") || exeName.contains("vcredist")) {
                                    cmd = "wine \"" + winPath + "\" /quiet /norestart";
                                } else if (exeName.endsWith(".msi")) {
                                    cmd = "wine msiexec /i \"" + winPath + "\" /quiet /norestart";
                                } else {
                                    cmd = "wine \"" + winPath + "\" /quiet /norestart";
                                }
                                launcher.execShellCommand(cmd);
                                installed++;
                            } catch (Exception e) {
                                Log.w(TAG,
                                        "Redistributable install failed: " + winPath, e);
                            }
                        }
                    }
                }
            }

            if (installed > 0) {
                try {
                    launcher.execShellCommand("wineserver -k");
                } catch (Exception e) {
                    Log.w(TAG, "wineserver -k failed after redist install", e);
                }
            }

            Log.d(TAG, "Installed " + installed
                    + " redistributable(s) for appId=" + appId + " in container " + host.container().id);
        } catch (Exception e) {
            Log.e(TAG, "Redistributable installation failed", e);
        }

        host.container().putExtra(redistKey, "true");
        host.container().saveData();
    }

    public void runSteamlessOnExe(GuestProgramLauncherComponent launcher) {
        if (host.shortcut() == null || !"STEAM".equals(host.shortcut().getExtra("game_source"))) return;
        int appId;
        try {
            appId = Integer.parseInt(host.shortcut().getExtra("app_id"));
        } catch (Exception e) {
            Log.e(TAG, "Invalid app_id for Steamless", e);
            return;
        }

        String gameInstallPath = host.resolveSteamGameInstallPath(appId);
        if (gameInstallPath == null || gameInstallPath.isEmpty()) return;

        File steamlessDir = new File(host.imageFs().getRootDir(), "Steamless");
        File steamlessCli = new File(steamlessDir, "Steamless.CLI.exe");
        File pluginsDir = new File(steamlessDir, "Plugins");
        if (!steamlessCli.exists() || !pluginsDir.exists()) {
            try {
                steamlessDir.mkdirs();
                TarCompressorUtils.extract(
                        TarCompressorUtils.Type.ZSTD,
                        context, "extras.tzst", host.imageFs().getRootDir());
                com.winlator.cmod.shared.io.FileUtils.chmod(steamlessCli, 0755);
                Log.d(TAG, "Extracted Steamless CLI + Plugins to " + steamlessDir);
            } catch (Exception e) {
                Log.e(TAG, "Failed to extract Steamless", e);
                return;
            }
        }

        if (!pluginsDir.exists() || pluginsDir.list() == null || pluginsDir.list().length == 0) {
            Log.e(TAG, "Steamless Plugins/ directory is missing or empty — cannot unpack");
            return;
        }

        String executablePath = host.resolveShortcutSteamExecutablePath(gameInstallPath);
        if (executablePath == null || executablePath.isEmpty()) {
            executablePath = host.container().getExecutablePath();
        }
        if (executablePath == null || executablePath.isEmpty()) {
            executablePath = com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.getInstalledExe(appId);
        }
        if (executablePath == null || executablePath.isEmpty()) {
            Log.w(TAG, "No executable path found for Steamless");
            return;
        }

        File batchFile = null;
        try {
            File hostExe = new File(gameInstallPath, executablePath.replace('\\', '/'));

            String windowsPath = com.winlator.cmod.runtime.wine.WineUtils.getDriveCGameWindowsPath(
                    host.container(), "STEAM", gameInstallPath, hostExe.getAbsolutePath());
            if (windowsPath == null || windowsPath.isEmpty()) {
                windowsPath = com.winlator.cmod.runtime.wine.WineUtils.hostPathToRootWinePath(host.container(), hostExe.getAbsolutePath());
            }
            Log.d(TAG, "Steamless: resolved windowsPath=" + windowsPath
                    + " (hostExe=" + hostExe.getAbsolutePath() + ")");

            batchFile = new File(host.imageFs().getRootDir(), "tmp/steamless_wrapper.bat");
            batchFile.getParentFile().mkdirs();
            String batchContent = "@echo off\r\n"
                    + "z:\\Steamless\\Steamless.CLI.exe \"" + windowsPath + "\"\r\n"
                    + "echo STEAMLESS_EXIT_CODE=%ERRORLEVEL%\r\n";
            com.winlator.cmod.shared.io.FileUtils.writeString(batchFile, batchContent);

            Log.d(TAG, "Steamless: running on " + windowsPath + " (exe=" + executablePath + ")");
            String slCmd = "wine z:\\tmp\\steamless_wrapper.bat";
            String slOutput = launcher.execShellCommand(slCmd);
            Log.d(TAG, "Steamless CLI output: " + slOutput);

            boolean steamlessSuccess = slOutput != null
                    && slOutput.toLowerCase(Locale.ROOT).contains("successfully unpacked");

            String unixPath = executablePath.replace('\\', '/');
            File exe = new File(gameInstallPath, unixPath);
            File unpackedExe = new File(gameInstallPath, unixPath + ".unpacked.exe");
            File originalExe = new File(gameInstallPath, unixPath + ".original.exe");

            Log.d(TAG, "Steamless: checking exe=" + exe.getAbsolutePath()
                    + " exists=" + exe.exists() + " unpacked=" + unpackedExe.getAbsolutePath()
                    + " exists=" + unpackedExe.exists() + " cliSuccess=" + steamlessSuccess);

            if (steamlessSuccess && exe.exists() && unpackedExe.exists()) {
                if (!originalExe.exists()) {
                    java.nio.file.Files.copy(exe.toPath(), originalExe.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Log.d(TAG, "Steamless: backed up original exe as " + originalExe.getName());
                }
                java.nio.file.Files.copy(unpackedExe.toPath(), exe.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Log.d(TAG, "Steamless: swapped exe with unpacked version");

                com.winlator.cmod.feature.stores.steam.utils.MarkerUtils.INSTANCE.addMarker(
                        gameInstallPath, com.winlator.cmod.feature.stores.steam.enums.Marker.STEAM_DRM_PATCHED);

                launcher.execShellCommand("wineserver -k");
                host.container().setNeedsUnpacking(false);
                host.container().saveData();
            } else if (!steamlessSuccess && !unpackedExe.exists()) {
                // Stop retrying only when Steamless confirms no unpacker applies.
                boolean allUnpackersFailed = slOutput != null
                        && slOutput.toLowerCase(Locale.ROOT).contains("all unpackers failed");

                if (allUnpackersFailed) {
                    Log.w(TAG,
                            "Steamless: game does not use SteamStub DRM (all unpackers failed). "
                            + "Disabling Legacy DRM for this game to avoid future overhead.");
                    launcher.execShellCommand("wineserver -k");
                    markSteamUnpackChecked(appId, gameInstallPath, executablePath);
                    host.container().setNeedsUnpacking(false);
                    host.container().saveData();
                } else {
                    Log.w(TAG,
                            "Steamless: transient failure (CLI ran but no .unpacked.exe), will retry next launch");
                }
            } else if (!steamlessSuccess && unpackedExe.exists()) {
                if (!originalExe.exists() && exe.exists()) {
                    java.nio.file.Files.copy(exe.toPath(), originalExe.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                java.nio.file.Files.copy(unpackedExe.toPath(), exe.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Log.d(TAG, "Steamless: used existing .unpacked.exe from prior run");

                com.winlator.cmod.feature.stores.steam.utils.MarkerUtils.INSTANCE.addMarker(
                        gameInstallPath, com.winlator.cmod.feature.stores.steam.enums.Marker.STEAM_DRM_PATCHED);
                launcher.execShellCommand("wineserver -k");
                host.container().setNeedsUnpacking(false);
                host.container().saveData();
            }
        } catch (Exception e) {
            Log.e(TAG, "Steamless execution failed, will retry next launch", e);
        } finally {
            if (batchFile != null && batchFile.exists()) batchFile.delete();
        }
    }

    public XServer getXServer() {
        return xServer;
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
    

}
