package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A4 of the plan for the Xiaomi and the realme (26/11/2026): the ride log says more of the phone's state. The readers live in classes that need Android, so the
 * source is read, as the other "...IsWired" tests do. Held: the standby bucket and the background restriction are read with no throw and written once per
 * projection (and on a change); the maker's system version is in the process-start line; and the Xiaomi's autostart switch is NOT read (that goes through an
 * interface outside the SDK, which can break with any update and must never decide anything in the app).
 */
class AppStandbyIsWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private val reader = source("service/DeviceStateReader.kt")
    private val service = source("service/ProjectionService.kt")

    @Test
    fun `the bucket and the restriction are each read inside a runCatching, and unknown when it fails`() {
        val read = reader.substringAfter("fun appStandby(): AppStandby.Reading").substringBefore("private fun readNow()")
        assertTrue(read, "bucket = runCatching { usageStats.appStandbyBucket }.getOrNull()," in read)
        assertTrue(read, "backgroundRestricted = runCatching { activity.isBackgroundRestricted }.getOrNull()," in read)
    }

    @Test
    fun `each projection makes its own log of the standby line and offers it at every look, after the device state is read`() {
        val look = service.substringAfter("private fun lookAtTheDevice() {").substringBefore("private fun warnIfHot")
        assertEquals(1, Regex("val standby = AppStandbyLog \\{ RideDiagnostics\\.log\\(\"device\", it\\) \\}").findAll(look).count())
        assertEquals(1, Regex("standby\\.offer\\(reader\\.appStandby\\(\\)\\)").findAll(look).count())
        assertTrue("inside the look's runCatching", look.indexOf("runCatching {") < look.indexOf("standby.offer("))
        assertTrue("after the state is read", look.indexOf("val state = reader.read()") < look.indexOf("standby.offer("))
        assertTrue("before the schedule starts the looks", look.indexOf("val standby = AppStandbyLog") < look.indexOf("deviceLook = worker.scheduleWithFixedDelay"))
    }

    @Test
    fun `the process-start line carries the display and the incremental of the system build`() {
        val start = source("data/RideDiagnostics.kt").substringAfter("fun startProcess(reason: String) {").substringBefore("fun stopProcess")
        assertTrue(start, "SystemBuild.line(Build.DISPLAY, Build.VERSION.INCREMENTAL)" in start)
        assertTrue("the header is written with it", start.indexOf("SystemBuild.line(") < start.indexOf("raw(\"==== process start: \$reason — \$app, \$device ====\")"))
    }

    @Test
    fun `no code reads the Xiaomi's autostart switch by reflection`() {
        val readers = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { "MiuiSettings" in it || "miui.autostart" in it || "AutoStartManager" in it || "getAutoStartState" in it } }
            .map { it.name }.toList()
        assertEquals("who reads the Xiaomi's autostart: $readers", emptyList<String>(), readers)
        assertFalse("the reader uses no reflection", "Class.forName" in reader || "getDeclaredMethod" in reader)
    }
}
