package com.reau.app.dash

import com.reau.app.dash.protocol.K1GPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the flight recorder writes to the ride log when the dash goes silent: the peaks, the longest run, the shortest gap, the keyframe next to a command, what was sent after the
 * dash's last word, and the entries. A scenario is laid out in microseconds on an injected clock; the numbers in the lines are checked by hand.
 */
class BlackBoxReportTest {
    private var nowNs = 0L
    private val box = BlackBox(clockNs = { nowNs })

    private fun at(us: Long) {
        nowNs = us * 1_000L
    }

    /** An RTP packet of [size] bytes with the given [seq] and flags, at [us]. */
    private fun rtp(us: Long, size: Int, seq: Int, key: Boolean = false, first: Boolean = true, last: Boolean = true, tookUs: Long = 0) {
        at(us - tookUs)
        val start = box.nowUs()
        at(us)
        val nalType = if (key) 7 else 1
        val p = ByteArray(size)
        p[0] = 0x80.toByte()
        p[1] = (if (last) 0x80 or 96 else 96).toByte()
        p[2] = (seq shr 8).toByte()
        p[3] = seq.toByte()
        if (first && last) {
            p[12] = nalType.toByte()
        } else {
            p[12] = (28).toByte()
            p[13] = ((if (first) 0x80 else 0) or (if (last) 0x40 else 0) or nalType).toByte()
        }
        box.rtp(p, start)
    }

    private fun command(us: Long, vararg keys: Pair<Int, Int>) {
        at(us)
        box.command(K1GPacket.build(*keys.map { K1GPacket.tlv(it.first, it.second, 1) }.toTypedArray()))
    }

    private fun heard(us: Long, size: Int, type: Int, sub: Int) {
        at(us)
        box.rx(size, K1GPacket.parseIncoming(K1GPacket.build(K1GPacket.tlv(type, sub, 1))))
    }

    private fun lines(nowUs: Long): List<String> = BlackBoxReport.lines("a watchdog fired", box.snapshot(), nowUs)

    /**
     * The scene, t in ms: a heartbeat of the dash at 1000.0 and a telemetry at 1500.0 (its last datagram, the zero); a keyframe of 3 packets at 1100.0, 1100.2 and 1100.4 with a
     * command at 1110.0 (10 ms after the first), an ordinary frame at 1300.0, and after the last word: a frame of 1000 B at 1600.0 and a command at 2400.0. Now is 6000.0.
     */
    private fun scene() {
        heard(1_000_000, 40, 0x01, 0x01)
        rtp(1_100_000, 1_400, 10, key = true, first = true, last = false)
        rtp(1_100_200, 1_400, 11, key = true, first = false, last = false)
        rtp(1_100_400, 900, 12, key = true, first = false, last = true, tookUs = 6_000)
        command(1_110_000, 0x05 to 0x01, 0x05 to 0x56)
        rtp(1_300_000, 500, 13)
        heard(1_500_000, 52, 0x0C, 0x03)
        rtp(1_600_000, 1_000, 14)
        command(2_400_000, 0x01 to 0x01)
    }

    @Test
    fun `the header says when the dash was last heard and what the ring holds`() {
        scene()
        val l = lines(6_000_000)
        assertEquals(
            "box for a watchdog fired: 9 entries in the last 10 s (sent 7: rtp 5, commands 2; from the dash 2; wifi events 0); " +
                "last datagram from the dash 4500.000 ms before this report (52 B 0C03)",
            l[0],
        )
    }

    @Test
    fun `the peaks are the most datagrams and bytes in any window of 10, 50 and 100 ms`() {
        scene()
        val l = lines(6_000_000)
        // 10 ms (half open): the three keyframe packets (3 pkts, 3700 B); the command 10 ms after the first is outside. 50 ms: keyframe 3 + command = 4 pkts, 3700 + command's size.
        val cmd = K1GPacket.build(K1GPacket.tlv(0x05, 0x01, 1), K1GPacket.tlv(0x05, 0x56, 1)).size
        assertEquals(
            "peak sent in the last 10 s: 10 ms: 3 pkts, 3700 B (bytes at -399.600); 50 ms: 4 pkts, ${3_700 + cmd} B (bytes at -390.000); " +
                "100 ms: 4 pkts, ${3_700 + cmd} B (bytes at -390.000)",
            l[1],
        )
    }

