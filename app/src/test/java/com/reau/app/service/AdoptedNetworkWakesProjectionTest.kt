package com.reau.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Wi-Fi manager needs Android to run, so the wiring is read from the source, as [AssociationHoldIsWiredTest] does. A dash network the phone joined by
 * itself and the manager took (useAlreadyConnectedNetwork) tells whoever listens, as a network the request got does: the projection, with a dash dropped
 * and waiting out its retry, tries it at once (ride of 24/11/2026, 21:01: the network was taken, nobody was told, the projection sat out its minute and the
 * image came back 102.9 s after the dash was on).
 */
class AdoptedNetworkWakesProjectionTest {
    private val dir = "src/main/java/com/reau/app/"
    private val wifi = File(dir + "dash/DashWifiManager.kt").readText()

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `the network taken as already joined tells the listener, after the state says connected`() {
        val adopt = wifi.between("private fun useAlreadyConnectedNetwork(): Boolean {", "private fun verifyJoinedLater(")
        val connected = adopt.indexOf("_state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = activeSsid)")
        val told = adopt.indexOf("onNetworkAvailable?.invoke()")
        assertTrue("the state is set", connected >= 0)
        assertTrue("the listener is told, after the state (the projection reads it)", told > connected)
        assertTrue("and before the call returns", told < adopt.lastIndexOf("return true"))
        assertTrue("the ride log says so, as for a network the request got", adopt.contains("SystemEvents.log(com.reau.app.service.SystemEvents.bikeNetwork(true))"))
    }

    @Test
    fun `the listener is the one that cuts the projection's wait`() {
        val sides = File(dir + "service/LiveSides.kt").readText()
        assertTrue(sides.contains("wifi.onNetworkAvailable = { bikeNetworkAvailable() }"))
        assertTrue(sides.contains("report(ProjectionEvent.BikeNetworkAvailable)"))
    }
}
