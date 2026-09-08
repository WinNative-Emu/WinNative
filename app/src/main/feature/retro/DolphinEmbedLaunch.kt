package com.winlator.cmod.feature.retro

import android.content.Context
import android.content.Intent
import com.winlator.cmod.R
import com.winlator.cmod.runtime.container.Shortcut
import org.dolphinemu.dolphinemu.wn.DolphinEmulationActivity
import org.dolphinemu.dolphinemu.wn.DolphinNetplay

object DolphinEmbedLaunch {
    const val DRIVER_PREF = "wn.gc.driver"

    private fun syncCloudSaves(context: Context, shortcut: Shortcut) {
        if (context !is android.app.Activity) return
        if (shortcut.getExtra("cloud_sync_enabled", "1") == "0") return
        val system = RetroShortcuts.systemForShortcut(shortcut) ?: return
        val gameName = shortcut.getExtra("custom_name", shortcut.name)
        val stageKey = RetroSaveStates.cloudGameId(system.id, gameName)
        val cloudId =
            com.winlator.cmod.feature.sync.google.GameSaveBackupManager.customGameId(
                shortcut.container.id,
                shortcut.file.name,
            )
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
                    val localTs = DolphinCloudSync.localTimestamp(context, stageKey)
                    val mark = prefs.getLong("retro_cloud_mark_$cloudId", 0L)
                    val restore: suspend () -> Unit = {
                        val result =
                            com.winlator.cmod.feature.sync.google.GameSaveBackupManager.restoreFromGoogle(
                                context,
                                latest,
                                com.winlator.cmod.feature.sync.google.GameSaveBackupManager.GameSource.CUSTOM,
                                cloudId,
                                com.winlator.cmod.feature.sync.google.GoogleAuthMode.RESUME,
                                customSaveDir = DolphinCloudSync.stagingDir(context, stageKey),
                            )
                        if (result.success) {
                            DolphinCloudSync.applyStaged(context, stageKey)
                            prefs.edit().putLong("retro_cloud_mark_$cloudId", latest.timestampMs).apply()
                        }
                    }
                    if (localTs == 0L) {
                        restore()
                    } else if (latest.timestampMs > localTs + 120_000L && latest.timestampMs > mark) {
                        val useCloud = askCloudConflict(context, gameName)
                        if (useCloud) {
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
                .setTitle(activity.getString(R.string.retro_lr_cloud_save))
                .setMessage(activity.getString(R.string.retro_lr_cloud_conflict_message, gameName))
                .setCancelable(false)
                .setPositiveButton(activity.getString(R.string.retro_lr_use_cloud_save)) { _, _ ->
                    useCloud.set(true)
                    latch.countDown()
                }
                .setNegativeButton(activity.getString(R.string.retro_scr_keep_local_save)) { _, _ ->
                    latch.countDown()
                }
                .show()
        }
        latch.await()
        return useCloud.get()
    }

    private fun gcProcess(context: Context): android.app.ActivityManager.RunningAppProcessInfo? =
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull { it.processName.endsWith(":gc") }
        }.getOrNull()

