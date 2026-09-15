package com.winlator.cmod.runtime.input.controls

internal class SteamControllerInputRouter(
    private val navigation: SteamControllerNavigation,
    private val pointer: SteamControllerPointer,
) : SteamControllerBackend.Listener {
    private val pads = linkedMapOf<Int, ExternalController>()
    private var settings: SteamControllerBackend.Listener? = null
    private var captures: (() -> Boolean)? = null
    private var awaitNeutral = false
    private val scrollPositions = mutableMapOf<Int, Pair<Float, Float>>()
    var leftTrackpadScroll = true
        set(value) {
            field = value
            scrollPositions.clear()
        }
    var foreground = false
        set(value) {
            field = value
            if (!value) awaitNeutral = true
        }
    val hasControllers get() = pads.isNotEmpty()

    fun attach(listener: SteamControllerBackend.Listener, captureInput: () -> Boolean) {
        awaitNeutral = true
        settings = listener
        captures = captureInput
        clearOutputs()
        pads.values.toList().forEach { listener.onSteamPadConnected(it) }
    }

    fun detach(listener: SteamControllerBackend.Listener): Boolean {
        if (settings !== listener) return false
        awaitNeutral = true
        val previous = settings
        settings = null
        captures = null
        pads.values.toList().forEach { previous?.onSteamPadDisconnected(it) }
        return true
    }

    fun clearOutputs() {
        scrollPositions.clear()
        navigation.clear()
        pointer.clear()
    }

    fun prepareStateReplay() {
        if (!isSteamPadInputEnabled()) clearOutputs()
    }

    override fun isSteamPadInputEnabled() = foreground && !awaitNeutral && captures?.invoke() != true

    override fun onSteamPadConnected(pad: ExternalController) {
        pads[pad.deviceId] = pad
        navigation.onSteamPadConnected(pad)
        settings?.onSteamPadConnected(pad)
    }

    override fun onSteamPadDisconnected(pad: ExternalController) {
        scrollPositions.remove(pad.deviceId)
        pads.remove(pad.deviceId)
        navigation.onSteamPadDisconnected(pad)
        settings?.onSteamPadDisconnected(pad)
        if (pads.isEmpty()) pointer.clear()
    }

    override fun onSteamPadState(pad: ExternalController, guide: Boolean, quickAccess: Boolean, keys: IntArray) {
        if (!foreground) return
        val captured = captures?.invoke() == true
        settings?.onSteamPadState(pad, guide, quickAccess, keys)
        if (captured) {
            awaitNeutral = true
            scrollPositions.clear()
        }
        if (awaitNeutral && !captured) {
            awaitNeutral = keys.isNotEmpty() || kotlin.math.abs(pad.state.thumbLX) > 0.55f ||
                kotlin.math.abs(pad.state.thumbLY) > 0.55f || pad.state.triggerL > 0.5f || pad.state.triggerR > 0.5f
            navigation.clear()
            return
        }
        if (captured || !isSteamPadInputEnabled()) navigation.clear()
        else {
            if (leftTrackpadScroll && pad.steamLeftTouch) {
                val previous = scrollPositions.put(pad.deviceId, pad.steamLeftX to pad.steamLeftY)
                if (previous != null) pointer.scroll(
                    (previous.first - pad.steamLeftX) * 12f,
                    (pad.steamLeftY - previous.second) * 12f,
                )
            } else scrollPositions.remove(pad.deviceId)
            navigation.onSteamPadState(pad, guide, quickAccess, keys)
        }
    }

    override fun onSteamPadGyro(pad: ExternalController, x: Float, y: Float, z: Float, timestamp: Long) {
        if (foreground) settings?.onSteamPadGyro(pad, x, y, z, timestamp)
    }

    override fun onSteamPadBinding(binding: Binding, down: Boolean) = Unit
    override fun onSteamPadMouseMove(dx: Int, dy: Int) {
        if (isSteamPadInputEnabled()) pointer.move(dx, dy)
    }
    override fun onSteamPadMouseButton(secondary: Boolean, down: Boolean) {
        if (!down || isSteamPadInputEnabled()) pointer.button(secondary, down)
    }
}
