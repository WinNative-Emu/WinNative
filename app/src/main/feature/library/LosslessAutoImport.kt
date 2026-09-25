package com.winlator.cmod.feature.library

import android.content.Context
import android.net.Uri
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.display.lsfg.LosslessScaling
import java.io.File

object LosslessAutoImport {
    const val STEAM_APP_ID = 993090

    const val RESULT_READY = 0
    const val RESULT_IMPORTED = 1
    const val RESULT_UPDATED = 2
    const val RESULT_NOT_OWNED = 3
    const val RESULT_NOT_FOUND = 4
    const val RESULT_FAILED = 5

    private const val DLL_NAME = "Lossless.dll"
    private const val INSTALL_DIR_NAME = "Lossless Scaling"

    class Outcome(val result: Int, val sourceName: String)

    fun isOwned(): Boolean {
        return true
        /*val licensed = runCatching { SteamService.getPkgInfoOf(STEAM_APP_ID) != null }.getOrDefault(false)
        if (licensed) return true
        return runCatching { SteamService.getInstalledApp(STEAM_APP_ID) != null }.getOrDefault(false)*/
    }

    fun findDll(context: Context): File? {
        val candidates = LinkedHashSet<File>()
        for (dir in steamCandidateDirs()) {
            val dll = File(dir, DLL_NAME)
            if (dll.isFile && dll.canRead()) candidates += dll
        }
        runCatching { LosslessScaling.findInContainers(ContainerManager(context).containers) }
            .getOrDefault(emptyList())
            .forEach { candidates += it }

        return candidates.maxWithOrNull(
            compareBy<File> { LosslessScaling.variantRank(LosslessScaling.dllVariant(it)) }
                .thenBy { it.length() },
        )
    }

    fun sync(context: Context): Outcome {
        if (!isOwned()) {
            return Outcome(if (LosslessScaling.isInstalled(context)) RESULT_READY else RESULT_NOT_OWNED, "")
        }

        val dll = findDll(context)
        if (dll == null) {
            return Outcome(if (LosslessScaling.isInstalled(context)) RESULT_READY else RESULT_NOT_FOUND, "")
        }

        val name = dll.parentFile?.name.orEmpty()
        val installed = LosslessScaling.isInstalled(context)
        if (installed && !LosslessScaling.isCacheStale(context, dll)) return Outcome(RESULT_READY, name)

        val status = LosslessScaling.installFrom(context, dll)
        if (status != LosslessScaling.STATUS_OK) return Outcome(RESULT_FAILED, name)
        return Outcome(if (installed) RESULT_UPDATED else RESULT_IMPORTED, name)
    }

    fun importFrom(context: Context, uri: Uri): Outcome {
        if (!isOwned()) return Outcome(RESULT_NOT_OWNED, "")
        val status = LosslessScaling.installFrom(context, uri)
        if (status != LosslessScaling.STATUS_OK) return Outcome(RESULT_FAILED, "")
        return Outcome(RESULT_IMPORTED, uri.lastPathSegment?.substringAfterLast('/').orEmpty())
    }

    fun importFrom(context: Context, dll: File): Outcome {
        val name = dll.parentFile?.name?.takeIf { it.isNotBlank() } ?: dll.name
        if (!isOwned()) return Outcome(RESULT_NOT_OWNED, name)
        val status = LosslessScaling.installFrom(context, dll)
        if (status != LosslessScaling.STATUS_OK) return Outcome(RESULT_FAILED, name)
        return Outcome(RESULT_IMPORTED, name)
    }

    private fun steamCandidateDirs(): List<File> {
        val dirs = LinkedHashSet<File>()

        runCatching { SteamService.getInstalledApp(STEAM_APP_ID)?.installPath }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { dirs += File(it) }

        runCatching { SteamService.getAppDirPath(STEAM_APP_ID) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { dirs += File(it) }

        runCatching { SteamService.allInstallPaths }
            .getOrDefault(emptyList())
            .forEach { base -> if (base.isNotBlank()) dirs += File(base, INSTALL_DIR_NAME) }

        runCatching { SteamService.defaultAppInstallPath }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { dirs += File(it, INSTALL_DIR_NAME) }

        return dirs.toList()
    }
}
