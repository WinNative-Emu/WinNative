package com.winlator.cmod.feature.stores.itch.service

import android.content.Context
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.app.db.download.DownloadRecord
import com.winlator.cmod.app.service.download.DownloadCoordinator
import com.winlator.cmod.feature.stores.itch.data.ItchGame
import com.winlator.cmod.feature.stores.itch.data.ItchUpload
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.feature.stores.steam.events.AndroidEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private const val PROGRESS_NOTIFY_INTERVAL_MS = 200L

class ItchDownloadManager(
    private val context: Context,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobs = ConcurrentHashMap<Int, Job>()

    val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()

    private val pending = ConcurrentHashMap<Int, Request>()

    private fun requestFor(gameId: Int): Request? =
        pending[gameId] ?: ItchDownloadRequestStore.take(context, gameId)?.let { (game, upload, path) ->
            Request(game, upload, path).also { pending[gameId] = it }
        }

    data class Request(
        val game: ItchGame,
        val upload: ItchUpload,
        val installPath: String,
    )

    fun enqueue(
        game: ItchGame,
        upload: ItchUpload,
    ) {
        val existing = ItchLibrary.find(context, game.id)
        val installPath =
            existing?.installPath?.takeIf { it.isNotBlank() }
                ?: ItchConstants.gameInstallPath(context, game.title)
        pending[game.id] = Request(game, upload, installPath)
        ItchDownloadRequestStore.put(context, game, upload, installPath)
        if (existing == null) ItchLibrary.record(context, game, installPath, "")
        val info =
            activeDownloads.getOrPut(game.id.toString()) {
                DownloadInfo(jobCount = 1, gameId = game.id, downloadingAppIds = CopyOnWriteArrayList())
            }
        info.setActive(true)
        info.clearError()
        info.setTotalExpectedBytes(upload.sizeBytes)
        info.setDisplayTotalExpectedBytes(upload.sizeBytes)
        info.initializeBytesDownloaded(0L)
        info.updateStatus(DownloadPhase.QUEUED)
        info.updateCurrentFileName(upload.fileName)
        notifyUi(game.id)

        scope.launch {
            val decision =
                DownloadCoordinator.requestSlot(
                    store = DownloadRecord.STORE_ITCH,
                    storeGameId = game.id.toString(),
                    title = game.title,
                    artUrl = game.coverUrl,
                    installPath = installPath,
                    bytesTotal = upload.sizeBytes,
                )
            if (decision is DownloadCoordinator.Decision.Start) start(decision.record)
        }
    }

    fun start(record: DownloadRecord) {
        val gameId = record.storeGameId.toIntOrNull() ?: return
        val request = requestFor(gameId)
        if (request == null) {
            scope.launch {
                DownloadCoordinator.notifyFinished(
                    DownloadRecord.STORE_ITCH,
                    record.storeGameId,
                    DownloadRecord.STATUS_FAILED,
                    context.getString(com.winlator.cmod.R.string.itch_store_download_needs_restart),
                )
            }
            return
        }
        if (jobs[gameId]?.isActive == true) return

        val info =
            activeDownloads.getOrPut(record.storeGameId) {
                DownloadInfo(jobCount = 1, gameId = gameId, downloadingAppIds = CopyOnWriteArrayList())
            }
        info.setActive(true)
        info.updateStatus(DownloadPhase.DOWNLOADING)
        notifyUi(gameId)

        val job =
            scope.launch {
                runTransfer(request, info)
            }
        jobs[gameId] = job
        info.setDownloadJob(job)
    }

    private suspend fun runTransfer(
        request: Request,
        info: DownloadInfo,
    ) {
        val game = request.game
        val installDir = File(request.installPath)
        val cacheDir = File(context.cacheDir, "itch-downloads").apply { mkdirs() }
        val payload = File(cacheDir, "${game.id}-${sanitizeFileName(request.upload.fileName)}")
        try {
            installDir.mkdirs()
            info.updateStatus(DownloadPhase.DOWNLOADING, context.getString(com.winlator.cmod.R.string.itch_store_status_preparing))

            val downloadPage = ItchWebClient.resolveDownloadPage(context, game.url)
            val pageHtml = ItchWebClient.getHtml(context, downloadPage)
            val token =
                ItchWebClient.csrfToken(pageHtml)
                    ?: throw IOException(context.getString(com.winlator.cmod.R.string.itch_store_error_no_token))
            val uploads = ItchCatalog.parseUploads(pageHtml)
            val upload =
                uploads.firstOrNull { it.id == request.upload.id }
                    ?: ItchCatalog.pickWindowsUpload(uploads)
                    ?: throw IOException(context.getString(com.winlator.cmod.R.string.itch_store_error_no_uploads))

            var resumeFrom = if (payload.isFile) payload.length() else 0L
            if (resumeFrom > 0L && upload.sizeBytes > 0L && resumeFrom >= upload.sizeBytes) resumeFrom = 0L
            if (resumeFrom == 0L) payload.delete()

            val fileUrl = ItchWebClient.mintFileUrl(context, game.url, upload.id, token)
            ItchWebClient.openStream(context, fileUrl, resumeFrom).use { response ->
                val body = response.body ?: throw IOException("Empty download body")
                val supportsResume = response.code == 206
                val offset = if (supportsResume) resumeFrom else 0L
                if (!supportsResume && resumeFrom > 0L) payload.delete()
                val total = if (body.contentLength() > 0L) body.contentLength() + offset else upload.sizeBytes
                if (total > 0L) {
                    info.setTotalExpectedBytes(total)
                    info.setDisplayTotalExpectedBytes(total)
                }
                info.initializeBytesDownloaded(offset)
                info.updateStatus(DownloadPhase.DOWNLOADING, upload.fileName)

                java.io.FileOutputStream(payload, offset > 0L).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    val input = body.byteStream()
                    var lastNotify = 0L
                    while (true) {
                        if (!info.isActive()) throw CancellationException("Stopped")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        info.updateBytesDownloaded(read.toLong())
                        info.setProgress(info.getProgress())
                        val now = System.currentTimeMillis()
                        if (now - lastNotify > PROGRESS_NOTIFY_INTERVAL_MS) {
                            lastNotify = now
                            val progress = info.getBytesProgress()
                            DownloadCoordinator.updateProgress(
                                DownloadRecord.STORE_ITCH,
                                game.id.toString(),
                                progress.first,
                                progress.second,
                            )
                            notifyUi(game.id)
                        }
                    }
                }
            }

            info.updateStatus(DownloadPhase.DOWNLOADING, context.getString(com.winlator.cmod.R.string.itch_store_status_extracting))
            notifyUi(game.id)

            val result =
                ItchInstaller.install(
                    context = context,
                    title = game.title,
                    installDir = installDir,
                    payload = payload,
                    onProgress = { fraction ->
                        info.setProgress(fraction)
                        notifyUi(game.id)
                    },
                    isActive = { info.isActive() },
                )

            val executable = result.executable
            if (executable != null) {
                val coverArt = downloadCoverArt(game)
                com.winlator.cmod.app.shell
                    .addCustomGame(context, game.title, executable.absolutePath, installDir.absolutePath, coverArt)
                coverArt?.delete()
                ItchLibrary.record(context, game, installDir.absolutePath, executable.absolutePath, upload)
                info.updateStatus(DownloadPhase.COMPLETE, context.getString(com.winlator.cmod.R.string.itch_store_status_added))
            } else {
                ItchLibrary.record(context, game, installDir.absolutePath, "", upload)
                info.updateStatus(DownloadPhase.COMPLETE, context.getString(com.winlator.cmod.R.string.itch_store_status_no_executable))
            }
            info.setActive(false)
            ItchCollections.addGame(context, game)
            pending.remove(game.id)
            ItchDownloadRequestStore.remove(context, game.id)
            DownloadCoordinator.notifyFinished(DownloadRecord.STORE_ITCH, game.id.toString(), DownloadRecord.STATUS_COMPLETE)
            PluviaApp.events.emit(AndroidEvent.LibraryArtworkChanged)
            com.winlator.cmod.app.shell.UnifiedActivity
                .refreshLibrary()
            notifyUi(game.id)
        } catch (cancellation: CancellationException) {
            Timber.i("[Itch] transfer stopped for ${game.title}")
            notifyUi(game.id)
            throw cancellation
        } catch (error: ItchInstaller.UnsupportedArchiveException) {
            failWith(info, game.id, context.getString(com.winlator.cmod.R.string.itch_store_error_unsupported_archive, error.archiveName))
        } catch (error: Exception) {
            Timber.e(error, "[Itch] download failed for ${game.title}")
            failWith(info, game.id, error.message ?: context.getString(com.winlator.cmod.R.string.itch_store_error_generic))
        } finally {
            jobs.remove(game.id)
        }
    }

    private suspend fun failWith(
        info: DownloadInfo,
        gameId: Int,
        message: String,
    ) {
        info.markError(message)
        info.updateStatus(DownloadPhase.FAILED, message)
        DownloadCoordinator.notifyFinished(DownloadRecord.STORE_ITCH, gameId.toString(), DownloadRecord.STATUS_FAILED, message)
        notifyUi(gameId)
    }

    fun pause(record: DownloadRecord) {
        val gameId = record.storeGameId.toIntOrNull() ?: return
        activeDownloads[record.storeGameId]?.let { info ->
            info.setActive(false)
            info.updateStatus(DownloadPhase.PAUSED)
        }
        jobs.remove(gameId)?.cancel()
        notifyUi(gameId)
    }

    fun cancel(record: DownloadRecord) {
        val gameId = record.storeGameId.toIntOrNull() ?: return
        activeDownloads[record.storeGameId]?.let { info ->
            info.setActive(false)
            info.updateStatus(DownloadPhase.CANCELLED)
        }
        jobs.remove(gameId)?.cancel()
        pending.remove(gameId)
        ItchDownloadRequestStore.remove(context, gameId)
        if (!ItchService.isInstalled(context, gameId)) ItchLibrary.remove(context, gameId)
        scope.launch {
            runCatching {
                File(context.cacheDir, "itch-downloads")
                    .listFiles { file -> file.name.startsWith("$gameId-") }
                    ?.forEach { it.delete() }
            }
        }
        notifyUi(gameId)
    }

    fun isTransferActive(record: DownloadRecord): Boolean {
        val gameId = record.storeGameId.toIntOrNull() ?: return false
        return jobs[gameId]?.isActive == true
    }

    fun clearFinished() {
        val finished =
            activeDownloads
                .filterValues {
                    val status = it.getStatusFlow().value
                    status == DownloadPhase.COMPLETE || status == DownloadPhase.CANCELLED || status == DownloadPhase.FAILED
                }.keys
        finished.forEach { key ->
            activeDownloads.remove(key)
            key.toIntOrNull()?.let { notifyUi(it) }
        }
    }

    fun shutdown() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun downloadCoverArt(game: ItchGame): File? {
        val url = game.coverUrl
        if (url.isBlank()) return null
        return runCatching {
            val target = File.createTempFile("itch-cover-", ".img", context.cacheDir)
            ItchWebClient.openStream(context, url, 0L).use { response ->
                val body = response.body ?: throw IOException("Empty cover art body")
                target.outputStream().use { output -> body.byteStream().copyTo(output) }
            }
            if (target.length() > 0L) {
                target
            } else {
                target.delete()
                null
            }
        }.getOrNull()
    }

    private fun notifyUi(gameId: Int) {
        PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(gameId, false))
    }

    private fun sanitizeFileName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
}
