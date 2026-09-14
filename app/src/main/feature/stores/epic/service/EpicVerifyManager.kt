package com.winlator.cmod.feature.stores.epic.service

import android.content.Context
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.app.db.download.DownloadRecord
import com.winlator.cmod.app.service.download.DownloadCoordinator
import com.winlator.cmod.feature.stores.common.InstallOwnership
import com.winlator.cmod.feature.stores.common.InstallStore
import com.winlator.cmod.feature.stores.epic.data.EpicGame
import com.winlator.cmod.feature.stores.epic.service.manifest.ChunkInfo
import com.winlator.cmod.feature.stores.epic.service.manifest.EpicManifest
import com.winlator.cmod.feature.stores.epic.service.manifest.FileManifest
import com.winlator.cmod.feature.stores.epic.service.manifest.ManifestUtils
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.feature.stores.steam.enums.Marker
import com.winlator.cmod.feature.stores.steam.events.AndroidEvent
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpicVerifyManager
    @Inject
    constructor(
        private val epicManager: EpicManager,
        private val epicDownloadManager: EpicDownloadManager,
    ) {
        suspend fun verifyGameFiles(
            context: Context,
            game: EpicGame,
            installPath: String,
            downloadInfo: DownloadInfo,
            containerLanguage: String = EpicConstants.EPIC_FALLBACK_CONTAINER_LANGUAGE,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                val gameId = game.id
                try {
                    val installDir = File(installPath)
                    if (!installDir.isDirectory) {
                        return@withContext Result.failure(Exception("Install directory not found: $installPath"))
                    }

                    PluviaApp.events.emitJava(AndroidEvent.DownloadStatusChanged(gameId, true))
                    downloadInfo.setActive(true)
                    downloadInfo.updateStatus(DownloadPhase.VERIFYING, "Fetching manifest")

                    val selectedTags = EpicConstants.containerLanguageToEpicInstallTags(containerLanguage)
                    val scopes = mutableListOf<VerifyManifestScope>()

                    val baseManifestResult =
                        epicManager.fetchManifestFromEpic(
                            context,
                            game.namespace,
                            game.catalogId,
                            game.appName,
                        )
                    if (baseManifestResult.isFailure) {
                        return@withContext Result.failure(
                            baseManifestResult.exceptionOrNull() ?: Exception("Failed to fetch manifest"),
                        )
                    }
                    scopes.add(buildScope(game.title, game.appName, baseManifestResult.getOrThrow(), selectedTags))

                    val installedDlcs =
                        epicManager
                            .getDLCForTitle(game.id)
                            .filter { it.isInstalled && it.installPath == installPath }
                    installedDlcs.forEach { dlc ->
                        downloadInfo.updateStatus(DownloadPhase.VERIFYING, "Fetching DLC manifest: ${dlc.title}")
                        val dlcManifestResult =
                            epicManager.fetchManifestFromEpic(
                                context,
                                dlc.namespace,
                                dlc.catalogId,
                                dlc.appName,
                            )
                        if (dlcManifestResult.isSuccess) {
                            scopes.add(buildScope(dlc.title, dlc.appName, dlcManifestResult.getOrThrow(), selectedTags))
                        } else {
                            Timber.tag("Epic").w(
                                dlcManifestResult.exceptionOrNull(),
                                "Failed to fetch DLC manifest while verifying ${dlc.title}",
                            )
                        }
                    }

                    val scanBytes = scopes.sumOf { scope -> scope.files.sumOf { it.fileSize } }
                    downloadInfo.setTotalExpectedBytes(scanBytes)
                    downloadInfo.setDisplayTotalExpectedBytes(scanBytes)
                    downloadInfo.initializeBytesDownloaded(0L)
                    downloadInfo.setProgress(0.0f)
                    downloadInfo.emitProgressChange()
                    syncCoordinatorProgress(gameId, downloadInfo)

                    var scannedBytes = 0L
                    val repairPlans =
                        scopes.mapIndexed { index, scope ->
                            downloadInfo.updateStatus(
                                DownloadPhase.VERIFYING,
                                if (scopes.size > 1) {
                                    "Verifying ${scope.title} (${index + 1}/${scopes.size})"
                                } else {
                                    "Verifying files"
                                },
                            )
                            scope to
                                buildRepairPlan(scope, installDir, downloadInfo) { delta ->
                                    scannedBytes = (scannedBytes + delta).coerceAtMost(scanBytes)
                                    downloadInfo.setBytesDownloaded(scannedBytes)
                                    downloadInfo.emitProgressChange()
                                    syncCoordinatorProgress(gameId, downloadInfo)
                                }
                        }
                    val repairBytes = repairPlans.sumOf { (_, plan) -> plan.repairChunks.sumOf { it.fileSize } }
                    if (repairBytes > 0L) {
                        val totalWorkBytes = scanBytes + repairBytes
                        downloadInfo.setTotalExpectedBytes(totalWorkBytes)
                        downloadInfo.setDisplayTotalExpectedBytes(totalWorkBytes)
                        downloadInfo.setBytesDownloaded(scannedBytes)
                        downloadInfo.emitProgressChange()
                        syncCoordinatorProgress(gameId, downloadInfo)
                    }

                    val chunkCacheDir = File(installDir, ".chunks").also { it.mkdirs() }
                    repairPlans.forEachIndexed { index, (scope, plan) ->
                        if (plan.repairFiles.isEmpty()) {
                            Timber.tag("Epic").i("Verify found no missing or corrupt files for ${scope.title}")
                            return@forEachIndexed
                        }

                        downloadInfo.updateStatus(
                            DownloadPhase.DOWNLOADING,
                            if (scopes.size > 1) {
                                "Repairing ${scope.title}: ${plan.repairFiles.size} files"
                            } else {
                                "Repairing ${plan.repairFiles.size} files"
                            },
                        )
                        val result =
                            epicDownloadManager.repairManifestFiles(
                                chunks = plan.repairChunks,
                                files = plan.repairFiles,
                                chunkCacheDir = chunkCacheDir,
                                chunkDir = scope.chunkDir,
                                cdnUrls = scope.cdnUrls,
                                installDir = installDir,
                                downloadInfo = downloadInfo,
                            ) { completed, total ->
                                downloadInfo.updateStatus(
                                    DownloadPhase.DOWNLOADING,
                                    if (scopes.size > 1) {
                                        "Repairing ${scope.title} ($completed/$total chunks)"
                                    } else {
                                        "Repairing files ($completed/$total chunks)"
                                    },
                                )
                                downloadInfo.emitProgressChange()
                                syncCoordinatorProgress(gameId, downloadInfo)
                            }
                        if (result.isFailure) {
                            return@withContext Result.failure(result.exceptionOrNull() ?: Exception("Verify repair failed"))
                        }

                        Timber.tag("Epic").i(
                            "Verify repaired ${plan.repairFiles.size} files for ${scope.title} (${index + 1}/${repairPlans.size})",
                        )
                    }

                    downloadInfo.updateStatus(DownloadPhase.FINALIZING, "Finalizing verify")
                    chunkCacheDir.deleteRecursively()
                    scopes.forEach { scope ->
                        EpicInstalledManifestStore.saveManifest(installDir, scope.appName, scope.manifestBytes)
                    }
                    MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                    InstallOwnership.claim(installPath, InstallStore.EPIC)
                    downloadInfo.updateStatus(DownloadPhase.COMPLETE, "Verify complete")
                    downloadInfo.updateBytesDownloaded(downloadInfo.getTotalExpectedBytes() - downloadInfo.getBytesDownloaded())
                    downloadInfo.setProgress(1.0f)
                    downloadInfo.setActive(false)
                    downloadInfo.emitProgressChange()
                    syncCoordinatorProgress(gameId, downloadInfo)
                    PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(gameId))
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Verify failed for ${game.title}")
                    downloadInfo.updateStatus(DownloadPhase.FAILED, e.message ?: "Verify failed")
                    downloadInfo.setProgress(-1.0f)
                    downloadInfo.setActive(false)
                    Result.failure(e)
                } finally {
                    PluviaApp.events.emitJava(AndroidEvent.DownloadStatusChanged(gameId, false))
                }
            }

        private fun buildScope(
            title: String,
            appName: String,
            manifestData: EpicManager.ManifestResult,
            selectedTags: List<String>,
        ): VerifyManifestScope {
            val cdnUrls = selectCdnUrls(manifestData.cdnUrls)
            if (cdnUrls.isEmpty()) {
                throw Exception("No usable CDN URLs in manifest")
            }

            val manifest = EpicManifest.readAll(manifestData.manifestBytes)
            val files = ManifestUtils.getFilesForSelectedInstallTags(manifest, selectedTags)
            val chunks = ManifestUtils.getRequiredChunksForFileList(manifest, files)
            if (files.isEmpty()) throw Exception("No file manifest in manifest")
            if (chunks.isEmpty()) throw Exception("No chunk data in manifest")

            return VerifyManifestScope(
                title = title,
                appName = appName,
                manifestBytes = manifestData.manifestBytes,
                files = files,
                chunks = chunks,
                chunkDir = manifest.getChunkDir(),
                cdnUrls = cdnUrls,
            )
        }

        private fun buildRepairPlan(
            scope: VerifyManifestScope,
            installDir: File,
            downloadInfo: DownloadInfo,
            onScannedBytes: (Long) -> Unit,
        ): VerifyRepairPlan {
            val repairFiles = mutableListOf<FileManifest>()
            scope.files.forEachIndexed { index, fileManifest ->
                if (index % 25 == 0) {
                    downloadInfo.updateStatusMessage("Verifying ${scope.title} (${index + 1}/${scope.files.size})")
                    downloadInfo.updateCurrentFileName(fileManifest.displayName)
                }
                if (!isInstalledFileValid(fileManifest, installDir, onScannedBytes)) {
                    repairFiles.add(fileManifest)
                }
            }
            downloadInfo.updateCurrentFileName(null)

            if (repairFiles.isEmpty()) {
                return VerifyRepairPlan(emptyList(), emptyList())
            }

            val chunksByGuid = scope.chunks.associateBy { it.guidStr }
            val repairChunkGuids =
                repairFiles
                    .asSequence()
                    .flatMap { file -> file.chunkParts.asSequence().map { it.guidStr } }
                    .toSet()
            val repairChunks = repairChunkGuids.mapNotNull { chunksByGuid[it] }

            Timber.tag("Epic").i(
                "Verify repair plan for ${scope.title}: ${repairFiles.size}/${scope.files.size} files, " +
                    "${repairChunks.size}/${scope.chunks.size} chunks",
            )
            return VerifyRepairPlan(repairFiles, repairChunks)
        }

        private fun isInstalledFileValid(
            fileManifest: FileManifest,
            installDir: File,
            onScannedBytes: ((Long) -> Unit)?,
        ): Boolean {
            val outputFile = File(installDir, fileManifest.filename)
            if (!outputFile.exists()) {
                onScannedBytes?.invoke(fileManifest.fileSize)
                return false
            }
            if (outputFile.length() != fileManifest.fileSize) {
                onScannedBytes?.invoke(fileManifest.fileSize)
                return false
            }
            val expectedHash =
                expectedFileHash(fileManifest)
                    ?: run {
                        onScannedBytes?.invoke(fileManifest.fileSize)
                        return true
                    }
            return verifyFileHash(outputFile, expectedHash.bytes, expectedHash.algorithm, onScannedBytes)
        }

        private fun expectedFileHash(fileManifest: FileManifest): ExpectedFileHash? =
            when {
                hasExpectedHash(fileManifest.hashSha256) -> ExpectedFileHash("SHA-256", fileManifest.hashSha256)
                hasExpectedHash(fileManifest.hash) -> ExpectedFileHash("SHA-1", fileManifest.hash)
                hasExpectedHash(fileManifest.hashMd5) -> ExpectedFileHash("MD5", fileManifest.hashMd5)
                else -> null
            }

        private fun verifyFileHash(
            file: File,
            expectedHash: ByteArray,
            algorithm: String,
            onScannedBytes: ((Long) -> Unit)?,
        ): Boolean =
            try {
                val digest = MessageDigest.getInstance(algorithm)
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break
                        digest.update(buffer, 0, bytesRead)
                        onScannedBytes?.invoke(bytesRead.toLong())
                    }
                }
                val actualHash = digest.digest()
                val matches = actualHash.contentEquals(expectedHash)
                if (!matches) {
                    Timber.tag("Epic").w("Hash mismatch for ${file.name} using $algorithm")
                }
                matches
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Hash verification failed for ${file.absolutePath}")
                false
            }

        private fun hasExpectedHash(hash: ByteArray): Boolean = hash.any { it != 0.toByte() }

        private val FileManifest.displayName: String
            get() = filename.substringAfterLast('/').substringAfterLast('\\')

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

        private fun selectCdnUrls(cdnUrls: List<EpicManager.CdnUrl>): List<EpicManager.CdnUrl> {
            val nonCloudflare =
                cdnUrls.filter {
                    !it.baseUrl.startsWith("https://cloudflare.epicgamescdn.com")
                }
            return nonCloudflare.ifEmpty { cdnUrls }
        }

        private data class VerifyManifestScope(
            val title: String,
            val appName: String,
            val manifestBytes: ByteArray,
            val files: List<FileManifest>,
            val chunks: List<ChunkInfo>,
            val chunkDir: String,
            val cdnUrls: List<EpicManager.CdnUrl>,
        )

        private data class VerifyRepairPlan(
            val repairFiles: List<FileManifest>,
            val repairChunks: List<ChunkInfo>,
        )

        private data class ExpectedFileHash(
            val algorithm: String,
            val bytes: ByteArray,
        )
    }
