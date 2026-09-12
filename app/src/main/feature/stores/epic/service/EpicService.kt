package com.winlator.cmod.feature.stores.epic.service
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.winlator.cmod.BuildConfig
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.app.db.download.DownloadRecord
import com.winlator.cmod.app.service.DownloadService
import com.winlator.cmod.app.service.download.DownloadCoordinator
import com.winlator.cmod.feature.shortcuts.LibraryShortcutUtils
import com.winlator.cmod.feature.stores.common.StoreArtworkCache
import com.winlator.cmod.feature.stores.epic.data.EpicCredentials
import com.winlator.cmod.feature.stores.epic.data.EpicGame
import com.winlator.cmod.feature.stores.epic.data.EpicGameToken
import com.winlator.cmod.feature.stores.epic.ui.util.SnackbarManager
import com.winlator.cmod.feature.stores.common.StoreInstallPathSafety
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.feature.stores.steam.enums.Marker
import com.winlator.cmod.feature.stores.steam.events.AndroidEvent
import com.winlator.cmod.feature.stores.steam.utils.ContainerUtils
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.runtime.system.SessionKeepAliveService
import com.winlator.cmod.shared.android.AppTerminationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

// Service facade for Epic auth, library sync, downloads, and cloud saves.
@AndroidEntryPoint
class EpicService : Service() {
    /*private lateinit var notificationHelper: NotificationHelper
    var notificationID = 1*/

    @Inject
    lateinit var epicManager: EpicManager

    @Inject
    lateinit var epicDownloadManager: EpicDownloadManager

    @Inject
    lateinit var epicVerifyManager: EpicVerifyManager

    @Inject
    lateinit var epicUpdateManager: EpicUpdateManager

    @Inject
    lateinit var epicOverlayManager: EpicOverlayManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val activeDownloads = ConcurrentHashMap<Int, DownloadInfo>()

