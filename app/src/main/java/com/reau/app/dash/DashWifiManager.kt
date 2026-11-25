// The bike network coming and going is written to the `system` line.
package com.reau.app.dash

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.reau.app.dash.DashSsid.maskSsid
import com.reau.app.dash.DashSsid.matchesPendingSsid
import com.reau.app.dash.DashSsid.normalizeSsid
import com.reau.app.dash.JoinRules.CONNECT_TIMEOUT
import com.reau.app.dash.JoinRules.Refusal
import com.reau.app.dash.JoinRules.earlyInARow
import com.reau.app.dash.JoinRules.reconnectDue
import com.reau.app.dash.JoinRules.refusal
import com.reau.app.dash.JoinRules.timeoutForAttempt
import com.reau.app.util.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val MICROS_PER_MILLI = 1_000L
private const val MS_PER_SECOND = 1_000L

/**
 * Programmatically connects to the dash's WiFi hotspot using
 * WifiNetworkSpecifier + ConnectivityManager.requestNetwork().
 *
 * The dash's network is not made the process's default (no bindProcessToNetwork: that took routing, geocoding and
 * link-sharing off the phone's internet). Each dash socket is bound to it on its own (Network.bindSocket), so the packets
 * reach 192.168.1.1 while everything else keeps the phone's other network (cellular).
 *
 * Auto-reconnects on link loss until disconnect() is called.
 */
