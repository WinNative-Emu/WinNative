package com.winlator.cmod.app.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryGameLaunchStoreTagTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val stores =
        listOf(
            LaunchStoreOption(id = "STEAM", label = "Steam"),
            LaunchStoreOption(id = "EPIC", label = "Epic Games"),
        )

    private fun setScreen(
        sourceLabel: String,
        storeOptions: List<LaunchStoreOption>,
        selectedStoreId: String,
        onSelectStore: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            LibraryGameLaunchScreen(
                appName = "Test Game",
                subtitle = "Test Studio",
                sourceLabel = sourceLabel,
                heroImageUrl = null,
                customHeroImageCacheKey = null,
                releaseDateEpochSeconds = 0L,
                totalPlaytimeMillis = 0L,
                playCount = 0,
                lastPlayedMillis = 0L,
                installSizeText = "12.9 GB",
                removalKeepsFiles = false,
                hasPinnedShortcut = false,
                steamMenuEnabled = false,
                onBack = {},
                onPlay = {},
                onSettings = {},
                onBootToDesktop = {},
                onShortcut = {},
                onCloudSaves = {},
                onUninstall = {},
                storeOptions = storeOptions,
                selectedStoreId = selectedStoreId,
                onSelectStore = onSelectStore,
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun theTagShowsTheStoreTheGameCameFrom() {
        setScreen("Epic Games", stores, "EPIC")

        composeRule.onNodeWithText("EPIC GAMES", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theStoresAreBehindAChangeStoreEntry() {
        setScreen("Epic Games", stores, "EPIC")

        composeRule.onNodeWithText("EPIC GAMES", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Change Store", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Steam", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun everyOwnedStoreIsOfferedAfterChangeStore() {
        setScreen("Epic Games", stores, "EPIC")

        openStorePicker()

        composeRule.onNodeWithText("Steam", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Epic Games", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun choosingAnotherStoreReportsTheSelection() {
        var chosen: String? = null
        setScreen("Epic Games", stores, "EPIC", onSelectStore = { chosen = it })

        openStorePicker()
        composeRule.onNodeWithText("Steam", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        assertEquals("STEAM", chosen)
    }

    @Test
    fun reselectingTheCurrentStoreReportsNothing() {
        var chosen: String? = null
        setScreen("Epic Games", stores, "EPIC", onSelectStore = { chosen = it })

        openStorePicker()
        composeRule.onNodeWithText("Epic Games", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        assertNull(chosen)
    }

    @Test
    fun aGameOwnedOnOneStoreStillHasTheStoreMenu() {
        setScreen("Steam", stores.take(1), "STEAM")

        composeRule.onNodeWithText("STEAM", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Change Store", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Steam", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Epic Games", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aGameOwnedNowhereShowsTheEmptyStoreList() {
        setScreen("Custom", emptyList(), "")

        composeRule.onNodeWithText("CUSTOM", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Change Store", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText("No store libraries match this game", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    private fun openStorePicker() {
        composeRule.onNodeWithText("EPIC GAMES", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Change Store", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
    }
}
