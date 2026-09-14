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

// Full app-download/verify/update implementation (11-arg overload), split out of SteamService.kt (behavior-identical).

internal fun SteamService.Companion.downloadApp(
    appId: Int,
    downloadableDepots: Map<Int, DepotInfo>,
    userSelectedDlcAppIds: List<Int>,
    branch: String,
    includeInstalledDepots: Boolean,
    enableVerify: Boolean,
    allowPersistedProgress: Boolean = false,
    hasPersistedResumeRow: Boolean = false,
    customInstallPath: String? = null,
    downloadTaskType: String = DownloadRecord.TASK_INSTALL,
    targetDepotIds: Set<Int>? = null,
): DownloadInfo? {
    var appDirPath = getAppDirPath(appId)
    Timber.i("downloadApp called for appId: $appId, customInstallPath: $customInstallPath")
    Timber.i(
        "Steam DLC selection: appId=$appId selectedDlcAppIds=${userSelectedDlcAppIds.sorted()} " +
            "includeInstalledDepots=$includeInstalledDepots verify=$enableVerify allowResume=$allowPersistedProgress " +
            "targetDepotIds=${targetDepotIds?.sorted().orEmpty()}",
    )

    activeDownloadRecordFor(appId)?.let { activeRecord ->
        val requestedScopeIds =
            if (downloadTaskType == DownloadRecord.TASK_UPDATE && targetDepotIds != null) {
                targetDepotIds
            } else {
                userSelectedDlcAppIds.toSet()
            }
        val isSameCoordinatorDispatch =
            customInstallPath == null &&
                activeRecord.taskType == downloadTaskType &&
                parseDownloadScopeIds(activeRecord.selectedDlcs) == requestedScopeIds
        if (!isSameCoordinatorDispatch) {
            return rejectConflictingDownloadRequest(appId, activeRecord)
        }
    }

    if (customInstallPath != null) {
        val appInfo = getAppInfoOf(appId)
        val folderName = getAppDirName(appInfo)
        val safeFolderName = if (folderName.isNotEmpty()) folderName else appId.toString()

        val customFile = File(customInstallPath)
        val finalPath =
            if (customFile.name.equals(safeFolderName, ignoreCase = true)) {
                // User selected the game folder itself
                normalizeInstallPath(customFile.absolutePath)
            } else {
                // User selected parent folder, create/use subfolder
                normalizeInstallPath(File(customInstallPath, safeFolderName).absolutePath)
            }

        appDirPath = finalPath
        Timber.i("Final custom appDirPath: $appDirPath")

        runBlocking {
            if (appInfo != null) {
                val updatedApp = appInfo.copy(installDir = finalPath)
                instance?.appDao?.update(updatedApp)
                Timber.i("Updated SteamApp installDir in DB to: $finalPath")
            }
        }
    }

    val hasTrustedInstallAtStart =
        customInstallPath == null &&
            getTrustedInstalledAppInfo(appId) != null
    val isAddingDlcToTrustedInstall =
        hasTrustedInstallAtStart &&
            !includeInstalledDepots &&
            userSelectedDlcAppIds.isNotEmpty()

    // Ensure the download directory exists
    try {
        val dir = File(appDirPath)
        if (!dir.exists()) {
            if (dir.mkdirs()) {
                Timber.i("Created download directory: $appDirPath")
            } else {
                Timber.e("Failed to create download directory (mkdirs returned false): $appDirPath")
                instance?.let { service ->
                    service.scope.launch(Dispatchers.Main) {
                        WinToast.show(
                            service.applicationContext,
                            "Failed to create download directory. Check permissions.",
                            Toast.LENGTH_LONG,
                        )
                    }
                }
                return null
            }
        }

        if (!MarkerUtils.addMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)) {
            Timber.e("Failed to add DOWNLOAD_IN_PROGRESS_MARKER at $appDirPath")
        }

        // Fresh installs reset completion state; when the base is already trusted, keep the marker while adding DLC so a cancelled DLC download doesn't make the base look missing.
        if (downloadTaskType == DownloadRecord.TASK_UPDATE) {
            MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
        } else if (!includeInstalledDepots && !hasTrustedInstallAtStart) {
            MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
        }
    } catch (e: Exception) {
        Timber.e(e, "Error preparing download directory or markers: $appDirPath")
    }

    // If a custom path is provided, we want to force a new download at that location
    if (customInstallPath != null) {
        Timber.i("Custom path provided, cancelling any existing job for appId: $appId")
        downloadJobs[appId]?.cancel("Restarting download at custom path")
        downloadJobs.remove(appId)
    } else {
        // Only return existing job if it's still active
        val existingJob = downloadJobs[appId]
        if (existingJob != null && existingJob.isActive()) {
            Timber.i("Returning existing active download job for appId: $appId")
            return existingJob
        }
    }

    Timber.d("Checking depots for appId: $appId. downloadableDepots count: ${downloadableDepots.size}")
    if (downloadableDepots.isEmpty()) {
        Timber.w("Download aborted: downloadableDepots is empty for appId: $appId")
        return null
    }

    val indirectDlcAppIds = getDownloadableDlcAppsOf(appId).orEmpty().map { it.id }
    Timber.d("Indirect DLC app IDs for appId $appId: $indirectDlcAppIds")

    // Depots from Main game
    val mainDepots = getMainAppDepots(appId)
    val appInfoForDownload = getAppInfoOf(appId)
    val groupedBaseDlcDepotIds =
        appInfoForDownload
            ?.let { getGroupedBaseAppDlcContentDepotIds(it) }
            .orEmpty()
    // Base-package-entitled depots are base content; never let positional DLC grouping drop them (e.g. DMC5 601151/2) — that zeroes the downloads-tab size.
    val baseEntitledDepotIds =
        appInfoForDownload?.packageId?.let { getEntitledDepotIds(it) }.orEmpty()
    Timber.d("Main app depots count: ${mainDepots.size}")
    val baseMainAppDepots =
        if (isAddingDlcToTrustedInstall) {
            Timber.i(
                "Building DLC-only Steam download scope for installed appId=$appId " +
                    "selectedDlcAppIds=${userSelectedDlcAppIds.sorted()}",
            )
            emptyMap()
        } else {
            mainDepots.filter { (depotId, depot) ->
                depot.dlcAppId == INVALID_APP_ID &&
                    (depotId !in groupedBaseDlcDepotIds || depotId in baseEntitledDepotIds)
            }
        }
    val targetDepotIdSet = targetDepotIds?.takeIf { it.isNotEmpty() }
    var originalMainAppDepots =
        baseMainAppDepots +
            mainDepots.filter { (_, depot) ->
                userSelectedDlcAppIds.contains(depot.dlcAppId) &&
                    resolveDepotManifestInfo(depot, branch) != null
            } +
            getSelectedBaseAppDlcContentDepots(
                appId = appId,
                selectedDlcAppIds = userSelectedDlcAppIds,
                preferredLanguage = PrefManager.containerLanguage,
                branch = branch,
            )
    if (targetDepotIdSet != null) {
        originalMainAppDepots = originalMainAppDepots.filterKeys { it in targetDepotIdSet }
    }
    var mainAppDepots = originalMainAppDepots
    Timber.d("Filtered main app depots count: ${mainAppDepots.size}")

    // Depots from indirect DLC apps (reachable via findDownloadableDLCApps, which needs a cached license row).
    val indirectDlcAppDepots =
        downloadableDepots.filter { (_, depot) ->
            !mainAppDepots.map { it.key }.contains(depot.depotId) &&
                userSelectedDlcAppIds.contains(depot.dlcAppId) &&
                indirectDlcAppIds.contains(depot.dlcAppId) &&
                resolveDepotManifestInfo(depot, branch) != null
        }
    Timber.d("Filtered indirect DLC app depots count: ${indirectDlcAppDepots.size}")

    // Selected DLCs whose depots aren't reachable via indirectDlcAppIds (stale license row, or DLC declared on the base game) — look them up by appId so the download matches getDlcOnlyManifestSizes and the estimate/downloads-tab stay in sync.
    val coveredDlcAppIds =
        (originalMainAppDepots.values.asSequence() + indirectDlcAppDepots.values.asSequence())
            .mapNotNull { d -> d.dlcAppId.takeIf { it != INVALID_APP_ID } }
            .toSet()
    val missingDlcAppIds = userSelectedDlcAppIds.filter { it !in coveredDlcAppIds }
    val extraDlcAppDepots: Map<Int, DepotInfo> =
        if (missingDlcAppIds.isEmpty()) {
            emptyMap()
        } else {
            val appInfoForArch = getAppInfoOf(appId)
            val extraHas64Bit =
                appInfoForArch?.depots?.values?.any {
                    it.osArch == OSArch.Arch64 &&
                        (it.osList.contains(OS.windows) || it.osList.isEmpty() || it.osList.contains(OS.none))
                } ?: false
            val extraLanguage = PrefManager.containerLanguage
            val coveredDepotIds = originalMainAppDepots.keys + indirectDlcAppDepots.keys
            val collected = mutableMapOf<Int, DepotInfo>()
            for (dlcAppId in missingDlcAppIds) {
                // Only recover depots for DLC the account owns; otherwise Steam denies the key.
                val ownsDlc =
                    runBlocking(Dispatchers.IO) {
                        (instance?.licenseDao?.countLicensesForApp(dlcAppId) ?: 0) > 0
                    }
                if (!ownsDlc) {
                    Timber.i("Skipping recovery depots for unowned DLC appId=$dlcAppId")
                    continue
                }
                val dlcAppInfo =
                    runBlocking(Dispatchers.IO) { instance?.appDao?.findApp(dlcAppId) }
                        ?: continue
                for ((depotId, depot) in dlcAppInfo.depots) {
                    if (depotId in coveredDepotIds || depotId in collected) continue
                    if (!filterForDownloadableDepots(depot, extraHas64Bit, extraLanguage, ownedDlc = null)) continue
                    if (resolveDepotManifestInfo(depot, branch) == null) continue
                    collected[depotId] =
                        DepotInfo(
                            depotId = depot.depotId,
                            dlcAppId = dlcAppId,
                            optionalDlcId = depot.optionalDlcId,
                            depotFromApp = depot.depotFromApp,
                            sharedInstall = depot.sharedInstall,
                            osList = depot.osList,
                            osArch = depot.osArch,
                            language = depot.language,
                            lowViolence = depot.lowViolence,
                            manifests = depot.manifests,
                            encryptedManifests = depot.encryptedManifests,
                        )
                }
            }
            collected
        }
    if (extraDlcAppDepots.isNotEmpty()) {
        Timber.d("Recovered ${extraDlcAppDepots.size} extra DLC depots for selected DLCs ${missingDlcAppIds}")
    }
    // Single combined view of DLC depots (indirect + extras) used downstream for grouping, totals, and DownloadingAppInfo persistence — extras must be visible everywhere.
    val dlcAppDepots =
        (indirectDlcAppDepots + extraDlcAppDepots).let { depots ->
            if (targetDepotIdSet == null) depots else depots.filterKeys { it in targetDepotIdSet }
        }

    // Drop already-downloaded depots only when install metadata is trusted; a custom path re-checks/downloads everything at the new location.
    var installedApp = getInstalledApp(appId)
    val hasCompleteMarker = MarkerUtils.hasMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
    var hasTrustedInstalledState = installedApp?.isDownloaded == true && hasCompleteMarker
    if (!includeInstalledDepots && installedApp != null && !hasTrustedInstalledState && customInstallPath == null) {
        val hasStaleInstallMetadata =
            installedApp.isDownloaded ||
                installedApp.downloadedDepots.isNotEmpty() ||
                installedApp.dlcDepots.isNotEmpty()
        if (hasStaleInstallMetadata) {
            Timber.w(
                "Clearing stale install metadata for appId=$appId " +
                    "(isDownloaded=${installedApp.isDownloaded}, marker=$hasCompleteMarker)",
            )
            runBlocking(Dispatchers.IO) {
                instance?.appInfoDao?.deleteApp(appId)
            }
            installedApp = null
        }
        hasTrustedInstalledState = false
    }
    if (installedApp != null && !includeInstalledDepots && hasTrustedInstalledState && customInstallPath == null) {
        val beforeCount = mainAppDepots.size
        mainAppDepots = mainAppDepots.filter { it.key !in installedApp.downloadedDepots }
        Timber.d("Removed already downloaded depots. Count before: $beforeCount, after: ${mainAppDepots.size}")
    }

    // Resume support: .DepotDownloader/depot.config records depot state — finish_depot is written only after every file lands, so a "finished" entry always means a complete depot and is safe to keep across pause/resume (download() skips finished, write_depot() resumes the in-progress one chunk-by-chunk). Treat as "fresh" (discard depot.config) only for a brand-new install or a verify pass.
    val depotConfigFile = File(File(appDirPath, ".DepotDownloader"), "depot.config")
    val isFreshDownload =
        downloadTaskType == DownloadRecord.TASK_VERIFY || !depotConfigFile.exists()
    Timber.i(
        "Download fresh=$isFreshDownload for appId=$appId " +
            "(task=$downloadTaskType, depotConfigExists=${depotConfigFile.exists()})",
    )

    val allDepots = originalMainAppDepots + dlcAppDepots
    // Use install (uncompressed) size for progress; resolveDepotManifestInfo follows depot.depotFromApp and falls back to the public branch so shared/proxied DLC depots contribute their full size, not the 1L fallback.
    val depotSizeById =
        allDepots.mapValues { (_, depot) ->
            val mInfo = resolveDepotManifestInfo(depot, branch)
            (mInfo?.size ?: 1L).coerceAtLeast(1L)
        }

    // Mutable so the safety check below can drop suspicious entries before they poison di.depotCumulativeUncompressedBytes during resume init.
    var persistedDepotBytes: Map<Int, Long> =
        if (allowPersistedProgress) {
            DownloadInfo.loadPersistedDepotBytes(appDirPath)
        } else {
            emptyMap()
        }

    // Scope shrink (DLC de-selected, or Steam republished the depot list): drop orphan snapshot entries instead of refusing the resume; selectedDepots is built from current scope only, so partial-COMPLETE can't happen.
    if (allowPersistedProgress && persistedDepotBytes.isNotEmpty()) {
        val depotsInScope = allDepots.keys
        val orphanSnapshotDepots = persistedDepotBytes.keys - depotsInScope
        if (orphanSnapshotDepots.isNotEmpty()) {
            Timber.w(
                "Resume scope shrunk for appId=$appId: snapshot has depot(s) " +
                    "$orphanSnapshotDepots that are not in the current download scope " +
                    "(scope depots: $depotsInScope). Dropping orphan snapshot entries " +
                    "and continuing with in-scope depots.",
            )
            persistedDepotBytes = persistedDepotBytes.filterKeys { it in depotsInScope }
            DownloadInfo.persistDepotBytes(appDirPath, persistedDepotBytes)
        }
    }

    val fullyDownloadedDepotsFromSnapshot = mutableSetOf<Int>()
    if (persistedDepotBytes.isNotEmpty()) {
        for ((depotId, _) in allDepots) {
            val depotSize = depotSizeById[depotId] ?: 1L
            val downloadedBytes = persistedDepotBytes[depotId] ?: 0L
            Timber.d(
                "Resume snapshot for appId=$appId depot=$depotId: persisted=$downloadedBytes / size=$depotSize " +
                    (if (downloadedBytes >= depotSize) "-> SKIP-CANDIDATE" else "-> include"),
            )
            if (downloadedBytes >= depotSize) {
                fullyDownloadedDepotsFromSnapshot.add(depotId)
            }
        }
        if (fullyDownloadedDepotsFromSnapshot.isNotEmpty()) {
            // Trust the snapshot's "fully downloaded" claim only when the COMPLETE marker exists; without it the install was partial and snapshots have historically been corrupted to depotSize prematurely, so let per-file checksum validation handle resume instead.
            if (hasCompleteMarker) {
                Timber.i(
                    "Skipping ${fullyDownloadedDepotsFromSnapshot.size} fully downloaded depots from snapshot " +
                        "(COMPLETE marker present): $fullyDownloadedDepotsFromSnapshot",
                )
                mainAppDepots = mainAppDepots.filter { it.key !in fullyDownloadedDepotsFromSnapshot }
            } else {
                Timber.w(
                    "REFUSING to skip ${fullyDownloadedDepotsFromSnapshot.size} depots claimed full by snapshot " +
                        "for appId=$appId because COMPLETE marker is absent. Depots will be re-validated " +
                        "by the downloader; persisted byte counts are kept so the progress bar stays at the " +
                        "user's last position while verification confirms files on disk: " +
                        "$fullyDownloadedDepotsFromSnapshot",
                )
                // Keep persistedDepotBytes (the monotonic CAS in onProgress can't lower them, so the restored % shows during validation); just clear the skip set so the depots are re-validated.
                fullyDownloadedDepotsFromSnapshot.clear()
            }
        }
    }

    // Combine main app and DLC depots
    val filteredDlcAppDepots = dlcAppDepots.filter { it.key !in fullyDownloadedDepotsFromSnapshot }
    val selectedDepots = mainAppDepots + filteredDlcAppDepots
    Timber.i("Total selected depots for download: ${selectedDepots.size}")

    logDepotScopeDiagnostics(appId, branch, selectedDepots)

    if (selectedDepots.isEmpty()) {
        var preSnapshotMainAppDepots = originalMainAppDepots
        if (installedApp != null && !includeInstalledDepots && hasTrustedInstalledState) {
            preSnapshotMainAppDepots = preSnapshotMainAppDepots.filter { it.key !in installedApp.downloadedDepots }
        }
        val preSnapshotSelectedDepots = preSnapshotMainAppDepots + dlcAppDepots

        if (preSnapshotSelectedDepots.isEmpty()) {
            // Zero depots resolved: either (1) nothing selected and base already installed (genuine no-op complete), or (2) selected DLC(s) with no downloadable content (entitlement/branch-access DLCs, e.g. appid 373300) — case (2) must not silently show "Complete / 0 B".
            val selectedContentlessDlc = userSelectedDlcAppIds.isNotEmpty()
            Timber.i(
                "selectedDepots empty for appId=$appId — " +
                    if (selectedContentlessDlc) {
                        "selected DLC(s) $userSelectedDlcAppIds have no downloadable content"
                    } else {
                        "app already installed"
                    },
            )

            // Instead of returning null, create a completed job so it shows in UI
            val info = DownloadInfo(1, appId, CopyOnWriteArrayList(listOf(appId)))
            info.updateStatus(DownloadPhase.COMPLETE)
            info.setProgress(1f)
            downloadJobs[appId] = info

            if (allowPersistedProgress) {
                Timber.i("Resume became a no-op; clearing stale persisted resume state")
                clearFailedResumeState(appId)
            }

            MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            MarkerUtils.addMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            InstallOwnership.claim(appDirPath, InstallStore.STEAM)

            if (selectedContentlessDlc) {
                // Record content-less DLC(s) as installed so the picker shows "Installed" — owned but with nothing to download.
                runCatching {
                    runBlocking(Dispatchers.IO) {
                        val mainAppInfo = instance?.appInfoDao?.getInstalledApp(appId)
                        if (mainAppInfo != null) {
                            val updatedDlc =
                                (mainAppInfo.dlcDepots + userSelectedDlcAppIds)
                                    .distinct()
                                    .sorted()
                            instance?.appInfoDao?.update(
                                mainAppInfo.copy(dlcDepots = updatedDlc),
                            )
                            Timber.i(
                                "Marked content-less DLC(s) installed for appId=$appId: dlcDepots=$updatedDlc",
                            )
                        }
                    }
                }.onFailure { e ->
                    Timber.w(e, "Failed to record content-less DLC(s) for appId=$appId")
                }
            }

            // Honest message — don't claim a download happened when the selected DLC had no content to fetch.
            instance?.let { service ->
                service.scope.launch(Dispatchers.Main) {
                    if (selectedContentlessDlc) {
                        WinToast.show(
                            service.applicationContext,
                            "Selected DLC requires no download — marked installed",
                            Toast.LENGTH_LONG,
                        )
                    } else {
                        WinToast.show(
                            service.applicationContext,
                            "Download complete",
                            Toast.LENGTH_SHORT,
                        )
                    }
                }
            }

            return info
        }

        // Snapshot says all depots complete but marker missing — finalize metadata/markers directly instead of re-queuing depots.
        val canFinalizeFromSnapshot =
            allowPersistedProgress &&
                fullyDownloadedDepotsFromSnapshot.isNotEmpty() &&
                (hasCompleteMarker || hasPersistedResumeRow)
        if (canFinalizeFromSnapshot) {
            Timber.i("All resume depots appear complete from snapshot; finalizing without downloader")
            val info =
                finalizeSnapshotResumeAsComplete(
                    appId = appId,
                    appDirPath = appDirPath,
                    mainAppDepots = preSnapshotMainAppDepots,
                    dlcAppDepots = dlcAppDepots,
                    userSelectedDlcAppIds = userSelectedDlcAppIds,
                    branch = branch,
                )
            MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            return info
        } else {
            if (allowPersistedProgress) {
                if (fullyDownloadedDepotsFromSnapshot.isNotEmpty()) {
                    Timber.w(
                        "Snapshot indicates completion for appId=$appId but state is untrusted " +
                            "(marker=$hasCompleteMarker, resumeRow=$hasPersistedResumeRow); clearing resume metadata",
                    )
                } else {
                    Timber.i("selectedDepots resolved empty on resume; clearing stale resume metadata")
                }
                clearFailedResumeState(appId)
            } else {
                Timber.i("selectedDepots resolved empty after filtering; skipping download start")
            }
        }
        MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
        return null
    }

    val downloadingAppIds = CopyOnWriteArrayList<Int>()
    val calculatedDlcAppIds = CopyOnWriteArrayList<Int>()
    val allDepotIdsByDlcAppId =
        dlcAppDepots.values
            .groupBy(keySelector = { it.dlcAppId }, valueTransform = { it.depotId })
            .mapValues { (_, depotIds) -> depotIds.sorted() }
    val selectedDlcDepotIdsByDlcAppId =
        filteredDlcAppDepots.values
            .groupBy(keySelector = { it.dlcAppId }, valueTransform = { it.depotId })
            .mapValues { (_, depotIds) -> depotIds.sorted() }

    userSelectedDlcAppIds.forEach { dlcAppId ->
        if (allDepotIdsByDlcAppId[dlcAppId]?.isNotEmpty() == true) {
            downloadingAppIds.add(dlcAppId)
            calculatedDlcAppIds.add(dlcAppId)
        }
    }

    if (mainAppDepots.isNotEmpty()) {
        downloadingAppIds.add(appId)
    }

    // Some apps put DLC content under the base app with no dlcAppId on every depot; persist only DLC metadata in the selected scope, else marker-only DLCs get falsely saved as installed when a sibling DLC is selected.
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

    // If there are no DLC depots, download the main app only
    if (dlcAppDepots.isEmpty()) {
        // Because all dlcIDs are coming from main depots, need to add the dlcID to main app in order to save it to db after finish download
        mainAppDlcIds.addAll(
            mainAppDepots
                .filter { it.value.dlcAppId != INVALID_APP_ID && it.value.dlcAppId in selectedDlcAppIdSet }
                .map { it.value.dlcAppId }
                .distinct(),
        )
        // Entitlement/config DLCs have no downloadable depot but must still be remembered as selected/installed so launch metadata can expose them later.
        mainAppDlcIds.addAll(userSelectedDlcAppIds)

        // Refresh id List, so only main app is downloaded
        calculatedDlcAppIds.clear()
        downloadingAppIds.clear()
        downloadingAppIds.add(appId)
    }

    Timber.i("Starting download for $appId")
    Timber.i("App contains ${mainAppDepots.size} depot(s): ${mainAppDepots.keys}")
    Timber.i("DLC contains ${dlcAppDepots.size} depot(s): ${dlcAppDepots.keys}")
    Timber.i("downloadingAppIds: $downloadingAppIds")

    val service =
        instance ?: run {
            // Session gave up reconnecting and stopped the service; revive it and say so instead of failing silently.
            Timber.e("SteamService instance is null, cannot start download job.")
            MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            runCatching {
                val context = PluviaApp.instance.applicationContext ?: return@runCatching
                if (PrefManager.refreshToken.isNotBlank()) start(context)
                WinToast.show(
                    context,
                    "Steam is not connected — reconnecting, try the download again in a moment",
                    Toast.LENGTH_LONG,
                )
            }
            return null
        }

    val selectedDepotSizes =
        selectedDepots.mapValues { (depotId, _) ->
            depotSizeById[depotId] ?: 1L
        }
    val selectedTotalBytes = selectedDepotSizes.values.sum()
    val totalBytes = selectedTotalBytes.coerceAtLeast(1L)
    val selectedDisplayDownloadBytes =
        selectedDepots.values
            .sumOf { depot -> manifestDownloadBytes(resolveDepotManifestInfo(depot, branch)) }
            .takeIf { it > 0L }
            ?: totalBytes
    Timber.i(
        "Steam DLC selected download scope: appId=$appId selectedDlcAppIds=${userSelectedDlcAppIds.sorted()} " +
            "calculatedDlcAppIds=${calculatedDlcAppIds.sorted()} mainDepotIds=${mainAppDepots.keys.sorted()} " +
            "dlcDepotIdsByApp=$selectedDlcDepotIdsByDlcAppId totalBytes=$totalBytes " +
            "displayDownloadBytes=$selectedDisplayDownloadBytes metadataDlcAppIds=${mainAppDlcIds.sorted()}",
    )

    runBlocking {
        service.downloadingAppInfoDao.insert(
            DownloadingAppInfo(
                appId,
                dlcAppIds = userSelectedDlcAppIds,
            ),
        )
        Unit
    }
    Timber.i(
        "Steam DLC selection persisted: appId=$appId selectedDlcAppIds=${userSelectedDlcAppIds.sorted()} " +
            "installPath=$appDirPath",
    )

    // Ask the global coordinator whether this can start now or must queue behind other stores' downloads; it persists the decision in DownloadRecord so the request survives an app restart.
    val coordDecision =
        runBlocking {
            val title = getAppInfoOf(appId)?.name.orEmpty()
            val persistedScope =
                if (downloadTaskType == DownloadRecord.TASK_UPDATE && targetDepotIdSet != null) {
                    targetDepotIdSet.sorted().joinToString(",")
                } else {
                    userSelectedDlcAppIds.joinToString(",")
                }
            DownloadCoordinator.requestSlot(
                store = DownloadRecord.STORE_STEAM,
                storeGameId = appId.toString(),
                title = title,
                installPath = appDirPath,
                selectedDlcs = persistedScope,
                taskType = downloadTaskType,
                bytesTotal = selectedDisplayDownloadBytes,
            )
        }
    Timber.i(
        "Steam DLC coordinator record: appId=$appId selectedDlcAppIds=${userSelectedDlcAppIds.sorted()} " +
            "bytesTotal=$totalBytes displayDownloadBytes=$selectedDisplayDownloadBytes " +
            "decision=${coordDecision::class.simpleName}",
    )
    if (coordDecision is DownloadCoordinator.Decision.Queue) {
        Timber.i("Coordinator queued appId: $appId")
        if (downloadTaskType == DownloadRecord.TASK_UPDATE) {
            MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            MarkerUtils.addMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            InstallOwnership.claim(appDirPath, InstallStore.STEAM)
        }
        val info =
            DownloadInfo(selectedDepots.size, appId, downloadingAppIds).also { di ->
                di.branch = branch
                di.setPersistencePath(appDirPath)
                di.setTotalExpectedBytes(totalBytes)
                di.setDisplayTotalExpectedBytes(selectedDisplayDownloadBytes)
                di.updateStatus(DownloadPhase.QUEUED, "Queued")
                di.setActive(false)
            }
        downloadJobs[appId] = info
        notifyDownloadStarted(appId)
        return info
    }

    val info =
        DownloadInfo(selectedDepots.size, appId, downloadingAppIds).also { di ->
            di.branch = branch
            di.setPersistencePath(appDirPath)

            // Set weights for each depot based on manifest sizes
            selectedDepots.keys.forEachIndexed { index, depotId ->
                di.setWeight(index, selectedDepotSizes[depotId] ?: 1L)
            }

            // Track progress only for depots in this run so excluded/complete depots can't pre-fill progress at startup.

            // Total expected size (used for ETA based on recent download speed)
            di.setTotalExpectedBytes(totalBytes)
            di.setDisplayTotalExpectedBytes(selectedDisplayDownloadBytes)

            var resumedBytes = 0L

            if (allowPersistedProgress) {
                for ((depotId, bytes) in persistedDepotBytes) {
                    // Depot excluded as fully downloaded still needs its bytes tracked so future snapshots retain this progress.
                    val depotSize = depotSizeById[depotId] ?: continue
                    val safeBytes = bytes.coerceIn(0L, depotSize)
                    di.depotCumulativeUncompressedBytes[depotId] =
                        java.util.concurrent.atomic
                            .AtomicLong(safeBytes)
                    // Count resumed bytes only for depots actively downloading in this run.
                    if (depotId in selectedDepots) {
                        resumedBytes += safeBytes
                    }
                    Timber.i(
                        "RESUME-INIT depot=$depotId loaded=$safeBytes (snapshot=$bytes, max=$depotSize, " +
                            "inSelected=${depotId in selectedDepots}, inSession=${depotId in selectedDepotSizes.keys})",
                    )
                }
            } else {
                // SYNC clear so a stale snapshot from a prior session can't poison this fresh download — async clear races new persists that could read/overwrite with stale byte counts.
                di.clearPersistedBytesDownloaded(appDirPath, sync = true)
                Timber.i("RESUME-INIT cleared persisted snapshot (sync) for fresh download appId=$appId")
            }
            resumedBytes = resumedBytes.coerceIn(0L, totalBytes)

            if (resumedBytes > 0L) {
                di.initializeBytesDownloaded(resumedBytes)
                Timber.i("Resumed download: initialized with $resumedBytes bytes")
            }

            val downloadJob =
                service.scope.launch {
                    // Worker-local session brought up when no logged-on wnSession exists; NOT promoted to the global field so a concurrent logOut()/relogin can't close it mid-download. Disconnected + closed in this worker's finally.
                    var workerWnSession: WnSteamSession? = null
                    // Wi-Fi + CPU keep-alive: without it Wi-Fi PSP drops radio power on screen-off and router NAT evicts chunk sockets, surfacing as spurious "WN download failed" on stable Wi-Fi.
                    val keepAliveTag = "steam-download-$appId"
                    val keepAliveCtx = service.applicationContext
                    runCatching {
                        SessionKeepAliveService.startDownload(keepAliveCtx, keepAliveTag)
                    }.onFailure { e ->
                        Timber.w(e, "Failed to acquire keep-alive for Steam download $appId")
                    }
                    try {
                        // Retry loop for transient Steam API failures (AsyncJobFailedException) or missing client
                        val maxRetries = 3

                        for (attempt in 1..maxRetries) {
                            try {
                                if (attempt > 1) {
                                    Timber.i("Retry attempt $attempt/$maxRetries for appId: $appId")
                                    di.updateStatusMessage("Retrying download (attempt $attempt/$maxRetries)")
                                    withContext(Dispatchers.Main) {
                                        WinToast.show(
                                            instance?.applicationContext ?: return@withContext,
                                            "Retrying download (attempt $attempt/$maxRetries)",
                                            Toast.LENGTH_SHORT,
                                        )
                                    }
                                    kotlinx.coroutines.delay(3000L * attempt) // Exponential backoff
                                }

                                // Ensure a logged-on session (state()==3) for the download: prefer the long-lived wnSession, but it may not be logged on (cached-token restore, idled CM), so bring one up here if needed. The downloader requests depot keys itself.
                                var wnReady = wnSession?.takeIf { it.state() == 3 }
                                if (wnReady == null) {
                                    // Brief grace period: wnSession may be mid-logon.
                                    var grace = 0
                                    while (grace < 8 && wnSession?.state() != 3) {
                                        di.updateStatusMessage("Waiting for Steam connection")
                                        delay(1000L)
                                        grace++
                                    }
                                    wnReady = wnSession?.takeIf { it.state() == 3 }
                                }
                                if (wnReady == null) {
                                    // Reuse a session this worker brought up on a prior retry, if still logged on.
                                    wnReady = workerWnSession?.takeIf { it.state() == 3 }
                                }
                                if (wnReady == null) {
                                    Timber.i("downloadApp: no logged-on wnSession — bringing one up for the download")
                                    di.updateStatusMessage("Connecting to Steam")
                                    val svc = instance
                                        ?: throw Exception("Steam service unavailable.")
                                    val refreshTok = PrefManager.refreshToken
                                    if (refreshTok.isBlank()) {
                                        throw Exception(
                                            "Not logged in to Steam (no refresh token). " +
                                                "Please sign in and try again.",
                                        )
                                    }
                                    // Discard a worker session from a prior attempt that is no longer logged on.
                                    workerWnSession?.let { stale ->
                                        runCatching { stale.disconnect() }
                                        runCatching { stale.close() }
                                    }
                                    workerWnSession = null
                                    val brought = bringUpWnSession(svc)
                                        ?: throw Exception(
                                            "WN-Steam-Client: could not connect to Steam.",
                                        )
                                    workerWnSession = brought
                                    // Download-only session: skip the library-populate PICS crawl so it doesn't flood the CM while the download needs the channel for depot keys.
                                    brought.setAutoPopulateLibrary(false)
                                    di.updateStatusMessage("Logging in to Steam")
                                    if (!brought.logonWithRefreshToken(
                                            refreshTok,
                                            PrefManager.username,
                                            PrefManager.steamUserSteamId64,
                                        )
                                    ) {
                                        throw Exception("WN-Steam-Client: logon request failed.")
                                    }
                                    var logonWait = 0
                                    while (brought.state() != 3 && logonWait < 30) {
                                        delay(1000L)
                                        logonWait++
                                    }
                                    if (brought.state() != 3) {
                                        throw Exception(
                                            "WN-Steam-Client: could not log on to Steam. " +
                                                "Please check your connection.",
                                        )
                                    }
                                    wnReady = brought
                                    Timber.i("downloadApp: WN-Steam session logged on for download")
                                }

                                // Capture wnReady (a mutable var) once as a stable non-null handle for the download below.
                                val wnSessionForDownload = wnReady
                                    ?: throw Exception("WN-Steam-Client session unavailable.")

                                Timber.i("Initializing WN-Steam downloader for appId: $appId (attempt $attempt)")
                                di.updateStatusMessage("Initializing downloader")

                                // CA bundle for HTTPS CDN verification (same one CaBundleExtractor provides for the CM session).
                                val caPath = CaBundleExtractor.ensureBundle(
                                    instance?.applicationContext
                                        ?: throw Exception("Steam service unavailable"),
                                )

                                // Total expected bytes (drives di.getProgress()): use ManifestInfo.size (DECOMPRESSED) since onProgress reports decompressed bytes — the compressed .download size would overshoot 1.0.
                                val grandTotalBytes = selectedDepots.values.sumOf { depot ->
                                    resolveDepotManifestInfo(depot, branch)?.size ?: 0L
                                }
                                if (grandTotalBytes > 0L) di.setTotalExpectedBytes(grandTotalBytes)

                                // Native downloadApp() takes one appId (depot-key entitlement), so split into one batch per app — main app then each owned DLC. Triple = (appId, depotIds, manifestIds).
                                val wnBatches: List<Triple<Int, IntArray, LongArray>> = buildList {
                                    if (mainAppDepots.isNotEmpty()) {
                                        // Drop unresolvable depots (gid 0); sending manifest 0 aborts the native batch.
                                        val resolved = mainAppDepots.keys.sorted().mapNotNull { id ->
                                            val gid = resolveDepotManifestInfo(mainAppDepots[id]!!, branch)?.gid ?: 0L
                                            if (gid > 0L) {
                                                id to gid
                                            } else {
                                                Timber.w("Skipping main depot $id: unresolved manifest gid (branch=$branch)")
                                                null
                                            }
                                        }
                                        if (resolved.isNotEmpty()) {
                                            add(Triple(
                                                appId,
                                                resolved.map { it.first }.toIntArray(),
                                                resolved.map { it.second }.toLongArray(),
                                            ))
                                        }
                                    }
                                    calculatedDlcAppIds.forEach { dlcAppId ->
                                        val dlcDepotIds = selectedDlcDepotIdsByDlcAppId[dlcAppId].orEmpty()
                                        if (dlcDepotIds.isEmpty()) return@forEach
                                        val resolved = dlcDepotIds.mapNotNull { depotId ->
                                            val gid = selectedDepots[depotId]?.let { resolveDepotManifestInfo(it, branch)?.gid } ?: 0L
                                            if (gid > 0L) {
                                                depotId to gid
                                            } else {
                                                Timber.w("Skipping DLC depot $depotId (dlcAppId=$dlcAppId): unresolved manifest gid (branch=$branch)")
                                                null
                                            }
                                        }
                                        if (resolved.isEmpty()) return@forEach
                                        Timber.i("Steam DLC batch queued: dlcAppId=$dlcAppId depotIds=${resolved.map { it.first }}")
                                        add(Triple(
                                            dlcAppId,
                                            resolved.map { it.first }.toIntArray(),
                                            resolved.map { it.second }.toLongArray(),
                                        ))
                                    }
                                }
                                if (wnBatches.isEmpty()) {
                                    throw Exception("No depots resolved for download.")
                                }
                                Timber.i("WN download: ${wnBatches.size} app batch(es) to $appDirPath")

                                // Steam Controller Config download
                                val appConfig = getAppInfoOf(appId)?.config
                                if (appConfig?.steamControllerTemplateIndex == 1) {
                                    val controllerConfig =
                                        appConfig.steamControllerConfigDetails
                                            .let { selectSteamControllerConfig(it) }

                                    if (controllerConfig != null) {
                                        val publishedFileId = controllerConfig.publishedFileId
                                        runCatching {
                                            val requestBody =
                                                FormBody
                                                    .Builder()
                                                    .add(
                                                        "itemcount",
                                                        "1",
                                                    ).add("publishedfileids[0]", publishedFileId.toString())
                                                    .build()
                                            val request =
                                                Request
                                                    .Builder()
                                                    .url(
                                                        "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1",
                                                    ).post(requestBody)
                                                    .build()
                                            Net.http.newCall(request).execute().use { response ->
                                                if (response.isSuccessful) {
                                                    val responseBody = response.body?.string()
                                                    if (!responseBody.isNullOrEmpty()) {
                                                        val responseJson = JSONObject(responseBody)
                                                        val responseData = responseJson.optJSONObject("response")
                                                        val fileUrl =
                                                            responseData
                                                                ?.optJSONArray(
                                                                    "publishedfiledetails",
                                                                )?.optJSONObject(0)
                                                                ?.optString("file_url", "")
                                                                ?.trim()
                                                        if (!fileUrl.isNullOrEmpty()) {
                                                            val configFile = File(appDirPath, STEAM_CONTROLLER_CONFIG_FILENAME)
                                                            val downloadRequest =
                                                                Request
                                                                    .Builder()
                                                                    .url(fileUrl)
                                                                    .get()
                                                                    .build()
                                                            Net.http.newCall(downloadRequest).execute().use { downloadResponse ->
                                                                if (downloadResponse.isSuccessful) {
                                                                    downloadResponse.body?.byteStream()?.use { input ->
                                                                        configFile.outputStream().use { output ->
                                                                            input.copyTo(output)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Run each app batch through the native downloader (downloadApp() runs on a native worker thread); suspendCancellableCoroutine bridges WnDownloadListener.onComplete back here. Progress sets di's absolute byte count from the sum of every depot's cumulative bytes.
                                Timber.i("Downloading game to $appDirPath (attempt $attempt)")
                                val wnDepotBytes = java.util.concurrent.ConcurrentHashMap<Int, Long>()
                                for ((depotId, bytes) in di.depotCumulativeUncompressedBytes) {
                                    if (depotId in selectedDepots) {
                                        val initialBytes = bytes.get().coerceAtLeast(0L)
                                        if (initialBytes > 0L) {
                                            wnDepotBytes[depotId] = initialBytes
                                        }
                                    }
                                }
                                val wnGlobalPrev =
                                    java.util.concurrent.atomic.AtomicLong(wnDepotBytes.values.sum())
                                // Throttle DownloadRecord progress persistence.
                                val wnLastPersistMs = java.util.concurrent.atomic.AtomicLong(0L)
                                for (batch in wnBatches) {
                                    val (batchAppId, batchDepotIds, batchManifestIds) = batch
                                    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                                        wnSessionForDownload.downloadApp(
                                            batchAppId,
                                            batchDepotIds,
                                            batchManifestIds,
                                            branch,
                                            appDirPath,
                                            isFreshDownload,
                                            caPath,
                                            // "Download Speed" setting → parallel chunk-download worker count.
                                            PrefManager.downloadSpeed,
                                            object : WnDownloadListener {
                                                override fun onProgress(
                                                    depotId: Int,
                                                    depotDone: Long,
                                                    depotTotal: Long,
                                                    depotsDone: Int,
                                                    depotsTotal: Int,
                                                    verifying: Boolean,
                                                ) {
                                                    // The native worker may fire late callbacks after a pause/cancel (before it unwinds); ignore them or they'd overwrite PAUSED back to DOWNLOADING.
                                                    if (!di.isActive()) return
                                                    // Record per-depot cumulative bytes so the throttled snapshot (depot_bytes.json) restores the real % on resume; verification reports bytes from 0 each resume, so never let it lower a previously persisted count (quick pause/resume during VERIFYING would rewrite the snapshot to a partial scan).
                                                    val depotBytes =
                                                        di.depotCumulativeUncompressedBytes
                                                        .getOrPut(depotId) {
                                                            java.util.concurrent.atomic.AtomicLong(0L)
                                                        }
                                                    val observedDepotDone = depotDone.coerceAtLeast(0L)
                                                    var monotonicDepotDone: Long
                                                    while (true) {
                                                        val currentDepotDone = depotBytes.get()
                                                        monotonicDepotDone = maxOf(currentDepotDone, observedDepotDone)
                                                        if (monotonicDepotDone == currentDepotDone ||
                                                            depotBytes.compareAndSet(currentDepotDone, monotonicDepotDone)
                                                        ) {
                                                            break
                                                        }
                                                    }
                                                    wnDepotBytes[depotId] = monotonicDepotDone
                                                    di.markProgressSnapshotDirty()
                                                    val g = wnDepotBytes.values.sum()
                                                    val delta = g - wnGlobalPrev.getAndSet(g)
                                                    if (delta > 0L) di.updateBytesDownloaded(delta)
                                                    val statusTick =
                                                        if (verifying && observedDepotDone < monotonicDepotDone) {
                                                            "$g/$observedDepotDone"
                                                        } else {
                                                            g.toString()
                                                        }
                                                    // Phase from the native verifying flag: VERIFYING while validating on-disk content, DOWNLOADING while fetching. The status carries a unique suffix (g) each tick because a StateFlow dedups equal values, so a constant message would freeze the live byte count/speed — a changing one forces recomposition.
                                                    di.updateStatus(
                                                        if (verifying) {
                                                            DownloadPhase.VERIFYING
                                                        } else {
                                                            DownloadPhase.DOWNLOADING
                                                        },
                                                        if (verifying) {
                                                            "Verifying depot $depotId ($statusTick)"
                                                        } else {
                                                            "Downloading depot $depotId ($statusTick)"
                                                        },
                                                    )
                                                    di.emitProgressChange()
                                                    // Persist progress to the DownloadRecord (throttled 3s) so an app restart restores the real % instead of 0.
                                                    val nowMs = System.currentTimeMillis()
                                                    if (nowMs - wnLastPersistMs.get() >= 3000L) {
                                                        wnLastPersistMs.set(nowMs)
                                                        val (dispDone, dispTotal) =
                                                            di.getDisplayBytesProgress()
                                                        DownloadCoordinator.updateProgress(
                                                            DownloadRecord.STORE_STEAM,
                                                            appId.toString(),
                                                            dispDone,
                                                            dispTotal,
                                                        )
                                                    }
                                                }

                                                override fun onComplete(
                                                    success: Boolean,
                                                    error: String,
                                                    bytesWritten: Long,
                                                    depotsCompleted: Int,
                                                    depotsSkipped: Int,
                                                ) {
                                                    if (!cont.isActive) return
                                                    if (success) {
                                                        cont.resumeWith(Result.success(Unit))
                                                    } else if (!di.isActive() || di.isCancelling) {
                                                        // Pause/cancel aborted the native download — resume normally; the post-await barrier classifies it as PAUSED/CANCELLED, not a spurious FAILED.
                                                        cont.resumeWith(Result.success(Unit))
                                                    } else {
                                                        cont.resumeWith(
                                                            Result.failure(
                                                                WnDownloadTransientException(
                                                                    "WN download failed (app $batchAppId): $error",
                                                                ),
                                                            ),
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                        // Pause/cancel cancels this coroutine — abort the native worker so it stops promptly instead of running on in the background.
                                        cont.invokeOnCancellation {
                                            runCatching { wnSessionForDownload.cancelDownload() }
                                        }
                                    }
                                }

                                // Hard barrier: re-check cancellation even when the await returned cleanly — completion can fire as a side-effect of pending chunks being cancelled, and in that race we must NOT run completeAppDownload (it would set COMPLETE for a paused/partial install).
                                coroutineContext.ensureActive()
                                if (!di.isActive() || di.isCancelling) {
                                    Timber.i(
                                        "DepotDownloader completion returned but DownloadInfo is no longer active " +
                                            "(isActive=${di.isActive()}, isCancelling=${di.isCancelling}). " +
                                            "Skipping completeAppDownload — the user paused or cancelled.",
                                    )
                                    throw CancellationException(
                                        if (di.isCancelling) "Cancelled by user" else "Paused by user",
                                    )
                                }

                                Timber.i("DepotDownloader finished for appId: $appId")

                                // If it was extremely fast (e.g. already downloaded), ensure some visibility in UI
                                if (di.getProgress() >= 1.0f) {
                                    delay(1000)
                                }

                                // If we got here without exception, download succeeded
                                break
                            } catch (e: AsyncJobFailedException) {
                                Timber.w(e, "AsyncJobFailedException on attempt $attempt/$maxRetries for appId: $appId")
                                if (attempt >= maxRetries) {
                                    Timber.e("All $maxRetries retry attempts failed for appId: $appId")
                                    throw e
                                }
                                di.setActive(true)
                                continue
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                if (!di.isActive() || di.isCancelling) throw e
                                // Only retry the depot-transfer phase; entitlement/manifest/session errors won't fix themselves — fail fast.
                                if (e !is WnDownloadTransientException) throw e
                                Timber.w(e, "Transient WN download failure on attempt $attempt/$maxRetries for appId: $appId")
                                if (attempt >= maxRetries) {
                                    Timber.e("All $maxRetries retry attempts failed for appId: $appId")
                                    throw e
                                }
                                // Force-flush the byte snapshot so the next attempt resumes from the same offset instead of re-validating.
                                runCatching { di.persistProgressSnapshot(force = true) }
                                runCatching { updateCoordinatorDownloadProgress(di) }
                                // Failed batch's listener sets isActive=false; restore it so the next attempt's onProgress doesn't bail.
                                di.setActive(true)
                                continue
                            }
                        }

                        // Complete app download - Wrap in try-catch to ensure we don't crash at the finish line
                        try {
                            di.updateStatusMessage("Finalizing installation")
                            Timber.i("Finalizing installation at path: $appDirPath")

                            // Refuse to mark COMPLETE unless every depot fetched this run is recorded as finished at the expected manifest id in depot.config.
                            val deniedDepots = readDeniedDepots(appDirPath)
                            if (deniedDepots.isNotEmpty()) {
                                Timber.w(
                                    "Completeness gate excluding ${deniedDepots.size} depot(s) Steam denied " +
                                        "a key for appId=$appId: ${deniedDepots.sorted()}",
                                )
                            }
                            val expectedManifestByDepot =
                                selectedDepots.mapNotNull { (depotId, depot) ->
                                    // Steam-denied depots aren't part of this account's install.
                                    if (depotId in deniedDepots) return@mapNotNull null
                                    val gid = resolveDepotManifestInfo(depot, branch)?.gid ?: 0L
                                    if (gid > 0L) depotId to gid else null
                                }.toMap()
                            val completenessFailures =
                                verifyDepotConfigComplete(appDirPath, expectedManifestByDepot)
                            if (completenessFailures.isNotEmpty()) {
                                Timber.e(
                                    "COMPLETENESS GATE FAILED for appId=$appId task=$downloadTaskType at $appDirPath: " +
                                        "${completenessFailures.size}/${expectedManifestByDepot.size} depot(s) not fully " +
                                        "installed — refusing to mark COMPLETE. Details: ${completenessFailures.take(30)}",
                                )
                                // Keep resume state so a resume re-fetches the missing depots.
                                runCatching { di.persistProgressSnapshot(force = true) }
                                di.updateStatus(
                                    DownloadPhase.FAILED,
                                    "Install incomplete: ${completenessFailures.size} depot(s) missing — resume to finish",
                                )
                                di.setActive(false)
                                MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                                if (downloadTaskType == DownloadRecord.TASK_UPDATE) {
                                    MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                                }
                                runBlocking {
                                    DownloadCoordinator.notifyFinished(
                                        DownloadRecord.STORE_STEAM,
                                        appId.toString(),
                                        DownloadRecord.STATUS_FAILED,
                                        "incomplete: ${completenessFailures.size} depot(s) missing",
                                    )
                                }
                                removeDownloadJob(appId)
                                instance?.let { service ->
                                    service.scope.launch(Dispatchers.Main) {
                                        WinToast.show(
                                            service.applicationContext,
                                            "Download incomplete — some files are missing. Resume to finish.",
                                            Toast.LENGTH_LONG,
                                        )
                                    }
                                }
                                PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, false))
                                return@launch
                            }

                            if (originalMainAppDepots.isNotEmpty()) {
                                val mainAppDepotIds = originalMainAppDepots.keys.sorted()
                                completeAppDownload(di, appId, mainAppDepotIds, mainAppDlcIds, appDirPath)
                            }

                            calculatedDlcAppIds.forEach { dlcAppId ->
                                val dlcDepotIds = selectedDlcDepotIdsByDlcAppId[dlcAppId].orEmpty()
                                completeAppDownload(di, dlcAppId, dlcDepotIds, emptyList(), appDirPath)
                            }
                            Timber.i("Installation finalized for appId: $appId")

                            instance?.let { service ->
                                service.scope.launch(Dispatchers.Main) {
                                    WinToast.show(service.applicationContext, "Download complete", Toast.LENGTH_SHORT)
                                    Unit
                                }
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Error during finalize/database update for appId: $appId")
                            throw e
                        }

                        removeDownloadJob(appId)

                        runBlocking {
                            instance?.downloadingAppInfoDao?.deleteApp(appId)
                            Unit
                        }
                        Unit
                    } catch (e: DownloadFailedException) {
                        Timber.d(e, "Download failed for app $appId via cancellation")
                        clearFailedResumeState(appId)
                        di.updateStatus(DownloadPhase.FAILED)
                        di.setActive(false)
                        MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                        if (downloadTaskType == DownloadRecord.TASK_UPDATE) {
                            MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                        }
                        runBlocking {
                            DownloadCoordinator.notifyFinished(
                                DownloadRecord.STORE_STEAM,
                                appId.toString(),
                                DownloadRecord.STATUS_FAILED,
                                e.message,
                            )
                        }
                        removeDownloadJob(appId)
                        return@launch
                    } catch (e: CancellationException) {
                        if (di.isDeleting) {
                            Timber.d("Download cancelled for deletion for app $appId")
                            return@launch
                        }

                        if (di.isCancelling) {
                            Timber.d("Download cancelled by user for app $appId")
                            di.persistProgressSnapshot(force = true)
                            di.updateStatus(DownloadPhase.CANCELLED)
                            di.setActive(false)
                            runBlocking {
                                DownloadCoordinator.notifyFinished(
                                    DownloadRecord.STORE_STEAM,
                                    appId.toString(),
                                    DownloadRecord.STATUS_CANCELLED,
                                )
                            }
                            throw e
                        }

                        Timber.d(e, "Download paused for app $appId")
                        // Keep downloadingAppInfo on cancellation so resume does not fall into verify mode.
                        di.persistProgressSnapshot(force = true)
                        di.updateStatus(DownloadPhase.PAUSED)
                        di.setActive(false)
                        runBlocking {
                            DownloadCoordinator.notifyFinished(
                                DownloadRecord.STORE_STEAM,
                                appId.toString(),
                                DownloadRecord.STATUS_PAUSED,
                            )
                        }
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "Download failed for app $appId")
                        // Transient failures keep resume state so Retry continues from the same offset.
                        val isTransientFailure = e is WnDownloadTransientException
                        if (isTransientFailure) {
                            runCatching { di.persistProgressSnapshot(force = true) }
                        } else {
                            clearFailedResumeState(appId)
                        }

                        val errorMsg =
                            when (e) {
                                is ClassCastException -> "Casting error: ${e.message}"
                                is NullPointerException -> "Null reference: ${e.message}"
                                else -> e.localizedMessage ?: e.message ?: e.javaClass.simpleName
                            }

                        di.updateStatus(DownloadPhase.FAILED, errorMsg)
                        di.setActive(false)
                        MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                        if (downloadTaskType == DownloadRecord.TASK_UPDATE) {
                            MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                        }
                        if (!isTransientFailure) {
                            runBlocking {
                                instance?.downloadingAppInfoDao?.deleteApp(appId)
                                Unit
                            }
                        }
                        runBlocking {
                            DownloadCoordinator.notifyFinished(
                                DownloadRecord.STORE_STEAM,
                                appId.toString(),
                                DownloadRecord.STATUS_FAILED,
                                errorMsg,
                            )
                        }
                        removeDownloadJob(appId)
                        instance?.let { service ->
                            service.scope.launch(Dispatchers.Main) {
                                WinToast.show(service.applicationContext, "Download failed: $errorMsg", Toast.LENGTH_LONG)
                                Unit
                            }
                        }
                        PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, false))
                        Unit
                    } finally {
                        // Tear down a session this worker brought up itself.
                        workerWnSession?.let { ws ->
                            runCatching { ws.disconnect() }
                            runCatching { ws.close() }
                            Timber.i("downloadApp: closed worker WN-Steam session for app $appId")
                        }
                        workerWnSession = null
                        runCatching {
                            SessionKeepAliveService.stopDownload(keepAliveCtx, keepAliveTag)
                        }.onFailure { e ->
                            Timber.w(e, "Failed to release keep-alive for Steam download $appId")
                        }
                        Unit
                    }
                    Unit
                }
            downloadJob.invokeOnCompletion { throwable ->
                if (throwable is CancellationException && throwable !is DownloadFailedException) {
                    if (di.isDeleting) {
                        // Deletion handled externally
                    } else if (di.isCancelling) {
                        // Keep in downloadJobs for UI visibility, but still check queue
                        checkQueue()
                    } else {
                        Timber.d(throwable, "Download paused for app $appId")
                        removeDownloadJob(appId)
                    }
                }
            }
            di.setDownloadJob(downloadJob)
        }

    downloadJobs[appId] = info
    info.updateStatus(DownloadPhase.PREPARING)
    notifyDownloadStarted(appId)
    return info
}
