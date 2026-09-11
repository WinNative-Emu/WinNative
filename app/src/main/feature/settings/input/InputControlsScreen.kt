@file:OptIn(ExperimentalMaterial3Api::class)

package com.winlator.cmod.feature.settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.ScreenRotationAlt
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.R
import com.winlator.cmod.runtime.input.controls.Binding
import com.winlator.cmod.runtime.input.ui.DragAction
import com.winlator.cmod.runtime.input.ui.HoldBehavior
import com.winlator.cmod.runtime.input.ui.InputControlsView
import com.winlator.cmod.runtime.input.ui.PanAction
import com.winlator.cmod.runtime.input.ui.TouchGestureConfig
import com.winlator.cmod.runtime.input.ui.ZoomAction
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.focus.rememberSettingsContentNav
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import com.winlator.cmod.shared.ui.outlinedSwitchColors
import kotlin.math.roundToInt

private val InputBg = Color(0xFF11111C)
private val InputCard = Color(0xFF1C1C2A)
private val InputSubcard = Color(0xFF161622)
private val InputField = Color(0xFF14141E)
private val InputOutline = Color(0xFF2A2A3A)
private val InputIconBox = Color(0xFF242434)
private val InputAccent = Color(0xFF1A9FFF)
private val InputNavHighlight = Color(0xFF4FC3F7)
private val InputTextPrimary = Color(0xFFF0F4FF)
private val InputTextSecondary = Color(0xFF7A8FA8)
private val InputDanger = Color(0xFFFF7A88)
private val InputTickHidden = Color.Transparent

private val InputCardCorner = 10.dp
private val InputCardHorizontalPadding = 12.dp
private val InputCardVerticalPadding = 10.dp
private val InputFieldCorner = 8.dp
private val InputCompactGap = 6.dp
private val InputItemGap = 8.dp
private val InputIconBoxSize = 38.dp
private val InputActionSize = 30.dp
private val InputSliderHeight = 24.dp
private const val InputSliderTrackScaleY = 0.72f
private val InputProfileIconBoxSize = 42.dp
private val InputProfileActionSize = 38.dp
private val InputProfileActionIconSize = 25.dp
private val InputProfileActionStartGap = 6.dp
private val InputProfileSelectorMaxWidth = 420.dp
private val InputPrimaryTextSize = 13.sp
private val InputSecondaryTextSize = 11.sp
private val InputSectionTextSize = 10.sp

data class InputControlsScreenState(
    val selectedProfileName: String? = null,
    val selectedProfileElementCount: Int = 0,
    val selectedProfileCanReset: Boolean = false,
    val overlayOpacity: Int = 40,
    val autoHideTouchOnController: Boolean = false,
    val gyroscopeEnabled: Boolean = false,
    val gyroscopeModeIndex: Int = 0,
    val gyroOrientationEnabled: Boolean = false,
    val gyroscopeActivatorLabel: String = "",
    val rightStickGyroEnabled: Boolean = false,
    val gyroMouseEnabled: Boolean = false,
    val gyroMouseScale: Int = 50,
    val gyroscopeExpanded: Boolean = false,
    val gyroXSensitivity: Int = 100,
    val gyroYSensitivity: Int = 100,
    val gyroSmoothing: Int = 10,
    val gyroDeadzone: Int = 5,
    val invertGyroX: Boolean = false,
    val invertGyroY: Boolean = false,
    val selectedGestureProfileName: String? = null,
    val gestureEditorExpanded: Boolean = false,
    val selectedGestureConfig: TouchGestureConfig = TouchGestureConfig(),
    val triggerTypeIndex: Int = 1,
    val triggerCardExpanded: Boolean = false,
    val triggerDescription: String = "",
    val controllerCards: List<InputControllerCardState> = emptyList(),
    val steamControllerEnabled: Boolean = false,
    val steamTrackpadModeIndex: Int = 1,
    val steamPaddleLabels: List<String> = emptyList(),
    val dialog: InputControlsDialogUiState = InputControlsDialogUiState.None,
)

sealed interface InputControlsDialogUiState {
    data object None : InputControlsDialogUiState

    data class Prompt(
        val title: String,
        val initialValue: String,
        val confirmLabel: String,
    ) : InputControlsDialogUiState

    data class Confirm(
        val message: String,
        val confirmLabel: String,
        val tone: InputDialogTone,
    ) : InputControlsDialogUiState

    data class Choice(
        val title: String,
        val options: List<String>,
        val selectedIndex: Int,
    ) : InputControlsDialogUiState

    data class MultiChoice(
        val title: String,
        val options: List<String>,
        val selectedIndices: Set<Int> = emptySet(),
        val disabledIndices: Set<Int> = emptySet(),
        val confirmLabel: String,
    ) : InputControlsDialogUiState
}

enum class InputDialogTone {
    Accent,
    Danger,
}

data class InputControllerCardState(
    val controllerId: String,
    val name: String,
    val bindingCount: Int,
    val connected: Boolean,
    val expanded: Boolean,
    val showBindings: Boolean,
    val bindings: List<InputControllerBindingState> = emptyList(),
)

data class InputControllerBindingState(
    val keyCode: Int,
    val label: String,
    val typeLabel: String,
    val bindingLabel: String,
)

data class InputControlsScreenActions(
    val onSelectProfile: () -> Unit,
    val onOpenEditor: () -> Unit,
    val onAddProfile: () -> Unit,
    val onEditProfile: () -> Unit,
    val onDuplicateProfile: () -> Unit,
    val onResetProfile: () -> Unit,
    val onRemoveProfile: () -> Unit,
    val onDismissDialog: () -> Unit,
    val onConfirmDialog: () -> Unit,
    val onPromptDialogConfirm: (String) -> Unit,
    val onChoiceDialogSelect: (Int) -> Unit,
    val onMultiChoiceDialogConfirm: (Set<Int>) -> Unit,
    val onOverlayOpacityChanged: (Int) -> Unit,
    val onAutoHideTouchOnControllerChanged: (Boolean) -> Unit,
    val onGyroscopeEnabledChanged: (Boolean) -> Unit,
    val onGyroscopeModeSelected: (Int) -> Unit,
    val onGyroOrientationModeChanged: (Boolean) -> Unit,
    val onGyroscopeActivatorClick: () -> Unit,
    val onRightStickGyroChanged: (Boolean) -> Unit,
    val onGyroMouseEnabledChanged: (Boolean) -> Unit,
    val onGyroMouseScaleChanged: (Int) -> Unit,
    val onGyroscopeExpandedChanged: (Boolean) -> Unit,
    val onGyroXSensitivityChanged: (Int) -> Unit,
    val onGyroYSensitivityChanged: (Int) -> Unit,
    val onGyroSmoothingChanged: (Int) -> Unit,
    val onGyroDeadzoneChanged: (Int) -> Unit,
    val onInvertGyroXChanged: (Boolean) -> Unit,
    val onInvertGyroYChanged: (Boolean) -> Unit,
    val onResetGyroPreview: () -> Unit,
    val onAttachGyroPreview: (InputControlsView) -> Unit,
    val onDetachGyroPreview: () -> Unit,
    val onSelectGestureProfile: () -> Unit,
    val onToggleGestureEditor: () -> Unit,
    val onGestureConfigChanged: (TouchGestureConfig) -> Unit,
    val onNewGestureProfile: () -> Unit,
    val onRenameGestureProfile: () -> Unit,
    val onDuplicateGestureProfile: () -> Unit,
    val onDeleteGestureProfile: () -> Unit,
    val onImportGestureProfile: () -> Unit,
    val onExportGestureProfile: () -> Unit,
    val onTriggerTypeSelected: (Int) -> Unit,
    val onTriggerCardExpandedChanged: (Boolean) -> Unit,
    val onImportProfile: () -> Unit,
    val onDownloadProfile: () -> Unit,
    val onExportProfile: () -> Unit,
    val onControllerExpandedToggle: (String) -> Unit,
    val onRemoveController: (String) -> Unit,
    val onBindingTypeClick: (String, Int) -> Unit,
    val onBindingValueClick: (String, Int) -> Unit,
    val onRemoveBinding: (String, Int) -> Unit,
    val onOpenControllerTest: () -> Unit,
    val onSteamControllerEnabledChanged: (Boolean) -> Unit,
    val onSteamTrackpadModeSelected: (Int) -> Unit,
    val onSteamPaddleClick: (Int) -> Unit,
)

