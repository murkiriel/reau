package com.reau.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.reau.app.ui.theme.REauDark
import com.reau.app.ui.theme.REauLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fuel pump beside the range: the text colour when off, yellow on a dark disc and blinking when the dash's reminder is on. */
class FuelIconLookTest {
    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()).toDouble()
        val lo = minOf(a.luminance(), b.luminance()).toDouble()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `off or never said the pump is in the text colour of each theme, with no disc and no blink`() {
        for (scheme in listOf(REauLight, REauDark)) {
            for (warning in listOf(false, null)) {
                for (shown in listOf(true, false)) {
                    val look = FuelIconLook.of(warning, shown, scheme.onSurface)
                    assertEquals(scheme.onSurface, look.tint)
                    assertNull(look.chip)
                    assertFalse("the blink only hides the warning", look.hidden)
                }
            }
        }
    }

    @Test
    fun `on, the pump is yellow on a dark disc and hidden in the blink's dark second`() {
        val lit = FuelIconLook.of(true, blinkShown = true, text = REauLight.onSurface)
        assertEquals(com.reau.app.ui.theme.Ouro, lit.tint)
        assertEquals(com.reau.app.ui.theme.Grafite, lit.chip)
        assertFalse(lit.hidden)
        assertTrue(FuelIconLook.of(true, blinkShown = false, text = REauLight.onSurface).hidden)
    }

    @Test
    fun `the lit pump reads on both themes and the disc reads on the light one`() {
        val lit = FuelIconLook.of(true, true, REauLight.onSurface)
        val disc = checkNotNull(lit.chip)
        assertTrue("pump on disc", contrast(lit.tint, disc) >= 7.0)
        assertTrue("disc on the light theme's surface", contrast(disc, REauLight.surface) >= 7.0)
        assertTrue("pump on the dark theme's card", contrast(lit.tint, REauDark.surfaceContainer) >= 4.5)
    }

    @Test
    fun `the blink is a second lit and a second dark`() {
        assertEquals(1_000, FuelIconLook.BLINK_MS)
    }
}
