package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Something shown for a while and then taken away ([TimedShow]), with the clock and the wait the test's own: the last one wins and the time
 * starts again, the end is told, the switch off keeps nothing.
 */
class TimedShowTest {
    private var now = 1_000L
    private var told = 0

    private class Wait(val atMs: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private val waits = mutableListOf<Wait>()
    private val show = TimedShow<String>(
        1_500L, nowMs = { now },
        schedule = { delayMs, action ->
            val wait = Wait(now + delayMs, action)
            waits += wait
            val cancel: () -> Unit = { wait.cancelled = true }
            cancel
        },
        changed = { told++ },
    )

    /** Moves the clock to [to] and runs the waits that are due and not cancelled, as the real wait would. */
    private fun advance(to: Long) {
        now = to
        for (wait in waits.filter { !it.cancelled && it.atMs <= to }) {
            wait.cancelled = true
            wait.action()
        }
    }

    @Test
    fun `what is shown is current for the whole time and gone at the end of it`() {
        assertNull(show.current())
        assertTrue(show.show("a"))
        assertEquals("a", show.current())
        assertEquals(1_500L, show.remainingMs())
        now += 1_499
        assertEquals("a", show.current())
        assertEquals(1L, show.remainingMs())
        now += 1
        assertNull(show.current())
        assertEquals(0L, show.remainingMs())
    }

    @Test
    fun `the change is told when it is shown and when the time runs out`() {
        show.show("a")
        assertEquals(1, told)
        advance(2_499)
        assertEquals("not before the time", 1, told)
        assertEquals("a", show.current())
        advance(2_500)
        assertEquals(2, told)
        assertNull(show.current())
    }

    @Test
    fun `the last one wins, its time starts again and the wait of the first is cancelled`() {
        show.show("a")
        advance(2_000)
        show.show("b")
        assertEquals(2, told)
        assertTrue("the first wait was cancelled", waits.first().cancelled)
        advance(2_500)
        assertEquals("the first one's end does not take b away", "b", show.current())
        assertEquals(2, told)
        advance(3_500)
        assertNull(show.current())
        assertEquals(3, told)
    }

    @Test
    fun `a wait that wakes early waits again for what is left and tells nothing`() {
        show.show("a")
        now = 2_000
        waits.first().action()
        assertEquals("a", show.current())
        assertEquals(1, told)
        assertEquals("a new wait for the 500 ms left", 2_500L, waits.last().atMs)
        advance(2_500)
        assertNull(show.current())
        assertEquals(2, told)
    }

    @Test
    fun `switched off nothing is kept and nothing is told`() {
        show.enabled = false
        assertFalse(show.show("a"))
        assertNull(show.current())
        assertEquals(0, told)
        assertTrue(waits.isEmpty())
        show.enabled = true
        assertTrue(show.show("a"))
        assertEquals("a", show.current())
    }

    @Test
    fun `switched off while showing it goes away at once, with the change told and the wait cancelled`() {
        show.show("a")
        show.enabled = false
        assertNull(show.current())
        assertEquals(2, told)
        assertTrue(waits.single().cancelled)
        show.enabled = false
        assertEquals("nothing showing, nothing told", 2, told)
    }

    @Test
    fun `clear takes it away and tells, drop takes it away and tells nobody`() {
        show.show("a")
        show.clear()
        assertNull(show.current())
        assertEquals(2, told)
        show.show("b")
        show.drop()
        assertNull(show.current())
        assertEquals(3, told)
        assertTrue(waits.last().cancelled)
    }
}
