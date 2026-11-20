package com.reau.app.dash

import java.util.Locale

// The two signs that a session's sockets are on a Wi-Fi network that is gone, each of them a loss of the link (ride log of 14/11/2026, 19:41: the
// dash's Wi-Fi went for 9 s and came back as another Android network; the session was kept, the dash went on talking, the control packets
// "recovered" and every video packet failed with ENETUNREACH for 6 min 36 s, until the rider closed the projection). Pure, so the JVM tests cover them.

/**
 * The dash's Wi-Fi came up as another Android network than the one the session's sockets were bound to. A socket stays bound to its network for
 * good (`Network.bindSocket`), and a network that went never comes back under the same number: the video socket of such a session can only fail.
 * The session counts as lost and the next one opens its sockets on the new network.
 */
internal object NetworkSwitch {
    /** Whether [now], the network the Wi-Fi manager has up, is another than [opened], the session's; with either unknown there is nothing to say. */
    fun changed(opened: Any?, now: Any?): Boolean = opened != null && now != null && opened != now

    fun line(opened: Any?, now: Any?): String =
        "the dash's Wi-Fi is now network $now and this session's sockets are bound to network $opened, which is gone: " +
            "link lost, the session is redone on the new network"

    /** What the session fails with. */
    const val FAILURE = "The dash's Wi-Fi came back as another network — reconnecting"
}

/**
 * The safety net under [NetworkSwitch]: the video (RTP) failing at every send for longer than [LIMIT_MS] is a loss of the link, whatever else the
 * session sees. The dash's packets still arrive on a socket whose network went (the port is what delivers them) and the control packets go out again
 * as soon as the phone has any Wi-Fi with the dash's addresses (they are broadcasts), so neither the watch of the dash's silence nor "TX send
 * recovered" tells that the video has no way out.
 *
 * One object per socket. The video thread tells each send that failed ([failed]) or worked ([sent]); the session's 1 Hz watch asks [stalledMs].
 * The stall is measured at a send that failed: it takes a send (a probe of the suppressed channel) failing more than [LIMIT_MS] after the first
 * failure of the run, so a link that came back meanwhile and has not been probed yet is not taken as down, and a single send that works ends it.
 */
internal class RtpStall {
    @Volatile private var failingSinceMs = NOT_FAILING
    @Volatile private var failedForMs = NOT_FAILING

    /** A send failed at [nowMs]; [suppressed] says the channel is past its run of failures and only probing. */
    fun failed(nowMs: Long, suppressed: Boolean) {
        if (failingSinceMs == NOT_FAILING) failingSinceMs = nowMs
        if (suppressed) failedForMs = nowMs - failingSinceMs
    }

    /** A send worked: the run of failures is over. */
    fun sent() {
        failingSinceMs = NOT_FAILING
        failedForMs = NOT_FAILING
    }

    /** How long the video had been failing at its last failed send, when that is more than [limitMs]; else null. */
    fun stalledMs(limitMs: Long = LIMIT_MS): Long? = failedForMs.takeIf { it > limitMs }

    companion object {
        private const val NOT_FAILING = -1L

        /**
         * 5 s. A Wi-Fi network that went is asked for again after 4 s and takes some more to join (4.4 s in the log above), so it is never back
         * before this, and it comes back as another network: the session has to be redone anyway. A link that is still the same one is probed
         * every 20 packets, several times a second while riding, and the longest silence of a dash that was still there was 2.96 s
         * ([DashSession.RX_WATCHDOG_MS]): a failure that mends itself is over well before 5 s.
         */
        const val LIMIT_MS = 5_000L

        /** Whether the session is lost: only one that streams sends video at all. */
        fun fails(state: DashState, stalledMs: Long?): Boolean = state == DashState.STREAMING && stalledMs != null

        /** The line of the ride log; [rxAgoMs] is how long ago the dash last spoke (null: never). */
        fun line(stalledMs: Long, rxAgoMs: Long?): String =
            "RTP watchdog: every video send failed for ${seconds(stalledMs)} s " +
                "(last dash packet ${rxAgoMs?.let { "${seconds(it)} s ago" } ?: "never"}) → link lost"

        /** What the session fails with. */
        const val FAILURE = "Video stopped reaching the dash — connection lost"

        private fun seconds(ms: Long) = String.format(Locale.US, "%.1f", ms.coerceAtLeast(0) / 1000.0)
    }
}
