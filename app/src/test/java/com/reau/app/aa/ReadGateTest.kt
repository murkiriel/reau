package com.reau.app.aa

import com.reau.app.aa.ReadGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** The accessibility service read the screen at every window event, on the main thread (audit of 20/10/2026). */
class ReadGateTest {
    @Test
    fun `nothing is read when nothing needs it`() {
        val gate = ReadGate()
        assertEquals(Verdict.Skip, gate.onEvent(needed = false, nowMs = 1_000))
    }

    @Test
    fun `the first event reads at once, the ones inside the gap wait for its end`() {
        val gate = ReadGate(minGapMs = 100)
        assertEquals(Verdict.Now, gate.onEvent(true, 1_000))
        gate.read(1_000)
        assertEquals(Verdict.Later(afterMs = 70, first = true), gate.onEvent(true, 1_030))
        // more events before the planned read: still one read to come, so the caller plans nothing new
        assertEquals(Verdict.Later(afterMs = 40, first = false), gate.onEvent(true, 1_060))
        assertEquals(Verdict.Later(afterMs = 10, first = false), gate.onEvent(true, 1_090))
    }

    @Test
    fun `after the gap an event reads again, and a read made by someone else counts`() {
        val gate = ReadGate(minGapMs = 100)
        gate.read(1_000)
        assertEquals(Verdict.Now, gate.onEvent(true, 1_100))
        gate.read(1_100) // the poll or the planned read
        assertEquals(Verdict.Later(afterMs = 50, first = true), gate.onEvent(true, 1_150))
        gate.read(1_200) // the planned read took it
        assertEquals("a new gap begins", Verdict.Later(afterMs = 100, first = true), gate.onEvent(true, 1_200))
    }

    @Test
    fun `a reset forgets the planned read and the last one`() {
        val gate = ReadGate(minGapMs = 100)
        gate.read(1_000)
        assertEquals(Verdict.Later(afterMs = 70, first = true), gate.onEvent(true, 1_030))
        gate.reset()
        assertEquals(Verdict.Now, gate.onEvent(true, 1_040))
    }

    @Test
    fun `a hundred events in a second cost at most ten reads`() {
        val gate = ReadGate(minGapMs = 100)
        var reads = 0
        var planned: Long? = null
        for (t in 0L until 1_000L step 10) {
            if (planned != null && t >= planned) { gate.read(t); reads++; planned = null }
            when (val v = gate.onEvent(true, t)) {
                Verdict.Now -> { gate.read(t); reads++ }
                is Verdict.Later -> if (v.first) planned = t + v.afterMs
                Verdict.Skip -> Unit
            }
        }
        assertEquals(true, reads <= 11)
    }
}
