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

// Login token/session bring-up + update-check helpers, split out of SteamService.kt (behavior-identical).

/** Persist native-client auth credentials for cold-start auto-logon. */
internal fun SteamService.Companion.persistLoginTokens(
    username: String,
    accessToken: String?,
    refreshToken: String?,
    clientId: Long? = null,
) {
    isLoggingOut = false
    PrefManager.username = username
    if (accessToken != null) PrefManager.accessToken = accessToken
    if (refreshToken != null) PrefManager.refreshToken = refreshToken
    if (clientId != null) PrefManager.clientId = clientId
    val tokenForSid = refreshToken ?: accessToken
    var newSid: Long = 0L
    var accountSwitched: Boolean = false
    if (tokenForSid != null) {
        runCatching {
            val sub = JWT(tokenForSid).subject
            if (!sub.isNullOrBlank()) {
                val sid64 = sub.toLongOrNull()
                if (sid64 != null && sid64 != 0L) {
                    newSid = sid64
                    val prev = PrefManager.steamUserSteamId64
                    if (prev != sid64) {
                        accountSwitched = (prev != 0L)
                        PrefManager.steamUserSteamId64 = sid64
                        PrefManager.steamUserAccountId =
                            (sid64 and 0xFFFFFFFFL).toInt()
                        Timber.i("persistLoginTokens: cached steamId64=$sid64" +
                            if (accountSwitched) " (account switch from $prev)" else "")
                    }
                }
            }
        }.onFailure { e ->
            Timber.w(e, "persistLoginTokens: JWT decode failed")
        }
    }
    if (newSid != 0L) {
        runCatching {
            if (accountSwitched) {
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setPersonaName("")
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setFriendsList(LongArray(0))
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setCloudFiles(emptyArray(), IntArray(0), LongArray(0))
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setCloudEnabledForApp(false)
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setAppId(0)
                Timber.i("persistLoginTokens: cleared libsteamclient mirror on account switch")
                instance?.let { svc ->
                    svc.scope.launch(Dispatchers.IO) {
                        runCatching { svc.encryptedAppTicketDao.deleteAll() }
                            .onFailure {
                                Timber.w(it,
                                    "Failed to clear encrypted-app-ticket cache on account switch")
                            }
                        runCatching { svc.db.steamAppDao().deleteAll() }
                            .onFailure {
                                Timber.w(it,
                                    "Failed to clear steam_app catalog on account switch")
                            }
                        runCatching { svc.licenseDao.deleteAll() }
                            .onFailure {
                                Timber.w(it,
                                    "Failed to clear steam_license on account switch")
                            }
                    }
                }
            }
            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                .setSteamId(newSid)
            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                .setLoggedOn(true)
            com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                .setCloudEnabledForAccount(true)
        }.onFailure { e ->
            Timber.w(e, "persistLoginTokens: libsteamclient identity push failed")
        }
    }
}

