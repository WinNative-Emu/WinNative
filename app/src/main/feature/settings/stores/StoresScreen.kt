package com.winlator.cmod.feature.settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.focus.rememberSettingsContentNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.paneNavItem
import com.winlator.cmod.shared.ui.outlinedSwitchColors

// Palette
private val BgDark = Color(0xFF11111C)
private val CardDark = Color(0xFF1C1C2A)
private val CardBorder = Color(0xFF2A2A3A)
private val IconBoxBg = Color(0xFF242434)
private val SurfaceDark = Color(0xFF21212A)
private val Accent = Color(0xFF1A9FFF)
private val NavHighlight = Color(0xFF4FC3F7)
private val TextPrimary = Color(0xFFF0F4FF)
private val TextSecondary = Color(0xFF7A8FA8)
private val Divider = Color(0xFF343434)
private val DangerRed = Color(0xFFFF7A88)
private val StatusGreen = Color(0xFF3FB950)

// State
data class StoreState(
    val isSteamLoggedIn: Boolean = false,
    val isEpicLoggedIn: Boolean = false,
    val isGogLoggedIn: Boolean = false,
    val isItchLoggedIn: Boolean = false,
    val itchUserName: String = "",
    val sharedFolder: Boolean = true,
    val downloadSpeed: Int = 24,
    val downloadServer: Int = 0,
    val downloadServerManuallySet: Boolean = false,
    val defaultFolder: String = "",
    val steamFolder: String = "",
    val epicFolder: String = "",
    val gogFolder: String = "",
    val itchFolder: String = "",
    val containerLanguageLabels: List<String> = emptyList(),
    val containerLanguageIndex: Int = 0,
)

