package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import com.reau.app.util.MonotonicClock
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
 * The rider's yes to the dash's own "Exit navigation?" (its 09 00 0x12): REau answers 06 80 0x12, sends 06 05 = 0xAA (the navigation off) and holds the route card
 * and the nav packet back (both carry 06 05 = 0x55) until a new guidance; the rest (the status, the video) goes on. A real [DashSession] streaming to [FakeDashPeer] over
 * the loopback, as DashSessionCallCardTest does; what is checked is what goes on the wire, and the ride log's lines.
 */
class DashSessionExitNavigationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private var fakeDash: FakeDashPeer? = null
    private val logFile = java.nio.file.Files.createTempFile("exit-navigation", ".log").toFile()

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

    private fun streaming(): Pair<DashSession, FakeDashPeer> {
        com.reau.app.data.RideDiagnostics.testOnlySetFile(logFile)
        val fake = FakeDashPeer(clock = { System.nanoTime() / 1_000_000L }).also { fakeDash = it }
        val s = DashSession(scope, socketFactory = { DashSocket(null, ctrlDest = fake.ctrlDest, rtpDest = fake.rtpDest) }).also { session = it }
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

    private fun guide(s: DashSession, nowMs: Long = MonotonicClock.nowMs()) =
        s.updateNavInfo(0x14, 80, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS, nowMs = nowMs)

    /** The cards and packets that put the dash in its navigation: every TLV 06 05 = 0x55 (the route card's and the nav packet's projection flag). */
    private fun navOn(fake: FakeDashPeer) = fake.received.count { it.type == 0x06 && it.sub == 0x05 && it.value.singleOrNull()?.toInt() == 0x55 }

    private fun logged(): List<String> {
        com.reau.app.data.RideDiagnostics.logAndWait("end", "marker", timeoutMs = 10_000)
        return logFile.readLines()
    }

    @Test
    fun `the answer 06 80 12 goes first, then 06 05 AA alone, and the log says it`() {
        val (s, fake) = streaming()
        guide(s)
        await("the route card") { navOn(fake) >= 2 }
        val before = fake.received.size
        fake.pressButton(0x12)
        await("the navigation off") { fake.received.drop(before).any { it.type == 0x06 && it.sub == 0x05 && it.value.singleOrNull()?.toInt() == 0xAA } }
        val after = fake.received.drop(before)
        val answer = after.indexOfFirst { it.type == 0x06 && it.sub == 0x80 && it.value.singleOrNull()?.toInt() == 0x12 }
        val off = after.indexOfFirst { it.type == 0x06 && it.sub == 0x05 && it.value.singleOrNull()?.toInt() == 0xAA }
        assertTrue("the 06 80 12 answer, then the off: $answer, $off", answer in 0 until off)
        assertTrue(s.routeHold.isHeld)
        assertTrue(logged().any { it.contains("exit navigation confirmed on the dash: nav off sent, route card held until a new guidance") })
    }

    @Test
    fun `after the exit no route card and no nav packet go, while the status and the projection frames go on`() {
        val (s, fake) = streaming()
        guide(s)
        await("the route card") { navOn(fake) >= 2 }
        fake.pressButton(0x12)
        await("the hold") { s.routeHold.isHeld }
        Thread.sleep(300)
        val cards = navOn(fake)
        val maneuvers = fake.countOf(0x05, 0x02)
        val packets = fake.packetsReceived.get()
        Thread.sleep(2_500)
        assertEquals("no card, no packet", cards, navOn(fake))
        assertEquals("no maneuver (05 02) in a card or a nav packet, while Android Auto still guides", maneuvers, fake.countOf(0x05, 0x02))
        assertTrue("the rest goes on: ${fake.packetsReceived.get()} packets after $packets", fake.packetsReceived.get() >= packets + 5)
    }

    @Test
    fun `the guidance Android Auto goes on with does not bring the card back`() {
        val (s, fake) = streaming()
        guide(s)
        await("the route card") { navOn(fake) >= 2 }
        fake.pressButton(0x12)
        await("the hold") { s.routeHold.isHeld }
        Thread.sleep(300)
        val cards = navOn(fake)
        repeat(3) { guide(s, MonotonicClock.nowMs() + 60_000L * (it + 1)) }
        Thread.sleep(1_500)
        assertEquals(cards, navOn(fake))
        assertFalse(logged().any { it.contains("route card resumed") })
    }

    @Test
    fun `a new guidance after ten seconds without one brings the card back at once, and the log says so`() {
        val (s, fake) = streaming()
        guide(s)
        await("the route card") { navOn(fake) >= 2 }
        fake.pressButton(0x12)
        await("the hold") { s.routeHold.isHeld }
        Thread.sleep(300)
        val cards = navOn(fake)
        val inactiveAt = MonotonicClock.nowMs()
        s.clearNavInfo(inactiveAt)
        s.updateNavInfo(0x14, 80, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS, nowMs = inactiveAt + RouteHold.NEW_GUIDANCE_AFTER_MS)
        await("the card again") { navOn(fake) > cards }
        assertFalse(s.routeHold.isHeld)
        assertTrue(logged().any { it.contains("route card resumed: new guidance") })
    }

    @Test
    fun `a recalculation, a short inactive spell, keeps the card held`() {
        val (s, fake) = streaming()
        guide(s)
        await("the route card") { navOn(fake) >= 2 }
        fake.pressButton(0x12)
        await("the hold") { s.routeHold.isHeld }
        Thread.sleep(300)
        val cards = navOn(fake)
        val inactiveAt = MonotonicClock.nowMs()
        s.clearNavInfo(inactiveAt)
        guide(s, inactiveAt + 7_000)
        Thread.sleep(1_500)
        assertEquals(cards, navOn(fake))
        assertTrue(s.routeHold.isHeld)
    }
}
