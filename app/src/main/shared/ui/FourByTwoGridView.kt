package com.winlator.cmod.shared.ui
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.winlator.cmod.app.config.DeviceProfileSettings
import com.winlator.cmod.shared.ui.layout.isPortraitLayout
import com.winlator.cmod.shared.ui.layout.screenWidthDp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

enum class ViewMode { Grid }

fun gridColumnsForWidth(widthDp: Int): Int = DeviceProfileSettings.stockLibraryColumns(widthDp)

@Composable
fun defaultGridColumns(): Int =
    DeviceProfileSettings.libraryColumns(
        LocalContext.current,
        screenWidthDp().value.toInt(),
        isPortraitLayout(),
    )

@Composable
fun defaultGridImageAspect(): Float? = DeviceProfileSettings.libraryImageAspect(LocalContext.current)

fun gridRowHeightFor(
    columnWidth: Dp,
    imageAspect: Float?,
    titleStripDp: Float,
): Dp =
    if (imageAspect == null || imageAspect <= 0f) {
        columnWidth * DeviceProfileSettings.STOCK_LIBRARY_CARD_FACTOR
    } else {
        columnWidth / imageAspect + titleStripDp.dp
    }

/**
 * Unified grid layout used by store tabs
 *
 * @param items The data to display.
 * @param modifier Outer modifier (padding, size, etc.).
 * @param columns Number of grid columns.
 * @param spacing Gap between rows and columns.
 * @param contentPadding Extra padding inside the grid (e.g. for chasing-border inset).
 * @param gridState Shared [LazyGridState] — pass one in when you need joystick scroll.
 * @param clipContent Set to `false` to allow items to draw outside bounds (chasing border).
 * @param viewMode Reserved for future layout switching.
 * @param itemContent Composable for each item; receives index and computed row height.
 */
@Composable
fun <T> FourByTwoGridView(
    items: List<T>,
    modifier: Modifier = Modifier,
    columns: Int = defaultGridColumns(),
    imageAspect: Float? = defaultGridImageAspect(),
    spacing: Dp = 12.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    gridState: LazyGridState = rememberLazyGridState(),
    clipContent: Boolean = true,
    viewMode: ViewMode = ViewMode.Grid,
    keyOf: ((T) -> Any)? = null,
    itemContent: @Composable (item: T, index: Int, rowHeight: Dp) -> Unit,
) {
    when (viewMode) {
        ViewMode.Grid -> {
            BoxWithConstraints(modifier.fillMaxSize()) {
                val layoutDirection = LocalLayoutDirection.current
                val navBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val effectiveContentPadding =
                    PaddingValues(
                        start = contentPadding.calculateStartPadding(layoutDirection),
                        top = contentPadding.calculateTopPadding(),
                        end = contentPadding.calculateEndPadding(layoutDirection),
                        bottom = contentPadding.calculateBottomPadding() + navBottomInset,
                    )
                // Visible rows = 2; subtract one gap between them plus any content-padding inset
                val verticalInset =
                    effectiveContentPadding.calculateTopPadding() +
                        effectiveContentPadding.calculateBottomPadding()
                val horizontalInset =
                    effectiveContentPadding.calculateStartPadding(layoutDirection) +
                        effectiveContentPadding.calculateEndPadding(layoutDirection)
                val effectiveColumns = columns.coerceAtLeast(1)
                val availableRowHeight = ((maxHeight - spacing - verticalInset) / 2).coerceAtLeast(1.dp)
                val availableColumnWidth =
                    ((maxWidth - horizontalInset - spacing * (effectiveColumns - 1).toFloat()) / effectiveColumns.toFloat())
                        .coerceAtLeast(1.dp)
                val targetRowHeight =
                    minOf(
                        availableRowHeight,
                        gridRowHeightFor(availableColumnWidth, imageAspect, DeviceProfileSettings.libraryTitleStripDp()),
                    )
                val rowHeight by animateDpAsState(
                    targetValue = targetRowHeight,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessHigh,
                        ),
                    label = "rowHeight",
                )

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    contentPadding = effectiveContentPadding,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .then(if (!clipContent) Modifier.graphicsLayer { clip = false } else Modifier),
                ) {
                    itemsIndexed(
                        items = items,
                        key =
                            if (keyOf != null) {
                                { _, item -> keyOf(item) }
                            } else {
                                null
                            },
                    ) { index, item ->
                        itemContent(item, index, rowHeight)
                    }
                }

                // Snap to nearest row when mouse/touch scroll ends
                LaunchedEffect(gridState) {
                    snapshotFlow { gridState.isScrollInProgress }
                        .collect { scrolling ->
                            if (!scrolling) {
                                val info = gridState.layoutInfo
                                val firstVisible = info.visibleItemsInfo.firstOrNull() ?: return@collect
                                val row = firstVisible.index / columns
                                // If more than half the first row is scrolled off, snap to the next row
                                val snapToNext = firstVisible.offset.y < -(firstVisible.size.height / 2)
                                val targetRow = if (snapToNext) row + 1 else row
                                gridState.animateScrollToItem(targetRow * columns)
                            }
                        }
                }
            }
        }
    }
}

/**
 *
 * @param gridState The grid to scroll.
 * @param stickFlow The analog-stick value flow (–1..1).
 * @param deadZone Minimum absolute value before scrolling starts.
 * @param minSpeed Pixels-per-frame at the dead-zone edge.
 * @param maxSpeed Pixels-per-frame at full deflection.
 * @param quadratic Use a squared curve for acceleration (smoother ramp up).
 */
@Composable
fun JoystickGridScroll(
    gridState: LazyGridState,
    stickFlow: StateFlow<Float>?,
    deadZone: Float = 0.1f,
    minSpeed: Float = 1.25f,
    maxSpeed: Float = 8f,
    quadratic: Boolean = false,
) {
    val density = LocalContext.current.resources.displayMetrics.density
    if (stickFlow == null) return

    LaunchedEffect(gridState) {
        stickFlow.collect { value ->
            if (abs(value) > deadZone) {
                while (abs(stickFlow.value) > deadZone) {
                    val current = stickFlow.value
                    val factor = abs(current)
                    val curve = if (quadratic) factor * factor else factor
                    val speed = minSpeed + (curve * (maxSpeed - minSpeed))
                    val direction = if (current > 0) 1f else -1f
                    gridState.dispatchRawDelta(speed * direction * density)
                    delay(16)
                }
            }
        }
    }
}
