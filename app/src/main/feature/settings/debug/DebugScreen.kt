package com.winlator.cmod.feature.settings
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.focus.rememberSettingsContentNav
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavHandlers
import com.winlator.cmod.shared.ui.nav.paneNavItem
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import com.winlator.cmod.shared.ui.dialog.PopupDialog
import com.winlator.cmod.shared.ui.toast.WinToast
import com.winlator.cmod.shared.ui.outlinedSwitchColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction

import com.winlator.cmod.runtime.system.LogManager

// Palette (mirrors StoresScreen)
private val BgDark = Color(0xFF11111C)
private val CardDark = Color(0xFF1C1C2A)
private val CardBorder = Color(0xFF2A2A3A)
private val IconBoxBg = Color(0xFF242434)
private val SurfaceDark = Color(0xFF21212A)
private val Accent = Color(0xFF1A9FFF)
private val NavHighlight = Color(0xFF4FC3F7)
private val Warning = Color(0xFFFF4444)
private val Success = Color(0xFF7CC142)
private val TextPrimary = Color(0xFFF0F4FF)
private val TextSecondary = Color(0xFF7A8FA8)
private val SystemTagColor = Color(0xFFFFCC00)

// State
data class DebugState(
    val loggingEnabled: Boolean = false,
    val appDebug: Boolean = false,
    val filteredLogs: Boolean = false,
    val exitReasonLog: Boolean = false,
    val crashLog: Boolean = false,
    val eventWatchLog: Boolean = false,
    val tagFilterMode: LogManager.TagFilterMode = LogManager.TagFilterMode.ALL,
    val selectedTags: List<String> = emptyList(),
    val customTags: List<String> = emptyList(),

    val logcatGroupExpanded: Boolean = false,
    val exitGroupExpanded: Boolean = false,
    val filterGroupExpanded: Boolean = false,

    val wineDebug: Boolean = false,
    val wineChannels: List<String> = emptyList(),
    val wineClasses: List<String> = emptyList(),
    val emulatorLogs: Boolean = false,
    val steamLogs: Boolean = false,
    val inputLogs: Boolean = false,
    val downloadLogs: Boolean = false,
    val recordPerformanceToFile: Boolean = false,
    val vulkanValidationLayers: Boolean = false,
    val wnHybridMode: Boolean = false,
    val logsSize: String = "0 B",
)

data class LogFileEntry(
    val name: String,
    val sizeText: String,
    val dateText: String,
    val absolutePath: String,
    val downloaded: Boolean = false,
)

