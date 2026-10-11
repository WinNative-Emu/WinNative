package com.winlator.cmod.runtime.display

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.shared.framegen.DisFlowPreset
import com.winlator.cmod.shared.framegen.FrameGenEngine
import com.winlator.cmod.shared.framegen.FrameGenPreset
import kotlin.math.roundToInt

@Composable
internal fun FrameGenPaneContent(
    state: XServerDrawerState,
    listener: XServerDrawerActionListener,
) {
    var fpsLimitMemory by remember {
        mutableStateOf(if (state.fpsLimit > 0) state.fpsLimit else FPS_LIMITER_DEFAULT)
    }
    LaunchedEffect(state.fpsLimit) {
        if (state.fpsLimit > 0) fpsLimitMemory = state.fpsLimit
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val paneScale = computePaneScale(maxHeight)
        CompositionLocalProvider(LocalPaneScale provides paneScale) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = (12f * paneScale).dp, vertical = (12f * paneScale).dp),
                verticalArrangement = Arrangement.spacedBy((10f * paneScale).dp),
            ) {
                val engine = FrameGenEngine.of(state.frameGenEnabled, state.disFrameGenEnabled)

                FrameGenEngineSection(
                    engine = engine,
                    lsfgAvailable = state.frameGenAvailable,
                    targetRate = state.frameGenTargetRate,
                    multiplier = state.frameGenMultiplier,
                    flowScale = state.frameGenFlowScale,
                    disScale = state.disFrameGenScale,
                    disTargetFps = state.disFrameGenTargetFps,
                    disDebugFlow = state.disFrameGenDebugFlow,
                    maxRefreshRate = state.maxRefreshRate,
                    paneScale = paneScale,
                    onEngineSelected = listener::onFrameGenEngineSelected,
                    onTargetRateSelected = listener::onFrameGenTargetRateSelected,
                    onMultiplierSelected = listener::onFrameGenMultiplierSelected,
                    onFlowScaleChanged = listener::onFrameGenFlowScaleChanged,
                    onDisScaleChanged = listener::onDisFrameGenScaleChanged,
                    onDisTargetFpsSelected = listener::onDisFrameGenTargetFpsSelected,
                    onDisDebugFlowChanged = listener::onDisDebugFlowChanged,
                )

                ThinDivider()

                Column(verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp)) {
                    PaneSectionLabel(stringResource(R.string.session_drawer_fps_limiter))

                    Box(
                        Modifier.fillMaxWidth().paneNavItem(
                            cornerRadius = (12f * paneScale).dp,
                            onActivate = {
                                listener.onFPSLimitChanged(
                                    if (state.fpsLimit > 0) {
                                        0
                                    } else {
                                        fpsLimitMemory.coerceIn(FPS_LIMITER_MIN, state.maxRefreshRate)
                                    },
                                )
                            },
                            onAdjust = { dir ->
                                val base = if (state.fpsLimit > 0) state.fpsLimit else fpsLimitMemory
                                val q = base / 5.0
                                val units = if (dir > 0) Math.floor(q + 1e-4) + 1 else Math.ceil(q - 1e-4) - 1
                                listener.onFPSLimitChanged(
                                    (units * 5).toInt().coerceIn(FPS_LIMITER_MIN, state.maxRefreshRate),
                                )
                            },
                        ),
                    ) {
                        FPSLimiterCard(
                            currentLimit = state.fpsLimit,
                            maxRefreshRate = state.maxRefreshRate,
                            onLimitChanged = listener::onFPSLimitChanged,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun FrameGenEngineSection(
    engine: FrameGenEngine,
    lsfgAvailable: Boolean,
    targetRate: Int,
    multiplier: Int,
    flowScale: Int,
    disScale: Int,
    disTargetFps: Int,
    disDebugFlow: Boolean,
    maxRefreshRate: Int,
    paneScale: Float,
    onEngineSelected: (FrameGenEngine) -> Unit,
    onTargetRateSelected: (Int) -> Unit,
    onMultiplierSelected: (Int) -> Unit,
    onFlowScaleChanged: (Int) -> Unit,
    onDisScaleChanged: (Int) -> Unit,
    onDisTargetFpsSelected: (Int) -> Unit,
    onDisDebugFlowChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp)) {
        PaneSectionLabel(stringResource(R.string.session_drawer_frame_generation))

        FrameGenEngineSwitch(
            selected = engine,
            selectableFor = { it != FrameGenEngine.LSFG || lsfgAvailable },
            onSelected = onEngineSelected,
            paneScale = paneScale,
        )

        when (engine) {
            FrameGenEngine.OFF ->
                if (!lsfgAvailable) {
                    FrameGenNote(
                        stringResource(R.string.session_drawer_frame_generation_missing),
                        paneScale,
                    )
                }

            FrameGenEngine.LSFG ->
                FrameGenNote(stringResource(R.string.session_drawer_frame_generation_note), paneScale)

            FrameGenEngine.DIS ->
                FrameGenNote(
                    stringResource(R.string.session_drawer_dis_frame_generation_note),
                    paneScale,
                )
        }

        FrameGenReveal(visible = engine == FrameGenEngine.LSFG) {
            LosslessFrameGenOptions(
                targetRate = targetRate,
                multiplier = multiplier,
                flowScale = flowScale,
                maxRefreshRate = maxRefreshRate,
                paneScale = paneScale,
                onTargetRateSelected = onTargetRateSelected,
                onMultiplierSelected = onMultiplierSelected,
                onFlowScaleChanged = onFlowScaleChanged,
            )
        }

        FrameGenReveal(visible = engine == FrameGenEngine.DIS) {
            DisFrameGenOptions(
                scale = disScale,
                targetFps = disTargetFps,
                debugFlow = disDebugFlow,
                maxRefreshRate = maxRefreshRate,
                paneScale = paneScale,
                onScaleChanged = onDisScaleChanged,
                onTargetFpsSelected = onDisTargetFpsSelected,
                onDebugFlowChanged = onDisDebugFlowChanged,
            )
        }
    }
}

@Composable
internal fun FrameGenEngineSwitch(
    selected: FrameGenEngine,
    selectableFor: (FrameGenEngine) -> Boolean,
    onSelected: (FrameGenEngine) -> Unit,
    paneScale: Float,
) {
    val engines = FrameGenEngine.values()
    val index = engines.indexOf(selected).coerceAtLeast(0)
    val trackRadius = (14f * paneScale).dp
    val segmentRadius = (11f * paneScale).dp
    val trackPadding = (3f * paneScale).dp
    val segmentHeight = (36f * paneScale).dp

    val step = { direction: Int ->
        var next = index
        var tried = 0
        do {
            next = (next + direction + engines.size) % engines.size
            tried++
        } while (tried < engines.size && !selectableFor(engines[next]))
        if (next != index && selectableFor(engines[next])) onSelected(engines[next])
    }

    BoxWithConstraints(
        modifier =
            Modifier.fillMaxWidth().paneNavItem(
                cornerRadius = trackRadius,
                onActivate = { step(1) },
                onAdjust = { direction -> step(if (direction > 0) 1 else -1) },
            ),
    ) {
        val segmentWidth = (maxWidth - trackPadding * 2) / engines.size
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * index,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            label = "frameGenEngineIndicator",
        )
        val active = selected != FrameGenEngine.OFF
        val trackShape = RoundedCornerShape(trackRadius)
        val segmentShape = RoundedCornerShape(segmentRadius)

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(trackShape)
                    .background(PaneInnerResting)
                    .border(1.dp, RestingCardBorder, trackShape)
                    .padding(trackPadding),
        ) {
            Box(
                modifier =
                    Modifier
                        .offset(x = indicatorOffset)
                        .width(segmentWidth)
                        .height(segmentHeight)
                        .clip(segmentShape)
                        .background(if (active) DrawerAccent.copy(alpha = 0.20f) else PaneInnerPressed)
                        .border(1.dp, if (active) DrawerAccent else RestingCardBorder, segmentShape),
            )

            Row(modifier = Modifier.fillMaxWidth().height(segmentHeight)) {
                engines.forEach { option ->
                    val current = option == selected
                    val selectable = selectableFor(option)
                    val interactionSource = remember(option) { MutableInteractionSource() }
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(segmentShape)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    enabled = selectable,
                                    onClick = { onSelected(option) },
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(option.labelRes),
                            color =
                                when {
                                    !selectable -> DrawerTextSecondary.copy(alpha = 0.38f)
                                    current -> DrawerTextPrimary
                                    else -> DrawerTextSecondary
                                },
                            fontSize = (13f * paneScale).sp,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameGenReveal(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            expandVertically(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top,
            ) + fadeIn(animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)),
        exit =
            shrinkVertically(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing)),
    ) {
        content()
    }
}

@Composable
internal fun FrameGenerationSection(
    available: Boolean,
    enabled: Boolean,
    targetRate: Int,
    multiplier: Int,
    flowScale: Int,
    maxRefreshRate: Int,
    paneScale: Float,
    onEnabledChanged: (Boolean) -> Unit,
    onTargetRateSelected: (Int) -> Unit,
    onMultiplierSelected: (Int) -> Unit,
    onFlowScaleChanged: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp)) {
        PaneSectionLabel(stringResource(R.string.session_drawer_frame_generation))

        if (!available) {
            FrameGenNote(stringResource(R.string.session_drawer_frame_generation_missing), paneScale)
        } else {
            NavBooleanRow(
                title = stringResource(R.string.session_drawer_frame_generation_enable),
                checked = enabled,
                onCheckedChange = onEnabledChanged,
            )

            FrameGenNote(stringResource(R.string.session_drawer_frame_generation_note), paneScale)

            FrameGenReveal(visible = enabled) {
                LosslessFrameGenOptions(
                    targetRate = targetRate,
                    multiplier = multiplier,
                    flowScale = flowScale,
                    maxRefreshRate = maxRefreshRate,
                    paneScale = paneScale,
                    onTargetRateSelected = onTargetRateSelected,
                    onMultiplierSelected = onMultiplierSelected,
                    onFlowScaleChanged = onFlowScaleChanged,
                )
            }
        }
    }
}

