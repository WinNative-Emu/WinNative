package com.winlator.cmod.app.shell

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryGameLaunchBranchTagTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val branches =
        listOf(
            StoreBranchOption(id = "public", label = "Default (public)", buildId = 100L, isInstalled = true),
            StoreBranchOption(id = "beta", label = "beta", buildId = 200L),
            StoreBranchOption(id = "legacy", label = "legacy", buildId = 50L),
        )

    private fun setScreen(
        branchOptions: List<StoreBranchOption>,
        selectedBranchId: String = "public",
        onSelectBranch: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            LibraryGameLaunchScreen(
                appName = "Test Game",
                subtitle = "Test Studio",
                sourceLabel = "Steam",
                heroImageUrl = null,
                customHeroImageCacheKey = null,
                releaseDateEpochSeconds = 0L,
                totalPlaytimeMillis = 0L,
                playCount = 0,
                lastPlayedMillis = 0L,
                installSizeText = "12.9 GB",
                removalKeepsFiles = false,
                hasPinnedShortcut = false,
                steamMenuEnabled = true,
                onBack = {},
                onPlay = {},
                onSettings = {},
                onBootToDesktop = {},
                onShortcut = {},
                onCloudSaves = {},
                onUninstall = {},
                branches = branchOptions,
                selectedBranchId = selectedBranchId,
                onSelectBranch = onSelectBranch,
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun branchTagSitsLeftOfTheSourceTag() {
        setScreen(branchOptions = branches)

        val tag = composeRule.onNodeWithText("PUBLIC", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val source = composeRule.onNodeWithText("STEAM", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        assert(tag.right <= source.left) {
            "branch tag (right=${tag.right}) should sit left of the Steam tag (left=${source.left})"
        }
    }

    @Test
    fun branchTagIsHiddenWhenOnlyOneBranchExists() {
        setScreen(branchOptions = branches.take(1))

        composeRule.onNodeWithText("PUBLIC", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun branchTagIsHiddenForNonSteamGames() {
        setScreen(branchOptions = emptyList())

        composeRule.onNodeWithText("PUBLIC", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun branchTagShowsTheSelectedBranch() {
        setScreen(branchOptions = branches, selectedBranchId = "beta")

        composeRule.onNodeWithText("BETA", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun choosingABranchReportsTheSelection() {
        var chosen: String? = null
        setScreen(branchOptions = branches, onSelectBranch = { chosen = it })

        composeRule.onNodeWithText("PUBLIC", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("legacy", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        assertEquals("legacy", chosen)
    }

    @Test
    fun theSelectedBranchIsNotReportedAgainWhenReselected() {
        var calls = 0
        val selected = mutableStateOf("public")
        setScreen(branchOptions = branches, onSelectBranch = { calls++; selected.value = it })

        composeRule.onNodeWithText("PUBLIC", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Default (public)", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        assertEquals(0, calls)
    }
}
