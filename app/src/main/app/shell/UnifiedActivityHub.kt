package com.winlator.cmod.app.shell
import com.winlator.cmod.app.shell.UnifiedActivity.TabDef

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
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
import com.winlator.cmod.feature.stores.itch.ui.auth.ItchLoginActivity
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
import com.winlator.cmod.shared.ui.layout.isPortraitLayout
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

// Main hub scaffold + top bar + glasses sheet + library carousel, split out of UnifiedActivity.kt (behavior-identical).

private val StoreTabKeys = setOf("steam", "epic", "gog", "itch")
private val HeaderCollapseTriggerDistance = 24.dp
private const val HeaderRevealFraction = 0.5f

@Composable
internal fun UnifiedActivity.UnifiedHub() {
    val horizontalNavigationInsets =
        WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
    val initialLibraryLayoutMode = startupLibraryLayoutMode
    val initialStoreVisible = startupStoreVisible ?: mapOf("steam" to true, "epic" to true, "gog" to true, "itch" to true)
    val initialContentFilters = startupContentFilters ?: mapOf("games" to true, "dlc" to false, "applications" to false, "tools" to false)
    if (!startupBootstrapReady || initialLibraryLayoutMode == null) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(BgDark)
                    .windowInsetsPadding(horizontalNavigationInsets),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(color = Accent)
                Text(
                    text = stringResource(R.string.common_ui_app_name),
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        return
    }

    val storeVisible = remember { mutableStateMapOf(*initialStoreVisible.entries.map { it.key to it.value }.toTypedArray()) }
    var showAddCustomGame by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var searchQueryTfv by remember { mutableStateOf(TextFieldValue("")) }
    val searchQuery = searchQueryTfv.text
    var localLibraryRefreshKey by remember { mutableIntStateOf(0) }
    var shortcutDataRefreshKey by remember { mutableIntStateOf(0) }
    var iconRefreshKey by remember { mutableIntStateOf(0) }

    val currentRefreshSignal = this@UnifiedHub.libraryRefreshSignal
    val libraryRefreshKey = currentRefreshSignal + localLibraryRefreshKey
    val shortcutRefreshKey = libraryRefreshKey + shortcutDataRefreshKey
    val playtimeRefreshKey = this@UnifiedHub.libraryPlaytimeRefreshSignal

    val contentFilters = remember { mutableStateMapOf(*initialContentFilters.entries.map { it.key to it.value }.toTypedArray()) }
    var libraryLayoutMode by remember {
        mutableStateOf(
            runCatching { LibraryLayoutMode.valueOf(PrefManager.libraryLayoutMode) }
                .getOrElse { initialLibraryLayoutMode },
        )
    }
    var immersiveMode by remember { mutableStateOf(PrefManager.libraryImmersiveMode) }
    var immersiveBlur by remember { mutableStateOf(PrefManager.libraryImmersiveBlur) }
    var forceLandscape by remember { mutableStateOf(PrefManager.libraryForceLandscape) }
    val tabs = remember(storeVisible.toMap()) { buildTabs(storeVisible) }
    var selectedIdx by rememberSaveable { mutableIntStateOf(0) }
    var selectedDownloadId by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    LaunchedEffect(drawerState.isOpen) {
        drawerOpen = drawerState.isOpen
        if (!drawerState.isOpen) drawerNavBridge.controllerActive = false
    }
    val isLoggedIn by SteamService.isLoggedInFlow.collectAsState()
    val chatServiceEnabled by SteamService.chatServiceEnabledFlow.collectAsState()
    val isEpicLoggedIn by EpicAuthManager.isLoggedInFlow.collectAsState()
    val isGogLoggedIn by GOGAuthManager.isLoggedInFlow.collectAsState()
    val steamApps by db.steamAppDao().getAllOwnedApps().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val persona by SteamService.instance?.localPersona?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val scope = rememberCoroutineScope()
    val rightDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val friends by SteamService.instance?.friendsList?.collectAsState()
        ?: remember { mutableStateOf(emptyList<com.winlator.cmod.feature.stores.steam.data.SteamFriendEntry>()) }
    var chatFriend by remember { mutableStateOf<com.winlator.cmod.feature.stores.steam.data.SteamFriendEntry?>(null) }
    val friendsDrawerOpen = rightDrawerState.isOpen
    LaunchedEffect(rightDrawerState.isOpen) {
        rightDrawerOpen = rightDrawerState.isOpen
        if (!rightDrawerState.isOpen) friendsDrawerNavBridge.controllerActive = false
    }
    LaunchedEffect(Unit) {
        (context as? UnifiedActivity)?.openFriendsSignal?.collect {
            if (rightDrawerState.isOpen) rightDrawerState.close() else rightDrawerState.open()
        }
    }
    var installedFriendGameIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    LaunchedEffect(friends) {
        val ids = friends.map { it.gameAppId }.filter { it > 0 }.distinct()
        installedFriendGameIds =
            withContext(Dispatchers.IO) { ids.filter { SteamService.isAppInstalled(it) }.toSet() }
    }
    LaunchedEffect(isLoggedIn, chatServiceEnabled) {
        if (isLoggedIn && chatServiceEnabled) {
            while (true) {
                runCatching { SteamService.instance?.refreshFriends() }
                kotlinx.coroutines.delay(30_000L)
            }
        }
    }
    LaunchedEffect(isLoggedIn, friendsDrawerOpen, chatServiceEnabled) {
        if (isLoggedIn && friendsDrawerOpen && chatServiceEnabled) {
            while (true) {
                runCatching { SteamService.instance?.syncFriendsPresence() }
                kotlinx.coroutines.delay(5_000L)
            }
        }
    }
    LaunchedEffect(isLoggedIn, chatServiceEnabled) {
        if (isLoggedIn && chatServiceEnabled) {
            runCatching { com.winlator.cmod.feature.stores.steam.chat.ChatOverlayService.start(context) }
        }
    }

    val epicApps by db.epicGameDao().getAll().collectAsState(initial = emptyList())
    val gogApps by db.gogGameDao().getAll().collectAsState(initial = emptyList())

    val controllerState = rememberControllerConnectionState()
    val isControllerConnected = controllerState.isConnected
    val isPS = controllerState.isPlayStation
    val isLibraryTab = tabs.getOrNull(selectedIdx)?.key == "library"

    val libraryRefreshListener =
        remember {
            object : EventDispatcher.JavaEventListener {
                override fun onEvent(event: Any) {
                    when (event) {
                        is AndroidEvent.LibraryInstallStatusChanged -> {
                            localLibraryRefreshKey++
                            shortcutDataRefreshKey++
                            iconRefreshKey++
                        }
                        is AndroidEvent.LibraryArtworkChanged -> {
                            shortcutDataRefreshKey++
                            iconRefreshKey++
                        }
                    }
                }
            }
        }
    DisposableEffect(libraryRefreshListener) {
        PluviaApp.events.onJava(AndroidEvent.LibraryInstallStatusChanged::class, libraryRefreshListener)
        PluviaApp.events.onJava(AndroidEvent.LibraryArtworkChanged::class, libraryRefreshListener)
        onDispose {
            PluviaApp.events.offJava(AndroidEvent.LibraryInstallStatusChanged::class, libraryRefreshListener)
            PluviaApp.events.offJava(AndroidEvent.LibraryArtworkChanged::class, libraryRefreshListener)
        }
    }

    LaunchedEffect(isEpicLoggedIn) {
        if (isEpicLoggedIn) {
            EpicService.start(context)
        }
    }

    LaunchedEffect(isGogLoggedIn) {
        if (isGogLoggedIn) {
            GOGService.start(context)
        }
    }

    val epicLoginLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val code = result.data?.getStringExtra(EpicOAuthActivity.EXTRA_AUTH_CODE)
                if (code != null) {
                    scope.launch {
                        val authResult = EpicAuthManager.authenticateWithCode(context, code)
                        if (authResult.isSuccess) {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                R.string.stores_accounts_logged_in_epic,
                                android.widget.Toast.LENGTH_SHORT,
                            )
                        } else {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                getString(R.string.stores_accounts_epic_login_failed, authResult.exceptionOrNull()?.message),
                                android.widget.Toast.LENGTH_LONG,
                            )
                        }
                    }
                }
            }
        }

    val gogLoginLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val code = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_AUTH_CODE)
                if (!code.isNullOrBlank()) {
                    scope.launch {
                        val authResult = GOGAuthManager.authenticateWithCode(context, code)
                        if (authResult.isSuccess) {
                            GOGService.start(context)
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                R.string.stores_accounts_logged_in_gog,
                                android.widget.Toast.LENGTH_SHORT,
                            )
                        } else {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                getString(R.string.stores_accounts_gog_login_failed, authResult.exceptionOrNull()?.message),
                                android.widget.Toast.LENGTH_LONG,
                            )
                        }
                    }
                }
            }
        }

    var itchAuthRefreshKey by remember { mutableIntStateOf(0) }
    val itchLoginLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            itchAuthRefreshKey++
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                com.winlator.cmod.shared.ui.toast.WinToast.show(
                    context,
                    R.string.itch_store_signed_in,
                    android.widget.Toast.LENGTH_SHORT,
                )
            }
        }

    val filteredSteamApps =
        remember(steamApps, contentFilters.toMap()) {
            steamApps.filter { app ->
                when (app.type) {
                    com.winlator.cmod.feature.stores.steam.enums.AppType.game -> contentFilters["games"] == true
                    com.winlator.cmod.feature.stores.steam.enums.AppType.demo -> contentFilters["games"] == true
                    com.winlator.cmod.feature.stores.steam.enums.AppType.dlc -> contentFilters["dlc"] == true
                    com.winlator.cmod.feature.stores.steam.enums.AppType.application -> contentFilters["applications"] == true
                    com.winlator.cmod.feature.stores.steam.enums.AppType.tool -> contentFilters["tools"] == true
                    com.winlator.cmod.feature.stores.steam.enums.AppType.config -> contentFilters["tools"] == true
                    else -> contentFilters["games"] == true
                }
            }
        }

    var globalSettingsApp by remember { mutableStateOf<SteamApp?>(null) }
    var globalSettingsGogGame by remember { mutableStateOf<GOGGame?>(null) }

    LaunchedEffect(tabs.size) { if (selectedIdx >= tabs.size) selectedIdx = 0 }
    LaunchedEffect(isLoggedIn, persona) {
        if (isLoggedIn && persona == null) {
            SteamService.requestUserPersona()
        }
    }

    val activity = LocalContext.current as? UnifiedActivity

    LaunchedEffect(tabs) {
        activity?.keyEventFlow?.collect { event ->
            val key = tabs.getOrNull(selectedIdx)?.key ?: "library"
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_BUTTON_L1 -> {
                    selectedIdx = if (selectedIdx > 0) selectedIdx - 1 else tabs.size - 1
                }

                android.view.KeyEvent.KEYCODE_BUTTON_R1 -> {
                    selectedIdx = (selectedIdx + 1) % tabs.size
                }

                android.view.KeyEvent.KEYCODE_BUTTON_START -> {
                    navigateToSettings(SettingsNavItem.STORES)
                }

                android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    if (key != "downloads") {
                        if (drawerState.isOpen) drawerState.close() else drawerState.open()
                    }
                }

                android.view.KeyEvent.KEYCODE_BUTTON_X -> {
                    if (key == "library" && (selectedSteamAppId != 0 || selectedGogGameId.isNotEmpty())) {
                        activity?.openHeroForFocusedSignal?.tryEmit(Unit)
                    }
                }

                android.view.KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                    if (key == "library") {
                        activity?.openSearchSignal?.tryEmit(Unit)
                    }
                }

                android.view.KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                    if (key == "library") {
                        showAddCustomGame = true
                    }
                }

                android.view.KeyEvent.KEYCODE_BUTTON_B -> {
                    if (chatFriend != null) {
                        chatFriend = null
                    } else if (rightDrawerState.isOpen) {
                        rightDrawerState.close()
                    } else if (drawerState.isOpen) {
                        drawerState.close()
                    } else if (globalSettingsApp != null) {
                        globalSettingsApp = null
                    } else if (globalSettingsGogGame != null) {
                        globalSettingsGogGame = null
                    } else if (showAddCustomGame) {
                        showAddCustomGame = false
                    } else {
                        showExitDialog = true
                    }
                }

                android.view.KeyEvent.KEYCODE_BUTTON_Y -> {
                    if (key == "library" && (selectedSteamAppId != 0 || selectedGogGameId.isNotEmpty())) {
                        if (selectedLibrarySource == "GOG") {
                            globalSettingsGogGame = gogApps.find { it.id == selectedGogGameId }
                            return@collect
                        }
                        val isCustom = selectedSteamAppId < 0
                        val epicId = if (selectedSteamAppId >= 2000000000) selectedSteamAppId - 2000000000 else 0

                        globalSettingsApp = (
                            steamApps.find { it.id == selectedSteamAppId }
                                ?: if (isCustom) {
                                    SteamApp(id = selectedSteamAppId, name = selectedSteamAppName, developer = "Custom")
                                } else if (epicId > 0) {
                                    val epic = epicApps.find { it.id == epicId }
                                    SteamApp(
                                        id = selectedSteamAppId,
                                        name = selectedSteamAppName,
                                        developer = epic?.developer ?: "Epic Games",
                                        gameDir = epic?.installPath ?: "",
                                    )
                                } else {
                                    null
                                }
                        )
                    }
                }

                android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_DPAD_CENTER -> {
                    if (key == "library" && (selectedSteamAppId != 0 || selectedGogGameId.isNotEmpty())) {
                        val isCustom = selectedSteamAppId < 0
                        val epicId = if (selectedSteamAppId >= 2000000000) selectedSteamAppId - 2000000000 else 0
                        val containerManager = ContainerManager(context)
                        if (isCustom) {
                            launchCustomGame(context, containerManager, selectedSteamAppName)
                        } else if (selectedLibrarySource == "GOG") {
                            gogApps.find { it.id == selectedGogGameId }?.let {
                                launchGogGame(context, containerManager, it)
                            }
                        } else if (epicId > 0) {
                            val epic = epicApps.find { it.id == epicId }
                            if (epic != null && epic.isInstalled) {
                                val dummyApp =
                                    SteamApp(id = selectedSteamAppId, name = selectedSteamAppName, gameDir = epic.installPath)
                                launchSteamGame(context, containerManager, dummyApp)
                            }
                        } else {
                            val steam = steamApps.find { it.id == selectedSteamAppId }
                            if (steam != null) {
                                launchSteamGame(context, containerManager, steam)
                            }
                        }
                    } else if (key != "library" && key != "downloads") {
                        storeItemClickCallback?.invoke(storeFocusIndex.value)
                    }
                }

            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl,
    ) {
    ModalNavigationDrawer(
        drawerState = rightDrawerState,
        drawerContent = {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
            ) {
                com.winlator.cmod.feature.stores.steam.friends.FriendsDrawerContent(
                    isOpen = rightDrawerState.isOpen,
                    self = persona ?: com.winlator.cmod.feature.stores.steam.data.SteamFriend(),
                    friends = friends,
                    installedGameIds = installedFriendGameIds,
                    chatEnabled = chatServiceEnabled,
                    onSetState = { st -> scope.launch { SteamService.setPersonaState(st) } },
                    onOpenChat = { f -> chatFriend = f; scope.launch { rightDrawerState.close() } },
                    onJoinGame = { f ->
                        scope.launch { rightDrawerState.close() }
                        scope.launch {
                            val app = withContext(Dispatchers.IO) { SteamService.getAppInfoOf(f.gameAppId) }
                            val installed = withContext(Dispatchers.IO) { SteamService.getInstalledApp(f.gameAppId) }
                            val label = f.gameName.ifBlank { context.getString(R.string.steam_join_the_game) }
                            if (app != null && installed != null) {
                                android.widget.Toast.makeText(
                                    context, context.getString(R.string.steam_join_joining, f.name, label), android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                launchSteamGame(context, ContainerManager(context), app, f.connectString)
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    if (app != null) context.getString(R.string.steam_join_install, label, f.name)
                                    else context.getString(R.string.steam_join_not_owned, label),
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    onPlayGame = { f ->
                        scope.launch { rightDrawerState.close() }
                        scope.launch {
                            val app = withContext(Dispatchers.IO) { SteamService.getAppInfoOf(f.gameAppId) }
                            if (app != null) {
                                launchSteamGame(context, ContainerManager(context), app, null)
                            }
                        }
                    },
                )
            }
        },
        scrimColor = Color.Black.copy(alpha = 0.5f),
        gesturesEnabled = rightDrawerState.isOpen,
    ) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
    ) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                persona = persona,
                isOpen = drawerState.isOpen,
                context = context,
                scope = scope,
                storeVisible = storeVisible,
                contentFilters = contentFilters,
                libraryLayoutMode = libraryLayoutMode,
                immersiveMode = immersiveMode,
                immersiveBlur = immersiveBlur,
                forceLandscape = forceLandscape,
                onLibraryLayoutSelected = {
                    libraryLayoutMode = it
                    PrefManager.libraryLayoutMode = it.name
                },
                onStoreVisibleChanged = { key, value ->
                    storeVisible[key] = value
                    PrefManager.libraryStoreVisible = storeVisible.entries.filter { it.value }.joinToString(",") { it.key }
                },
                onContentFiltersChanged = { key, value ->
                    contentFilters[key] = value
                    PrefManager.libraryContentFilters = contentFilters.entries.filter { it.value }.joinToString(",") { it.key }
                },
                onImmersiveModeChanged = {
                    immersiveMode = it
                    PrefManager.libraryImmersiveMode = it
                },
                onImmersiveBlurChanged = {
                    immersiveBlur = it
                    PrefManager.libraryImmersiveBlur = it
                },
                onForceLandscapeChanged = {
                    forceLandscape = it
                    com.winlator.cmod.shared.android.OrientationLock.forceLandscape = it
                },
                onExportAll = {
                    scope.launch {
                        val count =
                            withContext(Dispatchers.IO) {
                                com.winlator.cmod.feature.shortcuts.FrontendExporter.exportAll(context)
                            }
                        val dir = com.winlator.cmod.feature.shortcuts.FrontendExporter.resolveExportDir(context)
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            if (count > 0) {
                                context.getString(R.string.shortcuts_export_all_done, count, dir?.path ?: "")
                            } else {
                                context.getString(R.string.shortcuts_export_all_none)
                            },
                        )
                    }
                },
                onExitApp = {
                    AppTerminationHelper.exitApplication(this@UnifiedHub, "hub_drawer_exit")
                },
            )
        },
        scrimColor = Color.Black.copy(alpha = 0.5f),
        gesturesEnabled = drawerState.isOpen,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(BgDark)
                .windowInsetsPadding(horizontalNavigationInsets),
        ) {
            val currentTabKeyForImmersive = tabs.getOrNull(selectedIdx)?.key ?: "library"
            val immersiveActive = immersiveMode && currentTabKeyForImmersive == "library"
            DisposableEffect(immersiveActive) {
                applyImmersiveSystemBars(immersiveActive)
                onDispose { applyImmersiveSystemBars(false) }
            }
            if (immersiveMode && currentTabKeyForImmersive == "library") {
                val immersiveModel by immersiveBackgroundRef.collectAsState()
                val immersiveRequest =
                    remember(immersiveModel, immersiveBlur, context) {
                        val builder = ImageRequest.Builder(context).data(immersiveModel)
                        (immersiveModel as? java.io.File)?.takeIf { it.isFile }?.let { file ->
                            // Custom uploads can be overwritten in place.
                            val key = "library_immersive_bg:${file.absolutePath}:${file.lastModified()}"
                            builder.memoryCacheKey(if (immersiveBlur) "$key:blur" else key).diskCacheKey(key)
                        }
                        if (immersiveBlur) {
                            // Blur baked into the bitmap at decode (quarter-res + radius 2 ≈ 8px on screen), so drawing costs the same as a plain image.
                            val dm = context.resources.displayMetrics
                            builder
                                .size(dm.widthPixels / 4, dm.heightPixels / 4)
                                .scale(coil.size.Scale.FILL)
                                .transformations(BoxBlurTransformation(radius = 2))
                        }
                        builder.crossfade(400).build()
                    }
                AnimatedVisibility(
                    visible = immersiveModel != null,
                    enter = fadeIn(tween(400)),
                    exit = fadeOut(tween(400)),
                    modifier = Modifier.matchParentSize(),
                ) {
                    Box(Modifier.matchParentSize()) {
                        AsyncImage(
                            model = immersiveRequest,
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop,
                        )
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(BgDark.copy(alpha = 0.5f)),
                        )
                    }
                }
            }
            val scaffoldContainer = if (immersiveMode && currentTabKeyForImmersive == "library") Color.Transparent else BgDark
            val openFileManager: () -> Unit = {
                val internalPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                val managedRoots = driveRoots(includeInternal = true)
                val containerManager = com.winlator.cmod.runtime.container.ContainerManager(context)
                val containers =
                    containerManager.getContainers().map {
                        DirectoryPickerDialog.ManagedContainer(it.id, it.getName())
                    }
                DirectoryPickerDialog.showManager(
                    activity = this@UnifiedHub,
                    initialPath = internalPath,
                    managedRoots = managedRoots,
                    containers = containers,
                    onRunFile = { exePath, containerId ->
                        val container = containerManager.getContainerById(containerId)
                        if (container != null) {
                            val winePath =
                                com.winlator.cmod.runtime.wine.WineUtils
                                    .hostPathToMappedWinePath(container, exePath)
                            startActivity(
                                android.content.Intent(
                                    this@UnifiedHub,
                                    com.winlator.cmod.runtime.display.XServerDisplayActivity::class.java,
                                ).apply {
                                    putExtra("container_id", container.id)
                                    putExtra("boot_exe", winePath)
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }
                    },
                    onCreateShortcut = { exePath ->
                        val exeFile = java.io.File(exePath)
                        addCustomGame(
                            context,
                            exeFile.nameWithoutExtension,
                            exePath,
                            exeFile.parent ?: exePath,
                        )
                        localLibraryRefreshKey++
                    },
                )
            }
            val activeTabKey = tabs.getOrNull(selectedIdx)?.key ?: "library"
            val headerCollapsible = activeTabKey in StoreTabKeys
            val headerCollapseTriggerPx = with(LocalDensity.current) { HeaderCollapseTriggerDistance.toPx() }
            var headerHeightPx by remember { mutableIntStateOf(0) }
            var headerVisible by remember { mutableStateOf(true) }
            val headerOffsetPx by animateFloatAsState(
                targetValue =
                    if (!headerCollapsible || headerVisible || headerHeightPx == 0) {
                        0f
                    } else {
                        -headerHeightPx.toFloat()
                    },
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "headerOffset",
            )
            LaunchedEffect(activeTabKey) { headerVisible = true }
            LaunchedEffect(headerVisible, headerCollapsible) {
                storeHeaderVisible.value = !headerCollapsible || headerVisible
            }
            val headerScrollConnection =
                remember(headerCollapsible, headerCollapseTriggerPx) {
                    object : NestedScrollConnection {
                        private var downDistance = 0f
                        private var upDistance = 0f

                        override fun onPreScroll(
                            available: Offset,
                            source: NestedScrollSource,
                        ): Offset {
                            if (!headerCollapsible || headerHeightPx == 0) return Offset.Zero
                            val delta = available.y
                            if (delta < 0f) {
                                upDistance = 0f
                                downDistance -= delta
                                if (downDistance > headerCollapseTriggerPx) headerVisible = false
                            } else if (delta > 0f) {
                                downDistance = 0f
                                upDistance += delta
                                if (upDistance > headerHeightPx * HeaderRevealFraction) headerVisible = true
                            }
                            return Offset.Zero
                        }

                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource,
                        ): Offset {
                            if (headerCollapsible && available.y > 0f) {
                                downDistance = 0f
                                upDistance = 0f
                                headerVisible = true
                            }
                            return Offset.Zero
                        }
                    }
                }

            Scaffold(
                modifier = Modifier.nestedScroll(headerScrollConnection),
                containerColor = scaffoldContainer,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    Box(
                        Modifier
                            .onSizeChanged { headerHeightPx = it.height }
                            .graphicsLayer { translationY = headerOffsetPx },
                    ) {
                    TopBar(tabs, selectedIdx, {
                        selectedIdx = it
                    }, persona, context, scope, isControllerConnected, isPS, isLibraryTab, searchQueryTfv, {
                        searchQueryTfv =
                            it
                    }, onFilterClicked = { scope.launch { drawerState.open() } }, onFriendsClicked = { scope.launch { rightDrawerState.open() } }) {
                        if (selectedLibrarySource == "GOG") {
                            globalSettingsGogGame = gogApps.find { it.id == selectedGogGameId }
                        } else {
                            globalSettingsApp = (
                                steamApps.find { it.id == selectedSteamAppId }
                                    ?: if (selectedSteamAppId < 0) {
                                        SteamApp(
                                            id = selectedSteamAppId,
                                            name = selectedSteamAppName,
                                            developer = "Custom",
                                        )
                                    } else if (selectedSteamAppId >= 2000000000) {
                                        val epicId = selectedSteamAppId - 2000000000
                                        val epic = epicApps.find { it.id == epicId }
                                        SteamApp(
                                            id = selectedSteamAppId,
                                            name = selectedSteamAppName,
                                            developer = epic?.developer ?: "Epic Games",
                                            gameDir = epic?.installPath ?: "",
                                        )
                                    } else {
                                        null
                                    }
                            )
                        }
                    }
                    }
                },
            ) { padding ->
                LaunchedEffect(selectedIdx, tabs) {
                    currentTabKey = tabs.getOrNull(selectedIdx)?.key ?: "library"
                    storeFocusIndex.value = 0
                    downloadsNavBridge.controllerActive = false
                }

                val key = tabs.getOrNull(selectedIdx)?.key ?: "library"
                val innerBoxBg = if (immersiveMode && key == "library") Color.Transparent else BgDark
                val headerLayoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
                val headerDensity = LocalDensity.current
                val headerMinTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val contentTopPadding =
                    with(headerDensity) {
                        (padding.calculateTopPadding().toPx() + headerOffsetPx)
                            .coerceAtLeast(headerMinTopPadding.toPx())
                            .toDp()
                    }

                Box(
                    Modifier
                        .padding(
                            start = padding.calculateStartPadding(headerLayoutDirection),
                            top = contentTopPadding,
                            end = padding.calculateEndPadding(headerLayoutDirection),
                            bottom = padding.calculateBottomPadding(),
                        ).fillMaxSize()
                        .background(innerBoxBg),
                ) {

                    LaunchedEffect(key) { libraryTabActive.value = (key == "library") }

                    // Keep Library composed so its state survives tab switches.
                    Box(
                        Modifier.fillMaxSize().let {
                            if (key == "library") {
                                it
                            } else {
                                it.alpha(0f).pointerInput(Unit) { /* block ghost taps */ }
                            }
                        },
                    ) {
                        LibraryCarousel(
                            isLoggedIn = isLoggedIn,
                            steamApps = filteredSteamApps,
                            epicApps = epicApps,
                            gogApps = gogApps,
                            layoutMode = libraryLayoutMode,
                            libraryRefreshKey = libraryRefreshKey,
                            shortcutRefreshKey = shortcutRefreshKey,
                            playtimeRefreshKey = playtimeRefreshKey,
                            iconRefreshKey = iconRefreshKey,
                            searchQuery = searchQuery,
                            isControllerConnected = isControllerConnected,
                        )
                    }

                    if (key != "library") {
                        AnimatedContent(
                            targetState = key,
                            transitionSpec = {
                                fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                            },
                            label = "tabContent",
                        ) { animatedKey ->
                            when (animatedKey) {
                                "downloads" -> {
                                    DownloadsTab(
                                        selectedDownloadId,
                                        animationsActive = key == "downloads",
                                        onSelectDownload = { selectedDownloadId = it },
                                    )
                                }

                                "steam" -> {
                                    SteamStoreTab(isLoggedIn, filteredSteamApps, searchQuery, LibraryLayoutMode.GRID_4)
                                }

                                "epic" -> {
                                    EpicStoreTab(isEpicLoggedIn, epicApps, searchQuery, LibraryLayoutMode.GRID_4) {
                                        epicLoginLauncher.launch(Intent(this@UnifiedHub, EpicOAuthActivity::class.java))
                                    }
                                }

                                "gog" -> {
                                    GOGStoreTab(isGogLoggedIn, gogApps, searchQuery, LibraryLayoutMode.GRID_4) {
                                        gogLoginLauncher.launch(Intent(this@UnifiedHub, GOGOAuthActivity::class.java))
                                    }
                                }

                                "itch" -> {
                                    ItchStoreTab(
                                        searchQuery = searchQuery,
                                        signInSignal = itchAuthRefreshKey,
                                        onSignInClick = {
                                            itchLoginLauncher.launch(Intent(this@UnifiedHub, ItchLoginActivity::class.java))
                                        },
                                        onSignedOut = { itchAuthRefreshKey++ },
                                    )
                                }

                                else -> {}
                            }
                        }
                    }

                    val configuration = LocalConfiguration.current
                    val libraryFabBase = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
                    val addGameFabSize = (libraryFabBase * 0.125f).dp.coerceIn(56.dp, 64.dp)
                    val addGameFabMargin = (libraryFabBase * 0.035f).dp.coerceIn(12.dp, 20.dp)
                    val addGameFabIconSize = (libraryFabBase * 0.055f).dp.coerceIn(24.dp, 28.dp)
                    val fabNavInsets = WindowInsets.navigationBars.asPaddingValues()
                    val fabEndInset =
                        (20.dp - fabNavInsets.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr))
                            .coerceAtLeast(4.dp)
                    val fabStartInset =
                        (20.dp - fabNavInsets.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr))
                            .coerceAtLeast(4.dp)

                    if (drawerState.isClosed) {
                        DrawerSwipeHotZone(
                            modifier = Modifier.align(Alignment.CenterStart),
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                        )
                    }
                    if (rightDrawerState.isClosed) {
                        DrawerSwipeHotZone(
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 22.dp),
                            isRightSide = true,
                            onOpenDrawer = { scope.launch { rightDrawerState.open() } },
                        )
                    }

                    // Composed after the hot zones so the FAB stays on top for hit-testing.
                    if (key == "library") {
                        Column(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .windowInsetsPadding(
                                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                                    )
                                    .padding(end = fabEndInset, bottom = addGameFabMargin),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (isControllerConnected) {
                                ControllerBadge("R3")
                                Spacer(Modifier.height(8.dp))
                            }
                            Box(
                                modifier =
                                    Modifier
                                        .size(addGameFabSize)
                                    .drawBehind {
                                        drawCircle(
                                            brush =
                                                Brush.radialGradient(
                                                    colors = listOf(Accent.copy(alpha = 0.22f), Color.Transparent),
                                                    center = center,
                                                    radius = size.minDimension * 0.64f,
                                                ),
                                            radius = size.minDimension * 0.64f,
                                        )
                                    }
                                    .clip(CircleShape)
                                    .background(Color.Transparent, CircleShape)
                                    .border(1.5.dp, Accent.copy(alpha = 0.55f), CircleShape)
                                    .focusProperties { canFocus = false } // No specific button for this, handle via long press or touch
                                    .clickable(
                                        interactionSource = null,
                                        indication = androidx.compose.material3.ripple(color = Accent),
                                    ) { showAddCustomGame = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = "Add Custom Game",
                                tint = Accent,
                                modifier = Modifier.size(addGameFabIconSize),
                            )
                            }
                        }
                    }

                    if (key == "library" || key == "downloads") {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .windowInsetsPadding(
                                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                                    )
                                    .padding(start = fabStartInset, bottom = addGameFabMargin)
                                    .size(addGameFabSize)
                                    .drawBehind {
                                        drawCircle(
                                            brush =
                                                Brush.radialGradient(
                                                    colors = listOf(Accent.copy(alpha = 0.22f), Color.Transparent),
                                                    center = center,
                                                    radius = size.minDimension * 0.64f,
                                                ),
                                            radius = size.minDimension * 0.64f,
                                        )
                                    }
                                    .clip(CircleShape)
                                    .background(Color.Transparent, CircleShape)
                                    .border(1.5.dp, Accent.copy(alpha = 0.55f), CircleShape)
                                    .focusProperties { canFocus = false }
                                    .clickable(
                                        interactionSource = null,
                                        indication = androidx.compose.material3.ripple(color = Accent),
                                    ) { openFileManager() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.FolderOpen,
                                contentDescription = "Files",
                                tint = Accent,
                                modifier = Modifier.size(addGameFabIconSize),
                            )
                        }
                    }
                }
            }
        }
    } // end ModalNavigationDrawer
    } // end inner LTR
    } // end right friends ModalNavigationDrawer
    } // end RTL provider

    if (globalSettingsApp != null) {
        GameSettingsDialog(
            app = globalSettingsApp!!,
            onDismissRequest = { globalSettingsApp = null },
        )
    }
    if (globalSettingsGogGame != null) {
        GOGGameSettingsDialog(
            app = globalSettingsGogGame!!,
            onDismissRequest = { globalSettingsGogGame = null },
        )
    }

    if (showAddCustomGame) {
        AddCustomGameDialog(onDismiss = {
            showAddCustomGame = false
            localLibraryRefreshKey++
        })
    }

    chatFriend?.let { cf ->
        com.winlator.cmod.feature.stores.steam.friends.SteamChatScreen(
            friend = friends.firstOrNull { it.steamId == cf.steamId } ?: cf,
            onClose = { chatFriend = null },
        )
    }

    BackHandler(enabled = true) {
        if (chatFriend != null) {
            chatFriend = null
        } else if (rightDrawerState.isOpen) {
            scope.launch { rightDrawerState.close() }
        } else if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (globalSettingsApp != null) {
            globalSettingsApp = null
        } else if (globalSettingsGogGame != null) {
            globalSettingsGogGame = null
        } else if (showAddCustomGame) {
            showAddCustomGame = false
        } else {
            showExitDialog = true
        }
    }

    if (showExitDialog) {
        Dialog(
            onDismissRequest = { showExitDialog = false },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
        ) {
            Box(
                modifier =
                    Modifier
                        .width(320.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(SurfaceDark)
                        .border(1.dp, Accent.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                        .padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.common_ui_exit_app_confirm),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showExitDialog = false },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TextSecondary.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.common_ui_cancel), fontWeight = FontWeight.Medium)
                        }
                        Button(
                            onClick = {
                                AppTerminationHelper.exitApplication(this@UnifiedHub, "hub_exit_menu")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.common_ui_exit), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun UnifiedActivity.DrawerSwipeHotZone(
    modifier: Modifier = Modifier,
    isRightSide: Boolean = false,
    onOpenDrawer: () -> Unit,
) {
    val density = LocalDensity.current
    val openThresholdPx = with(density) { 36.dp.toPx() }

    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .width(if (isRightSide) 30.dp else 40.dp)
                .pointerInput(openThresholdPx, isRightSide) {
                    var accumulatedDrag = 0f
                    var opened = false

                    detectHorizontalDragGestures(
                        onDragStart = {
                            accumulatedDrag = 0f
                            opened = false
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            val delta = if (isRightSide) -dragAmount else dragAmount
                            if (delta <= 0f || opened) return@detectHorizontalDragGestures

                            accumulatedDrag += delta
                            change.consume()

                            if (accumulatedDrag >= openThresholdPx) {
                                opened = true
                                onOpenDrawer()
                            }
                        },
                    )
                },
    )
}

