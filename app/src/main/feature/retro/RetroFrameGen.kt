package com.winlator.cmod.feature.retro

import android.content.Context
import android.content.Intent
import com.winlator.cmod.feature.library.LosslessAutoImport
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.display.lsfg.LosslessScaling
import com.winlator.cmod.shared.framegen.FrameGenOptions

object RetroFrameGen {
    private const val SYSTEM_DRIVER = "System"
    private const val CONSOLE_RATE = 60

    fun supported(context: Context): Boolean =
        LosslessScaling.isSupportedByGpu(context, SYSTEM_DRIVER)

    fun shadersReady(context: Context): Boolean =
        LosslessScaling.resolveCacheFile(context, true) != null

    fun enabled(context: Context, shortcut: Shortcut?, systemId: String?): Boolean {
        val stored = shortcut?.getExtra(FrameGenOptions.KEY_ENABLED).orEmpty()
        if (stored.isNotEmpty()) return stored == "1"
        return systemId != null && RetroDefaults.frameGen(context, systemId)
    }

    fun multiplier(context: Context, shortcut: Shortcut?, systemId: String?): Int {
        val stored = shortcut?.getExtra(FrameGenOptions.KEY_MULTIPLIER)?.toIntOrNull()
        val fallback = systemId?.let { RetroDefaults.frameGenMultiplier(context, it) }
        return FrameGenOptions.clampMultiplier(
            stored ?: fallback ?: FrameGenOptions.DEFAULT_MULTIPLIER,
        )
    }

    fun targetRate(context: Context, shortcut: Shortcut?, systemId: String?): Int {
        val stored = shortcut?.getExtra(FrameGenOptions.KEY_TARGET_RATE)?.toIntOrNull()
        val fallback = systemId?.let { RetroDefaults.frameGenTargetRate(context, it) }
        return (stored ?: fallback ?: 0).coerceAtLeast(0)
    }

    fun flowScale(context: Context, shortcut: Shortcut?, systemId: String?): Int {
        val stored = shortcut?.getExtra(FrameGenOptions.KEY_FLOW_SCALE)?.toIntOrNull()
        val fallback = systemId?.let { RetroDefaults.frameGenFlowScale(context, it) }
        return FrameGenOptions.clampFlowScale(
            stored ?: fallback ?: FrameGenOptions.DEFAULT_FLOW_SCALE,
        )
    }

    fun optionsFor(context: Context, shortcut: Shortcut?, systemId: String?): FrameGenOptions {
        if (!enabled(context, shortcut, systemId)) return FrameGenOptions()
        if (!supported(context)) return FrameGenOptions()

        if (!shadersReady(context)) LosslessAutoImport.sync(context)
        val cache = LosslessScaling.resolveCacheFile(context, true) ?: return FrameGenOptions()

        return FrameGenOptions(
            enabled = true,
            multiplier = multiplier(context, shortcut, systemId),
            targetRate = targetRate(context, shortcut, systemId),
            flowScale = flowScale(context, shortcut, systemId),
            cachePath = cache.absolutePath,
            driverName = SYSTEM_DRIVER,
            sourceRate = CONSOLE_RATE,
        )
    }

    fun liveOptions(
        context: Context,
        multiplier: Int,
        targetRate: Int,
        flowScale: Int,
    ): FrameGenOptions {
        if (!supported(context)) return FrameGenOptions()
        if (!shadersReady(context)) LosslessAutoImport.sync(context)
        val cache = LosslessScaling.resolveCacheFile(context, true) ?: return FrameGenOptions()
        return FrameGenOptions(
            enabled = true,
            multiplier = FrameGenOptions.clampMultiplier(multiplier),
            targetRate = targetRate.coerceAtLeast(0),
            flowScale = FrameGenOptions.clampFlowScale(flowScale),
            cachePath = cache.absolutePath,
            driverName = SYSTEM_DRIVER,
            sourceRate = CONSOLE_RATE,
        )
    }

    fun writeInto(context: Context, intent: Intent, shortcut: Shortcut?, systemId: String?): Intent =
        optionsFor(context, shortcut, systemId).writeTo(intent)
}
