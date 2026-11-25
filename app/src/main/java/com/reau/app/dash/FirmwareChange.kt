package com.reau.app.dash

/**
 * The ride log's note when the dash's firmware is not the one the last session saw. The firmware is what the dash answers `0A02` with (`0B02`,
 * the partitions, [DashReport.partitions]); [DashReadings] keeps the last answer between runs, so the one it held before the new answer is the
 * last version seen. The version is not personal, and nothing but it is written (no name, serial or VIN). Pure: [line] is the rule.
 */
object FirmwareChange {
    /** The partitions of a `0B02` value as the log writes them, `BB.MM.mm` each ("00.02.07 01.11.63"); null when the value lists none. */
    fun versions(hex: String?): String? =
        DashReport.partitions(hex).takeIf { it.isNotEmpty() }
            ?.joinToString(" ") { "%02d.%02d.%02d".format(java.util.Locale.US, it.block, it.major, it.minor) }

    /**
     * The line for the new answer [now] when the last one kept was [before] (hex of `0B02`): "dash firmware: X" the first time (nothing kept), "dash
     * firmware changed from X to Y" when the versions differ, nothing when they are the same or the new answer lists no version. A kept answer with
     * no readable version counts as nothing kept.
     */
    fun line(before: String?, now: String?): String? {
        val to = versions(now)
        val from = versions(before)
        return when {
            to == null -> null
            from == null -> "dash firmware: $to"
            from == to -> null
            else -> "dash firmware changed from $from to $to"
        }
    }
}
