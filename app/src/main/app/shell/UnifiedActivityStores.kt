package com.winlator.cmod.app.shell

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.winlator.cmod.app.config.DeviceProfileSettings
import com.winlator.cmod.BuildConfig
import com.winlator.cmod.R
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.app.service.DownloadService
import com.winlator.cmod.app.service.download.DownloadCoordinator
import com.winlator.cmod.app.update.UpdateService
import com.winlator.cmod.feature.settings.InputControlsFragment
import com.winlator.cmod.feature.settings.SettingsFocusZone
import com.winlator.cmod.feature.settings.SettingsHost
import com.winlator.cmod.feature.settings.SettingsNavBridge
import com.winlator.cmod.feature.settings.SettingsNavItem
import com.winlator.cmod.feature.setup.SetupWizardActivity
import com.winlator.cmod.feature.shortcuts.LibraryShortcutUtils
import com.winlator.cmod.feature.shortcuts.LibraryShortcutArtwork
import com.winlator.cmod.feature.shortcuts.ShortcutBroadcastReceiver
import com.winlator.cmod.feature.shortcuts.ShortcutSettingsComposeDialog
import com.winlator.cmod.feature.shortcuts.ShortcutsFragment
import com.winlator.cmod.feature.stores.common.StoreArtworkCache
import com.winlator.cmod.feature.stores.epic.data.EpicCredentials
import com.winlator.cmod.feature.stores.epic.data.EpicGame
import com.winlator.cmod.feature.stores.epic.data.EpicGameToken
import com.winlator.cmod.feature.stores.epic.service.EpicAuthManager
import com.winlator.cmod.feature.stores.epic.service.EpicCloudSavesManager
import com.winlator.cmod.feature.stores.epic.service.EpicConstants
import com.winlator.cmod.feature.stores.epic.service.EpicDownloadManager
import com.winlator.cmod.feature.stores.epic.service.EpicGameLauncher
import com.winlator.cmod.feature.stores.epic.service.EpicManager
import com.winlator.cmod.feature.stores.epic.service.EpicService
import com.winlator.cmod.feature.stores.epic.service.EpicUpdateInfo
import com.winlator.cmod.feature.stores.epic.ui.auth.EpicOAuthActivity
import com.winlator.cmod.feature.stores.gog.data.GOGDlcInfo
import com.winlator.cmod.feature.stores.gog.data.GOGGame
import com.winlator.cmod.feature.stores.gog.data.LibraryItem
import com.winlator.cmod.feature.stores.gog.service.GOGAuthManager
import com.winlator.cmod.feature.stores.gog.service.GOGConstants
import com.winlator.cmod.feature.stores.gog.service.GOGManifestSizes
import com.winlator.cmod.feature.stores.gog.service.GOGService
import com.winlator.cmod.feature.stores.gog.service.GOGUpdateInfo
import com.winlator.cmod.feature.stores.gog.ui.auth.GOGOAuthActivity
import com.winlator.cmod.feature.stores.steam.SteamLoginActivity
import com.winlator.cmod.feature.stores.steam.data.DepotInfo
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.feature.stores.steam.events.AndroidEvent
import com.winlator.cmod.feature.stores.steam.events.EventDispatcher
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.feature.stores.steam.utils.getAvatarURL
import com.winlator.cmod.feature.sync.CloudSyncHelper
import com.winlator.cmod.feature.sync.google.CloudSyncManager
import com.winlator.cmod.feature.sync.google.GameSaveBackupManager
import com.winlator.cmod.feature.sync.ui.CloudSavesContent
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.display.XServerDisplayActivity
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.input.ControllerHelper
import com.winlator.cmod.runtime.wine.PeIconExtractor
import com.winlator.cmod.shared.android.ActivityResultHost
import com.winlator.cmod.shared.android.AppTerminationHelper
import com.winlator.cmod.shared.android.DirectoryPickerDialog
import com.winlator.cmod.shared.android.FixedFontScaleAppCompatActivity
import com.winlator.cmod.shared.android.RefreshRateUtils
import com.winlator.cmod.shared.io.StorageUtils
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.ui.CarouselView
import com.winlator.cmod.shared.ui.dialog.PopupDialog
import com.winlator.cmod.shared.ui.dialog.PopupTextAction
import androidx.compose.foundation.focusGroup
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.focus.controllerMenuInput
import com.winlator.cmod.shared.ui.focus.controllerTextFieldEscape
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PANE_DIR_ACTIVATE
import com.winlator.cmod.shared.ui.nav.PANE_DIR_DOWN
import com.winlator.cmod.shared.ui.nav.PANE_DIR_LEFT
import com.winlator.cmod.shared.ui.nav.PANE_DIR_RIGHT
import com.winlator.cmod.shared.ui.nav.PANE_DIR_SECONDARY
import com.winlator.cmod.shared.ui.nav.PANE_DIR_UP
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem
import com.winlator.cmod.shared.ui.FourByTwoGridView
import com.winlator.cmod.shared.ui.JoystickGridScroll
import com.winlator.cmod.shared.ui.JoystickListScroll
import com.winlator.cmod.shared.ui.ListView
import com.winlator.cmod.shared.ui.widget.chasingBorder
import com.winlator.cmod.shared.theme.WinNativeTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.Lazy
import com.winlator.cmod.feature.stores.steam.enums.EPersonaState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

// Epic/GOG/Steam store tabs, capsules and manager dialogs, split out of UnifiedActivity.kt (behavior-identical).

