package com.winlator.cmod.feature.settings
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.winlator.cmod.shared.ui.layout.isPortraitLayout
import com.winlator.cmod.shared.ui.layout.screenWidthDp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.fragment.compose.AndroidFragment
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.winlator.cmod.R
import com.winlator.cmod.feature.sync.google.GoogleFragment
import com.winlator.cmod.shared.ui.nav.PANE_DIR_ACTIVATE
import com.winlator.cmod.shared.ui.nav.PANE_DIR_DOWN
import com.winlator.cmod.shared.ui.nav.PANE_DIR_LEFT
import com.winlator.cmod.shared.ui.nav.PANE_DIR_RIGHT
import com.winlator.cmod.shared.ui.nav.PANE_DIR_SECONDARY
import com.winlator.cmod.shared.ui.nav.PANE_DIR_UP

object SettingsRoutes {
    fun fromNavItem(item: SettingsNavItem): String = "settings/${item.name.lowercase()}"
}

enum class SettingsFocusZone { SIDEBAR, CONTENT }

class SettingsNavBridge {
    var selectedItem by mutableStateOf(SettingsNavItem.CONTAINERS)
    var zone by mutableStateOf(SettingsFocusZone.SIDEBAR)
    var onSelectItem: ((SettingsNavItem) -> Unit)? = null

    var contentControllerActive by mutableStateOf(false)
    var contentNavSignal by mutableStateOf(0)
        private set
    var contentNavDir by mutableStateOf(0)
        private set

    private fun contentNav(dir: Int) {
        contentNavDir = dir
        contentNavSignal++
    }

    fun contentNavLeft() = contentNav(PANE_DIR_LEFT)

    fun contentNavRight() = contentNav(PANE_DIR_RIGHT)

    fun contentNavUp() = contentNav(PANE_DIR_UP)

    fun contentNavDown() = contentNav(PANE_DIR_DOWN)

    fun contentActivate() = contentNav(PANE_DIR_ACTIVATE)

    fun contentSecondary() = contentNav(PANE_DIR_SECONDARY)

    var contentSectionSignal by mutableStateOf(0)
        private set
    var contentSectionDir by mutableStateOf(0)
        private set

    private fun contentSection(dir: Int) {
        contentSectionDir = dir
        contentSectionSignal++
    }

    fun contentSectionPrev() = contentSection(-1)

    fun contentSectionNext() = contentSection(1)

    var sidebarRevealSignal by mutableStateOf(0)
        private set

    fun revealSidebar() {
        zone = SettingsFocusZone.SIDEBAR
        sidebarRevealSignal++
    }
}

private val SettingsBg = Color(0xFF11111C)

