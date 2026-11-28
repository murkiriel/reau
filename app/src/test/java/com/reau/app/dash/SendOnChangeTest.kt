package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [SendOnChange], the bookkeeping behind the switch "send only what changed": a command goes the first time of a session, when its content changes, and again
 * after anything that makes the dash forget; never for the same content twice. Off, everything goes, always, and nothing is kept.
 */
class SendOnChangeTest {
    /** The test's clock, in ms; it starts far from zero, so a time kept as 0 would look 30 s old at once. */
    private var nowMs = 1_000_000L

    private fun on(refreshMs: Long = SendOnChange.REFRESH_MS) = SendOnChange(enabled = true, refreshMs = refreshMs, nowMs = { nowMs })

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `the first ask of a key is a yes, the same content again is a no, a change is a yes`() {
        val c = on()
        assertTrue(c.offer("status", bytes(1, 2)))
        c.sent("status", bytes(1, 2))
        assertFalse("same bytes", c.offer("status", bytes(1, 2)))
        assertFalse("a copy of the same bytes", c.offer("status", bytes(1, 2).copyOf()))
        assertTrue("one byte changed", c.offer("status", bytes(1, 3)))
        c.sent("status", bytes(1, 3))
        assertFalse(c.offer("status", bytes(1, 3)))
        assertTrue("and back to what it was before is a change too", c.offer("status", bytes(1, 2)))
    }

    @Test
    fun `each key is its own, and a packet longer by a byte is a change`() {
        val c = on()
        c.sent("a", bytes(5))
        assertTrue(c.offer("b", bytes(5)))
        assertFalse(c.offer("a", bytes(5)))
        assertTrue(c.offer("a", bytes(5, 0)))
    }

    @Test
    fun `what did not reach the wire is not remembered, so the next ask tries again`() {
        val c = on()
        var tries = 0
        assertTrue(c.attempt("card", bytes(9)) { tries++; false })
        assertTrue("the send failed: still the first time", c.attempt("card", bytes(9)) { tries++; false })
        assertTrue(c.attempt("card", bytes(9)) { tries++; true })
        assertFalse("it went: nothing more to say", c.attempt("card", bytes(9)) { tries++; true })
        assertEquals(3, tries)
    }

    @Test
    fun `attempt says it was asked to send whatever the answer of the send, and no when nothing was due`() {
        val c = on()
        assertTrue(c.attempt("k", bytes(1)) { false })
        assertTrue(c.attempt("k", bytes(1)) { true })
        assertFalse(c.attempt("k", bytes(1)) { error("not asked") })
    }

    @Test
    fun `the card at the end of a button's batch went only when it was in the batch and the whole batch reached the wire`() {
        val c = on()
        assertTrue(c.cardWent(batchSize = 3, answersSize = 2, allWired = true))
        assertFalse("the batch failed", c.cardWent(batchSize = 3, answersSize = 2, allWired = false))
        assertFalse("the card was left out of the batch", c.cardWent(batchSize = 2, answersSize = 2, allWired = true))
    }

    @Test
    fun `a new session starts from nothing, every key is a first again`() {
        val c = on()
        c.sent("0556", bytes(0x55))
        c.sent("status", bytes(1))
        c.begin(on = true)
        assertTrue(c.offer("0556", bytes(0x55)))
        assertTrue(c.offer("status", bytes(1)))
        assertFalse(c.holds("0556"))
    }

    @Test
    fun `reset says everything again without touching the switch`() {
        val c = on()
        c.sent("nav", bytes(1))
        c.reset()
        assertTrue(c.enabled)
        assertTrue(c.offer("nav", bytes(1)))
    }

    @Test
    fun `forgetting one key says that one again and leaves the others`() {
        val c = on()
        c.sent("card", bytes(1))
        c.sent("nav", bytes(2))
        c.forget("card")
        assertTrue(c.offer("card", bytes(1)))
        assertFalse(c.offer("nav", bytes(2)))
    }

    @Test
    fun `a session begins with the rider's switch as it is then, on or off`() {
        val c = SendOnChange()
        assertFalse("off by default: what every test and the old behaviour get", c.enabled)
        c.begin(on = true)
        assertTrue(c.enabled)
        c.begin(on = false)
        assertFalse(c.enabled)
    }

