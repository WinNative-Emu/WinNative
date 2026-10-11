package com.winlator.cmod.feature.settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.widget.chasingBorder

// ─── Palette ────────────────────────────────────────────────────────

private val SidebarBgTop = Color(0xFF171E2E)
private val SidebarBgBot = Color(0xFF11161F)
private val SectionLabelClr = Color(0xFF3D4F65)
private val HeaderTextClr = Color(0xFF58708C)
private val TextNormal = Color(0xFF7A8FA8)
private val TextSelected = Color(0xFFF0F4FF)
private val IconMuted = Color(0xFF4A7A8F)
private val SelectedBg = Color(0xFF131D2F)
private val DividerColor = Color(0xFF212C3F)

private val AccentSelected = Color(0xFF4FC3F7)
private val AccentPressed = Color(0xFF8BDEFF)

private val InterFamily = FontFamily(Font(R.font.inter_medium, FontWeight.Medium))

// ─── Navigation model ───────────────────────────────────────────────

enum class NavSection {
    DEVICE,
    ACCOUNTS,
    SYSTEM,
    TOOLS,
    CREDITS,
    HELP,
}

enum class SettingsNavItem(
    val menuId: Int,
    val icon: ImageVector,
    val titleRes: Int,
    val section: NavSection,
) {
    DEVICE_PROFILE(
        R.id.main_menu_device_profile,
        Icons.Outlined.TabletAndroid,
        R.string.device_profile_nav_title,
        NavSection.DEVICE,
    ),
    GOOGLE(R.id.main_menu_google, Icons.Outlined.AccountCircle, R.string.google_cloud_google, NavSection.ACCOUNTS),
    STORES(R.id.main_menu_stores, Icons.Outlined.ShoppingBag, R.string.stores_accounts_title, NavSection.ACCOUNTS),
    CONTAINERS(R.id.main_menu_containers, Icons.Outlined.ViewInAr, R.string.common_ui_containers, NavSection.SYSTEM),
    PRESETS(R.id.main_menu_settings, Icons.Outlined.Tune, R.string.container_presets_title, NavSection.SYSTEM),
    COMPONENTS(R.id.main_menu_contents, Icons.Outlined.Extension, R.string.settings_content_components, NavSection.SYSTEM),
    DRIVERS(R.id.main_menu_adrenotools_gpu_drivers, Icons.Outlined.Memory, R.string.settings_drivers_title, NavSection.SYSTEM),
    INPUT_CONTROLS(R.id.main_menu_input_controls, Icons.Outlined.SportsEsports, R.string.common_ui_input_controls, NavSection.SYSTEM),
    RETRO(R.id.main_menu_retro, Icons.Outlined.VideogameAsset, R.string.settings_retro_title, NavSection.SYSTEM),
    OTHER(R.id.main_menu_other, Icons.Outlined.Widgets, R.string.common_ui_other, NavSection.SYSTEM),
    DEBUG(R.id.main_menu_advanced, Icons.Outlined.BugReport, R.string.settings_debug_title, NavSection.TOOLS),
    CREDITS(R.id.main_menu_credits, Icons.Outlined.Info, R.string.retro_scr_tab_credits, NavSection.CREDITS),
    SUPPORT(R.id.main_menu_support, Icons.Outlined.HelpOutline, R.string.settings_support_title, NavSection.HELP),
    ABOUT(R.id.main_menu_about, Icons.Outlined.Info, R.string.common_ui_about, NavSection.HELP),
    ;

    companion object {
        fun fromMenuId(id: Int): SettingsNavItem? = entries.find { it.menuId == id }
    }
}

private val sectionedNavItems: Map<NavSection, List<SettingsNavItem>> =
    SettingsNavItem.entries.groupBy { it.section }

// ─── Main sidebar composable ────────────────────────────────────────

