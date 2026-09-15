package com.winlator.cmod.shared.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenSizesTest {
    @Test
    fun parseAcceptsPlainResolution() {
        assertArrayEquals(intArrayOf(1920, 1080), ScreenSizes.parse("1920x1080"))
    }

    @Test
    fun parseRejectsNonResolutionText() {
        assertNull(ScreenSizes.parse("Користувацький"))
        assertNull(ScreenSizes.parse("custom"))
        assertNull(ScreenSizes.parse("x"))
        assertNull(ScreenSizes.parse("1920x"))
        assertNull(ScreenSizes.parse(""))
        assertNull(ScreenSizes.parse(null))
    }

    @Test
    fun parseRejectsOutOfRangeDimensions() {
        assertNull(ScreenSizes.parse("0x1080"))
        assertNull(ScreenSizes.parse("-1920x1080"))
        assertNull(ScreenSizes.parse("99999999999x1080"))
        assertNull(ScreenSizes.parse("40000x1080"))
    }

    @Test
    fun formatRoundsDownToEvenDimensions() {
        assertEquals("1280x720", ScreenSizes.format(1281, 721))
    }

    @Test
    fun formatClampsToASizeTheXServerAccepts() {
        assertEquals("32766x32766", ScreenSizes.format(999999, 999999))
        assertEquals("2x2", ScreenSizes.format(1, 1))
    }

    @Test
    fun sanitizeFallsBackForUnusableValues() {
        assertEquals("1280x720", ScreenSizes.sanitize("Користувацький", "1280x720"))
        assertEquals("1280x720", ScreenSizes.sanitize(null, "1280x720"))
    }

    @Test
    fun sanitizeKeepsUsableValuesVerbatim() {
        assertEquals("1281x721", ScreenSizes.sanitize("1281x721", "1280x720"))
        assertEquals("1920x1080", ScreenSizes.sanitize("1920x1080", "1280x720"))
    }
}
