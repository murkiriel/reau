package com.reau.app.dash

import com.reau.app.dash.JoinRules.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dash's Wi-Fi can stop answering mid-ride and, for minutes, stay on the air on the same channel while refusing
 * every join (deauth reason 15, the four-way handshake), with the factory password that had just worked. Android gives
 * up on each request after 5-12 s of a 30 s wait, and a "rede não encontrada ou senha errada" each time would be wrong:
 * only a bike power-off clears a dash in that state.
 *
 * And the same drop asked for the network twice: the request the link loss scheduled 4 s ahead went out after the
 * projection's own, 1.4 s later, and cancelled it half-way.
 */
class DashStuckTest {
    private val timeout = 30_000

    @Test
    fun `a dash that refuses the join after it worked in this projection is a stuck dash, the first time`() {
        // 5723 ms after the request, scan unreadable in the background.
        assertEquals(Refusal.DASH_STUCK, JoinRules.refusal(seenInScan = null, tookMs = 5_723, timeoutMs = timeout, joinedBefore = true, earlyInARow = 1))
        assertEquals(Refusal.DASH_STUCK, JoinRules.refusal(seenInScan = true, tookMs = 9_550, timeoutMs = timeout, joinedBefore = true, earlyInARow = 1))
    }

    @Test
    fun `with no join yet, three quick refusals in a row are a stuck dash, fewer are not`() {
        // After REau was restarted it had no join of its own to go by.
        assertEquals(Refusal.UNKNOWN, JoinRules.refusal(seenInScan = null, tookMs = 9_550, timeoutMs = timeout, joinedBefore = false, earlyInARow = 1))
        assertEquals(Refusal.SEEN_NOT_JOINED, JoinRules.refusal(seenInScan = true, tookMs = 10_407, timeoutMs = timeout, joinedBefore = false, earlyInARow = 2))
        assertEquals(Refusal.DASH_STUCK, JoinRules.refusal(seenInScan = true, tookMs = 6_000, timeoutMs = timeout, joinedBefore = false, earlyInARow = 3))
    }

    @Test
    fun `a dash missing from the scan is not there, whatever came before, and the log says whether it had been there`() {
        // The bike turned off: no access point at all, nothing to power-cycle.
        assertEquals(Refusal.GONE_AFTER_JOIN, JoinRules.refusal(seenInScan = false, tookMs = 5_000, timeoutMs = timeout, joinedBefore = true, earlyInARow = 5))
        // Never joined in this projection: it is just not found.
        assertEquals(Refusal.NOT_FOUND, JoinRules.refusal(seenInScan = false, tookMs = 30_000, timeoutMs = timeout, joinedBefore = false, earlyInARow = 0))
        assertEquals(Refusal.NOT_FOUND, JoinRules.refusal(seenInScan = false, tookMs = 5_000, timeoutMs = timeout, joinedBefore = false, earlyInARow = 5))
    }

    @Test
    fun `the rider reads the same words for a dash that went away as for one never found`() {
        assertEquals(Refusal.NOT_FOUND.message("RE_AB12_345678"), Refusal.GONE_AFTER_JOIN.message("RE_AB12_345678"))
    }

    @Test
    fun `a request that ran out the clock was not refused by the dash`() {
        // No answer at all (out of range, the network picker left open): the old messages stand.
        assertEquals(Refusal.UNKNOWN, JoinRules.refusal(seenInScan = null, tookMs = 30_000, timeoutMs = timeout, joinedBefore = true, earlyInARow = 0))
        assertEquals(Refusal.SEEN_NOT_JOINED, JoinRules.refusal(seenInScan = true, tookMs = 15_000, timeoutMs = timeout, joinedBefore = true, earlyInARow = 0))
        assertEquals("just under half the wait is quick", Refusal.DASH_STUCK,
            JoinRules.refusal(seenInScan = true, tookMs = 14_999, timeoutMs = timeout, joinedBefore = true, earlyInARow = 1))
    }

    @Test
    fun `quick refusals are counted in a row and a slow one starts over`() {
        assertEquals(1, JoinRules.earlyInARow(previous = 0, tookMs = 5_723, timeoutMs = timeout))
        assertEquals(3, JoinRules.earlyInARow(previous = 2, tookMs = 6_003, timeoutMs = timeout))
        assertEquals(0, JoinRules.earlyInARow(previous = 2, tookMs = 30_000, timeoutMs = timeout))
    }

    @Test
    fun `the reconnect a link loss scheduled does not go out after a newer request`() {
        // Scheduled as request 7; the projection's request 8 went out 2 s later.
        assertFalse(JoinRules.reconnectDue(wantConnected = true, scheduledAt = 7, latestRequest = 8))
        assertTrue(JoinRules.reconnectDue(wantConnected = true, scheduledAt = 7, latestRequest = 7))
        assertFalse(JoinRules.reconnectDue(wantConnected = false, scheduledAt = 7, latestRequest = 7))
    }

    // ── The phone already has the dash's network up when Android lets the request go (19/11/2026, 01:26) ──