    companion object {
        private var instance: EpicService? = null

        private const val ACTION_SYNC_LIBRARY = BuildConfig.APPLICATION_ID + ".EPIC_SYNC_LIBRARY"
        private const val ACTION_MANUAL_SYNC = BuildConfig.APPLICATION_ID + ".EPIC_MANUAL_SYNC"
        private const val SYNC_THROTTLE_MILLIS = 15 * 60 * 1000L // 15 minutes

        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null
        private var lastSyncTimestamp: Long = 0L
        private var hasPerformedInitialSync: Boolean = false
        private var hasPerformedInitialCloudSaveCheck: Boolean = false

        val isRunning: Boolean
            get() = instance != null


        fun start(context: Context) {
            Timber.tag("EPIC").d("Starting service...")
            if (isRunning) {
                Timber.tag("EPIC").d("[EpicService] Service already running, skipping start")
                return
            }

            if (!hasPerformedInitialSync) {
                Timber.tag("EPIC").i("[EpicService] First-time start - starting service with initial sync")
                val intent = Intent(context, EpicService::class.java)
                intent.action = ACTION_SYNC_LIBRARY

                startEpicService(context, intent)
                return
            }

            val now = System.currentTimeMillis()
            val timeSinceLastSync = now - lastSyncTimestamp

            val intent = Intent(context, EpicService::class.java)
            if (timeSinceLastSync >= SYNC_THROTTLE_MILLIS) {
                Timber.tag("EPIC").i("[EpicService] Starting service with automatic sync (throttle passed)")
                intent.action = ACTION_SYNC_LIBRARY
            } else {
                val remainingMinutes = (SYNC_THROTTLE_MILLIS - timeSinceLastSync) / 1000 / 60
                Timber.tag("EPIC").i("Starting service without sync - throttled (${remainingMinutes}min remaining)")
            }

            startEpicService(context, intent)
        }

        fun triggerLibrarySync(context: Context) {
            Timber.tag("EPIC").i("Triggering manual library sync (bypasses throttle)")
            val intent = Intent(context, EpicService::class.java)
            intent.action = ACTION_MANUAL_SYNC
            startEpicService(context, intent)
        }

        fun startEpicService(context: Context, intent: Intent) {
            try {
                // Just start as a normal service. KeepAliveService should protect this.
                context.startService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start EpicService")
            }
        }

        fun stop() {
            instance?.let { service ->
                runCatching {
                    SessionKeepAliveService.stopComponent(service, SessionKeepAliveService.COMPONENT_EPIC)
                }.onFailure { Timber.w(it, "Failed to remove EpicService foreground state during shutdown") }
                /*runCatching {
                    if (service::notificationHelper.isInitialized)
                        service.notificationHelper.cancel(service.notificationID)
                }.onFailure { Timber.w(it, "Failed to cancel EpicService notification during shutdown") }*/
                service.stopSelf()
            }
        }


        suspend fun authenticateWithCode(
            context: Context,
            authorizationCode: String,
        ): Result<EpicCredentials> = EpicAuthManager.authenticateWithCode(context, authorizationCode)

        fun hasStoredCredentials(context: Context): Boolean = EpicAuthManager.hasStoredCredentials(context)

        suspend fun getStoredCredentials(context: Context): Result<EpicCredentials> = EpicAuthManager.getStoredCredentials(context)

        // Clears credentials, removes non-installed games, and stops the service.
        suspend fun logout(context: Context): Result<Unit> {
            return withContext(Dispatchers.IO) {
                try {
                    Timber.tag("EPIC").i("Logging out from Epic...")

                    val credentialsCleared = EpicAuthManager.clearStoredCredentials(context)
                    if (!credentialsCleared) {
                        Timber.tag("Epic").e("Failed to clear credentials during logout")
                        return@withContext Result.failure(Exception("Failed to clear stored credentials"))
                    }

                    val instance = getInstance()
                    if (instance != null) {
                        instance.epicManager.deleteAllNonInstalledGames()
                        Timber.tag("Epic").i("All Non-installed Epic games removed from database")

                        stop()
                    } else {
                        Timber.tag("Epic").w("Service not running during logout, but credentials were cleared")
                    }

                    Timber.tag("Epic").i("Logout completed successfully")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Error during logout")
                    Result.failure(e)
                }
            }
        }


        fun hasActiveOperations(): Boolean = syncInProgress || backgroundSyncJob?.isActive == true || hasActiveDownload()

        private fun setSyncInProgress(inProgress: Boolean) {
            syncInProgress = inProgress
        }

        fun isSyncInProgress(): Boolean = syncInProgress

        fun getInstance(): EpicService? = instance


        fun hasActiveDownload(): Boolean = getInstance()?.activeDownloads?.isNotEmpty() ?: false

        fun getCurrentlyDownloadingGame(): Int? = getInstance()?.activeDownloads?.keys?.firstOrNull()

        fun getDownloadInfo(appId: Int): DownloadInfo? = getInstance()?.activeDownloads?.get(appId)

        fun getAllDownloads(): Map<Int, DownloadInfo> = getInstance()?.activeDownloads ?: emptyMap()

        suspend fun deleteGame(
            context: Context,
            appId: Int,
        ): Result<Unit> {
            var instance = getInstance()
            if (instance == null) {
                Timber.tag("Epic").i("deleteGame: Service not running, attempting to start...")
                start(context)

                for (i in 0..20) {
                    kotlinx.coroutines.delay(100)
                    instance = getInstance()
                    if (instance != null) break
                }
            }

            if (instance == null) {
                Timber.tag("Epic").e("deleteGame: EpicService failed to start or instance is still null")
                return Result.failure(Exception("Epic service is not active. Please try again in a moment."))
            }

            return try {
                Timber.tag("Epic").i("Starting uninstallation for appId: $appId")

                // Terminate any running Wine processes to avoid file locks
                withContext(Dispatchers.Main) {
                    Timber.tag("Epic").d("Terminating Wine processes...")
                    com.winlator.cmod.runtime.system.ProcessHelper
                        .terminateAllWineProcesses()
                    kotlinx.coroutines.delay(1000)
                }

                val game = instance.epicManager.getGameById(appId)
                if (game == null) {
                    Timber.tag("Epic").e("deleteGame: Game not found in DB: $appId")
                    return Result.failure(Exception("Game not found: $appId"))
                }

                val path = if (game.installPath.isNotEmpty()) game.installPath else EpicConstants.getGameInstallPath(context, game.appName)
                val gameDir = File(path)

                // Safety check: Ensure we are NOT deleting the base Epic/games directory
                val deleteCheck =
                    StoreInstallPathSafety.checkInstallDirDelete(
                        context,
                        path,
                        protectedRoots = listOf(EpicConstants.defaultEpicGamesPath(context)),
                    )
                if (!deleteCheck.allowed) {
                    Timber.tag("Epic").e("Safety Triggered: Refusing to delete install path '$path': ${deleteCheck.reason}")
                    return Result.failure(Exception("Refusing to delete unsafe install path: $path"))
                } else if (gameDir.exists()) {
                    Timber.tag("Epic").i("Deleting installation folder: $path")
                    try {
                        val deleted = gameDir.deleteRecursively()
                        if (deleted) {
                            Timber.tag("Epic").i("Successfully deleted installation folder")
                        } else {
                            Timber.tag("Epic").w("Failed to delete some files in installation folder")
                            return Result.failure(Exception("Failed to fully delete installation folder: $path"))
                        }
                    } catch (e: Exception) {
                        Timber.tag("Epic").e(e, "Exception while deleting installation folder")
                        return Result.failure(e)
                    }

                    MarkerUtils.removeMarker(path, Marker.DOWNLOAD_COMPLETE_MARKER)
                    MarkerUtils.removeMarker(path, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                } else {
                    Timber.tag("Epic").w("Installation folder not found: $path")
                }

                // Uninstall from database (keeps the entry but marks as not installed)
                Timber.tag("Epic").d("Updating database: marking game $appId as uninstalled")
                instance.epicManager.uninstall(appId)
                StoreArtworkCache.deleteGame(context, "epic", appId.toString())

                // Delete game shortcuts but preserve the created containers
                withContext(Dispatchers.IO) {
                    val deletedCount = LibraryShortcutUtils.deleteEpicShortcuts(context, appId)
                    Timber.tag("Epic").d("Deleted $deletedCount Epic shortcuts for appId=$appId")
                }

                Timber.tag("Epic").d("Emitting LibraryInstallStatusChanged event")
                com.winlator.cmod.app.PluviaApp.events.emitJava(
                    com.winlator.cmod.feature.stores.steam.events.AndroidEvent
                        .LibraryInstallStatusChanged(appId),
                )

                Timber.tag("Epic").i("Successfully completed uninstallation for appId: $appId")
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Critical failure during uninstallation for appId: $appId")
                Result.failure(e)
            }
        }

        suspend fun cleanupDownload(
            context: Context,
            appId: Int,
        ) {
            withContext(Dispatchers.IO) {
                getInstance()?.epicManager?.getGameById(appId)?.let { game ->
                    val path = EpicConstants.getGameInstallPath(context, game.appName)
                    MarkerUtils.removeMarker(path, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                }
            }
            getInstance()?.activeDownloads?.remove(appId)
        }

        fun cancelDownload(appId: Int): Boolean {
            // Route through the coordinator: it persists CANCELLED and asks our dispatcher to
            DownloadCoordinator.runOnScope {
                DownloadCoordinator.cancel(DownloadRecord.STORE_EPIC, appId.toString())
            }
            return true
        }

        fun pauseDownload(appId: Int) {
            DownloadCoordinator.runOnScope {
                DownloadCoordinator.pause(DownloadRecord.STORE_EPIC, appId.toString())
            }
        }

        fun pauseAll() {
            DownloadCoordinator.runOnScope { DownloadCoordinator.pauseAll() }
        }

        fun resumeDownload(appId: Int) {
            // Coordinator marks the record as QUEUED and ticks. The dispatcher will be invoked
            // to launch the actual download (it pulls saved DLC/language/install-path either
            // from the in-memory params cache or from the persisted record).
            DownloadCoordinator.runOnScope {
                DownloadCoordinator.resume(DownloadRecord.STORE_EPIC, appId.toString())
            }
        }

        fun resumeAll() {
            DownloadCoordinator.runOnScope { DownloadCoordinator.resumeAll() }
        }

        fun cancelAll() {
            DownloadCoordinator.runOnScope { DownloadCoordinator.cancelAll() }
        }

        fun clearCompletedDownloads() {
            val instance = getInstance() ?: return
            val toRemove =
                instance.activeDownloads
                    .filterValues {
                        val status = it.getStatusFlow().value
                        status == com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.COMPLETE ||
                            status == com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.CANCELLED ||
                            status == com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.FAILED
                    }.keys
            if (toRemove.isNotEmpty()) {
                toRemove.forEach { instance.activeDownloads.remove(it) }
                // Notify the Downloads tab so the list re-syncs and the cleared rows disappear.
                toRemove.forEach { appId ->
                    PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, false))
                }
            }
        }


        fun getEpicGameOf(appId: Int): EpicGame? =
            runBlocking(Dispatchers.IO) {
                getInstance()?.epicManager?.getGameById(appId)
            }

        fun getEpicGameByAppName(appName: String): EpicGame? =
            runBlocking(Dispatchers.IO) {
                getInstance()?.epicManager?.getGameByAppName(appName)
            }

        fun getDLCForGame(appId: Int): List<EpicGame> = runBlocking(Dispatchers.IO) { getDLCForGameSuspend(appId) }

        suspend fun getDLCForGameSuspend(appId: Int): List<EpicGame> = getInstance()?.epicManager?.getDLCForTitle(appId) ?: emptyList()

        suspend fun updateEpicGame(game: EpicGame) {
            getInstance()?.epicManager?.updateGame(game)
        }

        fun isGameInstalled(
            context: Context,
            appId: Int,
        ): Boolean {
            val game = getEpicGameOf(appId) ?: return false

            if (game.isInstalled && game.installPath.isNotEmpty()) {
                return MarkerUtils.hasMarker(game.installPath, Marker.DOWNLOAD_COMPLETE_MARKER) &&
                    !MarkerUtils.hasMarker(game.installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            }

            val installPath =
                game.installPath.takeIf { it.isNotEmpty() }
                    ?: game.appName.takeIf { it.isNotEmpty() }?.let {
                        EpicConstants.getGameInstallPath(context, it)
                    }
                    ?: return false

            val isDownloadComplete = MarkerUtils.hasMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            val isDownloadInProgress = MarkerUtils.hasMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            if (isDownloadComplete && !isDownloadInProgress) {
                val updatedGame =
                    game.copy(
                        isInstalled = true,
                        installPath = installPath,
                    )
                runBlocking(Dispatchers.IO) {
                    getInstance()?.epicManager?.updateGame(updatedGame)
                }
                return true
            }

            return false
        }

        fun getInstallPath(appId: Int): String? {
            val game = getEpicGameOf(appId)
            return if (game?.isInstalled == true && game.installPath.isNotEmpty()) {
                game.installPath
            } else {
                null
            }
        }

        suspend fun getInstalledExe(appId: Int): String = getInstance()?.epicManager?.getInstalledExe(appId) ?: ""

        /**
         * Resolves the effective launch executable for an Epic game.
         * Container id is expected to be "EPIC_&lt;numericId&gt;" (from library). Returns empty if
         * game is not installed, no executable can be found, or containerId cannot be parsed.
         */
        suspend fun getLaunchExecutable(containerId: String): String {
            val gameId =
                try {
                    ContainerUtils.extractGameIdFromContainerId(containerId)
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Failed to parse Epic containerId: $containerId")
                    return ""
                }
            return getInstance()?.epicManager?.getLaunchExecutable(gameId) ?: ""
        }

        suspend fun refreshLibrary(context: Context): Result<Int> =
            getInstance()?.epicManager?.refreshLibrary(context)
                ?: Result.failure(Exception("Service not available"))

        suspend fun fetchManifestSizes(
            context: Context,
            appId: Int,
        ): EpicManager.ManifestSizes =
            getInstance()?.epicManager?.fetchManifestSizes(context, appId)
                ?: EpicManager.ManifestSizes(installSize = 0L, downloadSize = 0L)

        suspend fun checkForGameUpdate(
            context: Context,
            appId: Int,
        ): EpicUpdateInfo {
            val instance = getInstance()
                ?: return EpicUpdateInfo(message = "Service not available")
            val game = instance.epicManager.getGameById(appId)
                ?: return EpicUpdateInfo(message = "Game not found: $appId")
            val installPath =
                game.installPath.ifEmpty {
                    EpicConstants.getGameInstallPath(context, game.appName)
                }
            return instance.epicUpdateManager.checkForGameUpdate(
                context = context,
                game = game,
                installPath = installPath,
                containerLanguage = PrefManager.containerLanguage,
            )
        }

        fun downloadGame(
            context: Context,
            appId: Int,
            dlcGameIds: List<Int>,
            installPath: String,
            containerLanguage: String,
        ): Result<DownloadInfo> {
            val instance = getInstance() ?: return Result.failure(Exception("Service not available"))

            val game =
                runBlocking { instance.epicManager.getGameById(appId) }
                    ?: return Result.failure(Exception("Game not found for appId: $appId"))
            val gameId = game.id ?: return Result.failure(Exception("Game ID not found for appId: $appId"))
            val effectiveInstallPath =
                if (installPath.isNotEmpty()) {
                    installPath
                } else {
                    EpicConstants.getGameInstallPath(
                        context,
                        game.appName,
                    )
                }

            // Persist the chosen install path BEFORE the download starts so cancel/pause/resume
            // can find the partial files even when the user picked a non-default path.
            if (game.installPath != effectiveInstallPath) {
                runBlocking(Dispatchers.IO) {
                    instance.epicManager.updateGame(game.copy(installPath = effectiveInstallPath))
                }
            }

            val existingDownload = instance.activeDownloads[appId]
            if (existingDownload != null) {
                if (existingDownload.isActive()) {
                    Timber.tag("Epic").w("Download already in progress for $appId")
                    return Result.success(existingDownload)
                }
                instance.activeDownloads.remove(appId)
            }

            // Create DownloadInfo before launching coroutine to avoid race condition
            val downloadInfo =
                DownloadInfo(
                    jobCount = 1,
                    gameId = appId,
                    downloadingAppIds = CopyOnWriteArrayList<Int>(),
                )

            // Stash the original parameters so resume() can restore them after pause.
            instance.downloadParams[appId] =
                DownloadParams(
                    dlcGameIds = dlcGameIds,
                    containerLanguage = containerLanguage,
                    installPath = effectiveInstallPath,
                )

            instance.activeDownloads[appId] = downloadInfo

            // Pre-seed from the persisted record so Resume doesn't flash 0% during re-verify.
            val priorRecord =
                runBlocking {
                    DownloadCoordinator.findRecord(DownloadRecord.STORE_EPIC, appId.toString())
                }
            if (priorRecord != null && priorRecord.bytesTotal > 0L) {
                downloadInfo.setTotalExpectedBytes(priorRecord.bytesTotal)
                downloadInfo.setDisplayTotalExpectedBytes(priorRecord.bytesTotal)
                downloadInfo.initializeBytesDownloaded(priorRecord.bytesDownloaded)
            }

            // Ask the global coordinator whether we can start now or must wait. The coordinator
            // persists a DownloadRecord either way, so the download survives an app restart.
            val decision =
                runBlocking {
                    DownloadCoordinator.requestSlot(
                        store = DownloadRecord.STORE_EPIC,
                        storeGameId = appId.toString(),
                        title = game.title,
                        artUrl = game.iconUrl,
                        installPath = effectiveInstallPath,
                        selectedDlcs = dlcGameIds.joinToString(","),
                        language = containerLanguage,
                    )
                }
            when (decision) {
                is DownloadCoordinator.Decision.Queue -> {
                    // No slot available right now. Show the entry as queued; the coordinator
                    // will call back via the dispatcher when a slot frees up.
                    downloadInfo.setActive(false)
                    downloadInfo.isCancelling = false
                    downloadInfo.updateStatus(DownloadPhase.QUEUED, "Queued...")
                    PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, true))
                    return Result.success(downloadInfo)
                }
                is DownloadCoordinator.Decision.Start -> {
                    // Fall through to launch the coroutine immediately.
                }
            }

            downloadInfo.setActive(true)
            downloadInfo.isCancelling = false
            downloadInfo.updateStatus(DownloadPhase.DOWNLOADING)

            val job =
                instance.scope.launch {
                    val keepAliveTag = "epic-download-$appId"
                    val keepAliveCtx = instance.applicationContext
                    runCatching {
                        SessionKeepAliveService.startDownload(keepAliveCtx, keepAliveTag)
                    }.onFailure { e ->
                        Timber.w(e, "Failed to acquire keep-alive for Epic download $appId")
                    }
                    try {
                        val commonRedistDir = File(effectiveInstallPath, "_CommonRedist")
                        Timber.tag("Epic").i("Starting download for game: ${game.title}, gameId: ${game.id}")

                        val result =
                            instance.epicDownloadManager.downloadGame(
                                context,
                                game,
                                effectiveInstallPath,
                                downloadInfo,
                                containerLanguage,
                                dlcGameIds,
                                commonRedistDir,
                            )

                        Timber
                            .tag(
                                "Epic",
                            ).d("Download result: ${if (result.isSuccess) "SUCCESS" else "FAILURE: ${result.exceptionOrNull()?.message}"}")

                        if (result.isSuccess) {
                            Timber.i("[Download] Completed successfully for game $gameId")
                            downloadInfo.setProgress(1.0f)
                            downloadInfo.setActive(false)
                            downloadInfo.updateStatus(DownloadPhase.COMPLETE)

                            SnackbarManager.show("Download completed successfully!")
                        } else {
                            val error = result.exceptionOrNull()
                            when {
                                downloadInfo.isCancelling -> {
                                    Timber.i("[Download] Cancelled for game $gameId")
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(DownloadPhase.CANCELLED)
                                }

                                downloadInfo.getStatusFlow().value == DownloadPhase.FAILED -> {
                                    Timber.e(error, "[Download] Failed for game $gameId")
                                    downloadInfo.setActive(false)
                                    SnackbarManager.show("Download failed: ${error?.message ?: "Unknown error"}")
                                }

                                !downloadInfo.isActive() -> {
                                    Timber.i("[Download] Paused for game $gameId")
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(DownloadPhase.PAUSED)
                                }

                                else -> {
                                    Timber.e(error, "[Download] Failed for game $gameId")
                                    downloadInfo.setProgress(-1.0f)
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(DownloadPhase.FAILED, error?.message ?: "Unknown error")
                                    SnackbarManager.show("Download failed: ${error?.message ?: "Unknown error"}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        when {
                            downloadInfo.isCancelling -> {
                                Timber.i("[Download] Cancelled for game $gameId")
                                downloadInfo.setActive(false)
                                downloadInfo.updateStatus(DownloadPhase.CANCELLED)
                            }

                            downloadInfo.getStatusFlow().value == DownloadPhase.FAILED -> {
                                Timber.e(e, "[Download] Exception for game $gameId")
                                downloadInfo.setActive(false)
                                SnackbarManager.show("Download error: ${e.message ?: "Unknown error"}")
                            }

                            !downloadInfo.isActive() -> {
                                Timber.i("[Download] Paused for game $gameId")
                                downloadInfo.setActive(false)
                                downloadInfo.updateStatus(DownloadPhase.PAUSED)
                            }

                            else -> {
                                Timber.e(e, "[Download] Exception for game $gameId")
                                downloadInfo.setProgress(-1.0f)
                                downloadInfo.setActive(false)
                                downloadInfo.updateStatus(DownloadPhase.FAILED, e.message ?: "Unknown error")
                                SnackbarManager.show("Download error: ${e.message ?: "Unknown error"}")
                            }
                        }
                    } finally {
                        // Notify coordinator of the terminal status so the global queue can
                        // advance and the persisted DownloadRecord stays in sync.
                        updateCoordinatorDownloadProgress(appId, downloadInfo)
                        val finalCoordStatus =
                            when (downloadInfo.getStatusFlow().value) {
                                DownloadPhase.COMPLETE -> DownloadRecord.STATUS_COMPLETE
                                DownloadPhase.PAUSED -> DownloadRecord.STATUS_PAUSED
                                DownloadPhase.CANCELLED -> DownloadRecord.STATUS_CANCELLED
                                DownloadPhase.FAILED -> DownloadRecord.STATUS_FAILED
                                else -> DownloadRecord.STATUS_FAILED
                            }
                        DownloadCoordinator.notifyFinished(
                            DownloadRecord.STORE_EPIC,
                            appId.toString(),
                            finalCoordStatus,
                        )
                        // Keep COMPLETE and FAILED entries in the map so the Downloads tab can
                        // show them until the user clicks Clear.
                        PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, false))
                        Timber.d(
                            "[Download] Finished for game $gameId, progress: ${downloadInfo.getProgress()}, active: ${downloadInfo.isActive()}",
                        )
                        runCatching {
                            SessionKeepAliveService.stopDownload(keepAliveCtx, keepAliveTag)
                        }.onFailure { e ->
                            Timber.w(e, "Failed to release keep-alive for Epic download $appId")
                        }
                    }
                }
            downloadInfo.setDownloadJob(job)

            // Return the DownloadInfo immediately so caller can track progress
            return Result.success(downloadInfo)
        }

        fun verifyGameFiles(
            context: Context,
            appId: Int,
        ): DownloadInfo? {
            val instance = getInstance() ?: return null

            val game =
                runBlocking(Dispatchers.IO) { instance.epicManager.getGameById(appId) }
                    ?: return null
            val installPath =
                game.installPath.ifEmpty {
                    EpicConstants.getGameInstallPath(context, game.appName)
                }
            if (installPath.isEmpty() || !File(installPath).isDirectory) {
                return null
            }

            val activeCoordinatorRecords =
                DownloadCoordinator.snapshotRecords()
                    .filter {
                        it.status == DownloadRecord.STATUS_DOWNLOADING ||
                            it.status == DownloadRecord.STATUS_QUEUED
                    }
            if (activeCoordinatorRecords.any {
                    it.store != DownloadRecord.STORE_EPIC || it.storeGameId != appId.toString()
                }
            ) {
                return null
            }

            val existingDownload = instance.activeDownloads[appId]
            if (existingDownload?.isActive() == true) {
                return null
            }
            instance.activeDownloads.remove(appId)

            val downloadInfo =
                DownloadInfo(
                    jobCount = 1,
                    gameId = appId,
                    downloadingAppIds = CopyOnWriteArrayList<Int>(),
                )
            instance.activeDownloads[appId] = downloadInfo

            val decision =
                runBlocking {
                    DownloadCoordinator.requestSlot(
                        store = DownloadRecord.STORE_EPIC,
                        storeGameId = appId.toString(),
                        title = game.title,
                        artUrl = game.iconUrl,
                        installPath = installPath,
                        language = PrefManager.containerLanguage,
                        taskType = DownloadRecord.TASK_VERIFY,
                    )
                }

            if (decision is DownloadCoordinator.Decision.Queue) {
                downloadInfo.setActive(false)
                downloadInfo.isCancelling = false
                downloadInfo.updateStatus(DownloadPhase.QUEUED, "Queued...")
                PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, true))
                return downloadInfo
            }

