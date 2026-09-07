package com.winlator.cmod.feature.stores.steam.utils
import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.winlator.cmod.feature.stores.steam.data.DepotInfo
import com.winlator.cmod.feature.stores.steam.data.ManifestInfo
import com.winlator.cmod.feature.stores.steam.enums.PathType
import com.winlator.cmod.feature.stores.steam.enums.SpecialGameSaveMapping
import com.winlator.cmod.feature.stores.steam.service.SteamBranchSelection
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.service.ensureFreshDepotData
import com.winlator.cmod.feature.stores.steam.service.getInstalledBuildId
import com.winlator.cmod.feature.stores.steam.service.readInstalledDepotManifestIds
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.getAppDirName
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.getAppInfoOf
import com.winlator.cmod.feature.stores.steam.utils.FileUtils
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.wine.WineUtils
import com.winlator.cmod.runtime.wine.WineRegistryEditor
import com.winlator.cmod.feature.stores.steam.enums.EOSType
import com.winlator.cmod.feature.stores.steam.enums.EPersonaState
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.getOwnedAppDlc
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

object SteamUtils {
    private fun coreSteamClientFiles(): Array<String> =
        arrayOf(
            "GameOverlayRenderer.dll",
            "GameOverlayRenderer64.dll",
            "steamclient.dll",
            "steamclient64.dll",
        )

    private fun steamClientFiles(): Array<String> =
        coreSteamClientFiles() +
            arrayOf(
                "steamclient_loader_x86.exe",
                "steamclient_loader_x64.exe",
            )

    @JvmStatic
    fun generateInterfacesFile(dllFile: File) {
        val outFile = File(dllFile.parent, "steam_interfaces.txt")
        if (outFile.exists()) return

        try {
            val bytes = Files.readAllBytes(dllFile.toPath())
            val strings = mutableSetOf<String>()

            val sb = StringBuilder()

            fun flush() {
                if (sb.length >= 10) {
                    val candidate = sb.toString()
                    if (candidate.matches(Regex("^Steam[A-Za-z]+[0-9]{3}\$", RegexOption.IGNORE_CASE))) {
                        strings += candidate
                    }
                }
                sb.setLength(0)
            }

            for (b in bytes) {
                val ch = b.toInt() and 0xFF
                if (ch in 0x20..0x7E) {
                    sb.append(ch.toChar())
                } else {
                    flush()
                }
            }
            flush()

            if (strings.isEmpty()) {
                Timber.w("No Steam interface strings found in \${dllFile.name}")
                return
            }

            val sorted = strings.sorted()
            Files.write(outFile.toPath(), sorted)
            Timber.i("Generated steam_interfaces.txt (\${sorted.size} interfaces) for \${dllFile.name}")
        } catch (e: Exception) {
            Timber.w(e, "Failed to generate steam_interfaces.txt from native scanning for \${dllFile.name}")
        }
    }

    // Back up steamclient artifacts before DRM/Steamless manipulation.
    @JvmStatic
    @JvmOverloads
    fun backupSteamclientFiles(
        context: Context,
        steamAppId: Int = -1,
    ) {
        val imageFs = ImageFs.find(context)
        var backupCount = 0
        val backupDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/steamclient_backup")
        backupDir.mkdirs()

        coreSteamClientFiles().forEach { file ->
            val dll = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/$file")
            val backupFile = File(backupDir, "$file.orig")
            if (dll.exists()) {
                // Refuse to replace a real-DLL backup with a small Goldberg stub.
                if (backupFile.exists() && backupFile.length() > dll.length() * 2 &&
                    backupFile.length() > 1_000_000) {
                    Timber.w(
                        "backupSteamclientFiles: refusing shrink of $file.orig " +
                            "(current=${dll.length()} existing backup=${backupFile.length()}) — " +
                            "current file looks like a stub, keeping previous backup",
                    )
                    return@forEach
                }
                Files.copy(dll.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                backupCount++
            }
        }

        val idLog = if (steamAppId >= 0) steamAppId.toString() else "unknown"
        Timber.i("backupSteamclientFiles complete (appId=$idLog, count=$backupCount)")
    }

    // True when the shared Steam store still has real DLLs, not stubs.
    @JvmStatic
    fun isSharedSteamStorePristine(context: Context): Boolean {
        val imageFs = ImageFs.find(context)
        val minBytes = 2_000_000L
        coreSteamClientFiles().forEach { file ->
            val dll = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/$file")
            if (!dll.exists()) {
                Timber.w("isSharedSteamStorePristine: $file missing from shared store")
                return false
            }
            if (dll.length() < minBytes) {
                Timber.w(
                    "isSharedSteamStorePristine: $file is ${dll.length()} bytes (< $minBytes), " +
                        "looks like a Goldberg stub — shared store is contaminated",
                )
                return false
            }
        }
        return true
    }

    // Restore steamclient artifacts and clean injected extras if needed.
    @JvmStatic
    @JvmOverloads
    fun restoreSteamclientFiles(
        context: Context,
        steamAppId: Int = -1,
    ) {
        val imageFs = ImageFs.find(context)
        var restoredCount = 0

        val origDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam")
        val backupDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/steamclient_backup")
        if (backupDir.exists()) {
            coreSteamClientFiles().forEach { file ->
                val dll = File(backupDir, "$file.orig")
                if (dll.exists()) {
                    Files.copy(dll.toPath(), File(origDir, file).toPath(), StandardCopyOption.REPLACE_EXISTING)
                    restoredCount++
                }
            }

            arrayOf(
                "steamclient_loader_x32.exe.orig", "steamclient_loader_x86.exe.orig",
                "steamclient_loader_x64.exe.orig",
            ).forEach { loaderBackup ->
                val staleBackup = File(backupDir, loaderBackup)
                if (staleBackup.exists()) {
                    staleBackup.delete()
                }
            }
        }

        val extraDllDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/extra_dlls")
        if (extraDllDir.exists()) {
            extraDllDir.deleteRecursively()
        }

        arrayOf(
            "steamclient_loader_x32.exe", "steamclient_loader_x86.exe",
            "steamclient_loader_x64.exe",
        ).forEach { loaderExe ->
            val staleLoader = File(origDir, loaderExe)
            if (staleLoader.exists()) {
                staleLoader.delete()
            }
        }

        val idLog = if (steamAppId >= 0) steamAppId.toString() else "unknown"
        Timber.i("restoreSteamclientFiles complete (appId=$idLog, restored=$restoredCount)")
    }

    private val bootstrapClient =
        OkHttpClient
            .Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .build()

    private val doh: DnsOverHttps =
        DnsOverHttps
            .Builder()
            .client(bootstrapClient)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("8.8.4.4"),
            ).build()

