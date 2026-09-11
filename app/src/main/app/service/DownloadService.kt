package com.winlator.cmod.app.service
import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import com.winlator.cmod.R
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.feature.stores.epic.service.EpicService
import com.winlator.cmod.feature.stores.gog.service.GOGService
import com.winlator.cmod.feature.stores.itch.service.ItchService
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.shared.io.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

object DownloadService {
    private var lastUpdateTime: Long = 0
    private var downloadDirectoryApps: MutableList<String>? = null
    @Volatile
    private var initialized = false

    private var _baseDataDirPath: String = ""
    private var _baseCacheDirPath: String = ""
    private var _baseExternalAppDirPath: String = ""
    private var _externalVolumePaths: List<String> = emptyList()
    private var _appContext: Context? = null

    var baseDataDirPath: String
        get() {
            ensureInitialized()
            return _baseDataDirPath
        }
        private set(value) {
            _baseDataDirPath = value
        }

    var baseCacheDirPath: String
        get() {
            ensureInitialized()
            return _baseCacheDirPath
        }
        private set(value) {
            _baseCacheDirPath = value
        }

    var baseExternalAppDirPath: String
        get() {
            ensureInitialized()
            return _baseExternalAppDirPath
        }
        private set(value) {
            _baseExternalAppDirPath = value
        }

    var externalVolumePaths: List<String>
        get() {
            ensureInitialized()
            return _externalVolumePaths
        }
        private set(value) {
            _externalVolumePaths = value
        }

    var appContext: Context?
        get() {
            ensureInitialized()
            return _appContext
        }
        private set(value) {
            _appContext = value
        }

    fun populateDownloadService(context: Context) {
        ensureInitialized(context)
    }

    private fun ensureInitialized(context: Context? = null) {
        if (initialized) return

        synchronized(this) {
            if (initialized) return

            val resolvedContext =
                context?.applicationContext
                    ?: _appContext
                    ?: runCatching { PluviaApp.instance.applicationContext }.getOrNull()
                    ?: throw IllegalStateException("DownloadService used before application startup")

            appContext = resolvedContext
            baseDataDirPath = resolvedContext.dataDir.path
            baseCacheDirPath = resolvedContext.cacheDir.path
            val extFiles = resolvedContext.getExternalFilesDir(null)
            baseExternalAppDirPath = extFiles?.parentFile?.path ?: ""

            val storageManager = resolvedContext.getSystemService(StorageManager::class.java)
            externalVolumePaths =
                resolvedContext
                    .getExternalFilesDirs(null)
                    .filterNotNull()
                    .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
                    .filter { storageManager?.getStorageVolume(it)?.isPrimary != true }
                    .map { it.absolutePath }
                    .distinct()

            initialized = true
        }
    }

    fun getAllDownloads(): List<Pair<String, com.winlator.cmod.feature.stores.steam.data.DownloadInfo>> {
        val list = mutableListOf<Pair<String, com.winlator.cmod.feature.stores.steam.data.DownloadInfo>>()
        SteamService.getAllDownloads().forEach { (id, info) -> list.add("STEAM_$id" to info) }
        EpicService.getAllDownloads().forEach { (id, info) -> list.add("EPIC_$id" to info) }
        GOGService.getAllDownloads().forEach { (id, info) -> list.add("GOG_$id" to info) }
        ItchService.getAllDownloads().forEach { (id, info) -> list.add("ITCH_$id" to info) }

        // Cover the cross-restart case: the DownloadCoordinator may know about records
        // (PAUSED or QUEUED) for which no store has yet created an in-memory DownloadInfo.
        // Fabricate stub DownloadInfos for those so the Downloads tab shows them and the user
        // can Resume / Cancel them.
        val coord = com.winlator.cmod.app.service.download.DownloadCoordinator
        val records = coord.snapshotRecords()
        val recordsByUiId = records.associateBy { "${it.store}_${it.storeGameId}" }
        list.forEach { (id, info) ->
            val record = recordsByUiId[id] ?: return@forEach
            if (record.bytesTotal > 0L) {
                info.setDisplayTotalExpectedBytes(record.bytesTotal)
                if (info.getTotalExpectedBytes() <= 0L) {
                    info.setTotalExpectedBytes(record.bytesTotal)
                    info.initializeBytesDownloaded(record.bytesDownloaded)
                }
            }
        }

        val knownIds = list.map { it.first }.toSet()
        records.forEach { record ->
            val id = "${record.store}_${record.storeGameId}"
            if (id in knownIds) return@forEach
            val phase = mapRecordStatusToPhase(record.status)
            val gameIdInt = record.storeGameId.toIntOrNull() ?: 0
            // Preserve the original FAILED reason across restarts so the row
            // doesn't collapse to a generic "Unknown error" on rehydrate.
            val stubStatusMessage =
                when (phase) {
                    com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.PAUSED ->
                        appContext?.getString(R.string.downloads_queue_paused_resume_hint)
                    com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.FAILED ->
                        record.errorMessage?.takeUnless { it.isBlank() }
                    else -> null
                }
            val stub =
                com.winlator.cmod.feature.stores.steam.data.DownloadInfo(
                    jobCount = 1,
                    gameId = gameIdInt,
                    downloadingAppIds = java.util.concurrent.CopyOnWriteArrayList(),
                ).apply {
                    setActive(false)
                    updateStatus(phase, stubStatusMessage)
                    if (record.bytesTotal > 0L) {
                        setTotalExpectedBytes(record.bytesTotal)
                        setDisplayTotalExpectedBytes(record.bytesTotal)
                        initializeBytesDownloaded(record.bytesDownloaded)
                    }
                }
            list.add(id to stub)
        }
        return list
    }