// Root
@Composable
fun DebugScreen(
    state: DebugState,
    wineChannelOptions: List<String>,
    wineClassOptions: List<String>,
    allLogTagOptions: List<String>,           // LogManager.getAllKnownTags()
    onLoggingEnabledChanged: (Boolean) -> Unit,
    onAppDebugChanged: (Boolean) -> Unit,
    onFilteredLogsChanged: (Boolean) -> Unit,
    onExitReasonLogChanged: (Boolean) -> Unit,
    onCrashLogChanged: (Boolean) -> Unit,
    onEventWatchLogChanged: (Boolean) -> Unit,
    onTagFilterModeChanged: (LogManager.TagFilterMode) -> Unit,
    onSelectedTagsChanged: (List<String>) -> Unit,
    onAddCustomTag: (String) -> Unit,
    onRemoveCustomTag: (String) -> Unit,
    onManualTextFilterChanged: (String) -> Unit,   // not persisted — live field only
    onLogcatGroupExpandedChanged: (Boolean) -> Unit,
    onExitGroupExpandedChanged: (Boolean) -> Unit,
    onFilterGroupExpandedChanged: (Boolean) -> Unit,
    onWineDebugChanged: (Boolean) -> Unit,
    onWineChannelsChanged: (List<String>) -> Unit,
    onWineClassesChanged: (List<String>) -> Unit,
    onResetWineChannels: () -> Unit,
    onRemoveWineChannel: (String) -> Unit,
    onEmulatorLogsChanged: (Boolean) -> Unit,
    onSteamLogsChanged: (Boolean) -> Unit,
    onInputLogsChanged: (Boolean) -> Unit,
    onDownloadLogsChanged: (Boolean) -> Unit,
    onRecordPerformanceToFileChanged: (Boolean) -> Unit,
    onVulkanValidationLayersChanged: (Boolean) -> Unit,
    onWnHybridModeChanged: (Boolean) -> Unit,
    onShareLogs: () -> Unit,
    onDownloadLogs: () -> String?,
    onDeleteLogs: () -> Unit,
    onListLogFiles: () -> List<LogFileEntry>,
    onReadLogFile: (LogFileEntry) -> String,
    onShareLogFile: (LogFileEntry) -> Unit,
    onDownloadLogFile: (LogFileEntry) -> String?,
    onDeleteLogFile: (LogFileEntry) -> Unit,
    bridge: SettingsNavBridge? = null,
) {
    var showChannelsDialog by remember { mutableStateOf(false) }
    var showTagFilterDialog by remember { mutableStateOf(false) }
    var showLogsBrowser by remember { mutableStateOf(false) }
    val layoutDirection = LocalLayoutDirection.current
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val navBarStartPadding = navBarPadding.calculateStartPadding(layoutDirection)
    val navBarEndPadding = navBarPadding.calculateEndPadding(layoutDirection)
    val navBarBottomPadding = navBarPadding.calculateBottomPadding()

    if (showChannelsDialog) {
        WineChannelsDialog(
            options = wineChannelOptions,
            initiallySelected = state.wineChannels,
            onDismiss = { showChannelsDialog = false },
            onConfirm = { selected ->
                onWineChannelsChanged(selected)
                showChannelsDialog = false
            },
        )
    }

    if (showTagFilterDialog) {
        LogTagFilterDialog(
            options = allLogTagOptions,
            initiallySelected = state.selectedTags,
            initialMode = state.tagFilterMode,
            customTags = state.customTags,
            onDismiss = { showTagFilterDialog = false },
            onConfirm = { mode, selected ->
                onTagFilterModeChanged(mode)
                onSelectedTagsChanged(selected)
                showTagFilterDialog = false
            },
            onAddCustomTag = onAddCustomTag,
            onRemoveCustomTag = onRemoveCustomTag,
        )
    }

    if (showLogsBrowser) {
        val initialFiles = remember { onListLogFiles() }
        LogsBrowserDialog(
            initialFiles = initialFiles,
            logsSize = state.logsSize,
            onReadLogFile = onReadLogFile,
            onShareAllLogs = onShareLogs,
            onDownloadAllLogs = onDownloadLogs,
            onDeleteAllLogs = onDeleteLogs,
            onShareLogFile = onShareLogFile,
            onDownloadLogFile = onDownloadLogFile,
            onDeleteLogFile = onDeleteLogFile,
            onDismiss = { showLogsBrowser = false },
        )
    }

    val contentNav = rememberSettingsContentNav(bridge)

    // Enable stable cursor to prevent focus jumping during layout shifts (like collapsing groups)
    androidx.compose.runtime.SideEffect {
        contentNav.stableCursor = true
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
                        bottom = 4.dp + navBarBottomPadding,
                    ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel(stringResource(R.string.common_ui_application))

            SettingsToggleCard(
                title = stringResource(R.string.settings_debug_application_logging_title),
                subtitle = stringResource(R.string.settings_debug_application_logging_subtitle),
                icon = Icons.Outlined.BugReport,
                accentColor = Warning,
                checked = state.loggingEnabled,
                onCheckedChange = onLoggingEnabledChanged,
            )

            AnimatedVisibility(
                visible = state.loggingEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                // Logcat group
                CollapsibleGroup(
                    title = stringResource(R.string.settings_debug_logcat_title),
                    accentColor = TextSecondary,
                    expanded = state.logcatGroupExpanded,
                    onExpandedChange = onLogcatGroupExpandedChanged,
                ) {
                    SettingsToggleCard(
                        title = stringResource(R.string.settings_debug_application_logging_title),
                        subtitle = stringResource(R.string.settings_debug_log_to_file_desc),
                        icon = Icons.Outlined.BugReport,
                        accentColor = Warning,
                        checked = state.appDebug,
                        onCheckedChange = onAppDebugChanged,
                    )

                    SettingsToggleCard(
                        title = stringResource(R.string.settings_debug_steam_logs_title),
                        subtitle = stringResource(R.string.settings_debug_steam_logs_subtitle),
                        icon = Icons.Outlined.SportsEsports,
                        checked = state.steamLogs,
                        onCheckedChange = onSteamLogsChanged,
                    )

                    SettingsToggleCard(
                        title = stringResource(R.string.settings_debug_input_logs),
                        subtitle = stringResource(R.string.settings_debug_input_logs_description),
                        icon = Icons.Outlined.Gamepad,
                        checked = state.inputLogs,
                        onCheckedChange = onInputLogsChanged,
                    )

                    SettingsToggleCard(
                        title = stringResource(R.string.settings_debug_download_logs),
                        subtitle = stringResource(R.string.settings_debug_download_logs_description),
                        icon = Icons.Outlined.CloudDownload,
                        checked = state.downloadLogs,
                        onCheckedChange = onDownloadLogsChanged,
                    )
                }
            }

            AnimatedVisibility(
                visible = state.loggingEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                // Exit group
                CollapsibleGroup(
                    title = stringResource(R.string.common_ui_exit),
                    accentColor = TextSecondary,
                    expanded = state.exitGroupExpanded,
                    onExpandedChange = onExitGroupExpandedChanged,
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {   // This log only works on API 30+ (Android 11+)
                        SettingsToggleCard(
                            title = stringResource(R.string.settings_debug_exit_reason_log_title),
                            subtitle = stringResource(R.string.settings_debug_exit_reason_log_subtitle),
                            icon = Icons.Outlined.BugReport,
                            accentColor = SystemTagColor,
                            checked = state.exitReasonLog,
                            onCheckedChange = onExitReasonLogChanged,
                        )
                    }

                    SettingsToggleCard(
                        title = stringResource(R.string.settings_debug_crash_log_title),
                        subtitle = stringResource(R.string.settings_debug_crash_log_subtitle),
                        icon = Icons.Outlined.BugReport,
                        accentColor = SystemTagColor,
                        checked = state.crashLog,
                        onCheckedChange = onCrashLogChanged,
                    )
                }
            }

            AnimatedVisibility(
                visible = state.loggingEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                // Filter group
                CollapsibleGroup(
                    title = stringResource(R.string.session_gyroscope_filtering),
                    accentColor = TextSecondary,
                    expanded = state.filterGroupExpanded,
                    onExpandedChange = onFilterGroupExpandedChanged,
                ) {
                    SettingsToggleCard(
                        title = stringResource(R.string.settings_filtered_logs_to_file_title),
                        subtitle = stringResource(R.string.settings_filtered_logs_to_file_desc),
                        icon = Icons.Outlined.BugReport,
                        accentColor = Warning,
                        checked = state.filteredLogs,
                        onCheckedChange = onFilteredLogsChanged,
                    )

                    SettingsToggleCard(
                        title = stringResource(R.string.settings_debug_event_watch_log_title),
                        subtitle = stringResource(R.string.settings_debug_event_watch_log_subtitle),
                        icon = Icons.Outlined.BugReport,
                        accentColor = SystemTagColor,
                        checked = state.eventWatchLog,
                        onCheckedChange = onEventWatchLogChanged,
                    )

                    AnimatedVisibility(
                        visible = state.filteredLogs || state.eventWatchLog,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        LogTagFilterCard(
                            mode = state.tagFilterMode,
                            selectedTags = state.selectedTags,
                            enabled = state.filteredLogs || state.eventWatchLog,
                            onEdit = { showTagFilterDialog = true },
                            onModeChanged = onTagFilterModeChanged,
                            onRemoveSelectedTag = { tag ->
                                onSelectedTagsChanged(state.selectedTags - tag)
                            },
                        )
                    }

                    AnimatedVisibility(
                        visible = state.filteredLogs || state.eventWatchLog,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        ManualTextFilterField(
                            enabled = state.filteredLogs || state.eventWatchLog,
                            resetKey = state.filteredLogs to state.eventWatchLog,
                            onTextChanged = onManualTextFilterChanged,
                        )
                    }
                }
            }

            SectionLabel(stringResource(R.string.settings_debug_section_emulation), modifier = Modifier.padding(top = 8.dp))

            SettingsToggleCard(
                title = stringResource(R.string.settings_debug_wine_logs_title),
                subtitle = stringResource(R.string.settings_debug_wine_logs_subtitle),
                icon = Icons.Outlined.Terminal,
                checked = state.wineDebug,
                onCheckedChange = onWineDebugChanged,
            )

            WineChannelsCard(
                channels = state.wineChannels,
                enabled = state.wineDebug,
                onEdit = { showChannelsDialog = true },
                onReset = onResetWineChannels,
                onRemoveChannel = onRemoveWineChannel,
            )

            WineClassesCard(
                options = wineClassOptions,
                selected = state.wineClasses,
                enabled = state.wineDebug,
                onToggle = { cls ->
                    val updated =
                        if (cls in state.wineClasses) {
                            state.wineClasses - cls
                        } else {
                            state.wineClasses + cls
                        }
                    onWineClassesChanged(wineClassOptions.filter { it in updated })
                },
            )

            SettingsToggleCard(
                title = stringResource(R.string.settings_debug_emulator_logs_title),
                subtitle = stringResource(R.string.settings_debug_emulator_logs_subtitle),
                icon = Icons.Outlined.Memory,
                checked = state.emulatorLogs,
                onCheckedChange = onEmulatorLogsChanged,
            )

            SectionLabel(stringResource(R.string.settings_debug_section_subsystems), modifier = Modifier.padding(top = 8.dp))

/*
            SettingsToggleCard(
                title = stringResource(R.string.settings_debug_vulkan_validation_layers_title),
                subtitle = stringResource(R.string.settings_debug_vulkan_validation_layers_subtitle),
                icon = Icons.Outlined.BugReport,
                accentColor = Warning,
                checked = state.vulkanValidationLayers,
                onCheckedChange = onVulkanValidationLayersChanged,
            )
*/

            SettingsToggleCard(
                title = stringResource(R.string.settings_hud_record_to_file_title),
                subtitle = stringResource(R.string.settings_hud_record_to_file_summary),
                icon = Icons.Outlined.Speed,
                checked = state.recordPerformanceToFile,
                onCheckedChange = onRecordPerformanceToFileChanged,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogActionButton(
                    icon = Icons.Outlined.FolderOpen,
                    label = stringResource(R.string.settings_debug_open_logs_folder_short),
                    accentColor = Accent,
                    onClick = { showLogsBrowser = true },
                )
            }

            Spacer(Modifier.height(24.dp))
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

@Composable
private fun ManualTextFilterField(
    enabled: Boolean,
    resetKey: Any,
    onTextChanged: (String) -> Unit,
) {
    var text by remember { mutableStateOf(LogManager.getManualTextFilter()) }
    LaunchedEffect(resetKey) { text = LogManager.getManualTextFilter() }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusManager = LocalFocusManager.current
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = 1.dp,
                    color = if (focused) Accent else CardBorder,
                    shape = RoundedCornerShape(8.dp),
                ).paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = { if (enabled) runCatching { focusRequester.requestFocus() } },
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                ).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_debug_manual_text_filter_hint),
                fontSize = 12.sp,
                color = TextSecondary,
            )
        }
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = {
                text = it
                onTextChanged(it)
            },
            enabled = enabled,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused },
        )
    }
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
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .paneNavItem(
                        cornerRadius = 12.dp,
                        onActivate = { onCheckedChange(!checked) },
                        highlightColor = NavHighlight,
                        tapToSelect = true,
                    )
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
                modifier = Modifier
                    .scale(0.78f)
                    .focusProperties { canFocus = false },
                colors =
                    outlinedSwitchColors(
                        accentColor = accentColor,
                        textSecondaryColor = TextSecondary,
                    ),
            )
        }
    }
}

