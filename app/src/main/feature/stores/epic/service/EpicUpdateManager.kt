package com.winlator.cmod.feature.stores.epic.service

import android.content.Context
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
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class EpicUpdateInfo(
    val hasUpdate: Boolean = false,
    val downloadSize: Long = 0L,
    val changedFileCount: Int = 0,
    val removedFileCount: Int = 0,
    val message: String? = null,
)

internal object EpicInstalledManifestStore {
    private const val MANIFEST_DIR = ".winnative/epic/manifests"
    private val unsafeNameChars = Regex("[^A-Za-z0-9._-]")

    fun saveManifest(
        installDir: File,
        appName: String,
        manifestBytes: ByteArray,
    ) {
        val file = manifestFile(installDir, appName)
        file.parentFile?.mkdirs()
        file.writeBytes(manifestBytes)
    }

    fun loadManifest(
        installDir: File,
        appName: String,
    ): EpicManifest? =
        runCatching {
            val file = manifestFile(installDir, appName)
            if (!file.isFile || !file.canRead()) return@runCatching null
            EpicManifest.readAll(file.readBytes())
        }.onFailure {
            Timber.tag("Epic").w(it, "Failed to read installed Epic manifest for $appName")
        }.getOrNull()

    private fun manifestFile(
        installDir: File,
        appName: String,
    ): File = File(File(installDir, MANIFEST_DIR), "${sanitize(appName)}.manifest")

    private fun sanitize(value: String): String =
        value
            .ifBlank { "unknown" }
            .replace(unsafeNameChars, "_")
}

