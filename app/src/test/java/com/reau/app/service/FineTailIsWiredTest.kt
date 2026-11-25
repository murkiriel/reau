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
    private val stats = source("dash/DashStats.kt")
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

    @Test
    fun `a new socket starts the detail over and gives the Wi-Fi manager its handle`() {
        val open = session.between("stats.sendTail.clear()", "// The refusal that the local address went around")
        assertTrue(open.contains("stats.fine.clear()"))
        assertTrue(open.contains("DashHeard.fine = stats.fine"))
    }

    @Test
    fun `every frame, every TLV sent and every TLV received reach it, and the video loop tells the session each frame`() {
        assertTrue(stats.between("fun sent(packet: ByteArray", "fun malformed()").contains("fine.sent(DashStats.key(it), it.value)"))
        assertTrue(stats.between("fun received(tlvs: List<Tlv>", "private fun classify(").contains("fine.received(key(tlv), tlv.value.size)"))
        assertTrue(session.contains("fun frameSent(bytes: Int, packets: Int, key: Boolean, maxGapNs: Long) = stats.fine.frame(bytes, packets, key, maxGapNs)"))
        assertTrue(live.contains("override fun frameSent(bytes: Int, packets: Int, key: Boolean, maxGapNs: Long) = session.frameSent(bytes, packets, key, maxGapNs)"))
        assertTrue(loop.contains("onFrame = dash::frameSent"))
    }

    @Test
    fun `the output only watches the gaps between packets, it does not wait, and the clock is read before the packet goes out`() {
        val send = rtp.between("private val packetizer = RtpPacketizer", "private fun watchGap()")
        assertTrue(send.indexOf("watchGap()") in 0 until send.indexOf("sendRtp(packet)"))
        val watch = rtp.between("private fun watchGap()", "// All NALs of one frame")
        assertTrue(watch.contains("nanoTime()"))
        assertFalse(watch, watch.contains("sleep") || watch.contains("delay") || watch.contains("sendRtp"))
    }
}
