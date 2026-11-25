package com.reau.app.dash

import android.os.SystemClock

/**
 * When the phone's Wi-Fi last left a network (Android's own `wifi network lost` or the supplicant going to DISCONNECTED, both heard by
 * SystemEvents). Ride log of 24/11/2026, 07:34:15: the phone left another saved network at 15.004, the supplicant was DISCONNECTED at 15.304,
 * and the watch saw the dash in the scan at 15.321 and put the request out again at 15.354, 27 ms before Android started ASSOCIATING with it at
 * 15.381: the attempt was dropped by the new request, 0.7 s lost (4 to 4.7 s on 23/11 at 20:40:46 and 21:15:49, with the extra scan after the
 * abort). The guard over the join ([AssociationInFlight], [HoldWhileJoining]) holds only once the supplicant already says it is joining, and at that
 * moment it did not yet. A phone that has just left another network is about to join the dash's, so the watches leave their re-request alone for
 * [HOLD_MS] after it ([holds]); the look 0.5 s later decides again, and the 6 s of [DashJoin.HOLD_WHILE_JOINING_MS] still ends any hold.
 *
 * The clock is a parameter: elapsed realtime in the app, a counter in the tests. The stamp is a time, nothing else (no name, no address).
 */
internal class PhoneLeftNetwork(private val clock: () -> Long) {
    @Volatile private var atMs: Long? = null

    /** The phone's Wi-Fi left a network now. */
    fun left() {
        atMs = clock()
    }

    /** How long ago the phone left a network, or null when it has not since the process began. */
    fun sinceMs(): Long? = atMs?.let { (clock() - it).coerceAtLeast(0) }

    companion object {
        /** A re-request by a sighting waits this long after the phone left a network. */
        const val HOLD_MS = 1_000L

        /** The one in the app, fed by SystemEvents and read by the watches over the dash's Wi-Fi. */
        val shared = PhoneLeftNetwork { SystemClock.elapsedRealtime() }

        /** Whether a network left [sinceMs] ago (null: none) holds the re-request back: under [HOLD_MS] ago. */
        fun holds(sinceMs: Long?): Boolean = sinceMs != null && sinceMs < HOLD_MS

        /** The ride log's line for the first look that holds the re-request back. */
        fun line(sinceMs: Long): String = "holding the re-request: the phone left another network $sinceMs ms ago"
    }
}
