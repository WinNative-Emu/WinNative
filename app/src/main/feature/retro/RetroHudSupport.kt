package com.winlator.cmod.feature.retro

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.preference.PreferenceManager
import com.winlator.cmod.R
import com.winlator.cmod.runtime.display.ui.FrameRating
import java.util.HashMap

object RetroHudSupport {
    const val HUD_ELEVATION = 2500f

    val ELEMENT_LABEL_RES =
        intArrayOf(
            R.string.retro_lr_hud_fps,
            R.string.retro_lr_hud_console,
            R.string.retro_lr_hud_gpu,
            R.string.retro_lr_hud_cpu,
            R.string.retro_lr_hud_ram,
            R.string.retro_lr_hud_battery,
            R.string.retro_lr_hud_temp,
            R.string.retro_lr_hud_graph,
            R.string.retro_lr_hud_cpu_temp,
        )

    val ELEMENT_ORDER = intArrayOf(1, 2, 3, 8, 4, 5, 6, 0, 7)

    const val PS2_HUD_PREF = "wn.ps2.hud"
    private const val PS2_ELEMENTS_PREF = "wn.ps2.hud.elements"
    private const val PS2_ALPHA_PREF = "wn.ps2.hud.alpha"
    private const val PS2_BG_DECOUPLED_PREF = "wn.ps2.hud.bg_decoupled"
    private const val PS2_BG_ALPHA_PREF = "wn.ps2.hud.bg_alpha"
    private const val PS2_SCALE_PREF = "wn.ps2.hud.scale"

    fun consoleHudDefault(
        context: Context,
        systemId: String,
    ): Boolean =
        if (systemId == RetroSystems.PS2.id) resolvePs2HudEnabled(context) else RetroDefaults.hud(context, systemId)

    fun hudEnabled(
        context: Context,
        shortcut: com.winlator.cmod.runtime.container.Shortcut?,
        systemId: String,
    ): Boolean {
        val perGame = shortcut?.getExtra(RetroShortcuts.KEY_HUD).orEmpty()
        return if (perGame.isEmpty()) consoleHudDefault(context, systemId) else perGame == "1"
    }

    fun defaultElements(): BooleanArray = booleanArrayOf(true, true, true, true, true, true, true, true, false)

    fun ps2Prefs(context: Context) =
        context.getSharedPreferences("ARMSX2", Context.MODE_PRIVATE)

    fun resolvePs2HudEnabled(context: Context): Boolean {
        val prefs = ps2Prefs(context)
        if (prefs.contains(PS2_HUD_PREF)) {
            return prefs.getBoolean(PS2_HUD_PREF, false)
        }
        val legacy =
            listOf("fps", "speed", "res", "cpu", "gpu", "frametimes", "gsstats", "inputs", "hwinfo", "version")
                .any { prefs.getBoolean("wn.osd.$it", false) }
        if (legacy) {
            prefs.edit().putBoolean(PS2_HUD_PREF, true).apply()
            return true
        }
        return RetroDefaults.hud(context, RetroSystems.PS2.id)
    }

    fun setPs2HudEnabled(context: Context, enabled: Boolean) {
        ps2Prefs(context).edit().putBoolean(PS2_HUD_PREF, enabled).apply()
    }

    fun loadPs2Elements(context: Context): BooleanArray {
        val prefs = ps2Prefs(context)
        val raw = prefs.getString(PS2_ELEMENTS_PREF, null)
        if (!raw.isNullOrBlank()) {
            val parts = raw.split(',')
            val out = defaultElements()
            for (i in out.indices) {
                if (i < parts.size) out[i] = parts[i] == "1" || parts[i].equals("true", true)
            }
            return out
        }
        if (listOf("fps", "cpu", "gpu", "frametimes").any { prefs.contains("wn.osd.$it") }) {
            val out = defaultElements()
            out[0] = prefs.getBoolean("wn.osd.fps", true)
            out[2] = prefs.getBoolean("wn.osd.gpu", true)
            out[3] = prefs.getBoolean("wn.osd.cpu", true)
            out[7] = prefs.getBoolean("wn.osd.frametimes", true)
            savePs2Elements(context, out)
            return out
        }
        return defaultElements()
    }

