package com.winlator.cmod.feature.retro

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import com.armsx2.WinNativeHost
import com.winlator.cmod.R
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.WindowImpl
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.display.ui.FrameRating
import com.winlator.cmod.shared.theme.WinNativeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kr.co.iefriends.pcsx2.NativeApp

const val PS2_DEFAULT_DNS = "45.7.228.197"

data class Ps2NetHost(val url: String, val ip: String)

object Ps2GameOverlay {
    private const val FULL = 32767

    @Volatile
    private var attachedTo: java.lang.ref.WeakReference<ComponentActivity>? = null

    fun install() {
        attachedTo = null
        WinNativeHost.attachOverlay = attach@{ activity ->
            if (attachedTo?.get() === activity) return@attach
            attachedTo = java.lang.ref.WeakReference(activity)
            attach(activity)
        }
        WinNativeHost.applyBootSettings = { ctx -> applyBootConfig(ctx) }
    }

    private fun loadShortcut(activity: android.app.Activity): Shortcut? {
        val containerId = activity.intent?.getIntExtra(RetroShortcuts.EXTRA_PS2_CONTAINER_ID, -1) ?: -1
        val path = activity.intent?.getStringExtra(RetroShortcuts.EXTRA_PS2_SHORTCUT_PATH)
        if (containerId < 0 || path.isNullOrBlank()) return null
        val file = java.io.File(path)
        if (!file.isFile) return null
        return runCatching {
            val cm = ContainerManager(activity)
            val container =
                if (containerId == ContainerManager.RETRO_CONTAINER_ID) cm.retroContainer else cm.getContainerById(containerId)
            container?.let { Shortcut(it, file) }
        }.getOrNull()
    }

    const val PREF_SHELL_BG = "wn.ps2.shellbg"

    private fun ps2Prefs(ctx: android.content.Context) =
        ctx.getSharedPreferences("ARMSX2", android.content.Context.MODE_PRIVATE)

    private fun fmvAspectName(value: Int): String =
        when (value) {
            1 -> "Auto 4:3/3:2"
            2 -> "4:3"
            3 -> "16:9"
            else -> "Off"
        }

    private fun writePatchSettings(ctx: android.content.Context) {
        val prefs = ps2Prefs(ctx)
        NativeApp.setSetting("EmuCore", "EnableWideScreenPatches", "bool", prefs.getBoolean("wn.ps2.widescreen", false).toString())
        NativeApp.setSetting("EmuCore", "EnableNoInterlacingPatches", "bool", prefs.getBoolean("wn.ps2.nointerlace", false).toString())
        NativeApp.setSetting("EmuCore", "EnableFastBoot", "bool", prefs.getBoolean("wn.ps2.fastboot", true).toString())
    }

    private fun blankHddFile(ctx: android.content.Context): java.io.File =
        java.io.File(ctx.filesDir, "hdd/DEV9hdd.raw")

    private fun resolveHdd(ctx: android.content.Context): Pair<java.io.File?, Boolean> {
        val prefs = ps2Prefs(ctx)
        val image = RetroHddImport.imageFile(ctx, prefs.getString("wn.ps2.hddimage", ""))
        if (image != null) return image to true
        if (prefs.getBoolean("wn.ps2.hdd", false)) return blankHddFile(ctx) to true
        return null to false
    }

    private fun ensureHddImage(ctx: android.content.Context) {
        val prefs = ps2Prefs(ctx)
        if (RetroHddImport.imageFile(ctx, prefs.getString("wn.ps2.hddimage", "")) != null) return
        if (!prefs.getBoolean("wn.ps2.hdd", false)) return
        val img = blankHddFile(ctx)
        if (img.exists() && img.length() > 0L) return
        runCatching {
            img.parentFile?.mkdirs()
            java.io.RandomAccessFile(img, "rw").use { it.setLength(8L * 1024 * 1024 * 1024) }
        }
    }

