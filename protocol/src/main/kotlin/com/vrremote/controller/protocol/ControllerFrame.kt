package com.vrremote.controller.protocol

/** Length of one controller report. */
const val FRAME_SIZE: Int = 20

/**
 * One decoded 20 byte controller report.
 *
 * [raw] keeps the untouched bytes so the GUI can show exactly what arrived and
 * so samples can be frozen into test fixtures later.
 */
class ControllerFrame(
    /** Byte 0 exactly as received, including the 0xC0 marker bits. */
    val buttonByte: Int,
    val touchpadX: Int,
    val touchpadY: Int,
    val battery: Int,
    /** Orientation quaternion, one field per axis. */
    val rotationX: Float,
    val rotationY: Float,
    val rotationZ: Float,
    val rotationW: Float,
    /** Acceleration in g, decoded from signed bytes. */
    val accX: Float,
    val accY: Float,
    val accZ: Float,
    /** Angular velocity in degrees per second. */
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    raw: ByteArray,
) {
    val raw: ByteArray = raw.copyOf()

    /** The six button bits with the marker removed. */
    val buttonMask: Int get() = buttonByte and BUTTON_BITS

    val markerOk: Boolean get() = buttonByte and FRAME_MARKER == FRAME_MARKER

    fun isPressed(button: ControllerButton): Boolean = buttonByte and button.mask != 0

    val pressedButtons: List<ControllerButton>
        get() = ControllerButton.entries.filter { isPressed(it) }

    /**
     * Huawei's own SDK treats a zero pair as "not touched"; anything else is a
     * position on the flat touchpad.
     */
    val touchpadTouched: Boolean get() = touchpadX != 0 || touchpadY != 0

    fun rawHex(): String = raw.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
