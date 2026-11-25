package com.reau.app.service

import com.reau.app.R
import com.reau.app.aa.AaLinkState
import com.reau.app.ui.MainLook
import com.reau.app.ui.ScreenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's Wi-Fi radio off: the rule that says so to the rider (pure), the events that raise and clear it, and the dash being tried at once when the
 * radio comes on. Before it the projection stayed on "looking for the dash" for ever, with no word of why.
 */
class ProjectionMachineWifiOffTest {
    private fun running(dash: DashLink, wifiOff: Boolean = true) = Projection(
        phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = dash, wifiOff = wifiOff,
    )

    // ── the rule ───────────────────────────────────────────────────────────

    @Test
    fun `with the radio off and the dash not ready the headline asks for the Wi-Fi`() {
        for (dash in listOf(DashLink.CONNECTING, DashLink.DROPPED, DashLink.OFF)) {
            assertEquals(dash.name, Headline.WIFI_OFF, running(dash).headline)
            assertTrue(dash.name, running(dash).wifiOffAlarm)
        }
    }

    @Test
    fun `with the radio on nothing changes`() {
        assertEquals(Headline.DASH_SEARCHING, running(DashLink.DROPPED, wifiOff = false).headline)
        assertEquals(Headline.CONNECTING, running(DashLink.CONNECTING, wifiOff = false).headline)
        assertFalse(running(DashLink.DROPPED, wifiOff = false).wifiOffAlarm)
    }

    @Test
    fun `a ready dash is no reason to ask for the Wi-Fi, the drop that follows is`() {
        assertEquals(Headline.STREAMING, running(DashLink.READY).headline)
        assertFalse(running(DashLink.READY).wifiOffAlarm)
        assertEquals(Headline.WIFI_OFF, running(DashLink.DROPPED).copy(dashWasReady = true).headline)
    }

    @Test
    fun `the radio off is the answer to a lost dash, not the dash lost`() {
        val lost = running(DashLink.DROPPED).copy(dashWasReady = true)
        assertEquals(Headline.WIFI_OFF, lost.headline)
        assertEquals(Headline.DASH_LOST, lost.copy(wifiOff = false).headline)
    }

    @Test
    fun `the network server does not look for the dash, so the radio is none of its business`() {
        assertEquals(Headline.NETWORK_SERVER, running(DashLink.OFF).copy(networkServer = true).headline)
        assertFalse(running(DashLink.OFF).copy(networkServer = true).wifiOffAlarm)
    }

    @Test
    fun `a stopped or preparing projection asks for nothing`() {
        assertEquals(Headline.STOPPED, Projection(wifiOff = true).headline)
        assertFalse(Projection(wifiOff = true).wifiOffAlarm)
        assertEquals(Headline.PREPARING, Projection(phase = Phase.PREPARING, wifiOff = true).headline)
        assertFalse(Projection(phase = Phase.PREPARING, wifiOff = true).wifiOffAlarm)
    }

    @Test
    fun `what Android Auto asks of the rider comes first, and the card mode needs the radio too`() {
        assertEquals(Headline.ANDROID_AUTO_SERVER_OFF, running(DashLink.DROPPED).copy(androidAuto = AaLinkState.SERVER_OFF).headline)
        assertEquals(Headline.WIFI_OFF, running(DashLink.CONNECTING).copy(card = true, androidAuto = AaLinkState.IDLE).headline)
    }

    @Test
    fun `the radio off outranks a dash taken or a network never approved`() {
        assertEquals(Headline.WIFI_OFF, running(DashLink.DROPPED).copy(dashFirstJoin = true).headline)
        assertEquals(Headline.WIFI_OFF, running(DashLink.TAKEN).headline)
    }

    // ── the events ─────────────────────────────────────────────────────────

    @Test
    fun `the radio going off raises the flag of a running projection`() {
        val step = ProjectionMachine.step(running(DashLink.DROPPED, wifiOff = false), ProjectionEvent.WifiRadio(false))
        assertTrue(step.state.wifiOff)
        assertTrue("only the state changes", step.effects.isEmpty())
        assertEquals(Headline.WIFI_OFF, step.state.headline)
    }

    @Test
    fun `a projection that is not running does not take the radio`() {
        for (state in listOf(Projection(), Projection(phase = Phase.PREPARING))) {
            assertFalse(ProjectionMachine.step(state, ProjectionEvent.WifiRadio(false)).state.wifiOff)
        }
    }

    @Test
    fun `the radio coming on while a dropped dash waits for its retry cuts the wait and tries the dash now`() {
        val waiting = running(DashLink.DROPPED).copy(dashRetries = 4, dashGoneAtMs = 1_000L)
        val step = ProjectionMachine.step(waiting, ProjectionEvent.WifiRadio(true), nowMs = 90_000L)
        assertEquals(listOf(ProjectionEffect.CancelDashRetry, ProjectionEffect.StartDash), step.effects)
        assertEquals(DashLink.CONNECTING, step.state.dash)
        assertFalse(step.state.wifiOff)
        assertEquals("the pace of the waits starts over", 0 to null, step.state.dashRetries to step.state.dashGoneAtMs)
    }

