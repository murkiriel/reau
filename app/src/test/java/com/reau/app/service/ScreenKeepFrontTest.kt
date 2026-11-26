package com.reau.app.service

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the service and the app's activity share for the lock screen choice: armed or not, and whether the activity is resumed. */
class ScreenKeepFrontTest {
    @After
    fun reset() {
        ScreenKeepFront.clearPause()
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

    @Test
    fun `the rider's pause disarms, holds the arming off until the next projection and then everything is as the setting says`() {
        ScreenKeepFront.arm(true)
        ScreenKeepFront.pauseByRider()
        assertTrue(ScreenKeepFront.paused)
        assertFalse("the activity is not kept over the lock screen", ScreenKeepFront.armed.value)
        ScreenKeepFront.arm(true)
        assertFalse("a sync of the settings does not undo the pause", ScreenKeepFront.armed.value)
        // The next projection: the keeper let go at the end of the last one, then arms again from the saved setting.
        ScreenKeepFront.clearPause()
        assertFalse(ScreenKeepFront.paused)
        ScreenKeepFront.arm(true)
        assertTrue(ScreenKeepFront.armed.value)
    }
}
