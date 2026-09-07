package com.winlator.cmod.feature.retro

import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.RectF
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import com.swordfish.libretrodroid.ViewportAlignment
import com.winlator.cmod.R
import com.winlator.cmod.feature.sync.google.GameSaveBackupManager
import com.winlator.cmod.feature.sync.google.GoogleAuthMode
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.display.ui.FrameRating
import com.winlator.cmod.runtime.input.controls.ExternalController
import com.winlator.cmod.shared.android.FixedFontScaleAppCompatActivity
import com.winlator.cmod.shared.framegen.FrameGen
import com.winlator.cmod.shared.framegen.FrameGenGlSurface
import com.winlator.cmod.shared.theme.WinNativeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.math.abs

class RetroActivity : FixedFontScaleAppCompatActivity(), RetroInputView.Listener {
    companion object {
        const val EXTRA_ROM_PATH = "retro_rom_path"
        const val EXTRA_SYSTEM_ID = "retro_system_id"
        const val EXTRA_GAME_NAME = "retro_game_name"
        const val EXTRA_SHORTCUT_PATH = "retro_shortcut_path"
        const val EXTRA_CONTAINER_ID = "retro_container_id"
        const val EXTRA_SHADER = "retro_shader"
        const val EXTRA_TOUCH_CONTROLS = "retro_touch_controls"
        const val EXTRA_ADAPTIVE_STICKS = "retro_adaptive_sticks"
        const val EXTRA_AUDIO = "retro_audio"
        const val EXTRA_HUD = "retro_hud"
        const val EXTRA_VARIABLES = "retro_variables"

        const val EXTRA_UPSCALE = "retro_upscale"
        const val EXTRA_SGSR = "retro_sgsr"

        private val SHADER_KEYS = listOf("default", "crt", "lcd", "sharp")
        private val SHADER_LABEL_RES =
            listOf(R.string.retro_lr_shader_default, R.string.retro_lr_shader_crt, R.string.retro_lr_shader_lcd, R.string.retro_lr_shader_sharp)
        private val UPSCALE_KEYS = listOf("2x", "4x", "native")
    }

