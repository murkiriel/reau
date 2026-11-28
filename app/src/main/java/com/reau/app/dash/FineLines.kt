package com.reau.app.dash

import java.util.Locale

/** One video frame of the [FineTail], [age] whole seconds before the report. */
internal class FineFrame(val age: Int, val bytes: Int, val packets: Int, val gapMs: Int, val key: Boolean)

/** One TLV of the [FineTail], sent ([tx]) or received, [age] whole seconds before the report; [title] is one of the `FineLines.TITLE_` marks. */
internal class FineCommand(val age: Int, val tx: Boolean, val code: String, val size: Int, val title: Byte)

/**
 * The lines of the [FineTail] for the ride log, from what it kept: the header (with the window's totals) and one line for each second that had
 * something, oldest first, as
 * `fine tail t-14 s: video K 28728B/21p gap 3ms, P 8100B/6p gap 2ms | tx 0501×3 264B | title 88B same | rx 0101 6B, 0C*×5 40B`.
 * Pure: it has only codes, sizes and moments to write, never what a command said. At most [MAX_LINES] lines and [MAX_CHARS] characters in all; what does
 * not fit is the oldest seconds, said in the header.
 */
internal object FineLines {
    /** Seconds of detail kept and written. */
    const val WINDOW_S = 15

    const val MAX_LINES = 20
    const val MAX_CHARS = 4_000

    /** The most characters of one second's line (past it the line is cut, with an ellipsis). */
    const val MAX_LINE = 260

    const val TITLE_NONE: Byte = 0
    const val TITLE_SAME: Byte = 1
    const val TITLE_CHANGED: Byte = 2
    private val TELEMETRY = setOf("0C", "0F")

    /** [quietS]: for how many seconds the dash had been quiet when this is written (the window ends at its last message), or null when it never spoke. */
    fun report(why: String, frames: List<FineFrame>, commands: List<FineCommand>, quietS: Int? = null): List<String> {
        val head = "fine tail before $why (last $WINDOW_S s)"
        val quiet = quietS?.let { "; the dash had been quiet for $it s (the window ends at its last message)" }.orEmpty()
        if (frames.isEmpty() && commands.isEmpty()) return listOf("$head: nothing exchanged$quiet")
        val seconds = (frames.map { it.age } + commands.map { it.age }).distinct().sortedDescending()
        var body = seconds.map { s -> secondLine(s, frames.filter { it.age == s }, commands.filter { it.age == s }) }
        val summary = summary(frames, commands)
        var cut = false
        while (body.isNotEmpty() && (body.size > MAX_LINES - 1 || head.length + summary.length + quiet.length + body.sumOf { it.length } > MAX_CHARS)) {
            body = body.drop(1)
            cut = true
        }
        return listOf("$head: $summary$quiet" + if (cut) "; the oldest seconds left out for size" else "") + body
    }

    private fun summary(frames: List<FineFrame>, commands: List<FineCommand>): String {
        val biggest = frames.maxByOrNull { it.bytes }
        val video = if (biggest == null) "no video frame" else {
            "${frames.count { it.key }} keyframes in ${frames.size} frames, biggest ${biggest.bytes} B in ${biggest.packets} pkts, " +
                "longest gap between packets ${frames.maxOf { it.gapMs }} ms"
        }
        return "$video; ${commands.count { it.tx }} commands out, ${commands.count { !it.tx }} in"
    }

    private fun secondLine(age: Int, frames: List<FineFrame>, commands: List<FineCommand>): String {
        val video = frames.joinToString(", ") { String.format(Locale.US, "%s %dB/%dp gap %dms", if (it.key) "K" else "P", it.bytes, it.packets, it.gapMs) }
        val parts = listOfNotNull(
            video.takeIf { it.isNotEmpty() }?.let { "video $it" },
            counted(commands.filter { it.tx })?.let { "tx $it" },
            commands.lastOrNull { it.title != TITLE_NONE }?.let { last ->
                "title ${last.size}B ${if (commands.any { it.title == TITLE_CHANGED }) "changed" else "same"}"
            },
            counted(commands.filter { !it.tx })?.let { "rx $it" },
        )
        val line = "fine tail t-$age s: " + parts.joinToString(" | ")
        return if (line.length > MAX_LINE) line.take(MAX_LINE - 1) + "…" else line
    }

    /** `0501×3 264B, 0556 12B`: the codes in the order they first came, how many of each and their bytes; the telemetry kinds are one family, `0C*`. */
    private fun counted(some: List<FineCommand>): String? {
        if (some.isEmpty()) return null
        val byCode = LinkedHashMap<String, Pair<Int, Int>>()
        some.forEach { c ->
            val code = if (c.code.take(2) in TELEMETRY) "${c.code.take(2)}*" else c.code
            val (n, bytes) = byCode[code] ?: (0 to 0)
            byCode[code] = (n + 1) to (bytes + c.size)
        }
        return byCode.entries.joinToString(", ") { (code, nb) -> (if (nb.first > 1) "$code×${nb.first}" else code) + " ${nb.second}B" }
    }
}