@Composable
internal fun UnifiedActivity.GlassesSettingsSheet(onDismiss: () -> Unit) {
    val gm = com.winlator.cmod.runtime.display.GlassesManager
    val settings by gm.settings.collectAsState()
    val brightnessMax = gm.brightnessMax()
    val volumeMax = gm.volumeMax()
    val brightness = if (settings.brightness < 0) brightnessMax else settings.brightness
    val volume = if (settings.volume < 0) volumeMax else settings.volume
    val registry = remember { PaneNavRegistry() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(LocalPaneNav provides registry) {
        DialogPaneNav(registry, onDismiss = onDismiss)
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(24.dp),
            color = SurfaceDark,
            modifier = Modifier.fillMaxWidth(0.82f),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Eyeglasses2Icon, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(gm.modelName(), color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassesLabel(stringResource(R.string.glasses_panel_refresh))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(60, 90, 120).forEach { hz ->
                                    val selected = settings.refreshHz == hz
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(if (selected) Accent else TextSecondary.copy(alpha = 0.12f))
                                            .paneNavItem(cornerRadius = 11.dp, onActivate = { gm.setRefreshHz(hz) }, isEntry = hz == 60)
                                            .clickable { gm.setRefreshHz(hz) }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("$hz", color = if (selected) SurfaceDark else TextPrimary,
                                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GlassesToggleTile(stringResource(R.string.glasses_panel_sunblock),
                                settings.sunblock, Modifier.weight(1f)) { gm.setSunblock(it) }
                            GlassesToggleTile(stringResource(R.string.session_drawer_output_3d),
                                settings.threeD, Modifier.weight(1f)) { gm.set3D(it) }
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        GlassesPercentSlider(stringResource(R.string.session_drawer_output_brightness),
                            brightness, brightnessMax) { gm.setBrightness(it) }
                        GlassesPercentSlider(stringResource(R.string.session_drawer_output_volume),
                            volume, volumeMax) { gm.setVolume(it) }
                    }
                }
            }
        }
        }
    }
}

