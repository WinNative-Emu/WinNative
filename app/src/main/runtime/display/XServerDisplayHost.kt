package com.winlator.cmod.runtime.display

import android.widget.FrameLayout
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.winlator.cmod.R
import com.winlator.cmod.shared.theme.WinNativeTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

const val XSERVER_DRAWER_EDGE_SWIPE_DP = 24

// Horizontal swipe distance to open the drawer; shared with XServerDisplayActivity.
// 16dp: a short swipe from the edge reliably opens (was 32dp) so you no longer have to
// drag toward the middle for the drawer to appear.
const val XSERVER_DRAWER_OPEN_TRIGGER_DP = 16

// Open only on a clearly horizontal swipe: dx must exceed this * |dy| (~27deg of horizontal).
// 1.5f: tolerate a slightly diagonal swipe (was 2f) so an imperfect angle doesn't bounce back.
const val XSERVER_DRAWER_OPEN_HORIZONTAL_RATIO = 1.5f

private val DrawerWidth = 300.dp
private val DrawerStartPadding = 6.dp
private val DrawerVerticalPadding = 6.dp
private const val DrawerSettleAnimationMs = 200
// Dragging out any visible amount of the drawer keeps it open. The settle check is
// drawerProgress >= this, and progress = draggedPx / drawerWidthPx (~300dp), so 0 means
// “as soon as the gesture was claimed (past the open trigger) it stays open”; a positive
// value would again bounce back for anything dragged less than threshold × width.
private const val DrawerOpenSettleThreshold = 0f
private const val DrawerCloseSettleThreshold = 0.65f
private val DrawerSettleAnimationSpec =
    tween<Float>(
        durationMillis = DrawerSettleAnimationMs,
        easing = LinearEasing,
    )

interface XServerDisplayHostCallbacks {
    fun onDrawerSlide()

    fun onDrawerOpened()

    fun onDrawerClosed()

    fun onDrawerGestureClaimed()

    fun onDialogVisibilityChanged(visible: Boolean)

    fun isControllerConnected(): Boolean
}

fun setupXServerDisplayHost(
    composeView: ComposeView,
    displayFrame: FrameLayout,
    stateHolder: XServerDrawerStateHolder,
    listener: XServerDrawerActionListener,
    callbacks: XServerDisplayHostCallbacks,
) {
    composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    composeView.setContent {
        XServerDisplayHost(
            displayFrame = displayFrame,
            stateHolder = stateHolder,
            listener = listener,
            callbacks = callbacks,
        )
    }
}

