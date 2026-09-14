package com.winlator.cmod.runtime.display.framegen

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemFrameGenDeviceTest {
    @Test
    fun theVendorSignalIsReadableOnThisDevice() {
        assumeTrue(SystemFrameGenDetector.isVendorDevice())

        SystemFrameGenDetector.invalidate()
        val state = SystemFrameGenDetector.detect(maxAgeMs = 0L)
        Log.i("SystemFrameGenDeviceTest", "state=$state")

        assertTrue("expected a vendor signal, got $state", state.vendorSupported)
        assertTrue(
            "expected a gpp signal, got \"${state.signal}\"",
            state.signal.startsWith(SystemFrameGenDetector.KEY_INTERP_RATE) ||
                state.signal.startsWith(SystemFrameGenDetector.KEY_FRC_ENABLE),
        )
        assertTrue("multiplier should be at least 1, got $state", state.multiplier >= 1)
        assertEquals(state.active, state.multiplier > 1)
    }

    @Test
    fun repeatedProbesAgreeWithThemselves() {
        assumeTrue(SystemFrameGenDetector.isVendorDevice())

        SystemFrameGenDetector.invalidate()
        val first = SystemFrameGenDetector.detect(maxAgeMs = 0L)
        SystemFrameGenDetector.invalidate()
        val second = SystemFrameGenDetector.detect(maxAgeMs = 0L)

        assertEquals(first.signal, second.signal)
        assertEquals(first.active, second.active)
        assertEquals(first.multiplier, second.multiplier)
    }
}
