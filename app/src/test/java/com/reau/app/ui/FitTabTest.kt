package com.reau.app.ui

import com.reau.app.aa.AaScreen
import org.junit.Assert.assertEquals
import org.junit.Test

/** The fit opens on the tab of the screen Android Auto shows now. */
class FitTabTest {
    @Test
    fun `the fit opens on the tab of the screen shown`() {
        assertEquals(FitTab.NAV, FitTab.opening(AaScreen.NAV))
        assertEquals("the app menu has no tab: it shares the others'", FitTab.APPS, FitTab.opening(AaScreen.MENU))
        assertEquals(FitTab.APPS, FitTab.opening(AaScreen.APPS))
        assertEquals("not known yet: the apps'", FitTab.APPS, FitTab.opening(AaScreen.UNKNOWN))
    }

    @Test
    fun `the tabs are in the order of the rail`() {
        assertEquals(listOf("fit_tab_nav", "fit_tab_apps"), FitTab.entries.map { it.tag })
    }
}
