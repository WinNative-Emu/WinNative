package com.winlator.cmod.feature.sync

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.gog.service.GOGCloudSavesManager
import com.winlator.cmod.feature.sync.google.GameSaveBackupManager
import com.winlator.cmod.feature.sync.google.GoogleAuthMode
import com.winlator.cmod.runtime.container.Shortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object GogLaunchCloudSync {
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
        if (shortcut.getExtra("game_source") != "GOG") return
        if (!cloudSyncEnabled || CloudSyncHelper.isOfflineMode(shortcut)) return

        Timber.tag("GogLaunchCloudSync").i("Checking GOG cloud saves before launch for ${shortcut.name}")
        CloudSyncHelper.forceDownloadOnContainerSwap(activity, shortcut)

        if (!CloudSyncHelper.hasLocalCloudSaves(activity, shortcut)) {
            Timber.tag("GogLaunchCloudSync").i("No local GOG cloud-save files found; downloading before launch")
            statusSink.show(activity.getString(R.string.preloader_downloading_cloud))
            CloudSyncHelper.downloadCloudSaves(activity, shortcut)
            statusSink.show(activity.getString(R.string.preloader_initializing))
            return
        }

        val pendingAction = CloudSyncHelper.getGogPendingSyncAction(activity, shortcut)
        Timber.tag("GogLaunchCloudSync").i("Pending GOG cloud action before launch: $pendingAction")
        when (pendingAction) {
            GOGCloudSavesManager.SyncAction.NONE -> return
            GOGCloudSavesManager.SyncAction.DOWNLOAD -> {
                statusSink.show(activity.getString(R.string.preloader_downloading_cloud))
                CloudSyncHelper.downloadCloudSaves(activity, shortcut)
                statusSink.show(activity.getString(R.string.preloader_initializing))
                return
            }
            GOGCloudSavesManager.SyncAction.UPLOAD -> {
                Timber.tag("GogLaunchCloudSync").i(
                    "Local GOG cloud saves are newer before launch; deferring upload until exit",
                )
                return
            }
            GOGCloudSavesManager.SyncAction.CONFLICT -> {
                // Fall through to the conflict dialog below.
            }
        }

        val dialogLatch = CountDownLatch(1)
        var useCloud = false
        var useLocal = false
        var keepBackup = false
        val timestamps = CloudSyncHelper.getGogConflictTimestamps(activity, shortcut)

        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val cancelObserver =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    Timber.tag("GogLaunchCloudSync").w(
                        "Activity destroyed while GOG cloud-conflict dialog was up; releasing latch",
                    )
                    dialogLatch.countDown()
                }
            }

        if (activity.isFinishing || activity.isDestroyed ||
            lifecycle?.currentState == Lifecycle.State.DESTROYED
        ) {
            Timber.tag("GogLaunchCloudSync").w(
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
                GogCloudConflictDialog.show(
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
                Timber.tag("GogLaunchCloudSync").w(
                    t,
                    "Could not show the GOG cloud-conflict dialog; keeping the local save",
                )
                dialogLatch.countDown()
            }
        }

        try {
            if (!dialogLatch.await(10, TimeUnit.MINUTES)) {
                Timber.tag("GogLaunchCloudSync").w(
                    "GOG cloud-conflict dialog timed out after 10 minutes; treating as 'keep local'",
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
                    // "Use Cloud" overwrites the local save — back it up first (M-2).
                    backupLocalSaveToGoogle(activity, shortcut)
                }
                statusSink.show(activity.getString(R.string.preloader_syncing_cloud))
                CloudSyncHelper.downloadCloudSaves(activity, shortcut)
                statusSink.show(activity.getString(R.string.preloader_initializing))
            }
            useLocal -> {
                if (keepBackup) {
                    // "Use Local" pushes local over the GOG cloud; GOG has no non-destructive cloud capture (only Steam does), so back up the local save to Google as the recovery point.
                    backupLocalSaveToGoogle(activity, shortcut)
                }
                statusSink.show(activity.getString(R.string.preloader_syncing_cloud))
                CloudSyncHelper.uploadCloudSaves(activity, shortcut)
                statusSink.show(activity.getString(R.string.preloader_initializing))
            }
        }
    }

    /** Back up the local GOG save before a "Use Cloud" download overwrites it — mirrors to Google Play Games (no-op when not signed in). Best-effort; never blocks the launch path. */
    private fun backupLocalSaveToGoogle(
        activity: Activity,
        shortcut: Shortcut,
    ) {
        val gameId =
            shortcut.getExtra("gog_id").ifEmpty { shortcut.getExtra("app_id") }.takeIf { it.isNotEmpty() } ?: return
        val gameName = shortcut.name ?: "Unknown"
        try {
            val result =
                runBlocking(Dispatchers.IO) {
                    GameSaveBackupManager.backupSaveToGoogle(
                        activity = activity,
                        gameSource = GameSaveBackupManager.GameSource.GOG,
                        gameId = gameId,
                        gameName = gameName,
                        origin = GameSaveBackupManager.BackupOrigin.LOCAL,
                        authMode = GoogleAuthMode.RESUME,
                    )
                }
            Timber.tag("GogLaunchCloudSync").i("Pre-overwrite GOG local backup: %s", result.message)
        } catch (e: Exception) {
            Timber.tag("GogLaunchCloudSync").w(e, "Failed to back up GOG local save before Use-Cloud")
        }
    }
}
