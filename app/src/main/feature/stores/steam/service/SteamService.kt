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

internal fun JSONArray?.toIntList(): List<Int> {
    val len = this?.length() ?: 0
    if (len == 0) return emptyList()
    val out = ArrayList<Int>(len)
    for (i in 0 until len) out.add(this!!.getInt(i))
    return out
}

@AndroidEntryPoint
class SteamService : Service() {
    @Inject
    lateinit var db: PluviaDatabase

    @Inject
    lateinit var licenseDao: SteamLicenseDao

    @Inject
    lateinit var appDao: SteamAppDao

    @Inject
    lateinit var changeNumbersDao: ChangeNumbersDao

    @Inject
    lateinit var appInfoDao: AppInfoDao

    @Inject
    lateinit var fileChangeListsDao: FileChangeListsDao

    @Inject
    lateinit var cachedLicenseDao: CachedLicenseDao

    @Inject
    lateinit var encryptedAppTicketDao: EncryptedAppTicketDao

    @Inject
    lateinit var downloadingAppInfoDao: DownloadingAppInfoDao

    internal lateinit var notificationHelper: NotificationHelper

    internal var _unifiedFriends: SteamUnifiedFriends? = null

    internal var _loginResult: LoginResult = LoginResult.Failed

    internal var retryAttempt = 0

    // Auto-reconnect coroutine for the C++ WN-Steam-Client session.
    @Volatile internal var connectJob: Job? = null

    // Pending backoff-delayed reconnect scheduled by onWnDisconnected.
    @Volatile internal var reconnectJob: Job? = null

    // Resets retryAttempt to 0 only after the session stays up STABLE_CONNECTION_MS; a flapping connection must not reset it (unbounded reconnect / battery drain).
    @Volatile internal var stableConnectionJob: Job? = null
    @Volatile internal var refreshTokenWatchdogJob: Job? = null

    // App-lifecycle gating: while backgrounded with nothing needing Steam (no download, no running game) the session is suspended — disconnected, reconnect/PICS loops cancelled — to draw no power; wakes on foreground. Driven from PluviaApp lifecycle callbacks.
    @Volatile internal var appInForeground = true
    @Volatile internal var suspendedForBackground = false

    @Volatile internal var suspendedForBionic = false

    // Cancellable timer deferring the background suspend by BACKGROUND_IDLE_GRACE_MS — see scheduleBackgroundSuspendCheck.
    @Volatile internal var backgroundIdleJob: Job? = null

    internal val appPicsChannel =
        Channel<List<PICSRequest>>(
            capacity = 1_000,
            onBufferOverflow = BufferOverflow.SUSPEND,
            onUndeliveredElement = { droppedApps ->
                Timber.w("App PICS Channel dropped: ${droppedApps.size} apps")
            },
        )

    internal val packagePicsChannel =
        Channel<List<PICSRequest>>(
            capacity = 1_000,
            onBufferOverflow = BufferOverflow.SUSPEND,
            onUndeliveredElement = { droppedPackages ->
                Timber.w("Package PICS Channel dropped: ${droppedPackages.size} packages")
            },
        )

    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    internal val onEndProcess: (AndroidEvent.EndProcess) -> Unit = {
        Companion.stop()
    }

    // The current shared family group the logged in user is joined to.
    private var familyGroupMembers: ArrayList<Int> = arrayListOf()

    private val appTokens: ConcurrentHashMap<Int, Long> = ConcurrentHashMap()

    internal var picsGetProductInfoJob: Job? = null
    internal var picsChangesCheckerJob: Job? = null
    private var friendCheckerJob: Job? = null

    private val _isPlayingBlocked = MutableStateFlow(false)
    val isPlayingBlocked = _isPlayingBlocked.asStateFlow()

    // Cache in-memory the local persona state.
    private val _localPersona =
        MutableStateFlow(
            SteamFriend(name = PrefManager.steamUserName, avatarHash = PrefManager.steamUserAvatarHash),
        )
    val localPersona = _localPersona.asStateFlow()

    internal val _friendsList = MutableStateFlow<List<SteamFriendEntry>>(emptyList())
    val friendsList = _friendsList.asStateFlow()

    internal val _incomingChat =
        MutableSharedFlow<Pair<Long, com.winlator.cmod.feature.stores.steam.data.SteamChatMessage>>(
            replay = 32,
            extraBufferCapacity = 256,
        )
    val incomingChat = _incomingChat.asSharedFlow()

    internal val _unreadCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val unreadCounts = _unreadCounts.asStateFlow()

    internal val _recentChats = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val recentChats = _recentChats.asStateFlow()

    internal val activeConversations = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    internal var messagePollerJob: Job? = null

    data class ManifestSizes(
        val installSize: Long = 0L,
        val downloadSize: Long = 0L,
    )

    data class SteamUpdateInfo(
        val hasUpdate: Boolean = false,
        val downloadSize: Long = 0L,
        val depotIds: List<Int> = emptyList(),
        val message: String? = null,
    )

