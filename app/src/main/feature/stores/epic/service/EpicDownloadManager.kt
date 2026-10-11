package com.winlator.cmod.feature.stores.epic.service
import android.content.Context
import android.util.Log
import com.winlator.cmod.app.db.download.DownloadRecord
import com.winlator.cmod.app.service.download.DownloadCoordinator
import com.winlator.cmod.feature.stores.common.InstallOwnership
import com.winlator.cmod.feature.stores.common.InstallStore
import com.winlator.cmod.feature.stores.epic.data.EpicGame
import com.winlator.cmod.feature.stores.epic.service.manifest.ChunkInfo
import com.winlator.cmod.feature.stores.epic.service.manifest.ChunkPart
import com.winlator.cmod.feature.stores.epic.service.manifest.EpicManifest
import com.winlator.cmod.feature.stores.epic.service.manifest.FileManifest
import com.winlator.cmod.feature.stores.epic.service.manifest.ManifestUtils
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.feature.stores.steam.enums.Marker
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import javax.inject.Inject
import javax.inject.Singleton

// Downloads Epic games from chunked Epic manifests.
@Singleton
class EpicDownloadManager
    @Inject
    constructor(
        private val epicManager: EpicManager,
    ) {
        private val okHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .connectionPool(okhttp3.ConnectionPool(32, 5, TimeUnit.MINUTES))
                .build()

        companion object {
            private const val MAX_PARALLEL_DOWNLOADS = 12
            private const val CHUNK_BUFFER_SIZE = 1024 * 1024
            private const val MAX_CHUNK_RETRIES = 5
            private const val RETRY_DELAY_MS = 1000L
            private const val RESUME_STATE_FILE = "epic_applied_chunks.txt"
        }

        // Downloads and installs an Epic game.
        suspend fun downloadGame(
            context: Context,
            game: EpicGame,
            installPath: String,
            downloadInfo: DownloadInfo,
            containerLanguage: String = EpicConstants.EPIC_FALLBACK_CONTAINER_LANGUAGE,
            dlcIds: List<Int>,
            commonRedistDir: File? = null,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    Timber.tag("Epic").i("Starting download for ${game.title} to $installPath")

                    File(installPath).mkdirs()
                    MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

                    val gameId = game.id
                    com.winlator.cmod.app.PluviaApp.events.emitJava(
                        com.winlator.cmod.feature.stores.steam.events.AndroidEvent
                            .DownloadStatusChanged(gameId, true),
                    )

                    val dlcsToDownload =
                        if (dlcIds.size > 0) {
                            try {
                                Timber.tag("Epic").d("User has opted to download ${dlcIds.size} DLC titles for game: ${game.title}")
                                val dlcs = epicManager.getGamesById(dlcIds)
                                if (dlcs.isNotEmpty()) {
                                    Timber.tag("Epic").d("Found ${dlcs.size} DLC(s) for ${game.title}")
                                }
                                dlcs.filter { dlcIds.contains(it.id) }
                            } catch (e: Exception) {
                                Timber.tag("Epic").e(e, "Error checking for DLCs, continuing without")
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }

                    Timber.tag("Epic").i("Filtered to ${dlcsToDownload.size} DLC(s) for ${game.title}")

                    val manifestResult =
                        epicManager.fetchManifestFromEpic(
                            context,
                            game.namespace,
                            game.catalogId,
                            game.appName,
                        )
                    if (manifestResult.isFailure) {
                        return@withContext Result.failure(
                            manifestResult.exceptionOrNull() ?: Exception("Failed to fetch manifest"),
                        )
                    }

                    val manifestData = manifestResult.getOrNull()!!

                    val cdnUrls = selectCdnUrls(manifestData.cdnUrls)
                    if (cdnUrls.isEmpty()) {
                        return@withContext Result.failure(Exception("No usable CDN URLs in manifest"))
                    }

                    Timber.tag("Epic").d("Manifest fetched with ${cdnUrls.size} CDN URLs, parsing...")

                    val manifest = EpicManifest.readAll(manifestData.manifestBytes)

                    val selectedTags = EpicConstants.containerLanguageToEpicInstallTags(containerLanguage)
                    val files = ManifestUtils.getFilesForSelectedInstallTags(manifest, selectedTags)
                    val chunks = ManifestUtils.getRequiredChunksForFileList(manifest, files)

                    if (selectedTags.isNotEmpty()) {
                        Timber.tag("Epic").i("Container language '$containerLanguage' -> install tags: ${selectedTags.joinToString()}")
                    }

                    val chunkDir = manifest.getChunkDir()

                    if (chunks.isEmpty()) {
                        return@withContext Result.failure(Exception("No chunk data in manifest"))
                    }
                    if (files.isEmpty()) {
                        val msg =
                            if (selectedTags.isNotEmpty()) {
                                "No files found for the selected language. This game may not support this language."
                            } else {
                                "No file manifest in manifest"
                            }
                        return@withContext Result.failure(Exception(msg))
                    }

                    var totalDownloadSize = chunks.sumOf { it.fileSize }
                    var totalInstalledSize = files.sumOf { it.fileSize }
                    val baseGameSize = totalDownloadSize
                    val baseGameInstalledSize = totalInstalledSize

                    val dlcManifestData = mutableListOf<Pair<EpicGame, EpicManager.ManifestResult>>()
                    if (dlcsToDownload.isNotEmpty()) {
                        downloadInfo.updateStatusMessage("Calculating DLC sizes...")
                        for (dlc in dlcsToDownload) {
                            try {
                                val dlcManifestResult =
                                    epicManager.fetchManifestFromEpic(
                                        context,
                                        dlc.namespace,
                                        dlc.catalogId,
                                        dlc.appName,
                                    )
                                if (dlcManifestResult.isSuccess) {
                                    val dlcManifest = dlcManifestResult.getOrNull()!!
                                    val dlcParsed = EpicManifest.readAll(dlcManifest.manifestBytes)
                                    val dlcFiles = ManifestUtils.getFilesForSelectedInstallTags(dlcParsed, selectedTags)
                                    val dlcChunks = ManifestUtils.getRequiredChunksForFileList(dlcParsed, dlcFiles)
                                    val dlcDownloadSize = dlcChunks.sumOf { it.fileSize }
                                    val dlcInstalledSize = dlcFiles.sumOf { it.fileSize }
                                    totalDownloadSize += dlcDownloadSize
                                    totalInstalledSize += dlcInstalledSize
                                    dlcManifestData.add(dlc to dlcManifest)
                                    Timber.tag("Epic").i("DLC ${dlc.title} size: ${dlcDownloadSize / 1_000_000} MB")
                                } else {
                                    Timber.tag("Epic").w("Failed to fetch manifest for DLC ${dlc.title}, will skip")
                                }
                            } catch (e: Exception) {
                                Timber.tag("Epic").e(e, "Error fetching manifest for DLC ${dlc.title}")
                            }
                        }
                    }

                    val chunkCount = chunks.size
                    val fileCount = files.size

                    Timber.tag("Epic").d(
                        """
                |Download prepared:
                |  Base game download size: ${baseGameSize / 1_000_000_000.0} GB
                |  Base game installed size: ${baseGameInstalledSize / 1_000_000_000.0} GB
                |  DLCs: ${dlcManifestData.size}
                |  Total installed size (including DLCs): ${totalInstalledSize / 1_000_000_000.0} GB
                |  Total download size (including DLCs): ${totalDownloadSize / 1_000_000_000.0} GB
                |  Chunks: $chunkCount
                |  Files: $fileCount
                |  ChunkDir: $chunkDir
                        """.trimMargin(),
                    )

                    downloadInfo.setTotalExpectedBytes(totalDownloadSize)
                    downloadInfo.setDisplayTotalExpectedBytes(totalDownloadSize)
                    downloadInfo.updateStatusMessage("Downloading base game...")
                    syncCoordinatorProgress(gameId, downloadInfo)

                    // Download chunks in parallel
                    val chunkCacheDir = File(installPath, ".chunks")
                    chunkCacheDir.mkdirs()

                    Timber.tag("Epic").d(
                        """
                |=== NATIVE KOTLIN MANIFEST DATA ===
                |CDN URLs (${cdnUrls.size}):
                |${cdnUrls.joinToString("\n") { "  - ${it.baseUrl}" }}
                |Chunks: ${chunks.size}
                |Files: ${files.size}
                |==================================
                        """.trimMargin(),
                    )

                    downloadInfo.setProgress(0.0f)
                    downloadInfo.emitProgressChange()
                    syncCoordinatorProgress(gameId, downloadInfo)

                    val installDir = File(installPath)
                    installDir.mkdirs()

                    val installResult =
                        downloadChunksIntoFiles(
                            chunks = chunks,
                            files = files,
                            chunkCacheDir = chunkCacheDir,
                            chunkDir = chunkDir,
                            cdnUrls = cdnUrls,
                            installDir = installDir,
                            downloadInfo = downloadInfo,
                        ) { downloadedChunks, totalChunks ->
                            val progress = downloadedChunks.toFloat() / totalChunks
                            downloadInfo.setProgress(progress)
                            val statusMsg =
                                if (dlcManifestData.isNotEmpty()) {
                                    "Downloading base game ($downloadedChunks/$totalChunks chunks)"
                                } else {
                                    "Downloading chunks ($downloadedChunks/$totalChunks)"
                                }
                            downloadInfo.updateStatus(DownloadPhase.DOWNLOADING, statusMsg)
                            downloadInfo.emitProgressChange()
                            syncCoordinatorProgress(gameId, downloadInfo)

                            Timber.tag("Epic").d("Download progress: $downloadedChunks/$totalChunks chunks (${(progress * 100).toInt()}%)")
                        }

                    if (installResult.isFailure) {
                        return@withContext Result.failure(
                            installResult.exceptionOrNull() ?: Exception("Failed to install chunks"),
                        )
                    }
                    EpicInstalledManifestStore.saveManifest(installDir, game.appName, manifestData.manifestBytes)

                    chunkCacheDir.deleteRecursively()

                    // Log final directory structure
                    Timber.tag("Epic").i("Download completed successfully for ${game.title}")
                    logDirectoryStructure(installDir)

                    // Download DLCs using pre-fetched manifest data
                    if (dlcManifestData.isNotEmpty()) {
                        try {
                            Timber.tag("Epic").i("Downloading ${dlcManifestData.size} DLC(s) for ${game.title}")

                            dlcManifestData.forEachIndexed { index, (dlc, manifestData) ->
                                try {
                                    Timber.tag("Epic").i("Downloading DLC ${index + 1}/${dlcManifestData.size}: ${dlc.title}")
                                    downloadInfo.updateStatusMessage("Downloading DLC: ${dlc.title} (${index + 1}/${dlcManifestData.size})")

                                    // Download the DLC using already-fetched manifest
                                    val dlcResult =
                                        downloadGameWithManifest(
                                            context = context,
                                            game = dlc,
                                            manifestData = manifestData,
                                            installPath = installPath,
                                            downloadInfo = downloadInfo,
                                            selectedTags = selectedTags,
                                        )

                                    if (dlcResult.isFailure) {
                                        Timber.tag("Epic").w("Failed to download DLC ${dlc.title}: ${dlcResult.exceptionOrNull()?.message}")
                                        // Continue with other DLCs even if one fails
                                    } else {
                                        Timber.tag("Epic").i("Successfully downloaded DLC: ${dlc.title}")
                                    }
                                } catch (e: Exception) {
                                    Timber.tag("Epic").e(e, "Error downloading DLC ${dlc.title}")
                                    // Continue with other DLCs
                                }
                            }

                            downloadInfo.updateStatusMessage("DLC downloads complete")
                            Timber.tag("Epic").i("Finished downloading DLCs for ${game.title}")
                        } catch (e: Exception) {
                            Timber.tag("Epic").e(e, "Error downloading DLCs")
                            // Don't fail the base game download if DLC fails
                        }
                    }
                    MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                    InstallOwnership.claim(installPath, InstallStore.EPIC)

                    // Update database with install info after the completion marker exists.
                    try {
                        val updatedGame =
                            game.copy(
                                isInstalled = true,
                                installPath = installPath,
                                installSize = totalInstalledSize,
                                executable = manifest.meta?.launchExe ?: "",
                                version = manifest.meta?.buildVersion.orEmpty().ifBlank { game.version },
                            )
                        epicManager.updateGame(updatedGame)
                        Timber.tag("Epic").i("Updated database: game marked as installed with executable: ${updatedGame.executable}")
                    } catch (e: Exception) {
                        Timber.tag("Epic").e(e, "Failed to update database for game ${game.id}")
                        // Don't fail the entire download for DB issues
                    }

                    downloadInfo.updateStatusMessage("Complete")
                    // Ensure bytes-based progress shows 100% completion
                    downloadInfo.updateBytesDownloaded(downloadInfo.getTotalExpectedBytes() - downloadInfo.getBytesDownloaded())
                    downloadInfo.setProgress(1.0f)
                    downloadInfo.setActive(false)
                    downloadInfo.emitProgressChange()
                    syncCoordinatorProgress(gameId, downloadInfo)

                    com.winlator.cmod.app.PluviaApp.events.emitJava(
                        com.winlator.cmod.feature.stores.steam.events.AndroidEvent
                            .LibraryInstallStatusChanged(gameId),
                    )

                    Timber.tag("Epic").i("Download completed successfully for game $gameId")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Download failed: ${e.message}")
                    MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    downloadInfo.updateStatusMessage("Failed: ${e.message}")
                    downloadInfo.setProgress(-1.0f)
                    downloadInfo.setActive(false)
                    Result.failure(e)
                } finally {
                    // Always emit download stopped event
                    val gameId = game.id ?: 0
                    com.winlator.cmod.app.PluviaApp.events.emitJava(
                        com.winlator.cmod.feature.stores.steam.events.AndroidEvent
                            .DownloadStatusChanged(gameId, false),
                    )
                }
            }

        /**
         * Download game using an already-fetched manifest (used for DLCs)
         */
        private suspend fun downloadGameWithManifest(
            context: Context,
            game: EpicGame,
            manifestData: EpicManager.ManifestResult,
            installPath: String,
            downloadInfo: DownloadInfo,
            selectedTags: List<String> = emptyList(),
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    Timber.tag("Epic").i("Starting download for ${game.title} using pre-fetched manifest")

                    // Parse manifest
                    val cdnUrls = selectCdnUrls(manifestData.cdnUrls)
                    if (cdnUrls.isEmpty()) {
                        return@withContext Result.failure(Exception("No usable CDN URLs in manifest"))
                    }
                    val manifest = EpicManifest.readAll(manifestData.manifestBytes)

                    val files = ManifestUtils.getFilesForSelectedInstallTags(manifest, selectedTags)
                    val chunks = ManifestUtils.getRequiredChunksForFileList(manifest, files)
                    val chunkDir = manifest.getChunkDir()

                    if (chunks.isEmpty()) {
                        return@withContext Result.failure(Exception("No chunk data in manifest"))
                    }
                    if (files.isEmpty()) {
                        return@withContext Result.failure(Exception("No file manifest in manifest"))
                    }

                    // Download chunks
                    val chunkCacheDir = File(installPath, ".chunks")
                    chunkCacheDir.mkdirs()

                    val installDir = File(installPath)
                    installDir.mkdirs()

                    val installResult =
                        downloadChunksIntoFiles(
                            chunks = chunks,
                            files = files,
                            chunkCacheDir = chunkCacheDir,
                            chunkDir = chunkDir,
                            cdnUrls = cdnUrls,
                            installDir = installDir,
                            downloadInfo = downloadInfo,
                        )
                    if (installResult.isFailure) {
                        return@withContext Result.failure(
                            installResult.exceptionOrNull() ?: Exception("Failed to install chunks"),
                        )
                    }
                    EpicInstalledManifestStore.saveManifest(installDir, game.appName, manifestData.manifestBytes)

                    chunkCacheDir.deleteRecursively()

                    try {
                        epicManager.updateGame(
                            game.copy(
                                isInstalled = true,
                                installPath = installPath,
                                installSize = files.sumOf { it.fileSize }.takeIf { it > 0L } ?: game.installSize,
                                executable = manifest.meta?.launchExe.orEmpty().ifBlank { game.executable },
                                version = manifest.meta?.buildVersion.orEmpty().ifBlank { game.version },
                            ),
                        )
                        Timber.tag("Epic").i("Updated database: DLC ${game.title} marked as installed")
                    } catch (e: Exception) {
                        Timber.tag("Epic").e(e, "Failed to update database for DLC ${game.id}")
                    }

                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "DLC download failed: ${e.message}")
                    Result.failure(e)
                }
            }

        /**
         * Download the EOS overlay payload referenced by [manifestResult] and assemble it into
         * [installPath]. Used by [EpicOverlayManager] when provisioning the overlay into a Wine
         * prefix. Reuses the same chunk/file pipeline as [downloadGame], but without DLC and
         * without the install marker bookkeeping (the overlay is identified by the presence of
         * its marker DLL — see EpicOverlayManager.OVERLAY_MARKER_FILE).
         */
        suspend fun downloadOverlay(
            manifestResult: EpicManager.ManifestResult,
            installPath: String,
            onProgress: ((Int, Int) -> Unit)? = null,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val cdnUrls = selectCdnUrls(manifestResult.cdnUrls)
                    if (cdnUrls.isEmpty()) {
                        return@withContext Result.failure(Exception("No usable CDN URLs in manifest"))
                    }

                    val manifest = EpicManifest.readAll(manifestResult.manifestBytes)
                    val chunks =
                        manifest.chunkDataList?.elements
                            ?: return@withContext Result.failure(Exception("Manifest contains no chunk data"))
                    val files =
                        manifest.fileManifestList?.elements
                            ?: return@withContext Result.failure(Exception("Manifest contains no file list"))
                    val chunkDir = manifest.getChunkDir()

                    val installDir = File(installPath).also { it.mkdirs() }
                    val chunkCacheDir = File(installDir, ".chunks").also { it.mkdirs() }

                    // Overlay downloads are small — no UI progress events; the optional
                    // onProgress callback is fine-grained enough for the UI dependency layer.
                    val dummyDownloadInfo =
                        DownloadInfo(
                            jobCount = 1,
                            gameId = -1,
                            downloadingAppIds = java.util.concurrent.CopyOnWriteArrayList(),
                        )

                    var downloadedChunks = 0
                    val totalChunks = chunks.size

                    chunks.chunked(MAX_PARALLEL_DOWNLOADS).forEach { batch ->
                        val results =
                            batch
                                .map { chunk ->
                                    async {
                                        downloadChunkWithRetry(chunk, chunkCacheDir, chunkDir, cdnUrls, dummyDownloadInfo)
                                    }
                                }.awaitAll()

                        results.firstOrNull { it.isFailure }?.let { failure ->
                            chunkCacheDir.deleteRecursively()
                            return@withContext Result.failure(
                                failure.exceptionOrNull() ?: Exception("Chunk download failed"),
                            )
                        }

                        downloadedChunks += batch.size
                        onProgress?.invoke(downloadedChunks, totalChunks)
                    }

                    files.chunked(4).forEach { batch ->
                        val results =
                            batch
                                .map { fileManifest ->
                                    async { assembleFile(fileManifest, chunkCacheDir, installDir) }
                                }.awaitAll()

                        results.firstOrNull { it.isFailure }?.let { failure ->
                            chunkCacheDir.deleteRecursively()
                            return@withContext Result.failure(
                                failure.exceptionOrNull() ?: Exception("File assembly failed"),
                            )
                        }
                    }

                    chunkCacheDir.deleteRecursively()
                    Timber.tag("Epic").i("downloadOverlay completed: $installPath")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "downloadOverlay failed for $installPath")
                    Result.failure(e)
                }
            }

        private data class ChunkWriteTarget(
            val fileManifest: FileManifest,
            val chunkPart: ChunkPart,
        )

        private data class DownloadedChunk(
            val chunk: ChunkInfo,
            val chunkFile: File,
        )

        private fun selectCdnUrls(cdnUrls: List<EpicManager.CdnUrl>): List<EpicManager.CdnUrl> = cdnUrls

        // Spread chunks across CDNs by GUID hash; full list still iterated on failure.
        private fun orderedCdnUrlsForChunk(
            chunk: ChunkInfo,
            cdnUrls: List<EpicManager.CdnUrl>,
        ): List<EpicManager.CdnUrl> {
            if (cdnUrls.size <= 1) return cdnUrls
            val startIndex = ((chunk.guidStr.hashCode().toLong() and 0x7FFFFFFFL) % cdnUrls.size).toInt()
            if (startIndex == 0) return cdnUrls
            return cdnUrls.subList(startIndex, cdnUrls.size) + cdnUrls.subList(0, startIndex)
        }

        private fun buildChunkDownloadUrl(
            cdnUrl: EpicManager.CdnUrl,
            chunkPath: String,
        ): String = "${cdnUrl.baseUrl}${cdnUrl.cloudDir}/$chunkPath${cdnUrl.authQueryParams}"

        private fun loadCompletedChunkGuids(stateFile: File): MutableSet<String> {
            if (!stateFile.exists()) return mutableSetOf()
            return runCatching {
                stateFile
                    .readLines()
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toMutableSet()
            }.getOrElse { error ->
                Timber.tag("Epic").w(error, "Failed to read Epic resume state; restarting chunk application")
                mutableSetOf()
            }
        }

        private fun appendCompletedChunkGuid(
            stateFile: File,
            guid: String,
        ) {
            stateFile.parentFile?.mkdirs()
            stateFile.appendText("$guid\n")
        }

        private data class ExistingFileInspection(
            val validCompleteFiles: Set<String>,
            val invalidCompleteFiles: Set<String>,
        )

        private fun inspectExistingFiles(
            files: List<FileManifest>,
            installDir: File,
            downloadInfo: DownloadInfo,
        ): ExistingFileInspection {
            val validCompleteFiles = mutableSetOf<String>()
            val invalidCompleteFiles = mutableSetOf<String>()
            val existingCandidates =
                files.filter { fileManifest ->
                    File(installDir, fileManifest.filename).exists()
                }

            if (existingCandidates.isNotEmpty()) {
                downloadInfo.updateStatus(DownloadPhase.VERIFYING, "Verifying existing files...")
            }

            existingCandidates.forEachIndexed { index, fileManifest ->
                if (index % 25 == 0) {
                    downloadInfo.updateStatus(
                        DownloadPhase.VERIFYING,
                        "Verifying existing files (${index + 1}/${existingCandidates.size})",
                    )
                }

                val outputFile = File(installDir, fileManifest.filename)
                if (outputFile.length() != fileManifest.fileSize) return@forEachIndexed

                val valid =
                    if (hasExpectedHash(fileManifest.hash)) {
                        verifyFileHash(outputFile, fileManifest.hash)
                    } else {
                        true
                    }

                if (valid) {
                    validCompleteFiles.add(fileManifest.filename)
                } else {
                    invalidCompleteFiles.add(fileManifest.filename)
                }
            }

            return ExistingFileInspection(
                validCompleteFiles = validCompleteFiles,
                invalidCompleteFiles = invalidCompleteFiles,
            )
        }

        private fun hasExpectedHash(hash: ByteArray): Boolean = hash.any { it != 0.toByte() }

        private fun isChunkPartRangePresent(
            installDir: File,
            target: ChunkWriteTarget,
        ): Boolean {
            val outputFile = File(installDir, target.fileManifest.filename)
            if (!outputFile.exists()) return false

            val partEnd = target.chunkPart.fileOffset + target.chunkPart.size.toLong()
            return outputFile.length() >= partEnd
        }

        /**
         * Download chunks with a bounded worker pool, apply each verified chunk to the final
         * files, then record it in a resume ledger. A pause, failed chunk, or process death only
         * replays the current in-flight chunks instead of starting the whole game over.
         */
        internal suspend fun repairManifestFiles(
            chunks: List<ChunkInfo>,
            files: List<FileManifest>,
            chunkCacheDir: File,
            chunkDir: String,
            cdnUrls: List<EpicManager.CdnUrl>,
            installDir: File,
            downloadInfo: DownloadInfo,
            onBatchInstalled: (Int, Int) -> Unit = { _, _ -> },
        ): Result<Unit> =
            downloadChunksIntoFiles(
                chunks = chunks,
                files = files,
                chunkCacheDir = chunkCacheDir,
                chunkDir = chunkDir,
                cdnUrls = cdnUrls,
                installDir = installDir,
                downloadInfo = downloadInfo,
                allowExistingFileResume = false,
                onBatchInstalled = onBatchInstalled,
            )

        internal suspend fun downloadManifestFiles(
            chunks: List<ChunkInfo>,
            files: List<FileManifest>,
            chunkCacheDir: File,
            chunkDir: String,
            cdnUrls: List<EpicManager.CdnUrl>,
            installDir: File,
            downloadInfo: DownloadInfo,
            onBatchInstalled: (Int, Int) -> Unit = { _, _ -> },
        ): Result<Unit> =
            downloadChunksIntoFiles(
                chunks = chunks,
                files = files,
                chunkCacheDir = chunkCacheDir,
                chunkDir = chunkDir,
                cdnUrls = cdnUrls,
                installDir = installDir,
                downloadInfo = downloadInfo,
                allowExistingFileResume = true,
                onBatchInstalled = onBatchInstalled,
            )

        private suspend fun downloadChunksIntoFiles(
            chunks: List<ChunkInfo>,
            files: List<FileManifest>,
            chunkCacheDir: File,
            chunkDir: String,
            cdnUrls: List<EpicManager.CdnUrl>,
            installDir: File,
            downloadInfo: DownloadInfo,
            allowExistingFileResume: Boolean = true,
            onBatchInstalled: (Int, Int) -> Unit = { _, _ -> },
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val chunkTargets = buildChunkTargets(files)
                    val existingFileInspection =
                        if (allowExistingFileResume) {
                            inspectExistingFiles(
                                files = files,
                                installDir = installDir,
                                downloadInfo = downloadInfo,
                            )
                        } else {
                            ExistingFileInspection(emptySet(), emptySet())
                        }
                    val resumeStateFile = File(chunkCacheDir, RESUME_STATE_FILE)
                    val chunkIds = chunks.mapTo(mutableSetOf()) { it.guidStr }
                    val ledgerChunks =
                        if (allowExistingFileResume) {
                            loadCompletedChunkGuids(resumeStateFile)
                                .filterTo(mutableSetOf()) { it in chunkIds }
                                .filterTo(mutableSetOf()) { guid ->
                                    val targets = chunkTargets[guid].orEmpty()
                                    targets.isNotEmpty() &&
                                        targets.none {
                                            it.fileManifest.filename in existingFileInspection.invalidCompleteFiles
                                        } &&
                                        targets.all { target ->
                                            target.fileManifest.filename in existingFileInspection.validCompleteFiles ||
                                                isChunkPartRangePresent(installDir, target)
                                        }
                                }
                        } else {
                            mutableSetOf()
                        }
                    val validExistingFileChunks =
                        if (allowExistingFileResume) {
                            chunks
                                .asSequence()
                                .filter { chunk ->
                                    chunkTargets[chunk.guidStr]
                                        .orEmpty()
                                        .all { it.fileManifest.filename in existingFileInspection.validCompleteFiles }
                                }.mapTo(mutableSetOf()) { it.guidStr }
                        } else {
                            mutableSetOf()
                        }
                    val completedChunks = (ledgerChunks + validExistingFileChunks).toMutableSet()

                    prepareOutputFiles(
                        files = files,
                        installDir = installDir,
                        resumeChunkCount = completedChunks.size,
                    )

                    val completedBytes =
                        chunks
                            .asSequence()
                            .filter { it.guidStr in completedChunks }
                            .sumOf { it.fileSize }
                    // maxOf, not +: bytes may already cover completedBytes from a Resume pre-seed
                    // or prior DLC accumulation.
                    downloadInfo.initializeBytesDownloaded(
                        maxOf(downloadInfo.getBytesDownloaded(), completedBytes),
                    )

                    var installedChunks = completedChunks.size
                    val totalChunks = chunks.size
                    if (installedChunks > 0) {
                        Timber.tag("Epic").i("Resuming Epic download with $installedChunks/$totalChunks chunks already applied")
                        onBatchInstalled(installedChunks, totalChunks)
                        syncCoordinatorProgress(downloadInfo)
                    }

                    val pendingChunks = chunks.filter { it.guidStr !in completedChunks }
                    val nextIndex = AtomicInteger(0)
                    val workers = minOf(MAX_PARALLEL_DOWNLOADS, pendingChunks.size).coerceAtLeast(1)
                    val prefetchCapacity = (workers * 2).coerceAtLeast(1)

                    coroutineScope {
                        val downloadedChunks = Channel<DownloadedChunk>(capacity = prefetchCapacity)
                        val downloaders =
                            (0 until workers)
                                .map {
                                    async {
                                        while (true) {
                                            if (!downloadInfo.isActive() || downloadInfo.isCancelling) {
                                                throw kotlinx.coroutines.CancellationException("Download cancelled")
                                            }

                                            val index = nextIndex.getAndIncrement()
                                            if (index >= pendingChunks.size) break

                                            val chunk = pendingChunks[index]
                                            val downloadResult =
                                                downloadChunkWithRetry(
                                                    chunk,
                                                    chunkCacheDir,
                                                    chunkDir,
                                                    cdnUrls,
                                                    downloadInfo,
                                                )
                                            if (downloadResult.isFailure) {
                                                throw downloadResult.exceptionOrNull() ?: Exception("Failed to download chunk ${chunk.guidStr}")
                                            }

                                            downloadedChunks.send(
                                                DownloadedChunk(
                                                    chunk = chunk,
                                                    chunkFile = downloadResult.getOrThrow(),
                                                ),
                                            )
                                        }
                                    }
                                }

                        val applier =
                            async {
                                for (downloaded in downloadedChunks) {
                                    if (!downloadInfo.isActive() || downloadInfo.isCancelling) {
                                        throw kotlinx.coroutines.CancellationException("Download cancelled")
                                    }

                                    val chunk = downloaded.chunk
                                    val chunkFile = downloaded.chunkFile

                                    if (chunk.guidStr !in completedChunks) {
                                        val applyResult =
                                            applyChunkToFiles(
                                                chunk = chunk,
                                                chunkFile = chunkFile,
                                                targets = chunkTargets[chunk.guidStr].orEmpty(),
                                                installDir = installDir,
                                            )
                                        if (applyResult.isFailure) {
                                            throw applyResult.exceptionOrNull()
                                                ?: Exception("Failed to apply chunk ${chunk.guidStr}")
                                        }

                                        appendCompletedChunkGuid(resumeStateFile, chunk.guidStr)
                                        completedChunks.add(chunk.guidStr)
                                    }

                                    if (!chunkFile.delete()) {
                                        Timber.tag("Epic").w("Failed to delete temporary chunk ${chunkFile.absolutePath}")
                                    }

                                    installedChunks += 1
                                    onBatchInstalled(installedChunks, totalChunks)
                                    syncCoordinatorProgress(downloadInfo)
                                }
                            }

                        try {
                            downloaders.awaitAll()
                        } finally {
                            downloadedChunks.close()
                        }
                        applier.await()
                    }

                    verifyInstalledFiles(files, installDir, downloadInfo)
                    syncCoordinatorProgress(downloadInfo)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Failed to download/install chunk batch")
                    Result.failure<Unit>(e)
                }
            }

        private fun buildChunkTargets(files: List<FileManifest>): Map<String, List<ChunkWriteTarget>> {
            val targets = mutableMapOf<String, MutableList<ChunkWriteTarget>>()
            files.forEach { fileManifest ->
                fileManifest.chunkParts.forEach { chunkPart ->
                    targets
                        .getOrPut(chunkPart.guidStr) { mutableListOf() }
                        .add(ChunkWriteTarget(fileManifest, chunkPart))
                }
            }
            return targets
        }

        private fun prepareOutputFiles(
            files: List<FileManifest>,
            installDir: File,
            resumeChunkCount: Int,
        ) {
            val canResume = resumeChunkCount > 0
            files.forEach { fileManifest ->
                val outputFile = File(installDir, fileManifest.filename)
                outputFile.parentFile?.mkdirs()
                if (!canResume && outputFile.exists() && !outputFile.delete()) {
                    throw Exception("Failed to replace existing file: ${outputFile.absolutePath}")
                }
                if (fileManifest.fileSize == 0L) {
                    outputFile.createNewFile()
                }
            }
        }

        private fun applyChunkToFiles(
            chunk: ChunkInfo,
            chunkFile: File,
            targets: List<ChunkWriteTarget>,
            installDir: File,
        ): Result<Unit> {
            return try {
                if (targets.isEmpty()) {
                    Timber.tag("Epic").w("Downloaded chunk ${chunk.guidStr} is not referenced by selected files")
                    return Result.success(Unit)
                }

                val chunkLength = chunkFile.length()
                val buffer = ByteArray(65536)

                targets.forEach { target ->
                    val part = target.chunkPart
                    val partOffset = part.offset.toLong()
                    val partSize = part.size.toLong()
                    val partEnd = partOffset + partSize
                    if (partOffset < 0L || partSize < 0L || partEnd > chunkLength) {
                        throw Exception(
                            "Chunk part out of bounds for ${target.fileManifest.filename}: " +
                                "offset=$partOffset size=$partSize chunkSize=$chunkLength",
                        )
                    }

                    val outputFile = File(installDir, target.fileManifest.filename)
                    outputFile.parentFile?.mkdirs()
                    RandomAccessFile(chunkFile, "r").use { input ->
                        RandomAccessFile(outputFile, "rw").use { output ->
                            input.seek(partOffset)
                            output.seek(part.fileOffset)

                            var remaining = partSize
                            while (remaining > 0L) {
                                val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                                val bytesRead = input.read(buffer, 0, toRead)
                                if (bytesRead < 0) {
                                    throw Exception("Unexpected EOF reading chunk ${chunk.guidStr}")
                                }
                                output.write(buffer, 0, bytesRead)
                                remaining -= bytesRead.toLong()
                            }
                        }
                    }
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Failed to apply chunk ${chunk.guidStr}")
                Result.failure(e)
            }
        }

        private fun verifyInstalledFiles(
            files: List<FileManifest>,
            installDir: File,
            downloadInfo: DownloadInfo,
        ): Result<Unit> {
            downloadInfo.updateStatus(DownloadPhase.VERIFYING, "Verifying installed files...")
            files.forEachIndexed { index, fileManifest ->
                if (index % 25 == 0) {
                    downloadInfo.updateStatus(
                        DownloadPhase.VERIFYING,
                        "Verifying installed files (${index + 1}/${files.size})",
                    )
                }
                val outputFile = File(installDir, fileManifest.filename)
                if (!outputFile.exists()) {
                    return Result.failure(Exception("Installed file missing: ${fileManifest.filename}"))
                }
                if (outputFile.length() != fileManifest.fileSize) {
                    return Result.failure(
                        Exception(
                            "Installed file size mismatch for ${fileManifest.filename}: " +
                                "expected ${fileManifest.fileSize}, got ${outputFile.length()}",
                        ),
                    )
                }
                if (hasExpectedHash(fileManifest.hash) && !verifyFileHash(outputFile, fileManifest.hash)) {
                    return Result.failure(Exception("Installed file hash mismatch: ${fileManifest.filename}"))
                }
            }
            return Result.success(Unit)
        }

        private fun syncCoordinatorProgress(downloadInfo: DownloadInfo) {
            if (downloadInfo.gameId <= 0) return
            syncCoordinatorProgress(downloadInfo.gameId, downloadInfo)
        }

        private fun syncCoordinatorProgress(
            gameId: Int,
            downloadInfo: DownloadInfo,
        ) {
            DownloadCoordinator.updateProgress(
                DownloadRecord.STORE_EPIC,
                gameId.toString(),
                downloadInfo.getBytesDownloaded(),
                downloadInfo.getTotalExpectedBytes(),
            )
        }

        /**
         * Download a single chunk with retry logic
         */
        private suspend fun downloadChunkWithRetry(
            chunk: ChunkInfo,
            chunkCacheDir: File,
            chunkDir: String,
            cdnUrls: List<EpicManager.CdnUrl>,
            downloadInfo: DownloadInfo,
        ): Result<File> =
            withContext(Dispatchers.IO) {
                var lastException: Exception? = null

                repeat(MAX_CHUNK_RETRIES) { attempt ->
                    if (!downloadInfo.isActive() || downloadInfo.isCancelling) {
                        return@withContext Result.failure(kotlinx.coroutines.CancellationException("Download cancelled"))
                    }

                    val result = downloadChunk(chunk, chunkCacheDir, chunkDir, cdnUrls, downloadInfo)

                    if (result.isSuccess) {
                        if (attempt > 0) {
                            Timber.tag("Epic").i("Chunk ${chunk.guidStr} downloaded successfully after ${attempt + 1} attempts")
                        }
                        return@withContext result
                    }

                    lastException = result.exceptionOrNull() as? Exception
                    if (lastException is kotlinx.coroutines.CancellationException) {
                        return@withContext Result.failure(lastException)
                    }

                    if (attempt < MAX_CHUNK_RETRIES - 1) {
                        val delay = RETRY_DELAY_MS * (1 shl attempt) // Exponential backoff: 1s, 2s, 4s
                        Timber
                            .tag(
                                "Epic",
                            ).w(
                                "Chunk ${chunk.guidStr} download failed (attempt ${attempt + 1}/$MAX_CHUNK_RETRIES): ${lastException?.message}. Retrying in ${delay}ms...",
                            )
                        kotlinx.coroutines.delay(delay)
                    }
                }

                Timber.tag("Epic").e(lastException, "Failed to download chunk ${chunk.guidStr} after $MAX_CHUNK_RETRIES attempts")
                Result.failure(lastException ?: Exception("Failed to download chunk ${chunk.guidStr}"))
            }

        /**
         * Download a single chunk from Epic CDN with decompression
         */
        private suspend fun downloadChunk(
            chunk: ChunkInfo,
            chunkCacheDir: File,
            chunkDir: String,
            cdnUrls: List<EpicManager.CdnUrl>,
            downloadInfo: DownloadInfo,
        ): Result<File> =
            withContext(Dispatchers.IO) {
                try {
                    val chunkFile = File(chunkCacheDir, "${chunk.guidStr}.chunk")
                    val decompressedFile = File(chunkCacheDir, chunk.guidStr)

                    // Skip if already downloaded and decompressed
                    if (decompressedFile.exists() && decompressedFile.length() == chunk.windowSize.toLong()) {
                        // Quick verification - only verify if size matches
                        if (verifyChunkHashFile(decompressedFile, chunk.shaHash)) {
                            Timber.tag("Epic").d("Chunk ${chunk.guidStr} already exists and verified, skipping")
                            downloadInfo.updateBytesDownloaded(chunk.fileSize)
                            return@withContext Result.success(decompressedFile)
                        } else {
                            Timber.tag("Epic").w("Chunk ${chunk.guidStr} exists but failed verification, re-downloading")
                            decompressedFile.delete()
                        }
                    }

                    val chunkPath = chunk.getPath(chunkDir)

                    // Try each CDN base URL until one succeeds
                    var lastException: Exception? = null
                    val orderedCdnUrls = orderedCdnUrlsForChunk(chunk, cdnUrls)
                    for ((cdnIndex, cdnUrl) in orderedCdnUrls.withIndex()) {
                        try {
                            if (!downloadInfo.isActive() || downloadInfo.isCancelling) {
                                throw kotlinx.coroutines.CancellationException("Download cancelled")
                            }

                            // Build full URL with the CDN auth params returned by Epic's launcher API.
                            val url = buildChunkDownloadUrl(cdnUrl, chunkPath)

                            val request =
                                Request
                                    .Builder()
                                    .url(url)
                                    .header("User-Agent", "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit")
                                    .build()

                            // Use .use {} to ensure response is always closed, even on exception
                            okHttpClient.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) {
                                    lastException = Exception("HTTP ${response.code} downloading chunk from ${cdnUrl.baseUrl}")
                                    return@use // Exit use block, response will be closed automatically
                                }

                                // Download and decompress Epic chunk file using streaming to avoid OOM exceptions
                                val responseBody = response.body!!
                                val tempChunkFile = File(chunkCacheDir, "${chunk.guidStr}.tmp")
                                val tempDecompressedFile = File(chunkCacheDir, "${chunk.guidStr}.decompressed.tmp")

                                try {
                                    tempChunkFile.delete()
                                    tempDecompressedFile.delete()

                                    // Stream download to temp file
                                    responseBody.byteStream().use { input ->
                                        tempChunkFile.outputStream().use { output ->
                                            val buffer = ByteArray(65536)
                                            var bytesRead: Int
                                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                                // Cooperative cancellation: bail out of the byte
                                                // read loop immediately on pause/cancel instead of
                                                // waiting until the chunk finishes.
                                                if (!downloadInfo.isActive() || downloadInfo.isCancelling) {
                                                    throw kotlinx.coroutines.CancellationException(
                                                        "Download cancelled mid-chunk",
                                                    )
                                                }
                                                output.write(buffer, 0, bytesRead)
                                                downloadInfo.updateBytesDownloaded(bytesRead.toLong())
                                            }
                                        }
                                    }

                                    // Decompress from temp file directly to output file with streaming hash calculation
                                    // This avoids allocating 1.5GB in memory
                                    decompressStreamingChunkToFile(
                                        tempChunkFile,
                                        tempDecompressedFile,
                                        chunk.windowSize.toLong(),
                                        chunk.shaHash,
                                    )
                                    decompressedFile.delete()
                                    if (!tempDecompressedFile.renameTo(decompressedFile)) {
                                        throw Exception("Failed to finalize chunk ${chunk.guidStr}")
                                    }
                                } finally {
                                    tempChunkFile.delete()
                                    tempDecompressedFile.delete()
                                }

                                return@withContext Result.success(decompressedFile)
                            }

                            // If we get here, response was unsuccessful, try next CDN
                            if (lastException != null) {
                                continue
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) {
                                throw e
                            }
                            if (cdnIndex < orderedCdnUrls.size - 1) {
                                Timber.tag("Epic").w(e, "Failed to download from ${cdnUrl.baseUrl}, trying next...")
                            }
                            lastException = e
                        }
                    }

                    // All URLs failed
                    return@withContext Result.failure(lastException ?: Exception("All CDN URLs failed for chunk ${chunk.guidStr}"))
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Failed to download chunk ${chunk.guidStr}")
                    Result.failure(e)
                }
            }

        /**
         * Read and decompress an Epic Chunk file
         * Epic chunks have their own format with header + optional compression
         *
         * Format (from legendary/models/chunk.py):
         * - Magic: 0xB1FE3AA2 (4 bytes)
         * - Header version: 3 (4 bytes)
         * - Header size: 66 (4 bytes)
         * - Compressed size (4 bytes)
         * - GUID (16 bytes)
         * - Hash (8 bytes)
         * - Stored as flags (1 byte) - bit 0 = compressed
         * - SHA hash (20 bytes)
         * - Hash type (1 byte)
         * - Uncompressed size (4 bytes)
         * - Data (compressed_size bytes)
         */
        private fun readEpicChunk(chunkBytes: ByteArray): ByteArray {
            val buffer = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Read header
            val magic = buffer.int
            if (magic != 0xB1FE3AA2.toInt()) {
                throw Exception("Invalid chunk magic: 0x${magic.toString(16)}")
            }

            val headerVersion = buffer.int
            val headerSize = buffer.int
            val compressedSize = buffer.int

            // Skip GUID (16 bytes), hash (8 bytes)
            buffer.position(buffer.position() + 24)

            val storedAs = buffer.get().toInt() and 0xFF
            val isCompressed = (storedAs and 0x1) == 0x1

            // Skip SHA hash (20 bytes), hash type (1 byte)
            buffer.position(buffer.position() + 21)

            val uncompressedSize = buffer.int

            // Read chunk data starting from header end
            val dataStart = headerSize
            // ! Note: This may require adjustments if we see chunks bigger than 2GB - Unlikely but worth Observing
            val dataBytes = chunkBytes.copyOfRange(dataStart, dataStart + compressedSize)

            return if (isCompressed) {
                // Decompress using zlib
                val inflater = Inflater()
                try {
                    inflater.setInput(dataBytes)
                    val result = ByteArray(uncompressedSize)
                    val resultLength = inflater.inflate(result)
                    if (resultLength != uncompressedSize) {
                        throw IllegalStateException("Decompressed chunk size mismatch: expected $uncompressedSize, got $resultLength")
                    }
                    result
                } finally {
                    inflater.end()
                }
            } else {
                // Already uncompressed
                dataBytes
            }
        }

        /**
         * Decompress an Epic chunk file directly to output file with streaming hash verification
         * This avoids allocating huge ByteArrays (1.5GB) in memory
         */
        private fun decompressStreamingChunkToFile(
            chunkFile: File,
            outputFile: File,
            expectedSize: Long,
            expectedHash: ByteArray,
        ) {
            val digest = MessageDigest.getInstance("SHA-1")
            var totalBytesWritten = 0L

            chunkFile.inputStream().buffered().use { input ->
                // Read the entire header - determine size dynamically
                val headerStart = ByteArray(12)
                if (input.read(headerStart) != 12) {
                    throw Exception("Failed to read chunk header start")
                }

                val startBuffer = ByteBuffer.wrap(headerStart).order(ByteOrder.LITTLE_ENDIAN)
                val magic = startBuffer.int
                if (magic != 0xB1FE3AA2.toInt()) {
                    throw Exception("Invalid chunk magic: 0x${magic.toString(16)}")
                }

                val headerVersion = startBuffer.int
                val headerSize = startBuffer.int

                // Epic chunks can have different header sizes (62 or 66 bytes)
                // Minimum viable header is 62 bytes
                if (headerSize < 62 || headerSize > 66) {
                    throw Exception("Invalid header size: $headerSize (expected 62-66 bytes)")
                }

                // Read the remaining header bytes
                val remainingSize = headerSize - 12
                val remainingBytes = ByteArray(remainingSize)
                if (input.read(remainingBytes) != remainingSize) {
                    throw Exception("Failed to read remaining header: expected $remainingSize bytes")
                }

                // Parse the header fields from the remaining bytes sequentially
                // This matches the format in legendary/models/chunk.py
                val buffer = ByteBuffer.wrap(remainingBytes).order(ByteOrder.LITTLE_ENDIAN)

                // Chunk header format (after magic/version/headerSize):
                // compressedSize: 4 bytes (file offset 12-15)
                // GUID: 16 bytes (file offset 16-31)
                // hash: 8 bytes (file offset 32-39)
                // storedAs: 1 byte (file offset 40)
                // SHA hash: 20 bytes (file offset 41-60)
                // For 66-byte headers:
                //   hash type: 1 byte (file offset 61)
                //   uncompressedSize: 4 bytes (file offset 62-65)
                // For 62-byte headers (older format):
                //   uncompressedSize: 4 bytes (file offset 58-61) - replaces hash type + padding

                // Ensure we have minimum required fields (up to SHA hash)
                if (buffer.remaining() < 50) {
                    throw Exception("Buffer underflow: only ${buffer.remaining()} bytes available, need at least 50")
                }

                val compressedSize = buffer.int // Read compressed size
                buffer.position(buffer.position() + 16) // Skip GUID (16 bytes)
                buffer.position(buffer.position() + 8) // Skip hash (8 bytes)
                val storedAs = buffer.get().toInt() and 0xFF // Read storedAs flag
                val isCompressed = (storedAs and 0x1) == 0x1

                // Version Check to understand header spacing
                // Header version 2: includes sha_hash (20 bytes) + hash_type (1 byte) = 62 total bytes
                // Header version 3: adds uncompressed_size (4 bytes) = 66 total bytes
                if (headerVersion >= 2) {
                    buffer.position(buffer.position() + 20) // Skip SHA hash (20 bytes)
                    if (buffer.remaining() >= 1) {
                        buffer.position(buffer.position() + 1) // Skip hash type (1 byte)
                    }
                }

                val uncompressedSize =
                    if (headerVersion >= 3 && buffer.remaining() >= 4) {
                        // Version 3+: uncompressedSize field is present (4 bytes)
                        buffer.int
                    } else {
                        // Version 2 or no uncompressedSize field: use expectedSize parameter
                        Timber
                            .tag(
                                "Epic",
                            ).d("Header version $headerVersion doesn't include uncompressedSize field, using expectedSize=$expectedSize")
                        expectedSize.toInt()
                    }

                Timber
                    .tag(
                        "Epic",
                    ).d(
                        "Chunk header: magic=0x${magic.toString(
                            16,
                        )}, headerVersion=$headerVersion, headerSize=$headerSize, compressedSize=$compressedSize, uncompressedSize=$uncompressedSize, storedAs=0x${storedAs.toString(
                            16,
                        )}, isCompressed=$isCompressed, expectedSize=$expectedSize",
                    )

                outputFile.outputStream().buffered().use { output ->
                    if (isCompressed) {
                        // Streaming decompression
                        val inflater = Inflater()
                        try {
                            val inputBuffer = ByteArray(65536) // 64KB compressed read buffer
                            val outputBuffer = ByteArray(65536) // 64KB decompressed write buffer
                            var endOfStream = false
                            var firstRead = true

                            while (totalBytesWritten < uncompressedSize && !endOfStream) {
                                // Feed more input if needed
                                if (inflater.needsInput() && !endOfStream) {
                                    val bytesRead = input.read(inputBuffer)
                                    if (bytesRead == -1) {
                                        endOfStream = true
                                        Timber
                                            .tag(
                                                "Epic",
                                            ).d("Unexpected end of stream: read=$totalBytesWritten, expected=$uncompressedSize")
                                    } else {
                                        if (firstRead) {
                                            Log.d(
                                                "Epic",
                                                "First compressed data bytes: ${inputBuffer.take(
                                                    16,
                                                ).joinToString(" ") { "%02x".format(it) }}",
                                            )
                                            firstRead = false
                                        }
                                        inflater.setInput(inputBuffer, 0, bytesRead)
                                    }
                                }

                                // Try to decompress
                                try {
                                    val decompressed = inflater.inflate(outputBuffer)
                                    if (decompressed > 0) {
                                        output.write(outputBuffer, 0, decompressed)
                                        digest.update(outputBuffer, 0, decompressed)
                                        totalBytesWritten += decompressed
                                    } else if (inflater.finished() || endOfStream) {
                                        // No more data available
                                        break
                                    }
                                } catch (e: java.util.zip.DataFormatException) {
                                    Timber.tag("Epic").d("DataFormatException during inflate: ${e.message}")
                                    Timber.tag("Epic").d("  totalBytesWritten=$totalBytesWritten, expectedSize=$uncompressedSize")
                                    Timber.tag("Epic").d("  inflater: finished=${inflater.finished()}, needsInput=${inflater.needsInput()}")
                                    throw Exception("Failed to decompress chunk: ${e.message}", e)
                                }
                            }
                        } finally {
                            inflater.end()
                        }
                    } else {
                        // Already uncompressed - stream directly
                        val buffer = ByteArray(65536)
                        var remaining = compressedSize
                        while (remaining > 0) {
                            val toRead = minOf(remaining, buffer.size)
                            val bytesRead = input.read(buffer, 0, toRead)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            totalBytesWritten += bytesRead
                            remaining -= bytesRead
                        }
                    }
                }
            }

            // Verify size
            if (totalBytesWritten != expectedSize) {
                Timber
                    .tag(
                        "Epic",
                    ).d("Size mismatch: expected=$expectedSize, actual=$totalBytesWritten, diff=${expectedSize - totalBytesWritten}")
                outputFile.delete()
                throw Exception("Decompressed size mismatch: expected $expectedSize, got $totalBytesWritten")
            }

            // Verify hash
            val actualHash = digest.digest()
            if (!actualHash.contentEquals(expectedHash)) {
                val expectedHex = expectedHash.joinToString("") { "%02x".format(it) }
                val actualHex = actualHash.joinToString("") { "%02x".format(it) }
                outputFile.delete()
                throw Exception("Chunk hash verification failed: expected $expectedHex, got $actualHex")
            }
        }

        /**
         * Read and decompress an Epic Chunk file from disk using streaming to avoid OOM
         * This version reads from a file input stream and decompresses in chunks
         */
        private fun readEpicChunkFromFile(
            chunkFile: File,
            expectedSize: Long,
        ): ByteArray {
            chunkFile.inputStream().buffered().use { input ->
                // Read header (66 bytes)
                val headerBytes = ByteArray(66)
                val headerRead = input.read(headerBytes)
                if (headerRead != 66) {
                    throw Exception("Failed to read chunk header")
                }

                val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

                // Parse header
                val magic = buffer.int
                if (magic != 0xB1FE3AA2.toInt()) {
                    throw Exception("Invalid chunk magic: 0x${magic.toString(16)}")
                }

                val headerVersion = buffer.int
                val headerSize = buffer.int
                val compressedSize = buffer.int

                // Skip GUID (16 bytes), hash (8 bytes)
                buffer.position(buffer.position() + 24)

                val storedAs = buffer.get().toInt() and 0xFF
                val isCompressed = (storedAs and 0x1) == 0x1

                // Skip SHA hash (20 bytes), hash type (1 byte)
                buffer.position(buffer.position() + 21)

                val uncompressedSize = buffer.int

                // Skip to data start if header is larger than 66 bytes
                if (headerSize > 66) {
                    input.skip((headerSize - 66).toLong())
                }

                return if (isCompressed) {
                    // Decompress using streaming to avoid loading entire compressed data into memory
                    val inflater = Inflater()
                    try {
                        val result = ByteArray(uncompressedSize)
                        var resultOffset = 0
                        val inputBuffer = ByteArray(65536) // 64KB buffer for reading compressed data

                        while (resultOffset < uncompressedSize) {
                            if (inflater.needsInput()) {
                                val bytesRead = input.read(inputBuffer)
                                if (bytesRead == -1) break
                                inflater.setInput(inputBuffer, 0, bytesRead)
                            }

                            val decompressed = inflater.inflate(result, resultOffset, uncompressedSize - resultOffset)
                            resultOffset += decompressed

                            if (inflater.finished()) break
                        }

                        if (resultOffset != uncompressedSize) {
                            throw IllegalStateException("Decompressed chunk size mismatch: expected $uncompressedSize, got $resultOffset")
                        }
                        result
                    } finally {
                        inflater.end()
                    }
                } else {
                    // Already uncompressed - read directly
                    val result = ByteArray(compressedSize)
                    var totalRead = 0
                    while (totalRead < compressedSize) {
                        val bytesRead = input.read(result, totalRead, compressedSize - totalRead)
                        if (bytesRead == -1) break
                        totalRead += bytesRead
                    }
                    result
                }
            }
        }

        private fun verifyFileHash(
            file: File,
            expectedHash: ByteArray,
        ): Boolean =
            try {
                val digest = MessageDigest.getInstance("SHA-1")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                val actualHash = digest.digest()
                val matches = actualHash.contentEquals(expectedHash)
                if (!matches) {
                    val expectedHex = expectedHash.joinToString("") { "%02x".format(it) }
                    val actualHex = actualHash.joinToString("") { "%02x".format(it) }
                    Timber.tag("Epic").e("Hash mismatch for ${file.name}: expected $expectedHex, got $actualHex")
                }
                matches
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Hash verification failed for ${file.absolutePath}")
                false
            }

        private fun verifyChunkHashFile(
            file: File,
            expectedHash: ByteArray,
        ): Boolean = verifyFileHash(file, expectedHash)

        /**
         * Verify chunk SHA-1 hash from byte array
         */
        private fun verifyChunkHashBytes(
            data: ByteArray,
            expectedHash: ByteArray,
        ): Boolean =
            try {
                val digest = MessageDigest.getInstance("SHA-1")
                digest.update(data)
                val actualHash = digest.digest()
                val matches = actualHash.contentEquals(expectedHash)

                if (!matches) {
                    val expectedHex = expectedHash.joinToString("") { "%02x".format(it) }
                    val actualHex = actualHash.joinToString("") { "%02x".format(it) }
                    Timber.tag("Epic").e("Hash mismatch: expected $expectedHex, got $actualHex")
                }

                matches
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Hash verification failed")
                false
            }

        /**
         * Assemble a file from its chunks
         */
        private suspend fun assembleFile(
            fileManifest: com.winlator.cmod.feature.stores.epic.service.manifest.FileManifest,
            chunkCacheDir: File,
            installDir: File,
        ): Result<File> =
            withContext(Dispatchers.IO) {
                try {
                    val outputFile = File(installDir, fileManifest.filename)
                    outputFile.parentFile?.mkdirs()

                    outputFile.outputStream().use { output ->
                        for (chunkPart in fileManifest.chunkParts) {
                            val chunkFile = File(chunkCacheDir, chunkPart.guidStr)

                            if (!chunkFile.exists()) {
                                return@withContext Result.failure(Exception("Chunk file missing: ${chunkPart.guidStr}"))
                            }

                            chunkFile.inputStream().use { input ->
                                input.skip(chunkPart.offset.toLong())

                                val buffer = ByteArray(65536) // Increased to 64KB for better I/O performance
                                var remaining = chunkPart.size.toLong()

                                while (remaining > 0) {
                                    val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                                    val bytesRead = input.read(buffer, 0, toRead)

                                    if (bytesRead == -1) break

                                    output.write(buffer, 0, bytesRead)
                                    remaining -= bytesRead
                                }
                            }
                        }
                    }

                    Result.success(outputFile)
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Failed to assemble file ${fileManifest.filename}")
                    Result.failure(e)
                }
            }

        /**
         * Log the directory structure of the installed game
         */
        private fun logDirectoryStructure(
            dir: File,
            prefix: String = "",
            isRoot: Boolean = true,
        ) {
            if (!dir.exists()) {
                Timber.tag("Epic").w("Directory does not exist: ${dir.absolutePath}")
                return
            }

            if (isRoot) {
                Timber.tag("Epic").i("=== Installation Directory Structure ===")
                Timber.tag("Epic").i("Root: ${dir.absolutePath}")
            }

            val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()

            files.forEachIndexed { index, file ->
                val isLast = index == files.lastIndex
                val connector = if (isLast) "└── " else "├── "
                val fileInfo =
                    if (file.isDirectory) {
                        "${file.name}/"
                    } else {
                        val size = formatFileSize(file.length())
                        "${file.name} ($size)"
                    }

                Timber.tag("Epic").i("$prefix$connector$fileInfo")

                // Recursively log subdirectories
                if (file.isDirectory) {
                    val newPrefix = prefix + if (isLast) "    " else "│   "
                    logDirectoryStructure(file, newPrefix, isRoot = false)
                }
            }

            if (isRoot) {
                val totalSize = calculateTotalSize(dir)
                val fileCount = countFiles(dir)
                Timber.tag("Epic").i("=== Summary ===")
                Timber.tag("Epic").i("Total files: $fileCount")
                Timber.tag("Epic").i("Total size: ${formatFileSize(totalSize)}")
                Timber.tag("Epic").i("==================")
            }
        }

        /**
         * Format file size in human-readable format
         */
        private fun formatFileSize(bytes: Long): String =
            when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
                else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            }

        /**
         * Calculate total size of a directory recursively
         */
        private fun calculateTotalSize(dir: File): Long {
            if (!dir.exists()) return 0
            if (dir.isFile) return dir.length()
            return dir.listFiles()?.sumOf { calculateTotalSize(it) } ?: 0
        }

        /**
         * Count total number of files in a directory recursively
         */
        private fun countFiles(dir: File): Int {
            if (!dir.exists()) return 0
            if (dir.isFile) return 1
            return dir.listFiles()?.sumOf { countFiles(it) } ?: 0
        }
    }
