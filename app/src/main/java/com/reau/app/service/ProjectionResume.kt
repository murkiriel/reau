package com.reau.app.service

import android.app.Service
import android.os.Build

/**
 * The partial resume of the projection after the system ended the process (a maker's battery manager: ColorOS "o-kill", reason OTHER (13)), as
 * the pure rule it is: no Android object, no clock, nothing kept here ([ProjectionResumeBook] keeps the mark).
 *
 * While the rider has the projection on, the service answers START_STICKY ([returnFor]) and a mark is kept on the phone: the instant of the
 * service's last sign of life, renewed about once a minute. When the process is killed the system starts the service again with no intent;
 * [decide] says whether that is the rider's projection coming back. It is not for a force stop (Xiaomi's "FORCE STOP"): the package is then
 * "stopped" and no restart, alarm or job comes at all, which no code can change.
 *
 * Why 30 minutes of silence ([MAX_IDLE_MS]): a restart comes seconds to a few minutes after the kill (the system backs off between restarts), so a
 * mark older than that belongs to a ride that ended with the phone in a pocket, and bringing it back would only hold the phone's locks until the dash
 * is waited for in vain ([com.reau.app.settings.REauSettings.noDashStopMinutes] would end it, 5 to 20 minutes later). And why 3 in 30 minutes
 * ([MAX_ATTEMPTS], [WINDOW_MS]): a process that is killed again and again within minutes of coming back is a phone that does not want REau running; the third
 * resume is the last, and the rider is left with the notice of the system closing REau ([com.reau.app.data.SystemClosedRide]).
 */
object ProjectionResume {
    /** The longest the mark may have gone without a sign of life, to be resumed. */
    const val MAX_IDLE_MS = 30 * 60_000L

    /** The window in which the resumes are counted, and the most there may be in it. */
    const val WINDOW_MS = 30 * 60_000L
    const val MAX_ATTEMPTS = 3

    /**
     * How long the dash may have been gone, as of the last sign of life, for the projection to be resumed (25/11/2026). The mark used to know nothing of the dash: a
     * rider who switched the bike off and kept the phone in a pocket had REau killed 10 minutes later, the restart found a mark with a sign of life a minute old,
     * and the resume held the phone's locks for up to [com.reau.app.settings.REauSettings.noDashStopMinutes] more minutes for a dash that was not there, up to 3 times.
     * Two minutes: the beat writes the dash's last instant at most a minute late, and a tunnel or a Wi-Fi hiccup that took the dash for a minute or so is a ride
     * that goes on; a dash gone for longer is a bike that is off.
     */
    const val DASH_GONE_MAX_MS = 2 * 60_000L

    /** How often the mark's instant is renewed while the projection runs (the service looks at the phone every 5 s; the disk is written once a minute). */
    const val BEAT_EVERY_MS = 60_000L

    private const val MS_PER_MINUTE = 60_000L

    /**
     * What is kept: [lastSeenMs] the instant of the last sign of life (wall clock: it has to outlive the process; [Mark.card]: the card mode was the one on),
     * [resumedAtMs] the resumes of this ride so far, [removedAtMs] when the rider took the app off the recent apps during this ride (0 when not), and [dashUpAtMs]
     * the last instant the dash was connected (or, with the projection just switched on or just resumed, that instant: the dash is then waited for; 0 when the mark
     * does not say, which reads as "unknown, resume"): see [decide].
     */
    class Mark(
        val lastSeenMs: Long,
        val card: Boolean,
        val resumedAtMs: List<Long> = emptyList(),
        val removedAtMs: Long = 0L,
        val dashUpAtMs: Long = 0L,
    )

    /** Whether the dash is connected, for the mark: ready, and nothing else (connecting, dropped, taken by another phone, asking for a name). */
    fun dashUp(link: DashLink): Boolean = link == DashLink.READY

    /** What to do with a restart that has no intent. */
    sealed interface Verdict {
        /** The rider's projection comes back: [attempt] (1-based) of [MAX_ATTEMPTS], in the [card] mode, with [resumedAtMs] the resumes counting this one. */
        class Resume(val attempt: Int, val card: Boolean, val resumedAtMs: List<Long>) : Verdict

        /** It does not: [why] goes to the log. */
        class Drop(val why: String) : Verdict
    }

