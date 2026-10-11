package com.winlator.cmod.feature.stores.gog.service

import android.content.Context
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.app.db.download.DownloadRecord
import com.winlator.cmod.app.service.download.DownloadCoordinator
import com.winlator.cmod.feature.stores.common.InstallOwnership
import com.winlator.cmod.feature.stores.common.InstallStore
import com.winlator.cmod.feature.stores.gog.api.DepotFile
import com.winlator.cmod.feature.stores.gog.api.GOGApiClient
import com.winlator.cmod.feature.stores.gog.api.GOGBuild
import com.winlator.cmod.feature.stores.gog.api.GOGManifestParser
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.feature.stores.steam.enums.Marker
import com.winlator.cmod.feature.stores.steam.events.AndroidEvent
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a "check for update" probe against the GOG content system.
 */
data class GOGUpdateInfo(
    val hasUpdate: Boolean = false,
    val currentBuildId: String? = null,
    val latestBuildId: String? = null,
    val latestVersionName: String? = null,
    val downloadSize: Long = 0L,
    val changedFileCount: Int = 0,
    val message: String? = null,
)

@Singleton
class GOGUpdateManager
    @Inject
    constructor(
        private val gogDownloadManager: GOGDownloadManager,
        private val gogManager: GOGManager,
        private val apiClient: GOGApiClient,
        private val parser: GOGManifestParser,
        @ApplicationContext private val context: Context,
    ) {
        private val WINDOWS_OS_VERSION = "windows"

        suspend fun checkForGameUpdate(
            gameId: String,
            installPath: File,
            language: String = GOGConstants.GOG_FALLBACK_DOWNLOAD_LANGUAGE,
        ): GOGUpdateInfo =
            withContext(Dispatchers.IO) {
                try {
                    if (!installPath.isDirectory) {
                        return@withContext GOGUpdateInfo(
                            message = "Install directory not found: ${installPath.absolutePath}",
                        )
                    }

                    val localManifest = GOGManifestUtils.readLocalManifest(installPath)
                    val installedBuildId = localManifest?.optString("buildId", "")?.takeIf { it.isNotBlank() }
                    val effectiveLanguage =
                        localManifest?.optString("language", "")?.takeIf { it.isNotBlank() } ?: language

                    val latestBuild = selectPreferredBuild(gameId)
                        ?: return@withContext GOGUpdateInfo(
                            currentBuildId = installedBuildId,
                            message = "No Windows builds available for this game",
                        )

                    if (installedBuildId == null) {
                        return@withContext GOGUpdateInfo(
                            currentBuildId = null,
                            latestBuildId = latestBuild.buildId,
                            latestVersionName = latestBuild.versionName,
                            message = "No installed build id recorded — run Verify Files first",
                        )
                    }

                    if (installedBuildId == latestBuild.buildId) {
                        return@withContext GOGUpdateInfo(
                            hasUpdate = false,
                            currentBuildId = installedBuildId,
                            latestBuildId = latestBuild.buildId,
                            latestVersionName = latestBuild.versionName,
                        )
                    }

                    val plan = computeUpdatePlan(gameId, installPath, latestBuild, effectiveLanguage)

                    GOGUpdateInfo(
                        hasUpdate = plan.changedFiles.isNotEmpty() || installedBuildId != latestBuild.buildId,
                        currentBuildId = installedBuildId,
                        latestBuildId = latestBuild.buildId,
                        latestVersionName = latestBuild.versionName,
                        downloadSize = plan.downloadSize,
                        changedFileCount = plan.changedFiles.size,
                    )
                } catch (e: Exception) {
                    Timber.tag("GOG").w(e, "GOG update check failed for $gameId")
                    GOGUpdateInfo(message = e.message ?: "Update check failed")
                }
            }

        suspend fun updateGameFiles(
            gameId: String,
            installPath: File,
            downloadInfo: DownloadInfo,
            language: String = GOGConstants.GOG_FALLBACK_DOWNLOAD_LANGUAGE,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                val gameIdInt = gameId.toIntOrNull() ?: 0
                try {
                    if (!installPath.isDirectory) {
                        return@withContext Result.failure(
                            Exception("Install directory not found: ${installPath.absolutePath}"),
                        )
                    }

                    val localManifest = GOGManifestUtils.readLocalManifest(installPath)
                    val storedLanguage = localManifest?.optString("language", "")?.takeIf { it.isNotBlank() }
                    val installedDlcIds = GOGManifestUtils.getInstalledDlcIds(installPath)
                    val effectiveLanguage = storedLanguage ?: language

                    PluviaApp.events.emitJava(AndroidEvent.DownloadStatusChanged(gameIdInt, true))
                    downloadInfo.setActive(true)
                    downloadInfo.isCancelling = false
                    downloadInfo.updateStatus(DownloadPhase.DOWNLOADING, "Preparing update")

                    MarkerUtils.addMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_COMPLETE_MARKER)

                    val supportDir = File(installPath, "_CommonRedist")

                    val result =
                        gogDownloadManager.downloadGame(
                            gameId = gameId,
                            installPath = installPath,
                            downloadInfo = downloadInfo,
                            language = effectiveLanguage,
                            withDlcs = installedDlcIds.isNotEmpty(),
                            supportDir = supportDir,
                            selectedDlcIds = installedDlcIds,
                            buildIdOverride = null, // use latest build
                            verifyMode = true,
                            verifyProgressSink = { bytesDone, total ->
                                DownloadCoordinator.updateProgress(
                                    DownloadRecord.STORE_GOG,
                                    gameId,
                                    bytesDone,
                                    total,
                                )
                            },
                        )

                    if (result.isFailure) {
                        restoreInstalledMarker(installPath.absolutePath)
                        return@withContext result
                    }

                    downloadInfo.updateStatus(DownloadPhase.COMPLETE, "Update complete")
                    downloadInfo.setProgress(1.0f)
                    downloadInfo.setActive(false)
                    downloadInfo.emitProgressChange()
                    syncCoordinatorProgress(gameId, downloadInfo)
                    PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(gameIdInt))
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "Update failed for game $gameId")
                    restoreInstalledMarker(installPath.absolutePath)
                    downloadInfo.updateStatus(DownloadPhase.FAILED, e.message ?: "Update failed")
                    downloadInfo.setProgress(-1.0f)
                    downloadInfo.setActive(false)
                    Result.failure(e)
                } finally {
                    PluviaApp.events.emitJava(AndroidEvent.DownloadStatusChanged(gameIdInt, false))
                }
            }

        private suspend fun selectPreferredBuild(gameId: String): GOGBuild? {
            val gen2 = apiClient.getBuildsForGame(gameId, WINDOWS_OS_VERSION, generation = 2)
            if (gen2.isSuccess) {
                parser
                    .selectBuild(gen2.getOrThrow().items, preferredGeneration = 2, platform = WINDOWS_OS_VERSION)
                    ?.let { return it }
            }
            val gen1 = apiClient.getBuildsForGame(gameId, WINDOWS_OS_VERSION, generation = 1)
            if (gen1.isSuccess) {
                return parser.selectBuild(gen1.getOrThrow().items, preferredGeneration = 1, platform = WINDOWS_OS_VERSION)
            }
            return null
        }

        private data class UpdatePlan(
            val changedFiles: List<DepotFile>,
            val downloadSize: Long,
        )

        private suspend fun computeUpdatePlan(
            gameId: String,
            installPath: File,
            latestBuild: GOGBuild,
            language: String,
        ): UpdatePlan {
            val manifestResult = apiClient.fetchManifest(latestBuild.link)
            if (manifestResult.isFailure) {
                Timber.tag("GOG").w(
                    manifestResult.exceptionOrNull(),
                    "Failed to fetch latest manifest for $gameId during update check",
                )
                return UpdatePlan(emptyList(), 0L)
            }
            val manifest = manifestResult.getOrThrow()

            if (latestBuild.generation == 1) {
                return UpdatePlan(changedFiles = emptyList(), downloadSize = 0L)
            }

            val ownedGameIds = gogManager.getAllGameIds()
            val installedDlcIds = GOGManifestUtils.getInstalledDlcIds(installPath)
            val requestedProductIds = buildSet {
                add(manifest.baseProductId)
                addAll(installedDlcIds)
            }

            val (languageDepots, _) = parser.filterDepotsByLanguage(manifest, language)
            val depots =
                parser
                    .filterDepotsByOwnership(languageDepots, ownedGameIds)
                    .filter { it.productId in requestedProductIds }
            if (depots.isEmpty()) return UpdatePlan(emptyList(), 0L)

            val allFiles = mutableListOf<DepotFile>()
            for (depot in depots) {
                val depotManifestResult = apiClient.fetchDepotManifest(depot.manifest)
                if (depotManifestResult.isFailure) {
                    Timber.tag("GOG").w(
                        depotManifestResult.exceptionOrNull(),
                        "Failed to fetch depot manifest ${depot.manifest} during update check",
                    )
                    continue
                }
                allFiles += depotManifestResult.getOrThrow().files.filterNot { it.isSupportFile() }
            }

            val changed = mutableListOf<DepotFile>()
            val seenCompressedChunks = HashSet<String>()
            var downloadSize = 0L
            for (file in allFiles) {
                val outputFile = File(installPath, file.path)
                if (!gogDownloadManager.isInstalledFileValidByChunkMd5(outputFile, file)) {
                    changed += file
                    for (chunk in file.chunks) {
                        if (seenCompressedChunks.add(chunk.compressedMd5)) {
                            downloadSize += (chunk.compressedSize ?: chunk.size).coerceAtLeast(0L)
                        }
                    }
                }
            }
            return UpdatePlan(changed, downloadSize)
        }

        private fun restoreInstalledMarker(installPath: String) {
            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            InstallOwnership.claim(installPath, InstallStore.GOG)
        }

        private fun syncCoordinatorProgress(
            gameId: String,
            downloadInfo: DownloadInfo,
        ) {
            DownloadCoordinator.updateProgress(
                DownloadRecord.STORE_GOG,
                gameId,
                downloadInfo.getBytesDownloaded(),
                downloadInfo.getTotalExpectedBytes(),
            )
        }
    }
