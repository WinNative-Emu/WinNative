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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.dialog.PopupDialog
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.focus.rememberSettingsContentNav
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem
import com.winlator.cmod.shared.ui.layout.isCompactWidth

private val BgDark = Color(0xFF11111C)
private val CardDark = Color(0xFF1C1C2A)
private val CardDarker = Color(0xFF15151E)
private val CardBorder = Color(0xFF2A2A3A)
private val IconBoxBg = Color(0xFF242434)
private val Accent = Color(0xFF1A9FFF)
private val NavHighlight = Color(0xFF4FC3F7)
private val SuccessGreen = Color(0xFF5BD68F)
private val DangerRed = Color(0xFFFF7A88)
private val TextPrimary = Color(0xFFD6DAE0)
private val TextSecondary = Color(0xFF7A8FA8)

@Composable
private fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}

// State
data class DriversState(
    val installedDrivers: List<InstalledDriverItem> = emptyList(),
    val sources: List<DriverRepo> = emptyList(),
    val releasesBySource: Map<String, List<DriverReleaseItem>> = emptyMap(),
    val expandedSourceApiUrl: String? = null,
    val expandedReleaseId: Long? = null,
    val loadingSourceApiUrl: String? = null,
    val hasMissingDefaults: Boolean = false,
    // Names of GitHub release assets already installed — used to show an "Installed"
    // badge on AssetRow and block duplicate downloads.
    val installedAssetNames: Set<String> = emptySet(),
    // Non-null when a download or install is in flight; drives the Compose progress dialog.
    val downloadProgress: DownloadProgress? = null,
)

/**
 * Snapshot of an in-flight driver download or install, surfaced via DriversState and
 * rendered by DownloadProgressDialog. [progress] is a 0..1 fraction; [indeterminate] is
 * true while we don't yet know total bytes or during the install (unzip) phase.
 */
data class DownloadProgress(
    val title: String,
    val assetName: String,
    val progress: Float = 0f,
    val indeterminate: Boolean = false,
)

data class InstalledDriverItem(
    val id: String,
    val name: String,
    val version: String,
)

data class DriverRepo(
    val name: String,
    val repoUrl: String,
    val apiUrl: String,
)

data class DriverReleaseItem(
    val id: Long,
    val title: String,
    val subtitle: String,
    val notes: String,
    val assets: List<DriverAssetItem>,
)

data class DriverAssetItem(
    val id: Long,
    val name: String,
    val downloadUrl: String,
    val sizeLabel: String,
)

