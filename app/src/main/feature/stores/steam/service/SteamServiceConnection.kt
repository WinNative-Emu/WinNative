package com.winlator.cmod.feature.stores.steam.service
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.BACKGROUND_IDLE_GRACE_MS
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.CONNECT_LOGON_MAX_ATTEMPTS
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.MAX_PICS_BUFFER
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.RECONNECT_BACKOFF_CAP_MS
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.REFRESH_TOKEN_ROTATION_CHECK_INTERVAL_MS
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.REFRESH_TOKEN_ROTATION_THRESHOLD_DAYS
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.WN_PLANW_REAP_OFFLINE_MS
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.instance
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.isConnected
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.isLoggingOut
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.isRunning
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.isStopping
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.isWaitingForQRAuth
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.kickPlayingSessionIfReady
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.syncInProgressApps
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.userSteamId
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.withWnSession
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.wnLibrary
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.wnLibraryMirrorJob
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.wnLoggedOnHandled
import com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.wnSession
import android.app.Service.STOP_FOREGROUND_REMOVE
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

// Instance connection/session lifecycle + bionic handoff + license processing, split out of SteamService.kt (behavior-identical).

internal fun SteamService.suspensionReasonForDiag(): String {
    val reasons = mutableListOf<String>()
    if (suspendedForBackground) reasons += "background-idle"
    if (suspendedForBionic) reasons += "bionic-handoff"
    if (!appInForeground) reasons += "app-bg"
    if (isLoggingOut) reasons += "logging-out"
    if (isStopping) reasons += "stopping"
    return reasons.joinToString(",")
}

/** Brings up (or reuses) the long-lived session and logs it on with the stored refresh token; [withWnSession] promotes it and installs the orchestrator observer (fires [onWnLoggedOn]). Retries with backoff until logged on or the service stops. Cold-start auto-logon and reconnect. */
internal fun SteamService.connectAndLogon() {
    if (connectJob?.isActive == true) return
    connectJob =
        scope.launch {
            PluviaApp.events.emit(SteamEvent.Connected(true))
            var attempt = 0
            while (isRunning && !isStopping && PrefManager.refreshToken.isNotBlank()) {
                if (wnSession?.state() == 3) break
                Timber.d("connectAndLogon: bringing up WN-Steam-Client session")
                val state = withWnSession { it.state() }
                if (state == 3) break
                attempt++
                if (attempt >= CONNECT_LOGON_MAX_ATTEMPTS) {
                    // Logon failed this many times — likely an expired/revoked token or sustained outage. Stop instead of spinning a full bring-up + logon forever (battery drain); a foreground wake or explicit re-login re-triggers connectAndLogon.
                    Timber.w("connectAndLogon: giving up after $attempt failed attempts")
                    break
                }
                val backoffMs = reconnectBackoffMs(attempt)
                Timber.w("connectAndLogon: not logged on — retry $attempt in ${backoffMs}ms")
                delay(backoffMs)
            }
        }
}

internal fun SteamService.ensureHealthySessionImpl() {
    if (!isRunning || isStopping || isLoggingOut) return
    if (PrefManager.refreshToken.isBlank()) return
    if (PluviaApp.isGameSessionActive()) return
    if (suspendedForBionic) {
        Timber.w("ensureHealthySession: clearing stale Bionic hand-off (no game running)")
        bionicHandoffReleaseImpl()
        return
    }
    if (wnSession?.state() == 3) return
    Timber.i("ensureHealthySession: session not logged on — re-driving connectAndLogon")
    retryAttempt = 0
    connectAndLogon()
}

internal fun SteamService.handleAppForegrounded() {
    appInForeground = true
    // Cancel any pending suspend timer — the app is back, so the session must stay up regardless of how long it was minimized.
    backgroundIdleJob?.cancel()
    backgroundIdleJob = null
    if (!suspendedForBackground) return
    suspendedForBackground = false
    Timber.i("App foregrounded — waking the WN-Steam-Client session")
    retryAttempt = 0
    if (isRunning && !isStopping && PrefManager.refreshToken.isNotBlank()) {
        runCatching {
            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                .setLoggedOn(true)
        }
        connectAndLogon()
    }
}

/** App went to the background — arm the deferred suspend check. */
internal fun SteamService.handleAppBackgrounded() {
    appInForeground = false
    scheduleBackgroundSuspendCheck()
}

