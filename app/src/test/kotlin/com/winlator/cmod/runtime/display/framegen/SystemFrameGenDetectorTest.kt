package com.winlator.cmod.runtime.display.framegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemFrameGenDetectorTest {
    private fun noFallback(): Map<String, String> = emptyMap()

    private val redMagicPropsOff =
        linkedMapOf(
            "ro.vendor.feature.zte_feature_gfrc" to "true",
            "vendor.gpp.create_frc_extension" to "1",
            "vendor.gpp.dynamic.settings.enable" to "1",
            "vendor.gpp.frc.enable" to "0x21",
            "vendor.gpp.gfrc.interp.rate" to "0",
            "vendor.gpp.gfrc.upscale.ratio" to "0",
        )

    private val redMagicPropsOn =
        LinkedHashMap(redMagicPropsOff).apply {
            put("vendor.gpp.frc.enable", "0x22")
            put("vendor.gpp.gfrc.interp.rate", "1")
        }

    @Test
    fun theInterpolationRateDrivesTheStateOnARedMagic() {
        val off = SystemFrameGenDetector.evaluate("0", "0x21", capable = true, fallback = ::noFallback)
        assertTrue(off.vendorSupported)
        assertFalse(off.active)
        assertEquals(1, off.multiplier)
        assertEquals("vendor.gpp.gfrc.interp.rate=0", off.signal)

        val on = SystemFrameGenDetector.evaluate("1", "0x22", capable = true, fallback = ::noFallback)
        assertTrue(on.active)
        assertEquals(2, on.multiplier)
        assertEquals("vendor.gpp.gfrc.interp.rate=1", on.signal)
    }

    @Test
    fun aZeroRateDefersToTheEnableNibble() {
        val state = SystemFrameGenDetector.evaluate("0", "0x22", capable = true, fallback = ::noFallback)
        assertTrue(state.active)
        assertEquals(2, state.multiplier)
        assertEquals("vendor.gpp.frc.enable=0x22", state.signal)
    }

    @Test
    fun bothKeysOffStayOff() {
        val state = SystemFrameGenDetector.evaluate("0", "0x21", capable = true, fallback = ::noFallback)
        assertFalse(state.active)
        assertEquals(1, state.multiplier)
    }

    @Test
    fun twoInsertedFramesReportATripleRate() {
        val state = SystemFrameGenDetector.evaluate("2", "0x23", capable = true, fallback = ::noFallback)
        assertTrue(state.active)
        assertEquals(3, state.multiplier)
    }

    @Test
    fun theHexEnableIsUsedWhenTheRateIsMissing() {
        val off = SystemFrameGenDetector.evaluate(null, "0x21", capable = true, fallback = ::noFallback)
        assertFalse(off.active)
        assertEquals(1, off.multiplier)

        val on = SystemFrameGenDetector.evaluate(null, "0x22", capable = true, fallback = ::noFallback)
        assertTrue(on.active)
        assertEquals(2, on.multiplier)
        assertEquals("vendor.gpp.frc.enable=0x22", on.signal)
    }

    @Test
    fun theCapabilityFlagAloneIsNeverReadAsRunning() {
        val state = SystemFrameGenDetector.scan(redMagicPropsOff, capable = true)

        assertTrue(state.vendorSupported)
        assertFalse(state.active)
        assertEquals(1, state.multiplier)
        assertFalse(state.signal.contains("zte_feature_gfrc"))
        assertFalse(state.signal.contains("create_frc_extension"))
    }

    @Test
    fun theScanIgnoresTheKnownKeysBecauseTheFastPathOwnsThem() {
        val state = SystemFrameGenDetector.scan(redMagicPropsOn, capable = true)

        assertFalse(state.active)
        assertEquals("", state.signal)
        assertTrue(state.vendorSupported)
    }

    @Test
    fun theScanCoversFirmwareThatUsesADifferentKey() {
        val state =
            SystemFrameGenDetector.scan(
                linkedMapOf(
                    "ro.build.version.sdk" to "36",
                    "persist.sys.nubia.game.frame_interp.rate" to "2",
                ),
                capable = false,
            )

        assertTrue(state.active)
        assertEquals("persist.sys.nubia.game.frame_interp.rate=2", state.signal)
        assertEquals(3, state.multiplier)
    }

    @Test
    fun theScanPrefersARateKeyOverAPlainSwitch() {
        val state =
            SystemFrameGenDetector.scan(
                linkedMapOf(
                    "persist.sys.nubia.game.frc" to "1",
                    "persist.sys.nubia.game.frc.interp.rate" to "2",
                ),
                capable = false,
            )

        assertEquals("persist.sys.nubia.game.frc.interp.rate=2", state.signal)
        assertEquals(3, state.multiplier)
    }

    @Test
    fun readOnlyAndCapabilityKeysAreNotStateKeys() {
        assertFalse(SystemFrameGenDetector.isStateKey("ro.vendor.feature.zte_feature_gfrc"))
        assertFalse(SystemFrameGenDetector.isStateKey("vendor.gpp.create_frc_extension"))
        assertFalse(SystemFrameGenDetector.isStateKey("ro.vendor.nubia.frc.support"))
        assertFalse(SystemFrameGenDetector.isStateKey("persist.sys.nubia.frc.whitelist"))
    }

    @Test
    fun theKnownKeysAreLeftToTheFastPath() {
        assertFalse(SystemFrameGenDetector.isStateKey("vendor.gpp.gfrc.interp.rate"))
        assertFalse(SystemFrameGenDetector.isStateKey("vendor.gpp.frc.enable"))
    }

    @Test
    fun otherVendorStateKeysAreRecognised() {
        assertTrue(SystemFrameGenDetector.isStateKey("persist.sys.nubia.game.frc"))
        assertTrue(SystemFrameGenDetector.isStateKey("sys.zte.display.frame_interp"))
    }

    @Test
    fun theUpscalerIsNotMistakenForFrameGeneration() {
        assertFalse(SystemFrameGenDetector.isStateKey("vendor.gpp.gfrc.upscale.ratio"))
    }

    @Test
    fun unrelatedKeysAreIgnored() {
        assertFalse(SystemFrameGenDetector.isStateKey("ro.build.version.sdk"))
        assertFalse(SystemFrameGenDetector.isStateKey("debug.hwui.renderer"))
        assertFalse(SystemFrameGenDetector.isStateKey("persist.sys.nubia.game.mode"))
        assertFalse(SystemFrameGenDetector.isStateKey("persist.some.random.frc"))
    }

    @Test
    fun hexAndDecimalValuesBothParse() {
        assertEquals(0x22, SystemFrameGenDetector.parseNumber("0x22"))
        assertEquals(2, SystemFrameGenDetector.parseNumber("2"))
        assertEquals(null, SystemFrameGenDetector.parseNumber("true"))
        assertEquals(null, SystemFrameGenDetector.parseNumber(""))
        assertEquals(null, SystemFrameGenDetector.parseNumber(null))
    }

    @Test
    fun aMissingVendorNamespaceFallsBackWithoutClaimingItIsRunning() {
        val state =
            SystemFrameGenDetector.evaluate(
                interpRate = null,
                frcEnable = null,
                capable = false,
                fallback = { linkedMapOf("ro.build.version.sdk" to "36") },
            )

        assertFalse(state.vendorSupported)
        assertFalse(state.active)
        assertEquals(1, state.multiplier)
    }

    @Test
    fun anAbsurdRateIsClamped() {
        val state = SystemFrameGenDetector.evaluate("99", null, capable = true, fallback = ::noFallback)
        assertEquals(8, state.multiplier)
    }
}
