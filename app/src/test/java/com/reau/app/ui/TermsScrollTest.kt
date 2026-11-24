package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Descer até o fim" roll takes exactly one second, always (the owner's decision of 24/11/2026; it was 1 to 2 s by the distance). */
class TermsScrollTest {
    private val screen = File("src/main/java/com/reau/app/ui/TermsScreen.kt").readText()

    @Test
    fun `the roll takes one second`() {
        assertEquals(1_000, TermsScroll.DURATION_MS)
    }

    @Test
    fun `the button rolls with that time, whatever the distance or the density`() {
        assertTrue(screen.contains("tween<Float>(TermsScroll.DURATION_MS, easing = LinearEasing)"))
        assertFalse("the time no longer follows the distance", screen.contains("TermsScroll.durationMs"))
        assertFalse("nor the density", screen.contains("LocalDensity"))
    }

    @Test
    fun `when there is nothing to roll there is no animation`() {
        assertTrue(screen.contains("if (scroll.maxValue - scroll.value > 0) {"))
    }
}
