package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the process-state summary is given to the system and read back (26/11/2026, idea S3): the calls need Android (`ActivityManager.setProcessStateSummary`,
 * `ApplicationExitInfo.getProcessStateSummary`, API 30), so the source is read, as the other "...IsWired" tests do. The format and the keeper are in ProcessStateSummaryTest.
 */
class ProcessStateSummaryIsWiredTest {
    private val main = File("src/main/java/com/reau/app")

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `the reader gives the bytes to the system only from Android 11, inside a runCatching`() {
        val keep = File(main, "service/DeviceStateReader.kt").readText().between("fun keepSummary(bytes: ByteArray) {", "private fun readNow()")
        assertTrue(keep, "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { activity.setProcessStateSummary(bytes) }" in keep)
    }

    @Test
    fun `each projection makes its keeper, feeds it at every look after the device is read, and ends it with the projection`() {
        val service = File(main, "service/ProjectionService.kt").readText()
        val look = service.between("private fun lookAtTheDevice() {", "private fun warnIfHot")
        assertEquals(1, Regex("val summary = com\\.reau\\.app\\.data\\.ProcessStateSummaryKeeper \\{ reader\\.keepSummary\\(it\\) \\}").findAll(look).count())
        assertTrue("the service keeps it for the end", "stateSummary = summary" in look)
        assertTrue("fed after the state is read", look.indexOf("val state = reader.read()") < look.indexOf("summary.offer("))
        assertTrue("inside the look's runCatching", look.indexOf("runCatching {") < look.indexOf("summary.offer("))
        assertTrue("with the state of the look, not a copy that could be stale", "state.screenOn, resumedAttempt, state.batteryPct, state.charging" in look)
        assertTrue("the dash is the link's, as the resume mark takes it", "ProjectionResume.dashUp(controller.state.dash), state.screenOn" in look)
        val stop = service.between("private fun stopLookingAtTheDevice() {", "/** The rider's notice that the phone is hot")
        assertTrue(stop, "stateSummary?.ended()" in stop)
    }

    @Test
    fun `the exit history reads the summary of a past exit, within the 128 bytes, as ASCII`() {
        val history = File(main, "data/ExitHistory.kt").readText().between("fun pastExit(info: ApplicationExitInfo) = PastExit(", "@RequiresApi(Build.VERSION_CODES.R)\n    private fun keepTrace")
        assertTrue(history, "runCatching { info.processStateSummary }.getOrNull()\n            ?.takeIf { it.size <= ProcessStateSummary.MAX_BYTES }\n            ?.let { String(it, Charsets.US_ASCII) }" in history)
    }

    @Test
    fun `the line of a death carries the decoded summary, before the description the reader takes as the last quoted text`() {
        val describe = File(main, "data/ExitReasons.kt").readText().between("fun describe(exit: PastExit", "/** The file-name start of the trace")
        val state = describe.indexOf("ProcessStateSummary.describe(exit.stateSummary, exit.timestampMs)")
        assertTrue(describe, state > 0)
        assertTrue("before the description", state < describe.indexOf("exit.description?.takeIf"))
    }
}
