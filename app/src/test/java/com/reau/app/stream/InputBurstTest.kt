package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How long the rider's input counts as fresh, for drawing Android Auto's answer at once ([FreshInput]). */
class InputBurstTest {
    @Test
    fun `nothing is fresh before the first input`() {
        assertFalse(FreshInput(1_800).fresh(0))
        assertFalse(FreshInput(1_800).fresh(5_000))
    }

    @Test
    fun `an input is fresh for the window and then stops being`() {
        val input = FreshInput(1_800)
        input.sent(10_000)
        assertTrue(input.fresh(10_000))
        assertTrue(input.fresh(11_800))
        assertFalse(input.fresh(11_801))
    }

    @Test
    fun `a new input renews the window`() {
        val input = FreshInput(1_800)
        input.sent(10_000)
        input.sent(11_500)
        assertTrue(input.fresh(13_300))
        assertFalse(input.fresh(13_301))
    }

    @Test
    fun `the picture is looked at four times a second, ten right after an input, for three seconds`() {
        assertEquals(250L, InputBurst.lookEveryMs(lookQuick = false))
        assertEquals(100L, InputBurst.lookEveryMs(lookQuick = true))
        val input = FreshInput(InputBurst.LOOK_QUICK_WINDOW_MS)
        input.sent(10_000)
        assertTrue(input.fresh(13_000))
        assertFalse(input.fresh(13_001))
    }
}
