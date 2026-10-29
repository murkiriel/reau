package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ride logs of 19/10/2026: a first request made while the bike was waking up rode its whole 30 s although the dash was in the
 * scan for the last 8 s; the next one, 2 s later, joined in 1.3 s. So REau asks again when the dash shows up.
 */
class DashJoinRefileTest {
    private fun refile(
        firstApproval: Boolean = false,
        everConnected: Boolean = false,
        seenAtRequest: Boolean = false,
        seenNow: Boolean = true,
        waitedMs: Long = 10_000L,
    ) = DashJoin.refileOnSighting(firstApproval, everConnected, seenAtRequest, seenNow, waitedMs)

    @Test
    fun `a request made with no dash in the scan is put out again when the dash shows up`() {
        assertTrue(refile())
    }

    @Test
    fun `nothing happens while the dash is still not in the scan`() {
        assertFalse(refile(seenNow = false))
    }

    @Test
    fun `the first approval of a network is never put out again, the system's dialog would start over`() {
        assertFalse(refile(firstApproval = true))
    }

    @Test
    fun `a reconnection after a join is not touched, it has its short waits`() {
        assertFalse(refile(everConnected = true))
    }

    @Test
    fun `a dash that was already in the scan when the request went out is the system's to answer`() {
        assertFalse(refile(seenAtRequest = true))
    }

    @Test
    fun `the system gets a couple of seconds on its own first`() {
        assertFalse(refile(waitedMs = DashJoin.REFILE_MIN_WAIT_MS - 1))
        assertTrue(refile(waitedMs = DashJoin.REFILE_MIN_WAIT_MS))
        assertEquals(2_000L, DashJoin.REFILE_MIN_WAIT_MS)
    }
}

/**
 * Ride logs of 20/10/2026: after the dash's Wi-Fi was lost, REau joined it 10 s and 12 s after it had shown up in the scan, because
 * the 4 s pause and the 10 s request ran their course.
 */
class DashJoinRefileAfterLossTest {
    private val sec = 1_000_000L
    private val lostAt = 100 * sec
    private fun refile(
        firstApproval: Boolean = false,
        sighting: Long? = lostAt + 10 * sec,
        now: Long = lostAt + 12 * sec,
        requestAgeMs: Long = 8_000,
        inPause: Boolean = false,
        sinceLastRefileMs: Long? = null,
    ) = DashJoin.refileAfterLoss(firstApproval, lostAt, sighting, now, requestAgeMs, inPause, sinceLastRefileMs)

    @Test
    fun `the real case a loss at T, the dash seen at T+10, a request out since T+4 is refiled at T+12`() {
        assertTrue(refile())
    }

    @Test
    fun `a sighting older than the loss says nothing of the dash being back`() {
        assertFalse(refile(sighting = lostAt - 8 * sec))
        assertFalse(refile(sighting = lostAt))
    }

    @Test
    fun `a request has a couple of seconds on its own first`() {
        assertFalse(refile(sighting = lostAt + 11 * sec, now = lostAt + 11_500_000L, requestAgeMs = 1_999))
        assertTrue(refile(sighting = lostAt + 11 * sec, requestAgeMs = DashJoin.REFILE_MIN_WAIT_MS))
    }

    @Test
    fun `a dash the request already saw is the systems to answer, and one sighting does not refile every 4 seconds`() {
        // the request went out at T+10.5, after the sighting
        assertFalse(refile(sighting = lostAt + 10 * sec, now = lostAt + 13 * sec, requestAgeMs = 2_500))
    }

    @Test
    fun `two refilings are at least 4 seconds apart`() {
        assertFalse(refile(sinceLastRefileMs = DashJoin.REFILE_MIN_GAP_MS - 1))
        assertTrue(refile(sinceLastRefileMs = DashJoin.REFILE_MIN_GAP_MS))
    }

    @Test
    fun `the first approval of a network is never touched`() {
        assertFalse(refile(firstApproval = true))
        assertFalse(refile(firstApproval = true, inPause = true))
    }

    @Test
    fun `an empty or unreadable scan decides nothing`() {
        assertFalse(refile(sighting = null))
        assertFalse(refile(sighting = null, inPause = true))
    }

