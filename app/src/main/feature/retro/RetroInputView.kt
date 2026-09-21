package com.winlator.cmod.feature.retro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.winlator.cmod.runtime.input.controls.GameHubLayout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class RetroInputView(
    context: Context,
    private val listener: Listener,
    private val system: RetroSystem? = null,
) : View(context) {
    interface Listener {
        fun onButton(
            keyCode: Int,
            down: Boolean,
        )

        fun onDpad(
            x: Float,
            y: Float,
        )

        fun onStick(
            x: Float,
            y: Float,
        )

        fun onRightStick(
            x: Float,
            y: Float,
        )

        fun onMenu()
    }

    private enum class GlassShape { CIRCLE, PILL, TRIGGER_LT, TRIGGER_LB, TRIGGER_RT, TRIGGER_RB }

    private class GlassButton(
        val keyCode: Int,
        val label: String,
        val shape: GlassShape,
        val textScale: Float = 1f,
        val bounds: RectF = RectF(),
    )

    private class CButton(
        val dx: Float,
        val dy: Float,
        val glyph: String,
        val bounds: RectF = RectF(),
    )

    private data class OverlayConfig(
        val hasXY: Boolean,
        val hasShoulders: Boolean,
        val hasTriggers: Boolean,
        val hasStick: Boolean,
        val leftTriggerLabel: String = "L2",
        val rightTriggerLabel: String = "R2",
        val leftShoulderLabel: String = "L",
        val rightShoulderLabel: String = "R",
        val showLeftShoulder: Boolean = true,
        val showRightTrigger: Boolean = true,
        val flatFaces: Boolean = false,
        val hasDualSticks: Boolean = false,
        val faceTop: String = "X",
        val faceBottom: String = "B",
        val faceLeft: String = "Y",
        val faceRight: String = "A",
    )

    private val config =
        when (system?.id) {
            RetroSystems.NES.id ->
                OverlayConfig(
                    hasXY = false,
                    hasShoulders = false,
                    hasTriggers = false,
                    hasStick = false,
                    flatFaces = true,
                )
            RetroSystems.SNES.id -> OverlayConfig(hasXY = true, hasShoulders = true, hasTriggers = false, hasStick = false)
            RetroSystems.GBA.id -> OverlayConfig(hasXY = false, hasShoulders = true, hasTriggers = false, hasStick = false)
            RetroSystems.GENESIS.id -> OverlayConfig(hasXY = true, hasShoulders = true, hasTriggers = false, hasStick = false)
            RetroSystems.N64.id ->
                OverlayConfig(
                    hasXY = false,
                    hasShoulders = true,
                    hasTriggers = true,
                    hasStick = true,
                    leftTriggerLabel = "Z",
                    showRightTrigger = false,
                )
            RetroSystems.GAMECUBE.id ->
                OverlayConfig(
                    hasXY = true,
                    hasShoulders = true,
                    hasTriggers = true,
                    hasStick = false,
                    hasDualSticks = true,
                    leftTriggerLabel = "L",
                    rightTriggerLabel = "R",
                    leftShoulderLabel = "Z",
                    rightShoulderLabel = "Z",
                    showLeftShoulder = false,
                    faceTop = "X",
                    faceBottom = "B",
                    faceLeft = "Y",
                    faceRight = "A",
                )
            RetroSystems.WII.id ->
                OverlayConfig(
                    hasXY = true,
                    hasShoulders = true,
                    hasTriggers = true,
                    hasStick = false,
                    hasDualSticks = true,
                    leftTriggerLabel = "ZL",
                    rightTriggerLabel = "ZR",
                    leftShoulderLabel = "L",
                    rightShoulderLabel = "R",
                    faceTop = "X",
                    faceBottom = "B",
                    faceLeft = "Y",
                    faceRight = "A",
                )
            RetroSystems.PS2.id ->
                OverlayConfig(
                    hasXY = true,
                    hasShoulders = true,
                    hasTriggers = true,
                    hasStick = false,
                    hasDualSticks = true,
                    faceTop = "\u25b3",
                    faceBottom = "\u2715",
                    faceLeft = "\u25a1",
                    faceRight = "\u25cb",
                )
            RetroSystems.PSX.id ->
                OverlayConfig(
                    hasXY = true,
                    hasShoulders = true,
                    hasTriggers = true,
                    hasStick = false,
                    faceTop = "△",
                    faceBottom = "✕",
                    faceLeft = "□",
                    faceRight = "○",
                )
            else -> OverlayConfig(hasXY = false, hasShoulders = false, hasTriggers = false, hasStick = false)
        }

    private fun faceGlyphScale(label: String): Float =
        when (label) {
            "\u25cb" -> 1.85f
            "\u25b3" -> 1.55f
            "\u25a1" -> 1.45f
            "\u2715" -> 1f
            else -> 1f
        }

    private val buttons = mutableListOf<GlassButton>()
    private val cButtons = mutableListOf<CButton>()
    private val menuButton = GlassButton(0, "MENU", GlassShape.PILL, textScale = 0.75f)
    private var snap = 0f
    private var cStickX = 0f
    private var cStickY = 0f

    private class RetroTheme(
        val body: Int,
        val dpad: Int,
        val pill: Int,
        val pillText: Int,
        val button: Int,
        val buttonText: Int,
        val buttonColors: Map<String, Int> = emptyMap(),
        val buttonTextColors: Map<String, Int> = emptyMap(),
        val backplate: Int = 0,
        val clusterPlate: Int = 0,
        val stickCap: Int = 0xFFB4B4BA.toInt(),
        val cButton: Int = 0xFFE3BE2A.toInt(),
        val cText: Int = 0xFF4A3B08.toInt(),
    )

    private val theme: RetroTheme =
        when (system?.id) {
            RetroSystems.NES.id ->
                RetroTheme(
                    body = 0xFFD8D5CD.toInt(),
                    dpad = 0xFF26262A.toInt(),
                    pill = 0xFF303034.toInt(),
                    pillText = 0xFFD8D5CD.toInt(),
                    button = 0xFFC03A30.toInt(),
                    buttonText = 0xFFF6EAE8.toInt(),
                    backplate = 0xFFEDEBE6.toInt(),
                )
            RetroSystems.SNES.id ->
                RetroTheme(
                    body = 0xFFCBCBD3.toInt(),
                    dpad = 0xFF3E3E46.toInt(),
                    pill = 0xFF8F8F98.toInt(),
                    pillText = 0xFF2F2F36.toInt(),
                    button = 0xFF564A9E.toInt(),
                    buttonText = 0xFFEDEBFA.toInt(),
                    buttonColors =
                        mapOf(
                            "X" to 0xFFBCB6DF.toInt(),
                            "Y" to 0xFFBCB6DF.toInt(),
                        ),
                    buttonTextColors =
                        mapOf(
                            "X" to 0xFF423D66.toInt(),
                            "Y" to 0xFF423D66.toInt(),
                        ),
                    clusterPlate = 0xFFB2B1BB.toInt(),
                )
            RetroSystems.GAMEBOY.id ->
                RetroTheme(
                    body = 0xFFC9C6C1.toInt(),
                    dpad = 0xFF2E2E33.toInt(),
                    pill = 0xFF8A8A86.toInt(),
                    pillText = 0xFF33333A.toInt(),
                    button = 0xFF8E3162.toInt(),
                    buttonText = 0xFFF2DEE8.toInt(),
                )
            RetroSystems.GAMEBOY_COLOR.id ->
                RetroTheme(
                    body = 0xFF5F45AC.toInt(),
                    dpad = 0xFF26262B.toInt(),
                    pill = 0xFF453D78.toInt(),
                    pillText = 0xFFD9D3F2.toInt(),
                    button = 0xFF37305E.toInt(),
                    buttonText = 0xFFCFC8EE.toInt(),
                )
            RetroSystems.GBA.id ->
                RetroTheme(
                    body = 0xFF7C74C6.toInt(),
                    dpad = 0xFF33333A.toInt(),
                    pill = 0xFF9C96CC.toInt(),
                    pillText = 0xFF3C3866.toInt(),
                    button = 0xFFD3CFEA.toInt(),
                    buttonText = 0xFF4A4670.toInt(),
                )
            RetroSystems.N64.id ->
                RetroTheme(
                    body = 0xFFA7A7AD.toInt(),
                    dpad = 0xFF4C4C54.toInt(),
                    pill = 0xFF77777F.toInt(),
                    pillText = 0xFFEFEFF4.toInt(),
                    button = 0xFF77777F.toInt(),
                    buttonText = 0xFFEFEFF4.toInt(),
                    buttonColors =
                        mapOf(
                            "A" to 0xFF2E63C9.toInt(),
                            "B" to 0xFF2F9E44.toInt(),
                            "START" to 0xFFC8362E.toInt(),
                        ),
                    stickCap = 0xFF9EA0A6.toInt(),
                )
            RetroSystems.GAMECUBE.id ->
                RetroTheme(
                    body = 0xFF6B6F78.toInt(),
                    dpad = 0xFF3A3D44.toInt(),
                    pill = 0xFF555861.toInt(),
                    pillText = 0xFFEDEFF4.toInt(),
                    button = 0xFF555861.toInt(),
                    buttonText = 0xFFEDEFF4.toInt(),
                    buttonColors =
                        mapOf(
                            "A" to 0xFF2F8F4E.toInt(),
                            "B" to 0xFFC8362E.toInt(),
                            "X" to 0xFFE8E8EC.toInt(),
                            "Y" to 0xFFE8E8EC.toInt(),
                            "START" to 0xFF3A3D44.toInt(),
                            "Z" to 0xFF5B3FA0.toInt(),
                        ),
                    buttonTextColors =
                        mapOf(
                            "X" to 0xFF3A3D44.toInt(),
                            "Y" to 0xFF3A3D44.toInt(),
                        ),
                    stickCap = 0xFF8A8E98.toInt(),
                )
            RetroSystems.WII.id ->
                RetroTheme(
                    body = 0xFFE4E6EA.toInt(),
                    dpad = 0xFF2C2E34.toInt(),
                    pill = 0xFFB8BCC4.toInt(),
                    pillText = 0xFF2C2E34.toInt(),
                    button = 0xFF2C2E34.toInt(),
                    buttonText = 0xFFF2F4F8.toInt(),
                    buttonColors =
                        mapOf(
                            "A" to 0xFF1BA0D8.toInt(),
                            "B" to 0xFF2C2E34.toInt(),
                            "X" to 0xFFD8DCE4.toInt(),
                            "Y" to 0xFFD8DCE4.toInt(),
                            "+" to 0xFF1BA0D8.toInt(),
                            "START" to 0xFF1BA0D8.toInt(),
                        ),
                    buttonTextColors =
                        mapOf(
                            "X" to 0xFF2C2E34.toInt(),
                            "Y" to 0xFF2C2E34.toInt(),
                        ),
                    stickCap = 0xFFC8CCD4.toInt(),
                )
            RetroSystems.GENESIS.id, RetroSystems.MASTER_SYSTEM.id, RetroSystems.GAME_GEAR.id ->
                RetroTheme(
                    body = 0xFF1D1D21.toInt(),
                    dpad = 0xFF0E0E11.toInt(),
                    pill = 0xFF2C2C33.toInt(),
                    pillText = 0xFFB9B9C2.toInt(),
                    button = 0xFF303038.toInt(),
                    buttonText = 0xFFB9B9C2.toInt(),
                )
            RetroSystems.PSX.id, RetroSystems.PS2.id ->
                RetroTheme(
                    body = 0xFFB7B7C0.toInt(),
                    dpad = 0xFF50505A.toInt(),
                    pill = 0xFF5A5A64.toInt(),
                    pillText = 0xFFE9E9F0.toInt(),
                    button = 0xFF3D3D45.toInt(),
                    buttonText = 0xFFE9E9F0.toInt(),
                    buttonTextColors =
                        mapOf(
                            "△" to 0xFF43B26A.toInt(),
                            "○" to 0xFFD64B45.toInt(),
                            "✕" to 0xFF8FA9DF.toInt(),
                            "□" to 0xFFD387BE.toInt(),
                        ),
                )
            else ->
                RetroTheme(
                    body = 0xFFC9C6C1.toInt(),
                    dpad = 0xFF2E2E33.toInt(),
                    pill = 0xFF8A8A86.toInt(),
                    pillText = 0xFF33333A.toInt(),
                    button = 0xFF55555C.toInt(),
                    buttonText = 0xFFEFEFF4.toInt(),
                )
        }

    private var gameArea: RectF? = null
    var hapticStrength = 0f
    var adaptiveSticks: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                stickActive = false
                stick2Active = false
                invalidate()
            }
        }
    val supportsStickButtons: Boolean get() = config.hasDualSticks

    var shellBackground: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var showL3R3: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                if (width > 0 && height > 0) {
                    relayout()
                    invalidate()
                }
            }
        }
    var invertLX = false
    var invertLY = false
    var invertRX = false
    var invertRY = false

    fun loadStickInversion() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val sys = system?.id ?: return
        invertLX = prefs.getBoolean("retro_inv_lx_$sys", false)
        invertLY = prefs.getBoolean("retro_inv_ly_$sys", false)
        invertRX = prefs.getBoolean("retro_inv_rx_$sys", false)
        invertRY = prefs.getBoolean("retro_inv_ry_$sys", false)
    }

    private fun ix(v: Float, inv: Boolean) = if (inv) -v else v
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    var editMode = false
        private set
    var onEditStateChanged: ((Boolean) -> Unit)? = null
    private var customColors = RetroCustomColors()
    private var overrides: MutableMap<String, RetroControlOverride> = mutableMapOf()
    private var overridesLoaded = false
    private var overridesPortrait = false
    private var selectedId: String? = null
    private var dragId: String? = null
    private var dragPointerId = -1
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var dragActive = false
    private var dragDownX = 0f
    private var dragDownY = 0f
    private var swallowInput = false
    private var dpadVisible = true
    private var stickVisible = true
    private var stick2Visible = true
    private val hiddenControls = mutableListOf<Pair<String, String>>()
    private val toolbarPills = mutableListOf<Triple<String, String, RectF>>()
    private val trayPills = mutableListOf<Triple<String, String, RectF>>()

    private val editUnit: Float
        get() = snap * (if (height > width) 2f else 1f)

    fun setCustomColors(colors: RetroCustomColors) {
        customColors = colors
        invalidate()
    }

    private fun cancelDrag() {
        dragId = null
        dragPointerId = -1
        dragActive = false
    }

    fun enterEdit() {
        if (editMode) return
        releaseAll()
        editMode = true
        selectedId = null
        cancelDrag()
        onEditStateChanged?.invoke(true)
        relayout()
    }

    fun finishEdit() {
        if (!editMode) return
        editMode = false
        selectedId = null
        cancelDrag()
        swallowInput = true
        saveOverrides()
        onEditStateChanged?.invoke(false)
        relayout()
    }

    fun resetLayout() {
        overrides.clear()
        RetroControlLayouts.reset(context, system?.id, height > width)
        selectedId = null
        relayout()
    }

    private fun saveOverrides() {
        RetroControlLayouts.save(context, system?.id, height > width, overrides)
    }

    fun setGameArea(area: RectF?) {
        if (area == gameArea) return
        gameArea = area
        relayout()
    }

    private fun hapticTick() {
        if (hapticStrength <= 0.01f) return
        val amplitude = (hapticStrength * 255f).toInt().coerceIn(1, 255)
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(15, amplitude.toInt())) }
    }

    private fun mix(
        a: Int,
        b: Int,
        f: Float,
    ): Int {
        val inv = 1f - f
        return Color.argb(
            255,
            (Color.red(a) * inv + Color.red(b) * f).toInt(),
            (Color.green(a) * inv + Color.green(b) * f).toInt(),
            (Color.blue(a) * inv + Color.blue(b) * f).toInt(),
        )
    }

    private fun lighten(
        color: Int,
        f: Float,
    ) = mix(color, Color.WHITE, f)

    private fun darken(
        color: Int,
        f: Float,
    ) = mix(color, Color.BLACK, f)

    private var dpadCx = 0f
    private var dpadCy = 0f
    private var dpadRadius = 0f

    private var stickCx = 0f
    private var stickCy = 0f
    private var stickRadius = 0f
    private var stickPointerId = -1
    private var stickX = 0f
    private var stickY = 0f
    private var stick2Cx = 0f
    private var stick2Cy = 0f
    private var stick2Radius = 0f
    private var stick2PointerId = -1
    private var stick2X = 0f
    private var stick2Y = 0f

    private var stickActive = false
    private var stickOriginX = 0f
    private var stickOriginY = 0f
    private var stick2Active = false
    private var stick2OriginX = 0f
    private var stick2OriginY = 0f

    private val DOUBLE_TAP_MS = 500L
    private val TAP_MAX_MS = 300L
    private val TAP_MAX_MAG = 0.35f
    private val DEAD_ZONE_PX = 10f

    private val DPAD_AXIS_SWAP_BIAS = 1.35f
    private val FACE_KEYCODES = setOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
    )
    private var stickDownTime = 0L
    private var stickMaxMag = 0f
    private var stickLastTapTime = 0L
    private var stickL3Held = false
    private var stick2DownTime = 0L
    private var stick2MaxMag = 0f
    private var stick2LastTapTime = 0L
    private var stickR3Held = false

    private val pressedButtons = HashSet<Int>()
    private var dpadX = 0f
    private var dpadY = 0f
    private var menuLatched = false

    private val pointerButton = HashMap<Int, Int>()
    private val pointerCButton = HashMap<Int, CButton>()
    private var dpadPointerId = -1
    private var menuPointerId = -1

    private var strokeWidth = 4f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val arrowCenter = FloatArray(2)

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    fun relayout() {
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return
        val portrait = height > width
        if (!overridesLoaded || overridesPortrait != portrait) {
            overrides = RetroControlLayouts.load(context, system?.id, portrait)
            overridesLoaded = true
            overridesPortrait = portrait
            cancelDrag()
        }
        buttons.clear()
        cButtons.clear()
        if (portrait) {
            layoutPortrait(width, height)
        } else {
            layoutLandscape(width, height)
        }
        applyOverrides(width, height)
        if (editMode) layoutEditChrome(width, height)
        invalidate()
    }

    private fun idFor(button: GlassButton): String =
        if (button === menuButton) {
            "menu"
        } else {
            when (button.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> "btn_a"
                KeyEvent.KEYCODE_BUTTON_B -> "btn_b"
                KeyEvent.KEYCODE_BUTTON_X -> "btn_x"
                KeyEvent.KEYCODE_BUTTON_Y -> "btn_y"
                KeyEvent.KEYCODE_BUTTON_L1 -> "l1"
                KeyEvent.KEYCODE_BUTTON_R1 -> "r1"
                KeyEvent.KEYCODE_BUTTON_L2 -> "l2"
                KeyEvent.KEYCODE_BUTTON_R2 -> "r2"
                KeyEvent.KEYCODE_BUTTON_THUMBL -> "l3"
                KeyEvent.KEYCODE_BUTTON_THUMBR -> "r3"
                KeyEvent.KEYCODE_BUTTON_START -> "start"
                KeyEvent.KEYCODE_BUTTON_SELECT -> "select"
                else -> "btn_${button.keyCode}"
            }
        }

    private fun reposition(
        button: GlassButton,
        o: RetroControlOverride,
        w: Float,
        h: Float,
    ) {
        val hw = button.bounds.width() * 0.5f * o.scale
        val hh = button.bounds.height() * 0.5f * o.scale
        button.bounds.set(o.x * w - hw, o.y * h - hh, o.x * w + hw, o.y * h + hh)
    }

    private fun applyOverrides(
        w: Float,
        h: Float,
    ) {
        dpadVisible = true
        stickVisible = true
        stick2Visible = true
        hiddenControls.clear()
        var hideCpad = false
        overrides.forEach { (id, o) ->
            when (id) {
                "dpad" ->
                    if (!o.visible) {
                        dpadVisible = false
                        hiddenControls += id to "DPAD"
                    } else {
                        dpadCx = o.x * w
                        dpadCy = o.y * h
                        dpadRadius *= o.scale
                    }
                "stick" ->
                    if (config.hasStick || config.hasDualSticks) {
                        if (!o.visible) {
                            stickVisible = false
                            hiddenControls += id to if (config.hasDualSticks) "L-STICK" else "STICK"
                        } else {
                            stickCx = o.x * w
                            stickCy = o.y * h
                            stickRadius *= o.scale
                        }
                    }
                "stick2" ->
                    if (config.hasDualSticks) {
                        if (!o.visible) {
                            stick2Visible = false
                            hiddenControls += id to "R-STICK"
                        } else {
                            stick2Cx = o.x * w
                            stick2Cy = o.y * h
                            stick2Radius *= o.scale
                        }
                    }
                "cpad" ->
                    if (cButtons.isNotEmpty()) {
                        if (!o.visible) {
                            hideCpad = true
                            hiddenControls += id to "C-PAD"
                        } else {
                            val cx = cButtons.map { it.bounds.centerX() }.average().toFloat()
                            val cy = cButtons.map { it.bounds.centerY() }.average().toFloat()
                            val nx = o.x * w
                            val ny = o.y * h
                            cButtons.forEach { c ->
                                val rx = (c.bounds.centerX() - cx) * o.scale
                                val ry = (c.bounds.centerY() - cy) * o.scale
                                val half = c.bounds.width() * 0.5f * o.scale
                                c.bounds.set(nx + rx - half, ny + ry - half, nx + rx + half, ny + ry + half)
                            }
                        }
                    }
                "menu" -> reposition(menuButton, o, w, h)
                else -> {
                    val target = buttons.firstOrNull { idFor(it) == id } ?: return@forEach
                    if (!o.visible) {
                        buttons.remove(target)
                        hiddenControls += id to target.label
                    } else {
                        reposition(target, o, w, h)
                    }
                }
            }
        }
        if (hideCpad) cButtons.clear()
        if (selectedId != null && controlRect(selectedId!!) == null) selectedId = null
    }

    private fun controlRect(id: String): RectF? =
        when (id) {
            "dpad" ->
                if (dpadVisible && dpadRadius > 0f) {
                    RectF(dpadCx - dpadRadius, dpadCy - dpadRadius, dpadCx + dpadRadius, dpadCy + dpadRadius)
                } else {
                    null
                }
            "stick" ->
                if ((config.hasStick || config.hasDualSticks) && stickVisible && stickRadius > 0f) {
                    RectF(stickCx - stickRadius, stickCy - stickRadius, stickCx + stickRadius, stickCy + stickRadius)
                } else {
                    null
                }
            "stick2" ->
                if (config.hasDualSticks && stick2Visible && stick2Radius > 0f) {
                    RectF(
                        stick2Cx - stick2Radius,
                        stick2Cy - stick2Radius,
                        stick2Cx + stick2Radius,
                        stick2Cy + stick2Radius,
                    )
                } else {
                    null
                }
            "cpad" ->
                if (cButtons.isNotEmpty()) {
                    RectF(
                        cButtons.minOf { it.bounds.left },
                        cButtons.minOf { it.bounds.top },
                        cButtons.maxOf { it.bounds.right },
                        cButtons.maxOf { it.bounds.bottom },
                    )
                } else {
                    null
                }
            "menu" -> menuButton.bounds
            else -> buttons.firstOrNull { idFor(it) == id }?.bounds
        }

    private fun allControlIds(): List<String> =
        buildList {
            addAll(buttons.map { idFor(it) })
            add("menu")
            add("dpad")
            if (config.hasStick || config.hasDualSticks) add("stick")
            if (config.hasDualSticks) add("stick2")
            add("cpad")
        }

    private fun ensureOverride(id: String): RetroControlOverride {
        overrides[id]?.let { return it }
        val r = controlRect(id)
        val o =
            RetroControlOverride(
                (r?.centerX() ?: width * 0.5f) / width,
                (r?.centerY() ?: height * 0.5f) / height,
            )
        overrides[id] = o
        return o
    }

    private fun moveControl(
        id: String,
        cx: Float,
        cy: Float,
    ) {
        val o = ensureOverride(id)
        o.x = (cx / width).coerceIn(0.02f, 0.98f)
        o.y = (cy / height).coerceIn(0.02f, 0.98f)
        relayout()
    }

    private fun scaleControl(
        id: String,
        delta: Float,
    ) {
        val o = ensureOverride(id)
        o.scale = (o.scale + delta).coerceIn(0.6f, 1.8f)
        saveOverrides()
        relayout()
    }

    private fun setControlVisible(
        id: String,
        visible: Boolean,
    ) {
        val o = ensureOverride(id)
        o.visible = visible
        saveOverrides()
        relayout()
    }

    private fun layoutLandscape(
        width: Float,
        height: Float,
    ) {
        if (config.hasStick) {
            layoutN64(width, height)
            return
        }
        snap = width / 100f
        val margin = snap * 2.5f
        val bottomGap = snap * 9f
        var faceRadius = snap * 3f
        strokeWidth = max(2f, snap * 0.18f)

        val trigW = snap * 10.4f
        val trigH = snap * 5.2f
        val trigGap = snap * 1.5f

        var leftCursor = margin
        var rightCursor = margin
        if (config.hasTriggers) {
            val lt =
                GlassButton(KeyEvent.KEYCODE_BUTTON_L2, config.leftTriggerLabel, GlassShape.TRIGGER_LT, textScale = 1.3f)
            lt.bounds.set(margin, leftCursor, margin + trigW, leftCursor + trigH)
            buttons += lt
            leftCursor += trigH + trigGap
            if (config.showRightTrigger) {
                val rt =
                    GlassButton(
                        KeyEvent.KEYCODE_BUTTON_R2,
                        config.rightTriggerLabel,
                        GlassShape.TRIGGER_RT,
                        textScale = 1.3f,
                    )
                rt.bounds.set(width - margin - trigW, rightCursor, width - margin, rightCursor + trigH)
                buttons += rt
                rightCursor += trigH + trigGap
            }
        }
        if (config.hasShoulders) {
            val leftShape = if (config.hasTriggers) GlassShape.TRIGGER_LB else GlassShape.TRIGGER_LT
            val rightShape = if (config.hasTriggers) GlassShape.TRIGGER_RB else GlassShape.TRIGGER_RT
            if (config.showLeftShoulder) {
                val lb =
                    GlassButton(
                        KeyEvent.KEYCODE_BUTTON_L1,
                        config.leftShoulderLabel,
                        leftShape,
                        textScale = 1.3f,
                    )
                lb.bounds.set(margin, leftCursor, margin + trigW, leftCursor + trigH)
                buttons += lb
            }
            val rb =
                GlassButton(
                    KeyEvent.KEYCODE_BUTTON_R1,
                    config.rightShoulderLabel,
                    rightShape,
                    textScale = 1.3f,
                )
            rb.bounds.set(width - margin - trigW, rightCursor, width - margin, rightCursor + trigH)
            buttons += rb
            leftCursor += trigH + trigGap
        }

        var spread = snap * 5.5f
        var clusterCx = width - margin - faceRadius - spread
        val rightBarWidth = gameArea?.let { width - it.right } ?: 0f
        if (rightBarWidth > snap * 8f) {
            val clusterHalf = if (config.hasXY) faceRadius + spread else faceRadius * 2.45f
            val avail = rightBarWidth * 0.5f - snap
            if (clusterHalf > avail) {
                val fit = (avail / clusterHalf).coerceAtLeast(0.62f)
                faceRadius *= fit
                spread *= fit
            }
            clusterCx = width - rightBarWidth * 0.5f
        }
        val clusterCy =
            if (config.hasDualSticks) {
                dpadCy - dpadRadius + spread + faceRadius
            } else {
                height - bottomGap - faceRadius - spread
            }
        var clusterTop = height
        fun addFace(
            keyCode: Int,
            label: String,
            cx: Float,
            cy: Float,
        ) {
            val button = GlassButton(keyCode, label, GlassShape.CIRCLE, textScale = faceGlyphScale(label))
            button.bounds.set(cx - faceRadius, cy - faceRadius, cx + faceRadius, cy + faceRadius)
            buttons += button
            clusterTop = min(clusterTop, button.bounds.top)
        }
        if (config.hasXY) {
            addFace(KeyEvent.KEYCODE_BUTTON_X, config.faceTop, clusterCx, clusterCy - spread)
            addFace(KeyEvent.KEYCODE_BUTTON_B, config.faceBottom, clusterCx, clusterCy + spread)
            addFace(KeyEvent.KEYCODE_BUTTON_Y, config.faceLeft, clusterCx - spread, clusterCy)
            addFace(KeyEvent.KEYCODE_BUTTON_A, config.faceRight, clusterCx + spread, clusterCy)
        } else if (config.flatFaces) {
            addFace(KeyEvent.KEYCODE_BUTTON_B, "B", clusterCx - faceRadius * 1.35f, clusterCy + spread * 0.5f)
            addFace(KeyEvent.KEYCODE_BUTTON_A, "A", clusterCx + faceRadius * 1.35f, clusterCy + spread * 0.5f)
        } else {
            addFace(KeyEvent.KEYCODE_BUTTON_B, "B", clusterCx - faceRadius * 1.1f, clusterCy + spread * 0.5f + faceRadius * 0.5f)
            addFace(KeyEvent.KEYCODE_BUTTON_A, "A", clusterCx + faceRadius * 1.1f, clusterCy + spread * 0.5f - faceRadius * 1.1f)
        }

        val pillW = snap * 6f
        val pillH = snap * 3f
        val pillGap = snap * 1.2f
        stickRadius = 0f
        dpadRadius = snap * 7.5f
        dpadCx = margin + dpadRadius
        val leftBarWidth = gameArea?.left ?: 0f
        if (leftBarWidth > snap * 8f) {
            val avail = leftBarWidth - snap * 2f
            if (dpadRadius * 2f > avail) dpadRadius = (avail * 0.5f).coerceAtLeast(snap * 5f)
            dpadCx = leftBarWidth * 0.5f
        }
        dpadCy = height - bottomGap - dpadRadius

        if (config.hasDualSticks) {
            stickRadius = snap * 5.5f
            stick2Radius = stickRadius
            dpadCy -= stickRadius * 0.9f
            stickCx = dpadCx + dpadRadius + snap * 0.8f + stickRadius
            stickCy = height - snap * 4.5f - stickRadius
            stick2Cx = width - stickCx
            stick2Cy = stickCy

            if (showL3R3) {
            val l3r = faceRadius * 0.82f
            val l3cy = height - snap * 3.2f - l3r
            val l3cx = margin + l3r + snap * 0.5f
            val r3cx = width - margin - l3r - snap * 0.5f
            val l3 = GlassButton(KeyEvent.KEYCODE_BUTTON_THUMBL, "L3", GlassShape.CIRCLE, textScale = 0.8f)
            l3.bounds.set(l3cx - l3r, l3cy - l3r, l3cx + l3r, l3cy + l3r)
            buttons += l3
            val r3 = GlassButton(KeyEvent.KEYCODE_BUTTON_THUMBR, "R3", GlassShape.CIRCLE, textScale = 0.8f)
            r3.bounds.set(r3cx - l3r, l3cy - l3r, r3cx + l3r, l3cy + l3r)
            buttons += r3
            }
        }

        if (config.hasTriggers && config.hasXY) {
            val rowY = height - snap * 2.5f - pillH
            var pillX = (width - pillW * 3f - pillGap * 2f) * 0.5f
            menuButton.bounds.set(pillX, rowY, pillX + pillW, rowY + pillH)
            pillX += pillW + pillGap
            val select = GlassButton(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT", GlassShape.PILL, textScale = 0.75f)
            select.bounds.set(pillX, rowY, pillX + pillW, rowY + pillH)
            buttons += select
            pillX += pillW + pillGap
            val start = GlassButton(KeyEvent.KEYCODE_BUTTON_START, "START", GlassShape.PILL, textScale = 0.75f)
            start.bounds.set(pillX, rowY, pillX + pillW, rowY + pillH)
            buttons += start
            return
        }

        val pillY = clusterTop - pillH - snap * 3.5f
        val start = GlassButton(KeyEvent.KEYCODE_BUTTON_START, "START", GlassShape.PILL, textScale = 0.75f)
        start.bounds.set(width - margin - pillW, pillY, width - margin, pillY + pillH)
        buttons += start
        val select = GlassButton(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT", GlassShape.PILL, textScale = 0.75f)
        select.bounds.set(
            width - margin - pillW * 2 - pillGap,
            pillY,
            width - margin - pillW - pillGap,
            pillY + pillH,
        )
        buttons += select

        val menuW = snap * 6f
        val menuY =
            min(
                max(pillY, leftCursor),
                dpadCy - dpadRadius - pillH - snap * 3f,
            )
        menuButton.bounds.set(dpadCx - menuW * 0.5f, menuY, dpadCx + menuW * 0.5f, menuY + pillH)
    }

    private fun layoutN64(
        width: Float,
        height: Float,
    ) {
        snap = width / 100f
        strokeWidth = max(2f, snap * 0.18f)
        val margin = snap * 2.5f
        val trigW = snap * 10.4f
        val trigH = snap * 5.2f
        val trigGap = snap * 1.5f

        val lb = GlassButton(KeyEvent.KEYCODE_BUTTON_L1, "L", GlassShape.TRIGGER_LT, textScale = 1.3f)
        lb.bounds.set(margin, margin, margin + trigW, margin + trigH)
        buttons += lb
        val rb = GlassButton(KeyEvent.KEYCODE_BUTTON_R1, "R", GlassShape.TRIGGER_RT, textScale = 1.3f)
        rb.bounds.set(width - margin - trigW, margin, width - margin, margin + trigH)
        buttons += rb
        val z =
            GlassButton(KeyEvent.KEYCODE_BUTTON_L2, config.leftTriggerLabel, GlassShape.TRIGGER_RB, textScale = 1.3f)
        z.bounds.set(
            width - margin - trigW,
            margin + trigH + trigGap,
            width - margin,
            margin + trigH * 2 + trigGap,
        )
        buttons += z

        val faceRadius = snap * 3f
        val spread = snap * 5.5f
        var clusterCx = width - margin - faceRadius - spread
        val rightBarWidth = gameArea?.let { width - it.right } ?: 0f
        if (rightBarWidth >= (faceRadius + spread) * 2f + snap * 2f) {
            clusterCx = width - rightBarWidth * 0.5f
        }
        val clusterCy = height - snap * 4.5f - faceRadius - spread
        val bCx = clusterCx - faceRadius * 0.9f
        val bCy = clusterCy + spread * 0.5f - faceRadius * 0.9f
        val bButton = GlassButton(KeyEvent.KEYCODE_BUTTON_Y, "B", GlassShape.CIRCLE)
        bButton.bounds.set(bCx - faceRadius, bCy - faceRadius, bCx + faceRadius, bCy + faceRadius)
        buttons += bButton
        val aCx = clusterCx + faceRadius * 0.9f
        val aCy = clusterCy + spread * 0.5f + faceRadius * 0.9f
        val aButton = GlassButton(KeyEvent.KEYCODE_BUTTON_B, "A", GlassShape.CIRCLE)
        aButton.bounds.set(aCx - faceRadius, aCy - faceRadius, aCx + faceRadius, aCy + faceRadius)
        buttons += aButton

        val pillW = snap * 6f
        val pillH = snap * 3f
        val pillGap = snap * 1.2f
        val pillY = height - snap * 2.5f - pillH
        var pillX = (width - pillW * 3f - pillGap * 2f) * 0.5f
        menuButton.bounds.set(pillX, pillY, pillX + pillW, pillY + pillH)
        pillX += pillW + pillGap
        val select = GlassButton(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT", GlassShape.PILL, textScale = 0.75f)
        select.bounds.set(pillX, pillY, pillX + pillW, pillY + pillH)
        buttons += select
        pillX += pillW + pillGap
        val start = GlassButton(KeyEvent.KEYCODE_BUTTON_START, "START", GlassShape.PILL, textScale = 0.75f)
        start.bounds.set(pillX, pillY, pillX + pillW, pillY + pillH)
        buttons += start

        stickRadius = snap * 7f
        stickCx = margin + stickRadius + snap * 1f
        val leftBarWidth = gameArea?.left ?: 0f
        if (leftBarWidth >= stickRadius * 2f + snap * 2f) {
            stickCx = leftBarWidth * 0.5f
        }
        stickCy = height - snap * 5.5f - stickRadius
        dpadRadius = snap * 6.5f
        dpadCx = stickCx
        dpadCy = stickCy - stickRadius - snap * 2f - dpadRadius

        val cRadius = snap * 2.4f
        val cSpread = snap * 3.6f
        val cCx = clusterCx + snap * 2f
        val topOfFaces = bCy - faceRadius
        val bottomOfTriggers = margin + trigH * 2 + trigGap
        val cCy =
            min(
                (bottomOfTriggers + topOfFaces) * 0.5f + snap * 2f,
                topOfFaces - snap * 1.5f - cSpread - cRadius,
            )
        fun addC(
            dx: Float,
            dy: Float,
            glyph: String,
            x: Float,
            y: Float,
        ) {
            val c = CButton(dx, dy, glyph)
            c.bounds.set(x - cRadius, y - cRadius, x + cRadius, y + cRadius)
            cButtons += c
        }
        addC(0f, -1f, "▲", cCx, cCy - cSpread)
        addC(0f, 1f, "▼", cCx, cCy + cSpread)
        addC(-1f, 0f, "◀", cCx - cSpread, cCy)
        addC(1f, 0f, "▶", cCx + cSpread, cCy)
    }

    private val portraitGameAspect: Float
        get() =
            when (system?.id) {
                RetroSystems.GAMEBOY.id, RetroSystems.GAMEBOY_COLOR.id -> 0.9f
                RetroSystems.GBA.id -> 2f / 3f
                else -> 0.75f
            }

    private fun portraitZoneTop(
        width: Float,
        height: Float,
    ): Float = max(width * portraitGameAspect + snap * 8f, height * 0.42f)

    private fun layoutPortrait(
        width: Float,
        height: Float,
    ) {
        if (config.hasStick) {
            layoutPortraitN64(width, height)
            return
        }
        snap = width / 100f
        strokeWidth = max(2f, snap * 0.4f)
        stickRadius = 0f
        val zoneTop = portraitZoneTop(width, height)
        val zoneH = height - zoneTop
        val margin = snap * 5f
        val trigW = snap * 24f
        val trigH = snap * 8f
        val trigGap = snap * 1.5f

        var sideCursor = zoneTop + snap * 2f
        if (config.hasTriggers) {
            val lt = GlassButton(KeyEvent.KEYCODE_BUTTON_L2, config.leftTriggerLabel, GlassShape.TRIGGER_LT, textScale = 1.3f)
            lt.bounds.set(margin, sideCursor, margin + trigW, sideCursor + trigH)
            buttons += lt
            if (config.showRightTrigger) {
                val rt =
                    GlassButton(
                        KeyEvent.KEYCODE_BUTTON_R2,
                        config.rightTriggerLabel,
                        GlassShape.TRIGGER_RT,
                        textScale = 1.3f,
                    )
                rt.bounds.set(width - margin - trigW, sideCursor, width - margin, sideCursor + trigH)
                buttons += rt
            }
            sideCursor += trigH + trigGap
        }
        if (config.hasShoulders) {
            val leftShape = if (config.hasTriggers) GlassShape.TRIGGER_LB else GlassShape.TRIGGER_LT
            val rightShape = if (config.hasTriggers) GlassShape.TRIGGER_RB else GlassShape.TRIGGER_RT
            if (config.showLeftShoulder) {
                val lb =
                    GlassButton(
                        KeyEvent.KEYCODE_BUTTON_L1,
                        config.leftShoulderLabel,
                        leftShape,
                        textScale = 1.3f,
                    )
                lb.bounds.set(margin, sideCursor, margin + trigW, sideCursor + trigH)
                buttons += lb
            }
            val rb =
                GlassButton(
                    KeyEvent.KEYCODE_BUTTON_R1,
                    config.rightShoulderLabel,
                    rightShape,
                    textScale = 1.3f,
                )
            rb.bounds.set(width - margin - trigW, sideCursor, width - margin, sideCursor + trigH)
            buttons += rb
            sideCursor += trigH + trigGap
        }

        val rowCy = max(zoneTop + zoneH * 0.45f, sideCursor + snap * 18f)
        dpadRadius = snap * 15f
        dpadCx = margin + dpadRadius
        dpadCy = rowCy

        fun addFace(
            keyCode: Int,
            label: String,
            cx: Float,
            cy: Float,
            radius: Float,
        ) {
            val button = GlassButton(keyCode, label, GlassShape.CIRCLE, textScale = faceGlyphScale(label))
            button.bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
            buttons += button
        }
        if (config.hasXY) {
            val faceRadius = snap * 6.5f
            val spread = snap * 12f
            val clusterCx = width - margin - faceRadius - spread
            addFace(KeyEvent.KEYCODE_BUTTON_X, config.faceTop, clusterCx, rowCy - spread, faceRadius)
            addFace(KeyEvent.KEYCODE_BUTTON_B, config.faceBottom, clusterCx, rowCy + spread, faceRadius)
            addFace(KeyEvent.KEYCODE_BUTTON_Y, config.faceLeft, clusterCx - spread, rowCy, faceRadius)
            addFace(KeyEvent.KEYCODE_BUTTON_A, config.faceRight, clusterCx + spread, rowCy, faceRadius)
        } else if (config.flatFaces) {
            val faceRadius = snap * 8.5f
            addFace(KeyEvent.KEYCODE_BUTTON_A, "A", width - margin - faceRadius, rowCy, faceRadius)
            addFace(
                KeyEvent.KEYCODE_BUTTON_B,
                "B",
                width - margin - faceRadius * 3.7f,
                rowCy,
                faceRadius,
            )
        } else {
            val faceRadius = snap * 8.5f
            addFace(KeyEvent.KEYCODE_BUTTON_A, "A", width - margin - faceRadius, rowCy - faceRadius * 0.9f, faceRadius)
            addFace(
                KeyEvent.KEYCODE_BUTTON_B,
                "B",
                width - margin - faceRadius * 3f - snap * 2f,
                rowCy + faceRadius * 0.9f,
                faceRadius,
            )
        }

        val pillW = snap * 13f
        val pillH = snap * 5.5f
        val pillGap = snap * 2f
        val pillY = height - margin - pillH
        val select = GlassButton(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT", GlassShape.PILL, textScale = 0.75f)
        select.bounds.set(width * 0.5f - pillGap * 0.5f - pillW, pillY, width * 0.5f - pillGap * 0.5f, pillY + pillH)
        buttons += select
        val start = GlassButton(KeyEvent.KEYCODE_BUTTON_START, "START", GlassShape.PILL, textScale = 0.75f)
        start.bounds.set(width * 0.5f + pillGap * 0.5f, pillY, width * 0.5f + pillGap * 0.5f + pillW, pillY + pillH)
        buttons += start

        val menuW = snap * 13f
        menuButton.bounds.set(
            width * 0.5f - menuW * 0.5f,
            zoneTop + snap * 1.5f,
            width * 0.5f + menuW * 0.5f,
            zoneTop + snap * 1.5f + pillH,
        )
    }

    private fun layoutPortraitN64(
        width: Float,
        height: Float,
    ) {
        snap = width / 100f
        strokeWidth = max(2f, snap * 0.4f)
        val zoneTop = portraitZoneTop(width, height)
        val margin = snap * 5f
        val trigW = snap * 24f
        val trigH = snap * 8f
        val trigGap = snap * 1.5f

        val lb = GlassButton(KeyEvent.KEYCODE_BUTTON_L1, "L", GlassShape.TRIGGER_LT, textScale = 1.3f)
        lb.bounds.set(margin, zoneTop + snap * 2f, margin + trigW, zoneTop + snap * 2f + trigH)
        buttons += lb
        val rb = GlassButton(KeyEvent.KEYCODE_BUTTON_R1, "R", GlassShape.TRIGGER_RT, textScale = 1.3f)
        rb.bounds.set(width - margin - trigW, zoneTop + snap * 2f, width - margin, zoneTop + snap * 2f + trigH)
        buttons += rb
        val z = GlassButton(KeyEvent.KEYCODE_BUTTON_L2, config.leftTriggerLabel, GlassShape.TRIGGER_RB, textScale = 1.3f)
        z.bounds.set(
            width - margin - trigW,
            zoneTop + snap * 2f + trigH + trigGap,
            width - margin,
            zoneTop + snap * 2f + trigH * 2 + trigGap,
        )
        buttons += z

        val pillW = snap * 13f
        val pillH = snap * 5.5f
        val pillGap = snap * 2f
        val pillY = height - margin - pillH
        val select = GlassButton(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT", GlassShape.PILL, textScale = 0.75f)
        select.bounds.set(width * 0.5f - pillGap * 0.5f - pillW, pillY, width * 0.5f - pillGap * 0.5f, pillY + pillH)
        buttons += select
        val start = GlassButton(KeyEvent.KEYCODE_BUTTON_START, "START", GlassShape.PILL, textScale = 0.75f)
        start.bounds.set(width * 0.5f + pillGap * 0.5f, pillY, width * 0.5f + pillGap * 0.5f + pillW, pillY + pillH)
        buttons += start

        val menuW = snap * 13f
        menuButton.bounds.set(
            width * 0.5f - menuW * 0.5f,
            zoneTop + snap * 1.5f,
            width * 0.5f + menuW * 0.5f,
            zoneTop + snap * 1.5f + pillH,
        )

        stickRadius = snap * 12f
        stickCx = margin + stickRadius
        stickCy = pillY - snap * 2f - stickRadius
        dpadRadius = snap * 10f
        dpadCx = stickCx
        dpadCy = stickCy - stickRadius - snap * 2f - dpadRadius

        val faceRadius = snap * 7.5f
        val clusterCx = width - margin - faceRadius - snap * 9f
        val aCy = stickCy
        val aCx = clusterCx + faceRadius * 0.9f
        val bCx = clusterCx - faceRadius * 0.9f
        val bCy = aCy - faceRadius * 1.8f
        val bButton = GlassButton(KeyEvent.KEYCODE_BUTTON_Y, "B", GlassShape.CIRCLE)
        bButton.bounds.set(bCx - faceRadius, bCy - faceRadius, bCx + faceRadius, bCy + faceRadius)
        buttons += bButton
        val aButton = GlassButton(KeyEvent.KEYCODE_BUTTON_B, "A", GlassShape.CIRCLE)
        aButton.bounds.set(aCx - faceRadius, aCy - faceRadius, aCx + faceRadius, aCy + faceRadius)
        buttons += aButton

        val cRadius = snap * 4.5f
        val cSpread = snap * 6.5f
        val cCx = clusterCx + snap * 1f
        val shouldersBottom = zoneTop + snap * 2f + trigH * 2 + trigGap
        val facesTop = bCy - faceRadius
        val cCy = (shouldersBottom + facesTop) * 0.5f
        fun addC(
            dx: Float,
            dy: Float,
            glyph: String,
            x: Float,
            y: Float,
        ) {
            val c = CButton(dx, dy, glyph)
            c.bounds.set(x - cRadius, y - cRadius, x + cRadius, y + cRadius)
            cButtons += c
        }
        addC(0f, -1f, "▲", cCx, cCy - cSpread)
        addC(0f, 1f, "▼", cCx, cCy + cSpread)
        addC(-1f, 0f, "◀", cCx - cSpread, cCy)
        addC(1f, 0f, "▶", cCx + cSpread, cCy)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gameArea == null && !editMode) return

        val alpha = (customColors.opacity * 255).toInt().coerceIn(0, 255)
        canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)

        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        drawShellBackground(canvas)
        if (editMode) canvas.drawColor(0x2E000000)
        drawClusterPlate(canvas)
        drawFacePlate(canvas)
        if (dpadVisible) drawDpad(canvas)
        val adaptive = adaptiveSticks && !editMode
        if ((config.hasStick || config.hasDualSticks) && stickVisible && (!adaptive || stickActive)) {
            drawStick(canvas, if (adaptive) stickOriginX else stickCx, if (adaptive) stickOriginY else stickCy)
        }
        if (config.hasDualSticks && stick2Visible && (!adaptive || stick2Active)) {
            drawStick2(canvas, adaptive)
        }
        buttons.forEach { drawThemedButton(canvas, it, pressedButtons.contains(it.keyCode)) }
        cButtons.forEach { drawCButton(canvas, it) }
        drawThemedButton(canvas, menuButton, menuLatched)
        if (editMode) drawEditChrome(canvas)

        canvas.restore()
    }

    private fun shadowTint(alpha: Int): Int {
        val c = customColors.shadow ?: return Color.argb(alpha, 0, 0, 0)
        return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun luminance(color: Int): Float =
        (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f

    private fun drawShellBackground(canvas: Canvas) {
        if (!shellBackground) return
        val area = gameArea ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = customColors.body ?: theme.body
        if (area.top > 0f) canvas.drawRect(0f, 0f, w, area.top, paint)
        if (area.bottom < h) canvas.drawRect(0f, area.bottom, w, h, paint)
        if (area.left > 0f) canvas.drawRect(0f, area.top, area.left, area.bottom, paint)
        if (area.right < w) canvas.drawRect(area.right, area.top, w, area.bottom, paint)
    }

    private fun drawFacePlate(canvas: Canvas) {
        if (theme.backplate == 0) return
        val faces = buttons.filter { it.shape == GlassShape.CIRCLE }
        if (faces.isEmpty()) return
        val pad = faces.first().bounds.width() * 0.28f
        val left = faces.minOf { it.bounds.left } - pad
        val top = faces.minOf { it.bounds.top } - pad
        val right = faces.maxOf { it.bounds.right } + pad
        val bottom = faces.maxOf { it.bounds.bottom } + pad
        if (right - left > faces.first().bounds.width() * 3.6f ||
            bottom - top > faces.first().bounds.height() * 3.6f
        ) {
            return
        }
        val corner = (bottom - top) * 0.24f
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = theme.backplate
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = darken(theme.backplate, 0.16f)
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, paint)
    }

    private fun drawClusterPlate(canvas: Canvas) {
        if (theme.clusterPlate == 0 || !config.hasXY) return
        val faces = buttons.filter { it.shape == GlassShape.CIRCLE }
        if (faces.size < 3) return
        val cx = faces.map { it.bounds.centerX() }.average().toFloat()
        val cy = faces.map { it.bounds.centerY() }.average().toFloat()
        val reach =
            faces.maxOf {
                hypot(it.bounds.centerX() - cx, it.bounds.centerY() - cy) + it.bounds.width() * 0.5f
            }
        if (reach > faces.first().bounds.width() * 2.2f) return
        val radius = reach + snap * 1.4f
        paint.shader =
            RadialGradient(
                cx,
                cy,
                radius,
                intArrayOf(theme.clusterPlate, theme.clusterPlate, darken(theme.clusterPlate, 0.12f)),
                floatArrayOf(0f, 0.8f, 1f),
                Shader.TileMode.CLAMP,
            )
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = darken(theme.clusterPlate, 0.22f)
        canvas.drawCircle(cx, cy, radius, paint)
    }

    private fun buttonFill(button: GlassButton): Int =
        customColors.button
            ?: theme.buttonColors[button.label]
            ?: if (button.shape == GlassShape.CIRCLE) theme.button else theme.pill

    private fun buttonTextColor(button: GlassButton): Int =
        customColors.text
            ?: theme.buttonTextColors[button.label]
            ?: if (button.shape == GlassShape.CIRCLE) theme.buttonText else theme.pillText

    private fun drawThemedButton(
        canvas: Canvas,
        button: GlassButton,
        pressed: Boolean,
    ) {
        val b = button.bounds
        val color = buttonFill(button)
        if (button.shape == GlassShape.CIRCLE) {
            drawRoundButton(
                canvas,
                b.centerX(),
                b.centerY(),
                b.width() * 0.5f,
                color,
                buttonTextColor(button),
                button.label,
                pressed,
                if (button.label.length > 1) 0.62f else 0.92f * button.textScale,
            )
            return
        }
        val depth = b.height() * 0.08f
        val dy = if (pressed) depth * 0.7f else 0f
        buildShapePath(button)
        path.offset(0f, depth)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = shadowTint(if (pressed) 40 else 70)
        canvas.drawPath(path, paint)
        buildShapePath(button)
        path.offset(0f, dy)
        paint.alpha = 255
        paint.shader =
            LinearGradient(
                b.left,
                b.top + dy,
                b.left,
                b.bottom + dy,
                intArrayOf(
                    lighten(color, if (pressed) 0.06f else 0.24f),
                    color,
                    darken(color, if (pressed) 0.24f else 0.14f),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = darken(color, 0.38f)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.color = buttonTextColor(button)
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.textSize = b.height() * (if (button.shape == GlassShape.PILL) 0.44f else 0.5f) * button.textScale / 0.75f * 0.75f
        val maxTextWidth = b.width() - b.height() * 0.5f
        if (button.label.isNotEmpty() && paint.measureText(button.label) > maxTextWidth) {
            paint.textSize = paint.textSize * maxTextWidth / paint.measureText(button.label)
        }
        val textY = b.centerY() + dy - (paint.descent() + paint.ascent()) * 0.5f
        canvas.drawText(button.label, b.centerX(), textY, paint)
        paint.isFakeBoldText = false
    }

    private fun drawRoundButton(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        color: Int,
        textColor: Int,
        label: String,
        pressed: Boolean,
        textScale: Float,
    ) {
        val depth = radius * 0.12f
        val dy = if (pressed) depth * 0.7f else 0f
        val bodyCy = cy + dy

        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader =
            RadialGradient(
                cx,
                cy + depth,
                radius * 1.18f,
                shadowTint(if (pressed) 55 else 95),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        canvas.drawCircle(cx, cy + depth, radius * 1.18f, paint)

        paint.shader =
            RadialGradient(
                cx - radius * 0.35f,
                bodyCy - radius * 0.45f,
                radius * 1.9f,
                intArrayOf(
                    lighten(color, if (pressed) 0.08f else 0.3f),
                    color,
                    darken(color, if (pressed) 0.28f else 0.16f),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        canvas.drawCircle(cx, bodyCy, radius, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = darken(color, 0.4f)
        canvas.drawCircle(cx, bodyCy, radius - strokeWidth * 0.5f, paint)

        if (!pressed) {
            paint.style = Paint.Style.FILL
            paint.shader =
                RadialGradient(
                    cx - radius * 0.34f,
                    bodyCy - radius * 0.4f,
                    radius * 0.62f,
                    Color.argb(70, 255, 255, 255),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            canvas.drawCircle(cx - radius * 0.34f, bodyCy - radius * 0.4f, radius * 0.62f, paint)
            paint.shader = null
        }

        if (label.isNotEmpty()) {
            paint.style = Paint.Style.FILL
            paint.color = textColor
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            paint.textSize = radius * textScale * 1.05f
            val maxTextWidth = radius * (if (label.length == 1) 2.4f else 1.7f)
            if (paint.measureText(label) > maxTextWidth) {
                paint.textSize = paint.textSize * maxTextWidth / paint.measureText(label)
            }
            val textY = bodyCy - (paint.descent() + paint.ascent()) * 0.5f
            canvas.drawText(label, cx, textY, paint)
            paint.isFakeBoldText = false
        }
    }

    private fun drawCButton(
        canvas: Canvas,
        button: CButton,
    ) {
        val b = button.bounds
        val pressed =
            (button.dx != 0f && cStickX == button.dx) || (button.dy != 0f && cStickY == button.dy)
        drawRoundButton(
            canvas,
            b.centerX(),
            b.centerY(),
            b.width() * 0.5f,
            customColors.button ?: theme.cButton,
            customColors.text ?: theme.cText,
            button.glyph,
            pressed,
            0.8f,
        )
    }

    private fun buildShapePath(button: GlassButton) {
        val b = button.bounds
        when (button.shape) {
            GlassShape.CIRCLE -> {
                path.reset()
                path.addCircle(b.centerX(), b.centerY(), b.width() * 0.5f, Path.Direction.CW)
            }
            GlassShape.PILL -> {
                path.reset()
                val r = b.height() * 0.5f
                path.addRoundRect(b.left, b.top, b.right, b.bottom, r, r, Path.Direction.CW)
            }
            GlassShape.TRIGGER_LT ->
                GameHubLayout.buildTriggerPath(path, GameHubLayout.RenderShape.TRIGGER_LT, b.left, b.top, b.right, b.bottom)
            GlassShape.TRIGGER_LB ->
                GameHubLayout.buildTriggerPath(path, GameHubLayout.RenderShape.TRIGGER_LB, b.left, b.top, b.right, b.bottom)
            GlassShape.TRIGGER_RT ->
                GameHubLayout.buildTriggerPath(path, GameHubLayout.RenderShape.TRIGGER_RT, b.left, b.top, b.right, b.bottom)
            GlassShape.TRIGGER_RB ->
                GameHubLayout.buildTriggerPath(path, GameHubLayout.RenderShape.TRIGGER_RB, b.left, b.top, b.right, b.bottom)
        }
    }

    private fun drawDpad(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        val color = customColors.button ?: theme.dpad
        val arm = dpadRadius * 0.36f
        val corner = arm * 0.5f
        val cx = dpadCx
        val cy = dpadCy
        val r = dpadRadius
        val depth = r * 0.05f
        val pressedUp = dpadY < -0.1f
        val pressedDown = dpadY > 0.1f
        val pressedLeft = dpadX < -0.1f
        val pressedRight = dpadX > 0.1f

        path.reset()
        path.addRoundRect(cx - r, cy - arm + depth, cx + r, cy + arm + depth, corner, corner, Path.Direction.CW)
        path.addRoundRect(cx - arm, cy - r + depth, cx + arm, cy + r + depth, corner, corner, Path.Direction.CW)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = shadowTint(80)
        canvas.drawPath(path, paint)

        path.reset()
        path.addRoundRect(cx - r, cy - arm, cx + r, cy + arm, corner, corner, Path.Direction.CW)
        path.addRoundRect(cx - arm, cy - r, cx + arm, cy + r, corner, corner, Path.Direction.CW)
        paint.alpha = 255
        paint.shader =
            LinearGradient(
                cx,
                cy - r,
                cx,
                cy + r,
                intArrayOf(lighten(color, 0.2f), color, darken(color, 0.2f)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        canvas.drawPath(path, paint)
        paint.shader = null

        fun pressArm(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(90, 0, 0, 0)
            canvas.drawRoundRect(left, top, right, bottom, corner, corner, paint)
        }
        if (pressedUp) pressArm(cx - arm, cy - r, cx + arm, cy - arm * 0.4f)
        if (pressedDown) pressArm(cx - arm, cy + arm * 0.4f, cx + arm, cy + r)
        if (pressedLeft) pressArm(cx - r, cy - arm, cx - arm * 0.4f, cy + arm)
        if (pressedRight) pressArm(cx + arm * 0.4f, cy - arm, cx + r, cy + arm)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = darken(color, 0.45f)
        path.reset()
        path.addRoundRect(cx - r, cy - arm, cx + r, cy + arm, corner, corner, Path.Direction.CW)
        val vertical = Path()
        vertical.addRoundRect(cx - arm, cy - r, cx + arm, cy + r, corner, corner, Path.Direction.CW)
        path.op(vertical, Path.Op.UNION)
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.FILL
        paint.shader =
            RadialGradient(
                cx,
                cy,
                arm * 0.85f,
                darken(color, 0.3f),
                color,
                Shader.TileMode.CLAMP,
            )
        canvas.drawCircle(cx, cy, arm * 0.85f, paint)
        paint.shader = null

        val tip = arm * 0.5f
        val arrowBase =
            customColors.text
                ?: if (luminance(color) < 0.55f) 0xFFF3F4F7.toInt() else 0xFF17181C.toInt()
        fun arrowPath(
            tx: Float,
            ty: Float,
            dxu: Float,
            dyu: Float,
        ) {
            path.reset()
            path.moveTo(tx + dxu * tip, ty + dyu * tip)
            path.lineTo(tx - dyu * tip * 0.8f - dxu * tip * 0.5f, ty + dxu * tip * 0.8f - dyu * tip * 0.5f)
            path.lineTo(tx + dyu * tip * 0.8f - dxu * tip * 0.5f, ty - dxu * tip * 0.8f - dyu * tip * 0.5f)
            path.close()
        }
        fun arrow(
            tx: Float,
            ty: Float,
            dxu: Float,
            dyu: Float,
        ) {
            arrowPath(tx, ty + tip * 0.14f, dxu, dyu)
            paint.color = shadowTint(120)
            canvas.drawPath(path, paint)
            arrowPath(tx, ty, dxu, dyu)
            paint.color = arrowBase
            canvas.drawPath(path, paint)
        }
        val arrowDist = r * 0.66f
        arrow(cx, cy - arrowDist, 0f, -1f)
        arrow(cx, cy + arrowDist, 0f, 1f)
        arrow(cx - arrowDist, cy, -1f, 0f)
        arrow(cx + arrowDist, cy, 1f, 0f)
    }

    private fun drawStick2(
        canvas: Canvas,
        adaptive: Boolean = false,
    ) {
        val ocx = stickCx
        val ocy = stickCy
        val orad = stickRadius
        val ox = stickX
        val oy = stickY
        val op = stickPointerId
        stickCx = stick2Cx
        stickCy = stick2Cy
        stickRadius = stick2Radius
        stickX = stick2X
        stickY = stick2Y
        stickPointerId = stick2PointerId
        if (adaptive) {
            drawStick(canvas, stick2OriginX, stick2OriginY)
        } else {
            drawStick(canvas)
        }
        stickCx = ocx
        stickCy = ocy
        stickRadius = orad
        stickX = ox
        stickY = oy
        stickPointerId = op
    }

    private fun drawPsStick(
        canvas: Canvas,
        cx: Float = stickCx,
        cy: Float = stickCy,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val engaged = stickPointerId != -1
        val wellColor = darken(customColors.body ?: theme.body, 0.16f)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader =
            RadialGradient(
                cx,
                cy,
                stickRadius,
                intArrayOf(darken(wellColor, 0.32f), wellColor, lighten(wellColor, 0.1f)),
                floatArrayOf(0f, 0.78f, 1f),
                Shader.TileMode.CLAMP,
            )
        canvas.drawCircle(cx, cy, stickRadius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = darken(wellColor, 0.4f)
        canvas.drawCircle(cx, cy, stickRadius, paint)

        val thumbX = cx + stickX * stickRadius * 0.5f
        val thumbY = cy + stickY * stickRadius * 0.5f
        val thumbRadius = stickRadius * 0.6f
        val capColor = customColors.button ?: 0xFF33333A.toInt()
        paint.style = Paint.Style.FILL
        paint.shader =
            RadialGradient(
                thumbX,
                thumbY + thumbRadius * 0.2f,
                thumbRadius * 1.3f,
                shadowTint(100),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        canvas.drawCircle(thumbX, thumbY + thumbRadius * 0.18f, thumbRadius * 1.3f, paint)
        paint.alpha = 255
        paint.shader =
            RadialGradient(
                thumbX - thumbRadius * 0.28f,
                thumbY - thumbRadius * 0.32f,
                thumbRadius * 1.9f,
                intArrayOf(
                    lighten(capColor, if (engaged) 0.12f else 0.28f),
                    capColor,
                    darken(capColor, 0.3f),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        canvas.drawCircle(thumbX, thumbY, thumbRadius, paint)
        paint.shader =
            RadialGradient(
                thumbX,
                thumbY + thumbRadius * 0.08f,
                thumbRadius * 0.72f,
                intArrayOf(
                    darken(capColor, if (engaged) 0.34f else 0.24f),
                    darken(capColor, 0.12f),
                    lighten(capColor, 0.18f),
                ),
                floatArrayOf(0f, 0.75f, 1f),
                Shader.TileMode.CLAMP,
            )
        canvas.drawCircle(thumbX, thumbY, thumbRadius * 0.72f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = darken(capColor, 0.42f)
        canvas.drawCircle(thumbX, thumbY, thumbRadius, paint)
        paint.strokeWidth = strokeWidth * 0.7f
        paint.color = lighten(capColor, 0.08f)
        canvas.drawCircle(thumbX, thumbY, thumbRadius * 0.72f, paint)
    }

    private fun drawStick(
        canvas: Canvas,
        cx: Float = stickCx,
        cy: Float = stickCy,
    ) {
        if (config.hasDualSticks) {
            drawPsStick(canvas, cx, cy)
            return
        }
        val engaged = stickPointerId != -1
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val wellColor = darken(customColors.button ?: theme.dpad, 0.1f)
        paint.shader = null
        paint.style = Paint.Style.FILL

        path.reset()
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45 - 22.5).toDouble())
            val px = cx + stickRadius * Math.cos(angle).toFloat()
            val py = cy + stickRadius * Math.sin(angle).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        paint.shader =
            RadialGradient(
                cx,
                cy,
                stickRadius,
                intArrayOf(darken(wellColor, 0.35f), wellColor, lighten(wellColor, 0.08f)),
                floatArrayOf(0f, 0.75f, 1f),
                Shader.TileMode.CLAMP,
            )
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = darken(wellColor, 0.4f)
        canvas.drawPath(path, paint)

        val thumbX = cx + stickX * stickRadius * 0.5f
        val thumbY = cy + stickY * stickRadius * 0.5f
        val thumbRadius = stickRadius * 0.5f
        val capColor = customColors.button ?: theme.stickCap
        paint.style = Paint.Style.FILL
        paint.shader =
            RadialGradient(
                thumbX,
                thumbY + thumbRadius * 0.18f,
                thumbRadius * 1.25f,
                shadowTint(90),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        canvas.drawCircle(thumbX, thumbY + thumbRadius * 0.18f, thumbRadius * 1.25f, paint)
        paint.shader =
            RadialGradient(
                thumbX - thumbRadius * 0.3f,
                thumbY - thumbRadius * 0.35f,
                thumbRadius * 1.8f,
                intArrayOf(
                    lighten(capColor, if (engaged) 0.1f else 0.3f),
                    capColor,
                    darken(capColor, 0.22f),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        canvas.drawCircle(thumbX, thumbY, thumbRadius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = darken(capColor, 0.35f)
        canvas.drawCircle(thumbX, thumbY, thumbRadius - strokeWidth * 0.5f, paint)
        canvas.drawCircle(thumbX, thumbY, thumbRadius * 0.55f, paint)
    }

    private fun layoutEditChrome(
        width: Float,
        height: Float,
    ) {
        toolbarPills.clear()
        trayPills.clear()
        val u = editUnit
        val pillH = u * 3.4f
        val gap = u * 0.9f
        val textPaint = paint
        textPaint.textSize = pillH * 0.42f
        textPaint.isFakeBoldText = true
        val actions =
            listOf(
                "done" to "DONE",
                "reset" to "RESET",
                "minus" to "−",
                "plus" to "+",
                "hide" to "HIDE",
            )
        val widths =
            actions.map { (_, label) ->
                max(textPaint.measureText(label) + pillH * 1.1f, pillH * 1.4f)
            }
        textPaint.isFakeBoldText = false
        val totalW = widths.sum() + gap * (actions.size - 1)
        var x = (width - totalW) * 0.5f
        val y = u * 1.2f
        actions.forEachIndexed { index, (action, label) ->
            toolbarPills += Triple(action, label, RectF(x, y, x + widths[index], y + pillH))
            x += widths[index] + gap
        }
        if (hiddenControls.isNotEmpty()) {
            textPaint.textSize = pillH * 0.38f
            val trayY = y + pillH + gap
            val trayWidths =
                hiddenControls.map { (_, label) ->
                    max(textPaint.measureText("+ $label") + pillH * 1.0f, pillH * 1.3f)
                }
            var trayX = (width - trayWidths.sum() - gap * (hiddenControls.size - 1)) * 0.5f
            hiddenControls.forEachIndexed { index, (id, label) ->
                trayPills += Triple(id, "+ $label", RectF(trayX, trayY, trayX + trayWidths[index], trayY + pillH))
                trayX += trayWidths[index] + gap
            }
        }
    }

    private fun drawChromePill(
        canvas: Canvas,
        rect: RectF,
        label: String,
        accent: Boolean,
        enabled: Boolean = true,
    ) {
        val r = rect.height() * 0.5f
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = if (accent) 0xF22196F3.toInt() else 0xF21C222C.toInt()
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = if (accent) 0xFF64B5F6.toInt() else 0xFF3A4354.toInt()
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (enabled) 0xFFF2F5FA.toInt() else 0x66F2F5FA
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.textSize = rect.height() * (if (label.startsWith("+ ")) 0.38f else 0.42f)
        val textY = rect.centerY() - (paint.descent() + paint.ascent()) * 0.5f
        canvas.drawText(label, rect.centerX(), textY, paint)
        paint.isFakeBoldText = false
    }

    private fun drawEditChrome(canvas: Canvas) {
        val u = editUnit
        selectedId?.let { id ->
            controlRect(id)?.let { rect ->
                val pad = u * 0.7f
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(2f, u * 0.22f)
                paint.color = 0xFF29B6F6.toInt()
                paint.pathEffect = DashPathEffect(floatArrayOf(u * 0.9f, u * 0.6f), 0f)
                canvas.drawRoundRect(
                    rect.left - pad,
                    rect.top - pad,
                    rect.right + pad,
                    rect.bottom + pad,
                    u,
                    u,
                    paint,
                )
                paint.pathEffect = null
            }
        }
        val hideEnabled = selectedId != null && selectedId != "menu"
        val sizeEnabled = selectedId != null
        toolbarPills.forEach { (action, label, rect) ->
            val enabled =
                when (action) {
                    "hide" -> hideEnabled
                    "minus", "plus" -> sizeEnabled
                    else -> true
                }
            drawChromePill(canvas, rect, label, accent = action == "done", enabled = enabled)
        }
        trayPills.forEach { (_, label, rect) ->
            drawChromePill(canvas, rect, label, accent = false)
        }
        val hintY = (toolbarPills.firstOrNull()?.third?.bottom ?: 0f) +
            (if (trayPills.isEmpty()) u * 2.2f else u * 6.2f)
        paint.style = Paint.Style.FILL
        paint.color = 0xCCFFFFFF.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = u * 1.4f
        canvas.drawText(
            if (selectedId == null) "Tap a control to select, drag to move" else "Drag to move · +/− to resize",
            width * 0.5f,
            hintY,
            paint,
        )
    }

    private fun controlAt(
        x: Float,
        y: Float,
    ): String? {
        val u = editUnit
        var best: String? = null
        var bestArea = Float.MAX_VALUE
        allControlIds().forEach { id ->
            val rect = controlRect(id) ?: return@forEach
            if (x >= rect.left - u && x <= rect.right + u && y >= rect.top - u && y <= rect.bottom + u) {
                val area = rect.width() * rect.height()
                if (area < bestArea) {
                    bestArea = area
                    best = id
                }
            }
        }
        return best
    }

    private fun editTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                toolbarPills.firstOrNull { it.third.contains(x, y) }?.let { (action, _, _) ->
                    hapticTick()
                    cancelDrag()
                    when (action) {
                        "done" -> finishEdit()
                        "reset" -> resetLayout()
                        "minus" -> selectedId?.let { scaleControl(it, -0.1f) }
                        "plus" -> selectedId?.let { scaleControl(it, 0.1f) }
                        "hide" ->
                            selectedId?.takeIf { it != "menu" }?.let {
                                setControlVisible(it, false)
                                selectedId = null
                            }
                    }
                    return
                }
                trayPills.firstOrNull { it.third.contains(x, y) }?.let { (id, _, _) ->
                    hapticTick()
                    cancelDrag()
                    setControlVisible(id, true)
                    selectedId = id
                    return
                }
                if (dragId == null) {
                    val hit = controlAt(x, y)
                    selectedId = hit
                    if (hit != null) {
                        val rect = controlRect(hit)
                        dragId = hit
                        dragPointerId = event.getPointerId(index)
                        dragActive = false
                        dragDownX = x
                        dragDownY = y
                        dragOffsetX = (rect?.centerX() ?: x) - x
                        dragOffsetY = (rect?.centerY() ?: y) - y
                        hapticTick()
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val id = dragId ?: return
                val pointerIndex = event.findPointerIndex(dragPointerId)
                if (pointerIndex < 0) return
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                if (!dragActive && hypot(x - dragDownX, y - dragDownY) < editUnit * 0.6f) return
                dragActive = true
                moveControl(id, x + dragOffsetX, y + dragOffsetY)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val ended =
                    event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_CANCEL ||
                        event.getPointerId(event.actionIndex) == dragPointerId
                if (ended && dragId != null) {
                    val moved = dragActive
                    cancelDrag()
                    if (moved) saveOverrides()
                }
            }
        }
    }

    fun releaseAll() {
        for (keyCode in pressedButtons) listener.onButton(keyCode, false)
        pressedButtons.clear()
        pointerButton.clear()
        pointerCButton.clear()
        dpadPointerId = -1
        menuPointerId = -1
        if (dpadX != 0f || dpadY != 0f) {
            dpadX = 0f
            dpadY = 0f
            listener.onDpad(0f, 0f)
        }
        if (stickPointerId != -1 || stickX != 0f || stickY != 0f) {
            stickPointerId = -1
            stickX = 0f
            stickY = 0f
            listener.onStick(0f, 0f)
        }
        stickActive = false
        if (stickL3Held) {
            stickL3Held = false
            listener.onButton(KeyEvent.KEYCODE_BUTTON_THUMBL, false)
        }
        if (cStickX != 0f || cStickY != 0f) {
            cStickX = 0f
            cStickY = 0f
            listener.onRightStick(0f, 0f)
        }
        if (stick2PointerId != -1 || stick2X != 0f || stick2Y != 0f) {
            stick2PointerId = -1
            stick2X = 0f
            stick2Y = 0f
            listener.onRightStick(0f, 0f)
        }
        stick2Active = false
        if (stickR3Held) {
            stickR3Held = false
            listener.onButton(KeyEvent.KEYCODE_BUTTON_THUMBR, false)
        }
        menuLatched = false
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            ->
                if (editMode) {
                    editTouch(event)
                } else if (swallowInput) {
                    if (event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_CANCEL
                    ) {
                        swallowInput = false
                    }
                } else {
                    recompute(event)
                }
            else -> return false
        }
        invalidate()
        return true
    }

    private fun hitButton(
        button: GlassButton,
        x: Float,
        y: Float,
    ): Boolean {
        val b = button.bounds
        val inflate = snap * 1.2f
        return if (button.shape == GlassShape.CIRCLE) {
            val r = b.width() * 0.5f + inflate
            hypot(x - b.centerX(), y - b.centerY()) <= r
        } else {
            x >= b.left - inflate && x <= b.right + inflate &&
                y >= b.top - inflate && y <= b.bottom + inflate
        }
    }

    private fun inStickHomeZone(
        x: Float,
        y: Float,
        rightHalf: Boolean,
    ): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false
        val top = h * 0.42f
        val midX = w * 0.5f
        val inHalf = if (rightHalf) x >= midX else x < midX
        return inHalf && y >= top
    }

    private fun hitsOtherControl(
        x: Float,
        y: Float,
    ): Boolean {
        val pad = DEAD_ZONE_PX
        fun inPadded(b: RectF): Boolean =
            x >= b.left - pad && x <= b.right + pad && y >= b.top - pad && y <= b.bottom + pad
        if (menuPointerId == -1 && inPadded(menuButton.bounds)) return true
        for (button in buttons) {
            if (inPadded(button.bounds)) return true
        }
        val face = buttons.filter { it.keyCode in FACE_KEYCODES }
        if (face.size >= 3) {
            val l = face.minOf { it.bounds.left } - pad
            val t = face.minOf { it.bounds.top } - pad
            val r = face.maxOf { it.bounds.right } + pad
            val b = face.maxOf { it.bounds.bottom } + pad
            if (x in l..r && y in t..b) return true
        }
        for (c in cButtons) {
            val reach = c.bounds.width() * 0.5f + snap * 1.2f + pad
            if (hypot(x - c.bounds.centerX(), y - c.bounds.centerY()) <= reach) return true
        }
        if (dpadVisible && hypot(x - dpadCx, y - dpadCy) <= dpadRadius * 1.4f + pad) return true
        return false
    }

    private fun recompute(event: MotionEvent) {
        val adaptive = adaptiveSticks
        val now = event.eventTime
        val released =
            event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        val liftedPointer =
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val downId =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                    event.getPointerId(event.actionIndex)
                else -> -1
            }

        val activeIds = HashSet<Int>()
        if (!released) {
            for (i in 0 until event.pointerCount) {
                if (i == liftedPointer) continue
                activeIds.add(event.getPointerId(i))
            }
        }
        pointerButton.keys.retainAll(activeIds)
        pointerCButton.keys.retainAll(activeIds)
        if (dpadPointerId != -1 && dpadPointerId !in activeIds) dpadPointerId = -1
        if (menuPointerId != -1 && menuPointerId !in activeIds) menuPointerId = -1

        val newPressed = HashSet<Int>()
        var newDpadX = 0f
        var newDpadY = 0f
        var newCX = 0f
        var newCY = 0f
        var menuTouched = false
        var stickSeen = false
        var newStickX = stickX
        var newStickY = stickY
        var stick2Seen = false
        var newStick2X = stick2X
        var newStick2Y = stick2Y

        if (!released) {
            for (i in 0 until event.pointerCount) {
                if (i == liftedPointer) continue
                val x = event.getX(i)
                val y = event.getY(i)
                val pointerId = event.getPointerId(i)
                val isNewDown = pointerId == downId

                if (config.hasDualSticks && stick2Visible) {
                    if (pointerId == stick2PointerId) {
                        stick2Seen = true
                        val ocx = if (stick2Active) stick2OriginX else stick2Cx
                        val ocy = if (stick2Active) stick2OriginY else stick2Cy
                        newStick2X = ((x - ocx) / stick2Radius).coerceIn(-1f, 1f)
                        newStick2Y = ((y - ocy) / stick2Radius).coerceIn(-1f, 1f)
                        stick2MaxMag = max(stick2MaxMag, hypot(newStick2X, newStick2Y))
                        continue
                    }
                    if (isNewDown && stick2PointerId == -1) {
                        var grabbed = false
                        if (adaptive) {
                            if (inStickHomeZone(x, y, rightHalf = true) && !hitsOtherControl(x, y)) {
                                stick2PointerId = pointerId
                                stick2Seen = true
                                stick2Active = true
                                stick2OriginX = x
                                stick2OriginY = y
                                hapticTick()
                                newStick2X = 0f
                                newStick2Y = 0f
                                grabbed = true
                            }
                        } else if (hypot(x - stick2Cx, y - stick2Cy) <= stick2Radius * 1.3f) {
                            stick2PointerId = pointerId
                            stick2Seen = true
                            hapticTick()
                            newStick2X = ((x - stick2Cx) / stick2Radius).coerceIn(-1f, 1f)
                            newStick2Y = ((y - stick2Cy) / stick2Radius).coerceIn(-1f, 1f)
                            grabbed = true
                        }
                        if (grabbed) {
                            stick2DownTime = now
                            stick2MaxMag = 0f
                            if (!showL3R3 && stick2LastTapTime != 0L && now - stick2LastTapTime <= DOUBLE_TAP_MS) {
                                stickR3Held = true
                                stick2LastTapTime = 0L
                                listener.onButton(KeyEvent.KEYCODE_BUTTON_THUMBR, true)
                                hapticTick()
                            }
                            continue
                        }
                    }
                }

                if ((config.hasStick || config.hasDualSticks) && stickVisible) {
                    if (pointerId == stickPointerId) {
                        stickSeen = true
                        val ocx = if (stickActive) stickOriginX else stickCx
                        val ocy = if (stickActive) stickOriginY else stickCy
                        newStickX = ((x - ocx) / stickRadius).coerceIn(-1f, 1f)
                        newStickY = ((y - ocy) / stickRadius).coerceIn(-1f, 1f)
                        stickMaxMag = max(stickMaxMag, hypot(newStickX, newStickY))
                        continue
                    }
                    if (isNewDown && stickPointerId == -1) {
                        var grabbed = false
                        if (adaptive) {
                            if (inStickHomeZone(x, y, rightHalf = false) && !hitsOtherControl(x, y)) {
                                stickPointerId = pointerId
                                stickSeen = true
                                stickActive = true
                                stickOriginX = x
                                stickOriginY = y
                                hapticTick()
                                newStickX = 0f
                                newStickY = 0f
                                grabbed = true
                            }
                        } else if (hypot(x - stickCx, y - stickCy) <= stickRadius * 1.3f) {
                            stickPointerId = pointerId
                            stickSeen = true
                            hapticTick()
                            newStickX = ((x - stickCx) / stickRadius).coerceIn(-1f, 1f)
                            newStickY = ((y - stickCy) / stickRadius).coerceIn(-1f, 1f)
                            grabbed = true
                        }
                        if (grabbed) {
                            stickDownTime = now
                            stickMaxMag = 0f
                            if (!showL3R3 && stickLastTapTime != 0L && now - stickLastTapTime <= DOUBLE_TAP_MS) {
                                stickL3Held = true
                                stickLastTapTime = 0L
                                listener.onButton(KeyEvent.KEYCODE_BUTTON_THUMBL, true)
                                hapticTick()
                            }
                            continue
                        }
                    }
                }

                if (pointerId == menuPointerId) {
                    menuTouched = true
                    continue
                }
                if (isNewDown && menuPointerId == -1 && hitButton(menuButton, x, y)) {
                    menuPointerId = pointerId
                    menuTouched = true
                    continue
                }

                val ownedC = pointerCButton[pointerId]
                if (ownedC != null) {
                    if (ownedC.dx != 0f) newCX = ownedC.dx
                    if (ownedC.dy != 0f) newCY = ownedC.dy
                    continue
                }
                if (isNewDown) {
                    var cHit = false
                    for (c in cButtons) {
                        val reach = c.bounds.width() * 0.5f + snap * 1.2f
                        if (hypot(x - c.bounds.centerX(), y - c.bounds.centerY()) <= reach) {
                            pointerCButton[pointerId] = c
                            if (c.dx != 0f) newCX = c.dx
                            if (c.dy != 0f) newCY = c.dy
                            cHit = true
                            break
                        }
                    }
                    if (cHit) continue
                }

                val ownedB = pointerButton[pointerId]
                if (ownedB != null) {
                    newPressed.add(ownedB)
                    continue
                }
                if (isNewDown) {
                    var buttonHit = false
                    for (button in buttons) {
                        if (hitButton(button, x, y)) {
                            pointerButton[pointerId] = button.keyCode
                            newPressed.add(button.keyCode)
                            buttonHit = true
                            break
                        }
                    }
                    if (buttonHit) continue
                }

                if (dpadVisible && (pointerId == dpadPointerId ||
                        (isNewDown && dpadPointerId == -1 &&
                            hypot(x - dpadCx, y - dpadCy) <= dpadRadius * 1.4f))
                ) {
                    dpadPointerId = pointerId
                    val dxToPad = x - dpadCx
                    val dyToPad = y - dpadCy
                    val dz = dpadRadius * 0.24f
                    var wantX = if (dxToPad > dz) 1f else if (dxToPad < -dz) -1f else 0f
                    var wantY = if (dyToPad > dz) 1f else if (dyToPad < -dz) -1f else 0f

                    if (wantX != 0f && wantY != 0f) {
                        val alongX = abs(dxToPad)
                        val alongY = abs(dyToPad)
                        val holdingX = dpadX != 0f && dpadY == 0f
                        val holdingY = dpadY != 0f && dpadX == 0f
                        val takeX =
                            when {
                                holdingX -> alongX * DPAD_AXIS_SWAP_BIAS >= alongY
                                holdingY -> alongX >= alongY * DPAD_AXIS_SWAP_BIAS
                                else -> alongX >= alongY
                            }
                        if (takeX) wantY = 0f else wantX = 0f
                    }
                    newDpadX = wantX
                    newDpadY = wantY
                }
            }
        }

        if (newCX != cStickX || newCY != cStickY) {
            if (newCX != 0f || newCY != 0f) hapticTick()
            cStickX = newCX
            cStickY = newCY
            listener.onRightStick(ix(cStickX, invertRX), ix(cStickY, invertRY))
        }

        if (!stickSeen && stickPointerId != -1) {
            stickPointerId = -1
            stickActive = false
            newStickX = 0f
            newStickY = 0f
            stickLastTapTime = if (now - stickDownTime < TAP_MAX_MS && stickMaxMag < TAP_MAX_MAG) now else 0L
            if (stickL3Held) {
                stickL3Held = false
                listener.onButton(KeyEvent.KEYCODE_BUTTON_THUMBL, false)
            }
        }
        if (newStickX != stickX || newStickY != stickY) {
            stickX = newStickX
            stickY = newStickY
            listener.onStick(ix(stickX, invertLX), ix(stickY, invertLY))
        }

        if (!stick2Seen && stick2PointerId != -1) {
            stick2PointerId = -1
            stick2Active = false
            newStick2X = 0f
            newStick2Y = 0f
            stick2LastTapTime = if (now - stick2DownTime < TAP_MAX_MS && stick2MaxMag < TAP_MAX_MAG) now else 0L
            if (stickR3Held) {
                stickR3Held = false
                listener.onButton(KeyEvent.KEYCODE_BUTTON_THUMBR, false)
            }
        }
        if (newStick2X != stick2X || newStick2Y != stick2Y) {
            stick2X = newStick2X
            stick2Y = newStick2Y
            listener.onRightStick(ix(stick2X, invertRX), ix(stick2Y, invertRY))
        }

        for (keyCode in pressedButtons) {
            if (!newPressed.contains(keyCode)) listener.onButton(keyCode, false)
        }
        for (keyCode in newPressed) {
            if (!pressedButtons.contains(keyCode)) {
                hapticTick()
                listener.onButton(keyCode, true)
            }
        }
        pressedButtons.clear()
        pressedButtons.addAll(newPressed)

        if (newDpadX != dpadX || newDpadY != dpadY) {
            if (newDpadX != 0f || newDpadY != 0f) hapticTick()
            dpadX = newDpadX
            dpadY = newDpadY
            listener.onDpad(dpadX, dpadY)
        }

        if (menuTouched && !menuLatched) {
            menuLatched = true
            hapticTick()
            listener.onMenu()
        } else if (!menuTouched) {
            menuLatched = false
        }
    }
}