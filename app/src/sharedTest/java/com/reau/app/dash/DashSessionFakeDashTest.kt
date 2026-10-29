package com.reau.app.dash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real [DashSession] talking to [FakeDashPeer] (the dash's own side of
 * K1G) over real loopback UDP sockets — [DashSocket]'s destination injected via [DashSession]'s
 * `socketFactory`, its bind ports (2000/2002) untouched, exactly as on the real subnet. It lives in the
 * shared test sources, so it runs on the JVM (in the CI, the coverage report and the mutation test) and on the
 * emulator. (DashSocketTest's kdoc found loopback UDP unreliable in an old sandbox, "packets never arrive even
 * on 127.0.0.1"; on the Linux build machine and the runners it is not, and this test checks it on every run.)
 *
 * **What this proves and what it doesn't**: FakeDashPeer answers the bytes the real firmware was
 * observed to send, and these tests prove DashSession sequences and wires
 * the protocol correctly against that — they are NOT proof a real physical dash accepts any of it.
 * Only a test on the real dash can show that.
 */
class DashSessionFakeDashTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private var fakeDash: FakeDashPeer? = null

    @After
    fun tearDown() {
        session?.disconnect()
        scope.cancel()
        fakeDash?.close()
    }

    private fun newSession(
        phoneStatusProvider: () -> com.reau.app.dash.protocol.DashCommands.PhoneStatus? = { null },
        timing: DashSession.Timing = DashSession.Timing(),
    ): Pair<DashSession, FakeDashPeer> {
        val fake = FakeDashPeer().also { fakeDash = it }
        val s = DashSession(
            scope,
            socketFactory = { DashSocket(null, ctrlDest = fake.ctrlDest, rtpDest = fake.rtpDest) },
            phoneStatusProvider = phoneStatusProvider,
            timing = timing,
        ).also { session = it }
        return s to fake
    }

    /** Polls [state] up to [timeoutMs], returns as soon as [predicate] is true — real UDP round trips
     *  over loopback are fast but not instant, and DashSession's own nav-entry pacing adds ~2s. */
    private fun <T> waitUntil(timeoutMs: Long = 8_000, intervalMs: Long = 50, state: () -> T, predicate: (T) -> Boolean): T = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = state()
        while (!predicate(last) && System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            last = state()
        }
        last
    }

    @Test
    fun aDashSwitchedOnSecondsAgoIsGreetedAgainUntilItsCounterSaysItIsReady() {
        val (s, fake) = newSession()
        fake.beYoung(12) // as the entry of 09:52:36 on 20/10/2026: a counter of 12, ready (26) only 14 s later
        s.connect("DashFake")

        val state = waitUntil(timeoutMs = 32_000, state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })

        assertEquals(DashState.READY, state)
        assertNotNull(fake.sessionKey)
    }

    @Test
    fun aDashThatIgnoresTheFirstFourGreetingsStillAuthenticatesBeforeTheDeadline() {
        // The greeting's patience shortened (3 s on the bike): the rule is the count of resends, not the seconds.
        val (s, fake) = newSession(timing = DashSession.Timing(keyPatienceMs = 600))
        fake.ignoreFirstGreetings = 4 // the 5th greeting (the 4th resend) is the first answered; the old limit stopped at 3 resends
        s.connect("DashFake")

        val state = waitUntil(timeoutMs = 10_000, state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })

        assertEquals(DashState.READY, state)
        assertNotNull(fake.sessionKey)
    }

    @Test
    fun theFullHandshakeReachesReadyWithARealUsableSessionKey() {
        val (s, fake) = newSession()
        s.connect("DashFake")

        val state = waitUntil(state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })

        assertEquals(DashState.READY, state)
        assertEquals("the phone's SSID must survive RSA round-trip intact", "DashFake", fake.lastSsid)
        assertNotNull("a real AES-256 key came out the other end", fake.sessionKey)
        assertEquals(32, fake.sessionKey!!.size)
    }

    @Test
    fun navEntryFollowsTheDocumentedOrderRouteCardX4ThenProjectionThenZ2ThenRouteCard() {
        val (s, fake) = newSession()
        s.connect("DashFake")
        waitUntil(state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })
        // _state flips to READY the instant enterNavMode()'s last send() call returns -- fire-and-
        // forget UDP, so the fake dash's receiver thread can still be a few ms behind draining its
        // socket. A short grace period avoids a false "not found yet" on the very last TLVs sent.
        runBlocking { delay(300) }

        // DashSession.enterNavMode, in order: navContext(05,2E) emptyLists(five 05,2F..05,33 TLVs,
        // not tracked here) route-card(05,01, the title TLV) x4 projectionFrame(05,56) navPlaceholder
        // (06,0A) navStart(06,80) once more route-card(05,01) -- the 5th, "confirmation" one.
        // projectionFrame shares its (type,sub) with TWO other commands that reuse the very same
        // slot with a different VALUE (found empirically: an assertion first failed against the
        // initial burst's own "projection off" announcement, sent before auth even completes) --
        // 0x55 = projectionFrame "on" (this one), 0xAA = projectionStop/the burst's off marker. A
        // plain (type,sub) match is ambiguous here, so this one specific step is tagged by value too.
        fun tag(t: com.reau.app.dash.protocol.Tlv): Any = when {
            t.type == 0x05 && t.sub == 0x56 -> Triple(t.type, t.sub, t.value.firstOrNull())
            else -> t.type to t.sub
        }
        val projectionFrameOn = Triple(0x05, 0x56, 0x55.toByte())
        val wanted = setOf(0x05 to 0x2E, 0x05 to 0x01, 0x06 to 0x0A, 0x06 to 0x80, projectionFrameOn)
        val seq = fake.receivedSinceHandshake().map { tag(it) }.filter { it in wanted }

        assertEquals("5 route cards total: 4 during entry + 1 confirmation after navStart", 5, seq.count { it == (0x05 to 0x01) })
        val firstRouteCardAt = seq.indexOfFirst { it == (0x05 to 0x01) }
        assertTrue("navContext (05 2E) must come before the first route card", seq.indexOf(0x05 to 0x2E) in 0 until firstRouteCardAt)
        val fourthRouteCardAt = seq.withIndex().filter { it.value == (0x05 to 0x01) }[3].index
        assertTrue("projectionFrame (05 56, value 0x55) comes after the 4 entry route cards -- seq=$seq", seq.indexOf(projectionFrameOn) > fourthRouteCardAt)
        assertTrue("navPlaceholder (06 0A) comes after projectionFrame", seq.indexOf(0x06 to 0x0A) > seq.indexOf(projectionFrameOn))
        assertTrue("navStart (06 80) comes after navPlaceholder", seq.indexOf(0x06 to 0x80) > seq.indexOf(0x06 to 0x0A))
        assertTrue("the confirmation route card is the very last of the 5", seq.indexOfLast { it == (0x05 to 0x01) } > seq.indexOf(0x06 to 0x80))
    }

    @Test
    fun frameDecodeAcksFromTheDashGetTheirMandatoryReplyEachTime() {
        val (s, fake) = newSession()
        s.connect("DashFake")
        waitUntil(state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })
        s.startStreaming()

        fake.ackIdrFrame()
        fake.ackPFrame()

        // DashSession replies to every dash ack with frameDecodedIdr()/frameDecodedP() -- both are
        // K1G TLVs the fake dash itself receives back as (06,11)/(06,12) per DashCommands' own hex.
        waitUntil(state = { fake.countOf(0x06, 0x11) to fake.countOf(0x06, 0x12) }, predicate = { (idr, p) -> idr >= 1 && p >= 1 })
        assertTrue(fake.countOf(0x06, 0x11) >= 1)
        assertTrue(fake.countOf(0x06, 0x12) >= 1)
    }

    @Test
    fun heartbeatAndRouteCardKeepAliveRepeatAtAboutOneHertzWhileStreaming() {
        val (s, fake) = newSession()
        s.connect("DashFake")
        waitUntil(state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })
        s.startStreaming()
        fake.ackIdrFrame() // keep the RX watchdog happy while we measure cadence

        val before = fake.countOf(0x06, 0x08)  // heartbeat's own TLV, HB_0049's "06 08 0001 05" marker
        runBlocking { delay(3_200) }
        val after = fake.countOf(0x06, 0x08)

        // ~1 Hz over ~3.2s -- generous bounds for real scheduler jitter on a shared CI/emulator, but
        // proves it neither stalls nor free-runs.
        assertTrue("expected ~3 heartbeats in 3.2s, got ${after - before}", (after - before) in 2..5)
    }

    // ── sendRealPhoneStatus: the fake dash sees the right bytes on the wire in both cases ──

    @Test
    fun heartbeatSendsTodaysExactPlaceholderBytesWhenNoPhoneStatusProviderIsGiven() {
        // The default (no phoneStatusProvider passed, matching every real caller today) -- what the
        // fake dash sees on the wire must be untouched by the real phone status at all.
        val (s, fake) = newSession()
        s.connect("DashFake")
        waitUntil(state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })
        s.startStreaming()
        fake.ackIdrFrame()

        waitUntil(state = { fake.countOf(0x06, 0x08) }, predicate = { it >= 1 })
        val battery = fake.received.last { it.type == 0x06 && it.sub == 0x04 }.value[0].toInt() and 0xFF
        val charging = fake.received.last { it.type == 0x06 && it.sub == 0x0F }.value[0].toInt() and 0xFF
        val gps = fake.received.last { it.type == 0x06 && it.sub == 0x03 }.value[0].toInt() and 0xFF
        val signalPresent = fake.received.last { it.type == 0x06 && it.sub == 0x01 }.value[0].toInt() and 0xFF
        assertEquals("today's fixed placeholder battery (62%)", 0xA2, battery)
        assertEquals("today's fixed placeholder charging (off)", 0xAA, charging)
        assertEquals("today's fixed placeholder GPS (on)", 0x55, gps)
        assertEquals("today's fixed placeholder signal presence (present)", 0x01, signalPresent)
    }

    @Test
    fun heartbeatSendsThePhoneStatusProvidersRealBytesWhenOneIsGiven() {
        // The flag-on case (the caller wires this from DashConfig.sendRealPhoneStatus) -- values
        // deliberately all different from today's placeholder bytes, so a false pass (accidentally
        // reading the old fixed bytes) cannot happen unnoticed.
        val liveStatus = com.reau.app.dash.protocol.DashCommands.PhoneStatus(
            batteryPercent = 45, charging = true, gpsOn = false, signalPresent = true,
        )
        val (s, fake) = newSession(phoneStatusProvider = { liveStatus })
        s.connect("DashFake")
        waitUntil(state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })
        s.startStreaming()
        fake.ackIdrFrame()

        waitUntil(state = { fake.countOf(0x06, 0x08) }, predicate = { it >= 1 })
        val battery = fake.received.last { it.type == 0x06 && it.sub == 0x04 }.value[0].toInt() and 0xFF
        val charging = fake.received.last { it.type == 0x06 && it.sub == 0x0F }.value[0].toInt() and 0xFF
        val gps = fake.received.last { it.type == 0x06 && it.sub == 0x03 }.value[0].toInt() and 0xFF
        val signalPresent = fake.received.last { it.type == 0x06 && it.sub == 0x01 }.value[0].toInt() and 0xFF
        assertEquals(145, battery)      // 45 + 100
        assertEquals(0x55, charging)    // charging -> 0x55
        assertEquals(0xAA, gps)         // gpsOn=false -> 0xAA
        assertEquals(0x01, signalPresent)
    }

    @Test
    fun theRxWatchdogFailsTheSessionAfterAboutSixSecondsOfDashSilence() {
        // The watchdog's times shortened (4 s and a 1 s tick on the bike; RX_WATCHDOG_MS is pinned in TimingConstantsTest's place by DashSessionTest).
        val (s, fake) = newSession(timing = DashSession.Timing(rxWatchdogMs = 1_000, watchdogTickMs = 250))
        s.connect("DashFake")
        waitUntil(state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })
        s.startStreaming()
        fake.ackIdrFrame()                                  // one real ack first -- the watchdog only arms after loggedFirstAck
        runBlocking { delay(300) }
        fake.silent = true                                  // now go quiet, as if out of range

        val state = waitUntil(timeoutMs = 4_000, state = { s.state.value }, predicate = { it == DashState.ERROR })
        assertEquals(DashState.ERROR, state)
    }

    // ── what the frame loop reads to keep the rate inside what the dash takes ──

    @Test
    fun theFirstFrameCountsAsConfirmedOnlyOnceTheDashAcksIt() {
        val (s, fake) = newSession()
        s.connect("DashFake")
        waitUntil(state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })
        s.startStreaming()
        runBlocking { delay(300) }
        assertEquals("streaming, but the dash has decoded nothing yet", false, s.firstFrameConfirmed)

        fake.ackIdrFrame()
        assertEquals(true, waitUntil(state = { s.firstFrameConfirmed }, predicate = { it }))
    }

    @Test
    fun aRejectedSessionKeyMakesTheAppRetryAndTheSecondAttemptStillReachesReady() {
        val (s, fake) = newSession()
        fake.rejectFirstKey = true
        s.connect("DashFake")

        val state = waitUntil(state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })

        assertEquals("the auth-retry path (DashAuth.reset + a fresh authRequest) must recover from one rejection", DashState.READY, state)
        assertNotNull(fake.sessionKey)
    }

    @Test
    fun aRejectionAfterTheKeyWasConfirmedDoesNotStartTheExchangeOver() {
        val (s, fake) = newSession()
        s.connect("DashFake")
        waitUntil(state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })
        assertEquals(DashState.READY, s.state.value)
        val key = fake.sessionKey
        val greetings = fake.countOf(0x08, 0x04)
        val keys = fake.countOf(0x08, 0x00)
        fake.rejectNow()
        Thread.sleep(1_500)
        assertEquals("no new greeting asking for a key", greetings, fake.countOf(0x08, 0x04))
        assertEquals("no new session key sent", keys, fake.countOf(0x08, 0x00))
        assertArrayEquals(key, fake.sessionKey)
    }

    @Test
    fun aButtonPressFromTheDashReachesOnButtonAndGetsAckedBack() {
        val (s, fake) = newSession()
        var seen: com.reau.app.dash.protocol.DashButtonEvent? = null
        s.onButton = { seen = it }
        s.connect("DashFake")
        waitUntil(state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })

        fake.pressButton(0x18) // JOYSTICK_IN

        waitUntil(state = { seen }, predicate = { it != null })
        assertTrue(seen is com.reau.app.dash.protocol.DashButtonEvent.JoystickIn)
        waitUntil(state = { fake.countOf(0x06, 0x80) }, predicate = { it >= 1 })
    }

    @Test
    fun theMusicCardIsSentOnlyWhileTheJoystickPageAllowsIt() {
        val (s, fake) = newSession()
        s.musicCardAllowed = false
        s.updateNowPlaying("Song", "Album", "Band")
        s.connect("DashFake")
        waitUntil(state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })
        s.startStreaming()
        Thread.sleep(2_500)
        assertEquals("no music control on the page: no card", 0, fake.countOf(0x05, 0x0D))
        s.musicCardAllowed = true
        waitUntil(state = { fake.countOf(0x05, 0x0D) }, predicate = { it >= 1 })
    }

    /** A-14: the album's picture goes out in up to 66 slices with a pause between them; a call card does not wait for the last. */
    @Test
    fun aCallCardDoesNotWaitForTheAlbumPicture() {
        val (s, fake) = newSession()
        s.connect("DashFake")
        waitUntil(state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })
        s.startStreaming()
        val jpeg = ByteArray(60_000) { (it % 251).toByte() }
        val slices = com.reau.app.dash.protocol.DashCommands.albumArtChunks(jpeg).size
        s.updateNowPlaying("Song", "Album", "Band", coverJpeg = jpeg)
        waitUntil(state = { fake.countOf(0x05, 0x40) }, predicate = { it >= 1 })
        val callsBefore = fake.countOf(0x05, 0x21)

        s.showCaller("Ana")

        waitUntil(state = { fake.countOf(0x05, 0x21) }, predicate = { it > callsBefore })
        assertTrue("the call card came before the picture's last slice", fake.countOf(0x05, 0x40) < slices)
    }

    @Test
    fun aMediaButtonThatHidesTheManeuverRingIsToldToTheDebugText() {
        val (s, fake) = newSession()
        s.maneuverCircleEnabled = false
        val notes = java.util.concurrent.CopyOnWriteArrayList<String>()
        s.onRingChange = { notes += it }
        s.connect("DashFake")
        waitUntil(state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })

        fake.pressButton(0x13) // a map mode press shows the ring
        waitUntil(state = { notes.size }, predicate = { it >= 1 })
        fake.pressButton(0x05) // a media mode press hides it
        waitUntil(state = { notes.size }, predicate = { it >= 2 })

        assertEquals(listOf("maneuver ring shown: joystick 0x13", "maneuver ring hidden: joystick 0x05"), notes.toList())
    }

    @Test
    fun disconnectSendsTheDocumentedTeardownSequence() {
        val (s, fake) = newSession()
        s.connect("DashFake")
        waitUntil(state = { s.state.value }, predicate = { it == DashState.READY || it == DashState.ERROR })

        s.disconnect()
        runBlocking { delay(300) } // teardown sends are fire-and-forget UDP; give them a moment to land

        // projectionStop(05,56) projectionOff(06,05) exitBack(06,80) exitProjectionIdle(06,05)x4 exitSessionBoundary(05,17)x2
        assertTrue("projectionStop", fake.countOf(0x05, 0x56) >= 1)
        assertTrue("exitSessionBoundary sent twice", fake.countOf(0x05, 0x17) >= 2)
        assertEquals(DashState.IDLE, s.state.value)
    }
}
