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
import com.winlator.cmod.feature.library.LosslessAutoImport
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

// Download finalize/verify/complete, split out of SteamService.kt (behavior-identical).

internal fun SteamService.Companion.finalizeSnapshotResumeAsComplete(
    appId: Int,
    appDirPath: String,
    mainAppDepots: Map<Int, DepotInfo>,
    dlcAppDepots: Map<Int, DepotInfo>,
    userSelectedDlcAppIds: List<Int>,
    branch: String = STEAM_DEFAULT_BRANCH,
): DownloadInfo {
    val downloadingAppIds = CopyOnWriteArrayList<Int>()
    val calculatedDlcAppIds = CopyOnWriteArrayList<Int>()
    val allDepotIdsByDlcAppId =
        dlcAppDepots.values
            .groupBy(keySelector = { it.dlcAppId }, valueTransform = { it.depotId })
            .mapValues { (_, depotIds) -> depotIds.sorted() }

    userSelectedDlcAppIds.forEach { dlcAppId ->
        if (allDepotIdsByDlcAppId[dlcAppId]?.isNotEmpty() == true) {
            downloadingAppIds.add(dlcAppId)
            calculatedDlcAppIds.add(dlcAppId)
        }
    }

    if (mainAppDepots.isNotEmpty() && !downloadingAppIds.contains(appId)) {
        downloadingAppIds.add(appId)
    }

    val info = DownloadInfo(1, appId, downloadingAppIds)
    info.branch = branch
    info.setPersistencePath(appDirPath)
    info.updateStatus(DownloadPhase.COMPLETE)
    info.setProgress(1f)
    downloadJobs[appId] = info
    notifyDownloadStarted(appId)

    val selectedDlcAppIdSet = userSelectedDlcAppIds.toSet()
    val mainAppDlcIds =
        getMainAppDlcIdsWithoutProperDepotDlcIds(appId)
            .filterTo(mutableListOf()) { it in selectedDlcAppIdSet }
    mainAppDlcIds.addAll(
        mainAppDepots.values
            .map { it.dlcAppId }
            .filter { it != INVALID_APP_ID && it in selectedDlcAppIdSet }
            .distinct(),
    )
    if (dlcAppDepots.isEmpty()) {
        mainAppDlcIds.addAll(
            mainAppDepots
                .filter { it.value.dlcAppId != INVALID_APP_ID && it.value.dlcAppId in selectedDlcAppIdSet }
                .map { it.value.dlcAppId }
                .distinct(),
        )
    }
    mainAppDlcIds.addAll(calculatedDlcAppIds.filter { it !in mainAppDlcIds })

    runBlocking(Dispatchers.IO) {
        if (mainAppDepots.isNotEmpty()) {
            completeAppDownload(
                downloadInfo = info,
                downloadingAppId = appId,
                entitledDepotIds = mainAppDepots.keys.sorted(),
                selectedDlcAppIds = mainAppDlcIds,
                appDirPath = appDirPath,
            )
        }

        calculatedDlcAppIds.forEach { dlcAppId ->
            val dlcDepotIds = allDepotIdsByDlcAppId[dlcAppId].orEmpty()
            completeAppDownload(
                downloadInfo = info,
                downloadingAppId = dlcAppId,
                entitledDepotIds = dlcDepotIds,
                selectedDlcAppIds = emptyList(),
                appDirPath = appDirPath,
            )
        }

        instance?.downloadingAppInfoDao?.deleteApp(appId)
        Unit
    }

    // Show success message to user for no-op/resume completion
    instance?.let { service ->
        service.scope.launch(Dispatchers.Main) {
            WinToast.show(service.applicationContext, "Download complete", Toast.LENGTH_SHORT)
            Unit
        }
    }
    return info
}

/** Returns one description per [expectedManifestByDepot] entry not finished at the expected manifest id in depot.config (missing, in-progress, or wrong manifest); an absent/unreadable config passes (legacy installs predate it). */
/** Depot ids Steam denied a key for on the last native run (.DepotDownloader/denied.depots). */
internal fun SteamService.Companion.readDeniedDepots(appDirPath: String): Set<Int> =
    runCatching {
        val file = File(File(appDirPath, ".DepotDownloader"), "denied.depots")
        if (!file.isFile) return@runCatching emptySet<Int>()
        file.readLines().mapNotNull { it.trim().toIntOrNull() }.toSet()
    }.getOrDefault(emptySet())


