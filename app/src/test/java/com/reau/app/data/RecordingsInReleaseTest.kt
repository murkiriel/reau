package com.reau.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decided on 28/10/2026: the two recordings exist in a release too (off by default, the rider's to switch on). Nothing in their path may
 * ask for a debug build, or a release would switch them on and record nothing, or record and not share: the files that write, trim, share
 * and delete them name no build type but the two ceilings ([RecordingsSpace], [DiagSpace]), which are the same in size for a release.
 */
class RecordingsInReleaseTest {
    private val main = File("src/main/java/com/reau/app")

    @Test
    fun `the files of the recording path do not ask for a debug build`() {
        val path = listOf(
            "aa/SessionRecorder.kt", "aa/AaReceiver.kt", "stream/H264Recorder.kt", "stream/VideoLoop.kt", "stream/RtpOutput.kt",
            "service/LiveSides.kt", "service/ProjectionService.kt", "util/DiagnosticsExport.kt", "data/LogsCleanup.kt", "data/LogAge.kt",
            "data/AppFolders.kt", "data/RideDiagnostics.kt",
        )
        for (name in path) {
            val file = File(main, name)
            assertTrue("$name is gone", file.isFile)
            val gated = file.readLines().filter { "BuildConfig.DEBUG" in it || "debugBuild" in it }
            assertEquals("$name gates something by the build type: $gated", emptyList<String>(), gated)
        }
    }

    @Test
    fun `a release keeps and cleans the recordings by the same rules as a debug build, with the 200 MB ceiling`() {
        assertEquals(200L * 1024 * 1024, RecordingsSpace.capBytes(debugBuild = false))
        assertEquals(200L * 1024 * 1024, DiagSpace.capBytes(debugBuild = false))
        assertEquals(30, com.reau.app.settings.REauSettings.defaultLogKeepDays(debugBuild = false))
        assertEquals("a release's settings keep the days the rider chose", 14, com.reau.app.settings.REauSettings(logKeepDays = 14).sanitized(debugBuild = false).logKeepDays)
    }
}
