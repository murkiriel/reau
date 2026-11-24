package com.reau.app.dash

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call card's decisions ([CallCards]): the time as it is said, the small line of each state in the three languages (the words are the ones of the resource files),
 * what the card follows of the call (ringing, in call with the time running, over for 2 s, missed for 5 s, or gone), and the ride log's lines, which never carry a name or a number.
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
        missed = string(folder, "call_card_missed"),
    )

    private fun line(folder: String, card: CallCard) = CallCards.plain(CallCards.spans(card, words(folder)))

    private val ringing = CallCard(CallPhase.RINGING, "Ana")
    private val dialing = CallCard(CallPhase.DIALING, "Ana")
    private val onCall = CallCard(CallPhase.IN_CALL, "Ana", 102)
    private val ended = CallCard(CallPhase.ENDED, "Ana", 222)
    private val missed = CallCard(CallPhase.MISSED, "Ana")

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
        assertEquals("Chamada perdida", line("values-pt", missed))
    }

    @Test
    fun `in English`() {
        assertEquals("◀ Answer · Decline ▶", line("values", ringing))
        assertEquals("On call 01:42 · ● End", line("values", onCall))
        assertEquals("Calling…", line("values", dialing))
        assertEquals("Ended · 03:42", line("values", ended))
        assertEquals("Missed call", line("values", missed))
    }

    @Test
    fun `in Spanish`() {
        assertEquals("◀ Atender · Rechazar ▶", line("values-es", ringing))
        assertEquals("En llamada 01:42 · ● Colgar", line("values-es", onCall))
        assertEquals("Llamando…", line("values-es", dialing))
        assertEquals("Finalizada · 03:42", line("values-es", ended))
        assertEquals("Llamada perdida", line("values-es", missed))
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
        assertEquals("the missed call's words have their own role (red, no shape)", listOf(CallSpan("Chamada perdida", CallRole.MISSED)), CallCards.spans(missed, w))
    }

    @Test
    fun `the notice is the card with its line, and the same one again is equal (it is not drawn again)`() {
        val w = words("values")
        assertEquals(CallCards.notice(onCall, w), CallCards.notice(onCall.copy(), w))
        assertFalse(CallCards.notice(onCall, w) == CallCards.notice(onCall.copy(seconds = 103), w))
        assertTrue(CallCards.isOver(ended))
        assertTrue("the missed call's card is the grey one too", CallCards.isOver(missed))
        assertFalse(CallCards.isOver(onCall))
        assertFalse(CallCards.isOver(ringing))
    }

    @Test
    fun `the ride log has the phase and the time, never a name or a number`() {
        val named = CallCard(CallPhase.IN_CALL, "Ana Souza +55 11 91234-5678", 5)
        assertEquals("call card: ringing", CallCards.logLine(named.copy(phase = CallPhase.RINGING)))
        assertEquals("call card: calling", CallCards.logLine(named.copy(phase = CallPhase.DIALING)))
        assertEquals("call card: in call", CallCards.logLine(named))
        assertEquals("call card: ended after 03:42", CallCards.logLine(ended))
        assertEquals("call card: missed", CallCards.logLine(named.copy(phase = CallPhase.MISSED)))
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
    fun `a call that rang and was not answered is a missed card for five seconds, and then nothing`() {
        tracker.onCall("Ana", inCall = false)
        now += 20_000
        assertTrue(tracker.onNoCall())
        assertEquals(CallCard(CallPhase.MISSED, "Ana"), tracker.current())
        assertEquals(5_000L, tracker.nextTickMs())
        now += 4_999
        assertEquals(CallPhase.MISSED, tracker.current()!!.phase)
        assertEquals(1L, tracker.nextTickMs())
        now += 1
        assertNull(tracker.current())
        assertNull(tracker.nextTickMs())
        assertFalse("none again: nothing changed, the notice does not come back", tracker.onNoCall())
        assertNull(tracker.current())
    }

    @Test
    fun `a missed call is told again as none while its card shows, and the card stays the same`() {
        tracker.onCall("Ana", inCall = false)
        assertTrue(tracker.onNoCall())
        now += 2_000
        assertFalse(tracker.onNoCall())
        assertEquals(CallCard(CallPhase.MISSED, "Ana"), tracker.current())
        assertEquals("the five seconds count from the end of the call", 3_000L, tracker.nextTickMs())
    }

    @Test
    fun `a call the rider dialled that was not answered leaves no card`() {
        tracker.onCall("Ana", inCall = false, dialing = true)
        assertTrue(tracker.onNoCall())
        assertNull(tracker.current())
        assertNull(tracker.nextTickMs())
    }

    @Test
    fun `a call the rider declined with the joystick is no missed call`() {
        tracker.onCall("Ana", inCall = false)
        tracker.onDeclined()
        assertEquals("the decline alone changes nothing", CallCard(CallPhase.RINGING, "Ana"), tracker.current())
        assertTrue(tracker.onNoCall())
        assertNull(tracker.current())
        assertNull(tracker.nextTickMs())
    }

    @Test
    fun `the decline is of that call only, the next one that rings and goes unanswered is missed`() {
        tracker.onCall("Ana", inCall = false)
        tracker.onDeclined()
        tracker.onNoCall()
        now += 100
        tracker.onCall("Bia", inCall = false)
        tracker.onNoCall()
        assertEquals(CallCard(CallPhase.MISSED, "Bia"), tracker.current())
    }

    @Test
    fun `a decline with nothing ringing is forgotten, and does not hide a later missed call`() {
        tracker.onDeclined()
        tracker.onCall("Ana", inCall = false)
        tracker.onNoCall()
        assertEquals(CallPhase.MISSED, tracker.current()!!.phase)
        tracker.onCall("Bia", inCall = true)
        tracker.onDeclined()
        now += 3_000
        tracker.onNoCall()
        assertEquals("a call that was on ends as it did", CallCard(CallPhase.ENDED, "Bia", 3), tracker.current())
    }

    @Test
    fun `reset forgets a decline too`() {
        tracker.onCall("Ana", inCall = false)
        tracker.onDeclined()
        tracker.reset()
        tracker.onCall("Bia", inCall = false)
        tracker.onNoCall()
        assertEquals(CallCard(CallPhase.MISSED, "Bia"), tracker.current())
    }

    @Test
    fun `an answered call ends as before, a grey card with the time for two seconds, however it rang`() {
        tracker.onCall("Ana", inCall = false)
        now += 3_000
        tracker.onCall("Ana", inCall = true)
        now += 10_000
        assertTrue(tracker.onNoCall())
        assertEquals(CallCard(CallPhase.ENDED, "Ana", 10), tracker.current())
        assertEquals(2_000L, tracker.nextTickMs())
    }

    @Test
    fun `a new call ringing while the missed card shows takes its place at once`() {
        tracker.onCall("Ana", inCall = false)
        tracker.onNoCall()
        now += 2_000
        assertTrue(tracker.onCall("Bia", inCall = false))
        assertEquals(CallCard(CallPhase.RINGING, "Bia"), tracker.current())
        assertNull(tracker.nextTickMs())
        now += 10_000
        assertEquals("the old card's time does not end the new one", CallCard(CallPhase.RINGING, "Bia"), tracker.current())
    }

    @Test
    fun `reset takes the missed card away too`() {
        tracker.onCall("Ana", inCall = false)
        tracker.onNoCall()
        tracker.reset()
        assertNull(tracker.current())
        assertNull(tracker.nextTickMs())
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

    // The name that comes after the number (a call the rider makes): the card's name field waits for it and writes the number only when none comes.

    @Test
    fun `a number with no name yet is an empty name for half a second, and the name that comes then is the only one written`() {
        assertTrue(tracker.onCall("5511999990000", inCall = true, numberOnly = true))
        assertEquals("the number is not shown", CallCard(CallPhase.IN_CALL, "", 0), tracker.current())
        now += 500
        assertEquals(CallCard(CallPhase.IN_CALL, "", 0), tracker.current())
        assertFalse("the name only: the phase did not change", tracker.onCall("Ana", inCall = true))
        assertEquals("the name at once, with the time that runs", CallCard(CallPhase.IN_CALL, "Ana", 0), tracker.current())
        now += 1_600
        assertEquals("and the number does not come back after the 2 s", CallCard(CallPhase.IN_CALL, "Ana", 2), tracker.current())
    }

    @Test
    fun `a name that never comes is the number after 2 s`() {
        tracker.onCall("5511999990000", inCall = true, numberOnly = true)
        now += CallCards.NAME_WAIT_MS - 1
        assertEquals("", tracker.current()!!.name)
        now += 1
        assertEquals(CallCard(CallPhase.IN_CALL, "5511999990000", 2), tracker.current())
        assertFalse("the same number again does not start the wait over", tracker.onCall("5511999990000", inCall = true, numberOnly = true))
        assertEquals("5511999990000", tracker.current()!!.name)
    }

    @Test
    fun `the number heard again while it waits does not push the 2 s back`() {
        tracker.onCall("5511999990000", inCall = false, numberOnly = true)
        now += 1_500
        tracker.onCall("5511999990000", inCall = false, numberOnly = true)
        now += 500
        assertEquals("5511999990000", tracker.current()!!.name)
    }

    @Test
    fun `a ringing call waits for the name the same way, and the wait is a tick of its own`() {
        tracker.onCall("5511999990000", inCall = false, numberOnly = true)
        assertEquals(CallCard(CallPhase.RINGING, ""), tracker.current())
        assertEquals("the card is drawn again when the wait ends", 2_000L, tracker.nextTickMs())
        now += 700
        assertEquals(1_300L, tracker.nextTickMs())
        now += 1_300
        assertEquals(CallCard(CallPhase.RINGING, "5511999990000"), tracker.current())
        assertNull("nothing more to wait for", tracker.nextTickMs())
    }

    @Test
    fun `a dialled call waits for the name too`() {
        tracker.onCall("5511999990000", inCall = false, dialing = true, numberOnly = true)
        assertEquals(CallCard(CallPhase.DIALING, ""), tracker.current())
        tracker.onCall("Ana", inCall = false, dialing = true)
        assertEquals(CallCard(CallPhase.DIALING, "Ana"), tracker.current())
    }

    @Test
    fun `on a call the next tick is the nearer of the second and the end of the wait`() {
        tracker.onCall("5511999990000", inCall = true, numberOnly = true)
        now += 400
        assertEquals("the second", 600L, tracker.nextTickMs())
        now += 600
        assertEquals("a second again, the wait has 1 s to go", 1_000L, tracker.nextTickMs())
        tracker.onCall("Ana", inCall = true)
        assertEquals("with the name there is only the second", 1_000L, tracker.nextTickMs())
    }

    @Test
    fun `a name that came is kept when the number is told again with it`() {
        tracker.onCall("Ana", inCall = true)
        assertEquals("Ana", tracker.current()!!.name)
        now += 5_000
        tracker.onCall("Ana", inCall = true)
        assertEquals("Ana", tracker.current()!!.name)
    }

    @Test
    fun `a call that ends before the name came leaves the number on its grey card, answered or missed`() {
        tracker.onCall("5511999990000", inCall = false, numberOnly = true)
        now += 300
        tracker.onNoCall()
        assertEquals(CallCard(CallPhase.MISSED, "5511999990000"), tracker.current())
        now += 5_000
        tracker.onCall("5511999990001", inCall = true, numberOnly = true)
        now += 700
        tracker.onNoCall()
        assertEquals(CallCard(CallPhase.ENDED, "5511999990001", 0), tracker.current())
    }

    @Test
    fun `a new call under the same number waits for its name again, and so does the second call that rings during a call`() {
        tracker.onCall("5511999990000", inCall = true, numberOnly = true)
        now += 3_000
        tracker.onNoCall()
        now += 100
        tracker.onCall("5511999990000", inCall = false, numberOnly = true)
        assertEquals("", tracker.current()!!.name)
        tracker.reset()
        tracker.onCall("Ana", inCall = true)
        now += 10_000
        tracker.onCall("5511999990002", inCall = false, numberOnly = true)
        assertEquals(CallCard(CallPhase.RINGING, ""), tracker.current())
        tracker.onCall("Bia", inCall = false)
        assertEquals("Bia", tracker.current()!!.name)
    }

    @Test
    fun `the private call has no number to wait with, so the word for it is the name at once`() {
        tracker.onCall("Número privado", inCall = false)
        assertEquals("Número privado", tracker.current()!!.name)
        assertNull(tracker.nextTickMs())
    }

    @Test
    fun `reset forgets the wait`() {
        tracker.onCall("5511999990000", inCall = true, numberOnly = true)
        tracker.reset()
        tracker.onCall("5511999990000", inCall = true)
        assertEquals("5511999990000", tracker.current()!!.name)
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
    fun `the grey card stays two seconds and the missed call's five, each in one place`() {
        assertEquals(2_000L, CallCards.END_SHOW_MS)
        assertEquals(5_000L, CallCards.MISSED_SHOW_MS)
    }
}