            downloadInfo.setActive(true)
            downloadInfo.isCancelling = false
            downloadInfo.updateStatus(DownloadPhase.VERIFYING)

            val job =
                instance.scope.launch {
                    val keepAliveTag = "epic-verify-$appId"
                    val keepAliveCtx = instance.applicationContext
                    runCatching {
                        SessionKeepAliveService.startDownload(keepAliveCtx, keepAliveTag)
                    }.onFailure { e ->
                        Timber.w(e, "Failed to acquire keep-alive for Epic verify $appId")
                    }
                    try {
                        val result =
                            instance.epicVerifyManager.verifyGameFiles(
                                context = context,
                                game = game,
                                installPath = installPath,
                                downloadInfo = downloadInfo,
                                containerLanguage = PrefManager.containerLanguage,
                            )

                        if (result.isSuccess) {
                            downloadInfo.setProgress(1.0f)
                            downloadInfo.setActive(false)
                            downloadInfo.updateStatus(DownloadPhase.COMPLETE)
                            SnackbarManager.show("Verify files complete")
                        } else {
                            val error = result.exceptionOrNull()
                            when {
                                downloadInfo.isCancelling -> {
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(DownloadPhase.CANCELLED)
                                }
                                downloadInfo.getStatusFlow().value == DownloadPhase.FAILED -> {
                                    Timber.e(error, "[Verify] Failed for Epic game $appId")
                                    downloadInfo.setActive(false)
                                    SnackbarManager.show("Verify files failed: ${error?.message ?: "Unknown error"}")
                                }
                                !downloadInfo.isActive() -> {
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(DownloadPhase.PAUSED)
                                }
                                else -> {
                                    Timber.e(error, "[Verify] Failed for Epic game $appId")
                                    downloadInfo.setProgress(-1.0f)
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(DownloadPhase.FAILED, error?.message ?: "Unknown error")
                                    SnackbarManager.show("Verify files failed: ${error?.message ?: "Unknown error"}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        when {
                            downloadInfo.isCancelling -> {
                                downloadInfo.setActive(false)
                                downloadInfo.updateStatus(DownloadPhase.CANCELLED)
                            }
                            downloadInfo.getStatusFlow().value == DownloadPhase.FAILED -> {
                                Timber.e(e, "[Verify] Exception for Epic game $appId")
                                downloadInfo.setActive(false)
                                SnackbarManager.show("Verify files failed: ${e.message ?: "Unknown error"}")
                            }
                            !downloadInfo.isActive() -> {
                                downloadInfo.setActive(false)
                                downloadInfo.updateStatus(DownloadPhase.PAUSED)
                            }
                            else -> {
                                Timber.e(e, "[Verify] Exception for Epic game $appId")
                                downloadInfo.setProgress(-1.0f)
                                downloadInfo.setActive(false)
                                downloadInfo.updateStatus(DownloadPhase.FAILED, e.message ?: "Unknown error")
                                SnackbarManager.show("Verify files failed: ${e.message ?: "Unknown error"}")
                            }
                        }
                    } finally {
                        updateCoordinatorDownloadProgress(appId, downloadInfo)
                        val finalCoordStatus =
                            when (downloadInfo.getStatusFlow().value) {
                                DownloadPhase.COMPLETE -> DownloadRecord.STATUS_COMPLETE
                                DownloadPhase.PAUSED -> DownloadRecord.STATUS_PAUSED
                                DownloadPhase.CANCELLED -> DownloadRecord.STATUS_CANCELLED
                                DownloadPhase.FAILED -> DownloadRecord.STATUS_FAILED
                                else -> DownloadRecord.STATUS_FAILED
                            }
                        DownloadCoordinator.notifyFinished(
                            DownloadRecord.STORE_EPIC,
                            appId.toString(),
                            finalCoordStatus,
                        )
                        PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, false))
                        runCatching {
                            SessionKeepAliveService.stopDownload(keepAliveCtx, keepAliveTag)
                        }.onFailure { e ->
                            Timber.w(e, "Failed to release keep-alive for Epic verify $appId")
                        }
                    }
                }
            downloadInfo.setDownloadJob(job)
            return downloadInfo
        }