    private fun readHosts(prefs: android.content.SharedPreferences): List<Ps2NetHost> {
        val raw = prefs.getString("wn.ps2.net.hosts", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Ps2NetHost(o.optString("url"), o.optString("ip", "0.0.0.0"))
            }.filter { it.url.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun writeDev9Settings(ctx: android.content.Context) {
        val prefs = ps2Prefs(ctx)
        val on = prefs.getBoolean("wn.ps2.net.enable", true)
        NativeApp.setSetting("DEV9/Eth", "EthEnable", "bool", on.toString())
        NativeApp.setSetting("DEV9/Eth", "EthApi", "string", "Sockets")
        NativeApp.setSetting("DEV9/Eth", "EthDevice", "string", (prefs.getString("wn.ps2.net.ethdevice", "Auto") ?: "Auto").ifBlank { "Auto" })
        NativeApp.setSetting("DEV9/Eth", "InterceptDHCP", "bool", prefs.getBoolean("wn.ps2.net.dhcp", true).toString())
        NativeApp.setSetting("DEV9/Eth", "AutoMask", "bool", "true")
        NativeApp.setSetting("DEV9/Eth", "AutoGateway", "bool", "true")
        val hosts = readHosts(prefs)
        val chosen = prefs.getString("wn.ps2.net.dnsmode", "Manual") ?: "Manual"
        val mode =
            when {
                hosts.isNotEmpty() -> "Internal"
                chosen.equals("Manual", ignoreCase = true) -> "Manual"
                else -> "Internal"
            }
        NativeApp.setSetting("DEV9/Eth", "ModeDNS1", "string", mode)
        NativeApp.setSetting("DEV9/Eth", "ModeDNS2", "string", "Auto")
        NativeApp.setSetting("DEV9/Eth", "DNS1", "string", (prefs.getString("wn.ps2.net.dns1", PS2_DEFAULT_DNS) ?: PS2_DEFAULT_DNS).ifBlank { PS2_DEFAULT_DNS })
        NativeApp.setSetting("DEV9/Eth", "DNS2", "string", (prefs.getString("wn.ps2.net.dns2", "") ?: "").ifBlank { "0.0.0.0" })
        NativeApp.setSetting("DEV9/Eth/Hosts", "Count", "int", hosts.size.toString())
        hosts.forEachIndexed { i, h ->
            NativeApp.setSetting("DEV9/Eth/Hosts/Host$i", "Url", "string", h.url)
            NativeApp.setSetting("DEV9/Eth/Hosts/Host$i", "Desc", "string", "WinNative")
            NativeApp.setSetting("DEV9/Eth/Hosts/Host$i", "Address", "string", h.ip.ifBlank { "0.0.0.0" })
            NativeApp.setSetting("DEV9/Eth/Hosts/Host$i", "Enabled", "bool", "true")
        }
        val (hddFile, hddOn) = resolveHdd(ctx)
        NativeApp.setSetting("DEV9/Hdd", "HddEnable", "bool", hddOn.toString())
        NativeApp.setSetting("DEV9/Hdd", "HddFile", "string", (hddFile ?: blankHddFile(ctx)).absolutePath)
    }

    fun applyBootConfig(ctx: android.content.Context) {
        runCatching {
            applyTurnipDebug(ctx)
            ensureHddImage(ctx)
            writeDev9Settings(ctx)
            writePatchSettings(ctx)
            RetroHudSupport.suppressNativePs2Osd()
            applyDisplayAspect(ctx, live = false)
        }
    }

    private fun applyDisplayAspect(
        ctx: android.content.Context,
        live: Boolean,
    ) {
        val prefs = ps2Prefs(ctx)
        val aspect = resolveBootAspect(prefs)
        val name =
            when (aspect) {
                3 -> "16:9"
                else -> "4:3"
            }
        runCatching {
            NativeApp.setSetting("EmuCore/GS", "AspectRatio", "string", name)
            NativeApp.setAspectRatio(aspect)
            if (!live) {
                NativeApp.commitSettings()
            }
        }
    }

    private fun resolveBootAspect(prefs: android.content.SharedPreferences): Int {
        val aspect = prefs.getInt("wn.ps2.aspect", 1).coerceIn(0, 3)
        return when (aspect) {
            3 -> 3
            2 -> 2
            else -> 2
        }
    }

    private fun applyTurnipDebug(ctx: android.content.Context) {
        val prefs = ps2Prefs(ctx)
        val driver = (prefs.getString("wn.ps2.driver", "") ?: "").trim()
        val usingTurnip = driver.isNotEmpty() && !driver.equals("system", ignoreCase = true)
        val flags = (prefs.getString("wn.ps2.turnipflags", "") ?: "").trim()
        runCatching {
            if (usingTurnip && flags.isNotEmpty()) {
                android.system.Os.setenv("TU_DEBUG", flags, true)
            } else {
                android.system.Os.unsetenv("TU_DEBUG")
            }
        }
    }

    private fun mapFace(keyCode: Int): Int =
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
            else -> keyCode
        }

    private fun emitAxis(pos: Int, neg: Int, v: Float) {
        val mag = (kotlin.math.abs(v) * FULL).toInt().coerceAtMost(FULL)
        NativeApp.setPadButton(pos, if (v > 0f) mag else 0, v > 0f)
        NativeApp.setPadButton(neg, if (v < 0f) mag else 0, v < 0f)
    }

    private fun attach(activity: ComponentActivity) {
        val menu = RetroMenuController()
        val ps2Screen = mutableStateOf<String?>(null)
        var savesLoadMode = false
        var pad: RetroInputView? = null
        val touchVisible =
            mutableStateOf(
                ps2Prefs(activity).getBoolean("wn.ps2.touchcontrols", RetroDefaults.touchControls(activity, RetroSystems.PS2.id)),
            )
        fun anyGameController(): Boolean =
            android.view.InputDevice.getDeviceIds().any {
                com.winlator.cmod.runtime.input.controls.ExternalController.isGameController(android.view.InputDevice.getDevice(it))
            }
        val controllerConnected = mutableStateOf(anyGameController())
        val manualTouchOverride = mutableStateOf(false)
        val launchRomPath = activity.intent?.getStringExtra(RetroShortcuts.EXTRA_PS2_ROM_PATH).orEmpty()
        val launchGameName = activity.intent?.getStringExtra(RetroShortcuts.EXTRA_PS2_GAME_NAME).orEmpty()
        val inputManager = activity.getSystemService(android.hardware.input.InputManager::class.java)
        inputManager?.registerInputDeviceListener(
            object : android.hardware.input.InputManager.InputDeviceListener {
                private fun refreshPadPresence() {
                    val was = controllerConnected.value
                    controllerConnected.value = anyGameController()
                    if (controllerConnected.value != was) manualTouchOverride.value = false
                    val showPad = touchVisible.value && (!controllerConnected.value || manualTouchOverride.value)
                    RetroAchievementOverlayState.syncPlacement(showPad, controllerConnected.value)
                    MainActivityRuntime.runWhenNativeReady {
                        runCatching { RetroPs2OsdPlacement.apply(showPad, controllerConnected.value) }
                    }
                }

                override fun onInputDeviceAdded(deviceId: Int) = refreshPadPresence()

                override fun onInputDeviceRemoved(deviceId: Int) = refreshPadPresence()

                override fun onInputDeviceChanged(deviceId: Int) = refreshPadPresence()
            },
            null,
        )
        var customColors = RetroControlLayouts.loadColors(activity, RetroSystems.PS2.id)
        var wnPaused = false
        val prefs = activity.getSharedPreferences("ARMSX2", android.content.Context.MODE_PRIVATE)
        var frameRating: FrameRating? = null
        var shortcut: Shortcut? = null
        val pendingExtras = LinkedHashMap<String, String>()
        fun persistExtra(key: String, value: String) {
            val target = shortcut
            if (target == null) {
                pendingExtras[key] = value
                return
            }
            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch(Dispatchers.IO) {
                runCatching {
                    target.putExtra(key, value)
                    target.saveData()
                }
            }
        }
        var hudVisible = RetroHudSupport.consoleHudDefault(activity, RetroSystems.PS2.id)
        var hudStyle = RetroHudSupport.loadPs2HudStyle(activity)
        var hudElements = RetroHudSupport.loadPs2Elements(activity)
        val menuToggleGate = RetroHudSupport.MenuToggleGate()
        val frameSource =
            RetroHudSupport.Ps2FrameSource(
                ratingProvider = { frameRating },
                enabledProvider = { hudVisible },
            )
        if (com.winlator.cmod.shared.framegen.FrameGen.requested) frameSource.start()

        fun persistColors() {
            RetroControlLayouts.saveColors(activity, RetroSystems.PS2.id, customColors)
            pad?.setCustomColors(customColors)
            menu.rebuild()
        }

        fun applyHudToRating() {
            val rating = frameRating ?: return
            RetroHudSupport.applyStyle(rating, hudStyle, hudElements)
        }

        fun showHud() {
            var rating = frameRating
            if (rating == null) {
                val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
                val renderer =
                    when (prefs.getString("wn.ps2.renderer", "vulkan")) {
                        "opengl" -> "OpenGL"
                        "software" -> "Software"
                        else -> "Vulkan"
                    }
                rating = RetroHudSupport.createFrameRating(activity, renderer)
                frameRating = rating
                RetroHudSupport.attachFrameRating(root, rating)
                applyHudToRating()
            }
            rating.visibility = View.VISIBLE
            rating.reset()
            RetroHudSupport.bindFrameGeneration(rating)
            frameSource.start()
        }

        fun hideHud() {
            frameRating?.visibility = View.GONE
        }

        fun setHudVisible(value: Boolean) {
            hudVisible = value
            persistExtra(RetroShortcuts.KEY_HUD, if (value) "1" else "0")
            if (value) showHud() else hideHud()
            menu.rebuild()
        }

        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            val loaded = loadShortcut(activity)
            activity.runOnUiThread {
                shortcut = loaded
                if (loaded != null) {
                    val perGame = loaded.getExtra(RetroShortcuts.KEY_HUD)
                    if (perGame.isNotEmpty() && (perGame == "1") != hudVisible) {
                        setHudVisible(perGame == "1")
                    }
                    if (pendingExtras.isNotEmpty()) {
                        val queued = pendingExtras.toMap()
                        pendingExtras.clear()
                        @Suppress("OPT_IN_USAGE")
                        GlobalScope.launch(Dispatchers.IO) {
                            runCatching {
                                queued.forEach { (k, v) -> loaded.putExtra(k, v) }
                                loaded.saveData()
                            }
                        }
                    }
                }
            }
        }

        fun gsSet(key: String, type: String, value: String) {
            runCatching {
                NativeApp.setSetting("EmuCore/GS", key, type, value)
                NativeApp.commitSettings()
                NativeApp.applyGSSettingsLive()
            }
        }

        fun bg(block: () -> Unit) {
            MainActivityRuntime.runWhenNativeReady { runCatching { block() } }
        }

        fun nativeUp(): Boolean = MainActivityRuntime.isNativeReady()

        fun gsSetAsync(key: String, type: String, value: String) = bg { gsSet(key, type, value) }

        fun spSet(key: String, type: String, value: String) {
            bg {
                NativeApp.setSetting("EmuCore/Speedhacks", key, type, value)
                NativeApp.commitSettings()
            }
        }

        fun spu2Set(key: String, type: String, value: String) {
            bg {
                NativeApp.setSetting("SPU2/Output", key, type, value)
                NativeApp.commitSettings()
            }
        }

        fun ensureHddImage() = ensureHddImage(activity)

        fun writeNetworkSettings() = writeDev9Settings(activity)

        MainActivityRuntime.runWhenNativeReady {
            kotlin.concurrent.thread(name = "ps2-dnas-bypass") {
                Ps2DnasBypass.applyWhenReady(activity)
            }
        }

        bg {
            NativeApp.setAudioVolume(prefs.getInt("wn.ps2.volume", 100))
            NativeApp.setAudioMuted(prefs.getBoolean("wn.ps2.muted", false))
            NativeApp.setAudioSwapChannels(prefs.getBoolean("wn.ps2.swap", false))
            val customDriver = (prefs.getString("wn.ps2.driver", "") ?: "").trim()
                .let { it.isNotEmpty() && !it.equals("system", ignoreCase = true) }
            when {
                customDriver -> NativeApp.renderVulkan()
                prefs.getString("wn.ps2.renderer", "vulkan") == "opengl" -> NativeApp.renderOpenGL()
                prefs.getString("wn.ps2.renderer", "vulkan") == "software" -> NativeApp.renderSoftware()
                else -> NativeApp.renderVulkan()
            }
            NativeApp.renderUpscalemultiplier(prefs.getFloat("wn.ps2.upscale", 1f))
            RetroHudSupport.suppressNativePs2Osd()
            val touchOn = prefs.getBoolean("wn.ps2.touchcontrols", RetroDefaults.touchControls(activity, RetroSystems.PS2.id))
            RetroPs2OsdPlacement.apply(touchOn, false)
            applyDisplayAspect(activity, live = true)
            NativeApp.setFrameSkip(prefs.getInt("wn.ps2.frameskip", 0).coerceIn(0, 3))
            NativeApp.speedhackEecyclerate(prefs.getInt("wn.ps2.eeRate", 0).coerceIn(-3, 3))
            NativeApp.speedhackEecycleskip(prefs.getInt("wn.ps2.eeSkip", 0).coerceIn(0, 3))
            NativeApp.setInstantVU1(prefs.getBoolean("wn.ps2.instantVu1", true))
            NativeApp.renderTvShader(prefs.getInt("wn.ps2.tvshader", 0).coerceIn(0, 7))
            NativeApp.setSetting("EmuCore/GS", "linear_present_mode", "int", prefs.getInt("wn.ps2.displayfilter", 1).coerceIn(0, 2).toString())
            NativeApp.setSetting("EmuCore/GS", "filter", "int", prefs.getInt("wn.ps2.filter", 2).coerceIn(0, 3).toString())
            NativeApp.setSetting("EmuCore/GS", "accurate_blending_unit", "int", prefs.getInt("wn.ps2.blend", 1).coerceIn(0, 5).toString())
            NativeApp.setSetting("EmuCore/GS", "hw_mipmap", "bool", prefs.getBoolean("wn.ps2.mipmap", true).toString())
            NativeApp.setSetting("EmuCore/Speedhacks", "vuThread", "bool", prefs.getBoolean("wn.ps2.mtvu", true).toString())
            NativeApp.setSetting("EmuCore/Speedhacks", "fastCDVD", "bool", prefs.getBoolean("wn.ps2.fastCdvd", false).toString())
            NativeApp.setSetting("EmuCore/GS", "deinterlace_mode", "int", prefs.getInt("wn.ps2.deinterlace", 0).coerceIn(0, 9).toString())
            NativeApp.setSetting("EmuCore/GS", "FMVAspectRatioSwitch", "string", fmvAspectName(prefs.getInt("wn.ps2.fmvaspect", 0)))
            NativeApp.setSetting("EmuCore/GS", "pcrtc_antiblur", "bool", prefs.getBoolean("wn.ps2.antiblur", true).toString())
            NativeApp.setSetting("EmuCore/Speedhacks", "vuFlagHack", "bool", prefs.getBoolean("wn.ps2.vuFlagHack", true).toString())
            NativeApp.setSetting("EmuCore/Speedhacks", "IntcStat", "bool", prefs.getBoolean("wn.ps2.intc", true).toString())
            NativeApp.setSetting("EmuCore/Speedhacks", "WaitLoop", "bool", prefs.getBoolean("wn.ps2.waitloop", true).toString())
            NativeApp.setSetting("SPU2/Output", "SyncMode", "string", if (prefs.getBoolean("wn.ps2.timestretch", true)) "TimeStretch" else "Disabled")
            NativeApp.setSetting("SPU2/Output", "BufferMS", "int", prefs.getInt("wn.ps2.audiobuffer", 50).coerceIn(10, 200).toString())
            NativeApp.setSetting("SPU2/Output", "OutputLatencyMS", "int", prefs.getInt("wn.ps2.audiolatency", 20).coerceIn(5, 200).toString())
            prefs.getString("wn.ps2.mc.slot1", null)?.takeIf { it.isNotBlank() }?.let { name ->
                NativeApp.setSetting("MemoryCards", "Slot1_Filename", "string", name)
                NativeApp.setSetting("MemoryCards", "Slot1_Enable", "bool", "true")
            }
            prefs.getString("wn.ps2.mc.slot2", null)?.takeIf { it.isNotBlank() }?.let { name ->
                NativeApp.setSetting("MemoryCards", "Slot2_Filename", "string", name)
                NativeApp.setSetting("MemoryCards", "Slot2_Enable", "bool", "true")
            }
            ensureHddImage()
            writeNetworkSettings()
            runCatching { NativeApp.applyGSSettingsLive() }
            applyDisplayAspect(activity, live = true)
        }

        fun openWinNativeScreen(screen: String) {
            menu.close()
            ps2Screen.value = screen
        }

        fun mainEntries(): List<RetroMenuEntry> =
            buildList {
                add(
                    RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_save_state), RetroDrawerIcons.Save) {
                        savesLoadMode = false
                        menu.showPane(RetroPane.SAVES)
                    },
                )
                add(
                    RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_load_save_state), RetroDrawerIcons.Load) {
                        savesLoadMode = true
                        menu.showPane(RetroPane.SAVES)
                    },
                )
                add(RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_achievements), RetroDrawerIcons.Achievements) { openWinNativeScreen("achievements") })
                add(RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_cheats), RetroDrawerIcons.Cheats) { openWinNativeScreen("cheats") })
                add(RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_memory_cards), RetroDrawerIcons.Save) { menu.showPane(RetroPane.MEMCARDS) })
                add(
                    RetroMenuEntry.Toggle(activity.getString(R.string.retro_ps2_fast_forward), checked = MainActivityRuntime.fastForwardToggleActive) {
                        (activity as? MainActivityRuntime)?.toggleFastForward()
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_reset), RetroDrawerIcons.Reset) {
                        menu.close()
                        MainActivityRuntime.restart()
                    },
                )
                add(
                    RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_swap_disc), RetroDrawerIcons.Disc) {
                        menu.close()
                        MainActivityRuntime.promptSwapDisc()
                    },
                )
            }

        fun saveSlotEntries(): List<RetroMenuEntry> =
            (1..8).map { ui ->
                val slot = ui - 1
                val filled = runCatching {
                    NativeApp.getGamePathSlot(slot)?.takeIf { it.isNotBlank() }?.let { java.io.File(it).exists() }
                }.getOrNull() == true
                RetroMenuEntry.SaveSlot(
                    slot = ui,
                    title = activity.getString(R.string.retro_ps2_slot, ui),
                    subtitle =
                        when {
                            savesLoadMode && filled -> activity.getString(R.string.retro_ps2_slot_tap_to_load)
                            savesLoadMode -> activity.getString(R.string.retro_ps2_slot_empty)
                            filled -> activity.getString(R.string.retro_ps2_slot_tap_to_overwrite)
                            else -> activity.getString(R.string.retro_ps2_slot_empty_tap_to_save)
                        },
                    filled = filled,
                    onClick = {
                        if (savesLoadMode) {
                            if (!filled) {
                                Toast.makeText(activity, activity.getString(R.string.retro_ps2_slot_is_empty, ui), Toast.LENGTH_SHORT).show()
                            } else {
                                menu.close()
                                Thread {
                                    val ok = runCatching { NativeApp.loadStateFromSlot(slot) }.getOrDefault(false)
                                    activity.runOnUiThread {
                                        Toast.makeText(activity, if (ok) activity.getString(R.string.retro_ps2_loaded_slot, ui) else activity.getString(R.string.retro_ps2_could_not_load_slot, ui), Toast.LENGTH_SHORT).show()
                                        if (ok) { wnPaused = false; MainActivityRuntime.resume() }
                                    }
                                }.start()
                            }
                        } else {
                            Thread {
                                val ok = runCatching { NativeApp.saveStateToSlot(slot) }.getOrDefault(false)
                                activity.runOnUiThread {
                                    Toast.makeText(activity, if (ok) activity.getString(R.string.retro_ps2_saved_to_slot, ui) else activity.getString(R.string.retro_ps2_save_failed), Toast.LENGTH_SHORT).show()
                                    menu.rebuild()
                                }
                            }.start()
                        }
                    },
                    onRename = {},
                )
            }

        var launchMemcardImport: (() -> Unit)? = null

        fun applyMemSlot(slot: Int, name: String?) {
            prefs.edit().apply {
                if (name == null) remove("wn.ps2.mc.slot$slot") else putString("wn.ps2.mc.slot$slot", name)
            }.apply()
            bg {
                NativeApp.setSetting("MemoryCards", "Slot${slot}_Enable", "bool", "false")
                if (name != null) {
                    NativeApp.setSetting("MemoryCards", "Slot${slot}_Filename", "string", name)
                    NativeApp.setSetting("MemoryCards", "Slot${slot}_Enable", "bool", "true")
                }
                NativeApp.commitSettings()
            }
        }

        fun memcardEntries(): List<RetroMenuEntry> =
            buildList {
                val cards = runCatching { listMemcards(activity) }.getOrDefault(emptyList())
                val slot1 = prefs.getString("wn.ps2.mc.slot1", "").orEmpty()
                val slot2 = prefs.getString("wn.ps2.mc.slot2", "").orEmpty()
                cards.forEach { card ->
                    val assigned = when (card.name) { slot1 -> 1; slot2 -> 2; else -> 0 }
                    add(
                        RetroMenuEntry.SaveSlot(
                            slot = assigned,
                            title = card.name.removeSuffix(".ps2"),
                            subtitle = humanSize(card.length()) +
                                when (assigned) { 1 -> activity.getString(R.string.retro_ps2_slot_1_suffix); 2 -> activity.getString(R.string.retro_ps2_slot_2_suffix); else -> activity.getString(R.string.retro_ps2_tap_to_use_suffix) },
                            filled = assigned != 0,
                            onClick = {
                                when (assigned) {
                                    0 -> applyMemSlot(1, card.name)
                                    1 -> { applyMemSlot(1, null); applyMemSlot(2, card.name) }
                                    else -> applyMemSlot(2, null)
                                }
                                menu.rebuild()
                            },
                            onRename = {},
                        ),
                    )
                }
                add(
                    RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_new_memory_card), RetroDrawerIcons.Save) {
                        bg {
                            val existing = runCatching { listMemcards(activity).map { it.name }.toHashSet() }.getOrDefault(hashSetOf())
                            var n = 1
                            var name = "Mcd%03d.ps2".format(n)
                            while (name in existing) { n++; name = "Mcd%03d.ps2".format(n) }
                            runCatching { NativeApp.createMemoryCard(name, 1, 1) }
                            activity.runOnUiThread { menu.rebuild() }
                        }
                    },
                )
                add(RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_import_card), RetroDrawerIcons.Load) { launchMemcardImport?.invoke() })
            }

        fun controlsEntries(): List<RetroMenuEntry> =
            buildList {
                add(
                    RetroMenuEntry.Toggle(activity.getString(R.string.retro_ps2_onscreen_controls), checked = touchVisible.value) { value ->
                        touchVisible.value = value
                        if (controllerConnected.value) manualTouchOverride.value = true
                        ps2Prefs(activity).edit().putBoolean("wn.ps2.touchcontrols", value).apply()
                        persistExtra(RetroShortcuts.KEY_TOUCH_CONTROLS, if (value) "1" else "0")
                        val showPad = value && (!controllerConnected.value || manualTouchOverride.value)
                        RetroAchievementOverlayState.syncPlacement(showPad, controllerConnected.value)
                        bg {
                            RetroPs2OsdPlacement.apply(showPad, controllerConnected.value)
                        }
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Toggle(
                        activity.getString(R.string.retro_lr_shell_background),
                        subtitle = activity.getString(R.string.retro_lr_shell_background_subtitle),
                        checked = ps2Prefs(activity).getBoolean(PREF_SHELL_BG, true),
                    ) { value ->
                        ps2Prefs(activity).edit().putBoolean(PREF_SHELL_BG, value).apply()
                        pad?.shellBackground = value
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Toggle(
                        activity.getString(R.string.retro_ps2_adaptive_sticks),
                        subtitle = activity.getString(R.string.retro_ps2_adaptive_sticks_subtitle),
                        checked = ps2Prefs(activity).getBoolean("wn.ps2.adaptivesticks", false),
                    ) { value ->
                        ps2Prefs(activity).edit().putBoolean("wn.ps2.adaptivesticks", value).apply()
                        persistExtra(RetroShortcuts.KEY_ADAPTIVE_STICKS, if (value) "1" else "0")
                        pad?.adaptiveSticks = value
                        pad?.invalidate()
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Toggle(
                        activity.getString(R.string.retro_ps2_show_l3r3),
                        subtitle = activity.getString(R.string.retro_ps2_show_l3r3_subtitle),
                        checked = ps2Prefs(activity).getBoolean("wn.ps2.showl3r3", true),
                    ) { value ->
                        ps2Prefs(activity).edit().putBoolean("wn.ps2.showl3r3", value).apply()
                        pad?.showL3R3 = value
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_edit_layout), RetroDrawerIcons.EditLayout) {
                        menu.close()
                        touchVisible.value = true
                        pad?.enterEdit()
                    },
                )
                add(
                    RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_reset_layout), RetroDrawerIcons.Reset) {
                        pad?.resetLayout()
                        Toast.makeText(activity, activity.getString(R.string.retro_ps2_layout_reset), Toast.LENGTH_SHORT).show()
                    },
                )
                val invPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
                fun invToggle(label: String, key: String) =
                    RetroMenuEntry.Toggle(label, checked = invPrefs.getBoolean(key, false)) { value ->
                        invPrefs.edit().putBoolean(key, value).apply()
                        pad?.loadStickInversion()
                        menu.rebuild()
                    }
                add(invToggle(activity.getString(R.string.retro_ps2_left_stick_invert_x), "retro_inv_lx_ps2"))
                add(invToggle(activity.getString(R.string.retro_ps2_left_stick_invert_y), "retro_inv_ly_ps2"))
                add(invToggle(activity.getString(R.string.retro_ps2_right_stick_invert_x), "retro_inv_rx_ps2"))
                add(invToggle(activity.getString(R.string.retro_ps2_right_stick_invert_y), "retro_inv_ry_ps2"))
                add(
                    RetroMenuEntry.ColorPick(activity.getString(R.string.retro_ps2_button_color), customColors.button) { value ->
                        customColors = customColors.copy(button = value)
                        persistColors()
                    },
                )
                add(
                    RetroMenuEntry.ColorPick(activity.getString(R.string.retro_ps2_letter_color), customColors.text) { value ->
                        customColors = customColors.copy(text = value)
                        persistColors()
                    },
                )
                add(
                    RetroMenuEntry.ColorPick(activity.getString(R.string.retro_ps2_shadow_color), customColors.shadow) { value ->
                        customColors = customColors.copy(shadow = value)
                        persistColors()
                    },
                )
                add(
                    RetroMenuEntry.ColorPick(activity.getString(R.string.retro_ps2_background_color), customColors.body) { value ->
                        customColors = customColors.copy(body = value)
                        persistColors()
                    },
                )
                add(
                    RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_reset_colors), RetroDrawerIcons.Reset) {
                        customColors = RetroCustomColors()
                        persistColors()
                    },
                )
            }

        fun displayEntries(): List<RetroMenuEntry> =
            buildList {
                val scales = listOf(1f, 1.5f, 2f, 3f, 4f)
                val scaleLabels = listOf(
                    activity.getString(R.string.retro_ps2_scale_1x_native),
                    activity.getString(R.string.retro_ps2_scale_1_5x),
                    activity.getString(R.string.retro_ps2_scale_2x),
                    activity.getString(R.string.retro_ps2_scale_3x),
                    activity.getString(R.string.retro_ps2_scale_4x),
                )
                val scaleIdx = scales.indexOfFirst { kotlin.math.abs(it - prefs.getFloat("wn.ps2.upscale", 1f)) < 0.01f }.coerceAtLeast(0)
                add(
                    RetroMenuEntry.Choice(activity.getString(R.string.retro_ps2_resolution_scale), scaleLabels, scaleIdx) { next ->
                        prefs.edit().putFloat("wn.ps2.upscale", scales[next]).apply()
                        bg { NativeApp.renderUpscalemultiplier(scales[next]) }
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Choice(
                        activity.getString(R.string.retro_ps2_aspect_ratio),
                        listOf(
                            activity.getString(R.string.retro_ps2_aspect_stretch),
                            activity.getString(R.string.retro_ps2_aspect_auto_standard),
                            activity.getString(R.string.retro_ps2_aspect_4_3),
                            activity.getString(R.string.retro_ps2_aspect_16_9),
                        ),
                        prefs.getInt("wn.ps2.aspect", 1).coerceIn(0, 3),
                    ) { next ->
                        prefs.edit().putInt("wn.ps2.aspect", next).apply()
                        bg { NativeApp.setAspectRatio(next) }
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Choice(
                        activity.getString(R.string.retro_ps2_fmv_aspect_ratio),
                        listOf(
                            activity.getString(R.string.retro_ps2_shader_off),
                            activity.getString(R.string.retro_ps2_aspect_auto_standard),
                            activity.getString(R.string.retro_ps2_aspect_4_3),
                            activity.getString(R.string.retro_ps2_aspect_16_9),
                        ),
                        prefs.getInt("wn.ps2.fmvaspect", 0).coerceIn(0, 3),
                    ) { next ->
                        prefs.edit().putInt("wn.ps2.fmvaspect", next).apply()
                        gsSetAsync("FMVAspectRatioSwitch", "string", fmvAspectName(next))
                        menu.rebuild()
                    },
                )
                val deintLabels = listOf(
                    activity.getString(R.string.retro_ps2_deint_auto),
                    activity.getString(R.string.retro_ps2_deint_off),
                    activity.getString(R.string.retro_ps2_deint_weave_tff),
                    activity.getString(R.string.retro_ps2_deint_weave_bff),
                    activity.getString(R.string.retro_ps2_deint_bob_tff),
                    activity.getString(R.string.retro_ps2_deint_bob_bff),
                    activity.getString(R.string.retro_ps2_deint_blend_tff),
                    activity.getString(R.string.retro_ps2_deint_blend_bff),
                    activity.getString(R.string.retro_ps2_deint_adaptive_tff),
                    activity.getString(R.string.retro_ps2_deint_adaptive_bff),
                )
                add(
                    RetroMenuEntry.Choice(activity.getString(R.string.retro_ps2_deinterlace_mode), deintLabels, prefs.getInt("wn.ps2.deinterlace", 0).coerceIn(0, 9)) { next ->
                        prefs.edit().putInt("wn.ps2.deinterlace", next).apply()
                        gsSetAsync("deinterlace_mode", "int", next.toString())
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Choice(
                        activity.getString(R.string.retro_ps2_display_filter),
                        listOf(
                            activity.getString(R.string.retro_ps2_filter_nearest),
                            activity.getString(R.string.retro_ps2_filter_bilinear_smooth),
                            activity.getString(R.string.retro_ps2_filter_bilinear_sharp),
                        ),
                        prefs.getInt("wn.ps2.displayfilter", 1).coerceIn(0, 2),
                    ) { next ->
                        prefs.edit().putInt("wn.ps2.displayfilter", next).apply()
                        gsSetAsync("linear_present_mode", "int", next.toString())
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Choice(
                        activity.getString(R.string.retro_ps2_texture_filter),
                        listOf(
                            activity.getString(R.string.retro_ps2_filter_nearest),
                            activity.getString(R.string.retro_ps2_filter_bilinear_forced),
                            activity.getString(R.string.retro_ps2_filter_bilinear_ps2),
                            activity.getString(R.string.retro_ps2_filter_bilinear_sprites),
                        ),
                        prefs.getInt("wn.ps2.filter", 2).coerceIn(0, 3),
                    ) { next ->
                        prefs.edit().putInt("wn.ps2.filter", next).apply()
                        gsSetAsync("filter", "int", next.toString())
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Toggle(activity.getString(R.string.retro_ps2_mipmapping), checked = prefs.getBoolean("wn.ps2.mipmap", true)) { value ->
                        prefs.edit().putBoolean("wn.ps2.mipmap", value).apply()
                        gsSetAsync("hw_mipmap", "bool", value.toString())
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Choice(
                        activity.getString(R.string.retro_ps2_blending_accuracy),
                        listOf(
                            activity.getString(R.string.retro_ps2_blend_minimum),
                            activity.getString(R.string.retro_ps2_blend_basic),
                            activity.getString(R.string.retro_ps2_blend_medium),
                            activity.getString(R.string.retro_ps2_blend_high),
                            activity.getString(R.string.retro_ps2_blend_full),
                            activity.getString(R.string.retro_ps2_blend_maximum),
                        ),
                        prefs.getInt("wn.ps2.blend", 1).coerceIn(0, 5),
                    ) { next ->
                        prefs.edit().putInt("wn.ps2.blend", next).apply()
                        gsSetAsync("accurate_blending_unit", "int", next.toString())
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Toggle(activity.getString(R.string.retro_ps2_anti_blur), checked = prefs.getBoolean("wn.ps2.antiblur", true)) { value ->
                        prefs.edit().putBoolean("wn.ps2.antiblur", value).apply()
                        gsSetAsync("pcrtc_antiblur", "bool", value.toString())
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Choice(
                        activity.getString(R.string.retro_ps2_crt_tv_shader),
                        listOf(
                            activity.getString(R.string.retro_ps2_shader_off),
                            activity.getString(R.string.retro_ps2_shader_scanline),
                            activity.getString(R.string.retro_ps2_shader_diagonal),
                            activity.getString(R.string.retro_ps2_shader_triangular),
                            activity.getString(R.string.retro_ps2_shader_wave),
                            activity.getString(R.string.retro_ps2_shader_lottes),
                            activity.getString(R.string.retro_ps2_shader_4xrgss),
                            activity.getString(R.string.retro_ps2_shader_nxagss),
                        ),
                        prefs.getInt("wn.ps2.tvshader", 0).coerceIn(0, 7),
                    ) { next ->
                        prefs.edit().putInt("wn.ps2.tvshader", next).apply()
                        bg { NativeApp.renderTvShader(next) }
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Choice(
                        activity.getString(R.string.retro_ps2_frame_skip),
                        listOf(
                            activity.getString(R.string.retro_ps2_frameskip_off),
                            activity.getString(R.string.retro_ps2_frameskip_1),
                            activity.getString(R.string.retro_ps2_frameskip_2),
                            activity.getString(R.string.retro_ps2_frameskip_3),
                        ),
                        prefs.getInt("wn.ps2.frameskip", 0).coerceIn(0, 3),
                    ) { next ->
                        prefs.edit().putInt("wn.ps2.frameskip", next).apply()
                        bg { NativeApp.setFrameSkip(next) }
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Toggle(activity.getString(R.string.retro_ps2_widescreen_patches), checked = prefs.getBoolean("wn.ps2.widescreen", false)) { value ->
                        prefs.edit().putBoolean("wn.ps2.widescreen", value).apply()
                        bg {
                            NativeApp.setSetting("EmuCore", "EnableWideScreenPatches", "bool", value.toString())
                            NativeApp.commitSettings()
                            NativeApp.reloadPatches()
                        }
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Toggle(activity.getString(R.string.retro_ps2_no_interlace_patches), checked = prefs.getBoolean("wn.ps2.nointerlace", false)) { value ->
                        prefs.edit().putBoolean("wn.ps2.nointerlace", value).apply()
                        bg {
                            NativeApp.setSetting("EmuCore", "EnableNoInterlacingPatches", "bool", value.toString())
                            NativeApp.commitSettings()
                            NativeApp.reloadPatches()
                        }
                        menu.rebuild()
                    },
                )
            }

        fun soundEntries(): List<RetroMenuEntry> =
            buildList {
                val muted = prefs.getBoolean("wn.ps2.muted", false)
                val volume = prefs.getInt("wn.ps2.volume", 100)
                val swap = prefs.getBoolean("wn.ps2.swap", false)
                add(
                    RetroMenuEntry.Slider(
                        label = activity.getString(R.string.retro_ps2_volume),
                        valueText = activity.getString(R.string.retro_ps2_volume_percent, volume),
                        value = volume.toFloat(),
                        min = 0f,
                        max = 200f,
                        step = 5f,
                    ) { value ->
                        val v = value.toInt()
                        prefs.edit().putInt("wn.ps2.volume", v).apply()
                        bg { NativeApp.setAudioVolume(v) }
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Toggle(activity.getString(R.string.retro_ps2_mute), checked = muted) { value ->
                        prefs.edit().putBoolean("wn.ps2.muted", value).apply()
                        bg { NativeApp.setAudioMuted(value) }
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Toggle(activity.getString(R.string.retro_ps2_time_stretch), checked = prefs.getBoolean("wn.ps2.timestretch", true)) { value ->
                        prefs.edit().putBoolean("wn.ps2.timestretch", value).apply()
                        spu2Set("SyncMode", "string", if (value) "TimeStretch" else "Disabled")
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Toggle(activity.getString(R.string.retro_ps2_swap_stereo_channels), checked = swap) { value ->
                        prefs.edit().putBoolean("wn.ps2.swap", value).apply()
                        bg { NativeApp.setAudioSwapChannels(value) }
                        menu.rebuild()
                    },
                )
            }

        fun performanceEntries(): List<RetroMenuEntry> =
            buildList {
                val rateValues = listOf(-3, -2, -1, 0, 1, 2, 3)
                val rateLabels = listOf(
                    activity.getString(R.string.retro_ps2_rate_50),
                    activity.getString(R.string.retro_ps2_rate_60),
                    activity.getString(R.string.retro_ps2_rate_75),
                    activity.getString(R.string.retro_ps2_rate_100_default),
                    activity.getString(R.string.retro_ps2_rate_130),
                    activity.getString(R.string.retro_ps2_rate_180),
                    activity.getString(R.string.retro_ps2_rate_300),
                )
                val curRate = prefs.getInt("wn.ps2.eeRate", 0).coerceIn(-3, 3)
                add(
                    RetroMenuEntry.Choice(activity.getString(R.string.retro_ps2_ee_cycle_rate), rateLabels, rateValues.indexOf(curRate).coerceAtLeast(0)) { next ->
                        prefs.edit().putInt("wn.ps2.eeRate", rateValues[next]).apply()
                        bg { NativeApp.speedhackEecyclerate(rateValues[next]) }
                        spSet("EECycleRate", "int", rateValues[next].toString())
                        menu.rebuild()
                    },
                )
                val skipLabels = listOf(
                    activity.getString(R.string.retro_ps2_skip_off),
                    activity.getString(R.string.retro_ps2_skip_1),
                    activity.getString(R.string.retro_ps2_skip_2),
                    activity.getString(R.string.retro_ps2_skip_3),
                )
                add(
                    RetroMenuEntry.Choice(activity.getString(R.string.retro_ps2_ee_cycle_skip), skipLabels, prefs.getInt("wn.ps2.eeSkip", 0).coerceIn(0, 3)) { next ->
                        prefs.edit().putInt("wn.ps2.eeSkip", next).apply()
                        bg { NativeApp.speedhackEecycleskip(next) }
                        spSet("EECycleSkip", "int", next.toString())
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Toggle(activity.getString(R.string.retro_ps2_instant_vu1), checked = prefs.getBoolean("wn.ps2.instantVu1", true)) { value ->
                        prefs.edit().putBoolean("wn.ps2.instantVu1", value).apply()
                        bg { NativeApp.setInstantVU1(value) }
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Toggle(activity.getString(R.string.retro_ps2_vu_flag_hack), checked = prefs.getBoolean("wn.ps2.vuFlagHack", true)) { value ->
                        prefs.edit().putBoolean("wn.ps2.vuFlagHack", value).apply()
                        spSet("vuFlagHack", "bool", value.toString())
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Toggle(activity.getString(R.string.retro_ps2_intc_spin), checked = prefs.getBoolean("wn.ps2.intc", true)) { value ->
                        prefs.edit().putBoolean("wn.ps2.intc", value).apply()
                        spSet("IntcStat", "bool", value.toString())
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Toggle(activity.getString(R.string.retro_ps2_wait_loop), checked = prefs.getBoolean("wn.ps2.waitloop", true)) { value ->
                        prefs.edit().putBoolean("wn.ps2.waitloop", value).apply()
                        spSet("WaitLoop", "bool", value.toString())
                        menu.rebuild()
                    },
                )
                add(
                    RetroMenuEntry.Toggle(activity.getString(R.string.retro_ps2_fast_cdvd), checked = prefs.getBoolean("wn.ps2.fastCdvd", false)) { value ->
                        prefs.edit().putBoolean("wn.ps2.fastCdvd", value).apply()
                        spSet("fastCDVD", "bool", value.toString())
                        menu.rebuild()
                    },
                )
            }

        fun hudEntries(): List<RetroMenuEntry> =
            RetroHudSupport.buildHudEntries(
                context = activity,
                hudVisible = hudVisible,
                style = hudStyle,
                elements = hudElements,
                onMaster = { setHudVisible(it) },
                onStyle = { next ->
                    hudStyle = next
                    RetroHudSupport.savePs2HudStyle(activity, next)
                    applyHudToRating()
                },
                onElements = { next ->
                    hudElements = next
                    RetroHudSupport.savePs2Elements(activity, next)
                    applyHudToRating()
                },
                onRebuild = { menu.rebuild() },
            )

        menu.tabs = RetroDrawerTabs.build(activity, includePerformance = true)
        menu.paneContentProvider = { pane ->
            if (pane == RetroPane.FRAMEGEN) {
                { RetroFrameGenPane.Content(activity, loadShortcut(activity), RetroSystems.PS2.id) }
            } else {
                null
            }
        }
        menu.entriesProvider = { pane ->
            when (pane) {
                null -> mainEntries()
                RetroPane.DISPLAY -> displayEntries()
                RetroPane.FRAMEGEN -> emptyList()
                RetroPane.PERFORMANCE -> performanceEntries()
                RetroPane.SOUND -> soundEntries()
                RetroPane.SAVES -> saveSlotEntries()
                RetroPane.MEMCARDS -> memcardEntries()
                RetroPane.CONTROLS -> controlsEntries()
                RetroPane.HUD -> hudEntries()
                RetroPane.NETWORK -> emptyList()
                RetroPane.SYSTEM -> emptyList()
            }
        }
        menu.bottomProvider = {
            listOf(
                if (wnPaused) {
                    RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_resume), RetroDrawerIcons.Resume, active = true) {
                        wnPaused = false
                        MainActivityRuntime.resume()
                        menu.close()
                    }
                } else {
                    RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_pause), RetroDrawerIcons.Pause) {
                        wnPaused = true
                        menu.rebuild()
                    }
                },
                RetroMenuEntry.Action(activity.getString(R.string.retro_ps2_exit), RetroDrawerIcons.Exit, danger = true) {
                    menu.close()
                    runCatching { NativeApp.shutdown() }
                    activity.finish()
                },
            )
        }

        val listener =
            object : RetroInputView.Listener {
                override fun onButton(keyCode: Int, down: Boolean) {
                    if (!nativeUp()) return
                    NativeApp.setPadButton(mapFace(keyCode), if (down) FULL else 0, down)
                }

                override fun onDpad(x: Float, y: Float) {
                    if (!nativeUp()) return
                    NativeApp.setPadButton(KeyEvent.KEYCODE_DPAD_LEFT, if (x < -0.3f) FULL else 0, x < -0.3f)
                    NativeApp.setPadButton(KeyEvent.KEYCODE_DPAD_RIGHT, if (x > 0.3f) FULL else 0, x > 0.3f)
                    NativeApp.setPadButton(KeyEvent.KEYCODE_DPAD_UP, if (y < -0.3f) FULL else 0, y < -0.3f)
                    NativeApp.setPadButton(KeyEvent.KEYCODE_DPAD_DOWN, if (y > 0.3f) FULL else 0, y > 0.3f)
                }

                override fun onStick(x: Float, y: Float) {
                    if (!nativeUp()) return
                    emitAxis(111, 113, x)
                    emitAxis(112, 110, y)
                }

                override fun onRightStick(x: Float, y: Float) {
                    if (!nativeUp()) return
                    emitAxis(121, 123, x)
                    emitAxis(122, 120, y)
                }

                override fun onMenu() {
                    if (!nativeUp()) return
                    activity.runOnUiThread {
                        pad?.releaseAll()
                        menu.rebuild()
                        menu.open()
                    }
                }
            }

        WinNativeHost.openMenu = {
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (!nativeUp()) return@runOnUiThread
                if (menu.visible) {
                    if (!menuToggleGate.allow()) return@runOnUiThread
                    menu.close()
                } else {
                    pad?.releaseAll()
                    menu.rebuild()
                    menu.open()
                }
            }
        }
        WinNativeHost.isMenuOpen = { menu.visible && ps2Screen.value == null }
        WinNativeHost.menuKeyHandler = handler@{ event ->
            if (!menu.visible || ps2Screen.value != null) return@handler false
            if (event.keyCode == KeyEvent.KEYCODE_BACK) return@handler false
            menu.handleKey(event.keyCode, event.action)
        }
        WinNativeHost.menuAxisHandler = { x, y ->
            if (!menu.visible || ps2Screen.value != null) false
            else menu.handleAxis(x, y)
        }

        if (hudVisible) {
            activity.window.decorView.post {
                if (!activity.isFinishing && !activity.isDestroyed && hudVisible) showHud()
            }
        }

        bg {
            Ps2RaBridge.pushSharedLogin(activity)
            NativeApp.setAchievementsOption("notifications", false)
            NativeApp.setAchievementsOption("leaderboardNotifications", false)
            NativeApp.setSetting("Achievements", "Notifications", "bool", "false")
            NativeApp.setSetting("Achievements", "LeaderboardNotifications", "bool", "false")
            NativeApp.commitSettings()
        }

        val achievementPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var knownUnlocked = emptySet<Int>()
        var achievementsSeeded = false
        val achievementPoll =
            object : Runnable {
                override fun run() {
                    if (activity.isFinishing || activity.isDestroyed) return
                    if (!nativeUp()) {
                        achievementPollHandler.postDelayed(this, 1500L)
                        return
                    }
                    if (ps2Screen.value == null) {
                        runCatching {
                            val json = NativeApp.getAchievementsJSON() ?: return@runCatching
                            val items = com.armsx2.ui.achievements.parseAchievementItems(json)
                            val unlocked = items.filter { it.unlocked }.map { it.id }.toSet()
                            if (!achievementsSeeded) {
                                knownUnlocked = unlocked
                                achievementsSeeded = true
                            } else {
                                val newly = unlocked - knownUnlocked
                                if (newly.isNotEmpty()) {
                                    knownUnlocked = unlocked
                                    items.filter { it.id in newly }.forEach { item ->
                                        val showPad =
                                            touchVisible.value &&
                                                (pad?.editMode == true || (!controllerConnected.value || manualTouchOverride.value))
                                        RetroAchievementOverlayState.syncPlacement(showPad, controllerConnected.value)
                                        RetroAchievementOverlayState.show(item.title, item.points, item.description)
                                    }
                                }
                            }
                        }
                    }
                    achievementPollHandler.postDelayed(this, 1500L)
                }
            }
        achievementPollHandler.postDelayed(achievementPoll, 3000L)

        val overlayView =
            ComposeView(activity).apply {
                elevation = 2000f
                setContent {
                    WinNativeTheme {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.fillMaxSize(),
                        ) {
                        val memImport = androidx.activity.compose.rememberLauncherForActivityResult(
                            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                        ) { uri ->
                            if (uri != null) {
                                Thread {
                                    runCatching {
                                        val name = queryName(activity, uri) ?: "Imported.ps2"
                                        val fileName = if (name.endsWith(".ps2", true)) name else "$name.ps2"
                                        val target = uniqueMemcard(memcardDir(activity), fileName)
                                        activity.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
                                    }
                                    activity.runOnUiThread { menu.rebuild() }
                                }.start()
                            }
                        }
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            launchMemcardImport = { memImport.launch(arrayOf("*/*")) }
                        }
                        val menuVisible = menu.visible
                        androidx.compose.runtime.LaunchedEffect(menuVisible) {
                            if (!menuVisible && !wnPaused && ps2Screen.value == null) {
                                MainActivityRuntime.resume()
                            }
                        }
                        val screen by ps2Screen
                        BackHandler(enabled = screen == null) {
                            when {
                                pad?.editMode == true -> pad?.finishEdit()
                                menu.visible -> menu.handleKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)
                                else -> {
                                    pad?.releaseAll()
                                    menu.rebuild()
                                    menu.open()
                                }
                            }
                        }
                        if (screen != null) {
                            val dismiss = {
                                ps2Screen.value = null
                                wnPaused = false
                                MainActivityRuntime.resume()
                            }
                            BackHandler(enabled = true) { dismiss() }
                            when (screen) {
                                "cheats" -> Ps2CheatsScreen(activity, dismiss)
                                "achievements" ->
                                    Ps2AchievementsScreen(
                                        activity,
                                        romPath = launchRomPath,
                                        launchGameName = launchGameName,
                                        onBack = dismiss,
                                    )
                            }
                            return@WinNativeTheme
                        }
                        val covered = WindowImpl.frontendCovers
                        val showPad = touchVisible.value && (pad?.editMode == true || (!controllerConnected.value || manualTouchOverride.value))
                        if (!covered && showPad) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    RetroInputView(ctx, listener, RetroSystems.PS2).also { view ->
                                        view.loadStickInversion()
                                        view.adaptiveSticks = ps2Prefs(ctx).getBoolean("wn.ps2.adaptivesticks", false)
                                        view.showL3R3 = ps2Prefs(ctx).getBoolean("wn.ps2.showl3r3", true)
                                        view.shellBackground = ps2Prefs(ctx).getBoolean(PREF_SHELL_BG, true)
                                        view.hapticStrength =
                                            androidx.preference.PreferenceManager
                                                .getDefaultSharedPreferences(ctx)
                                                .getFloat("retro_haptic_strength", 0.4f)
                                        view.setCustomColors(customColors)
                                        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                                            val w = v.width.toFloat()
                                            val h = v.height.toFloat()
                                            if (w > 0f && h > 0f) {
                                                val aspect = 4f / 3f
                                                val gw = (h * aspect).coerceAtMost(w)
                                                val gh = gw / aspect
                                                val left = (w - gw) * 0.5f
                                                val top = (h - gh) * 0.5f
                                                view.setGameArea(android.graphics.RectF(left, top, left + gw, top + gh))
                                            }
                                        }
                                        pad = view
                                    }
                                },
                            )
                        }
                        if (!covered) {
                            val showPadForHud = touchVisible.value && (pad?.editMode == true || (!controllerConnected.value || manualTouchOverride.value))
                            RetroAchievementOverlayState.syncPlacement(showPadForHud, controllerConnected.value)
                            RetroAchievementOverlayBanner()
                            RetroDrawerMenu(menu)
                        }
                        }
                    }
                }
            }
        activity.addContentView(
            overlayView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val aspectPinHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var aspectPinTicks = 0
        val aspectPin =
            object : Runnable {
                override fun run() {
                    if (activity.isFinishing || activity.isDestroyed) return
                    applyDisplayAspect(activity, live = true)
                    aspectPinTicks++
                    if (aspectPinTicks < 20) {
                        aspectPinHandler.postDelayed(this, 100L)
                    }
                }
            }
        aspectPinHandler.post(aspectPin)

        val prevCallback = activity.window.callback
        if (prevCallback != null) {
            activity.window.callback =
                object : android.view.Window.Callback by prevCallback {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (ps2Screen.value == null && menu.visible &&
                            event.keyCode != KeyEvent.KEYCODE_BACK &&
                            menu.handleKey(event.keyCode, event.action)
                        ) {
                            return true
                        }
                        return prevCallback.dispatchKeyEvent(event)
                    }

                    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
                        if (ps2Screen.value == null && menu.visible &&
                            event.source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK
                        ) {
                            val x = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X).takeIf { kotlin.math.abs(it) > 0.5f }
                                ?: event.getAxisValue(android.view.MotionEvent.AXIS_X)
                            val y = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y).takeIf { kotlin.math.abs(it) > 0.5f }
                                ?: event.getAxisValue(android.view.MotionEvent.AXIS_Y)
                            activity.runOnUiThread { menu.handleAxis(x, y) }
                            return true
                        }
                        return prevCallback.dispatchGenericMotionEvent(event)
                    }
                }
        }
    }
}
