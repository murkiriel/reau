package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ride log of 23/11/2026 (the ignition switched off and on, three times): in the quick one, at 21:15:49 and 21:15:55, the watch put the request out
 * again while Android was already joining the dash, and each time Android dropped the attempt (6.6 s lost). The guard of a join holds the request
 * now when the supplicant is in the middle of joining the dash, with the 6 s limit of the old guard, and lets it go as before in the other looks.
 *
 * The numbers are the log's, in milliseconds from 21:15:00, and the watch looks at each 0.5 s tick.
 */
class AssociationInFlightTest {
    private val sec = 1_000_000L

    private val dash = "RE_DASH_0001"

    /** The decision of the watch over a loss at [nowMs], with the join the probe has seen so far (the numbers of the sighting at 21:15:55.6, 14.3 s after the loss). */
    private fun atTheSighting(flight: AssociationInFlight, hold: HoldWhileJoining, nowMs: Long, networkUp: Boolean = false) = DashJoin.refileAfterLossHeld(
        firstApproval = false, lostAtUs = 0L, sightingStampUs = 50 * sec, nowUs = 56 * sec, requestAgeMs = 6_200L, inPause = false,
        sinceLastRefileMs = null, hold = hold, nowMs = nowMs, joiningNow = { flight.inFlight(nowMs, networkUp) },
    )

