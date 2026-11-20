package com.reau.app.stream

import com.reau.app.stream.PaceBitrateLog.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each pace really cost in bits ([PaceBitrateLog]): the totals per pace, the periodic line, the line at the end, and no division by zero. */
class PaceBitrateLogTest {
    private fun log(everyMs: Long = 300_000L) = PaceBitrateLog(everyMs).also { it.start(0) }

    @Test
    fun `before any time there is nothing to say`() {
        val l = log()
        assertNull(l.line())
        assertNull("a poll with no time since the start adds nothing", l.run { add(Kind.A, 4, 1, 1_000, 0); line() })
    }

    @Test
    fun `the average is bytes times 8 over the time, and the fps and minutes are of the time at that pace`() {
        val l = log()
        // 60 s at 8 fps: 480 frames, 3 840 000 bytes = 512 kbps
        for (i in 1..240) l.add(Kind.A, 8, frames = 2, bytes = 16_000, nowMs = i * 250L)
        assertEquals("bitrate by pace: A 8 fps avg 512 kbps 8.0 fps sent 1.0 min", l.line())
    }

    @Test
    fun `a change of pace puts the following polls in the new line, in the order A, B, light`() {
        val l = log()
        var now = 0L
        fun poll(kind: Kind, fps: Int, bytes: Long) { now += 1_000; l.add(kind, fps, frames = fps, bytes = bytes, nowMs = now) }
        poll(Kind.LIGHT, 6, 36_250)
        poll(Kind.B, 4, 25_625)
        poll(Kind.A, 8, 64_000)
        poll(Kind.A, 8, 64_000)
        poll(Kind.B, 4, 25_625)
        val line = l.line()!!
        assertEquals(
            "bitrate by pace: A 8 fps avg 512 kbps 8.0 fps sent 0.0 min; B 4 fps avg 205 kbps 4.0 fps sent 0.0 min; " +
                "light 6 fps avg 290 kbps 6.0 fps sent 0.0 min",
            line,
        )
    }

    @Test
    fun `the same kind at another rate is another line`() {
        val l = log()
        l.add(Kind.B, 4, 4, 25_000, 1_000)
        l.add(Kind.B, 3, 3, 25_000, 2_000)
        val line = l.line()!!
        assertTrue(line, "B 3 fps" in line && "B 4 fps" in line)
    }

    @Test
    fun `a clock that does not move or goes back adds nothing`() {
        val l = log()
        l.add(Kind.A, 4, 1, 1_000, 1_000)
        l.add(Kind.A, 4, 1, 1_000, 1_000)
        l.add(Kind.A, 4, 1, 1_000, 500)
        assertEquals("bitrate by pace: A 4 fps avg 8 kbps 1.0 fps sent 0.0 min", l.line())
    }

    @Test
    fun `the periodic line comes when the interval is over, then the interval starts again`() {
        val l = log(everyMs = 10_000)
        for (i in 1..9) l.add(Kind.A, 4, 4, 2_000, i * 1_000L)
        assertNull(l.periodicLine(9_999))
        l.add(Kind.A, 4, 4, 2_000, 10_000)
        val first = l.periodicLine(10_000)
        assertTrue(first.toString(), first!!.startsWith("bitrate by pace: A 4 fps avg 16 kbps"))
        assertNull("the next one is another interval away", l.periodicLine(15_000))
        l.add(Kind.B, 2, 2, 1_000, 20_000)
        val second = l.periodicLine(20_000)!!
        assertTrue("the totals are since the start, not since the last line: $second", "A 4 fps" in second && "B 2 fps" in second)
    }

    @Test
    fun `the line at the end is the whole stream, and a new stream starts the totals over`() {
        val l = log()
        l.add(Kind.A, 4, 4, 2_000, 1_000)
        assertTrue(l.line()!!.contains("A 4 fps"))
        l.start(100_000)
        assertNull(l.line())
        l.add(Kind.B, 2, 2, 1_000, 101_000)
        assertEquals("bitrate by pace: B 2 fps avg 8 kbps 2.0 fps sent 0.0 min", l.line())
    }

    @Test
    fun `a summary every 5 minutes is the default`() {
        assertEquals(300_000L, PaceBitrateLog.SUMMARY_EVERY_MS)
        val l = PaceBitrateLog().also { it.start(0) }
        l.add(Kind.A, 4, 4, 2_000, 299_000)
        assertNull(l.periodicLine(299_999))
        assertTrue(l.periodicLine(300_000) != null)
    }

    @Test
    fun `the video loop writes the line at the end of the stream and the start line says both profiles`() {
        val loop = java.io.File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()
        assertTrue(loop.contains("ending.paceSummary()?.let(log)"))
        assertTrue(loop.contains("g.periodicPaceSummary(polledAtMs)?.let(log)"))
        assertTrue(loop.contains("(ceiling \$ceiling, \${g.profilesText})"))
    }
}
