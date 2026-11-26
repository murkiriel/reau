package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the phrase "REau is back" is said (26/11/2026, A8 of the Xiaomi and realme plan): after a resume that started and was not refused, through the same
 * RiderVoice every other notice uses, so the rider's switch for the voice rules it and a switch off says nothing. What is said and when it is repeated are
 * RiderVoiceTest's and RiderVoiceRulesTest's; the cue's rule is ProjectionResumeTest's; this is the order in the service, read from the source.
 */
class ResumeVoiceIsWiredTest {
    private val service = File("src/main/java/com/reau/app/service/ProjectionService.kt").readText()

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `the phrase is said at the end of the start, after the resume's line and after the refusal check, and only for a resume`() {
        val prepare = service.between("override fun prepare(): String? {", "worker.execute { housekeeping() }")
        val resumed = prepare.indexOf("logResume(ProjectionResume.resumedLine(")
        val refusal = prepare.indexOf("refusal()?.let { return it }")
        val said = prepare.indexOf("ProjectionResume.voiceCueFor(resumedAttempt)?.let { RiderVoice.speak(it) }")
        assertTrue(prepare, resumed in 0 until refusal && refusal in 0 until said)
        assertEquals("said once in the start", 1, Regex("""voiceCueFor""").findAll(service).count())
    }

    @Test
    fun `it goes through the voice of the service, which the rider's switch for spoken alerts rules, and writes its own line when it speaks`() {
        assertTrue("enabled = { SettingsStore.open(this).load().spokenAlerts }" in service)
        assertTrue("RiderVoice.install(voice)" in service)
        val voice = File("src/main/java/com/reau/app/service/RiderVoice.kt").readText()
        assertTrue("log(\"voice: \$cue said\")" in voice)
    }
}
