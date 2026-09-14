package com.vrremote.controller.input

/**
 * HID report descriptors for the three virtual devices.
 *
 * Usages are plain AOSP/Linux ones, so key codes are resolved by the device's
 * own `Generic.kl` and nothing here depends on a specific vendor:
 *
 * - keyboard arrows   0x4F–0x52 → Linux 106/105/103/108 → KEYCODE_DPAD_*
 * - keyboard enter    0x28      → Linux 28               → KEYCODE_ENTER
 * - consumer AC Home  0x223     → Linux 172              → KEYCODE_HOME
 * - consumer AC Back  0x224     → Linux 158              → KEYCODE_BACK
 * - consumer volume   0xE9/0xEA → Linux 115/114          → KEYCODE_VOLUME_*
 */
internal object HidDescriptors {

    /** Modifier byte, reserved byte and six key slots. */
    const val KEYBOARD_REPORT_SIZE = 8

    /** Twelve consumer controls plus four padding bits. */
    const val CONSUMER_REPORT_SIZE = 2

    /** Buttons, X, Y, wheel. */
    const val MOUSE_REPORT_SIZE = 4

    /** Standard boot keyboard, the same shape scrcpy uses (Apache-2.0). */
    val KEYBOARD = hid(
        0x05, 0x01,   // Usage Page (Generic Desktop)
        0x09, 0x06,   // Usage (Keyboard)
        0xA1, 0x01,   // Collection (Application)
        0x05, 0x07,   //   Usage Page (Key Codes)
        0x19, 0xE0,   //   Usage Minimum (224)
        0x29, 0xE7,   //   Usage Maximum (231)
        0x15, 0x00,   //   Logical Minimum (0)
        0x25, 0x01,   //   Logical Maximum (1)
        0x75, 0x01,   //   Report Size (1)
        0x95, 0x08,   //   Report Count (8)
        0x81, 0x02,   //   Input (Data, Variable, Absolute)
        0x75, 0x08,   //   Report Size (8)
        0x95, 0x01,   //   Report Count (1)
        0x81, 0x01,   //   Input (Constant) reserved byte
        0x05, 0x08,   //   Usage Page (LEDs)
        0x19, 0x01,   //   Usage Minimum (1)
        0x29, 0x05,   //   Usage Maximum (5)
        0x75, 0x01,   //   Report Size (1)
        0x95, 0x05,   //   Report Count (5)
        0x91, 0x02,   //   Output (Data, Variable, Absolute)
        0x75, 0x03,   //   Report Size (3)
        0x95, 0x01,   //   Report Count (1)
        0x91, 0x01,   //   Output (Constant) padding
        0x05, 0x07,   //   Usage Page (Key Codes)
        0x19, 0x00,   //   Usage Minimum (0)
        0x29, 0x65,   //   Usage Maximum (101)
        0x15, 0x00,   //   Logical Minimum (0)
        0x25, 0x65,   //   Logical Maximum (101)
        0x75, 0x08,   //   Report Size (8)
        0x95, 0x06,   //   Report Count (6)
        0x81, 0x00,   //   Input (Data, Array)
        0xC0,         // End Collection
    )

