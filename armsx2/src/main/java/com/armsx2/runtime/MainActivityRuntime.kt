package com.armsx2.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.armsx2.BuildConfig
import com.armsx2.EmuState
import com.armsx2.FilenameParser
import com.armsx2.GameInfo
import com.armsx2.events.TestResult
import com.armsx2.input.ControllerMappings
import com.armsx2.runtime.MainActivityRuntime.Companion.internalBiosDir
import com.armsx2.runtime.MainActivityRuntime.Companion.romsDirs
import com.armsx2.ui.InGameOverlay
import com.armsx2.ui.WindowImpl
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kr.co.iefriends.pcsx2.AssetFiles
import kr.co.iefriends.pcsx2.NativeApp
import org.libsdl.app.HIDDeviceManager
import org.libsdl.app.SDLControllerManager
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import androidx.core.net.toUri
import androidx.core.content.edit

private const val LIGHT_NAVIGATION_BAR_SCRIM = 0x04000000
private const val DARK_NAVIGATION_BAR_SCRIM = 0x0A000000

private const val STICK_DEAD = 0.15f
private const val TRIGGER_DEAD = 0.06f
private const val STICK_DIGITAL_THRESHOLD = 0.5f
private const val STICK_CROSS_GATE = 0.15f
private const val UI_NAV_DEAD = 0.20f
private const val UI_NAV_RELEASE_DEAD = 0.06f
private const val UI_HAT_DEAD = 0.50f
private const val UI_NAV_DOMINANCE = 1.35f
private const val UI_OVERLAY_RELEASE_MS = 80L
private const val UI_KEY_AXIS_SUPPRESS_MS = 220L
private const val NAV_REPEAT_INITIAL_MS = 340L
private const val NAV_REPEAT_INTERVAL_MS = 110L

private const val COMBO_MIN_GAP_MS = 40L

val codeGenTests = mutableStateOf("")
val patchTests = mutableStateOf("")
val vuJitTests = mutableStateOf("")
val eeJitTests = mutableStateOf("")
val vifTests = mutableStateOf("")
val eeSeqTests = mutableStateOf("")