// Wine debug channels card (shown when Wine debug is enabled)
@Composable
private fun WineChannelsCard(
    channels: List<String>,
    enabled: Boolean,
    onEdit: () -> Unit,
    onReset: () -> Unit,
    onRemoveChannel: (String) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.48f)
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
            Row(
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
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_debug_wine_channels_title),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.settings_debug_wine_channels_summary),
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier =
                        Modifier.paneNavItem(
                            cornerRadius = 8.dp,
                            onActivate = { if (enabled) onEdit() },
                            highlightColor = NavHighlight,
                        ),
                ) {
                    SmallActionButton(
                        label = stringResource(R.string.common_ui_select),
                        textColor = Accent,
                        onClick = { if (enabled) onEdit() },
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier =
                        Modifier.paneNavItem(
                            cornerRadius = 8.dp,
                            onActivate = { if (enabled) onReset() },
                            highlightColor = NavHighlight,
                        ),
                ) {
                    SmallActionButton(
                        label = stringResource(R.string.common_ui_reset),
                        textColor = TextSecondary,
                        onClick = { if (enabled) onReset() },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (channels.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_debug_no_channels_selected),
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                } else {
                    channels.forEach { channel ->
                        key(channel) {
                            ChannelChip(
                                label = channel,
                                onRemove = { if (enabled) onRemoveChannel(channel) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// Wine debug message-class card (err / warn / fix-me / trace)
@Composable
private fun WineClassesCard(
    options: List<String>,
    selected: List<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.48f)
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
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_debug_wine_classes_title),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.settings_debug_wine_classes_summary),
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                options.forEach { cls ->
                    ClassChip(
                        label = cls,
                        isSelected = cls in selected,
                        enabled = enabled,
                        onToggle = { onToggle(cls) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.ClassChip(
    label: String,
    isSelected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    // pointerInput keys never change, so capture the latest callback to avoid acting
    // on a stale selection snapshot.
    val action = rememberUpdatedState { if (enabled) onToggle() }
    val bg = if (isSelected) Accent.copy(alpha = 0.18f) else IconBoxBg
    val borderColor = if (isSelected) Accent.copy(alpha = 0.55f) else CardBorder
    val textColor = if (isSelected) Accent else TextPrimary
    Box(
        modifier =
            Modifier
                .weight(1f)
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .paneNavItem(cornerRadius = 8.dp, onActivate = { if (enabled) onToggle() })
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { action.value() })
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// Log tag/filter channels card (shown when Application Log is enabled)
@Composable
private fun LogTagFilterCard(
    mode: LogManager.TagFilterMode,
    selectedTags: List<String>,
    enabled: Boolean,
    onEdit: () -> Unit,
    onModeChanged: (LogManager.TagFilterMode) -> Unit,
    onRemoveSelectedTag: (String) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.48f)
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
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_debug_log_tag_filter_channel),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    val modeLabel = when (mode) {
                        LogManager.TagFilterMode.ALL -> stringResource(R.string.settings_debug_tag_filter_all)
                        LogManager.TagFilterMode.INCLUDE -> stringResource(R.string.settings_debug_tag_filter_include)
                        LogManager.TagFilterMode.EXCLUDE -> stringResource(R.string.settings_debug_tag_filter_exclude)
                    }
                    Text(
                        text = modeLabel,
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                SmallActionButton(
                    label = stringResource(R.string.common_ui_select),
                    textColor = Accent,
                    onClick = { if (enabled) onEdit() },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = stringResource(R.string.settings_debug_tag_filter_mode), color = TextSecondary, fontSize = 11.sp)
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.settings_debug_tag_filter_all),
                        stringResource(R.string.settings_debug_tag_filter_include),
                        stringResource(R.string.settings_debug_tag_filter_exclude),
                    ),
                    selectedIndex = when (mode) {
                        LogManager.TagFilterMode.ALL -> 0
                        LogManager.TagFilterMode.INCLUDE -> 1
                        LogManager.TagFilterMode.EXCLUDE -> 2
                    },
                    onSelectedIndex = { idx ->
                        onModeChanged(
                            when (idx) {
                                0 -> LogManager.TagFilterMode.ALL
                                1 -> LogManager.TagFilterMode.INCLUDE
                                else -> LogManager.TagFilterMode.EXCLUDE
                            },
                        )
                    },
                )
            }
            if (mode != LogManager.TagFilterMode.ALL) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectedTags.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_debug_no_tags_selected),
                            color = TextSecondary,
                            fontSize = 11.sp,
                        )
                    } else {
                        selectedTags.forEach { tag ->
                            ChannelChip(
                                label = tag,
                                onRemove = { if (enabled) onRemoveSelectedTag(tag) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelChip(
    label: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(IconBoxBg)
                .border(1.dp, CardBorder, RoundedCornerShape(7.dp))
                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier =
                Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .paneNavItem(
                        cornerRadius = 5.dp,
                        onActivate = { onRemove() },
                        highlightColor = NavHighlight,
                        tapToSelect = true,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.settings_debug_remove_channel_desc, label),
                tint = TextSecondary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun SmallActionButton(
    label: String,
    textColor: Color,
    isEntry: Boolean = false,
    onClick: () -> Unit,
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "debugBtnScale",
    )
    Box(
        modifier =
            Modifier
                .scale(scale)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF222232))
                .border(1.dp, textColor.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                .paneNavItem(cornerRadius = 8.dp, onActivate = onClick, isEntry = isEntry)
                .pointerInput(onClick) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = { onClick() },
                    )
                }
                .padding(horizontal = 11.dp, vertical = 6.dp),
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

// Generic MultiSelectDialog
@Composable
private fun MultiSelectDialog(
    title: String,
    options: List<String>,
    initiallySelected: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    headerContent: (@Composable () -> Unit)? = null,
    extraContent: (@Composable (selected: Set<String>, onToggle: (String) -> Unit) -> Unit)? = null,
    systemTags: Set<String> = emptySet(),
    maxWidth: Dp = 460.dp,
) {
    val selected =
        remember {
            mutableStateOf(initiallySelected.toSet())
        }
    val contentRegistry = remember { PaneNavRegistry().apply { stableCursor = true } }
    val footerRegistry = remember { PaneNavRegistry().apply { singleRow = true } }
    var footerZone by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var viewportTop by remember { mutableStateOf(0f) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    // Capture latest options and callbacks to avoid stale data in remembered handlers
    val currentOptions by rememberUpdatedState(options)
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(contentRegistry.activeRow, contentRegistry.activeCol, viewportHeight, footerZone) {
        if (footerZone || !contentRegistry.controllerActive || contentRegistry.manualSelection) return@LaunchedEffect
        val bounds = contentRegistry.activeItemBounds() ?: return@LaunchedEffect
        val margin = (bounds.second - bounds.first) + with(density) { 10.dp.toPx() }
        val vpBottom = viewportTop + viewportHeight
        val delta = when {
            bounds.second + margin > vpBottom -> bounds.second + margin - vpBottom
            bounds.first - margin < viewportTop -> bounds.first - margin - viewportTop
            else -> 0f
        }
        if (delta != 0f) runCatching { gridState.animateScrollBy(delta) }
    }
    val toFooter: () -> Unit = {
        footerZone = true
        contentRegistry.controllerActive = false
        footerRegistry.controllerActive = true
        footerRegistry.reset()
    }
    SideEffect {
        contentRegistry.onEdgeDown = {
            if (gridState.canScrollForward) {
                val b = contentRegistry.activeItemBounds()
                val step = if (b != null) (b.second - b.first) + with(density) { 8.dp.toPx() } else viewportHeight * 0.3f
                scope.launch { runCatching { gridState.animateScrollBy(step) } }
            } else {
                toFooter()
            }
        }
        contentRegistry.onEdgeLeft = toFooter
        contentRegistry.onEdgeRight = toFooter
        footerRegistry.onEdgeUp = {
            footerZone = false
            footerRegistry.controllerActive = false
            contentRegistry.controllerActive = true
        }
    }
    val handlers =
        remember {
            paneNavHandlers(
                onDismiss = { currentOnDismiss() },
                onStart = {
                    val ordered = currentOptions.filter { it in selected.value }
                    currentOnConfirm(ordered)
                },
                registry = { if (footerZone) footerRegistry else contentRegistry },
            )
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        DialogPaneNav(handlers)
        CompositionLocalProvider(LocalPaneNav provides contentRegistry) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val availableHeight = maxHeight
            Box(
                modifier =
                    Modifier
                        .widthIn(max = maxWidth)
                        .fillMaxWidth()
                        .heightIn(max = availableHeight)
                        .clip(RoundedCornerShape(18.dp))
                        .background(CardDark)
                        .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                        .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Column(modifier = Modifier.fillMaxHeight()) {
                    Text(
                        text = title,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))

                    headerContent?.invoke()

                    // Optional hint area can be provided by caller via extraContent or you can keep a default hint
                    Spacer(Modifier.height(12.dp))

                    ChannelGrid(
                        options = options,
                        systemTags = systemTags,
                        selected = selected.value,
                        gridState = gridState,
                        onViewport = { top, h ->
                            viewportTop = top
                            viewportHeight = h
                        },
                        onToggle = { channel ->
                            selected.value =
                                if (channel in selected.value) {
                                    selected.value - channel
                                } else {
                                    selected.value + channel
                                }
                        },
                    )

                    // If caller wants to render extra UI (mode switch, add tag field), call it here.
                    extraContent?.invoke(selected.value) { channel ->
                        selected.value =
                            if (channel in selected.value) {
                                selected.value - channel
                            } else {
                                selected.value + channel
                            }
                    }

                    Spacer(Modifier.height(14.dp))
                    CompositionLocalProvider(LocalPaneNav provides footerRegistry) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        ) {
                            SmallActionButton(
                                label = stringResource(R.string.common_ui_cancel),
                                textColor = TextSecondary,                                onClick = onDismiss,
                            )
                            SmallActionButton(
                                label = stringResource(R.string.common_ui_confirm),
                                textColor = Accent,                                isEntry = true,
                                onClick = {
                                    val ordered = options.filter { it in selected.value }
                                    onConfirm(ordered)
                                },
                            )
                        }
                    }
                }
            }
        }
        }
    }
}


// Wine debug channel selector dialog
@Composable
private fun WineChannelsDialog(
    options: List<String>,
    initiallySelected: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    MultiSelectDialog(
        title = stringResource(R.string.settings_debug_wine_debug_channel),
        options = options,
        initiallySelected = initiallySelected,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        extraContent = null // no extra UI needed for wine channels
    )
}

@Composable
private fun LogTagFilterDialog(
    options: List<String>,
    initiallySelected: List<String>,
    initialMode: LogManager.TagFilterMode,
    customTags: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (LogManager.TagFilterMode, List<String>) -> Unit,
    onAddCustomTag: (String) -> Unit,
    onRemoveCustomTag: (String) -> Unit,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var newTagText by remember { mutableStateOf("") }
    // Custom tags are just as selectable as the build-discovered ones — merge
    // them into the grid instead of only listing them in the management row below.
    val allOptions = remember(options, customTags) { (options + customTags).distinct().sorted() }

    MultiSelectDialog(
        title = stringResource(R.string.settings_debug_log_tag_filter_channel),
        options = allOptions,
        systemTags = remember { LogManager.getSystemTags() },
        initiallySelected = initiallySelected,
        onDismiss = onDismiss,
        onConfirm = { selected -> onConfirm(mode, selected) },
        maxWidth = 680.dp,
        headerContent = {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                // Top Row: Mode Switch and Add Field side-by-side
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Mode Section
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_debug_tag_filter_mode).uppercase(),
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        SegmentedControl(
                            options = listOf(
                                stringResource(R.string.settings_debug_tag_filter_all),
                                stringResource(R.string.settings_debug_tag_filter_include),
                                stringResource(R.string.settings_debug_tag_filter_exclude),
                            ),
                            selectedIndex = when (mode) {
                                LogManager.TagFilterMode.ALL -> 0
                                LogManager.TagFilterMode.INCLUDE -> 1
                                LogManager.TagFilterMode.EXCLUDE -> 2
                            },
                            onSelectedIndex = { idx ->
                                mode = when (idx) {
                                    0 -> LogManager.TagFilterMode.ALL
                                    1 -> LogManager.TagFilterMode.INCLUDE
                                    else -> LogManager.TagFilterMode.EXCLUDE
                                }
                            }
                        )
                    }

                    // Add Custom Tag Section
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_debug_add_custom_tag_hint).uppercase(),
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Simple state to track focus for the border color
                            var isTextFieldFocused by remember { mutableStateOf(false) }

                            // Use BasicTextField for full control over vertical padding
                            androidx.compose.foundation.text.BasicTextField(
                                value = newTagText,
                                onValueChange = { newTagText = it },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = TextPrimary,
                                    fontSize = 13.sp
                                ),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .onFocusChanged { isTextFieldFocused = it.isFocused },
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .background(Color.Transparent, RoundedCornerShape(8.dp))
                                            .border(
                                                width = 1.dp,
                                                color = if (isTextFieldFocused) Accent else CardBorder,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .padding(horizontal = 12.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (newTagText.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.settings_debug_add_custom_tag_hint),
                                                fontSize = 12.sp,
                                                color = TextSecondary
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            SmallActionButton(
                                label = stringResource(R.string.common_ui_add),
                                textColor = Accent,
                                onClick = {
                                    val tag = newTagText.trim()
                                    if (tag.isNotEmpty()) {
                                        onAddCustomTag(tag)
                                        newTagText = ""
                                    }
                                }
                            )
                        }
                    }
                }

                // Custom Tags row
                if (customTags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = Color(0xFF2A2A3A), thickness = 0.5.dp)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("CUSTOM:", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        customTags.forEach { tag ->
                            RemovableTagChip(tag = tag, onRemove = { onRemoveCustomTag(tag) })
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    HorizontalDivider(color = Color(0xFF2A2A3A), thickness = 0.5.dp)
                }
            }
        },
    )
}

