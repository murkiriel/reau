package com.reau.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.reau.app.dash.DashPairing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one notice, before the first ride, that tells the rider of a Xiaomi (Redmi, POCO) or a realme (OPPO, OnePlus) phone to do the maker's own settings that
 * keep the system from closing REau (26/11/2026, A3 of the Xiaomi and realme plan). The rule, pure ([due]): only on the two families, only once the phone has
 * joined the dash for the first time (not at the first opening: a rider who has not seen REau work does not yet know why he would need it), once, and once more
 * after the system closed REau in a ride ([SystemClosedRide] told it). It is not a second card for that case: when the card of the system closing REau is
 * pending, it is the one shown, and its button leads to the same page ([com.reau.app.ui.MainLook.keepAliveWarnings]). What is kept on the phone is the time
 * the notice was answered and the time of the last closing that was told by then.
 */
object KeepAliveNotice {
    /**
     * Whether the notice is due: [eligible] (the phone's family has steps of its own), [paired] (the phone has joined the dash once), and either it was not answered
     * yet ([answeredAtMs] 0) or the system closed REau ([lastClosedMs], as [SystemClosedRide] kept it) after the closing the answer knew of ([answeredClosedMs]).
     */
    fun due(eligible: Boolean, paired: Boolean, answeredAtMs: Long, lastClosedMs: Long, answeredClosedMs: Long): Boolean {
        if (!eligible || !paired) return false
        return answeredAtMs <= 0L || lastClosedMs > answeredClosedMs
    }

    private val dueNow = MutableStateFlow(false)

    /** Whether the main screen shows the notice now. */
    val isDue: StateFlow<Boolean> get() = dueNow

    /** The phone has joined the dash at least once: Android's question about joining it was answered (see [DashPairing.joinedSsid]); a failure reads as not paired. */
    fun paired(context: Context): Boolean = runCatching { DashPairing.open(context).joinedSsid.isNotBlank() }.getOrDefault(false)

    /**
     * Looks again at what is kept and at the pairing, and hands the answer to [isDue]; the activity calls it at each return to the screen. A phone of another
     * family is not asked anything (the pairing is not even read): it is never due.
     */
    fun refresh(context: Context, eligible: Boolean) {
        if (!eligible) {
            dueNow.value = false
            return
        }
        val book = Book.open(context)
        dueNow.value = due(eligible, paired(context), book.answeredAt(), SystemClosedRide.lastNotedAt(context), book.answeredClosed())
    }

    /**
     * The rider answered: opened the steps or said he understood ([how] goes to the log). Kept with the last closing known now, so the next closing makes it due
     * again. Nothing on a phone of another family, and nothing again when the answer is already kept and no closing came after it (the page opens it each time).
     */
    fun answered(context: Context, eligible: Boolean, how: String) {
        if (!eligible) return
        val book = Book.open(context)
        val closedMs = SystemClosedRide.lastNotedAt(context)
        dueNow.value = false
        if (book.answeredAt() > 0L && closedMs <= book.answeredClosed()) return
        book.answer(System.currentTimeMillis(), closedMs)
        runCatching { RideDiagnostics.log("ui", "keepalive: the one-time notice was answered ($how)") }
    }

    /** What is kept on the phone (epoch ms; none kept reads as 0). */
    internal class Book(private val prefs: SharedPreferences) {
        fun answeredAt(): Long = prefs.getLong(ANSWERED, 0L)

        fun answeredClosed(): Long = prefs.getLong(CLOSED, 0L)

        /** Forgets the answer, so the notice is due again (only the debug build's "show the card again" calls it, 26/11/2026; nothing of a release does). */
        fun forget() {
            prefs.edit {
                remove(ANSWERED)
                remove(CLOSED)
            }
        }

        fun answer(nowMs: Long, closedMs: Long) {
            prefs.edit {
                putLong(ANSWERED, nowMs)
                putLong(CLOSED, closedMs)
            }
        }

        companion object {
            private const val NAME = "reau_keepalive_notice"
            private const val ANSWERED = "answered_at"
            private const val CLOSED = "closed_at_answer"

            fun open(context: Context) = Book((context.applicationContext ?: context).getSharedPreferences(NAME, Context.MODE_PRIVATE))
        }
    }
}
