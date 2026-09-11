package com.winlator.cmod.feature.retro

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.shared.theme.SessionDrawerStyle
import com.winlator.cmod.shared.theme.WinNativeDanger
import com.winlator.cmod.shared.theme.WinNativeOutline
import com.winlator.cmod.shared.theme.WinNativeSurface
import com.winlator.cmod.shared.ui.outlinedSwitchColors
import com.winlator.cmod.shared.ui.widget.chasingBorder

private const val DrawerGradientLift = SessionDrawerStyle.GradientLift

private val DrawerAccent = SessionDrawerStyle.Accent
private val DrawerActiveAccent = SessionDrawerStyle.ActiveAccent
private val DrawerFocusFill = SessionDrawerStyle.FocusFill
private val DrawerTextPrimary = SessionDrawerStyle.TextPrimary
private val DrawerTextSecondary = SessionDrawerStyle.TextSecondary
private val RetroSheetColor = SessionDrawerStyle.Background
private val TopRailSurfaceColor = SessionDrawerStyle.TopRailSurface
private val PaneSurfacePressed = SessionDrawerStyle.PaneSurfacePressed
private val PaneInnerResting = SessionDrawerStyle.PaneInnerResting
private val PaneInnerPressed = SessionDrawerStyle.PaneInnerPressed
private val TileExitResting = SessionDrawerStyle.TileExitResting
private val TileExitPressed = SessionDrawerStyle.TileExitPressed
private val RestingCardBorder = SessionDrawerStyle.RestingCardBorder
private val ActiveCardBorder = SessionDrawerStyle.ActiveCardBorder
private val GlassExitTint = SessionDrawerStyle.GlassExitTint
private val DividerColor = SessionDrawerStyle.Divider

private val DrawerWidth = SessionDrawerStyle.Width
private val DrawerStartPadding = SessionDrawerStyle.StartPadding
private val DrawerVerticalPadding = SessionDrawerStyle.VerticalPadding

enum class RetroPane { DISPLAY, FRAMEGEN, SOUND, CONTROLS, HUD, SAVES, PERFORMANCE, MEMCARDS, NETWORK, SYSTEM }

class RetroRenamePrompt(
    val title: String,
    val initial: String,
    val onConfirm: (String?) -> Unit,
)