@Composable
fun InputControlsScreen(
    state: InputControlsScreenState,
    actions: InputControlsScreenActions,
    bridge: SettingsNavBridge? = null,
) {
    val layoutDirection = LocalLayoutDirection.current
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val navBarStartPadding = navBarPadding.calculateStartPadding(layoutDirection)
    val navBarEndPadding = navBarPadding.calculateEndPadding(layoutDirection)
    val navBarBottomPadding = navBarPadding.calculateBottomPadding()
    val contentNav = rememberSettingsContentNav(bridge)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(InputBg),
    ) {
        CompositionLocalProvider(LocalPaneNav provides contentNav) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = 12.dp + navBarStartPadding,
                            top = 12.dp,
                            end = 12.dp + navBarEndPadding,
                        ),
                verticalArrangement = Arrangement.spacedBy(InputCompactGap),
            ) {
                SectionLabel(stringResource(R.string.input_controls_auto_hide_section))
                AutoHideTouchCard(state, actions)
                ProfileCard(state, actions)
                SectionLabel(stringResource(R.string.input_controls_editor_input_profiles_section))
                ActionCard(
                    icon = Icons.Outlined.FileDownload,
                    title = stringResource(R.string.input_controls_editor_import_profile),
                    onClick = actions.onImportProfile,
                )
                ActionCard(
                    icon = Icons.Outlined.Download,
                    title = stringResource(R.string.common_ui_download),
                    onClick = actions.onDownloadProfile,
                )
                ActionCard(
                    icon = Icons.Outlined.FileUpload,
                    title = stringResource(R.string.input_controls_editor_export_profile),
                    onClick = actions.onExportProfile,
                )
                SectionLabel(stringResource(R.string.input_controls_editor_overlay_opacity))
                OverlayOpacityCard(state, actions)
                SectionLabel(stringResource(R.string.session_gamepad_trigger_type))
                TriggerTypeCard(state, actions)
                SectionLabel(stringResource(R.string.session_gyroscope_title))
                GyroscopeCard(state, actions)
                SectionLabel(stringResource(R.string.session_gesture_profile_section))
                GestureProfileCard(state, actions)
                if (state.gestureEditorExpanded) {
                    CardShell {
                        GestureEditorBody(state.selectedGestureConfig) { actions.onGestureConfigChanged(it) }
                    }
                }
                ActionCard(
                    icon = Icons.Outlined.FileDownload,
                    title = stringResource(R.string.gesture_profile_import),
                    onClick = actions.onImportGestureProfile,
                )
                ActionCard(
                    icon = Icons.Outlined.FileUpload,
                    title = stringResource(R.string.gesture_profile_export),
                    onClick = actions.onExportGestureProfile,
                )
                SectionLabel(stringResource(R.string.controller_test_title))
                ActionCard(
                    icon = Icons.Outlined.SportsEsports,
                    title = stringResource(R.string.controller_test_card_title),
                    onClick = actions.onOpenControllerTest,
                )
                SectionLabel(stringResource(R.string.steam_controller_section))
                SteamControllerCard(state, actions)
                SectionLabel(stringResource(R.string.session_gamepad_external_controllers))
                if (state.controllerCards.isEmpty()) {
                    EmptyStateCard(stringResource(R.string.common_ui_no_items_to_display))
                } else {
                    state.controllerCards.forEach { controller ->
                        key(controller.controllerId) {
                            ControllerCard(controller, actions)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp + navBarBottomPadding))
            }
        }

        when (val dialog = state.dialog) {
            InputControlsDialogUiState.None -> {
                Unit
            }

            is InputControlsDialogUiState.Prompt -> {
                InputPromptDialog(
                    title = dialog.title,
                    initialValue = dialog.initialValue,
                    confirmLabel = dialog.confirmLabel,
                    onDismiss = actions.onDismissDialog,
                    onConfirm = actions.onPromptDialogConfirm,
                )
            }

            is InputControlsDialogUiState.Confirm -> {
                InputConfirmDialog(
                    message = dialog.message,
                    confirmLabel = dialog.confirmLabel,
                    tone = dialog.tone,
                    onDismiss = actions.onDismissDialog,
                    onConfirm = actions.onConfirmDialog,
                )
            }

            is InputControlsDialogUiState.Choice -> {
                InputChoiceDialog(
                    title = dialog.title,
                    options = dialog.options,
                    selectedIndex = dialog.selectedIndex,
                    onDismiss = actions.onDismissDialog,
                    onSelected = actions.onChoiceDialogSelect,
                )
            }

            is InputControlsDialogUiState.MultiChoice -> {
                InputMultiChoiceDialog(
                    title = dialog.title,
                    options = dialog.options,
                    selectedIndices = dialog.selectedIndices,
                    disabledIndices = dialog.disabledIndices,
                    confirmLabel = dialog.confirmLabel,
                    onDismiss = actions.onDismissDialog,
                    onConfirm = actions.onMultiChoiceDialogConfirm,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = InputTextSecondary,
        fontSize = InputSectionTextSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun CardShell(
    onClick: (() -> Unit)? = null,
    horizontalPadding: Dp = InputCardHorizontalPadding,
    verticalPadding: Dp = InputCardVerticalPadding,
    content: @Composable () -> Unit,
) {
    val clickableModifier =
        if (onClick != null) {
            Modifier.paneNavItem(
                cornerRadius = InputCardCorner,
                onActivate = onClick,
                highlightColor = InputNavHighlight,
                tapToSelect = true,
            )
        } else {
            Modifier
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(InputCardCorner))
                .background(InputCard)
                .border(1.dp, InputOutline, RoundedCornerShape(InputCardCorner))
                .then(clickableModifier)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) {
        content()
    }
}

@Composable
private fun IconBox(
    image: ImageVector,
    tint: Color,
) {
    Box(
        modifier =
            Modifier
                .size(InputIconBoxSize)
                .clip(RoundedCornerShape(InputFieldCorner))
                .background(InputIconBox),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = image,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun IconActionButton(
    image: ImageVector,
    contentDescription: String,
    tint: Color = InputTextSecondary,
    onClick: () -> Unit,
    size: Dp = InputActionSize,
    iconSize: Dp = if (size <= 28.dp) 14.dp else if (size >= InputProfileActionSize) 18.dp else 16.dp,
) {
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(RoundedCornerShape(InputFieldCorner))
                .background(InputSubcard)
                .border(1.dp, InputOutline, RoundedCornerShape(InputFieldCorner))
                .paneNavItem(
                    cornerRadius = InputFieldCorner,
                    onActivate = onClick,
                    highlightColor = InputNavHighlight,
                    tapToSelect = true,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = image,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun DecorativeChevronBox() {
    Box(
        modifier =
            Modifier
                .size(InputActionSize)
                .clip(RoundedCornerShape(InputFieldCorner))
                .background(InputSubcard)
                .border(1.dp, InputOutline, RoundedCornerShape(InputFieldCorner)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = InputTextSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Switch(
        modifier = modifier.scale(0.78f).focusProperties { canFocus = false },
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors =
            outlinedSwitchColors(
                accentColor = InputAccent,
                textSecondaryColor = InputTextSecondary,
            ),
    )
}

@Composable
private fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(InputCompactGap)) {
        options.forEachIndexed { index, label ->
            Chip(
                text = label,
                selected = index == selectedIndex,
                onClick = { onSelected(index) },
            )
        }
    }
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(InputFieldCorner))
                .background(if (selected) InputAccent.copy(alpha = 0.18f) else InputSubcard)
                .border(
                    1.dp,
                    if (selected) InputAccent.copy(alpha = 0.35f) else InputOutline,
                    RoundedCornerShape(InputFieldCorner),
                ).paneNavItem(
                    cornerRadius = InputFieldCorner,
                    onActivate = onClick,
                    highlightColor = InputNavHighlight,
                    tapToSelect = true,
                ).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) InputAccent else InputTextSecondary,
            fontSize = InputSecondaryTextSize,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SelectionPill(
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .heightIn(min = 30.dp)
                .clip(RoundedCornerShape(InputFieldCorner))
                .background(InputField)
                .border(1.dp, InputOutline, RoundedCornerShape(InputFieldCorner))
                .paneNavItem(
                    cornerRadius = InputFieldCorner,
                    onActivate = onClick,
                    highlightColor = InputNavHighlight,
                    tapToSelect = true,
                ).padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = InputTextPrimary,
            fontSize = InputPrimaryTextSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = InputTextSecondary,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun EmptyStateCard(text: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(InputCardCorner))
                .background(InputCard)
                .border(1.dp, InputOutline, RoundedCornerShape(InputCardCorner))
                .padding(horizontal = 14.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = InputTextSecondary,
            fontSize = InputPrimaryTextSize,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun InputDialogShell(
    onDismiss: () -> Unit,
    title: String? = null,
    maxWidth: Dp = 440.dp,
    content: @Composable () -> Unit,
) {
    val registry = remember { PaneNavRegistry() }
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        DialogPaneNav(registry, onDismiss = onDismiss)
        CompositionLocalProvider(LocalPaneNav provides registry) {
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .widthIn(max = maxWidth)
                            .fillMaxWidth()
                            .heightIn(max = maxHeight)
                            .clip(RoundedCornerShape(16.dp))
                            .background(InputCard)
                            .border(1.dp, InputOutline, RoundedCornerShape(16.dp))
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                    ) {
                        if (title != null) {
                            Text(
                                text = title,
                                color = InputTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(InputOutline),
                            )
                            Spacer(Modifier.height(14.dp))
                        }
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSelectorIconBox(tint: Color) {
    Box(
        modifier =
            Modifier
                .size(InputProfileIconBoxSize)
                .clip(RoundedCornerShape(InputFieldCorner))
                .background(InputIconBox),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(2) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(tint),
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier =
                            Modifier
                                .width(18.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(tint.copy(alpha = 0.95f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun InputFixedFooterDialogShell(
    onDismiss: () -> Unit,
    title: String? = null,
    maxWidth: Dp = 440.dp,
    maxBodyHeight: Dp = 320.dp,
    footer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val registry = remember { PaneNavRegistry() }
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        DialogPaneNav(registry, onDismiss = onDismiss)
        CompositionLocalProvider(LocalPaneNav provides registry) {
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val responsiveBodyMaxHeight =
                    minOf(
                        maxBodyHeight,
                        (maxHeight - 176.dp).coerceAtLeast(140.dp),
                    )

                Box(
                    modifier =
                        Modifier
                            .widthIn(max = maxWidth)
                            .fillMaxWidth()
                            .heightIn(max = maxHeight)
                            .clip(RoundedCornerShape(16.dp))
                            .background(InputCard)
                            .border(1.dp, InputOutline, RoundedCornerShape(16.dp))
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (title != null) {
                            Text(
                                text = title,
                                color = InputTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(InputOutline),
                            )
                            Spacer(Modifier.height(14.dp))
                        }

                        val scrollState = rememberScrollState()
                        val density = LocalDensity.current
                        var viewportTop by remember { mutableStateOf(0f) }
                        var viewportHeight by remember { mutableStateOf(0) }
                        LaunchedEffect(registry.activeRow, registry.activeCol, viewportHeight) {
                            if (!registry.controllerActive) return@LaunchedEffect
                            val bounds = registry.activeItemBounds() ?: return@LaunchedEffect
                            val margin = with(density) { 16.dp.toPx() }
                            val vpBottom = viewportTop + viewportHeight
                            val delta =
                                when {
                                    bounds.second + margin > vpBottom -> bounds.second + margin - vpBottom
                                    bounds.first - margin < viewportTop -> bounds.first - margin - viewportTop
                                    else -> 0f
                                }
                            if (delta != 0f) runCatching { scrollState.animateScrollBy(delta) }
                        }
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = responsiveBodyMaxHeight)
                                    .onGloballyPositioned {
                                        viewportTop = it.positionInWindow().y
                                        viewportHeight = it.size.height
                                    }
                                    .verticalScroll(scrollState),
                        ) {
                            content()
                        }

                        Spacer(Modifier.height(14.dp))
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(InputOutline),
                        )
                        Spacer(Modifier.height(14.dp))
                        footer()
                    }
                }
            }
        }
    }
}

@Composable
private fun InputDialogButton(
    label: String,
    primary: Boolean,
    textColor: Color,
    backgroundColor: Color? = null,
    borderColor: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val resolvedBackground = backgroundColor ?: if (primary) InputAccent else InputSubcard
    val resolvedBorder = borderColor ?: if (primary) InputAccent.copy(alpha = 0.5f) else InputOutline
    val disabledBackground = InputSubcard.copy(alpha = 0.96f)
    val disabledBorder = InputOutline.copy(alpha = 0.9f)
    val nav = LocalPaneNav.current
    val clickModifier =
        when {
            !enabled -> Modifier
            nav != null ->
                Modifier.paneNavItem(
                    cornerRadius = 10.dp,
                    onActivate = onClick,
                    highlightColor = InputNavHighlight,
                    tapToSelect = true,
                )
            else ->
                Modifier
                    .paneNavItem(cornerRadius = 10.dp, onActivate = onClick)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
        }

    Box(
        modifier =
            Modifier
                .widthIn(min = 84.dp)
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (enabled) resolvedBackground else disabledBackground)
                .border(
                    1.dp,
                    if (enabled) resolvedBorder else disabledBorder,
                    RoundedCornerShape(10.dp),
                ).then(clickModifier)
                .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) textColor else InputTextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun InputConfirmDialog(
    message: String,
    confirmLabel: String,
    tone: InputDialogTone,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    InputDialogShell(
        onDismiss = onDismiss,
        maxWidth = 420.dp,
    ) {
        Text(
            text = message,
            color = InputTextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(InputOutline),
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            InputDialogButton(
                label = stringResource(R.string.common_ui_cancel),
                primary = false,
                textColor = InputTextPrimary,
                onClick = onDismiss,
            )
            InputDialogButton(
                label = confirmLabel,
                primary = tone == InputDialogTone.Accent,
                textColor = if (tone == InputDialogTone.Danger) InputDanger else InputTextPrimary,
                onClick = onConfirm,
            )
        }
    }
}

@Composable
private fun InputPromptDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(title, initialValue) { mutableStateOf(initialValue) }
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val fieldFocus = remember { FocusRequester() }

    InputDialogShell(
        onDismiss = onDismiss,
        title = title,
        maxWidth = 440.dp,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .requiredHeightIn(min = 46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(InputField)
                    .border(
                        if (focused) 1.5.dp else 1.dp,
                        if (focused) InputAccent else InputOutline,
                        RoundedCornerShape(12.dp),
                    ).paneNavItem(
                        cornerRadius = 12.dp,
                        onActivate = { runCatching { fieldFocus.requestFocus() } },
                        highlightColor = InputNavHighlight,
                        isEntry = true,
                    ).padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = InputTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                cursorBrush = SolidColor(InputAccent),
                interactionSource = interactionSource,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            onConfirm(text.trim())
                        },
                    ),
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
            )
        }
        Spacer(Modifier.height(16.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(InputOutline),
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            InputDialogButton(
                label = stringResource(R.string.common_ui_cancel),
                primary = false,
                textColor = InputTextPrimary,
                onClick = onDismiss,
            )
            InputDialogButton(
                label = confirmLabel,
                primary = true,
                textColor = InputAccent,
                backgroundColor = InputAccent.copy(alpha = 0.12f),
                borderColor = InputAccent.copy(alpha = 0.3f),
                onClick = { onConfirm(text.trim()) },
            )
        }
    }
}

@Composable
private fun InputChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelected: (Int) -> Unit,
) {
    InputFixedFooterDialogShell(
        onDismiss = onDismiss,
        title = title,
        maxWidth = 430.dp,
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                InputDialogButton(
                    label = stringResource(R.string.common_ui_cancel),
                    primary = false,
                    textColor = InputTextPrimary,
                    onClick = onDismiss,
                )
            }
        },
    ) {
        val focusTarget = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(InputField)
                            .border(
                                1.dp,
                                InputOutline,
                                RoundedCornerShape(12.dp),
                            ).paneNavItem(
                                cornerRadius = 12.dp,
                                onActivate = { onSelected(index) },
                                highlightColor = InputNavHighlight,
                                tapToSelect = true,
                                isEntry = index == focusTarget,
                            ).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .border(
                                    1.4.dp,
                                    if (selected) InputAccent else InputOutline,
                                    RoundedCornerShape(9.dp),
                                ).background(if (selected) InputAccent.copy(alpha = 0.12f) else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(7.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(InputAccent),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = option,
                        color = InputTextPrimary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (options.isEmpty()) {
                Text(
                    text = stringResource(R.string.common_ui_no_items_to_display),
                    color = InputTextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun InputMultiChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndices: Set<Int>,
    disabledIndices: Set<Int>,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (Set<Int>) -> Unit,
) {
    var currentSelection by remember(title, options, selectedIndices) {
        mutableStateOf(selectedIndices - disabledIndices)
    }
    val hasSelection = currentSelection.isNotEmpty()

    InputFixedFooterDialogShell(
        onDismiss = onDismiss,
        title = title,
        maxWidth = 430.dp,
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                InputDialogButton(
                    label = stringResource(R.string.common_ui_cancel),
                    primary = false,
                    textColor = InputTextPrimary,
                    onClick = onDismiss,
                )
                InputDialogButton(
                    label = confirmLabel,
                    primary = true,
                    textColor = InputAccent,
                    backgroundColor = InputAccent.copy(alpha = 0.12f),
                    borderColor = InputAccent.copy(alpha = 0.3f),
                    enabled = hasSelection,
                    onClick = { onConfirm(currentSelection) },
                )
            }
        },
    ) {
        val focusTarget = options.indices.firstOrNull { it !in disabledIndices } ?: -1
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { index, option ->
                val selected = index in currentSelection
                val disabled = index in disabledIndices
                val rowModifier =
                    if (disabled) {
                        Modifier
                    } else {
                        Modifier.paneNavItem(
                            cornerRadius = 12.dp,
                            onActivate = {
                                currentSelection =
                                    if (selected) {
                                        currentSelection - index
                                    } else {
                                        currentSelection + index
                                    }
                            },
                            highlightColor = InputNavHighlight,
                            tapToSelect = true,
                            isEntry = index == focusTarget,
                        )
                    }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    disabled -> InputField.copy(alpha = 0.55f)
                                    selected -> InputAccent.copy(alpha = 0.08f)
                                    else -> InputField
                                },
                            ).border(
                                1.dp,
                                when {
                                    disabled -> InputOutline.copy(alpha = 0.6f)
                                    selected -> InputAccent.copy(alpha = 0.24f)
                                    else -> InputOutline
                                },
                                RoundedCornerShape(12.dp),
                            ).then(rowModifier)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!disabled) {
                        Box(
                            modifier =
                                Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .border(
                                        1.4.dp,
                                        if (selected) InputAccent else InputOutline,
                                        RoundedCornerShape(5.dp),
                                    ).background(
                                        if (selected) InputAccent.copy(alpha = 0.12f) else Color.Transparent,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(InputAccent),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        text = option,
                        color = if (disabled) InputTextSecondary else InputTextPrimary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (disabled) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.common_ui_installed),
                            color = InputTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            if (options.isEmpty()) {
                Text(
                    text = stringResource(R.string.common_ui_no_items_to_display),
                    color = InputTextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun sliderColors() =
    SliderDefaults.colors(
        thumbColor = InputAccent,
        activeTrackColor = InputAccent,
        inactiveTrackColor = InputOutline,
        activeTickColor = InputTickHidden,
        inactiveTickColor = InputTickHidden,
    )

@Composable
private fun InputSliderTrack(sliderState: SliderState) {
    SliderDefaults.Track(
        sliderState = sliderState,
        colors = sliderColors(),
        modifier = Modifier.scale(scaleX = 1f, scaleY = InputSliderTrackScaleY),
    )
}

private fun snapToStep(
    value: Float,
    step: Int,
    min: Int,
    max: Int,
): Int {
    val rounded = (value / step).roundToInt() * step
    return rounded.coerceIn(min, max)
}

@Composable
private fun ProfileCard(
    state: InputControlsScreenState,
    actions: InputControlsScreenActions,
) {
    val selectionInteraction = remember { MutableInteractionSource() }
    val selectorPressed by selectionInteraction.collectIsPressedAsState()
    val selectorTint by animateFloatAsState(
        targetValue = if (selectorPressed) 1f else 0f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "profileSelectorPressed",
    )

    CardShell(
        horizontalPadding = 10.dp,
        verticalPadding = 8.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ProfileSelectorRow(
                state = state,
                selectionInteraction = selectionInteraction,
                selectorTint = selectorTint,
                selectorPressed = selectorPressed,
                onClick = actions.onSelectProfile,
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = InputProfileSelectorMaxWidth),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileEditButton(
                    onClick = actions.onOpenEditor,
                    modifier = Modifier.padding(start = InputProfileActionStartGap),
                )
                ProfileActionRow(
                    state = state,
                    actions = actions,
                    modifier = Modifier.padding(start = InputProfileActionStartGap),
                )
            }
        }
    }
}

@Composable
private fun ProfileSelectorRow(
    state: InputControlsScreenState,
    selectionInteraction: MutableInteractionSource,
    selectorTint: Float,
    selectorPressed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(InputCardCorner))
                .background(
                    Color(
                        red = InputField.red,
                        green = InputField.green,
                        blue = InputField.blue,
                        alpha = 0.28f + (0.34f * selectorTint),
                    ),
                ).border(
                    1.dp,
                    Color(
                        red = InputOutline.red + ((InputAccent.red - InputOutline.red) * selectorTint * 0.45f),
                        green = InputOutline.green + ((InputAccent.green - InputOutline.green) * selectorTint * 0.45f),
                        blue = InputOutline.blue + ((InputAccent.blue - InputOutline.blue) * selectorTint * 0.45f),
                        alpha = 0.8f + (0.15f * selectorTint),
                    ),
                    RoundedCornerShape(InputCardCorner),
                )
                .paneNavItem(
                    cornerRadius = InputCardCorner,
                    onActivate = onClick,
                    highlightColor = InputNavHighlight,
                )
                .clickable(
                    interactionSource = selectionInteraction,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileSelectorIconBox(if (selectorPressed) InputTextPrimary else InputAccent)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text =
                    state.selectedProfileName
                        ?: stringResource(R.string.input_controls_editor_select_profile),
                color = InputTextPrimary,
                fontSize = InputPrimaryTextSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text =
                    if (state.selectedProfileName != null) {
                        stringResource(R.string.common_ui_elements_count, state.selectedProfileElementCount)
                    } else {
                        stringResource(R.string.input_controls_editor_no_profile_selected)
                    },
                color = InputTextSecondary,
                fontSize = InputSecondaryTextSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(InputItemGap))
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint =
                if (selectorPressed) {
                    InputTextPrimary
                } else {
                    InputAccent.copy(alpha = 0.9f)
                },
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ProfileActionRow(
    state: InputControlsScreenState,
    actions: InputControlsScreenActions,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        ProfileOverflowButton(
            onClick = { menuOpen = true },
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = InputCard,
        ) {
            ProfileActionMenuItem(
                icon = Icons.Outlined.Add,
                label = stringResource(R.string.common_ui_add),
                onClick = {
                    menuOpen = false
                    actions.onAddProfile()
                },
            )
            ProfileActionMenuItem(
                icon = Icons.Outlined.Edit,
                label = stringResource(R.string.common_ui_edit),
                onClick = {
                    menuOpen = false
                    actions.onEditProfile()
                },
            )
            ProfileActionMenuItem(
                icon = Icons.Outlined.ContentCopy,
                label = stringResource(R.string.common_ui_duplicate),
                onClick = {
                    menuOpen = false
                    actions.onDuplicateProfile()
                },
            )
            if (state.selectedProfileCanReset) {
                ProfileActionMenuItem(
                    icon = Icons.Outlined.Restore,
                    label = stringResource(R.string.common_ui_reset),
                    onClick = {
                        menuOpen = false
                        actions.onResetProfile()
                    },
                )
            }
            ProfileActionMenuItem(
                icon = Icons.Outlined.Delete,
                label = stringResource(R.string.common_ui_remove),
                iconTint = InputDanger,
                textColor = InputDanger,
                onClick = {
                    menuOpen = false
                    actions.onRemoveProfile()
                },
            )
        }
    }
}

@Composable
private fun ProfileEditButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(InputProfileActionSize)
                .clip(RoundedCornerShape(InputFieldCorner))
                .paneNavItem(
                    cornerRadius = InputFieldCorner,
                    onActivate = onClick,
                    highlightColor = InputNavHighlight,
                    tapToSelect = true,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.SportsEsports,
            contentDescription = stringResource(R.string.input_controls_editor_title),
            tint = InputAccent,
            modifier = Modifier.size(InputProfileActionIconSize),
        )
    }
}

@Composable
private fun ProfileOverflowButton(onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .size(InputProfileActionSize)
                .clip(RoundedCornerShape(InputFieldCorner))
                .paneNavItem(
                    cornerRadius = InputFieldCorner,
                    onActivate = onClick,
                    highlightColor = InputNavHighlight,
                    tapToSelect = true,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.MoreVert,
            contentDescription = stringResource(R.string.common_ui_options),
            tint = Color.White,
            modifier = Modifier.size(InputProfileActionIconSize),
        )
    }
}

@Composable
private fun ProfileActionMenuItem(
    icon: ImageVector,
    label: String,
    iconTint: Color = InputAccent,
    textColor: Color = InputTextPrimary,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(label, color = textColor, fontSize = InputPrimaryTextSize)
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun AutoHideTouchCard(
    state: InputControlsScreenState,
    actions: InputControlsScreenActions,
) {
    CardShell {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .paneNavItem(
                        cornerRadius = InputCardCorner,
                        onActivate = {
                            actions.onAutoHideTouchOnControllerChanged(!state.autoHideTouchOnController)
                        },
                        highlightColor = InputNavHighlight,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBox(Icons.Outlined.SportsEsports, InputTextSecondary)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.input_controls_auto_hide_on_controller_title),
                    color = InputTextPrimary,
                    fontSize = InputPrimaryTextSize,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = stringResource(R.string.input_controls_auto_hide_on_controller_summary),
                    color = InputTextSecondary,
                    fontSize = InputSecondaryTextSize,
                )
            }
            Spacer(Modifier.width(8.dp))
            AppSwitch(
                checked = state.autoHideTouchOnController,
                onCheckedChange = actions.onAutoHideTouchOnControllerChanged,
            )
        }
    }
}

@Composable
private fun OverlayOpacityCard(
    state: InputControlsScreenState,
    actions: InputControlsScreenActions,
) {
    CardShell {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBox(Icons.Outlined.Visibility, InputTextSecondary)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.input_controls_editor_overlay_opacity),
                        color = InputTextPrimary,
                        fontSize = InputPrimaryTextSize,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = "${state.overlayOpacity}%",
                        color = InputTextSecondary,
                        fontSize = InputSecondaryTextSize,
                    )
                }
            }
            Spacer(Modifier.height(InputCompactGap))
            Slider(
                value = state.overlayOpacity.toFloat(),
                onValueChange = {
                    actions.onOverlayOpacityChanged(snapToStep(it, 5, 10, 100))
                },
                valueRange = 10f..100f,
                steps = 17,
                modifier =
                    Modifier
                        .height(InputSliderHeight)
                        .paneNavItem(
                            cornerRadius = InputFieldCorner,
                            onAdjust = { dir ->
                                actions.onOverlayOpacityChanged((state.overlayOpacity + dir * 5).coerceIn(10, 100))
                            },
                            highlightColor = InputNavHighlight,
                        ),
                colors = sliderColors(),
                track = { InputSliderTrack(it) },
            )
        }
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    CardShell(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(InputFieldCorner))
                        .background(InputIconBox),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = InputTextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                color = InputTextPrimary,
                fontSize = InputPrimaryTextSize,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            DecorativeChevronBox()
        }
    }
}