@Composable
private fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelectedIndex: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceDark)
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            .padding(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Accent else Color.Transparent)
                    // Add paneNavItem so the controller can focus this option
                    .paneNavItem(
                        cornerRadius = 8.dp,
                        onActivate = { onSelectedIndex(index) },
                        highlightColor = NavHighlight,
                        tapToSelect = true
                    )
                    .clickable { onSelectedIndex(index) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Color.White else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun RemovableTagChip(tag: String, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDark)
            .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(text = tag, color = TextPrimary, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                // Add paneNavItem to the 'X' button container
                .paneNavItem(
                    cornerRadius = 10.dp,
                    onActivate = { onRemove() },
                    highlightColor = NavHighlight,
                    tapToSelect = true
                )
                .clickable { onRemove() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun ColumnScope.ChannelGrid(
    options: List<String>,
    selected: Set<String>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onViewport: (Float, Int) -> Unit,
    onToggle: (String) -> Unit,
    systemTags: Set<String> = emptySet(),   // new — tags that render in a distinct color
) {
    if (options.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_debug_no_channels_available),
            color = TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 24.dp),
        )
        return
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 92.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .onGloballyPositioned {
                    onViewport(it.positionInWindow().y, it.size.height)
                },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(options) { index, channel ->
            val isSystem = channel in systemTags
            val chipAccent = if (isSystem) SystemTagColor else Accent
            SelectableChannelChip(
                label = channel,
                isSelected = channel in selected,
                isEntry = index == 0,
                onToggle = { onToggle(channel) },
                tint = chipAccent,
            )
        }
    }
}

