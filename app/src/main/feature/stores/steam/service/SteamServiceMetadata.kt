package com.winlator.cmod.feature.stores.steam.service
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.room.withTransaction
import com.winlator.cmod.BuildConfig
import com.winlator.cmod.R
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.app.db.download.DownloadRecord
import com.winlator.cmod.app.service.DownloadService
import com.winlator.cmod.app.service.NetworkMonitor
import com.winlator.cmod.app.service.download.DownloadCoordinator
import com.winlator.cmod.feature.shortcuts.LibraryShortcutUtils
import com.winlator.cmod.feature.stores.common.InstallOwnership
import com.winlator.cmod.feature.stores.common.InstallStore
import com.winlator.cmod.feature.stores.steam.data.AppInfo
import com.winlator.cmod.feature.stores.steam.data.CachedLicense
import com.winlator.cmod.feature.stores.steam.data.DepotInfo
import com.winlator.cmod.feature.stores.steam.data.DownloadFailedException
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.data.WnDownloadTransientException
import com.winlator.cmod.feature.stores.steam.data.DownloadingAppInfo
import com.winlator.cmod.feature.stores.steam.data.EncryptedAppTicket
import com.winlator.cmod.feature.stores.steam.data.GameProcessInfo
import com.winlator.cmod.feature.stores.steam.data.LaunchInfo
import com.winlator.cmod.feature.stores.steam.data.ManifestInfo
import com.winlator.cmod.feature.stores.steam.data.OwnedGames
import com.winlator.cmod.feature.stores.steam.data.PostSyncInfo
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.feature.stores.steam.data.SteamControllerConfigDetail
import com.winlator.cmod.feature.stores.steam.data.SteamFriend
import com.winlator.cmod.feature.stores.steam.data.SteamFriendEntry
import com.winlator.cmod.feature.stores.steam.data.SteamLicense
import com.winlator.cmod.feature.stores.steam.data.UserFileInfo
import com.winlator.cmod.feature.stores.steam.db.dao.AppInfoDao
import com.winlator.cmod.feature.stores.steam.db.dao.CachedLicenseDao
import com.winlator.cmod.feature.stores.steam.db.dao.ChangeNumbersDao
import com.winlator.cmod.feature.stores.steam.db.dao.DownloadingAppInfoDao
import com.winlator.cmod.feature.stores.steam.db.dao.EncryptedAppTicketDao
import com.winlator.cmod.feature.stores.steam.db.dao.FileChangeListsDao
import com.winlator.cmod.feature.stores.steam.db.dao.SteamAppDao
import com.winlator.cmod.feature.stores.steam.db.dao.SteamLicenseDao
import com.winlator.cmod.feature.stores.steam.enums.ControllerSupport
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.feature.stores.steam.enums.GameSource
import com.winlator.cmod.feature.stores.steam.enums.Language
import com.winlator.cmod.feature.stores.steam.enums.LoginResult
import com.winlator.cmod.feature.stores.steam.enums.Marker
import com.winlator.cmod.feature.stores.steam.enums.OS
import com.winlator.cmod.feature.stores.steam.enums.OSArch
import com.winlator.cmod.feature.stores.steam.enums.SaveLocation
import com.winlator.cmod.feature.stores.steam.enums.SyncResult
import com.auth0.android.jwt.JWT
import com.winlator.cmod.feature.stores.common.StoreAuthStatus
import com.winlator.cmod.feature.stores.common.StoreArtworkCache
import com.winlator.cmod.feature.stores.common.StoreInstallPathSafety
import com.winlator.cmod.feature.stores.steam.events.AndroidEvent
import com.winlator.cmod.feature.stores.steam.events.SteamEvent
import com.winlator.cmod.feature.stores.steam.inventorygen.InventoryItemsGenerator
import com.winlator.cmod.feature.stores.steam.wnsteam.CaBundleExtractor
import com.winlator.cmod.feature.stores.steam.wnsteam.WnAuthCallback
import com.winlator.cmod.feature.stores.steam.wnsteam.WnDownloadListener
import com.winlator.cmod.feature.stores.steam.wnsteam.WnAuthResult
import com.winlator.cmod.feature.stores.steam.wnsteam.WnAuthenticator
import com.winlator.cmod.feature.stores.steam.wnsteam.WnLibraryStore
import com.winlator.cmod.feature.stores.steam.wnsteam.WnQrCallback
import com.winlator.cmod.feature.stores.steam.wnsteam.WnSteamSession
import com.winlator.cmod.feature.stores.steam.wnsteam.WnSteamStateObserver
import com.winlator.cmod.feature.stores.steam.workshop.WorkshopModsGenerator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume
import com.winlator.cmod.feature.stores.steam.statsgen.StatType
import com.winlator.cmod.feature.stores.steam.statsgen.StatsAchievementsGenerator
import com.winlator.cmod.feature.stores.steam.statsgen.VdfParser
import com.winlator.cmod.feature.stores.steam.utils.ContainerUtils
import com.winlator.cmod.feature.stores.steam.utils.LicenseSerializer
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils
import com.winlator.cmod.feature.stores.steam.utils.Net
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.feature.stores.steam.utils.SteamUtils
import com.winlator.cmod.feature.stores.steam.utils.WnKeyValue
import com.winlator.cmod.feature.stores.steam.utils.generateSteamApp
import com.winlator.cmod.feature.steamcloudsync.SteamAutoCloud
import com.winlator.cmod.feature.sync.google.CloudSyncManager
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.system.GPUInformation
import com.winlator.cmod.runtime.system.SessionKeepAliveService
import com.winlator.cmod.shared.android.AppTerminationHelper
import com.winlator.cmod.shared.ui.toast.WinToast
import com.winlator.cmod.shared.android.NotificationHelper
import com.winlator.cmod.shared.io.StorageUtils
import dagger.hilt.android.AndroidEntryPoint
import com.winlator.cmod.feature.stores.steam.enums.EDepotFileFlag
import com.winlator.cmod.feature.stores.steam.enums.ELicenseFlags
import com.winlator.cmod.feature.stores.steam.enums.ELicenseType
import com.winlator.cmod.feature.stores.steam.enums.EPaymentMethod
import com.winlator.cmod.feature.stores.steam.enums.EOSType
import com.winlator.cmod.feature.stores.steam.enums.EPersonaState
import com.winlator.cmod.feature.stores.steam.enums.EResult
import com.winlator.cmod.feature.stores.steam.data.AsyncJobFailedException
import com.winlator.cmod.feature.stores.steam.data.GamePlayedInfo
import com.winlator.cmod.feature.stores.steam.data.PICSRequest
import com.winlator.cmod.feature.stores.steam.data.SteamID
import com.winlator.cmod.feature.stores.steam.utils.KeyValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.NullPointerException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Collections
import java.util.Date
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.seconds