@Composable
private fun Subcard(
    title: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    content: @Composable () -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "subcardChevronRotation",
    )
    val borderColor = if (expanded) InputAccent.copy(alpha = 0.45f) else InputOutline

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(InputCardCorner))
                .background(InputSubcard)
                .border(1.dp, borderColor, RoundedCornerShape(InputCardCorner)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .paneNavItem(
                        cornerRadius = InputCardCorner,
                        onActivate = onToggleExpanded,
                        highlightColor = InputNavHighlight,
                        tapToSelect = true,
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = if (expanded) InputAccent else InputTextSecondary,
                modifier =
                    Modifier
                        .size(18.dp)
                        .rotate(chevronRotation),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                color = InputTextSecondary,
                fontSize = InputPrimaryTextSize,
                modifier = Modifier.weight(1f),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter =
                fadeIn(tween(110)) +
                    expandVertically(
                        animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
                        expandFrom = Alignment.Top,
                    ),
            exit =
                fadeOut(tween(90)) +
                    shrinkVertically(
                        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
                        shrinkTowards = Alignment.Top,
                    ),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(InputSubcard)
                        .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SliderField(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    val stepSize = if (steps > 0) (valueRange.endInclusive - valueRange.start) / (steps + 1) else 1f
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .paneNavItem(
                    cornerRadius = InputFieldCorner,
                    onAdjust = { dir ->
                        onValueChange(
                            (value + dir * stepSize).coerceIn(valueRange.start, valueRange.endInclusive),
                        )
                    },
                    highlightColor = InputNavHighlight,
                ),
    ) {
        Text(
            text = label,
            color = InputTextSecondary,
            fontSize = InputPrimaryTextSize,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.height(InputSliderHeight),
            colors = sliderColors(),
            track = { InputSliderTrack(it) },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .paneNavItem(
                    cornerRadius = InputFieldCorner,
                    onActivate = { onCheckedChange(!checked) },
                    highlightColor = InputNavHighlight,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = InputTextSecondary,
            fontSize = InputPrimaryTextSize,
            modifier = Modifier.weight(1f),
        )
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CenteredPillButton(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .height(30.dp)
                .widthIn(min = 96.dp)
                .clip(RoundedCornerShape(InputFieldCorner))
                .background(InputSubcard)
                .border(1.dp, InputOutline, RoundedCornerShape(InputFieldCorner))
                .paneNavItem(
                    cornerRadius = InputFieldCorner,
                    onActivate = onClick,
                    highlightColor = InputNavHighlight,
                    tapToSelect = true,
                ).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = InputTextPrimary,
            fontSize = InputPrimaryTextSize,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

private enum class BindingCategory { MOUSE, KEYBOARD, GAMEPAD }

private val MOUSE_BINDING_OPTIONS: List<Binding> = buildList {
    add(Binding.NONE)
    add(Binding.MOUSE_LEFT_BUTTON); add(Binding.MOUSE_MIDDLE_BUTTON); add(Binding.MOUSE_RIGHT_BUTTON)
    add(Binding.MOUSE_SCROLL_UP); add(Binding.MOUSE_SCROLL_DOWN)
}
private val KEYBOARD_BINDING_OPTIONS: List<Binding> = buildList {
    add(Binding.NONE)
    for (b in Binding.values()) if (b.isKeyboard) add(b)
}
private val GAMEPAD_BINDING_OPTIONS: List<Binding> = buildList {
    add(Binding.NONE)
    for (b in Binding.values()) if (b.isGamepad) add(b)
}

private fun categoryOf(b: Binding): BindingCategory = when {
    b.isGamepad -> BindingCategory.GAMEPAD
    b.isKeyboard -> BindingCategory.KEYBOARD
    else -> BindingCategory.MOUSE
}

private fun optionsFor(category: BindingCategory): List<Binding> = when (category) {
    BindingCategory.MOUSE -> MOUSE_BINDING_OPTIONS
    BindingCategory.KEYBOARD -> KEYBOARD_BINDING_OPTIONS
    BindingCategory.GAMEPAD -> GAMEPAD_BINDING_OPTIONS
}

private fun prettyEnum(name: String): String =
    name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun <T> OptionDropdown(
    label: String,
    current: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = InputTextSecondary, fontSize = InputPrimaryTextSize, modifier = Modifier.weight(1f))
        Box {
            SelectionPill(text = optionLabel(current), onClick = { expanded = true })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = InputCard) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onSelect(option); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun <T> PillDropdown(
    current: T,
    options: List<T>,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        SelectionPill(text = optionLabel(current), onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = InputCard) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

@Composable
private fun BindingPicker(
    label: String,
    binding: Binding,
    onBinding: (Binding) -> Unit,
) {
    var category by remember { mutableStateOf(categoryOf(binding)) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = InputTextSecondary, fontSize = InputPrimaryTextSize, modifier = Modifier.weight(1f))
        PillDropdown(category, BindingCategory.values().toList(), { prettyEnum(it.name) }) { newCategory ->
            if (newCategory != category) {
                category = newCategory
                if (categoryOf(binding) != newCategory) onBinding(Binding.NONE)
            }
        }
        Spacer(Modifier.width(8.dp))
        PillDropdown(binding, optionsFor(category), { it.toString() }) { onBinding(it) }
    }
}

@Composable
private fun GestureBindingRow(
    title: String,
    enabled: Boolean,
    binding: Binding,
    onEnabled: (Boolean) -> Unit,
    onBinding: (Binding) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(InputCompactGap)) {
        SwitchRow(title = title, checked = enabled, onCheckedChange = onEnabled)
        if (enabled) {
            BindingPicker(stringResource(R.string.session_rts_action), binding, onBinding)
        }
    }
}

@Composable
private fun HoldRow(
    title: String,
    enabled: Boolean,
    binding: Binding,
    behavior: HoldBehavior,
    delay: Int,
    delayLabel: String,
    onEnabled: (Boolean) -> Unit,
    onBinding: (Binding) -> Unit,
    onBehavior: (HoldBehavior) -> Unit,
    onDelay: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(InputCompactGap)) {
        SwitchRow(title = title, checked = enabled, onCheckedChange = onEnabled)
        if (enabled) {
            BindingPicker(stringResource(R.string.session_rts_action), binding, onBinding)
            OptionDropdown(stringResource(R.string.session_rts_behavior), behavior, HoldBehavior.values().toList(), { prettyEnum(it.name) }, onBehavior)
            SliderField("$delayLabel: $delay", delay.toFloat(), 200f..1500f, 0) { onDelay(it.toInt()) }
        }
    }
}

@Composable
private fun SwipeSet(
    title: String,
    enabled: Boolean,
    up: Binding,
    down: Binding,
    left: Binding,
    right: Binding,
    threshold: Int,
    onEnabled: (Boolean) -> Unit,
    onUp: (Binding) -> Unit,
    onDown: (Binding) -> Unit,
    onLeft: (Binding) -> Unit,
    onRight: (Binding) -> Unit,
    onThreshold: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(InputCompactGap)) {
        SwitchRow(title = title, checked = enabled, onCheckedChange = onEnabled)
        if (enabled) {
            BindingPicker(stringResource(R.string.session_rts_up), up, onUp)
            BindingPicker(stringResource(R.string.session_rts_down), down, onDown)
            BindingPicker(stringResource(R.string.session_rts_left), left, onLeft)
            BindingPicker(stringResource(R.string.session_rts_right), right, onRight)
            SliderField("${stringResource(R.string.session_rts_swipe_threshold)}: $threshold", threshold.toFloat(), 20f..200f, 0) { onThreshold(it.toInt()) }
        }
    }
}

@Composable
private fun GestureProfileCard(
    state: InputControlsScreenState,
    actions: InputControlsScreenActions,
) {
    val selectionInteraction = remember { MutableInteractionSource() }
    val selectorPressed by selectionInteraction.collectIsPressedAsState()
    val selectorTint by animateFloatAsState(
        targetValue = if (selectorPressed) 1f else 0f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "gestureSelectorPressed",
    )

    CardShell(
        horizontalPadding = 10.dp,
        verticalPadding = 8.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GestureSelectorRow(
                state = state,
                selectionInteraction = selectionInteraction,
                selectorTint = selectorTint,
                selectorPressed = selectorPressed,
                onClick = actions.onSelectGestureProfile,
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = InputProfileSelectorMaxWidth),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileEditButton(
                    onClick = actions.onToggleGestureEditor,
                    modifier = Modifier.padding(start = InputProfileActionStartGap),
                )
                GestureActionRow(
                    actions = actions,
                    modifier = Modifier.padding(start = InputProfileActionStartGap),
                )
            }
        }
    }
}