    @Test
    fun `the peaks of the two seconds before the last word leave out what came after it`() {
        scene()
        val l = lines(6_000_000)
        // The frame at 1600.0 (1000 B) and the command at 2400.0 are after the zero: left out, and the 2 s window is 0..1500 ms of the zero.
        assertTrue(l[2], l[2].startsWith("peak sent in the 2 s before the dash's last datagram: 10 ms: 3 pkts, 3700 B"))
        assertTrue(l[2], l[2].contains("100 ms: 4 pkts"))
    }

    @Test
    fun `the longest run, the shortest gap, the keyframe next to a command and the slow send`() {
        scene()
        val l = lines(6_000_000)
        assertEquals(
            "longest run of RTP packets less than 2 ms apart: 3 pkts, 3700 B in 0.4 ms at -400.000; shortest gap between sent datagrams 200 µs; " +
                "keyframe packet within 20 ms of a command: yes, 1 time (1 keyframes, 2 commands), closest 9.6 ms: command 0501,0556 at -390.000; " +
                "send calls of 5 ms or more: 1, slowest 6.0 ms at -399.600",
            l[3],
        )
    }

    @Test
    fun `what was sent after the dash's last datagram, what the dash said and its longest silence`() {
        scene()
        val l = lines(6_000_000)
        val cmd = K1GPacket.build(K1GPacket.tlv(0x01, 0x01, 1)).size
        assertEquals(
            "sent after the dash's last datagram: 2 datagrams (1 rtp, 1 commands, ${1_000 + cmd} B); " +
                "dash datagrams by their first key: 0101 heartbeat 1, 0C telemetry 1, other 0; longest silence between two 500.0 ms",
            l[4],
        )
    }

    @Test
    fun `a datagram at the very instant of the last word is not after it`() {
        heard(1_000_000, 40, 0x01, 0x01)
        rtp(1_000_000, 100, 1)
        val l = lines(2_000_000)
        assertTrue(l[4], l[4].startsWith("sent after the dash's last datagram: 0 datagrams"))
    }

    @Test
    fun `the entries are the two seconds before the last word and half a second after it, in ms from it`() {
        rtp(-1_000_000, 700, 99) // older than 2 s before the zero: not listed (but in the peaks of the last 10 s)
        scene()
        val l = lines(6_000_000)
        val entries = l.last()
        assertTrue(entries, entries.startsWith("entries, ms from the last dash datagram"))
        assertTrue(entries, "-500.000 r40 0101" in entries)
        assertTrue(entries, "-400.000 v1400 #10 KF" in entries)
        assertTrue(entries, "-399.800 v1400 #11 K" in entries)
        assertTrue(entries, "-399.600 v900 #12 KL d6.0" in entries)
        assertTrue(entries, "-390.000 c" in entries && "0501,0556" in entries)
        assertTrue(entries, "-200.000 v500 #13 FL" in entries)
        assertTrue(entries, "+0.000 r52 0C03" in entries)
        assertTrue(entries, "+100.000 v1000 #14 FL" in entries)
        assertTrue("the command at +900 ms is after the half second", "+900.000" !in entries)
        assertTrue("#99" !in entries)
    }

    @Test
    fun `a datagram that carries more TLVs than the keys kept says how many more`() {
        at(1_000_000)
        box.command(K1GPacket.build(*(1..5).map { K1GPacket.tlv(0x05, it, 1) }.toTypedArray()))
        heard(1_000_100, 20, 0x01, 0x01)
        assertTrue(lines(1_100_000).last(), "c" in lines(1_100_000).last() && "0501,0502,0503+2" in lines(1_100_000).last())
    }

    @Test
    fun `with no word from the dash in the ring the zero is the report and the peaks of the 2 s say so`() {
        rtp(5_000_000, 100, 1)
        val l = lines(5_500_000)
        assertTrue(l[0], l[0].endsWith("the dash was never heard in the ring"))
        assertTrue(l[2], l[2].endsWith("none heard"))
        assertTrue(l[4], l[4].startsWith("sent after the dash's last datagram: 0 datagrams"))
        assertTrue(l.last(), "-500.000 v100 #1 FL" in l.last())
    }

