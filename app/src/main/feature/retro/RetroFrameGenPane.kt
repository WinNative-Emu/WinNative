package com.winlator.cmod.feature.retro

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.shared.ui.dialog.findActivity
import com.winlator.cmod.runtime.display.FrameGenerationSection
import com.winlator.cmod.runtime.display.LocalPaneScale
import com.winlator.cmod.runtime.display.computePaneScale
import com.winlator.cmod.shared.framegen.FrameGen
import com.winlator.cmod.shared.framegen.FrameGenOptions

object RetroFrameGenPane {
    @Composable
    fun Content(
        context: Context,
        shortcut: Shortcut?,
        systemId: String?,
    ) {
        var revision by remember { mutableIntStateOf(0) }
        val available =
            remember { RetroFrameGen.supported(context) && RetroFrameGen.shadersReady(context) }
        val maxRefreshRate = remember { maxRefreshRateOf(context) }
        val stored =
            remember(revision) {
                Stored(
                    enabled = RetroFrameGen.enabled(context, shortcut, systemId),
                    multiplier = RetroFrameGen.multiplier(context, shortcut, systemId),
                    targetRate = RetroFrameGen.targetRate(context, shortcut, systemId),
                    flowScale = RetroFrameGen.flowScale(context, shortcut, systemId),
                )
            }

        var pending by remember { mutableStateOf<Stored?>(null) }
        val live = FrameGen.active
        val current =
            if (live) {
                Stored(
                    enabled = FrameGen.generating,
                    multiplier = FrameGen.multiplier,
                    targetRate = FrameGen.targetRate,
                    flowScale = FrameGen.flowScale,
                )
            } else {
                pending ?: stored
            }
        val enabled = current.enabled
        val multiplier = current.multiplier
        val targetRate = current.targetRate
        val flowScale = current.flowScale

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
                    FrameGenerationSection(
                        available = available,
                        enabled = enabled,
                        targetRate = targetRate,
                        multiplier = multiplier,
                        flowScale = flowScale,
                        maxRefreshRate = maxRefreshRate,
                        paneScale = paneScale,
                        onEnabledChanged = { value ->
                            persist(shortcut, FrameGenOptions.KEY_ENABLED, if (value) "1" else "0")
                            pending = current.copy(enabled = value)
                            if (value && !FrameGen.active) {
                                FrameGen.install(
                                    context,
                                    RetroFrameGen.liveOptions(context, multiplier, targetRate, flowScale),
                                )
                                FrameGen.applyDisplayMode(context.findActivity())
                                FrameGen.rebindSurface()
                            } else {
                                FrameGen.setGenerating(value)
                                FrameGen.applyDisplayMode(context.findActivity())
                            }
                            revision++
                        },
                        onTargetRateSelected = { rate ->
                            persist(shortcut, FrameGenOptions.KEY_TARGET_RATE, rate.toString())
                            pending = current.copy(targetRate = rate)
                            FrameGen.reconfigure(multiplier, rate, flowScale)
                            FrameGen.applyDisplayMode(context.findActivity())
                            revision++
                        },
                        onMultiplierSelected = { value ->
                            persist(shortcut, FrameGenOptions.KEY_MULTIPLIER, value.toString())
                            pending = current.copy(multiplier = value)
                            FrameGen.reconfigure(value, targetRate, flowScale)
                            FrameGen.applyDisplayMode(context.findActivity())
                            revision++
                        },
                        onFlowScaleChanged = { value ->
                            persist(shortcut, FrameGenOptions.KEY_FLOW_SCALE, value.toString())
                            pending = current.copy(flowScale = value)
                            FrameGen.reconfigure(multiplier, targetRate, value)
                            revision++
                        },
                    )
                }
            }
        }
    }

    private data class Stored(
        val enabled: Boolean,
        val multiplier: Int,
        val targetRate: Int,
        val flowScale: Int,
    )

    private fun maxRefreshRateOf(context: Context): Int {
        val display =
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    context.display
                } else {
                    null
                }
            }.getOrNull()
                ?: runCatching {
                    @Suppress("DEPRECATION")
                    (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                        .defaultDisplay
                }.getOrNull()
        val rate =
            display?.supportedModes?.maxOfOrNull { it.refreshRate }
                ?: display?.refreshRate
                ?: 60f
        return Math.round(rate).coerceAtLeast(60)
    }

    private fun persist(
        shortcut: Shortcut?,
        key: String,
        value: String,
    ) {
        if (shortcut == null) return
        kotlin.concurrent.thread(name = "WnFrameGenPersist") {
            runCatching {
                shortcut.putExtra(key, value)
                shortcut.saveData()
            }
        }
    }
}