    /**
     * Whether a restart with no intent at [nowMs] resumes the projection. In this order: the rider's switch is off; there is no mark (the projection was
     * not on, or the rider turned it off: both erase it); the clock is behind the mark; the last sign of life is more than [MAX_IDLE_MS] old; the rider
     * the dash was gone for more than [DASH_GONE_MAX_MS] at that sign of life (the bike was switched off); the rider
     * took the app off the recent apps during this ride (an act of the rider's, the same that [com.reau.app.data.SystemClosedRide] does not tell as the system's
     * work: a maker may end the process right then, and that is not to be undone); [MAX_ATTEMPTS] resumes are already inside the last [WINDOW_MS].
     * Otherwise it resumes.
     */
    fun decide(mark: Mark?, enabled: Boolean, nowMs: Long): Verdict {
        val idleMs = mark?.let { nowMs - it.lastSeenMs } ?: 0L
        val dashGoneMs = mark?.takeIf { it.dashUpAtMs > 0 }?.let { it.lastSeenMs - it.dashUpAtMs } ?: 0L
        val recent = mark?.resumedAtMs.orEmpty().filter { nowMs - it in 0 until WINDOW_MS }
        return when {
            !enabled -> Verdict.Drop("the rider's switch for resuming is off")
            mark == null -> Verdict.Drop("no mark: the projection was not on, or the rider turned it off")
            idleMs < 0 -> Verdict.Drop("the clock is behind the mark")
            idleMs > MAX_IDLE_MS -> Verdict.Drop("the last sign of life was ${idleMs / MS_PER_MINUTE} min ago, over ${MAX_IDLE_MS / MS_PER_MINUTE}")
            dashGoneMs > DASH_GONE_MAX_MS ->
                Verdict.Drop("the dash had been gone for ${dashGoneMs / MS_PER_MINUTE} min at the last sign of life, over ${DASH_GONE_MAX_MS / MS_PER_MINUTE}")
            mark.removedAtMs > 0 -> Verdict.Drop("the rider removed the app from the recent apps during this ride")
            recent.size >= MAX_ATTEMPTS -> Verdict.Drop("already ${recent.size} resumes in the last ${WINDOW_MS / MS_PER_MINUTE} min")
            else -> Verdict.Resume(attempt = recent.size + 1, card = mark.card, resumedAtMs = recent + nowMs)
        }
    }

    /** What the service answers to a command: the system brings it back after a kill only while the projection is the rider's and running. */
    fun returnFor(resumable: Boolean): Int = if (resumable) Service.START_STICKY else Service.START_NOT_STICKY

    /**
     * The foreground types to try, richest first ([ForegroundTypes.ladder]). A [restart] (no intent: the service is started from the background by the
     * system) asks for the connected device alone: Android 14 and later throw from startForeground for the microphone and the location types of a service
     * started from the background (they are "while-in-use"), and REau holds no background location permission. So the resume never asks for them, whatever
     * [microphone] and [location] say.
     */
    fun typesFor(restart: Boolean, microphone: Boolean, location: Boolean, sdk: Int = Build.VERSION.SDK_INT): List<Int> =
        if (restart) ForegroundTypes.ladder(microphone = false, location = false, sdk = sdk) else ForegroundTypes.ladder(microphone, location, sdk)

    /** The log's lines of a foreground that Android accepted for a resume: the types, and what the projection does without the two it did not ask for. */
    fun foregroundNotes(accepted: Int): List<String> = listOf(
        "projection: foreground types accepted: ${ForegroundTypes.names(accepted)} (0x${Integer.toHexString(accepted)})",
        "projection: resumed in the foreground with the connected device type alone: no microphone for Gemini, no location for the dash's Wi-Fi list " +
            "(an empty list reads as unknown, not as the dash gone)",
    )

    /** The line of the ride log for a resume, written once the session of the log has begun; [note]: what else it says (the battery optimization, [exemptionNote]). */
    fun resumedLine(attempt: Int, note: String? = null): String =
        "resumed after the system ended the process (attempt $attempt of $MAX_ATTEMPTS" + (note?.let { "; $it" } ?: "") + ")"

    /** What the log says of the battery optimization: with the exemption a foreground start from the background is allowed; without it Android may refuse it. */
    fun exemptionNote(exempt: Boolean?): String = when (exempt) {
        true -> "battery optimization: REau is exempt"
        false -> "battery optimization: REau is NOT exempt"
        null -> "battery optimization: unknown"
    }

    /** The line for a restart that does not resume. */
    fun notResumedLine(why: String): String = "not resumed after the system ended the process: $why"
}