    private fun mapRecordStatusToPhase(
        status: String,
    ): com.winlator.cmod.feature.stores.steam.enums.DownloadPhase =
        when (status) {
            com.winlator.cmod.app.db.download.DownloadRecord.STATUS_DOWNLOADING ->
                com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.DOWNLOADING
            com.winlator.cmod.app.db.download.DownloadRecord.STATUS_QUEUED ->
                com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.QUEUED
            com.winlator.cmod.app.db.download.DownloadRecord.STATUS_PAUSED ->
                com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.PAUSED
            com.winlator.cmod.app.db.download.DownloadRecord.STATUS_COMPLETE ->
                com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.COMPLETE
            com.winlator.cmod.app.db.download.DownloadRecord.STATUS_CANCELLED ->
                com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.CANCELLED
            com.winlator.cmod.app.db.download.DownloadRecord.STATUS_FAILED ->
                com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.FAILED
            else -> com.winlator.cmod.feature.stores.steam.enums.DownloadPhase.UNKNOWN
        }

    fun pauseAll() {
        SteamService.pauseAll()
        EpicService.pauseAll()
        GOGService.pauseAll()
        ItchService.pauseAll()
    }

    fun pauseDownload(id: String) {
        when {
            id.startsWith("STEAM_") -> {
                val appId = id.removePrefix("STEAM_").toIntOrNull() ?: return
                SteamService.pauseDownload(appId)
            }

            id.startsWith("EPIC_") -> {
                val appId = id.removePrefix("EPIC_").toIntOrNull() ?: return
                EpicService.pauseDownload(appId)
            }

            id.startsWith("GOG_") -> {
                val gameId = id.removePrefix("GOG_")
                GOGService.pauseDownload(gameId)
            }

            id.startsWith("ITCH_") -> {
                ItchService.pauseDownload(id.removePrefix("ITCH_"))
            }
        }
    }

    fun resumeAll() {
        SteamService.resumeAll()
        EpicService.resumeAll()
        GOGService.resumeAll()
        ItchService.resumeAll()
    }

    fun resumeDownload(id: String) {
        when {
            id.startsWith("STEAM_") -> {
                val appId = id.removePrefix("STEAM_").toIntOrNull() ?: return
                SteamService.resumeDownload(appId)
            }

            id.startsWith("EPIC_") -> {
                val appId = id.removePrefix("EPIC_").toIntOrNull() ?: return
                EpicService.resumeDownload(appId)
            }

            id.startsWith("GOG_") -> {
                val gameId = id.removePrefix("GOG_")
                GOGService.resumeDownload(gameId)
            }

            id.startsWith("ITCH_") -> {
                ItchService.resumeDownload(id.removePrefix("ITCH_"))
            }
        }
    }

    fun cancelAll() {
        SteamService.cancelAll()
        EpicService.cancelAll()
        GOGService.cancelAll()
        ItchService.cancelAll()
    }

    fun clearCompletedDownloads() {
        SteamService.clearCompletedDownloads()
        EpicService.clearCompletedDownloads()
        GOGService.clearCompletedDownloads()
        ItchService.clearCompletedDownloads()
        // Sweep finished records from the cross-store coordinator table too.
        com.winlator.cmod.app.service.download.DownloadCoordinator.runOnScope {
            com.winlator.cmod.app.service.download.DownloadCoordinator.clear()
        }
    }

    fun clearCompletedDownloadsBlocking() {
        SteamService.clearCompletedDownloadsForShutdown()
        EpicService.clearCompletedDownloads()
        GOGService.clearCompletedDownloads()
        ItchService.clearCompletedDownloads()
        // Shutdown can kill the process immediately, so wait for persisted history cleanup.
        com.winlator.cmod.app.service.download.DownloadCoordinator.clearBlocking()
    }

    fun cancelDownload(id: String) {
        when {
            id.startsWith("STEAM_") -> {
                val appId = id.removePrefix("STEAM_").toIntOrNull() ?: return
                SteamService.cancelDownload(appId)
            }

            id.startsWith("EPIC_") -> {
                val appId = id.removePrefix("EPIC_").toIntOrNull() ?: return
                EpicService.cancelDownload(appId)
            }

            id.startsWith("GOG_") -> {
                val gameId = id.removePrefix("GOG_")
                GOGService.cancelDownload(gameId)
            }

            id.startsWith("ITCH_") -> {
                ItchService.cancelDownload(id.removePrefix("ITCH_"))
            }
        }
    }

    fun getSizeFromStoreDisplay(appId: Int): String {
        val depots = SteamService.getDownloadableDepots(appId)
        val installBytes = depots.values.sumOf { it.manifests["public"]?.size ?: 0L }
        return StorageUtils.formatBinarySize(installBytes)
    }

    suspend fun getSizeOnDiskDisplay(
        appId: Int,
        setResult: (String) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            if (SteamService.isAppInstalled(appId)) {
                val appSizeText =
                    StorageUtils.formatBinarySize(
                        StorageUtils.getFolderSize(SteamService.getAppDirPath(appId)),
                    )

                Timber.d("Finding $appId size on disk $appSizeText")
                setResult(appSizeText)
            }
        }
    }
}
