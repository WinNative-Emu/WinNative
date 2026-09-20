package com.winlator.cmod.runtime.input.controls

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.MotionEvent
import android.view.Window
import android.widget.FrameLayout
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import com.winlator.cmod.shared.ui.nav.ControllerWindowInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SteamControllerPointerTest {
    private val context = RuntimeEnvironment.getApplication()
    private class Surface(context: Context) : FrameLayout(context) {
        var focused = true
        val events = mutableListOf<MotionEvent>()
        override fun hasWindowFocus() = focused
        override fun isShown() = true
        override fun dispatchTouchEvent(event: MotionEvent): Boolean { events.add(MotionEvent.obtain(event)); return true }
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean { events.add(MotionEvent.obtain(event)); return true }
    }
    private val surface = Surface(context).apply { layout(0, 0, 800, 600) }
    private val window = mock(Window::class.java).apply { `when`(decorView).thenReturn(surface) }
    private val activity = spy(Robolectric.buildActivity(Activity::class.java).setup().get()).apply {
        doReturn(this@SteamControllerPointerTest.window).`when`(this).getWindow()
    }
    private val pointer = SteamControllerPointer(activity)

    @Test fun cursorClampsToWindowAndClickHasMouseCoordinates() {
        pointer.move(1000, -1000)
        pointer.button(false, true)
        pointer.button(false, false)
        assertEquals(listOf(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), surface.events.map { it.actionMasked })
        surface.events.forEach {
            assertEquals(799f, it.x, 0f)
            assertEquals(0f, it.y, 0f)
            assertEquals(MotionEvent.TOOL_TYPE_MOUSE, it.getToolType(0))
        }
        assertEquals(MotionEvent.BUTTON_PRIMARY, surface.events[1].buttonState)
        assertEquals(0, surface.events[2].buttonState)
        pointer.clear()
    }

    @Test fun movingIntoFocusedDialogCancelsHeldClickInPreviousWindow() {
        pointer.button(false, true)
        surface.focused = false
        val dialog = Surface(context).apply { layout(0, 0, 400, 300) }
        val dialogWindow = mock(Window::class.java).apply { `when`(decorView).thenReturn(dialog) }
        val unregister = ControllerWindowInput.register(dialogWindow)
        try {
            pointer.move(10, 20)
            pointer.button(false, false)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), surface.events.map { it.actionMasked })
            assertEquals(listOf(MotionEvent.ACTION_HOVER_MOVE), dialog.events.map { it.actionMasked })
            assertEquals(210f, dialog.events.single().x, 0f)
        } finally { pointer.clear(); unregister() }
    }

    @Test fun focusLossCancelsClickRatherThanActivatingCoveredWindow() {
        pointer.button(false, true)
        surface.focused = false
        pointer.button(false, false)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), surface.events.map { it.actionMasked })
    }
}
