@file:OptIn(ExperimentalMaterial3Api::class)

package com.winlator.cmod.feature.settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.shared.ui.layout.isPortraitLayout
import com.winlator.cmod.R
import com.winlator.cmod.runtime.system.ProcessHelper
import com.winlator.cmod.shared.ui.dialog.PopupDialog
import com.winlator.cmod.shared.ui.focus.rememberSettingsContentNav
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.focusProperties
import com.winlator.cmod.shared.ui.outlinedSwitchColors

// Palette (mirrors DebugScreen / StoresScreen)
private val BgDark = Color(0xFF11111C)
private val CardDark = Color(0xFF1C1C2A)
private val CardBorder = Color(0xFF2A2A3A)
private val IconBoxBg = Color(0xFF242434)
private val SurfaceDark = Color(0xFF21212A)
private val Accent = Color(0xFF1A9FFF)
private val NavHighlight = Color(0xFF4FC3F7)
private val TextPrimary = Color(0xFFF0F4FF)
private val TextSecondary = Color(0xFF7A8FA8)
private val SettingsSliderHeight = 24.dp
private const val SettingsSliderTrackScaleY = 0.72f
private val Error = Color(0xFFFF4444)

// State
data class OtherSettingsState(
    val checkForUpdates: Boolean = true,
    val updateChannelIndex: Int = 0,
    val languageLabels: List<String> = emptyList(),
    val languageIndex: Int = 0,
    val soundFontFiles: List<String> = emptyList(),
    val soundFontIndex: Int = 0,
    val winlatorPath: String = "",
    val shortcutExportPath: String = "",
    val cursorSpeedPercent: Int = 100,
    val cursorLock: Boolean = false,
    val xinputDisabled: Boolean = false,
    val enableFileProvider: Boolean = true,
    val enableAutoScraping: Boolean = false,
    val openInBrowser: Boolean = false,
    val shareClipboard: Boolean = false,
    val enableBackgroundSession: Boolean = false,
    val enableAutoPause: Boolean = false,
    val useBackgroundWakelock: Boolean = false,
    val heartbeatFrequency: Int = 0,
    val backgroundPauseMode: ProcessHelper.BackgroundPauseMode = ProcessHelper.BackgroundPauseMode.GAME_ONLY,
    val externalDisplayOutput: Boolean = false,
    val imagefsInstallProgress: Int? = null,
)

@Composable
private fun settingsSliderColors() =
    SliderDefaults.colors(
        thumbColor = Accent,
        activeTrackColor = Accent,
        inactiveTrackColor = SurfaceDark,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
    )

@Composable
private fun SettingsSliderTrack(sliderState: SliderState) {
    SliderDefaults.Track(
        sliderState = sliderState,
        colors = settingsSliderColors(),
        modifier = Modifier.scale(scaleX = 1f, scaleY = SettingsSliderTrackScaleY),
    )
}

