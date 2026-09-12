package com.winlator.cmod.feature.stores.gog.service
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.app.db.download.DownloadRecord
import com.winlator.cmod.app.service.download.DownloadCoordinator
import com.winlator.cmod.feature.stores.epic.ui.util.SnackbarManager
import com.winlator.cmod.feature.stores.common.StoreInstallPathSafety
import com.winlator.cmod.feature.stores.gog.data.GOGCredentials
import com.winlator.cmod.feature.stores.gog.data.GOGGame
import com.winlator.cmod.feature.stores.gog.data.LibraryItem
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.data.LaunchInfo
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.feature.stores.steam.enums.Marker
import com.winlator.cmod.feature.stores.steam.events.AndroidEvent
import com.winlator.cmod.feature.stores.steam.utils.ContainerUtils
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils
import com.winlator.cmod.feature.sync.google.GameSaveBackupManager.BackupResult
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.system.SessionKeepAliveService
import com.winlator.cmod.shared.android.AppTerminationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipOutputStream
import javax.inject.Inject

// Service facade for GOG auth, library sync, downloads, and cloud saves.
@AndroidEntryPoint
class GOGService : Service() {

    /*private lateinit var notificationHelper: NotificationHelper
    var notificationID = 1*/

    @Inject
    lateinit var gogManager: GOGManager

    @Inject
    lateinit var gogDownloadManager: GOGDownloadManager

    @Inject
    lateinit var gogVerifyManager: GOGVerifyManager

    @Inject
    lateinit var gogUpdateManager: GOGUpdateManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()

    // Download parameters per gameId so resume can restore container language and
    // install path instead of falling back to defaults.
    data class DownloadParams(
        val dlcGameIds: List<Int>,
        val containerLanguage: String,
        val installPath: String,
    )

    private val downloadParams = ConcurrentHashMap<String, DownloadParams>()

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = { stop() }

    companion object {
        private const val ACTION_SYNC_LIBRARY = "com.winlator.cmod.GOG_SYNC_LIBRARY"
        private const val ACTION_MANUAL_SYNC = "com.winlator.cmod.GOG_MANUAL_SYNC"
        private const val SYNC_THROTTLE_MILLIS = 15 * 60 * 1000L // 15 minutes

        private var instance: GOGService? = null

        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null
        private var lastSyncTimestamp: Long = 0L
        private var hasPerformedInitialSync: Boolean = false

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {
            if (isRunning) {
                Timber.d("[GOGService] Service already running, skipping start")
                return
            }

            if (!hasPerformedInitialSync) {
                Timber.i("[GOGService] First-time start - starting service with initial sync")
                val intent = Intent(context, GOGService::class.java)
                intent.action = ACTION_SYNC_LIBRARY

                startGOGService(context, intent)
                return
            }

            val now = System.currentTimeMillis()
            val timeSinceLastSync = now - lastSyncTimestamp

            val intent = Intent(context, GOGService::class.java)
            if (timeSinceLastSync >= SYNC_THROTTLE_MILLIS) {
                Timber.i("[GOGService] Starting service with automatic sync (throttle passed)")
                intent.action = ACTION_SYNC_LIBRARY
            } else {
                val remainingMinutes = (SYNC_THROTTLE_MILLIS - timeSinceLastSync) / 1000 / 60
                Timber.d("[GOGService] Starting service without sync - throttled (${remainingMinutes}min remaining)")
            }

            startGOGService(context, intent)
        }

        fun triggerLibrarySync(context: Context) {
            Timber.i("[GOGService] Triggering manual library sync (bypasses throttle)")
            val intent = Intent(context, GOGService::class.java)
            intent.action = ACTION_MANUAL_SYNC
            startGOGService(context, intent)
        }

        fun startGOGService(context: Context, intent: Intent) {
            try {
                // Just start as a normal service. KeepAliveService should protect this.
                context.startService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start GOGService")
            }
        }

        fun stop() {
            instance?.let { service ->
                runCatching {
                    SessionKeepAliveService.stopComponent(service, SessionKeepAliveService.COMPONENT_GOG)
                }.onFailure { Timber.w(it, "Failed to remove GOGService foreground state during shutdown") }
                /*runCatching {
                    if (service::notificationHelper.isInitialized)
                        service.notificationHelper.cancel(service.notificationID)
                }.onFailure { Timber.w(it, "Failed to cancel GOGService notification during shutdown") }*/
                service.stopSelf()
            }
        }


        suspend fun authenticateWithCode(
            context: Context,
            authorizationCode: String,
        ): Result<GOGCredentials> = GOGAuthManager.authenticateWithCode(context, authorizationCode)

        fun hasStoredCredentials(context: Context): Boolean = GOGAuthManager.hasStoredCredentials(context)

        suspend fun getStoredCredentials(context: Context): Result<GOGCredentials> = GOGAuthManager.getStoredCredentials(context)

        suspend fun getResolvedSaveDirectories(
            context: Context,
            appId: String,
        ): List<File> = getResolvedSaveDirectories(context, appId, null)

        suspend fun getResolvedSaveDirectories(
            context: Context,
            appId: String,
            targetContainerId: Int?,
        ): List<File> {
            val activeInstance = getInstance() ?: return emptyList()
            val gameId = ContainerUtils.extractGameIdFromContainerId(appId).toString()
            val game = activeInstance.gogManager.getGameFromDbById(gameId) ?: return emptyList()
            return activeInstance.gogManager
                .getSaveDirectoryPath(context, appId, game.title, targetContainerId)
                ?.map { File(it.location) }
                ?.filter { it.exists() || !it.path.isNullOrEmpty() }
                ?: emptyList()
        }

        suspend fun hasActualLocalCloudSaves(
            context: Context,
            appId: String,
        ): Boolean = hasActualLocalCloudSaves(context, appId, null)

        suspend fun hasActualLocalCloudSaves(
            context: Context,
            appId: String,
            targetContainerId: Int?,
        ): Boolean =
            getResolvedSaveDirectories(context, appId, targetContainerId).any { dir ->
                dir.exists() && dir.walkTopDown().any { it.isFile }
            }

        // Non-suspend so the Java exit-path (XServerDisplayActivity) can call it directly.
        @JvmStatic
        @JvmOverloads
        fun canAttemptExitUpload(
            context: Context,
            appId: String,
            targetContainerId: Int? = null,
        ): Boolean =
            runBlocking(Dispatchers.IO) {
                try {
                    val activeInstance = getInstance() ?: return@runBlocking false
                    if (!GOGAuthManager.hasStoredCredentials(context)) {
                        Timber.tag("GOG").i("[Cloud Saves] Skip exit upload: not signed in to GOG")
                        return@runBlocking false
                    }
                    val normalizedAppId = if (appId.startsWith("GOG_", ignoreCase = true)) appId else "GOG_$appId"
                    val gameId = ContainerUtils.extractGameIdFromContainerId(normalizedAppId).toString()
                    val game = activeInstance.gogManager.getGameFromDbById(gameId) ?: run {
                        Timber.tag("GOG").i("[Cloud Saves] Skip exit upload: game $appId not in DB")
                        return@runBlocking false
                    }
                    val saveLocations =
                        activeInstance.gogManager.getSaveDirectoryPath(context, normalizedAppId, game.title, targetContainerId)
                    if (saveLocations.isNullOrEmpty()) {
                        Timber.tag("GOG").i("[Cloud Saves] Skip exit upload: no cloud-save locations for ${game.title}")
                        return@runBlocking false
                    }
                    val hasLocalFile =
                        saveLocations.any { loc ->
                            val dir = File(loc.location)
                            dir.exists() && dir.walkTopDown().any { it.isFile }
                        }
                    if (!hasLocalFile) {
                        Timber.tag("GOG").i("[Cloud Saves] Skip exit upload: save directory empty for ${game.title}")
                        return@runBlocking false
                    }
                    true
                } catch (e: Exception) {
                    Timber.tag("GOG").w(e, "[Cloud Saves] canAttemptExitUpload threw, skipping")
                    false
                }
            }

        suspend fun getPendingSyncAction(
            context: Context,
            appId: String,
            targetContainerId: Int? = null,
        ): GOGCloudSavesManager.SyncAction =
            determinePendingAction(context, appId, "auto", targetContainerId)

        suspend fun getPendingExitSyncAction(
            context: Context,
            appId: String,
            targetContainerId: Int? = null,
        ): GOGCloudSavesManager.SyncAction =
            determinePendingAction(context, appId, "exit_upload", targetContainerId)

        // Conflict-wins precedence across multiple save locations.
        private suspend fun determinePendingAction(
            context: Context,
            appId: String,
            preferredAction: String,
            targetContainerId: Int?,
        ): GOGCloudSavesManager.SyncAction =
            withContext(Dispatchers.IO) {
                try {
                    val activeInstance = getInstance() ?: return@withContext GOGCloudSavesManager.SyncAction.NONE
                    if (!GOGAuthManager.hasStoredCredentials(context)) return@withContext GOGCloudSavesManager.SyncAction.NONE
                    val normalizedAppId = if (appId.startsWith("GOG_", ignoreCase = true)) appId else "GOG_$appId"
                    val gameId = ContainerUtils.extractGameIdFromContainerId(normalizedAppId).toString()
                    val game =
                        activeInstance.gogManager.getGameFromDbById(gameId)
                            ?: return@withContext GOGCloudSavesManager.SyncAction.NONE
                    val saveLocations =
                        activeInstance.gogManager.getSaveDirectoryPath(context, normalizedAppId, game.title, targetContainerId)
                            ?: return@withContext GOGCloudSavesManager.SyncAction.NONE
                    val cloudSavesManager = GOGCloudSavesManager(context)
                    val perLocationActions =
                        saveLocations.map { location ->
                            val timestamp =
                                activeInstance.gogManager
                                    .getCloudSaveSyncTimestamp(normalizedAppId, location.name)
                                    .toLongOrNull()
                                    ?: 0L
                            cloudSavesManager.determineSyncAction(
                                localPath = location.location,
                                dirname = location.name,
                                clientId = location.clientId,
                                clientSecret = location.clientSecret,
                                lastSyncTimestamp = timestamp,
                                preferredAction = preferredAction,
                            )
                        }
                    when {
                        perLocationActions.any { it == GOGCloudSavesManager.SyncAction.CONFLICT } ->
                            GOGCloudSavesManager.SyncAction.CONFLICT
                        perLocationActions.any { it == GOGCloudSavesManager.SyncAction.UPLOAD } ->
                            GOGCloudSavesManager.SyncAction.UPLOAD
                        perLocationActions.any { it == GOGCloudSavesManager.SyncAction.DOWNLOAD } ->
                            GOGCloudSavesManager.SyncAction.DOWNLOAD
                        else -> GOGCloudSavesManager.SyncAction.NONE
                    }
                } catch (e: Exception) {
                    Timber.tag("GOG").w(e, "[Cloud Saves] determinePendingAction failed for $appId")
                    GOGCloudSavesManager.SyncAction.NONE
                }
            }

        suspend fun cloudSavesDiffer(
            context: Context,
            appId: String,
        ): Boolean? = cloudSavesDiffer(context, appId, null)

        suspend fun cloudSavesDiffer(
            context: Context,
            appId: String,
            targetContainerId: Int?,
        ): Boolean? =
            withContext(Dispatchers.IO) {
                try {
                    val activeInstance = getInstance() ?: return@withContext null
                    if (!GOGAuthManager.hasStoredCredentials(context)) return@withContext false
                    val normalizedAppId = if (appId.startsWith("GOG_", ignoreCase = true)) appId else "GOG_$appId"
                    val gameId = ContainerUtils.extractGameIdFromContainerId(normalizedAppId).toString()
                    val game = activeInstance.gogManager.getGameFromDbById(gameId) ?: return@withContext false
                    val saveLocations =
                        activeInstance.gogManager.getSaveDirectoryPath(context, normalizedAppId, game.title, targetContainerId)
                            ?: return@withContext false
                    val cloudSavesManager = GOGCloudSavesManager(context)
                    saveLocations.any { location ->
                        val timestamp =
                            activeInstance.gogManager
                                .getCloudSaveSyncTimestamp(normalizedAppId, location.name)
                                .toLongOrNull()
                                ?: 0L
                        cloudSavesManager.needsSync(
                            localPath = location.location,
                            dirname = location.name,
                            clientId = location.clientId,
                            clientSecret = location.clientSecret,
                            lastSyncTimestamp = timestamp,
                        )
                    }
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "[Cloud Saves] Failed to probe cloud save diff for $appId")
                    null
                }
            }