class DashWifiManager(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Which network the rider already let REau join; null (a scan-only manager, a test) assumes nothing about it. */
    private val joinMemory: JoinMemory? = null,
) {
    companion object {
        private const val TAG              = "DashWifiManager"
        /** How many refused networks are remembered (they are few: one per refusal, cleared when the rider switches off). */
        private const val MAX_REFUSED      = 8
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // The readers of Android that keep nothing of the request: the Wi-Fi list, and the Wi-Fi the phone joined by itself.
    private val scan = DashScan(context, wifi, cm)
    private val joinedWifi = JoinedWifi(cm, wifi)

    // What the watches know of the phone's own join of the dash's Wi-Fi: a request is not put out again under a join Android is making.
    private val joins = Joins()

    /**
     * Is the phone's Wi‑Fi radio on? WifiNetworkSpecifier can only associate to the dash when
     * Wi‑Fi is enabled, and on Android 10+ the app can't enable it itself — so we check up front
     * and have the UI send the rider to the Wi‑Fi settings panel rather than silently timing out.
     * (Doesn't matter which network Wi‑Fi is currently joined to — the specifier connects the dash
     * as a separate per-app network.)
     */
    fun isWifiEnabled(): Boolean = wifi.isWifiEnabled

    private val _state = MutableStateFlow(WifiState())
    val state = _state.asStateFlow()

    /**
     * The dash WiFi network, exposed so the dash UDP sockets can be bound to it
     * INDIVIDUALLY (Network.bindSocket). We deliberately do NOT bindProcessToNetwork:
     * that routed the WHOLE app through the dash's no-internet WiFi, so routing,
     * geocoding and shared-link resolution all failed while connected ("can't share").
     */
    @Volatile var network: Network? = null
        private set

    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    // The callback is still registered with Android: false once the system dropped it itself (onUnavailable), when unregistering
    // it again only makes Android log "NetworkCallback was already unregistered".
    @Volatile private var callbackRegistered = false
    // The current request was answered by the system (the network came, even if it was lost since): what "let go unanswered" means.
    @Volatile private var requestAnswered = false
    @Volatile private var reconnectJob: Job? = null
    // True once we've successfully connected this session. Lets us tell a MID-RIDE drop (keep
    // retrying — the dash AP may have hung and will return after a power-cycle) apart from a failed
    // INITIAL connect / user-declined dialog (give up, don't spam).
    private var everConnected = false
    // Read by the system's callback threads and the coroutines, written by the rider's connect() and disconnect().
    @Volatile private var wantConnected = false
    // Held by disconnect() and by every request: a request that was due when the rider switched off cannot go out after the
    // switch-off and be left asking for the bike's Wi-Fi until the process dies (audit of 20/10/2026).
    private val requestLock = Any()
    private var pendingSsid = ""
    private var pendingPassword = ""
    private var pendingPrefix = false
    private var resolvedSsid: String? = null
    private var requestStartMs = 0L
    // What the request would wait without the stretch of a first approval: the yardstick of a "quick" refusal.
    private var requestTimeoutMs = CONNECT_TIMEOUT
    // The last request asked for a network the rider had not approved yet (the system's dialog is expected).
    private var requestFirstApproval = false
    // When this manager last acted on getting a lost network back (the loss, the refusal, its own request).
    @Volatile private var reconnectActedMs = 0L
    // Requests made so far; a scheduled reconnect remembers the count it was scheduled at (reconnectDue).
    private var requests = 0
    // The dash's network worked at least once since this manager was made (one projection), and the quick refusals
    // in a row since it last worked (refusal, earlyInARow).
    private var joinedOnce = false
    private var quickRefusals = 0
    // When the first request of this manager went out, and when the last one was let go without the system answering it:
    // only for the log, to see a request that the system leaves "searching" while REau re-issues it.
    private var firstRequestMs = 0L
    private var releasedUnansweredMs = 0L
    // What the phone knew of the network that is up (the line of its loss), and the channel of the connection that was lost,
    // to say on which channel the dash came back (LinkLoss).
    private val notes = NetNotes()
    // How many times a first connection's request was put out again because the dash was in the scan and had not joined (DashJoin.refileSightedAndSlow).
    @Volatile private var sightedRefiles = 0
    // When the bike network was lost and has not come back since (0: it is there), for the pause between requests (ReconnectPace); said once.
    private val absence = AbsenceClock()
    // The network in use is one the phone had joined by itself (a suggestion, a saved one), not the answer to REau's own request.
    @Volatile private var joinedByPhone = false
    // The tries that found the dash's network unavailable since it was last there (for the line that is not written at every try).
    private var unavailableInARow = 0
    // The loss being got back (microseconds since boot, as the scan's stamps; 0 = none), the watch over it and its last refiling.
    @Volatile private var lostAtUs = 0L
    @Volatile private var lossWatch: Job? = null
    private var lastRefileMs = 0L
    // Sockets refused by Android (EPERM) in a row, and the networks it was refused on: they are not used again as "already connected".
    private val bindRefusals = BindRefusals()
    // Which refusal the projection's "try now" after [RefusalRetry.AFTER_MS] belongs to; another refusal or a switch-off makes it stale.
    private var refusalSerial = 0
    private val refusedNetworks = RefusedNetworks<Network>(MAX_REFUSED)
    // The pause after a refusal that is not asked again at once (BindRefusals), added to the pace of the requests; 0 when there is none.
    @Volatile private var holdOffMs = 0L
    // The dash that is in the scan and refuses every join (its Wi-Fi hung), told to the rider once for an absence (DashStuck).
    private val stuck = StuckDashWatch()

    /**
     * When we connect by prefix (any RE_* dash), the exact SSID is only known once the
     * link is up. This callback reports it so the caller can persist it for direct
     * reconnects next time. Null/blank if it can't be resolved.
     */
    var onSsidResolved: ((String) -> Unit)? = null

    /** Told each time the bike's network is got (after [state] says CONNECTED); the projection tries a waiting dash at once. */
    @Volatile var onNetworkAvailable: (() -> Unit)? = null

    /**
     * Told `true` when the dash is judged hung (it is in the scan with a good signal and refuses every join, [DashStuck]) and `false` when
     * that is over (the dash is back, or the rider switched off): the service shows or takes away the rider's notice.
     */
    @Volatile var onDashStuck: ((Boolean) -> Unit)? = null

    /**
     * Told `true` when Android refused the sockets of the dash's network [BindRefusals.TELL_AT] times in a row (EPERM) and `false` when that is
     * over (a bind worked, or the rider switched off): the service shows or takes away the rider's notice.
     */
    var onBindRefused: ((Boolean) -> Unit)? by bindRefusals::onTold

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Request a WiFi network. Shows a one-time system confirmation dialog.
     *
     * @param prefixMatch when true, [ssid] is treated as a PREFIX and Android offers any
     *   matching network (e.g. every `RE_*` dash) — this is what makes the app work on
     *   any rider's dash without hardcoding their SSID. When false, exact-match.
     */
    fun connect(ssid: String, password: String = "", prefixMatch: Boolean = false) {
        wantConnected    = true
        everConnected    = false   // fresh user-initiated attempt
        com.reau.app.data.RideDiagnostics.log("wifi", NetworkBlock.defaultLine(context))
        notes.forget()
        unavailableInARow = 0
        sightedRefiles = 0
        holdOffMs = 0L
        absence.back()
        DashHeard.forget()
        SilentDash.shared.reset() // a fresh attempt: the run of silent sessions starts over
        pendingSsid      = ssid
        pendingPassword  = password
        pendingPrefix    = prefixMatch
        resolvedSsid     = null
        requestNetwork()
    }

    /**
     * Find a dash SSID from the latest WiFi scan results (any network whose name starts
     * with [prefix], e.g. "RE_"). We need the EXACT SSID string up front because the dash
     * validates it inside the encrypted auth handshake — and Android 13+ redacts the SSID
     * of the connected network, so we can't read it back after connecting.
     */
    fun findDashSsid(prefix: String): String? = scan.find(prefix)

    /**
     * Whether the last Wi-Fi scan has [ssid] (or, with [prefix], a network starting with it) seen recently
     * ([DashJoin.SIGHTING_FRESH_MS]: the scan list keeps a dash that was switched off for minutes). False when it does not or
     * when the scan cannot be read or is empty: the caller only acts on a sighting.
     */
    fun seenInLastScan(ssid: String, prefix: Boolean): Boolean = scan.seenRecently(ssid, prefix)

    /**
     * True while this manager is getting a lost bike network back by itself, recently enough to be trusted
     * (the rule is the top-level [reconnectUnderWay]). [WifiDashLinkUp] then waits for that instead of asking anew.
     */
    fun reconnectUnderWay(nowMs: Long = MonotonicClock.nowMs()): Boolean =
        reconnectUnderWay(wantConnected, everConnected, _state.value.status, nowMs - reconnectActedMs, currentPauseMs(nowMs))

    /** The pause between requests now: the short one, or the long one once the dash has been away for 3 minutes ([ReconnectPace]). */
    private fun currentPauseMs(nowMs: Long): Long = maxOf(absence.pauseMs(nowMs), holdOffMs)

    fun disconnect() {
        Log.i(TAG, "Disconnect requested")
        // Asked twice by the projection's stop and release; only a request with something to let go is told.
        if (disconnectHasSomethingToLetGo(wantConnected, networkCallback != null, _state.value.status)) {
            com.reau.app.data.RideDiagnostics.log("wifi", "disconnect requested")
        }
        synchronized(requestLock) {
            wantConnected = false
            refusalSerial++
            reconnectJob?.cancel()
            lossWatch?.cancel()
            lostAtUs = 0L
            holdOffMs = 0L
            release()
            refusedNetworks.clear()
            _state.value = WifiState()
        }
        bindRefusals.bound()
        // The notice goes with the service, but the verdict is kept: only the ignition clears a hung dash (StuckMemory, ride of 21/11/2026).
        clearStuck(forgetVerdict = false)
    }

    // ── Internal ──────────────────────────────────────────────────────────

    /** Asks for the bike's network, unless the rider has switched off since this was due (the check and the request are one step). */
    private fun requestNetwork() = synchronized(requestLock) {
        if (wantConnected) {
            if (wifi.isWifiEnabled) askForTheNetwork() else radioOffNoRequest()
        }
    }

    /**
     * The phone's Wi-Fi radio is off: Android cannot grant the request, which would only wait out its timeout, and the reconnection loop would repeat it. Nothing
     * is asked, and the loop ends here; the projection asks anew (a fresh [connect]) when the radio is on, which is why [everConnected] goes back to false: the
     * reconnection is not "under way" any more for [reconnectUnderWay].
     */
    private fun radioOffNoRequest() {
        everConnected = false
        com.reau.app.data.RideDiagnostics.log("wifi", "the phone's Wi-Fi is off: no request for the dash's network, it is asked for again when the radio is on")
    }

    /**
     * Some OEM builds (seen on Android 11) suppress the WifiNetworkSpecifier system dialog
     * entirely when the phone is ALREADY joined to the exact network being requested —
     * requestNetwork() then just times out instead of returning it. Reusing an already-
     * connected match sidesteps that: no new dialog, no request, straight to CONNECTED. False when there is none.
     */
    private fun useAlreadyConnectedNetwork(): Boolean {
        val (activeNetwork, activeSsid) = findAlreadyConnectedDashNetwork() ?: return false
        if (!joinedWifi.usable(activeNetwork)) {
            com.reau.app.data.RideDiagnostics.warn("wifi", JoinedNetwork.deadLine(activeNetwork, "when it was found"))
            return false
        }
        network = activeNetwork
        // The watcher first: it hears of the network's loss from here on. A network that went before it was registered is told by the check
        // right after, and one that goes in the next moments by [verifyJoinedLater] (31/10/2026: a network of 47 ms of life was taken as
        // connected for good, and no loss was ever heard of).
        watchJoined(activeNetwork)
        if (!joinedWifi.usable(activeNetwork)) {
            dropWatcher()
            network = null
            com.reau.app.data.RideDiagnostics.warn("wifi", JoinedNetwork.deadLine(activeNetwork, "while it was being taken"))
            return false
        }
        resolvedSsid = activeSsid
        requestAnswered = true
        TimeToPicture.marks.networkUp(MonotonicClock.nowMs())
        joinedByPhone = true
        lostAtUs = 0L
        absence.back()
        holdOffMs = 0L
        clearStuck()
        everConnected = true
        joinedOnce = true
        quickRefusals = 0
        // Not remembered as approved: a network the phone joined by itself (a suggestion, a saved one) says nothing
        // about the dialog of a request for it.
        Log.i(TAG, "Using already-connected matching WiFi '${maskSsid(activeSsid)}'")
        val caps = cm.getNetworkCapabilities(activeNetwork)
        com.reau.app.data.RideDiagnostics.log(
            "wifi", "using already-connected WiFi '${maskSsid(activeSsid)}' (network $activeNetwork, ${networkOwner(caps)})",
        )
        noteNetwork(caps)
        onSsidResolved?.invoke(activeSsid)
        _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = activeSsid)
        // As for a network the request got: a dash dropped and waiting out its retry is tried now (ride of 24/11/2026, 21:01: the network the
        // phone joined by itself was taken and the projection, never told, sat out its minute; the image came back 102.9 s after the dash was on).
        com.reau.app.service.SystemEvents.log(com.reau.app.service.SystemEvents.bikeNetwork(true))
        onNetworkAvailable?.invoke()
        verifyJoinedLater(activeNetwork)
        return true
    }

    /** A moment after [joined] was taken, sees that Android still has it; if not, it is a loss like any other ([bikeLost]). */
    private fun verifyJoinedLater(joined: Network) {
        scope.launch {
            delay(JoinedNetwork.CHECK_AFTER_MS)
            if (wantConnected && network == joined && !joinedWifi.usable(joined)) {
                com.reau.app.data.RideDiagnostics.warn("wifi", JoinedNetwork.deadLine(joined, "${JoinedNetwork.CHECK_AFTER_MS} ms after it was taken"))
                bikeLost()
            }
        }
    }

    /**
     * The request [dead] ended "unavailable" in a first connection while the phone already has the dash's network up (it joined by itself: a
     * suggestion or a saved network): that network is taken, by [useAlreadyConnectedNetwork] as any other the phone joined by itself, instead of
     * declaring a refusal (ride of 19/11/2026, 01:26: "the dash is stuck" 1.8 s into a join that had worked). One step under [requestLock]:
     * nothing is taken for a manager switched off, or whose request is no longer [dead] (a newer one is out). The request that ended is let
     * go for good, so none is left open (Android has already dropped it; the call only makes sure) and the only callback left is the watcher
     * of the network taken. The answer is [JoinRules.Adoption]: ADOPTED; STALE (switched off, or a newer request is out: nothing is touched, one line is logged
     * and the caller does nothing more); GONE (the network went in between): the caller gives the refusal it always gave.
     * A socket that Android then refuses (EPERM) goes the way it always went: [bindResult].
     */
    private fun adoptTheNetworkThePhoneJoined(dead: ConnectivityManager.NetworkCallback, tookMs: Long, quickInARow: Int): JoinRules.Adoption {
        synchronized(requestLock) {
            val outcome = JoinRules.adoption(wantConnected, requestIsCurrent = networkCallback === dead) {
                runCatching { cm.unregisterNetworkCallback(dead) }
                callbackRegistered = false
                networkCallback = null
                val line = "unavailable after ${tookMs}ms (initial connect) — " +
                    "dash network already up (joined by the phone itself): adopting it instead of a refusal; " +
                    "quick=$quickInARow joined=$joinedOnce firstApproval=$requestFirstApproval approvedBefore=${approvedBefore()}; ${lastScanEvidence()}"
                Log.i(TAG, "WiFi unavailable (${tookMs}ms) — the dash's network is already up: adopting it")
                com.reau.app.data.RideDiagnostics.log("wifi", line)
                useAlreadyConnectedNetwork()
            }
            when (outcome) {
                JoinRules.Adoption.ADOPTED -> Unit
                JoinRules.Adoption.STALE -> com.reau.app.data.RideDiagnostics.log(
                    "wifi", "unavailable of a request that is no longer the one in force (switched off or a newer one is out): nothing adopted or given up",
                )
                JoinRules.Adoption.GONE -> com.reau.app.data.RideDiagnostics.warn(
                    "wifi", "the dash network that was up went before it could be adopted: the refusal stands as it always did",
                )
            }
            return outcome
        }
    }

    @Suppress("TooGenericExceptionCaught") // a failed request is logged and shown as the ERROR state
    private fun askForTheNetwork() {
        release()
        requestAnswered = false
        requests++
        requestStartMs = MonotonicClock.nowMs()
        TimeToPicture.marks.requested(requestStartMs)
        reconnectActedMs = requestStartMs
        val timeout = chooseTimeout()
        Log.i(TAG, "Requesting WiFi: '${maskSsid(pendingSsid)}' " +
            "(${if (pendingPrefix) "prefix" else "exact"}, password=${if (pendingPassword.isBlank()) "none" else "set"}, timeout=${timeout}ms" +
            "${if (requestFirstApproval) ", first approval" else ""})")
        logRequestContext(timeout)
        _state.value = WifiState(status = WifiConnStatus.REQUESTING, ssid = pendingSsid)

        if (useAlreadyConnectedNetwork()) return

        val request = dashNetworkRequest() ?: return

        // Android 12+: FLAG_INCLUDE_LOCATION_INFO, so the joined network's name is not redacted.
        val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BikeCallback(DashName.callbackFlags()) else BikeCallback()

        networkCallback = cb
        // Set before the request: its callback can end (onUnavailable) before this thread gets to write it after.
        callbackRegistered = true
        try {
            cm.requestNetwork(request, cb, timeout)
            startAndroid11SsidPolling()
            watchForTheDash(requests)
        } catch (e: Exception) {
            callbackRegistered = false
            Log.e(TAG, "requestNetwork threw: ${e.message}", e)
            com.reau.app.data.RideDiagnostics.error("wifi", "requestNetwork threw: ${e.javaClass.simpleName}: ${e.message}")
            _state.value = WifiState(
                status = WifiConnStatus.ERROR,
                ssid   = pendingSsid,
                error  = "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    /**
     * While request [request] goes unanswered with the dash not in the scan, looks at the scan every [DashJoin.WATCH_PERIOD_MS] and puts
     * the request out again once the dash shows up (the rule is [DashJoin.refileOnSighting]). The loop ends by itself when
     * another request goes out, the request is answered, or the rider switches off.
     */
    private fun watchForTheDash(request: Int) {
        if (everConnected || requestFirstApproval) return
        // Already in the scan when the request went out: the system should join in a few seconds, and if it has not in
        // [DashJoin.REFILE_SIGHTED_MS] the request is put out again (a request left 30 s was followed by one that joined in 5.4 s).
        val seenAtRequest = seenInLastScan(pendingSsid, pendingPrefix)
        val begun = SystemClock.elapsedRealtime()
        val hold = joins.hold()
        scope.launch {
            while (stillRequesting(request)) {
                joins.tick()
                val waited = SystemClock.elapsedRealtime() - begun
                if (!stillRequesting(request)) return@launch
                val seenNow = seenInLastScan(pendingSsid, pendingPrefix)
                val sighted = !seenAtRequest && DashJoin.refileOnSighting(requestFirstApproval, everConnected, false, seenNow, waited)
                val slow = DashJoin.refileSightedAndSlow(requestFirstApproval, everConnected, seenAtRequest, waited, sightedRefiles)
                if (sighted || slow) {
                    if (hold.holds(request)) continue
                    if (slow) sightedRefiles++
                    com.reau.app.data.RideDiagnostics.log("wifi", "the dash ${refileWhy(slow)} $waited ms into request #$request: asking again")
                    requestNetwork()
                    return@launch
                }
            }
        }
    }

    /** The request numbered [request] is the one out, the rider still wants the dash and the system has not answered it. */
    private fun stillRequesting(request: Int) = requests == request && wantConnected && _state.value.status == WifiConnStatus.REQUESTING

    private fun refileWhy(slow: Boolean) = if (slow) "was in the scan when it went out and has not joined" else "showed up in the scan"

    /**
     * The watches' look at the phone's own join of the dash's Wi-Fi ([AssociationProbe]), one for the manager: [tick] is the wait between two
     * looks at the scan, ending in a look at the join, and [hold] the guard of a watch over a request ([JoinHold]), which leaves the request
     * alone when the dash's network is up already or being joined.
     */
    private inner class Joins {
        val probe = AssociationProbe(wifi, scan)

        suspend fun tick() {
            delay(DashJoin.WATCH_PERIOD_MS)
            probe.look(pendingSsid, pendingPrefix, SystemClock.elapsedRealtime())
        }

        fun hold() = JoinHold(
            joined = { findAlreadyConnectedDashNetwork() != null }, probe = probe,
            say = { com.reau.app.data.RideDiagnostics.log("wifi", it) }, maskedSsid = { maskSsid(pendingSsid) },
        )
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val scheduledAt = requests
        val pause = currentPauseMs(MonotonicClock.nowMs())
        if (absence.backoffStarted(MonotonicClock.nowMs())) {
            val minutes = ReconnectPace.BACKOFF_AFTER_MS / 60_000
            val cycle = (RECONNECT_TIMEOUT + pause) / 1_000
            com.reau.app.data.RideDiagnostics.log(
                "wifi", "the dash has been away for $minutes min: a request every $cycle s from now; a sighting in the scan brings it back at once",
            )
        }
        reconnectJob = scope.launch {
            delay(pause)
            // The back off of a refused network is one pause (BindRefusals): the next one, if the request ends unanswered, is the usual one.
            holdOffMs = 0L
            if (reconnectDue(wantConnected, scheduledAt, requests)) requestNetwork()
        }
    }

    /**
     * Log only, for a request the system's dialog leaves "searching": which request this is and how long REau has been at
     * it, how long since the last one was let go unanswered, whether the screen is on and unlocked (the system's dialog
     * needs it) and what the scan says of the dash, with the age of that sighting.
     */
    @SuppressLint("MissingPermission")
    private fun logRequestContext(timeout: Int) {
        if (firstRequestMs == 0L) firstRequestMs = requestStartMs
        val now = SystemClock.elapsedRealtime()
        val sinceFirst = (MonotonicClock.nowMs() - firstRequestMs) / 1_000
        val gap = if (releasedUnansweredMs == 0L) "first" else "${now - releasedUnansweredMs} ms after the last was let go unanswered"
        val line = "requestNetwork '${maskSsid(pendingSsid)}' everConnected=$everConnected timeout=${timeout}ms; " +
            "request #$requests (${sinceFirst}s since the first, $gap), screen ${screenWords()}, ${lastScanEvidence()}, " +
            "approvedBefore=${approvedBefore()}"
        Log.i(TAG, line)
        com.reau.app.data.RideDiagnostics.log("wifi", line)
    }

    /** Log only: `interactive=true locked=false`, the screen's state as the system's join dialog needs it (on and unlocked). */
    private fun screenWords(): String = screenWordsOf(context)

    /** Log only: what the last Wi-Fi scan says of the dash, and how old that sighting is. */
    private fun lastScanEvidence(): String = scan.evidence(pendingSsid, pendingPrefix, network)

    /** How long the request about to go out waits: more for a network the rider has not approved yet (DashJoin). */
    private fun chooseTimeout(): Int {
        requestFirstApproval = !everConnected && needsApproval()
        requestTimeoutMs = timeoutForAttempt(everConnected)
        return timeoutForAttempt(everConnected, requestFirstApproval)
    }

    /** The request about to go out is the first for a network the rider has not approved (the system's dialog is expected). */
    private fun needsApproval(): Boolean = runCatching {
        DashJoin.needsApproval(joinMemory?.joinedSsid, pendingSsid, pendingPrefix)
    }.getOrDefault(false)

    /** Log only: whether the system had already been told to let REau join this network ("unknown" without a memory). */
    private fun approvedBefore(): String = if (joinMemory == null) "unknown" else (!needsApproval()).toString()

    /** The network was joined: remembered, so that the next request for it waits as usual (a keystore failure costs nothing). */
    private fun rememberJoined(name: String) {
        val memory = joinMemory ?: return
        if (!DashJoin.worthKeeping(name, pendingSsid, pendingPrefix)) return
        runCatching { if (memory.joinedSsid != name) memory.joinedSsid = name }
            .onFailure { com.reau.app.data.RideDiagnostics.warn("wifi", "could not remember the joined network: ${it.javaClass.simpleName}") }
    }

    private fun release() {
        if (networkCallback != null) {
            val status = _state.value.status
            com.reau.app.data.RideDiagnostics.log(
                "wifi", releaseLine(requests, MonotonicClock.nowMs() - requestStartMs, status, requestAnswered),
            )
            // A request let go while the system had not answered it: the next one starts the system's dialog over.
            if (!requestAnswered) releasedUnansweredMs = SystemClock.elapsedRealtime()
        }
        if (callbackRegistered) networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callbackRegistered = false
        networkCallback = null
        network = null
    }

    // ── Android <12 SSID-redaction fallback ─────────────────────────────────
    // On some Android 11 / early-12 OEM builds, NetworkCallback.onCapabilitiesChanged never
    // delivers a readable WifiInfo.transportInfo for a WifiNetworkSpecifier-requested network —
    // the connection itself succeeds (onAvailable fires) but the SSID we need for the K1G
    // handshake never resolves (seen on real Android 11
    // hardware). Below API 31 (S), poll the legacy WifiManager.connectionInfo (deprecated but
    // still populated) a few times as a fallback; API 31+ already gets the SSID reliably via
    // onCapabilitiesChanged and doesn't need this.

    private fun startAndroid11SsidPolling() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) return
        val request = requests
        scope.launch {
            delay(5_000)
            repeat(6) {
                // Not for a request that was let go, nor after the rider switched off: it would put CONNECTED back on a disconnected manager.
                if (!wantConnected || requests != request) return@launch
                if (_state.value.status == WifiConnStatus.CONNECTED && resolvedSsid != null) return@launch
                findAlreadyConnectedDashNetwork()?.let { (activeNetwork, activeSsid) ->
                    network = activeNetwork
                    resolvedSsid = activeSsid
                    requestAnswered = true
                    Log.i(TAG, "Android 11 SSID fallback resolved '${maskSsid(activeSsid)}'")
                    com.reau.app.data.RideDiagnostics.log("wifi", "Android 11 SSID fallback resolved '${maskSsid(activeSsid)}'")
                    onSsidResolved?.invoke(activeSsid)
                    _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = activeSsid)
                    return@launch
                }
                delay(2_000)
            }
        }
    }

    /** The currently-associated WiFi network, IF its SSID matches [pendingSsid]/[pendingPrefix] —
     * lets [requestNetwork] reuse a connection the system already made instead of opening a new,
     * possibly-redundant request. */
    private fun findAlreadyConnectedDashNetwork(): Pair<Network, String>? = joinedWifi.find(pendingSsid, pendingPrefix, refusedNetworks::has)

    /** The bike Wi-Fi request's callback; on Android 12+ built with [DashName.callbackFlags] (see the call site). */
    private inner class BikeCallback : ConnectivityManager.NetworkCallback {
        constructor() : super()

        @RequiresApi(Build.VERSION_CODES.S)
        constructor(flags: Int) : super(flags)

        override fun onAvailable(network: Network) {
            // A late callback of a request that was let go (the rider switched off) must not bring the manager back.
            if (!wantConnected) return
            this@DashWifiManager.network = network
            requestAnswered = true
            TimeToPicture.marks.networkUp(MonotonicClock.nowMs())
            joinedByPhone = false
            lostAtUs = 0L
            absence.back()
            holdOffMs = 0L
            clearStuck()
            unavailableInARow = 0
            val tookMs = MonotonicClock.nowMs() - requestStartMs
            everConnected = true
            joinedOnce = true
            quickRefusals = 0
            reconnectJob?.cancel()
            noteNetwork(cm.getNetworkCapabilities(network))
            // SSID is read in onCapabilitiesChanged (transportInfo isn't populated here
            // yet on Android 13+). Try once anyway, else fall back to the pending value.
            val resolved = joinedWifi.ssidOf(network).takeIf { it.isNotBlank() } ?: pendingSsid
            rememberJoined(resolved)
            Log.i(TAG, "WiFi connected ✓  ssid='${maskSsid(resolved)}' network=$network (${tookMs}ms)")
            com.reau.app.data.RideDiagnostics.log(
                "wifi", "connected '${maskSsid(resolved)}' in ${tookMs}ms (network $network, ${networkOwner(cm.getNetworkCapabilities(network))})",
            )
            com.reau.app.service.SystemEvents.log(com.reau.app.service.SystemEvents.bikeNetwork(true))
            if (resolved.isNotBlank() && resolved != pendingSsid) onSsidResolved?.invoke(resolved)
            _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = resolved)
            onNetworkAvailable?.invoke()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!wantConnected) return
            noteNetwork(caps)
            resolveSsidFrom(network, caps)
        }

        override fun onUnavailable() {
            // Android has dropped the callback itself, wanted or not.
            callbackRegistered = false
            if (!wantConnected) return
            this@DashWifiManager.network = null
            val tookMs = MonotonicClock.nowMs() - requestStartMs
            var seenNow: Boolean? = null
            val early = earlyInARow(quickRefusals, tookMs, requestTimeoutMs)
            val outcome = JoinRules.onUnavailable(
                everConnected, wantConnected,
                dashNetworkUp = { findAlreadyConnectedDashNetwork() != null },
                seenInScan = { scan.verdict(pendingSsid, pendingPrefix).also { seenNow = it } },
                JoinRules.Attempt(tookMs, requestTimeoutMs, joinedOnce, early),
            )
            when {
                outcome == JoinRules.Unavailable.RetryMidSession -> retryAfterUnavailable(tookMs)
                // The phone already has the dash's network up (it joined by itself): that is the answer to the request, not a refusal.
                outcome == JoinRules.Unavailable.AdoptExisting &&
                    adoptTheNetworkThePhoneJoined(this, tookMs, early) != JoinRules.Adoption.GONE -> Unit
                else -> giveUpAfterUnavailable(outcome, seenNow, tookMs, early)
            }
        }

        override fun onLosing(network: Network, maxMsToLive: Int) {
            if (wantConnected) com.reau.app.data.RideDiagnostics.warn("wifi", LinkLoss.losingLine(maxMsToLive))
        }

        override fun onLost(network: Network) = bikeLost()
    }

    /**
     * Mid-ride drop where the dash AP went missing (we HAD connected): keep retrying so a dash that's hung — and revived by a bike power-cycle — auto-rejoins with no manual
     * step. The ViewModel's reconnect-giveup timer still bounds this if it never returns.
     */
    private fun retryAfterUnavailable(tookMs: Long) {
        Log.w(TAG, "WiFi unavailable mid-session (${tookMs}ms) — dash AP missing, retrying in ${RECONNECT_DELAY}ms")
        // The first of an absence, then every fifth with the count: a long absence wrote a trio of lines at each try.
        if (unavailableLogDue(++unavailableInARow)) {
            com.reau.app.data.RideDiagnostics.warn(
                "wifi", "unavailable after ${tookMs}ms (mid-session, #$unavailableInARow of this absence) — retrying in " +
                    "${RECONNECT_DELAY}ms; ${lastScanEvidence()}, approvedBefore=${approvedBefore()}",
            )
        }
        _state.value = WifiState(
            status = WifiConnStatus.REQUESTING,
            ssid   = pendingSsid,
            error  = context.getString(com.reau.app.R.string.dash_err_wifi_lost),
        )
        reconnectActedMs = MonotonicClock.nowMs()
        noteFailedTry()
        scheduleReconnect()
    }

    /** The first connection's request ended without the network: the refusal that [outcome] gives (or the one from the scan, after an adoption that did not hold), and the end of the attempt. */
    private fun giveUpAfterUnavailable(outcome: JoinRules.Unavailable, seenNow: Boolean?, tookMs: Long, early: Int) {
        // Failed INITIAL connect / user declined the dialog → give up. onUnavailable()
        // alone can't tell WHY (Android collapses "SSID not in range" and "auth/password
        // rejected" into the same callback) — but findDashSsid() already proved a fresh
        // scan can see the dash's SSID, so reuse that same lookup here to disambiguate the
        // message instead of leaving the rider guessing (a real gap seen in a log:
        // several consecutive failures with no way to tell if the dash was just
        // out of range or the saved password had gone stale).
        // A quick refusal after the network worked, or several in a row, is a stuck dash (DashStuckTest).
        val seenInScan = seenNow ?: scan.verdict(pendingSsid, pendingPrefix)
        quickRefusals = early
        // After an adoption that did not hold (the network went in between), the verdict is the one it always was.
        val ruled = (outcome as? JoinRules.Unavailable.Refused)?.refusal
            ?: refusal(seenInScan, tookMs, requestTimeoutMs, joinedOnce, quickRefusals)
        val refusal = stuckAtFirstTry(ruled)
        val message = refusal.message(pendingSsid).let { (text, name) -> context.getString(text, name) }
        Log.w(TAG, "WiFi unavailable (${tookMs}ms) — seenInScan=$seenInScan refusal=$refusal")
        com.reau.app.data.RideDiagnostics.warn(
            "wifi", "unavailable after ${tookMs}ms (initial connect) — giving up, seenInScan=$seenInScan " +
                "quick=$quickRefusals joined=$joinedOnce refusal=$refusal firstApproval=$requestFirstApproval " +
                "approvedBefore=${approvedBefore()}; ${lastScanEvidence()}",
        )
        _state.value = WifiState(
            status = WifiConnStatus.ERROR,
            ssid   = pendingSsid,
            error  = message,
            dashVisible = refusal == Refusal.DASH_STUCK || refusal == Refusal.SEEN_NOT_JOINED,
        )
        wantConnected = false
    }

    /**
     * Canonical place to read the connected SSID — REQUIRED for auth, since the dash validates the SSID inside the encrypted
     * handshake (DashAuth). Without the real SSID (prefix-discovery), every auth is rejected.
     */
    private fun resolveSsidFrom(network: Network, caps: NetworkCapabilities) {
        val info = caps.transportInfo as? WifiInfo ?: return
        val ssid = normalizeSsid(info.ssid)
        if (ssid.isBlank() || ssid == resolvedSsid) return
        resolvedSsid = ssid
        rememberJoined(ssid)
        this.network = network
        Log.i(TAG, "Resolved dash SSID: '${maskSsid(ssid)}'")
        com.reau.app.data.RideDiagnostics.log("wifi", "resolved SSID via onCapabilitiesChanged: '${maskSsid(ssid)}'")
        onSsidResolved?.invoke(ssid)
        _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = ssid)
    }

    /**
     * Whether the network came from a request with a specifier. A non-null specifier says yes; a null one is not "no" (Android may
     * leave it out of a callback that has no privilege to see it), so it says no only for a network the phone joined by itself
     * ([joinedByPhone]) and is unknown otherwise, and below API 30, which does not say.
     */
    private fun hasSpecifier(caps: NetworkCapabilities): Boolean? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> null
        caps.networkSpecifier != null -> true
        joinedByPhone -> false
        else -> null
    }

    /**
     * Keeps what Android says of the network that is up ([NetNotes]); the first time the channel is known after a loss, says whether
     * the dash came back on it. [caps] null (Android has no snapshot yet) changes nothing.
     */
    private fun noteNetwork(caps: NetworkCapabilities?) {
        notes.note(caps?.let(::capsRead), MonotonicClock.nowMs())?.let { com.reau.app.data.RideDiagnostics.log("wifi", it) }
    }

    /** What Android says of the network in [caps], as it is given to [NetNotes]. */
    private fun capsRead(caps: NetworkCapabilities): CapsRead {
        val info = caps.transportInfo as? WifiInfo
        return CapsRead(
            mhz = info?.frequency,
            rssiDbm = info?.rssi,
            specifier = hasSpecifier(caps),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            notRestricted = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
            apFingerprint = NetNotes.apFingerprint(info?.bssid), // compared in memory only: the address is never logged
        )
    }

    /**
     * The request for the dash's network (its name or prefix, its password, no internet asked), or null when it cannot be built: a
     * password or a name Android refuses, or a blank name, is logged and shown as the ERROR state, never a crash.
     */
    private fun dashNetworkRequest(): NetworkRequest? {
        val specBuilder = WifiNetworkSpecifier.Builder()
        if (pendingPassword.isNotBlank()) {
            // The builder throws for a passphrase it cannot take (not ASCII, as a pasted non-breaking space is);
            // uncaught, that killed the worker thread and the app. Reported like any other failed request.
            try {
                specBuilder.setWpa2Passphrase(pendingPassword)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "the dash password was refused: ${e.message}", e)
                com.reau.app.data.RideDiagnostics.error("wifi", passphraseRefusedLine(e))
                _state.value = WifiState(status = WifiConnStatus.ERROR, ssid = pendingSsid, error = "${e.javaClass.simpleName}: ${e.message}")
                return null
            }
        }

        // The name (or its prefix) and both builds go through the guard: a blank or refused name is an ERROR state, not a crash.
        return JoinRules.requestOrRefusal(pendingSsid.isBlank()) {
            if (pendingPrefix) specBuilder.setSsidPattern(PatternMatcher(pendingSsid, PatternMatcher.PATTERN_PREFIX))
            else specBuilder.setSsid(pendingSsid)
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // The dash hotspot has no WAN — it never gets NET_CAPABILITY_INTERNET. Requesting
                // it anyway (the default) leaves this network's UID exemption from Restricted
                // Networking Mode / per-app firewall rules improperly registered by netd, since the
                // request and the actual network never agree on capabilities — that's what produced
                // "Binding socket to network N failed: EPERM" on a device with Restricted Networking
                // Mode on, even with no VPN routing involved. Local-only WifiNetworkSpecifier
                // requests are documented to need this removed for exactly this reason.
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specBuilder.build())
                .build()
        }.getOrElse { e ->
            Log.e(TAG, "the dash network request could not be built: ${e.javaClass.simpleName}")
            com.reau.app.data.RideDiagnostics.error("wifi", "the dash network request could not be built: ${e.javaClass.simpleName}")
            // Only the exception's class: Android's message can carry the network's name, which the log never holds.
            _state.value = WifiState(status = WifiConnStatus.ERROR, ssid = pendingSsid, error = e.javaClass.simpleName)
            null
        }
    }

    /** The log line for a passphrase Android refused: whatever Android says, the password itself is not in it. */
    private fun passphraseRefusedLine(e: IllegalArgumentException): String =
        "the dash password was refused by Android: ${e.javaClass.simpleName}: ${e.message?.replace(pendingPassword, "…")}"

    /** The bike network went: forgotten, and asked for again while the rider still wants the dash. */
    private fun bikeLost() {
        if (!wantConnected) return
        Log.w(TAG, "WiFi link lost — reconnecting in ${RECONNECT_DELAY}ms")
        val now = MonotonicClock.nowMs()
        absence.lost(now)
        ImageGap.clock.lost(now, DashHeard.agoMs(now))
        stuck.lost(now, dashSpoke = DashHeard.agoMs(now) != null)
        SilentDash.shared.reset() // the network fell: a dash that is gone is not a silent one
        val screenOn = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive
        com.reau.app.data.RideDiagnostics.warn(
            "wifi", LinkLoss.lostLine(notes.facts, now, DashHeard.agoMs(now), screenOn, RECONNECT_DELAY),
        )
        // What the phone itself says of its Wi-Fi and networks at this moment (the system's own lines are collected by adb, not by the app).
        com.reau.app.data.RideDiagnostics.log("wifi", PhoneSide.now(context))
        DashHeard.trail?.report("the Wi-Fi was lost", now)?.forEach { com.reau.app.data.RideDiagnostics.log("wifi", it) }
        // What was going out in the seconds before the silence: RTP per second, the biggest burst, the last keyframe and command.
        DashHeard.tail?.reportOnce("the Wi-Fi was lost", notes.facts?.let { "on this network for ${(now - it.upAtMs).coerceAtLeast(0) / MS_PER_SECOND} s" })
            ?.let { com.reau.app.data.RideDiagnostics.log("wifi", it) }
        // And the detail of the last 15 s: each frame, each command's code and size (never what it said); a log never takes the projection down.
        runCatching { DashHeard.fine?.reportOnce("the Wi-Fi was lost")?.forEach { com.reau.app.data.RideDiagnostics.log("wifi", it) } }
        // The next connection of this projection is compared with the channel this one had (kept if it is not known now).
        notes.lost()
        com.reau.app.service.SystemEvents.log(com.reau.app.service.SystemEvents.bikeNetwork(false))
        network = null
        _state.value = WifiState(
            status = WifiConnStatus.REQUESTING,
            ssid   = pendingSsid,
            error  = context.getString(com.reau.app.R.string.dash_err_link_lost),
        )
        reconnectActedMs = MonotonicClock.nowMs()
        if (wantConnected) {
            scheduleReconnect()
            watchAfterLoss()
        }
    }

    /**
     * While the dash's network stays lost, looks at the scan every [DashJoin.WATCH_PERIOD_MS] and asks for the network at once when the
     * dash shows up newer than the loss, cutting the 4 s pause or a request already out ([DashJoin.refileAfterLoss]). One watch
     * for a loss; it ends when the network is back or the rider switches off.
     */
    private fun watchAfterLoss() {
        lostAtUs = SystemClock.elapsedRealtime() * MICROS_PER_MILLI
        lastRefileMs = 0L
        if (lossWatch?.isActive == true) return
        lossWatch = scope.launch {
            // The join of the dash's Wi-Fi is held for its own few seconds in each loss (the watch lasts as long as the loss does).
            val hold = HoldWhileJoining()
            while (stillLost()) {
                joins.tick()
                if (stillLost() && refileForSighting(hold)) requestNetwork()
            }
        }
    }

    /** The rider still wants the dash and the network lost has not come back. */
    private fun stillLost() = wantConnected && lostAtUs != 0L && network == null

    /**
     * The decision of [watchAfterLoss] for this moment, with the line that tells it. A request is not put out again while the dash's Wi-Fi
     * is already joined and waiting to be answered, or the phone is in the middle of joining it ([DashJoin.refileAfterLossHeld]): letting the
     * request go takes that network down, or drops the join Android is making.
     */
    private fun refileForSighting(hold: HoldWhileJoining): Boolean {
        val nowUs = SystemClock.elapsedRealtime() * MICROS_PER_MILLI
        val nowMs = MonotonicClock.nowMs()
        val pause = reconnectJob?.isActive == true
        val sinceLostMs = (nowUs - lostAtUs) / MICROS_PER_MILLI
        val sightingUs = scan.newestSightingUs(pendingSsid, pendingPrefix)
        // How long ago the phone left another network (SystemEvents): a join of the dash's Wi-Fi is about to begin, so the request is not let go yet.
        val leftMs = PhoneLeftNetwork.shared.sinceMs()
        val decision = DashJoin.refileAfterLossHeld(
            requestFirstApproval, lostAtUs, sightingUs, nowUs,
            requestAgeMs = nowMs - requestStartMs, inPause = pause,
            sinceLastRefileMs = if (lastRefileMs == 0L) null else nowMs - lastRefileMs,
            hold = hold, nowMs = SystemClock.elapsedRealtime(), phoneLeftMs = { leftMs },
            joiningNow = { joins.probe.holdsNow(findAlreadyConnectedDashNetwork() != null, SystemClock.elapsedRealtime()) },
        )
        when (decision) {
            DashJoin.LossRefile.NO, DashJoin.LossRefile.HELD -> Unit
            DashJoin.LossRefile.HELD_FIRST_LEAVING -> com.reau.app.data.RideDiagnostics.log("wifi", PhoneLeftNetwork.line(leftMs ?: 0L))
            DashJoin.LossRefile.HELD_FIRST -> com.reau.app.data.RideDiagnostics.log("wifi", joins.probe.lossLine(sinceLostMs, requests, maskSsid(pendingSsid)))
            DashJoin.LossRefile.REFILE -> {
                lastRefileMs = nowMs
                reconnectJob?.cancel()
                // Log only: what the scan and the phone said at the look that asks again (the dash's result age, the loss and the screen).
                com.reau.app.data.RideDiagnostics.log("wifi", ScanLookLog.refileLine(sightingUs, nowUs, sinceLostMs, requests, screenWords()))
                val cut = if (pause) " (the pause is cut short)" else ""
                com.reau.app.data.RideDiagnostics.log("wifi", "the dash showed up in the scan $sinceLostMs ms after the Wi-Fi was lost: asking again$cut")
                // A request let go before its time, with the dash in the scan, is a try that did not get it (a request that ended already counted).
                if (!pause) noteFailedTry()
            }
        }
        return decision == DashJoin.LossRefile.REFILE
    }

    // ── A hung dash, and a network Android refuses ─────────────────────────

    /**
     * A try for the dash's network ended without it (the request ran out, or was let go before its time). With the dash in the scan at a good
     * signal, enough of them in a row mean its Wi-Fi hung ([StuckDashWatch]): the ride log says so once, and the rider is told to switch the
     * ignition off and on.
     */
    private fun noteFailedTry() {
        val look = scan.stuckLook(pendingSsid, pendingPrefix)
        judgeStuck(look.seen, look.sighting)
    }

    /**
     * The verdict of a failed try ([sighting] of the dash in [seen]), from [StuckDashWatch] or, at the first try of a start, from [StuckMemory]
     * (ride of 21/11/2026: the rider switched the projection off and on three times after the notice, and each start needed the whole count
     * again). Said to the ride log and to the rider once, and remembered for the starts that follow. Null when there is none.
     */
    private fun judgeStuck(seen: List<ScanSeen>?, sighting: DashStuck.Sighting): DashStuck.Verdict? {
        val nowMs = MonotonicClock.nowMs()
        val since = StuckMemory.sinceVerdictMs(pendingSsid, pendingPrefix, sighting, nowMs)
        val verdict = stuck.failedTry(nowMs, sighting, remembered = since != null) ?: return null
        val rssi = seen.orEmpty().filter { matchesPendingSsid(it.ssid, pendingSsid, pendingPrefix) }.maxOfOrNull { it.level }
        com.reau.app.data.RideDiagnostics.warn(
            "wifi",
            if (verdict.remembered) DashStuck.rememberedLine(since ?: 0L, rssi) else DashStuck.line(verdict.absentMs, verdict.tries, rssi),
        )
        StuckMemory.remember(pendingSsid, pendingPrefix, nowMs)
        onDashStuck?.invoke(true)
        return verdict
    }

    /**
     * The initial connect's [ruled] refusal with the memory of a hung dash applied: a first try that failed with the dash in the scan at a good
     * signal, while [StuckMemory] holds a verdict of it, is [Refusal.DASH_STUCK] at once and tells the rider (the quick-refusal rule alone raises
     * no notification and needs three refusals). A [Refusal.DASH_STUCK] ruled by that count is remembered, so the next start is at once. A try
     * with the dash gone from the scan makes [StuckMemory] forget (the ignition was cycled).
     */
    private fun stuckAtFirstTry(ruled: Refusal): Refusal {
        val (seen, sighting) = scan.stuckLook(pendingSsid, pendingPrefix)
        val verdict = judgeStuck(seen, sighting)
        // The later tries of the same start (the rider already told) keep the words of a hung dash on the screen while the memory holds.
        val held = verdict?.remembered == true ||
            StuckMemory.sinceVerdictMs(pendingSsid, pendingPrefix, sighting, MonotonicClock.nowMs()) != null
        if (held && ruled != Refusal.NOT_FOUND && ruled != Refusal.GONE_AFTER_JOIN) return Refusal.DASH_STUCK
        if (ruled == Refusal.DASH_STUCK) StuckMemory.remember(pendingSsid, pendingPrefix, MonotonicClock.nowMs())
        return ruled
    }

    /**
     * The absence is over (the dash is back, or the rider switched off): the rider's notice goes if it was shown. [forgetVerdict]: the dash is
     * really back, so [StuckMemory] lets go too; the manual disconnect keeps it.
     */
    private fun clearStuck(forgetVerdict: Boolean = true) {
        if (forgetVerdict) StuckMemory.forget()
        if (stuck.back()) {
            com.reau.app.data.RideDiagnostics.log("wifi", "the dash is back (or REau was switched off): the notice of the hung dash is taken away")
            onDashStuck?.invoke(false)
        }
    }

    /**
     * How binding the session's sockets to [bound] went ([DashSession.onBindResult]). A refusal ([refused], EPERM) means that network is no
     * good: it is remembered so it is not taken as "already connected" again, its request and watcher are let go, and the network is asked for
     * again at once the first time; if the next one is refused too, after [BindRefusals.BACKOFF_BASE_MS] and more at each one in a row (the
     * session's own retry in between finds the manager asking, [reconnectUnderWay]). Ride log of 31/10/2026: the same dead network was handed
     * to the session every 30 s for 4.5 minutes. A bind that worked starts the count over.
     */
    fun bindResult(bound: Network?, refused: Boolean) {
        if (!refused) {
            bindRefusals.bound()
            return
        }
        synchronized(requestLock) {
            if (!wantConnected) return
            val current = network
            if (bound != null && current != null && bound != current) {
                com.reau.app.data.RideDiagnostics.log("wifi", "a socket was refused on network $bound, but the network in use is $current: left as it is")
                return
            }
            val dead = bound ?: current
            // Whose it was, said before the network is let go (the phone's own join is not REau's request going wrong).
            val phoneJoined = current != null && joinedByPhone
            val owner = networkOwner()
            val verdict = bindRefusals.refused(dead, phoneJoined)
            com.reau.app.data.RideDiagnostics.warn("wifi", verdict.line(dead, owner))
            NetworkBlock.vpnRefusalNote(NetworkBlock.vpnHolds(context))?.let { com.reau.app.data.RideDiagnostics.log("wifi", it) }
            val serial = ++refusalSerial
            dead?.let(refusedNetworks::remember)
            release()
            holdOffMs = if (verdict.step == BindRefusals.Step.BACK_OFF) verdict.backoffMs else 0L
            _state.value = WifiState(
                status = WifiConnStatus.REQUESTING,
                ssid   = pendingSsid,
                error  = context.getString(com.reau.app.R.string.dash_err_link_lost),
            )
            reconnectActedMs = MonotonicClock.nowMs()
            if (verdict.step == BindRefusals.Step.ASK_AGAIN) {
                askForTheNetwork()
                tellTheProjectionSoon(serial)
            } else {
                scheduleReconnect()
            }
        }
    }

    /** Whose the network in use is, for the ride log ([NetworkOwner]). */
    private fun networkOwner(caps: NetworkCapabilities? = null): String = notes.owner(
        joinedByPhone, requests,
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )

    /**
     * The failed session has told the projection to wait for its own retry (30 s after many tries) by the time the network asked for again after
     * a refusal is up. [RefusalRetry.AFTER_MS] later, with that network up, the projection is told to try the dash now (ride log of 06/11/2026,
     * 17:47:21: another network was up 20 ms after the refusal and the dash was tried 30 s later). With the request still unanswered, its answer does it.
     */
    private fun tellTheProjectionSoon(serial: Int) {
        scope.launch {
            delay(RefusalRetry.AFTER_MS)
            val up = synchronized(requestLock) {
                val now = network
                val due = RefusalRetry.due(
                    wantConnected, serial != refusalSerial, _state.value.status == WifiConnStatus.CONNECTED && now != null,
                    now != null && refusedNetworks.has(now),
                )
                now.takeIf { due }
            } ?: return@launch
            com.reau.app.data.RideDiagnostics.log("wifi", RefusalRetry.line(up, RefusalRetry.AFTER_MS))
            onNetworkAvailable?.invoke()
        }
    }

    /** Lets go of the watcher of a network the phone joined by itself (the request, if there is one, stays). */
    private fun dropWatcher() {
        if (callbackRegistered) networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callbackRegistered = false
        networkCallback = null
    }

    /**
     * A dash network the phone had joined by itself is used without a request of REau's own, so no
     * request callback says when it goes; without this, every try bound to the same dead network (EPERM) for minutes.
     * A listener for Wi-Fi networks tells its loss; any other network it hears of is none of its business.
     */
    // registerNetworkCallback throws a TooManyRequestsException, hidden from the SDK (a RuntimeException), when the
    // app holds too many callbacks; a watcher that could not be set must never cost the connection.
    @Suppress("TooGenericExceptionCaught")
    private fun watchJoined(joined: Network) {
        val watcher = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(lost: Network) {
                if (lost == joined && network == joined) bikeLost()
            }
        }
        val anyWifi = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(anyWifi, watcher)
            networkCallback = watcher // release() lets it go with the rest
            callbackRegistered = true
        } catch (e: RuntimeException) {
            com.reau.app.data.RideDiagnostics.warn("wifi", "could not watch the joined WiFi: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

/** Log only: `interactive=true locked=false`, the screen's state as the system's join dialog needs it (on and unlocked). */
private fun screenWordsOf(context: Context): String {
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    return "interactive=${power?.isInteractive} locked=${keyguard?.isKeyguardLocked}"
}
