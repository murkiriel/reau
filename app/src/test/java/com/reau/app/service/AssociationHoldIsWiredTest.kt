package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Wi-Fi manager needs Android to run, so the lines that wire the decisions of AssociationInFlightTest are read from the source, as
 * [DashRecoveryIsWiredTest] does: both watches look at the phone's join at each tick and hold the request under it, the ticks are the
 * shared 0.5 s, and none of this asks Android for a scan (the system limits them and they cost battery).
 */
class AssociationHoldIsWiredTest {
    private val dir = "src/main/java/com/reau/app/"
    private val wifi = File(dir + "dash/DashWifiManager.kt").readText()
    private val scan = File(dir + "dash/DashScan.kt").readText()
    private val probe = File(dir + "dash/AssociationProbe.kt").readText()
    private val linkUp = File(dir + "service/DashLinkUp.kt").readText()

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `the watch over a request waits with the tick that looks at the join, and its hold counts the join`() {
        val watch = wifi.between("private fun watchForTheDash(", "private fun stillRequesting(")
        assertTrue(watch.contains("joins.tick()"))
        assertTrue(watch.contains("val hold = joins.hold()"))
        val joins = wifi.between("private inner class Joins {", "private fun scheduleReconnect")
        assertTrue("the wait, then the look at the join", joins.indexOf("delay(DashJoin.WATCH_PERIOD_MS)") in 0 until joins.indexOf("probe.look(pendingSsid, pendingPrefix,"))
        assertTrue("the hold reads the network already up too", joins.contains("findAlreadyConnectedDashNetwork() != null") && joins.contains("probe = probe"))
        val hold = probe.substringAfter("internal class JoinHold(") // the last class of the file
        assertTrue(hold.contains("probe.joining(up, now)"))
        assertTrue("a network that is up keeps its own line, the join says so", hold.contains("probe.line("))
    }

    @Test
    fun `the watch over a loss waits with the same tick, holds under the join and says so`() {
        assertTrue(wifi.between("private fun watchAfterLoss()", "private fun stillLost()").contains("joins.tick()"))
        val refile = wifi.between("private fun refileForSighting(", "// ── A hung dash")
        assertTrue(refile.contains("joins.probe.holdsNow("))
        assertTrue(refile.contains("joins.probe.lossLine(sinceLostMs, requests, maskSsid(pendingSsid))"))
    }

    @Test
    fun `the line of the join masks the network's name, and the probe writes neither a name nor an address`() {
        assertTrue(wifi.between("private inner class Joins {", "private fun scheduleReconnect").contains("maskSsid(pendingSsid)"))
        assertFalse(probe.contains("Log."))
        assertFalse(probe.contains("RideDiagnostics"))
    }

    @Test
    fun `the looks are every half second and read the list only, no scan is asked for`() {
        assertEquals("one wait for the watch over a request and the one over a loss", 1, Regex("""delay\(DashJoin\.WATCH_PERIOD_MS\)""").findAll(wifi).count())
        assertEquals(2, Regex("""joins\.tick\(\)""").findAll(wifi).count())
        assertTrue(linkUp.contains("const val SIGHTING_POLL_MS = com.reau.app.dash.DashJoin.WATCH_PERIOD_MS"))
        for ((name, text) in mapOf("DashWifiManager" to wifi, "DashScan" to scan, "AssociationProbe" to probe, "DashLinkUp" to linkUp)) {
            assertFalse("$name asks for a scan", text.contains(Regex("""\.startScan\s*\(""")))
        }
    }
}
