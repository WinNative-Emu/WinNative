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
import com.winlator.cmod.BuildConfig
import com.winlator.cmod.R
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.app.service.DownloadService
import com.winlator.cmod.app.service.download.DownloadCoordinator
import com.winlator.cmod.app.update.UpdateHost
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

internal val BgDark = Color(0xFF18181D)
internal val SurfaceDark = Color(0xFF1E252E)
internal val CardDark = Color(0xFF12121B)
internal val CardBorder = Color(0xFF2A2A3A)
internal val Accent = Color(0xFF1A9FFF)
internal val AccentGlow = Color(0xFF58A6FF)
internal val TextPrimary = Color(0xFFF0F4FF)
internal val TextSecondary = Color(0xFF7A8FA8)
internal val DangerRed = Color(0xFFFF6B6B)
internal val StatusOnline = Color(0xFF3FB950)
internal val StatusAway = Color(0xFFF0C040)
private val StatusOffline = Color(0xFF6E7681)
internal val DownloadCardBlack = Color.Black.copy(alpha = 0.46f)
internal val DownloadCardSelectedBlack = Color.Black.copy(alpha = 0.58f)
internal val DownloadButtonBlack = Color.Black.copy(alpha = 0.38f)
private val DownloadChaseBlue = Color(0xFF2196F3)
private val DownloadChaseSky = Color(0xFF29B6F6)
private val DownloadChaseCyan = Color(0xFF00E5FF)
internal val DownloadChaseGradientStops =
    arrayOf(
        0.00f to DownloadChaseBlue,
        0.125f to DownloadChaseSky,
        0.25f to DownloadChaseCyan,
        0.375f to DownloadChaseSky,
        0.50f to DownloadChaseBlue,
        0.625f to DownloadChaseSky,
        0.75f to DownloadChaseCyan,
        0.875f to DownloadChaseSky,
        1.00f to DownloadChaseBlue,
    )
private val TabScreenHorizontalPadding = 16.dp
private val TabScreenBottomPadding = 8.dp
internal val UnifiedTopBarHorizontalPadding = 12.dp
internal val UnifiedTopBarTopPadding = 4.dp
internal val UnifiedTopBarHeight = 56.dp
internal val TabListContentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)
internal val TabGridContentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
internal val TabGridTopPadding = 8.dp
internal val TabCarouselTopPadding = 12.dp
internal val TabCarouselBottomPadding = 20.dp
internal val DownloadsHeaderTopPadding = 2.dp

internal fun Modifier.tabScreenPadding(
    top: Dp = 0.dp,
    bottom: Dp = TabScreenBottomPadding,
): Modifier = padding(start = TabScreenHorizontalPadding, top = top, end = TabScreenHorizontalPadding, bottom = bottom)

internal val LIBRARY_NAME_SANITIZE_REGEX = "[^A-Za-z0-9 _-]".toRegex()

enum class LibraryLayoutMode {
    GRID_4,
    CAROUSEL,
    LIST,
}

internal class DownloadsNavBridge {
    var controllerActive by mutableStateOf(false)
    var navSignal by mutableStateOf(0)
        private set
    var navDir by mutableStateOf(0)
        private set

    private fun nav(dir: Int) {
        navDir = dir
        navSignal++
    }

    fun left() = nav(PANE_DIR_LEFT)

    fun right() = nav(PANE_DIR_RIGHT)

    fun up() = nav(PANE_DIR_UP)

    fun down() = nav(PANE_DIR_DOWN)

    fun activate() = nav(PANE_DIR_ACTIVATE)

    fun secondary() = nav(PANE_DIR_SECONDARY)
}

