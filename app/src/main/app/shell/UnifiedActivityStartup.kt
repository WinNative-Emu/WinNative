package com.winlator.cmod.app.shell
import com.winlator.cmod.app.shell.UnifiedActivity.PendingNavigation
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
import com.winlator.cmod.feature.stores.itch.service.ItchService
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

// Settings-intent routing + desktop launch + startup bootstrap + tab building, split out of UnifiedActivity.kt (behavior-identical).

internal fun UnifiedActivity.navigateToSettings(
    item: SettingsNavItem = SettingsNavItem.CONTAINERS,
    profileId: Int = 0,
    editContainerId: Int = 0,
    returnToGameOnBack: Boolean = false,
) {
    // In-activity settings navigation does not trigger Activity resume.
    reapplyPreferredRefreshRate()
    val route = buildSettingsRoute(item, profileId, editContainerId, returnToGameOnBack)
    val nav = rootNavController
    if (nav == null) {
        pendingNavigation = PendingNavigation(item, profileId, editContainerId, returnToGameOnBack)
        return
    }
    isPoppingSettings = false
    nav.navigate(route) {
        launchSingleTop = true
    }
}

internal fun UnifiedActivity.buildSettingsRoute(
    item: SettingsNavItem = SettingsNavItem.CONTAINERS,
    profileId: Int = 0,
    editContainerId: Int = 0,
    returnToGameOnBack: Boolean = false,
): String =
    "settings?item=${item.name}&profileId=$profileId&editContainerId=$editContainerId&returnToGameOnBack=$returnToGameOnBack"


internal fun UnifiedActivity.extractSettingsNavigation(intent: Intent?): PendingNavigation? {
    if (intent == null) return null

    val editContainerId = intent.getIntExtra("edit_container_id", 0)
    if (editContainerId > 0) {
        return PendingNavigation(SettingsNavItem.CONTAINERS, 0, editContainerId)
    }

    if (intent.getBooleanExtra("edit_input_controls", false)) {
        val profileId = intent.getIntExtra("selected_profile_id", 0)
        val returnToGameOnBack = intent.getBooleanExtra("return_to_game_on_back", false)
        return PendingNavigation(SettingsNavItem.INPUT_CONTROLS, profileId, 0, returnToGameOnBack)
    }

    val selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0)
    if (selectedMenuItemId > 0) {
        val target = SettingsNavItem.fromMenuId(selectedMenuItemId) ?: SettingsNavItem.CONTAINERS
        return PendingNavigation(target, 0, 0)
    }

    return null
}

internal fun UnifiedActivity.consumeSettingsIntent(intent: Intent?) {
    intent ?: return
    intent.removeExtra("edit_container_id")
    intent.removeExtra("edit_input_controls")
    intent.removeExtra("selected_profile_id")
    intent.removeExtra("selected_menu_item_id")
    intent.removeExtra("return_to_game_on_back")
}

internal fun UnifiedActivity.handleSettingsIntent(intent: Intent?) {
    val request = extractSettingsNavigation(intent) ?: return
    consumeSettingsIntent(intent)
    navigateToSettings(request.item, request.profileId, request.editContainerId, request.returnToGameOnBack)
}

internal fun UnifiedActivity.maybeForwardFrontendLaunch(): Boolean {
    val source = intent ?: return false
    val path = resolveIncomingDesktopPath(source) ?: return false
    startActivity(
        Intent(this, XServerDisplayActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("shortcut_path", path)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        },
    )
    finish()
    return true
}

internal fun UnifiedActivity.resolveIncomingDesktopPath(source: Intent): String? {
    materializeDesktop(source.data)?.let { return it }
    source.clipData?.let { clip ->
        for (i in 0 until clip.itemCount) {
            materializeDesktop(clip.getItemAt(i).uri)?.let { return it }
            materializeDesktop(clip.getItemAt(i).text?.toString())?.let { return it }
        }
    }
    val extras = source.extras ?: return null
    for (key in extras.keySet()) {
        materializeDesktop(extras.get(key))?.let { return it }
    }
    return null
}

internal fun UnifiedActivity.materializeDesktop(value: Any?): String? =
    when (value) {
        is android.net.Uri -> materializeDesktopUri(value)
        is String ->
            if (value.startsWith("content://") || value.startsWith("file://")) {
                materializeDesktopUri(android.net.Uri.parse(value))
            } else {
                java.io.File(value).takeIf { it.isFile && looksLikeDesktopFile(it) }?.absolutePath
            }
        else -> null
    }


