package com.winlator.cmod.shared.ui.focus

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val AccentBorder = Color(0xFF00D7F5)

fun Modifier.controllerFocusBorder(
    cornerRadius: Dp = 10.dp,
    borderWidth: Dp = 2.dp,
    color: Color = AccentBorder,
): Modifier =
    composed {
        var focused by remember { mutableStateOf(false) }
        this
            .onFocusChanged { focused = it.isFocused }
            .drawWithContent {
                drawContent()
                if (focused) {
                    val bw = borderWidth.toPx()
                    val cr = cornerRadius.toPx()
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(bw / 2, bw / 2),
                        size = Size(size.width - bw, size.height - bw),
                        cornerRadius = CornerRadius(cr, cr),
                        style = Stroke(width = bw),
                    )
                }
            }
    }

fun Modifier.controllerFocusGlow(
    cornerRadius: Dp = 8.dp,
    color: Color = AccentBorder,
): Modifier =
    composed {
        var focused by remember { mutableStateOf(false) }
        val intensity by animateFloatAsState(
            targetValue = if (focused) 1f else 0f,
            animationSpec = tween(160),
            label = "controllerFocusGlow",
        )
        this
            .onFocusChanged { focused = it.isFocused }
            .drawWithContent {
                val cr = cornerRadius.toPx()
                if (intensity > 0f) {
                    drawRoundRect(
                        color = color.copy(alpha = 0.28f * intensity),
                        cornerRadius = CornerRadius(cr, cr),
                    )
                }
                drawContent()
                if (intensity > 0f) {
                    drawRoundRect(
                        color = color.copy(alpha = 0.12f * intensity),
                        cornerRadius = CornerRadius(cr, cr),
                    )
                }
            }
    }

fun Modifier.controllerFocusItem(
    cornerRadius: Dp = 8.dp,
    color: Color = AccentBorder,
    onActivate: (() -> Unit)? = null,
): Modifier =
    composed {
        var focused by remember { mutableStateOf(false) }
        val intensity by animateFloatAsState(
            targetValue = if (focused) 1f else 0f,
            animationSpec = tween(160),
            label = "controllerFocusItem",
        )
        this
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (onActivate != null) {
                    Modifier.onKeyEvent { e ->
                        if (e.nativeKeyEvent.action != android.view.KeyEvent.ACTION_UP) {
                            false
                        } else {
                            when (e.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                                android.view.KeyEvent.KEYCODE_ENTER,
                                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                                android.view.KeyEvent.KEYCODE_BUTTON_A,
                                -> {
                                    onActivate()
                                    true
                                }

                                else -> false
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ).focusable()
            .drawWithContent {
                val cr = cornerRadius.toPx()
                if (intensity > 0f) {
                    drawRoundRect(
                        color = color.copy(alpha = 0.28f * intensity),
                        cornerRadius = CornerRadius(cr, cr),
                    )
                }
                drawContent()
                if (intensity > 0f) {
                    drawRoundRect(
                        color = color.copy(alpha = 0.12f * intensity),
                        cornerRadius = CornerRadius(cr, cr),
                    )
                }
            }
    }

fun Modifier.controllerSliderEscape(): Modifier =
    composed {
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        this.onPreviewKeyEvent { e ->
            if (e.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                false
            } else {
                when (e.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP ->
                        focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                        focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                    else -> false
                }
            }
        }
    }

fun Modifier.controllerTextFieldEscape(): Modifier = controllerSliderEscape()

fun Modifier.controllerMenuInput(
    onDismiss: () -> Unit,
    onSecondary: (() -> Unit)? = null,
    onStart: (() -> Unit)? = null,
    repeatMs: Long = 200L,
): Modifier =
    composed {
        val view = LocalView.current
        val lastMove = remember { longArrayOf(0L) }

        DisposableEffect(view) {
            val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            val unregister = window?.let { com.winlator.cmod.shared.ui.nav.ControllerWindowInput.register(it) }
            val decor = view.rootView
            val listener =
                android.view.View.OnGenericMotionListener { _, ev ->
                    if ((ev.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
                        ev.action == MotionEvent.ACTION_MOVE
                    ) {
                        val x = ev.getAxisValue(MotionEvent.AXIS_X)
                        val y = ev.getAxisValue(MotionEvent.AXIS_Y)
                        val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
                        val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
                        val code =
                            when {
                                x < -0.5f || hx < -0.5f -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
                                x > 0.5f || hx > 0.5f -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                                y < -0.5f || hy < -0.5f -> android.view.KeyEvent.KEYCODE_DPAD_UP
                                y > 0.5f || hy > 0.5f -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
                                else -> 0
                            }
                        if (code != 0) {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastMove[0] >= repeatMs) {
                                lastMove[0] = now
                                decor.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                                decor.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
                            }
                            return@OnGenericMotionListener true
                        }
                    }
                    false
                }
            decor.setOnGenericMotionListener(listener)
            onDispose {
                unregister?.invoke()
                decor.setOnGenericMotionListener(null)
            }
        }

        this.onPreviewKeyEvent { e ->
            when (e.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_BUTTON_A -> {
                    view.dispatchKeyEvent(
                        android.view.KeyEvent(e.nativeKeyEvent.action, android.view.KeyEvent.KEYCODE_DPAD_CENTER),
                    )
                    true
                }

                android.view.KeyEvent.KEYCODE_BUTTON_B -> {
                    if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                        val imeVisible =
                            androidx.core.view.ViewCompat
                                .getRootWindowInsets(view)
                                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                        if (imeVisible) {
                            androidx.core.view.ViewCompat
                                .getWindowInsetsController(view)
                                ?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                        } else {
                            onDismiss()
                        }
                    }
                    true
                }

                android.view.KeyEvent.KEYCODE_BUTTON_X -> {
                    if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) onSecondary?.invoke()
                    true
                }

                android.view.KeyEvent.KEYCODE_BUTTON_START -> {
                    if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) onStart?.invoke()
                    true
                }

                android.view.KeyEvent.KEYCODE_BUTTON_Y,
                android.view.KeyEvent.KEYCODE_BUTTON_L1,
                android.view.KeyEvent.KEYCODE_BUTTON_R1,
                -> true

                else -> false
            }
        }
    }
