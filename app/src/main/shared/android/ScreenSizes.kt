package com.winlator.cmod.shared.android

object ScreenSizes {
    private const val MIN_DIMENSION = 2
    private const val MAX_DIMENSION = 32767

    @JvmStatic
    fun parse(value: String?): IntArray? {
        if (value.isNullOrEmpty()) return null
        val parts = value.split("x")
        if (parts.size != 2) return null
        val width = parts[0].trim().toIntOrNull() ?: return null
        val height = parts[1].trim().toIntOrNull() ?: return null
        if (width !in MIN_DIMENSION..MAX_DIMENSION) return null
        if (height !in MIN_DIMENSION..MAX_DIMENSION) return null
        return intArrayOf(width, height)
    }

    @JvmStatic
    fun format(width: Int, height: Int): String = "${toEven(width)}x${toEven(height)}"

    @JvmStatic
    fun sanitize(value: String?, fallback: String): String {
        val parsed = parse(value) ?: return fallback
        return "${parsed[0]}x${parsed[1]}"
    }

    private fun toEven(value: Int): Int =
        (value.coerceIn(MIN_DIMENSION, MAX_DIMENSION) / 2) * 2
}
