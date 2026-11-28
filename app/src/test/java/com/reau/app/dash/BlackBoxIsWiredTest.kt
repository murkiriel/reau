package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the flight recorder ([BlackBox]) is fed and read. The send and receive paths need a socket, a scope and a Context, as in the other "...IsWired" tests, so these are
 * of the text of the source. What is kept is held by BlackBoxTest, what is written by BlackBoxReportTest, the probe by PanelProbeTest.
 */
class BlackBoxIsWiredTest {
    private fun code(path: String): String {
        val comment = Regex("""^\s*(\*|//|/\*)""")
        return File("src/main/java/com/reau/app/$path").readText().lines().filterNot { comment.containsMatchIn(it) }.joinToString("\n")
    }

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    private val socket = code("dash/DashSocket.kt")
    private val session = code("dash/DashSession.kt")
    private val stats = code("dash/DashStats.kt")
    private val system = code("service/SystemEvents.kt")

    @Test
    fun `every RTP packet that went out is noted with the clock read just before the send`() {
        val send = socket.between("fun sendRtp(data: ByteArray) {", "} catch (e: Exception) {\n            rtpSendFailed")
        assertEquals(1, Regex("""box\?\.rtp\(data, startUs\)""").findAll(send).count())
        assertTrue(send, send.indexOf("val startUs = box?.nowUs() ?: 0L") < send.indexOf("rtpSocket.send("))
        assertTrue(send, send.indexOf("rtpSocket.send(") < send.indexOf("box?.rtp(data, startUs)"))
        assertTrue("the same place the send tail is told", send.indexOf("box?.rtp(data, startUs)") < send.indexOf("stats?.sendTail?.rtp(data.size)"))
    }

    @Test
    fun `every control datagram that went out is noted after its send, outside the try, and a failed send is not`() {
        val wire = socket.between("private fun wire(data: ByteArray): ByteArray? {", "private fun heldBack()")
        assertEquals(1, Regex("""stats\?\.blackBox\?\.command\(pkt\)""").findAll(wire).count())
        val sent = wire.between("val sent = try {", "if (!sent) return null")
        assertFalse("a note taken inside the try could be taken for a failed send", "blackBox" in sent)
        assertTrue(wire, wire.indexOf("if (!sent) return null") < wire.indexOf("stats?.blackBox?.command(pkt)"))
        assertTrue(wire, wire.indexOf("stats?.blackBox?.command(pkt)") < wire.indexOf("return pkt"))
    }

    @Test
    fun `every datagram from the dash is noted with its size where the session reads it`() {
        assertEquals(1, Regex("""blackBox\.rx\(size, tlvs\)""").findAll(stats).count())
        val dispatch = session.between("internal fun dispatchIncoming(pkt: ByteArray, sock: DashSocket) {", "for (tlv in tlvs) {")
        assertTrue(dispatch, "sock.stats?.received(tlvs, MonotonicClock.nowMs(), pkt.size)" in dispatch)
    }

    @Test
    fun `a new socket starts the ring over and the Wi-Fi events go to the same one`() {
        val open = session.between("sock.stats = stats", "onBindResult?.invoke(network, false)")
        assertTrue(open, "stats.blackBox.clear()" in open)
        assertTrue(open, open.indexOf("stats.blackBox.clear()") < open.indexOf("DashHeard.box = stats.blackBox"))
        val log = system.between("fun log(line: String) {", "}")
        assertTrue(log, "com.reau.app.dash.DashHeard.box?.event(line)" in log)
        assertTrue("every system line, the supplicant's, the networks' and the bike's, goes through it", "RideDiagnostics.log(TAG, line)" in log)
        val wifi = code("dash/DashWifiManager.kt").between("private fun lastScanEvidence(): String =", "\n")
        assertTrue(wifi, "DashHeard.box?.event(it)" in wifi)
    }

    @Test
    fun `the RX watchdog writes the box and asks the probe before it fails the session, and the failure of a silent session writes it too`() {
        val watchdog = session.between("if (rxSilenceFails(", "if (rtpStallFails(sock, mine)) break")
        assertTrue(watchdog, "logBlackBox(\"the RX watchdog fired\", silent)" in watchdog)
        assertTrue(watchdog, "probePanel(silent)" in watchdog)
        assertTrue(watchdog, watchdog.indexOf("logLinkAtFailure()") < watchdog.indexOf("logBlackBox("))
        assertTrue(watchdog, watchdog.indexOf("probePanel(silent)") < watchdog.indexOf("fail(\"Dash stopped responding"))
        val fail = session.between("Log.e(TAG, \"ERROR — \$msg\")", "socket?.close(); socket = null")
        assertTrue(fail, "if (silentEndOf == generation) logBlackBox(\"the session failed with the dash silent\", null)" in fail)
        // The other watchdogs (the first ack, the video) are not a silence of the dash: they do not write it.
        assertEquals(2, Regex("""logBlackBox\(""").findAll(session.between("private fun launchStatusHeartbeat(", "private fun logLinkAtFailure()") + fail).count())
    }

    @Test
    fun `the box is written once for a silence, under its own tag, from the session's own ring`() {
        val log = session.between("private fun logBlackBox(why: String, silentS: Long?) {", "private fun probePanel(")
        assertTrue(log, "if (now - blackBoxLoggedMs < BLACKBOX_ONCE_MS) return" in log)
        assertTrue(log, log.indexOf("return") < log.indexOf("blackBoxLoggedMs = now"))
        assertTrue(log, "BlackBoxReport.lines(" in log && "box.snapshot(), box.nowUs()" in log)
        assertTrue(log, "RideDiagnostics.log(BlackBoxReport.TAG, it)" in log)
        assertEquals("blackbox", BlackBoxReport.TAG)
    }

    @Test
    fun `the probe goes through the dash's network on a background thread, only when that network is up, and does not hold the failure`() {
        val probe = session.between("private fun probePanel(silentS: Long) {", "private fun launchStatsLine()")
        assertTrue(probe, "val network = linkNetwork" in probe)
        assertTrue(probe, "!networkUp(network)" in probe && "PanelProbe.skippedLine(silentS)" in probe)
        val launched = probe.substringAfter("scope.launch(Dispatchers.IO) {")
        assertTrue(launched, "PanelProbe.probe(network)" in launched && "PanelProbe.line(result, silentS)" in launched)
        assertFalse("the probe's connect is not on the watchdog's thread", "PanelProbe.probe(network)" in probe.substringBefore("scope.launch"))
        assertFalse("nothing waits for it", "join()" in probe || "runBlocking" in probe || "await()" in probe)
        assertTrue(code("dash/PanelProbe.kt").contains("network.socketFactory.createSocket()"))
    }

    @Test
    fun `the supplicant's join timing is fed from the broadcast with its error and written under the box's tag`() {
        val line = system.between("private fun supplicantLine(", "private fun networkStateLine(")
        assertTrue(line, "intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0)" in line)
        assertTrue(line, "join.state(state?.name, error, SystemClock.elapsedRealtime())" in line)
        assertTrue(line, "RideDiagnostics.log(com.reau.app.dash.BlackBoxReport.TAG, it)" in line)
        assertTrue(system, "supplicantLine(intent, supplicant, joinTiming)" in system)
    }

    @Test
    fun `the recorder asks for no permission and keeps no payload`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest, "READ_LOGS" in manifest)
        val box = code("dash/BlackBox.kt")
        assertFalse("nothing but a size and keys is copied from a datagram", "copyOf" in box || "copyOfRange" in box || "toList()" in box)
    }
}
