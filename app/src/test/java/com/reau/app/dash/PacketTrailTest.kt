package com.reau.app.dash

import com.reau.app.dash.protocol.Tlv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketTrailTest {
    private fun tlv(type: Int, sub: Int, size: Int = 2) = Tlv(type, sub, ByteArray(size) { 0x7E })

    @Test
    fun `repeats are counted, the telemetry is one family and each direction has its own list`() {
        val trail = PacketTrail()
        val now = 100_000L
        val t = now - 12_000
        repeat(4) { trail.add(t, tx = true, tlv(0x05, 0x56)) }
        trail.add(t, tx = true, tlv(0x06, 0x08))
        trail.add(t, tx = true, tlv(0x05, 0x01, size = 30))
        trail.add(t, tx = false, tlv(0x01, 0x01))
        repeat(5) { i -> trail.add(t, tx = false, tlv(0x0C, i)) }
        assertEquals(
            listOf("t-12.0 s tx 0556×4, 0608, 0501 (40 B); rx 0101, 0C*×5 (12 B)"),
            trail.lines(now),
        )
    }

    @Test
    fun `what is older than 30 seconds is not told and the ring forgets the oldest past 256`() {
        val trail = PacketTrail()
        trail.add(1_000, tx = true, tlv(0x09, 0x09))
        trail.add(70_000, tx = true, tlv(0x05, 0x56))
        assertEquals(listOf("t-5.0 s tx 0556 (2 B)"), trail.lines(75_000))
        repeat(PacketTrail.CAPACITY + 50) { trail.add(80_000, tx = false, tlv(0x01, 0x01)) }
        assertEquals(PacketTrail.CAPACITY, trail.size())
        assertEquals(listOf("t-1.0 s rx 0101×256 (512 B)"), trail.lines(81_000))
    }

    @Test
    fun `thirty seconds come out in at most twelve lines, oldest first`() {
        val trail = PacketTrail()
        val now = 100_000L
        for (ms in 0 until 30_000 step 500) trail.add(now - 29_999 + ms, tx = true, tlv(0x05, 0x56))
        val lines = trail.lines(now)
        assertTrue(lines.size.toString(), lines.size in 2..PacketTrail.MAX_LINES)
        val ages = lines.map { it.substringAfter("t-").substringBefore(" s").toDouble() }
        assertEquals(ages.sortedDescending(), ages)
    }

    @Test
    fun `no value of any TLV is ever written, text cards included`() {
        val trail = PacketTrail()
        val card = Tlv(0x05, 0x01, "Rua das Flores 12".toByteArray())
        val key = Tlv(0x08, 0x00, byteArrayOf(0x4B, 0x45, 0x59))
        trail.add(1_000, tx = true, card)
        trail.add(1_000, tx = true, key)
        val text = trail.report("a test", 2_000).joinToString("\n")
        assertFalse(text, text.contains("Rua"))
        assertFalse(text, text.contains("4B4559") || text.contains("KEY"))
        assertTrue(text, text.contains("0501") && text.contains("0800"))
    }

    @Test
    fun `the report says it was quiet when nothing was exchanged`() {
        assertEquals(listOf("packet trail before the Wi-Fi was lost (last 30 s): no packets"), PacketTrail().report("the Wi-Fi was lost", 5_000))
        val busy = PacketTrail().apply { add(4_000, tx = false, tlv(0x01, 0x01)) }.report("a watchdog fired", 5_000)
        assertEquals("packet trail before a watchdog fired (last 30 s):", busy.first())
        assertEquals(2, busy.size)
    }

    @Test
    fun `the stats feed the trail with what goes out and what comes in`() {
        val stats = DashStats()
        val packet = com.reau.app.dash.protocol.K1GPacket.build(tlv(0x05, 0x56))
        stats.sent(packet, nowMs = 10_000)
        stats.received(listOf(tlv(0x01, 0x01)), 10_100)
        val lines = stats.trail.lines(10_500)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("tx 0556") && lines[0].contains("rx 0101"))
    }
}
