package com.winlator.cmod.runtime.display.ui

import android.os.SystemClock
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.cmod.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.HashMap

@RunWith(AndroidJUnit4::class)
class SystemFrameGenHudTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class FakeOutput(
        private val scanoutFps: Int,
        private val presentedFps: Int,
    ) : FrameRating.OutputFrameSource {
        private val startMs = SystemClock.elapsedRealtime()

        private fun elapsedSeconds(): Double = (SystemClock.elapsedRealtime() - startMs) / 1000.0

        override fun getPresentedFrameCount(): Long = (elapsedSeconds() * scanoutFps).toLong()

        override fun getGeneratedFrameCount(): Long =
            (elapsedSeconds() * (scanoutFps - presentedFps)).toLong()
    }

    private lateinit var hud: FrameRating

    private fun hostHud() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        FrameRating(context, HashMap<Any, Any>()).also {
                            hud = it
                            it.toggleElement(0, true)
                            it.setHudMirrorActive(true)
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun fpsText(): String {
        var text = ""
        composeRule.runOnUiThread {
            text = hud.findViewById<TextView>(R.id.TVFpsBig)?.text?.toString().orEmpty()
        }
        return text
    }

    private fun feedFrames(
        fps: Int,
        millis: Long,
    ) {
        val periodMs = 1000L / fps
        val deadline = SystemClock.elapsedRealtime() + millis
        while (SystemClock.elapsedRealtime() < deadline) {
            composeRule.runOnUiThread { hud.recordGameFrame() }
            SystemClock.sleep(periodMs)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun theTopHudShowsTheSystemOutputRateBesideTheGameRate() {
        hostHud()
        composeRule.runOnUiThread {
            hud.setOutputFrameSource(FakeOutput(scanoutFps = 144, presentedFps = 72))
            hud.setFrameGenerationActive(true)
        }

        feedFrames(fps = 72, millis = 2500L)

        val text = fpsText()
        assertTrue("expected an arrow in \"$text\"", text.contains("→"))
        assertTrue("expected the scan-out rate in \"$text\"", text.substringAfter("→").trim().toInt() in 130..150)
    }

    @Test
    fun theArrowIsNotShownWhileNothingIsBeingGenerated() {
        hostHud()
        composeRule.runOnUiThread {
            hud.setOutputFrameSource(FakeOutput(scanoutFps = 72, presentedFps = 72))
            hud.setFrameGenerationActive(true)
        }

        feedFrames(fps = 72, millis = 2500L)

        val text = fpsText()
        assertFalse("expected no arrow in \"$text\"", text.contains("→"))
    }

    @Test
    fun theArrowIsNotShownWhenNoFrameGeneratorIsRunning() {
        hostHud()
        composeRule.runOnUiThread {
            hud.setOutputFrameSource(null)
            hud.setFrameGenerationActive(false)
        }

        feedFrames(fps = 72, millis = 2000L)

        val text = fpsText()
        assertFalse("expected no arrow in \"$text\"", text.contains("→"))
    }
}
