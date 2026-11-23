package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine line of the ride log, from the dash's 0C26 (log only): thresholds measured on 366 rides, state per connection. */
class EngineWatchTest {
    private fun feed(w: EngineWatch, vararg values: Int): List<String> = values.flatMap { w.onReading(it) }

    @Test
    fun `the first reading at 0xB0 or more says running at connect and no start`() {
        assertEquals(listOf("engine at connect: running (0C26 0xE8)"), feed(EngineWatch(), 0xE8))
        assertEquals(listOf("engine at connect: running (0C26 0xB0)"), feed(EngineWatch(), 0xB0))
    }

    @Test
    fun `the first reading below 0x90 says stopped at connect and no stop`() {
        assertEquals(listOf("engine at connect: stopped (0C26 0x5F)"), feed(EngineWatch(), 0x5F))
        assertEquals(listOf("engine at connect: stopped (0C26 0x8F)"), feed(EngineWatch(), 0x8F))
        // two low ones after it: it was stopped already, so nothing more is said
        assertEquals(emptyList<String>(), feed(EngineWatch().also { it.onReading(0x5F) }, 0x4E, 0x48))
    }

    @Test
    fun `the first reading in the dead band says unknown at connect`() {
        assertEquals(listOf("engine at connect: unknown (0C26 0x90)"), feed(EngineWatch(), 0x90))
        assertEquals(listOf("engine at connect: unknown (0C26 0xAF)"), feed(EngineWatch(), 0xAF))
    }

    @Test
    fun `a start is one reading at 0xB0 or more after stopped`() {
        val w = EngineWatch()
        feed(w, 0x40)
        assertEquals(listOf("engine: running (0C26 0xE8)"), w.onReading(0xE8))
        assertEquals(emptyList<String>(), w.onReading(0xE0))
    }

    @Test
    fun `a start from unknown is said too`() {
        val w = EngineWatch()
        feed(w, 0xA0)
        assertEquals(listOf("engine: running (0C26 0xB0)"), w.onReading(0xB0))
    }

    @Test
    fun `a reading just below 0xB0 does not start it`() {
        val w = EngineWatch()
        feed(w, 0x40)
        assertEquals(emptyList<String>(), feed(w, 0xAF, 0x90))
    }

    @Test
    fun `two low readings in a row after running say stopped with both values`() {
        val w = EngineWatch()
        feed(w, 0xE8, 0xE9)
        assertEquals(emptyList<String>(), w.onReading(0x5F))
        assertEquals(listOf("engine: stopped (0C26 0x5F, 0x4E)"), w.onReading(0x4E))
        // the rest of the drop says nothing more
        assertEquals(emptyList<String>(), feed(w, 0x48, 0x44))
    }

    @Test
    fun `the real fall 79 7A 5F 4E 48 44 says stopped once`() {
        val w = EngineWatch()
        feed(w, 0xE8)
        val lines = feed(w, 0x79, 0x7A, 0x5F, 0x4E, 0x48, 0x44)
        assertEquals(listOf("engine: stopped (0C26 0x79, 0x7A)"), lines)
    }

    @Test
    fun `a fall that goes through the dead band says stopped at the first two low ones`() {
        val w = EngineWatch()
        feed(w, 0xE8)
        assertEquals(emptyList<String>(), feed(w, 0xA0, 0x95))
        assertEquals(listOf("engine: stopped (0C26 0x5F, 0x4E)"), feed(w, 0x5F, 0x4E))
    }

    @Test
    fun `one low reading alone does not say stopped`() {
        val w = EngineWatch()
        feed(w, 0xE8)
        assertEquals(emptyList<String>(), feed(w, 0x60, 0xE8, 0xE7, 0xE8))
    }

    @Test
    fun `a reading in the dead band between two low ones breaks the run`() {
        val w = EngineWatch()
        feed(w, 0xE8)
        assertEquals(emptyList<String>(), feed(w, 0x60, 0xA0, 0x60))
        assertEquals(listOf("engine: stopped (0C26 0x60, 0x50)"), w.onReading(0x50))
    }

    @Test
    fun `the dead band alone changes no state`() {
        val w = EngineWatch()
        feed(w, 0xE8)
        assertEquals(emptyList<String>(), feed(w, 0x90, 0xA0, 0xAF, 0x90))
        // still running: a low pair now is a real stop
        assertEquals(listOf("engine: stopped (0C26 0x10, 0x11)"), feed(w, 0x10, 0x11))
    }

    @Test
    fun `two low readings from unknown say stopped`() {
        val w = EngineWatch()
        feed(w, 0xA0)
        assertEquals(listOf("engine: stopped (0C26 0x70, 0x6F)"), feed(w, 0x70, 0x6F))
    }

    @Test
    fun `a stop and a start again are each said once`() {
        val w = EngineWatch()
        val lines = feed(w, 0xE8, 0x50, 0x40, 0x30, 0xC0, 0xC1, 0x20, 0x21)
        assertEquals(
            listOf(
                "engine at connect: running (0C26 0xE8)",
                "engine: stopped (0C26 0x50, 0x40)",
                "engine: running (0C26 0xC0)",
                "engine: stopped (0C26 0x20, 0x21)",
            ),
            lines,
        )
    }

    @Test
    fun `0xF0 or more is said once per rise and counts as running`() {
        val w = EngineWatch()
        assertEquals(listOf("engine at connect: running (0C26 0xE8)"), w.onReading(0xE8))
        assertEquals(listOf("engine: 0C26 0xF9 (cranking or external supply)"), w.onReading(0xF9))
        assertEquals(emptyList<String>(), feed(w, 0xFA, 0xF0, 0xFF))
        // below 0xF0 (still running): the next rise is said again
        assertEquals(emptyList<String>(), w.onReading(0xEF))
        assertEquals(listOf("engine: 0C26 0xF0 (cranking or external supply)"), w.onReading(0xF0))
    }

    @Test
    fun `0xF0 or more after stopped says the start and the high value`() {
        val w = EngineWatch()
        feed(w, 0x40)
        assertEquals(
            listOf("engine: 0C26 0xF9 (cranking or external supply)", "engine: running (0C26 0xF9)"),
            w.onReading(0xF9),
        )
    }

    @Test
    fun `0xF0 or more as the first reading says running at connect and the high value`() {
        assertEquals(
            listOf("engine at connect: running (0C26 0xF9)", "engine: 0C26 0xF9 (cranking or external supply)"),
            feed(EngineWatch(), 0xF9),
        )
    }

    @Test
    fun `each connection starts over`() {
        val w = EngineWatch()
        assertEquals(listOf("engine at connect: running (0C26 0xE8)"), feed(w, 0xE8))
        feed(w, 0x60) // a low one left over from the connection before
        w.reset()
        assertEquals(listOf("engine at connect: stopped (0C26 0x40)"), feed(w, 0x40))
        w.reset()
        // the low one of before does not count: one low reading alone after running says nothing
        assertEquals(listOf("engine at connect: running (0C26 0xE8)"), feed(w, 0xE8))
        assertTrue(feed(w, 0x60, 0xE8).isEmpty())
        w.reset()
        // the 0xF0 flag starts over as well
        assertEquals(2, feed(w, 0xF9).size)
        w.reset()
        assertEquals(2, feed(w, 0xF9).size)
    }
}
