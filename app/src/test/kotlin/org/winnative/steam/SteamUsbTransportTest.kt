package org.winnative.steam

import android.app.Application
import android.hardware.usb.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class SteamUsbTransportTest {
    private val manager = mock(HIDDeviceManager::class.java)
    private val usb = mock(UsbManager::class.java)
    private val device = mock(UsbDevice::class.java)
    private val iface = mock(UsbInterface::class.java)
    private val connection = mock(UsbDeviceConnection::class.java)
    private val endpoint = mock(UsbEndpoint::class.java)

    private fun controller(): HIDDeviceUSB {
        `when`(manager.getUSBManager()).thenReturn(usb)
        `when`(device.getInterface(0)).thenReturn(iface)
        `when`(device.deviceName).thenReturn("/dev/bus/usb/001/002")
        `when`(device.vendorId).thenReturn(0x28de)
        `when`(iface.id).thenReturn(2)
        `when`(iface.endpointCount).thenReturn(1)
        `when`(iface.getEndpoint(0)).thenReturn(endpoint)
        `when`(endpoint.direction).thenReturn(UsbConstants.USB_DIR_IN)
        `when`(endpoint.maxPacketSize).thenReturn(64)
        `when`(usb.openDevice(device)).thenReturn(connection)
        `when`(connection.claimInterface(iface, true)).thenReturn(true)
        `when`(connection.bulkTransfer(any(), any(), anyInt(), anyInt())).thenAnswer { Thread.sleep(5); 0 }
        return HIDDeviceUSB(manager, device, 0)
    }

    @Test fun repeatedOpenDoesNotCreateAnotherConnectionOrClaim() {
        val pad = controller()
        try {
            assertTrue(pad.open())
            assertTrue(pad.open())
            verify(usb, times(1)).openDevice(device)
            verify(connection, times(1)).claimInterface(iface, true)
        } finally { pad.close() }
    }

    @Test fun permissionGrantOnlySelectsRequestedInterfacesOnce() {
        val keyboard = mock(HIDDevice::class.java)
        val gamepad = mock(HIDDevice::class.java)
        `when`(keyboard.device).thenReturn(device)
        `when`(keyboard.id).thenReturn(11)
        `when`(gamepad.device).thenReturn(device)
        `when`(gamepad.id).thenReturn(22)
        ReflectionHelpers.setField(manager, "mDevicesById", hashMapOf(11 to keyboard, 22 to gamepad))
        ReflectionHelpers.setField(manager, "mPendingUsbOpens", hashSetOf(22))
        ReflectionHelpers.setField(manager, "mUsbPermissionRequests", hashSetOf(device))
        val take = HIDDeviceManager::class.java.getDeclaredMethod("takePendingUsbDevices", UsbDevice::class.java).apply { isAccessible = true }
        assertEquals(listOf(gamepad), take.invoke(manager, device))
        assertEquals(emptyList<HIDDevice>(), take.invoke(manager, device))
    }

    @Test fun shortFeatureWriteIsFailureAndReportIdIsUnsigned() {
        val pad = controller()
        try {
            assertTrue(pad.open())
            `when`(connection.controlTransfer(anyInt(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), anyInt())).thenReturn(2)
            assertEquals(-1, pad.writeReport(byteArrayOf(0xc0.toByte(), 1, 2, 3), true))
            verify(connection).controlTransfer(eq(0x21), eq(9), eq(0x3c0), eq(2), any(), eq(0), eq(4), eq(1000))
        } finally { pad.close() }
    }

    @Test fun valveBootKeyboardAndMouseAreExcludedButControllerInterfaceRemains() {
        controller()
        `when`(iface.interfaceClass).thenReturn(UsbConstants.USB_CLASS_HID)
        val method = HIDDeviceManager::class.java.getDeclaredMethod("isHIDDeviceInterface", UsbDevice::class.java, UsbInterface::class.java).apply { isAccessible = true }
        `when`(iface.interfaceSubclass).thenReturn(1)
        `when`(iface.interfaceProtocol).thenReturn(1)
        assertEquals(false, method.invoke(manager, device, iface))
        `when`(iface.interfaceProtocol).thenReturn(2)
        assertEquals(false, method.invoke(manager, device, iface))
        `when`(iface.interfaceSubclass).thenReturn(0)
        `when`(iface.interfaceProtocol).thenReturn(0)
        assertEquals(true, method.invoke(manager, device, iface))
        `when`(device.vendorId).thenReturn(0x1234)
        `when`(iface.interfaceSubclass).thenReturn(1)
        `when`(iface.interfaceProtocol).thenReturn(1)
        assertEquals(true, method.invoke(manager, device, iface))
    }
}
