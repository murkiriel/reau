package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `aa-screen displays:` came 224 times in a day, every 1 to 5 s for 15 minutes with the dash away (20/10/2026). */
class LineThrottleTest {
    @Test
    fun `the first line goes out and the ones inside the next second are held`() {
        val throttle = LineThrottle(minGapMs = 1_000)
        assertEquals("a", throttle.offer(10_000, "a"))
        assertNull(throttle.offer(10_300, "b"))
        assertNull(throttle.offer(10_999, "c"))
    }

    @Test
    fun `the line after the gap says how many changes it stands for`() {
        val throttle = LineThrottle(minGapMs = 1_000)
        throttle.offer(10_000, "a")
        throttle.offer(10_300, "b")
        throttle.offer(10_600, "c")
        assertEquals("d (+2 more changes in the last 1 s, not written)", throttle.offer(11_000, "d"))
        assertEquals("then the count starts over", "e", throttle.offer(12_000, "e"))
    }

    @Test
    fun `a change every 100 ms for a minute writes at most 60 lines`() {
        val throttle = LineThrottle()
        var written = 0
        for (t in 0L until 60_000L step 100) if (throttle.offer(t, "x") != null) written++
        assertEquals(60, written)
    }
}