    fun savePs2Elements(context: Context, elements: BooleanArray) {
        val raw = elements.joinToString(",") { if (it) "1" else "0" }
        ps2Prefs(context).edit().putString(PS2_ELEMENTS_PREF, raw).apply()
    }

    fun loadPs2HudStyle(context: Context): HudStyle {
        val prefs = ps2Prefs(context)
        val pm = PreferenceManager.getDefaultSharedPreferences(context)
        val alpha = prefs.getFloat(PS2_ALPHA_PREF, pm.getFloat(FrameRating.PREF_HUD_ALPHA, 1f))
        val scale = prefs.getFloat(PS2_SCALE_PREF, pm.getFloat(FrameRating.PREF_HUD_SCALE, 1f))
        val bgDecoupled = prefs.getBoolean(PS2_BG_DECOUPLED_PREF, false)
        val bgAlpha =
            prefs.getFloat(
                PS2_BG_ALPHA_PREF,
                (alpha * FrameRating.BACKDROP_BASE_ALPHA).coerceIn(0.1f, 1f),
            )
        val frametimeNumeric = pm.getBoolean(FrameRating.PREF_HUD_FRAMETIME_NUMERIC, false)
        val dualBattery = pm.getBoolean(FrameRating.PREF_HUD_DUAL_SERIES_BATTERY, false)
        return HudStyle(alpha, bgDecoupled, bgAlpha, scale, frametimeNumeric, dualBattery)
    }

    fun savePs2HudStyle(context: Context, style: HudStyle) {
        ps2Prefs(context)
            .edit()
            .putFloat(PS2_ALPHA_PREF, style.alpha)
            .putBoolean(PS2_BG_DECOUPLED_PREF, style.bgDecoupled)
            .putFloat(PS2_BG_ALPHA_PREF, style.bgAlpha)
            .putFloat(PS2_SCALE_PREF, style.scale)
            .apply()
        PreferenceManager
            .getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(FrameRating.PREF_HUD_FRAMETIME_NUMERIC, style.frametimeNumeric)
            .putBoolean(FrameRating.PREF_HUD_DUAL_SERIES_BATTERY, style.dualBattery)
            .apply()
    }

    fun applyStyle(
        rating: FrameRating,
        style: HudStyle,
        elements: BooleanArray,
    ) {
        rating.setHudAlpha(style.alpha)
        rating.setBackgroundAlphaDecoupled(style.bgDecoupled)
        rating.setHudBackgroundAlpha(style.bgAlpha)
        rating.setHudScale(style.scale)
        rating.setFrametimeNumericMode(style.frametimeNumeric)
        rating.setDualSeriesBattery(style.dualBattery)
        elements.forEachIndexed { index, enabled -> rating.toggleElement(index, enabled) }
    }

    fun loadGlobalHudElements(context: Context): BooleanArray {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val csv = prefs.getString("retro_hud_elements", null) ?: return defaultElements()
        val parts = csv.split(',')
        val defaults = defaultElements()
        return BooleanArray(defaults.size) { i -> parts.getOrNull(i)?.toBoolean() ?: defaults[i] }
    }

    fun saveGlobalHudElements(context: Context, elements: BooleanArray) {
        androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
            .edit()
            .putString("retro_hud_elements", elements.joinToString(","))
            .apply()
    }

    fun loadGlobalHudStyle(context: Context): HudStyle {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        return HudStyle(
            alpha = prefs.getFloat("retro_hud_alpha", 1f),
            bgDecoupled = prefs.getBoolean("retro_hud_bg_decoupled", false),
            bgAlpha = prefs.getFloat("retro_hud_bg_alpha", FrameRating.BACKDROP_BASE_ALPHA),
            scale = prefs.getFloat("retro_hud_scale", 1f),
            frametimeNumeric = prefs.getBoolean(FrameRating.PREF_HUD_FRAMETIME_NUMERIC, false),
            dualBattery = prefs.getBoolean(FrameRating.PREF_HUD_DUAL_SERIES_BATTERY, false),
        )
    }

