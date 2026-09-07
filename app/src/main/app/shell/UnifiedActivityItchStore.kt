package com.winlator.cmod.app.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.itch.data.ItchBrowseFilter
import com.winlator.cmod.feature.stores.itch.data.ItchFacet
import com.winlator.cmod.feature.stores.itch.data.ItchGame
import com.winlator.cmod.feature.stores.itch.data.ItchGameDetails
import com.winlator.cmod.feature.stores.itch.data.ItchUpdateInfo
import com.winlator.cmod.feature.stores.itch.data.ItchUpload
import com.winlator.cmod.feature.stores.itch.service.ItchCatalog
import com.winlator.cmod.feature.stores.itch.service.ItchConstants
import com.winlator.cmod.feature.stores.itch.service.ItchService
import com.winlator.cmod.runtime.input.ControllerHelper
import com.winlator.cmod.shared.ui.FourByTwoGridView
import com.winlator.cmod.shared.ui.JoystickGridScroll
import com.winlator.cmod.shared.ui.widget.chasingBorder
import com.winlator.cmod.shared.ui.toast.WinToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ItchRed = Color(0xFFFA5C5C)
private val DrawerHotZoneClearance = 44.dp
private val DrawerHotZoneStart = 28.dp
private const val SEARCH_DEBOUNCE_MS = 450L

