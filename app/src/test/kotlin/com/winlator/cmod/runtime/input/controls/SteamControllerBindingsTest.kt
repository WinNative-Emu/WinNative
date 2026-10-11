package com.winlator.cmod.runtime.input.controls

import android.app.Application
import android.content.Context
import android.view.KeyEvent
import com.winlator.cmod.runtime.input.ui.InputControlsView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SteamControllerBindingsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val profile = ControlsProfile(context, 987654)
    private val pad = ExternalController().apply { id = "sdl:test"; setDeviceId(-1001) }
    private val mapped = ExternalController().apply { id = pad.id; setDeviceId(pad.deviceId) }
    private val view = RecordingView(context)

    private class RecordingView(context: Context) : InputControlsView(context) {
        val keys = mutableListOf<Pair<Binding, Boolean>>()
        override fun handleInputEvent(controller: ExternalController?, binding: Binding, down: Boolean, offset: Float, sendUpdate: Boolean) {
            if (binding.isGamepad) super.handleInputEvent(controller, binding, down, offset, sendUpdate)
            else keys.add(binding to down)
        }
    }

    private fun bind(key: Int, target: Binding) {
        mapped.addControllerBinding(ExternalControllerBinding().apply { setKeyCode(key); binding = target })
        profile.putController(mapped)
        view.profile = profile
    }

    @Test fun stickMagnitudeUpdatesWithoutCrossingDeadzone() {
        bind(ExternalControllerBinding.AXIS_X_POSITIVE.toInt(), Binding.GAMEPAD_LEFT_THUMB_RIGHT)
        pad.state.thumbLX = 0.4f
        assertTrue(view.onSteamPadState(pad, intArrayOf()))
        assertEquals(0.4f, pad.remappedState.thumbLX, 0.001f)
        pad.state.thumbLX = 0.9f
        view.onSteamPadState(pad, intArrayOf())
        assertEquals(0.9f, pad.remappedState.thumbLX, 0.001f)
    }

    @Test fun triggerIsAnalogAndReleasedOnDisconnect() {
        bind(KeyEvent.KEYCODE_BUTTON_L2, Binding.GAMEPAD_BUTTON_R2)
        pad.state.triggerL = 0.45f
        view.onSteamPadState(pad, intArrayOf())
        assertEquals(0.45f, pad.remappedState.triggerR, 0.001f)
        view.onSteamPadDisconnected(pad)
        assertEquals(0f, mapped.remappedState.triggerR, 0f)
    }

    @Test fun negativeStickAxisMapsToPositiveTriggerMagnitude() {
        bind(ExternalControllerBinding.AXIS_X_NEGATIVE.toInt(), Binding.GAMEPAD_BUTTON_R2)
        pad.state.thumbLX = -0.65f
        view.onSteamPadState(pad, intArrayOf())
        assertEquals(0.65f, pad.remappedState.triggerR, 0.001f)
    }

    @Test fun changingAxisMagnitudeDoesNotRepeatKeyboardPress() {
        bind(ExternalControllerBinding.AXIS_X_POSITIVE.toInt(), Binding.KEY_W)
        pad.state.thumbLX = 0.4f
        view.onSteamPadState(pad, intArrayOf())
        pad.state.thumbLX = 0.9f
        view.onSteamPadState(pad, intArrayOf())
        assertEquals(listOf(Binding.KEY_W to true), view.keys)
    }

    @Test fun profileChangeReleasesKeyboardBoundToAxisAndTrigger() {
        bind(ExternalControllerBinding.AXIS_X_POSITIVE.toInt(), Binding.KEY_W)
        bind(KeyEvent.KEYCODE_BUTTON_L2, Binding.KEY_SPACE)
        pad.state.thumbLX = 0.7f
        pad.state.triggerL = 0.7f
        view.onSteamPadState(pad, intArrayOf())
        view.profile = null
        assertTrue(view.keys.contains(Binding.KEY_W to false))
        assertTrue(view.keys.contains(Binding.KEY_SPACE to false))
    }

    @Test fun twoButtonsSharingTargetDoNotReleaseEachOther() {
        bind(KeyEvent.KEYCODE_BUTTON_A, Binding.KEY_SPACE)
        bind(KeyEvent.KEYCODE_BUTTON_B, Binding.KEY_SPACE)
        view.onSteamPadState(pad, intArrayOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B))
        view.onSteamPadState(pad, intArrayOf(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(listOf(Binding.KEY_SPACE to true), view.keys)
        view.onSteamPadState(pad, intArrayOf())
        assertEquals(listOf(Binding.KEY_SPACE to true, Binding.KEY_SPACE to false), view.keys)
    }

    @Test fun dpadAndExtraButtonsReachProfileBindings() {
        bind(KeyEvent.KEYCODE_DPAD_UP, Binding.GAMEPAD_BUTTON_A)
        bind(KeyEvent.KEYCODE_BUTTON_5, Binding.GAMEPAD_BUTTON_B)
        pad.state.dpad[0] = true
        view.onSteamPadState(pad, intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BUTTON_5))
        assertTrue(pad.remappedState.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
        assertTrue(pad.remappedState.isPressed(ExternalController.IDX_BUTTON_B.toInt()))
    }

    @Test fun disconnectingOnePadDoesNotReleaseAnotherPadsKey() {
        bind(KeyEvent.KEYCODE_BUTTON_A, Binding.KEY_SPACE)
        val second = ExternalController().apply { id = "sdl:second"; setDeviceId(-1002) }
        val secondMapped = ExternalController().apply {
            id = second.id
            setDeviceId(second.deviceId)
            addControllerBinding(ExternalControllerBinding().apply {
                setKeyCode(KeyEvent.KEYCODE_BUTTON_A)
                binding = Binding.KEY_SPACE
            })
        }
        profile.putController(secondMapped)
        view.onSteamPadState(pad, intArrayOf(KeyEvent.KEYCODE_BUTTON_A))
        view.onSteamPadState(second, intArrayOf(KeyEvent.KEYCODE_BUTTON_A))
        view.onSteamPadDisconnected(pad)
        assertEquals(listOf(Binding.KEY_SPACE to true), view.keys)
        view.onSteamPadDisconnected(second)
        assertEquals(listOf(Binding.KEY_SPACE to true, Binding.KEY_SPACE to false), view.keys)
    }
}
