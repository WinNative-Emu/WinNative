package com.winlator.cmod.feature.stores.itch.service

import android.content.Context
import com.winlator.cmod.app.db.download.DownloadRecord
import com.winlator.cmod.app.service.download.DownloadCoordinator
import com.winlator.cmod.feature.stores.itch.data.ItchBrowseFilter
import com.winlator.cmod.feature.stores.itch.data.ItchGame
import com.winlator.cmod.feature.stores.itch.data.ItchUpdateInfo
import com.winlator.cmod.feature.stores.itch.data.ItchGameDetails
import com.winlator.cmod.feature.stores.itch.data.ItchUpload
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

object ItchService {
    @Volatile
    private var manager: ItchDownloadManager? = null

    private val dispatcher =
        object : DownloadCoordinator.Dispatcher {
            override fun startQueued(record: DownloadRecord) {
                manager?.start(record)
            }

            override fun pauseRunning(record: DownloadRecord) {
                manager?.pause(record)
            }

            override fun cancelRunning(record: DownloadRecord) {
                manager?.cancel(record)
            }

            override fun isTransferActive(record: DownloadRecord): Boolean = manager?.isTransferActive(record) ?: false
        }

    @Synchronized
    fun start(context: Context) {
        if (manager != null) return
        manager = ItchDownloadManager(context.applicationContext)
        DownloadCoordinator.registerDispatcher(DownloadRecord.STORE_ITCH, dispatcher)
        ItchLibrary.prune(context.applicationContext)
        Timber.i("[Itch] service started")
    }

    @Synchronized
    fun stop() {
        manager?.shutdown()
        manager = null
        DownloadCoordinator.unregisterDispatcher(DownloadRecord.STORE_ITCH)
    }

    fun isLoggedIn(context: Context): Boolean = ItchAuthManager.isLoggedIn(context)

    fun userName(context: Context): String = ItchAuthManager.userName(context)

    suspend fun refreshProfile(context: Context): String =
        withContext(Dispatchers.IO) {
            ItchAuthManager.refreshProfile(context)
            ItchAuthManager.userName(context)
        }

    suspend fun signOut(context: Context) =
        withContext(Dispatchers.IO) {
            ItchAuthManager.signOut(context)
        }

    suspend fun browse(
        context: Context,
        filter: ItchBrowseFilter,
        page: Int,
    ): List<ItchGame> = withContext(Dispatchers.IO) { ItchCatalog.browse(context, filter, page) }

    suspend fun search(
        context: Context,
        query: String,
    ): List<ItchGame> = withContext(Dispatchers.IO) { ItchCatalog.search(context, query) }

    suspend fun owned(
        context: Context,
        page: Int,
    ): List<ItchGame> = withContext(Dispatchers.IO) { ItchOwnedGames.fetch(context, page) }

    suspend fun details(
        context: Context,
        game: ItchGame,
    ): ItchGameDetails = withContext(Dispatchers.IO) { ItchCatalog.details(context, game) }

    suspend fun uploads(
        context: Context,
        game: ItchGame,
    ): List<ItchUpload> =
        withContext(Dispatchers.IO) {
            val downloadPage = ItchWebClient.resolveDownloadPage(context, game.url)
            val html = ItchWebClient.getHtml(context, downloadPage)
            val parsed = ItchCatalog.parseUploads(html)
            Timber.i("[Itch] %s listed %d uploads", game.title, parsed.size)
            parsed
        }

    fun download(
        context: Context,
        game: ItchGame,
        upload: ItchUpload,
    ) {
        start(context)
        manager?.enqueue(game, upload)
    }

    fun installPath(
        context: Context,
        game: ItchGame,
    ): String = ItchLibrary.find(context, game.id)?.installPath ?: ItchConstants.gameInstallPath(context, game.title)

    fun isInstalled(
        context: Context,
        gameId: Int,
    ): Boolean {
        val entry = ItchLibrary.find(context, gameId) ?: return false
        return ItchInstaller.isInstalled(File(entry.installPath))
    }

    fun installedIds(context: Context): Set<Int> = ItchLibrary.installedIds(context)

