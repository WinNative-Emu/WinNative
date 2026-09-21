package com.winlator.cmod.runtime.display.steam;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.feature.stores.steam.utils.PrefManager;
import com.winlator.cmod.feature.stores.steam.utils.SteamUtils;
import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.container.Shortcut;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import com.winlator.cmod.runtime.wine.WineRegistryEditor;
import com.winlator.cmod.runtime.wine.WineUtils;
import com.winlator.cmod.shared.io.FileUtils;

import java.io.File;
import java.util.Locale;

/**
 * Steam prefix environment: ACF, libraryfolders, userdata, localconfig, Bionic bootstrap hooks.
 * Extracted from XServerDisplayActivity.setupSteamEnvironment and helpers.
 */
public final class SteamEnvironmentSetup {
    private static final String TAG = "SteamEnvironmentSetup";

    public interface Host {
        Context context();
        Container container();
        ImageFs imageFs();
        Shortcut shortcut();
        /** True when Bionic/native steam client path is enabled for this launch. */
        boolean isBionicSteamEnabledForShortcut();
        /** Canonical install dir name for steamapps/common symlink. */
        String canonicalSteamInstallDir(int appId);
    }

    private final Host host;
    private final Context context;

    public SteamEnvironmentSetup(Host host) {
        this.host = host;
        this.context = host.context().getApplicationContext();
    }

