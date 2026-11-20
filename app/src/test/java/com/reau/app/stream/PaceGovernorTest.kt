package com.reau.app.stream

import com.reau.app.dash.DashConfig
import com.reau.app.stream.StreamGovernor.Action
import com.reau.app.video.DashCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixed video of plan 32 (20/11/2026) as the stream governor lives it, in a debug and in a release build alike: profile A is 6 fps at 400 kbps,
 * profile B (the navigation's reduced tier) is the exact half of the pace, 3 fps, at the SAME 400 kbps (a tier, a still picture and the start ramp
 * change the pace and never the bits), the start ramp stays, the light screens are never above 6 fps, and none of it ever rebuilds the encoder. The
 * reserve has no ceiling any more (the most any pace is, 6 fps, is the ceiling the reserve used to have). What reaches the governor from the settings
 * is VideoChoiceStoreTest's.
 */
class PaceGovernorTest {
    private var nowMs = 0L

    private fun governor(pace: Int = A_FPS, lightScreenFps: Int = DashConfig.LIGHT_SCREEN_FPS, bitrate: Int = A_BPS): StreamGovernor =
        StreamGovernor(pace, bitrateCeilingBps = bitrate, keyframeSec = DashConfig.PROFILE_KEYFRAME_SEC, lightScreenFps = lightScreenFps)