// Root
@Composable
fun DriversScreen(
    state: DriversState,
    onInstallFromFile: () -> Unit,
    onSourceTapped: (DriverRepo) -> Unit,
    onReleaseTapped: (DriverReleaseItem) -> Unit,
    onDownloadAsset: (DriverAssetItem) -> Unit,
    onRemoveDriver: (InstalledDriverItem) -> Unit,
    onRepoAdded: (name: String, apiUrl: String) -> Unit,
    onRepoUpdated: (index: Int, name: String, apiUrl: String) -> Unit,
    onRepoDeleted: (index: Int) -> Unit,
    onRestoreDefaultRepos: () -> Unit,
    bridge: SettingsNavBridge? = null,
) {
    var showAddRepoDialog by remember { mutableStateOf(false) }
    var editingRepo by remember { mutableStateOf<Pair<Int, DriverRepo>?>(null) }
    var driverPendingRemoval by remember { mutableStateOf<InstalledDriverItem?>(null) }
    var repoPendingRemoval by remember { mutableStateOf<Pair<Int, DriverRepo>?>(null) }
    val layoutDirection = LocalLayoutDirection.current
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val navBarStartPadding = navBarPadding.calculateStartPadding(layoutDirection)
    val navBarEndPadding = navBarPadding.calculateEndPadding(layoutDirection)
    val navBarBottomPadding = navBarPadding.calculateBottomPadding()
    val contentNav = rememberSettingsContentNav(bridge)

    if (showAddRepoDialog || editingRepo != null) {
        val editing = editingRepo
        RepoEditDialog(
            existing = editing?.second,
            onDismiss = {
                showAddRepoDialog = false
                editingRepo = null
            },
            onConfirm = { name, apiUrl ->
                if (editing != null) {
                    onRepoUpdated(editing.first, name, apiUrl)
                } else {
                    onRepoAdded(name, apiUrl)
                }
                showAddRepoDialog = false
                editingRepo = null
            },
        )
    }

    driverPendingRemoval?.let { driver ->
        val nav = remember { PaneNavRegistry() }
        Dialog(onDismissRequest = { driverPendingRemoval = null }) {
            DialogPaneNav(nav, onDismiss = { driverPendingRemoval = null })
            CompositionLocalProvider(LocalPaneNav provides nav) {
                PopupDialog(
                    title = stringResource(R.string.settings_drivers_remove_title),
                    message = stringResource(R.string.settings_drivers_confirm_remove),
                    confirmLabel = stringResource(R.string.common_ui_remove),
                    modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                    icon = Icons.Outlined.Delete,
                    accentColor = DangerRed,
                    onCancel = { driverPendingRemoval = null },
                    onConfirm = {
                        onRemoveDriver(driver)
                        driverPendingRemoval = null
                    },
                )
            }
        }
    }

    repoPendingRemoval?.let { (index, repo) ->
        val nav = remember { PaneNavRegistry() }
        Dialog(onDismissRequest = { repoPendingRemoval = null }) {
            DialogPaneNav(nav, onDismiss = { repoPendingRemoval = null })
            CompositionLocalProvider(LocalPaneNav provides nav) {
                PopupDialog(
                    title = stringResource(R.string.settings_drivers_remove_repo_title),
                    message = stringResource(R.string.settings_drivers_remove_repo_message, repo.name),
                    confirmLabel = stringResource(R.string.common_ui_remove),
                    modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                    icon = Icons.Outlined.Delete,
                    accentColor = DangerRed,
                    onCancel = { repoPendingRemoval = null },
                    onConfirm = {
                        onRepoDeleted(index)
                        repoPendingRemoval = null
                    },
                )
            }
        }
    }

    state.downloadProgress?.let { progress ->
        DownloadProgressDialog(progress = progress)
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
            HeroHeader(
                installedCount = state.installedDrivers.size,
                repoCount = state.sources.size,
                onInstall = onInstallFromFile,
                onAddRepo = { showAddRepoDialog = true },
            )

            if (state.installedDrivers.isEmpty() && state.sources.isEmpty()) {
                EmptyState()
            }

            if (state.installedDrivers.isNotEmpty()) {
                SectionLabel(
                    text = stringResource(R.string.common_ui_installed),
                    modifier = Modifier.padding(top = 4.dp),
                )
                state.installedDrivers.forEach { driver ->
                    key(driver.id) {
                        InstalledDriverCard(
                            driver = driver,
                            onRemove = { driverPendingRemoval = driver },
                        )
                    }
                }
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                SectionLabel(
                    text = stringResource(R.string.settings_drivers_github_repos),
                    modifier = Modifier.weight(1f),
                )
                if (state.hasMissingDefaults) {
                    SmallPillButton(
                        label = "Restore defaults",
                        icon = Icons.Outlined.Restore,
                        tint = Accent,
                        onClick = onRestoreDefaultRepos,
                    )
                }
            }

            if (state.sources.isEmpty()) {
                EmptyRepoCard()
            } else {
                state.sources.forEachIndexed { index, source ->
                    key(source.apiUrl) {
                        val releases = state.releasesBySource[source.apiUrl].orEmpty()
                        val isExpanded = state.expandedSourceApiUrl == source.apiUrl
                        val isLoading = state.loadingSourceApiUrl == source.apiUrl
                        RepoCard(
                            source = source,
                            isExpanded = isExpanded,
                            isLoading = isLoading,
                            releases = releases,
                            expandedReleaseId = state.expandedReleaseId,
                            installedAssetNames = state.installedAssetNames,
                            onTap = { onSourceTapped(source) },
                            onReleaseTap = onReleaseTapped,
                            onDownloadAsset = onDownloadAsset,
                            onEdit = { editingRepo = index to source },
                            onDelete = { repoPendingRemoval = index to source },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// Hero header

@Composable
private fun HeroHeader(
    installedCount: Int,
    repoCount: Int,
    onInstall: () -> Unit,
    onAddRepo: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(text = stringResource(R.string.settings_drivers_manager_header))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardDark)
                    .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val compactHeader = maxWidth < 430.dp
                if (compactHeader) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DriverManagerCounts(
                            installedCount = installedCount,
                            repoCount = repoCount,
                            stacked = true,
                        )
                        Spacer(Modifier.width(10.dp))
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DriverManagerActions(
                                onAddRepo = onAddRepo,
                                onInstall = onInstall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DriverManagerCounts(
                            installedCount = installedCount,
                            repoCount = repoCount,
                            modifier = Modifier.weight(1f),
                        )

                        Spacer(Modifier.width(12.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DriverManagerActions(
                                onAddRepo = onAddRepo,
                                onInstall = onInstall,
                                // The 112 dp minimum opposite the weighted counts column
                                // ellipsizes driver names on a phone.
                                modifier =
                                    Modifier.widthIn(
                                        min = if (isCompactWidth()) 0.dp else 112.dp,
                                        max = 132.dp,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DriverManagerCounts(
    installedCount: Int,
    repoCount: Int,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
) {
    if (stacked) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            CountPill(label = "Installed", count = installedCount)
            CountPill(label = "Repos", count = repoCount)
        }
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CountPill(label = "Installed", count = installedCount)
            Spacer(Modifier.width(6.dp))
            CountPill(label = "Repos", count = repoCount)
        }
    }
}

@Composable
private fun DriverManagerActions(
    onAddRepo: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HeroButton(
        label = "Add Repo",
        icon = Icons.Outlined.Add,
        onClick = onAddRepo,
        modifier = modifier,
    )
    HeroButton(
        label = stringResource(R.string.settings_drivers_install),
        icon = Icons.Outlined.Upload,
        onClick = onInstall,
        modifier = modifier,
    )
}

@Composable
private fun CountPill(
    label: String,
    count: Int,
) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Accent.copy(alpha = 0.12f))
                .border(1.dp, Accent.copy(alpha = 0.28f), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = count.toString(),
            color = Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(9.dp))
                .background(Accent.copy(alpha = 0.12f))
                .border(1.dp, Accent.copy(alpha = 0.32f), RoundedCornerShape(9.dp))
                .paneNavItem(
                    cornerRadius = 9.dp,
                    onActivate = onClick,
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                )
                .height(30.dp)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                color = Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

// Installed driver card

@Composable
private fun InstalledDriverCard(
    driver: InstalledDriverItem,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
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
                    imageVector = Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = driver.name,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        driver.version.ifBlank {
                            stringResource(R.string.settings_drivers_repo_no_version)
                        },
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconTapButton(
                    icon = Icons.Outlined.MoreVert,
                    tint = TextSecondary,
                    onClick = { menuOpen = true },
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = CardDark,
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = DangerRed,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Remove",
                                    color = DangerRed,
                                    fontSize = 13.sp,
                                )
                            }
                        },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

// Repo card with inline expandable releases

@Composable
private fun RepoCard(
    source: DriverRepo,
    isExpanded: Boolean,
    isLoading: Boolean,
    releases: List<DriverReleaseItem>,
    expandedReleaseId: Long?,
    installedAssetNames: Set<String>,
    onTap: () -> Unit,
    onReleaseTap: (DriverReleaseItem) -> Unit,
    onDownloadAsset: (DriverAssetItem) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val borderColor = if (isExpanded) Accent.copy(alpha = 0.45f) else CardBorder
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "repoChevron_${source.apiUrl}",
    )
    var menuOpen by remember { mutableStateOf(false) }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
    ) {
        Column {
            // Header row — tappable
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .paneNavItem(
                            cornerRadius = 12.dp,
                            onActivate = { if (!isLoading) onTap() },
                            highlightColor = NavHighlight,
                            tapToSelect = true,
                        )
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RepoIconBadge(loading = isLoading, expanded = isExpanded)
                Spacer(Modifier.width(13.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.name,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            when {
                                isLoading -> stringResource(R.string.settings_drivers_repo_loading_releases)
                                releases.isNotEmpty() -> stringResource(R.string.settings_drivers_repo_release_count, releases.size)
                                else -> stringResource(R.string.settings_drivers_repo_tap_to_load)
                            },
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Box {
                    IconTapButton(
                        icon = Icons.Outlined.MoreVert,
                        tint = TextSecondary,
                        onClick = { menuOpen = true },
                    )
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = CardDark,
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = null,
                                        tint = Accent,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Edit", color = TextPrimary, fontSize = 13.sp)
                                }
                            },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = null,
                                        tint = DangerRed,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Delete", color = DangerRed, fontSize = 13.sp)
                                }
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }

                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = if (isExpanded) Accent else TextSecondary,
                    modifier =
                        Modifier
                            .size(20.dp)
                            .rotate(chevronRotation),
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter =
                    fadeIn(tween(200)) +
                        expandVertically(
                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        ),
                exit =
                    fadeOut(tween(140)) +
                        shrinkVertically(
                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        ),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(CardDarker)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isLoading && releases.isEmpty()) {
                        LoadingPlaceholder()
                    } else if (releases.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_drivers_repo_no_release_assets),
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 14.dp, horizontal = 6.dp),
                        )
                    } else {
                        releases.forEach { release ->
                            ReleaseCard(
                                release = release,
                                expanded = expandedReleaseId == release.id,
                                installedAssetNames = installedAssetNames,
                                onTap = { onReleaseTap(release) },
                                onDownloadAsset = onDownloadAsset,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoIconBadge(
    loading: Boolean,
    expanded: Boolean,
) {
    val accentColor = if (expanded || loading) Accent else TextSecondary
    Box(
        modifier =
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(IconBoxBg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Hub,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun LoadingPlaceholder() {
    Text(
        text = stringResource(R.string.settings_drivers_repo_loading_releases),
        color = TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 14.dp, horizontal = 6.dp),
    )
}

// Release card with expandable notes + assets

@Composable
private fun ReleaseCard(
    release: DriverReleaseItem,
    expanded: Boolean,
    installedAssetNames: Set<String>,
    onTap: () -> Unit,
    onDownloadAsset: (DriverAssetItem) -> Unit,
) {
    val borderColor = if (expanded) Accent.copy(alpha = 0.45f) else CardBorder
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "releaseChevron_${release.id}",
    )
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CardDark)
                .border(1.dp, borderColor, RoundedCornerShape(10.dp)),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .paneNavItem(
                            cornerRadius = 10.dp,
                            onActivate = onTap,
                            highlightColor = NavHighlight,
                            tapToSelect = true,
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Accent.copy(alpha = if (expanded) 1f else 0.55f)),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = release.title,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (release.subtitle.isNotBlank()) {
                        Text(
                            text = release.subtitle,
                            color = TextSecondary,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = if (expanded) Accent else TextSecondary,
                    modifier =
                        Modifier
                            .size(18.dp)
                            .rotate(chevronRotation),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter =
                    fadeIn(tween(200)) +
                        expandVertically(
                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        ),
                exit =
                    fadeOut(tween(140)) +
                        shrinkVertically(
                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        ),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (release.notes.isNotBlank()) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CardDarker)
                                    .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                        ) {
                            Text(
                                text = release.notes,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                    release.assets.forEach { asset ->
                        AssetRow(
                            asset = asset,
                            isInstalled = asset.name in installedAssetNames,
                            onDownload = { onDownloadAsset(asset) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssetRow(
    asset: DriverAssetItem,
    isInstalled: Boolean,
    onDownload: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(IconBoxBg)
                .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isInstalled) Icons.Outlined.CloudDone else Icons.Outlined.CloudDownload,
            contentDescription = null,
            tint = if (isInstalled) SuccessGreen else Accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = asset.name,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (asset.sizeLabel.isNotBlank()) {
                Text(
                    text = asset.sizeLabel,
                    color = TextSecondary,
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (isInstalled) {
            InstalledPill()
        } else {
            SmallPillButton(
                label = "Download",
                icon = Icons.Outlined.Download,
                tint = Accent,
                compact = true,
                onClick = onDownload,
            )
        }
    }
}

// Non-interactive status pill shown in place of the Download button when an asset
// is already installed. Styled in the success-green palette so the row reads as
// "done" without going fully greyed-out like a disabled pill would.
@Composable
private fun InstalledPill() {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(SuccessGreen.copy(alpha = 0.14f))
                .border(1.dp, SuccessGreen.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = null,
            tint = SuccessGreen,
            modifier = Modifier.size(11.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "Installed",
            color = SuccessGreen,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// Generic small controls

@Composable
private fun IconTapButton(
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = onClick,
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SmallPillButton(
    label: String,
    icon: ImageVector?,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    // When disabled, swap the accent tint for the muted secondary color so the pill
    // visibly greys out, and drop the click target so taps are ignored.
    val effectiveTint = if (enabled) tint else TextSecondary.copy(alpha = 0.55f)
    val borderColor = if (enabled) tint.copy(alpha = 0.3f) else CardBorder
    val background = if (enabled) tint.copy(alpha = 0.14f) else CardDarker
    val horizontalPadding = if (compact) 8.dp else 10.dp
    val verticalPadding = if (compact) 4.dp else 6.dp
    val iconSize = if (compact) 11.dp else 12.dp
    val labelSize = if (compact) 10.sp else 11.sp
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(background)
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .then(
                    if (enabled) {
                        Modifier.paneNavItem(
                            cornerRadius = 8.dp,
                            onActivate = onClick,
                            highlightColor = NavHighlight,
                            tapToSelect = true,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = effectiveTint,
                modifier = Modifier.size(iconSize),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = label,
            color = effectiveTint,
            fontSize = labelSize,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DialogActionButton(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
) {
    val nav = LocalPaneNav.current
    val clickModifier =
        if (nav != null) {
            Modifier.paneNavItem(
                cornerRadius = 8.dp,
                onActivate = onClick,
                highlightColor = NavHighlight,
                tapToSelect = true,
            )
        } else {
            Modifier
                .paneNavItem(cornerRadius = 8.dp, onActivate = onClick, highlightColor = NavHighlight)
                .noRippleClickable(onClick = onClick)
        }
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(CardDarker)
                .border(1.dp, textColor.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                .then(clickModifier)
                .padding(horizontal = 14.dp, vertical = 8.dp),
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

// Empty state

@Composable
private fun EmptyRepoCard() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(IconBoxBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Hub,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "No repositories",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Restore the default GitHub driver sources or add one manually.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 20.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Inbox,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "No drivers yet",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Install a ZIP package or load a repository to get started.",
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

// Dialogs

@Composable
private fun RepoEditDialog(
    existing: DriverRepo?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, apiUrl: String) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.apiUrl.orEmpty()) }
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
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .widthIn(max = 440.dp)
                            .fillMaxWidth()
                            .heightIn(max = maxHeight)
                            .clip(RoundedCornerShape(16.dp))
                            .background(CardDark)
                            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .wrapContentHeight()
                                .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = if (existing == null) "Add Repository" else "Edit Repository",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))

                        LabeledField(
                            label = "Name",
                            value = name,
                            onValueChange = { name = it },
                            placeholder = "Display name",
                            isEntry = true,
                        )
                        Spacer(Modifier.height(6.dp))
                        LabeledField(
                            label = "GitHub URL",
                            value = url,
                            onValueChange = { url = it },
                            placeholder = "https://github.com/owner/repo/releases",
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        )

                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            DialogActionButton(label = "Cancel", textColor = TextSecondary, onClick = onDismiss)
                            DialogActionButton(
                                label = if (existing == null) "Add" else "Save",
                                textColor = Accent,
                                onClick = {
                                    val trimmedName = name.trim()
                                    val trimmedUrl = url.trim()
                                    if (trimmedName.isNotEmpty() && trimmedUrl.isNotEmpty()) {
                                        onConfirm(trimmedName, trimmedUrl)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// Download / install progress dialog

@Composable
private fun DownloadProgressDialog(progress: DownloadProgress) {
    Dialog(
        onDismissRequest = { /* non-dismissable while a transfer is in flight */ },
        properties =
            DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
        PopupDialog(
            title = progress.title,
            message = progress.assetName,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.88f),
            icon = Icons.Outlined.Download,
            accentColor = Accent,
            progress = if (progress.indeterminate) Float.NaN else progress.progress,
        )
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    isEntry: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(4.dp))
        // Focus-aware border so the field outlines blue while being edited, matching
        // the Material OutlinedTextField look used elsewhere (e.g. SteamLoginActivity).
        val interactionSource = remember { MutableInteractionSource() }
        val isFocused by interactionSource.collectIsFocusedAsState()
        val borderColor = if (isFocused) Accent else CardBorder
        val borderWidth = if (isFocused) 1.5.dp else 1.dp
        val fieldFocus = remember { FocusRequester() }
        // Fixed min height + CenterStart alignment so the field keeps a consistent
        // tappable bar instead of collapsing to the font's intrinsic line height
        // (which made typed text look squashed against the top of the box).
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .requiredHeightIn(min = 40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(CardDarker)
                    .border(borderWidth, borderColor, RoundedCornerShape(9.dp))
                    .paneNavItem(
                        cornerRadius = 9.dp,
                        onActivate = { runCatching { fieldFocus.requestFocus() } },
                        highlightColor = NavHighlight,
                        isEntry = isEntry,
                    )
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                cursorBrush =
                    androidx.compose.ui.graphics
                        .SolidColor(Accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                interactionSource = interactionSource,
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                decorationBox = { innerTextField ->
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = TextSecondary.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                        )
                    }
                    innerTextField()
                },
            )
        }
    }
}