@Composable
private fun GestureSelectorRow(
    state: InputControlsScreenState,
    selectionInteraction: MutableInteractionSource,
    selectorTint: Float,
    selectorPressed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(InputCardCorner))
                .background(
                    Color(
                        red = InputField.red,
                        green = InputField.green,
                        blue = InputField.blue,
                        alpha = 0.28f + (0.34f * selectorTint),
                    ),
                ).border(
                    1.dp,
                    Color(
                        red = InputOutline.red + ((InputAccent.red - InputOutline.red) * selectorTint * 0.45f),
                        green = InputOutline.green + ((InputAccent.green - InputOutline.green) * selectorTint * 0.45f),
                        blue = InputOutline.blue + ((InputAccent.blue - InputOutline.blue) * selectorTint * 0.45f),
                        alpha = 0.8f + (0.15f * selectorTint),
                    ),
                    RoundedCornerShape(InputCardCorner),
                )
                .paneNavItem(
                    cornerRadius = InputCardCorner,
                    onActivate = onClick,
                    highlightColor = InputNavHighlight,
                )
                .clickable(
                    interactionSource = selectionInteraction,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileSelectorIconBox(if (selectorPressed) InputTextPrimary else InputAccent)
        Spacer(Modifier.width(10.dp))
        Text(
            text =
                state.selectedGestureProfileName
                    ?: stringResource(R.string.input_controls_editor_select_profile),
            color = InputTextPrimary,
            fontSize = InputPrimaryTextSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(InputItemGap))
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint =
                if (selectorPressed) {
                    InputTextPrimary
                } else {
                    InputAccent.copy(alpha = 0.9f)
                },
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun GestureActionRow(
    actions: InputControlsScreenActions,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        ProfileOverflowButton(
            onClick = { menuOpen = true },
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = InputCard,
        ) {
            ProfileActionMenuItem(
                icon = Icons.Outlined.Add,
                label = stringResource(R.string.common_ui_new),
                onClick = {
                    menuOpen = false
                    actions.onNewGestureProfile()
                },
            )
            ProfileActionMenuItem(
                icon = Icons.Outlined.Edit,
                label = stringResource(R.string.common_ui_rename),
                onClick = {
                    menuOpen = false
                    actions.onRenameGestureProfile()
                },
            )
            ProfileActionMenuItem(
                icon = Icons.Outlined.ContentCopy,
                label = stringResource(R.string.common_ui_duplicate),
                onClick = {
                    menuOpen = false
                    actions.onDuplicateGestureProfile()
                },
            )
            ProfileActionMenuItem(
                icon = Icons.Outlined.Delete,
                label = stringResource(R.string.common_ui_remove),
                iconTint = InputDanger,
                textColor = InputDanger,
                onClick = {
                    menuOpen = false
                    actions.onDeleteGestureProfile()
                },
            )
        }
    }
}

