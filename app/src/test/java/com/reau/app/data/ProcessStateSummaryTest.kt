package com.reau.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The summary REau gives the system for its own process (26/11/2026, idea S3): short ASCII that fits the 128 bytes Android keeps, with no personal datum, read back
 * in the `exit` line of the death. Android itself (`setProcessStateSummary`) is out of reach here: [ProcessStateSummaryIsWiredTest] holds where it is called.
 */
class ProcessStateSummaryTest {
    private fun look(
        projecting: Boolean = true, dashUp: Boolean = true, screenOn: Boolean? = true, attempt: Int = 0, battery: Int? = 65, charging: Boolean? = false,
    ) = ProcessStateSummary.Look(projecting, dashUp, screenOn, attempt, battery, charging)

    private fun text(bytes: ByteArray) = String(bytes, Charsets.US_ASCII)

    @Test
    fun `the summary is ASCII, a few dozen bytes and never over 128, even with the largest numbers`() {
        val most = ProcessStateSummary.State(look(attempt = 99, battery = 100_000, screenOn = false), screenOffAtSec = 999_999_999_999L, screenKnown = true)
        for (state in listOf(most, ProcessStateSummary.State(look(), null, true), ProcessStateSummary.State(look(battery = null, charging = null), null, false))) {
            val bytes = ProcessStateSummary.encode(state)
            assertTrue("${bytes.size} bytes", bytes.size in 1..ProcessStateSummary.MAX_BYTES)
            assertTrue(text(bytes), bytes.all { it in 0x20..0x7E })
        }
        assertTrue(ProcessStateSummary.encode(most).size < 64)
        assertEquals("what Android keeps in a process summary", 128, ProcessStateSummary.MAX_BYTES)
    }

    @Test
    fun `the summary says the projection, the dash, the screen, the attempt, the battery and the charger and nothing else`() {
        val state = ProcessStateSummary.State(look(attempt = 2, screenOn = false), screenOffAtSec = 1_795_712_345L, screenKnown = true)
        assertEquals("REau1;p=1;d=1;o=1795712345;t=2;b=65;c=0", text(ProcessStateSummary.encode(state)))
        assertEquals("REau1;p=0;d=0;o=-;t=0;b=?;c=?", text(ProcessStateSummary.encode(ProcessStateSummary.State(look(false, false, true, 0, null, null), null, true))))
        assertEquals("the screen not known is a question mark", "REau1;p=1;d=1;o=?;t=0;b=65;c=1",
            text(ProcessStateSummary.encode(ProcessStateSummary.State(look(screenOn = null, charging = true), null, false))))
    }

    @Test
    fun `the battery counts in steps of 5 percent and the attempt stops at 9`() {
        assertEquals("b=60", text(ProcessStateSummary.encode(ProcessStateSummary.State(look(battery = 64), null, true))).substringAfter(";t=0;").substringBefore(";c"))
        assertEquals("b=0", text(ProcessStateSummary.encode(ProcessStateSummary.State(look(battery = 4), null, true))).substringAfter(";t=0;").substringBefore(";c"))
        assertTrue(text(ProcessStateSummary.encode(ProcessStateSummary.State(look(attempt = 40), null, true))).contains(";t=9;"))
        assertTrue(text(ProcessStateSummary.encode(ProcessStateSummary.State(look(attempt = -3), null, true))).contains(";t=0;"))
    }

    @Test
    fun `the decoded summary tells the screen time to the death, and the rest in words`() {
        val state = ProcessStateSummary.State(look(attempt = 2, screenOn = false), screenOffAtSec = 1_000L, screenKnown = true)
        val said = ProcessStateSummary.describe(text(ProcessStateSummary.encode(state)), deathMs = 1_301_000L)
        assertEquals("projecting=yes dash=up screen=off off-for=301s attempt=2 battery=65% charging=no", said)
    }

    @Test
    fun `a screen on, a screen not known, a battery not known and a charger plugged are told`() {
        assertEquals(
            "projecting=no dash=down screen=on attempt=0 battery=? charging=yes",
            ProcessStateSummary.describe(text(ProcessStateSummary.encode(ProcessStateSummary.State(look(false, false, true, 0, null, true), null, true))), 5_000L),
        )
        assertEquals(
            "projecting=yes dash=up screen=? attempt=1 battery=100% charging=?",
            ProcessStateSummary.describe(text(ProcessStateSummary.encode(ProcessStateSummary.State(look(attempt = 1, battery = 100, charging = null), null, false))), 5_000L),
        )
    }

    @Test
    fun `a screen that went off after the death is counted as zero, never negative`() {
        val said = ProcessStateSummary.describe("REau1;p=1;d=1;o=9999;t=0;b=50;c=0", deathMs = 1_000L)
        assertTrue(said, said!!.contains("off-for=0s"))
    }

    @Test
    fun `what is not the summary of this build says nothing`() {
        for (other in listOf(null, "", "REau2;p=1;d=1;o=-;t=0;b=50;c=0", "REau1;p=1", "WayFindr 1.3.2", "REau1;p=2;d=1;o=-;t=0;b=50;c=0", "REau1;p=1;d=1;o=-;t=0;b=50;c=0;x=1")) {
            assertNull("$other", ProcessStateSummary.describe(other, 1_000L))
        }
    }

    @Test
    fun `the keeper gives the summary at the first look and again only when the state changed`() {
        val given = ArrayList<String>()
        val keeper = ProcessStateSummaryKeeper { given += text(it) }
        keeper.offer(10_000L, look())
        keeper.offer(15_000L, look())
        keeper.offer(20_000L, look(battery = 66)) // the same step of 5 %: not a change
        assertEquals(1, given.size)
        keeper.offer(25_000L, look(battery = 69)) // 65 to 65 still
        keeper.offer(30_000L, look(battery = 70))
        assertEquals(2, given.size)
        keeper.offer(35_000L, look(dashUp = false, battery = 70))
        assertEquals(3, given.size)
        assertTrue(given[2], given[2].contains(";d=0;"))
    }

    @Test
    fun `the keeper takes the instant the screen went off from the first look that saw it off, not from the later ones`() {
        val given = ArrayList<String>()
        val keeper = ProcessStateSummaryKeeper { given += text(it) }
        keeper.offer(100_000L, look(screenOn = true))
        keeper.offer(105_000L, look(screenOn = false))
        keeper.offer(110_000L, look(screenOn = false))
        keeper.offer(115_000L, look(screenOn = null))
        assertEquals(2, given.size)
        assertTrue(given[1], given[1].contains(";o=105;"))
        keeper.offer(120_000L, look(screenOn = true))
        assertTrue(given.last(), given.last().contains(";o=-;"))
        keeper.offer(130_000L, look(screenOn = false))
        assertTrue(given.last(), given.last().contains(";o=130;"))
    }

    @Test
    fun `the end of the projection gives a summary that says the projection and the dash are over, once`() {
        val given = ArrayList<String>()
        val keeper = ProcessStateSummaryKeeper { given += text(it) }
        keeper.ended() // no look yet: nothing to say
        assertEquals(0, given.size)
        keeper.offer(10_000L, look(attempt = 2))
        keeper.ended()
        keeper.ended()
        assertEquals(2, given.size)
        assertTrue(given[1], given[1].startsWith("REau1;p=0;d=0;") && given[1].contains(";t=0;"))
    }
}
