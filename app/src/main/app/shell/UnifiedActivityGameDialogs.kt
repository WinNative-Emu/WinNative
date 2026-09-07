package com.winlator.cmod.app.shell
import com.winlator.cmod.app.shell.UnifiedActivity.GameSettingsActionItem
import com.winlator.cmod.app.shell.UnifiedActivity.GameSettingsScreen
import com.winlator.cmod.app.shell.UnifiedActivity.HeroBootChoice
import com.winlator.cmod.app.shell.UnifiedActivity.HeroLaunchPopup
import com.winlator.cmod.app.shell.UnifiedActivity.HomeShortcutUiState
import com.winlator.cmod.app.shell.UnifiedActivity.LibraryDetailPopup
import com.winlator.cmod.app.shell.UnifiedActivity.LibraryDetailScreen

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
import com.winlator.cmod.feature.stores.itch.service.ItchLibrary
import com.winlator.cmod.feature.stores.itch.service.ItchService
import com.winlator.cmod.feature.stores.steam.SteamLoginActivity
import com.winlator.cmod.feature.stores.steam.data.DepotInfo
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.feature.stores.steam.events.AndroidEvent
import com.winlator.cmod.feature.stores.steam.events.EventDispatcher
import com.winlator.cmod.feature.stores.steam.service.STEAM_DEFAULT_BRANCH
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.service.getInstalledBranch
import com.winlator.cmod.feature.stores.steam.service.getSelectableBranches
import com.winlator.cmod.feature.stores.steam.service.getSelectedBranch
import com.winlator.cmod.feature.stores.steam.service.setSelectedBranch
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

// Game settings/detail dialogs, split out of UnifiedActivity.kt (behavior-identical).

@Composable
internal fun UnifiedActivity.LibraryDetailPopupFrame(
    title: String,
    onDismissRequest: () -> Unit,
    wide: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismissInteractionSource = remember { MutableInteractionSource() }
    val panelInteractionSource = remember { MutableInteractionSource() }
    val registry = remember { PaneNavRegistry() }

    CompositionLocalProvider(LocalPaneNav provides registry) {
    DialogPaneNav(registry, onDismiss = onDismissRequest)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.58f))
                .clickable(
                    interactionSource = dismissInteractionSource,
                    indication = null,
                    onClick = onDismissRequest,
                ),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val panelMaxWidth = if (wide) 440.dp else 360.dp
            val panelWidthFraction = if (wide) 0.72f else 0.58f
            val panelMaxHeight = (maxHeight - 16.dp).coerceAtLeast(240.dp)

            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth(panelWidthFraction)
                        .widthIn(max = panelMaxWidth)
                        .heightIn(max = panelMaxHeight)
                        .clickable(
                            interactionSource = panelInteractionSource,
                            indication = null,
                            onClick = {},
                        ),
                shape = RoundedCornerShape(16.dp),
                color = CardDark,
                border = BorderStroke(1.dp, CardBorder),
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
            ) {
                Column {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontSize = 13.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = onDismissRequest,
                            modifier =
                                Modifier
                                    .size(34.dp)
                                    .paneNavItem(cornerRadius = 8.dp, onActivate = onDismissRequest),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.common_ui_close),
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = CardBorder, thickness = 0.5.dp)
                    Column(
                        modifier =
                            Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        content()
                    }
                }
            }
        }
    }
    }
}

@Composable
internal fun UnifiedActivity.GameSettingsDialogFrame(
    title: String,
    onDismissRequest: () -> Unit,
    wide: Boolean = false,
    contentKey: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val registry = remember { PaneNavRegistry() }
    LaunchedEffect(contentKey) { registry.reset() }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
      CompositionLocalProvider(LocalPaneNav provides registry) {
        DialogPaneNav(registry, onDismiss = onDismissRequest)
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars),
            contentAlignment = Alignment.Center,
        ) {
            val widthModifier =
                if (wide) {
                    Modifier.widthIn(min = 320.dp, max = (maxWidth - 32.dp).coerceAtMost(560.dp))
                } else {
                    Modifier.widthIn(min = 200.dp, max = 280.dp)
                }
            val maxContentHeight = (maxHeight - 48.dp).coerceAtLeast(320.dp)
            Surface(
                modifier = widthModifier.heightIn(max = maxContentHeight),
                shape = RoundedCornerShape(14.dp),
                color = CardDark,
                border = BorderStroke(1.dp, CardBorder),
                tonalElevation = 8.dp,
            ) {
                Column(
                    modifier =
                        Modifier
                            .padding(vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = onDismissRequest,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(34.dp)
                                .paneNavItem(cornerRadius = 17.dp, onActivate = onDismissRequest, pinTop = true),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.common_ui_close),
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = CardBorder, thickness = 0.5.dp)
                    Column(
                        modifier =
                            Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        content()
                    }
                }
            }
        }
      }
    }
}

@Composable
internal fun UnifiedActivity.GameSettingsActionGrid(
    actions: List<GameSettingsActionItem>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        actions.forEachIndexed { index, action ->
            if (index > 0) {
                HorizontalDivider(
                    color = CardBorder.copy(alpha = 0.5f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            GameSettingsActionCard(action = action, isEntry = index == 0)
        }
    }
}

@Composable
internal fun UnifiedActivity.GameSettingsActionCard(
    action: GameSettingsActionItem,
    modifier: Modifier = Modifier,
    isEntry: Boolean = false,
) {
    val isDanger = action.accentColor == DangerRed
    val iconColor = if (isDanger) DangerRed else TextSecondary
    val textColor = if (isDanger) DangerRed else TextPrimary

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "actionCardScale",
    )
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.paneNavItem(cornerRadius = 0.dp, onActivate = action.onClick, isEntry = isEntry)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = action.onClick,
                ).padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = action.title,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
internal fun UnifiedActivity.GameSettingsInfoCard(
    message: String,
    accentColor: Color = Accent,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = accentColor.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Shared uninstall/remove confirmation UI used by GameSettingsDialog,
 * GOGGameSettingsDialog, and LibraryGameDetailDialog.
 */
@Composable
internal fun UnifiedActivity.UninstallConfirmation(
    message: String,
    confirmLabel: String = stringResource(R.string.common_ui_uninstall),
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var isUninstalling by remember { mutableStateOf(false) }

    GameSettingsInfoCard(message = message, accentColor = DangerRed)

    if (isUninstalling) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = DangerRed)
        }
    } else {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    isUninstalling = true
                    onConfirm()
                },
                modifier = Modifier.paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = { isUninstalling = true; onConfirm() },
                    isEntry = true,
                ),
                border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
            ) {
                Text(
                    confirmLabel,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onCancel,
                modifier = Modifier.paneNavItem(cornerRadius = 8.dp, onActivate = onCancel),
            ) {
                Text(stringResource(R.string.common_ui_cancel), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun UnifiedActivity.ShortcutRemovalConfirmation(
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var isRemoving by remember { mutableStateOf(false) }

    GameSettingsInfoCard(message = message, accentColor = DangerRed)

    if (isRemoving) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = DangerRed)
        }
    } else {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    isRemoving = true
                    onConfirm()
                },
                modifier = Modifier.paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = { isRemoving = true; onConfirm() },
                    isEntry = true,
                ),
                border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
            ) {
                Text(
                    stringResource(R.string.common_ui_remove),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onCancel,
                modifier = Modifier.paneNavItem(cornerRadius = 8.dp, onActivate = onCancel),
            ) {
                Text(stringResource(R.string.common_ui_cancel), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun UnifiedActivity.HeroLaunchConfirmFooter(
    onCancel: () -> Unit,
    onContinue: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaneFooterAction(
            label = stringResource(R.string.common_ui_cancel),
            textColor = DangerRed,
            onClick = onCancel,
        )
        PaneFooterAction(
            label = stringResource(R.string.common_ui_continue),
            textColor = StatusOnline,
            onClick = onContinue,
            isEntry = true,
        )
    }
}

@Composable
internal fun UnifiedActivity.PaneFooterAction(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
    isEntry: Boolean = false,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = onClick,
                    tapToSelect = true,
                    isEntry = isEntry,
                ).padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun UnifiedActivity.HeroBootDialog(
    onConfirm: (HeroBootChoice) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var choice by remember { mutableStateOf(HeroBootChoice.Desktop) }
    val graphicsTest = stringResource(R.string.hero_graphics_tests_title)
    val inputTest = stringResource(R.string.hero_input_tests_title)
    val bits32 = stringResource(R.string.hero_graphics_test_32)
    val bits64 = stringResource(R.string.hero_graphics_test_64)
    val test32 = "$graphicsTest $bits32"
    val test64 = "$graphicsTest $bits64"
    val input32 = "$inputTest $bits32"
    val input64 = "$inputTest $bits64"
    val title =
        when (choice) {
            HeroBootChoice.Desktop -> stringResource(R.string.hero_boot_to_desktop_title)
            HeroBootChoice.Cube32 -> test32
            HeroBootChoice.Cube64 -> test64
            HeroBootChoice.Input32 -> input32
            HeroBootChoice.Input64 -> input64
        }
    val registry = remember { PaneNavRegistry() }
    Dialog(onDismissRequest = onDismissRequest) {
      CompositionLocalProvider(LocalPaneNav provides registry) {
        DialogPaneNav(registry, onDismiss = onDismissRequest, onStart = { onConfirm(choice) })
        PopupDialog(
            title = title,
            icon = Icons.Outlined.DesktopWindows,
            accentColor = Accent,
            modifier = Modifier.widthIn(min = 220.dp, max = 290.dp),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HeroBootOptionRow(
                        label = stringResource(R.string.hero_boot_to_desktop_title),
                        selected = choice == HeroBootChoice.Desktop,
                        onClick = { choice = HeroBootChoice.Desktop },
                    )
                    HeroBootOptionRow(
                        label = test32,
                        selected = choice == HeroBootChoice.Cube32,
                        onClick = { choice = HeroBootChoice.Cube32 },
                    )
                    HeroBootOptionRow(
                        label = test64,
                        selected = choice == HeroBootChoice.Cube64,
                        onClick = { choice = HeroBootChoice.Cube64 },
                    )
                    HeroBootOptionRow(
                        label = input32,
                        selected = choice == HeroBootChoice.Input32,
                        onClick = { choice = HeroBootChoice.Input32 },
                    )
                    HeroBootOptionRow(
                        label = input64,
                        selected = choice == HeroBootChoice.Input64,
                        onClick = { choice = HeroBootChoice.Input64 },
                    )
                }
            },
            footer = {
                HeroLaunchConfirmFooter(onCancel = onDismissRequest, onContinue = { onConfirm(choice) })
            },
        )
      }
    }
}

@Composable
internal fun UnifiedActivity.HeroBootOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val glassBlue = Accent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(glassBlue.copy(alpha = if (selected) 0.26f else 0.05f))
            .border(1.dp, glassBlue.copy(alpha = if (selected) 0.65f else 0.12f), RoundedCornerShape(8.dp))
            .paneNavItem(cornerRadius = 8.dp, onActivate = onClick, tapToSelect = true)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else glassBlue.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun UnifiedActivity.HeroRemoveShortcutDialog(
    gameName: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val registry = remember { PaneNavRegistry() }
    var isRemoving by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismissRequest) {
      CompositionLocalProvider(LocalPaneNav provides registry) {
        DialogPaneNav(registry, onDismiss = onDismissRequest)
        PopupDialog(
            title = stringResource(R.string.common_ui_shortcut),
            message = stringResource(R.string.shortcuts_list_remove_game_shortcut_message, gameName),
            icon = Icons.Outlined.Home,
            accentColor = DangerRed,
            confirmButtonColor = DangerRed,
            progressLabel = stringResource(R.string.common_ui_working),
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
            footer = {
                if (isRemoving) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(color = DangerRed, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(R.string.common_ui_working),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PaneFooterAction(
                            label = stringResource(R.string.common_ui_cancel),
                            textColor = TextSecondary,
                            onClick = onDismissRequest,
                        )
                        PaneFooterAction(
                            label = stringResource(R.string.common_ui_remove),
                            textColor = DangerRed,
                            onClick = {
                                isRemoving = true
                                onConfirm()
                            },
                            isEntry = true,
                        )
                    }
                }
            },
        )
      }
    }
}