@Composable
private fun LosslessFrameGenOptions(
    targetRate: Int,
    multiplier: Int,
    flowScale: Int,
    maxRefreshRate: Int,
    paneScale: Float,
    onTargetRateSelected: (Int) -> Unit,
    onMultiplierSelected: (Int) -> Unit,
    onFlowScaleChanged: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp)) {
        FrameGenFieldLabel(
            stringResource(R.string.session_drawer_frame_generation_target),
            paneScale,
        )

        val rates =
            remember(maxRefreshRate, targetRate) {
                (
                    FrameGenTargetRates.filter { it <= maxRefreshRate } +
                        listOfNotNull(targetRate.takeIf { it > 0 })
                )
                    .distinct()
                    .sorted()
            }

        ChipFlow {
            HUDToggleChip(
                label = stringResource(R.string.session_drawer_frame_generation_target_off),
                checked = targetRate == 0,
                onClick = { onTargetRateSelected(0) },
                modifier = Modifier.paneNavItem(
                    cornerRadius = (16f * paneScale).dp,
                    onActivate = { onTargetRateSelected(0) },
                ),
            )
            rates.forEach { rate ->
                HUDToggleChip(
                    label = stringResource(
                        R.string.session_drawer_frame_generation_target_value,
                        rate,
                    ),
                    checked = targetRate == rate,
                    onClick = { onTargetRateSelected(rate) },
                    modifier = Modifier.paneNavItem(
                        cornerRadius = (16f * paneScale).dp,
                        onActivate = { onTargetRateSelected(rate) },
                    ),
                )
            }
        }

        if (targetRate == 0) {
            FrameGenFieldLabel(
                stringResource(R.string.session_drawer_frame_generation_multiplier),
                paneScale,
            )
            ChipFlow {
                FrameGenMultipliers.forEach { option ->
                    HUDToggleChip(
                        label = stringResource(
                            R.string.session_drawer_frame_generation_multiplier_value,
                            option,
                        ),
                        checked = multiplier == option,
                        onClick = { onMultiplierSelected(option) },
                        modifier = Modifier.paneNavItem(
                            cornerRadius = (16f * paneScale).dp,
                            onActivate = { onMultiplierSelected(option) },
                        ),
                    )
                }
            }
        } else {
            FrameGenNote(
                stringResource(R.string.session_drawer_frame_generation_target_note),
                paneScale,
            )
        }

        FrameGenPresetRow(
            selected = FrameGenPreset.fromFlowScale(flowScale),
            onSelected = { onFlowScaleChanged(it.flowScale) },
            paneScale = paneScale,
        )
    }
}

