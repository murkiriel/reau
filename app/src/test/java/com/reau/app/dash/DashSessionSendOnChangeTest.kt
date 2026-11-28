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
 * The switch "send only what changed" ([DashSession]'s `sendOnChange`), on a real session streaming to [FakeDashPeer] over the loopback, as DashSessionCallCardTest does: with it on,
 * only the video is periodic (the projection flag, the status, the route card, the nav packet and the cards go when they change, and all of them again on a new link); with it
 * off, every one goes on its old timer, as before. What is checked is what goes on the wire, and the ride log's line.
 */
class DashSessionSendOnChangeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private val fakes = mutableListOf<FakeDashPeer>()
    private var current: FakeDashPeer? = null
    private val logFile = java.nio.file.Files.createTempFile("send-on-change", ".log").toFile()

    @After
    fun tearDown() {
        session?.disconnect()
        scope.cancel()
        fakes.forEach { it.close() }
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

    /** A session of the switch [on] streaming to a fake dash which talks as a real one; [again] connects the same session to a new fake after a disconnect. */
    private fun streaming(on: Boolean, prepare: (DashSession) -> Unit = {}): Pair<DashSession, FakeDashPeer> {
        com.reau.app.data.RideDiagnostics.testOnlySetFile(logFile)
        val s = DashSession(
            scope, sendOnChange = { on }, statsEveryMs = 3_600_000L, // the test reads the dash-stats window itself: the session's own line must not close it
            socketFactory = { current!!.let { f -> DashSocket(null, ctrlDest = f.ctrlDest, rtpDest = f.rtpDest) } },
        ).also { session = it }
        // The card with no route without the clock of its ETA, so the idle card is the same all along (a minute's turn would be a real change).
        s.idleCard = DashIdleCard.HIDDEN
        prepare(s)
        return s to connect(s)
    }

    private fun connect(s: DashSession): FakeDashPeer {
        val fake = FakeDashPeer(clock = { System.nanoTime() / 1_000_000L }).also { fakes += it; current = it }
        s.connect("DashFake")
        await("the session to be ready") { s.state.value == DashState.READY || s.state.value == DashState.ERROR }
        assertEquals(DashState.READY, s.state.value)
        s.startStreaming()
        fake.ackIdrFrame()
        await("the dash's ack") { s.firstFrameConfirmed }
        scope.launch {
            while (isActive && current === fake) {
                fake.ackPFrame()
                delay(300)
            }
        }
        return fake
    }

    private fun projectionFlags(f: FakeDashPeer) = f.received.count { it.type == 0x05 && it.sub == 0x56 && it.value.singleOrNull()?.toInt()?.and(0xFF) == 0x55 }
    private fun statuses(f: FakeDashPeer) = f.countOf(0x06, 0x04)
    /** What the phone sent on its own initiative: not the answers to the dash's decode acks (0611, 0612), which the fake sends every 300 ms. */
    private fun ownPackets(f: FakeDashPeer) = f.received.count { !(it.type == 0x06 && (it.sub == 0x11 || it.sub == 0x12)) }
    private fun heartbeatsOf(line: String) = Regex("""heartbeats=(\d+)""").find(line)!!.groupValues[1].toInt()
    private fun routeCards(f: FakeDashPeer) = f.countOf(0x05, 0x08)
    private fun navPackets(f: FakeDashPeer) = f.countOf(0x05, 0x04)

    private fun guide(s: DashSession, meters: Int = 80) =
        s.updateNavInfo(0x14, meters, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS)

    private fun logged(): List<String> {
        com.reau.app.data.RideDiagnostics.logAndWait("end", "marker", timeoutMs = 10_000)
        return logFile.readLines()
    }

    // ── on: the quiet of a projection with nothing new ──────────────────────

    @Test
    fun `on, a projection with nothing new sends no projection flag, status or route card for seconds, and the video goes on`() {
        val (s, fake) = streaming(on = true)
        Thread.sleep(1_500)
        val flags = projectionFlags(fake)
        val status = statuses(fake)
        val cards = routeCards(fake)
        val packets = ownPackets(fake)
        s.stats.windowLine(1_000, MonotonicClock.nowMs()) // a new window
        Thread.sleep(2_500)
        assertEquals("the dash-stats line counts what was sent: no heartbeat in 2.5 s", 0, heartbeatsOf(s.stats.windowLine(2_500, MonotonicClock.nowMs())))
        assertEquals("the projection flag is a latch", flags, projectionFlags(fake))
        assertEquals("the status is said when it changes", status, statuses(fake))
        assertEquals("the route card too", cards, routeCards(fake))
        assertEquals("not one packet of the phone's own in 2.5 s", packets, ownPackets(fake))
        assertTrue("it was said once at the start", flags in 1..3)
    }

    @Test
    fun `off, the same projection sends the flag at 4 Hz, the status and the route card every second, as before`() {
        val (s, fake) = streaming(on = false)
        Thread.sleep(1_000)
        val flags = projectionFlags(fake)
        val status = statuses(fake)
        val cards = routeCards(fake)
        s.stats.windowLine(1_000, MonotonicClock.nowMs()) // a new window
        Thread.sleep(2_500)
        val line = s.stats.windowLine(2_500, MonotonicClock.nowMs())
        assertTrue("the dash-stats line counts every heartbeat, as before: $line", heartbeatsOf(line) >= 8)
        assertTrue("flag: ${projectionFlags(fake) - flags} in 2.5 s", projectionFlags(fake) - flags >= 8)
        assertTrue("status: ${statuses(fake) - status} in 2.5 s", statuses(fake) - status >= 2)
        assertTrue("route card: ${routeCards(fake) - cards} in 2.5 s", routeCards(fake) - cards >= 2)
    }

    @Test
    fun `the log says whether the session sends only what changed`() {
        streaming(on = true)
        assertTrue(logged().any { it.contains("dash") && it.endsWith("send-on-change on") })
        session?.disconnect()
        val (_, _) = streaming(on = false)
        assertTrue(logged().any { it.endsWith("send-on-change off") })
    }

    @Test
    fun `the data of the bike is not asked again on a timer with the switch on, the burst asked once`() {
        val src = java.io.File("src/main/java/com/reau/app/dash/DashSession.kt").readText()
        assertTrue("if (!change.enabled && dataRefreshDue(n, _state.value)) {" in src)
        val (_, fake) = streaming(on = true)
        assertEquals("the burst's ask and nothing else", 1, fake.countOf(0x0A, 0x02))
    }

    // ── on: what changes goes, once ─────────────────────────────────────────

    @Test
    fun `a call going out once and a change of its state once more, the name never every second`() {
        val (s, fake) = streaming(on = true)
        Thread.sleep(500)
        s.showCaller("Ana")
        await("the name") { fake.countOf(0x05, 0x22) >= 1 }
        Thread.sleep(1_500)
        val status = statuses(fake)
        Thread.sleep(2_300)
        assertEquals("the name once", 1, fake.countOf(0x05, 0x22))
        assertEquals("the status said the call once, in the next second, and nothing since", status, statuses(fake))
        s.showCaller("Ana", inCall = true)
        await("the new state") { fake.countOf(0x05, 0x22) >= 2 }
        Thread.sleep(1_500)
        assertEquals("the second state once", 2, fake.countOf(0x05, 0x22))
        s.clearCaller()
        Thread.sleep(500)
        s.showCaller("Ana", inCall = true) // the very state and name the dash was last told: after a clear it is new again
        await("a next call is a first one") { fake.countOf(0x05, 0x22) >= 3 }
    }

    @Test
    fun `the music card goes whole once, the pause alone, the next song's text alone`() {
        val (s, fake) = streaming(on = true)
        Thread.sleep(500)
        s.updateNowPlaying("Song", "Album", "Artist", playing = true)
        await("the card") { fake.countOf(0x05, 0x0D) >= 1 }
        Thread.sleep(2_300)
        assertEquals("the text once", 1, fake.countOf(0x05, 0x0D))
        assertEquals("the light on once", 1, fake.received.count { it.type == 0x05 && it.sub == 0x17 && it.value.singleOrNull()?.toInt()?.and(0xFF) == 0x55 })
        s.updateNowPlaying("Song", "Album", "Artist", playing = true)
        Thread.sleep(1_300)
        assertEquals("the same song again says nothing", 1, fake.countOf(0x05, 0x0D))
        val plays = fake.countOf(0x05, 0x19)
        s.updateNowPlaying("Song", "Album", "Artist", playing = false)
        await("the pause") { fake.countOf(0x05, 0x19) > plays }
        Thread.sleep(1_300)
        assertEquals("the pause is the play state alone", 1, fake.countOf(0x05, 0x0D))
        assertEquals(plays + 1, fake.countOf(0x05, 0x19))
        s.updateNowPlaying("Other", "Album", "Artist", playing = false)
        await("the new text") { fake.countOf(0x05, 0x0D) >= 2 }
        Thread.sleep(1_300)
        assertEquals(2, fake.countOf(0x05, 0x0D))
    }

    @Test
    fun `a music card that goes away and comes back shows whole, every packet again`() {
        val (s, fake) = streaming(on = true)
        s.updateNowPlaying("Song", "Album", "Artist", playing = true)
        await("the card") { fake.countOf(0x05, 0x0D) >= 1 }
        fun lights(value: Int) = fake.received.count { it.type == 0x05 && it.sub == 0x17 && it.value.singleOrNull()?.toInt()?.and(0xFF) == value }
        val burstsOff = lights(0xAA) // the initial burst says "no song" once
        s.musicCardAllowed = false
        await("the clear") { lights(0xAA) > burstsOff }
        s.musicCardAllowed = true
        await("the text again") { fake.countOf(0x05, 0x0D) >= 2 }
        await("the play state again") { fake.countOf(0x05, 0x19) >= 2 }
        await("the cover's flag again") { fake.countOf(0x05, 0x58) >= 2 }
        assertEquals("the light is on again too", 2, lights(0x55))
    }

    @Test
    fun `the nav packet goes when the guidance changes, not every second, and again for a guidance after none`() {
        val (s, fake) = streaming(on = true)
        guide(s)
        await("the nav packet") { navPackets(fake) >= 1 }
        Thread.sleep(2_300)
        assertEquals("one, while it says the same", 1, navPackets(fake))
        guide(s, meters = 60)
        await("the change") { navPackets(fake) >= 2 }
        Thread.sleep(1_300)
        assertEquals(2, navPackets(fake))
        s.clearNavInfo()
        Thread.sleep(1_300)
        guide(s, meters = 60)
        await("the same guidance after none is new to the dash") { navPackets(fake) >= 3 }
    }

    @Test
    fun `the route card goes when its title does, an action's name and then the street again`() {
        val (s, fake) = streaming(on = true)
        Thread.sleep(1_200)
        val cards = routeCards(fake)
        assertTrue(s.echoAction("Zoom +"))
        await("the card with the name") { routeCards(fake) > cards }
        val named = routeCards(fake)
        await("the card back to the title when the name runs out", timeoutMs = ActionEcho.SHOW_MS + 4_000) { routeCards(fake) > named }
        val back = routeCards(fake)
        Thread.sleep(2_500)
        assertEquals("the title is held by the dash, the card is not repeated", back, routeCards(fake))
    }

    @Test
    fun `a volume step puts the status on the wire at once, and the same status again says nothing`() {
        var level = 3
        com.reau.app.data.RideDiagnostics.testOnlySetFile(logFile)
        val fake = FakeDashPeer(clock = { System.nanoTime() / 1_000_000L }).also { fakes += it; current = it }
        val s = DashSession(
            scope, sendOnChange = { true }, socketFactory = { DashSocket(null, ctrlDest = fake.ctrlDest, rtpDest = fake.rtpDest) },
            phoneStatusProvider = { DashCommands.PhoneStatus(80, charging = false, gpsOn = true, signalPresent = true, musicLevel = level) },
        ).also { session = it }
        s.idleCard = DashIdleCard.HIDDEN
        s.connect("DashFake")
        await("ready") { s.state.value == DashState.READY }
        s.startStreaming()
        fake.ackIdrFrame()
        await("the ack") { s.firstFrameConfirmed }
        Thread.sleep(1_500)
        val status = statuses(fake)
        s.sendStatusNow()
        Thread.sleep(500)
        assertEquals("the same volume: nothing to say", status, statuses(fake))
        level = 4
        s.sendStatusNow()
        await("the new level at once") { statuses(fake) > status }
        Thread.sleep(1_500)
        assertEquals("said once, the loop's next second adds nothing", status + 1, statuses(fake))
    }

    @Test
    fun `a press that shows the maneuver ring sends the card at once with the answer, and the next second adds no second card`() {
        val (s, fake) = streaming(on = true) { it.maneuverCircleEnabled = false }
        guide(s)
        await("the guidance card") { navPackets(fake) >= 1 }
        Thread.sleep(1_300)
        val cards = routeCards(fake)
        fake.pressButton(0x13)
        await("the card with the ring") { routeCards(fake) > cards }
        Thread.sleep(2_000)
        assertEquals("the card of the press, once", cards + 1, routeCards(fake))
    }

    @Test
    fun `a press that changes the ring but not what the card says sends no card`() {
        // With no guidance and the card with no route hidden, the ring on or off draws the same card: nothing is new to the dash.
        val (_, fake) = streaming(on = true) { it.maneuverCircleEnabled = false }
        Thread.sleep(1_500)
        val cards = routeCards(fake)
        fake.pressButton(0x13)
        Thread.sleep(2_500)
        assertEquals("the same card, not said again", cards, routeCards(fake))
    }

    // ── on: a new link says everything again ────────────────────────────────

    @Test
    fun `a reconnection says the projection flag, the status and the route card to the new dash, even with nothing changed`() {
        val (s, first) = streaming(on = true)
        guide(s)
        s.updateNowPlaying("Song", "Album", "Artist")
        await("the first dash has it all") { navPackets(first) >= 1 && first.countOf(0x05, 0x0D) >= 1 }
        s.disconnect()
        val second = connect(s)
        guide(s) // a disconnect ends the guidance; Android Auto's goes on and says the same thing again
        await("the nav packet to the new dash") { navPackets(second) >= 1 }
        await("the music card to the new dash") { second.countOf(0x05, 0x0D) >= 1 }
        await("the route card to the new dash") { routeCards(second) >= 2 }
        await("the status to the new dash, after the burst's and the first turn's") { statuses(second) >= 3 }
        assertTrue("the flag: the nav entry's and the stream's", projectionFlags(second) >= 2)
    }

    @Test
    fun `the dash's own exit from the navigation makes the next card and nav packet new to it`() {
        val (s, fake) = streaming(on = true)
        guide(s)
        await("the card and the packet") { navPackets(fake) >= 1 && s.change.holds(SendOnChange.ROUTE_CARD) && s.change.holds(SendOnChange.NAV) }
        fake.pressButton(0x12)
        await("the hold") { s.routeHold.isHeld }
        assertFalse("the card is not held as sent after the navigation off", s.change.holds(SendOnChange.ROUTE_CARD))
        assertFalse(s.change.holds(SendOnChange.NAV))
        val packets = navPackets(fake)
        val inactiveAt = MonotonicClock.nowMs()
        s.clearNavInfo(inactiveAt)
        s.updateNavInfo(0x14, 80, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS, nowMs = inactiveAt + RouteHold.NEW_GUIDANCE_AFTER_MS)
        await("the same guidance after the exit brings the nav packet back") { navPackets(fake) > packets }
    }

    // ── off: the old behaviour, exactly ─────────────────────────────────────

    @Test
    fun `off, a call's name goes every second and the data of the bike is not touched by the switch`() {
        val (s, fake) = streaming(on = false)
        s.showCaller("Ana")
        await("the name") { fake.countOf(0x05, 0x22) >= 1 }
        Thread.sleep(2_300)
        assertTrue("the name every second: ${fake.countOf(0x05, 0x22)}", fake.countOf(0x05, 0x22) >= 3)
        assertFalse(s.change.enabled)
        assertFalse("nothing is held with the switch off", s.change.holds(SendOnChange.STATUS))
    }

    @Test
    fun `off, a press that shows the maneuver ring sends the card with the answer and the second's card follows, as before`() {
        val (s, fake) = streaming(on = false) { it.maneuverCircleEnabled = false }
        guide(s)
        await("the guidance card") { navPackets(fake) >= 1 }
        Thread.sleep(1_300)
        val cards = routeCards(fake)
        fake.pressButton(0x13)
        await("the card with the ring") { routeCards(fake) > cards }
        Thread.sleep(2_000)
        assertTrue("the press's and the seconds': ${routeCards(fake) - cards}", routeCards(fake) - cards >= 3)
    }

    @Test
    fun `the loops keep the clock and the data of the bike on their old timers with the switch off, and by the minute and once with it on`() {
        val src = java.io.File("src/main/java/com/reau/app/dash/DashSession.kt").readText()
        assertTrue(src, "if (runCatching { sendStatus(sock, status) }.getOrDefault(true)) stats.heartbeat()" in src)
        assertTrue(src, "                sendClock(sock, n)\n" in src)
        assertTrue(src, "change.attempt(SendOnChange.CLOCK, DashCommands.clockShown(now)) {\n                runCatching { sock.send(DashCommands.timeSync(now)) }.getOrDefault(false)" in src)
        assertTrue(src, "} else if (tick % 30 == 0) {\n            runCatching { sock.send(DashCommands.timeSync()) }" in src)
        assertTrue(src, "        if (change.enabled) {\n            val now = java.util.Calendar.getInstance()" in src)
    }

    @Test
    fun `off, the nav packet goes every second`() {
        val (s, fake) = streaming(on = false)
        guide(s)
        await("the nav packet") { navPackets(fake) >= 1 }
        Thread.sleep(2_300)
        assertTrue("${navPackets(fake)}", navPackets(fake) >= 3)
    }
}
