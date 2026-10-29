package com.reau.app.dash

/**
 * On which Wi-Fi link the dash's own 10 s mark (09 06 55) has been seen. The mark is the dash's clock since the link came up, not the
 * session's: a session made again on the same link (after a greeting the dash ignored) never gets it again. A new link is another
 * object, and what was seen on the old one is forgotten. Not thread-safe: the session's coroutines take turns.
 */
internal class LinkNotice {
    private var link: Any? = null

    /** The mark was seen on [onLink]. */
    fun seenOn(onLink: Any) {
        link = onLink
    }

    /** Whether the mark was seen on [onLink] (and on no other link since). */
    fun knownOn(onLink: Any): Boolean = link == onLink
}

/**
 * When the dash is ready to take REau's greeting, told by the dash's own heartbeat counter. The dash answers the Wi-Fi and sends its
 * telemetry some 25 s before its own application accepts the negotiation: in the logs of 19 and 20/10/2026 four of ten entries ended in
 * `Auth timed out`, the dash having sent no key, and the counter of its first heartbeat (`0101` = "K1G" and a counter that goes up by one
 * a second) was 17, 19, 12 and 22 in the four; in eight of the nine good entries it was 28 to 39 (the ninth, 13, was the exception).
 * Before it reaches [READY_COUNT] the greeting is sent again every [WAIT_RESEND_MS], and the 15 s to authenticate count from the moment the
 * counter reaches it (or from the entry, whichever is later), with a ceiling of [CAP_MS]. A dash whose counter was not seen keeps the old
 * rules. Pure, so the JVM tests cover it.
 *
 * **The counter wraps at 256.** It is sent in two bytes, but the high one stays 0 (a log of 20/10/2026: `…E9`, then `…07` thirty seconds later,
 * and `0xD5` → `0x11` in 60 s), so it measures the dash's age only up to 255 s. A dash that has been on for 256 s or more is read as a
 * young one when REau first sees it; it costs greetings every 2 s and a longer wait (a dash that takes badly to repeated greetings would not be told from a young one), and nothing here can tell the two apart.
 */
internal object DashReadiness {
    /** The counter from which the dash was seen to accept the greeting. */
    const val READY_COUNT = 26

    /** How often the greeting goes again while the dash is not ready. */
    const val WAIT_RESEND_MS = 2_000L

    /** The most greetings sent again while waiting for it (CAP_MS / WAIT_RESEND_MS, less the first). */
    const val MAX_WAIT_RESENDS = 14

    /** The time to authenticate, counted from the entry or from the dash being ready, the later. */
    const val AUTH_WINDOW_MS = 15_000L

    /** The most the wait for the dash can stretch the time to authenticate, from the entry. */
    const val CAP_MS = 30_000L

    private const val MS_PER_S = 1_000L
    private val PREFIX = byteArrayOf('K'.code.toByte(), '1'.code.toByte(), 'G'.code.toByte())

    /** The counter of a `0101` heartbeat value ("K1G", then the counter in two bytes), or null for anything else. */
    fun counterOf(value: ByteArray): Int? {
        if (value.size < PREFIX.size + 2) return null
        if (PREFIX.indices.any { value[it] != PREFIX[it] }) return null
        return ((value[PREFIX.size].toInt() and 0xFF) shl 8) or (value[PREFIX.size + 1].toInt() and 0xFF)
    }

    /** The dash's counter at [nowMs], from the [first] one seen at [firstAtMs] (it goes up by one a second). */
    fun counterAt(first: Int, firstAtMs: Long, nowMs: Long): Int = first + ((nowMs - firstAtMs).coerceAtLeast(0) / MS_PER_S).toInt()

    /** Whether the dash is ready at [nowMs]; one whose counter was not seen ([first] null) counts as ready (the old rules). */
    fun ready(first: Int?, firstAtMs: Long, nowMs: Long): Boolean = first == null || counterAt(first, firstAtMs, nowMs) >= READY_COUNT

