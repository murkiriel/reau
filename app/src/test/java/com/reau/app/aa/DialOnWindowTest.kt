package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialOnWindowTest {
    @Test
    fun `the first window may dial, a second one inside a second may not, and one after it may`() {
        val dial = DialOnWindow()
        assertTrue(dial.allow(5_000))
        assertFalse(dial.allow(5_400))
        assertFalse(dial.allow(5_999))
        assertTrue(dial.allow(6_000))
    }

    @Test
    fun `a refused window does not push the next one away`() {
        val dial = DialOnWindow()
        assertTrue(dial.allow(0))
        assertFalse(dial.allow(900))        // refused: the gap counts from the dial that went, not from this one
        assertTrue(dial.allow(1_000))
    }

    @Test
    fun `ten a projection, then none until a new projection`() {
        val dial = DialOnWindow()
        val went = (0 until 25).count { dial.allow(it * 1_500L) }
        assertEquals(10, went)
        assertFalse(dial.allow(1_000_000))
        dial.reset()
        assertTrue("a new projection has its own ten", dial.allow(1_000_000))
        assertEquals(9, (1 until 20).count { dial.allow(1_000_000 + it * 1_500L) })
    }

    @Test
    fun `the time starts anywhere, and a clock that starts at zero is fine`() {
        val dial = DialOnWindow()
        assertTrue(dial.allow(0))
        assertFalse(dial.allow(500))
        assertTrue(dial.allow(1_500))
    }
}