    @Test
    fun `the radio on again with the dash waiting for Android Auto only clears the flag`() {
        val step = ProjectionMachine.step(running(DashLink.OFF), ProjectionEvent.WifiRadio(true))
        assertFalse(step.state.wifiOff)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun `a radio that was not known to be off does not cut any wait`() {
        val waiting = running(DashLink.DROPPED, wifiOff = false).copy(dashRetries = 2, dashGoneAtMs = 1_000L)
        val step = ProjectionMachine.step(waiting, ProjectionEvent.WifiRadio(true), nowMs = 5_000L)
        assertEquals(waiting, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun `a ready dash proves the radio is on, whatever the broadcast missed`() {
        val step = ProjectionMachine.step(running(DashLink.CONNECTING), ProjectionEvent.DashChanged(DashLink.READY))
        assertFalse(step.state.wifiOff)
        assertEquals(Headline.STREAMING, step.state.copy(androidAuto = AaLinkState.ACTIVE).headline)
    }

    @Test
    fun `stopping forgets the radio`() {
        val stopped = ProjectionMachine.step(running(DashLink.DROPPED), ProjectionEvent.Stop).state
        assertFalse(stopped.wifiOff)
    }

    // ── what the rider reads and gets ──────────────────────────────────────

    @Test
    fun `the screen, the line under it, the warning card and the notification all say the radio is off`() {
        val state = running(DashLink.DROPPED)
        assertEquals(R.string.headline_wifi_off, ScreenText.headline(Headline.WIFI_OFF))
        assertEquals(R.string.sub_wifi_off, MainLook.sub(state))
        assertEquals(R.string.notif_title_wifi_off to R.string.notif_text_wifi_off, ProjectionNotification.contentFor(Headline.WIFI_OFF))
        assertTrue(MainLook.Warning.WIFI_OFF in MainLook.warnings(state, com.reau.app.ui.BatteryUi()))
        assertFalse(MainLook.Warning.WIFI_OFF in MainLook.warnings(state.copy(wifiOff = false), com.reau.app.ui.BatteryUi()))
        assertFalse(MainLook.Warning.WIFI_OFF in MainLook.warnings(running(DashLink.READY), com.reau.app.ui.BatteryUi()))
    }

    @Test
    fun `the warning is the rider's to act on, so it takes the error colour like the others`() {
        assertEquals(ProjectionNotification.colorFor(Headline.DASH_TAKEN), ProjectionNotification.colorFor(Headline.WIFI_OFF))
    }

    @Test
    fun `the button opens Android's Wi-Fi panel`() {
        assertEquals(android.provider.Settings.Panel.ACTION_WIFI, WifiPanel.ACTION)
    }

    // ── the notices a change of state asks for ─────────────────────────────

    @Test
    fun `the notice comes when the alarm rises, goes when it falls and is not repeated while it stays`() {
        val ok = running(DashLink.DROPPED, wifiOff = false)
        val off = running(DashLink.DROPPED)
        assertEquals(listOf(StateAlerts.Alert.WIFI_OFF_SHOW), StateAlerts.between(ok, off))
        assertEquals(listOf(StateAlerts.Alert.WIFI_OFF_CLEAR), StateAlerts.between(off, ok))
        assertTrue(StateAlerts.between(off, off.copy(dashRetries = 3)).isEmpty())
        assertTrue(StateAlerts.between(ok, ok.copy(dashRetries = 3)).isEmpty())
    }

    @Test
    fun `the notice goes with the projection and when the dash is ready`() {
        val off = running(DashLink.DROPPED)
        assertEquals(listOf(StateAlerts.Alert.WIFI_OFF_CLEAR), StateAlerts.between(off, Projection(stopping = true)))
        assertEquals(StateAlerts.Alert.WIFI_OFF_CLEAR, StateAlerts.between(off, off.copy(dash = DashLink.READY)).first())
    }

    @Test
    fun `the dash being back is told when it turns ready in a running projection, once`() {
        val gone = running(DashLink.DROPPED, wifiOff = false)
        val back = gone.copy(dash = DashLink.READY)
        assertEquals(listOf(StateAlerts.Alert.DASH_BACK), StateAlerts.between(gone, back))
        assertTrue("still ready: nothing to tell", StateAlerts.between(back, back.copy(dashRetries = 0)).isEmpty())
        assertEquals(listOf(StateAlerts.Alert.DASH_BACK), StateAlerts.between(running(DashLink.CONNECTING, wifiOff = false), back))
        assertTrue("not in a stopped projection", StateAlerts.between(gone, Projection(dash = DashLink.READY)).isEmpty())
    }
}
