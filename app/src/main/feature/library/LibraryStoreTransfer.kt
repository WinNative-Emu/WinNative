package com.winlator.cmod.feature.library

import android.content.Context
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.feature.stores.common.InstallOwnership
import com.winlator.cmod.feature.stores.common.InstallStore
import com.winlator.cmod.feature.stores.steam.data.AppInfo
import com.winlator.cmod.feature.stores.steam.enums.Marker
import com.winlator.cmod.feature.stores.steam.utils.MarkerUtils
import timber.log.Timber
import java.io.File

object LibraryStoreTransfer {
    suspend fun switchStore(
        context: Context,
        installPath: String,
        from: LibraryStoreOption?,
        to: LibraryStoreOption,
    ): Boolean {
        val preferenceKey =
            LibraryStoreLinks
                .pathKey(installPath)
                .ifEmpty { LibraryStoreLinks.titleKey(to.title) }
        LibraryStoreLinks.setPreferredStore(context, preferenceKey, to.store)

        if (from != null && from.store == to.store) return true

        val dir = installPath.trim()
        if (dir.isEmpty() || !File(dir).isDirectory) return true

        return runCatching {
            InstallOwnership.claim(dir, to.store)
            if (!MarkerUtils.hasMarker(dir, Marker.DOWNLOAD_COMPLETE_MARKER)) {
                MarkerUtils.addMarker(dir, Marker.DOWNLOAD_COMPLETE_MARKER)
            }
            adoptInstall(to, dir)
            if (from != null) releaseInstall(from, dir)
            true
        }.getOrElse { e ->
            Timber.e(e, "Failed to move install at %s to %s", dir, to.store.id)
            false
        }
    }

    private suspend fun adoptInstall(
        target: LibraryStoreOption,
        dir: String,
    ) {
        val db = PluviaDatabase.getInstance()
        when (target.store) {
            InstallStore.STEAM -> {
                val appId = target.storeGameId.toIntOrNull() ?: return
                db.steamAppDao().findApp(appId)?.let { app ->
                    db.steamAppDao().update(app.copy(installDir = dir))
                }
                val existing = db.appInfoDao().get(appId)
                if (existing != null) {
                    db.appInfoDao().update(existing.copy(isDownloaded = true, installPath = dir))
                } else {
                    db.appInfoDao().insert(AppInfo(id = appId, isDownloaded = true, installPath = dir))
                }
            }

            InstallStore.EPIC -> {
                val appId = target.storeGameId.toIntOrNull() ?: return
                db.epicGameDao().getById(appId)?.let { game ->
                    db.epicGameDao().update(game.copy(isInstalled = true, installPath = dir))
                }
            }

            InstallStore.GOG -> {
                db.gogGameDao().getById(target.storeGameId)?.let { game ->
                    db.gogGameDao().update(game.copy(isInstalled = true, installPath = dir))
                }
            }

            InstallStore.ITCH -> Unit
        }
    }

    private suspend fun releaseInstall(
        source: LibraryStoreOption,
        dir: String,
    ) {
        val db = PluviaDatabase.getInstance()
        when (source.store) {
            InstallStore.STEAM -> {
                val appId = source.storeGameId.toIntOrNull() ?: return
                db.appInfoDao().get(appId)?.let { info ->
                    db.appInfoDao().update(info.copy(isDownloaded = false))
                }
                db.steamAppDao().findApp(appId)?.let { app ->
                    if (app.installDir.equals(dir, ignoreCase = true)) {
                        db.steamAppDao().update(app.copy(installDir = ""))
                    }
                }
            }

            InstallStore.EPIC -> {
                val appId = source.storeGameId.toIntOrNull() ?: return
                db.epicGameDao().getById(appId)?.let { game ->
                    db.epicGameDao().update(game.copy(isInstalled = false))
                }
            }

            InstallStore.GOG -> {
                db.gogGameDao().getById(source.storeGameId)?.let { game ->
                    db.gogGameDao().update(game.copy(isInstalled = false))
                }
            }

            InstallStore.ITCH -> Unit
        }
    }
}