// Root
@Composable
fun OtherSettingsScreen(
    state: OtherSettingsState,
    onCheckForUpdatesChanged: (Boolean) -> Unit,
    onCheckForUpdatesNow: () -> Unit,
    onUpdateChannelSelected: (Int) -> Unit,
    onLanguageSelected: (Int) -> Unit,
    onSoundFontSelected: (Int) -> Unit,
    onInstallSoundFont: () -> Unit,
    onRemoveSoundFont: () -> Unit,
    onPickWinlatorPath: () -> Unit,
    onPickShortcutExportPath: () -> Unit,
    onExportAll: () -> Unit,
    onCursorSpeedChanged: (Int) -> Unit,
    onCursorLockChanged: (Boolean) -> Unit,
    onXinputDisabledChanged: (Boolean) -> Unit,
    onEnableFileProviderChanged: (Boolean) -> Unit,
    onAutoScrapingChanged: (Boolean) -> Unit,
    onOpenInBrowserChanged: (Boolean) -> Unit,
    onShareClipboardChanged: (Boolean) -> Unit,
    onEnableBackgroundSessionChanged: (Boolean) -> Unit,
    onEnableAutoPauseChanged: (Boolean) -> Unit,
    onUseBackgroundWakelockChanged: (Boolean) -> Unit,
    onHeartbeatFrequencyChanged: (Int) -> Unit,
    onBackgroundPauseModeChanged: (ProcessHelper.BackgroundPauseMode) -> Unit,
    onExternalDisplayOutputChanged: (Boolean) -> Unit,
    onRunSetupWizard: () -> Unit,
    onReinstallImagefs: () -> Unit,
    bridge: SettingsNavBridge? = null,
) {
    var showReinstallDialog by remember { mutableStateOf(false) }
    val layoutDirection = LocalLayoutDirection.current
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val navBarStartPadding = navBarPadding.calculateStartPadding(layoutDirection)
    val navBarEndPadding = navBarPadding.calculateEndPadding(layoutDirection)
    val navBarBottomPadding = navBarPadding.calculateBottomPadding()
    val contentNav = rememberSettingsContentNav(bridge)

    if (showReinstallDialog) {
        ReinstallImagefsConfirmDialog(
            onConfirm = {
                showReinstallDialog = false
                onReinstallImagefs()
            },
            onDismiss = { showReinstallDialog = false },
        )
    }

    state.imagefsInstallProgress?.let { percent ->
        ImagefsInstallProgressDialog(percent = percent)
    }

    CompositionLocalProvider(LocalPaneNav provides contentNav) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(BgDark)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 16.dp + navBarStartPadding,
                        end = 16.dp + navBarEndPadding,
                        top = 16.dp,
                    ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel(stringResource(R.string.common_ui_application))

            UpdatesCard(
                checked = state.checkForUpdates,
                onCheckedChange = onCheckForUpdatesChanged,
                onCheckNow = onCheckForUpdatesNow,
            )

            SettingsDropdownCard(
                title = stringResource(R.string.settings_general_update_channel),
                subtitle = stringResource(R.string.settings_general_update_channel_summary),
                icon = Icons.Outlined.Sync,
                options =
                    listOf(
                        stringResource(R.string.update_channel_official),
                        stringResource(R.string.update_channel_development),
                    ),
                selectedIndex = state.updateChannelIndex,
                onOptionSelected = onUpdateChannelSelected,
            )

            SettingsDropdownCard(
                title = stringResource(R.string.settings_other_language_title),
                subtitle = stringResource(R.string.settings_other_language_summary),
                icon = Icons.Outlined.Language,
                options = state.languageLabels,
                selectedIndex = state.languageIndex,
                onOptionSelected = onLanguageSelected,
            )

            SectionLabel(stringResource(R.string.settings_audio_sound), modifier = Modifier.padding(top = 8.dp))

            SoundFontCard(
                files = state.soundFontFiles,
                selectedIndex = state.soundFontIndex,
                onSelected = onSoundFontSelected,
                onInstall = onInstallSoundFont,
                onRemove = onRemoveSoundFont,
            )

            SectionLabel(stringResource(R.string.settings_general_paths_title), modifier = Modifier.padding(top = 8.dp))

            FolderPathCard(
                label = stringResource(R.string.settings_general_winlator_path_title),
                path = state.winlatorPath,
                onBrowse = onPickWinlatorPath,
            )

            FolderPathCard(
                label = stringResource(R.string.settings_general_shortcut_export_path_title),
                path = state.shortcutExportPath,
                onBrowse = onPickShortcutExportPath,
                secondaryLabel = stringResource(R.string.shortcuts_export_all),
                onSecondary = onExportAll,
            )

            SectionLabel(stringResource(R.string.session_xserver_title), modifier = Modifier.padding(top = 8.dp))

            CursorSpeedCard(
                percent = state.cursorSpeedPercent,
                onPercentChanged = onCursorSpeedChanged,
            )

            SettingsToggleCard(
                title = stringResource(R.string.settings_general_cursor_lock_title),
                subtitle = stringResource(R.string.settings_general_cursor_lock_summary),
                icon = Icons.Outlined.Mouse,
                checked = state.cursorLock,
                onCheckedChange = onCursorLockChanged,
            )

            SettingsToggleCard(
                title = stringResource(R.string.settings_general_xinput_toggle_title),
                subtitle = stringResource(R.string.settings_general_xinput_toggle_summary),
                icon = Icons.Outlined.SportsEsports,
                checked = state.xinputDisabled,
                onCheckedChange = onXinputDisabledChanged,
            )

            SectionLabel(stringResource(R.string.settings_other_section_background), modifier = Modifier.padding(top = 8.dp))

            SettingsToggleCard(
                title = stringResource(R.string.settings_general_background),
                subtitle = stringResource(R.string.settings_other_background_subtitle),
                icon = Icons.Outlined.Visibility,
                checked = state.enableBackgroundSession,
                onCheckedChange = onEnableBackgroundSessionChanged,
            )

            SettingsToggleCard(
                title = stringResource(R.string.settings_other_bg_auto_pause_title),
                subtitle = stringResource(R.string.settings_other_bg_auto_pause_subtitle),
                icon = Icons.Outlined.Visibility,
                checked = state.enableAutoPause,
                onCheckedChange = onEnableAutoPauseChanged,
            )

            AnimatedVisibility(
                visible = state.enableBackgroundSession,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                SettingsToggleCard(
                    title = stringResource(R.string.settings_other_bg_wakelock_title),
                    subtitle = stringResource(R.string.settings_other_bg_wakelock_subtitle),
                    icon = Icons.Outlined.BatteryAlert,
                    checked = state.useBackgroundWakelock,
                    onCheckedChange = onUseBackgroundWakelockChanged,
                )
            }

            AnimatedVisibility(
                visible = state.enableBackgroundSession && state.useBackgroundWakelock,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                HeartbeatFrequencyCard(
                    currentFrequency = state.heartbeatFrequency,
                    onFrequencyChanged = onHeartbeatFrequencyChanged,
                )
            }

            BackgroundPauseModeCard(
                currentMode = state.backgroundPauseMode,
                onModeChanged = onBackgroundPauseModeChanged,
            )

            SectionLabel(stringResource(R.string.settings_other_section_integration), modifier = Modifier.padding(top = 8.dp))

            SettingsToggleCard(
                title = stringResource(R.string.session_drawer_output_to_display),
                subtitle = stringResource(R.string.settings_external_display_output_summary),
                icon = Icons.Outlined.Monitor,
                checked = state.externalDisplayOutput,
                onCheckedChange = onExternalDisplayOutputChanged,
            )

            SettingsToggleCard(
                title = stringResource(R.string.settings_general_enable_auto_scraping),
                subtitle = stringResource(R.string.settings_general_auto_scraping_summary),
                icon = Icons.Outlined.ArrowCircleDown,
                checked = state.enableAutoScraping,
                onCheckedChange = onAutoScrapingChanged,
            )

            SettingsToggleCard(
                title = stringResource(R.string.settings_general_enable_file_provider),
                subtitle = stringResource(R.string.settings_general_file_provider_summary),
                icon = Icons.Outlined.Folder,
                checked = state.enableFileProvider,
                onCheckedChange = onEnableFileProviderChanged,
            )

            SettingsToggleCard(
                title = stringResource(R.string.settings_general_open_with_android_browser),
                subtitle = stringResource(R.string.settings_general_open_browser_summary),
                icon = Icons.Outlined.OpenInBrowser,
                checked = state.openInBrowser,
                onCheckedChange = onOpenInBrowserChanged,
            )

            SettingsToggleCard(
                title = stringResource(R.string.settings_general_share_android_clipboard),
                subtitle = stringResource(R.string.settings_general_clipboard_summary),
                icon = Icons.Outlined.ContentCopy,
                checked = state.shareClipboard,
                onCheckedChange = onShareClipboardChanged,
            )

            SectionLabel(stringResource(R.string.settings_general_imagefs), modifier = Modifier.padding(top = 8.dp))

            ReinstallImagefsCard(onClick = { showReinstallDialog = true })

            SetupWizardCard(onClick = onRunSetupWizard)

            Spacer(Modifier.height(24.dp + navBarBottomPadding))
        }
    }
}