/** Orchestrator observer on the long-lived [WnSteamSession]; drives the connection lifecycle off the channel state — state 2 (Connected): mark connected; state 3 (LoggedOn): mark connected+logged-in then run [onWnLoggedOn] once; state 0 (Disconnected): clear flows and, if still the shared session, hand off to [onWnDisconnected]. */
internal fun SteamService.Companion.installWnLogonObserver(session: WnSteamSession) {
    // A fresh session begins a fresh logon — let onWnLoggedOn re-run.
    wnLoggedOnHandled = false
    session.setStateObserver(object : WnSteamStateObserver {
        override fun onStateChanged(state: Int) {
            val name = when (state) {
                0 -> "Disconnected"; 1 -> "Connecting"
                2 -> "Connected";    3 -> "LoggedOn"
                else -> "?($state)"
            }
            Timber.i("WnSteam(logon) state -> %s", name)
            when (state) {
                2 -> {
                    isConnected = true
                }
                3 -> {
                    isConnected = true
                    _isLoggedInFlow.value = true
                    recordLogonSuccess()
                    if (!wnLoggedOnHandled) {
                        wnLoggedOnHandled = true
                        instance?.onWnLoggedOn(session)
                    }
                }
                0 -> {
                    if (wnSession === session) {
                        isConnected = false
                        if (PrefManager.refreshToken.isBlank()) {
                            _isLoggedInFlow.value = false
                        }
                        wnSession = null
                        wnLoggedOnHandled = false
                        instance?.onWnDisconnected()
                    }
                }
            }
        }
        override fun onClientMessage(emsg: Int, eresult: Int, body: ByteArray) {
            Timber.d("WnSteam(logon) inbound emsg=%d eresult=%d body=%d bytes",
                emsg, eresult, body.size)
            if (emsg == 751 && eresult != 1) {
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .reportLogonFailure(eresult = eresult, stillRetrying = true)
                recordLogonFailure(eresult)
                val nonRecoverable = eresult == 5 || eresult == 15 ||
                    eresult == 18 || eresult == 65
                if (nonRecoverable && PrefManager.refreshToken.isNotBlank()) {
                    Timber.w("WnSteam: non-recoverable EResult=$eresult on logon — " +
                        "clearing refresh-token, will require re-sign-in")
                    PrefManager.clearAuthTokens()
                    _isLoggedInFlow.value = false
                    runCatching {
                        PluviaApp.events.emit(
                            SteamEvent.LogonEnded(
                                PrefManager.username,
                                LoginResult.Failed,
                                "Steam refused the cached session (EResult=$eresult). Please sign in again."))
                    }
                    runCatching {
                        com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                            .setLoggedOn(false)
                        com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                            .setSteamId(0L)
                        com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                            .setPersonaName("")
                    }
                }
            }
        }
    })
    // Wire the Kotlin library facade now so it's ready for the populate-complete observer fire that lands a couple seconds after the ClientLicenseList push.
    wnLibraryMirrorJob?.cancel()
    wnLibrary?.stopObserving()
    val library = WnLibraryStore(session)
    wnLibrary = library
    library.startObserving()
    // Log every snapshot transition to see when the populate pipeline completes; the flow is hot + replay=1 so late collectors get the latest snapshot.
    wnLibraryMirrorJob = instance?.scope?.launch(Dispatchers.Default) {
        library.snapshots.collect { snap ->
            Timber.i(
                "WnLibrary snapshot: %d packages, %d owned apps (of %d tracked)",
                snap.packages.size, snap.ownedApps.size, snap.allAppsCount,
            )
            val nameIds  = mutableListOf<Int>()
            val nameStrs = mutableListOf<String>()
            var buildIdsPushed = 0
            var staleBuildIdsPinned = 0
            var sourcePackagesPushed = 0
            for (a in snap.ownedApps) {
                if (a.name.isNotEmpty()) {
                    nameIds.add(a.id)
                    nameStrs.add(a.name)
                }
                // The snapshot carries the branch's newest build; reporting that for a game whose
                // files are older makes the title think it is running an out-of-date install.
                val installedBuildId = getInstalledBuildId(a.id)
                val reportedBuildId = if (installedBuildId > 0L) installedBuildId else a.buildId.toLong()
                if (reportedBuildId > 0L) {
                    com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                        .setAppBuildId(a.id, reportedBuildId.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    ++buildIdsPushed
                    if (installedBuildId > 0L && installedBuildId != a.buildId.toLong()) {
                        ++staleBuildIdsPinned
                    }
                }
                if (a.sourcePackageIds.isNotEmpty()) {
                    com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                        .setAppSourcePackages(
                            a.id, a.sourcePackageIds.toIntArray())
                    ++sourcePackagesPushed
                }
            }
            if (nameIds.isNotEmpty()) {
                com.winlator.cmod.feature.stores.steam.wnsteam.WnLibSteamClient
                    .setAppNames(nameIds.toIntArray(), nameStrs.toTypedArray())
            }
            if (nameIds.isNotEmpty() || buildIdsPushed > 0 || sourcePackagesPushed > 0) {
                Timber.d("WnLibrary mirror → libsteamclient.so: " +
                    "names=${nameIds.size} buildIds=$buildIdsPushed " +
                    "installedBuildIdsPinned=$staleBuildIdsPinned " +
                    "sourcePackages=$sourcePackagesPushed")
            }
        }
    }
}

internal suspend fun SteamService.Companion.bringUpWnSession(svc: SteamService): WnSteamSession? {
    if (NetworkMonitor.isOffline(svc)) {
        Timber.i(
            "Cannot start WnSteam session: this device has no network. Everything a game needs " +
                "to launch is already cached locally, so callers fall back to the cache instead " +
                "of waiting on a connection that cannot be made.",
        )
        return null
    }
    val caPath = CaBundleExtractor.ensureBundle(svc)
    if (caPath.isEmpty()) {
        Timber.e("Cannot start WnSteam session: CA bundle unavailable")
        return null
    }
    val cmUrl = withContext(Dispatchers.IO) {
        WnSteamSession.pickCmUrl(caPath)
    }
    if (cmUrl.isEmpty()) {
        Timber.e("Cannot start WnSteam session: no CM URL")
        return null
    }
    Timber.i("WnSteam: connecting to %s", cmUrl)

    val session = WnSteamSession()
    var ok = false
    try {
        session.setCaBundlePath(caPath)
        val connected = suspendCancellableCoroutine<Boolean> { cont ->
            session.setStateObserver(object : WnSteamStateObserver {
                override fun onStateChanged(state: Int) {
                    if (!cont.isActive) return
                    if (state == 2) cont.resume(true)
                    else if (state == 0) cont.resume(false)
                }
                override fun onClientMessage(emsg: Int, eresult: Int, body: ByteArray) {}
            })
            if (!session.connect(cmUrl)) cont.resume(false)
            cont.invokeOnCancellation { session.disconnect() }
        }
        if (!connected) {
            Timber.e("WnSteam channel did not reach Connected state")
            return null
        }
        ok = true
        return session
    } finally {
        if (!ok) {
            try { session.disconnect() } catch (_: Throwable) {}
            try { session.close() } catch (_: Throwable) {}
        }
    }
}

internal fun SteamService.Companion.dispatchOverlayRequest(serialized: String) {
    val parts = serialized.split('\u0001')
    if (parts.size < 4) {
        Timber.w("dispatchOverlayRequest: malformed payload (parts=${parts.size})")
        return
    }
    val kind  = parts[0]
    val arg1  = parts[1]
    val sid   = parts[2].toLongOrNull() ?: 0L
    val appId = parts[3].toIntOrNull() ?: 0
    val url = when (kind) {
        "webpage" -> if (arg1.startsWith("http://") || arg1.startsWith("https://")) arg1
                     else "https://${arg1}"
        "store"   -> "https://store.steampowered.com/app/${appId}/"
        "user"    -> "https://steamcommunity.com/profiles/${sid}"
        "invite"  -> {
            Timber.i("overlay: invite dialog requested (lobby=0x${sid.toString(16)})")
            return
        }
        "dialog"  -> when (arg1) {
            "Achievements" -> "https://steamcommunity.com/stats/${appId}/achievements/"
            "Players"      -> "https://steamcommunity.com/profiles/${
                com.winlator.cmod.feature.stores.steam.utils.PrefManager.steamUserSteamId64
            }"
            else           -> "https://steamcommunity.com/profiles/${
                com.winlator.cmod.feature.stores.steam.utils.PrefManager.steamUserSteamId64
            }"
        }
        else -> {
            Timber.w("dispatchOverlayRequest: unknown kind '$kind'")
            return
        }
    }
    val svc = instance ?: return
    runCatching {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)
        ).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        svc.applicationContext.startActivity(intent)
        Timber.i("overlay: dispatched $kind → $url")
    }.onFailure { e ->
        Timber.w(e, "overlay: startActivity failed for $url")
    }
}