        suspend fun getNewestCloudSaveSyncTimestamp(
            context: Context,
            appId: String,
        ): Long? = getNewestCloudSaveSyncTimestamp(context, appId, null)

        suspend fun getNewestCloudSaveSyncTimestamp(
            context: Context,
            appId: String,
            targetContainerId: Int?,
        ): Long? =
            withContext(Dispatchers.IO) {
                try {
                    val activeInstance = getInstance() ?: return@withContext null
                    val normalizedAppId = if (appId.startsWith("GOG_", ignoreCase = true)) appId else "GOG_$appId"
                    val gameId = ContainerUtils.extractGameIdFromContainerId(normalizedAppId).toString()
                    val game = activeInstance.gogManager.getGameFromDbById(gameId) ?: return@withContext null
                    val saveLocations =
                        activeInstance.gogManager.getSaveDirectoryPath(context, normalizedAppId, game.title, targetContainerId)
                            ?: return@withContext null
                    saveLocations
                        .mapNotNull { location ->
                            activeInstance.gogManager
                                .getCloudSaveSyncTimestamp(normalizedAppId, location.name)
                                .toLongOrNull()
                        }.maxOrNull()
                        ?.times(1000)
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "[Cloud Saves] Failed to read newest cloud timestamp for $appId")
                    null
                }
            }

        suspend fun listCloudSaveHistory(
            context: Context,
            appId: String,
            targetContainerId: Int? = null,
        ): List<CloudSaveEntry> =
            withContext(Dispatchers.IO) {
                try {
                    val activeInstance = getInstance() ?: return@withContext emptyList()
                    if (!GOGAuthManager.hasStoredCredentials(context)) return@withContext emptyList()
                    val normalizedAppId = if (appId.startsWith("GOG_", ignoreCase = true)) appId else "GOG_$appId"
                    val gameId = ContainerUtils.extractGameIdFromContainerId(normalizedAppId).toString()
                    val game = activeInstance.gogManager.getGameFromDbById(gameId) ?: return@withContext emptyList()
                    val saveLocations =
                        activeInstance.gogManager.getSaveDirectoryPath(context, normalizedAppId, game.title, targetContainerId)
                            ?: return@withContext emptyList()
                    val cloudSavesManager = GOGCloudSavesManager(context)
                    saveLocations.flatMap { location ->
                        if (location.clientSecret.isEmpty()) {
                            Timber.tag("GOG").w("[Cloud Saves] Skipping history list for '${location.name}': missing clientSecret")
                            emptyList()
                        } else {
                            cloudSavesManager
                                .listCloudSaveFiles(
                                    dirname = location.name,
                                    clientId = location.clientId,
                                    clientSecret = location.clientSecret,
                                )
                                .map { file ->
                                    CloudSaveEntry(
                                        locationName = location.name,
                                        relativePath = file.relativePath,
                                        md5Hash = file.md5Hash,
                                        timestampMs = (file.updateTimestamp ?: 0L) * 1000L,
                                    )
                                }
                        }
                    }.sortedByDescending { it.timestampMs }
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "[Cloud Saves] Failed to list cloud save history for $appId")
                    emptyList()
                }
            }

        suspend fun exportCloudSavesZip(
            context: Context,
            appId: String,
            outputStream: OutputStream,
            targetContainerId: Int? = null,
        ): BackupResult =
            withContext(Dispatchers.IO) {
                try {
                    val activeInstance = getInstance() ?: return@withContext BackupResult(false, "GOG service is not running.")
                    if (!GOGAuthManager.hasStoredCredentials(context)) {
                        return@withContext BackupResult(false, "Sign in to GOG before exporting cloud saves.")
                    }
                    val normalizedAppId = if (appId.startsWith("GOG_", ignoreCase = true)) appId else "GOG_$appId"
                    val gameId = ContainerUtils.extractGameIdFromContainerId(normalizedAppId).toString()
                    val game =
                        activeInstance.gogManager.getGameFromDbById(gameId)
                            ?: return@withContext BackupResult(false, "GOG game not found.")
                    val saveLocations =
                        activeInstance.gogManager.getSaveDirectoryPath(context, normalizedAppId, game.title, targetContainerId)
                            ?: return@withContext BackupResult(false, "No GOG cloud-save locations found.")

                    val cloudSavesManager = GOGCloudSavesManager(context)
                    var exportedFiles = 0
                    ZipOutputStream(BufferedOutputStream(outputStream)).use { zip ->
                        for (location in saveLocations) {
                            if (location.clientSecret.isEmpty()) {
                                Timber.tag("GOG").w("[Cloud Saves] Skipping zip export for '${location.name}': missing clientSecret")
                                continue
                            }
                            val prefix = location.name.ifBlank { "__default" }
                            exportedFiles +=
                                cloudSavesManager.addCloudSaveFilesToZip(
                                    zip = zip,
                                    zipPrefix = prefix,
                                    dirname = location.name,
                                    clientId = location.clientId,
                                    clientSecret = location.clientSecret,
                                )
                        }
                    }

                    if (exportedFiles == 0) {
                        BackupResult(false, "No GOG cloud save files found.")
                    } else {
                        BackupResult(true, "Exported $exportedFiles GOG cloud save file(s).")
                    }
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "[Cloud Saves] Failed to export cloud saves zip for $appId")
                    BackupResult(false, "Export failed: ${e.message}")
                }
            }

        data class CloudSaveEntry(
            val locationName: String,
            val relativePath: String,
            val md5Hash: String,
            val timestampMs: Long,
        )

        suspend fun validateCredentials(context: Context): Result<Boolean> = GOGAuthManager.validateCredentials(context)

        fun clearStoredCredentials(context: Context): Boolean = GOGAuthManager.clearStoredCredentials(context)

        // Clears credentials, removes non-installed games, and stops the service.
        suspend fun logout(context: Context): Result<Unit> {
            return withContext(Dispatchers.IO) {
                try {
                    Timber.i("[GOGService] Logging out from GOG")

                    val instance = getInstance()
                    if (instance == null) {
                        clearStoredCredentials(context)
                        Timber.w("[GOGService] Service instance not available during logout; credentials cleared only")
                        return@withContext Result.success(Unit)
                    }

                    val credentialsCleared = clearStoredCredentials(context)
                    if (!credentialsCleared) {
                        Timber.w("[GOGService] Failed to clear credentials during logout")
                    }

                    instance.gogManager.deleteAllNonInstalledGames()
                    Timber.i("[GOGService] All non-installed GOG games removed from database")

                    stop()

                    Timber.i("[GOGService] Logout completed successfully")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.e(e, "[GOGService] Error during logout")
                    Result.failure(e)
                }
            }
        }


        fun hasActiveOperations(): Boolean = syncInProgress || backgroundSyncJob?.isActive == true || hasActiveDownload()

        private fun setSyncInProgress(inProgress: Boolean) {
            syncInProgress = inProgress
        }

        fun isSyncInProgress(): Boolean = syncInProgress

        fun getInstance(): GOGService? = instance


        fun hasActiveDownload(): Boolean = getInstance()?.activeDownloads?.isNotEmpty() ?: false

        fun getCurrentlyDownloadingGame(): String? = getInstance()?.activeDownloads?.keys?.firstOrNull()

        fun getDownloadInfo(gameId: String): DownloadInfo? = getInstance()?.activeDownloads?.get(gameId)

        fun getAllDownloads(): Map<String, DownloadInfo> = getInstance()?.activeDownloads ?: emptyMap()

        fun cleanupDownload(gameId: String) {
            getInstance()?.activeDownloads?.remove(gameId)
        }

        fun cancelDownload(gameId: String): Boolean {
            // Route through the coordinator: it persists CANCELLED and asks our dispatcher to
            DownloadCoordinator.runOnScope {
                DownloadCoordinator.cancel(DownloadRecord.STORE_GOG, gameId)
            }
            return true
        }

        fun pauseDownload(gameId: String) {
            DownloadCoordinator.runOnScope {
                DownloadCoordinator.pause(DownloadRecord.STORE_GOG, gameId)
            }
        }

        fun pauseAll() {
            DownloadCoordinator.runOnScope { DownloadCoordinator.pauseAll() }
        }

        fun resumeDownload(gameId: String) {
            DownloadCoordinator.runOnScope {
                DownloadCoordinator.resume(DownloadRecord.STORE_GOG, gameId)
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
                toRemove.forEach { gameId ->
                    val numericId = gameId.toIntOrNull() ?: 0
                    PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(numericId, false))
                }
            }
        }


        fun getGOGGameOf(gameId: String): GOGGame? =
            runBlocking(Dispatchers.IO) {
                getInstance()?.gogManager?.getGameFromDbById(gameId)
            }

        suspend fun updateGOGGame(game: GOGGame) {
            getInstance()?.gogManager?.updateGame(game)
        }

        fun isGameInstalled(gameId: String): Boolean {
            return runBlocking(Dispatchers.IO) {
                val game = getInstance()?.gogManager?.getGameFromDbById(gameId)
                    ?: return@runBlocking false

                // If the DB already says the game is installed, trust it and just verify the
                // install dir still exists. Avoids the verify/update race where the IN_PROGRESS
                // marker is briefly present and this method would otherwise flip isInstalled=false
                // and clear installPath in the DB — making the game vanish from the library.
                // Mirrors EpicService.isGameInstalled's early-return.
                if (game.isInstalled && game.installPath.isNotBlank()) {
                    return@runBlocking File(game.installPath).isDirectory
                }

                val candidatePaths =
                    linkedSetOf<String>().apply {
                        if (game.installPath.isNotBlank()) add(game.installPath)
                        if (game.title.isNotBlank()) add(GOGConstants.getGameInstallPath(game.title))
                    }
                val installedPath =
                    candidatePaths.firstOrNull { path ->
                        path.isNotBlank() &&
                            File(path).isDirectory &&
                            MarkerUtils.hasMarker(path, Marker.DOWNLOAD_COMPLETE_MARKER) &&
                            !MarkerUtils.hasMarker(path, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    }
                val isInstalled = installedPath != null

                if (game.isInstalled != isInstalled || (isInstalled && game.installPath != installedPath)) {
                    getInstance()?.gogManager?.updateGame(
                        game.copy(
                            isInstalled = isInstalled,
                            installPath = installedPath.orEmpty(),
                        ),
                    )
                }

                isInstalled
            }
        }

        fun getInstallPath(gameId: String): String? =
            runBlocking(Dispatchers.IO) {
                val game = getInstance()?.gogManager?.getGameFromDbById(gameId)
                if (game != null && isGameInstalled(gameId)) {
                    game.installPath.ifBlank { GOGConstants.getGameInstallPath(game.title) }
                } else {
                    null
                }
            }

        fun verifyInstallation(gameId: String): Pair<Boolean, String?> =
            getInstance()?.gogManager?.verifyInstallation(gameId)
                ?: Pair(false, "Service not available")

        suspend fun getInstalledExe(libraryItem: LibraryItem): String =
            getInstance()?.gogManager?.getInstalledExe(libraryItem)
                ?: ""

        /**
         * Resolves the effective launch executable for a GOG game (container config or auto-detected).
         * Returns empty string if no executable can be found.
         */
        suspend fun getLaunchExecutable(
            appId: String,
            container: Container,
        ): String = getInstance()?.gogManager?.getLaunchExecutable(appId, container) ?: ""

        fun getGogWineStartCommand(
            libraryItem: LibraryItem,
            container: Container,
            bootToContainer: Boolean,
            appLaunchInfo: LaunchInfo?,
            envVars: com.winlator.cmod.runtime.wine.EnvVars,
            guestProgramLauncherComponent: com.winlator.cmod.runtime.display.environment.components.GuestProgramLauncherComponent,
            gameId: Int,
        ): String =
            getInstance()?.gogManager?.getGogWineStartCommand(
                libraryItem,
                container,
                bootToContainer,
                appLaunchInfo,
                envVars,
                guestProgramLauncherComponent,
                gameId,
            ) ?: "\"explorer.exe\""

        suspend fun refreshLibrary(context: Context): Result<Int> =
            getInstance()?.gogManager?.refreshLibrary(context)
                ?: Result.failure(Exception("Service not available"))

        suspend fun getDLCForGameSuspend(
            gameId: String,
            containerLanguage: String,
        ) = getInstance()?.gogManager?.getOwnedDlcsForGame(gameId, containerLanguage) ?: emptyList()

        suspend fun getInstallableSelectedManifestSizes(
            gameId: String,
            containerLanguage: String,
            dlcGameIds: Collection<Int> = emptyList(),
        ): GOGManifestSizes =
            getInstance()?.gogManager?.getInstallableSelectedManifestSizes(gameId, containerLanguage, dlcGameIds)
                ?: GOGManifestSizes()

        suspend fun getDlcOnlyManifestSizes(
            gameId: String,
            dlcGameId: Int,
            containerLanguage: String,
        ): GOGManifestSizes =
            getInstance()?.gogManager?.getDlcOnlyManifestSizes(gameId, dlcGameId, containerLanguage)
                ?: GOGManifestSizes()

        suspend fun getInstalledDlcIds(gameId: String): Set<String> =
            getInstance()?.gogManager?.getInstalledDlcIds(gameId) ?: emptySet()

        fun downloadGame(
            context: Context,
            gameId: String,
            installPath: String,
            containerLanguage: String,
            dlcGameIds: List<Int> = emptyList(),
        ): Result<DownloadInfo?> {
            val activeInstance =
                getInstance() ?: run {
                    start(context)
                    return Result.failure(Exception("GOG service is starting. Please try again."))
                }
            val game =
                runBlocking(Dispatchers.IO) { activeInstance.gogManager.getGameFromDbById(gameId) }
                    ?: return Result.failure(Exception("Game not found: $gameId"))
            val effectiveInstallPath =
                if (installPath.isNotEmpty()) {
                    installPath
                } else {
                    activeInstance.gogManager.getGameInstallPath(
                        gameId,
                        game.title,
                    )
                }

            // Persist the chosen install path BEFORE the download starts so cancel/pause/resume
            // can find the partial files even when the user picked a non-default path.
            // (Previously installPath was only written on successful completion, causing cancel
            // to delete the default directory instead of the actual partial install.)
            if (game.installPath != effectiveInstallPath) {
                runBlocking(Dispatchers.IO) {
                    activeInstance.gogManager.updateGame(game.copy(installPath = effectiveInstallPath))
                }
            }

            val existingDownload = activeInstance.activeDownloads[gameId]
            if (existingDownload != null) {
                if (existingDownload.isActive()) {
                    Timber.tag("GOG").w("Download already in progress for $gameId")
                    return Result.success(existingDownload)
                }
                activeInstance.activeDownloads.remove(gameId)
            }

            val downloadInfo =
                DownloadInfo(
                    jobCount = 1,
                    gameId = gameId.toIntOrNull() ?: 0,
                    downloadingAppIds = CopyOnWriteArrayList<Int>(),
                )

            // Stash the original parameters so resume() can restore them after pause.
            activeInstance.downloadParams[gameId] =
                DownloadParams(
                    dlcGameIds = dlcGameIds,
                    containerLanguage = containerLanguage,
                    installPath = effectiveInstallPath,
                )

            // Track in activeDownloads first
            activeInstance.activeDownloads[gameId] = downloadInfo

            // Pre-seed from the persisted record so a resumed download shows its prior
            // byte/percent immediately instead of flashing 0% while manifests refetch.
            val priorRecord =
                runBlocking {
                    DownloadCoordinator.findRecord(DownloadRecord.STORE_GOG, gameId)
                }
            if (priorRecord != null && priorRecord.bytesTotal > 0L) {
                downloadInfo.setTotalExpectedBytes(priorRecord.bytesTotal)
                downloadInfo.setDisplayTotalExpectedBytes(priorRecord.bytesTotal)
                downloadInfo.initializeBytesDownloaded(priorRecord.bytesDownloaded)
            }

            // Ask the global coordinator whether to start now or queue. The coordinator
            // persists a DownloadRecord either way so the download survives an app restart.
            val decision =
                runBlocking {
                    DownloadCoordinator.requestSlot(
                        store = DownloadRecord.STORE_GOG,
                        storeGameId = gameId,
                        title = game.title,
                        artUrl = game.iconUrl,
                        installPath = effectiveInstallPath,
                        selectedDlcs = dlcGameIds.joinToString(","),
                        language = containerLanguage,
                    )
                }
            when (decision) {
                is DownloadCoordinator.Decision.Queue -> {
                    downloadInfo.setActive(false)
                    downloadInfo.isCancelling = false
                    downloadInfo.updateStatus(DownloadPhase.QUEUED, "Queued")
                    val numericId = gameId.toIntOrNull() ?: 0
                    PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(numericId, true))
                    return Result.success(downloadInfo)
                }
                is DownloadCoordinator.Decision.Start -> {
                    // Fall through to launch the coroutine immediately.
                }
            }

            downloadInfo.setActive(true)
            downloadInfo.isCancelling = false
            downloadInfo.updateStatus(DownloadPhase.DOWNLOADING)

            // Launch download in service scope so it runs independently
            val job =
                activeInstance.scope.launch {
                    val keepAliveTag = "gog-download-$gameId"
                    val keepAliveCtx = activeInstance.applicationContext
                    runCatching {
                        SessionKeepAliveService.startDownload(keepAliveCtx, keepAliveTag)
                    }.onFailure { e ->
                        Timber.w(e, "Failed to acquire keep-alive for GOG download $gameId")
                    }
                    try {
                        Timber.d("[Download] Starting download for game $gameId")
                        val commonRedistDir = File(effectiveInstallPath, "_CommonRedist")
                        Timber.tag("GOG").d("Will install dependencies to _CommonRedist")

                        val result =
                            activeInstance.gogDownloadManager.downloadGame(
                                gameId,
                                File(effectiveInstallPath),
                                downloadInfo,
                                containerLanguage,
                                withDlcs = dlcGameIds.isNotEmpty(),
                                supportDir = commonRedistDir,
                                selectedDlcIds = dlcGameIds.map { it.toString() }.toSet(),

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
                            val error = result.exceptionOrNull()
                            when {
                                downloadInfo.isCancelling -> {
                                    Timber.i("[Download] Cancelled for game $gameId")
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(DownloadPhase.CANCELLED)
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
                        } else {
                            Timber.i("[Download] Completed successfully for game $gameId")
                            downloadInfo.setProgress(1.0f)
                            downloadInfo.setActive(false)
                            downloadInfo.updateStatus(DownloadPhase.COMPLETE)

                            SnackbarManager.show("Download completed successfully!")
                        }
                    } catch (e: Exception) {
                        when {
                            downloadInfo.isCancelling -> {
                                Timber.i("[Download] Cancelled for game $gameId")
                                downloadInfo.setActive(false)
                                downloadInfo.updateStatus(DownloadPhase.CANCELLED)
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
                        val finalCoordStatus =
                            when (downloadInfo.getStatusFlow().value) {
                                DownloadPhase.COMPLETE -> DownloadRecord.STATUS_COMPLETE
                                DownloadPhase.PAUSED -> DownloadRecord.STATUS_PAUSED
                                DownloadPhase.CANCELLED -> DownloadRecord.STATUS_CANCELLED
                                DownloadPhase.FAILED -> DownloadRecord.STATUS_FAILED
                                else -> DownloadRecord.STATUS_FAILED
                            }
                        DownloadCoordinator.notifyFinished(
                            DownloadRecord.STORE_GOG,
                            gameId,
                            finalCoordStatus,
                        )
                        val numericId = gameId.toIntOrNull() ?: 0
                        PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(numericId, false))
                        Timber.d(
                            "[Download] Finished for game $gameId, progress: ${downloadInfo.getProgress()}, active: ${downloadInfo.isActive()}",
                        )
                        runCatching {
                            SessionKeepAliveService.stopDownload(keepAliveCtx, keepAliveTag)
                        }.onFailure { e ->
                            Timber.w(e, "Failed to release keep-alive for GOG download $gameId")
                        }
                    }
                }
            downloadInfo.setDownloadJob(job)

            return Result.success(downloadInfo)
        }

        suspend fun refreshSingleGame(
            gameId: String,
            context: Context,
        ): Result<GOGGame?> =
            getInstance()?.gogManager?.refreshSingleGame(gameId, context)
                ?: Result.failure(Exception("Service not available"))

        /**
         * Probe GOG for whether a newer build for [gameId] is available than what's installed.
         * Returns a populated [GOGUpdateInfo] (with [GOGUpdateInfo.message] set on failure) so
         * callers can render a status string without surfacing exceptions.
         */
        suspend fun checkForGameUpdate(
            context: Context,
            gameId: String,
        ): GOGUpdateInfo {
            val instance = getInstance()
                ?: return GOGUpdateInfo(message = "GOG service is not active")
            val game =
                runBlocking(Dispatchers.IO) { instance.gogManager.getGameFromDbById(gameId) }
                    ?: return GOGUpdateInfo(message = "Game not found: $gameId")
            val installPath =
                game.installPath.ifEmpty { GOGConstants.getGameInstallPath(game.title) }
            if (installPath.isBlank() || !File(installPath).isDirectory) {
                return GOGUpdateInfo(message = "Game is not installed")
            }
            return instance.gogUpdateManager.checkForGameUpdate(
                gameId = gameId,
                installPath = File(installPath),
                language = com.winlator.cmod.feature.stores.steam.utils.PrefManager.containerLanguage,
            )
        }

        /**
         * Kick off a Heroic-style verify (repair) of [gameId]'s installed files. Returns the
         * [DownloadInfo] tracking progress, or null if the verify cannot start (game missing,
         * another download already active, etc).
         */
        fun verifyGameFiles(
            context: Context,
            gameId: String,
        ): DownloadInfo? {
            val instance = getInstance() ?: return null
            val game =
                runBlocking(Dispatchers.IO) { instance.gogManager.getGameFromDbById(gameId) }
                    ?: return null
            val installPath =
                game.installPath.ifEmpty { GOGConstants.getGameInstallPath(game.title) }
            if (installPath.isBlank() || !File(installPath).isDirectory) return null

            val activeOther =
                DownloadCoordinator
                    .snapshotRecords()
                    .filter {
                        it.status == DownloadRecord.STATUS_DOWNLOADING ||
                            it.status == DownloadRecord.STATUS_QUEUED
                    }.any {
                        it.store != DownloadRecord.STORE_GOG || it.storeGameId != gameId
                    }
            if (activeOther) return null

            val existing = instance.activeDownloads[gameId]
            if (existing?.isActive() == true) return null
            instance.activeDownloads.remove(gameId)

            val downloadInfo =
                DownloadInfo(
                    jobCount = 1,
                    gameId = gameId.toIntOrNull() ?: 0,
                    downloadingAppIds = CopyOnWriteArrayList<Int>(),
                )
            instance.activeDownloads[gameId] = downloadInfo

            val priorVerifyRecord =
                runBlocking { DownloadCoordinator.findRecord(DownloadRecord.STORE_GOG, gameId) }
            if (priorVerifyRecord != null && priorVerifyRecord.bytesTotal > 0L) {
                downloadInfo.setTotalExpectedBytes(priorVerifyRecord.bytesTotal)
                downloadInfo.setDisplayTotalExpectedBytes(priorVerifyRecord.bytesTotal)
                downloadInfo.initializeBytesDownloaded(priorVerifyRecord.bytesDownloaded)
            }

            val decision =
                runBlocking {
                    DownloadCoordinator.requestSlot(
                        store = DownloadRecord.STORE_GOG,
                        storeGameId = gameId,
                        title = game.title,
                        artUrl = game.iconUrl,
                        installPath = installPath,
                        language = com.winlator.cmod.feature.stores.steam.utils.PrefManager.containerLanguage,
                        taskType = DownloadRecord.TASK_VERIFY,
                    )
                }
            if (decision is DownloadCoordinator.Decision.Queue) {
                downloadInfo.setActive(false)
                downloadInfo.isCancelling = false
                downloadInfo.updateStatus(DownloadPhase.QUEUED, "Queued")
                PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, true))
                return downloadInfo
            }

            downloadInfo.setActive(true)
            downloadInfo.isCancelling = false
            downloadInfo.updateStatus(DownloadPhase.VERIFYING)

            val job =
                instance.scope.launch {
                    val keepAliveTag = "gog-verify-$gameId"
                    val keepAliveCtx = instance.applicationContext
                    runCatching {
                        SessionKeepAliveService.startDownload(keepAliveCtx, keepAliveTag)
                    }.onFailure { e ->
                        Timber.w(e, "Failed to acquire keep-alive for GOG verify $gameId")
                    }
                    try {
                        val result =
                            instance.gogVerifyManager.verifyGameFiles(
                                gameId = gameId,
                                installPath = File(installPath),
                                downloadInfo = downloadInfo,
                                language = com.winlator.cmod.feature.stores.steam.utils.PrefManager.containerLanguage,
                            )
                        if (result.isFailure) {
                            val error = result.exceptionOrNull()
                            when {
                                downloadInfo.isCancelling -> {
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(DownloadPhase.CANCELLED)
                                }
                                !downloadInfo.isActive() -> {
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(DownloadPhase.PAUSED)
                                }
                                else -> {
                                    Timber.tag("GOG").e(error, "[Verify] Failed for GOG game $gameId")
                                    downloadInfo.setProgress(-1.0f)
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(
                                        DownloadPhase.FAILED,
                                        error?.message ?: "Unknown error",
                                    )
                                    SnackbarManager.show("Verify failed: ${error?.message ?: "Unknown error"}")
                                }
                            }
                        } else {
                            downloadInfo.setProgress(1.0f)
                            downloadInfo.setActive(false)
                            downloadInfo.updateStatus(DownloadPhase.COMPLETE)
                            SnackbarManager.show("Verify files complete")
                        }
                    } catch (e: Exception) {
                        Timber.tag("GOG").e(e, "[Verify] Exception for GOG game $gameId")
                        downloadInfo.setProgress(-1.0f)
                        downloadInfo.setActive(false)
                        downloadInfo.updateStatus(DownloadPhase.FAILED, e.message ?: "Unknown error")
                        SnackbarManager.show("Verify failed: ${e.message ?: "Unknown error"}")
                    } finally {
                        val finalCoordStatus =
                            when (downloadInfo.getStatusFlow().value) {
                                DownloadPhase.COMPLETE -> DownloadRecord.STATUS_COMPLETE
                                DownloadPhase.PAUSED -> DownloadRecord.STATUS_PAUSED
                                DownloadPhase.CANCELLED -> DownloadRecord.STATUS_CANCELLED
                                DownloadPhase.FAILED -> DownloadRecord.STATUS_FAILED
                                else -> DownloadRecord.STATUS_FAILED
                            }
                        DownloadCoordinator.notifyFinished(
                            DownloadRecord.STORE_GOG,
                            gameId,
                            finalCoordStatus,
                        )
                        PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, false))
                        runCatching {
                            SessionKeepAliveService.stopDownload(keepAliveCtx, keepAliveTag)
                        }.onFailure { e ->
                            Timber.w(e, "Failed to release keep-alive for GOG verify $gameId")
                        }
                    }
                }
            downloadInfo.setDownloadJob(job)
            return downloadInfo
        }

        fun updateGameFiles(
            context: Context,
            gameId: String,
        ): DownloadInfo? {
            val instance = getInstance() ?: return null
            val game =
                runBlocking(Dispatchers.IO) { instance.gogManager.getGameFromDbById(gameId) }
                    ?: return null
            val installPath =
                game.installPath.ifEmpty { GOGConstants.getGameInstallPath(game.title) }
            if (installPath.isBlank() || !File(installPath).isDirectory) return null

            val activeOther =
                DownloadCoordinator
                    .snapshotRecords()
                    .filter {
                        it.status == DownloadRecord.STATUS_DOWNLOADING ||
                            it.status == DownloadRecord.STATUS_QUEUED
                    }.any {
                        it.store != DownloadRecord.STORE_GOG || it.storeGameId != gameId
                    }
            if (activeOther) return null

            val existing = instance.activeDownloads[gameId]
            if (existing?.isActive() == true) return null
            instance.activeDownloads.remove(gameId)

            val downloadInfo =
                DownloadInfo(
                    jobCount = 1,
                    gameId = gameId.toIntOrNull() ?: 0,
                    downloadingAppIds = CopyOnWriteArrayList<Int>(),
                )
            instance.activeDownloads[gameId] = downloadInfo

            val priorUpdateRecord =
                runBlocking { DownloadCoordinator.findRecord(DownloadRecord.STORE_GOG, gameId) }
            if (priorUpdateRecord != null && priorUpdateRecord.bytesTotal > 0L) {
                downloadInfo.setTotalExpectedBytes(priorUpdateRecord.bytesTotal)
                downloadInfo.setDisplayTotalExpectedBytes(priorUpdateRecord.bytesTotal)
                downloadInfo.initializeBytesDownloaded(priorUpdateRecord.bytesDownloaded)
            }

            val decision =
                runBlocking {
                    DownloadCoordinator.requestSlot(
                        store = DownloadRecord.STORE_GOG,
                        storeGameId = gameId,
                        title = game.title,
                        artUrl = game.iconUrl,
                        installPath = installPath,
                        language = com.winlator.cmod.feature.stores.steam.utils.PrefManager.containerLanguage,
                        taskType = DownloadRecord.TASK_UPDATE,
                    )
                }
            if (decision is DownloadCoordinator.Decision.Queue) {
                downloadInfo.setActive(false)
                downloadInfo.isCancelling = false
                downloadInfo.updateStatus(DownloadPhase.QUEUED, "Queued")
                PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, true))
                return downloadInfo
            }

            downloadInfo.setActive(true)
            downloadInfo.isCancelling = false
            downloadInfo.updateStatus(DownloadPhase.DOWNLOADING)

            val job =
                instance.scope.launch {
                    val keepAliveTag = "gog-update-$gameId"
                    val keepAliveCtx = instance.applicationContext
                    runCatching {
                        SessionKeepAliveService.startDownload(keepAliveCtx, keepAliveTag)
                    }.onFailure { e ->
                        Timber.w(e, "Failed to acquire keep-alive for GOG update $gameId")
                    }
                    try {
                        val result =
                            instance.gogUpdateManager.updateGameFiles(
                                gameId = gameId,
                                installPath = File(installPath),
                                downloadInfo = downloadInfo,
                                language = com.winlator.cmod.feature.stores.steam.utils.PrefManager.containerLanguage,
                            )
                        if (result.isFailure) {
                            val error = result.exceptionOrNull()
                            when {
                                downloadInfo.isCancelling -> {
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(DownloadPhase.CANCELLED)
                                }
                                !downloadInfo.isActive() -> {
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(DownloadPhase.PAUSED)
                                }
                                else -> {
                                    Timber.tag("GOG").e(error, "[Update] Failed for GOG game $gameId")
                                    downloadInfo.setProgress(-1.0f)
                                    downloadInfo.setActive(false)
                                    downloadInfo.updateStatus(
                                        DownloadPhase.FAILED,
                                        error?.message ?: "Unknown error",
                                    )
                                    SnackbarManager.show("Update failed: ${error?.message ?: "Unknown error"}")
                                }
                            }
                        } else {
                            downloadInfo.setProgress(1.0f)
                            downloadInfo.setActive(false)
                            downloadInfo.updateStatus(DownloadPhase.COMPLETE)
                            SnackbarManager.show("Update complete")
                        }
                    } catch (e: Exception) {
                        Timber.tag("GOG").e(e, "[Update] Exception for GOG game $gameId")
                        downloadInfo.setProgress(-1.0f)
                        downloadInfo.setActive(false)
                        downloadInfo.updateStatus(DownloadPhase.FAILED, e.message ?: "Unknown error")
                        SnackbarManager.show("Update failed: ${e.message ?: "Unknown error"}")
                    } finally {
                        val finalCoordStatus =
                            when (downloadInfo.getStatusFlow().value) {
                                DownloadPhase.COMPLETE -> DownloadRecord.STATUS_COMPLETE
                                DownloadPhase.PAUSED -> DownloadRecord.STATUS_PAUSED
                                DownloadPhase.CANCELLED -> DownloadRecord.STATUS_CANCELLED
                                DownloadPhase.FAILED -> DownloadRecord.STATUS_FAILED
                                else -> DownloadRecord.STATUS_FAILED
                            }
                        DownloadCoordinator.notifyFinished(
                            DownloadRecord.STORE_GOG,
                            gameId,
                            finalCoordStatus,
                        )
                        PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, false))
                        runCatching {
                            SessionKeepAliveService.stopDownload(keepAliveCtx, keepAliveTag)
                        }.onFailure { e ->
                            Timber.w(e, "Failed to release keep-alive for GOG update $gameId")
                        }
                    }
                }
            downloadInfo.setDownloadJob(job)
            return downloadInfo
        }

        suspend fun deleteGame(
            context: Context,
            libraryItem: LibraryItem,
        ): Result<Unit> =
            getInstance()?.gogManager?.deleteGame(context, libraryItem)
                ?: Result.failure(Exception("Service not available"))

        /**
         * Sync GOG cloud saves for a game
         * @param context Android context
         * @param appId Game app ID (e.g., "gog_123456")
         * @param preferredAction Preferred sync action: "download", "upload", or "none"
         * @return true if sync succeeded, false otherwise
         */
        suspend fun syncCloudSaves(
            context: Context,
            appId: String,
            preferredAction: String = "none",
        ): Boolean = syncCloudSaves(context, appId, preferredAction, null)

        suspend fun syncCloudSaves(
            context: Context,
            appId: String,
            preferredAction: String,
            targetContainerId: Int?,
        ): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    Timber.tag("GOG").d("[Cloud Saves] syncCloudSaves called for $appId with action: $preferredAction")

                    val serviceInstance = getInstance()
                    if (serviceInstance == null) {
                        Timber.tag("GOG").e("[Cloud Saves] Service instance not available for sync start")
                        return@withContext false
                    }

                    if (!serviceInstance.gogManager.startSync(appId)) {
                        Timber.tag("GOG").w("[Cloud Saves] Sync already in progress for $appId, skipping duplicate sync")
                        return@withContext false
                    }

                    try {
                        val instance = getInstance()
                        if (instance == null) {
                            Timber.tag("GOG").e("[Cloud Saves] Service instance not available")
                            return@withContext false
                        }

                        if (!GOGAuthManager.hasStoredCredentials(context)) {
                            Timber.tag("GOG").e("[Cloud Saves] Cannot sync saves: not authenticated")
                            return@withContext false
                        }

                        val authConfigPath = GOGAuthManager.getAuthConfigPath(context)
                        Timber.tag("GOG").d("[Cloud Saves] Using auth config path: $authConfigPath")

                        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
                        Timber.tag("GOG").d("[Cloud Saves] Extracted game ID: $gameId from appId: $appId")
                        val game = instance.gogManager.getGameFromDbById(gameId.toString())

                        if (game == null) {
                            Timber.tag("GOG").e("[Cloud Saves] Game not found for appId: $appId")
                            return@withContext false
                        }
                        Timber.tag("GOG").d("[Cloud Saves] Found game: ${game.title}")

                        Timber.tag("GOG").d(
                            "[Cloud Saves] Resolving save directory paths for $appId (container: ${targetContainerId ?: "auto"})",
                        )
                        val saveLocations =
                            instance.gogManager.getSaveDirectoryPath(context, appId, game.title, targetContainerId)

                        if (saveLocations == null || saveLocations.isEmpty()) {
                            Timber.tag("GOG").w("[Cloud Saves] No save locations found for game $appId (cloud saves may not be enabled)")
                            return@withContext false
                        }
                        Timber.tag("GOG").i("[Cloud Saves] Found ${saveLocations.size} save location(s) for $appId")

                        var allSucceeded = true

                        // Sync each save location
                        for ((index, location) in saveLocations.withIndex()) {
                            try {
                                Timber
                                    .tag(
                                        "GOG",
                                    ).d("[Cloud Saves] Processing location ${index + 1}/${saveLocations.size}: '${location.name}'")

                                // Log directory state BEFORE sync
                                try {
                                    val saveDir = java.io.File(location.location)
                                    Timber.tag("GOG").d("[Cloud Saves] [BEFORE] Checking directory: ${location.location}")
                                    Timber
                                        .tag(
                                            "GOG",
                                        ).d(
                                            "[Cloud Saves] [BEFORE] Directory exists: ${saveDir.exists()}, isDirectory: ${saveDir.isDirectory}",
                                        )
                                    if (saveDir.exists() && saveDir.isDirectory) {
                                        val filesBefore = saveDir.listFiles()
                                        if (filesBefore != null && filesBefore.isNotEmpty()) {
                                            Timber.tag("GOG").i(
                                                "[Cloud Saves] [BEFORE] ${filesBefore.size} files in '${location.name}': ${filesBefore.joinToString(
                                                    ", ",
                                                ) {
                                                    it.name
                                                }}",
                                            )
                                        } else {
                                            Timber.tag("GOG").i("[Cloud Saves] [BEFORE] Directory '${location.name}' is empty")
                                        }
                                    } else {
                                        Timber.tag("GOG").i("[Cloud Saves] [BEFORE] Directory '${location.name}' does not exist yet")
                                    }
                                } catch (e: Exception) {
                                    Timber.tag("GOG").e(e, "[Cloud Saves] [BEFORE] Failed to check directory")
                                }

                                val timestampStr = instance.gogManager.getCloudSaveSyncTimestamp(appId, location.name)
                                val timestamp = timestampStr.toLongOrNull() ?: 0L

                                Timber
                                    .tag(
                                        "GOG",
                                    ).i(
                                        "[Cloud Saves] Syncing '${location.name}' for game $gameId (clientId: ${location.clientId}, path: ${location.location}, timestamp: $timestamp, action: $preferredAction)",
                                    )

                                if (location.clientSecret.isEmpty()) {
                                    Timber.tag("GOG").e("[Cloud Saves] Missing clientSecret for '${location.name}', skipping sync")
                                    continue
                                }

                                val cloudSavesManager = GOGCloudSavesManager(context)
                                val newTimestamp =
                                    cloudSavesManager.syncSaves(
                                        clientId = location.clientId,
                                        clientSecret = location.clientSecret,
                                        localPath = location.location,
                                        dirname = location.name,
                                        lastSyncTimestamp = timestamp,
                                        preferredAction = preferredAction,
                                    )

                                if (newTimestamp > 0) {
                                    // Success - store new timestamp
                                    instance.gogManager.setCloudSaveSyncTimestamp(appId, location.name, newTimestamp.toString())
                                    Timber.tag("GOG").d("[Cloud Saves] Updated timestamp for '${location.name}': $newTimestamp")

                                    // Log the save files in the directory after sync
                                    try {
                                        val saveDir = java.io.File(location.location)
                                        if (saveDir.exists() && saveDir.isDirectory) {
                                            val files = saveDir.listFiles()
                                            if (files != null && files.isNotEmpty()) {
                                                val fileList = files.joinToString(", ") { it.name }
                                                Timber
                                                    .tag(
                                                        "GOG",
                                                    ).i(
                                                        "[Cloud Saves] [$preferredAction] Files in '${location.name}': $fileList (${files.size} files)",
                                                    )

                                                // Log detailed file info
                                                files.forEach { file ->
                                                    val size = if (file.isFile) "${file.length()} bytes" else "directory"
                                                    Timber.tag("GOG").d("[Cloud Saves] [$preferredAction]   - ${file.name} ($size)")
                                                }
                                            } else {
                                                Timber
                                                    .tag(
                                                        "GOG",
                                                    ).w(
                                                        "[Cloud Saves] [$preferredAction] Directory '${location.name}' is empty at: ${location.location}",
                                                    )
                                            }
                                        } else {
                                            Timber
                                                .tag(
                                                    "GOG",
                                                ).w("[Cloud Saves] [$preferredAction] Directory not found: ${location.location}")
                                        }
                                    } catch (e: Exception) {
                                        Timber.tag("GOG").e(e, "[Cloud Saves] Failed to list files in directory: ${location.location}")
                                    }

                                    Timber
                                        .tag(
                                            "GOG",
                                        ).i("[Cloud Saves] Successfully synced save location '${location.name}' for game $gameId")
                                } else {
                                    Timber
                                        .tag(
                                            "GOG",
                                        ).e(
                                            "[Cloud Saves] Failed to sync save location '${location.name}' for game $gameId (timestamp: $newTimestamp)",
                                        )
                                    allSucceeded = false
                                }
                            } catch (e: Exception) {
                                Timber.tag("GOG").e(e, "[Cloud Saves] Exception syncing save location '${location.name}' for game $gameId")
                                allSucceeded = false
                            }
                        }

                        if (allSucceeded) {
                            Timber.tag("GOG").i("[Cloud Saves] All save locations synced successfully for $appId")
                            return@withContext true
                        } else {
                            Timber.tag("GOG").w("[Cloud Saves] Some save locations failed to sync for $appId")
                            return@withContext false
                        }
                    } finally {
                        // Always end the sync, even if an exception occurred
                        getInstance()?.gogManager?.endSync(appId)
                        Timber.tag("GOG").d("[Cloud Saves] Sync completed and lock released for $appId")
                    }
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "[Cloud Saves] Failed to sync cloud saves for App ID: $appId")
                    return@withContext false
                }
            }
    }

    private val coordinatorDispatcher =
        object : DownloadCoordinator.Dispatcher {
            override fun startQueued(record: DownloadRecord) {
                val context = com.winlator.cmod.app.service.DownloadService.appContext ?: return
                val gameId = record.storeGameId
                val params = downloadParams[gameId]
                val dlcGameIds =
                    params?.dlcGameIds
                        ?: record.selectedDlcs
                            .split(',')
                            .mapNotNull { it.trim().toIntOrNull() }
                val installPath = params?.installPath ?: record.installPath
                val containerLanguage = params?.containerLanguage ?: record.language

                // "already downloading" — it will recreate the DownloadInfo and launch.
                activeDownloads.remove(gameId)

                when (record.taskType) {
                    DownloadRecord.TASK_VERIFY -> verifyGameFiles(context, gameId)
                    DownloadRecord.TASK_UPDATE -> updateGameFiles(context, gameId)
                    else -> downloadGame(context, gameId, installPath, containerLanguage, dlcGameIds)
                }
            }

            override fun pauseRunning(record: DownloadRecord) {
                val gameId = record.storeGameId
                val info = activeDownloads[gameId] ?: return
                if (info.isActive()) {
                    info.isCancelling = false
                    info.updateStatus(DownloadPhase.PAUSED)
                    info.cancel("Paused by user")
                } else {
                    info.updateStatus(DownloadPhase.PAUSED)
                    info.setActive(false)
                    val numericId = gameId.toIntOrNull() ?: 0
                    PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(numericId, false))
                }
            }

            override fun cancelRunning(record: DownloadRecord) {
                val gameId = record.storeGameId
                val info = activeDownloads[gameId]
                if (info != null) {
                    info.isCancelling = true
                    info.cancel("Cancelled by user")
                }
                CoroutineScope(Dispatchers.IO).launch {
                    info?.awaitCompletion(timeoutMs = 3000L)
                    val pathToDelete =
                        record.installPath.ifEmpty {
                            val game = gogManager.getGameFromDbById(gameId)
                            if (game != null) {
                                game.installPath.ifEmpty {
                                    gogManager.getGameInstallPath(gameId, game.title)
                                }
                            } else {
                                ""
                            }
                        }
                    // VERIFY/UPDATE operate in-place on an already-installed game. Cancelling them
                    // must NEVER wipe the install dir — only the fresh-install task gets the rollback
                    // delete. Restore the COMPLETE marker so the library and launcher still treat
                    // the game as installed (any half-written files will be caught on next verify).
                    if (record.taskType == DownloadRecord.TASK_UPDATE ||
                        record.taskType == DownloadRecord.TASK_VERIFY
                    ) {
                        if (pathToDelete.isNotEmpty()) {
                            MarkerUtils.removeMarker(pathToDelete, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                            MarkerUtils.addMarker(pathToDelete, Marker.DOWNLOAD_COMPLETE_MARKER)
                        }
                        info?.updateStatus(DownloadPhase.CANCELLED)
                        val numericIdEarly = gameId.toIntOrNull() ?: 0
                        PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(numericIdEarly, false))
                        return@launch
                    }
                    if (pathToDelete.isNotEmpty()) {
                        val dirFile = File(pathToDelete)
                        if (dirFile.exists() && dirFile.isDirectory) {
                            val deleteCheck =
                                StoreInstallPathSafety.checkInstallDirDelete(
                                    applicationContext,
                                    pathToDelete,
                                    protectedRoots = listOf(GOGConstants.defaultGOGGamesPath),
                                )
                            if (deleteCheck.allowed) {
                                MarkerUtils.removeMarker(pathToDelete, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                                MarkerUtils.removeMarker(pathToDelete, Marker.DOWNLOAD_COMPLETE_MARKER)
                                dirFile.deleteRecursively()
                            } else {
                                Timber.e("Refusing to delete cancelled GOG download path '$pathToDelete': ${deleteCheck.reason}")
                            }
                        }
                    }
                    info?.updateStatus(DownloadPhase.CANCELLED)
                    val numericId = gameId.toIntOrNull() ?: 0
                    PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(numericId, false))
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        instance = this

//        notificationHelper = NotificationHelper(applicationContext)
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)

        DownloadCoordinator.registerDispatcher(DownloadRecord.STORE_GOG, coordinatorDispatcher)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Timber.d("[GOGService] onStartCommand() - action: ${intent?.action}")

        SessionKeepAliveService.startComponent(this, SessionKeepAliveService.COMPONENT_GOG, "Connected")

        val shouldSync =
            when (intent?.action) {
                ACTION_MANUAL_SYNC -> {
                    Timber.i("[GOGService] Manual sync requested - bypassing throttle")
                    true
                }

                ACTION_SYNC_LIBRARY -> {
                    Timber.i("[GOGService] Automatic sync requested")
                    true
                }

                null -> {
                    // START_STICKY restart: sync only if initial sync is missing or throttle elapsed.
                    val timeSinceLastSync = System.currentTimeMillis() - lastSyncTimestamp
                    val shouldResync = !hasPerformedInitialSync || timeSinceLastSync >= SYNC_THROTTLE_MILLIS

                    if (shouldResync) {
                        Timber.i(
                            "[GOGService] Service restarted by Android - performing sync (hasPerformedInitialSync=$hasPerformedInitialSync, timeSinceLastSync=${timeSinceLastSync}ms)",
                        )
                        true
                    } else {
                        Timber.d("[GOGService] Service restarted by Android - skipping sync (throttled)")
                        false
                    }
                }

                else -> {
                    Timber.d("[GOGService] Service started without sync action")
                    false
                }
            }

        if (shouldSync && (backgroundSyncJob == null || backgroundSyncJob?.isActive != true)) {
            Timber.i("[GOGService] Starting background library sync")
            backgroundSyncJob?.cancel()
            backgroundSyncJob =
                scope.launch {
                    try {
                        setSyncInProgress(true)
                        Timber.d("[GOGService]: Starting background library sync")

                        val syncResult = gogManager.startBackgroundSync(applicationContext)
                        if (syncResult.isFailure) {
                            Timber.w("[GOGService]: Failed to start background sync: ${syncResult.exceptionOrNull()?.message}")
                        } else {
                            Timber.i("[GOGService]: Background library sync completed successfully")
                            lastSyncTimestamp = System.currentTimeMillis()
                            hasPerformedInitialSync = true
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "[GOGService]: Exception starting background sync")
                    } finally {
                        setSyncInProgress(false)
                    }
                }
        } else if (shouldSync) {
            Timber.d("[GOGService] Background sync already in progress, skipping")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)
        DownloadCoordinator.unregisterDispatcher(DownloadRecord.STORE_GOG)

        backgroundSyncJob?.cancel()
        setSyncInProgress(false)

        scope.cancel()
        SessionKeepAliveService.stopComponent(this, SessionKeepAliveService.COMPONENT_GOG)
        instance = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Timber.i("[GOGService] Task removed; stopping managed app services")
        SessionKeepAliveService.stopComponent(this, SessionKeepAliveService.COMPONENT_GOG)
        AppTerminationHelper.stopManagedServices(applicationContext, "gog_task_removed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
