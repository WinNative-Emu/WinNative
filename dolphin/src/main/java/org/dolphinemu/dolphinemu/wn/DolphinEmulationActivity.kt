package org.dolphinemu.dolphinemu.wn

import android.content.Context
import android.os.Bundle
import androidx.activity.addCallback
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import com.winlator.cmod.shared.framegen.FrameGen
import org.dolphinemu.dolphinemu.DolphinApplication
import org.dolphinemu.dolphinemu.NativeLibrary
import org.dolphinemu.dolphinemu.features.input.model.InputOverrider
import org.dolphinemu.dolphinemu.features.input.model.InputOverrider.ControlId
import org.dolphinemu.dolphinemu.utils.DirectoryInitialization
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

class DolphinEmulationActivity :
    androidx.activity.ComponentActivity(),
    SurfaceHolder.Callback,
    NativeLibrary.WnHost {
    companion object {
        private const val TAG = "WnDolphin"
        const val EXTRA_ROM_PATH = "dolphin_rom_path"
        const val EXTRA_SYSTEM_ID = "dolphin_system_id"
        const val EXTRA_GAME_NAME = "dolphin_game_name"
        const val EXTRA_VARIABLES = "dolphin_variables"
        const val EXTRA_VARIABLES_STRING = "dolphin_vars"
        const val EXTRA_SHORTCUT_PATH = "dolphin_shortcut_path"
        const val EXTRA_CONTAINER_ID = "dolphin_container_id"
        const val EXTRA_RA_ENABLED = "dolphin_ra_enabled"
        const val EXTRA_RA_USER = "dolphin_ra_user"
        const val EXTRA_RA_TOKEN = "dolphin_ra_token"
        const val EXTRA_RA_HARDCORE = "dolphin_ra_hardcore"

        @Volatile
        var currentVariables: Map<String, String> = emptyMap()
            private set

        @Volatile
        var currentGameId: String = ""
            private set

        @Volatile
        private var nativeLoadResult: Boolean? = null

        @Synchronized
        fun ensureNativeLibrary(context: Context): Boolean =
            nativeLoadResult ?: runCatching {
                System.load(File(context.filesDir, "retro/bundle/cores/libmain.so").absolutePath)
            }.isSuccess.also { nativeLoadResult = it }

        @Volatile
        private var emulationStarted = false

        @Volatile
        private var stopping = false

        @Volatile
        private var bootedInProcess = false

        @Volatile
        private var runThread: Thread? = null
    }

    private fun teardown() {
        if (stopping) return
        stopping = true
        runCatching { File(filesDir, "dolphin-embed/.running_rom").delete() }
        thread(name = "WnDolphinTeardown") {
            runCatching { DolphinNetplay.stop() }
            if (emulationStarted) {
                runCatching { NativeLibrary.StopEmulation() }
                runThread?.join(15000)
            }
            if (bootedInProcess) {
                runCatching { DolphinHost.onStageSaves?.invoke(this) }
                    .onFailure { Log.w(TAG, "stage saves failed", it) }
            }
            runOnUiThread { if (!isFinishing) finish() }
            Thread.sleep(250)
            Log.i(TAG, "teardown complete, ending :gc process")
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private var surfaceView: SurfaceView? = null
    private var isWii = false
    private var overridesRegistered = false

    private val invertPrefs by lazy {
        getSharedPreferences("${packageName}_preferences", android.content.Context.MODE_PRIVATE)
    }

    @Volatile
    private var surfaceLive = false

    private val customDriverActive: Boolean
        get() = !currentVariables["dolphin_gpu_driver_lib"].isNullOrBlank()

    private fun initialSurfaceParams(): android.widget.FrameLayout.LayoutParams {
        val lp =
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            )
        if (currentVariables["wn_touch"] != "0") {
            val bounds =
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    windowManager.currentWindowMetrics.bounds
                } else {
                    val dm = resources.displayMetrics
                    android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
                }
            val w = maxOf(bounds.width(), bounds.height())
            val h = minOf(bounds.width(), bounds.height())
            val gameWidth = (h * 4f / 3f).coerceAtMost(w.toFloat())
            lp.width = gameWidth.toInt()
            lp.height = h
            lp.leftMargin = ((w - gameWidth) * 0.5f).toInt()
        }
        return lp
    }

    private fun applyImmersiveMode() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            window.attributes = attrs
        }
        val controller =
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun stageGpuDriver(vars: Map<String, String>) {
        val packDir = vars["dolphin_gpu_driver"]?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val extracted = File(filesDir, "dolphin-embed/GpuDrivers/Extracted")
            extracted.mkdirs()
            File(packDir).listFiles()?.forEach { src ->
                if (!src.isFile) return@forEach
                val dst = File(extracted, src.name)
                if (!dst.isFile || dst.length() != src.length()) src.copyTo(dst, overwrite = true)
            }
            Log.i(TAG, "GPU driver staged from $packDir lib=${vars["dolphin_gpu_driver_lib"]}")
        }.onFailure { Log.w(TAG, "GPU driver staging failed: $it") }
    }

    private fun resolveVariables(): Map<String, String> {
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val map = (intent.getSerializableExtra(EXTRA_VARIABLES) as? HashMap<String, String>) ?: HashMap()
        intent.getStringExtra(EXTRA_VARIABLES_STRING)?.split(';')?.forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) map[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
        }
        return map
    }

    private fun initAchievements() {
        if (!intent.getBooleanExtra(EXTRA_RA_ENABLED, false)) return
        val user = intent.getStringExtra(EXTRA_RA_USER).orEmpty()
        val token = intent.getStringExtra(EXTRA_RA_TOKEN).orEmpty()
        if (user.isBlank() || token.isBlank()) return
        runCatching {
            val base = org.dolphinemu.dolphinemu.features.settings.model.NativeConfig.LAYER_BASE
            org.dolphinemu.dolphinemu.features.settings.model.NativeConfig
                .setBoolean(base, "RetroAchievements", "Achievements", "Enabled", true)
            org.dolphinemu.dolphinemu.features.settings.model.NativeConfig
                .setString(base, "RetroAchievements", "Achievements", "Username", user)
            org.dolphinemu.dolphinemu.features.settings.model.NativeConfig
                .setString(base, "RetroAchievements", "Achievements", "ApiToken", token)
            org.dolphinemu.dolphinemu.features.settings.model.NativeConfig.setBoolean(
                base, "RetroAchievements", "Achievements", "HardcoreEnabled",
                intent.getBooleanExtra(EXTRA_RA_HARDCORE, false),
            )
            org.dolphinemu.dolphinemu.features.settings.model.AchievementModel.init()
            thread(name = "WnDolphinRaLogin") {
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        org.dolphinemu.dolphinemu.features.settings.model.AchievementModel.asyncLogin("")
                    }
                }.onFailure { Log.w(TAG, "RA login failed", it) }
            }
        }.onFailure { Log.w(TAG, "RA init failed", it) }
    }

    private fun applySysconf(vars: Map<String, String>) {
        runCatching {
            org.dolphinemu.dolphinemu.features.settings.model.NativeConfig.setBoolean(
                org.dolphinemu.dolphinemu.features.settings.model.NativeConfig.LAYER_BASE,
                "SYSCONF",
                "IPL",
                "AR",
                DolphinConfigWriter.wiiWidescreen(vars),
            )
            org.dolphinemu.dolphinemu.features.settings.model.NativeConfig.setInt(
                org.dolphinemu.dolphinemu.features.settings.model.NativeConfig.LAYER_BASE,
                "SYSCONF",
                "BT",
                "BAR",
                if (DolphinConfigWriter.sensorBarTop(vars)) 1 else 0,
            )
        }.onFailure { Log.w(TAG, "sysconf apply failed: $it") }
    }

    private fun registerInputOverrides() {
        if (overridesRegistered || !NativeLibrary.IsRunning()) return
        overridesRegistered = true
        isWii = runCatching { NativeLibrary.IsEmulatingWiiUnchecked() }.getOrDefault(false) ||
            intent.getStringExtra(EXTRA_SYSTEM_ID) == "wii"
        InputOverrider.registerGameCube(0)
        InputOverrider.registerWii(0)
        runCatching { NativeLibrary.GetCurrentGameIDUnchecked() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { currentGameId = it }
        Log.i(TAG, "input overrides registered (wiiHint=$isWii)")
    }

    private fun unregisterInputOverrides() {
        if (!overridesRegistered) return
        overridesRegistered = false
        runCatching { InputOverrider.unregisterGameCube(0) }
        runCatching { InputOverrider.unregisterWii(0) }
    }

    private fun set(control: Int, state: Double) {
        if (overridesRegistered) InputOverrider.setControlState(0, control, state)
    }

    private fun gcButtonFor(keyCode: Int): Int? =
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> ControlId.GCPAD_A_BUTTON
            KeyEvent.KEYCODE_BUTTON_B -> ControlId.GCPAD_B_BUTTON
            KeyEvent.KEYCODE_BUTTON_X -> ControlId.GCPAD_X_BUTTON
            KeyEvent.KEYCODE_BUTTON_Y -> ControlId.GCPAD_Y_BUTTON
            KeyEvent.KEYCODE_BUTTON_START -> ControlId.GCPAD_START_BUTTON
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> ControlId.GCPAD_Z_BUTTON
            KeyEvent.KEYCODE_BUTTON_L2 -> ControlId.GCPAD_L_DIGITAL
            KeyEvent.KEYCODE_BUTTON_R2 -> ControlId.GCPAD_R_DIGITAL
            KeyEvent.KEYCODE_DPAD_UP -> ControlId.GCPAD_DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> ControlId.GCPAD_DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> ControlId.GCPAD_DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> ControlId.GCPAD_DPAD_RIGHT
            else -> null
        }

    private fun wiiButtonFor(keyCode: Int): Int? =
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> ControlId.WIIMOTE_A_BUTTON
            KeyEvent.KEYCODE_BUTTON_B -> ControlId.WIIMOTE_B_BUTTON
            KeyEvent.KEYCODE_BUTTON_X -> ControlId.WIIMOTE_ONE_BUTTON
            KeyEvent.KEYCODE_BUTTON_Y -> ControlId.WIIMOTE_TWO_BUTTON
            KeyEvent.KEYCODE_BUTTON_START -> ControlId.WIIMOTE_PLUS_BUTTON
            KeyEvent.KEYCODE_BUTTON_SELECT -> ControlId.WIIMOTE_MINUS_BUTTON
            KeyEvent.KEYCODE_BUTTON_MODE -> ControlId.WIIMOTE_HOME_BUTTON
            KeyEvent.KEYCODE_BUTTON_L1 -> ControlId.NUNCHUK_C_BUTTON
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_R2,
            -> ControlId.NUNCHUK_Z_BUTTON
            KeyEvent.KEYCODE_DPAD_UP -> ControlId.WIIMOTE_DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> ControlId.WIIMOTE_DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> ControlId.WIIMOTE_DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> ControlId.WIIMOTE_DPAD_RIGHT
            else -> null
        }

    @Volatile
    var menuButtonHandler: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        registerInputOverrides()
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE &&
            intent.getStringExtra(EXTRA_SYSTEM_ID) != "wii"
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) menuButtonHandler?.invoke()
            return true
        }
        val gc = gcButtonFor(event.keyCode)
        val wii = wiiButtonFor(event.keyCode)
        if ((gc != null || wii != null) && overridesRegistered) {
            val state = if (event.action == KeyEvent.ACTION_DOWN) 1.0 else 0.0
            gc?.let { set(it, state) }
            wii?.let { set(it, state) }
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
                set(ControlId.GCPAD_L_ANALOG, state)
            }
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
                set(ControlId.GCPAD_R_ANALOG, state)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        registerInputOverrides()
        if (!overridesRegistered ||
            event.action != MotionEvent.ACTION_MOVE ||
            event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK
        ) {
            return super.dispatchGenericMotionEvent(event)
        }
        val invSys = intent.getStringExtra(EXTRA_SYSTEM_ID) ?: "gc"
        val invLX = invertPrefs.getBoolean("retro_inv_lx_$invSys", false)
        val invLY = invertPrefs.getBoolean("retro_inv_ly_$invSys", false)
        val invRX = invertPrefs.getBoolean("retro_inv_rx_$invSys", false)
        val invRY = invertPrefs.getBoolean("retro_inv_ry_$invSys", false)
        val lx = event.getAxisValue(MotionEvent.AXIS_X).toDouble().let { if (invLX) -it else it }
        val ly = (-event.getAxisValue(MotionEvent.AXIS_Y).toDouble()).let { if (invLY) -it else it }
        val rx = event.getAxisValue(MotionEvent.AXIS_Z).toDouble().let { if (invRX) -it else it }
        val ry = (-event.getAxisValue(MotionEvent.AXIS_RZ).toDouble()).let { if (invRY) -it else it }
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)).toDouble()
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)).toDouble()
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        set(ControlId.GCPAD_MAIN_STICK_X, lx)
        set(ControlId.GCPAD_MAIN_STICK_Y, ly)
        set(ControlId.GCPAD_C_STICK_X, rx)
        set(ControlId.GCPAD_C_STICK_Y, ry)
        set(ControlId.GCPAD_L_ANALOG, lt)
        set(ControlId.GCPAD_R_ANALOG, rt)
        set(ControlId.GCPAD_L_DIGITAL, if (lt > 0.9) 1.0 else 0.0)
        set(ControlId.GCPAD_R_DIGITAL, if (rt > 0.9) 1.0 else 0.0)
        set(ControlId.GCPAD_DPAD_LEFT, if (hatX < -0.5f) 1.0 else 0.0)
        set(ControlId.GCPAD_DPAD_RIGHT, if (hatX > 0.5f) 1.0 else 0.0)
        set(ControlId.GCPAD_DPAD_UP, if (hatY < -0.5f) 1.0 else 0.0)
        set(ControlId.GCPAD_DPAD_DOWN, if (hatY > 0.5f) 1.0 else 0.0)
        set(ControlId.NUNCHUK_STICK_X, lx)
        set(ControlId.NUNCHUK_STICK_Y, ly)
        set(ControlId.WIIMOTE_IR_X, rx)
        set(ControlId.WIIMOTE_IR_Y, ry)
        set(ControlId.NUNCHUK_Z_BUTTON, if (rt > 0.5) 1.0 else 0.0)
        set(ControlId.NUNCHUK_C_BUTTON, if (lt > 0.5) 1.0 else 0.0)
        set(ControlId.WIIMOTE_DPAD_LEFT, if (hatX < -0.5f) 1.0 else 0.0)
        set(ControlId.WIIMOTE_DPAD_RIGHT, if (hatX > 0.5f) 1.0 else 0.0)
        set(ControlId.WIIMOTE_DPAD_UP, if (hatY < -0.5f) 1.0 else 0.0)
        set(ControlId.WIIMOTE_DPAD_DOWN, if (hatY > 0.5f) 1.0 else 0.0)
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DolphinApplication.install(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        FrameGen.installFromIntent(this, intent)
        FrameGen.applyDisplayMode(this)
        applyImmersiveMode()
        onBackPressedDispatcher.addCallback(this) {
            if (emulationStarted) {
                Log.i(TAG, "back pressed, tearing down")
                teardown()
            } else {
                finish()
            }
        }

        if (!ensureNativeLibrary(this)) {
            Log.e(TAG, "libmain failed to load")
            Toast.makeText(this, "Dolphin emucore not installed — download the console cores in Settings › Retro", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val romPath = intent.getStringExtra(EXTRA_ROM_PATH)
        if (romPath.isNullOrBlank() || !File(romPath).isFile) {
            Log.e(TAG, "ROM not found: $romPath")
            Toast.makeText(this, "ROM not found", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        NativeLibrary.host = this
        if (!emulationStarted) {
            val vars = resolveVariables()
            currentVariables = vars
            emulationStarted = true
            stopping = false
            bootedInProcess = true
            runThread =
                thread(name = "WnDolphinRun") {
                    val userDir = DirectoryInitialization.ensureDirectories(this)
                    DirectoryInitialization.writeBaseConfig(userDir)
                    stageGpuDriver(vars)
                    DolphinConfigWriter.write(userDir, vars)
                    NativeLibrary.SetUserDirectory(userDir.path)
                    NativeLibrary.SetCacheDirectory(File(cacheDir, "dolphin-embed").also { it.mkdirs() }.path)
                    NativeLibrary.Initialize()
                    NativeLibrary.ReloadConfig()
                    applySysconf(vars)
                    initAchievements()
                    runCatching { File(filesDir, "dolphin-embed/.running_rom").writeText(romPath) }
                    val netplay = DolphinNetplay.fromIntent(intent)
                    if (netplay != null) {
                        Log.i(TAG, "Netplay ${if (netplay.host) "host" else "join"} boot $romPath")
                        val bootData = DolphinNetplay.startAndAwaitBoot(this, romPath, netplay)
                        if (bootData == 0L) {
                            Log.w(TAG, "Netplay boot aborted")
                            emulationStarted = false
                            teardown()
                            return@thread
                        }
                        NativeLibrary.RunNetPlay(arrayOf(romPath), false, bootData)
                    } else {
                        Log.i(TAG, "Booting $romPath vars=${vars.size}")
                        NativeLibrary.Run(arrayOf(romPath), false)
                    }
                    Log.i(TAG, "Run() returned")
                    emulationStarted = false
                    teardown()
                }
        } else {
            Log.i(TAG, "Emulation already running, reattaching surface")
            registerInputOverrides()
        }

        val view = SurfaceView(this)
        view.holder.addCallback(this)
        surfaceView = view
        val container = android.widget.FrameLayout(this)
        container.addView(view, initialSurfaceParams())
        setContentView(container)
        window.decorView.post {
            if (!isFinishing && !isDestroyed) {
                Log.i(TAG, "attachOverlay hook present=${DolphinHost.attachOverlay != null}")
                runCatching { DolphinHost.attachOverlay?.invoke(this) }
                    .onFailure { Log.e(TAG, "overlay attach failed", it) }
            }
        }
    }

    fun sendOverlayButton(keyCode: Int, down: Boolean) {
        registerInputOverrides()
        val state = if (down) 1.0 else 0.0
        gcButtonFor(keyCode)?.let { set(it, state) }
        wiiButtonFor(keyCode)?.let { set(it, state) }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) set(ControlId.GCPAD_L_ANALOG, state)
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) set(ControlId.GCPAD_R_ANALOG, state)
    }

    fun sendOverlayLeftStick(x: Float, y: Float) {
        registerInputOverrides()
        set(ControlId.GCPAD_MAIN_STICK_X, x.toDouble())
        set(ControlId.GCPAD_MAIN_STICK_Y, -y.toDouble())
        set(ControlId.NUNCHUK_STICK_X, x.toDouble())
        set(ControlId.NUNCHUK_STICK_Y, -y.toDouble())
    }

    fun sendOverlayRightStick(x: Float, y: Float) {
        registerInputOverrides()
        set(ControlId.GCPAD_C_STICK_X, x.toDouble())
        set(ControlId.GCPAD_C_STICK_Y, -y.toDouble())
        set(ControlId.WIIMOTE_IR_X, x.toDouble())
        set(ControlId.WIIMOTE_IR_Y, -y.toDouble())
    }

    fun sendOverlayDpad(x: Float, y: Float) {
        registerInputOverrides()
        set(ControlId.GCPAD_DPAD_LEFT, if (x < -0.5f) 1.0 else 0.0)
        set(ControlId.GCPAD_DPAD_RIGHT, if (x > 0.5f) 1.0 else 0.0)
        set(ControlId.GCPAD_DPAD_UP, if (y < -0.5f) 1.0 else 0.0)
        set(ControlId.GCPAD_DPAD_DOWN, if (y > 0.5f) 1.0 else 0.0)
        set(ControlId.WIIMOTE_DPAD_LEFT, if (x < -0.5f) 1.0 else 0.0)
        set(ControlId.WIIMOTE_DPAD_RIGHT, if (x > 0.5f) 1.0 else 0.0)
        set(ControlId.WIIMOTE_DPAD_UP, if (y < -0.5f) 1.0 else 0.0)
        set(ControlId.WIIMOTE_DPAD_DOWN, if (y > 0.5f) 1.0 else 0.0)
    }

    val emulationRunning: Boolean
        get() = emulationStarted && NativeLibrary.IsRunning()

    fun pauseGame() {
        if (emulationRunning && NativeLibrary.IsRunningAndUnpaused()) NativeLibrary.PauseEmulation()
    }

    fun resumeGame() {
        if (emulationRunning && !NativeLibrary.IsRunningAndUnpaused()) NativeLibrary.UnPauseEmulation()
    }

    val gamePaused: Boolean
        get() = emulationRunning && !NativeLibrary.IsRunningAndUnpaused()

    fun saveStateSlot(slot: Int) {
        if (emulationRunning) NativeLibrary.SaveState(slot, false)
    }

    fun loadStateSlot(slot: Int) {
        if (emulationRunning) NativeLibrary.LoadState(slot)
    }

    fun stateSlotTime(slot: Int): Long =
        if (emulationRunning) runCatching { NativeLibrary.GetUnixTimeOfStateSlot(slot) }.getOrDefault(0L) else 0L

    fun stopGame() {
        teardown()
    }

    fun pressWiiHome() {
        registerInputOverrides()
        if (!overridesRegistered) return
        set(ControlId.WIIMOTE_HOME_BUTTON, 1.0)
        window.decorView.postDelayed({ set(ControlId.WIIMOTE_HOME_BUTTON, 0.0) }, 120)
    }

    fun applyVariable(key: String, value: String) {
        val merged = currentVariables.toMutableMap()
        merged[key] = value
        currentVariables = merged
        thread(name = "WnDolphinApply") {
            runCatching {
                DolphinConfigWriter.write(DirectoryInitialization.userDir(this), merged)
                NativeLibrary.ReloadConfig()
                applySysconf(merged)
            }.onFailure { Log.w(TAG, "applyVariable failed: $it") }
        }
    }

    fun setSurfaceBounds(bounds: android.graphics.RectF?) {
        if (surfaceLive && customDriverActive) return
        runOnUiThread {
            val view = surfaceView ?: return@runOnUiThread
            val lp = view.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return@runOnUiThread
            if (bounds == null) {
                lp.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                lp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                lp.leftMargin = 0
                lp.topMargin = 0
            } else {
                lp.width = bounds.width().toInt()
                lp.height = bounds.height().toInt()
                lp.leftMargin = bounds.left.toInt()
                lp.topMargin = bounds.top.toInt()
            }
            view.layoutParams = lp
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceLive = true
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        Log.i(TAG, "surfaceChanged ${width}x$height")
        bindSurface(holder, width, height)
        FrameGen.setSurfaceRebinder { bindSurface(holder, width, height) }
    }

    private fun bindSurface(
        holder: SurfaceHolder,
        width: Int,
        height: Int,
    ) {
        val output = holder.surface ?: return
        if (!output.isValid) return
        NativeLibrary.SurfaceChanged(FrameGen.wrap(output, width, height) ?: output)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceDestroyed")
        FrameGen.setSurfaceRebinder(null)
        NativeLibrary.SurfaceDestroyed()
        FrameGen.release()
    }

    override fun onResume() {
        super.onResume()
        if (emulationStarted && !stopping && NativeLibrary.IsRunning()) {
            NativeLibrary.UnPauseEmulation()
        }
    }

    override fun onPause() {
        super.onPause()
        if (emulationStarted && !stopping && NativeLibrary.IsRunningAndUnpaused()) {
            NativeLibrary.PauseEmulation()
        }
    }

    override fun onDestroy() {
        unregisterInputOverrides()
        if (isFinishing && bootedInProcess) {
            teardown()
        }
        NativeLibrary.host = null
        super.onDestroy()
    }

    override fun onToast(message: String, long: Boolean) {
        Log.i(TAG, "toast: $message")
        runOnUiThread {
            Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAlert(
        caption: String,
        text: String,
        yesNo: Boolean,
        isWarning: Boolean,
        nonBlocking: Boolean,
    ): Boolean {
        Log.w(TAG, "alert [$caption] $text")
        return true
    }

    override fun onUpdateTouchPointer() {}

    override fun onTitleChanged() {
        Log.i(TAG, "title: " + runCatching { NativeLibrary.GetCurrentTitleDescriptionUnchecked() }.getOrDefault(""))
        runCatching { NativeLibrary.GetCurrentGameIDUnchecked() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { currentGameId = it }
        runOnUiThread { registerInputOverrides() }
    }

    override fun onEmulationFinished() {
        Log.i(TAG, "emulation finished")
        runOnUiThread { if (!isFinishing) finish() }
    }
}
