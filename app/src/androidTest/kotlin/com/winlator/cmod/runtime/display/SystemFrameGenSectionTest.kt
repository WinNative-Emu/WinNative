package com.winlator.cmod.runtime.display

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemFrameGenSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setSection(
        detected: Boolean,
        hudEnabled: Boolean,
        signal: String,
        onHudEnabledChanged: (Boolean) -> Unit = {},
    ) {
        composeRule.setContent {
            SystemFrameGenerationSection(
                detected = detected,
                hudEnabled = hudEnabled,
                signal = signal,
                paneScale = 1f,
                onHudEnabledChanged = onHudEnabledChanged,
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun theDetectedSignalIsNamed() {
        setSection(detected = true, hudEnabled = true, signal = "persist.sys.nubia.game.frc=2")

        composeRule
            .onNodeWithText("persist.sys.nubia.game.frc=2", substring = true, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun anUndetectedDeviceSaysSoAndStillOffersTheSwitch() {
        setSection(detected = false, hudEnabled = false, signal = "")

        composeRule
            .onNodeWithText("Not detected", substring = true, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Count system frames in the HUD", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theSwitchReflectsTheHudState() {
        setSection(detected = true, hudEnabled = true, signal = "x=1")
        composeRule.onNode(isToggleable(), useUnmergedTree = true).assertIsOn()
    }

    @Test
    fun theSwitchIsOffWhenTheHudIsNotCountingSystemFrames() {
        setSection(detected = false, hudEnabled = false, signal = "")
        composeRule.onNode(isToggleable(), useUnmergedTree = true).assertIsOff()
    }

    @Test
    fun togglingTheSwitchReportsTheNewState() {
        var reported: Boolean? = null
        setSection(detected = false, hudEnabled = false, signal = "", onHudEnabledChanged = { reported = it })

        composeRule.onNodeWithText("Count system frames in the HUD", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        assertEquals(true, reported)
    }
}
