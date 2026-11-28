package com.reau.app.dash

import com.reau.app.dash.BlackBoxText.describe
import com.reau.app.dash.BlackBoxText.keysOf
import com.reau.app.dash.BlackBoxText.ms
import com.reau.app.dash.BlackBoxText.ms1
import com.reau.app.dash.BlackBoxText.packed
import com.reau.app.dash.BlackBoxText.token

/**
 * The lines of the ride log (`[blackbox]`) that say what REau sent to the dash in its last seconds, written when the dash goes silent (0.3.99 test build). Reads a
 * [BlackBox.Snapshot]; no Android in it, the times are given. The question it answers: did a spike of a few milliseconds (a keyframe, a command next to it) come right before the
 * dash stopped talking?
 *
 * Lines, in this order (at most [MAX_LINES], none over [MAX_LINE_CHARS] characters):
 *  1. the header: the ring's size, and when the dash was last heard (its datagram's size and TLV keys);
 *  2. the peak of datagrams and bytes sent in any [WINDOWS_MS] ms, in the last [WINDOW_US] / 1e6 s;
 *  3. the same in the [BEFORE_RX_US] / 1e6 s before the last datagram from the dash;
 *  4. the longest run of RTP packets sent less than [BURST_GAP_US] µs apart, the shortest gap between two sent datagrams, how often a keyframe packet and a command went out within
 *     [NEAR_US] / 1e3 ms of each other, and the slowest send calls;
 *  5. what was sent after the dash's last datagram, what the dash said in the window and its longest silence between two datagrams;
 *  6. the phone's Wi-Fi events in the window;
 *  7. the entries from [BEFORE_RX_US] / 1e6 s before the last datagram of the dash to [AFTER_RX_US] / 1e3 ms after it, many to a line, the oldest left out first when they do not fit.
 * The times in lines 5 to 7 are milliseconds from the dash's last datagram (negative: before it), or from the report when the dash was never heard.
 */
internal object BlackBoxReport {
    /** The tag of the lines. */
    const val TAG = "blackbox"

    /** How far back the report looks. */
    const val WINDOW_US = 10_000_000L

    /** The span before the dash's last datagram that gets its own peaks and its entries. */
    const val BEFORE_RX_US = 2_000_000L

    /** The span after it whose entries are listed too (what REau did at once after the dash's last word). */
    const val AFTER_RX_US = 500_000L

    /** Sliding windows of the peaks, in milliseconds. */
    val WINDOWS_MS = longArrayOf(10L, 50L, 100L)

    /** Two RTP packets this close (µs) are one run: a keyframe is packetized in a loop with nothing between its packets. */
    const val BURST_GAP_US = 2_000L

    /** A keyframe packet and a command this close (µs) are the coincidence the report looks for. */
    const val NEAR_US = 20_000L

    /** A send call this slow (µs) is told in the line of the slow sends. */
    const val SLOW_SEND_US = 5_000L

    const val MAX_LINES = 60
    const val MAX_LINE_CHARS = 880
    private const val MAX_EVENT_LINES = 3
    private const val US_PER_MS = 1_000L
    private const val NS_PER_S = 1_000_000L
    private const val SUMMARY_LINES = 5

    private const val LEGEND =
        "entries, ms from the last dash datagram (v=RTP size #seq K=keyframe F=first L=last, d=ms the send took; " +
            "c=command size keys; r=dash datagram size keys)"

    /** What the window holds: how many entries, and the indexes of the sent ones ([tx]) and the dash's ([rx]), in time order. */
    private class Slice(val size: Int, val tx: List<Int>, val rx: List<Int>)

    private class Peak(val packets: Int, val bytes: Long, val bytesAtUs: Long)

