package com.reau.app.dash

import com.reau.app.dash.protocol.K1GPacket
import com.reau.app.dash.protocol.Tlv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** The fine detail of the last 15 s kept for the ride log: the lines, the window, the size cap and, above all, that no content gets in. */
class FineTailTest {
    private val s = 1_000_000_000L
    private val now = 100 * s

    private fun tail() = FineTail()

    private fun FineTail.key(ageS: Double, bytes: Int = 28_728, packets: Int = 21, gapMs: Long = 3) =
        frame(bytes, packets, key = true, maxGapNs = gapMs * 1_000_000L, nowNs = now - (ageS * s).toLong())

    @Test
    fun `a second is one line with its frames, the commands out and in, and the title`() {
        val t = tail()
        t.key(14.5)
        t.frame(8_100, 6, key = false, maxGapNs = 2_000_000L, nowNs = now - (14.2 * s).toLong())
        val at = now - (14.0 * s).toLong()
        repeat(3) { t.sent("0501", ByteArray(88) { 0x41 }, at) }
        t.sent("0556", ByteArray(12), at)
        // What the dash said ends the window (26/11/2026): it spoke at the report's moment, so the seconds are counted back from `now` as in the report.
        t.received("0101", 6, now)
        repeat(5) { t.received("0C0$it", 8, now) }
        val lines = t.report("a watchdog fired", now)
        assertEquals(
            "fine tail before a watchdog fired (last 15 s): 1 keyframes in 2 frames, biggest 28728 B in 21 pkts, longest gap between packets 3 ms; 4 commands out, 6 in" +
                "; the dash had been quiet for 0 s (the window ends at its last message)",
            lines[0],
        )
        assertEquals(
            "fine tail t-14 s: video K 28728B/21p gap 3ms, P 8100B/6p gap 2ms | tx 0501×3 264B, 0556 12B | title 88B changed",
            lines[1],
        )
        assertEquals("fine tail t-0 s: rx 0101 6B, 0C*×5 40B", lines[2])
        assertEquals(3, lines.size)
    }

    @Test
    fun `the title is told same when its bytes are those of the one before, changed when they are not, and never as text`() {
        val t = tail()
        t.sent("0501", "Rua das Flores".toByteArray(), now - 5 * s)
        t.sent("0501", "Rua das Flores".toByteArray(), now - 4 * s)
        t.sent("0501", "Avenida Brasil, 100".toByteArray(), now - 3 * s)
        val text = t.report("x", now).joinToString("\n")
        assertTrue(text, Regex("""t-5 s: tx 0501 14B \| title 14B changed""").containsMatchIn(text))
        assertTrue(text, Regex("""t-4 s: tx 0501 14B \| title 14B same""").containsMatchIn(text))
        assertTrue(text, Regex("""t-3 s: tx 0501 19B \| title 19B changed""").containsMatchIn(text))
        assertFalse(text, text.contains("Rua") || text.contains("Flores") || text.contains("Avenida"))
    }

    @Test
    fun `what is older than 15 seconds or in the future is not told, the lines are oldest first`() {
        val t = tail()
        t.key(16.0)
        t.key(15.5)
        t.received("0101", 1, now - 15 * s - 1)
        t.key(14.9)
        t.key(0.2)
        t.received("0101", 1, now + s)
        val lines = t.report("x", now)
        assertEquals(listOf("fine tail t-14 s", "fine tail t-0 s"), lines.drop(1).map { it.substringBefore(":") })
        assertTrue(lines[0], lines[0].contains("2 keyframes in 2 frames"))
    }

