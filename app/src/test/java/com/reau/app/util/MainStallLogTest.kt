package com.reau.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the ride log says when the main thread stops answering ([MainStallLog]): nothing under 1 s, one line at 1 s, 3 s and 5 s (each once while the
 * stall lasts), and one line with the whole time when the main thread answers again.
 */
class MainStallLogTest {
    private val log = MainStallLog()
    private val stack = { "a.B.c < d.E.f" }

    @Test
    fun `a main thread that is late by less than a second says nothing`() {
        assertNull(log.waiting(0, stack))
        assertNull(log.waiting(999, stack))
        assertNull(log.answered(999))
    }

    @Test
    fun `at one second a line says how late it is and where the main thread is`() {
        assertEquals("main thread not answering for 1.0 s, at: a.B.c < d.E.f", log.waiting(1_000, stack))
    }

    @Test
    fun `the same level is never written twice while the stall lasts`() {
        assertTrue(log.waiting(1_000, stack) != null)
        assertNull(log.waiting(1_000, stack))
        assertNull(log.waiting(1_500, stack))
        assertNull(log.waiting(2_999, stack))
    }

    @Test
    fun `a stall that lasts writes again at three seconds and at five and then no more`() {
        assertTrue(log.waiting(1_000, stack) != null)
        assertEquals("main thread not answering for 3.2 s, at: a.B.c < d.E.f", log.waiting(3_200, stack))
        assertNull(log.waiting(4_000, stack))
        assertEquals("main thread not answering for 5.0 s, at: a.B.c < d.E.f", log.waiting(5_000, stack))
        assertNull(log.waiting(5_500, stack))
        assertNull(log.waiting(60_000, stack))
    }

    @Test
    fun `a first look that finds the main thread already five seconds late writes one line, not three`() {
        assertEquals("main thread not answering for 5.4 s, at: a.B.c < d.E.f", log.waiting(5_400, stack))
        assertNull(log.waiting(5_900, stack))
    }

    @Test
    fun `the stack is asked only when a line is written`() {
        var asked = 0
        val counting = { asked++; "x.Y.z" }
        log.waiting(500, counting)
        assertEquals(0, asked)
        log.waiting(1_000, counting)
        log.waiting(1_200, counting)
        assertEquals(1, asked)
    }

    @Test
    fun `when the main thread answers again a line says the whole time`() {
        log.waiting(1_000, stack)
        log.waiting(3_000, stack)
        assertEquals("main thread answered again after 3.7 s", log.answered(3_700))
        assertNull(log.answered(0))
    }

    @Test
    fun `a stall that ended between two looks is still told, with its time`() {
        assertEquals("main thread answered again after 1.4 s", log.answered(1_400))
    }

    @Test
    fun `after the main thread answered a new stall is told from one second again`() {
        log.waiting(1_000, stack)
        log.waiting(5_000, stack)
        log.answered(5_300)
        assertNull(log.waiting(800, stack))
        assertEquals("main thread not answering for 1.1 s, at: a.B.c < d.E.f", log.waiting(1_100, stack))
    }

    @Test
    fun `the levels are one second then three and five`() {
        assertEquals(listOf(1_000L, 3_000L, 5_000L), MainStallLog.LEVELS_MS)
    }

    @Test
    fun `the stack says the top eight frames as class and method only, innermost first`() {
        val frames = (1..12).map { StackTraceElement("com.reau.app.C$it", "m$it", "File$it.kt", it) }
        val text = MainStallLog.stackText(frames)
        assertEquals((1..8).joinToString(" < ") { "com.reau.app.C$it.m$it" }, text)
        assertEquals(8, MainStallLog.STACK_FRAMES)
        assertTrue(text, ".kt" !in text && ":" !in text)
    }

    @Test
    fun `a main thread with no stack to read says so`() {
        assertEquals("no stack", MainStallLog.stackText(emptyList()))
    }
}
