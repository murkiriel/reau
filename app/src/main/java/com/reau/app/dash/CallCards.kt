package com.reau.app.dash

import com.reau.app.util.MonotonicClock

/** Where the call is, for the call card: ringing, being dialled by the rider, going on, or over a moment ago (the card stays 2 s, grey). */
enum class CallPhase { RINGING, DIALING, IN_CALL, ENDED }

/**
 * What the call card is about now: the [phase], the [name] (the contact's, or the number, or the word for a private number: the text the dash's own call card used to say)
 * and the [seconds] of the call so far ([CallPhase.IN_CALL]) or all of it ([CallPhase.ENDED]); 0 before it is answered.
 */
data class CallCard(val phase: CallPhase, val name: String, val seconds: Long = 0)

/** What a piece of the card's small line is: a plain hint, the answer's (green), the decline's (red) or the end's (red). */
enum class CallRole { HINT, ANSWER, DECLINE, END }

/** A piece of the small line, in one colour. An [CallRole.ANSWER] is drawn with a left triangle before it, a [CallRole.DECLINE] with a right one after it, an [CallRole.END] with a dot before it. */
data class CallSpan(val text: String, val role: CallRole)

/**
 * The words of the small line, in the rider's language: [answer] and [decline] are one word each, [onCall] and [ended] take the time ("01:42"), [end] and [calling] stand alone.
 * The Android side builds it from the resources ([ActionEchoText.callWords]); a test builds it by hand.
 */
class CallWords(
    val answer: String,
    val decline: String,
    val onCall: (String) -> String,
    val end: String,
    val calling: String,
    val ended: (String) -> String,
)

/** What the compositor draws: the [card] (with the name) and the pieces of its small line. The same notice again is not drawn again. */
data class CallNotice(val card: CallCard, val spans: List<CallSpan>)

/**
 * The call card's decisions, with no Android class in them so the unit tests see them. The card is REau's own pill at the top of the dash's video
 * ([com.reau.app.video.CallCardInk]) for the whole call, in the place of the dash's own call screen: who calls (or the one called), the joystick's hints, the time of the call.
 */
object CallCards {
    /** How long the card stays, grey, after the call ends. */
    const val END_SHOW_MS = 2_000L

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
    }

    fun notice(card: CallCard, words: CallWords): CallNotice = CallNotice(card, spans(card, words))

    /** The small line as plain text, with the marks the card draws as shapes: "◀ Answer · Decline ▶", "On call 01:42 · ● End". */
    fun plain(spans: List<CallSpan>): String = spans.joinToString("") {
        when (it.role) {
            CallRole.ANSWER -> "◀ ${it.text}"
            CallRole.DECLINE -> "${it.text} ▶"
            CallRole.END -> "● ${it.text}"
            CallRole.HINT -> it.text
        }
    }

    /** Whether the card is the grey one of a call that is over. */
    fun isOver(card: CallCard): Boolean = card.phase == CallPhase.ENDED

    /** The ride log's line when the card takes this phase: no name, no number. */
    fun logLine(card: CallCard): String = when (card.phase) {
        CallPhase.RINGING -> "call card: ringing"
        CallPhase.DIALING -> "call card: calling"
        CallPhase.IN_CALL -> "call card: in call"
        CallPhase.ENDED -> "call card: ended after ${clock(card.seconds)}"
    }

    /**
     * The call as the card follows it, from what Android Auto says of the calls ([onCall]: one rings, is dialled or goes on; [onNoCall]: none). What shows now is [current]
     * (a call over stays [END_SHOW_MS]); [nextTickMs] says when it changes by itself (each second of a call on, the end of the grey card). A call that rang and was not
     * answered, or was declined, leaves no card at all. The clock is a parameter, so a test moves it by hand.
     */
    class Tracker(private val nowMs: () -> Long = MonotonicClock::nowMs) {
        private var phase: CallPhase? = null
        private var name = ""
        private var answeredAtMs: Long? = null
        private var endedAtMs = 0L
        private var endedSeconds = 0L

        /**
         * A call rings ([inCall] false), is dialled by the rider ([dialing]) or goes on ([inCall]), under [name]. True when the phase changed (for the log); the same again, or a
         * new name only, is false. The time of a call counts from the moment it is first seen going on, and goes on across a second ringing call.
         */
        @Synchronized
        fun onCall(name: String, inCall: Boolean, dialing: Boolean = false): Boolean {
            val now = nowMs()
            val next = when {
                inCall -> CallPhase.IN_CALL
                dialing -> CallPhase.DIALING
                else -> CallPhase.RINGING
            }
            if (next == CallPhase.IN_CALL && answeredAtMs == null) answeredAtMs = now
            this.name = name
            val changed = phase != next
            phase = next
            return changed
        }

        /** No call any more: one that was answered stays as the grey card for [END_SHOW_MS]; one that never was leaves nothing. True when the card changed. */
        @Synchronized
        fun onNoCall(): Boolean {
            val now = nowMs()
            val changed = phase != null && phase != CallPhase.ENDED
            if (changed) {
                val answered = answeredAtMs
                if (answered == null) {
                    phase = null
                } else {
                    endedSeconds = (now - answered).coerceAtLeast(0L) / SECOND_MS
                    endedAtMs = now
                    answeredAtMs = null
                    phase = CallPhase.ENDED
                }
            }
            return changed
        }

        /** Takes the card away (the switch went off, or the projection ended). */
        @Synchronized
        fun reset() {
            phase = null
            answeredAtMs = null
        }

        /** What the card shows now, or null for none. */
        @Synchronized
        fun current(): CallCard? {
            val now = nowMs()
            return when (val p = phase) {
                null -> null
                CallPhase.ENDED -> if (now - endedAtMs < END_SHOW_MS) CallCard(CallPhase.ENDED, name, endedSeconds) else null
                CallPhase.IN_CALL -> CallCard(CallPhase.IN_CALL, name, (now - (answeredAtMs ?: now)).coerceAtLeast(0L) / SECOND_MS)
                CallPhase.RINGING, CallPhase.DIALING -> CallCard(p, name)
            }
        }

        /** In how long [current] changes by itself, or null when it does not (ringing, dialling, no card): the next whole second of a call on, the end of the grey card. */
        @Synchronized
        fun nextTickMs(): Long? {
            val now = nowMs()
            return when (phase) {
                CallPhase.IN_CALL -> SECOND_MS - (now - (answeredAtMs ?: now)).coerceAtLeast(0L) % SECOND_MS
                CallPhase.ENDED -> (endedAtMs + END_SHOW_MS - now).takeIf { it > 0L }
                else -> null
            }
        }
    }
}
