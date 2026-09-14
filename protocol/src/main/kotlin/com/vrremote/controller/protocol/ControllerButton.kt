package com.vrremote.controller.protocol

/**
 * Button bits carried in byte 0 of the 20 byte controller frame.
 *
 * Only the low six bits are used. Bits 6 and 7 are always set and act as a
 * frame marker; see [FRAME_MARKER].
 */
enum class ControllerButton(val mask: Int, val label: String) {
    TRIGGER(0x01, "扳机"),
    CONFIRM(0x02, "确认"),
    VOLUME_DOWN(0x04, "音量减"),
    VOLUME_UP(0x08, "音量加"),
    HOME(0x10, "主页"),
    BACK(0x20, "返回"),
}

/** Bits 6 and 7 of byte 0 are always set. */
const val FRAME_MARKER: Int = 0xC0

/** Everything a button can occupy. */
const val BUTTON_BITS: Int = 0x3F
