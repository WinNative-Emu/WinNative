package com.winlator.cmod.app.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.winlator.cmod.R
import com.winlator.cmod.shared.io.StorageUtils
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem
import org.json.JSONArray

/** A single Steam Workshop / UGC item surfaced in the Workshop browser. */
internal data class StoreWorkshopItem(
    val publishedFileId: Long,
    val title: String,
    val author: String,
    val previewImageUrl: String?,
    val fileSizeBytes: Long,
    val manifestId: Long = 0L,
    val timeUpdated: Long = 0L,
    val isInstalled: Boolean = false,
)

/** Loading lifecycle for the Workshop browser. */
internal enum class WorkshopLoadState { LOADING, READY, ERROR }

// Palette — mirrors the per-game Settings dialog so the window feels native.
private val WsBg = Color(0xFF12121B)
private val WsBorder = Color(0xFF2A2A3A)
private val WsInputBg = Color(0xFF171722)
private val WsAccent = Color(0xFF1A9FFF)
private val WsAccentGlow = Color(0xFF58A6FF)
private val WsTextPrimary = Color(0xFFF0F4FF)
private val WsTextSecondary = Color(0xFF93A6BC)
private val WsTextDim = Color(0xFF6E7681)
private val WsInstalledTitle = Color(0xFFB7F8CE)
private val WsDanger = Color(0xFFFF6B6B)
private val WsScrim = Color(0xFF000000)

/**
 * The Steam Workshop browser — a Settings-shaped modal window with a search
 * field in the header and a scrollable list of subscribed Workshop items the
 * user can install into the game's `steam_settings/mods` directory.
 *
 * Stateless: all data and callbacks are hoisted to the [WorkshopDialog] wrapper.
 */
