package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.dash.WifiConnStatus
import com.reau.app.dash.WifiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's Wi-Fi radio off: the link to the dash asks Android for no network (each request would wait its 30 s for a grant that cannot come), tells
 * the projection the radio is off, and asks at once when it is on. With the machine: the radio coming on cuts the wait of the retry.
 */
class WifiDashLinkUpRadioTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val wifi = MutableStateFlow(WifiState())
    private var radio = false
    private val asked = mutableListOf<String>()
    private val ready = mutableListOf<String>()
    private val failed = mutableListOf<String>()
    private val radioSaid = mutableListOf<Boolean>()
    private val logged = mutableListOf<String>()

    private class Known(override var ssid: String = "RE_AB12_345678") : WifiDashLinkUp.Pairing {
        override val ssidPrefix = "RE_"
        override val password = "secret"
        override fun remember(ssid: String) { this.ssid = ssid }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun linkUp() = WifiDashLinkUp(
        scope, wifi, { null },
        { ssid, _, _ -> asked += ssid; wifi.value = WifiState(WifiConnStatus.CONNECTED, ssid = ssid) },
        { }, Known(), radioOn = { radio },
    ).also {
        it.onWifiRadio { on -> radioSaid += on }
        it.joining = WifiDashLinkUp.Joining(log = { line -> logged += line })
    }

    @Test
    fun `with the radio off no network is asked for, however many times the projection tries`() {
        val link = linkUp()
        repeat(5) { link.start({ ssid, _ -> ready += ssid }, { failed += it }) }
        assertTrue("asked for $asked", asked.isEmpty())
        assertEquals(List(5) { WifiDashLinkUp.RADIO_OFF }, failed)
        assertTrue(ready.isEmpty())
    }

    @Test
    fun `the projection is told at every try that the radio is off, and the log says it once`() {
        val link = linkUp()
        repeat(3) { link.start({ _, _ -> }, { }) }
        assertEquals(listOf(false, false, false), radioSaid)
        assertEquals("said once, not at every retry: $logged", 1, logged.count { "Wi-Fi is off" in it })
    }

    @Test
    fun `the failure is a dropped dash for the machine, whose retries then find the radio again`() {
        assertEquals(DashLink.DROPPED, WifiDashLinkUp.linkAfterFailure(WifiDashLinkUp.RADIO_OFF))
    }

    @Test
    fun `with the radio on the network is asked for at once and the radio is said to be on`() {
        val link = linkUp()
        link.start({ _, _ -> }, { failed += it })
        assertEquals(listOf(WifiDashLinkUp.RADIO_OFF), failed)
        radio = true
        link.start({ ssid, _ -> ready += ssid }, { failed += it })
        assertEquals(listOf("RE_AB12_345678"), asked)
        assertEquals(listOf("RE_AB12_345678"), ready)
        assertEquals(listOf(false, true), radioSaid)
    }

    @Test
    fun `the log says it again the next time the radio goes off`() {
        val link = linkUp()
        link.start({ _, _ -> }, { })
        radio = true
        link.start({ _, _ -> }, { })
        radio = false
        link.start({ _, _ -> }, { })
        assertEquals(2, logged.count { "Wi-Fi is off" in it })
    }

    @Test
    fun `the machine tries the dash the moment the radio comes on, and the link then asks for the network`() {
        // The radio is off: the try fails, the machine waits its retry; the radio's broadcast cuts the wait and the next try is the request.
        val link = linkUp()
        var state = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = DashLink.CONNECTING)
        link.start({ _, _ -> }, { })
        state = ProjectionMachine.step(state, ProjectionEvent.WifiRadio(false)).state
        state = ProjectionMachine.step(state, ProjectionEvent.DashChanged(DashLink.DROPPED), nowMs = 1_000L).state
        assertEquals(Headline.WIFI_OFF, state.headline)
        assertTrue(asked.isEmpty())

        radio = true
        val step = ProjectionMachine.step(state, ProjectionEvent.WifiRadio(true), nowMs = 20_000L)
        assertEquals(listOf(ProjectionEffect.CancelDashRetry, ProjectionEffect.StartDash), step.effects)
        link.start({ ssid, _ -> ready += ssid }, { failed += it })
        assertEquals(listOf("RE_AB12_345678"), asked)
    }
}
