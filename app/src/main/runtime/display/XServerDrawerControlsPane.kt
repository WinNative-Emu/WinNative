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

// Input-controls pane composables, split out of XServerDrawerMenu.kt (behavior-identical).

@Composable
internal fun InputControlsPaneContent(
    state: XServerDrawerState,
    listener: XServerDrawerActionListener,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val paneScale = computePaneScale(maxHeight, ControlsPaneScaleMin)
        val scrollState = rememberScrollState()
        val gcmEnabled = state.inputControlsGcmRumbleMode != "disabled"
        CompositionLocalProvider(LocalPaneScale provides paneScale) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(
                            start = (12f * paneScale).dp,
                            end = (12f * paneScale).dp,
                            top = (4f * paneScale).dp,
                            bottom = (12f * paneScale).dp,
                        ),
                verticalArrangement = Arrangement.spacedBy((10f * paneScale).dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp)) {
                    PaneSectionLabel(stringResource(R.string.input_controls_editor_select_profile))
                    InputControlsProfileSelector(
                        profileNames = state.inputControlsProfileNames,
                        selectedIndex = state.inputControlsSelectedProfileIndex,
                        onProfileSelected = listener::onInputControlsProfileSelected,
                        onEditClick = listener::onInputControlsEditClick,
                    )
                }

                if (state.inputControlsStyleNames.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp)) {
                        PaneSectionLabel(stringResource(R.string.input_controls_select_style))
                        InputControlsSimpleDropdown(
                            options = state.inputControlsStyleNames,
                            selectedIndex = state.inputControlsSelectedStyleIndex,
                            onSelected = listener::onInputControlsStyleSelected,
                        )
                    }
                }

                if (state.inputControlsAccentThemeNames.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp)) {
                        PaneSectionLabel(stringResource(R.string.input_controls_accent_theme))
                        InputControlsSimpleDropdown(
                            options = state.inputControlsAccentThemeNames,
                            selectedIndex = state.inputControlsSelectedAccentThemeIndex,
                            onSelected = listener::onInputControlsAccentThemeSelected,
                        )
                    }
                }

                NavBooleanRow(
                    title = stringResource(R.string.session_drawer_show_touchscreen_controls),
                    checked = state.inputControlsShowOverlay,
                    onCheckedChange = listener::onInputControlsShowOverlayChanged,
                )

                NavBooleanRow(
                    title = stringResource(R.string.input_controls_adaptive_joysticks),
                    subtitle = stringResource(R.string.input_controls_adaptive_joysticks_summary),
                    checked = state.inputControlsAdaptiveJoysticks,
                    onCheckedChange = listener::onInputControlsAdaptiveJoysticksChanged,
                )

                if (state.inputControlsShowOverlay) {
                    NavSliderRow(
                        label = stringResource(R.string.input_controls_editor_overlay_opacity),
                        valueText = "${(state.inputControlsOverlayOpacity * 100).toInt()}%",
                        value = state.inputControlsOverlayOpacity,
                        valueRange = 0.1f..1.0f,
                        steps = 17,
                        onValueChange = listener::onInputControlsOverlayOpacityChanged,
                    )
                    Spacer(Modifier.height(4.dp))

                    NavBooleanRow(
                        title = stringResource(R.string.input_controls_tap_to_click),
                        checked = state.inputControlsTapToClick,
                        onCheckedChange = listener::onInputControlsTapToClickChanged,
                    )
                }

                NavBooleanRow(
                    title = stringResource(R.string.settings_general_touchscreen_haptics),
                    checked = state.inputControlsTouchscreenHaptics,
                    onCheckedChange = listener::onInputControlsTouchscreenHapticsChanged,
                )

                NavBooleanRow(
                    title = stringResource(R.string.session_gamepad_enable_vibration),
                    checked = state.inputControlsGamepadVibration,
                    onCheckedChange = listener::onInputControlsGamepadVibrationChanged,
                )

                NavSliderRow(
                    label = "Mouse sensitivity scale",
                    valueText = "${Math.round(state.cursorSpeed * 100)}%",
                    value = state.cursorSpeed * 100f,
                    valueRange = 10f..300f,
                    steps = 0,
                    onValueChange = { listener.onCursorSpeedChanged(it / 100f) },
                    adjustStep = 5f,
                )

                val rsMapMode = state.screenTouchMode == 2
                val rsValue = if (rsMapMode) state.screenTouchRsSensitivity else state.rightStickSensitivity
                NavSliderRow(
                    label = stringResource(R.string.session_drawer_right_stick_sensitivity),
                    valueText = "${Math.round(rsValue * 100)}%",
                    value = rsValue * 100f,
                    valueRange = (if (rsMapMode) 25f else 10f)..200f,
                    steps = 0,
                    onValueChange = { listener.onRightStickSensitivityChanged(it / 100f) },
                    adjustStep = 5f,
                )

                LaunchedEffect(gcmEnabled) {
                    if (gcmEnabled) scrollState.animateScrollTo(Int.MAX_VALUE)
                }

                NavBooleanRow(
                    title = "GameSir Controller Rumble",
                    subtitle = "For Android-mode GameSir controllers only",
                    checked = gcmEnabled,
                    onCheckedChange = { enabled ->
                        listener.onInputControlsGcmRumbleModeChanged(if (enabled) "known" else "disabled")
                    },
                )

                if (gcmEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy((8f * paneScale).dp),
                        ) {
                            HUDToggleChip(
                                label = "Known",
                                checked = state.inputControlsGcmRumbleMode == "known",
                                onClick = { listener.onInputControlsGcmRumbleModeChanged("known") },
                                modifier = Modifier.weight(1f).paneNavItem(
                                    cornerRadius = (16f * paneScale).dp,
                                    onActivate = { listener.onInputControlsGcmRumbleModeChanged("known") },
                                ),
                            )
                            HUDToggleChip(
                                label = "All (experimental)",
                                checked = state.inputControlsGcmRumbleMode == "all",
                                onClick = { listener.onInputControlsGcmRumbleModeChanged("all") },
                                modifier = Modifier.weight(1f).paneNavItem(
                                    cornerRadius = (16f * paneScale).dp,
                                    onActivate = { listener.onInputControlsGcmRumbleModeChanged("all") },
                                ),
                            )
                        }
                        Text(
                            text = if (state.inputControlsGcmRumbleMode == "all")
                                "All GameSir devices"
                            else
                                "G8+ MFi, X5s, X3 Pro",
                            color = DrawerTextSecondary,
                            fontSize = (11f * paneScale).sp,
                        )
                    }
                }

                NavBooleanRow(
                    title = stringResource(R.string.input_controls_reverse_binding_order),
                    checked = state.inputControlsReverseBindingOrder,
                    onCheckedChange = listener::onInputControlsReverseBindingOrderChanged,
                )
            }
        }
    }
}

