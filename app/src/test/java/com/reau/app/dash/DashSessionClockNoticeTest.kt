package com.reau.app.dash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ride logs of 20/10/2026: the dash's 09 06 55 came at +10 s of the burst with no video at all, so it is the dash's own 10 s clock,
 * not a decoded frame. It still says the dash is alive then: a stream that starts after it runs at the pace of one that is.
 */
class DashSessionClockNoticeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = DashSession(scope)
    private val sock = DashSocket()

    @After
    fun tearDown() {
        sock.close()
        session.disconnect()
        scope.cancel()
    }

    private fun notice(): ByteArray {
        val header = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
        return header + byteArrayOf(0x09, 0x06, 0x00, 0x01, 0x55)
    }

    @Test
    fun `a mark seen while authenticating makes the stream that follows start as one that is alive`() {
        session.stateForTest(DashState.AUTHENTICATING)
        session.dispatchIncoming(notice(), sock)
        assertFalse("not a decoded frame yet", session.firstFrameConfirmed)
        session.stateForTest(DashState.READY)
        session.startStreaming()
        assertEquals(DashState.STREAMING, session.state.value)
        assertTrue(session.firstFrameConfirmed)
    }

    @Test
    fun `a mark seen while ready counts too`() {
        session.stateForTest(DashState.READY)
        session.dispatchIncoming(notice(), sock)
        session.startStreaming()
        assertTrue(session.firstFrameConfirmed)
    }

    @Test
    fun `a stream with no mark before it waits for one, as before`() {
        session.stateForTest(DashState.READY)
        session.startStreaming()
        assertFalse(session.firstFrameConfirmed)
    }

    @Test
    fun `a mark seen while idle says nothing about the stream`() {
        session.dispatchIncoming(notice(), sock)
        session.stateForTest(DashState.READY)
        session.startStreaming()
        assertFalse(session.firstFrameConfirmed)
    }

    @Test
    fun `the silence watchdog arms as soon as the stream began and the dash had spoken`() {
        val limit = 4_000L
        assertTrue(DashSession.rxSilenceFails(DashState.STREAMING, lastRxMs = 1_000, nowMs = 5_001, limitMs = limit))
        assertFalse("the boundary", DashSession.rxSilenceFails(DashState.STREAMING, 1_000, 5_000, limit))
        assertFalse("a dash that never spoke is the first-ack watchdog's", DashSession.rxSilenceFails(DashState.STREAMING, 0, 99_000, limit))
        // the phone's own pause: the loop's first turn after it has sent one heartbeat since the dash spoke, not four
        assertFalse("a pause of the phone is not the dash's silence", DashSession.rxSilenceFails(DashState.STREAMING, 1_000, 99_000, limit, heartbeatsSinceRx = 1))
        assertFalse(DashSession.rxSilenceFails(DashState.STREAMING, 1_000, 99_000, limit, heartbeatsSinceRx = 3))
        assertTrue(DashSession.rxSilenceFails(DashState.STREAMING, 1_000, 99_000, limit, heartbeatsSinceRx = 4))
        for (s in DashState.entries - DashState.STREAMING) assertFalse(s.name, DashSession.rxSilenceFails(s, 1_000, 99_000, limit))
    }

    @Test
    fun `the log never calls the mark a decoded frame`() {
        val line = DashSession.clockNoticeLine(10_200)
        assertEquals("dash notice 09 06 55 at +10.2 s of the burst (the dash's own 10 s mark; not proof of a decoded frame)", line)
        assertFalse(line, "video accepted" in line || "DECODED" in line)
    }

    @Test
    fun `a mark seen in one session counts for the next one made on the same link`() {
        session.beginAuthState(null)
        session.stateForTest(DashState.AUTHENTICATING)
        session.dispatchIncoming(notice(), sock)
        // the greeting was ignored; the session is made again on the same link, and the mark does not come again
        session.beginAuthState(null)
        session.stateForTest(DashState.READY)
        session.startStreaming()
        assertTrue("it starts at the pace of a dash that is alive", session.firstFrameConfirmed)
    }

    @Test
    fun `a session on a link where no mark was seen waits for it as before`() {
        session.beginAuthState(null)
        session.stateForTest(DashState.READY)
        session.startStreaming()
        assertFalse(session.firstFrameConfirmed)
    }

    @Test
    fun `the mark belongs to the link and another link has not seen it`() {
        val notices = LinkNotice()
        val first = Any()
        val second = Any()
        assertFalse(notices.knownOn(first))
        notices.seenOn(first)
        assertTrue(notices.knownOn(first))
        assertFalse("a new Wi-Fi link is another object", notices.knownOn(second))
        notices.seenOn(second)
        assertFalse("and the old one is forgotten", notices.knownOn(first))
    }
}
