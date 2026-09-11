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
import com.winlator.cmod.shared.theme.WinNativeBackground
import com.winlator.cmod.shared.theme.WinNativeOutline
import com.winlator.cmod.shared.theme.WinNativePanel
import com.winlator.cmod.shared.theme.WinNativeSurface
import com.winlator.cmod.shared.theme.WinNativeTextPrimary
import com.winlator.cmod.shared.theme.WinNativeTextSecondary
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

// HUD pane composables, split out of XServerDrawerMenu.kt (behavior-identical).

@Composable
internal fun HUDPaneContent(
    state: XServerDrawerState,
    listener: XServerDrawerActionListener,
) {
    var activeEditor by remember { mutableStateOf<HUDMetricEditor?>(null) }
    val elementNames =
        listOf(
            stringResource(R.string.session_drawer_hud_element_fps),
            stringResource(R.string.session_drawer_hud_element_api),
            stringResource(R.string.session_drawer_hud_element_gpu),
            stringResource(R.string.session_drawer_hud_element_cpu),
            stringResource(R.string.session_drawer_hud_element_ram),
            stringResource(R.string.session_drawer_hud_element_battery),
            stringResource(R.string.session_drawer_hud_element_temp),
            stringResource(R.string.session_drawer_hud_element_graph),
            stringResource(R.string.session_drawer_hud_element_cpu_temp),
        )
    val elementOrder = listOf(1, 2, 3, 8, 4, 5, 6, 0, 7)
    val active =
        state.items.firstOrNull { it.itemId == R.id.main_menu_fps_monitor }?.active ?: false

    activeEditor?.let { editor ->
        HUDMetricInputDialog(
            editor = editor,
            initialPercent =
                when (editor) {
                    HUDMetricEditor.ALPHA -> (state.hudTransparency * 100).roundToInt()
                    HUDMetricEditor.BACKGROUND_ALPHA -> (state.hudBackgroundTransparency * 100).roundToInt()
                    HUDMetricEditor.SCALE -> (state.hudScale * 100).roundToInt()
                },
            onDismiss = { activeEditor = null },
            onConfirm = { enteredPercent ->
                activeEditor = null
                when (editor) {
                    HUDMetricEditor.ALPHA -> {
                        listener.onHUDTransparencyChanged(enteredPercent.coerceIn(editor.minPercent, editor.maxPercent) / 100f)
                    }
                    HUDMetricEditor.BACKGROUND_ALPHA -> {
                        listener.onHUDBackgroundTransparencyChanged(enteredPercent.coerceIn(editor.minPercent, editor.maxPercent) / 100f)
                    }
                    HUDMetricEditor.SCALE -> {
                        listener.onHUDScaleChanged(enteredPercent.coerceIn(editor.minPercent, editor.maxPercent) / 100f)
                    }
                }
            },
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val paneScale = computePaneScale(maxHeight)
        CompositionLocalProvider(LocalPaneScale provides paneScale) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = (12f * paneScale).dp, vertical = (12f * paneScale).dp),
                verticalArrangement = Arrangement.spacedBy((10f * paneScale).dp),
            ) {
            NavEnableRow(
                title = stringResource(R.string.session_drawer_fps_monitor),
                checked = active,
                onCheckedChange = { listener.onActionSelected(R.id.main_menu_fps_monitor) },
            )

            // Mango-style HUD toggle + settings gear, shown whether the HUD is on or off.
            var mangoSettingsOpen by remember { mutableStateOf(false) }
            Row(
                horizontalArrangement = Arrangement.spacedBy((8f * paneScale).dp),
                // Top-aligned so the gear's slot shares the toggle row's top edge:
                // same nav row (right selects the gear, down skips past it) and the
                // highlight hugs the gear itself like the Controls pane gear.
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier.weight(1f).paneNavItem(
                        cornerRadius = (14f * paneScale).dp,
                        onActivate = { listener.onMangoHudChanged(!state.mangoHudEnabled) },
                    ),
                ) {
                    DrawerBooleanRow(
                        title = stringResource(R.string.session_drawer_hud_mango_style),
                        checked = state.mangoHudEnabled,
                        onCheckedChange = listener::onMangoHudChanged,
                    )
                }
                val gearShape = RoundedCornerShape((12f * paneScale).dp)
                Box(
                    modifier =
                        Modifier
                            .size((44f * paneScale).dp)
                            .clip(gearShape)
                            .background(PaneInnerResting)
                            .border(1.dp, RestingCardBorder, gearShape)
                            .paneNavItem(
                                cornerRadius = (12f * paneScale).dp,
                                onActivate = { mangoSettingsOpen = true },
                            )
                            .clickable { mangoSettingsOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.common_ui_settings),
                        tint = DrawerTextPrimary,
                        modifier = Modifier.size((20f * paneScale).dp),
                    )
                }
            }
            if (mangoSettingsOpen) {
                MangoHudSettingsDialog(
                    elements = state.mangoHudElements,
                    hudAlpha = state.mangoHudAlpha,
                    bgAlpha = state.mangoHudBgAlpha,
                    hudScale = state.mangoHudScale,
                    locked = state.mangoHudLocked,
                    fexStatsAvailable = state.fexStatsAvailable,
                    onToggle = listener::onMangoHudElementToggled,
                    onAlphaChanged = listener::onMangoHudAlphaChanged,
                    onBgAlphaChanged = listener::onMangoHudBackgroundAlphaChanged,
                    onScaleChanged = listener::onMangoHudScaleChanged,
                    onLockChanged = listener::onMangoHudLockChanged,
                    onDismiss = { mangoSettingsOpen = false },
                )
            }

            if (active) {
                NavSliderRow(
                    label = stringResource(R.string.session_drawer_hud_alpha),
                    valueText = "${(state.hudTransparency * 100).toInt()}%",
                    value = state.hudTransparency,
                    valueRange = 0.1f..1f,
                    steps = 17,
                    onValueClick = { activeEditor = HUDMetricEditor.ALPHA },
                    onValueChange = { listener.onHUDTransparencyChanged(it.snapToStep(0.05f, 0.1f, 1f)) },
                )

                if (state.hudBackgroundAlphaEnabled) {
                    NavSliderRow(
                        label = stringResource(R.string.session_drawer_hud_background),
                        valueText = "${(state.hudBackgroundTransparency * 100).toInt()}%",
                        value = state.hudBackgroundTransparency,
                        valueRange = 0.1f..1f,
                        steps = 17,
                        onValueClick = { activeEditor = HUDMetricEditor.BACKGROUND_ALPHA },
                        onValueChange = { listener.onHUDBackgroundTransparencyChanged(it.snapToStep(0.05f, 0.1f, 1f)) },
                    )
                }

                NavSliderRow(
                    label = stringResource(R.string.session_drawer_hud_scale),
                    valueText = "${Math.round(state.hudScale * 100)}%",
                    value = state.hudScale,
                    valueRange = 0.3f..2.0f,
                    steps = 33,
                    onValueClick = { activeEditor = HUDMetricEditor.SCALE },
                    onValueChange = { listener.onHUDScaleChanged(it.snapToStep(0.05f, 0.3f, 2.0f)) },
                    adjustStep = 0.05f,
                )

                NavBooleanRow(
                    title = stringResource(R.string.session_drawer_hud_background_alpha),
                    checked = state.hudBackgroundAlphaEnabled,
                    onCheckedChange = listener::onHUDBackgroundAlphaDecoupledChanged,
                )

                NavBooleanRow(
                    title = stringResource(R.string.session_drawer_hud_frametime_numeric),
                    checked = state.frametimeNumericEnabled,
                    onCheckedChange = listener::onFrametimeNumericChanged,
                )

                Column(verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp)) {
                    PaneSectionLabel(stringResource(R.string.session_drawer_hud_elements))
                    ChipFlow {
                        elementOrder.forEach { index ->
                            HUDToggleChip(
                                label = elementNames[index],
                                checked = state.hudElements[index],
                                onClick = { listener.onHUDElementToggled(index, !state.hudElements[index]) },
                                modifier = Modifier.paneNavItem(
                                    cornerRadius = (16f * paneScale).dp,
                                    onActivate = { listener.onHUDElementToggled(index, !state.hudElements[index]) },
                                ),
                            )
                        }
                    }
                }

                NavBooleanRow(
                    title = stringResource(R.string.session_drawer_dual_series_battery),
                    checked = state.dualSeriesBatteryEnabled,
                    onCheckedChange = listener::onDualSeriesBatteryChanged,
                )
            }
            }
        }
    }
}

