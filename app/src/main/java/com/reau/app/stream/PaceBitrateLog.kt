package com.reau.app.stream

import java.util.Locale

/**
 * What each pace really cost in bits, for the ride log (plan 32): the nominal bitrate of a profile says little, so every poll's frames and
 * bytes are added to the pace that was IN FORCE during it, and a summary line says each pace's average bitrate (bytes x 8 / time), the
 * frames per second actually sent and how long the stream spent at it. The paces are told apart by what put the stream there ([Kind]) and
 * by the rate itself, so profile A at 8 fps, profile A capped to 6 by the reserve and the light screens at 6 are three lines, not one.
 *
 * The line is written every [everyMs] of stream ([periodicLine]) and when the stream ends ([line]); both are the totals since the stream
 * began, never reset. A pace with no time in it is left out, so nothing divides by zero. Pure, with the clock passed in, so the JVM tests cover it.
 */
internal class PaceBitrateLog(private val everyMs: Long = SUMMARY_EVERY_MS) {
    /** What put the stream at its pace, in the order the line lists them. */
    enum class Kind(val label: String) {
        /** Profile A at the rate the stream was started with. */
        A("A"),

        /** Profile B, the reduced one (the half of A). */
        B("B"),

        /** The light screens of Android Auto, faster than the profile. */
        LIGHT("light"),

        /** Profile A held under the reserve's ceiling ([com.reau.app.dash.DashConfig.RESERVE_CAP_FPS]). */
        RESERVE("reserve"),

        /** The bike is in the reserve and the debug switch has the ceiling off ([com.reau.app.settings.REauSettings.reserveCapOn]): the profile at its own pace, measured apart. */
        RESERVE_UNCAPPED("reserve (no cap)"),
    }

    private data class Key(val kind: Kind, val fps: Int)

    private class Total {
        var frames = 0L
        var bytes = 0L
        var ms = 0L
    }

    private val totals = HashMap<Key, Total>()
    private var lastPollMs: Long? = null
    private var lastSummaryMs = 0L

    /** A stream begins at [nowMs]: nothing is counted yet, and the totals start over. */
    fun start(nowMs: Long) {
        totals.clear()
        lastPollMs = nowMs
        lastSummaryMs = nowMs
    }

    /**
     * The poll at [nowMs] sent [frames] frames and [bytes] bytes, at the pace [kind] / [fps] that was in force since the poll before it. A poll
     * with no time since the one before (or a clock that went back) adds nothing: its bytes would have no time to be averaged over.
     */
    fun add(kind: Kind, fps: Int, frames: Int, bytes: Long, nowMs: Long) {
        val before = lastPollMs
        lastPollMs = nowMs
        if (before == null || nowMs <= before) return
        val total = totals.getOrPut(Key(kind, fps)) { Total() }
        total.frames += frames
        total.bytes += bytes
        total.ms += nowMs - before
    }

    /** The summary line when [everyMs] have gone by since the last one (or since the start), else null; the clock for the next one starts here. */
    fun periodicLine(nowMs: Long): String? {
        if (nowMs - lastSummaryMs < everyMs) return null
        lastSummaryMs = nowMs
        return line()
    }

    /** The summary line of everything so far, or null when no pace has any time yet. */
    fun line(): String? {
        val parts = totals.entries
            .filter { it.value.ms > 0 }
            .sortedWith(compareBy({ it.key.kind.ordinal }, { it.key.fps }))
            .map { (key, t) ->
                String.format(
                    Locale.US, "%s %d fps avg %.0f kbps %.1f fps sent %.1f min",
                    key.kind.label, key.fps, t.bytes * BITS / t.ms, t.frames * MS_PER_SECOND / t.ms, t.ms / MS_PER_MINUTE,
                )
            }
        return if (parts.isEmpty()) null else "bitrate by pace: ${parts.joinToString("; ")}"
    }

    companion object {
        /** A summary every 5 minutes of stream, and one at the end. */
        const val SUMMARY_EVERY_MS = 300_000L

        // bytes x 8 / ms is bits per millisecond, which is kbps.
        private const val BITS = 8.0
        private const val MS_PER_SECOND = 1000.0
        private const val MS_PER_MINUTE = 60_000.0
    }
}