    @Test
    fun `in the pause a sighting newer than the loss and the latest request cuts the pause short`() {
        // the last request ended at T+1 (it was 10 s old at the loss, say); the dash is seen at T+2, the pause is at T+3
        assertTrue(refile(sighting = lostAt + 2 * sec, now = lostAt + 3 * sec, requestAgeMs = 3_000, inPause = true))
        assertFalse(refile(sighting = lostAt - sec, now = lostAt + 3 * sec, requestAgeMs = 3_000, inPause = true))
    }

    @Test
    fun `a dash in sight that refuses the join does not turn the pause into a request every 2 seconds`() {
        // the sighting is older than the request that was just refused: the pause runs its 4 s
        assertFalse(refile(sighting = lostAt + 2 * sec, now = lostAt + 7 * sec, requestAgeMs = 2_000, inPause = true))
        // and the gap between two refilings holds in the pause too
        assertFalse(refile(sighting = lostAt + 6 * sec, now = lostAt + 7 * sec, requestAgeMs = 3_000, inPause = true, sinceLastRefileMs = 3_999))
        assertTrue(refile(sighting = lostAt + 6 * sec, now = lostAt + 7 * sec, requestAgeMs = 3_000, inPause = true, sinceLastRefileMs = 4_000))
    }
}

/** Ride log of 20/10/2026, 13:29-13:31: the dash entered the scan at 13:30:38, and a request out since 13:30:24 left the whole 30 s; the next, 5.4 s. */
class DashJoinRefileSightedTest {
    private fun slow(first: Boolean = false, connected: Boolean = false, seenAtRequest: Boolean = true, waited: Long = 8_000, refiles: Int = 0) =
        DashJoin.refileSightedAndSlow(first, connected, seenAtRequest, waited, refiles)

    @Test
    fun `a request made with the dash in the scan is put out again after 8 s without a join, not before`() {
        assertFalse(slow(waited = 7_999))
        assertTrue(slow(waited = DashJoin.REFILE_SIGHTED_MS))
        assertEquals(8_000L, DashJoin.REFILE_SIGHTED_MS)
    }

    @Test
    fun `never for the first approval, after a join, or when the dash was not in the scan at the request`() {
        assertFalse(slow(first = true))
        assertFalse(slow(connected = true))
        assertFalse("that case has its own rule, the sighting", slow(seenAtRequest = false))
    }

    @Test
    fun `at most twice, so a dash that really refuses is not asked in a loop`() {
        assertTrue(slow(refiles = 0))
        assertTrue(slow(refiles = 1))
        assertFalse(slow(refiles = DashJoin.MAX_SIGHTED_REFILES))
    }

    @Test
    fun `the real case of 13-30 refiles 16 s after the request when the dash shows up at 14 s`() {
        // request out at 24.7 s with the dash not in the scan; seen at 38.7 s; the watch looks every 2 s: it refiles at 40.7 s, not at 54.8 s
        assertTrue(DashJoin.refileOnSighting(firstApproval = false, everConnected = false, seenAtRequest = false, seenNow = true, waitedMs = 16_000))
        assertFalse("and not while it is not in the scan", DashJoin.refileOnSighting(false, false, false, seenNow = false, waitedMs = 16_000))
    }

    @Test
    fun `a request whose network is already joined is held, letting it go would drop that network`() {
        assertTrue(DashJoin.holdWhileJoining(joiningNow = true, heldMs = 0L))
        assertTrue(DashJoin.holdWhileJoining(joiningNow = true, heldMs = DashJoin.HOLD_WHILE_JOINING_MS - 1))
    }

    @Test
    fun `the hold ends after six seconds, a joined network that never answers is the system's own`() {
        assertFalse(DashJoin.holdWhileJoining(joiningNow = true, heldMs = DashJoin.HOLD_WHILE_JOINING_MS))
        assertEquals(6_000L, DashJoin.HOLD_WHILE_JOINING_MS)
    }

    @Test
    fun `nothing is held while the dash's network is not joined`() {
        assertFalse(DashJoin.holdWhileJoining(joiningNow = false, heldMs = 0L))
    }
}