// Session/logon diagnostics + installed-metadata recovery, split out of SteamService.kt (behavior-identical).

internal fun SteamService.Companion.recordLogonSuccess() {
    if (logonGateUntilMs != 0L || consecutiveLogonFailures != 0) {
        Timber.i("logon gate cleared (was open until ${logonGateUntilMs}, " +
            "$consecutiveLogonFailures prior failure(s))")
    }
    logonGateUntilMs = 0L
    lastLogonFailureEresult = 0
    consecutiveLogonFailures = 0
}

internal fun SteamService.Companion.recordLogonFailure(eresult: Int) {
    if (eresult == 1) return
    if (eresult == 67 || eresult == 88) return
    consecutiveLogonFailures += 1
    lastLogonFailureEresult = eresult
    val backoffMs = when (consecutiveLogonFailures) {
        1    -> 30_000L
        2    -> 120_000L
        3    -> 300_000L
        4    -> 900_000L
        5    -> 1_800_000L
        else -> 3_600_000L
    }
    logonGateUntilMs = System.currentTimeMillis() + backoffMs
    Timber.w("logon gate engaged: EResult=$eresult, " +
        "consecutive=$consecutiveLogonFailures, backoff=${backoffMs / 1000}s")
}

/** Tears down any prior wnSession at the top of every login entry so a retry doesn't leak the native handle (transport thread + heartbeat + TLS socket). */
internal fun SteamService.Companion.teardownPriorWnSession() {
    val prior = wnSession
    wnSession = null
    wnLoggedOnHandled = false
    wnLibraryMirrorJob?.cancel()
    wnLibraryMirrorJob = null
    wnLibrary?.stopObserving()
    wnLibrary = null
    if (prior != null) {
        Timber.i("Tearing down prior wnSession before relogin")
        try { prior.disconnect() } catch (_: Throwable) {}
        try { prior.close()      } catch (_: Throwable) {}
    }
}

internal fun SteamService.Companion.getInstalledSelectableDlcAppIds(appId: Int): Set<Int> =
    getSelectableDlcAppsOf(appId)
        .mapNotNull { dlcApp ->
            val dlcInfo = getInstalledApp(dlcApp.id)
            if (dlcInfo?.isDownloaded == true) dlcApp.id else null
        }.toSet()


