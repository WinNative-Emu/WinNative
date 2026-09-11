package com.winlator.cmod.shared.ui.controllertest

import android.content.Context
import android.view.KeyEvent
import com.winlator.cmod.R
import com.winlator.cmod.runtime.input.controls.Binding
import com.winlator.cmod.runtime.input.controls.ControlsProfile
import com.winlator.cmod.runtime.input.controls.ExternalController
import com.winlator.cmod.runtime.input.controls.ExternalControllerBinding

internal data class BindTarget(
    val id: String,
    val nameRes: Int?,
    val literalName: String?,
    val keyCode: Int,
    val native: Binding?,
)

internal val BIND_TARGETS: List<BindTarget> =
    listOf(
        BindTarget("a", null, "A", KeyEvent.KEYCODE_BUTTON_A, Binding.GAMEPAD_BUTTON_A),
        BindTarget("b", null, "B", KeyEvent.KEYCODE_BUTTON_B, Binding.GAMEPAD_BUTTON_B),
        BindTarget("x", null, "X", KeyEvent.KEYCODE_BUTTON_X, Binding.GAMEPAD_BUTTON_X),
        BindTarget("y", null, "Y", KeyEvent.KEYCODE_BUTTON_Y, Binding.GAMEPAD_BUTTON_Y),
        BindTarget("lb", null, "LB", KeyEvent.KEYCODE_BUTTON_L1, Binding.GAMEPAD_BUTTON_L1),
        BindTarget("rb", null, "RB", KeyEvent.KEYCODE_BUTTON_R1, Binding.GAMEPAD_BUTTON_R1),
        BindTarget("lt", null, "LT", KeyEvent.KEYCODE_BUTTON_L2, Binding.GAMEPAD_BUTTON_L2),
        BindTarget("rt", null, "RT", KeyEvent.KEYCODE_BUTTON_R2, Binding.GAMEPAD_BUTTON_R2),
        BindTarget(
            "l3",
            R.string.controller_bind_target_l3,
            null,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            Binding.GAMEPAD_BUTTON_L3,
        ),
        BindTarget(
            "r3",
            R.string.controller_bind_target_r3,
            null,
            KeyEvent.KEYCODE_BUTTON_THUMBR,
            Binding.GAMEPAD_BUTTON_R3,
        ),
        BindTarget(
            "start",
            R.string.controller_bind_target_start,
            null,
            KeyEvent.KEYCODE_BUTTON_START,
            Binding.GAMEPAD_BUTTON_START,
        ),
        BindTarget(
            "back",
            R.string.controller_bind_target_back,
            null,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            Binding.GAMEPAD_BUTTON_SELECT,
        ),
        BindTarget("guide", R.string.controller_bind_target_guide, null, KeyEvent.KEYCODE_BUTTON_MODE, null),
        BindTarget(
            "dup",
            R.string.controller_bind_target_dpad_up,
            null,
            KeyEvent.KEYCODE_DPAD_UP,
            Binding.GAMEPAD_DPAD_UP,
        ),
        BindTarget(
            "ddown",
            R.string.controller_bind_target_dpad_down,
            null,
            KeyEvent.KEYCODE_DPAD_DOWN,
            Binding.GAMEPAD_DPAD_DOWN,
        ),
        BindTarget(
            "dleft",
            R.string.controller_bind_target_dpad_left,
            null,
            KeyEvent.KEYCODE_DPAD_LEFT,
            Binding.GAMEPAD_DPAD_LEFT,
        ),
        BindTarget(
            "dright",
            R.string.controller_bind_target_dpad_right,
            null,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            Binding.GAMEPAD_DPAD_RIGHT,
        ),
        BindTarget(
            "lsu",
            R.string.controller_bind_target_lstick_up,
            null,
            ExternalControllerBinding.AXIS_Y_NEGATIVE.toInt(),
            Binding.GAMEPAD_LEFT_THUMB_UP,
        ),
        BindTarget(
            "lsd",
            R.string.controller_bind_target_lstick_down,
            null,
            ExternalControllerBinding.AXIS_Y_POSITIVE.toInt(),
            Binding.GAMEPAD_LEFT_THUMB_DOWN,
        ),
        BindTarget(
            "lsl",
            R.string.controller_bind_target_lstick_left,
            null,
            ExternalControllerBinding.AXIS_X_NEGATIVE.toInt(),
            Binding.GAMEPAD_LEFT_THUMB_LEFT,
        ),
        BindTarget(
            "lsr",
            R.string.controller_bind_target_lstick_right,
            null,
            ExternalControllerBinding.AXIS_X_POSITIVE.toInt(),
            Binding.GAMEPAD_LEFT_THUMB_RIGHT,
        ),
        BindTarget(
            "rsu",
            R.string.controller_bind_target_rstick_up,
            null,
            ExternalControllerBinding.AXIS_RZ_NEGATIVE.toInt(),
            Binding.GAMEPAD_RIGHT_THUMB_UP,
        ),
        BindTarget(
            "rsd",
            R.string.controller_bind_target_rstick_down,
            null,
            ExternalControllerBinding.AXIS_RZ_POSITIVE.toInt(),
            Binding.GAMEPAD_RIGHT_THUMB_DOWN,
        ),
        BindTarget(
            "rsl",
            R.string.controller_bind_target_rstick_left,
            null,
            ExternalControllerBinding.AXIS_Z_NEGATIVE.toInt(),
            Binding.GAMEPAD_RIGHT_THUMB_LEFT,
        ),
        BindTarget(
            "rsr",
            R.string.controller_bind_target_rstick_right,
            null,
            ExternalControllerBinding.AXIS_Z_POSITIVE.toInt(),
            Binding.GAMEPAD_RIGHT_THUMB_RIGHT,
        ),
    )

