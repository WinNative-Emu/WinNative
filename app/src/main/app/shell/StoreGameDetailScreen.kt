package com.winlator.cmod.app.shell

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.winlator.cmod.R
import com.winlator.cmod.shared.io.StorageUtils
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavHandlers
import com.winlator.cmod.shared.ui.nav.paneNavItem

internal data class StoreDlcItem(
    val id: Int,
    val name: String,
    val downloadSize: Long,
    val isInstalled: Boolean = false,
)

internal data class StoreBranchOption(
    val id: String,
    val label: String,
    val buildId: Long = 0L,
    val isInstalled: Boolean = false,
)

internal data class StoreDetailChip(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val highlight: Boolean = false,
)

private val CompactDetailWidth = 560.dp
private val StoreBlack = Color.Black
private val StoreCard = Color(0xFF12121B)
private val StoreAccent = Color(0xFF1A9FFF)
private val StoreAccentGlow = Color(0xFF58A6FF)
private val StoreTextPrimary = Color(0xFFF0F4FF)
private val StoreTextSecondary = Color(0xFF93A6BC)
private val StoreDanger = Color(0xFFFF6B6B)

@Composable
internal fun StoreGameDetailScreen(
    title: String,
    subtitle: String,
    sourceLabel: String,
    heroImageUrl: Any?,
    isLoading: Boolean,
    isInstalled: Boolean,
    installPathDisplay: String,
    downloadSize: Long,
    installSize: Long,
    availableBytes: Long,
    isInstallEnabled: Boolean,
    isDownloadActionEnabled: Boolean = isInstallEnabled,
    customPathLabel: String,
    showCustomPath: Boolean = true,
    showCloudSync: Boolean = false,
    showUninstall: Boolean = true,
    uninstallAsPrimaryAction: Boolean = false,
    showUpdateCheck: Boolean = false,
    isCheckingForUpdate: Boolean = false,
    isUpdateAvailable: Boolean = false,
    updateDownloadSize: Long = 0L,
    updateStatusText: String? = null,
    isUpdateActionEnabled: Boolean = true,
    isUpdateCheckCoolingDown: Boolean = false,
    showWorkshop: Boolean = false,
    showVerifyFiles: Boolean = false,
    areSteamActionsEnabled: Boolean = true,
    detailChips: List<StoreDetailChip> = emptyList(),
    dlcs: List<StoreDlcItem> = emptyList(),
    selectedDlcIds: Set<Int> = emptySet(),
    isDlcSelectionEnabled: Boolean = true,
    branches: List<StoreBranchOption> = emptyList(),
    selectedBranchId: String = "",
    isBranchSelectionEnabled: Boolean = true,
    onSelectBranch: (String) -> Unit = {},
    onBack: () -> Unit,
    onInstall: () -> Unit = {},
    onCheckForUpdate: () -> Unit = {},
    onWorkshop: () -> Unit = {},
    onVerifyFiles: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onUninstall: () -> Unit = {},
    onCloudSync: () -> Unit = {},
    onCustomPath: () -> Unit = {},
    onToggleDlc: (Int) -> Unit = {},
    onToggleSelectAllDlcs: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var dlcExpanded by remember { mutableStateOf(false) }
    var dlcHeaderHeightPx by remember { mutableIntStateOf(0) }

    val mainRegistry = remember { PaneNavRegistry() }
    val menuRegistry = remember { PaneNavRegistry() }
    val branchRegistry = remember { PaneNavRegistry() }
    val topBranchRegistry = remember { PaneNavRegistry() }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var branchMenuOpen by remember { mutableStateOf(false) }
    var topBranchMenuOpen by remember { mutableStateOf(false) }

    StoreScreenCutoutMode()

    CompositionLocalProvider(LocalPaneNav provides mainRegistry) {
        DialogPaneNav(
            paneNavHandlers(
                onDismiss = {
                    when {
                        sourceMenuOpen -> sourceMenuOpen = false
                        topBranchMenuOpen -> topBranchMenuOpen = false
                        branchMenuOpen -> branchMenuOpen = false
                        else -> onBack()
                    }
                },
            ) {
                when {
                    sourceMenuOpen -> menuRegistry
                    topBranchMenuOpen -> topBranchRegistry
                    branchMenuOpen -> branchRegistry
                    else -> mainRegistry
                }
            },
        )
        Box(Modifier.fillMaxSize()) {
        val edgePadding = 22.dp
        val bottomPadding = 8.dp
        val actionIconSize = 48.dp
        val actionIconSpacing = 8.dp
        val actionWidth = actionIconSize * 5 + actionIconSpacing * 4
        val ctaHeight = 56.dp
        val contentGap = 18.dp
        val horizontalNavInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
        val hasSelectedInstallableDlc = dlcs.any { !it.isInstalled && it.id in selectedDlcIds }
        val showDownloadCta = !isInstalled || hasSelectedInstallableDlc
        val showUninstallCta = uninstallAsPrimaryAction && showUninstall && isInstalled
        val updateCheckAvailable = showUpdateCheck && isInstalled
        val showUpdateCta = updateCheckAvailable && isUpdateAvailable
        val verifyFilesAvailable = showVerifyFiles && isInstalled
        val workshopAvailable = showWorkshop && isInstalled
        val sourceMenuEnabled = updateCheckAvailable || verifyFilesAvailable || workshopAvailable
        val showDlcCard = dlcs.isNotEmpty()
        val showBranchPicker = branches.size > 1
        val showActionColumn =
            showDownloadCta || showUpdateCta ||
                (showCloudSync || showUninstall)

        if (heroImageUrl != null) {
            val heroRequest =
                remember(heroImageUrl, context) {
                    ImageRequest
                        .Builder(context)
                        .data(heroImageUrl)
                        .crossfade(150)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                }
            AsyncImage(
                model = heroRequest,
                contentDescription = "$title artwork",
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
                            colors = listOf(StoreAccent.copy(alpha = 0.34f), StoreCard, StoreBlack),
                            radius = 980f,
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.SportsEsports,
                    contentDescription = null,
                    tint = StoreTextPrimary.copy(alpha = 0.18f),
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
                                0.0f to StoreBlack.copy(alpha = 0.9f),
                                0.36f to StoreBlack.copy(alpha = 0.58f),
                                0.72f to StoreBlack.copy(alpha = 0.18f),
                                1.0f to StoreBlack.copy(alpha = 0.62f),
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
                                0.0f to StoreBlack.copy(alpha = 0.54f),
                                0.36f to Color.Transparent,
                                0.72f to StoreBlack.copy(alpha = 0.32f),
                                1.0f to StoreBlack.copy(alpha = 0.94f),
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
                        .paneNavItem(cornerRadius = 22.dp, onActivate = onBack, navRow = 0, navCol = 0)
                        .background(StoreBlack.copy(alpha = 0.5f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.common_ui_back),
                    tint = StoreTextPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (showBranchPicker) {
                StoreBranchTag(
                    branches = branches,
                    selectedBranchId = selectedBranchId,
                    enabled = !isLoading && isBranchSelectionEnabled,
                    expanded = topBranchMenuOpen,
                    onExpandedChange = { open ->
                        if (open) sourceMenuOpen = false
                        topBranchMenuOpen = open
                    },
                    menuRegistry = topBranchRegistry,
                    onSelectBranch = onSelectBranch,
                )
                Spacer(Modifier.width(8.dp))
            }
            StoreSourceTag(
                sourceLabel = sourceLabel,
                menuEnabled = sourceMenuEnabled,
                menuOpen = sourceMenuOpen,
                onMenuOpenChange = { sourceMenuOpen = it },
                menuRegistry = menuRegistry,
                showCheckForUpdate = updateCheckAvailable,
                showVerifyFiles = verifyFilesAvailable,
                showWorkshop = workshopAvailable,
                isCheckingForUpdate = isCheckingForUpdate,
                areSteamActionsEnabled = areSteamActionsEnabled,
                isUpdateCheckEnabled =
                    !isLoading &&
                        !isCheckingForUpdate &&
                        !isUpdateCheckCoolingDown &&
                        isUpdateActionEnabled,
                onVerifyFiles = onVerifyFiles,
                onCheckForUpdate = onCheckForUpdate,
                onWorkshop = onWorkshop,
            )
        }

        val dlcHeaderReserveHeight =
            if (showDlcCard && dlcHeaderHeightPx > 0) {
                with(density) { dlcHeaderHeightPx.toDp() } + 12.dp
            } else {
                0.dp
            }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(
                        start = edgePadding,
                        top = 68.dp,
                        end = edgePadding,
                        bottom = bottomPadding + dlcHeaderReserveHeight,
                    ),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = StoreTextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = StoreTextPrimary.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column {
                val statChips: @Composable (Modifier) -> Unit = { chipModifier ->
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = chipModifier,
                        ) {
                            if (isLoading) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = StoreAccent,
                                        strokeWidth = 2.dp,
                                    )
                                    Text(
                                        stringResource(R.string.common_ui_loading),
                                        color = StoreTextSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                            } else if (isInstalled) {
                                detailChips.forEach { chip ->
                                    StoreStatChip(
                                        icon = chip.icon,
                                        label = chip.label,
                                        value = chip.value,
                                        valueColor = if (chip.highlight) StoreAccentGlow else null,
                                    )
                                }
                                StoreStatChip(
                                    icon = Icons.Outlined.Storage,
                                    label = stringResource(R.string.library_games_install_path),
                                    value = installPathDisplay,
                                )
                                if (isUpdateAvailable && updateDownloadSize > 0L) {
                                    StoreStatChip(
                                        icon = Icons.Outlined.SystemUpdate,
                                        label = stringResource(R.string.store_game_update),
                                        value = StorageUtils.formatBinarySize(updateDownloadSize),
                                    )
                                }
                            } else {
                                detailChips.forEach { chip ->
                                    StoreStatChip(
                                        icon = chip.icon,
                                        label = chip.label,
                                        value = chip.value,
                                        valueColor = if (chip.highlight) StoreAccentGlow else null,
                                    )
                                }
                                if (downloadSize > 0L) {
                                    StoreStatChip(
                                        icon = Icons.Outlined.Download,
                                        label = stringResource(R.string.common_ui_download),
                                        value = StorageUtils.formatBinarySize(downloadSize),
                                    )
                                }
                                if (installSize > 0L) {
                                    StoreStatChip(
                                        icon = Icons.Outlined.Storage,
                                        label = stringResource(R.string.common_ui_size),
                                        value = StorageUtils.formatBinarySize(installSize),
                                        valueColor = if (!isInstallEnabled) StoreDanger else null,
                                    )
                                }
                                if (availableBytes > 0L) {
                                    StoreStatChip(
                                        icon = Icons.Outlined.Folder,
                                        label = stringResource(R.string.common_ui_available),
                                        value = StorageUtils.formatBinarySize(availableBytes),
                                        valueColor = if (!isInstallEnabled) StoreDanger else null,
                                    )
                                }
                                if (showCustomPath) {
                                    StoreActionChip(
                                        icon = Icons.Outlined.Folder,
                                        label = customPathLabel,
                                        onClick = onCustomPath,
                                    )
                                }
                            }
                        }
                }

                val actionColumn: @Composable (Modifier) -> Unit = { actionModifier ->
                        if (showActionColumn) {
                            Column(
                                modifier = actionModifier,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (showUpdateCta) {
                                    StoreCtaButton(
                                        height = ctaHeight,
                                        icon = Icons.Outlined.SystemUpdate,
                                        label = stringResource(R.string.store_game_download_update),
                                        enabled =
                                            !isLoading &&
                                                isUpdateActionEnabled &&
                                                !isCheckingForUpdate,
                                        loading = false,
                                        onClick = onDownloadUpdate,
                                        isEntry = true,
                                        navRow = 1,
                                        navCol = 3,
                                    )
                                }

                                if (updateCheckAvailable && !updateStatusText.isNullOrBlank()) {
                                    Text(
                                        updateStatusText,
                                        color =
                                            if (updateStatusText == stringResource(R.string.store_game_update_check_failed)) {
                                                StoreDanger
                                            } else {
                                                StoreTextSecondary
                                            },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                if (showDownloadCta && !isLoading && !isInstallEnabled && installSize > 0L) {
                                    val deficit = (installSize - availableBytes).coerceAtLeast(0L)
                                    if (deficit > 0L) {
                                        Text(
                                            stringResource(
                                                R.string.library_games_not_enough_space,
                                                StorageUtils.formatBinarySize(deficit),
                                            ),
                                            color = StoreDanger,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(actionIconSpacing),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    if (showCloudSync && isInstalled) {
                                        StoreIconActionButton(
                                            icon = Icons.Outlined.CloudSync,
                                            contentDescription = stringResource(R.string.cloud_saves_title),
                                            size = actionIconSize,
                                            onClick = onCloudSync,
                                            navRow = 1,
                                            navCol = 1,
                                        )
                                    }
                                    if (showUninstall && isInstalled && !showUninstallCta) {
                                        StoreIconActionButton(
                                            icon = Icons.Outlined.Delete,
                                            contentDescription = stringResource(R.string.common_ui_uninstall),
                                            size = actionIconSize,
                                            onClick = onUninstall,
                                            tint = StoreDanger,
                                            navRow = 1,
                                            navCol = 2,
                                        )
                                    }
                                }

                                if (showBranchPicker && showDownloadCta) {
                                    StoreBranchPicker(
                                        branches = branches,
                                        selectedBranchId = selectedBranchId,
                                        enabled = !isLoading && isBranchSelectionEnabled,
                                        expanded = branchMenuOpen,
                                        onExpandedChange = { branchMenuOpen = it },
                                        menuRegistry = branchRegistry,
                                        onSelectBranch = onSelectBranch,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                if (showDownloadCta) {
                                    StoreCtaButton(
                                        height = ctaHeight,
                                        icon = Icons.Outlined.Download,
                                        label = stringResource(R.string.common_ui_download),
                                        enabled = !isLoading && isDownloadActionEnabled,
                                        loading = isLoading,
                                        onClick = onInstall,
                                        isEntry = true,
                                        navRow = 1,
                                        navCol = 4,
                                    )
                                }

                                if (showUninstallCta) {
                                    StoreCtaButton(
                                        height = ctaHeight,
                                        icon = Icons.Outlined.Delete,
                                        label = stringResource(R.string.common_ui_uninstall),
                                        enabled = !isLoading,
                                        loading = false,
                                        onClick = onUninstall,
                                        danger = true,
                                        isEntry = !showDownloadCta,
                                        navRow = 1,
                                        navCol = 5,
                                    )
                                }
                            }
                        }
                }

                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < CompactDetailWidth) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            statChips(Modifier.fillMaxWidth())
                            actionColumn(Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(contentGap),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            statChips(Modifier.weight(1f))
                            actionColumn(Modifier.width(actionWidth))
                        }
                    }
                }
            }
        }

        if (showDlcCard) {
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(
                            start = edgePadding,
                            top = 68.dp,
                            end = edgePadding,
                            bottom = bottomPadding,
                        ),
                contentAlignment = Alignment.BottomStart,
            ) {
                val maxListHeight = (maxHeight - 96.dp).coerceAtLeast(120.dp)
                StoreDlcCard(
                    dlcs = dlcs,
                    selectedDlcIds = selectedDlcIds,
                    selectionEnabled = isDlcSelectionEnabled,
                    expanded = dlcExpanded,
                    onToggleExpanded = { dlcExpanded = !dlcExpanded },
                    onToggleDlc = onToggleDlc,
                    onToggleSelectAll = onToggleSelectAllDlcs,
                    maxListHeight = maxListHeight,
                    onHeaderMeasured = { dlcHeaderHeightPx = it },
                )
            }
        }
    }
    }
}

@Composable
private fun StoreDlcCard(
    dlcs: List<StoreDlcItem>,
    selectedDlcIds: Set<Int>,
    selectionEnabled: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleDlc: (Int) -> Unit,
    onToggleSelectAll: () -> Unit,
    maxListHeight: Dp = 280.dp,
    onHeaderMeasured: (Int) -> Unit = {},
) {
    val selectableDlcs = remember(dlcs) { dlcs.filterNot { it.isInstalled } }
    val totalSize = remember(selectableDlcs) { selectableDlcs.sumOf { it.downloadSize.coerceAtLeast(0L) } }
    val selectedCount = selectableDlcs.count { it.id in selectedDlcIds }
    val installedCount = dlcs.count { it.isInstalled }
    val selectedSize = remember(dlcs, selectedDlcIds) {
        dlcs.filter { !it.isInstalled && it.id in selectedDlcIds }.sumOf { it.downloadSize.coerceAtLeast(0L) }
    }
    val allSelected = selectableDlcs.isNotEmpty() && selectedCount == selectableDlcs.size

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = StoreBlack,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { onHeaderMeasured(it.height) }
                        .paneNavItem(cornerRadius = 12.dp, onActivate = onToggleExpanded, navRow = 2, navCol = 0)
                        .clickable(onClick = onToggleExpanded)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Outlined.Extension,
                    contentDescription = null,
                    tint = StoreAccentGlow,
                    modifier = Modifier.size(16.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        stringResource(R.string.library_games_dlcs).uppercase(),
                        color = StoreTextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                    )
                    Text(
                        buildDlcSummary(
                            selectedCount = selectedCount,
                            totalCount = selectableDlcs.size,
                            installedCount = installedCount,
                            selectedSize = selectedSize,
                            totalSize = totalSize,
                        ),
                        color = StoreTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = StoreTextPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    StoreDlcDivider()
                    if (selectableDlcs.isNotEmpty()) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (selectionEnabled) {
                                            Modifier.paneNavItem(cornerRadius = 8.dp, onActivate = onToggleSelectAll, navRow = 3, navCol = 0)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .clickable(enabled = selectionEnabled, onClick = onToggleSelectAll)
                                    .padding(horizontal = 6.dp, vertical = 0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = allSelected,
                                onCheckedChange = { onToggleSelectAll() },
                                enabled = selectionEnabled,
                                colors =
                                    CheckboxDefaults.colors(
                                        checkedColor = StoreAccent,
                                        uncheckedColor = StoreTextSecondary,
                                        checkmarkColor = Color.White,
                                    ),
                            )
                            Text(
                                stringResource(
                                    if (allSelected) R.string.common_ui_deselect_all else R.string.common_ui_select_all,
                                ),
                                color = StoreTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        StoreDlcDivider()
                    }
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxListHeight)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        dlcs.forEachIndexed { index, dlc ->
                            if (index > 0) {
                                StoreDlcDivider()
                            }
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (dlc.isInstalled || !selectionEnabled) {
                                                Modifier
                                            } else {
                                                Modifier
                                                    .paneNavItem(cornerRadius = 8.dp, onActivate = { onToggleDlc(dlc.id) }, navRow = 4 + index, navCol = 0)
                                                    .clickable { onToggleDlc(dlc.id) }
                                            },
                                        )
                                        .padding(horizontal = 6.dp, vertical = 0.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (dlc.isInstalled) {
                                    Checkbox(
                                        checked = true,
                                        onCheckedChange = {},
                                        enabled = false,
                                        colors =
                                            CheckboxDefaults.colors(
                                                checkedColor = Color(0xFF38D77A),
                                                disabledCheckedColor = Color(0xFF38D77A),
                                                checkmarkColor = Color.White,
                                            ),
                                    )
                                } else {
                                    Checkbox(
                                        checked = dlc.id in selectedDlcIds,
                                        onCheckedChange = { onToggleDlc(dlc.id) },
                                        enabled = selectionEnabled,
                                        colors =
                                            CheckboxDefaults.colors(
                                                checkedColor = StoreAccent,
                                                uncheckedColor = StoreTextSecondary,
                                                checkmarkColor = Color.White,
                                            ),
                                    )
                                }
                                Text(
                                    dlc.name,
                                    color = StoreTextPrimary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (dlc.downloadSize > 0L) StorageUtils.formatBinarySize(dlc.downloadSize) else "—",
                                    color = StoreTextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 10.dp),
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
private fun StoreDlcDivider() {
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.16f),
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

private fun buildDlcSummary(
    selectedCount: Int,
    totalCount: Int,
    installedCount: Int,
    selectedSize: Long,
    totalSize: Long,
): String {
    val totalSizeStr = if (totalSize > 0L) StorageUtils.formatBinarySize(totalSize) else null
    val selectedSizeStr = if (selectedSize > 0L) StorageUtils.formatBinarySize(selectedSize) else null
    val selectionText =
        when {
            totalCount == 0 -> null
            selectedCount == 0 && totalSizeStr != null -> "$totalCount available · $totalSizeStr total"
            selectedCount == 0 -> "$totalCount available"
            selectedSizeStr != null && totalSizeStr != null ->
                "$selectedCount of $totalCount · $selectedSizeStr / $totalSizeStr"
            else -> "$selectedCount of $totalCount selected"
        }
    val installedText = if (installedCount > 0) "$installedCount installed" else null
    return when {
        selectionText != null && installedText != null -> "$selectionText · $installedText"
        selectionText != null -> selectionText
        installedText != null -> installedText
        else -> ""
    }
}

@Composable
private fun StoreSourceTag(
    sourceLabel: String,
    menuEnabled: Boolean = false,
    menuOpen: Boolean = false,
    onMenuOpenChange: (Boolean) -> Unit = {},
    menuRegistry: PaneNavRegistry? = null,
    showCheckForUpdate: Boolean = false,
    showVerifyFiles: Boolean = false,
    showWorkshop: Boolean = false,
    isCheckingForUpdate: Boolean = false,
    areSteamActionsEnabled: Boolean = true,
    isUpdateCheckEnabled: Boolean = true,
    onVerifyFiles: () -> Unit = {},
    onCheckForUpdate: () -> Unit = {},
    onWorkshop: () -> Unit = {},
) {
    var anchorHeightPx by remember { mutableIntStateOf(0) }
    Box {
        Surface(
            color = Color.White.copy(alpha = 0.1f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            modifier =
                Modifier
                    .onSizeChanged { anchorHeightPx = it.height }
                    .then(
                        if (menuEnabled) {
                            Modifier
                                .paneNavItem(cornerRadius = 8.dp, onActivate = { onMenuOpenChange(!menuOpen) }, navRow = 0, navCol = 1)
                                .clickable { onMenuOpenChange(!menuOpen) }
                        } else {
                            Modifier
                        },
                    ),
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
                        .background(StoreAccent),
                )
                Text(
                    sourceLabel.uppercase(),
                    color = StoreTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (menuEnabled) {
                    Icon(
                        Icons.Outlined.ArrowDropDown,
                        contentDescription = stringResource(R.string.store_game_steam_options),
                        tint = StoreTextPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (menuEnabled) {
            val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
            StoreSourceActionPopup(
                expanded = menuOpen,
                onDismissRequest = { onMenuOpenChange(false) },
                offset = IntOffset(0, anchorHeightPx + gapPx),
            ) {
                CompositionLocalProvider(LocalPaneNav provides menuRegistry) {
                    if (showVerifyFiles) {
                        StoreSourceMenuItem(
                            icon = Icons.AutoMirrored.Outlined.FactCheck,
                            label = stringResource(R.string.store_game_verify_files),
                            enabled = areSteamActionsEnabled && !isCheckingForUpdate,
                        ) { onMenuOpenChange(false); onVerifyFiles() }
                    }
                    if (showCheckForUpdate) {
                        StoreSourceMenuItem(
                            icon = Icons.Outlined.Refresh,
                            label =
                                if (isCheckingForUpdate) {
                                    stringResource(R.string.store_game_checking_for_update)
                                } else {
                                    stringResource(R.string.store_game_check_for_update)
                                },
                            enabled = areSteamActionsEnabled && isUpdateCheckEnabled,
                        ) { onMenuOpenChange(false); onCheckForUpdate() }
                    }
                    if (showWorkshop) {
                        StoreSourceMenuItem(
                            icon = Icons.Outlined.Construction,
                            label = stringResource(R.string.store_game_workshop),
                            enabled = areSteamActionsEnabled,
                        ) { onMenuOpenChange(false); onWorkshop() }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreBranchTag(
    branches: List<StoreBranchOption>,
    selectedBranchId: String,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    menuRegistry: PaneNavRegistry?,
    onSelectBranch: (String) -> Unit,
) {
    val selected =
        branches.firstOrNull { it.id.equals(selectedBranchId, ignoreCase = true) }
            ?: branches.firstOrNull()
            ?: return
    val contentColor = if (enabled) StoreTextPrimary else StoreTextPrimary.copy(alpha = 0.45f)
    var anchorHeightPx by remember { mutableIntStateOf(0) }

    Box {
        Surface(
            color = Color.White.copy(alpha = 0.1f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            modifier =
                Modifier
                    .onSizeChanged { anchorHeightPx = it.height }
                    .then(
                        if (enabled) {
                            Modifier
                                .paneNavItem(
                                    cornerRadius = 8.dp,
                                    onActivate = { onExpandedChange(!expanded) },
                                    navRow = 0,
                                    navCol = 2,
                                ).clickable { onExpandedChange(!expanded) }
                        } else {
                            Modifier
                        },
                    ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Layers,
                    contentDescription = null,
                    tint = if (enabled) StoreAccentGlow else StoreTextSecondary,
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
            StoreSourceActionPopup(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                offset = IntOffset(0, anchorHeightPx + gapPx),
            ) {
                CompositionLocalProvider(LocalPaneNav provides menuRegistry) {
                    Column(
                        modifier =
                            Modifier
                                .widthIn(min = 172.dp, max = 240.dp)
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        branches.forEach { branch ->
                            StoreBranchMenuItem(
                                branch = branch,
                                isSelected = branch.id.equals(selected.id, ignoreCase = true),
                            ) {
                                onExpandedChange(false)
                                if (!branch.id.equals(selected.id, ignoreCase = true)) {
                                    onSelectBranch(branch.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreBranchPicker(
    branches: List<StoreBranchOption>,
    selectedBranchId: String,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    menuRegistry: PaneNavRegistry?,
    onSelectBranch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected =
        branches.firstOrNull { it.id.equals(selectedBranchId, ignoreCase = true) }
            ?: branches.firstOrNull()
            ?: return
    val contentColor = if (enabled) StoreTextPrimary else StoreTextPrimary.copy(alpha = 0.45f)
    var anchorHeightPx by remember { mutableIntStateOf(0) }

    Box(modifier) {
        Surface(
            color = StoreBlack.copy(alpha = 0.44f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.11f)),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { anchorHeightPx = it.height }
                    .then(
                        if (enabled) {
                            Modifier
                                .paneNavItem(
                                    cornerRadius = 12.dp,
                                    onActivate = { onExpandedChange(!expanded) },
                                    navRow = 1,
                                    navCol = 5,
                                ).clickable { onExpandedChange(!expanded) }
                        } else {
                            Modifier
                        },
                    ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    Icons.Outlined.Layers,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (enabled) StoreAccentGlow else StoreTextSecondary,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        stringResource(R.string.store_game_branch_label).uppercase(),
                        color = StoreTextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        selected.label,
                        color = contentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Outlined.ArrowDropDown,
                    contentDescription = stringResource(R.string.store_game_branch_label),
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (enabled) {
            val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
            StoreBranchPopup(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                offset = IntOffset(0, -(anchorHeightPx + gapPx)),
            ) {
                CompositionLocalProvider(LocalPaneNav provides menuRegistry) {
                    branches.forEach { branch ->
                        StoreBranchMenuItem(
                            branch = branch,
                            isSelected = branch.id.equals(selected.id, ignoreCase = true),
                        ) {
                            onExpandedChange(false)
                            if (!branch.id.equals(selected.id, ignoreCase = true)) {
                                onSelectBranch(branch.id)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreBranchPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: IntOffset,
    content: @Composable () -> Unit,
) {
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = expanded
    if (!transitionState.currentState && !transitionState.targetState) return

    Popup(
        alignment = Alignment.BottomStart,
        offset = offset,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = false),
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
                        transformOrigin = TransformOrigin(0f, 1f),
                    ),
            exit =
                fadeOut(animationSpec = tween(durationMillis = 80)) +
                    scaleOut(
                        animationSpec = tween(durationMillis = 110),
                        targetScale = 0.92f,
                        transformOrigin = TransformOrigin(0f, 1f),
                    ),
        ) {
            Surface(
                color = StoreBlack.copy(alpha = 0.88f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                tonalElevation = 0.dp,
                shadowElevation = 16.dp,
            ) {
                Column(
                    modifier =
                        Modifier
                            .widthIn(min = 208.dp, max = 320.dp)
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun StoreBranchMenuItem(
    branch: StoreBranchOption,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .paneNavItem(cornerRadius = 8.dp, onActivate = onClick)
                .clickable(onClick = onClick)
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.Layers,
            contentDescription = null,
            tint = if (isSelected) StoreAccentGlow else Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(16.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                branch.label,
                color = if (isSelected) StoreTextPrimary else Color.White.copy(alpha = 0.86f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (branch.buildId > 0L) {
                Text(
                    stringResource(R.string.store_game_branch_build, branch.buildId.toString()),
                    color = StoreTextSecondary,
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
                tint = StoreTextSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun StoreSourceActionPopup(
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
        properties = PopupProperties(focusable = false),
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
                color = StoreBlack.copy(alpha = 0.78f),
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
private fun StoreSourceMenuItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.45f)
    Row(
        modifier =
            Modifier
                .then(
                    if (enabled) {
                        Modifier
                            .paneNavItem(cornerRadius = 8.dp, onActivate = onClick)
                            .clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                )
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
private fun StoreStatChip(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color? = null,
) {
    Surface(
        color = StoreBlack.copy(alpha = 0.44f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.11f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = StoreAccentGlow)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    label.uppercase(),
                    color = StoreTextSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    value,
                    color = valueColor ?: StoreTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StoreActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        color = StoreBlack.copy(alpha = 0.44f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, StoreAccentGlow.copy(alpha = 0.36f)),
        modifier =
            Modifier
                .paneNavItem(cornerRadius = 12.dp, onActivate = onClick, navRow = 1, navCol = 0)
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = StoreAccentGlow)
            Text(
                label,
                color = StoreTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StoreCtaButton(
    height: Dp,
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    isEntry: Boolean = false,
    danger: Boolean = false,
    navRow: Int? = null,
    navCol: Int? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "storeCtaScale",
    )
    val flare by animateFloatAsState(
        targetValue = if (isPressed && enabled) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "storeCtaFlare",
    )
    val shape = remember { RoundedCornerShape(14.dp) }
    val activeBrush =
        Brush.horizontalGradient(
            colors =
                if (danger) {
                    listOf(
                        StoreDanger.copy(alpha = 0.30f),
                        StoreDanger.copy(alpha = 0.44f),
                        StoreDanger.copy(alpha = 0.30f),
                    )
                } else {
                    listOf(
                        Color(0xFF00B4D8).copy(alpha = 0.38f),
                        StoreAccent.copy(alpha = 0.38f),
                        Color(0xFF7B2FF7).copy(alpha = 0.38f),
                    )
                },
        )
    val disabledBrush =
        Brush.horizontalGradient(
            colors =
                listOf(
                    Color(0xFF3A3A4A).copy(alpha = 0.35f),
                    Color(0xFF2A2A36).copy(alpha = 0.35f),
                ),
        )
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
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(height)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(shape)
                .paneNavItem(
                    cornerRadius = 14.dp,
                    onActivate = { if (enabled && !loading) onClick() },
                    isEntry = isEntry,
                    navRow = navRow,
                    navCol = navCol,
                )
                .background(if (enabled) activeBrush else disabledBrush)
                .background(glassSheenBrush)
                .border(1.dp, glassRimBrush, shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { if (enabled && !loading) onClick() },
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StoreIconActionButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    onClick: () -> Unit,
    tint: Color = Color.White,
    navRow: Int? = null,
    navCol: Int? = null,
) {
    Surface(
        modifier =
            Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .paneNavItem(cornerRadius = 8.dp, onActivate = onClick, navRow = navRow, navCol = navCol)
                .clickable(onClick = onClick),
        color = StoreBlack.copy(alpha = 0.46f),
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

@Composable
private fun StoreScreenCutoutMode() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

    val view = LocalView.current
    DisposableEffect(view) {
        val window =
            (view.parent as? DialogWindowProvider)?.window
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
        // FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS is required for navigationBarColor to take effect.
        // Compose Dialog windows use Theme.DeviceDefault.Dialog which doesn't set it by default,
        // so the system would otherwise draw its own opaque navbar over our transparent request.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
        )
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        window.attributes =
            window.attributes.apply {
                layoutInDisplayCutoutMode = storeCutoutMode()
            }
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        onDispose {
            window.attributes =
                window.attributes.apply {
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

private fun storeCutoutMode(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
