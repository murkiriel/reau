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
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A real [DashSession] streaming to [FakeDashPeer] over the loopback, and the two ways it is lost with the dash still talking (ride log of
 * 14/11/2026): the dash's Wi-Fi back as another network ([NetworkSwitch]) and the video failing at every send ([RtpStall]). The network is a
 * number here: a JVM test has no Android `Network`, and the session only compares the two.
 */
class DashSessionStaleLinkTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private var fakeDash: FakeDashPeer? = null
    @Volatile private var socket: DashSocket? = null
    private val errors = CopyOnWriteArrayList<String>()
    private val down = IOException("sendto failed: ENETUNREACH (Network is unreachable)")
    private val logFile = java.nio.file.Files.createTempFile("stale-link", ".log").toFile()

    @After
    fun tearDown() {
        session?.disconnect()
        scope.cancel()
        fakeDash?.close()
        com.reau.app.data.RideDiagnostics.testOnlyReset()
        logFile.delete()
    }

    private fun await(what: String, timeoutMs: Long = 8_000, until: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (!until()) {
            assertTrue("timed out waiting for $what (state ${session?.state?.value}, errors $errors)", System.nanoTime() < end)
            Thread.sleep(10)
        }
    }

    /** A session streaming to the fake dash, the watches' loop at 50 ms and the video's safety net at 400 ms. */
    private fun streaming(): DashSession {
        com.reau.app.data.RideDiagnostics.testOnlySetFile(logFile)
        val fake = FakeDashPeer(clock = { System.nanoTime() / 1_000_000L }).also { fakeDash = it }
        val s = DashSession(
            scope,
            socketFactory = { DashSocket(null, ctrlDest = fake.ctrlDest, rtpDest = fake.rtpDest).also { sock -> socket = sock } },
            timing = DashSession.Timing(watchdogTickMs = 50, rtpStallMs = 400),
        ).also { session = it }
        s.onError = { errors += it }
        s.connect("DashFake")
        await("the session to be ready") { s.state.value == DashState.READY || s.state.value == DashState.ERROR }
        assertEquals(DashState.READY, s.state.value)
        s.startStreaming()
        fake.ackIdrFrame()
        await("the dash's ack") { s.firstFrameConfirmed }
        return s
    }

    private fun logged(): List<String> {
        com.reau.app.data.RideDiagnostics.logAndWait("end", "marker", timeoutMs = 10_000)
        return logFile.readLines()
    }

    /** The video failing at every send for [forMs], a send every 10 ms, while the dash goes on talking. */
    private fun videoFailsFor(forMs: Long, s: DashSession) {
        val end = System.nanoTime() + forMs * 1_000_000L
        while (System.nanoTime() < end && s.state.value == DashState.STREAMING) {
            socket!!.testOnlyRtpSendFailed(down)
            fakeDash!!.ackPFrame()
            Thread.sleep(10)
        }
    }

    @Test
    fun `the dash's Wi-Fi back as another network loses the session, with its line in the ride log`() {
        val s = streaming()
        assertTrue(s.networkNow(now = 300, opened = 299))
        assertEquals(DashState.ERROR, s.state.value)
        assertEquals(listOf(NetworkSwitch.FAILURE), errors.toList())
        val lines = logged()
        assertTrue(lines.toString(), lines.any { it.endsWith("[session] W: " + NetworkSwitch.line(299, 300)) })
        assertTrue(lines.toString(), lines.any { it.contains("session fail: " + NetworkSwitch.FAILURE) })
    }

    @Test
    fun `the same network again leaves the session as it is`() {
        val s = streaming()
        assertFalse(s.networkNow(now = 299, opened = 299))
        assertFalse("a session bound to no network", s.networkNow(now = 300))
        Thread.sleep(200)
        assertEquals(DashState.STREAMING, s.state.value)
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test
    fun `with no session open another network is nothing to lose`() {
        val s = DashSession(scope).also { session = it }
        assertFalse(s.networkNow(now = 300, opened = 299))
        assertEquals(DashState.IDLE, s.state.value)
    }

    @Test
    fun `after a session failed another network does not fail it again`() {
        val s = streaming()
        s.fail("Dash stopped responding — connection lost")
        assertFalse(s.networkNow(now = 300, opened = 299))
        assertEquals(listOf("Dash stopped responding — connection lost"), errors.toList())
    }

    @Test
    fun `the video failing past the limit loses the session with the dash still talking`() {
        val s = streaming()
        videoFailsFor(3_000, s)
        await("the session to be lost", timeoutMs = 2_000) { s.state.value == DashState.ERROR }
        assertEquals(listOf(RtpStall.FAILURE), errors.toList())
        val lines = logged()
        assertTrue(lines.toString(), lines.any { it.contains("[error] E: RTP watchdog: every video send failed for ") && it.endsWith("→ link lost") })
    }

    @Test
    fun `a short failure of the video that mends itself leaves the session streaming`() {
        val s = streaming()
        videoFailsFor(200, s) // suppressed (more than five failures), but well inside the 400 ms
        assertTrue(socket!!.testOnlyRtpSuppressed())
        repeat(20) { s.sendRtp(ByteArray(12)) } // the 20th is the probe, and the loopback takes it
        assertFalse(socket!!.testOnlyRtpSuppressed())
        Thread.sleep(800) // twice the limit, sixteen turns of the watch
        assertEquals(DashState.STREAMING, s.state.value)
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test
    fun `a video that fails only now and then is never a stall`() {
        val s = streaming()
        val end = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < end) {
            repeat(3) { socket!!.testOnlyRtpSendFailed(down) }
            s.sendRtp(ByteArray(12))
            Thread.sleep(20)
        }
        assertEquals(DashState.STREAMING, s.state.value)
        assertTrue(errors.toString(), errors.isEmpty())
    }
}