open class MainActivityRuntime : ComponentActivity() {
    companion object {
        fun nativeGetRegionForSerial(serial: String): String = NativeApp.getRegionForSerial(serial)

        fun nativeSetRumbleEnabled(on: Boolean) {
            NativeApp.sRumbleEnabled = on
        }

        fun nativeSetMultitap(port: Int, on: Boolean) = NativeApp.setMultitap(port, on)

        fun nativeClearAchievementsHostOverride() = NativeApp.clearAchievementsHostOverride()

        var instance: MainActivityRuntime? = null
        lateinit var prefs: SharedPreferences
        val setupComplete = mutableStateOf(false)
        val setupRecoveryNeeded = mutableStateOf(false)
        val setupEditorVisible = mutableStateOf(false)
        val nativeReady = mutableStateOf(false)
        val systemDir = mutableStateOf<String?>(null)
        val bios = mutableStateOf<String?>(null)
        val biosDir = mutableStateOf<String?>(null)

        val romsDirs = mutableStateOf<List<String>>(emptyList())

        fun setRomsDirs(dirs: List<String>) {
            romsDirs.value = dirs
            val arr = org.json.JSONArray()
            for (d in dirs) arr.put(d)
            prefs.edit {
                putString("romsDirs", arr.toString())
                    .remove("roms")
            }
        }

        val renderer = mutableStateOf("auto")
        val upscale = androidx.compose.runtime.mutableFloatStateOf(1.0f)

        val customDriverId = mutableStateOf<String?>(null)

        private val eDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

        private val eScope = CoroutineScope(
            eDispatcher + SupervisorJob() +
                CoroutineExceptionHandler { _, t ->
                    android.util.Log.e("ARMSX2", "emulator task failed", t)
                },
        )

        private val nativeReadyLock = Any()
        private var nativeInitialized = false
        private val nativeReadyWaiters = ArrayList<() -> Unit>()

        @JvmStatic
        fun runWhenNativeReady(block: () -> Unit) {
            val deferred = synchronized(nativeReadyLock) {
                if (nativeInitialized) {
                    false
                } else {
                    nativeReadyWaiters.add(block)
                    true
                }
            }
            if (!deferred) Thread { runCatching { block() } }.start()
        }

        @JvmStatic
        fun isNativeReady(): Boolean = synchronized(nativeReadyLock) { nativeInitialized }

        private fun flushNativeReadyWaiters() {
            val pending = synchronized(nativeReadyLock) {
                nativeInitialized = true
                val copy = ArrayList(nativeReadyWaiters)
                nativeReadyWaiters.clear()
                copy
            }
            for (waiter in pending) {
                runCatching { waiter() }
                    .onFailure { android.util.Log.e("ARMSX2", "deferred native task failed", it) }
            }
        }

        fun systemDirPosix(): String? {
            val v = systemDir.value ?: return null
            return if (v.startsWith("content://")) resolveTreeUriToPosix(v) else v
        }

        fun inputProfilesDir(): File? {
            val root = systemDirPosix()
                ?: instance?.applicationContext?.getExternalFilesDir(null)?.absolutePath
                ?: return null
            val dir = File(root, "inputprofiles")
            if (!dir.exists()) runCatching { dir.mkdirs() }
            return if (dir.isDirectory) dir else null
        }

        fun sdCardDataDir(context: Context): String? {
            val dirs = context.getExternalFilesDirs(null)
            for (i in 1 until dirs.size) {
                val d = dirs[i] ?: continue
                return d.absolutePath
            }
            return null
        }

        fun biosFolderPosix(): String? =
            bios.value?.takeIf { it.isNotEmpty() }?.let { File(it).parent }

        fun internalBiosDir(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.dataDir, "bios")

        fun resolveTreeUriToPosix(uriString: String?): String? {
            val raw = uriString ?: return null
            val uri = try {
                raw.toUri() } catch (_: Exception) { return null }
            val docId = try {
                android.provider.DocumentsContract.getTreeDocumentId(uri)
            } catch (_: Exception) { null } ?: return null
            val parts = docId.split(":", limit = 2)
            if (parts.size != 2) return null
            val (volumeId, relPath) = parts
            return when (volumeId) {
                "primary" -> "/storage/emulated/0/$relPath"
                else -> "/storage/$volumeId/$relPath"
            }
        }

        fun validateSystemDirWritable(posixPath: String): Boolean {
            return try {
                val dir = File(posixPath)
                if (!dir.exists() && !dir.mkdirs()) return false
                if (!dir.isDirectory) return false
                val probe = File(dir, ".armsx2-write-probe")
                val ok = probe.createNewFile()
                if (ok) probe.delete()
                ok
            } catch (_: Exception) {
                false
            }
        }

        val surface = mutableStateOf<EmulationSurface?>(null)

        @JvmField
        val eState = mutableStateOf(EmuState.STOPPED)

        val currentSaveSlot = androidx.compose.runtime.mutableIntStateOf(0)

        @Volatile var fastForwardToggleActive = false

        @Volatile var slowDownToggleActive = false

        val gyroActive = mutableStateOf(true)

        @Volatile var usbKeyboardActive = false

        val currentGame = mutableStateOf<GameInfo?>(null)

        val focusRequester = FocusRequester()

        private var m_szGamefile = ""
        private val pendingExternalLaunch = mutableStateOf<String?>(null)
        private val pendingLaunch = mutableStateOf<Pair<String, GameInfo?>?>(null)

        fun onTestResults(result: TestResult) {
            when (result.name) {
                "VuJitTests" -> vuJitTests.value = "${result.passed}/${result.total}"
                "PatchTests" -> patchTests.value = "${result.passed}/${result.total}"
                "CodegenTests" -> codeGenTests.value = "${result.passed}/${result.total}"
                "EeJitTests" -> eeJitTests.value = "${result.passed}/${result.total}"
                "VifTests" -> vifTests.value = "${result.passed}/${result.total}"
                "EeSeqTests" -> eeSeqTests.value = "${result.passed}/${result.total}"
                else -> println("Test:${result.name}: ${result.passed}/${result.total}")
            }
        }

        fun invoke(task: suspend () -> Unit) {
            eScope.launch {
                task()
            }
        }

        private val vmLifecycleLock = Any()
        @Volatile private var vmStopInProgress = false
        @Volatile private var vmRestartAfterStop = false
        @Volatile private var vmRunLoopActive = false

        @Volatile var quitAfterStop = false
        @Volatile var launchedExternally = false

        private fun finishHostActivity() {
            val activity = instance ?: return
            if (com.armsx2.WinNativeHost.enabled() || !activity.isTaskRoot) {
                activity.finish()
            } else {
                activity.finishAndRemoveTask()
            }
        }

        private fun finishToLauncherIfRequested() {
            if (quitAfterStop) {
                quitAfterStop = false
                instance?.runOnUiThread { finishHostActivity() }
            }
        }

        @JvmStatic
        fun closeGame(saveAutosave: Boolean = false) {
            if (launchedExternally && prefs.getBoolean("ui.exitToLauncherExternal", true))
                quitAfterStop = true
            stop(saveAutosave = saveAutosave)
        }

        @JvmStatic
        fun exitApp() {
            if (eState.value == EmuState.STOPPED && !vmStopInProgress && !vmRunLoopActive) {
                instance?.runOnUiThread { finishHostActivity() }
            } else {
                quitAfterStop = true
                stop()
            }
        }

        @JvmStatic
        fun isVmStopInProgress(): Boolean = vmStopInProgress

        fun start() {
            synchronized(vmLifecycleLock) {
                if (vmStopInProgress || vmRunLoopActive || eState.value != EmuState.STOPPED) {
                    vmRestartAfterStop = true
                    return
                }
                vmRunLoopActive = true
            }

            invoke {
                try {
                    eState.value = EmuState.RUNNING
                    println("@@ANDROID_START_VM@@ kind=game path=${m_szGamefile.take(240)}")
                    com.armsx2.input.PadRouter.reset()
                    WindowImpl.showLibrary.value = false
                    WindowImpl.overlayVisible.value = false
                    WindowImpl.toolbarVisible.value = false
                    applyRendererPrefs()
                    NativeApp.runVMThread(m_szGamefile)
                } finally {
                    eState.value = EmuState.STOPPED
                    val restartNow = synchronized(vmLifecycleLock) {
                        vmRunLoopActive = false
                        vmStopInProgress = false
                        if (vmRestartAfterStop) {
                            vmRestartAfterStop = false
                            true
                        } else {
                            false
                        }
                    }
                    if (restartNow) {
                        start()
                    } else {
                        WindowImpl.toolbarVisible.value = true
                        WindowImpl.showLibrary.value = false
                        WindowImpl.overlayVisible.value = false
                        finishToLauncherIfRequested()
                    }
                }
            }
        }

        private fun connectedGamepadCount(): Int {
            var n = 0
            for (id in InputDevice.getDeviceIds()) {
                val dev = InputDevice.getDevice(id) ?: continue
                if (dev.isVirtual) continue
                val s = dev.sources
                if ((s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) n++
            }
            return n
        }

        private fun applyRendererPrefs() {
            instance?.applicationContext?.let { com.armsx2.WinNativeHost.applyBootSettings?.invoke(it) }
            var resolved = com.armsx2.config.ConfigStore.resolveForGame(currentGame.value?.settingsKey)
            currentGame.value?.serial?.takeIf { it.isNotBlank() }?.let { serial ->
                if (prefs.getBoolean("memcard.perGame", false) &&
                    resolved.memoryCardSlot1Filename.equals("mcd001.ps2", ignoreCase = true)) {
                    resolved = resolved.copy(
                        memoryCardSlot1Filename = "$serial.ps2",
                        memoryCardSlot1Enabled = true,
                    )
                }
            }
            run {
                val effectiveBios = resolved.biosFilename.takeIf { it.isNotBlank() }
                    ?: bios.value?.takeIf { it.isNotEmpty() }?.let { File(it).name }
                if (!effectiveBios.isNullOrBlank()) {
                    NativeApp.setSetting("Filenames", "BIOS", "string", effectiveBios)
                    NativeApp.commitSettings()
                }
            }
            upscale.value = resolved.upscaleFloat
            renderer.value = resolved.renderer
            NativeApp.renderUpscalemultiplier(upscale.value)
            val ctx = instance?.applicationContext
            val picked: com.armsx2.CustomDriver.InstalledDriver? =
                if (ctx != null) customDriverId.value?.let { id ->
                    com.armsx2.CustomDriver.listInstalled(ctx).firstOrNull { it.id == id }
                } else null
            if (ctx != null) com.armsx2.CustomDriver.applyToNative(ctx, picked)
            when {
                picked != null -> NativeApp.renderVulkan()
                renderer.value == "vulkan" -> NativeApp.renderVulkan()
                renderer.value == "opengl" -> NativeApp.renderOpenGL()
                renderer.value == "software" -> NativeApp.renderSoftware()
                else -> NativeApp.renderAuto()
            }
            val winNativeHost = com.armsx2.WinNativeHost.applyBootSettings != null
            if (winNativeHost) {
                val want = prefs.getInt("wn.ps2.aspect", 1).coerceIn(0, 3)
                val safe =
                    when (want) {
                        3 -> 3
                        2 -> 2
                        else -> 2
                    }
                resolved = resolved.copy(aspectRatio = safe)
            }
            resolved.applyTo()
            instance?.applicationContext?.let { com.armsx2.WinNativeHost.applyBootSettings?.invoke(it) }
            if (winNativeHost) {
                val want = prefs.getInt("wn.ps2.aspect", 1).coerceIn(0, 3)
                val safe =
                    when (want) {
                        3 -> 3
                        2 -> 2
                        else -> 2
                    }
                val name =
                    when (safe) {
                        3 -> "16:9"
                        else -> "4:3"
                    }
                runCatching {
                    NativeApp.setSetting("EmuCore/GS", "AspectRatio", "string", name)
                    NativeApp.setAspectRatio(safe)
                    NativeApp.commitSettings()
                }
            }
            usbKeyboardActive = resolved.usbKeyboard

            runCatching {
                NativeApp.setSetting("Pad1", "Deadzone", "float", "0")
                NativeApp.setSetting("Pad2", "Deadzone", "float", "0")
                val twoPads = connectedGamepadCount() >= 2
                NativeApp.setSetting("Pad2", "Type", "string", if (twoPads) "DualShock2" else "None")
                if (twoPads) {
                    NativeApp.setSetting("Pad2", "AxisScale", "float", "1.33")
                    NativeApp.setSetting("Pad2", "ButtonDeadzone", "float", "0")
                }
                if (ControllerMappings.multitapEnabled()) {
                    NativeApp.setSetting("Pad", "MultitapPort1", "bool", "true")
                    NativeApp.setSetting("Pad", "MultitapPort2", "bool", "true")
                    for (s in 2..8) {
                        NativeApp.setSetting("Pad$s", "Type", "string", "DualShock2")
                        NativeApp.setSetting("Pad$s", "Deadzone", "float", "0")
                        NativeApp.setSetting("Pad$s", "AxisScale", "float", "1.33")
                        NativeApp.setSetting("Pad$s", "ButtonDeadzone", "float", "0")
                    }
                }
            }

            runCatching {
                val netOn = prefs.getBoolean("wn.ps2.net.enable", true)
                NativeApp.setSetting("DEV9/Eth", "EthEnable", "bool", netOn.toString())
                if (netOn) {
                    NativeApp.setSetting("DEV9/Eth", "EthApi", "string", "Sockets")
                    NativeApp.setSetting("DEV9/Eth", "EthDevice", "string", (prefs.getString("wn.ps2.net.ethdevice", "Auto") ?: "Auto").ifBlank { "Auto" })
                    NativeApp.setSetting("DEV9/Eth", "InterceptDHCP", "bool", prefs.getBoolean("wn.ps2.net.dhcp", true).toString())
                    NativeApp.setSetting("DEV9/Eth", "AutoMask", "bool", "true")
                    NativeApp.setSetting("DEV9/Eth", "AutoGateway", "bool", "true")
                    val dnsMode = prefs.getString("wn.ps2.net.dnsmode", "Manual") ?: "Manual"
                    NativeApp.setSetting("DEV9/Eth", "ModeDNS1", "string", dnsMode)
                    NativeApp.setSetting("DEV9/Eth", "ModeDNS2", "string", "Auto")
                    NativeApp.setSetting("DEV9/Eth", "DNS1", "string", (prefs.getString("wn.ps2.net.dns1", "45.7.228.197") ?: "45.7.228.197").ifBlank { "45.7.228.197" })
                    NativeApp.setSetting("DEV9/Eth", "DNS2", "string", (prefs.getString("wn.ps2.net.dns2", "") ?: "").ifBlank { "0.0.0.0" })
                }
            }

            val limit = InGameOverlay.frameLimitOn.value
            NativeApp.setSetting("EmuCore/GS", "FrameLimitEnable", "bool", limit.toString())
            NativeApp.speedhackLimitermode(
                when {
                    fastForwardToggleActive -> 1
                    slowDownToggleActive -> 2
                    else -> if (limit) 0 else 3
                }
            )
        }

        fun launchGame(uri: String, info: GameInfo? = null, external: Boolean = false) {
            if (uri.isBlank()) {
                println("@@ANDROID_LAUNCH_REJECT@@ reason=blank_uri title=${info?.title ?: ""}")
                return
            }
            println(
                "@@ANDROID_LAUNCH_GAME@@ title=${info?.title ?: "<direct>"} " +
                    "uri=${uri.take(240)} state=${eState.value} runLoop=$vmRunLoopActive " +
                    "stopping=$vmStopInProgress nativeReady=${nativeReady.value}"
            )
            instance?.applicationContext?.let { applyAngleEnv(it) }
            if (!nativeReady.value) {
                println("@@ANDROID_LAUNCH_DEFER@@ nativeReady=false — queuing '${info?.title ?: uri.take(80)}'")
                pendingLaunch.value = uri to info
                return
            }
            currentGame.value = info
            launchedExternally = external
            pendingAutoLoadOnBoot = prefs.getBoolean("autoLoadOnBoot", false)
            m_szGamefile = uri
            synchronized(vmLifecycleLock) {
                if (eState.value != EmuState.STOPPED || vmStopInProgress || vmRunLoopActive) {
                    vmRestartAfterStop = true
                }
            }
            if (eState.value == EmuState.STOPPED && !vmStopInProgress && !vmRunLoopActive)
                start()
            else
                stop(restartAfterStop = true)
        }

        private fun launchPendingExternalGameIfReady() {
            if (!setupComplete.value || !nativeReady.value) return
            pendingLaunch.value?.let { (u, i) ->
                pendingLaunch.value = null
                launchGame(u, i)
                return
            }
            val queued = pendingExternalLaunch.value
            if (queued.isNullOrEmpty()) return
            pendingExternalLaunch.value = null
            launchGame(queued, null, external = true)
        }

        fun startBios() {
            currentGame.value = null
            m_szGamefile = ""
            val shouldStart = synchronized(vmLifecycleLock) {
                if (vmStopInProgress || vmRunLoopActive || eState.value != EmuState.STOPPED) {
                    vmRestartAfterStop = true
                    false
                } else {
                    vmRunLoopActive = true
                    true
                }
            }
            if (!shouldStart) {
                stop(restartAfterStop = true)
                return
            }
            invoke {
                try {
                    eState.value = EmuState.RUNNING
                    println("@@ANDROID_START_VM@@ kind=bios path=<empty>")
                    com.armsx2.input.PadRouter.reset()
                    applyRendererPrefs()
                    NativeApp.runVMThread(m_szGamefile)
                } finally {
                    eState.value = EmuState.STOPPED
                    val restartNow = synchronized(vmLifecycleLock) {
                        vmRunLoopActive = false
                        vmStopInProgress = false
                        if (vmRestartAfterStop) {
                            vmRestartAfterStop = false
                            true
                        } else {
                            false
                        }
                    }
                    if (restartNow) {
                        start()
                    }
                }
            }
        }

        private val vmControl = Executors.newSingleThreadExecutor { r ->
            Thread(r, "VMControl")
        }
        private val vmStopControl = Executors.newSingleThreadExecutor { r ->
            Thread(r, "VMStop")
        }

        fun pause() {
            if (vmStopInProgress)
                return
            vmControl.execute {
                if (!vmStopInProgress)
                    NativeApp.pause()
            }
        }

        fun pauseForOverlay() {
            if (vmStopInProgress)
                return
            NativeApp.pause()
        }

        fun resume() {
            if (vmStopInProgress)
                return
            vmControl.execute {
                if (!vmStopInProgress)
                    NativeApp.resume()
            }
        }

        fun stop(saveAutosave: Boolean = false, restartAfterStop: Boolean = false) {
            fastForwardToggleActive = false
            slowDownToggleActive = false
            gyroActive.value = true
            val nativeActive = runCatching { NativeApp.hasActiveVM() }.getOrDefault(false)
            val shouldStop = synchronized(vmLifecycleLock) {
                if (restartAfterStop)
                    vmRestartAfterStop = true
                else
                    vmRestartAfterStop = false

                if (vmStopInProgress) {
                    nativeActive
                } else if (eState.value == EmuState.STOPPED && !vmRunLoopActive && !nativeActive) {
                    false
                } else {
                    vmStopInProgress = true
                    true
                }
            }
            if (!shouldStop)
                return

            WindowImpl.overlayVisible.value = false
            WindowImpl.showLibrary.value = false
            val doAutosave = saveAutosave ||
                (!restartAfterStop &&
                    runCatching { prefs.getBoolean("autoSaveOnExit", false) }.getOrDefault(false))
            vmStopControl.execute {
                println("@@ANDROID_STOP_JAVA@@ begin saveAutosave=$doAutosave forced=$saveAutosave restart=$restartAfterStop")
                if (doAutosave)
                    NativeApp.saveAutosaveState()
                NativeApp.shutdown()
                println("@@ANDROID_STOP_JAVA@@ shutdown_return active=${NativeApp.hasActiveVM()} runLoop=$vmRunLoopActive state=${eState.value}")
                if (!vmRunLoopActive && (eState.value == EmuState.STOPPED || !NativeApp.hasActiveVM())) {
                    eState.value = EmuState.STOPPED
                    val restartNow = synchronized(vmLifecycleLock) {
                        vmStopInProgress = false
                        if (vmRestartAfterStop) {
                            vmRestartAfterStop = false
                            true
                        } else {
                            false
                        }
                    }
                    if (restartNow) {
                        start()
                    } else {
                        synchronized(vmLifecycleLock) {
                            WindowImpl.toolbarVisible.value = true
                            WindowImpl.showLibrary.value = false
                            WindowImpl.overlayVisible.value = false
                        }
                        currentGame.value = null
                        finishToLauncherIfRequested()
                    }
                }
            }
        }

        fun restart() {
            synchronized(vmLifecycleLock) {
                vmRestartAfterStop = true
            }
            if (eState.value == EmuState.STOPPED && !vmStopInProgress && !vmRunLoopActive)
                start()
            else
                stop(restartAfterStop = true)
        }

        fun promptSwapDisc() {
            val activity = instance ?: return
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { activity.swapDiscAction.launch(intent) }
        }

        fun applyAngleEnv(context: Context) {
            val settings = runCatching { com.armsx2.config.ConfigStore.loadGlobal() }.getOrNull()
            val eligible = settings?.useAngleOpenGL == true && settings.renderer == "opengl"
            val libDir = context.applicationInfo.nativeLibraryDir
            val egl = File(libDir, "libEGL_angle.so")
            val gles = File(libDir, "libGLESv2_angle.so")
            try {
                if (eligible && egl.exists() && gles.exists()) {
                    android.system.Os.setenv("ARMSX2_ANGLE_EGL_LIBRARY", egl.absolutePath, true)
                    android.system.Os.setenv("ARMSX2_ANGLE_GLES_LIBRARY", gles.absolutePath, true)
                    android.util.Log.i("ARMSX2", "ANGLE OpenGL enabled: ${egl.absolutePath}")
                } else {
                    runCatching { android.system.Os.unsetenv("ARMSX2_ANGLE_EGL_LIBRARY") }
                    runCatching { android.system.Os.unsetenv("ARMSX2_ANGLE_GLES_LIBRARY") }
                }
            } catch (e: Exception) {
                android.util.Log.e("ARMSX2", "applyAngleEnv failed: ${e.message}")
            }
        }

        @Volatile
        var pendingAutoLoadOnBoot = false

        @Volatile
        private var pendingSlotLoadOnBoot: Int? = null

        fun launchCurrentGameFromSaveSlot(slot: Int): Boolean {
            val game = currentGame.value ?: return false
            val launchPath = if (game.uri.scheme == "file") {
                game.uri.path ?: game.uri.toString()
            } else {
                game.uri.toString()
            }
            if (launchPath.isBlank()) return false
            pendingSlotLoadOnBoot = slot
            pendingAutoLoadOnBoot = false
            launchGame(launchPath, game)
            return true
        }

        private fun adoptExternalGameIdentity() {
            if (!launchedExternally || currentGame.value != null) return
            val path = m_szGamefile.takeIf { it.isNotEmpty() } ?: return
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.post(object : Runnable {
                var attempts = 0
                override fun run() {
                    if (vmStopInProgress || eState.value == EmuState.STOPPED) return
                    if (currentGame.value != null) return
                    val serial = runCatching { NativeApp.getGameSerial() }.getOrNull()
                        ?.trim()?.uppercase()?.takeIf { it.isNotEmpty() && it != "00000000" }
                    if (serial == null) {
                        if (++attempts < 40) handler.postDelayed(this, 250)
                        return
                    }
                    val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                        ?: path.substringAfterLast('/')
                    val (title, _) = FilenameParser.parse(name)
                    currentGame.value = GameInfo(
                        uri = uri,
                        title = title,
                        serial = serial,
                        extension = name.substringAfterLast('.', "").uppercase(),
                    )
                }
            })
        }

        @JvmStatic
        fun onVmRunning() {
            adoptExternalGameIdentity()
            val requestedSlot = pendingSlotLoadOnBoot
            val loadAutosave = pendingAutoLoadOnBoot && requestedSlot == null
            if (requestedSlot == null && !loadAutosave) return
            pendingSlotLoadOnBoot = null
            pendingAutoLoadOnBoot = false
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val tryLoad = object : Runnable {
                var attempts = 0
                var lastFrame = -1
                var advancingPolls = 0
                override fun run() {
                    if (vmStopInProgress || eState.value == EmuState.STOPPED) return
                    val frame = runCatching { NativeApp.getPresentedFrameCount() }.getOrDefault(0)
                    advancingPolls = if (lastFrame in 0 until frame) advancingPolls + 1 else 0
                    lastFrame = frame
                    if (advancingPolls < 3) {
                        if (++attempts < 60) handler.postDelayed(this, 250)
                        return
                    }
                    val loaded = runCatching {
                        if (requestedSlot != null) NativeApp.loadStateFromSlot(requestedSlot)
                        else NativeApp.loadAutosaveState()
                    }.getOrDefault(false)
                    if (!loaded && ++attempts < 60)
                        handler.postDelayed(this, 250)
                }
            }
            handler.postDelayed(tryLoad, 250)
        }

        fun finishSetup() {
            prefs.edit { putBoolean("setupComplete", true) }
            setupComplete.value = true
            setupEditorVisible.value = false
        }

        fun reopenSetup() {
            setupEditorVisible.value = true
        }

        private var lastInitDataRoot: String? = null
        fun currentInitDataRoot(): String? = lastInitDataRoot

        fun restartApp(context: Context) {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.startActivity(intent)
            }
            Runtime.getRuntime().exit(0)
        }

        fun renderOpenGL() {
            NativeApp.renderOpenGL()
        }

        fun renderVulkan() {
            NativeApp.renderVulkan()
        }

        fun renderSoftware() {
            NativeApp.renderSoftware()
        }

        fun romsAccessible(context: Context, romsDirs: List<String>): Boolean {
            if (romsDirs.isEmpty()) return false
            val persisted = runCatching { context.contentResolver.persistedUriPermissions }
                .getOrDefault(emptyList())
                .filter { it.isReadPermission }
                .map { it.uri.toString() }
                .toHashSet()
            val allFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                android.os.Environment.isExternalStorageManager()
            fun posixReadable(path: String?): Boolean {
                if (path == null) return false
                if (allFiles) return true
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                    runCatching { File(path).canRead() }.getOrDefault(false)
            }
            return romsDirs.any { raw ->
                when {
                    raw.startsWith("content:") ->
                        raw in persisted || (allFiles && resolveTreeUriToPosix(raw) != null)
                    raw.startsWith("file:") -> posixReadable(raw.toUri().path)
                    else -> posixReadable(raw)
                }
            }
        }

        fun assetCopyRoot(context: Context): String {
            val custom = systemDirPosix()
            return custom?.takeIf { validateSystemDirWritable(it) }
                ?: context.getExternalFilesDir(null)?.absolutePath
                ?: context.dataDir.absolutePath
        }

        fun copyAssetAll(p_context: Context, srcPath: String) {
            val bundled = File(p_context.filesDir, "retro/bundle/data/armsx2/$srcPath")
            if (bundled.isDirectory) {
                AssetFiles.copyTree(bundled, File(assetCopyRoot(p_context), srcPath))
            }
        }

        private fun sameFilePath(a: File, b: File): Boolean {
            val ca = runCatching { a.canonicalFile }.getOrDefault(a.absoluteFile)
            val cb = runCatching { b.canonicalFile }.getOrDefault(b.absoluteFile)
            return ca == cb
        }

        private fun copyFileViaTemp(src: File, target: File): Boolean {
            if (sameFilePath(src, target))
                return target.exists() && target.length() > 0L
            if (!src.exists() || src.length() <= 0L)
                return false

            val parent = target.parentFile ?: return false
            if (!parent.exists() && !parent.mkdirs())
                return false

            val tmp = File(parent, ".${target.name}.migrate.tmp")
            if (tmp.exists())
                tmp.delete()

            return runCatching {
                src.copyTo(tmp, overwrite = true)
                if (tmp.length() <= 0L)
                    return@runCatching false
                if (target.exists() && !target.delete())
                    return@runCatching false
                val installed = tmp.renameTo(target) || runCatching {
                    tmp.copyTo(target, overwrite = true)
                    true
                }.getOrDefault(false)
                installed && target.exists() && target.length() > 0L
            }.getOrDefault(false).also {
                tmp.delete()
            }
        }

        fun getSupportedGLESVersion(context: Context): Double =
            runCatching {
                val am = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
                am.deviceConfigurationInfo.glEsVersion.toDouble()
            }.getOrDefault(Double.MAX_VALUE)

        fun isAndroidEmulator(): Boolean {
            return Build.MODEL.startsWith("sdk_")
        }
    }