        fun updateGameFiles(
            context: Context,
            appId: Int,
        ): DownloadInfo? {
            val instance = getInstance() ?: return null

            val game =
                runBlocking(Dispatchers.IO) { instance.epicManager.getGameById(appId) }
                    ?: return null
            val installPath =
                game.installPath.ifEmpty {
                    EpicConstants.getGameInstallPath(context, game.appName)
                }
            if (installPath.isEmpty() || !File(installPath).isDirectory) {
                return null
            }

            val activeCoordinatorRecords =
                DownloadCoordinator.snapshotRecords()
                    .filter {
                        it.status == DownloadRecord.STATUS_DOWNLOADING ||
                            it.status == DownloadRecord.STATUS_QUEUED
                    }
            if (activeCoordinatorRecords.any {
                    it.store != DownloadRecord.STORE_EPIC || it.storeGameId != appId.toString()
                }
            ) {
                return null
            }

            val existingDownload = instance.activeDownloads[appId]
            if (existingDownload?.isActive() == true) {
                return null
            }
            instance.activeDownloads.remove(appId)

            val downloadInfo =
                DownloadInfo(
                    jobCount = 1,
                    gameId = appId,
                    downloadingAppIds = CopyOnWriteArrayList<Int>(),
                )
            instance.activeDownloads[appId] = downloadInfo

            val decision =
                runBlocking {
                    DownloadCoordinator.requestSlot(
                        store = DownloadRecord.STORE_EPIC,
                        storeGameId = appId.toString(),
                        title = game.title,
                        artUrl = game.iconUrl,
                        installPath = installPath,
                        language = PrefManager.containerLanguage,
                        taskType = DownloadRecord.TASK_UPDATE,
                    )
                }

            if (decision is DownloadCoordinator.Decision.Queue) {
                downloadInfo.setActive(false)
                downloadInfo.isCancelling = false
                downloadInfo.updateStatus(DownloadPhase.QUEUED, "Queued...")
                PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, true))
                return downloadInfo
            }

            downloadInfo.setActive(true)
            downloadInfo.isCancelling = false
            downloadInfo.updateStatus(DownloadPhase.DOWNLOADING)

            val job =
                instance.scope.launch {
                    val keepAliveTag = "epic-update-$appId"
                    val keepAliveCtx = instance.applicationContext
                    runCatching {
                        SessionKeepAliveService.startDownload(keepAliveCtx, keepAliveTag)
                    }.onFailure { e ->
                        Timber.w(e, "Failed to acquire keep-alive for Epic update $appId")
                    }
                    try {
                        val result =
                            instance.epicUpdateManager.updateGameFiles(
                                context = context,
                                game = game,
                                installPath = installPath,
                                downloadInfo = downloadInfo,
                                containerLanguage = PrefManager.containerLanguage,
                            )

                        if (result.isSuccess) {
                            downloadInfo.setProgress(1.0f)
                            downloadInfo.setActive(false)
                            downloadInfo.updateStatus(DownloadPhase.COMPLETE)
                            SnackbarManager.show("Update complete")
                        } else {
                            val error = result.exceptionOrNull()
                            when {
                                downloadInfo.isCancelling -> {
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(DownloadPhase.CANCELLED)
                                }
                                downloadInfo.getStatusFlow().value == DownloadPhase.FAILED -> {
                                    Timber.e(error, "[Update] Failed for Epic game $appId")
                                    downloadInfo.setActive(false)
                                    SnackbarManager.show("Update failed: ${error?.message ?: "Unknown error"}")
                                }
                                !downloadInfo.isActive() -> {
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(DownloadPhase.PAUSED)
                                }
                                else -> {
                                    Timber.e(error, "[Update] Failed for Epic game $appId")
                                    downloadInfo.setProgress(-1.0f)
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(DownloadPhase.FAILED, error?.message ?: "Unknown error")
                                    SnackbarManager.show("Update failed: ${error?.message ?: "Unknown error"}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        when {
                            downloadInfo.isCancelling -> {
                                downloadInfo.setActive(false)
                                downloadInfo.updateStatus(DownloadPhase.CANCELLED)
                            }
                            downloadInfo.getStatusFlow().value == DownloadPhase.FAILED -> {
                                Timber.e(e, "[Update] Exception for Epic game $appId")
                                downloadInfo.setActive(false)
                                SnackbarManager.show("Update failed: ${e.message ?: "Unknown error"}")
                            }
                            !downloadInfo.isActive() -> {
                                downloadInfo.setActive(false)
                                downloadInfo.updateStatus(DownloadPhase.PAUSED)
                            }
                            else -> {
                                Timber.e(e, "[Update] Exception for Epic game $appId")
                                downloadInfo.setProgress(-1.0f)
                                downloadInfo.setActive(false)
                                downloadInfo.updateStatus(DownloadPhase.FAILED, e.message ?: "Unknown error")
                                SnackbarManager.show("Update failed: ${e.message ?: "Unknown error"}")
                            }
                        }
                    } finally {
                        updateCoordinatorDownloadProgress(appId, downloadInfo)
                        val finalCoordStatus =
                            when (downloadInfo.getStatusFlow().value) {
                                DownloadPhase.COMPLETE -> DownloadRecord.STATUS_COMPLETE
                                DownloadPhase.PAUSED -> DownloadRecord.STATUS_PAUSED
                                DownloadPhase.CANCELLED -> DownloadRecord.STATUS_CANCELLED
                                DownloadPhase.FAILED -> DownloadRecord.STATUS_FAILED
                                else -> DownloadRecord.STATUS_FAILED
                            }
                        DownloadCoordinator.notifyFinished(
                            DownloadRecord.STORE_EPIC,
                            appId.toString(),
                            finalCoordStatus,
                        )
                        PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, false))
                        runCatching {
                            SessionKeepAliveService.stopDownload(keepAliveCtx, keepAliveTag)
                        }.onFailure { e ->
                            Timber.w(e, "Failed to release keep-alive for Epic update $appId")
                        }
                    }
                }
            downloadInfo.setDownloadJob(job)
            return downloadInfo
        }

        private fun updateCoordinatorDownloadProgress(
            appId: Int,
            downloadInfo: DownloadInfo,
        ) {
            val (displayDownloadedBytes, displayTotalBytes) = downloadInfo.getDisplayBytesProgress()
            val downloadedBytes =
                if (displayTotalBytes > 0L) {
                    displayDownloadedBytes
                } else {
                    downloadInfo.getBytesDownloaded()
                }
            val totalBytes =
                if (displayTotalBytes > 0L) {
                    displayTotalBytes
                } else {
                    downloadInfo.getTotalExpectedBytes()
                }
            DownloadCoordinator.updateProgress(
                DownloadRecord.STORE_EPIC,
                appId.toString(),
                downloadedBytes,
                totalBytes,
            )
        }

        suspend fun refreshSingleGame(
            appId: Int,
            context: Context,
        ): Result<EpicGame?> {
            val game = getInstance()?.epicManager?.getGameById(appId)
            return if (game != null) {
                Result.success(game)
            } else {
                Result.failure(Exception("Game not found: $appId"))
            }
        }


        suspend fun getGameLaunchToken(
            context: Context,
            namespace: String? = null,
            catalogItemId: String? = null,
            requiresOwnershipToken: Boolean = false,
        ): Result<EpicGameToken> = EpicAuthManager.getGameLaunchToken(context, namespace, catalogItemId, requiresOwnershipToken)

        suspend fun buildLaunchParameters(
            context: Context,
            game: EpicGame,
            offline: Boolean = false,
            languageCode: String = "en-US",
            container: com.winlator.cmod.runtime.container.Container? = null,
        ): Result<List<String>> = EpicGameLauncher.buildLaunchParameters(context, game, offline, languageCode, container)

        fun cleanupLaunchTokens(
            context: Context,
            container: com.winlator.cmod.runtime.container.Container? = null,
        ) {
            EpicGameLauncher.cleanupOwnershipTokens(context, container)
        }


        /**
         * Install the EOS overlay into [container]'s Wine prefix. Idempotent.
         *
         * Failure to install is non-fatal — call sites typically log and continue with the
         * launch. Most games still run fine without the overlay; only in-game friend
         * notifications / purchasing UI are missing.
         */
        suspend fun installOverlay(
            context: Context,
            container: com.winlator.cmod.runtime.container.Container,
            forceReinstall: Boolean = false,
            onProgress: ((Int, Int) -> Unit)? = null,
        ): Result<Unit> {
            val instance = getInstance()
                ?: return Result.failure(Exception("Epic service is not active"))
            return instance.epicOverlayManager.installOverlay(
                context = context,
                container = container,
                forceReinstall = forceReinstall,
                onProgress = onProgress,
            )
        }

        /**
         * True if the EOS overlay has been installed into [container]'s Wine prefix.
         * Returns false if the service is not running.
         */
        fun isOverlayInstalled(container: com.winlator.cmod.runtime.container.Container): Boolean =
            getInstance()?.epicOverlayManager?.isOverlayInstalled(container) ?: false

        /**
         * Remove the EOS overlay from [container]'s Wine prefix and clear its registry pointer.
         * Returns failure if the service is not running.
         */
        suspend fun removeOverlay(
            container: com.winlator.cmod.runtime.container.Container,
        ): Result<Unit> {
            val instance = getInstance()
                ?: return Result.failure(Exception("Epic service is not active"))
            return instance.epicOverlayManager.removeOverlay(container)
        }


        fun getAccountId(): String? {
            return try {
                val context = getInstance()?.applicationContext ?: return null
                val credentialsResult =
                    kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                        EpicAuthManager.getStoredCredentials(context)
                    }
                credentialsResult.getOrNull()?.accountId
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Failed to get account ID")
                null
            }
        }
    }

    // Original download parameters per appId so resume can restore DLC selection,
    // language, and install path instead of falling back to defaults.
    data class DownloadParams(
        val dlcGameIds: List<Int>,
        val containerLanguage: String,
        val installPath: String,
    )

    private val downloadParams = ConcurrentHashMap<Int, DownloadParams>()

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = { stop() }

    private val coordinatorDispatcher =
        object : DownloadCoordinator.Dispatcher {
            override fun startQueued(record: DownloadRecord) {
                val context = DownloadService.appContext ?: return
                val appId = record.storeGameId.toIntOrNull() ?: return
                val params = downloadParams[appId]
                val dlcGameIds =
                    params?.dlcGameIds
                        ?: record.selectedDlcs
                            .split(',')
                            .mapNotNull { it.trim().toIntOrNull() }
                val containerLanguage = params?.containerLanguage ?: record.language
                val installPath = params?.installPath ?: record.installPath

                // "already downloading" — it will recreate the DownloadInfo and launch.
                activeDownloads.remove(appId)

                if (record.taskType == DownloadRecord.TASK_UPDATE) {
                    updateGameFiles(context, appId)
                } else if (record.taskType == DownloadRecord.TASK_VERIFY) {
                    verifyGameFiles(context, appId)
                } else {
                    downloadGame(context, appId, dlcGameIds, installPath, containerLanguage)
                }
            }

            override fun pauseRunning(record: DownloadRecord) {
                val appId = record.storeGameId.toIntOrNull() ?: return
                val info = activeDownloads[appId] ?: return
                DownloadCoordinator.updateProgress(
                    DownloadRecord.STORE_EPIC,
                    appId.toString(),
                    info.getBytesDownloaded(),
                    info.getTotalExpectedBytes(),
                )
                if (info.isActive()) {
                    info.isCancelling = false
                    info.updateStatus(DownloadPhase.PAUSED)
                    info.cancel("Paused by user")
                } else {
                    info.updateStatus(DownloadPhase.PAUSED)
                    info.setActive(false)
                    PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, false))
                }
            }

            override fun cancelRunning(record: DownloadRecord) {
                val appId = record.storeGameId.toIntOrNull() ?: return
                val info = activeDownloads[appId]
                if (info != null) {
                    info.isCancelling = true
                    info.cancel("Cancelled by user")
                }
                CoroutineScope(Dispatchers.IO).launch {
                    info?.awaitCompletion(timeoutMs = 3000L)
                    val pathToDelete =
                        record.installPath.ifEmpty {
                            val game = epicManager.getGameById(appId)
                            game?.installPath?.ifEmpty {
                                EpicConstants.getGameInstallPath(applicationContext, game.appName)
                            } ?: ""
                        }
                    if (record.taskType == DownloadRecord.TASK_UPDATE || record.taskType == DownloadRecord.TASK_VERIFY) {
                        if (pathToDelete.isNotEmpty()) {
                            MarkerUtils.removeMarker(pathToDelete, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                            MarkerUtils.addMarker(pathToDelete, Marker.DOWNLOAD_COMPLETE_MARKER)
                        }
                        info?.updateStatus(DownloadPhase.CANCELLED)
                        PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, false))
                        return@launch
                    }
                    if (pathToDelete.isNotEmpty()) {
                        val dirFile = File(pathToDelete)
                        if (dirFile.exists() && dirFile.isDirectory) {
                            val deleteCheck =
                                StoreInstallPathSafety.checkInstallDirDelete(
                                    applicationContext,
                                    pathToDelete,
                                    protectedRoots = listOf(EpicConstants.defaultEpicGamesPath(applicationContext)),
                                )
                            if (deleteCheck.allowed) {
                                MarkerUtils.removeMarker(pathToDelete, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                                MarkerUtils.removeMarker(pathToDelete, Marker.DOWNLOAD_COMPLETE_MARKER)
                                dirFile.deleteRecursively()
                            } else {
                                Timber.tag("Epic").e("Refusing to delete cancelled Epic download path '$pathToDelete': ${deleteCheck.reason}")
                            }
                        }
                    }
                    info?.updateStatus(DownloadPhase.CANCELLED)
                    PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, false))
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Timber.tag("Epic").i("[EpicService] Service created")