class RetroConfirmPrompt(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val dismissLabel: String,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

class RetroConflictPrompt(
    val message: String,
    val options: List<String>,
    val onKeepLocal: () -> Unit,
    val onPick: (Int) -> Unit,
)

data class RetroTabSpec(
    val pane: RetroPane?,
    val icon: ImageVector,
    val label: String,
)

sealed class RetroMenuEntry {
    class Action(
        val label: String,
        val icon: ImageVector,
        val active: Boolean = false,
        val danger: Boolean = false,
        val subtitle: String? = null,
        val onClick: () -> Unit,
    ) : RetroMenuEntry()

    class Toggle(
        val label: String,
        val subtitle: String? = null,
        val checked: Boolean,
        val onChange: (Boolean) -> Unit,
    ) : RetroMenuEntry()

    class Choice(
        val label: String,
        val values: List<String>,
        val selectedIndex: Int,
        val visible: Boolean = true,
        val onSelected: (Int) -> Unit,
    ) : RetroMenuEntry()

    class Radio(
        val label: String,
        val selected: Boolean,
        val onSelect: () -> Unit,
    ) : RetroMenuEntry()

    class Stepper(
        val label: String,
        val valueText: String,
        val onStep: (Int) -> Unit,
    ) : RetroMenuEntry()

    class TogglePair(
        val left: Toggle,
        val right: Toggle,
    ) : RetroMenuEntry()

    class Slider(
        val label: String,
        val valueText: String,
        val value: Float,
        val min: Float,
        val max: Float,
        val step: Float,
        val onChange: (Float) -> Unit,
    ) : RetroMenuEntry()

    class Chips(
        val label: String,
        val items: List<String>,
        val states: List<Boolean>,
        val onToggle: (Int) -> Unit,
    ) : RetroMenuEntry()

    class ColorPick(
        val label: String,
        val color: Int?,
        val onPick: (Int?) -> Unit,
    ) : RetroMenuEntry()

    class SaveSlot(
        val slot: Int,
        val title: String,
        val subtitle: String,
        val filled: Boolean,
        val onClick: () -> Unit,
        val onRename: () -> Unit,
    ) : RetroMenuEntry()

    class TextInput(
        val label: String,
        val value: String,
        val placeholder: String = "",
        val onOpen: () -> Unit,
    ) : RetroMenuEntry()
}

val RetroColorPalette: List<Int> =
    listOf(
        0xFFFFFFFF, 0xFFB0B4BC, 0xFF6B7280, 0xFF2A2A30, 0xFF000000,
        0xFFE53935, 0xFFFF7043, 0xFFFFB300, 0xFFFFF176, 0xFF7CB342,
        0xFF2F9E44, 0xFF26A69A, 0xFF29B6F6, 0xFF2E63C9, 0xFF5E35B1,
        0xFF8E24AA, 0xFFD81B60, 0xFF8D6E63,
    ).map { it.toInt() }

class RetroMenuController {
    var visible by mutableStateOf(false)
        private set
    var pane by mutableStateOf<RetroPane?>(null)
        private set
    var region by mutableIntStateOf(1)
    var railIndex by mutableIntStateOf(0)
    var contentIndex by mutableIntStateOf(0)
    var chipIndex by mutableIntStateOf(0)
    var bottomIndex by mutableIntStateOf(0)
    var controllerActive by mutableStateOf(false)
    var tabs by mutableStateOf<List<RetroTabSpec>>(emptyList())
    var entries by mutableStateOf<List<RetroMenuEntry>>(emptyList())
        private set
    var bottomEntries by mutableStateOf<List<RetroMenuEntry.Action>>(emptyList())
        private set
    var entriesProvider: ((RetroPane?) -> List<RetroMenuEntry>)? = null
    var paneContentProvider: ((RetroPane?) -> (@Composable () -> Unit)?)? = null
    var bottomProvider: (() -> List<RetroMenuEntry.Action>)? = null
    var renamePrompt by mutableStateOf<RetroRenamePrompt?>(null)
    var conflictPrompt by mutableStateOf<RetroConflictPrompt?>(null)
    var confirmPrompt by mutableStateOf<RetroConfirmPrompt?>(null)

    val gridColumns: Int
        get() = if (pane == null) 3 else 1

    fun open() {
        pane = null
        railIndex = 0
        region = 1
        contentIndex = 0
        bottomIndex = 0
        controllerActive = false
        rebuild()
        visible = true
    }

    fun close() {
        visible = false
    }

    fun showPane(target: RetroPane?) {
        pane = target
        rebuild()
        region = 1
        contentIndex = 0
        railIndex = tabs.indexOfFirst { it.pane == target }.coerceAtLeast(0)
    }

    fun rebuild() {
        entries = entriesProvider?.invoke(pane) ?: emptyList()
        bottomEntries = bottomProvider?.invoke() ?: emptyList()
        if (contentIndex >= entries.size) contentIndex = (entries.size - 1).coerceAtLeast(0)
        if (bottomIndex >= bottomEntries.size) bottomIndex = (bottomEntries.size - 1).coerceAtLeast(0)
    }

    private fun activate(direction: Int) {
        when (val entry = entries.getOrNull(contentIndex)) {
            is RetroMenuEntry.Action -> if (direction == 0) entry.onClick()
            is RetroMenuEntry.Toggle -> entry.onChange(!entry.checked)
            is RetroMenuEntry.TogglePair -> {
                val half = if (direction > 0) entry.right else entry.left
                half.onChange(!half.checked)
            }
            is RetroMenuEntry.Choice -> {
                val size = entry.values.size
                if (size > 0) {
                    val step = if (direction < 0) -1 else 1
                    entry.onSelected((entry.selectedIndex + step + size) % size)
                }
            }
            is RetroMenuEntry.Radio -> if (direction == 0) entry.onSelect()
            is RetroMenuEntry.Stepper -> entry.onStep(if (direction < 0) -1 else 1)
            is RetroMenuEntry.Slider ->
                if (direction != 0) {
                    entry.onChange((entry.value + direction * entry.step).coerceIn(entry.min, entry.max))
                }
            is RetroMenuEntry.Chips ->
                if (direction == 0) {
                    entry.onToggle(chipIndex.coerceIn(0, entry.items.size - 1))
                } else {
                    chipIndex = (chipIndex + direction + entry.items.size) % entry.items.size
                }
            is RetroMenuEntry.ColorPick -> {
                val step = if (direction < 0) -1 else 1
                val palette = RetroColorPalette
                val current = entry.color?.let { palette.indexOf(it) } ?: -1
                val span = palette.size + 1
                val next = ((current + 1 + step + span) % span) - 1
                entry.onPick(if (next < 0) null else palette[next])
            }
            is RetroMenuEntry.SaveSlot -> if (direction == 0) entry.onClick()
            is RetroMenuEntry.TextInput -> if (direction == 0) entry.onOpen()
            else -> {}
        }
    }

    private fun moveContent(delta: Int) {
        if (entries.isEmpty()) return
        val next = contentIndex + delta
        when {
            next < 0 -> region = 0
            next >= entries.size && pane == null -> region = 2
            next in entries.indices -> contentIndex = next
        }
    }

    private var axisLatched = false

    fun handleAxis(x: Float, y: Float): Boolean {
        if (!visible) return false
        val dz = 0.5f
        val dir =
            when {
                y < -dz -> KeyEvent.KEYCODE_DPAD_UP
                y > dz -> KeyEvent.KEYCODE_DPAD_DOWN
                x < -dz -> KeyEvent.KEYCODE_DPAD_LEFT
                x > dz -> KeyEvent.KEYCODE_DPAD_RIGHT
                else -> 0
            }
        if (dir == 0) {
            axisLatched = false
            return true
        }
        if (axisLatched) return true
        axisLatched = true
        handleKey(dir, KeyEvent.ACTION_DOWN)
        handleKey(dir, KeyEvent.ACTION_UP)
        return true
    }

    fun handleKey(
        keyCode: Int,
        action: Int,
    ): Boolean {
        if (!visible) return false
        val navKeys =
            setOf(
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_BUTTON_MODE,
                KeyEvent.KEYCODE_BUTTON_START,
            )
        if (keyCode !in navKeys) return true
        if (action == KeyEvent.ACTION_DOWN) {
            controllerActive = true
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP ->
                    when (region) {
                        1 -> moveContent(-gridColumns)
                        2 -> region = 1
                    }
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    when (region) {
                        0 -> if (entries.isNotEmpty()) region = 1
                        1 -> moveContent(gridColumns)
                    }
                KeyEvent.KEYCODE_DPAD_LEFT ->
                    when (region) {
                        0 -> railIndex = (railIndex - 1 + tabs.size) % tabs.size
                        1 -> if (gridColumns > 1) moveContent(-1) else activate(-1)
                        2 -> if (bottomEntries.isNotEmpty()) {
                            bottomIndex = (bottomIndex - 1 + bottomEntries.size) % bottomEntries.size
                        }
                    }
                KeyEvent.KEYCODE_DPAD_RIGHT ->
                    when (region) {
                        0 -> railIndex = (railIndex + 1) % tabs.size
                        1 -> if (gridColumns > 1) moveContent(1) else activate(1)
                        2 -> if (bottomEntries.isNotEmpty()) {
                            bottomIndex = (bottomIndex + 1) % bottomEntries.size
                        }
                    }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A ->
                    when (region) {
                        0 -> tabs.getOrNull(railIndex)?.let { showPane(it.pane) }
                        1 -> activate(0)
                        2 -> bottomEntries.getOrNull(bottomIndex)?.onClick?.invoke()
                    }
            }
        } else if (action == KeyEvent.ACTION_UP) {
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK,
                -> if (pane != null) showPane(null) else close()
            }
        }
        return true
    }
}

