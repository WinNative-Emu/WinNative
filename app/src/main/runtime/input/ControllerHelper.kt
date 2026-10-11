package com.winlator.cmod.runtime.input
import android.view.InputDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object ControllerHelper {
    private const val SONY_VENDOR_ID = 0x054C
    private var steamConnected by mutableStateOf(false)

    @JvmStatic
    fun setSteamControllerConnected(connected: Boolean) {
        steamConnected = connected
    }

    fun isControllerConnected(): Boolean {
        if (steamConnected) return true
        val deviceIds = InputDevice.getDeviceIds()
        for (deviceId in deviceIds) {
            val device = InputDevice.getDevice(deviceId) ?: continue
            val name = device.name?.lowercase() ?: ""
            if (name.contains("uinput-fpc") || name.contains("goodix_fp") || name.contains("uinput-")) {
                continue
            }
            val sources = device.sources
            if (!device.isVirtual &&
                (
                    (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                        (
                            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
                                (sources and InputDevice.SOURCE_MOUSE) == 0
                        )
                )
            ) {
                return true
            }
        }
        return false
    }

    fun isPlayStationController(): Boolean {
        val deviceIds = InputDevice.getDeviceIds()
        for (deviceId in deviceIds) {
            val device = InputDevice.getDevice(deviceId) ?: continue
            if (isPlayStationDevice(device)) return true
        }
        return false
    }

    fun isPlayStationDevice(device: InputDevice): Boolean {
        if (device.vendorId == SONY_VENDOR_ID) return true
        val name = device.name.lowercase()
        return name.contains("dualshock") || name.contains("playstation") ||
            name.contains("dualsense") || name.contains("wireless controller")
    }

    fun isPlayStationControllerById(deviceId: Int): Boolean {
        val device = InputDevice.getDevice(deviceId) ?: return false
        return isPlayStationDevice(device)
    }
}
