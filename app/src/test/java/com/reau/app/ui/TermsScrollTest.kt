package com.reau.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermsScrollTest {
    private val density = 2.75f

    @Test
    fun theRollTakesBetweenOneAndTwoSeconds() {
        assertEquals(1_000, TermsScroll.MIN_MS)
        assertEquals(2_000, TermsScroll.MAX_MS)
    }

    @Test
    fun theTextGoesByAtAPaceTheEyeCanFollow() {
        // Under 1,000 dp a second, a few screens each second; the old 1,800 dp (5,000 px) a second was a blur.
        assertTrue(TermsScroll.DP_PER_SECOND <= 1_000f)
        assertTrue(TermsScroll.DP_PER_SECOND >= 300f)
    }

    @Test
    fun aShortDistanceTakesTheMinimum() {
        assertEquals(TermsScroll.MIN_MS, TermsScroll.durationMs(1_000, density))
    }

    @Test
    fun noDistanceTakesTheMinimumToo() {
        assertEquals(TermsScroll.MIN_MS, TermsScroll.durationMs(0, density))
        assertEquals(TermsScroll.MIN_MS, TermsScroll.durationMs(-5, density))
    }

    @Test
    fun aVeryLongTextTakesTheMaximum() {
        assertEquals(TermsScroll.MAX_MS, TermsScroll.durationMs(2_000_000, density))
    }

    @Test
    fun aLongerTextTakesLongerUpToTheMaximum() {
        // 600 dp a second at density 2.75 is 1,650 px a second.
        assertEquals(1_200, TermsScroll.durationMs(1_980, density))
        assertEquals(1_800, TermsScroll.durationMs(2_970, density))
        assertTrue(TermsScroll.durationMs(2_970, density) > TermsScroll.durationMs(1_980, density))
        // The whole terms on a phone (about 4,200 dp) would take 7 s at this pace: it is cut to the 2 s ceiling.
        assertEquals(TermsScroll.MAX_MS, TermsScroll.durationMs(11_550, density))
    }

    @Test
    fun theSameTimeOnAnyDensity() {
        assertEquals(TermsScroll.durationMs(1_800, 2f), TermsScroll.durationMs(2_700, 3f))
        assertEquals(1_500, TermsScroll.durationMs(1_800, 2f))
    }

    @Test
    fun aBrokenDensityFallsBackToTheMinimum() {
        assertEquals(TermsScroll.MIN_MS, TermsScroll.durationMs(20_000, 0f))
    }
}
