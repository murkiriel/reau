package com.reau.app.dash

import android.os.SystemClock
import com.reau.app.dash.protocol.Tlv

/**
 * The flight recorder of the dash link (0.3.99 test build): a ring in memory of the last datagrams REau sent to the dash and the last the dash sent back, each with the
 * phone's clock in microseconds, kept always (the detailed log off), and written to the ride log only when the dash goes silent ([BlackBoxReport] reads it). The seconds-scale
 * records ([PacketTrail], [SendTail]) cannot tell a burst of a few milliseconds from a steady flow; this can.
 *
 * Cheap on the hot path, which is the RTP send: an entry is five array writes at a slot that exists (no object, no string, no list per packet), under one lock that only the
 * senders and the receive loop share, and the clock is the one Android already keeps ([SystemClock.elapsedRealtimeNanos], which counts the time the phone sleeps). What an entry
 * keeps: the time, the direction and kind ([RTP], [COMMAND], [RX]), the datagram's size, and
 *  - RTP: the sequence number, whether it belongs to a keyframe (the NAL bundle that starts with SPS, or an IDR slice), whether it is the first and the last packet of its frame, and
 *    how long the send call took;
 *  - command and RX: the first [MAX_OPS] TLV keys (`0501`: type and sub) and how many TLVs the datagram had.
 * Never a payload. [CAPACITY] entries, the oldest overwritten; the Wi-Fi's own events (the supplicant, the networks, a scan) are kept apart, [EVENTS] of them, as short texts.
 * What it records is never sent or changed by it. Thread-safe; no method throws.
 */