    /** The lines for [why] at [nowUs], from [snap]. */
    fun lines(why: String, snap: BlackBox.Snapshot, nowUs: Long): List<String> {
        val from = nowUs - WINDOW_US
        val inWindow = (0 until snap.count).filter { snap.atUs[it] in from..nowUs }
        val tx = inWindow.filter { snap.isTx(it) }
        val rx = inWindow.filter { snap.kind[it] == BlackBox.RX }
        val lastRx = rx.lastOrNull()
        val zero = lastRx?.let { snap.atUs[it] } ?: nowUs
        val out = ArrayList<String>()
        out += header(why, snap, Slice(inWindow.size, tx, rx), nowUs)
        out += "peak sent in the last ${WINDOW_US / NS_PER_S} s: ${peaks(snap, tx, zero)}"
        out += "peak sent in the ${BEFORE_RX_US / NS_PER_S} s before the dash's last datagram: " +
            if (lastRx == null) "none heard" else peaks(snap, tx.filter { snap.atUs[it] in zero - BEFORE_RX_US..zero }, zero)
        out += runsAndCoincidence(snap, tx, zero)
        out += afterAndRx(snap, tx, rx, lastRx, zero)
        val events = snap.events.filter { it.atUs in from..nowUs }
        val eventLines = packed("wifi events, ms from the last dash datagram: ", events.map { "${ms(it.atUs - zero)} ${it.text}" }, MAX_EVENT_LINES, "events")
        out += eventLines
        val shown = inWindow.filter { snap.atUs[it] in zero - BEFORE_RX_US..zero + AFTER_RX_US }
        out += packed("$LEGEND: ", shown.map { token(snap, it, zero) }, (MAX_LINES - SUMMARY_LINES - eventLines.size).coerceAtLeast(1), "entries")
        return out
    }

    private fun header(why: String, snap: BlackBox.Snapshot, slice: Slice, nowUs: Long): String {
        val rtp = slice.tx.count { snap.kind[it] == BlackBox.RTP }
        val heard = slice.rx.lastOrNull()?.let {
            "last datagram from the dash ${ms(nowUs - snap.atUs[it]).removePrefix("+")} ms before this report (${describe(snap, it)})"
        } ?: "the dash was never heard in the ring"
        return "box for $why: ${slice.size} entries in the last ${WINDOW_US / NS_PER_S} s " +
            "(sent ${slice.tx.size}: rtp $rtp, commands ${slice.tx.size - rtp}; from the dash ${slice.rx.size}; " +
            "wifi events ${snap.events.count { it.atUs >= nowUs - WINDOW_US }}); $heard"
    }

    private fun peaks(snap: BlackBox.Snapshot, tx: List<Int>, zero: Long): String =
        WINDOWS_MS.joinToString("; ") { w ->
            val p = peak(snap, tx, w * US_PER_MS)
            "$w ms: ${p.packets} pkts, ${p.bytes} B" + if (p.packets > 0) " (bytes at ${ms(p.bytesAtUs - zero)})" else ""
        }

    /** The most datagrams and the most bytes in any window of [windowUs] (half open, ending at a datagram). [tx] in time order. */
    private fun peak(snap: BlackBox.Snapshot, tx: List<Int>, windowUs: Long): Peak {
        var left = 0
        var bytes = 0L
        var bestPackets = 0
        var bestBytes = 0L
        var bestAt = 0L
        for (right in tx.indices) {
            bytes += snap.size[tx[right]]
            while (snap.atUs[tx[right]] - snap.atUs[tx[left]] >= windowUs) {
                bytes -= snap.size[tx[left]]
                left++
            }
            bestPackets = maxOf(bestPackets, right - left + 1)
            if (bytes > bestBytes) {
                bestBytes = bytes
                bestAt = snap.atUs[tx[right]]
            }
        }
        return Peak(bestPackets, bestBytes, bestAt)
    }

    private fun runsAndCoincidence(snap: BlackBox.Snapshot, tx: List<Int>, zero: Long): String {
        var run = 0
        var runBytes = 0L
        var runStart = 0L
        var best = 0
        var bestBytes = 0L
        var bestStart = 0L
        var bestSpan = 0L
        var minGap = Long.MAX_VALUE
        for (n in tx.indices) {
            val i = tx[n]
            val gap = if (n > 0) snap.atUs[i] - snap.atUs[tx[n - 1]] else Long.MAX_VALUE
            if (n > 0) minGap = minOf(minGap, gap)
            val continues = n > 0 && snap.kind[i] == BlackBox.RTP && snap.kind[tx[n - 1]] == BlackBox.RTP && gap < BURST_GAP_US
            if (continues) {
                run++
                runBytes += snap.size[i]
            } else {
                run = 1
                runBytes = snap.size[i].toLong()
                runStart = snap.atUs[i]
            }
            if (run > best) {
                best = run
                bestBytes = runBytes
                bestStart = runStart
                bestSpan = snap.atUs[i] - runStart
            }
        }
        val burst = if (best == 0) {
            "no RTP run"
        } else {
            "longest run of RTP packets less than ${BURST_GAP_US / US_PER_MS} ms apart: $best pkts, $bestBytes B in ${ms1(bestSpan)} ms " +
                "at ${ms(bestStart - zero)}"
        }
        val gaps = if (minGap == Long.MAX_VALUE) "no gap" else "shortest gap between sent datagrams ${minGap} µs"
        return "$burst; $gaps; ${coincidence(snap, tx, zero)}; ${slowSends(snap, tx, zero)}"
    }

