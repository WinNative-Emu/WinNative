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
    
    // Имя папки, куда пользователь должен положить файл (опционально, можно класть и в корень files/)
    const val USER_PROVIDED_DIR_NAME = "LosslessScaling_User"

    class Outcome(val result: Int, val sourceName: String)

    fun isOwned(): Boolean {
        val licensed = runCatching { SteamService.getPkgInfoOf(STEAM_APP_ID) != null }.getOrDefault(false)
        if (licensed) return true
        return runCatching { SteamService.getInstalledApp(STEAM_APP_ID) != null }.getOrDefault(false)
    }

    /**
     * Проверяет, является ли файл доверенным (внутренняя память или внешняя приватная папка приложения)
     */
    private fun isTrustedSource(context: Context, file: File): Boolean {
        val isInternal = file.absolutePath.startsWith(context.filesDir.absolutePath) ||
                         file.absolutePath.startsWith(context.cacheDir.absolutePath)
        
        val externalDir = context.getExternalFilesDir(null)
        val isExternalPrivate = externalDir != null && file.absolutePath.startsWith(externalDir.absolutePath)
        
        return isInternal || isExternalPrivate
    }

    /**
     * Ищет файл, который пользователь положил в Android/data/com.winlator.cmod/files/
     */
    fun findUserProvidedDll(context: Context): File? {
        val baseDir = context.getExternalFilesDir(null) ?: return null
        
        // 1. Проверяем корень папки files/
        val directDll = File(baseDir, DLL_NAME)
        if (directDll.isFile && directDll.canRead()) return directDll
        
        // 2. Проверяем специальную подпапку для порядка
        val subDir = File(baseDir, USER_PROVIDED_DIR_NAME)
        if (subDir.isDirectory) {
            val subDll = File(subDir, DLL_NAME)
            if (subDll.isFile && subDll.canRead()) return subDll
        }
        
        return null
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
        // Сначала проверяем, не предоставил ли пользователь файл вручную
        val userDll = findUserProvidedDll(context)
        if (userDll != null) {
            val installed = LosslessScaling.isInstalled(context)
            if (installed && !LosslessScaling.isCacheStale(context, userDll)) {
                return Outcome(RESULT_READY, "User Provided")
            }
            val status = LosslessScaling.installFrom(context, userDll)
            val name = "User Provided (${userDll.parentFile?.name})"
            if (status != LosslessScaling.STATUS_OK) return Outcome(RESULT_FAILED, name)
            return Outcome(if (installed) RESULT_UPDATED else RESULT_IMPORTED, name)
        }

        // Если нет, идем по стандартному пути Steam
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
        
        // ГЛАВНОЕ ИЗМЕНЕНИЕ: Если файл из доверенной папки, проверку Steam пропускаем
        if (!isTrustedSource(context, dll) && !isOwned()) {
            return Outcome(RESULT_NOT_OWNED, name)
        }
        
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