    private lateinit var retroView: GLRetroView
    private var overlay: RetroInputView? = null
    private val menu = RetroMenuController()
    private var retroReady = false
    private var gameName = "game"
    private var fastForward = false
    private var audioEnabledSetting = true
    private var touchControlsSetting = true
    private var adaptiveSticksSetting = false
    private var currentShaderKey = "default"
    private var sgsrEnabled = false
    private var coreVars = HashMap<String, String>()
    private var diskCount = 0
    private var currentDisk = 0
    private var system: RetroSystem? = null
    private var persistShortcut: Shortcut? = null
    private var playtimePrefs: SharedPreferences? = null
    private var sessionStart = 0L
    private var emulationPaused = false
    private var controllerConnected = false
    private var manualTouchOverride = false
    private var inputManager: InputManager? = null
    private var hudVisible = false
    private var currentUpscaleKey = "native"
    private var hudAlpha = 1f
    private var hudBgDecoupled = false
    private var hudBgAlpha = 1f
    private var hudScale = 1f
    private var hudElements = booleanArrayOf(true, true, true, true, true, true, true, true, false)
    private var hudFrametimeNumeric = false
    private var hudDualBattery = false
    private var frameRating: FrameRating? = null
    private val biosPicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                RetroBiosImport.importFromUri(this, uri)
                    .onSuccess {
                        Toast.makeText(this, getString(R.string.retro_lr_bios_imported, it), Toast.LENGTH_SHORT).show()
                        recreate()
                    }
                    .onFailure {
                        Toast.makeText(this, it.message ?: getString(R.string.retro_lr_invalid_bios_file), Toast.LENGTH_LONG).show()
                    }
            } else {
                finish()
            }
        }
    private var rootLayout: FrameLayout? = null
    private var menuComposeView: ComposeView? = null
    private var surfaceReady = false
    private var coreRefreshRate = 0f
    private var customColors = RetroCustomColors()
    private var savesLoadMode = false
    private var achievementsSessionStarted = false
    private var cloudSyncEnabled = false
    private var conflictChecked = false
    private var retroCloudId = ""
    private var netplayLocalPort = 0
    private var netplayArmedThisSession = false

    private val isPortrait: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    private val gameAspect: Float
        get() =
            when (system?.id) {
                RetroSystems.GAMEBOY.id, RetroSystems.GAMEBOY_COLOR.id -> 10f / 9f
                RetroSystems.GBA.id -> 3f / 2f
                else -> 4f / 3f
            }

    private fun overlayPush(): Float = 0f

    private fun updateOverlayArea() {
        val rootWidth = rootLayout?.width ?: 0
        val rootHeight = rootLayout?.height ?: 0
        if (rootWidth <= 0 || rootHeight <= 0) return
        val push = overlayPush()
        val area =
            if (isPortrait) {
                val top = push * rootHeight
                val gameHeight = rootWidth / gameAspect
                RectF(0f, top, rootWidth.toFloat(), (top + gameHeight).coerceAtMost(rootHeight.toFloat()))
            } else {
                val availHeight = rootHeight * (1f - push)
                val gameWidth = (availHeight * gameAspect).coerceAtMost(rootWidth.toFloat())
                val left = (rootWidth - gameWidth) * 0.5f
                val gameHeight = gameWidth / gameAspect
                val top = push * rootHeight + (availHeight - gameHeight) * 0.5f
                RectF(left, top, left + gameWidth, top + gameHeight)
            }
        overlay?.setGameArea(area)
    }

    private fun applyDisplayGeometry() {
        updateOverlayArea()
        if (!surfaceReady || !retroReady) return
        retroView.viewportAlignment = if (isPortrait) ViewportAlignment.TOP else ViewportAlignment.CENTER
        retroView.viewport = RectF(0f, overlayPush(), 1f, 1f)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.releaseAll()
        rootLayout?.post {
            overlay?.relayout()
            applyDisplayGeometry()
        }
    }

    private val inputDeviceListener =
        object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = refreshControllerPresence()

            override fun onInputDeviceRemoved(deviceId: Int) = refreshControllerPresence()

            override fun onInputDeviceChanged(deviceId: Int) = refreshControllerPresence()
        }

    private val stickIsAnalog: Boolean
        get() = system?.id == RetroSystems.N64.id || RetroCoreManager.usesDolphinCore(system)

    private fun anyGameControllerConnected(): Boolean =
        InputDevice.getDeviceIds().any { id ->
            ExternalController.isGameController(InputDevice.getDevice(id))
        }

    private fun refreshControllerPresence() {
        val wasConnected = controllerConnected
        controllerConnected = anyGameControllerConnected()
        if (controllerConnected != wasConnected) manualTouchOverride = false
        updateOverlayVisibility()
        syncInGameOverlayPlacement()
    }

    private val touchControlsEffective: Boolean
        get() = touchControlsSetting && (!controllerConnected || manualTouchOverride)

    private fun updateOverlayVisibility() {
        overlay?.visibility =
            if (overlay?.editMode == true || touchControlsEffective) View.VISIBLE else View.GONE
    }

    private fun pauseEmulation() {
        if (emulationPaused || !retroReady) return
        emulationPaused = true
        retroView.onPause()
        LibretroDroid.pause()
        RetroAchievementsManager.idle()
    }

    private fun resumeEmulation() {
        if (!emulationPaused || !retroReady) return
        emulationPaused = false
        LibretroDroid.resume()
        retroView.onResume()
    }

    private fun startAchievementsSession() {
        if (achievementsSessionStarted) return
        val sys = system ?: return
        val rom = intent.getStringExtra(EXTRA_ROM_PATH) ?: return
        if (!RetroAchievementsManager.isEnabled(this) || !RetroAchievementsManager.isLoggedIn(this)) return
        achievementsSessionStarted = true
        RetroAchievementsManager.unlockListener = { unlock ->
            runOnUiThread { showAchievementUnlock(unlock) }
        }
        syncInGameOverlayPlacement()
        RetroAchievementsManager.resetListener = {
            runOnUiThread {
                if (retroReady) retroView.reset()
                RetroAchievementsManager.onEmulatorReset()
            }
        }
        RetroAchievementsManager.hardcoreNoticeListener = { message ->
            runOnUiThread { showInGameMessage(message) }
        }
        RetroAchievementsManager.startSession(this, sys.id, rom)
    }

    private fun syncInGameOverlayPlacement() {
        RetroAchievementOverlayState.syncPlacement(
            touchControlsVisible = touchControlsEffective,
            controllerConnected = controllerConnected,
        )
    }

    private fun showInGameMessage(message: String) {
        syncInGameOverlayPlacement()
        if (RetroAchievementOverlayState.useDisplayArea) {
            RetroAchievementOverlayState.showMessage(message)
        } else {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAchievementUnlock(unlock: RetroUnlock) {
        syncInGameOverlayPlacement()
        RetroAchievementOverlayState.show(unlock.title, unlock.points, unlock.description)
    }

    fun cheatsAllowed(): Boolean = !(achievementsSessionStarted && RetroAchievementsManager.isHardcoreActive())

    private fun applyCheats() {
        if (!retroReady) return
        runCatching { retroView.resetCheat() }
        if (!cheatsAllowed()) return
        val enabled = RetroCheats.load(this, gameName).filter { it.enabled }
        if (enabled.isEmpty()) return
        enabled.forEachIndexed { index, cheat ->
            runCatching { retroView.setCheat(index, true, cheat.code) }
        }
        if (achievementsSessionStarted) {
            RetroAchievementsManager.endSession()
            achievementsSessionStarted = false
            Toast.makeText(this, getString(R.string.retro_lr_cheats_enabled_achievements_disabled), Toast.LENGTH_LONG).show()
        }
    }

    private fun openCheatsScreen() {
        val sys = system ?: return
        startActivity(
            android.content.Intent(this, RetroCheatsActivity::class.java).apply {
                putExtra(RetroCheatsActivity.EXTRA_SYSTEM_ID, sys.id)
                putExtra(RetroCheatsActivity.EXTRA_GAME_NAME, gameName)
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val romPath = intent.getStringExtra(EXTRA_ROM_PATH)
        val systemId = intent.getStringExtra(EXTRA_SYSTEM_ID)
        gameName = intent.getStringExtra(EXTRA_GAME_NAME) ?: "game"
        val resolvedSystem = RetroSystems.fromId(systemId)
        system = resolvedSystem

        if (romPath.isNullOrBlank() || resolvedSystem == null) {
            Toast.makeText(this, getString(R.string.retro_lr_invalid_retro_game), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val sourceFile = File(romPath)
        if (!sourceFile.isFile) {
            Toast.makeText(this, getString(R.string.retro_lr_rom_not_found, romPath), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val romFile =
            if (RetroRomArchive.isArchive(romPath)) {
                RetroRomArchive.extractTo(this, romPath) ?: run {
                    Toast.makeText(this, getString(R.string.retro_lr_could_not_read_rom_archive), Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
            } else {
                sourceFile
            }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER

        netplayArmedThisSession = RetroDefaults.netplayEnabled(this, resolvedSystem.id)
        if (!netplayArmedThisSession && RetroNetplayLobby.isInRoomSession()) {
            RetroNetplayLobby.leave(silent = true)
        }
        val wantGbaMulti =
            resolvedSystem.id == RetroSystems.GBA.id &&
                netplayArmedThisSession &&
                (
                    RetroDefaults.netplayLaunchMode(this, resolvedSystem.id) == "host" ||
                        RetroDefaults.netplayLaunchMode(this, resolvedSystem.id) == "join" ||
                        RetroNetplayLobby.isGameLink ||
                        RetroNetplayLobby.isInRoomSession()
                )
        val coreFile =
            if (wantGbaMulti) {
                RetroCoreManager.multiplayerCoreFile(this, resolvedSystem)
            } else {
                RetroCoreManager.coreFile(this, resolvedSystem)
            }
        if (!coreFile.isFile) {
            val name =
                if (wantGbaMulti) RetroSystems.GBA_MULTIPLAYER_CORE else resolvedSystem.coreFileName
            val message =
                if (RetroBundle.isInstalled(this)) {
                    getString(R.string.retro_lr_core_not_installed, name)
                } else {
                    getString(R.string.retro_bundle_required)
                }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (RetroCoreManager.missingBios(this, resolvedSystem)) {
            showBiosRequiredDialog(resolvedSystem)
            return
        }

        if (resolvedSystem.id == RetroSystems.N64.id) RetroCoreManager.ensureGlideN64Ini(this)
        if (RetroCoreManager.usesDolphinCore(resolvedSystem)) {
            RetroCoreManager.ensureDolphinSys(this)
            RetroCoreManager.ensureDolphinUser(this)
        }
        val savesDir = RetroCoreManager.savesDir(this)
        RetroSaveStates.migrateLegacy(this, gameName)
        RetroSaveStates.recordIdentity(this, gameName, resolvedSystem.id)
        val sramFile = RetroSaveStates.sramFile(this, gameName)
        currentShaderKey = intent.getStringExtra(EXTRA_SHADER)?.lowercase() ?: "default"
        sgsrEnabled = intent.getBooleanExtra(EXTRA_SGSR, false)
        if (currentShaderKey == "sgsr") {
            currentShaderKey = "default"
            sgsrEnabled = true
        }
        if (currentShaderKey !in SHADER_KEYS) currentShaderKey = "default"
        currentUpscaleKey = intent.getStringExtra(EXTRA_UPSCALE)?.lowercase() ?: "native"
        if (currentUpscaleKey !in UPSCALE_KEYS) currentUpscaleKey = "native"
        audioEnabledSetting = intent.getBooleanExtra(EXTRA_AUDIO, true)
        touchControlsSetting = intent.getBooleanExtra(EXTRA_TOUCH_CONTROLS, true)
        adaptiveSticksSetting = intent.getBooleanExtra(EXTRA_ADAPTIVE_STICKS, false)
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        coreVars = (intent.getSerializableExtra(EXTRA_VARIABLES) as? HashMap<String, String>) ?: HashMap()
        RetroCoreOptions.defaultVariables(resolvedSystem).forEach { (key, value) ->
            if (!coreVars.containsKey(key)) coreVars[key] = value
        }
        if (RetroCoreManager.usesDolphinCore(resolvedSystem)) {
            RetroCoreOptions.sanitizeDolphinVariables(coreVars)
        }

        val root = FrameLayout(this)
        rootLayout = root

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (retroReady) {
                    if (menu.visible) {
                        menu.close()
                        return
                    }
                    overlay?.releaseAll()
                    menu.open()
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, callback)

        val inputView = RetroInputView(this, this, resolvedSystem)
        inputView.loadStickInversion()
        inputView.hapticStrength =
            androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
                .getFloat("retro_haptic_strength", 0.4f)
        inputView.adaptiveSticks = adaptiveSticksSetting
        inputView.showL3R3 =
            androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
                .getBoolean(RetroControlsMenu.l3r3PrefKey(resolvedSystem.id), true)
        inputView.shellBackground = RetroControlLayouts.shellBackground(this, resolvedSystem.id)
        customColors = RetroControlLayouts.loadColors(this, resolvedSystem.id)
        inputView.setCustomColors(customColors)
        inputView.onEditStateChanged = { editing ->
            runOnUiThread {
                frameRating?.visibility =
                    if (!editing && hudVisible) View.VISIBLE else View.GONE
                updateOverlayVisibility()
                menu.rebuild()
            }
        }
        overlay = inputView
        root.addView(
            inputView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayArea() }

        menu.entriesProvider = { pane -> buildEntriesFor(pane) }
        menu.paneContentProvider = { pane ->
            if (pane == RetroPane.FRAMEGEN) {
                {
                    RetroFrameGenPane.Content(
                        this,
                        loadShortcut(),
                        intent.getStringExtra(EXTRA_SYSTEM_ID),
                    )
                }
            } else {
                null
            }
        }
        menu.bottomProvider = { buildBottomEntries() }
        val coreOpts = RetroCoreOptions.forSystem(system)
        menu.tabs =
            RetroDrawerTabs.build(
                this,
                includePerformance = coreOpts.any { it.category == RetroOptionCategory.PERFORMANCE },
                includeNetplay = RetroOnlineSupport.supportsMultiplayerUi(system?.id),
                includeSystem = coreOpts.any { it.category == RetroOptionCategory.SYSTEM },
            )
        hudVisible = intent.getBooleanExtra(EXTRA_HUD, false)
        RetroNetplayLobby.bindLocalName(this)
        val menuView =
            ComposeView(this).apply {
                elevation = 2000f
                setContent {
                    WinNativeTheme {
                        Box(Modifier.fillMaxSize()) {
                            syncInGameOverlayPlacement()
                            RetroAchievementOverlayBanner()
                            val netPhase = RetroNetplayLobby.phase
                            val netMembers = RetroNetplayLobby.members
                            val netDiscovered = RetroNetplayLobby.discovered
                            val netStatus = RetroNetplayLobby.statusText
                            LaunchedEffect(netPhase, netMembers, netDiscovered, netStatus) {
                                if (menu.visible && menu.pane == RetroPane.NETWORK) {
                                    menu.rebuild()
                                }
                            }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(top = 56.dp),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                RetroNetplayRoomBanner(
                                    onLeave = { leaveNetplayRoom() },
                                    onJoinRoom = { room -> joinDiscoveredRoom(room) },
                                )
                            }
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                RetroNetplayEventToast(
                                    Modifier.padding(bottom = 72.dp),
                                )
                            }
                            RetroDrawerMenu(menu)
                        }
                    }
                }
            }
        menuComposeView = menuView
        root.addView(
            menuView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        setContentView(root)
        inputManager = getSystemService(InputManager::class.java)
        inputManager?.registerInputDeviceListener(inputDeviceListener, null)
        refreshControllerPresence()
        retroReady = false
        if (hudVisible) {
            root.post {
                if (!isFinishing && !isDestroyed && hudVisible) showHud()
            }
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (overlay?.editMode == true) {
                        overlay?.finishEdit()
                    } else if (menu.visible) {
                        menu.handleKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)
                    } else {
                        openMenu()
                    }
                }
            },
        )
        loadHudSettings()
        recordLaunchStats()

        FrameGen.installFromIntent(this, intent)
        val frameGenOn = FrameGen.requested
        val frameGenRate = if (frameGenOn) FrameGen.applyDisplayMode(this) else 0f
        val sixtyRequested = if (frameGenOn) false else requestSixtyHzDisplayMode()
        var waitAttempts = 0
        lateinit var startWhenReady: Runnable
        startWhenReady =
            Runnable {
                if (isFinishing || isDestroyed) return@Runnable
                val rate =
                    runCatching { windowManager.defaultDisplay.refreshRate }.getOrDefault(60f)
                if (sixtyRequested && abs(rate - 60f) > 2f && waitAttempts < 12) {
                    waitAttempts++
                    root.postDelayed(startWhenReady, 100)
                    return@Runnable
                }
                if (frameGenRate > 1f && abs(rate - frameGenRate) > 2f && waitAttempts < 12) {
                    waitAttempts++
                    root.postDelayed(startWhenReady, 100)
                    return@Runnable
                }
                val data =
                    GLRetroViewData(this).apply {
                        coreFilePath = coreFile.absolutePath
                        gameFilePath = romFile.absolutePath
                        systemDirectory = RetroCoreManager.systemDir(this@RetroActivity).absolutePath
                        savesDirectory = savesDir.absolutePath
                        shader = effectiveShader()
                        variables = coreVars.map { Variable(it.key, it.value) }.toTypedArray()
                        rumbleEventsEnabled = true
                        preferLowLatencyAudio = !RetroCoreManager.usesDolphinCore(resolvedSystem)
                        skipDuplicateFrames = false
                        viewportAlignment =
                            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                                ViewportAlignment.TOP
                            } else {
                                ViewportAlignment.CENTER
                            }
                        if (sramFile.isFile) saveRAMState = runCatching { sramFile.readBytes() }.getOrNull()
                    }
                val view = GLRetroView(this, data)
                coreRefreshRate = rate
                if (frameGenOn) FrameGenGlSurface.attach(view, rate)
                FrameGen.setSurfaceRebinder {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && FrameGenGlSurface.attach(view, coreRefreshRate)) {
                            view.onPause()
                            view.onResume()
                        }
                    }
                }
                if (!frameGenOn && android.os.Build.VERSION.SDK_INT >= 30) {
                    view.holder.addCallback(
                        object : android.view.SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                runCatching {
                                    holder.surface.setFrameRate(
                                        60f,
                                        android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                                    )
                                }
                            }

                            override fun surfaceChanged(
                                holder: android.view.SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) {}

                            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
                        },
                    )
                }
                retroView = view
                root.addView(
                    view,
                    0,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                lifecycle.addObserver(view)
                retroReady = true
                refreshControllerPresence()
                observeErrors()
                observeEvents()
                scheduleCloudConflictCheck()
            }
        retroCloudId =
            intent.getStringExtra(EXTRA_SHORTCUT_PATH)?.let { path ->
                GameSaveBackupManager.customGameId(intent.getIntExtra(EXTRA_CONTAINER_ID, 0), File(path).name)
            } ?: RetroSaveStates.cloudGameId(resolvedSystem.id, gameName)
        lifecycleScope.launch(Dispatchers.IO) {
            cloudSyncEnabled =
                runCatching { loadShortcut()?.getExtra("cloud_sync_enabled", "1") != "0" }.getOrDefault(true)
            if (cloudSyncEnabled && !RetroSaveStates.sramFile(this@RetroActivity, gameName).isFile) {
                runCatching {
                    withTimeout(12_000L) {
                        val entries =
                            GameSaveBackupManager.listGoogleHistory(
                                this@RetroActivity,
                                GameSaveBackupManager.GameSource.CUSTOM,
                                retroCloudId,
                                GoogleAuthMode.RESUME,
                            )
                        val latest = entries.maxByOrNull { it.timestampMs }
                        if (latest != null) {
                            val result =
                                GameSaveBackupManager.restoreFromGoogle(
                                    this@RetroActivity,
                                    latest,
                                    GameSaveBackupManager.GameSource.CUSTOM,
                                    retroCloudId,
                                    GoogleAuthMode.RESUME,
                                    customSaveDir = RetroSaveStates.gameDir(this@RetroActivity, gameName),
                                )
                            if (result.success) {
                                RetroSaveStates.migrateLegacyCloudLayout(this@RetroActivity, gameName)
                                setCloudMark(latest.timestampMs)
                                runOnUiThread {
                                    Toast
                                        .makeText(this@RetroActivity, getString(R.string.retro_lr_cloud_save_restored), Toast.LENGTH_SHORT)
                                        .show()
                                }
                            }
                        }
                    }
                }
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) root.post(startWhenReady)
            }
        }
    }

    private fun scheduleCloudConflictCheck() {
        if (!cloudSyncEnabled || conflictChecked) return
        conflictChecked = true
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val sram = RetroSaveStates.sramFile(this@RetroActivity, gameName)
                val localTs = if (sram.isFile) sram.lastModified() else 0L
                if (localTs <= 0L) return@runCatching
                val entries =
                    GameSaveBackupManager.listGoogleHistory(
                        this@RetroActivity,
                        GameSaveBackupManager.GameSource.CUSTOM,
                        retroCloudId,
                        GoogleAuthMode.RESUME,
                    )
                val mark = cloudMark()
                val newer =
                    entries
                        .filter { it.timestampMs > localTs + 120_000L && it.timestampMs > mark }
                        .sortedByDescending { it.timestampMs }
                if (newer.isEmpty()) return@runCatching
                val top = newer.take(5)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    pauseEmulation()
                    menu.conflictPrompt =
                        RetroConflictPrompt(
                            message = getString(R.string.retro_lr_cloud_conflict_message, gameName),
                            options =
                                top.map { entry ->
                                    (entry.label ?: getString(R.string.retro_lr_cloud_save)) + " — " +
                                        RetroSaveStates.relativeTime(entry.timestampMs)
                                },
                            onKeepLocal = {
                                setCloudMark(top.first().timestampMs)
                                menu.conflictPrompt = null
                                resumeEmulation()
                            },
                            onPick = { index ->
                                menu.conflictPrompt = null
                                restoreCloudEntry(top[index])
                            },
                        )
                }
            }
        }
    }

    private fun restoreCloudEntry(entry: GameSaveBackupManager.BackupHistoryEntry) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result =
                runCatching {
                    GameSaveBackupManager.restoreFromGoogle(
                        this@RetroActivity,
                        entry,
                        GameSaveBackupManager.GameSource.CUSTOM,
                        retroCloudId,
                        GoogleAuthMode.INTERACTIVE,
                        customSaveDir = RetroSaveStates.gameDir(this@RetroActivity, gameName),
                    )
                }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result?.success == true) {
                    RetroSaveStates.migrateLegacyCloudLayout(this@RetroActivity, gameName)
                    setCloudMark(entry.timestampMs)
                    runCatching {
                        val sram = RetroSaveStates.sramFile(this@RetroActivity, gameName)
                        if (sram.isFile) retroView.unserializeSRAM(sram.readBytes())
                    }
                    Toast.makeText(this@RetroActivity, getString(R.string.retro_lr_cloud_save_restored), Toast.LENGTH_SHORT).show()
                } else {
                    Toast
                        .makeText(this@RetroActivity, result?.message ?: getString(R.string.retro_lr_restore_failed), Toast.LENGTH_SHORT)
                        .show()
                }
                resumeEmulation()
                menu.rebuild()
            }
        }
    }

    private fun cloudMark(): Long =
        androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
            .getLong("retro_cloud_mark_$retroCloudId", 0L)

    private fun setCloudMark(value: Long) {
        androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
            .edit()
            .putLong("retro_cloud_mark_$retroCloudId", value)
            .apply()
    }

    private fun launchExitCloudBackup() {
        if (!cloudSyncEnabled) return
        val hasSaves =
            RetroSaveStates.gameDir(this, gameName).walkTopDown().any { it.isFile }
        if (!hasSaves) return
        androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
            .edit()
            .putString("retro_pending_backup_id", retroCloudId)
            .putString("retro_pending_backup_name", gameName)
            .apply()
    }

    override fun onDestroy() {
        inputManager?.unregisterInputDeviceListener(inputDeviceListener)
        shutdownNetplaySession(callNativeStop = false)
        RetroAchievementsManager.stopPolling()
        if (achievementsSessionStarted) {
            RetroAchievementsManager.unlockListener = null
            RetroAchievementsManager.resetListener = null
            RetroAchievementsManager.hardcoreNoticeListener = null
            RetroAchievementsManager.endSession()
        }
        FrameGen.setSurfaceRebinder(null)
        FrameGenGlSurface.detach()
        FrameGen.release()
        super.onDestroy()
    }

    private fun shutdownNetplaySession(callNativeStop: Boolean = true) {
        val sysId = system?.id
        val view =
            if (callNativeStop && ::retroView.isInitialized && retroReady) retroView else null
        runCatching {
            RetroNetplayLobby.leave(silent = true, retroView = view)
        }
        netplayLocalPort = 0
        netplayArmedThisSession = false
        if (sysId != null) {
            RetroDefaults.clearNetplayArm(this, sysId)
        }
    }

    private fun showHud() {
        var rating = frameRating
        if (rating == null) {
            val root = rootLayout ?: return
            rating =
                RetroHudSupport.createFrameRating(
                    this,
                    RetroHudSupport.libretroRendererLabel(),
                )
            frameRating = rating
            val menuIndex = menuComposeView?.let { root.indexOfChild(it) } ?: -1
            if (menuIndex >= 0) root.addView(rating, menuIndex) else root.addView(rating)
            rating.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) rootLayout?.post { applyDisplayGeometry() }
            }
            applyRetroHudSettings(rating)
        }
        RetroHudSupport.bindFrameGeneration(rating)
        rating.visibility = View.VISIBLE
        rating.reset()
        rating.post { applyDisplayGeometry() }
    }

    private fun applyRetroHudSettings(rating: FrameRating) {
        rating.setHudAlpha(hudAlpha)
        rating.setBackgroundAlphaDecoupled(hudBgDecoupled)
        rating.setHudBackgroundAlpha(hudBgAlpha)
        rating.setHudScale(hudScale)
        rating.setFrametimeNumericMode(hudFrametimeNumeric)
        rating.setDualSeriesBattery(hudDualBattery)
        hudElements.forEachIndexed { index, enabled -> rating.toggleElement(index, enabled) }
    }

    private fun loadHudSettings() {
        val style = RetroHudSupport.loadGlobalHudStyle(this)
        hudAlpha = style.alpha
        hudBgDecoupled = style.bgDecoupled
        hudBgAlpha = style.bgAlpha
        hudScale = style.scale
        hudFrametimeNumeric = style.frametimeNumeric
        hudDualBattery = style.dualBattery
        hudElements = RetroHudSupport.loadGlobalHudElements(this)
    }

    private fun saveHudSettings() {
        RetroHudSupport.saveGlobalHudStyle(
            this,
            HudStyle(hudAlpha, hudBgDecoupled, hudBgAlpha, hudScale, hudFrametimeNumeric, hudDualBattery),
        )
        RetroHudSupport.saveGlobalHudElements(this, hudElements)
    }

    private fun recordLaunchStats() {
        val prefs = getSharedPreferences("playtime_stats", MODE_PRIVATE)
        playtimePrefs = prefs
        sessionStart = System.currentTimeMillis()
        prefs
            .edit()
            .putInt("${gameName}_play_count", prefs.getInt("${gameName}_play_count", 0) + 1)
            .putLong("${gameName}_last_played", sessionStart)
            .apply()
    }

    private fun accumulatePlaytime() {
        val prefs = playtimePrefs ?: return
        val now = System.currentTimeMillis()
        val delta = now - sessionStart
        if (delta > 0) {
            prefs
                .edit()
                .putLong("${gameName}_playtime", prefs.getLong("${gameName}_playtime", 0L) + delta)
                .apply()
        }
        sessionStart = now
    }

    private fun observeEvents() {
        retroView
            .getGLRetroEvents()
            .onEach { event ->
                when (event) {
                    is GLRetroView.GLRetroEvents.FrameRendered -> {
                        if (hudVisible) frameRating?.recordGameFrame()
                        FrameGen.noteSourceFrames(1)
                        RetroNetplayLobby.onFrameRendered()
                    }
                    is GLRetroView.GLRetroEvents.SurfaceCreated -> {
                        if (!audioEnabledSetting) retroView.audioEnabled = false
                        surfaceReady = true
                        applyDisplayGeometry()
                        if (RetroCoreManager.usesDolphinCore(system)) {
                            runCatching {
                                val joypad = 1
                                retroView.setControllerType(0, joypad)
                                retroView.setControllerType(1, joypad)
                                retroView.setControllerType(2, joypad)
                                retroView.setControllerType(3, joypad)
                            }
                        }
                        lifecycleScope.launch(Dispatchers.Default) {
                            runCatching {
                                diskCount = retroView.getAvailableDisks()
                                currentDisk = retroView.getCurrentDisk()
                            }
                        }
                        startAchievementsSession()
                        applyCheats()
                        startNetplayIfNeeded()
                    }
                }
            }.launchIn(lifecycleScope)
    }

    private fun startNetplayIfNeeded() {
        val sysId = system?.id ?: return
        if (!RetroOnlineSupport.supportsMultiplayerUi(sysId)) return

        if (!netplayArmedThisSession && !RetroDefaults.netplayEnabled(this, sysId)) {
            if (RetroNetplayLobby.isInRoomSession() ||
                RetroNetplayLobby.phase == RetroNetplayPhase.SCANNING ||
                RetroNetplayLobby.phase == RetroNetplayPhase.SCAN_RESULTS
            ) {
                RetroNetplayLobby.leave(silent = true, retroView = retroView)
            }
            return
        }
        netplayArmedThisSession = true

        if (RetroNetplayLobby.attachEmulator(retroView)) {
            if (!RetroNetplayLobby.isGameLink) {
                RetroNetplayLobby.prepareMultiplayerPads(retroView)
            }
            netplayLocalPort =
                when {
                    RetroNetplayLobby.isGameLink -> 0
                    RetroNetplayLobby.isHost -> 0
                    else -> 1
                }
            menu.rebuild()
            return
        }

        if (!RetroNetplayLobby.canStartSession() ||
            RetroNetplayLobby.phase == RetroNetplayPhase.SCANNING
        ) {
            return
        }
        val mode = RetroDefaults.netplayLaunchMode(this, sysId)
        if (mode != "host" && mode != "join") return

        val port =
            if (RetroOnlineSupport.supportsGameLink(sysId)) {
                RetroGameLink.clampPort(RetroDefaults.netplayPort(this, sysId))
            } else {
                RetroDefaults.netplayPort(this, sysId)
            }
        val name = RetroNetplayLobby.defaultPlayerName(this)
        if (!RetroOnlineSupport.supportsGameLink(sysId)) {
            RetroNetplayLobby.prepareMultiplayerPads(retroView)
        }
        when (mode) {
            "host" -> {
                netplayLocalPort = 0
                RetroNetplayLobby.host(
                    systemId = sysId,
                    gameName = gameName,
                    playerName = name,
                    port = port,
                    context = this,
                    retroView = retroView,
                )
            }
            "join" -> {
                val host = RetroDefaults.netplayHost(this, sysId).trim()
                if (host.isBlank()) return
                netplayLocalPort = if (RetroOnlineSupport.supportsGameLink(sysId)) 0 else 1
                RetroNetplayLobby.join(
                    host = host,
                    port = port,
                    playerName = name,
                    gameName = gameName,
                    systemId = sysId,
                    retroView = retroView,
                )
            }
        }
        menu.rebuild()
    }

    private fun leaveNetplayRoom() {
        when (RetroNetplayLobby.phase) {
            RetroNetplayPhase.SCANNING -> RetroNetplayLobby.stopScan()
            RetroNetplayPhase.SCAN_RESULTS -> RetroNetplayLobby.dismissScanResults()
            RetroNetplayPhase.IDLE -> {
                if (RetroNetplayLobby.discovered.isNotEmpty() ||
                    RetroNetplayLobby.statusText.isNotBlank()
                ) {
                    RetroNetplayLobby.dismissScanResults()
                }
            }
            else -> {
                RetroNetplayLobby.leave(
                    silent = false,
                    retroView = if (retroReady) retroView else null,
                )
                netplayLocalPort = 0
            }
        }
        menu.rebuild()
    }

    private fun joinDiscoveredRoom(room: RetroNetplayDiscoveredRoom) {
        if (!retroReady) return
        val sysId = system?.id
        val name = RetroNetplayLobby.defaultPlayerName(this)
        netplayLocalPort = if (RetroOnlineSupport.supportsGameLink(sysId)) 0 else 1
        RetroNetplayLobby.join(
            host = room.hostAddress,
            port = room.port,
            playerName = name,
            gameName = room.gameName.ifBlank { gameName },
            systemId = sysId,
            retroView = retroView,
        )
        menu.close()
        menu.rebuild()
    }

    private fun localNetplayPort(): Int {
        if (RetroNetplayLobby.isGameLink) return 0
        return when (RetroNetplayLobby.phase) {
            RetroNetplayPhase.HOSTING, RetroNetplayPhase.IN_ROOM, RetroNetplayPhase.CONNECTING ->
                if (RetroNetplayLobby.isHost) 0 else 1
            else -> netplayLocalPort
        }
    }

    private fun observeErrors() {
        retroView
            .getGLRetroErrors()
            .onEach { error ->
                val message =
                    when (error) {
                        GLRetroView.ERROR_LOAD_LIBRARY -> getString(R.string.retro_lr_failed_load_core)
                        GLRetroView.ERROR_LOAD_GAME -> getString(R.string.retro_lr_failed_load_rom)
                        GLRetroView.ERROR_GL_NOT_COMPATIBLE -> getString(R.string.retro_lr_graphics_not_supported)
                        else -> getString(R.string.retro_lr_emulator_error)
                    }
                Toast.makeText(this@RetroActivity, message, Toast.LENGTH_LONG).show()
                finish()
            }.launchIn(lifecycleScope)
    }

    private fun sgsrPrePasses(): Int =
        when (currentUpscaleKey) {
            "4x" -> 2
            "native" -> 3
            else -> 1
        }

    private fun effectiveShader(): ShaderConfig =
        if (sgsrEnabled) {
            ShaderConfig.SGSR(sgsrPrePasses(), currentShaderKey)
        } else {
            when (currentShaderKey) {
                "crt" -> ShaderConfig.CRT
                "lcd" -> ShaderConfig.LCD
                "sharp" -> ShaderConfig.Sharp
                else -> ShaderConfig.Default
            }
        }

    private fun loadShortcut(): Shortcut? {
        persistShortcut?.let { return it }
        val containerId = intent.getIntExtra(EXTRA_CONTAINER_ID, 0)
        val path = intent.getStringExtra(EXTRA_SHORTCUT_PATH)
        if (containerId < 0 || path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            val cm = ContainerManager(this)
            val container =
                if (containerId == ContainerManager.RETRO_CONTAINER_ID) cm.retroContainer else cm.getContainerById(containerId)
            container?.let { Shortcut(it, file) }
        }.getOrNull()?.also { persistShortcut = it }
    }

    private fun persistExtra(
        key: String,
        value: String,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val shortcut = loadShortcut()
                shortcut?.putExtra(key, value)
                shortcut?.saveData()
            }
        }
    }

    private fun buildEntriesFor(pane: RetroPane?): List<RetroMenuEntry> =
        when (pane) {
            null -> buildMainEntries()
            RetroPane.DISPLAY ->
                buildList {
                    SHADER_KEYS.forEachIndexed { index, key ->
                        add(
                            RetroMenuEntry.Radio(
                                label = getString(SHADER_LABEL_RES[index]),
                                selected = currentShaderKey == key,
                            ) {
                                currentShaderKey = key
                                retroView.shader = effectiveShader()
                                persistExtra(RetroShortcuts.KEY_SHADER, key)
                                menu.rebuild()
                            },
                        )
                    }
                    add(
                        RetroMenuEntry.Toggle(getString(R.string.retro_lr_sgsr), checked = sgsrEnabled) { value ->
                            sgsrEnabled = value
                            retroView.shader = effectiveShader()
                            persistExtra(RetroShortcuts.KEY_SGSR, if (value) "1" else "0")
                            menu.rebuild()
                        },
                    )
                    val upscaleIndex = UPSCALE_KEYS.indexOf(currentUpscaleKey).coerceAtLeast(0)
                    add(
                        RetroMenuEntry.Choice(
                            getString(R.string.retro_lr_sgsr_upscale),
                            listOf("2x", "4x", getString(R.string.retro_lr_upscale_native)),
                            upscaleIndex,
                            visible = sgsrEnabled,
                        ) { next ->
                            currentUpscaleKey = UPSCALE_KEYS[next]
                            persistExtra(RetroShortcuts.KEY_UPSCALE, currentUpscaleKey)
                            if (sgsrEnabled) retroView.shader = effectiveShader()
                            menu.rebuild()
                        },
                    )
                    addAll(coreOptionEntries(RetroOptionCategory.DISPLAY))
                }
            RetroPane.FRAMEGEN -> emptyList()
            RetroPane.SOUND ->
                buildList {
                    add(
                        RetroMenuEntry.Toggle(getString(R.string.retro_lr_sound), checked = audioEnabledSetting) { value ->
                            audioEnabledSetting = value
                            retroView.audioEnabled = value
                            persistExtra(RetroShortcuts.KEY_AUDIO, if (value) "1" else "0")
                            menu.rebuild()
                        },
                    )
                    addAll(coreOptionEntries(RetroOptionCategory.SOUND))
                }
            RetroPane.CONTROLS -> buildControlsEntries() + coreOptionEntries(RetroOptionCategory.CONTROLS)
            RetroPane.HUD -> buildHudEntries()
            RetroPane.SAVES -> buildSaveSlotEntries()
            RetroPane.PERFORMANCE -> coreOptionEntries(RetroOptionCategory.PERFORMANCE)
            RetroPane.SYSTEM -> coreOptionEntries(RetroOptionCategory.SYSTEM)
            RetroPane.MEMCARDS -> emptyList()
            RetroPane.NETWORK -> buildNetplayEntries()
        }

    private fun coreOptionEntries(category: RetroOptionCategory): List<RetroMenuEntry> {
        val options =
            RetroCoreOptions.forSystem(system)
                .filter { it.category == category }
                .filter { opt -> opt.isApplicable { key -> coreVars[key] ?: optionDefault(key) } }
        if (options.isEmpty()) return emptyList()

        fun entryFor(option: RetroCoreOption): RetroMenuEntry {
            val current = coreVars[option.key] ?: option.defaultValue
            val index = option.values.indexOf(current).coerceAtLeast(0)
            return RetroMenuEntry.Choice(option.labelText(this), option.valueLabels, index) { next ->
                val newValue = option.values[next]
                coreVars[option.key] = newValue
                retroView.updateVariables(Variable(option.key, newValue))
                persistExtra(RetroShortcuts.VAR_PREFIX + option.key, newValue)
                menu.rebuild()
            }
        }

        return (options.filterNot { it.advanced } + options.filter { it.advanced })
            .map(::entryFor)
    }

    private fun optionDefault(key: String): String? =
        RetroCoreOptions.forSystem(system).firstOrNull { it.key == key }?.defaultValue

    private fun buildNetplayEntries(): List<RetroMenuEntry> {
        val sysId = system?.id ?: return emptyList()
        if (!RetroOnlineSupport.supportsMultiplayerUi(sysId)) return emptyList()
        val phase = RetroNetplayLobby.phase
        val port =
            if (RetroOnlineSupport.supportsGameLink(sysId)) {
                RetroGameLink.clampPort(RetroDefaults.netplayPort(this, sysId))
            } else {
                RetroDefaults.netplayPort(this, sysId)
            }
        val name = RetroNetplayLobby.defaultPlayerName(this)
        val savedHost = RetroDefaults.netplayHost(this, sysId)
        val entries = mutableListOf<RetroMenuEntry>()

        when (phase) {
            RetroNetplayPhase.HOSTING, RetroNetplayPhase.CONNECTING, RetroNetplayPhase.IN_ROOM -> {
                val localIp = RetroNetplayLobby.localIpv4Addresses().firstOrNull()
                entries +=
                    RetroMenuEntry.Action(
                        label =
                            when (phase) {
                                RetroNetplayPhase.HOSTING -> getString(R.string.retro_netplay_hosting_room)
                                RetroNetplayPhase.CONNECTING -> getString(R.string.retro_netplay_connecting)
                                else -> getString(R.string.retro_netplay_in_room)
                            },
                        icon = RetroDrawerIcons.Group,
                        active = true,
                        subtitle =
                            when (phase) {
                                RetroNetplayPhase.HOSTING ->
                                    if (localIp != null) {
                                        "$localIp:$port"
                                    } else {
                                        getString(R.string.retro_netplay_waiting_players)
                                    }
                                else ->
                                    RetroNetplayLobby.roomTitle.ifBlank { gameName }
                            },
                    ) {}
                val members =
                    RetroNetplayLobby.members.ifEmpty {
                        listOf(RetroNetplayMember(name, isHost = RetroNetplayLobby.isHost, isLocal = true))
                    }
                members.forEach { member ->
                    val title =
                        buildString {
                            append(member.name)
                            if (member.isHost) append(" · Host")
                            if (member.isLocal) append(" · You")
                            if (!member.isHost && !member.isLocal) append(" · Joined")
                        }
                    entries +=
                        RetroMenuEntry.Action(
                            label = title,
                            icon = RetroDrawerIcons.Group,
                            active = member.isLocal,
                        ) {}
                }
                entries +=
                    RetroMenuEntry.Action(
                        label = getString(R.string.retro_netplay_leave),
                        icon = RetroDrawerIcons.Exit,
                        danger = true,
                    ) {
                        leaveNetplayRoom()
                    }
            }

            RetroNetplayPhase.IDLE, RetroNetplayPhase.SCANNING, RetroNetplayPhase.SCAN_RESULTS -> {
                if (!netplayArmedThisSession) {
                    entries +=
                        RetroMenuEntry.Action(
                            label = getString(R.string.retro_netplay_title),
                            icon = RetroDrawerIcons.Group,
                            subtitle = getString(R.string.retro_netplay_enable_before_launch),
                        ) {}
                    return entries
                }
                entries +=
                    RetroMenuEntry.Action(
                        label = getString(R.string.retro_netplay_title),
                        icon = RetroDrawerIcons.Group,
                        active = phase != RetroNetplayPhase.IDLE,
                        subtitle =
                            when (phase) {
                                RetroNetplayPhase.SCANNING -> getString(R.string.retro_netplay_scanning)
                                RetroNetplayPhase.SCAN_RESULTS ->
                                    RetroNetplayLobby.statusText.ifBlank {
                                        getString(R.string.retro_netplay_scan_results)
                                    }
                                else ->
                                    if (RetroOnlineSupport.supportsGameLink(sysId)) {
                                        getString(R.string.retro_netplay_game_link_hint)
                                    } else {
                                        getString(R.string.retro_netplay_menu_hint)
                                    }
                            },
                    ) {}
                if (phase != RetroNetplayPhase.SCANNING) {
                    val hostIp = RetroNetplayLobby.localIpv4Addresses().firstOrNull()
                    entries +=
                        RetroMenuEntry.Action(
                            label = getString(R.string.retro_netplay_host_action),
                            icon = RetroDrawerIcons.Add,
                            subtitle =
                                if (hostIp != null) {
                                    "${getString(R.string.retro_gs_netplay_port)} $port\n$hostIp"
                                } else {
                                    "${getString(R.string.retro_gs_netplay_port)} $port"
                                },
                        ) {
                            netplayLocalPort = 0
                            RetroNetplayLobby.host(
                                systemId = sysId,
                                gameName = gameName,
                                playerName = name,
                                port = port,
                                context = this,
                                retroView = retroView,
                            )
                            menu.rebuild()
                        }
                }
                entries +=
                    RetroMenuEntry.Action(
                        label =
                            when (phase) {
                                RetroNetplayPhase.SCANNING -> getString(R.string.retro_netplay_stop_scan)
                                RetroNetplayPhase.SCAN_RESULTS -> getString(R.string.retro_netplay_scan_again)
                                else -> getString(R.string.retro_netplay_scan_action)
                            },
                        icon = RetroDrawerIcons.Search,
                        active = phase == RetroNetplayPhase.SCANNING || phase == RetroNetplayPhase.SCAN_RESULTS,
                        subtitle =
                            when (phase) {
                                RetroNetplayPhase.SCANNING ->
                                    RetroNetplayLobby.statusText.ifBlank {
                                        getString(R.string.retro_netplay_scanning)
                                    }
                                RetroNetplayPhase.SCAN_RESULTS ->
                                    RetroNetplayLobby.statusText.ifBlank {
                                        getString(R.string.retro_netplay_scan_results)
                                    }
                                else -> getString(R.string.retro_netplay_scan_hint)
                            },
                    ) {
                        if (phase == RetroNetplayPhase.SCANNING) {
                            RetroNetplayLobby.stopScan()
                            menu.rebuild()
                        } else {
                            menu.close()
                            RetroNetplayLobby.scan(this, sysId)
                        }
                    }
                if (phase == RetroNetplayPhase.SCAN_RESULTS) {
                    entries +=
                        RetroMenuEntry.Action(
                            label = getString(R.string.retro_netplay_dismiss),
                            icon = RetroDrawerIcons.Exit,
                        ) {
                            RetroNetplayLobby.dismissScanResults()
                            menu.rebuild()
                        }
                }
                RetroNetplayLobby.discovered.forEach { room ->
                    entries +=
                        RetroMenuEntry.Action(
                            label = "${room.gameName} · ${room.hostPlayerName}",
                            icon = RetroDrawerIcons.Play,
                            subtitle = "${room.hostAddress}:${room.port} · ${getString(R.string.retro_netplay_join_room)}",
                        ) {
                            joinDiscoveredRoom(room)
                        }
                }
                if (phase != RetroNetplayPhase.SCANNING) {
                    entries +=
                        RetroMenuEntry.Action(
                            label = getString(R.string.retro_netplay_join_action),
                            icon = RetroDrawerIcons.Link,
                            subtitle =
                                if (savedHost.isNotBlank()) {
                                    "$savedHost:$port"
                                } else {
                                    getString(R.string.retro_gs_netplay_host_hint)
                                },
                        ) {
                            promptJoinByAddress(sysId, port, name, savedHost)
                        }
                }
            }
        }
        return entries
    }

    private fun promptJoinByAddress(
        sysId: String,
        port: Int,
        playerName: String,
        initialHost: String,
    ) {
        menu.renamePrompt =
            RetroRenamePrompt(
                title = getString(R.string.retro_gs_netplay_host),
                initial = initialHost,
            ) { entered ->
                val host = entered?.trim().orEmpty()
                if (host.isBlank()) return@RetroRenamePrompt
                netplayLocalPort = 1
                RetroNetplayLobby.join(
                    host = host,
                    port = port,
                    playerName = playerName,
                    gameName = gameName,
                    systemId = sysId,
                    retroView = retroView,
                )
                menu.close()
                menu.rebuild()
            }
    }

    private fun buildControlsEntries(): List<RetroMenuEntry> =
        RetroControlsMenu.build(
            RetroControlsMenu.Host(
                context = this,
                overlay = overlay,
                menu = menu,
                systemId = system?.id,
                touchControls = { touchControlsSetting },
                onTouchControls = { value ->
                    touchControlsSetting = value
                    if (controllerConnected) manualTouchOverride = true
                    updateOverlayVisibility()
                    syncInGameOverlayPlacement()
                    persistExtra(RetroShortcuts.KEY_TOUCH_CONTROLS, if (value) "1" else "0")
                },
                adaptiveSticks = { adaptiveSticksSetting },
                onAdaptiveSticks = { value ->
                    adaptiveSticksSetting = value
                    persistExtra(RetroShortcuts.KEY_ADAPTIVE_STICKS, if (value) "1" else "0")
                },
                orientationLabel = {
                    if ((rootLayout?.height ?: 0) > (rootLayout?.width ?: 0)) {
                        getString(R.string.retro_lr_portrait)
                    } else {
                        getString(R.string.retro_lr_landscape)
                    }
                },
                onCloseMenu = { menu.close() },
                showStickInversion = system?.id == RetroSystems.PSX.id || RetroCoreManager.usesDolphinCore(system),
            ),
        )

    private fun setHudVisible(value: Boolean) {
        hudVisible = value
        if (value) {
            showHud()
        } else {
            frameRating?.visibility = View.GONE
            applyDisplayGeometry()
        }
        persistExtra(RetroShortcuts.KEY_HUD, if (value) "1" else "0")
        menu.rebuild()
    }

    private fun buildHudEntries(): List<RetroMenuEntry> {
        val style =
            HudStyle(
                alpha = hudAlpha,
                bgDecoupled = hudBgDecoupled,
                bgAlpha = hudBgAlpha,
                scale = hudScale,
                frametimeNumeric = hudFrametimeNumeric,
                dualBattery = hudDualBattery,
            )
        return RetroHudSupport.buildHudEntries(
            context = this,
            hudVisible = hudVisible,
            style = style,
            elements = hudElements,
            onMaster = { setHudVisible(it) },
            onStyle = { next ->
                hudAlpha = next.alpha
                hudBgDecoupled = next.bgDecoupled
                hudBgAlpha = next.bgAlpha
                hudScale = next.scale
                hudFrametimeNumeric = next.frametimeNumeric
                hudDualBattery = next.dualBattery
                frameRating?.let { RetroHudSupport.applyStyle(it, next, hudElements) }
                saveHudSettings()
            },
            onElements = { next ->
                hudElements = next
                frameRating?.let { RetroHudSupport.applyStyle(it, style, next) }
                saveHudSettings()
            },
            onRebuild = { menu.rebuild() },
        )
    }

    private fun buildMainEntries(): List<RetroMenuEntry> {
        val entries = mutableListOf<RetroMenuEntry>()
        val hardcoreActive = achievementsSessionStarted && RetroAchievementsManager.isHardcoreActive()
        entries +=
            RetroMenuEntry.Action(getString(R.string.retro_lr_save_state), RetroDrawerIcons.Save) {
                savesLoadMode = false
                menu.showPane(RetroPane.SAVES)
            }
        entries +=
            RetroMenuEntry.Action(getString(R.string.retro_lr_load_save_state), RetroDrawerIcons.Load) {
                if (hardcoreActive) {
                    Toast.makeText(this, getString(R.string.retro_lr_loading_states_disabled_hardcore), Toast.LENGTH_SHORT).show()
                } else {
                    savesLoadMode = true
                    menu.showPane(RetroPane.SAVES)
                }
            }
        if (RetroAchievementsManager.isLoggedIn(this) && RetroAchievementsManager.consoleId(system?.id) != 0) {
            entries +=
                RetroMenuEntry.Action(getString(R.string.retro_lr_achievements), RetroDrawerIcons.Achievements) {
                    menu.close()
                    openAchievementsScreen()
                }
        }
        entries +=
            RetroMenuEntry.Action(getString(R.string.retro_lr_cheats), RetroDrawerIcons.Cheats, active = !cheatsAllowed()) {
                if (!cheatsAllowed()) {
                    Toast.makeText(this, getString(R.string.retro_lr_cheats_disabled_hardcore), Toast.LENGTH_SHORT).show()
                } else {
                    menu.close()
                    openCheatsScreen()
                }
            }
        entries +=
            RetroMenuEntry.Action(getString(R.string.retro_lr_reset), RetroDrawerIcons.Reset) {
                menu.close()
                retroView.reset()
                RetroAchievementsManager.onEmulatorReset()
            }
        entries +=
            RetroMenuEntry.Action(getString(R.string.retro_lr_fast_forward), RetroDrawerIcons.FastForward, active = fastForward) {
                if (hardcoreActive) {
                    Toast.makeText(this, getString(R.string.retro_lr_fast_forward_disabled_hardcore), Toast.LENGTH_SHORT).show()
                } else {
                    fastForward = !fastForward
                    retroView.frameSpeed = if (fastForward) 2 else 1
                    menu.rebuild()
                }
            }
        entries +=
            RetroMenuEntry.Action(getString(R.string.retro_lr_hud), RetroDrawerIcons.Hud, active = hudVisible) {
                setHudVisible(!hudVisible)
            }
        if (diskCount > 1) {
            entries +=
                RetroMenuEntry.Action(getString(R.string.retro_lr_disc, currentDisk + 1, diskCount), RetroDrawerIcons.Disc) {
                    val next = (currentDisk + 1) % diskCount
                    lifecycleScope.launch(Dispatchers.Default) {
                        runCatching { retroView.changeDisk(next) }
                        currentDisk = next
                        runOnUiThread { menu.rebuild() }
                    }
                }
        }
        return entries
    }

    private fun buildBottomEntries(): List<RetroMenuEntry.Action> =
        listOf(
            if (emulationPaused) {
                RetroMenuEntry.Action(getString(R.string.retro_lr_resume), RetroDrawerIcons.Resume, active = true) {
                    resumeEmulation()
                    menu.close()
                }
            } else {
                RetroMenuEntry.Action(getString(R.string.retro_lr_pause), RetroDrawerIcons.Pause) {
                    pauseEmulation()
                    menu.close()
                }
            },
            RetroMenuEntry.Action(getString(R.string.retro_lr_exit), RetroDrawerIcons.Exit, danger = true) { finish() },
        )

    private fun requestSixtyHzDisplayMode(): Boolean {
        val display = runCatching { windowManager.defaultDisplay }.getOrNull() ?: return false
        val current = runCatching { display.mode }.getOrNull() ?: return false
        val rate = display.refreshRate
        val multiple = Math.round(rate / 60f)
        if (multiple >= 1 && kotlin.math.abs(rate - multiple * 60f) < 2f) return false
        val sixtyModes = display.supportedModes.filter { abs(it.refreshRate - 60f) < 1f }
        val target =
            sixtyModes.firstOrNull {
                it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
            } ?: sixtyModes.firstOrNull() ?: return false
        val attributes = window.attributes
        attributes.preferredDisplayModeId = target.modeId
        window.attributes = attributes
        return true
    }

    private fun openMenu() {
        if (!retroReady) {
            return
        }
        overlay?.releaseAll()
        menu.open()
    }

    private fun mapPhysicalKey(keyCode: Int): Int =
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
            else -> keyCode
        }

    private fun isGamepadSource(event: KeyEvent): Boolean {
        val source = event.device?.sources ?: return false
        return source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    private val forwardedKeys =
        setOf(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE && isGamepadSource(event)) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (menu.visible) menu.close() else if (retroReady) openMenu()
            }
            return true
        }
        if (menu.visible && isGamepadSource(event)) {
            menu.handleKey(keyCode, event.action)
            return true
        }
        if (retroReady && isGamepadSource(event)) {
            if (keyCode in forwardedKeys) {
                val mapped = mapPhysicalKey(keyCode)
                val port = localNetplayPort()
                retroView.sendKeyEvent(event.action, mapped, port)
                if (RetroNetplayLobby.activeSession()?.isRunning == true) {
                    RetroNetplayLobby.sendLocalKey(mapped, event.action)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (menu.visible && event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            val x = event.getAxisValue(MotionEvent.AXIS_HAT_X).takeIf { abs(it) > 0.5f } ?: event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y).takeIf { abs(it) > 0.5f } ?: event.getAxisValue(MotionEvent.AXIS_Y)
            menu.handleAxis(x, y)
            return true
        }
        if (retroReady &&
            event.action == MotionEvent.ACTION_MOVE &&
            event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        ) {
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val stickX = if (overlay?.invertLX == true) -event.getAxisValue(MotionEvent.AXIS_X) else event.getAxisValue(MotionEvent.AXIS_X)
            val stickY = if (overlay?.invertLY == true) -event.getAxisValue(MotionEvent.AXIS_Y) else event.getAxisValue(MotionEvent.AXIS_Y)
            val port = localNetplayPort()
            val netActive = RetroNetplayLobby.activeSession()?.isRunning == true
            if (stickIsAnalog) {
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, hatX, hatY, port)
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, stickX, stickY, port)
                if (netActive) {
                    RetroNetplayLobby.sendLocalMotion(GLRetroView.MOTION_SOURCE_DPAD, hatX, hatY)
                    RetroNetplayLobby.sendLocalMotion(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, stickX, stickY)
                }
            } else {
                val deadzone = 0.45f
                val dpadX =
                    when {
                        abs(hatX) > 0.5f -> hatX
                        stickX > deadzone -> 1f
                        stickX < -deadzone -> -1f
                        else -> 0f
                    }
                val dpadY =
                    when {
                        abs(hatY) > 0.5f -> hatY
                        stickY > deadzone -> 1f
                        stickY < -deadzone -> -1f
                        else -> 0f
                    }
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, dpadX, dpadY, port)
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, stickX, stickY, port)
                if (netActive) {
                    RetroNetplayLobby.sendLocalMotion(GLRetroView.MOTION_SOURCE_DPAD, dpadX, dpadY)
                    RetroNetplayLobby.sendLocalMotion(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, stickX, stickY)
                }
            }
            val rx = if (overlay?.invertRX == true) -event.getAxisValue(MotionEvent.AXIS_Z) else event.getAxisValue(MotionEvent.AXIS_Z)
            val ry = if (overlay?.invertRY == true) -event.getAxisValue(MotionEvent.AXIS_RZ) else event.getAxisValue(MotionEvent.AXIS_RZ)
            retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, rx, ry, port)
            if (netActive) {
                RetroNetplayLobby.sendLocalMotion(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, rx, ry)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onButton(
        keyCode: Int,
        down: Boolean,
    ) {
        if (!retroReady || menu.visible) return
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        val port = localNetplayPort()
        retroView.sendKeyEvent(action, keyCode, port)
        if (RetroNetplayLobby.activeSession()?.isRunning == true) {
            RetroNetplayLobby.sendLocalKey(keyCode, action)
        }
    }

    override fun onDpad(
        x: Float,
        y: Float,
    ) {
        if (!retroReady || menu.visible) return
        val port = localNetplayPort()
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y, port)
        if (RetroNetplayLobby.activeSession()?.isRunning == true) {
            RetroNetplayLobby.sendLocalMotion(GLRetroView.MOTION_SOURCE_DPAD, x, y)
        }
    }

    override fun onStick(
        x: Float,
        y: Float,
    ) {
        if (!retroReady || menu.visible) return
        val port = localNetplayPort()
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y, port)
        if (RetroNetplayLobby.activeSession()?.isRunning == true) {
            RetroNetplayLobby.sendLocalMotion(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y)
        }
    }

    override fun onRightStick(
        x: Float,
        y: Float,
    ) {
        if (!retroReady || menu.visible) return
        val port = localNetplayPort()
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, x, y, port)
        if (RetroNetplayLobby.activeSession()?.isRunning == true) {
            RetroNetplayLobby.sendLocalMotion(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, x, y)
        }
    }

    override fun onMenu() {
        runOnUiThread { openMenu() }
    }

    private fun saveState(slot: Int) {
        runCatching {
            val bytes = retroView.serializeState()
            check(RetroSaveStates.writeSlot(this, gameName, slot, bytes))
        }.onSuccess {
            showInGameMessage(getString(R.string.retro_lr_saved_to_slot, slot))
            launchExitCloudBackup()
        }.onFailure {
            showInGameMessage(getString(R.string.retro_lr_could_not_save_state))
        }
    }

    private fun showBiosRequiredDialog(system: RetroSystem) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.retro_lr_bios_required_title, system.shortName))
            .setMessage(getString(R.string.retro_lr_bios_required_message))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.retro_lr_import_bios)) { _, _ ->
                runCatching { biosPicker.launch(arrayOf("*/*")) }
                    .onFailure { finish() }
            }
            .setNegativeButton(getString(R.string.retro_lr_cancel)) { _, _ -> finish() }
            .show()
    }

    private fun openAchievementsScreen() {
        val sys = system ?: return
        val rom = intent.getStringExtra(EXTRA_ROM_PATH) ?: return
        startActivity(
            android.content.Intent(this, RetroAchievementsActivity::class.java).apply {
                putExtra(RetroAchievementsActivity.EXTRA_SYSTEM_ID, sys.id)
                putExtra(RetroAchievementsActivity.EXTRA_GAME_NAME, gameName)
                putExtra(RetroAchievementsActivity.EXTRA_ROM_PATH, rom)
                putExtra(RetroAchievementsActivity.EXTRA_IN_SESSION, true)
            },
        )
    }

    private fun loadState(slot: Int) {
        if (achievementsSessionStarted && RetroAchievementsManager.isHardcoreActive()) {
            showInGameMessage(getString(R.string.retro_lr_loading_states_disabled_hardcore))
            return
        }
        val bytes = RetroSaveStates.readSlot(this, gameName, slot)
        if (bytes == null) {
            showInGameMessage(getString(R.string.retro_lr_slot_empty, slot))
            return
        }
        runCatching { check(retroView.unserializeState(bytes)) }
            .onSuccess { showInGameMessage(getString(R.string.retro_lr_loaded_slot, slot)) }
            .onFailure { showInGameMessage(getString(R.string.retro_lr_could_not_load_state)) }
    }

    private fun buildSaveSlotEntries(): List<RetroMenuEntry> =
        RetroSaveStates.listSlots(this, gameName).map { info ->
            RetroMenuEntry.SaveSlot(
                slot = info.slot,
                title = info.customName ?: getString(R.string.retro_lr_slot_title, info.slot),
                subtitle = RetroSaveStates.relativeTime(info.timestampMs),
                filled = info.exists,
                onClick = {
                    if (savesLoadMode) {
                        if (info.exists) {
                            menu.close()
                            loadState(info.slot)
                        }
                    } else {
                        saveState(info.slot)
                        menu.rebuild()
                    }
                },
                onRename = {
                    menu.renamePrompt =
                        RetroRenamePrompt(
                            title = getString(R.string.retro_lr_rename_slot, info.slot),
                            initial = info.customName ?: "",
                        ) { newName ->
                            RetroSaveStates.renameSlot(this, gameName, info.slot, newName)
                            menu.rebuild()
                        }
                },
            )
        }

    private fun persistSram() {
        if (!retroReady) return
        runCatching {
            val sram = retroView.serializeSRAM()
            if (sram.isNotEmpty()) {
                RetroSaveStates.sramFile(this, gameName).writeBytes(sram)
            }
        }
    }

    override fun onPause() {
        persistSram()
        accumulatePlaytime()
        if (isFinishing) launchExitCloudBackup()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (retroReady && surfaceReady) applyCheats()
        if (emulationPaused && retroReady) {
            window.decorView.post {
                if (emulationPaused && retroReady) {
                    retroView.onPause()
                    LibretroDroid.pause()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}