@Composable
private fun GestureEditorBody(
    sourceConfig: TouchGestureConfig,
    onConfigChanged: (TouchGestureConfig) -> Unit,
) {
    var config by remember(sourceConfig) { mutableStateOf(sourceConfig.clone()) }
    fun mutate(block: TouchGestureConfig.() -> Unit) {
        val next = config.clone(); next.block(); config = next; onConfigChanged(next)
    }
    Column(verticalArrangement = Arrangement.spacedBy(InputItemGap)) {
        GestureBindingRow(stringResource(R.string.session_rts_tap1), config.tap1Enabled, config.tap1, { mutate { tap1Enabled = it } }, { mutate { tap1 = it } })
            GestureBindingRow(stringResource(R.string.session_rts_tap2), config.tap2Enabled, config.tap2, { mutate { tap2Enabled = it } }, { mutate { tap2 = it } })
            GestureBindingRow(stringResource(R.string.session_rts_tap3), config.tap3Enabled, config.tap3, { mutate { tap3Enabled = it } }, { mutate { tap3 = it } })
            GestureBindingRow(stringResource(R.string.session_rts_tap4), config.tap4Enabled, config.tap4, { mutate { tap4Enabled = it } }, { mutate { tap4 = it } })
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(InputCompactGap)) {
                SwitchRow(stringResource(R.string.session_rts_double_tap), config.doubleTapEnabled) { mutate { doubleTapEnabled = it } }
                if (config.doubleTapEnabled) {
                    SliderField("${stringResource(R.string.session_rts_double_tap_delay)}: ${config.doubleTapDelay}", config.doubleTapDelay.toFloat(), 200f..1500f, 0) { mutate { doubleTapDelay = it.toInt() } }
                }
            }
            HoldRow(stringResource(R.string.session_rts_long_press), config.longPressEnabled, config.longPress, config.longPressBehavior, config.longPressDelay, stringResource(R.string.session_rts_long_press_delay),
                { mutate { longPressEnabled = it } }, { mutate { longPress = it } }, { mutate { longPressBehavior = it } }, { mutate { longPressDelay = it } })
            HoldRow(stringResource(R.string.session_rts_hold2), config.hold2Enabled, config.hold2, config.hold2Behavior, config.hold2Delay, stringResource(R.string.session_rts_hold_delay),
                { mutate { hold2Enabled = it } }, { mutate { hold2 = it } }, { mutate { hold2Behavior = it } }, { mutate { hold2Delay = it } })
            HoldRow(stringResource(R.string.session_rts_hold3), config.hold3Enabled, config.hold3, config.hold3Behavior, config.hold3Delay, stringResource(R.string.session_rts_hold_delay),
                { mutate { hold3Enabled = it } }, { mutate { hold3 = it } }, { mutate { hold3Behavior = it } }, { mutate { hold3Delay = it } })
            HoldRow(stringResource(R.string.session_rts_hold4), config.hold4Enabled, config.hold4, config.hold4Behavior, config.hold4Delay, stringResource(R.string.session_rts_hold_delay),
                { mutate { hold4Enabled = it } }, { mutate { hold4 = it } }, { mutate { hold4Behavior = it } }, { mutate { hold4Delay = it } })
            SwipeSet(stringResource(R.string.session_rts_swipe3), config.swipe3Enabled, config.swipe3Up, config.swipe3Down, config.swipe3Left, config.swipe3Right, config.swipe3Threshold,
                { mutate { swipe3Enabled = it } }, { mutate { swipe3Up = it } }, { mutate { swipe3Down = it } }, { mutate { swipe3Left = it } }, { mutate { swipe3Right = it } }, { mutate { swipe3Threshold = it } })
            SwipeSet(stringResource(R.string.session_rts_swipe4), config.swipe4Enabled, config.swipe4Up, config.swipe4Down, config.swipe4Left, config.swipe4Right, config.swipe4Threshold,
                { mutate { swipe4Enabled = it } }, { mutate { swipe4Up = it } }, { mutate { swipe4Down = it } }, { mutate { swipe4Left = it } }, { mutate { swipe4Right = it } }, { mutate { swipe4Threshold = it } })
            if (config.drag2Action == DragAction.NONE) {
                OptionDropdown(stringResource(R.string.session_rts_pan), config.panAction, PanAction.values().toList(), { prettyEnum(it.name) }) { mutate { panAction = it } }
            }
            if (config.panAction == PanAction.NONE) {
                OptionDropdown(stringResource(R.string.session_rts_drag2), config.drag2Action, DragAction.values().toList(), { prettyEnum(it.name) }) { mutate { drag2Action = it } }
            }
            if (config.pan1Action == PanAction.NONE) {
                OptionDropdown(stringResource(R.string.session_rts_drag), config.dragAction, DragAction.values().toList(), { prettyEnum(it.name) }) { mutate { dragAction = it } }
            }
            if (config.dragAction == DragAction.NONE) {
                OptionDropdown(stringResource(R.string.session_rts_pan1), config.pan1Action, PanAction.values().toList(), { prettyEnum(it.name) }) { mutate { pan1Action = it } }
            }
            OptionDropdown(stringResource(R.string.session_rts_zoom), config.zoomAction, ZoomAction.values().toList(), { prettyEnum(it.name) }) { mutate { zoomAction = it } }
            if (config.dragAction != DragAction.NONE || config.drag2Action != DragAction.NONE) {
                SliderField("${stringResource(R.string.session_rts_drag_threshold)}: ${config.dragThreshold}", config.dragThreshold.toFloat(), 10f..200f, 0) { mutate { dragThreshold = it.toInt() } }
            }
        SliderField("${stringResource(R.string.session_rts_pan_threshold)}: ${config.gestureThreshold}", config.gestureThreshold.toFloat(), 10f..120f, 0) { mutate { gestureThreshold = it.toInt() } }
    }
}

