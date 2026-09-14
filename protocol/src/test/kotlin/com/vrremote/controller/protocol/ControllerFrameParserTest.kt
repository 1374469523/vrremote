package com.vrremote.controller.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerFrameParserTest {

    private fun frameOf(vararg overrides: Pair<Int, Int>): ByteArray {
        val bytes = ByteArray(FRAME_SIZE)
        bytes[0] = FRAME_MARKER.toByte()
        for ((index, value) in overrides) {
            bytes[index] = value.toByte()
        }
        return bytes
    }

    private fun inputFrameOf(vararg overrides: Pair<Int, Int>): ControllerFrame {
        val parsed = ControllerFrameParser.parse(frameOf(*overrides))
        assertTrue(parsed is ParsedPacket.InputFrame)
        return (parsed as ParsedPacket.InputFrame).frame
    }

    @Test
    fun `a 20 byte frame is decoded`() {
        val parsed = ControllerFrameParser.parse(frameOf())
        assertTrue(parsed is ParsedPacket.InputFrame)
    }

    @Test
    fun `an all zero frame reports no buttons`() {
        val frame = inputFrameOf()
        assertEquals(0, frame.buttonMask)
        assertTrue(frame.pressedButtons.isEmpty())
        assertFalse(frame.touchpadTouched)
    }

    @Test
    fun `every single button bit is decoded`() {
        for (button in ControllerButton.entries) {
            val frame = inputFrameOf(0 to (FRAME_MARKER or button.mask))
            assertTrue("$button should be pressed", frame.isPressed(button))
            assertEquals(
                "only $button should be pressed",
                listOf(button),
                frame.pressedButtons,
            )
        }
    }

    @Test
    fun `combined button bits are decoded`() {
        val frame = inputFrameOf(0 to (FRAME_MARKER or 0x0A))
        assertEquals(listOf(ControllerButton.CONFIRM, ControllerButton.VOLUME_UP), frame.pressedButtons)
    }

    @Test
    fun `all button bits at once are decoded`() {
        val frame = inputFrameOf(0 to (FRAME_MARKER or BUTTON_BITS))
        assertEquals(ControllerButton.entries.size, frame.pressedButtons.size)
    }

    @Test
    fun `a missing marker is reported but the frame still decodes`() {
        val frame = inputFrameOf(0 to 0x02)
        assertFalse(frame.markerOk)
        assertTrue(frame.isPressed(ControllerButton.CONFIRM))
    }

    @Test
    fun `the touchpad position is decoded`() {
        val frame = inputFrameOf(15 to 128, 16 to 200)
        assertEquals(128, frame.touchpadX)
        assertEquals(200, frame.touchpadY)
        assertTrue(frame.touchpadTouched)
    }

    @Test
    fun `a zero pair means the touchpad is not touched`() {
        val frame = inputFrameOf(15 to 0, 16 to 0)
        assertFalse(frame.touchpadTouched)
    }

    @Test
    fun `a single non zero axis already counts as touched`() {
        assertTrue(inputFrameOf(15 to 0, 16 to 1).touchpadTouched)
        assertTrue(inputFrameOf(15 to 1, 16 to 0).touchpadTouched)
    }

    @Test
    fun `touchpad extremes stay unsigned`() {
        val frame = inputFrameOf(15 to 255, 16 to 255)
        assertEquals(255, frame.touchpadX)
        assertEquals(255, frame.touchpadY)
    }

    @Test
    fun `the battery byte stays unsigned`() {
        assertEquals(87, inputFrameOf(17 to 87).battery)
        assertEquals(255, inputFrameOf(17 to 255).battery)
    }

    @Test
    fun `the button byte stays unsigned`() {
        assertEquals(FRAME_MARKER or 0x20, inputFrameOf(0 to (FRAME_MARKER or 0x20)).buttonByte)
    }

    @Test
    fun `a short frame is unknown`() {
        val parsed = ControllerFrameParser.parse(ByteArray(FRAME_SIZE - 1))
        assertEquals(ParsedPacket.UnknownFrame(FRAME_SIZE - 1, ByteArray(FRAME_SIZE - 1)), parsed)
    }

    @Test
    fun `a long frame is unknown`() {
        val parsed = ControllerFrameParser.parse(ByteArray(FRAME_SIZE + 1))
        assertEquals(ParsedPacket.UnknownFrame(FRAME_SIZE + 1, ByteArray(FRAME_SIZE + 1)), parsed)
    }

    @Test
    fun `an empty frame is unknown`() {
        assertEquals(ParsedPacket.UnknownFrame(0, ByteArray(0)), ControllerFrameParser.parse(ByteArray(0)))
    }

    @Test
    fun `a null payload is unknown and does not throw`() {
        assertEquals(ParsedPacket.UnknownFrame(0, ByteArray(0)), ControllerFrameParser.parse(null))
    }

    @Test
    fun `the raw copy is independent of the source buffer`() {
        val source = frameOf(0 to (FRAME_MARKER or 0x08))
        val frame = (ControllerFrameParser.parse(source) as ParsedPacket.InputFrame).frame
        source[0] = 0
        assertEquals(FRAME_MARKER or 0x08, frame.buttonByte)
        assertEquals(FRAME_SIZE, frame.raw.size)
    }

    @Test
    fun `raw hex renders every byte`() {
        val hex = inputFrameOf(0 to 0xC2).rawHex()
        assertTrue(hex.startsWith("C2 00 00"))
        assertEquals(FRAME_SIZE, hex.split(" ").size)
    }

    @Test
    fun `motion bytes follow the original Huawei scaling`() {
        // Captured from the real controller on the phone.
        val raw = byteArrayOf(
            0xC0.toByte(), 0xF9.toByte(), 0x65, 0xCC.toByte(), 0xE1.toByte(),
            0x8A.toByte(), 0xF2.toByte(), 0x04, 0xD4.toByte(), 0xFD.toByte(),
            0x5A, 0xFE.toByte(), 0x2A, 0xFF.toByte(), 0x5E, 0x00,
            0x00, 0x4F, 0x64, 0x95.toByte(),
        )
        val frame = (ControllerFrameParser.parse(raw) as ParsedPacket.InputFrame).frame

        assertEquals(-1691f / 32767f, frame.rotationX, 1e-6f)
        assertEquals(-13087f / 32767f, frame.rotationY, 1e-6f)
        assertEquals(-29966f / 32767f, frame.rotationW, 1e-6f)
        assertEquals(1236f / 32767f, frame.rotationZ, 1e-6f)
        assertEquals(-3 * 16f / 127f, frame.accX, 1e-6f)
        assertEquals(90 * 16f / 127f, frame.accY, 1e-6f)
        assertEquals(-2 * 16f / 127f, frame.accZ, 1e-6f)
        assertEquals(42 * 2000f / 127f, frame.gyroX, 1e-6f)
        assertEquals(-1 * 2000f / 127f, frame.gyroY, 1e-6f)
        assertEquals(94 * 2000f / 127f, frame.gyroZ, 1e-6f)
        assertEquals(79, frame.battery)
    }

    @Test
    fun `an all zero frame reports a neutral quaternion`() {
        val frame = inputFrameOf()
        assertEquals(0f, frame.rotationX, 0f)
        assertEquals(0f, frame.accX, 0f)
        assertEquals(0f, frame.gyroX, 0f)
    }

    @Test
    fun `an idle capture keeps the touchpad released`() {
        // Also captured from the real controller: finger off the touchpad, no
        // button held, 76 percent battery.
        val raw = byteArrayOf(
            0xC0.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x85.toByte(), 0x17,
            0xDD.toByte(), 0x3D, 0x08, 0x2D, 0xFE.toByte(),
            0xBF.toByte(), 0x03, 0x46, 0xFE.toByte(), 0x91.toByte(), 0x00,
            0x00, 0x4C, 0x2B, 0x06,
        )
        val frame = (ControllerFrameParser.parse(raw) as ParsedPacket.InputFrame).frame

        assertEquals(0, frame.buttonMask)
        assertFalse(frame.touchpadTouched)
        assertEquals(76, frame.battery)
        assertTrue(frame.markerOk)
    }
}