    suspend fun checkForUpdate(
        context: Context,
        game: ItchGame,
    ): ItchUpdateInfo =
        withContext(Dispatchers.IO) {
            val entry = ItchLibrary.find(context, game.id)
            val uploads = uploads(context, game)
            val latest =
                uploads.firstOrNull { it.id == entry?.uploadId && entry.uploadId != 0L }
                    ?: ItchCatalog.pickWindowsUpload(uploads)
            if (entry == null || latest == null) {
                return@withContext ItchUpdateInfo(false, null, "", latest?.buildLabel.orEmpty())
            }
            val known = entry.uploadId != 0L || entry.uploadedAt.isNotEmpty()
            val changed =
                when {
                    !known -> true
                    entry.uploadId != 0L && latest.id != entry.uploadId -> true
                    entry.uploadedAt.isNotEmpty() && latest.uploadedAt.isNotEmpty() &&
                        latest.uploadedAt != entry.uploadedAt -> true
                    entry.uploadVersion.isNotEmpty() && latest.version.isNotEmpty() &&
                        latest.version != entry.uploadVersion -> true
                    entry.uploadSize > 0L && latest.sizeBytes > 0L && latest.sizeBytes != entry.uploadSize -> true
                    else -> false
                }
            ItchUpdateInfo(changed, latest.takeIf { changed }, entry.buildLabel, latest.buildLabel)
        }

    suspend fun checkInstalledForUpdate(
        context: Context,
        gameId: Int,
    ): ItchUpdateInfo? {
        val game = installedGame(context, gameId) ?: return null
        return checkForUpdate(context, game)
    }

    fun downloadInstalledUpdate(
        context: Context,
        gameId: Int,
        upload: ItchUpload,
    ): Boolean {
        val game = installedGame(context, gameId) ?: return false
        download(context, game, upload)
        return true
    }

    private fun installedGame(
        context: Context,
        gameId: Int,
    ): ItchGame? {
        val entry = ItchLibrary.find(context, gameId) ?: return null
        if (entry.url.isBlank()) return null
        return ItchGame(id = entry.id, title = entry.title, url = entry.url, coverUrl = entry.coverUrl)
    }

    fun installedGameId(
        context: Context,
        installFolder: String,
        title: String,
    ): Int? {
        val entries = ItchLibrary.all(context)
        val folder = installFolder.trim().trimEnd('/')
        if (folder.isNotEmpty()) {
            entries.firstOrNull { it.installPath.trimEnd('/') == folder }?.let { return it.id }
        }
        return entries.firstOrNull { it.title.equals(title.trim(), ignoreCase = true) }?.id
    }

    fun uninstall(
        context: Context,
        gameId: Int,
    ): Boolean {
        val entry = ItchLibrary.find(context, gameId) ?: return false
        val dir = File(entry.installPath)
        val check =
            com.winlator.cmod.feature.stores.common.StoreInstallPathSafety
                .checkInstallDirDelete(context, dir.absolutePath)
        if (!check.allowed) {
            Timber.w("[Itch] refusing to delete ${dir.absolutePath}: ${check.reason}")
            return false
        }
        val removed = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        if (removed) {
            com.winlator.cmod.app.shell
                .removeCustomGame(context, entry.installPath)
            ItchLibrary.remove(context, gameId)
        }
        return removed
    }

    fun getAllDownloads(): Map<String, DownloadInfo> = manager?.activeDownloads?.toMap() ?: emptyMap()

    fun downloadInfo(gameId: Int): DownloadInfo? = manager?.activeDownloads?.get(gameId.toString())

    fun pauseDownload(gameId: String) {
        DownloadCoordinator.runOnScope { DownloadCoordinator.pause(DownloadRecord.STORE_ITCH, gameId) }
    }

    fun resumeDownload(gameId: String) {
        DownloadCoordinator.runOnScope { DownloadCoordinator.resume(DownloadRecord.STORE_ITCH, gameId) }
    }

    fun cancelDownload(gameId: String) {
        DownloadCoordinator.runOnScope { DownloadCoordinator.cancel(DownloadRecord.STORE_ITCH, gameId) }
    }

    fun pauseAll() {
        DownloadCoordinator.runOnScope { DownloadCoordinator.pauseAll() }
    }

    fun resumeAll() {
        DownloadCoordinator.runOnScope { DownloadCoordinator.resumeAll() }
    }

    fun cancelAll() {
        DownloadCoordinator.runOnScope { DownloadCoordinator.cancelAll() }
    }

    fun clearCompletedDownloads() {
        manager?.clearFinished()
    }
}