// Section label
@Composable
private fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        color = TextSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        modifier = modifier.padding(bottom = 4.dp),
    )
}

// Settings toggle card
@Composable
private fun SettingsToggleCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accentColor: Color = Accent,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                .paneNavItem(
                    cornerRadius = 12.dp,
                    onActivate = { onCheckedChange(!checked) },
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(IconBoxBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = TextSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.scale(0.78f).focusProperties { canFocus = false },
                colors =
                    outlinedSwitchColors(
                        accentColor = accentColor,
                        textSecondaryColor = TextSecondary,
                    ),
            )
        }
    }
}

@Composable
private fun UpdatesCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                .paneNavItem(
                    cornerRadius = 12.dp,
                    onActivate = { onCheckedChange(!checked) },
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(IconBoxBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_general_check_for_updates),
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.settings_general_check_for_updates_summary),
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            SmallActionButton(label = stringResource(R.string.common_ui_check), textColor = Accent, onClick = onCheckNow)
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.scale(0.78f),
                colors =
                    outlinedSwitchColors(
                        accentColor = Accent,
                        textSecondaryColor = TextSecondary,
                    ),
            )
        }
    }
}

// Generic dropdown card (labels list + index selection)
@Composable
private fun SettingsDropdownCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
    accentColor: Color = Accent,
) {
    var expanded by remember { mutableStateOf(false) }
    val safeIndex = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    val selectedLabel = options.getOrNull(safeIndex) ?: ""

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(IconBoxBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = TextSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box {
                Row(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF222232))
                            .border(1.dp, accentColor.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                            .paneNavItem(
                                cornerRadius = 8.dp,
                                onActivate = { if (options.isNotEmpty()) expanded = true },
                                highlightColor = NavHighlight,
                                tapToSelect = true,
                            ).padding(horizontal = 10.dp, vertical = 7.dp)
                            .widthIn(max = 180.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = selectedLabel,
                        color = accentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(14.dp),
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    shape = RoundedCornerShape(8.dp),
                    containerColor = Color(0xFF24243B),
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.widthIn(max = 260.dp),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        options.forEachIndexed { index, label ->
                            val isSelected = index == safeIndex
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = label,
                                        color = if (isSelected) accentColor else TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        softWrap = true,
                                    )
                                },
                                onClick = {
                                    onOptionSelected(index)
                                    expanded = false
                                },
                                modifier =
                                    Modifier.background(
                                        if (isSelected) accentColor.copy(alpha = 0.08f) else Color.Transparent,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

// SoundFont card: dropdown + Install + Remove
@Composable
private fun SoundFontCard(
    files: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val safeIndex = selectedIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0))
    val selectedLabel = files.getOrNull(safeIndex) ?: ""

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp)),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(IconBoxBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LibraryMusic,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_audio_midi_sound_font),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.settings_audio_summary),
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF222232))
                                .border(1.dp, Accent.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                                .paneNavItem(
                                    cornerRadius = 8.dp,
                                    onActivate = { if (files.isNotEmpty()) expanded = true },
                                    highlightColor = NavHighlight,
                                    tapToSelect = true,
                                ).padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = selectedLabel,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        shape = RoundedCornerShape(8.dp),
                        containerColor = Color(0xFF24243B),
                        border = BorderStroke(1.dp, CardBorder),
                        modifier = Modifier.widthIn(max = 320.dp),
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState()),
                        ) {
                            files.forEachIndexed { index, label ->
                                val isSelected = index == safeIndex
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            color = if (isSelected) Accent else TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            softWrap = true,
                                        )
                                    },
                                    onClick = {
                                        onSelected(index)
                                        expanded = false
                                    },
                                    modifier =
                                        Modifier.background(
                                            if (isSelected) Accent.copy(alpha = 0.08f) else Color.Transparent,
                                        ),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                SmallActionButton(label = stringResource(R.string.common_ui_install), textColor = Accent, onClick = onInstall)
                Spacer(Modifier.width(6.dp))
                SmallActionButton(label = stringResource(R.string.common_ui_remove), textColor = TextSecondary, onClick = onRemove)
            }
        }
    }
}