@Composable
private fun SelectableChannelChip(
    label: String,
    isSelected: Boolean,
    isEntry: Boolean,
    onToggle: () -> Unit,
    tint: Color = Accent,
) {
    val isSystemTag = tint == SystemTagColor

    val bg = if (isSelected) tint.copy(alpha = 0.18f) else IconBoxBg
    // If it's a system tag, show a subtle tinted border and text even when unselected
    val borderColor = when {
        isSelected -> tint.copy(alpha = 0.55f)
        isSystemTag -> tint.copy(alpha = 0.35f) // Subtle yellow border for system tags
        else -> CardBorder
    }
    val textColor = when {
        isSelected -> tint
        isSystemTag -> tint.copy(alpha = 0.85f) // Dimmed yellow text for system tags
        else -> TextPrimary
    }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .paneNavItem(cornerRadius = 8.dp, onActivate = onToggle, isEntry = isEntry)
                .pointerInput(label) {
                    detectTapGestures(onTap = { onToggle() })
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun RowScope.LogActionButton(
    icon: ImageVector,
    label: String,
    accentColor: Color,
    onClick: () -> Unit,
    sublabel: String? = null,
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "logActionScale",
    )
    Row(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .scale(scale)
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .border(1.dp, accentColor.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                .paneNavItem(
                    cornerRadius = 12.dp,
                    onActivate = { onClick() },
                    highlightColor = NavHighlight
                )
                .pointerInput(onClick) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = { onClick() },
                    )
                }
                .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LogsBrowserDialog(
    initialFiles: List<LogFileEntry>,
    logsSize: String,
    onReadLogFile: (LogFileEntry) -> String,
    onShareAllLogs: () -> Unit,
    onDownloadAllLogs: () -> String?,
    onDeleteAllLogs: () -> Unit,
    onShareLogFile: (LogFileEntry) -> Unit,
    onDownloadLogFile: (LogFileEntry) -> String?,
    onDeleteLogFile: (LogFileEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var files by remember { mutableStateOf(initialFiles) }
    var selected by remember { mutableStateOf<LogFileEntry?>(null) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var downloaded by remember {
        mutableStateOf(initialFiles.filter { it.downloaded }.map { it.absolutePath }.toSet())
    }
    val registry = remember { PaneNavRegistry().apply { stableCursor = true } }
    var rightStick by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(selected) { registry.reset() }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        val dialogView = LocalView.current
        val context = LocalContext.current
        val showSavedToast: (String) -> Unit = { path ->
            WinToast.show(context, context.getString(R.string.settings_debug_logs_saved, path), dialogView)
        }
        val logNavHandlers =
            remember {
                paneNavHandlers(
                    onDismiss = { if (selected != null) selected = null else onDismiss() },
                    onScroll = { rightStick = it },
                ) { registry }
            }
        DialogPaneNav(logNavHandlers)
        CompositionLocalProvider(LocalPaneNav provides registry) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val availableHeight = maxHeight
            Box(
                modifier =
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .heightIn(max = availableHeight)
                        .clip(RoundedCornerShape(18.dp))
                        .background(CardDark)
                        .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column(modifier = Modifier.fillMaxHeight()) {
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            val forward = targetState != null
                            val direction =
                                if (forward) {
                                    AnimatedContentTransitionScope.SlideDirection.Left
                                } else {
                                    AnimatedContentTransitionScope.SlideDirection.Right
                                }
                            (slideIntoContainer(direction, tween(280)) + fadeIn(tween(280)))
                                .togetherWith(
                                    slideOutOfContainer(direction, tween(280)) + fadeOut(tween(280)),
                                )
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "logsBrowserNav",
                    ) { target ->
                        if (target == null) {
                            LogFileListView(
                                files = files,
                                onOpen = { selected = it },
                                onShareAllLogs = onShareAllLogs,
                                onDownloadAllLogs = { onDownloadAllLogs()?.let(showSavedToast) },
                                onDeleteAllLogs = { showDeleteAllConfirm = true },
                                onShareLogFile = onShareLogFile,
                                downloadedPaths = downloaded,
                                onDownloadLogFile = { entry ->
                                    onDownloadLogFile(entry)?.let { path ->
                                        downloaded = downloaded + entry.absolutePath
                                        showSavedToast(path)
                                    }
                                },
                                onDeleteLogFile = { entry ->
                                    onDeleteLogFile(entry)
                                    files = files.filterNot { it.absolutePath == entry.absolutePath }
                                },
                                onClose = onDismiss,
                            )
                        } else {
                            LogDetailView(
                                entry = target,
                                onBack = { selected = null },
                                onClose = onDismiss,
                                onReadLogFile = onReadLogFile,
                                rightStick = rightStick,
                            )
                        }
                    }
                }
            }
        }
        }
    }

    if (showDeleteAllConfirm) {
        Dialog(onDismissRequest = { showDeleteAllConfirm = false }) {
            PopupDialog(
                title = stringResource(R.string.settings_debug_delete_logs_confirm_title),
                message = stringResource(R.string.settings_debug_delete_logs_confirm_message, logsSize),
                confirmLabel = stringResource(R.string.settings_debug_delete_logs_short),
                modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                icon = Icons.Outlined.Delete,
                accentColor = Warning,
                onCancel = { showDeleteAllConfirm = false },
                onConfirm = {
                    showDeleteAllConfirm = false
                    onDeleteAllLogs()
                    files = emptyList()
                },
            )
        }
    }
}

