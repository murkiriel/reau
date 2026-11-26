package com.reau.app.service

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the service and the app's activity share for the lock screen choice: armed or not, and whether the activity is resumed. */
class ScreenKeepFrontTest {
    @After
    fun reset() {
        ScreenKeepFront.arm(false)
        ScreenKeepFront.resumed = false
    }

    @Test
    fun `it starts not armed and not resumed`() {
        assertFalse(ScreenKeepFront.armed.value)
        assertFalse(ScreenKeepFront.resumed)
    }

    @Test
    fun `arming and letting go are seen by whoever follows the state`() {
        ScreenKeepFront.arm(true)
        assertTrue(ScreenKeepFront.armed.value)
        ScreenKeepFront.arm(false)
        assertFalse(ScreenKeepFront.armed.value)
    }
}