    companion object {
        const val MAX_PICS_BUFFER = 256

        const val MAX_RETRY_ATTEMPTS = 20

        // Session must stay logged on this long before its reconnect counts as successful and resets the retry budget.
        private const val STABLE_CONNECTION_MS = 60_000L

        // Reconnect backoff cap — even a permanently-flapping connection reconnects at most once per this interval.
        internal const val RECONNECT_BACKOFF_CAP_MS = 5 * 60_000L

        // connectAndLogon gives up after this many consecutive failed bring-up attempts (exponential backoff) instead of retrying a doomed logon forever.
        internal const val CONNECT_LOGON_MAX_ATTEMPTS = 8

        internal const val REFRESH_TOKEN_ROTATION_THRESHOLD_DAYS = 7
        internal const val REFRESH_TOKEN_ROTATION_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        // Grace before a backgrounded session may suspend, so a brief app-switch doesn't thrash disconnect/reconnect (battery drain). While connection-critical work runs the check repeats once per interval.
        internal const val BACKGROUND_IDLE_GRACE_MS = 60_000L

        // Stay fully offline this long after a PlanW game closes so Steam reaps the launcher's games-played registration (else next launch hits AlreadyRunning 0x10).
        internal const val WN_PLANW_REAP_OFFLINE_MS = 10_000L

        const val INVALID_APP_ID: Int = Int.MAX_VALUE
        const val INVALID_PKG_ID: Int = Int.MAX_VALUE
        internal const val STEAM_CONTROLLER_CONFIG_FILENAME = "steam_controller_config.vdf"
        internal const val DOWNLOAD_INFO_DIR = ".DownloadInfo"
        internal const val DOWNLOAD_INFO_FILE = "depot_bytes.json"
        internal const val LEGACY_DOWNLOAD_INFO_FILE = "bytes_downloaded.txt"
        internal const val COMPONENTS_BASE_URL = "https://github.com/maxjivi05/Components/releases/download/Components"
        @Volatile
        private var startupMetadataRepairJob: Job? = null

        /** Default timeout when making requests. */
        var requestTimeout = 30.seconds

        /** Default timeout when reading the response body. */
        var responseTimeout = 120.seconds


        internal var instance: SteamService? = null

        var cachedAchievements: List<com.winlator.cmod.feature.stores.steam.statsgen.Achievement>? = null
            private set
        var cachedAchievementsAppId: Int? = null
            private set

        fun clearCachedAchievements() {
            cachedAchievements = null
            cachedAchievementsAppId = null
        }

        // Generate (CM schema + unlock state) and return achievements for a game.
        suspend fun loadAchievements(
            appId: Int,
            configDirectory: String,
        ): List<com.winlator.cmod.feature.stores.steam.statsgen.Achievement> {
            runCatching { generateAchievements(appId, configDirectory) }
            return if (cachedAchievementsAppId == appId) cachedAchievements ?: emptyList() else emptyList()
        }

        fun pauseAll() {
            DownloadCoordinator.runOnScope { DownloadCoordinator.pauseAll() }
        }

        fun pauseDownload(appId: Int) {
            DownloadCoordinator.runOnScope {
                DownloadCoordinator.pause(DownloadRecord.STORE_STEAM, appId.toString())
            }
        }

        fun resumeAll() {
            DownloadCoordinator.runOnScope { DownloadCoordinator.resumeAll() }
        }

        fun bionicHandoffAcquire() {
            instance?.bionicHandoffAcquireImpl()
        }

        fun bionicHandoffRelease() {
            instance?.bionicHandoffReleaseImpl()
        }

        fun bionicHandoffReleaseAndKickPlayingSessionAsync(onlyGame: Boolean = true) {
            instance?.bionicHandoffReleaseAndKickPlayingSessionAsyncImpl(onlyGame)
        }

        @JvmStatic
        fun bionicHandoffReleaseAndKickPlayingSessionBlocking(
            onlyGame: Boolean = true,
            maxWaitMs: Long = 4_000L,
        ): Boolean =
            runBlocking(Dispatchers.IO) {
                val svc = instance
                if (svc == null) {
                    runCatching {
                        com.winlator.cmod.feature.stores.steam.wnsteam.WnSteamBootstrap.stop()
                    }
                    return@runBlocking false
                }
                svc.bionicHandoffReleaseAndKickPlayingSessionBlockingImpl(
                    onlyGame,
                    maxWaitMs.coerceAtLeast(0L),
                )
            }

        @JvmStatic
        fun isBionicHandoffActive(): Boolean =
            instance?.suspendedForBionic == true

        @JvmStatic
        fun kickPlayingSessionIfReadyBlocking(onlyGame: Boolean = true): Boolean =
            runBlocking(Dispatchers.IO) {
                kickPlayingSessionIfReady(onlyGame)
            }


        fun setHybridModeRuntime(enabled: Boolean) {
            val svc = instance ?: return
            if (enabled) {
                if (!isLoggedIn) return                      // takeover fires from onWnLoggedOn on next sign-in
                if (svc.suspendedForBionic) return           // bootstrap already owns the session
                svc.scope.launch {
                    Timber.i("Hybrid mode live-toggle ON — bootstrap takeover")
                    val renewed = svc.renewRefreshTokenForHandoff()
                    Timber.i("Hybrid mode live-toggle: token renewal " +
                        if (renewed) "OK (fresh token saved)"
                        else "skipped/failed — using existing token")
                    svc.bionicHandoffAcquireImpl()
                    delay(1800)
                    try {
                        com.winlator.cmod.feature.stores.steam.wnsteam
                            .WnSteamBootstrap.prewarm(svc.applicationContext)
                        Timber.i("Hybrid mode live-toggle: bootstrap prewarm dispatched")
                    } catch (t: Throwable) {
                        Timber.e(t, "Hybrid mode live-toggle: prewarm failed; reverting")
                        svc.bionicHandoffReleaseImpl()
                    }
                }
            } else {
                if (!svc.suspendedForBionic) return           // wn-session already owns
                svc.scope.launch {
                    Timber.i("Hybrid mode live-toggle OFF — bringing wn-session back")
                    svc.bionicHandoffReleaseImpl()
                }
            }
        }

        fun resumeDownload(appId: Int) {
            DownloadCoordinator.runOnScope {
                DownloadCoordinator.resume(DownloadRecord.STORE_STEAM, appId.toString())
            }
        }

        fun cancelAll() {
            DownloadCoordinator.runOnScope { DownloadCoordinator.cancelAll() }
        }

        fun cancelDownload(appId: Int) {
            DownloadCoordinator.runOnScope {
                DownloadCoordinator.cancel(DownloadRecord.STORE_STEAM, appId.toString())
            }
        }

        // Legacy entry point kept for binary compat — delegates to the coordinator instead of the old Steam-only queue logic (which would race it and double-start downloads).
        fun checkQueue() {
            DownloadCoordinator.blockingTick()
        }

        internal val downloadJobs = ConcurrentHashMap<Int, DownloadInfo>()

        fun clearCompletedDownloads() {
            clearCompletedDownloadsInternal(dispatchQueueAfterClear = true)
            // Also remove finished records from the cross-store coordinator table.
            DownloadCoordinator.runOnScope { DownloadCoordinator.clear() }
        }

        fun clearCompletedDownloadsForShutdown() {
            clearCompletedDownloadsInternal(dispatchQueueAfterClear = false)
        }

        fun hasPartialDownload(appId: Int): Boolean {
            if (isAppInstalled(appId)) return false

            val appDirPath = getAppDirPath(appId)
            val downloadingApp = getDownloadingAppInfoOf(appId)
            val hasCompleteMarker = MarkerUtils.hasMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            val hasPartialFiles = hasPartialDownloadFiles(appDirPath)
            val hasPersistedMetadata = hasPersistedDepotResumeMetadata(appDirPath)
            val isResumable =
                if (hasCompleteMarker) {
                    downloadingApp != null || hasPersistedMetadata
                } else {
                    hasPartialFiles
                }

            if (isResumable) {
                return true
            }

            if (downloadingApp != null) {
                runBlocking(Dispatchers.IO) {
                    instance?.downloadingAppInfoDao?.deleteApp(appId)
                }
            }

            if (hasCompleteMarker && !hasPersistedMetadata) {
                clearPersistedProgressSnapshot(appDirPath)
            }

            return false
        }

        internal val syncInProgressApps = ConcurrentHashMap<Int, AtomicBoolean>()

        // Track whether a game is currently running to prevent premature service stop
        @JvmStatic
        @Volatile
        var keepAlive: Boolean = false

        data class CloudSyncMessage(
            val appId: Int,
            val isUpload: Boolean,
            val message: String,
            val progress: Float,
        )

        val cloudSyncStatus = MutableStateFlow<CloudSyncMessage?>(null)

        @Volatile
        var isImporting: Boolean = false

        var isStopping: Boolean = false
            internal set
        private val _isConnectedFlow = MutableStateFlow(false)
        val isConnectedFlow = _isConnectedFlow.asStateFlow()

        /** Pure getter over [isConnectedFlow] — do not read the live socket here; only callbacks may write the flow, else concurrent reads flicker the UI during CM reconnect gaps. */
        var isConnected: Boolean
            get() = _isConnectedFlow.value
            internal set(value) {
                _isConnectedFlow.value = value
            }

        var isRunning: Boolean = false
            internal set
        var isLoggingOut: Boolean = false
            internal set

        internal val _isLoggedInFlow = MutableStateFlow(false)
        val isLoggedInFlow = _isLoggedInFlow.asStateFlow()

        // Master chat switch (default on). When off, the Steam session stays logged on for
        // downloads/library, but the chat layer is killed: offline presence, no message
        // poller, no friends refresh, no chat overlay/notifications.
        private val _chatServiceEnabledFlow = MutableStateFlow(true)
        val chatServiceEnabledFlow = _chatServiceEnabledFlow.asStateFlow()

        /** Flip the master chat switch: persists, updates the reactive flag, and applies at once (presence, poller, overlay). */
        fun setChatServiceEnabled(context: Context, enabled: Boolean) {
            PrefManager.chatServiceEnabled = enabled
            _chatServiceEnabledFlow.value = enabled
            instance?.applyChatServiceState(enabled)
            if (!enabled) {
                com.winlator.cmod.feature.stores.steam.chat.ChatOverlayService.stop(context)
            } else if (PrefManager.chatHeadsEnabled) {
                com.winlator.cmod.feature.stores.steam.chat.ChatOverlayService.start(context)
            }
        }

        /** Pure getter over [isLoggedInFlow] — never write the flow from a read (caused UI flicker on transient CM disconnect); only authoritative sources mutate it (initLoginStatus, onLoggedOn/Off, logOut, clearValues). */
        // Invoked by name via reflection from SteamBridge/SteamClientManager — keep in the companion; do not move to an extension file.
        val isLoggedIn: Boolean
            get() = !isLoggingOut && _isLoggedInFlow.value

        var isWaitingForQRAuth: Boolean = false
            internal set

        // In-flight credentials/QR auth session; held in the companion so stopLoginWithQr() can cancel anywhere; cleared on success (ownership → wnSession) or failure.
        private var wnAuthSession: WnSteamSession? = null

        // Long-lived session carrying the post-logon CM connection — the sole Steam connection, from refresh-token acquisition through logout. @Volatile: logOut() reads on UI thread while the auth flow writes on IO.
        @Volatile internal var wnSession: WnSteamSession? = null

        @JvmStatic
        fun wnSessionStateForDiag(): Int = wnSession?.state() ?: -1

        @JvmStatic
        fun wnSessionSuspensionReasonForDiag(): String =
            instance?.suspensionReasonForDiag() ?: "no-service"

        // True once onWnLoggedOn ran for the current wnSession; reset on disconnect/teardown so reconnect re-runs it. Guards the state observer double-firing.
        @Volatile internal var wnLoggedOnHandled = false

        // Serializes session bring-up: concurrent callers racing into bringUpWnSession() spin up separate CM sessions that kick each other (ClientLoggedOff eresult=34) since Steam allows one per account-instance. Only bring-up is gated; reusing a logged-on session stays lock-free.
        private val wnSessionBringUpMutex = kotlinx.coroutines.sync.Mutex()

        @Volatile internal var logonGateUntilMs: Long = 0L
            internal set
        @Volatile internal var lastLogonFailureEresult: Int = 0
            internal set
        @Volatile internal var consecutiveLogonFailures: Int = 0
            internal set

        /** Live Kotlin facade over wnSession's native library store; created with the session, torn down by teardownPriorWnSession()/logOut(). Collect `snapshots` to observe library changes; `current` is the latest value. */
        @Volatile var wnLibrary: WnLibraryStore? = null
            internal set
        @Volatile internal var wnLibraryMirrorJob: Job? = null

        /** Keeps [isConnectedFlow] in sync with the live socket; only touches the connected flow (writing isLoggedInFlow from a read flipped the UI to "signed out" on CM load-balancing). */
        fun syncStates() {
            // Connected == the C++ WN-Steam-Client channel is up (state >= 2).
            val connected = (wnSession?.state() ?: 0) >= 2
            if (connected != _isConnectedFlow.value) _isConnectedFlow.value = connected
        }

        /** True if a stored refresh token exists — gates whether auto-reconnect is attempted on app start. */
        fun hasStoredCredentials(context: Context): Boolean {
            PrefManager.init(context)
            return PrefManager.refreshToken.isNotBlank()
        }

        /** Classifies the session into [StoreAuthStatus] so the UI can show reconnecting / expired / no-login: LOGGED_OUT (no token), EXPIRED (JWT exp past), ACTIVE (logged in), REFRESHABLE (valid token, not yet logged on / mid-reconnect), UNKNOWN (token unparseable). */
        fun getAuthStatus(context: Context): StoreAuthStatus {
            PrefManager.init(context)
            val refreshToken = PrefManager.refreshToken
            if (refreshToken.isBlank()) return StoreAuthStatus.LOGGED_OUT

            val jwtExpired: Boolean? =
                try {
                    JWT(refreshToken).isExpired(0)
                } catch (_: Exception) {
                    null
                }
            if (jwtExpired == true) return StoreAuthStatus.EXPIRED

            if (!isLoggingOut && _isLoggedInFlow.value) return StoreAuthStatus.ACTIVE

            return if (jwtExpired == null) StoreAuthStatus.UNKNOWN else StoreAuthStatus.REFRESHABLE
        }

        /** Pre-seeds the login flow from stored credentials so the UI doesn't flash "sign in" while connecting. */
        fun initLoginStatus(context: Context) {
            if (!isLoggingOut) {
                _isLoggedInFlow.value = hasStoredCredentials(context)
            }
        }


        val internalAppInstallPath: String
            get() = Paths.get(DownloadService.baseDataDirPath, "Steam", "steamapps", "common").pathString

        val externalAppInstallPath: String
            get() = Paths.get(PrefManager.externalStoragePath, "Steam", "steamapps", "common").pathString

        val allInstallPaths: List<String>
            get() {
                val paths = mutableListOf(internalAppInstallPath)
                if (PrefManager.externalStoragePath.isNotBlank()) {
                    paths += externalAppInstallPath
                }
                for (volumePath in DownloadService.externalVolumePaths) {
                    if (volumePath.isNotBlank()) {
                        paths += Paths.get(volumePath, "Steam", "steamapps", "common").pathString
                    }
                }
                return paths.distinct()
            }

        private val internalAppStagingPath: String
            get() {
                return Paths.get(DownloadService.baseDataDirPath, "Steam", "steamapps", "staging").pathString
            }
        private val externalAppStagingPath: String
            get() {
                return Paths.get(PrefManager.externalStoragePath, "Steam", "steamapps", "staging").pathString
            }

        val defaultStoragePath: String
            get() {
                return if (PrefManager.useExternalStorage && File(PrefManager.externalStoragePath).exists()) {
                    // We still have an SD card file structure as expected
                    Timber.i("External storage path is " + PrefManager.externalStoragePath)
                    PrefManager.externalStoragePath
                } else {
                    if (instance != null) {
                        return DownloadService.baseDataDirPath
                    }
                    return ""
                }
            }

        val defaultAppInstallPath: String
            get() {
                val context = PluviaApp.instance.applicationContext ?: return internalAppInstallPath
                val storeDefaultUri = if (PrefManager.useSingleDownloadFolder) PrefManager.defaultDownloadFolder else PrefManager.steamDownloadFolder
                if (storeDefaultUri.isNotEmpty()) {
                    val baseDir =
                        com.winlator.cmod.shared.io.FileUtils
                            .getFilePathFromUri(context, android.net.Uri.parse(storeDefaultUri))
                    Timber.i("defaultAppInstallPath: resolved baseDir $baseDir from URI $storeDefaultUri")
                    if (baseDir != null) return baseDir
                }

                return if (PrefManager.useExternalStorage && File(PrefManager.externalStoragePath).exists()) {
                    // We still have an SD card file structure as expected
                    Timber.i("Using external storage")
                    Timber.i("install path for external storage is " + externalAppInstallPath)
                    externalAppInstallPath
                } else {
                    Timber.i("Using internal storage")
                    internalAppInstallPath
                }
            }

        val defaultAppStagingPath: String
            get() {
                val context = PluviaApp.instance.applicationContext ?: return internalAppStagingPath
                val storeDefaultUri = if (PrefManager.useSingleDownloadFolder) PrefManager.defaultDownloadFolder else PrefManager.steamDownloadFolder
                if (storeDefaultUri.isNotEmpty()) {
                    val baseDir =
                        com.winlator.cmod.shared.io.FileUtils
                            .getFilePathFromUri(context, android.net.Uri.parse(storeDefaultUri))
                    if (baseDir != null) return Paths.get(baseDir, "staging").pathString
                }

                return if (PrefManager.useExternalStorage) {
                    externalAppStagingPath
                } else {
                    internalAppStagingPath
                }
            }

        val userSteamId: SteamID?
            get() {
                // Prefer the live native session; fall back during reconnect gaps.
                val live = wnSession?.steamId()?.takeIf { it != 0L }
                val id = live ?: PrefManager.steamUserSteamId64.takeIf { it != 0L }
                return id?.let { SteamID(it) }
            }

        val familyMembers: List<Int>
            get() = instance?.familyGroupMembers ?: emptyList()

        val isLoginInProgress: Boolean
            get() = instance?._loginResult == LoginResult.InProgress

        suspend fun setPersonaState(state: EPersonaState) =
            withContext(Dispatchers.IO) {
                PrefManager.personaState = state.code()
                if (!PrefManager.wnHybridMode) {
                    withWnSession { session -> session.setPersonaState(state.code()) }
                } else {
                    Timber.d("Hybrid: setPersonaState($state) local-only; " +
                        "Steam-side broadcast deferred until IClientFriends RE")
                }
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setPersonaState(state.code())
                // Reflect locally — Steam doesn't echo our own persona state back, so the UI would stay stale until the next requestUserPersona().
                instance?._localPersona?.update { it.copy(state = state) }
                instance?.localPersona?.value?.let {
                    PluviaApp.events.emit(SteamEvent.PersonaStateReceived(it))
                }
            }

        suspend fun requestUserPersona() =
            withContext(Dispatchers.IO) {
                val svc = instance ?: return@withContext

                run {
                    val bs = com.winlator.cmod.feature.stores.steam.wnsteam
                        .WnSteamBootstrap
                    val playerName = bs.personaName()
                    if (!playerName.isNullOrEmpty()) {
                        val stateCode = bs.personaState()
                        svc._localPersona.update {
                            it.copy(
                                name = playerName,
                                state = EPersonaState.from(stateCode) ?: EPersonaState.Offline,
                            )
                        }
                        PrefManager.steamUserName = playerName
                        PluviaApp.events.emit(
                            SteamEvent.PersonaStateReceived(svc.localPersona.value),
                        )
                        Timber.i("user persona via libsteamclient.so: name='$playerName' state=$stateCode")
                        return@withContext
                    }
                    Timber.d("libsteamclient.so persona empty; falling through to wn-session")
                }

                // Fetch local persona: CMsgClientRequestFriendData is sent; the CMsgClientPersonaState reply is server-pushed and cached — poll getSelfPersona() for it.
                val json =
                    withWnSession { session ->
                        session.requestUserPersona()
                        var j: String? = null
                        for (i in 0 until 25) {
                            j = session.getSelfPersona()
                            if (j != null) break
                            delay(200)
                        }
                        j
                    } ?: return@withContext
                try {
                    val obj = JSONObject(json)
                    val avatarHash = obj.optString("avatarHash")
                    val playerName = obj.optString("playerName")
                    val gameAppId = obj.optInt("gameAppId")
                    svc._localPersona.update {
                        it.copy(
                            avatarHash = avatarHash.ifEmpty { it.avatarHash },
                            name = playerName.ifEmpty { it.name },
                            state = EPersonaState.from(obj.optInt("personaState")) ?: EPersonaState.Offline,
                            gameAppID = gameAppId,
                            gameName = svc.appDao.findApp(gameAppId)?.name
                                ?: obj.optString("gameName"),
                        )
                    }
                    if (avatarHash.isNotEmpty()) PrefManager.steamUserAvatarHash = avatarHash
                    if (playerName.isNotEmpty()) PrefManager.steamUserName = playerName
                    PluviaApp.events.emit(SteamEvent.PersonaStateReceived(svc.localPersona.value))
                    Timber.i("user persona via wn-steam-client: name='$playerName'")

                    if (playerName.isNotEmpty()) {
                        com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                            .setPersonaName(playerName)
                    }
                    com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                        .setPersonaState(obj.optInt("personaState"))

                    if (avatarHash.isNotEmpty() && avatarHash.length % 2 == 0) {
                        val selfSid = withWnSession { it.steamId() } ?: 0L
                        if (selfSid != 0L) {
                            val bytes = ByteArray(avatarHash.length / 2)
                            var ok = true
                            for (k in bytes.indices) {
                                val hi = Character.digit(avatarHash[k * 2], 16)
                                val lo = Character.digit(avatarHash[k * 2 + 1], 16)
                                if (hi < 0 || lo < 0) { ok = false; break }
                                bytes[k] = ((hi shl 4) or lo).toByte()
                            }
                            if (ok) {
                                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                                    .setFriendAvatarHash(selfSid, bytes)
                                com.winlator.cmod.feature.stores.steam.wnsteam.AvatarFetcher
                                    .enqueueAllTiers(selfSid, avatarHash)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "requestUserPersona: persona parse failed")
                }
            }

        suspend fun getSelfCurrentlyPlayingAppId(): Int? =
            withContext(Dispatchers.IO) {
                val self = instance?.localPersona?.value ?: return@withContext null
                if (self.isPlayingGame) self.gameAppID else null
            }

        suspend fun kickPlayingSession(onlyGame: Boolean = true): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    instance?._isPlayingBlocked?.value = true
                    // Kick + wait: the server-pushed CMsgClientPlayingSessionState updates the playing-blocked cache; poll it for the unblock.
                    val cleared = withWnSession { session ->
                        // Invalidate the playing-blocked cache before the kick: the session is reused, so the loop must only observe a post-kick server push.
                        session.markPlayingBlocked()
                        session.kickPlayingSession(onlyGame)
                        val deadline = System.currentTimeMillis() + 5000
                        var ok = false
                        while (System.currentTimeMillis() < deadline) {
                            delay(100)
                            if (!session.isPlayingBlocked()) { ok = true; break }
                        }
                        ok
                    } == true
                    instance?._isPlayingBlocked?.value = !cleared
                    cleared
                } catch (_: Exception) {
                    false
                }
            }

        // Fire-and-skip: use the already-logged-on wn-session if one exists, else return false without bringing one up — clears server-side "playing" state on game termination without blocking teardown 5-15s on a cold bring-up.
        @JvmStatic
        suspend fun kickPlayingSessionIfReady(onlyGame: Boolean = true): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    val session = wnSession?.takeIf { it.state() == 3 } ?: return@withContext false
                    session.markPlayingBlocked()
                    session.kickPlayingSession(onlyGame)
                    // kickPlayingSession clears only playing-BLOCKED, not the launcher's games-played registration — clear that too (empty CMsgClientGamesPlayed) or the next launch hits AlreadyRunning.
                    session.notifyGamesPlayed("[]", EOSType.AndroidUnknown.code())
                    instance?._isPlayingBlocked?.value = true
                    Timber.i("kickPlayingSessionIfReady: dispatched (onlyGame=$onlyGame) + cleared games-played")
                    true
                } catch (e: Throwable) {
                    Timber.w(e, "kickPlayingSessionIfReady failed")
                    false
                }
            }

        // Single caller only needs whether any licenses exist — returns the raw cached rows.
        suspend fun getLicensesFromDb(): List<CachedLicense> =
            withContext(Dispatchers.IO) {
                instance?.cachedLicenseDao?.getAll() ?: emptyList()
            }

        fun getPkgInfoOf(appId: Int): SteamLicense? =
            runBlocking(Dispatchers.IO) {
                instance?.licenseDao?.findLicense(
                    instance?.appDao?.findApp(appId)?.packageId ?: INVALID_PKG_ID,
                )
            }

        fun getAppInfoOf(appId: Int): SteamApp? =
            runBlocking(Dispatchers.IO) {
                val dao = instance?.appDao ?: runCatching { PluviaDatabase.getInstance().steamAppDao() }.getOrNull()
                dao?.findApp(appId)
            }

        fun getDownloadingAppInfoOf(appId: Int): DownloadingAppInfo? =
            runBlocking(Dispatchers.IO) {
                instance?.downloadingAppInfoDao?.getDownloadingApp(appId)
            }

        fun getDownloadableDlcAppsOf(appId: Int): List<SteamApp>? =
            runBlocking(Dispatchers.IO) { instance?.appDao?.findDownloadableDLCApps(appId) }

        fun getSelectableDlcAppsOf(appId: Int): List<SteamApp> =
            runBlocking(Dispatchers.IO) {
                val service = instance ?: return@runBlocking emptyList()
                val appInfo = service.appDao.findApp(appId) ?: return@runBlocking emptyList()
                val preferredLanguage = PrefManager.containerLanguage
                val has64Bit =
                    appInfo.depots.values.any {
                        it.osArch == OSArch.Arch64 &&
                            (it.osList.contains(OS.windows) || (it.osList.isEmpty() || it.osList.contains(OS.none)))
                    }

                val mainAppDlcIds =
                    appInfo.depots.values
                        .asSequence()
                        .filter { depot ->
                            depot.dlcAppId != INVALID_APP_ID &&
                                filterForDownloadableDepots(depot, has64Bit, preferredLanguage, ownedDlc = null)
                        }.map { it.dlcAppId }

                val indirectDlcApps = service.appDao.findDownloadableDLCApps(appId).orEmpty()
                val hiddenDlcApps = service.appDao.findHiddenDLCApps(appId).orEmpty()
                val dlcAppsById = (indirectDlcApps + hiddenDlcApps).associateBy { it.id }
                val indirectDlcIds = indirectDlcApps.map { it.id }.asSequence()
                val hiddenDlcIds = hiddenDlcApps.map { it.id }.asSequence()
                val groupedBaseDlcIds =
                    getGroupedBaseAppDlcIds(
                        appInfo = appInfo,
                        preferredLanguage = preferredLanguage,
                        has64Bit = has64Bit,
                    ).asSequence()

                val declaredDlcIds = appInfo.dlcAppIds.asSequence()

                val selectableDlcIds = (mainAppDlcIds + groupedBaseDlcIds + indirectDlcIds + hiddenDlcIds + declaredDlcIds).distinct().toList()

                if (selectableDlcIds.isEmpty()) return@runBlocking emptyList()

                // Single bulk SELECT instead of N findApp() calls; keeps DB-first by overlaying dlcAppsById only for IDs not in the DB.
                val dlcFromDb = service.appDao.findApps(selectableDlcIds).associateBy { it.id }
                selectableDlcIds
                    .mapNotNull { dlcAppId ->
                        (dlcFromDb[dlcAppId] ?: dlcAppsById[dlcAppId])?.takeIf { it.name.isNotBlank() }
                    }
                    .sortedBy { it.name.lowercase() }
            }

        fun getHiddenDlcAppsOf(appId: Int): List<SteamApp>? = runBlocking(Dispatchers.IO) { instance?.appDao?.findHiddenDLCApps(appId) }

        fun getInstalledApp(appId: Int): AppInfo? =
            runBlocking(Dispatchers.IO) {
                val dao = instance?.appInfoDao ?: runCatching { PluviaDatabase.getInstance().appInfoDao() }.getOrNull()
                dao?.getInstalledApp(appId)
            }

        fun getInstalledDepotsOf(appId: Int): List<Int>? = getTrustedInstalledAppInfo(appId)?.downloadedDepots

        fun getInstalledDlcDepotsOf(appId: Int): List<Int>? {
            val installedApp = getTrustedInstalledAppInfo(appId)
            val installedDlcAppIds = installedApp?.dlcDepots.orEmpty().toMutableSet()
            installedDlcAppIds.addAll(getInstalledSelectableDlcAppIds(appId))

            if (installedApp != null && installedDlcAppIds != installedApp.dlcDepots.toSet()) {
                runBlocking(Dispatchers.IO) {
                    instance?.appInfoDao?.update(installedApp.copy(dlcDepots = installedDlcAppIds.sorted()))
                }
            }

            return installedDlcAppIds.sorted()
        }

        fun repairInstalledMetadataFromDisk(): Int {
            return runBlocking(Dispatchers.IO) {
                val db = PluviaDatabase.getInstance()
                val apps =
                    runCatching { db.steamAppDao().getAllAsList() }.getOrElse {
                        Timber.e(it, "Failed to load Steam apps for install repair")
                        return@runBlocking 0
                    }

                var repairedCount = 0
                for (app in apps) {
                    val installedApp = db.appInfoDao().getInstalledApp(app.id)
                    if (installedApp?.isDownloaded == true) continue
                    if (tryRecoverInstalledAppInfo(app.id) != null) {
                        repairedCount++
                    }
                }
                repairedCount
            }
        }

        fun maybeRepairInstalledMetadataOnStartup(context: Context) {
            val appContext = context.applicationContext
            if (!hasStoredCredentials(appContext)) return

            if (startupMetadataRepairJob?.isActive == true) return

            startupMetadataRepairJob =
                CoroutineScope(Dispatchers.IO).launch {
                    if (!shouldRepairInstalledMetadata()) return@launch
                delay(1500L)
                val repairedCount = repairInstalledMetadataFromDisk()
                if (repairedCount > 0) {
                    Timber.i("Startup metadata repair recovered $repairedCount Steam install record(s)")
                }
            }
        }

        fun getAllDownloads(): Map<Int, DownloadInfo> = downloadJobs

        fun getAppDownloadInfo(appId: Int): DownloadInfo? = downloadJobs[appId]

        fun isAppInstalled(appId: Int): Boolean {
            return getTrustedInstalledAppInfo(appId) != null
        }

        fun uninstallApp(
            appId: Int,
            onComplete: (Boolean) -> Unit = {},
        ) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val dirPath = getAppDirPath(appId)
                    val deleteCheck =
                        StoreInstallPathSafety.checkInstallDirDelete(
                            instance?.applicationContext ?: DownloadService.appContext,
                            dirPath,
                            protectedRoots = steamProtectedInstallRoots(),
                        )
                    if (!deleteCheck.allowed) {
                        Timber.e("Refusing to uninstall Steam appId=$appId from '$dirPath': ${deleteCheck.reason}")
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onComplete(false)
                        }
                        return@launch
                    }

                    val dirFile = java.io.File(dirPath)
                    if (dirFile.exists() && dirFile.isDirectory) {
                        val deleted = deleteRecursivelyWithRetries(dirFile)
                        if (!deleted) {
                            Timber.e("Failed to fully delete Steam appId=$appId at '$dirPath'")
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                onComplete(false)
                            }
                            return@launch
                        }
                    }

                    cleanupSteamAppCacheDirs(appId)

                    val appInfo = getInstalledApp(appId)
                    if (appInfo != null) {
                        instance?.appInfoDao?.update(appInfo.copy(isDownloaded = false))
                    }
                    runCatching { PrefManager.clearInstalledBranchState(appId) }
                    LibraryShortcutUtils.deleteSteamShortcuts(PluviaApp.instance, appId)
                    PluviaApp.events.emit(AndroidEvent.LibraryInstallStatusChanged(appId))
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onComplete(true)
                    }
                } catch (e: Exception) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onComplete(false)
                    }
                }
            }
        }

        fun getAppDlc(appId: Int): Map<Int, DepotInfo> =
            getAppInfoOf(appId)
                ?.let {
                    it.depots.filter { it.value.dlcAppId != INVALID_APP_ID }
                }.orEmpty()

        suspend fun getOwnedAppDlc(appId: Int): Map<Int, DepotInfo> {
            val ownedGameIds =
                runCatching {
                    val steamId = userSteamId ?: return@runCatching emptySet<Int>()
                    getOwnedGames(steamId.convertToUInt64()).map { it.appId }.toHashSet()
                }.getOrDefault(emptySet())

            return getAppDlc(appId)
                .filter { (_, depot) ->
                    when {
                        // Base-game depots always download
                        depot.dlcAppId == INVALID_APP_ID -> true

                        // ① licence cache — DLC app IDs live inside package rows, not as package IDs.
                        runBlocking(Dispatchers.IO) {
                            instance?.licenseDao?.countLicensesForApp(depot.dlcAppId) ?: 0
                        } > 0 -> true

                        // ② PICS row
                        instance?.appDao?.findApp(depot.dlcAppId) != null -> true

                        // ③ owned-games list
                        depot.dlcAppId in ownedGameIds -> true

                        // ④ final online / cached call
                        else -> false
                    }
                }.toMap()
        }

        fun getMainAppDlcIdsWithoutProperDepotDlcIds(appId: Int): MutableList<Int> {
            val mainAppDlcIds = mutableListOf<Int>()
            val hiddenDlcAppIds = getHiddenDlcAppsOf(appId).orEmpty().map { it.id }

            val appInfo = getAppInfoOf(appId)
            if (appInfo != null) {
                // Hidden DLC can be represented by a single placeholder depot.
                val checkingAppDlcIds =
                    appInfo.depots
                        .filter { it.value.dlcAppId != INVALID_APP_ID }
                        .map { it.value.dlcAppId }
                        .distinct()
                checkingAppDlcIds.forEach { checkingDlcId ->
                    val checkMap = appInfo.depots.filter { it.value.dlcAppId == checkingDlcId }
                    if (checkMap.size == 1) {
                        val depotInfo = checkMap[checkMap.keys.first()]!!
                        if (depotInfo.osList.contains(OS.none) &&
                            depotInfo.manifests.isEmpty() &&
                            hiddenDlcAppIds.isNotEmpty() && hiddenDlcAppIds.contains(checkingDlcId)
                        ) {
                            mainAppDlcIds.add(checkingDlcId)
                        }
                    }
                }
            }

            return mainAppDlcIds
        }

        /** Refresh owned games: query Steam, diff the local DB, queue PICS for new apps; returns the count of newly discovered appIds scheduled for PICS. */
        suspend fun refreshOwnedGamesFromServer(): Int =
            withContext(Dispatchers.IO) {
                val service = instance ?: return@withContext 0
                val unifiedFriends = service._unifiedFriends ?: return@withContext 0
                val steamId = userSteamId ?: return@withContext 0

                runCatching {
                    val ownedGames = unifiedFriends.getOwnedGames(steamId.convertToUInt64())
                    val remoteAppIds = ownedGames.map { it.appId }.filter { it > 0 }.toSet()
                    if (remoteAppIds.isEmpty()) {
                        return@runCatching 0
                    }

                    val localAppIds = service.appDao.getAllAppIds().toSet()
                    val missingAppIds = remoteAppIds - localAppIds
                    if (missingAppIds.isEmpty()) {
                        return@runCatching 0
                    }

                    missingAppIds
                        .chunked(MAX_PICS_BUFFER)
                        .forEach { chunk ->
                            val requests = chunk.map { PICSRequest(id = it) }
                            service.appPicsChannel.send(requests)
                        }

                    missingAppIds.size
                }.onFailure { error ->
                    Timber.tag("SteamService").e(error, "Failed to refresh owned games from server")
                }.getOrDefault(0)
            }

        /** Common filter for downloadable depots. */
        fun filterForDownloadableDepots(
            depot: DepotInfo,
            has64Bit: Boolean,
            preferredLanguage: String,
            ownedDlc: Map<Int, DepotInfo>?,
        ): Boolean {
            if (depot.manifests.isEmpty() && depot.encryptedManifests.isNotEmpty()) {
                return false
            }
            // 1. Has something to download
            if (depot.manifests.isEmpty() && !depot.sharedInstall) {
                return false
            }
            // 2. Supported OS
            if (!(
                    depot.osList.contains(OS.windows) ||
                        (!depot.osList.contains(OS.linux) && !depot.osList.contains(OS.macos))
                )
            ) {
                return false
            }
            // 3. Arch: allow 64-bit and Unknown always; 32-bit only when no 64-bit depot exists.
            val archOk =
                when (depot.osArch) {
                    OSArch.Arch64, OSArch.Unknown -> true
                    OSArch.Arch32 -> !has64Bit
                    else -> false
                }
            if (!archOk) return false
            // 4. DLC you actually own
            if (depot.dlcAppId != INVALID_APP_ID && ownedDlc != null && !ownedDlc.containsKey(depot.depotId)) {
                return false
            }
            // 5. Language filter - if depot has language, it must match preferred language
            if (depot.language.isNotEmpty() && !depot.language.equals(preferredLanguage, ignoreCase = true)) {
                return false
            }

            return true
        }

        fun getMainAppDepots(appId: Int): Map<Int, DepotInfo> {
            val appInfo = getAppInfoOf(appId) ?: return emptyMap()
            val ownedDlc = runBlocking { getOwnedAppDlc(appId) }
            val preferredLanguage = PrefManager.containerLanguage
            val entitledDepotIds = getEntitledDepotIds(appInfo.packageId)

            // If the game ships any 64-bit depot for Windows, prefer those and ignore x86 ones
            val has64Bit =
                appInfo.depots.values.any {
                    it.osArch == OSArch.Arch64 && (it.osList.contains(OS.windows) || (it.osList.isEmpty() || it.osList.contains(OS.none)))
                }

            return dropSupersededDepots(
                appId,
                appInfo.depots
                    .asSequence()
                    .filter { (depotId, depot) ->
                        return@filter isDepotEntitled(depotId, depot, entitledDepotIds) &&
                            filterForDownloadableDepots(depot, has64Bit, preferredLanguage, ownedDlc)
                    }.associate { it.toPair() },
            )
        }

        /** Downloadable depots for an app, including all DLCs. */
        fun getDownloadableDepots(
            appId: Int,
            preferredLanguage: String = PrefManager.containerLanguage,
        ): Map<Int, DepotInfo> {
            val appInfo = getAppInfoOf(appId) ?: return emptyMap()
            val ownedDlc = runBlocking { getOwnedAppDlc(appId) }
            val entitledDepotIds = getEntitledDepotIds(appInfo.packageId)

            // If the game ships any 64-bit depot for Windows, prefer those and ignore x86 ones
            val has64Bit =
                appInfo.depots.values.any {
                    it.osArch == OSArch.Arch64 && (it.osList.contains(OS.windows) || (it.osList.isEmpty() || it.osList.contains(OS.none)))
                }

            val map = mutableMapOf<Int, DepotInfo>()
            for ((depotId, depot) in appInfo.depots) {
                if (isDepotEntitled(depotId, depot, entitledDepotIds) &&
                    filterForDownloadableDepots(depot, has64Bit, preferredLanguage, ownedDlc)
                ) {
                    map[depotId] = depot
                }
            }

            val indirectDlcApps = getDownloadableDlcAppsOf(appId).orEmpty()
            for (dlcApp in indirectDlcApps) {
                val entitledDlcDepotIds = getEntitledDepotIds(dlcApp.packageId)
                for ((depotId, depot) in dlcApp.depots) {
                    if (isDepotEntitled(depotId, depot, entitledDlcDepotIds) &&
                        filterForDownloadableDepots(depot, has64Bit, preferredLanguage, null)
                    ) {
                        map[depotId] =
                            DepotInfo(
                                depotId = depot.depotId,
                                dlcAppId = dlcApp.id, // Set to DLC App ID
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
            }

            return dropSupersededDepots(appId, map)
        }

        internal data class GroupedBaseAppDlcDepot(
            val depotId: Int,
            val dlcAppId: Int,
            val depot: DepotInfo,
        )

        fun getSelectedManifestSizes(
            appId: Int,
            userSelectedDlcAppIds: Collection<Int> = emptyList(),
            preferredLanguage: String = PrefManager.containerLanguage,
            branch: String = getSelectedBranch(appId),
        ): ManifestSizes {
            ensureFreshDepotData(appId)
            val selectedDepots = getSelectedDownloadDepots(appId, userSelectedDlcAppIds, preferredLanguage, branch)
            if (selectedDepots.isEmpty()) return ManifestSizes()

            return calculateManifestSizes(selectedDepots.values, branch)
        }

        fun getInstallableSelectedManifestSizes(
            appId: Int,
            userSelectedDlcAppIds: Collection<Int> = emptyList(),
            preferredLanguage: String = PrefManager.containerLanguage,
            branch: String = getSelectedBranch(appId),
        ): ManifestSizes {
            ensureFreshDepotData(appId)
            val selectedDepots = getSelectedDownloadDepots(appId, userSelectedDlcAppIds, preferredLanguage, branch)
            val installableDepots =
                filterAlreadyInstalledDepots(
                    appId = appId,
                    depots = selectedDepots,
                    includeInstalledDepots = false,
                )
            if (installableDepots.isEmpty()) return ManifestSizes()

            return calculateManifestSizes(installableDepots.values, branch)
        }

        fun getDlcOnlyManifestSizes(
            appId: Int,
            dlcAppId: Int,
            preferredLanguage: String = PrefManager.containerLanguage,
            branch: String = getSelectedBranch(appId),
        ): ManifestSizes {
            val service = instance ?: return ManifestSizes()
            ensureFreshDepotData(appId)
            ensureFreshDepotData(dlcAppId)
            val mainAppInfo =
                runBlocking(Dispatchers.IO) { service.appDao.findApp(appId) } ?: return ManifestSizes()
            val has64Bit =
                mainAppInfo.depots.values.any {
                    it.osArch == OSArch.Arch64 &&
                        (it.osList.contains(OS.windows) || (it.osList.isEmpty() || it.osList.contains(OS.none)))
                }

            val mainAppDlcDepots =
                getSelectedBaseAppDlcContentDepots(appId, listOf(dlcAppId), preferredLanguage, branch).values

            val dlcAppInfo = runBlocking(Dispatchers.IO) { service.appDao.findApp(dlcAppId) }
            val dlcAppDepots =
                dlcAppInfo?.depots?.values?.filter { depot ->
                    filterForDownloadableDepots(depot, has64Bit, preferredLanguage, ownedDlc = null)
                }.orEmpty()

            val combined = (mainAppDlcDepots + dlcAppDepots).associateBy { it.depotId }.values
            if (combined.isEmpty()) return ManifestSizes()

            return calculateManifestSizes(combined, branch)
        }

        fun getAppDirName(app: SteamApp?): String {
            val configuredInstallDir =
                app?.config?.installDir
                    .orEmpty()
                    .trim()
                    .takeUnless(::isSuspiciousSteamInstallDirLeaf)

            return configuredInstallDir.takeUnless { it.isNullOrEmpty() }
                ?: app?.name.orEmpty()
        }

        // Invoked by name via reflection from SteamBridge — keep in the companion; do not move to an extension file.
        fun getAppDirPath(gameId: Int): String {
            val info = getAppInfoOf(gameId)

            // Check custom install dir first (full absolute path only): installDir from PICS metadata is just a folder name, custom installs store the full path.
            val customDir = info?.installDir.orEmpty()
            if (customDir.isNotEmpty() && (customDir.startsWith("/") || customDir.contains(File.separator))) {
                val normalizedCustomDir = normalizeInstallPath(customDir)
                if (!isSuspiciousSteamInstallPath(normalizedCustomDir)) {
                    // It's a full path (custom install location)
                    return normalizedCustomDir
                }
                Timber.w(
                    "getAppDirPath: ignoring suspicious stored install path %s for appId=%d",
                    normalizedCustomDir,
                    gameId,
                )
            }

            val appName = getAppDirName(info)
            val oldName = info?.name.orEmpty()
            val candidateNames =
                buildList {
                    if (appName.isNotEmpty()) add(appName)
                    if (oldName.isNotEmpty() && oldName != appName) add(oldName)
                }

            // No metadata: fall back to the durably-recorded install path (disk-validated, appId-specific; never a shared root).
            if (candidateNames.isEmpty()) {
                val recordedPath = getInstalledApp(gameId)?.installPath.orEmpty()
                if (recordedPath.isNotEmpty()) {
                    val normalizedRecorded = normalizeInstallPath(recordedPath)
                    if (File(normalizedRecorded).isDirectory && !isSuspiciousSteamInstallPath(normalizedRecorded)) {
                        return normalizedRecorded
                    }
                }
                Timber.w("getAppDirPath: no metadata to resolve install dir for appId=%d", gameId)
                return ""
            }

            // Respect user-selected default download folder
            val context = PluviaApp.instance.applicationContext
            if (context != null) {
                val storeDefaultUri = if (PrefManager.useSingleDownloadFolder) PrefManager.defaultDownloadFolder else PrefManager.steamDownloadFolder
                if (storeDefaultUri.isNotEmpty()) {
                    val baseDir =
                        com.winlator.cmod.shared.io.FileUtils
                            .getFilePathFromUri(context, android.net.Uri.parse(storeDefaultUri))
                    Timber.i("getAppDirPath: resolved baseDir $baseDir from URI $storeDefaultUri")
                    if (baseDir != null) {
                        for (candidateName in candidateNames) {
                            val candidatePath = Paths.get(baseDir, candidateName)
                            if (Files.exists(candidatePath)) {
                                Timber.i("getAppDirPath: found existing path $candidatePath")
                                return normalizeInstallPath(candidatePath.pathString)
                            }
                        }
                        val targetName = candidateNames.firstOrNull().orEmpty()
                        val targetPath = Paths.get(baseDir, targetName)
                        // If it doesn't exist yet, this is where we'll install it
                        Timber.i("getAppDirPath: returning new path $targetPath")
                        return normalizeInstallPath(targetPath.pathString)
                    }
                }
            }

            for (basePath in allInstallPaths) {
                for (candidateName in candidateNames) {
                    val candidate = Paths.get(basePath, candidateName)
                    if (Files.exists(candidate)) return normalizeInstallPath(candidate.pathString)
                }
            }

            // Nothing on disk yet – default to whatever location you want new installs to use
            val targetName = candidateNames.firstOrNull().orEmpty()
            if (PrefManager.useExternalStorage) {
                return normalizeInstallPath(Paths.get(externalAppInstallPath, targetName).pathString)
            }
            return normalizeInstallPath(Paths.get(internalAppInstallPath, targetName).pathString)
        }

        /** Resolves the executable for an installed Steam app from its appinfo `config.launch` entries — depot manifests store filenames AES-encrypted and are never decrypted, so scanning them is useless. */
        // Invoked by name via reflection from SteamBridge — keep in the companion; do not move to an extension file.
        fun getInstalledExe(appId: Int): String =
            getWindowsLaunchInfos(appId).firstOrNull()?.executable ?: ""

        fun getLaunchExecutable(
            appId: String,
            container: Container,
        ): String {
            val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
            return container.executablePath.ifEmpty { getInstalledExe(gameId) }
        }

        suspend fun deleteApp(appId: Int): Boolean =
            withContext(Dispatchers.IO) {
                val appDirPath = getAppDirPath(appId)
                val deleteCheck =
                    StoreInstallPathSafety.checkInstallDirDelete(
                        instance?.applicationContext ?: DownloadService.appContext,
                        appDirPath,
                        protectedRoots = steamProtectedInstallRoots(),
                    )

                // Guard against accidental root deletion if path resolution failed.
                if (!deleteCheck.allowed) {
                    Timber.e("Refusing to delete appId=$appId from '$appDirPath': ${deleteCheck.reason}")
                    return@withContext false
                }

                // If an active download exists, stop it and wait briefly before deleting files.
                downloadJobs[appId]?.let { info ->
                    info.isDeleting = true
                    info.cancel("Cancelled for delete")
                    info.awaitCompletion(timeoutMs = 5000L)
                    removeDownloadJob(appId)
                }

                MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                clearPersistedProgressSnapshot(appDirPath)

                cleanupSteamAppCacheDirs(appId)

                // Remove from DB synchronously so immediate reinstall cannot race with stale metadata.
                with(instance!!) {
                    db.withTransaction {
                        appInfoDao.deleteApp(appId)
                        changeNumbersDao.deleteByAppId(appId)
                        fileChangeListsDao.deleteByAppId(appId)
                        downloadingAppInfoDao.deleteApp(appId)

                        appDao.findApp(appId)?.let { steamApp ->
                            if (steamApp.installDir.isNotEmpty()) {
                                appDao.update(steamApp.copy(installDir = ""))
                                Timber.i("Cleared installDir for appId $appId in DB")
                            }
                        }

                        val indirectDlcAppIds = getDownloadableDlcAppsOf(appId).orEmpty().map { it.id }
                        indirectDlcAppIds.forEach { dlcAppId ->
                            appInfoDao.deleteApp(dlcAppId)
                            changeNumbersDao.deleteByAppId(dlcAppId)
                            fileChangeListsDao.deleteByAppId(dlcAppId)
                        }
                    }
                }

                return@withContext deleteRecursivelyWithRetries(File(appDirPath))
            }

        fun setCustomInstallPath(
            appId: Int,
            customInstallPath: String,
        ): String {
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

            runBlocking(Dispatchers.IO) {
                instance?.appDao?.findApp(appId)?.let { steamApp ->
                    instance?.appDao?.update(steamApp.copy(installDir = finalPath))
                    Timber.i("Updated SteamApp installDir in DB to: $finalPath")
                }
            }
            return finalPath
        }

        fun downloadApp(appId: Int): DownloadInfo? = downloadApp(appId, dlcAppIdsHint = null)

        /** Resume/start entry point; [dlcAppIdsHint] (the coordinator's persisted [DownloadRecord.selectedDlcs]) is authoritative and overrides the legacy fallback chain. Pass null for legacy callers — the record is then looked up here. */
        fun downloadApp(appId: Int, dlcAppIdsHint: List<Int>?): DownloadInfo? {
            val currentDownloadInfo = downloadJobs[appId]
            if (currentDownloadInfo != null) {
                if (!currentDownloadInfo.isActive()) {
                    removeDownloadJob(appId)
                } else {
                    return downloadApp(appId, currentDownloadInfo.downloadingAppIds, isUpdateOrVerify = false)
                }
            }

            // No authoritative DLC list from the caller → recover from the coordinator's persisted record; if missing, fall through to the DownloadingAppInfo-based recovery below.
            val recordDlcIds: List<Int>? = dlcAppIdsHint
                ?: runCatching {
                    runBlocking(Dispatchers.IO) {
                        val record = DownloadCoordinator.findRecord(
                            DownloadRecord.STORE_STEAM,
                            appId.toString(),
                        )
                        record?.selectedDlcs
                            ?.split(',')
                            ?.mapNotNull { it.trim().toIntOrNull() }
                    }
                }.getOrNull()

            val downloadingAppInfo = getDownloadingAppInfoOf(appId)
            val appDirPath = getAppDirPath(appId)
            val hasCompleteMarker = MarkerUtils.hasMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            val hasPartialFiles = hasPartialDownloadFiles(appDirPath)
            val hasPersistedMetadata = hasPersistedDepotResumeMetadata(appDirPath)
            val hasResumablePayload =
                if (hasCompleteMarker) {
                    downloadingAppInfo != null || hasPersistedMetadata || recordDlcIds != null
                } else {
                    hasPartialFiles
                }
            if (hasResumablePayload) {
                // Trust order (do NOT union — an empty authoritative list means "no DLCs"): 1) coordinator record (authoritative, durable; empty = base game only), 2) DownloadingAppInfo row, 3) inferResumeDlcAppIds (depots with bytes), 4) resolveInstalledDlcIdsForUpdateOrVerify (installed DLCs).
                val resumeDlcAppIds: List<Int> =
                    recordDlcIds
                        ?: downloadingAppInfo?.dlcAppIds
                        ?: run {
                            val inferred = inferResumeDlcAppIds(appId, appDirPath)
                            if (inferred.isNotEmpty()) inferred
                            else resolveInstalledDlcIdsForUpdateOrVerify(appId)
                        }
                return downloadApp(
                    appId = appId,
                    dlcAppIds = resumeDlcAppIds,
                    includeInstalledDepots = false,
                    enableVerify = false,
                    allowPersistedProgress = true,
                    hasPersistedResumeRow = downloadingAppInfo != null || recordDlcIds != null,
                )
            }

            if (downloadingAppInfo != null) {
                runBlocking(Dispatchers.IO) {
                    instance?.downloadingAppInfoDao?.deleteApp(appId)
                }
            }

            if (hasCompleteMarker && !hasPersistedMetadata) {
                clearPersistedProgressSnapshot(appDirPath)
            }

            if (!hasPartialFiles) {
                clearPersistedProgressSnapshot(appDirPath)
            }

            return downloadApp(
                appId = appId,
                dlcAppIds = resolveInstalledDlcIdsForUpdateOrVerify(appId),
                includeInstalledDepots = false,
                enableVerify = false,
                allowPersistedProgress = false,
            )
        }

        fun downloadAppForUpdate(
            appId: Int,
            targetDepotIds: Collection<Int> = emptyList(),
        ): DownloadInfo? =
            downloadApp(
                appId,
                resolveInstalledDlcIdsForUpdateOrVerify(appId),
                includeInstalledDepots = true,
                enableVerify = false,
                allowPersistedProgress = false,
                downloadTaskType = DownloadRecord.TASK_UPDATE,
                targetDepotIds = targetDepotIds.toSet().takeIf { it.isNotEmpty() },
            )

        fun downloadAppForVerify(appId: Int): DownloadInfo? =
            downloadApp(
                appId,
                resolveInstalledDlcIdsForUpdateOrVerify(appId),
                includeInstalledDepots = true,
                enableVerify = true,
                allowPersistedProgress = false,
                downloadTaskType = DownloadRecord.TASK_VERIFY,
            )

        fun downloadApp(
            appId: Int,
            dlcAppIds: List<Int>,
            isUpdateOrVerify: Boolean,
            customInstallPath: String? = null,
        ): DownloadInfo? {
            // Backward-compatible API: isUpdateOrVerify=true includes already-downloaded depots (update scope) but does not force verify.
            return downloadApp(
                appId = appId,
                dlcAppIds = dlcAppIds,
                includeInstalledDepots = isUpdateOrVerify,
                enableVerify = false,
                allowPersistedProgress = false,
                customInstallPath = customInstallPath,
            )
        }

        fun isImageFsInstalled(context: Context): Boolean = ImageFs.find(context).isValid()

        fun isSteamInstallable(context: Context): Boolean = File(context.filesDir, "steam.tzst").exists()

        fun isFileInstallable(
            context: Context,
            filename: String,
        ): Boolean = File(context.filesDir, filename).exists()

        suspend fun fetchFile(
            url: String,
            dest: File,
            onProgress: (Float) -> Unit,
        ) = withContext(Dispatchers.IO) {
            val tmp = File(dest.absolutePath + ".part")
            try {
                val http = SteamUtils.http

                val req = Request.Builder().url(url).build()
                http.newCall(req).execute().use { rsp ->
                    check(rsp.isSuccessful) { "HTTP ${rsp.code}" }
                    val body = rsp.body ?: error("empty body")
                    val total = body.contentLength()
                    tmp.outputStream().use { out ->
                        body.byteStream().copyTo(out, 8 * 1024) { read ->
                            onProgress(read.toFloat() / total)
                        }
                    }
                    if (total > 0 && tmp.length() != total) {
                        tmp.delete()
                        error("incomplete download")
                    }
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }

        suspend fun fetchFileWithFallback(
            fileName: String,
            dest: File,
            context: Context,
            onProgress: (Float) -> Unit,
        ) = withContext(Dispatchers.IO) {
            val urls = downloadUrlsFor(fileName)
            var lastError: Exception? = null
            for ((index, url) in urls.withIndex()) {
                try {
                    fetchFile(url, dest, onProgress)
                    return@withContext
                } catch (e: Exception) {
                    lastError = e
                    if (index < urls.lastIndex) {
                        Timber.w(e, "Download failed from $url; retrying with next URL")
                    }
                }
            }

            dest.delete()
            withContext(Dispatchers.Main) {
                val msg = "Download failed with ${lastError?.message ?: "unknown error"}. Please disable VPN or try a different network."
                WinToast.show(context.applicationContext, msg, android.widget.Toast.LENGTH_LONG)
            }
            throw IOException(
                "Failed to download $fileName. Please check your network connection or try a VPN.",
                lastError,
            )
        }

        /** copyTo with progress callback */
        private inline fun InputStream.copyTo(
            out: OutputStream,
            bufferSize: Int = DEFAULT_BUFFER_SIZE,
            progress: (Long) -> Unit,
        ) {
            val buf = ByteArray(bufferSize)
            var bytesRead: Int
            var total = 0L
            while (read(buf).also { bytesRead = it } >= 0) {
                if (bytesRead == 0) continue
                out.write(buf, 0, bytesRead)
                total += bytesRead
                progress(total)
            }
        }

        fun downloadFile(
            onDownloadProgress: (Float) -> Unit,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            context: Context,
            fileName: String,
        ) = parentScope.async {
            Timber.i("$fileName will be downloaded")
            val dest = File(context.filesDir, fileName)
            Timber.d("Downloading $fileName to " + dest.toString())
            fetchFileWithFallback(fileName, dest, context, onDownloadProgress)
        }

        fun resolveSteamControllerVdfText(appId: Int): String? {
            val config = getAppInfoOf(appId)?.config ?: return null
            return when (config.steamControllerTemplateIndex) {
                1 -> {
                    readDownloadedSteamInputTemplate(appId)
                }

                13 -> {
                    val manifestFile =
                        resolveSteamInputManifestFile(appId, getAppDirPath(appId))
                            ?: return null
                    loadConfigFromManifest(manifestFile)
                }

                2, 12 -> {
                    readBuiltInSteamInputTemplate("controller_xboxone_gamepad_fps.vdf")
                }

                6 -> {
                    readBuiltInSteamInputTemplate("controller_xboxone_wasd.vdf")
                }

                4, 5 -> {
                    readBuiltInSteamInputTemplate("gamepad_joystick.vdf")
                }

                else -> {
                    readBuiltInSteamInputTemplate("gamepad_joystick.vdf")
                }
            }
        }

        fun getWindowsLaunchInfos(appId: Int): List<LaunchInfo> =
            getAppInfoOf(appId)
                ?.let { appInfo ->
                    appInfo.config.launch.filter { launchInfo ->
                        // since configOS was unreliable and configArch was even more unreliable
                        launchInfo.executable.endsWith(".exe")
                    }
                }.orEmpty()

        suspend fun notifyRunningProcesses(vararg gameProcesses: GameProcessInfo) =
            withContext(Dispatchers.IO) {
                instance?.let { steamInstance ->
                    if (isConnected) {
                        val gamesPlayed =
                            gameProcesses.mapNotNull { gameProcess ->
                                getAppInfoOf(gameProcess.appId)?.let { appInfo ->
                                    getPkgInfoOf(gameProcess.appId)?.let { pkgInfo ->
                                        appInfo.branches[gameProcess.branch]?.let { branch ->
                                            val processId =
                                                gameProcess.processes
                                                    .firstOrNull { it.parentIsSteam }
                                                    ?.processId
                                                    ?: gameProcess.processes.firstOrNull()?.processId
                                                    ?: 0

                                            val userAccountId = userSteamId!!.accountID.toInt()
                                            GamePlayedInfo(
                                                gameId = gameProcess.appId.toLong(),
                                                processId = processId,
                                                ownerId =
                                                    if (pkgInfo.ownerAccountId.contains(userAccountId)) {
                                                        userAccountId
                                                    } else {
                                                        pkgInfo.ownerAccountId.first()
                                                    },
                                                // Unknown Steam launch source; keep observed value.
                                                launchSource = 100,
                                                gameBuildId = branch.buildId.toInt(),
                                                processIdList = gameProcess.processes,
                                            )
                                        }
                                    }
                                }
                            }

                        Timber.i(
                            "GameProcessInfo:%s",
                            gamesPlayed.joinToString("\n") { game ->
                                """
                        |   processId: ${game.processId}
                        |   gameId: ${game.gameId}
                        |   processes: ${
                                    game.processIdList.joinToString("\n") { process ->
                                        """
                                |   processId: ${process.processId}
                                |   processIdParent: ${process.processIdParent}
                                |   parentIsSteam: ${process.parentIsSteam}
                                        """.trimMargin()
                                    }
                                }
                                """.trimMargin()
                            },
                        )

                        // Report running games via the C++ WN-Steam-Client.
                        val gamesJson = JSONArray()
                        gamesPlayed.forEach { g ->
                            val procs = JSONArray()
                            g.processIdList.forEach { p ->
                                procs.put(
                                    JSONObject()
                                        .put("pid", p.processId)
                                        .put("ppid", p.processIdParent)
                                        .put("isSteam", p.parentIsSteam),
                                )
                            }
                            gamesJson.put(
                                JSONObject()
                                    .put("gameId", g.gameId)
                                    .put("processId", g.processId)
                                    .put("ownerId", g.ownerId)
                                    .put("launchSource", g.launchSource)
                                    .put("gameBuildId", g.gameBuildId)
                                    .put("processes", procs),
                            )
                        }
                        withWnSession { session ->
                            withContext(Dispatchers.IO) {
                                session.notifyGamesPlayed(
                                    gamesJson.toString(),
                                    EOSType.AndroidUnknown.code(),
                                )
                            }
                        }
                    }
                }
            }

        fun beginLaunchApp(
            appId: Int,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            ignorePendingOperations: Boolean = false,
            preferredSave: SaveLocation = SaveLocation.None,
            prefixToPath: (String) -> String,
            isOffline: Boolean = false,
            onProgress: ((message: String, progress: Float) -> Unit)? = null,
        ): Deferred<PostSyncInfo> =
            parentScope.async {
                if (isOffline || !isConnected) {
                    return@async PostSyncInfo(SyncResult.UpToDate)
                }
                if (!tryAcquireSync(appId)) {
                    Timber.w("Cannot launch app when sync already in progress for appId=$appId")
                    return@async PostSyncInfo(SyncResult.InProgress)
                }

                try {
                    val progressWrapper: (String, Float) -> Unit = { msg, prog ->
                        cloudSyncStatus.value = CloudSyncMessage(appId, false, msg, prog)
                        onProgress?.invoke(msg, prog)
                    }
                    var syncResult = PostSyncInfo(SyncResult.UnknownFail)

                    val maxAttempts = 3
                    for (attempt in 1..maxAttempts) {
                        try {
                            val clientId = PrefManager.clientId
                            val steamInstance = instance
                            val appInfo = getAppInfoOf(appId)

                            if (steamInstance != null && appInfo != null) {
                                progressWrapper("Checking Cloud Saves", 0f)
                                val postSyncInfo =
                                    SteamAutoCloud
                                        .syncUserFiles(
                                            appInfo = appInfo,
                                            clientId = clientId,
                                            steamInstance = steamInstance,
                                            preferredSave = preferredSave,
                                            parentScope = parentScope,
                                            prefixToPath = prefixToPath,
                                            onProgress = progressWrapper,
                                        ).await()

                                postSyncInfo?.let { info ->
                                    syncResult = info

                                    if (info.syncResult == SyncResult.Success || info.syncResult == SyncResult.UpToDate) {
                                        Timber.i(
                                            "Signaling app launch:\n\tappId: %d\n\tclientId: %s\n\tosType: %s",
                                            appId,
                                            PrefManager.clientId,
                                            EOSType.AndroidUnknown,
                                        )

                                        // Signal app-launch intent; returns pending-remote-operation codes (empty = clear), null = transport/auth failure.
                                        val pendingRemoteOperations =
                                            withWnSession { session ->
                                                withContext(Dispatchers.IO) {
                                                    session.signalAppLaunchIntent(
                                                        appId = appId,
                                                        clientId = clientId,
                                                        machineName = SteamUtils.getMachineName(steamInstance),
                                                        ignorePending = ignorePendingOperations,
                                                        osType = EOSType.AndroidUnknown.code(),
                                                    )
                                                }
                                            }

                                        if (pendingRemoteOperations == null) {
                                            // Failure — do NOT treat as clear-to-launch (this RPC is the cloud-save conflict guard).
                                            Timber.w("signalAppLaunchIntent failed for app $appId — not proceeding")
                                            syncResult = PostSyncInfo(syncResult = SyncResult.UnknownFail)
                                        } else if (pendingRemoteOperations.isNotEmpty() && !ignorePendingOperations) {
                                            syncResult =
                                                PostSyncInfo(
                                                    syncResult = SyncResult.PendingOperations,
                                                    pendingRemoteOperations = pendingRemoteOperations,
                                                )
                                        } else if (ignorePendingOperations &&
                                            // 1 == ECloudPendingRemoteOperation AppSessionActive
                                            pendingRemoteOperations.any { it == 1 }
                                        ) {
                                            // Kick the other playing session.
                                            withWnSession { session ->
                                                withContext(Dispatchers.IO) {
                                                    session.kickPlayingSession()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            break
                        } catch (e: AsyncJobFailedException) {
                            if (attempt == maxAttempts) {
                                Timber.e(e, "Cloud sync failed after $maxAttempts attempts for app $appId")
                                syncResult = PostSyncInfo(SyncResult.UnknownFail)
                            } else {
                                Timber.w("Cloud sync attempt $attempt failed for app $appId, retrying")
                                delay(1000L * attempt)
                            }
                        }
                    }

                    return@async syncResult
                } finally {
                    cloudSyncStatus.value = null
                    releaseSync(appId)
                }
            }

        /** Lightweight probe: does [appId]'s cloud-save change number differ from the local value? Single metadata call, no file transfer. true = differs, false = in sync, null = couldn't check. */
        suspend fun cloudSavesDiffer(appId: Int): Boolean? {
            val steamInstance = instance ?: return null
            val localCN = steamInstance.changeNumbersDao.getByAppId(appId)?.changeNumber ?: return null
            return try {
                // Cloud.GetAppFileChangelist via the C++ WN-Steam-Client.
                val json =
                    withWnSession { session ->
                        withContext(Dispatchers.IO) { session.getCloudFileList(appId) }
                    } ?: return null
                val currentCN = JSONObject(json).optLong("currentChangeNumber", 0L)
                currentCN != localCN
            } catch (e: Exception) {
                Timber.e(e, "Failed to probe Steam cloud change number for appId=$appId")
                null
            }
        }

        suspend fun getTrackedCloudSaveFiles(appId: Int): List<UserFileInfo>? =
            withContext(Dispatchers.IO) {
                instance?.fileChangeListsDao?.getByAppId(appId)?.userFileInfo
            }

        // getCloudFileList returns the full snapshot, so the newest remote timestamp falls straight out of the conflict snapshot.
        suspend fun getNewestRemoteCloudSaveTimestamp(appId: Int): Long? =
            fetchCloudConflictSnapshot(appId)?.newestRemoteTimestamp

        data class CloudConflictSnapshot(
            val differs: Boolean,
            val newestRemoteTimestamp: Long?,
        )

        /** Public wrapper around the cloud-file-list RPC (Cloud.GetAppFileChangelist); always returns the FULL listing — [changeNumber] is accepted but ignored. Null if not logged on. */
        suspend fun fetchCloudFileList(
            appId: Int,
            @Suppress("UNUSED_PARAMETER") changeNumber: Long = 0L,
        ): SteamAutoCloud.CloudFileChangeList? =
            withContext(Dispatchers.IO) {
                val json =
                    withWnSession { session ->
                        withContext(Dispatchers.IO) { session.getCloudFileList(appId) }
                    } ?: return@withContext null
                try {
                    SteamAutoCloud.parseCloudFileChangeList(json)
                } catch (e: Exception) {
                    Timber.e(e, "fetchCloudFileList failed for appId=%d", appId)
                    null
                }
            }

        /** Download one cloud file's bytes by its prefixed path (pathPrefix/filename). Pure read — no local file, change-number DB, or tracked-files DB write; used by cloud-save pre-capture to back up the current save without mutating local state. Null if not logged on / on failure. */
        suspend fun downloadCloudFileBytes(
            appId: Int,
            prefixedPath: String,
        ): ByteArray? =
            withContext(Dispatchers.IO) {
                withWnSession { session ->
                    withContext(Dispatchers.IO) { session.downloadCloudFile(appId, prefixedPath) }
                }
            }

        /** Single-round-trip launch-time conflict probe: a real conflict needs BOTH a change-number mismatch AND per-file content divergence (CN-only produced spurious dialogs). Fast-paths on CN match; on mismatch content-checks via [SteamAutoCloud.cloudContentDiffersFromLocal]. Returns the conflict flag + newest remote timestamp. */
        @JvmOverloads
        suspend fun fetchCloudConflictSnapshot(
            appId: Int,
            context: android.content.Context? = null,
        ): CloudConflictSnapshot? =
            withContext(Dispatchers.IO) {
                val localCN = instance?.changeNumbersDao?.getByAppId(appId)?.changeNumber

                // Cloud.GetAppFileChangelist — full remote file list; the parser scales proto unix-second timestamps to the millis the rest of the code expects.
                val deadlineMs = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < deadlineMs) {
                    val s = withWnSession { it }
                    if (s != null && _isLoggedInFlow.value) break
                    kotlinx.coroutines.delay(250L)
                }
                val wnJson =
                    withWnSession { session ->
                        withContext(Dispatchers.IO) { session.getCloudFileList(appId) }
                    } ?: return@withContext null
                try {
                    val response = SteamAutoCloud.parseCloudFileChangeList(wnJson)
                    val cnMismatch = localCN == null || response.currentChangeNumber != localCN
                    val newest =
                        response.files
                            .filter { it.isPersisted }
                            .mapNotNull { it.timestamp.takeIf { ts -> ts > 0L } }
                            .maxOrNull()

                    // CN match → no divergence; skip the per-file hashing. Fast path.
                    if (!cnMismatch) {
                        return@withContext CloudConflictSnapshot(differs = false, newestRemoteTimestamp = newest)
                    }

                    // CN mismatch — real conflict only if file content diverges; conservative (differs=true) when no Context is available to resolve local paths.
                    val ctx = context ?: PluviaApp.instance
                    val contentDiffers =
                        if (ctx != null) {
                            val accountId =
                                userSteamId?.accountID?.toLong()
                                    ?: PrefManager.steamUserAccountId.takeIf { it != 0 }?.toLong()
                                    ?: 0L
                            val prefixToPath: (String) -> String = { prefix ->
                                com.winlator.cmod.feature.stores.steam.enums.PathType
                                    .from(prefix)
                                    .toAbsPath(ctx, appId, accountId)
                            }
                            val appInfo = getAppInfoOf(appId)
                            com.winlator.cmod.feature.steamcloudsync.SteamAutoCloud
                                .cloudContentDiffersFromLocal(response, prefixToPath, appInfo)
                        } else {
                            true
                        }
                    Timber.i(
                        "cloud conflict snapshot via wn-steam-client: app=$appId " +
                            "cnMismatch=$cnMismatch contentDiffers=$contentDiffers files=${response.files.size}",
                    )
                    CloudConflictSnapshot(differs = contentDiffers, newestRemoteTimestamp = newest)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse Steam cloud conflict snapshot for appId=$appId")
                    null
                }
            }

        suspend fun forceSyncUserFiles(
            appId: Int,
            prefixToPath: (String) -> String,
            preferredSave: SaveLocation = SaveLocation.None,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            overrideLocalChangeNumber: Long? = null,
        ): Deferred<PostSyncInfo> =
            parentScope.async {
                if (!tryAcquireSync(appId)) {
                    Timber.w("Cannot force sync when sync already in progress for appId=$appId")
                    return@async PostSyncInfo(SyncResult.InProgress)
                }

                try {
                    var syncResult = PostSyncInfo(SyncResult.UnknownFail)

                    val maxAttempts = 3
                    for (attempt in 1..maxAttempts) {
                        try {
                            val clientId = PrefManager.clientId
                            val steamInstance = instance
                            val appInfo = getAppInfoOf(appId)

                            if (steamInstance != null && appInfo != null) {
                                val postSyncInfo =
                                    SteamAutoCloud
                                        .syncUserFiles(
                                            appInfo = appInfo,
                                            clientId = clientId,
                                            steamInstance = steamInstance,
                                            preferredSave = preferredSave,
                                            parentScope = parentScope,
                                            prefixToPath = prefixToPath,
                                            overrideLocalChangeNumber = overrideLocalChangeNumber,
                                        ).await()

                                postSyncInfo?.let { info ->
                                    syncResult = info
                                    Timber.i("Force cloud sync completed for app $appId with result: ${info.syncResult}")
                                }
                            }
                            break
                        } catch (e: AsyncJobFailedException) {
                            if (attempt == maxAttempts) {
                                Timber.e(e, "Force cloud sync failed after $maxAttempts attempts for app $appId")
                            } else {
                                Timber.w("Force cloud sync attempt $attempt failed for app $appId, retrying")
                                delay(1000L * attempt)
                            }
                        }
                    }

                    return@async syncResult
                } finally {
                    releaseSync(appId)
                }
            }

        suspend fun generateAchievements(
            appId: Int,
            configDirectory: String,
        ) = runCatching {
            run {
                val bs = com.winlator.cmod.feature.stores.steam.wnsteam
                    .WnSteamBootstrap
                if (bs.currentAppId() == appId && bs.numAchievements() > 0) {
                    val list = bs.listAchievementsFull()
                    if (list.isNotEmpty()) {
                        cachedAchievements = list.map { a ->
                            com.winlator.cmod.feature.stores.steam.statsgen.Achievement(
                                name        = a.apiName,
                                displayName = a.displayName?.let { mapOf("english" to it) },
                                description = a.description?.let { mapOf("english" to it) },
                                hidden      = if (a.hidden) 1 else 0,
                                unlocked    = a.achieved,
                                unlockTimestamp = a.unlockTimeRtime32.takeIf { it > 0 },
                            )
                        }
                        cachedAchievementsAppId = appId
                        Timber.i("user-stats schema via libsteamclient.so: ${list.size} achievements (app $appId)")
                        return@runCatching
                    }
                }
                Timber.d("libsteamclient.so achievements not ready for app $appId — falling through to wn-session CM fetch")
            }

            warmAchievementSchemaFromCache(appId)

            val schemaArray: ByteArray = run {
                val wn = withWnSession { session ->
                    withContext(Dispatchers.IO) { session.getUserStatsSchema(appId) }
                }
                if (wn != null && wn.isNotEmpty()) {
                    Timber.i("user-stats schema via wn-steam-client: ${wn.size} bytes (app $appId)")
                    wn
                } else {
                    Timber.w("wn-steam-client user-stats schema unavailable for app $appId")
                    return@runCatching
                }
            }
            val generator = StatsAchievementsGenerator()
            val result = generator.generateStatsAchievements(schemaArray, configDirectory)
            val nameToBlockBit = result.nameToBlockBit
            cachedAchievements = mergeAchievementUnlockState(appId, result.achievements, nameToBlockBit)
            cachedAchievementsAppId = appId
            if (nameToBlockBit.isNotEmpty()) {
                val mappingJson = JSONObject()
                nameToBlockBit.forEach { (name, pair) ->
                    mappingJson.put(name, JSONArray(listOf(pair.first, pair.second)))
                }
                File(configDirectory, "achievement_name_to_block.json").writeText(mappingJson.toString(), Charsets.UTF_8)
            }

            pushAchievementSchemaToLibSteamClient(appId, result.achievements, result.stats,
                                                   result.nameToBlockBit)
        }.onFailure { e ->
            Timber.w(e, "Failed to generate achievements for appId=$appId")
        }

        fun pushAppInstalledDepotsToLibSteamClient(appId: Int) = runCatching {
            if (appId <= 0) return@runCatching
            val depots = resolvePreferredLaunchDepotIds(
                appId = appId,
                branch = resolveSelectedBetaName(appId).ifBlank { STEAM_DEFAULT_BRANCH },
            )
            if (depots.isEmpty()) {
                Timber.w(
                    "pushAppInstalledDepotsToLibSteamClient: no depots resolved for appId=$appId; " +
                        "leaving previous bridge state intact",
                )
                return@runCatching
            }
            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                .setAppInstalledDepots(appId, depots)
            Timber.i("Pushed ${depots.size} installed depot(s) to libsteamclient.so (app $appId)")
        }.onFailure { e ->
            Timber.w(e, "pushAppInstalledDepotsToLibSteamClient failed (appId=$appId)")
        }

        suspend fun pushAppDlcsToLibSteamClient(appId: Int) = runCatching {
            if (appId <= 0) return@runCatching
            val selectedBranch = resolveSelectedBetaName(appId).ifBlank { STEAM_DEFAULT_BRANCH }
            val localBuildId = resolveInstalledBuildId(appId, selectedBranch)
            if (localBuildId > 0) {
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setAppBuildId(appId, localBuildId)
                Timber.i("Pushed installed buildId=$localBuildId to libsteamclient.so (app $appId branch=$selectedBranch)")
            }
            val snapshotJson = withWnSession { s ->
                withContext(Dispatchers.IO) { s.getLibrarySnapshotJson() }
            } ?: return@runCatching
            val ownedApps = try {
                JSONObject(snapshotJson).optJSONArray("owned_apps") ?: return@runCatching
            } catch (_: Exception) { return@runCatching }

            val dlcIds = mutableListOf<Int>()
            val byId   = mutableMapOf<Int, String>()
            var parentBuildId = 0
            for (i in 0 until ownedApps.length()) {
                val obj = ownedApps.optJSONObject(i) ?: continue
                val id  = obj.optInt("id")
                byId[id] = obj.optString("name", "")
                if (id != appId) continue
                parentBuildId = obj.optInt("build_id", 0)
                val arr = obj.optJSONArray("dlc") ?: continue
                for (k in 0 until arr.length()) dlcIds.add(arr.optInt(k))
            }
            if (parentBuildId > 0 && localBuildId <= 0) {
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setAppBuildId(appId, parentBuildId)
                Timber.i("Pushed snapshot buildId=$parentBuildId to libsteamclient.so (app $appId)")
            } else if (parentBuildId > 0 && parentBuildId != localBuildId) {
                Timber.i(
                    "Keeping installed buildId=$localBuildId for app $appId; the library snapshot " +
                        "reports the latest public build $parentBuildId, which is not what is on disk",
                )
            }
            if (dlcIds.isEmpty()) {
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setAppDlcs(appId, IntArray(0), emptyArray(), BooleanArray(0))
                Timber.d("Pushed empty DLC list to libsteamclient.so (app $appId)")
                return@runCatching
            }
            val ids   = IntArray(dlcIds.size)  { dlcIds[it] }
            val names = Array(dlcIds.size) { byId[dlcIds[it]] ?: "" }
            val avail = BooleanArray(dlcIds.size) { true }
            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                .setAppDlcs(appId, ids, names, avail)
            Timber.i("Pushed ${dlcIds.size} DLC entries to libsteamclient.so (app $appId)")
        }.onFailure { e ->
            Timber.w(e, "pushAppDlcsToLibSteamClient failed (appId=$appId)")
        }

        fun pushAppWorkshopItemsToLibSteamClient(appId: Int) = runCatching {
            if (appId <= 0) return@runCatching
            val ctx = instance?.applicationContext ?: return@runCatching
            val ids = com.winlator.cmod.feature.stores.steam.workshop.WorkshopModsGenerator
                .installedItemIds(ctx, appId)
            if (ids.isEmpty()) {
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setAppWorkshopItems(appId, LongArray(0), emptyArray(), LongArray(0), LongArray(0))
                return@runCatching
            }
            val sorted = ids.toLongArray().also { it.sort() }
            val installDirs = Array(sorted.size) { i ->
                com.winlator.cmod.feature.stores.steam.workshop.WorkshopModsGenerator
                    .contentDir(ctx, appId, sorted[i]).absolutePath
            }
            val sizes = LongArray(sorted.size) { i ->
                runCatching {
                    val dir = com.winlator.cmod.feature.stores.steam.workshop.WorkshopModsGenerator
                        .contentDir(ctx, appId, sorted[i])
                    var total = 0L
                    dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
                    total
                }.getOrDefault(0L)
            }
            val timestamps = LongArray(sorted.size) { i ->
                runCatching {
                    com.winlator.cmod.feature.stores.steam.workshop.WorkshopModsGenerator
                        .contentDir(ctx, appId, sorted[i]).lastModified() / 1000L
                }.getOrDefault(0L)
            }
            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                .setAppWorkshopItems(appId, sorted, installDirs, sizes, timestamps)
            Timber.i("Pushed ${sorted.size} workshop item(s) to libsteamclient.so (app $appId)")
        }.onFailure { e ->
            Timber.w(e, "pushAppWorkshopItemsToLibSteamClient failed (appId=$appId)")
        }

        suspend fun pushInventoryItemDefsToLibSteamClient(appId: Int) = runCatching {
            if (appId <= 0) return@runCatching
            val ctx = instance?.applicationContext ?: return@runCatching
            val caPath = CaBundleExtractor.ensureBundle(ctx)
            val archive = withWnSession { session ->
                withContext(Dispatchers.IO) { session.getItemDefArchive(appId, caPath) }
            } ?: return@runCatching
            val trimmed = archive.trim()
            if (trimmed.isEmpty() || trimmed == "null") return@runCatching
            val arr = try {
                org.json.JSONArray(trimmed)
            } catch (_: org.json.JSONException) {
                try { org.json.JSONArray("[$trimmed]") } catch (_: Exception) { return@runCatching }
            }
            val defIds = mutableListOf<Int>()
            val counts = mutableListOf<Int>()
            val keys = mutableListOf<String>()
            val vals = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val defId = o.opt("itemdefid")?.toString()?.toIntOrNull() ?: continue
                if (defId <= 0) continue
                val names = o.keys()
                var n = 0
                while (names.hasNext()) {
                    val k = names.next()
                    if (k == "itemdefid") continue
                    val v = o.opt(k)?.toString().orEmpty()
                    keys.add(k)
                    vals.add(v)
                    n++
                }
                defIds.add(defId)
                counts.add(n)
            }
            if (defIds.isEmpty()) {
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setInventoryItemDefs(appId, IntArray(0), IntArray(0), emptyArray(), emptyArray())
                return@runCatching
            }
            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient.setInventoryItemDefs(
                appId,
                defIds.toIntArray(),
                counts.toIntArray(),
                keys.toTypedArray(),
                vals.toTypedArray(),
            )
            Timber.i("Pushed ${defIds.size} inventory item defs to libsteamclient.so (app $appId)")
        }.onFailure { e ->
            Timber.w(e, "pushInventoryItemDefsToLibSteamClient failed (appId=$appId)")
        }

        suspend fun pushCloudStateToLibSteamClient(appId: Int) = runCatching {
            if (appId <= 0) return@runCatching
            val response = fetchCloudFileList(appId) ?: run {
                Timber.d("pushCloudStateToLibSteamClient: no cloud list (app $appId, not logged on?)")
                return@runCatching
            }
            val live = response.files.filter { it.isPersisted }
            val names      = Array(live.size) { i -> live[i].filename }
            val sizes      = IntArray(live.size) { i -> live[i].rawFileSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
            val timestamps = LongArray(live.size) { i -> live[i].timestamp / 1000L }
            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                .setAppId(appId)
            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                .setCloudFiles(names, sizes, timestamps)
            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                .setCloudEnabledForApp(true)
            Timber.i("Pushed ${live.size} cloud file(s) to libsteamclient.so (app $appId, " +
                "changeNumber=${response.currentChangeNumber})")

            val quota = withWnSession { s ->
                withContext(Dispatchers.IO) { s.getCloudUserQuota() }
            }
            if (quota != null && quota.size == 2 && quota[0] >= 0 && quota[1] >= 0) {
                val total = quota[0]
                val used  = quota[1]
                val avail = (total - used).coerceAtLeast(0L)
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setCloudQuota(total, avail)
                Timber.i("Pushed cloud quota to libsteamclient.so: total=$total used=$used avail=$avail")
            } else {
                Timber.d("Cloud quota fetch returned nothing (not logged on or transport error)")
            }
        }.onFailure { e ->
            Timber.w(e, "pushCloudStateToLibSteamClient failed (appId=$appId)")
        }

        @JvmStatic
        fun prepareLibSteamClientForLaunchBlocking(appId: Int) {
            runBlocking { prepareLibSteamClientForLaunch(appId) }
        }
        suspend fun prepareLibSteamClientForLaunch(appId: Int) {
            if (appId <= 0) return
            startOverlayPollLoop()
            val selectedBranch = resolveSelectedBetaName(appId)
            val baseStatePrimed =
                runCatching { primeLibSteamClientLaunchState(appId, selectedBranch) }
                    .getOrElse { e ->
                        Timber.w(e, "prepareLibSteamClientForLaunch: base-state prime failed for app $appId")
                        false
                    }
            Timber.i(
                "prepareLibSteamClientForLaunch: app=$appId beta='${selectedBranch.ifEmpty { "public" }}' " +
                    "baseStatePrimed=$baseStatePrimed",
            )
            val deadlineMs = System.currentTimeMillis() + 15_000L
            while (System.currentTimeMillis() < deadlineMs) {
                val session = withWnSession { it }
                if (session != null && _isLoggedInFlow.value) break
                kotlinx.coroutines.delay(500L)
            }
            kotlinx.coroutines.coroutineScope {
                val ticketJob = async {
                    runCatching { refreshEncryptedAppTicketForLibSteamClient(appId) }
                        .getOrElse { e ->
                            Timber.w(e, "prepareLibSteamClientForLaunch: encrypted-app-ticket failed for app $appId")
                            false
                        }
                }
                val ownerJob = async {
                    runCatching { prefetchOwnershipTicketForLibSteamClient(appId) }
                        .getOrElse { e ->
                            Timber.w(e, "prepareLibSteamClientForLaunch: ownership-ticket failed for app $appId")
                            false
                        }
                }
                val cloudJob = async {
                    runCatching { pushCloudStateToLibSteamClient(appId); true }
                        .getOrElse { e ->
                            Timber.w(e, "prepareLibSteamClientForLaunch: cloud state push failed for app $appId")
                            false
                        }
                }
                val dlcJob = async {
                    runCatching { pushAppDlcsToLibSteamClient(appId); true }
                        .getOrElse { e ->
                            Timber.w(e, "prepareLibSteamClientForLaunch: DLC push failed for app $appId")
                            false
                        }
                }
                val depotsJob = async {
                    runCatching { pushAppInstalledDepotsToLibSteamClient(appId); true }
                        .getOrElse { e ->
                            Timber.w(e, "prepareLibSteamClientForLaunch: depots push failed for app $appId")
                            false
                        }
                }
                val workshopJob = async {
                    runCatching { pushAppWorkshopItemsToLibSteamClient(appId); true }
                        .getOrElse { e ->
                            Timber.w(e, "prepareLibSteamClientForLaunch: workshop push failed for app $appId")
                            false
                        }
                }
                val inventoryJob = async {
                    runCatching { pushInventoryItemDefsToLibSteamClient(appId); true }
                        .getOrElse { e ->
                            Timber.w(e, "prepareLibSteamClientForLaunch: inventory push failed for app $appId")
                            false
                        }
                }
                val ticketOk = ticketJob.await()
                val ownerOk = ownerJob.await()
                val cloudOk = cloudJob.await()
                val dlcOk = dlcJob.await()
                val depotsOk = depotsJob.await()
                val workshopOk = workshopJob.await()
                val inventoryOk = inventoryJob.await()
                Timber.i(
                    "prepareLibSteamClientForLaunch: app=$appId " +
                        "baseStatePrimed=$baseStatePrimed " +
                        "encrypted-app-ticket=$ticketOk ownership-ticket=$ownerOk " +
                        "cloud=$cloudOk dlc=$dlcOk depots=$depotsOk " +
                        "workshop=$workshopOk inventory=$inventoryOk",
                )
            }
        }

        @JvmStatic
        fun resolveSelectedBetaName(appId: Int): String {
            if (appId <= 0) return ""
            val shortcutBranch =
                instance?.let { svc ->
                    runCatching {
                        for (sc in ContainerManager(svc).loadShortcuts()) {
                            val scAppId = sc.getExtra("app_id").toIntOrNull() ?: continue
                            if (scAppId != appId) continue
                            val branch = sc.getExtra("selectedBranch").trim()
                            if (branch.isNotEmpty()) return@runCatching branch
                        }
                        ""
                    }.getOrElse { "" }
                }.orEmpty()
            if (shortcutBranch.isNotEmpty()) return shortcutBranch

            val installed = getInstalledBranch(appId)
            if (!installed.equals(STEAM_DEFAULT_BRANCH, ignoreCase = true)) return installed

            val selected = getSelectedBranch(appId)
            return if (selected.equals(STEAM_DEFAULT_BRANCH, ignoreCase = true)) "" else selected
        }

        suspend fun refreshEncryptedAppTicketForLibSteamClient(appId: Int): Boolean {
            if (appId <= 0) return false
            val instance = SteamService.instance ?: return false
            val bytes = runCatching { instance.getEncryptedAppTicket(appId) }
                .getOrElse { e ->
                    Timber.w(e, "encrypted-app-ticket: getEncryptedAppTicket threw for app $appId")
                    null
                }
            if (bytes == null || bytes.isEmpty()) {
                Timber.d("encrypted-app-ticket: unavailable for app $appId (cache miss + " +
                    "wn-session returned null/empty)")
                return false
            }
            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                .setEncryptedAppTicket(appId, bytes, eresult = 1)
            Timber.i("Pushed encrypted app ticket to libsteamclient.so: app=$appId bytes=${bytes.size}")
            return true
        }

        suspend fun prefetchOwnershipTicketForLibSteamClient(appId: Int): Boolean {
            if (appId <= 0) return false
            val dlcAppIds = getInstalledDlcDepotsOf(appId).orEmpty().toIntArray()
            val ok = withContext(Dispatchers.IO) {
                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                val s = withWnSession { it } ?: run {
                    deferred.complete(false); null
                }
                if (s != null) {
                    s.prepareApp(appId, dlcAppIds,
                        com.winlator.cmod.feature.stores.steam.wnsteam.WnPrepareAppCallback { result, err ->
                            if (!result) {
                                Timber.d("prepareApp(app=$appId) → not ok: ${err ?: "no error"}")
                            }
                            deferred.complete(result)
                        })
                }
                deferred.await()
            }
            if (ok) {
                Timber.i("Prefetched ownership ticket for app=$appId " +
                    "(+${dlcAppIds.size} DLC) — libsteamclient.so cache primed")
            }
            return ok
        }

        suspend fun refreshCloudQuotaForLibSteamClient() = runCatching {
            val quota = withWnSession { s ->
                withContext(Dispatchers.IO) { s.getCloudUserQuota() }
            } ?: return@runCatching
            if (quota.size != 2 || quota[0] < 0 || quota[1] < 0) return@runCatching
            val total = quota[0]
            val used  = quota[1]
            val avail = (total - used).coerceAtLeast(0L)
            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                .setCloudQuota(total, avail)
            Timber.i("Refreshed cloud quota: total=$total used=$used avail=$avail")
        }.onFailure { e ->
            Timber.w(e, "refreshCloudQuotaForLibSteamClient failed")
        }

        internal fun warmAchievementSchemaFromCache(appId: Int): Boolean {
            if (appId <= 0) return false
            val ctx = instance?.applicationContext ?: return false
            val file = File(ctx.filesDir, "wn_lsteam_schemas/$appId.json")
            if (!file.exists()) return false
            val root = try { JSONObject(file.readText(Charsets.UTF_8)) }
                       catch (_: Exception) { return false }
            if (root.optInt("v") != 1) return false
            val achievements = mutableListOf<com.winlator.cmod.feature.stores.steam.statsgen.Achievement>()
            val achArr = root.optJSONArray("achievements") ?: JSONArray()
            for (i in 0 until achArr.length()) {
                val o = achArr.optJSONObject(i) ?: continue
                val name = o.optString("name", "")
                if (name.isEmpty()) continue
                val displayName = o.optJSONObject("displayName")?.let { mo ->
                    val m = mutableMapOf<String, String>()
                    mo.keys().forEach { k -> m[k] = mo.optString(k, "") }
                    m.toMap()
                }
                val description = o.optJSONObject("description")?.let { mo ->
                    val m = mutableMapOf<String, String>()
                    mo.keys().forEach { k -> m[k] = mo.optString(k, "") }
                    m.toMap()
                }
                achievements.add(
                    com.winlator.cmod.feature.stores.steam.statsgen.Achievement(
                        name = name,
                        displayName = displayName,
                        description = description,
                        hidden = o.optInt("hidden", 0),
                        icon = o.optString("icon", "").takeIf { it.isNotEmpty() },
                        unlocked = if (o.has("unlocked")) o.optBoolean("unlocked") else null,
                        unlockTimestamp = if (o.has("unlockTimestamp")) o.optInt("unlockTimestamp") else null,
                    ),
                )
            }
            val stats = mutableListOf<com.winlator.cmod.feature.stores.steam.statsgen.Stat>()
            val statArr = root.optJSONArray("stats") ?: JSONArray()
            for (i in 0 until statArr.length()) {
                val o = statArr.optJSONObject(i) ?: continue
                stats.add(
                    com.winlator.cmod.feature.stores.steam.statsgen.Stat(
                        id      = o.optString("id", ""),
                        name    = o.optString("name", ""),
                        type    = o.optString("type", "1"),
                        default = o.optString("default", "0"),
                    ),
                )
            }
            if (achievements.isEmpty() && stats.isEmpty()) return false
            val nameToBlockBit = mutableMapOf<String, Pair<Int, Int>>()
            val bitsArr = root.optJSONArray("nameToBlockBit")
            if (bitsArr != null) {
                for (i in 0 until bitsArr.length()) {
                    val o = bitsArr.optJSONObject(i) ?: continue
                    val name = o.optString("name", "")
                    if (name.isEmpty()) continue
                    nameToBlockBit[name] = Pair(
                        o.optInt("block", -1),
                        o.optInt("bit", 0),
                    )
                }
            }
            pushAchievementSchemaToLibSteamClient(appId, achievements, stats, nameToBlockBit)
            Timber.i("Warmed schema cache for app $appId: ${achievements.size} ach, " +
                "${stats.size} stats, ${nameToBlockBit.size} bit-mappings " +
                "(cached ts=${root.optLong("ts")})")
            return true
        }

        /** Fetch the app's Steam Inventory item definitions and write `steam_settings/items.json` + `default_items.json` into [configDirectory]. Best-effort — no-ops when there's no inventory, not logged on, or the fetch fails. */
        suspend fun generateInventoryItems(
            appId: Int,
            configDirectory: String,
        ) = runCatching {
            // The GetItemDefArchive HTTPS GET needs the same PEM trust bundle CaBundleExtractor provides for the CM session.
            val ctx = instance?.applicationContext ?: return@runCatching
            val caPath = CaBundleExtractor.ensureBundle(ctx)
            val archive =
                withWnSession { session ->
                    withContext(Dispatchers.IO) { session.getItemDefArchive(appId, caPath) }
                }
            if (archive == null) {
                Timber.i("Inventory item-def archive unavailable for app $appId")
                return@runCatching
            }
            val count = InventoryItemsGenerator.generate(archive, configDirectory)
            Timber.i("Inventory items generated for app $appId: $count definition(s)")
        }.onFailure { e ->
            Timber.w(e, "Failed to generate inventory items for appId=$appId")
        }

        /** Fetch the account's subscribed Steam Workshop items for [appId] as a JSON array string; brings up a CM session if needed. null = not logged on / transport failure, "[]" = no subscriptions. */
        suspend fun getSubscribedWorkshopItems(appId: Int): String? =
            withWnSession { session ->
                withContext(Dispatchers.IO) { session.getSubscribedWorkshopItems(appId) }
            }

        // Published-file-ids with an install in flight — guards two concurrent installs of the same item from wiping each other's content dir.
        private val workshopInstallsInFlight =
            java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

        /** Download and stage one subscribed Workshop item for [appId] into the staging area; the meta marker is written LAST so a partial download is never mistaken for installed. Returns true on success. BLOCKING — runs on Dispatchers.IO. */
        suspend fun installWorkshopItem(
            appId: Int,
            publishedFileId: Long,
            manifestId: Long,
            title: String,
            fileSizeBytes: Long,
            timeUpdated: Long,
            previewUrl: String,
        ): Boolean =
            withContext(Dispatchers.IO) {
                if (manifestId == 0L) {
                    Timber.w("Workshop item $publishedFileId has no content manifest — cannot install")
                    return@withContext false
                }
                if (!workshopInstallsInFlight.add(publishedFileId)) {
                    Timber.w("Workshop item $publishedFileId — install already in progress")
                    return@withContext false
                }
                try {
                    val ctx = instance?.applicationContext ?: return@withContext false
                    val caPath = CaBundleExtractor.ensureBundle(ctx)
                    val content = WorkshopModsGenerator.contentDir(ctx, appId, publishedFileId)
                    val meta = WorkshopModsGenerator.metaFile(ctx, appId, publishedFileId)
                    val preview = WorkshopModsGenerator.previewFile(ctx, appId, publishedFileId)
                    // A (re)install starts clean: drop any stale marker / content / preview.
                    meta.delete()
                    preview.delete()
                    content.deleteRecursively()
                    content.mkdirs()

                    val bytes =
                        withWnSession { session ->
                            session.downloadWorkshopItem(appId, manifestId, content.absolutePath, caPath)
                        } ?: -1L
                    if (bytes < 0L) {
                        Timber.w("Workshop content download failed for item $publishedFileId (app $appId)")
                        content.deleteRecursively()
                        return@withContext false
                    }
                    // Drop the .DepotDownloader resume folder so it isn't exposed as mod content.
                    File(content, ".DepotDownloader").deleteRecursively()

                    // Preview image — best-effort; a missing preview must not fail the install.
                    if (previewUrl.isNotBlank()) {
                        runCatching { downloadWorkshopPreview(previewUrl, preview) }
                            .onFailure { Timber.d(it, "Workshop preview download skipped for $publishedFileId") }
                    }

                    // Meta marker written LAST — its presence means "fully installed".
                    meta.writeText(
                        org.json.JSONObject()
                            .put("title", title)
                            .put("fileSize", fileSizeBytes)
                            .put("timeUpdated", timeUpdated)
                            .put("manifestId", manifestId)
                            .toString(),
                        Charsets.UTF_8,
                    )
                    Timber.i("Workshop item $publishedFileId installed for app $appId ($bytes bytes)")
                    true
                } finally {
                    workshopInstallsInFlight.remove(publishedFileId)
                }
            }

        fun getGseSaveDirs(appId: Int): List<File> {
            val context = instance?.applicationContext ?: return emptyList()
            val imageFs = ImageFs.find(context)
            val dirs = mutableListOf<File>()
            dirs.add(
                File(
                    imageFs.rootDir,
                    "${ImageFs.WINEPREFIX}/drive_c/users/xuser/AppData/Roaming/GSE Saves/$appId",
                ),
            )
            val accountId =
                userSteamId?.accountID?.toInt()
                    ?: PrefManager.steamUserAccountId.takeIf { it != 0 }
            if (accountId != null) {
                dirs.add(
                    File(
                        imageFs.rootDir,
                        "${ImageFs.WINEPREFIX}/drive_c/Program Files (x86)/Steam/userdata/$accountId/$appId",
                    ),
                )
            }
            return dirs
        }

        suspend fun syncAchievementsFromGoldberg(appId: Int) {
            val context = instance?.applicationContext ?: return
            val gseSaveDirs = getGseSaveDirs(appId).filter { it.isDirectory }
            if (gseSaveDirs.isEmpty()) {
                Timber.d("No GSE save directory found for appId=$appId")
                return
            }

            val unlockedNames = mutableSetOf<String>()
            var gseStatsDir: File? = null

            for (gseSaveDir in gseSaveDirs) {
                val goldbergAchFile = File(gseSaveDir, "achievements.json")
                if (goldbergAchFile.exists()) {
                    try {
                        val json = JSONObject(goldbergAchFile.readText(Charsets.UTF_8))
                        for (name in json.keys()) {
                            val entry = json.optJSONObject(name) ?: continue
                            if (entry.optBoolean("earned", false)) {
                                unlockedNames.add(name)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse Goldberg achievements.json in ${gseSaveDir.absolutePath} for appId=$appId")
                    }
                }

                val statsDir = File(gseSaveDir, "stats")
                if (gseStatsDir == null && statsDir.isDirectory && (statsDir.listFiles()?.isNotEmpty() == true)) {
                    gseStatsDir = statsDir
                }
            }

            val hasStats = gseStatsDir != null

            if (unlockedNames.isEmpty() && !hasStats) {
                Timber.d("No earned achievements or stats found in Goldberg output for appId=$appId")
                return
            }

            val configDirectory = findSteamSettingsDir(context, appId)
            if (configDirectory == null) {
                Timber.w("Could not find steam_settings directory for appId=$appId")
                return
            }

            val result = storeAchievementUnlocks(appId, configDirectory, unlockedNames, gseStatsDir ?: gseSaveDirs.first().resolve("stats"))
            result.onFailure { e ->
                Timber.e(e, "Failed to sync achievements and stats to Steam for appId=$appId")
            }
        }

        suspend fun storeAchievementUnlocks(
            appId: Int,
            configDirectory: String,
            unlockedNames: Set<String>,
            gseStatsDir: File,
        ): Result<Unit> =
            runCatching {
                val mySteamId = userSteamId
                    ?: throw IllegalStateException("storeAchievementUnlocks: no SteamID")

                // Fetch the app's user-stats (schema + crc + achievement blocks).
                val statsJson = withWnSession { session -> session.getUserStatsFull(appId) }
                    ?: throw IllegalStateException("getUserStats failed: no response")
                val statsObj = JSONObject(statsJson)
                val eresult = statsObj.optInt("eresult", 2)
                if (eresult != EResult.OK.code()) {
                    throw IllegalStateException("getUserStats failed: eresult=$eresult")
                }
                val crcStats = statsObj.optInt("crcStats")
                val schemaBytes = hexToBytes(statsObj.optString("schema"))
                val achievementBlocks = statsObj.optJSONArray("achievementBlocks")

                val allStats = mutableMapOf<Int, Int>()

                val mappingFile = File(configDirectory, "achievement_name_to_block.json")
                if (!mappingFile.exists() && unlockedNames.isNotEmpty()) {
                    generateAchievements(appId, configDirectory)
                }

                if (mappingFile.exists() && unlockedNames.isNotEmpty()) {
                    val mappingJson = JSONObject(mappingFile.readText(Charsets.UTF_8))
                    val nameToBlockBit = mutableMapOf<String, Pair<Int, Int>>()
                    for (key in mappingJson.keys()) {
                        val arr = mappingJson.optJSONArray(key) ?: continue
                        if (arr.length() >= 2) {
                            nameToBlockBit[key] = Pair(arr.getInt(0), arr.getInt(1))
                        }
                    }

                    for (i in 0 until (achievementBlocks?.length() ?: 0)) {
                        val block = achievementBlocks!!.getJSONObject(i)
                        val blockId = block.optInt("achievementId")
                        val unlockTimes = block.optJSONArray("unlockTimes")
                        var bitmask = 0
                        for (j in 0 until (unlockTimes?.length() ?: 0)) {
                            // unlock_time is a uint32 (can exceed Int range) — read as Long; non-zero means the bit is unlocked.
                            if (unlockTimes!!.getLong(j) != 0L) bitmask = bitmask or (1 shl j)
                        }
                        allStats[blockId] = bitmask
                    }

                    for (name in unlockedNames) {
                        val mapped = nameToBlockBit[name] ?: continue
                        val current = allStats.getOrDefault(mapped.first, 0)
                        allStats[mapped.first] = current or (1 shl mapped.second)
                    }
                }

                if (gseStatsDir.isDirectory) {
                    val statNameToId = mutableMapOf<String, Int>()
                    try {
                        val parsedSchema = VdfParser().binaryLoads(schemaBytes)
                        for ((_, appData) in parsedSchema) {
                            if (appData !is Map<*, *>) continue
                            val statInfo = (appData as Map<String, Any>)["stats"] as? Map<String, Any> ?: continue
                            for ((statKey, statData) in statInfo) {
                                if (statData !is Map<*, *>) continue
                                val stat = statData as Map<String, Any>
                                val statType = stat["type"]?.toString() ?: continue
                                if (statType == StatType.STAT_TYPE_BITS || statType == StatType.ACHIEVEMENTS) continue
                                val name = stat["name"]?.toString()?.lowercase() ?: continue
                                val id = statKey.toIntOrNull() ?: continue
                                statNameToId[name] = id
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse schema for stat name mapping, appId=$appId")
                    }

                    if (statNameToId.isNotEmpty()) {
                        for (statFile in gseStatsDir.listFiles() ?: emptyArray()) {
                            if (!statFile.isFile) continue
                            val statId = statNameToId[statFile.name.lowercase()] ?: continue
                            val bytes = statFile.readBytes()
                            if (bytes.size >= 4) {
                                val value = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
                                allStats[statId] = value
                                Timber.d("Read GSE stat: ${statFile.name} -> statId=$statId, value=$value")
                            }
                        }
                    }
                }

                if (allStats.isEmpty()) {
                    Timber.d("No stats or achievements to store for appId=$appId")
                    return@runCatching
                }

                Timber.d("storeUserStats: appId=$appId, crcStats=$crcStats, stats=$allStats")
                sendStoreUserStats(appId, allStats, mySteamId.convertToUInt64(), crcStats)
            }

        data class CloudSyncOutcome(
            val success: Boolean,
            val message: String = "",
        )

        suspend fun closeApp(
            appId: Int,
            isOffline: Boolean,
            prefixToPath: (String) -> String,
            onProgress: ((message: String, progress: Float) -> Unit)? = null,
        ): CloudSyncOutcome =
            withContext(Dispatchers.IO) {
                async {
                    // In-Wine Steam owns cloud saves during a hand-off; syncing here too would race it as a second writer.
                    if (isBionicHandoffActive()) {
                        Timber.i("closeApp: Bionic hand-off active for app %d — deferring exit cloud sync to in-Wine Steam", appId)
                        return@async CloudSyncOutcome(true, "Steam Launcher handles cloud saves directly.")
                    }
                    if (isOffline || !isConnected) {
                        return@async CloudSyncOutcome(false, "Steam is offline.")
                    }

                    if (!tryAcquireSync(appId)) {
                        Timber.w("Cannot close app when sync already in progress for appId=$appId")
                        return@async CloudSyncOutcome(false, "Steam cloud sync is already in progress.")
                    }

                    try {
                        try {
                            syncAchievementsFromGoldberg(appId)
                        } catch (e: Exception) {
                            Timber.e(e, "Achievement sync failed for appId=$appId, continuing with cloud save sync")
                        }

                        val progressWrapper: (String, Float) -> Unit = { msg, prog ->
                            cloudSyncStatus.value = CloudSyncMessage(appId, true, msg, prog)
                            onProgress?.invoke(msg, prog)
                        }
                        val maxAttempts = 3
                        var lastErrorMessage = "Steam cloud save sync failed."
                        for (attempt in 1..maxAttempts) {
                            try {
                                val clientId = PrefManager.clientId
                                val steamInstance = instance
                                val appInfo = getAppInfoOf(appId)

                                if (steamInstance != null && appInfo != null) {
                                    progressWrapper("Checking Local Saves", 0f)
                                    // SaveLocation.None: unchanged content never uploads, and both-sides-changed surfaces as a Conflict instead of overwriting the newer cloud copy.
                                    val postSyncInfo =
                                        SteamAutoCloud
                                            .syncUserFiles(
                                                appInfo = appInfo,
                                                clientId = clientId,
                                                steamInstance = steamInstance,
                                                preferredSave = SaveLocation.None,
                                                parentScope = this@async,
                                                prefixToPath = prefixToPath,
                                                onProgress = progressWrapper,
                                            ).await()

                                    val syncResult = postSyncInfo?.syncResult ?: SyncResult.UnknownFail
                                    // Signal exit-sync-done via the C++ WN-Steam-Client.
                                    withWnSession { session ->
                                        withContext(Dispatchers.IO) {
                                            session.signalAppExitSyncDone(
                                                appId = appId,
                                                clientId = clientId,
                                                uploadsCompleted = postSyncInfo?.uploadsCompleted == true,
                                                uploadsRequired = postSyncInfo?.uploadsRequired == true,
                                            )
                                        }
                                    }

                                    if (syncResult == SyncResult.Success || syncResult == SyncResult.UpToDate) {
                                        return@async CloudSyncOutcome(true)
                                    }

                                    // Discriminate the failure message by SyncResult so callers (SteamExitCloudSync.isRetryable, the UI retry loop) can tell terminal failures (Conflict) from transient ones (UpdateFail/DownloadFail).
                                    lastErrorMessage =
                                        when (syncResult) {
                                            SyncResult.Conflict ->
                                                "Steam cloud save sync conflict — relaunch the game to resolve."
                                            SyncResult.PendingOperations ->
                                                "Steam cloud sync pending — another device may still be uploading."
                                            SyncResult.InProgress ->
                                                "Steam cloud sync already in progress."
                                            SyncResult.UpdateFail ->
                                                "Steam cloud save upload failed."
                                            SyncResult.DownloadFail ->
                                                "Steam cloud save download failed."
                                            else -> "Steam cloud save sync failed."
                                        }
                                } else {
                                    lastErrorMessage = "Steam cloud service is unavailable."
                                }
                            } catch (e: AsyncJobFailedException) {
                                // e.message is often an EResult enum name (e.g. "Pending", "RemoteFileConflict"); SteamExitCloudSync's retry classifier matches substrings like "conflict"/"pending", so those short-circuit the retry loop without a SyncResult plumb-through here.
                                lastErrorMessage = e.message ?: "Steam cloud save sync failed."
                                if (attempt == maxAttempts) {
                                    Timber.e(e, "Close app sync failed after $maxAttempts attempts for app $appId")
                                } else {
                                    Timber.w("Close app sync attempt $attempt failed for app $appId, retrying")
                                    delay(1000L * attempt)
                                }
                            }
                        }
                        return@async CloudSyncOutcome(false, lastErrorMessage)
                    } finally {
                        cloudSyncStatus.value = null
                        releaseSync(appId)
                    }
                }.await()
            }

        interface CloudSyncCallback {
            fun onProgress(
                message: String,
                progress: Float,
            )

            fun onComplete(
                success: Boolean,
                message: String,
            )
        }

        @JvmStatic
        fun beginLaunchAppBlocking(
            context: android.content.Context,
            appId: Int,
            ignorePendingOperations: Boolean = false,
            preferredSave: SaveLocation = SaveLocation.None,
            isOffline: Boolean = false,
            callback: CloudSyncCallback? = null,
        ): PostSyncInfo =
            runBlocking(Dispatchers.IO) {
                check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                    "beginLaunchAppBlocking must not be called on the main thread"
                }
                var completionSent = false
                val accountId =
                    userSteamId?.accountID?.toLong()
                        ?: PrefManager.steamUserSteamId64.takeIf { it != 0L }?.let { it and 0xFFFFFFFFL }
                        ?: PrefManager.steamUserAccountId.takeIf { it != 0 }?.toLong()
                        ?: 0L
                val prefixToPath: (String) -> String = { prefix ->
                    com.winlator.cmod.feature.stores.steam.enums.PathType
                        .from(prefix)
                        .toAbsPath(context, appId, accountId)
                }

                try {
                    beginLaunchApp(
                        appId = appId,
                        parentScope = this,
                        ignorePendingOperations = ignorePendingOperations,
                        preferredSave = preferredSave,
                        prefixToPath = prefixToPath,
                        isOffline = isOffline,
                        onProgress = { msg, prog -> callback?.onProgress(msg, prog) },
                    ).await()
                } catch (e: Exception) {
                    completionSent = true
                    callback?.onComplete(false, e.message ?: "Steam cloud sync failed.")
                    throw e
                } finally {
                    if (!completionSent) {
                        callback?.onComplete(true, "")
                    }
                }
            }

        /** Sync cloud saves for backup/restore without closing the app; [preferredAction] is "download" or "upload". Returns true on success. */
        suspend fun syncCloudSavesForBackup(
            context: android.content.Context,
            appId: Int,
            preferredAction: String,
        ): Boolean {
            return withContext(Dispatchers.IO) {
                try {
                    val accountId =
                        userSteamId?.accountID?.toLong()
                            ?: PrefManager.steamUserSteamId64.takeIf { it != 0L }?.let { it and 0xFFFFFFFFL }
                            ?: PrefManager.steamUserAccountId.takeIf { it != 0 }?.toLong()
                            ?: 0L
                    val prefixToPath: (String) -> String = { prefix ->
                        com.winlator.cmod.feature.stores.steam.enums.PathType
                            .from(prefix)
                            .toAbsPath(context, appId, accountId)
                    }
                    val steamInst = instance
                    val appInfo = getAppInfoOf(appId)
                    val clientId = PrefManager.clientId

                    if (steamInst == null || appInfo == null) {
                        return@withContext false
                    }

                    SteamAutoCloud
                        .syncUserFiles(
                            appInfo = appInfo,
                            clientId = clientId,
                            steamInstance = steamInst,
                            prefixToPath = prefixToPath,
                            onProgress = { _, _ -> },
                        ).await()
                    true
                } catch (e: Exception) {
                    timber.log.Timber
                        .tag("SteamService")
                        .e(e, "syncCloudSavesForBackup failed")
                    false
                }
            }
        }

        @JvmStatic
        fun syncCloudOnExit(
            context: android.content.Context,
            appId: Int,
            callback: CloudSyncCallback,
            containerHint: Container? = null,
        ) {
            runCatching {
                val target = containerHint
                    ?: com.winlator.cmod.feature.stores.steam.utils.ContainerUtils
                        .getUsableContainerOrNull(context, appId.toString())
                target?.let { com.winlator.cmod.runtime.container.ContainerManager(context).activateContainer(it) }
            }.onFailure { Timber.w(it, "syncCloudOnExit: container activation failed for app=%d", appId) }

            val accountId =
                userSteamId?.accountID?.toLong()
                    ?: PrefManager.steamUserSteamId64.takeIf { it != 0L }?.let { it and 0xFFFFFFFFL }
                    ?: PrefManager.steamUserAccountId.takeIf { it != 0 }?.toLong()
                    ?: 0L
            val prefixToPath: (String) -> String = { prefix ->
                com.winlator.cmod.feature.stores.steam.enums.PathType
                    .from(prefix)
                    .toAbsPath(context, appId, accountId)
            }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val outcome =
                        closeApp(
                            appId = appId,
                            isOffline = false,
                            prefixToPath = prefixToPath,
                            onProgress = { msg, prog -> callback.onProgress(msg, prog) },
                        )
                    notifyRunningProcesses()
                    withContext(Dispatchers.Main) {
                        callback.onComplete(outcome.success, outcome.message)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        callback.onComplete(false, e.message ?: "Steam cloud save sync failed.")
                    }
                }
            }
        }

        /** loginusers.vdf writer for the OAuth-style refresh-token flow. accessToken is optional (Steam ignores it if absent); personaName defaults to the AccountName. */
        internal fun getLoginUsersVdfOauth(
            steamId64: String,
            account: String,
            refreshToken: String,
            accessToken: String? = null,
            personaName: String = account,
        ): String {
            val epoch = System.currentTimeMillis() / 1_000

            val vdf =
                buildString {
                    appendLine("\"users\"")
                    appendLine("{")
                    appendLine("    \"$steamId64\"")
                    appendLine("    {")
                    appendLine("        \"AccountName\"          \"$account\"")
                    appendLine("        \"PersonaName\"          \"$personaName\"")
                    appendLine("        \"RememberPassword\"     \"1\"")
                    appendLine("        \"WantsOfflineMode\"     \"0\"")
                    appendLine("        \"SkipOfflineModeWarning\"     \"0\"")
                    appendLine("        \"AllowAutoLogin\"       \"1\"")
                    appendLine("        \"MostRecent\"           \"1\"")
                    appendLine("        \"Timestamp\"            \"$epoch\"")
                    appendLine("    }")
                    appendLine("}")
                }

            return vdf
        }

        suspend fun startLoginWithCredentials(
            username: String,
            password: String,
            rememberSession: Boolean,
            authenticator: WnAuthenticator,
        ) = withContext(Dispatchers.IO) {
            val svc = instance ?: run {
                PluviaApp.events.emit(
                    SteamEvent.LogonEnded(username, LoginResult.Failed,
                        "SteamService not initialized"),
                )
                return@withContext
            }

            Timber.i("Logging in via credentials (wn-steam-client).")
            svc._loginResult = LoginResult.InProgress
            PluviaApp.events.emit(SteamEvent.LogonStarted(username))

            teardownPriorWnSession()

            val session = bringUpWnSession(svc) ?: run {
                PluviaApp.events.emit(
                    SteamEvent.LogonEnded(username, LoginResult.Failed,
                        "Failed to connect to Steam CM"),
                )
                return@withContext
            }
            wnAuthSession = session
            var keepSessionAlive = false
            try {
                val result = suspendCancellableCoroutine<WnAuthResult> { cont ->
                    session.startLoginWithCredentials(
                        username = username.trim(),
                        password = password.trim(),
                        persistentSession = rememberSession,
                        authenticator = authenticator,
                        callback = WnAuthCallback { r ->
                            if (cont.isActive) cont.resume(r)
                        },
                    )
                    cont.invokeOnCancellation { session.cancelLogin() }
                }

                if (!result.success || result.refreshToken.isEmpty()) {
                    Timber.e("WnSteam auth failed: %s", result.errorMessage)
                    com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                        .reportLogonFailure(
                            eresult = result.errorCode.takeIf { it != 0 } ?: 2 /* Fail */,
                            stillRetrying = false,
                        )
                    recordLogonFailure(result.errorCode.takeIf { it != 0 } ?: 2)
                    PluviaApp.events.emit(
                        SteamEvent.LogonEnded(username, LoginResult.Failed,
                            if (result.errorMessage.isNotEmpty()) result.errorMessage
                            else "auth failed (eresult=${result.errorCode})"),
                    )
                    return@withContext
                }

                Timber.i("WnSteam auth OK for %s", result.accountName)

                // Persist the acquired tokens so a later cold start auto-logons.
                persistLoginTokens(
                    username = result.accountName,
                    accessToken = result.accessToken,
                    refreshToken = result.refreshToken,
                )

                // Promote the auth session to the long-lived logon session. DO NOT insert a suspension point (withContext/delay/suspendCancellable) across the next four lines: cancellation mid-promotion leaves wnSession set while keepSessionAlive is false, so finally would close the live session (use-after-free).
                installWnLogonObserver(session)
                wnSession = session
                wnAuthSession = null
                keepSessionAlive = true

                if (!session.logonWithRefreshToken(result.refreshToken, result.accountName, result.steamId)) {
                    Timber.w("WnSteam logon_with_refresh_token returned false (channel not Connected?)")
                }

                // Watchdog: if the CM logon never reaches LoggedOn, surface a failure so the login UI doesn't hang on the spinner.
                svc.scope.launch {
                    var waited = 0
                    while (waited < 35 && session.state() != 3) { delay(1000); waited++ }
                    if (session.state() != 3 && wnSession === session) {
                        Timber.w("WnSteam CM logon never reached LoggedOn")
                        com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                            .reportLogonFailure(eresult = 6, stillRetrying = true)
                        PluviaApp.events.emit(
                            SteamEvent.LogonEnded(result.accountName, LoginResult.Failed,
                                "Steam logon timed out"),
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Login failed")
                val message = when (e) {
                    is CancellationException -> "Unknown cancellation"
                    else -> e.message ?: e.javaClass.name
                }
                PluviaApp.events.emit(SteamEvent.LogonEnded(username, LoginResult.Failed, message))
            } finally {
                if (!keepSessionAlive) {
                    try { session.disconnect() } catch (_: Throwable) {}
                    try { session.close() } catch (_: Throwable) {}
                    if (wnAuthSession === session) wnAuthSession = null
                }
            }
        }

        /** Creates a fresh [WnSteamSession], connects, and waits for the encrypted channel to reach Connected (state 2). Caller owns the returned session (disconnect/close); null on failure. */
        /** Run [block] with a logged-on session: reuse the global [wnSession] if logged on (state 3), else bring up a temporary one, log on with the stored refresh token, run [block], and tear it down. Null if no logged-on session could be obtained. */
        internal suspend fun <T> withWnSession(
            block: suspend (WnSteamSession) -> T,
        ): T? {
            if (instance?.suspendedForBionic == true) {
                Timber.i("withWnSession: suspended for Bionic hand-off — no CM session")
                return null
            }
            wnSession?.takeIf { it.state() == 3 }?.let { return block(it) }
            val gateUntil = logonGateUntilMs
            if (gateUntil > 0L) {
                val now = System.currentTimeMillis()
                if (now < gateUntil) {
                    Timber.w(
                        "withWnSession: logon gated for ${(gateUntil - now) / 1000}s " +
                            "more (last EResult=$lastLogonFailureEresult)",
                    )
                    return null
                }
            }
            return wnSessionBringUpMutex.withLock {
                wnSession?.takeIf { it.state() == 3 }?.let { return@withLock block(it) }
                val svc = instance ?: return@withLock null
                val refreshTok = PrefManager.refreshToken
                if (refreshTok.isBlank()) {
                    Timber.w("withWnSession: no stored refresh token")
                    return@withLock null
                }
                run {
                    val gu = logonGateUntilMs
                    if (gu > 0L && System.currentTimeMillis() < gu) {
                        Timber.w(
                            "withWnSession (post-lock): logon gated, EResult=$lastLogonFailureEresult",
                        )
                        return@withLock null
                    }
                }
                val brought = bringUpWnSession(svc) ?: run {
                    Timber.w("withWnSession: could not connect a session")
                    return@withLock null
                }
                var promoted = false
                try {
                    if (!brought.logonWithRefreshToken(
                            refreshTok,
                            PrefManager.username,
                            PrefManager.steamUserSteamId64,
                        )
                    ) {
                        Timber.w("withWnSession: logon request failed")
                        recordLogonFailure(2)
                        return@withLock null
                    }
                    // 12s logon wait — enough for a slow CM round-trip but bounded so game-launch isn't blocked 30s on a session that won't recover.
                    var wait = 0
                    while (brought.state() != 3 && wait < 24) {
                        delay(500L)
                        wait++
                    }
                    if (brought.state() != 3) {
                        Timber.w("withWnSession: session never reached LoggedOn after 12s")
                        if (lastLogonFailureEresult == 0) recordLogonFailure(16)
                        return@withLock null
                    }
                    installWnLogonObserver(brought)
                    wnSession = brought
                    promoted = true
                    isConnected = true
                    _isLoggedInFlow.value = true
                    recordLogonSuccess()
                    if (!wnLoggedOnHandled) {
                        wnLoggedOnHandled = true
                        instance?.onWnLoggedOn(brought)
                    }
                    block(brought)
                } finally {
                    if (!promoted) {
                        runCatching { brought.disconnect() }
                        runCatching { brought.close() }
                    }
                }
            }
        }

        suspend fun startLoginWithQr() = withContext(Dispatchers.IO) {
            val svc = instance ?: run {
                PluviaApp.events.emit(
                    SteamEvent.QrAuthEnded(success = false,
                        message = "SteamService not initialized"),
                )
                return@withContext
            }

            Timber.i("Logging in via QR (wn-steam-client).")
            isWaitingForQRAuth = true

            teardownPriorWnSession()

            val session = bringUpWnSession(svc) ?: run {
                isWaitingForQRAuth = false
                PluviaApp.events.emit(
                    SteamEvent.QrAuthEnded(success = false,
                        message = "Failed to connect to Steam"),
                )
                return@withContext
            }
            wnAuthSession = session
            var keepSessionAlive = false
            try {
                var qrScannedEmitted = false
                Log.i("WnSteamQr", "startLoginWithQr: session ready")
                val result = suspendCancellableCoroutine<WnAuthResult> { cont ->
                    session.startLoginWithQr(
                        qrCallback = WnQrCallback { url ->
                            Log.i("WnSteamQr", "qrCallback: challenge url len=${url.length}")
                            PluviaApp.events.emit(SteamEvent.QrChallengeReceived(url))
                        },
                        resultCallback = WnAuthCallback { r ->
                            Log.i(
                                "WnSteamQr",
                                "resultCallback: success=${r.success} errorCode=${r.errorCode} " +
                                    "error='${r.errorMessage}' refreshLen=${r.refreshToken.length} " +
                                    "account='${r.accountName}' remote=${r.hadRemoteInteraction} " +
                                    "agreementLen=${r.agreementSessionUrl.length}",
                            )
                            if (!qrScannedEmitted && r.hadRemoteInteraction) {
                                qrScannedEmitted = true
                                Log.i("WnSteamQr", "resultCallback: emitting QrCodeScanned")
                                PluviaApp.events.emit(SteamEvent.QrCodeScanned)
                            }
                            val isQrApprovalUpdate =
                                !r.success &&
                                    r.hadRemoteInteraction &&
                                    r.refreshToken.isEmpty() &&
                                    r.errorMessage.isEmpty()
                            if (isQrApprovalUpdate) {
                                Log.i("WnSteamQr", "resultCallback: intermediate approval update")
                                return@WnAuthCallback
                            }
                            Log.i("WnSteamQr", "resultCallback: resuming QR coroutine")
                            if (cont.isActive) cont.resume(r)
                        },
                    )
                    cont.invokeOnCancellation { session.cancelLogin() }
                }

                isWaitingForQRAuth = false
                Log.i(
                    "WnSteamQr",
                    "QR coroutine completed success=${result.success} refreshLen=${result.refreshToken.length} " +
                        "account='${result.accountName}' steamId=${result.steamId}",
                )
                PluviaApp.events.emit(SteamEvent.QrAuthEnded(result.success))

                if (!result.success || result.refreshToken.isEmpty()) {
                    Timber.e("WnSteam QR auth failed: %s", result.errorMessage)
                    return@withContext
                }

                // Persist the acquired tokens so a later cold start auto-logons.
                persistLoginTokens(
                    username = result.accountName,
                    accessToken = result.accessToken,
                    refreshToken = result.refreshToken,
                )

                // Promote the QR session to the long-lived logon session. DO NOT insert a suspension point across these four lines — see the note in startLoginWithCredentials.
                installWnLogonObserver(session)
                wnSession = session
                wnAuthSession = null
                keepSessionAlive = true

                if (!session.logonWithRefreshToken(result.refreshToken, result.accountName, result.steamId)) {
                    Timber.w("WnSteam QR logon_with_refresh_token returned false")
                    Log.w("WnSteamQr", "logonWithRefreshToken returned false")
                } else {
                    Log.i("WnSteamQr", "logonWithRefreshToken queued")
                }

                // Watchdog: surface a failure if the CM logon hangs.
                svc.scope.launch {
                    var waited = 0
                    while (waited < 35 && session.state() != 3) { delay(1000); waited++ }
                    if (session.state() != 3 && wnSession === session) {
                        Timber.w("WnSteam QR CM logon never reached LoggedOn")
                        PluviaApp.events.emit(
                            SteamEvent.LogonEnded(result.accountName, LoginResult.Failed,
                                "Steam logon timed out"),
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "QR failed")
                isWaitingForQRAuth = false
                val message = when (e) {
                    is CancellationException -> "QR Session timed out"
                    else -> e.message ?: e.javaClass.name
                }
                PluviaApp.events.emit(SteamEvent.QrAuthEnded(success = false, message = message))
            } finally {
                if (!keepSessionAlive) {
                    try { session.disconnect() } catch (_: Throwable) {}
                    try { session.close() } catch (_: Throwable) {}
                    if (wnAuthSession === session) wnAuthSession = null
                }
            }
        }

        fun stopLoginWithQr() {
            Timber.i("Stopping QR polling")
            isWaitingForQRAuth = false
            wnAuthSession?.let {
                try { it.cancelLogin() } catch (_: Throwable) {}
            }
        }

        fun start(context: Context) {
            try {
                val intent = Intent(context, SteamService::class.java)
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start SteamService")
            }
        }

        /** App-lifecycle hooks from PluviaApp (last activity stops → onAppBackgrounded, first starts → onAppForegrounded) — let the Steam session sleep while the app is minimized and idle. See [handleAppBackgrounded]. */
        fun onAppForegrounded() {
            val service = instance
            if (service == null) {
                // Only activity creation starts the service, so without this a stopped session stays dead for the whole process.
                if (PrefManager.refreshToken.isNotBlank()) {
                    runCatching { PluviaApp.instance.applicationContext?.let { start(it) } }
                }
                return
            }
            service.handleAppForegrounded()
        }

        fun onAppBackgrounded() {
            instance?.handleAppBackgrounded()
        }

        @JvmStatic
        fun ensureHealthySession() {
            instance?.ensureHealthySessionImpl()
        }

        fun stop() {
            instance?.let { steamInstance ->
                if (!isStopping) {
                    isStopping = true
                    runCatching {
                        steamInstance.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    }.onFailure { Timber.w(it, "Failed to remove SteamService foreground state during shutdown") }
                    runCatching {
                        steamInstance.notificationHelper.cancel()
                        steamInstance.notificationHelper.cancelBackgroundRunning()
                    }.onFailure { Timber.w(it, "Failed to cancel SteamService notification during shutdown") }
                    steamInstance.stopSelf()
                }
                steamInstance.scope.launch {
                    steamInstance.stop()
                }
            }
        }

        fun logOut() {
            // Capture username before clearing anything
            val username = PrefManager.username

            // ── Atomic state flip ──
            isLoggingOut = true
            _isLoggedInFlow.value = false
            PrefManager.clearAuthTokens()
            instance?.let { svc ->
                svc.scope.launch(Dispatchers.IO) {
                    runCatching { svc.encryptedAppTicketDao.deleteAll() }
                        .onFailure { Timber.w(it, "Failed to clear encrypted-app-ticket cache on logout") }
                }
            }
            runCatching {
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setAppId(0)
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setLoggedOn(false)
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setSteamId(0L)
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setPersonaName("")
            }

            wnLibraryMirrorJob?.cancel()
            wnLibraryMirrorJob = null
            wnLibrary?.stopObserving()
            wnLibrary = null

            // Tear down the logon session after observers are quiesced so delayed library refreshes can't touch a closing native handle.
            wnSession?.let { s ->
                try { s.disconnect() } catch (_: Throwable) {}
                try { s.close()      } catch (_: Throwable) {}
            }
            wnSession = null

            try {
                com.winlator.cmod.feature.stores.steam.wnsteam
                    .WnSteamBootstrap.stop()
            } catch (_: Throwable) {}
            instance?.bionicHandoffReleaseImpl()

            // Cancel background jobs immediately
            instance?.picsGetProductInfoJob?.cancel()
            instance?.picsChangesCheckerJob?.cancel()
            instance?.friendCheckerJob?.cancel()
            instance?.messagePollerJob?.cancel()

            // Emit event synchronously so the UI can react in the same frame
            PluviaApp.events.emit(SteamEvent.LoggedOut(username))

            // Session already disconnected above; just clear the local database (best-effort).
            instance?.let { svc ->
                svc.scope.launch(Dispatchers.Default) {
                    try {
                        clearDatabase()
                    } catch (e: Exception) {
                        Timber.e(e, "Error during async logOff")
                    }
                }
            }
            stopOverlayPollLoop()
            // No logged-off callback stops the service any more — do it here.
            stop()
        }

        fun requestSync() {
            instance?.let { service ->
                service.scope.launch {
                    refreshOwnedGamesFromServer()
                }
            }
        }

        @Volatile private var overlayPollJob: kotlinx.coroutines.Job? = null

        fun startOverlayPollLoop() {
            if (overlayPollJob?.isActive == true) return
            val svc = instance ?: return
            overlayPollJob = svc.scope.launch(Dispatchers.IO) {
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    runCatching {
                        val req = com.winlator.cmod.feature.stores.steam.wnsteam
                            .WnLibSteamClient.pollOverlayRequest() ?: return@runCatching
                        dispatchOverlayRequest(req)
                    }.onFailure { e ->
                        Timber.w(e, "overlayPollJob: dispatch failed")
                    }
                    kotlinx.coroutines.delay(250)
                }
            }
        }

        fun stopOverlayPollLoop() {
            overlayPollJob?.cancel()
            overlayPollJob = null
        }

        fun clearDatabase() {
            with(instance!!) {
                scope.launch {
                    db.withTransaction {
                        // Don't delete apps / change numbers / file lists here — preserve installed games + shortcuts; cloud-sync caches are cleared separately.

                        licenseDao.deleteAll()
                        encryptedAppTicketDao.deleteAll()
                        downloadingAppInfoDao.deleteAll()
                        db.steamAppDao().deleteAll()
                    }
                }
            }
        }

        suspend fun getOwnedGames(friendID: Long): List<OwnedGames> =
            withContext(Dispatchers.IO) {
                instance?._unifiedFriends!!.getOwnedGames(friendID)
            }

        // True if any download or cloud sync is in progress.
        fun hasActiveOperations(): Boolean {
            val anySyncInProgress = syncInProgressApps.values.any { it.get() }
            return anySyncInProgress || downloadJobs.values.any { it.getProgress() < 1f }
        }

        // Should service auto-stop when idle (backgrounded)?
        var autoStopWhenIdle: Boolean = false

        suspend fun isUpdatePending(
            appId: Int,
            branch: String = getSelectedBranch(appId),
        ): Boolean = checkForAppUpdate(appId, branch).hasUpdate

        suspend fun checkForAppUpdate(
            appId: Int,
            branch: String = getSelectedBranch(appId),
        ): SteamUpdateInfo =
            withContext(Dispatchers.IO) {
                fun SteamUpdateInfo.logged(): SteamUpdateInfo {
                    Timber.i(
                        "Steam update check result: appId=$appId branch=$branch " +
                            "hasUpdate=$hasUpdate downloadSize=$downloadSize depotIds=$depotIds message=$message",
                    )
                    return this
                }

                Timber.i("Steam update check started: appId=$appId branch=$branch")
                if (!isConnected || !isLoggedIn) {
                    return@withContext SteamUpdateInfo(message = "Steam is not connected").logged()
                }
                if (!isAppInstalled(appId)) {
                    return@withContext SteamUpdateInfo(message = "Game is not installed").logged()
                }

                val remoteSteamApp = fetchLatestSteamAppInfo(appId)
                    ?: return@withContext SteamUpdateInfo(message = "Could not fetch Steam metadata").logged()
                persistLatestSteamAppInfo(appId, remoteSteamApp)

                val appDirPath = getAppDirPath(appId)
                val selectedDepots =
                    getSelectedDownloadDepots(
                        appId = appId,
                        userSelectedDlcAppIds = resolveInstalledDlcIdsForUpdateOrVerify(appId),
                        preferredLanguage = PrefManager.containerLanguage,
                        branch = branch,
                    )
                if (selectedDepots.isEmpty()) {
                    return@withContext SteamUpdateInfo(message = "No installed depots to update").logged()
                }

                repairSupersededInstall(appId, appDirPath, selectedDepots.keys)

                val installedManifestIds = readInstalledDepotManifestIds(appDirPath)
                val cachedManifestFiles: Set<String> =
                    File(appDirPath, ".DepotDownloader").list()?.toHashSet() ?: emptySet()
                // Resolve manifests once per depot; the filter below picks which need updating and the size calc reuses these.
                val depotManifests: Map<Int, Pair<DepotInfo, ManifestInfo>> =
                    selectedDepots.mapNotNull { (depotId, depot) ->
                        val manifest = resolveDepotManifestInfo(depot, branch) ?: return@mapNotNull null
                        depotId to (depot to manifest)
                    }.toMap()
                val updateDepots =
                    depotManifests.filter { (depotId, depotAndManifest) ->
                        val manifest = depotAndManifest.second
                        val installedManifestId = installedManifestIds[depotId]
                        if (installedManifestId != null) {
                            installedManifestId != manifest.gid
                        } else {
                            "${depotId}_${manifest.gid}.manifest" !in cachedManifestFiles
                        }
                    }

                if (updateDepots.isEmpty()) {
                    val installedBuildId = getInstalledBuildId(appId)
                    val unresolvedDepots = selectedDepots.keys - depotManifests.keys
                    val stale =
                        SteamBranchSelection.isInstallStale(
                            installedBuildId = installedBuildId,
                            branches = remoteSteamApp.branches,
                            branch = branch,
                        )
                    if (stale) {
                        Timber.i(
                            "Steam update check: appId=$appId branch=$branch found no changed depot " +
                                "manifests, but the recorded install build $installedBuildId is behind " +
                                "${SteamBranchSelection.buildIdForBranch(remoteSteamApp.branches, branch)} " +
                                "(unresolved depots=$unresolvedDepots) — reporting an update",
                        )
                        SteamUpdateInfo(
                            hasUpdate = true,
                            downloadSize =
                                depotManifests.values
                                    .sumOf { (_, manifest) -> manifestDownloadBytes(manifest) }
                                    .coerceAtLeast(0L),
                            depotIds = selectedDepots.keys.sorted(),
                        ).logged()
                    } else {
                        SteamUpdateInfo(hasUpdate = false).logged()
                    }
                } else {
                    SteamUpdateInfo(
                        hasUpdate = true,
                        downloadSize =
                            updateDepots.values
                                .sumOf { (_, manifest) -> manifestDownloadBytes(manifest) }
                                .coerceAtLeast(0L),
                        depotIds = updateDepots.keys.sorted(),
                    ).logged()
                }
            }

        /** Transitional bridge: converts a [KeyValue] tree into the nested Map [WnKeyValue] consumes. */

        internal val picsRefreshedAppsThisSession =
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())

        suspend fun checkDlcOwnershipViaPICSBatch(dlcAppIds: Set<Int>): Set<Int> {
            if (dlcAppIds.isEmpty()) return emptySet()

            try {
                // Step 1: PICS access tokens — a granted token ⇒ candidate ownership.
                val tokJson =
                    withWnSession { session ->
                        withContext(Dispatchers.IO) {
                            session.getPicsAccessTokens(dlcAppIds.toList(), emptyList())
                        }
                    } ?: return emptySet()

                val tokenMap = HashMap<Int, Long>()
                JSONObject(tokJson).optJSONObject("appTokens")?.let { at ->
                    for (k in at.keys()) {
                        val id = k.toIntOrNull() ?: continue
                        if (id in dlcAppIds) {
                            tokenMap[id] = at.getString(k).toLongOrNull() ?: 0L
                        }
                    }
                }
                Timber.d("DLC ownership: ${tokenMap.size} candidate(s) from access tokens")
                if (tokenMap.isEmpty()) {
                    Timber.w("No owned DLCs found via access tokens")
                    return emptySet()
                }

                // Step 2: confirm via PICS product info — an app that returns a product-info entry is owned/accessible.
                val allOwnedAppIds = mutableSetOf<Int>()
                tokenMap.keys.toList().chunked(100).forEach { chunk ->
                    val infoJson =
                        withWnSession { session ->
                            withContext(Dispatchers.IO) {
                                session.getPicsAppProductInfo(chunk, chunk.map { tokenMap[it] ?: 0L })
                            }
                        } ?: return@forEach
                    val arr = JSONArray(infoJson)
                    for (i in 0 until arr.length()) {
                        allOwnedAppIds.add(arr.getJSONObject(i).optInt("appid"))
                    }
                }

                Timber.i(
                    "Final owned DLC appIds (wn): $allOwnedAppIds " +
                        "(${allOwnedAppIds.size} of ${dlcAppIds.size} checked)",
                )
                return allOwnedAppIds
            } catch (e: Exception) {
                Timber.e(e, "checkDlcOwnershipViaPICSBatch (wn) failed for ${dlcAppIds.size} appIds")
                return emptySet()
            }
        }
    }

    private val coordinatorDispatcher =
        object : DownloadCoordinator.Dispatcher {
            override fun startQueued(record: DownloadRecord) {
                val appId = record.storeGameId.toIntOrNull()
                if (appId == null) {
                    runBlocking {
                        DownloadCoordinator.notifyFinished(
                            DownloadRecord.STORE_STEAM,
                            record.storeGameId,
                            DownloadRecord.STATUS_FAILED,
                            "Invalid Steam app id '${record.storeGameId}'",
                        )
                    }
                    return
                }
                // No AppInfo yet (pre-login dispatch): requeue; the post-logon tick retries it.
                if (getAppInfoOf(appId) == null) {
                    Timber.w("startQueued: no AppInfo yet for appId=$appId — requeueing until Steam data is ready")
                    runBlocking {
                        DownloadCoordinator.requeue(DownloadRecord.STORE_STEAM, record.storeGameId)
                    }
                    return
                }
                // Drop any stale queued/paused DownloadInfo BEFORE downloadApp(), else it finds the inactive entry and calls removeDownloadJob() (firing the legacy checkQueue() + an extra notify) before building a fresh one.
                downloadJobs.remove(appId)
                // selectedDlcs carries authoritative DLC app IDs for installs; for updates it carries the changed depot IDs from checkForAppUpdate() so queued updates keep the narrowed scope.
                val persistedIds =
                    record.selectedDlcs
                        .split(',')
                        .mapNotNull { it.trim().toIntOrNull() }
                val started =
                    if (record.taskType == DownloadRecord.TASK_UPDATE) {
                        downloadAppForUpdate(appId, persistedIds)
                    } else if (record.taskType == DownloadRecord.TASK_VERIFY) {
                        downloadAppForVerify(appId)
                    } else {
                        downloadApp(appId, persistedIds)
                    }
                if (started == null) {
                    // Mark FAILED so the slot frees and Retry stays functional.
                    Timber.e("startQueued: downloadApp returned null for appId=$appId (task=${record.taskType}) — marking record FAILED")
                    runBlocking {
                        DownloadCoordinator.notifyFinished(
                            DownloadRecord.STORE_STEAM,
                            record.storeGameId,
                            DownloadRecord.STATUS_FAILED,
                            "Could not start download — retry after Steam finishes loading",
                        )
                    }
                } else if (started.getStatusFlow().value == DownloadPhase.COMPLETE) {
                    // No downloader runs, so release the slot here; keyed on COMPLETE because a queued job is also inactive.
                    Timber.i("startQueued: appId=$appId resolved as already complete — releasing coordinator slot")
                    runBlocking {
                        DownloadCoordinator.notifyFinished(
                            DownloadRecord.STORE_STEAM,
                            record.storeGameId,
                            DownloadRecord.STATUS_COMPLETE,
                        )
                    }
                }
            }

            override fun isTransferActive(record: DownloadRecord): Boolean {
                val appId = record.storeGameId.toIntOrNull() ?: return false
                return downloadJobs[appId]?.isActive() == true
            }

            override fun pauseRunning(record: DownloadRecord) {
                val appId = record.storeGameId.toIntOrNull() ?: return
                val info = downloadJobs[appId] ?: return
                val status = info.getStatusFlow().value
                if (status == DownloadPhase.COMPLETE || status == DownloadPhase.CANCELLED) return
                if (info.isActive()) {
                    info.isCancelling = false
                    info.updateStatus(DownloadPhase.PAUSED)
                    info.cancel("Paused by user")
                } else if (status == DownloadPhase.QUEUED) {
                    info.updateStatus(DownloadPhase.PAUSED)
                    info.setActive(false)
                    notifyDownloadStopped(appId)
                }
            }

            override fun cancelRunning(record: DownloadRecord) {
                val appId = record.storeGameId.toIntOrNull() ?: return
                val info = downloadJobs[appId]
                val statusAtCancel = info?.getStatusFlow()?.value
                if (info != null) {
                    info.isCancelling = true
                    info.cancel("Cancelled by user")
                }
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    val isUpdateTask = record.taskType == DownloadRecord.TASK_UPDATE
                    info?.awaitCompletion(timeoutMs = if (isUpdateTask) 10000L else 3000L)
                    val appDirPath = record.installPath.ifEmpty { getAppDirPath(appId) }
                    if (isUpdateTask) {
                        val updateNeverStarted =
                            statusAtCancel == DownloadPhase.QUEUED ||
                                (
                                    statusAtCancel == DownloadPhase.PAUSED &&
                                        MarkerUtils.hasMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER) &&
                                        !MarkerUtils.hasMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                                )
                        if (updateNeverStarted) {
                            MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                            MarkerUtils.addMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                        } else {
                            cleanupCancelledUpdate(appDirPath)
                        }
                        try {
                            instance?.downloadingAppInfoDao?.deleteApp(appId)
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to clear cancelled Steam update metadata for appId=$appId")
                        }
                        info?.updateStatus(DownloadPhase.CANCELLED)
                        removeDownloadJob(appId, forceRemove = true)
                        return@launch
                    }
                    val dirFile = java.io.File(appDirPath)
                    if (dirFile.exists() && dirFile.isDirectory) {
                        val deleteCheck =
                            StoreInstallPathSafety.checkInstallDirDelete(
                                instance?.applicationContext ?: DownloadService.appContext,
                                appDirPath,
                                protectedRoots = steamProtectedInstallRoots(),
                            )
                        if (deleteCheck.allowed) {
                            MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                            MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                            deleteRecursivelyWithRetries(dirFile)
                        } else {
                            Timber.e("Refusing to delete cancelled Steam download path '$appDirPath': ${deleteCheck.reason}")
                        }
                    }
                    info?.updateStatus(DownloadPhase.CANCELLED)
                    removeDownloadJob(appId, forceRemove = true)
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        instance = this

        _chatServiceEnabledFlow.value = PrefManager.chatServiceEnabled

        notificationHelper = NotificationHelper(applicationContext)
        val notification = notificationHelper.createForegroundNotification("Steam Service is running")
        startForeground(1, notification)

        com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
            .seedFromPrefManager(applicationContext)

        scope.launch(Dispatchers.IO) {
            try {
                val ownedIds = appDao.getAllAppIds().toIntArray()
                if (ownedIds.isNotEmpty()) {
                    com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                        .setOwnedApps(ownedIds)
                }
                val installed = ownedIds.filter { isAppInstalled(it) }.toIntArray()
                if (installed.isNotEmpty()) {
                    com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                        .setInstalledApps(installed)
                    installed.forEach { appId ->
                        val dir = runCatching { getAppDirPath(appId) }.getOrNull()
                        if (!dir.isNullOrEmpty()) {
                            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                                .setAppInstallDir(appId, dir)
                        }
                        val acct = runCatching {
                            com.winlator.cmod.feature.stores.steam.utils
                                .SteamUtils.getSteam3AccountId().toLong()
                        }.getOrNull() ?: 0L
                        if (acct > 0L) {
                            val remoteDir = runCatching {
                                com.winlator.cmod.feature.stores.steam.enums
                                    .PathType.SteamUserData.toAbsPath(
                                        this@SteamService, appId, acct)
                            }.getOrNull()
                            if (!remoteDir.isNullOrEmpty()) {
                                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                                    .setAppCloudRemoteDir(appId, remoteDir)
                            }
                        }
                    }
                }
                val nameIds   = mutableListOf<Int>()
                val nameStrs  = mutableListOf<String>()
                for (id in ownedIds) {
                    val nm = runCatching { appDao.findApp(id)?.name }.getOrNull().orEmpty()
                    if (nm.isNotEmpty()) {
                        nameIds.add(id)
                        nameStrs.add(nm)
                    }
                }
                if (nameIds.isNotEmpty()) {
                    com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                        .setAppNames(nameIds.toIntArray(), nameStrs.toTypedArray())
                }
                Timber.i("WnLibSteamClient seed: owned=${ownedIds.size} installed=${installed.size} names=${nameIds.size}")
            } catch (t: Throwable) {
                Timber.w(t, "WnLibSteamClient seed failed")
            }
        }

        // Connection/login flows are driven by the session observer; isLoggedInFlow is pre-seeded by initLoginStatus().
        _isConnectedFlow.value = false

        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)

        DownloadCoordinator.init(db)
        DownloadCoordinator.registerDispatcher(DownloadRecord.STORE_STEAM, coordinatorDispatcher)

        // Re-arm the background idle timer on any download state change — one that kept the session awake can finish/pause, so re-evaluate (and suspend once idle) without waiting on the running timer. Grace delay still applies.
        scope.launch {
            DownloadCoordinator.changes.collect {
                if (!appInForeground) scheduleBackgroundSuspendCheck()
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Notification intents
        when (intent?.action) {
            NotificationHelper.ACTION_EXIT -> {
                Timber.d("Exiting app via notification intent")
                AppTerminationHelper.stopManagedServices(applicationContext, "notification_exit", forceStopChat = true)
                runCatching {
                    getSystemService(android.app.ActivityManager::class.java)
                        ?.appTasks?.forEach { it.finishAndRemoveTask() }
                }
                android.os.Process.killProcess(android.os.Process.myPid())
                return START_NOT_STICKY
            }
        }

        if (!isRunning) {
            isRunning = true

            _unifiedFriends = SteamUnifiedFriends(this)
            // Family groups and friends go through the native Steam client.

            // Stored credentials bootstrap the native session and its observer drives the rest of the lifecycle; a fresh login (no token yet) arrives later via startLoginWith{Credentials,Qr}.
            if (PrefManager.refreshToken.isNotBlank()) {
                connectAndLogon()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }

        DownloadCoordinator.unregisterDispatcher(DownloadRecord.STORE_STEAM)

        // Persist progress for all active downloads — safety net for OS kills.
        downloadJobs.values.forEach { downloadInfo ->
            downloadInfo.persistProgressSnapshot(force = true)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel()
        notificationHelper.cancelBackgroundRunning()

        if (!isStopping) {
            scope.launch { stop() }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Timber.i("Task removed; stopping managed app services")
        AppTerminationHelper.stopManagedServices(applicationContext, "steam_task_removed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Applies the master chat switch to a live session: enable → restart the message poller, go online (stored persona), refresh friends; disable → stop the poller and go offline. No-op until logged on (onWnLoggedOn handles cold start). */
    private fun applyChatServiceState(enabled: Boolean) {
        if (!isLoggedIn) return
        if (enabled) {
            messagePollerJob?.cancel()
            messagePollerJob = continuousIncomingMessagePoller()
            scope.launch {
                val effectiveState =
                    (EPersonaState.from(PrefManager.personaState) ?: EPersonaState.Online).code()
                withWnSession { s -> s.setPersonaState(effectiveState) }
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setPersonaState(effectiveState)
            }
            scope.launch { runCatching { refreshFriends() } }
        } else {
            messagePollerJob?.cancel()
            messagePollerJob = null
            scope.launch {
                val offline = EPersonaState.Offline.code()
                withWnSession { s -> s.setPersonaState(offline) }
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setPersonaState(offline)
            }
        }
    }

    private suspend fun stop() {
        Timber.i("Stopping Steam service")
        isStopping = true
        connectJob?.cancel()
        reconnectJob?.cancel()
        stableConnectionJob?.cancel()
        refreshTokenWatchdogJob?.cancel()
        wnLibraryMirrorJob?.cancel()
        wnLibraryMirrorJob = null
        wnLibrary?.stopObserving()
        wnLibrary = null
        wnSession?.let { s ->
            runCatching { s.disconnect() }
            runCatching { s.close() }
        }
        wnSession = null
        clearValues()
    }

    // region [REGION] WN-Steam-Client lifecycle

    fun onWnDisconnected() {
        Timber.i("WN-Steam-Client channel disconnected")
        if (isStopping || isLoggingOut) return
        // A self-triggered disconnect to sleep the backgrounded app must NOT schedule a reconnect — that would defeat the suspend.
        if (suspendedForBackground || suspendedForBionic) {
            Timber.i("Channel disconnect was an intentional suspend " +
                "(background=$suspendedForBackground bionic=$suspendedForBionic) — not reconnecting")
            return
        }
        // This drop means the just-ended session was NOT stable — cancel the stable-connection timer so the retry budget keeps climbing and the backoff grows.
        stableConnectionJob?.cancel()
        refreshTokenWatchdogJob?.cancel()
        stableConnectionJob = null
        if (retryAttempt < MAX_RETRY_ATTEMPTS && PrefManager.refreshToken.isNotBlank()) {
            retryAttempt++
            val backoffMs = reconnectBackoffMs(retryAttempt)
            Timber.w("Reconnect scheduled in ${backoffMs}ms (retry $retryAttempt/$MAX_RETRY_ATTEMPTS)")
            notificationHelper.notify("Retrying")
            PluviaApp.events.emit(SteamEvent.RemotelyDisconnected)
            reconnectJob?.cancel()
            reconnectJob =
                scope.launch {
                    delay(backoffMs)
                    if (!isStopping && !isLoggingOut) connectAndLogon()
                }
        } else {
            PluviaApp.events.emit(SteamEvent.Disconnected)
            clearValues()
            stopSelf()
        }
    }

    /** Post-logon orchestration (onLoggedOn): runs once per logged-on [WnSteamSession] (guarded by wnLoggedOnHandled), fired from the [installWnLogonObserver] observer or the [withWnSession] promotion path. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun onWnLoggedOn(session: WnSteamSession) {
        Timber.i("Logged onto Steam (WN-Steam-Client)")

        // Do NOT reset retryAttempt here — a session that logs on then drops within STABLE_CONNECTION_MS isn't healthy; arm a timer that resets it only after the session stays up (onWnDisconnected cancels it).
        stableConnectionJob?.cancel()
        refreshTokenWatchdogJob?.cancel()
        stableConnectionJob =
            scope.launch {
                delay(STABLE_CONNECTION_MS)
                retryAttempt = 0
                Timber.d("Connection stable — reconnect retry budget reset")
            }
        startRefreshTokenWatchdog()
        isLoggingOut = false
        _isLoggedInFlow.value = true

        val steamId64 = session.steamId()
        if (steamId64 != 0L) {
            // SteamID.accountID == the low 32 bits of the SteamID64.
            val accountId = (steamId64 and 0xFFFFFFFFL).toInt()
            if (PrefManager.steamUserAccountId != accountId) {
                PrefManager.steamUserAccountId = accountId
                Timber.d("Saving logged in Steam accountID $accountId")
                clearCloudSyncCaches()
            }
            if (PrefManager.steamUserSteamId64 != steamId64) {
                PrefManager.steamUserSteamId64 = steamId64
                Timber.d("Saving logged in Steam ID64 $steamId64")
            }
        }

        com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
            .setSteamId(steamId64)
        com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
            .setLoggedOn(true)
        com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
            .setCloudEnabledForAccount(true)


        // Start downloads that were requeued while AppInfo wasn't loaded yet.
        DownloadCoordinator.blockingTick()

        // retrieve persona data of logged in user
        scope.launch { requestUserPersona() }

        // Populate the license tables from CMsgClientLicenseList.
        scope.launch { processLicenseList() }

        scope.launch { pushFriendsListToLibSteamClient() }

        scope.launch { refreshCloudQuotaForLibSteamClient() }

        // Request family share info if the logon response gave us a family id.
        val familyGroupId = session.familyGroupId()
        if (familyGroupId != 0L) {
            scope.launch {
                val json = withWnSession { s -> s.getFamilyGroup(familyGroupId) }
                if (json == null) {
                    Timber.w("An error occurred loading family group info.")
                    return@launch
                }
                try {
                    val obj = JSONObject(json)
                    val members = obj.optJSONArray("members")
                    Timber.i(
                        "Found family share: ${obj.optString("name")}, " +
                            "with ${members?.length() ?: 0} members.",
                    )
                    if (members != null) {
                        for (i in 0 until members.length()) {
                            val memberId64 = members.getLong(i)
                            familyGroupMembers.add((memberId64 and 0xFFFFFFFFL).toInt())
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "family group: parse failed")
                }
            }
        }

        picsChangesCheckerJob?.cancel()
        picsChangesCheckerJob = continuousPICSChangesChecker()
        picsGetProductInfoJob?.cancel()
        picsGetProductInfoJob = continuousPICSGetProductInfo()
        messagePollerJob?.cancel()
        messagePollerJob = if (PrefManager.chatServiceEnabled) continuousIncomingMessagePoller() else null

        // Repair legacy depots whose stored download>size was frozen by the change-number skip.
        healCorruptManifestDownloadSizes()

        // Tell steam our presence — online (stored persona) when chat is on, offline when the master switch is off — this lets friends update.
        scope.launch {
            val effectiveState =
                if (!PrefManager.chatServiceEnabled) {
                    EPersonaState.Offline.code()
                } else {
                    (EPersonaState.from(PrefManager.personaState) ?: EPersonaState.Online).code()
                }
            withWnSession { s -> s.setPersonaState(effectiveState) }
            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                .setPersonaState(effectiveState)
        }

        notificationHelper.notify("Connected")
        _loginResult = LoginResult.Success
        PluviaApp.events.emit(SteamEvent.LogonEnded(PrefManager.username, LoginResult.Success))

        scope.launch {
            try {
                val installed = withContext(Dispatchers.IO) {
                    appDao.getAllAppIds().filter { isAppInstalled(it) }
                }
                if (installed.isEmpty()) {
                    Timber.d("post-logon ticket pre-fetch: no installed apps")
                    return@launch
                }
                Timber.i("post-logon ticket pre-fetch: ${installed.size} installed app(s)")
                val sem = kotlinx.coroutines.sync.Semaphore(8)
                kotlinx.coroutines.coroutineScope {
                    installed.forEach { appId: Int ->
                        launch {
                            sem.withPermit {
                                prefetchOwnershipTicketForLibSteamClient(appId)
                            }
                        }
                    }
                }
                Timber.i("post-logon ticket pre-fetch: complete")
            } catch (t: Throwable) {
                Timber.w(t, "post-logon ticket pre-fetch failed")
            }
        }

    }
    // endregion

    // QR challenge-URL updates flow from WnSteamSession via WnQrCallback; see startLoginWithQr below.
    // endregion

    suspend fun refreshFriends() {
        val svc = instance ?: return
        val ids = withWnSession { s -> s.getFriendsList() } ?: LongArray(0)
        if (ids.isEmpty()) return
        com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient.setFriendsList(ids)
        val merged = LinkedHashMap<Long, SteamFriendEntry>()
        for (id in ids) merged[id] = SteamFriendEntry(steamId = id, name = "", state = EPersonaState.Offline)
        fun mergeJson(json: String?) {
            val arr = try { JSONArray(json ?: "[]") } catch (_: Exception) { JSONArray() }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sid = o.optLong("sid", 0L)
                if (sid == 0L) continue
                merged[sid] = SteamFriendEntry(
                    steamId = sid,
                    name = o.optString("name", ""),
                    state = EPersonaState.from(o.optInt("state", 0)) ?: EPersonaState.Offline,
                    gameAppId = o.optInt("app", 0),
                    gameName = o.optString("gameName", ""),
                    avatarHash = o.optString("avatarHash", ""),
                    connectString = o.optString("connect", ""),
                )
            }
        }
        runCatching {
            mergeJson(com.winlator.cmod.feature.stores.steam.utils.PrefManager.friendsSnapshotJson)
        }
        svc._friendsList.value = merged.values.toList()
        withWnSession { s -> s.requestFriendPersonas(ids, personaStateRequested = 0xffff) }
        var gotLive = false
        for (attempt in 0 until 20) {
            if (attempt > 0 && attempt % 5 == 0) {
                withWnSession { s -> s.requestFriendPersonas(ids, personaStateRequested = 0xffff) }
            }
            val json = withWnSession { s -> s.getFriendPersonas() }
            if (!json.isNullOrBlank() && json != "[]") {
                mergeJson(json)
                gotLive = true
                runCatching {
                    com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient.pushFriendPersonasJson(json, persistSnapshot = true)
                }
            }
            // Resolve game titles for in-game friends (Steam omits game_name for Steam apps).
            for ((id, entry) in merged.toList()) {
                if (entry.isOnline && entry.gameName.isBlank() && entry.gameAppId > 0) {
                    val name = resolveGameName(entry.gameAppId)
                    if (name.isNotBlank()) merged[id] = entry.copy(gameName = name)
                }
            }
            svc._friendsList.value = merged.values.toList()
            if (gotLive && merged.values.count { it.name.isNotBlank() } >= ids.size) break
            kotlinx.coroutines.delay(1000L)
        }
    }

    suspend fun syncFriendsPresence() {
        val svc = instance ?: return
        val current = svc._friendsList.value
        if (current.isEmpty()) return
        val json = withContext(Dispatchers.IO) { withWnSession { s -> s.getFriendPersonas() } } ?: return
        val arr = try { JSONArray(json) } catch (_: Exception) { return }
        if (arr.length() == 0) return
        val byId = LinkedHashMap<Long, SteamFriendEntry>(current.size)
        for (e in current) byId[e.steamId] = e
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sid = o.optLong("sid", 0L)
            if (sid == 0L || !byId.containsKey(sid)) continue
            byId[sid] = SteamFriendEntry(
                steamId = sid,
                name = o.optString("name", ""),
                state = EPersonaState.from(o.optInt("state", 0)) ?: EPersonaState.Offline,
                gameAppId = o.optInt("app", 0),
                gameName = o.optString("gameName", ""),
                avatarHash = o.optString("avatarHash", ""),
                connectString = o.optString("connect", ""),
            )
        }
        for ((id, entry) in byId.toList()) {
            if (entry.isOnline && entry.gameName.isBlank() && entry.gameAppId > 0) {
                val name = resolveGameName(entry.gameAppId)
                if (name.isNotBlank()) byId[id] = entry.copy(gameName = name)
            }
        }
        svc._friendsList.value = byId.values.toList()
    }

    private val gameNameCache = java.util.concurrent.ConcurrentHashMap<Int, String>()

    // appId -> display name: cached, local app DB first, then the public store API.
    suspend fun resolveGameName(appId: Int): String {
        if (appId <= 0) return ""
        gameNameCache[appId]?.let { return it }
        getAppInfoOf(appId)?.name?.takeIf { it.isNotBlank() }?.let {
            gameNameCache[appId] = it
            return it
        }
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                val conn = java.net.URL(
                    "https://store.steampowered.com/api/appdetails?appids=$appId&filters=basic",
                ).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(text).optJSONObject(appId.toString())
                if (o?.optBoolean("success") == true) o.optJSONObject("data")?.optString("name").orEmpty() else ""
            }.getOrDefault("")
        }
        if (fetched.isNotBlank()) gameNameCache[appId] = fetched
        return fetched
    }

    // Send a 1-to-1 text message to a friend. Returns true on success.
    suspend fun sendChatMessage(steamId: Long, text: String): Boolean {
        if (text.isBlank()) return false
        val resp = withContext(Dispatchers.IO) { withWnSession { s -> s.sendFriendMessage(steamId, text) } }
        return !resp.isNullOrBlank()
    }

    // Upload an image to Steam chat UGC and send it to a friend; returns the URL or null.
    suspend fun sendChatImage(steamId: Long, bytes: ByteArray, fileName: String): String? {
        if (bytes.isEmpty()) return null
        val refreshToken = com.winlator.cmod.feature.stores.steam.utils.PrefManager.refreshToken
        if (refreshToken.isBlank()) return null
        return withContext(Dispatchers.IO) {
            withWnSession { s -> s.sendChatImage(steamId, refreshToken, bytes, fileName) }
        }
    }

    // Load conversation history with a friend, ordered oldest-first.
    suspend fun loadChatHistory(steamId: Long, count: Int = 50): List<com.winlator.cmod.feature.stores.steam.data.SteamChatMessage> {
        val json = withContext(Dispatchers.IO) { withWnSession { s -> s.getRecentMessages(steamId, count) } } ?: "[]"
        val arr = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<com.winlator.cmod.feature.stores.steam.data.SteamChatMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                com.winlator.cmod.feature.stores.steam.data.SteamChatMessage(
                    fromSelf = o.optBoolean("fromSelf", false),
                    text = o.optString("message", ""),
                    timestamp = o.optInt("timestamp", 0),
                    ordinal = o.optInt("ordinal", 0),
                )
            )
        }
        out.sortWith(compareBy({ it.timestamp }, { it.ordinal }))
        return out
    }

    // Drain queued incoming messages, grouped by friend steamId.
    suspend fun drainIncomingMessages(): Map<Long, List<com.winlator.cmod.feature.stores.steam.data.SteamChatMessage>> {
        val json = withWnSession { s -> s.drainFriendMessages() } ?: "[]"
        val arr = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        if (arr.length() == 0) return emptyMap()
        val grouped = LinkedHashMap<Long, MutableList<com.winlator.cmod.feature.stores.steam.data.SteamChatMessage>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val fid = o.optLong("friendId", 0L)
            if (fid == 0L) continue
            grouped.getOrPut(fid) { ArrayList() }.add(
                com.winlator.cmod.feature.stores.steam.data.SteamChatMessage(
                    fromSelf = o.optBoolean("fromSelf", false),
                    text = o.optString("message", ""),
                    timestamp = o.optInt("timestamp", 0),
                    ordinal = o.optInt("ordinal", 0),
                )
            )
        }
        return grouped
    }

    fun setActiveConversation(steamId: Long) {
        if (steamId == 0L) return
        activeConversations.merge(steamId, 1) { a, b -> a + b }
        touchRecentChat(steamId)
        clearUnread(steamId)
        runCatching { notificationHelper.cancelChatNotification(steamId) }
    }

    fun sendChatImageAsync(friendId: Long, bytes: ByteArray, fileName: String) {
        if (friendId == 0L || bytes.isEmpty()) return
        scope.launch { sendChatImage(friendId, bytes, fileName) }
    }

    fun clearActiveConversation(steamId: Long) {
        if (steamId == 0L) return
        activeConversations.compute(steamId) { _, v -> if (v == null || v <= 1) null else v - 1 }
    }

    fun clearUnread(steamId: Long) {
        _unreadCounts.update { if (it.containsKey(steamId)) it - steamId else it }
    }

    /** Encrypted app ticket for an app (30-minute cache); serialized protobuf bytes, or null if unavailable. */
    suspend fun getEncryptedAppTicket(appId: Int): ByteArray? {
        return try {
            val cachedTicket = encryptedAppTicketDao.getByAppId(appId)
            val now = System.currentTimeMillis()
            val thirtyMinutes = 30 * 60 * 1000L

            if (cachedTicket != null && (now - cachedTicket.timestamp) < thirtyMinutes) {
                Timber.d("Using cached encrypted app ticket protobuf for app $appId")
                return cachedTicket.encryptedTicket
            }

            // Cold Client needs this ticket for Capcom DRM titles; tolerate a slow wn-session cold-start by waiting up to 15s.
            var wnTicket: ByteArray? = null
            val ticketWaitDeadlineMs = System.currentTimeMillis() + 15_000L
            while (wnTicket == null && System.currentTimeMillis() < ticketWaitDeadlineMs) {
                wnTicket = withWnSession { session ->
                    withContext(Dispatchers.IO) { session.requestEncryptedAppTicket(appId) }
                }
                if (wnTicket != null) break
                kotlinx.coroutines.delay(500L)
            }
            if (wnTicket == null) {
                Timber.w("encrypted app ticket: 15s wait elapsed without success for app $appId")
            }
            if (wnTicket != null && wnTicket.isNotEmpty()) {
                runCatching {
                    encryptedAppTicketDao.insert(
                        EncryptedAppTicket(
                            appId = appId,
                            result = EResult.OK.code(),
                            ticketVersionNo = 0,
                            crcEncryptedTicket = 0,
                            cbEncryptedUserData = 0,
                            cbEncryptedAppOwnershipTicket = 0,
                            encryptedTicket = wnTicket,
                            timestamp = now,
                        ),
                    )
                }.onFailure { Timber.w(it, "encrypted app ticket cache insert failed") }
                Timber.i("encrypted app ticket via wn-steam-client: ${wnTicket.size} bytes (app $appId)")
                return wnTicket
            }
            Timber.w("wn-steam-client encrypted app ticket unavailable for app $appId")
            null
        } catch (e: Exception) {
            Timber.e(e, "Error getting encrypted app ticket for app $appId")
            null
        }
    }

    /** Encrypted app ticket as a base64 string (30-minute cache); null if unavailable. */
    suspend fun getEncryptedAppTicketBase64(appId: Int): String? {
        val ticket = getEncryptedAppTicket(appId) ?: return null
        return Base64.encodeToString(ticket, Base64.NO_WRAP)
    }
}
