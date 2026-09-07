package com.winlator.cmod.app.shell

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.layout.isPortraitLayout
import androidx.compose.runtime.CompositionLocalProvider
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.outlinedSwitchColors
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LaunchBlack = Color.Black
private val LaunchCard = Color(0xFF12121B)
private val LaunchAccent = Color(0xFF1A9FFF)
private val LaunchAccentGlow = Color(0xFF58A6FF)
private val LaunchTextPrimary = Color(0xFFF0F4FF)
private val LaunchTextSecondary = Color(0xFF93A6BC)
private val LaunchDanger = Color(0xFFFF6B6B)

@Composable
internal fun LibraryGameLaunchScreen(
    appName: String,
    subtitle: String,
    sourceLabel: String,
    heroImageUrl: Any?,
    customHeroImageCacheKey: String?,
    releaseDateEpochSeconds: Long,
    totalPlaytimeMillis: Long,
    playCount: Int,
    lastPlayedMillis: Long,
    installSizeText: String?,
    removalKeepsFiles: Boolean,
    isRetro: Boolean = false,
    showBootToDesktop: Boolean = !isRetro,
    showSaveTransfer: Boolean = false,
    hasPinnedShortcut: Boolean,
    steamMenuEnabled: Boolean = false,
    areSteamActionsEnabled: Boolean = true,
    showVerifyFiles: Boolean = true,
    showCheckForUpdate: Boolean = true,
    showWorkshop: Boolean = true,
    playEnabled: Boolean = true,
    playDisabledLabel: String? = null,
    /**
     * An alternative engine this particular game can be played with, offered
     * right above Play because it changes what Play does. Absent (and the row
     * not drawn at all) for every game that has no such choice, which is all
     * but a handful.
     */
    altEngineLabel: String? = null,
    altEngineEnabled: Boolean = false,
    onAltEngineChange: ((Boolean) -> Unit)? = null,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onSettings: () -> Unit,
    onBootToDesktop: () -> Unit,
    onAchievements: (() -> Unit)? = null,
    onCheats: (() -> Unit)? = null,
    cheatsEnabled: Boolean = true,
    onShortcut: () -> Unit,
    onCloudSaves: () -> Unit,
    onSaveTransfer: (() -> Unit)? = null,
    onUninstall: () -> Unit,
    onVerifyFiles: () -> Unit = {},
    onCheckForUpdate: () -> Unit = {},
    onWorkshop: () -> Unit = {},
    branches: List<StoreBranchOption> = emptyList(),
    selectedBranchId: String = "",
    isBranchSelectionEnabled: Boolean = true,
    onSelectBranch: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var uninstallMenuOpen by remember { mutableStateOf(false) }
    val saveTransferVisible = showSaveTransfer && onSaveTransfer != null
    val bootVisible = showBootToDesktop
    val actionIconCount =
        1 +
            (if (saveTransferVisible) 1 else 0) +
            1 +
            (if (bootVisible) 1 else 0) +
            1 +
            1

    LaunchScreenCutoutMode()

    Box(Modifier.fillMaxSize()) {
        val edgePadding = 22.dp
        val bottomPadding = 20.dp
        val actionIconSize = 46.dp
        val actionIconSpacing = 8.dp
        val actionWidth = actionIconSize * actionIconCount + actionIconSpacing * (actionIconCount - 1).coerceAtLeast(0)
        val playHeight = 56.dp
        val contentGap = 18.dp
        val horizontalNavInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)

        if (heroImageUrl != null) {
            val heroRequest =
                remember(heroImageUrl, customHeroImageCacheKey, context) {
                    ImageRequest
                        .Builder(context)
                        .data(heroImageUrl)
                        .apply {
                            if (customHeroImageCacheKey != null) {
                                memoryCacheKey(customHeroImageCacheKey)
                                diskCacheKey(customHeroImageCacheKey)
                            }
                        }.crossfade(150)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                }
            AsyncImage(
                model = heroRequest,
                contentDescription = "$appName artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(LaunchAccent.copy(alpha = 0.34f), LaunchCard, LaunchBlack),
                            radius = 980f,
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.SportsEsports,
                    contentDescription = null,
                    tint = LaunchTextPrimary.copy(alpha = 0.18f),
                    modifier = Modifier.size(132.dp),
                )
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops =
                            arrayOf(
                                0.0f to LaunchBlack.copy(alpha = 0.9f),
                                0.36f to LaunchBlack.copy(alpha = 0.58f),
                                0.72f to LaunchBlack.copy(alpha = 0.18f),
                                1.0f to LaunchBlack.copy(alpha = 0.62f),
                            ),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops =
                            arrayOf(
                                0.0f to LaunchBlack.copy(alpha = 0.54f),
                                0.36f to Color.Transparent,
                                0.72f to LaunchBlack.copy(alpha = 0.32f),
                                1.0f to LaunchBlack.copy(alpha = 0.94f),
                            ),
                    ),
                ),
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(horizontalNavInsets)
                    .padding(start = edgePadding, top = 12.dp, end = edgePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(LaunchBlack.copy(alpha = 0.5f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.common_ui_back),
                    tint = LaunchTextPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (branches.size > 1) {
                LaunchBranchTag(
                    branches = branches,
                    selectedBranchId = selectedBranchId,
                    enabled = isBranchSelectionEnabled,
                    onSelectBranch = onSelectBranch,
                )
                Spacer(Modifier.width(8.dp))
            }
            SourceTag(
                sourceLabel = sourceLabel,
                menuEnabled = steamMenuEnabled,
                showVerifyFiles = showVerifyFiles,
                showCheckForUpdate = showCheckForUpdate,
                showWorkshop = showWorkshop,
                showAchievements = onAchievements != null,
                showCheats = onCheats != null,
                cheatsEnabled = cheatsEnabled,
                areSteamActionsEnabled = areSteamActionsEnabled,
                onVerifyFiles = onVerifyFiles,
                onCheckForUpdate = onCheckForUpdate,
                onWorkshop = onWorkshop,
                onAchievements = { onAchievements?.invoke() },
                onCheats = { onCheats?.invoke() },
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = edgePadding, top = 68.dp, end = edgePadding, bottom = bottomPadding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    appName,
                    style = MaterialTheme.typography.headlineLarge,
                    color = LaunchTextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = LaunchTextPrimary.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (releaseDateEpochSeconds > 0L) {
                    val releaseDateText = remember(releaseDateEpochSeconds) { formatReleaseDate(releaseDateEpochSeconds) }
                    Text(
                        releaseDateText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LaunchTextPrimary.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val portraitHero = isPortraitLayout()
            val statChips: @Composable (Modifier) -> Unit = { statsModifier ->
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = statsModifier,
                ) {
                    if (totalPlaytimeMillis > 0L) {
                        val playtimeText = remember(totalPlaytimeMillis) { formatLibraryPlaytime(totalPlaytimeMillis) }
                        GameStatChip(
                            icon = Icons.Outlined.Schedule,
                            label = stringResource(R.string.library_games_playtime),
                            value = playtimeText,
                        )
                    }
                    if (playCount > 0) {
                        GameStatChip(
                            icon = Icons.Outlined.SportsEsports,
                            label = stringResource(R.string.library_games_plays),
                            value = playCount.toString(),
                        )
                    }
                    if (lastPlayedMillis > 0L) {
                        val lastPlayedText = remember(lastPlayedMillis) { formatLibraryLastPlayed(lastPlayedMillis) }
                        GameStatChip(
                            icon = Icons.Outlined.History,
                            label = stringResource(R.string.library_games_last_played),
                            value = lastPlayedText,
                        )
                    }
                    if (installSizeText != null) {
                        GameStatChip(
                            icon = Icons.Outlined.Storage,
                            label = stringResource(R.string.common_ui_size),
                            value = installSizeText,
                        )
                    }
                }
            }

            val actionBlock: @Composable (Modifier) -> Unit = { actionsModifier ->
                Column(
                    modifier = actionsModifier,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (altEngineLabel != null && onAltEngineChange != null) {
                        LaunchAltEngineToggle(
                            label = altEngineLabel,
                            checked = altEngineEnabled,
                            width = actionWidth,
                            onCheckedChange = onAltEngineChange,
                        )
                    }

                    LaunchPlayButton(
                        height = playHeight,
                        enabled = playEnabled,
                        disabledLabel = playDisabledLabel,
                        onClick = onPlay,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(actionIconSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LaunchIconActionButton(
                            icon = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.common_ui_settings),
                            size = actionIconSize,
                            onClick = onSettings,
                        )
                        if (saveTransferVisible) {
                            LaunchIconActionButton(
                                icon = Icons.Outlined.SaveAlt,
                                contentDescription = stringResource(R.string.retro_save_transfer_title),
                                size = actionIconSize,
                                onClick = { onSaveTransfer?.invoke() },
                            )
                        }
                        LaunchIconActionButton(
                            icon = Icons.Outlined.CloudSync,
                            contentDescription = stringResource(R.string.cloud_saves_title),
                            size = actionIconSize,
                            onClick = onCloudSaves,
                        )
                        if (bootVisible) {
                            LaunchIconActionButton(
                                icon = Icons.Outlined.DesktopWindows,
                                contentDescription = stringResource(R.string.hero_boot_to_desktop_title),
                                size = actionIconSize,
                                onClick = onBootToDesktop,
                            )
                        }
                        LaunchIconActionButton(
                            icon = Icons.Outlined.Home,
                            contentDescription =
                                stringResource(
                                    if (hasPinnedShortcut) R.string.common_ui_remove else R.string.common_ui_shortcut,
                                ),
                            size = actionIconSize,
                            onClick = onShortcut,
                        )
                        Box {
                            LaunchIconActionButton(
                                icon = Icons.Outlined.Delete,
                                contentDescription =
                                    stringResource(
                                        if (removalKeepsFiles) R.string.common_ui_remove else R.string.common_ui_uninstall,
                                    ),
                                size = actionIconSize,
                                onClick = { uninstallMenuOpen = true },
                                tint = LaunchDanger,
                            )
                            LaunchUninstallMenu(
                                expanded = uninstallMenuOpen,
                                appName = appName,
                                removalKeepsFiles = removalKeepsFiles,
                                onDismissRequest = { uninstallMenuOpen = false },
                                onConfirm = {
                                    uninstallMenuOpen = false
                                    onUninstall()
                                },
                            )
                        }
                    }
                }
            }

            if (portraitHero) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(contentGap),
                ) {
                    statChips(Modifier.fillMaxWidth())
                    actionBlock(Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(contentGap),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    statChips(Modifier.weight(1f))
                    actionBlock(Modifier.width(actionWidth))
                }
            }
        }

        if (uninstallMenuOpen) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(LaunchBlack.copy(alpha = 0.46f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { uninstallMenuOpen = false },
                        ),
            )
        }
    }
}