@Composable
private fun DisFrameGenOptions(
    scale: Int,
    targetFps: Int,
    debugFlow: Boolean,
    maxRefreshRate: Int,
    paneScale: Float,
    onScaleChanged: (Int) -> Unit,
    onTargetFpsSelected: (Int) -> Unit,
    onDebugFlowChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp)) {
        FrameGenFieldLabel(
            stringResource(R.string.session_drawer_dis_resolution_scale),
            paneScale,
        )

        val flowPreset = DisFlowPreset.fromStored(scale)
        ChipFlow {
            DisFlowPreset.values().forEach { preset ->
                HUDToggleChip(
                    label = stringResource(preset.labelRes),
                    checked = flowPreset == preset,
                    onClick = { onScaleChanged(preset.minSide) },
                    modifier = Modifier.paneNavItem(
                        cornerRadius = (16f * paneScale).dp,
                        onActivate = { onScaleChanged(preset.minSide) },
                    ),
                )
            }
        }

        FrameGenNote(
            stringResource(
                R.string.session_drawer_dis_resolution_scale_note,
                flowPreset.minSide,
            ),
            paneScale,
        )

        FrameGenFieldLabel(
            stringResource(R.string.session_drawer_dis_target_fps),
            paneScale,
        )

        val rates =
            remember(maxRefreshRate, targetFps) {
                (
                    DisFrameGenTargetRates.filter { it <= maxRefreshRate } +
                        listOfNotNull(targetFps.takeIf { it > 0 })
                )
                    .distinct()
                    .sorted()
            }

        ChipFlow {
            HUDToggleChip(
                label = stringResource(R.string.session_drawer_dis_target_fps_max),
                checked = targetFps == 0,
                onClick = { onTargetFpsSelected(0) },
                modifier = Modifier.paneNavItem(
                    cornerRadius = (16f * paneScale).dp,
                    onActivate = { onTargetFpsSelected(0) },
                ),
            )
            rates.forEach { rate ->
                HUDToggleChip(
                    label = stringResource(R.string.session_drawer_dis_target_fps_value, rate),
                    checked = targetFps == rate,
                    onClick = { onTargetFpsSelected(rate) },
                    modifier = Modifier.paneNavItem(
                        cornerRadius = (16f * paneScale).dp,
                        onActivate = { onTargetFpsSelected(rate) },
                    ),
                )
            }
        }

        NavBooleanRow(
            title = stringResource(R.string.session_drawer_dis_debug_flow),
            checked = debugFlow,
            onCheckedChange = onDebugFlowChanged,
        )

        FrameGenNote(stringResource(R.string.session_drawer_dis_debug_flow_note), paneScale)
    }
}

