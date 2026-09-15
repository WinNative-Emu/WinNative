package com.winlator.cmod.runtime.input.controls

import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SteamControllerNavigationTest {
    private val events = mutableListOf<KeyEvent>()
    private val navigation = SteamControllerNavigation { events.add(it) }
    private val pad = ExternalController().apply { setDeviceId(-1001) }

    @Test fun heldStickRepeatsDespiteChangingAnalogValuesAndStopsOnDisconnect() {
        navigation.onSteamPadConnected(pad)
        repeat(6) {
            pad.state.thumbLX = 0.7f + it * 0.02f
            navigation.onSteamPadState(pad, false, false, intArrayOf())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
        assertTrue(events.count { it.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && it.action == KeyEvent.ACTION_DOWN } >= 3)
        navigation.onSteamPadDisconnected(pad)
        assertEquals(KeyEvent.ACTION_UP, events.last().action)
        val count = events.size
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(count, events.size)
    }

    @Test fun libraryButtonsDoNotRepeatOrReleaseWhileAnotherPadHoldsThem() {
        val second = ExternalController().apply { setDeviceId(-1002) }
        navigation.onSteamPadState(pad, false, false, intArrayOf(KeyEvent.KEYCODE_BUTTON_A))
        navigation.onSteamPadState(second, false, false, intArrayOf(KeyEvent.KEYCODE_BUTTON_A))
        navigation.onSteamPadDisconnected(pad)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, events.size)
        navigation.clear()
        assertEquals(2, events.size)
        assertEquals(KeyEvent.ACTION_UP, events.last().action)
        assertFalse(navigation.hasControllers())
    }

    @Test fun touchpadAndClickNavigateAndActivate() {
        pad.steamRightTouch = true
        pad.steamRightX = 0.5f
        pad.steamRightY = 0.9f
        navigation.onSteamPadState(pad, false, false, intArrayOf(KeyEvent.KEYCODE_BUTTON_7))
        assertEquals(setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BUTTON_A), events.map { it.keyCode }.toSet())
        navigation.clear()
    }

    @Test fun mouseTrackpadDoesNotAlsoNavigateOrActivateFocusedButton() {
        val mouseNavigation = SteamControllerNavigation({ events.add(it) }, false)
        pad.steamRightTouch = true
        pad.steamRightX = 0.9f
        mouseNavigation.onSteamPadState(pad, false, false, intArrayOf(KeyEvent.KEYCODE_BUTTON_6, KeyEvent.KEYCODE_BUTTON_7))
        assertTrue(events.isEmpty())
        mouseNavigation.clear()
    }

    @Test fun launchingActivityDuringDispatchCancelsRemainingHeldInputs() {
        lateinit var router: SteamControllerNavigation
        var downs = 0
        router = SteamControllerNavigation {
            if (it.action == KeyEvent.ACTION_DOWN) { downs++; router.clear() }
        }
        router.onSteamPadState(pad, false, false, intArrayOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(1, downs)
        assertFalse(router.hasControllers())
    }
}
