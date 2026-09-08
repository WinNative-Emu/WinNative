package com.winlator.cmod.shared.android

import android.app.Activity
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

object DeviceResolutions {
    const val EXTRA_ENABLED = "showDeviceResolutions"
    private const val MAX_RATIO_TERM = 64
    private val TIER_HEIGHTS = intArrayOf(360, 480, 540, 600, 720, 900, 1080, 1200, 1440, 2160)

    @JvmStatic
    fun isEnabled(value: String?): Boolean = value == "1"

    @JvmStatic
    fun extraValue(enabled: Boolean): String = if (enabled) "1" else "0"

    @JvmStatic
    fun panelSize(activity: Activity): Pair<Int, Int>? {
        var width = 0
        var height = 0
        displayOf(activity)?.mode?.let {
            width = it.physicalWidth
            height = it.physicalHeight
        }
        if (width <= 0 || height <= 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = activity.windowManager.currentWindowMetrics.bounds
                width = bounds.width()
                height = bounds.height()
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay.getRealMetrics(metrics)
                width = metrics.widthPixels
                height = metrics.heightPixels
            }
        }
        if (width <= 0 || height <= 0) return null
        return maxOf(width, height) to minOf(width, height)
    }

    @JvmStatic
    fun screenSizeEntries(activity: Activity, customEntry: String): List<String> {
        val (longSide, shortSide) = panelSize(activity) ?: return emptyList()
        val nativeWidth = toEven(longSide)
        val nativeHeight = toEven(shortSide)
        if (nativeWidth <= 0 || nativeHeight <= 0) return emptyList()

        val entries = ArrayList<String>()
        entries.add(customEntry)
        for (tier in TIER_HEIGHTS) {
            if (tier >= nativeHeight) continue
            val width = toEven((tier.toDouble() * longSide / shortSide).roundToInt())
            if (width < tier) continue
            entries.add("${width}x$tier (${tierLabel(tier)})")
        }
        if (nativeWidth < nativeHeight) return emptyList()
        entries.add("${nativeWidth}x$nativeHeight (Native)")
        return entries
    }

    @JvmStatic
    fun panelSummary(activity: Activity): String {
        val (longSide, shortSide) = panelSize(activity) ?: return ""
        return "${longSide}x$shortSide, ${aspectRatioLabel(longSide, shortSide)}"
    }

    private fun displayOf(activity: Activity): Display? {
        activity.window?.decorView?.display?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return activity.display
        @Suppress("DEPRECATION")
        return activity.windowManager.defaultDisplay
    }

    private fun tierLabel(height: Int): String =
        when (height) {
            1440 -> "2K"
            2160 -> "4K"
            else -> "${height}p"
        }

    private fun toEven(value: Int): Int = (value / 2) * 2

    private fun aspectRatioLabel(longSide: Int, shortSide: Int): String {
        val divisor = gcd(longSide, shortSide)
        val terms = longSide / divisor to shortSide / divisor
        if (terms.first <= MAX_RATIO_TERM && terms.second <= MAX_RATIO_TERM) {
            return "${terms.first}:${terms.second}"
        }
        val ratio = longSide.toDouble() / shortSide
        val rounded = (ratio * 100).roundToInt() / 100.0
        return if (abs(rounded - rounded.toInt()) < 0.005) {
            "${rounded.toInt()}:1"
        } else {
            "${rounded}:1"
        }
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = y
            y = x % y
            x = t
        }
        return if (x == 0) 1 else x
    }
}
