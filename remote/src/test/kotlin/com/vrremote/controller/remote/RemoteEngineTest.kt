package com.vrremote.controller.remote

import com.vrremote.controller.protocol.ControllerButton
import com.vrremote.controller.protocol.ControllerFrame
import com.vrremote.controller.protocol.FRAME_MARKER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteEngineTest {

    private var now = 1_000L

    private fun engine(
        mode: RemoteMode = RemoteMode.NORMAL,
        settings: RemoteSettings = RemoteSettings(),
    ) = RemoteEngine(initialMode = mode, settings = { settings }, clock = { now })

    private fun frame(
        buttons: Int = 0,
        x: Int = 0,
        y: Int = 0,
        quaternion: FloatArray = floatArrayOf(0f, 0f, 0f, 1f),
    ) = ControllerFrame(
        buttonByte = FRAME_MARKER or buttons,
        touchpadX = x,
        touchpadY = y,
        battery = 80,
        rotationX = quaternion[0],
        rotationY = quaternion[1],
        rotationZ = quaternion[2],
        rotationW = quaternion[3],
        accX = 0f,
        accY = 0f,
        accZ = 0f,
        gyroX = 0f,
        gyroY = 0f,
        gyroZ = 0f,
        raw = ByteArray(20),
    )

    private fun keys(actions: List<RemoteAction>) =
        actions.filterIsInstance<RemoteAction.KeyPress>().map { it.keyCode }

    // ------------------------------------------------------------------ buttons

    @Test
    fun `back home and volume fire once per press`() {
        val engine = engine()
        engine.onFrame(frame())

        assertEquals(listOf(RemoteKey.BACK), keys(engine.onFrame(frame(ControllerButton.BACK.mask))))
        assertTrue(keys(engine.onFrame(frame(ControllerButton.BACK.mask))).isEmpty())

        assertEquals(listOf(RemoteKey.HOME), keys(engine.onFrame(frame(ControllerButton.HOME.mask))))
        assertTrue(keys(engine.onFrame(frame(ControllerButton.HOME.mask))).isEmpty())

        val volume = frame(ControllerButton.VOLUME_UP.mask)
        assertEquals(listOf(RemoteKey.VOLUME_UP), keys(engine.onFrame(volume)))
        assertTrue(keys(engine.onFrame(volume)).isEmpty())

        val down = frame(ControllerButton.VOLUME_DOWN.mask)
        assertEquals(listOf(RemoteKey.VOLUME_DOWN), keys(engine.onFrame(down)))
        assertTrue(keys(engine.onFrame(down)).isEmpty())
    }

    @Test
    fun `a key fires again after being released`() {
        val engine = engine()
        val pressed = frame(ControllerButton.BACK.mask)
        engine.onFrame(pressed)
        engine.onFrame(frame())
        assertEquals(listOf(RemoteKey.BACK), keys(engine.onFrame(pressed)))
    }

    @Test
    fun `the trigger cycles the mode order and debounces repeats`() {
        val engine = engine()
        val pressed = frame(ControllerButton.TRIGGER.mask)

        assertEquals(listOf(RemoteAction.CycleMode), engine.onFrame(pressed))
        assertTrue(engine.onFrame(pressed).isEmpty())

        engine.onFrame(frame())
        assertTrue("a second press within the debounce window is ignored", engine.onFrame(pressed).isEmpty())

        now += 400
        engine.onFrame(frame())
        assertEquals(listOf(RemoteAction.CycleMode), engine.onFrame(pressed))
    }

    @Test
    fun `mode cycle order is normal then touch then motion`() {
        assertEquals(RemoteMode.FLY_TOUCH, RemoteMode.NORMAL.next())
        assertEquals(RemoteMode.FLY_MOTION, RemoteMode.FLY_TOUCH.next())
        assertEquals(RemoteMode.NORMAL, RemoteMode.FLY_MOTION.next())
    }

    @Test
    fun `confirm is enter in normal mode and the left button in fly modes`() {
        val normal = engine(RemoteMode.NORMAL)
        normal.onFrame(frame())
        assertEquals(listOf(RemoteKey.ENTER), keys(normal.onFrame(frame(ControllerButton.CONFIRM.mask))))

        val fly = engine(RemoteMode.FLY_TOUCH)
        fly.onFrame(frame())
        assertEquals(
            listOf(RemoteAction.PointerButton(RemoteButton.LEFT, true)),
            fly.onFrame(frame(ControllerButton.CONFIRM.mask)),
        )
        assertEquals(
            listOf(RemoteAction.PointerButton(RemoteButton.LEFT, false)),
            fly.onFrame(frame()),
        )
    }

    // ------------------------------------------------------------ normal mode

    @Test
    fun `a step on the touchpad produces exactly one direction key`() {
        val engine = engine()
        engine.onFrame(frame(x = 100, y = 100))
        val actions = engine.onFrame(frame(x = 100 + 40, y = 100))
        assertEquals(listOf(RemoteKey.DPAD_RIGHT), keys(actions))
    }

    @Test
    fun `movement below the step size produces nothing`() {
        val engine = engine()
        engine.onFrame(frame(x = 100, y = 100))
        assertTrue(keys(engine.onFrame(frame(x = 110, y = 105))).isEmpty())
    }

    @Test
    fun `the dominant axis wins`() {
        val engine = engine()
        engine.onFrame(frame(x = 100, y = 100))
        assertEquals(listOf(RemoteKey.DPAD_UP), keys(engine.onFrame(frame(x = 105, y = 60))))

        val other = engine()
        other.onFrame(frame(x = 100, y = 100))
        assertEquals(listOf(RemoteKey.DPAD_DOWN), keys(other.onFrame(frame(x = 110, y = 160))))
    }

    @Test
    fun `each further step emits another key`() {
        val engine = engine()
        engine.onFrame(frame(x = 100, y = 100))
        assertEquals(listOf(RemoteKey.DPAD_RIGHT), keys(engine.onFrame(frame(x = 140, y = 100))))
        assertEquals(listOf(RemoteKey.DPAD_RIGHT), keys(engine.onFrame(frame(x = 180, y = 100))))
    }

    @Test
    fun `lifting the finger resets the anchor`() {
        val engine = engine()
        engine.onFrame(frame(x = 100, y = 100))
        engine.onFrame(frame(x = 140, y = 100))
        engine.onFrame(frame(x = 0, y = 0))
        assertTrue(
            "touching down again must not fire",
            keys(engine.onFrame(frame(x = 200, y = 100))).isEmpty(),
        )
    }

    @Test
    fun `the step size follows the settings`() {
        val engine = engine(settings = RemoteSettings(dpadStep = 60))
        engine.onFrame(frame(x = 100, y = 100))
        assertTrue(keys(engine.onFrame(frame(x = 140, y = 100))).isEmpty())
        assertEquals(listOf(RemoteKey.DPAD_RIGHT), keys(engine.onFrame(frame(x = 165, y = 100))))
    }

    // ---------------------------------------------------------- touch fly mode

    @Test
    fun `touch fly mode does not move the cursor on touch down`() {
        val engine = engine(RemoteMode.FLY_TOUCH)
        engine.onFrame(frame(x = 0, y = 0))
        assertTrue(engine.onFrame(frame(x = 50, y = 50)).isEmpty())
    }

    @Test
    fun `touch fly mode scales touchpad movement`() {
        val engine = engine(RemoteMode.FLY_TOUCH, RemoteSettings(flyTouchGain = 4f))
        engine.onFrame(frame(x = 0, y = 0))
        engine.onFrame(frame(x = 50, y = 50))
        assertEquals(
            listOf(RemoteAction.PointerMove(40, 80)),
            engine.onFrame(frame(x = 60, y = 70)),
        )
    }

    @Test
    fun `touch fly mode ignores jitter below the dead zone`() {
        val engine = engine(RemoteMode.FLY_TOUCH)
        engine.onFrame(frame(x = 0, y = 0))
        engine.onFrame(frame(x = 50, y = 50))
        assertTrue(engine.onFrame(frame(x = 52, y = 51)).isEmpty())
    }

    // --------------------------------------------------------- motion fly mode

    @Test
    fun `motion fly mode needs a baseline frame first`() {
        val engine = engine(RemoteMode.FLY_MOTION)
        assertTrue(engine.onFrame(frame(quaternion = quat(yaw = 0.4f))).isEmpty())
    }

    @Test
    fun `motion fly mode ignores hand tremor`() {
        val engine = engine(RemoteMode.FLY_MOTION)
        engine.onFrame(frame())
        assertTrue(engine.onFrame(frame(quaternion = quat(yaw = 0.0005f))).isEmpty())
    }

    @Test
    fun `motion fly mode moves the cursor to the right when yawing right`() {
        val engine = engine(
            RemoteMode.FLY_MOTION,
            RemoteSettings(flyMotionGain = 1000f, flyMotionDeadZone = 0.001f),
        )
        engine.onFrame(frame())
        val actions = engine.onFrame(frame(quaternion = quat(yaw = 0.05f)))
        val move = actions.filterIsInstance<RemoteAction.PointerMove>().single()
        assertEquals(50, move.dx)
        assertEquals(0, move.dy)
    }

    @Test
    fun `motion fly mode moves the cursor up when pitching up`() {
        val engine = engine(
            RemoteMode.FLY_MOTION,
            RemoteSettings(flyMotionGain = 1000f, flyMotionDeadZone = 0.001f),
        )
        engine.onFrame(frame())
        val actions = engine.onFrame(frame(quaternion = quat(pitch = 0.05f)))
        val move = actions.filterIsInstance<RemoteAction.PointerMove>().single()
        assertEquals(0, move.dx)
        assertEquals(-50, move.dy)
    }

    @Test
    fun `switching mode takes a fresh baseline`() {
        val engine = engine(RemoteMode.FLY_MOTION)
        engine.onFrame(frame(quaternion = quat(pitch = 0.2f)))
        engine.onFrame(frame(quaternion = quat(pitch = 0.3f)))
        engine.cycleMode()
        engine.setMode(RemoteMode.FLY_MOTION)
        assertTrue(engine.onFrame(frame(quaternion = quat(pitch = 0.9f))).isEmpty())
    }

    // ------------------------------------------------------------------ session

    @Test
    fun `reset releases a held mouse button`() {
        val engine = engine(RemoteMode.FLY_TOUCH)
        engine.onFrame(frame())
        engine.onFrame(frame(ControllerButton.CONFIRM.mask))
        assertEquals(
            listOf(RemoteAction.PointerButton(RemoteButton.LEFT, false)),
            engine.reset(),
        )
    }

    @Test
    fun `switching mode releases a held mouse button`() {
        val engine = engine(RemoteMode.FLY_TOUCH)
        engine.onFrame(frame())
        engine.onFrame(frame(ControllerButton.CONFIRM.mask))
        assertEquals(
            listOf(RemoteAction.PointerButton(RemoteButton.LEFT, false)),
            engine.setMode(RemoteMode.NORMAL),
        )
    }

    @Test
    fun `reset forgets held buttons so the next frame is not a release`() {
        val engine = engine()
        engine.onFrame(frame(ControllerButton.BACK.mask))
        engine.reset()
        assertTrue(keys(engine.onFrame(frame(ControllerButton.BACK.mask))).isNotEmpty())
    }

    /** Small-angle quaternion: rotation of `angle` radians around one axis. */
    private fun quat(pitch: Float = 0f, yaw: Float = 0f, roll: Float = 0f): FloatArray {
        val x = pitch * 0.5f
        val y = yaw * 0.5f
        val z = roll * 0.5f
        return floatArrayOf(
            x,
            y,
            z,
            kotlin.math.sqrt(1f - (x * x + y * y + z * z)),
        )
    }
}
