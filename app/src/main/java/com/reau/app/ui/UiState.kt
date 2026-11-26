package com.reau.app.ui

import com.reau.app.service.Projection
import com.reau.app.settings.AppLanguage
import com.reau.app.settings.REauSettings

/** What the main screen shows. [cardName] is the picture the card mode sends; null is the legibility card. */
data class MainUiState(
    val projection: Projection = Projection(),
    val androidAutoVersion: String? = null,
    val cardName: String? = null,
    /** What the main screen shows of the servers (the network server's icon). */
    val server: ServerUi = ServerUi(),
    /** Battery saver and REau's battery-optimization exemption. */
    val battery: BatteryUi = BatteryUi(),
    /** When the projection started (SystemClock.elapsedRealtime), for the time on Desligar; null while stopped. */
    val startedAtMs: Long? = null,
    /** The dash's full Wi-Fi name as learned, for the Wi-Fi shortcut; empty while none is known. */
    val dashName: String = "",
    /** How many of the permissions page's rows are still missing, for its shortcut. */
    val permissionsMissing: Int = 0,
    /** REau's traffic goes into a VPN, which keeps it off the bike's Wi-Fi. */
    val vpnHoldsREau: Boolean = false,
    /** Android's Location switch is off although REau has the permission. */
    val locationOff: Boolean = false,
    /** Whether the alert channel (stuck dash, refused network, hot phone) would be heard, or is muted or blocked in Android. */
    val alertLevel: com.reau.app.service.AlertChannelState.Level = com.reau.app.service.AlertChannelState.Level.LOUD,
    /** What the alert card's button does ([AlertRoute]): asking for the notification permission changes its words. */
    val alertRoute: AlertRoute.Route = AlertRoute.Route.NONE,
    /** REau's own version, under Android Auto's at the foot. */
    val reauVersion: String = com.reau.app.BuildConfig.VERSION_NAME,
    /** The switch "Clima no painel", which the weather section of the main screen shows. */
    val weatherOn: Boolean = false,
    /** The head unit server is off because Android Auto was updated since it last worked ([com.reau.app.aa.AaUpdatedServerOff]): the card of the server says so. */
    val aaUpdatedServerOff: Boolean = false,
    /** When the system closed REau during the last ride, until the rider dismisses the notice ([com.reau.app.data.SystemClosedRide]); null with none to tell. */
    val systemClosedAtMs: Long? = null,
    /** The one notice before the first ride, on a Xiaomi or realme phone, is due ([com.reau.app.data.KeepAliveNotice]). */
    val keepAliveDue: Boolean = false,
) {
    /** Whether Android Auto is installed; when not, its buttons lead to the store instead of to its settings. */
    val androidAutoInstalled: Boolean get() = androidAutoVersion != null
}

/**
 * Battery saver and REau's exemption from battery optimization, as the main screen shows them: both matter, since
 * battery optimization can kill a long-running projection.
 */
data class BatteryUi(val saverOn: Boolean = false, val exempt: Boolean = true) {
    /** The cards to show, in order: the exemption first (the one the rider fixes once), then battery saver. */
    val notices: List<BatteryNotice> get() = listOfNotNull(BatteryNotice.NOT_EXEMPT.takeIf { !exempt }, BatteryNotice.SAVER_ON.takeIf { saverOn })
}

enum class BatteryNotice { NOT_EXEMPT, SAVER_ON }

/** The state with the network server's flag set (the icon of the main screen and the server screen read it). */
fun MainUiState.withNetwork(on: Boolean): MainUiState = copy(server = server.copy(networkOn = on))

/** The state with the time of the exit the system caused, for the notice on the main screen. */
fun MainUiState.withSystemClosed(atMs: Long?): MainUiState = copy(systemClosedAtMs = atMs)

/** The state with whether the one notice before the first ride on a Xiaomi or realme phone is due. */
fun MainUiState.withKeepAliveDue(due: Boolean): MainUiState = copy(keepAliveDue = due)