@Composable
internal fun HUDMetricInputDialog(
    editor: HUDMetricEditor,
    initialPercent: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(initialPercent.toString()) }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun submit() {
        val parsed = value.toIntOrNull() ?: initialPercent
        onConfirm(parsed.coerceIn(editor.minPercent, editor.maxPercent))
    }

    val focusRequester = remember { FocusRequester() }
    WinNativeDialogShell(
        onDismiss = onDismiss,
        title =
            when (editor) {
                HUDMetricEditor.ALPHA -> stringResource(R.string.session_drawer_hud_alpha_input_title)
                HUDMetricEditor.BACKGROUND_ALPHA -> stringResource(R.string.session_drawer_hud_background_alpha_input_title)
                HUDMetricEditor.SCALE -> stringResource(R.string.session_drawer_hud_scale_input_title)
            },
        maxWidth = 380.dp,
    ) {
      LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
      Column(
          modifier =
              Modifier
                  .controllerMenuInput(
                      onDismiss = onDismiss,
                      onStart = {
                          keyboardController?.hide()
                          submit()
                      },
                  ),
      ) {
        Text(
            text = stringResource(R.string.session_drawer_hud_input_hint, editor.minPercent, editor.maxPercent),
            color = DrawerTextSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { incoming -> value = incoming.filter(Char::isDigit) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            suffix = {
                Text(
                    text = "%",
                    color = DrawerTextSecondary,
                    fontSize = 13.sp,
                )
            },
            textStyle = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(color = DrawerTextPrimary),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DrawerAccent,
                    unfocusedBorderColor = DrawerOutline,
                    focusedTextColor = DrawerTextPrimary,
                    unfocusedTextColor = DrawerTextPrimary,
                    focusedContainerColor = DrawerBackground,
                    unfocusedContainerColor = DrawerBackground,
                    focusedLabelColor = DrawerTextSecondary,
                    unfocusedLabelColor = DrawerTextSecondary,
                    cursorColor = DrawerAccent,
                ),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        keyboardController?.hide()
                        submit()
                    },
                ),
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(DrawerOutline),
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            DialogFocusButton(
                label = stringResource(R.string.common_ui_cancel),
                textColor = DrawerTextPrimary,
                backgroundColor = PaneInnerResting,
                borderColor = RestingCardBorder,
                onClick = onDismiss,
            )
            DialogFocusButton(
                label = stringResource(R.string.common_ui_apply),
                textColor = DrawerAccent,
                backgroundColor = DrawerAccent.copy(alpha = 0.12f),
                borderColor = DrawerAccent.copy(alpha = 0.3f),
                focusRequester = focusRequester,
                onClick = {
                    keyboardController?.hide()
                    submit()
                },
            )
        }
      }
    }
}