    /** When the time to authenticate ends: [AUTH_WINDOW_MS] from the later of the entry ([burstStartMs]) and the dash being ready. */
    fun deadlineMs(burstStartMs: Long, first: Int?, firstAtMs: Long): Long {
        if (first == null) return burstStartMs + AUTH_WINDOW_MS
        val readyAt = firstAtMs + (READY_COUNT - first).coerceAtLeast(0) * MS_PER_S
        return minOf(maxOf(burstStartMs, readyAt) + AUTH_WINDOW_MS, burstStartMs + CAP_MS)
    }

    /**
     * When the greeting goes (again) during one entry, for a dash whose first heartbeat counter is [first] seen at [firstAtMs]. While the
     * dash is not ready: every [WAIT_RESEND_MS], and **once at the moment it becomes ready** (the greeting of 2 s before went to a dash that was
     * not listening; the first live test of this rule ran out of greetings exactly then). Once it is ready: the old pace, a greeting after
     * [DashSession.KEY_PATIENCE_MS] up to [DashSession.MAX_BURST_RESENDS] times, counted from the moment it became ready. The caller tells
     * [sent] each greeting that goes. Not thread-safe: one coroutine drives it.
     */
    class Greeting(private val patientEveryMs: Long, private val patientMax: Int) {
        private var wasNotReady = false
        private var waiting = 0
        private var patient = 0

        /** How many greetings went again in all. */
        val total: Int get() = waiting + patient

        /** How many of them went at the patient pace (once the dash was ready), for "n of the most". */
        val patientSent: Int get() = patient

        /** Whether the last greeting that went was the one at the moment the dash became ready. */
        var readyMoment = false
            private set

        /** Whether the dash is not ready at [nowMs] (a log line says so). */
        fun notReady(first: Int?, firstAtMs: Long, nowMs: Long): Boolean = !ready(first, firstAtMs, nowMs)

        /** Whether a greeting goes now; [sinceLastBurstMs] since the last one. */
        fun due(first: Int?, firstAtMs: Long, nowMs: Long, sinceLastBurstMs: Long, keySeen: Boolean): Boolean {
            if (keySeen) return false.also { wasNotReady = notReady(first, firstAtMs, nowMs) }
            val waitingNow = notReady(first, firstAtMs, nowMs)
            val becameReady = wasNotReady && !waitingNow
            wasNotReady = waitingNow
            readyMoment = false
            return when {
                waitingNow -> resendDue(sinceLastBurstMs, keySeen = false, waiting)
                becameReady -> true.also { readyMoment = true }
                else -> patient < patientMax && sinceLastBurstMs >= patientEveryMs
            }.also { if (it) { if (waitingNow) waiting++ else patient++ } }
        }
    }

    /** The line of the dash's age at its first heartbeat, always in the ride log (it used to show only in the detailed log). */
    fun ageLine(counter: Int): String {
        val base = "dash heartbeat age $counter s at the first packet"
        return if (counter < READY_COUNT) "$base (the counter wraps at 256: it may also be a dash on for 256 s or more)" else base
    }

    /** The line of the key arriving: [afterMs] after the first greeting, after [resends] greetings sent again. */
    fun keyLine(afterMs: Long, resends: Int): String {
        val times = if (resends == 1) "resend" else "resends"
        return String.format(java.util.Locale.US, "key arrived %.1f s after the first greeting, after %d %s", afterMs / MS_PER_S.toDouble(), resends, times)
    }

    /** Whether the greeting goes again while the dash is not ready: no key yet, [sinceLastBurstMs] since the last, and not too many. */
    fun resendDue(sinceLastBurstMs: Long, keySeen: Boolean, resends: Int): Boolean =
        !keySeen && resends < MAX_WAIT_RESENDS && sinceLastBurstMs >= WAIT_RESEND_MS
}
