package com.winlator.cmod.shared.ui.nav

import android.view.KeyEvent
import android.view.Window
import java.lang.ref.WeakReference

internal object ControllerWindowInput {
    private val windows = mutableListOf<WeakReference<Window>>()

    fun register(window: Window): () -> Unit {
        val reference = WeakReference(window)
        windows.add(reference)
        return { windows.remove(reference) }
    }

    fun focusedWindow(): Window? {
        windows.removeAll { it.get() == null }
        return windows.asReversed().firstNotNullOfOrNull { reference ->
            reference.get()?.takeIf { it.decorView.hasWindowFocus() && it.decorView.isShown }
        }
    }

    fun dispatch(event: KeyEvent): Boolean {
        val window = focusedWindow() ?: return false
        window.callback?.dispatchKeyEvent(event)
        return true
    }
}
