package com.winlator.cmod.runtime.input

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.cmod.runtime.input.controls.SteamControllerBackend
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.winnative.steam.HIDDeviceManager
import org.winnative.steam.SDL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SteamControllerNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun isolatedJniCanInitializePollAndRestart() {
        val backend = SteamControllerBackend::class.java
        val init = backend.getDeclaredMethod("nativeInit", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val poll = backend.getDeclaredMethod("nativePoll", IntArray::class.java, FloatArray::class.java).apply { isAccessible = true }
        val shutdown = backend.getDeclaredMethod("nativeShutdown").apply { isAccessible = true }
        compose.runOnUiThread {
            System.loadLibrary("SDL3steam")
            System.loadLibrary("steamctrl")
            SDL.setupJNI()
        }
        assertNotEquals(SDL::class.java, Class.forName("org.libsdl.app.SDL"))
        val worker = Executors.newSingleThreadExecutor()
        try {
            repeat(3) {
                lateinit var manager: HIDDeviceManager
                compose.runOnUiThread {
                    SDL.initialize()
                    SDL.setContext(compose.activity)
                    manager = HIDDeviceManager.acquire(compose.activity)
                }
                try {
                    worker.submit {
                        try {
                            assertEquals(true, init.invoke(null, false))
                            assertTrue((poll.invoke(null, IntArray(16), FloatArray(64)) as Int) in 0..4)
                            assertEquals(-1, poll.invoke(null, IntArray(1), FloatArray(1)))
                        } finally {
                            shutdown.invoke(null)
                        }
                    }.get(15, TimeUnit.SECONDS)
                } finally {
                    compose.runOnUiThread {
                        HIDDeviceManager.release(manager)
                        SDL.setContext(null)
                    }
                }
            }
        } finally {
            worker.shutdownNow()
        }
    }
}
