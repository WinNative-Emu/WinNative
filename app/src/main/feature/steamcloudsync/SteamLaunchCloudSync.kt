package com.winlator.cmod.feature.steamcloudsync

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.data.PostSyncInfo
import com.winlator.cmod.feature.stores.steam.enums.SaveLocation
import com.winlator.cmod.feature.stores.steam.enums.SyncResult
import com.winlator.cmod.feature.sync.google.GameSaveBackupManager
import com.winlator.cmod.feature.sync.google.GoogleAuthMode
import com.winlator.cmod.runtime.container.Shortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object SteamLaunchCloudSync {
    fun interface StatusSink {
        fun show(text: String)
    }

    /** Launch-time Steam cloud reconciliation. Steam-only — never starts Google Play Games or Drive consent. */
    @JvmStatic
    fun syncBeforeLaunch(
        activity: Activity,
        shortcut: Shortcut?,
        cloudSyncEnabled: Boolean,
        statusSink: StatusSink,
    ) {
        if (shortcut == null) return
        if (shortcut.getExtra("game_source") != "STEAM") return
        if (!cloudSyncEnabled || SteamCloudSyncHelper.isOfflineMode(shortcut)) return

        SteamCloudSyncHelper.forceDownloadOnContainerSwap(activity, shortcut)

        statusSink.show(activity.getString(R.string.preloader_checking_cloud))
        val initialSync =
            runCatching {
                SteamCloudSyncHelper.syncBeforeLaunchBlocking(activity, shortcut, SaveLocation.None)
            }.onFailure { e ->
                Timber.tag("SteamLaunchCloudSync").w(e, "Steam launch cloud sync failed")
            }.getOrNull()

        when (initialSync?.syncResult) {
            SyncResult.Success,
            SyncResult.UpToDate,
            null,
            -> {
                statusSink.show(activity.getString(R.string.preloader_initializing))
                return
            }
            SyncResult.Conflict -> {
                showConflictAndResolve(activity, shortcut, statusSink, initialSync)
                return
            }
            SyncResult.PendingOperations -> {
                Timber.tag("SteamLaunchCloudSync").w(
                    "Steam launch cloud sync has pending remote operations for %s: %s",
                    shortcut.name,
                    initialSync.pendingRemoteOperations,
                )
                statusSink.show(activity.getString(R.string.preloader_initializing))
                return
            }
            else -> {
                Timber.tag("SteamLaunchCloudSync").w(
                    "Steam launch cloud sync returned %s for %s",
                    initialSync.syncResult,
                    shortcut.name,
                )
                statusSink.show(activity.getString(R.string.preloader_initializing))
                return
            }
        }
    }

    private fun showConflictAndResolve(
        activity: Activity,
        shortcut: Shortcut,
        statusSink: StatusSink,
        initialSync: PostSyncInfo,
    ) {
        val dialogLatch = CountDownLatch(1)
        var answered = false
        var useCloud = false
        var keepBackup = false
        val timestamps = SteamCloudSyncHelper.timestampsFromSyncInfo(activity, shortcut, initialSync)

        // If the activity is destroyed while the dialog is up, release the latch on ON_DESTROY or this thread blocks forever; default useCloud=false falls back to "keep local".
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val cancelObserver =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    Timber.tag("SteamLaunchCloudSync").w(
                        "Activity destroyed while cloud-conflict dialog was up; releasing latch",
                    )
                    dialogLatch.countDown()
                }
            }

        if (activity.isFinishing || activity.isDestroyed ||
            lifecycle?.currentState == Lifecycle.State.DESTROYED
        ) {
            Timber.tag("SteamLaunchCloudSync").w(
                "Activity is already gone; not prompting for the cloud conflict and keeping the " +
                    "local save so the launch is never held behind a dialog nobody can answer",
            )
            statusSink.show(activity.getString(R.string.preloader_initializing))
            return
        }

        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) {
                dialogLatch.countDown()
                return@runOnUiThread
            }
            lifecycle?.addObserver(cancelObserver)
            try {
                SteamCloudConflictDialog.show(
                    activity,
                    timestamps,
                    onUseCloud = { keep ->
                        answered = true
                        useCloud = true
                        keepBackup = keep
                        dialogLatch.countDown()
                    },
                    onUseLocal = { keep ->
                        answered = true
                        useCloud = false
                        keepBackup = keep
                        dialogLatch.countDown()
                    },
                )
            } catch (t: Throwable) {
                Timber.tag("SteamLaunchCloudSync").w(
                    t,
                    "Could not show the cloud-conflict dialog; keeping the local save",
                )
                dialogLatch.countDown()
            }
        }

        try {
            // Timeout in case the dialog and lifecycle observer both fail to fire — bounds the worst case.
            if (!dialogLatch.await(10, TimeUnit.MINUTES)) {
                Timber.tag("SteamLaunchCloudSync").w(
                    "Cloud-conflict dialog timed out after 10 minutes; treating as 'keep local'",
                )
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            activity.runOnUiThread { lifecycle?.removeObserver(cancelObserver) }
            return
        }

        activity.runOnUiThread { lifecycle?.removeObserver(cancelObserver) }

        if (!answered) {
            Timber.tag("SteamLaunchCloudSync").w(
                "Cloud conflict was never answered; leaving both saves untouched rather than " +
                    "pushing the local one over the cloud",
            )
            statusSink.show(activity.getString(R.string.preloader_initializing))
            return
        }

        if (keepBackup && useCloud) {
            // "Use Cloud" overwrites the LOCAL save — snapshot it first.
            backupDiscardedSave(activity, shortcut, GameSaveBackupManager.BackupOrigin.LOCAL)
        }
        if (keepBackup && !useCloud) {
            // "Use Local" overwrites the CLOUD save (maybe newer progress from another device); Steam keeps no server-side history, so capture the cloud copy first.
            backupDiscardedCloudSave(activity, shortcut)
        }
        val preferredSave = if (useCloud) SaveLocation.Remote else SaveLocation.Local
        statusSink.show(
            activity.getString(
                if (useCloud) R.string.preloader_downloading_cloud else R.string.preloader_uploading_cloud,
            ),
        )
        runCatching {
            SteamCloudSyncHelper.syncBeforeLaunchBlocking(activity, shortcut, preferredSave)
        }.onFailure { e ->
            Timber.tag("SteamLaunchCloudSync").w(e, "Steam conflict resolution sync failed")
        }
        statusSink.show(activity.getString(R.string.preloader_initializing))
    }

    private fun backupDiscardedSave(
        activity: Activity,
        shortcut: Shortcut,
        origin: GameSaveBackupManager.BackupOrigin,
    ) {
        val gameId = shortcut.getExtra("app_id").takeIf { it.isNotEmpty() } ?: return
        val gameName = shortcut.name ?: "Unknown"
        try {
            val result =
                runBlocking(Dispatchers.IO) {
                    GameSaveBackupManager.backupDiscardedSave(
                        activity = activity,
                        gameSource = GameSaveBackupManager.GameSource.STEAM,
                        gameId = gameId,
                        gameName = gameName,
                        origin = origin,
                        authMode = GoogleAuthMode.RESUME,
                        containerHint = SteamCloudSyncHelper.resolveShortcutContainer(activity, shortcut),
                    )
                }
            Timber.tag("SteamLaunchCloudSync").i("Discarded Steam save backup: %s", result.message)
        } catch (e: Exception) {
            Timber.tag("SteamLaunchCloudSync").w(e, "Failed to back up discarded Steam save")
        }
    }

    /** Capture the Steam Cloud save before "Use Local" overwrites it — non-destructive pre-capture (never touches the live local save). Best-effort. */
    private fun backupDiscardedCloudSave(
        activity: Activity,
        shortcut: Shortcut,
    ) {
        val appId = shortcut.getExtra("app_id").takeIf { it.isNotEmpty() }?.toIntOrNull() ?: return
        try {
            val captured =
                runBlocking(Dispatchers.IO) {
                    SteamSaveSnapshotManager.captureCloudSnapshot(
                        activity.applicationContext,
                        appId,
                        SteamCloudSyncHelper.resolveShortcutContainer(activity, shortcut),
                    )
                }
            Timber.tag("SteamLaunchCloudSync").i(
                "Cloud save pre-capture before Use-Local for appId=%d: %s",
                appId,
                captured,
            )
        } catch (e: Exception) {
            Timber.tag("SteamLaunchCloudSync").w(e, "Failed to capture cloud save before Use-Local")
        }
    }
}