/** What the main screen shows of the servers. */
data class ServerUi(
    /** The network server (the browser's, "Ver no navegador") is up: the icon beside Android Auto's side blinks. */
    val networkOn: Boolean = false,
)

/** The main screen's actions beyond start/stop and the settings: the card mode, the shortcuts, the warnings. */
data class MainExtras(
    val onCard: () -> Unit = {},
    val onShowAndroidAuto: () -> Unit = {},
    /** Android's dialog to leave REau out of battery optimization, and battery saver's settings. */
    val onAskBatteryExemption: () -> Unit = {},
    val onOpenBatterySaver: () -> Unit = {},
    /** Android's settings page of the alert channel (or of the app's notifications), when the alerts are muted or blocked. */
    val onOpenAlertSettings: () -> Unit = {},
    /** Android's Wi-Fi panel, to switch the phone's Wi-Fi on (the app cannot do it since Android 10). */
    val onOpenWifiPanel: () -> Unit = {},
    /** The permissions page and "O REau e os seus dados", from the main screen. */
    val onOpenPermissions: () -> Unit = {},
    val onOpenAboutData: () -> Unit = {},
    /** The "Wi-Fi da moto" shortcut opens the Wi-Fi's own screen. */
    val onOpenWifi: () -> Unit = {},
    /** The weather's own screen, from its shortcut. */
    val onOpenWeather: () -> Unit = {},
    /** The network server's screen, from its icon. */
    val onOpenServer: () -> Unit = {},
    /** The tips page, from the lightbulb in the main screen's bar. */
    val onOpenTips: () -> Unit = {},
    /** The guide of what keeps REau from being closed by the system, and the dismissing of the notice that the system closed it. */
    val onOpenKeepAlive: () -> Unit = {},
    val onDismissSystemClosed: () -> Unit = {},
    /** The one notice before the first ride: "Entendi" (the other button, "Ver os passos", is [onOpenKeepAlive]). */
    val onDismissKeepAliveAdvice: () -> Unit = {},
)

/** What the settings screen shows of the dash pairing. */
data class PairingUi(val ssid: String = "", val ssidPrefix: String = "RE_", val password: String = "")

/**
 * What the settings screen does: hands over a changed setting or pairing, forgets the dash, goes back, asks for a
 * picture for the card mode.
 */
@Suppress("LongParameterList") // one callback per thing the screen hands back, each with its default
class SettingsActions(
    val onSettings: (REauSettings) -> Unit,
    val onPairing: (PairingUi) -> Unit,
    val onForgetDash: () -> Unit,
    val onBack: () -> Unit,
    val onPickCardImage: () -> Unit = {},
    /** Share the logs (with the recordings when true). */
    val onShareLogs: (withRecordings: Boolean) -> Unit = {},
    /** The language of the app's texts, chosen on the settings (the screens are redrawn in it). */
    val onLanguage: (AppLanguage) -> Unit = {},
    /** "Ver o registro": the logs, inside the app. */
    val onViewLogs: () -> Unit = {},
    /**
     * Delete the logs (and the recordings when true), after a confirmation; [done] gets how many files and KB
     * went, for the Snackbar.
     */
    val onDeleteLogs: (withRecordings: Boolean, done: (files: Int, kb: Long) -> Unit) -> Unit = { _, _ -> },
    /** Ask Android for the location, only for the Wi-Fi list (the dash's name). */
    val onAskWifiList: () -> Unit = {},
)

/** Where the permissions page sends the rider. */
data class PermissionsActions(
    val onBack: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    /** Ask for one row's permission ([com.reau.app.settings.AppPermissions.ask]). */
    val onAsk: (com.reau.app.settings.AppPermissions.Ask) -> Unit = {},
    /** "Pedir as que faltam": the runtime permissions still missing, in one dialog. */
    val onAskAll: (List<String>) -> Unit = {},
    /** The guide of what keeps REau from being closed by the system, from the special access group (near the battery line). */
    val onOpenKeepAlive: () -> Unit = {},
)