    private val adopt = JoinRules.Unavailable.AdoptExisting

    private fun unavailable(
        everConnected: Boolean = false,
        wantConnected: Boolean = true,
        up: Boolean,
        seen: Boolean? = true,
        tookMs: Long = 1_798,
        joinedBefore: Boolean = true,
        earlyInARow: Int = 1,
    ) = JoinRules.onUnavailable(everConnected, wantConnected, { up }, { seen }, tookMs, timeout, joinedBefore, earlyInARow)

    @Test
    fun `a quick refusal with the dash's network already up is adopted, not called a stuck dash`() {
        assertEquals(adopt, unavailable(up = true))
        // The same refusal with no network up is the stuck dash it was taken for, as before.
        assertEquals(JoinRules.Unavailable.Refused(Refusal.DASH_STUCK), unavailable(up = false))
    }

    @Test
    fun `a slow refusal with the dash's network already up is adopted too`() {
        assertEquals(adopt, unavailable(up = true, tookMs = 29_000))
        assertEquals(JoinRules.Unavailable.Refused(Refusal.SEEN_NOT_JOINED), unavailable(up = false, tookMs = 29_000))
    }

    @Test
    fun `with no network up the verdicts are the ones they always were`() {
        assertEquals(JoinRules.Unavailable.Refused(Refusal.NOT_FOUND), unavailable(up = false, seen = false, joinedBefore = false))
        assertEquals(JoinRules.Unavailable.Refused(Refusal.GONE_AFTER_JOIN), unavailable(up = false, seen = false, joinedBefore = true))
        assertEquals(JoinRules.Unavailable.Refused(Refusal.UNKNOWN), unavailable(up = false, seen = null, tookMs = 29_000, joinedBefore = false))
        assertEquals(JoinRules.Unavailable.Refused(Refusal.DASH_STUCK), unavailable(up = false, joinedBefore = false, earlyInARow = 3))
    }

    @Test
    fun `a network that is up is adopted even when the scan does not list the dash`() {
        assertEquals(adopt, unavailable(up = true, seen = false, joinedBefore = false))
    }

    @Test
    fun `in a reconnection nothing is adopted here, the request is made again as always`() {
        assertEquals(JoinRules.Unavailable.RetryMidSession, unavailable(everConnected = true, up = true))
        assertEquals(JoinRules.Unavailable.RetryMidSession, unavailable(everConnected = true, up = false))
    }

    @Test
    fun `the network and the scan are asked only when the answer needs them`() {
        var asked = ""
        JoinRules.onUnavailable(true, true, { asked += "n"; true }, { asked += "s"; true }, 100, timeout, true, 1)
        assertEquals("a reconnection asks neither", "", asked)
        JoinRules.onUnavailable(false, true, { asked += "n"; true }, { asked += "s"; true }, 100, timeout, true, 1)
        assertEquals("a network that is up needs no scan", "n", asked)
        asked = ""
        JoinRules.onUnavailable(false, true, { asked += "n"; false }, { asked += "s"; true }, 100, timeout, true, 1)
        assertEquals("ns", asked)
    }

    /**
     * Text of the source: what the callback does with each answer. The adoption is one step under the request lock that lets the request that
     * ended go for good, takes the network by the path of every network the phone joined by itself, says so in the log, and leaves the
     * refusal as it was when nothing could be taken.
     */
    @Test
    fun `the callback adopts the network under the lock, lets the request go and leaves the refusal when it cannot`() {
        val manager = java.io.File("src/main/java/com/reau/app/dash/DashWifiManager.kt").readText()
        val callback = manager.substringAfter("override fun onUnavailable()").substringBefore("override fun onLosing(")
        assertTrue(callback, "if (outcome == JoinRules.Unavailable.AdoptExisting && adoptTheNetworkThePhoneJoined(this, tookMs, early)) return" in callback)
        assertTrue(callback, "dashNetworkUp = { findAlreadyConnectedDashNetwork() != null }" in callback)
        assertTrue("the refusal stays for an adoption that did not hold", "?: refusal(seenInScan, tookMs, requestTimeoutMs, joinedOnce, quickRefusals)" in callback)
        val adoption = manager.substringAfter("private fun adoptTheNetworkThePhoneJoined(").substringBefore("private fun askForTheNetwork")
        assertTrue(adoption, "synchronized(requestLock)" in adoption)
        assertTrue("a stale request or a switch-off adopts nothing", "if (!wantConnected || networkCallback !== dead) return false" in adoption)
        assertTrue("the request that ended is let go", "cm.unregisterNetworkCallback(dead)" in adoption)
        assertTrue(adoption.indexOf("networkCallback = null") in 0 until adoption.indexOf("useAlreadyConnectedNetwork()"))
        assertTrue(adoption, "dash network already up (joined by the phone itself): adopting it instead of a refusal" in adoption)
        assertTrue("the same path as any network the phone joined by itself", "val taken = useAlreadyConnectedNetwork()" in adoption)
    }
}