@Composable
private fun LogsHeaderShareAll(onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "shareAllScale",
    )
    Row(
        modifier =
            Modifier
                .scale(scale)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF222232))
                .border(1.dp, Accent.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                .paneNavItem(cornerRadius = 8.dp, onActivate = { onClick() })
                .pointerInput(onClick) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = { onClick() },
                    )
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Share,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.settings_debug_share_all_logs_short),
            color = Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun LogsHeaderDownloadAll(onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "downloadAllScale",
    )
    Row(
        modifier =
            Modifier
                .scale(scale)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF222232))
                .border(1.dp, Success.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                .paneNavItem(cornerRadius = 8.dp, onActivate = { onClick() })
                .pointerInput(onClick) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = { onClick() },
                    )
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Download,
            contentDescription = null,
            tint = Success,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.settings_debug_download_all_logs_short),
            color = Success,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun LogsHeaderDeleteAll(onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "deleteAllScale",
    )
    Row(
        modifier =
            Modifier
                .scale(scale)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF222232))
                .border(1.dp, Warning.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                .paneNavItem(cornerRadius = 8.dp, onActivate = { onClick() })
                .pointerInput(onClick) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = { onClick() },
                    )
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.DeleteSweep,
            contentDescription = null,
            tint = Warning,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.settings_debug_delete_all_logs_short),
            color = Warning,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun LogsHeaderIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = TextSecondary,
    iconSize: Dp = 18.dp,
) {
    Box(
        modifier =
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .paneNavItem(cornerRadius = 8.dp, onActivate = { onClick() })
                .pointerInput(onClick) {
                    detectTapGestures(onTap = { onClick() })
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun ColumnScope.LogFileList(
    files: List<LogFileEntry>,
    onOpen: (LogFileEntry) -> Unit,
    onShare: (LogFileEntry) -> Unit,
    downloadedPaths: Set<String>,
    onDownload: (LogFileEntry) -> Unit,
    onDelete: (LogFileEntry) -> Unit,
) {
    if (files.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_debug_no_logs_available),
            color = TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 24.dp),
        )
        return
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val nav = LocalPaneNav.current
    var viewportTop by remember { mutableStateOf(0f) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    if (nav != null) {
        LaunchedEffect(nav.activeRow, nav.activeCol, viewportHeight) {
            if (!nav.controllerActive || nav.manualSelection) return@LaunchedEffect
            val bounds = nav.activeItemBounds() ?: return@LaunchedEffect
            val rowH = bounds.second - bounds.first
            val margin = (rowH * 2f + with(density) { 12.dp.toPx() }).coerceAtMost(viewportHeight * 0.4f)
            val vpBottom = viewportTop + viewportHeight
            val delta = when {
                bounds.second + margin > vpBottom -> bounds.second + margin - vpBottom
                bounds.first - margin < viewportTop -> bounds.first - margin - viewportTop
                else -> 0f
            }
            if (delta != 0f) runCatching { listState.animateScrollBy(delta) }
        }
        SideEffect {
            val rowStep: () -> Float = {
                val b = nav.activeItemBounds()
                if (b != null) (b.second - b.first) + with(density) { 8.dp.toPx() } else viewportHeight * 0.3f
            }
            nav.onEdgeDown = {
                if (listState.canScrollForward) scope.launch { runCatching { listState.animateScrollBy(rowStep()) } }
            }
            nav.onEdgeUp = {
                if (listState.canScrollBackward) scope.launch { runCatching { listState.animateScrollBy(-rowStep()) } }
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .onGloballyPositioned {
                    viewportTop = it.positionInWindow().y
                    viewportHeight = it.size.height
                },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(files, key = { it.absolutePath }) { entry ->
            LogFileRow(
                entry = entry,
                onOpen = { onOpen(entry) },
                onShare = { onShare(entry) },
                isDownloaded = entry.absolutePath in downloadedPaths,
                onDownload = { onDownload(entry) },
                onDelete = { onDelete(entry) },
            )
        }
    }
}

@Composable
private fun LogFileRow(
    entry: LogFileEntry,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    isDownloaded: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(IconBoxBg)
                .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .paneNavItem(cornerRadius = 8.dp, onActivate = { onOpen() })
                    .pointerInput(entry.absolutePath) {
                        detectTapGestures(onTap = { onOpen() })
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                text = entry.name,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${entry.sizeText} · ${entry.dateText}",
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        LogRowIconButton(
            icon = Icons.Outlined.Delete,
            tint = Warning,
            contentDescription = stringResource(R.string.settings_debug_delete_logs),
            onClick = onDelete,
        )
        Spacer(Modifier.width(12.dp))
        LogRowIconButton(
            icon = Icons.Outlined.Share,
            tint = Accent,
            contentDescription = stringResource(R.string.settings_debug_share_logs),
            onClick = onShare,
        )
        Spacer(Modifier.width(12.dp))
        LogRowIconButton(
            icon = Icons.Outlined.Download,
            tint = Success,
            contentDescription = stringResource(R.string.settings_debug_download_logs),
            onClick = onDownload,
            filled = isDownloaded,
        )
    }
}

@Composable
private fun LogRowIconButton(
    icon: ImageVector,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit,
    filled: Boolean = false,
) {
    val shape = RoundedCornerShape(10.dp)
    val fillBrush =
        if (filled) {
            Brush.verticalGradient(listOf(tint.copy(alpha = 0.34f), tint.copy(alpha = 0.14f)))
        } else {
            SolidColor(Color(0xFF222232))
        }
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(shape)
                .background(fillBrush, shape)
                .border(1.dp, tint.copy(alpha = if (filled) 0.65f else 0.30f), shape)
                .paneNavItem(cornerRadius = 8.dp, onActivate = { onClick() })
                .pointerInput(onClick) {
                    detectTapGestures(onTap = { onClick() })
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun LogFileListView(
    files: List<LogFileEntry>,
    onOpen: (LogFileEntry) -> Unit,
    onShareAllLogs: () -> Unit,
    onDownloadAllLogs: () -> Unit,
    onDeleteAllLogs: () -> Unit,
    onShareLogFile: (LogFileEntry) -> Unit,
    downloadedPaths: Set<String>,
    onDownloadLogFile: (LogFileEntry) -> Unit,
    onDeleteLogFile: (LogFileEntry) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_debug_logs_browser_title),
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            LogsHeaderDeleteAll(onClick = onDeleteAllLogs)
            Spacer(Modifier.width(16.dp))
            LogsHeaderShareAll(onClick = onShareAllLogs)
            Spacer(Modifier.width(16.dp))
            LogsHeaderDownloadAll(onClick = onDownloadAllLogs)
            Spacer(Modifier.width(8.dp))
            LogsHeaderIcon(
                icon = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.common_ui_close),
                onClick = onClose,
                tint = TextPrimary,
                iconSize = 22.dp,
            )
        }
        Spacer(Modifier.height(12.dp))
        LogFileList(
            files = files,
            onOpen = onOpen,
            onShare = onShareLogFile,
            downloadedPaths = downloadedPaths,
            onDownload = onDownloadLogFile,
            onDelete = onDeleteLogFile,
        )
    }
}

@Composable
private fun LogDetailView(
    entry: LogFileEntry,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onReadLogFile: (LogFileEntry) -> String,
    rightStick: Float,
) {
    var content by remember(entry.absolutePath) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.absolutePath) {
        content = withContext(Dispatchers.IO) { onReadLogFile(entry) }
    }
    val titleAlpha by animateFloatAsState(
        targetValue = if (content != null) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "logTitleFade",
    )
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogsHeaderIcon(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.common_ui_back),
                onClick = onBack,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.name,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f)
                        .alpha(titleAlpha),
            )
            Spacer(Modifier.width(6.dp))
            LogsHeaderIcon(
                icon = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.common_ui_close),
                onClick = onClose,
                tint = TextPrimary,
                iconSize = 22.dp,
            )
        }
        Spacer(Modifier.height(12.dp))
        LogContentBody(
            content = content,
            entry = entry,
            rightStick = rightStick,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        )
    }
}

