package com.vrremote.controller.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCodecTest {

    @Test
    fun `profiles survive a round trip`() {
        val profiles = listOf(
            AppProfile(
                packageName = "com.example.reader",
                label = "阅读",
                bindings = mapOf(
                    ProfileTrigger.TOUCH_LEFT to ProfileAction.TAP_LEFT,
                    ProfileTrigger.TOUCH_PRESS to ProfileAction.DOUBLE_TAP,
                ),
            ),
            AppProfile(
                packageName = "com.example.camera",
                label = "相机",
                enabled = false,
                bindings = mapOf(ProfileTrigger.VOLUME_UP to ProfileAction.KEY_VOLUME_UP),
            ),
        )

        val decoded = ProfileCodec.decode(ProfileCodec.encode(profiles))

        assertEquals(2, decoded.size)
        val reader = decoded.first { it.packageName == "com.example.reader" }
        assertEquals("阅读", reader.label)
        assertTrue(reader.enabled)
        assertEquals(ProfileAction.TAP_LEFT, reader.actionFor(ProfileTrigger.TOUCH_LEFT))
        assertEquals(ProfileAction.DOUBLE_TAP, reader.actionFor(ProfileTrigger.TOUCH_PRESS))
        val camera = decoded.first { it.packageName == "com.example.camera" }
        assertEquals(false, camera.enabled)
        assertEquals(ProfileAction.KEY_VOLUME_UP, camera.actionFor(ProfileTrigger.VOLUME_UP))
    }

    @Test
    fun `an empty store decodes to nothing`() {
        assertTrue(ProfileCodec.decode(null).isEmpty())
        assertTrue(ProfileCodec.decode("").isEmpty())
    }

    @Test
    fun `a foreign or truncated payload decodes to nothing`() {
        assertTrue(ProfileCodec.decode("v9\ncom.example\tLabel\ttrue\t").isEmpty())
        assertTrue(ProfileCodec.decode("garbage without a version").isEmpty())
    }

    @Test
    fun `unknown triggers and actions are skipped, not fatal`() {
        val text = "v1\ncom.example\tLabel\ttrue\tTOUCH_PRESS=DOUBLE_TAP;NOPE=TAP_LEFT;VOLUME_UP=WRONG"
        val decoded = ProfileCodec.decode(text)
        assertEquals(1, decoded.size)
        assertEquals(ProfileAction.DOUBLE_TAP, decoded[0].actionFor(ProfileTrigger.TOUCH_PRESS))
        assertEquals(ProfileAction.NONE, decoded[0].actionFor(ProfileTrigger.VOLUME_UP))
    }

    @Test
    fun `a broken enabled flag falls back to enabled`() {
        val decoded = ProfileCodec.decode("v1\ncom.example\tLabel\twhatever\t")
        assertEquals(true, decoded.single().enabled)
    }

    @Test
    fun `a missing label falls back to the package name`() {
        val decoded = ProfileCodec.decode("v1\ncom.example\t\ttrue\t")
        assertEquals("com.example", decoded.single().label)
    }
}