    public void setupSteamEnvironment(int appId, File gameDir) {
        try {
            File winePrefix = host.container().getRootDir();
            File steamDir = new File(winePrefix, ".wine/drive_c/Program Files (x86)/Steam");
            steamDir.mkdirs();

            File steamappsDir = new File(steamDir, "steamapps");
            File commonDir = new File(steamappsDir, "common");
            commonDir.mkdirs();
            WineUtils.ensureSteamappsCommonSymlink(host.container(), gameDir.getAbsolutePath(),
                    host.canonicalSteamInstallDir(appId));

            String acfLanguage = PrefManager.INSTANCE.getContainerLanguage();
            String containerLang = host.container().getExtra("containerLanguage", null);
            if (containerLang != null && !containerLang.isEmpty()) {
                acfLanguage = containerLang;
            }
            SteamUtils.createAppManifest(context, appId, acfLanguage);

            File defaultAcf = new File(host.imageFs().getRootDir(),
                    ImageFs.WINEPREFIX + "/drive_c/Program Files (x86)/Steam/steamapps/appmanifest_" + appId + ".acf");
            File containerAcf = new File(steamappsDir, "appmanifest_" + appId + ".acf");
            // Refresh the container manifest from the freshly generated one on every launch so
            // newly installed DLC / language changes propagate. The generated manifest is the
            // source of truth (the native launcher rewrites this same file too), so a stale
            // container copy must not be left in place.
            if (defaultAcf.exists()) {
                try {
                    java.nio.file.Files.copy(defaultAcf.toPath(), containerAcf.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Log.d(TAG, "Synced ACF manifest to container steamapps dir");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to copy ACF to container steamapps", e);
                }
            }

            ensureSteamLibraryFoldersConfig(steamDir, steamappsDir);

            File steamworksAcf = new File(steamappsDir, "appmanifest_228980.acf");
            if (!steamworksAcf.exists()) {
                String steamworksAcfContent = "\"AppState\"\n" +
                        "{\n" +
                        "\t\"appid\"\t\t\"228980\"\n" +
                        "\t\"universe\"\t\t\"1\"\n" +
                        "\t\"name\"\t\t\"Steamworks Common Redistributables\"\n" +
                        "\t\"StateFlags\"\t\t\"4\"\n" +
                        "\t\"installdir\"\t\t\"Steamworks Shared\"\n" +
                        "\t\"buildid\"\t\t\"1\"\n" +
                        "\t\"BytesToDownload\"\t\t\"0\"\n" +
                        "\t\"BytesDownloaded\"\t\t\"0\"\n" +
                        "}\n";
                FileUtils.writeString(steamworksAcf, steamworksAcfContent);
            }

            long steamIdLong = com.winlator.cmod.feature.stores.steam.utils.PrefManager.INSTANCE.getSteamUserSteamId64();
            String steamId64 = steamIdLong > 0 ? String.valueOf(steamIdLong) : "76561198000000000";
            int steamAccountId = com.winlator.cmod.feature.stores.steam.utils.PrefManager.INSTANCE.getSteamUserAccountId();
            String steamUserDataId = steamAccountId > 0 ? String.valueOf(steamAccountId) : steamId64;

            // Stamp-cache the registry/userdata/local-config edits so warm launches skip the per-launch file-copy / VDF-parse work. Stamp key appId|userDataId — change either and it re-runs.
            File steamEnvStamp = new File(winePrefix,
                    ".wine/drive_c/.wn-steamenv-" + appId + "-" + steamUserDataId + ".stamp");
            String expectedStamp = "v1|" + appId + "|" + steamUserDataId;
            String existingStamp = steamEnvStamp.exists()
                    ? FileUtils.readString(steamEnvStamp).trim() : "";
            boolean steamEnvWarm = expectedStamp.equals(existingStamp);

            if (!steamEnvWarm) {
                try {
                    SteamUtils.autoLoginUserChanges(imageFs);
                    Log.d(TAG, "autoLoginUserChanges complete");
                } catch (Exception e) {
                    Log.w(TAG, "autoLoginUserChanges failed, falling back", e);
                }

                skipFirstTimeSteamSetup(winePrefix);
                reconcileSteamUserdata(steamDir, steamUserDataId, steamId64);
                SteamUtils.updateOrModifyLocalConfig(host.imageFs(), container, String.valueOf(appId), steamUserDataId);
                setupLightweightSteamConfig(steamDir, steamUserDataId);

                try {
                    FileUtils.writeString(steamEnvStamp, expectedStamp);
                } catch (Exception e) {
                    Log.w(TAG,
                            "Failed to write steam-env stamp at " + steamEnvStamp.getPath(), e);
                }
            } else {
                Log.d(TAG,
                        "Steam env warm-cache hit (appId=" + appId
                                + ", userId=" + steamUserDataId + ") — skipping reconcile + autoLogin");
            }

            boolean planWActiveBootstrapSkip = com.winlator.cmod.feature.stores.steam.utils
                    .PrefManager.INSTANCE.getWnPlanW();
            if (host.isBionicSteamEnabledForShortcut() && planWActiveBootstrapSkip) {
                try {
                    boolean kicked = com.winlator.cmod.feature.stores.steam.service.SteamService
                            .Companion.kickPlayingSessionIfReadyBlocking(true);
                    Log.i(TAG,
                            "Steam Launcher: pre-launch kickPlayingSessionIfReady fired="
                                    + kicked);
                } catch (Throwable t) {
                    Log.w(TAG,
                            "Steam Launcher: pre-launch kickPlayingSessionIfReady failed", t);
                }
                try {
                    com.winlator.cmod.feature.stores.steam.service.SteamService
                            .Companion.bionicHandoffAcquire();
                    Log.i(TAG,
                            "Steam Launcher: suspended Android wn-session before PlanW launch");
                } catch (Throwable t) {
                    Log.w(TAG,
                            "Steam Launcher: failed to suspend Android wn-session", t);
                }
                Log.i(TAG,
                        "Steam Launcher: skipping Android-side WnSteamBootstrap + stage2 "
                        + "diagnostics — Wine-side steam.exe is the sole Steam "
                        + "session (avoids double-logon + the listAchievements "
                        + "native crash)");
            } else if (host.isBionicSteamEnabledForShortcut()) {
                try {
                    boolean staged = com.winlator.cmod.feature.stores.steam.wnsteam
                            .WnSteamAssetsInstaller.INSTANCE.install(context, host.container());
                    File libSteamClientSo =
                            new File(host.imageFs().getRootDir(), "usr/lib/libsteamclient.so");
                    Log.d(TAG,
                            "Bionic Steam bootstrap: staged=" + staged
                                    + " libsteamclient.so exists=" + libSteamClientSo.exists()
                                    + " (" + libSteamClientSo.getAbsolutePath() + ")");
                    if (libSteamClientSo.exists()) {
                        String bsAccount = com.winlator.cmod.feature.stores.steam.utils
                                .PrefManager.INSTANCE.getUsername();
                        String bsToken = com.winlator.cmod.feature.stores.steam.utils
                                .PrefManager.INSTANCE.getRefreshToken();
                        long bsSteamId = com.winlator.cmod.feature.stores.steam.utils
                                .PrefManager.INSTANCE.getSteamUserSteamId64();
                        File bsHome = new File(host.imageFs().getRootDir(), "home");
                        Log.d(TAG,
                                "Bionic Steam bootstrap: account=" + bsAccount
                                        + " tokenLen="
                                        + (bsToken == null ? 0 : bsToken.length())
                                        + " steamId=" + bsSteamId);
                        int rc = com.winlator.cmod.feature.stores.steam.wnsteam
                                .WnSteamBootstrap.INSTANCE.start(
                                        context,
                                        libSteamClientSo.getAbsolutePath(),
                                        bsHome.getAbsolutePath(),
                                        "127.0.0.1:57343",
                                        "127.0.0.1:57344",
                                        new String[0],
                                        bsAccount,
                                        bsToken,
                                        bsSteamId,
                                        appId);
                        Log.d(TAG,
                                "Bionic Steam bootstrap: start() rc=" + rc
                                        + " appId=" + appId);
                        com.winlator.cmod.feature.stores.steam.wnsteam
                                .WnLibSteamClient.INSTANCE.setAppId(appId);
                        try {
                            com.winlator.cmod.feature.stores.steam.service.SteamService
                                    .prepareLibSteamClientForLaunchBlocking(appId);
                        } catch (Throwable t) {
                            Log.w(TAG,
                                    "Bionic Steam: prepareLibSteamClientForLaunch failed for app "
                                            + appId, t);
                        }
                        com.winlator.cmod.feature.stores.steam.wnsteam.WnSteamBootstrap bs =
                                com.winlator.cmod.feature.stores.steam.wnsteam.WnSteamBootstrap.INSTANCE;
                        long liveSid = bs.liveSteamId();
                        int  liveApp = bs.currentAppId();
                        Log.d(TAG,
                                "Bionic Steam bootstrap: live ISteamUser.steamId="
                                        + liveSid + " (prefmgr=" + bsSteamId
                                        + " match=" + (liveSid == bsSteamId)
                                        + ") ISteamUtils.appId=" + liveApp);

                        try {
                            boolean subscribed = bs.isSubscribedApp(appId);
                            int     license   = bs.userHasLicenseForApp(liveSid, appId);
                            boolean installed = bs.isAppInstalled(appId);
                            String  installDir = bs.appInstallDir(appId);
                            int[]   depots    = bs.installedDepots(appId);
                            String  lang      = bs.currentGameLanguage();
                            boolean publicLogged = bs.loggedOnPublic();
                            Log.d(TAG,
                                    "Bionic stage2 apps/user: subscribed=" + subscribed
                                            + " license=" + license + " (0=ok 1=no 2=noauth)"
                                            + " installed=" + installed
                                            + " installDir=" + installDir
                                            + " depots=" + (depots == null ? 0 : depots.length)
                                            + " lang=" + lang
                                            + " loggedOnPublic=" + publicLogged);

                            boolean cloudAcct = bs.cloudEnabledForAccount();
                            boolean cloudApp  = bs.cloudEnabledForApp();
                            int     cloudCnt  = bs.cloudFileCount();
                            long[]  cloudQ    = bs.cloudQuota();
                            Log.d(TAG,
                                    "Bionic stage2 cloud: account=" + cloudAcct
                                            + " app=" + cloudApp
                                            + " files=" + cloudCnt
                                            + " quota=" + cloudQ[1] + "/" + cloudQ[0]);

                            int numAch = bs.numAchievements();
                            java.util.List<String> achNames = bs.listAchievements();
                            String firstAch = achNames.isEmpty() ? "(none)" : achNames.get(0);
                            Log.d(TAG,
                                    "Bionic stage2 stats: numAch=" + numAch
                                            + " firstName=" + firstAch);

                            String  pname  = bs.personaName();
                            int     pstate = bs.personaState();
                            int     fcount = bs.friendCount(
                                    com.winlator.cmod.feature.stores.steam.wnsteam
                                            .WnSteamBootstrap.FriendFlags.Immediate);
                            Log.d(TAG,
                                    "Bionic stage2 friends: personaName=" + pname
                                            + " personaState=" + pstate
                                            + " friendCount(immediate)=" + fcount);

                            int  purchaseTime = bs.earliestPurchaseUnixTime(appId);
                            int  numDlc       = bs.dlcCount(appId);
                            long owner        = bs.appOwner();
                            boolean famShared = bs.isSubscribedFromFamilySharing();
                            Log.d(TAG,
                                    "Bionic stage2 perApp: earliestPurchase=" + purchaseTime
                                            + " dlcCount=" + numDlc
                                            + " appOwner=" + owner
                                            + " (familySharing=" + famShared + ")");
                        } catch (Throwable t) {
                            Log.w(TAG,
                                    "Bionic stage2 diagnostic failed", t);
                        }
                    } else {
                        Log.w(TAG,
                                "Bionic Steam bootstrap: libsteamclient.so missing, "
                                        + "skipping nativeInit");
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Bionic Steam bootstrap failed", t);
                }
            }

            Log.d(TAG, "Steam environment setup complete for appId=" + appId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup Steam environment", e);
        }
    }

    public void setupLightweightSteamConfig(File steamDir, String steamId64) {
        try {
            File userDataPath = new File(steamDir, "userdata/" + steamId64);
            File configPath = new File(userDataPath, "config");
            File remotePath = new File(userDataPath, "7/remote");
            configPath.mkdirs();
            remotePath.mkdirs();

            File localConfigFile = new File(configPath, "localconfig.vdf");
            if (!localConfigFile.exists()) {
                String localConfigContent = "\"UserLocalConfigStore\"\n" +
                        "{\n" +
                        "  \"Software\"\n" +
                        "  {\n" +
                        "    \"Valve\"\n" +
                        "    {\n" +
                        "      \"Steam\"\n" +
                        "      {\n" +
                        "        \"SmallMode\"                      \"1\"\n" +
                        "        \"LibraryDisableCommunityContent\" \"1\"\n" +
                        "        \"LibraryLowBandwidthMode\"        \"1\"\n" +
                        "        \"LibraryLowPerfMode\"             \"1\"\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "  \"friends\"\n" +
                        "  {\n" +
                        "    \"SignIntoFriends\" \"0\"\n" +
                        "  }\n" +
                        "}\n";
                FileUtils.writeString(localConfigFile, localConfigContent);
            }

            File sharedConfigFile = new File(remotePath, "sharedconfig.vdf");
            if (!sharedConfigFile.exists()) {
                String sharedConfigContent = "\"UserRoamingConfigStore\"\n" +
                        "{\n" +
                        "  \"Software\"\n" +
                        "  {\n" +
                        "    \"Valve\"\n" +
                        "    {\n" +
                        "      \"Steam\"\n" +
                        "      {\n" +
                        "        \"SteamDefaultDialog\" \"#app_games\"\n" +
                        "        \"FriendsUI\"\n" +
                        "        {\n" +
                        "          \"FriendsUIJSON\" \"{\\\"bSignIntoFriends\\\":false,\\\"bAnimatedAvatars\\\":false,\\\"PersonaNotifications\\\":0,\\\"bDisableRoomEffects\\\":true}\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}\n";
                FileUtils.writeString(sharedConfigFile, sharedConfigContent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to setup lightweight Steam configuration", e);
        }
    }

    public void reconcileSteamUserdata(File steamDir, String steamUserDataId, String steamId64) {
        if (steamDir == null || !steamDir.exists() || steamUserDataId == null || steamUserDataId.isEmpty()) {
            return;
        }

        File userdataDir = new File(steamDir, "userdata");
        if (!userdataDir.exists()) userdataDir.mkdirs();

        File activeUserDir = new File(userdataDir, steamUserDataId);
        if (!activeUserDir.exists()) activeUserDir.mkdirs();

        String fallbackUserId = "76561198000000000";
        if (fallbackUserId.equals(steamUserDataId) || fallbackUserId.equals(steamId64)) {
            return;
        }

        File staleUserDir = new File(userdataDir, fallbackUserId);
        if (!staleUserDir.exists()) {
            return;
        }

        try {
            File staleLocalConfig = new File(staleUserDir, "config/localconfig.vdf");
            File activeLocalConfig = new File(activeUserDir, "config/localconfig.vdf");
            if (staleLocalConfig.exists() && !activeLocalConfig.exists()) {
                activeLocalConfig.getParentFile().mkdirs();
                FileUtils.copy(staleLocalConfig, activeLocalConfig);
            }

            File staleSharedConfig = new File(staleUserDir, "7/remote/sharedconfig.vdf");
            File activeSharedConfig = new File(activeUserDir, "7/remote/sharedconfig.vdf");
            if (staleSharedConfig.exists() && !activeSharedConfig.exists()) {
                activeSharedConfig.getParentFile().mkdirs();
                FileUtils.copy(staleSharedConfig, activeSharedConfig);
            }

            if (FileUtils.delete(staleUserDir)) {
                Log.d(TAG,
                        "Removed stale fallback Steam userdata profile " + fallbackUserId + " in favor of " + steamUserDataId);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to reconcile stale Steam userdata", e);
        }
    }

    public void ensureSteamLibraryFoldersConfig(File steamDir, File steamappsDir) {
        if (steamDir == null || steamappsDir == null) {
            return;
        }

        try {
            File configDir = new File(steamDir, "config");
            if (!configDir.exists()) {
                configDir.mkdirs();
            }

            java.util.Set<String> installedAppIds = new java.util.TreeSet<>();
            File[] manifests = steamappsDir.listFiles((dir, name) ->
                    name != null && name.startsWith("appmanifest_") && name.endsWith(".acf"));
            if (manifests != null) {
                for (File manifest : manifests) {
                    String name = manifest.getName();
                    String appId = name.substring("appmanifest_".length(), name.length() - ".acf".length());
                    if (!appId.isEmpty()) {
                        installedAppIds.add(appId);
                    }
                }
            }

            StringBuilder content = new StringBuilder();
            content.append("\"libraryfolders\"\n");
            content.append("{\n");
            content.append("\t\"0\"\n");
            content.append("\t{\n");
            content.append("\t\t\"path\"\t\t\"C:\\\\Program Files (x86)\\\\Steam\"\n");
            content.append("\t\t\"label\"\t\t\"\"\n");
            content.append("\t\t\"contentid\"\t\t\"0\"\n");
            content.append("\t\t\"totalsize\"\t\t\"0\"\n");
            content.append("\t\t\"update_clean_bytes_tally\"\t\t\"0\"\n");
            content.append("\t\t\"time_last_update_verified\"\t\t\"")
                    .append(System.currentTimeMillis() / 1000L)
                    .append("\"\n");
            content.append("\t\t\"apps\"\n");
            content.append("\t\t{\n");
            for (String appId : installedAppIds) {
                content.append("\t\t\t\"").append(appId).append("\"\t\t\"0\"\n");
            }
            content.append("\t\t}\n");
            content.append("\t}\n");
            content.append("}\n");

            File libraryFolders = new File(configDir, "libraryfolders.vdf");
            FileUtils.writeString(libraryFolders, content.toString());
            Log.d(TAG, "Updated Steam libraryfolders.vdf with " + installedAppIds.size() + " app(s)");
        } catch (Exception e) {
            Log.w(TAG, "Failed to update Steam libraryfolders.vdf", e);
        }
    }

    public void copySteamRuntimeIntoGameDir(File gameDir) {
        File gameSteamDir = new File(gameDir, "Steam");
        if (gameSteamDir.exists()) {
            return;
        }

        try {
            gameSteamDir.mkdirs();
            File steamDirSrc = new File(host.container().getRootDir(), ".wine/drive_c/Program Files (x86)/Steam");
            File[] steamChildren = steamDirSrc.listFiles();
            if (steamChildren != null) {
                for (File child : steamChildren) {
                    String name = child.getName().toLowerCase(Locale.ROOT);
                    if (name.equals("dumps") || name.equals("steamapps") || name.equals("userdata")) continue;

                    File targetChild = new File(gameSteamDir, child.getName());
                    com.winlator.cmod.shared.io.FileUtils.copy(child, targetChild);
                }
            }
            Log.d(TAG, "Physically copied Steam client files to " + gameSteamDir.getAbsolutePath());
        } catch (Exception copyEx) {
            Log.e(TAG, "Failed to copy Steam client files to game dir", copyEx);
        }
    }

    public void cleanupEmbeddedSteamRuntime(File gameDir) {
        File embeddedSteamDir = new File(gameDir, "Steam");
        if (!embeddedSteamDir.exists() || !embeddedSteamDir.isDirectory()) {
            return;
        }

        boolean looksLikeCopiedSteamRuntime =
                new File(embeddedSteamDir, "steam.exe").exists()
                || new File(embeddedSteamDir, "steamclient.dll").exists()
                || new File(embeddedSteamDir, "steamclient_loader_x64.exe").exists()
                || new File(embeddedSteamDir, "ColdClientLoader.ini").exists();
        if (!looksLikeCopiedSteamRuntime) {
            return;
        }

        try {
            if (FileUtils.delete(embeddedSteamDir)) {
                Log.d(TAG, "Removed embedded Steam runtime from game directory " + embeddedSteamDir.getAbsolutePath());
            } else {
                Log.w(TAG, "Failed to remove embedded Steam runtime from game directory " + embeddedSteamDir.getAbsolutePath());
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to remove embedded Steam runtime", e);
        }
    }

    public void skipFirstTimeSteamSetup(File containerDir) {
        File systemRegFile = new File(containerDir, ".wine/system.reg");
        if (!systemRegFile.exists()) return;

        String[][] redistributables = {
            {"DirectX\\Jun2010", "DXSetup"},
            {".NET\\3.5", "3.5 SP1"},
            {".NET\\3.5 Client Profile", "3.5 Client Profile SP1"},
            {".NET\\4.0", "4.0"},
            {".NET\\4.0 Client Profile", "4.0 Client Profile"},
            {".NET\\4.5.1", "4.5.1"},
            {".NET\\4.5.2", "4.5.2"},
            {".NET\\4.6", "4.6"},
            {".NET\\4.6.1", "4.6.1"},
            {".NET\\4.6.2", "4.6.2"},
            {".NET\\4.7", "4.7"},
            {".NET\\4.7.1", "4.7.1"},
            {".NET\\4.7.2", "4.7.2"},
            {".NET\\4.8", "4.8"},
            {".NET\\4.8.1", "4.8.1"},
            {"XNA\\3.0", "3.0"},
            {"XNA\\3.1", "3.1"},
            {"XNA\\4.0", "4.0"},
            {"OpenAL\\2.0.7.0", "2.0.7.0"},
        };

        try (WineRegistryEditor reg = new WineRegistryEditor(systemRegFile)) {
            for (String[] entry : redistributables) {
                String regPath = "Software\\Valve\\Steam\\Apps\\CommonRedist\\" + entry[0];
                String regPathWow = "Software\\Wow6432Node\\Valve\\Steam\\Apps\\CommonRedist\\" + entry[0];
                reg.setDwordValue(regPath, entry[1], 1);
                reg.setDwordValue(regPathWow, entry[1], 1);
            }
            Log.d(TAG, "Marked " + redistributables.length + " redistributables as installed");
        } catch (Exception e) {
            Log.w(TAG, "Failed to set redistributable registry entries", e);
        }
    }


}
