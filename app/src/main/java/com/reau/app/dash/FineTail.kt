package com.reau.app.dash

import java.util.Locale

/**
 * The fine detail of the last [FineLines.WINDOW_S] seconds before the dash went quiet, for the ride log: each video frame (its moment, bytes, whether it is
 * a keyframe, its RTP packets and the longest gap between two of its packets), each command that went to the dash and each that came from it
 * (the code only, such as `0501` or `0C*`, and its size in bytes, never what it said) and, of the title of the route card (`0501`), its size and
 * whether it changed since the one before. [SendTail] says how much went out each second and the last keyframe; this says what went out, frame by
 * frame and command by command, to tell a burst, a long keyframe or a missing message apart after a dash that restarted by itself.
 *
 * Privacy: the only things given in are a code, a size and, for the title, the bytes it is made of, of which only whether they equal the last
 * title's is kept (a hash, never written). No text of a street, a song, a call or the key exchange gets in or out.
 *
 * Cheap on the hot path: the video's packets are not seen here (only the frame's end, 2 to 6 times a second), and a command is written into arrays
 * that exist, with no allocation, under a lock nobody else holds for long. Times are `System.nanoTime()` readings, given by the caller in the tests.
 * Thread-safe (the receive loop, the cards, the heartbeats and the encoder's output thread write it).
 *
 * The window ends at the last message that CAME FROM the dash, not at the moment the report is written (26/11/2026, review of the plan 34, A2): the watchdog fires 4 s after the
 * dash went quiet, and counted back from the report 4 of the 15 s would be frames sent into the silence. What is told is the 15 s before it, and the header says for how long the
 * dash had been quiet. A dash that never spoke in this session has no such moment, and the window ends at the report as before.
 */
class FineTail {
    private val frameAt = LongArray(FRAME_CAPACITY)
    private val frameBytes = IntArray(FRAME_CAPACITY)
    private val framePackets = IntArray(FRAME_CAPACITY)
    private val frameGapMs = IntArray(FRAME_CAPACITY)
    private val frameKey = BooleanArray(FRAME_CAPACITY)
    private var frames = 0L

    private val commandAt = LongArray(COMMAND_CAPACITY)
    private val commandCode = arrayOfNulls<String>(COMMAND_CAPACITY)
    private val commandSize = IntArray(COMMAND_CAPACITY)
    private val commandTx = BooleanArray(COMMAND_CAPACITY)
    private val commandTitle = ByteArray(COMMAND_CAPACITY)
    private var commands = 0L

    private var titleHash = 0
    private var titleSeen = false
    private var reportedNs = NONE
    private var lastRxNs = NONE

    /** A video frame of [bytes] bytes went out in [packets] RTP packets, the longest gap between two of them [maxGapNs], at [nowNs]. */
    @Synchronized
    fun frame(bytes: Int, packets: Int, key: Boolean, maxGapNs: Long, nowNs: Long = System.nanoTime()) {
        val i = (frames % FRAME_CAPACITY).toInt()
        frameAt[i] = nowNs
        frameBytes[i] = bytes
        framePackets[i] = packets
        frameGapMs[i] = (maxGapNs.coerceAtLeast(0L) / NS_PER_MS).toInt().coerceAtMost(MAX_GAP_MS)
        frameKey[i] = key
        frames++
    }

    /** A TLV of a packet that went to the dash: its [code] (`0501`) and the [value]'s size; for the title (`0501`) also whether it differs from the last. */
    @Synchronized
    fun sent(code: String, value: ByteArray, nowNs: Long = System.nanoTime()) {
        var title = TITLE_NONE
        if (code == TITLE_CODE) {
            val hash = value.contentHashCode()
            title = if (titleSeen && hash == titleHash) TITLE_SAME else TITLE_CHANGED
            titleHash = hash
            titleSeen = true
        }
        add(nowNs, tx = true, code, value.size, title)
    }