@Composable
internal fun UnifiedActivity.ItchStoreTab(
    searchQuery: String,
    signInSignal: Int,
    onSignInClick: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? UnifiedActivity
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    val games = remember { mutableStateListOf<ItchGame>() }
    var facet by remember { mutableStateOf(ItchFacet.ALL) }
    var windowsOnly by remember { mutableStateOf(true) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var exhausted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<ItchGame?>(null) }
    var installedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var signedIn by remember { mutableStateOf(false) }
    var userName by remember { mutableStateOf("") }
    var ownedGames by remember { mutableStateOf<List<ItchGame>>(emptyList()) }

    val query = searchQuery.trim()
    val isSearching = query.length >= 2
    val ownedIds = remember(ownedGames) { ownedGames.map { it.id }.toSet() }
    val facets = remember(signedIn) { ItchFacet.visible(signedIn) }

    LaunchedEffect(signInSignal) {
        signedIn = withContext(Dispatchers.IO) { ItchService.isLoggedIn(context) }
        userName = ItchService.userName(context)
        if (signedIn && userName.isBlank()) {
            userName =
                try {
                    ItchService.refreshProfile(context)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    ""
                }
        }
    }

    LaunchedEffect(signedIn, signInSignal, reloadKey) {
        ownedGames =
            if (signedIn) {
                try {
                    ItchService.owned(context, 1)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        if (!signedIn && facet.kind == ItchFacet.Kind.OWNED) facet = ItchFacet.ALL
    }

    LaunchedEffect(reloadKey, activity?.libraryRefreshSignal) {
        installedIds = withContext(Dispatchers.IO) { ItchService.installedIds(context) }
    }

    val visibleIn: (List<ItchGame>, Boolean) -> List<ItchGame> = { list, platformsListed ->
        list.filter { game ->
            val windowsOk = !windowsOnly || game.hasWindowsBuild || (!platformsListed && game.platforms.isEmpty())
            windowsOk && (game.isFree || game.id in ownedIds)
        }
    }

    LaunchedEffect(facet, windowsOnly, query, reloadKey, ownedGames) {
        if (isSearching) delay(SEARCH_DEBOUNCE_MS)
        loading = true
        error = null
        exhausted = false
        page = 1
        try {
            val filter = ItchBrowseFilter(facet, windowsOnly)
            val list =
                when {
                    isSearching -> visibleIn(ItchService.search(context, query), false)
                    filter.isOwned -> visibleIn(ownedGames, false)
                    else -> {
                        val head = if (filter.isAll) visibleIn(ownedGames, false) else emptyList()
                        val headIds = head.map { it.id }.toSet()
                        head + visibleIn(ItchService.browse(context, filter, 1), true).filterNot { it.id in headIds }
                    }
                }
            games.clear()
            games.addAll(list)
            exhausted = isSearching || filter.isOwned
            loading = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            games.clear()
            error = failure.message ?: failure::class.java.simpleName
            loading = false
        }
    }

    LaunchedEffect(facet, windowsOnly, isSearching, reloadKey) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                if (!exhausted && !loading && !loadingMore && !isSearching && games.isNotEmpty() &&
                    lastVisible >= games.size - 6
                ) {
                    loadingMore = true
                    val next = page + 1
                    try {
                        val list = ItchService.browse(context, ItchBrowseFilter(facet, windowsOnly), next)
                        val known = games.map { it.id }.toSet()
                        val fresh = visibleIn(list, true).filterNot { it.id in known }
                        games.addAll(fresh)
                        page = next
                        if (list.size < ItchConstants.PAGE_SIZE) exhausted = true
                    } catch (cancelled: CancellationException) {
                        loadingMore = false
                        throw cancelled
                    } catch (_: Throwable) {
                        exhausted = true
                    }
                    loadingMore = false
                }
            }
    }

    LaunchedEffect(games.size) {
        activity?.storeItemCount = games.size
        val lastIndex = (games.size - 1).coerceAtLeast(0)
        if (activity != null && games.isNotEmpty() && activity.storeFocusIndex.value > lastIndex) {
            activity.storeFocusIndex.value = lastIndex
        }
    }

    DisposableEffect(games.size) {
        val clickCallback: (Int) -> Unit = { index -> games.getOrNull(index)?.let { selected = it } }
        activity?.storeItemClickCallback = clickCallback
        activity?.storeGridState = gridState
        onDispose {
            if (activity?.storeItemClickCallback === clickCallback) {
                activity?.storeItemClickCallback = null
                activity?.storeGridState = null
            }
        }
    }

    val isControllerActive = ControllerHelper.isControllerConnected()
    val accountRowVisible by (activity?.storeHeaderVisible ?: kotlinx.coroutines.flow.MutableStateFlow(true)).collectAsState()

    Column(Modifier.fillMaxSize()) {
        ItchHeader(
            facets = facets,
            facet = facet,
            windowsOnly = windowsOnly,
            signedIn = signedIn,
            userName = userName,
            searching = isSearching,
            accountRowVisible = accountRowVisible,
            onFacetChange = { facet = it },
            onWindowsOnlyChange = { windowsOnly = it },
            onSignIn = onSignInClick,
            onSignOut = {
                scope.launch {
                    ItchService.signOut(context)
                    signedIn = false
                    userName = ""
                    onSignedOut()
                }
            },
        )

        when {
            loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ItchRed)
                }

            error != null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Text(
                            stringResource(R.string.itch_store_load_failed, error.orEmpty()),
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        CompactActionButton(
                            icon = Icons.Outlined.Refresh,
                            label = stringResource(R.string.itch_store_retry),
                            tint = ItchRed,
                            modifier = Modifier.width(180.dp),
                        ) { reloadKey++ }
                    }
                }

            games.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (facet.kind == ItchFacet.Kind.OWNED) R.string.itch_store_no_owned else R.string.itch_store_no_results,
                        ),
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }

            else -> {
                val focusIndex by (activity?.storeFocusIndex ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsState()
                val focusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
                LaunchedEffect(focusIndex) {
                    if (!isSearching && isControllerActive && focusIndex in games.indices) {
                        gridState.animateScrollToItem(focusIndex)
                        runCatching { focusRequesters[focusIndex]?.requestFocus() }
                    }
                }
                JoystickGridScroll(gridState, activity?.rightStickScrollState)
                FourByTwoGridView(
                    items = games.toList(),
                    modifier = Modifier.tabScreenPadding(top = TabGridTopPadding),
                    gridState = gridState,
                    keyOf = { it.id },
                ) { game, index, rowHeight ->
                    val requester = remember(index) { focusRequesters.getOrPut(index) { FocusRequester() } }
                    Box(Modifier.height(rowHeight).focusRequester(requester)) {
                        ItchStoreCapsule(
                            game = game,
                            isInstalled = game.id in installedIds,
                            isFocusedOverride = index == focusIndex,
                            isControllerActive = isControllerActive,
                        ) { selected = game }
                    }
                }
            }
        }
    }

    selected?.let { game ->
        ItchGameDialog(
            game = game,
            installed = game.id in installedIds,
            onDismiss = { selected = null },
            onInstalledChanged = { reloadKey++ },
        )
    }
}

