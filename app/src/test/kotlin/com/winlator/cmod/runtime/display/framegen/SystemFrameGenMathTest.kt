package com.winlator.cmod.runtime.display.framegen

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemFrameGenMathTest {
    private val maxCatchup = 16L

    private fun nanos(ms: Double): Long = (ms * 1_000_000.0).toLong()

    @Test
    fun theFirstCallbackCountsAsOneVsync() {
        assertEquals(1L, SystemFrameGenMath.vsyncSteps(0L, nanos(8.0), 120f, maxCatchup))
    }

    @Test
    fun oneVsyncApartCountsOnce() {
        val period = nanos(1000.0 / 144.0)
        assertEquals(1L, SystemFrameGenMath.vsyncSteps(0L + 1L, 1L + period, 144f, maxCatchup))
    }

    @Test
    fun aCoalescedCallbackRecoversTheVsyncsItMissed() {
        val period = nanos(1000.0 / 144.0)
        assertEquals(4L, SystemFrameGenMath.vsyncSteps(1L, 1L + period * 4, 144f, maxCatchup))
    }

    @Test
    fun aLongStallIsCappedSoTheCounterCannotJump() {
        val period = nanos(1000.0 / 144.0)
        assertEquals(maxCatchup, SystemFrameGenMath.vsyncSteps(1L, 1L + period * 400, 144f, maxCatchup))
    }

    @Test
    fun anUnknownRefreshRateFallsBackToASingleStep() {
        assertEquals(1L, SystemFrameGenMath.vsyncSteps(1L, nanos(100.0), 0f, maxCatchup))
    }

    @Test
    fun aNonAdvancingTimestampNeverCountsZeroOrLess() {
        assertEquals(1L, SystemFrameGenMath.vsyncSteps(nanos(50.0), nanos(50.0), 120f, maxCatchup))
        assertEquals(1L, SystemFrameGenMath.vsyncSteps(nanos(50.0), nanos(40.0), 120f, maxCatchup))
    }

    @Test
    fun sixtyHzOnA120HzPanelCountsTwoScanoutsPerPresent() {
        val period = nanos(1000.0 / 120.0)
        var previous = 1L
        var scanout = 0L
        repeat(60) {
            val now = previous + period * 2
            scanout += SystemFrameGenMath.vsyncSteps(previous, now, 120f, maxCatchup)
            previous = now
        }
        assertEquals(120L, scanout)
    }
}
