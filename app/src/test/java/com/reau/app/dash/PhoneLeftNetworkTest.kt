package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ride log of 24/11/2026, 07:34:15 (seconds from the minute): the phone left another saved network at 15.004 and the supplicant was DISCONNECTED at
 * 15.304; the watch looked at the scan at 15.321, saw the dash and put the request out again at 15.354, and Android started ASSOCIATING with it at
 * 15.381: the attempt was dropped by the new request (0.7 s lost; 4 to 4.7 s on 23/11 at 20:40:46 and 21:15:49). The re-request waits a second after
 * the phone left a network, the look 0.5 s later decides again, and the 6 s of the hold over a join still end it. The clock is a counter.
 */
class PhoneLeftNetworkTest {
    private var now = 15_004L
    private val left = PhoneLeftNetwork { now }
    private val sec = 1_000_000L

    // ── The stamp ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `before the phone has left any network nothing is held`() {
        assertNull(left.sinceMs())
        assertFalse(PhoneLeftNetwork.holds(left.sinceMs()))
    }

    @Test
    fun `the stamp says how long ago the phone left a network, and a later one replaces it`() {
        left.left()
        now += 350
        assertEquals(350L, left.sinceMs())
        left.left()
        now += 20
        assertEquals(20L, left.sinceMs())
    }

    @Test
    fun `a clock that went back is no negative age`() {
        left.left()
        now -= 5
        assertEquals(0L, left.sinceMs())
    }

    @Test
    fun `the request is held at 200 ms, and let go at 1000 and 1500 ms`() {
        left.left()
        now += 200
        assertTrue("200 ms", PhoneLeftNetwork.holds(left.sinceMs()))
        now += 799
        assertTrue("999 ms", PhoneLeftNetwork.holds(left.sinceMs()))
        now += 1
        assertFalse("1000 ms", PhoneLeftNetwork.holds(left.sinceMs()))
        now += 500
        assertFalse("1500 ms", PhoneLeftNetwork.holds(left.sinceMs()))
        assertEquals("a second", 1_000L, PhoneLeftNetwork.HOLD_MS)
    }

    @Test
    fun `the line says how long ago, and names no network`() {
        assertEquals("holding the re-request: the phone left another network 350 ms ago", PhoneLeftNetwork.line(350))
    }

    // ── The hold, with the 6 s of the join's ────────────────────────────────────────────────────────────

    @Test
    fun `the hold over a join holds for a network just left, and says it is for that`() {
        val hold = HoldWhileJoining()
        val first = hold.holds(joiningNow = false, nowMs = 15_321L, phoneLeftMs = 317L)
        assertTrue(first.holds)
        assertTrue(first.began)
        assertTrue("held for the network left, not for a join", first.byLeaving)
        val next = hold.holds(joiningNow = false, nowMs = 15_821L, phoneLeftMs = 817L)
        assertTrue("the look 0.5 s later holds again", next.holds)
        assertFalse(next.began)
        val third = hold.holds(joiningNow = false, nowMs = 16_321L, phoneLeftMs = 1_317L)
        assertFalse("a second after, the request goes out", third.holds)
    }

    @Test
    fun `with no network left, or none known, the hold is as it was`() {
        for (leftMs in listOf(null, 1_000L, 5_000L)) {
            val verdict = HoldWhileJoining().holds(joiningNow = false, nowMs = 1_000L, phoneLeftMs = leftMs)
            assertFalse("$leftMs", verdict.holds)
            assertFalse(verdict.byLeaving)
        }
        assertTrue(HoldWhileJoining().holds(joiningNow = true, nowMs = 1_000L).holds)
        assertEquals("the default is none left", HoldWhileJoining().holds(true, 1_000L), HoldWhileJoining().holds(true, 1_000L, null))
    }

    @Test
    fun `a join under way is held for the join, even with a network just left`() {
        val verdict = HoldWhileJoining().holds(joiningNow = true, nowMs = 1_000L, phoneLeftMs = 100L)
        assertTrue(verdict.holds)
        assertFalse("the join is the reason", verdict.byLeaving)
    }

    @Test
    fun `the waits of the two reasons together do not pass the six seconds of the hold`() {
        val hold = HoldWhileJoining()
        // A join held 5 s, then the supplicant lets go of it and a network is left: the same count goes on.
        assertTrue(hold.holds(joiningNow = true, nowMs = 10_000L).began)
        assertTrue(hold.holds(joiningNow = true, nowMs = 14_500L).holds)
        assertTrue("5.5 s in, held for the network left", hold.holds(joiningNow = false, nowMs = 15_500L, phoneLeftMs = 300L).holds)
        assertFalse(
            "6 s in, the ceiling: the request goes out although the network was left 400 ms ago",
            hold.holds(joiningNow = false, nowMs = 10_000L + DashJoin.HOLD_WHILE_JOINING_MS, phoneLeftMs = 400L).holds,
        )
    }

    @Test
    fun `a stamp that keeps coming cannot hold longer than six seconds`() {
        val hold = HoldWhileJoining()
        var nowMs = 1_000L
        var held = 0L
        while (hold.holds(joiningNow = false, nowMs = nowMs, phoneLeftMs = 100L).holds) {
            nowMs += 500L
            held += 500L
            assertTrue("a loop", held <= DashJoin.HOLD_WHILE_JOINING_MS)
        }
        assertEquals(DashJoin.HOLD_WHILE_JOINING_MS, held)
    }

    @Test
    fun `when nothing holds, the count starts over`() {
        val hold = HoldWhileJoining()
        assertTrue(hold.holds(false, 1_000L, 100L).began)
        assertFalse(hold.holds(false, 2_000L, 1_100L).holds)
        assertTrue("a new network left begins a new hold", hold.holds(false, 9_000L, 100L).began)
    }

    // ── The watch over a loss, with the numbers of 24/11 ────────────────────────────────────────────────

    /** The watch over a loss at [nowMs]: the loss at 0, the dash's result stamped at 15 s, the look at 15.321 s with the request out for 2.1 s (the log's numbers). */
    private fun atTheLook(hold: HoldWhileJoining, nowMs: Long, leftMs: Long?, joining: Boolean = false) = DashJoin.refileAfterLossHeld(
        firstApproval = false, lostAtUs = 0L, sightingStampUs = 15 * sec, nowUs = 15_321_000L, requestAgeMs = 2_100L, inPause = false,
        sinceLastRefileMs = null, hold = hold, nowMs = nowMs, joiningNow = { joining }, phoneLeftMs = { leftMs },
    )

    @Test
    fun `the look of 24-11 at 15,321 holds because the phone left a network 317 ms before, and the next one 0,5 s later still does`() {
        val hold = HoldWhileJoining()
        assertEquals(DashJoin.LossRefile.HELD_FIRST_LEAVING, atTheLook(hold, nowMs = 15_321L, leftMs = 317L))
        assertEquals(DashJoin.LossRefile.HELD, atTheLook(hold, nowMs = 15_821L, leftMs = 817L))
        assertEquals("1,017 s after the network was left", DashJoin.LossRefile.REFILE, atTheLook(hold, nowMs = 16_321L, leftMs = 1_317L))
    }

    @Test
    fun `without a network just left the look of the log asks again at once, as before`() {
        assertEquals(DashJoin.LossRefile.REFILE, atTheLook(HoldWhileJoining(), nowMs = 15_321L, leftMs = null))
        assertEquals(DashJoin.LossRefile.REFILE, atTheLook(HoldWhileJoining(), nowMs = 15_321L, leftMs = 1_000L))
    }

    @Test
    fun `the first approval of a network is never asked again, with a network just left or not`() {
        val decision = DashJoin.refileAfterLossHeld(
            firstApproval = true, lostAtUs = 0L, sightingStampUs = 15 * sec, nowUs = 15_321_000L, requestAgeMs = 2_100L, inPause = false,
            sinceLastRefileMs = null, hold = HoldWhileJoining(), nowMs = 15_321L, joiningNow = { false }, phoneLeftMs = { 100L },
        )
        assertEquals("the first approval is never asked again", DashJoin.LossRefile.NO, decision)
    }

    @Test
    fun `a join under way keeps its own first line, with a network just left too`() {
        assertEquals(DashJoin.LossRefile.HELD_FIRST, atTheLook(HoldWhileJoining(), nowMs = 15_321L, leftMs = 100L, joining = true))
    }

    // ── The wiring, read from the source (the Wi-Fi manager needs Android) ──────────────────────────────

    private val dir = "src/main/java/com/reau/app/"

    @Test
    fun `the system events stamp the stamp at a network lost and at the supplicant's DISCONNECTED`() {
        val events = File(dir + "service/SystemEvents.kt").readText()
        assertTrue(events.substringAfter("override fun onLost(network: Network) {").substringBefore("override fun onUnavailable").contains("PhoneLeftNetwork.shared.left()"))
        assertTrue(events.contains("if (WifiEvents.leavesANetwork(state?.name)) PhoneLeftNetwork.shared.left()"))
        assertTrue(com.reau.app.service.WifiEvents.leavesANetwork("DISCONNECTED"))
        for (other in listOf("ASSOCIATING", "COMPLETED", "SCANNING", "INACTIVE", null)) assertFalse("$other", com.reau.app.service.WifiEvents.leavesANetwork(other))
    }

    @Test
    fun `both watches read the stamp before they let a request go, and say the hold`() {
        val wifi = File(dir + "dash/DashWifiManager.kt").readText()
        val probe = File(dir + "dash/AssociationProbe.kt").readText()
        // The watch over a loss: the stamp is read once, passed to the decision, and the first hold is said.
        val loss = wifi.substringAfter("private fun refileForSighting(").substringBefore("// ── A hung dash")
        assertTrue(loss.contains("PhoneLeftNetwork.shared.sinceMs()"))
        assertTrue(loss.contains("phoneLeftMs = { leftMs }"))
        assertTrue(loss.contains("DashJoin.LossRefile.HELD_FIRST_LEAVING -> com.reau.app.data.RideDiagnostics.log(\"wifi\", PhoneLeftNetwork.line(leftMs ?: 0L))"))
        // The watch over a request holds through JoinHold, which reads the same stamp, before it lets the request go.
        val request = wifi.substringAfter("private fun watchForTheDash(").substringBefore("private fun stillRequesting(")
        assertTrue(request.indexOf("if (hold.holds(request)) continue") in 0 until request.indexOf("requestNetwork()"))
        val hold = probe.substringAfter("internal class JoinHold(")
        assertTrue(hold.contains("PhoneLeftNetwork.shared.sinceMs()"))
        assertTrue(hold.contains("clock.holds(up || probe.joining(up, now), now, left)"))
        assertTrue(hold.contains("if (verdict.byLeaving) PhoneLeftNetwork.line(left ?: 0L)"))
    }
}
