package com.winlator.cmod.feature.settings

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.R
import com.winlator.cmod.runtime.content.ContentProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ComponentsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun platformTabsExposeLinuxProtonsFromComponents() {
        runComponentsTest("components-linux-tab.png")
    }

    @Test
    fun platformTabsExposeLinuxProtonsFromComponentsInLandscape() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.screenWidthDp >
                compose.activity.resources.configuration.screenHeightDp
        }
        runComponentsTest("components-linux-tab-landscape.png")
    }

    private fun runComponentsTest(screenshotName: String) {
        val state =
            mutableStateOf(
                ComponentsState(
                    installed =
                        listOf(
                            ComponentItem(
                                key = "proton",
                                type = ContentProfile.ContentType.CONTENT_TYPE_PROTON,
                                verName = "Proton-11.0",
                                isInstalled = true,
                                hasRemote = false,
                            ),
                        ),
                    linuxAvailable =
                        listOf(
                            LinuxComponentItem(
                                key = "GE-Proton11-7-aarch64",
                                type = ContentProfile.ContentType.CONTENT_TYPE_PROTON,
                                verName = "GE-Proton 11-7 (ARM64)",
                                isInstalled = false,
                                hasRemote = true,
                                sizeBytes = 645_786_140,
                            ),
                            LinuxComponentItem(
                                key = "proton-cachyos-11.0-20260703-slr-arm64",
                                type = ContentProfile.ContentType.CONTENT_TYPE_PROTON,
                                verName = "CachyOS Proton 11.0-20260703",
                                isInstalled = false,
                                hasRemote = true,
                                sizeBytes = 339_912_088,
                            ),
                        ),
                ),
            )
        val bridge = SettingsNavBridge().apply {
            zone = SettingsFocusZone.CONTENT
            contentControllerActive = true
        }
        var downloadedLinuxProton: String? = null
        compose.setContent {
            ComponentsScreen(
                bridge = bridge,
                state = state.value,
                onPlatformSelected = { platform -> state.value = state.value.copy(platform = platform) },
                onTypeSelected = {},
                onInstallFromFile = {},
                onDownloadItem = {},
                onRemoveItem = {},
                onDownloadLinuxItem = { downloadedLinuxProton = it.key },
                onRemoveLinuxItem = {},
                onCancelLinuxItem = {},
                onDismissConflict = {},
                onToggleAutoCreateContainer = {},
                onRefresh = {},
            )
        }

        compose.onNodeWithText("Android ${context.getString(R.string.settings_content_components)}").assertIsDisplayed()
        compose.onNodeWithText("Linux ${context.getString(R.string.settings_content_components)}").assertIsDisplayed()
        compose.onNodeWithText("Proton-11.0").assertIsDisplayed()
        compose.onNodeWithText("Android ${context.getString(R.string.settings_content_components)}").performClick()
        compose.runOnIdle { bridge.contentNavRight() }
        compose.waitForIdle()
        compose.runOnIdle { bridge.contentActivate() }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(ComponentsPlatform.LINUX, state.value.platform) }
        compose.onNodeWithText("Proton").assertIsDisplayed()
        compose.onNodeWithText("GE-Proton 11-7 (ARM64)").assertIsDisplayed()
        compose.onNodeWithText("CachyOS Proton 11.0-20260703").assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.common_ui_open)).assertCountEquals(0)
        screenshot(screenshotName)
        compose.onAllNodesWithText(context.getString(R.string.common_ui_download))[0].performClick()
        compose.runOnIdle { assertTrue(downloadedLinuxProton in state.value.linuxAvailable.map { it.key }) }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.SystemClock.sleep(350)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