@Composable
internal fun UnifiedActivity.GlassesLabel(text: String) {
    Text(text, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun UnifiedActivity.GlassesPercentSlider(label: String, level: Int, max: Int, onChange: (Int) -> Unit) {
    val pct = if (max > 0) Math.round(level * 100f / max) else 0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            GlassesLabel(label)
            Text("$pct%", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        androidx.compose.material3.Slider(
            value = level.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..max.toFloat(),
            steps = (max - 1).coerceAtLeast(0),
            modifier = Modifier.paneNavItem(
                cornerRadius = 8.dp,
                onAdjust = { dir -> onChange((level + dir).coerceIn(0, max)) },
            ),
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = TextSecondary.copy(alpha = 0.2f),
            ),
        )
    }
}

@Composable
internal fun UnifiedActivity.GlassesToggleTile(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(if (checked) Accent.copy(alpha = 0.16f) else TextSecondary.copy(alpha = 0.08f))
            .paneNavItem(cornerRadius = 13.dp, onActivate = { onChange(!checked) })
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
            ),
        )
    }
}

@Composable
internal fun UnifiedActivity.TopBar(
    tabs: List<TabDef>,
    selectedIdx: Int,
    onSelect: (Int) -> Unit,
    persona: com.winlator.cmod.feature.stores.steam.data.SteamFriend?,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    isControllerConnected: Boolean,
    isPS: Boolean,
    isLibraryTab: Boolean,
    searchQuery: TextFieldValue,
    onSearchQueryChange: (TextFieldValue) -> Unit,
    onFilterClicked: () -> Unit,
    onFriendsClicked: () -> Unit = {},
    onGameSettingsClicked: () -> Unit,
) {
    var isSearchExpanded by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isDownloadsTab = tabs.getOrNull(selectedIdx)?.key == "downloads"
    val glassesConnected by com.winlator.cmod.runtime.display.GlassesManager.connected.collectAsState()
    var showGlassesPanel by remember { mutableStateOf(false) }

    LaunchedEffect(selectedIdx) {
        if (isSearchExpanded) {
            onSearchQueryChange(TextFieldValue(""))
            isSearchExpanded = false
        }
    }

    // Auto-focus the search field when expanded
    LaunchedEffect(isSearchExpanded) {
        if (isSearchExpanded) {
            kotlinx.coroutines.delay(150)
            searchFocusRequester.requestFocus()
        } else if (searchQuery.text.isNotEmpty()) {
            onSearchQueryChange(TextFieldValue(""))
        }
    }

    val controllerSearchActivity = LocalContext.current as? UnifiedActivity
    LaunchedEffect(Unit) {
        controllerSearchActivity?.openSearchSignal?.collect {
            if (!isDownloadsTab) {
                if (isSearchExpanded) {
                    onSearchQueryChange(TextFieldValue(""))
                    isSearchExpanded = false
                } else {
                    isSearchExpanded = true
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        controllerSearchActivity?.openGlassesSignal?.collect {
            if (glassesConnected) showGlassesPanel = true
        }
    }

    val portraitTopBar = isPortraitLayout()
    val topBarWidth = screenWidthDp()
    val topBarView = androidx.compose.ui.platform.LocalView.current
    val topBarDensity = androidx.compose.ui.platform.LocalDensity.current
    val topBarOrientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    val navRightInset =
        remember(topBarOrientation, topBarView) {
            val px =
                androidx.core.view.ViewCompat.getRootWindowInsets(topBarView)
                    ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.right ?: 0
            with(topBarDensity) { px.toDp() }
        }

    val tabsContent: @Composable (Modifier) -> Unit = { tabsModifier ->
            Row(
                modifier = tabsModifier,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                @Suppress("DEPRECATION")
                CompositionLocalProvider(
                    androidx.compose.material3.LocalRippleConfiguration provides null,
                ) {
                    val tabSideGutter = 12.dp
                    val tabBarShape = RoundedCornerShape(18.dp)
                    val visibleCount = minOf(3, tabs.size)
                    val tabWidth =
                        if (portraitTopBar) {
                            ((topBarWidth - UnifiedTopBarHorizontalPadding * 2 - tabSideGutter * 2) / visibleCount)
                                .coerceAtLeast(84.dp)
                        } else {
                            100.dp
                        }
                    val tabListState = rememberLazyListState()
                    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = tabListState)

                    LaunchedEffect(selectedIdx) {
                        val scrollTo = maxOf(0, selectedIdx - 1)
                        tabListState.animateScrollToItem(scrollTo)
                    }

                    Box(
                        modifier =
                            Modifier
                                .width(tabWidth * visibleCount + tabSideGutter * 2)
                                .height(44.dp)
                                .shadow(8.dp, tabBarShape, spotColor = Color.Black.copy(alpha = 0.5f))
                                .clip(tabBarShape)
                                .background(CardDark)
                                .border(1.dp, CardBorder, tabBarShape),
                    ) {
                        LazyRow(
                            state = tabListState,
                            flingBehavior = snapFlingBehavior,
                            modifier =
                                Modifier
                                    .align(Alignment.Center)
                                    .width(tabWidth * visibleCount)
                                    .fillMaxHeight()
                                    .focusProperties { canFocus = !isLibraryTab },
                            userScrollEnabled = tabs.size > visibleCount,
                        ) {
                            itemsIndexed(tabs) { index, tab ->
                                val selected = selectedIdx == index
                                val interactionSource = remember { MutableInteractionSource() }
                                val isPressed by interactionSource.collectIsPressedAsState()
                                val tabScale by animateFloatAsState(
                                    targetValue = if (isPressed) 0.92f else 1f,
                                    animationSpec = spring(stiffness = Spring.StiffnessHigh),
                                    label = "tabScale",
                                )
                                val textColor by animateColorAsState(
                                    targetValue = if (selected) Accent else TextSecondary,
                                    animationSpec = tween(280),
                                    label = "tabTextColor",
                                )

                                Box(
                                    modifier =
                                        Modifier
                                            .width(tabWidth)
                                            .fillMaxHeight()
                                            .focusProperties { canFocus = false }
                                            .graphicsLayer {
                                                scaleX = tabScale
                                                scaleY = tabScale
                                            }.clickable(
                                                interactionSource = interactionSource,
                                                indication = null,
                                            ) { onSelect(index) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = tab.label.uppercase(),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        color = textColor,
                                    )
                                }
                            }
                        }
                        if (isControllerConnected) {
                            ControllerBadge(
                                "L1",
                                Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
                                compact = true,
                            )
                            ControllerBadge(
                                "R1",
                                Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                                compact = true,
                            )
                        }
                    }
                }
            }
    }

    val leftContent: @Composable (Modifier) -> Unit = { leftModifier ->
            Row(
                modifier = leftModifier,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .border(1.dp, Accent.copy(alpha = 0.5f), CircleShape)
                            .focusProperties { canFocus = !isLibraryTab },
                    contentAlignment = Alignment.Center,
                ) {
                    @Suppress("DEPRECATION")
                    CompositionLocalProvider(
                        androidx.compose.material3.LocalRippleConfiguration provides
                            androidx.compose.material3.RippleConfiguration(color = Accent),
                    ) {
                        IconButton(onClick = {
                            navigateToSettings(SettingsNavItem.STORES)
                        }, modifier = Modifier.size(44.dp), enabled = true) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Menu", tint = Accent, modifier = Modifier.size(24.dp))
                        }
                    }
                }
                if (isControllerConnected) {
                    Spacer(Modifier.width(4.dp))
                    ControllerBadge(if (isPS) "\u2261" else "Start")
                }

                Spacer(Modifier.width(6.dp))

                val searchIconRotation by animateFloatAsState(
                    targetValue = if (isSearchExpanded) 90f else 0f,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                    label = "searchIconRotation",
                )

                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSearchExpanded) {
                                    Accent.copy(alpha = 0.15f)
                                } else {
                                    Color.Transparent
                                },
                            ).border(
                                1.dp,
                                Accent.copy(alpha = if (isDownloadsTab) 0.25f else 0.5f),
                                CircleShape,
                            ).focusProperties { canFocus = !isLibraryTab },
                    contentAlignment = Alignment.Center,
                ) {
                    @Suppress("DEPRECATION")
                    CompositionLocalProvider(
                        androidx.compose.material3.LocalRippleConfiguration provides
                            androidx.compose.material3.RippleConfiguration(color = Accent),
                    ) {
                        IconButton(
                            onClick = {
                                if (!isDownloadsTab) {
                                    if (isSearchExpanded) {
                                        onSearchQueryChange(TextFieldValue(""))
                                        isSearchExpanded = false
                                    } else {
                                        isSearchExpanded = true
                                    }
                                }
                            },
                            modifier = Modifier.size(44.dp),
                            enabled = !isDownloadsTab,
                        ) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = "Search",
                                tint =
                                    if (isDownloadsTab) {
                                        TextSecondary.copy(alpha = 0.4f)
                                    } else {
                                        Accent
                                    },
                                modifier =
                                    Modifier
                                        .size(24.dp)
                                        .graphicsLayer { rotationZ = searchIconRotation },
                            )
                        }
                    }
                }
                if (isControllerConnected) {
                    Spacer(Modifier.width(4.dp))
                    ControllerBadge("L3")
                }
            }
    }

    val rightContent: @Composable (Modifier) -> Unit = { rightModifier ->
            Row(
                modifier = rightModifier,
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(8.dp))

                if (glassesConnected) {
                    Box(
                        modifier =
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Transparent)
                                .border(1.dp, Accent.copy(alpha = 0.5f), CircleShape)
                                .clickable { showGlassesPanel = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Eyeglasses2Icon, contentDescription = "Glasses", tint = Accent, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                }

                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .border(1.dp, Accent.copy(alpha = 0.5f), CircleShape)
                            .focusProperties { canFocus = !isLibraryTab }
                            .clickable(
                                interactionSource = null,
                                indication = androidx.compose.material3.ripple(color = Accent),
                            ) { onFilterClicked() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.FilterList, contentDescription = "Filter", tint = Accent, modifier = Modifier.size(24.dp))
                }
                if (isControllerConnected) {
                    Spacer(Modifier.width(4.dp))
                    ControllerBadge("Select")
                }

                Spacer(Modifier.width(6.dp))

                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .border(1.dp, Accent.copy(alpha = 0.5f), CircleShape)
                            .clickable { onFriendsClicked() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.People, contentDescription = "Friends", tint = Accent, modifier = Modifier.size(24.dp))
                }
                if (isControllerConnected && navRightInset <= 0.dp) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier =
                            Modifier
                                .background(Color(0xFF394048), RoundedCornerShape(15.dp))
                                .border(1.dp, Color(0xFF8B949E).copy(alpha = 0.5f), RoundedCornerShape(15.dp))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.SportsEsports,
                            contentDescription = "Guide",
                            tint = Color(0xFFE6EDF3),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
    }

    val guideOverflowContent: @Composable (Modifier) -> Unit = { guideModifier ->
        if (isControllerConnected && navRightInset > 0.dp) {
            Box(
                modifier =
                    guideModifier
                        .offset(x = 38.dp)
                        .zIndex(2f)
                        .background(Color(0xFF394048), RoundedCornerShape(15.dp))
                        .border(1.dp, Color(0xFF8B949E).copy(alpha = 0.5f), RoundedCornerShape(15.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.SportsEsports,
                    contentDescription = "Guide",
                    tint = Color(0xFFE6EDF3),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = UnifiedTopBarHorizontalPadding,
                        end = UnifiedTopBarHorizontalPadding,
                        top = UnifiedTopBarTopPadding,
                    )
                    .height(UnifiedTopBarHeight),
        ) {
            if (!portraitTopBar) {
                tabsContent(Modifier.align(Alignment.Center).zIndex(1f))
            }
            leftContent(Modifier.align(Alignment.CenterStart).fillMaxHeight())
            rightContent(Modifier.align(Alignment.CenterEnd).fillMaxHeight().zIndex(2f))
            guideOverflowContent(Modifier.align(Alignment.CenterEnd))
        }

        if (portraitTopBar) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = UnifiedTopBarHorizontalPadding,
                            end = UnifiedTopBarHorizontalPadding,
                            top = 8.dp,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                tabsContent(Modifier)
            }
        }

        if (showGlassesPanel) GlassesSettingsSheet(onDismiss = { showGlassesPanel = false })

        AnimatedVisibility(
            visible = isSearchExpanded && !isDownloadsTab,
            enter =
                expandVertically(
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    expandFrom = Alignment.Top,
                ) + fadeIn(animationSpec = tween(200)),
            exit =
                shrinkVertically(
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(animationSpec = tween(120)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .widthIn(max = 600.dp)
                            .fillMaxWidth(if (portraitTopBar) 1f else 0.7f)
                            .height(44.dp)
                            .shadow(8.dp, RoundedCornerShape(24.dp), spotColor = Color.Black.copy(alpha = 0.4f))
                            .clip(RoundedCornerShape(24.dp))
                            .background(SurfaceDark),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            singleLine = true,
                            textStyle =
                                TextStyle(
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                            cursorBrush = Brush.verticalGradient(listOf(Accent, AccentGlow)),
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .focusRequester(searchFocusRequester),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (searchQuery.text.isEmpty()) {
                                        Text(
                                            "Search games",
                                            style =
                                                TextStyle(
                                                    color = TextSecondary,
                                                    fontSize = 15.sp,
                                                ),
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                        if (searchQuery.text.isNotEmpty()) {
                            IconButton(
                                onClick = { onSearchQueryChange(TextFieldValue("")) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Clear",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    } // end Column
}

@Composable
internal fun UnifiedActivity.LibraryCarousel(
    isLoggedIn: Boolean,
    steamApps: List<SteamApp>,
    epicApps: List<EpicGame>,
    gogApps: List<GOGGame>,
    layoutMode: LibraryLayoutMode,
    libraryRefreshKey: Int = 0,
    shortcutRefreshKey: Int = 0,
    playtimeRefreshKey: Int = 0,
    iconRefreshKey: Int = 0,
    searchQuery: String = "",
    isControllerConnected: Boolean = false,
) {
    val context = LocalContext.current

    var cachedShortcuts by remember { mutableStateOf<List<Shortcut>>(emptyList()) }
    var customApps by remember { mutableStateOf<List<SteamApp>>(emptyList()) }
    var localLibraryRefreshKey by remember { mutableIntStateOf(0) }
    var shortcutsLoaded by remember { mutableStateOf(false) }
    var pullRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(shortcutRefreshKey, localLibraryRefreshKey, com.winlator.cmod.feature.retro.RetroBoxart.artVersion.value) {
        shortcutsLoaded = false

        // Pull-to-refresh only: rescan disk so a manually moved game is picked up without faking a re-download.
        // Skipped on the initial pass because the scan walks every known app.
        if (pullRefreshing) {
            runCatching {
                withContext(Dispatchers.IO) { SteamService.repairInstalledMetadataFromDisk() }
            }.onFailure { Log.w("UnifiedActivity", "Pull-to-refresh install repair failed", it) }
        }

        val shortcutScanResult =
            runCatching {
                withContext(Dispatchers.IO) {
                    runCatching { com.winlator.cmod.feature.retro.RetroRomScanner.scanConfiguredFolder(context) }
                    val cm = ContainerManager(context)
                    cm.upgradeShortcuts {
                        localLibraryRefreshKey++
                    }
                    val allShortcuts = cm.loadShortcuts()
                    val badges = HashMap<Int, String>()
                    val apps =
                        allShortcuts
                            .mapNotNull { shortcut ->
                                if (!LibraryShortcutUtils.isCustomLibraryShortcut(shortcut)) {
                                    return@mapNotNull null
                                }

                                val displayName =
                                    shortcut
                                        .getExtra("custom_name", shortcut.name)
                                        .ifBlank { shortcut.name }

                                val uuid = shortcut.getExtra("uuid")
                                val identity =
                                    if (uuid.isNotEmpty()) {
                                        uuid
                                    } else {
                                        shortcut.file?.absolutePath ?: displayName
                                    }
                                val customId = -(identity.hashCode().and(0x7FFFFFFF) + 1)

                                com.winlator.cmod.feature.retro.RetroSystems
                                    .fromId(
                                        shortcut.getExtra(
                                            com.winlator.cmod.feature.retro.RetroShortcuts.KEY_SYSTEM,
                                        ),
                                    )?.let { badges[customId] = it.id }

                                SteamApp(
                                    id = customId,
                                    name = displayName,
                                    developer = "Custom",
                                    gameDir =
                                        shortcut.getExtra(
                                            "game_install_path",
                                            shortcut.getExtra("custom_game_folder", ""),
                                        ),
                                )
                            }

                    Triple(allShortcuts, apps, badges)
                }
            }.getOrNull()

        if (shortcutScanResult != null) {
            cachedShortcuts = shortcutScanResult.first
            customApps = shortcutScanResult.second
            retroLibrarySystemIds.value = shortcutScanResult.third
        }

        shortcutsLoaded = true
    }

    // Move library filtering and file checks off the main thread.
    var mergedInstalledApps by remember { mutableStateOf<List<SteamApp>>(emptyList()) }
    var installedApps by remember { mutableStateOf<List<SteamApp>>(emptyList()) }
    var stableInstalledApps by remember { mutableStateOf<List<SteamApp>>(emptyList()) }
    var gogByPseudoId by remember { mutableStateOf<Map<Int, GOGGame>>(emptyMap()) }
    var epicByPseudoId by remember { mutableStateOf<Map<Int, EpicGame>>(emptyMap()) }
    var stableGogByPseudoId by remember { mutableStateOf<Map<Int, GOGGame>>(emptyMap()) }
    var stableEpicByPseudoId by remember { mutableStateOf<Map<Int, EpicGame>>(emptyMap()) }
    var customListArtworkPathByAppId by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var customHeroArtworkPathByAppId by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var customCarouselArtworkPathByAppId by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var customArtworkPathByAppId by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var customIconArtworkPathByAppId by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var customIconPathByAppId by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var stableCustomArtworkPathByAppId by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var stableCustomIconArtworkPathByAppId by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var stableCustomIconPathByAppId by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var stableCustomHeroPathByAppId by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var stableCustomCarouselPathByAppId by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var stableCustomListPathByAppId by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var artworkCacheRefreshKey by remember { mutableIntStateOf(0) }
    var libraryLoaded by remember { mutableStateOf(false) }
    // Suppress transient empty states before background recomputation starts.
    val scanInputToken =
        remember(steamApps, epicApps, gogApps, customApps, libraryRefreshKey, localLibraryRefreshKey) { Any() }
    var processedScanToken by remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(scanInputToken) {
        withContext(Dispatchers.IO) {
            val steamInstalled = steamApps.filter { SteamService.isAppInstalled(it.id) }

            val epicInstalled = epicApps.filter { it.isInstalled }

            // Match Epic's DB-backed install filter during verify/update.
            val gogInstalled = gogApps.filter { it.isInstalled }

            val gogMap = gogInstalled.associateBy { gogPseudoId(it.id) }
            val epicMap = epicInstalled.associateBy { 2000000000 + it.id }

            val playtimePrefs = context.getSharedPreferences("playtime_stats", android.content.Context.MODE_PRIVATE)
            val allPlaytime = playtimePrefs.all
            val mappedEpic =
                epicInstalled.map { epic ->
                    SteamApp(
                        id = 2000000000 + epic.id,
                        name = epic.title,
                        developer = epic.developer,
                        gameDir = epic.installPath,
                    )
                }
            val mappedGog =
                gogInstalled.map { gog ->
                    SteamApp(
                        id = gogPseudoId(gog.id),
                        name = gog.title,
                        developer = gog.developer,
                        gameDir = gog.installPath,
                    )
                }
            val merged = (steamInstalled + customApps + mappedEpic + mappedGog).distinctBy { it.id }
            val sorted =
                merged.sortedByDescending { app ->
                    val searchKey =
                        if (app.id >= 2000000000 || app.id < 0) {
                            app.name
                        } else {
                            app.name.replace(LIBRARY_NAME_SANITIZE_REGEX, "")
                        }
                    (allPlaytime["${searchKey}_last_played"] as? Long) ?: 0L
                }

            withContext(Dispatchers.Main) {
                gogByPseudoId = gogMap
                epicByPseudoId = epicMap
                mergedInstalledApps = merged
                installedApps = sorted
                if (sorted.isNotEmpty()) {
                    stableInstalledApps = sorted
                    stableGogByPseudoId = gogMap
                    stableEpicByPseudoId = epicMap
                }
                libraryLoaded = true
                processedScanToken = scanInputToken
                pullRefreshing = false
            }
        }
    }

    LaunchedEffect(installedApps, gogByPseudoId, cachedShortcuts, iconRefreshKey) {
        val appsSnapshot = installedApps
        val gogSnapshot = gogByPseudoId
        val shortcutsSnapshot = cachedShortcuts

        val artworkPaths =
            withContext(Dispatchers.IO) {
                buildMap<Int, String> {
                    appsSnapshot.forEach { app ->
                        val gogGame = gogSnapshot[app.id]
                        val isCustom = app.id < 0
                        val isEpic = app.id >= 2000000000
                        val epicId = if (isEpic) app.id - 2000000000 else 0
                        val shortcut =
                            if (gogGame != null) {
                                shortcutsSnapshot.find {
                                    it.getExtra("game_source") == "GOG" && it.getExtra("gog_id") == gogGame.id
                                }
                            } else {
                                findShortcutForGame(shortcutsSnapshot, app, isCustom, isEpic, epicId)
                            }
                        val customPath =
                            shortcut
                                ?.getExtra("customLibraryIconPath")
                                ?.ifBlank { shortcut.getExtra("customCoverArtPath") }
                        if (!customPath.isNullOrBlank() && java.io.File(customPath).exists()) {
                            put(app.id, customPath)
                        }
                    }
                }
            }

        val iconArtworkPaths =
            withContext(Dispatchers.IO) {
                buildMap<Int, String> {
                    appsSnapshot.forEach { app ->
                        val gogGame = gogSnapshot[app.id]
                        val isCustom = app.id < 0
                        val isEpic = app.id >= 2000000000
                        val epicId = if (isEpic) app.id - 2000000000 else 0
                        val shortcut =
                            if (gogGame != null) {
                                shortcutsSnapshot.find {
                                    it.getExtra("game_source") == "GOG" && it.getExtra("gog_id") == gogGame.id
                                }
                            } else {
                                findShortcutForGame(shortcutsSnapshot, app, isCustom, isEpic, epicId)
                            }
                        val customPath = shortcut?.let(LibraryShortcutArtwork::findIconArtworkPath)
                        if (customPath != null) {
                            put(app.id, customPath)
                        }
                    }
                }
            }

        val customHeroPath =
            withContext(Dispatchers.IO) {
                buildMap<Int, String> {
                    appsSnapshot.forEach { app ->
                        if (app.id >= 0) return@forEach
                        val shortcut = findShortcutForGame(shortcutsSnapshot, app, true, false, 0) ?: return@forEach
                        val heroPath = shortcut.getExtra("customLibraryHeroArtPath")
                        if (heroPath.isNullOrBlank() || !java.io.File(heroPath).isFile)
                            return@forEach
                        put(app.id, heroPath)
                    }
                }
            }

        val customCarouselPath =
            withContext(Dispatchers.IO) {
                buildMap<Int, String> {
                    appsSnapshot.forEach { app ->
                        if (app.id >= 0) return@forEach
                        val shortcut = findShortcutForGame(shortcutsSnapshot, app, true, false, 0) ?: return@forEach
                        val carouselPath = shortcut.getExtra("customLibraryCarouselArtPath")
                        if (carouselPath.isNullOrBlank() || !java.io.File(carouselPath).isFile)
                            return@forEach
                        put(app.id, carouselPath)
                    }
                }
            }

        val customListPath =
            withContext(Dispatchers.IO) {
                buildMap<Int, String> {
                    appsSnapshot.forEach { app ->
                        if (app.id >= 0) return@forEach
                        val shortcut = findShortcutForGame(shortcutsSnapshot, app, true, false, 0) ?: return@forEach
                        val listPath = shortcut.getExtra("customLibraryListArtPath")
                        if (listPath.isNullOrBlank() || !java.io.File(listPath).isFile)
                            return@forEach
                        put(app.id, listPath)
                    }
                }
            }

        val customIconPaths =
            withContext(Dispatchers.IO) {
                buildMap<Int, String> {
                    appsSnapshot.forEach { app ->
                        if (app.id >= 0) return@forEach
                        val safeName = app.name.replace("/", "_").replace("\\", "_")
                        val iconFile = java.io.File(context.filesDir, "custom_icons/$safeName.png")
                        if (iconFile.exists()) {
                            put(app.id, iconFile.absolutePath)
                        }
                    }
                }
            }

        customArtworkPathByAppId = artworkPaths
        customIconArtworkPathByAppId = iconArtworkPaths
        customIconPathByAppId = customIconPaths
        customHeroArtworkPathByAppId = customHeroPath
        customCarouselArtworkPathByAppId = customCarouselPath
        customListArtworkPathByAppId = customListPath
        if (appsSnapshot.isNotEmpty()) {
            stableCustomArtworkPathByAppId = artworkPaths
            stableCustomIconArtworkPathByAppId = iconArtworkPaths
            stableCustomIconPathByAppId = customIconPaths
            stableCustomHeroPathByAppId = customHeroPath
            stableCustomCarouselPathByAppId = customCarouselPath
            stableCustomListPathByAppId = customListPath
        }
    }

    LaunchedEffect(mergedInstalledApps, playtimeRefreshKey) {
        if (mergedInstalledApps.isEmpty()) {
            installedApps = emptyList()
            return@LaunchedEffect
        }

        val sorted =
            withContext(Dispatchers.IO) {
                val playtimePrefs = context.getSharedPreferences("playtime_stats", android.content.Context.MODE_PRIVATE)
                val allPlaytime = playtimePrefs.all
                mergedInstalledApps.sortedByDescending { app ->
                    val searchKey =
                        if (app.id >= 2000000000 || app.id < 0) {
                            app.name
                        } else {
                            app.name.replace(LIBRARY_NAME_SANITIZE_REGEX, "")
                        }
                    (allPlaytime["${searchKey}_last_played"] as? Long) ?: 0L
                }
            }

        installedApps = sorted
    }

    val awaitingShortcutScan = installedApps.isEmpty() && !shortcutsLoaded
    val keepPreviousLibraryVisible =
        installedApps.isEmpty() &&
            stableInstalledApps.isNotEmpty() &&
            (processedScanToken !== scanInputToken || awaitingShortcutScan)
    val visibleInstalledApps = if (keepPreviousLibraryVisible) stableInstalledApps else installedApps
    val visibleGogByPseudoId = if (keepPreviousLibraryVisible) stableGogByPseudoId else gogByPseudoId
    val visibleEpicByPseudoId = if (keepPreviousLibraryVisible) stableEpicByPseudoId else epicByPseudoId
    val visibleCustomArtworkPathByAppId =
        if (keepPreviousLibraryVisible) stableCustomArtworkPathByAppId else customArtworkPathByAppId
    val visibleCustomIconArtworkPathByAppId =
        if (keepPreviousLibraryVisible) stableCustomIconArtworkPathByAppId else customIconArtworkPathByAppId
    val visibleCustomIconPathByAppId =
        if (keepPreviousLibraryVisible) stableCustomIconPathByAppId else customIconPathByAppId
    val visibleCustomListPathByAppId =
        if (keepPreviousLibraryVisible) stableCustomListPathByAppId else customListArtworkPathByAppId
    val visibleCustomHeroPathByAppId =
        if (keepPreviousLibraryVisible) stableCustomHeroPathByAppId else customHeroArtworkPathByAppId
    val visibleCustomCarouselPathByAppId =
        if (keepPreviousLibraryVisible) stableCustomCarouselPathByAppId else customCarouselArtworkPathByAppId

    val displayedApps =
        remember(visibleInstalledApps, searchQuery) {
            if (searchQuery.isBlank()) {
                visibleInstalledApps
            } else {
                visibleInstalledApps.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
        }

    LaunchedEffect(
        visibleInstalledApps,
        visibleGogByPseudoId,
        visibleEpicByPseudoId,
        visibleCustomArtworkPathByAppId,
        visibleCustomIconArtworkPathByAppId,
        cachedShortcuts,
    ) {
        var deletedCustomOverrides = false
        val refs =
            visibleInstalledApps.flatMap { app ->
                val gogGame = visibleGogByPseudoId[app.id]
                val epicGame = visibleEpicByPseudoId[app.id]
                val overriddenSlots =
                    customArtworkOverrideSlots(
                        app = app,
                        gogGame = gogGame,
                        epicGame = epicGame,
                        hasDefaultCustomArt = visibleCustomArtworkPathByAppId[app.id] != null,
                        hasIconCustomArt = visibleCustomIconArtworkPathByAppId[app.id] != null,
                        hasHeroCustomArt =
                            findLibraryArtworkShortcut(cachedShortcuts, app, gogGame, epicGame)
                                ?.hasExistingArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.GAME_CARD.extraKey) == true,
                    )

                if (overriddenSlots.isNotEmpty()) {
                    val cacheId = artworkCacheId(app, gogGame, epicGame)
                    if (cacheId != null) {
                        val deleted =
                            withContext(Dispatchers.IO) {
                                StoreArtworkCache.deleteSlots(context, cacheId.store, cacheId.gameId, overriddenSlots)
                            }
                        deletedCustomOverrides = deletedCustomOverrides || deleted
                    }
                }

                StoreArtworkCache
                    .libraryRefs(
                        app = app,
                        gogGame = gogGame,
                        epicGame = epicGame,
                    ).filterNot { it.slot in overriddenSlots }
            }
        val cachedAny =
            withContext(Dispatchers.IO) {
                StoreArtworkCache.cacheAll(context, refs)
            }
        if (cachedAny || deletedCustomOverrides) artworkCacheRefreshKey++
    }

    // The startup bootstrap screen already masks the first frame. Do not
    // force an extra minimum spinner duration here or the library visibly
    // bounces through two loading states on launch.
    // A logged-in store whose owned-apps list is still empty hasn't finished
    // its initial library fetch yet — keep the spinner up instead of flashing
    // "No games installed". This resolves itself once the store populates its
    // DB (steamApps/epicApps/gogApps become non-empty) or if other sources
    // (custom apps, other stores) already have installed games.
    val awaitingStoreSync =
        installedApps.isEmpty() && (
            (isLoggedIn && steamApps.isEmpty()) ||
                (epicApps.isEmpty() && EpicService.hasStoredCredentials(context)) ||
                (gogApps.isEmpty() && GOGAuthManager.isLoggedIn(context))
        )
    // Only block the surface while the first library result is unresolved.
    // After that, keep the current content/empty state visible during
    // background refreshes so the UI does not flicker back to a spinner.
    val initialLibraryLoadPending = !libraryLoaded
    val waitingForFirstEmptyStateResolution =
        installedApps.isEmpty() && (processedScanToken !== scanInputToken || awaitingStoreSync || awaitingShortcutScan)
    val showLoading = initialLibraryLoadPending || waitingForFirstEmptyStateResolution
    if (showLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val spinAlpha by animateFloatAsState(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 600),
                label = "loaderFade",
            )
            CircularProgressIndicator(
                color = Accent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp).alpha(spinAlpha),
            )
        }
        return
    }

    if (visibleInstalledApps.isEmpty()) {
        val epicLoggedIn by EpicAuthManager.isLoggedInFlow.collectAsState()
        val gogLoggedIn by GOGAuthManager.isLoggedInFlow.collectAsState()
        val anyLoggedIn = isLoggedIn || epicLoggedIn || gogLoggedIn
        val hasAnyCredentials =
            anyLoggedIn ||
                SteamService.hasStoredCredentials(context) ||
                EpicService.hasStoredCredentials(context) ||
                GOGAuthManager.isLoggedIn(context)
        if (!anyLoggedIn && !hasAnyCredentials) {
            LoginRequiredScreen("Library") {
                navigateToSettings(SettingsNavItem.STORES)
            }
        } else if (anyLoggedIn) {
            PullToRefreshBox(
                isRefreshing = pullRefreshing,
                onRefresh = {
                    pullRefreshing = true
                    localLibraryRefreshKey++
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyStateMessage(stringResource(R.string.library_games_no_games_installed))
                }
            }
        }
        return
    }

    var selectedAppForSettings by remember { mutableStateOf<SteamApp?>(null) }
    var selectedGogGameForSettings by remember { mutableStateOf<GOGGame?>(null) }
    var detailApp by remember { mutableStateOf<SteamApp?>(null) }
    var detailGogGame by remember { mutableStateOf<GOGGame?>(null) }
    val gridState = rememberLazyGridState()
    val carouselState = rememberLazyListState()
    val activity = LocalContext.current as? UnifiedActivity

    // Pause chasing borders on library cards while any dialog is open.
    LaunchedEffect(selectedAppForSettings, selectedGogGameForSettings, detailApp) {
        chasingBordersPaused.value =
            selectedAppForSettings != null || selectedGogGameForSettings != null || detailApp != null
    }
    DisposableEffect(Unit) {
        onDispose { chasingBordersPaused.value = false }
    }

    LaunchedEffect(layoutMode) {
        currentLibraryLayoutMode = layoutMode
    }

    // Keep activity's item count in sync
    LaunchedEffect(displayedApps.size) {
        activity?.libraryItemCount = displayedApps.size
        val lastIndex = (displayedApps.size - 1).coerceAtLeast(0)
        if (activity != null && displayedApps.isNotEmpty() && activity.libraryFocusIndex.value > lastIndex) {
            activity.libraryFocusIndex.value = lastIndex
        }
    }

    // FocusRequesters for each grid item
    val focusRequesters =
        remember(displayedApps.size) {
            List(displayedApps.size) { FocusRequester() }
        }

    // Observe focus index changes from the activity and request focus on the target item
    val focusIndex by (activity?.libraryFocusIndex ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsState()
    LaunchedEffect(focusIndex, focusRequesters.size, layoutMode) {
        if (searchQuery.isEmpty() &&
            layoutMode == LibraryLayoutMode.GRID_4 &&
            focusRequesters.isNotEmpty() &&
            focusIndex in focusRequesters.indices
        ) {
            gridState.animateScrollToItem(focusIndex)
            try {
                focusRequesters[focusIndex].requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    // Track selected app for the top-right Game Settings button
    LaunchedEffect(focusIndex, displayedApps) {
        val app = displayedApps.getOrNull(focusIndex) ?: displayedApps.firstOrNull()
        selectedSteamAppId = app?.id ?: 0
        selectedSteamAppName = app?.name ?: ""
        val gogGame = app?.let { visibleGogByPseudoId[it.id] }
        selectedLibrarySource =
            when {
                gogGame != null -> "GOG"
                app == null -> ""
                app.id >= 2000000000 -> "EPIC"
                app.id < 0 -> "CUSTOM"
                else -> "STEAM"
            }
        selectedGogGameId = gogGame?.id.orEmpty()
    }

    val heroApps = rememberUpdatedState(displayedApps)
    val heroFocus = rememberUpdatedState(focusIndex)
    val heroGogMap = rememberUpdatedState(visibleGogByPseudoId)
    LaunchedEffect(Unit) {
        activity?.openHeroForFocusedSignal?.collect {
            val list = heroApps.value
            val app = list.getOrNull(heroFocus.value) ?: list.firstOrNull()
            if (app != null) {
                detailGogGame = heroGogMap.value[app.id]
                detailApp = app
            }
        }
    }

    // Publish the focused game's hero art (custom card > store hero > grid capsule) for the immersive background; shortcuts load once per refresh signal, not per focus move.
    var immersiveShortcuts by remember { mutableStateOf<List<Shortcut>?>(null) }
    LaunchedEffect(shortcutRefreshKey, libraryRefreshKey, artworkCacheRefreshKey) {
        immersiveShortcuts =
            withContext(Dispatchers.IO) { ContainerManager(context).loadShortcuts() }
    }

    LaunchedEffect(focusIndex, displayedApps, immersiveShortcuts) {
        val shortcuts = immersiveShortcuts ?: return@LaunchedEffect
        val app = displayedApps.getOrNull(focusIndex) ?: displayedApps.firstOrNull()
        if (app == null) {
            activity?.immersiveBackgroundRef?.value = null
            return@LaunchedEffect
        }
        // Debounce so scrubbing the grid doesn't decode every intermediate hero.
        delay(200)
        val gogGame = visibleGogByPseudoId[app.id]
        val epicGame = visibleEpicByPseudoId[app.id]
        val isCustom = app.id < 0
        val isEpic = app.id >= 2000000000
        val epicId = if (isEpic) app.id - 2000000000 else 0

        val shortcut =
            when {
                gogGame != null ->
                    shortcuts.find {
                        it.getExtra("game_source") == "GOG" && it.getExtra("gog_id") == gogGame.id
                    }
                else -> findShortcutForGame(shortcuts, app, isCustom, isEpic, epicId)
            }
        val customHeroFile =
            withContext(Dispatchers.IO) {
                shortcut
                    ?.getExtra(LibraryShortcutArtwork.LibraryArtworkSlot.GAME_CARD.extraKey)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { java.io.File(it) }
                    ?.takeIf { it.isFile }
            }

        activity?.immersiveBackgroundRef?.value =
            customHeroFile
                ?: run {
                    val ref =
                        StoreArtworkCache.heroRef(app, gogGame, epicGame)
                            ?: StoreArtworkCache.primaryRef(
                                app,
                                gogGame,
                                epicGame,
                                useLibraryCapsule = false,
                                listMode = false,
                            )
                    StoreArtworkCache.imageModel(context, ref)
                }
    }

    val openSettingsForApp: (Int, SteamApp) -> Unit = { index, app ->
        activity?.libraryFocusIndex?.value = index
        selectedSteamAppId = app.id
        selectedSteamAppName = app.name
        val gogGame = visibleGogByPseudoId[app.id]
        selectedLibrarySource =
            when {
                gogGame != null -> "GOG"
                app.id >= 2000000000 -> "EPIC"
                app.id < 0 -> "CUSTOM"
                else -> "STEAM"
            }
        selectedGogGameId = gogGame?.id.orEmpty()

        if (gogGame != null) {
            selectedGogGameForSettings = gogGame
        } else {
            selectedAppForSettings = app
        }
    }

    PullToRefreshBox(
        isRefreshing = pullRefreshing,
        onRefresh = {
            pullRefreshing = true
            localLibraryRefreshKey++
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        when (layoutMode) {
            LibraryLayoutMode.GRID_4 -> {
                FourByTwoGridView(
                    items = displayedApps,
                    modifier = Modifier.tabScreenPadding(),
                    gridState = gridState,
                    contentPadding = TabGridContentPadding,
                    clipContent = false,
                    keyOf = { it.id },
                ) { app, index, rowHeight ->
                    GameCapsule(
                        app = app,
                        gogGame = visibleGogByPseudoId[app.id],
                        epicGame = visibleEpicByPseudoId[app.id],
                        iconRefreshKey = iconRefreshKey,
                        artworkCacheRefreshKey = artworkCacheRefreshKey,
                        isFocusedOverride = index == focusIndex,
                        isControllerActive = isControllerConnected,
                        customArtworkPath = visibleCustomIconArtworkPathByAppId[app.id] ?: visibleCustomArtworkPathByAppId[app.id],
                        customIconPath = visibleCustomIconPathByAppId[app.id],
                        customListPath = visibleCustomListPathByAppId[app.id],
                        customHeroPath = visibleCustomHeroPathByAppId[app.id],
                        onClick = {
                            // Keeps the immersive background on the opened game after backing out.
                            activity?.libraryFocusIndex?.value = index
                            detailGogGame = visibleGogByPseudoId[app.id]
                            detailApp = app
                        },
                        onLongClick = {
                            openSettingsForApp(index, app)
                        },
                        modifier =
                            Modifier
                                .height(rowHeight)
                                .then(
                                    if (index in focusRequesters.indices) {
                                        Modifier.focusRequester(focusRequesters[index])
                                    } else {
                                        Modifier
                                    },
                                ),
                    )
                }
            }

            LibraryLayoutMode.CAROUSEL -> {
                // Host the horizontal carousel in a same-height vertical scroll so a downward finger pull reaches the shared PullToRefreshBox.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val carouselViewportHeight = maxHeight
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Box(Modifier.fillMaxWidth().height(carouselViewportHeight)) {
                            CarouselView(
                                items = displayedApps,
                                modifier = Modifier.tabScreenPadding(top = TabCarouselTopPadding, bottom = TabCarouselBottomPadding),
                                listState = carouselState,
                                selectedIndex = focusIndex,
                                onCenteredIndexChanged = { centeredIndex ->
                                    if (activity != null && activity.libraryFocusIndex.value != centeredIndex) {
                                        activity.libraryFocusIndex.value = centeredIndex
                                    }
                                },
                            ) { app, index, isSelected, cardWidth, cardHeight ->
                                GameCapsule(
                                    app = app,
                                    gogGame = visibleGogByPseudoId[app.id],
                                    epicGame = visibleEpicByPseudoId[app.id],
                                    iconRefreshKey = iconRefreshKey,
                                    artworkCacheRefreshKey = artworkCacheRefreshKey,
                                    isFocusedOverride = isSelected,
                                    isControllerActive = isControllerConnected,
                                    customArtworkPath = visibleCustomIconArtworkPathByAppId[app.id] ?: visibleCustomArtworkPathByAppId[app.id],
                                    customIconPath = visibleCustomIconPathByAppId[app.id],
                                    customListPath = visibleCustomListPathByAppId[app.id],
                                    customCarouselPath = visibleCustomCarouselPathByAppId[app.id],
                                    customHeroPath = visibleCustomHeroPathByAppId[app.id],
                                    onClick = {
                                        detailGogGame = visibleGogByPseudoId[app.id]
                                        detailApp = app
                                    },
                                    onLongClick = { openSettingsForApp(index, app) },
                                    useLibraryCapsule = true,
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .then(
                                                if (index in focusRequesters.indices) {
                                                    Modifier.focusRequester(focusRequesters[index])
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                )
                            }
                        }
                    }
                }
            }

            LibraryLayoutMode.LIST -> {
                val listViewState = rememberLazyListState()
                ListView(
                    items = displayedApps,
                    modifier = Modifier.tabScreenPadding(),
                    listState = listViewState,
                    contentPadding = TabListContentPadding,
                    selectedIndex = focusIndex,
                    onSelectedIndexChanged = { newIdx ->
                        activity?.libraryFocusIndex?.value = newIdx
                    },
                    keyOf = { it.id },
                ) { app, index, isSelected ->
                    GameCapsule(
                        app = app,
                        gogGame = visibleGogByPseudoId[app.id],
                        epicGame = visibleEpicByPseudoId[app.id],
                        iconRefreshKey = iconRefreshKey,
                        artworkCacheRefreshKey = artworkCacheRefreshKey,
                        isFocusedOverride = isSelected,
                        isControllerActive = isControllerConnected,
                        customArtworkPath = visibleCustomIconArtworkPathByAppId[app.id] ?: visibleCustomArtworkPathByAppId[app.id],
                        customIconPath = visibleCustomIconPathByAppId[app.id],
                        customListPath = visibleCustomListPathByAppId[app.id],
                        customHeroPath = visibleCustomHeroPathByAppId[app.id],
                        onClick = {
                            // Keeps the immersive background on the opened game after backing out.
                            activity?.libraryFocusIndex?.value = index
                            detailGogGame = visibleGogByPseudoId[app.id]
                            detailApp = app
                        },
                        onLongClick = { openSettingsForApp(index, app) },
                        listMode = true,
                        modifier =
                            Modifier
                                .then(
                                    if (index in focusRequesters.indices) {
                                        Modifier.focusRequester(focusRequesters[index])
                                    } else {
                                        Modifier
                                    },
                                ),
                    )
                }
                JoystickListScroll(
                    listState = listViewState,
                    stickFlow = activity?.rightStickScrollState,
                    minSpeed = 2.5f,
                    maxSpeed = 16f,
                    quadratic = true,
                )
            }
        }
    }

    if (selectedAppForSettings != null) {
        GameSettingsDialog(
            app = selectedAppForSettings!!,
            onDismissRequest = { selectedAppForSettings = null },
        )
    }
    if (selectedGogGameForSettings != null) {
        GOGGameSettingsDialog(
            app = selectedGogGameForSettings!!,
            onDismissRequest = { selectedGogGameForSettings = null },
        )
    }
    if (detailApp != null) {
        LibraryGameDetailDialog(
            app = detailApp!!,
            gogGame = detailGogGame,
            onDismissRequest = {
                detailApp = null
                detailGogGame = null
            },
        )
    }
}
