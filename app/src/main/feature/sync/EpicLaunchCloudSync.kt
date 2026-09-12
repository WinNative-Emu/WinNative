package com.winlator.cmod.feature.sync

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.epic.service.EpicCloudSavesManager
import com.winlator.cmod.feature.sync.google.GameSaveBackupManager
import com.winlator.cmod.feature.sync.google.GoogleAuthMode
import com.winlator.cmod.runtime.container.Shortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object EpicLaunchCloudSync {
    fun interface StatusSink {
        fun show(text: String)
    }

    @JvmStatic
    fun syncBeforeLaunch(
        activity: Activity,
        shortcut: Shortcut?,
        cloudSyncEnabled: Boolean,
        statusSink: StatusSink,
    ) {
        if (shortcut == null) return
        if (shortcut.getExtra("game_source") != "EPIC") return
        if (!cloudSyncEnabled || CloudSyncHelper.isOfflineMode(shortcut)) return

        CloudSyncHelper.forceDownloadOnContainerSwap(activity, shortcut)

        if (!CloudSyncHelper.hasLocalCloudSaves(activity, shortcut)) {
            statusSink.show(activity.getString(R.string.preloader_downloading_cloud))
            CloudSyncHelper.downloadCloudSaves(activity, shortcut)
            statusSink.show(activity.getString(R.string.preloader_initializing))
            return
        }

        when (CloudSyncHelper.getEpicPendingSyncAction(activity, shortcut)) {
            EpicCloudSavesManager.SyncAction.NONE -> return
            EpicCloudSavesManager.SyncAction.DOWNLOAD -> {
                statusSink.show(activity.getString(R.string.preloader_downloading_cloud))
                CloudSyncHelper.downloadCloudSaves(activity, shortcut)
                statusSink.show(activity.getString(R.string.preloader_initializing))
                return
            }
            EpicCloudSavesManager.SyncAction.UPLOAD -> {
                statusSink.show(activity.getString(R.string.preloader_syncing_cloud))
                CloudSyncHelper.uploadCloudSaves(activity, shortcut)
                statusSink.show(activity.getString(R.string.preloader_initializing))
                return
            }
            EpicCloudSavesManager.SyncAction.CONFLICT -> {
                // Fall through to the conflict dialog below.
            }
        }

        val dialogLatch = CountDownLatch(1)
        var useCloud = false
        var useLocal = false
        var keepBackup = false
        val timestamps = CloudSyncHelper.getEpicConflictTimestamps(activity, shortcut)

        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val cancelObserver =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    Timber.tag("EpicLaunchCloudSync").w(
                        "Activity destroyed while Epic cloud-conflict dialog was up; releasing latch",
                    )
                    dialogLatch.countDown()
                }
            }

        if (activity.isFinishing || activity.isDestroyed ||
            lifecycle?.currentState == Lifecycle.State.DESTROYED
        ) {
            Timber.tag("EpicLaunchCloudSync").w(
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
                EpicCloudConflictDialog.show(
                    activity = activity,
                    timestamps = timestamps,
                    onUseCloud = { keep ->
                        useCloud = true
                        keepBackup = keep
                        dialogLatch.countDown()
                    },
                    onUseLocal = { keep ->
                        useCloud = false
                        useLocal = true
                        keepBackup = keep
                        dialogLatch.countDown()
                    },
                )
            } catch (t: Throwable) {
                Timber.tag("EpicLaunchCloudSync").w(
                    t,
                    "Could not show the Epic cloud-conflict dialog; keeping the local save",
                )
                dialogLatch.countDown()
            }
        }

        try {
            if (!dialogLatch.await(10, TimeUnit.MINUTES)) {
                Timber.tag("EpicLaunchCloudSync").w(
                    "Epic cloud-conflict dialog timed out after 10 minutes; treating as 'keep local'",
                )
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            activity.runOnUiThread { lifecycle?.removeObserver(cancelObserver) }
            return
        }

        activity.runOnUiThread { lifecycle?.removeObserver(cancelObserver) }

        when {
            useCloud -> {
                if (keepBackup) {
                    // "Use Cloud" overwrites the local save — back it up first (M-1).
                    backupLocalSaveToGoogle(activity, shortcut)
                }
                statusSink.show(activity.getString(R.string.preloader_syncing_cloud))
                CloudSyncHelper.downloadCloudSaves(activity, shortcut)
                statusSink.show(activity.getString(R.string.preloader_initializing))
            }
            useLocal -> {
                if (keepBackup) {
                    // "Use Local" pushes local over the Epic cloud; Epic has no non-destructive cloud capture (only Steam does), so back up the local save to Google as the recovery point.
                    backupLocalSaveToGoogle(activity, shortcut)
                }
                statusSink.show(activity.getString(R.string.preloader_syncing_cloud))
                CloudSyncHelper.uploadCloudSaves(activity, shortcut)
                statusSink.show(activity.getString(R.string.preloader_initializing))
            }
        }
    }

    /** Back up the local Epic save before a "Use Cloud" download overwrites it — mirrors to Google Play Games (no-op when not signed in). Best-effort; never blocks the launch path. */
    private fun backupLocalSaveToGoogle(
        activity: Activity,
        shortcut: Shortcut,
    ) {
        val gameId = shortcut.getExtra("app_id").takeIf { it.isNotEmpty() } ?: return
        val gameName = shortcut.name ?: "Unknown"
        try {
            val result =
                runBlocking(Dispatchers.IO) {
                    GameSaveBackupManager.backupSaveToGoogle(
                        activity = activity,
                        gameSource = GameSaveBackupManager.GameSource.EPIC,
                        gameId = gameId,
                        gameName = gameName,
                        origin = GameSaveBackupManager.BackupOrigin.LOCAL,
                        authMode = GoogleAuthMode.RESUME,
                    )
                }
            Timber.tag("EpicLaunchCloudSync").i("Pre-overwrite Epic local backup: %s", result.message)
        } catch (e: Exception) {
            Timber.tag("EpicLaunchCloudSync").w(e, "Failed to back up Epic local save before Use-Cloud")
        }
    }
}