@Composable
private fun GyroscopeCard(
    state: InputControlsScreenState,
    actions: InputControlsScreenActions,
) {
    CardShell {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .paneNavItem(
                            cornerRadius = InputFieldCorner,
                            onActivate = { actions.onGyroscopeEnabledChanged(!state.gyroscopeEnabled) },
                            highlightColor = InputNavHighlight,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBox(Icons.Outlined.ScreenRotationAlt, InputTextSecondary)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.session_gyroscope_title),
                    color = InputTextPrimary,
                    fontSize = InputPrimaryTextSize,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                AppSwitch(
                    checked = state.gyroscopeEnabled,
                    onCheckedChange = actions.onGyroscopeEnabledChanged,
                )
            }

            if (state.gyroscopeEnabled) {
                Spacer(Modifier.height(InputItemGap))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.common_ui_mode),
                        color = InputTextSecondary,
                        fontSize = InputPrimaryTextSize,
                    )
                    Spacer(Modifier.width(InputItemGap))
                    ChipRow(
                        options =
                            listOf(
                                stringResource(R.string.session_gyroscope_hold),
                                stringResource(R.string.session_gyroscope_toggle),
                            ),
                        selectedIndex = state.gyroscopeModeIndex,
                        onSelected = actions.onGyroscopeModeSelected,
                    )
                }

                Spacer(Modifier.height(InputItemGap))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .paneNavItem(
                                cornerRadius = InputFieldCorner,
                                onActivate = { actions.onGyroOrientationModeChanged(!state.gyroOrientationEnabled) },
                                highlightColor = InputNavHighlight,
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.session_gyroscope_orientation_mode),
                        color = InputTextSecondary,
                        fontSize = InputPrimaryTextSize,
                        modifier = Modifier.weight(1f),
                    )
                    AppSwitch(
                        checked = state.gyroOrientationEnabled,
                        onCheckedChange = actions.onGyroOrientationModeChanged,
                    )
                }

                Spacer(Modifier.height(InputItemGap))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.session_gyroscope_activator_button),
                        color = InputTextSecondary,
                        fontSize = InputPrimaryTextSize,
                        modifier = Modifier.weight(1f),
                    )
                    SelectionPill(
                        text = state.gyroscopeActivatorLabel,
                        onClick = actions.onGyroscopeActivatorClick,
                    )
                }

                Spacer(Modifier.height(InputItemGap))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .paneNavItem(
                                cornerRadius = InputFieldCorner,
                                onActivate = { actions.onRightStickGyroChanged(!state.rightStickGyroEnabled) },
                                highlightColor = InputNavHighlight,
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.session_gyroscope_enable_right_stick),
                        color = InputTextSecondary,
                        fontSize = InputPrimaryTextSize,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.common_ui_experimental),
                        color = InputAccent,
                        fontSize = InputSecondaryTextSize,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(8.dp))
                    AppSwitch(
                        checked = state.rightStickGyroEnabled,
                        onCheckedChange = actions.onRightStickGyroChanged,
                    )
                }

                Spacer(Modifier.height(InputItemGap))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .paneNavItem(
                                cornerRadius = InputFieldCorner,
                                onActivate = { actions.onGyroMouseEnabledChanged(!state.gyroMouseEnabled) },
                                highlightColor = InputNavHighlight,
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.session_gyroscope_experimental_mouse_movement),
                        color = InputTextSecondary,
                        fontSize = InputPrimaryTextSize,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.common_ui_experimental),
                        color = InputAccent,
                        fontSize = InputSecondaryTextSize,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(8.dp))
                    AppSwitch(
                        checked = state.gyroMouseEnabled,
                        onCheckedChange = actions.onGyroMouseEnabledChanged,
                    )
                }

                if (state.gyroMouseEnabled) {
                    Spacer(Modifier.height(InputCompactGap))
                    SliderField(
                        label = stringResource(R.string.session_gyroscope_mouse_sensitivity_format, state.gyroMouseScale),
                        value = state.gyroMouseScale.toFloat(),
                        valueRange = 0f..200f,
                        steps = 199,
                        onValueChange = { actions.onGyroMouseScaleChanged(it.roundToInt().coerceIn(0, 200)) },
                    )
                }

                Spacer(Modifier.height(InputCompactGap))
                Subcard(
                    title = stringResource(R.string.session_gyroscope_calibrate),
                    expanded = state.gyroscopeExpanded,
                    onToggleExpanded = {
                        actions.onGyroscopeExpandedChanged(!state.gyroscopeExpanded)
                    },
                ) {
                    Spacer(Modifier.height(InputItemGap))
                    SliderField(
                        label = stringResource(R.string.session_gyroscope_x_sensitivity_format, state.gyroXSensitivity),
                        value = state.gyroXSensitivity.toFloat(),
                        valueRange = 1f..300f,
                        steps = 0,
                        onValueChange = { actions.onGyroXSensitivityChanged(it.roundToInt().coerceIn(1, 300)) },
                    )
                    Spacer(Modifier.height(InputCompactGap))
                    SliderField(
                        label = stringResource(R.string.session_gyroscope_y_sensitivity_format, state.gyroYSensitivity),
                        value = state.gyroYSensitivity.toFloat(),
                        valueRange = 1f..300f,
                        steps = 0,
                        onValueChange = { actions.onGyroYSensitivityChanged(it.roundToInt().coerceIn(1, 300)) },
                    )
                    Spacer(Modifier.height(InputCompactGap))
                    SliderField(
                        label = stringResource(R.string.session_gyroscope_smoothing_format, state.gyroSmoothing),
                        value = state.gyroSmoothing.toFloat(),
                        valueRange = 0f..100f,
                        steps = 99,
                        onValueChange = { actions.onGyroSmoothingChanged(it.roundToInt().coerceIn(0, 100)) },
                    )
                    Spacer(Modifier.height(InputCompactGap))
                    SliderField(
                        label = stringResource(R.string.session_gyroscope_deadzone_format, state.gyroDeadzone),
                        value = state.gyroDeadzone.toFloat(),
                        valueRange = 0f..100f,
                        steps = 99,
                        onValueChange = { actions.onGyroDeadzoneChanged(it.roundToInt().coerceIn(0, 100)) },
                    )
                    Spacer(Modifier.height(InputItemGap))
                    SwitchRow(
                        title = stringResource(R.string.session_gamepad_invert_x),
                        checked = state.invertGyroX,
                        onCheckedChange = actions.onInvertGyroXChanged,
                    )
                    Spacer(Modifier.height(4.dp))
                    SwitchRow(
                        title = stringResource(R.string.session_gamepad_invert_y),
                        checked = state.invertGyroY,
                        onCheckedChange = actions.onInvertGyroYChanged,
                    )
                    Spacer(Modifier.height(InputItemGap))
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(136.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black),
                    ) {
                        AndroidView(
                            factory = { context ->
                                InputControlsView(context, true).apply {
                                    actions.onAttachGyroPreview(this)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            update = { view ->
                                actions.onAttachGyroPreview(view)
                            },
                        )
                        DisposableEffect(Unit) {
                            onDispose { actions.onDetachGyroPreview() }
                        }
                    }
                    Spacer(Modifier.height(InputItemGap))
                    CenteredPillButton(
                        text = stringResource(R.string.session_gyroscope_reset_stick),
                        onClick = actions.onResetGyroPreview,
                    )
                }
            }
        }
    }
}

