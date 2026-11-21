package com.reau.app.dash

import com.reau.app.dash.DashSsid.maskSsid

/**
 * Android gave up on the request by itself, well before REau's own wait ran out: it reached the dash and was refused.
 * A dash stuck mid-ride was seen refusing every join for 11 minutes after 5-12 s of a 30 s wait.
 */
private fun quickRefusal(tookMs: Long, timeoutMs: Int): Boolean = tookMs < timeoutMs / 2

/**
 * The rules of a request for the dash's network: how long it waits, whether it is tried again, and why it was refused. Pure decision logic
 * kept out of the Android-framework-calling methods of [DashWifiManager] so it is unit-testable without a real
 * ConnectivityManager/WifiManager (DashWifiManagerHelpersTest, DashStuckTest, DashJoinTest).
 */
internal object JoinRules {
    /** Why a first request for the dash's network failed, as far as REau can tell (DashStuckTest). */
    enum class Refusal(private val words: Int) {
        NOT_FOUND(com.reau.app.R.string.dash_err_ssid_not_found),
        // The dash was on this bike network a moment ago and the scan no longer sees it. The rider is told the same
        // words as NOT_FOUND; only the log tells them apart.
        GONE_AFTER_JOIN(com.reau.app.R.string.dash_err_ssid_not_found),
        SEEN_NOT_JOINED(com.reau.app.R.string.dash_err_auth_failed),
        UNKNOWN(com.reau.app.R.string.dash_err_not_found_or_password),
        // Evaluated only in the "initial connect" branch of onUnavailable and shown on the screen. A dash that hangs in the middle of a ride (the
        // mid-session branch) is [DashStuck] / [StuckDashWatch], which also raises the rider's notification (StuckDashWatchTest).
        DASH_STUCK(com.reau.app.R.string.dash_err_dash_stuck),
        ;

        /**
         * The words of this refusal and the dash's name they carry, masked: they only reach the log (the
         * name ties a log to one bike).
         */
        internal fun message(ssid: String): Pair<Int, String> = words to maskSsid(ssid)
    }

    internal const val CONNECT_TIMEOUT = 30_000  // ms — Android shows system dialog within this

    /** A reconnect (we've connected once already this session) gets the shorter timeout — see
     * the comment on [RECONNECT_TIMEOUT] for why. The first approval of a network ([firstApproval]) gets time for
     * the rider's tap on Android's dialog (DashJoin). */
    internal fun timeoutForAttempt(everConnected: Boolean, firstApproval: Boolean = false): Int =
        DashJoin.timeoutMs(everConnected, firstApproval, RECONNECT_TIMEOUT, CONNECT_TIMEOUT)

    /** Mid-session drop (dash AP went missing after we'd already connected) keeps retrying;
     * a failed initial connect / declined dialog gives up. */
    internal fun shouldRetryOnUnavailable(everConnected: Boolean, wantConnected: Boolean): Boolean =
        everConnected && wantConnected

    /** Quick refusals in a row that make a dash with no join of its own yet count as stuck. */
    internal const val STUCK_AFTER = 3

    /**
     * A dash not in the scan is not there (GONE_AFTER_JOIN when its network had already worked in this projection, so
     * the log says it went away rather than never came). A dash that refuses quickly, after its network already worked in this
     * projection (same password) or [STUCK_AFTER] times in a row, has a stuck Wi-Fi, which only a bike power-off
     * clears. Otherwise, what the scan says, as before.
     */
    internal fun refusal(seenInScan: Boolean?, tookMs: Long, timeoutMs: Int, joinedBefore: Boolean, earlyInARow: Int): Refusal =
        when {
            seenInScan == false -> if (joinedBefore) Refusal.GONE_AFTER_JOIN else Refusal.NOT_FOUND
            quickRefusal(tookMs, timeoutMs) && (joinedBefore || earlyInARow >= STUCK_AFTER) -> Refusal.DASH_STUCK
            seenInScan == true -> Refusal.SEEN_NOT_JOINED
            else -> Refusal.UNKNOWN
        }