@Composable
fun StoresScreen(
    state: StoreState,
    serverOptions: List<Pair<Int, String>>,
    onSteamSignIn: () -> Unit,
    onSteamSignOut: () -> Unit,
    onEpicSignIn: () -> Unit,
    onEpicSignOut: () -> Unit,
    onGogSignIn: () -> Unit,
    onGogSignOut: () -> Unit,
    onItchSignIn: () -> Unit,
    onItchSignOut: () -> Unit,
    onSharedFolderChanged: (Boolean) -> Unit,
    onDownloadSpeedChanged: (Int) -> Unit,
    onDownloadServerChanged: (Int) -> Unit,
    onPickDefaultFolder: () -> Unit,
    onPickSteamFolder: () -> Unit,
    onPickEpicFolder: () -> Unit,
    onPickGogFolder: () -> Unit,
    onPickItchFolder: () -> Unit,
    onContainerLanguageSelected: (Int) -> Unit,
    bridge: SettingsNavBridge? = null,
) {
    val layoutDirection = LocalLayoutDirection.current
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val navBarStartPadding = navBarPadding.calculateStartPadding(layoutDirection)
    val navBarEndPadding = navBarPadding.calculateEndPadding(layoutDirection)
    val navBarBottomPadding = navBarPadding.calculateBottomPadding()
    val contentNav = rememberSettingsContentNav(bridge)
    val downloadSpeedOptions =
        listOf(
            8 to stringResource(R.string.stores_accounts_download_speed_conservative),
            16 to stringResource(R.string.stores_accounts_download_speed_balanced),
            24 to stringResource(R.string.stores_accounts_download_speed_standard),
            32 to stringResource(R.string.stores_accounts_download_speed_performance),
        )

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
            SectionLabel(stringResource(R.string.stores_accounts_connected_stores))

            StoreCard(
                name = stringResource(R.string.stores_accounts_steam_integration_title),
                icon = Icons.Outlined.Gamepad,
                accentColor = Color(0xFF66C0F4),
                isLoggedIn = state.isSteamLoggedIn,
                onSignIn = onSteamSignIn,
                onSignOut = onSteamSignOut,
            )
            StoreCard(
                name = stringResource(R.string.preloader_platform_epic),
                icon = Icons.Outlined.Gamepad,
                accentColor = Color(0xFF8BAFD4),
                isLoggedIn = state.isEpicLoggedIn,
                onSignIn = onEpicSignIn,
                onSignOut = onEpicSignOut,
            )
            StoreCard(
                name = stringResource(R.string.preloader_platform_gog),
                icon = Icons.Outlined.Gamepad,
                accentColor = Color(0xFFA855F7),
                isLoggedIn = state.isGogLoggedIn,
                onSignIn = onGogSignIn,
                onSignOut = onGogSignOut,
            )
            StoreCard(
                name = stringResource(R.string.itch_store_title),
                icon = Icons.Outlined.Gamepad,
                accentColor = Color(0xFFFA5C5C),
                isLoggedIn = state.isItchLoggedIn,
                onSignIn = onItchSignIn,
                onSignOut = onItchSignOut,
            )

            SectionLabel(stringResource(R.string.stores_accounts_download_settings), modifier = Modifier.padding(top = 8.dp))

            SettingsToggleCard(
                title = stringResource(R.string.stores_accounts_shared_downloads_folder),
                subtitle = stringResource(R.string.stores_accounts_shared_downloads_subtitle),
                icon = Icons.Outlined.FolderShared,
                checked = state.sharedFolder,
                onCheckedChange = onSharedFolderChanged,
            )

            AnimatedContent(
                targetState = state.sharedFolder,
                transitionSpec = {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(160)) using
                        SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> tween(240) })
                },
                label = "folderPaths",
            ) { shared ->
                if (shared) {
                    FolderPathCard(
                        label = stringResource(R.string.stores_accounts_default_downloads_folder),
                        path = state.defaultFolder,
                        onBrowse = onPickDefaultFolder,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FolderPathCard(
                            stringResource(R.string.stores_accounts_steam_downloads),
                            state.steamFolder,
                            onPickSteamFolder,
                        )
                        FolderPathCard(
                            stringResource(R.string.stores_accounts_epic_downloads),
                            state.epicFolder,
                            onPickEpicFolder,
                        )
                        FolderPathCard(
                            stringResource(R.string.stores_accounts_gog_downloads),
                            state.gogFolder,
                            onPickGogFolder,
                        )
                        FolderPathCard(
                            stringResource(R.string.itch_store_downloads),
                            state.itchFolder,
                            onPickItchFolder,
                        )
                    }
                }
            }

            SectionLabel(stringResource(R.string.steam_section_title), modifier = Modifier.padding(top = 8.dp))

            SettingsDropdownCard(
                title = stringResource(R.string.stores_accounts_download_speed),
                subtitle = stringResource(R.string.stores_accounts_download_speed_subtitle),
                icon = Icons.Outlined.Speed,
                selectedValue = state.downloadSpeed,
                options = downloadSpeedOptions,
                onOptionSelected = onDownloadSpeedChanged,
                highlightMaxValue = true,
            )

            val serverSubtitle =
                if (!state.downloadServerManuallySet && state.downloadServer != 0) {
                    val name = serverOptions.firstOrNull { it.first == state.downloadServer }?.second ?: ""
                    stringResource(R.string.stores_accounts_download_server_auto_detected, name)
                } else {
                    stringResource(R.string.stores_accounts_download_server_subtitle)
                }
            SettingsDropdownCard(
                title = stringResource(R.string.stores_accounts_download_server),
                subtitle = serverSubtitle,
                icon = Icons.Outlined.Public,
                selectedValue = state.downloadServer,
                options = serverOptions,
                onOptionSelected = onDownloadServerChanged,
            )

            val gameLanguageOptions = remember(state.containerLanguageLabels) {
                state.containerLanguageLabels.mapIndexed { index, label -> index to label }
            }
            SettingsDropdownCard(
                title = stringResource(R.string.settings_other_game_language_title),
                subtitle = stringResource(R.string.settings_other_game_language_summary),
                icon = Icons.Outlined.Language,
                selectedValue = state.containerLanguageIndex,
                options = gameLanguageOptions,
                onOptionSelected = onContainerLanguageSelected,
            )

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

