package com.reau.app.dash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * With REau's own call card on ([DashSession.callCardOn]) the dash is told nothing of the call: no state (05 21) and no name (05 22) in the cards' round, "no call" in the
 * 1 Hz status, and no "no call" clear when nothing was sent; turned on in the middle of a call it sends the one clear, turned off it sends the call again. A real
 * [DashSession] streaming to [FakeDashPeer] over the loopback, as DashSessionStaleLinkTest does; what is checked is what goes on the wire, and the ride log's lines.
 */
class DashSessionCallCardTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private var fakeDash: FakeDashPeer? = null
    private val logFile = java.nio.file.Files.createTempFile("call-card", ".log").toFile()

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
            assertTrue("timed out waiting for $what (state ${session?.state?.value})", System.nanoTime() < end)
            Thread.sleep(10)
        }
    }

    /** A session streaming to the fake dash, which talks as a real one does so the rounds go on. */
    private fun streaming(callCard: Boolean): Pair<DashSession, FakeDashPeer> {
        com.reau.app.data.RideDiagnostics.testOnlySetFile(logFile)
        val fake = FakeDashPeer(clock = { System.nanoTime() / 1_000_000L }).also { fakeDash = it }
        val s = DashSession(scope, socketFactory = { DashSocket(null, ctrlDest = fake.ctrlDest, rtpDest = fake.rtpDest) }).also { session = it }
        s.callCardOn = callCard
        s.connect("DashFake")
        await("the session to be ready") { s.state.value == DashState.READY || s.state.value == DashState.ERROR }
        assertEquals(DashState.READY, s.state.value)
        s.startStreaming()
        fake.ackIdrFrame()
        await("the dash's ack") { s.firstFrameConfirmed }
        scope.launch {
            while (isActive) {
                fake.ackPFrame()
                delay(300)
            }
        }
        return s to fake
    }

    private fun states(fake: FakeDashPeer) = fake.received.filter { it.type == 0x05 && it.sub == 0x21 }.map { it.value.single().toInt() and 0xFF }

    private fun logged(): List<String> {
        com.reau.app.data.RideDiagnostics.logAndWait("end", "marker", timeoutMs = 10_000)
        return logFile.readLines()
    }

    @Test
    fun `without the card a ringing call goes out as before, the state and the name`() {
        val (s, fake) = streaming(callCard = false)
        s.showCaller("Ana")
        await("the state and the name") { fake.countOf(0x05, 0x22) >= 1 }
        assertTrue(states(fake).toString(), 0x0A in states(fake))
    }

    @Test
    fun `with the card on a ringing call and a call going on send the dash neither the state nor the name`() {
        val (s, fake) = streaming(callCard = true)
        s.showCaller("Ana")
        Thread.sleep(2_300)
        s.showCaller("Ana", inCall = true)
        Thread.sleep(2_300)
        assertEquals("no name", 0, fake.countOf(0x05, 0x22))
        assertTrue("only 'no call' (0x32) in the status, never ringing (0x0A) or in call (0x14): ${states(fake)}", states(fake).all { it == 0x32 })
        s.clearCaller()
        Thread.sleep(1_300)
        assertEquals(0, fake.countOf(0x05, 0x22))
        assertTrue(states(fake).all { it == 0x32 })
        val lines = logged()
        assertFalse("nothing was sent, so nothing is cleared: $lines", lines.any { it.contains("call card cleared") || it.contains("call card shown") })
    }

    @Test
    fun `the session still knows the call, so the maneuver ring stays on for it`() {
        val (s, _) = streaming(callCard = true)
        s.maneuverCircleEnabled = false
        val before = s.ringVisible
        s.showCaller("Ana")
        assertTrue("the ring is shown for the call even with its switch off ($before before)", s.ringVisible)
        s.clearCaller()
        assertEquals(before, s.ringVisible)
    }

    @Test
    fun `turned on in the middle of a call, the one clear goes at once and the name stops`() {
        val (s, fake) = streaming(callCard = false)
        s.showCaller("Ana")
        await("the name") { fake.countOf(0x05, 0x22) >= 1 }
        s.callCardOn = true
        await("the clear") { logged().any { it.contains("call card cleared") } }
        Thread.sleep(300)
        val names = fake.countOf(0x05, 0x22)
        Thread.sleep(2_300)
        assertEquals("no more names", names, fake.countOf(0x05, 0x22))
        assertEquals("the last state said is 'no call'", 0x32, states(fake).last())
        assertEquals("cleared once, not each second", 1, logged().count { it.contains("call card cleared") })
    }

    @Test
    fun `turned off in the middle of a call, the dash is told the call again`() {
        val (s, fake) = streaming(callCard = true)
        s.showCaller("Ana", inCall = true)
        Thread.sleep(1_500)
        assertEquals(0, fake.countOf(0x05, 0x22))
        s.callCardOn = false
        await("the name again") { fake.countOf(0x05, 0x22) >= 1 }
        assertEquals("in call", 0x14, states(fake).last { it != 0x32 })
    }
}