@Composable
fun SettingsHost(
    bridge: SettingsNavBridge,
    startItem: SettingsNavItem = SettingsNavItem.CONTAINERS,
    selectedProfileId: Int = 0,
    bordersPaused: Boolean = false,
    onBack: () -> Unit,
) {
    val settingsNavController = rememberNavController()
    var currentItem by rememberSaveable { mutableStateOf(startItem) }

    val navigateTo: (SettingsNavItem) -> Unit = { item ->
        if (item != currentItem) {
            currentItem = item
            settingsNavController.navigate(SettingsRoutes.fromNavItem(item)) {
                popUpTo(SettingsRoutes.fromNavItem(startItem)) {
                    inclusive = false
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    SideEffect { bridge.selectedItem = currentItem }

    DisposableEffect(Unit) {
        bridge.zone = SettingsFocusZone.SIDEBAR
        bridge.onSelectItem = navigateTo
        onDispose { bridge.onSelectItem = null }
    }

    val portrait = isPortraitLayout()
    var sectionsOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(portrait) { if (!portrait) sectionsOpen = false }

    LaunchedEffect(bridge.sidebarRevealSignal) {
        if (portrait && bridge.sidebarRevealSignal > 0) sectionsOpen = true
    }

    LaunchedEffect(bridge.zone) {
        if (portrait && bridge.zone == SettingsFocusZone.CONTENT) sectionsOpen = false
    }

    BackHandler(enabled = portrait && sectionsOpen) { sectionsOpen = false }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SettingsBg),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize(),
        ) {
            if (!portrait) {
                SettingsNavSidebar(
                    selectedItem = currentItem,
                    railActive = bridge.zone == SettingsFocusZone.SIDEBAR,
                    onItemSelected = { item ->
                        bridge.zone = SettingsFocusZone.SIDEBAR
                        navigateTo(item)
                    },
                    onBackPressed = onBack,
                    bordersPaused = bordersPaused,
                )
            }

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
            ) {
            if (portrait) {
                SettingsPortraitTopBar(
                    title = stringResource(currentItem.titleRes),
                    onBackPressed = onBack,
                    onOpenSections = { sectionsOpen = true },
                )
            }
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                                    if (ev.type == PointerEventType.Press) {
                                        bridge.zone = SettingsFocusZone.CONTENT
                                        bridge.contentControllerActive = false
                                    }
                                }
                            }
                        },
            ) {
                NavHost(
                    navController = settingsNavController,
                    startDestination = SettingsRoutes.fromNavItem(startItem),
                    enterTransition = { fadeIn(tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)) },
                    exitTransition = { fadeOut(tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)) },
                    popEnterTransition = { fadeIn(tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)) },
                    popExitTransition = { fadeOut(tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable(SettingsRoutes.fromNavItem(SettingsNavItem.DEVICE_PROFILE)) {
                        com.winlator.cmod.feature.settings.device.DeviceProfileScreen(bridge = bridge)
                    }
                    composable(SettingsRoutes.fromNavItem(SettingsNavItem.CONTAINERS)) {
                        AndroidFragment<ContainersFragment>()
                    }
                    composable(SettingsRoutes.fromNavItem(SettingsNavItem.INPUT_CONTROLS)) {
                        AndroidFragment<InputControlsFragment>(
                            arguments =
                                Bundle().apply {
                                    putInt("selectedProfileId", selectedProfileId)
                                },
                        )
                    }
                    composable(SettingsRoutes.fromNavItem(SettingsNavItem.COMPONENTS)) {
                        AndroidFragment<ContentsFragment>()
                    }
                    composable(SettingsRoutes.fromNavItem(SettingsNavItem.DRIVERS)) {
                        AndroidFragment<DriversFragment>()
                    }
                    composable(SettingsRoutes.fromNavItem(SettingsNavItem.STORES)) {
                        AndroidFragment<StoresFragment>()
                    }
                    composable(SettingsRoutes.fromNavItem(SettingsNavItem.DEBUG)) {
                        AndroidFragment<DebugFragment>()
                    }
                    composable(SettingsRoutes.fromNavItem(SettingsNavItem.GOOGLE)) {
                        AndroidFragment<GoogleFragment>()
                    }
                    composable(SettingsRoutes.fromNavItem(SettingsNavItem.PRESETS)) {
                        AndroidFragment<PresetsFragment>()
                    }
                    composable(SettingsRoutes.fromNavItem(SettingsNavItem.OTHER)) {
                        AndroidFragment<OtherSettingsFragment>()
                    }
                    composable(SettingsRoutes.fromNavItem(SettingsNavItem.RETRO)) {
                        com.winlator.cmod.feature.retro.RetroDefaultsScreen(bridge = bridge)
                    }
                    composable(SettingsRoutes.fromNavItem(SettingsNavItem.CREDITS)) {
                        com.winlator.cmod.feature.retro.RetroCreditsScreen(bridge = bridge)
                    }
                    composable(SettingsRoutes.fromNavItem(SettingsNavItem.SUPPORT)) {
                        com.winlator.cmod.feature.settings.support.SupportScreen(bridge = bridge)
                    }
                    composable(SettingsRoutes.fromNavItem(SettingsNavItem.ABOUT)) {
                        com.winlator.cmod.feature.settings.about.AboutScreen(bridge = bridge)
                    }
                }
            }
            }
        }

        if (portrait && sectionsOpen) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                                    if (ev.type == PointerEventType.Press) {
                                        sectionsOpen = false
                                        ev.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        },
            )
            SettingsNavSidebar(
                selectedItem = currentItem,
                railActive = bridge.zone == SettingsFocusZone.SIDEBAR,
                onItemSelected = { item ->
                    bridge.zone = SettingsFocusZone.CONTENT
                    sectionsOpen = false
                    navigateTo(item)
                },
                onBackPressed = { sectionsOpen = false },
                bordersPaused = bordersPaused,
                sidebarWidth = minOf(280.dp, screenWidthDp() - 56.dp),
                showHeader = false,
            )
        }
    }
}
