package com.winlator.cmod.app.shell
import com.winlator.cmod.app.shell.UnifiedActivity.ControllerConnectionState

import android.content.SharedPreferences
import androidx.preference.PreferenceManager
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.core.net.toUri
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
import com.winlator.cmod.feature.artwork.SteamArtworkScraper
import com.winlator.cmod.runtime.container.Container
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
import com.winlator.cmod.shared.ui.layout.screenWidthDp
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
import kotlinx.coroutines.CoroutineScope
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

// Navigation drawer + add-custom-game dialog + empty/login states, split out of UnifiedActivity.kt (behavior-identical).

@Composable
internal fun UnifiedActivity.EmptyStateMessage(message: String) {
    Text(message, color = TextSecondary, modifier = Modifier.padding(16.dp))
}

@Composable
internal fun UnifiedActivity.LoginRequiredScreen(
    storeName: String,
    onLoginClick: () -> Unit,
) {
    val message =
        if (storeName ==
            "Library"
        ) {
            stringResource(R.string.library_games_sign_in_prompt)
        } else {
            stringResource(R.string.stores_accounts_sign_in_store_prompt, storeName)
        }
    val buttonText =
        if (storeName ==
            "Library"
        ) {
            stringResource(R.string.stores_accounts_manage)
        } else {
            stringResource(R.string.stores_accounts_sign_into_store, storeName)
        }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 48.dp),
        ) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                message,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(20.dp))
            val interactionSource =
                remember {
                    androidx.compose.foundation.interaction
                        .MutableInteractionSource()
                }
            val isPressed by interactionSource.collectIsPressedAsState()
            val btnScale by animateFloatAsState(
                targetValue = if (isPressed) 0.95f else 1f,
                animationSpec = tween(100),
                label = "btnScale",
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .graphicsLayer {
                            scaleX = btnScale
                            scaleY = btnScale
                        }.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onLoginClick,
                        ).border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(buttonText, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// Drawer content: avatar card + filters
@Composable
internal fun UnifiedActivity.DrawerContent(
    persona: com.winlator.cmod.feature.stores.steam.data.SteamFriend?,
    isOpen: Boolean,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    storeVisible: SnapshotStateMap<String, Boolean>,
    contentFilters: SnapshotStateMap<String, Boolean>,
    libraryLayoutMode: LibraryLayoutMode,
    immersiveMode: Boolean,
    immersiveBlur: Boolean,
    forceLandscape: Boolean,
    onLibraryLayoutSelected: (LibraryLayoutMode) -> Unit,
    onStoreVisibleChanged: (String, Boolean) -> Unit,
    onContentFiltersChanged: (String, Boolean) -> Unit,
    onImmersiveModeChanged: (Boolean) -> Unit,
    onImmersiveBlurChanged: (Boolean) -> Unit,
    onForceLandscapeChanged: (Boolean) -> Unit,
    onExportAll: () -> Unit,
    onExitApp: () -> Unit,
) {
    val drawerBridge = (context as? UnifiedActivity)?.drawerNavBridge
    val navRegistry = remember(drawerBridge) { PaneNavRegistry(initialSignal = drawerBridge?.navSignal ?: -1) }
    navRegistry.controllerActive = drawerBridge?.controllerActive ?: false
    LaunchedEffect(navRegistry, drawerBridge?.navSignal) {
        navRegistry.processNav(drawerBridge?.navSignal ?: 0, drawerBridge?.navDir ?: 0)
    }
    LaunchedEffect(isOpen) { if (isOpen) navRegistry.reset() }

    ModalDrawerSheet(
        drawerShape = RectangleShape,
        drawerContainerColor = Color(0xFF12121B),
        drawerContentColor = TextPrimary,
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.width(minOf(324.dp, screenWidthDp() - 56.dp)),
    ) {
        CompositionLocalProvider(LocalPaneNav provides navRegistry) {
        Column(
            Modifier
                .fillMaxHeight()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {

            DrawerSwitchCard(
                label = stringResource(R.string.library_games_force_landscape),
                description = stringResource(R.string.library_games_force_landscape_description),
                checked = forceLandscape,
                onCheckedChange = onForceLandscapeChanged,
            )

            Spacer(Modifier.height(16.dp))

            // ── Layouts ──
            Text(
                stringResource(R.string.library_games_layouts_header),
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawerFilterButton(
                    label = "4-Grid",
                    checked = libraryLayoutMode == LibraryLayoutMode.GRID_4,
                    modifier = Modifier.weight(1f),
                ) { if (it) onLibraryLayoutSelected(LibraryLayoutMode.GRID_4) }
                DrawerFilterButton(
                    label = stringResource(R.string.library_games_layout_carousel),
                    checked = libraryLayoutMode == LibraryLayoutMode.CAROUSEL,
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                ) { if (it) onLibraryLayoutSelected(LibraryLayoutMode.CAROUSEL) }
                DrawerFilterButton(
                    label = stringResource(R.string.library_games_layout_list),
                    checked = libraryLayoutMode == LibraryLayoutMode.LIST,
                    modifier = Modifier.weight(1f),
                ) { if (it) onLibraryLayoutSelected(LibraryLayoutMode.LIST) }
            }

            Spacer(Modifier.height(16.dp))

            // ── View Options ──
            Text(
                stringResource(R.string.library_games_view_options_header),
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Spacer(Modifier.height(8.dp))

            DrawerSwitchCard(
                label = stringResource(R.string.library_games_immersive_mode),
                description = stringResource(R.string.library_games_immersive_mode_description),
                checked = immersiveMode,
                onCheckedChange = onImmersiveModeChanged,
            )

            AnimatedVisibility(visible = immersiveMode) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    DrawerSwitchCard(
                        label = stringResource(R.string.library_games_immersive_blur),
                        description = stringResource(R.string.library_games_immersive_blur_description),
                        checked = immersiveBlur,
                        onCheckedChange = onImmersiveBlurChanged,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            DrawerActionCard(
                icon = Icons.Outlined.IosShare,
                label = stringResource(R.string.shortcuts_export_to_frontend),
                onClick = onExportAll,
            )

            Spacer(Modifier.height(16.dp))

            // ── Stores ──
            Text(
                stringResource(R.string.stores_accounts_stores_header),
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawerFilterButton("Steam", storeVisible["steam"] == true, Modifier.weight(1f)) { onStoreVisibleChanged("steam", it) }
                DrawerFilterButton("Epic", storeVisible["epic"] == true, Modifier.weight(1f)) { onStoreVisibleChanged("epic", it) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawerFilterButton("GOG", storeVisible["gog"] == true, Modifier.weight(1f)) { onStoreVisibleChanged("gog", it) }
                DrawerFilterButton("itch.io", storeVisible["itch"] == true, Modifier.weight(1f)) { onStoreVisibleChanged("itch", it) }
            }

            Spacer(Modifier.height(16.dp))

            // ── Content Types ──
            Text(
                stringResource(R.string.settings_content_types_header),
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawerFilterButton("Games", contentFilters["games"] == true, Modifier.weight(1f)) { onContentFiltersChanged("games", it) }
                DrawerFilterButton("DLC", contentFilters["dlc"] == true, Modifier.weight(1f)) { onContentFiltersChanged("dlc", it) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawerFilterButton("Applications", contentFilters["applications"] == true, Modifier.weight(1f)) { onContentFiltersChanged("applications", it) }
                DrawerFilterButton("Tools", contentFilters["tools"] == true, Modifier.weight(1f)) { onContentFiltersChanged("tools", it) }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = TextSecondary.copy(alpha = 0.15f))
            Spacer(Modifier.height(16.dp))

            DrawerExitAppCard(onClick = onExitApp)
        }
        }
    }
}

@Composable
internal fun UnifiedActivity.DrawerExitAppCard(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(100),
        label = "exitAppCardScale",
    )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(12.dp))
                .background(DangerRed.copy(alpha = 0.16f))
                .border(1.dp, DangerRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .paneNavItem(cornerRadius = 12.dp, onActivate = onClick)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DangerRed.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ExitToApp,
                contentDescription = null,
                tint = Color(0xFFFFB4B4),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.common_ui_exit_app),
            color = Color(0xFFFFD6D6),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun UnifiedActivity.DrawerActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(100),
        label = "drawerActionCardScale",
    )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(12.dp))
                .background(Accent.copy(alpha = 0.14f))
                .border(1.dp, Accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .paneNavItem(cornerRadius = 12.dp, onActivate = onClick)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            color = TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun UnifiedActivity.DrawerFilterButton(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    onToggle: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bgColor by animateColorAsState(
        targetValue = if (checked) Accent.copy(alpha = 0.2f) else CardDark,
        animationSpec = tween(200),
        label = "filterBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) Accent else CardBorder,
        animationSpec = tween(200),
        label = "filterBorder",
    )
    val textColor by animateColorAsState(
        targetValue = if (checked) Accent else TextSecondary,
        animationSpec = tween(200),
        label = "filterText",
    )
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "filterScale",
    )

    Box(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(RoundedCornerShape(8.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .paneNavItem(cornerRadius = 8.dp, onActivate = { onToggle(!checked) })
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) { onToggle(!checked) }
                .padding(vertical = 10.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontSize = fontSize,
            color = textColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun UnifiedActivity.DrawerSwitchCard(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bgColor by animateColorAsState(
        targetValue = if (checked) Accent.copy(alpha = 0.18f) else CardDark,
        animationSpec = tween(200),
        label = "switchCardBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) Accent else CardBorder,
        animationSpec = tween(200),
        label = "switchCardBorder",
    )
    val labelColor by animateColorAsState(
        targetValue = if (checked) Accent else TextPrimary,
        animationSpec = tween(200),
        label = "switchCardLabel",
    )
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(120),
        label = "switchCardScale",
    )

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(RoundedCornerShape(10.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                .paneNavItem(cornerRadius = 10.dp, onActivate = { onCheckedChange(!checked) })
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) { onCheckedChange(!checked) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = labelColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 2,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.focusProperties { canFocus = false },
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Accent,
                    checkedBorderColor = Accent,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = CardDark,
                    uncheckedBorderColor = CardBorder,
                ),
        )
    }
}

@Composable
internal fun UnifiedActivity.AddCustomGameDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedExePath by remember { mutableStateOf<String?>(null) }
    var gameName by remember { mutableStateOf("") }
    var gameFolder by remember { mutableStateOf<String?>(null) }
    var retroSystem by remember { mutableStateOf<com.winlator.cmod.feature.retro.RetroSystem?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    var nameEditing by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val nameKeyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(nameEditing) {
        if (nameEditing) {
            runCatching { nameFocus.requestFocus() }
            nameKeyboard?.show()
        }
    }
    val registry = remember { PaneNavRegistry() }
    val addEnabled =
        selectedExePath != null && gameName.isNotBlank() && !isAdding &&
            (retroSystem != null || gameFolder != null)
    val doAdd: () -> Unit = {
        isAdding = true
        val chosenRetro = retroSystem
        scope.launch(Dispatchers.IO) {
            val added =
                if (chosenRetro != null) {
                    com.winlator.cmod.feature.retro.RetroShortcuts
                        .create(context, gameName.trim(), selectedExePath!!, chosenRetro)
                } else {
                    addCustomGame(context, gameName.trim(), selectedExePath!!, gameFolder!!)
                    true
                }
            withContext(Dispatchers.Main) {
                isAdding = false
                if (added) {
                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                        context,
                        "$gameName added!",
                        android.widget.Toast.LENGTH_SHORT,
                    )
                    onDismiss()
                } else {
                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                        context,
                        "Could not add game",
                        android.widget.Toast.LENGTH_SHORT,
                    )
                }
            }
        }
    }

    fun selectExecutable(path: String) {
        val detectedRetro = com.winlator.cmod.feature.retro.RetroSystems.detectForFile(path)
        val file = java.io.File(path)
        val launchable = file.extension.lowercase() in DirectoryPickerDialog.ExecutableExtensions
        if (!file.isFile || (!launchable && detectedRetro == null)) {
            com.winlator.cmod.shared.ui.toast.WinToast.show(
                context,
                R.string.common_ui_select_valid_exe_file,
                android.widget.Toast.LENGTH_SHORT,
            )
            return
        }

        selectedExePath = path
        retroSystem = detectedRetro
        gameFolder =
            if (detectedRetro != null) {
                java.io.File(path).parent
            } else {
                LibraryShortcutUtils.detectCustomGameFolder(path)
            }
        if (gameName.isBlank()) {
            gameName =
                if (detectedRetro != null) {
                    java.io
                        .File(path)
                        .nameWithoutExtension
                        .replace("_", " ")
                        .replace("-", " ")
                } else {
                    LibraryShortcutUtils.suggestCustomGameName(path)
                }
        }
    }

    val defaultDensity = LocalDensity.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(defaultDensity.density, fontScale = 1f),
            androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
            LocalPaneNav provides registry,
        ) {
            DialogPaneNav(registry, onDismiss = onDismiss, onStart = { if (addEnabled) doAdd() })
            Surface(
                modifier =
                    Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth(0.9f),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF141B24),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    // Title
                    Text(
                        stringResource(R.string.library_games_add_custom_game),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )

                    Spacer(Modifier.height(10.dp))

                    // Scrollable content area
                    Column(
                        modifier =
                            Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        // Pick EXE button
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .paneNavItem(
                                        cornerRadius = 12.dp,
                                        tapToSelect = true,
                                        isEntry = true,
                                        onActivate = {
                                            DirectoryPickerDialog.showFile(
                                                activity = this@AddCustomGameDialog,
                                                initialPath =
                                                    selectedExePath ?: gameFolder
                                                        ?: android.os.Environment
                                                            .getExternalStoragePublicDirectory(
                                                                android.os.Environment.DIRECTORY_DOWNLOADS,
                                                            ).absolutePath,
                                                title = getString(R.string.common_ui_select_exe),
                                                allowedExtensions = DirectoryPickerDialog.ExecutableExtensions +
                                                    com.winlator.cmod.feature.retro.RetroSystems.allExtensions,
                                                dimAmount = 0.5f,
                                                preserveBackdropBlur = true,
                                                extraRoots = driveRoots(includeInternal = true),
                                                onSelected = ::selectExecutable,
                                            )
                                        },
                                    ).padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                selectedExePath ?: "Select Executable or Console ROM",
                                color = if (selectedExePath == null) TextSecondary else TextPrimary,
                                maxLines = if (selectedExePath == null) 1 else Int.MAX_VALUE,
                                overflow = if (selectedExePath == null) TextOverflow.Ellipsis else TextOverflow.Visible,
                                fontSize = if (selectedExePath == null) 12.sp else 10.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        if (selectedExePath != null) {

                            Spacer(Modifier.height(8.dp))

                            // Game name text field — compact
                            OutlinedTextField(
                                value = gameName,
                                onValueChange = { gameName = it },
                                label = { Text(stringResource(R.string.library_games_game_name), fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .paneNavItem(cornerRadius = 10.dp, onActivate = { nameEditing = true })
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            nameEditing = true
                                        }
                                    }
                                    .focusRequester(nameFocus)
                                    .focusProperties { canFocus = nameEditing }
                                    .onFocusChanged { if (!it.isFocused) nameEditing = false }
                                    .controllerTextFieldEscape(),
                                textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                                colors =
                                    OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Accent,
                                        unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary,
                                        cursorColor = Accent,
                                        focusedLabelColor = Accent,
                                        unfocusedLabelColor = TextSecondary,
                                    ),
                                shape = RoundedCornerShape(10.dp),
                            )

                            Spacer(Modifier.height(8.dp))

                            if (retroSystem != null) {
                                val activeRetroSystem = retroSystem
                                var consoleMenuOpen by remember { mutableStateOf(false) }
                                Box {
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color.White.copy(alpha = 0.05f))
                                                .paneNavItem(
                                                    cornerRadius = 10.dp,
                                                    tapToSelect = true,
                                                    onActivate = { consoleMenuOpen = true },
                                                ).clickable { consoleMenuOpen = true }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Outlined.SportsEsports,
                                            contentDescription = null,
                                            tint = StatusOnline.copy(alpha = 0.7f),
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Console", color = TextSecondary, fontSize = 9.sp)
                                            Text(
                                                activeRetroSystem?.displayName ?: "",
                                                color = TextPrimary,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Icon(
                                            Icons.Outlined.Edit,
                                            contentDescription = stringResource(R.string.common_ui_change),
                                            tint = Accent,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = consoleMenuOpen,
                                        onDismissRequest = { consoleMenuOpen = false },
                                        containerColor = Color(0xFF1C232E),
                                    ) {
                                        com.winlator.cmod.feature.retro.RetroSystems.ALL.forEach { candidate ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        candidate.displayName,
                                                        color =
                                                            if (candidate.id == activeRetroSystem?.id) Accent else TextPrimary,
                                                        fontSize = 12.sp,
                                                    )
                                                },
                                                onClick = {
                                                    retroSystem = candidate
                                                    consoleMenuOpen = false
                                                },
                                            )
                                        }
                                    }
                                }
                            } else {
                            // Game folder — single compact row
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White.copy(alpha = 0.05f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.Folder,
                                    contentDescription = null,
                                    tint = StatusOnline.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.library_games_game_folder_mapped_drive),
                                        color = TextSecondary,
                                        fontSize = 9.sp,
                                    )
                                    Text(
                                        gameFolder ?: stringResource(R.string.common_ui_auto_detected),
                                        color = if (gameFolder != null) TextPrimary else TextSecondary,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                val openFolderPicker = {
                                    if (ensureAllFilesAccessForImports(context)) {
                                        DirectoryPickerDialog.show(
                                            activity = this@AddCustomGameDialog,
                                            initialPath = gameFolder,
                                            title = getString(R.string.common_ui_select_folder),
                                            dimAmount = 0.5f,
                                            preserveBackdropBlur = true,
                                            extraRoots = driveRoots(includeInternal = true),
                                        ) { path -> gameFolder = path }
                                    }
                                }
                                IconButton(
                                    onClick = openFolderPicker,
                                    modifier =
                                        Modifier.size(28.dp).paneNavItem(
                                            cornerRadius = 8.dp,
                                            onActivate = openFolderPicker,
                                        ),
                                ) {
                                    Icon(
                                        Icons.Outlined.Edit,
                                        contentDescription = stringResource(R.string.common_ui_change),
                                        tint = Accent,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TextSecondary.copy(alpha = 0.3f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier =
                                Modifier.height(34.dp).widthIn(min = 72.dp)
                                    .paneNavItem(cornerRadius = 10.dp, onActivate = onDismiss),
                        ) {
                            Text(stringResource(R.string.common_ui_cancel), fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = doAdd,
                            enabled = addEnabled,
                            shape = RoundedCornerShape(10.dp),
                            border =
                                androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (addEnabled) Accent.copy(alpha = 0.5f) else TextSecondary.copy(alpha = 0.2f),
                                ),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier =
                                Modifier.height(34.dp).widthIn(min = 72.dp)
                                    .paneNavItem(cornerRadius = 10.dp, onActivate = { if (addEnabled) doAdd() }),
                        ) {
                            if (isAdding) {
                                CircularProgressIndicator(color = Accent, modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.common_ui_add), fontWeight = FontWeight.Medium, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun UnifiedActivity.ensureAllFilesAccessForImports(context: android.content.Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || android.os.Environment.isExternalStorageManager()) {
        return true
    }

    com.winlator.cmod.shared.ui.toast.WinToast.show(
        context,
        "Grant All files access to browse Downloads directly.",
        android.widget.Toast.LENGTH_LONG,
    )

    val intent =
        android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = android.net.Uri.parse("package:$packageName")
        }
    startActivity(intent)
    return false
}

internal fun UnifiedActivity.driveRoots(includeInternal: Boolean): List<DirectoryPickerDialog.ManagedRoot> {
    val imagefsRoot =
        com.winlator.cmod.runtime.display.environment.ImageFs.find(this).getRootDir()
    val roots =
        mutableListOf(
            DirectoryPickerDialog.ManagedRoot("C:", java.io.File(imagefsRoot, "home").absolutePath),
            DirectoryPickerDialog.ManagedRoot("Z:", imagefsRoot.absolutePath),
            DirectoryPickerDialog.ManagedRoot(
                "D:",
                android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    .absolutePath,
            ),
        )
    if (includeInternal) {
        roots +=
            DirectoryPickerDialog.ManagedRoot(
                "Internal",
                android.os.Environment.getExternalStorageDirectory().absolutePath,
            )
    }
    return roots
}

internal suspend fun scrapeCustomGameArtwork(
    context: android.content.Context,
    gameName: String,
    shortcutUuid: String,
    container: Container,
    shortcutFile: java.io.File
) {
    withContext(Dispatchers.Main) {
        com.winlator.cmod.shared.ui.toast.WinToast.show(context, R.string.library_games_scraping_artwork, android.widget.Toast.LENGTH_LONG)
    }
    val shortcut = Shortcut(container, shortcutFile)
    val artworkInfo = SteamArtworkScraper(context).getGameArtwork(gameName)
    val dir = java.io.File(context.filesDir, "library_view_artwork")
    if (!dir.exists()) dir.mkdirs()
    var saved = false
    artworkInfo.forEach { (slotSuffix, file) ->
        val librarySlot =
            LibraryShortcutArtwork.LibraryArtworkSlot.entries.find { it.fileSuffix == slotSuffix }
        val bitmap =
            if (librarySlot != null && file.isFile) {
                com.winlator.cmod.shared.android.ImageUtils.getBitmapFromUri(context, file.toUri(), 1024)
            } else {
                null
            }
        if (librarySlot != null && bitmap != null) {
            val outputFile = java.io.File(dir, "${shortcutUuid}_${librarySlot.fileSuffix}.png")
            if (com.winlator.cmod.shared.io.FileUtils.saveBitmapToFile(bitmap, outputFile)) {
                shortcut.putExtra(librarySlot.extraKey, outputFile.absolutePath)
                saved = true
            }
        }
        file.delete()
    }
    if (saved) shortcut.saveData()
    withContext(Dispatchers.Main) {
        com.winlator.cmod.shared.ui.toast.WinToast.show(
            context,
            if (saved) R.string.common_ui_done else R.string.common_ui_failed,
            android.widget.Toast.LENGTH_LONG,
        )
        if (saved) {
            com.winlator.cmod.app.PluviaApp.events.emit(com.winlator.cmod.feature.stores.steam.events.AndroidEvent.LibraryArtworkChanged)
        }
    }
}

internal fun addCustomGame(
    context: android.content.Context,
    name: String,
    exePath: String,
    gameFolderPath: String,
    coverArt: java.io.File? = null,
) {
    val containerManager = ContainerManager(context)
    val container = SetupWizardActivity.getPreferredGameContainer(context, containerManager)
    if (container == null) {
        SetupWizardActivity.promptToInstallWineOrCreateContainer(context)
        return
    }

    val exeFile = java.io.File(exePath)
    container.drives =
        com.winlator.cmod.runtime.wine.WineUtils
            .normalizePersistentDrives(context, container.drives ?: Container.DEFAULT_DRIVES, false)
    val windowsPath =
        com.winlator.cmod.runtime.wine.WineUtils
            .resolveGameExeWindowsPath(container, "CUSTOM", gameFolderPath, exeFile.absolutePath)
    val execCmd = "wine \"$windowsPath\""

    val desktopDir = container.getDesktopDir()
    if (!desktopDir.exists()) desktopDir.mkdirs()
    val safeName = name.replace("/", "_").replace("\\", "_")
    val shortcutFile = java.io.File(desktopDir, "$safeName.desktop")
    val shortcutUuid = java.util.UUID.randomUUID().toString()
    val iconOutFile = LibraryShortcutArtwork.buildManagedCustomGameArtworkFile(context, shortcutUuid)
    val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    val extractedArtworkPath =
        try {
            if (coverArt != null && coverArt.isFile) {
                coverArt.copyTo(iconOutFile, overwrite = true)
                iconOutFile.absolutePath
            } else if (PeIconExtractor.extractAndSave(java.io.File(exePath), iconOutFile)) {
                iconOutFile.absolutePath
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    val content = StringBuilder()
    content.append("[Desktop Entry]\n")
    content.append("Type=Application\n")
    content.append("Name=$name\n")
    content.append("Exec=$execCmd\n")
    content.append("Icon=custom_game\n")
    content.append("\n[Extra Data]\n")
    content.append("game_source=CUSTOM\n")
    content.append("custom_name=$name\n")
    content.append("custom_exe=$exePath\n")
    content.append("custom_game_folder=$gameFolderPath\n")
    content.append("uuid=$shortcutUuid\n")
    extractedArtworkPath?.let { content.append("customCoverArtPath=$it\n") }
    content.append("container_id=${container.id}\n")
    content.append("use_container_defaults=1\n")
    com.winlator.cmod.shared.io.FileUtils
        .writeString(shortcutFile, content.toString())
    container.saveData()
    if (coverArt == null && preferences.getBoolean("enable_auto_scraping", false)) {
        CoroutineScope(Dispatchers.IO).launch {
            scrapeCustomGameArtwork(context, name, shortcutUuid, container, shortcutFile)
        }
    }
}

internal fun removeCustomGame(
    context: android.content.Context,
    gameFolderPath: String,
): Boolean {
    if (gameFolderPath.isBlank()) return false
    val target = java.io.File(gameFolderPath).absolutePath.trimEnd('/')
    val shortcuts = runCatching { ContainerManager(context).loadShortcuts() }.getOrDefault(emptyList())
    val matches =
        shortcuts.filter { shortcut ->
            shortcut.getExtra("game_source") == "CUSTOM" &&
                shortcut.getExtra("custom_game_folder").trimEnd('/') == target
        }
    matches.forEach { LibraryShortcutUtils.deleteShortcutArtifacts(context, it) }
    if (matches.isNotEmpty()) UnifiedActivity.refreshLibrary()
    return matches.isNotEmpty()
}

@Composable
internal fun UnifiedActivity.CustomPathWarningDialog(
    onDismiss: () -> Unit,
    onProceed: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CardDark,
            modifier = Modifier.padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.stores_accounts_custom_download_path),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.stores_accounts_custom_download_path_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_ui_close), color = TextSecondary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onProceed,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.common_ui_proceed))
                    }
                }
            }
        }
    }
}

@Composable
internal fun UnifiedActivity.rememberControllerConnectionState(): ControllerConnectionState {
    val context = LocalContext.current
    val inputManager = remember(context) { context.getSystemService(InputManager::class.java) }
    var controllerState by remember { mutableStateOf(ControllerConnectionState()) }

    DisposableEffect(inputManager) {
        fun refreshState() {
            controllerState =
                ControllerConnectionState(
                    isConnected = ControllerHelper.isControllerConnected(),
                    isPlayStation = ControllerHelper.isPlayStationController(),
                )
        }

        val listener =
            object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) = refreshState()

                override fun onInputDeviceRemoved(deviceId: Int) = refreshState()

                override fun onInputDeviceChanged(deviceId: Int) = refreshState()
            }

        refreshState()
        inputManager?.registerInputDeviceListener(listener, null)
        onDispose {
            inputManager?.unregisterInputDeviceListener(listener)
        }
    }

    return controllerState
}
