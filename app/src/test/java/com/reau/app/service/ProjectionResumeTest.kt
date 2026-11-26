package com.reau.app.service

import android.app.Service
import android.content.pm.ServiceInfo
import com.reau.app.service.ProjectionResume.Mark
import com.reau.app.service.ProjectionResume.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule of the partial resume ([ProjectionResume]): a restart of the service with no intent, after the system ended the process, brings the rider's projection
 * back only with the rider's switch on, a mark that is there and recent, no mark of the rider having taken the app off the recent apps, and fewer than three resumes in
 * the last half hour. The rest (where the mark is written and erased, what the service answers) is [ProjectionResumeIsWiredTest]'s.
 */
class ProjectionResumeTest {
    private val now = 1_000_000_000_000L
    private val minute = 60_000L

    private fun mark(agoMs: Long = 10_000L, card: Boolean = false, resumed: List<Long> = emptyList(), removedAt: Long = 0L) =
        Mark(lastSeenMs = now - agoMs, card = card, resumedAtMs = resumed, removedAtMs = removedAt)

    private fun decide(mark: Mark?, enabled: Boolean = true) = ProjectionResume.decide(mark, enabled, now)

    private fun resumed(verdict: Verdict) = verdict as Verdict.Resume

    private fun dropped(verdict: Verdict) = (verdict as Verdict.Drop).why

    @Test
    fun `no mark means the rider's projection was not on, and nothing is resumed`() {
        assertTrue(dropped(decide(null)), dropped(decide(null)).startsWith("no mark"))
    }

    @Test
    fun `a recent mark resumes as the first attempt, in the mode that was on`() {
        val first = resumed(decide(mark(card = false)))
        assertEquals(1, first.attempt)
        assertFalse(first.card)
        assertEquals(listOf(now), first.resumedAtMs)
        assertTrue("the card mode comes back as the card mode", resumed(decide(mark(card = true))).card)
    }

    @Test
    fun `the rider's switch off drops it, mark or no mark`() {
        assertEquals("the rider's switch for resuming is off", dropped(decide(mark(), enabled = false)))
        assertEquals("the rider's switch for resuming is off", dropped(decide(null, enabled = false)))
    }

    @Test
    fun `the last sign of life may be 30 minutes old and not a millisecond older`() {
        assertEquals(1, resumed(decide(mark(agoMs = 30 * minute))).attempt)
        val over = dropped(decide(mark(agoMs = 30 * minute + 1)))
        assertTrue(over, over.startsWith("the last sign of life was 30 min ago, over 30"))
        assertTrue(dropped(decide(mark(agoMs = 3 * 60 * minute))).startsWith("the last sign of life was 180 min ago"))
    }

    @Test
    fun `a clock behind the mark is not trusted`() {
        assertEquals("the clock is behind the mark", dropped(decide(mark(agoMs = -1L))))
        assertEquals("the same instant is fine", 1, resumed(decide(mark(agoMs = 0L))).attempt)
    }

    @Test
    fun `the third resume of the window is the last, and each earlier one is counted`() {
        assertEquals(2, resumed(decide(mark(resumed = listOf(now - 5 * minute)))).attempt)
        val third = resumed(decide(mark(resumed = listOf(now - 20 * minute, now - 5 * minute))))
        assertEquals(3, third.attempt)
        assertEquals("the attempts kept are the window's and this one", listOf(now - 20 * minute, now - 5 * minute, now), third.resumedAtMs)
        val fourth = dropped(decide(mark(resumed = listOf(now - 25 * minute, now - 20 * minute, now - 5 * minute))))
        assertEquals("already 3 resumes in the last 30 min", fourth)
    }

    @Test
    fun `a resume of more than 30 minutes ago no longer counts, and is not kept`() {
        val verdict = resumed(decide(mark(resumed = listOf(now - 30 * minute - 1, now - 31 * minute, now - 90 * minute, now - 2 * minute))))
        assertEquals(2, verdict.attempt)
        assertEquals(listOf(now - 2 * minute, now), verdict.resumedAtMs)
        assertEquals("exactly 30 minutes ago is out of the window", 1, resumed(decide(mark(resumed = listOf(now - 30 * minute)))).attempt)
    }