// Folder path card (mirrors StoresScreen.FolderPathCard)
@Composable
private fun FolderPathCard(
    label: String,
    path: String,
    onBrowse: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp)),
    ) {
        val stackActions = isPortraitLayout() && secondaryLabel != null && onSecondary != null

        val leading: @Composable () -> Unit = {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(IconBoxBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        val labels: @Composable (Modifier) -> Unit = { labelModifier ->
            Column(modifier = labelModifier) {
                Text(label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = path.ifEmpty { stringResource(R.string.common_ui_not_configured) },
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val actions: @Composable () -> Unit = {
            if (secondaryLabel != null && onSecondary != null) {
                SmallActionButton(
                    label = secondaryLabel,
                    textColor = Accent,
                    onClick = onSecondary,
                )
                Spacer(Modifier.width(8.dp))
            }
            SmallActionButton(
                label = stringResource(R.string.settings_general_choose_path),
                textColor = Accent,
                onClick = onBrowse,
            )
        }

        if (stackActions) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    leading()
                    Spacer(Modifier.width(13.dp))
                    labels(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions()
                }
            }
        } else {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading()
                Spacer(Modifier.width(13.dp))
                labels(Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                actions()
            }
        }
    }
}

// Cursor speed slider card
@Composable
private fun CursorSpeedCard(
    percent: Int,
    onPercentChanged: (Int) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp)),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(IconBoxBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Speed,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_general_cursor_speed),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.settings_general_xserver_cursor_summary),
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$percent%",
                    color = Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Slider(
                value = percent.toFloat(),
                onValueChange = { onPercentChanged(it.toInt()) },
                valueRange = 10f..300f,
                steps = 0,
                colors = settingsSliderColors(),
                track = { SettingsSliderTrack(it) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(SettingsSliderHeight)
                        .paneNavItem(
                            cornerRadius = 12.dp,
                            onAdjust = { dir ->
                                onPercentChanged((percent + dir * 5).coerceIn(10, 300))
                            },
                            highlightColor = NavHighlight,
                        ),
            )
        }
    }
}

