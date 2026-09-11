package com.winlator.cmod.runtime.display

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.shared.framegen.DisFlowPreset
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
                FrameGenerationSection(state = state, listener = listener, paneScale = paneScale)

                ThinDivider()

                DisFrameGenerationSection(state = state, listener = listener, paneScale = paneScale)

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
private fun FrameGenerationSection(
    state: XServerDrawerState,
    listener: XServerDrawerActionListener,
    paneScale: Float,
) {
    FrameGenerationSection(
        available = state.frameGenAvailable,
        enabled = state.frameGenEnabled,
        targetRate = state.frameGenTargetRate,
        multiplier = state.frameGenMultiplier,
        flowScale = state.frameGenFlowScale,
        maxRefreshRate = state.maxRefreshRate,
        paneScale = paneScale,
        onEnabledChanged = listener::onFrameGenEnabledChanged,
        onTargetRateSelected = listener::onFrameGenTargetRateSelected,
        onMultiplierSelected = listener::onFrameGenMultiplierSelected,
        onFlowScaleChanged = listener::onFrameGenFlowScaleChanged,
        blockedByDis = state.disFrameGenEnabled,
    )
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
    // The compositor drives one interpolator per frame, so the two engines are
    // exclusive. The engine that is not running shows no switch at all, only a
    // line naming the one to turn off first.
    blockedByDis: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp)) {
        PaneSectionLabel(stringResource(R.string.session_drawer_frame_generation))

        if (!available) {
            FrameGenNote(stringResource(R.string.session_drawer_frame_generation_missing), paneScale)
        } else if (blockedByDis) {
            // Not an inert switch: a Material switch that is tapped but whose
            // `checked` never changes can be left sitting in the new position,
            // which is exactly how two engines end up looking enabled at once.
            // With no switch present there is nothing to light up.
            FrameGenNote(
                stringResource(R.string.session_drawer_frame_generation_blocked_by_dis),
                paneScale,
            )
        } else {
            NavBooleanRow(
                title = stringResource(R.string.session_drawer_frame_generation_enable),
                checked = enabled,
                onCheckedChange = onEnabledChanged,
            )

            FrameGenNote(stringResource(R.string.session_drawer_frame_generation_note), paneScale)

            AnimatedVisibility(
                visible = enabled,
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
        }
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

@Composable
private fun DisFrameGenerationSection(
    state: XServerDrawerState,
    listener: XServerDrawerActionListener,
    paneScale: Float,
) {
    Column(verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp)) {
        PaneSectionLabel(stringResource(R.string.session_drawer_dis_frame_generation))

        if (state.frameGenEnabled) {
            FrameGenNote(
                stringResource(R.string.session_drawer_dis_frame_generation_blocked_by_lsfg),
                paneScale,
            )
        } else {
            NavBooleanRow(
                title = stringResource(R.string.session_drawer_dis_frame_generation_enable),
                checked = state.disFrameGenEnabled,
                onCheckedChange = listener::onDisFrameGenEnabledChanged,
            )

            FrameGenNote(
                stringResource(R.string.session_drawer_dis_frame_generation_note),
                paneScale,
            )
        }

        AnimatedVisibility(
            visible = state.disFrameGenEnabled,
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
            Column(verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp)) {
                FrameGenFieldLabel(
                    stringResource(R.string.session_drawer_dis_resolution_scale),
                    paneScale,
                )

                val flowPreset = DisFlowPreset.fromStored(state.disFrameGenScale)
                ChipFlow {
                    DisFlowPreset.values().forEach { preset ->
                        HUDToggleChip(
                            label = stringResource(preset.labelRes),
                            checked = flowPreset == preset,
                            onClick = { listener.onDisFrameGenScaleChanged(preset.minSide) },
                            modifier = Modifier.paneNavItem(
                                cornerRadius = (16f * paneScale).dp,
                                onActivate = { listener.onDisFrameGenScaleChanged(preset.minSide) },
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

                NavBooleanRow(
                    title = stringResource(R.string.session_drawer_dis_debug_flow),
                    checked = state.disFrameGenDebugFlow,
                    onCheckedChange = listener::onDisDebugFlowChanged,
                )

                FrameGenFieldLabel(
                    stringResource(R.string.session_drawer_dis_target_fps),
                    paneScale,
                )

                val rates =
                    remember(state.maxRefreshRate, state.disFrameGenTargetFps) {
                        (
                            DisFrameGenTargetRates.filter { it <= state.maxRefreshRate } +
                                listOfNotNull(state.disFrameGenTargetFps.takeIf { it > 0 })
                        )
                            .distinct()
                            .sorted()
                    }

                ChipFlow {
                    HUDToggleChip(
                        label = stringResource(R.string.session_drawer_dis_target_fps_max),
                        checked = state.disFrameGenTargetFps == 0,
                        onClick = { listener.onDisFrameGenTargetFpsSelected(0) },
                        modifier = Modifier.paneNavItem(
                            cornerRadius = (16f * paneScale).dp,
                            onActivate = { listener.onDisFrameGenTargetFpsSelected(0) },
                        ),
                    )
                    rates.forEach { rate ->
                        HUDToggleChip(
                            label =
                                stringResource(
                                    R.string.session_drawer_dis_target_fps_value,
                                    rate,
                                ),
                            checked = state.disFrameGenTargetFps == rate,
                            onClick = { listener.onDisFrameGenTargetFpsSelected(rate) },
                            modifier = Modifier.paneNavItem(
                                cornerRadius = (16f * paneScale).dp,
                                onActivate = { listener.onDisFrameGenTargetFpsSelected(rate) },
                            ),
                        )
                    }
                }
            }
        }
    }
}
