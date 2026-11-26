package com.reau.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The phone's system closing REau in the middle of a ride: some makers' battery managers end a process that is running a foreground service, and the
 * rider is left without the dash and does not know why. The next opening of the app reads how the process before it ended (`ApplicationExitInfo`,
 * [ExitHistory]); when it was the system's work, with the projection on, the main screen tells the rider once, until the rider dismisses it, and points to
 * the guide of what lowers the chance of it happening again ([com.reau.app.ui.KeepAliveGuide]). The decision is a pure rule over the exit's numbers; what is
 * kept on the phone is the time of the exit told and the one dismissed.
 */
object SystemClosedRide {
    /**
     * The reasons that mean the system ended the process from outside (Android's `ApplicationExitInfo.REASON_*`, as numbers like [ExitReasons]): signaled (2),
     * low memory (3), excessive resource usage (9), user requested (10: a force stop, which a maker's manager issues too), user stopped (11), dependency died
     * (12), other (13) and freezer (14). Left out on purpose: the app's own exit (1), crashes (4, 5) and ANR (6), which the crash reports tell; a failed start
     * (7); a permission change (8); and the package being updated or changed (15, 16), which is the rider installing.
     */
    internal val BY_THE_SYSTEM = setOf(2, 3, 9, 10, 11, 12, 13, 14)

    /** At most "foreground service": REau was projecting, not sitting cached in the background (the same line as [ExitReasons]). */
    private const val PROJECTING_IMPORTANCE = 125

    /** What Android writes when the rider removed the app from the recent apps: the rider's own act, not the system's. */
    private val BY_THE_RIDER = listOf("remove task", "swipeupclean")

    /** How old an exit may be to be told: an exit of days ago says nothing useful about this ride. */
    const val MAX_AGE_MS = 3 * 24 * 60 * 60_000L

    /**
     * Whether an exit ([reason], [importance] and [description] as Android kept them, at [timestampMs]) is the system closing REau during a projection, seen at
     * [nowMs]: a reason of [BY_THE_SYSTEM], an importance of a foreground service or better, a description that does not say the rider removed the app from the
     * recent apps, and not older than [MAX_AGE_MS]. The Desligar button does not come here: it stops the service first, so the process leaves as cached (400)
     * or by itself (reason 1), and neither is told.
     */
    fun decide(reason: Int, importance: Int, description: String?, timestampMs: Long, nowMs: Long): Boolean =
        reason in BY_THE_SYSTEM && importance in 1..PROJECTING_IMPORTANCE && nowMs - timestampMs in 0..MAX_AGE_MS &&
            BY_THE_RIDER.none { description?.contains(it, ignoreCase = true) == true }

    /** The newest of [exits] to tell: one [decide] says yes to and that came after [toldUpTo] (the newest time already told or dismissed); null when none. */
    fun pick(exits: List<PastExit>, toldUpTo: Long, nowMs: Long): PastExit? =
        exits.filter { it.timestampMs > toldUpTo && decide(it.reason, it.importance, it.description, it.timestampMs, nowMs) }.maxByOrNull { it.timestampMs }

    /**
     * The time of the exit as the notice writes it: the hour and minute in the phone's way of writing them, and the day too when it was not [nowMs]'s day.
     * [locale] and [zone] are the phone's, handed in so a test can fix them.
     */
    fun stamp(exitMs: Long, nowMs: Long, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale).apply { timeZone = zone }.format(Date(exitMs))
        if (sameDay(exitMs, nowMs, zone)) return time
        return DateFormat.getDateInstance(DateFormat.SHORT, locale).apply { timeZone = zone }.format(Date(exitMs)) + " " + time
    }

    private fun sameDay(a: Long, b: Long, zone: TimeZone): Boolean {
        val first = Calendar.getInstance(zone).apply { timeInMillis = a }
        val second = Calendar.getInstance(zone).apply { timeInMillis = b }
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
    }

    /** The ride log's line when the rider is told; [at] is the exit's own time as the log writes times. */
    fun logLine(exit: PastExit, at: String): String =
        "the system closed REau during the last projection (at $at, reason ${ExitReasons.reasonName(exit.reason)} (${exit.reason})): the rider is told"

    // -- what is kept and what the screen reads ----------------------------------------------------------------------------

    private val told = MutableStateFlow<Long?>(null)

    /** The time of the exit the main screen tells the rider about, or null with none to tell (or once dismissed). */
    val pendingAt: StateFlow<Long?> get() = told

    /** The time of the newest exit ever told (0 with none), dismissed or not: [KeepAliveNotice] shows itself again when a later one comes. */
    fun lastNotedAt(context: Context): Long = Book.open(context).notedAt()

    /**
     * Reads what is kept on the phone into [pendingAt]; the activity calls it when it opens, off the main thread (the first read is the disk). It runs while the opening's
     * thread may be in [note], so the read and the hand-over to the flow are one step under a lock, with [note]'s: the last one to hand over has read the latest state.
     */
    fun load(context: Context) {
        val book = Book.open(context)
        synchronized(told) { told.value = book.pendingAt() }
    }

    /**
     * Looks at the past [exits] (the exit history the opening reads): the newest one that [pick] takes, if any not told yet, is kept, written in the ride log
     * once and handed to [pendingAt]. Called at every opening of the app and at a projection's start; with nothing new it changes nothing.
     */
    fun note(context: Context, exits: List<PastExit>, nowMs: Long) {
        val book = Book.open(context)
        val exit = pick(exits, book.toldUpTo(), nowMs) ?: return
        if (!book.note(exit.timestampMs)) return
        synchronized(told) { told.value = book.pendingAt() }
        runCatching {
            val at = SimpleDateFormat("HH:mm", Locale.US).format(Date(exit.timestampMs))
            RideDiagnostics.log("ui", logLine(exit, at))
        }
    }

    /** The rider dismissed the notice (or opened the guide it points to): it is not shown again for that exit. */
    fun dismiss(context: Context) {
        Book.open(context).dismiss()
        told.value = null
        runCatching { RideDiagnostics.log("ui", "the rider dismissed the notice that the system closed REau") }
    }

    /** What is kept on the phone: the time of the newest exit told and the time of the newest one dismissed (both epoch ms; none kept reads as 0). */
    internal class Book(private val prefs: SharedPreferences) {
        private fun at(key: String): Long = prefs.getLong(key, 0L)

        /** The time of the exit told and not dismissed yet; null when none. */
        fun pendingAt(): Long? = at(NOTED).takeIf { it > at(DISMISSED) }

        /** The time of the newest exit told, whether dismissed since or not; 0 with none. */
        fun notedAt(): Long = at(NOTED)

        /** The newest time that is not to be told again: told already, or dismissed. */
        fun toldUpTo(): Long = maxOf(at(NOTED), at(DISMISSED))

        /** Keeps [exitMs] as told; false when an exit that new or newer was told already. */
        fun note(exitMs: Long): Boolean {
            if (exitMs <= toldUpTo()) return false
            prefs.edit { putLong(NOTED, exitMs) }
            return true
        }

        fun dismiss() {
            val noted = at(NOTED)
            if (noted > at(DISMISSED)) prefs.edit { putLong(DISMISSED, noted) }
        }

        companion object {
            private const val NAME = "reau_system_closed"
            private const val NOTED = "noted_at"
            private const val DISMISSED = "dismissed_at"

            fun open(context: Context) = Book((context.applicationContext ?: context).getSharedPreferences(NAME, Context.MODE_PRIVATE))
        }
    }
}
