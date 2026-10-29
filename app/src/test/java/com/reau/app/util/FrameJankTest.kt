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
}