@Composable
private fun FrameGenPresetRow(
    selected: FrameGenPreset,
    onSelected: (FrameGenPreset) -> Unit,
    paneScale: Float,
) {
    val presets = FrameGenPreset.values()
    val index = presets.indexOf(selected).coerceAtLeast(0)

    Column(verticalArrangement = Arrangement.spacedBy((4f * paneScale).dp)) {
        NavSliderRow(
            label = stringResource(R.string.frame_generation_preset),
            valueText = stringResource(selected.labelRes),
            value = index.toFloat(),
            valueRange = 0f..(presets.size - 1).toFloat(),
            steps = presets.size - 2,
            adjustStep = 1f,
            onValueChange = { onSelected(FrameGenPreset.atIndex(it.roundToInt())) },
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            presets.forEachIndexed { i, preset ->
                Text(
                    text = stringResource(preset.shortLabelRes),
                    color = if (i == index) DrawerAccent else DrawerTextSecondary,
                    fontSize = (10f * paneScale).sp,
                    fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        presets.size - 1 -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        FrameGenNote(stringResource(selected.descriptionRes), paneScale)
    }
}

@Composable
private fun FrameGenFieldLabel(text: String, paneScale: Float) {
    Text(
        text = text,
        color = DrawerTextSecondary,
        fontSize = (12f * paneScale).sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun FrameGenNote(text: String, paneScale: Float) {
    Text(
        text = text,
        color = DrawerTextSecondary,
        fontSize = (11f * paneScale).sp,
        lineHeight = (15f * paneScale).sp,
    )
}