// Sign-out confirmation dialog
@Composable
private fun SignOutConfirmDialog(
    storeName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardDark)
                    .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                    .padding(24.dp),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.stores_accounts_sign_out_confirm, storeName),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.stores_accounts_sign_out_message),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    ActionButton(
                        label = stringResource(R.string.common_ui_cancel),
                        textColor = TextSecondary,
                        onClick = onDismiss,
                    )
                    ActionButton(
                        label = stringResource(R.string.common_ui_sign_out),
                        textColor = DangerRed,
                        onClick = {
                            onConfirm()
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

// Store card
@Composable
private fun StoreCard(
    name: String,
    icon: ImageVector,
    accentColor: Color,
    isLoggedIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    var showSignOutDialog by remember { mutableStateOf(false) }
    if (showSignOutDialog) {
        SignOutConfirmDialog(
            storeName = name,
            onConfirm = onSignOut,
            onDismiss = { showSignOutDialog = false },
        )
    }

    val pulse = rememberInfiniteTransition(label = "pulse_$name")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.7f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "scale_$name",
    )
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "alpha_$name",
    )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon box
            Box(
                modifier =
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(IconBoxBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = name,
                    tint = accentColor,
                    modifier = Modifier.size(21.dp),
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isLoggedIn) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(10.dp)
                                        .scale(pulseScale)
                                        .clip(CircleShape)
                                        .background(StatusGreen.copy(alpha = pulseAlpha)),
                            )
                        }
                        Box(
                            modifier =
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isLoggedIn) {
                                            StatusGreen
                                        } else {
                                            TextSecondary.copy(alpha = 0.4f)
                                        },
                                    ),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text =
                            if (isLoggedIn) {
                                stringResource(
                                    R.string.common_ui_signed_in,
                                )
                            } else {
                                stringResource(R.string.google_cloud_status_not_signed_in)
                            },
                        color = if (isLoggedIn) StatusGreen else TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }

            ActionButton(
                label = if (isLoggedIn) stringResource(R.string.common_ui_sign_out) else stringResource(R.string.common_ui_sign_in),
                textColor = if (isLoggedIn) DangerRed else accentColor,
                onClick = if (isLoggedIn) ({ showSignOutDialog = true }) else onSignIn,
            )
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "btnScale",
    )
    Box(
        modifier =
            Modifier
                .scale(scale)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF222232))
                .border(1.dp, textColor.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = onClick,
                    highlightColor = NavHighlight,
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
                }.padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
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
    cardColor: Color = CardDark,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(cardColor)
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

// Settings dropdown card
@Composable
private fun SettingsDropdownCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selectedValue: Int,
    options: List<Pair<Int, String>>,
    onOptionSelected: (Int) -> Unit,
    accentColor: Color = Accent,
    highlightMaxValue: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedValue }?.second ?: options.firstOrNull()?.second ?: ""
    val maxValue = if (highlightMaxValue) options.maxOfOrNull { it.first } else null
    val selectedColor = if (highlightMaxValue && selectedValue == maxValue) Color(0xFFFF9500) else accentColor

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
                var isPressed by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.93f else 1f,
                    animationSpec = spring(stiffness = Spring.StiffnessHigh),
                    label = "dropdownScale",
                )
                Row(
                    modifier =
                        Modifier
                            .scale(scale)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF222232))
                            .border(1.dp, selectedColor.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                            .paneNavItem(
                                cornerRadius = 8.dp,
                                onActivate = { expanded = true },
                                onAdjust = { dir ->
                                    val curr = options.indexOfFirst { it.first == selectedValue }.coerceAtLeast(0)
                                    val next = (curr + dir).coerceIn(0, options.size - 1)
                                    if (next != curr) onOptionSelected(options[next].first)
                                },
                                highlightColor = NavHighlight,
                            )
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        isPressed = true
                                        tryAwaitRelease()
                                        isPressed = false
                                    },
                                    onTap = { expanded = true },
                                )
                            }.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = selectedLabel,
                        color = selectedColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = selectedColor,
                        modifier = Modifier.size(14.dp),
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    shape = RoundedCornerShape(8.dp),
                    containerColor = Color(0xFF24243B),
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.widthIn(max = 220.dp),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        options.forEach { (value, label) ->
                            val isSelected = value == selectedValue
                            val itemColor = if (highlightMaxValue && value == maxValue) Color(0xFFFF9500) else accentColor
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = label,
                                        color = if (isSelected) itemColor else TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        softWrap = true,
                                    )
                                },
                                onClick = {
                                    onOptionSelected(value)
                                    expanded = false
                                },
                                modifier =
                                    Modifier.background(
                                        if (isSelected) itemColor.copy(alpha = 0.08f) else Color.Transparent,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

// Folder path card
@Composable
private fun FolderPathCard(
    label: String,
    path: String,
    onBrowse: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
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
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = path.ifEmpty { stringResource(R.string.common_ui_not_configured) },
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            BrowseButton(onClick = onBrowse)
        }
    }
}

@Composable
private fun BrowseButton(onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "browseScale",
    )
    Box(
        modifier =
            Modifier
                .scale(scale)
                .clip(RoundedCornerShape(8.dp))
                .background(Accent.copy(alpha = 0.12f))
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = onClick,
                    highlightColor = NavHighlight,
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
                }.padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.common_ui_browse),
            color = Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
