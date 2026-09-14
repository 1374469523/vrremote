package com.vrremote.controller.remote

import com.vrremote.controller.protocol.ControllerButton
import com.vrremote.controller.protocol.ControllerFrame
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns controller frames into phone actions.
 *
 * The engine owns no Android state and no I/O: it is fed one frame and returns
 * the actions that frame produced, which keeps every rule unit testable.
 */
class RemoteEngine(
    initialMode: RemoteMode = RemoteMode.NORMAL,
    private val settings: () -> RemoteSettings = { RemoteSettings() },
    private val profile: () -> AppProfile? = { null },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    companion object {
        /** Minimum gap between two trigger-driven mode switches. */
        private const val MODE_SWITCH_DEBOUNCE_MS = 300L

        /** Touchpad travel that counts as a swipe inside a configured app. */
        private const val PROFILE_SWIPE_THRESHOLD = 40

        /**
         * Rotation around the controller's yaw axis moves the cursor
         * horizontally, pitch moves it vertically. Screen Y grows downwards,
         * hence the negative pitch factor.
         *
         * Signs come from the real controller: turning it right makes the
         * reported yaw component go negative, so the yaw factor is negated too.
         */
        private const val YAW_TO_X = -1f
        private const val PITCH_TO_Y = -1f

        private val REMOTE_KEYS = listOf(
            ControllerButton.BACK to RemoteKey.BACK,
            ControllerButton.HOME to RemoteKey.HOME,
            ControllerButton.VOLUME_UP to RemoteKey.VOLUME_UP,
            ControllerButton.VOLUME_DOWN to RemoteKey.VOLUME_DOWN,
        )
    }

    var mode: RemoteMode = initialMode
        private set

    private var pressedBefore = 0
    private var lastModeSwitchAt = 0L

    private var touchActive = false
    private var anchorX = 0
    private var anchorY = 0

    private var previousQuaternion: FloatArray? = null
    private var leftButtonDown = false

    /** One swipe fires once; the latch clears when the finger leaves the pad. */
    private var profileSwipeLatched = false

    /**
     * Sub-pixel pointer movement carried over between frames.
     *
     * Reports arrive about 100 times a second, so a slow drag only moves a
     * fraction of a pixel per frame. Without this remainder the cursor would
     * appear stuck until the finger sped up.
     */
    private var pendingPointerX = 0f
    private var pendingPointerY = 0f

    /** Switches mode; returns the actions needed to leave the previous mode cleanly. */
    fun setMode(next: RemoteMode): List<RemoteAction> {
        if (next == mode) return emptyList()
        mode = next
        return clearSessionState()
    }

    fun cycleMode(): List<RemoteAction> = setMode(mode.next())

    /** Call when the controller disconnects so nothing leaks into the next session. */
    fun reset(): List<RemoteAction> {
        pressedBefore = 0
        lastModeSwitchAt = 0L
        return clearSessionState()
    }

    private fun clearSessionState(): List<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()
        if (leftButtonDown) {
            leftButtonDown = false
            actions += RemoteAction.PointerButton(RemoteButton.LEFT, false)
        }
        touchActive = false
        profileSwipeLatched = false
        anchorX = 0
        anchorY = 0
        previousQuaternion = null
        pendingPointerX = 0f
        pendingPointerY = 0f
        return actions
    }

    fun onFrame(frame: ControllerFrame): List<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()
        val pressed = frame.buttonMask

        consumeTrigger(pressed, actions)

        when (mode) {
            RemoteMode.NORMAL -> {
                consumeRemoteKeys(pressed, actions)
                consumeNormal(frame, pressed, actions)
            }

            RemoteMode.FLY_TOUCH -> {
                consumeRemoteKeys(pressed, actions)
                consumeTouchFly(frame, pressed, actions)
            }

            RemoteMode.FLY_MOTION -> {
                consumeRemoteKeys(pressed, actions)
                consumeMotionFly(frame, pressed, actions)
            }

            RemoteMode.PROFILE -> consumeProfile(frame, pressed, actions)
        }

        pressedBefore = pressed
        return actions
    }

    // ------------------------------------------------------------------ buttons

    private fun consumeTrigger(pressed: Int, actions: MutableList<RemoteAction>) {
        val bit = ControllerButton.TRIGGER.mask
        val was = (pressedBefore and bit) != 0
        if ((pressed and bit) == 0 || was) return
        val now = clock()
        if (now - lastModeSwitchAt < MODE_SWITCH_DEBOUNCE_MS) return
        lastModeSwitchAt = now
        actions += RemoteAction.CycleMode
    }

    private fun consumeRemoteKeys(pressed: Int, actions: MutableList<RemoteAction>) {
        for ((button, keyCode) in REMOTE_KEYS) {
            val bit = button.mask
            val isDown = (pressed and bit) != 0
            val wasDown = (pressedBefore and bit) != 0
            if (isDown && !wasDown) actions += RemoteAction.KeyPress(keyCode)
        }
    }

    private fun consumeConfirm(
        pressed: Int,
        actions: MutableList<RemoteAction>,
        asMouseButton: Boolean,
    ) {
        val bit = ControllerButton.CONFIRM.mask
        val isDown = (pressed and bit) != 0
        val wasDown = (pressedBefore and bit) != 0
        if (isDown == wasDown) return
        if (asMouseButton) {
            leftButtonDown = isDown
            actions += RemoteAction.PointerButton(RemoteButton.LEFT, isDown)
        } else if (isDown) {
            actions += RemoteAction.KeyPress(RemoteKey.ENTER)
        }
    }

    // ------------------------------------------------------------------- modes

    private fun consumeNormal(
        frame: ControllerFrame,
        pressed: Int,
        actions: MutableList<RemoteAction>,
    ) {
        consumeConfirm(pressed, actions, asMouseButton = false)
        consumeDpadSteps(frame, actions)
    }

    private fun consumeTouchFly(
        frame: ControllerFrame,
        pressed: Int,
        actions: MutableList<RemoteAction>,
    ) {
        consumeConfirm(pressed, actions, asMouseButton = true)
        if (!frame.touchpadTouched) {
            touchActive = false
            return
        }
        val x = frame.touchpadX
        val y = frame.touchpadY
        if (!touchActive) {
            touchActive = true
            anchorX = x
            anchorY = y
            return
        }
        val dx = x - anchorX
        val dy = y - anchorY
        anchorX = x
        anchorY = y
        if (dx == 0 && dy == 0) return
        val gain = settings().flyTouchGain
        emitPointer(pendingPointerX + dx * gain, pendingPointerY + dy * gain, actions)
    }

    private fun consumeMotionFly(
        frame: ControllerFrame,
        pressed: Int,
        actions: MutableList<RemoteAction>,
    ) {
        consumeConfirm(pressed, actions, asMouseButton = true)
        val current = floatArrayOf(
            frame.rotationX,
            frame.rotationY,
            frame.rotationZ,
            frame.rotationW,
        )
        val previous = previousQuaternion
        previousQuaternion = current
        if (previous == null) return
        if (current.isDegenerate() || previous.isDegenerate()) return

        // Rotation of this frame relative to the previous one, expressed in the
        // controller's own frame. Using only the delta means the cursor never
        // drifts, and no "point at the screen" calibration step is needed.
        val delta = multiply(current, conjugate(previous))
        val yaw = 2f * delta[1]
        val pitch = 2f * delta[0]
        val config = settings()
        val dx = if (abs(yaw) < config.flyMotionDeadZone) 0f else yaw * config.flyMotionGain * YAW_TO_X
        val dy = if (abs(pitch) < config.flyMotionDeadZone) 0f else pitch * config.flyMotionGain * PITCH_TO_Y
        if (dx == 0f && dy == 0f) return
        emitPointer(pendingPointerX + dx, pendingPointerY + dy, actions)
    }

    /**
     * Sends whole pixels and keeps the fraction for the next frame, so slow
     * movement is never rounded away.
     */
    private fun emitPointer(rawX: Float, rawY: Float, actions: MutableList<RemoteAction>) {
        val stepX = rawX.toInt()
        val stepY = rawY.toInt()
        pendingPointerX = rawX - stepX
        pendingPointerY = rawY - stepY
        if (stepX != 0 || stepY != 0) {
            actions += RemoteAction.PointerMove(stepX, stepY)
        }
    }

    /**
     * One direction key per step of travel: the finger has to move
     * [RemoteSettings.dpadStep] units on the main axis before the next key, so
     * sliding slowly walks the focus one item at a time.
     */
    private fun consumeDpadSteps(frame: ControllerFrame, actions: MutableList<RemoteAction>) {
        if (!frame.touchpadTouched) {
            touchActive = false
            return
        }
        val x = frame.touchpadX
        val y = frame.touchpadY
        if (!touchActive) {
            touchActive = true
            anchorX = x
            anchorY = y
            return
        }
        val dx = x - anchorX
        val dy = y - anchorY
        val step = settings().dpadStep
        if (abs(dx) < step && abs(dy) < step) return
        actions += if (abs(dx) >= abs(dy)) {
            RemoteAction.KeyPress(if (dx > 0) RemoteKey.DPAD_RIGHT else RemoteKey.DPAD_LEFT)
        } else {
            RemoteAction.KeyPress(if (dy > 0) RemoteKey.DPAD_DOWN else RemoteKey.DPAD_UP)
        }
        anchorX = x
        anchorY = y
    }

    // --------------------------------------------------------- profile mode

    /**
     * Follows the app in the foreground: every trigger runs whatever that app
     * was configured with. Apps without a profile do nothing at all.
     */
    private fun consumeProfile(
        frame: ControllerFrame,
        pressed: Int,
        actions: MutableList<RemoteAction>,
    ) {
        val active = profile()?.takeIf { it.enabled } ?: run {
            touchActive = false
            profileSwipeLatched = false
            return
        }

        consumePressEdge(
            pressed = pressed,
            mask = ControllerButton.CONFIRM.mask,
            action = active.actionFor(ProfileTrigger.TOUCH_PRESS),
            actions = actions,
        )
        consumePressEdge(
            pressed = pressed,
            mask = ControllerButton.VOLUME_UP.mask,
            action = active.actionFor(ProfileTrigger.VOLUME_UP),
            fallbackKey = RemoteKey.VOLUME_UP,
            actions = actions,
        )
        consumePressEdge(
            pressed = pressed,
            mask = ControllerButton.VOLUME_DOWN.mask,
            action = active.actionFor(ProfileTrigger.VOLUME_DOWN),
            fallbackKey = RemoteKey.VOLUME_DOWN,
            actions = actions,
        )

        if (!frame.touchpadTouched) {
            touchActive = false
            profileSwipeLatched = false
            return
        }
        val x = frame.touchpadX
        val y = frame.touchpadY
        if (!touchActive) {
            touchActive = true
            profileSwipeLatched = false
            anchorX = x
            anchorY = y
            return
        }
        if (profileSwipeLatched) return
        val dx = x - anchorX
        val dy = y - anchorY
        if (abs(dx) < PROFILE_SWIPE_THRESHOLD && abs(dy) < PROFILE_SWIPE_THRESHOLD) return
        val trigger = if (abs(dx) >= abs(dy)) {
            if (dx > 0) ProfileTrigger.TOUCH_RIGHT else ProfileTrigger.TOUCH_LEFT
        } else {
            if (dy > 0) ProfileTrigger.TOUCH_DOWN else ProfileTrigger.TOUCH_UP
        }
        profileSwipeLatched = true
        emitProfileAction(active.actionFor(trigger), actions)
    }

    private fun consumePressEdge(
        pressed: Int,
        mask: Int,
        action: ProfileAction,
        fallbackKey: Int? = null,
        actions: MutableList<RemoteAction>,
    ) {
        val isDown = (pressed and mask) != 0
        val wasDown = (pressedBefore and mask) != 0
        if (!isDown || wasDown) return
        // The volume keys keep behaving like volume unless the app rebinds them:
        // silently swallowing them would surprise anyone who added an app just
        // to remap the touchpad.
        if (action == ProfileAction.NONE && fallbackKey != null) {
            actions += RemoteAction.KeyPress(fallbackKey)
            return
        }
        emitProfileAction(action, actions)
    }

    private fun emitProfileAction(action: ProfileAction, actions: MutableList<RemoteAction>) {
        when (action) {
            ProfileAction.NONE -> Unit
            ProfileAction.SWIPE_UP -> actions += RemoteAction.Swipe(SwipeDirection.UP)
            ProfileAction.SWIPE_DOWN -> actions += RemoteAction.Swipe(SwipeDirection.DOWN)
            ProfileAction.SWIPE_LEFT -> actions += RemoteAction.Swipe(SwipeDirection.LEFT)
            ProfileAction.SWIPE_RIGHT -> actions += RemoteAction.Swipe(SwipeDirection.RIGHT)
            ProfileAction.TAP_LEFT -> actions += RemoteAction.Tap(TapPosition.LEFT)
            ProfileAction.TAP_CENTER -> actions += RemoteAction.Tap(TapPosition.CENTER)
            ProfileAction.TAP_RIGHT -> actions += RemoteAction.Tap(TapPosition.RIGHT)
            ProfileAction.TAP_BOTTOM -> actions += RemoteAction.Tap(TapPosition.BOTTOM)
            ProfileAction.DOUBLE_TAP -> actions += RemoteAction.DoubleTap(TapPosition.CENTER)
            ProfileAction.LONG_PRESS -> actions += RemoteAction.LongPress(TapPosition.CENTER)
            else -> action.keyCode?.let { actions += RemoteAction.KeyPress(it) }
        }
    }

    // -------------------------------------------------------------------- maths

    private fun FloatArray.isDegenerate(): Boolean {
        val sum = this[0] * this[0] + this[1] * this[1] + this[2] * this[2] + this[3] * this[3]
        return sum < 0.1f
    }

    private fun conjugate(q: FloatArray) = floatArrayOf(-q[0], -q[1], -q[2], q[3])

    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val (ax, ay, az, aw) = a
        val (bx, by, bz, bw) = b
        return floatArrayOf(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz,
        )
    }

    private operator fun FloatArray.component1() = this[0]
    private operator fun FloatArray.component2() = this[1]
    private operator fun FloatArray.component3() = this[2]
    private operator fun FloatArray.component4() = this[3]

}
