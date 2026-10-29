package com.reau.app.aa

import com.reau.app.aa.PortWait.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wait for the port after "start" was touched, and the answer that comes after it ran out. */
class PortWaitTest {
    private val wait = PortWait()

    @Test
    fun `an answer inside the wait is how long it took, and not late`() {
        wait.walkBegun()
        assertFalse(wait.tapped(1_000))
        assertEquals(Answer(2_300, late = false), wait.answered(3_300))
        assertFalse("the wait is over", wait.expired(1_000))
        assertNull("nothing waits for a second answer", wait.answered(4_000))
    }

    @Test
    fun `an answer after the wait ran out is late, counted from the touched start, and only once`() {
        wait.walkBegun()
        wait.tapped(1_000)
        assertTrue(wait.expired(1_000))
        assertEquals(Answer(21_600, late = true), wait.answered(22_600))
        assertNull(wait.answered(23_000))
    }

    @Test
    fun `a late answer in the middle of a new walk is not taken by the old wait`() {
        wait.walkBegun()
        wait.tapped(1_000)
        assertTrue(wait.expired(1_000))
        wait.walkBegun()                       // the second walk began
        assertNull("the old wait does not take it", wait.answered(9_000))
        assertTrue("the new walk keeps it for its end", wait.up)
        assertTrue("and then the new touched start has it already", wait.tapped(10_000))
    }

    @Test
    fun `an answer during the walk is kept for the walk's end, and a new touched start forgets an old late wait`() {
        wait.walkBegun()
        wait.tapped(1_000)
        wait.expired(1_000)
        wait.walkBegun()
        wait.tapped(5_000)                     // a new wait; the old one's lateness is gone
        assertEquals(Answer(500, late = false), wait.answered(5_500))
    }

    @Test
    fun `the wait of another touched start is not the one that ran out`() {
        wait.walkBegun()
        wait.tapped(1_000)
        assertFalse(wait.expired(999))
        assertEquals(Answer(100, late = false), wait.answered(1_100))
    }

    @Test
    fun `a late answer turns not up or working into started, and nothing else`() {
        assertEquals(ServerResult.STARTED, ServerResult.afterLateAnswer(ServerResult.NOT_UP))
        assertEquals(ServerResult.STARTED, ServerResult.afterLateAnswer(ServerResult.WORKING))
        for (other in listOf(ServerResult.STOPPED, ServerResult.LEFT_ON, ServerResult.STOPPING, ServerResult.SERVICE_OFF, ServerResult.NONE)) {
            assertEquals(other, ServerResult.afterLateAnswer(other))
        }
    }
}
