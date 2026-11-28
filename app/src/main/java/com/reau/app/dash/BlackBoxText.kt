package com.reau.app.dash

import kotlin.math.abs

/** The words of the [BlackBoxReport]'s lines: one entry as a token, a time as milliseconds, and many tokens packed in lines of the length the ride log takes. */
internal object BlackBoxText {
    private const val US_PER_MS = 1_000L
    private const val MS_PER_S = 1_000L

    fun token(snap: BlackBox.Snapshot, i: Int, zero: Long): String {
        val at = ms(snap.atUs[i] - zero)
        return when (snap.kind[i]) {
            BlackBox.RTP -> {
                val f = snap.flags(i)
                val marks = (if (f and BlackBox.KEY != 0) "K" else "") + (if (f and BlackBox.FIRST != 0) "F" else "") +
                    (if (f and BlackBox.LAST != 0) "L" else "")
                val took = if (snap.sendUs(i) >= US_PER_MS) " d${ms1(snap.sendUs(i))}" else ""
                "$at v${snap.size[i]} #${snap.seq(i)}" + (if (marks.isEmpty()) "" else " $marks") + took
            }
            BlackBox.COMMAND -> "$at c${snap.size[i]} ${keysOf(snap, i)}"
            else -> "$at r${snap.size[i]} ${keysOf(snap, i)}"
        }.trimEnd()
    }

    /** `0501,0556,0608+2` for a command or a datagram of the dash: the keys kept and how many more TLVs it had. */
    fun keysOf(snap: BlackBox.Snapshot, i: Int): String {
        val kept = (0 until BlackBox.MAX_OPS).map { snap.op(i, it) }.filter { it >= 0 }
        val more = snap.tlvCount(i) - kept.size
        return kept.joinToString(",") { BlackBox.keyText(it) } + if (more > 0) "+$more" else ""
    }

    fun describe(snap: BlackBox.Snapshot, i: Int) = "${snap.size[i]} B ${keysOf(snap, i)}".trim()

    /**
     * [tokens] in lines of at most [BlackBoxReport.MAX_LINE_CHARS] characters, the first starting with [prefix], at most [budget] of them: when they do not fit, the oldest tokens are left out and
     * the first line says how many.
     */
    fun packed(prefix: String, tokens: List<String>, budget: Int, what: String): List<String> {
        if (tokens.isEmpty()) return if (what == "events") emptyList() else listOf("${prefix}none")
        var kept = tokens
        while (true) {
            val lines = pack(prefix + (if (kept.size < tokens.size) "(${tokens.size - kept.size} older $what left out) " else ""), kept)
            if (lines.size <= budget) return lines
            kept = kept.drop(maxOf(1, kept.size * (lines.size - budget) / lines.size))
        }
    }

    private fun pack(prefix: String, tokens: List<String>): List<String> {
        val lines = ArrayList<String>()
        var line = StringBuilder(prefix)
        var empty = true
        for (t in tokens) {
            if (!empty && line.length + t.length + 2 > BlackBoxReport.MAX_LINE_CHARS) {
                lines += line.toString().trimEnd(';', ' ')
                line = StringBuilder()
                empty = true
            }
            if (!empty) line.append("; ")
            line.append(t)
            empty = false
        }
        lines += line.toString()
        return lines
    }

    /** Microseconds as milliseconds with three decimals, signed: `-1999.870`, `+0.512`. */
    fun ms(us: Long): String {
        val a = abs(us)
        return (if (us < 0) "-" else "+") + a / US_PER_MS + "." + (a % US_PER_MS).toString().padStart(3, '0')
    }

    /** Microseconds as milliseconds with one decimal. */
    fun ms1(us: Long): String = String.format(java.util.Locale.US, "%.1f", us / MS_PER_S.toDouble())
}