    fun saveGlobalHudStyle(context: Context, style: HudStyle) {
        androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
            .edit()
            .putFloat("retro_hud_alpha", style.alpha)
            .putBoolean("retro_hud_bg_decoupled", style.bgDecoupled)
            .putFloat("retro_hud_bg_alpha", style.bgAlpha)
            .putFloat("retro_hud_scale", style.scale)
            .putBoolean(FrameRating.PREF_HUD_FRAMETIME_NUMERIC, style.frametimeNumeric)
            .putBoolean(FrameRating.PREF_HUD_DUAL_SERIES_BATTERY, style.dualBattery)
            .apply()
    }

    fun loadContainerHudSettings(
        context: Context,
        containerId: Int,
    ): Pair<HudStyle, BooleanArray>? {
        if (containerId <= 0) return null
        return runCatching {
            val json =
                com.winlator.cmod.runtime.container.ContainerManager(context)
                    .getContainerById(containerId)
                    ?.getExtra("hudSettings")
            if (json.isNullOrEmpty()) return null
            val obj = org.json.JSONObject(json)
            val transparency = obj.optDouble("transparency", 1.0).toFloat()
            val style =
                HudStyle(
                    alpha = transparency,
                    bgDecoupled = obj.optBoolean("backgroundAlphaDecoupled", false),
                    bgAlpha =
                        obj
                            .optDouble(
                                "backgroundTransparency",
                                (transparency * FrameRating.BACKDROP_BASE_ALPHA).toDouble(),
                            ).toFloat(),
                    scale = obj.optDouble("scale", 1.0).toFloat(),
                )
            val legacyCpuRam = obj.optBoolean("showCpuRam", true)
            val legacyBattTemp = obj.optBoolean("showBattTemp", true)
            val elements =
                booleanArrayOf(
                    obj.optBoolean("showFPS", true),
                    obj.optBoolean("showRenderer", true),
                    obj.optBoolean("showGPU", true),
                    obj.optBoolean("showCPU", legacyCpuRam),
                    obj.optBoolean("showRAM", legacyCpuRam),
                    obj.optBoolean("showBattery", legacyBattTemp),
                    obj.optBoolean("showTemp", legacyBattTemp),
                    obj.optBoolean("showGraph", true),
                    obj.optBoolean("showCpuTemp", false),
                )
            style to elements
        }.getOrNull()
    }

    fun saveContainerHudSettings(
        context: Context,
        containerId: Int,
        style: HudStyle,
        elements: BooleanArray,
    ) {
        if (containerId <= 0) return
        runCatching {
            val container =
                com.winlator.cmod.runtime.container.ContainerManager(context)
                    .getContainerById(containerId) ?: return
            val obj = org.json.JSONObject()
            obj.put("transparency", style.alpha.toDouble())
            obj.put("backgroundAlphaDecoupled", style.bgDecoupled)
            obj.put("backgroundTransparency", style.bgAlpha.toDouble())
            obj.put("scale", style.scale.toDouble())
            obj.put("showFPS", elements.getOrElse(0) { true })
            obj.put("showRenderer", elements.getOrElse(1) { true })
            obj.put("showGPU", elements.getOrElse(2) { true })
            obj.put("showCPU", elements.getOrElse(3) { true })
            obj.put("showRAM", elements.getOrElse(4) { true })
            obj.put("showBattery", elements.getOrElse(5) { true })
            obj.put("showTemp", elements.getOrElse(6) { true })
            obj.put("showGraph", elements.getOrElse(7) { true })
            obj.put("showCpuTemp", elements.getOrElse(8) { false })
            container.putExtra("hudSettings", obj.toString())
            container.saveData()
        }
    }

    fun libretroRendererLabel(): String = "OpenGL"

    fun createFrameRating(
        context: Context,
        rendererLabel: String,
    ): FrameRating {
        val rating = FrameRating(context, HashMap<String, String>())
        rating.setRenderer(rendererLabel)
        rating.setHudElevation(HUD_ELEVATION)
        rating.visibility = View.GONE
        bindFrameGeneration(rating)
        return rating
    }

