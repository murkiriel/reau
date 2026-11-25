package com.reau.app.dash

/**
 * The rider's notice for a dash whose Wi-Fi is up and which has gone quiet, again and again: the sessions end with no word from it while the
 * phone's network to it is still joined (ride log of 23/11/2026 08:05: 86 s of silence on a joined network, and the rider had no way to know that
 * switching the ignition off and on was the cure). One session like that is a hiccup, and the ordinary end of a ride (the bike off, its Wi-Fi gone) is not
 * one at all, so the notice comes at the [TELL_AT]th session in a row ([silentEnd]) and only when
 *  - the phone's network to the dash is still joined when the session ends (a Wi-Fi that went away is the ordinary ignition, [silentEnd]'s `wifiJoined`), and
 *  - the dash has spoken before in this projection (`dashSpoke`: a dash that is still starting up has not spoken yet, and is given the time).
 * A session that reaches STREAMING or a network that falls ends the run ([reset]). The notice comes once for a run (the rider is not told at every
 * session), and goes when the run ends. Plain counting with no clock: the callers say what they saw, so the tests need none.
 */
class SilentDash(private val tellAt: Int = TELL_AT) {
    /** What a silent end of a session asks of the rider's notice: nothing, or to tell (once for a run). */
    enum class Verdict { NONE, TELL }

    private var run = 0
    private var told = false

    /** The rider's notice: told (`true`) or taken away (`false`); the service sets it. Called when the verdict says so, and when a told run ends. */
    @Volatile var notice: ((Boolean) -> Unit)? = null

    /** How many silent sessions in a row there are now (for the ride log's line). */
    fun silentRun(): Int = synchronized(this) { run }

    /**
     * A session ended with no word from the dash: [wifiJoined] the phone's network to the dash was still up and [dashSpoke] the dash had spoken
     * before in this projection. Anything else is not a silent dash: the run starts over ([reset]). The notice is told through [notice] at the
     * [tellAt]th in a row, once.
     */
    fun silentEnd(wifiJoined: Boolean, dashSpoke: Boolean): Verdict {
        if (!wifiJoined || !dashSpoke) {
            reset()
            return Verdict.NONE
        }
        val tell = synchronized(this) {
            run++
            (run >= tellAt && !told).also { if (it) told = true }
        }
        if (tell) notice?.invoke(true)
        return if (tell) Verdict.TELL else Verdict.NONE
    }

    /** A session reached STREAMING, or the network fell, or the projection began or ended: the run starts over, and a notice told is taken away. */
    fun reset() {
        val wasTold = synchronized(this) {
            run = 0
            told.also { told = false }
        }
        if (wasTold) notice?.invoke(false)
    }

    companion object {
        /** Silent sessions in a row that make the notice. */
        const val TELL_AT = 3

        /** The one of the process: the dash session and the Wi-Fi manager feed it, the service sets its notice. */
        val shared = SilentDash()

        /** The ride log's line for a session that ended with the dash silent for [silentS] s and the phone's Wi-Fi to it still joined; [inARow]th such. */
        fun line(silentS: Long, inARow: Int): String =
            "the dash has been silent $silentS s while its Wi-Fi is still joined (session $inARow in a row; the rider is told at $TELL_AT)"
    }
}

/**
 * The rider's notice that another app holds the dash's port (UDP 2002): told **once for an episode** (the first time the dash reads as taken,
 * not at each of the tries 2, 4, 8, 16 and 30 s apart that follow), and taken away when REau connects, or when the projection ends. The tries
 * and the link states are the machine's; this only keeps whether the rider has been told. Plain: the caller says what the dash's link is.
 */
internal class TakenNotice {
    /** What the dash's link asks of the notice. */
    enum class Edge { NONE, SHOW, CLEAR }

    private var told = false

    /** The dash's link is now [taken] (another app holds the port) or [ready] (REau is connected); any other link leaves the notice as it is. */
    @Synchronized
    fun link(taken: Boolean, ready: Boolean): Edge = when {
        taken && !told -> Edge.SHOW.also { told = true }
        ready && told -> Edge.CLEAR.also { told = false }
        else -> Edge.NONE
    }

    /** The projection ended: whether a notice is up and must go. */
    @Synchronized
    fun end(): Boolean = told.also { told = false }
}
