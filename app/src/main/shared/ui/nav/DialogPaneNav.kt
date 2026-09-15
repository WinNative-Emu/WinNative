package com.winlator.cmod.shared.ui.nav

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.abs

private const val PANE_STICK_ENGAGE = 0.5f
private const val PANE_STICK_RELEASE = 0.35f

// Window-callback nav source: fires before Compose focus resolution, so it works in tap-opened dialogs with no focused node.
internal class PaneNavWindowHandlers(
    val onDir: (Int) -> Unit,
    val onActivate: () -> Unit,
    val onSecondary: () -> Unit = {},
    val onDismiss: () -> Unit,
    val onStart: () -> Unit = {},
    val onScroll: (Float) -> Unit = {},
)

// Handlers driving a swappable PaneNavRegistry; the provider is re-read per event so a host can route to an overlay and back.
internal fun paneNavHandlers(
    onDismiss: () -> Unit,
    onStart: () -> Unit = {},
    onScroll: (Float) -> Unit = {},
    registry: () -> PaneNavRegistry?,
): PaneNavWindowHandlers =
    PaneNavWindowHandlers(
        onDir = { registry()?.navDir(it) },
        onActivate = { registry()?.navDir(PANE_DIR_ACTIVATE) },
        onSecondary = { registry()?.navDir(PANE_DIR_SECONDARY) },
        onDismiss = onDismiss,
        onStart = onStart,
        onScroll = onScroll,
    )

private class PaneNavWindowCallback(
    private val base: Window.Callback,
    private val handlers: PaneNavWindowHandlers,
    private val window: Window?,
) : Window.Callback by base {
    private var stickEngaged = 0

    private fun hideImeIfVisible(): Boolean {
        val win = window ?: return false
        val decor = win.decorView
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor) ?: return false
        if (!insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())) return false
        androidx.core.view.WindowInsetsControllerCompat(win, decor)
            .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (!isOwnedKey(keyCode)) return base.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> handlers.onDir(PANE_DIR_LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> handlers.onDir(PANE_DIR_RIGHT)
            KeyEvent.KEYCODE_DPAD_UP -> handlers.onDir(PANE_DIR_UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> handlers.onDir(PANE_DIR_DOWN)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> handlers.onActivate()
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            -> handlers.onSecondary()
            KeyEvent.KEYCODE_BUTTON_START -> handlers.onStart()
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BACK,
            -> if (!hideImeIfVisible()) handlers.onDismiss()
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK ||
            event.action != MotionEvent.ACTION_MOVE
        ) {
            return base.dispatchGenericMotionEvent(event)
        }
        handlers.onScroll(event.getAxisValue(MotionEvent.AXIS_RZ))
        val sx = event.getAxisValue(MotionEvent.AXIS_X)
        val sy = event.getAxisValue(MotionEvent.AXIS_Y)
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val dir =
            when {
                sx < -PANE_STICK_ENGAGE || hx < -PANE_STICK_ENGAGE -> PANE_DIR_LEFT
                sx > PANE_STICK_ENGAGE || hx > PANE_STICK_ENGAGE -> PANE_DIR_RIGHT
                sy < -PANE_STICK_ENGAGE || hy < -PANE_STICK_ENGAGE -> PANE_DIR_UP
                sy > PANE_STICK_ENGAGE || hy > PANE_STICK_ENGAGE -> PANE_DIR_DOWN
                else -> 0
            }
        if (dir != 0) {
            if (stickEngaged == 0) {
                stickEngaged = dir
                handlers.onDir(dir)
            }
            return true
        }
        if (abs(sx) < PANE_STICK_RELEASE && abs(sy) < PANE_STICK_RELEASE &&
            abs(hx) < PANE_STICK_RELEASE && abs(hy) < PANE_STICK_RELEASE
        ) {
            stickEngaged = 0
        }
        return true
    }

    private fun isOwnedKey(keyCode: Int): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BACK,
            -> true
            else -> false
        }
}

// Install the nav source on this window; returns a restore lambda that reinstalls the previous callback on teardown.
internal fun Window.bindPaneNav(handlers: PaneNavWindowHandlers): () -> Unit {
    val prev = callback ?: return {}
    val wrapper = PaneNavWindowCallback(prev, handlers, this)
    callback = wrapper
    val unregister = ControllerWindowInput.register(this)
    return {
        unregister()
        if (callback === wrapper) callback = prev
    }
}

internal fun Window.bindPaneNav(
    registry: PaneNavRegistry,
    onDismiss: () -> Unit,
    onStart: () -> Unit = {},
): () -> Unit = bindPaneNav(paneNavHandlers(onDismiss, onStart) { registry })

// For Dialog { } content: resolves the dialog's own Window via DialogWindowProvider and binds the nav source.
@Composable
internal fun DialogPaneNav(
    registry: PaneNavRegistry,
    onDismiss: () -> Unit,
    onStart: () -> Unit = {},
) {
    DialogPaneNav(paneNavHandlers(onDismiss, onStart) { registry })
}

@Composable
internal fun DialogPaneNav(handlers: PaneNavWindowHandlers) {
    val view = LocalView.current
    DisposableEffect(view, handlers) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val restore = window?.bindPaneNav(handlers)
        onDispose { restore?.invoke() }
    }
}