internal fun UnifiedActivity.materializeDesktopUri(uri: android.net.Uri): String? {
    when (uri.scheme?.lowercase()) {
        "file" -> {
            val file = uri.path?.let { java.io.File(it) }
            if (file != null && file.isFile && looksLikeDesktopFile(file)) return file.absolutePath
        }
        "content" -> {
            val resolved = com.winlator.cmod.shared.io.FileUtils.getFilePathFromUri(this, uri)
            if (!resolved.isNullOrEmpty()) {
                val file = java.io.File(resolved)
                if (file.isFile && looksLikeDesktopFile(file)) return file.absolutePath
            }
            return copyUriToCacheDesktop(uri)
        }
    }
    return null
}

internal fun UnifiedActivity.copyUriToCacheDesktop(uri: android.net.Uri): String? =
    runCatching {
        val out = java.io.File(cacheDir, "frontend_launch.desktop")
        val copied =
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
        if (copied && out.isFile && looksLikeDesktopFile(out)) out.absolutePath else null
    }.getOrNull()


internal fun UnifiedActivity.looksLikeDesktopFile(file: java.io.File): Boolean {
    if (!file.isFile || file.length() > 1_000_000L) return false
    return runCatching {
        val text = file.readText()
        text.contains("[Desktop Entry]") || text.contains("container_id")
    }.getOrDefault(false)
}

internal fun UnifiedActivity.bootstrapStartupState() {
    startupBootstrapReady = false
    startupLibraryLayoutMode = null
    startupStoreVisible = null
    startupContentFilters = null

    lifecycleScope.launch(Dispatchers.IO) {
        val appContext = applicationContext
        val resolvedLayoutMode =
            runCatching {
                PrefManager.init(appContext)
                LibraryLayoutMode.valueOf(PrefManager.libraryLayoutMode)
            }.getOrElse { error ->
                Log.w("UnifiedActivity", "Failed to resolve initial library layout", error)
                LibraryLayoutMode.GRID_4
            }

        val resolvedStoreVisible =
            runCatching {
                val saved = PrefManager.libraryStoreVisible.split(",").toSet()
                mapOf(
                    "steam" to ("steam" in saved),
                    "epic" to ("epic" in saved),
                    "gog" to ("gog" in saved),
                    "itch" to ("itch" in saved),
                )
            }.getOrElse { mapOf("steam" to true, "epic" to true, "gog" to true, "itch" to true) }

        val resolvedContentFilters =
            runCatching {
                val saved = PrefManager.libraryContentFilters.split(",").toSet()
                mapOf(
                    "games" to ("games" in saved),
                    "dlc" to ("dlc" in saved),
                    "applications" to ("applications" in saved),
                    "tools" to ("tools" in saved),
                )
            }.getOrElse { mapOf("games" to true, "dlc" to false, "applications" to false, "tools" to false) }

        runCatching { dbProvider.get() }
            .onFailure { Log.w("UnifiedActivity", "Database warmup failed", it) }
        runCatching { EpicAuthManager.updateLoginStatus(appContext) }
            .onFailure { Log.w("UnifiedActivity", "Epic auth warmup failed", it) }
        runCatching { GOGAuthManager.updateLoginStatus(appContext) }
            .onFailure { Log.w("UnifiedActivity", "GOG auth warmup failed", it) }
        runCatching { ItchService.start(appContext) }
            .onFailure { Log.w("UnifiedActivity", "itch.io service warmup failed", it) }
        runCatching { SteamService.initLoginStatus(appContext) }
            .onFailure { Log.w("UnifiedActivity", "Steam auth warmup failed", it) }

        withContext(Dispatchers.Main.immediate) {
            startupLibraryLayoutMode = resolvedLayoutMode
            currentLibraryLayoutMode = resolvedLayoutMode
            startupStoreVisible = resolvedStoreVisible
            startupContentFilters = resolvedContentFilters
            startupBootstrapReady = true
        }
    }
}

