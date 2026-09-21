package com.winlator.cmod.runtime.display

import android.app.Activity
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import com.winlator.cmod.shared.ui.focus.controllerMenuInput
import com.winlator.cmod.shared.ui.focus.controllerFocusBorder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Stable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.winlator.cmod.R
import com.winlator.cmod.shared.theme.WinNativeBackground
import com.winlator.cmod.shared.theme.WinNativeOutline
import com.winlator.cmod.shared.theme.WinNativePanel
import com.winlator.cmod.shared.theme.WinNativeSurface
import com.winlator.cmod.shared.theme.WinNativeTextPrimary
import com.winlator.cmod.shared.theme.WinNativeTextSecondary
import com.winlator.cmod.shared.theme.WinNativeTheme
import com.winlator.cmod.shared.ui.dialog.WinNativeDialogButton
import com.winlator.cmod.shared.ui.dialog.WinNativeDialogShell
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav as SharedLocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry as SharedPaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem as sharedPaneNavItem
import com.winlator.cmod.shared.ui.outlinedSwitchColors
import com.winlator.cmod.shared.ui.widget.chasingBorder
import kotlin.math.roundToInt

// Logs pane composables, split out of XServerDrawerMenu.kt (behavior-identical).

@Composable
internal fun LogsPaneContent(
    logsState: LogsPaneState,
    listener: XServerDrawerActionListener,
    onClose: () -> Unit,
) {
    DisposableEffect(Unit) {
        listener.onLogsPaneVisibilityChanged(true)
        onDispose { listener.onLogsPaneVisibilityChanged(false) }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val paneScale = computePaneScale(maxHeight)
        CompositionLocalProvider(LocalPaneScale provides paneScale) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = (12f * paneScale).dp, vertical = (10f * paneScale).dp),
                verticalArrangement = Arrangement.spacedBy((10f * paneScale).dp),
            ) {
                LogsPaneHeader(
                    paused = logsState.paused,
                    lineCount = logsState.lines.size,
                    onClear = { listener.onLogsClear() },
                    onTogglePause = { listener.onLogsPauseChanged(!logsState.paused) },
                    onShare = { listener.onLogsShare() },
                    onClose = onClose,
                )

                LogsPaneList(
                    lines = logsState.lines,
                    paused = logsState.paused,
                    modifier = Modifier.weight(1f, fill = true).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun LogsPaneHeader(
    paused: Boolean,
    lineCount: Int,
    onClear: () -> Unit,
    onTogglePause: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    val paneScale = LocalPaneScale.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((8f * paneScale).dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.session_drawer_logs),
                color = DrawerTextPrimary,
                fontSize = (16f * paneScale).sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    if (paused) {
                        stringResource(R.string.session_drawer_logs_paused_indicator) +
                            " · " +
                            stringResource(R.string.session_drawer_logs_line_count, lineCount)
                    } else {
                        stringResource(R.string.session_drawer_logs_line_count, lineCount)
                    },
                color = if (paused) DrawerAccent else DrawerTextSecondary,
                fontSize = (11f * paneScale).sp,
                fontWeight = FontWeight.Medium,
                // Three action tiles and the close button take about 190 dp of the drawer's
                // fixed 300 dp, so this subtitle wrapped to two or three lines.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LogsPaneActionTile(
            icon = if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
            contentDescription =
                if (paused) {
                    stringResource(R.string.session_drawer_logs_resume)
                } else {
                    stringResource(R.string.session_drawer_logs_pause)
                },
            onClick = onTogglePause,
        )
        LogsPaneActionTile(
            icon = Icons.Outlined.DeleteSweep,
            contentDescription = stringResource(R.string.session_drawer_logs_clear),
            onClick = onClear,
        )
        LogsPaneActionTile(
            icon = Icons.Outlined.Share,
            contentDescription = stringResource(R.string.session_drawer_logs_share),
            onClick = onShare,
        )

        TaskManagerCloseButton(onClick = onClose)
    }
}

@Composable
internal fun LogsPaneActionTile(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val paneScale = LocalPaneScale.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val tint by animateColorAsState(
        targetValue = if (pressed) DrawerAccent else DrawerTextPrimary,
        animationSpec = tween(120),
        label = "logsActionTileTint",
    )
    Box(
        modifier =
            Modifier
                .size((38f * paneScale).dp)
                .clip(CircleShape)
                .paneNavItem(cornerRadius = (19f * paneScale).dp, onActivate = onClick)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size((24f * paneScale).dp),
        )
    }
}

@Composable
internal fun LogsPaneList(
    lines: List<String>,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    val paneScale = LocalPaneScale.current
    val shape = RoundedCornerShape((10f * paneScale).dp)
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size, paused) {
        if (!paused && lines.isNotEmpty()) {
            listState.scrollToItem((lines.size - 1).coerceAtLeast(0))
        }
    }

    Box(
        modifier =
            modifier
                .clip(shape)
                .background(PaneInnerResting)
                .border(1.dp, RestingCardBorder, shape),
    ) {
        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.common_ui_no_items_to_display),
                color = DrawerTextSecondary,
                fontSize = (12f * paneScale).sp,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = (24f * paneScale).dp),
                textAlign = TextAlign.Center,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        horizontal = (10f * paneScale).dp,
                        vertical = (8f * paneScale).dp,
                    ),
                verticalArrangement = Arrangement.spacedBy((1f * paneScale).dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        color = DrawerTextPrimary,
                        fontSize = (11f * paneScale).sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = (14f * paneScale).sp,
                        letterSpacing = 0.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