@Composable
internal fun UnifiedActivity.GameSettingsDialog(
    app: SteamApp,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(GameSettingsScreen.Menu) }
    val scope = rememberCoroutineScope()
    val isCustom = app.id < 0
    val isEpic = app.id >= 2000000000
    val epicId = if (isEpic) app.id - 2000000000 else 0
    val itchGameId by produceState<Int?>(null, app.id, isCustom) {
        value =
            if (isCustom) {
                withContext(Dispatchers.IO) { ItchService.installedGameId(context, app.gameDir.orEmpty(), app.name) }
            } else {
                null
            }
    }
    var shortcutRefreshKey by remember(app.id, isCustom, isEpic, epicId) { mutableStateOf(0) }
    var pinnedShortcutOverride by remember(app.id, isCustom, isEpic, epicId) { mutableStateOf<Boolean?>(null) }
    val epicArtworkUrl by produceState<String?>(initialValue = null, key1 = isEpic, key2 = epicId) {
        value =
            if (isEpic) {
                val epicGame = db.epicGameDao().getById(epicId)
                epicGame?.primaryImageUrl ?: epicGame?.iconUrl
            } else {
                null
            }
    }
    val currentRefreshSignal = this@GameSettingsDialog.libraryRefreshSignal
    val homeShortcutState by produceState(
        HomeShortcutUiState(),
        app.id,
        isCustom,
        isEpic,
        epicId,
        currentRefreshSignal,
        shortcutRefreshKey,
    ) {
        value =
            withContext(Dispatchers.IO) {
                val shortcut = findLibraryShortcutForGame(ContainerManager(context), app, isCustom, isEpic, epicId)
                HomeShortcutUiState(
                    shortcut = shortcut,
                    isPinned = shortcut?.let { LibraryShortcutUtils.hasPinnedHomeShortcut(context, it) } == true,
                    loaded = true,
                )
            }
    }
    val artworkRefreshListener =
        remember(app.id, isCustom, isEpic, epicId) {
            object : EventDispatcher.JavaEventListener {
                override fun onEvent(event: Any) {
                    if (event is AndroidEvent.LibraryArtworkChanged) {
                        shortcutRefreshKey++
                    }
                }
            }
        }
    DisposableEffect(artworkRefreshListener) {
        PluviaApp.events.onJava(AndroidEvent.LibraryArtworkChanged::class, artworkRefreshListener)
        onDispose {
            PluviaApp.events.offJava(AndroidEvent.LibraryArtworkChanged::class, artworkRefreshListener)
        }
    }
    val hasPinnedShortcut = pinnedShortcutOverride ?: homeShortcutState.isPinned

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val os = context.contentResolver.openOutputStream(uri) ?: return@launch
                        val zos = java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(os))

                        val containerManager =
                            com.winlator.cmod.runtime.container
                                .ContainerManager(context)
                        val shortcut = findLibraryShortcutForGame(containerManager, app, isCustom, isEpic, epicId)

                        val dirsToZip = mutableListOf<java.io.File>()

                        val goldbergSaves = java.io.File(SteamService.getAppDirPath(app.id), "steam_settings/saves")
                        if (goldbergSaves.exists() && goldbergSaves.isDirectory) {
                            dirsToZip.add(goldbergSaves)
                        }

                        if (shortcut != null) {
                            val prefixDir = java.io.File(shortcut.container.getRootDir(), ".wine/drive_c/users/xuser")
                            val docs = java.io.File(prefixDir, "Documents")
                            val savedGames = java.io.File(prefixDir, "Saved Games")
                            val appData = java.io.File(prefixDir, "AppData")
                            if (docs.exists()) dirsToZip.add(docs)
                            if (savedGames.exists()) dirsToZip.add(savedGames)
                            if (appData.exists()) dirsToZip.add(appData)
                        }

                        fun zipDir(
                            dir: java.io.File,
                            baseName: String,
                        ) {
                            val children = dir.listFiles() ?: return
                            for (child in children) {
                                val name = if (baseName.isEmpty()) child.name else "$baseName/${child.name}"
                                if (child.isDirectory) {
                                    zos.putNextEntry(java.util.zip.ZipEntry("$name/"))
                                    zos.closeEntry()
                                    zipDir(child, name)
                                } else {
                                    zos.putNextEntry(java.util.zip.ZipEntry(name))
                                    val fis = java.io.FileInputStream(child)
                                    val buf = ByteArray(1024 * 8)
                                    var len: Int
                                    while (fis.read(buf).also { len = it } > 0) {
                                        zos.write(buf, 0, len)
                                    }
                                    fis.close()
                                    zos.closeEntry()
                                }
                            }
                        }

                        for (dir in dirsToZip) {
                            val baseName = dir.name
                            zos.putNextEntry(java.util.zip.ZipEntry("$baseName/"))
                            zos.closeEntry()
                            zipDir(dir, baseName)
                        }

                        zos.close()
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                R.string.saves_import_export_exported,
                                android.widget.Toast.LENGTH_SHORT,
                            )
                            onDismissRequest()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                getString(R.string.saves_import_export_exported_failed, e.message),
                                android.widget.Toast.LENGTH_SHORT,
                            )
                        }
                    }
                }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val `is` = context.contentResolver.openInputStream(uri) ?: return@launch
                        val zis = java.util.zip.ZipInputStream(java.io.BufferedInputStream(`is`))

                        val containerManager =
                            com.winlator.cmod.runtime.container
                                .ContainerManager(context)
                        val shortcut = findLibraryShortcutForGame(containerManager, app, isCustom, isEpic, epicId)

                        val goldbergSavesParent =
                            java.io.File(
                                if (isEpic) app.gameDir else SteamService.getAppDirPath(app.id),
                                if (isEpic) "" else "steam_settings",
                            )
                        val prefixDir = shortcut?.let { java.io.File(it.container.getRootDir(), ".wine/drive_c/users/xuser") }

                        var ze: java.util.zip.ZipEntry?
                        while (zis.nextEntry.also { ze = it } != null) {
                            val entry = ze!!
                            val name = entry.name
                            var destFile: java.io.File? = null
                            if (name.startsWith("saves/")) {
                                destFile = java.io.File(goldbergSavesParent, name)
                            } else if (prefixDir != null) {
                                if (name.startsWith("Documents/") || name.startsWith("Saved Games/") || name.startsWith("AppData/")) {
                                    destFile = java.io.File(prefixDir, name)
                                }
                            }

                            if (destFile != null) {
                                if (entry.isDirectory) {
                                    destFile.mkdirs()
                                } else {
                                    destFile.parentFile?.mkdirs()
                                    val fos = java.io.FileOutputStream(destFile)
                                    val buf = ByteArray(1024 * 8)
                                    var len: Int
                                    while (zis.read(buf).also { len = it } > 0) {
                                        fos.write(buf, 0, len)
                                    }
                                    fos.close()
                                }
                            }
                            zis.closeEntry()
                        }
                        zis.close()
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                R.string.saves_import_export_imported,
                                android.widget.Toast.LENGTH_SHORT,
                            )
                            onDismissRequest()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                getString(R.string.saves_import_export_imported_failed, e.message),
                                android.widget.Toast.LENGTH_SHORT,
                            )
                        }
                    }
                }
            }
        }

    GameSettingsDialogFrame(
        title = app.name,
        onDismissRequest = onDismissRequest,
        wide = currentTab == GameSettingsScreen.CloudSaves,
        contentKey = currentTab,
    ) {
        when (currentTab) {
            GameSettingsScreen.Menu -> {
                val actions =
                    listOf(
                        GameSettingsActionItem(
                            title = stringResource(R.string.common_ui_settings),
                            icon = Icons.Outlined.Settings,
                            onClick = {
                                val containerManager = ContainerManager(context)
                                val shortcut =
                                    findLibraryShortcutForGame(containerManager, app, isCustom, isEpic, epicId)
                                        ?: if (isCustom) {
                                            null
                                        } else {
                                            ShortcutSettingsComposeDialog.createLibraryShortcut(
                                                context = context,
                                                containerManager = containerManager,
                                                source = if (isEpic) "EPIC" else "STEAM",
                                                appId = if (isEpic) epicId else app.id,
                                                gogId = null,
                                                appName = app.name,
                                            )
                                        }
                                if (shortcut != null) {
                                    ShortcutSettingsComposeDialog(this@GameSettingsDialog, shortcut).show()
                                }
                                onDismissRequest()
                            },
                        ),
                        GameSettingsActionItem(
                            title = stringResource(R.string.hero_boot_to_desktop_title),
                            icon = Icons.Outlined.DesktopWindows,
                            onClick = {
                                val shortcut =
                                    findLibraryShortcutForGame(ContainerManager(context), app, isCustom, isEpic, epicId)
                                if (shortcut != null) {
                                    context.startActivity(
                                        Intent(context, XServerDisplayActivity::class.java)
                                            .putExtra("container_id", shortcut.container.id),
                                    )
                                } else {
                                    com.winlator.cmod.shared.ui.toast.WinToast.show(context, R.string.shortcuts_list_not_available)
                                }
                                onDismissRequest()
                            },
                        ),
                        GameSettingsActionItem(
                            title =
                                stringResource(
                                    if (hasPinnedShortcut) {
                                        R.string.common_ui_remove
                                    } else {
                                        R.string.common_ui_shortcut
                                    },
                                ),
                            icon = Icons.Outlined.Home,
                            accentColor = if (hasPinnedShortcut) DangerRed else Accent,
                            onClick = {
                                if (hasPinnedShortcut) {
                                    currentTab = GameSettingsScreen.Shortcut
                                } else {
                                    scope.launch {
                                        val created =
                                            withContext(Dispatchers.IO) {
                                                addLibraryShortcutToHomeScreen(
                                                    context,
                                                    app,
                                                    isCustom,
                                                    isEpic,
                                                    epicId,
                                                    epicArtworkUrl,
                                                )
                                            }
                                        if (!created) {
                                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                                context,
                                                context.getString(
                                                    R.string.library_games_failed_to_create_shortcut,
                                                    app.name,
                                                ),
                                            )
                                        }
                                    }
                                }
                            },
                        ),
                        GameSettingsActionItem(
                            title = stringResource(R.string.cloud_saves_title),
                            icon = Icons.Outlined.CloudSync,
                            onClick = { currentTab = GameSettingsScreen.CloudSaves },
                        ),

                        GameSettingsActionItem(
                            title =
                                if (isCustom) {
                                    stringResource(
                                        R.string.common_ui_remove,
                                    )
                                } else {
                                    stringResource(R.string.common_ui_uninstall)
                                },
                            icon = Icons.Outlined.Delete,
                            accentColor = DangerRed,
                            onClick = { currentTab = GameSettingsScreen.Uninstall },
                        ),
                    )

                GameSettingsActionGrid(actions = actions)
            }

            GameSettingsScreen.Shortcut -> {
                ShortcutRemovalConfirmation(
                    message = stringResource(R.string.shortcuts_list_remove_game_shortcut_message, app.name),
                    onConfirm = {
                        scope.launch {
                            val removed =
                                withContext(Dispatchers.IO) {
                                    homeShortcutState.shortcut?.let {
                                        LibraryShortcutUtils.disablePinnedHomeShortcut(context, it)
                                    } == true
                                }
                            pinnedShortcutOverride = if (removed) false else hasPinnedShortcut
                            shortcutRefreshKey++
                            currentTab = GameSettingsScreen.Menu
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                if (removed) {
                                    context.getString(R.string.shortcuts_list_removed)
                                } else {
                                    context.getString(R.string.common_ui_unknown_error)
                                },
                            )
                        }
                    },
                    onCancel = { currentTab = GameSettingsScreen.Menu },
                )
            }

            GameSettingsScreen.CloudSaves -> {
                var isWorking by remember { mutableStateOf(false) }
                val shortcut =
                    remember(app.id, epicId, isCustom, isEpic) {
                        findLibraryShortcutForGame(ContainerManager(context), app, isCustom, isEpic, epicId)
                    }
                var cloudSyncEnabled by remember(shortcut?.file?.absolutePath) {
                    mutableStateOf(isShortcutCloudSyncEnabled(shortcut))
                }
                var offlineModeEnabled by remember(shortcut?.file?.absolutePath) {
                    mutableStateOf(isShortcutOfflineMode(shortcut))
                }

                val gameSource =
                    when {
                        isEpic -> GameSaveBackupManager.GameSource.EPIC
                        isCustom -> GameSaveBackupManager.GameSource.CUSTOM
                        else -> GameSaveBackupManager.GameSource.STEAM
                    }
                val gameIdStr =
                    when {
                        isEpic -> epicId.toString()
                        isCustom -> shortcut?.let { GameSaveBackupManager.customGameId(it) } ?: app.name
                        else -> app.id.toString()
                    }
                val providerLabel =
                    when (gameSource) {
                        GameSaveBackupManager.GameSource.EPIC ->
                            stringResource(R.string.preloader_platform_epic)
                        GameSaveBackupManager.GameSource.CUSTOM ->
                            stringResource(R.string.preloader_platform_custom)
                        else ->
                            stringResource(R.string.preloader_platform_steam)
                    }

                CloudSavesContent(
                    activity = this@GameSettingsDialog,
                    isWorking = isWorking,
                    cloudSyncEnabled = cloudSyncEnabled,
                    offlineModeEnabled = offlineModeEnabled,
                    gameSource = gameSource,
                    gameId = gameIdStr,
                    gameName = app.name,
                    shortcut = shortcut,
                    retroSaveDir = com.winlator.cmod.feature.sync.google.GameSaveBackupManager.retroSaveDir(context, shortcut, gameIdStr),
                    onCloudSyncToggle = { enabled ->
                        cloudSyncEnabled = enabled
                        setShortcutCloudSyncEnabled(shortcut, enabled)
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            if (enabled) {
                                context.getString(R.string.cloud_sync_enabled_summary)
                            } else {
                                context.getString(R.string.cloud_sync_disabled_summary)
                            },
                            android.widget.Toast.LENGTH_SHORT,
                        )
                    },
                    onOfflineModeToggle = { enabled ->
                        offlineModeEnabled = enabled
                        setShortcutOfflineMode(shortcut, enabled)
                    },
                    onSyncFromCloud = {
                        if (!isWorking) {
                            isWorking = true
                            scope.launch(Dispatchers.IO) {
                                val ok =
                                    CloudSyncHelper.downloadCloudSaves(
                                        context,
                                        gameSource,
                                        gameIdStr,
                                        shortcut,
                                    )
                                withContext(Dispatchers.Main) {
                                    isWorking = false
                                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                                        context,
                                        if (ok) {
                                            context.getString(
                                                R.string.cloud_saves_sync_from_provider_success,
                                                providerLabel,
                                            )
                                        } else {
                                            context.getString(
                                                R.string.cloud_saves_sync_from_provider_failed,
                                                providerLabel,
                                            )
                                        },
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                }
                            }
                        }
                    },
                    onBack = { currentTab = GameSettingsScreen.Menu },
                )
            }

            GameSettingsScreen.Uninstall -> {
                UninstallConfirmation(
                    message =
                        if (isCustom && itchGameId == null) {
                            getString(R.string.library_games_remove_confirm, app.name)
                        } else {
                            getString(R.string.library_games_uninstall_confirm, app.name)
                        },
                    confirmLabel =
                        if (isCustom && itchGameId == null) {
                            stringResource(
                                R.string.common_ui_remove,
                            )
                        } else {
                            stringResource(R.string.common_ui_uninstall)
                        },
                    onConfirm = {
                        if (isCustom) {
                            val itchId = itchGameId
                            scope.launch(Dispatchers.IO) {
                                val cm = ContainerManager(context)
                                val sc = findLibraryShortcutForGame(cm, app, isCustom, isEpic, epicId)
                                sc?.let { LibraryShortcutUtils.deleteShortcutArtifacts(context, it) }
                                itchId?.let { ItchService.uninstall(context, it) }
                                PluviaApp.events.emit(AndroidEvent.LibraryInstallStatusChanged(app.id))
                                withContext(Dispatchers.Main) {
                                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                                        context,
                                        getString(
                                            if (itchId == null) {
                                                R.string.library_games_game_removed
                                            } else {
                                                R.string.library_games_game_uninstalled
                                            },
                                            app.name,
                                        ),
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                    onDismissRequest()
                                }
                            }
                        } else if (isEpic) {
                            scope.launch(Dispatchers.IO) {
                                val result = EpicService.deleteGame(context, epicId)
                                withContext(Dispatchers.Main) {
                                    if (result.isSuccess) {
                                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                                            context,
                                            getString(R.string.library_games_game_uninstalled, app.name),
                                            android.widget.Toast.LENGTH_SHORT,
                                        )
                                    } else {
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
                        } else {
                            SteamService.uninstallApp(app.id) { success ->
                                if (success) {
                                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                                        context,
                                        getString(R.string.library_games_game_uninstalled, app.name),
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                } else {
                                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                                        context,
                                        getString(R.string.library_games_failed_to_uninstall),
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                }
                                onDismissRequest()
                            }
                        }
                    },
                    onCancel = { currentTab = GameSettingsScreen.Menu },
                )
            }
        }
    }
}

