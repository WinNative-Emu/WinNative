package com.winlator.cmod.feature.sync.google
import android.app.Activity
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.games.PlayGames
import com.google.android.gms.games.SnapshotsClient
import com.google.android.gms.games.snapshot.Snapshot
import com.google.android.gms.games.snapshot.SnapshotContents
import com.google.android.gms.games.snapshot.SnapshotMetadataChange
import com.google.android.gms.tasks.Tasks
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.common.Store
import com.winlator.cmod.feature.stores.common.StoreSessionBus
import com.winlator.cmod.feature.stores.common.StoreSessionEvent
import com.winlator.cmod.feature.stores.epic.service.EpicAuthManager
import com.winlator.cmod.feature.stores.epic.service.EpicService
import com.winlator.cmod.feature.stores.gog.service.GOGAuthManager
import com.winlator.cmod.feature.stores.gog.service.GOGService
import com.winlator.cmod.feature.stores.itch.service.ItchAuthManager
import com.winlator.cmod.feature.stores.itch.service.ItchService
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object CloudSyncManager {
    const val REQUEST_CODE_SAVED_GAMES_PERMISSIONS = 4101

    private const val TAG = "CloudSyncManager"
    private const val PREFS_NAME = "google_store_login_sync"
    private const val KEY_GOOGLE_SYNC_ENABLED = "google_sync_enabled"
    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val KEY_LAST_SYNC_ERROR = "last_sync_error"

    /** When true, the app attempts a Google Play Games sign-in once per process launch. Off by default. */
    private const val KEY_AUTO_SIGNIN_ON_LAUNCH = "google_auto_signin_on_launch"
    private const val SNAPSHOT_NAME = "store_logins_v1"
    private const val AUTH_SESSION_RETRY_COUNT = 5
    private const val AUTH_SESSION_RETRY_DELAY_MS = 750L
    private const val ZIP_MANIFEST = "manifest.json"
    private const val ZIP_STEAM = "stores/steam.json"
    private const val ZIP_EPIC = "stores/epic_credentials.json"
    private const val ZIP_GOG = "stores/gog_auth.json"
    private const val ZIP_ITCH = "stores/itch.json"

    private const val STORE_STEAM = "Steam"
    private const val STORE_EPIC = "Epic"
    private const val STORE_GOG = "GOG"
    private const val STORE_ITCH = "Itch.io"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val syncMutex = Mutex()

    private fun isActivityValidForPlayGames(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed) {
            return false
        }
        val lifecycleState = (activity as? LifecycleOwner)?.lifecycle?.currentState
        return lifecycleState?.isAtLeast(Lifecycle.State.STARTED) ?: true
    }

    data class StoreLoginSyncState(
        val googleSignedIn: Boolean = false,
        val localStores: Set<String> = emptySet(),
        val cloudStores: Set<String> = emptySet(),
        val lastSyncTime: Long? = null,
        val status: SyncStatus = SyncStatus.NOT_SIGNED_IN,
        val detail: String = "",
    )

    enum class SyncStatus {
        NOT_SIGNED_IN,
        EMPTY,
        SYNCED,
        BACKUP_PENDING,
        RESTORE_AVAILABLE,
        ERROR,
    }

    private data class StorePayload(
        val createdAt: Long,
        val stores: Map<String, ByteArray>,
        val fingerprint: String,
    )

    private data class SnapshotReadResult(
        val payload: StorePayload?,
        val lastModifiedTime: Long?,
    )

    private data class SyncSummary(
        val restoredStores: Set<String> = emptySet(),
        val uploadedStores: Set<String> = emptySet(),
        val localStores: Set<String> = emptySet(),
        val cloudStores: Set<String> = emptySet(),
        val lastSyncTime: Long? = null,
    ) {
        fun message(context: Context): String =
            when {
                restoredStores.isNotEmpty() && uploadedStores.isNotEmpty() -> {
                    context.getString(R.string.google_cloud_logins_restored_and_synced, restoredStores.union(uploadedStores).joinToString())
                }

                restoredStores.isNotEmpty() -> {
                    context.getString(R.string.google_cloud_logins_restored, restoredStores.joinToString())
                }

                uploadedStores.isNotEmpty() -> {
                    context.getString(R.string.google_cloud_logins_backed_up, uploadedStores.joinToString())
                }

                cloudStores.isNotEmpty() -> {
                    context.getString(R.string.google_cloud_sync_ready)
                }

                else -> {
                    context.getString(R.string.google_cloud_no_logins_to_sync)
                }
            }
    }

    fun signIn(
        activity: Activity,
        callback: (Boolean, String) -> Unit,
    ) {
        PlayGamesBootstrap.ensureInitialized(activity)
        val gamesSignInClient = PlayGames.getGamesSignInClient(activity)
        Log.i(TAG, "Starting Google Play Games sign-in for store login sync")
        Timber.tag(TAG).i("Starting Google Play Games sign-in for store login sync")
        gamesSignInClient.signIn().addOnCompleteListener { task ->
            if (task.isSuccessful && task.result?.isAuthenticated == true) {
                Log.d(TAG, "Sign in successful to Play Games Services; validating Saved Games access")
                Timber.tag(TAG).i("Google Play Games sign-in succeeded; validating Saved Games access")
                startCloudSyncSession(activity, callback)
            } else {
                Log.e(TAG, "Sign in failed: ${task.exception?.message}")
                Timber.tag(TAG).e(task.exception, "Google Play Games sign-in failed")
                callback(false, activity.getString(R.string.google_cloud_sign_in_failed))
            }
        }
    }

    private fun startCloudSyncSession(
        activity: Activity,
        callback: (Boolean, String) -> Unit,
    ) {
        scope.launch {
            prefs(activity).edit().putBoolean(KEY_GOOGLE_SYNC_ENABLED, true).apply()

            val result =
                withContext(Dispatchers.IO) {
                    if (!awaitAuthenticatedSession(activity)) {
                        return@withContext false to activity.getString(R.string.google_cloud_sign_in_finishing)
                    }

                    Timber.tag(TAG).i("Play Games session ready; checking Saved Games state")
                    val message =
                        runCatching {
                            val state = readStateInternal(activity, authenticated = true)
                            when {
                                state.cloudStores.isNotEmpty() && state.cloudStores != state.localStores -> {
                                    activity.getString(R.string.google_cloud_connected_restore_available, state.cloudStores.joinToString())
                                }

                                state.localStores.isNotEmpty() -> {
                                    activity.getString(R.string.google_cloud_connected_tap_backup, state.localStores.joinToString())
                                }

                                else -> {
                                    activity.getString(R.string.google_cloud_connected_ready)
                                }
                            }
                        }.getOrElse { error ->
                            rememberSyncError(activity, error)
                        }
                    true to message
                }
            callback(result.first, result.second)
        }
    }

    fun signOut(
        activity: Activity,
        callback: (Boolean, String) -> Unit,
    ) {
        Log.i(TAG, "Disabling Google Play Games store login sync")
        Timber.tag(TAG).i("Disabling Google Play Games sync for this app")

        prefs(activity)
            .edit()
            .putBoolean(KEY_GOOGLE_SYNC_ENABLED, false)
            .apply()
        clearSyncError(activity)
        callback(true, activity.getString(R.string.google_cloud_sync_disabled))
    }

    /** Whether the app should auto sign-in to Google Play Games on launch. Off by default. */
    fun isAutoSignInOnLaunchEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SIGNIN_ON_LAUNCH, false)

    fun setAutoSignInOnLaunchEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SIGNIN_ON_LAUNCH, enabled).apply()
    }

    fun isAuthenticated(
        activity: Activity,
        callback: (Boolean) -> Unit,
    ) {
        PlayGamesBootstrap.ensureInitialized(activity)
        val gamesSignInClient = PlayGames.getGamesSignInClient(activity)
        gamesSignInClient.isAuthenticated.addOnCompleteListener { task ->
            val authenticated = task.isSuccessful && task.result?.isAuthenticated == true
            callback(authenticated)
        }
    }

    suspend fun syncOnGoogleScreenOpened(activity: Activity): StoreLoginSyncState =
        withContext(Dispatchers.IO) {
            readCachedStoreLoginState(activity)
        }

    suspend fun readStoreLoginState(activity: Activity): StoreLoginSyncState {
        return withContext(Dispatchers.IO) {
            readCachedStoreLoginState(activity)
        }
    }

    suspend fun refreshStoreLoginState(activity: Activity): StoreLoginSyncState {
        return withContext(Dispatchers.IO) {
            if (!isGoogleSyncEnabled(activity)) {
                return@withContext notSignedInState(activity)
            }
            val authenticated = isAuthenticatedBlocking(activity)
            Timber.tag(TAG).d("Refreshing store login sync state; authenticated=%s", authenticated)
            readStateInternal(activity, authenticated)
        }
    }

    suspend fun restoreStoreLogins(activity: Activity): String {
        return withContext(Dispatchers.IO) {
            if (!isGoogleSyncEnabled(activity)) {
                return@withContext activity.getString(R.string.google_cloud_sign_in_first)
            }
            if (!isAuthenticatedBlocking(activity)) {
                Timber.tag(TAG).w("restoreStoreLogins aborted: Google Play Games not authenticated")
                return@withContext activity.getString(R.string.google_cloud_sign_in_first)
            }

            Log.i(TAG, "Starting manual store login restore")
            clearSyncError(activity)
            Timber.tag(TAG).i("Starting manual store login restore")
            runCatching {
                val summary =
                    syncMutex.withLock {
                        val remote = readRemotePayload(activity)
                        val payload =
                            remote.payload ?: return@withLock SyncSummary(
                                localStores = collectLocalStoreNames(activity),
                                cloudStores = emptySet(),
                            )

                        val restored = restoreMissingStores(activity, payload)
                        if (restored.isNotEmpty()) {
                            rehydrateRestoredStores(activity, restored)
                        }
                        val local = collectLocalPayload(activity)
                        SyncSummary(
                            restoredStores = restored,
                            localStores = local.stores.keys,
                            cloudStores = payload.stores.keys,
                            lastSyncTime = remote.lastModifiedTime ?: payload.createdAt,
                        )
                    }

                clearSyncError(activity)
                if (summary.cloudStores.isEmpty()) {
                    activity.getString(R.string.google_cloud_no_saved_logins_found)
                } else {
                    summary.message(activity)
                }
            }.getOrElse { error ->
                rememberSyncError(activity, error)
            }
        }
    }

    suspend fun backupStoreLogins(activity: Activity): String {
        return withContext(Dispatchers.IO) {
            if (!isGoogleSyncEnabled(activity)) {
                return@withContext activity.getString(R.string.google_cloud_sign_in_first)
            }
            if (!isAuthenticatedBlocking(activity)) {
                Timber.tag(TAG).w("backupStoreLogins aborted: Google Play Games not authenticated")
                return@withContext activity.getString(R.string.google_cloud_sign_in_first)
            }

            Log.i(TAG, "Starting manual store login backup")
            clearSyncError(activity)
            Timber.tag(TAG).i("Starting manual store login backup")
            runCatching {
                val summary =
                    syncMutex.withLock {
                        val localPayload = collectLocalPayload(activity)
                        val remotePayload = readRemotePayload(activity).payload
                        if (localPayload.stores.isEmpty()) {
                            return@withLock SyncSummary(
                                localStores = emptySet(),
                                cloudStores = remotePayload?.stores?.keys ?: emptySet(),
                                lastSyncTime =
                                    readRemotePayload(activity).lastModifiedTime
                                        ?: prefs(activity).getLong(KEY_LAST_SYNC_TIME, 0L).takeIf { it > 0L },
                            )
                        }
                        val shouldUpload = remotePayload == null || localPayload.fingerprint != remotePayload.fingerprint
                        val uploaded = if (shouldUpload) backupPayload(activity, localPayload) else emptySet()
                        val effectiveCloudStores =
                            if (uploaded.isNotEmpty()) {
                                localPayload.stores.keys
                            } else {
                                remotePayload?.stores?.keys ?: localPayload.stores.keys
                            }
                        SyncSummary(
                            uploadedStores = uploaded,
                            localStores = localPayload.stores.keys,
                            cloudStores = effectiveCloudStores,
                            lastSyncTime = prefs(activity).getLong(KEY_LAST_SYNC_TIME, 0L).takeIf { it > 0L },
                        )
                    }

                clearSyncError(activity)
                if (summary.uploadedStores.isNotEmpty()) {
                    summary.message(activity)
                } else if (summary.cloudStores.isNotEmpty()) {
                    activity.getString(R.string.google_cloud_backup_already_matches)
                } else {
                    activity.getString(R.string.google_cloud_no_local_logins)
                }
            }.getOrElse { error ->
                rememberSyncError(activity, error)
            }
        }
    }

    private suspend fun performSmartSync(
        activity: Activity,
        preferRestoreForMissingStores: Boolean,
    ): SyncSummary =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                val localBefore = collectLocalPayload(activity)
                val remote = readRemotePayload(activity)
                val remotePayload = remote.payload
                Timber.tag(TAG).i(
                    "performSmartSync localStores=%s remoteStores=%s preferRestore=%s",
                    localBefore.stores.keys,
                    remotePayload?.stores?.keys ?: emptySet<String>(),
                    preferRestoreForMissingStores,
                )

                val restoredStores =
                    if (preferRestoreForMissingStores && remotePayload != null) {
                        restoreMissingStores(activity, remotePayload)
                    } else {
                        emptySet()
                    }
                if (restoredStores.isNotEmpty()) {
                    rehydrateRestoredStores(activity, restoredStores)
                }

                val localAfterRestore = collectLocalPayload(activity)
                val effectiveCloudStores = remotePayload?.stores?.keys ?: emptySet()

                SyncSummary(
                    restoredStores = restoredStores,
                    localStores = localAfterRestore.stores.keys,
                    cloudStores = effectiveCloudStores,
                    lastSyncTime = remote.lastModifiedTime ?: remotePayload?.createdAt,
                )
            }
        }

    private suspend fun rehydrateRestoredStores(
        context: Context,
        restoredStores: Set<String>,
    ) {
        if (STORE_STEAM in restoredStores) {
            rehydrateSteamSession(context)
        }
        if (STORE_EPIC in restoredStores) {
            rehydrateEpicSession(context)
        }
        if (STORE_GOG in restoredStores) {
            rehydrateGogSession(context)
        }
        if (STORE_ITCH in restoredStores) {
            rehydrateItchSession(context)
        }
    }

    private suspend fun rehydrateSteamSession(context: Context) {
        Timber.tag(TAG).i("Rehydrating restored Steam session for live UI and store state")

        SteamService.initLoginStatus(context)

        // If a logged-out service instance is still around, restart it so the restored tokens are used.
        if (SteamService.instance != null && !SteamService.isLoggedIn) {
            Timber.tag(TAG).d("Restarting existing Steam service after token restore")
            SteamService.stop()
            waitForCondition(timeoutMillis = 6000L) { SteamService.instance == null }
        }

        SteamService.start(context)
        waitForCondition(timeoutMillis = 8000L) { SteamService.instance != null }
        waitForCondition(timeoutMillis = 12000L) { SteamService.isLoggedIn }

        if (SteamService.isLoggedIn) {
            runCatching { SteamService.requestUserPersona() }
                .onFailure { Timber.tag(TAG).w(it, "Steam persona refresh failed after restore") }
            SteamService.requestSync()
        } else {
            Timber.tag(TAG).w("Steam session restore did not finish logging in before refresh timeout")
        }
    }

    private suspend fun rehydrateEpicSession(context: Context) {
        Timber.tag(TAG).i("Rehydrating restored Epic session for live UI and store state")
        EpicAuthManager.updateLoginStatus(context)
        EpicService.triggerLibrarySync(context)
        waitForCondition(timeoutMillis = 6000L) { EpicService.isRunning }
        runCatching { EpicService.refreshLibrary(context) }
            .onFailure { Timber.tag(TAG).w(it, "Epic library refresh failed after restore") }
    }

    private suspend fun rehydrateGogSession(context: Context) {
        Timber.tag(TAG).i("Rehydrating restored GOG session for live UI and store state")
        GOGAuthManager.updateLoginStatus(context)
        GOGService.triggerLibrarySync(context)
        waitForCondition(timeoutMillis = 6000L) { GOGService.isRunning }
        runCatching { GOGService.refreshLibrary(context) }
            .onFailure { Timber.tag(TAG).w(it, "GOG library refresh failed after restore") }
    }

    private suspend fun rehydrateItchSession(context: Context) {
        Timber.tag(TAG).i("Rehydrating restored Itch session for live UI and store state")
        ItchService.start(context)
        ItchService.refreshProfile(context)
    }

    private suspend fun waitForCondition(
        timeoutMillis: Long,
        pollIntervalMillis: Long = 250L,
        predicate: () -> Boolean,
    ): Boolean {
        val maxAttempts = (timeoutMillis / pollIntervalMillis).toInt().coerceAtLeast(1)
        repeat(maxAttempts) {
            if (predicate()) {
                return true
            }
            delay(pollIntervalMillis)
        }
        return predicate()
    }

    private suspend fun backupPayload(
        activity: Activity,
        payload: StorePayload,
    ): Set<String> {
        val client = freshSnapshotsClient(activity) ?: return emptySet()
        Log.i(TAG, "Opening snapshot for backup: $SNAPSHOT_NAME")
        Timber.tag(TAG).i("Opening snapshot for backup: %s", SNAPSHOT_NAME)
        val snapshot = openSnapshot(activity, client, createIfMissing = true) ?: return emptySet()
        val snapshotFileDescriptor = snapshotParcelFileDescriptor(snapshot.snapshotContents)
        try {
            val bytes = payloadToZip(payload)
            Timber.tag(TAG).i("Writing %d bytes to snapshot for stores=%s", bytes.size, payload.stores.keys)
            if (!snapshot.snapshotContents.writeBytes(bytes)) {
                throw IllegalStateException("Unable to write snapshot contents")
            }

            val metadata =
                SnapshotMetadataChange
                    .Builder()
                    .setDescription("Store logins: ${payload.stores.keys.joinToString()}")
                    .setPlayedTimeMillis(0L)
                    .setProgressValue(payload.stores.size.toLong())
                    .build()

            Tasks.await(client.commitAndClose(snapshot, metadata))
            clearSyncError(activity)
            Timber.tag(TAG).i("Snapshot commitAndClose succeeded for stores=%s", payload.stores.keys)

            prefs(activity)
                .edit()
                .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
                .apply()

            return payload.stores.keys
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Snapshot backup failed for stores=%s", payload.stores.keys)
            runCatching { Tasks.await(client.discardAndClose(snapshot)) }
            throw error
        } finally {
            closeQuietly(snapshotFileDescriptor)
        }
    }

    private suspend fun readRemotePayload(activity: Activity): SnapshotReadResult {
        val client = freshSnapshotsClient(activity) ?: return SnapshotReadResult(null, null)
        Timber.tag(TAG).d("Opening snapshot for read: %s", SNAPSHOT_NAME)
        val snapshot = openSnapshot(activity, client, createIfMissing = false) ?: return SnapshotReadResult(null, null)
        val snapshotFileDescriptor = snapshotParcelFileDescriptor(snapshot.snapshotContents)
        return try {
            val bytes = snapshot.snapshotContents.readFully()
            val payload = if (bytes.isNotEmpty()) zipToPayload(bytes) else null
            val modifiedTime = snapshot.metadata.lastModifiedTimestamp
            clearSyncError(activity)
            Timber.tag(TAG).i(
                "Read snapshot bytes=%d remoteStores=%s lastModified=%d",
                bytes.size,
                payload?.stores?.keys ?: emptySet<String>(),
                modifiedTime,
            )
            Tasks.await(client.discardAndClose(snapshot))
            SnapshotReadResult(payload, modifiedTime)
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Snapshot read failed")
            runCatching { Tasks.await(client.discardAndClose(snapshot)) }
            throw error
        } finally {
            closeQuietly(snapshotFileDescriptor)
        }
    }

    private suspend fun openSnapshot(
        context: Context,
        client: SnapshotsClient,
        createIfMissing: Boolean,
    ): Snapshot? {
        repeat(AUTH_SESSION_RETRY_COUNT) { attempt ->
            try {
                Log.i(TAG, "SnapshotsClient.open(name=$SNAPSHOT_NAME, createIfMissing=$createIfMissing)")
                Timber.tag(TAG).d(
                    "SnapshotsClient.open(name=%s, createIfMissing=%s, attempt=%d)",
                    SNAPSHOT_NAME,
                    createIfMissing,
                    attempt + 1,
                )
                val result =
                    Tasks.await(
                        client.open(
                            SNAPSHOT_NAME,
                            createIfMissing,
                            SnapshotsClient.RESOLUTION_POLICY_MOST_RECENTLY_MODIFIED,
                        ),
                    )
                if (!result.isConflict) {
                    Timber.tag(TAG).d("Snapshot open returned without conflict")
                    return result.data
                }
                val snapshot = resolveSnapshotConflict(client, result.conflict) ?: return null
                return snapshot
            } catch (error: Exception) {
                if (!createIfMissing && isMissingSnapshotError(error)) {
                    Timber.tag(TAG).d("No existing store login snapshot found: ${error.message}")
                    return null
                }
                if (isSignInRequiredError(error) && attempt < AUTH_SESSION_RETRY_COUNT - 1) {
                    Timber.tag(TAG).w(
                        error,
                        "Snapshot open hit transient sign-in state; retrying in %d ms",
                        AUTH_SESSION_RETRY_DELAY_MS,
                    )
                    delay(AUTH_SESSION_RETRY_DELAY_MS)
                    return@repeat
                }
                rememberSyncError(context, error)
                Timber.tag(TAG).e(error, "Snapshot open failed for createIfMissing=%s", createIfMissing)
                throw error
            }
        }
        return null
    }

    private suspend fun resolveSnapshotConflict(
        client: SnapshotsClient,
        conflict: SnapshotsClient.SnapshotConflict?,
    ): Snapshot? {
        if (conflict == null) return null

        var pendingConflict: SnapshotsClient.SnapshotConflict? = conflict
        while (pendingConflict != null) {
            val candidates =
                listOfNotNull(
                    pendingConflict.snapshot,
                    pendingConflict.conflictingSnapshot,
                )
            if (candidates.isEmpty()) {
                return null
            }

            val chosen =
                candidates.maxByOrNull { snapshot ->
                    snapshot.metadata.lastModifiedTimestamp
                } ?: return null

            Log.w(TAG, "Snapshot conflict detected for $SNAPSHOT_NAME; resolving with most recent snapshot")
            Timber.tag(TAG).w(
                "Snapshot conflict detected for %s; resolving with lastModified=%d",
                SNAPSHOT_NAME,
                chosen.metadata.lastModifiedTimestamp,
            )

            val resolved =
                Tasks.await(
                    client.resolveConflict(
                        pendingConflict.conflictId,
                        chosen,
                    ),
                )

            if (!resolved.isConflict) {
                Timber.tag(TAG).i(
                    "Resolved snapshot conflict for %s with lastModified=%d",
                    SNAPSHOT_NAME,
                    chosen.metadata.lastModifiedTimestamp,
                )
                return resolved.data
            }

            pendingConflict = resolved.conflict
        }

        return null
    }

    private suspend fun freshSnapshotsClient(activity: Activity): SnapshotsClient? {
        if (!isActivityValidForPlayGames(activity)) {
            Timber.tag(TAG).w(
                "Skipping snapshot client creation for %s because the activity is no longer active",
                activity::class.java.simpleName,
            )
            return null
        }
        PlayGamesBootstrap.ensureInitialized(activity)
        return PlayGames.getSnapshotsClient(activity)
    }

    private fun snapshotParcelFileDescriptor(contents: SnapshotContents?): ParcelFileDescriptor? =
        runCatching {
            contents?.parcelFileDescriptor
        }.getOrNull()

    private fun closeQuietly(descriptor: ParcelFileDescriptor?) {
        try {
            descriptor?.close()
        } catch (_: Exception) {
        }
    }

    fun onSavedGamesPermissionResult(activity: Activity) {
        Timber.tag(TAG).w(
            "Ignoring legacy Saved Games permission callback after Play Games v2-only sync migration for %s",
            activity::class.java.simpleName,
        )
    }

    private suspend fun readStateInternal(
        activity: Activity,
        authenticated: Boolean,
    ): StoreLoginSyncState {
        val localStores = collectLocalStoreNames(activity)
        if (!authenticated) {
            return StoreLoginSyncState(
                googleSignedIn = false,
                localStores = localStores,
                status = SyncStatus.NOT_SIGNED_IN,
                detail =
                    if (localStores.isEmpty()) {
                        activity.getString(R.string.google_cloud_sign_in_to_sync)
                    } else {
                        activity.getString(R.string.google_cloud_sign_in_to_backup, localStores.joinToString())
                    },
            )
        }

        return try {
            val remote = readRemotePayload(activity)
            val cloudStores = remote.payload?.stores?.keys ?: emptySet()
            val status =
                when {
                    cloudStores.isEmpty() && localStores.isEmpty() -> SyncStatus.EMPTY
                    cloudStores.isNotEmpty() && cloudStores == localStores -> SyncStatus.SYNCED
                    cloudStores.isNotEmpty() && cloudStores.containsAll(localStores) -> SyncStatus.RESTORE_AVAILABLE
                    localStores.isNotEmpty() && localStores.containsAll(cloudStores) -> SyncStatus.BACKUP_PENDING
                    cloudStores.isNotEmpty() && localStores.isNotEmpty() -> SyncStatus.BACKUP_PENDING
                    localStores.isNotEmpty() -> SyncStatus.BACKUP_PENDING
                    else -> SyncStatus.EMPTY
                }
            val detail =
                when (status) {
                    SyncStatus.EMPTY -> {
                        activity.getString(R.string.google_cloud_no_backed_up_logins)
                    }

                    SyncStatus.BACKUP_PENDING -> {
                        when {
                            cloudStores.isNotEmpty() && localStores.isNotEmpty() && cloudStores != localStores -> {
                                activity.getString(R.string.google_cloud_local_cloud_differ)
                            }

                            localStores.isNotEmpty() -> {
                                activity.getString(R.string.google_cloud_local_ready_to_backup, localStores.joinToString())
                            }

                            else -> {
                                activity.getString(R.string.google_cloud_backup_ready)
                            }
                        }
                    }

                    SyncStatus.RESTORE_AVAILABLE -> {
                        activity.getString(R.string.google_cloud_restore_available, cloudStores.joinToString())
                    }

                    SyncStatus.SYNCED -> {
                        activity.getString(R.string.google_cloud_logins_synced)
                    }

                    SyncStatus.NOT_SIGNED_IN -> {
                        activity.getString(R.string.google_cloud_sign_in_to_sync)
                    }

                    SyncStatus.ERROR -> {
                        activity.getString(R.string.google_cloud_sync_problem)
                    }
                }
            StoreLoginSyncState(
                googleSignedIn = true,
                localStores = localStores,
                cloudStores = cloudStores,
                lastSyncTime = remote.lastModifiedTime ?: prefs(activity).getLong(KEY_LAST_SYNC_TIME, 0L).takeIf { it > 0L },
                status = status,
                detail = detail,
            )
        } catch (error: Exception) {
            val detail = rememberSyncError(activity, error)
            Timber.tag(TAG).e(error, "Failed to read store login sync state")
            StoreLoginSyncState(
                googleSignedIn = true,
                localStores = localStores,
                status = SyncStatus.ERROR,
                detail = detail,
            )
        }
    }

    private fun collectLocalStoreNames(context: Context): Set<String> = collectLocalPayload(context).stores.keys

    private fun collectLocalPayload(context: Context): StorePayload {
        PrefManager.init(context)

        val stores = linkedMapOf<String, ByteArray>()

        exportSteam(context)?.let { stores[STORE_STEAM] = it }
        exportEpic(context)?.let { stores[STORE_EPIC] = it }
        exportGog(context)?.let { stores[STORE_GOG] = it }
        exportItch(context)?.let { stores[STORE_ITCH] = it }

        val createdAt = System.currentTimeMillis()
        val fingerprint = computeFingerprint(stores)
        Timber.tag(TAG).i("Collected local store payload stores=%s fingerprint=%s", stores.keys, fingerprint.take(12))
        return StorePayload(createdAt = createdAt, stores = stores, fingerprint = fingerprint)
    }

    private fun exportSteam(context: Context): ByteArray? {
        if (!SteamService.hasStoredCredentials(context)) {
            return null
        }

        val json =
            JSONObject().apply {
                put("username", PrefManager.username)
                put("refreshToken", PrefManager.refreshToken)
                put("accessToken", PrefManager.accessToken)
                put("steamUserSteamId64", PrefManager.steamUserSteamId64)
                put("steamUserAccountId", PrefManager.steamUserAccountId)
                put("steamUserName", PrefManager.steamUserName)
                put("steamUserAvatarHash", PrefManager.steamUserAvatarHash)
                put("personaState", PrefManager.personaState)
                put("clientId", PrefManager.clientId)
            }
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun exportEpic(context: Context): ByteArray? {
        if (!EpicAuthManager.hasStoredCredentials(context)) {
            return null
        }
        val file = File(context.filesDir, "epic/credentials.json")
        return if (file.exists()) file.readBytes() else null
    }

    private fun exportGog(context: Context): ByteArray? {
        if (!GOGAuthManager.hasStoredCredentials(context)) {
            return null
        }
        val file = File(GOGAuthManager.getAuthConfigPath(context))
        return if (file.exists()) file.readBytes() else null
    }

    private fun exportItch(context: Context): ByteArray? {
        if (!ItchAuthManager.isLoggedIn(context)) return null
        val prefs = context.applicationContext.getSharedPreferences("itch_store", Context.MODE_PRIVATE)
        val user = prefs.getString("signed_in_user", "")
        val cookies = prefs.getStringSet("cookies", emptySet())
        if (user.isNullOrEmpty() && cookies.isNullOrEmpty()) return null

        val json = JSONObject().apply {
            put("signed_in_user", user)
            put("cookies", JSONArray(cookies))
        }
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun restoreMissingStores(
        context: Context,
        payload: StorePayload,
    ): Set<String> {
        val restored = linkedSetOf<String>()

        for ((store, bytes) in payload.stores) {
            when (store) {
                STORE_STEAM -> {
                    if (!SteamService.hasStoredCredentials(context) && restoreSteam(context, bytes)) {
                        restored += STORE_STEAM
                    }
                }

                STORE_EPIC -> {
                    if (!EpicAuthManager.hasStoredCredentials(context) && restoreEpic(context, bytes)) {
                        restored += STORE_EPIC
                    }
                }

                STORE_GOG -> {
                    if (!GOGAuthManager.hasStoredCredentials(context) && restoreGog(context, bytes)) {
                        restored += STORE_GOG
                    }
                }

                STORE_ITCH -> {
                    if (!ItchAuthManager.isLoggedIn(context) && restoreItch(context, bytes)) {
                        restored += STORE_ITCH
                    }
                }
            }
        }

        return restored
    }

    private fun restoreSteam(
        context: Context,
        bytes: ByteArray,
    ): Boolean =
        runCatching {
            Timber.tag(TAG).i("Restoring Steam login tokens from cloud payload")
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            PrefManager.init(context)
            PrefManager.username = json.optString("username", "")
            PrefManager.refreshToken = json.optString("refreshToken", "")
            PrefManager.accessToken = json.optString("accessToken", "")
            PrefManager.steamUserSteamId64 = json.optLong("steamUserSteamId64", 0L)
            PrefManager.steamUserAccountId = json.optInt("steamUserAccountId", 0)
            PrefManager.steamUserName = json.optString("steamUserName", "")
            PrefManager.steamUserAvatarHash = json.optString("steamUserAvatarHash", "")
            PrefManager.personaState = json.optInt("personaState", 0)
            PrefManager.clientId = json.optLong("clientId", 0L)
            SteamService.initLoginStatus(context)
            SteamService.start(context)
            StoreSessionBus.emit(StoreSessionEvent.SessionRestored(Store.STEAM))
            true
        }.getOrElse { error ->
            Timber.tag(TAG).e(error, "Failed to restore Steam login tokens")
            false
        }

    private fun restoreEpic(
        context: Context,
        bytes: ByteArray,
    ): Boolean =
        runCatching {
            Timber.tag(TAG).i("Restoring Epic login tokens from cloud payload")
            val file = File(context.filesDir, "epic/credentials.json")
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            EpicAuthManager.updateLoginStatus(context)
            EpicService.start(context)
            StoreSessionBus.emit(StoreSessionEvent.SessionRestored(Store.EPIC))
            true
        }.getOrElse { error ->
            Timber.tag(TAG).e(error, "Failed to restore Epic login tokens")
            false
        }

    private fun restoreGog(
        context: Context,
        bytes: ByteArray,
    ): Boolean =
        runCatching {
            Timber.tag(TAG).i("Restoring GOG login tokens from cloud payload")
            val file = File(GOGAuthManager.getAuthConfigPath(context))
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            GOGAuthManager.updateLoginStatus(context)
            GOGService.start(context)
            StoreSessionBus.emit(StoreSessionEvent.SessionRestored(Store.GOG))
            true
        }.getOrElse { error ->
            Timber.tag(TAG).e(error, "Failed to restore GOG login tokens")
            false
        }

    private fun restoreItch(context: Context, bytes: ByteArray): Boolean =
        runCatching {
            Timber.tag(TAG).i("Restoring Itch.io login tokens from cloud payload")
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            val prefs = context.applicationContext.getSharedPreferences("itch_store", Context.MODE_PRIVATE)
            val user = json.optString("signed_in_user", "")
            val cookiesJson = json.optJSONArray("cookies")
            val cookies = mutableSetOf<String>()
            if (cookiesJson != null) {
                for (i in 0 until cookiesJson.length()) cookies.add(cookiesJson.getString(i))
            }

            prefs.edit().apply {
                if (user.isNotEmpty()) putString("signed_in_user", user)
                if (cookies.isNotEmpty()) putStringSet("cookies", cookies)
                apply()
            }
            // Reset the WebClient so it recreates the CookieJar and reads the newly restored cookies from disk.
            com.winlator.cmod.feature.stores.itch.service.ItchWebClient.reset()
            StoreSessionBus.emit(StoreSessionEvent.SessionRestored(Store.ITCH))
            true
        }.getOrElse { error ->
            Timber.tag(TAG).e(error, "Failed to restore Itch.io login tokens")
            false
        }

    private fun payloadToZip(payload: StorePayload): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val manifest =
                JSONObject().apply {
                    put("version", 1)
                    put("createdAt", payload.createdAt)
                    put("fingerprint", payload.fingerprint)
                    put("stores", JSONArray(payload.stores.keys.toList()))
                }
            writeZipEntry(zip, ZIP_MANIFEST, manifest.toString(2).toByteArray(StandardCharsets.UTF_8))

            payload.stores.forEach { (store, data) ->
                val entryName =
                    when (store) {
                        STORE_STEAM -> ZIP_STEAM
                        STORE_EPIC -> ZIP_EPIC
                        STORE_GOG -> ZIP_GOG
                        STORE_ITCH -> ZIP_ITCH
                        else -> null
                    }
                if (entryName != null) {
                    writeZipEntry(zip, entryName, data)
                }
            }
        }
        return output.toByteArray()
    }

    private fun zipToPayload(bytes: ByteArray): StorePayload? {
        val stores = linkedMapOf<String, ByteArray>()
        var createdAt = 0L
        var fingerprint: String? = null

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val entryBytes = zip.readBytes()
                when (entry.name) {
                    ZIP_MANIFEST -> {
                        val manifest = JSONObject(String(entryBytes, StandardCharsets.UTF_8))
                        createdAt = manifest.optLong("createdAt", 0L)
                        fingerprint = manifest.optString("fingerprint").takeIf { it.isNotBlank() }
                    }

                    ZIP_STEAM -> {
                        stores[STORE_STEAM] = entryBytes
                    }

                    ZIP_EPIC -> {
                        stores[STORE_EPIC] = entryBytes
                    }

                    ZIP_GOG -> {
                        stores[STORE_GOG] = entryBytes
                    }

                    ZIP_ITCH -> {
                        stores[STORE_ITCH] = entryBytes
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (stores.isEmpty() && createdAt == 0L && fingerprint == null) {
            return null
        }

        val resolvedFingerprint = fingerprint ?: computeFingerprint(stores)
        return StorePayload(
            createdAt = if (createdAt > 0L) createdAt else System.currentTimeMillis(),
            stores = stores,
            fingerprint = resolvedFingerprint,
        )
    }

    private fun writeZipEntry(
        zip: ZipOutputStream,
        name: String,
        data: ByteArray,
    ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    private fun computeFingerprint(stores: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        stores.toSortedMap().forEach { (store, data) ->
            digest.update(store.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(data)
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun notSignedInState(activity: Activity): StoreLoginSyncState =
        StoreLoginSyncState(
            googleSignedIn = false,
            localStores = collectLocalStoreNames(activity),
            status = SyncStatus.NOT_SIGNED_IN,
            detail = activity.getString(R.string.google_cloud_sign_in_to_sync),
        )

    fun readCachedStoreLoginState(context: Context): StoreLoginSyncState {
        val localStores = collectLocalStoreNames(context)
        val lastSyncTime = prefs(context).getLong(KEY_LAST_SYNC_TIME, 0L).takeIf { it > 0L }
        val syncEnabled = isGoogleSyncEnabled(context)
        return StoreLoginSyncState(
            googleSignedIn = syncEnabled,
            localStores = localStores,
            cloudStores = emptySet(),
            lastSyncTime = lastSyncTime,
            status =
                when {
                    !syncEnabled -> SyncStatus.NOT_SIGNED_IN
                    localStores.isNotEmpty() -> SyncStatus.BACKUP_PENDING
                    else -> SyncStatus.EMPTY
                },
            detail =
                when {
                    !syncEnabled -> context.getString(R.string.google_cloud_sign_in_to_sync)
                    localStores.isNotEmpty() -> context.getString(R.string.google_cloud_local_ready_to_backup, localStores.joinToString())
                    else -> context.getString(R.string.google_cloud_connected_ready)
                },
        )
    }

    private fun isGoogleSyncEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_GOOGLE_SYNC_ENABLED, false)

    private fun clearSyncError(context: Context) {
        prefs(context)
            .edit()
            .remove(KEY_LAST_SYNC_ERROR)
            .apply()
    }

    private fun rememberSyncError(
        context: Context,
        error: Throwable,
    ): String {
        val detail = explainSyncError(context, error)
        Log.e(TAG, detail, error)
        prefs(context)
            .edit()
            .putString(KEY_LAST_SYNC_ERROR, detail)
            .apply()
        return detail
    }

    private fun explainSyncError(
        context: Context,
        error: Throwable,
    ): String {
        val apiStatusCode =
            generateSequence(error) { it.cause }
                .filterIsInstance<ApiException>()
                .map { it.statusCode }
                .firstOrNull()
        val chain =
            generateSequence(error) { it.cause }
                .mapNotNull { throwable ->
                    val simpleName = throwable::class.java.simpleName.takeIf { it.isNotBlank() }
                    val message = throwable.message?.takeIf { it.isNotBlank() }
                    when {
                        simpleName != null && message != null -> "$simpleName: $message"
                        message != null -> message
                        else -> simpleName
                    }
                }.toList()
        val rawMessage = chain.joinToString(" | ")
        val normalized = rawMessage.lowercase()
        return when {
            apiStatusCode == CommonStatusCodes.SIGN_IN_REQUIRED ||
                "sign_in_required" in normalized || "apiexception: 4" in normalized -> {
                context.getString(R.string.google_cloud_error_auth_needed)
            }

            apiStatusCode == CommonStatusCodes.DEVELOPER_ERROR ||
                "developer_error" in normalized ||
                "statuscode=10" in normalized -> {
                context.getString(R.string.google_cloud_error_developer)
            }

            "sign-in check failed" in normalized ||
                "cannot find the installed destination app" in normalized ||
                "games service" in normalized -> {
                context.getString(R.string.google_cloud_error_build_rejected)
            }

            apiStatusCode == CommonStatusCodes.NETWORK_ERROR ||
                apiStatusCode == CommonStatusCodes.TIMEOUT ||
                apiStatusCode == CommonStatusCodes.ERROR ||
                "service_version_update_required" in normalized ||
                "network" in normalized || "timeout" in normalized -> {
                context.getString(R.string.google_cloud_error_network)
            }

            isMissingSnapshotError(error) -> {
                context.getString(R.string.google_cloud_error_no_snapshot)
            }

            rawMessage.isNotBlank() -> {
                context.getString(R.string.google_cloud_error_generic, rawMessage)
            }

            else -> {
                context.getString(R.string.google_cloud_error_before_open)
            }
        }
    }

    private fun isMissingSnapshotError(error: Throwable): Boolean {
        val apiStatusCode =
            generateSequence(error) { it.cause }
                .filterIsInstance<ApiException>()
                .map { it.statusCode }
                .firstOrNull()
        val rawMessage =
            generateSequence(error) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" ")
                .lowercase()
        return apiStatusCode == 26504 ||
            "snapshot_not_found" in rawMessage ||
            "snapshot not found" in rawMessage ||
            "no snapshot" in rawMessage
    }

    private fun isSignInRequiredError(error: Throwable): Boolean {
        val apiStatusCode =
            generateSequence(error) { it.cause }
                .filterIsInstance<ApiException>()
                .map { it.statusCode }
                .firstOrNull()
        val rawMessage =
            generateSequence(error) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" ")
                .lowercase()
        return apiStatusCode == CommonStatusCodes.SIGN_IN_REQUIRED ||
            "sign_in_required" in rawMessage ||
            "statuscode=4" in rawMessage
    }

    private suspend fun awaitAuthenticatedSession(activity: Activity): Boolean {
        if (!isActivityValidForPlayGames(activity)) {
            return false
        }
        PlayGamesBootstrap.ensureInitialized(activity)
        repeat(AUTH_SESSION_RETRY_COUNT) { attempt ->
            if (isAuthenticatedBlocking(activity)) {
                return true
            }
            if (attempt < AUTH_SESSION_RETRY_COUNT - 1) {
                delay(AUTH_SESSION_RETRY_DELAY_MS)
            }
        }
        return false
    }

    private suspend fun performSmartSyncWithAuthRetry(
        activity: Activity,
        preferRestoreForMissingStores: Boolean,
    ): SyncSummary {
        repeat(AUTH_SESSION_RETRY_COUNT) { attempt ->
            try {
                return performSmartSync(activity, preferRestoreForMissingStores)
            } catch (error: Exception) {
                if (isSignInRequiredError(error) && attempt < AUTH_SESSION_RETRY_COUNT - 1) {
                    Timber.tag(TAG).w(
                        error,
                        "Saved Games sync hit transient sign-in state; retrying in %d ms",
                        AUTH_SESSION_RETRY_DELAY_MS,
                    )
                    delay(AUTH_SESSION_RETRY_DELAY_MS)
                    return@repeat
                }
                throw error
            }
        }
        throw IllegalStateException("Play Games authentication did not become ready for Saved Games access.")
    }

    private suspend fun isAuthenticatedBlocking(activity: Activity): Boolean {
        if (!isActivityValidForPlayGames(activity)) {
            Timber.tag(TAG).i(
                "Skipping Google auth check because %s is finishing or destroyed",
                activity::class.java.simpleName,
            )
            return false
        }
        return try {
            PlayGamesBootstrap.ensureInitialized(activity)
            val task = PlayGames.getGamesSignInClient(activity).isAuthenticated
            val result =
                withContext(Dispatchers.IO) {
                    try {
                        com.google.android.gms.tasks.Tasks
                            .await(task, 10, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (e: java.util.concurrent.TimeoutException) {
                        Timber.tag(TAG).e("Timeout waiting for Google authentication state")
                        null
                    }
                }
            val authenticated = result?.isAuthenticated == true
            Timber.tag(TAG).i("Blocking Google auth check result=%s", authenticated)
            authenticated
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Failed to read Google authentication state")
            false
        }
    }
}
