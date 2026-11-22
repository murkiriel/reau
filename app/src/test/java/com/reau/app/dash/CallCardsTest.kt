package com.reau.app.dash

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call card's decisions ([CallCards]): the time as it is said, the small line of each state in the three languages (the words are the ones of the resource files),
 * what the card follows of the call (ringing, in call with the time running, over for 2 s, or gone), and the ride log's lines, which never carry a name or a number.
 * The drawing ([com.reau.app.video.CallCardInk]) is a bitmap that the unit tests cannot make; the layout is in CallCardLayoutTest.
 */
class CallCardsTest {
    private fun string(folder: String, name: String) =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]

    /** The words of [folder]'s language, as the resources give them (%1$s is the time). */
    private fun words(folder: String) = CallWords(
        answer = string(folder, "call_card_answer"),
        decline = string(folder, "call_card_decline"),
        onCall = { string(folder, "call_card_on_call").replace("%1\$s", it) },
        end = string(folder, "call_card_end"),
        calling = string(folder, "call_card_calling"),
        ended = { string(folder, "call_card_ended").replace("%1\$s", it) },
    )

    private fun line(folder: String, card: CallCard) = CallCards.plain(CallCards.spans(card, words(folder)))

    private val ringing = CallCard(CallPhase.RINGING, "Ana")
    private val dialing = CallCard(CallPhase.DIALING, "Ana")
    private val onCall = CallCard(CallPhase.IN_CALL, "Ana", 102)
    private val ended = CallCard(CallPhase.ENDED, "Ana", 222)

    @Test
    fun `the time is minutes and seconds, and hours too past the hour`() {
        assertEquals("00:00", CallCards.clock(0))
        assertEquals("00:59", CallCards.clock(59))
        assertEquals("01:00", CallCards.clock(60))
        assertEquals("01:42", CallCards.clock(102))
        assertEquals("03:42", CallCards.clock(222))
        assertEquals("59:59", CallCards.clock(3_599))
        assertEquals("1:00:00", CallCards.clock(3_600))
        assertEquals("1:02:03", CallCards.clock(3_723))
        assertEquals("10:00:00", CallCards.clock(36_000))
        assertEquals("a negative time is none", "00:00", CallCards.clock(-5))
    }

    @Test
    fun `in Portuguese the small line says the hints, the time, the calling and the end`() {
        assertEquals("◀ Atender · Recusar ▶", line("values-pt", ringing))
        assertEquals("Em chamada 01:42 · ● Encerrar", line("values-pt", onCall))
        assertEquals("Chamando…", line("values-pt", dialing))
        assertEquals("Encerrada · 03:42", line("values-pt", ended))
    }

    @Test
    fun `in English`() {
        assertEquals("◀ Answer · Decline ▶", line("values", ringing))
        assertEquals("On call 01:42 · ● End", line("values", onCall))
        assertEquals("Calling…", line("values", dialing))
        assertEquals("Ended · 03:42", line("values", ended))
    }

    @Test
    fun `in Spanish`() {
        assertEquals("◀ Atender · Rechazar ▶", line("values-es", ringing))
        assertEquals("En llamada 01:42 · ● Colgar", line("values-es", onCall))
        assertEquals("Llamando…", line("values-es", dialing))
        assertEquals("Finalizada · 03:42", line("values-es", ended))
    }

    @Test
    fun `each piece has its colour role, and the time runs inside the line`() {
        val w = words("values-pt")
        assertEquals(
            listOf(CallSpan("Atender", CallRole.ANSWER), CallSpan(" · ", CallRole.HINT), CallSpan("Recusar", CallRole.DECLINE)),
            CallCards.spans(ringing, w),
        )
        assertEquals(
            listOf(CallSpan("Em chamada 01:42 · ", CallRole.HINT), CallSpan("Encerrar", CallRole.END)),
            CallCards.spans(onCall, w),
        )
        assertEquals(listOf(CallSpan("Em chamada 1:02:03 · ", CallRole.HINT), CallSpan("Encerrar", CallRole.END)), CallCards.spans(onCall.copy(seconds = 3_723), w))
        assertEquals(listOf(CallSpan("Chamando…", CallRole.HINT)), CallCards.spans(dialing, w))
        assertEquals(listOf(CallSpan("Encerrada · 03:42", CallRole.HINT)), CallCards.spans(ended, w))
    }

    @Test
    fun `the notice is the card with its line, and the same one again is equal (it is not drawn again)`() {
        val w = words("values")
        assertEquals(CallCards.notice(onCall, w), CallCards.notice(onCall.copy(), w))
        assertFalse(CallCards.notice(onCall, w) == CallCards.notice(onCall.copy(seconds = 103), w))
        assertTrue(CallCards.isOver(ended))
        assertFalse(CallCards.isOver(onCall))
    }

    @Test
    fun `the ride log has the phase and the time, never a name or a number`() {
        val named = CallCard(CallPhase.IN_CALL, "Ana Souza +55 11 91234-5678", 5)
        assertEquals("call card: ringing", CallCards.logLine(named.copy(phase = CallPhase.RINGING)))
        assertEquals("call card: calling", CallCards.logLine(named.copy(phase = CallPhase.DIALING)))
        assertEquals("call card: in call", CallCards.logLine(named))
        assertEquals("call card: ended after 03:42", CallCards.logLine(ended))
        for (phase in CallPhase.entries) {
            val text = CallCards.logLine(named.copy(phase = phase))
            assertFalse(text, "Ana" in text || "55" in text.replace("call card: ", ""))
        }
    }

    // The call as the card follows it, with the clock in the test's hand.
    private var now = 10_000L
    private val tracker = CallCards.Tracker { now }

    @Test
    fun `with no call there is no card`() {
        assertNull(tracker.current())
        assertNull(tracker.nextTickMs())
        assertFalse("no call and none before: nothing changed", tracker.onNoCall())
    }

    @Test
    fun `a ringing call is a card with no time and no tick until it is answered`() {
        assertTrue(tracker.onCall("Ana", inCall = false))
        assertEquals(CallCard(CallPhase.RINGING, "Ana"), tracker.current())
        assertNull(tracker.nextTickMs())
        now += 30_000
        assertEquals(CallCard(CallPhase.RINGING, "Ana"), tracker.current())
        assertFalse("the same again", tracker.onCall("Ana", inCall = false))
    }

    @Test
    fun `a call dialled by the rider is its own phase`() {
        assertTrue(tracker.onCall("Ana", inCall = false, dialing = true))
        assertEquals(CallCard(CallPhase.DIALING, "Ana"), tracker.current())
        assertNull(tracker.nextTickMs())
        assertTrue(tracker.onCall("Ana", inCall = true))
        assertEquals(CallPhase.IN_CALL, tracker.current()!!.phase)
    }

    @Test
    fun `answered, the time counts from then and the card changes at each whole second`() {
        tracker.onCall("Ana", inCall = false)
        now += 5_000
        assertTrue(tracker.onCall("Ana", inCall = true))
        assertEquals(CallCard(CallPhase.IN_CALL, "Ana", 0), tracker.current())
        assertEquals(1_000L, tracker.nextTickMs())
        now += 400
        assertEquals(600L, tracker.nextTickMs())
        assertEquals(0L, tracker.current()!!.seconds)
        now += 600
        assertEquals(1L, tracker.current()!!.seconds)
        assertEquals(1_000L, tracker.nextTickMs())
        now += 101_000
        assertEquals(102L, tracker.current()!!.seconds)
        assertFalse("Android Auto says it again: nothing changed", tracker.onCall("Ana", inCall = true))
        assertEquals("and the time does not start over", 102L, tracker.current()!!.seconds)
    }

    @Test
    fun `a new name on the same call changes the card's name, not the phase or the time`() {
        tracker.onCall("+55 11 91234-5678", inCall = true)
        now += 3_000
        assertFalse(tracker.onCall("Ana", inCall = true))
        assertEquals(CallCard(CallPhase.IN_CALL, "Ana", 3), tracker.current())
    }

    @Test
    fun `the call ending is a grey card with the whole time for two seconds, and then nothing`() {
        tracker.onCall("Ana", inCall = true)
        now += 222_900
        assertTrue(tracker.onNoCall())
        assertEquals(CallCard(CallPhase.ENDED, "Ana", 222), tracker.current())
        assertEquals(2_000L, tracker.nextTickMs())
        now += 1_999
        assertEquals(CallPhase.ENDED, tracker.current()!!.phase)
        assertEquals(1L, tracker.nextTickMs())
        now += 1
        assertNull(tracker.current())
        assertNull(tracker.nextTickMs())
        assertFalse("none again: nothing changed", tracker.onNoCall())
    }

    @Test
    fun `a call that rang and was not answered, or was declined, leaves no card at all`() {
        tracker.onCall("Ana", inCall = false)
        assertTrue(tracker.onNoCall())
        assertNull(tracker.current())
        tracker.onCall("Ana", inCall = false, dialing = true)
        assertTrue(tracker.onNoCall())
        assertNull(tracker.current())
    }

    @Test
    fun `a call after one that just ended starts its own time`() {
        tracker.onCall("Ana", inCall = true)
        now += 60_000
        tracker.onNoCall()
        now += 500
        assertTrue(tracker.onCall("Bia", inCall = false))
        assertEquals(CallCard(CallPhase.RINGING, "Bia"), tracker.current())
        now += 1_000
        tracker.onCall("Bia", inCall = true)
        now += 2_000
        assertEquals(CallCard(CallPhase.IN_CALL, "Bia", 2), tracker.current())
        now += 1_000
        tracker.onNoCall()
        assertEquals("the second call's time only", 3L, tracker.current()!!.seconds)
    }

    @Test
    fun `a second call ringing during a call, and answered, goes on with the same time`() {
        tracker.onCall("Ana", inCall = true)
        now += 10_000
        assertTrue(tracker.onCall("Bia", inCall = false))
        assertEquals(CallCard(CallPhase.RINGING, "Bia"), tracker.current())
        now += 5_000
        tracker.onCall("Bia", inCall = true)
        assertEquals(15L, tracker.current()!!.seconds)
    }

    @Test
    fun `reset takes the card away`() {
        tracker.onCall("Ana", inCall = true)
        tracker.reset()
        assertNull(tracker.current())
        assertNull(tracker.nextTickMs())
        assertFalse(tracker.onNoCall())
    }

    @Test
    fun `the grey card stays two seconds, in one place`() {
        assertEquals(2_000L, CallCards.END_SHOW_MS)
    }
}
