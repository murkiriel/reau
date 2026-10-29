package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The waits and counts that decide when the dash's link is called lost or stuck, checked by what the session decides with its own
 * defaults ([DashSession.Timing]) rather than by the constants' values. Each was chosen from the ride logs (the dash's heartbeat every
 * second and its longest silences, the 25 s a dash takes to accept the greeting, the 15 s without a decode ack); the numbers are written
 * out here, so a change of one is a decision made here too, with the log that justifies it. (27/10/2026 review: the earlier version only
 * compared the constants with themselves.) The count of packets that cannot be handled is DashSessionRxFailureTest's.
 */
class TimingConstantsTest {
    private val defaults = DashSession.Timing()
    private val streaming = DashState.STREAMING

    @Test
    fun `with the session's own watchdog, four seconds of silence are not yet a lost dash, a moment more is`() {
        val last = 10_000L
        assertEquals(false, DashSession.rxSilenceFails(streaming, last, last + 4_000, defaults.rxWatchdogMs, heartbeatsSinceRx = 5))
        assertEquals(true, DashSession.rxSilenceFails(streaming, last, last + 4_001, defaults.rxWatchdogMs, heartbeatsSinceRx = 5))
    }

    @Test
    fun `a silence counts against the dash only after four heartbeats sent`() {
        val last = 10_000L
        assertEquals("three: the phone may have been paused", false, DashSession.rxSilenceFails(streaming, last, last + 9_000, defaults.rxWatchdogMs, heartbeatsSinceRx = 3))
        assertEquals(true, DashSession.rxSilenceFails(streaming, last, last + 9_000, defaults.rxWatchdogMs, heartbeatsSinceRx = 4))
    }

    @Test
    fun `the watchdogs look once a second`() = assertEquals(1_000L, defaults.watchdogTickMs)

    @Test
    fun `a dash that answers but never confirms a picture is given fifteen seconds before it is looked at again`() {
        val start = 50_000L
        fun at(nowMs: Long) = DashSession.firstAckTimedOut(false, start, nowMs, DashSession.FIRST_ACK_TIMEOUT_MS)
        assertEquals(false, at(start + 14_999))
        assertEquals(true, at(start + 15_001))
    }

    @Test
    fun `the greeting waits three seconds for the dash's key before it is sent again`() {
        assertEquals(3_000L, defaults.keyPatienceMs)
        assertEquals(false, DashSession.burstDue(2_999, keySeen = false, resends = 0))
        assertEquals(true, DashSession.burstDue(3_000, keySeen = false, resends = 0))
    }

    @Test
    fun `a dash that is not ready yet is greeted again every two seconds, up to fourteen times`() {
        assertEquals(false, DashReadiness.resendDue(1_999, keySeen = false, resends = 0))
        assertEquals(true, DashReadiness.resendDue(2_000, keySeen = false, resends = 13))
        assertEquals(false, DashReadiness.resendDue(2_000, keySeen = false, resends = 14))
    }
}
