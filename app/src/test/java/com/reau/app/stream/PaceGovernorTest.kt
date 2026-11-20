package com.reau.app.stream

import com.reau.app.dash.DashConfig
import com.reau.app.dash.VideoChoice
import com.reau.app.stream.StreamGovernor.Action
import com.reau.app.video.DashCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pace of profile A and the reserve's ceiling (D117, plan 32) as the stream governor lives them in a release build (not manual): profile B is the
 * exact half of A in the pace and in the bitrate; in the reserve A goes down to 6 fps and 400 kbps and B to the half of that, the light screens never
 * above 6, and none of it ever rebuilds the encoder. The debug build's manual choices (no profile B, no start ramp, no halving while still; the reserve,
 * the light screens and the heat stay) are the second half of this file. What reaches the governor from the settings is VideoChoiceStoreTest's.
 */
class PaceGovernorTest {
    private var nowMs = 0L

    /** What a pace was given as a ceiling in the first plan-32 build: the bitrate A of a release-like stream at that pace (B is the half of it). */
    private fun bitrateOf(pace: Int) = when (pace) {
        8 -> 600_000
        12 -> 800_000
        else -> 400_000
    }

    private fun governor(
        pace: Int,
        lightScreenFps: Int = DashConfig.LIGHT_SCREEN_FPS,
        reserveCap: Boolean = true,
        bitrate: Int = bitrateOf(pace),
        manual: Boolean = false,
    ): StreamGovernor = StreamGovernor(
        pace, bitrateCeilingBps = bitrate, keyframeSec = DashConfig.PROFILE_KEYFRAME_SEC,
        reducedBitrateBps = bitrate / 2, lightScreenFps = lightScreenFps, reserveCapOn = reserveCap, manual = manual,
    )

