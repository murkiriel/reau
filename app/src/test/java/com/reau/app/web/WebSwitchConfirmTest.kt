package com.reau.app.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rider turned the network server on with the projection running and the app stopped looking for the dash for seven minutes, without knowing
 * that the switch does that. So turning it on asks first when the projection runs or the dash is in use; turning it off never asks, and with
 * the projection off (nothing in use) the text under the switch is what warns.
 */
class WebSwitchConfirmTest {
    @Test
    fun `turning on with the projection running asks`() {
        assertTrue(WebSwitch.needsConfirmation(turningOn = true, projectionOn = true, dashInUse = false))
    }

    @Test
    fun `turning on with the dash in use asks even if the projection flag is off`() {
        assertTrue(WebSwitch.needsConfirmation(turningOn = true, projectionOn = false, dashInUse = true))
    }

    @Test
    fun `turning on with the projection running and the dash in use asks`() {
        assertTrue(WebSwitch.needsConfirmation(turningOn = true, projectionOn = true, dashInUse = true))
    }

    @Test
    fun `turning on with the projection off and the dash not in use goes straight through`() {
        assertFalse(WebSwitch.needsConfirmation(turningOn = true, projectionOn = false, dashInUse = false))
    }

    @Test
    fun `turning off never asks`() {
        for (projectionOn in listOf(true, false)) {
            for (dashInUse in listOf(true, false)) {
                assertFalse("$projectionOn $dashInUse", WebSwitch.needsConfirmation(turningOn = false, projectionOn = projectionOn, dashInUse = dashInUse))
            }
        }
    }
}
