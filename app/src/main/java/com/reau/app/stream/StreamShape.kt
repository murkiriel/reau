package com.reau.app.stream

import java.util.Locale

/**
 * What a stream's frames looked like to the dash, for one line of the ride log (26/11/2026, the test of the encoder): bytes a frame do not say it, but what separates
 * one encoder's stream from another's is the keyframe (the MediaTek one makes them 4 to 10 times bigger than the Qualcomm one) and the worst second. Every frame that went
 * out is [add]ed, with the time it left; [line] says the totals since the stream began, and [periodicLine] the same every [everyMs] of stream (the process may be
 * ended before the stream is). Pure, with the clock passed in, so the JVM tests cover it.
 *
 * - the keyframes: how many, and the bytes and RTP packets of the average one and of the biggest;
 * - the slices of a frame (NAL units of a picture; the dash is known to take one): the most any frame had and how many frames had more than one;
 * - the worst second: the most bytes, and the most RTP packets, that left in any [PEAK_WINDOW_MS] (a sliding window ending at each frame).
 */
internal class StreamShape(private val everyMs: Long = SUMMARY_EVERY_MS) {
    private class Sent(val atMs: Long, val bytes: Int, val packets: Int)

    private val recent = ArrayDeque<Sent>()
    private var recentBytes = 0L
    private var recentPackets = 0
    private var frames = 0
    private var keyframes = 0
    private var keyBytesSum = 0L
    private var keyBytesMax = 0
    private var keyPacketsSum = 0L
    private var keyPacketsMax = 0
    private var slicesMax = 0
    private var framesWithManySlices = 0
    private var peakBytes = 0L
    private var peakPackets = 0
    private var lastLineMs: Long? = null

    /** One frame of [bytes] bytes in [packets] RTP packets, of [slices] slices, went out at [atMs]. */
    fun add(atMs: Long, bytes: Int, packets: Int, key: Boolean, slices: Int) {
        if (lastLineMs == null) lastLineMs = atMs
        frames++
        if (key) {
            keyframes++
            keyBytesSum += bytes
            keyBytesMax = maxOf(keyBytesMax, bytes)
            keyPacketsSum += packets
            keyPacketsMax = maxOf(keyPacketsMax, packets)
        }
        slicesMax = maxOf(slicesMax, slices)
        if (slices > 1) framesWithManySlices++
        recent.addLast(Sent(atMs, bytes, packets))
        recentBytes += bytes
        recentPackets += packets
        // The window is (atMs - PEAK_WINDOW_MS, atMs]: a frame exactly one second back is out of it.
        while (recent.first().atMs <= atMs - PEAK_WINDOW_MS) {
            val old = recent.removeFirst()
            recentBytes -= old.bytes
            recentPackets -= old.packets
        }
        peakBytes = maxOf(peakBytes, recentBytes)
        peakPackets = maxOf(peakPackets, recentPackets)
    }

    /** The line of everything so far, or null before the first frame. */
    fun line(): String? {
        if (frames == 0) return null
        val keyBytesAvg = if (keyframes > 0) keyBytesSum / keyframes else 0L
        val keyPacketsAvg = if (keyframes > 0) keyPacketsSum.toDouble() / keyframes else 0.0
        val slices = if (slicesMax <= 1) "1" else "$slicesMax multi=$framesWithManySlices/$frames"
        return String.format(
            Locale.US,
            "video shape: frames=%d keyframes=%d keybytes avg=%d max=%d keypackets avg=%.1f max=%d slices=%s peak1s=%dkbps/%dpkt",
            frames, keyframes, keyBytesAvg, keyBytesMax, keyPacketsAvg, keyPacketsMax, slices, peakBytes * BITS_PER_BYTE / KILO, peakPackets,
        )
    }

    /** [line] when [everyMs] of stream have gone by since the last one (or since the first frame), else null; the clock for the next one starts here. */
    fun periodicLine(nowMs: Long): String? {
        val last = lastLineMs ?: return null
        if (nowMs - last < everyMs) return null
        lastLineMs = nowMs
        return line()
    }

    companion object {
        /** The window of the worst second. */
        const val PEAK_WINDOW_MS = 1_000L

        /** A line every 5 minutes of stream, like the one of the bitrate by pace, and one at the end. */
        const val SUMMARY_EVERY_MS = 300_000L

        private const val BITS_PER_BYTE = 8
        private const val KILO = 1000
    }
}