    /** One poll 250 ms after the one before, with [drawn] new pictures (the stream moves when there are some). */
    private fun StreamGovernor.poll(
        drawn: Int = 1,
        navSample: StreamGovernor.NavSample? = null,
        light: Boolean = false,
        confirmed: Boolean = true,
    ): List<Action> {
        nowMs += POLL_MS
        return onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs,
                compositor = DashCompositor.Stats(sourceFrames = drawn * 2, drawn = drawn, repeated = 0, composeNs = drawn * 2_000_000L, tickWaitsMs = emptyList()),
                framesOut = drawn, bytesOut = drawn * 1_000L, lateFrames = 0, encodeNs = drawn * 8_000_000L,
                dashAcks = 0L, firstFrameConfirmed = confirmed, navSample = navSample, lightScreen = light,
            ),
        ).filterNot { it is Action.Stats }
    }

    private fun maneuverSample(distance: Int?) = StreamGovernor.NavSample(
        maneuverProfileEnabled = true, speedProfileEnabled = false, distanceToManeuverMeters = distance, speedKmh = null,
    )

    /** A started governor, one picture in, then 10 s of the maneuver profile far from the maneuver: the next change of tier is not held back. */
    private fun started(pace: Int = A_FPS, lightScreenFps: Int = DashConfig.LIGHT_SCREEN_FPS): StreamGovernor = governor(pace, lightScreenFps).also {
        it.start(nowMs)
        it.poll(1)
        repeat(41) { _ -> it.poll(1, maneuverSample(distance = 1200)) }
        it.poll(1, maneuverSample(distance = 1200))
    }

    private fun StreamGovernor.reduce(light: Boolean = false): List<Action> = poll(1, maneuverSample(distance = 1450), light)

    private fun StreamGovernor.full(light: Boolean = false): List<Action> = poll(1, maneuverSample(distance = 300), light)

    // ---- profile A, and the exact half of its pace for B at the same bitrate

    @Test
    fun `the fixed video is 6 fps and 400 kbps for A, 3 fps for B`() {
        assertEquals(listOf(6, 400_000, 3), listOf(DashConfig.PROFILE_FULL_FPS, DashConfig.PROFILE_FULL_BPS, DashConfig.PROFILE_REDUCED_FPS))
        val g = started()
        assertEquals(A_FPS, g.fps)
        assertEquals("the encoder is built for it", A_FPS, g.encoderFps)
        assertEquals(A_BPS, g.activeBitrateBps)
        val reduced = g.reduce()
        assertEquals(B_FPS, g.fps)
        assertEquals(B_FPS, g.profileFps)
        assertEquals("what the encoder is built for stays A's", A_FPS, g.encoderFps)
        assertTrue("got $reduced", Action.SetPace(DashCompositor.Pace(333, 666, 0)) in reduced)
    }

    @Test
    fun `profile B keeps the 400 kbps of A, going to it and coming back never touches the bitrate`() {
        val g = started()
        val all = g.reduce() + (1..60).flatMap { _ -> g.reduce() } + (1..60).flatMap { _ -> g.full() }
        assertEquals(A_FPS, g.fps)
        assertTrue("no bitrate was set by a change of tier: got ${all.filterIsInstance<Action.SetBitrate>()}", all.none { it is Action.SetBitrate })
        assertEquals(A_BPS, g.activeBitrateBps)
    }

    @Test
    fun `a heat-capped stream keeps the exact half of the pace it has`() {
        // under a moderate heat the whole stream is 4 fps, and its B is 2
        val g = StreamGovernor(4, lightScreenFps = 6).also { it.start(nowMs); it.poll(1) }
        repeat(41) { _ -> g.poll(1, maneuverSample(distance = 1200)) }
        g.reduce()
        assertEquals(4, g.encoderFps)
        assertEquals(2, g.fps)
    }

    @Test
    fun `the clock ticks at 6 fps in A and at 3 in B, with the idle tick twice that`() {
        val g = governor().also { it.start(nowMs) }
        assertEquals(listOf(Action.SetPace(DashCompositor.Pace(167, 334, 0))), g.poll(drawn = 0))
        repeat(41) { _ -> g.poll(0, maneuverSample(distance = 1200)) }
        assertEquals(A_FPS, g.fps)
        val reduced = g.reduce()
        assertTrue("got $reduced", Action.SetPace(DashCompositor.Pace(333, 666, 0)) in reduced)
    }

    @Test
    fun `the encoder is built once for A's pace in both profiles, and a change of profile never asks for another`() {
        val g = governor().also { it.start(nowMs); it.poll(1); it.poll(1, maneuverSample(distance = 1200)) }
        val all = mutableListOf<Action>()
        for (distance in listOf(1450, 400, 1500, 1750, 300)) {
            repeat(41) { _ -> all += g.poll(1, maneuverSample(distance)) }
            assertEquals("at $distance m", A_FPS, g.encoderFps)
            assertEquals("at $distance m, a rebuild would ask for 6 too", A_FPS, g.rebuildAction().fps)
            assertEquals("and for the same bitrate", A_BPS, g.rebuildAction().bitrateBps)
        }
        assertTrue("got ${all.filterIsInstance<Action.Rebuild>()}", all.none { it is Action.Rebuild })
        assertEquals(listOf(333L, 167L, 333L, 167L), all.filterIsInstance<Action.SetPace>().map { it.pace.activeMs })
    }

    @Test
    fun `the keyframes, A asks for none of its own at its own pace and B has the loop ask once a second`() {
        val g = started()
        assertEquals(A_FPS, g.gopFps)
        assertEquals(false, DashLogic.paceSyncNeeded(g.fps, g.encoderFps, g.gopFps))
        assertTrue((1..40).flatMap { _ -> g.poll() }.filterIsInstance<Action.RequestSync>().isEmpty())
        g.reduce()
        assertEquals(B_FPS, g.fps)
        assertEquals(true, DashLogic.paceSyncNeeded(g.fps, g.encoderFps, g.gopFps))
        val asked = (1..40).flatMap { _ -> g.poll(1, maneuverSample(distance = 1450)) }.filterIsInstance<Action.RequestSync>()
        assertTrue("got ${asked.size}: $asked", asked.size in 9..10)
    }

    @Test
    fun `the profiles text says A and B, pace and bitrate, the same bitrate`() {
        assertEquals("profile A 6 fps / 400 kbps, B 3 fps / 400 kbps", governor().profilesText)
        assertEquals("profile A 4 fps / 400 kbps, B 2 fps / 400 kbps", governor(4).profilesText)
    }

    // ---- the start ramp stays, the halving of the bitrate does not

    @Test
    fun `a stream starts at the half pace until the dash shows a frame, then goes to A, at 400 kbps all the time`() {
        val g = governor()
        val start = g.start(nowMs)
        assertTrue("got $start", Action.SetPace(DashCompositor.Pace(334, 334, 0)) in start)
        assertTrue("the bitrate is the ceiling from the first frame: got $start", Action.SetBitrate(A_BPS) in start)
        val waiting = (1..8).flatMap { _ -> g.poll(drawn = 1, confirmed = false) }
        assertTrue("before the dash shows a frame: got $waiting", waiting.none { it is Action.SetPace || it is Action.SetBitrate })
        val first = g.poll(drawn = 1, confirmed = true)
        assertTrue("got $first", Action.SetPace(DashCompositor.Pace(167, 334, 0)) in first)
        assertTrue("and no new bitrate: got $first", first.none { it is Action.SetBitrate })
    }

    @Test
    fun `a still picture never halves the bitrate, 40 seconds of it change neither pace nor bitrate`() {
        val g = started()
        val still = (1..160).flatMap { _ -> g.poll(drawn = 0) }
        assertTrue("got $still", still.none { it is Action.SetBitrate || it is Action.SetPace || it is Action.Rebuild })
        assertEquals(A_BPS, g.activeBitrateBps)
        assertTrue("and the picture coming back sets nothing", g.poll(drawn = 1).none { it is Action.SetBitrate })
    }

    @Test
    fun `the bitrate is set once, at the start, and the window's line says it`() {
        val g = governor().also { it.start(nowMs) }
        val lines = (1..40).flatMap { _ -> g.poll(1, maneuverSample(distance = 1200)) }
        assertTrue("got ${lines.filterIsInstance<Action.SetBitrate>()}", lines.none { it is Action.SetBitrate })
        val window = lines.filterIsInstance<Action.Log>().map { it.line }.first { it.startsWith("video fps target=") }
        assertTrue(window, window.startsWith("video fps target=6 ") && " set=400k " in window && "auto=6fps" in window)
    }

    // ---- the light screens are never above 6 fps

    @Test
    fun `a light screen takes B from 3 up to 6, and A stays at 6`() {
        val g = started()
        assertEquals(A_FPS, g.fps)
        g.poll(light = true)
        assertEquals(A_FPS, g.fps)
        repeat(4) { _ -> g.poll(light = false) } // past the hold of the light pace
        g.reduce()
        assertEquals(B_FPS, g.fps)
        val lit = g.reduce(light = true)
        assertEquals("the menu in B goes up to 6", 6, g.fps)
        assertTrue("got $lit", lit.filterIsInstance<Action.Log>().any { it.line == "light screen: pace up to 6 fps (the app menu or an app of Android Auto)" })
        repeat(60) { _ -> g.reduce(light = false) }
        assertEquals(B_FPS, g.fps)
    }

    @Test
    fun `a light screen is never above 6 fps, whatever pace it is asked for`() {
        for (asked in listOf(6, 8, 10, 12, 30)) {
            val g = started(lightScreenFps = asked)
            g.poll(light = true)
            assertEquals("asked $asked on A", A_FPS, g.fps)
            g.reduce(light = true)
            assertEquals("asked $asked on B", 6, g.fps)
        }
        assertEquals(6, LightScreen.fps(base = 3, light = 8))
        assertEquals(6, LightScreen.fps(base = 6, light = 12))
        assertEquals(6, LightScreen.fps(base = 3, light = 6))
        assertEquals("the light pace never lowers the profile's", 4, LightScreen.fps(base = 4, light = 2))
    }

    @Test
    fun `the light screens' pace changes only the pace, never the bitrate or the encoder`() {
        val g = started()
        g.reduce()
        val up = g.reduce(light = true)
        val down = (1..60).flatMap { _ -> g.reduce(light = false) }
        assertTrue("got ${up + down}", (up + down).none { it is Action.SetBitrate || it is Action.Rebuild })
        assertEquals(A_BPS, g.activeBitrateBps)
    }

    // ---- no reserve ceiling

    @Test
    fun `the governor says nothing about a reserve, and the pace in it is the pace outside it`() {
        val g = started()
        val actions = (1..80).flatMap { _ -> g.poll() }
        assertEquals(A_FPS, g.fps)
        assertTrue(actions.filterIsInstance<Action.Log>().none { it.line.startsWith("reserve") })
    }

    // ---- what each pace cost in bits

    @Test
    fun `the governor adds every poll to the pace in force and says it at the end of the stream`() {
        val g = governor().also { it.start(nowMs) }
        assertEquals("nothing sent yet", null, g.paceSummary())
        repeat(8) { _ -> g.poll(1, maneuverSample(distance = 1200)) }
        assertTrue(g.paceSummary()!!, g.paceSummary()!!.startsWith("bitrate by pace: A 6 fps avg 32 kbps"))
        assertFalse("B has no time yet", "B 3" in g.paceSummary()!!)
    }

    @Test
    fun `B and the light screens each have their own line`() {
        val g = started()
        g.reduce()
        repeat(8) { _ -> g.reduce() }
        assertTrue(g.paceSummary()!!, "B 3 fps" in g.paceSummary()!!)
        g.reduce(light = true)
        repeat(4) { _ -> g.reduce(light = true) }
        assertTrue(g.paceSummary()!!, "light 6 fps" in g.paceSummary()!!)
        assertFalse(g.paceSummary()!!, "reserve" in g.paceSummary()!!)
    }

    @Test
    fun `a summary comes every 5 minutes of stream, written once for each`() {
        val g = governor().also { it.start(nowMs) }
        val lines = (1..(2 * 300_000 / POLL_MS).toInt() + 4).mapNotNull {
            val actions = g.poll(1, maneuverSample(distance = 1200))
            assertTrue("the summary is no action of the governor: $actions", actions.filterIsInstance<Action.Log>().none { l -> l.line.startsWith("bitrate by pace:") })
            g.periodicPaceSummary(nowMs)
        }
        assertEquals(lines.toString(), 2, lines.size)
    }

    private companion object {
        const val POLL_MS = 250L
        const val A_FPS = 6
        const val B_FPS = 3
        const val A_BPS = 400_000
    }
}
