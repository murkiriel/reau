package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the link to Android Auto decides on its own, with no socket: which loop is the current one, when a state is told, when the
 * call-in says "not calling", which try writes its failure. (The dialling, the sessions and the stop need a device: AaReceiverTest.)
 */
class AaReceiverRulesTest {
    @Test
    fun `only the loop of the latest start, and only while running, is the current one`() {
        assertTrue(AaReceiver.sameRun(running = true, generation = 3, mine = 3))
        assertFalse("stopped", AaReceiver.sameRun(running = false, generation = 3, mine = 3))
        assertFalse("a newer start came", AaReceiver.sameRun(running = true, generation = 4, mine = 3))
        assertFalse("an older number", AaReceiver.sameRun(running = true, generation = 2, mine = 3))
        assertFalse(AaReceiver.sameRun(running = false, generation = 4, mine = 3))
    }

    @Test
    fun `a state is told only by the current loop and only when it changes`() {
        val from = AaLinkState.CONNECTING
        assertTrue(AaReceiver.deliverable(true, 1, 1, from, AaLinkState.ACTIVE))
        assertFalse("the same state again", AaReceiver.deliverable(true, 1, 1, from, from))
        assertFalse("a loop left over from before a stop", AaReceiver.deliverable(false, 1, 1, from, AaLinkState.ACTIVE))
        assertFalse("a loop of an older start", AaReceiver.deliverable(true, 2, 1, from, AaLinkState.ACTIVE))
    }

    @Test
    fun `the call-in says not calling at the fifth failed try, not before`() {
        assertEquals(5, AaReceiver.CALL_IN_TRIES_BEFORE_SAYING)
        for (before in 0..3) assertFalse("after $before failed tries", AaReceiver.notCalling(before))
        assertTrue("the fifth", AaReceiver.notCalling(4))
        assertTrue("and every one after it", AaReceiver.notCalling(9))
    }

    @Test
    fun `only the first refused dial of a round writes its failure to the log`() {
        assertTrue(AaReceiver.firstTry(0))
        assertFalse(AaReceiver.firstTry(1))
        assertFalse(AaReceiver.firstTry(7))
    }
}