/** Compact dropdown for the Controls Style/Label-Theme rows; like [InputControlsProfileSelector] but without the edit button (built-in, non-editable choices). */
@Composable
internal fun InputControlsSimpleDropdown(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    val paneScale = LocalPaneScale.current
    var expanded by remember { mutableStateOf(false) }
    val selectedText = options.getOrElse(selectedIndex) { options.firstOrNull() ?: "" }
    val parentNav = LocalPaneNav.current
    val optionRegistry = remember { PaneNavRegistry() }
    LaunchedEffect(expanded) {
        if (expanded) {
            optionRegistry.reset()
            optionRegistry.controllerActive = true
            parentNav?.overlay = optionRegistry
            parentNav?.overlayClose = { expanded = false }
        } else if (parentNav?.overlay === optionRegistry) {
            parentNav.overlay = null
            parentNav.overlayClose = null
        }
    }

    val cornerRadius = (14f * paneScale).dp
    val shape = RoundedCornerShape(cornerRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val bgColor by animateColorAsState(
        targetValue = if (pressed) PaneInnerPressed else PaneInnerResting,
        animationSpec = tween(140),
        label = "inputControlsSimpleBg",
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(bgColor)
                    .border(1.dp, RestingCardBorder, shape)
                    .paneNavItem(
                        cornerRadius = cornerRadius,
                        onActivate = { expanded = true },
                        onAdjust = { dir ->
                            if (options.isNotEmpty()) {
                                onSelected(((selectedIndex + dir) % options.size + options.size) % options.size)
                            }
                        },
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) { expanded = true }
                    .padding(horizontal = (12f * paneScale).dp, vertical = (10f * paneScale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedText,
                color = DrawerTextPrimary,
                fontSize = (14f * paneScale).sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                tint = DrawerTextSecondary,
                modifier = Modifier.size((22f * paneScale).dp),
            )
        }

        InputControlsOptionsPopup(
            expanded = expanded,
            options = options,
            selectedIndex = selectedIndex,
            onSelected = onSelected,
            onDismiss = { expanded = false },
            optionRegistry = optionRegistry,
        )
    }
}

@Composable
internal fun InputControlsProfileSelector(
    profileNames: List<String>,
    selectedIndex: Int,
    onProfileSelected: (Int) -> Unit,
    onEditClick: () -> Unit,
) {
    val paneScale = LocalPaneScale.current
    var expanded by remember { mutableStateOf(false) }
    val disabledPlaceholder = stringResource(R.string.common_ui_disabled_placeholder)
    val selectedText = profileNames.getOrElse(selectedIndex) { disabledPlaceholder }
    val parentNav = LocalPaneNav.current
    val optionRegistry = remember { PaneNavRegistry() }
    LaunchedEffect(expanded) {
        if (expanded) {
            optionRegistry.reset()
            optionRegistry.controllerActive = true
            parentNav?.overlay = optionRegistry
            parentNav?.overlayClose = { expanded = false }
        } else if (parentNav?.overlay === optionRegistry) {
            parentNav.overlay = null
            parentNav.overlayClose = null
        }
    }

    val cornerRadius = (14f * paneScale).dp
    val shape = RoundedCornerShape(cornerRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val bgColor by animateColorAsState(
        targetValue = if (pressed) PaneInnerPressed else PaneInnerResting,
        animationSpec = tween(140),
        label = "inputControlsProfileBg",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy((8f * paneScale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(bgColor)
                        .border(1.dp, RestingCardBorder, shape)
                        .paneNavItem(cornerRadius = cornerRadius, onActivate = { expanded = true })
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                        ) { expanded = true }
                        .padding(horizontal = (12f * paneScale).dp, vertical = (10f * paneScale).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedText,
                    color = DrawerTextPrimary,
                    fontSize = (14f * paneScale).sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                    tint = DrawerTextSecondary,
                    modifier = Modifier.size((22f * paneScale).dp),
                )
            }

            InputControlsOptionsPopup(
                expanded = expanded,
                options = profileNames,
                selectedIndex = selectedIndex,
                onSelected = onProfileSelected,
                onDismiss = { expanded = false },
                optionRegistry = optionRegistry,
            )
        }

        Box(
            modifier =
                Modifier
                    .size((44f * paneScale).dp)
                    .clip(shape)
                    .background(PaneInnerResting)
                    .border(1.dp, RestingCardBorder, shape)
                    .paneNavItem(cornerRadius = cornerRadius, onActivate = onEditClick)
                    .clickable(onClick = onEditClick),
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
}

// Drawer-styled dropdown for the Controls selectors.
@Composable
internal fun InputControlsOptionsPopup(
    expanded: Boolean,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    optionRegistry: PaneNavRegistry,
) {
    if (!expanded) return
    val paneScale = LocalPaneScale.current
    val density = LocalDensity.current
    val gapPx = with(density) { (4f * paneScale).dp.roundToPx() }
    val shape = RoundedCornerShape((12f * paneScale).dp)
    val scrollState = rememberScrollState()
    Popup(
        popupPositionProvider = remember(gapPx) { TaskManagerPopupPositionProvider(gapPx) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        CompositionLocalProvider(LocalPaneNav provides optionRegistry) {
            Column(
                modifier =
                    Modifier
                        .widthIn(min = (160f * paneScale).dp, max = (280f * paneScale).dp)
                        .clip(shape)
                        .background(PaneSurfaceColor)
                        .border(1.dp, RestingCardBorder, shape)
                        .heightIn(max = (260f * paneScale).dp)
                        .verticalScroll(scrollState)
                        .padding((5f * paneScale).dp),
                verticalArrangement = Arrangement.spacedBy((4f * paneScale).dp),
            ) {
                options.forEachIndexed { index, name ->
                    InputControlsOptionItem(
                        label = name,
                        selected = index == selectedIndex,
                        onClick = {
                            onSelected(index)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun InputControlsOptionItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paneScale = LocalPaneScale.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val bgColor by animateColorAsState(
        targetValue = if (pressed) DrawerAccent.copy(alpha = 0.16f) else PaneInnerResting,
        animationSpec = tween(120),
        label = "inputControlsOptionItem",
    )
    val shape = RoundedCornerShape((8f * paneScale).dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bgColor)
                .border(1.dp, if (selected) ActiveCardBorder else RestingCardBorder, shape)
                .paneNavItem(cornerRadius = (8f * paneScale).dp, onActivate = onClick)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = (12f * paneScale).dp, vertical = (10f * paneScale).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((8f * paneScale).dp),
    ) {
        Text(
            text = label,
            color = if (selected) DrawerAccent else DrawerTextPrimary,
            fontSize = (13f * paneScale).sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = DrawerAccent,
                modifier = Modifier.size((16f * paneScale).dp),
            )
        }
    }
}

@Composable
internal fun ExpandableSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val paneScale = LocalPaneScale.current
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "expandableRotation",
    )
    val headerInteractionSource = remember { MutableInteractionSource() }
    val headerPressed = headerInteractionSource.collectIsPressedAsState().value
    val headerBg by animateColorAsState(
        targetValue =
            when {
                headerPressed -> PaneInnerPressed
                else -> PaneInnerResting
            },
        animationSpec = tween(140),
        label = "expandableHeaderBg",
    )
    val headerBorder by animateColorAsState(
        targetValue = if (expanded) DrawerAccent else RestingCardBorder,
        animationSpec = tween(140),
        label = "expandableHeaderBorder",
    )
    val headerShape = RoundedCornerShape((12f * paneScale).dp)
    Column(verticalArrangement = Arrangement.spacedBy((10f * paneScale).dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(headerShape)
                    .background(headerBg)
                    .border(1.dp, headerBorder, headerShape)
                    .paneNavItem(cornerRadius = (12f * paneScale).dp, onActivate = onToggle)
                    .clickable(
                        interactionSource = headerInteractionSource,
                        indication = null,
                        onClick = onToggle,
                    )
                    .padding(horizontal = (12f * paneScale).dp, vertical = (10f * paneScale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = if (expanded) DrawerAccent else DrawerTextPrimary,
                fontSize = (14f * paneScale).sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = if (expanded) DrawerAccent else DrawerTextSecondary,
                modifier =
                    Modifier
                        .size((18f * paneScale).dp)
                        .graphicsLayer { rotationZ = rotation },
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy((12f * paneScale).dp)) {
                content()
            }
        }
    }
}