/** Arm the background suspend check: [maybeSuspendForBackground] runs immediately, then repeats once per [BACKGROUND_IDLE_GRACE_MS] while connection-critical work runs (so nothing has to hook each operation's completion). A foreground event cancels it. */
internal fun SteamService.scheduleBackgroundSuspendCheck() {
    backgroundIdleJob?.cancel()
    if (appInForeground || isStopping || isLoggingOut) return
    backgroundIdleJob =
        scope.launch {
            while (isActive) {
                if (appInForeground || isStopping || isLoggingOut || suspendedForBackground) {
                    return@launch
                }
                // Suspended → done. Still busy → loop and re-check later.
                if (maybeSuspendForBackground()) return@launch
                delay(BACKGROUND_IDLE_GRACE_MS)
            }
        }
}

/** Reason the connection must stay open (null = safe to suspend) — anything that would corrupt data if the CM session dropped mid-operation: an active download (not paused/queued), a running game session, or an in-flight cloud-save sync (never interrupt or the save can be left corrupt). */
internal fun SteamService.connectionCriticalWork(): String? =
    when {
        DownloadCoordinator.hasActiveDownload() -> "a download is active"
        PluviaApp.isGameSessionActive() -> "a game session is running"
        syncInProgressApps.values.any { it.get() } -> "a cloud save sync is in progress"
        PrefManager.chatStayRunningOnExit -> "background chat is enabled"
        else -> null
    }


/** Suspend the backgrounded Steam session to draw no power — unless [connectionCriticalWork] still needs it. Disconnects the session and cancels every reconnect/PICS loop; wakes from [handleAppForegrounded]. Returns true if suspended. */
internal fun SteamService.maybeSuspendForBackground(): Boolean {
    if (appInForeground || isStopping || isLoggingOut || suspendedForBackground) return false
    val keepAliveReason = connectionCriticalWork()
    if (keepAliveReason != null) {
        Timber.i("App backgrounded but %s — keeping the Steam session connected", keepAliveReason)
        return false
    }
    Timber.i("App backgrounded and idle — suspending WN-Steam-Client session to save battery")
    suspendedForBackground = true
    connectJob?.cancel()
    reconnectJob?.cancel()
    stableConnectionJob?.cancel()
    refreshTokenWatchdogJob?.cancel()
    picsChangesCheckerJob?.cancel()
    picsGetProductInfoJob?.cancel()
    messagePollerJob?.cancel()
    wnSession?.let { s -> runCatching { s.disconnect() } }
    /** ToDo: To ensure the following code works correctly, it is necessary to investigate why,
     * when SteamService is stopped in the background without an active game session, SteamService
     * resumes after 5–10 seconds. This behavior can be verified in the active services notification.
     */
    // Commented out because Steam auto-start again in background after a few seconds.
    /*runCatching { SessionKeepAliveService.stopComponent(this, SessionKeepAliveService.COMPONENT_STEAM) }
        .onFailure { Timber.w(it, "Failed to remove SteamService foreground state during background") }*/
    return true
}

internal fun SteamService.startRefreshTokenWatchdog() {
    refreshTokenWatchdogJob?.cancel()
    refreshTokenWatchdogJob = scope.launch(Dispatchers.IO) {
        while (isActive && !isStopping && !isLoggingOut) {
            runCatching { maybeRotateRefreshToken() }
                .onFailure { Timber.w(it, "refresh-token watchdog tick threw") }
            delay(REFRESH_TOKEN_ROTATION_CHECK_INTERVAL_MS)
        }
    }
}

internal suspend fun SteamService.maybeRotateRefreshToken() {
    val cur = PrefManager.refreshToken
    if (cur.isBlank()) return
    val expMs: Long? = try {
        val jwt = com.auth0.android.jwt.JWT(cur)
        jwt.expiresAt?.time
    } catch (t: Throwable) {
        Timber.w(t, "refresh-token watchdog: JWT decode failed; rotating defensively")
        null
    }
    val nowMs = System.currentTimeMillis()
    val thresholdMs = REFRESH_TOKEN_ROTATION_THRESHOLD_DAYS * 24L * 60L * 60L * 1000L
    val needsRotation = expMs == null || (expMs - nowMs) < thresholdMs
    if (!needsRotation) {
        val daysLeft = ((expMs!! - nowMs) / (24L * 60L * 60L * 1000L)).coerceAtLeast(0)
        Timber.d("refresh-token watchdog: token healthy ($daysLeft days to expiry)")
        return
    }
    val rotated = renewRefreshTokenForHandoff()
    Timber.i("refresh-token watchdog: rotation attempt → $rotated")
}

