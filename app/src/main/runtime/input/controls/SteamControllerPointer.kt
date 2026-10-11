package com.winlator.cmod.runtime.input.controls

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.nav.ControllerWindowInput

internal class SteamControllerPointer(private val activity: Activity) {
    private var window: Window? = null
    private var cursor: View? = null
    private var x = 0f
    private var y = 0f
    private var buttons = 0
    private var downTime = 0L

    fun move(dx: Int, dy: Int) {
        val target = target() ?: return
        x = (x + dx).coerceIn(0f, (target.decorView.width - 1).coerceAtLeast(0).toFloat())
        y = (y + dy).coerceIn(0f, (target.decorView.height - 1).coerceAtLeast(0).toFloat())
        cursor?.translationX = x
        cursor?.translationY = y
        send(if (buttons == 0) MotionEvent.ACTION_HOVER_MOVE else MotionEvent.ACTION_MOVE)
    }

    fun scroll(horizontal: Float, vertical: Float) {
        if ((horizontal == 0f && vertical == 0f) || target() == null) return
        send(MotionEvent.ACTION_SCROLL, horizontal, vertical)
    }

    fun button(secondary: Boolean, down: Boolean) {
        if (down && target() == null) return
        if (window == null) return
        if (window?.decorView?.hasWindowFocus() != true) {
            clear()
            return
        }
        val bit = if (secondary) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY
        val previous = buttons
        buttons = if (down) buttons or bit else buttons and bit.inv()
        if (previous == buttons) return
        if (previous == 0) {
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN)
        }
        if (buttons == 0) send(MotionEvent.ACTION_UP)
    }

    fun clear() {
        if (buttons != 0) {
            buttons = 0
            send(MotionEvent.ACTION_CANCEL)
        }
        cursor?.let { (window?.decorView as? ViewGroup)?.overlay?.remove(it) }
        cursor = null
        window = null
    }

    private fun target(): Window? {
        val target = ControllerWindowInput.focusedWindow()
            ?: activity.window.takeIf { it.decorView.hasWindowFocus() }
            ?: return null
        if (window !== target) {
            clear()
            window = target
            x = target.decorView.width / 2f
            y = target.decorView.height / 2f
            val size = (24 * activity.resources.displayMetrics.density).toInt()
            cursor = ImageView(activity).apply {
                setImageResource(R.drawable.cursor)
                scaleType = ImageView.ScaleType.FIT_START
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layout(0, 0, size, size)
                translationX = x
                translationY = y
            }
            (target.decorView as? ViewGroup)?.overlay?.add(cursor!!)
        }
        return target
    }

    private fun send(action: Int, horizontal: Float = 0f, vertical: Float = 0f) {
        val decor = window?.decorView ?: return
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply { x = this@SteamControllerPointer.x; y = this@SteamControllerPointer.y; pressure = if (buttons != 0) 1f else 0f }
        coords.setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
            arrayOf(properties), arrayOf(coords), 0, buttons, 1f, 1f, SteamControllerBackend.DEVICE_ID_BASE,
            0, android.view.InputDevice.SOURCE_MOUSE, 0)
        try {
            if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_SCROLL) {
                decor.dispatchGenericMotionEvent(event)
            } else decor.dispatchTouchEvent(event)
        } finally { event.recycle() }
    }
}