    /**
     * Consumer control page for the keys a keyboard collection cannot express.
     *
     * The kernel translates these usages into Linux key codes, which the device
     * key layout then turns into Android key codes:
     *
     * 0xE9/0xEA → KEY_VOLUMEUP/DOWN → 音量键
     * 0x223/0x224 → KEY_HOMEPAGE/KEY_BACK → 主页/返回
     * 0xCD/0xB5/0xB6/0xB3/0xB4 → 播放暂停/下一个/上一个/快进/快退
     * 0xE2 → KEY_MUTE → 静音
     * 0x40 → KEY_MENU → 菜单
     * 0x1A2 → KEY_APPSELECT → 最近任务
     */
    val CONSUMER = hid(
        0x05, 0x0C,   // Usage Page (Consumer)
        0x09, 0x01,   // Usage (Consumer Control)
        0xA1, 0x01,   // Collection (Application)
        0x15, 0x00,   //   Logical Minimum (0)
        0x25, 0x01,   //   Logical Maximum (1)
        0x75, 0x01,   //   Report Size (1)
        0x95, 0x0C,   //   Report Count (12)
        0x09, 0xE9,   //   Usage (Volume Increment)
        0x09, 0xEA,   //   Usage (Volume Decrement)
        0x0A, 0x23, 0x02, // Usage (AC Home)
        0x0A, 0x24, 0x02, // Usage (AC Back)
        0x09, 0xCD,   //   Usage (Play/Pause)
        0x09, 0xB5,   //   Usage (Scan Next Track)
        0x09, 0xB6,   //   Usage (Scan Previous Track)
        0x09, 0xB3,   //   Usage (Fast Forward)
        0x09, 0xB4,   //   Usage (Rewind)
        0x09, 0xE2,   //   Usage (Mute)
        0x09, 0x40,   //   Usage (Menu)
        0x0A, 0xA2, 0x01, // Usage (Application launch, KEY_APPSELECT)
        0x81, 0x02,   //   Input (Data, Variable, Absolute)
        0x95, 0x04,   //   Report Count (4)
        0x81, 0x03,   //   Input (Constant) padding
        0xC0,         // End Collection
    )

    /** Relative mouse, the same shape scrcpy uses (Apache-2.0). */
    val MOUSE = hid(
        0x05, 0x01,   // Usage Page (Generic Desktop)
        0x09, 0x02,   // Usage (Mouse)
        0xA1, 0x01,   // Collection (Application)
        0x09, 0x01,   //   Usage (Pointer)
        0xA1, 0x00,   //   Collection (Physical)
        0x05, 0x09,   //     Usage Page (Buttons)
        0x19, 0x01,   //     Usage Minimum (1)
        0x29, 0x03,   //     Usage Maximum (3)
        0x15, 0x00,   //     Logical Minimum (0)
        0x25, 0x01,   //     Logical Maximum (1)
        0x95, 0x03,   //     Report Count (3)
        0x75, 0x01,   //     Report Size (1)
        0x81, 0x02,   //     Input (Data, Variable, Absolute)
        0x95, 0x01,   //     Report Count (1)
        0x75, 0x05,   //     Report Size (5)
        0x81, 0x03,   //     Input (Constant) padding
        0x05, 0x01,   //     Usage Page (Generic Desktop)
        0x09, 0x30,   //     Usage (X)
        0x09, 0x31,   //     Usage (Y)
        0x09, 0x38,   //     Usage (Wheel)
        0x15, 0x81,   //     Logical Minimum (-127)
        0x25, 0x7F,   //     Logical Maximum (127)
        0x75, 0x08,   //     Report Size (8)
        0x95, 0x03,   //     Report Count (3)
        0x81, 0x06,   //     Input (Data, Variable, Relative)
        0xC0,         //   End Collection
        0xC0,         // End Collection
    )

    /** Keyboard usage for an Android key code, or null when it is not a keyboard key. */
    fun keyboardUsage(keyCode: Int): Int? = when (keyCode) {
        19 -> 0x52 // dpad up
        20 -> 0x51 // dpad down
        21 -> 0x50 // dpad left
        22 -> 0x4F // dpad right
        66 -> 0x28 // enter
        92 -> 0x4B // page up
        93 -> 0x4E // page down
        else -> null
    }

    /** Consumer bit for an Android key code, or null when it is not a consumer key. */
    fun consumerBit(keyCode: Int): Int? = when (keyCode) {
        24 -> 0x0001 // volume up
        25 -> 0x0002 // volume down
        3 -> 0x0004  // home
        4 -> 0x0008  // back
        85 -> 0x0010 // play / pause
        87 -> 0x0020 // next track
        88 -> 0x0040 // previous track
        90 -> 0x0080 // fast forward
        89 -> 0x0100 // rewind
        164 -> 0x0200 // mute
        82 -> 0x0400 // menu
        187 -> 0x0800 // app switch
        else -> null
    }

    /** True when the virtual devices can produce this Android key code. */
    fun canEmit(keyCode: Int): Boolean =
        keyboardUsage(keyCode) != null || consumerBit(keyCode) != null

    /** Builds a descriptor from plain usages so the tables stay readable. */
    private fun hid(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