//        notificationHelper = NotificationHelper(applicationContext)
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)

        DownloadCoordinator.registerDispatcher(DownloadRecord.STORE_EPIC, coordinatorDispatcher)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Timber.tag("EPIC").d("onStartCommand() - action: ${intent?.action}")

        SessionKeepAliveService.startComponent(this, SessionKeepAliveService.COMPONENT_EPIC, "Connected")

        val shouldSync =
            when (intent?.action) {
                ACTION_MANUAL_SYNC -> {
                    Timber.tag("EPIC").i("Manual sync requested - bypassing throttle")
                    true
                }

                ACTION_SYNC_LIBRARY -> {
                    Timber.tag("EPIC").i("Automatic sync requested")
                    true
                }

                null -> {
                    // START_STICKY restart: sync only if initial sync is missing or throttle elapsed.
                    val timeSinceLastSync = System.currentTimeMillis() - lastSyncTimestamp
                    val shouldResync = !hasPerformedInitialSync || timeSinceLastSync >= SYNC_THROTTLE_MILLIS

                    if (shouldResync) {
                        Timber
                            .tag(
                                "EPIC",
                            ).i(
                                "Service restarted by Android - performing sync (hasPerformedInitialSync=$hasPerformedInitialSync, timeSinceLastSync=${timeSinceLastSync}ms)",
                            )
                        true
                    } else {
                        Timber.tag("EPIC").d("Service restarted by Android - skipping sync (throttled)")
                        false
                    }
                }

                else -> {
                    Timber.tag("EPIC").d(" Service started without sync action")
                    false
                }
            }

        if (shouldSync && (backgroundSyncJob == null || backgroundSyncJob?.isActive != true)) {
            Timber.tag("EPIC").i("Starting background library sync")

            backgroundSyncJob?.cancel()
            backgroundSyncJob =
                scope.launch {
                    try {
                        setSyncInProgress(true)
                        Timber.tag("EPIC").d("Starting background library sync")
                        val syncResult = epicManager.startBackgroundSync(applicationContext)
                        if (syncResult.isFailure) {
                            Timber.w("Failed to start background sync: ${syncResult.exceptionOrNull()?.message}")
                        } else {
                            Timber.tag("EPIC").i("Background library sync completed successfully")
                            lastSyncTimestamp = System.currentTimeMillis()
                            hasPerformedInitialSync = true
                            performInitialCloudSaveCheck(applicationContext)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Exception starting background sync")
                    } finally {
                        setSyncInProgress(false)
                    }
                }
        } else if (shouldSync) {
            Timber.tag("EPIC").d("Background sync already in progress, skipping")
        } else if (!hasPerformedInitialCloudSaveCheck) {
            scope.launch {
                performInitialCloudSaveCheck(applicationContext)
            }
        }

        return START_STICKY
    }

    private suspend fun performInitialCloudSaveCheck(context: Context) {
        if (hasPerformedInitialCloudSaveCheck) return
        hasPerformedInitialCloudSaveCheck = true

        val games =
            try {
                epicManager
                    .getAllGames()
                    .filter { it.isInstalled && it.cloudSaveEnabled && !it.isDLC }
            } catch (e: Exception) {
                Timber.tag("Epic").w(e, "[Cloud Saves] Startup check could not load installed Epic games")
                return
            }
        if (games.isEmpty()) return

        val shortcuts: List<com.winlator.cmod.runtime.container.Shortcut> =
            runCatching<List<com.winlator.cmod.runtime.container.Shortcut>> {
                com.winlator.cmod.runtime.container.ContainerManager(context).loadShortcuts()
            }.getOrElse {
                Timber.tag("Epic").w(it, "[Cloud Saves] Startup check could not load shortcuts; using default cloud-sync state")
                emptyList()
            }
        val shortcutsByAppId =
            shortcuts
                .filter { it.getExtra("game_source") == "EPIC" }
                .associateBy { it.getExtra("app_id") }

        Timber.tag("Epic").i("[Cloud Saves] Startup checking ${games.size} installed Epic cloud-save title(s)")
        games.forEach { game ->
            val shortcut = shortcutsByAppId[game.id.toString()]
            if (shortcut != null &&
                (shortcut.getExtra("cloud_sync_disabled", "0") == "1" ||
                    shortcut.getExtra("offline_mode", "0") == "1")
            ) {
                Timber.tag("Epic").d("[Cloud Saves] Startup check skipped for ${game.title}: shortcut cloud sync disabled")
                return@forEach
            }

            runCatching {
                EpicCloudSavesManager.restoreCloudSavesIfLocalMissing(context, game.id)
            }.onFailure {
                Timber.tag("Epic").w(it, "[Cloud Saves] Startup restore failed for ${game.title}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag("Epic").i("[EpicService] Service destroyed")
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)
        DownloadCoordinator.unregisterDispatcher(DownloadRecord.STORE_EPIC)

        backgroundSyncJob?.cancel()
        setSyncInProgress(false)
        hasPerformedInitialCloudSaveCheck = false

        // Safety net for service/process teardown: persist the latest visible
        // progress into the coordinator before cancelling workers.
        activeDownloads.forEach { (appId, info) ->
            val (displayDownloadedBytes, displayTotalBytes) = info.getDisplayBytesProgress()
            DownloadCoordinator.updateProgress(
                DownloadRecord.STORE_EPIC,
                appId.toString(),
                if (displayTotalBytes > 0L) displayDownloadedBytes else info.getBytesDownloaded(),
                if (displayTotalBytes > 0L) displayTotalBytes else info.getTotalExpectedBytes(),
            )
        }

        scope.cancel()
        SessionKeepAliveService.stopComponent(this, SessionKeepAliveService.COMPONENT_EPIC)
        instance = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Timber.tag("EPIC").i("Task removed; stopping managed app services")
        SessionKeepAliveService.stopComponent(this, SessionKeepAliveService.COMPONENT_EPIC)
        AppTerminationHelper.stopManagedServices(applicationContext, "epic_task_removed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
