package com.reau.app.dash

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log
import com.reau.app.data.RideDiagnostics
import com.reau.app.settings.SettingsStore

/**
 * Keeps the OS's WifiNetworkSuggestion for the dash in sync with the "join the bike's Wi-Fi by itself" switch.
 * This is what lets Android join the dash's Wi-Fi on its
 * own whenever it is in range, without the app running a scan and without the network picker. It does not start
 * streaming: the projection still asks for the network, which then answers at once.
 *
 * Stateless: a suggestion is either in effect or not. Every call site (app start, the switch, the dash's name learned
 * or forgotten) goes through [sync], which is idempotent and cheap.
 */
object DashWifiSuggestions {
    private const val TAG = "DashWifiSuggestions"

    /**
     * The suggestion that should be in effect, or null. A suggestion needs the EXACT name -- there is no "any RE_*
     * network" suggestion -- so it stays off until the dash's whole name is known (never the factory prefix alone).
     */
    internal fun suggestionFor(enabled: Boolean, ssid: String, password: String): WifiNetworkSuggestion? {
        if (!enabled) return null
        if (!DashName.isDashName(ssid)) return null
        return WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .apply { if (password.isNotBlank()) setWpa2Passphrase(password) }
            .build()
    }

    /** Reads the switch and what is remembered of the dash, and applies them. */
    @Synchronized // the state is read under the same lock as it is applied: a slow sync cannot undo a newer one
    fun sync(context: Context) {
        val settings = runCatching { SettingsStore.open(context).load() }.getOrNull()
        val pairing = runCatching { DashPairing.open(context) }.getOrNull()
        apply(context, settings?.autoJoinWifi == true, pairing?.ssid.orEmpty(), pairing?.password.orEmpty())
    }

    /** [sync] off the main thread (binder calls): after the switch changed or the dash was forgotten. */
    fun syncSoon(context: Context) {
        kotlin.concurrent.thread(name = "wifi-suggestion") { sync(context) }
    }

    /**
     * Brings the suggestions this app has registered to the wanted one, if any, and logs what it did to the ride log (this runs at
     * moments, app start or a name just learned, when nobody watches logcat). The decision is [SuggestionPlan.of]; this is the thin
     * shell around WifiManager.
     *
     * A wanted suggestion that is already registered is left alone: removing a suggestion that is in use drops the Wi-Fi
     * association (DISCONNECT_NETWORK_REMOVED), and the app starting with the phone already joined to the dash by it would then
     * wait seconds for the network to come back. The removals here are not lingering (ACTION_REMOVE_SUGGESTION_LINGER, API 33): what
     * is removed is either a leftover of another dash or everything when the switch is off, and in both the intent is to cut.
     */
    @Synchronized // sync runs from several threads now (the app's start, the screen, the projection): read-decide-write must not interleave
    fun apply(context: Context, enabled: Boolean, ssid: String, password: String) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Called from Application.onCreate: a password the builder refuses (not ASCII) must not take the app down.
        val suggestion = runCatching { suggestionFor(enabled, ssid, password) }
            .onFailure {
                Log.w(TAG, "the suggestion was refused: ${it.message}")
                RideDiagnostics.log("wifi", "auto-join suggestion refused: ${it.javaClass.simpleName}: ${it.message}")
            }
            .getOrNull()
        val masked = DashSsid.maskSsid(ssid)
        val current = if (suggestion != null) registered(wifi) else null // nothing to compare when all goes
        val plan = SuggestionPlan.of(suggestion?.let { SuggestionKey.of(ssid, password) }, current?.map { it.first }, Build.VERSION.SDK_INT)
        when (plan.move) {
            SuggestionMove.CLEAR_ALL -> {
                // Removing with an empty list clears every suggestion this app has registered (documented WifiManager behaviour).
                removeSuggestions(wifi, emptyList())
                // Only with the switch on: with it off (the default) this would be a line in every ride log for nothing.
                if (enabled) RideDiagnostics.log("wifi", "auto-join suggestion: none in effect (dash name known=${DashName.isDashName(ssid)})")
            }
            SuggestionMove.KEEP -> {
                val removed = removeLeftovers(wifi, current, plan.remove)
                RideDiagnostics.log("wifi", "auto-join suggestion for '$masked' already in effect: kept" + leftoversNote(removed))
            }
            SuggestionMove.ADD, SuggestionMove.RESET -> {
                if (plan.move == SuggestionMove.RESET) removeSuggestions(wifi, emptyList())
                add(wifi, suggestion ?: return, masked)
                val removed = removeLeftovers(wifi, current, plan.remove)
                if (removed > 0) RideDiagnostics.log("wifi", "auto-join suggestion for '$masked': replaced" + leftoversNote(removed))
            }
        }
    }

    private fun leftoversNote(removed: Int) = if (removed > 0) " ($removed other suggestion(s) removed)" else ""

    /** What the app has registered, as (key, suggestion) pairs; null when it cannot be read (API 29 has no getNetworkSuggestions). */
    private fun registered(wifi: WifiManager): List<Pair<SuggestionKey, WifiNetworkSuggestion>>? {
        if (Build.VERSION.SDK_INT < SuggestionPlan.READABLE_FROM_SDK) return null
        return runCatching { wifi.getNetworkSuggestions().map { SuggestionKey.of(it.ssid.orEmpty(), it.passphrase) to it } }
            .onFailure {
                Log.w(TAG, "getNetworkSuggestions failed: ${it.message}")
                RideDiagnostics.log("wifi", "suggestion read FAILED: ${it.message}")
            }
            .getOrNull()
    }

    private fun add(wifi: WifiManager, suggestion: WifiNetworkSuggestion, masked: String) {
        val status = runCatching { wifi.addNetworkSuggestions(listOf(suggestion)) }
            .onFailure { Log.w(TAG, "addNetworkSuggestions threw: ${it.message}") }
            .getOrNull()
        Log.i(TAG, "addNetworkSuggestions('$masked') -> status=$status")
        // status 0 == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS; anything else means Android will not auto-join.
        RideDiagnostics.log(
            "wifi", "auto-join suggestion for '$masked' -> status=$status" + (if (status != 0) " (0=success; non-zero means Android rejected it)" else ""),
        )
    }

    /** Removes the registered suggestions whose key is in [keys]; returns how many it asked to remove. */
    private fun removeLeftovers(wifi: WifiManager, current: List<Pair<SuggestionKey, WifiNetworkSuggestion>>?, keys: List<SuggestionKey>): Int {
        val gone = current.orEmpty().filter { it.first in keys }.map { it.second }
        if (gone.isNotEmpty()) removeSuggestions(wifi, gone)
        return gone.size
    }

    private fun removeSuggestions(wifi: WifiManager, list: List<WifiNetworkSuggestion>) {
        runCatching { wifi.removeNetworkSuggestions(list) }
            .onSuccess { if (it != 0) RideDiagnostics.log("wifi", "suggestion remove -> status=$it") }
            .onFailure {
                Log.w(TAG, "removeNetworkSuggestions failed: ${it.message}")
                RideDiagnostics.log("wifi", "suggestion remove FAILED: ${it.message}")
            }
    }
}
