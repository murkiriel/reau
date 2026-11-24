package com.reau.app.dash

import com.reau.app.phone.VolumeChange
import com.reau.app.phone.VolumeStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The volume card's decisions ([VolumeCards]): which broadcasts of the phone's volume are a step for the card, what the card says, the 2 s it lasts
 * after the last step ([TimedShow], as the legend's), and the title's echo that the card replaces. The Android side ([com.reau.app.service.VolumeWatch])
 * is read from the source in VolumeCardIsWiredTest.
 */
class VolumeCardsTest {
    @Test
    fun `the music stream is a music card with its level out of its most`() {
        val step = VolumeCards.decide(VolumeCards.STREAM_MUSIC, value = 7, previous = 6, max = 15)!!
        assertEquals(VolumeCard(VolumeKind.MUSIC, 7, 15), step.card)
        assertEquals(6, step.from)
        assertFalse(step.headset)
    }

    @Test
    fun `the call stream is a call card, and so is the Bluetooth headset's call (SCO)`() {
        val call = VolumeCards.decide(VolumeCards.STREAM_VOICE_CALL, 4, 5, 7)!!
        assertEquals(VolumeCard(VolumeKind.CALL, 4, 7), call.card)
        assertFalse(call.headset)
        val sco = VolumeCards.decide(VolumeCards.STREAM_BLUETOOTH_SCO, 4, 3, 15)!!
        assertEquals(VolumeCard(VolumeKind.CALL, 4, 15), sco.card)
        assertTrue(sco.headset)
    }

    @Test
    fun `the other streams are no card (ring 2, alarm 4, notification 5, system 1, the rest)`() {
        for (stream in listOf(1, 2, 4, 5, 7, 8, 9, 10, 11, -1, 99)) {
            assertNull("stream $stream", VolumeCards.decide(stream, 3, 2, 15))
        }
    }

    @Test
    fun `a level that did not move is ignored, a missing level or a stream with no steps too`() {
        assertNull(VolumeCards.decide(VolumeCards.STREAM_MUSIC, 7, 7, 15))
        assertNull("no level in the broadcast", VolumeCards.decide(VolumeCards.STREAM_MUSIC, -1, 3, 15))
        assertNull("no steps", VolumeCards.decide(VolumeCards.STREAM_MUSIC, 3, 2, 0))
        assertNotNull("no previous level still shows", VolumeCards.decide(VolumeCards.STREAM_MUSIC, 3, -1, 15))
    }

    @Test
    fun `a level above the most is held at the most, and level 0 is muted whichever the stream`() {
        assertEquals(15, VolumeCards.decide(VolumeCards.STREAM_MUSIC, 40, 3, 15)!!.card.level)
        for (stream in listOf(VolumeCards.STREAM_MUSIC, VolumeCards.STREAM_VOICE_CALL, VolumeCards.STREAM_BLUETOOTH_SCO)) {
            val card = VolumeCards.decide(stream, 0, 1, 7)!!.card
            assertTrue("stream $stream", card.muted)
            assertEquals(0f, card.fraction, 0f)
        }
        assertFalse(VolumeCard(VolumeKind.MUSIC, 1, 15).muted)
        assertEquals(0.5f, VolumeCard(VolumeKind.CALL, 5, 10).fraction, 0f)
        assertEquals(1f, VolumeCard(VolumeKind.CALL, 7, 7).fraction, 0f)
    }

    @Test
    fun `the log line says the kind, the level and the one before, and nothing of what plays or of who calls`() {
        assertEquals("volume card: music 7/15 (was 6)", VolumeCards.decide(3, 7, 6, 15)!!.logLine())
        assertEquals("volume card: call 4/7 (was 5)", VolumeCards.decide(0, 4, 5, 7)!!.logLine())
        assertEquals("volume card: call 4/7 (was 5, headset)", VolumeCards.decide(6, 4, 5, 7)!!.logLine())
    }

    @Test
    fun `a call volume step that moved nothing is the card again, at the most and at the least`() {
        val top = VolumeCards.atTheEnd(VolumeChange(VolumeStream.CALL, 8, 8, 8))!!
        assertEquals(VolumeCard(VolumeKind.CALL, 8, 8), top.card)
        assertEquals("volume card: call 8/8 (was 8)", top.logLine())
        assertEquals("at the least the card says that level, not muted", VolumeCard(VolumeKind.CALL, 1, 8), VolumeCards.atTheEnd(VolumeChange(VolumeStream.CALL, 1, 1, 8))!!.card)
        assertTrue("a call that the phone lets reach 0 says muted", VolumeCards.atTheEnd(VolumeChange(VolumeStream.CALL, 0, 0, 8))!!.card.muted)
    }

    @Test
    fun `a step that moved is the broadcast's, and the music's end is no card again`() {
        assertNull(VolumeCards.atTheEnd(VolumeChange(VolumeStream.CALL, 7, 8, 8)))
        assertNull(VolumeCards.atTheEnd(VolumeChange(VolumeStream.CALL, 2, 1, 8)))
        assertNull("only the call's: the music and the rest stay as they are", VolumeCards.atTheEnd(VolumeChange(VolumeStream.MUSIC, 15, 15, 15)))
    }

    @Test
    fun `the broadcast and its extras are the system's own names`() {
        assertEquals("android.media.VOLUME_CHANGED_ACTION", VolumeCards.ACTION)
        assertEquals("android.media.EXTRA_VOLUME_STREAM_TYPE", VolumeCards.EXTRA_STREAM_TYPE)
        assertEquals("android.media.EXTRA_VOLUME_STREAM_VALUE", VolumeCards.EXTRA_STREAM_VALUE)
        assertEquals("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", VolumeCards.EXTRA_PREVIOUS_VALUE)
        assertEquals(listOf(0, 3, 6), listOf(VolumeCards.STREAM_VOICE_CALL, VolumeCards.STREAM_MUSIC, VolumeCards.STREAM_BLUETOOTH_SCO))
    }

    @Test
    fun `with the card on the title has no volume echo, with it off the echo is the same as before`() {
        assertNull(VolumeCards.titleEcho(cardOn = true) { "Volume 12/30" })
        assertEquals("Volume 12/30", VolumeCards.titleEcho(cardOn = false) { "Volume 12/30" })
        assertEquals("Chamada 5/7", VolumeCards.titleEcho(cardOn = false) { "Chamada 5/7" })
    }

    // The card's time is a TimedShow of 2 s, as the legend's: it goes 2 s after the last step, and each step starts the time again.
    private var now = 10_000L
    private var told = 0

    private class Wait(val atMs: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private val waits = mutableListOf<Wait>()
    private val show = TimedShow<VolumeNotice>(
        VolumeCards.SHOW_MS, nowMs = { now },
        schedule = { delayMs, action ->
            val wait = Wait(now + delayMs, action)
            waits += wait
            val cancel: () -> Unit = { wait.cancelled = true }
            cancel
        },
        changed = { told++ },
    )

    private fun advance(to: Long) {
        now = to
        for (wait in waits.filter { !it.cancelled && it.atMs <= to }) {
            wait.cancelled = true
            wait.action()
        }
    }

    private fun notice(level: Int) = VolumeNotice(VolumeCard(VolumeKind.MUSIC, level, 15), "Music $level/15")

    @Test
    fun `the card comes at once and goes 2 s after the last step, and every step starts the 2 s again`() {
        assertEquals(2_000L, VolumeCards.SHOW_MS)
        show.show(notice(7))
        assertEquals(1, told)
        assertEquals(notice(7), show.current())
        advance(11_500)
        show.show(notice(8))
        assertEquals("a step updates the card", notice(8), show.current())
        advance(12_000)
        assertEquals("the first step's end does not take the card away", notice(8), show.current())
        advance(13_499)
        assertEquals(notice(8), show.current())
        advance(13_500)
        assertNull(show.current())
        assertEquals("shown twice, gone once", 3, told)
    }

    @Test
    fun `switched off nothing is kept, and switching off takes the card away at once`() {
        show.enabled = false
        assertFalse(show.show(notice(7)))
        assertNull(show.current())
        assertEquals(0, told)
        show.enabled = true
        show.show(notice(7))
        show.enabled = false
        assertNull(show.current())
        assertEquals(2, told)
    }

    @Test
    fun `the joystick's own step shows the level after it, music or call, held at the most`() {
        assertEquals(
            VolumeCards.Step(VolumeCard(VolumeKind.MUSIC, 7, 15), from = 6, headset = false),
            VolumeCards.stepOf(com.reau.app.phone.VolumeChange(com.reau.app.phone.VolumeStream.MUSIC, 6, 7, 15)),
        )
        val call = VolumeCards.stepOf(com.reau.app.phone.VolumeChange(com.reau.app.phone.VolumeStream.CALL, 5, 4, 7))
        assertEquals(VolumeCard(VolumeKind.CALL, 4, 7), call.card)
        assertEquals("at the end of the range nothing moved and the card still says the level", "volume card: music 15/15 (was 15)",
            VolumeCards.stepOf(com.reau.app.phone.VolumeChange(com.reau.app.phone.VolumeStream.MUSIC, 15, 15, 15)).logLine())
    }
}