@AndroidEntryPoint
class UnifiedActivity :
    FixedFontScaleAppCompatActivity(),
    ActivityResultHost {
    @Inject lateinit var dbProvider: Lazy<PluviaDatabase>

    internal val db: PluviaDatabase
        get() = dbProvider.get()

    internal data class PendingNavigation(
        val item: SettingsNavItem = SettingsNavItem.CONTAINERS,
        val profileId: Int = 0,
        val editContainerId: Int = 0,
        val returnToGameOnBack: Boolean = false,
    )

    internal data class ControllerConnectionState(
        val isConnected: Boolean = ControllerHelper.isControllerConnected(),
        val isPlayStation: Boolean = ControllerHelper.isPlayStationController(),
    )

    internal var rootNavController: NavHostController? = null

    internal var pendingNavigation: PendingNavigation? = null

    // Absorb rapid Back presses during the settings exit animation.
    internal var isPoppingSettings: Boolean = false

    internal var selectedSteamAppId: Int = 0
    internal var selectedSteamAppName: String = ""
    internal var selectedLibrarySource: String = ""
    internal var selectedGogGameId: String = ""

    var libraryRefreshSignal by mutableIntStateOf(0)

    var libraryPlaytimeRefreshSignal by mutableIntStateOf(0)
    private var hasCompletedInitialResume = false

    // Activity-level so task progress survives game-detail dialog teardown.
    internal var taskProgressInfo by mutableStateOf<DownloadInfo?>(null)
    internal var taskProgressGameName by mutableStateOf("")
    internal var taskProgressCompleteMsg by mutableStateOf("")
    internal var taskProgressFailedMsg by mutableStateOf("")
    internal var taskProgressShown by mutableStateOf(false)
    internal var taskDoneMessage by mutableStateOf<String?>(null)
    internal var taskDoneFailed by mutableStateOf(false)
    internal var taskCheckingShown by mutableStateOf(false)
    internal var taskCheckingGameName by mutableStateOf("")

    internal var taskProgressCompleteAsToast by mutableStateOf(false)

    // Prevent overlapping checks after the checking pop-up is dismissed.
    internal var updateCheckInProgress = false

    // Avoid paying card border animation cost behind full-screen dialogs.
    internal val chasingBordersPaused = mutableStateOf(false)

    // Keep first composition light while prefs/auth state and Room warm up.
    internal var startupBootstrapReady by mutableStateOf(false)
    internal var startupLibraryLayoutMode by mutableStateOf<LibraryLayoutMode?>(null)
    internal var startupStoreVisible: Map<String, Boolean>? = null
    internal var startupContentFilters: Map<String, Boolean>? = null

    // Lets kept-alive library cards skip animation while their tab is hidden.
    internal val libraryTabActive = mutableStateOf(true)

    val rightStickScrollState = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val leftStickScrollState = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val keyEventFlow = kotlinx.coroutines.flow.MutableSharedFlow<android.view.KeyEvent>(extraBufferCapacity = 10)

    val openHeroForFocusedSignal = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val openSearchSignal = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val openFriendsSignal = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val openGlassesSignal = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal var l2KeyDown = false
    internal var r2KeyDown = false
    internal var l2AxisDown = false
    internal var r2AxisDown = false
    internal var glassesComboArmed = true

    val libraryFocusIndex = kotlinx.coroutines.flow.MutableStateFlow(0)
    var libraryItemCount: Int = 0
    internal var currentLibraryLayoutMode: LibraryLayoutMode = LibraryLayoutMode.GRID_4

    // Coil model for the focused game's immersive background.
    val immersiveBackgroundRef = kotlinx.coroutines.flow.MutableStateFlow<Any?>(null)

    private val defaultNavigationBarColor: Int = android.graphics.Color.TRANSPARENT

    val storeFocusIndex = kotlinx.coroutines.flow.MutableStateFlow(0)
    var storeItemCount: Int = 0

    val storeHeaderVisible = kotlinx.coroutines.flow.MutableStateFlow(true)

    internal val storeColumns: Int
        get() = com.winlator.cmod.shared.ui.gridColumnsForWidth(resources.configuration.screenWidthDp)

    var storeGridState: androidx.compose.foundation.lazy.grid.LazyGridState? = null

    // Shared gate for d-pad, hat, and joystick navigation events.
    private var lastMoveTime = 0L

    private var dpadHeld = false
    private var joystickActive = false
    @Volatile private var retroCloudUploadBusy = false

    internal val settingsNavBridge = SettingsNavBridge()
    internal val downloadsNavBridge = DownloadsNavBridge()
    internal val drawerNavBridge = DownloadsNavBridge()
    internal val friendsDrawerNavBridge = DownloadsNavBridge()
    private var settingsStickEngaged = 0

    companion object {
        private const val MOVE_INTERVAL_MS = 250L
        private var instance: UnifiedActivity? = null

        fun refreshLibrary() {
            instance?.let { it.libraryRefreshSignal++ }
        }

        /** Currently attached Activity (or null if the app is fully backgrounded/killed). */
        @JvmStatic
        fun currentActivity(): UnifiedActivity? =
            instance?.takeUnless { it.isFinishing || it.isDestroyed }
    }

    private val wallpaperImagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult

            val bitmap =
                com.winlator.cmod.shared.android.ImageUtils
                    .getBitmapFromUri(this, uri, 1280)
            if (bitmap != null) {
                val wallpaperFile =
                    com.winlator.cmod.runtime.wine.WineThemeManager
                        .getUserWallpaperFile(this)
                com.winlator.cmod.shared.android.ImageUtils
                    .save(bitmap, wallpaperFile, Bitmap.CompressFormat.PNG, 100)
            }
        }

    override fun launchWallpaperImagePicker() {
        wallpaperImagePickerLauncher.launch("image/*")
    }

    // Avoid fragment tree traversal on every input event.
    private var cachedInputControlsFragment: InputControlsFragment? = null
    private val inputControlsFragmentTracker =
        object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(
                fm: androidx.fragment.app.FragmentManager,
                f: androidx.fragment.app.Fragment,
            ) {
                if (f is InputControlsFragment) cachedInputControlsFragment = f
            }

            override fun onFragmentPaused(
                fm: androidx.fragment.app.FragmentManager,
                f: androidx.fragment.app.Fragment,
            ) {
                if (f is InputControlsFragment) cachedInputControlsFragment = null
            }
        }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        cachedInputControlsFragment?.let { fragment ->
            if (fragment.dispatchKeyEvent(event)) return true
        }

        val keyCode = event.keyCode
        val action = event.action

        if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_B &&
            action == android.view.KeyEvent.ACTION_DOWN &&
            hideImeIfVisible()
        ) {
            return true
        }

        if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_MODE) {
            handleGuideButton(action, event.repeatCount)
            return true
        }

        if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_L2 ||
            keyCode == android.view.KeyEvent.KEYCODE_BUTTON_R2
        ) {
            val down = action == android.view.KeyEvent.ACTION_DOWN
            if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_L2) l2KeyDown = down else r2KeyDown = down
            updateGlassesCombo()
        }

        if (menuNavActive) {
            return dispatchMenuNavKey(event, keyCode, action)
        }

        if (drawerOpen) {
            return dispatchDrawerNavKey(event, keyCode, action)
        }

        if (rightDrawerOpen) {
            return dispatchDrawerNavKey(event, keyCode, action, friendsDrawerNavBridge)
        }

        // Prevent global controller buttons from falling through to launch actions.
        val isHandledGlobally =
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_BUTTON_START,
                android.view.KeyEvent.KEYCODE_BUTTON_A,
                android.view.KeyEvent.KEYCODE_BUTTON_B,
                android.view.KeyEvent.KEYCODE_BUTTON_X,
                android.view.KeyEvent.KEYCODE_BUTTON_Y,
                android.view.KeyEvent.KEYCODE_BUTTON_L1,
                android.view.KeyEvent.KEYCODE_BUTTON_R1,
                android.view.KeyEvent.KEYCODE_BUTTON_SELECT,
                android.view.KeyEvent.KEYCODE_BUTTON_THUMBL,
                android.view.KeyEvent.KEYCODE_BUTTON_THUMBR,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                -> true

                else -> false
            }

        val isDpad =
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
        if (isDpad) {
            if (action == android.view.KeyEvent.ACTION_UP) {
                dpadHeld = false
                return true
            }
            if (action == android.view.KeyEvent.ACTION_DOWN) {
                val now = android.os.SystemClock.uptimeMillis()
                if (!dpadHeld || (now - lastMoveTime >= MOVE_INTERVAL_MS)) {
                    val left = keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    val right = keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    val up = keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
                    val down = keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    when (currentTabKey) {
                        "library" -> moveLibraryFocus(left, right, up, down)
                        "downloads" -> routeDownloadsNav(left, right, up, down)
                        else -> moveStoreFocus(left, right, up, down)
                    }
                    lastMoveTime = now
                    dpadHeld = true
                }
            }
            return true
        }

        if (currentTabKey == "downloads" && action == android.view.KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_BUTTON_A,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                -> {
                    downloadsNavBridge.controllerActive = true
                    downloadsNavBridge.activate()
                    return true
                }

                android.view.KeyEvent.KEYCODE_BUTTON_Y -> {
                    downloadsNavBridge.controllerActive = true
                    downloadsNavBridge.secondary()
                    return true
                }
            }
        }

        if (action == android.view.KeyEvent.ACTION_DOWN) {
            if (isHandledGlobally) {
                keyEventFlow.tryEmit(event)
                return true
            }
        } else if (action == android.view.KeyEvent.ACTION_UP && isHandledGlobally) {
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        chasingBordersPaused.value = true
        UpdateService.stopHourlyLoop()
        UpdateService.cancelPostGameCheck()
    }

    override fun onResume() {
        super.onResume()
        settingsStickEngaged = 0
        joystickActive = false
        chasingBordersPaused.value = false
        if (hasCompletedInitialResume) {
            libraryPlaytimeRefreshSignal++
            SteamService.ensureHealthySession()
        } else {
            hasCompletedInitialResume = true
        }

        UpdateService.startHourlyLoop(this)
        processPendingRetroCloudBackup()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        settingsStickEngaged = 0
        joystickActive = false
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            DownloadService.clearCompletedDownloads()
        }
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(inputControlsFragmentTracker)
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        cachedInputControlsFragment?.let { fragment ->
            if (fragment.dispatchGenericMotionEvent(event)) return true
        }

        if ((event.source and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK &&
            event.action == android.view.MotionEvent.ACTION_MOVE
        ) {
            val lt = kotlin.math.max(
                event.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(android.view.MotionEvent.AXIS_BRAKE),
            )
            val rt = kotlin.math.max(
                event.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(android.view.MotionEvent.AXIS_GAS),
            )
            l2AxisDown = lt > 0.5f
            r2AxisDown = rt > 0.5f
            updateGlassesCombo()

            if (menuNavActive) {
                val sx = event.getAxisValue(android.view.MotionEvent.AXIS_X)
                val sy = event.getAxisValue(android.view.MotionEvent.AXIS_Y)
                val shx = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)
                val shy = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)
                val code =
                    when {
                        sx < -0.5f || shx < -0.5f -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
                        sx > 0.5f || shx > 0.5f -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                        sy < -0.5f || shy < -0.5f -> android.view.KeyEvent.KEYCODE_DPAD_UP
                        sy > 0.5f || shy > 0.5f -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
                        else -> 0
                    }
                if (code != 0) {
                    if (settingsStickEngaged == 0) {
                        settingsStickEngaged = code
                        handleSettingsStick(code)
                    }
                    return true
                }
                if (kotlin.math.abs(sx) < 0.35f && kotlin.math.abs(sy) < 0.35f &&
                    kotlin.math.abs(shx) < 0.35f && kotlin.math.abs(shy) < 0.35f
                ) {
                    settingsStickEngaged = 0
                }
                return true
            }

            val rz = event.getAxisValue(android.view.MotionEvent.AXIS_RZ)
            rightStickScrollState.value = rz

            val leftY = event.getAxisValue(android.view.MotionEvent.AXIS_Y)
            leftStickScrollState.value = leftY

            val x = event.getAxisValue(android.view.MotionEvent.AXIS_X)
            val y = event.getAxisValue(android.view.MotionEvent.AXIS_Y)
            val hatX = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)

            val isJoystickLeft = x < -0.5f
            val isJoystickRight = x > 0.5f
            val isJoystickUp = y < -0.5f
            val isJoystickDown = y > 0.5f

            val isHatLeft = hatX < -0.5f
            val isHatRight = hatX > 0.5f
            val isHatUp = hatY < -0.5f
            val isHatDown = hatY > 0.5f

            val now = event.eventTime

            val anyDirection =
                isHatLeft || isHatRight || isHatUp || isHatDown ||
                    isJoystickLeft || isJoystickRight || isJoystickUp || isJoystickDown

            if (anyDirection) {
                if (now - lastMoveTime >= MOVE_INTERVAL_MS) {
                    val left = isHatLeft || isJoystickLeft
                    val right = isHatRight || isJoystickRight
                    val up = isHatUp || isJoystickUp
                    val down = isHatDown || isJoystickDown
                    if (menuNavActive || drawerOpen || rightDrawerOpen) {
                        val dpadCode =
                            when {
                                left -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
                                right -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                                up -> android.view.KeyEvent.KEYCODE_DPAD_UP
                                down -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
                                else -> 0
                            }
                        if (dpadCode != 0) injectKeyEvent(dpadCode)
                    } else {
                        when (currentTabKey) {
                            "library" -> moveLibraryFocus(left, right, up, down)
                            "downloads" -> routeDownloadsNav(left, right, up, down)
                            else -> moveStoreFocus(left, right, up, down)
                        }
                    }
                    lastMoveTime = now
                    joystickActive = true
                }
                return true
            } else if (joystickActive) {
                joystickActive = false
                lastMoveTime = 0L
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    internal var currentTabKey: String = "library"

    @Volatile
    private var inSettingsRoute: Boolean = false

    @Volatile
    internal var drawerOpen: Boolean = false
    internal var rightDrawerOpen: Boolean = false
    internal val guideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal var guideHoldRunnable: Runnable? = null

    internal val menuNavActive: Boolean
        get() = inSettingsRoute

    var storeItemClickCallback: ((Int) -> Unit)? = null

    private fun dispatchMenuNavKey(
        event: android.view.KeyEvent,
        keyCode: Int,
        action: Int,
    ): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            -> {
                if (settingsNavBridge.zone == SettingsFocusZone.SIDEBAR) {
                    if (action == android.view.KeyEvent.ACTION_DOWN) applySettingsSidebarNav(keyCode)
                    return true
                }
                if (action == android.view.KeyEvent.ACTION_DOWN) navigateSettingsContent(keyCode)
                return true
            }

            android.view.KeyEvent.KEYCODE_BUTTON_A,
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            -> {
                if (settingsNavBridge.zone == SettingsFocusZone.SIDEBAR) {
                    if (action == android.view.KeyEvent.ACTION_DOWN) enterSettingsContent()
                    return true
                }
                if (action == android.view.KeyEvent.ACTION_DOWN) settingsNavBridge.contentActivate()
                return true
            }

            android.view.KeyEvent.KEYCODE_BUTTON_B -> {
                if (action == android.view.KeyEvent.ACTION_DOWN) {
                    onBackPressedDispatcher.onBackPressed()
                }
                return true
            }

            android.view.KeyEvent.KEYCODE_BUTTON_Y -> {
                if (action == android.view.KeyEvent.ACTION_DOWN &&
                    settingsNavBridge.zone == SettingsFocusZone.CONTENT
                ) {
                    settingsNavBridge.contentSecondary()
                }
                return true
            }

            android.view.KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (action == android.view.KeyEvent.ACTION_DOWN &&
                    settingsNavBridge.zone == SettingsFocusZone.CONTENT
                ) {
                    settingsNavBridge.contentSectionPrev()
                }
                return true
            }

            android.view.KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (action == android.view.KeyEvent.ACTION_DOWN &&
                    settingsNavBridge.zone == SettingsFocusZone.CONTENT
                ) {
                    settingsNavBridge.contentSectionNext()
                }
                return true
            }

            android.view.KeyEvent.KEYCODE_BUTTON_X,
            android.view.KeyEvent.KEYCODE_BUTTON_START,
            -> return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dispatchDrawerNavKey(
        event: android.view.KeyEvent,
        keyCode: Int,
        action: Int,
        bridge: DownloadsNavBridge = drawerNavBridge,
    ): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            -> {
                if (action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    bridge.controllerActive = true
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> bridge.left()
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> bridge.right()
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> bridge.up()
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> bridge.down()
                    }
                }
                return true
            }

            android.view.KeyEvent.KEYCODE_BUTTON_A,
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            -> {
                if (action == android.view.KeyEvent.ACTION_DOWN) {
                    bridge.controllerActive = true
                    bridge.activate()
                }
                return true
            }

            android.view.KeyEvent.KEYCODE_BUTTON_Y -> {
                if (action == android.view.KeyEvent.ACTION_DOWN) {
                    bridge.controllerActive = true
                    bridge.secondary()
                }
                return true
            }

            android.view.KeyEvent.KEYCODE_BUTTON_B,
            android.view.KeyEvent.KEYCODE_BUTTON_SELECT,
            -> {
                if (action == android.view.KeyEvent.ACTION_DOWN) keyEventFlow.tryEmit(event)
                return true
            }

            android.view.KeyEvent.KEYCODE_BUTTON_X,
            android.view.KeyEvent.KEYCODE_BUTTON_START,
            android.view.KeyEvent.KEYCODE_BUTTON_L1,
            android.view.KeyEvent.KEYCODE_BUTTON_R1,
            android.view.KeyEvent.KEYCODE_BUTTON_THUMBL,
            android.view.KeyEvent.KEYCODE_BUTTON_THUMBR,
            -> return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (maybeForwardFrontendLaunch()) return
        handleSettingsIntent(intent)
    }

    internal fun retryPendingRetroCloudBackup() = processPendingRetroCloudBackup()

    private fun processPendingRetroCloudBackup() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val hasLegacy = prefs.getString("retro_pending_backup_id", null) != null
        val hasDolphin = com.winlator.cmod.feature.retro.DolphinCloudSync.peekPending(this) != null
        if (!hasLegacy && !hasDolphin) return
        if (!com.winlator.cmod.feature.sync.google.GameSaveBackupManager.isDriveConnected(this)) return
        runCatching {
            com.winlator.cmod.feature.sync.google.PlayGamesBootstrap.ensureInitialized(this)
            com.google.android.gms.games.PlayGames
                .getGamesSignInClient(this)
                .signIn()
                .addOnCompleteListener { runPendingRetroUploads() }
        }.onFailure { runPendingRetroUploads() }
    }

    private fun runPendingRetroUploads() {
        if (retroCloudUploadBusy) return
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val pendingId = prefs.getString("retro_pending_backup_id", null)
        val pendingName = prefs.getString("retro_pending_backup_name", null)
        val dolphinPending = com.winlator.cmod.feature.retro.DolphinCloudSync.peekPending(this)
        if ((pendingId == null || pendingName == null) && dolphinPending == null) return
        retroCloudUploadBusy = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                dolphinPending?.let { p ->
                    if (uploadRetroCloudBackup(p.cloudId, p.gameName)) {
                        com.winlator.cmod.feature.retro.DolphinCloudSync.clearPending(this@UnifiedActivity)
                        if (p.fingerprint.isNotEmpty()) {
                            prefs.edit().putString("retro_cloud_fp_${p.cloudId}", p.fingerprint).apply()
                        }
                    }
                }
                if (pendingId != null && pendingName != null &&
                    uploadRetroCloudBackup(pendingId, pendingName)
                ) {
                    prefs.edit().remove("retro_pending_backup_id").remove("retro_pending_backup_name").apply()
                }
            } finally {
                retroCloudUploadBusy = false
            }
        }
    }

    private suspend fun uploadRetroCloudBackup(cloudId: String, gameName: String): Boolean {
        val result =
            runCatching {
                GameSaveBackupManager.backupSaveToGoogle(
                    this@UnifiedActivity,
                    GameSaveBackupManager.GameSource.CUSTOM,
                    cloudId,
                    gameName,
                    GameSaveBackupManager.BackupOrigin.AUTO,
                    com.winlator.cmod.feature.sync.google.GoogleAuthMode.RESUME,
                    customSaveDir = com.winlator.cmod.feature.retro.RetroSaveStates.gameDir(this, gameName),
                )
            }.getOrNull()
        android.util.Log.i("WnDolphin", "upload id=$cloudId success=${result?.success} msg=${result?.message}")
        if (result?.success == true) {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putLong("retro_cloud_mark_$cloudId", System.currentTimeMillis()).apply()
            return true
        }
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        instance = this
        super.onCreate(savedInstanceState)
        if (!SetupWizardActivity.isSetupComplete(this) || !ImageFs.find(this).isUpToDate) {
            startActivity(
                Intent(this, SetupWizardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
            com.winlator.cmod.shared.android.AppUtils
                .applyOpenActivityTransition(this, 0, 0)
            finish()
            return
        }

        if (maybeForwardFrontendLaunch()) return

        supportFragmentManager.registerFragmentLifecycleCallbacks(inputControlsFragmentTracker, true)
        com.winlator.cmod.runtime.display.GlassesManager.init(this)
        bootstrapStartupState()
        maybeAutoSignInGoogleOnLaunch()
        processPendingRetroCloudBackup()

        // Surface store-session events as toasts.
        lifecycleScope.launch {
            com.winlator.cmod.feature.stores.common.StoreSessionBus.events.collect { event ->
                val label =
                    when (event.store) {
                        com.winlator.cmod.feature.stores.common.Store.EPIC -> "Epic"
                        com.winlator.cmod.feature.stores.common.Store.GOG -> "GOG"
                        com.winlator.cmod.feature.stores.common.Store.STEAM -> "Steam"
                    }
                when (event) {
                    is com.winlator.cmod.feature.stores.common.StoreSessionEvent.SessionExpired -> {
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            this@UnifiedActivity,
                            "$label session expired — please sign in again",
                            android.widget.Toast.LENGTH_LONG,
                        )
                    }
                    is com.winlator.cmod.feature.stores.common.StoreSessionEvent.SessionRestored -> Unit
                    is com.winlator.cmod.feature.stores.common.StoreSessionEvent.SessionRefreshed -> {
                        Unit
                    }
                }
            }
        }

        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val initialSettingsNavigation = extractSettingsNavigation(intent)
        if (initialSettingsNavigation != null) {
            consumeSettingsIntent(intent)
        }

        // Exclude the drawer edge from system back gesture where Android allows it.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val decorView = window.decorView
            val updateDrawerGestureExclusion = {
                val leftEdgeWidth = (32 * resources.displayMetrics.density).toInt()
                val exclusionRect = android.graphics.Rect(0, 0, leftEdgeWidth, decorView.height)
                decorView.systemGestureExclusionRects = listOf(exclusionRect)
            }
            decorView.post(updateDrawerGestureExclusion)
            decorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateDrawerGestureExclusion()
            }
        }

        setContent {
            val navController = rememberNavController()
            rootNavController = navController

            DisposableEffect(navController) {
                val listener =
                    androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
                        inSettingsRoute = destination.route?.startsWith("settings") == true
                    }
                navController.addOnDestinationChangedListener(listener)
                onDispose { navController.removeOnDestinationChangedListener(listener) }
            }

            LaunchedEffect(Unit) {
                val pending = pendingNavigation
                if (pending != null) {
                    navigateToSettings(
                        pending.item,
                        pending.profileId,
                        pending.editContainerId,
                        pending.returnToGameOnBack,
                    )
                    pendingNavigation = null
                } else {
                    handleSettingsIntent(intent)
                }
            }

            WinNativeTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Accent,
                        background = BgDark,
                        surface = SurfaceDark,
                        onSurface = TextPrimary,
                    ),
            ) {
                NavHost(
                    navController = navController,
                    startDestination =
                        initialSettingsNavigation?.let {
                            buildSettingsRoute(it.item, it.profileId, it.editContainerId, it.returnToGameOnBack)
                        } ?: "hub",
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        val fromRoute = initialState.destination.route
                        val toRoute = targetState.destination.route
                        if (
                            (
                                (fromRoute == "hub" && toRoute?.startsWith("settings") == true) ||
                                    (fromRoute?.startsWith("settings") == true && toRoute == "hub")
                            )
                        ) {
                            fadeIn(tween(220, easing = FastOutSlowInEasing))
                        } else {
                            EnterTransition.None
                        }
                    },
                    exitTransition = {
                        val fromRoute = initialState.destination.route
                        val toRoute = targetState.destination.route
                        if (
                            (
                                (fromRoute == "hub" && toRoute?.startsWith("settings") == true) ||
                                    (fromRoute?.startsWith("settings") == true && toRoute == "hub")
                            )
                        ) {
                            fadeOut(tween(220, easing = FastOutSlowInEasing))
                        } else {
                            ExitTransition.None
                        }
                    },
                    popEnterTransition = {
                        val fromRoute = initialState.destination.route
                        val toRoute = targetState.destination.route
                        if (
                            (
                                (fromRoute == "hub" && toRoute?.startsWith("settings") == true) ||
                                    (fromRoute?.startsWith("settings") == true && toRoute == "hub")
                            )
                        ) {
                            fadeIn(tween(220, easing = FastOutSlowInEasing))
                        } else {
                            EnterTransition.None
                        }
                    },
                    popExitTransition = {
                        val fromRoute = initialState.destination.route
                        val toRoute = targetState.destination.route
                        if (
                            (
                                (fromRoute == "hub" && toRoute?.startsWith("settings") == true) ||
                                    (fromRoute?.startsWith("settings") == true && toRoute == "hub")
                            )
                        ) {
                            fadeOut(tween(220, easing = FastOutSlowInEasing))
                        } else {
                            ExitTransition.None
                        }
                    },
                ) {
                    composable("hub") {
                        LaunchedEffect(Unit) { isPoppingSettings = false }
                        UnifiedHub()
                    }
                    composable(
                        "settings?item={item}&profileId={profileId}&editContainerId={editContainerId}&returnToGameOnBack={returnToGameOnBack}",
                        arguments =
                            listOf(
                                navArgument("item") {
                                    type = NavType.StringType
                                    defaultValue = SettingsNavItem.CONTAINERS.name
                                },
                                navArgument("profileId") {
                                    type = NavType.IntType
                                    defaultValue = 0
                                },
                                navArgument("editContainerId") {
                                    type = NavType.IntType
                                    defaultValue = 0
                                },
                                navArgument("returnToGameOnBack") {
                                    type = NavType.BoolType
                                    defaultValue = false
                                },
                            ),
                    ) { backStackEntry ->
                        val itemName = backStackEntry.arguments?.getString("item") ?: SettingsNavItem.CONTAINERS.name
                        val startItem =
                            try {
                                SettingsNavItem.valueOf(itemName)
                            } catch (_: Exception) {
                                SettingsNavItem.CONTAINERS
                            }
                        val profileId = backStackEntry.arguments?.getInt("profileId") ?: 0
                        val editContainerId = backStackEntry.arguments?.getInt("editContainerId") ?: 0
                        val returnToGameOnBack =
                            backStackEntry.arguments?.getBoolean("returnToGameOnBack") ?: false

                        val exitSettingsToHubOnce: () -> Unit = {
                            if (!isPoppingSettings) {
                                isPoppingSettings = true
                                if (returnToGameOnBack) {
                                    setResult(android.app.Activity.RESULT_OK)
                                    finish()
                                } else {
                                    val poppedToHub = navController.popBackStack("hub", inclusive = false)
                                    if (!poppedToHub) {
                                        navController.navigate("hub") {
                                            popUpTo(navController.graph.startDestinationId) {
                                                inclusive = true
                                            }
                                            launchSingleTop = true
                                        }
                                    }
                                }
                            }
                        }

                        SettingsHost(
                            bridge = settingsNavBridge,
                            startItem = startItem,
                            selectedProfileId = profileId,
                            bordersPaused = chasingBordersPaused.value,
                            onBack = exitSettingsToHubOnce,
                        )
                        BackHandler(enabled = true) { exitSettingsToHubOnce() }

                        if (editContainerId > 0) {
                            LaunchedEffect(editContainerId) {
                                val activity = this@UnifiedActivity
                                val cm = ContainerManager(activity)
                                val container = cm.getContainerById(editContainerId)
                                if (container != null) {
                                    com.winlator.cmod.feature.settings
                                        .ContainerSettingsComposeDialog(
                                            activity,
                                            container,
                                        ) { exitSettingsToHubOnce() }
                                        .show()
                                } else {
                                    exitSettingsToHubOnce()
                                }
                            }
                        }
                    }
                }

                TaskProgressHost()

                UpdateHost()
            }
        }
        UpdateService.onAppStarted(this)
        scheduleDeferredStoreBootstrap()
    }

    internal data class TabDef(
        val label: String,
        val key: String,
    )

    internal enum class GameSettingsScreen {
        Menu,
        Shortcut,
        CloudSaves,
        Uninstall,
    }

    internal data class HomeShortcutUiState(
        val shortcut: Shortcut? = null,
        val isPinned: Boolean = false,
        val loaded: Boolean = false,
    )

    internal data class ArtworkCacheId(
        val store: String,
        val gameId: String,
    )

    internal data class GameSettingsActionItem(
        val title: String,
        val icon: ImageVector,
        val accentColor: Color = Accent,
        val onClick: () -> Unit,
    )

    // Library Game Detail Dialog

    internal enum class LibraryDetailScreen { Main, Shortcut, CloudSaves, Uninstall }

    internal enum class LibraryDetailPopup { CloudSaves }

    internal enum class HeroLaunchPopup { BootToDesktop, RemoveShortcut }

    internal enum class HeroBootChoice { Desktop, Cube32, Cube64, Input32, Input64 }

    internal data class DownloadCancelRequest(
        val ids: List<String>,
        val isCancelAll: Boolean,
    )

    internal fun Shortcut.hasExistingArtwork(extraKey: String): Boolean =
        (getExtra(extraKey)
            .takeIf { it.isNotBlank() }
            ?.let { java.io.File(it).isFile } == true)

    internal fun buildWineExecCommand(
        container: com.winlator.cmod.runtime.container.Container?,
        gameInstallPath: String,
        relativeExePath: String,
    ): String {
        val exeFile = java.io.File(gameInstallPath, relativeExePath.replace("\\", "/"))
        return buildWineExecCommand(container, gameInstallPath, exeFile)
    }

    internal fun buildWineExecCommand(
        container: com.winlator.cmod.runtime.container.Container?,
        gameInstallPath: String,
        exeFile: java.io.File,
    ): String {
        val windowsPath =
            container?.let {
                com.winlator.cmod.runtime.wine.WineUtils
                    .resolveGameExeWindowsPath(
                        it,
                        "CUSTOM",
                        gameInstallPath,
                        exeFile.absolutePath,
                    )
            } ?: run {
                com.winlator.cmod.runtime.wine.WineUtils.getDosPath(exeFile.absolutePath)
            }
        return "wine \"$windowsPath\""
    }

}

@Composable
fun ControllerBadge(
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val corner = if (compact) 11.dp else 15.dp
    Box(
        modifier =
            modifier
                .defaultMinSize(minHeight = if (compact) 16.dp else 22.dp)
                .background(Color(0xFF394048), RoundedCornerShape(corner))
                .border(1.dp, Color(0xFF8B949E).copy(alpha = 0.5f), RoundedCornerShape(corner))
                .padding(horizontal = if (compact) 5.dp else 10.dp, vertical = if (compact) 1.dp else 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color(0xFFE6EDF3),
            fontSize = if (compact) 9.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = if (compact) 11.sp else 15.sp,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

internal inline fun android.content.Context.runIfOnlineOrToast(action: () -> Unit) {
    if (com.winlator.cmod.app.service.NetworkMonitor.hasInternet.value) {
        action()
    } else {
        com.winlator.cmod.shared.ui.toast.WinToast.show(
            this,
            getString(R.string.downloads_no_internet),
            android.widget.Toast.LENGTH_SHORT,
        )
    }
}