    @Test
    fun `the window ends at the last message of the dash, not at the report, and the header says for how long the dash was quiet`() {
        val t = tail()
        // The dash spoke up to 4 s before the report (the watchdog's moment); the app kept sending video into the silence, for 4 s more.
        t.received("0101", 6, now - 4 * s)
        t.key(14.5)
        t.key(5.0)
        t.key(2.0)
        t.key(0.5)
        t.sent("0556", ByteArray(1), now - 3 * s)
        val lines = t.report("a watchdog fired", now)
        assertTrue(lines[0], lines[0].endsWith("; the dash had been quiet for 4 s (the window ends at its last message)"))
        assertEquals("only the frames up to the silence, counted back from it: 14.5 s before the report is 10 s before the silence, 5 s is 1 s, and its last message is t-0", listOf("fine tail t-10 s", "fine tail t-1 s", "fine tail t-0 s"), lines.drop(1).map { it.substringBefore(":") })
        assertTrue("the frames and the command sent into the silence are left out: ${lines[0]}", lines[0].contains("2 keyframes in 2 frames") && lines[0].contains("0 commands out, 1 in"))
    }

    @Test
    fun `a dash that never spoke has the window end at the report, as before, with no word about silence`() {
        val t = tail().apply { key(2.0) }
        val lines = t.report("a watchdog fired", now)
        assertFalse(lines[0], lines[0].contains("quiet"))
        assertTrue(lines[1], lines[1].startsWith("fine tail t-2 s"))
        assertEquals(listOf("fine tail before the session failed (last 15 s): nothing exchanged"), tail().report("the session failed", now))
    }

    @Test
    fun `a message of the dash after the report moment does not move the window forward, and clear forgets the last message`() {
        val t = tail().apply { key(1.0) }
        t.received("0101", 1, now + 3 * s)
        val lines = t.report("x", now)
        assertTrue(lines[0], lines[0].endsWith("quiet for 0 s (the window ends at its last message)"))
        t.clear()
        assertFalse(t.report("x", now).single().contains("quiet"))
    }

    @Test
    fun `nothing in the window is said so in one line`() {
        assertEquals(listOf("fine tail before the session failed (last 15 s): nothing exchanged"), tail().report("the session failed", now))
        val old = tail().apply { key(40.0) }
        assertEquals(1, old.report("the session failed", now).size)
    }

    @Test
    fun `the lines are at most 20 and 4000 characters, the oldest seconds go first and the header says so`() {
        val t = tail()
        for (second in 0 until 15) {
            repeat(6) { n -> t.key(second + 0.1 + n * 0.1, packets = 21) }
            // 34 + 34 a second: 15 seconds fill the command ring (1 024) without losing the oldest, so every second's line is full.
            for (kind in 0 until 34) t.sent("05%02X".format(kind), ByteArray(30), now - (second * s) - 5_000_000L)
            for (kind in 0 until 34) t.received("01%02X".format(kind), 30, now - (second * s) - 4_000_000L)
        }
        val lines = t.report("a watchdog fired", now)
        assertTrue("${lines.size} lines", lines.size <= FineLines.MAX_LINES)
        assertTrue("${lines.sumOf { it.length }} characters", lines.sumOf { it.length } <= FineLines.MAX_CHARS)
        assertTrue(lines[0], lines[0].contains("left out for size"))
        assertTrue("one line is cut with an ellipsis", lines.drop(1).all { it.length <= FineLines.MAX_LINE })
        assertTrue("the newest second is kept", lines.last().startsWith("fine tail t-0 s"))
    }

    @Test
    fun `the rings forget the oldest past their capacity without growing`() {
        val t = tail()
        repeat(FineTail.COMMAND_CAPACITY + 500) { t.received("0101", 1, now - s) }
        repeat(FineTail.FRAME_CAPACITY + 50) { t.frame(100, 1, key = false, maxGapNs = 0, nowNs = now - 3 * s) }
        val text = t.report("x", now).joinToString("\n")
        assertTrue(text, text.contains("0101×${FineTail.COMMAND_CAPACITY} ${FineTail.COMMAND_CAPACITY}B"))
        assertTrue(text, text.contains("${FineTail.FRAME_CAPACITY} frames"))
    }

    @Test
    fun `it is written once for a loss that the Wi-Fi and a watchdog both tell, and starts again after clear`() {
        val t = tail().apply { key(1.0) }
        assertTrue(t.reportOnce("the Wi-Fi was lost", now).isNotEmpty())
        assertEquals(emptyList<String>(), t.reportOnce("a watchdog fired", now + 4 * s))
        assertTrue(t.reportOnce("a watchdog fired", now + 31 * s).isNotEmpty())
        t.clear()
        assertTrue(t.reportOnce("a watchdog fired", now + 32 * s).single().endsWith("nothing exchanged"))
    }