@Composable
private fun LogContentBody(
    content: String?,
    entry: LogFileEntry,
    rightStick: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = content == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            CircularProgressIndicator(
                color = Accent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(40.dp),
            )
        }
        AnimatedVisibility(
            visible = content != null,
            enter = fadeIn(animationSpec = tween(durationMillis = 320)),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "${entry.sizeText} · ${entry.dateText}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val logScrollState = rememberScrollState()
                LaunchedEffect(rightStick) {
                    if (rightStick == 0f) return@LaunchedEffect
                    while (true) {
                        logScrollState.scrollBy(rightStick * 26f)
                        withFrameNanos { }
                    }
                }
                val scrollbarAlpha by animateFloatAsState(
                    targetValue = if (logScrollState.isScrollInProgress) 1f else 0f,
                    animationSpec =
                        tween(
                            durationMillis = if (logScrollState.isScrollInProgress) 150 else 500,
                            delayMillis = if (logScrollState.isScrollInProgress) 0 else 300,
                        ),
                    label = "scrollbarAlpha",
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceDark)
                            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                            .verticalScrollbar(
                                logScrollState,
                                TextSecondary.copy(alpha = 0.6f)
                            ) { scrollbarAlpha }
                            .padding(10.dp)
                            .verticalScroll(logScrollState),
                ) {
                    SelectionContainer {
                        Text(
                            text = content.orEmpty(),
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.verticalScrollbar(
    scrollState: ScrollState,
    thumbColor: Color,
    width: Dp = 3.dp,
    minThumbHeight: Dp = 24.dp,
    edgePadding: Dp = 5.dp,
    alpha: () -> Float = { 1f },
): Modifier =
    drawWithContent {
        drawContent()
        val thumbAlpha = alpha().coerceIn(0f, 1f)
        val maxValue = scrollState.maxValue
        if (maxValue <= 0 || thumbAlpha <= 0f) return@drawWithContent
        val widthPx = width.toPx()
        val padPx = edgePadding.toPx()
        val viewportHeight = size.height
        val trackHeight = (viewportHeight - 2 * padPx).coerceAtLeast(0f)
        val contentHeight = viewportHeight + maxValue
        val thumbHeight =
            (trackHeight * (viewportHeight / contentHeight))
                .coerceIn(minThumbHeight.toPx().coerceAtMost(trackHeight), trackHeight)
        val thumbOffsetY = padPx + (scrollState.value.toFloat() / maxValue) * (trackHeight - thumbHeight)
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(size.width - widthPx - padPx, thumbOffsetY),
            size = Size(widthPx, thumbHeight),
            cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f),
            alpha = thumbAlpha,
        )
    }

@Composable
private fun CollapsibleGroup(
    title: String,
    subtitle: String? = null,
    accentColor: Color = Accent,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .paneNavItem(
                        cornerRadius = 10.dp,
                        onActivate = { onExpandedChange(!expanded) },
                        highlightColor = NavHighlight,
                        tapToSelect = true
                    )
                    .padding(vertical = 4.dp, horizontal = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title.uppercase(),
                        color = accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                }
            }
        }
    }
}