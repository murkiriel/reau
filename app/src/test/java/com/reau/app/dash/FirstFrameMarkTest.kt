package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The line of the instant the dash first confirms a frame of the stream (plan 35, 25/11/2026): the reader had only the 5 s windows and could say no more than
 * "between 5.4 and 10.5 s after streaming began".
 */
class FirstFrameMarkTest {
    @Test
    fun `the first notice while streaming gives the milliseconds since streaming began`() {
        val mark = FirstFrameMark()
        assertEquals(7_420L, mark.ack(DashState.STREAMING, streamingStartMs = 10_000, nowMs = 17_420))
    }

    @Test
    fun `it is told once per session and again after a reset`() {
        val mark = FirstFrameMark()
        assertEquals(1_000L, mark.ack(DashState.STREAMING, 1_000, 2_000))
        assertNull(mark.ack(DashState.STREAMING, 1_000, 3_000))
        mark.reset()
        assertEquals(500L, mark.ack(DashState.STREAMING, 1_000, 1_500))
    }

    @Test
    fun `a notice before the stream is not a frame of it and does not use up the line`() {
        val mark = FirstFrameMark()
        for (state in listOf(DashState.IDLE, DashState.CONNECTING, DashState.AUTHENTICATING, DashState.READY, DashState.ERROR)) {
            assertNull(state.name, mark.ack(state, streamingStartMs = 1_000, nowMs = 2_000))
        }
        assertNull("streaming that has not begun", mark.ack(DashState.STREAMING, streamingStartMs = 0, nowMs = 2_000))
        assertEquals(1_000L, mark.ack(DashState.STREAMING, 1_000, 2_000))
    }

    @Test
    fun `a clock reading before the start is zero, not negative`() {
        assertEquals(0L, FirstFrameMark().ack(DashState.STREAMING, 5_000, 4_000))
    }

    @Test
    fun `the line has the number and the notice, in the words the reader looks for`() {
        assertEquals("first frame confirmed by the dash 7420 ms after streaming began (09 06 55)", FirstFrameMark.line(7_420, "09 06 55"))
    }

    @Test
    fun `the session asks after both decode notices, once the reply is sent, and starts over with a session`() {
        val session = File("src/main/java/com/reau/app/dash/DashSession.kt").readText()
        assertTrue(Regex("sock\\.send\\(DashCommands\\.frameDecodedIdr\\(\\)\\)\\s+noteFirstFrame\\(\"09 06 55\"\\)").containsMatchIn(session))
        assertTrue(Regex("sock\\.send\\(DashCommands\\.frameDecodedP\\(\\)\\)\\s+noteFirstFrame\\(\"09 04 55\"\\)").containsMatchIn(session))
        assertTrue(Regex("loggedFirstAck = false\\s+firstFrame\\.reset\\(\\)").containsMatchIn(session))
        assertTrue(session, "firstFrame.ack(_state.value, streamingStartMs, now)" in session)
    }
}
