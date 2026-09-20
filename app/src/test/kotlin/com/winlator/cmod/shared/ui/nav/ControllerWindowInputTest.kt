package com.winlator.cmod.shared.ui.nav

import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.view.Window
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ControllerWindowInputTest {
    @Test fun focusedDialogConsumesInputAndDisposalRestoresFallback() {
        val window = mock(Window::class.java)
        val decor = mock(View::class.java)
        val callback = mock(Window.Callback::class.java)
        `when`(window.decorView).thenReturn(decor)
        `when`(window.callback).thenReturn(callback)
        `when`(decor.isShown).thenReturn(true)
        `when`(decor.hasWindowFocus()).thenReturn(true)
        val remove = ControllerWindowInput.register(window)
        val key = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)
        try {
            assertTrue(ControllerWindowInput.dispatch(key))
            verify(callback).dispatchKeyEvent(key)
            `when`(decor.hasWindowFocus()).thenReturn(false)
            assertFalse(ControllerWindowInput.dispatch(key))
            verifyNoMoreInteractions(callback)
        } finally { remove() }
        assertFalse(ControllerWindowInput.dispatch(key))
    }
}
