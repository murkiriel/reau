package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The app in front on the phone's screen, for the server walk: never a window of Android Auto's virtual displays. */
class FrontWindowTest {
    private val aa = "com.google.android.projection.gearhead"
    private val maps = "com.google.android.apps.maps"

    @Test
    fun `the active window of the phone's screen is the one in front`() {
        val windows = listOf(FrontWindow.Win("com.reau.app", active = false, focused = false), FrontWindow.Win(aa, active = true, focused = true))
        assertEquals(aa, FrontWindow.of(windows, fallbackActive = maps))
    }

    @Test
    fun `Maps' projection window as the system's active one is not in front when the phone's windows say Android Auto is`() {
        // 23/10/2026: the system's active window was Maps' (a virtual display), the phone's screen held Android Auto's settings
        assertEquals(aa, FrontWindow.of(listOf(FrontWindow.Win(aa, active = true, focused = true)), fallbackActive = maps))
    }

    @Test
    fun `with no window active the focused one is`() {
        val windows = listOf(FrontWindow.Win("com.reau.app", active = false, focused = false), FrontWindow.Win(aa, active = false, focused = true))
        assertEquals(aa, FrontWindow.of(windows, fallbackActive = null))
    }

    @Test
    fun `windows with none active or focused say nothing, which is not another app in front`() {
        assertNull(FrontWindow.of(listOf(FrontWindow.Win(aa, active = false, focused = false)), fallbackActive = maps))
    }

    @Test
    fun `with no window listed the active one stands in`() {
        assertEquals(aa, FrontWindow.of(emptyList(), fallbackActive = aa))
        assertNull(FrontWindow.of(emptyList(), fallbackActive = null))
    }
}