    private var boundRating: java.lang.ref.WeakReference<FrameRating>? = null
    private var frameGenWatched = false

    fun bindFrameGeneration(rating: FrameRating) {
        boundRating = java.lang.ref.WeakReference(rating)
        if (!frameGenWatched) {
            frameGenWatched = true
            com.winlator.cmod.shared.framegen.FrameGen.setStateListener {
                Handler(Looper.getMainLooper()).post { rebindFrameGeneration() }
            }
        }
        applyFrameGeneration(rating)
    }

    private fun rebindFrameGeneration() {
        val rating = boundRating?.get() ?: return
        applyFrameGeneration(rating)
    }

    private fun applyFrameGeneration(rating: FrameRating) {
        val requested = com.winlator.cmod.shared.framegen.FrameGen.requested
        rating.setOutputFrameSource(if (requested) frameGenOutputSource else null)
        rating.setFrameGenerationActive(requested)
    }

    private val frameGenOutputSource =
        object : FrameRating.OutputFrameSource {
            override fun getPresentedFrameCount(): Long =
                com.winlator.cmod.shared.framegen.FrameGen.realFrames() +
                    com.winlator.cmod.shared.framegen.FrameGen.generatedFrames()

            override fun getGeneratedFrameCount(): Long =
                com.winlator.cmod.shared.framegen.FrameGen.generatedFrames()
        }

    fun attachFrameRating(
        parent: ViewGroup,
        rating: FrameRating,
        below: View? = null,
    ) {
        if (rating.parent != null) return
        val lp =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        val index = below?.let { parent.indexOfChild(it) } ?: -1
        if (index >= 0) parent.addView(rating, index, lp) else parent.addView(rating, lp)
    }

    fun buildHudEntries(
        context: Context,
        hudVisible: Boolean,
        style: HudStyle,
        elements: BooleanArray,
        onMaster: (Boolean) -> Unit,
        onStyle: (HudStyle) -> Unit,
        onElements: (BooleanArray) -> Unit,
        onRebuild: () -> Unit,
    ): List<RetroMenuEntry> {
        val entries = mutableListOf<RetroMenuEntry>()
        entries +=
            RetroMenuEntry.Toggle(context.getString(R.string.retro_lr_performance_hud), checked = hudVisible) { value ->
                onMaster(value)
                onRebuild()
            }
        if (!hudVisible) return entries
        entries +=
            RetroMenuEntry.Slider(
                label = context.getString(R.string.retro_lr_alpha),
                valueText = "${(style.alpha * 100).toInt()}%",
                value = style.alpha,
                min = 0.1f,
                max = 1f,
                step = 0.05f,
            ) { value ->
                val next =
                    if (!style.bgDecoupled) {
                        style.copy(
                            alpha = value,
                            bgAlpha = (value * FrameRating.BACKDROP_BASE_ALPHA).coerceIn(0.1f, 1f),
                        )
                    } else {
                        style.copy(alpha = value)
                    }
                onStyle(next)
                onRebuild()
            }
        entries +=
            RetroMenuEntry.Toggle(context.getString(R.string.retro_lr_background_alpha), checked = style.bgDecoupled) { value ->
                val next =
                    if (!value) {
                        style.copy(
                            bgDecoupled = false,
                            bgAlpha = (style.alpha * FrameRating.BACKDROP_BASE_ALPHA).coerceIn(0.1f, 1f),
                        )
                    } else {
                        style.copy(bgDecoupled = true)
                    }
                onStyle(next)
                onRebuild()
            }
        if (style.bgDecoupled) {
            entries +=
                RetroMenuEntry.Slider(
                    label = context.getString(R.string.retro_lr_background),
                    valueText = "${(style.bgAlpha * 100).toInt()}%",
                    value = style.bgAlpha,
                    min = 0.1f,
                    max = 1f,
                    step = 0.05f,
                ) { value ->
                    onStyle(style.copy(bgAlpha = value))
                    onRebuild()
                }
        }
        entries +=
            RetroMenuEntry.Slider(
                label = context.getString(R.string.retro_lr_scale),
                valueText = "${(style.scale * 100).toInt()}%",
                value = style.scale,
                min = 0.3f,
                max = 2f,
                step = 0.05f,
            ) { value ->
                onStyle(style.copy(scale = value))
                onRebuild()
            }
        entries +=
            RetroMenuEntry.Toggle(context.getString(R.string.retro_lr_numeric_frametime), checked = style.frametimeNumeric) { value ->
                onStyle(style.copy(frametimeNumeric = value))
                onRebuild()
            }
        entries +=
            RetroMenuEntry.Toggle(context.getString(R.string.retro_lr_dual_series_battery), checked = style.dualBattery) { value ->
                onStyle(style.copy(dualBattery = value))
                onRebuild()
            }
        entries +=
            RetroMenuEntry.Chips(
                label = context.getString(R.string.retro_lr_hud_elements),
                items = ELEMENT_ORDER.map { context.getString(ELEMENT_LABEL_RES[it]) },
                states = ELEMENT_ORDER.map { elements[it] },
            ) { position ->
                val index = ELEMENT_ORDER[position]
                val next = elements.copyOf()
                next[index] = !next[index]
                onElements(next)
                onRebuild()
            }
        return entries
    }

