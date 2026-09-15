package org.winnative.steam

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class SteamBluetoothTransportTest {
    private val manager = mock(HIDDeviceManager::class.java)
    private val device = mock(BluetoothDevice::class.java)
    private val gatt = mock(BluetoothGatt::class.java)
    private val characteristic = BluetoothGattCharacteristic(
        HIDDeviceBLESteamController.reportCharacteristic,
        BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE,
    )

    private fun controller(): HIDDeviceBLESteamController {
        `when`(manager.context).thenReturn(RuntimeEnvironment.getApplication())
        `when`(device.name).thenReturn("Steam Ctrl")
        `when`(device.address).thenReturn("00:11:22:33:44:55")
        `when`(device.connectGatt(any(), anyBoolean(), any(), anyInt())).thenReturn(gatt)
        val service = BluetoothGattService(HIDDeviceBLESteamController.steamControllerService, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        `when`(gatt.getService(HIDDeviceBLESteamController.steamControllerService)).thenReturn(service)
        `when`(gatt.writeCharacteristic(any())).thenReturn(true)
        return HIDDeviceBLESteamController(manager, device).also {
            ReflectionHelpers.setField(it, "mIsRegistered", true)
        }
    }

    @Test fun featureReportRetainsFinalPayloadByte() {
        val controller = controller()
        assertEquals(4, controller.writeReport(byteArrayOf(0, 1, 2, 3), true))
        shadowOf(Looper.getMainLooper()).idle()
        assertArrayEquals(byteArrayOf(1, 2, 3), characteristic.value)
        controller.shutdown()
    }

    @Test fun callbacksAfterShutdownDoNotDereferenceReleasedManager() {
        val controller = controller()
        controller.shutdown()
        controller.onCharacteristicChanged(gatt, characteristic, byteArrayOf(1, 2, 3))
        controller.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        controller.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ReflectionHelpers.getField<Any?>(controller, "mManager"))
        assertEquals(-1, controller.writeReport(byteArrayOf(0, 1), true))
    }

    @Test fun failedWriteDisconnectsInsteadOfWedgingQueue() {
        val controller = controller()
        `when`(gatt.writeCharacteristic(any())).thenReturn(false)
        controller.writeReport(byteArrayOf(0, 1, 2), true)
        shadowOf(Looper.getMainLooper()).idle()
        verify(manager).disconnectBluetoothDevice(device)
        verify(manager).retryBluetoothDevice(device)
        controller.shutdown()
    }

    @Test fun missingCallbackTimesOutAndDisconnects() {
        val controller = controller()
        controller.writeReport(byteArrayOf(0, 1, 2), true)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        verify(manager).disconnectBluetoothDevice(device)
        verify(manager).retryBluetoothDevice(device)
        controller.shutdown()
    }

    @Test fun callbackForOldConnectionCannotFinishCurrentOperation() {
        val controller = controller()
        controller.writeReport(byteArrayOf(0, 1, 2), true)
        shadowOf(Looper.getMainLooper()).idle()
        controller.onCharacteristicWrite(mock(BluetoothGatt::class.java), characteristic, BluetoothGatt.GATT_SUCCESS)
        assertNotNull(ReflectionHelpers.getField<Any?>(controller, "mCurrentOperation"))
        controller.shutdown()
    }

    @Test fun failedServiceDiscoveryRetriesConnection() {
        val controller = controller()
        controller.onServicesDiscovered(gatt, BluetoothGatt.GATT_FAILURE)
        verify(manager).disconnectBluetoothDevice(device)
        verify(manager).retryBluetoothDevice(device)
        controller.shutdown()
    }

    @Test fun tritonNegotiatesMtuBeforeEnablingNotifications() {
        val controller = controller()
        ReflectionHelpers.setField(controller, "mIsRegistered", false)
        ReflectionHelpers.setField(controller, "mIsConnected", true)
        val service = gatt.getService(HIDDeviceBLESteamController.steamControllerService)
        val input = BluetoothGattCharacteristic(HIDDeviceBLESteamController.inputCharacteristicTriton_0x47,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        input.addDescriptor(BluetoothGattDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(input)
        `when`(gatt.services).thenReturn(listOf(service))
        `when`(gatt.requestMtu(517)).thenReturn(true)
        `when`(gatt.setCharacteristicNotification(any(), anyBoolean())).thenReturn(true)
        `when`(gatt.writeDescriptor(any())).thenReturn(true)
        controller.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        verify(gatt).requestMtu(517)
        verify(gatt, never()).writeDescriptor(any())
        controller.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        verify(gatt).writeDescriptor(any())
        controller.shutdown()
    }

    @Test fun missingMtuCallbackTimesOutSetup() {
        val controller = controller()
        ReflectionHelpers.setField(controller, "mIsRegistered", false)
        val service = gatt.getService(HIDDeviceBLESteamController.steamControllerService)
        `when`(gatt.services).thenReturn(listOf(service))
        `when`(gatt.requestMtu(517)).thenReturn(true)
        controller.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        verify(manager).disconnectBluetoothDevice(device)
        verify(manager).retryBluetoothDevice(device)
        controller.shutdown()
    }
}
