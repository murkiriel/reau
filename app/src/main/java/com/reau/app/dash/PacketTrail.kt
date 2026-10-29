package com.reau.app.dash

import com.reau.app.dash.protocol.Tlv
import java.util.Locale

/**
 * The black box of the dash link: the last [WINDOW_MS] of control packets (type and subtype, direction, size, moment), kept in
 * memory only and written to the ride log when the Wi-Fi goes or a watchdog fires, with the detailed log off. Never a payload:
 * the text cards (route title, music, call, album picture) and the key exchange show their key and nothing else, as [DashDump]
 * leaves them out. The ring is [capacity] entries; what is older than [WINDOW_MS] is not told. Thread-safe (the receive loop, the
 * heartbeats and the Wi-Fi callbacks write and read it).
 */
class PacketTrail(private val capacity: Int = CAPACITY) {
    private class Entry(val atMs: Long, val tx: Boolean, val key: String, val size: Int)

    private val ring = ArrayDeque<Entry>()

    /** One TLV of a packet going out ([tx]) or coming in, [size] bytes of value; the value itself is never kept. */
    @Synchronized fun add(nowMs: Long, tx: Boolean, tlv: Tlv) {
        ring.addLast(Entry(nowMs, tx, DashStats.key(tlv), tlv.value.size))
        while (ring.size > capacity) ring.removeFirst()
    }

    @Synchronized fun clear() = ring.clear()

    @Synchronized fun size(): Int = ring.size

    /**
     * The last 30 s up to [nowMs], oldest first, in at most [MAX_LINES] lines, one for each [BUCKET_MS] of that time:
     * `t-12.0 s tx 0556×4, 0608, 0501 (88 B); rx 0101, 0C*×5 (240 B)` (the age of the bucket's first packet; repeats counted; the
     * telemetry kinds are one family, `0C*`). Empty when nothing was exchanged.
     */
    @Synchronized fun lines(nowMs: Long): List<String> {
        val live = ring.filter { nowMs - it.atMs in 0 until WINDOW_MS }
        return live.groupBy { (nowMs - it.atMs) / BUCKET_MS }
            .toSortedMap(compareByDescending { it })
            .map { (_, bucket) -> line(bucket, nowMs) }
    }

    /** What the log writes for [why] (`the Wi-Fi was lost`, a watchdog): a header line and the [lines], or the one line saying it was quiet. */
    fun report(why: String, nowMs: Long): List<String> {
        val body = lines(nowMs)
        val head = "packet trail before $why (last ${WINDOW_MS / MS_PER_S.toLong()} s)"
        return if (body.isEmpty()) listOf("$head: no packets") else listOf("$head:") + body
    }

    private fun line(bucket: List<Entry>, nowMs: Long): String {
        val first = bucket.first()
        val parts = listOf(true, false).mapNotNull { tx ->
            bucket.filter { it.tx == tx }.takeIf { it.isNotEmpty() }?.let { part ->
                val counted = LinkedHashMap<String, Int>()
                part.forEach { counted.merge(group(it.key), 1, Int::plus) }
                val keys = counted.entries.joinToString(", ") { (k, n) -> if (n > 1) "$k×$n" else k }
                "${if (tx) "tx" else "rx"} $keys (${part.sumOf { it.size }} B)"
            }
        }
        return String.format(Locale.US, "t-%.1f s %s", (nowMs - first.atMs) / MS_PER_S, parts.joinToString("; "))
    }

    /** The telemetry kinds (0C.., 0F..) come by the dozen; each family is one key, as in the `dash-stats` line. */
    private fun group(key: String) = if (key.take(2) in TELEMETRY) "${key.take(2)}*" else key

    companion object {
        const val CAPACITY = 256
        const val WINDOW_MS = 30_000L
        const val MAX_LINES = 12
        private const val BUCKET_MS = WINDOW_MS / MAX_LINES
        private const val MS_PER_S = 1_000.0
        private val TELEMETRY = setOf("0C", "0F")
    }
}
