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

// Task-progress popup + store update-check helpers, split out of UnifiedActivity.kt (behavior-identical).

internal fun UnifiedActivity.showTaskProgressPopup(
    info: DownloadInfo,
    gameName: String,
    completeMsg: String,
    failedMsg: String,
    completeAsToast: Boolean = false,
) {
    taskCheckingShown = false
    taskProgressInfo = info
    taskProgressGameName = gameName
    taskProgressCompleteMsg = completeMsg
    taskProgressFailedMsg = failedMsg
    taskProgressCompleteAsToast = completeAsToast
    taskProgressShown = true
    taskDoneMessage = null
}

// Runs a Steam update check behind the checking pop-up.
internal fun UnifiedActivity.startUpdateCheck(appId: Int, gameName: String) {
    if (updateCheckInProgress) return
    if (!com.winlator.cmod.app.service.NetworkMonitor.hasInternet.value) {
        com.winlator.cmod.shared.ui.toast.WinToast.show(
            this,
            getString(R.string.downloads_no_internet),
            android.widget.Toast.LENGTH_SHORT,
        )
        return
    }
    updateCheckInProgress = true
    taskCheckingGameName = gameName
    taskCheckingShown = true
    taskDoneMessage = null
    lifecycleScope.launch {
        val result =
            runCatching {
                withContext(Dispatchers.IO) { SteamService.checkForAppUpdate(appId) }
            }.getOrNull()
        try {
        when {
            result == null || result.message != null -> {
                taskCheckingShown = false
                taskDoneFailed = true
                taskDoneMessage = getString(R.string.store_game_update_check_failed_notice)
            }
            result.hasUpdate -> {
                val started =
                    withContext(Dispatchers.IO) {
                        SteamService.downloadAppForUpdate(appId, result.depotIds)
                    }
                if (started != null) {
                    showTaskProgressPopup(
                        started,
                        gameName,
                        getString(R.string.store_game_update_complete),
                        getString(R.string.store_game_update_failed_notice),
                    )
                } else {
                    // A download is already running — downloadApp showed its toast.
                    taskCheckingShown = false
                }
            }
            else -> {
                taskCheckingShown = false
                taskDoneFailed = false
                taskDoneMessage = getString(R.string.store_game_no_updates_notice)
            }
        }
        } finally {
            updateCheckInProgress = false
        }
    }
}

internal fun UnifiedActivity.startGogUpdateCheck(gameId: String, gameName: String) {
    if (updateCheckInProgress) return
    if (!com.winlator.cmod.app.service.NetworkMonitor.hasInternet.value) {
        com.winlator.cmod.shared.ui.toast.WinToast.show(
            this,
            getString(R.string.downloads_no_internet),
            android.widget.Toast.LENGTH_SHORT,
        )
        return
    }
    updateCheckInProgress = true
    taskCheckingGameName = gameName
    taskCheckingShown = true
    taskDoneMessage = null
    lifecycleScope.launch {
        val result =
            runCatching {
                withContext(Dispatchers.IO) { GOGService.checkForGameUpdate(this@startGogUpdateCheck, gameId) }
            }.getOrNull()
        try {
            when {
                result == null || result.message != null -> {
                    taskCheckingShown = false
                    taskDoneFailed = true
                    taskDoneMessage = getString(R.string.store_game_update_check_failed_notice)
                }
                result.hasUpdate -> {
                    val started =
                        withContext(Dispatchers.IO) {
                            GOGService.updateGameFiles(this@startGogUpdateCheck, gameId)
                        }
                    if (started != null) {
                        showTaskProgressPopup(
                            started,
                            gameName,
                            getString(R.string.store_game_update_complete),
                            getString(R.string.store_game_update_failed_notice),
                        )
                    } else {
                        taskCheckingShown = false
                    }
                }
                else -> {
                    taskCheckingShown = false
                    taskDoneFailed = false
                    taskDoneMessage = getString(R.string.store_game_no_updates_notice)
                }
            }
        } finally {
            updateCheckInProgress = false
        }
    }
}