    // ── The join of the log is held ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the sighting while the supplicant associates with the dash is held, and the request is not put out again`() {
        val flight = AssociationInFlight()
        // The supplicant went ASSOCIATING with the dash at 54.94; the 0.5 s ticks that followed saw it.
        flight.look("ASSOCIATING", ofTheDash = true, nowMs = 55_100L)
        flight.look("ASSOCIATED", ofTheDash = true, nowMs = 55_600L)
        assertEquals(DashJoin.LossRefile.HELD_FIRST, atTheSighting(flight, HoldWhileJoining(), nowMs = 55_600L))
    }

    @Test
    fun `each of the states of a join under way holds, from CONNECTING to COMPLETED with the network not up yet`() {
        for (state in listOf("CONNECTING", "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE", "COMPLETED")) {
            val flight = AssociationInFlight().also { it.look(state, ofTheDash = true, nowMs = 1_000L) }
            assertTrue(state, flight.inFlight(1_000L, networkUp = false))
            assertEquals(state, flight.state)
        }
    }

    @Test
    fun `the join seen a moment ago still holds, since the states pass in tens of milliseconds`() {
        val flight = AssociationInFlight().also { it.look("ASSOCIATING", ofTheDash = true, nowMs = 10_000L) }
        assertTrue(flight.inFlight(10_000L + AssociationInFlight.WINDOW_MS, networkUp = false))
        assertFalse("past the window", flight.inFlight(10_000L + AssociationInFlight.WINDOW_MS + 1, networkUp = false))
        assertEquals("about 1.5 s", 1_500L, AssociationInFlight.WINDOW_MS)
    }

    @Test
    fun `a join that goes on is held only for six seconds, then the request goes out as before`() {
        val flight = AssociationInFlight()
        val hold = HoldWhileJoining()
        var nowMs = 1_000L
        flight.look("ASSOCIATING", true, nowMs)
        assertEquals(DashJoin.LossRefile.HELD_FIRST, atTheSighting(flight, hold, nowMs))
        while (nowMs + DashJoin.WATCH_PERIOD_MS < 1_000L + DashJoin.HOLD_WHILE_JOINING_MS) {
            nowMs += DashJoin.WATCH_PERIOD_MS
            flight.look("FOUR_WAY_HANDSHAKE", true, nowMs) // Android keeps trying, each tick sees it
            assertEquals("at $nowMs", DashJoin.LossRefile.HELD, atTheSighting(flight, hold, nowMs))
        }
        nowMs = 1_000L + DashJoin.HOLD_WHILE_JOINING_MS
        flight.look("FOUR_WAY_HANDSHAKE", true, nowMs)
        assertEquals("the limit of the hold is the old guard's", DashJoin.LossRefile.REFILE, atTheSighting(flight, hold, nowMs))
    }

    // ── The looks of the other three sightings of the log are not held ─────────────────────────────────────

    @Test
    fun `a supplicant disconnected or scanning does not hold, as in the cold start and the two voltas that joined at once`() {
        for (state in listOf("DISCONNECTED", "SCANNING", "INACTIVE", "DORMANT", "INTERFACE_DISABLED", "UNINITIALIZED", "INVALID", null)) {
            val flight = AssociationInFlight().also { it.look(state, ofTheDash = true, nowMs = 1_000L) }
            assertFalse("$state", flight.inFlight(1_000L, networkUp = false))
            assertEquals(state, DashJoin.LossRefile.REFILE, atTheSighting(flight, HoldWhileJoining(), nowMs = 1_000L))
        }
        assertEquals("no look at all", DashJoin.LossRefile.REFILE, atTheSighting(AssociationInFlight(), HoldWhileJoining(), nowMs = 1_000L))
    }

    @Test
    fun `the phone joined to another network, or joining it, does not hold`() {
        val flight = AssociationInFlight()
        flight.look("COMPLETED", ofTheDash = false, nowMs = 1_000L) // connected to the phone's own saved Wi-Fi
        flight.look("ASSOCIATING", ofTheDash = false, nowMs = 1_500L) // or joining it
        assertFalse(flight.inFlight(1_500L, networkUp = false))
        assertEquals(DashJoin.LossRefile.REFILE, atTheSighting(flight, HoldWhileJoining(), nowMs = 1_500L))
    }

    @Test
    fun `a disconnection after the join was seen does not erase it within the window, a retry of Android between two states is the same join`() {
        val flight = AssociationInFlight()
        flight.look("ASSOCIATING", true, 54_940L)
        flight.look("DISCONNECTED", true, 55_100L)
        assertTrue(flight.inFlight(55_600L, networkUp = false))
        assertFalse("but not for ever", flight.inFlight(54_940L + AssociationInFlight.WINDOW_MS + 1, networkUp = false))
    }

    @Test
    fun `with the dash's network already up the old guard has it, not this one`() {
        val flight = AssociationInFlight().also { it.look("COMPLETED", true, 1_000L) }
        assertFalse(flight.inFlight(1_000L, networkUp = true))
    }

    @Test
    fun `the hold starts over after a gap, so a join of the next sighting is held for its own six seconds`() {
        val flight = AssociationInFlight()
        val hold = HoldWhileJoining()
        flight.look("ASSOCIATING", true, 1_000L)
        assertEquals(DashJoin.LossRefile.HELD_FIRST, atTheSighting(flight, hold, 1_000L))
        assertEquals("nothing seen for more than the window: the request goes out", DashJoin.LossRefile.REFILE, atTheSighting(flight, hold, 5_000L))
        flight.look("ASSOCIATING", true, 20_000L)
        assertEquals(DashJoin.LossRefile.HELD_FIRST, atTheSighting(flight, hold, 20_000L))
    }

    // ── Which network the supplicant is joining ──────────────────────────────────────────────────────────────

    private fun theDash(ssid: String?, bssid: String? = null, prefix: Boolean = false, known: Set<String> = emptySet(), pending: String = dash) =
        AssociationInFlight.ofTheDash(ssid, bssid, pending, prefix) { known }

    @Test
    fun `the name decides, quoted or not, exact or by its beginning`() {
        assertTrue(theDash(dash))
        assertTrue(theDash("\"$dash\""))
        assertFalse(theDash("HomeNet"))
        assertFalse(theDash("\"HomeNet\""))
        assertTrue(theDash("RE_DASH_0001", prefix = true, pending = "RE_DASH"))
        assertFalse(theDash("HomeNet", prefix = true, pending = "RE_DASH"))
    }

    @Test
    fun `with the name hidden the address is looked up among the dash's in the scan, and a readable name that is not the dash's is not saved by an address`() {
        val known = setOf("aa:bb:cc:00:11:22")
        assertTrue(theDash("<unknown ssid>", "AA:BB:CC:00:11:22", known = known))
        assertTrue(theDash(null, "aa:bb:cc:00:11:22", known = known))
        assertFalse(theDash("<unknown ssid>", "aa:bb:cc:99:99:99", known = known))
        assertFalse("the address Android hides", theDash("<unknown ssid>", "02:00:00:00:00:00", known = setOf("02:00:00:00:00:00")))
        assertFalse("nothing readable", theDash(null, null, known = known))
        assertFalse(theDash("HomeNet", "aa:bb:cc:00:11:22", known = known))
    }

    @Test
    fun `the scan is asked for addresses only when the name does not decide`() {
        var asked = 0
        AssociationInFlight.ofTheDash(dash, "aa:bb:cc:00:11:22", dash, false) { asked++; emptySet() }
        AssociationInFlight.ofTheDash("HomeNet", "aa:bb:cc:00:11:22", dash, false) { asked++; emptySet() }
        assertEquals(0, asked)
        AssociationInFlight.ofTheDash(null, "aa:bb:cc:00:11:22", dash, false) { asked++; emptySet() }
        assertEquals(1, asked)
    }

    @Test
    fun `without a name to look for nothing is the dash`() {
        assertFalse(theDash("", pending = ""))
        assertFalse(AssociationInFlight.ofTheDash("HomeNet", null, "", true) { emptySet() })
    }

    // ── The look at the scan every 0.5 s (A3) ──────────────────────────────────────────────────────────────────

    @Test
    fun `the watches look at the list every half second, from every 2 s`() {
        assertEquals(500L, DashJoin.WATCH_PERIOD_MS)
        assertEquals("the watches of the start look as often", DashJoin.WATCH_PERIOD_MS, com.reau.app.service.WifiDashLinkUp.SIGHTING_POLL_MS)
    }

    @Test
    fun `a faster look does not make a faster loop of requests, a refiling waits its 4 s after the last`() {
        // The dash is in the scan and refuses: each look, every 0.5 s, finds the same sighting right after a refiling at 0.
        fun refile(sinceLastMs: Long?) =
            DashJoin.refileAfterLoss(false, 0L, 100 * sec, 101 * sec, requestAgeMs = 3_000L, inPause = false, sinceLastRefileMs = sinceLastMs)
        assertTrue("the first of a loss", refile(null))
        for (since in DashJoin.WATCH_PERIOD_MS until DashJoin.REFILE_MIN_GAP_MS step DashJoin.WATCH_PERIOD_MS) assertFalse("$since ms", refile(since))
        assertTrue(refile(DashJoin.REFILE_MIN_GAP_MS))
    }

    @Test
    fun `the pause is cut once, at the first look past the minimum gap`() {
        val cuts = (1..8).map { it * DashJoin.WATCH_PERIOD_MS }.filter { waited ->
            DashJoin.cutsPauseOnSighting(false, seenWhenFailed = false, seenNow = true, sinceFailureMs = waited, sinceLastCutMs = null)
        }
        assertEquals("from the 2 s of the minimum gap on", 2_000L, cuts.first())
        assertFalse("the looks before it do not", 1_500L in cuts)
        assertEquals("the pause between two requests is the machine's own, unchanged", 4_000L, ReconnectPace.pauseMs(0))
    }
}