    @Test
    fun `an empty ring still says so`() {
        val l = lines(1_000_000)
        assertEquals("box for a watchdog fired: 0 entries in the last 10 s (sent 0: rtp 0, commands 0; from the dash 0; wifi events 0); the dash was never heard in the ring", l[0])
        assertTrue(l[3], l[3].startsWith("no RTP run; no gap; keyframe packet within 20 ms of a command: no (0 keyframes, 0 commands)"))
        assertEquals("entries, ms from the last dash datagram (v=RTP size #seq K=keyframe F=first L=last, d=ms the send took; c=command size keys; r=dash datagram size keys): none", l.last())
    }

    @Test
    fun `what is older than ten seconds is not in the report`() {
        rtp(1_000_000, 100, 1)
        val l = lines(20_000_000)
        assertTrue(l[0], l[0].contains("0 entries in the last 10 s"))
    }

    @Test
    fun `a command and a keyframe 20 ms apart are not within 20 ms`() {
        rtp(1_000_000, 100, 1, key = true)
        command(1_020_000, 0x05 to 0x01)
        assertTrue(lines(2_000_000)[3], "keyframe packet within 20 ms of a command: yes, 1 time" in lines(2_000_000)[3])
        val far = BlackBox(clockNs = { nowNs })
        at(1_000_000)
        far.rtp(ByteArray(20).also { it[12] = 7 }, 1_000_000)
        at(1_020_001)
        far.command(K1GPacket.build(K1GPacket.tlv(0x05, 0x01, 1)))
        val l = BlackBoxReport.lines("x", far.snapshot(), 2_000_000)
        assertTrue(l[3], "keyframe packet within 20 ms of a command: no (1 keyframes, 1 commands)" in l[3])
    }

    @Test
    fun `packets 2 ms apart are not one run, and a command between two packets breaks the run`() {
        rtp(1_000_000, 100, 1)
        rtp(1_002_000, 100, 2)
        rtp(1_004_100, 100, 3)
        assertTrue(lines(2_000_000)[3], lines(2_000_000)[3].startsWith("longest run of RTP packets less than 2 ms apart: 1 pkts"))
        rtp(1_005_000, 100, 4)
        command(1_005_500, 0x05 to 0x01)
        rtp(1_006_000, 100, 5)
        rtp(1_006_500, 100, 6)
        assertTrue(lines(2_000_000)[3], lines(2_000_000)[3].startsWith("longest run of RTP packets less than 2 ms apart: 2 pkts"))
    }

    @Test
    fun `the wifi events of the window are told, with their time from the last word`() {
        heard(1_000_000, 40, 0x01, 0x01)
        at(900_000)
        box.event("wifi supplicant: ASSOCIATED")
        at(1_200_000)
        box.event("wifi network lost (0 left)")
        val l = lines(3_000_000)
        assertTrue(l.joinToString("\n"), "wifi events, ms from the last dash datagram: -100.000 wifi supplicant: ASSOCIATED; +200.000 wifi network lost (0 left)" in l)
        assertTrue(l[0], "wifi events 2" in l[0])
    }

    @Test
    fun `a ring full of video stays within the lines and characters of the log, and keeps the entries nearest the last word`() {
        for (n in 0 until 2_000) rtp(4_000_000L + n * 500L, 1_300, n and 0xFFFF)
        heard(5_000_000, 40, 0x01, 0x01)
        heard(5_000_100, 40, 0x01, 0x01)
        val l = lines(6_000_000)
        assertTrue("${l.size} lines", l.size <= 60)
        assertTrue(l.maxOf { it.length }.toString(), l.all { it.length <= 900 })
        assertTrue(l.last(), l.any { "older entries left out" in it })
        assertTrue("the newest kept", "#1999" in l.last() || l.any { "#1999" in it })
    }

    @Test
    fun `the whole report of the scene, as the people who will read it see it`() {
        scene()
        at(1_150_000)
        box.event("wifi supplicant: FOUR_WAY_HANDSHAKE")
        val l = lines(6_000_000)
        assertEquals(7, l.size)
        assertEquals("wifi events, ms from the last dash datagram: -350.000 wifi supplicant: FOUR_WAY_HANDSHAKE", l[5])
        assertTrue(l[6], l[6].endsWith("-500.000 r40 0101; -400.000 v1400 #10 KF; -399.800 v1400 #11 K; -399.600 v900 #12 KL d6.0; -390.000 c27 0501,0556; " +
            "-200.000 v500 #13 FL; +0.000 r52 0C03; +100.000 v1000 #14 FL"))
    }
}