@Composable
internal fun UnifiedActivity.GOGGameSettingsDialog(
    app: GOGGame,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(GameSettingsScreen.Menu) }
    val scope = rememberCoroutineScope()
    var shortcutRefreshKey by remember(app.id) { mutableStateOf(0) }
    var pinnedShortcutOverride by remember(app.id) { mutableStateOf<Boolean?>(null) }
    val currentRefreshSignal = this@GOGGameSettingsDialog.libraryRefreshSignal
    val homeShortcutState by produceState(
        HomeShortcutUiState(),
        app.id,
        currentRefreshSignal,
        shortcutRefreshKey,
    ) {
        value =
            withContext(Dispatchers.IO) {
                val shortcut =
                    ContainerManager(context).loadShortcuts().find {
                        it.getExtra("game_source") == "GOG" && it.getExtra("gog_id") == app.id
                    }
                HomeShortcutUiState(
                    shortcut = shortcut,
                    isPinned = shortcut?.let { LibraryShortcutUtils.hasPinnedHomeShortcut(context, it) } == true,
                    loaded = true,
                )
            }
    }
    val hasPinnedShortcut = pinnedShortcutOverride ?: homeShortcutState.isPinned

    GameSettingsDialogFrame(
        title = app.title,
        onDismissRequest = onDismissRequest,
        wide = currentTab == GameSettingsScreen.CloudSaves,
        contentKey = currentTab,
    ) {
        when (currentTab) {
            GameSettingsScreen.Menu -> {
                GameSettingsActionGrid(
                    actions =
                        listOf(
                            GameSettingsActionItem(
                                title = stringResource(R.string.common_ui_settings),
                                icon = Icons.Outlined.Settings,
                                onClick = {
                                    val containerManager = ContainerManager(context)
                                    val shortcut =
                                        containerManager.loadShortcuts().find {
                                            it.getExtra("game_source") == "GOG" && it.getExtra("gog_id") == app.id
                                        } ?: ShortcutSettingsComposeDialog.createLibraryShortcut(
                                            context = context,
                                            containerManager = containerManager,
                                            source = "GOG",
                                            appId = gogPseudoId(app.id),
                                            gogId = app.id,
                                            appName = app.title,
                                        )
                                    if (shortcut != null) {
                                        ShortcutSettingsComposeDialog(this@GOGGameSettingsDialog, shortcut).show()
                                    }
                                    onDismissRequest()
                                },
                            ),
                            GameSettingsActionItem(
                                title =
                                    stringResource(
                                        if (hasPinnedShortcut) {
                                            R.string.common_ui_remove
                                        } else {
                                            R.string.common_ui_shortcut
                                        },
                                    ),
                                icon = Icons.Outlined.Home,
                                accentColor = if (hasPinnedShortcut) DangerRed else Accent,
                                onClick = {
                                    if (hasPinnedShortcut) {
                                        currentTab = GameSettingsScreen.Shortcut
                                    } else {
                                        scope.launch {
                                            val artworkUrl = app.imageUrl.ifEmpty { app.iconUrl }
                                            val created =
                                                withContext(Dispatchers.IO) {
                                                    addGogShortcutToHomeScreen(context, app, artworkUrl)
                                                }
                                            if (!created) {
                                                com.winlator.cmod.shared.ui.toast.WinToast.show(
                                                    context,
                                                    context.getString(
                                                        R.string.library_games_failed_to_create_shortcut,
                                                        app.title,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                },
                            ),
                            GameSettingsActionItem(
                                title = stringResource(R.string.cloud_saves_title),
                                icon = Icons.Outlined.CloudSync,
                                onClick = { currentTab = GameSettingsScreen.CloudSaves },
                            ),
                            GameSettingsActionItem(
                                title = stringResource(R.string.common_ui_uninstall),
                                icon = Icons.Outlined.Delete,
                                accentColor = DangerRed,
                                onClick = { currentTab = GameSettingsScreen.Uninstall },
                            ),
                        ),
                )
            }

            GameSettingsScreen.Shortcut -> {
                ShortcutRemovalConfirmation(
                    message = stringResource(R.string.shortcuts_list_remove_game_shortcut_message, app.title),
                    onConfirm = {
                        scope.launch {
                            val removed =
                                withContext(Dispatchers.IO) {
                                    homeShortcutState.shortcut?.let {
                                        LibraryShortcutUtils.disablePinnedHomeShortcut(context, it)
                                    } == true
                                }
                            pinnedShortcutOverride = if (removed) false else hasPinnedShortcut
                            shortcutRefreshKey++
                            currentTab = GameSettingsScreen.Menu
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                if (removed) {
                                    context.getString(R.string.shortcuts_list_removed)
                                } else {
                                    context.getString(R.string.common_ui_unknown_error)
                                },
                                android.widget.Toast.LENGTH_SHORT,
                            )
                        }
                    },
                    onCancel = { currentTab = GameSettingsScreen.Menu },
                )
            }

            GameSettingsScreen.CloudSaves -> {
                var isWorking by remember { mutableStateOf(false) }
                val shortcut =
                    remember(app.id) {
                        ContainerManager(context).loadShortcuts().find {
                            it.getExtra("game_source") == "GOG" && it.getExtra("gog_id") == app.id
                        }
                    }
                var cloudSyncEnabled by remember(shortcut?.file?.absolutePath) {
                    mutableStateOf(isShortcutCloudSyncEnabled(shortcut))
                }
                var offlineModeEnabled by remember(shortcut?.file?.absolutePath) {
                    mutableStateOf(isShortcutOfflineMode(shortcut))
                }

                val gogProviderLabel = stringResource(R.string.preloader_platform_gog)

                CloudSavesContent(
                    activity = this@GOGGameSettingsDialog,
                    isWorking = isWorking,
                    cloudSyncEnabled = cloudSyncEnabled,
                    offlineModeEnabled = offlineModeEnabled,
                    gameSource = GameSaveBackupManager.GameSource.GOG,
                    gameId = app.id,
                    gameName = app.title,
                    shortcut = shortcut,
                    onCloudSyncToggle = { enabled ->
                        cloudSyncEnabled = enabled
                        setShortcutCloudSyncEnabled(shortcut, enabled)
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            if (enabled) {
                                context.getString(R.string.cloud_sync_enabled_summary)
                            } else {
                                context.getString(R.string.cloud_sync_disabled_summary)
                            },
                            android.widget.Toast.LENGTH_SHORT,
                        )
                    },
                    onOfflineModeToggle = { enabled ->
                        offlineModeEnabled = enabled
                        setShortcutOfflineMode(shortcut, enabled)
                    },
                    onSyncFromCloud = {
                        if (!isWorking) {
                            isWorking = true
                            scope.launch(Dispatchers.IO) {
                                val ok =
                                    CloudSyncHelper.downloadCloudSaves(
                                        context,
                                        GameSaveBackupManager.GameSource.GOG,
                                        app.id,
                                        shortcut,
                                    )
                                withContext(Dispatchers.Main) {
                                    isWorking = false
                                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                                        context,
                                        if (ok) {
                                            context.getString(
                                                R.string.cloud_saves_sync_from_provider_success,
                                                gogProviderLabel,
                                            )
                                        } else {
                                            context.getString(
                                                R.string.cloud_saves_sync_from_provider_failed,
                                                gogProviderLabel,
                                            )
                                        },
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                }
                            }
                        }
                    },
                    onBack = { currentTab = GameSettingsScreen.Menu },
                )
            }

            GameSettingsScreen.Uninstall -> {
                UninstallConfirmation(
                    message = getString(R.string.library_games_uninstall_confirm, app.title),
                    onConfirm = {
                        scope.launch(Dispatchers.IO) {
                            val result = GOGService.deleteGame(
                                context,
                                LibraryItem("GOG_${app.id}", app.title, com.winlator.cmod.feature.stores.steam.enums.GameSource.GOG),
                            )
                            withContext(Dispatchers.Main) {
                                if (result.isSuccess) {
                                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                                        context,
                                        getString(R.string.library_games_game_uninstalled, app.title),
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                } else {
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
                    onCancel = { currentTab = GameSettingsScreen.Menu },
                )
            }
        }
    }
}

@Composable
internal fun UnifiedActivity.LibraryGameDetailDialog(
    app: SteamApp,
    gogGame: GOGGame? = null,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf(LibraryDetailScreen.Main) }
    var activePopup by remember { mutableStateOf<LibraryDetailPopup?>(null) }
    var showAchievements by remember(app.id) { mutableStateOf(false) }
    var shortcutRefreshKey by remember(app.id, gogGame?.id) { mutableStateOf(0) }
    var pinnedShortcutOverride by remember(app.id, gogGame?.id) { mutableStateOf<Boolean?>(null) }
    var showWorkshopDialog by remember(app.id) { mutableStateOf(false) }

    val isCustom = app.id < 0
    val isEpic = app.id >= 2000000000
    val isGog = gogGame != null
    val epicId = if (isEpic) app.id - 2000000000 else 0
    val itchGameId by produceState<Int?>(null, app.id, isCustom) {
        value =
            if (isCustom) {
                withContext(Dispatchers.IO) { ItchService.installedGameId(context, app.gameDir.orEmpty(), app.name) }
            } else {
                null
            }
    }
    val isItchGame = itchGameId != null
    val itchSourceLabel = if (isItchGame) stringResource(R.string.itch_store_title) else null
    val itchAuthor =
        remember(itchGameId) {
            itchGameId
                ?.let { ItchLibrary.find(context, it) }
                ?.url
                ?.substringAfter("https://")
                ?.substringBefore(".itch.io")
                ?.takeIf { it.isNotBlank() }
        }

    var steamBranches by remember(app.id) { mutableStateOf<List<StoreBranchOption>>(emptyList()) }
    var selectedSteamBranch by remember(app.id) { mutableStateOf(STEAM_DEFAULT_BRANCH) }
    var steamBranchRefreshKey by remember(app.id) { mutableStateOf(0) }
    val publicBranchLabel = stringResource(R.string.store_game_branch_public)

    LaunchedEffect(app.id, isCustom, isEpic, isGog, steamBranchRefreshKey) {
        if (isCustom || isEpic || isGog) {
            steamBranches = emptyList()
            return@LaunchedEffect
        }
        val loaded =
            withContext(Dispatchers.IO) {
                val installedBranch = SteamService.getInstalledBranch(app.id)
                SteamService.getSelectableBranches(app.id).map { branch ->
                    StoreBranchOption(
                        id = branch.name,
                        label =
                            if (branch.name.equals(STEAM_DEFAULT_BRANCH, ignoreCase = true)) {
                                publicBranchLabel
                            } else {
                                branch.name
                            },
                        buildId = branch.buildId,
                        isInstalled = branch.name.equals(installedBranch, ignoreCase = true),
                    )
                } to SteamService.getSelectedBranch(app.id)
            }
        steamBranches = loaded.first
        selectedSteamBranch = loaded.second
    }

    val libraryDownloadRecords by com.winlator.cmod.app.service.download.DownloadCoordinator.records.collectAsState(
        initial = com.winlator.cmod.app.service.download.DownloadCoordinator.snapshotRecords(),
    )
    val hasBlockingSteamDownloadForLibrary =
        !isCustom && !isEpic && !isGog &&
            libraryDownloadRecords.any {
                it.store == com.winlator.cmod.app.db.download.DownloadRecord.STORE_STEAM &&
                    it.storeGameId == app.id.toString() &&
                    it.status in setOf(
                        com.winlator.cmod.app.db.download.DownloadRecord.STATUS_QUEUED,
                        com.winlator.cmod.app.db.download.DownloadRecord.STATUS_DOWNLOADING,
                        com.winlator.cmod.app.db.download.DownloadRecord.STATUS_PAUSED,
                        com.winlator.cmod.app.db.download.DownloadRecord.STATUS_FAILED,
                    )
            }
    val hasBlockingEpicDownloadForLibrary =
        isEpic &&
            libraryDownloadRecords.any {
                it.store == com.winlator.cmod.app.db.download.DownloadRecord.STORE_EPIC &&
                    it.storeGameId == epicId.toString() &&
                    it.status in setOf(
                        com.winlator.cmod.app.db.download.DownloadRecord.STATUS_QUEUED,
                        com.winlator.cmod.app.db.download.DownloadRecord.STATUS_DOWNLOADING,
                        com.winlator.cmod.app.db.download.DownloadRecord.STATUS_PAUSED,
                        com.winlator.cmod.app.db.download.DownloadRecord.STATUS_FAILED,
                    )
            }
    val hasBlockingGogDownloadForLibrary =
        isGog &&
            libraryDownloadRecords.any {
                it.store == com.winlator.cmod.app.db.download.DownloadRecord.STORE_GOG &&
                    it.storeGameId == gogGame?.id &&
                    it.status in setOf(
                        com.winlator.cmod.app.db.download.DownloadRecord.STATUS_QUEUED,
                        com.winlator.cmod.app.db.download.DownloadRecord.STATUS_DOWNLOADING,
                        com.winlator.cmod.app.db.download.DownloadRecord.STATUS_PAUSED,
                        com.winlator.cmod.app.db.download.DownloadRecord.STATUS_FAILED,
                    )
            }

    val epicGame by produceState<EpicGame?>(initialValue = null, key1 = epicId) {
        value = if (isEpic) db.epicGameDao().getById(epicId) else null
    }

    val epicArtworkUrl by produceState<String?>(initialValue = null, key1 = isEpic, key2 = epicId) {
        value =
            if (isEpic) {
                val eg = db.epicGameDao().getById(epicId)
                eg?.primaryImageUrl ?: eg?.iconUrl
            } else {
                null
            }
    }
    val currentRefreshSignal = this@LibraryGameDetailDialog.libraryRefreshSignal
    val homeShortcutState by produceState(
        HomeShortcutUiState(),
        app.id,
        gogGame?.id,
        isCustom,
        isEpic,
        isGog,
        epicId,
        currentRefreshSignal,
        shortcutRefreshKey,
    ) {
        value =
            withContext(Dispatchers.IO) {
                val shortcut =
                    when {
                        isGog -> {
                            ContainerManager(context).loadShortcuts().find {
                                it.getExtra("game_source") == "GOG" && it.getExtra("gog_id") == gogGame!!.id
                            }
                        }

                        else -> {
                            findLibraryShortcutForGame(ContainerManager(context), app, isCustom, isEpic, epicId)
                        }
                    }
                HomeShortcutUiState(
                    shortcut = shortcut,
                    isPinned = shortcut?.let { LibraryShortcutUtils.hasPinnedHomeShortcut(context, it) } == true,
                    loaded = true,
                )
            }
    }
    val artworkRefreshListener =
        remember(app.id, gogGame?.id) {
            object : EventDispatcher.JavaEventListener {
                override fun onEvent(event: Any) {
                    if (event is AndroidEvent.LibraryArtworkChanged) {
                        shortcutRefreshKey++
                    }
                }
            }
        }
    DisposableEffect(artworkRefreshListener) {
        PluviaApp.events.onJava(AndroidEvent.LibraryArtworkChanged::class, artworkRefreshListener)
        onDispose {
            PluviaApp.events.offJava(AndroidEvent.LibraryArtworkChanged::class, artworkRefreshListener)
        }
    }
    val hasPinnedShortcut = pinnedShortcutOverride ?: homeShortcutState.isPinned
    val librarySystemIdHint = if (isCustom) retroLibrarySystemIds.value[app.id] else null
    val retroCaps =
        remember(homeShortcutState.shortcut, homeShortcutState.loaded, isCustom, librarySystemIdHint) {
            when {
                !isCustom -> com.winlator.cmod.feature.retro.RetroShortcuts.LibraryCapabilities()
                homeShortcutState.loaded ->
                    com.winlator.cmod.feature.retro.RetroShortcuts.libraryCapabilities(homeShortcutState.shortcut)
                else ->
                    com.winlator.cmod.feature.retro.RetroShortcuts.libraryCapabilitiesForSystemId(librarySystemIdHint)
            }
        }
    val isRetro = retroCaps.isRetro
    val isExternalRetro = retroCaps.isExternal
    val retroSystemId = retroCaps.systemId
    val retroRomPath =
        retroCaps.romPath
            ?: homeShortcutState.shortcut
                ?.getExtra(com.winlator.cmod.feature.retro.RetroShortcuts.KEY_ROM)
                ?.takeIf { it.isNotEmpty() }
    LaunchedEffect(retroSystemId, retroRomPath, isExternalRetro) {
        val sid = retroSystemId
        val rp = retroRomPath
        if (sid != null && rp != null && !isExternalRetro) {
            com.winlator.cmod.feature.retro.RetroAchievementsManager.prefetch(context, sid, rp)
        }
    }

    BackHandler(enabled = activePopup != null) {
        activePopup = null
    }

    // Hero image
    val customHeroImageFile =
        homeShortcutState.shortcut
            ?.getExtra("customLibraryHeroArtPath")
            ?.takeIf { it.isNotBlank() }
            ?.let { java.io.File(it) }
            ?.takeIf { it.exists() }
    val customHeroImageCacheKey =
        customHeroImageFile?.let {
            "library_custom_hero:${it.absolutePath}:${it.lastModified()}"
        }
    val heroImageUrl: Any? =
        customHeroImageFile ?: when {
            isGog -> {
                StoreArtworkCache.imageModel(context, StoreArtworkCache.gogHeroRef(gogGame!!))
            }

            isEpic -> {
                epicGame?.let { StoreArtworkCache.imageModel(context, StoreArtworkCache.epicHeroRef(it)) }
            }

            isCustom -> {
                val customCoverArt =
                    homeShortcutState.shortcut
                        ?.getExtra("customCoverArtPath")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { java.io.File(it) }
                        ?.takeIf { it.exists() }
                customCoverArt ?: run {
                    val safeName = app.name.replace("/", "_").replace("\\", "_")
                    val iconFile = java.io.File(context.filesDir, "custom_icons/$safeName.png")
                    if (iconFile.exists()) iconFile else null
                }
            }

            else -> {
                val heroUrl = app.getHeroUrl()
                StoreArtworkCache.imageModel(context, StoreArtworkCache.steamRef(app, "hero", heroUrl))
            }
        }

    val subtitle =
        when {
            isGog -> {
                gogGame!!.developer
            }

            isCustom -> {
                itchAuthor ?: stringResource(R.string.library_games_custom_game)
            }

            isEpic -> {
                epicGame?.developer ?: ""
            }

            else -> {
                listOfNotNull(
                    app.developer.takeIf { it.isNotBlank() },
                    app.publisher.takeIf { it.isNotBlank() },
                ).distinctBy { it.trim().lowercase() }.joinToString(" • ")
            }
        }

    // Playtime info
    val playtimePrefs =
        remember {
            context.getSharedPreferences("playtime_stats", android.content.Context.MODE_PRIVATE)
        }
    val searchKey =
        remember(app) {
            if (app.id >= 2000000000 || app.id < 0) {
                app.name
            } else {
                app.name.replace(LIBRARY_NAME_SANITIZE_REGEX, "")
            }
        }
    val lastPlayed = playtimePrefs.getLong("${searchKey}_last_played", 0L)
    val totalPlaytime = playtimePrefs.getLong("${searchKey}_playtime", 0L)
    val playCount = playtimePrefs.getInt("${searchKey}_play_count", 0)

    val sourceLabel =
        when {
            isGog -> "GOG"
            isEpic -> "Epic Games"
            isCustom ->
                retroCaps.sourceLabel
                    ?: librarySystemIdHint?.let {
                        com.winlator.cmod.feature.retro.RetroSystems
                            .fromId(it)
                            ?.badgeLabel
                    }
                    ?: itchSourceLabel
                    ?: "Custom"
            else -> "Steam"
        }

    // Install path
    val installPath =
        remember(app, gogGame) {
            when {
                isGog -> {
                    gogGame!!.installPath
                }

                isEpic -> {
                    epicGame?.installPath ?: ""
                }

                isCustom -> {
                    app.gameDir
                }

                else -> {
                    try {
                        SteamService.getAppDirPath(app.id)
                    } catch (_: Exception) {
                        ""
                    }
                }
            }
        }

    // Install size (computed async)
    val installSizeText by produceState<String?>(initialValue = null, key1 = installPath) {
        value =
            if (installPath.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    try {
                        val bytes = StorageUtils.getFolderSize(installPath)
                        if (bytes > 0) StorageUtils.formatBinarySize(bytes) else null
                    } catch (_: Exception) {
                        null
                    }
                }
            } else {
                null
            }
    }

    // Export / Import launchers (reuse GameSettingsDialog pattern)

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val os = context.contentResolver.openOutputStream(uri) ?: return@launch
                        val zos = java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(os))
                        val containerManager = ContainerManager(context)
                        val shortcut = findLibraryShortcutForGame(containerManager, app, isCustom, isEpic, epicId)
                        val dirsToZip = mutableListOf<java.io.File>()
                        val goldbergSaves = java.io.File(SteamService.getAppDirPath(app.id), "steam_settings/saves")
                        if (goldbergSaves.exists() && goldbergSaves.isDirectory) dirsToZip.add(goldbergSaves)
                        if (shortcut != null) {
                            val prefixDir = java.io.File(shortcut.container.getRootDir(), ".wine/drive_c/users/xuser")
                            listOf("Documents", "Saved Games", "AppData").forEach { name ->
                                val dir = java.io.File(prefixDir, name)
                                if (dir.exists()) dirsToZip.add(dir)
                            }
                        }

                        fun zipDir(
                            dir: java.io.File,
                            baseName: String,
                        ) {
                            val children = dir.listFiles() ?: return
                            for (child in children) {
                                val name = if (baseName.isEmpty()) child.name else "$baseName/${child.name}"
                                if (child.isDirectory) {
                                    zos.putNextEntry(java.util.zip.ZipEntry("$name/"))
                                    zos.closeEntry()
                                    zipDir(child, name)
                                } else {
                                    zos.putNextEntry(java.util.zip.ZipEntry(name))
                                    child.inputStream().use { it.copyTo(zos) }
                                    zos.closeEntry()
                                }
                            }
                        }
                        for (dir in dirsToZip) {
                            zos.putNextEntry(java.util.zip.ZipEntry("${dir.name}/"))
                            zos.closeEntry()
                            zipDir(dir, dir.name)
                        }
                        zos.close()
                        withContext(Dispatchers.Main) {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                R.string.saves_import_export_exported,
                                android.widget.Toast.LENGTH_SHORT,
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                getString(R.string.saves_import_export_exported_failed, e.message),
                                android.widget.Toast.LENGTH_SHORT,
                            )
                        }
                    }
                }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch
                        val zis = java.util.zip.ZipInputStream(java.io.BufferedInputStream(inputStream))
                        val containerManager = ContainerManager(context)
                        val shortcut = findLibraryShortcutForGame(containerManager, app, isCustom, isEpic, epicId)
                        val goldbergSavesParent =
                            java.io.File(
                                if (isEpic) app.gameDir else SteamService.getAppDirPath(app.id),
                                if (isEpic) "" else "steam_settings",
                            )
                        val prefixDir = shortcut?.let { java.io.File(it.container.getRootDir(), ".wine/drive_c/users/xuser") }
                        var ze: java.util.zip.ZipEntry?
                        while (zis.nextEntry.also { ze = it } != null) {
                            val entry = ze!!
                            val name = entry.name
                            var destFile: java.io.File? = null
                            if (name.startsWith("saves/")) {
                                destFile = java.io.File(goldbergSavesParent, name)
                            } else if (prefixDir != null &&
                                (name.startsWith("Documents/") || name.startsWith("Saved Games/") || name.startsWith("AppData/"))
                            ) {
                                destFile = java.io.File(prefixDir, name)
                            }
                            if (destFile != null) {
                                if (entry.isDirectory) {
                                    destFile.mkdirs()
                                } else {
                                    destFile.parentFile?.mkdirs()
                                    java.io.FileOutputStream(destFile).use { fos -> zis.copyTo(fos) }
                                }
                            }
                            zis.closeEntry()
                        }
                        zis.close()
                        withContext(Dispatchers.Main) {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                R.string.saves_import_export_imported,
                                android.widget.Toast.LENGTH_SHORT,
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                getString(R.string.saves_import_export_imported_failed, e.message),
                                android.widget.Toast.LENGTH_SHORT,
                            )
                        }
                    }
                }
            }
        }

    val uninstallGame: () -> Unit = {
        if (isGog) {
            scope.launch(Dispatchers.IO) {
                val result = GOGService.deleteGame(
                    context,
                    LibraryItem(
                        "GOG_${gogGame!!.id}",
                        gogGame.title,
                        com.winlator.cmod.feature.stores.steam.enums.GameSource.GOG,
                    ),
                )
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            getString(R.string.library_games_game_uninstalled, app.name),
                            android.widget.Toast.LENGTH_SHORT,
                        )
                    } else {
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            getString(
                                R.string.library_games_failed_to_uninstall_reason,
                                result.exceptionOrNull()?.message ?: getString(R.string.common_ui_unknown_error),
                            ),
                            android.widget.Toast.LENGTH_LONG,
                        )
                    }
                    onDismissRequest()
                }
            }
        } else if (isCustom) {
            val itchId = itchGameId
            scope.launch(Dispatchers.IO) {
                val cm = ContainerManager(context)
                val sc = findLibraryShortcutForGame(cm, app, isCustom, isEpic, epicId)
                sc?.let { LibraryShortcutUtils.deleteShortcutArtifacts(context, it) }
                java.io
                    .File(
                        context.filesDir,
                        "custom_icons/${app.name.replace("/", "_")}.png",
                    ).delete()
                itchId?.let { ItchService.uninstall(context, it) }
                PluviaApp.events.emit(AndroidEvent.LibraryInstallStatusChanged(app.id))
                withContext(Dispatchers.Main) {
                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                        context,
                        getString(
                            if (itchId == null) R.string.library_games_game_removed else R.string.library_games_game_uninstalled,
                            app.name,
                        ),
                        android.widget.Toast.LENGTH_SHORT,
                    )
                    onDismissRequest()
                }
            }
        } else if (isEpic) {
            scope.launch(Dispatchers.IO) {
                val result = EpicService.deleteGame(context, epicId)
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            getString(R.string.library_games_game_uninstalled, app.name),
                            android.widget.Toast.LENGTH_SHORT,
                        )
                    } else {
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            getString(
                                R.string.library_games_failed_to_uninstall_reason,
                                result.exceptionOrNull()?.message ?: "",
                            ),
                            android.widget.Toast.LENGTH_LONG,
                        )
                    }
                    onDismissRequest()
                }
            }
        } else {
            SteamService.uninstallApp(app.id) { success ->
                if (success) {
                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                        context,
                        getString(R.string.library_games_game_uninstalled, app.name),
                        android.widget.Toast.LENGTH_SHORT,
                    )
                } else {
                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                        context,
                        getString(R.string.library_games_failed_to_uninstall),
                        android.widget.Toast.LENGTH_SHORT,
                    )
                }
                onDismissRequest()
            }
        }
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
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    val showHero = currentScreen == LibraryDetailScreen.Main
                    val subScreenTitle =
                        when (currentScreen) {
                            LibraryDetailScreen.Shortcut -> stringResource(R.string.common_ui_shortcut)
                            LibraryDetailScreen.Uninstall ->
                                stringResource(
                                    if (isCustom && itchGameId == null) R.string.common_ui_remove else R.string.common_ui_uninstall,
                                )
                            else -> ""
                        }
                    // Sub-screens get a compact title bar. The main launch view owns the full
                    // screen and draws artwork edge-to-edge in its content branch.
                    if (!showHero) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(SurfaceDark)
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { currentScreen = LibraryDetailScreen.Main }) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = stringResource(R.string.common_ui_back),
                                    tint = TextPrimary,
                                )
                            }
                            Text(
                                subScreenTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 4.dp),
                            )
                            Text(
                                app.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(end = 16.dp),
                            )
                        }
                        HorizontalDivider(color = CardBorder, thickness = 0.5.dp)
                    }

                    // Bottom content
                    when (currentScreen) {
                        LibraryDetailScreen.Main -> {
                            // Lock Play while VERIFY / UPDATE is rewriting depots in place
                            // for this game — launching mid-write can corrupt the install.
                            val activePlayBlockingTask =
                                if (isCustom) {
                                    null
                                } else if (isGog) {
                                    val gogIdStr = gogGame!!.id
                                    libraryDownloadRecords.firstOrNull { rec ->
                                        rec.store == com.winlator.cmod.app.db.download
                                            .DownloadRecord.STORE_GOG &&
                                            rec.storeGameId == gogIdStr &&
                                            rec.status ==
                                            com.winlator.cmod.app.db.download
                                                .DownloadRecord.STATUS_DOWNLOADING &&
                                            (
                                                rec.taskType ==
                                                    com.winlator.cmod.app.db.download
                                                        .DownloadRecord.TASK_VERIFY ||
                                                    rec.taskType ==
                                                        com.winlator.cmod.app.db.download
                                                            .DownloadRecord.TASK_UPDATE
                                            )
                                    }?.taskType
                                } else if (isEpic) {
                                    val appIdStr = epicId.toString()
                                    libraryDownloadRecords.firstOrNull { rec ->
                                        rec.store == com.winlator.cmod.app.db.download
                                            .DownloadRecord.STORE_EPIC &&
                                            rec.storeGameId == appIdStr &&
                                            rec.status ==
                                            com.winlator.cmod.app.db.download
                                                .DownloadRecord.STATUS_DOWNLOADING &&
                                            (
                                                rec.taskType ==
                                                    com.winlator.cmod.app.db.download
                                                        .DownloadRecord.TASK_VERIFY ||
                                                    rec.taskType ==
                                                        com.winlator.cmod.app.db.download
                                                            .DownloadRecord.TASK_UPDATE
                                            )
                                    }?.taskType
                                } else {
                                    val appIdStr = app.id.toString()
                                    libraryDownloadRecords.firstOrNull { rec ->
                                        rec.store == com.winlator.cmod.app.db.download
                                            .DownloadRecord.STORE_STEAM &&
                                            rec.storeGameId == appIdStr &&
                                            rec.status ==
                                            com.winlator.cmod.app.db.download
                                                .DownloadRecord.STATUS_DOWNLOADING &&
                                            (
                                                rec.taskType ==
                                                    com.winlator.cmod.app.db.download
                                                        .DownloadRecord.TASK_VERIFY ||
                                                    rec.taskType ==
                                                        com.winlator.cmod.app.db.download
                                                            .DownloadRecord.TASK_UPDATE
                                            )
                                    }?.taskType
                                }
                            val playEnabled = activePlayBlockingTask == null
                            val playDisabledLabel =
                                when (activePlayBlockingTask) {
                                    com.winlator.cmod.app.db.download.DownloadRecord.TASK_VERIFY ->
                                        stringResource(R.string.downloads_queue_phase_verifying)
                                    com.winlator.cmod.app.db.download.DownloadRecord.TASK_UPDATE ->
                                        stringResource(R.string.downloads_queue_phase_updating)
                                    else -> null
                                }
                            val launchAppName =
                                homeShortcutState.shortcut
                                    ?.getExtra("custom_name", "")
                                    ?.takeIf { it.isNotBlank() }
                                    ?: when {
                                        isEpic -> epicGame?.title?.takeIf { it.isNotBlank() } ?: app.name
                                        isGog -> gogGame?.title?.takeIf { it.isNotBlank() } ?: app.name
                                        else -> app.name
                                    }
                            val heroToastAnchor = LocalView.current
                            var heroPopup by remember { mutableStateOf<HeroLaunchPopup?>(null) }
                            var bootShortcut by remember { mutableStateOf<com.winlator.cmod.runtime.container.Shortcut?>(null) }
                            val resolveOrCreateShortcut: () -> com.winlator.cmod.runtime.container.Shortcut? = {
                                val containerManager = ContainerManager(context)
                                when {
                                    isGog ->
                                        containerManager.loadShortcuts().find {
                                            it.getExtra("game_source") == "GOG" &&
                                                it.getExtra("gog_id") == gogGame!!.id
                                        } ?: ShortcutSettingsComposeDialog.createLibraryShortcut(
                                            context = context,
                                            containerManager = containerManager,
                                            source = "GOG",
                                            appId = gogPseudoId(gogGame!!.id),
                                            gogId = gogGame.id,
                                            appName = app.name,
                                        )
                                    isCustom -> findLibraryShortcutForGame(containerManager, app, isCustom, isEpic, epicId)
                                    else ->
                                        findLibraryShortcutForGame(containerManager, app, isCustom, isEpic, epicId)
                                            ?: ShortcutSettingsComposeDialog.createLibraryShortcut(
                                                context = context,
                                                containerManager = containerManager,
                                                source = if (isEpic) "EPIC" else "STEAM",
                                                appId = if (isEpic) epicId else app.id,
                                                gogId = null,
                                                appName = app.name,
                                            )
                                }
                            }
                            var showSaveTransfer by remember(app.id) { mutableStateOf(false) }
                            val retroSaveImportLauncher =
                                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                                    if (uri != null) {
                                        val sourceName =
                                            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                                if (it.moveToFirst()) it.getString(0) else null
                                            } ?: "save"
                                        val result =
                                            runCatching {
                                                val bytes =
                                                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                                        ?: return@runCatching com.winlator.cmod.feature.retro.RetroSaveImport.Result.Invalid("Could not read the file.")
                                                com.winlator.cmod.feature.retro.RetroSaveImport.import(context, app.name, sourceName, bytes)
                                            }.getOrElse { com.winlator.cmod.feature.retro.RetroSaveImport.Result.Invalid("Could not read the file.") }
                                        val message =
                                            when (result) {
                                                is com.winlator.cmod.feature.retro.RetroSaveImport.Result.Success ->
                                                    "Imported save (${result.name}, ${result.bytes / 1024} KB)"
                                                is com.winlator.cmod.feature.retro.RetroSaveImport.Result.Invalid ->
                                                    "Import failed: ${result.reason}"
                                            }
                                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            val retroSaveExportLauncher =
                                rememberLauncherForActivityResult(
                                    ActivityResultContracts.CreateDocument("application/octet-stream"),
                                ) { uri ->
                                    if (uri != null) {
                                        val ok =
                                            runCatching {
                                                val sram =
                                                    com.winlator.cmod.feature.retro.RetroSaveStates.sramFile(context, app.name)
                                                if (!sram.isFile) return@runCatching false
                                                context.contentResolver.openOutputStream(uri)?.use { it.write(sram.readBytes()) }
                                                true
                                            }.getOrDefault(false)
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(
                                                if (ok) R.string.retro_save_transfer_export_ok else R.string.retro_save_transfer_export_failed,
                                            ),
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            if (showSaveTransfer) {
                                androidx.compose.material3.AlertDialog(
                                    onDismissRequest = { showSaveTransfer = false },
                                    title = { androidx.compose.material3.Text(stringResource(R.string.retro_save_transfer_title)) },
                                    text = { androidx.compose.material3.Text(stringResource(R.string.retro_save_transfer_message)) },
                                    confirmButton = {
                                        androidx.compose.material3.TextButton(onClick = {
                                            showSaveTransfer = false
                                            retroSaveImportLauncher.launch(arrayOf("*/*"))
                                        }) { androidx.compose.material3.Text(stringResource(R.string.retro_save_transfer_import)) }
                                    },
                                    dismissButton = {
                                        androidx.compose.material3.TextButton(onClick = {
                                            val sram = com.winlator.cmod.feature.retro.RetroSaveStates.sramFile(context, app.name)
                                            showSaveTransfer = false
                                            if (!sram.isFile) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    context.getString(R.string.retro_save_transfer_none),
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            } else {
                                                retroSaveExportLauncher.launch("${app.name}.srm")
                                            }
                                        }) { androidx.compose.material3.Text(stringResource(R.string.retro_save_transfer_export)) }
                                    },
                                )
                            }
                            // The 3D engine offers itself only for the few Gen 1
                            // titles it supports, and only when its files are
                            // actually in the retro bundle. Compatibility is the
                            // ROM's SHA-1, so deciding it means hashing a file:
                            // done once off the composition thread, keyed on the
                            // shortcut, rather than on every recomposition.
                            val engine3dShortcut = homeShortcutState.shortcut
                            val engine3dKey = engine3dShortcut?.file?.absolutePath
                            var engine3dSupported by remember(engine3dKey) { mutableStateOf(false) }
                            var engine3dOn by remember(engine3dKey) {
                                mutableStateOf(
                                    engine3dShortcut?.let {
                                        com.winlator.cmod.feature.retro.Gen1EmbedLaunch.isEnabled(it)
                                    } ?: false,
                                )
                            }
                            LaunchedEffect(engine3dKey) {
                                engine3dSupported =
                                    engine3dShortcut != null &&
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            com.winlator.cmod.feature.retro.Gen1EmbedLaunch
                                                .isCompatible(context, engine3dShortcut)
                                        }.getOrDefault(false)
                                    }
                            }
                            LibraryGameLaunchScreen(
                                appName = launchAppName,
                                subtitle = subtitle,
                                sourceLabel = sourceLabel,
                                heroImageUrl = heroImageUrl,
                                customHeroImageCacheKey = customHeroImageCacheKey,
                                releaseDateEpochSeconds = app.releaseDate,
                                totalPlaytimeMillis = totalPlaytime,
                                playCount = playCount,
                                lastPlayedMillis = lastPlayed,
                                installSizeText = installSizeText,
                                removalKeepsFiles = isCustom && itchGameId == null,
                                isRetro = isRetro,
                                showBootToDesktop = retroCaps.showBootToDesktop,
                                showSaveTransfer = retroCaps.showSaveTransfer,
                                hasPinnedShortcut = hasPinnedShortcut,
                                playEnabled = playEnabled,
                                playDisabledLabel = playDisabledLabel,
                                altEngineLabel =
                                    if (engine3dSupported) stringResource(R.string.retro_gs_engine_3d) else null,
                                altEngineEnabled = engine3dOn,
                                onAltEngineChange =
                                    if (engine3dSupported && engine3dShortcut != null) {
                                        { on ->
                                            engine3dOn = on
                                            // Written straight to the shortcut, so
                                            // Play uses it immediately and the
                                            // Graphics pane agrees with it.
                                            engine3dShortcut.putExtra(
                                                com.winlator.cmod.feature.retro.Gen1EmbedLaunch.KEY_ENGINE_3D,
                                                if (on) "1" else "0",
                                            )
                                            engine3dShortcut.saveData()
                                        }
                                    } else {
                                        null
                                    },
                                onBack = onDismissRequest,
                                onPlay = {
                                    val containerManager = ContainerManager(context)
                                    if (isCustom) {
                                        launchCustomGame(context, containerManager, app.name)
                                    } else if (isGog) {
                                        launchGogGame(context, containerManager, gogGame!!)
                                    } else if (isEpic) {
                                        epicGame?.let { launchEpicGame(context, containerManager, it) }
                                    } else {
                                        launchSteamGame(context, containerManager, app)
                                    }
                                    onDismissRequest()
                                },
                                onSettings = {
                                    val shortcut = resolveOrCreateShortcut()
                                    if (shortcut != null) {
                                        ShortcutSettingsComposeDialog(this@LibraryGameDetailDialog, shortcut).show()
                                    }
                                },
                                onBootToDesktop = {
                                    val shortcut = resolveOrCreateShortcut()
                                    if (shortcut != null) {
                                        bootShortcut = shortcut
                                        heroPopup = HeroLaunchPopup.BootToDesktop
                                    } else {
                                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                                            context,
                                            R.string.shortcuts_list_not_available,
                                            heroToastAnchor,
                                        )
                                    }
                                },
                                onAchievements =
                                    when {
                                        isRetro -> {
                                            val sysId = retroSystemId
                                            val rom = retroRomPath
                                            if (sysId != null && rom != null && retroCaps.showAchievements) {
                                                {
                                                    context.startActivity(
                                                        android.content.Intent(
                                                            context,
                                                            com.winlator.cmod.feature.retro.RetroAchievementsActivity::class.java,
                                                        ).apply {
                                                            putExtra(
                                                                com.winlator.cmod.feature.retro.RetroAchievementsActivity.EXTRA_SYSTEM_ID,
                                                                sysId,
                                                            )
                                                            putExtra(
                                                                com.winlator.cmod.feature.retro.RetroAchievementsActivity.EXTRA_ROM_PATH,
                                                                rom,
                                                            )
                                                            putExtra(
                                                                com.winlator.cmod.feature.retro.RetroAchievementsActivity.EXTRA_GAME_NAME,
                                                                app.name,
                                                            )
                                                            putExtra(
                                                                com.winlator.cmod.feature.retro.RetroAchievementsActivity.EXTRA_IN_SESSION,
                                                                false,
                                                            )
                                                        },
                                                    )
                                                }
                                            } else {
                                                null
                                            }
                                        }
                                        !isCustom && !isEpic && !isGog -> {
                                            { showAchievements = true }
                                        }
                                        else -> null
                                    },
                                onShortcut = {
                                    if (hasPinnedShortcut) {
                                        heroPopup = HeroLaunchPopup.RemoveShortcut
                                    } else {
                                        scope.launch {
                                            val created =
                                                withContext(Dispatchers.IO) {
                                                    if (isGog) {
                                                        val artworkUrl = gogGame!!.imageUrl.ifEmpty { gogGame.iconUrl }
                                                        addGogShortcutToHomeScreen(context, gogGame, artworkUrl)
                                                    } else {
                                                        addLibraryShortcutToHomeScreen(
                                                            context,
                                                            app,
                                                            isCustom,
                                                            isEpic,
                                                            epicId,
                                                            epicArtworkUrl,
                                                        )
                                                    }
                                                }
                                            if (!created) {
                                                com.winlator.cmod.shared.ui.toast.WinToast.show(
                                                    context,
                                                    context.getString(
                                                        R.string.library_games_failed_to_create_shortcut,
                                                        app.name,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                },
                                onCloudSaves = { activePopup = LibraryDetailPopup.CloudSaves },
                                onSaveTransfer =
                                    if (retroCaps.showSaveTransfer) {
                                        { showSaveTransfer = true }
                                    } else {
                                        null
                                    },
                                onCheats =
                                    if (retroCaps.showCheats && retroSystemId != null) {
                                        {
                                            context.startActivity(
                                                android.content.Intent(
                                                    context,
                                                    com.winlator.cmod.feature.retro.RetroCheatsActivity::class.java,
                                                ).apply {
                                                    putExtra(
                                                        com.winlator.cmod.feature.retro.RetroCheatsActivity.EXTRA_SYSTEM_ID,
                                                        retroSystemId,
                                                    )
                                                    putExtra(
                                                        com.winlator.cmod.feature.retro.RetroCheatsActivity.EXTRA_GAME_NAME,
                                                        app.name,
                                                    )
                                                },
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                cheatsEnabled =
                                    !(
                                        com.winlator.cmod.feature.retro.RetroAchievementsManager.isEnabled(context) &&
                                            com.winlator.cmod.feature.retro.RetroAchievementsManager.isLoggedIn(context) &&
                                            com.winlator.cmod.feature.retro.RetroAchievementsManager.isHardcorePreferred(context)
                                    ),
                                onUninstall = uninstallGame,
                                steamMenuEnabled = (isCustom && isItchGame) ||
                                    (
                                        !isCustom &&
                                            (!isEpic || epicGame?.isInstalled == true) &&
                                            (!isGog || gogGame?.isInstalled == true)
                                    ),
                                showVerifyFiles = !isCustom &&
                                    (!isEpic || epicGame?.isInstalled == true) &&
                                    (!isGog || gogGame?.isInstalled == true),
                                showCheckForUpdate = (isCustom && isItchGame) ||
                                    (
                                        !isCustom &&
                                            (!isEpic || epicGame?.isInstalled == true) &&
                                            (!isGog || gogGame?.isInstalled == true)
                                    ),
                                showWorkshop = !isCustom && !isEpic && !isGog,
                                areSteamActionsEnabled =
                                    when {
                                        isCustom && isItchGame -> true
                                        isEpic -> !hasBlockingEpicDownloadForLibrary
                                        isGog -> !hasBlockingGogDownloadForLibrary
                                        else -> !hasBlockingSteamDownloadForLibrary
                                    },
                                onVerifyFiles = {
                                    context.runIfOnlineOrToast {
                                        scope.launch {
                                            val started =
                                                withContext(Dispatchers.IO) {
                                                    when {
                                                        isEpic -> EpicService.verifyGameFiles(context, epicId)
                                                        isGog -> GOGService.verifyGameFiles(context, gogGame!!.id)
                                                        else -> SteamService.downloadAppForVerify(app.id)
                                                    }
                                                }
                                            if (started != null) {
                                                showTaskProgressPopup(
                                                    started,
                                                    if (isGog) gogGame!!.title else app.name,
                                                    getString(R.string.store_game_verify_complete),
                                                    getString(R.string.store_game_verify_failed_notice),
                                                    completeAsToast = true,
                                                )
                                            }
                                            if (started == null) {
                                                com.winlator.cmod.shared.ui.toast.WinToast.show(
                                                    context,
                                                    getString(R.string.store_game_download_already_active),
                                                    android.widget.Toast.LENGTH_SHORT,
                                                )
                                            }
                                        }
                                    }
                                },
                                onCheckForUpdate = {
                                    when {
                                        isCustom && isItchGame -> startItchUpdateCheck(itchGameId!!, app.name)
                                        isEpic -> startEpicUpdateCheck(epicId, app.name)
                                        isGog -> startGogUpdateCheck(gogGame!!.id, gogGame.title)
                                        else -> startUpdateCheck(app.id, app.name)
                                    }
                                },
                                onWorkshop = { if (!isEpic && !isGog && !isCustom) showWorkshopDialog = true },
                                branches = steamBranches,
                                selectedBranchId = selectedSteamBranch,
                                isBranchSelectionEnabled = !hasBlockingSteamDownloadForLibrary,
                                onSelectBranch = { branchId ->
                                    selectedSteamBranch = branchId
                                    val label = steamBranches.firstOrNull { it.id == branchId }?.label ?: branchId
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            SteamService.setSelectedBranch(app.id, branchId)
                                        }
                                        steamBranchRefreshKey++
                                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                                            context,
                                            context.getString(R.string.store_game_branch_switch_installed, label),
                                            android.widget.Toast.LENGTH_SHORT,
                                        )
                                    }
                                },
                            )

                            when (heroPopup) {
                                HeroLaunchPopup.BootToDesktop ->
                                    HeroBootDialog(
                                        onConfirm = { choice ->
                                            heroPopup = null
                                            bootShortcut?.let { sc ->
                                                val intent =
                                                    Intent(context, XServerDisplayActivity::class.java)
                                                        .putExtra("container_id", sc.container.id)
                                                when (choice) {
                                                    HeroBootChoice.Desktop -> {}
                                                    HeroBootChoice.Cube32 ->
                                                        intent
                                                            .putExtra("shortcut_path", sc.file.absolutePath)
                                                            .putExtra("boot_exe", "C:\\ProgramData\\Microsoft\\Windows\\Graphics-Test-32bit.exe")
                                                    HeroBootChoice.Cube64 ->
                                                        intent
                                                            .putExtra("shortcut_path", sc.file.absolutePath)
                                                            .putExtra("boot_exe", "C:\\ProgramData\\Microsoft\\Windows\\Graphics-Test-64bit.exe")
                                                    HeroBootChoice.Input32 ->
                                                        intent
                                                            .putExtra("shortcut_path", sc.file.absolutePath)
                                                            .putExtra("boot_exe", "C:\\ProgramData\\Microsoft\\Windows\\InputControl32.exe")
                                                    HeroBootChoice.Input64 ->
                                                        intent
                                                            .putExtra("shortcut_path", sc.file.absolutePath)
                                                            .putExtra("boot_exe", "C:\\ProgramData\\Microsoft\\Windows\\InputControl64.exe")
                                                }
                                                context.startActivity(intent)
                                                onDismissRequest()
                                            }
                                        },
                                        onDismissRequest = { heroPopup = null },
                                    )
                                HeroLaunchPopup.RemoveShortcut ->
                                    HeroRemoveShortcutDialog(
                                        gameName = if (isGog) gogGame!!.title else app.name,
                                        onConfirm = {
                                            scope.launch {
                                                val removed =
                                                    withContext(Dispatchers.IO) {
                                                        homeShortcutState.shortcut?.let {
                                                            LibraryShortcutUtils.disablePinnedHomeShortcut(context, it)
                                                        } == true
                                                    }
                                                pinnedShortcutOverride = if (removed) false else hasPinnedShortcut
                                                shortcutRefreshKey++
                                                heroPopup = null
                                                com.winlator.cmod.shared.ui.toast.WinToast.show(
                                                    context,
                                                    if (removed) {
                                                        context.getString(R.string.shortcuts_list_removed)
                                                    } else {
                                                        context.getString(R.string.common_ui_unknown_error)
                                                    },
                                                )
                                            }
                                        },
                                        onDismissRequest = { heroPopup = null },
                                    )
                                null -> {}
                            }
                        }

                        LibraryDetailScreen.Shortcut -> {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 24.dp, vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    stringResource(R.string.common_ui_shortcut),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.1.sp,
                                )

                                Spacer(Modifier.weight(1f))

                                ShortcutRemovalConfirmation(
                                    message =
                                        stringResource(
                                            R.string.shortcuts_list_remove_game_shortcut_message,
                                            if (isGog) gogGame!!.title else app.name,
                                        ),
                                    onConfirm = {
                                        scope.launch {
                                            val removed =
                                                withContext(Dispatchers.IO) {
                                                    homeShortcutState.shortcut?.let {
                                                        LibraryShortcutUtils.disablePinnedHomeShortcut(context, it)
                                                    } == true
                                                }
                                            pinnedShortcutOverride = if (removed) false else hasPinnedShortcut
                                            shortcutRefreshKey++
                                            currentScreen = LibraryDetailScreen.Main
                                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                                context,
                                                if (removed) {
                                                    context.getString(R.string.shortcuts_list_removed)
                                                } else {
                                                    context.getString(R.string.common_ui_unknown_error)
                                                },
                                            )
                                        }
                                    },
                                    onCancel = { currentScreen = LibraryDetailScreen.Main },
                                )
                            }
                        }

                        LibraryDetailScreen.CloudSaves -> {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .navigationBarsPadding(),
                            ) {
                            var isWorking by remember { mutableStateOf(false) }

                            val detailGameSource =
                                when {
                                    isGog -> GameSaveBackupManager.GameSource.GOG
                                    isEpic -> GameSaveBackupManager.GameSource.EPIC
                                    else -> GameSaveBackupManager.GameSource.STEAM
                                }
                            val detailGameId =
                                when {
                                    isGog -> gogGame!!.id
                                    isEpic -> epicId.toString()
                                    else -> app.id.toString()
                                }
                            val detailShortcut =
                                remember(app.id, gogGame?.id, epicId, isGog, isEpic, isCustom) {
                                    val containerManager = ContainerManager(context)
                                    when {
                                        isGog -> {
                                            containerManager.loadShortcuts().find {
                                                it.getExtra("game_source") == "GOG" && it.getExtra("gog_id") == gogGame!!.id
                                            }
                                        }

                                        else -> {
                                            findLibraryShortcutForGame(containerManager, app, isCustom, isEpic, epicId)
                                        }
                                    }
                                }
                            var cloudSyncEnabled by remember(detailShortcut?.file?.absolutePath) {
                                mutableStateOf(isShortcutCloudSyncEnabled(detailShortcut))
                            }
                            var offlineModeEnabled by remember(detailShortcut?.file?.absolutePath) {
                                mutableStateOf(isShortcutOfflineMode(detailShortcut))
                            }

                            val detailProviderLabel =
                                when (detailGameSource) {
                                    GameSaveBackupManager.GameSource.GOG ->
                                        stringResource(R.string.preloader_platform_gog)
                                    GameSaveBackupManager.GameSource.EPIC ->
                                        stringResource(R.string.preloader_platform_epic)
                                    GameSaveBackupManager.GameSource.CUSTOM ->
                                        stringResource(R.string.preloader_platform_custom)
                                    GameSaveBackupManager.GameSource.STEAM ->
                                        stringResource(R.string.preloader_platform_steam)
                                }

                            CloudSavesContent(
                                activity = this@LibraryGameDetailDialog,
                                isWorking = isWorking,
                                cloudSyncEnabled = cloudSyncEnabled,
                                offlineModeEnabled = offlineModeEnabled,
                                gameSource = detailGameSource,
                                gameId = detailGameId,
                                gameName = app.name,
                                shortcut = detailShortcut,
                                retroSaveDir = com.winlator.cmod.feature.sync.google.GameSaveBackupManager.retroSaveDir(context, detailShortcut, detailGameId),
                                onCloudSyncToggle = { enabled ->
                                    cloudSyncEnabled = enabled
                                    setShortcutCloudSyncEnabled(detailShortcut, enabled)
                                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                                        context,
                                        if (enabled) {
                                            context.getString(R.string.cloud_sync_enabled_summary)
                                        } else {
                                            context.getString(R.string.cloud_sync_disabled_summary)
                                        },
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                },
                                onOfflineModeToggle = { enabled ->
                                    offlineModeEnabled = enabled
                                    setShortcutOfflineMode(detailShortcut, enabled)
                                },
                                onSyncFromCloud = {
                                    if (!isWorking) {
                                        isWorking = true
                                        scope.launch(Dispatchers.IO) {
                                            val ok =
                                                CloudSyncHelper.downloadCloudSaves(
                                                    context,
                                                    detailGameSource,
                                                    detailGameId,
                                                    detailShortcut,
                                                )
                                            withContext(Dispatchers.Main) {
                                                isWorking = false
                                                com.winlator.cmod.shared.ui.toast.WinToast.show(
                                                    context,
                                                    if (ok) {
                                                        context.getString(
                                                            R.string.cloud_saves_sync_from_provider_success,
                                                            detailProviderLabel,
                                                        )
                                                    } else {
                                                        context.getString(
                                                            R.string.cloud_saves_sync_from_provider_failed,
                                                            detailProviderLabel,
                                                        )
                                                    },
                                                    android.widget.Toast.LENGTH_SHORT,
                                                )
                                            }
                                        }
                                    }
                                },
                                showBottomBack = false,
                                onBack = { currentScreen = LibraryDetailScreen.Main },
                            )
                            }
                        }

                        LibraryDetailScreen.Uninstall -> {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 24.dp, vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    stringResource(
                                        if (isCustom && itchGameId == null) {
                                            R.string.library_games_remove_game
                                        } else {
                                            R.string.library_games_uninstall_game
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.1.sp,
                                )

                                Spacer(Modifier.weight(1f))

                                UninstallConfirmation(
                                    message =
                                        if (isCustom && itchGameId == null) {
                                            getString(R.string.library_games_remove_confirm, app.name)
                                        } else {
                                            getString(R.string.library_games_uninstall_confirm, app.name)
                                        },
                                    confirmLabel =
                                        stringResource(
                                            if (isCustom && itchGameId == null) {
                                                R.string.common_ui_remove
                                            } else {
                                                R.string.common_ui_uninstall
                                            },
                                        ),
                                    onConfirm = uninstallGame,
                                    onCancel = { currentScreen = LibraryDetailScreen.Main },
                                )
                            }
                        }
                    }
                }

                if (showAchievements) {
                    Dialog(
                        onDismissRequest = { showAchievements = false },
                        properties = DialogProperties(
                            usePlatformDefaultWidth = false,
                            dismissOnClickOutside = false,
                            decorFitsSystemWindows = false,
                        ),
                    ) {
                        com.winlator.cmod.feature.stores.steam.achievements.SteamAchievementsScreen(
                            appId = app.id,
                            appName = app.name,
                            onClose = { showAchievements = false },
                        )
                    }
                }

                activePopup?.let { popup ->
                    LibraryDetailPopupFrame(
                        title =
                            when (popup) {
                                LibraryDetailPopup.CloudSaves ->
                                    stringResource(
                                        R.string.cloud_saves_title_for_provider,
                                        when {
                                            isGog -> stringResource(R.string.preloader_platform_gog)
                                            isEpic -> stringResource(R.string.preloader_platform_epic)
                                            isCustom -> stringResource(R.string.preloader_platform_custom)
                                            else -> stringResource(R.string.preloader_platform_steam)
                                        },
                                        app.name,
                                    )
                            },
                        wide = popup == LibraryDetailPopup.CloudSaves,
                        onDismissRequest = { activePopup = null },
                    ) {
                        when (popup) {
                            LibraryDetailPopup.CloudSaves -> {
                                var isWorking by remember { mutableStateOf(false) }

                                val detailGameSource =
                                    when {
                                        isGog -> GameSaveBackupManager.GameSource.GOG
                                        isEpic -> GameSaveBackupManager.GameSource.EPIC
                                        isCustom -> GameSaveBackupManager.GameSource.CUSTOM
                                        else -> GameSaveBackupManager.GameSource.STEAM
                                    }
                                val detailShortcut =
                                    remember(app.id, gogGame?.id, epicId, isGog, isEpic, isCustom) {
                                        val containerManager = ContainerManager(context)
                                        when {
                                            isGog -> {
                                                containerManager.loadShortcuts().find {
                                                    it.getExtra("game_source") == "GOG" && it.getExtra("gog_id") == gogGame!!.id
                                                }
                                            }

                                            else -> {
                                                findLibraryShortcutForGame(containerManager, app, isCustom, isEpic, epicId)
                                            }
                                        }
                                    }
                                val detailGameId =
                                    when {
                                        isGog -> gogGame!!.id
                                        isEpic -> epicId.toString()
                                        isCustom ->
                                            detailShortcut?.let { GameSaveBackupManager.customGameId(it) }
                                                ?: app.name
                                        else -> app.id.toString()
                                    }
                                var cloudSyncEnabled by remember(detailShortcut?.file?.absolutePath) {
                                    mutableStateOf(isShortcutCloudSyncEnabled(detailShortcut))
                                }
                                var offlineModeEnabled by remember(detailShortcut?.file?.absolutePath) {
                                    mutableStateOf(isShortcutOfflineMode(detailShortcut))
                                }

                                val detailProviderLabel =
                                    when (detailGameSource) {
                                        GameSaveBackupManager.GameSource.GOG ->
                                            stringResource(R.string.preloader_platform_gog)
                                        GameSaveBackupManager.GameSource.EPIC ->
                                            stringResource(R.string.preloader_platform_epic)
                                        GameSaveBackupManager.GameSource.CUSTOM ->
                                            stringResource(R.string.preloader_platform_custom)
                                        GameSaveBackupManager.GameSource.STEAM ->
                                            stringResource(R.string.preloader_platform_steam)
                                    }

                                CloudSavesContent(
                                    activity = this@LibraryGameDetailDialog,
                                    isWorking = isWorking,
                                    cloudSyncEnabled = cloudSyncEnabled,
                                    offlineModeEnabled = offlineModeEnabled,
                                    gameSource = detailGameSource,
                                    gameId = detailGameId,
                                    gameName = app.name,
                                    shortcut = detailShortcut,
                                    retroSaveDir = com.winlator.cmod.feature.sync.google.GameSaveBackupManager.retroSaveDir(context, detailShortcut, detailGameId),
                                    onCloudSyncToggle = { enabled ->
                                        cloudSyncEnabled = enabled
                                        setShortcutCloudSyncEnabled(detailShortcut, enabled)
                                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                                            context,
                                            if (enabled) {
                                                context.getString(R.string.cloud_sync_enabled_summary)
                                            } else {
                                                context.getString(R.string.cloud_sync_disabled_summary)
                                            },
                                            android.widget.Toast.LENGTH_SHORT,
                                        )
                                    },
                                    onOfflineModeToggle = { enabled ->
                                        offlineModeEnabled = enabled
                                        setShortcutOfflineMode(detailShortcut, enabled)
                                    },
                                onSyncFromCloud = {
                                    if (!isWorking) {
                                        isWorking = true
                                            scope.launch(Dispatchers.IO) {
                                                val ok =
                                                    CloudSyncHelper.downloadCloudSaves(
                                                        context,
                                                        detailGameSource,
                                                        detailGameId,
                                                        detailShortcut,
                                                    )
                                                withContext(Dispatchers.Main) {
                                                    isWorking = false
                                                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                                                        context,
                                                        if (ok) {
                                                            context.getString(
                                                                R.string.cloud_saves_sync_from_provider_success,
                                                                detailProviderLabel,
                                                            )
                                                        } else {
                                                            context.getString(
                                                                R.string.cloud_saves_sync_from_provider_failed,
                                                                detailProviderLabel,
                                                            )
                                                        },
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    showTitle = false,
                                    showBottomBack = false,
                                    onBack = { activePopup = null },
                                )
                            }
                        }
                    }
                }

                if (
                    currentScreen != LibraryDetailScreen.Main &&
                    currentScreen != LibraryDetailScreen.CloudSaves
                ) {
                    // Close button overlay
                    IconButton(
                        onClick = onDismissRequest,
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .size(42.dp)
                                .shadow(8.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.35f))
                                .clip(CircleShape)
                                .background(BgDark.copy(alpha = 0.7f)),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }
            }

            if (showWorkshopDialog) {
                WorkshopDialog(
                    appId = app.id,
                    gameTitle = app.name,
                    onDismissRequest = { showWorkshopDialog = false },
                )
            }
        }
    }
}