@Composable
private fun LaunchScreenCutoutMode() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
            ?: return@DisposableEffect onDispose { }

        val originalCutoutMode = window.attributes.layoutInDisplayCutoutMode
        val originalWidth = window.attributes.width
        val originalHeight = window.attributes.height
        val originalNavigationBarColor = window.navigationBarColor
        val originalNavBarContrastEnforced =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced
            } else {
                false
            }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
        )
        // FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS is required for navigationBarColor; Dialog windows don't set it by default.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
        )
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = launchScreenCutoutMode()
        }
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        onDispose {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = originalCutoutMode
            }
            window.navigationBarColor = originalNavigationBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = originalNavBarContrastEnforced
            }
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            )
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.setLayout(originalWidth, originalHeight)
        }
    }
}

private fun launchScreenCutoutMode(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

@Composable
private fun LaunchUninstallMenu(
    expanded: Boolean,
    appName: String,
    removalKeepsFiles: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title =
        stringResource(
            if (removalKeepsFiles) R.string.library_games_remove_game else R.string.library_games_uninstall_game,
        )
    val confirmLabel =
        stringResource(if (removalKeepsFiles) R.string.common_ui_remove else R.string.common_ui_uninstall)
    val message =
        stringResource(
            if (removalKeepsFiles) R.string.library_games_remove_confirm else R.string.library_games_uninstall_confirm,
            appName,
        )

    LaunchDangerConfirmDialog(
        visible = expanded,
        title = title,
        message = message,
        confirmLabel = confirmLabel,
        onDismissRequest = onDismissRequest,
        onConfirm = onConfirm,
        icon = Icons.Outlined.Delete,
        cancelColor = LaunchAccent,
    )
}

@Composable
internal fun LaunchDangerConfirmMenu(
    expanded: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    icon: ImageVector = Icons.Outlined.Delete,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = DpOffset(x = 0.dp, y = (-56).dp),
        modifier = Modifier.width(286.dp),
        shape = RoundedCornerShape(12.dp),
        containerColor = LaunchCard,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        tonalElevation = 0.dp,
        shadowElevation = 14.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = LaunchDanger,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    title,
                    color = LaunchTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                message,
                color = LaunchTextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LaunchMenuTextAction(
                    label = stringResource(R.string.common_ui_cancel),
                    textColor = LaunchTextSecondary,
                    onClick = onDismissRequest,
                )
                LaunchMenuTextAction(
                    label = confirmLabel,
                    textColor = LaunchDanger,
                    onClick = onConfirm,
                )
            }
        }
    }
}

