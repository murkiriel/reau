package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/** The one probe of the dash's IP stack after a silence: what each way a TCP connect can end says about the dash. */
class PanelProbeTest {
    @Test
    fun `a connect that works says the port is open and the stack alive`() {
        assertEquals(PanelProbe.Verdict.OPEN, PanelProbe.classify(null))
    }

    @Test
    fun `a reset is a stack that answers, Android's own words and the plain ones`() {
        assertEquals(PanelProbe.Verdict.REFUSED, PanelProbe.classify(ConnectException("failed to connect to /192.168.1.1 (port 2001) from /192.168.1.7 (port 40000) after 3ms: isConnected failed: ECONNREFUSED (Connection refused)")))
        assertEquals(PanelProbe.Verdict.REFUSED, PanelProbe.classify(ConnectException("Connection refused")))
    }

    @Test
    fun `no answer in time is a timeout, whether Android words it or the JDK does`() {
        assertEquals(PanelProbe.Verdict.TIMEOUT, PanelProbe.classify(SocketTimeoutException("failed to connect to /192.168.1.1 (port 2001) from /192.168.1.7 (port 40000) after 1000ms")))
        assertEquals(PanelProbe.Verdict.TIMEOUT, PanelProbe.classify(ConnectException("connect failed: ETIMEDOUT (Connection timed out)")))
    }

    @Test
    fun `no route is told apart from a refusal and from a timeout`() {
        assertEquals(PanelProbe.Verdict.NO_ROUTE, PanelProbe.classify(ConnectException("connect failed: ENETUNREACH (Network is unreachable)")))
        assertEquals(PanelProbe.Verdict.NO_ROUTE, PanelProbe.classify(ConnectException("connect failed: EHOSTUNREACH (No route to host)")))
        assertEquals(PanelProbe.Verdict.NO_ROUTE, PanelProbe.classify(NoRouteToHostException()))
    }

    @Test
    fun `anything else is an error that could not be tried`() {
        assertEquals(PanelProbe.Verdict.ERROR, PanelProbe.classify(IllegalStateException("socket closed")))
        assertEquals(PanelProbe.Verdict.ERROR, PanelProbe.classify(java.io.IOException()))
    }

    @Test
    fun `the attempt is timed, and a refusal or a timeout need no more words, the others carry the system's`() {
        var t = 0L
        val clock = { t }
        val refused = PanelProbe.attempt(clock) { t += 3_000_000L; throw ConnectException("ECONNREFUSED") }
        assertEquals(PanelProbe.Verdict.REFUSED, refused.verdict)
        assertEquals(3L, refused.tookMs)
        assertNull(refused.detail)
        val timeout = PanelProbe.attempt(clock) { t += 1_001_000_000L; throw SocketTimeoutException("after 1000ms") }
        assertEquals(PanelProbe.Verdict.TIMEOUT, timeout.verdict)
        assertEquals(1_001L, timeout.tookMs)
        assertNull(timeout.detail)
        val route = PanelProbe.attempt(clock) { throw ConnectException("ENETUNREACH (Network is unreachable)") }
        assertEquals("ConnectException: ENETUNREACH (Network is unreachable)", route.detail)
        val open = PanelProbe.attempt(clock) { t += 5_000_000L }
        assertEquals(PanelProbe.Verdict.OPEN, open.verdict)
        assertEquals(5L, open.tookMs)
    }

    @Test
    fun `the lines say how long the silence was and what it means`() {
        assertEquals(
            "panel probe after 4 s of silence (TCP 192.168.1.1:2001 through the dash's network, up to 1000 ms): " +
                "no answer: the dash's Wi-Fi or network stack looks dead, in 1001 ms",
            PanelProbe.line(PanelProbe.Result(PanelProbe.Verdict.TIMEOUT, 1_001, null), 4),
        )
        val refused = PanelProbe.line(PanelProbe.Result(PanelProbe.Verdict.REFUSED, 3, null), 5)
        assertTrue(refused, "refused: the dash's network stack answers" in refused && refused.endsWith("in 3 ms"))
        assertEquals("panel probe after 4 s of silence skipped: the phone's network to the dash is already gone", PanelProbe.skippedLine(4))
    }
}
