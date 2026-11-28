package com.reau.app.dash

import com.reau.app.dash.protocol.K1GPacket
import com.reau.app.dash.video.RtpPacketizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ring of the dash link's flight recorder: what an entry keeps of an RTP packet, a command and a datagram from the dash, and how the ring wraps. The clock is given. */
class BlackBoxTest {
    private var nowNs = 5_000_000_000L
    private val box = BlackBox(clockNs = { nowNs })

    /** The RTP packets [RtpPacketizer] makes of one NAL, as REau sends them. */
    private fun packets(nal: ByteArray, endOfAu: Boolean = true): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        RtpPacketizer { out += it }.packetize(nal, endOfAu, wallClockMs = 1_000)
        return out
    }

    private fun nal(type: Int, size: Int) = ByteArray(size).also { it[0] = type.toByte() }

    @Test
    fun `a keyframe bundle in four packets is key in all of them, first in the first and last in the last`() {
        val p = packets(nal(7, 4_000)) // the dash gets the SPS, the PPS and the IDR as one NAL that starts with the SPS
        assertEquals(3, p.size)
        p.forEach { box.rtp(it, box.nowUs()) }
        val s = box.snapshot()
        assertEquals(listOf(BlackBox.KEY or BlackBox.FIRST, BlackBox.KEY, BlackBox.KEY or BlackBox.LAST), (0 until 3).map { s.flags(it) })
        // The sequence numbers are the packets' own, in order.
        val seq = (0 until 3).map { s.seq(it) }
        assertEquals(listOf(seq[0], (seq[0] + 1) and 0xFFFF, (seq[0] + 2) and 0xFFFF), seq)
        assertEquals(p.map { it.size }, (0 until 3).map { s.size[it] })
    }

    @Test
    fun `a small P frame is one packet, first and last and not a keyframe`() {
        box.rtp(packets(nal(1, 300)).single(), box.nowUs())
        val s = box.snapshot()
        assertEquals(BlackBox.FIRST or BlackBox.LAST, s.flags(0))
    }

    @Test
    fun `a big P frame is cut in pieces and only the first is first, only the last last, none a keyframe`() {
        val p = packets(nal(1, 3_000))
        p.forEach { box.rtp(it, box.nowUs()) }
        val s = box.snapshot()
        assertEquals(listOf(BlackBox.FIRST, 0, BlackBox.LAST), (0 until p.size).map { s.flags(it) })
    }

    @Test
    fun `an IDR slice and a PPS count as key, a packet of no marker is not last`() {
        box.rtp(packets(nal(5, 100)).single(), box.nowUs())
        box.rtp(packets(nal(8, 10)).single(), box.nowUs())
        box.rtp(packets(nal(1, 100), endOfAu = false).single(), box.nowUs())
        val s = box.snapshot()
        assertTrue(s.flags(0) and BlackBox.KEY != 0)
        assertTrue(s.flags(1) and BlackBox.KEY != 0)
        assertFalse(s.flags(2) and BlackBox.KEY != 0)
        assertFalse(s.flags(2) and BlackBox.LAST != 0)
    }

    @Test
    fun `the time is the clock in microseconds, and the send call's length is the time since it was read`() {
        val start = box.nowUs()
        assertEquals(5_000_000L, start)
        nowNs += 2_345_000L
        box.rtp(packets(nal(1, 50)).single(), start)
        val s = box.snapshot()
        assertEquals(5_002_345L, s.atUs[0])
        assertEquals(2_345L, s.sendUs(0))
    }

    @Test
    fun `a command keeps the first three keys and how many TLVs it had`() {
        box.command(K1GPacket.build(K1GPacket.tlv(0x05, 0x01, 1), K1GPacket.tlv(0x05, 0x56, 2, 3), K1GPacket.tlv(0x06, 0x08), K1GPacket.tlv(0x0A, 0x02), K1GPacket.tlv(0x01, 0x01)))
        box.command(K1GPacket.build(K1GPacket.tlv(0x05, 0x56, 0x55)))
        val s = box.snapshot()
        assertEquals(BlackBox.COMMAND, s.kind[0])
        assertEquals(listOf(0x0501, 0x0556, 0x0608), (0 until 3).map { s.op(0, it) })
        assertEquals(5, s.tlvCount(0))
        assertEquals(listOf(0x0556, -1, -1), (0 until 3).map { s.op(1, it) })
        assertEquals(1, s.tlvCount(1))
        assertEquals(K1GPacket.build(K1GPacket.tlv(0x05, 0x56, 0x55)).size, s.size[1])
    }

    @Test
    fun `a datagram of the dash keeps its keys without the packet header's segment`() {
        val tlvs = K1GPacket.parseIncoming(K1GPacket.build(K1GPacket.tlv(0x01, 0x01, 1, 2), K1GPacket.tlv(0x0C, 0x03, 9)))
        box.rx(64, tlvs)
        val s = box.snapshot()
        assertEquals(BlackBox.RX, s.kind[0])
        assertEquals(64, s.size[0])
        // parseIncoming reads the header's own 02 01 segment too: it is not a TLV of the dash.
        assertEquals(listOf(0x0101, 0x0C03, -1), (0 until 3).map { s.op(0, it) })
        assertEquals(2, s.tlvCount(0))
    }

    @Test
    fun `a packet too short or cut mid-TLV is kept as it is and never throws`() {
        box.command(ByteArray(3))
        box.command(K1GPacket.build(K1GPacket.tlv(0x05, 0x01, 1, 2, 3, 4)).copyOf(19))
        box.rtp(ByteArray(5), 0L)
        box.rx(0, emptyList())
        assertEquals(4, box.snapshot().count)
    }

    @Test
    fun `the ring keeps the newest entries, oldest first, when it is full`() {
        val small = BlackBox(clockNs = { nowNs }, capacity = 4)
        for (n in 1..6) {
            nowNs += 1_000_000L
            small.rx(n, emptyList())
        }
        val s = small.snapshot()
        assertEquals(listOf(3, 4, 5, 6), (0 until s.count).map { s.size[it] })
        assertEquals(listOf(5_003_000L, 5_004_000L, 5_005_000L, 5_006_000L), (0 until s.count).map { s.atUs[it] })
    }

    @Test
    fun `the events are kept apart, cut short and capped, newest last`() {
        for (n in 1..(BlackBox.EVENTS + 5)) {
            nowNs += 1_000L
            box.event("e$n")
        }
        box.event("x".repeat(500))
        val events = box.snapshot().events
        assertEquals(BlackBox.EVENTS, events.size)
        assertEquals("e7", events.first().text) // 69 events and one more: the first 6 are gone
        assertEquals(BlackBox.EVENT_CHARS, events.last().text.length)
        assertTrue(events.zipWithNext().all { (a, b) -> a.atUs <= b.atUs })
    }

    @Test
    fun `clear empties the ring and the events`() {
        box.rx(1, emptyList())
        box.event("a")
        box.clear()
        val s = box.snapshot()
        assertEquals(0, s.count)
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun `the stats note each datagram from the dash with its size, and none from a packet of only the header`() {
        val stats = DashStats()
        val tlvs = K1GPacket.parseIncoming(K1GPacket.build(K1GPacket.tlv(0x0C, 0x02, 1)))
        stats.received(tlvs, nowMs = 1_000, size = 40)
        val s = stats.blackBox.snapshot()
        assertEquals(1, s.count)
        assertEquals(40, s.size[0])
        assertEquals(0x0C02, s.op(0, 0))
    }
}