@Composable
fun SettingsNavSidebar(
    selectedItem: SettingsNavItem,
    railActive: Boolean = true,
    onItemSelected: (SettingsNavItem) -> Unit,
    onBackPressed: () -> Unit,
    bordersPaused: Boolean = false,
    sidebarWidth: Dp = 220.dp,
    showHeader: Boolean = true,
) {
    val layoutDirection = LocalLayoutDirection.current
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val navStartInset = navBarPadding.calculateStartPadding(layoutDirection)
    val navBottomInset = navBarPadding.calculateBottomPadding()

    val listState = rememberLazyListState()
    val selectedLazyIndex =
        remember(selectedItem) {
            var idx = 0
            var found = 0
            NavSection.entries.forEachIndexed { sIdx, section ->
                if (sIdx > 0) idx++
                idx++
                val secItems = sectionedNavItems[section].orEmpty()
                val pos = secItems.indexOf(selectedItem)
                if (pos >= 0) found = idx + pos
                idx += secItems.size
            }
            found
        }
    LaunchedEffect(selectedLazyIndex) {
        val info = listState.layoutInfo
        if (info.visibleItemsInfo.isEmpty()) return@LaunchedEffect
        val visible = info.visibleItemsInfo.firstOrNull { it.index == selectedLazyIndex }
        val fullyVisible =
            visible != null &&
                visible.offset >= info.viewportStartOffset &&
                visible.offset + visible.size <= info.viewportEndOffset
        if (!fullyVisible) runCatching { listState.animateScrollToItem(selectedLazyIndex) }
    }

    Row(
        modifier =
            Modifier
                .fillMaxHeight()
                .padding(start = navStartInset),
    ) {
        Column(
            modifier =
                Modifier
                    .width(sidebarWidth)
                    .fillMaxHeight()
                    .background(Brush.verticalGradient(listOf(SidebarBgTop, SidebarBgBot))),
        ) {
            if (showHeader) {
                SidebarHeader(onBackPressed)
            } else {
                Spacer(Modifier.height(12.dp))
            }

            // Scrollable nav items
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = 8.dp, end = 8.dp, top = 2.dp),
                contentPadding = PaddingValues(bottom = 24.dp + navBottomInset),
            ) {
                NavSection.entries.forEachIndexed { index, section ->
                    if (index > 0) {
                        item(
                            key = "spacer_${section.name}",
                            contentType = "sectionSpacer",
                        ) {
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    item(
                        key = "header_${section.name}",
                        contentType = "sectionHeader",
                    ) {
                        SectionHeader(section.name)
                    }
                    items(
                        items = sectionedNavItems[section].orEmpty(),
                        key = { it.name },
                        contentType = { "navItem" },
                    ) { item ->
                        NavItemRow(
                            item = item,
                            isSelected = item == selectedItem,
                            railActive = railActive,
                            borderPaused = bordersPaused,
                            onClick = { onItemSelected(item) },
                        )
                    }
                }
            }
        }

        // Right-edge divider
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(DividerColor),
        )
    }
}

@Composable
fun SettingsPortraitTopBar(
    title: String,
    onBackPressed: () -> Unit,
    onOpenSections: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(SidebarBgTop, SidebarBgBot)))
                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onBackPressed),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.common_ui_back),
                tint = AccentSelected,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = title,
            color = TextSelected,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = InterFamily,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 6.dp, end = 6.dp),
        )
        Box(
            modifier =
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SelectedBg)
                    .clickable(onClick = onOpenSections)
                    .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Menu,
                    contentDescription = stringResource(R.string.common_ui_settings),
                    tint = AccentSelected,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.common_ui_settings).uppercase(),
                    color = AccentSelected,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFamily,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

// ─── Header ─────────────────────────────────────────────────────────

@Composable
private fun SidebarHeader(onBackPressed: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val arrowTint by animateColorAsState(
        targetValue = if (isPressed) AccentPressed else AccentSelected,
        animationSpec = tween(120),
        label = "sidebarHeaderArrowTint",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onBackPressed,
                ).padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 6.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = stringResource(R.string.common_ui_back),
            tint = arrowTint,
            modifier = Modifier.size(22.dp),
        )

        Text(
            text = stringResource(R.string.common_ui_settings).uppercase(),
            color = HeaderTextClr,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = InterFamily,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

// ─── Section header ─────────────────────────────────────────────────

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        color = SectionLabelClr,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = InterFamily,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
    )
}

// ─── Navigation item row ────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavItemRow(
    item: SettingsNavItem,
    isSelected: Boolean,
    railActive: Boolean = true,
    borderPaused: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(isSelected) {
        if (isSelected) runCatching { bringIntoView.bringIntoView() }
    }

    val iconTint by animateColorAsState(
        targetValue = if (isSelected) AccentSelected else IconMuted,
        animationSpec = tween(280),
        label = "iconTint",
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) TextSelected else TextNormal,
        animationSpec = tween(280),
        label = "textColor",
    )
    val showFill = isSelected && railActive
    val bgAlpha by animateFloatAsState(
        targetValue =
            when {
                showFill -> 1f
                isHovered -> 0.5f
                else -> 0f
            },
        animationSpec = tween(200),
        label = "bgAlpha",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoView)
                .padding(vertical = 2.dp)
                .background(
                    color = SelectedBg.copy(alpha = bgAlpha),
                    shape = RoundedCornerShape(8.dp),
                ).then(
                    if (isSelected) {
                        Modifier.chasingBorder(
                            paused = borderPaused,
                            animationDurationMs = 8200,
                            borderWidth = 1.5.dp,
                        )
                    } else {
                        Modifier
                    },
                ).then(
                    if (!isSelected && isHovered) {
                        Modifier.staticBorder()
                    } else {
                        Modifier
                    },
                ).hoverable(interactionSource)
                .pointerInput(Unit) {
                    detectTapGestures { onClick() }
                }.padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )

        Text(
            text = stringResource(item.titleRes),
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = InterFamily,
            letterSpacing = 0.02.sp,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

// ─── Static hover/focus border ──────────────────────────────────────

private fun Modifier.staticBorder(
    cornerRadius: Dp = 8.dp,
    borderWidth: Dp = 1.5.dp,
    color: Color = Color(0x5000D7F5),
): Modifier =
    this.drawWithContent {
        drawContent()
        val bw = borderWidth.toPx()
        val cr = cornerRadius.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(bw / 2, bw / 2),
            size = Size(size.width - bw, size.height - bw),
            cornerRadius = CornerRadius(cr, cr),
            style = Stroke(width = bw),
        )
    }
