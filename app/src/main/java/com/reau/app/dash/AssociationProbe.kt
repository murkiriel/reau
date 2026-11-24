package com.reau.app.dash

import android.net.wifi.WifiManager
import android.os.SystemClock

/**
 * The thin shell over Android for [AssociationInFlight]: reads the phone's main Wi-Fi connection (the supplicant's state and the name of the
 * network it is joining, which `ACCESS_FINE_LOCATION` and the location switch allow, as the Wi-Fi list the sighting comes from already needs)
 * and passes them on. Nothing is asked of Android that starts work (no scan), and no name or address is written anywhere.
 */
internal class AssociationProbe(private val wifi: WifiManager, private val scan: DashScan, private val flight: AssociationInFlight = AssociationInFlight()) {
    /** The state of the join last seen, for the ride log. */
    val state: String? get() = flight.state

    /** One look at the phone's Wi-Fi, for the dash named [pendingSsid] ([pendingPrefix]: only its beginning). Never throws: what Android refuses to say is no look. */
    @Suppress("DEPRECATION") // the only way to ask the supplicant's state and the network it is joining without a callback
    fun look(pendingSsid: String, pendingPrefix: Boolean, nowMs: Long) {
        val info = runCatching { wifi.connectionInfo }.getOrNull() ?: return
        val state = info.supplicantState?.name
        if (!AssociationInFlight.counts(state)) return
        val ofTheDash = AssociationInFlight.ofTheDash(info.ssid, info.bssid, pendingSsid, pendingPrefix) { scan.dashBssids(pendingSsid, pendingPrefix) }
        flight.look(state, ofTheDash, nowMs)
    }

    /** See [AssociationInFlight.inFlight]: the phone is joining the dash's Wi-Fi, which is not up yet ([joined]: it is, and the other guard has it). */
    fun joining(joined: Boolean, nowMs: Long): Boolean = flight.inFlight(nowMs, networkUp = joined)

    /** Whether the last [holdsNow] held for the join (and not for the network already up), for the line that says it. */
    var byTheJoin = false
        private set

    /** The question of the watch over a loss: the dash's network is up already ([joined]) or being joined; remembers which of the two. */
    fun holdsNow(joined: Boolean, nowMs: Long): Boolean {
        byTheJoin = joining(joined, nowMs)
        return joined || byTheJoin
    }

    /** The ride log's line for the first time a request is left alone after the dash showed up [sinceLostMs] ms after the loss (request #[request]). */
    fun lossLine(sinceLostMs: Long, request: Int, maskedSsid: String): String {
        val seen = "the dash showed up in the scan $sinceLostMs ms after the Wi-Fi was lost"
        return if (byTheJoin) "$seen, and ${line("in request #$request", maskedSsid)}"
        else "$seen and its Wi-Fi is already joined in request #$request: leaving it to be answered"
    }

    /** The ride log's line for a request left alone under the join ([where] says the request), with the dash's name masked by the caller. */
    fun line(where: String, maskedSsid: String): String = "the phone is joining the dash's Wi-Fi '$maskedSsid' ($state) $where: leaving it to be answered"
}

/**
 * The hold of one watch over a request: the dash's Wi-Fi already joined ([joined]), or being joined by the phone ([probe]), while the request is
 * still unanswered. The first is the network the request itself holds up, and the second the join Android is making for it; putting the request
 * out again would drop either ([DashJoin.holdWhileJoining], with its limit of 6 s). The first time of a hold is said to the ride log ([say]).
 */
internal class JoinHold(
    private val joined: () -> Boolean,
    private val probe: AssociationProbe,
    private val say: (String) -> Unit,
    private val maskedSsid: () -> String,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val clock = HoldWhileJoining()

    fun holds(request: Int): Boolean {
        val now = nowMs()
        val up = joined()
        val verdict = clock.holds(up || probe.joining(up, now), now)
        if (verdict.began) {
            say(
                if (up) "the dash's Wi-Fi is already joined in request #$request: leaving it to be answered"
                else probe.line("in request #$request", maskedSsid()),
            )
        }
        return verdict.holds
    }
}