@Composable
internal fun StoreWorkshopScreen(
    gameTitle: String,
    loadState: WorkshopLoadState,
    errorMessage: String?,
    items: List<StoreWorkshopItem>,
    query: String,
    busyIds: Set<Long>,
    onQueryChange: (String) -> Unit,
    onInstall: (Long) -> Unit,
    onUninstall: (Long) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val registry = remember { PaneNavRegistry() }
    CompositionLocalProvider(LocalPaneNav provides registry) {
    DialogPaneNav(registry, onDismiss = onClose)
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                // Dim the game-detail screen behind so the modal reads as foreground.
                .background(WsScrim.copy(alpha = 0.6f))
                .windowInsetsPadding(WindowInsets.navigationBars),
        contentAlignment = Alignment.Center,
    ) {
        val dialogWidth = (maxWidth - 32.dp).coerceAtMost(560.dp)
        val dialogHeight = (maxHeight - 48.dp).coerceIn(360.dp, 640.dp)
        Surface(
            modifier =
                Modifier
                    .widthIn(min = minOf(320.dp, dialogWidth), max = dialogWidth)
                    .fillMaxWidth()
                    .height(dialogHeight),
            shape = RoundedCornerShape(14.dp),
            color = WsBg,
            border = BorderStroke(1.dp, WsBorder),
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                WorkshopHeader(
                    gameTitle = gameTitle,
                    itemCount = if (loadState == WorkshopLoadState.READY) items.size else null,
                    onClose = onClose,
                )
                HorizontalDivider(color = WsBorder, thickness = 0.5.dp)
                WorkshopSearchBar(
                    query = query,
                    enabled = loadState == WorkshopLoadState.READY,
                    onQueryChange = onQueryChange,
                )
                HorizontalDivider(color = WsBorder, thickness = 0.5.dp)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (loadState) {
                        WorkshopLoadState.LOADING ->
                            WorkshopStatus(
                                icon = null,
                                title = stringResource(R.string.workshop_loading_items),
                                subtitle = stringResource(R.string.workshop_loading_subtitle),
                            )
                        WorkshopLoadState.ERROR ->
                            WorkshopStatus(
                                icon = Icons.Outlined.Refresh,
                                title = stringResource(R.string.workshop_error_title),
                                subtitle = errorMessage ?: stringResource(R.string.workshop_error_subtitle),
                                actionLabel = stringResource(R.string.session_drawer_retry),
                                onAction = onRetry,
                            )
                        WorkshopLoadState.READY ->
                            if (items.isEmpty()) {
                                WorkshopStatus(
                                    icon = if (query.isBlank()) Icons.Outlined.Inventory2 else Icons.Outlined.SearchOff,
                                    title =
                                        if (query.isBlank()) {
                                            stringResource(R.string.workshop_empty_title)
                                        } else {
                                            stringResource(R.string.workshop_search_empty_title, query)
                                        },
                                    subtitle =
                                        if (query.isBlank()) {
                                            stringResource(R.string.workshop_empty_subtitle)
                                        } else {
                                            stringResource(R.string.workshop_search_empty_subtitle)
                                        },
                                )
                            } else {
                                WorkshopList(
                                    items = items,
                                    busyIds = busyIds,
                                    onInstall = onInstall,
                                    onUninstall = onUninstall,
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
private fun WorkshopHeader(
    gameTitle: String,
    itemCount: Int?,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(WsAccent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Construction,
                contentDescription = null,
                tint = WsAccentGlow,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                stringResource(R.string.workshop_title),
                color = WsTextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp,
            )
            Text(
                gameTitle,
                style = MaterialTheme.typography.titleSmall,
                color = WsTextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (itemCount != null) {
            Surface(
                modifier =
                    Modifier.semantics {
                        contentDescription = "$itemCount Workshop items"
                    },
                color = WsAccent.copy(alpha = 0.14f),
                shape = RoundedCornerShape(7.dp),
            ) {
                Text(
                    itemCount.toString(),
                    color = WsAccentGlow,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.size(36.dp).paneNavItem(onActivate = onClose)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.common_ui_close),
                tint = WsTextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun WorkshopSearchBar(
    query: String,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .paneNavItem(cornerRadius = 9.dp, onActivate = { runCatching { fieldFocus.requestFocus() } })
                .clip(RoundedCornerShape(9.dp))
                .background(WsInputBg)
                .border(1.dp, WsBorder, RoundedCornerShape(9.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = if (enabled) WsAccent else WsTextDim,
            modifier = Modifier.size(18.dp),
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    stringResource(R.string.workshop_search_items),
                    color = WsTextDim,
                    fontSize = 13.sp,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(color = WsTextPrimary, fontSize = 13.sp),
                cursorBrush = Brush.verticalGradient(listOf(WsAccent, WsAccentGlow)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.workshop_clear_search),
                    tint = WsTextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun WorkshopList(
    items: List<StoreWorkshopItem>,
    busyIds: Set<Long>,
    onInstall: (Long) -> Unit,
    onUninstall: (Long) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
    ) {
        items.forEach { item ->
            WorkshopItemRow(
                item = item,
                busy = item.publishedFileId in busyIds,
                onInstall = { onInstall(item.publishedFileId) },
                onUninstall = { onUninstall(item.publishedFileId) },
            )
            HorizontalDivider(
                color = Color.White.copy(alpha = 0.06f),
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
    }
}

@Composable
private fun WorkshopItemRow(
    item: StoreWorkshopItem,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            Modifier
                .size(width = 66.dp, height = 42.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(WsInputBg)
                .border(1.dp, WsBorder, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (!item.previewImageUrl.isNullOrBlank()) {
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(item.previewImageUrl)
                        .crossfade(120)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Outlined.Construction,
                    contentDescription = null,
                    tint = WsTextDim,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.title,
                color = if (item.isInstalled) WsInstalledTitle else WsTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val itemFallback = stringResource(R.string.workshop_item_fallback)
            Text(
                buildString {
                    if (item.author.isNotBlank()) append(item.author)
                    if (item.fileSizeBytes > 0L) {
                        if (isNotEmpty()) append("  ·  ")
                        append(StorageUtils.formatBinarySize(item.fileSizeBytes))
                    }
                    if (isEmpty()) append(itemFallback)
                },
                color = WsTextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        WorkshopRowAction(
            isInstalled = item.isInstalled,
            busy = busy,
            onInstall = onInstall,
            onUninstall = onUninstall,
        )
    }
}

@Composable
private fun WorkshopRowAction(
    isInstalled: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    when {
        busy ->
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = WsAccentGlow,
                strokeWidth = 2.dp,
            )
        isInstalled ->
            WorkshopActionPill(
                icon = Icons.Outlined.Delete,
                label = stringResource(R.string.common_ui_uninstall),
                tint = WsDanger,
                onClick = onUninstall,
            )
        else ->
            WorkshopActionPill(
                icon = Icons.Outlined.Download,
                label = stringResource(R.string.common_ui_install),
                tint = WsAccentGlow,
                onClick = onInstall,
            )
    }
}

/** Compact pill action used for a Workshop row's Install / Uninstall button. */
@Composable
private fun WorkshopActionPill(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    // Outer Box keeps the touch target >= 44dp tall while the pill stays compact.
    Box(
        modifier =
            Modifier
                .heightIn(min = 44.dp)
                .paneNavItem(cornerRadius = 8.dp, onActivate = onClick, tapToSelect = true)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = tint.copy(alpha = 0.16f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, tint.copy(alpha = 0.4f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    label,
                    color = tint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Parse the JSON array returned by `WnSteamSession.getSubscribedWorkshopItems`
 * (objects keyed publishedFileId / title / previewUrl / fileSizeBytes / ) into
 * the browser's [StoreWorkshopItem] model. [installedIds] marks which items
 * already have content staged on disk. Returns an empty list on malformed JSON.
 */
internal fun parseWorkshopItemsJson(
    json: String,
    installedIds: Set<Long> = emptySet(),
): List<StoreWorkshopItem> {
    val arr =
        try {
            JSONArray(json.trim())
        } catch (e: Exception) {
            return emptyList()
        }
    val out = ArrayList<StoreWorkshopItem>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optLong("publishedFileId", 0L)
        if (id == 0L) continue
        out.add(
            StoreWorkshopItem(
                publishedFileId = id,
                title = o.optString("title").ifBlank { id.toString() },
                author = "",
                previewImageUrl = o.optString("previewUrl").takeIf { it.isNotBlank() },
                fileSizeBytes = o.optLong("fileSizeBytes", 0L),
                manifestId = o.optLong("hcontentFile", 0L),
                timeUpdated = o.optLong("timeUpdated", 0L),
                isInstalled = id in installedIds,
            ),
        )
    }
    return out
}

@Composable
private fun WorkshopStatus(
    icon: ImageVector?,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = WsTextDim,
                modifier = Modifier.size(44.dp),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(34.dp),
                color = WsAccent,
                strokeWidth = 3.dp,
            )
        }
        Text(
            title,
            color = WsTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            subtitle,
            color = WsTextSecondary,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(2.dp))
            Surface(
                modifier = Modifier.paneNavItem(onActivate = onAction, tapToSelect = true).clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction),
                color = WsAccent.copy(alpha = 0.16f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, WsAccentGlow.copy(alpha = 0.4f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = null,
                        tint = WsAccentGlow,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        actionLabel,
                        color = WsAccentGlow,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
