package com.winlator.cmod.feature.library

import android.content.Context
import android.os.StatFs
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.feature.stores.steam.data.AppInfo
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.shared.android.StoragePathUtils
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.stream.Collectors
import kotlinx.coroutines.CancellationException
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
     * With room for a second copy the source is only removed once every file has been written. Without
     * it, the game is moved a file at a time, so only the largest file needs room; a failure moves the
     * files back, and a move that is cut short resumes from where it stopped the next time it is run.
     */
    suspend fun move(
        context: Context,
        plan: Plan,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = plan.source
                val destination = plan.destination
                val journal = File(source, JOURNAL)
                check(source.isDirectory) { "${source.path} is not a directory" }
                val resuming = journal.isFile && journal.readText().trim() == destination.path
                check(resuming || !destination.exists() || destination.list()?.isEmpty() != false) {
                    "${destination.path} already exists"
                }
                val parent = destination.parentFile
                check(parent != null && (parent.isDirectory || parent.mkdirs())) {
                    "cannot create ${destination.parent}"
                }

                if (!resuming && destination.isDirectory) destination.delete()
                val renamed = !resuming && source.renameTo(destination)
                if (!renamed) {
                    val all = entries(source)
                    val files = all.filter { it.isFile && it != journal }
                    val total = files.sumOf { it.length() } + if (resuming && destination.isDirectory) sizeOf(destination) else 0L
                    val free = freeSpace(parent)
                    if (!resuming && free >= total) {
                        try {
                            copyTree(source, destination, total, onProgress)
                            check(sizeOf(destination) == total) { "copy of ${source.name} is incomplete" }
                        } catch (error: Throwable) {
                            deleteTree(destination)
                            throw error
                        }
                    } else {
                        val largest = files.maxOfOrNull { it.length() } ?: 0L
                        check(free >= largest + MOVE_HEADROOM) { "not enough room in ${parent.path}" }
                        journal.writeText(destination.path)
                        for (dir in all.filter { it.isDirectory }) {
                            val target = target(source, destination, dir)
                            check(target.isDirectory || target.mkdirs()) { "cannot create ${target.path}" }
                        }
                        val (markers, data) = files.partition { it.parentFile == source && it.name.startsWith(".") }
                        try {
                            moveFiles(source, destination, data, total, onProgress)
                            for (marker in markers) copyFile(marker, target(source, destination, marker))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            runCatching { moveFiles(destination, source, entries(destination).filter { it.isFile }, 0L) { _, _ -> } }
                                .onSuccess { deleteTree(destination); journal.delete() }
                                .onFailure { Timber.e(it, "Could not put %s back after a failed move", source.path) }
                            throw error
                        }
                    }
                }

                record(plan.appId, destination)
                runCatching { LinuxSteamLibrary.relink(ContainerManager(context), plan.appId, destination) }
                    .onFailure { Timber.w(it, "Could not update the entries of appId=%d", plan.appId) }
                if (!renamed && !deleteTree(source)) {
                    Timber.w("Moved appId=%d but could not fully remove %s", plan.appId, source.path)
                }
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

    private fun entries(root: File): List<File> =
        Files.walk(root.toPath()).use { stream -> stream.map { it.toFile() }.collect(Collectors.toList()) }

    private fun sizeOf(dir: File): Long = entries(dir).filter { it.isFile }.sumOf { it.length() }

    private fun freeSpace(dir: File): Long =
        runCatching {
            val stat = StatFs(dir.path)
            stat.availableBlocksLong * stat.blockSizeLong
        }.getOrDefault(Long.MAX_VALUE)

    private fun target(
        from: File,
        to: File,
        entry: File,
    ): File = File(to, entry.path.substring(from.path.length).trimStart(File.separatorChar))

    private fun copyFile(
        entry: File,
        target: File,
    ) {
        val dir = target.parentFile
        check(dir != null && (dir.isDirectory || dir.mkdirs())) { "cannot create ${target.parent}" }
        entry.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
        // The executable bit is what shared storage could not keep, so it is carried over here.
        if (entry.canExecute()) target.setExecutable(true, false)
        target.setLastModified(entry.lastModified())
    }

    private suspend fun copyTree(
        source: File,
        destination: File,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        var copied = 0L
        var reported = 0L
        for (entry in entries(source)) {
            coroutineContext.ensureActive()
            val target = target(source, destination, entry)
            if (entry.isDirectory) {
                check(target.isDirectory || target.mkdirs()) { "cannot create ${target.path}" }
                continue
            }
            if (!entry.isFile || entry.name == JOURNAL) continue
            copyFile(entry, target)
            copied += entry.length()
            // Progress redraws cost more than the copy does when a game holds thousands of files.
            if (copied - reported >= PROGRESS_STEP || copied == total) {
                reported = copied
                onProgress(copied, total)
            }
        }
    }

    /** Copies each file, checks it landed whole, then drops the original before taking the next. */
    private suspend fun moveFiles(
        from: File,
        to: File,
        files: List<File>,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        var moved = total - files.sumOf { it.length() }
        var reported = 0L
        for (entry in files) {
            coroutineContext.ensureActive()
            val target = target(from, to, entry)
            val length = entry.length()
            copyFile(entry, target)
            check(target.length() == length) { "copy of ${entry.path} is incomplete" }
            check(entry.delete()) { "cannot remove ${entry.path}" }
            moved += length
            if (moved - reported >= PROGRESS_STEP || moved == total) {
                reported = moved
                onProgress(moved, total)
            }
        }
    }

    private fun deleteTree(dir: File): Boolean {
        val root = dir.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
        var clean = true
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    path: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    path.toFile().setWritable(true, true)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    path: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (!runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) clean = false
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    path: Path,
                    exc: IOException,
                ): FileVisitResult {
                    clean = false
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    path: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    if (!runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) clean = false
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return clean && !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
    }

    private const val PROGRESS_STEP = 16L * 1024 * 1024
    private const val MOVE_HEADROOM = 256L * 1024 * 1024
    private const val JOURNAL = ".winnative-move"
}
