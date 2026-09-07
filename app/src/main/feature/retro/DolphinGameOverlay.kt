package com.winlator.cmod.feature.retro

import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.shared.theme.GameSettingsStyle
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.display.ui.FrameRating
import com.winlator.cmod.shared.theme.WinNativeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.dolphinemu.dolphinemu.wn.DolphinEmulationActivity
import org.dolphinemu.dolphinemu.wn.DolphinHost
import org.dolphinemu.dolphinemu.wn.DolphinNetplay
import java.io.File

object DolphinGameOverlay {
    private const val SLOT_COUNT = 4

    private fun dolphinAnyController(): Boolean =
        android.view.InputDevice.getDeviceIds().any {
            com.winlator.cmod.runtime.input.controls.ExternalController.isGameController(
                android.view.InputDevice.getDevice(it),
            )
        }

    private fun buildDolphinNetplay(activity: DolphinEmulationActivity): List<RetroMenuEntry> =
        buildList {
            add(
                RetroMenuEntry.Action(
                    label =
                        activity.getString(
                            if (DolphinNetplayHud.hosting) {
                                R.string.retro_netplay_hosting_room
                            } else {
                                R.string.retro_netplay_in_room
                            },
                        ),
                    icon = Icons.Outlined.Wifi,
                    active = true,
                    onClick = {},
                ),
            )
            DolphinNetplayHud.hostCode?.let { code ->
                add(
                    RetroMenuEntry.Action(
                        label = "${activity.getString(R.string.retro_netplay_host_code)}: $code",
                        icon = Icons.Outlined.Wifi,
                        onClick = {},
                    ),
                )
            }
            val members = DolphinNetplayHud.members
            if (members.isEmpty()) {
                add(
                    RetroMenuEntry.Action(
                        label = activity.getString(R.string.retro_netplay_waiting_players),
                        icon = Icons.Outlined.Person,
                        onClick = {},
                    ),
                )
            } else {
                members.forEach { name ->
                    add(RetroMenuEntry.Action(label = name, icon = Icons.Outlined.Person, onClick = {}))
                }
            }
        }

