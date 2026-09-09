package com.winlator.cmod.runtime.display

import android.app.Activity
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import com.winlator.cmod.shared.ui.focus.controllerMenuInput
import com.winlator.cmod.shared.ui.focus.controllerFocusBorder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Stable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.winlator.cmod.R
import com.winlator.cmod.shared.theme.SessionDrawerStyle
import com.winlator.cmod.shared.theme.WinNativeTheme
import com.winlator.cmod.shared.ui.dialog.WinNativeDialogButton
import com.winlator.cmod.shared.ui.dialog.WinNativeDialogShell
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav as SharedLocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry as SharedPaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem as sharedPaneNavItem
import com.winlator.cmod.shared.ui.outlinedSwitchColors
import com.winlator.cmod.shared.ui.widget.chasingBorder
import kotlin.math.roundToInt

private const val DrawerGradientLift = SessionDrawerStyle.GradientLift

internal val DrawerAccent = SessionDrawerStyle.Accent
private val DrawerActiveAccent = SessionDrawerStyle.ActiveAccent
private val DrawerFocusFill = SessionDrawerStyle.FocusFill
internal val DrawerTextPrimary = SessionDrawerStyle.TextPrimary
internal val DrawerTextSecondary = SessionDrawerStyle.TextSecondary
internal val DrawerOutline = SessionDrawerStyle.Outline
internal val DrawerBackground = SessionDrawerStyle.Background

internal val PaneSurfaceColor = SessionDrawerStyle.PaneSurface
private val PaneSurfacePressed = SessionDrawerStyle.PaneSurfacePressed

private val TopRailSurfaceColor = SessionDrawerStyle.TopRailSurface

private val TileResting = SessionDrawerStyle.TileResting
internal val TileExitResting = SessionDrawerStyle.TileExitResting
internal val TileExitPressed = SessionDrawerStyle.TileExitPressed
internal val PaneInnerResting = SessionDrawerStyle.PaneInnerResting
internal val PaneInnerPressed = SessionDrawerStyle.PaneInnerPressed
internal val RestingCardBorder = SessionDrawerStyle.RestingCardBorder
private val DisabledCardBorder = SessionDrawerStyle.DisabledCardBorder
internal val ActiveCardBorder = SessionDrawerStyle.ActiveCardBorder
private val BottomDividerColor = SessionDrawerStyle.Outline
internal val GlassExitTint = SessionDrawerStyle.GlassExitTint
internal val RecordRed = Color(0xFFE53935)

// Pane content scales down on short displays.
internal val LocalPaneScale = staticCompositionLocalOf { 1f }

private const val PANE_DIR_LEFT = 1
private const val PANE_DIR_RIGHT = 2
private const val PANE_DIR_UP = 3
private const val PANE_DIR_DOWN = 4
private const val PANE_DIR_ACTIVATE = 5
private const val PANE_DIR_SECONDARY = 6
private const val PANE_ROW_Y_THRESHOLD = 24f

private class PaneNavEntry(
    var x: Float,
    var y: Float,
    var onActivate: () -> Unit,
    var onAdjust: (Int) -> Unit,
    var onSecondary: () -> Unit,
)

@Stable
internal class PaneNavRegistry {
    private val items = mutableStateMapOf<Int, PaneNavEntry>()
    private var slotCounter = 0
    private var lastSignal = -1
    var controllerActive by mutableStateOf(false)
    var overlay by mutableStateOf<PaneNavRegistry?>(null)
    var overlayClose: (() -> Unit)? = null
    var activeRow by mutableStateOf(0)
        private set
    var activeCol by mutableStateOf(0)
        private set

    fun nextSlot(): Int = slotCounter++

    fun reset() {
        activeRow = 0
        activeCol = 0
    }

    fun reportCallbacks(slot: Int, onActivate: () -> Unit, onAdjust: (Int) -> Unit, onSecondary: () -> Unit) {
        val e = items[slot]
        if (e == null) {
            items[slot] = PaneNavEntry(0f, 0f, onActivate, onAdjust, onSecondary)
        } else {
            e.onActivate = onActivate
            e.onAdjust = onAdjust
            e.onSecondary = onSecondary
        }
    }

    fun reportPosition(slot: Int, x: Float, y: Float) {
        val e = items[slot] ?: return
        if (e.x != x || e.y != y) {
            items[slot] = PaneNavEntry(x, y, e.onActivate, e.onAdjust, e.onSecondary)
        }
    }

    fun unregister(slot: Int) { items.remove(slot) }

    val rows: List<List<Int>>
        get() {
            val sorted = items.entries.sortedWith(compareBy({ it.value.y }, { it.value.x }))
            val result = mutableListOf<MutableList<Int>>()
            var prevY = Float.NaN
            for (entry in sorted) {
                val y = entry.value.y
                if (result.isEmpty() || kotlin.math.abs(y - prevY) > PANE_ROW_Y_THRESHOLD) {
                    result.add(mutableListOf(entry.key))
                } else {
                    result.last().add(entry.key)
                }
                prevY = y
            }
            return result
        }

    fun isActive(slot: Int): Boolean {
        if (!controllerActive) return false
        val r = rows
        if (r.isEmpty()) return false
        val row = r[activeRow.coerceIn(0, r.size - 1)]
        return row[activeCol.coerceIn(0, row.size - 1)] == slot
    }

    fun processNav(signal: Int, dir: Int) {
        if (lastSignal == -1) {
            lastSignal = signal
            return
        }
        if (signal == lastSignal) return
        lastSignal = signal
        handleNav(dir)
    }

    private fun handleNav(dir: Int) {
        overlay?.let { ov ->
            ov.controllerActive = true
            ov.handleNav(dir)
            return
        }
        val r = rows
        if (r.isEmpty()) return
        var row = activeRow.coerceIn(0, r.size - 1)
        var col = activeCol.coerceIn(0, r[row].size - 1)
        when (dir) {
            PANE_DIR_UP -> if (row > 0) { row--; col = col.coerceAtMost(r[row].size - 1) }
            PANE_DIR_DOWN -> if (row < r.size - 1) { row++; col = col.coerceAtMost(r[row].size - 1) }
            PANE_DIR_LEFT ->
                if (r[row].size <= 1) items[r[row][0]]?.onAdjust?.invoke(-1) else if (col > 0) col--
            PANE_DIR_RIGHT ->
                if (r[row].size <= 1) items[r[row][0]]?.onAdjust?.invoke(1) else if (col < r[row].size - 1) col++
            PANE_DIR_ACTIVATE -> items[r[row][col]]?.onActivate?.invoke()
            PANE_DIR_SECONDARY -> items[r[row][col]]?.onSecondary?.invoke()
        }
        activeRow = row
        activeCol = col
    }
}

internal val LocalPaneNav = staticCompositionLocalOf<PaneNavRegistry?> { null }