@Singleton
class EpicUpdateManager
    @Inject
    constructor(
        private val epicManager: EpicManager,
        private val epicDownloadManager: EpicDownloadManager,
    ) {
        suspend fun checkForGameUpdate(
            context: Context,
            game: EpicGame,
            installPath: String,
            containerLanguage: String = EpicConstants.EPIC_FALLBACK_CONTAINER_LANGUAGE,
        ): EpicUpdateInfo =
            withContext(Dispatchers.IO) {
                runCatching {
                    val plan =
                        buildPlan(
                            context = context,
                            game = game,
                            installPath = installPath,
                            containerLanguage = containerLanguage,
                            verifyWithoutInstalledManifest = false,
                            downloadInfo = null,
                        )
                    EpicUpdateInfo(
                        hasUpdate = plan.hasUpdate,
                        downloadSize = plan.downloadSize,
                        changedFileCount = plan.changedFileCount,
                        removedFileCount = plan.removedFileCount,
                    )
                }.getOrElse { error ->
                    Timber.tag("Epic").w(error, "Epic update check failed for ${game.title}")
                    EpicUpdateInfo(message = error.message ?: "Epic update check failed")
                }
            }

        suspend fun updateGameFiles(
            context: Context,
            game: EpicGame,
            installPath: String,
            downloadInfo: DownloadInfo,
            containerLanguage: String = EpicConstants.EPIC_FALLBACK_CONTAINER_LANGUAGE,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                val installDir = File(installPath)
                try {
                    if (!installDir.isDirectory) {
                        return@withContext Result.failure(Exception("Install directory not found: $installPath"))
                    }

                    MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                    downloadInfo.setActive(true)
                    downloadInfo.updateStatus(DownloadPhase.DOWNLOADING, "Checking for update")

                    val plan =
                        buildPlan(
                            context = context,
                            game = game,
                            installPath = installPath,
                            containerLanguage = containerLanguage,
                            verifyWithoutInstalledManifest = true,
                            downloadInfo = downloadInfo,
                        )

                    downloadInfo.setTotalExpectedBytes(plan.downloadSize)
                    downloadInfo.setDisplayTotalExpectedBytes(plan.downloadSize)
                    downloadInfo.initializeBytesDownloaded(0L)
                    downloadInfo.setProgress(0.0f)
                    downloadInfo.emitProgressChange()
                    syncCoordinatorProgress(game.id, downloadInfo)

                    if (!plan.hasUpdate) {
                        finalizeUpdate(game, installPath, installDir, plan)
                        downloadInfo.updateStatus(DownloadPhase.COMPLETE, "Already up to date")
                        downloadInfo.setProgress(1.0f)
                        downloadInfo.setActive(false)
                        downloadInfo.emitProgressChange()
                        syncCoordinatorProgress(game.id, downloadInfo)
                        return@withContext Result.success(Unit)
                    }

                    val chunkCacheDir = File(installDir, ".chunks").also { it.mkdirs() }
                    plan.scopes.forEachIndexed { index, scope ->
                        if (scope.updateFiles.isNotEmpty()) {
                            downloadInfo.updateStatus(
                                DownloadPhase.DOWNLOADING,
                                if (plan.scopes.size > 1) {
                                    "Updating ${scope.game.title} (${index + 1}/${plan.scopes.size})"
                                } else {
                                    "Updating files"
                                },
                            )
                            val result =
                                epicDownloadManager.downloadManifestFiles(
                                    chunks = scope.updateChunks,
                                    files = scope.updateFiles,
                                    chunkCacheDir = chunkCacheDir,
                                    chunkDir = scope.chunkDir,
                                    cdnUrls = scope.cdnUrls,
                                    installDir = installDir,
                                    downloadInfo = downloadInfo,
                                ) { completed, total ->
                                    downloadInfo.updateStatus(
                                        DownloadPhase.DOWNLOADING,
                                        if (plan.scopes.size > 1) {
                                            "Updating ${scope.game.title} ($completed/$total chunks)"
                                        } else {
                                            "Updating files ($completed/$total chunks)"
                                        },
                                    )
                                    downloadInfo.emitProgressChange()
                                    syncCoordinatorProgress(game.id, downloadInfo)
                            }
                            if (result.isFailure) {
                                restoreInstalledMarker(installPath)
                                return@withContext Result.failure(result.exceptionOrNull() ?: Exception("Epic update failed"))
                            }
                        }
                    }

                    chunkCacheDir.deleteRecursively()
                    downloadInfo.updateStatus(DownloadPhase.FINALIZING, "Finalizing update")
                    finalizeUpdate(game, installPath, installDir, plan)
                    downloadInfo.updateStatus(DownloadPhase.COMPLETE, "Update complete")
                    downloadInfo.updateBytesDownloaded(downloadInfo.getTotalExpectedBytes() - downloadInfo.getBytesDownloaded())
                    downloadInfo.setProgress(1.0f)
                    downloadInfo.setActive(false)
                    downloadInfo.emitProgressChange()
                    syncCoordinatorProgress(game.id, downloadInfo)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Epic update failed for ${game.title}")
                    restoreInstalledMarker(installPath)
                    downloadInfo.updateStatus(DownloadPhase.FAILED, e.message ?: "Epic update failed")
                    downloadInfo.setProgress(-1.0f)
                    downloadInfo.setActive(false)
                    Result.failure(e)
                }
            }

        private suspend fun buildPlan(
            context: Context,
            game: EpicGame,
            installPath: String,
            containerLanguage: String,
            verifyWithoutInstalledManifest: Boolean,
            downloadInfo: DownloadInfo?,
        ): EpicUpdatePlan {
            val installDir = File(installPath)
            if (!installDir.isDirectory) {
                throw Exception("Install directory not found: $installPath")
            }

            val selectedTags = EpicConstants.containerLanguageToEpicInstallTags(containerLanguage)
            val scopes = mutableListOf<EpicUpdateScope>()

            scopes.add(
                buildScope(
                    context = context,
                    game = game,
                    installDir = installDir,
                    selectedTags = selectedTags,
                    verifyWithoutInstalledManifest = verifyWithoutInstalledManifest,
                    downloadInfo = downloadInfo,
                ),
            )

            val installedDlcs =
                epicManager
                    .getDLCForTitle(game.id)
                    .filter { it.isInstalled && it.installPath == installPath }
            installedDlcs.forEach { dlc ->
                scopes.add(
                    buildScope(
                        context = context,
                        game = dlc,
                        installDir = installDir,
                        selectedTags = selectedTags,
                        verifyWithoutInstalledManifest = verifyWithoutInstalledManifest,
                        downloadInfo = downloadInfo,
                    ),
                )
            }

            return EpicUpdatePlan(scopes)
        }

        private suspend fun buildScope(
            context: Context,
            game: EpicGame,
            installDir: File,
            selectedTags: List<String>,
            verifyWithoutInstalledManifest: Boolean,
            downloadInfo: DownloadInfo?,
        ): EpicUpdateScope {
            downloadInfo?.updateStatusMessage("Fetching manifest: ${game.title}")
            val manifestResult =
                epicManager.fetchManifestFromEpic(
                    context,
                    game.namespace,
                    game.catalogId,
                    game.appName,
                )
            if (manifestResult.isFailure) {
                throw manifestResult.exceptionOrNull() ?: Exception("Failed to fetch manifest")
            }

            val manifestData = manifestResult.getOrThrow()
            val newManifest = EpicManifest.readAll(manifestData.manifestBytes)
            val newFiles = ManifestUtils.getFilesForSelectedInstallTags(newManifest, selectedTags)
            val oldManifest = EpicInstalledManifestStore.loadManifest(installDir, game.appName)
            val oldFiles =
                oldManifest?.let { ManifestUtils.getFilesForSelectedInstallTags(it, selectedTags) }
                    ?: emptyList()
            val comparison = compareFiles(oldFiles, newFiles)
            val versionChanged = game.version.isBlank() || game.version != newManifest.meta?.buildVersion.orEmpty()

            val updateFiles =
                when {
                    oldManifest != null -> {
                        val missingUnchanged =
                            comparison.unchanged.filterNot { isInstalledFilePresent(it, installDir) }
                        (comparison.added + comparison.modified.map { it.second } + missingUnchanged)
                            .distinctBy { it.filename }
                    }
                    verifyWithoutInstalledManifest -> {
                        downloadInfo?.updateStatusMessage("Scanning existing files: ${game.title}")
                        newFiles.filterNot { isInstalledFileValid(it, installDir) }
                    }
                    versionChanged -> newFiles
                    else -> emptyList()
                }

            val chunks = ManifestUtils.getRequiredChunksForFileList(newManifest, updateFiles)
            val cdnUrls = selectCdnUrls(manifestData.cdnUrls)
            if (cdnUrls.isEmpty()) {
                throw Exception("No usable CDN URLs in manifest")
            }

            return EpicUpdateScope(
                game = game,
                manifestBytes = manifestData.manifestBytes,
                manifest = newManifest,
                updateFiles = updateFiles,
                updateChunks = chunks,
                removedFiles = comparison.removed,
                versionChanged = versionChanged,
                chunkDir = newManifest.getChunkDir(),
                cdnUrls = cdnUrls,
            )
        }

        private suspend fun finalizeUpdate(
            baseGame: EpicGame,
            installPath: String,
            installDir: File,
            plan: EpicUpdatePlan,
        ) {
            plan.scopes.forEach { scope ->
                scope.removedFiles.forEach { fileManifest ->
                    val file = File(installDir, fileManifest.filename)
                    if (file.exists() && file.isFile && !file.delete()) {
                        Timber.tag("Epic").w("Failed to delete removed Epic file ${file.absolutePath}")
                    }
                }
                EpicInstalledManifestStore.saveManifest(installDir, scope.game.appName, scope.manifestBytes)
                val installSize = ManifestUtils.getTotalInstalledSize(scope.manifest)
                val updated =
                    scope.game.copy(
                        isInstalled = true,
                        installPath = installPath,
                        installSize = installSize.takeIf { it > 0L } ?: scope.game.installSize,
                        version = scope.manifest.meta?.buildVersion.orEmpty().ifBlank { scope.game.version },
                        executable = scope.manifest.meta?.launchExe.orEmpty().ifBlank { scope.game.executable },
                    )
                epicManager.updateGame(updated)
            }

            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            InstallOwnership.claim(installPath, InstallStore.EPIC)
            com.winlator.cmod.app.PluviaApp.events.emitJava(
                com.winlator.cmod.feature.stores.steam.events.AndroidEvent.LibraryInstallStatusChanged(baseGame.id),
            )
        }

        private fun restoreInstalledMarker(installPath: String) {
            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            InstallOwnership.claim(installPath, InstallStore.EPIC)
        }

        private fun compareFiles(
            oldFiles: List<FileManifest>,
            newFiles: List<FileManifest>,
        ): EpicFileComparison {
            val oldByName = oldFiles.associateBy { it.filename }
            val newByName = newFiles.associateBy { it.filename }
            val added = mutableListOf<FileManifest>()
            val modified = mutableListOf<Pair<FileManifest, FileManifest>>()
            val unchanged = mutableListOf<FileManifest>()
            val removed = mutableListOf<FileManifest>()

            newByName.forEach { (filename, newFile) ->
                val oldFile = oldByName[filename]
                when {
                    oldFile == null -> added.add(newFile)
                    filesEqual(oldFile, newFile) -> unchanged.add(newFile)
                    else -> modified.add(oldFile to newFile)
                }
            }
            oldByName.forEach { (filename, oldFile) ->
                if (filename !in newByName) removed.add(oldFile)
            }
            return EpicFileComparison(added, modified, unchanged, removed)
        }

        private fun filesEqual(
            oldFile: FileManifest,
            newFile: FileManifest,
        ): Boolean {
            if (oldFile.fileSize != newFile.fileSize) return false
            val oldHash = expectedHash(oldFile)
            val newHash = expectedHash(newFile)
            return if (oldHash != null && newHash != null) {
                oldHash.contentEquals(newHash)
            } else {
                oldHash == null && newHash == null
            }
        }

        private fun isInstalledFilePresent(
            fileManifest: FileManifest,
            installDir: File,
        ): Boolean {
            val file = File(installDir, fileManifest.filename)
            return file.isFile && file.length() == fileManifest.fileSize
        }

        private fun isInstalledFileValid(
            fileManifest: FileManifest,
            installDir: File,
        ): Boolean {
            val file = File(installDir, fileManifest.filename)
            if (!file.isFile || file.length() != fileManifest.fileSize) return false
            val expected = expectedHash(fileManifest) ?: return true
            val algorithm =
                when (expected.size) {
                    32 -> "SHA-256"
                    20 -> "SHA-1"
                    16 -> "MD5"
                    else -> return true
                }
            return runCatching {
                val digest = MessageDigest.getInstance(algorithm)
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().contentEquals(expected)
            }.getOrElse {
                Timber.tag("Epic").w(it, "Failed to verify ${file.absolutePath}")
                false
            }
        }

        private fun expectedHash(fileManifest: FileManifest): ByteArray? =
            when {
                fileManifest.hashSha256.any { it != 0.toByte() } -> fileManifest.hashSha256
                fileManifest.hash.any { it != 0.toByte() } -> fileManifest.hash
                fileManifest.hashMd5.any { it != 0.toByte() } -> fileManifest.hashMd5
                else -> null
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

        private fun selectCdnUrls(cdnUrls: List<EpicManager.CdnUrl>): List<EpicManager.CdnUrl> {
            val nonCloudflare =
                cdnUrls.filter {
                    !it.baseUrl.startsWith("https://cloudflare.epicgamescdn.com")
                }
            return nonCloudflare.ifEmpty { cdnUrls }
        }

        private data class EpicUpdatePlan(
            val scopes: List<EpicUpdateScope>,
        ) {
            val downloadSize: Long get() = scopes.sumOf { scope -> scope.updateChunks.sumOf { it.fileSize } }
            val changedFileCount: Int get() = scopes.sumOf { it.updateFiles.size }
            val removedFileCount: Int get() = scopes.sumOf { it.removedFiles.size }
            val hasUpdate: Boolean
                get() = scopes.any { it.versionChanged || it.updateFiles.isNotEmpty() || it.removedFiles.isNotEmpty() }
        }

        private data class EpicUpdateScope(
            val game: EpicGame,
            val manifestBytes: ByteArray,
            val manifest: EpicManifest,
            val updateFiles: List<FileManifest>,
            val updateChunks: List<ChunkInfo>,
            val removedFiles: List<FileManifest>,
            val versionChanged: Boolean,
            val chunkDir: String,
            val cdnUrls: List<EpicManager.CdnUrl>,
        )

        private data class EpicFileComparison(
            val added: List<FileManifest>,
            val modified: List<Pair<FileManifest, FileManifest>>,
            val unchanged: List<FileManifest>,
            val removed: List<FileManifest>,
        )
    }