    /** How many commands had a keyframe packet within [NEAR_US] of them (and the closest of them), of the keyframes and commands there were. */
    private fun coincidence(snap: BlackBox.Snapshot, tx: List<Int>, zero: Long): String {
        val keys = tx.filter { snap.kind[it] == BlackBox.RTP && snap.flags(it) and BlackBox.KEY != 0 }
        val keyTimes = LongArray(keys.size) { snap.atUs[keys[it]] }
        val commands = tx.filter { snap.kind[it] == BlackBox.COMMAND }
        val frames = keys.count { snap.flags(it) and BlackBox.FIRST != 0 }
        var near = 0
        var closest = Long.MAX_VALUE
        var closestAt = 0L
        var closestKey = ""
        for (c in commands) {
            val d = nearest(keyTimes, snap.atUs[c])
            if (d <= NEAR_US) {
                near++
                if (d < closest) {
                    closest = d
                    closestAt = snap.atUs[c]
                    closestKey = keysOf(snap, c)
                }
            }
        }
        val example = if (near > 0) ", closest ${ms1(closest)} ms: command $closestKey at ${ms(closestAt - zero)}" else ""
        val verdict = if (near > 0) "yes, $near ${if (near == 1) "time" else "times"}" else "no"
        return "keyframe packet within ${NEAR_US / US_PER_MS} ms of a command: $verdict ($frames keyframes, ${commands.size} commands)$example"
    }

    /** The distance from [at] to the nearest of the sorted [times]; max when there are none. */
    private fun nearest(times: LongArray, at: Long): Long {
        if (times.isEmpty()) return Long.MAX_VALUE
        var lo = 0
        var hi = times.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] < at) lo = mid + 1 else hi = mid
        }
        val after = if (lo < times.size) times[lo] - at else Long.MAX_VALUE
        val before = if (lo > 0) at - times[lo - 1] else Long.MAX_VALUE
        return minOf(after, before)
    }

    private fun slowSends(snap: BlackBox.Snapshot, tx: List<Int>, zero: Long): String {
        val rtp = tx.filter { snap.kind[it] == BlackBox.RTP }
        val slow = rtp.filter { snap.sendUs(it) >= SLOW_SEND_US }
        val worst = rtp.maxByOrNull { snap.sendUs(it) } ?: return "no RTP send timed"
        return "send calls of ${SLOW_SEND_US / US_PER_MS} ms or more: ${slow.size}, slowest ${ms1(snap.sendUs(worst))} ms at ${ms(snap.atUs[worst] - zero)}"
    }

    private fun afterAndRx(snap: BlackBox.Snapshot, tx: List<Int>, rx: List<Int>, lastRx: Int?, zero: Long): String {
        val after = if (lastRx == null) emptyList() else tx.filter { snap.atUs[it] > zero }
        val rtp = after.count { snap.kind[it] == BlackBox.RTP }
        val sent = "sent after the dash's last datagram: ${after.size} datagrams ($rtp rtp, ${after.size - rtp} commands, ${after.sumOf { snap.size[it] }} B)"
        var heartbeats = 0
        var telemetry = 0
        var other = 0
        var longest = 0L
        for ((n, i) in rx.withIndex()) {
            when (snap.op(i, 0) shr 8) {
                HEARTBEAT_TYPE -> if (snap.op(i, 0) == HEARTBEAT) heartbeats++ else other++
                TELEMETRY_TYPE -> telemetry++
                else -> other++
            }
            if (n > 0) longest = maxOf(longest, snap.atUs[i] - snap.atUs[rx[n - 1]])
        }
        val silence = if (rx.size > 1) "${ms1(longest)} ms" else "none"
        val heard = "dash datagrams by their first key: 0101 heartbeat $heartbeats, 0C telemetry $telemetry, other $other; longest silence between two $silence"
        return "$sent; $heard"
    }

    private const val HEARTBEAT_TYPE = 0x01
    private const val HEARTBEAT = 0x0101
    private const val TELEMETRY_TYPE = 0x0C
}
