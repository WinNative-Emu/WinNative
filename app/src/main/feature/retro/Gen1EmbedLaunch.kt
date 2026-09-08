package com.winlator.cmod.feature.retro

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.winlator.cmod.runtime.container.Shortcut
import java.io.File
import java.security.MessageDigest

object Gen1EmbedLaunch {
    const val KEY_ENGINE_3D = "retro_engine_3d"

    const val VOXEL_MOD_ID = "DRAMATIC_SHAPE"

    private val COMPATIBLE = mapOf(
        "ea9bcae617fdf159b045185467ae58b2e4a48b9a" to "red",
        "d7037c83e1ae5b39bde3c30787637ba1d4c48ce2" to "blue",
        "cc7d03262ebfaf2f06772c1a480c7d9d5f4a38e1" to "yellow",
    )

    fun versionForRom(rom: File): String? {
        if (!rom.isFile || rom.length() != 1024L * 1024L) return null
        val digest = MessageDigest.getInstance("SHA-1")
        rom.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val sha1 = digest.digest().joinToString("") { "%02x".format(it) }
        return COMPATIBLE[sha1]
    }

    fun isCompatible(context: Context, shortcut: Shortcut): Boolean =
        Gen1EngineActivity.isInstalled(context) &&
            versionForRom(File(RetroShortcuts.romPath(shortcut))) != null

    fun isEnabled(shortcut: Shortcut): Boolean =
        shortcut.getExtra(KEY_ENGINE_3D) == "1"

    fun shouldLaunch(context: Context, shortcut: Shortcut): Boolean =
        isEnabled(shortcut) && isCompatible(context, shortcut)

    fun launch(context: Context, shortcut: Shortcut) {
        val intent = launchIntent(context, shortcut) ?: return
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun launchIntentIfSupported(context: Context, shortcut: Shortcut): Intent? {
        if (!Gen1EngineActivity.isInstalled(context)) return null
        val intent = launchIntent(context, shortcut) ?: return null
        prepareLaunch(context, shortcut, intent.getStringExtra(Gen1EngineActivity.EXTRA_VERSION).orEmpty())
        return intent
    }

    private fun syncCloudSaves(context: Context, shortcut: Shortcut) {
        if (context !is android.app.Activity) return
        if (shortcut.getExtra("cloud_sync_enabled", "1") == "0") return
        val gameName = shortcut.getExtra("custom_name", shortcut.name)
        val cloudId = Gen1CloudSync.cloudId(shortcut)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(12_000L) {
                    val entries =
                        com.winlator.cmod.feature.sync.google.GameSaveBackupManager.listGoogleHistory(
                            context,
                            com.winlator.cmod.feature.sync.google.GameSaveBackupManager.GameSource.CUSTOM,
                            cloudId,
                            com.winlator.cmod.feature.sync.google.GoogleAuthMode.RESUME,
                        )
                    val latest = entries.maxByOrNull { it.timestampMs } ?: return@withTimeout
                    val localTs = Gen1CloudSync.localTimestamp(context, cloudId)
                    val mark = prefs.getLong("retro_cloud_mark_$cloudId", 0L)
                    val restore: suspend () -> Unit = {
                        val result =
                            com.winlator.cmod.feature.sync.google.GameSaveBackupManager.restoreFromGoogle(
                                context,
                                latest,
                                com.winlator.cmod.feature.sync.google.GameSaveBackupManager.GameSource.CUSTOM,
                                cloudId,
                                com.winlator.cmod.feature.sync.google.GoogleAuthMode.RESUME,
                                customSaveDir = Gen1CloudSync.stagingDir(context, cloudId),
                            )
                        if (result.success) {
                            Gen1CloudSync.applyStaged(context, cloudId)
                            prefs.edit().putLong("retro_cloud_mark_$cloudId", latest.timestampMs).apply()
                        }
                    }
                    if (localTs == 0L) {
                        restore()
                    } else if (latest.timestampMs > localTs + 120_000L && latest.timestampMs > mark) {
                        if (askCloudConflict(context, gameName)) {
                            restore()
                        } else {
                            prefs.edit().putLong("retro_cloud_mark_$cloudId", latest.timestampMs).apply()
                        }
                    }
                }
            }
        }
    }

    private fun askCloudConflict(activity: android.app.Activity, gameName: String): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        val useCloud = java.util.concurrent.atomic.AtomicBoolean(false)
        activity.runOnUiThread {
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(com.winlator.cmod.R.string.retro_lr_cloud_save))
                .setMessage(
                    activity.getString(com.winlator.cmod.R.string.retro_lr_cloud_conflict_message, gameName),
                )
                .setCancelable(false)
                .setPositiveButton(activity.getString(com.winlator.cmod.R.string.retro_lr_use_cloud_save)) { _, _ ->
                    useCloud.set(true)
                    latch.countDown()
                }
                .setNegativeButton(activity.getString(com.winlator.cmod.R.string.retro_scr_keep_local_save)) { _, _ ->
                    latch.countDown()
                }
                .show()
        }
        latch.await()
        return useCloud.get()
    }

    fun prepareLaunch(context: Context, shortcut: Shortcut, version: String) {
        val cloudId = Gen1CloudSync.cloudId(shortcut)
        Gen1CloudSync.rememberVersion(context, cloudId, version)
        Gen1CloudSync.applyRestoreIfPending(context, cloudId)
        syncCloudSaves(context, shortcut)
    }

    fun launchIntent(context: Context, shortcut: Shortcut): Intent? {
        val rom = File(RetroShortcuts.romPath(shortcut))
        val version = versionForRom(rom) ?: return null

        return Intent(context, Gen1EngineActivity::class.java).apply {
            data = Uri.fromFile(Gen1EngineActivity.gameArchive(context))
            putExtra(Gen1EngineActivity.EXTRA_ROM_PATH, rom.absolutePath)
            putExtra(Gen1EngineActivity.EXTRA_VERSION, version)
            putExtra(
                Gen1EngineActivity.EXTRA_GAME_NAME,
                shortcut.getExtra("custom_name", shortcut.name),
            )
            putExtra(Gen1EngineActivity.EXTRA_SHORTCUT_PATH, shortcut.file.absolutePath)
            putExtra(
                Gen1EngineActivity.EXTRA_ARTWORK_PATH,
                shortcut.getExtra("customCoverArtPath"),
            )
            putExtra(
                Gen1EngineActivity.EXTRA_ENGINE_VARS,
                Gen1EngineSettings.resolve(context, shortcut),
            )
            RetroFrameGen.writeInto(
                context,
                this,
                shortcut,
                RetroShortcuts.systemForShortcut(shortcut)?.id,
            )
        }
    }
}
