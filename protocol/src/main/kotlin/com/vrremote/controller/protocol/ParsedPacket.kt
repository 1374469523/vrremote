package com.vrremote.controller.protocol

/** Result of decoding one notification payload. */
sealed interface ParsedPacket {

    /** A well formed 20 byte report. */
    data class InputFrame(val frame: ControllerFrame) : ParsedPacket

    /** Anything that is not exactly 20 bytes; kept so the GUI can show it. */
    data class UnknownFrame(val length: Int, val raw: ByteArray) : ParsedPacket {
        override fun equals(other: Any?): Boolean =
            other is UnknownFrame && other.length == length && other.raw.contentEquals(raw)

        override fun hashCode(): Int = 31 * length + raw.contentHashCode()
    }
}
