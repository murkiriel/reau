package com.reau.app.data

import java.util.Locale

/**
 * A short summary of REau's own state that the system keeps for the process (`ActivityManager.setProcessStateSummary`, Android 11+, up to 128 bytes) and hands back
 * inside the `ApplicationExitInfo` of that process's death (26/11/2026, idea S3 of the study of the Xiaomi and realme). The `exit` line of the next opening then says what
 * the process knew about itself when it died, even if its last lines never reached the file and with no crossing with the last `device` look (which can be 5 s away).
 *
 * What it holds, in ASCII, as `REau1;p=1;d=1;o=1795712345;t=2;b=65;c=0`: `p` the projection on (1) or off (0), `d` the dash connected, `o` when the screen went off (epoch
 * seconds; `-` when it is on, `?` when not known), `t` the attempt of the resume after a death by the system (0 to 9, 0 for none; this build has no resume, so always 0), `b` the battery in percent in steps of 5
 * (`?` unknown) and `c` the charger. No place, no name, no number of the phone, no identifier: only these six facts. The text is plain ASCII so the reader (`mortes.py`)
 * and a person can read it as it stands.
 */
object ProcessStateSummary {
    /** The most bytes Android keeps in a process's summary (`ActivityManager.setProcessStateSummary` throws above it). */
    const val MAX_BYTES = 128

    private const val MAGIC = "REau1"
    private const val BATTERY_STEP = 5
    private const val MAX_PERCENT = 100
    private const val MAX_ATTEMPT = 9
    private const val MS_PER_S = 1000L
    private val SHAPE = Regex("""REau1;p=([01]);d=([01]);o=(-|\?|\d{1,12});t=(\d);b=(\d{1,3}|\?);c=([01?])""")

    /** What a look at the phone says, for [ProcessStateSummaryKeeper] to make a [State] of. */
    data class Look(
        val projecting: Boolean,
        val dashUp: Boolean,
        /** Whether the display is lit; null when the look could not read it. */
        val screenOn: Boolean?,
        val attempt: Int,
        val batteryPct: Int?,
        val charging: Boolean?,
    )

    /** The state as it is encoded: [screenOffAtSec] is the instant the screen went off, in epoch seconds, null when it is on or not known ([screenKnown] says which). */
    data class State(
        val look: Look,
        val screenOffAtSec: Long?,
        val screenKnown: Boolean,
    )

    /** [state] as the bytes handed to Android: ASCII, never over [MAX_BYTES]. */
    fun encode(state: State): ByteArray {
        val look = state.look
        val screen = when {
            !state.screenKnown -> "?"
            state.screenOffAtSec == null -> "-"
            else -> state.screenOffAtSec.coerceAtLeast(0L).toString()
        }
        val battery = look.batteryPct?.coerceIn(0, MAX_PERCENT)?.let { (it / BATTERY_STEP * BATTERY_STEP).toString() } ?: "?"
        val charger = when (look.charging) { true -> "1"; false -> "0"; null -> "?" }
        val text = "$MAGIC;p=${bit(look.projecting)};d=${bit(look.dashUp)};o=$screen;t=${look.attempt.coerceIn(0, MAX_ATTEMPT)};b=$battery;c=$charger"
        return text.toByteArray(Charsets.US_ASCII).copyOf(minOf(text.length, MAX_BYTES))
    }

    private fun bit(value: Boolean) = if (value) "1" else "0"

    /**
     * What [summary] (the bytes Android gave back as text, or null with none) says, for the `exit` line of a death at [deathMs]: `projecting=yes dash=up screen=off off-for=301s
     * attempt=2 battery=65% charging=no`. A summary that is not this format (another build, or none) gives null: the line says nothing of it rather than guess. The time the
     * screen was off is counted to [deathMs] (the instant of the screen is the one of the look that saw it go off, up to 5 s late).
     */
    fun describe(summary: String?, deathMs: Long): String? {
        val m = summary?.let { SHAPE.matchEntire(it) } ?: return null
        val g = m.groupValues
        val projecting = g[1]
        val dash = g[2]
        val screen = g[3]
        val attempt = g[4]
        val battery = g[5]
        val charger = g[6]
        val screenText = when (screen) {
            "-" -> "screen=on"
            "?" -> "screen=?"
            else -> String.format(Locale.US, "screen=off off-for=%ds", (deathMs / MS_PER_S - screen.toLong()).coerceAtLeast(0L))
        }
        return listOf(
            "projecting=" + if (projecting == "1") "yes" else "no",
            "dash=" + if (dash == "1") "up" else "down",
            screenText,
            "attempt=$attempt",
            "battery=" + if (battery == "?") "?" else "$battery%",
            "charging=" + when (charger) { "1" -> "yes"; "0" -> "no"; else -> "?" },
        ).joinToString(" ")
    }
}

/**
 * Gives [keep] the summary only when it changed since the last one it gave (a change of the state, never every look: the battery counts in steps of 5 %), and follows
 * when the screen went off. One per projection; the look at the phone feeds it ([ProcessStateSummary.Look]). After [ended] it takes no more looks: a look already queued on
 * the worker (the one the memory signal asks for) runs after the projection's end and would write `p=1` over the summary that says it is over (review of the plan 34, B1, 26/11/2026).
 */
class ProcessStateSummaryKeeper(private val keep: (ByteArray) -> Unit) {
    private var screenOffAtSec: Long? = null
    private var screenKnown = false
    private var last: String? = null
    private var lastLook: ProcessStateSummary.Look? = null
    private var over = false

    /** A look at the phone at wall-clock [nowMs]; ignored once the projection [ended]. */
    @Synchronized
    fun offer(nowMs: Long, look: ProcessStateSummary.Look) {
        if (over) return
        // A look that does not know the screen (null) changes nothing.
        look.screenOn?.let { on ->
            if (on) screenOffAtSec = null else if (screenOffAtSec == null) screenOffAtSec = nowMs / MS_PER_S
            screenKnown = true
        }
        lastLook = look
        give(ProcessStateSummary.State(look, screenOffAtSec, screenKnown))
    }

    /** The projection ended: the summary says so (the process may live on with the app open). */
    @Synchronized
    fun ended() {
        over = true
        val look = lastLook ?: return
        give(ProcessStateSummary.State(look.copy(projecting = false, dashUp = false, attempt = 0), screenOffAtSec, screenKnown))
    }

    private fun give(state: ProcessStateSummary.State) {
        val bytes = ProcessStateSummary.encode(state)
        val text = String(bytes, Charsets.US_ASCII)
        if (text == last) return
        last = text
        keep(bytes)
    }

    private companion object {
        const val MS_PER_S = 1000L
    }
}
