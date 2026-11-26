package com.reau.app.data

/**
 * The maker's system version for the ride log's header line (26/11/2026). "Android 16 (API 36)" says nothing of the maker's layer on top (HyperOS, realme UI),
 * and the menu paths and the background rules change with its version: the runs of 25/11/2026 on a Xiaomi and a realme could not be told apart by the release
 * alone. Both fields are public API (`Build.DISPLAY`, `Build.VERSION.INCREMENTAL`): a build label shared by every unit of that firmware, never a serial number.
 */
object SystemBuild {
    private const val MAX_LENGTH = 40

    /** A label an engineering build stamps with the name of whoever built it (`eng.<user>.<date>`): never written out. */
    private const val ENGINEERING_PREFIX = "eng."
    private const val ENGINEERING_LABEL = "eng-build"
    private const val UNKNOWN = "?"

    /** The header's tail, `display=<label> incremental=<label>` (a label that could not be read is `?`). */
    fun line(display: String?, incremental: String?): String = "display=${label(display)} incremental=${label(incremental)}"

    /**
     * One label on one line: printable ASCII only (the rest, and every space, becomes `_`), at most [MAX_LENGTH] characters, `?` when empty or null, and
     * [ENGINEERING_LABEL] for a label that starts as an engineering build's does.
     */
    internal fun label(raw: String?): String {
        val text = raw?.trim().orEmpty()
        return when {
            text.isEmpty() -> UNKNOWN
            text.startsWith(ENGINEERING_PREFIX, ignoreCase = true) -> ENGINEERING_LABEL
            else -> text.map { if (it in '!'..'~') it else '_' }.joinToString("").take(MAX_LENGTH)
        }
    }
}
