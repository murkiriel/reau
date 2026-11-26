package com.reau.app.dash

import com.reau.app.util.MonotonicClock

/**
 * Where the call is, for the call card: ringing, being dialled by the rider, going on, over a moment ago after it was answered (the card stays 2 s, grey), or missed
 * (it rang and ended unanswered: the card stays 5 s, grey, with "Missed call" in red).
 */
enum class CallPhase { RINGING, DIALING, IN_CALL, ENDED, MISSED }

/**
 * What the call card is about now: the [phase], the [name] (the contact's, or the number, or the word for a private number: the text the dash's own call card used to say)
 * and the [seconds] of the call so far ([CallPhase.IN_CALL]) or all of it ([CallPhase.ENDED]); 0 before it is answered.
 */
data class CallCard(val phase: CallPhase, val name: String, val seconds: Long = 0)

/** What a piece of the card's small line is: a plain hint, the answer's (green), the decline's (red), the end's (red) or the missed call's (red, with no shape). */
enum class CallRole { HINT, ANSWER, DECLINE, END, MISSED }

/** A piece of the small line, in one colour. An [CallRole.ANSWER] is drawn with a left triangle before it, a [CallRole.DECLINE] with a right one after it, an [CallRole.END] with a dot before it; a [CallRole.MISSED] has no shape. */
data class CallSpan(val text: String, val role: CallRole)

/**
 * The words of the small line, in the rider's language: [answer] and [decline] are one word each, [onCall] and [ended] take the time ("01:42"), [end], [calling] and [missed] stand alone.
 * The Android side builds it from the resources ([ActionEchoText.callWords]); a test builds it by hand.
 */
@Suppress("LongParameterList") // one word of the small line each, as the resources give them
class CallWords(
    val answer: String,
    val decline: String,
    val onCall: (String) -> String,
    val end: String,
    val calling: String,
    val ended: (String) -> String,
    val missed: String,
)

/**
 * What the compositor draws: the [card] (with the name) and the pieces of its small line. The same notice again is not drawn again. [battery]: the pill of the low battery
 * ([BatteryPill], 26/11/2026) in the call card's place and drawing: a battery for the handset, red for the border, the level for the name, the hint for the small line.
 */
data class CallNotice(val card: CallCard, val spans: List<CallSpan>, val battery: Boolean = false)

/**
 * The call card's decisions, with no Android class in them so the unit tests see them. The card is REau's own pill at the top of the dash's video
 * ([com.reau.app.video.CallCardInk]) for the whole call, in the place of the dash's own call screen: who calls (or the one called), the joystick's hints, the time of the call.
 */
object CallCards {
    /** How long the card stays, grey, after a call that was answered ends. */
    const val END_SHOW_MS = 2_000L

    /** How long the card stays, grey, with "Missed call", after a call that rang and ended unanswered. */
    const val MISSED_SHOW_MS = 5_000L

    /**
     * How long the card waits for the contact's name when Android Auto has told only the number: the name field stays empty meanwhile (the number is not flashed
     * for a moment before the name, as the ride of 23/11/2026 showed on a call the rider made), and when no name has come by then (a number that is not saved) the card says the number.
     */
    const val NAME_WAIT_MS = 2_000L

    private const val SECOND_MS = 1_000L
    private const val SECONDS_IN_HOUR = 3_600L
    private const val SECONDS_IN_MINUTE = 60L

    /** The between of two pieces of the small line. */
    const val SEPARATOR = " · "

