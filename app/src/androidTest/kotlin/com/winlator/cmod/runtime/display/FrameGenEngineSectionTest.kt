package com.winlator.cmod.runtime.display

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.cmod.shared.framegen.FrameGenEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrameGenEngineSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var selected: FrameGenEngine? = null

    private fun setSection(
        engine: FrameGenEngine,
        lsfgAvailable: Boolean = true,
    ) {
        selected = null
        composeRule.setContent {
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
                onEngineSelected = { selected = it },
                onTargetRateSelected = {},
                onMultiplierSelected = {},
                onFlowScaleChanged = {},
                onDisScaleChanged = {},
                onDisTargetFpsSelected = {},
                onDisDebugFlowChanged = {},
            )
        }
        composeRule.waitForIdle()
    }

    private fun assertGone(text: String) {
        composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun offHidesBothEngineOptionSets() {
        setSection(FrameGenEngine.OFF)

        composeRule.onNodeWithText("Off").assertIsDisplayed()
        composeRule.onNodeWithText("LSFG").assertIsDisplayed()
        composeRule.onNodeWithText("DIS").assertIsDisplayed()
        assertGone("Adaptive Target")
        assertGone("Resolution Scale")
    }

    @Test
    fun lsfgShowsOnlyTheLosslessOptions() {
        setSection(FrameGenEngine.LSFG)

        composeRule.onNodeWithText("Adaptive Target", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Multiplier", useUnmergedTree = true).assertIsDisplayed()
        assertGone("Resolution Scale")
        assertGone("Show flow")
    }

    @Test
    fun disShowsOnlyTheDisOptions() {
        setSection(FrameGenEngine.DIS)

        composeRule.onNodeWithText("Resolution Scale", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Target FPS", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Show flow", useUnmergedTree = true).assertIsDisplayed()
        assertGone("Adaptive Target")
    }

    @Test
    fun tappingASegmentReportsThatEngine() {
        setSection(FrameGenEngine.OFF)

        composeRule.onNodeWithText("DIS").performClick()
        composeRule.waitForIdle()

        assertEquals(FrameGenEngine.DIS, selected)
    }

    @Test
    fun switchingStraightFromLsfgToDisIsOneStep() {
        setSection(FrameGenEngine.LSFG)

        composeRule.onNodeWithText("DIS").performClick()
        composeRule.waitForIdle()

        assertEquals(FrameGenEngine.DIS, selected)
    }

    @Test
    fun lsfgIsNotSelectableWithoutLosslessScaling() {
        setSection(FrameGenEngine.OFF, lsfgAvailable = false)

        composeRule.onNodeWithText("LSFG").performClick()
        composeRule.waitForIdle()

        assertNull(selected)
        composeRule
            .onNodeWithText("Install Lossless Scaling", substring = true, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun disStaysSelectableWithoutLosslessScaling() {
        setSection(FrameGenEngine.OFF, lsfgAvailable = false)

        composeRule.onNodeWithText("DIS").performClick()
        composeRule.waitForIdle()

        assertEquals(FrameGenEngine.DIS, selected)
    }
}