    fun suppressNativePs2Osd() {
        runCatching {
            kr.co.iefriends.pcsx2.NativeApp.osdShowFPS(false)
            kr.co.iefriends.pcsx2.NativeApp.osdShowVPS(false)
            kr.co.iefriends.pcsx2.NativeApp.osdShowSpeed(false)
            kr.co.iefriends.pcsx2.NativeApp.osdShowCPU(false)
            kr.co.iefriends.pcsx2.NativeApp.osdShowGPU(false)
            kr.co.iefriends.pcsx2.NativeApp.osdShowResolution(false)
            kr.co.iefriends.pcsx2.NativeApp.osdShowGSStats(false)
            kr.co.iefriends.pcsx2.NativeApp.osdShowFrameTimes(false)
            kr.co.iefriends.pcsx2.NativeApp.osdShowHardwareInfo(false)
            kr.co.iefriends.pcsx2.NativeApp.osdShowVersion(false)
            kr.co.iefriends.pcsx2.NativeApp.osdShowSettings(false)
            kr.co.iefriends.pcsx2.NativeApp.osdShowInputs(false)
            kr.co.iefriends.pcsx2.NativeApp.osdShowGpuStats(false)
        }
    }

    class Ps2FrameSource(
        private val ratingProvider: () -> FrameRating?,
        private val enabledProvider: () -> Boolean,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var lastCount = -1
        private var running = false
        private val tick =
            object : Runnable {
                override fun run() {
                    if (!running) return
                    if (com.armsx2.runtime.MainActivityRuntime.isNativeReady()) {
                        val count = runCatching { kr.co.iefriends.pcsx2.NativeApp.getPresentedFrameCount() }.getOrDefault(0)
                        if (lastCount >= 0 && count >= lastCount) {
                            val delta = (count - lastCount).coerceAtMost(8)
                            com.winlator.cmod.shared.framegen.FrameGen.noteSourceFrames(delta)
                            val rating = ratingProvider()
                            if (enabledProvider() && rating != null) {
                                repeat(delta) { rating.recordGameFrame() }
                            }
                        }
                        lastCount = count
                    }
                    handler.postDelayed(this, 16L)
                }
            }

        fun start() {
            if (running) return
            running = true
            lastCount = -1
            handler.post(tick)
        }

        fun stop() {
            running = false
            handler.removeCallbacks(tick)
        }
    }

    class MenuToggleGate(private val minIntervalMs: Long = 350L) {
        private var lastToggleMs = 0L

        fun allow(): Boolean {
            val now = SystemClock.uptimeMillis()
            if (now - lastToggleMs < minIntervalMs) return false
            lastToggleMs = now
            return true
        }
    }
}

data class HudStyle(
    val alpha: Float = 1f,
    val bgDecoupled: Boolean = false,
    val bgAlpha: Float = FrameRating.BACKDROP_BASE_ALPHA,
    val scale: Float = 1f,
    val frametimeNumeric: Boolean = false,
    val dualBattery: Boolean = false,
)
