package com.reau.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The debug build's log of the picture classifier (item 15a (iii), 08/11/2026): the class when it changes, the p when the net is not sure, never a flood. */
class LookAuditTest {
    @Test
    fun `the first look is written, with nothing before it`() {
        assertEquals("picture look: MAP p=0.995 (was nothing)", LookAudit().line(PictureLook.Kind.MAP, 0.995f, 0))
    }

    @Test
    fun `a sure look of the same class says nothing`() {
        val audit = LookAudit()
        audit.line(PictureLook.Kind.MAP, 0.999f, 0)
        assertNull(audit.line(PictureLook.Kind.MAP, 0.99f, 250))
        assertNull(audit.line(PictureLook.Kind.MAP, 0.98f, 500))
    }

    @Test
    fun `a change of class is written at once, with the class it was and the p`() {
        val audit = LookAudit()
        audit.line(PictureLook.Kind.MAP, 0.999f, 0)
        assertEquals("picture look: MENU p=0.981 (was MAP)", audit.line(PictureLook.Kind.MENU, 0.981f, 250))
        assertEquals("picture look: UNSURE p=0.640 (was MENU, p under 0.98)", audit.line(PictureLook.Kind.UNSURE, 0.64f, 500))
    }

    @Test
    fun `a look below 0 98 is written even when the class is the same, once a second at most, with the count of the ones left out`() {
        val audit = LookAudit()
        audit.line(PictureLook.Kind.MAP, 0.999f, 0)
        assertEquals("picture look: MAP p=0.940 (p under 0.98)", audit.line(PictureLook.Kind.MAP, 0.94f, 1_000))
        assertNull(audit.line(PictureLook.Kind.MAP, 0.93f, 1_250))
        assertNull(audit.line(PictureLook.Kind.MAP, 0.95f, 1_750))
        assertEquals("picture look: MAP p=0.920 (p under 0.98; 2 looks not written)", audit.line(PictureLook.Kind.MAP, 0.92f, 2_000))
    }

    @Test
    fun `a black picture gated by the net says its class once, not at every look`() {
        val audit = LookAudit()
        audit.line(PictureLook.Kind.MAP, 0.999f, 0)
        assertEquals("picture look: UNSURE p=0.000 (was MAP)", audit.line(PictureLook.Kind.UNSURE, 0f, 250))
        assertNull(audit.line(PictureLook.Kind.UNSURE, 0f, 500))
        assertNull(audit.line(PictureLook.Kind.UNSURE, 0f, 5_000))
    }

    @Test
    fun `the lines are limited and the last one says so`() {
        val audit = LookAudit(maxLines = 2)
        assertTrue(audit.line(PictureLook.Kind.MAP, 0.999f, 0)!!.startsWith("picture look: MAP"))
        assertTrue(audit.line(PictureLook.Kind.MENU, 0.999f, 250)!!.startsWith("picture look: MENU"))
        assertEquals("picture look: 2 lines written, no more in this session", audit.line(PictureLook.Kind.MAP, 0.999f, 500))
        assertNull(audit.line(PictureLook.Kind.MENU, 0.999f, 750))
        assertNull(audit.line(PictureLook.Kind.MAP, 0.5f, 5_000))
    }

    @Test
    fun `the limit is 0 98, the study's candidate for the net, not the abstention limit of 0 9`() {
        assertEquals(0.98f, LookAudit.LOW_P, 0f)
        assertTrue(LookAudit.LOW_P > ScreenNet.MIN_CONFIDENCE)
    }

    @Test
    fun `PictureLook hands the net's own answer back, class and confidence`() {
        val into = ScreenNet.Result()
        val kind = PictureLook.of(ByteArray(PictureLook.SAMPLE_W * PictureLook.SAMPLE_H * 4), PictureLook.SAMPLE_W, PictureLook.SAMPLE_H, into)
        assertEquals("a black picture is gated", PictureLook.Kind.UNSURE, kind)
        assertEquals(0f, into.confidence, 0f)
        assertEquals(PictureLook.Kind.UNSURE, PictureLook.of(ByteArray(3), 2, 2, into))
    }

    // ── the minute's limit (15/11/2026): a long ride is covered, and what is left out is counted ──

    /** Looks at 250 ms, one a second written at most, [perMinute] a minute: the lines written and the text of each. */
    private fun run(audit: LookAudit, kind: PictureLook.Kind, p: Float, fromMs: Long, toMs: Long): List<String> =
        (fromMs..toMs step 250L).mapNotNull { audit.line(kind, p, it) }

    @Test
    fun `a minute holds at most its limit of lows of the same class, and the next minute's first line counts the ones left out`() {
        val audit = LookAudit(lowPerMinute = 3)
        audit.line(PictureLook.Kind.MAP, 0.999f, 0)
        val first = run(audit, PictureLook.Kind.MAP, 0.95f, 1_000, 59_750)
        assertEquals("3 lines in the minute, not one a second", 3, first.size)
        val next = audit.line(PictureLook.Kind.MAP, 0.95f, 61_000)
        assertEquals("picture look: MAP p=0.950 (p under 0.98; 227 looks not written)", next)
    }

