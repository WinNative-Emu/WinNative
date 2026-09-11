package com.winlator.cmod.runtime.display.framegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemFrameGenDetectorTest {
    @Test
    fun vendorFrameGenKeysAreRecognised() {
        assertTrue(SystemFrameGenDetector.isFrameGenKey("persist.sys.nubia.game.frc"))
        assertTrue(SystemFrameGenDetector.isFrameGenKey("sys.nubia.display.frc.enable"))
        assertTrue(SystemFrameGenDetector.isFrameGenKey("persist.vendor.zte.game.frame_insert"))
        assertTrue(SystemFrameGenDetector.isFrameGenKey("nubia_game_frame_interpolation"))
        assertTrue(SystemFrameGenDetector.isFrameGenKey("persist.sys.redmagic.memc"))
    }

    @Test
    fun unrelatedAndCapabilityKeysAreIgnored() {
        assertFalse(SystemFrameGenDetector.isFrameGenKey("ro.build.version.sdk"))
        assertFalse(SystemFrameGenDetector.isFrameGenKey("debug.hwui.renderer"))
        assertFalse(SystemFrameGenDetector.isFrameGenKey("persist.sys.nubia.game.mode"))
        assertFalse(SystemFrameGenDetector.isFrameGenKey("ro.vendor.nubia.frc.support"))
        assertFalse(SystemFrameGenDetector.isFrameGenKey("persist.sys.nubia.frc.whitelist"))
        assertFalse(SystemFrameGenDetector.isFrameGenKey("vendor.debug.frc.version"))
    }

    @Test
    fun aFeatureKeyOutsideAVendorScopeIsIgnored() {
        assertFalse(SystemFrameGenDetector.isFrameGenKey("persist.some.random.frc"))
    }

    @Test
    fun enabledValuesAreReadTheWayVendorsWriteThem() {
        assertTrue(SystemFrameGenDetector.isEnabledValue("1"))
        assertTrue(SystemFrameGenDetector.isEnabledValue("2"))
        assertTrue(SystemFrameGenDetector.isEnabledValue("true"))
        assertTrue(SystemFrameGenDetector.isEnabledValue("ON"))
        assertTrue(SystemFrameGenDetector.isEnabledValue("enabled"))
        assertTrue(SystemFrameGenDetector.isEnabledValue("x2"))

        assertFalse(SystemFrameGenDetector.isEnabledValue("0"))
        assertFalse(SystemFrameGenDetector.isEnabledValue(""))
        assertFalse(SystemFrameGenDetector.isEnabledValue("  "))
        assertFalse(SystemFrameGenDetector.isEnabledValue("false"))
        assertFalse(SystemFrameGenDetector.isEnabledValue("off"))
        assertFalse(SystemFrameGenDetector.isEnabledValue("null"))
    }

    @Test
    fun multipliersAreReadFromNumericAndSuffixedValues() {
        assertEquals(2, SystemFrameGenDetector.parseMultiplier("2"))
        assertEquals(3, SystemFrameGenDetector.parseMultiplier("3"))
        assertEquals(2, SystemFrameGenDetector.parseMultiplier("x2"))
        assertEquals(3, SystemFrameGenDetector.parseMultiplier("3X"))
        assertEquals(0, SystemFrameGenDetector.parseMultiplier("1"))
        assertEquals(0, SystemFrameGenDetector.parseMultiplier("on"))
    }

    @Test
    fun anEnabledSignalIsReported() {
        val state =
            SystemFrameGenDetector.evaluate(
                linkedMapOf(
                    "ro.build.version.sdk" to "36",
                    "persist.sys.nubia.game.frc" to "2",
                ),
            )

        assertTrue(state.vendorSupported)
        assertTrue(state.active)
        assertEquals("persist.sys.nubia.game.frc=2", state.signal)
        assertEquals(2, state.multiplier)
    }

    @Test
    fun anEnabledKeyWinsOverAnEarlierDisabledOne() {
        val state =
            SystemFrameGenDetector.evaluate(
                linkedMapOf(
                    "persist.sys.nubia.game.frc" to "0",
                    "sys.nubia.display.frame_interp" to "1",
                ),
            )

        assertTrue(state.active)
        assertEquals("sys.nubia.display.frame_interp=1", state.signal)
    }

    @Test
    fun aDisabledKeyIsStillReportedSoTheKeyIsKnown() {
        val state =
            SystemFrameGenDetector.evaluate(linkedMapOf("persist.sys.nubia.game.frc" to "0"))

        assertTrue(state.vendorSupported)
        assertFalse(state.active)
        assertEquals("persist.sys.nubia.game.frc=0", state.signal)
        assertEquals(0, state.multiplier)
    }

    @Test
    fun noMatchingKeyLeavesTheStateInactive() {
        val state =
            SystemFrameGenDetector.evaluate(
                linkedMapOf("ro.build.version.sdk" to "36", "debug.hwui.renderer" to "skiagl"),
            )

        assertTrue(state.vendorSupported)
        assertFalse(state.active)
        assertEquals("", state.signal)
    }
}
