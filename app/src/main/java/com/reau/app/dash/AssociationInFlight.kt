package com.reau.app.dash

/**
 * Whether the phone is in the middle of joining **the dash's** Wi-Fi, for the watches that put a request out again when the dash shows up in
 * the scan ([DashJoin.refileAfterLossHeld], [DashWifiManager]'s watch over a request). Ride log of 23/11/2026, 21:15:49 and 21:15:55: the dash
 * came back after a quick off and on of the ignition and Android was already joining it (the supplicant in ASSOCIATING, then ASSOCIATED and the
 * handshake); each of the two re-requests of the watch let go the request Android was answering, and Android dropped the attempt: 6.6 s lost
 * before the network came. The old guard ([HoldWhileJoining]) only held when the dash's network was already **up**, which comes a moment after.
 *
 * A look ([look]) is the supplicant's state and whether it is the dash it is joining; the watch looks at each of its ticks, and the join is "in
 * flight" for [WINDOW_MS] after the last look that saw it, since the states pass in tens of milliseconds and the tick may fall between two of them.
 * Another network (the phone's own saved Wi-Fi, which Android also tries when the dash goes away), a disconnected or scanning supplicant, and a
 * network that is already up (the old guard's) are not this. Time is given, not read, so a test sets the clock. No SSID or BSSID is kept.
 */
internal class AssociationInFlight(private val windowMs: Long = WINDOW_MS) {
    private var lastMs: Long? = null

    /** The state of the last look that saw the join (for the ride log), null before any. */
    var state: String? = null
        private set

    /** One look: the supplicant is in [state] and [ofTheDash] says whether the network it is joining is the dash's. */
    fun look(state: String?, ofTheDash: Boolean, nowMs: Long) {
        if (ofTheDash && counts(state)) {
            lastMs = nowMs
            this.state = state
        }
    }

    /** Whether a join was seen in the last [windowMs] and the dash's network is not up yet ([networkUp]: the old guard's business). */
    fun inFlight(nowMs: Long, networkUp: Boolean): Boolean {
        val at = lastMs ?: return false
        return !networkUp && nowMs - at <= windowMs
    }

    companion object {
        /** How long a look that saw the join keeps counting: the watch's own tick (0.5 s) three times over. */
        const val WINDOW_MS = 1_500L

        /** The supplicant's (and the network state's) words for a join under way: from the request to the address, the network not available yet. */
        private val IN_PROGRESS = setOf("CONNECTING", "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE", "COMPLETED")

        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val HIDDEN_BSSID = "02:00:00:" + "00:00:00"

        /** Whether [state] (a supplicant state's name) is a join under way; the cheap test before the name of the network is read. */
        fun counts(state: String?): Boolean = state in IN_PROGRESS

        /**
         * Whether the network the supplicant is joining ([ssid], quoted or not, and [bssid], as `WifiInfo` gives them) is the dash's: a readable
         * name decides ([pendingSsid], or its beginning with [pendingPrefix]); with the name hidden, the address is looked up among the
         * addresses the scan has for the dash ([dashBssids], asked only then). Neither readable: not the dash, as nothing is known.
         */
        fun ofTheDash(ssid: String?, bssid: String?, pendingSsid: String, pendingPrefix: Boolean, dashBssids: () -> Set<String>): Boolean {
            val name = ssid?.trim('"')?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
            val address = bssid?.takeIf { it.isNotBlank() && it != HIDDEN_BSSID }?.lowercase()
            return when {
                pendingSsid.isBlank() -> false
                name != null -> DashSsid.matchesPendingSsid(name, pendingSsid, pendingPrefix)
                else -> address != null && address in dashBssids()
            }
        }
    }
}