@Composable
internal fun LaunchDangerConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    icon: ImageVector = Icons.Outlined.Warning,
    titleTextAlign: TextAlign = TextAlign.Start,
    messageTextAlign: TextAlign = TextAlign.Start,
    accentColor: Color = LaunchDanger,
    cancelColor: Color = LaunchTextSecondary,
) {
    if (!visible) return

    val registry = remember { PaneNavRegistry() }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(LocalPaneNav provides registry) {
            DialogPaneNav(registry, onDismiss = onDismissRequest)
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(LaunchBlack.copy(alpha = 0.46f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismissRequest,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier =
                        Modifier
                            .width(286.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { },
                            ),
                    shape = RoundedCornerShape(12.dp),
                    color = LaunchCard,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                    shadowElevation = 14.dp,
                    tonalElevation = 0.dp,
                ) {
                    LaunchDangerConfirmContent(
                        title = title,
                        message = message,
                        confirmLabel = confirmLabel,
                        onDismissRequest = onDismissRequest,
                        onConfirm = onConfirm,
                        icon = icon,
                        titleTextAlign = titleTextAlign,
                        messageTextAlign = messageTextAlign,
                        accentColor = accentColor,
                        cancelColor = cancelColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun LaunchDangerConfirmContent(
    title: String,
    message: String,
    confirmLabel: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    icon: ImageVector,
    titleTextAlign: TextAlign,
    messageTextAlign: TextAlign,
    accentColor: Color,
    cancelColor: Color,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (titleTextAlign == TextAlign.Center) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .size(18.dp),
                )
                Text(
                    title,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 28.dp),
                    color = LaunchTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    title,
                    color = LaunchTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            message,
            modifier = Modifier.fillMaxWidth(),
            color = LaunchTextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            textAlign = messageTextAlign,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LaunchMenuTextAction(
                label = stringResource(R.string.common_ui_cancel),
                textColor = cancelColor,
                onClick = onDismissRequest,
                modifier = Modifier.paneNavItem(onActivate = onDismissRequest),
            )
            LaunchMenuTextAction(
                label = confirmLabel,
                textColor = accentColor,
                onClick = onConfirm,
                modifier = Modifier.paneNavItem(onActivate = onConfirm, isEntry = true),
            )
        }
    }
}

@Composable
private fun LaunchMenuTextAction(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .controllerFocusGlow(cornerRadius = 8.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun SourceTag(
    sourceLabel: String,
    menuEnabled: Boolean = false,
    showVerifyFiles: Boolean = true,
    showCheckForUpdate: Boolean = true,
    showWorkshop: Boolean = true,
    showAchievements: Boolean = false,
    showCheats: Boolean = false,
    cheatsEnabled: Boolean = true,
    areSteamActionsEnabled: Boolean = true,
    onVerifyFiles: () -> Unit = {},
    onCheckForUpdate: () -> Unit = {},
    onWorkshop: () -> Unit = {},
    onAchievements: () -> Unit = {},
    onCheats: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var anchorHeightPx by remember { mutableStateOf(0) }
    val menuInteractive = menuEnabled || showAchievements || showCheats
    Box {
        Surface(
            color = Color.White.copy(alpha = 0.1f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            modifier =
                Modifier
                    .onSizeChanged { anchorHeightPx = it.height }
                    .then(if (menuInteractive) Modifier.clickable { menuOpen = true } else Modifier),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(LaunchAccent),
                )
                Text(
                    sourceLabel.uppercase(),
                    color = LaunchTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (menuInteractive) {
                    Icon(
                        Icons.Outlined.ArrowDropDown,
                        contentDescription = stringResource(R.string.store_game_steam_options),
                        tint = LaunchTextPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (menuInteractive) {
            val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
            LaunchSourceActionPopup(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                offset = IntOffset(0, anchorHeightPx + gapPx),
            ) {
                if (menuEnabled && showVerifyFiles) {
                    LaunchSourceMenuItem(
                        icon = Icons.AutoMirrored.Outlined.FactCheck,
                        label = stringResource(R.string.store_game_verify_files),
                        enabled = areSteamActionsEnabled,
                    ) { menuOpen = false; onVerifyFiles() }
                }
                if (menuEnabled && showCheckForUpdate) {
                    LaunchSourceMenuItem(
                        icon = Icons.Outlined.Refresh,
                        label = stringResource(R.string.store_game_check_for_update),
                        enabled = areSteamActionsEnabled,
                    ) { menuOpen = false; onCheckForUpdate() }
                }
                if (menuEnabled && showWorkshop) {
                    LaunchSourceMenuItem(
                        icon = Icons.Outlined.Construction,
                        label = stringResource(R.string.store_game_workshop),
                        enabled = areSteamActionsEnabled,
                    ) { menuOpen = false; onWorkshop() }
                }
                if (showAchievements) {
                    LaunchSourceMenuItem(
                        icon = Icons.Outlined.EmojiEvents,
                        label = stringResource(R.string.steam_achievements_title),
                    ) { menuOpen = false; onAchievements() }
                }
                if (showCheats) {
                    LaunchSourceMenuItem(
                        icon = Icons.Outlined.Bolt,
                        label = stringResource(R.string.retro_cheats_title),
                        enabled = cheatsEnabled,
                    ) { menuOpen = false; onCheats() }
                }
            }
        }
    }
}

@Composable
private fun LaunchBranchTag(
    branches: List<StoreBranchOption>,
    selectedBranchId: String,
    enabled: Boolean,
    onSelectBranch: (String) -> Unit,
) {
    val selected =
        branches.firstOrNull { it.id.equals(selectedBranchId, ignoreCase = true) }
            ?: branches.firstOrNull()
            ?: return
    var menuOpen by remember { mutableStateOf(false) }
    var anchorHeightPx by remember { mutableStateOf(0) }
    val contentColor = if (enabled) LaunchTextPrimary else LaunchTextPrimary.copy(alpha = 0.45f)

    Box {
        Surface(
            color = Color.White.copy(alpha = 0.1f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            modifier =
                Modifier
                    .onSizeChanged { anchorHeightPx = it.height }
                    .then(if (enabled) Modifier.clickable { menuOpen = true } else Modifier),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Layers,
                    contentDescription = null,
                    tint = if (enabled) LaunchAccentGlow else LaunchTextSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    selected.id.uppercase(),
                    color = contentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 132.dp),
                )
                if (enabled) {
                    Icon(
                        Icons.Outlined.ArrowDropDown,
                        contentDescription = stringResource(R.string.store_game_branch_label),
                        tint = contentColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (enabled) {
            val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
            LaunchSourceActionPopup(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                offset = IntOffset(0, anchorHeightPx + gapPx),
            ) {
                Column(
                    modifier =
                        Modifier
                            .widthIn(min = 172.dp, max = 240.dp)
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                ) {
                    branches.forEach { branch ->
                        val isSelected = branch.id.equals(selected.id, ignoreCase = true)
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        menuOpen = false
                                        if (!isSelected) onSelectBranch(branch.id)
                                    }.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.Layers,
                                contentDescription = null,
                                tint = if (isSelected) LaunchAccentGlow else Color.White.copy(alpha = 0.55f),
                                modifier = Modifier.size(16.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                Text(
                                    branch.label,
                                    color = if (isSelected) LaunchTextPrimary else Color.White.copy(alpha = 0.86f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (branch.buildId > 0L) {
                                    Text(
                                        stringResource(R.string.store_game_branch_build, branch.buildId.toString()),
                                        color = LaunchTextSecondary,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (branch.isInstalled) {
                                Icon(
                                    Icons.Outlined.Storage,
                                    contentDescription = null,
                                    tint = LaunchTextSecondary,
                                    modifier = Modifier.size(14.dp),
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
private fun LaunchSourceActionPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: IntOffset,
    content: @Composable () -> Unit,
) {
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = expanded
    if (!transitionState.currentState && !transitionState.targetState) return

    Popup(
        alignment = Alignment.TopEnd,
        offset = offset,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = transitionState,
            enter =
                fadeIn(animationSpec = tween(durationMillis = 90)) +
                    scaleIn(
                        animationSpec =
                            spring(
                                dampingRatio = 0.78f,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        initialScale = 0.88f,
                        transformOrigin = TransformOrigin(1f, 0f),
                    ),
            exit =
                fadeOut(animationSpec = tween(durationMillis = 80)) +
                    scaleOut(
                        animationSpec = tween(durationMillis = 110),
                        targetScale = 0.92f,
                        transformOrigin = TransformOrigin(1f, 0f),
                    ),
        ) {
            Surface(
                color = LaunchBlack.copy(alpha = 0.78f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                tonalElevation = 0.dp,
                shadowElevation = 16.dp,
            ) {
                Column { content() }
            }
        }
    }
}

@Composable
private fun LaunchSourceMenuItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.45f)
    Row(
        modifier =
            Modifier
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
        Text(
            label,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GameStatChip(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = LaunchBlack.copy(alpha = 0.44f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.11f)),
    ) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal = 10.dp,
                    vertical = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = LaunchAccentGlow)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    label.uppercase(),
                    color = LaunchTextSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    value,
                    color = LaunchTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The alternative-engine switch above Play.
 *
 * Deliberately the same width as the Play button and immediately above it: it
 * decides which engine Play will start, so it belongs in the reading path to
 * that button rather than buried in a settings pane. The state is the game's
 * own saved setting, so what it shows survives leaving the screen.
 */
@Composable
private fun LaunchAltEngineToggle(
    label: String,
    checked: Boolean,
    width: Dp,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .width(width)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = outlinedSwitchColors(
                accentColor = LaunchAccent,
                textSecondaryColor = Color.White.copy(alpha = 0.55f),
            ),
        )
    }
}

@Composable
private fun LaunchPlayButton(
    height: Dp,
    enabled: Boolean = true,
    disabledLabel: String? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "launchPlayScale",
    )
    val flare by animateFloatAsState(
        targetValue = if (enabled && isPressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "launchPlayFlare",
    )

    val playShape = remember { RoundedCornerShape(14.dp) }
    // Disabled: drop the clickable entirely so focus skips it and a stray controller A-press can't fire onClick.
    val backgroundBrush =
        if (enabled) {
            Brush.horizontalGradient(
                colors =
                    listOf(
                        Color(0xFF00B4D8).copy(alpha = 0.38f),
                        LaunchAccent.copy(alpha = 0.38f),
                        Color(0xFF7B2FF7).copy(alpha = 0.38f),
                    ),
            )
        } else {
            Brush.horizontalGradient(
                colors =
                    listOf(
                        Color(0xFF3A3F4A).copy(alpha = 0.35f),
                        Color(0xFF2D313A).copy(alpha = 0.35f),
                        Color(0xFF3A3F4A).copy(alpha = 0.35f),
                    ),
            )
        }
    val glassSheenBrush =
        if (enabled) {
            Brush.verticalGradient(
                0.00f to Color.White.copy(alpha = 0.28f),
                0.35f to Color.White.copy(alpha = 0.06f),
                0.55f to Color.Transparent,
                1.00f to Color.Black.copy(alpha = 0.12f),
            )
        } else {
            Brush.verticalGradient(
                0.0f to Color.White.copy(alpha = 0.10f),
                0.6f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = 0.08f),
            )
        }
    val glassRimBrush =
        if (enabled) {
            Brush.verticalGradient(
                0.0f to Color.White.copy(alpha = 0.55f + 0.35f * flare),
                0.5f to Color.White.copy(alpha = 0.08f + 0.18f * flare),
                1.0f to Color.White.copy(alpha = 0.22f + 0.22f * flare),
            )
        } else {
            Brush.verticalGradient(
                0.0f to Color.White.copy(alpha = 0.16f),
                1.0f to Color.White.copy(alpha = 0.04f),
            )
        }
    val foregroundAlpha = if (enabled) 1f else 0.75f

    val baseModifier =
        Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.clip(playShape)
            .background(backgroundBrush)
            .background(glassSheenBrush)
            .border(1.dp, glassRimBrush, playShape)
    val finalModifier =
        if (enabled) {
            baseModifier
                .controllerFocusGlow(cornerRadius = 14.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
        } else {
            baseModifier
        }

    val showStatus = !enabled && !disabledLabel.isNullOrBlank()
    val icon = if (showStatus) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow
    val label = if (showStatus) disabledLabel!! else stringResource(R.string.library_games_play)

    Box(
        modifier = finalModifier,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = Color.White.copy(alpha = foregroundAlpha),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = Color.White.copy(alpha = foregroundAlpha),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LaunchIconActionButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    Surface(
        modifier =
            Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        color = LaunchBlack.copy(alpha = 0.46f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(28.dp),
                tint = tint,
            )
        }
    }
}

private fun formatReleaseDate(releaseDateEpochSeconds: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        .format(Date(releaseDateEpochSeconds * 1000L))

private fun formatLibraryPlaytime(playtimeMillis: Long): String {
    val totalMinutes = (playtimeMillis / 60000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}

private fun formatLibraryLastPlayed(lastPlayedMillis: Long): String =
    SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(lastPlayedMillis))
