package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the fixes of the review of the plan 35 (26/11/2026) are wired in the service: the order the service's stop reads its two ids in, and that the low-battery notice
 * is told once per trip (kept with the resume's mark) and goes when the projection ends. The rules themselves are held by ProjectionResumeBookTest and BatteryLowWatchTest;
 * these need a Context or the service's threads, so they read the source as the other "...IsWired" tests do.
 */
class Revisao35IsWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `the release reads the latest command before the last start, and ends the service with that reading`() {
        val release = source("service/ProjectionService.kt").between("override fun release() {", "override fun startAndroidAuto()")
        val latest = release.indexOf("val latest = latestCommand")
        val stop = release.indexOf("stopSelf(ForegroundHandover.stopId(command, lastStart, latest))")
        assertTrue(release, latest >= 0 && stop > latest)
        assertEquals("the stop's id has no second read of the latest command", 0, Regex("""stopId\([^)]*latestCommand""").findAll(release).count())
    }

    @Test
    fun `the watch is made with what the ride's mark says, the notice is recorded when given, and the notice goes with the projection`() {
        val service = source("service/ProjectionService.kt")
        val look = service.between("private fun lookAtTheDevice()", "private fun stopLookingAtTheDevice")
        assertTrue(look, "val told = runCatching { resumeBook.batteryTold() }.getOrDefault(false)" in look)
        assertTrue(look, "val low = BatteryLowWatch(alreadyTold = told)" in look)
        val warn = service.between("private fun warnIfBatteryLow", "/**")
        val shown = warn.indexOf("ProjectionNotification.showBatteryLow(this, pct)")
        val recorded = warn.indexOf("runCatching { resumeBook.recordBatteryTold() }")
        assertTrue(warn, shown >= 0 && recorded > shown)
        val release = service.between("override fun release() {", "override fun startAndroidAuto()")
        assertTrue(release, "ProjectionNotification.clearBatteryLow(this@ProjectionService)" in release)
        val notification = source("service/ProjectionNotification.kt").between("fun clearBatteryLow(context: Context) {", "}")
        assertTrue(notification, "cancel(ID_BATTERY_LOW)" in notification)
    }

    @Test
    fun `the book forgets the notice at Ligar and keeps it at a resume`() {
        val book = source("service/ProjectionResumeBook.kt")
        val begin = book.between("fun begin(nowMs: Long, card: Boolean) {", "lastBeatMs = nowMs")
        assertTrue(begin, "remove(BATTERY_TOLD)" in begin)
        assertTrue("a resume does not touch it", "BATTERY_TOLD" !in book.between("fun recordResume(", "lastDashUpMs = nowMs"))
        assertTrue("the removal from the recent apps is a commit", "prefs.edit(commit = true) { putLong(REMOVED, nowMs) }" in book)
    }
}
