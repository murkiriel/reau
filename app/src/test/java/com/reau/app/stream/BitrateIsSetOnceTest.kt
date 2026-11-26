package com.reau.app.stream

import com.reau.app.stream.StreamGovernor.Action
import com.reau.app.video.DashCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan 35, step G (26/11/2026): the governor sets the bitrate ONCE per stream, in [StreamGovernor.start], and no poll ever does. The guard that asked at every poll
 * whether the bitrate had moved could not fire: the bitrate is made of the ceiling, the encoder's rate and a constant, all fixed when the governor is made. The ride
 * logs of 25/11/2026 agree: 20 streams, 20 `bitrate=` lines (one per `video loop start`), no `encoder rebuilt`, `set=400k` in every window, on the 6 fps CBR phone and on the
 * 4 fps MediaTek pilots that the encoder runs as VBR and whose windows say `short: kbps 460` (a live verdict, which tells and changes nothing).
 */
class BitrateIsSetOnceTest {
    private var nowMs = 0L

    private fun governor(fullFps: Int, ceilingBps: Int) = StreamGovernor(fullFps, bitrateCeilingBps = ceilingBps, keyframeSec = 1, lightScreenFps = 6)

    /** One poll 250 ms after the one before: 2 new pictures and 20 000 bytes (640 kbps, over any ceiling of the app) unless said otherwise. */
    private fun StreamGovernor.poll(
        drawn: Int = 2,
        bytes: Long = 20_000L,
        navSample: StreamGovernor.NavSample? = null,
        light: Boolean = false,
        thermalOk: Boolean = true,
        fpsTest: Int? = null,
    ): List<Action> {
        nowMs += POLL_MS
        return onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs,
                compositor = DashCompositor.Stats(sourceFrames = drawn, drawn = drawn, repeated = 0, composeNs = drawn * 2_000_000L, tickWaitsMs = emptyList()),
                framesOut = drawn, bytesOut = bytes, lateFrames = 0, encodeNs = drawn * 8_000_000L, dashAcks = 0L, firstFrameConfirmed = true,
                thermalOk = thermalOk, navSample = navSample, lightScreen = light, fpsTest = fpsTest, fpsTestAsked = fpsTest,
            ),
        )
    }

    private fun maneuver(distance: Int) = StreamGovernor.NavSample(
        maneuverProfileEnabled = true, speedProfileEnabled = false, distanceToManeuverMeters = distance, speedKmh = null,
    )

    /** Everything that moves a pace in a ride: profile B and back (a change each 12 s), a still picture, the light screens, the heat and the test card. */
    private fun ride(g: StreamGovernor): List<Action> = buildList {
        repeat(4) { round ->
            repeat(48) { addAll(g.poll(navSample = maneuver(if (round % 2 == 0) 1_450 else 300))) }
            repeat(40) { addAll(g.poll(drawn = 0, bytes = 0L)) }
            repeat(40) { addAll(g.poll(light = true)) }
            repeat(40) { addAll(g.poll(thermalOk = false)) }
            repeat(40) { addAll(g.poll(fpsTest = 6)) }
            repeat(8) { addAll(g.poll()) }
        }
    }

    @Test
    fun `no poll of any pace sets the bitrate, at 6 and 3 fps of the phone and at 4 and 2 of the pilots, over budget or not`() {
        for (fullFps in listOf(6, 4)) for (ceiling in listOf(400_000, 200_000)) {
            val g = governor(fullFps, ceiling)
            val expected = g.activeBitrateBps
            val start = g.start(nowMs)
            val said = "$fullFps fps, ceiling $ceiling"
            assertEquals(said, listOf(Action.SetBitrate(expected)), start.filterIsInstance<Action.SetBitrate>())
            val all = ride(g)
            assertTrue("$said: got ${all.filterIsInstance<Action.SetBitrate>()}", all.none { it is Action.SetBitrate })
            assertEquals(said, expected, g.activeBitrateBps)
            // A rebuild (the test card above the encoder's rate asks for one) carries the same bitrate, so nothing set it again either.
            all.filterIsInstance<Action.Rebuild>().forEach { assertEquals(said, expected, it.bitrateBps) }
            all.filterIsInstance<Action.Log>().map { it.line }.filter { it.startsWith("video fps ") }.forEach {
                assertTrue("$said: $it", " set=${expected / 1000}k " in it)
            }
        }
    }

    @Test
    fun `a window over the ceiling says so in its verdict and moves nothing, the encoder that runs VBR and the bits it sent`() {
        // The MediaTek pilots: 4 fps at the 400 kbps ceiling, 640 kbps measured. The verdict is the live part of the bitrate in the governor.
        val g = governor(4, 400_000).also { it.start(nowMs) }
        val lines = (1..60).flatMap { g.poll() }
        val verdicts = lines.filterIsInstance<Action.Log>().map { it.line }.filter { it.startsWith("video fps ") }
        assertTrue("got $verdicts", verdicts.isNotEmpty() && verdicts.all { "[short: kbps 640" in it || "[not judged]" in it })
        assertTrue("got $verdicts", verdicts.any { "[short: kbps 640" in it })
        assertTrue("and it asked for nothing: got ${lines.filterNot { it is Action.Log || it is Action.Stats }}", lines.none { it is Action.SetBitrate || it is Action.Rebuild })
    }

    @Test
    fun `a stream that starts again sets the bitrate once more, once`() {
        val g = governor(6, 400_000)
        repeat(3) {
            assertEquals(listOf(Action.SetBitrate(400_000)), g.start(nowMs).filterIsInstance<Action.SetBitrate>())
            assertTrue((1..20).flatMap { g.poll() }.none { it is Action.SetBitrate })
        }
    }

    // ---- the source: one place makes the action, and it is not the poll

    private val source = File("src/main/java/com/reau/app/stream/StreamGovernor.kt").readText()

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `only the start of the stream builds the action, the poll has no bitrate in it`() {
        assertEquals("the one place that makes the action", 1, Regex("""Action\.SetBitrate\(""").findAll(source).count())
        val start = source.between("fun start(nowMs: Long): List<Action> {", "/** A keyframe was asked for by someone else")
        assertEquals(1, Regex("""startBitrate\(\)""").findAll(start).count())
        val poll = source.between("fun onPoll(p: Poll): List<Action> {", "The frames and bytes sent in the last second")
        assertFalse(poll, poll.contains("SetBitrate") || poll.contains("startBitrate") || poll.contains("bitrateBps ="))
    }

    private companion object {
        const val POLL_MS = 250L
    }
}