@Composable
private fun ReinstallImagefsConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val nav = remember { PaneNavRegistry() }
    Dialog(onDismissRequest = onDismiss) {
        DialogPaneNav(nav, onDismiss = onDismiss)
        CompositionLocalProvider(LocalPaneNav provides nav) {
            PopupDialog(
                title = stringResource(R.string.settings_general_reinstall_imagefs),
                message = stringResource(R.string.settings_general_confirm_reinstall_imagefs),
                icon = Icons.Outlined.Autorenew,
                confirmLabel = stringResource(R.string.common_ui_reinstall),
                onConfirm = onConfirm,
                onCancel = onDismiss,
                accentColor = Accent,
                modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
            )
        }
    }
}

@Composable
private fun ImagefsInstallProgressDialog(percent: Int) {
    Dialog(
        onDismissRequest = { /* non-dismissable */ },
        properties =
            DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
    ) {
        PopupDialog(
            title = stringResource(R.string.setup_wizard_installing_system_files),
            message = stringResource(R.string.settings_other_keep_app_open),
            icon = Icons.Outlined.Autorenew,
            accentColor = Accent,
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
            progress = percent.coerceIn(0, 100) / 100f,
        )
    }
}

// Reinstall imagefs card with centered action button
@Composable
private fun ReinstallImagefsCard(onClick: () -> Unit) {
    SettingsActionCard(
        title = stringResource(R.string.settings_general_reinstall_imagefs),
        subtitle = stringResource(R.string.settings_general_imagefs_summary),
        icon = Icons.Outlined.Autorenew,
        buttonLabel = stringResource(R.string.common_ui_reinstall),
        onClick = onClick,
    )
}

@Composable
private fun SetupWizardCard(onClick: () -> Unit) {
    SettingsActionCard(
        title = stringResource(R.string.settings_other_setup_wizard_title),
        subtitle = stringResource(R.string.settings_other_setup_wizard_summary),
        icon = Icons.Outlined.Settings,
        buttonLabel = stringResource(R.string.common_ui_open),
        onClick = onClick,
    )
}

@Composable
private fun SettingsActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    buttonLabel: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(IconBoxBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            SmallActionButton(
                label = buttonLabel,
                textColor = Accent,
                onClick = onClick,
            )
        }
    }
}

