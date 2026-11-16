package com.reau.app.data

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Test helper: reads back what the app wrote to its own ride log since the test began, for the lines that only a real codec
 * produces (`encoder accepted:`, `codec metrics (encoder …)`). [begin] writes a marker line; [text] is everything the newest log
 * file holds after it, once the background writer has caught up.
 *
 * Lines are written as `<time>  <offset>  [tag] message`, so a test looks for `"[stream] encoder accepted: "` and the like.
 */
class RideLogProbe(private val context: Context) {
    private val marker = "ride-log-probe-${System.nanoTime()}"

    /** Opens the log if the process has not (the app does it at start), then writes the marker. Skips the test where the ride log is switched off. */
    fun begin() {
        RideDiagnostics.init(context)
        RideDiagnostics.startProcess("test")
        assumeTrue("the ride log is switched off on this device", RideDiagnostics.enabled && RideDiagnostics.logDir() != null)
        RideDiagnostics.logAndWait("test", marker)
    }

    /** Everything in the newest log file after the marker; empty when the marker is not there (the file rolled over, or [begin] was not called). */
    fun text(): String {
        RideDiagnostics.logAndWait("test", "end of $marker")
        val newest = RideDiagnostics.logDir()
            ?.listFiles { f -> f.name.startsWith("ride-") }
            ?.maxByOrNull { it.lastModified() }
            ?: return ""
        return newest.readText().substringAfter(marker, "")
    }

    /** Waits until [condition] holds for the text since the marker; fails with [what] and that text at the deadline, which only says "it never came". */
    fun await(what: String, timeoutMs: Long = 4_000, condition: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var now = text()
        while (!condition(now) && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            now = text()
        }
        assertTrue("$what; the ride log since the test began: $now", condition(now))
        return now
    }

    companion object {
        /** The lines of [text] that carry [needle]. */
        fun lines(text: String, needle: String): List<String> = text.lines().filter { needle in it }
    }
}