internal fun SteamService.Companion.clearUserData() {
    PrefManager.clearAuthTokens()

    clearDatabase()
}

internal fun SteamService.Companion.clearCloudSyncCaches() {
    instance?.let { svc ->
        svc.scope.launch {
            svc.db.withTransaction {
                svc.changeNumbersDao.deleteAll()
                svc.fileChangeListsDao.deleteAll()
            }
            Timber.i("Cleared cloud sync caches (change numbers + file lists)")
        }
    }
}

internal suspend fun SteamService.Companion.fetchLatestSteamAppInfo(appId: Int): SteamApp? {
    // getPicsAppInfo returns {"changeNumber":N,"appinfo":{...}}; the native side parses appinfo VDF and WnKeyValue decodes it.
    val wnApp =
        withWnSession { session ->
            withContext(Dispatchers.IO) {
                // Fetch the app token first; token-gated apps omit depots without it.
                val token =
                    runCatching {
                        session.getPicsAccessTokens(listOf(appId), emptyList())?.let { tj ->
                            JSONObject(tj)
                                .optJSONObject("appTokens")
                                ?.optString(appId.toString())
                                ?.toLongOrNull()
                        }
                    }.getOrNull() ?: 0L
                session.getPicsAppInfo(appId, token)?.let { json ->
                    try {
                        val obj = JSONObject(json)
                        val appinfo = obj.optJSONObject("appinfo") ?: return@let null
                        val app = WnKeyValue.fromJsonObject(appinfo).generateSteamApp()
                        if (app.id == INVALID_APP_ID) {
                            null
                        } else {
                            app.copy(
                                receivedPICS = true,
                                lastChangeNumber = obj.optInt("changeNumber", 0),
                            )
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "wn-steam-client appinfo parse failed for appId=$appId")
                        null
                    }
                }
            }
        }
    if (wnApp != null) {
        Timber.i("app info via wn-steam-client: appId=$appId name='${wnApp.name}'")
        return wnApp
    }
    Timber.w("wn-steam-client app info unavailable for appId=$appId")
    return null
}

