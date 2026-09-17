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

/**
 * The games WinNative downloaded, presented to the native Steam client as a library folder at
 * [GUEST_ROOT]: the manifests live in the runtime's rootfs and each game folder is bound in under
 * its Steam install name. winnative-steam-library registers the folder with the client.
 */
object LinuxSteamLibrary {
    const val GUEST_ROOT = "/mnt/winnative"
    private const val TAG = "LinuxSteamLibrary"

    /** Worker thread. Returns the proot bind specs (`host:guest`) for the installed games. */
    @JvmStatic
    fun prepare(
        context: Context,
        rootfs: File,
    ): List<String> {
        val steamapps = File(rootfs, "mnt/winnative/steamapps")
        val common = File(steamapps, "common")
        if (!common.isDirectory && !common.mkdirs()) return emptyList()
        val installed =
            runCatching {
                runBlocking(Dispatchers.IO) { PluviaDatabase.getInstance(context).appInfoDao().getAllInstalledAppIds() }
            }.getOrElse {
                Log.w(TAG, "Installed Steam games unavailable", it)
                emptyList()
            }.filter { SteamService.isAppInstalled(it) }
        val language = PrefManager.containerLanguage.ifBlank { "english" }
        val prefixManifests = File(ImageFs.find(context).wineprefix, "drive_c/Program Files (x86)/Steam/steamapps")
        val binds = ArrayList<String>()
        val kept = HashSet<String>()
        for (appId in installed) {
            val gameDir = File(SteamService.getAppDirPath(appId))
            if (!gameDir.isDirectory) continue
            SteamUtils.createAppManifest(context, appId, language)
            val manifest = File(prefixManifests, "appmanifest_$appId.acf")
            if (!manifest.isFile) continue
            val installDir = SteamService.getAppDirName(SteamService.getAppInfoOf(appId)).ifBlank { gameDir.name }
            manifest.copyTo(File(steamapps, manifest.name), overwrite = true)
            kept.add(manifest.name)
            File(common, installDir).mkdirs()
            binds.add("${gameDir.path}:$GUEST_ROOT/steamapps/common/$installDir")
        }
        steamapps.listFiles { _, name -> name.startsWith("appmanifest_") && name.endsWith(".acf") }
            ?.filter { it.name !in kept }
            ?.forEach { it.delete() }
        return binds
    }
}