    /** One poll 250 ms after the one before, with [drawn] new pictures (the stream moves when there are some). */
    private fun StreamGovernor.poll(
        drawn: Int = 1,
        navSample: StreamGovernor.NavSample? = null,
        lowFuel: Boolean = false,
        light: Boolean = false,
        confirmed: Boolean = true,
    ): List<Action> {
        nowMs += POLL_MS
        return onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs,
                compositor = DashCompositor.Stats(sourceFrames = drawn * 2, drawn = drawn, repeated = 0, composeNs = drawn * 2_000_000L, tickWaitsMs = emptyList()),
                framesOut = drawn, bytesOut = drawn * 1_000L, lateFrames = 0, encodeNs = drawn * 8_000_000L,
                dashAcks = 0L, firstFrameConfirmed = confirmed, navSample = navSample, lowFuel = lowFuel, lightScreen = light,
            ),
        ).filterNot { it is Action.Stats }
    }

    private fun maneuverSample(distance: Int?) = StreamGovernor.NavSample(
        maneuverProfileEnabled = true, speedProfileEnabled = false, distanceToManeuverMeters = distance, speedKmh = null,
    )

    /** A started governor, one picture in, then 10 s of the maneuver profile far from the maneuver: the next change of tier is not held back. */
    private fun started(
        pace: Int,
        lightScreenFps: Int = DashConfig.LIGHT_SCREEN_FPS,
        reserveCap: Boolean = true,
        bitrate: Int = bitrateOf(pace),
        manual: Boolean = false,
    ): StreamGovernor = governor(pace, lightScreenFps, reserveCap, bitrate, manual).also {
        it.start(nowMs)
        it.poll(1)
        repeat(41) { _ -> it.poll(1, maneuverSample(distance = 1200)) }
        it.poll(1, maneuverSample(distance = 1200))
    }

    private fun StreamGovernor.reduce(lowFuel: Boolean = false, light: Boolean = false): List<Action> =
        poll(1, maneuverSample(distance = 1450), lowFuel, light)

    private fun StreamGovernor.full(lowFuel: Boolean = false, light: Boolean = false): List<Action> =
        poll(1, maneuverSample(distance = 300), lowFuel, light)

    private fun List<Action>.reserveLogs() = filterIsInstance<Action.Log>().map { it.line }.filter { it.startsWith("reserve:") }

    // ---- the pace of profile A and the exact half of it for B

    @Test
    fun `each pace gives profile A its pace and bitrate and profile B the exact half of both`() {
        for ((pace, a, b) in listOf(Triple(4, 400_000, 200_000), Triple(6, 400_000, 200_000), Triple(8, 600_000, 300_000))) {
            val g = started(pace)
            assertEquals("A at $pace", pace, g.fps)
            assertEquals("the encoder is built for it", pace, g.encoderFps)
            assertEquals("A's bitrate at $pace", a, g.activeBitrateBps)
            val reduced = g.reduce()
            assertEquals("B at $pace is the exact half", pace / 2, g.fps)
            assertEquals(pace / 2, g.profileFps)
            assertTrue("B's bitrate at $pace: got $reduced", Action.SetBitrate(b) in reduced)
            assertEquals("what the encoder is built for stays A's", a, g.activeBitrateBps)
            val back = (1..41).flatMap { _ -> g.full() }
            assertTrue("back to A's bitrate at $pace: got $back", Action.SetBitrate(a) in back)
            assertEquals(pace, g.fps)
        }
    }

    @Test
    fun `the odd half is kept, 6 gives 3, since the table's B is always the exact half`() {
        val g = started(6)
        g.reduce()
        assertEquals(3, g.fps)
        assertEquals(3, g.profileFps)
    }

    @Test
    fun `a heat-capped stream keeps the exact half of the pace it has`() {
        // 8 fps under a moderate heat is 6, and its B is 3
        val g = StreamGovernor(6, lightScreenFps = 6, reducedBitrateBps = 300_000).also { it.start(nowMs); it.poll(1) }
        repeat(41) { _ -> g.poll(1, maneuverSample(distance = 1200)) }
        g.reduce()
        assertEquals(6, g.encoderFps)
        assertEquals(3, g.fps)
    }

    @Test
    fun `the clock ticks at 8 fps in A and at 4 in B, with the idle tick twice that`() {
        val g = governor(8).also { it.start(nowMs) }
        assertEquals(listOf(Action.SetPace(DashCompositor.Pace(125, 250, 0))), g.poll(drawn = 0))
        repeat(41) { _ -> g.poll(0, maneuverSample(distance = 1200)) }
        assertEquals(8, g.fps)
        val reduced = g.reduce()
        assertTrue("got $reduced", Action.SetPace(DashCompositor.Pace(250, 500, 0)) in reduced)
    }

    @Test
    fun `the encoder is built once for A's pace in both profiles, and a change of profile never asks for another`() {
        val g = governor(8).also { it.start(nowMs); it.poll(1); it.poll(1, maneuverSample(distance = 1200)) }
        val all = mutableListOf<Action>()
        for (distance in listOf(1450, 400, 1500, 1750, 300)) {
            repeat(41) { _ -> all += g.poll(1, maneuverSample(distance)) }
            assertEquals("at $distance m", 8, g.encoderFps)
            assertEquals("at $distance m, a rebuild would ask for 8 too", 8, g.rebuildAction().fps)
        }
        assertTrue("got ${all.filterIsInstance<Action.Rebuild>()}", all.none { it is Action.Rebuild })
        assertEquals(listOf(250L, 125L, 250L, 125L), all.filterIsInstance<Action.SetPace>().map { it.pace.activeMs })
    }

    @Test
    fun `a manual stream keeps every mix of the debug build's pace and ceiling as chosen, even where a frame gets fewer bits than the least`() {
        for (fps in VideoChoice.FPS_OPTIONS) for (kbps in VideoChoice.CAP_OPTIONS_KBPS) {
            assertEquals("$fps fps at $kbps kbps", kbps * 1000, governor(fps, bitrate = kbps * 1000, manual = true).activeBitrateBps)
        }
        assertTrue("12 fps at 400 kbps is under the least bits a frame gets", 400_000 / 12 < DashLogic.MIN_BITS_PER_FRAME)
        for (fps in listOf(4, 6, 8)) assertEquals("not manual, $fps fps at 400 kbps", 400_000.coerceAtLeast(DashLogic.minBitrateForFps(fps)), governor(fps, bitrate = 400_000).activeBitrateBps)
    }

    @Test
    fun `the keyframes, A asks for none of its own at its own pace and B has the loop ask once a second`() {
        val g = started(8)
        assertEquals(8, g.gopFps)
        assertEquals(false, DashLogic.paceSyncNeeded(g.fps, g.encoderFps, g.gopFps))
        assertTrue((1..40).flatMap { _ -> g.poll() }.filterIsInstance<Action.RequestSync>().isEmpty())
        g.reduce()
        assertEquals(4, g.fps)
        assertEquals(true, DashLogic.paceSyncNeeded(g.fps, g.encoderFps, g.gopFps))
        val asked = (1..40).flatMap { _ -> g.poll(1, maneuverSample(distance = 1450)) }.filterIsInstance<Action.RequestSync>()
        assertTrue("got ${asked.size}: $asked", asked.size in 9..10)
    }

    @Test
    fun `the profiles text says A and B, pace and target bitrate`() {
        assertEquals("profile A 8 fps / 600 kbps, B 4 fps / 300 kbps", governor(8).profilesText)
        assertEquals("profile A 6 fps / 400 kbps, B 3 fps / 200 kbps", governor(6).profilesText)
        assertEquals("profile A 4 fps / 400 kbps, B 2 fps / 200 kbps", governor(4).profilesText)
        assertEquals("manual 6 fps / 500 kbps, no profile B", governor(6, bitrate = 500_000, manual = true).profilesText)
    }

    // ---- the reserve's ceiling

    @Test
    fun `without the reserve nothing changes, 8 fps stays 8 and the governor says nothing about a reserve`() {
        val g = started(8)
        val actions = (1..40).flatMap { _ -> g.poll(lowFuel = false) }
        assertEquals(8, g.fps)
        assertTrue(actions.reserveLogs().isEmpty())
    }

    @Test
    fun `in the reserve A goes down to 6 fps and 400 kbps at once, with one line, and no encoder is rebuilt`() {
        val g = started(8)
        val entered = g.poll(lowFuel = true)
        assertEquals(6, g.fps)
        assertEquals(6, g.profileFps)
        assertEquals(listOf("reserve: pace capped at 6 fps (was 8)"), entered.reserveLogs())
        assertTrue("got $entered", Action.SetPace(DashCompositor.Pace(167, 334, 0)) in entered)
        assertTrue("got $entered", Action.SetBitrate(400_000) in entered)
        assertTrue(entered.none { it is Action.Rebuild })
        assertEquals("the encoder stays as it was built", 8, g.encoderFps)
        assertEquals(600_000, g.activeBitrateBps)
        assertEquals("a rebuild of a failed encoder would take the capped bitrate", listOf(8, 400_000), g.rebuildAction().let { listOf(it.fps, it.bitrateBps) })
    }

    @Test
    fun `the line comes once on the way in and once on the way out, never at every poll`() {
        val g = started(8)
        assertEquals(1, g.poll(lowFuel = true).reserveLogs().size)
        val inside = (1..40).flatMap { _ -> g.poll(lowFuel = true) }
        assertTrue(inside.reserveLogs().toString(), inside.reserveLogs().isEmpty())
        val left = g.poll(lowFuel = false)
        assertEquals(1, left.reserveLogs().size)
        assertTrue(left.reserveLogs().single(), left.reserveLogs().single().startsWith("reserve: pace cap lifted"))
        val outside = (1..40).flatMap { _ -> g.poll(lowFuel = false) }
        assertTrue(outside.reserveLogs().isEmpty())
    }

    @Test
    fun `entering and leaving give the chosen pace and bitrate back`() {
        val g = started(8)
        g.poll(lowFuel = true)
        assertEquals(6, g.fps)
        val left = g.poll(lowFuel = false)
        assertEquals(8, g.fps)
        assertTrue("got $left", Action.SetPace(DashCompositor.Pace(125, 250, 0)) in left)
        assertTrue("got $left", Action.SetBitrate(600_000) in left)
        assertTrue(left.none { it is Action.Rebuild })
    }

    @Test
    fun `profile B in the reserve is the half of the capped A, 3 fps and 200 kbps, from 8 fps too`() {
        val g = started(8)
        g.reduce()
        assertEquals(4, g.fps)
        val capped = g.reduce(lowFuel = true)
        assertEquals(3, g.fps)
        assertEquals(3, g.profileFps)
        assertTrue("got $capped", Action.SetPace(DashCompositor.Pace(333, 666, 0)) in capped)
        assertTrue("got $capped", Action.SetBitrate(200_000) in capped)
        assertTrue(capped.none { it is Action.Rebuild })
        val lifted = g.reduce(lowFuel = false)
        assertEquals(4, g.fps)
        assertTrue("got $lifted", Action.SetBitrate(300_000) in lifted)
    }

    // ---- the debug switch that turns the reserve's ceiling off

    @Test
    fun `with the cap switched off the reserve changes neither the pace nor the bitrate nor the encoder, and says so once`() {
        for (pace in listOf(8, 12)) {
            val g = started(pace, reserveCap = false)
            val bitrate = bitrateOf(pace)
            val entered = g.poll(lowFuel = true)
            assertEquals("pace $pace stays", pace, g.fps)
            assertEquals(pace, g.profileFps)
            assertEquals(listOf("reserve: cap off by the debug switch (pace stays $pace fps)"), entered.reserveLogs())
            assertTrue("pace $pace: got $entered", entered.none { it is Action.SetPace || it is Action.SetBitrate || it is Action.Rebuild })
            assertEquals(listOf(pace, bitrate), listOf(g.encoderFps, g.activeBitrateBps))
            val inside = (1..40).flatMap { _ -> g.poll(lowFuel = true) }
            assertTrue(inside.reserveLogs().toString(), inside.reserveLogs().isEmpty())
            assertTrue("no change of bitrate inside the reserve: got ${inside.filterIsInstance<Action.SetBitrate>()}", inside.none { it is Action.SetBitrate || it is Action.SetPace })
            assertEquals(pace, g.fps)
            val left = g.poll(lowFuel = false)
            assertEquals(listOf("reserve: over (the cap was off by the debug switch, pace stays $pace fps)"), left.reserveLogs())
            assertEquals(pace, g.fps)
            assertTrue((1..40).flatMap { _ -> g.poll(lowFuel = false) }.reserveLogs().isEmpty())
        }
    }

    @Test
    fun `a governor made without a word about the switch has the cap on, as every release build does`() {
        val g = StreamGovernor(8, bitrateCeilingBps = 600_000, reducedBitrateBps = 300_000, lightScreenFps = 6).also { it.start(nowMs); it.poll(1) }
        val entered = g.poll(lowFuel = true)
        assertEquals(6, g.fps)
        assertEquals(listOf("reserve: pace capped at 6 fps (was 8)"), entered.reserveLogs())
    }

    @Test
    fun `with the cap switched off profile B and the light screens keep their own pace in the reserve`() {
        val g = started(8, reserveCap = false)
        g.poll(lowFuel = true)
        val reduced = g.reduce(lowFuel = true)
        assertEquals("B of 8 stays 4, not the 3 of the capped reserve", 4, g.fps)
        assertTrue("got $reduced", Action.SetBitrate(300_000) in reduced)
        val fast = started(4, lightScreenFps = 10, reserveCap = false)
        fast.poll(lowFuel = true, light = true)
        assertEquals("the light screens are not held at 6", 10, fast.fps)
    }

    @Test
    fun `the reserve with the cap switched off is measured on its own line, at the pace the profile really ran`() {
        val g = started(8, reserveCap = false)
        g.poll(lowFuel = true)
        repeat(8) { _ -> g.poll(lowFuel = true) }
        val inside = g.paceSummary()!!
        assertTrue(inside, "reserve (no cap) 8 fps" in inside)
        assertTrue("it is not the capped reserve's line: $inside", "reserve 6" !in inside.replace("reserve (no cap)", ""))
        g.poll(lowFuel = false)
        repeat(4) { _ -> g.poll(lowFuel = false) }
        assertTrue("outside the reserve the time goes back to A: ${g.paceSummary()}", "A 8 fps" in g.paceSummary()!!)
        val capped = started(8)
        capped.poll(lowFuel = true)
        repeat(8) { _ -> capped.poll(lowFuel = true) }
        assertTrue(capped.paceSummary()!!, "reserve 6 fps" in capped.paceSummary()!! && "(no cap)" !in capped.paceSummary()!!)
    }

    @Test
    fun `the cap switched off is not told again at every poll, and a stream that starts again tells it again`() {
        val g = started(8, reserveCap = false)
        assertEquals(1, g.poll(lowFuel = true).reserveLogs().size)
        assertTrue(g.poll(lowFuel = true).reserveLogs().isEmpty())
        g.start(nowMs)
        assertEquals("a new stream says it again", 1, g.poll(lowFuel = true).reserveLogs().size)
    }

    @Test
    fun `with the cap switched off and no reserve nothing is said, and the cap on says its own lines and never the switch's`() {
        val off = started(8, reserveCap = false)
        assertTrue((1..40).flatMap { _ -> off.poll(lowFuel = false) }.reserveLogs().isEmpty())
        val on = started(8)
        assertEquals(listOf("reserve: pace capped at 6 fps (was 8)"), on.poll(lowFuel = true).reserveLogs())
        assertEquals(6, on.fps)
    }

    @Test
    fun `the light screens never go above 6 in the reserve, and above 6 outside it only by their own pace`() {
        val g = started(8)
        g.poll(light = true)
        assertEquals("outside the reserve the pace is A's", 8, g.fps)
        g.poll(lowFuel = true, light = true)
        assertEquals(6, g.fps)
        // a light-screen pace faster than 6 is held at 6 as well
        val fast = started(4, lightScreenFps = 10)
        fast.poll(light = true)
        assertEquals(10, fast.fps)
        fast.poll(lowFuel = true, light = true)
        assertEquals("the light-screen pace itself is capped", 6, fast.fps)
        assertEquals("the profile's own pace is not touched at 4", 4, fast.profileFps)
        fast.poll(lowFuel = false, light = true)
        assertEquals(10, fast.fps)
    }

    @Test
    fun `4 fps does not change in the reserve, nor 6, in the pace or in the bitrate`() {
        for ((pace, bitrate) in listOf(4 to 400_000, 6 to 400_000)) {
            val g = started(pace)
            val entered = g.poll(lowFuel = true)
            assertEquals(pace, g.fps)
            assertTrue("pace $pace: got $entered", entered.none { it is Action.SetPace || it is Action.SetBitrate || it is Action.Rebuild })
            g.reduce(lowFuel = true)
            assertEquals("B at $pace", pace / 2, g.fps)
            val back = (1..41).flatMap { _ -> g.full(lowFuel = true) }
            assertEquals(pace, g.fps)
            assertTrue("back to A's bitrate $bitrate: got $back", Action.SetBitrate(bitrate) in back)
        }
    }

    @Test
    fun `the window's line reads the capped pace as its target, and the stream's rate stays the chosen one`() {
        val g = started(8)
        g.poll(lowFuel = true)
        val lines = (1..40).flatMap { _ -> g.poll(lowFuel = true) }.filterIsInstance<Action.Log>().map { it.line }
        val window = lines.first { it.startsWith("video fps target=") }
        assertTrue(window, window.startsWith("video fps target=6 "))
        assertTrue(window, "auto=8fps" in window)
    }

    // ---- what each pace cost in bits

    @Test
    fun `the governor adds every poll to the pace in force and says it at the end of the stream`() {
        val g = governor(8).also { it.start(nowMs) }
        assertEquals("nothing sent yet", null, g.paceSummary())
        repeat(8) { _ -> g.poll(1, maneuverSample(distance = 1200)) }
        assertTrue(g.paceSummary()!!, g.paceSummary()!!.startsWith("bitrate by pace: A 8 fps avg 32 kbps"))
        assertFalse("B has no time yet", "B 4" in g.paceSummary()!!)
        g.poll(1, maneuverSample(distance = 1200), lowFuel = true)
        g.poll(1, maneuverSample(distance = 1200), lowFuel = true)
        val line = g.paceSummary()!!
        assertTrue(line, "reserve 6 fps" in line)
    }

    @Test
    fun `B, the light screens and A under the reserve each have their own line`() {
        val g = started(8)
        g.reduce()
        repeat(8) { _ -> g.reduce() }
        assertTrue(g.paceSummary()!!, "B 4 fps" in g.paceSummary()!!)
        g.poll(light = true)
        repeat(4) { _ -> g.poll(light = true) }
        assertTrue(g.paceSummary()!!, "light 8 fps" in g.paceSummary()!!)
        g.poll(lowFuel = true)
        repeat(4) { _ -> g.poll(lowFuel = true) }
        assertTrue(g.paceSummary()!!, "reserve 6 fps" in g.paceSummary()!!)
        val light6 = started(4)
        repeat(4) { _ -> light6.poll(light = true) }
        assertTrue(light6.paceSummary()!!, "light 6 fps" in light6.paceSummary()!!)
    }

    @Test
    fun `a summary comes every 5 minutes of stream, written once for each`() {
        val g = governor(4).also { it.start(nowMs) }
        val lines = (1..(2 * 300_000 / POLL_MS).toInt() + 4).mapNotNull {
            val actions = g.poll(1, maneuverSample(distance = 1200))
            assertTrue("the summary is no action of the governor: $actions", actions.filterIsInstance<Action.Log>().none { l -> l.line.startsWith("bitrate by pace:") })
            g.periodicPaceSummary(nowMs)
        }
        assertEquals(lines.toString(), 2, lines.size)
    }

    // ---- a release build is not manual: profiles A and B, the start ramp and the still halving, as they were

    @Test
    fun `a release stream starts at the half pace and the half bitrate until the dash shows a frame, then goes to A`() {
        val g = governor(4)
        val start = g.start(nowMs)
        assertTrue("got $start", Action.SetPace(DashCompositor.Pace(500, 500, 0)) in start)
        assertTrue("got $start", Action.SetBitrate(200_000) in start)
        val first = g.poll(drawn = 1, confirmed = true)
        assertTrue("got $first", Action.SetPace(DashCompositor.Pace(250, 500, 0)) in first)
        assertTrue("got $first", Action.SetBitrate(400_000) in first)
    }

    @Test
    fun `a release stream halves the bitrate while the picture is still and goes back when it moves`() {
        val g = started(4)
        val still = (1..8).flatMap { _ -> g.poll(drawn = 0) }
        assertTrue("got $still", Action.SetBitrate(200_000) in still)
        assertTrue(Action.SetBitrate(400_000) in g.poll(drawn = 1))
    }

    @Test
    fun `a release stream has profile B at 2 fps and 200 kbps, from the maneuver far away`() {
        val g = started(4)
        val reduced = g.reduce()
        assertEquals(2, g.fps)
        assertTrue("got $reduced", Action.SetBitrate(200_000) in reduced)
    }

    // ---- the debug build's manual choices

    @Test
    fun `a manual stream runs at the chosen pace and ceiling, odd paces and 2 and 3 fps included, and the encoder is built for them`() {
        for (pace in VideoChoice.FPS_OPTIONS) {
            val g = started(pace, bitrate = 700_000, manual = true)
            assertEquals("pace $pace", pace, g.fps)
            assertEquals(pace, g.profileFps)
            assertEquals(pace, g.encoderFps)
            assertEquals(700_000, g.activeBitrateBps)
            assertEquals(pace, g.rebuildAction().fps)
        }
    }

    @Test
    fun `a manual stream is held to the chosen ceiling at 10 and 12 fps, and a release stream is raised to give each frame its bits`() {
        for (pace in listOf(10, 12)) {
            val g = started(pace, bitrate = 400_000, manual = true)
            assertEquals("manual $pace fps at 400", 400_000, g.activeBitrateBps)
            assertEquals(pace, g.encoderFps)
            assertEquals(pace, g.fps)
            assertEquals("a rebuilt encoder takes the chosen ceiling", 400_000, g.rebuildAction().bitrateBps)
            assertEquals("the mix of the rider's 1000", 1_000_000, started(pace, bitrate = 1_000_000, manual = true).activeBitrateBps)
        }
        assertEquals("a ceiling over the dash's own is brought down to it", DashConfig.MAX_STREAM_BITRATE_BPS, governor(8, bitrate = 5_000_000, manual = true).activeBitrateBps)
        assertEquals("not manual: 12 fps at 400 is raised to the 500 that gives a frame its least bits", 500_000, governor(12, bitrate = 400_000).activeBitrateBps)
        assertEquals("and 4 fps is not", 400_000, governor(4, bitrate = 400_000).activeBitrateBps)
    }

    @Test
    fun `the clock ticks at the chosen pace up to 12 fps`() {
        val g = governor(12, bitrate = 800_000, manual = true)
        val start = g.start(nowMs)
        assertTrue("got $start", Action.SetPace(DashCompositor.Pace(83, 250, 0)) in start)
        val g10 = governor(10, bitrate = 700_000, manual = true)
        assertTrue(Action.SetPace(DashCompositor.Pace(100, 250, 0)) in g10.start(nowMs))
    }

    @Test
    fun `a manual stream has no profile B, whatever the navigation says, in the pace or in the bitrate`() {
        for (pace in listOf(4, 6, 10, 12)) {
            val g = started(pace, bitrate = 600_000, manual = true)
            val far = (1..60).flatMap { _ -> g.reduce() }
            assertEquals("pace $pace far from the maneuver", pace, g.fps)
            assertTrue("pace $pace: got $far", far.none { it is Action.SetPace || it is Action.SetBitrate || it is Action.Rebuild })
            assertTrue("no nav line", far.filterIsInstance<Action.Log>().none { it.line.startsWith("nav profile") })
            val speed = StreamGovernor.NavSample(true, true, 5000, 120.0, com.reau.app.dash.NavProfileMode.PESSIMISTA)
            (1..60).forEach { _ -> g.poll(1, speed) }
            assertEquals("pace $pace at speed", pace, g.fps)
            assertEquals(600_000, g.activeBitrateBps)
        }
    }

    @Test
    fun `a manual stream starts at the chosen pace and ceiling, with no ramp at half`() {
        val g = governor(6, bitrate = 500_000, manual = true)
        val start = g.start(nowMs)
        assertTrue("got $start", Action.SetPace(DashCompositor.Pace(167, 334, 0)) in start)
        assertTrue("got $start", Action.SetBitrate(500_000) in start)
        val unconfirmed = g.poll(drawn = 1, confirmed = false)
        assertTrue("before the dash shows a frame: got $unconfirmed", unconfirmed.none { it is Action.SetPace || it is Action.SetBitrate })
        assertEquals(6, g.fps)
    }

    @Test
    fun `a manual stream keeps the chosen ceiling while the picture is still`() {
        val g = started(6, bitrate = 600_000, manual = true)
        val still = (1..40).flatMap { _ -> g.poll(drawn = 0) }
        assertTrue("got $still", still.none { it is Action.SetBitrate })
        assertEquals(6, g.fps)
    }

    @Test
    fun `a manual stream keeps the reserve's ceiling, 6 fps and 400 kbps, and gives the choice back when it ends`() {
        val g = started(8, bitrate = 800_000, manual = true)
        val entered = g.poll(lowFuel = true)
        assertEquals(6, g.fps)
        assertEquals(listOf("reserve: pace capped at 6 fps (was 8)"), entered.reserveLogs())
        assertTrue("got $entered", Action.SetBitrate(400_000) in entered)
        assertTrue(entered.none { it is Action.Rebuild })
        val lifted = g.poll(lowFuel = false)
        assertEquals(8, g.fps)
        assertTrue("got $lifted", Action.SetBitrate(800_000) in lifted)
        val low = started(4, bitrate = 400_000, manual = true)
        val under = low.poll(lowFuel = true)
        assertEquals("a pace under the cap is left as it is", 4, low.fps)
        assertTrue("got $under", under.none { it is Action.SetPace || it is Action.SetBitrate })
    }

    @Test
    fun `a manual stream with the reserve's cap switched off is not capped`() {
        val g = started(8, bitrate = 800_000, reserveCap = false, manual = true)
        val entered = g.poll(lowFuel = true)
        assertEquals(8, g.fps)
        assertTrue("got $entered", entered.none { it is Action.SetPace || it is Action.SetBitrate })
        assertEquals(listOf("reserve: cap off by the debug switch (pace stays 8 fps)"), entered.reserveLogs())
    }

    @Test
    fun `a manual stream keeps the light screens' pace`() {
        val g = started(4, lightScreenFps = 6, manual = true)
        assertEquals(4, g.fps)
        g.poll(light = true)
        assertEquals(6, g.fps)
        g.poll(light = false)
        repeat(60) { _ -> g.poll(light = false) }
        assertEquals(4, g.fps)
    }

    @Test
    fun `a manual stream's summary has the chosen pace as A and never a line of B`() {
        val g = started(10, manual = true)
        repeat(8) { _ -> g.reduce() }
        val line = g.paceSummary()!!
        assertTrue(line, "A 10 fps" in line)
        assertFalse(line, "B " in line.replace("bitrate by pace", ""))
    }

    private companion object {
        const val POLL_MS = 250L
    }
}