internal fun Modifier.paneHighlight(highlighted: Boolean, cornerRadius: Dp): Modifier =
    drawWithContent {
        val cr = cornerRadius.toPx()
        if (highlighted) {
            drawRoundRect(color = DrawerAccent.copy(alpha = 0.20f), cornerRadius = CornerRadius(cr, cr))
        }
        drawContent()
        if (highlighted) {
            drawRoundRect(
                color = DrawerAccent,
                cornerRadius = CornerRadius(cr, cr),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.paneNavItem(
    cornerRadius: Dp = 10.dp,
    onActivate: () -> Unit = {},
    onAdjust: (Int) -> Unit = {},
    onSecondary: () -> Unit = {},
): Modifier {
    val nav = LocalPaneNav.current ?: return this
    val slot = remember { nav.nextSlot() }
    DisposableEffect(slot) { onDispose { nav.unregister(slot) } }
    SideEffect { nav.reportCallbacks(slot, onActivate, onAdjust, onSecondary) }
    val highlighted = nav.isActive(slot)

    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(highlighted) { if (highlighted) runCatching { bring.bringIntoView() } }

    return this
        .onGloballyPositioned {
            val p = it.positionInWindow()
            nav.reportPosition(slot, p.x, p.y)
        }
        .bringIntoViewRequester(bring)
        .paneHighlight(highlighted, cornerRadius)
}

@Composable
internal fun NavBooleanRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    val paneScale = LocalPaneScale.current
    Box(
        Modifier.fillMaxWidth().paneNavItem(
            cornerRadius = (12f * paneScale).dp,
            onActivate = { onCheckedChange(!checked) },
        ),
    ) {
        DrawerBooleanRow(title = title, checked = checked, onCheckedChange = onCheckedChange, subtitle = subtitle)
    }
}

@Composable
internal fun NavEnableRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val paneScale = LocalPaneScale.current
    Box(
        Modifier.fillMaxWidth().paneNavItem(
            cornerRadius = (12f * paneScale).dp,
            onActivate = { onCheckedChange(!checked) },
        ),
    ) {
        PaneEnableRow(title = title, checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun NavSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueClick: (() -> Unit)? = null,
    onValueChangeFinished: (() -> Unit)? = null,
    adjustStep: Float? = null,
) {
    val paneScale = LocalPaneScale.current
    Box(
        Modifier.fillMaxWidth().paneNavItem(
            cornerRadius = (12f * paneScale).dp,
            onActivate = { onValueClick?.invoke() },
            onAdjust = { dir ->
                val next =
                    if (adjustStep != null) {
                        val q = (value / adjustStep).toDouble()
                        val units = if (dir > 0) Math.floor(q + 1e-4) + 1 else Math.ceil(q - 1e-4) - 1
                        (units * adjustStep).toFloat()
                    } else {
                        val divisions = if (steps > 0) steps + 1 else 20
                        value + dir * (valueRange.endInclusive - valueRange.start) / divisions
                    }
                onValueChange(next.coerceIn(valueRange.start, valueRange.endInclusive))
                onValueChangeFinished?.invoke()
            },
        ),
    ) {
        DrawerSliderRow(
            label = label,
            valueText = valueText,
            value = value,
            valueRange = valueRange,
            steps = steps,
            onValueChange = onValueChange,
            onValueClick = onValueClick,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

private const val PaneScaleMin = SessionDrawerStyle.PaneScaleMin
internal const val ControlsPaneScaleMin = 0.62f
private const val PaneScaleReferenceHeightDp = SessionDrawerStyle.PaneScaleReferenceHeightDp
internal const val PendingTaskAffinityTimeoutMs = 2500L

internal fun computePaneScale(availableHeight: Dp, minScale: Float = PaneScaleMin): Float =
    (availableHeight.value / PaneScaleReferenceHeightDp).coerceIn(minScale, 1f)

internal enum class HUDMetricEditor(
    val minPercent: Int,
    val maxPercent: Int,
) {
    ALPHA(minPercent = 10, maxPercent = 100),
    SCALE(minPercent = 30, maxPercent = 200),
    BACKGROUND_ALPHA(minPercent = 10, maxPercent = 100),
}

internal enum class DrawerPane { INPUT_CONTROLS, HUD, FRAME_GEN, GYROSCOPE, SCREEN_EFFECTS, RESHADE, OUTPUT, TASK_MANAGER, LOGS, TOUCH }

internal const val LogsPaneMaxLines = 2000
internal const val LogsFlushIntervalMs = 200L

data class LogsPaneState(
    val lines: List<String> = emptyList(),
    val paused: Boolean = false,
)

data class TaskManagerProcess(
    val pid: Int,
    val name: String,
    val memoryFormatted: String,
    val affinityMask: Int,
    val isWow64: Boolean,
)

data class TaskManagerPaneState(
    val processes: List<TaskManagerProcess> = emptyList(),
    val cpuPercent: Int = 0,
    val cpuCoreCount: Int = 0,
    val cpuCorePercents: List<Int> = emptyList(),
    val memoryPercent: Int = 0,
    val memoryDetail: String = "",
)

internal data class PendingTaskAffinity(
    val affinityMask: Int,
    val requestedAtMillis: Long,
)

// Top-rail pane specs.
private data class RailPaneSpec(
    val pane: DrawerPane,
    val itemId: Int,
    val labelRes: Int,
    val iconOverride: ImageVector? = null,
)

private val RAIL_PANES =
    listOf(
        RailPaneSpec(
            pane = DrawerPane.INPUT_CONTROLS,
            itemId = R.id.main_menu_input_controls,
            labelRes = R.string.session_drawer_rail_label_input_controls,
            iconOverride = Icons.Outlined.SportsEsports,
        ),
        RailPaneSpec(
            pane = DrawerPane.HUD,
            itemId = R.id.main_menu_fps_monitor,
            labelRes = R.string.session_drawer_rail_label_hud,
        ),
        RailPaneSpec(
            pane = DrawerPane.FRAME_GEN,
            itemId = R.id.main_menu_frame_generation,
            labelRes = R.string.session_drawer_rail_label_frame_gen,
            iconOverride = Icons.Outlined.Speed,
        ),
        RailPaneSpec(
            pane = DrawerPane.GYROSCOPE,
            itemId = R.id.main_menu_gyroscope,
            labelRes = R.string.session_drawer_rail_label_gyro,
            iconOverride = Icons.Outlined.ScreenRotation,
        ),
        RailPaneSpec(
            pane = DrawerPane.SCREEN_EFFECTS,
            itemId = R.id.main_menu_screen_effects,
            labelRes = R.string.session_drawer_rail_label_effects,
        ),
        // Shown only when the host adds a main_menu_reshade item to state.items.
        RailPaneSpec(
            pane = DrawerPane.RESHADE,
            itemId = R.id.main_menu_reshade,
            labelRes = R.string.reshade_section_title,
            iconOverride = Icons.Outlined.AutoAwesome,
        ),
        // Shown only when the host adds a main_menu_output item to state.items.
        RailPaneSpec(
            pane = DrawerPane.OUTPUT,
            itemId = R.id.main_menu_output,
            labelRes = R.string.session_drawer_rail_label_output,
            iconOverride = Icons.Outlined.Monitor,
        ),
    )

private val RAIL_PANE_ITEM_IDS = RAIL_PANES.map { it.itemId }.toSet()
private val PINNED_BOTTOM_ITEM_IDS = setOf(R.id.main_menu_pause, R.id.main_menu_exit)

private val TopRailTileMinWidth = 60.dp
private val TopRailTileHorizontalPadding = 10.dp
private val TopRailTileTopPadding = 10.dp
private val TopRailTileBottomPadding = 7.dp
private val TopRailTileSpacing = 10.dp

private const val ActionCardColumns = 3
private val ActionCardMinHeight = 72.dp
private val ActionCardSpacing = 8.dp

private const val ActionCardRevealStaggerMs = 28
private const val ActionCardRevealDurationMs = 220

internal val FrameGenMultipliers = listOf(2, 3, 4)
internal val FrameGenTargetRates = listOf(60, 90, 120, 144, 165)
internal const val FrameGenFlowScaleMin = 25
internal const val FrameGenFlowScaleMax = 100

enum class DrawerSide { LEFT, RIGHT, BOTH }

data class XServerDrawerItem(
    val itemId: Int,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val side: DrawerSide = DrawerSide.LEFT,
)

/** Device-filtered options + persisted selections for the Record popup. Quality: 0=Perf,1=Balance,2=Quality. */
data class RecordUiConfig(
    val fpsOptions: List<Int> = emptyList(),
    val resolutionLabels: List<String> = emptyList(),
    val fpsIndex: Int = 0,
    val resolutionIndex: Int = 0,
    val quality: Int = 2,
    val recordUI: Boolean = false,
)

data class XServerDrawerState(
    val items: List<XServerDrawerItem>,
    val paneSides: Map<Int, DrawerSide> = emptyMap(),
    val hudTransparency: Float = 1.0f,
    val hudBackgroundAlphaEnabled: Boolean = false,
    val hudBackgroundTransparency: Float = 1.0f,
    val hudScale: Float = 1.0f,
    val hudElements: BooleanArray = booleanArrayOf(true, true, true, true, true, true, true, true, false),
    val dualSeriesBatteryEnabled: Boolean = false,
    val frametimeNumericEnabled: Boolean = false,
    val hudCardExpanded: Boolean = false,
    val gyroscopeEnabled: Boolean = false,
    val gyroscopeModeIndex: Int = 0,
    val gyroOrientationEnabled: Boolean = false,
    val gyroscopeActivatorLabel: String = "",
    val rightStickGyroEnabled: Boolean = false,
    val gyroMouseEnabled: Boolean = false,
    val gyroMouseScale: Float = 50.0f,
    val gyroXSensitivity: Float = 1.0f,
    val gyroYSensitivity: Float = 1.0f,
    val gyroSmoothing: Float = 0.1f,
    val gyroDeadzone: Float = 0.05f,
    val invertGyroX: Boolean = false,
    val invertGyroY: Boolean = false,
    val gyroscopeCardExpanded: Boolean = false,
    val fpsLimit: Int = 0,
    val maxRefreshRate: Int = 60,
    val frameGenAvailable: Boolean = false,
    val frameGenEnabled: Boolean = false,
    val frameGenMultiplier: Int = 2,
    val frameGenTargetRate: Int = 0,
    val frameGenFlowScale: Int = 70,
    val screenEffectsCardExpanded: Boolean = false,
    val sgsrEnabled: Boolean = false,
    val sgsrSharpness: Int = 100,
    val vividEnabled: Boolean = false,
    val vividStrength: Int = 100,
    val colorProfile: Int = 0,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val gammaPercent: Int = 100,
    val scaleFilter: Int = 0,
    val saturation: Int = 100,
    val temperature: Int = 0,
    val tint: Int = 0,
    val sharpenEnabled: Boolean = false,
    val sharpenStrength: Int = 50,
    val scanlinesEnabled: Boolean = false,
    val scanlinesIntensity: Int = 50,
    val pixelateEnabled: Boolean = false,
    val pixelateBlock: Int = 6,
    val colorBlind: Int = 0,
    // reshadeMode is "solo" (one effect bypasses the rest) or "stack"; param keys follow ReshadeManager.seedValues.
    val reshadeMasterEnabled: Boolean = false,
    val reshadeMode: String = "solo",
    val reshadeLoadout: List<ReshadeLoadoutItem> = emptyList(),
    val inputControlsProfileNames: List<String> = emptyList(),
    val inputControlsSelectedProfileIndex: Int = 0,
    val inputControlsStyleNames: List<String> = emptyList(),
    val inputControlsSelectedStyleIndex: Int = 0,
    val inputControlsAccentThemeNames: List<String> = emptyList(),
    val inputControlsSelectedAccentThemeIndex: Int = 0,
    val inputControlsShowOverlay: Boolean = false,
    val inputControlsTapToClick: Boolean = true,
    val inputControlsOverlayOpacity: Float = 0.4f,
    val inputControlsTouchscreenHaptics: Boolean = false,
    val inputControlsGamepadVibration: Boolean = true,
    val inputControlsGcmRumbleMode: String = "disabled",
    val inputControlsReverseBindingOrder: Boolean = false,
    val cursorSpeed: Float = 1.0f,
    // External display / cast "Output" pane.
    val outputSwapActive: Boolean = false,
    val outputDisplayName: String = "",
    val outputResolutionLabels: List<String> = emptyList(),
    val outputSelectedResolutionIndex: Int = 0,
    val outputRefreshLabels: List<String> = emptyList(),
    val outputSelectedRefreshIndex: Int = 0,
    val outputAspectMode: Int = 0,
    val outputGameModeSupported: Boolean = false,
    val outputGameModeEnabled: Boolean = false,
    // Sink ignored real mode switches — phone is scaling; resolution becomes a render-size control.
    val outputPanelScaling: Boolean = false,
    val outputPanelNative: String = "",
    // Display connected but game still on the phone — show the "Send to display" button.
    val outputDisplayAvailable: Boolean = false,
    // Viture XR glasses controls (USB), present only when Viture glasses are connected.
    val outputVitureConnected: Boolean = false,
    val outputVitureName: String = "",
    val outputVitureSupportsBrightness: Boolean = false,
    val outputVitureBrightness: Int = 0,
    val outputVitureBrightnessMax: Int = 8,
    val outputVitureSupportsFilm: Boolean = false,
    val outputVitureFilmStepped: Boolean = false,
    val outputVitureFilm: Int = 0,
    val outputVitureSupports3D: Boolean = false,
    val outputViture3D: Boolean = false,
    val outputVitureSupportsVolume: Boolean = false,
    val outputVitureVolume: Int = 0,
    val outputVitureVolumeMax: Int = 8,
    val recordConfig: RecordUiConfig = RecordUiConfig(),
    val mouseEnabled: Boolean = true,
    val relativeMouseEnabled: Boolean = false,
    val screenTouchMode: Int = 0,
    val rtsGesturesEnabled: Boolean = false,
    val gestureProfileNames: List<String> = emptyList(),
    val gestureSelectedProfileIndex: Int = 0,
    val rightStickSensitivity: Float = 1.0f,
    val screenTouchRsSensitivity: Float = 1.25f,
    val mangoHudEnabled: Boolean = false,
    val mangoHudElements: BooleanArray = BooleanArray(20) { it < 12 },
    val mangoHudAlpha: Float = 1.0f,
    val mangoHudBgAlpha: Float = 0.5f,
    val mangoHudScale: Float = 0.735f,
    val mangoHudLocked: Boolean = false,
)

class XServerDrawerStateHolder(
    initialState: XServerDrawerState,
) {
    var state by mutableStateOf(initialState, neverEqualPolicy())
    var taskManagerState by mutableStateOf(TaskManagerPaneState(), neverEqualPolicy())
    var logsState by mutableStateOf(LogsPaneState(), neverEqualPolicy())
        private set
    private val logsBuffer = java.util.Collections.synchronizedList(ArrayList<String>(LogsPaneMaxLines))
    @Volatile private var logsPausedFlag = false
    @Volatile private var logsPaneVisibleFlag = false
    private val logsMainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val logsFlushPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val logsFlushRunnable = Runnable {
        logsFlushPending.set(false)
        flushLogsBufferToState()
    }
    private var drawerOpen by mutableStateOf(false)
    internal var openSide by mutableStateOf<DrawerSide?>(null)
    internal var openPane by mutableStateOf<DrawerPane?>(null)
    var menuNavRegion by mutableStateOf(0)
        private set
    var menuNavIndex by mutableStateOf(0)
        private set
    var menuActivateSignal by mutableStateOf(0)
        private set
    private var tabCount = 1
    private var cardCount = 0
    private var cardColumns = 3
    private var bottomCount = 0
    var paneNavSignal by mutableStateOf(0)
        private set
    var paneNavDir by mutableStateOf(0)
        private set
    var controllerConnected by mutableStateOf(false)
        private set
    private var paneVisibilityListener: ((Boolean) -> Unit)? = null

    // Bumped on swap-back so the host re-requests a layout pass on the Compose-hosted display frame.
    var phoneRelayoutTick by mutableStateOf(0)
        private set

    fun requestPhoneRelayout() {
        phoneRelayoutTick++
    }

    val isDrawerOpen: Boolean
        get() = drawerOpen

    fun openDrawer() = openDrawer(DrawerSide.LEFT)

    fun openDrawer(side: DrawerSide) {
        drawerOpen = true
        openSide = side
    }

    private fun regionSize(r: Int) = when (r) {
        0 -> tabCount
        1 -> cardCount
        2 -> bottomCount
        else -> 0
    }

    private fun clampNav() {
        val max = (regionSize(menuNavRegion) - 1).coerceAtLeast(0)
        if (menuNavIndex > max) menuNavIndex = max
    }

    fun menuActivate() { menuActivateSignal++ }

    fun resetMenuNav() {
        menuNavRegion = 0
        menuNavIndex = 0
    }

    fun menuNavLeft() { if (menuNavIndex > 0) menuNavIndex-- }

    fun menuNavRight() { if (menuNavIndex < regionSize(menuNavRegion) - 1) menuNavIndex++ }

    fun setMenuNav(region: Int, index: Int) {
        menuNavRegion = region
        menuNavIndex = index.coerceAtLeast(0)
        clampNav()
    }

    fun menuNavUp() {
        when (menuNavRegion) {
            1 ->
                if (menuNavIndex < cardColumns) {
                    if (tabCount > 0) {
                        menuNavRegion = 0
                        menuNavIndex = menuNavIndex.coerceAtMost(tabCount - 1)
                    }
                } else {
                    menuNavIndex -= cardColumns
                }
            2 ->
                when {
                    cardCount > 0 -> {
                        menuNavRegion = 1
                        menuNavIndex = cardCount - 1
                    }
                    tabCount > 0 -> {
                        menuNavRegion = 0
                        menuNavIndex = menuNavIndex.coerceAtMost(tabCount - 1)
                    }
                }
        }
    }

    fun menuNavDown() {
        when (menuNavRegion) {
            0 ->
                when {
                    cardCount > 0 -> {
                        menuNavRegion = 1
                        menuNavIndex = 0
                    }
                    bottomCount > 0 -> {
                        menuNavRegion = 2
                        menuNavIndex = 0
                    }
                }
            1 -> {
                val below = menuNavIndex + cardColumns
                if (below < cardCount) {
                    menuNavIndex = below
                } else if (bottomCount > 0) {
                    menuNavRegion = 2
                    menuNavIndex = 0
                }
            }
        }
    }

    fun setMenuTabCount(n: Int) {
        tabCount = n.coerceAtLeast(0)
        clampNav()
    }

    fun setMenuCardLayout(count: Int, columns: Int) {
        cardCount = count.coerceAtLeast(0)
        cardColumns = columns.coerceAtLeast(1)
        clampNav()
    }

    fun setMenuBottomCount(n: Int) {
        bottomCount = n.coerceAtLeast(0)
        clampNav()
    }

    private fun paneNav(dir: Int) {
        paneNavDir = dir
        paneNavSignal++
    }

    fun paneNavLeft() = paneNav(PANE_DIR_LEFT)

    fun paneNavRight() = paneNav(PANE_DIR_RIGHT)

    fun paneNavUp() = paneNav(PANE_DIR_UP)

    fun paneNavDown() = paneNav(PANE_DIR_DOWN)

    fun paneActivate() = paneNav(PANE_DIR_ACTIVATE)

    fun paneSecondary() = paneNav(PANE_DIR_SECONDARY)

    fun resetPaneNav() {}

    fun updateControllerConnected(connected: Boolean) { controllerConnected = connected }

    var paneOverlayCloser: (() -> Unit)? = null

    fun consumeOverlayBack(): Boolean {
        val c = paneOverlayCloser ?: return false
        c()
        return true
    }

    fun closeDrawer() {
        drawerOpen = false
        openSide = null
        openPane = null
    }

    fun isPaneOpen(): Boolean = openPane != null

    fun closeOpenPane() {
        if (openPane != null) {
            openPane = null
            paneVisibilityListener?.invoke(false)
        }
    }

    internal fun setPaneVisibilityListener(listener: (Boolean) -> Unit) {
        paneVisibilityListener = listener
    }

    internal fun clearPaneVisibilityListener() {
        paneVisibilityListener = null
    }

    internal fun setOpenPaneAndNotify(newPane: DrawerPane?) {
        val wasVisible = openPane != null
        val nowVisible = newPane != null
        openPane = newPane
        if (newPane != null) resetPaneNav()
        if (wasVisible != nowVisible) paneVisibilityListener?.invoke(nowVisible)
    }

    fun openLogsPane() {
        setOpenPaneAndNotify(DrawerPane.LOGS)
    }

    /** Append a log line (any thread). Buffers off-thread when the pane is hidden (no recomposition); flushes to state when the pane is visible. */
    fun appendLogLine(line: String) {
        if (logsPausedFlag) return
        synchronized(logsBuffer) {
            logsBuffer.add(line)
            while (logsBuffer.size > LogsPaneMaxLines) logsBuffer.removeAt(0)
        }
        if (logsPaneVisibleFlag && logsFlushPending.compareAndSet(false, true)) {
            logsMainHandler.postDelayed(logsFlushRunnable, LogsFlushIntervalMs)
        }
    }

    private fun flushLogsBufferToState() {
        val snapshot = synchronized(logsBuffer) { ArrayList(logsBuffer) }
        logsState = logsState.copy(lines = snapshot)
    }

    fun clearLogLines() {
        synchronized(logsBuffer) { logsBuffer.clear() }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            logsState = logsState.copy(lines = emptyList())
        } else {
            logsMainHandler.post { logsState = logsState.copy(lines = emptyList()) }
        }
    }

    fun setLogsPaused(paused: Boolean) {
        if (logsPausedFlag == paused) return
        logsPausedFlag = paused
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            logsState = logsState.copy(paused = paused)
        } else {
            logsMainHandler.post { logsState = logsState.copy(paused = paused) }
        }
    }

    fun setLogsPaneVisible(visible: Boolean) {
        if (logsPaneVisibleFlag == visible) return
        logsPaneVisibleFlag = visible
        if (visible) flushLogsBufferToState()
    }

    fun snapshotLogLines(): List<String> = synchronized(logsBuffer) { ArrayList(logsBuffer) }
}

interface XServerDrawerActionListener {
    fun onActionSelected(itemId: Int)

    fun onHUDElementToggled(
        index: Int,
        enabled: Boolean,
    )

    fun onHUDTransparencyChanged(transparency: Float)

    fun onHUDBackgroundAlphaDecoupledChanged(enabled: Boolean)

    fun onHUDBackgroundTransparencyChanged(transparency: Float)

    fun onHUDScaleChanged(scale: Float)

    fun onDualSeriesBatteryChanged(enabled: Boolean)

    fun onFrametimeNumericChanged(enabled: Boolean)

    fun onMangoHudChanged(enabled: Boolean)

    fun onMangoHudElementToggled(
        index: Int,
        enabled: Boolean,
    )

    fun onMangoHudAlphaChanged(alpha: Float)

    fun onMangoHudBackgroundAlphaChanged(alpha: Float)

    fun onMangoHudScaleChanged(scale: Float)

    fun onMangoHudLockChanged(locked: Boolean)

    fun onHUDCardExpandedChanged(expanded: Boolean)

    fun onGyroscopeEnabledChanged(enabled: Boolean)

    fun onGyroscopeModeSelected(mode: Int)

    fun onGyroOrientationModeChanged(enabled: Boolean)

    fun onGyroscopeActivatorSelected(keycode: Int)

    fun onGyroscopeActivatorBindingSelected(bindingName: String)

    fun onRightStickGyroChanged(enabled: Boolean)

    fun onGyroMouseEnabledChanged(enabled: Boolean)

    fun onGyroMouseScaleChanged(scale: Float)

    fun onGyroXSensitivityChanged(sensitivity: Float)

    fun onGyroYSensitivityChanged(sensitivity: Float)

    fun onGyroSmoothingChanged(smoothing: Float)

    fun onGyroDeadzoneChanged(deadzone: Float)

    fun onInvertGyroXChanged(enabled: Boolean)

    fun onInvertGyroYChanged(enabled: Boolean)

    fun onGyroscopeCardExpandedChanged(expanded: Boolean)

    fun onFPSLimitChanged(limit: Int)

    fun onFrameGenEnabledChanged(enabled: Boolean)

    fun onFrameGenMultiplierSelected(multiplier: Int)

    fun onFrameGenTargetRateSelected(rate: Int)

    fun onFrameGenFlowScaleChanged(percent: Int)

    fun onScreenEffectsCardExpandedChanged(expanded: Boolean)

    fun onOutputResolutionSelected(index: Int)

    fun onOutputRefreshRateSelected(index: Int)

    fun onOutputAspectModeSelected(mode: Int)

    fun onOutputGameModeToggled(enabled: Boolean)

    fun onOutputVitureBrightness(level: Int)

    fun onOutputVitureFilm(level: Int)

    fun onOutputViture3D(enabled: Boolean)

    fun onOutputVitureVolume(level: Int)

    fun onOutputReturnToPhone()

    fun onOutputSwapToDisplay()

    fun onOutputCastClick()

    fun onSGSREnabledChanged(enabled: Boolean)

    fun onSGSRSharpnessChanged(sharpness: Int)

    fun onVividEnabledChanged(enabled: Boolean)

    fun onVividStrengthChanged(strength: Int)

    fun onColorProfileSelected(profile: Int)

    fun onBrightnessChanged(value: Int)

    fun onContrastChanged(value: Int)

    fun onGammaChanged(value: Int)

    fun onScaleFilterSelected(mode: Int)

    fun onSaturationChanged(value: Int)

    fun onTemperatureChanged(value: Int)

    fun onTintChanged(value: Int)

    fun onSharpenEnabledChanged(enabled: Boolean)

    fun onSharpenStrengthChanged(value: Int)

    fun onScanlinesEnabledChanged(enabled: Boolean)

    fun onScanlinesIntensityChanged(value: Int)

    fun onPixelateEnabledChanged(enabled: Boolean)

    fun onPixelateBlockChanged(value: Int)

    fun onColorBlindSelected(mode: Int)

    fun onResetEffects()

    fun onReshadeMasterEnabledChanged(enabled: Boolean)

    // In solo mode enabling one effect bypasses the others (host-side).
    fun onReshadeEffectEnabledChanged(index: Int, enabled: Boolean)

    fun onReshadeModeChanged(mode: String)

    // key = ReshadeManager.seedValues scheme.
    fun onReshadeParamChanged(index: Int, key: String, value: Float)

    fun onReshadeReset(index: Int)

    fun onInputControlsProfileSelected(index: Int)

    fun onInputControlsStyleSelected(index: Int)

    fun onInputControlsAccentThemeSelected(index: Int)

    fun onInputControlsShowOverlayChanged(enabled: Boolean)

    fun onInputControlsTapToClickChanged(enabled: Boolean)

    fun onInputControlsOverlayOpacityChanged(opacity: Float)

    fun onInputControlsTouchscreenHapticsChanged(enabled: Boolean)

    fun onInputControlsGamepadVibrationChanged(enabled: Boolean)

    fun onCursorSpeedChanged(speed: Float)

    fun onInputControlsGcmRumbleModeChanged(mode: String)

    fun onInputControlsReverseBindingOrderChanged(enabled: Boolean)

    fun onInputControlsEditClick()

    fun onScreenTouchModeChanged(mode: Int)

    fun onRtsGesturesToggled(enabled: Boolean)

    fun onGestureProfileSelected(index: Int)

    fun onRtsGesturesEditClick()

    fun onRightStickSensitivityChanged(sensitivity: Float)

    fun onTaskManagerVisibilityChanged(visible: Boolean)

    fun onTaskManagerCpuExpandedChanged(expanded: Boolean)

    fun onTaskManagerEndProcess(name: String)

    fun onTaskManagerBringToFront(name: String)

    fun onTaskManagerSetAffinity(pid: Int, affinityMask: Int)

    fun onTaskManagerNewTask(command: String)

    fun onLogsClear()

    fun onLogsPauseChanged(paused: Boolean)

    fun onLogsPaneVisibilityChanged(visible: Boolean)

    fun onLogsShare()

    /** Start recording with the chosen settings (indices into the option lists in RecordUiConfig). */
    fun onRecordStart(fpsIndex: Int, resolutionIndex: Int, quality: Int, recordUI: Boolean)
}

fun buildXServerDrawerState(
    context: Context,
    relativeMouseEnabled: Boolean,
    mouseDisabled: Boolean,
    fpsMonitorEnabled: Boolean,
    paused: Boolean,
    showMagnifier: Boolean,
    magnifierActive: Boolean,
    showLogs: Boolean,
    hudTransparency: Float = 1.0f,
    hudBackgroundAlphaEnabled: Boolean = false,
    hudBackgroundTransparency: Float = 1.0f,
    hudScale: Float = 1.0f,
    hudElements: BooleanArray = booleanArrayOf(true, true, true, true, true, true, true, true, false),
    dualSeriesBatteryEnabled: Boolean = false,
    frametimeNumericEnabled: Boolean = false,
    hudCardExpanded: Boolean = false,
    gyroscopeEnabled: Boolean = false,
    gyroscopeModeIndex: Int = 0,
    gyroOrientationEnabled: Boolean = false,
    gyroscopeActivatorLabel: String = "",
    rightStickGyroEnabled: Boolean = false,
    gyroMouseEnabled: Boolean = false,
    gyroMouseScale: Float = 50.0f,
    gyroXSensitivity: Float = 1.0f,
    gyroYSensitivity: Float = 1.0f,
    gyroSmoothing: Float = 0.1f,
    gyroDeadzone: Float = 0.05f,
    invertGyroX: Boolean = false,
    invertGyroY: Boolean = false,
    gyroscopeCardExpanded: Boolean = false,
    fpsLimit: Int = 0,
    screenEffectsCardExpanded: Boolean = false,
    sgsrEnabled: Boolean = false,
    sgsrSharpness: Int = 100,
    vividEnabled: Boolean = false,
    vividStrength: Int = 100,
    colorProfile: Int = 0,
    brightness: Int = 0,
    contrast: Int = 0,
    gammaPercent: Int = 100,
    scaleFilter: Int = 0,
    saturation: Int = 100,
    temperature: Int = 0,
    tint: Int = 0,
    sharpenEnabled: Boolean = false,
    sharpenStrength: Int = 50,
    scanlinesEnabled: Boolean = false,
    scanlinesIntensity: Int = 50,
    pixelateEnabled: Boolean = false,
    pixelateBlock: Int = 6,
    colorBlind: Int = 0,
    inputControlsProfileNames: List<String> = emptyList(),
    inputControlsSelectedProfileIndex: Int = 0,
    inputControlsStyleNames: List<String> = emptyList(),
    inputControlsSelectedStyleIndex: Int = 0,
    inputControlsAccentThemeNames: List<String> = emptyList(),
    inputControlsSelectedAccentThemeIndex: Int = 0,
    inputControlsShowOverlay: Boolean = false,
    inputControlsTapToClick: Boolean = true,
    inputControlsOverlayOpacity: Float = 0.4f,
    inputControlsTouchscreenHaptics: Boolean = false,
    inputControlsGamepadVibration: Boolean = true,
    inputControlsGcmRumbleMode: String = "disabled",
    inputControlsReverseBindingOrder: Boolean = false,
    cursorSpeed: Float = 1.0f,
    fullscreenEnabled: Boolean = false,
    maxRefreshRate: Int = 60,
    refactorSizeEnabled: Boolean = false,
    recordingActive: Boolean = false,
    recordConfig: RecordUiConfig = RecordUiConfig(),
    screenTouchMode: Int = 0,
    rtsGesturesEnabled: Boolean = false,
    gestureProfileNames: List<String> = emptyList(),
    gestureSelectedProfileIndex: Int = 0,
    rightStickSensitivity: Float = 1.0f,
    screenTouchRsSensitivity: Float = 1.25f,
    mangoHudEnabled: Boolean = false,
    mangoHudElements: BooleanArray = BooleanArray(20) { it < 12 },
    mangoHudAlpha: Float = 1.0f,
    mangoHudBgAlpha: Float = 0.5f,
    mangoHudScale: Float = 0.735f,
    mangoHudLocked: Boolean = false,
): XServerDrawerState {
    val items =
        mutableListOf(
            XServerDrawerItem(
                itemId = R.id.main_menu_fps_monitor,
                title = context.getString(R.string.session_drawer_fps_monitor),
                subtitle =
                    if (fpsMonitorEnabled) context.getString(R.string.common_ui_enabled) else context.getString(R.string.common_ui_disabled),
                icon = Icons.Outlined.Monitor,
                active = fpsMonitorEnabled,
            ),
            XServerDrawerItem(
                itemId = R.id.main_menu_keyboard,
                title = context.getString(R.string.session_drawer_keyboard),
                subtitle = "",
                icon = Icons.Outlined.Keyboard,
            ),
            XServerDrawerItem(
                itemId = R.id.main_menu_input_controls,
                title = context.getString(R.string.common_ui_input_controls),
                subtitle = "",
                icon = Icons.Outlined.SportsEsports,
                active = inputControlsSelectedProfileIndex > 0,
            ),
            XServerDrawerItem(
                itemId = R.id.main_menu_gyroscope,
                title = context.getString(R.string.session_gyroscope_title),
                subtitle = "",
                icon = Icons.Outlined.SportsEsports,
                active = gyroscopeEnabled,
            ),
            XServerDrawerItem(
                itemId = R.id.main_menu_touch,
                title = context.getString(R.string.session_drawer_touch),
                subtitle = "",
                icon = Icons.Outlined.TouchApp,
                active = screenTouchMode != 0 || rtsGesturesEnabled || !mouseDisabled,
            ),
            XServerDrawerItem(
                itemId = R.id.main_menu_toggle_fullscreen,
                title = context.getString(R.string.session_drawer_toggle_fullscreen),
                subtitle = "",
                icon = Icons.Outlined.Fullscreen,
                active = fullscreenEnabled,
            ),
            XServerDrawerItem(
                itemId = R.id.main_menu_screen_effects,
                title = context.getString(R.string.session_drawer_screen_effects),
                subtitle = context.getString(R.string.session_drawer_screen_effects_subtitle),
                icon = Icons.Outlined.Tune,
                active = sgsrEnabled || vividEnabled || colorProfile > 0 ||
                    brightness != 0 || contrast != 0 || gammaPercent != 100 || scaleFilter != 0 ||
                    saturation != 100 || temperature != 0 || tint != 0 ||
                    sharpenEnabled || scanlinesEnabled || pixelateEnabled || colorBlind != 0,
            ),
            XServerDrawerItem(
                itemId = R.id.main_menu_pause,
                title = if (paused) context.getString(R.string.session_drawer_resume) else context.getString(R.string.session_drawer_pause),
                subtitle =
                    if (paused) context.getString(R.string.session_drawer_wine_processes_paused) else context.getString(R.string.session_drawer_pause_all_wine_processes),
                icon = if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                active = paused,
            ),
            XServerDrawerItem(
                itemId = R.id.main_menu_pip_mode,
                title = context.getString(R.string.session_drawer_picture_in_picture),
                subtitle = "",
                icon = Icons.Outlined.PictureInPictureAlt,
            ),
        )

    if (showMagnifier) {
        items +=
            XServerDrawerItem(
                itemId = R.id.main_menu_magnifier,
                title = context.getString(R.string.session_drawer_magnifier),
                subtitle = "",
                icon = Icons.Outlined.ZoomIn,
                active = magnifierActive,
            )
    }

    items +=
        XServerDrawerItem(
            itemId = R.id.main_menu_refactor_size,
            title = context.getString(R.string.session_drawer_refactor_size),
            subtitle = "",
            icon = Icons.Outlined.PictureInPictureAlt,
            active = refactorSizeEnabled,
        )

    items +=
        XServerDrawerItem(
            itemId = R.id.main_menu_task_manager,
            title = context.getString(R.string.session_task_title),
            subtitle = "",
            icon = Icons.AutoMirrored.Outlined.ViewList,
        )

    items +=
        XServerDrawerItem(
            itemId = R.id.main_menu_record,
            title = context.getString(R.string.session_drawer_rail_label_record),
            subtitle = "",
            icon = Icons.Outlined.FiberManualRecord,
            active = recordingActive,
        )

    if (showLogs) {
        items.add(
            XServerDrawerItem(
                itemId = R.id.main_menu_logs,
                title = context.getString(R.string.session_drawer_logs),
                subtitle = "",
                icon = Icons.Outlined.Terminal,
            ),
        )
    }

    items +=
        XServerDrawerItem(
            itemId = R.id.main_menu_exit,
            title = context.getString(R.string.common_ui_exit),
            subtitle = context.getString(R.string.session_drawer_exit_subtitle),
            icon = Icons.AutoMirrored.Outlined.ExitToApp,
        )

    items +=
        XServerDrawerItem(
            itemId = R.id.main_menu_drawer_layout,
            title = context.getString(R.string.session_drawer_layout),
            subtitle = "",
            icon = Icons.Outlined.Tune,
        )

    val itemSideMap = loadDrawerItemSides(context)
    val finalItems = items.map { it.copy(side = itemSideMap[it.itemId] ?: DrawerSide.LEFT) }
    val paneSides = loadDrawerPaneSides(context)

    return XServerDrawerState(
        recordConfig = recordConfig,
        items = finalItems,
        paneSides = paneSides,
        hudTransparency = hudTransparency,
        hudBackgroundAlphaEnabled = hudBackgroundAlphaEnabled,
        hudBackgroundTransparency = hudBackgroundTransparency,
        hudScale = hudScale,
        hudElements = hudElements,
        dualSeriesBatteryEnabled = dualSeriesBatteryEnabled,
        frametimeNumericEnabled = frametimeNumericEnabled,
        hudCardExpanded = hudCardExpanded,
        gyroscopeEnabled = gyroscopeEnabled,
        gyroscopeModeIndex = gyroscopeModeIndex,
        gyroOrientationEnabled = gyroOrientationEnabled,
        gyroscopeActivatorLabel = gyroscopeActivatorLabel,
        rightStickGyroEnabled = rightStickGyroEnabled,
        gyroMouseEnabled = gyroMouseEnabled,
        gyroMouseScale = gyroMouseScale,
        gyroXSensitivity = gyroXSensitivity,
        gyroYSensitivity = gyroYSensitivity,
        gyroSmoothing = gyroSmoothing,
        gyroDeadzone = gyroDeadzone,
        invertGyroX = invertGyroX,
        invertGyroY = invertGyroY,
        gyroscopeCardExpanded = gyroscopeCardExpanded,
        fpsLimit = fpsLimit,
        maxRefreshRate = maxRefreshRate,
        screenEffectsCardExpanded = screenEffectsCardExpanded,
        sgsrEnabled = sgsrEnabled,
        sgsrSharpness = sgsrSharpness,
        vividEnabled = vividEnabled,
        vividStrength = vividStrength,
        colorProfile = colorProfile,
        brightness = brightness,
        contrast = contrast,
        gammaPercent = gammaPercent,
        scaleFilter = scaleFilter,
        saturation = saturation,
        temperature = temperature,
        tint = tint,
        sharpenEnabled = sharpenEnabled,
        sharpenStrength = sharpenStrength,
        scanlinesEnabled = scanlinesEnabled,
        scanlinesIntensity = scanlinesIntensity,
        pixelateEnabled = pixelateEnabled,
        pixelateBlock = pixelateBlock,
        colorBlind = colorBlind,
        inputControlsProfileNames = inputControlsProfileNames,
        inputControlsSelectedProfileIndex = inputControlsSelectedProfileIndex,
        inputControlsStyleNames = inputControlsStyleNames,
        inputControlsSelectedStyleIndex = inputControlsSelectedStyleIndex,
        inputControlsAccentThemeNames = inputControlsAccentThemeNames,
        inputControlsSelectedAccentThemeIndex = inputControlsSelectedAccentThemeIndex,
        inputControlsShowOverlay = inputControlsShowOverlay,
        inputControlsTapToClick = inputControlsTapToClick,
        inputControlsOverlayOpacity = inputControlsOverlayOpacity,
        inputControlsTouchscreenHaptics = inputControlsTouchscreenHaptics,
        inputControlsGamepadVibration = inputControlsGamepadVibration,
        inputControlsGcmRumbleMode = inputControlsGcmRumbleMode,
        inputControlsReverseBindingOrder = inputControlsReverseBindingOrder,
        cursorSpeed = cursorSpeed,
        mouseEnabled = !mouseDisabled,
        relativeMouseEnabled = relativeMouseEnabled,
        screenTouchMode = screenTouchMode,
        rtsGesturesEnabled = rtsGesturesEnabled,
        gestureProfileNames = gestureProfileNames,
        gestureSelectedProfileIndex = gestureSelectedProfileIndex,
        rightStickSensitivity = rightStickSensitivity,
        screenTouchRsSensitivity = screenTouchRsSensitivity,
        mangoHudEnabled = mangoHudEnabled,
        mangoHudElements = mangoHudElements,
        mangoHudAlpha = mangoHudAlpha,
        mangoHudBgAlpha = mangoHudBgAlpha,
        mangoHudScale = mangoHudScale,
        mangoHudLocked = mangoHudLocked,
    )
}

fun setupXServerDrawerComposeView(
    composeView: ComposeView,
    stateHolder: XServerDrawerStateHolder,
    _activity: Activity,
    listener: XServerDrawerActionListener,
    onDismiss: Runnable,
    onPaneVisibilityChanged: (Boolean) -> Unit = {},
) {
    stateHolder.setPaneVisibilityListener(onPaneVisibilityChanged)
    composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    composeView.setContent {
        WinNativeTheme {
            XServerDrawerContent(
                state = stateHolder.state,
                taskManagerState = stateHolder.taskManagerState,
                logsState = stateHolder.logsState,
                openPane = stateHolder.openPane,
                onOpenPaneChange = { stateHolder.setOpenPaneAndNotify(it) },
                listener = listener,
                onDismiss = { onDismiss.run() },
            )
        }
    }
}

fun withFrameGenState(
    state: XServerDrawerState,
    available: Boolean,
    enabled: Boolean,
    multiplier: Int,
    targetRate: Int,
    flowScale: Int,
    frameGenTitle: String,
): XServerDrawerState =
    state.copy(
        items =
            state.items +
                XServerDrawerItem(
                    itemId = R.id.main_menu_frame_generation,
                    title = frameGenTitle,
                    subtitle = "",
                    icon = Icons.Outlined.Speed,
                    active = enabled || state.fpsLimit > 0,
                ),
        frameGenAvailable = available,
        frameGenEnabled = enabled,
        frameGenMultiplier = multiplier.coerceIn(2, FrameGenMultipliers.last()),
        frameGenTargetRate = targetRate.coerceAtLeast(0),
        frameGenFlowScale = flowScale.coerceIn(FrameGenFlowScaleMin, FrameGenFlowScaleMax),
    )

// Append the always-present "Output" tab item and its state to the drawer state.
fun withOutputState(
    state: XServerDrawerState,
    swapActive: Boolean,
    displayName: String,
    resolutionLabels: List<String>,
    selectedResolutionIndex: Int,
    refreshLabels: List<String>,
    selectedRefreshIndex: Int,
    aspectMode: Int,
    gameModeSupported: Boolean,
    gameModeEnabled: Boolean,
    panelScaling: Boolean,
    panelNative: String,
    displayAvailable: Boolean,
    outputTitle: String,
): XServerDrawerState {
    val outputItem =
        XServerDrawerItem(
            itemId = R.id.main_menu_output,
            title = outputTitle,
            subtitle = "",
            icon = Icons.Outlined.Monitor,
        )
    return state.copy(
        items = state.items + outputItem,
        outputSwapActive = swapActive,
        outputDisplayName = displayName,
        outputResolutionLabels = resolutionLabels,
        outputSelectedResolutionIndex = selectedResolutionIndex,
        outputRefreshLabels = refreshLabels,
        outputSelectedRefreshIndex = selectedRefreshIndex,
        outputAspectMode = aspectMode,
        outputGameModeSupported = gameModeSupported,
        outputGameModeEnabled = gameModeEnabled,
        outputPanelScaling = panelScaling,
        outputPanelNative = panelNative,
        outputDisplayAvailable = displayAvailable,
    )
}

// Overlay Viture-glasses control state onto the output state (only when Viture glasses are connected).
fun withVitureState(
    state: XServerDrawerState,
    name: String,
    supportsBrightness: Boolean,
    brightness: Int,
    brightnessMax: Int,
    supportsFilm: Boolean,
    filmStepped: Boolean,
    film: Int,
    supports3D: Boolean,
    threeD: Boolean,
    supportsVolume: Boolean,
    volume: Int,
    volumeMax: Int,
): XServerDrawerState =
    state.copy(
        outputVitureConnected = true,
        outputVitureName = name,
        outputVitureSupportsBrightness = supportsBrightness,
        outputVitureBrightness = brightness,
        outputVitureBrightnessMax = brightnessMax,
        outputVitureSupportsFilm = supportsFilm,
        outputVitureFilmStepped = filmStepped,
        outputVitureFilm = film,
        outputVitureSupports3D = supports3D,
        outputViture3D = threeD,
        outputVitureSupportsVolume = supportsVolume,
        outputVitureVolume = volume,
        outputVitureVolumeMax = volumeMax,
    )

// Call only when a ReShade effect was applied at launch (Vulkan wrapper) so the vkBasalt layer is loaded.
fun withReshadeState(
    state: XServerDrawerState,
    masterEnabled: Boolean,
    mode: String,
    loadout: List<ReshadeLoadoutItem>,
    reshadeTitle: String,
): XServerDrawerState {
    val reshadeItem =
        XServerDrawerItem(
            itemId = R.id.main_menu_reshade,
            title = reshadeTitle,
            subtitle = "",
            icon = Icons.Outlined.AutoAwesome,
            active = masterEnabled,
        )
    return state.copy(
        items = state.items + reshadeItem,
        reshadeMasterEnabled = masterEnabled,
        reshadeMode = mode,
        reshadeLoadout = loadout,
    )
}

@Composable
internal fun XServerDrawerContent(
    state: XServerDrawerState,
    taskManagerState: TaskManagerPaneState,
    logsState: LogsPaneState,
    openPane: DrawerPane?,
    onOpenPaneChange: (DrawerPane?) -> Unit,
    listener: XServerDrawerActionListener,
    onDismiss: () -> Unit,
    openSide: DrawerSide? = null,
    onDrawerLayoutChanged: (List<XServerDrawerItem>, Map<Int, DrawerSide>) -> Unit = { _, _ -> },
    revealCards: Boolean = true,
    menuNavRegion: Int = 0,
    menuNavIndex: Int = 0,
    menuActivateSignal: Int = 0,
    onSetTabCount: (Int) -> Unit = {},
    onSetCardLayout: (Int, Int) -> Unit = { _, _ -> },
    onSetBottomCount: (Int) -> Unit = {},
    onCursor: (Int, Int) -> Unit = { _, _ -> },
    paneNavSignal: Int = 0,
    paneNavDir: Int = 0,
    controllerActive: Boolean = false,
    onOverlayCloserChange: ((() -> Unit)?) -> Unit = {},
) {
    // Content stays composed while the sheet is closed (host translates it off-screen) to avoid a first-composition cost on open; the staggered card reveal is driven from the sheet's engaged state so it replays on each open.
    val cardsRevealed = remember { mutableStateOf(false) }
    LaunchedEffect(revealCards) { cardsRevealed.value = revealCards }

    val paneNav = remember(openPane) { PaneNavRegistry() }
    paneNav.controllerActive = controllerActive
    LaunchedEffect(paneNav, paneNavSignal) {
        paneNav.processNav(paneNavSignal, paneNavDir)
    }
    LaunchedEffect(paneNav, paneNav.overlay) {
        onOverlayCloserChange(if (paneNav.overlay != null) paneNav.overlayClose else null)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
        ) {
            val paneScale = computePaneScale(maxHeight)
            CompositionLocalProvider(LocalPaneScale provides paneScale) {
                Column(modifier = Modifier.fillMaxSize()) {
                    val railVisible = openPane != DrawerPane.TASK_MANAGER && openPane != DrawerPane.LOGS
                    val chromeEnter =
                        expandVertically(
                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        ) + fadeIn(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing))
                    val chromeExit =
                        shrinkVertically(
                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        ) + fadeOut(animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing))

                    AnimatedVisibility(
                        visible = railVisible,
                        enter = chromeEnter,
                        exit = chromeExit,
                    ) {
                        Column {
                            TopRail(
                                state = state,
                                openPane = openPane,
                                openSide = openSide,
                                paneSides = state.paneSides,
                                onTabClick = { spec ->
                                    onOpenPaneChange(if (openPane == spec.pane) null else spec.pane)
                                },
                                onMenuClick = { onOpenPaneChange(null) },
                                region = menuNavRegion,
                                navIndex = menuNavIndex,
                                activateSignal = menuActivateSignal,
                                onSetNavCount = onSetTabCount,
                                onCursor = onCursor,
                                controllerActive = controllerActive,
                            )

                            ThinDivider()
                        }
                    }

                    Box(
                        modifier =
                            Modifier
                                .weight(1f, fill = true)
                                .fillMaxWidth(),
                    ) {
                        AnimatedContent(
                            targetState = openPane,
                            transitionSpec = {
                                val enteringTaskManager = targetState == DrawerPane.TASK_MANAGER
                                val enteringLogs = targetState == DrawerPane.LOGS
                                val returningToMenu = targetState == null
                                if (enteringTaskManager || enteringLogs) {
                                    (
                                        slideInVertically(
                                            initialOffsetY = { it / 3 },
                                            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                                        ) + fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
                                    ) togetherWith fadeOut(animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing)) using
                                        SizeTransform(clip = false)
                                } else if (returningToMenu) {
                                    EnterTransition.None togetherWith ExitTransition.None
                                } else {
                                    fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)) togetherWith
                                        fadeOut(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
                                }
                            },
                            label = "drawerBody",
                        ) { pane ->
                            CompositionLocalProvider(LocalPaneNav provides paneNav) {
                            when (pane) {
                                DrawerPane.INPUT_CONTROLS -> InputControlsPaneContent(state = state, listener = listener)
                                DrawerPane.HUD -> HUDPaneContent(state = state, listener = listener)
                                DrawerPane.FRAME_GEN -> FrameGenPaneContent(state = state, listener = listener)
                                DrawerPane.GYROSCOPE -> GyroscopePaneContent(state = state, listener = listener)
                                DrawerPane.TOUCH -> TouchPaneContent(state = state, listener = listener, onClose = { onOpenPaneChange(null) })
                                DrawerPane.SCREEN_EFFECTS -> ScreenEffectsPaneContent(state = state, listener = listener)
                                DrawerPane.RESHADE -> ReshadePaneContent(state = state, listener = listener)
                                DrawerPane.OUTPUT -> OutputPaneContent(state = state, listener = listener)
                                DrawerPane.TASK_MANAGER ->
                                    TaskManagerPaneContent(
                                        taskManagerState = taskManagerState,
                                        listener = listener,
                                        onClose = { onOpenPaneChange(null) },
                                    )
                                DrawerPane.LOGS ->
                                    LogsPaneContent(
                                        logsState = logsState,
                                        listener = listener,
                                        onClose = { onOpenPaneChange(null) },
                                    )
                                null ->
                                    ActionCardGrid(
                                        state = state,
                                        listener = listener,
                                        cardsRevealed = cardsRevealed.value,
                                        openSide = openSide,
                                        onDrawerLayoutChanged = onDrawerLayoutChanged,
                                        onOpenTaskManager = { onOpenPaneChange(DrawerPane.TASK_MANAGER) },
                                        onOpenLogs = { onOpenPaneChange(DrawerPane.LOGS) },
                                        onOpenTouch = { onOpenPaneChange(DrawerPane.TOUCH) },
                                        region = menuNavRegion,
                                        navIndex = menuNavIndex,
                                        activateSignal = menuActivateSignal,
                                        onSetCardLayout = onSetCardLayout,
                                        onCursor = onCursor,
                                        controllerActive = controllerActive,
                                    )
                            }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = openPane == null,
                        enter = chromeEnter,
                        exit = chromeExit,
                    ) {
                        Column {
                            ThinDivider()

                            BottomActions(
                                state = state,
                                listener = listener,
                                region = menuNavRegion,
                                navIndex = menuNavIndex,
                                activateSignal = menuActivateSignal,
                                onSetCount = onSetBottomCount,
                                onCursor = onCursor,
                                controllerActive = controllerActive,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class RailTileBounds(val offsetX: Float, val width: Float, val height: Float)

@Composable
private fun TopRail(
    state: XServerDrawerState,
    openPane: DrawerPane?,
    openSide: DrawerSide? = null,
    paneSides: Map<Int, DrawerSide> = emptyMap(),
    onTabClick: (RailPaneSpec) -> Unit,
    onMenuClick: () -> Unit,
    region: Int = 0,
    navIndex: Int = 0,
    activateSignal: Int = 0,
    onSetNavCount: (Int) -> Unit = {},
    onCursor: (Int, Int) -> Unit = { _, _ -> },
    controllerActive: Boolean = false,
) {
    val paneScale = LocalPaneScale.current
    val density = LocalDensity.current
    val activeSpecs =
        RAIL_PANES.filter { spec ->
            state.items.any { it.itemId == spec.itemId } &&
                (openSide == null ||
                    (paneSides[spec.itemId] ?: DrawerSide.LEFT) == openSide ||
                    (paneSides[spec.itemId] ?: DrawerSide.LEFT) == DrawerSide.BOTH)
        }

    val tabCount = activeSpecs.size + 1
    LaunchedEffect(tabCount) { onSetNavCount(tabCount) }
    val lastActivate = remember { mutableStateOf(activateSignal) }
    LaunchedEffect(activateSignal) {
        if (activateSignal != lastActivate.value) {
            lastActivate.value = activateSignal
            if (region == 0) {
                if (navIndex <= 0) onMenuClick() else activeSpecs.getOrNull(navIndex - 1)?.let { onTabClick(it) }
            }
        }
    }

    val tileBounds = remember { mutableStateMapOf<String, RailTileBounds>() }
    val railScroll = rememberScrollState()

    val selectedKey =
        if (controllerActive && region == 0) {
            if (navIndex <= 0) "menu" else activeSpecs.getOrNull(navIndex - 1)?.itemId?.toString() ?: "menu"
        } else {
            when (openPane) {
                null -> "menu"
                else -> activeSpecs.firstOrNull { it.pane == openPane }?.itemId?.toString() ?: "menu"
            }
        }
    val selectedBounds = tileBounds[selectedKey]

    val indicatorAnimSpec = tween<Dp>(durationMillis = 240, easing = FastOutSlowInEasing)
    val indicatorX by animateDpAsState(
        targetValue = selectedBounds?.let { with(density) { it.offsetX.toDp() } } ?: 0.dp,
        animationSpec = indicatorAnimSpec,
        label = "topRailIndicatorX",
    )
    val indicatorWidth by animateDpAsState(
        targetValue = selectedBounds?.let { with(density) { it.width.toDp() } } ?: 0.dp,
        animationSpec = indicatorAnimSpec,
        label = "topRailIndicatorW",
    )
    val indicatorTileHeight by animateDpAsState(
        targetValue = selectedBounds?.let { with(density) { it.height.toDp() } } ?: 0.dp,
        animationSpec = indicatorAnimSpec,
        label = "topRailIndicatorTileHeight",
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (selectedBounds != null) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "topRailIndicatorAlpha",
    )

    val underlineThickness = (2f * paneScale).dp
    val underlineHorizontalInset = (6f * paneScale).dp

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(TopRailSurfaceColor)
                .padding(
                    start = (10f * paneScale).dp,
                    end = (10f * paneScale).dp,
                    top = (5f * paneScale).dp,
                    bottom = (2f * paneScale).dp,
                ),
    ) {
        if (selectedBounds != null) {
            Box(
                modifier =
                    Modifier
                        .offset(
                            x = indicatorX - with(density) { railScroll.value.toDp() } + underlineHorizontalInset,
                            y = indicatorTileHeight - underlineThickness,
                        )
                        .width((indicatorWidth - underlineHorizontalInset * 2).coerceAtLeast(0.dp))
                        .height(underlineThickness)
                        .graphicsLayer { alpha = indicatorAlpha }
                        .clip(RoundedCornerShape(underlineThickness / 2))
                        .background(DrawerAccent),
            )
        }

        Row(
            modifier = Modifier.horizontalScroll(railScroll),
            horizontalArrangement = Arrangement.spacedBy(TopRailTileSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopRailTile(
                icon = Icons.Outlined.Apps,
                label = stringResource(R.string.session_drawer_main_menu_title),
                active = false,
                selected = openPane == null,
                onClick = { onCursor(0, 0); onMenuClick() },
                tileKey = "menu",
                onBoundsChanged = { tileBounds["menu"] = it },
                highlighted = controllerActive && region == 0 && navIndex == 0,
            )
            activeSpecs.forEachIndexed { index, spec ->
                val item = state.items.first { it.itemId == spec.itemId }
                val key = item.itemId.toString()
                TopRailTile(
                    icon = spec.iconOverride ?: item.icon,
                    label = stringResource(spec.labelRes),
                    active = item.active,
                    selected = openPane == spec.pane,
                    onClick = { onCursor(0, index + 1); onTabClick(spec) },
                    tileKey = key,
                    onBoundsChanged = { tileBounds[key] = it },
                    highlighted = controllerActive && region == 0 && navIndex == index + 1,
                )
            }
        }
    }
}

@Composable
private fun TopRailTile(
    icon: ImageVector,
    label: String,
    active: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    tileKey: String,
    onBoundsChanged: (RailTileBounds) -> Unit,
    highlighted: Boolean = false,
) {
    val paneScale = LocalPaneScale.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value

    val minWidth = TopRailTileMinWidth * paneScale
    val horizontalPadding = TopRailTileHorizontalPadding * paneScale
    val topPadding = TopRailTileTopPadding * paneScale
    val bottomPadding = TopRailTileBottomPadding * paneScale
    val cornerRadius = (12f * paneScale).dp

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "topRailScale_$tileKey",
    )
    val bgColor by animateColorAsState(
        targetValue =
            when {
                highlighted -> DrawerFocusFill
                pressed && !selected -> PaneSurfacePressed
                else -> Color.Transparent
            },
        animationSpec = tween(120),
        label = "topRailBg_$tileKey",
    )
    val tint by animateColorAsState(
        targetValue =
            when {
                selected -> DrawerAccent
                active -> DrawerActiveAccent
                else -> DrawerTextPrimary
            },
        animationSpec = tween(120),
        label = "topRailTint_$tileKey",
    )

    val shape = RoundedCornerShape(cornerRadius)
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(highlighted, selected) {
        if (highlighted || selected) runCatching { bring.bringIntoView() }
    }
    Column(
        modifier =
            Modifier
                .defaultMinSize(minWidth = minWidth)
                .bringIntoViewRequester(bring)
                .onGloballyPositioned { coords ->
                    val bounds = coords.boundsInParent()
                    onBoundsChanged(
                        RailTileBounds(
                            offsetX = bounds.left,
                            width = bounds.width,
                            height = bounds.height,
                        ),
                    )
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(bgColor)
                .then(
                    if (highlighted) {
                        Modifier.chasingBorder(
                            cornerRadius = cornerRadius,
                            borderWidth = 1.5.dp,
                            animationDurationMs = 8200,
                        )
                    } else {
                        Modifier
                    },
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = topPadding,
                    bottom = bottomPadding,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size((22f * paneScale).dp),
        )
        Spacer(Modifier.height((2f * paneScale).dp))
        Text(
            text = label,
            color = DrawerTextPrimary,
            fontSize = (12f * paneScale).sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = 0.2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionCardGrid(
    state: XServerDrawerState,
    listener: XServerDrawerActionListener,
    cardsRevealed: Boolean,
    openSide: DrawerSide? = null,
    onDrawerLayoutChanged: (List<XServerDrawerItem>, Map<Int, DrawerSide>) -> Unit = { _, _ -> },
    onOpenTaskManager: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenTouch: () -> Unit,
    region: Int = 0,
    navIndex: Int = 0,
    activateSignal: Int = 0,
    onSetCardLayout: (Int, Int) -> Unit = { _, _ -> },
    onCursor: (Int, Int) -> Unit = { _, _ -> },
    controllerActive: Boolean = false,
) {
    val paneScale = LocalPaneScale.current
    val cards =
        state.items.filter {
            it.itemId !in RAIL_PANE_ITEM_IDS &&
                it.itemId !in PINNED_BOTTOM_ITEM_IDS &&
                (openSide == null || it.side == openSide || it.side == DrawerSide.BOTH)
        }
    var showRecordSettings by remember { mutableStateOf(false) }
    var showDrawerLayout by remember { mutableStateOf(false) }
    val gridContext = androidx.compose.ui.platform.LocalContext.current

    fun cardClick(item: XServerDrawerItem) {
        when (item.itemId) {
            R.id.main_menu_task_manager -> onOpenTaskManager()
            R.id.main_menu_logs -> onOpenLogs()
            R.id.main_menu_drawer_layout -> showDrawerLayout = true
            // Recording: stop if active, otherwise open the settings popup.
            R.id.main_menu_record ->
                if (item.active) listener.onActionSelected(item.itemId)
                else showRecordSettings = true
            R.id.main_menu_touch -> onOpenTouch()
            else -> listener.onActionSelected(item.itemId)
        }
    }

    LaunchedEffect(cards.size) { onSetCardLayout(cards.size, ActionCardColumns) }
    val lastActivate = remember { mutableStateOf(activateSignal) }
    LaunchedEffect(activateSignal) {
        if (activateSignal != lastActivate.value) {
            lastActivate.value = activateSignal
            if (region == 1) cards.getOrNull(navIndex)?.let { cardClick(it) }
        }
    }

    val verticalPadding = (10f * paneScale).dp
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val rows = ((cards.size + ActionCardColumns - 1) / ActionCardColumns).coerceAtLeast(1)
        val rowHeight =
            ((maxHeight - verticalPadding * 2 - ActionCardSpacing * (rows - 1)) / rows)
                .coerceAtLeast(ActionCardMinHeight * paneScale)
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = (10f * paneScale).dp, vertical = verticalPadding),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ActionCardSpacing),
                verticalArrangement = Arrangement.spacedBy(ActionCardSpacing),
                maxItemsInEachRow = ActionCardColumns,
            ) {
                cards.forEachIndexed { index, item ->
                    val label = railLabelResFor(item.itemId)?.let { stringResource(it) } ?: item.title
                    ActionCard(
                        item = item,
                        label = label,
                        revealIndex = index,
                        revealed = cardsRevealed,
                        highlighted = controllerActive && region == 1 && navIndex == index,
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(rowHeight),
                        onClick = { onCursor(1, index); cardClick(item) },
                    )
                }
                val trailing = (ActionCardColumns - cards.size % ActionCardColumns) % ActionCardColumns
                repeat(trailing) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }

    if (showRecordSettings) {
        RecordSettingsDialog(
            config = state.recordConfig,
            onDismiss = { showRecordSettings = false },
            onRecordNow = { fpsIndex, resIndex, quality, recordUI ->
                showRecordSettings = false
                listener.onRecordStart(fpsIndex, resIndex, quality, recordUI)
            },
        )
    }

    if (showDrawerLayout) {
        DrawerLayoutDialog(
            items =
                state.items.filter {
                    it.itemId !in RAIL_PANE_ITEM_IDS &&
                        it.itemId !in PINNED_BOTTOM_ITEM_IDS &&
                        it.itemId != R.id.main_menu_drawer_layout
                },
            paneSides = state.paneSides,
            onDismiss = { showDrawerLayout = false },
            onApply = { menuSides, paneSides ->
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(gridContext)
                    .edit()
                    .putString(
                        "drawer_item_sides",
                        menuSides.entries.joinToString(",") { "${it.key}:${it.value.name}" },
                    )
                    .putString(
                        "drawer_pane_sides",
                        paneSides.entries.joinToString(",") { "${it.key}:${it.value.name}" },
                    )
                    .apply()
                onDrawerLayoutChanged(
                    state.items.map { item ->
                        menuSides[item.itemId]?.let { item.copy(side = it) } ?: item
                    },
                    paneSides,
                )
                showDrawerLayout = false
            },
        )
    }
}

private fun loadDrawerItemSides(context: android.content.Context): Map<Int, DrawerSide> {
    val raw =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getString("drawer_item_sides", "")
            .orEmpty()
    if (raw.isBlank()) return emptyMap()
    return parseDrawerSides(raw)
}

private fun loadDrawerPaneSides(context: android.content.Context): Map<Int, DrawerSide> {
    val raw =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getString("drawer_pane_sides", "")
            .orEmpty()
    if (raw.isBlank()) return emptyMap()
    return parseDrawerSides(raw)
}

private fun parseDrawerSides(raw: String): Map<Int, DrawerSide> =
    raw.split(",").mapNotNull { seg ->
        val parts = seg.split(":")
        val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val side =
            when (parts.getOrNull(1)) {
                "RIGHT" -> DrawerSide.RIGHT
                "BOTH" -> DrawerSide.BOTH
                else -> DrawerSide.LEFT
            }
        id to side
    }.toMap()


@Composable
private fun DrawerLayoutDialog(
    items: List<XServerDrawerItem>,
    paneSides: Map<Int, DrawerSide> = emptyMap(),
    onDismiss: () -> Unit,
    onApply: (Map<Int, DrawerSide>, Map<Int, DrawerSide>) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var menuSides by remember { mutableStateOf(items.associate { it.itemId to it.side }) }
    var paneMap by remember { mutableStateOf(paneSides) }
    var activeTab by remember { mutableStateOf(0) }

    @Composable
    fun sideRow(title: String, current: DrawerSide, onPick: (DrawerSide) -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, fontSize = 13.sp, modifier = Modifier.weight(1f))
            DrawerSide.entries.forEach { option ->
                androidx.compose.material3.RadioButton(
                    selected = current == option,
                    onClick = { onPick(option) },
                )
                Text(
                    when (option) {
                        DrawerSide.LEFT -> "\u2190"
                        DrawerSide.RIGHT -> "\u2192"
                        DrawerSide.BOTH -> "\u2194"
                    },
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(4.dp))
            }
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.session_drawer_layout),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        stringResource(R.string.session_drawer_layout_items),
                        stringResource(R.string.session_drawer_layout_tabs),
                    ).forEachIndexed { index, label ->
                        androidx.compose.material3.FilterChip(
                            selected = activeTab == index,
                            onClick = { activeTab = index },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                if (activeTab == 0) {
                    items.forEach { item ->
                        sideRow(item.title, menuSides[item.itemId] ?: DrawerSide.LEFT) {
                            menuSides = menuSides + (item.itemId to it)
                        }
                    }
                } else {
                    RAIL_PANES.forEach { spec ->
                        val label =
                            runCatching { context.getString(spec.labelRes) }.getOrNull()
                                ?: spec.pane?.name ?: ""
                        sideRow(label, paneMap[spec.itemId] ?: DrawerSide.LEFT) {
                            // Rebuild the map instead of mutating in place: Compose only
                            // recomposes on a new reference, and in-place mutation of a plain
                            // MutableMap never re-renders the radio selection.
                            paneMap = paneMap + (spec.itemId to it)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_ui_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Button(onClick = { onApply(menuSides, paneMap) }) {
                        Text(stringResource(R.string.common_ui_ok))
                    }
                }
            }
        }
    }
}


@Composable
private fun ActionCard(
    item: XServerDrawerItem,
    label: String,
    revealIndex: Int,
    revealed: Boolean,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val paneScale = LocalPaneScale.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val enabled = item.enabled

    val staggerDelay = revealIndex * ActionCardRevealStaggerMs
    val revealAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec =
            tween(
                durationMillis = ActionCardRevealDurationMs,
                delayMillis = staggerDelay,
                easing = FastOutSlowInEasing,
            ),
        label = "actionCardReveal_${item.itemId}",
    )
    val revealOffsetY by animateDpAsState(
        targetValue = if (revealed) 0.dp else 8.dp,
        animationSpec =
            tween(
                durationMillis = ActionCardRevealDurationMs,
                delayMillis = staggerDelay,
                easing = FastOutSlowInEasing,
            ),
        label = "actionCardRevealOffset_${item.itemId}",
    )

    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.96f else 1f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "actionCardScale_${item.itemId}",
    )
    val bgColor by animateColorAsState(
        targetValue =
            when {
                !enabled -> Color(0x05FFFFFF)
                highlighted -> DrawerFocusFill
                pressed -> PaneInnerPressed
                else -> PaneInnerResting
            },
        animationSpec = tween(120),
        label = "actionCardBg_${item.itemId}",
    )
    val borderColor by animateColorAsState(
        targetValue =
            when {
                !enabled -> DisabledCardBorder
                item.active -> ActiveCardBorder
                else -> RestingCardBorder
            },
        animationSpec = tween(120),
        label = "actionCardBorder_${item.itemId}",
    )
    val tint by animateColorAsState(
        targetValue =
            when {
                !enabled -> DrawerTextSecondary.copy(alpha = 0.45f)
                item.active -> DrawerActiveAccent
                else -> DrawerTextPrimary
            },
        animationSpec = tween(120),
        label = "actionCardTint_${item.itemId}",
    )

    val cornerRadius = (12f * paneScale).dp
    val shape = RoundedCornerShape(cornerRadius)
    val topColor =
        Color(
            red = (bgColor.red + (1f - bgColor.red) * DrawerGradientLift).coerceIn(0f, 1f),
            green = (bgColor.green + (1f - bgColor.green) * DrawerGradientLift).coerceIn(0f, 1f),
            blue = (bgColor.blue + (1f - bgColor.blue) * DrawerGradientLift).coerceIn(0f, 1f),
            alpha = bgColor.alpha,
        )
    val cardBrush = Brush.verticalGradient(listOf(topColor, bgColor))
    Column(
        modifier =
            modifier
                .offset(y = revealOffsetY)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = revealAlpha
                }
                .clip(shape)
                .background(cardBrush)
                .border(1.dp, borderColor, shape)
                .then(
                    if (highlighted) {
                        Modifier.chasingBorder(
                            cornerRadius = cornerRadius,
                            borderWidth = 1.5.dp,
                            animationDurationMs = 8200,
                        )
                    } else {
                        Modifier
                    },
                )
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(vertical = (8f * paneScale).dp, horizontal = (4f * paneScale).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.title,
            tint = tint,
            modifier = Modifier.size((24f * paneScale).dp),
        )
        Spacer(Modifier.height((4f * paneScale).dp))
        Text(
            text = label,
            color = if (enabled) DrawerTextPrimary else DrawerTextSecondary.copy(alpha = 0.45f),
            fontSize = (13f * paneScale).sp,
            fontWeight = if (item.active) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = 0.2.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BottomActions(
    state: XServerDrawerState,
    listener: XServerDrawerActionListener,
    region: Int = 0,
    navIndex: Int = 0,
    activateSignal: Int = 0,
    onSetCount: (Int) -> Unit = {},
    onCursor: (Int, Int) -> Unit = { _, _ -> },
    controllerActive: Boolean = false,
) {
    val paneScale = LocalPaneScale.current
    val pause = state.items.firstOrNull { it.itemId == R.id.main_menu_pause }
    val exit = state.items.firstOrNull { it.itemId == R.id.main_menu_exit }
    val pauseIndex = if (pause != null) 0 else -1
    val exitIndex = if (exit != null) (if (pause != null) 1 else 0) else -1
    val count = (if (pause != null) 1 else 0) + (if (exit != null) 1 else 0)
    LaunchedEffect(count) { onSetCount(count) }
    val lastActivate = remember { mutableStateOf(activateSignal) }
    LaunchedEffect(activateSignal) {
        if (activateSignal != lastActivate.value) {
            lastActivate.value = activateSignal
            if (region == 2) {
                when (navIndex) {
                    pauseIndex -> pause?.let { listener.onActionSelected(it.itemId) }
                    exitIndex -> exit?.let { listener.onActionSelected(it.itemId) }
                }
            }
        }
    }
    if (pause == null && exit == null) return
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = (10f * paneScale).dp, vertical = (8f * paneScale).dp),
        horizontalArrangement = Arrangement.spacedBy((8f * paneScale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pause != null) {
            BottomActionButton(
                item = pause,
                label = pause.title,
                isExit = false,
                highlighted = controllerActive && region == 2 && navIndex == pauseIndex,
                modifier = Modifier.weight(1f),
                onClick = { onCursor(2, pauseIndex); listener.onActionSelected(pause.itemId) },
            )
        }
        if (exit != null) {
            BottomActionButton(
                item = exit,
                label = stringResource(R.string.common_ui_exit),
                isExit = true,
                highlighted = controllerActive && region == 2 && navIndex == exitIndex,
                modifier = Modifier.weight(1f),
                onClick = { onCursor(2, exitIndex); listener.onActionSelected(exit.itemId) },
            )
        }
    }
}

@Composable
private fun BottomActionButton(
    item: XServerDrawerItem,
    label: String,
    isExit: Boolean,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val paneScale = LocalPaneScale.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value

    val bgColor by animateColorAsState(
        targetValue =
            when {
                highlighted -> DrawerFocusFill
                isExit && pressed -> TileExitPressed
                isExit -> TileExitResting
                pressed -> PaneSurfacePressed
                else -> PaneInnerResting
            },
        animationSpec = tween(120),
        label = "bottomActionBg_${item.itemId}",
    )
    val borderColor =
        when {
            isExit -> GlassExitTint.copy(alpha = 0.34f)
            item.active -> ActiveCardBorder
            else -> RestingCardBorder
        }
    val tint =
        when {
            isExit -> GlassExitTint
            item.active -> DrawerActiveAccent
            else -> DrawerTextPrimary
        }

    val cornerRadius = (14f * paneScale).dp
    val shape = RoundedCornerShape(cornerRadius)
    Row(
        modifier =
            modifier
                .clip(shape)
                .background(bgColor)
                .border(1.dp, borderColor, shape)
                .then(
                    if (highlighted) {
                        Modifier.chasingBorder(
                            cornerRadius = cornerRadius,
                            borderWidth = 1.5.dp,
                            animationDurationMs = 8200,
                        )
                    } else {
                        Modifier
                    },
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = (12f * paneScale).dp, vertical = (10f * paneScale).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.title,
            tint = tint,
            modifier = Modifier.size((18f * paneScale).dp),
        )
        Spacer(Modifier.width((8f * paneScale).dp))
        Text(
            text = label,
            color = tint,
            fontSize = (13f * paneScale).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ThinDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BottomDividerColor),
    )
}

@Composable
internal fun DrawerResetRow(label: String, onClick: () -> Unit) {
    val paneScale = LocalPaneScale.current
    val shape = RoundedCornerShape((14f * paneScale).dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(PaneInnerResting)
                .border(1.dp, RestingCardBorder, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = (12f * paneScale).dp, vertical = (10f * paneScale).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = DrawerAccent,
            fontSize = (14f * paneScale).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun railLabelResFor(itemId: Int): Int? =
    when (itemId) {
        R.id.main_menu_keyboard -> R.string.session_drawer_rail_label_keyboard
        R.id.main_menu_input_controls -> R.string.session_drawer_rail_label_input_controls
        R.id.main_menu_relative_mouse_movement -> R.string.session_drawer_rail_label_relative_mouse
        R.id.main_menu_disable_mouse -> R.string.session_drawer_rail_label_mouse
        R.id.main_menu_toggle_fullscreen -> R.string.session_drawer_rail_label_fullscreen
        R.id.main_menu_pip_mode -> R.string.session_drawer_rail_label_pip
        R.id.main_menu_magnifier -> R.string.session_drawer_rail_label_magnifier
        R.id.main_menu_task_manager -> R.string.session_drawer_rail_label_task_manager
        R.id.main_menu_record -> R.string.session_drawer_rail_label_record
        R.id.main_menu_logs -> R.string.session_drawer_rail_label_logs
        else -> null
    }

@Composable
private fun PaneEnableRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    DrawerBooleanRow(
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}


internal class TaskManagerPopupPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gapPx
        val y =
            if (below + popupContentSize.height <= windowSize.height) {
                below
            } else {
                (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
            }
        return IntOffset(x, y)
    }
}

internal val NEW_TASK_PRESETS = listOf("Wfm.exe", "Winecfg.exe", "Regedit.exe", "Taskmgr.exe", "Services.exe")

@Composable
internal fun PaneSectionLabel(text: String) {
    val paneScale = LocalPaneScale.current
    Text(
        text = text,
        color = DrawerTextPrimary,
        fontSize = (14f * paneScale).sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
    )
}

@Composable
private fun DrawerMetricChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val paneScale = LocalPaneScale.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "drawerMetricScale_$label",
    )
    val bgColor by animateColorAsState(
        targetValue = if (pressed) PaneInnerPressed else PaneInnerResting,
        animationSpec = tween(140),
        label = "drawerMetricBg",
    )

    Column(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .fillMaxWidth()
                .clip(RoundedCornerShape((12f * paneScale).dp))
                .background(bgColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = (10f * paneScale).dp, vertical = (7f * paneScale).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label.uppercase(),
            color = DrawerTextSecondary,
            fontSize = (11f * paneScale).sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            color = DrawerTextPrimary,
            fontSize = (13f * paneScale).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DrawerReadOnlyValueRow(
    label: String,
    valueText: String,
) {
    val paneScale = LocalPaneScale.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape((10f * paneScale).dp))
                .background(PaneInnerResting)
                .border(1.dp, RestingCardBorder, RoundedCornerShape((10f * paneScale).dp))
                .padding(horizontal = (12f * paneScale).dp, vertical = (8f * paneScale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = DrawerTextPrimary,
            fontSize = (14f * paneScale).sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = valueText,
            color = DrawerAccent,
            fontSize = (13f * paneScale).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun DrawerSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueClick: (() -> Unit)? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val paneScale = LocalPaneScale.current
    Column(verticalArrangement = Arrangement.spacedBy((6f * paneScale).dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = DrawerTextPrimary,
                fontSize = (14f * paneScale).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            val valueModifier =
                if (onValueClick != null) {
                    Modifier
                        .clip(RoundedCornerShape((8f * paneScale).dp))
                        .background(PaneInnerResting)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onValueClick,
                        )
                        .padding(horizontal = (8f * paneScale).dp, vertical = (2f * paneScale).dp)
                } else {
                    Modifier
                }
            Text(
                text = valueText,
                color = DrawerAccent,
                fontSize = (13f * paneScale).sp,
                fontWeight = FontWeight.SemiBold,
                modifier = valueModifier,
            )
        }
        CompactSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    var sliderValue by remember(value) { mutableStateOf(value) }

    val sliderColors =
        SliderDefaults.colors(
            thumbColor = DrawerAccent,
            activeTrackColor = DrawerAccent,
            inactiveTrackColor = TileResting,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        )
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                onValueChange(it)
            },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(0.96f).requiredHeight(20.dp),
            colors = sliderColors,
            thumb = {
                Box(
                    modifier =
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(DrawerAccent),
                )
            },
            track = { sliderState ->
                val span = sliderState.valueRange.endInclusive - sliderState.valueRange.start
                val fraction =
                    if (span <= 0f) 0f else ((sliderState.value - sliderState.valueRange.start) / span).coerceIn(0f, 1f)
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(TileResting),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(DrawerAccent),
                    )
                }
            },
        )
    }
}

internal fun Float.snapToStep(
    step: Float,
    min: Float,
    max: Float,
): Float = (min + (((this - min) / step).roundToInt() * step)).coerceIn(min, max)

@Composable
internal fun DialogFocusButton(
    label: String,
    textColor: Color,
    backgroundColor: Color,
    borderColor: Color,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .widthIn(min = 84.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(backgroundColor)
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                .controllerFocusBorder(cornerRadius = 10.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun DrawerBooleanRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    val paneScale = LocalPaneScale.current
    val compact = paneScale < PaneScaleMin
    val rowInteractionSource = remember { MutableInteractionSource() }
    val pressed = rowInteractionSource.collectIsPressedAsState().value
    val switchInteractionSource = remember { MutableInteractionSource() }

    val bgColor by animateColorAsState(
        targetValue =
            when {
                pressed -> PaneInnerPressed
                else -> PaneInnerResting
            },
        animationSpec = tween(140),
        label = "drawerBooleanRowBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) ActiveCardBorder else RestingCardBorder,
        animationSpec = tween(140),
        label = "drawerBooleanRowBorder",
    )
    val cornerRadius = (14f * paneScale).dp
    val shape = RoundedCornerShape(cornerRadius)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bgColor)
                .border(1.dp, borderColor, shape)
                .clickable(
                    interactionSource = rowInteractionSource,
                    indication = null,
                ) { onCheckedChange(!checked) }
                .padding(horizontal = (12f * paneScale).dp, vertical = (8f * paneScale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DrawerTextPrimary,
                fontSize = (14f * paneScale).sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle ?: if (checked) {
                    stringResource(R.string.common_ui_enabled)
                } else {
                    stringResource(R.string.common_ui_disabled)
                },
                color = DrawerTextSecondary,
                fontSize = (12f * paneScale).sp,
            )
        }
        CompositionLocalProvider(
            LocalRippleConfiguration provides null,
            LocalMinimumInteractiveComponentSize provides if (compact) Dp.Unspecified else 48.dp,
        ) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                interactionSource = switchInteractionSource,
                colors = outlinedSwitchColors(DrawerAccent, DrawerTextSecondary),
            )
        }
    }
}

internal val RECORD_QUALITY_LABELS = listOf("Performance", "Balance", "Quality")

internal const val FPS_LIMITER_MIN = 15
internal const val FPS_LIMITER_DEFAULT = 60

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipFlow(content: @Composable () -> Unit) {
    val paneScale = LocalPaneScale.current
    val gap = (8f * paneScale).dp
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        content()
    }
}
