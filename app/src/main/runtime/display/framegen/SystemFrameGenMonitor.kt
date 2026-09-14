package com.winlator.cmod.runtime.display.framegen

import com.winlator.cmod.runtime.display.ui.FrameRating
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class SystemFrameGenMonitor(
    private val presentedFrames: () -> Long,
    private val refreshHz: () -> Float = { 0f },
    private val elapsedNanos: () -> Long = { System.nanoTime() },
) : FrameRating.OutputFrameSource {
    private val outputFrames = AtomicLong()
    private val generatedFrames = AtomicLong()

    @Volatile
    private var running = false

    @Volatile
    private var multiplier = 1

    private var lastPresented = 0L
    private var lastNanos = 0L

    @Synchronized
    fun start(multiplier: Int) {
        this.multiplier = multiplier.coerceAtLeast(1)
        if (running) return
        running = true
        outputFrames.set(0L)
        generatedFrames.set(0L)
        lastPresented = presentedFrames()
        lastNanos = elapsedNanos()
    }

    @Synchronized
    fun stop() {
        running = false
    }

    @Synchronized
    fun setMultiplier(multiplier: Int) {
        accumulate()
        this.multiplier = multiplier.coerceAtLeast(1)
    }

    fun isRunning(): Boolean = running

    fun multiplier(): Int = multiplier

    override fun getPresentedFrameCount(): Long {
        accumulate()
        return outputFrames.get()
    }

    override fun getGeneratedFrameCount(): Long {
        accumulate()
        return generatedFrames.get()
    }

    @Synchronized
    private fun accumulate() {
        if (!running) return
        val now = presentedFrames()
        val nowNanos = elapsedNanos()
        val delta = now - lastPresented
        val elapsed = nowNanos - lastNanos
        if (delta <= 0L) {
            if (delta < 0L) {
                lastPresented = now
                lastNanos = nowNanos
            }
            return
        }
        lastPresented = now
        lastNanos = nowNanos

        val factor = multiplier
        val requested = delta * factor
        val output = min(requested, scanoutCeiling(elapsed, requested)).coerceAtLeast(delta)
        val generated = (output - delta).coerceAtLeast(0L)
        outputFrames.addAndGet(output)
        generatedFrames.addAndGet(generated)
    }

    private fun scanoutCeiling(
        elapsedNanos: Long,
        requested: Long,
    ): Long {
        val hz = refreshHz()
        if (hz <= 0f || elapsedNanos <= 0L) return requested
        val ceiling = (elapsedNanos / 1_000_000_000.0 * hz).toLong() + 1L
        return ceiling.coerceAtLeast(1L)
    }
}