@Composable
internal fun UnifiedActivity.CompactActionButton(
    icon: ImageVector,
    label: String,
    tint: Color = TextPrimary,
    bgColor: Color = SurfaceDark,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    fontSize: TextUnit = 13.sp,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
        label = "btnScale",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.18f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "btnGlow",
    )
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        color = bgColor,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = glowAlpha)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                color = tint,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Single game capsule for carousel / grid / list
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun UnifiedActivity.GameCapsule(
    app: SteamApp,
    gogGame: GOGGame? = null,
    epicGame: EpicGame? = null,
    iconRefreshKey: Int = 0,
    artworkCacheRefreshKey: Int = 0,
    isFocusedOverride: Boolean = false,
    isControllerActive: Boolean = false,
    customArtworkPath: String? = null,
    customIconPath: String? = null,
    customListPath: String? = null,
    customCarouselPath: String? = null,
    customHeroPath: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    useLibraryCapsule: Boolean = false,
    listMode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isCustom = app.id < 0
    val isEpic = app.id >= 2000000000
    val defaultClick: () -> Unit = {
        val containerManager =
            com.winlator.cmod.runtime.container
                .ContainerManager(context)
        if (isCustom) {
            launchCustomGame(context, containerManager, app.name)
        } else if (gogGame != null) {
            launchGogGame(context, containerManager, gogGame)
        } else if (isEpic) {
            epicGame?.let { launchEpicGame(context, containerManager, it) }
        } else {
            launchSteamGame(context, containerManager, app)
        }
    }
    // Each view has its own shape, so prefer the slot scraped for it.
    val artworkToBeUsed =
        when {
            listMode -> customListPath ?: customArtworkPath
            useLibraryCapsule -> customCarouselPath ?: customArtworkPath
            else -> customArtworkPath
        }
    val clickInteraction = remember { MutableInteractionSource() }
    val isPressed by clickInteraction.collectIsPressedAsState()
    val isFocused = isControllerActive && isFocusedOverride
    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.7f else 0f,
        animationSpec = if (isPressed) tween(100) else tween(400),
        label = "capsuleGlow",
    )
    val clickModifier =
        Modifier
            .then(
                if (glowAlpha > 0f) {
                    Modifier.drawWithContent {
                        drawContent()
                        drawRoundRect(
                            color = AccentGlow,
                            alpha = glowAlpha * 0.25f,
                            cornerRadius = CornerRadius(12.dp.toPx()),
                        )
                    }
                } else {
                    Modifier
                },
            ).combinedClickable(
                interactionSource = clickInteraction,
                indication = null,
                onClick = onClick ?: defaultClick,
                onLongClick = onLongClick,
            )

    @Composable
    fun ArtContent(artModifier: Modifier) {
        val customArtworkFile =
            artworkToBeUsed
                ?.let { java.io.File(it) }

        if (customArtworkFile != null) {
            val customArtworkCacheKey =
                "library_custom_icon:${customArtworkFile.absolutePath}:${customArtworkFile.lastModified()}"
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(customArtworkFile)
                        .memoryCacheKey(customArtworkCacheKey)
                        .diskCacheKey(customArtworkCacheKey)
                        .crossfade(300)
                        .build(),
                contentDescription = app.name,
                modifier = artModifier,
                contentScale = ContentScale.Crop,
            )
        } else if (isCustom) {
            val iconFile = customIconPath?.let { path -> java.io.File(path) }
            if (iconFile != null) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(context)
                            .data(iconFile)
                            .crossfade(300)
                            .build(),
                    contentDescription = app.name,
                    modifier = artModifier,
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = artModifier.background(SurfaceDark),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.SportsEsports,
                        contentDescription = app.name,
                        tint = Accent.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        } else {
            val preferWide = !useLibraryCapsule && !listMode && DeviceProfileSettings.preferWideArtwork(context)
            val imageModel =
                remember(app.id, gogGame, epicGame, useLibraryCapsule, listMode, preferWide, artworkCacheRefreshKey) {
                    StoreArtworkCache.imageModel(
                        context,
                        StoreArtworkCache.primaryRef(app, gogGame, epicGame, useLibraryCapsule, listMode, preferWide),
                    )
                }
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(imageModel)
                        .crossfade(300)
                        .build(),
                contentDescription = app.name,
                modifier = artModifier,
                contentScale = ContentScale.Crop,
            )
        }
    }

    if (listMode) {
        // Horizontal row card with hero background
        val heroRef = if (!isCustom && gogGame == null && !isEpic) StoreArtworkCache.heroRef(app, null, null) else null
        val heroModel =
            remember(app.id, heroRef, artworkCacheRefreshKey) {
                StoreArtworkCache.imageModel(context, heroRef)
            }

        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (isControllerActive && !isFocused) {
                            Modifier.border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                        } else {
                            Modifier
                        },
                    ).chasingBorder(
                        isFocused = isFocused,
                        paused = chasingBordersPaused.value || !libraryTabActive.value,
                        cornerRadius = 14.dp,
                    ).background(CardDark, RoundedCornerShape(14.dp))
                    .focusable()
                    .then(clickModifier),
        ) {
            // Hero background layer (falls back to CardDark if image fails)
            if (heroRef != null) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(context)
                            .data(heroModel)
                            .crossfade(300)
                            .build(),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = 0.25f },
                    contentScale = ContentScale.Crop,
                )
            } else {
                customHeroPath?.let {
                    val heroFile = java.io.File(customHeroPath)
                    if (heroFile.isFile) {
                        AsyncImage(
                            model =
                                ImageRequest
                                    .Builder(context)
                                    .data(heroFile)
                                    .crossfade(300)
                                    .build(),
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .graphicsLayer { alpha = 0.25f },
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }

            // Foreground content
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .height(52.dp)
                            .aspectRatio(462f / 174f)
                            .clip(RoundedCornerShape(8.dp)),
                ) {
                    ArtContent(Modifier.fillMaxSize())
                    libraryBadgeLabel(app.id, isCustom)?.let { badge ->
                        RetroConsoleRibbon(badge, Modifier.align(Alignment.CenterStart))
                    }
                }

                Spacer(Modifier.width(14.dp))

                Text(
                    text = app.name,
                    modifier =
                        Modifier
                            .weight(1f)
                            .then(if (isFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        // Vertical card: art on top, title below
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                modifier
                    .fillMaxWidth()
                    .then(
                        if (isFocused) {
                            Modifier
                        } else {
                            Modifier.border(1.dp, CardDark, RoundedCornerShape(12.dp))
                        },
                    ).chasingBorder(
                        isFocused = isFocused,
                        paused = chasingBordersPaused.value || !libraryTabActive.value,
                        cornerRadius = 12.dp,
                    ).background(CardDark, RoundedCornerShape(12.dp))
                    .focusable()
                    .then(clickModifier),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            ) {
                ArtContent(Modifier.fillMaxSize())
                libraryBadgeLabel(app.id, isCustom)?.let { badge ->
                    RetroConsoleRibbon(badge, Modifier.align(Alignment.CenterStart))
                }
            }

            Text(
                text = app.name,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .then(if (isFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// Epic Store Tab
@Composable
internal fun UnifiedActivity.EpicStoreTab(
    isLoggedIn: Boolean,
    epicApps: List<EpicGame>,
    searchQuery: String = "",
    layoutMode: LibraryLayoutMode = LibraryLayoutMode.GRID_4,
    onLoginClick: () -> Unit,
) {
    val context = LocalContext.current

    if (!isLoggedIn) {
        LoginRequiredScreen("Epic Games", onLoginClick)
        return
    }

    val selectedAppId = remember { mutableStateOf<Int?>(null) }
    val gridState = rememberLazyGridState()
    val activity = LocalContext.current as? UnifiedActivity

    // Ensure library updates from cloud
    LaunchedEffect(Unit) {
        if (epicApps.isEmpty()) {
            EpicService.triggerLibrarySync(context)
        }
    }

    val displayedApps =
        remember(epicApps, searchQuery) {
            if (searchQuery.isBlank()) {
                epicApps
            } else {
                epicApps.filter { it.title.contains(searchQuery, ignoreCase = true) }
            }
        }
    val installStateById = rememberEpicInstallStateMap(context, displayedApps)

    // Sync store focus infrastructure
    LaunchedEffect(displayedApps.size) {
        activity?.storeItemCount = displayedApps.size
        val lastIndex = (displayedApps.size - 1).coerceAtLeast(0)
        if (activity != null && displayedApps.isNotEmpty() && activity.storeFocusIndex.value > lastIndex) {
            activity.storeFocusIndex.value = lastIndex
        }
    }
    DisposableEffect(displayedApps) {
        val clickCallback: (Int) -> Unit = { idx ->
            displayedApps.getOrNull(idx)?.let { selectedAppId.value = it.id }
        }
        activity?.storeItemClickCallback = clickCallback
        activity?.storeGridState = gridState
        onDispose {
            if (activity?.storeItemClickCallback === clickCallback) {
                activity?.storeItemClickCallback = null
                activity?.storeGridState = null
            }
        }
    }

    if (layoutMode == LibraryLayoutMode.LIST) {
        val listViewState = rememberLazyListState()
        JoystickListScroll(listViewState, activity?.rightStickScrollState)
        ListView(
            items = displayedApps,
            modifier = Modifier.tabScreenPadding(),
            listState = listViewState,
            contentPadding = TabListContentPadding,
            keyOf = { it.id },
        ) { app, _, _ ->
            EpicStoreCapsule(
                app,
                isInstalled = installStateById[app.id] == true,
                listMode = true,
                isControllerActive = ControllerHelper.isControllerConnected(),
            ) {
                selectedAppId.value =
                    app.id
            }
        }
    } else {
        val focusIndex by (activity?.storeFocusIndex ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsState()
        val focusRequesters =
            remember(displayedApps.size) {
                List(displayedApps.size) { FocusRequester() }
            }
        LaunchedEffect(focusIndex, focusRequesters.size) {
            if (searchQuery.isEmpty() && focusRequesters.isNotEmpty() && focusIndex in focusRequesters.indices) {
                gridState.animateScrollToItem(focusIndex)
                try {
                    focusRequesters[focusIndex].requestFocus()
                } catch (_: Exception) {
                }
            }
        }
        JoystickGridScroll(gridState, activity?.rightStickScrollState)
        FourByTwoGridView(
            items = displayedApps,
            modifier = Modifier.tabScreenPadding(top = TabGridTopPadding),
            gridState = gridState,
            keyOf = { it.id },
        ) { app, index, rowHeight ->
            Box(
                Modifier.height(rowHeight).then(
                    if (index in focusRequesters.indices) {
                        Modifier.focusRequester(focusRequesters[index])
                    } else {
                        Modifier
                    },
                ),
            ) {
                EpicStoreCapsule(
                    app,
                    isInstalled = installStateById[app.id] == true,
                    isFocusedOverride = index == focusIndex,
                    isControllerActive = ControllerHelper.isControllerConnected(),
                ) {
                    selectedAppId.value =
                        app.id
                }
            }
        }
    }

    val selectedApp = epicApps.find { it.id == selectedAppId.value }
    if (selectedApp != null) {
        EpicGameManagerDialog(
            app = selectedApp,
            onDismissRequest = { selectedAppId.value = null },
        )
    }
}

@Composable
internal fun UnifiedActivity.StoreInstalledBadge(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    attachedCorner: Boolean = false,
) {
    val shape =
        if (attachedCorner) {
            RoundedCornerShape(topStart = 8.dp)
        } else {
            RoundedCornerShape(4.dp)
        }
    Box(
        modifier =
            modifier
                .background(StatusOnline, shape)
                .border(1.dp, Color.White.copy(alpha = 0.34f), shape)
                .padding(
                    start = if (compact) 6.dp else 9.dp,
                    end = if (compact) 6.dp else 9.dp,
                    top = if (compact) 2.dp else 4.dp,
                    bottom = if (compact) 1.dp else 2.dp,
                ),
    ) {
        Text(
            stringResource(R.string.library_games_installed_badge),
            color = Color(0xFF06140A),
            fontSize = if (compact) 9.sp else 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.6.sp,
            maxLines = 1,
        )
    }
}

@Composable
internal fun UnifiedActivity.EpicStoreCapsule(
    app: com.winlator.cmod.feature.stores.epic.data.EpicGame,
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
        label = "epicCapsuleGlow",
    )
    val effectiveFocus = isControllerActive && (isFocusedOverride || isFocused)
    val imageUrl = app.primaryImageUrl ?: app.iconUrl

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
                    model =
                        ImageRequest
                            .Builder(context)
                            .data(imageUrl)
                            .crossfade(300)
                            .build(),
                    contentDescription = app.title,
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
                app.title,
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
                    model =
                        ImageRequest
                            .Builder(context)
                            .data(imageUrl)
                            .crossfade(300)
                            .build(),
                    contentDescription = app.title,
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
                app.title,
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
internal fun UnifiedActivity.EpicGameManagerDialog(
    app: EpicGame,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val installed = EpicService.isGameInstalled(context, app.id)
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(!installed) }
    var manifestSizes by remember { mutableStateOf<EpicManager.ManifestSizes?>(null) }
    var dlcApps by remember { mutableStateOf<List<EpicGame>>(emptyList()) }
    val selectedDlcIds = remember { mutableStateListOf<Int>() }
    var customPath by remember { mutableStateOf<String?>(null) }
    var showCustomPathWarning by remember { mutableStateOf(false) }
    var isCheckingForUpdate by remember(app.id) { mutableStateOf(false) }
    var updateInfo by remember(app.id) { mutableStateOf<EpicUpdateInfo?>(null) }
    var updateStatusText by remember(app.id) { mutableStateOf<String?>(null) }
    val epicDownloadRecords by com.winlator.cmod.app.service.download.DownloadCoordinator.records.collectAsState(
        initial = com.winlator.cmod.app.service.download.DownloadCoordinator.snapshotRecords(),
    )
    val hasBlockingEpicDownload =
        epicDownloadRecords.any {
            it.store == com.winlator.cmod.app.db.download.DownloadRecord.STORE_EPIC &&
                it.storeGameId == app.id.toString() &&
                it.status in setOf(
                    com.winlator.cmod.app.db.download.DownloadRecord.STATUS_QUEUED,
                    com.winlator.cmod.app.db.download.DownloadRecord.STATUS_DOWNLOADING,
                    com.winlator.cmod.app.db.download.DownloadRecord.STATUS_PAUSED,
                    com.winlator.cmod.app.db.download.DownloadRecord.STATUS_FAILED,
                )
        }
    val updateActionEnabled = !hasBlockingEpicDownload
    val activeEpicDownloadText = stringResource(R.string.store_game_download_already_active)
    val noUpdateAvailableText = stringResource(R.string.store_game_no_update_available)
    val updateAvailableText = stringResource(R.string.store_game_update_available)
    val updateFailedText = stringResource(R.string.store_game_update_check_failed)

    if (showCustomPathWarning) {
        CustomPathWarningDialog(
            onDismiss = { showCustomPathWarning = false },
            onProceed = {
                showCustomPathWarning = false
                DirectoryPickerDialog.show(
                    activity = this@EpicGameManagerDialog,
                    initialPath = customPath ?: EpicConstants.getGameInstallPath(context, app.appName),
                    title = getString(R.string.settings_content_install_directory),
                    extraRoots = driveRoots(includeInternal = true),
                ) { path -> customPath = path }
            },
        )
    }

    LaunchedEffect(app.id, installed) {
        if (!installed) {
            val (sizes, sizedDlcs) =
                withContext(Dispatchers.IO) {
                    val baseSizes = EpicService.fetchManifestSizes(context, app.id)
                    val dlcs = EpicService.getDLCForGameSuspend(app.id)
                    val dlcsWithSizes =
                        dlcs
                            .map { dlc ->
                                async {
                                    if (dlc.downloadSize > 0L || dlc.installSize > 0L) {
                                        dlc
                                    } else {
                                        val dlcSizes = EpicService.fetchManifestSizes(context, dlc.id)
                                        dlc.copy(
                                            downloadSize = dlcSizes.downloadSize,
                                            installSize = dlcSizes.installSize,
                                        )
                                    }
                                }
                            }.awaitAll()
                    baseSizes to dlcsWithSizes
                }
            manifestSizes = sizes
            dlcApps = sizedDlcs
            isLoading = false
        } else {
            dlcApps =
                withContext(Dispatchers.IO) {
                    EpicService
                        .getDLCForGameSuspend(app.id)
                        .map { dlc ->
                            async {
                                if (dlc.downloadSize > 0L || dlc.installSize > 0L) {
                                    dlc
                                } else {
                                    val dlcSizes = EpicService.fetchManifestSizes(context, dlc.id)
                                    dlc.copy(
                                        downloadSize = dlcSizes.downloadSize,
                                        installSize = dlcSizes.installSize,
                                    )
                                }
                            }
                        }.awaitAll()
                }
        }
    }

    val baseDownloadSize = manifestSizes?.downloadSize ?: 0L
    val baseInstallSize = manifestSizes?.installSize ?: 0L
    val selectedDlcDownloadBytes =
        dlcApps.filter { it.id in selectedDlcIds }.sumOf { it.downloadSize.coerceAtLeast(0L) }
    val selectedDlcInstallBytes =
        dlcApps.filter { it.id in selectedDlcIds }.sumOf { it.installSize.coerceAtLeast(0L) }
    val totalDownloadSize = baseDownloadSize + selectedDlcDownloadBytes
    val totalInstallSize = baseInstallSize + selectedDlcInstallBytes
    val defaultPathSet =
        if (PrefManager.useSingleDownloadFolder) {
            PrefManager.defaultDownloadFolder.isNotEmpty()
        } else {
            PrefManager.epicDownloadFolder
                .isNotEmpty()
        }
    val effectivePath = customPath ?: EpicConstants.getGameInstallPath(context, app.appName)
    val availableBytes =
        try {
            StorageUtils.getAvailableSpace(effectivePath)
        } catch (e: Exception) {
            0L
        }
    // Installed game: base content is on disk, so only gate on the newly-selected DLC bytes.
    val requiredBytes = if (installed) selectedDlcInstallBytes else totalInstallSize
    val isInstallEnabled = requiredBytes == 0L || availableBytes >= requiredBytes
    val installActionEnabled = isInstallEnabled && !hasBlockingEpicDownload
    val installPathDisplay = if (installed) app.installPath else (customPath ?: EpicConstants.defaultEpicGamesPath(context))

    val dlcItems =
        remember(dlcApps) {
            dlcApps.map { dlc ->
                val size =
                    dlc.downloadSize.takeIf { it > 0L }
                        ?: dlc.installSize
                StoreDlcItem(id = dlc.id, name = dlc.title, downloadSize = size, isInstalled = dlc.isInstalled)
            }
        }
    val customPathLabel =
        when {
            customPath != null -> stringResource(R.string.common_ui_custom)
            defaultPathSet -> stringResource(R.string.common_ui_already_set)
            else -> stringResource(R.string.common_ui_custom)
        }

    Dialog(
        onDismissRequest = onDismissRequest,
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
                title = app.title,
                subtitle =
                    listOfNotNull(
                        app.developer.takeIf { it.isNotBlank() },
                        app.publisher.takeIf {
                            it.isNotBlank() && !it.equals(app.developer, ignoreCase = true)
                        },
                    ).joinToString(" • "),
                sourceLabel = "Epic Games",
                heroImageUrl = StoreArtworkCache.imageModel(context, StoreArtworkCache.epicHeroRef(app)),
                isLoading = isLoading,
                isInstalled = installed,
                installPathDisplay = installPathDisplay,
                downloadSize = totalDownloadSize,
                installSize = totalInstallSize,
                availableBytes = availableBytes,
                isInstallEnabled = isInstallEnabled,
                isDownloadActionEnabled = installActionEnabled,
                customPathLabel = customPathLabel,
                showCustomPath = true,
                showCloudSync = false,
                showUninstall = false,
                showUpdateCheck = installed,
                isCheckingForUpdate = isCheckingForUpdate,
                isUpdateAvailable = updateInfo?.hasUpdate == true,
                updateDownloadSize = updateInfo?.downloadSize ?: 0L,
                updateStatusText = updateStatusText,
                isUpdateActionEnabled = updateActionEnabled,
                showVerifyFiles = installed,
                areSteamActionsEnabled = !hasBlockingEpicDownload,
                dlcs = dlcItems,
                selectedDlcIds = selectedDlcIds.toSet(),
                onBack = onDismissRequest,
                onInstall = {
                    if (hasBlockingEpicDownload) {
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            activeEpicDownloadText,
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        return@StoreGameDetailScreen
                    }
                    val installPath =
                        if (customPath != null) {
                            val sanitizedTitle = app.title.replace(Regex("[^a-zA-Z0-9 \\-_]"), "").trim()
                            java.io.File(customPath!!, sanitizedTitle).absolutePath
                        } else {
                            EpicConstants.getGameInstallPath(context, app.title)
                        }
                    context.runIfOnlineOrToast {
                        EpicService.downloadGame(context, app.id, selectedDlcIds.toList(), installPath, "en-US")
                        onDismissRequest()
                    }
                },
                onCloudSync = {
                    scope.launch(Dispatchers.IO) {
                        EpicCloudSavesManager.syncCloudSaves(context, app.id, "auto")
                    }
                    onDismissRequest()
                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                        context,
                        context.getString(R.string.google_cloud_sync_started),
                        android.widget.Toast.LENGTH_SHORT,
                    )
                },
                onVerifyFiles = {
                    if (hasBlockingEpicDownload) {
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            activeEpicDownloadText,
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        return@StoreGameDetailScreen
                    }
                    context.runIfOnlineOrToast {
                        scope.launch {
                            val started =
                                withContext(Dispatchers.IO) {
                                    EpicService.verifyGameFiles(context, app.id)
                                }
                            if (started != null) {
                                showTaskProgressPopup(
                                    started,
                                    app.title,
                                    getString(R.string.store_game_verify_complete),
                                    getString(R.string.store_game_verify_failed_notice),
                                    completeAsToast = true,
                                )
                            } else {
                                com.winlator.cmod.shared.ui.toast.WinToast.show(
                                    context,
                                    activeEpicDownloadText,
                                    android.widget.Toast.LENGTH_SHORT,
                                )
                            }
                        }
                    }
                },
                onCheckForUpdate = {
                    if (hasBlockingEpicDownload) {
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            activeEpicDownloadText,
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        return@StoreGameDetailScreen
                    }
                    context.runIfOnlineOrToast {
                        scope.launch {
                            isCheckingForUpdate = true
                            updateStatusText = null
                            val latest =
                                withContext(Dispatchers.IO) {
                                    EpicService.checkForGameUpdate(context, app.id)
                                }
                            updateInfo = latest
                            updateStatusText =
                                when {
                                    latest.hasUpdate -> updateAvailableText
                                    latest.message != null -> updateFailedText
                                    else -> null
                                }
                            isCheckingForUpdate = false
                            if (!latest.hasUpdate && latest.message == null) {
                                com.winlator.cmod.shared.ui.toast.WinToast.show(
                                    context,
                                    noUpdateAvailableText,
                                    android.widget.Toast.LENGTH_SHORT,
                                )
                            }
                        }
                    }
                },
                onDownloadUpdate = {
                    if (!updateActionEnabled || updateInfo?.hasUpdate != true) return@StoreGameDetailScreen
                    context.runIfOnlineOrToast {
                        scope.launch {
                            val latest =
                                withContext(Dispatchers.IO) {
                                    EpicService.checkForGameUpdate(context, app.id)
                                }
                            updateInfo = latest
                            updateStatusText =
                                when {
                                    latest.hasUpdate -> updateAvailableText
                                    latest.message != null -> updateFailedText
                                    else -> null
                                }
                            if (!latest.hasUpdate) {
                                com.winlator.cmod.shared.ui.toast.WinToast.show(
                                    context,
                                    noUpdateAvailableText,
                                    android.widget.Toast.LENGTH_SHORT,
                                )
                                return@launch
                            }
                            val started =
                                withContext(Dispatchers.IO) {
                                    EpicService.updateGameFiles(context, app.id)
                                }
                            if (started != null) {
                                onDismissRequest()
                            } else {
                                com.winlator.cmod.shared.ui.toast.WinToast.show(
                                    context,
                                    activeEpicDownloadText,
                                    android.widget.Toast.LENGTH_SHORT,
                                )
                            }
                        }
                    }
                },
                onUninstall = {
                    scope.launch(Dispatchers.IO) {
                        val result = EpicService.deleteGame(context, app.id)
                        withContext(Dispatchers.Main) {
                            if (!result.isSuccess) {
                                com.winlator.cmod.shared.ui.toast.WinToast.show(
                                    context,
                                    getString(
                                        R.string.library_games_failed_to_uninstall_reason,
                                        result.exceptionOrNull()?.message
                                            ?: getString(R.string.common_ui_unknown_error),
                                    ),
                                    android.widget.Toast.LENGTH_LONG,
                                )
                            }
                            onDismissRequest()
                        }
                    }
                },
                onCustomPath = {
                    if (customPath == null && defaultPathSet) {
                        showCustomPathWarning = true
                    } else {
                        DirectoryPickerDialog.show(
                            activity = this@EpicGameManagerDialog,
                            initialPath = customPath ?: EpicConstants.getGameInstallPath(context, app.appName),
                            title = getString(R.string.settings_content_install_directory),
                            extraRoots = driveRoots(includeInternal = true),
                        ) { path -> customPath = path }
                    }
                },
                onToggleDlc = { id ->
                    if (selectedDlcIds.contains(id)) {
                        selectedDlcIds.remove(id)
                    } else {
                        selectedDlcIds.add(id)
                    }
                },
                onToggleSelectAllDlcs = {
                    val all = dlcItems.isNotEmpty() && dlcItems.all { it.id in selectedDlcIds }
                    if (all) {
                        selectedDlcIds.clear()
                    } else {
                        dlcItems.forEach { if (it.id !in selectedDlcIds) selectedDlcIds.add(it.id) }
                    }
                },
            )
        }
    }
}

@Composable
internal fun UnifiedActivity.GOGStoreTab(
    isLoggedIn: Boolean,
    gogApps: List<GOGGame>,
    searchQuery: String = "",
    layoutMode: LibraryLayoutMode = LibraryLayoutMode.GRID_4,
    onLoginClick: () -> Unit,
) {
    if (!isLoggedIn) {
        LoginRequiredScreen("GOG", onLoginClick)
        return
    }

    val selectedGameId = remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    val activity = LocalContext.current as? UnifiedActivity

    val displayedApps =
        remember(gogApps, searchQuery) {
            if (searchQuery.isBlank()) {
                gogApps
            } else {
                gogApps.filter { it.title.contains(searchQuery, ignoreCase = true) }
            }
        }
    val installStateById = rememberGogInstallStateMap(displayedApps)

    // Sync store focus infrastructure
    LaunchedEffect(displayedApps.size) {
        activity?.storeItemCount = displayedApps.size
        val lastIndex = (displayedApps.size - 1).coerceAtLeast(0)
        if (activity != null && displayedApps.isNotEmpty() && activity.storeFocusIndex.value > lastIndex) {
            activity.storeFocusIndex.value = lastIndex
        }
    }
    DisposableEffect(displayedApps) {
        val clickCallback: (Int) -> Unit = { idx ->
            displayedApps.getOrNull(idx)?.let { selectedGameId.value = it.id }
        }
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
    val gogBorderColor = if (isControllerActive) CardBorder else Color.Transparent

    if (layoutMode == LibraryLayoutMode.LIST) {
        val listViewState = rememberLazyListState()
        ListView(
            items = displayedApps,
            modifier = Modifier.tabScreenPadding(),
            listState = listViewState,
            contentPadding = TabListContentPadding,
            keyOf = { it.id },
        ) { app, _, _ ->
            val isInstalled = installStateById[app.id] == true
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, gogBorderColor, RoundedCornerShape(14.dp))
                        .background(CardDark, RoundedCornerShape(14.dp))
                        .clickable { selectedGameId.value = app.id }
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
                        model =
                            ImageRequest
                                .Builder(LocalContext.current)
                                .data(app.imageUrl.ifEmpty { app.iconUrl })
                                .crossfade(300)
                                .build(),
                        contentDescription = null,
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
                    text = app.title,
                    modifier = Modifier.weight(1f),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        val focusIndex by (activity?.storeFocusIndex ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsState()
        val focusRequesters =
            remember(displayedApps.size) {
                List(displayedApps.size) { FocusRequester() }
            }
        LaunchedEffect(focusIndex, focusRequesters.size) {
            if (searchQuery.isEmpty() && focusRequesters.isNotEmpty() && focusIndex in focusRequesters.indices) {
                gridState.animateScrollToItem(focusIndex)
                try {
                    focusRequesters[focusIndex].requestFocus()
                } catch (_: Exception) {
                }
            }
        }
        FourByTwoGridView(
            items = displayedApps,
            modifier = Modifier.tabScreenPadding(top = TabGridTopPadding),
            gridState = gridState,
            keyOf = { it.id },
        ) { app, index, rowHeight ->
            val isInstalled = installStateById[app.id] == true
            val isItemFocused = isControllerActive && index == focusIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .then(
                            if (index in focusRequesters.indices) {
                                Modifier.focusRequester(focusRequesters[index])
                            } else {
                                Modifier
                            },
                        ).border(1.dp, gogBorderColor, RoundedCornerShape(16.dp))
                        .chasingBorder(isFocused = isItemFocused, paused = chasingBordersPaused.value, cornerRadius = 16.dp)
                        .background(CardDark, RoundedCornerShape(16.dp))
                        .clickable { selectedGameId.value = app.id },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                ) {
                    AsyncImage(
                        model =
                            ImageRequest
                                .Builder(LocalContext.current)
                                .data(app.imageUrl.ifEmpty { app.iconUrl })
                                .crossfade(300)
                                .build(),
                        contentDescription = null,
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
                    text = app.title,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    selectedGameId.value?.let { gameId ->
        val app = gogApps.firstOrNull { it.id == gameId }
        if (app != null) {
            GOGGameManagerDialog(app = app) { selectedGameId.value = null }
        }
    }
}

@Composable
internal fun UnifiedActivity.GOGGameManagerDialog(
    app: GOGGame,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val installed = GOGService.isGameInstalled(app.id)
    val scope = rememberCoroutineScope()
    var isLoading by remember(app.id) { mutableStateOf(true) }
    var selectedManifestSizes by remember(app.id) { mutableStateOf(GOGManifestSizes()) }
    var dlcSizes by remember(app.id) { mutableStateOf<Map<Int, GOGManifestSizes>>(emptyMap()) }
    var customPath by remember { mutableStateOf<String?>(null) }
    var showCustomPathWarning by remember { mutableStateOf(false) }
    var dlcApps by remember(app.id) { mutableStateOf<List<GOGDlcInfo>>(emptyList()) }
    val selectedDlcIds = remember(app.id) { mutableStateListOf<Int>() }
    var isCheckingForGogUpdate by remember(app.id) { mutableStateOf(false) }
    var gogUpdateInfo by remember(app.id) { mutableStateOf<GOGUpdateInfo?>(null) }
    var gogUpdateStatusText by remember(app.id) { mutableStateOf<String?>(null) }
    val gogDownloadRecords by com.winlator.cmod.app.service.download.DownloadCoordinator.records.collectAsState(
        initial = com.winlator.cmod.app.service.download.DownloadCoordinator.snapshotRecords(),
    )
    val hasBlockingGogDownload =
        gogDownloadRecords.any {
            it.store == com.winlator.cmod.app.db.download.DownloadRecord.STORE_GOG &&
                it.storeGameId == app.id &&
                it.status in setOf(
                    com.winlator.cmod.app.db.download.DownloadRecord.STATUS_QUEUED,
                    com.winlator.cmod.app.db.download.DownloadRecord.STATUS_DOWNLOADING,
                    com.winlator.cmod.app.db.download.DownloadRecord.STATUS_PAUSED,
                    com.winlator.cmod.app.db.download.DownloadRecord.STATUS_FAILED,
                )
        }
    val gogUpdateActionEnabled = !hasBlockingGogDownload
    val activeGogDownloadText = stringResource(R.string.store_game_download_already_active)
    val gogNoUpdateAvailableText = stringResource(R.string.store_game_no_update_available)
    val gogUpdateAvailableText = stringResource(R.string.store_game_update_available)
    val gogUpdateFailedText = stringResource(R.string.store_game_update_check_failed)

    if (showCustomPathWarning) {
        CustomPathWarningDialog(
            onDismiss = { showCustomPathWarning = false },
            onProceed = {
                showCustomPathWarning = false
                DirectoryPickerDialog.show(
                    activity = this@GOGGameManagerDialog,
                    initialPath = customPath ?: GOGConstants.defaultGOGGamesPath,
                    title = getString(R.string.settings_content_install_directory),
                    extraRoots = driveRoots(includeInternal = true),
                ) { path -> customPath = path }
            },
        )
    }

    data class GogInstallLoadData(
        val dlcs: List<GOGDlcInfo>,
        val dlcSizes: Map<Int, GOGManifestSizes>,
        val baseManifestSizes: GOGManifestSizes,
    )

    LaunchedEffect(app.id, PrefManager.containerLanguage) {
        isLoading = true
        val loadData =
            withContext(Dispatchers.IO) {
                val dlcs = GOGService.getDLCForGameSuspend(app.id, PrefManager.containerLanguage)
                val perDlcSizes =
                    dlcs.mapNotNull { dlc ->
                        val id = dlc.id.toIntOrNull() ?: return@mapNotNull null
                        id to
                            GOGManifestSizes(
                                installSize = dlc.installSize,
                                downloadSize = dlc.downloadSize,
                            )
                    }.toMap()
                GogInstallLoadData(
                    dlcs = dlcs,
                    dlcSizes = perDlcSizes,
                    baseManifestSizes =
                        GOGService.getInstallableSelectedManifestSizes(
                            app.id,
                            PrefManager.containerLanguage,
                        ),
                )
            }
        dlcApps = loadData.dlcs
        dlcSizes = loadData.dlcSizes
        selectedManifestSizes = loadData.baseManifestSizes
        selectedDlcIds.clear()
        loadData.dlcs
            .filterNot { it.isInstalled }
            .mapNotNull { it.id.toIntOrNull() }
            .forEach { selectedDlcIds.add(it) }
        isLoading = false
    }

    LaunchedEffect(app.id, PrefManager.containerLanguage, selectedDlcIds.toList()) {
        selectedManifestSizes =
            withContext(Dispatchers.IO) {
                GOGService.getInstallableSelectedManifestSizes(
                    app.id,
                    PrefManager.containerLanguage,
                    selectedDlcIds.toList(),
                )
            }
    }

    val defaultPathSet =
        if (PrefManager.useSingleDownloadFolder) {
            PrefManager.defaultDownloadFolder.isNotEmpty()
        } else {
            PrefManager.gogDownloadFolder
                .isNotEmpty()
        }
    val installRootPath = customPath ?: GOGConstants.defaultGOGGamesPath
    val installPathDisplay =
        if (installed) {
            app.installPath
        } else if (customPath != null) {
            java.io.File(customPath!!, GOGConstants.getSanitizedGameFolderName(app.title)).absolutePath
        } else {
            GOGConstants.getGameInstallPath(app.title)
        }
    val dlcItems =
        remember(dlcApps, dlcSizes) {
            dlcApps.mapNotNull { dlc ->
                val id = dlc.id.toIntOrNull() ?: return@mapNotNull null
                val manifestSize = dlcSizes[id]
                val size =
                    manifestSize
                        ?.downloadSize
                        ?.takeIf { it > 0L }
                        ?: manifestSize?.installSize?.takeIf { it > 0L }
                        ?: dlc.downloadSize.takeIf { it > 0L }
                        ?: dlc.installSize
                StoreDlcItem(id = id, name = dlc.title, downloadSize = size, isInstalled = dlc.isInstalled)
            }
        }
    val selectedDlcDownloadSize =
        remember(dlcItems, selectedDlcIds.toList()) {
            dlcItems
                .filter { !it.isInstalled && it.id in selectedDlcIds }
                .sumOf { it.downloadSize.coerceAtLeast(0L) }
        }
    val selectedDlcInstallSize =
        remember(dlcSizes, dlcItems, selectedDlcIds.toList()) {
            dlcItems
                .filter { !it.isInstalled && it.id in selectedDlcIds }
                .sumOf { dlcSizes[it.id]?.installSize?.takeIf { size -> size > 0L } ?: it.downloadSize.coerceAtLeast(0L) }
        }
    val totalDownloadSize =
        if (installed) {
            selectedDlcDownloadSize
        } else {
            selectedManifestSizes.downloadSize.takeIf { it > 0L }
                ?: app.downloadSize + selectedDlcDownloadSize
        }
    val totalInstallSize =
        if (installed) {
            selectedDlcInstallSize
        } else {
            selectedManifestSizes.installSize.takeIf { it > 0L }
                ?: app.installSize.takeIf { it > 0L }
                ?: totalDownloadSize
        }
    val requiredBytes =
        if (installed) {
            selectedDlcInstallSize.takeIf { it > 0L } ?: selectedDlcDownloadSize
        } else {
            totalInstallSize.takeIf { it > 0L } ?: totalDownloadSize
        }
    val availableBytes =
        try {
            StorageUtils.getAvailableSpace(installRootPath)
        } catch (_: Exception) {
            0L
        }
    val isInstallEnabled = requiredBytes == 0L || availableBytes >= requiredBytes
    val installActionEnabled = isInstallEnabled && !hasBlockingGogDownload
    val customPathLabel =
        when {
            customPath != null -> stringResource(R.string.common_ui_custom)
            defaultPathSet -> stringResource(R.string.common_ui_already_set)
            else -> stringResource(R.string.common_ui_custom)
        }

    Dialog(
        onDismissRequest = onDismissRequest,
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
                title = app.title,
                subtitle =
                    listOfNotNull(
                        app.developer.takeIf { it.isNotBlank() },
                        app.publisher.takeIf {
                            it.isNotBlank() && !it.equals(app.developer, ignoreCase = true)
                        },
                    ).joinToString(" • "),
                sourceLabel = "GOG",
                heroImageUrl = StoreArtworkCache.imageModel(context, StoreArtworkCache.gogHeroRef(app)),
                isLoading = isLoading,
                isInstalled = installed,
                installPathDisplay = installPathDisplay,
                downloadSize = totalDownloadSize,
                installSize = totalInstallSize,
                availableBytes = availableBytes,
                isInstallEnabled = isInstallEnabled,
                isDownloadActionEnabled = installActionEnabled,
                customPathLabel = customPathLabel,
                showCustomPath = true,
                showCloudSync = false,
                showUninstall = false,
                showUpdateCheck = installed,
                isCheckingForUpdate = isCheckingForGogUpdate,
                isUpdateAvailable = gogUpdateInfo?.hasUpdate == true,
                updateDownloadSize = gogUpdateInfo?.downloadSize ?: 0L,
                updateStatusText = gogUpdateStatusText,
                isUpdateActionEnabled = gogUpdateActionEnabled,
                showVerifyFiles = installed,
                areSteamActionsEnabled = !hasBlockingGogDownload,
                dlcs = dlcItems,
                selectedDlcIds = selectedDlcIds.toSet(),
                isDlcSelectionEnabled = installActionEnabled,
                onBack = onDismissRequest,
                onInstall = {
                    if (hasBlockingGogDownload) {
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            getString(R.string.store_game_download_already_active),
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        return@StoreGameDetailScreen
                    }
                    context.runIfOnlineOrToast {
                        GOGService.downloadGame(
                            context,
                            app.id,
                            installPathDisplay,
                            PrefManager.containerLanguage,
                            selectedDlcIds.toList(),
                        )
                        onDismissRequest()
                    }
                },
                onCloudSync = {
                    scope.launch(Dispatchers.IO) {
                        GOGService.syncCloudSaves(context, "GOG_${app.id}", "auto")
                    }
                    onDismissRequest()
                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                        context,
                        context.getString(R.string.google_cloud_sync_started),
                        android.widget.Toast.LENGTH_SHORT,
                    )
                },
                onVerifyFiles = {
                    if (hasBlockingGogDownload) {
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            activeGogDownloadText,
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        return@StoreGameDetailScreen
                    }
                    context.runIfOnlineOrToast {
                        scope.launch {
                            val started =
                                withContext(Dispatchers.IO) {
                                    GOGService.verifyGameFiles(context, app.id)
                                }
                            if (started != null) {
                                showTaskProgressPopup(
                                    started,
                                    app.title,
                                    getString(R.string.store_game_verify_complete),
                                    getString(R.string.store_game_verify_failed_notice),
                                    completeAsToast = true,
                                )
                            } else {
                                com.winlator.cmod.shared.ui.toast.WinToast.show(
                                    context,
                                    activeGogDownloadText,
                                    android.widget.Toast.LENGTH_SHORT,
                                )
                            }
                        }
                    }
                },
                onCheckForUpdate = {
                    if (hasBlockingGogDownload) {
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            activeGogDownloadText,
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        return@StoreGameDetailScreen
                    }
                    context.runIfOnlineOrToast {
                        scope.launch {
                            isCheckingForGogUpdate = true
                            gogUpdateStatusText = null
                            val latest =
                                withContext(Dispatchers.IO) {
                                    GOGService.checkForGameUpdate(context, app.id)
                                }
                            gogUpdateInfo = latest
                            gogUpdateStatusText =
                                when {
                                    latest.hasUpdate -> gogUpdateAvailableText
                                    latest.message != null -> gogUpdateFailedText
                                    else -> null
                                }
                            isCheckingForGogUpdate = false
                            if (!latest.hasUpdate && latest.message == null) {
                                com.winlator.cmod.shared.ui.toast.WinToast.show(
                                    context,
                                    gogNoUpdateAvailableText,
                                    android.widget.Toast.LENGTH_SHORT,
                                )
                            }
                        }
                    }
                },
                onDownloadUpdate = {
                    if (!gogUpdateActionEnabled || gogUpdateInfo?.hasUpdate != true) return@StoreGameDetailScreen
                    context.runIfOnlineOrToast {
                        scope.launch {
                            val started =
                                withContext(Dispatchers.IO) {
                                    GOGService.updateGameFiles(context, app.id)
                                }
                            if (started != null) {
                                onDismissRequest()
                            } else {
                                com.winlator.cmod.shared.ui.toast.WinToast.show(
                                    context,
                                    activeGogDownloadText,
                                    android.widget.Toast.LENGTH_SHORT,
                                )
                            }
                        }
                    }
                },
                onUninstall = {
                    scope.launch(Dispatchers.IO) {
                        val result =
                            GOGService.deleteGame(
                                context,
                                LibraryItem(
                                    "GOG_${app.id}",
                                    app.title,
                                    com.winlator.cmod.feature.stores.steam.enums.GameSource.GOG,
                                ),
                            )
                        withContext(Dispatchers.Main) {
                            if (!result.isSuccess) {
                                com.winlator.cmod.shared.ui.toast.WinToast.show(
                                    context,
                                    getString(
                                        R.string.library_games_failed_to_uninstall_reason,
                                        result.exceptionOrNull()?.message
                                            ?: getString(R.string.common_ui_unknown_error),
                                    ),
                                    android.widget.Toast.LENGTH_LONG,
                                )
                            }
                            onDismissRequest()
                        }
                    }
                },
                onCustomPath = {
                    if (customPath == null && defaultPathSet) {
                        showCustomPathWarning = true
                    } else {
                        DirectoryPickerDialog.show(
                            activity = this@GOGGameManagerDialog,
                            initialPath = customPath ?: GOGConstants.defaultGOGGamesPath,
                            title = getString(R.string.settings_content_install_directory),
                            extraRoots = driveRoots(includeInternal = true),
                        ) { path -> customPath = path }
                    }
                },
                onToggleDlc = { id ->
                    if (dlcItems.any { it.id == id && it.isInstalled }) {
                        return@StoreGameDetailScreen
                    }
                    if (selectedDlcIds.contains(id)) {
                        selectedDlcIds.remove(id)
                    } else {
                        selectedDlcIds.add(id)
                    }
                },
                onToggleSelectAllDlcs = {
                    val selectableDlcItems = dlcItems.filterNot { it.isInstalled }
                    val all = selectableDlcItems.isNotEmpty() && selectableDlcItems.all { it.id in selectedDlcIds }
                    if (all) {
                        selectedDlcIds.removeAll(selectableDlcItems.map { it.id }.toSet())
                    } else {
                        selectableDlcItems.forEach { if (it.id !in selectedDlcIds) selectedDlcIds.add(it.id) }
                    }
                },
            )
        }
    }
}

// Steam Store Tab
@Composable
internal fun UnifiedActivity.SteamStoreTab(
    isLoggedIn: Boolean,
    steamApps: List<SteamApp>,
    searchQuery: String = "",
    layoutMode: LibraryLayoutMode = LibraryLayoutMode.GRID_4,
) {
    if (!isLoggedIn && !SteamService.hasStoredCredentials(this)) {
        LoginRequiredScreen("Steam") {
            startActivity(Intent(this@SteamStoreTab, SteamLoginActivity::class.java))
        }
        return
    }

    var selectedAppForDialog by remember { mutableStateOf<SteamApp?>(null) }
    val gridState = rememberLazyGridState()
    val activity = LocalContext.current as? UnifiedActivity

    val displayedApps =
        remember(steamApps, searchQuery) {
            if (searchQuery.isBlank()) {
                steamApps
            } else {
                steamApps.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
        }
    val installStateById = rememberSteamInstallStateMap(displayedApps)

    // Sync store focus infrastructure
    LaunchedEffect(displayedApps.size) {
        activity?.storeItemCount = displayedApps.size
        val lastIndex = (displayedApps.size - 1).coerceAtLeast(0)
        if (activity != null && displayedApps.isNotEmpty() && activity.storeFocusIndex.value > lastIndex) {
            activity.storeFocusIndex.value = lastIndex
        }
    }
    // Register A-button click callback and grid state for visible-area snapping
    DisposableEffect(displayedApps) {
        val clickCallback: (Int) -> Unit = { idx ->
            displayedApps.getOrNull(idx)?.let { selectedAppForDialog = it }
        }
        activity?.storeItemClickCallback = clickCallback
        activity?.storeGridState = gridState
        onDispose {
            if (activity?.storeItemClickCallback === clickCallback) {
                activity?.storeItemClickCallback = null
                activity?.storeGridState = null
            }
        }
    }

    if (layoutMode == LibraryLayoutMode.LIST) {
        val listViewState = rememberLazyListState()
        JoystickListScroll(listViewState, activity?.rightStickScrollState, minSpeed = 2.5f, maxSpeed = 16f, quadratic = true)
        ListView(
            items = displayedApps,
            modifier = Modifier.tabScreenPadding(),
            listState = listViewState,
            contentPadding = TabListContentPadding,
            keyOf = { it.id },
        ) { app, _, _ ->
            SteamStoreCapsule(
                app,
                isInstalled = installStateById[app.id] == true,
                listMode = true,
                isControllerActive = ControllerHelper.isControllerConnected(),
                onClick = {
                    selectedAppForDialog =
                        app
                },
            )
        }
    } else {
        val focusIndex by (activity?.storeFocusIndex ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsState()
        val focusRequesters =
            remember(displayedApps.size) {
                List(displayedApps.size) { FocusRequester() }
            }
        LaunchedEffect(focusIndex, focusRequesters.size) {
            if (searchQuery.isEmpty() && focusRequesters.isNotEmpty() && focusIndex in focusRequesters.indices) {
                gridState.animateScrollToItem(focusIndex)
                try {
                    focusRequesters[focusIndex].requestFocus()
                } catch (_: Exception) {
                }
            }
        }
        // Right joystick: 2x faster at full push with quadratic speed curve
        JoystickGridScroll(gridState, activity?.rightStickScrollState, minSpeed = 2.5f, maxSpeed = 16f, quadratic = true)
        // Left joystick: 75% slower scrolling (vertical only, for browsing store)
        JoystickGridScroll(gridState, activity?.leftStickScrollState, deadZone = 0.15f, minSpeed = 0.3125f, maxSpeed = 2f)
        FourByTwoGridView(
            items = displayedApps,
            modifier = Modifier.tabScreenPadding(top = TabGridTopPadding),
            gridState = gridState,
            keyOf = { it.id },
        ) { app, index, rowHeight ->
            Box(
                Modifier.height(rowHeight).then(
                    if (index in focusRequesters.indices) {
                        Modifier.focusRequester(focusRequesters[index])
                    } else {
                        Modifier
                    },
                ),
            ) {
                SteamStoreCapsule(
                    app,
                    isInstalled = installStateById[app.id] == true,
                    isFocusedOverride = index == focusIndex,
                    isControllerActive =
                        ControllerHelper
                            .isControllerConnected(),
                    onClick = {
                        selectedAppForDialog =
                            app
                    },
                )
            }
        }
    }

    if (selectedAppForDialog != null) {
        GameManagerDialog(
            app = selectedAppForDialog!!,
            onDismissRequest = { selectedAppForDialog = null },
        )
    }
}

@Composable
internal fun UnifiedActivity.SteamStoreCapsule(
    app: SteamApp,
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
        label = "steamCapsuleGlow",
    )
    val effectiveFocus = isControllerActive && (isFocusedOverride || isFocused)
    val borderColor = if (isControllerActive) CardBorder else Color.Transparent

    if (listMode) {
        Box(
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
                    ).clickable(interactionSource = clickInteraction, indication = null, onClick = onClick),
        ) {
            // Hero background
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(app.getHeroUrl())
                        .crossfade(300)
                        .build(),
                contentDescription = null,
                modifier =
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = 0.25f },
                contentScale = ContentScale.Crop,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
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
                        model =
                            ImageRequest
                                .Builder(context)
                                .data(app.getSmallCapsuleUrl())
                                .crossfade(300)
                                .build(),
                        contentDescription = null,
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
                    text = app.name,
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
                val imageUrl = app.getCapsuleUrl()

                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(context)
                            .data(imageUrl)
                            .crossfade(300)
                            .build(),
                    contentDescription = null,
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
                text = app.name,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
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