    fun launch(context: Context, shortcut: Shortcut) {
        val romPath = RetroShortcuts.romPath(shortcut)
        val newTask = context !is android.app.Activity
        kotlin.concurrent.thread(name = "WnDolphinLaunch") {
            syncCloudSaves(context, shortcut)
            val intent = launchIntent(context, shortcut)
            if (newTask) intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val proc = gcProcess(context)
            if (proc != null) {
                val marker = java.io.File(context.filesDir, "dolphin-embed/.running_rom")
                val running = runCatching { marker.takeIf { it.isFile }?.readText() }.getOrNull()
                if (running != romPath) {
                    if (running != null) {
                        android.util.Log.i("WnDolphin", "killing running :gc pid=" + proc.pid)
                        android.os.Process.killProcess(proc.pid)
                    }
                    val deadline = android.os.SystemClock.uptimeMillis() + 12000
                    while (android.os.SystemClock.uptimeMillis() < deadline && gcProcess(context) != null) {
                        Thread.sleep(150)
                    }
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { context.startActivity(intent) }
            }
        }
    }

    fun launchIntent(
        context: Context,
        shortcut: Shortcut,
    ): Intent {
        val system = RetroShortcuts.systemForShortcut(shortcut)
        val sysId = system?.id ?: RetroSystems.GAMECUBE.id
        val vars = RetroShortcuts.resolvedCoreVariables(context, shortcut)
        applyGpuDriver(context, vars)
        vars["wn_touch"] =
            shortcut.getExtra(RetroShortcuts.KEY_TOUCH_CONTROLS).ifEmpty {
                if (RetroDefaults.touchControls(context, sysId)) "1" else "0"
            }
        vars["wn_audio"] =
            shortcut.getExtra(RetroShortcuts.KEY_AUDIO).ifEmpty {
                if (RetroDefaults.audio(context, sysId)) "1" else "0"
            }
        vars["wn_hud"] = if (RetroHudSupport.hudEnabled(context, shortcut, sysId)) "1" else "0"
        vars["wn_adaptive"] =
            shortcut.getExtra(RetroShortcuts.KEY_ADAPTIVE_STICKS).ifEmpty {
                if (RetroDefaults.adaptiveSticks(context, sysId)) "1" else "0"
            }
        val netplayArmed = RetroDefaults.netplayEnabled(context, sysId)
        val netplayHost = RetroDefaults.netplayHostMode(context, sysId)
        val netplayTraversal = RetroDefaults.netplayTraversal(context, sysId)
        val netplayAddress = RetroDefaults.netplayHost(context, sysId)
        val netplayHostCode = RetroDefaults.netplayHostCode(context, sysId)
        val netplayPort = RetroDefaults.netplayPort(context, sysId)
        val netplayNick = RetroDefaults.netplayPlayerName(context)
        if (netplayArmed) RetroDefaults.setNetplayEnabled(context, sysId, false)
        return Intent(context, DolphinEmulationActivity::class.java).apply {
            putExtra(DolphinEmulationActivity.EXTRA_ROM_PATH, RetroShortcuts.romPath(shortcut))
            putExtra(DolphinEmulationActivity.EXTRA_SYSTEM_ID, system?.id)
            putExtra(
                DolphinEmulationActivity.EXTRA_GAME_NAME,
                shortcut.getExtra("custom_name", shortcut.name),
            )
            putExtra(DolphinEmulationActivity.EXTRA_SHORTCUT_PATH, shortcut.file.absolutePath)
            putExtra(DolphinEmulationActivity.EXTRA_CONTAINER_ID, shortcut.container.id)
            putExtra(DolphinEmulationActivity.EXTRA_VARIABLES, vars)
            if (netplayArmed) {
                putExtra(DolphinNetplay.EXTRA_ENABLE, true)
                putExtra(DolphinNetplay.EXTRA_HOST, netplayHost)
                putExtra(DolphinNetplay.EXTRA_TRAVERSAL, netplayTraversal)
                putExtra(DolphinNetplay.EXTRA_ADDRESS, netplayAddress)
                putExtra(DolphinNetplay.EXTRA_HOST_CODE, netplayHostCode)
                putExtra(DolphinNetplay.EXTRA_PORT, netplayPort)
                putExtra(DolphinNetplay.EXTRA_NICKNAME, netplayNick)
            }
            if (RetroAchievementsManager.isEnabled(context) && RetroAchievementsManager.isLoggedIn(context)) {
                putExtra(DolphinEmulationActivity.EXTRA_RA_ENABLED, true)
                putExtra(DolphinEmulationActivity.EXTRA_RA_USER, RetroAchievementsManager.username(context) ?: "")
                putExtra(DolphinEmulationActivity.EXTRA_RA_TOKEN, RetroAchievementsManager.apiToken(context) ?: "")
                putExtra(DolphinEmulationActivity.EXTRA_RA_HARDCORE, RetroAchievementsManager.isHardcorePreferred(context))
            }
            RetroFrameGen.writeInto(context, this, shortcut, sysId)
        }
    }

    private fun applyGpuDriver(
        context: Context,
        vars: HashMap<String, String>,
    ) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val driverId = (prefs.getString(DRIVER_PREF, "") ?: "").trim()
        if (driverId.isEmpty() || driverId.equals("system", ignoreCase = true)) return
        val driver =
            com.armsx2.CustomDriver.listInstalled(context).firstOrNull { it.id == driverId }
                ?: return
        vars["dolphin_gpu_driver"] = driver.driverDir.absolutePath
        vars["dolphin_gpu_driver_lib"] = driver.libraryName
    }
}
