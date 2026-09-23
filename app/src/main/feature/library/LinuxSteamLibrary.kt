package com.winlator.cmod.feature.library

import android.content.Context
import android.util.Log
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.feature.stores.common.InstallOwnership
import com.winlator.cmod.feature.stores.common.InstallStore
import com.winlator.cmod.feature.stores.steam.data.AppInfo
import com.winlator.cmod.feature.stores.steam.enums.Marker
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.feature.stores.steam.utils.SteamUtils
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.shared.io.FileUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * The games WinNative downloaded, presented to the native Steam client as a library folder at
 * [GUEST_ROOT]: the manifests live in the runtime's rootfs and each game folder is bound in under
 * its Steam install name. winnative-steam-library registers the folder with the client.
 *
 * Both sides update titles, so the manifest for each is reconciled rather than rewritten: a build
 * the client installed is adopted into the app's own install records, and the app's manifest only
 * replaces the client's when the app holds the newer build.
 */
object LinuxSteamLibrary {
    const val GUEST_ROOT = "/mnt/winnative"
    private const val TAG = "LinuxSteamLibrary"
    private val BUILD_ID = Regex("^\\s*\"buildid\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)
    private val STATE_FLAGS = Regex("^\\s*\"StateFlags\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)
    private val INSTALL_DIR = Regex("^\\s*\"installdir\"\\s*\"([^\"]+)\"", RegexOption.MULTILINE)
    private val APP_NAME = Regex("^\\s*\"name\"\\s*\"([^\"]+)\"", RegexOption.MULTILINE)
    private val MANIFEST_NAME = Regex("^appmanifest_(\\d+)\\.acf$")

    /** The client's own library inside the rootfs, where it installs unless told otherwise. */
    private const val CLIENT_STEAMAPPS = "root/.local/share/Steam/steamapps"

    /**
     * StateFlags is a bit field: a finished download keeps this bit while an update is due (2) or
     * paused (512), so it is tested rather than compared.
     */
    private const val STATE_FULLY_INSTALLED = 4
    private const val STATE_UNINSTALLING = 2048

    /** Marks a library entry this scan wrote, so the scan may also take it away. */
    const val KEY_CLIENT_INSTALL = "linux_client_install"

    /** Runtimes and redistributables the client installs for itself; none of them is a game. */
    private val TOOL_APP_IDS = setOf(228980, 1070560, 1391110, 1493710, 3127680, 4183110, 4185400, 4427310, 4628740)
    private val DEPOT_MANIFEST = Regex("\"(\\d+)\"\\s*\\{[^{}]*?\"manifest\"\\s*\"(\\d+)\"")

    /** Worker thread. Returns the proot bind specs (`host:guest`) for the installed games. */
    @JvmStatic
    fun prepare(
        context: Context,
        rootfs: File,
    ): List<String> {
        val steamapps = File(rootfs, "mnt/winnative/steamapps")
        val common = File(steamapps, "common")
        if (!common.isDirectory && !common.mkdirs()) return emptyList()
        val recorded =
            runCatching {
                runBlocking(Dispatchers.IO) { PluviaDatabase.getInstance(context).appInfoDao().getAllInstalledAppIds() }
            }.getOrElse {
                Log.w(TAG, "Installed Steam games unavailable", it)
                emptyList()
            }
        val installed = recorded.filter { SteamService.isAppInstalled(it) }
        val language = PrefManager.containerLanguage.ifBlank { "english" }
        val prefixManifests = File(ImageFs.find(context).wineprefix, "drive_c/Program Files (x86)/Steam/steamapps")
        val binds = ArrayList<String>()
        for (appId in installed) {
            val gameDir = File(SteamService.getAppDirPath(appId))
            if (!gameDir.isDirectory) continue
            val runtimeManifest = File(steamapps, "appmanifest_$appId.acf")
            val runtimeBuild = buildId(runtimeManifest)
            if (runtimeBuild > 0L && runtimeBuild >= PrefManager.getInstalledBuildId(appId)) {
                adopt(appId, runtimeManifest, runtimeBuild, gameDir)
            }
            SteamUtils.createAppManifest(context, appId, language)
            val manifest = File(prefixManifests, "appmanifest_$appId.acf")
            if (!manifest.isFile) continue
            val installDir = SteamService.getAppDirName(SteamService.getAppInfoOf(appId)).ifBlank { gameDir.name }
            if (runtimeBuild == 0L || buildId(manifest) > runtimeBuild) {
                manifest.copyTo(runtimeManifest, overwrite = true)
            }
            File(common, installDir).mkdirs()
            binds.add("${gameDir.path}:$GUEST_ROOT/steamapps/common/$installDir")
        }
        // A manifest is only the app's to remove when the app recorded the title and has since
        // uninstalled it; the client's own installs are left to the client.
        val uninstalled = recorded.toSet() - installed.toSet()
        for (appId in uninstalled) {
            File(steamapps, "appmanifest_$appId.acf").delete()
        }
        return binds
    }

    /** The build a manifest records, or 0 when there is no readable manifest. */
    private fun buildId(manifest: File): Long {
        if (!manifest.isFile) return 0L
        val text = runCatching { manifest.readText() }.getOrElse { return 0L }
        return BUILD_ID.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    /**
     * Records a build the client installed where the app's own updater looks: the build id it
     * compares against the branch, and the depot manifests it compares against the store's.
     */
    private fun adopt(
        appId: Int,
        manifest: File,
        build: Long,
        gameDir: File,
    ) {
        val text = runCatching { manifest.readText() }.getOrElse { return }
        PrefManager.setInstalledBuildId(appId, build)
        val start = text.indexOf("\"InstalledDepots\"")
        if (start < 0) return
        val depots = DEPOT_MANIFEST.findAll(text, start).associate { it.groupValues[1] to it.groupValues[2] }
        if (depots.isEmpty()) return
        val configDir = File(gameDir, ".DepotDownloader")
        val configFile = File(configDir, "depot.config")
        runCatching {
            val config = if (configFile.isFile) JSONObject(configFile.readText()) else JSONObject()
            val ids = config.optJSONObject("installedManifestIDs") ?: JSONObject()
            for ((depot, gid) in depots) ids.put(depot, gid.toLongOrNull() ?: continue)
            config.put("installedManifestIDs", ids)
            if (configDir.isDirectory || configDir.mkdirs()) configFile.writeText(config.toString())
        }.onFailure { Log.w(TAG, "Could not record the client's build of $appId", it) }
    }

    /**
     * Worker thread. Titles the client installed on its own are recorded the way the app's own
     * downloads are - an install record and ownership marker for the store, and a STEAM entry in
     * the GameScope container that launches them through the client - so the library shows them
     * and their settings, artwork and removal follow the game data. A title the client has since
     * uninstalled loses its record and entry again. Returns whether the library changed.
     */
    @JvmStatic
    fun adoptClientInstalls(
        context: Context,
        rootfs: File,
    ): Boolean {
        val container = LinuxApps.gamescopeContainer(ContainerManager(context)) ?: return false
        PrefManager.init(context)
        val present = HashSet<Int>()
        var changed = false
        for (library in listOf(File(rootfs, CLIENT_STEAMAPPS), File(rootfs, "mnt/winnative/steamapps"))) {
            val manifests = library.listFiles() ?: continue
            for (manifest in manifests) {
                val appId = MANIFEST_NAME.find(manifest.name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (appId in TOOL_APP_IDS) continue
                val text = runCatching { manifest.readText() }.getOrNull() ?: continue
                val flags = STATE_FLAGS.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if ((flags and STATE_FULLY_INSTALLED) == 0 || (flags and STATE_UNINSTALLING) != 0) continue
                val installDir = INSTALL_DIR.find(text)?.groupValues?.get(1) ?: continue
                val gameDir = File(library, "common/$installDir")
                // A folder bound in from the app's own download is empty on this side of proot,
                // and that title is recorded already.
                if (!gameDir.isDirectory || gameDir.list().isNullOrEmpty()) continue
                present.add(appId)
                if (SteamService.isAppInstalled(appId)) continue
                val name = SteamService.getAppInfoOf(appId)?.name?.ifBlank { null }
                    ?: APP_NAME.find(text)?.groupValues?.get(1)
                    ?: installDir
                if (record(context, appId, gameDir, buildId(manifest)) && writeEntry(container, appId, name, gameDir)) {
                    Log.i(TAG, "Adopted the client's install of $appId at $gameDir")
                    changed = true
                }
            }
        }
        val entries = container.desktopDir.listFiles { f -> f.name.endsWith(".desktop") } ?: return changed
        for (file in entries) {
            val shortcut = Shortcut(container, file)
            if (shortcut.getExtra(KEY_CLIENT_INSTALL) != "1") continue
            val appId = shortcut.getExtra("app_id").toIntOrNull() ?: continue
            if (appId in present) continue
            if (release(context, appId) && file.delete()) {
                Log.i(TAG, "Dropped the entry for $appId, which the client no longer has installed")
                changed = true
            }
        }
        return changed
    }

    /** Records a client install where the store looks for its own downloads. */
    private fun record(
        context: Context,
        appId: Int,
        gameDir: File,
        build: Long,
    ): Boolean {
        val dir = gameDir.path
        if (!InstallOwnership.claim(dir, InstallStore.STEAM)) return false
        if (!MarkerUtils.addMarker(dir, Marker.DOWNLOAD_COMPLETE_MARKER)) return false
        return runCatching {
            runBlocking(Dispatchers.IO) {
                val db = PluviaDatabase.getInstance(context)
                db.steamAppDao().findApp(appId)?.let { app -> db.steamAppDao().update(app.copy(installDir = dir)) }
                val existing = db.appInfoDao().get(appId)
                if (existing != null) {
                    db.appInfoDao().update(existing.copy(isDownloaded = true, installPath = dir))
                } else {
                    db.appInfoDao().insert(AppInfo(id = appId, isDownloaded = true, installPath = dir))
                }
            }
            if (build > 0L) PrefManager.setInstalledBuildId(appId, build)
            true
        }.getOrElse {
            Log.w(TAG, "Could not record the client's install of $appId", it)
            false
        }
    }

    /** Forgets a client install the client itself has removed. */
    private fun release(
        context: Context,
        appId: Int,
    ): Boolean =
        runCatching {
            runBlocking(Dispatchers.IO) {
                val db = PluviaDatabase.getInstance(context)
                db.appInfoDao().get(appId)?.let { db.appInfoDao().update(it.copy(isDownloaded = false)) }
                db.steamAppDao().findApp(appId)?.let { db.steamAppDao().update(it.copy(installDir = "")) }
            }
            true
        }.getOrElse {
            Log.w(TAG, "Could not release the client's install of $appId", it)
            false
        }

    /**
     * The library entry: the same STEAM entry the store writes for its downloads, in the GameScope
     * container, where a launch hands the title to the client as a rungameid URL.
     */
    private fun writeEntry(
        container: Container,
        appId: Int,
        name: String,
        gameDir: File,
    ): Boolean {
        val desktopDir = container.desktopDir
        if (!desktopDir.exists() && !desktopDir.mkdirs()) return false
        val safeName = name.replace("/", "_").replace("\\", "_")
        val file = File(desktopDir, "$safeName.desktop")
        if (file.exists() && file.length() > 0L) return true
        val content =
            buildString {
                append("[Desktop Entry]\n")
                append("Type=Application\n")
                append("Name=$name\n")
                append("Exec=${LinuxApps.EXEC}\n")
                append("Icon=steam_icon_$appId\n")
                append("\n[Extra Data]\n")
                append("game_source=STEAM\n")
                append("app_id=$appId\n")
                append("container_id=${container.id}\n")
                append("game_install_path=${gameDir.path}\n")
                append("use_container_defaults=1\n")
                append("$KEY_CLIENT_INSTALL=1\n")
            }
        return runCatching { FileUtils.writeString(file, content); true }.getOrElse {
            Log.w(TAG, "Could not write the entry for $appId", it)
            false
        }
    }
}