@Composable
fun RetroDrawerMenu(controller: RetroMenuController) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val sheetHeight =
            if (portrait) {
                minOf(maxWidth, maxHeight - DrawerVerticalPadding * 2)
            } else {
                maxHeight - DrawerVerticalPadding * 2
            }
        val paneScale =
            (sheetHeight.value / SessionDrawerStyle.PaneScaleReferenceHeightDp)
                .coerceIn(SessionDrawerStyle.PaneScaleMin, 1f)
        if (controller.visible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { controller.close() },
            )
        }

        val closedOffset = -(DrawerWidth + DrawerStartPadding + 8.dp)
        val sheetOffset by animateDpAsState(
            targetValue = if (controller.visible) 0.dp else closedOffset,
            animationSpec = tween(durationMillis = 200, easing = LinearEasing),
            label = "retroDrawerOffset",
        )
        if (sheetOffset > closedOffset) {
            Box(
                Modifier
                    .padding(start = DrawerStartPadding, top = DrawerVerticalPadding, bottom = DrawerVerticalPadding)
                    .height(sheetHeight)
                    .width(DrawerWidth)
                    .offset { androidx.compose.ui.unit.IntOffset(with(density) { sheetOffset.roundToPx() }, 0) }
                    .clip(RoundedCornerShape(20.dp))
                    .background(RetroSheetColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
            ) {
                Column(Modifier.fillMaxSize()) {
                    RetroTopRail(controller, paneScale)
                    ThinDivider()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (controller.pane == null) {
                            RetroActionGrid(controller, paneScale)
                        } else {
                            val custom = controller.paneContentProvider?.invoke(controller.pane)
                            if (custom != null) custom() else RetroPaneList(controller, paneScale)
                        }
                    }
                    if (controller.pane == null && controller.bottomEntries.isNotEmpty()) {
                        Column {
                            ThinDivider()
                            RetroBottomActions(controller, paneScale)
                        }
                    }
                }
            }
        }
        controller.renamePrompt?.let { prompt ->
            RetroRenameDialog(prompt) { controller.renamePrompt = null }
        }
        controller.conflictPrompt?.let { prompt ->
            RetroConflictDialog(prompt)
        }
        controller.confirmPrompt?.let { prompt ->
            RetroConfirmDialog(prompt)
        }
    }
}

private data class RailTileBounds(
    val offsetX: Float,
    val width: Float,
    val height: Float,
)

@Composable
private fun RetroTopRail(
    controller: RetroMenuController,
    paneScale: Float,
) {
    val density = LocalDensity.current
    val tileBounds = remember { mutableStateMapOf<Int, RailTileBounds>() }
    val railScroll = rememberScrollState()

    val selectedIndex =
        if (controller.controllerActive && controller.region == 0) {
            controller.railIndex
        } else {
            controller.tabs.indexOfFirst { it.pane == controller.pane }.coerceAtLeast(0)
        }
    val selectedBounds = tileBounds[selectedIndex]

    val indicatorX = selectedBounds?.let { with(density) { it.offsetX.toDp() } } ?: 0.dp
    val indicatorWidth = selectedBounds?.let { with(density) { it.width.toDp() } } ?: 0.dp
    val indicatorTileHeight = selectedBounds?.let { with(density) { it.height.toDp() } } ?: 0.dp
    val indicatorAlpha = if (selectedBounds != null) 1f else 0f

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
                            x = indicatorX - with(density) { railScroll.value.toDp() } + (6f * paneScale).dp,
                            y = indicatorTileHeight - (2f * paneScale).dp,
                        )
                        .width((indicatorWidth - (12f * paneScale).dp).coerceAtLeast(0.dp))
                        .height((2f * paneScale).dp)
                        .graphicsLayer { alpha = indicatorAlpha }
                        .clip(RoundedCornerShape(1.dp))
                        .background(DrawerAccent),
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(railScroll),
            horizontalArrangement = Arrangement.spacedBy((10f * paneScale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            controller.tabs.forEachIndexed { index, tab ->
                RetroRailTile(
                    icon = tab.icon,
                    label = tab.label,
                    selected =
                        if (controller.controllerActive && controller.region == 0) {
                            controller.railIndex == index
                        } else {
                            controller.pane == tab.pane
                        },
                    highlighted = controller.controllerActive && controller.region == 0 && controller.railIndex == index,
                    paneScale = paneScale,
                    onBoundsChanged = { tileBounds[index] = it },
                    onClick = {
                        controller.railIndex = index
                        controller.showPane(tab.pane)
                    },
                )
            }
        }
    }
}