internal val BIND_BY_ID: Map<String, BindTarget> = BIND_TARGETS.associateBy { it.id }

internal fun bindTargetName(
    context: Context,
    target: BindTarget,
): String = target.nameRes?.let { context.getString(it) } ?: target.literalName ?: target.id

internal fun bindingLabelOrNull(
    controller: ExternalController?,
    id: String,
): String? {
    val t = BIND_BY_ID[id] ?: return null
    val b = controller?.getControllerBinding(t.keyCode) ?: return null
    return b.binding?.toString()
}

internal fun isBound(
    controller: ExternalController?,
    id: String,
): Boolean {
    val t = BIND_BY_ID[id] ?: return false
    return controller?.getControllerBinding(t.keyCode) != null
}

internal fun boundCount(controller: ExternalController?): Int {
    if (controller == null) return 0
    return BIND_TARGETS.count { controller.getControllerBinding(it.keyCode) != null }
}

internal fun setTarget(
    controller: ExternalController,
    profile: ControlsProfile,
    id: String,
    binding: Binding,
) {
    val t = BIND_BY_ID[id] ?: return
    if (t.keyCode == KeyEvent.KEYCODE_UNKNOWN) return
    val existing = controller.getControllerBinding(t.keyCode)
    if (existing == null) {
        val created = ExternalControllerBinding()
        created.setKeyCode(t.keyCode)
        created.binding = binding
        controller.addControllerBinding(created)
    } else {
        existing.binding = binding
    }
    profile.save()
}

internal fun clearTarget(
    controller: ExternalController,
    profile: ControlsProfile,
    id: String,
) {
    val t = BIND_BY_ID[id] ?: return
    val b = controller.getControllerBinding(t.keyCode) ?: return
    controller.removeControllerBinding(b)
    profile.save()
}

internal fun fillNative(
    controller: ExternalController,
    profile: ControlsProfile,
) {
    var changed = false
    for (t in BIND_TARGETS) {
        if (t.native == null) continue
        if (controller.getControllerBinding(t.keyCode) == null) {
            val b = ExternalControllerBinding()
            b.setKeyCode(t.keyCode)
            b.binding = t.native
            controller.addControllerBinding(b)
            changed = true
        }
    }
    if (changed) profile.save()
}

internal enum class BindCategory { KEYBOARD, MOUSE, XBOX, NONE }

internal fun categoryLabels(cat: BindCategory): Array<String> =
    when (cat) {
        BindCategory.KEYBOARD -> Binding.keyboardBindingLabels()
        BindCategory.MOUSE -> Binding.mouseBindingLabels()
        BindCategory.XBOX -> Binding.gamepadBindingLabels()
        BindCategory.NONE -> arrayOf(Binding.NONE.toString())
    }

internal fun categoryValues(cat: BindCategory): Array<Binding> =
    when (cat) {
        BindCategory.KEYBOARD -> Binding.keyboardBindingValues()
        BindCategory.MOUSE -> Binding.mouseBindingValues()
        BindCategory.XBOX -> Binding.gamepadBindingValues()
        BindCategory.NONE -> arrayOf(Binding.NONE)
    }

internal fun resolveBindController(
    profile: ControlsProfile,
    controllerId: String?,
    controllerName: String?,
): ExternalController? {
    val id =
        controllerId?.takeIf { it.isNotEmpty() }
            ?: ExternalController.getControllers().firstOrNull()?.id
            ?: return profile.loadControllers().firstOrNull { it != null }
    profile.getController(id)?.let { return it }
    val controller =
        ExternalController.getController(id)
            ?: ExternalController().apply {
                setId(id)
                name = controllerName?.takeIf { it.isNotEmpty() } ?: id
            }
    profile.putController(controller)
    return controller
}
