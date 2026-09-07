package com.winlator.cmod.feature.steamcloudsync

import android.app.Activity
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.feature.sync.google.GameSaveBackupManager
import com.winlator.cmod.feature.sync.google.GoogleAuthMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber

object SteamCloudConflictBackup {
    fun backupDiscardedSave(
        activity: Activity,
        shortcut: Shortcut?,
        origin: GameSaveBackupManager.BackupOrigin,
    ) {
        if (shortcut == null) return
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
            Timber.tag("SteamCloudConflictBackup").i("Discarded Steam save backup: %s", result.message)
        } catch (e: Exception) {
            Timber.tag("SteamCloudConflictBackup").w(e, "Failed to back up discarded Steam save")
        }
    }
}
