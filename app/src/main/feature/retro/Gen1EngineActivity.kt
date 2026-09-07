package com.winlator.cmod.feature.retro

import android.os.Bundle
import android.system.Os
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.winlator.cmod.R
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.display.ui.FrameRating
import com.winlator.cmod.shared.theme.WinNativeTheme
import org.love2d.sdl.SDLActivity
import java.io.File

class Gen1EngineActivity :
    org.love2d.android.GameActivity(),
    RetroInputView.Listener,
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private var pad: RetroInputView? = null
    private var menuView: ComposeView? = null
    private lateinit var bridge: Gen1EngineBridge

    private var touchControls = true
    private var shellBackground = true
    private var engineVarsApplied = false

    private var persistShortcut: Shortcut? = null

    private var loadingVisible by androidx.compose.runtime.mutableStateOf(true)
    private var importState by
        androidx.compose.runtime.mutableStateOf<Gen1EngineBridge.Import?>(null)
    private var artwork by
        androidx.compose.runtime.mutableStateOf<android.graphics.Bitmap?>(null)

    private enum class SlotAction { SAVE, LOAD }

    private var slotAction = SlotAction.LOAD
    private val menu = RetroMenuController()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onSaveInstanceState(outState: Bundle) {
        savedStateController.performSave(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        bridge.startPolling(::onEngineState)
    }

    override fun onStop() {
        queueCloudBackup()
        bridge.stopPolling()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        handler.removeCallbacksAndMessages(null)
        bridge.shutdown()
        store.clear()
        com.winlator.cmod.shared.framegen.FrameGen.setSurfaceRebinder(null)
        com.winlator.cmod.shared.framegen.FrameGen.release()
        super.onDestroy()
    }

    private val heldDirections = HashSet<Int>()

    private fun sendKey(keyCode: Int, down: Boolean) {
        runCatching {
            if (down) SDLActivity.onNativeKeyDown(keyCode) else SDLActivity.onNativeKeyUp(keyCode)
        }.onFailure { Log.w(TAG, "key inject failed: ${it.message}") }
    }

    override fun onButton(keyCode: Int, down: Boolean) {
        if (menu.visible) {
            menu.handleKey(
                keyCode,
                if (down) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP,
            )
            return
        }
        val mapped = when (keyCode) {
            android.view.KeyEvent.KEYCODE_BUTTON_A -> android.view.KeyEvent.KEYCODE_Z
            android.view.KeyEvent.KEYCODE_BUTTON_B -> android.view.KeyEvent.KEYCODE_X
            android.view.KeyEvent.KEYCODE_BUTTON_START -> android.view.KeyEvent.KEYCODE_ESCAPE
            android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> android.view.KeyEvent.KEYCODE_TAB
            else -> return
        }
        sendKey(mapped, down)
    }

    override fun onDpad(x: Float, y: Float) {
        if (menu.visible) {
            menu.handleAxis(x, y)
            return
        }
        val wanted = HashSet<Int>(4)
        if (x <= -DPAD_DEADZONE) wanted.add(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        if (x >= DPAD_DEADZONE) wanted.add(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        if (y <= -DPAD_DEADZONE) wanted.add(android.view.KeyEvent.KEYCODE_DPAD_UP)
        if (y >= DPAD_DEADZONE) wanted.add(android.view.KeyEvent.KEYCODE_DPAD_DOWN)

        for (k in heldDirections - wanted) sendKey(k, false)
        for (k in wanted - heldDirections) sendKey(k, true)
        heldDirections.clear()
        heldDirections.addAll(wanted)
    }

    override fun onStick(x: Float, y: Float) = Unit

    override fun onRightStick(x: Float, y: Float) = Unit

    override fun onMenu() {
        runOnUiThread { openMenu() }
    }

    private fun openMenu() {
        releaseAllKeys()
        menu.open()
        pollFaster()
    }

    private fun releaseAllKeys() {
        for (k in heldDirections) sendKey(k, false)
        heldDirections.clear()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_MODE && isGamepadSource(event)) {
            if (event.action == android.view.KeyEvent.ACTION_UP) {
                if (menu.visible) menu.close() else openMenu()
            }
            return true
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_UP) {
                if (menu.visible) {
                    menu.handleKey(keyCode, android.view.KeyEvent.ACTION_UP)
                } else {
                    openMenu()
                }
            }
            return true
        }
        if (menu.visible) {
            menu.handleKey(keyCode, event.action)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        val joystick =
            event.source and android.view.InputDevice.SOURCE_JOYSTICK ==
                android.view.InputDevice.SOURCE_JOYSTICK
        if (menu.visible && joystick) {
            val hatX = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)
            val x =
                if (kotlin.math.abs(hatX) > 0.5f) hatX else event.getAxisValue(android.view.MotionEvent.AXIS_X)
            val y =
                if (kotlin.math.abs(hatY) > 0.5f) hatY else event.getAxisValue(android.view.MotionEvent.AXIS_Y)
            menu.handleAxis(x, y)
            return true
        }
        if (menu.visible) return true
        return super.dispatchGenericMotionEvent(event)
    }

    private fun buildTabs(): List<RetroTabSpec> =
        listOf(
            RetroTabSpec(null, RetroDrawerIcons.Play, getString(R.string.retro_tab_menu)),
            RetroTabSpec(
                RetroPane.DISPLAY,
                Icons.Outlined.Monitor,
                getString(R.string.retro_tab_display),
            ),
            RetroTabSpec(
                RetroPane.FRAMEGEN,
                Icons.Outlined.AutoAwesomeMotion,
                getString(R.string.session_drawer_frame_generation),
            ),
            RetroTabSpec(
                RetroPane.SOUND,
                Icons.AutoMirrored.Outlined.VolumeUp,
                getString(R.string.retro_tab_sound),
            ),
            RetroTabSpec(
                RetroPane.PERFORMANCE,
                Icons.Outlined.Bolt,
                getString(R.string.retro_ps2_tab_performance),
            ),
            RetroTabSpec(
                RetroPane.HUD,
                RetroDrawerIcons.Hud,
                getString(R.string.retro_tab_hud),
            ),
            RetroTabSpec(
                RetroPane.CONTROLS,
                Icons.Outlined.SportsEsports,
                getString(R.string.retro_tab_controls),
            ),
            RetroTabSpec(
                RetroPane.SYSTEM,
                Icons.Outlined.Tune,
                getString(R.string.retro_tab_system),
            ),
        )

    private fun paneForRow(id: String): RetroPane =
        when {
            Gen1EngineBridge.isModRow(id) -> RetroPane.DISPLAY
            id in SOUND_ROWS -> RetroPane.SOUND
            id in DISPLAY_ROWS -> RetroPane.DISPLAY
            id in PERFORMANCE_ROWS -> RetroPane.PERFORMANCE
            id in CONTROL_ROWS -> RetroPane.CONTROLS
            else -> RetroPane.SYSTEM
        }

    private fun isGamepadSource(event: android.view.KeyEvent): Boolean {
        val source = event.device?.sources ?: return false
        return source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD ||
            source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK
    }

    private fun stadiumRomEntry(row: Gen1EngineBridge.Row): RetroMenuEntry {
        val building = Gen1StadiumRom.isBuilding(row.value) || Gen1StadiumRom.hasStagedPick(this)
        val installed = Gen1StadiumRom.isInstalled(this)
        return RetroMenuEntry.Action(
            label = row.label,
            icon = RetroDrawerIcons.EditLayout,
            danger = installed && !building,
            subtitle =
                when {
                    building -> getString(R.string.retro_stadium_building)
                    installed -> getString(R.string.retro_stadium_ready)
                    else -> getString(R.string.retro_stadium_import)
                },
        ) {
            when {
                building -> Unit
                installed -> promptStadiumDelete()
                else -> pickStadiumRom()
            }
        }
    }

    private fun pickStadiumRom() {
        menu.close()
        val intent =
            android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        runCatching { startActivityForResult(intent, REQUEST_STADIUM_ROM) }
            .onFailure { toast(getString(R.string.retro_stadium_no_picker)) }
    }

    private fun promptStadiumDelete() {
        menu.confirmPrompt =
            RetroConfirmPrompt(
                title = getString(R.string.retro_stadium_row),
                message = getString(R.string.retro_stadium_delete_body),
                confirmLabel = getString(R.string.retro_stadium_delete),
                dismissLabel = getString(R.string.retro_stadium_keep),
                onConfirm = {
                    menu.confirmPrompt = null
                    val gone = Gen1StadiumRom.delete(this)
                    toast(
                        getString(
                            if (gone) {
                                R.string.retro_stadium_deleted
                            } else {
                                R.string.retro_stadium_delete_failed
                            },
                        ),
                    )
                    menu.rebuild()
                    pollFaster()
                },
                onDismiss = { menu.confirmPrompt = null },
            )
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?,
    ) {
        if (requestCode != REQUEST_STADIUM_ROM) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        toast(getString(R.string.retro_stadium_importing))
        lifecycleScope.launch(Dispatchers.IO) {
            val result = Gen1StadiumRom.stage(this@Gen1EngineActivity, uri)
            withContext(Dispatchers.Main) {
                result
                    .onSuccess {
                        toast(getString(R.string.retro_stadium_imported))
                        pollFaster()
                    }
                    .onFailure { toast(it.message ?: getString(R.string.retro_stadium_import_failed)) }
            }
        }
    }

    private fun rowEntry(row: Gen1EngineBridge.Row): RetroMenuEntry =
        when {
            row.id == Gen1StadiumRom.ROW_ID -> stadiumRomEntry(row)
            row.values.isNotEmpty() ->
                RetroMenuEntry.Choice(row.label, row.values, row.selectedIndex) { index ->
                    bridge.setRow(row.id, index)
                    pollFaster()
                }
            row.steppable ->
                RetroMenuEntry.Stepper(row.label, row.value) { direction ->
                    bridge.step(row.id, direction)
                    pollFaster()
                }
            else ->
                RetroMenuEntry.Action(row.label, RetroDrawerIcons.EditLayout, subtitle = row.value) {
                    bridge.activate(row.id)
                    menu.close()
                }
        }

    private fun toggleHalf(
        row: Gen1EngineBridge.Row,
        label: String,
        onIndex: Int,
    ): RetroMenuEntry.Toggle {
        val offIndex = if (onIndex == 0) 1 else 0
        return RetroMenuEntry.Toggle(
            label = label,
            subtitle = row.values.getOrNull(row.selectedIndex) ?: row.value,
            checked = row.selectedIndex == onIndex,
        ) { wanted ->
            bridge.setRow(row.id, if (wanted) onIndex else offIndex)
            pollFaster()
        }
    }

    private fun engineRows(pane: RetroPane): List<RetroMenuEntry> {
        val rows =
            bridge.state.rows.filter { paneForRow(it.id) == pane && it.id !in HIDDEN_ROWS }
        val ordered =
            if (pane == RetroPane.DISPLAY) {
                rows.filter { Gen1EngineBridge.isModRow(it.id) } +
                    rows.filterNot { Gen1EngineBridge.isModRow(it.id) }
            } else {
                rows
            }

        val animations = ordered.firstOrNull { it.id == ANIMATIONS_ROW }?.takeIf { it.values.size == 2 }
        val videoMode = ordered.firstOrNull { it.id == VIDEO_MODE_ROW }?.takeIf { it.values.size == 2 }
        val pair =
            if (animations != null && videoMode != null) {
                RetroMenuEntry.TogglePair(
                    left = toggleHalf(animations, animations.label, onIndex = 0),
                    right = toggleHalf(videoMode, getString(R.string.retro_engine_fullscreen), onIndex = 1),
                )
            } else {
                null
            }

        return buildList {
            for (row in ordered) {
                when {
                    pair != null && row.id == ANIMATIONS_ROW -> add(pair)
                    pair != null && row.id == VIDEO_MODE_ROW -> Unit
                    else -> add(rowEntry(row))
                }
            }
        }
    }

    private fun buildEntriesFor(pane: RetroPane?): List<RetroMenuEntry> =
        when (pane) {
            null -> buildMainEntries()
            RetroPane.SAVES -> buildSaveEntries()
            RetroPane.FRAMEGEN -> emptyList()
            RetroPane.CONTROLS -> buildControlEntries() + engineRows(RetroPane.CONTROLS)
            RetroPane.HUD -> buildHudEntries()
            else -> engineRows(pane)
        }

    private fun buildMainEntries(): List<RetroMenuEntry> =
        buildList {
            val activeSlot = bridge.state.slots.firstOrNull { it.active }?.name
            add(
                RetroMenuEntry.Action(
                    getString(R.string.retro_engine_save),
                    RetroDrawerIcons.Save,
                    subtitle = activeSlot,
                ) {
                    slotAction = SlotAction.SAVE
                    menu.showPane(RetroPane.SAVES)
                },
            )
            add(
                RetroMenuEntry.Action(
                    getString(R.string.retro_engine_load),
                    RetroDrawerIcons.Load,
                    subtitle = activeSlot,
                ) {
                    slotAction = SlotAction.LOAD
                    menu.showPane(RetroPane.SAVES)
                },
            )
            add(
                RetroMenuEntry.Action(
                    getString(R.string.retro_lr_fast_forward),
                    RetroDrawerIcons.FastForward,
                    active = bridge.state.fastForward,
                ) {
                    bridge.setFastForward(!bridge.state.fastForward)
                    pollFaster()
                },
            )
            add(
                RetroMenuEntry.Action(
                    getString(R.string.retro_lr_hud),
                    RetroDrawerIcons.Hud,
                    active = hudVisible,
                ) { setHudVisible(!hudVisible) },
            )
            add(
                RetroMenuEntry.Action(getString(R.string.retro_lr_reset), RetroDrawerIcons.Reset) {
                    bridge.reset()
                    menu.close()
                },
            )
            add(
                RetroMenuEntry.Action(
                    getString(R.string.retro_lr_achievements),
                    RetroDrawerIcons.Achievements,
                    subtitle = getString(R.string.retro_engine_achievements_unavailable),
                ) {
                    toast(getString(R.string.retro_engine_achievements_unavailable))
                },
            )
        }

    private fun slotSubtitle(slot: Gen1EngineBridge.Slot): String {
        val parts = mutableListOf<String>()
        if (slot.exists) {
            if (slot.playTime.isNotEmpty()) parts += slot.playTime
            if (slot.badges > 0) parts += resources.getQuantityString(R.plurals.retro_engine_badges, slot.badges, slot.badges)
            if (slot.caught > 0) parts += getString(R.string.retro_engine_caught, slot.caught)
        }
        if (slot.active) parts += getString(R.string.retro_engine_slot_active_only)
        return parts.joinToString(SUBTITLE_SEPARATOR)
    }

    private fun buildSaveEntries(): List<RetroMenuEntry> =
        buildList {
            val slots = bridge.state.slots
            slots.forEachIndexed { index, slot ->
                add(
                    RetroMenuEntry.SaveSlot(
                        slot = index,
                        title = slot.name,
                        subtitle = slotSubtitle(slot),
                        filled = slot.exists,
                        onClick = {
                            when (slotAction) {
                                SlotAction.SAVE -> {
                                    bridge.saveToSlot(slot.id)
                                    toast(getString(R.string.retro_engine_saved_to, slot.name))
                                    queueCloudBackupAfterSave()
                                    pollFaster()
                                    menu.close()
                                }
                                SlotAction.LOAD ->
                                    if (!slot.exists) {
                                        toast(getString(R.string.retro_engine_slot_empty))
                                    } else {
                                        bridge.loadSlot(slot.id)
                                        toast(getString(R.string.retro_engine_loaded_from, slot.name))
                                        pollFaster()
                                        menu.close()
                                    }
                            }
                        },
                        onRename = {
                            menu.renamePrompt =
                                RetroRenamePrompt(
                                    title = getString(R.string.retro_engine_rename_slot),
                                    initial = slot.name,
                                ) { entered ->
                                    val name = entered?.trim().orEmpty()
                                    if (name.isNotEmpty() && name != slot.name) {
                                        bridge.renameSlot(slot.id, name)
                                        pollFaster()
                                    }
                                }
                        },
                    ),
                )
            }
            if (slots.isEmpty()) {
                add(
                    RetroMenuEntry.Action(
                        getString(R.string.retro_engine_no_slots),
                        RetroDrawerIcons.Save,
                    ) {},
                )
            }
            if (slotAction == SlotAction.SAVE) {
                add(
                    RetroMenuEntry.Action(getString(R.string.retro_engine_new_slot), RetroDrawerIcons.Add) {
                        bridge.newSlot()
                        queueCloudBackupAfterSave()
                        pollFaster()
                        menu.close()
                    },
                )
            }
        }

    private fun buildControlEntries(): List<RetroMenuEntry> =
        RetroControlsMenu.build(
            RetroControlsMenu.Host(
                context = this,
                overlay = pad,
                menu = menu,
                systemId = RetroSystems.GAMEBOY.id,
                touchControls = { touchControls },
                onTouchControls = { value ->
                    touchControls = value
                    applyTouchControls()
                    persistExtra(RetroShortcuts.KEY_TOUCH_CONTROLS, if (value) "1" else "0")
                },
                adaptiveSticks = { false },
                onAdaptiveSticks = { },
                orientationLabel = {
                    val host = mLayout
                    if ((host?.height ?: 0) > (host?.width ?: 0)) {
                        getString(R.string.retro_lr_portrait)
                    } else {
                        getString(R.string.retro_lr_landscape)
                    }
                },
                onCloseMenu = { menu.close() },
                showStickInversion = false,
                onShellBackground = { value ->
                    shellBackground = value
                    val host = mLayout
                    pad?.let { view -> host?.post { updateGameArea(host, view) } }
                },
            ),
        )

    private var hudVisible = false
    private var hudStyle = HudStyle()
    private var hudElements = RetroHudSupport.defaultElements()
    private var frameRating: FrameRating? = null

    private fun buildHudEntries(): List<RetroMenuEntry> =
        RetroHudSupport.buildHudEntries(
            context = this,
            hudVisible = hudVisible,
            style = hudStyle,
            elements = hudElements,
            onMaster = { setHudVisible(it) },
            onStyle = { next ->
                hudStyle = next
                frameRating?.let { RetroHudSupport.applyStyle(it, next, hudElements) }
                RetroHudSupport.saveGlobalHudStyle(this, next)
            },
            onElements = { next ->
                hudElements = next
                frameRating?.let { RetroHudSupport.applyStyle(it, hudStyle, next) }
                RetroHudSupport.saveGlobalHudElements(this, next)
            },
            onRebuild = { menu.rebuild() },
        )

    private fun setHudVisible(value: Boolean) {
        hudVisible = value
        if (value) {
            showHud()
        } else {
            frameRating?.visibility = android.view.View.GONE
            handler.removeCallbacks(hudTick)
        }
        persistExtra(RetroShortcuts.KEY_HUD, if (value) "1" else "0")
        menu.rebuild()
    }

    private val hudTick =
        object : Runnable {
            override fun run() {
                val rating = frameRating ?: return
                val fps = bridge.state.fps
                if (!hudVisible || bridge.state.paused || fps <= 0) {
                    handler.postDelayed(this, HUD_IDLE_TICK_MS)
                    return
                }
                rating.recordGameFrame()
                handler.postDelayed(this, (1000L / fps).coerceAtLeast(1L))
            }
        }

    private fun showHud() {
        val host = mLayout ?: return
        var rating = frameRating
        if (rating == null) {
            rating = RetroHudSupport.createFrameRating(this, ENGINE_RENDERER_LABEL)
            frameRating = rating
            host.addView(rating, host.indexOfChild(menuView).coerceAtLeast(0))
            RetroHudSupport.applyStyle(rating, hudStyle, hudElements)
        }
        rating.visibility = android.view.View.VISIBLE
        rating.reset()
        RetroHudSupport.bindFrameGeneration(rating)
        handler.removeCallbacks(hudTick)
        handler.post(hudTick)
    }

    private fun buildBottomEntries(): List<RetroMenuEntry.Action> =
        buildList {
            if (bridge.state.paused) {
                add(
                    RetroMenuEntry.Action(
                        getString(R.string.retro_lr_resume),
                        RetroDrawerIcons.Resume,
                        active = true,
                    ) {
                        bridge.setPaused(false)
                        pollFaster()
                        menu.close()
                    },
                )
            } else {
                add(
                    RetroMenuEntry.Action(getString(R.string.retro_lr_pause), RetroDrawerIcons.Pause) {
                        bridge.setPaused(true)
                        pollFaster()
                    },
                )
            }
            add(
                RetroMenuEntry.Action(getString(R.string.retro_lr_exit), RetroDrawerIcons.Exit, danger = true) {
                    bridge.saveGame()
                    queueCloudBackupAfterSave()
                    menu.close()
                    handler.postDelayed({ finish() }, EXIT_SAVE_GRACE_MS)
                },
            )
        }

    private fun applyTouchControls() {
        pad?.visibility = if (touchControls) android.view.View.VISIBLE else android.view.View.GONE
        val host = mLayout ?: return
        pad?.let { view -> host.post { updateGameArea(host, view) } }
    }

    private fun persistExtra(key: String, value: String) {
        val path = intent.getStringExtra(EXTRA_SHORTCUT_PATH) ?: return
        Thread {
            runCatching {
                val shortcut =
                    persistShortcut ?: run {
                        val file = File(path)
                        if (!file.isFile) return@runCatching
                        val cm = ContainerManager(this)
                        Shortcut(cm.retroContainer, file)
                            .also { persistShortcut = it }
                    }
                shortcut.putExtra(key, value)
                shortcut.saveData()
            }.onFailure { Log.w(TAG, "could not persist $key: ${it.message}") }
        }.start()
    }

    private fun loadPersistedSettings() {
        val path = intent.getStringExtra(EXTRA_SHORTCUT_PATH)
        val shortcut =
            runCatching {
                path?.let { File(it) }?.takeIf { it.isFile }?.let { file ->
                    Shortcut(
                        ContainerManager(this).retroContainer,
                        file,
                    )
                }
            }.getOrNull()
        persistShortcut = shortcut

        touchControls =
            shortcut?.getExtra(RetroShortcuts.KEY_TOUCH_CONTROLS)?.takeIf { it.isNotEmpty() }?.let { it != "0" }
                ?: RetroDefaults.touchControls(this, RetroSystems.GAMEBOY.id)
        shellBackground = RetroControlLayouts.shellBackground(this, RetroSystems.GAMEBOY.id)
        hudVisible = shortcut?.getExtra(RetroShortcuts.KEY_HUD)?.takeIf { it.isNotEmpty() }?.let { it != "0" } ?: false
        hudStyle = RetroHudSupport.loadGlobalHudStyle(this)
        hudElements = RetroHudSupport.loadGlobalHudElements(this)
    }

    private fun queueCloudBackup() {
        val shortcut = persistShortcut ?: return
        if (shortcut.getExtra("cloud_sync_enabled", "1") == "0") return
        val gameName = intent.getStringExtra(EXTRA_GAME_NAME).orEmpty()
            .ifBlank { shortcut.getExtra("custom_name", shortcut.name) }
        val app = applicationContext
        Thread {
            runCatching { Gen1CloudSync.queueBackup(app, Gen1CloudSync.cloudId(shortcut), gameName) }
                .onFailure { Log.w(TAG, "could not queue cloud backup: ${it.message}") }
        }.apply { name = "gen1-cloud-queue"; start() }
    }

    private fun queueCloudBackupAfterSave() {
        handler.postDelayed({ queueCloudBackup() }, SAVE_SETTLE_MS)
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun onEngineState(state: Gen1EngineBridge.State, menuChanged: Boolean) {
        importState = state.import
        if (loadingVisible && state.booted) loadingVisible = false
        if (state.booted && state.rows.isNotEmpty()) {
            if (!engineVarsApplied) {
                engineVarsApplied = true
                @Suppress("UNCHECKED_CAST")
                val wanted =
                    intent.getSerializableExtra(EXTRA_ENGINE_VARS) as? HashMap<String, String>
                if (wanted != null) Gen1EngineSettings.applyTo(bridge, wanted)
            }
            Gen1EngineSettings.cache(this, state.rows)
        }
        if (menuChanged && menu.visible) menu.rebuild()
    }

    private fun pollFaster() = bridge.pollNow()

    override fun loadLibraries() {
        val dir = engineLibDir(this)
        for (lib in ENGINE_LIBS) {
            val so = File(dir, "lib$lib.so")
            if (!so.isFile) {
                throw UnsatisfiedLinkError("engine library missing from bundle: ${so.absolutePath}")
            }
            System.load(so.absolutePath)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        savedStateController.performRestore(savedInstanceState)

        val rom = intent.getStringExtra(EXTRA_ROM_PATH)
        val version = intent.getStringExtra(EXTRA_VERSION)
        Log.i(TAG, "onCreate rom=$rom version=$version")

        val prefetch = startSettingsPrefetch()

        Gen1ModInstaller.ensureInstalled(this)

        bridge = Gen1EngineBridge(this)
        bridge.clearStale()

        com.winlator.cmod.shared.framegen.FrameGen.installFromIntent(this, intent)
        com.winlator.cmod.shared.framegen.FrameGen.applyDisplayMode(this)
        com.winlator.cmod.shared.framegen.FrameGen.setSurfaceRebinder {
            runOnUiThread {
                if (!isFinishing && !isDestroyed && mSurface != null) {
                    runCatching {
                        SDLActivity.onNativeSurfaceDestroyed()
                        SDLActivity.onNativeSurfaceCreated()
                    }
                }
            }
        }

        runCatching {
            if (!rom.isNullOrEmpty()) Os.setenv("POKEPORT_IMPORT_ROM", rom, true)
            if (!version.isNullOrEmpty()) Os.setenv("POKEPORT_VERSION", version, true)
        }.onFailure { Log.w(TAG, "could not set engine environment: ${it.message}") }

        super.onCreate(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        menu.entriesProvider = { pane -> buildEntriesFor(pane) }
        menu.paneContentProvider = { pane ->
            if (pane == RetroPane.FRAMEGEN) {
                {
                    RetroFrameGenPane.Content(
                        this,
                        persistShortcut,
                        persistShortcut?.let { RetroShortcuts.systemForShortcut(it) }?.id,
                    )
                }
            } else {
                null
            }
        }
        menu.bottomProvider = { buildBottomEntries() }
        menu.tabs = buildTabs()

        window.decorView.let { root ->
            root.setViewTreeLifecycleOwner(this)
            root.setViewTreeViewModelStoreOwner(this)
            root.setViewTreeSavedStateRegistryOwner(this)
        }

        val host = mLayout
        if (host == null) {
            Log.w(TAG, "SDL layout missing; pad and menu not attached")
            return
        }

        pad = RetroInputView(this, this, RetroSystems.GAMEBOY).also { view ->
            view.loadStickInversion()
            view.hapticStrength =
                androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(this)
                    .getFloat(PREF_HAPTIC, DEFAULT_HAPTIC)
            view.setCustomColors(RetroControlLayouts.loadColors(this, RetroSystems.GAMEBOY.id))

            host.addView(
                view,
                android.widget.RelativeLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateGameArea(host, view)
            }
            host.post { updateGameArea(host, view) }
        }

        val menuComposeView =
            ComposeView(this).apply {
                elevation = MENU_ELEVATION
                setViewTreeLifecycleOwner(this@Gen1EngineActivity)
                setViewTreeViewModelStoreOwner(this@Gen1EngineActivity)
                setViewTreeSavedStateRegistryOwner(this@Gen1EngineActivity)
                setContent {
                    WinNativeTheme {
                        androidx.compose.runtime.LaunchedEffect(menu.visible, loadingVisible) {
                            bridge.setPollFast(menu.visible || loadingVisible)
                        }
                        Box(Modifier.fillMaxSize()) {
                            RetroDrawerMenu(menu)
                            Gen1LoadingScreen(
                                gameName = intent.getStringExtra(EXTRA_GAME_NAME).orEmpty(),
                                artwork = artwork,
                                state = importState,
                                visible = loadingVisible,
                            )
                        }
                    }
                }
            }
        menuView = menuComposeView
        host.addView(
            menuComposeView,
            android.widget.RelativeLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        prefetch.join()

        pad?.shellBackground = shellBackground
        applyTouchControls()
        if (hudVisible) host.post { showHud() }
    }

    private fun startSettingsPrefetch(): Thread =
        Thread {
            runCatching { loadPersistedSettings() }
                .onFailure { Log.w(TAG, "could not read saved settings: ${it.message}") }

            runCatching {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            }

            val decoded =
                intent.getStringExtra(EXTRA_ARTWORK_PATH)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { path -> decodeArtwork(path) }
            if (decoded != null) handler.post { artwork = decoded }
        }.apply {
            name = "gen1-settings"
            priority = Thread.NORM_PRIORITY - 1
            start()
        }

    private fun decodeArtwork(path: String): android.graphics.Bitmap? =
        runCatching {
            val bounds =
                android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@runCatching null
            val target = (ARTWORK_MAX_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)
            var sample = 1
            while (longest / (sample * 2) >= target) sample *= 2
            android.graphics.BitmapFactory.decodeFile(
                path,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()

    private fun updateGameArea(host: android.view.ViewGroup, view: RetroInputView) {
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return
        val portrait = h >= w
        val boxed = touchControls && shellBackground

        val budgetHeight = if (portrait && boxed) (h * PORTRAIT_GAME_HEIGHT_FRACTION).toInt() else h
        val scale = minOf(w / GB_WIDTH, budgetHeight / GB_HEIGHT).coerceAtLeast(1)
        val gameWidth = (GB_WIDTH * scale).toFloat()
        val gameHeight = (GB_HEIGHT * scale).toFloat()

        val left = (w - gameWidth) * 0.5f
        val top = if (portrait && boxed) 0f else (h - gameHeight) * 0.5f
        val area = android.graphics.RectF(left, top, left + gameWidth, top + gameHeight)

        view.setGameArea(area)
        applySurfaceBounds(area)
        applyFillScale(area, w, h)
    }

    private fun applyFillScale(area: android.graphics.RectF, hostWidth: Int, hostHeight: Int) {
        val surface = mSurface ?: return
        if ((touchControls && shellBackground) || area.width() <= 0f || area.height() <= 0f) {
            surface.scaleX = 1f
            surface.scaleY = 1f
            return
        }
        surface.pivotX = area.width() * 0.5f
        surface.pivotY = area.height() * 0.5f
        surface.scaleX = hostWidth / area.width()
        surface.scaleY = hostHeight / area.height()
    }

    private fun applySurfaceBounds(area: android.graphics.RectF) {
        val want =
            android.graphics.Rect(
                area.left.toInt(),
                area.top.toInt(),
                area.right.toInt(),
                area.bottom.toInt(),
            )
        if (want.isEmpty || want == surfaceBounds) return
        val surface = mSurface ?: return
        surfaceBounds = want
        surface.layoutParams =
            android.widget.RelativeLayout.LayoutParams(want.width(), want.height()).apply {
                leftMargin = want.left
                topMargin = want.top
            }
    }

    private var surfaceBounds: android.graphics.Rect? = null

    override fun onPause() {
        releaseAllKeys()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun getArguments(): Array<String> {
        val rom = intent?.getStringExtra(EXTRA_ROM_PATH)
        val version = intent?.getStringExtra(EXTRA_VERSION)
        val args = ArrayList<String>(4)
        if (!rom.isNullOrEmpty()) { args.add("--import-rom"); args.add(rom) }
        if (!version.isNullOrEmpty()) { args.add("--game-version"); args.add(version) }
        Log.i(TAG, "engine argv: $args")
        return args.toTypedArray()
    }

    companion object {
        private const val TAG = "WnGen1Engine"

        private const val REQUEST_STADIUM_ROM = 0x5D01

        const val EXTRA_ROM_PATH = "wn.engine.rom"
        const val EXTRA_VERSION = "wn.engine.version"
        const val EXTRA_GAME_NAME = "wn.engine.game_name"
        const val EXTRA_SHORTCUT_PATH = "wn.engine.shortcut"
        const val EXTRA_ENGINE_VARS = "wn.engine.vars"
        const val EXTRA_ARTWORK_PATH = "wn.engine.artwork"

        private const val DPAD_DEADZONE = 0.35f

        private const val PREF_HAPTIC = "retro_haptic_strength"
        private const val DEFAULT_HAPTIC = 0.4f

        private const val ENGINE_RENDERER_LABEL = "LOVE / GLES"

        private const val MENU_ELEVATION = 2000f

        private const val SUBTITLE_SEPARATOR = "  \u00b7  "

        private const val HUD_IDLE_TICK_MS = 250L

        private const val ARTWORK_MAX_DP = 240f

        private const val EXIT_SAVE_GRACE_MS = 400L

        private const val SAVE_SETTLE_MS = 700L

        private const val ANIMATIONS_ROW = "animations"
        private const val VIDEO_MODE_ROW = "videoMode"

        private val HIDDEN_ROWS = setOf("touchControls")

        private val SOUND_ROWS = setOf("musicVol", "sfxVol", "pikaVol", "musicFilter")
        private val DISPLAY_ROWS =
            setOf("colors", "tilt", "gbcfx", "zoom", "voidFill", "videoMode", "animations")
        private val PERFORMANCE_ROWS = setOf("fpsCap", "speed")
        private val CONTROL_ROWS = setOf("controls")

        private const val GB_WIDTH = 160
        private const val GB_HEIGHT = 144

        private const val PORTRAIT_GAME_HEIGHT_FRACTION = 0.6f

        private val ENGINE_LIBS = listOf("c++_shared", "mpg123", "openal", "love")

        fun engineDir(context: android.content.Context): File =
            File(RetroBundle.root(context), "data/gen1recomp")

        fun engineLibDir(context: android.content.Context): File =
            File(engineDir(context), "lib")

        fun gameArchive(context: android.content.Context): File =
            File(engineDir(context), "game.love")

        fun isInstalled(context: android.content.Context): Boolean =
            gameArchive(context).isFile &&
                ENGINE_LIBS.all { File(engineLibDir(context), "lib$it.so").isFile }
    }
}
