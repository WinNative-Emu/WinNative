package com.winlator.cmod.runtime.input.controls

import android.app.Application
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SteamControllerInputRouterTest {
    private val keys = mutableListOf<KeyEvent>()
    private val navigation = SteamControllerNavigation({ keys.add(it) }, false)
    private val pointer = mock(SteamControllerPointer::class.java)
    private val router = SteamControllerInputRouter(navigation, pointer).apply { foreground = true }
    private val pad = ExternalController().apply { setDeviceId(-1001) }

    @Test fun settingsReceivesExistingConnectionAndCaptureDoesNotReachMenu() {
        router.onSteamPadConnected(pad)
        val settings = mock(SteamControllerBackend.Listener::class.java)
        var capture = false
        router.attach(settings) { capture }
        verify(settings).onSteamPadConnected(pad)
        assertTrue(router.hasControllers)
        capture = true
        router.onSteamPadState(pad, false, false, intArrayOf(KeyEvent.KEYCODE_BUTTON_A))
        router.onSteamPadMouseMove(4, 5)
        verify(settings).onSteamPadState(eq(pad), eq(false), eq(false), any(IntArray::class.java))
        assertTrue(keys.isEmpty())
        verify(pointer, never()).move(anyInt(), anyInt())
        assertFalse(router.detach(mock(SteamControllerBackend.Listener::class.java)))
        assertTrue(router.detach(settings))
        assertTrue(router.hasControllers)
        router.onSteamPadState(pad, false, false, intArrayOf(KeyEvent.KEYCODE_BUTTON_A))
        assertTrue(keys.isEmpty())
        router.onSteamPadState(pad, false, false, intArrayOf())
        router.onSteamPadState(pad, false, false, intArrayOf(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, keys.single().keyCode)
        router.clearOutputs()
    }

    @Test fun leftPadSwipesScrollWithoutJumpingOnTouchOrRetouch() {
        pad.steamLeftTouch = true
        pad.steamLeftX = 0.5f
        pad.steamLeftY = 0.75f
        router.onSteamPadState(pad, false, false, intArrayOf())
        verify(pointer, never()).scroll(anyFloat(), anyFloat())
        pad.steamLeftY = 0.5f
        router.onSteamPadState(pad, false, false, intArrayOf())
        verify(pointer).scroll(0f, -3f)
        pad.steamLeftTouch = false
        router.onSteamPadState(pad, false, false, intArrayOf())
        pad.steamLeftTouch = true
        pad.steamLeftY = 0.9f
        router.onSteamPadState(pad, false, false, intArrayOf())
        verify(pointer, times(1)).scroll(anyFloat(), anyFloat())
        assertTrue(keys.isEmpty())
        router.clearOutputs()
    }

    @Test fun captureAndMousePreferencePreventLeftPadScrolling() {
        router.leftTrackpadScroll = false
        pad.steamLeftTouch = true
        router.onSteamPadState(pad, false, false, intArrayOf())
        pad.steamLeftY = 0.5f
        router.onSteamPadState(pad, false, false, intArrayOf())
        router.leftTrackpadScroll = true
        router.attach(mock(SteamControllerBackend.Listener::class.java)) { true }
        pad.steamLeftY = 0.8f
        router.onSteamPadState(pad, false, false, intArrayOf())
        verify(pointer, never()).scroll(anyFloat(), anyFloat())
        router.clearOutputs()
    }

    @Test fun buttonWhichClosesCaptureIsNotAlsoDispatchedToUnderlyingMenu() {
        var capture = true
        val settings = mock(SteamControllerBackend.Listener::class.java)
        doAnswer { capture = false; null }.`when`(settings).onSteamPadState(eq(pad), eq(false), eq(false), any(IntArray::class.java))
        router.attach(settings) { capture }
        router.onSteamPadState(pad, false, false, intArrayOf(KeyEvent.KEYCODE_BUTTON_A))
        assertTrue(keys.isEmpty())
        router.foreground = false
        router.prepareStateReplay()
        router.onSteamPadMouseButton(false, true)
        verify(pointer, never()).button(false, true)
    }
}
