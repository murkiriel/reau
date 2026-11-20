package com.reau.app.dash

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The two signs of a session whose sockets are on a network that is gone ([NetworkSwitch], [RtpStall]): ride log of 14/11/2026, the dash's Wi-Fi back
 * as another Android network and 6 min 36 s of video sends failing while the dash went on talking.
 */
class StaleLinkTest {
    @Test
    fun `another network than the session's is a switch, and the same one is not`() {
        assertTrue(NetworkSwitch.changed(opened = 299, now = 300))
        assertFalse(NetworkSwitch.changed(opened = 299, now = 299))
    }

    @Test
    fun `with the session's network or the new one unknown there is no switch`() {
        assertFalse("a session not bound to a network (a test's)", NetworkSwitch.changed(opened = null, now = 300))
        assertFalse("the manager has no network", NetworkSwitch.changed(opened = 299, now = null))
        assertFalse(NetworkSwitch.changed(opened = null, now = null))
    }

    @Test
    fun `the line of a switch names both networks and what is done`() {
        assertEquals(
            "the dash's Wi-Fi is now network 300 and this session's sockets are bound to network 299, which is gone: " +
                "link lost, the session is redone on the new network",
            NetworkSwitch.line(299, 300),
        )
    }

    /** A stall with five failures in the first 200 ms, as the socket has them before it suppresses the channel. */
    private fun suppressedAt(startMs: Long): RtpStall = RtpStall().also { stall ->
        repeat(4) { stall.failed(startMs + it * 50L, suppressed = false) }
        stall.failed(startMs + 200, suppressed = true)
    }

    @Test
    fun `a probe that fails more than five seconds after the first failure is a stall, with how long it lasted`() {
        val stall = suppressedAt(1_000)
        assertNull("only 200 ms so far", stall.stalledMs())
        stall.failed(6_001, suppressed = true)
        assertEquals(5_001L, stall.stalledMs())
    }

    @Test
    fun `at five seconds exactly it is not a stall yet`() {
        val stall = suppressedAt(1_000)
        stall.failed(6_000, suppressed = true)
        assertNull(stall.stalledMs())
    }

    @Test
    fun `the time alone is no stall, it takes a send that failed past the limit`() {
        val stall = suppressedAt(1_000)
        stall.failed(4_000, suppressed = true)
        // Nothing was sent since: the link may be back already, only the next probe tells.
        assertNull(stall.stalledMs())
    }

    @Test
    fun `a failure that mends itself is no stall, and the next run of failures counts from its own start`() {
        val stall = suppressedAt(1_000)
        stall.failed(3_000, suppressed = true)
        stall.sent()
        assertNull(stall.stalledMs())
        stall.failed(7_000, suppressed = false)
        stall.failed(7_500, suppressed = true)
        assertNull("500 ms into the second run, not 6.5 s since the first", stall.stalledMs())
        stall.failed(12_001, suppressed = true)
        assertEquals(5_001L, stall.stalledMs())
    }

    @Test
    fun `a send that works ends a stall`() {
        val stall = suppressedAt(1_000)
        stall.failed(9_000, suppressed = true)
        assertEquals(8_000L, stall.stalledMs())
        stall.sent()
        assertNull(stall.stalledMs())
    }

    @Test
    fun `failures that never reach the suppression are no stall however long they last`() {
        val stall = RtpStall()
        stall.failed(1_000, suppressed = false)
        stall.failed(60_000, suppressed = false)
        assertNull(stall.stalledMs())
    }

    @Test
    fun `the limit is five seconds, above the silence watch`() {
        assertEquals(5_000L, RtpStall.LIMIT_MS)
        assertTrue(RtpStall.LIMIT_MS > DashSession.RX_WATCHDOG_MS)
    }

    @Test
    fun `only a session that streams is lost by a stall`() {
        assertTrue(RtpStall.fails(DashState.STREAMING, 5_001))
        assertFalse(RtpStall.fails(DashState.STREAMING, null))
        DashState.entries.filter { it != DashState.STREAMING }.forEach { assertFalse(it.name, RtpStall.fails(it, 5_001)) }
    }

    @Test
    fun `the line of a stall says how long and when the dash last spoke`() {
        assertEquals("RTP watchdog: every video send failed for 6.2 s (last dash packet 0.3 s ago) → link lost", RtpStall.line(6_200, 300))
        assertEquals("RTP watchdog: every video send failed for 5.0 s (last dash packet never) → link lost", RtpStall.line(5_001, null))
    }
}

/** The socket feeds [RtpStall] from its own video sends: the failures, the suppression and the send that works. */
class DashSocketRtpStallTest {
    private var socket: DashSocket? = null
    private val down = IOException("sendto failed: ENETUNREACH (Network is unreachable)")

    @After
    fun tearDown() {
        socket?.close()
    }

    /** A socket whose video goes to a loopback port nobody listens on: a real send works (UDP asks for no listener). */
    private fun open(): DashSocket {
        val loopback = InetSocketAddress(InetAddress.getLoopbackAddress(), 9)
        return DashSocket(ctrlDest = loopback, rtpDest = loopback).also { socket = it }
    }

    @Test
    fun `video sends failing for more than five seconds stall the socket`() {
        val s = open()
        repeat(5) { s.testOnlyRtpSendFailed(down, nowMs = 1_000L + it) }
        assertTrue(s.testOnlyRtpSuppressed())
        assertNull(s.rtpStall.stalledMs())
        s.testOnlyRtpSendFailed(down, nowMs = 6_001)
        assertEquals(5_001L, s.rtpStall.stalledMs())
    }

    @Test
    fun `four failures do not suppress the channel and are no stall`() {
        val s = open()
        repeat(4) { s.testOnlyRtpSendFailed(down, nowMs = 1_000L + it * 3_000L) }
        assertFalse(s.testOnlyRtpSuppressed())
        assertNull(s.rtpStall.stalledMs())
    }

    @Test
    fun `the probe that goes out ends the stall`() {
        val s = open()
        repeat(5) { s.testOnlyRtpSendFailed(down, nowMs = 1_000L + it) }
        s.testOnlyRtpSendFailed(down, nowMs = 9_000)
        assertEquals(8_000L, s.rtpStall.stalledMs())
        // Suppressed: 19 packets are held back and the 20th is the probe, which the loopback takes.
        repeat(20) { s.sendRtp(ByteArray(12)) }
        assertFalse(s.testOnlyRtpSuppressed())
        assertNull(s.rtpStall.stalledMs())
    }
}
