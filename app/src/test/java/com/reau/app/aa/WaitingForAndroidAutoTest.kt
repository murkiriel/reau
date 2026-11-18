package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When Android Auto's session drops in the middle of a projection the dash keeps the last picture. A drop of up to 5 s (the redial brings the
 * picture back in about 1.6 s, 2.2 s at most) leaves it so; a longer one makes the dash say "waiting for Android Auto" until the first new picture.
 */
class WaitingForAndroidAutoTest {
    private val up = true
    private val down = false

    /** The first ten seconds of a normal projection: Android Auto up, the dash showing, a picture count that moves. */
    private fun running(w: WaitingForAndroidAuto, units: Long = 100) {
        assertNull(w.check(1_000L, up, true, units))
        assertNull(w.check(2_000L, up, true, units))
    }

    @Test
    fun `4,9 s after the drop there is no notice, 5,0 s after it there is`() {
        val w = WaitingForAndroidAuto()
        running(w)
        assertNull("the drop is seen", w.check(10_000L, down, true, 100))
        assertNull("4,9 s", w.check(14_900L, down, true, 100))
        assertFalse(w.isWaiting)
        assertEquals("5,0 s", true, w.check(15_000L, down, true, 100))
        assertTrue(w.isWaiting)
    }

    @Test
    fun `the notice is told once`() {
        val w = WaitingForAndroidAuto()
        running(w)
        w.check(10_000L, down, true, 100)
        assertEquals(true, w.check(15_000L, down, true, 100))
        assertNull(w.check(15_250L, down, true, 100))
        assertNull(w.check(60_000L, down, true, 100))
        assertTrue(w.isWaiting)
    }

    @Test
    fun `a drop that the redial mends with a picture in a couple of seconds never shows it`() {
        val w = WaitingForAndroidAuto()
        running(w)
        assertNull(w.check(10_000L, down, true, 100))
        assertNull(w.check(11_000L, down, true, 100))
        assertNull("the session is back", w.check(11_600L, up, true, 100))
        assertNull("and its first picture", w.check(11_800L, up, true, 101))
        assertNull(w.check(40_000L, up, true, 150))
        assertFalse(w.isWaiting)
    }

    @Test
    fun `the first new picture ends the notice, with the link already up`() {
        val w = WaitingForAndroidAuto()
        running(w)
        w.check(10_000L, down, true, 100)
        w.check(15_000L, down, true, 100)
        assertNull("the link is up and no picture yet: the notice stays", w.check(20_000L, up, true, 100))
        assertTrue(w.isWaiting)
        assertEquals(false, w.check(20_250L, up, true, 101))
        assertFalse(w.isWaiting)
    }

    @Test
    fun `the count runs from the drop, not from the link coming back`() {
        val w = WaitingForAndroidAuto()
        running(w)
        w.check(10_000L, down, true, 100)
        assertNull("up again after 3 s, with no picture yet", w.check(13_000L, up, true, 100))
        assertNull(w.check(14_900L, up, true, 100))
        assertEquals("5 s after the drop", true, w.check(15_000L, up, true, 100))
    }

    @Test
    fun `a picture that arrives while the link is still down ends the notice and the count begins again from there`() {
        val w = WaitingForAndroidAuto()
        running(w)
        w.check(10_000L, down, true, 100)
        assertEquals(true, w.check(15_000L, down, true, 100))
        assertEquals(false, w.check(16_000L, down, true, 101))
        assertNull("4,9 s after that picture", w.check(20_900L, down, true, 101))
        assertEquals("5,0 s after it", true, w.check(21_000L, down, true, 101))
    }

    @Test
    fun `a new drop begins its own count`() {
        val w = WaitingForAndroidAuto()
        running(w)
        w.check(10_000L, down, true, 100)
        assertEquals(true, w.check(15_000L, down, true, 100))
        assertEquals(false, w.check(16_000L, up, true, 101))
        assertNull("a long quiet stretch with the link up is no drop", w.check(100_000L, up, true, 101))
        assertNull("the second drop", w.check(200_000L, down, true, 160))
        assertNull("4,9 s into it, the first drop's time does not count", w.check(204_900L, down, true, 160))
        assertEquals(true, w.check(205_000L, down, true, 160))
    }

    @Test
    fun `a dash that is not showing the video has no notice, however long Android Auto is gone`() {
        val w = WaitingForAndroidAuto()
        for (t in 0..120) assertNull(w.check(t * 1_000L, down, false, 100))
        assertFalse(w.isWaiting)
    }

    @Test
    fun `the dash going away takes the notice off, and its return begins the count again`() {
        val w = WaitingForAndroidAuto()
        running(w)
        w.check(10_000L, down, true, 100)
        assertEquals(true, w.check(15_000L, down, true, 100))
        assertEquals("the dash drops with Android Auto", false, w.check(16_000L, down, false, 100))
        assertNull(w.check(30_000L, down, false, 100))
        assertNull("the dash is back, Android Auto still gone: from here", w.check(40_000L, down, true, 100))
        assertNull(w.check(44_900L, down, true, 100))
        assertEquals(true, w.check(45_000L, down, true, 100))
    }

    @Test
    fun `a projection that goes on with Android Auto up never shows it`() {
        val w = WaitingForAndroidAuto()
        for (t in 0..600) assertNull(w.check(t * 1_000L, up, true, 100))
        assertFalse(w.isWaiting)
    }
}