@Composable
private fun UnifiedActivity.ItchHeader(
    facets: List<ItchFacet>,
    facet: ItchFacet,
    windowsOnly: Boolean,
    signedIn: Boolean,
    userName: String,
    searching: Boolean,
    accountRowVisible: Boolean,
    onFacetChange: (ItchFacet) -> Unit,
    onWindowsOnlyChange: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().tabScreenPadding(top = 6.dp, bottom = 2.dp)) {
        AnimatedVisibility(
            visible = accountRowVisible,
            enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(220, easing = FastOutSlowInEasing)) + fadeOut(tween(160)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(end = DrawerHotZoneClearance),
            ) {
                Text(
                    text =
                        if (signedIn && userName.isNotBlank()) {
                            stringResource(R.string.itch_store_signed_in_as, userName)
                        } else if (signedIn) {
                            stringResource(R.string.itch_store_signed_in)
                        } else {
                            stringResource(R.string.itch_store_signed_out_hint)
                        },
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, ItchRed.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                            .clickable { if (signedIn) onSignOut() else onSignIn() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Icon(
                        if (signedIn) Icons.AutoMirrored.Outlined.Logout else Icons.AutoMirrored.Outlined.Login,
                        contentDescription = null,
                        tint = ItchRed,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        stringResource(if (signedIn) R.string.itch_store_sign_out else R.string.itch_store_sign_in_short),
                        color = ItchRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.itch_store_windows_only),
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(4.dp))
                Switch(
                    checked = windowsOnly,
                    onCheckedChange = onWindowsOnlyChange,
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ItchRed,
                            checkedBorderColor = ItchRed,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = CardDark,
                            uncheckedBorderColor = CardBorder,
                        ),
                )
            }
        }

        if (!searching) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = DrawerHotZoneStart, end = DrawerHotZoneClearance)
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                facets.forEach { entry ->
                    val active = entry == facet
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (active) ItchRed.copy(alpha = 0.18f) else CardDark, RoundedCornerShape(14.dp))
                                .border(
                                    1.dp,
                                    if (active) ItchRed.copy(alpha = 0.7f) else CardBorder,
                                    RoundedCornerShape(14.dp),
                                ).clickable { onFacetChange(entry) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            stringResource(entry.labelRes),
                            color = if (active) TextPrimary else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun UnifiedActivity.ItchStoreCapsule(
    game: ItchGame,
    isInstalled: Boolean,
    listMode: Boolean = false,
    isFocusedOverride: Boolean = false,
    isControllerActive: Boolean = false,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var isFocused by remember { mutableStateOf(false) }
    val clickInteraction = remember { MutableInteractionSource() }
    val isPressed by clickInteraction.collectIsPressedAsState()
    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.7f else 0f,
        animationSpec = if (isPressed) tween(100) else tween(400),
        label = "itchCapsuleGlow",
    )
    val effectiveFocus = isControllerActive && (isFocusedOverride || isFocused)
    val borderColor = if (isControllerActive) CardBorder else Color.Transparent

    if (listMode) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                    .chasingBorder(isFocused = effectiveFocus, paused = chasingBordersPaused.value, cornerRadius = 14.dp)
                    .background(CardDark, RoundedCornerShape(14.dp))
                    .onFocusChanged { isFocused = it.isFocused }
                    .focusable()
                    .then(
                        if (glowAlpha > 0f) {
                            Modifier.drawWithContent {
                                drawContent()
                                drawRoundRect(color = AccentGlow, alpha = glowAlpha * 0.25f, cornerRadius = CornerRadius(14.dp.toPx()))
                            }
                        } else {
                            Modifier
                        },
                    ).clickable(interactionSource = clickInteraction, indication = null, onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .height(52.dp)
                    .aspectRatio(462f / 174f)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(game.coverUrl).crossfade(300).build(),
                    contentDescription = game.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                if (isInstalled) {
                    StoreInstalledBadge(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                        compact = true,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(
                game.title,
                modifier =
                    Modifier
                        .weight(1f)
                        .then(if (effectiveFocus) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .fillMaxSize()
                    .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                    .chasingBorder(isFocused = effectiveFocus, paused = chasingBordersPaused.value, cornerRadius = 16.dp)
                    .background(CardDark, RoundedCornerShape(16.dp))
                    .onFocusChanged { isFocused = it.isFocused }
                    .focusable()
                    .then(
                        if (glowAlpha > 0f) {
                            Modifier.drawWithContent {
                                drawContent()
                                drawRoundRect(color = AccentGlow, alpha = glowAlpha * 0.25f, cornerRadius = CornerRadius(16.dp.toPx()))
                            }
                        } else {
                            Modifier
                        },
                    ).clickable(interactionSource = clickInteraction, indication = null, onClick = onClick),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(game.coverUrl).crossfade(300).build(),
                    contentDescription = game.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                if (isInstalled) {
                    StoreInstalledBadge(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        attachedCorner = true,
                    )
                }
            }

            Text(
                game.title,
                modifier =
                    Modifier
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .then(if (effectiveFocus) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun UnifiedActivity.ItchGameDialog(
    game: ItchGame,
    installed: Boolean,
    onDismiss: () -> Unit,
    onInstalledChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var details by remember(game.id) { mutableStateOf<ItchGameDetails?>(null) }
    var uploads by remember(game.id) { mutableStateOf<List<ItchUpload>?>(null) }
    var uploadsError by remember(game.id) { mutableStateOf<String?>(null) }
    var selectedUploadId by remember(game.id) { mutableStateOf<Long?>(null) }
    var busy by remember(game.id) { mutableStateOf(false) }
    var checkingUpdate by remember(game.id) { mutableStateOf(false) }
    var updateInfo by remember(game.id) { mutableStateOf<ItchUpdateInfo?>(null) }
    var updateStatus by remember(game.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(game.id) {
        try {
            details = ItchService.details(context, game)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            details = null
        }
        try {
            val list = ItchService.uploads(context, game)
            uploads = list
            selectedUploadId = ItchCatalog.pickWindowsUpload(list)?.id
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            uploadsError = failure.message ?: failure::class.java.simpleName
        }
    }

    val installPath = remember(game.id) { ItchService.installPath(context, game) }
    val selectedUpload = uploads?.firstOrNull { it.id == selectedUploadId }
    val availableBytes =
        remember(installPath) {
            try {
                com.winlator.cmod.shared.io.StorageUtils
                    .getAvailableSpace(java.io.File(installPath).parent ?: installPath)
            } catch (_: Exception) {
                0L
            }
        }
    val updateCheckFailed = stringResource(R.string.store_game_update_check_failed)
    val updateUpToDate = stringResource(R.string.itch_store_update_none)
    val updateAvailableTemplate = stringResource(R.string.itch_store_update_available)
    val updateAvailableText: (String) -> String = { label ->
        if (label.isBlank()) updateAvailableTemplate.format("") else updateAvailableTemplate.format(label)
    }
    val controllerLabel = stringResource(R.string.itch_store_controller)
    val controllerNotListed = stringResource(R.string.itch_store_controller_not_listed)
    val controllerUnsupported = stringResource(R.string.itch_store_controller_unsupported)
    val tagsLabel = stringResource(R.string.itch_store_tags)
    val detailChips =
        remember(details, controllerLabel, tagsLabel) {
            val current = details
            buildList {
                add(
                    StoreDetailChip(
                        icon = Icons.Outlined.SportsEsports,
                        label = controllerLabel,
                        value =
                            when {
                                current == null || !current.inputsKnown -> controllerNotListed
                                current.hasControllerSupport -> current.controllerInputs.joinToString(", ") { it.label }
                                else -> controllerUnsupported
                            },
                        highlight = current?.hasControllerSupport == true,
                    ),
                )
                current?.tags?.take(4)?.takeIf { it.isNotEmpty() }?.let { tags ->
                    add(StoreDetailChip(Icons.Outlined.Sell, tagsLabel, tags.joinToString(", ")))
                }
            }
        }
    val uploadOptions =
        remember(uploads) {
            uploads.orEmpty().map { upload ->
                StoreBranchOption(
                    id = upload.id.toString(),
                    label =
                        listOfNotNull(
                            upload.fileName,
                            upload.sizeLabel.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                )
            }
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RectangleShape,
            color = Color.Black,
        ) {
            StoreGameDetailScreen(
                title = game.title,
                subtitle =
                    listOfNotNull(
                        game.author.takeIf { it.isNotBlank() },
                        game.genre.takeIf { it.isNotBlank() },
                    ).joinToString(" • "),
                sourceLabel = stringResource(R.string.itch_store_title),
                heroImageUrl = details?.heroImageUrl?.takeIf { it.isNotBlank() } ?: game.coverUrl,
                isLoading = uploads == null && uploadsError == null,
                isInstalled = installed,
                installPathDisplay = installPath,
                downloadSize = selectedUpload?.sizeBytes ?: 0L,
                installSize = selectedUpload?.sizeBytes ?: 0L,
                availableBytes = availableBytes,
                isInstallEnabled = selectedUpload != null && !busy,
                customPathLabel = "",
                showCustomPath = false,
                detailChips = detailChips,
                showCloudSync = false,
                showUninstall = installed,
                uninstallAsPrimaryAction = true,
                showUpdateCheck = installed,
                isCheckingForUpdate = checkingUpdate,
                isUpdateAvailable = updateInfo?.available == true,
                updateDownloadSize = updateInfo?.upload?.sizeBytes ?: 0L,
                updateStatusText = updateStatus,
                isUpdateActionEnabled = !busy,
                showVerifyFiles = false,
                branches = uploadOptions,
                selectedBranchId = selectedUploadId?.toString().orEmpty(),
                isBranchSelectionEnabled = !busy,
                onSelectBranch = { id -> selectedUploadId = id.toLongOrNull() },
                onBack = onDismiss,
                onCheckForUpdate = {
                    checkingUpdate = true
                    updateStatus = null
                    context.runIfOnlineOrToast {
                        scope.launch {
                            updateInfo =
                                try {
                                    ItchService.checkForUpdate(context, game)
                                } catch (cancelled: CancellationException) {
                                    checkingUpdate = false
                                    throw cancelled
                                } catch (_: Throwable) {
                                    null
                                }
                            updateStatus =
                                when {
                                    updateInfo == null -> updateCheckFailed
                                    updateInfo?.available == true -> updateAvailableText(updateInfo?.latestLabel.orEmpty())
                                    else -> updateUpToDate
                                }
                            checkingUpdate = false
                        }
                    }
                },
                onDownloadUpdate = {
                    val upload = updateInfo?.upload ?: return@StoreGameDetailScreen
                    busy = true
                    context.runIfOnlineOrToast {
                        scope.launch {
                            ItchService.download(context, game, upload)
                            WinToast.show(
                                context,
                                getString(R.string.itch_store_download_started, game.title),
                                android.widget.Toast.LENGTH_SHORT,
                            )
                            updateInfo = null
                            updateStatus = null
                            busy = false
                            onDismiss()
                        }
                    }
                },
                onInstall = {
                    val upload = selectedUpload ?: return@StoreGameDetailScreen
                    busy = true
                    context.runIfOnlineOrToast {
                        scope.launch {
                            ItchService.download(context, game, upload)
                            WinToast.show(
                                context,
                                getString(R.string.itch_store_download_started, game.title),
                                android.widget.Toast.LENGTH_SHORT,
                            )
                            busy = false
                            onInstalledChanged()
                            onDismiss()
                        }
                    }
                },
                onUninstall = {
                    scope.launch {
                        withContext(Dispatchers.IO) { ItchService.uninstall(context, game.id) }
                        onInstalledChanged()
                        onDismiss()
                    }
                },
            )
        }
    }

    uploadsError?.let { message ->
        LaunchedEffect(message) {
            WinToast.show(context, message, android.widget.Toast.LENGTH_LONG)
        }
    }
}