    @Test
    fun `the lows left out because of the minute are counted with the ones left out because of the second`() {
        val audit = LookAudit(lowPerMinute = 2)
        audit.line(PictureLook.Kind.MAP, 0.999f, 0)
        assertTrue(audit.line(PictureLook.Kind.MAP, 0.95f, 1_000)!!.startsWith("picture look: MAP p=0.950 (p under 0.98)"))
        assertNull("the second", audit.line(PictureLook.Kind.MAP, 0.95f, 1_250))
        assertTrue(audit.line(PictureLook.Kind.MAP, 0.95f, 2_250)!!.contains("1 looks not written"))
        assertNull("the minute is full", audit.line(PictureLook.Kind.MAP, 0.95f, 3_500))
        assertEquals("picture look: MAP p=0.940 (p under 0.98; 1 looks not written)", audit.line(PictureLook.Kind.MAP, 0.94f, 61_000))
    }

    @Test
    fun `a class change is written even when the minute is full`() {
        val audit = LookAudit(lowPerMinute = 1)
        audit.line(PictureLook.Kind.MAP, 0.999f, 0)
        assertTrue(audit.line(PictureLook.Kind.MAP, 0.95f, 1_000)!!.startsWith("picture look: MAP"))
        assertNull(audit.line(PictureLook.Kind.MAP, 0.95f, 2_500))
        assertEquals("picture look: MENU p=0.990 (was MAP; 1 looks not written)", audit.line(PictureLook.Kind.MENU, 0.99f, 3_000))
        assertEquals("picture look: MAP p=0.960 (was MENU, p under 0.98)", audit.line(PictureLook.Kind.MAP, 0.96f, 3_250))
    }

    @Test
    fun `the looks left out are all of the class of the line before, because a change of class is always written`() {
        val audit = LookAudit(lowPerMinute = 1)
        audit.line(PictureLook.Kind.UNSURE, 0.85f, 0)
        assertNull(audit.line(PictureLook.Kind.UNSURE, 0.85f, 1_500))
        assertNull(audit.line(PictureLook.Kind.UNSURE, 0.86f, 2_500))
        // the class changes: the line is written at once and carries the 2 UNSURE looks left out before it, not its own class
        assertEquals("picture look: MAP p=0.950 (was UNSURE, p under 0.98; 2 looks not written)", audit.line(PictureLook.Kind.MAP, 0.95f, 3_000))
        assertNull(audit.line(PictureLook.Kind.MAP, 0.95f, 3_500))
        assertEquals("picture look: MAP p=0.950 (p under 0.98; 1 looks not written)", audit.line(PictureLook.Kind.MAP, 0.95f, 61_000))
    }

    @Test
    fun `an hour of a net that is never sure writes the minute's limit, is not cut, and costs about 140 KB`() {
        val audit = LookAudit()
        val lines = run(audit, PictureLook.Kind.UNSURE, 0.85f, 0, 3_600_000)
        assertTrue("no stop line in an hour", lines.none { "no more in this session" in it })
        assertEquals("1 200 lows (20 a minute for 60 minutes) and the first look, which is a class change", 1_201, lines.size)
        // with the log's prefix (time, offset, tag) in front of each, as the ride log writes them
        val bytes = lines.sumOf { it.length + 1 + "08:31:21.854  + 3599999ms  [compositor] ".length }
        assertTrue("$bytes bytes", bytes in 120_000..160_000)
    }

    @Test
    fun `the 52 minutes of a Spotify night are not cut at 14 as the old limit of 300 lines did`() {
        val audit = LookAudit()
        val lines = run(audit, PictureLook.Kind.UNSURE, 0.7f, 0, 52 * 60_000L)
        assertTrue(lines.none { "no more in this session" in it })
        assertEquals("20 a minute for 52 minutes and the look that opens the 53rd", 52 * 20 + 1, lines.size)
    }

    @Test
    fun `the ceiling still stops the lines of a ride that never ends, and says so once`() {
        val audit = LookAudit()
        var stops = 0
        var last: String? = null
        for (at in 0L..(4 * 3_600_000L) step 250L) {
            val line = audit.line(PictureLook.Kind.UNSURE, 0.7f, at) ?: continue
            if ("no more in this session" in line) stops++
            last = line
        }
        assertEquals(1, stops)
        assertEquals("picture look: ${LookAudit.MAX_LINES} lines written, no more in this session", last)
    }

    @Test
    fun `the limits are the minute's 20 lows and the 3 000 lines in all`() {
        assertEquals(20, LookAudit.LOW_PER_MINUTE)
        assertEquals(3_000, LookAudit.MAX_LINES)
        assertEquals(1_000L, LookAudit.LOW_GAP_MS)
    }
}