class BlackBox(
    /** The clock in nanoseconds, injected by the tests. */
    private val clockNs: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val capacity: Int = CAPACITY,
) {
    /** One Wi-Fi event: [atUs] on the same clock as the entries. */
    class Event(val atUs: Long, val text: String)

    /** The ring as it was, oldest first, in plain arrays ([count] long): what [BlackBoxReport] reads. */
    class Snapshot(
        val atUs: LongArray,
        val kind: ByteArray,
        val size: IntArray,
        val aux: IntArray,
        val extra: LongArray,
        val events: List<Event>,
    ) {
        val count: Int get() = atUs.size

        fun isTx(i: Int): Boolean = kind[i] == RTP || kind[i] == COMMAND

        /** The RTP packet's sequence number (entry [i] must be [RTP]). */
        fun seq(i: Int): Int = aux[i] and SEQ_MASK

        /** The RTP packet's flags ([KEY], [FIRST], [LAST]). */
        fun flags(i: Int): Int = aux[i] ushr FLAGS_SHIFT

        /** The TLVs the command or RX datagram had. */
        fun tlvCount(i: Int): Int = aux[i]

        /** The key of the [n]th TLV kept of a command or RX entry (`0x0501`), or -1 when it kept fewer. */
        fun op(i: Int, n: Int): Int = if (n < minOf(aux[i], MAX_OPS)) ((extra[i] ushr (KEY_BITS * n)) and KEY_MASK).toInt() else -1

        /** How long the RTP send call took, in microseconds. */
        fun sendUs(i: Int): Long = extra[i]
    }

    private val atUs = LongArray(capacity)
    private val kind = ByteArray(capacity)
    private val size = IntArray(capacity)
    private val aux = IntArray(capacity)
    private val extra = LongArray(capacity)
    private var next = 0
    private var count = 0

    private val eventAt = LongArray(EVENTS)
    private val eventText = arrayOfNulls<String>(EVENTS)
    private var eventNext = 0
    private var eventCount = 0

    /** The clock now, in microseconds: taken by the sender just before the send call, to say how long the call took. */
    fun nowUs(): Long = clockNs() / NS_PER_US

    /** An RTP [packet] went out; [startUs] ([nowUs]) was read just before the send call. */
    fun rtp(packet: ByteArray, startUs: Long) {
        put(RTP, packet.size, rtpAux(packet), 0L, startUs)
    }

    /** A control datagram went out: [packet] in the phone-to-dash layout, as [DashStats.sentKey] reads it. */
    fun command(packet: ByteArray) {
        var ops = 0L
        var n = 0
        if (packet.size >= FIRST_TLV_AT + TLV_HEADER) {
            val segments = u16(packet, SEGMENTS_AT)
            var i = FIRST_TLV_AT
            while (n < segments - 1 && i + TLV_HEADER <= packet.size) {
                if (n < MAX_OPS) ops = ops or (key(packet[i].toInt() and BYTE, packet[i + 1].toInt() and BYTE).toLong() shl (KEY_BITS * n))
                n++
                i += TLV_HEADER + u16(packet, i + 2)
            }
        }
        put(COMMAND, packet.size, n, ops, 0L)
    }

    /** A datagram of [size] bytes came from the dash, with [tlvs] (the packet header's own segment is not counted). */
    fun rx(size: Int, tlvs: List<Tlv>) {
        var ops = 0L
        var n = 0
        for (tlv in tlvs) {
            if (DashStats.isHeader(tlv)) continue
            if (n < MAX_OPS) ops = ops or (key(tlv.type, tlv.sub).toLong() shl (KEY_BITS * n))
            n++
        }
        put(RX, size, n, ops, 0L)
    }

    /** A Wi-Fi event of the phone (a supplicant state, a network lost, a scan), as a short text (cut at [EVENT_CHARS]). */
    @Synchronized
    fun event(text: String) {
        eventAt[eventNext] = clockNs() / NS_PER_US
        eventText[eventNext] = if (text.length > EVENT_CHARS) text.take(EVENT_CHARS) else text
        eventNext = (eventNext + 1) % EVENTS
        if (eventCount < EVENTS) eventCount++
    }

    @Synchronized
    fun clear() {
        next = 0
        count = 0
        eventNext = 0
        eventCount = 0
    }

    @Synchronized
    private fun put(kindOf: Byte, bytes: Int, auxOf: Int, extraOf: Long, startUs: Long) {
        val now = clockNs() / NS_PER_US
        atUs[next] = now
        kind[next] = kindOf
        size[next] = bytes
        aux[next] = auxOf
        extra[next] = if (kindOf == RTP) (now - startUs).coerceAtLeast(0L) else extraOf
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /** A copy of the ring and the events, oldest first (made when a report is written, not per packet). */
    @Synchronized
    fun snapshot(): Snapshot {
        val first = (next - count + capacity) % capacity
        val at = LongArray(count)
        val k = ByteArray(count)
        val s = IntArray(count)
        val a = IntArray(count)
        val e = LongArray(count)
        for (i in 0 until count) {
            val j = (first + i) % capacity
            at[i] = atUs[j]
            k[i] = kind[j]
            s[i] = size[j]
            a[i] = aux[j]
            e[i] = extra[j]
        }
        val firstEvent = (eventNext - eventCount + EVENTS) % EVENTS
        val events = (0 until eventCount).map { i ->
            val j = (firstEvent + i) % EVENTS
            Event(eventAt[j], eventText[j].orEmpty())
        }
        return Snapshot(at, k, s, a, e, events)
    }

    companion object {
        const val CAPACITY = 4096

        /** The kinds of an entry. */
        const val RTP: Byte = 1
        const val COMMAND: Byte = 2
        const val RX: Byte = 3

        /** The flags of an RTP entry. */
        const val KEY = 1
        const val FIRST = 2
        const val LAST = 4

        /** TLV keys kept of a command or RX entry. */
        const val MAX_OPS = 3
        const val EVENTS = 64
        const val EVENT_CHARS = 120

        private const val NS_PER_US = 1_000L
        private const val SEQ_MASK = 0xFFFF
        private const val FLAGS_SHIFT = 16
        private const val KEY_BITS = 16
        private const val KEY_MASK = 0xFFFFL
        private const val BYTE = 0xFF

        // The RTP header is 12 bytes; the payload's first byte is the NAL header (single NAL) or the FU indicator (FU-A, type 28), whose next byte is the FU header.
        private const val RTP_HEADER = 12
        private const val MARKER = 0x80
        private const val NAL_TYPE = 0x1F
        private const val FU_A = 28
        private const val FU_START = 0x80
        private const val NAL_IDR = 5
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8

        // Where DashStats.sentKey and K1GPacket.build put the first TLV.
        private const val FIRST_TLV_AT = 17
        private const val TLV_HEADER = 4
        private const val SEGMENTS_AT = 2

        /** The key of a TLV as a number: type in the high byte, sub in the low (`0x0501`). */
        fun key(type: Int, sub: Int): Int = ((type and BYTE) shl 8) or (sub and BYTE)

        /** [key] as the text the logs use, `0501`. */
        fun keyText(key: Int): String = String.format(java.util.Locale.US, "%04X", key)

        private fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and BYTE) shl 8) or (b[at + 1].toInt() and BYTE)

        /**
         * The sequence number and the flags of an RTP [packet] of [RtpPacketizer][com.reau.app.dash.video.RtpPacketizer], read in place, packed as an entry keeps them. A keyframe is
         * one NAL that starts with the SPS (the dash wants SPS, PPS and the IDR bundled), or an IDR slice; the first packet is the start of an FU-A or a NAL that was not cut, the last
         * the one with the marker bit. Too short to read: nothing.
         */
        internal fun rtpAux(packet: ByteArray): Int {
            if (packet.size <= RTP_HEADER) return 0
            val seq = u16(packet, 2)
            val last = packet[1].toInt() and MARKER != 0
            val indicator = packet[RTP_HEADER].toInt() and NAL_TYPE
            val cut = indicator == FU_A && packet.size > RTP_HEADER + 1
            val nal = if (cut) packet[RTP_HEADER + 1].toInt() and NAL_TYPE else indicator
            val first = !cut || packet[RTP_HEADER + 1].toInt() and FU_START != 0
            val key = nal == NAL_SPS || nal == NAL_PPS || nal == NAL_IDR
            val flags = (if (key) KEY else 0) or (if (first) FIRST else 0) or (if (last) LAST else 0)
            return seq or (flags shl FLAGS_SHIFT)
        }
    }
}
