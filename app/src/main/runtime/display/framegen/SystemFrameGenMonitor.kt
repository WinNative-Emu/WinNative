package com.winlator.cmod.runtime.display.framegen

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import com.winlator.cmod.runtime.display.ui.FrameRating
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class SystemFrameGenMonitor(
    private val display: () -> Display?,
    private val presentedFrames: () -> Long,
) : FrameRating.OutputFrameSource {
    private companion object {
        const val THREAD_NAME = "SystemFrameGenVsync"
        const val REFRESH_CACHE_MS = 500L
        const val MAX_CATCHUP_VSYNCS = 16L
    }

    private val scanoutFrames = AtomicLong()
    private val generatedFrames = AtomicLong()

    @Volatile
    private var running = false

    @Volatile
    private var thread: HandlerThread? = null

    @Volatile
    private var choreographer: Choreographer? = null

    @Volatile
    private var basePresented = 0L

    private var lastFrameNanos = 0L
    private var cachedRefreshHz = 0f
    private var cachedRefreshAtMs = 0L

    private val frameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                onVsync(frameTimeNanos)
                if (running) choreographer?.postFrameCallback(this)
            }
        }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        scanoutFrames.set(0L)
        generatedFrames.set(0L)
        basePresented = presentedFrames()
        lastFrameNanos = 0L
        cachedRefreshHz = 0f
        cachedRefreshAtMs = 0L
        val worker = HandlerThread(THREAD_NAME)
        worker.start()
        thread = worker
        Handler(worker.looper).post {
            if (!running) return@post
            val instance = Choreographer.getInstance()
            choreographer = instance
            instance.postFrameCallback(frameCallback)
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        choreographer?.removeFrameCallback(frameCallback)
        choreographer = null
        thread?.quitSafely()
        thread = null
    }

    fun isRunning(): Boolean = running

    override fun getPresentedFrameCount(): Long = scanoutFrames.get()

    override fun getGeneratedFrameCount(): Long = generatedFrames.get()

    private fun onVsync(frameTimeNanos: Long) {
        val previous = lastFrameNanos
        lastFrameNanos = frameTimeNanos

        val steps = SystemFrameGenMath.vsyncSteps(previous, frameTimeNanos, refreshHz(), MAX_CATCHUP_VSYNCS)
        val scanout = scanoutFrames.addAndGet(steps)
        val presented = max(0L, presentedFrames() - basePresented)
        val generated = scanout - presented
        if (generated > generatedFrames.get()) generatedFrames.set(generated)
    }

    private fun refreshHz(): Float {
        val now = SystemClock.elapsedRealtime()
        if (cachedRefreshHz > 0f && now - cachedRefreshAtMs < REFRESH_CACHE_MS) return cachedRefreshHz
        val rate = display()?.refreshRate ?: 0f
        if (rate > 0f) {
            cachedRefreshHz = rate
            cachedRefreshAtMs = now
        }
        return cachedRefreshHz
    }
}

internal object SystemFrameGenMath {
    fun vsyncSteps(
        previousNanos: Long,
        frameTimeNanos: Long,
        refreshHz: Float,
        maxCatchup: Long,
    ): Long {
        if (previousNanos <= 0L || refreshHz <= 0f) return 1L
        val elapsed = frameTimeNanos - previousNanos
        if (elapsed <= 0L) return 1L
        val period = 1_000_000_000.0 / refreshHz
        return Math.round(elapsed / period).coerceIn(1L, maxCatchup)
    }
}