    @Test
    fun `no value of any command reaches the lines, text cards, the key exchange and the music included`() {
        val stats = DashStats()
        fun pkt(vararg tlvs: Tlv) = K1GPacket.build(*tlvs)
        stats.sent(pkt(Tlv(0x05, 0x01, "Rua Secreta 77".toByteArray())))
        stats.sent(pkt(Tlv(0x08, 0x00, "CHAVE-SEGREDO".toByteArray())))
        stats.sent(pkt(Tlv(0x06, 0x08, "Musica Proibida".toByteArray())))
        stats.received(K1GPacket.parseIncoming(pkt(Tlv(0x0C, 0x02, "Wi-Fi RE_SSID".toByteArray()))), nowMs = 1_000)
        val text = stats.fine.report("a watchdog fired", System.nanoTime()).joinToString("\n")
        for (secret in listOf("Rua", "Secreta", "CHAVE", "SEGREDO", "Musica", "Proibida", "RE_SSID", "Wi-Fi RE")) assertFalse("$secret in: $text", text.contains(secret))
        assertTrue(text, text.contains("0501 14B") && text.contains("0800 13B") && text.contains("0608 15B") && text.contains("0C*"))
        // The hex of the values neither (the "Rua" of the title, 52 75 61).
        assertFalse(text, text.contains("527561"))
    }

    @Test
    fun `the session's stats feed it with every sent TLV and every received one, the header segment left out`() {
        val stats = DashStats()
        stats.sent(K1GPacket.build(K1GPacket.tlv(0x05, 0x56, 0x55), K1GPacket.tlv(0x01, 0x02)))
        stats.received(K1GPacket.parseIncoming(K1GPacket.build(K1GPacket.tlv(0x09, 0x06, 0x55))), nowMs = 1_000)
        val text = stats.fine.report("x", System.nanoTime()).joinToString("\n")
        assertTrue(text, text.contains("tx 0556 1B, 0102 0B") && text.contains("rx 0906 1B"))
        assertFalse(text, text.contains("0201"))
    }

    @Test
    fun `the session keeps each frame the video loop tells it, with its bytes, packets, kind and longest gap`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val session = DashSession(scope)
            session.frameSent(bytes = 21_000, packets = 7, key = true, maxGapNs = 3_000_000L)
            session.frameSent(bytes = 4_100, packets = 3, key = false, maxGapNs = 1_000_000L)
            val text = session.stats.fine.report("x", System.nanoTime()).joinToString("\n")
            assertTrue(text, text.contains("video K 21000B/7p gap 3ms, P 4100B/3p gap 1ms"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a new socket starts the detail over and gives the Wi-Fi manager its handle`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dash = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val ctrl = InetSocketAddress(InetAddress.getLoopbackAddress(), dash.localPort)
        val rtp = InetSocketAddress(InetAddress.getLoopbackAddress(), 1)
        val session = DashSession(scope, socketFactory = { DashSocket(it, ctrlDest = ctrl, rtpDest = rtp) })
        try {
            // A frame left by the socket before: the next one must not be told as if it were this one's.
            session.frameSent(bytes = 21_000, packets = 7, key = true, maxGapNs = 3_000_000L)
            session.connect("RE_TEST_1234")
            val deadline = System.currentTimeMillis() + 5_000
            while (session.state.value != DashState.AUTHENTICATING && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(DashState.AUTHENTICATING, session.state.value)
            val text = session.stats.fine.report("x", System.nanoTime()).joinToString("\n")
            assertFalse("the frame of the socket before is gone: $text", text.contains("21000B"))
            assertSame("the Wi-Fi manager reads the detail of this session", session.stats.fine, DashHeard.fine)
        } finally {
            session.disconnect()
            dash.close()
            scope.cancel()
        }
    }
}
