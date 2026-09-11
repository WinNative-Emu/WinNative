package com.winlator.cmod.runtime.display

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.winlator.cmod.runtime.input.controls.ControlsProfile
import com.winlator.cmod.shared.theme.WinNativeTheme
import com.winlator.cmod.shared.ui.controllertest.ControllerTestDialog

object ControllerTestHost {
    @JvmStatic
    fun attach(
        view: ComposeView,
        activity: XServerDisplayActivity,
        onDismiss: Runnable,
    ) {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            var revision by remember { mutableIntStateOf(0) }
            val manager = activity.controllerTestProfileManager
            val profiles: List<ControlsProfile> =
                remember(revision, manager) { manager?.getProfiles(true)?.toList() ?: emptyList() }
            val active: ControlsProfile? =
                remember(revision) { activity.inputControlsView?.profile }

            WinNativeTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Color(0xFF1A9FFF),
                        background = Color(0xFF18181D),
                        surface = Color(0xFF1C1C2A),
                    ),
            ) {
                ControllerTestDialog(
                    onDismiss = { onDismiss.run() },
                    profile = active,
                    allProfiles = profiles,
                    onSelectProfile = { profile ->
                        activity.applyControllerTestProfile(profile)
                        revision++
                    },
                    onCreateProfile = { name ->
                        val created = manager?.createProfile(name)
                        if (created != null) activity.applyControllerTestProfile(created)
                        revision++
                    },
                    onRenameProfile = null,
                    onBindingsChanged = {
                        activity.reloadControllerTestBindings()
                        revision++
                    },
                )
            }
        }
    }
}