internal suspend fun SteamService.renewRefreshTokenForHandoff(): Boolean =
    withContext(Dispatchers.IO) {
        val session = wnSession ?: return@withContext false
        val current = PrefManager.refreshToken
        val sid = PrefManager.steamUserSteamId64
        if (current.isEmpty() || sid == 0L) return@withContext false
        val fresh = try {
            session.renewRefreshToken(current, sid, timeoutMs = 15_000)
        } catch (t: Throwable) {
            Timber.w(t, "renewRefreshToken threw")
            null
        }
        if (fresh.isNullOrEmpty()) {
            Timber.w("renewRefreshTokenForHandoff: no new token returned")
            return@withContext false
        }
        PrefManager.refreshToken = fresh
        Timber.i("renewRefreshTokenForHandoff: token rotated (len ${current.length} -> ${fresh.length})")
        true
    }


internal fun SteamService.bionicHandoffAcquireImpl() {
    if (suspendedForBionic) return
    suspendedForBionic = true
    Timber.i("Bionic hand-off ACQUIRE — suspending WN-Steam-Client session for the bootstrap")
    connectJob?.cancel()
    reconnectJob?.cancel()
    stableConnectionJob?.cancel()
    refreshTokenWatchdogJob?.cancel()
    picsChangesCheckerJob?.cancel()
    picsGetProductInfoJob?.cancel()
    messagePollerJob?.cancel()
    wnSession?.let { s -> runCatching { s.logOffAndDisconnect(500) } }
}

internal fun SteamService.bionicHandoffReleaseImpl() {
    runCatching {
        com.winlator.cmod.feature.stores.steam.wnsteam.WnSteamBootstrap.stop()
    }
    if (!suspendedForBionic) return
    suspendedForBionic = false
    retryAttempt = 0
    if (!(isRunning && !isStopping && !isLoggingOut && PrefManager.refreshToken.isNotBlank())) {
        Timber.i("Bionic hand-off RELEASE — not resuming (service not in a resumable state)")
        return
    }
    // PlanW: defer the wn-session resume so the account stays offline long enough for Steam to reap the launcher's games-played registration; resuming immediately keeps it online and the next launch hits AlreadyRunning (0x10).
    if (PrefManager.wnPlanW) {
        Timber.i(
            "Bionic hand-off RELEASE — PlanW: deferring wn-session resume " +
                "${WN_PLANW_REAP_OFFLINE_MS}ms so Steam reaps the launcher's " +
                "games-played registration (account stays offline)",
        )
        scope.launch(Dispatchers.IO) {
            delay(WN_PLANW_REAP_OFFLINE_MS)
            // Skip if a new launch re-acquired the hand-off, or the session already came up some other way.
            if (!suspendedForBionic && isRunning && !isStopping && !isLoggingOut &&
                PrefManager.refreshToken.isNotBlank() && (wnSession?.state() ?: 0) != 3
            ) {
                Timber.i("Bionic hand-off RELEASE — PlanW reap window elapsed, resuming WN-Steam-Client")
                connectAndLogon()
            } else {
                Timber.i(
                    "Bionic hand-off RELEASE — PlanW reap window elapsed, resume skipped " +
                        "(suspendedForBionic=$suspendedForBionic wnState=${wnSession?.state()})",
                )
            }
        }
    } else {
        Timber.i("Bionic hand-off RELEASE — bootstrap logged off, resuming WN-Steam-Client")
        connectAndLogon()
    }
}

internal fun SteamService.bionicHandoffReleaseAndKickPlayingSessionAsyncImpl(onlyGame: Boolean) {
    bionicHandoffReleaseImpl()
    if (!isRunning || isStopping || isLoggingOut) return
    scope.launch(Dispatchers.IO) {
        repeat(20) { attempt ->
            if (kickPlayingSessionIfReady(onlyGame)) {
                Timber.i(
                    "Bionic hand-off release: kickPlayingSessionIfReady fired " +
                        "after reconnect (attempt ${attempt + 1}/20 onlyGame=$onlyGame)",
                )
                return@launch
            }
            delay(500L)
        }
        Timber.i(
            "Bionic hand-off release: wn-session never became ready " +
                "for kickPlayingSessionIfReady (onlyGame=$onlyGame)",
        )
    }
}