    @Test
    fun `off, everything goes every time and nothing is kept`() {
        val c = SendOnChange(enabled = false)
        repeat(3) {
            assertTrue(c.offer("status", bytes(1)))
            c.sent("status", bytes(1))
        }
        assertFalse("nothing held", c.holds("status"))
        var sent = 0
        repeat(5) { assertTrue(c.attempt("0556", bytes(0x55)) { sent++; true }) }
        assertEquals(5, sent)
    }

    @Test
    fun `the time says its minute, not its seconds, so it is due when the minute changes`() {
        fun at(h: Int, m: Int, s: Int) = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 28, h, m, s) }
        val c = on()
        val first = DashCommands.clockShown(at(14, 51, 52))
        assertTrue(c.offer("0606", first))
        c.sent("0606", first)
        assertFalse("later in the same minute", c.offer("0606", DashCommands.clockShown(at(14, 51, 53))))
        assertFalse(c.offer("0606", DashCommands.clockShown(at(14, 51, 59))))
        assertTrue("the next minute", c.offer("0606", DashCommands.clockShown(at(14, 52, 0))))
        assertTrue("the next hour, the same minute", c.offer("0606", DashCommands.clockShown(at(15, 51, 0))))
        assertEquals(listOf(14, 51), DashCommands.clockShown(at(14, 51, 52)).map { it.toInt() })
    }

    @Test
    fun `the time packet carries the clock it is given, with its seconds`() {
        val packet = DashCommands.timeSync(Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 28, 9, 5, 7) })
        assertEquals(listOf(9, 5, 7), packet.takeLast(3).map { it.toInt() })
    }

    @Test
    fun `the call card's content is its state and its name, never the encrypted packet whose bytes change every time`() {
        val key = ByteArray(32) { it.toByte() }
        val one = DashCommands.callNotify("Ana", key)
        val two = DashCommands.callNotify("Ana", key)
        assertFalse("a fresh IV each time: comparing the packets would never say 'same'", one.contentEquals(two))
        val c = on()
        val content = bytes(DashCommands.CallState.RINGING.code) + "Ana".toByteArray()
        c.sent("call", content)
        assertFalse(c.offer("call", bytes(DashCommands.CallState.RINGING.code) + "Ana".toByteArray()))
        assertTrue(c.offer("call", bytes(DashCommands.CallState.IN_CALL.code) + "Ana".toByteArray()))
    }

    // ── the slow reaffirmation ──────────────────────────────────────────────

    @Test
    fun `a value that did not change goes again 30 s after it went, not a millisecond before, and then waits 30 s again`() {
        assertEquals("every 30 s, as the time used to go", 30_000L, SendOnChange.REFRESH_MS)
        val c = on()
        c.sent("0556", bytes(0x55))
        nowMs += 29_999
        assertFalse("still fresh", c.offer("0556", bytes(0x55)))
        nowMs += 1
        assertTrue("30 s: said again", c.offer("0556", bytes(0x55)))
        var sent = 0
        assertTrue(c.attempt("0556", bytes(0x55)) { sent++; true })
        nowMs += 1_000
        assertFalse("the count starts again from the send", c.offer("0556", bytes(0x55)))
        nowMs += 29_000
        assertTrue(c.offer("0556", bytes(0x55)))
        assertEquals(1, sent)
    }

    @Test
    fun `each key counts its own 30 s, from its own last send`() {
        val c = on()
        c.sent("status", bytes(1))
        nowMs += 10_000
        c.sent("card", bytes(2))
        nowMs += 20_000
        assertTrue("the status went 30 s ago", c.offer("status", bytes(1)))
        assertFalse("the card only 20 s ago", c.offer("card", bytes(2)))
    }

    @Test
    fun `a reaffirmation that did not reach the wire is tried again at the next ask`() {
        val c = on()
        c.sent("nav", bytes(3))
        nowMs += 30_000
        assertTrue(c.attempt("nav", bytes(3)) { false })
        assertTrue("the failed one did not restart the count", c.offer("nav", bytes(3)))
    }

    @Test
    fun `with no reaffirmation (0) only a change sends again, however long it waits`() {
        val c = on(refreshMs = 0L)
        c.sent("0556", bytes(0x55))
        nowMs += 3_600_000
        assertFalse(c.offer("0556", bytes(0x55)))
        assertTrue(c.offer("0556", bytes(0xAA)))
    }

    @Test
    fun `the session's waits carry the reaffirmation, 30 s unless a test shortens it`() {
        assertEquals(SendOnChange.REFRESH_MS, DashSession.Timing().reaffirmMs)
    }
}