/** When the "Sign in to Google on launch" toggle is on, attempt a silent Play Games sign-in once per launch. */
internal fun UnifiedActivity.maybeAutoSignInGoogleOnLaunch() {
    if (!com.winlator.cmod.feature.sync.google.CloudSyncManager.isAutoSignInOnLaunchEnabled(this)) return
    runCatching {
        com.winlator.cmod.feature.sync.google.PlayGamesBootstrap.ensureInitialized(this)
        com.google.android.gms.games.PlayGames
            .getGamesSignInClient(this)
            .signIn()
            .addOnCompleteListener { task ->
                val authed = task.isSuccessful && task.result?.isAuthenticated == true
                if (authed) {
                    com.winlator.cmod.feature.sync.google.GameSaveBackupManager
                        .setDriveConnected(applicationContext, true)
                    retryPendingRetroCloudBackup()
                }
            }
    }.onFailure {
        timber.log.Timber.tag("UnifiedActivity").w(it, "Auto Google sign-in on launch failed")
    }
}

internal fun UnifiedActivity.scheduleDeferredStoreBootstrap() {
    window.decorView.post {
        if (isFinishing || isDestroyed) return@post
        lifecycleScope.launch(Dispatchers.IO) {
            if (EpicService.hasStoredCredentials(this@scheduleDeferredStoreBootstrap)) {
                EpicService.start(this@scheduleDeferredStoreBootstrap)
                // Keep token validation off the first-frame path.
                EpicAuthManager.getStoredCredentials(this@scheduleDeferredStoreBootstrap)
                com.winlator.cmod.feature.stores.epic.service.EpicTokenRefreshWorker
                    .schedule(this@scheduleDeferredStoreBootstrap)
            }

            if (SteamService.hasStoredCredentials(this@scheduleDeferredStoreBootstrap)) {
                SteamService.start(this@scheduleDeferredStoreBootstrap)
            }

            if (GOGAuthManager.isLoggedIn(this@scheduleDeferredStoreBootstrap)) {
                GOGService.start(this@scheduleDeferredStoreBootstrap)
            }

            SteamService.maybeRepairInstalledMetadataOnStartup(this@scheduleDeferredStoreBootstrap)
        }
    }
}

internal fun UnifiedActivity.buildTabs(storeVisible: Map<String, Boolean>): List<TabDef> {
    val base =
        mutableListOf(
            TabDef(getString(R.string.common_ui_library), "library"),
            TabDef(getString(R.string.common_ui_downloads), "downloads"),
        )
    if (storeVisible["steam"] != false) base.add(TabDef("Steam", "steam"))
    if (storeVisible["epic"] != false) base.add(TabDef("Epic", "epic"))
    if (storeVisible["gog"] != false) base.add(TabDef("GOG", "gog"))
    if (storeVisible["itch"] != false) base.add(TabDef("itch.io", "itch"))
    return base
}

@Composable
internal fun UnifiedActivity.rememberSteamInstallStateMap(apps: List<SteamApp>): Map<Int, Boolean> {
    var installStateMap by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }

    LaunchedEffect(apps) {
        installStateMap =
            withContext(Dispatchers.IO) {
                apps.associate { it.id to SteamService.isAppInstalled(it.id) }
            }
    }

    return installStateMap
}

@Composable
internal fun <K> UnifiedActivity.rememberInstallPathStateMap(entries: List<Pair<K, String?>>): Map<K, Boolean>
    where K : Any {
    var installStateMap by remember { mutableStateOf<Map<K, Boolean>>(emptyMap()) }

    LaunchedEffect(entries) {
        installStateMap =
            withContext(Dispatchers.IO) {
                entries.associate { (key, path) ->
                    key to (path?.isNotBlank() == true && java.io.File(path).exists())
                }
            }
    }

    return installStateMap
}

@Composable
internal fun UnifiedActivity.rememberEpicInstallStateMap(
    context: android.content.Context,
    apps: List<EpicGame>,
): Map<Int, Boolean> {
    var installStateMap by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }

    LaunchedEffect(apps) {
        installStateMap =
            withContext(Dispatchers.IO) {
                apps.associate { it.id to EpicService.isGameInstalled(context, it.id) }
            }
    }

    return installStateMap
}

@Composable
internal fun UnifiedActivity.rememberGogInstallStateMap(apps: List<GOGGame>): Map<String, Boolean> {
    var installStateMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    LaunchedEffect(apps) {
        installStateMap =
            withContext(Dispatchers.IO) {
                apps.associate { it.id to GOGService.isGameInstalled(it.id) }
            }
    }

    return installStateMap
}