    @Test
    fun `a resume that lies in the future of the clock does not count either`() {
        assertEquals(1, resumed(decide(mark(resumed = listOf(now + minute, now + 2 * minute, now + 3 * minute)))).attempt)
    }

    @Test
    fun `the rider taking the app off the recent apps drops it for the rest of that ride`() {
        assertEquals("the rider removed the app from the recent apps during this ride", dropped(decide(mark(removedAt = now - 5_000L))))
        assertEquals("no mark of it: it resumes", 1, resumed(decide(mark(removedAt = 0L))).attempt)
    }

    @Test
    fun `the switch comes first, then the mark, then the clock, then its age, then the recent apps, then the count`() {
        val everything = mark(agoMs = 3 * 60 * minute, removedAt = now, resumed = listOf(now - minute, now - 2 * minute, now - 3 * minute))
        assertEquals("the rider's switch for resuming is off", dropped(decide(everything, enabled = false)))
        assertTrue(dropped(decide(everything)).startsWith("the last sign of life"))
        val young = mark(removedAt = now, resumed = listOf(now - minute, now - 2 * minute, now - 3 * minute))
        assertTrue(dropped(decide(young)).startsWith("the rider removed"))
        assertTrue(dropped(decide(young.let { Mark(it.lastSeenMs, it.card, it.resumedAtMs) })).startsWith("already 3 resumes"))
    }

    @Test
    fun `the limits are the ones the setting's text promises, 3 resumes in 30 minutes`() {
        assertEquals(3, ProjectionResume.MAX_ATTEMPTS)
        assertEquals(30 * minute, ProjectionResume.WINDOW_MS)
        assertEquals(30 * minute, ProjectionResume.MAX_IDLE_MS)
        assertEquals(minute, ProjectionResume.BEAT_EVERY_MS)
    }

    // ── the types of the foreground a resume asks for ────────────────────────────────────────────────────

    private val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    @Test
    fun `a resume asks for the connected device alone, whatever Gemini and the location say, on every Android`() {
        for (sdk in listOf(29, 30, 33, 34, 36)) {
            for (microphone in listOf(false, true)) {
                for (location in listOf(false, true)) {
                    assertEquals("sdk $sdk mic $microphone loc $location", listOf(device), ProjectionResume.typesFor(true, microphone, location, sdk))
                }
            }
        }
    }

    @Test
    fun `the Ligar of the rider asks as it always did, the richest types first`() {
        assertEquals(listOf(device or mic or loc, device or mic, device or loc, device), ProjectionResume.typesFor(false, true, true, 36))
        assertEquals(listOf(device or loc, device), ProjectionResume.typesFor(false, false, true, 36))
        assertEquals(listOf(device), ProjectionResume.typesFor(false, false, false, 36))
    }

    @Test
    fun `no resume ladder holds a type that the foreground of a service started from the background may not take`() {
        val asked = ProjectionResume.typesFor(true, true, true, 36)
        assertTrue(asked.all { it and mic == 0 && it and loc == 0 })
    }

    @Test
    fun `the log says what the resume's foreground is without`() {
        val notes = ProjectionResume.foregroundNotes(device)
        assertEquals("projection: foreground types accepted: connectedDevice (0x${Integer.toHexString(device)})", notes[0])
        assertTrue(notes[1], "no microphone" in notes[1] && "no location" in notes[1])
    }

    // ── what the service answers, and the log's words ────────────────────────────────────────────────────

    @Test
    fun `the service is sticky only while the projection is the rider's and running`() {
        assertEquals(Service.START_STICKY, ProjectionResume.returnFor(true))
        assertEquals(Service.START_NOT_STICKY, ProjectionResume.returnFor(false))
    }

    @Test
    fun `the log's lines say the attempt and the reason`() {
        assertEquals("resumed after the system ended the process (attempt 2 of 3)", ProjectionResume.resumedLine(2))
        assertEquals("not resumed after the system ended the process: why", ProjectionResume.notResumedLine("why"))
    }
}