// Small pill button (mirrors DebugScreen.SmallActionButton)
@Composable
private fun SmallActionButton(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .width(104.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF222232))
                .border(1.dp, textColor.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = onClick,
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                ).padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BackgroundPauseModeCard(
    currentMode: ProcessHelper.BackgroundPauseMode,
    onModeChanged: (ProcessHelper.BackgroundPauseMode) -> Unit,
) {
    // Tracks if the card is expanded. False = collapsed by default.
    var expanded by remember { mutableStateOf(false) }

    // Each entry: mode, title string res, subtitle string res.
    val options = listOf(
        Triple(ProcessHelper.BackgroundPauseMode.ALL,            R.string.settings_other_bg_pause_all_title,            R.string.settings_other_bg_pause_all_subtitle),
        Triple(ProcessHelper.BackgroundPauseMode.GAME_ONLY,      R.string.settings_other_bg_pause_game_only_title,      R.string.settings_other_bg_pause_game_only_subtitle),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardDark)
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            // Header Row - Clicking this toggles expansion
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .paneNavItem(
                        cornerRadius = 8.dp,
                        onActivate = { expanded = !expanded },
                        highlightColor = NavHighlight,
                        tapToSelect = true,
                    )
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(IconBoxBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(13.dp))
                Text(
                    text = stringResource(R.string.settings_other_bg_pause_mode_title),
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                // Chevron icon indicating state
                Icon(
                    imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Options list - Only visible when expanded
            if (expanded) Spacer(Modifier.height(10.dp))
            options.forEach { (mode, titleRes, subtitleRes) ->
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .paneNavItem(
                                cornerRadius = 8.dp,
                                onActivate = { onModeChanged(mode) },
                                highlightColor = NavHighlight,
                                tapToSelect = true,
                            )
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentMode == mode,
                            onClick = { onModeChanged(mode) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Accent,
                                unselectedColor = TextSecondary,
                            ),
                            // Let the Row handle the focus
                            modifier = Modifier.focusProperties { canFocus = false }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(titleRes),
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(subtitleRes),
                                color = TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeartbeatFrequencyCard(
    currentFrequency: Int,
    onFrequencyChanged: (Int) -> Unit,
) {
    // Tracks expansion. False = collapsed by default.
    var expanded by remember { mutableStateOf(false) }
    // Raw text while the user is typing; committed as Int on Done/focus-loss.
    var rawText by remember(currentFrequency) { mutableStateOf(currentFrequency.toString()) }
    val focusManager = LocalFocusManager.current

    // Tracks internal focus to prevent redundant commit on initial composition/attach
    var isFocused by remember { mutableStateOf(false) }

    // Derive the effective value and whether the current input is in error,
    // so we can give the user immediate feedback without committing bad state.
    // Use Long to handle Int overflow detection correctly
    val parsedLong = rawText.trim().toLongOrNull()
    val isOverflow = rawText.isNotEmpty() && (parsedLong == null || parsedLong > Int.MAX_VALUE)
    val isTooSmall = parsedLong != null && parsedLong != 0L && parsedLong < 5
    val isError = isOverflow || isTooSmall

    val effectiveLabel = when {
        rawText.isEmpty() || parsedLong == 0L -> stringResource(R.string.settings_other_bg_heartbeat_disabled)
        isOverflow -> stringResource(R.string.settings_other_bg_heartbeat_effective, Int.MAX_VALUE)
        isTooSmall -> stringResource(R.string.settings_other_bg_heartbeat_minimum)
        else -> stringResource(R.string.settings_other_bg_heartbeat_effective, parsedLong!!.toInt())
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardDark)
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .paneNavItem(
                        cornerRadius = 8.dp,
                        onActivate = { expanded = !expanded },
                        highlightColor = NavHighlight,
                        tapToSelect = true,
                    )
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(IconBoxBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Timer,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_other_bg_heartbeat_title),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    AnimatedVisibility(
                        visible = expanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_other_bg_heartbeat_subtitle),
                            color = TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
                // Chevron icon
                Icon(
                    imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            // Collapsible content (Input + Supporting Text)
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = rawText,
                        onValueChange = { rawText = it.filter { c -> c.isDigit() } },
                        singleLine = true,
                        isError = isError,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .paneNavItem(
                                cornerRadius = 8.dp,
                                // onAdjust allows changing the value with D-pad
                                onAdjust = { dir ->
                                    // Calculate the next value using a step of 5
                                    val next = when {
                                        currentFrequency == 5 && dir < 0 -> 0   // If at 5 and pressing Left, jump to 0 (Disabled)
                                        currentFrequency == 0 && dir > 0 -> 5   // If at 0 and pressing Right, jump to 5 (Minimum)
                                        else -> (currentFrequency + dir * 5).coerceAtLeast(0)   // Standard increment/decrement
                                    }
                                    onFrequencyChanged(next)
                                },
                                highlightColor = NavHighlight,
                            )
                            .onFocusChanged { focus ->
                                // Commit and clamp when the user leaves the field,
                                // so tapping elsewhere still saves the value.
                                if (isFocused && !focus.isFocused) {
                                    commitFrequency(rawText, currentFrequency, onFrequencyChanged)
                                }
                                isFocused = focus.isFocused
                            },
                        suffix = { Text("s", color = TextSecondary, fontSize = 13.sp) },
                        supportingText = effectiveLabel.let { label ->
                            {
                                Text(
                                    text = label,
                                    color = if (isError) Error else TextSecondary,
                                    fontSize = 11.sp,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

// Extracted so both Done-action and focus-loss share identical clamping logic.
private fun commitFrequency(raw: String, current: Int, onFrequencyChanged: (Int) -> Unit) {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        if (current != 0) onFrequencyChanged(0)
        return
    }

    val parsedLong = trimmed.toLongOrNull()
    val committed = when {
        parsedLong == null || parsedLong > Int.MAX_VALUE -> Int.MAX_VALUE // Overflow -> clamp to Int.MAX
        parsedLong == 0L -> 0   // revert to default on empty / non-numeric -> disabled
        parsedLong < 5 -> 5     // clamp to minimum
        else -> parsedLong.toInt()
    }

    if (committed != current) {
        onFrequencyChanged(committed)
    }
}
