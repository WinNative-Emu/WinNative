package com.winlator.cmod.feature.settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.R
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.shared.ui.dialog.PopupDialog
import com.winlator.cmod.shared.ui.focus.rememberSettingsContentNav
import com.winlator.cmod.shared.ui.layout.isPortraitLayout
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem

// Palette (unified with Drivers / Stores / Other / Debug)
private val BgDark = Color(0xFF11111C)
private val CardDark = Color(0xFF1C1C2A)
private val CardDarker = Color(0xFF15151E)
private val CardBorder = Color(0xFF2A2A3A)
private val IconBoxBg = Color(0xFF242434)
private val SurfaceDark = Color(0xFF21212A)
private val Accent = Color(0xFF1A9FFF)
private val SuccessGreen = Color(0xFF5BD68F)
private val DangerRed = Color(0xFFFF7A88)
private val WarningAmber = Color(0xFFFFB454)
private val TextPrimary = Color(0xFFD6DAE0)
private val TextSecondary = Color(0xFF7A8FA8)
private val NavHighlight = Color(0xFF4FC3F7)

// State

data class ComponentItem(
    val key: String,
    val type: ContentProfile.ContentType,
    val verName: String,
    val isInstalled: Boolean,
    val hasRemote: Boolean,
    val sizeBytes: Long? = null,
    val isOfficial: Boolean = false,
)

data class LinuxComponentItem(
    val key: String,
    val type: ContentProfile.ContentType,
    val verName: String,
    val isInstalled: Boolean,
    val hasRemote: Boolean,
    val sizeBytes: Long? = null,
    val isOfficial: Boolean = false,
    val isWorking: Boolean = false,
    val progress: Float = Float.NaN,
)

data class ComponentsDownloadProgress(
    val title: String,
    val message: String,
    val progress: Float = 0f,
    val indeterminate: Boolean = false,
)

data class ComponentsConflict(
    val path: String,
)

enum class ComponentsPlatform {
    ANDROID,
    LINUX,
}

data class ComponentsState(
    val platform: ComponentsPlatform = ComponentsPlatform.ANDROID,
    val currentType: ContentProfile.ContentType = ContentProfile.ContentType.CONTENT_TYPE_WINE,
    val installed: List<ComponentItem> = emptyList(),
    val available: List<ComponentItem> = emptyList(),
    val linuxCurrentType: ContentProfile.ContentType = ContentProfile.ContentType.CONTENT_TYPE_PROTON,
    val linuxInstalled: List<LinuxComponentItem> = emptyList(),
    val linuxAvailable: List<LinuxComponentItem> = emptyList(),
    val downloadProgress: ComponentsDownloadProgress? = null,
    val conflict: ComponentsConflict? = null,
    val autoCreateContainer: Boolean = true,
    val isRefreshing: Boolean = false,
    val loadFailed: Boolean = false,
)

// Root

