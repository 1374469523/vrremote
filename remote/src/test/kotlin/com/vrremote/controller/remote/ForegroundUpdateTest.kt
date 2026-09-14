package com.vrremote.controller.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundUpdateTest {

    @Test
    fun `an unreadable lookup clears the tracked app`() {
        val update = foregroundUpdate(previous = "com.example.video", read = null)
        assertNull("unknown must mean do nothing, not keep the last app", update.packageName)
        assertTrue(update.changed)
    }

    @Test
    fun `the same app twice is not a change`() {
        val update = foregroundUpdate(previous = "com.example.video", read = "com.example.video")
        assertEquals("com.example.video", update.packageName)
        assertFalse(update.changed)
    }

    @Test
    fun `switching apps is a change`() {
        val update = foregroundUpdate(previous = "com.example.video", read = "com.example.camera")
        assertEquals("com.example.camera", update.packageName)
        assertTrue(update.changed)
    }

    @Test
    fun `a first successful lookup is a change`() {
        val update = foregroundUpdate(previous = null, read = "com.example.camera")
        assertEquals("com.example.camera", update.packageName)
        assertTrue(update.changed)
    }

    @Test
    fun `staying unknown is not a change`() {
        val update = foregroundUpdate(previous = null, read = null)
        assertNull(update.packageName)
        assertFalse(update.changed)
    }
}
