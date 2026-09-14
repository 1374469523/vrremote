package com.vrremote.controller.protocol

/**
 * Decodes controller notifications.
 *
 * The parser never throws: an unexpected payload becomes [ParsedPacket.UnknownFrame]
 * so a malformed packet can never take the app down.
 */
object ControllerFrameParser {

    fun parse(bytes: ByteArray?): ParsedPacket {
        if (bytes == null) {
            return ParsedPacket.UnknownFrame(0, ByteArray(0))
        }
        if (bytes.size != FRAME_SIZE) {
            return ParsedPacket.UnknownFrame(bytes.size, bytes.copyOf())
        }
        return ParsedPacket.InputFrame(
            ControllerFrame(
                buttonByte = bytes[0].toInt() and 0xFF,
                touchpadX = bytes[15].toInt() and 0xFF,
                touchpadY = bytes[16].toInt() and 0xFF,
                battery = bytes[17].toInt() and 0xFF,
                // Orientation arrives as four big-endian int16 values. The
                // original Huawei decoder divides by 32767 to get a unit
                // quaternion, which is what the offsets below reproduce.
                rotationX = bigEndianShort(bytes, 1) / ROTATION_SCALE,
                rotationY = bigEndianShort(bytes, 3) / ROTATION_SCALE,
                rotationZ = bigEndianShort(bytes, 7) / ROTATION_SCALE,
                rotationW = bigEndianShort(bytes, 5) / ROTATION_SCALE,
                accX = bytes[9] * ACC_RANGE / SENSOR_SCALE,
                accY = bytes[10] * ACC_RANGE / SENSOR_SCALE,
                accZ = bytes[11] * ACC_RANGE / SENSOR_SCALE,
                gyroX = bytes[12] * GYRO_RANGE / SENSOR_SCALE,
                gyroY = bytes[13] * GYRO_RANGE / SENSOR_SCALE,
                gyroZ = bytes[14] * GYRO_RANGE / SENSOR_SCALE,
                raw = bytes,
            )
        )
    }

    private const val ROTATION_SCALE = 32767f
    private const val ACC_RANGE = 16f
    private const val GYRO_RANGE = 2000f
    private const val SENSOR_SCALE = 127f

    private fun bigEndianShort(bytes: ByteArray, offset: Int): Float =
        (((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF))
            .toShort()
            .toFloat()
}