    /** A TLV that came from the dash: its [code] and the size of its value in bytes. */
    @Synchronized
    fun received(code: String, size: Int, nowNs: Long = System.nanoTime()) {
        if (lastRxNs == NONE || nowNs > lastRxNs) lastRxNs = nowNs
        add(nowNs, tx = false, code, size, TITLE_NONE)
    }

    private fun add(nowNs: Long, tx: Boolean, code: String, size: Int, title: Byte) {
        val i = (commands % COMMAND_CAPACITY).toInt()
        commandAt[i] = nowNs
        commandCode[i] = code
        commandSize[i] = size
        commandTx[i] = tx
        commandTitle[i] = title
        commands++
    }

    @Synchronized
    fun clear() {
        frames = 0L
        commands = 0L
        titleSeen = false
        titleHash = 0
        commandCode.fill(null)
        reportedNs = NONE
        lastRxNs = NONE
    }

    /**
     * The lines for [why] (`a watchdog fired`, `the Wi-Fi was lost`) written at [nowNs], or an empty list when one was written less than [ONCE_S] seconds
     * ago: the Wi-Fi's loss and the watchdog that follows it are one event, as for [SendTail.reportOnce].
     */
    @Synchronized
    fun reportOnce(why: String, nowNs: Long = System.nanoTime()): List<String> {
        if (reportedNs != NONE && nowNs - reportedNs in 0 until ONCE_S * NS_PER_S) return emptyList()
        reportedNs = nowNs
        return report(why, nowNs)
    }

    /** The lines for [why] at [nowNs], whatever was written before ([FineLines.report]); the window ends at the dash's last message when there was one before [nowNs]. */
    @Synchronized
    fun report(why: String, nowNs: Long): List<String> {
        val anchorNs = if (lastRxNs == NONE) nowNs else minOf(nowNs, lastRxNs)
        val quietS = if (lastRxNs == NONE) null else ((nowNs - anchorNs) / NS_PER_S).toInt()
        return FineLines.report(why, framesIn(anchorNs), commandsIn(anchorNs), quietS)
    }

    private fun ageOf(nowNs: Long, atNs: Long): Int? =
        ((nowNs - atNs) / NS_PER_S).takeIf { nowNs - atNs >= 0 && it < FineLines.WINDOW_S }?.toInt()

    private fun framesIn(nowNs: Long): List<FineFrame> = (maxOf(0L, frames - FRAME_CAPACITY) until frames).mapNotNull { n ->
        val i = (n % FRAME_CAPACITY).toInt()
        ageOf(nowNs, frameAt[i])?.let { FineFrame(it, frameBytes[i], framePackets[i], frameGapMs[i], frameKey[i]) }
    }

    private fun commandsIn(nowNs: Long): List<FineCommand> = (maxOf(0L, commands - COMMAND_CAPACITY) until commands).mapNotNull { n ->
        val i = (n % COMMAND_CAPACITY).toInt()
        ageOf(nowNs, commandAt[i])?.let { FineCommand(it, commandTx[i], commandCode[i].orEmpty(), commandSize[i], commandTitle[i]) }
    }

    companion object {
        /** Frames kept: the video runs at 2 to 6 a second, so [FineLines.WINDOW_S] seconds are 90 at the most. */
        const val FRAME_CAPACITY = 128

        /** Commands kept, both ways: some dozens a second with the telemetry. */
        const val COMMAND_CAPACITY = 1024

        const val ONCE_S = 30L
        private const val TITLE_CODE = "0501"
        private const val TITLE_NONE = FineLines.TITLE_NONE
        private const val TITLE_SAME = FineLines.TITLE_SAME
        private const val TITLE_CHANGED = FineLines.TITLE_CHANGED
        private const val MAX_GAP_MS = 60_000
        private const val NS_PER_MS = 1_000_000L
        private const val NS_PER_S = 1_000_000_000L
        private const val NONE = Long.MIN_VALUE
    }
}
