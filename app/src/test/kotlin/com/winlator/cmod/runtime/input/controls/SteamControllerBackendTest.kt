package com.winlator.cmod.runtime.input.controls

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SteamControllerBackendTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val backends = mutableListOf<SteamControllerBackend>()
    private val transports = mutableListOf<FakeTransport>()

    private class FakeTransport : SteamControllerBackend.Transport {
        val reports = LinkedBlockingQueue<Pair<Int, Int>>()
        val polled = AtomicInteger()
        val shutdownStarted = CountDownLatch(1)
        var allowShutdown = CountDownLatch(0)
        @Volatile var setupCount = 0
        @Volatile var releases = 0
        @Volatile var rumbleThread: Thread? = null
        @Volatile var fail = false
        private var current = 1 to 0
        override fun load() = true
        override fun setup(activity: Activity) { setupCount++ }
        override fun init(bluetooth: Boolean) = true
        override fun name(id: Int) = "Steam Controller"
        override fun path(id: Int) = "test:$id"
        override fun poll(ints: IntArray, floats: FloatArray): Int {
            if (fail) throw IllegalStateException("test transport failure")
            reports.poll(10, TimeUnit.MILLISECONDS)?.let { current = it; polled.incrementAndGet() }
            val (count, buttons) = current
            if (count > 0) {
                ints[0] = 1
                ints[1] = buttons
                ints[2] = 2 shl 8
                floats[6] = 1f
                floats[7] = 0.5f
                floats[8] = 0.5f
            }
            return count
        }
        override fun rumble(id: Int, low: Int, high: Int, durationMs: Int) { rumbleThread = Thread.currentThread() }
        override fun shutdown() { shutdownStarted.countDown(); allowShutdown.await(3, TimeUnit.SECONDS) }
        override fun release() { releases++ }
    }

    private class Events : SteamControllerBackend.Listener {
        val states = mutableListOf<List<Int>>()
        val bindings = mutableListOf<Pair<Binding, Boolean>>()
        val clicks = mutableListOf<Boolean>()
        var connected = 0
        var disconnected = 0
        var enabled = true
        override fun isSteamPadInputEnabled() = enabled
        override fun onSteamPadConnected(pad: ExternalController) { connected++ }
        override fun onSteamPadDisconnected(pad: ExternalController) { disconnected++ }
        override fun onSteamPadState(pad: ExternalController, guideDown: Boolean, quickAccessDown: Boolean, pressedKeyCodes: IntArray) {
            states.add(pressedKeyCodes.toList())
        }
        override fun onSteamPadBinding(binding: Binding, down: Boolean) { bindings.add(binding to down) }
        override fun onSteamPadMouseMove(dx: Int, dy: Int) = Unit
        override fun onSteamPadMouseButton(secondary: Boolean, down: Boolean) { clicks.add(down) }
    }

    private fun start(events: Events = Events(), transport: FakeTransport = FakeTransport()): SteamControllerBackend {
        val paddles = arrayOf(Binding.KEY_SPACE, Binding.KEY_SPACE)
        return SteamControllerBackend(activity, SteamControllerBackend.TRACKPAD_MOUSE_RIGHT, paddles, events, transport).also {
            transports.add(transport)
            backends.add(it)
            assertTrue(it.start())
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("condition timed out", condition())
    }

    @After fun cleanup() {
        backends.forEach { it.stop() }
        transports.forEach { it.allowShutdown.countDown() }
        await { transports.all { it.releases == it.setupCount } }
        activity.finish()
    }

    @Test fun replacementWaitsForNativeShutdownWithoutBlockingUi() {
        val first = FakeTransport().apply { allowShutdown = CountDownLatch(1) }
        val backend = start(transport = first)
        await { first.setupCount == 1 }
        val began = System.nanoTime()
        backend.stop()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 100)
        val second = FakeTransport()
        start(transport = second)
        assertTrue(first.shutdownStarted.await(2, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, second.setupCount)
        first.allowShutdown.countDown()
        await { second.setupCount == 1 }
        assertEquals(1, first.releases)
    }

    @Test fun queuedPressAndReleaseBothReachListener() {
        val events = Events()
        val transport = FakeTransport()
        start(events, transport)
        await { events.connected == 1 }
        transport.reports.add(1 to 1)
        transport.reports.add(1 to 0)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (transport.polled.get() < 2 && System.nanoTime() < deadline) Thread.sleep(5)
        await { events.states.size >= 3 }
        assertEquals(listOf(emptyList<Int>(), listOf(KeyEvent.KEYCODE_BUTTON_A), emptyList<Int>()), events.states)
    }

    @Test fun returningFromControlsEditorRestoresGameInputAfterEditorShutdown() {
        val gameEvents = Events()
        val gameTransport = FakeTransport()
        val game = start(gameEvents, gameTransport)
        await { gameEvents.connected == 1 }
        game.stop()

        val editorEvents = Events()
        val editorTransport = FakeTransport().apply { allowShutdown = CountDownLatch(1) }
        val editor = start(editorEvents, editorTransport)
        await { editorEvents.connected == 1 }
        assertEquals(1, gameEvents.disconnected)
        editor.stop()

        val resumedEvents = Events()
        val resumedTransport = FakeTransport()
        val resumed = start(resumedEvents, resumedTransport)
        assertTrue(editorTransport.shutdownStarted.await(2, TimeUnit.SECONDS))
        assertEquals(0, resumedTransport.setupCount)
        editorTransport.allowShutdown.countDown()
        await { resumedEvents.connected == 1 }
        assertEquals(1, editorTransport.releases)
        resumedTransport.reports.add(1 to ((1 shl 0) or (1 shl 17)))
        await { resumedEvents.states.lastOrNull()?.contains(KeyEvent.KEYCODE_BUTTON_A) == true }
        assertEquals(listOf(Binding.KEY_SPACE to true), resumedEvents.bindings)
        resumed.rumble(-1001, 100, 100, 100)
        await { resumedTransport.rumbleThread != null }
    }

    @Test fun leavingEditorBeforeItAcquiresSdlDoesNotLoseGameOwnership() {
        val gameTransport = FakeTransport().apply { allowShutdown = CountDownLatch(1) }
        val game = start(transport = gameTransport)
        await { gameTransport.setupCount == 1 }
        game.stop()
        val editorTransport = FakeTransport()
        val editor = start(transport = editorTransport)
        editor.stop()
        val resumedEvents = Events()
        val resumedTransport = FakeTransport()
        start(resumedEvents, resumedTransport)
        gameTransport.allowShutdown.countDown()
        await { resumedEvents.connected == 1 }
        assertEquals(0, editorTransport.setupCount)
        assertEquals(0, editorTransport.releases)
        assertEquals(1, resumedTransport.setupCount)
    }

    @Test fun stopReleasesPaddlesAndTouchpadClick() {
        val events = Events()
        val transport = FakeTransport()
        val backend = start(events, transport)
        transport.reports.add(1 to ((1 shl 17) or (1 shl 20)))
        await { events.clicks == listOf(true) }
        backend.stop()
        assertEquals(listOf(true, false), events.clicks)
        assertEquals(listOf(Binding.KEY_SPACE to true, Binding.KEY_SPACE to false), events.bindings)
        assertEquals(1, events.disconnected)
    }

    @Test fun sharedPaddleBindingStaysHeldUntilBothPaddlesRelease() {
        val events = Events()
        val transport = FakeTransport()
        start(events, transport)
        transport.reports.add(1 to ((1 shl 17) or (1 shl 19)))
        await { events.bindings.size == 1 }
        transport.reports.add(1 to (1 shl 19))
        await { events.states.lastOrNull()?.contains(KeyEvent.KEYCODE_BUTTON_2) == true && events.states.last().size == 1 }
        assertEquals(listOf(Binding.KEY_SPACE to true), events.bindings)
        transport.reports.add(1 to 0)
        await { events.bindings.size == 2 }
        assertEquals(Binding.KEY_SPACE to false, events.bindings.last())
    }

    @Test fun disablingInputReleasesHeldOutputsAndResumeRestoresThem() {
        val events = Events()
        val transport = FakeTransport()
        val backend = start(events, transport)
        transport.reports.add(1 to (1 shl 20))
        await { events.clicks == listOf(true) }
        events.enabled = false
        backend.publishCurrentState()
        assertEquals(listOf(true, false), events.clicks)
        events.enabled = true
        backend.publishCurrentState()
        assertEquals(listOf(true, false, true), events.clicks)
    }

    @Test fun rumbleRunsOnPollThreadAndPollFailureDisconnects() {
        val events = Events()
        val transport = FakeTransport()
        val backend = start(events, transport)
        await { events.connected == 1 }
        backend.rumble(-1001, 100, 100, 100)
        await { transport.rumbleThread != null }
        assertNotSame(Looper.getMainLooper().thread, transport.rumbleThread)
        transport.fail = true
        await { transport.releases == 1 }
        assertEquals(1, events.disconnected)
    }
}