@Composable
private fun RetroRailTile(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    highlighted: Boolean,
    paneScale: Float,
    onBoundsChanged: (RailTileBounds) -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "retroTileScale",
    )
    val bgColor by animateColorAsState(
        targetValue =
            when {
                highlighted -> DrawerFocusFill
                pressed && !selected -> PaneSurfacePressed
                else -> Color.Transparent
            },
        animationSpec = tween(120),
        label = "retroTileBg",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) DrawerAccent else DrawerTextPrimary,
        animationSpec = tween(120),
        label = "retroTileTint",
    )
    val cornerRadius = (12f * paneScale).dp
    val shape = RoundedCornerShape(cornerRadius)
    Column(
        modifier =
            Modifier
                .defaultMinSize(minWidth = (60f * paneScale).dp)
                .onGloballyPositioned { coords ->
                    val bounds = coords.boundsInParent()
                    onBoundsChanged(RailTileBounds(bounds.left, bounds.width, bounds.height))
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(bgColor)
                .then(
                    if (highlighted) {
                        Modifier.chasingBorder(cornerRadius = cornerRadius, borderWidth = 1.5.dp, animationDurationMs = 8200)
                    } else {
                        Modifier
                    },
                )
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(
                    start = (10f * paneScale).dp,
                    end = (10f * paneScale).dp,
                    top = (10f * paneScale).dp,
                    bottom = (7f * paneScale).dp,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size((22f * paneScale).dp))
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

@Composable
private fun RetroActionGrid(
    controller: RetroMenuController,
    paneScale: Float,
) {
    val actions = controller.entries
    val spacing = (8f * paneScale).dp
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = (10f * paneScale).dp, vertical = (10f * paneScale).dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        actions.chunked(controller.gridColumns).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                row.forEachIndexed { colIndex, entry ->
                    val flatIndex = rowIndex * controller.gridColumns + colIndex
                    if (entry is RetroMenuEntry.Action) {
                        RetroActionCard(
                            entry = entry,
                            highlighted =
                                controller.controllerActive &&
                                    controller.region == 1 &&
                                    controller.contentIndex == flatIndex,
                            paneScale = paneScale,
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                            onClick = {
                                controller.contentIndex = flatIndex
                                entry.onClick()
                            },
                        )
                    }
                }
                repeat(controller.gridColumns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RetroActionCard(
    entry: RetroMenuEntry.Action,
    highlighted: Boolean,
    paneScale: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "retroCardScale",
    )
    val bgColor by animateColorAsState(
        targetValue =
            when {
                highlighted -> DrawerFocusFill
                pressed -> PaneInnerPressed
                else -> PaneInnerResting
            },
        animationSpec = tween(120),
        label = "retroCardBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (entry.active) ActiveCardBorder else RestingCardBorder,
        animationSpec = tween(120),
        label = "retroCardBorder",
    )
    val tint =
        when {
            entry.danger -> GlassExitTint
            entry.active -> DrawerActiveAccent
            else -> DrawerTextPrimary
        }
    val cornerRadius = (12f * paneScale).dp
    val shape = RoundedCornerShape(cornerRadius)
    val topColor =
        Color(
            red = (bgColor.red + (1f - bgColor.red) * DrawerGradientLift).coerceIn(0f, 1f),
            green = (bgColor.green + (1f - bgColor.green) * DrawerGradientLift).coerceIn(0f, 1f),
            blue = (bgColor.blue + (1f - bgColor.blue) * DrawerGradientLift).coerceIn(0f, 1f),
            alpha = bgColor.alpha,
        )
    val shell =
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(topColor, bgColor)))
            .border(1.dp, borderColor, shape)
            .then(
                if (highlighted) {
                    Modifier.chasingBorder(cornerRadius = cornerRadius, borderWidth = 1.5.dp, animationDurationMs = 8200)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    if (entry.subtitle != null) {
        Row(
            modifier =
                shell
                    .defaultMinSize(minHeight = (72f * paneScale).dp)
                    .padding(horizontal = (12f * paneScale).dp, vertical = (10f * paneScale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = entry.icon,
                contentDescription = entry.label,
                tint = tint,
                modifier = Modifier.size((22f * paneScale).dp),
            )
            Spacer(Modifier.width((10f * paneScale).dp))
            Column(Modifier.weight(1f, fill = true)) {
                Text(
                    text = entry.label,
                    color = DrawerTextPrimary,
                    fontSize = (13f * paneScale).sp,
                    fontWeight = if (entry.active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 3,
                    softWrap = true,
                    overflow = TextOverflow.Clip,
                    lineHeight = (16f * paneScale).sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.subtitle,
                    color = DrawerTextSecondary,
                    fontSize = (11f * paneScale).sp,
                    maxLines = 3,
                    softWrap = true,
                    overflow = TextOverflow.Clip,
                    lineHeight = (14f * paneScale).sp,
                )
            }
        }
    } else {
        Column(
            modifier = shell,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = entry.icon,
                contentDescription = entry.label,
                tint = tint,
                modifier = Modifier.size((24f * paneScale).dp),
            )
            Spacer(Modifier.height((4f * paneScale).dp))
            Text(
                text = entry.label,
                color = DrawerTextPrimary,
                fontSize = (12f * paneScale).sp,
                fontWeight = if (entry.active) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = 0.2.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RetroPaneList(
    controller: RetroMenuController,
    paneScale: Float,
) {
    val columns = controller.gridColumns.coerceAtLeast(1)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = (10f * paneScale).dp, vertical = (10f * paneScale).dp),
        verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp),
    ) {
        controller.entries.chunked(columns).forEachIndexed { rowIndex, rowEntries ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy((8f * paneScale).dp),
            ) {
                rowEntries.forEachIndexed { colIndex, entry ->
                    val index = rowIndex * columns + colIndex
                    val highlighted =
                        controller.controllerActive &&
                            controller.region == 1 &&
                            controller.contentIndex == index
                    Box(Modifier.weight(1f)) {
                        when (entry) {
                            is RetroMenuEntry.Toggle ->
                                RetroBooleanRow(
                                    entry = entry,
                                    highlighted = highlighted,
                                    paneScale = paneScale,
                                    onClick = {
                                        controller.contentIndex = index
                                        entry.onChange(!entry.checked)
                                    },
                                )
                            is RetroMenuEntry.Choice ->
                                if (entry.visible) {
                                    RetroChoiceRow(
                                        entry = entry,
                                        highlighted = highlighted,
                                        paneScale = paneScale,
                                        onFocus = { controller.contentIndex = index },
                                    )
                                }
                            is RetroMenuEntry.TogglePair ->
                                RetroTogglePairRow(
                                    entry = entry,
                                    highlighted = highlighted,
                                    paneScale = paneScale,
                                    onFocus = { controller.contentIndex = index },
                                )
                            is RetroMenuEntry.Radio ->
                                RetroRadioRow(
                                    entry = entry,
                                    highlighted = highlighted,
                                    paneScale = paneScale,
                                    onClick = {
                                        controller.contentIndex = index
                                        entry.onSelect()
                                    },
                                )
                            is RetroMenuEntry.Stepper ->
                                RetroStepperRow(
                                    entry = entry,
                                    highlighted = highlighted,
                                    paneScale = paneScale,
                                    onFocus = { controller.contentIndex = index },
                                )
                            is RetroMenuEntry.Action ->
                                RetroActionCard(
                                    entry = entry,
                                    highlighted = highlighted,
                                    paneScale = paneScale,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(
                                                minHeight =
                                                    if (entry.subtitle != null) {
                                                        (68f * paneScale).dp
                                                    } else {
                                                        (56f * paneScale).dp
                                                    },
                                            ),
                                    onClick = {
                                        controller.contentIndex = index
                                        entry.onClick()
                                    },
                                )
                            is RetroMenuEntry.Slider ->
                                RetroSliderRow(
                                    entry = entry,
                                    highlighted = highlighted,
                                    paneScale = paneScale,
                                    onClick = { controller.contentIndex = index },
                                )
                            is RetroMenuEntry.Chips ->
                                RetroChipsGroup(
                                    entry = entry,
                                    highlighted = highlighted,
                                    chipFocus = if (highlighted) controller.chipIndex else -1,
                                    paneScale = paneScale,
                                    onChipClick = { chip ->
                                        controller.contentIndex = index
                                        controller.chipIndex = chip
                                        entry.onToggle(chip)
                                    },
                                )
                            is RetroMenuEntry.ColorPick ->
                                RetroColorRow(
                                    entry = entry,
                                    highlighted = highlighted,
                                    paneScale = paneScale,
                                    onFocus = { controller.contentIndex = index },
                                )
                            is RetroMenuEntry.SaveSlot ->
                                RetroSaveSlotRow(
                                    entry = entry,
                                    highlighted = highlighted,
                                    paneScale = paneScale,
                                    onClick = {
                                        controller.contentIndex = index
                                        entry.onClick()
                                    },
                                )
                            is RetroMenuEntry.TextInput ->
                                RetroTextInputRow(
                                    entry = entry,
                                    highlighted = highlighted,
                                    paneScale = paneScale,
                                    onClick = {
                                        controller.contentIndex = index
                                        entry.onOpen()
                                    },
                                )
                        }
                    }
                }
                repeat(columns - rowEntries.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RetroRowShell(
    highlighted: Boolean,
    activeBorder: Boolean,
    paneScale: Float,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val bgColor by animateColorAsState(
        targetValue =
            when {
                highlighted -> DrawerFocusFill
                pressed -> PaneInnerPressed
                else -> PaneInnerResting
            },
        animationSpec = tween(140),
        label = "retroRowBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (activeBorder) ActiveCardBorder else RestingCardBorder,
        animationSpec = tween(140),
        label = "retroRowBorder",
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
                .then(
                    if (highlighted) {
                        Modifier.chasingBorder(cornerRadius = cornerRadius, borderWidth = 1.5.dp, animationDurationMs = 8200)
                    } else {
                        Modifier
                    },
                )
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = (12f * paneScale).dp, vertical = (8f * paneScale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun RetroBooleanRow(
    entry: RetroMenuEntry.Toggle,
    highlighted: Boolean,
    paneScale: Float,
    onClick: () -> Unit,
) {
    RetroRowShell(highlighted = highlighted, activeBorder = entry.checked, paneScale = paneScale, onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                color = DrawerTextPrimary,
                fontSize = (14f * paneScale).sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.subtitle ?: if (entry.checked) stringResource(R.string.retro_scr_enabled) else stringResource(R.string.retro_scr_disabled),
                color = DrawerTextSecondary,
                fontSize = (12f * paneScale).sp,
            )
        }
        Switch(
            checked = entry.checked,
            onCheckedChange = entry.onChange,
            colors = outlinedSwitchColors(DrawerAccent, DrawerTextSecondary),
        )
    }
}

@Composable
private fun RetroTogglePairRow(
    entry: RetroMenuEntry.TogglePair,
    highlighted: Boolean,
    paneScale: Float,
    onFocus: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy((8f * paneScale).dp),
    ) {
        listOf(entry.left, entry.right).forEach { half ->
            Box(Modifier.weight(1f)) {
                RetroRowShell(
                    highlighted = highlighted,
                    activeBorder = half.checked,
                    paneScale = paneScale,
                    onClick = {
                        onFocus()
                        half.onChange(!half.checked)
                    },
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = half.label,
                            color = DrawerTextPrimary,
                            fontSize = (14f * paneScale).sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text =
                                half.subtitle ?: if (half.checked) {
                                    stringResource(R.string.retro_scr_enabled)
                                } else {
                                    stringResource(R.string.retro_scr_disabled)
                                },
                            color = if (half.checked) DrawerActiveAccent else DrawerTextSecondary,
                            fontSize = (12f * paneScale).sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RetroTextInputRow(
    entry: RetroMenuEntry.TextInput,
    highlighted: Boolean,
    paneScale: Float,
    onClick: () -> Unit,
) {
    RetroRowShell(highlighted = highlighted, activeBorder = entry.value.isNotBlank(), paneScale = paneScale, onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                color = DrawerTextPrimary,
                fontSize = (14f * paneScale).sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.value.ifBlank { entry.placeholder },
                color = if (entry.value.isBlank()) DrawerTextSecondary else DrawerActiveAccent,
                fontSize = (12f * paneScale).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RetroChoiceRow(
    entry: RetroMenuEntry.Choice,
    highlighted: Boolean,
    paneScale: Float,
    onFocus: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        RetroRowShell(
            highlighted = highlighted,
            activeBorder = false,
            paneScale = paneScale,
            onClick = {
                onFocus()
                expanded = true
            },
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.label,
                    color = DrawerTextPrimary,
                    fontSize = (14f * paneScale).sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.values.getOrNull(entry.selectedIndex) ?: "",
                    color = DrawerActiveAccent,
                    fontSize = (12f * paneScale).sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "▾",
                color = DrawerTextSecondary,
                fontSize = (16f * paneScale).sp,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = WinNativeSurface,
        ) {
            entry.values.forEachIndexed { index, value ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = value,
                            color = if (index == entry.selectedIndex) DrawerActiveAccent else DrawerTextPrimary,
                            fontSize = (13f * paneScale).sp,
                            fontWeight = if (index == entry.selectedIndex) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    },
                    onClick = {
                        expanded = false
                        entry.onSelected(index)
                    },
                )
            }
        }
    }
}

@Composable
private fun RetroStepperRow(
    entry: RetroMenuEntry.Stepper,
    highlighted: Boolean,
    paneScale: Float,
    onFocus: () -> Unit,
) {
    RetroRowShell(
        highlighted = highlighted,
        activeBorder = false,
        paneScale = paneScale,
        onClick = {
            onFocus()
            entry.onStep(1)
        },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                color = DrawerTextPrimary,
                fontSize = (14f * paneScale).sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.valueText,
                color = DrawerActiveAccent,
                fontSize = (12f * paneScale).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = "◂",
            color = DrawerTextSecondary,
            fontSize = (16f * paneScale).sp,
            modifier =
                Modifier
                    .clickable {
                        onFocus()
                        entry.onStep(-1)
                    }
                    .padding(horizontal = 6.dp),
        )
        Text(
            text = "▸",
            color = DrawerTextSecondary,
            fontSize = (16f * paneScale).sp,
            modifier =
                Modifier
                    .clickable {
                        onFocus()
                        entry.onStep(1)
                    }
                    .padding(horizontal = 6.dp),
        )
    }
}

@Composable
private fun RetroRadioRow(
    entry: RetroMenuEntry.Radio,
    highlighted: Boolean,
    paneScale: Float,
    onClick: () -> Unit,
) {
    RetroRowShell(highlighted = highlighted, activeBorder = entry.selected, paneScale = paneScale, onClick = onClick) {
        Text(
            text = entry.label,
            color = if (entry.selected) DrawerActiveAccent else DrawerTextPrimary,
            fontSize = (14f * paneScale).sp,
            fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (entry.selected) {
            Text(
                text = "✓",
                color = DrawerActiveAccent,
                fontSize = (14f * paneScale).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun RetroChipsGroup(
    entry: RetroMenuEntry.Chips,
    highlighted: Boolean,
    chipFocus: Int,
    paneScale: Float,
    onChipClick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp)) {
        Text(
            text = entry.label,
            color = DrawerTextSecondary,
            fontSize = (11f * paneScale).sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy((8f * paneScale).dp),
            verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp),
        ) {
            entry.items.forEachIndexed { index, item ->
                RetroHudChip(
                    label = item,
                    checked = entry.states.getOrElse(index) { false },
                    focused = highlighted && chipFocus == index,
                    paneScale = paneScale,
                    onClick = { onChipClick(index) },
                )
            }
        }
    }
}

@Composable
private fun RetroHudChip(
    label: String,
    checked: Boolean,
    focused: Boolean,
    paneScale: Float,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val bgColor by animateColorAsState(
        targetValue =
            when {
                focused -> DrawerFocusFill
                pressed -> PaneInnerPressed
                else -> PaneInnerResting
            },
        animationSpec = tween(140),
        label = "retroChipBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) DrawerAccent else RestingCardBorder,
        animationSpec = tween(140),
        label = "retroChipBorder",
    )
    val cornerRadius = (12f * paneScale).dp
    val shape = RoundedCornerShape(cornerRadius)
    Row(
        modifier =
            Modifier
                .clip(shape)
                .background(bgColor)
                .border(1.dp, borderColor, shape)
                .then(
                    if (focused) {
                        Modifier.chasingBorder(cornerRadius = cornerRadius, borderWidth = 1.5.dp, animationDurationMs = 8200)
                    } else {
                        Modifier
                    },
                )
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = (10f * paneScale).dp, vertical = (9f * paneScale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size((10f * paneScale).dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (checked) DrawerAccent else Color(0x14FFFFFF)),
        )
        Spacer(Modifier.width((8f * paneScale).dp))
        Text(
            text = label,
            color = DrawerTextPrimary,
            fontSize = (13f * paneScale).sp,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RetroSaveSlotRow(
    entry: RetroMenuEntry.SaveSlot,
    highlighted: Boolean,
    paneScale: Float,
    onClick: () -> Unit,
) {
    RetroRowShell(
        highlighted = highlighted,
        activeBorder = entry.filled,
        paneScale = paneScale,
        onClick = onClick,
    ) {
        Box(
            modifier =
                Modifier
                    .size((28f * paneScale).dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (entry.filled) DrawerAccent.copy(alpha = 0.24f) else Color(0x14FFFFFF)),
        ) {
            Text(
                text = entry.slot.toString(),
                color = if (entry.filled) DrawerActiveAccent else DrawerTextSecondary,
                fontSize = (12f * paneScale).sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Spacer(Modifier.width((10f * paneScale).dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                color = DrawerTextPrimary,
                fontSize = (13f * paneScale).sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.subtitle,
                color = if (entry.filled) DrawerActiveAccent else DrawerTextSecondary,
                fontSize = (11f * paneScale).sp,
            )
        }
        if (entry.filled) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.retro_scr_rename),
                tint = DrawerTextSecondary,
                modifier =
                    Modifier
                        .size((18f * paneScale).dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { entry.onRename() },
            )
        }
    }
}

@Composable
internal fun RetroRenameDialog(
    prompt: RetroRenamePrompt,
    onDismiss: () -> Unit,
) {
    var text by remember(prompt) { mutableStateOf(prompt.initial) }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .width(320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(WinNativeSurface)
                    .border(1.dp, WinNativeOutline, RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {}
                    .padding(16.dp),
        ) {
            Text(
                text = prompt.title,
                color = DrawerTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 48) text = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = DrawerTextPrimary, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.retro_scr_cancel),
                    color = DrawerTextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDismiss() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    text = stringResource(R.string.retro_scr_save),
                    color = DrawerActiveAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                prompt.onConfirm(text.trim().ifEmpty { null })
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun RetroConfirmDialog(prompt: RetroConfirmPrompt) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { prompt.onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .width(340.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(WinNativeSurface)
                    .border(1.dp, WinNativeOutline, RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {}
                    .padding(16.dp),
        ) {
            Text(
                text = prompt.title,
                color = DrawerTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = prompt.message,
                color = DrawerTextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DrawerFocusFill)
                            .border(1.dp, DrawerAccent, RoundedCornerShape(10.dp))
                            .clickable { prompt.onDismiss() }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = prompt.dismissLabel,
                        color = DrawerActiveAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PaneInnerResting)
                            .border(1.dp, RestingCardBorder, RoundedCornerShape(10.dp))
                            .clickable { prompt.onConfirm() }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = prompt.confirmLabel,
                        color = WinNativeDanger,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RetroConflictDialog(prompt: RetroConflictPrompt) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .width(340.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(WinNativeSurface)
                    .border(1.dp, WinNativeOutline, RoundedCornerShape(16.dp))
                    .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.retro_scr_cloud_save_conflict),
                color = DrawerTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = prompt.message,
                color = DrawerTextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                prompt.options.forEachIndexed { index, label ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(PaneInnerResting)
                                .border(1.dp, RestingCardBorder, RoundedCornerShape(10.dp))
                                .clickable { prompt.onPick(index) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = label,
                            color = DrawerTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DrawerFocusFill)
                        .border(1.dp, DrawerAccent, RoundedCornerShape(10.dp))
                        .clickable { prompt.onKeepLocal() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.retro_scr_keep_local_save),
                    color = DrawerActiveAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun RetroColorRow(
    entry: RetroMenuEntry.ColorPick,
    highlighted: Boolean,
    paneScale: Float,
    onFocus: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        RetroRowShell(
            highlighted = highlighted,
            activeBorder = entry.color != null,
            paneScale = paneScale,
            onClick = {
                onFocus()
                expanded = !expanded
            },
        ) {
            Text(
                text = entry.label,
                color = DrawerTextPrimary,
                fontSize = (14f * paneScale).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier =
                    Modifier
                        .size((20f * paneScale).dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(entry.color?.let { Color(it) } ?: Color(0x14FFFFFF))
                        .border(
                            1.dp,
                            if (entry.color != null) DrawerAccent else RestingCardBorder,
                            androidx.compose.foundation.shape.CircleShape,
                        ),
            ) {
                if (entry.color == null) {
                    Text(
                        text = stringResource(R.string.retro_scr_auto_color_abbrev),
                        color = DrawerTextSecondary,
                        fontSize = (10f * paneScale).sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Spacer(Modifier.width((8f * paneScale).dp))
            Text(
                text = if (expanded) "▴" else "▾",
                color = DrawerTextSecondary,
                fontSize = (16f * paneScale).sp,
            )
        }
        AnimatedVisibility(visible = expanded) {
            FlowRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = (6f * paneScale).dp, vertical = (8f * paneScale).dp),
                horizontalArrangement = Arrangement.spacedBy((7f * paneScale).dp),
                verticalArrangement = Arrangement.spacedBy((7f * paneScale).dp),
            ) {
                RetroColorSwatch(
                    color = null,
                    selected = entry.color == null,
                    paneScale = paneScale,
                ) {
                    entry.onPick(null)
                }
                RetroColorPalette.forEach { swatch ->
                    RetroColorSwatch(
                        color = swatch,
                        selected = entry.color == swatch,
                        paneScale = paneScale,
                    ) {
                        entry.onPick(swatch)
                    }
                }
            }
        }
    }
}

@Composable
private fun RetroColorSwatch(
    color: Int?,
    selected: Boolean,
    paneScale: Float,
    onClick: () -> Unit,
) {
    val shape = androidx.compose.foundation.shape.CircleShape
    Box(
        modifier =
            Modifier
                .size((26f * paneScale).dp)
                .clip(shape)
                .background(color?.let { Color(it) } ?: Color(0x14FFFFFF))
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) DrawerActiveAccent else RestingCardBorder,
                    shape,
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
    ) {
        if (color == null) {
            Text(
                text = stringResource(R.string.retro_scr_auto_color_abbrev),
                color = DrawerTextSecondary,
                fontSize = (11f * paneScale).sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun RetroSliderRow(
    entry: RetroMenuEntry.Slider,
    highlighted: Boolean,
    paneScale: Float,
    onClick: () -> Unit,
) {
    RetroRowShell(highlighted = highlighted, activeBorder = false, paneScale = paneScale, onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.label,
                    color = DrawerTextPrimary,
                    fontSize = (14f * paneScale).sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = entry.valueText,
                    color = DrawerActiveAccent,
                    fontSize = (12f * paneScale).sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val stepCount = (((entry.max - entry.min) / entry.step).toInt() - 1).coerceAtLeast(0)
            Slider(
                value = entry.value,
                onValueChange = { raw ->
                    val snapped =
                        (kotlin.math.round((raw - entry.min) / entry.step) * entry.step + entry.min)
                            .coerceIn(entry.min, entry.max)
                    if (snapped != entry.value) entry.onChange(snapped)
                },
                valueRange = entry.min..entry.max,
                steps = stepCount,
                colors =
                    SliderDefaults.colors(
                        thumbColor = DrawerAccent,
                        activeTrackColor = DrawerAccent,
                        inactiveTrackColor = WinNativeOutline.copy(alpha = 0.5f),
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent,
                    ),
                modifier = Modifier.fillMaxWidth().height((26f * paneScale).dp),
            )
        }
    }
}

@Composable
private fun RetroBottomActions(
    controller: RetroMenuController,
    paneScale: Float,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = (10f * paneScale).dp, vertical = (8f * paneScale).dp),
        horizontalArrangement = Arrangement.spacedBy((8f * paneScale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        controller.bottomEntries.forEachIndexed { index, entry ->
            RetroBottomActionButton(
                entry = entry,
                highlighted =
                    controller.controllerActive &&
                        controller.region == 2 &&
                        controller.bottomIndex == index,
                paneScale = paneScale,
                modifier = Modifier.weight(1f),
                onClick = {
                    controller.bottomIndex = index
                    entry.onClick()
                },
            )
        }
    }
}

@Composable
private fun RetroBottomActionButton(
    entry: RetroMenuEntry.Action,
    highlighted: Boolean,
    paneScale: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val bgColor by animateColorAsState(
        targetValue =
            when {
                highlighted -> DrawerFocusFill
                entry.danger && pressed -> TileExitPressed
                entry.danger -> TileExitResting
                pressed -> PaneSurfacePressed
                else -> PaneInnerResting
            },
        animationSpec = tween(120),
        label = "retroBottomBg",
    )
    val borderColor =
        when {
            entry.danger -> GlassExitTint.copy(alpha = 0.34f)
            entry.active -> ActiveCardBorder
            else -> RestingCardBorder
        }
    val tint =
        when {
            entry.danger -> GlassExitTint
            entry.active -> DrawerActiveAccent
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
                        Modifier.chasingBorder(cornerRadius = cornerRadius, borderWidth = 1.5.dp, animationDurationMs = 8200)
                    } else {
                        Modifier
                    },
                )
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = (12f * paneScale).dp, vertical = (10f * paneScale).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = entry.label,
            tint = tint,
            modifier = Modifier.size((18f * paneScale).dp),
        )
        Spacer(Modifier.width((8f * paneScale).dp))
        Text(
            text = entry.label,
            color = tint,
            fontSize = (13f * paneScale).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ThinDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DividerColor),
    )
}

object RetroDrawerTabs {
    fun build(
        context: android.content.Context,
        includePerformance: Boolean = false,
        includeNetplay: Boolean = false,
        includeSystem: Boolean = false,
    ): List<RetroTabSpec> {
        val tabs = mutableListOf<RetroTabSpec>()
        tabs += RetroTabSpec(null, Icons.Outlined.Apps, context.getString(R.string.retro_tab_menu))
        tabs += RetroTabSpec(RetroPane.DISPLAY, Icons.Outlined.Monitor, context.getString(R.string.retro_tab_display))
        tabs += RetroTabSpec(
            RetroPane.FRAMEGEN,
            Icons.Outlined.AutoAwesomeMotion,
            context.getString(R.string.session_drawer_frame_generation),
        )
        if (includePerformance) {
            tabs += RetroTabSpec(RetroPane.PERFORMANCE, Icons.Outlined.Bolt, context.getString(R.string.retro_ps2_tab_performance))
        }
        tabs += RetroTabSpec(RetroPane.HUD, Icons.Outlined.Speed, context.getString(R.string.retro_tab_hud))
        tabs += RetroTabSpec(RetroPane.SOUND, Icons.AutoMirrored.Outlined.VolumeUp, context.getString(R.string.retro_tab_sound))
        tabs += RetroTabSpec(RetroPane.CONTROLS, Icons.Outlined.SportsEsports, context.getString(R.string.retro_tab_controls))
        if (includeSystem) {
            tabs += RetroTabSpec(RetroPane.SYSTEM, Icons.Outlined.Tune, context.getString(R.string.retro_tab_system))
        }
        if (includeNetplay) {
            tabs += RetroTabSpec(RetroPane.NETWORK, Icons.Outlined.Wifi, context.getString(R.string.retro_tab_netplay))
        }
        return tabs
    }
}

object RetroDrawerIcons {
    val EditLayout = Icons.Outlined.Tune
    val Resume = Icons.Outlined.PlayArrow
    val Pause = Icons.Outlined.Pause
    val Save = Icons.Outlined.Save
    val Load = Icons.Outlined.Download
    val Reset = Icons.Outlined.RestartAlt
    val FastForward = Icons.Outlined.FastForward
    val Disc = Icons.Outlined.Album
    val Hud = Icons.Outlined.Speed
    val Achievements = Icons.Outlined.EmojiEvents
    val Cheats = Icons.Outlined.Bolt
    val Add = Icons.Outlined.Add
    val Play = Icons.Outlined.PlayArrow
    val Group = Icons.Outlined.Group
    val Link = Icons.Outlined.Link
    val Search = Icons.Outlined.Search
    val Exit = Icons.AutoMirrored.Outlined.ExitToApp
}