internal suspend fun SteamService.bionicHandoffReleaseAndKickPlayingSessionBlockingImpl(
    onlyGame: Boolean,
    maxWaitMs: Long,
): Boolean {
    bionicHandoffReleaseImpl()
    if (!isRunning || isStopping || isLoggingOut) return false

    val deadlineMs = System.currentTimeMillis() + maxWaitMs
    var attempt = 0
    do {
        attempt++
        if (kickPlayingSessionIfReady(onlyGame)) {
            Timber.i(
                "Bionic hand-off release: kickPlayingSessionIfReady fired " +
                    "before close (attempt $attempt onlyGame=$onlyGame)",
            )
            return true
        }

        val remainingMs = deadlineMs - System.currentTimeMillis()
        if (remainingMs <= 0L) break
        delay(minOf(250L, remainingMs))
    } while (true)

    Timber.i(
        "Bionic hand-off release: wn-session never became ready " +
            "before close (onlyGame=$onlyGame timeoutMs=$maxWaitMs)",
    )
    return false
}

internal fun SteamService.clearValues() {
    if (instance === this) {
        instance = null
    }

    _loginResult = LoginResult.Failed
    isRunning = false
    isConnected = false
    isLoggingOut = false
    isWaitingForQRAuth = false

    wnLoggedOnHandled = false
    wnLibraryMirrorJob?.cancel()
    wnLibraryMirrorJob = null
    wnLibrary?.stopObserving()
    wnLibrary = null

    _unifiedFriends?.close()
    _unifiedFriends = null
    familyGroupMembers.clear()

    isStopping = false
    retryAttempt = 0
    reconnectJob?.cancel()
    reconnectJob = null
    stableConnectionJob?.cancel()
    refreshTokenWatchdogJob?.cancel()
    stableConnectionJob = null
    backgroundIdleJob?.cancel()
    backgroundIdleJob = null
    suspendedForBackground = false
    suspendedForBionic = false
    appInForeground = true

    PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)
    PluviaApp.events.clearAllListenersOf<SteamEvent<Any>>()
}

/** Channel-dropped (onDisconnected) handler: reconnects while credentials + retries remain, else emits Disconnected and stops the service. Fired from the [installWnLogonObserver] state observer. */
/** Exponential reconnect backoff (2s, 4s, 8s… doubling per 1-based attempt, capped at [RECONNECT_BACKOFF_CAP_MS]) — without it a connection that logs on then drops reconnects in a tight loop and overheats the device. */
internal fun SteamService.reconnectBackoffMs(attempt: Int): Long {
    val shift = (attempt - 1).coerceIn(0, 8) // 2^0 .. 2^8
    val seconds = (1L shl shift) * 2L // 2, 4, 8, , 512
    return (seconds * 1000L).coerceAtMost(RECONNECT_BACKOFF_CAP_MS)
}

