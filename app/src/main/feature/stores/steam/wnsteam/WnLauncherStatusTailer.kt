package com.winlator.cmod.feature.stores.steam.wnsteam

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.winlator.cmod.R
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

class WnLauncherStatusTailer(
    context: Context,
    private val logFile: File,
    private val gameDisplayName: String,
    private val pollIntervalMs: Long = 200L,
    private val onPhase: (phaseText: String) -> Unit,
    private val onLaunchComplete: (() -> Unit)? = null,
    private val onLaunchFailed: ((reason: String) -> Unit)? = null,
    private val onBlocked: ((kind: String, blockingAppId: Int, pid: Int) -> Unit)? = null,
    private val onInsecureLaunch: (() -> Unit)? = null,
    private val onCloudConflict: ((appId: Int, localTime: Long, remoteTime: Long) -> Unit)? = null,
) {
    private val running = AtomicBoolean(false)
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null
    @Volatile private var lastEmitted: String = ""
    @Volatile private var launchAppDispatchedAt: Long = 0L
    @Volatile private var fileExistedAtStart: Boolean = false
    @Volatile private var launchCompleteSignaled: Boolean = false
    @Volatile private var directExeMode: Boolean = false
    @Volatile private var insecureSignaled: Boolean = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        fileExistedAtStart = logFile.exists()
        launchCompleteSignaled = false
        android.util.Log.i(TAG, "start: path=" + logFile.absolutePath
                + " exists=" + fileExistedAtStart
                + " size=" + (if (fileExistedAtStart) logFile.length() else -1L)
                + " canRead=" + logFile.canRead())
        thread = Thread({ tailLoop() }, "WnLauncherStatusTailer").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun tailLoop() {
        var lastOffset = 0L
        var openedOnce = false
        var iter = 0
        var totalLinesRead = 0
        android.util.Log.i(TAG, "tailLoop: entered, polling every ${pollIntervalMs}ms")
        while (running.get()) {
            iter++
            try {
                if (!logFile.exists()) {
                    if (iter % 25 == 1) {
                        android.util.Log.i(TAG, "tailLoop iter=$iter: file does not yet exist at ${logFile.absolutePath}")
                    }
                    Thread.sleep(pollIntervalMs)
                    continue
                }
                var linesThisIter = 0
                RandomAccessFile(logFile, "r").use { raf ->
                    val len = raf.length()
                    if (!openedOnce) {
                        openedOnce = true
                        if (fileExistedAtStart) {
                            lastOffset = len
                            android.util.Log.i(TAG, "tailLoop: first read; file len=$len — seeking to end (skipping any stale content from previous launch); waiting for launcher to truncate + write new content")
                        } else {
                            lastOffset = 0L
                            android.util.Log.i(TAG, "tailLoop: first read on freshly created log; file len=$len — reading from start")
                        }
                    } else if (len < lastOffset) {
                        android.util.Log.i(TAG, "tailLoop iter=$iter: file shrank from $lastOffset to $len bytes — launcher truncated, resetting offset")
                        lastOffset = 0L
                    }
                    raf.seek(lastOffset)
                    while (true) {
                        val line = raf.readLine() ?: break
                        linesThisIter++
                        totalLinesRead++
                        consumeLine(line)
                    }
                    lastOffset = raf.filePointer
                }
                if (linesThisIter > 0) {
                    android.util.Log.i(TAG, "tailLoop iter=$iter: read $linesThisIter new line(s), totalRead=$totalLinesRead, offset=$lastOffset")
                }
                watchdogTick()
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                android.util.Log.e(TAG, "tail iteration failed", e)
            }
            try {
                Thread.sleep(pollIntervalMs)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        android.util.Log.i(TAG, "tailLoop: exiting (running=${running.get()}, totalLinesRead=$totalLinesRead)")
    }

    private fun consumeLine(line: String) {
        if (!line.contains("[wn-launcher]")) return
        if (line.contains("BLOCKED: kind=")) {
            val kind = field(line, "kind=") ?: return
            val blocking = field(line, "blocking=")?.toIntOrNull() ?: 0
            val pid = field(line, "pid=")?.toIntOrNull() ?: 0
            android.util.Log.w(TAG, "launch blocked: kind=$kind blocking=$blocking pid=$pid")
            launchAppDispatchedAt = 0L
            main.post { onBlocked?.invoke(kind, blocking, pid) }
            return
        }
        if (line.contains("CLOUD-CONFLICT: appid=")) {
            val appId = field(line, "appid=")?.toIntOrNull() ?: 0
            val localTime = field(line, "local=")?.toLongOrNull() ?: 0L
            val remoteTime = field(line, "remote=")?.toLongOrNull() ?: 0L
            android.util.Log.w(TAG, "cloud conflict: appId=$appId local=$localTime remote=$remoteTime")
            main.post { onCloudConflict?.invoke(appId, localTime, remoteTime) }
            return
        }
        if (line.contains("direct-exe mode:")) directExeMode = true
        val startedViaFallback = line.contains("game process started pid=")
                && line.contains("CreateProcess fallback")
        if (startedViaFallback && !directExeMode && !insecureSignaled) {
            insecureSignaled = true
            android.util.Log.w(TAG, "game started via CreateProcess fallback — session is NOT VAC-secure")
            main.post { onInsecureLaunch?.invoke() }
        }
        val isWatchingForExit = line.contains("watching \"") && line.contains("for exit")
        val isTerminal = (line.contains("is running") && line.contains("LaunchApp"))
                || isWatchingForExit
                || startedViaFallback
        val isFatal = line.contains("LoadLibrary(") && line.contains("FAILED after all strategies")
        val isLaunchAppDispatched = line.contains("IClientAppManager.LaunchApp(appId=")
        val isCreateProcessFallback = line.contains("LaunchApp dispatched")
                && line.contains("never appeared")
                && line.contains("falling back to CreateProcess")
        val phase = phaseFor(line)
        if (phase != null && phase != lastEmitted) {
            emitPhase(phase, line)
        }
        if (isLaunchAppDispatched) launchAppDispatchedAt = System.currentTimeMillis()
        if (line.contains("LaunchApp: still waiting for") && launchAppDispatchedAt != 0L) {
            launchAppDispatchedAt = System.currentTimeMillis()
        }
        if (isTerminal) {
            if (launchCompleteSignaled) return
            launchCompleteSignaled = true
            android.util.Log.i(TAG, "terminal phase (LaunchApp is running) — signaling launch complete")
            // Disarm the watchdog: the game spawned successfully, so the 30s
            // post-dispatch timeout would otherwise fire mid-play and kill the
            // activity with a spurious onLaunchFailed.
            launchAppDispatchedAt = 0L
            main.post { onLaunchComplete?.invoke() }
        } else if (isFatal) {
            android.util.Log.w(TAG, "fatal phase (launcher LoadLibrary failed) — signaling launch failure")
            main.post { onLaunchFailed?.invoke(appContext.getString(R.string.preloader_steam_launcher_start_failed)) }
        } else if (isCreateProcessFallback) {
            // Keep the UI on "Launching <game>" through the fallback; disarm the watchdog.
            android.util.Log.w(TAG, "LaunchApp exhausted retries — launcher will try CreateProcess fallback (UI stays on Launching)")
            launchAppDispatchedAt = 0L
        }
    }

    private fun field(line: String, key: String): String? {
        val i = line.indexOf(key)
        if (i < 0) return null
        val rest = line.substring(i + key.length)
        val end = rest.indexOfFirst { it == ' ' || it == '\t' }
        return if (end < 0) rest.trim() else rest.substring(0, end).trim()
    }

    private fun watchdogTick() {
        val dispatchedAt = launchAppDispatchedAt
        if (dispatchedAt == 0L) return  // disarmed on spawn / fallback
        if (System.currentTimeMillis() - dispatchedAt > LAUNCH_APP_WATCHDOG_MS) {
            android.util.Log.w(TAG, "watchdog: ${LAUNCH_APP_WATCHDOG_MS}ms elapsed after LaunchApp with no terminal — assuming launch failed")
            launchAppDispatchedAt = 0L
            main.post { onLaunchFailed?.invoke(appContext.getString(R.string.preloader_steam_launcher_game_never_started)) }
        }
    }

    private fun emitPhase(phase: String, line: String) {
        lastEmitted = phase
        android.util.Log.i(TAG, "phase change: \"$phase\" (from line: ${line.take(80)})")
        main.post { onPhase(phase) }
    }

    private fun phaseFor(line: String): String? = when {
        line.contains("in-process Steam launcher starting") -> appContext.getString(R.string.preloader_starting_steam_launcher)
        line.contains("steamclient64.dll loaded") -> appContext.getString(R.string.preloader_loading_steam_client)
        line.contains("Steam_CreateGlobalUser OK") -> appContext.getString(R.string.preloader_connecting_to_steam)
        line.contains("LogOn(") && line.contains("EResult=1") -> appContext.getString(R.string.preloader_signing_in_to_steam)
        line.contains("callback 101 SteamServersConnected") -> appContext.getString(R.string.preloader_fetching_game_info)
        line.contains("Steam_BLoggedOn=true") -> appContext.getString(R.string.preloader_steam_ready)
        line.contains("RequestAppInfoUpdate(appId=") -> appContext.getString(R.string.preloader_updating_game_info)
        line.contains("GetAppInstallState(appId=") -> appContext.getString(R.string.preloader_verifying_install)
        line.contains("installscript: ") && line.contains("script(s) found") ->
            appContext.getString(R.string.preloader_checking_install_scripts)
        line.contains("installscript: running \"") -> phaseForInstallScript(line)
        line.contains("redist install: ") -> phaseForRedistInstall(line)
        line.contains("redist scan: scanning") -> appContext.getString(R.string.preloader_scanning_redists)
        line.contains("installing redistributable:") -> phaseForInstallingRedist(line)
        line.contains("redist scan: installed") -> appContext.getString(R.string.preloader_redists_ready)
        line.contains("redist scan: ") && line.contains(" of ") -> appContext.getString(R.string.preloader_redists_ready)
        line.contains("redist scan: 0 *.exe installers") -> appContext.getString(R.string.preloader_no_redists)
        line.contains("redist scan: no _CommonRedist") -> appContext.getString(R.string.preloader_no_redists)
        line.contains("steamservice: post-start state=4") -> appContext.getString(R.string.preloader_steam_service_running)
        line.contains("cloud: RunAutoCloudOnAppLaunch") -> appContext.getString(R.string.preloader_syncing_cloud_saves)
        line.contains("IClientAppManager.LaunchApp(appId=") -> appContext.getString(R.string.preloader_launching_game, gameDisplayName)
        line.contains("LoadLibrary(") && line.contains("FAILED after all strategies") ->
            appContext.getString(R.string.preloader_steam_launcher_failed_restaging)
        else -> null
    }

    private fun phaseForInstallScript(line: String): String {
        val tail = line.substringAfter(" -> ", "").substringAfterLast('\\')
        val name = INSTALLER_NAME.find(tail)?.groupValues?.get(1)?.trim().orEmpty()
        return if (name.isNotEmpty()) {
            appContext.getString(R.string.preloader_installing_named_redist, name)
        } else {
            appContext.getString(R.string.preloader_installing_redist_single)
        }
    }

    private fun phaseForRedistInstall(line: String): String? {
        if (line.contains(" exit=") || line.contains("timed out") || line.contains("timeout")
            || line.contains("CreateProcess failed") || line.contains("satisfied")) return null
        val tail = line.substringAfter("redist install: ", "")
        val name = INSTALLER_NAME.find(tail)?.groupValues?.get(1)?.trim().orEmpty()
        return if (name.isNotEmpty()) {
            appContext.getString(R.string.preloader_installing_named_redist, name)
        } else {
            appContext.getString(R.string.preloader_installing_redist_single)
        }
    }

    private fun phaseForInstallingRedist(line: String): String {
        val marker = "installing redistributable:"
        val start = line.indexOf(marker)
        if (start < 0) return appContext.getString(R.string.preloader_installing_redist_single)
        val rest = line.substring(start + marker.length).trim()
        val name = rest.substringBefore(" (").trim()
        val ratio = rest.substringAfter("(", "").substringBefore(",", "").trim()
        return if (name.isNotEmpty() && ratio.contains("/")) {
            appContext.getString(R.string.preloader_installing_named_redist_progress, name, ratio)
        } else if (name.isNotEmpty()) {
            appContext.getString(R.string.preloader_installing_named_redist, name)
        } else {
            appContext.getString(R.string.preloader_installing_redist_single)
        }
    }

    companion object {
        private const val TAG = "WnLauncherTailer"
        private const val LAUNCH_APP_WATCHDOG_MS = 35_000L
        private val INSTALLER_NAME = Regex("""^(.+?)\.(?:cmd|bat|exe|msi)\b""", RegexOption.IGNORE_CASE)
    }
}
