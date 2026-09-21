package com.winlator.cmod.feature.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.R
import com.winlator.cmod.runtime.content.DriverPackages
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class DriversScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun platformTabsAndInstallDestinationKeepPaneNavigation() {
        val state = mutableStateOf(DriversState(installedDrivers = listOf(InstalledDriverItem(id = "android", name = "WN Android", version = "1.16"))))
        val bridge = SettingsNavBridge().apply {
            zone = SettingsFocusZone.CONTENT
            contentControllerActive = true
        }
        var destination: DriverPackages.Platform? = null
        compose.setContent {
            DriversScreen(
                state = state.value,
                onInstallFromFile = { destination = it },
                onPlatformSelected = {
                    state.value = state.value.copy(platform = it, installedDrivers = listOf(InstalledDriverItem(id = DriverPackages.BUNDLED, name = "Bundled Mesa", version = "26.2.2", selected = true, removable = false), InstalledDriverItem(id = "linux-b", name = "WN Linux Turnip 0.1.0-b", version = "0.1.0-b"), InstalledDriverItem(id = "linux-p", name = "WN Linux Turnip 0.1.0-p", version = "0.1.0-p")))
                },
                onSelectDriver = {}, onSourceTapped = {}, onReleaseTapped = {}, onDownloadAsset = {}, onRemoveDriver = {},
                onRepoAdded = { _, _ -> }, onRepoUpdated = { _, _, _ -> }, onRepoDeleted = {}, onRestoreDefaultRepos = {}, bridge = bridge,
            )
        }
        compose.onNodeWithText("Android").assertIsDisplayed()
        compose.onNodeWithText("Linux").assertIsDisplayed()
        compose.onNodeWithText("Android").performClick()
        compose.runOnIdle { bridge.contentNavRight() }
        compose.waitForIdle()
        compose.runOnIdle { bridge.contentActivate() }
        compose.waitForIdle()
        assertEquals(DriverPackages.Platform.LINUX, state.value.platform)
        compose.onNodeWithText("Bundled Mesa").assertIsDisplayed()
        compose.onNodeWithText("WN Linux Turnip 0.1.0-b").assertIsDisplayed()
        compose.onNodeWithText("WN Linux Turnip 0.1.0-p").assertIsDisplayed()
        screenshot("linux-drivers.png")
        compose.onNodeWithText(context.getString(R.string.settings_drivers_install)).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_drivers_platform_hint)).assertIsDisplayed()
        screenshot("driver-destination.png")
        compose.onAllNodesWithText("Android")[1].performClick()
        compose.runOnIdle { assertEquals(DriverPackages.Platform.ANDROID, destination) }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.SystemClock.sleep(350)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