internal fun SteamService.Companion.getTrustedInstalledAppInfo(appId: Int): AppInfo? {
    val appInfo = getInstalledApp(appId) ?: tryRecoverInstalledAppInfo(appId)
    if (appInfo?.isDownloaded != true) return null

    val dirPath = getAppDirPath(appId)
    val dir = File(dirPath)
    if (!dir.isDirectory) return null
    if (InstallOwnership.isForeign(dirPath, InstallStore.STEAM)) return null
    if (!MarkerUtils.hasMarker(dirPath, Marker.DOWNLOAD_COMPLETE_MARKER)) return null

    // Backfill the durable install path once (metadata present now) so recognition survives eviction.
    if (appInfo.installPath.isNullOrEmpty()) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                PluviaDatabase.getInstance().appInfoDao().update(appInfo.copy(installPath = dirPath))
            }
        }
        return appInfo.copy(installPath = dirPath)
    }
    return appInfo
}

internal fun SteamService.Companion.tryRecoverInstalledAppInfo(appId: Int): AppInfo? {
    val dirPath = getAppDirPath(appId)
    if (dirPath.isBlank()) return null
    if (InstallOwnership.isForeign(dirPath, InstallStore.STEAM)) return null
    val hasCompleteMarker = MarkerUtils.hasMarker(dirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
    val hasInProgressMarker = MarkerUtils.hasMarker(dirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
    if (!hasCompleteMarker || hasInProgressMarker) return null

    val dir = File(dirPath)
    if (!dir.exists() || !dir.isDirectory) return null

    val downloadedDepotIds = runCatching { getMainAppDepots(appId).keys.sorted() }.getOrDefault(emptyList())
    val installedDlcAppIds = getInstalledSelectableDlcAppIds(appId)
    val recovered =
        AppInfo(
            id = appId,
            isDownloaded = true,
            downloadedDepots = downloadedDepotIds,
            dlcDepots = installedDlcAppIds.sorted(),
            installPath = dirPath,
        )

    runBlocking(Dispatchers.IO) {
        PluviaDatabase.getInstance().appInfoDao().insert(recovered)
    }
    Timber.i("Recovered Steam installed metadata from disk for appId=$appId at $dirPath")
    return recovered
}

/** The chosen download folder is not a steamapps root, so it never appears in [allInstallPaths] and marker scans miss games installed there. */
internal fun SteamService.Companion.configuredDownloadRoot(): String? =
    runCatching {
        val context = PluviaApp.instance.applicationContext ?: return@runCatching null
        val storeDefaultUri =
            if (PrefManager.useSingleDownloadFolder) PrefManager.defaultDownloadFolder else PrefManager.steamDownloadFolder
        if (storeDefaultUri.isEmpty()) return@runCatching null
        com.winlator.cmod.shared.io.FileUtils
            .getFilePathFromUri(context, android.net.Uri.parse(storeDefaultUri))
    }.getOrNull()

internal fun SteamService.Companion.countCompletedInstallMarkers(maxCount: Int = Int.MAX_VALUE): Int {
    var count = 0
    for (basePath in (allInstallPaths + listOfNotNull(configuredDownloadRoot())).distinct()) {
        val baseDir = File(basePath)
        val appDirs = baseDir.listFiles() ?: continue
        for (appDir in appDirs) {
            if (!appDir.isDirectory) continue
            val hasCompleteMarker = File(appDir, Marker.DOWNLOAD_COMPLETE_MARKER.fileName).exists()
            if (!hasCompleteMarker) continue

            val hasInProgressMarker = File(appDir, Marker.DOWNLOAD_IN_PROGRESS_MARKER.fileName).exists()
            if (hasInProgressMarker) continue

            count++
            if (count >= maxCount) return count
        }
    }
    return count
}

internal fun SteamService.Companion.shouldRepairInstalledMetadata(): Boolean {
    val db =
        runCatching { PluviaDatabase.getInstance() }.getOrElse {
            Timber.e(it, "Failed to access database for startup metadata repair gate")
            return false
        }

    val knownAppCount =
        runBlocking(Dispatchers.IO) {
            runCatching { db.steamAppDao().getAllAppIds().size }.getOrElse {
                Timber.e(it, "Failed to load Steam app ids for startup metadata repair gate")
                return@runBlocking 0
            }
        }
    if (knownAppCount == 0) return false

    val installedDbCount =
        runBlocking(Dispatchers.IO) {
            runCatching { db.appInfoDao().getAllInstalledAppIds().size }.getOrElse {
                Timber.e(it, "Failed to load installed Steam app ids for startup metadata repair gate")
                return@runBlocking 0
            }
        }

    val diskInstallCount = countCompletedInstallMarkers(maxCount = installedDbCount + 1)
    return diskInstallCount > installedDbCount
}