    /**
     * What the dash's request that Android let go (`NetworkCallback.onUnavailable`) comes to:
     * - [RetryMidSession]: the dash had worked in this projection and went away; the request is made again, as always.
     * - [AdoptExisting]: a first connection, but the phone **already has the dash's network up** (it joined by itself: a suggestion or a
     *   saved network). Ride of 19/11/2026, 01:26: the radio had just been turned on, the phone joined the dash 0.5 s before Android let REau's
     *   request go, and the quick refusal was called "the dash is stuck" 1.8 s into a join that had worked. The network is adopted by the same
     *   path as any network the phone joined by itself, and no refusal is declared.
     * - [Refused]: no network up; the verdict [JoinRules.refusal] gives, as it always did.
     */
    sealed class Unavailable {
        object RetryMidSession : Unavailable()
        object AdoptExisting : Unavailable()
        data class Refused(val refusal: Refusal) : Unavailable()
    }

    /**
     * The decision of the "unavailable" callback. [dashNetworkUp] (is the dash's network up in the phone now) and [seenInScan] are asked only when
     * the answer needs them: not at all in a reconnection, and the scan only when there is no network up to adopt. A dash network that is up is
     * adopted whatever the scan says and however quick the refusal was; a reconnection ([everConnected]) never adopts here (its next request
     * finds the network by itself, [DashWifiManager]).
     */
    internal fun onUnavailable(
        everConnected: Boolean,
        wantConnected: Boolean,
        dashNetworkUp: () -> Boolean,
        seenInScan: () -> Boolean?,
        attempt: Attempt,
    ): Unavailable = when {
        shouldRetryOnUnavailable(everConnected, wantConnected) -> Unavailable.RetryMidSession
        dashNetworkUp() -> Unavailable.AdoptExisting
        else -> Unavailable.Refused(refusal(seenInScan(), attempt.tookMs, attempt.timeoutMs, attempt.joinedBefore, attempt.earlyInARow))
    }

    /** What came of the adoption of a network the phone joined by itself ([adoption]). */
    enum class Adoption {
        /** The network was taken: the request that ended is answered, no refusal. */
        ADOPTED,

        /** Nothing to do with this callback any more: the manager is switched off or a newer request is out. No state is touched, no refusal is given. */
        STALE,

        /** The network went before it could be taken: the refusal stands, as it always did. */
        GONE,
    }

    /**
     * The adoption's outcome, in the order of the checks: a manager switched off ([wantConnected]) or a callback that is no longer the request in force
     * ([requestIsCurrent]) is [Adoption.STALE], whatever the network does, and [taken] is not asked; otherwise [taken] (it takes the network, true when it did)
     * says [Adoption.ADOPTED] or [Adoption.GONE]. Only [Adoption.GONE] gives the refusal: giving it for a stale callback would write ERROR and
     * `wantConnected = false` over the newer request, which would stay open in Android with nobody listening.
     */
    internal fun adoption(wantConnected: Boolean, requestIsCurrent: Boolean, taken: () -> Boolean): Adoption = when {
        !wantConnected || !requestIsCurrent -> Adoption.STALE
        taken() -> Adoption.ADOPTED
        else -> Adoption.GONE
    }

    /** What the join that ended says of itself, for the verdict of [refusal]: how long it took, the wait it was given, whether the dash had worked, and the quick refusals in a row. */
    internal data class Attempt(val tookMs: Long, val timeoutMs: Int, val joinedBefore: Boolean, val earlyInARow: Int)

    /** The quick refusals in a row, this one included; a request that ran its wait out starts the count over. */
    internal fun earlyInARow(previous: Int, tookMs: Long, timeoutMs: Int): Int =
        if (quickRefusal(tookMs, timeoutMs)) previous + 1 else 0

    /**
     * A reconnect scheduled by a link loss goes out only if no request went out after it was scheduled: otherwise
     * it can go out just after the projection's own request (1.4 s was seen) and cancel it half-way.
     */
    internal fun reconnectDue(wantConnected: Boolean, scheduledAt: Int, latestRequest: Int): Boolean =
        wantConnected && scheduledAt == latestRequest
}
