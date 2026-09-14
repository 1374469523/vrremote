package com.vrremote.controller.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The descriptor tables are the part of the injection layer that cannot be
 * checked by eye, so the mapping and the collection nesting are locked here.
 */
class HidDescriptorsTest {

    @Test
    fun `keyboard usages match the AOSP key layout`() {
        // Linux 103/108/105/106 and 28 map to the Android codes below.
        assertEquals(0x52, HidDescriptors.keyboardUsage(19))
        assertEquals(0x51, HidDescriptors.keyboardUsage(20))
        assertEquals(0x50, HidDescriptors.keyboardUsage(21))
        assertEquals(0x4F, HidDescriptors.keyboardUsage(22))
        assertEquals(0x28, HidDescriptors.keyboardUsage(66))
        assertNull("home is not a keyboard usage", HidDescriptors.keyboardUsage(3))
        assertNull(HidDescriptors.keyboardUsage(4))
        assertNull(HidDescriptors.keyboardUsage(24))
    }

    @Test
    fun `consumer bits carry volume home and back`() {
        assertEquals(0x01, HidDescriptors.consumerBit(24))
        assertEquals(0x02, HidDescriptors.consumerBit(25))
        assertEquals(0x04, HidDescriptors.consumerBit(3))
        assertEquals(0x08, HidDescriptors.consumerBit(4))
        assertNull(HidDescriptors.consumerBit(66))
    }

    @Test
    fun `every descriptor closes what it opens`() {
        for ((name, descriptor) in listOf(
            "keyboard" to HidDescriptors.KEYBOARD,
            "consumer" to HidDescriptors.CONSUMER,
            "mouse" to HidDescriptors.MOUSE,
        )) {
            var depth = 0
            var index = 0
            while (index < descriptor.size) {
                when (descriptor[index].toInt() and 0xFF) {
                    0xA1 -> depth++
                    0xC0 -> depth--
                }
                index++
            }
            assertEquals("$name descriptor must end closed", 0, depth)
            assertEquals(
                "$name descriptor must end with End Collection",
                0xC0,
                descriptor.last().toInt() and 0xFF,
            )
            assertTrue("$name descriptor must not be empty", descriptor.isNotEmpty())
        }
    }

    @Test
    fun `the mouse descriptor asks for relative x y and wheel`() {
        val descriptor = HidDescriptors.MOUSE.map { it.toInt() and 0xFF }
        // Usage page Generic Desktop, usage Mouse, then the relative fields.
        assertEquals(listOf(0x05, 0x01, 0x09, 0x02), descriptor.take(4))
        val index = descriptor.windowed(2).indexOf(listOf(0x81, 0x06))
        assertTrue("mouse must declare a relative input", index >= 0)
    }

    @Test
    fun `report sizes match what the reports actually contain`() {
        assertEquals(8, HidDescriptors.KEYBOARD_REPORT_SIZE)
        assertEquals(2, HidDescriptors.CONSUMER_REPORT_SIZE)
        assertEquals(4, HidDescriptors.MOUSE_REPORT_SIZE)
    }

    @Test
    fun `every action key code can be emitted, or is routed through the shell`() {
        val keyCodes = com.vrremote.controller.remote.ProfileAction.entries
            .mapNotNull { it.keyCode }
        assertTrue("the action list should expose key actions", keyCodes.size >= 19)
        val missing = keyCodes.filterNot { HidDescriptors.canEmit(it) }
        assertEquals(
            "these keys would silently do nothing on the HID path; route them through input keyevent",
            emptyList<Int>(),
            missing,
        )
    }

    @Test
    fun `each consumer key owns a distinct bit`() {
        val bits = com.vrremote.controller.remote.ProfileAction.entries
            .mapNotNull { action -> HidDescriptors.consumerBit(action.keyCode ?: return@mapNotNull null) }
        assertEquals(bits.size, bits.distinct().size)
        assertTrue("consumer bits must fit the two byte report", bits.all { it <= 0xFFFF })
    }
}
