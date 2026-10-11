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
        val steamButtons: Int = 0,
        val touchpadCount: Int = 0,
        val leftTouch: Boolean = false,
        val rightTouch: Boolean = false,
        val leftTouchX: Float = 0f,
        val leftTouchY: Float = 0f,
        val rightTouchX: Float = 0f,
        val rightTouchY: Float = 0f,
        val hasGyro: Boolean = false,
        val gyroMoving: Boolean = false,
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

    @JvmStatic
    fun disconnect(deviceId: Int) {
        if (_snapshot.value?.deviceId == deviceId) _snapshot.value = null
    }

    @JvmStatic
    fun publishSteamGyro(pad: ExternalController, x: Float, y: Float, z: Float) {
        val current = _snapshot.value ?: return
        if (!dialogOpen || current.deviceId != pad.deviceId) return
        val moving = kotlin.math.abs(x) > 0.2f || kotlin.math.abs(y) > 0.2f || kotlin.math.abs(z) > 0.2f
        if (current.gyroMoving != moving) _snapshot.value = current.copy(gyroMoving = moving)
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
                hasVibrator = pad.steamHasRumble,
                quickAccess = quickAccessDown,
                steamButtons = pad.steamButtons,
                touchpadCount = pad.steamTouchpadCount,
                leftTouch = pad.steamLeftTouch,
                rightTouch = pad.steamRightTouch,
                leftTouchX = pad.steamLeftX,
                leftTouchY = pad.steamLeftY,
                rightTouchX = pad.steamRightX,
                rightTouchY = pad.steamRightY,
                hasGyro = pad.steamHasGyro,
                gyroMoving = _snapshot.value?.takeIf { it.deviceId == pad.deviceId }?.gyroMoving ?: false,
            )
    }
}