internal fun SteamService.Companion.verifyDepotConfigComplete(
    appDirPath: String,
    expectedManifestByDepot: Map<Int, Long>,
): List<String> {
    if (expectedManifestByDepot.isEmpty()) return emptyList()
    val configFile = File(File(appDirPath, ".DepotDownloader"), "depot.config")
    val installed: Map<Int, Long>? =
        runCatching {
            if (!configFile.isFile) return@runCatching null
            val ids = JSONObject(configFile.readText()).optJSONObject("installedManifestIDs")
                ?: return@runCatching emptyMap<Int, Long>()
            buildMap {
                for (key in ids.keys()) {
                    val depotId = key.toIntOrNull() ?: continue
                    put(depotId, ids.optLong(key, 0L))
                }
            }
        }.getOrNull()

    if (installed == null) {
        Timber.w(
            "Completeness gate: depot.config missing/unreadable at $configFile for " +
                "${expectedManifestByDepot.size} expected depot(s); treating as pass (legacy install?)",
        )
        return emptyList()
    }

    val invalidManifestId = 0x7fffffffffffffffL
    val failures = mutableListOf<String>()
    for ((depotId, expectedGid) in expectedManifestByDepot.toSortedMap()) {
        when (val recorded = installed[depotId]) {
            null -> failures.add("depot $depotId missing (expected manifest $expectedGid)")
            invalidManifestId -> failures.add("depot $depotId still in-progress (expected $expectedGid)")
            expectedGid -> Unit
            else -> failures.add("depot $depotId recorded at manifest $recorded, expected $expectedGid")
        }
    }
    if (failures.isEmpty()) {
        Timber.i(
            "Completeness gate passed: ${expectedManifestByDepot.size} depot(s) fully recorded in depot.config at $appDirPath",
        )
    }
    return failures
}

/** Read-only diagnostic: logs each base depot of [appId], whether it reached [selectedDepots] and the first rule that dropped it, warning when a base content depot is dropped. */
internal fun SteamService.Companion.logDepotScopeDiagnostics(
    appId: Int,
    branch: String,
    selectedDepots: Map<Int, DepotInfo>,
) {
    runCatching {
        val appInfo = getAppInfoOf(appId) ?: return
        val preferredLanguage = PrefManager.containerLanguage
        val entitledDepotIds = getEntitledDepotIds(appInfo.packageId)
        val has64Bit =
            appInfo.depots.values.any {
                it.osArch == OSArch.Arch64 &&
                    (it.osList.contains(OS.windows) || it.osList.isEmpty() || it.osList.contains(OS.none))
            }
        val groupedBaseDlcDepotIds = getGroupedBaseAppDlcContentDepotIds(appInfo)
        val baseEntitledDepotIds = getEntitledDepotIds(appInfo.packageId).orEmpty()

        fun exclusionReason(depotId: Int, depot: DepotInfo): String {
            if (depot.manifests.isEmpty() && depot.encryptedManifests.isNotEmpty()) return "encrypted-only-manifest"
            if (depot.manifests.isEmpty() && !depot.sharedInstall) return "no-manifest"
            if (resolveDepotManifestInfo(depot, branch) == null) return "manifest-unresolved(branch=$branch)"
            val osOk =
                depot.osList.contains(OS.windows) ||
                    (!depot.osList.contains(OS.linux) && !depot.osList.contains(OS.macos))
            if (!osOk) return "os-excluded(osList=${depot.osList})"
            val archOk =
                when (depot.osArch) {
                    OSArch.Arch64, OSArch.Unknown -> true
                    OSArch.Arch32 -> !has64Bit
                    else -> false
                }
            if (!archOk) return "arch-excluded(osArch=${depot.osArch},has64Bit=$has64Bit)"
            if (depot.language.isNotEmpty() && !depot.language.equals(preferredLanguage, ignoreCase = true)) {
                return "language-mismatch(depot='${depot.language}',preferred='$preferredLanguage')"
            }
            if (!isDepotEntitled(depotId, depot, entitledDepotIds)) return "not-entitled"
            if (depotId in groupedBaseDlcDepotIds && depotId !in baseEntitledDepotIds) return "grouped-as-dlc-content"
            return "excluded-outside-base-filters"
        }

        val baseDepots = appInfo.depots.filter { it.value.dlcAppId == INVALID_APP_ID }
        var maxBaseContentBytes = 0L
        var selectedBaseBytes = 0L
        val droppedBaseContent = mutableListOf<String>()
        Timber.i(
            "DEPOT-DIAG appId=$appId branch=$branch baseDepots=${baseDepots.size} " +
                "selected=${selectedDepots.size} has64Bit=$has64Bit preferredLang='$preferredLanguage' " +
                "entitled=${entitledDepotIds?.sorted()} groupedAsDlc=${groupedBaseDlcDepotIds.sorted()}",
        )
        for ((depotId, depot) in baseDepots) {
            val manifest = resolveDepotManifestInfo(depot, branch)
            val size = manifest?.size ?: 0L
            val included = depotId in selectedDepots
            val reason = if (included) null else exclusionReason(depotId, depot)
            if (manifest != null) maxBaseContentBytes += size
            if (included) {
                selectedBaseBytes += size
            } else if (manifest != null && size > 0L) {
                droppedBaseContent.add("depot=$depotId size=$size reason=$reason")
            }
            Timber.i(
                "DEPOT-DIAG  base depot=$depotId included=$included size=$size gid=${manifest?.gid} " +
                    "osList=${depot.osList} osArch=${depot.osArch} lang='${depot.language}' " +
                    "shared=${depot.sharedInstall} fromApp=${depot.depotFromApp}" +
                    (if (reason != null) " DROP=$reason" else ""),
            )
        }
        if (droppedBaseContent.isNotEmpty()) {
            Timber.w(
                "DEPOT-DIAG appId=$appId DROPPED ${droppedBaseContent.size} base content depot(s): " +
                    "selectedBaseBytes=$selectedBaseBytes maxBaseContentBytes=$maxBaseContentBytes " +
                    "(maxBase double-counts redundant 32/64-bit variants) -> $droppedBaseContent",
            )
        }
    }.onFailure { e -> Timber.w(e, "DEPOT-DIAG failed for appId=$appId") }
}