internal fun UnifiedActivity.startItchUpdateCheck(gameId: Int, gameName: String) {
    if (updateCheckInProgress) return
    if (!com.winlator.cmod.app.service.NetworkMonitor.hasInternet.value) {
        com.winlator.cmod.shared.ui.toast.WinToast.show(
            this,
            getString(R.string.downloads_no_internet),
            android.widget.Toast.LENGTH_SHORT,
        )
        return
    }
    updateCheckInProgress = true
    taskCheckingGameName = gameName
    taskCheckingShown = true
    taskDoneMessage = null
    lifecycleScope.launch {
        val result =
            runCatching {
                com.winlator.cmod.feature.stores.itch.service.ItchService
                    .checkInstalledForUpdate(this@startItchUpdateCheck, gameId)
            }.getOrNull()
        try {
            val upload = result?.upload
            when {
                result == null -> {
                    taskCheckingShown = false
                    taskDoneFailed = true
                    taskDoneMessage = getString(R.string.store_game_update_check_failed_notice)
                }
                result.available && upload != null -> {
                    val started =
                        withContext(Dispatchers.IO) {
                            val queued =
                                com.winlator.cmod.feature.stores.itch.service.ItchService
                                    .downloadInstalledUpdate(this@startItchUpdateCheck, gameId, upload)
                            if (queued) {
                                com.winlator.cmod.feature.stores.itch.service.ItchService
                                    .downloadInfo(gameId)
                            } else {
                                null
                            }
                        }
                    if (started != null) {
                        showTaskProgressPopup(
                            started,
                            gameName,
                            getString(R.string.store_game_update_complete),
                            getString(R.string.store_game_update_failed_notice),
                        )
                    } else {
                        taskCheckingShown = false
                        taskDoneFailed = true
                        taskDoneMessage = getString(R.string.store_game_update_failed_notice)
                    }
                }
                else -> {
                    taskCheckingShown = false
                    taskDoneFailed = false
                    taskDoneMessage = getString(R.string.store_game_no_updates_notice)
                }
            }
        } finally {
            updateCheckInProgress = false
        }
    }
}

internal fun UnifiedActivity.startEpicUpdateCheck(appId: Int, gameName: String) {
    if (updateCheckInProgress) return
    if (!com.winlator.cmod.app.service.NetworkMonitor.hasInternet.value) {
        com.winlator.cmod.shared.ui.toast.WinToast.show(
            this,
            getString(R.string.downloads_no_internet),
            android.widget.Toast.LENGTH_SHORT,
        )
        return
    }
    updateCheckInProgress = true
    taskCheckingGameName = gameName
    taskCheckingShown = true
    taskDoneMessage = null
    lifecycleScope.launch {
        val result =
            runCatching {
                withContext(Dispatchers.IO) { EpicService.checkForGameUpdate(this@startEpicUpdateCheck, appId) }
            }.getOrNull()
        try {
            when {
                result == null || result.message != null -> {
                    taskCheckingShown = false
                    taskDoneFailed = true
                    taskDoneMessage = getString(R.string.store_game_update_check_failed_notice)
                }
                result.hasUpdate -> {
                    val started =
                        withContext(Dispatchers.IO) {
                            EpicService.updateGameFiles(this@startEpicUpdateCheck, appId)
                        }
                    if (started != null) {
                        showTaskProgressPopup(
                            started,
                            gameName,
                            getString(R.string.store_game_update_complete),
                            getString(R.string.store_game_update_failed_notice),
                        )
                    } else {
                        taskCheckingShown = false
                    }
                }
                else -> {
                    taskCheckingShown = false
                    taskDoneFailed = false
                    taskDoneMessage = getString(R.string.store_game_no_updates_notice)
                }
            }
        } finally {
            updateCheckInProgress = false
        }
    }
}
