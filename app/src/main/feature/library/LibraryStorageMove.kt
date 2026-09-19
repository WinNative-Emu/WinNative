package com.winlator.cmod.feature.library

import android.content.Context
import android.os.StatFs
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.feature.stores.steam.data.AppInfo
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.shared.android.StoragePathUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import timber.log.Timber

/**
 * Moves an installed game between the download folder the user picked (external) and the app's
 * own storage (internal). The native Steam client runs a game from either. The app's own directory
 * is on the same partition, so the move costs no extra space and, when the two are the same
 * filesystem, is a rename rather than a copy.
 */
object LibraryStorageMove {
    /** Where a game currently sits, and so which way [move] would take it. */
    enum class Target { APP_STORAGE, DOWNLOAD_FOLDER }

    data class Plan(
        val appId: Int,
        val source: File,
        val destination: File,
        val target: Target,
    )

    /** The app's own library root, created on demand. */
    fun appStorageRoot(context: Context): File? = StoragePathUtils.appPrivateGamesRoot(context)

    /** The root new downloads go to, as the store settings resolve it. */
    fun downloadRoot(context: Context): File? =
        runCatching { SteamService.defaultAppInstallPath }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)

    private fun isUnder(
        candidate: File,
        root: File?,
    ): Boolean = root != null && StoragePathUtils.isSameOrDescendant(candidate, root)

    /**
     * Worker thread - resolves the install path. Returns the move this game is due, or null when
     * there is nothing sensible to offer: no install on disk, a download still running, no second
     * root to move to, or a download folder that is itself inside the app's own storage.
     */
    fun plan(
        context: Context,
        appId: Int,
    ): Plan? {
        if (appId <= 0) return null
        if (!SteamService.isAppInstalled(appId)) return null
        if (SteamService.getAppDownloadInfo(appId)?.isActive() == true) return null

        val source = runCatching { SteamService.getAppDirPath(appId) }.getOrNull().orEmpty()
        if (source.isBlank()) return null
        val sourceDir = File(source).absoluteFile
        if (!sourceDir.isDirectory) return null

        val dataDir = context.dataDir.absoluteFile
        val appRoot = appStorageRoot(context) ?: return null
        val downloadRoot = downloadRoot(context)
        // A download folder inside the app's own directory leaves no second location, so neither
        // direction is offered.
        if (isUnder(sourceDir, dataDir) && (downloadRoot == null || isUnder(downloadRoot, dataDir))) return null

        val toAppStorage = !isUnder(sourceDir, dataDir)
        val targetRoot = if (toAppStorage) appRoot else downloadRoot ?: return null
        if (isUnder(sourceDir, targetRoot)) return null

        val destination = File(targetRoot, sourceDir.name).absoluteFile
        if (StoragePathUtils.samePath(sourceDir, destination)) return null
        return Plan(
            appId = appId,
            source = sourceDir,
            destination = destination,
            target = if (toAppStorage) Target.APP_STORAGE else Target.DOWNLOAD_FOLDER,
        )
    }

    /**
     * Moves the game and records the new location. [onProgress] is called with bytes copied so far
     * and the total, and is not called at all when the move turns out to be a rename.
     *
     * The source is only removed once every file has been written, so a failure part way through
     * leaves the playable copy where it was.
     */
    suspend fun move(
        plan: Plan,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = plan.source
                val destination = plan.destination
                check(source.isDirectory) { "${source.path} is not a directory" }
                check(!destination.exists() || destination.list()?.isEmpty() != false) {
                    "${destination.path} already exists"
                }
                val parent = destination.parentFile
                check(parent != null && (parent.isDirectory || parent.mkdirs())) {
                    "cannot create ${destination.parent}"
                }

                val total = sizeOf(source)
                check(freeSpace(parent) >= total) { "not enough room in ${parent.path}" }

                if (!source.renameTo(destination)) {
                    copyTree(source, destination, total, onProgress)
                    check(sizeOf(destination) == total) { "copy of ${source.name} is incomplete" }
                    check(deleteTree(source)) { "could not remove ${source.path}" }
                }

                record(plan.appId, destination)
                destination
            }.onFailure { Timber.e(it, "Could not move appId=%d to %s", plan.appId, plan.destination.path) }
        }

    /** The two places the app looks an install up: the catalogue row and the durable install record. */
    private suspend fun record(
        appId: Int,
        destination: File,
    ) {
        val db = PluviaDatabase.getInstance()
        db.steamAppDao().findApp(appId)?.let { app ->
            db.steamAppDao().update(app.copy(installDir = destination.path))
        }
        val existing = db.appInfoDao().get(appId)
        if (existing != null) {
            db.appInfoDao().update(existing.copy(isDownloaded = true, installPath = destination.path))
        } else {
            db.appInfoDao().insert(AppInfo(id = appId, isDownloaded = true, installPath = destination.path))
        }
    }

    private fun sizeOf(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun freeSpace(dir: File): Long =
        runCatching {
            val stat = StatFs(dir.path)
            stat.availableBlocksLong * stat.blockSizeLong
        }.getOrDefault(Long.MAX_VALUE)

    private suspend fun copyTree(
        source: File,
        destination: File,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        var copied = 0L
        var reported = 0L
        val prefix = source.path.length
        for (entry in source.walkTopDown()) {
            coroutineContext.ensureActive()
            val target = File(destination, entry.path.substring(prefix).trimStart(File.separatorChar))
            if (entry.isDirectory) {
                check(target.isDirectory || target.mkdirs()) { "cannot create ${target.path}" }
                continue
            }
            entry.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            // The executable bit is what shared storage could not keep, so it is carried over here.
            if (entry.canExecute()) target.setExecutable(true, false)
            target.setLastModified(entry.lastModified())
            copied += entry.length()
            // Progress redraws cost more than the copy does when a game holds thousands of files.
            if (copied - reported >= PROGRESS_STEP || copied == total) {
                reported = copied
                onProgress(copied, total)
            }
        }
    }

    private fun deleteTree(dir: File): Boolean {
        dir.walkBottomUp().forEach { if (!it.delete() && it.exists()) return false }
        return !dir.exists()
    }

    private const val PROGRESS_STEP = 16L * 1024 * 1024
}