internal suspend fun SteamService.Companion.persistLatestSteamAppInfo(
    appId: Int,
    remoteSteamApp: SteamApp,
) {
    val service = instance ?: return
    val appFromDb = service.appDao.findApp(appId)
    val packageId = appFromDb?.packageId ?: remoteSteamApp.packageId
    val packageFromDb = if (packageId != INVALID_PKG_ID) service.licenseDao.findLicense(packageId) else null
    val existingInstallDir = appFromDb?.installDir.orEmpty()
    val preserveInstallDir =
        existingInstallDir.isNotEmpty() &&
            (existingInstallDir.startsWith("/") || existingInstallDir.contains(File.separator))

    service.appDao.insert(
        remoteSteamApp.copy(
            packageId = packageId,
            ownerAccountId = packageFromDb?.ownerAccountId ?: appFromDb?.ownerAccountId.orEmpty(),
            licenseFlags =
                packageFromDb?.licenseFlags
                    ?: appFromDb?.licenseFlags
                    ?: EnumSet.noneOf(ELicenseFlags::class.java),
            installDir = if (preserveInstallDir) existingInstallDir else remoteSteamApp.installDir,
        ),
    )
}

/** Force-refreshes [appId]'s PICS depot data once per session; no-op on the main thread. */
internal fun SteamService.Companion.ensureFreshDepotData(appId: Int) {
    if (appId <= 0 || instance == null) return
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return
    if (!picsRefreshedAppsThisSession.add(appId)) return
    val refreshed =
        runCatching {
            runBlocking(Dispatchers.IO) {
                val fresh = fetchLatestSteamAppInfo(appId) ?: return@runBlocking false
                persistLatestSteamAppInfo(appId, fresh)
                true
            }
        }.getOrDefault(false)
    if (refreshed) {
        Timber.i("Refreshed PICS depot data for appId=$appId before depot selection")
    } else {
        picsRefreshedAppsThisSession.remove(appId)
    }
}

internal fun SteamService.Companion.readInstalledDepotManifestIds(appDirPath: String): Map<Int, Long> =
    runCatching {
        val configFile = File(File(appDirPath, ".DepotDownloader"), "depot.config")
        if (!configFile.exists() || !configFile.canRead()) return@runCatching emptyMap()
        val json = JSONObject(configFile.readText())
        val manifests = json.optJSONObject("installedManifestIDs") ?: return@runCatching emptyMap()
        val result = mutableMapOf<Int, Long>()
        for (key in manifests.keys()) {
            val depotId = key.toIntOrNull() ?: continue
            // Missing → INVALID_MANIFEST_ID (Long.MAX_VALUE).
            result[depotId] = manifests.optLong(key, Long.MAX_VALUE)
        }
        result
    }.getOrElse {
        Timber.w(it, "Failed to read Steam depot.config for $appDirPath")
        emptyMap()
    }


internal fun SteamService.Companion.cleanupCancelledUpdate(appDirPath: String) {
    MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
    MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
    clearPersistedProgressSnapshot(appDirPath)

    val stagingDir = File(File(appDirPath, ".DepotDownloader"), "staging")
    if (!stagingDir.exists()) return

    stagingDir
        .walkBottomUp()
        .forEach { staged ->
            if (staged == stagingDir) return@forEach
            if (staged.isDirectory) {
                if (staged.list().isNullOrEmpty()) staged.delete()
                return@forEach
            }

            val relative = staged.relativeTo(stagingDir)
            val finalFile = File(appDirPath, relative.path)
            runCatching {
                finalFile.parentFile?.mkdirs()
                if (finalFile.exists()) {
                    finalFile.delete()
                }
                if (!staged.renameTo(finalFile)) {
                    staged.copyTo(finalFile, overwrite = true)
                    staged.delete()
                }
            }.onFailure {
                Timber.w(it, "Failed to restore staged Steam update file ${staged.absolutePath}")
            }
        }

    if (stagingDir.exists() && stagingDir.list().isNullOrEmpty()) {
        stagingDir.delete()
    }
}
