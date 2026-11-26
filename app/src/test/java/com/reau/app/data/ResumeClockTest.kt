package com.reau.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seconds from the system ending the process to the picture back on the dash, written when the dash confirms the first frame of a resumed projection (plan 35,
 * 25/11/2026): the 0.4.0 resumes by itself and the log had nothing to say what that gained.
 */
class ResumeClockTest {
    @Test
    fun `the line says the seconds since the exit and since the process started, with the exit's reason`() {
        val clock = ResumeClock()
        // The process had started 3.2 s before the resume began; the system ended the one before 4.7 s before.
        clock.arm(nowMs = 100_000, processAgeMs = 3_200, exitAgeMs = 4_700, exitName = "low memory")
        assertEquals(
            "resumed projection: the dash confirmed the first frame 16.7 s after the system ended the process (low memory) and 15.2 s after this process started",
            clock.confirmed(nowMs = 112_000),
        )
    }

    @Test
    fun `without the exit the line says so and gives the time since the process started`() {
        val clock = ResumeClock()
        clock.arm(nowMs = 100_000, processAgeMs = 1_000, exitAgeMs = null, exitName = null)
        assertEquals(
            "resumed projection: the dash confirmed the first frame 9.0 s after this process started (the time the system ended the one before is not known)",
            clock.confirmed(nowMs = 108_000),
        )
    }

    @Test
    fun `an exit with no reason name is still told, and an exit too old or in the future is not the one`() {
        val named = ResumeClock().apply { arm(0, 0, 1_000, null) }.confirmed(1_000)
        assertEquals("resumed projection: the dash confirmed the first frame 2.0 s after the system ended the process and 1.0 s after this process started", named)
        for (age in listOf(ResumeClock.MAX_EXIT_AGE_MS + 1, -1L)) {
            val line = ResumeClock().apply { arm(0, 0, age, "low memory") }.confirmed(1_000)
            assertTrue(line, line!!.endsWith("(the time the system ended the one before is not known)"))
        }
    }

    @Test
    fun `it is told once, and nothing is told without a resume`() {
        val clock = ResumeClock()
        assertNull(clock.confirmed(1_000))
        clock.arm(0, 0, null, null)
        assertTrue(clock.confirmed(1_000) != null)
        assertNull(clock.confirmed(2_000))
    }

    @Test
    fun `a projection of the rider's own, which disarms it, is not told as a resume`() {
        val clock = ResumeClock()
        clock.arm(0, 0, 1_000, "low memory")
        clock.disarm()
        assertNull(clock.confirmed(5_000))
    }

    @Test
    fun `a first frame after ten minutes is not the resume's`() {
        val clock = ResumeClock()
        clock.arm(0, 0, 1_000, "low memory")
        assertNull(clock.confirmed(ResumeClock.MAX_WAIT_MS + 1))
        clock.arm(0, 0, 1_000, "low memory")
        assertTrue(clock.confirmed(ResumeClock.MAX_WAIT_MS) != null)
    }

    private fun code(path: String) = File("src/main/java/com/reau/app/$path").readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("(?m)(^|\\s)//.*$"), "")

    @Test
    fun `the service arms it in the resume's own log lines and disarms it at the rider's Ligar and at forgetting`() {
        val service = code("service/ProjectionService.kt")
        assertTrue(service, "armResumeClock()" in service && "ResumeClock.shared.arm(" in service)
        assertTrue(service, "exitAgeMs = exit?.let { System.currentTimeMillis() - it.timestampMs }" in service)
        assertTrue(service, "processAgeMs = SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()" in service)
        assertEquals("the Ligar and forgetResume", 2, Regex("ResumeClock\\.shared\\.disarm\\(\\)").findAll(service).count())
        // Armed only inside the resumed branch of the session's own start, after the `resumed after` line.
        assertTrue(service, Regex("logResume\\(ProjectionResume\\.resumedLine\\([^\\n]*\\n\\s*armResumeClock\\(\\)").containsMatchIn(service))
    }

    @Test
    fun `the dash session tells it when the first frame is confirmed`() {
        val session = code("dash/DashSession.kt")
        assertTrue(session, "ResumeClock.shared.confirmed(now)?.let" in session)
    }
}