    private val fallbackDns =
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                try {
                    doh.lookup(hostname)
                } catch (e: Exception) {
                    Timber.w(e, "DoH lookup failed for \$hostname, falling back to system DNS")
                    Dns.SYSTEM.lookup(hostname)
                }
        }

    val http: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .dns(fallbackDns)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .pingInterval(30, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .build()
    }

    @SuppressLint("HardwareIds")
    fun getMachineName(context: Context): String {
        val deviceName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return (deviceName ?: "Android Device") + " ($androidId)"
    }

    fun getPersonaState(state: Int): EPersonaState = EPersonaState.from(state) ?: EPersonaState.Offline

    fun getOSType(): EOSType = EOSType.WinUnknown

    @JvmStatic
    fun getSteamId64(): Long = PrefManager.steamUserSteamId64

    @JvmStatic
    fun getSteam3AccountId(): Int = PrefManager.steamUserAccountId

    fun getLanguage(steamLanguage: String): String = steamLanguage.lowercase()

    fun removeSpecialChars(s: String): String = s.replace(Regex("[^\\u0000-\\u007F]"), "")

    fun getUniqueDeviceId(context: Context): Int = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).hashCode()

    /**
     * Sets up auto-login configuration in the Wine prefix for Steam.
     * Writes loginusers.vdf and sets Wine registry keys.
     */
    @JvmStatic
    fun autoLoginUserChanges(imageFs: ImageFs) {
        val vdfFileText =
            SteamService.getLoginUsersVdfOauth(
                steamId64 = SteamService.userSteamId?.convertToUInt64().toString(),
                account = PrefManager.username,
                refreshToken = PrefManager.refreshToken,
                accessToken = PrefManager.accessToken,
                personaName =
                    SteamService.instance
                        ?.localPersona
                        ?.value
                        ?.name ?: PrefManager.username,
            )
        val steamConfigDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/config")
        try {
            steamConfigDir.mkdirs()
            File(steamConfigDir, "loginusers.vdf").writeText(vdfFileText)
            val rootDir = imageFs.rootDir
            val userRegFile = File(rootDir, ImageFs.WINEPREFIX + "/user.reg")

            val steamRoot = "C:\\Program Files (x86)\\Steam"
            val steamExe = "$steamRoot\\steam.exe"

            val hkcu = "Software\\Valve\\Steam"
            WineRegistryEditor(userRegFile).use { reg ->
                reg.setStringValue(hkcu, "AutoLoginUser", PrefManager.username)
                reg.setStringValue(hkcu, "SteamExe", steamExe)
                reg.setStringValue(hkcu, "SteamPath", steamRoot)
                reg.setStringValue(hkcu, "InstallPath", steamRoot)
            }
        } catch (e: Exception) {
            Timber.w("Could not add steam config options: $e")
        }
    }

    /**
     * Creates configuration files that make Steam run in lightweight mode
     * with reduced resource usage and disabled community features.
     */
    @JvmStatic
    fun setupLightweightSteamConfig(
        imageFs: ImageFs,
        steamId64: String?,
    ) {
        Timber.i("Setting up lightweight steam configs")
        try {
            val steamPath = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam")

            val userDataPath = File(steamPath, "userdata/$steamId64")
            val configPath = File(userDataPath, "config")
            val remotePath = File(userDataPath, "7/remote")

            configPath.mkdirs()
            remotePath.mkdirs()

            val localConfigContent =
                """
                "UserLocalConfigStore"
                {
                  "Software"
                  {
                    "Valve"
                    {
                      "Steam"
                      {
                        "SmallMode"                      "1"
                        "LibraryDisableCommunityContent" "1"
                        "LibraryLowBandwidthMode"        "1"
                        "LibraryLowPerfMode"             "1"
                      }
                    }
                  }
                  "friends"
                  {
                    "SignIntoFriends" "0"
                  }
                }
                """.trimIndent()

            val sharedConfigContent =
                """
                "UserRoamingConfigStore"
                {
                  "Software"
                  {
                    "Valve"
                    {
                      "Steam"
                      {
                        "SteamDefaultDialog" "#app_games"
                        "FriendsUI"
                        {
                          "FriendsUIJSON" "{\"bSignIntoFriends\":false,\"bAnimatedAvatars\":false,\"PersonaNotifications\":0,\"bDisableRoomEffects\":true}"
                        }
                      }
                    }
                  }
                }
                """.trimIndent()

            val localConfigFile = File(configPath, "localconfig.vdf")
            val sharedConfigFile = File(remotePath, "sharedconfig.vdf")

            if (!localConfigFile.exists()) {
                localConfigFile.writeText(localConfigContent)
                Timber.i("Created lightweight Steam localconfig.vdf")
            }

            if (!sharedConfigFile.exists()) {
                sharedConfigFile.writeText(sharedConfigContent)
                Timber.i("Created lightweight Steam sharedconfig.vdf")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to setup lightweight Steam configuration")
        }
    }

    /**
     * Adds depots listing, achievements mapping, and save-location symlinks to steam_settings.
     * Supplements the core Goldberg/ColdClient config written by writeCompleteSettingsDir.
     */
    @JvmStatic
    fun enrichSteamSettings(
        context: Context,
        appId: Int,
        settingsDir: File,
    ) {
        try {
            settingsDir.mkdirs()

            // depots.txt from installed depots (fall back to DLC depots)
            val depots =
                SteamService
                    .getInstalledDepotsOf(appId)
                    ?.takeIf { it.isNotEmpty() }
                    ?: SteamService.getInstalledDlcDepotsOf(appId)

            val depotsFile = File(settingsDir, "depots.txt")
            if (depots != null && depots.isNotEmpty()) {
                depotsFile.writeText(depots.joinToString(System.lineSeparator()), Charsets.UTF_8)
            } else if (depotsFile.exists()) {
                depotsFile.delete()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed writing depots.txt for appId=$appId")
        }

        // Achievements mapping (best-effort; requires Steam login)
        runCatching {
            runBlocking {
                SteamService.generateAchievements(appId, settingsDir.absolutePath)
            }
        }.onFailure { e ->
            Timber.d(e, "Achievements generation skipped for appId=$appId")
        }

        // Steam Inventory item definitions → items.json (best-effort; requires
        // Steam login). Gives inventory-driven games (TF2/CS-style) their item
        // catalog so the in-game inventory isn't empty.
        runCatching {
            runBlocking {
                SteamService.generateInventoryItems(appId, settingsDir.absolutePath)
            }
        }.onFailure { e ->
            Timber.d(e, "Inventory items generation skipped for appId=$appId")
        }

        // Steam Workshop mods → mods.json + mods/<id>/ + mod_images/<id>/
        // (best-effort). Links any content staged by the Workshop browser into
        // this steam_settings dir so gbe_fork's emulated ISteamUGC sees it.
        runCatching {
            com.winlator.cmod.feature.stores.steam.workshop.WorkshopModsGenerator
                .generate(context, appId, settingsDir)
        }.onFailure { e ->
            Timber.d(e, "Workshop mods generation skipped for appId=$appId")
        }

        val cloudPushed = runCatching {
            runBlocking {
                SteamService.pushCloudStateToLibSteamClient(appId)
            }
        }
        if (cloudPushed.isFailure) {
            Timber.w(cloudPushed.exceptionOrNull(),
                "Cloud state push FAILED for appId=$appId — clearing mirror to " +
                "prevent stale data leaking from a previously-launched app")
            runCatching {
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setCloudFiles(emptyArray(), IntArray(0), LongArray(0))
            }
        }

        runCatching {
            runBlocking {
                SteamService.pushAppDlcsToLibSteamClient(appId)
            }
        }.onFailure { e ->
            Timber.d(e, "DLC list push skipped for appId=$appId")
        }

        runCatching {
            SteamService.pushAppInstalledDepotsToLibSteamClient(appId)
        }.onFailure { e ->
            Timber.d(e, "Installed depots push skipped for appId=$appId")
        }

        runCatching {
            runBlocking {
                SteamService.refreshEncryptedAppTicketForLibSteamClient(appId)
            }
        }.onFailure { e ->
            Timber.d(e, "Encrypted app ticket push skipped for appId=$appId")
        }

        runCatching {
            runBlocking {
                SteamService.prefetchOwnershipTicketForLibSteamClient(appId)
            }
        }.onFailure { e ->
            Timber.d(e, "Ownership ticket pre-fetch skipped for appId=$appId")
        }

        // Special save-location symlinks
        ensureSaveLocationsForGames(context, appId)
    }

    /**
     * Ensures save location symlinks for special cases.
     */
    @JvmStatic
    fun ensureSaveLocationsForGames(
        context: Context,
        steamAppId: Int,
    ) {
        val mappings = SpecialGameSaveMapping.registry + loadExtraSaveMappings(context)
        val mapping = mappings.find { it.appId == steamAppId } ?: return

        try {
            val accountId =
                SteamService.userSteamId?.accountID?.toLong()
                    ?: PrefManager.steamUserAccountId.toLong()
            val steamId64 =
                SteamService.userSteamId?.convertToUInt64()?.toString()
                    ?: PrefManager.steamUserSteamId64.toString()

            val basePath = mapping.pathType.toAbsPath(context, steamAppId, accountId)

            val sourceRelativePath =
                mapping.sourceRelativePath
                    .replace("{64BitSteamID}", steamId64)
                    .replace("{Steam3AccountID}", accountId.toString())
            val targetRelativePath =
                mapping.targetRelativePath
                    .replace("{64BitSteamID}", steamId64)
                    .replace("{Steam3AccountID}", accountId.toString())

            val sourcePath = File(basePath, sourceRelativePath)
            val targetPath = File(basePath, targetRelativePath)

            if (!sourcePath.exists()) {
                Timber.i("[${mapping.description}] Source save folder does not exist yet: ${sourcePath.absolutePath}")
                return
            }

            if (targetPath.exists()) {
                if (Files.isSymbolicLink(targetPath.toPath())) {
                    Timber.i("[${mapping.description}] Symlink already exists: ${targetPath.absolutePath}")
                } else {
                    Timber.w("[${mapping.description}] Target path exists but is not a symlink: ${targetPath.absolutePath}")
                }
                return
            }

            targetPath.parentFile?.mkdirs()
            Files.createSymbolicLink(targetPath.toPath(), sourcePath.toPath())
            Timber.i("[${mapping.description}] Created symlink: ${targetPath.absolutePath} -> ${sourcePath.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "[${mapping.description}] Failed to create save location symlink")
        }
    }

    private fun loadExtraSaveMappings(context: Context): List<SpecialGameSaveMapping> {
        val merged = mutableListOf<SpecialGameSaveMapping>()
        // Bundled asset overrides (optional)
        runCatching {
            context.assets.open("steam_save_mappings.json").use { input ->
                merged.addAll(parseMappingsJson(input.readBytes().toString(Charsets.UTF_8)))
            }
        }.onFailure { /* optional asset; ignore */ }

        // User-provided runtime file
        runCatching {
            val file = File(context.filesDir, "steam_save_mappings.json")
            if (file.exists()) {
                merged.addAll(parseMappingsJson(file.readText()))
            }
        }.onFailure { Timber.w(it, "Failed to load user steam_save_mappings.json") }

        // De-dup by appId/pathType/paths
        return merged.distinctBy { Triple(it.appId, it.pathType, it.sourceRelativePath + "|" + it.targetRelativePath) }
    }

    private fun parseMappingsJson(json: String): List<SpecialGameSaveMapping> =
        runCatching {
            val arr = JSONArray(json)
            val result = mutableListOf<SpecialGameSaveMapping>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val appId = obj.optInt("appId", -1)
                val pathType = PathType.from(obj.optString("pathType", ""))
                val source = obj.optString("sourceRelativePath", "")
                val target = obj.optString("targetRelativePath", "")
                val description = obj.optString("description", "Custom Mapping")
                if (appId > 0 && pathType != PathType.None && source.isNotEmpty() && target.isNotEmpty()) {
                    result.add(
                        SpecialGameSaveMapping(
                            appId = appId,
                            pathType = pathType,
                            sourceRelativePath = source,
                            targetRelativePath = target,
                            description = description,
                        ),
                    )
                }
            }
            result
        }.getOrElse {
            Timber.w(it, "Failed to parse save mappings JSON")
            emptyList()
        }

    /**
     * Restores .original.exe backups created during Steamless unpack attempts.
     */
    @JvmStatic
    fun restoreOriginalExecutable(
        context: Context,
        steamAppId: Int,
    ) {
        Timber.i("Starting restoreOriginalExecutable for appId: $steamAppId")
        var restoredCount = 0

        val imageFs = ImageFs.find(context)
        val dosDevicesRoot = File(imageFs.wineprefix, "dosdevices")
        val dosDevicesPath =
            dosDevicesRoot
                .listFiles()
                ?.firstOrNull { it.isDirectory && it.name.matches(Regex("[a-z]:")) && it.name != "c:" && it.name != "z:" }
                ?: File(dosDevicesRoot, "f:")

        dosDevicesPath
            .walkTopDown()
            .maxDepth(10)
            .filter { it.isFile && it.name.endsWith(".original.exe", ignoreCase = true) }
            .forEach { file ->
                try {
                    val origPath = file.toPath()
                    val originalPath = origPath.parent.resolve(origPath.fileName.toString().removeSuffix(".original.exe"))

                    if (Files.exists(originalPath)) {
                        Files.delete(originalPath)
                    }

                    Files.copy(origPath, originalPath, StandardCopyOption.REPLACE_EXISTING)
                    Timber.i("Restored ${originalPath.fileName} from backup")
                    restoredCount++
                } catch (e: Exception) {
                    Timber.w(e, "Failed to restore ${file.name} from backup")
                }
            }

        Timber.i("Finished restoreOriginalExecutable for appId: $steamAppId. Restored $restoredCount executable(s)")
    }

    /**
     * Restores the unpacked executable (.unpacked.exe) if it exists and is different from current .exe
     * This ensures we use the DRM-free version when not using real Steam
     */
    @JvmStatic
    fun restoreUnpackedExecutable(
        context: Context,
        steamAppId: Int,
    ) {
        try {
            val imageFs = ImageFs.find(context)
            val appDirPath =
                com.winlator.cmod.feature.stores.steam.service.SteamService
                    .getAppDirPath(steamAppId)

            val container =
                com.winlator.cmod.feature.stores.steam.utils.ContainerUtils
                    .getContainer(context, "STEAM_$steamAppId")
            val executablePath = container?.executablePath ?: ""
            val relativeExecutablePath = executablePath.ifEmpty { SteamService.getInstalledExe(steamAppId) }
            if (relativeExecutablePath.isEmpty()) {
                Timber.i("No executable path available for appId=$steamAppId, skipping unpacked executable restore")
                return
            }

            val resolvedExeFile = File(appDirPath, relativeExecutablePath.replace("\\", "/"))
            val windowsExecutablePath =
                WineUtils.hostPathToRootWinePath(container, resolvedExeFile.absolutePath)
            if (windowsExecutablePath.isEmpty()) {
                Timber.w("Failed to map executable path for appId=$steamAppId at ${resolvedExeFile.absolutePath}")
                return
            }

            val driveLetter = windowsExecutablePath[0].lowercaseChar()
            val dosDevicePath =
                windowsExecutablePath
                    .replaceFirst("${windowsExecutablePath[0]}:", "$driveLetter:")
                    .replace('\\', '/')
            val exe = File(imageFs.wineprefix + "/dosdevices/" + dosDevicePath)
            val unpackedExe = File(imageFs.wineprefix + "/dosdevices/" + dosDevicePath + ".unpacked.exe")

            if (unpackedExe.exists()) {
                val areFilesDifferent =
                    !exe.exists() ||
                        exe.length() != unpackedExe.length() ||
                        exe.lastModified() != unpackedExe.lastModified()

                if (areFilesDifferent) {
                    Files.copy(unpackedExe.toPath(), exe.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Timber.i("Restored unpacked executable from ${unpackedExe.name} to ${exe.name}")
                } else {
                    Timber.i("Unpacked executable is already current, no restore needed")
                }
            } else {
                Timber.i("No unpacked executable found, using current executable")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore unpacked executable for appId $steamAppId")
        }
    }

    /**
     * Sync achievements/stats generated by Goldberg back to Steam (best-effort).
     */
    @JvmStatic
    fun syncGoldbergAchievementsAndStats(
        context: Context,
        appId: Int,
    ) {
        if (!SteamService.isLoggedIn) {
            queuePendingGoldbergSync(context, appId)
            Timber.w("Goldberg sync queued because Steam is not logged in for appId=$appId")
            return
        }
        runBlocking {
            runCatching { SteamService.syncAchievementsFromGoldberg(appId) }
                .onFailure {
                    Timber.w(it, "Goldberg achievement sync failed for appId=$appId; queuing for retry")
                    queuePendingGoldbergSync(context, appId)
                }
        }
    }

    @JvmStatic
    fun processPendingGoldbergSyncs(context: Context) {
        if (!SteamService.isLoggedIn) return
        val file = File(context.filesDir, "pending_goldberg_syncs.json")
        if (!file.exists()) return
        val pending =
            runCatching {
                val arr = JSONArray(file.readText())
                (0 until arr.length()).mapNotNull { arr.optInt(it, -1).takeIf { id -> id > 0 } }.toSet()
            }.getOrElse {
                Timber.w(it, "Failed to read pending Goldberg syncs")
                emptySet()
            }
        if (pending.isEmpty()) return
        val remaining = mutableSetOf<Int>()
        pending.forEach { id ->
            try {
                syncGoldbergAchievementsAndStats(context, id)
            } catch (e: Exception) {
                Timber.w(e, "Pending Goldberg sync failed for appId=$id; will keep queued")
                remaining.add(id)
            }
        }
        if (remaining.isEmpty()) {
            file.delete()
        } else {
            file.writeText(JSONArray(remaining.toList()).toString(), Charsets.UTF_8)
        }
    }

    private fun queuePendingGoldbergSync(
        context: Context,
        appId: Int,
    ) {
        runCatching {
            val file = File(context.filesDir, "pending_goldberg_syncs.json")
            val set =
                if (file.exists()) {
                    val arr = JSONArray(file.readText())
                    (0 until arr.length()).mapNotNull { arr.optInt(it, -1).takeIf { id -> id > 0 } }.toMutableSet()
                } else {
                    mutableSetOf()
                }
            set.add(appId)
            file.writeText(JSONArray(set.toList()).toString(), Charsets.UTF_8)
        }.onFailure { Timber.w(it, "Failed to queue pending Goldberg sync for appId=$appId") }
    }

    /**
     * Creates a Steam ACF (Application Cache File) manifest for the given app.
     * This allows real Steam to detect the game as installed.
     */
    private fun resolveManifestForBranch(
        depot: DepotInfo,
        branch: String,
        visitedApps: MutableSet<Int> = mutableSetOf(),
    ): ManifestInfo? {
        depot.manifests[branch]?.let { return it }
        depot.encryptedManifests[branch]?.let { return it }

        if (!branch.equals("public", ignoreCase = true)) {
            depot.manifests["public"]?.let { return it }
            depot.encryptedManifests["public"]?.let { return it }
        }

        val sourceAppId = depot.depotFromApp
        if (sourceAppId == SteamService.INVALID_APP_ID || !visitedApps.add(sourceAppId)) {
            return null
        }

        val sourceDepot = SteamService.getAppInfoOf(sourceAppId)?.depots?.get(depot.depotId) ?: return null
        return resolveManifestForBranch(sourceDepot, branch, visitedApps)
    }

    private fun collectInstalledDepotManifests(
        steamAppId: Int,
        appInfo: com.winlator.cmod.feature.stores.steam.data.SteamApp,
        branch: String,
        installedDepotIds: Set<Int>,
        installedDlcAppIds: Set<Int>,
        language: String,
        onDiskManifestIds: Map<Int, Long> = emptyMap(),
    ): LinkedHashMap<Int, ManifestInfo> {
        val allKnownDepots = linkedMapOf<Int, DepotInfo>()
        appInfo.depots.forEach { (depotId, depot) -> allKnownDepots[depotId] = depot }
        SteamService.getDownloadableDepots(steamAppId, language).forEach { (depotId, depot) ->
            allKnownDepots[depotId] = depot
        }

        val installedDlcDepotsIds = SteamService.getInstalledDlcDepotsOf(steamAppId)
        installedDlcDepotsIds?.forEach { appId ->
            try {
                runBlocking {
                    getOwnedAppDlc(appId).forEach { (depotId, depot) ->
                        allKnownDepots[depotId] = depot
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to resolve owned DLC depots for appId=$appId")
            }
        }


        val installedDepots = linkedMapOf<Int, ManifestInfo>()
        allKnownDepots.forEach { (depotId, depotInfo) ->
            // Exclude shared depots from InstalledDepots — they belong in SharedDepots only
            if (depotInfo.sharedInstall) return@forEach

            val shouldInclude =
                depotId in installedDepotIds ||
                    (
                        depotInfo.dlcAppId != SteamService.INVALID_APP_ID &&
                            depotInfo.dlcAppId in installedDlcAppIds
                    )
            if (!shouldInclude) return@forEach

            val manifest =
                SteamBranchSelection.installedManifest(
                    resolved = resolveManifestForBranch(depotInfo, branch)?.takeIf { it.gid != 0L },
                    onDiskManifestId = onDiskManifestIds[depotId],
                    branch = branch,
                )
            if (manifest != null) {
                installedDepots[depotId] = manifest
            }
        }
        return installedDepots
    }

    @JvmStatic
    fun installedDepotManifestIds(steamAppId: Int): Map<Int, Long> =
        runCatching {
            SteamService.readInstalledDepotManifestIds(SteamService.getAppDirPath(steamAppId))
        }.getOrDefault(emptyMap())

    @JvmStatic
    fun installedBuildId(steamAppId: Int): Long =
        runCatching { SteamService.getInstalledBuildId(steamAppId) }.getOrDefault(0L)

    @JvmStatic
    fun steamLaunchOptionFor(
        steamAppId: Int,
        relativeExe: String,
    ): Int =
        runCatching {
            var infos = SteamService.getWindowsLaunchInfos(steamAppId).filter { it.launchId >= 0 }
            if (infos.isEmpty()) {
                SteamService.ensureFreshDepotData(steamAppId)
                infos = SteamService.getWindowsLaunchInfos(steamAppId).filter { it.launchId >= 0 }
            }
            if (infos.isEmpty()) return@runCatching -1
            fun norm(p: String) = p.replace('\\', '/').trim().trimStart('/').lowercase()
            val want = norm(relativeExe)
            val exact = infos.firstOrNull { norm(it.executable) == want }
            val byName =
                exact ?: infos.firstOrNull {
                    norm(it.executable).substringAfterLast('/') == want.substringAfterLast('/')
                }
            (byName ?: infos.first()).launchId
        }.getOrDefault(-1)

    @JvmStatic
    @JvmOverloads
    fun createAppManifest(
        context: Context,
        steamAppId: Int,
        language: String = "english",
    ) {
        try {
            Timber.i("Attempting to createAppManifest for appId: $steamAppId")
            val appInfo =
                getAppInfoOf(steamAppId) ?: run {
                    Timber.w("No app info found for appId: $steamAppId")
                    return
                }

            val imageFs = ImageFs.find(context)

            val steamappsDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/steamapps")
            steamappsDir.mkdirs()

            val commonDir = File(steamappsDir, "common")
            commonDir.mkdirs()

            val gameDir = File(SteamService.getAppDirPath(steamAppId))
            if (!gameDir.isDirectory || !SteamService.isAppInstalled(steamAppId)) {
                Timber.w("Skipping ACF manifest for appId=$steamAppId because the install is not trusted on disk")
                return
            }
            val gameName = SteamService.getAppDirName(appInfo).ifBlank { gameDir.name }
            val sizeOnDisk = installSizeOnDisk(context, steamAppId, gameDir)
            val selectedBranch = SteamService.resolveSelectedBetaName(steamAppId).ifBlank { "public" }
            val ownerSteamId = PrefManager.steamUserSteamId64.takeIf { it > 0L }?.toString() ?: "0"

            if (gameName != gameDir.name) {
                Timber.i(
                    "ACF installdir '$gameName' differs from the on-disk folder '${gameDir.name}' " +
                        "for appId=$steamAppId; linking under the canonical name so LaunchApp resolves it",
                )
            }

            val steamGameLink = File(commonDir, gameName)
            if (!steamGameLink.exists()) {
                try {
                    Files.createSymbolicLink(steamGameLink.toPath(), gameDir.toPath())
                    Timber.i("Created symlink from ${steamGameLink.absolutePath} to ${gameDir.absolutePath}")
                } catch (e: Exception) {
                    Timber.w(e, "Failed to create symlink, trying copy instead")
                }
            }

            val latestBuildId = appInfo.branches[selectedBranch]?.buildId ?: appInfo.branches["public"]?.buildId ?: 0L
            val buildId = SteamService.getInstalledBuildId(steamAppId).takeIf { it > 0L } ?: latestBuildId
            val installedDepotIds = SteamService.getInstalledDepotsOf(steamAppId).orEmpty().toSet()
            val installedDlcAppIds = SteamService.getInstalledDlcDepotsOf(steamAppId).orEmpty().toSet()
            val onDiskManifestIds = SteamService.readInstalledDepotManifestIds(gameDir.absolutePath)
            val installedDepots =
                collectInstalledDepotManifests(
                    steamAppId = steamAppId,
                    appInfo = appInfo,
                    branch = selectedBranch,
                    installedDepotIds = installedDepotIds,
                    installedDlcAppIds = installedDlcAppIds,
                    language = language,
                    onDiskManifestIds = onDiskManifestIds,
                )
            if (buildId != latestBuildId) {
                Timber.i(
                    "ACF manifest for appId=$steamAppId reports installed buildId=$buildId on branch " +
                        "'$selectedBranch'; the current build for that branch is $latestBuildId",
                )
            }

            // Compute total download and stage sizes from resolved depots
            val totalBytesToDownload = installedDepots.values.sumOf { it.download }
            val totalBytesToStage = installedDepots.values.sumOf { it.size }

            // Collect dlcAppId mapping for InstalledDepots entries
            val allKnownDepots = linkedMapOf<Int, com.winlator.cmod.feature.stores.steam.data.DepotInfo>()
            appInfo.depots.forEach { (depotId, depot) -> allKnownDepots[depotId] = depot }
            SteamService.getDownloadableDepots(steamAppId, language).forEach { (depotId, depot) ->
                allKnownDepots[depotId] = depot
            }

            // Collect shared depots for SharedDepots section
            val sharedDepots = linkedMapOf<Int, Int>()
            allKnownDepots.forEach { (depotId, depotInfo) ->
                if (depotInfo.sharedInstall && depotInfo.depotFromApp != SteamService.INVALID_APP_ID) {
                    sharedDepots[depotId] = depotInfo.depotFromApp
                }
            }

            // Collect install scripts for InstallScripts section
            val installScripts = linkedMapOf<Int, String>()
            if (appInfo.installScript.isNotBlank()) {
                // Map the base game depot(s) to the install script (base app's own depots only,
                // matching the native launcher env-var path in XServerDisplayActivity).
                appInfo.depots.forEach { (depotId, depotInfo) ->
                    if (depotInfo.dlcAppId == SteamService.INVALID_APP_ID &&
                        depotInfo.depotFromApp == SteamService.INVALID_APP_ID
                    ) {
                        installScripts[depotId] = appInfo.installScript
                    }
                }
            }

            val acfContent =
                buildString {
                    appendLine("\"AppState\"")
                    appendLine("{")
                    appendLine("\t\"appid\"\t\t\"$steamAppId\"")
                    appendLine("\t\"universe\"\t\t\"1\"")
                    appendLine("\t\"LauncherPath\"\t\t\"C:\\\\Program Files (x86)\\\\Steam\\\\steam.exe\"")
                    appendLine("\t\"name\"\t\t\"${escapeString(appInfo.name)}\"")
                    appendLine("\t\"StateFlags\"\t\t\"4\"")

                    val actualInstallDir = gameName
                    appendLine("\t\"installdir\"\t\t\"${escapeString(actualInstallDir)}\"")

                    appendLine("\t\"LastUpdated\"\t\t\"${System.currentTimeMillis() / 1000}\"")
                    appendLine("\t\"LastPlayed\"\t\t\"0\"")
                    appendLine("\t\"SizeOnDisk\"\t\t\"$sizeOnDisk\"")
                    appendLine("\t\"StagingSize\"\t\t\"0\"")
                    appendLine("\t\"buildid\"\t\t\"$buildId\"")
                    appendLine("\t\"LastOwner\"\t\t\"$ownerSteamId\"")
                    appendLine("\t\"DownloadType\"\t\t\"1\"")
                    appendLine("\t\"UpdateResult\"\t\t\"0\"")
                    appendLine("\t\"BytesToDownload\"\t\t\"$totalBytesToDownload\"")
                    appendLine("\t\"BytesDownloaded\"\t\t\"$totalBytesToDownload\"")
                    appendLine("\t\"BytesToStage\"\t\t\"$totalBytesToStage\"")
                    appendLine("\t\"BytesStaged\"\t\t\"$totalBytesToStage\"")
                    appendLine("\t\"TargetBuildID\"\t\t\"$buildId\"")
                    appendLine("\t\"AutoUpdateBehavior\"\t\t\"0\"")
                    appendLine("\t\"AllowOtherDownloadsWhileRunning\"\t\t\"0\"")
                    appendLine("\t\"ScheduledAutoUpdate\"\t\t\"0\"")

                    if (installedDepots.isNotEmpty()) {
                        appendLine("\t\"InstalledDepots\"")
                        appendLine("\t{")
                        installedDepots.forEach { (depotId, manifest) ->
                            appendLine("\t\t\"$depotId\"")
                            appendLine("\t\t{")
                            appendLine("\t\t\t\"manifest\"\t\t\"${manifest.gid}\"")
                            appendLine("\t\t\t\"size\"\t\t\"${manifest.size}\"")
                            val dlcAppId = allKnownDepots[depotId]?.dlcAppId
                            if (dlcAppId != null && dlcAppId != SteamService.INVALID_APP_ID) {
                                appendLine("\t\t\t\"dlcappid\"\t\t\"$dlcAppId\"")
                            } else if (depotId in installedDlcAppIds) {
                                appendLine("\t\t\t\"dlcappid\"\t\t\"$depotId\"")
                            }
                            appendLine("\t\t}")
                        }
                        appendLine("\t}")
                    } else if (installedDepotIds.isNotEmpty()) {
                        Timber.w(
                            "ACF manifest for appId=$steamAppId has ${installedDepotIds.size} trusted depot(s) " +
                                "but none could be resolved for branch '$selectedBranch'",
                        )
                    }

                    if (installScripts.isNotEmpty()) {
                        appendLine("\t\"InstallScripts\"")
                        appendLine("\t{")
                        installScripts.forEach { (depotId, script) ->
                            appendLine("\t\t\"$depotId\"\t\t\"${escapeString(script)}\"")
                        }
                        appendLine("\t}")
                    }

                    if (sharedDepots.isNotEmpty()) {
                        appendLine("\t\"SharedDepots\"")
                        appendLine("\t{")
                        sharedDepots.forEach { (depotId, appTarget) ->
                            appendLine("\t\t\"$depotId\"\t\t\"$appTarget\"")
                        }
                        appendLine("\t}")
                    }

                    appendLine("\t\"UserConfig\"")
                    appendLine("\t{")
                    appendLine("\t\t\"language\"\t\t\"$language\"")
                    appendLine("\t}")
                    appendLine("\t\"MountedConfig\"")
                    appendLine("\t{")
                    appendLine("\t\t\"language\"\t\t\"$language\"")
                    appendLine("\t}")
                    appendLine("}")
                }

            val acfFile = File(steamappsDir, "appmanifest_$steamAppId.acf")
            acfFile.writeText(acfContent)
            Timber.i("Created ACF manifest for ${appInfo.name} at ${acfFile.absolutePath}")

            val hasSharedInstallDepots = appInfo.depots.values.any { it.sharedInstall }
            if (hasSharedInstallDepots) {
                val steamworksAcfContent =
                    buildString {
                        appendLine("\"AppState\"")
                        appendLine("{")
                        appendLine("\t\"appid\"\t\t\"228980\"")
                        appendLine("\t\"universe\"\t\t\"1\"")
                        appendLine("\t\"LauncherPath\"\t\t\"C:\\\\Program Files (x86)\\\\Steam\\\\steam.exe\"")
                        appendLine("\t\"name\"\t\t\"Steamworks Common Redistributables\"")
                        appendLine("\t\"StateFlags\"\t\t\"4\"")
                        appendLine("\t\"installdir\"\t\t\"Steamworks Shared\"")
                        appendLine("\t\"LastUpdated\"\t\t\"${System.currentTimeMillis() / 1000}\"")
                        appendLine("\t\"LastPlayed\"\t\t\"0\"")
                        appendLine("\t\"SizeOnDisk\"\t\t\"0\"")
                        appendLine("\t\"StagingSize\"\t\t\"0\"")
                        appendLine("\t\"buildid\"\t\t\"1\"")
                        appendLine("\t\"LastOwner\"\t\t\"0\"")
                        appendLine("\t\"DownloadType\"\t\t\"1\"")
                        appendLine("\t\"UpdateResult\"\t\t\"0\"")
                        appendLine("\t\"BytesToDownload\"\t\t\"0\"")
                        appendLine("\t\"BytesDownloaded\"\t\t\"0\"")
                        appendLine("\t\"BytesToStage\"\t\t\"0\"")
                        appendLine("\t\"BytesStaged\"\t\t\"0\"")
                        appendLine("\t\"TargetBuildID\"\t\t\"1\"")
                        appendLine("\t\"AutoUpdateBehavior\"\t\t\"0\"")
                        appendLine("\t\"AllowOtherDownloadsWhileRunning\"\t\t\"0\"")
                        appendLine("\t\"ScheduledAutoUpdate\"\t\t\"0\"")
                        appendLine("\t\"InstalledDepots\"")
                        appendLine("\t{")
                        appendLine("\t\t\"228980\"")
                        appendLine("\t\t{")
                        appendLine("\t\t\t\"manifest\"\t\t\"0\"")
                        appendLine("\t\t\t\"size\"\t\t\"0\"")
                        appendLine("\t\t}")
                        appendLine("\t}")
                        appendLine("\t\"UserConfig\"")
                        appendLine("\t{")
                        appendLine("\t\t\"language\"\t\t\"english\"")
                        appendLine("\t}")
                        appendLine("\t\"MountedConfig\"")
                        appendLine("\t{")
                        appendLine("\t\t\"language\"\t\t\"english\"")
                        appendLine("\t}")
                        appendLine("}")
                    }

                val steamworksAcfFile = File(steamappsDir, "appmanifest_228980.acf")
                steamworksAcfFile.writeText(steamworksAcfContent)
                Timber.i("Created Steamworks Common Redistributables ACF manifest")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create ACF manifest for appId $steamAppId")
        }
    }

    /**
     * Writes steam.cfg to skip Steam bootstrapper self-update and marks all common
     * redistributables (DirectX, .NET, XNA, OpenAL) as already installed in system.reg,
     * preventing Steam from re-running their setup wizards on first launch.
     */
    @JvmStatic
    fun skipFirstTimeSteamSetup(rootDir: File) {
        // Write steam.cfg
        try {
            val steamDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/Program Files (x86)/Steam")
            steamDir.mkdirs()
            val cfgFile = File(steamDir, "steam.cfg")
            if (!cfgFile.exists()) {
                cfgFile.writeText("BootStrapperInhibitAll=Enable\nBootStrapperForceSelfUpdate=False")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to write steam.cfg")
        }

        // Mark redistributables as installed in system.reg so Steam skips setup wizards
        val redistributables =
            listOf(
                "DirectX\\Jun2010" to "DXSetup",
                ".NET\\3.5" to "3.5 SP1",
                ".NET\\3.5 Client Profile" to "3.5 Client Profile SP1",
                ".NET\\4.0" to "4.0",
                ".NET\\4.0 Client Profile" to "4.0 Client Profile",
                ".NET\\4.5.2" to "4.5.2",
                ".NET\\4.6" to "4.6",
                ".NET\\4.7" to "4.7",
                ".NET\\4.8" to "4.8",
                "XNA\\3.0" to "3.0",
                "XNA\\3.1" to "3.1",
                "XNA\\4.0" to "4.0",
                "OpenAL\\2.0.7.0" to "2.0.7.0",
                ".NET\\4.5.1" to "4.5.1",
                ".NET\\4.6.1" to "4.6.1",
                ".NET\\4.6.2" to "4.6.2",
                ".NET\\4.7.1" to "4.7.1",
                ".NET\\4.7.2" to "4.7.2",
                ".NET\\4.8.1" to "4.8.1",
            )
        val systemRegFile = File(rootDir, ImageFs.WINEPREFIX + "/system.reg")
        if (!systemRegFile.exists()) return
        try {
            WineRegistryEditor(systemRegFile).use { reg ->
                redistributables.forEach { (subPath, valueName) ->
                    reg.setDwordValue("Software\\Valve\\Steam\\Apps\\CommonRedist\\$subPath", valueName, 1)
                    reg.setDwordValue("Software\\Wow6432Node\\Valve\\Steam\\Apps\\CommonRedist\\$subPath", valueName, 1)
                }
            }
            Timber.i("skipFirstTimeSteamSetup: marked ${redistributables.size} redistributables as installed")
        } catch (e: Exception) {
            Timber.w(e, "Failed to set redistributable registry entries")
        }
    }

    /**
     * Generates the cloud save config sections for configs.app.ini.
     * Produces [app::cloud_save::general] and, when Windows save patterns exist,
     * [app::cloud_save::win] with dir1=, dir2=, ... entries in the Goldberg-expected format.
     */
    @JvmStatic
    fun generateCloudSaveConfig(steamAppId: Int): String {
        val steamApp = getAppInfoOf(steamAppId) ?: return ""
        val windowsPatterns = steamApp.ufs.saveFilePatterns.filter { it.root.isWindows }

        return buildString {
            if (windowsPatterns.isNotEmpty()) {
                appendLine("[app::cloud_save::general]")
                appendLine("create_default_dir=1")
                appendLine("create_specific_dirs=1")
                appendLine()
                appendLine("[app::cloud_save::win]")
                val uniqueDirs = LinkedHashSet<String>()
                windowsPatterns.forEach { pattern ->
                    val root = if (pattern.root.name == "GameInstall") "gameinstall" else pattern.root.name
                    val path =
                        pattern.path
                            .replace("{64BitSteamID}", "{::64BitSteamID::}")
                            .replace("{Steam3AccountID}", "{::Steam3AccountID::}")
                    uniqueDirs.add("{::$root::}/$path")
                }
                uniqueDirs.forEachIndexed { index, dir ->
                    appendLine("dir${index + 1}=$dir")
                }
            }
        }
    }

    private fun escapeString(input: String?): String {
        if (input == null) return ""
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    }

    private const val INSTALL_SCAN_PREFS = "steam_install_size_cache"

    class InstallScan(
        @JvmField val sizeOnDisk: Long,
        @JvmField val hasSteamApiOrig: Boolean,
    )

    private val installScanMemo = java.util.concurrent.ConcurrentHashMap<String, Pair<String, InstallScan>>()

    private fun installScanKey(appId: Int, gameDir: File) = "$appId:${gameDir.absolutePath}"

    private fun installScanSignature(appId: Int, gameDir: File): String {
        val branch = SteamService.resolveSelectedBetaName(appId).ifBlank { "public" }
        val appInfo = getAppInfoOf(appId)
        val buildId =
            appInfo?.branches?.get(branch)?.buildId
                ?: appInfo?.branches?.get("public")?.buildId
                ?: 0L
        val depots = SteamService.getInstalledDepotsOf(appId).orEmpty().sorted().joinToString(",")
        val dlc = SteamService.getInstalledDlcDepotsOf(appId).orEmpty().sorted().joinToString(",")
        return "$buildId|${gameDir.lastModified()}|$depots|$dlc"
    }

    @JvmStatic
    fun invalidateInstallScan(
        context: Context,
        appId: Int,
        gameDir: File?,
    ) {
        if (gameDir == null) return
        val cacheKey = installScanKey(appId, gameDir)
        installScanMemo.remove(cacheKey)
        context.getSharedPreferences(INSTALL_SCAN_PREFS, Context.MODE_PRIVATE)
            .edit().remove(cacheKey).apply()
        Timber.i("invalidateInstallScan($cacheKey)")
    }

    @JvmStatic
    fun scanInstall(
        context: Context,
        appId: Int,
        gameDir: File?,
    ): InstallScan {
        if (gameDir == null || !gameDir.isDirectory) return InstallScan(0L, false)

        val cacheKey = installScanKey(appId, gameDir)
        val signature = installScanSignature(appId, gameDir)

        installScanMemo[cacheKey]?.let { (cachedSignature, cachedScan) ->
            if (cachedSignature == signature) return cachedScan
        }

        val prefs = context.getSharedPreferences(INSTALL_SCAN_PREFS, Context.MODE_PRIVATE)
        prefs.getString(cacheKey, null)?.let { stored ->
            val parts = stored.split('#')
            if (parts.size == 3) {
                val storedSize = parts[1].toLongOrNull()
                if (parts[0] == signature && storedSize != null) {
                    val scan = InstallScan(storedSize, parts[2].toBoolean())
                    installScanMemo[cacheKey] = signature to scan
                    Timber.i("scanInstall($cacheKey): cache hit, no directory walk")
                    return scan
                }
            }
        }

        val started = System.currentTimeMillis()
        var size = 0L
        var hasOrig = false
        try {
            gameDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    size += file.length()
                    val name = file.name.lowercase()
                    if (name == "steam_api.dll.orig" || name == "steam_api64.dll.orig") hasOrig = true
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "scanInstall($cacheKey) failed; not caching")
            return InstallScan(size, true)
        }
        Timber.i(
            "scanInstall($cacheKey): walked in ${System.currentTimeMillis() - started}ms " +
                "-> $size bytes, hasSteamApiOrig=$hasOrig",
        )

        val scan = InstallScan(size, hasOrig)
        installScanMemo[cacheKey] = signature to scan
        prefs.edit().putString(cacheKey, "$signature#$size#$hasOrig").apply()
        return scan
    }

    @JvmStatic
    fun installSizeOnDisk(
        context: Context,
        appId: Int,
        gameDir: File?,
    ): Long = scanInstall(context, appId, gameDir).sizeOnDisk

    private fun calculateDirectorySize(directory: File): Long {
        if (!directory.exists() || !directory.isDirectory) {
            return 0L
        }
        var size = 0L
        try {
            directory.walkTopDown().forEach { file ->
                if (file.isFile) {
                    size += file.length()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error calculating directory size")
        }
        return size
    }

    /**
     * Writes the complete steam_settings directory next to a DLL (steam_api.dll or steamclient.dll).
     * Single source of truth for all Goldberg config.
     *
     * Writes:
     *  - steam_appid.txt (parent dir level)
     *  - steam_settings/steam_appid.txt
     *  - steam_settings/depots.txt
     *  - steam_settings/configs.user.ini  ([user::general] + conditional [user::saves])
     *  - steam_settings/configs.app.ini   ([app::general] branch, [app::dlcs], [app::controller], [app::cloud_save])
     *  - steam_settings/configs.main.ini  ([main::connectivity] with offline support)
     *  - steam_settings/branches.json     (branch list for GetCurrentBetaName/GetAppBuildId)
     *  - steam_settings/controller/       (Steam Input VDF config if useSteamInput=true)
     *  - steam_settings/supported_languages.txt
     *
     * @param settingsParentDir  directory containing the DLL (steam_api.dll or steamclient.dll)
     * @param appId              Steam app ID
     * @param language           container language (e.g. "english")
     * @param isOffline          whether to write offline=1 in configs.main.ini
     * @param useSteamInput      whether to generate Steam Input controller config
     * @param ticketBase64       encrypted app ticket for online auth (may be null)
     */
    @JvmStatic
    @JvmOverloads
    fun writeCompleteSettingsDir(
        settingsParentDir: File,
        appId: Int,
        language: String = "english",
        isOffline: Boolean = false,
        useSteamInput: Boolean = false,
        ticketBase64: String? = null,
    ) {
        try {
            settingsParentDir.mkdirs()

            // steam_appid.txt at parent level
            val appIdFileUpper = File(settingsParentDir, "steam_appid.txt")
            if (!appIdFileUpper.exists()) {
                appIdFileUpper.writeText(appId.toString())
            }

            val settingsDir = File(settingsParentDir, "steam_settings")
            settingsDir.mkdirs()

            // steam_settings/steam_appid.txt
            File(settingsDir, "steam_appid.txt").writeText(appId.toString())

            // steam_settings/depots.txt — from installed depots, fall back to DLC depots
            val depots =
                SteamService
                    .getInstalledDepotsOf(appId)
                    ?.takeIf { it.isNotEmpty() }
                    ?: SteamService.getInstalledDlcDepotsOf(appId)
            val depotsFile = File(settingsDir, "depots.txt")
            if (depots != null && depots.isNotEmpty()) {
                depotsFile.writeText(depots.sorted().joinToString(System.lineSeparator()))
            } else {
                depotsFile.delete()
            }

            // --- configs.user.ini ---
            val accountName = PrefManager.username.takeIf { it.isNotEmpty() } ?: "Player"
            val accountSteamId =
                SteamService.userSteamId?.convertToUInt64()?.toString()
                    ?: PrefManager.steamUserSteamId64.takeIf { it != 0L }?.toString()
                    ?: "0"
            val accountId =
                SteamService.userSteamId?.accountID?.toLong()
                    ?: PrefManager.steamUserSteamId64.takeIf { it != 0L }?.let { it and 0xFFFFFFFFL }
                    ?: PrefManager.steamUserAccountId.takeIf { it != 0 }?.toLong()
                    ?: 0L

            val appInfo = getAppInfoOf(appId)
            val hasSaveFilePatterns = appInfo?.ufs?.saveFilePatterns?.isNotEmpty() == true

            val userIniContent =
                buildString {
                    appendLine("[user::general]")
                    appendLine("account_name=$accountName")
                    appendLine("account_steamid=$accountSteamId")
                    appendLine("language=${language.lowercase()}")
                    if (!ticketBase64.isNullOrEmpty()) {
                        appendLine("ticket=$ticketBase64")
                    }
                    // Only add [user::saves] when the game has no Cloud save patterns
                    if (!hasSaveFilePatterns) {
                        val steamUserDataPath = "C:\\Program Files (x86)\\Steam\\userdata\\$accountId"
                        appendLine()
                        appendLine("[user::saves]")
                        appendLine("local_save_path=$steamUserDataPath")
                    }
                }
            File(settingsDir, "configs.user.ini").writeText(userIniContent)

            // --- configs.app.ini ---
            val dlcIds = SteamService.getInstalledDlcDepotsOf(appId)
            val dlcApps = SteamService.getDownloadableDlcAppsOf(appId)
            val hiddenDlcApps = SteamService.getHiddenDlcAppsOf(appId)
            val appendedDlcIds = mutableListOf<Int>()

            val appIniContent =
                buildString {
                    // [app::general] — make Steam_Apps::GetCurrentBetaName()
                    // deterministic; WinNative always installs the public branch.
                    appendLine("[app::general]")
                    appendLine("is_beta_branch=0")
                    appendLine("branch_name=public")
                    appendLine()
                    appendLine("[app::dlcs]")
                    appendLine("unlock_all=0")
                    dlcIds?.sorted()?.forEach {
                        appendLine("$it=dlc$it")
                        appendedDlcIds.add(it)
                    }
                    dlcApps?.forEach { dlcApp ->
                        val installedDlcApp = SteamService.getInstalledApp(dlcApp.id)
                        if (installedDlcApp != null && !appendedDlcIds.contains(dlcApp.id)) {
                            appendLine("${dlcApp.id}=dlc${dlcApp.id}")
                            appendedDlcIds.add(dlcApp.id)
                        }
                    }
                    hiddenDlcApps?.forEach { hiddenDlcApp ->
                        if (!appendedDlcIds.contains(hiddenDlcApp.id) &&
                            (appInfo?.depots?.filter { (_, depot) -> depot.dlcAppId == hiddenDlcApp.id }?.size ?: 0) <= 1
                        ) {
                            appendLine("${hiddenDlcApp.id}=dlc${hiddenDlcApp.id}")
                        }
                    }
                    // [app::controller] — gbe_fork auto-enables SteamInput when
                    // steam_settings/controller/ contains action sets; state it
                    // explicitly so the generated config is self-describing.
                    if (useSteamInput) {
                        appendLine()
                        appendLine("[app::controller]")
                        appendLine("steam_input=1")
                    }
                    if (appInfo != null) {
                        appendLine()
                        append(generateCloudSaveConfig(appId))
                    }
                }
            File(settingsDir, "configs.app.ini").writeText(appIniContent)

            // --- configs.main.ini ---
            val mainIniContent =
                buildString {
                    appendLine("[main::connectivity]")
                    appendLine("disable_lan_only=${if (isOffline) 0 else 1}")
                    if (isOffline) {
                        appendLine("offline=1")
                    }
                    appendLine()
                    appendLine("[main::stats]")
                    appendLine("allow_unknown_stats=1")
                }
            File(settingsDir, "configs.main.ini").writeText(mainIniContent)

            // --- branches.json ---
            // gbe_fork reads this for Steam_Apps::GetCurrentBetaName() and
            // GetAppBuildId(). Emit the real branch list from PICS appinfo
            // when known (with build ids), else a minimal public branch.
            runCatching {
                val branchesArr = org.json.JSONArray()
                val branchList = appInfo?.branches?.values?.toList().orEmpty()
                fun putBranch(name: String, protected: Boolean, buildId: Long, timeUpdated: Long) {
                    branchesArr.put(
                        org.json.JSONObject()
                            .put("name", name)
                            .put("description", "")
                            .put("protected", protected)
                            .put("build_id", buildId)
                            .put("time_updated", timeUpdated),
                    )
                }
                if (branchList.isEmpty()) {
                    putBranch("public", false, 0L, 0L)
                } else {
                    branchList.forEach { b ->
                        putBranch(b.name, b.pwdRequired, b.buildId, (b.timeUpdated?.time ?: 0L) / 1000L)
                    }
                    // gbe_fork falls back to "public" when branch_name isn't in
                    // the file, but make it explicit so GetAppBuildId() is sane.
                    if (branchList.none { it.name.equals("public", ignoreCase = true) }) {
                        putBranch("public", false, 0L, 0L)
                    }
                }
                File(settingsDir, "branches.json").writeText(branchesArr.toString(2), Charsets.UTF_8)
            }.onFailure { e -> Timber.w(e, "Failed writing branches.json for appId=$appId") }

            // --- controller config ---
            val controllerDir = File(settingsDir, "controller")
            if (useSteamInput) {
                val controllerVdfText = SteamService.resolveSteamControllerVdfText(appId)
                if (!controllerVdfText.isNullOrEmpty()) {
                    runCatching {
                        SteamControllerVdfUtils.generateControllerConfig(
                            controllerVdfText,
                            controllerDir.toPath(),
                        )
                    }.onFailure { e ->
                        Timber.w(e, "Failed to generate controller config for appId=$appId")
                    }
                }
            } else {
                runCatching {
                    if (controllerDir.exists()) controllerDir.deleteRecursively()
                }.onFailure { e ->
                    Timber.w(e, "Failed to delete controller config dir for appId=$appId")
                }
            }

            // --- supported_languages.txt ---
            val supportedLanguages =
                listOf(
                    "arabic",
                    "bulgarian",
                    "schinese",
                    "tchinese",
                    "czech",
                    "danish",
                    "dutch",
                    "english",
                    "finnish",
                    "french",
                    "german",
                    "greek",
                    "hungarian",
                    "italian",
                    "japanese",
                    "koreana",
                    "norwegian",
                    "polish",
                    "portuguese",
                    "brazilian",
                    "romanian",
                    "russian",
                    "spanish",
                    "latam",
                    "swedish",
                    "thai",
                    "turkish",
                    "ukrainian",
                    "vietnamese",
                )
            File(settingsDir, "supported_languages.txt").writeText(supportedLanguages.joinToString("\n"))

            Timber.i("writeCompleteSettingsDir complete for appId=$appId in ${settingsParentDir.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to write complete steam_settings dir for appId=$appId")
        }
    }

    /**
     * Restores all steam_api.dll.orig / steam_api64.dll.orig backups in the app directory
     * back to their original names (steam_api.dll / steam_api64.dll).
     * Called before real-Steam launch and during ColdClient setup.
     */
    @JvmStatic
    fun putBackSteamDlls(appDirPath: String) {
        val rootPath = Paths.get(appDirPath)

        rootPath.toFile().walkTopDown().maxDepth(10).forEach { file ->
            val path = file.toPath()
            if (!file.isFile) return@forEach
            val name = path.fileName.toString()
            if (!name.startsWith("steam_api", ignoreCase = true)) return@forEach
            if (!name.endsWith(".orig", ignoreCase = true)) return@forEach

            val is64Bit = name.equals("steam_api64.dll.orig", ignoreCase = true)
            val is32Bit = name.equals("steam_api.dll.orig", ignoreCase = true)
            if (!is32Bit && !is64Bit) return@forEach

            try {
                val dllName = if (is64Bit) "steam_api64.dll" else "steam_api.dll"
                val originalPath = path.parent.resolve(dllName)
                Timber.i("Found ${path.fileName} at ${path.toAbsolutePath()}, restoring...")

                if (Files.exists(originalPath)) {
                    Files.delete(originalPath)
                }
                Files.copy(path, originalPath)
                Timber.i("Restored $dllName from .orig backup")
            } catch (e: IOException) {
                Timber.w(e, "Failed to restore ${file.name} from .orig backup")
            }
        }
    }

    /**
     * Updates localconfig.vdf with the container's LaunchOptions for the given appId,
     * and updates UserConfig/MountedConfig language in the ACF manifest.
     */
    /**
     * Disables Steam Cloud sync for a single app entry inside the parsed localconfig.vdf.
     *
     * Background: in Real Steam mode, Steam's launch pipeline calls AutoCloud on every launch.
     * If any server-side pending remote operations exist for the app (one per partial/killed
     * session piles up on the server), Steam wants to show a cloud-conflict dialog via the
     * CEF webhelper. That dialog can't render on arm64ec Wine, so Steam suspends the launch
     * indefinitely. Our own app already handles Steam Cloud sync via the WN-Steam-Client, so we turn
     * off Steam's own AutoCloud for the launched app — the server state isn't touched, we
     * just keep Steam from asking about it at launch.
     */
    private fun disableSteamCloudForApp(app: KeyValue) {
        val existingEnabled = app.children.firstOrNull { it.name == "cloudenabled" }
        if (existingEnabled != null) {
            existingEnabled.value = "0"
        } else {
            app.children.add(KeyValue("cloudenabled", "0"))
        }
        val cloudSection = app.children.firstOrNull { it.name == "cloud" }
            ?: KeyValue("cloud").also { app.children.add(it) }
        val lastSyncState = cloudSection.children.firstOrNull { it.name == "last_sync_state" }
        if (lastSyncState != null) {
            lastSyncState.value = "synchronized"
        } else {
            cloudSection.children.add(KeyValue("last_sync_state", "synchronized"))
        }
    }

    @JvmStatic
    fun updateOrModifyLocalConfig(
        imageFs: ImageFs,
        container: Container,
        appId: String,
        steamUserId64: String,
    ) {
        try {
            val exeCommandLine = container.execArgs

            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                .setLaunchCommandLine(exeCommandLine)

            val appIdInt = appId.toIntOrNull() ?: 0
            val familyShared: Boolean =
                if (appIdInt <= 0) {
                    false
                } else {
                    val license = SteamService.getPkgInfoOf(appIdInt)
                    val selfAcct = PrefManager.steamUserAccountId
                    val owners   = license?.ownerAccountId.orEmpty()
                    when {
                        owners.isEmpty()         -> false
                        owners.contains(selfAcct) -> false  // direct
                        owners.any { it in SteamService.familyMembers } -> true
                        else                     -> false
                    }
                }
            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                .setAppFamilyShared(familyShared)
            if (familyShared) {
                Timber.i("Bound app $appId is family-shared (owner outside self)")
            }

            val steamPath = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam")
            val userDataPath = File(steamPath, "userdata/$steamUserId64")
            val configPath = File(userDataPath, "config")
            configPath.mkdirs()

            val localConfigFile = File(configPath, "localconfig.vdf")

            if (localConfigFile.exists()) {
                val vdfContent = FileUtils.readFileAsString(localConfigFile.absolutePath)
                if (vdfContent != null) {
                    val vdfData = KeyValue.loadFromString(vdfContent)
                    if (vdfData != null) {
                        val app = vdfData["Software"]["Valve"]["Steam"]["apps"][appId]
                        val option = app.children.firstOrNull { it.name == "LaunchOptions" }
                        if (option != null) {
                            option.value = exeCommandLine.orEmpty()
                        } else {
                            app.children.add(KeyValue("LaunchOptions", exeCommandLine))
                        }
                        disableSteamCloudForApp(app)
                        vdfData.saveToFile(localConfigFile, false)
                    }
                }
            } else {
                val vdfData = KeyValue("UserLocalConfigStore")
                val option = KeyValue("LaunchOptions", exeCommandLine)
                val software = KeyValue("Software")
                val valve = KeyValue("Valve")
                val steam = KeyValue("Steam")
                val apps = KeyValue("apps")
                val app = KeyValue(appId)

                app.children.add(option)
                disableSteamCloudForApp(app)
                apps.children.add(app)
                steam.children.add(apps)
                valve.children.add(steam)
                software.children.add(valve)
                vdfData.children.add(software)

                vdfData.saveToFile(localConfigFile, false)
            }

            // Also update UserConfig + MountedConfig language in the ACF manifest
            val userLanguage =
                runCatching {
                    container
                        .getExtra("containerLanguage", null)
                        ?.takeIf { it.isNotEmpty() }
                        ?: PrefManager.containerLanguage
                }.getOrDefault(PrefManager.containerLanguage)

            val steamappsDir = File(steamPath, "steamapps")
            val appManifestFile = File(steamappsDir, "appmanifest_$appId.acf")

            if (appManifestFile.exists()) {
                val manifestContent = FileUtils.readFileAsString(appManifestFile.absolutePath)
                if (manifestContent != null) {
                    val manifestData = KeyValue.loadFromString(manifestContent)
                    if (manifestData != null) {
                        fun upsertLanguage(sectionName: String) {
                            val section = manifestData.children.firstOrNull { it.name == sectionName }
                            if (section != null) {
                                val langKey = section.children.firstOrNull { it.name == "language" }
                                if (langKey != null) {
                                    langKey.value = userLanguage
                                } else {
                                    section.children.add(KeyValue("language", userLanguage))
                                }
                            } else {
                                val newSection = KeyValue(sectionName)
                                newSection.children.add(KeyValue("language", userLanguage))
                                manifestData.children.add(newSection)
                            }
                        }
                        upsertLanguage("UserConfig")
                        upsertLanguage("MountedConfig")
                        manifestData.saveToFile(appManifestFile, false)
                        Timber.i("Updated ACF manifest language to $userLanguage for appId $appId")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to update or modify local config for appId $appId")
        }
    }

    /**
     * Queries PCGamingWiki's Cargo API for the highest Direct3D major version used by [steamAppId].
     * Invokes [callback] with the version number (9, 10, 11, or 12), or -1 on failure/unknown.
     */
    @JvmStatic
    fun fetchDirect3DMajor(
        steamAppId: Int,
        callback: (Int) -> Unit,
    ) {
        Timber.i("[DX Fetch] Starting fetchDirect3DMajor for appId=%d", steamAppId)
        val where = URLEncoder.encode("Infobox_game.Steam_AppID HOLDS \"$steamAppId\"", "UTF-8")
        val url =
            "https://pcgamingwiki.com/w/api.php" +
                "?action=cargoquery" +
                "&tables=Infobox_game,API" +
                "&join_on=Infobox_game._pageID=API._pageID" +
                "&fields=API.Direct3D_versions" +
                "&where=$where" +
                "&format=json"

        Timber.i("[DX Fetch] Query URL=%s", url)

        http.newCall(Request.Builder().url(url).build()).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    Timber.w(e, "[DX Fetch] Request failed for appId=%d", steamAppId)
                    callback(-1)
                }

                override fun onResponse(
                    call: Call,
                    res: Response,
                ) {
                    res.use {
                        try {
                            val body =
                                it.body?.string() ?: run {
                                    callback(-1)
                                    return
                                }
                            Timber.i("[DX Fetch] Raw body=%s", body)
                            val arr =
                                JSONObject(body).optJSONArray("cargoquery")
                                    ?: run {
                                        callback(-1)
                                        return
                                    }

                            val raw =
                                arr
                                    .optJSONObject(0)
                                    ?.optJSONObject("title")
                                    ?.optString("Direct3D versions")
                                    ?.trim() ?: ""

                            Timber.i("[DX Fetch] Direct3D versions string=%s", raw)

                            val dx =
                                Regex("\\b(9|10|11|12)\\b")
                                    .findAll(raw)
                                    .map { m -> m.value.toInt() }
                                    .maxOrNull() ?: -1

                            Timber.i("[DX Fetch] Resolved dx=%d for appId=%d", dx, steamAppId)
                            callback(dx)
                        } catch (e: Exception) {
                            Timber.w(e, "[DX Fetch] Parse error for appId=%d", steamAppId)
                            callback(-1)
                        }
                    }
                }
            },
        )
    }
}
