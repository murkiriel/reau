package com.reau.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logcat of 20/10/2026: `Skipped 54 frames` at the start and 36 on coming back, and nothing in the ride log to say where. */
class FrameJankTest {
    @Test
    fun `only a frame over 100 ms is slow`() {
        assertFalse(FrameJank.slow(16))
        assertFalse(FrameJank.slow(100))
        assertTrue(FrameJank.slow(101))
    }

    @Test
    fun `the phase is the start of the process, then the return to the screen, then settings or the rest`() {
        assertEquals("cold start", FrameJank.phaseOf(sinceStartMs = 1_200, sinceResumeMs = 1_000, inSettings = false))
        assertEquals("resume", FrameJank.phaseOf(sinceStartMs = 60_000, sinceResumeMs = 2_999, inSettings = true))
        assertEquals("settings", FrameJank.phaseOf(sinceStartMs = 60_000, sinceResumeMs = 3_000, inSettings = true))
        assertEquals("running", FrameJank.phaseOf(sinceStartMs = 60_000, sinceResumeMs = 90_000, inSettings = false))
        assertEquals("never resumed", "running", FrameJank.phaseOf(sinceStartMs = 60_000, sinceResumeMs = -1, inSettings = false))
    }

    @Test
    fun `the line says the time and the phase and nothing else`() {
        assertEquals("slow frame: 213 ms during resume", FrameJank.line(213, "resume"))
    }

    @Test
    fun `at most five lines in a minute, and the minute slides`() {
        var now = 0L
        val limiter = FrameJank.Limiter { now }
        repeat(FrameJank.MAX_PER_MINUTE) { assertTrue("line ${it + 1}", limiter.allow()); now += 1_000 }
        assertFalse("the sixth", limiter.allow())
        now = 59_999
        assertFalse("still inside the first minute", limiter.allow())
        now = 60_000 // the first line is a minute old
        assertTrue(limiter.allow())
        assertFalse("and the window is full again", limiter.allow() && limiter.allow() && limiter.allow() && limiter.allow())
    }

    @Test
    fun `a frame of half a second or more is always written, past the five of the minute`() {
        var now = 0L
        val limiter = FrameJank.Limiter { now }
        repeat(FrameJank.MAX_PER_MINUTE) { assertTrue(limiter.allow(200)); now += 1_000 }
        assertFalse("the count is used up for ordinary slow frames", limiter.allow(499))
        assertTrue("823 ms on 26/11/2026 was hidden behind the cold start", limiter.allow(823))
        now += FrameJank.LONG_EVERY_MS
        assertTrue("exactly the limit", limiter.allow(FrameJank.ALWAYS_TOLD_MS))
        assertFalse("and it does not open the count for the others", limiter.allow(300))
    }

    @Test
    fun `the frames of half a second or more have a ceiling of one a second, and the next second is open again`() {
        var now = 10_000L
        val limiter = FrameJank.Limiter { now }
        assertTrue("the first stuck frame", limiter.allow(2_000))
        now += 500
        assertFalse("another half a second later, in the same second", limiter.allow(900))
        now += 499
        assertFalse("999 ms after the first", limiter.allow(5_000))
        now += 1
        assertTrue("a second after the first", limiter.allow(700))
        // a stall of one minute, a frame every half second: 120 frames, 60 lines
        now = 100_000L
        val written = (0 until 120).count { now += 500; limiter.allow(600) }
        assertEquals("one a second, never two", 60, written)
        assertTrue("and the ordinary frames keep their five a minute", (1..FrameJank.MAX_PER_MINUTE).all { limiter.allow(200) })
        assertFalse(limiter.allow(200))
    }

    @Test
    fun `the ceiling lets the first long frame of the second through and drops a longer one that comes after it, and the text says so`() {
        var now = 10_000L
        val limiter = FrameJank.Limiter { now }
        assertTrue("the first, short of the long ones", limiter.allow(FrameJank.ALWAYS_TOLD_MS))
        now += 300
        assertFalse("the worst of the second comes later and is dropped", limiter.allow(9_000))
        val source = java.io.File("src/main/java/com/reau/app/util/FrameJank.kt").readText()
        assertTrue("the KDoc says the first one", "lets through the FIRST long frame of each second, not the worst" in source)
        assertFalse("the KDoc no longer promises the worst one", "keeps the worst frame of each second" in source)
    }
}