    val swapDiscAction = registerForActivityResult(
        StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == RESULT_OK) {
            try {
                val intent = result.data
                val uri = intent?.dataString ?: ""
                if (uri.isNotEmpty()) {
                    println("@@ANDROID_SWAP_DISC@@ uri=${uri.take(240)}")
                    kotlin.concurrent.thread {
                        val ok = runCatching { NativeApp.changeDisc(uri) }.getOrDefault(false)
                        instance?.runOnUiThread {
                            if (ok) {
                                resume()
                            } else {
                                resume()
                            }
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    val bootDiscAction = registerForActivityResult(
        StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == RESULT_OK) {
            try {
                val uri = result.data?.dataString ?: ""
                if (uri.isNotEmpty()) {
                    println("@@ANDROID_BOOT_DISC@@ uri=${uri.take(240)}")
                    launchGame(uri, null)
                }
            } catch (_: Exception) { }
        }
    }

    init {
        instance = this
    }

    private var emucoreInitDone = false

    private var autoBootBiosFired = false

    private val AUTO_BOOT_BIOS = false

    private fun kickoffEmucoreInit() {
        if (emucoreInitDone) return
        emucoreInitDone = true
        lastInitDataRoot = assetCopyRoot(applicationContext)

        invoke {
            runCatching { com.armsx2.config.ConfigStore.reconcileReusedFolder() }

            copyAssetAll(applicationContext, "bios")
            copyAssetAll(applicationContext, "resources")

            applyAngleEnv(applicationContext)

            bios.value?.takeIf { it.isNotEmpty() }?.let { current ->
                val src = File(current)
                val target = File(internalBiosDir(applicationContext).apply { mkdirs() }, src.name)
                if (!sameFilePath(target, src)) {
                    val present = (target.exists() && target.length() > 0L) ||
                        copyFileViaTemp(src, target)
                    if (present) {
                        bios.value = target.absolutePath
                        prefs.edit { putString("bios", target.absolutePath) }
                    }
                } else if (target.exists() && target.length() > 0L) {
                    bios.value = target.absolutePath
                    prefs.edit { putString("bios", target.absolutePath) }
                }
            }

            NativeApp.initializeOnce(applicationContext)
            nativeReady.value = true

            bios.value?.let { biosPath ->
                val name = File(biosPath).name
                if (name.isNotEmpty()) {
                    NativeApp.setSetting("Filenames", "BIOS", "string", name)
                    NativeApp.commitSettings()
                }
            }

            flushNativeReadyWaiters()

            runCatching {
                val rootPosix = systemDirPosix()
                if (!rootPosix.isNullOrEmpty()) {
                    val internalBios = internalBiosDir(applicationContext)
                    val mirrorDir = File(rootPosix, "bios")
                    if (mirrorDir.absolutePath != internalBios.absolutePath) {
                        mirrorDir.mkdirs()
                        internalBios.listFiles { f ->
                            f.isFile && !f.name.startsWith(".") && !f.name.endsWith(".migrate.tmp")
                        }?.forEach { f ->
                            val dst = File(mirrorDir, f.name)
                            if (!dst.exists() || dst.length() != f.length()) copyFileViaTemp(f, dst)
                        }
                    }
                }
            }

            SDLControllerManager.nativeSetupJNI()
            SDLControllerManager.initialize()
            HIDDeviceManager(applicationContext)

            println("PCSX2_INIT")

            NativeApp.runEeJitTests()
            NativeApp.runEeSeqTests()
            NativeApp.runVifTests()

            @Suppress("KotlinConstantConditions")
            if (AUTO_BOOT_BIOS && BuildConfig.DEBUG && !autoBootBiosFired &&
                eState.value == EmuState.STOPPED) {
                autoBootBiosFired = true
                startBios()
            }
        }
    }

    private val turboHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val turboRunnables = HashMap<Long, Runnable>()
    private val turboPressed = HashMap<Long, Boolean>()
    private fun turboMapKey(physicalCode: Int, port: Int) =
        (port.toLong() shl 32) or (physicalCode.toLong() and 0xffffffffL)

    private fun handleTurbo(physicalCode: Int, type: KeyEventType, target: Int, port: Int) {
        val key = turboMapKey(physicalCode, port)
        if (type == KeyEventType.KeyDown) {
            if (turboRunnables.containsKey(key)) return
            turboPressed[key] = false
            val r = object : Runnable {
                override fun run() {
                    val pressed = !(turboPressed[key] ?: false)
                    turboPressed[key] = pressed
                    sendKeyAction(if (pressed) KeyEventType.KeyDown else KeyEventType.KeyUp, target, port)
                    turboHandler.postDelayed(this, 33L)
                }
            }
            turboRunnables[key] = r
            turboHandler.post(r)
        } else {
            turboRunnables.remove(key)?.let { turboHandler.removeCallbacks(it) }
            turboPressed.remove(key)
            sendKeyAction(KeyEventType.KeyUp, target, port)
        }
    }

    fun sendKeyAction(p_action: KeyEventType, p_keycode_in: Int, port: Int = 0) {
        com.armsx2.ui.touch.TouchControls.onControllerInputDetected()
        val p_keycode = if (ControllerMappings.dpadAsLeftStick()) {
            when (p_keycode_in) {
                19 -> 110; 20 -> 112; 21 -> 113; 22 -> 111; else -> p_keycode_in
            }
        } else p_keycode_in
        if (p_action == KeyEventType.KeyDown) {
            var pad_force = 0
            if (p_keycode >= 110) {
                var _abs = 90f
                _abs = min(_abs, 100f)
                pad_force = (_abs * 32766.0f / 100).toInt()
            } else {
                pad_force = com.armsx2.ui.touch.TouchControls.pressureRangeFor(p_keycode)
            }
            if (p_keycode in 110..123 && port in analogKeyHeld.indices)
                analogKeyHeld[port][p_keycode] = pad_force / 32767f
            NativeApp.setPadButtonForPort(port, p_keycode, pad_force, true)
        } else if (p_action == KeyEventType.KeyUp || p_action == KeyEventType.Unknown) {
            if (p_keycode in 110..123 && port in analogKeyHeld.indices)
                analogKeyHeld[port].remove(p_keycode)
            NativeApp.setPadButtonForPort(port, p_keycode, 0, false)
        }
    }

    fun applyEmulationOrientation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun applyEdgeToEdge() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                LIGHT_NAVIGATION_BAR_SCRIM,
                DARK_NAVIGATION_BAR_SCRIM,
            ),
        )
    }

    private fun applySystemBarTheme(darkTheme: Boolean, showSystemBars: Boolean) {
        applyEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            val useDarkIcons = !darkTheme
            isAppearanceLightStatusBars = useDarkIcons
            isAppearanceLightNavigationBars = useDarkIcons
            if (showSystemBars) show(WindowInsetsCompat.Type.systemBars())
            else hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        NativeApp.ensureLoaded(applicationContext)
        if (NativeApp.hasNoNativeBinary) {
            android.util.Log.e("ARMSX2", "emucore native library missing — finishing")
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        onBackPressedDispatcher.addCallback(this) {
        }
        prefs = applicationContext.getSharedPreferences("ARMSX2", MODE_PRIVATE)
        com.winlator.cmod.shared.framegen.FrameGen.installFromIntent(this, intent)
        com.winlator.cmod.shared.framegen.FrameGen.applyDisplayMode(this)
        applyEmulationOrientation()
        NativeApp.sRumbleEnabled = ControllerMappings.rumbleEnabled()
        com.armsx2.input.PadRouter.multitapEnabled = ControllerMappings.multitapEnabled()
        setupComplete.value = prefs.getBoolean("setupComplete", false)
        systemDir.value = prefs.getString("systemDir", null)
        bios.value = prefs.getString("bios", null)
        biosDir.value = prefs.getString("biosDir", null)
        romsDirs.value = run {
            val newJson = prefs.getString("romsDirs", null)
            if (newJson != null) {
                runCatching {
                    val arr = org.json.JSONArray(newJson)
                    List(arr.length()) { arr.getString(it) }
                }.getOrDefault(emptyList())
            } else {
                val legacy = prefs.getString("roms", null)
                if (legacy != null) listOf(legacy) else emptyList()
            }
        }
        val embeddedGameLaunch = runCatching { intent?.data != null }.getOrDefault(false)
        if (embeddedGameLaunch) {
            setupComplete.value = true
        } else if (setupComplete.value && !romsAccessible(this, romsDirs.value)) {
            setupComplete.value = false
            setupRecoveryNeeded.value = true
        }
        com.armsx2.config.ConfigStore.loadGlobal().let { g0 ->
            renderer.value = g0.renderer
            upscale.value = g0.upscaleFloat
        }
        customDriverId.value = prefs.getString("customDriverId", null)?.takeIf { it.isNotEmpty() }
        surface.value = EmulationSurface(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.requestApplyInsets(window.decorView)

        fun tryAttachHostOverlay() {
            if (isFinishing || isDestroyed) return
            com.armsx2.WinNativeHost.attachOverlay?.invoke(this)
        }
        window.decorView.post { tryAttachHostOverlay() }
        window.decorView.postDelayed({ tryAttachHostOverlay() }, 50L)
        window.decorView.postDelayed({ tryAttachHostOverlay() }, 200L)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
            prefs.getBoolean("ui.sustainedPerf", false)) {
            runCatching { window.setSustainedPerformanceMode(true) }
        }

        if (setupComplete.value) {
            kickoffEmucoreInit()
        }

        val glVersion = getSupportedGLESVersion(this)

        if (glVersion < 3.1) {
            eState.value = EmuState.RENDER_UNSUPPORTED
            println("RENDER_UNSUPPORTED")
        }

        if (isAndroidEmulator()) {
            eState.value = EmuState.EMULATOR_UNSUPPORTED
            println("DEVICE_UNSUPPORTED")
        }

        if (eState.value == EmuState.RENDER_UNSUPPORTED || eState.value == EmuState.EMULATOR_UNSUPPORTED) {
            android.util.Log.e("ARMSX2", "device cannot run the emulator (${eState.value}) — finishing")
            finish()
            return
        }
        handleExternalLaunchIntent(intent)
        if (extractLaunchUri(intent) == null && !com.armsx2.WinNativeHost.enabled()) {
            finish()
            return
        }
        setContent {
            androidx.compose.runtime.LaunchedEffect(setupComplete.value) {
                if (setupComplete.value) {
                    kickoffEmucoreInit()
                }
            }

            androidx.compose.runtime.LaunchedEffect(
                setupComplete.value,
                nativeReady.value,
                pendingExternalLaunch.value,
                pendingLaunch.value,
            ) {
                launchPendingExternalGameIfReady()
            }

            val gyroCtx = androidx.compose.ui.platform.LocalContext.current
            val gyro = androidx.compose.runtime.remember {
                com.armsx2.input.AndroidGyroscopeInput(gyroCtx) { mode, x, y ->
                    instance?.onGyroAnalog(mode, x, y)
                }
            }
            val gyroMode = ControllerMappings.gyroMode()
            val gyroOn = gyroActive.value
            androidx.compose.runtime.DisposableEffect(
                eState.value, gyroMode, gyroOn,
                ControllerMappings.gyroSensitivity(),
                ControllerMappings.gyroSmoothing(),
                ControllerMappings.gyroInvertX(),
                ControllerMappings.gyroInvertY(),
            ) {
                if (eState.value == EmuState.RUNNING && gyroMode != 0 && gyroOn) {
                    gyro.start(
                        gyroMode,
                        ControllerMappings.gyroSensitivity(),
                        ControllerMappings.gyroSmoothing(),
                        ControllerMappings.gyroInvertX(),
                        ControllerMappings.gyroInvertY(),
                    )
                } else {
                    gyro.stop()
                }
                onDispose { gyro.stop() }
            }

            Box(
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (surface.value != null) {
                    val surfaceAspect =
                        when (prefs.getInt("wn.ps2.aspect", 1).coerceIn(0, 3)) {
                            3 -> 16f / 9f
                            else -> 4f / 3f
                        }
                    androidx.compose.runtime.LaunchedEffect(surface.value, eState.value) {
                        if (eState.value == EmuState.RUNNING) {
                            surface.value?.isFocusable = true
                            surface.value?.isFocusableInTouchMode = true
                            runCatching { focusRequester.requestFocus() }
                        }
                    }
                    AndroidView(
                        factory = { surface.value!! },
                        modifier =
                            Modifier
                                .focusable(true)
                                .focusRequester(focusRequester)
                                .fillMaxHeight()
                                .aspectRatio(surfaceAspect)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            com.armsx2.ui.touch.TouchControls.onSurfaceTouched()
                                        },
                                    )
                                }
                                .onKeyEvent { event ->
                                    if (eState.value != EmuState.RUNNING) {
                                        return@onKeyEvent false
                                    }
                                    val port =
                                        com.armsx2.input.PadRouter.portForDevice(event.nativeKeyEvent.deviceId)
                                    val macro =
                                        com.armsx2.ui.touch.TouchControls.macroForPhysicalCode(event.key.nativeKeyCode)
                                    if (macro != null) {
                                        com.armsx2.ui.touch.TouchControls.fireMacro(
                                            macro,
                                            "pad$port",
                                            event.type == KeyEventType.KeyDown,
                                        ) { code, pressed ->
                                            sendKeyAction(
                                                if (pressed) KeyEventType.KeyDown else KeyEventType.KeyUp,
                                                code,
                                                port,
                                            )
                                        }
                                        return@onKeyEvent true
                                    }
                                    val target =
                                        ControllerMappings.targetForPhysical(event.key.nativeKeyCode, port)
                                            ?: return@onKeyEvent false
                                    if (ControllerMappings.isTurboTarget(target, port)) {
                                        handleTurbo(event.key.nativeKeyCode, event.type, target, port)
                                        return@onKeyEvent true
                                    }
                                    sendKeyAction(event.type, target, port)
                                    true
                                },
                    )
                }
            }
        }
    }

    private val heldKeys = HashSet<Int>()

    private val backHoldHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var backHoldRunnable: Runnable? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val kc = event.keyCode
        if (kc != KeyEvent.KEYCODE_UNKNOWN) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> heldKeys.add(kc)
                KeyEvent.ACTION_UP -> heldKeys.remove(kc)
            }
        }
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            NativeApp.sRumbleDeviceId = event.deviceId
        }
        if (kc == KeyEvent.KEYCODE_BUTTON_MODE) {
            val open = com.armsx2.WinNativeHost.openMenu
            if (open != null) {
                if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) open()
                return true
            }
        }
        if (com.armsx2.WinNativeHost.isMenuOpen?.invoke() == true) {
            val handled = com.armsx2.WinNativeHost.menuKeyHandler?.invoke(event) == true
            if (handled) return true
        }
        if (forwardKeyToUsbKeyboard(event, kc)) {
            return true
        }
        val capturing = ControllerMappings.captureHotkey.value
        if (capturing != null) {
            if (kc != KeyEvent.KEYCODE_UNKNOWN) {
                val buf = ControllerMappings.captureKeys
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (buf.isEmpty()) {
                        buf.add(kc)
                        ControllerMappings.captureFirstDownMs = event.eventTime
                    } else if (!buf.contains(kc) &&
                        event.eventTime - ControllerMappings.captureFirstDownMs >= COMBO_MIN_GAP_MS
                    ) {
                        buf.add(kc)
                        ControllerMappings.bindHotkeyCombo(capturing, buf[0], buf[1])
                        ControllerMappings.endHotkeyCapture()
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    if (buf.size == 1 && buf.contains(kc)) {
                        ControllerMappings.bindHotkey(capturing, buf[0])
                        ControllerMappings.endHotkeyCapture()
                    }
                }
            }
            return true
        }
        val padCapture = ControllerMappings.capturePadAction.value
        if (padCapture != null) {
            if (kc != KeyEvent.KEYCODE_UNKNOWN && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                padCapture(kc)
            }
            return true
        }
        if (ControllerMappings.padCapturing.value) {
            return super.dispatchKeyEvent(event)
        }
        run {
            val pm = ControllerMappings.SysHotkey.PRESSURE_MOD
            val pmKey = ControllerMappings.hotkeyCode(pm)
            if (pmKey != KeyEvent.KEYCODE_UNKNOWN && kc == pmKey &&
                ControllerMappings.hotkeyModCode(pm) == KeyEvent.KEYCODE_UNKNOWN) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> com.armsx2.ui.touch.TouchControls.pressureModifierHeld.value = true
                    KeyEvent.ACTION_UP -> com.armsx2.ui.touch.TouchControls.pressureModifierHeld.value = false
                }
                return true
            }
        }
        run {
            val down = event.action == KeyEvent.ACTION_DOWN
            val matchKeys = if (down) heldKeys else heldKeys + kc
            if (ControllerMappings.matchHotkey(kc, matchKeys) == ControllerMappings.SysHotkey.MENU) {
                if (down) com.armsx2.WinNativeHost.openMenu?.invoke()
                return true
            }
        }
        if (eState.value == EmuState.RUNNING) {
            val down = event.action == KeyEvent.ACTION_DOWN
            val matchKeys = if (down) heldKeys else heldKeys + kc
            when (ControllerMappings.matchHotkey(kc, matchKeys)) {
                ControllerMappings.SysHotkey.PRESSURE_MOD -> {}
                ControllerMappings.SysHotkey.MENU -> {
                    if (down) com.armsx2.WinNativeHost.openMenu?.invoke()
                    return true
                }
                ControllerMappings.SysHotkey.SAVE_STATE -> {
                    if (down) {
                        val slot = currentSaveSlot.value
                        kotlin.concurrent.thread { runCatching { NativeApp.saveStateToSlot(slot) } }
                    }
                    return true
                }
                ControllerMappings.SysHotkey.LOAD_STATE -> {
                    if (down) {
                        val slot = currentSaveSlot.value
                        kotlin.concurrent.thread { runCatching { NativeApp.loadStateFromSlot(slot) } }
                    }
                    return true
                }
                ControllerMappings.SysHotkey.CYCLE_SLOT -> {
                    if (down) cycleSaveSlot()
                    return true
                }
                ControllerMappings.SysHotkey.TEXTURE_DUMP -> {
                    if (down) {
                        val on = runCatching { NativeApp.toggleTextureDumping() }.getOrDefault(false)
                        android.widget.Toast.makeText(
                            this,
                            if (on) "Texture dumping ON" else "Texture dumping OFF",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return true
                }
                ControllerMappings.SysHotkey.TOGGLE_OSD -> {
                    if (down && event.repeatCount == 0) InGameOverlay.toggleOsd()
                    return true
                }
                ControllerMappings.SysHotkey.GYRO_TOGGLE -> {
                    if (down && event.repeatCount == 0) toggleGyro()
                    return true
                }
                ControllerMappings.SysHotkey.GYRO_HOLD -> {
                    if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                        if (event.repeatCount == 0) gyroActive.value = down
                    }
                    return true
                }
                ControllerMappings.SysHotkey.FAST_FORWARD -> {
                    if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                        if (event.repeatCount == 0) {
                            if (down) fastForwardToggleActive = false
                            runCatching { NativeApp.speedhackLimitermode(if (down) 1 else baseLimiterMode()) }
                        }
                    }
                    return true
                }
                ControllerMappings.SysHotkey.FAST_FORWARD_TOGGLE -> {
                    if (down && event.repeatCount == 0) toggleFastForward()
                    return true
                }
                ControllerMappings.SysHotkey.SLOW_DOWN -> {
                    if (down && event.repeatCount == 0) toggleSlowDown()
                    return true
                }
                ControllerMappings.SysHotkey.RES_UP -> {
                    if (down) stepResolution(1)
                    return true
                }
                ControllerMappings.SysHotkey.RES_DOWN -> {
                    if (down) stepResolution(-1)
                    return true
                }
                ControllerMappings.SysHotkey.ACHIEVEMENTS -> {
                    return true
                }
                ControllerMappings.SysHotkey.CLOSE_GAME -> {
                    if (down) closeGame()
                    return true
                }
                ControllerMappings.SysHotkey.QUIT_APP -> {
                    if (down) { quitAfterStop = true; stop()
                    }
                    return true
                }
                ControllerMappings.SysHotkey.SAVE_AND_EXIT -> {
                    if (down) closeGame(saveAutosave = true)
                    return true
                }
                ControllerMappings.SysHotkey.RESET_GAME -> {
                    if (down) restart()
                    return true
                }
                null -> {}
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun forwardKeyToUsbKeyboard(event: KeyEvent, kc: Int): Boolean {
        if (!usbKeyboardActive) return false
        if (eState.value != EmuState.RUNNING) return false
        if (ControllerMappings.padCapturing.value ||
            ControllerMappings.captureHotkey.value != null) return false
        if (!event.isFromSource(InputDevice.SOURCE_KEYBOARD)) return false
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
        if (kc == KeyEvent.KEYCODE_UNKNOWN) return false
        if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_HOME) return false
        val pressed = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        return runCatching {
            NativeApp.usbKeyboardKey(0, kc, pressed)
        }.getOrDefault(false)
    }

    private fun baseLimiterMode(): Int =
        if (InGameOverlay.frameLimitOn.value) 0 else 3

    private fun toggleGyro() {
        val on = !gyroActive.value
        gyroActive.value = on
        hotkeyToast(if (on) "Gyro ON" else "Gyro OFF")
    }

    fun toggleFastForward() {
        fastForwardToggleActive = !fastForwardToggleActive
        val on = fastForwardToggleActive
        if (on) slowDownToggleActive = false
        runCatching { NativeApp.speedhackLimitermode(if (on) 1 else baseLimiterMode()) }
        hotkeyToast(if (on) "Fast Forward ON" else "Fast Forward OFF")
    }

    fun toggleSlowDown() {
        if (InGameOverlay.hardcoreOn.value) {
            slowDownToggleActive = false
            hotkeyToast("Slow Down is disabled in RetroAchievements Hardcore mode")
            return
        }
        slowDownToggleActive = !slowDownToggleActive
        val on = slowDownToggleActive
        if (on) fastForwardToggleActive = false
        runCatching { NativeApp.speedhackLimitermode(if (on) 2 else baseLimiterMode()) }
        hotkeyToast(if (on) "Slow Down ON (50%)" else "Slow Down OFF")
    }

    private var lastHotkeyToast: android.widget.Toast? = null
    private fun hotkeyToast(text: String) {
        if (!prefs.getBoolean("ui.hotkeyToasts", true)) return
        lastHotkeyToast?.cancel()
        lastHotkeyToast = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT)
            .also { it.show() }
    }

    fun saveState() {
        val slot = currentSaveSlot.value
        kotlin.concurrent.thread { runCatching { NativeApp.saveStateToSlot(slot) } }
    }

    fun loadState(onLoaded: (() -> Unit)? = null) {
        val slot = currentSaveSlot.value
        kotlin.concurrent.thread {
            runCatching { NativeApp.loadStateFromSlot(slot) }
            onLoaded?.let { cb -> android.os.Handler(android.os.Looper.getMainLooper()).post(cb) }
        }
    }

    private fun cycleSaveSlot() {
        val next = (currentSaveSlot.value + 1) % 10
        currentSaveSlot.value = next
        android.widget.Toast.makeText(this, "Save slot $next", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun stepResolution(dir: Int) {
        val next = (upscale.value.toInt() + dir).coerceIn(1, 8)
        val nf = next.toFloat()
        upscale.value = nf
        runCatching { NativeApp.renderUpscalemultiplier(nf) }
        runCatching {
            val serial = currentGame.value?.serial?.takeIf { it.isNotBlank() }
            val resolved = com.armsx2.config.ConfigStore.resolveForGame(serial)
            com.armsx2.config.ConfigStore.save(
                if (serial != null) com.armsx2.config.SettingsScope.Game
                else com.armsx2.config.SettingsScope.Global,
                serial,
                resolved.copy(upscaleFloat = nf),
            )
        }
        android.widget.Toast.makeText(this, "Resolution ${next}x", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (com.armsx2.WinNativeHost.isMenuOpen?.invoke() == true) {
            val x = ev.getAxisValue(MotionEvent.AXIS_HAT_X).takeIf { abs(it) > 0.5f }
                ?: ev.getAxisValue(MotionEvent.AXIS_X)
            val y = ev.getAxisValue(MotionEvent.AXIS_HAT_Y).takeIf { abs(it) > 0.5f }
                ?: ev.getAxisValue(MotionEvent.AXIS_Y)
            if (com.armsx2.WinNativeHost.menuAxisHandler?.invoke(x, y) == true) return true
        }
        if (ControllerMappings.padCapturing.value || ControllerMappings.captureHotkey.value != null) {
            return handleCaptureMotion(ev)
        }
        captureHatX = 0
        captureHatY = 0
        if (captureHeldSynth.isNotEmpty()) {
            heldKeys.removeAll(captureHeldSynth)
            captureHeldSynth.clear()
        }
        if (eState.value == EmuState.RUNNING) {
            if (!ev.isFromSource(InputDevice.SOURCE_JOYSTICK) &&
                !ev.isFromSource(InputDevice.SOURCE_GAMEPAD)) {
                return super.dispatchGenericMotionEvent(ev)
            }
            com.armsx2.ui.touch.TouchControls.onControllerInputDetected()
            val port = com.armsx2.input.PadRouter.portForDevice(ev.deviceId)
            dispatchStick(ev, ControllerMappings.leftStickMode(port),
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                aXPos = 111, aXNeg = 113, aYPos = 112, aYNeg = 110,
                leftStick = true, port = port)
            dispatchStick(ev, ControllerMappings.rightStickMode(port),
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                aXPos = 121, aXNeg = 123, aYPos = 122, aYNeg = 120,
                leftStick = false, port = port)
            fireStickHotkeys(ev, port)
            dispatchDpadCombined(ev, port)
            sendTrigger(ev, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE,
                KeyEvent.KEYCODE_BUTTON_L2, port)
            sendTrigger(ev, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS,
                KeyEvent.KEYCODE_BUTTON_R2, port)
            dispatchStickDirBindings(ev, port)
            flushAnalogAxes(port)
            debugStickProbe(ev)
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private val stickDirDigitalHeld = Array(8) { HashSet<Int>() }
    private fun dispatchStickDirBindings(ev: MotionEvent, port: Int) {
        for (left in booleanArrayOf(true, false)) {
            var vx = ev.getAxisValue(if (left) MotionEvent.AXIS_X else MotionEvent.AXIS_Z)
            var vy = ev.getAxisValue(if (left) MotionEvent.AXIS_Y else MotionEvent.AXIS_RZ)
            if (ControllerMappings.stickSwapXY(left)) { val t = vx; vx = vy; vy = t }
            if (ControllerMappings.stickInvertX(left)) vx = -vx
            if (ControllerMappings.stickInvertY(left)) vy = -vy
            for (dir in ControllerMappings.StickDir.values()) {
                val physCode = ControllerMappings.stickHotkeyKeyCode(left, dir)
                val target = ControllerMappings.targetForPhysical(physCode, port) ?: continue
                val mag = when (dir) {
                    ControllerMappings.StickDir.UP -> -vy
                    ControllerMappings.StickDir.DOWN -> vy
                    ControllerMappings.StickDir.LEFT -> -vx
                    ControllerMappings.StickDir.RIGHT -> vx
                }.coerceAtLeast(0f)
                if (target in 110..123) {
                    accumAnalog(target, shapeStickMag(mag, left))
                } else {
                    val held = stickDirDigitalHeld[port]
                    val on = mag > STICK_DIGITAL_THRESHOLD
                    val was = held.contains(target)
                    if (on != was) {
                        NativeApp.setPadButtonForPort(port, target, if (on) 32767 else 0, on)
                        if (on) held.add(target) else held.remove(target)
                    }
                }
            }
        }
    }

    private var lastStickProbeMs = 0L
    private fun debugStickProbe(ev: MotionEvent) {
        if (!prefs.getBoolean("debug.stickLog", false)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastStickProbeMs < 250) return
        lastStickProbeMs = now
        val z = ev.getAxisValue(MotionEvent.AXIS_Z)
        val rz = ev.getAxisValue(MotionEvent.AXIS_RZ)
        val rx = ev.getAxisValue(MotionEvent.AXIS_RX)
        val ry = ev.getAxisValue(MotionEvent.AXIS_RY)
        val mag = kotlin.math.hypot(z, rz)
        println("@@STICKPROBE@@ dev=${ev.deviceId} Z=%.3f RZ=%.3f RX=%.3f RY=%.3f mag=%.3f shaped=%.3f".format(
            z, rz, rx, ry, mag, shapeStickMag(mag.coerceAtMost(1f), false)))
    }

    private var captureHatX = 0
    private var captureHatY = 0

    private val captureHeldSynth = HashSet<Int>()
    private fun handleCaptureMotion(ev: MotionEvent): Boolean {
        val want = HashSet<Int>()
        val dx = uiHatDirection(ev.getAxisValue(MotionEvent.AXIS_HAT_X))
        val dy = uiHatDirection(ev.getAxisValue(MotionEvent.AXIS_HAT_Y))
        if (dx != 0) want.add(if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
        if (dy != 0) want.add(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
        captureStickCode(ev, MotionEvent.AXIS_X, MotionEvent.AXIS_Y, true).takeIf { it != 0 }?.let { want.add(it) }
        captureStickCode(ev, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, false).takeIf { it != 0 }?.let { want.add(it) }
        captureHatX = dx
        captureHatY = dy
        val now = SystemClock.uptimeMillis()
        val released = captureHeldSynth.filter { it !in want }
        for (code in released) {
            captureHeldSynth.remove(code)
            dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        }
        for (code in want) {
            if (captureHeldSynth.add(code))
                dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        }
        if (ControllerMappings.captureHotkey.value == null && !ControllerMappings.padCapturing.value) {
            heldKeys.removeAll(captureHeldSynth)
            captureHeldSynth.clear()
        }
        return true
    }

    private fun captureStickCode(ev: MotionEvent, axisX: Int, axisY: Int, left: Boolean): Int {
        val x = ev.getAxisValue(axisX)
        val y = ev.getAxisValue(axisY)
        val t = 0.7f
        return when {
            y <= -t -> ControllerMappings.stickHotkeyKeyCode(left, ControllerMappings.StickDir.UP)
            y >= t -> ControllerMappings.stickHotkeyKeyCode(left, ControllerMappings.StickDir.DOWN)
            x <= -t -> ControllerMappings.stickHotkeyKeyCode(left, ControllerMappings.StickDir.LEFT)
            x >= t -> ControllerMappings.stickHotkeyKeyCode(left, ControllerMappings.StickDir.RIGHT)
            else -> 0
        }
    }

    private fun uiHatDirection(value: Float): Int = when {
        value > UI_HAT_DEAD -> 1
        value < -UI_HAT_DEAD -> -1
        else -> 0
    }

    private fun shapeStickMag(m: Float, left: Boolean): Float {
        val dz = ControllerMappings.stickDeadzone(left)
        if (m <= dz) return 0f
        val outer = ControllerMappings.stickOuterDeadzone(left)
        val hi = (1f - outer).coerceAtLeast(dz + 0.01f)
        val t = ((m - dz) / (hi - dz)).coerceIn(0f, 1f)
        val accel = ControllerMappings.stickAcceleration(left)
        val curved =
            if (accel > 0f) Math.pow(t.toDouble(), (1f + accel).toDouble()).toFloat()
            else t
        val out = (curved * ControllerMappings.stickSensitivity(left)).coerceIn(0f, 1f)
        if (out <= 0f) return 0f
        val anti = ControllerMappings.stickAntiDeadzone(left)
        return if (anti > 0f) (anti + out * (1f - anti)).coerceIn(0f, 1f) else out
    }

    private val analogAccum = HashMap<Int, Float>()
    private val analogPrevSent = Array(8) { HashMap<Int, Float>() }
    val analogKeyHeld = Array(8) { HashMap<Int, Float>() }

    @Volatile private var gyroCombineActive = false
    @Volatile private var gyroCombineLeft = false
    @Volatile private var gyroVecX = 0f
    @Volatile private var gyroVecY = 0f
    private val lastPhysStickX = floatArrayOf(0f, 0f)
    private val lastPhysStickY = floatArrayOf(0f, 0f)

    private fun accumAnalog(code: Int, v: Float) {
        if (v <= 0f) return
        val cur = analogAccum[code] ?: 0f
        if (v > cur) analogAccum[code] = v
    }

    private fun flushAnalogAxes(port: Int) {
        val prev = analogPrevSent[port]
        for ((code, held) in analogKeyHeld[port]) accumAnalog(code, held)
        for (code in prev.keys) {
            if (!analogAccum.containsKey(code)) {
                NativeApp.setPadButtonForPort(port, code, 0, false)
            }
        }
        for ((code, v) in analogAccum) {
            if (prev[code] != v)
                NativeApp.setPadButtonForPort(port, code, (v * 32767).toInt(), true)
        }
        prev.clear()
        prev.putAll(analogAccum)
        analogAccum.clear()
    }

    private fun accumStickRadial(vx: Float, vy: Float, left: Boolean,
                                 aXPos: Int, aXNeg: Int, aYPos: Int, aYNeg: Int) {
        var gx = vx
        var gy = vy
        val ax = abs(gx)
        val ay = abs(gy)
        if (ax >= ay) { if (ay < ax * STICK_CROSS_GATE) gy = 0f }
        else { if (ax < ay * STICK_CROSS_GATE) gx = 0f }
        val mag = kotlin.math.hypot(gx, gy)
        if (mag <= 0f) return
        val shaped = shapeStickMag(mag.coerceAtMost(1f), left)
        val scale = shaped / mag
        val ox = gx * scale
        val oy = gy * scale
        if (ox > 0f) accumAnalog(aXPos, ox) else if (ox < 0f) accumAnalog(aXNeg, -ox)
        if (oy > 0f) accumAnalog(aYPos, oy) else if (oy < 0f) accumAnalog(aYNeg, -oy)
    }

    fun onGyroAnalog(mode: Int, gx: Float, gy: Float) {
        gyroCombineLeft = mode == 2 ||
            (mode == 1 && ControllerMappings.gyroAimStick() == ControllerMappings.GYRO_STICK_LEFT)
        gyroVecX = gx; gyroVecY = gy
        gyroCombineActive = gx != 0f || gy != 0f
        emitCombinedSticks()
    }

    private fun emitCombinedSticks() {
        val gxL = if (gyroCombineLeft) gyroVecX else 0f
        val gyL = if (gyroCombineLeft) gyroVecY else 0f
        val gxR = if (gyroCombineLeft) 0f else gyroVecX
        val gyR = if (gyroCombineLeft) 0f else gyroVecY
        accumStickRadial(lastPhysStickX[0] + gxL, lastPhysStickY[0] + gyL, true,  111, 113, 112, 110)
        accumStickRadial(lastPhysStickX[1] + gxR, lastPhysStickY[1] + gyR, false, 121, 123, 122, 120)
        flushAnalogAxes(0)
    }

    private fun dispatchStick(
        event: MotionEvent, mode: ControllerMappings.StickMode,
        axisX: Int, axisY: Int,
        aXPos: Int, aXNeg: Int, aYPos: Int, aYNeg: Int,
        leftStick: Boolean, port: Int,
    ) {
        var vx = event.getAxisValue(axisX)
        var vy = event.getAxisValue(axisY)
        if (ControllerMappings.stickSwapXY(leftStick)) { val t = vx; vx = vy; vy = t }
        if (ControllerMappings.stickInvertX(leftStick)) vx = -vx
        if (ControllerMappings.stickInvertY(leftStick)) vy = -vy
        when (mode) {
            ControllerMappings.StickMode.ANALOG -> {
                var sx = vx; var sy = vy
                if (port == 0) {
                    val si = if (leftStick) 0 else 1
                    lastPhysStickX[si] = vx; lastPhysStickY[si] = vy
                    if (gyroCombineActive && gyroCombineLeft == leftStick) { sx += gyroVecX; sy += gyroVecY }
                }
                accumStickRadial(sx, sy, leftStick, aXPos, aXNeg, aYPos, aYNeg)
                if (leftStick && ControllerMappings.dpadAsLeftStick()) {
                    val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                    val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                    if (hx > STICK_DEAD) accumAnalog(aXPos, hx) else if (hx < -STICK_DEAD) accumAnalog(aXNeg, -hx)
                    if (hy > STICK_DEAD) accumAnalog(aYPos, hy) else if (hy < -STICK_DEAD) accumAnalog(aYNeg, -hy)
                }
            }
            ControllerMappings.StickMode.FACE -> {
                sendAxisDigital(vx, posCode = 97, negCode = 99, port = port)
                sendAxisDigital(vy, posCode = 96, negCode = 100, port = port)
            }
            ControllerMappings.StickMode.CUSTOM -> {
                emitCustom(ControllerMappings.customStickCode(leftStick, ControllerMappings.StickDir.RIGHT, port),
                    if (vx > 0f) vx else 0f, port, leftStick)
                emitCustom(ControllerMappings.customStickCode(leftStick, ControllerMappings.StickDir.LEFT, port),
                    if (vx < 0f) -vx else 0f, port, leftStick)
                emitCustom(ControllerMappings.customStickCode(leftStick, ControllerMappings.StickDir.DOWN, port),
                    if (vy > 0f) vy else 0f, port, leftStick)
                emitCustom(ControllerMappings.customStickCode(leftStick, ControllerMappings.StickDir.UP, port),
                    if (vy < 0f) -vy else 0f, port, leftStick)
            }
        }
    }

    private val stickHotkeyHeld = Array(8) { HashSet<Int>() }

    private fun fireStickHotkeys(ev: MotionEvent, port: Int) {
        fireStickHotkeyAxis(ev, MotionEvent.AXIS_X, MotionEvent.AXIS_Y, true, port)
        fireStickHotkeyAxis(ev, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, false, port)
    }
    private fun fireStickHotkeyAxis(ev: MotionEvent, axisX: Int, axisY: Int, left: Boolean, port: Int) {
        val x = ev.getAxisValue(axisX)
        val y = ev.getAxisValue(axisY)
        val held = stickHotkeyHeld[port]
        val dirs = arrayOf(
            ControllerMappings.StickDir.UP to -y, ControllerMappings.StickDir.DOWN to y,
            ControllerMappings.StickDir.LEFT to -x, ControllerMappings.StickDir.RIGHT to x,
        )
        for ((dir, value) in dirs) {
            val code = ControllerMappings.stickHotkeyKeyCode(left, dir)
            if (value > STICK_DIGITAL_THRESHOLD) {
                heldKeys.add(code)
                if (held.add(code)) {
                    ControllerMappings.matchHotkey(code, heldKeys)?.let { runStickHotkey(it) }
                }
            } else {
                heldKeys.remove(code)
                held.remove(code)
            }
        }
    }

    private fun runStickHotkey(h: ControllerMappings.SysHotkey) {
        when (h) {
            ControllerMappings.SysHotkey.MENU -> {
                com.armsx2.WinNativeHost.openMenu?.invoke()
            }
            ControllerMappings.SysHotkey.SAVE_STATE -> {
                val slot = currentSaveSlot.value
                kotlin.concurrent.thread { runCatching { NativeApp.saveStateToSlot(slot) } }
            }
            ControllerMappings.SysHotkey.LOAD_STATE -> {
                val slot = currentSaveSlot.value
                kotlin.concurrent.thread { runCatching { NativeApp.loadStateFromSlot(slot) } }
            }
            ControllerMappings.SysHotkey.CYCLE_SLOT -> cycleSaveSlot()
            ControllerMappings.SysHotkey.TEXTURE_DUMP -> {
                val on = runCatching { NativeApp.toggleTextureDumping() }.getOrDefault(false)
                android.widget.Toast.makeText(this,
                    if (on) "Texture dumping ON" else "Texture dumping OFF",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            ControllerMappings.SysHotkey.FAST_FORWARD_TOGGLE -> {
                fastForwardToggleActive = !fastForwardToggleActive
                val on = fastForwardToggleActive
                runCatching { NativeApp.speedhackLimitermode(if (on) 1 else baseLimiterMode()) }
                hotkeyToast(if (on) "Fast Forward ON" else "Fast Forward OFF")
            }
            ControllerMappings.SysHotkey.GYRO_TOGGLE -> toggleGyro()
            ControllerMappings.SysHotkey.GYRO_HOLD -> toggleGyro()
            ControllerMappings.SysHotkey.RES_UP -> stepResolution(1)
            ControllerMappings.SysHotkey.RES_DOWN -> stepResolution(-1)
            ControllerMappings.SysHotkey.ACHIEVEMENTS -> {}
            ControllerMappings.SysHotkey.CLOSE_GAME -> closeGame()
            ControllerMappings.SysHotkey.QUIT_APP -> { quitAfterStop = true; stop()
            }
            ControllerMappings.SysHotkey.SAVE_AND_EXIT -> closeGame(saveAutosave = true)
            ControllerMappings.SysHotkey.RESET_GAME -> restart()
            ControllerMappings.SysHotkey.SLOW_DOWN -> toggleSlowDown()
            ControllerMappings.SysHotkey.TOGGLE_OSD -> InGameOverlay.toggleOsd()
            ControllerMappings.SysHotkey.FAST_FORWARD,
            ControllerMappings.SysHotkey.PRESSURE_MOD -> {}
        }
    }

    private fun emitCustom(code: Int, mag: Float, port: Int, srcLeft: Boolean) {
        ControllerMappings.hotkeyForStickCode(code)?.let { hk ->
            val held = stickHotkeyHeld[port]
            if (mag > STICK_DIGITAL_THRESHOLD) {
                if (held.add(code)) runStickHotkey(hk)
            } else {
                held.remove(code)
            }
            return
        }
        if (code in 19..22) return
        if (code in 110..123) {
            val m = shapeStickMag(mag, srcLeft)
            accumAnalog(code, m)
        } else {
            NativeApp.setPadButtonForPort(port, code, 32767, mag > STICK_DIGITAL_THRESHOLD)
        }
    }

    private fun sendAxisDigital(v: Float, posCode: Int, negCode: Int, port: Int) {
        NativeApp.setPadButtonForPort(port, posCode, 32767, v > STICK_DIGITAL_THRESHOLD)
        NativeApp.setPadButtonForPort(port, negCode, 32767, v < -STICK_DIGITAL_THRESHOLD)
    }

    private val dpadOwnHeld = Array(8) { HashSet<Int>() }

    private fun customTargetsDpad(port: Int): Boolean {
        for (isLeft in booleanArrayOf(true, false)) {
            if (ControllerMappings.stickModeFor(isLeft, port) != ControllerMappings.StickMode.CUSTOM) continue
            for (dir in ControllerMappings.StickDir.values())
                if (ControllerMappings.customStickCode(isLeft, dir, port) in 19..22) return true
        }
        return false
    }

    private fun dispatchDpadCombined(ev: MotionEvent, port: Int) {
        val held = dpadOwnHeld[port]
        val dpadAsStick = ControllerMappings.dpadAsLeftStick()
        val hatX = if (dpadAsStick) 0f else ev.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = if (dpadAsStick) 0f else ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val hatActive = hatX != 0f || hatY != 0f
        val leftDpad = false
        val rightDpad = false
        if (!hatActive && !leftDpad && !rightDpad && !customTargetsDpad(port)) {
            if (held.isNotEmpty()) {
                held.forEach { NativeApp.setPadButtonForPort(port, it, 0, false) }
                held.clear()
            }
            return
        }
        val dpRightBound = ControllerMappings.targetForPhysical(KeyEvent.KEYCODE_DPAD_RIGHT, port) != null
        val dpLeftBound = ControllerMappings.targetForPhysical(KeyEvent.KEYCODE_DPAD_LEFT, port) != null
        val dpDownBound = ControllerMappings.targetForPhysical(KeyEvent.KEYCODE_DPAD_DOWN, port) != null
        val dpUpBound = ControllerMappings.targetForPhysical(KeyEvent.KEYCODE_DPAD_UP, port) != null
        var right = hatX > 0.5f && dpRightBound
        var left = hatX < -0.5f && dpLeftBound
        var down = hatY > 0.5f && dpDownBound
        var up = hatY < -0.5f && dpUpBound

        fun foldStick(axisX: Int, axisY: Int) {
            val x = ev.getAxisValue(axisX)
            val y = ev.getAxisValue(axisY)
            right = right || x > STICK_DIGITAL_THRESHOLD
            left = left || x < -STICK_DIGITAL_THRESHOLD
            down = down || y > STICK_DIGITAL_THRESHOLD
            up = up || y < -STICK_DIGITAL_THRESHOLD
        }
        if (leftDpad) foldStick(MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
        if (rightDpad) foldStick(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)

        fun foldCustom(isLeft: Boolean, axisX: Int, axisY: Int) {
            if (ControllerMappings.stickModeFor(isLeft, port) != ControllerMappings.StickMode.CUSTOM) return
            val x = ev.getAxisValue(axisX)
            val y = ev.getAxisValue(axisY)
            fun mark(dir: ControllerMappings.StickDir, active: Boolean) {
                if (!active) return
                when (ControllerMappings.customStickCode(isLeft, dir, port)) {
                    22 -> right = true
                    21 -> left = true
                    20 -> down = true
                    19 -> up = true
                }
            }
            mark(ControllerMappings.StickDir.RIGHT, x > STICK_DIGITAL_THRESHOLD)
            mark(ControllerMappings.StickDir.LEFT, x < -STICK_DIGITAL_THRESHOLD)
            mark(ControllerMappings.StickDir.DOWN, y > STICK_DIGITAL_THRESHOLD)
            mark(ControllerMappings.StickDir.UP, y < -STICK_DIGITAL_THRESHOLD)
        }
        foldCustom(true, MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
        foldCustom(false, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)

        fun apply(code: Int, on: Boolean) {
            val was = held.contains(code)
            if (on == was) return
            NativeApp.setPadButtonForPort(port, code, if (on) 32767 else 0, on)
            if (on) held.add(code) else held.remove(code)
        }
        apply(22, right)
        apply(21, left)
        apply(20, down)
        apply(19, up)
    }

    private fun sendTrigger(event: MotionEvent, axisA: Int, axisB: Int, code: Int, port: Int) {
        val target = ControllerMappings.targetForPhysical(code, port) ?: return
        val raw = maxOf(event.getAxisValue(axisA), event.getAxisValue(axisB)).coerceIn(0f, 1f)
        val out = if (raw <= TRIGGER_DEAD) 0f else (raw - TRIGGER_DEAD) / (1f - TRIGGER_DEAD)
        if (target in 110..123) {
            accumAnalog(target, out)
        } else {
            NativeApp.setPadButtonForPort(port, target, (out * 32767).toInt(), out > 0f)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onPause() {
        if (eState.value == EmuState.RUNNING && !com.armsx2.WinNativeHost.enabled())
            pause()
        NativeApp.flushShaderCache()
        runCatching { NativeApp.dumpPgoProfile() }
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalLaunchIntent(intent)
    }

    override fun onDestroy() {
        if (isChangingConfigurations() || !isFinishing || instance !== this) {
            super.onDestroy()
            return
        }
        NativeApp.shutdown()
        com.winlator.cmod.shared.framegen.FrameGen.release()
        super.onDestroy()

        val appPid = Process.myPid()
        Process.killProcess(appPid)
    }

    private fun handleExternalLaunchIntent(intent: Intent?) {
        val uri = extractLaunchUri(intent) ?: return
        persistReadGrant(intent, uri)
        currentGame.value = null
        pendingExternalLaunch.value = uri.toString()
        launchPendingExternalGameIfReady()
    }

    private fun extractLaunchUri(intent: Intent?): Uri? {
        if (intent == null)
            return null

        intent.data?.let { return it }

        val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        stream?.let { return it }

        intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri?.let { return it }

        for (key in listOf("path", "game", "rom", "uri", "android.intent.extra.STREAM")) {
            val value = intent.getStringExtra(key)?.takeIf { it.isNotBlank() } ?: continue
            return value.toUri()
        }

        return null
    }

    private fun persistReadGrant(intent: Intent?, uri: Uri) {
        if (uri.scheme != "content" || intent == null)
            return

        val flags = intent.flags
        if ((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0 ||
            (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0)
            return

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
}
