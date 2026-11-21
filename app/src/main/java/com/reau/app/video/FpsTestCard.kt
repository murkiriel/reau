package com.reau.app.video

import java.util.Locale

/**
 * The numbers on the FPS test card ([FpsTestCardInk] draws them): what a filmed frame must say so that, frame after frame, a lost one, a repeated one
 * or one out of order can be read off the film, and so that the dash's real pace can be compared with the announced one. No Android in it: tested on the JVM.
 *
 * [n] is the count of frames really handed to the encoder since the card was switched on (the first is 0); [fps] is the pace in force for that frame.
 * The number shown wraps at [NUMBER_WRAP] (four digits) and the binary at [BITS] bits (4096), the seconds at 100 (two digits), so the layout never changes.
 */
object FpsTestCard {
    /** The bits of the binary strip. */
    const val BITS = 12

    /** The number in the corner has four digits: it wraps here. */
    const val NUMBER_WRAP = 10_000

    /** The seconds have two digits: they wrap here. */
    const val SECONDS_WRAP = 100

    /** What one frame says. [bits] has [BITS] entries, the most significant first. */
    data class Cells(
        /** The big number, four digits ("0006"). */
        val number: String,
        /** The pace, "6 FPS". */
        val rate: String,
        /** Whole seconds so far at this pace, two digits ("01"). */
        val seconds: String,
        /** The frame inside its second, 1..[fps]. */
        val slot: Int,
        /** The pace the slots are counted over. */
        val fps: Int,
        /** True for an even [n]: the square is white, else dark grey. */
        val even: Boolean,
        /** [n] in binary, the most significant bit first; true is a 1 (white). */
        val bits: List<Boolean>,
    ) {
        /** "seg 01  quadro 1/6". */
        val secondsLine: String get() = "seg $seconds  quadro $slot/$fps"
    }

    /** The cells of frame [n] at [fps] (below 1 it counts as 1; a negative [n] as 0). */
    fun cells(n: Long, fps: Int): Cells {
        val rate = fps.coerceAtLeast(1)
        val count = n.coerceAtLeast(0L)
        return Cells(
            number = String.format(Locale.US, "%04d", (count % NUMBER_WRAP).toInt()),
            rate = "$rate FPS",
            seconds = String.format(Locale.US, "%02d", ((count / rate) % SECONDS_WRAP).toInt()),
            slot = (count % rate).toInt() + 1,
            fps = rate,
            even = count % 2 == 0L,
            bits = (BITS - 1 downTo 0).map { ((count shr it) and 1L) == 1L },
        )
    }

    /** How often the log says how many frames were delivered, in ms. */
    const val SUMMARY_EVERY_MS = 10_000L

    /** The log line of the switch: "on" at [fps], or "off" after [delivered] frames. */
    fun switchLine(on: Boolean, fps: Int, delivered: Long): String =
        if (on) "fps test card ON: the picture is the numbered card at $fps fps, counting from 0" else "fps test card OFF after $delivered frames delivered"

    /** The log line every [SUMMARY_EVERY_MS]: [frames] delivered to the encoder in [elapsedMs], [delivered] in all, at [fps] now. */
    fun summaryLine(frames: Int, elapsedMs: Long, delivered: Long, fps: Int): String {
        val seconds = String.format(Locale.US, "%.1f", elapsedMs / MS_PER_S)
        val real = String.format(Locale.US, "%.1f", if (elapsedMs > 0) frames * MS_PER_S / elapsedMs else 0.0)
        return "fps test card: $frames frames delivered in $seconds s ($real fps), $delivered in all, target $fps fps"
    }

    private const val MS_PER_S = 1000.0
}