    /** The time as the card says it: "01:42", and "1:02:03" past the hour. */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        val hours = s / SECONDS_IN_HOUR
        val minutes = s % SECONDS_IN_HOUR / SECONDS_IN_MINUTE
        val rest = s % SECONDS_IN_MINUTE
        return if (hours > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, rest) else "%02d:%02d".format(java.util.Locale.ROOT, minutes, rest)
    }

    /** The pieces of the small line of [card] in [words]: the joystick's hints while it rings and on the call, the time, the end. */
    fun spans(card: CallCard, words: CallWords): List<CallSpan> = when (card.phase) {
        CallPhase.RINGING -> listOf(CallSpan(words.answer, CallRole.ANSWER), CallSpan(SEPARATOR, CallRole.HINT), CallSpan(words.decline, CallRole.DECLINE))
        CallPhase.IN_CALL -> listOf(CallSpan(words.onCall(clock(card.seconds)) + SEPARATOR, CallRole.HINT), CallSpan(words.end, CallRole.END))
        CallPhase.DIALING -> listOf(CallSpan(words.calling, CallRole.HINT))
        CallPhase.ENDED -> listOf(CallSpan(words.ended(clock(card.seconds)), CallRole.HINT))
        CallPhase.MISSED -> listOf(CallSpan(words.missed, CallRole.MISSED))
    }

    fun notice(card: CallCard, words: CallWords): CallNotice = CallNotice(card, spans(card, words))

    /**
     * The pill of the low battery as the call card draws it ([BatteryPill]): [level] ("Battery 10 %") where the name goes and [hint] ("Plug in the charger") in red on the small
     * line, the way "Missed call" is. The words are the Android side's, in the rider's language; the level is the real one.
     */
    fun batteryNotice(level: String, hint: String): CallNotice =
        CallNotice(CallCard(CallPhase.MISSED, level), listOf(CallSpan(hint, CallRole.MISSED)), battery = true)

    /** The small line as plain text, with the marks the card draws as shapes: "◀ Answer · Decline ▶", "On call 01:42 · ● End", "Missed call". */
    fun plain(spans: List<CallSpan>): String = spans.joinToString("") {
        when (it.role) {
            CallRole.ANSWER -> "◀ ${it.text}"
            CallRole.DECLINE -> "${it.text} ▶"
            CallRole.END -> "● ${it.text}"
            CallRole.HINT, CallRole.MISSED -> it.text
        }
    }

    /** Whether the card is the grey one of a call that is over (ended after it was answered, or missed). */
    fun isOver(card: CallCard): Boolean = card.phase == CallPhase.ENDED || card.phase == CallPhase.MISSED

    /** The ride log's line when the card takes this phase: no name, no number. */
    fun logLine(card: CallCard): String = when (card.phase) {
        CallPhase.RINGING -> "call card: ringing"
        CallPhase.DIALING -> "call card: calling"
        CallPhase.IN_CALL -> "call card: in call"
        CallPhase.ENDED -> "call card: ended after ${clock(card.seconds)}"
        CallPhase.MISSED -> "call card: missed"
    }

    /**
     * The call as the card follows it, from what Android Auto says of the calls ([onCall]: one rings, is dialled or goes on; [onNoCall]: none). What shows now is [current]
     * (a call over stays [END_SHOW_MS]); [nextTickMs] says when it changes by itself (each second of a call on, the end of the grey card). A call that rang and ended
     * unanswered stays [MISSED_SHOW_MS] as a "Missed call" card (a new call ringing in the meantime takes its place at once); a call the rider dialled that was not answered, and one
     * the rider declined with the joystick ([onDeclined]), leave no card at all. A decline made on the phone itself looks like any call that ended unanswered, so it shows as missed.
     * The clock is a parameter, so a test moves it by hand.
     */
    class Tracker(private val nowMs: () -> Long = MonotonicClock::nowMs) {
        private var phase: CallPhase? = null
        private var name = ""
        private var answeredAtMs: Long? = null
        private var endedAtMs = 0L
        private var endedSeconds = 0L
        private var declined = false

        // The moment the number was first heard with no name to go with it (null: the name is known, or none is waited for). The card's name is empty until [NAME_WAIT_MS] later.
        private var waitingSinceMs: Long? = null

        /**
         * A call rings ([inCall] false), is dialled by the rider ([dialing]) or goes on ([inCall]), under [name]. True when the phase changed (for the log); the same again, or a
         * new name only, is false. The time of a call counts from the moment it is first seen going on, and goes on across a second ringing call.
         * [numberOnly]: [name] is only the call's number, Android Auto has not told the contact's name (yet): the card keeps the name field empty for [NAME_WAIT_MS] from
         * the first time it hears that number, and says the number only if no name has come by then; a name that comes in the meantime is written at once.
         */
        @Synchronized
        fun onCall(name: String, inCall: Boolean, dialing: Boolean = false, numberOnly: Boolean = false): Boolean {
            val now = nowMs()
            val next = when {
                inCall -> CallPhase.IN_CALL
                dialing -> CallPhase.DIALING
                else -> CallPhase.RINGING
            }
            if (next == CallPhase.IN_CALL && answeredAtMs == null) answeredAtMs = now
            // A call that begins (no card, or the grey one of the call before) waits again for its name, even under the same number as the call before.
            val begins = phase == null || phase == CallPhase.ENDED || phase == CallPhase.MISSED
            if (!numberOnly) {
                waitingSinceMs = null
            } else if (begins || waitingSinceMs == null || name != this.name) {
                waitingSinceMs = now
            }
            this.name = name
            val changed = phase != next
            phase = next
            return changed
        }

        /**
         * The rider declined the ringing call with the joystick (REau sent the decline): when it ends, it leaves no card, as it is not a missed call. Nothing when no call rings.
         * A decline on the phone itself cannot be told from a call that ended by itself.
         */
        @Synchronized
        fun onDeclined() {
            if (phase == CallPhase.RINGING) declined = true
        }

        /**
         * No call any more: one that was answered stays as the grey card for [END_SHOW_MS]; one that rang and was not answered stays for [MISSED_SHOW_MS] as a missed call,
         * unless the rider declined it ([onDeclined]); a call the rider dialled that was not answered leaves nothing. True when the card changed.
         */
        @Synchronized
        fun onNoCall(): Boolean {
            val now = nowMs()
            val changed = phase != null && phase != CallPhase.ENDED && phase != CallPhase.MISSED
            if (changed) {
                val answered = answeredAtMs
                if (answered == null) {
                    val missed = phase == CallPhase.RINGING && !declined
                    endedAtMs = now
                    phase = if (missed) CallPhase.MISSED else null
                } else {
                    endedSeconds = (now - answered).coerceAtLeast(0L) / SECOND_MS
                    endedAtMs = now
                    answeredAtMs = null
                    phase = CallPhase.ENDED
                }
                declined = false
            }
            // The call is over: the grey card says the number if no name ever came.
            waitingSinceMs = null
            return changed
        }

        /** Takes the card away (the switch went off, or the projection ended). */
        @Synchronized
        fun reset() {
            phase = null
            answeredAtMs = null
            declined = false
            waitingSinceMs = null
        }

        /** What the card shows now, or null for none. */
        @Synchronized
        fun current(): CallCard? {
            val now = nowMs()
            return when (val p = phase) {
                null -> null
                CallPhase.ENDED -> if (now - endedAtMs < END_SHOW_MS) CallCard(CallPhase.ENDED, name, endedSeconds) else null
                CallPhase.MISSED -> if (now - endedAtMs < MISSED_SHOW_MS) CallCard(CallPhase.MISSED, name) else null
                CallPhase.IN_CALL -> CallCard(CallPhase.IN_CALL, nameNow(now), (now - (answeredAtMs ?: now)).coerceAtLeast(0L) / SECOND_MS)
                CallPhase.RINGING, CallPhase.DIALING -> CallCard(p, nameNow(now))
            }
        }

        /** The name the card writes at [now]: empty while the number waits for its name, else [name]. */
        private fun nameNow(now: Long): String = if (nameWaitLeftMs(now) > 0L) "" else name

        /** How much of [NAME_WAIT_MS] is still to wait for the name at [now]; 0 when no name is waited for. */
        private fun nameWaitLeftMs(now: Long): Long = waitingSinceMs?.let { (it + NAME_WAIT_MS - now).coerceAtLeast(0L) } ?: 0L

        /**
         * In how long [current] changes by itself, or null when it does not (ringing, dialling, no card): the next whole second of a call on, the end of the grey card (2 s, or 5 s for a
         * missed call), the end of the wait for a name ([NAME_WAIT_MS]: the number is written then), whichever comes first.
         */
        @Synchronized
        fun nextTickMs(): Long? {
            val now = nowMs()
            val nameWait = nameWaitLeftMs(now).takeIf { it > 0L }
            val own = when (phase) {
                CallPhase.IN_CALL -> SECOND_MS - (now - (answeredAtMs ?: now)).coerceAtLeast(0L) % SECOND_MS
                CallPhase.ENDED -> (endedAtMs + END_SHOW_MS - now).takeIf { it > 0L }
                CallPhase.MISSED -> (endedAtMs + MISSED_SHOW_MS - now).takeIf { it > 0L }
                else -> null
            }
            return if (own == null) nameWait else if (nameWait == null) own else minOf(own, nameWait)
        }
    }
}
