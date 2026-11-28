package com.reau.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The session, the Wi-Fi manager and the video loop need Android, so the lines that wire the fine tail ([com.reau.app.dash.FineTailTest]) are read from
 * the source: it is written wherever the packet trail and the send tail are, inside `runCatching` (a log never takes the projection down), and it is fed by
 * every frame, every TLV sent and every TLV received. None of it changes what is sent to the dash.
 */
class FineTailIsWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private val wifi = source("dash/DashWifiManager.kt")
    private val session = source("dash/DashSession.kt")
    private val live = source("service/LiveSides.kt")
    private val loop = source("stream/VideoLoop.kt")
    private val rtp = source("stream/RtpOutput.kt")

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `a watchdog and a failed session write the detail next to the trail and the tail, inside runCatching`() {
        val log = session.between("private fun logTrail(why: String)", "/** One dash-stats line every")
        assertTrue(log.contains("stats.trail.report(why, now)"))
        assertTrue(log.contains("stats.sendTail.reportOnce(why, open)"))
        assertTrue(log.contains("runCatching { stats.fine.reportOnce(why).forEach { com.reau.app.data.RideDiagnostics.log(\"link\", it) } }"))
        assertTrue("both of the session's causes go through that function", session.contains("logTrail(\"a watchdog fired\")") && session.contains("logTrail(\"the session failed\")"))
    }

    @Test
    fun `the loss of the Wi-Fi writes it with the send tail, before the network is forgotten, inside runCatching`() {
        val lost = wifi.between("private fun bikeLost()", "private fun watchAfterLoss()")
        assertTrue(lost.contains("runCatching { DashHeard.fine?.reportOnce(\"the Wi-Fi was lost\")?.forEach { com.reau.app.data.RideDiagnostics.log(\"wifi\", it) } }"))
        assertTrue(lost.indexOf("DashHeard.tail?.reportOnce(") < lost.indexOf("DashHeard.fine?.reportOnce("))
        assertTrue(lost.indexOf("DashHeard.fine?.reportOnce(") < lost.indexOf("notes.lost()"))
    }

    // 26/11/2026: what the session's stats and the session itself do with a TLV and a frame, and with a new socket, is proved by what they keep (FineTailTest: "the session's
    // stats feed it with every sent TLV and every received one", "the session keeps each frame the video loop tells it" and "a new socket starts the detail over"). What stays text is the two hand-overs that need the
    // service and the video loop: the live sides pass the frame on, and the loop gives the output the session's function.
    @Test
    fun `the live sides pass each frame to the session, and the video loop tells the session each frame`() {
        assertTrue(live.contains("override fun frameSent(bytes: Int, packets: Int, key: Boolean, maxGapNs: Long) = session.frameSent(bytes, packets, key, maxGapNs)"))
        assertTrue(loop.contains("onFrame = dash::frameSent"))
    }

    // 26/11/2026: that the clock of the gaps is read before the packet goes out is proved by RtpOutputTest ("the clock of the gaps is read before each packet goes
    // out"); what stays text is the absence of any wait in the function that watches the gap, which no clock of a test can show.
    @Test
    fun `the function that watches the gaps between packets does not wait and sends nothing`() {
        val watch = rtp.between("private fun watchGap()", "// All NALs of one frame")
        assertTrue(watch.contains("nanoTime()"))
        assertFalse(watch, watch.contains("sleep") || watch.contains("delay") || watch.contains("sendRtp"))
    }
}