/** Populate the steam_license / cached_license tables from the received licenses (CMsgClientLicenseList); driven from the post-logon flow. */
internal suspend fun SteamService.processLicenseList() {
    // The license list is pushed just after logon; poll briefly for it.
    var json: String? = null
    for (attempt in 0 until 15) {
        json = withWnSession { session -> session.getLicenseList() }
        if (json != null && json != "[]") break
        delay(200)
    }
    val arr =
        try {
            JSONArray(json ?: "[]")
        } catch (e: Exception) {
            Timber.w(e, "processLicenseList: bad license JSON")
            return
        }
    if (arr.length() == 0) {
        Timber.w("processLicenseList: no licenses received")
        return
    }
    Timber.i("Received License List, size: ${arr.length()}")

    data class RawLicense(
        val packageId: Int, val changeNumber: Int,
        val timeCreated: Long, val timeNextProcess: Long,
        val minuteLimit: Int, val minutesUsed: Int,
        val paymentMethod: Int, val flags: Int,
        val purchaseCountryCode: String, val licenseType: Int,
        val territoryCode: Int, val accessToken: Long,
        val ownerId: Int, val masterPackageId: Int,
    )
    val raw =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RawLicense(
                packageId = o.optInt("packageId"),
                changeNumber = o.optInt("changeNumber"),
                timeCreated = o.optLong("timeCreated"),
                timeNextProcess = o.optLong("timeNextProcess"),
                minuteLimit = o.optInt("minuteLimit"),
                minutesUsed = o.optInt("minutesUsed"),
                paymentMethod = o.optInt("paymentMethod"),
                flags = o.optInt("flags"),
                purchaseCountryCode = o.optString("purchaseCountryCode"),
                licenseType = o.optInt("licenseType"),
                territoryCode = o.optInt("territoryCode"),
                accessToken = o.optLong("accessToken"),
                ownerId = o.optInt("ownerId"),
                masterPackageId = o.optInt("masterPackageId"),
            )
        }

    db.withTransaction {
        // Every launch refreshes licenses, so findStaleLicences picks up packages we no longer have (e.g. family-share changes).

        // Store raw licenses for the manifest-fetch path (CachedLicense).
        cachedLicenseDao.deleteAll()
        cachedLicenseDao.insertAll(
            raw.map { l ->
                CachedLicense(
                    licenseJson =
                        LicenseSerializer.serializeLicenseFields(
                            packageID = l.packageId,
                            lastChangeNumber = l.changeNumber,
                            timeCreatedMs = l.timeCreated * 1000L,
                            timeNextProcessMs = l.timeNextProcess * 1000L,
                            minuteLimit = l.minuteLimit,
                            minutesUsed = l.minutesUsed,
                            paymentMethod = l.paymentMethod,
                            flags = l.flags,
                            purchaseCode = l.purchaseCountryCode,
                            licenseType = l.licenseType,
                            territoryCode = l.territoryCode,
                            accessToken = l.accessToken,
                            ownerAccountID = l.ownerId,
                            masterPackageID = l.masterPackageId,
                        ),
                )
            },
        )

        val myAccountId = userSteamId?.accountID?.toInt()
        val licensesToAdd =
            raw.groupBy { it.packageId }.map { (packageId, group) ->
                val preferred =
                    group.firstOrNull { it.ownerId == myAccountId }
                        ?: group.first()
                // OR-combine the flag bitfields across every owner of the package.
                val combinedFlags = EnumSet.noneOf(ELicenseFlags::class.java)
                group.forEach { combinedFlags.addAll(ELicenseFlags.from(it.flags)) }
                SteamLicense(
                    packageId = packageId,
                    lastChangeNumber = preferred.changeNumber,
                    timeCreated = Date(preferred.timeCreated * 1000L),
                    timeNextProcess = Date(preferred.timeNextProcess * 1000L),
                    minuteLimit = preferred.minuteLimit,
                    minutesUsed = preferred.minutesUsed,
                    paymentMethod = EPaymentMethod.from(preferred.paymentMethod) ?: EPaymentMethod.None,
                    licenseFlags = combinedFlags,
                    purchaseCode = preferred.purchaseCountryCode,
                    licenseType = ELicenseType.from(preferred.licenseType) ?: ELicenseType.NoLicense,
                    territoryCode = preferred.territoryCode,
                    accessToken = preferred.accessToken,
                    ownerAccountId = group.map { it.ownerId },
                    masterPackageID = preferred.masterPackageId,
                )
            }

        if (licensesToAdd.isNotEmpty()) {
            Timber.i("Adding ${licensesToAdd.size} licenses")
            licenseDao.insertAll(licensesToAdd)
        }

        val licensesToRemove =
            licenseDao.findStaleLicences(packageIds = raw.map { it.packageId })
        if (licensesToRemove.isNotEmpty()) {
            Timber.i("Removing ${licensesToRemove.size} (stale) licenses")
            licenseDao.deleteStaleLicenses(licensesToRemove.map { it.packageId })
        }

        licenseDao
            .getAllLicenses()
            .map { PICSRequest(it.packageId, it.accessToken) }
            .chunked(MAX_PICS_BUFFER)
            .forEach { chunk ->
                Timber.d("processLicenseList: Queueing ${chunk.size} package(s) for PICS")
                packagePicsChannel.send(chunk)
            }
    }
}
