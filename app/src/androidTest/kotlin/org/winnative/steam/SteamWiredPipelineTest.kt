package org.winnative.steam

import android.hardware.usb.UsbDevice
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.cmod.runtime.input.controls.SteamControllerBackend
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SteamWiredPipelineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class WiredPad(private val manager: HIDDeviceManager) : HIDDevice {
        @Volatile var requested = false
        @Volatile var granted = false
        override fun getId() = 4242
        override fun getVendorId() = 0x28de
        override fun getProductId() = 0x1302
        override fun getSerialNumber() = "test"
        override fun getVersion() = 0
        override fun getManufacturerName() = "Valve"
        override fun getProductName() = "Steam Controller"
        override fun getDevice(): UsbDevice? = null
        override fun open(): Boolean {
            if (!granted) {
                manager.HIDDeviceOpenPending(id)
                requested = true
                return false
            }
            return true
        }
        override fun writeReport(report: ByteArray, feature: Boolean) = report.size
        override fun readReport(report: ByteArray, feature: Boolean) = false
        override fun setFrozen(frozen: Boolean) = Unit
        override fun close() = Unit
        override fun shutdown() = Unit
    }

    @Test fun permittedWiredControllerReappearsAndReportsButtonsAndTrackpad() {
        val backend = SteamControllerBackend::class.java
        val init = backend.getDeclaredMethod("nativeInit", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val poll = backend.getDeclaredMethod("nativePoll", IntArray::class.java, FloatArray::class.java).apply { isAccessible = true }
        val shutdown = backend.getDeclaredMethod("nativeShutdown").apply { isAccessible = true }
        lateinit var manager: HIDDeviceManager
        compose.runOnUiThread {
            System.loadLibrary("SDL3steam")
            System.loadLibrary("steamctrl")
            SDL.setupJNI()
            SDL.initialize()
            SDL.setContext(compose.activity)
            manager = HIDDeviceManager.acquire(compose.activity)
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                try {
                    assertEquals(true, init.invoke(null, false))
                    val pad = WiredPad(manager)
                    val mapField = HIDDeviceManager::class.java.getDeclaredField("mDevicesById").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val devices = mapField.get(manager) as MutableMap<Int, HIDDevice>
                    synchronized(manager) { devices[pad.id] = pad }
                    manager.HIDDeviceConnected(pad.id, "test:wired", pad.vendorId, pad.productId,
                        pad.serialNumber, 0, pad.manufacturerName, pad.productName, 2, 3, 0, 0, false, 0)
                    val ints = IntArray(16)
                    val floats = FloatArray(64)
                    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!pad.requested && System.nanoTime() < deadline) {
                        poll.invoke(null, ints, floats)
                        Thread.sleep(10)
                    }
                    assertTrue("SDL must request access", pad.requested)
                    pad.granted = true
                    manager.HIDDeviceOpenResult(pad.id, true)
                    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                    var count = 0
                    while (count == 0 && System.nanoTime() < deadline) {
                        count = poll.invoke(null, ints, floats) as Int
                        Thread.sleep(10)
                    }
                    assertEquals("Permission completion must re-enumerate the controller", 1, count)
                    val report = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
                    report.put(0, 0x42)
                    report.putInt(2, 1 or 0x00200000)
                    report.putShort(24, 16384)
                    report.putShort(26, 0)
                    manager.HIDDeviceInputReport(pad.id, report.array())
                    poll.invoke(null, ints, floats)
                    assertEquals(1, ints[1] and 1)
                    assertEquals(2, ints[2] shr 8)
                    assertEquals(1f, floats[6], 0f)
                    assertTrue("Trackpad coordinates must reach the bridge", floats[7] > 0.7f)
                } finally { shutdown.invoke(null) }
            }.get(20, TimeUnit.SECONDS)
        } finally {
            worker.shutdownNow()
            compose.runOnUiThread {
                HIDDeviceManager.release(manager)
                SDL.setContext(null)
            }
        }
    }
}
