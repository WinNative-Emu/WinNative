package com.winlator.cmod.feature.stores.gog.service

import android.content.Context
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.app.db.download.DownloadRecord
import com.winlator.cmod.app.service.download.DownloadCoordinator
import com.winlator.cmod.feature.stores.common.InstallOwnership
import com.winlator.cmod.feature.stores.common.InstallStore
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

@Singleton
class GOGVerifyManager
    @Inject
    constructor(
        private val gogDownloadManager: GOGDownloadManager,
        private val gogManager: GOGManager,
        @ApplicationContext private val context: Context,
    ) {

        suspend fun verifyGameFiles(
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

                    PluviaApp.events.emitJava(AndroidEvent.DownloadStatusChanged(gameIdInt, true))
                    downloadInfo.setActive(true)
                    downloadInfo.isCancelling = false
                    downloadInfo.updateStatus(DownloadPhase.VERIFYING, "Preparing verification")

                    // Read locally-persisted manifest metadata to pin verification to the installed build.
                    val localManifest = GOGManifestUtils.readLocalManifest(installPath)
                    val installedBuildId = localManifest?.optString("buildId", "")?.takeIf { it.isNotBlank() }
                    val storedLanguage = localManifest?.optString("language", "")?.takeIf { it.isNotBlank() }
                    val installedDlcIds = GOGManifestUtils.getInstalledDlcIds(installPath)
                    val effectiveLanguage = storedLanguage ?: language

                    if (installedBuildId == null) {
                        Timber.tag("GOG").w(
                            "No local manifest with buildId found for game $gameId at ${installPath.absolutePath}; " +
                                "verifying against latest build instead",
                        )
                    }

                    val supportDir = File(installPath, "_CommonRedist")

                    // Mark in-progress so concurrent installs/launches see this as active work.
                    MarkerUtils.addMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_COMPLETE_MARKER)

                    val result =
                        gogDownloadManager.downloadGame(
                            gameId = gameId,
                            installPath = installPath,
                            downloadInfo = downloadInfo,
                            language = effectiveLanguage,
                            withDlcs = installedDlcIds.isNotEmpty(),
                            supportDir = supportDir,
                            selectedDlcIds = installedDlcIds,
                            buildIdOverride = installedBuildId,
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
                    downloadInfo.updateStatus(DownloadPhase.COMPLETE, "Verify complete")
                    downloadInfo.setProgress(1.0f)
                    downloadInfo.setActive(false)
                    downloadInfo.emitProgressChange()
                    syncCoordinatorProgress(gameId, downloadInfo)
                    PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(gameIdInt))
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "Verify failed for game $gameId")
                    restoreInstalledMarker(installPath.absolutePath)
                    downloadInfo.updateStatus(DownloadPhase.FAILED, e.message ?: "Verify failed")
                    downloadInfo.setProgress(-1.0f)
                    downloadInfo.setActive(false)
                    Result.failure(e)
                } finally {
                    PluviaApp.events.emitJava(AndroidEvent.DownloadStatusChanged(gameIdInt, false))
                }
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