internal suspend fun SteamService.Companion.completeAppDownload(
    downloadInfo: DownloadInfo,
    downloadingAppId: Int,
    entitledDepotIds: List<Int>,
    selectedDlcAppIds: List<Int>,
    appDirPath: String,
) {
    Timber.i("Item $downloadingAppId download completed, saving database")
    Timber.i(
        "Steam DLC downloaded item: baseAppId=${downloadInfo.gameId} completedAppId=$downloadingAppId " +
            "entitledDepotIds=${entitledDepotIds.sorted()} selectedDlcAppIds=${selectedDlcAppIds.sorted()} " +
            "remainingAppIds=${downloadInfo.downloadingAppIds.sorted()}",
    )

    // runCatching: a transient Room failure on one DLC row shouldn't FAIL the whole download — bytes are on disk; stale-metadata recovery fixes the row on next launch.
    runCatching {
        val appInfo = instance?.appInfoDao?.getInstalledApp(downloadingAppId)
        if (appInfo != null) {
            val updatedDownloadedDepots = (appInfo.downloadedDepots + entitledDepotIds).distinct()
            val updatedDlcDepots = (appInfo.dlcDepots + selectedDlcAppIds).distinct()

            instance?.appInfoDao?.update(
                AppInfo(
                    downloadingAppId,
                    isDownloaded = true,
                    downloadedDepots = updatedDownloadedDepots.sorted(),
                    dlcDepots = updatedDlcDepots.sorted(),
                ),
            )
        } else {
            instance?.appInfoDao?.insert(
                AppInfo(
                    downloadingAppId,
                    isDownloaded = true,
                    downloadedDepots = entitledDepotIds.sorted(),
                    dlcDepots = selectedDlcAppIds.sorted(),
                ),
            )
        }
    }.onFailure { e ->
        Timber.e(
            e,
            "DB write failed for completed item $downloadingAppId (baseApp=${downloadInfo.gameId}); " +
                "files are on disk, continuing finalize anyway.",
        )
    }

    // Remove completed appId from downloadInfo.dlcAppIds and check if it was actually removed
    val wasRemoved = downloadInfo.downloadingAppIds.remove(downloadingAppId)
    if (!wasRemoved) {
        Timber.d("Item $downloadingAppId was already removed from downloading list, skipping redundant completion.")
        return
    }

    // All downloading appIds are removed
    if (downloadInfo.downloadingAppIds.isEmpty()) {
        Timber.i("All items for game ${downloadInfo.gameId} completed, running final completion logic.")
        Timber.i(
            "Steam DLC download complete: appId=${downloadInfo.gameId} " +
                "downloadedBytes=${downloadInfo.getBytesDownloaded()} totalBytes=${downloadInfo.getTotalExpectedBytes()}",
        )
        // Settle remaining bytes at the end so progress doesn't sit under 100% when complete (e.g. dedup-skipped chunks that never reported via onChunkCompleted).
        val totalExpectedBytes = downloadInfo.getTotalExpectedBytes()
        if (totalExpectedBytes > 0L) {
            val downloadedBytes = downloadInfo.getBytesDownloaded()
            val remainingBytes = (totalExpectedBytes - downloadedBytes).coerceAtLeast(0L)
            if (remainingBytes > 0L) {
                downloadInfo.updateBytesDownloaded(remainingBytes, System.currentTimeMillis())
                downloadInfo.emitProgressChange()
                updateCoordinatorDownloadProgress(downloadInfo)
            }
        }

        // Defensive wrapping per marker — bytes are on disk, a single marker/DB write failing shouldn't flip the game to FAILED.
        withContext(Dispatchers.IO) {
            val markerAdded =
                runCatching { MarkerUtils.addMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER) }
                    .getOrElse { e ->
                        Timber.e(e, "Failed to add DOWNLOAD_COMPLETE_MARKER at $appDirPath")
                        false
                    }
            if (!markerAdded) {
                Timber.e(
                    "DOWNLOAD_COMPLETE_MARKER write returned false for appId=${downloadInfo.gameId} at $appDirPath " +
                        "(disk full / permissions?). Game files are on disk but next launch may re-validate.",
                )
            }
            runCatching { InstallOwnership.claim(appDirPath, InstallStore.STEAM) }
            recordInstalledBranch(downloadInfo.gameId, downloadInfo.branch)
            runCatching { MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER) }
            runCatching { MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED) }
            runCatching { MarkerUtils.removeMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED) }
            runCatching { MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DRM_PATCHED) }
            runCatching { MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DRM_UNPACK_CHECKED) }

            // Same reason as above: a Room exception here used to FAIL a fully-downloaded game with the COMPLETE marker already on disk.
            val mainAppId = downloadInfo.gameId
            val service = instance
            if (service != null) {
                runCatching {
                    val mainAppInfo = service.appInfoDao.getInstalledApp(mainAppId)
                    if (mainAppInfo != null) {
                        val updatedMainDlcDepots =
                            (mainAppInfo.dlcDepots + selectedDlcAppIds).distinct().sorted()
                        service.appInfoDao.update(
                            mainAppInfo.copy(
                                isDownloaded = true,
                                dlcDepots = updatedMainDlcDepots,
                                installPath = appDirPath,
                            ),
                        )
                        Timber.i(
                            "Marked main app $mainAppId as downloaded in DB with dlcDepots=$updatedMainDlcDepots",
                        )
                    } else {
                        service.appInfoDao.insert(
                            AppInfo(
                                mainAppId,
                                isDownloaded = true,
                                dlcDepots = selectedDlcAppIds.distinct().sorted(),
                                installPath = appDirPath,
                            ),
                        )
                        Timber.i(
                            "Inserted main app $mainAppId as downloaded in DB with dlcDepots=${selectedDlcAppIds.distinct().sorted()}",
                        )
                    }
                }.onFailure { e ->
                    Timber.e(
                        e,
                        "Database write failed during finalize for appId=$mainAppId — bytes are on disk, " +
                            "marker write ${if (markerAdded) "succeeded" else "FAILED"}; download will still be marked COMPLETE.",
                    )
                }
            }
            Unit
        }

        val service = instance
        if (service != null) {
            createSteamShortcut(service, downloadInfo.gameId)
        }

        // Mark inactive BEFORE updating status so checkQueue() frees this slot — else isActive() stays true and blocks the queue until manually cleared.
        downloadInfo.setActive(false)
        downloadInfo.updateStatus(DownloadPhase.COMPLETE)
        PluviaApp.events.emit(AndroidEvent.LibraryInstallStatusChanged(downloadInfo.gameId))

        if (downloadInfo.gameId == LosslessAutoImport.STEAM_APP_ID) {
            instance?.let { context ->
                Thread {
                    val outcome = runCatching { LosslessAutoImport.sync(context) }.getOrNull()
                    Timber.i("Lossless Scaling finished downloading; shader import result=${outcome?.result}")
                }.start()
            }
        }

        downloadInfo.clearPersistedBytesDownloaded(appDirPath, sync = true)
        // Notify the coordinator to advance the cross-store queue and persist COMPLETE.
        runBlocking {
            DownloadCoordinator.notifyFinished(
                DownloadRecord.STORE_STEAM,
                downloadInfo.gameId.toString(),
                DownloadRecord.STATUS_COMPLETE,
            )
        }
        checkQueue()
    }
    Unit
}

internal fun SteamService.Companion.updateCoordinatorDownloadProgress(downloadInfo: DownloadInfo) {
    val (displayDownloadedBytes, displayTotalBytes) = downloadInfo.getDisplayBytesProgress()
    DownloadCoordinator.updateProgress(
        DownloadRecord.STORE_STEAM,
        downloadInfo.gameId.toString(),
        displayDownloadedBytes,
        displayTotalBytes,
    )
}
