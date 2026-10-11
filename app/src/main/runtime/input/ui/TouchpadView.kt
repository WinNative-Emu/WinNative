package com.winlator.cmod.runtime.input.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.winlator.cmod.R
import com.winlator.cmod.runtime.display.XServerDisplayActivity
import com.winlator.cmod.runtime.display.renderer.ViewTransformation
import com.winlator.cmod.runtime.display.winhandler.MouseEventFlags
import com.winlator.cmod.runtime.display.xserver.Pointer
import com.winlator.cmod.runtime.display.xserver.XServer
import com.winlator.cmod.shared.android.AppUtils
import com.winlator.cmod.shared.math.Mathf
import com.winlator.cmod.shared.math.XForm

class TouchpadView(
    context: Context,
    private val xServer: XServer,
    private val timeoutHandler: Handler?,
    private val hideControlsRunnable: Runnable?,
) : View(context) {
    companion object {
        const val CURSOR_ACCELERATION = 1.25f
        const val CURSOR_ACCELERATION_THRESHOLD: Byte = 6
        private const val MAX_FINGERS: Byte = 4
        const val MAX_TAP_MILLISECONDS: Short = 200
        const val MAX_TAP_TRAVEL_DISTANCE: Byte = 10
        private const val MAX_TWO_FINGERS_SCROLL_DISTANCE: Short = 350
        private const val LONG_PRESS_RIGHT_CLICK_MS = 1000L
        private const val UPDATE_FORM_DELAYED_TIME = 50
        private val CLICK_DELAYED_TIME = 50.toByte()
        private val EFFECTIVE_TOUCH_DISTANCE = 20.toByte()
        const val MODE_TRACKPAD = 0
        const val MODE_TOUCHSCREEN = 1
        const val MODE_MAP_TO_RIGHT_STICK = 2
        private const val TOUCHSCREEN_DOUBLE_TAP_MS = 500L
        private const val TOUCHSCREEN_DOUBLE_TAP_DISTANCE = 100f
        private const val TOUCHSCREEN_PRESS_DELAY_MS = 50L
    }

    private var continueClick = true
    private var lastTapDownTime = 0L
    private var lastTapRawX = 0f
    private var lastTapRawY = 0f
    private var lastTapTransX = 0
    private var lastTapTransY = 0
    private var fingerPointerButtonLeft: Finger? = null
    private var fingerPointerButtonRight: Finger? = null
    private val fingers = arrayOfNulls<Finger>(4)
    private var fourFingersTapCallback: Runnable? = null
    private var lastTouchedPosX = 0
    private var lastTouchedPosY = 0
    private var mouseEnabled = true
    private var numFingers: Byte = 0
    private var pointerButtonLeftEnabled = true
    private var pointerButtonRightEnabled = true
    private var resolutionScale = 0f
    private var scrollAccumY = 0f
    private var scrolling = false
    private var sensitivity = 1.0f
    private var simTouchScreen = false
    private var sinkTouchActive = false
    private var sinkTapTime = 0L
    private var sinkTapX = 0f
    private var sinkTapY = 0f
    private var sinkTouchX = 0f
    private var sinkTouchY = 0f
    private var touchLeftPressed = false
    private var touchPrimaryId = -1
    private var touchSecondId = -1
    private var twoFingerGesture = false
    private var pressPending = false
    private val pressDown = FloatArray(4)
    private val pressCurrent = FloatArray(4)
    private val pendingPressRunnable = Runnable { firePendingPress() }
    private var twoFingerMoved = false
    private var twoFingerLastY = 0f
    private val twoFingerStart = FloatArray(4)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val twoFingerScrollStep = 40f * resources.displayMetrics.density
    private var wheelAccum = 0f
    private var mouseLastX = Float.NaN
    private var mouseLastY = Float.NaN
    private var mouseRemainderX = 0f
    private var mouseRemainderY = 0f
    private var screenTouchMode = MODE_TRACKPAD
    private var rtsGesturesEnabled = false
    private val xform = XForm.getInstance()
    private val screenTouchStick = ScreenTouchStick(context, xServer)
    private val rtsGestureEngine = RTSGestureEngine(xServer, xform)
    private var activeTouchHandler: ((MotionEvent) -> Boolean)? = null
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressActive = false
    private val isInputSuspended: Boolean get() = (context as? XServerDisplayActivity)?.isInputSuspended ?: false
    private val longPressRunnable = Runnable {
        if (tapToClickEnabled && numFingers.toInt() == 1 && fingers[0] != null && fingers[0]!!.travelDistance() < MAX_TAP_TRAVEL_DISTANCE) {
            if (isInputSuspended) return@Runnable
            longPressActive = true
            if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
            }
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
        }
    }

    init {
        layoutParams = FrameLayout.LayoutParams(-1, -1)
        background = createTransparentBg()
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        pointerIcon = PointerIcon.load(resources, R.drawable.hidden_pointer_arrow)
        updateXform(AppUtils.getScreenWidth(), AppUtils.getScreenHeight(), xServer.screenInfo.width.toInt(), xServer.screenInfo.height.toInt())
        setOnGenericMotionListener { _, event ->
            if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) handleStylusHoverEvent(event) else false
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateXform(w, h, xServer.screenInfo.width.toInt(), xServer.screenInfo.height.toInt())
        resolutionScale = 1000.0f / Math.min(xServer.screenInfo.width.toInt(), xServer.screenInfo.height.toInt())
        screenTouchStick.setSurfaceSize(w, h)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        resetInputState()
    }

    private fun updateXform(outerWidth: Int, outerHeight: Int, innerWidth: Int, innerHeight: Int) {
        val viewTransformation = ViewTransformation()
        viewTransformation.update(outerWidth, outerHeight, innerWidth, innerHeight)
        val invAspect = 1.0f / viewTransformation.aspect
        if (!xServer.renderer!!.isFullscreen) {
            XForm.makeTranslation(xform, -viewTransformation.viewOffsetX.toFloat(), -viewTransformation.viewOffsetY.toFloat())
            XForm.scale(xform, invAspect, invAspect)
        } else {
            XForm.makeScale(xform, innerWidth.toFloat() / outerWidth.toFloat(), innerHeight.toFloat() / outerHeight.toFloat())
        }
    }

    fun updateVisibleRelativeCursor(x: Int, y: Int) {
        xServer.renderer?.updateVisualCursorPosition(x, y)
    }

    private inner class Finger(x: Float, y: Float) {
        var lastX: Int
        var lastY: Int
        val startX: Int
        val startY: Int
        val touchTime: Long = System.currentTimeMillis()
        var x: Int
        var y: Int

        init {
            val transformedPoint = XForm.transformPoint(xform, x, y)
            val ix = transformedPoint[0].toInt()
            this.x = ix
            this.lastX = ix
            this.startX = ix
            val iy = transformedPoint[1].toInt()
            this.y = iy
            this.lastY = iy
            this.startY = iy
        }

        fun update(x: Float, y: Float) {
            this.lastX = this.x
            this.lastY = this.y
            val transformedPoint = XForm.transformPoint(xform, x, y)
            this.x = transformedPoint[0].toInt()
            this.y = transformedPoint[1].toInt()
        }

        fun deltaX(): Int {
            var dx = (this.x - this.lastX) * sensitivity
            if (Math.abs(dx) > 6.0f) dx *= 1.25f
            return Mathf.roundPoint(dx)
        }

        fun deltaY(): Int {
            var dy = (this.y - this.lastY) * sensitivity
            if (Math.abs(dy) > 6.0f) dy *= 1.25f
            return Mathf.roundPoint(dy)
        }

        fun isTap(): Boolean = System.currentTimeMillis() - touchTime < 200 && travelDistance() < 10.0f

        fun travelDistance(): Float = Math.hypot((x - startX).toDouble(), (y - startY).toDouble()).toFloat()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!mouseEnabled) return true
        resetTouchscreenTimeout()
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) return handleStylusEvent(event)
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return handleMouseTouchEvent(event)
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN || activeTouchHandler == null) {
            activeTouchHandler = selectTouchHandler()
        }

        // Block gestures that don't lead to the side menu while input is suspended
        if (isInputSuspended && (rtsGesturesEnabled || screenTouchMode != MODE_TRACKPAD)) return true

        val result = activeTouchHandler!!(event)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activeTouchHandler = null
        }
        return result
    }

    private fun selectTouchHandler(): (MotionEvent) -> Boolean = when {
        rtsGesturesEnabled -> rtsGestureEngine::onTouch
        screenTouchMode == MODE_MAP_TO_RIGHT_STICK && xServer.winHandler.canUseScreenTouchStick() -> screenTouchStick::onTouch
        screenTouchMode == MODE_TOUCHSCREEN -> ::handleTouchscreenEvent
        else -> ::handleTouchpadEvent
    }

    private fun resetTouchscreenTimeout() {
        xServer.renderer?.setCursorVisible(true)
        if (timeoutHandler != null && hideControlsRunnable != null) {
            timeoutHandler.removeCallbacks(hideControlsRunnable)
            timeoutHandler.postDelayed(hideControlsRunnable, 5000L)
        }
    }

    private fun handleStylusHoverEvent(event: MotionEvent): Boolean {
        if (isInputSuspended) return false
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            val transformedPoint = XForm.transformPoint(xform, event.x, event.y)
            xServer.injectPointerMove(transformedPoint[0].toInt(), transformedPoint[1].toInt())
            return true
        }
        return false
    }

    private fun handleStylusEvent(event: MotionEvent): Boolean {
        if (isInputSuspended) return true
        val action = event.actionMasked
        val buttonState = event.buttonState
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if ((buttonState and MotionEvent.BUTTON_SECONDARY) != 0) handleStylusRightClick(event) else handleStylusLeftClick(event)
            }
            MotionEvent.ACTION_MOVE -> handleStylusMove(event)
            MotionEvent.ACTION_UP -> handleStylusUp()
        }
        return true
    }

    private fun handleStylusLeftClick(event: MotionEvent) {
        val transformedPoint = XForm.transformPoint(xform, event.x, event.y)
        xServer.injectPointerMove(transformedPoint[0].toInt(), transformedPoint[1].toInt())
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
    }

    private fun handleStylusRightClick(event: MotionEvent) {
        val transformedPoint = XForm.transformPoint(xform, event.x, event.y)
        xServer.injectPointerMove(transformedPoint[0].toInt(), transformedPoint[1].toInt())
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
    }

    private fun handleStylusMove(event: MotionEvent) {
        val transformedPoint = XForm.transformPoint(xform, event.x, event.y)
        xServer.injectPointerMove(transformedPoint[0].toInt(), transformedPoint[1].toInt())
    }

    private fun handleStylusUp() {
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
    }

    private fun handleTouchpadEvent(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        val actionMasked = event.actionMasked
        if (actionMasked == MotionEvent.ACTION_CANCEL) {
            releaseTouches()
            return true
        }
        if (actionMasked != MotionEvent.ACTION_MOVE && (pointerId >= MAX_FINGERS || pointerIdsToIgnore.contains(pointerId))) return true
        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                scrollAccumY = 0.0f
                scrolling = false
                fingers[pointerId] = Finger(event.getX(actionIndex), event.getY(actionIndex))
                numFingers = (numFingers + 1).toByte()
                if (numFingers.toInt() > 1) {
                    longPressHandler.removeCallbacks(longPressRunnable)
                }
                if (pointerId == 0 && numFingers.toInt() == 1 && !simTouchScreen) {
                    longPressActive = false
                    longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_RIGHT_CLICK_MS)
                } else {
                    longPressHandler.removeCallbacks(longPressRunnable)
                }
                if (simTouchScreen && tapToClickEnabled) {
                    val clickDelay = Runnable { if (continueClick) {
                        xServer.injectPointerMove(lastTouchedPosX, lastTouchedPosY)
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
                    } }
                    if (pointerId == 0) {
                        continueClick = true
                        if (Math.hypot((fingers[0]!!.x - lastTouchedPosX).toDouble(), (fingers[0]!!.y - lastTouchedPosY).toDouble()) * resolutionScale > EFFECTIVE_TOUCH_DISTANCE) {
                            lastTouchedPosX = fingers[0]!!.x
                            lastTouchedPosY = fingers[0]!!.y
                        }
                        postDelayed(clickDelay, CLICK_DELAYED_TIME.toLong())
                    } else if (pointerId == 1) {
                        if (numFingers < 2) {
                            continueClick = true
                            if (Math.hypot((fingers[1]!!.x - lastTouchedPosX).toDouble(), (fingers[1]!!.y - lastTouchedPosY).toDouble()) * resolutionScale > EFFECTIVE_TOUCH_DISTANCE) {
                                lastTouchedPosX = fingers[1]!!.x
                                lastTouchedPosY = fingers[1]!!.y
                            }
                            postDelayed(clickDelay, CLICK_DELAYED_TIME.toLong())
                        } else {
                            continueClick = System.currentTimeMillis() - fingers[0]!!.touchTime > CLICK_DELAYED_TIME.toLong()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (fingers[pointerId] != null) {
                    fingers[pointerId]!!.update(event.getX(actionIndex), event.getY(actionIndex))
                    if (!longPressActive) handleFingerUp(fingers[pointerId]!!)
                    longPressActive = false
                    fingers[pointerId] = null
                    numFingers = (numFingers - 1).toByte()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until 4) {
                    if (fingers[i] != null) {
                        val pointerIndex = event.findPointerIndex(i)
                        if (pointerIndex >= 0) {
                            fingers[i]!!.update(event.getX(pointerIndex), event.getY(pointerIndex))
                            handleFingerMove(fingers[i]!!)
                        } else {
                            handleFingerUp(fingers[i]!!)
                            fingers[i] = null
                            numFingers = (numFingers - 1).toByte()
                        }
                    }
                }
            }
        }
        return true
    }

    /** A mouse the app doesn't capture: its buttons arrive through [onExternalMouseEvent], its drags come here. */
    private fun handleMouseTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !isInputSuspended) moveExternalMouse(event)
        return true
    }

    private fun moveExternalMouse(event: MotionEvent) {
        val transformedPoint = XForm.transformPoint(xform, event.x, event.y)
        if (xServer.isRelativeMouseMovement) {
            var dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
            var dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
            for (i in 0 until event.historySize) {
                dx += event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, i)
                dy += event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, i)
            }
            if (dx == 0f && dy == 0f && !mouseLastX.isNaN()) {
                dx = event.x - mouseLastX
                dy = event.y - mouseLastY
            }
            val delta = computeDeltaPoint(0f, 0f, dx, dy)
            mouseRemainderX += delta[0]
            mouseRemainderY += delta[1]
            val moveX = mouseRemainderX.toInt()
            val moveY = mouseRemainderY.toInt()
            mouseRemainderX -= moveX
            mouseRemainderY -= moveY
            if (moveX != 0 || moveY != 0) xServer.winHandler.mouseEvent(MouseEventFlags.MOVE, moveX, moveY, 0)
            updateVisibleRelativeCursor(transformedPoint[0].toInt(), transformedPoint[1].toInt())
        } else {
            xServer.injectPointerMove(transformedPoint[0].toInt(), transformedPoint[1].toInt())
        }
        mouseLastX = event.x
        mouseLastY = event.y
    }

    /** Wheel motion in notches; high-resolution wheels and touchpads send fractions of one. */
    fun onMouseWheel(amount: Float) {
        if (amount == 0f || amount.isNaN()) return
        if (wheelAccum != 0f && Math.signum(wheelAccum) != Math.signum(amount)) wheelAccum = 0f
        wheelAccum += amount
        while (wheelAccum >= 1f) {
            clickPointerButton(Pointer.Button.BUTTON_SCROLL_UP)
            wheelAccum -= 1f
        }
        while (wheelAccum <= -1f) {
            clickPointerButton(Pointer.Button.BUTTON_SCROLL_DOWN)
            wheelAccum += 1f
        }
    }

    private fun clickPointerButton(button: Pointer.Button) {
        xServer.injectPointerButtonPress(button)
        xServer.injectPointerButtonRelease(button)
    }

    /**
     * The first finger clicks and drags where it touches. A second finger ends that press: the
     * pair then scrolls as it moves, and right-clicks if it lifts without having moved.
     */
    private fun handleTouchscreenEvent(event: MotionEvent): Boolean {
        if (isInputSuspended) return true
        val index = event.actionIndex
        val pointerId = event.getPointerId(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerId >= MAX_FINGERS || pointerIdsToIgnore.contains(pointerId) || twoFingerGesture) return true
                if (touchPrimaryId == -1) {
                    touchPrimaryId = pointerId
                    handleTouchDown(event, index)
                } else if (touchSecondId == -1) {
                    touchSecondId = pointerId
                    startTwoFingerGesture(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFingerGesture) {
                    handleTwoFingerMove(event)
                } else if (touchPrimaryId != -1) {
                    val primaryIndex = event.findPointerIndex(touchPrimaryId)
                    if (primaryIndex >= 0) handleTouchMove(event, primaryIndex)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (twoFingerGesture) {
                    if (pointerId == touchPrimaryId || pointerId == touchSecondId) {
                        if (!twoFingerMoved && tapToClickEnabled) clickPointerButton(Pointer.Button.BUTTON_RIGHT)
                        twoFingerMoved = true
                        if (pointerId == touchPrimaryId) touchPrimaryId = -1 else touchSecondId = -1
                        if (touchPrimaryId == -1 && touchSecondId == -1) twoFingerGesture = false
                    }
                } else if (pointerId == touchPrimaryId) {
                    handleTouchUp(event, index)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) releaseTouchscreenPointers()
            }
            MotionEvent.ACTION_CANCEL -> releaseTouches()
        }
        return true
    }

    /**
     * Takes touchscreen-mode touches (0 = down, 1 = move, 2 = up, in screen coordinates) while a
     * Wayland session presents: the compositor knows where on the screen it draws the scene,
     * which this view's own transform does not. Returns false when no such session is attached.
     */
    fun interface TouchscreenSink {
        fun onTouch(action: Int, rawX: Float, rawY: Float): Boolean
    }

    var touchscreenSink: TouchscreenSink? = null

    private fun rawX(event: MotionEvent, index: Int): Float = event.getX(index) + event.rawX - event.x

    private fun rawY(event: MotionEvent, index: Int): Float = event.getY(index) + event.rawY - event.y

    private fun readTouch(event: MotionEvent, index: Int, into: FloatArray) {
        into[0] = event.getX(index)
        into[1] = event.getY(index)
        into[2] = rawX(event, index)
        into[3] = rawY(event, index)
    }

    /** The press waits briefly so a second finger landing with the first starts a gesture, not a click. */
    private fun handleTouchDown(event: MotionEvent, index: Int) {
        readTouch(event, index, pressDown)
        pressDown.copyInto(pressCurrent)
        pressPending = true
        postDelayed(pendingPressRunnable, TOUCHSCREEN_PRESS_DELAY_MS)
    }

    private fun firePendingPress() {
        if (!pressPending) return
        cancelPendingPress()
        if (isInputSuspended) return
        pressAt(pressDown[0], pressDown[1], pressDown[2], pressDown[3])
        if (!pressCurrent.contentEquals(pressDown)) moveTouch()
    }

    private fun cancelPendingPress() {
        pressPending = false
        removeCallbacks(pendingPressRunnable)
    }

    private fun pressAt(x: Float, y: Float, rawX: Float, rawY: Float) {
        if (tapToClickEnabled && sinkTouchDown(rawX, rawY)) return
        val transformedPoint = XForm.transformPoint(xform, x, y)
        var tx = transformedPoint[0].toInt()
        var ty = transformedPoint[1].toInt()
        val now = System.currentTimeMillis()
        val near = Math.hypot((x - lastTapRawX).toDouble(), (y - lastTapRawY).toDouble()) < TOUCHSCREEN_DOUBLE_TAP_DISTANCE
        if (now - lastTapDownTime < TOUCHSCREEN_DOUBLE_TAP_MS && near) {
            tx = lastTapTransX
            ty = lastTapTransY
        }
        lastTapDownTime = now
        lastTapRawX = x
        lastTapRawY = y
        lastTapTransX = tx
        lastTapTransY = ty
        xServer.injectPointerMove(tx, ty)
        if (tapToClickEnabled) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
            touchLeftPressed = true
        }
    }

    /** A second tap close to the first lands exactly on it, so a double tap is a double click. */
    private fun sinkTouchDown(rawX: Float, rawY: Float): Boolean {
        val sink = touchscreenSink ?: return false
        val now = System.currentTimeMillis()
        val near = Math.hypot((rawX - sinkTapX).toDouble(), (rawY - sinkTapY).toDouble()) < TOUCHSCREEN_DOUBLE_TAP_DISTANCE
        if (now - sinkTapTime >= TOUCHSCREEN_DOUBLE_TAP_MS || !near) {
            sinkTapX = rawX
            sinkTapY = rawY
        }
        sinkTapTime = now
        sinkTouchX = sinkTapX
        sinkTouchY = sinkTapY
        sinkTouchActive = sink.onTouch(0, sinkTapX, sinkTapY)
        return sinkTouchActive
    }

    private fun handleTouchMove(event: MotionEvent, index: Int) {
        readTouch(event, index, pressCurrent)
        if (pressPending) {
            val travel = Math.hypot((pressCurrent[0] - pressDown[0]).toDouble(), (pressCurrent[1] - pressDown[1]).toDouble())
            if (travel > touchSlop) firePendingPress()
            return
        }
        moveTouch()
    }

    private fun moveTouch() {
        if (sinkTouchActive) {
            sinkTouchX = pressCurrent[2]
            sinkTouchY = pressCurrent[3]
            touchscreenSink?.onTouch(1, sinkTouchX, sinkTouchY)
            return
        }
        val transformedPoint = XForm.transformPoint(xform, pressCurrent[0], pressCurrent[1])
        xServer.injectPointerMove(transformedPoint[0].toInt(), transformedPoint[1].toInt())
    }

    private fun handleTouchUp(event: MotionEvent, index: Int) {
        readTouch(event, index, pressCurrent)
        firePendingPress()
        if (sinkTouchActive) {
            sinkTouchX = pressCurrent[2]
            sinkTouchY = pressCurrent[3]
        }
        releaseTouchscreenPointers()
    }

    /** Ends (or never starts) the first finger's press; the pointer stays there for the pair's clicks. */
    private fun startTwoFingerGesture(event: MotionEvent) {
        val pressed = !pressPending
        endTouchscreenPress()
        if (!pressed && touchscreenSink?.onTouch(1, pressCurrent[2], pressCurrent[3]) != true) {
            val transformedPoint = XForm.transformPoint(xform, pressCurrent[0], pressCurrent[1])
            xServer.injectPointerMove(transformedPoint[0].toInt(), transformedPoint[1].toInt())
        }
        twoFingerGesture = true
        twoFingerMoved = false
        scrollAccumY = 0f
        val first = event.findPointerIndex(touchPrimaryId)
        val second = event.findPointerIndex(touchSecondId)
        if (first < 0 || second < 0) {
            twoFingerMoved = true
            return
        }
        twoFingerStart[0] = event.getX(first)
        twoFingerStart[1] = event.getY(first)
        twoFingerStart[2] = event.getX(second)
        twoFingerStart[3] = event.getY(second)
        twoFingerLastY = (twoFingerStart[1] + twoFingerStart[3]) * 0.5f
    }

    private fun handleTwoFingerMove(event: MotionEvent) {
        val first = event.findPointerIndex(touchPrimaryId)
        val second = event.findPointerIndex(touchSecondId)
        if (first < 0 || second < 0) return
        val y1 = event.getY(first)
        val y2 = event.getY(second)
        if (!twoFingerMoved &&
            (Math.hypot((event.getX(first) - twoFingerStart[0]).toDouble(), (y1 - twoFingerStart[1]).toDouble()) > touchSlop ||
                Math.hypot((event.getX(second) - twoFingerStart[2]).toDouble(), (y2 - twoFingerStart[3]).toDouble()) > touchSlop)
        ) {
            twoFingerMoved = true
        }
        val y = (y1 + y2) * 0.5f
        scrollAccumY += y - twoFingerLastY
        twoFingerLastY = y
        while (scrollAccumY <= -twoFingerScrollStep) {
            clickPointerButton(Pointer.Button.BUTTON_SCROLL_DOWN)
            scrollAccumY += twoFingerScrollStep
        }
        while (scrollAccumY >= twoFingerScrollStep) {
            clickPointerButton(Pointer.Button.BUTTON_SCROLL_UP)
            scrollAccumY -= twoFingerScrollStep
        }
    }

    private fun endTouchscreenPress() {
        cancelPendingPress()
        if (sinkTouchActive) {
            sinkTouchActive = false
            touchscreenSink?.onTouch(2, sinkTouchX, sinkTouchY)
        }
        if (touchLeftPressed) {
            touchLeftPressed = false
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
        }
    }

    private fun releaseTouchscreenPointers() {
        endTouchscreenPress()
        touchPrimaryId = -1
        touchSecondId = -1
        twoFingerGesture = false
    }

    private fun handleFingerUp(finger1: Finger) {
        if (tapToClickEnabled) {
            when (numFingers.toInt()) {
                1 -> {
                    if (simTouchScreen) {
                        postDelayed({ if (continueClick && !isInputSuspended) xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT) }, CLICK_DELAYED_TIME.toLong())
                    } else if (finger1.isTap()) {
                        pressPointerButtonLeft(finger1)
                    }
                }
                2 -> {
                    val finger2 = findSecondFinger(finger1)
                    if (finger2 != null && finger1.isTap()) pressPointerButtonRight(finger1)
                }
                4 -> {
                    fourFingersTapCallback?.let {
                        for (i in 0 until 4) if (fingers[i] != null && !fingers[i]!!.isTap()) return
                        it.run()
                    }
                }
            }
        }
        releasePointerButtonLeft(finger1)
        releasePointerButtonRight(finger1)
    }

    private fun handleFingerMove(finger1: Finger) {
        if (finger1.travelDistance() >= MAX_TAP_TRAVEL_DISTANCE) {
            longPressHandler.removeCallbacks(longPressRunnable)
        }
        if (isInputSuspended) return
        var skipPointerMove = false
        val finger2 = if (numFingers.toInt() == 2) findSecondFinger(finger1) else null
        if (finger2 != null) {
            val resScale = 1000.0f / Math.min(xServer.screenInfo.width.toInt(), xServer.screenInfo.height.toInt())
            val currDistance = Math.hypot((finger1.x - finger2.x).toDouble(), (finger1.y - finger2.y).toDouble()).toFloat() * resScale
            if (currDistance < 350.0f) {
                scrollAccumY += (finger1.y + finger2.y) * 0.5f - (finger1.lastY + finger2.lastY) * 0.5f
                if (scrollAccumY < -100.0f) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN)
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN)
                    scrollAccumY = 0.0f
                } else if (scrollAccumY > 100.0f) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP)
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP)
                    scrollAccumY = 0.0f
                }
                scrolling = true
            } else if (tapToClickEnabled && currDistance >= 350.0f && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT) && finger2.travelDistance() < 10.0f) {
                pressPointerButtonLeft(finger1)
                skipPointerMove = true
            }
        }
        if (!scrolling && numFingers <= 2 && !skipPointerMove) {
            // finger2 drives movement when finger1 is held still (two-finger drag-to-move/resize)
            val drivingFinger = if (finger2 != null && (finger2.deltaX() != 0 || finger2.deltaY() != 0) && finger1.deltaX() == 0 && finger1.deltaY() == 0) finger2 else finger1
            val dx = drivingFinger.deltaX()
            val dy = drivingFinger.deltaY()
            if (simTouchScreen) {
                if (System.currentTimeMillis() - finger1.touchTime > CLICK_DELAYED_TIME.toLong()) xServer.injectPointerMove(finger1.x, finger1.y)
            } else {
                if (xServer.isRelativeMouseMovement) {
                    xServer.winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0)
                    updateVisibleRelativeCursor(xServer.pointer.x + dx, xServer.pointer.y + dy)
                    return
                }
                xServer.injectPointerMoveDelta(dx, dy)
            }
        }
    }

    private fun findSecondFinger(finger: Finger): Finger? {
        for (i in 0 until 4) if (fingers[i] != null && fingers[i] != finger) return fingers[i]
        return null
    }

    private fun pressPointerButtonLeft(finger: Finger) {
        if (isInputSuspended) return
        if (pointerButtonLeftEnabled && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
            fingerPointerButtonLeft = finger
        }
    }

    private fun pressPointerButtonRight(finger: Finger) {
        if (isInputSuspended) return
        if (pointerButtonRightEnabled && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
            fingerPointerButtonRight = finger
        }
    }

    private fun releasePointerButtonLeft(finger: Finger) {
        if (isInputSuspended) {
            fingerPointerButtonLeft = null
            return
        }
        if (pointerButtonLeftEnabled && finger == fingerPointerButtonLeft && xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            postDelayed({
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                fingerPointerButtonLeft = null
            }, 30L)
        }
    }

    private fun releasePointerButtonRight(finger: Finger) {
        if (isInputSuspended) {
            fingerPointerButtonRight = null
            return
        }
        if (pointerButtonRightEnabled && finger == fingerPointerButtonRight && xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            postDelayed({
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
                fingerPointerButtonRight = null
            }, 30L)
        }
    }

    fun setSensitivity(sensitivity: Float) {
        this.sensitivity = sensitivity
    }

    fun setPointerButtonLeftEnabled(enabled: Boolean) {
        this.pointerButtonLeftEnabled = enabled
    }

    fun setPointerButtonRightEnabled(enabled: Boolean) {
        this.pointerButtonRightEnabled = enabled
    }

    fun setFourFingersTapCallback(callback: Runnable?) {
        this.fourFingersTapCallback = callback
    }

    fun onExternalMouseEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(8194)) return false
        if (isInputSuspended) return true
        resetTouchscreenTimeout()
        val actionButton = event.actionButton
        when (event.action) {
            2, 7 -> {
                moveExternalMouse(event)
                return true
            }
            8 -> {
                onMouseWheel(event.getAxisValue(MotionEvent.AXIS_VSCROLL))
                return true
            }
            9, 10 -> {
                mouseLastX = Float.NaN
                mouseLastY = Float.NaN
                return false
            }
            11 -> {
                if (actionButton == 1) {
                    if (xServer.isRelativeMouseMovement) xServer.winHandler.mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0) else xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
                } else if (actionButton == 2) {
                    if (xServer.isRelativeMouseMovement) xServer.winHandler.mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0) else xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
                } else if (actionButton == 4) {
                    if (xServer.isRelativeMouseMovement) xServer.winHandler.mouseEvent(MouseEventFlags.MIDDLEDOWN, 0, 0, 0) else xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE)
                }
                return true
            }
            12 -> {
                if (actionButton == 1) {
                    if (xServer.isRelativeMouseMovement) xServer.winHandler.mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0) else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                } else if (actionButton == 2) {
                    if (xServer.isRelativeMouseMovement) xServer.winHandler.mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0) else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
                } else if (actionButton == 4) {
                    if (xServer.isRelativeMouseMovement) xServer.winHandler.mouseEvent(MouseEventFlags.MIDDLEUP, 0, 0, 0) else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE)
                }
                return true
            }
        }
        return false
    }

    fun computeDeltaPoint(lastX: Float, lastY: Float, x: Float, y: Float): FloatArray {
        val result = floatArrayOf(0f, 0f)
        XForm.transformPoint(xform, lastX, lastY, result)
        val lX = result[0]
        val lY = result[1]
        XForm.transformPoint(xform, x, y, result)
        val nX = result[0]
        val nY = result[1]
        result[0] = nX - lX
        result[1] = nY - lY
        return result
    }

    private fun createTransparentBg(): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(0))
            addState(intArrayOf(), ColorDrawable(0))
        }

    fun setSimTouchScreen(sim: Boolean) {
        this.simTouchScreen = sim
        xServer.setSimulateTouchScreen(sim)
    }

    fun isSimTouchScreen(): Boolean = simTouchScreen

    fun setScreenTouchMode(mode: Int) {
        setSimTouchScreen(mode == MODE_TOUCHSCREEN)
        if (screenTouchMode == mode) return
        screenTouchMode = mode
        screenTouchStick.releaseAll()
        resetInputState()
    }

    fun getScreenTouchMode(): Int = screenTouchMode

    fun setRtsGesturesEnabled(enabled: Boolean) {
        if (rtsGesturesEnabled == enabled) return
        rtsGesturesEnabled = enabled
        rtsGestureEngine.releaseAll()
        resetInputState()
    }

    fun setGestureConfig(json: String?) {
        rtsGestureEngine.setConfig(TouchGestureConfig.fromJson(json))
    }

    fun toggleFullscreen() {
        Handler(Looper.getMainLooper()).postDelayed({
            updateXform(width, height, xServer.screenInfo.width.toInt(), xServer.screenInfo.height.toInt())
        }, 50L)
    }

    private val pointerIdsToIgnore = mutableSetOf<Int>()

    /** Pointers the on-screen controls hold; one that slides onto a control lets go of what it pressed here. */
    fun setPointerIdsToIgnore(ids: Set<Int>) {
        pointerIdsToIgnore.clear()
        pointerIdsToIgnore.addAll(ids)
        if (touchPrimaryId in ids || touchSecondId in ids) releaseTouchscreenPointers()
        for (i in 0 until MAX_FINGERS) {
            val finger = fingers[i] ?: continue
            if (i !in ids) continue
            releasePointerButtonLeft(finger)
            releasePointerButtonRight(finger)
            fingers[i] = null
            numFingers = (numFingers - 1).toByte()
        }
    }

    var tapToClickEnabled = true
        set(value) {
            field = value
            if (!value) resetInputState()
        }

    fun setMouseEnabled(enabled: Boolean) {
        this.mouseEnabled = enabled
        if (!enabled) {
            resetInputState()
            cancelMousePointerTimeout()
            xServer.renderer?.setCursorVisible(false)
        } else {
            resetTouchscreenTimeout()
        }
    }

    fun resetInputState() {
        screenTouchStick.releaseAll()
        rtsGestureEngine.releaseAll()
        releaseTouches()
        wheelAccum = 0f
        mouseRemainderX = 0f
        mouseRemainderY = 0f
    }

    private fun releaseTouches() {
        longPressHandler.removeCallbacks(longPressRunnable)
        longPressActive = false
        continueClick = false
        scrolling = false
        scrollAccumY = 0f
        for (i in 0 until 4) {
            fingers[i] = null
        }
        numFingers = 0
        fingerPointerButtonLeft = null
        fingerPointerButtonRight = null
        releaseTouchscreenPointers()

        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
        }
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
        }
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_MIDDLE)) {
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE)
        }
    }

    fun cancelMousePointerTimeout() {
        if (timeoutHandler != null && hideControlsRunnable != null) {
            timeoutHandler.removeCallbacks(hideControlsRunnable)
        }
    }
}
