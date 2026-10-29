package com.reau.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The warning is for a rider who gave REau the location and then switched Android's Location off; no permission is the permissions screen's to say. */
class LocationSwitchTest {
    @Test
    fun `permission given and the switch off is the warning`() {
        assertTrue(LocationSwitch.offDespitePermission(granted = true, switchOn = false))
    }

    @Test
    fun `the switch on, or no permission, is not`() {
        assertFalse(LocationSwitch.offDespitePermission(granted = true, switchOn = true))
        assertFalse(LocationSwitch.offDespitePermission(granted = false, switchOn = false))
        assertFalse(LocationSwitch.offDespitePermission(granted = false, switchOn = true))
    }
}