@Composable
private fun XServerDisplayHost(
    displayFrame: FrameLayout,
    stateHolder: XServerDrawerStateHolder,
    listener: XServerDrawerActionListener,
    callbacks: XServerDisplayHostCallbacks,
) {
    val animationScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val screenWidthPx =
        with(density) {
            androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
        }
    val closedFallbackPx = with(density) { -(DrawerWidth + DrawerStartPadding).toPx() }
    var drawerOffsetPx by remember { mutableFloatStateOf(closedFallbackPx) }
    var drawerWidthPx by remember { mutableFloatStateOf(0f) }
    val drawerClosedOffset =
        if (drawerWidthPx > 0f) {
            -drawerWidthPx - with(density) { DrawerStartPadding.toPx() }
        } else {
            closedFallbackPx
        }
    val drawerOpenOffset = 0f
    var lastSide by remember {
        androidx.compose.runtime.mutableStateOf<com.winlator.cmod.runtime.display.DrawerSide?>(
            null,
        )
    }
    LaunchedEffect(stateHolder.openSide) {
        if (stateHolder.openSide != null) {
            lastSide = stateHolder.openSide
        }
    }
    // "Engaged" = on or sliding onto the screen; drives the card-reveal animation (the content itself is always composed).
    val drawerEngaged = drawerWidthPx <= 0f ||
        drawerOffsetPx > drawerClosedOffset + 1f ||
        stateHolder.isDrawerOpen
    val drawerContentComposed = stateHolder.isDrawerOpen ||
        (drawerWidthPx > 0f && drawerOffsetPx > drawerClosedOffset + 1f)
    val dialogVisible = false

    DisposableEffect(stateHolder) {
        stateHolder.setPaneVisibilityListener { }
        onDispose {
            stateHolder.clearPaneVisibilityListener()
        }
    }

    LaunchedEffect(drawerWidthPx) {
        if (drawerWidthPx > 0f && drawerOffsetPx < 0f && !stateHolder.isDrawerOpen) {
            drawerOffsetPx = drawerClosedOffset
        }
    }

    LaunchedEffect(stateHolder.isDrawerOpen, drawerWidthPx) {
        val target = if (stateHolder.isDrawerOpen) drawerOpenOffset else drawerClosedOffset
        if (drawerOffsetPx != target) {
            callbacks.onDrawerSlide()
            animate(
                initialValue = drawerOffsetPx,
                targetValue = target,
                animationSpec = DrawerSettleAnimationSpec,
            ) { value, _ ->
                drawerOffsetPx = value
                callbacks.onDrawerSlide()
            }
            if (stateHolder.isDrawerOpen) {
                callbacks.onDrawerOpened()
            } else {
                callbacks.onDrawerClosed()
            }
        }
    }

    LaunchedEffect(dialogVisible) {
        callbacks.onDialogVisibilityChanged(dialogVisible)
    }

    // On swap-back, re-measure the hosted display frame so the reparented surface reclaims full size.
    LaunchedEffect(stateHolder.phoneRelayoutTick) {
        if (stateHolder.phoneRelayoutTick > 0) displayFrame.requestLayout()
    }

    WinNativeTheme {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(drawerWidthPx, dialogVisible) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (dialogVisible || drawerWidthPx <= 0f) return@awaitEachGesture

                            val edgeWidthPx = XSERVER_DRAWER_EDGE_SWIPE_DP.dp.toPx()
                            val openTriggerPx = XSERVER_DRAWER_OPEN_TRIGGER_DP.dp.toPx()
                            val drawerOpen = stateHolder.isDrawerOpen
                            val sheetAtRight =
                                (stateHolder.openSide ?: lastSide) ==
                                    com.winlator.cmod.runtime.display.DrawerSide.RIGHT
                            val sheetLeftPx = screenWidthPx - drawerWidthPx
                            val canStartFromHere =
                                if (drawerOpen) {
                                    if (sheetAtRight) {
                                        down.position.x >= sheetLeftPx - edgeWidthPx &&
                                            down.position.x <= sheetLeftPx
                                    } else {
                                        down.position.x >= drawerWidthPx &&
                                            down.position.x <= drawerWidthPx + edgeWidthPx
                                    }
                                } else {
                                    down.position.x <= edgeWidthPx ||
                                        down.position.x >= screenWidthPx - edgeWidthPx
                                }
                            if (!canStartFromHere) {
                                if (drawerOpen && !callbacks.isControllerConnected()) {
                                    val outside =
                                        if (sheetAtRight) down.position.x < sheetLeftPx
                                        else down.position.x > drawerWidthPx
                                    if (outside) stateHolder.closeDrawer()
                                }
                                return@awaitEachGesture
                            }

                            var gestureClaimed = false
                            var cancelledByVerticalDrag = false
                            var totalDx = 0f
                            var totalDy = 0f
                            var dragStartOffset = drawerOffsetPx

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) break

                                val delta = change.positionChange()
                                totalDx += delta.x
                                totalDy += delta.y

                                if (!gestureClaimed) {
                                    // Only an *obviously* vertical drag cancels the gesture; a slightly
                                    // diagonal swipe (dy up to 2x dx) still counts as horizontal so a
                                    // not-quite-straight swipe opens instead of bouncing back.
                                    if (abs(totalDy) > viewConfiguration.touchSlop &&
                                        abs(totalDy) > abs(totalDx) * 2f
                                    ) {
                                        cancelledByVerticalDrag = true
                                        break
                                    }
                                    val horizontalDragClaimed =
                                        if (stateHolder.isDrawerOpen) {
                                            val closeDx = if (sheetAtRight) totalDx > 0f else totalDx < 0f
                                            abs(totalDx) > viewConfiguration.touchSlop &&
                                                abs(totalDx) > abs(totalDy) &&
                                                closeDx
                                        } else {
                                            val openDx = if (sheetAtRight) totalDx < 0f else totalDx > 0f
                                            abs(totalDx) > openTriggerPx &&
                                                abs(totalDx) >
                                                    abs(totalDy) * XSERVER_DRAWER_OPEN_HORIZONTAL_RATIO &&
                                                openDx
                                        }
                                    if (horizontalDragClaimed) {
                                        gestureClaimed = true
                                        dragStartOffset = drawerOffsetPx
                                        totalDx = 0f
                                        callbacks.onDrawerGestureClaimed()
                                    }
                                }

                                if (gestureClaimed) {
                                    change.consume()
                                    val nextOffset =
                                        (dragStartOffset + totalDx)
                                            .coerceIn(drawerClosedOffset, drawerOpenOffset)
                                    drawerOffsetPx = nextOffset
                                    callbacks.onDrawerSlide()
                                }
                            }

                            if (gestureClaimed && !cancelledByVerticalDrag) {
                                val drawerOpenProgress =
                                    if (drawerClosedOffset < 0f) {
                                        ((drawerOffsetPx - drawerClosedOffset) / -drawerClosedOffset)
                                            .coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                val shouldOpen =
                                    if (stateHolder.isDrawerOpen) {
                                        drawerOpenProgress > DrawerCloseSettleThreshold
                                    } else {
                                        drawerOpenProgress >= DrawerOpenSettleThreshold
                                    }
                                val target = if (shouldOpen) drawerOpenOffset else drawerClosedOffset
                                animationScope.launch {
                                    animate(
                                        initialValue = drawerOffsetPx,
                                        targetValue = target,
                                        animationSpec = DrawerSettleAnimationSpec,
                                    ) { value, _ ->
                                        drawerOffsetPx = value
                                        callbacks.onDrawerSlide()
                                    }
                                    if (shouldOpen) {
                                        if (!stateHolder.isDrawerOpen) stateHolder.openDrawer()
                                        callbacks.onDrawerOpened()
                                    } else {
                                        if (stateHolder.isDrawerOpen) stateHolder.closeDrawer()
                                        callbacks.onDrawerClosed()
                                    }
                                }
                            }
                        }
                    },
        ) {
            val drawerTopInset = DrawerVerticalPadding
            val originalHeight = maxHeight - DrawerVerticalPadding * 2
            val drawerHeight = maxHeight - drawerTopInset - DrawerVerticalPadding
            val evenScale =
                if (originalHeight > 0.dp) {
                    (drawerHeight / originalHeight).coerceIn(0.6f, 1f)
                } else {
                    1f
                }
            val scaledDrawerWidth = DrawerWidth * evenScale
            // Derived, not measured, so the sheet need not exist while closed.
            val scaledDrawerWidthPx = with(density) { scaledDrawerWidth.toPx() }
            LaunchedEffect(scaledDrawerWidthPx) {
                drawerWidthPx = scaledDrawerWidthPx
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                AndroidView(
                    factory = { displayFrame },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .zIndex(0f),
                    update = {},
                )
            }

            // Performance HUD: half the screen (left in landscape, top in portrait), consuming its own touches so the rest stays a trackpad. Hosted here, not a nested ComposeView.
            val perfHudVisible by PerformanceHudState.visible.collectAsState()
            if (perfHudVisible) {
                val landscape =
                    LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                Box(
                    modifier =
                        Modifier
                            .zIndex(1f)
                            .then(
                                if (landscape) {
                                    Modifier.fillMaxHeight().fillMaxWidth(0.5f).align(Alignment.CenterStart)
                                } else {
                                    Modifier.fillMaxWidth().fillMaxHeight(0.5f).align(Alignment.TopCenter)
                                },
                            )
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent().changes.forEach { it.consume() }
                                    }
                                }
                            },
                ) {
                    PerformanceHudOverlay()
                }
            }

            // Closed, the sheet sits flush on the edge and its rounded corners leak a hairline.
            val openRight =
                (stateHolder.openSide ?: lastSide) == com.winlator.cmod.runtime.display.DrawerSide.RIGHT
            if (drawerContentComposed) {
                ModalDrawerSheet(
                    drawerShape = RoundedCornerShape(20.dp),
                    drawerContainerColor = PaneSurfaceColor,
                    drawerContentColor = Color.Unspecified,
                    drawerTonalElevation = 0.dp,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    modifier =
                        Modifier
                            .zIndex(2f)
                            .then(if (openRight) Modifier.align(Alignment.CenterEnd) else Modifier)
                            .padding(
                                start = if (openRight) 0.dp else DrawerStartPadding,
                                end = if (openRight) DrawerStartPadding else 0.dp,
                                top = drawerTopInset,
                                bottom = DrawerVerticalPadding,
                            )
                            .fillMaxHeight()
                            .width(scaledDrawerWidth)
                            .offset {
                                val effective =
                                    if (stateHolder.isDrawerOpen) drawerOffsetPx else drawerClosedOffset
                                val dx = effective.roundToInt()
                                androidx.compose.ui.unit.IntOffset(if (openRight) -dx else dx, 0)
                            },
                ) {
                    XServerDrawerContent(
                        state = stateHolder.state,
                        taskManagerState = stateHolder.taskManagerState,
                        logsState = stateHolder.logsState,
                        openPane = stateHolder.openPane,
                        onOpenPaneChange = { stateHolder.setOpenPaneAndNotify(it) },
                        listener = listener,
                        onDismiss = { stateHolder.closeDrawer() },
                        openSide = stateHolder.openSide,
                        onDrawerLayoutChanged = { newItems, newPaneSides ->
                            stateHolder.state =
                                stateHolder.state.copy(items = newItems, paneSides = newPaneSides)
                        },
                        revealCards = drawerEngaged,
                        menuNavRegion = stateHolder.menuNavRegion,
                        menuNavIndex = stateHolder.menuNavIndex,
                        menuActivateSignal = stateHolder.menuActivateSignal,
                        onSetTabCount = { stateHolder.setMenuTabCount(it) },
                        onSetCardLayout = { c, cols -> stateHolder.setMenuCardLayout(c, cols) },
                        onSetBottomCount = { stateHolder.setMenuBottomCount(it) },
                        onCursor = { r, i -> stateHolder.setMenuNav(r, i) },
                        paneNavSignal = stateHolder.paneNavSignal,
                        paneNavDir = stateHolder.paneNavDir,
                        controllerActive = stateHolder.controllerConnected,
                        onOverlayCloserChange = { stateHolder.paneOverlayCloser = it },
                    )
                }
            }
        }
    }
}