@Composable
private fun SteamControllerCard(
    state: InputControlsScreenState,
    actions: InputControlsScreenActions,
) {
    CardShell {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .paneNavItem(
                            cornerRadius = InputCardCorner,
                            onActivate = {
                                actions.onSteamControllerEnabledChanged(!state.steamControllerEnabled)
                            },
                            highlightColor = InputNavHighlight,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBox(
                    image = Icons.Outlined.SportsEsports,
                    tint = if (state.steamControllerEnabled) InputAccent else InputTextSecondary,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.steam_controller_enable),
                        color = InputTextPrimary,
                        fontSize = InputPrimaryTextSize,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = stringResource(R.string.steam_controller_enable_summary),
                        color = InputTextSecondary,
                        fontSize = InputSecondaryTextSize,
                    )
                }
                Spacer(Modifier.width(8.dp))
                AppSwitch(
                    checked = state.steamControllerEnabled,
                    onCheckedChange = actions.onSteamControllerEnabledChanged,
                )
            }
            AnimatedVisibility(
                visible = state.steamControllerEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(InputItemGap))
                    Text(
                        text = stringResource(R.string.steam_controller_trackpad_mode),
                        color = InputTextSecondary,
                        fontSize = InputSectionTextSize,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(InputCompactGap))
                    ChipRow(
                        options =
                            listOf(
                                stringResource(R.string.steam_controller_trackpad_off),
                                stringResource(R.string.steam_controller_trackpad_right),
                                stringResource(R.string.steam_controller_trackpad_left),
                                stringResource(R.string.steam_controller_trackpad_both),
                            ),
                        selectedIndex = state.steamTrackpadModeIndex,
                        onSelected = actions.onSteamTrackpadModeSelected,
                    )
                    Spacer(Modifier.height(InputItemGap))
                    Text(
                        text = stringResource(R.string.steam_controller_extra_buttons),
                        color = InputTextSecondary,
                        fontSize = InputSectionTextSize,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(InputCompactGap))
                    val paddleTitles =
                        listOf(
                            stringResource(R.string.steam_controller_paddle_l4),
                            stringResource(R.string.steam_controller_paddle_l5),
                            stringResource(R.string.steam_controller_paddle_r4),
                            stringResource(R.string.steam_controller_paddle_r5),
                            stringResource(R.string.steam_controller_button_qam),
                        )
                    paddleTitles.forEachIndexed { index, title ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = title,
                                color = InputTextPrimary,
                                fontSize = InputSecondaryTextSize,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            SelectionPill(
                                text = state.steamPaddleLabels.getOrNull(index) ?: "",
                                onClick = { actions.onSteamPaddleClick(index) },
                            )
                        }
                    }
                    Spacer(Modifier.height(InputCompactGap))
                    Text(
                        text = stringResource(R.string.steam_controller_restart_required),
                        color = InputTextSecondary,
                        fontSize = InputSecondaryTextSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun TriggerTypeCard(
    state: InputControlsScreenState,
    actions: InputControlsScreenActions,
) {
    CardShell {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBox(Icons.Outlined.Tune, InputTextSecondary)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.session_gamepad_trigger_type),
                    color = InputTextPrimary,
                    fontSize = InputPrimaryTextSize,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(InputItemGap))
            ChipRow(
                options =
                    listOf(
                        stringResource(R.string.session_gamepad_as_button),
                        stringResource(R.string.session_gamepad_as_axis),
                    ),
                selectedIndex = state.triggerTypeIndex,
                onSelected = actions.onTriggerTypeSelected,
            )
            Spacer(Modifier.height(InputCompactGap))
            Subcard(
                title = stringResource(R.string.session_gamepad_trigger_type),
                expanded = state.triggerCardExpanded,
                onToggleExpanded = {
                    actions.onTriggerCardExpandedChanged(!state.triggerCardExpanded)
                },
            ) {
                Spacer(Modifier.height(InputCompactGap))
                Text(
                    text = state.triggerDescription,
                    color = InputTextSecondary,
                    fontSize = InputSecondaryTextSize,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun ControllerCard(
    state: InputControllerCardState,
    actions: InputControlsScreenActions,
) {
    CardShell {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBox(
                    image = Icons.Outlined.SportsEsports,
                    tint = if (state.connected) InputAccent else InputDanger,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.name,
                        color = InputTextPrimary,
                        fontSize = InputPrimaryTextSize,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = "${state.bindingCount} ${stringResource(R.string.session_gamepad_bindings)}",
                        color = InputTextSecondary,
                        fontSize = InputSecondaryTextSize,
                    )
                }
                if (state.showBindings && state.bindingCount > 0) {
                    IconActionButton(
                        image = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.common_ui_remove),
                        tint = InputDanger,
                        onClick = { actions.onRemoveController(state.controllerId) },
                    )
                }
            }

            if (state.showBindings) {
                Spacer(Modifier.height(InputCompactGap))
                Subcard(
                    title = stringResource(R.string.session_gamepad_control_bindings),
                    expanded = state.expanded,
                    onToggleExpanded = {
                        actions.onControllerExpandedToggle(state.controllerId)
                    },
                ) {
                    Spacer(Modifier.height(InputCompactGap))
                    if (state.bindings.isEmpty()) {
                        Text(
                            text = stringResource(R.string.session_gamepad_press_any_button),
                            color = InputTextSecondary,
                            fontSize = InputPrimaryTextSize,
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            state.bindings.forEach { binding ->
                                BindingRow(
                                    controllerId = state.controllerId,
                                    state = binding,
                                    actions = actions,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BindingRow(
    controllerId: String,
    state: InputControllerBindingState,
    actions: InputControlsScreenActions,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(InputFieldCorner))
                .background(InputSubcard)
                .border(1.dp, InputOutline, RoundedCornerShape(InputFieldCorner))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.label,
            color = InputTextPrimary,
            fontSize = InputPrimaryTextSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(InputCompactGap))
        BindingSelectionButton(
            text = state.typeLabel,
            modifier = Modifier.weight(0.8f),
            onClick = { actions.onBindingTypeClick(controllerId, state.keyCode) },
        )
        Spacer(Modifier.width(4.dp))
        BindingSelectionButton(
            text = state.bindingLabel,
            modifier = Modifier.weight(1f),
            onClick = { actions.onBindingValueClick(controllerId, state.keyCode) },
        )
        Spacer(Modifier.width(InputCompactGap))
        IconActionButton(
            image = Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.common_ui_remove),
            tint = InputDanger,
            onClick = { actions.onRemoveBinding(controllerId, state.keyCode) },
            size = 28.dp,
        )
    }
}

@Composable
private fun BindingSelectionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            modifier
                .heightIn(min = 28.dp)
                .clip(RoundedCornerShape(InputFieldCorner))
                .background(InputField)
                .border(1.dp, InputOutline, RoundedCornerShape(InputFieldCorner))
                .paneNavItem(
                    cornerRadius = InputFieldCorner,
                    onActivate = onClick,
                    highlightColor = InputNavHighlight,
                    tapToSelect = true,
                ).padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = InputTextPrimary,
            fontSize = InputSecondaryTextSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = InputTextSecondary,
            modifier = Modifier.size(12.dp),
        )
    }
}
