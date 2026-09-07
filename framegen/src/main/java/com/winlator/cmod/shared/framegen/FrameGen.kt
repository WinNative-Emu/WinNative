package com.winlator.cmod.shared.framegen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager

object FrameGen {
    private const val TAG = "WnFrameGen"

    private var appContext: Context? = null
    private var options = FrameGenOptions()
    private var handle = 0L
    private var producer: Surface? = null
    private var boundOutput: Surface? = null
    private var boundWidth = 0
    private var boundHeight = 0
    private var refreshRate = 0f

    val requested: Boolean
        @Synchronized get() = options.usable

    val active: Boolean
        @Synchronized get() = handle != 0L

    @JvmStatic
    @Synchronized
    fun install(context: Context, source: FrameGenOptions) {
        appContext = context.applicationContext
        if (options != source) release()
        options = source
        if (options.usable) FrameGenNative.ensureLoaded()
    }

    @JvmStatic
    fun installFromIntent(context: Context, intent: Intent?) {
        install(context, FrameGenOptions.fromIntent(intent))
    }

    @JvmStatic
    @Synchronized
    fun wrap(output: Surface?, width: Int, height: Int): Surface? {
        if (output == null || !output.isValid) return output
        if (!options.usable || width <= 0 || height <= 0) return output
        if (handle != 0L && boundOutput === output && boundWidth == width && boundHeight == height) {
            return producer ?: output
        }

        releaseLocked()

        val context = appContext ?: return output
        if (!FrameGenNative.ensureLoaded()) {
            Log.w(TAG, "native library unavailable; frame generation stays off")
            return output
        }

        val created =
            runCatching {
                FrameGenNative.nativeCreate(
                    context,
                    output,
                    width,
                    height,
                    options.cachePath,
                    options.driverName,
                    options.multiplier,
                    options.targetRate,
                    options.flowScale,
                    refreshRate,
                )
            }.getOrElse {
                Log.w(TAG, "frame generation could not start: ${it.message}")
                0L
            }
        if (created == 0L) return output

        val surface = runCatching { FrameGenNative.nativeProducerSurface(created) }.getOrNull()
        if (surface == null || !surface.isValid) {
            FrameGenNative.nativeDestroy(created)
            return output
        }

        handle = created
        producer = surface
        boundOutput = output
        boundWidth = width
        boundHeight = height
        Log.i(TAG, "frame generation active ${width}x$height multiplier=${options.multiplier} " +
            "target=${options.targetRate} flow=${options.flowScale} refresh=$refreshRate")
        return surface
    }

    @JvmStatic
    @Synchronized
    fun realFrames(): Long =
        if (handle == 0L) 0L else runCatching { FrameGenNative.nativeRealFrames(handle) }.getOrDefault(0L)

    @JvmStatic
    @Synchronized
    fun generatedFrames(): Long =
        if (handle == 0L) 0L
        else runCatching { FrameGenNative.nativeGeneratedFrames(handle) }.getOrDefault(0L)

    @JvmStatic
    @Synchronized
    fun release() = releaseLocked()

    private fun releaseLocked() {
        if (handle != 0L) {
            runCatching { FrameGenNative.nativeDestroy(handle) }
            handle = 0L
        }
        producer?.let { runCatching { it.release() } }
        producer = null
        boundOutput = null
        boundWidth = 0
        boundHeight = 0
    }

    @JvmStatic
    fun applyDisplayMode(activity: Activity?): Float {
        val window = activity?.window ?: return 0f
        val params = window.attributes
        if (!requested) {
            if (params.preferredDisplayModeId != 0) {
                params.preferredDisplayModeId = 0
                window.attributes = params
            }
            synchronized(this) { refreshRate = 0f }
            return 0f
        }

        val display = displayOf(activity) ?: return 0f
        val active = display.mode
        val source = if (options.sourceRate > 0) options.sourceRate else 60
        val wanted =
            when {
                options.targetRate > 0 -> options.targetRate
                else -> options.multiplier * source
            }

        var best: Display.Mode? = null
        for (mode in display.supportedModes) {
            if (mode.physicalWidth != active.physicalWidth ||
                mode.physicalHeight != active.physicalHeight
            ) {
                continue
            }
            if (best == null || betterMode(mode, best!!, wanted)) best = mode
        }
        val selected = best ?: active
        if (selected.modeId != params.preferredDisplayModeId || params.preferredRefreshRate != 0f) {
            params.preferredDisplayModeId = selected.modeId
            params.preferredRefreshRate = 0f
            window.attributes = params
        }

        val rate = selected.refreshRate
        synchronized(this) {
            refreshRate = rate
            if (handle != 0L) {
                runCatching {
                    FrameGenNative.nativeConfigure(
                        handle,
                        options.multiplier,
                        options.targetRate,
                        options.flowScale,
                        rate,
                    )
                }
            }
        }
        Log.i(TAG, "frame generation display mode: wanted ${wanted}Hz, selected ${Math.round(rate)}Hz")
        return rate
    }

    private fun betterMode(candidate: Display.Mode, current: Display.Mode, wanted: Int): Boolean {
        val a = candidate.refreshRate
        val b = current.refreshRate
        val aMeets = a + 0.5f >= wanted
        val bMeets = b + 0.5f >= wanted
        if (aMeets != bMeets) return aMeets
        if (!aMeets) return a > b
        return a < b
    }

    private fun displayOf(activity: Activity): Display? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.let { return it }
        }
        val manager = activity.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        return manager?.defaultDisplay
    }
}