@Composable
fun ComponentsScreen(
    bridge: SettingsNavBridge? = null,
    state: ComponentsState,
    onPlatformSelected: (ComponentsPlatform) -> Unit,
    onTypeSelected: (ContentProfile.ContentType) -> Unit,
    onInstallFromFile: () -> Unit,
    onDownloadItem: (ComponentItem) -> Unit,
    onRemoveItem: (ComponentItem) -> Unit,
    onDownloadLinuxItem: (LinuxComponentItem) -> Unit,
    onRemoveLinuxItem: (LinuxComponentItem) -> Unit,
    onCancelLinuxItem: (LinuxComponentItem) -> Unit,
    onDismissConflict: () -> Unit,
    onToggleAutoCreateContainer: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    var itemPendingRemoval by remember { mutableStateOf<ComponentItem?>(null) }
    var linuxItemPendingRemoval by remember { mutableStateOf<LinuxComponentItem?>(null) }
    val layoutDirection = LocalLayoutDirection.current
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val navBarStartPadding = navBarPadding.calculateStartPadding(layoutDirection)
    val navBarEndPadding = navBarPadding.calculateEndPadding(layoutDirection)
    val navBarBottomPadding = navBarPadding.calculateBottomPadding()
    val contentNav = rememberSettingsContentNav(bridge)

    val sectionSignal = bridge?.contentSectionSignal ?: 0
    var lastSectionSignal by remember { mutableStateOf(sectionSignal) }
    LaunchedEffect(sectionSignal) {
        if (sectionSignal != lastSectionSignal) {
            lastSectionSignal = sectionSignal
            val dir = bridge?.contentSectionDir ?: 0
            if (dir != 0) {
                if (state.platform == ComponentsPlatform.ANDROID) {
                    val types = ContentProfile.ContentType.values()
                    val idx = types.indexOf(state.currentType).coerceAtLeast(0)
                    onTypeSelected(types[((idx + dir) % types.size + types.size) % types.size])
                } else {
                    onPlatformSelected(ComponentsPlatform.ANDROID)
                }
            }
        }
    }

    itemPendingRemoval?.let { item ->
        val nav = remember { PaneNavRegistry() }
        Dialog(onDismissRequest = { itemPendingRemoval = null }) {
            DialogPaneNav(nav, onDismiss = { itemPendingRemoval = null })
            CompositionLocalProvider(LocalPaneNav provides nav) {
                PopupDialog(
                    title = stringResource(R.string.settings_content_remove_title),
                    message = stringResource(R.string.settings_content_confirm_remove),
                    confirmLabel = stringResource(R.string.common_ui_remove),
                    modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                    icon = Icons.Outlined.Delete,
                    accentColor = DangerRed,
                    onCancel = { itemPendingRemoval = null },
                    onConfirm = {
                        onRemoveItem(item)
                        itemPendingRemoval = null
                    },
                )
            }
        }
    }

    linuxItemPendingRemoval?.let { item ->
        val nav = remember { PaneNavRegistry() }
        Dialog(onDismissRequest = { linuxItemPendingRemoval = null }) {
            DialogPaneNav(nav, onDismiss = { linuxItemPendingRemoval = null })
            CompositionLocalProvider(LocalPaneNav provides nav) {
                PopupDialog(
                    title = stringResource(R.string.settings_content_remove_title),
                    message = stringResource(R.string.settings_content_confirm_remove),
                    confirmLabel = stringResource(R.string.common_ui_remove),
                    modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                    icon = Icons.Outlined.Delete,
                    accentColor = DangerRed,
                    onCancel = { linuxItemPendingRemoval = null },
                    onConfirm = {
                        onRemoveLinuxItem(item)
                        linuxItemPendingRemoval = null
                    },
                )
            }
        }
    }

    state.downloadProgress?.let { progress ->
        DownloadProgressDialog(progress = progress)
    }

    state.conflict?.let { conflict ->
        val nav = remember { PaneNavRegistry() }
        Dialog(onDismissRequest = onDismissConflict) {
            DialogPaneNav(nav, onDismiss = onDismissConflict)
            CompositionLocalProvider(LocalPaneNav provides nav) {
                PopupDialog(
                    title = stringResource(R.string.settings_content_already_installed_title),
                    message = stringResource(R.string.settings_content_already_installed_message, conflict.path),
                    confirmLabel = stringResource(R.string.common_ui_ok),
                    modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                    icon = Icons.Outlined.Warning,
                    accentColor = WarningAmber,
                    onConfirm = onDismissConflict,
                )
            }
        }
    }

    val installedCount =
        if (state.platform == ComponentsPlatform.LINUX) state.linuxInstalled.size else state.installed.size
    val availableCount =
        if (state.platform == ComponentsPlatform.LINUX) state.linuxAvailable.size else state.available.size

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
                installedCount = installedCount,
                availableCount = availableCount,
                platform = state.platform,
                currentType = state.currentType,
                linuxCurrentType = state.linuxCurrentType,
                autoCreateContainer = state.autoCreateContainer,
                isRefreshing = state.isRefreshing,
                loadFailed = state.loadFailed,
                onPlatformSelected = onPlatformSelected,
                onTypeSelected = onTypeSelected,
                onInstallFromFile = onInstallFromFile,
                onToggleAutoCreateContainer = onToggleAutoCreateContainer,
                onRefresh = onRefresh,
            )

            if (state.platform == ComponentsPlatform.LINUX) {
                if (state.linuxInstalled.isEmpty() && state.linuxAvailable.isEmpty() && !state.isRefreshing) {
                    EmptyState()
                } else {
                    if (state.linuxInstalled.isNotEmpty()) {
                        SectionLabel(
                            text = stringResource(R.string.common_ui_installed),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        state.linuxInstalled.forEach { item ->
                            key("linux_installed_${item.key}") {
                                LinuxComponentItemCard(
                                    item = item,
                                    onDownload = { onDownloadLinuxItem(item) },
                                    onRemove = { linuxItemPendingRemoval = item },
                                    onCancel = { onCancelLinuxItem(item) },
                                )
                            }
                        }
                    }

                    if (state.linuxAvailable.isNotEmpty()) {
                        SectionLabel(
                            text = stringResource(R.string.common_ui_available),
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        state.linuxAvailable.forEach { item ->
                            key("linux_available_${item.key}") {
                                LinuxComponentItemCard(
                                    item = item,
                                    onDownload = { onDownloadLinuxItem(item) },
                                    onRemove = { linuxItemPendingRemoval = item },
                                    onCancel = { onCancelLinuxItem(item) },
                                )
                            }
                        }
                    }
                }
            } else if (state.installed.isEmpty() && state.available.isEmpty() && !state.isRefreshing) {
                EmptyState()
            } else {
                if (state.installed.isNotEmpty()) {
                    SectionLabel(
                        text = stringResource(R.string.common_ui_installed),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    state.installed.forEach { item ->
                        key("installed_${state.currentType.name}_${item.key}") {
                            ComponentItemCard(
                                item = item,
                                onDownload = { onDownloadItem(item) },
                                onRemove = { itemPendingRemoval = item },
                            )
                        }
                    }
                }

                if (state.available.isNotEmpty()) {
                    SectionLabel(
                        text = stringResource(R.string.common_ui_available),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    state.available.forEach { item ->
                        key("available_${state.currentType.name}_${item.key}") {
                            ComponentItemCard(
                                item = item,
                                onDownload = { onDownloadItem(item) },
                                onRemove = { itemPendingRemoval = item },
                            )
                        }
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
    availableCount: Int,
    platform: ComponentsPlatform,
    currentType: ContentProfile.ContentType,
    linuxCurrentType: ContentProfile.ContentType,
    autoCreateContainer: Boolean,
    isRefreshing: Boolean,
    loadFailed: Boolean,
    onPlatformSelected: (ComponentsPlatform) -> Unit,
    onTypeSelected: (ContentProfile.ContentType) -> Unit,
    onInstallFromFile: () -> Unit,
    onToggleAutoCreateContainer: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val counts: @Composable () -> Unit = {
                CountPill(label = stringResource(R.string.common_ui_installed), count = installedCount)
                Spacer(Modifier.width(6.dp))
                CountPill(label = stringResource(R.string.common_ui_available), count = availableCount)
            }
            val toggle: @Composable (Modifier) -> Unit = { toggleModifier ->
                if (platform == ComponentsPlatform.ANDROID) {
                    ToggleChip(
                        label = stringResource(R.string.settings_content_auto_create_container),
                        enabled = autoCreateContainer,
                        compact = true,
                        modifier = toggleModifier,
                        onToggle = { onToggleAutoCreateContainer(!autoCreateContainer) },
                    )
                } else {
                    Spacer(toggleModifier)
                }
            }
            val refresh: @Composable () -> Unit = {
                RefreshChip(
                    isRefreshing = isRefreshing,
                    loadFailed = loadFailed,
                    onRefresh = onRefresh,
                )
            }
            val install: @Composable () -> Unit = {
                if (platform == ComponentsPlatform.ANDROID) {
                    SmallPillButton(
                        label = stringResource(R.string.settings_content_install),
                        icon = Icons.Outlined.Upload,
                        tint = Accent,
                        compact = true,
                        onClick = onInstallFromFile,
                    )
                }
            }

            if (isPortraitLayout()) {
                // Portrait has no room for counts + toggle + refresh + install on a
                // single line: the counts collapse to nothing and the install pill is
                // left a few characters wide, wrapping its label mid-word. Split the
                // controls over two rows so every chip keeps its natural width.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    counts()
                    Spacer(Modifier.weight(1f))
                    refresh()
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The install pill is unweighted, so it is measured at its full
                    // intrinsic width first; the toggle absorbs whatever is left and
                    // ellipsises its label rather than squeezing its neighbour.
                    toggle(Modifier.weight(1f))
                    Spacer(Modifier.width(16.dp))
                    install()
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        counts()
                    }
                    Spacer(Modifier.width(10.dp))
                    toggle(Modifier)
                    Spacer(Modifier.width(6.dp))
                    refresh()
                    Spacer(Modifier.width(8.dp))
                    install()
                }
            }

            Spacer(Modifier.height(12.dp))
            PlatformTabsContent(
                platform = platform,
                onPlatformSelected = onPlatformSelected,
            )
            Spacer(Modifier.height(10.dp))
            TypeTabsContent(
                currentType = if (platform == ComponentsPlatform.LINUX) linuxCurrentType else currentType,
                types = if (platform == ComponentsPlatform.LINUX) LINUX_CONTENT_TYPES else ContentProfile.ContentType.values().toList(),
                onTypeSelected = onTypeSelected,
            )
        }
    }
}

@Composable
private fun ToggleChip(
    label: String,
    enabled: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val tint = if (enabled) SuccessGreen else TextSecondary
    val background = if (enabled) SuccessGreen.copy(alpha = 0.14f) else SurfaceDark
    val borderColor = if (enabled) SuccessGreen.copy(alpha = 0.45f) else CardBorder
    val horizontalPadding = if (compact) 8.dp else 10.dp
    val verticalPadding = if (compact) 4.dp else 5.dp
    val dotSize = if (compact) 5.dp else 6.dp
    val fontSize = if (compact) 10.sp else 11.sp
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(background)
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = onToggle,
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                )
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        // Centres the dot + label when a caller stretches the chip (portrait);
        // a no-op when the chip sits at its natural width (landscape).
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(dotSize)
                    .clip(RoundedCornerShape(3.dp))
                    .background(tint),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = tint,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RefreshChip(
    isRefreshing: Boolean,
    loadFailed: Boolean,
    onRefresh: () -> Unit,
) {
    val tint = if (loadFailed) WarningAmber else Accent
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.14f))
                .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = { if (!isRefreshing) onRefresh() },
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 1.5.dp,
                color = tint,
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = stringResource(R.string.settings_content_refresh),
                color = tint,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
private fun PlatformTabsContent(
    platform: ComponentsPlatform,
    onPlatformSelected: (ComponentsPlatform) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlatformTabChip(
            label = "${stringResource(R.string.settings_drivers_android)} ${stringResource(R.string.settings_content_components)}",
            selected = platform == ComponentsPlatform.ANDROID,
            onClick = { onPlatformSelected(ComponentsPlatform.ANDROID) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        PlatformTabChip(
            label = "${stringResource(R.string.settings_drivers_linux)} ${stringResource(R.string.settings_content_components)}",
            selected = platform == ComponentsPlatform.LINUX,
            onClick = { onPlatformSelected(ComponentsPlatform.LINUX) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlatformTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) Accent.copy(alpha = 0.18f) else SurfaceDark
    val borderColor = if (selected) Accent.copy(alpha = 0.45f) else CardBorder
    val textColor = if (selected) Accent else TextSecondary
    Box(
        modifier =
            modifier
                .height(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(background)
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                .paneNavItem(
                    cornerRadius = 10.dp,
                    onActivate = onClick,
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                )
                .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TypeTabsContent(
    currentType: ContentProfile.ContentType,
    types: List<ContentProfile.ContentType>,
    onTypeSelected: (ContentProfile.ContentType) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            types.forEachIndexed { index, type ->
                TypeTabChip(
                    label = type.toString(),
                    selected = type == currentType,
                    onClick = { onTypeSelected(type) },
                )
                if (index < types.lastIndex) {
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Crossfade(
            targetState = currentType,
            animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
            label = "componentsTypeDescription",
        ) { type ->
            Text(
                text = stringResource(descriptionResFor(type)),
                color = TextPrimary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
            )
        }
    }
}

private fun descriptionResFor(type: ContentProfile.ContentType): Int =
    when (type) {
        ContentProfile.ContentType.CONTENT_TYPE_WINE -> R.string.settings_content_desc_wine
        ContentProfile.ContentType.CONTENT_TYPE_PROTON -> R.string.settings_content_desc_proton
        ContentProfile.ContentType.CONTENT_TYPE_DXVK -> R.string.settings_content_desc_dxvk
        ContentProfile.ContentType.CONTENT_TYPE_VKD3D -> R.string.settings_content_desc_vkd3d
        ContentProfile.ContentType.CONTENT_TYPE_BOX64 -> R.string.settings_content_desc_box64
        ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64 -> R.string.settings_content_desc_wowbox64
        ContentProfile.ContentType.CONTENT_TYPE_FEXCORE -> R.string.settings_content_desc_fexcore
        ContentProfile.ContentType.CONTENT_TYPE_D7VK -> R.string.settings_content_desc_d7vk
    }

private val LINUX_CONTENT_TYPES = listOf(ContentProfile.ContentType.CONTENT_TYPE_PROTON)

@Composable
private fun TypeTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) Accent.copy(alpha = 0.18f) else SurfaceDark
    val borderColor = if (selected) Accent.copy(alpha = 0.45f) else CardBorder
    val textColor = if (selected) Accent else TextSecondary
    Box(
        modifier =
            Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(background)
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .paneNavItem(
                    cornerRadius = 16.dp,
                    onActivate = onClick,
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                )
                .padding(horizontal = 14.dp),
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

// Component item card

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComponentItemCard(
    item: ComponentItem,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
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
                    .padding(horizontal = 14.dp, vertical = 11.dp),
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
                    imageVector = iconFor(item.type),
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.verName,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 5000,
                        repeatDelayMillis = 5000,
                        velocity = 25.dp,
                        spacing = MarqueeSpacing(40.dp),
                    ),
                )
                val sizeLabel = formatSizeLabel(item)
                if (sizeLabel != null) {
                    Text(
                        text = sizeLabel,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // The trailing action (Download / Delete / placeholder) drives the
            // height; the badges fillMaxHeight() so they always match it exactly.
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.isOfficial) {
                    OfficialBadge(Modifier.fillMaxHeight())
                    Spacer(Modifier.width(8.dp))
                }
                if (isOnlineCapable(item)) {
                    OnlineCapableBadge(Modifier.fillMaxHeight())
                    Spacer(Modifier.width(8.dp))
                }
                if (item.isInstalled) {
                    IconTapButton(
                        icon = Icons.Outlined.Delete,
                        tint = DangerRed,
                        onClick = onRemove,
                    )
                } else if (item.hasRemote) {
                    SmallPillButton(
                        label = stringResource(R.string.common_ui_download),
                        icon = Icons.Outlined.Download,
                        tint = Accent,
                        compact = true,
                        onClick = onDownload,
                    )
                } else {
                    // Locally extracted profile with no remote URL — non-interactive placeholder.
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        tint = TextSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinuxComponentItemCard(
    item: LinuxComponentItem,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
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
                    .padding(horizontal = 14.dp, vertical = 11.dp),
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
                    imageVector = iconFor(item.type),
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.verName,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 5000,
                        repeatDelayMillis = 5000,
                        velocity = 25.dp,
                        spacing = MarqueeSpacing(40.dp),
                    ),
                )
                val sizeLabel = formatSizeLabel(item)
                if (sizeLabel != null) {
                    Text(
                        text = sizeLabel,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.isOfficial) {
                    OfficialBadge(Modifier.fillMaxHeight())
                    Spacer(Modifier.width(8.dp))
                }
                when {
                    item.isWorking -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Accent,
                            progress = { if (item.progress.isFinite()) item.progress.coerceIn(0f, 1f) else 0f },
                        )
                        // These builds are several hundred megabytes; without this a mistaken tap
                        // can only be stopped by killing the app.
                        Spacer(Modifier.width(8.dp))
                        SmallPillButton(
                            label = stringResource(R.string.common_ui_cancel),
                            icon = null,
                            tint = DangerRed,
                            compact = true,
                            onClick = onCancel,
                        )
                    }
                    item.isInstalled -> {
                        IconTapButton(
                            icon = Icons.Outlined.Delete,
                            tint = DangerRed,
                            onClick = onRemove,
                        )
                    }
                    item.hasRemote -> {
                        SmallPillButton(
                            label = stringResource(R.string.common_ui_download),
                            icon = Icons.Outlined.Download,
                            tint = Accent,
                            compact = true,
                            onClick = onDownload,
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Outlined.CloudDownload,
                            contentDescription = null,
                            tint = TextSecondary.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
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
                .background(tint.copy(alpha = 0.14f))
                .border(1.dp, tint.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
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

private const val ONLINE_CAPABLE_MARKER = "online"

// Shared with the Contents list, which orders online-capable layers first. Only a layer that
// names itself online qualifies; a Steam-enabled build is not automatically online-capable.
internal fun isOnlineCapableName(vararg fields: String?): Boolean =
    fields.any { it != null && it.contains(ONLINE_CAPABLE_MARKER, ignoreCase = true) }

private fun isOnlineCapable(item: ComponentItem): Boolean =
    isOnlineCapableName(item.verName, item.key)

// Badge marking first-party "WinNative" builds. A perfect square (width follows
// the filled height) in WinNative blue, carrying only the WinNative logo for
// "WN" branding. Pass Modifier.fillMaxHeight() to match the row's action height.
@Composable
private fun OfficialBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Accent.copy(alpha = 0.14f))
            .border(1.dp, Accent.copy(alpha = 0.30f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_winnative_badge),
            contentDescription = "Official WinNative build",
            modifier = Modifier.fillMaxSize(0.8f),
        )
    }
}

@Composable
private fun OnlineCapableBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SuccessGreen.copy(alpha = 0.14f))
            .border(1.dp, SuccessGreen.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_content_badge_online),
            color = SuccessGreen,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SmallPillButton(
    label: String,
    icon: ImageVector?,
    tint: Color,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val horizontalPadding = if (compact) 8.dp else 10.dp
    val verticalPadding = if (compact) 4.dp else 6.dp
    val iconSize = if (compact) 11.dp else 12.dp
    val fontSize = if (compact) 10.sp else 11.sp
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.14f))
                .border(1.dp, tint.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = onClick,
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                )
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = label,
            color = tint,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            // Never let a squeezed row break the label across lines mid-word;
            // clip it instead so the pill keeps a single-line height.
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Empty state

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
                text = stringResource(R.string.common_ui_no_items_to_display),
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_content_empty_subtitle),
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

// Confirm dialog

@Composable
private fun DownloadProgressDialog(progress: ComponentsDownloadProgress) {
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
            message = progress.message,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.88f),
            icon = Icons.Outlined.Download,
            accentColor = Accent,
            progress = if (progress.indeterminate) Float.NaN else progress.progress,
        )
    }
}

// Helpers

@Composable
private fun formatSizeLabel(item: ComponentItem): String? {
    if (item.isInstalled) {
        val bytes = item.sizeBytes ?: return "${stringResource(R.string.common_ui_size)}: --"
        if (bytes <= 0L) return null
        return "${stringResource(R.string.common_ui_size)}: ${formatBytes(bytes)}"
    }
    if (!item.hasRemote) return null
    val bytes = item.sizeBytes ?: return "${stringResource(R.string.common_ui_size)}: --"
    if (bytes <= 0L) return null
    return "${stringResource(R.string.common_ui_size)}: ${formatBytes(bytes)}"
}

@Composable
private fun formatSizeLabel(item: LinuxComponentItem): String? {
    val bytes = item.sizeBytes ?: return "${stringResource(R.string.common_ui_size)}: --"
    if (bytes <= 0L) return null
    return "${stringResource(R.string.common_ui_size)}: ${formatBytes(bytes)}"
}

private fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${value.toInt()} ${units[unitIndex]}"
    } else {
        String.format(java.util.Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

private fun iconFor(type: ContentProfile.ContentType): ImageVector =
    when (type) {
        ContentProfile.ContentType.CONTENT_TYPE_WINE,
        ContentProfile.ContentType.CONTENT_TYPE_PROTON,
        -> Icons.Outlined.Science

        ContentProfile.ContentType.CONTENT_TYPE_DXVK,
        ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
        ContentProfile.ContentType.CONTENT_TYPE_D7VK,
        -> Icons.Outlined.DeveloperBoard

        ContentProfile.ContentType.CONTENT_TYPE_BOX64,
        ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64,
        ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
        -> Icons.Outlined.Memory
    }
