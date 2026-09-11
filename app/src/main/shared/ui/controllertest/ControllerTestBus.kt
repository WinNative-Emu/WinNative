package com.winlator.cmod.shared.ui.controllertest

import android.view.InputDevice
import com.winlator.cmod.runtime.input.controls.ExternalController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ControllerTestSnapshot
    @JvmOverloads
    constructor(
        val buttons: Int,
        val dpadUp: Boolean,
        val dpadRight: Boolean,
        val dpadDown: Boolean,
        val dpadLeft: Boolean,
        val thumbLX: Float,
        val thumbLY: Float,
        val thumbRX: Float,
        val thumbRY: Float,
        val triggerL: Float,
        val triggerR: Float,
        val guide: Boolean,
        val deviceId: Int,
        val deviceName: String,
        val deviceDescriptor: String,
        val padArt: Int,
        val batteryPct: Int,
        val hasVibrator: Boolean,
        val quickAccess: Boolean = false,
    )

object ControllerTestBus {
    private val _snapshot = MutableStateFlow<ControllerTestSnapshot?>(null)
    val snapshot: StateFlow<ControllerTestSnapshot?> = _snapshot

    @Volatile
    private var active: Boolean = false

    @Volatile
    private var dialogOpen: Boolean = false

    @JvmField
    @Volatile
    var onIdentify: Runnable? = null

    @JvmStatic
    fun isActive(): Boolean = active

    @JvmStatic
    fun isDialogOpen(): Boolean = dialogOpen

    @JvmStatic
    fun currentDeviceId(): Int = _snapshot.value?.deviceId ?: Int.MIN_VALUE

    @JvmStatic
    fun setActive(value: Boolean) {
        active = value
    }

    @JvmStatic
    fun setDialogOpen(value: Boolean) {
        dialogOpen = value
        if (!value) {
            active = false
            _snapshot.value = null
        }
    }

    private fun batteryPercent(device: InputDevice?): Int {
        if (device == null || android.os.Build.VERSION.SDK_INT < 29) return -1
        val state = device.batteryState ?: return -1
        if (!state.isPresent) return -1
        val capacity = state.capacity
        if (capacity.isNaN() || capacity < 0f) return -1
        return (capacity * 100f).toInt().coerceIn(0, 100)
    }

    @JvmStatic
    fun publish(
        controller: ExternalController,
        device: InputDevice?,
        guideDown: Boolean,
    ) {
        if (!dialogOpen) return
        val state = controller.state
        _snapshot.value =
            ControllerTestSnapshot(
                buttons = state.buttons.toInt() and 0xFFFF,
                dpadUp = state.dpad[0],
                dpadRight = state.dpad[1],
                dpadDown = state.dpad[2],
                dpadLeft = state.dpad[3],
                thumbLX = state.thumbLX,
                thumbLY = state.thumbLY,
                thumbRX = state.thumbRX,
                thumbRY = state.thumbRY,
                triggerL = state.triggerL,
                triggerR = state.triggerR,
                guide = guideDown,
                deviceId = controller.deviceId,
                deviceName = device?.name ?: controller.name ?: "",
                deviceDescriptor = device?.descriptor ?: controller.id ?: "",
                padArt = classifyPadArt(device).ordinal,
                batteryPct = batteryPercent(device),
                hasVibrator = device?.vibrator?.hasVibrator() == true,
            )
    }

    @JvmStatic
    fun publishSteamPad(
        pad: ExternalController,
        guideDown: Boolean,
        quickAccessDown: Boolean,
    ) {
        if (!dialogOpen) return
        val state = pad.state
        _snapshot.value =
            ControllerTestSnapshot(
                buttons = state.buttons.toInt() and 0xFFFF,
                dpadUp = state.dpad[0],
                dpadRight = state.dpad[1],
                dpadDown = state.dpad[2],
                dpadLeft = state.dpad[3],
                thumbLX = state.thumbLX,
                thumbLY = state.thumbLY,
                thumbRX = state.thumbRX,
                thumbRY = state.thumbRY,
                triggerL = state.triggerL,
                triggerR = state.triggerR,
                guide = guideDown,
                deviceId = pad.deviceId,
                deviceName = pad.name ?: "Steam Controller",
                deviceDescriptor = pad.id ?: "",
                padArt = PadArt.STEAM.ordinal,
                batteryPct = -1,
                hasVibrator = true,
                quickAccess = quickAccessDown,
            )
    }
}
