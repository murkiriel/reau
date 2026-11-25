package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After a USB end of the Android Auto session the music that played stays paused: PLAY is pressed once when the new session is up, only if the music
 * was playing, no call is on, and the end was the USB's. The clock is a counter.
 */
class MusicResumeTest {
    private var now = 50_000L
    private val resume = MusicResume({ now })

    @Test
    fun `resumes after a USB end with the music playing`() {
        resume.sessionEnded(byUsb = true, wasPlaying = true)
        now += 6_000
        assertEquals(MusicResume.Verdict.RESUME, resume.decide(inCall = false, playingNow = false))
    }

    @Test
    fun `does not resume when the music was not playing`() {
        resume.sessionEnded(byUsb = true, wasPlaying = false)
        now += 6_000
        assertEquals(MusicResume.Verdict.WAS_STOPPED, resume.decide(inCall = false, playingNow = false))
    }

    @Test
    fun `does not resume in a call`() {
        resume.sessionEnded(byUsb = true, wasPlaying = true)
        now += 6_000
        assertEquals(MusicResume.Verdict.IN_A_CALL, resume.decide(inCall = true, playingNow = false))
    }

    @Test
    fun `does not resume when the session ended for another reason`() {
        resume.sessionEnded(byUsb = false, wasPlaying = true)
        assertFalse(resume.waiting())
        assertNull(resume.decide(inCall = false, playingNow = false))
    }

    @Test
    fun `an end for another reason after a USB one takes the wait back`() {
        resume.sessionEnded(byUsb = true, wasPlaying = true)
        resume.sessionEnded(byUsb = false, wasPlaying = true)
        assertNull(resume.decide(inCall = false, playingNow = false))
    }

    @Test
    fun `resumes once only`() {
        resume.sessionEnded(byUsb = true, wasPlaying = true)
        assertTrue(resume.waiting())
        assertEquals(MusicResume.Verdict.RESUME, resume.decide(inCall = false, playingNow = false))
        assertFalse(resume.waiting())
        assertNull(resume.decide(inCall = false, playingNow = false))
    }

    @Test
    fun `a call that stops the resume uses up the wait`() {
        resume.sessionEnded(byUsb = true, wasPlaying = true)
        assertEquals(MusicResume.Verdict.IN_A_CALL, resume.decide(inCall = true, playingNow = false))
        assertNull("the call ending later does not bring the music back", resume.decide(inCall = false, playingNow = false))
    }

    @Test
    fun `does not press play on a music that plays again by itself`() {
        resume.sessionEnded(byUsb = true, wasPlaying = true)
        assertEquals(MusicResume.Verdict.PLAYING_AGAIN, resume.decide(inCall = false, playingNow = true))
    }

    @Test
    fun `a session that comes too late is not the same fall, edge included`() {
        resume.sessionEnded(byUsb = true, wasPlaying = true)
        now += MusicResume.VALID_MS
        assertEquals(MusicResume.Verdict.RESUME, resume.decide(inCall = false, playingNow = false))
        resume.sessionEnded(byUsb = true, wasPlaying = true)
        now += MusicResume.VALID_MS + 1
        assertEquals(MusicResume.Verdict.TOO_LATE, resume.decide(inCall = false, playingNow = false))
    }

    @Test
    fun `nothing waiting says nothing`() {
        assertNull(resume.decide(inCall = false, playingNow = false))
    }

    @Test
    fun `the lines say what was done and why not`() {
        assertEquals("music resumed after the USB ended the Android Auto session", MusicResume.Verdict.RESUME.line)
        assertTrue(MusicResume.Verdict.WAS_STOPPED.line.endsWith("it was not playing"))
        assertTrue(MusicResume.Verdict.IN_A_CALL.line.endsWith("a call is on"))
    }
}