@Composable
internal fun MangoHudSettingsDialog(
    elements: BooleanArray,
    hudAlpha: Float,
    bgAlpha: Float,
    hudScale: Float,
    locked: Boolean,
    fexStatsAvailable: Boolean,
    onToggle: (Int, Boolean) -> Unit,
    onAlphaChanged: (Float) -> Unit,
    onBgAlphaChanged: (Float) -> Unit,
    onScaleChanged: (Float) -> Unit,
    onLockChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Chip label position matches MangoHudView element indices.
    val labels =
        listOf(
            stringResource(R.string.mango_hud_element_gpu_load),
            stringResource(R.string.mango_hud_element_gpu_temp),
            stringResource(R.string.mango_hud_element_cpu_load),
            stringResource(R.string.mango_hud_element_cpu_temp),
            stringResource(R.string.mango_hud_element_ram),
            stringResource(R.string.mango_hud_element_battery),
            stringResource(R.string.mango_hud_element_lows),
            stringResource(R.string.mango_hud_element_graph),
            stringResource(R.string.mango_hud_element_engine),
            stringResource(R.string.mango_hud_element_vram),
            stringResource(R.string.mango_hud_element_cpu_mhz),
            stringResource(R.string.mango_hud_element_gpu_clock),
            stringResource(R.string.mango_hud_element_cores),
            stringResource(R.string.mango_hud_element_network),
            stringResource(R.string.mango_hud_element_swap),
            stringResource(R.string.mango_hud_element_resolution),
            stringResource(R.string.mango_hud_element_wine),
            stringResource(R.string.mango_hud_element_duration),
            stringResource(R.string.mango_hud_element_clock),
            stringResource(R.string.mango_hud_element_throttle),
            stringResource(R.string.mango_hud_group_fex),
            stringResource(R.string.mango_hud_element_fex_version),
            stringResource(R.string.mango_hud_element_fex_app_type),
            stringResource(R.string.mango_hud_element_fex_hot_threads),
            stringResource(R.string.mango_hud_element_fex_jit_load),
            stringResource(R.string.mango_hud_element_fex_sigbus),
            stringResource(R.string.mango_hud_element_fex_smc),
            stringResource(R.string.mango_hud_element_fex_softfloat),
            stringResource(R.string.mango_hud_element_fex_cache_miss),
        )
    // Display groups (label res -> element indices), decoupled from index order.
    val groups =
        listOf(
            R.string.session_drawer_hud_element_gpu to listOf(0, 1, 11, 9),
            R.string.session_drawer_hud_element_cpu to listOf(2, 3, 10, 12),
            R.string.mango_hud_group_memory to listOf(4, 14),
            R.string.session_drawer_hud_element_fps to listOf(6, 7, 8),
            R.string.mango_hud_group_system to listOf(5, 13, 15, 16, 17, 18, 19),
        ) +
        if (fexStatsAvailable) {
            listOf(R.string.mango_hud_group_fex to listOf(21, 22, 23, 24, 25, 26, 27, 28))
        } else {
            emptyList()
        }

    // stableCursor: track the selected slot by identity, not by spatial index —
    // scrolling reshuffles item positions and index-based tracking jumps to the
    // pinned header mid-scroll.
    val mangoNav = remember { SharedPaneNavRegistry().apply { stableCursor = true } }
    val shape = RoundedCornerShape(16.dp)
    val maxCardHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CompositionLocalProvider(SharedLocalPaneNav provides mangoNav) {
            DialogPaneNav(mangoNav, onDismiss = onDismiss, onStart = onDismiss)
            Box(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier =
                        Modifier
                            .widthIn(max = 400.dp)
                            .fillMaxWidth()
                            .heightIn(max = maxCardHeight)
                            .clip(shape)
                            .background(WinNativeSurface)
                            .border(1.dp, WinNativeOutline, shape)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Pinned header: title + always-visible close, no scrolling needed to exit.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = null,
                            tint = WinNativeTextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.session_drawer_hud_mango_settings),
                            color = DrawerTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        val closeShape = RoundedCornerShape(10.dp)
                        // Touch-only: a pinned slot inside a scrolling nav set makes the
                        // spatial rows reshuffle mid-scroll; controllers close with B.
                        Box(
                            modifier =
                                Modifier
                                    .size(32.dp)
                                    .clip(closeShape)
                                    .background(PaneInnerResting)
                                    .border(1.dp, RestingCardBorder, closeShape)
                                    .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.common_ui_close),
                                tint = DrawerTextPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(WinNativeOutline))

                    Column(
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().sharedPaneNavItem(
                                onActivate = { onLockChanged(!locked) },
                            ),
                        ) {
                            DrawerBooleanRow(
                                title = stringResource(R.string.mango_hud_lock),
                                subtitle = stringResource(R.string.mango_hud_lock_subtitle),
                                checked = locked,
                                onCheckedChange = onLockChanged,
                            )
                        }

                        groups.forEach { (labelRes, indices) ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PaneSectionLabel(stringResource(labelRes))
                                ChipFlow {
                                    indices.forEach { index ->
                                        HUDToggleChip(
                                            label = labels[index],
                                            checked = elements[index],
                                            onClick = { onToggle(index, !elements[index]) },
                                            modifier = Modifier.sharedPaneNavItem(
                                                onActivate = { onToggle(index, !elements[index]) },
                                                isEntry = index == 0,
                                            ),
                                        )
                                    }
                                }
                            }
                        }

                        // Local value drives the drag so a 1% move doesn't rebuild the whole drawer state.
                        var scaleLocal by remember(hudScale) { mutableStateOf(hudScale) }
                        Box(
                            modifier = Modifier.fillMaxWidth().sharedPaneNavItem(
                                onAdjust = { dir ->
                                    scaleLocal = (scaleLocal + dir * 0.05f).coerceIn(0.5f, 1.5f)
                                    onScaleChanged(scaleLocal)
                                },
                            ),
                        ) {
                            DrawerSliderRow(
                                label = stringResource(R.string.session_drawer_hud_scale),
                                valueText = "${Math.round(scaleLocal * 100)}%",
                                value = scaleLocal,
                                valueRange = 0.5f..1.5f,
                                steps = 0,
                                onValueChange = {
                                    scaleLocal = Math.round(it * 100) / 100f
                                    onScaleChanged(scaleLocal)
                                },
                            )
                        }
                        Box(
                            modifier = Modifier.fillMaxWidth().sharedPaneNavItem(
                                onAdjust = { dir ->
                                    onAlphaChanged((hudAlpha + dir * 0.05f).coerceIn(0.1f, 1f))
                                },
                            ),
                        ) {
                            DrawerSliderRow(
                                label = stringResource(R.string.session_drawer_hud_alpha),
                                valueText = "${(hudAlpha * 100).toInt()}%",
                                value = hudAlpha,
                                valueRange = 0.1f..1f,
                                steps = 17,
                                onValueChange = { onAlphaChanged(it.snapToStep(0.05f, 0.1f, 1f)) },
                            )
                        }
                        Box(
                            modifier = Modifier.fillMaxWidth().sharedPaneNavItem(
                                onAdjust = { dir ->
                                    onBgAlphaChanged((bgAlpha + dir * 0.05f).coerceIn(0f, 1f))
                                },
                            ),
                        ) {
                            DrawerSliderRow(
                                label = stringResource(R.string.session_drawer_hud_background),
                                valueText = "${(bgAlpha * 100).toInt()}%",
                                value = bgAlpha,
                                valueRange = 0f..1f,
                                steps = 19,
                                onValueChange = { onBgAlphaChanged(it.snapToStep(0.05f, 0f, 1f)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HUDToggleChip(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paneScale = LocalPaneScale.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val bgColor by animateColorAsState(
        targetValue =
            when {
                pressed -> PaneInnerPressed
                else -> PaneInnerResting
            },
        animationSpec = tween(140),
        label = "hudChipBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) DrawerAccent else RestingCardBorder,
        animationSpec = tween(140),
        label = "hudChipBorder",
    )
    val cornerRadius = (12f * paneScale).dp
    val shape = RoundedCornerShape(cornerRadius)
    val indicatorSize = (10f * paneScale).dp

    Row(
        modifier =
            modifier
                .clip(shape)
                .background(bgColor)
                .border(1.dp, borderColor, shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = (10f * paneScale).dp, vertical = (9f * paneScale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(indicatorSize)
                    .clip(CircleShape)
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