    @Composable
    private fun DolphinNetplayBanner(hosting: Boolean, hostCode: String?, members: List<String>) {
        AnimatedVisibility(
            visible = hosting && members.size < 2,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier.fillMaxSize().padding(top = 56.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                val parts =
                    buildList {
                        add(stringResource(R.string.retro_netplay_hosting_room))
                        if (hostCode != null) {
                            add("${stringResource(R.string.retro_netplay_host_code)}: $hostCode")
                        }
                        add(stringResource(R.string.retro_netplay_waiting_players))
                    }
                Text(
                    parts.joinToString("  ·  "),
                    color = GameSettingsStyle.TextPrimary,
                    fontSize = 13.sp,
                    modifier =
                        Modifier
                            .background(Color(0xF0121824), RoundedCornerShape(10.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }

    fun install() {
        DolphinHost.attachOverlay = { activity ->
            if (activity is DolphinEmulationActivity) attach(activity)
        }
        DolphinHost.onStageSaves = { activity -> DolphinCloudSync.stageAndQueue(activity) }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        DolphinHost.onNetplayStatus = { hosting, code, members ->
            main.post { DolphinNetplayHud.update(hosting, code, members) }
        }
    }

    private fun loadShortcut(activity: DolphinEmulationActivity): Shortcut? {
        val containerId = activity.intent.getIntExtra(DolphinEmulationActivity.EXTRA_CONTAINER_ID, -1)
        val path = activity.intent.getStringExtra(DolphinEmulationActivity.EXTRA_SHORTCUT_PATH)
        if (containerId < 0 || path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            val cm = ContainerManager(activity)
            val container =
                if (containerId == ContainerManager.RETRO_CONTAINER_ID) cm.retroContainer else cm.getContainerById(containerId)
            container?.let { Shortcut(it, file) }
        }.getOrNull()
    }

    private fun attach(activity: DolphinEmulationActivity) {
        val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        val system =
            RetroSystems.fromId(activity.intent.getStringExtra(DolphinEmulationActivity.EXTRA_SYSTEM_ID))
                ?: RetroSystems.GAMECUBE
        var shortcut: Shortcut? = null
        val pendingExtras = linkedMapOf<String, String>()
        val menu = RetroMenuController()
        val dolphinScreen = mutableStateOf<String?>(null)
        lateinit var input: RetroInputView
        val vars = DolphinEmulationActivity.currentVariables
        var touchControls = vars["wn_touch"] != "0"
        var adaptiveSticks = vars["wn_adaptive"] == "1"
        var audioOn = vars["wn_audio"] != "0"
        var hudOn = vars["wn_hud"] == "1"
        var savesLoadMode = false
        var frameRating: FrameRating? = null
        var hudStyle = RetroHudSupport.loadGlobalHudStyle(activity)
        var hudElements = RetroHudSupport.loadGlobalHudElements(activity)
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            val sc = loadShortcut(activity)
            activity.runOnUiThread {
                shortcut = sc
                if (sc != null && pendingExtras.isNotEmpty()) {
                    val queued = pendingExtras.toMap()
                    pendingExtras.clear()
                    GlobalScope.launch(Dispatchers.IO) {
                        runCatching {
                            queued.forEach { (k, v) -> sc.putExtra(k, v) }
                            sc.saveData()
                        }
                    }
                }
            }
        }

        val gameName =
            activity.intent.getStringExtra(DolphinEmulationActivity.EXTRA_GAME_NAME) ?: "game"
        val stageKey = RetroSaveStates.cloudGameId(system.id, gameName)
        val cloudId =
            activity.intent.getStringExtra(DolphinEmulationActivity.EXTRA_SHORTCUT_PATH)?.let { path ->
                com.winlator.cmod.feature.sync.google.GameSaveBackupManager.customGameId(
                    activity.intent.getIntExtra(DolphinEmulationActivity.EXTRA_CONTAINER_ID, 0),
                    java.io.File(path).name,
                )
            } ?: stageKey

        fun cloudSyncEnabled(): Boolean = shortcut?.getExtra("cloud_sync_enabled", "1") != "0"

        fun stageCloudBackup(onDone: (() -> Unit)? = null) {
            if (!cloudSyncEnabled()) {
                onDone?.let { activity.runOnUiThread(it) }
                return
            }
            val gameId = DolphinEmulationActivity.currentGameId
            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch(Dispatchers.IO) {
                if (DolphinCloudSync.stage(activity, stageKey, gameId)) {
                    val fp = DolphinCloudSync.stagedFingerprint(activity, stageKey)
                    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
                    if (fp != prefs.getString("retro_cloud_fp_$cloudId", null)) {
                        DolphinCloudSync.writePending(activity, cloudId, gameName, fp)
                    }
                }
                onDone?.let { activity.runOnUiThread(it) }
            }
        }

        fun persistExtra(key: String, value: String) {
            val sc = shortcut
            if (sc == null) {
                pendingExtras[key] = value
                return
            }
            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch(Dispatchers.IO) {
                runCatching {
                    sc.putExtra(key, value)
                    sc.saveData()
                }
            }
        }

        fun applyVar(key: String, value: String) {
            activity.applyVariable(key, value)
            persistExtra(RetroShortcuts.VAR_PREFIX + key, value)
        }

        fun showHud() {
            var rating = frameRating
            if (rating == null) {
                rating = RetroHudSupport.createFrameRating(activity, "Vulkan")
                frameRating = rating
                RetroHudSupport.attachFrameRating(root, rating)
                RetroHudSupport.applyStyle(rating, hudStyle, hudElements)
                org.dolphinemu.dolphinemu.NativeLibrary.frameListener =
                    Runnable { frameRating?.recordGameFrame() }
            }
            rating.visibility = android.view.View.VISIBLE
            rating.reset()
            RetroHudSupport.bindFrameGeneration(rating)
        }

        fun setHudVisible(value: Boolean) {
            hudOn = value
            activity.applyVariable("wn_hud", if (value) "1" else "0")
            persistExtra(RetroShortcuts.KEY_HUD, if (value) "1" else "0")
            if (value) showHud() else frameRating?.visibility = android.view.View.GONE
        }

        val listener =
            object : RetroInputView.Listener {
                override fun onButton(keyCode: Int, down: Boolean) {
                    if (!menu.visible) activity.sendOverlayButton(keyCode, down)
                }

                override fun onDpad(x: Float, y: Float) {
                    if (!menu.visible) activity.sendOverlayDpad(x, y)
                }

                override fun onStick(x: Float, y: Float) {
                    if (!menu.visible) activity.sendOverlayLeftStick(x, y)
                }

                override fun onRightStick(x: Float, y: Float) {
                    if (!menu.visible) activity.sendOverlayRightStick(x, y)
                }

                override fun onMenu() {
                    activity.runOnUiThread {
                        input.releaseAll()
                        menu.open()
                    }
                }
            }

        input = RetroInputView(activity, listener, system)
        input.hapticStrength =
            androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(activity)
                .getFloat("retro_haptic_strength", 0.4f)
        input.adaptiveSticks = adaptiveSticks
        input.showL3R3 =
            androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(activity)
                .getBoolean(RetroControlsMenu.l3r3PrefKey(system.id), true)
        input.setCustomColors(RetroControlLayouts.loadColors(activity, system.id))
        input.shellBackground = RetroControlLayouts.shellBackground(activity, system.id)
        input.loadStickInversion()
        var controllerConnected = dolphinAnyController()
        var manualTouchOverride = false
        fun touchEffective() = touchControls && (!controllerConnected || manualTouchOverride)
        input.visibility = if (touchEffective()) android.view.View.VISIBLE else android.view.View.GONE
        fun updateGameArea() {
            val w = root.width.toFloat()
            val h = root.height.toFloat()
            if (w <= 0f || h <= 0f) return
            val gameWidth = (h * 4f / 3f).coerceAtMost(w)
            val left = (w - gameWidth) * 0.5f
            val box = android.graphics.RectF(left, 0f, left + gameWidth, h)
            input.setGameArea(box)
            activity.setSurfaceBounds(if (touchEffective() && input.shellBackground) box else null)
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGameArea() }
        root.post { updateGameArea() }
        fun refreshDolphinController() {
            val was = controllerConnected
            controllerConnected = dolphinAnyController()
            if (controllerConnected != was) manualTouchOverride = false
            val show = touchEffective()
            input.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            updateGameArea()
            if (show) input.post { updateGameArea(); input.relayout() }
        }
        activity.getSystemService(android.hardware.input.InputManager::class.java)
            ?.registerInputDeviceListener(
                object : android.hardware.input.InputManager.InputDeviceListener {
                    override fun onInputDeviceAdded(deviceId: Int) = refreshDolphinController()

                    override fun onInputDeviceRemoved(deviceId: Int) = refreshDolphinController()

                    override fun onInputDeviceChanged(deviceId: Int) = refreshDolphinController()
                },
                null,
            )
        root.addView(
            input,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        menu.tabs = RetroDrawerTabs.build(activity, includeNetplay = DolphinNetplay.active)
        menu.paneContentProvider = { pane ->
            if (pane == RetroPane.FRAMEGEN) {
                {
                    RetroFrameGenPane.Content(
                        activity,
                        shortcut ?: loadShortcut(activity),
                        system.id,
                    )
                }
            } else {
                null
            }
        }
        menu.entriesProvider = { pane ->
            when (pane) {
                null ->
                    buildMain(
                        activity,
                        menu,
                        hudOn,
                        system.id == RetroSystems.WII.id,
                        onAchievements = {
                            dolphinScreen.value = "achievements"
                            menu.close()
                        },
                        onHud = ::setHudVisible,
                    ) { load ->
                        savesLoadMode = load
                        menu.showPane(RetroPane.SAVES)
                    }
                RetroPane.SAVES -> buildMemoryCards(activity, menu, savesLoadMode) { stageCloudBackup() }
                RetroPane.NETWORK -> buildDolphinNetplay(activity)
                RetroPane.DISPLAY -> buildDisplay(activity, menu, system, ::applyVar)
                RetroPane.FRAMEGEN -> emptyList()
                RetroPane.HUD ->
                    RetroHudSupport.buildHudEntries(
                        context = activity,
                        hudVisible = hudOn,
                        style = hudStyle,
                        elements = hudElements,
                        onMaster = { setHudVisible(it) },
                        onStyle = { next ->
                            hudStyle = next
                            frameRating?.let { RetroHudSupport.applyStyle(it, next, hudElements) }
                            RetroHudSupport.saveGlobalHudStyle(activity, next)
                        },
                        onElements = { next ->
                            hudElements = next
                            frameRating?.let { RetroHudSupport.applyStyle(it, hudStyle, next) }
                            RetroHudSupport.saveGlobalHudElements(activity, next)
                        },
                        onRebuild = { menu.rebuild() },
                    )
                RetroPane.SOUND ->
                    listOf(
                        RetroMenuEntry.Toggle(activity.getString(R.string.retro_lr_sound), checked = audioOn) { value ->
                            audioOn = value
                            applyVar("wn_audio", if (value) "1" else "0")
                            persistExtra(RetroShortcuts.KEY_AUDIO, if (value) "1" else "0")
                            menu.rebuild()
                        },
                    )
                RetroPane.CONTROLS ->
                    RetroControlsMenu.build(
                        RetroControlsMenu.Host(
                            context = activity,
                            overlay = input,
                            menu = menu,
                            systemId = system.id,
                            touchControls = { touchControls },
                            onTouchControls = { value ->
                                touchControls = value
                                if (controllerConnected) manualTouchOverride = true
                                input.visibility =
                                    if (touchEffective()) android.view.View.VISIBLE else android.view.View.GONE
                                updateGameArea()
                                persistExtra(RetroShortcuts.KEY_TOUCH_CONTROLS, if (value) "1" else "0")
                            },
                            adaptiveSticks = { adaptiveSticks },
                            onAdaptiveSticks = { value ->
                                adaptiveSticks = value
                                persistExtra(RetroShortcuts.KEY_ADAPTIVE_STICKS, if (value) "1" else "0")
                            },
                            orientationLabel = { activity.getString(R.string.retro_lr_landscape) },
                            onCloseMenu = { menu.close() },
                            showStickInversion = true,
                            onShellBackground = { updateGameArea() },
                        ),
                    )
                else -> emptyList()
            }
        }
        menu.bottomProvider = { buildBottom(activity, menu) { activity.stopGame() } }

        val compose =
            ComposeView(activity).apply {
                elevation = 2000f
                setContent {
                    WinNativeTheme {
                        Box(Modifier.fillMaxSize()) {
                            val screen by dolphinScreen
                            if (screen == "achievements") {
                                val dismiss = { dolphinScreen.value = null }
                                androidx.activity.compose.BackHandler(enabled = true) { dismiss() }
                                RetroAchievementsScreen(
                                    systemId = system.id,
                                    gameName = activity.intent.getStringExtra(
                                        DolphinEmulationActivity.EXTRA_GAME_NAME,
                                    ) ?: system.displayName,
                                    romPath = activity.intent.getStringExtra(
                                        DolphinEmulationActivity.EXTRA_ROM_PATH,
                                    ).orEmpty(),
                                    inSession = true,
                                    onClose = dismiss,
                                    floatingOverGame = true,
                                    nativeJson = {
                                        org.dolphinemu.dolphinemu.features.settings.model.AchievementModel
                                            .getAchievementsJSON()
                                    },
                                )
                            } else {
                                val hosting = DolphinNetplayHud.hosting
                                val code = DolphinNetplayHud.hostCode
                                val members = DolphinNetplayHud.members
                                LaunchedEffect(hosting, code, members) {
                                    if (menu.visible && menu.pane == RetroPane.NETWORK) menu.rebuild()
                                }
                                DolphinNetplayBanner(hosting, code, members)
                                RetroDrawerMenu(menu)
                            }
                        }
                    }
                }
            }
        root.addView(
            compose,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        if (hudOn) root.post { showHud() }

        activity.onBackPressedDispatcher.addCallback(activity) {
            when {
                input.editMode -> input.finishEdit()
                menu.visible -> menu.close()
                else -> menu.open()
            }
        }
        activity.menuButtonHandler = { if (menu.visible) menu.close() else menu.open() }
    }

    private fun buildMain(
        activity: DolphinEmulationActivity,
        menu: RetroMenuController,
        hudOn: Boolean,
        isWii: Boolean,
        onAchievements: () -> Unit,
        onHud: (Boolean) -> Unit,
        openMemoryCards: (load: Boolean) -> Unit,
    ): List<RetroMenuEntry> =
        buildList {
            if (isWii) {
                add(
                    RetroMenuEntry.Action(activity.getString(R.string.retro_dolphin_wii_home), Icons.Outlined.Home) {
                        activity.pressWiiHome()
                        menu.close()
                    },
                )
            }
            add(
                RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_achievements), RetroDrawerIcons.Achievements) {
                    onAchievements()
                },
            )
            add(
                RetroMenuEntry.Action(activity.getString(R.string.retro_lr_save_state), RetroDrawerIcons.Save) {
                    openMemoryCards(false)
                },
            )
            add(
                RetroMenuEntry.Action(activity.getString(R.string.retro_lr_load_save_state), RetroDrawerIcons.Load) {
                    openMemoryCards(true)
                },
            )
            add(
                RetroMenuEntry.Action(activity.getString(R.string.retro_lr_screenshot), RetroDrawerIcons.Hud) {
                    runCatching { org.dolphinemu.dolphinemu.NativeLibrary.SaveScreenShot() }
                    menu.close()
                },
            )
            add(
                RetroMenuEntry.Toggle(activity.getString(R.string.retro_lr_hud), checked = hudOn) { value ->
                    onHud(value)
                    menu.rebuild()
                },
            )
        }

    private fun buildMemoryCards(
        activity: DolphinEmulationActivity,
        menu: RetroMenuController,
        loadMode: Boolean,
        onSaved: () -> Unit,
    ): List<RetroMenuEntry> =
        (1..SLOT_COUNT).map { slot ->
            val ts = activity.stateSlotTime(slot)
            RetroMenuEntry.SaveSlot(
                slot = slot,
                title = activity.getString(R.string.retro_lr_memory_card_slot, slot),
                subtitle = RetroSaveStates.relativeTime(ts * 1000),
                filled = ts > 0L,
                onClick = {
                    if (loadMode) {
                        if (ts > 0L) {
                            activity.loadStateSlot(slot)
                            menu.close()
                            Toast
                                .makeText(activity, activity.getString(R.string.retro_lr_loaded_slot, slot), Toast.LENGTH_SHORT)
                                .show()
                        }
                    } else {
                        activity.saveStateSlot(slot)
                        onSaved()
                        Toast
                            .makeText(activity, activity.getString(R.string.retro_lr_saved_to_slot, slot), Toast.LENGTH_SHORT)
                            .show()
                        menu.rebuild()
                    }
                },
                onRename = {},
            )
        }

    private fun buildDisplay(
        activity: DolphinEmulationActivity,
        menu: RetroMenuController,
        system: RetroSystem,
        applyVar: (String, String) -> Unit,
    ): List<RetroMenuEntry> =
        buildList {
            val vars = DolphinEmulationActivity.currentVariables
            RetroCoreOptions.forSystem(system).forEach { option ->
                val current = vars[option.key] ?: option.defaultValue
                val index = option.values.indexOf(current).coerceAtLeast(0)
                add(
                    RetroMenuEntry.Choice(
                        option.labelText(activity),
                        option.valueLabels,
                        index,
                    ) { next ->
                        applyVar(option.key, option.values[next])
                        menu.rebuild()
                    },
                )
            }
        }

    private fun buildBottom(
        activity: DolphinEmulationActivity,
        menu: RetroMenuController,
        onExit: () -> Unit,
    ): List<RetroMenuEntry.Action> =
        listOf(
            if (activity.gamePaused) {
                RetroMenuEntry.Action(activity.getString(R.string.retro_lr_resume), RetroDrawerIcons.Resume, active = true) {
                    activity.resumeGame()
                    menu.close()
                }
            } else {
                RetroMenuEntry.Action(activity.getString(R.string.retro_lr_pause), RetroDrawerIcons.Pause) {
                    activity.pauseGame()
                    menu.close()
                }
            },
            RetroMenuEntry.Action(activity.getString(R.string.retro_lr_exit), RetroDrawerIcons.Exit, danger = true) {
                onExit()
            },
        )
}

object DolphinNetplayHud {
    var hosting by mutableStateOf(false)
        private set
    var hostCode by mutableStateOf<String?>(null)
        private set
    var members by mutableStateOf<List<String>>(emptyList())
        private set

    fun update(hosting: Boolean, hostCode: String?, members: List<String>) {
        this.hosting = hosting
        this.hostCode = hostCode
        this.members = members
    }
}
