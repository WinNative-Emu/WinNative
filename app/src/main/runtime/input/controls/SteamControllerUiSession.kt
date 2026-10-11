package com.winlator.cmod.runtime.input.controls

import android.app.Activity
import android.view.InputDevice
import com.winlator.cmod.shared.ui.nav.ControllerWindowInput

internal class SteamControllerUiSession(
    private val activity: Activity,
    private val createBackend: (SteamControllerBackend.Listener) -> SteamControllerBackend = {
        SteamControllerBackend(activity, SteamControllerBackend.TRACKPAD_MOUSE_RIGHT, null, it)
    },
) {
    private val pointer = SteamControllerPointer(activity)
    private val navigation = SteamControllerNavigation({
        if (!ControllerWindowInput.dispatch(it) && activity.window.decorView.hasWindowFocus()) {
            activity.dispatchKeyEvent(it)
        }
    }, false)
    private val router = SteamControllerInputRouter(navigation, pointer)
    private var backend: SteamControllerBackend? = null

    fun resume() {
        router.foreground = true
        if (backend == null && SteamControllerPrefs.isEnabled(activity)) {
            createBackend(router).let { if (it.start()) backend = it }
        }
        backend?.publishCurrentState()
    }

    fun pause() {
        router.foreground = false
        router.clearOutputs()
        backend?.publishCurrentState()
    }

    fun stop() {
        router.foreground = false
        router.clearOutputs()
        backend?.stop()
        backend = null
    }

    fun isShadow(device: InputDevice?) = router.hasControllers &&
        device?.vendorId == SteamControllerBackend.VALVE_VENDOR_ID
}
