package com.winlator.cmod.feature.library

import android.content.Context
import android.util.Log
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.feature.stores.steam.utils.SteamUtils
import com.winlator.cmod.runtime.display.environment.ImageFs
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
}
