package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order of the "Depuração" group, the owner's (28/10/2026), the same in both editions: the ride log, the detailed log right under it,
 * then the two recordings (in a release too, off by default), then the buttons to see, share and delete the logs and the days they are kept.
 * Read from the source (the rows are lambdas, with nothing to list): the tags in the order the group builds them.
 */
class DebugRowsOrderTest {
    private val source = File("src/main/java/com/reau/app/ui/SettingsDebug.kt").readText()

    private fun body(startMarker: String, endMarker: String): String {
        val from = source.indexOf(startMarker)
        assertTrue("$startMarker is gone", from >= 0)
        return source.substring(from, source.indexOf(endMarker, from))
    }

    /** The tags of the rows of the group, in the order they are built: the first list, the debug build's rows in its place, the last list. */
    private fun order(): List<String> {
        val build = body("private fun recordingRows", "\n}\n")
        val group = body("    return listOf<@Composable () -> Unit>(\n        { RideLog", "        { LogKeepDays(s, change) },")
        val out = mutableListOf<String>()
        for (line in group.lines()) {
            when {
                "RideLog(s, change)" in line -> out += "ride_log"
                "\"detailed_log\"" in line -> out += "detailed_log"
                "recordingRows(" in line -> out += Regex("""\"((?:record_video)|(?:record_sessions))\"""").findAll(build).map { it.groupValues[1] }.toList()
                "\"view_logs\"" in line -> out += "view_logs"
            }
        }
        return out
    }

    @Test
    fun `the detailed log is right under the ride log, then the recordings, then the logs' buttons`() {
        assertEquals(listOf("ride_log", "detailed_log", "record_video", "record_sessions", "view_logs"), order())
    }

    /** 28/10/2026: the recordings exist in a release (off by default); no build type decides whether their rows are shown. */
    @Test
    fun `the recordings' rows are shown in a release as in a debug build`() {
        val rows = body("private fun recordingRows", "\n}\n")
        assertTrue("the rows are gated by the build type again", "BuildConfig" !in rows && "DEBUG" !in rows)
        val group = body("    return listOf<@Composable () -> Unit>(\n        { RideLog", "        { LogKeepDays(s, change) },")
        assertTrue("the group does not build them conditionally", "recordingRows(s, change)" in group && "if (" !in group)
    }

    @Test
    fun `the debug options that went have no row any more`() {
        for (tag in listOf("faster_menu", "aa_single_version_request", "diag_link_only", "diag_no_dscp")) assertTrue("$tag is back", "\"$tag\"" !in source)
    }
}
