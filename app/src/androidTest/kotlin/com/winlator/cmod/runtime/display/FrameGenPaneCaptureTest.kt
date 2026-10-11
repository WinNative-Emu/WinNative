package com.winlator.cmod.runtime.display

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.shared.framegen.FrameGenEngine
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrameGenPaneCaptureTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(
        name: String,
        engine: FrameGenEngine,
        lsfgAvailable: Boolean = true,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalPaneScale provides 1f) {
                Box(
                    Modifier
                        .width(300.dp)
                        .background(Color(0xFF11111C))
                        .padding(12.dp),
                ) {
                    androidx.compose.foundation.layout.Column(
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                    ) {
                        FrameGenEngineSection(
                            engine = engine,
                            lsfgAvailable = lsfgAvailable,
                            targetRate = 0,
                            multiplier = 2,
                            flowScale = 70,
                            disScale = 180,
                            disTargetFps = 0,
                            disDebugFlow = false,
                            maxRefreshRate = 120,
                            paneScale = 1f,
                            onEngineSelected = {},
                            onTargetRateSelected = {},
                            onMultiplierSelected = {},
                            onFlowScaleChanged = {},
                            onDisScaleChanged = {},
                            onDisTargetFpsSelected = {},
                            onDisDebugFlowChanged = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        val out = File(dir, "$name.png")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun captureOff() = capture("framegen-off", FrameGenEngine.OFF)

    @Test
    fun captureLsfg() = capture("framegen-lsfg", FrameGenEngine.LSFG)

    @Test
    fun captureDis() = capture("framegen-dis", FrameGenEngine.DIS)

    @Test
    fun captureOffNoLossless() = capture("framegen-off-no-lossless", FrameGenEngine.OFF, lsfgAvailable = false)
}
