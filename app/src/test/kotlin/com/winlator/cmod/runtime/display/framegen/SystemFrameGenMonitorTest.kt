package com.winlator.cmod.runtime.display.framegen

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemFrameGenMonitorTest {
    private var presented = 0L
    private var nanos = 0L
    private var hz = 0f

    private fun monitor() = SystemFrameGenMonitor({ presented }, { hz }, { nanos })

    private fun advance(
        frames: Long,
        seconds: Double,
    ) {
        presented += frames
        nanos += (seconds * 1_000_000_000.0).toLong()
    }

    @Test
    fun aDoubledOutputCountsOneGeneratedFramePerRenderedFrame() {
        hz = 120f
        val monitor = monitor()
        monitor.start(2)

        advance(frames = 60, seconds = 1.0)

        assertEquals(120L, monitor.getPresentedFrameCount())
        assertEquals(60L, monitor.getGeneratedFrameCount())
    }

    @Test
    fun nothingIsCountedBeforeTheGameAdvances() {
        val monitor = monitor()
        monitor.start(2)

        assertEquals(0L, monitor.getPresentedFrameCount())
        assertEquals(0L, monitor.getGeneratedFrameCount())
    }

    @Test
    fun aTripleRateCountsTwoGeneratedFramesPerRenderedFrame() {
        hz = 180f
        val monitor = monitor()
        monitor.start(3)

        advance(frames = 40, seconds = 1.0)

        assertEquals(120L, monitor.getPresentedFrameCount())
        assertEquals(80L, monitor.getGeneratedFrameCount())
    }

    @Test
    fun changingTheRateOnlyAffectsFramesAfterTheChange() {
        hz = 240f
        val monitor = monitor()
        monitor.start(2)

        advance(frames = 60, seconds = 1.0)
        assertEquals(120L, monitor.getPresentedFrameCount())

        monitor.setMultiplier(3)
        advance(frames = 60, seconds = 1.0)

        assertEquals(120L + 180L, monitor.getPresentedFrameCount())
        assertEquals(60L + 120L, monitor.getGeneratedFrameCount())
    }

    @Test
    fun aStoppedMonitorStopsCounting() {
        hz = 120f
        val monitor = monitor()
        monitor.start(2)

        advance(frames = 60, seconds = 1.0)
        assertEquals(120L, monitor.getPresentedFrameCount())

        monitor.stop()
        advance(frames = 600, seconds = 10.0)

        assertEquals(120L, monitor.getPresentedFrameCount())
    }

    @Test
    fun aRendererRestartDoesNotRewindTheCounters() {
        hz = 120f
        presented = 1000L
        val monitor = monitor()
        monitor.start(2)

        advance(frames = 60, seconds = 1.0)
        assertEquals(120L, monitor.getPresentedFrameCount())

        presented = 0L
        nanos += 1_000_000_000L
        assertEquals(120L, monitor.getPresentedFrameCount())

        advance(frames = 30, seconds = 0.5)
        assertEquals(120L + 60L, monitor.getPresentedFrameCount())
    }

    @Test
    fun aMultiplierOfOneGeneratesNothing() {
        hz = 120f
        val monitor = monitor()
        monitor.start(1)

        advance(frames = 60, seconds = 1.0)

        assertEquals(60L, monitor.getPresentedFrameCount())
        assertEquals(0L, monitor.getGeneratedFrameCount())
    }

    @Test
    fun theOutputNeverExceedsWhatThePanelCanScanOut() {
        hz = 120f
        val monitor = monitor()
        monitor.start(2)

        advance(frames = 120, seconds = 1.0)

        assertEquals(121L, monitor.getPresentedFrameCount())
        assertEquals(1L, monitor.getGeneratedFrameCount())
    }

    @Test
    fun anUnknownPanelRateLeavesTheCountUnclamped() {
        hz = 0f
        val monitor = monitor()
        monitor.start(2)

        advance(frames = 120, seconds = 1.0)

        assertEquals(240L, monitor.getPresentedFrameCount())
    }
}
