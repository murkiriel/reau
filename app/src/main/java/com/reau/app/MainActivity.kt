package com.reau.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.reau.app.phone.PhoneCalls
import com.reau.app.aa.AndroidAuto
import com.reau.app.card.CardName
import com.reau.app.dash.DashName
import com.reau.app.dash.DashPairing
import com.reau.app.dash.DashWifiManager
import com.reau.app.dash.DashWifiSuggestions
import com.reau.app.data.DetailedLog
import com.reau.app.data.RideDiagnostics
import com.reau.app.service.AaDialNow
import com.reau.app.service.LiveTuning
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.service.ProjectionService
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsStore
import com.reau.app.settings.StartAsks
import com.reau.app.ui.AaScreenMask
import com.reau.app.ui.AndroidAutoScreen
import com.reau.app.ui.MainExtras
import com.reau.app.ui.MainScreen
import com.reau.app.ui.MainUiState
import com.reau.app.ui.PairingUi
import com.reau.app.ui.ServerUi
import com.reau.app.ui.withKeepAliveDue
import com.reau.app.ui.withNetwork
import com.reau.app.ui.withSystemClosed
import com.reau.app.ui.SettingsActions
import com.reau.app.ui.SettingsScreen
import com.reau.app.util.DiagnosticsExport
import kotlin.concurrent.thread

/**
 * The app's one activity: the main screen and the settings screen. The projection itself lives in
 * [ProjectionService]; this only switches it on and off and shows where it stands.
 */
@Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
class MainActivity : ComponentActivity() {
    private var androidAutoVersion by mutableStateOf<String?>(null)

    // The server is off because an update of Android Auto turned it off (AaUpdatedServerOff): the card of the server says so.
    private var aaUpdatedServerOff by mutableStateOf(false)

    // Whether REau is on screen (between onResume and onPause), for what opens Android Auto's settings by itself.
    private var onScreen by mutableStateOf(false)

    private var permissionRows by mutableStateOf(emptyList<com.reau.app.settings.AppPermissions.Row>())
    // When the page's window was launched, to tell a window the rider answered from one Android never showed (AppPermissions.openAppPageAfter).
    private var askedPermissionsAtMs = 0L
    // The permissions page asks for what is missing; the rows are read again after.
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        notificationsAsked(answers)
        permissionRows = PermissionRows.read(this)
        permissionsAnswered(answers)
    }
    internal val askRuntime: (List<String>) -> Unit = {
        askedPermissionsAtMs = com.reau.app.util.MonotonicClock.nowMs()
        askPermissions.launch(it.toTypedArray())
    }

    /** Something asked is still refused and no window can have been shown (Android refuses by itself after two refusals): the app's page in Android opens. */
    private fun permissionsAnswered(answers: Map<String, Boolean>) {
        val elapsed = com.reau.app.util.MonotonicClock.nowMs() - askedPermissionsAtMs
        if (!com.reau.app.settings.AppPermissions.openAppPageAfter(answers, elapsed)) return
        val refused = answers.count { !it.value }
        RideDiagnostics.log("ui", "the permissions page asked, Android refused with no window ($refused of ${answers.size}): the app's page opened")
        PermissionRows.openAppSettings(this)
    }

    // Battery saver and the exemption, read again at each return to the screen and when battery saver flips.
    private var battery by mutableStateOf(com.reau.app.ui.BatteryUi())

    // Settings changed by picking a card picture, for the screen to read again.
    private var settingsVersion by mutableIntStateOf(0)

    // Which start waits for Android's questions: the projection or the card.
    private var startingCard = false

    // Whether REau may read the Wi-Fi list now (to learn the dash's full name), for the Wi-Fi screen.
    private var scanAllowed by mutableStateOf(true)

    // The location (only for the Wi-Fi list) answered: said in the log, and the dash's name learned now if it opened.
    private val wifiListAnswered: (Boolean) -> Unit = { granted ->
        RideDiagnostics.log("ui", "the location for the Wi-Fi list " + if (granted) "allowed" else "refused")
        permissionRows = PermissionRows.read(this)
        learnDashName()
    }

    // Android 13+ asks before the projection's notification may show and, while the dash's whole name is not known,
    // for the location, only for the Wi-Fi list (StartAsks); the projection starts either way.
    private val askBeforeStart = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        notificationsAsked(answers)
        answers[StartAsks.FINE_LOCATION]?.let { wifiListAnswered(it) }
        if (startingCard) ProjectionService.startCard(this) else ProjectionService.start(this)
        startAnswered(answers)
    }

    // When Ligar's window was launched, to tell a window the rider answered from one Android never showed (StartAsks.openAppPageAfter).
    private var askedStartAtMs = 0L

    /** The projection already started: a refusal with no window opens the app's page, once (the mark is erased when Ligar has nothing to ask). */
    private fun startAnswered(answers: Map<String, Boolean>) {
        val store = SettingsStore.open(this)
        val elapsed = com.reau.app.util.MonotonicClock.nowMs() - askedStartAtMs
        if (!StartAsks.openAppPageAfter(answers, elapsed, store.startAppPageOpened())) return
        store.startAppPageOpenedNow()
        val refused = answers.count { !it.value }
        RideDiagnostics.log("ui", "Ligar asked, Android refused with no window ($refused of ${answers.size}): the app's page opened, once")
        PermissionRows.openAppSettings(this)
    }

    // The Wi-Fi screen's "Permitir a localização" (only for the Wi-Fi list).
    private val askWifiList = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        wifiListAnswered(answers[StartAsks.FINE_LOCATION] == true)
    }

    // Whether REau's own traffic goes into a VPN (read at each return to the screen), for the main screen's card.
    private var vpnHoldsREau by mutableStateOf(false)
    private var locationOff by mutableStateOf(false)

    // Whether the alert channel would be heard (read at each return to the screen): muted or blocked in Android, the main screen says so.
    private var alertLevel by mutableStateOf(com.reau.app.service.AlertChannelState.Level.LOUD)
    private var alertRoute by mutableStateOf(com.reau.app.ui.AlertRoute.Route.NONE)

    // The alert card's "Permitir os avisos": Android's window for the notification permission (13+); when it was launched, to tell a window
    // the rider answered from one Android never showed (AlertRoute.openAppPageAfter).
    private var askedNotificationsAtMs = 0L
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        com.reau.app.ui.AlertSettings.answered(this, granted, askedNotificationsAtMs)
        readAlertLevel()
        permissionRows = PermissionRows.read(this)
    }

    /** The notification permission was among what Android just asked: REau has asked, and a blocked app is a refusal now (AlertRoute). */
    private fun notificationsAsked(answers: Map<String, Boolean>) {
        if (StartAsks.NOTIFICATIONS in answers) SettingsStore.open(this).notificationsAskedNow()
        readAlertLevel()
    }

    // The pairing read again by the screens once a search learned the dash's name.
    private var pairingVersion by mutableIntStateOf(0)

    // Asked once a run as REau opens with the dash's name unknown (DashFinder); the Wi-Fi screen offers it again.
    private var wifiListAsked = false

    // The dash's Wi-Fi found and filled in by itself: searched for while REau is on screen.
    private val dashSearch = DashSearch(
        this,
        step = {
            val known = DashPairing.open(this).ssid.isNotBlank()
            com.reau.app.dash.DashFinder.step(known, scanAllowed, wifiListAsked, termsAccepted = termsAcceptedNow())
        },
        ask = {
            wifiListAsked = true
            askWifiList.launch(StartAsks.LOCATION.toTypedArray())
        },
        learn = { learnDashName() },
    )

    /** The phone is in use and unlocked (no lock screen showing); when `KeyguardManager` cannot be had it counts as locked. */
    private fun phoneUnlocked(): Boolean = (getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked == false

    // The terms of use come before every question of Android's (the location, for the Wi-Fi list, is one).
    private fun termsAcceptedNow() = !com.reau.app.settings.Terms.needsAcceptance(com.reau.app.settings.TermsStore.open(this).accepted())

    // Answering calls by the joystick: without both permissions the switch goes back off.
    private val askForCalls = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) return@registerForActivityResult
        val store = SettingsStore.open(this)
        store.save(store.load().copy(answerCalls = false))
        RideDiagnostics.log("ui", "the phone permissions were refused: answering by the joystick stays off")
        settingsVersion++
    }

    // Gemini by the joystick: without the microphone the switch goes back off.
    private val askForMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) return@registerForActivityResult
        val store = SettingsStore.open(this)
        store.save(store.load().copy(gemini = false))
        RideDiagnostics.log("ui", "the microphone permission was refused: Gemini by the joystick stays off")
        settingsVersion++
    }

    // The system's own picker; the picture stays readable after a restart by keeping the permission it grants.
    private val pickCardImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val store = SettingsStore.open(this)
        store.save(store.load().copy(cardImage = uri.toString()))
        settingsVersion++
    }

    // Before Android 13 the chosen language is put on the context here (from 13 the system holds it).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.reau.app.settings.AppLanguages.wrap(newBase))
    }

    private var resumedAtMs = 0L

    /** The phone is a Xiaomi or a realme (a family with steps of its own): the only phones the one notice before the first ride is for. */
    private val keepAliveEligible: Boolean get() = com.reau.app.ui.KeepAliveGuide.hasNotice(com.reau.app.ui.KeepAliveGuide.current())

    @Suppress("LongMethod") // one straight run of setup/serialization; cutting it adds indirection and no clarity
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.reau.app.util.FrameJank.watch(this, com.reau.app.util.MonotonicClock.nowMs()) { resumedAtMs }
        lifecycle.addObserver(dashSearch)
        if (savedInstanceState == null) quickStart(intent)
        // What the last opening found about the system closing REau during a ride, still not dismissed (the opening's thread may add to it: SystemClosedRide.note).
        // Off the main thread: the first read of the kept preference is the disk (review of the plan 34, B3, 26/11/2026; the notice shows a moment later, as the Android Auto one does).
        // The one notice before the first ride on a Xiaomi or realme phone (KeepAliveNotice): due or not, as of now; read again at each return to the screen. It reads the SAME
        // file (SystemClosedRide.lastNotedAt), so it goes in the same IO coroutine, after the load: on the main thread it would take the disk back (review of 26/11/2026, C4).
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.reau.app.data.SystemClosedRide.load(applicationContext) }
            runCatching { com.reau.app.data.KeepAliveNotice.refresh(applicationContext, keepAliveEligible) }
        }
        setContent {
            val projection by ProjectionService.projection.collectAsStateWithLifecycle()
            val startedAt by ProjectionService.startedAt.collectAsStateWithLifecycle()
            // The network server is up when it has published its address and PIN (the icon of the main screen blinks for it).
            val networkOn = com.reau.app.web.WebAccess.state.collectAsStateWithLifecycle().value != null
            // The system closed REau during the last ride, until the rider dismisses the notice (SystemClosedRide, told at the opening).
            val systemClosedAt = com.reau.app.data.SystemClosedRide.pendingAt.collectAsStateWithLifecycle().value
            val keepAliveDue = com.reau.app.data.KeepAliveNotice.isDue.collectAsStateWithLifecycle().value
            var inSettings by rememberSaveable { mutableStateOf(false) }
            var inAndroidAuto by rememberSaveable { mutableStateOf(false) }
            var inJoystick by rememberSaveable { mutableStateOf(false) }
            var page by rememberSaveable { mutableStateOf(SettingsPage.NONE) }
            androidx.compose.runtime.SideEffect { com.reau.app.util.FrameJank.settingsOpen = inSettings || page != SettingsPage.NONE }
            // A screen under another keeps its place (its scroll) until it is left.
            val kept = rememberSaveableStateHolder()
            val store = remember { SettingsStore.open(this) }
            val pairingStore = remember { DashPairing.open(this) }
            var settings by remember(settingsVersion) { mutableStateOf(store.load()) }
            var pairing by remember(pairingVersion) { mutableStateOf(pairingStore.toUi()) }
            com.reau.app.ui.BatteryWatch.Follow(this) { battery = it }
            // The server is switched on by hand, so Android Auto's settings open by themselves, once for each projection.
            var aaSettingsOpened by remember(startedAt) { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(projection.androidAuto, startedAt, onScreen, androidAutoVersion) {
                if (AndroidAuto.opensSettingsByItself(
                        projection.androidAuto, aaSettingsOpened, onScreen, androidAutoVersion != null,
                    )) {
                    aaSettingsOpened = true
                    RideDiagnostics.log("aa-server", "the head unit server is off: Android Auto's settings open by themselves")
                    openAndroidAutoSettings()
                }
            }

            androidx.compose.runtime.LaunchedEffect(projection.androidAuto, androidAutoVersion) { aaUpdatedServerOff = readAaUpdated(projection.androidAuto) }

            val saveSettings: (REauSettings) -> Unit = { settings = saved(store, settings, it) }
            val reload = { settings = store.load(); pairing = pairingStore.toUi() }
            // The browser switch goes off with the projection (the service writes it): the screen follows when it ends.
            androidx.compose.runtime.LaunchedEffect(startedAt == null) { if (startedAt == null) settings = store.load() }
            val leaveSettings = { inSettings = false; kept.removeState(SETTINGS_SCREEN) }
            val actions = settingsActions(saveSettings, pairingStore, { pairing = it }, leaveSettings) { page = it }
            // Nothing of the app is reachable until the rider has accepted the current terms of use.
            val termsStore = remember { com.reau.app.settings.TermsStore.open(this) }
            var termsAccepted by remember { mutableStateOf(!com.reau.app.settings.Terms.needsAcceptance(termsStore.accepted())) }
            androidx.compose.runtime.CompositionLocalProvider(com.reau.app.ui.theme.LocalREauNight provides rememberNight(this, settings)) {
            if (!termsAccepted) {
                com.reau.app.ui.TermsScreen(
                    gate = true,
                    onAccept = {
                        termsStore.accept()
                        termsAccepted = true
                        dashSearch.onResume(this)
                        RideDiagnostics.log("ui", "the rider accepted the terms of use, version ${com.reau.app.settings.Terms.VERSION}")
                    },
                    onDecline = {
                        RideDiagnostics.log("ui", "the rider did not accept the terms of use")
                        finish()
                    },
                    onBack = {},
                )
            } else if (inAndroidAuto) {
                AndroidAutoFromSettings(settings, saveSettings, store) { inAndroidAuto = false }
            } else if (page == SettingsPage.WIFI) {
                BackHandler { page = SettingsPage.NONE }
                com.reau.app.ui.WifiScreen(
                    settings, pairing, actions, scanAllowed,
                    projectionRunning = projection.phase != com.reau.app.service.Phase.STOPPED,
                ) { page = SettingsPage.NONE }
            } else if (page == SettingsPage.WEATHER) {
                BackHandler { page = SettingsPage.NONE }
                com.reau.app.ui.WeatherScreen(settings.dashWeather, { saveSettings(settings.copy(dashWeather = it)) }) { page = SettingsPage.NONE }
            } else if (page == SettingsPage.SERVER) {
                // The server's screen, from its icon on the main screen.
                BackHandler { page = SettingsPage.NONE }
                com.reau.app.ui.ServerScreen(
                    mainState(projection, settings, startedAt, pairing.ssid).withNetwork(networkOn), settings,
                    { saveSettings(it.sanitized()) },
                ) { page = SettingsPage.NONE }
            } else if (page != SettingsPage.NONE) {
                SettingsPageFromSettings(
                    this, page, permissionRows, settings.useLocation, com.reau.app.settings.AppPermissions.functionsOf(settings), { page = it },
                ) { page = SettingsPage.NONE }
            } else if (inJoystick) {
                // The joystick page, over the settings.
                BackHandler { inJoystick = false }
                com.reau.app.ui.JoystickScreen(settings, saveSettings) { inJoystick = false }
            } else if (inSettings) {
                BackHandler(onBack = leaveSettings)
                kept.SaveableStateProvider(SETTINGS_SCREEN) {
                    SettingsScreen(settings, actions, { inJoystick = true })
                }
            } else {
                kept.SaveableStateProvider(MAIN_SCREEN) {
                    MainScreen(
                        mainState(projection, settings, startedAt, pairing.ssid).withNetwork(networkOn).withSystemClosed(systemClosedAt)
                            .withKeepAliveDue(keepAliveDue),
                        onToggle = { toggle(projection) },
                        onOpenAndroidAutoSettings = { openAndroidAutoSettings() },
                        onOpenSettings = { reload(); inSettings = true },
                        mainExtras { inAndroidAuto = true.also { RideDiagnostics.log("ui", "the rider opened the Android Auto screen") } }.copy(
                            onOpenPermissions = { page = SettingsPage.PERMISSIONS }, onOpenAboutData = { page = SettingsPage.ABOUT_DATA },
                            onOpenWifi = { reload(); page = SettingsPage.WIFI },
                            onOpenWeather = { page = SettingsPage.WEATHER },
                            onOpenServer = { page = SettingsPage.SERVER },
                            onOpenTips = { page = SettingsPage.TIPS },
                        ).withKeepAliveTaps(systemClosedAt) { page = SettingsPage.KEEP_ALIVE },
                    )
                }
            }
            }
        }
    }

    /** What the settings and the Wi-Fi screen do; [onBack] leaves the settings, [open] a page over them (the Wi-Fi's too). */
    private fun settingsActions(
        saveSettings: (REauSettings) -> Unit,
        pairingStore: DashPairing,
        setPairing: (PairingUi) -> Unit,
        onBack: () -> Unit,
        open: (SettingsPage) -> Unit,
    ) = SettingsActions(
        onSettings = saveSettings,
        onPairing = { setPairing(savePairing(pairingStore, it)) },
        // With the projection on, it is switched off first: the pairing is not cleared in the middle of a reconnection.
        onForgetDash = {
            if (ProjectionService.projection.value.phase == Phase.STOPPED) {
                setPairing(forgetDash(this, pairingStore).toUi())
            } else {
                ProjectionService.stop(this)
                // The app's own scope, not the screen's: the rider confirmed, and turning the phone or leaving the app during the
                // wait must not cancel the forgetting (the projection would be off and the dash still paired).
                val app = applicationContext
                forgetScope.launch {
                    kotlinx.coroutines.withTimeoutOrNull(FORGET_WAIT_STOP_MS) { ProjectionService.projection.first { it.phase == Phase.STOPPED } }
                    val forgotten = forgetDash(app, pairingStore).toUi()
                    runOnUiThread { if (!isDestroyed) setPairing(forgotten) }
                }
            }
        },
        onBack = onBack,
        onPickCardImage = { pickCardImage.launch(arrayOf("image/*")) },
        onShareLogs = { DiagnosticsExport.shareFrom(this, withRecordings = it) },
        onLanguage = { com.reau.app.settings.AppLanguages.set(this, it) },
        onViewLogs = { open(SettingsPage.LOGS) },
        onAskWifiList = { askWifiList.launch(StartAsks.LOCATION.toTypedArray()) },
        onDeleteLogs = { withRecordings, done -> deleteLogs(this, withRecordings, done) },
    )

    /**
     * What the cards about the system closing REau and the one-time notice do with the rider's taps (26/11/2026; in a function of its own so onCreate stays simple):
     * "Ver os passos" goes to the page with [open], and dismisses the card of the closing when there is one ([systemClosedAt]); "Entendi" on either card answers the
     * one-time notice, so it does not come on top of a closing just told.
     */
    private fun MainExtras.withKeepAliveTaps(systemClosedAt: Long?, open: () -> Unit): MainExtras = copy(
        onOpenKeepAlive = {
            // The card of the system closing REau goes away with it; the one-time notice has no such card to dismiss (the page answers it).
            if (systemClosedAt != null) com.reau.app.data.SystemClosedRide.dismiss(this@MainActivity)
            open()
        },
        onDismissSystemClosed = {
            com.reau.app.data.SystemClosedRide.dismiss(this@MainActivity)
            com.reau.app.data.KeepAliveNotice.answered(this@MainActivity, keepAliveEligible, "understood, after the system closed REau")
        },
        onDismissKeepAliveAdvice = { com.reau.app.data.KeepAliveNotice.answered(this@MainActivity, keepAliveEligible, "understood") },
    )

    /** What the main screen shows: the projection, its start, the card's picture, the server, battery, dash and permissions. */
    private fun mainState(projection: com.reau.app.service.Projection, settings: REauSettings, startedAt: Long?, dashName: String) =
        MainUiState(
            projection, androidAutoVersion, settings.cardImage?.let { CardName.shown(this, it) },
            ServerUi(),
            battery,
            startedAtMs = startedAt, dashName = dashName, permissionsMissing = permissionsMissing(settings), vpnHoldsREau = vpnHoldsREau,
            locationOff = locationOff,
            alertLevel = alertLevel,
            alertRoute = alertRoute,
            weatherOn = settings.dashWeather,
            aaUpdatedServerOff = aaUpdatedServerOff,
        )

    /**
     * Whether the server being off may be the work of an update of Android Auto; the ride log says it once for each time it becomes so.
     * The package query (a binder call) and the first read of the kept preference (the disk) run off the main thread (review of 26/11/2026, A5).
     */
    private suspend fun readAaUpdated(link: com.reau.app.aa.AaLinkState): Boolean {
        val updated = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.reau.app.aa.AaUpdatedServerOff.now(this@MainActivity, link)
        }
        if (updated && !aaUpdatedServerOff) RideDiagnostics.log("aa-server", com.reau.app.aa.AaUpdatedServerOff.LOG_LINE)
        return updated
    }

    /** Reads whether the alert channel would be heard; the ride log says it when it changes (and the main screen's card follows [alertLevel]). */
    private fun readAlertLevel() {
        val read = com.reau.app.service.AlertChannelState.read(this)
        val route = com.reau.app.ui.AlertSettings.routeNow(this, read)
        // On a clean install, before REau asked for the permission, the card waits: nobody has refused anything (AlertRoute).
        val level = if (route == com.reau.app.ui.AlertRoute.Route.NONE) com.reau.app.service.AlertChannelState.Level.LOUD else read
        alertRoute = route
        if (level != alertLevel) {
            val line = com.reau.app.service.AlertChannelState.line(level)
            if (line != null) RideDiagnostics.warn("ui", line) else RideDiagnostics.log("ui", "alerts: the alert channel would be heard again")
        }
        alertLevel = level
    }

    /** "Faltam N": only what the functions switched on need (the others are optional on the permissions page). */
    private fun permissionsMissing(settings: REauSettings) = com.reau.app.settings.AppPermissions.missing(
        permissionRows, Build.VERSION.SDK_INT, settings.useLocation, com.reau.app.settings.AppPermissions.functionsOf(settings),
    )

    /** The main screen's other actions: the card mode, the Android Auto screen ([onShowAndroidAuto]), the server. */
    private fun mainExtras(onShowAndroidAuto: () -> Unit) = MainExtras(
        onCard = { start(card = true) },
        onShowAndroidAuto = onShowAndroidAuto,
        onAskBatteryExemption = { com.reau.app.ui.BatteryWatch.askExemption(this) },
        onOpenBatterySaver = { com.reau.app.ui.BatteryWatch.openSaver(this) },
        onOpenAlertSettings = {
            com.reau.app.ui.AlertSettings.open(this) {
                askedNotificationsAtMs = com.reau.app.util.MonotonicClock.nowMs()
                askNotifications.launch(StartAsks.NOTIFICATIONS)
            }
        },
        onOpenWifiPanel = { com.reau.app.service.WifiPanel.open(this) },
    )

    /** What follows a change in the settings: the log, the Wi-Fi suggestion, a running refit. */
    @Suppress("CyclomaticComplexMethod") // one branch per field or case of the format it reads or writes
    private fun settingsChanged(before: REauSettings, after: REauSettings) {
        before.changesTo(after).forEach { RideDiagnostics.log("ui", "setting $it") }
        // The bike Wi-Fi suggestion follows the switch.
        if (after.autoJoinWifi != before.autoJoinWifi) DashWifiSuggestions.syncSoon(this)
        if (after.answerCalls && !before.answerCalls && !PhoneCalls(this).permitted()) askForCalls.launch(PhoneCalls.PERMISSIONS)
        if (after.gemini && !before.gemini && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askForMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
        // The detailed log follows its switch at once.
        DetailedLog.enabled = after.detailedLog
        // So do the ride log and the logcat copy.
        if (after.rideLog != before.rideLog) switchRideLog(after.rideLog)
        if (after.logKeepDays != before.logKeepDays) thread(name = "log-age") { RideDiagnostics.applyKeepDays(after.logKeepDays) }
        // A running projection takes a new fit at once, to adjust it looking at the dash; so does every video
        // setting (LiveTuning).
        val refit = LiveTuning.steps(before, after).isNotEmpty()
        if (after.useLocation != before.useLocation) {
            // The place is learned here, on the screen (the service cannot read it); the refit goes after, so the dash's day or night uses it.
            learnSunPlace(this, then = { if (refit) ProjectionService.refit(this) })
        } else if (refit) {
            ProjectionService.refit(this)
        }
    }

    /** Saves [after], reads it back and lets everything that follows a change know; the settings now. */
    private fun saved(store: SettingsStore, before: com.reau.app.settings.REauSettings, after: com.reau.app.settings.REauSettings) =
        store.run {
            save(after)
            load()
        }.also { settingsChanged(before, it) }

    private fun toggle(p: Projection) {
        // The button is disabled meanwhile; this is the second guard against a start that would queue behind the stop.
        if (p.stopping) {
            RideDiagnostics.log("ui", "the rider tapped while the projection was turning off: nothing done")
            return
        }
        val phase = p.phase
        RideDiagnostics.log("ui", if (phase == Phase.STOPPED) "the rider switched the projection on" else "the rider switched the projection off")
        if (phase == Phase.STOPPED) start(card = false) else ProjectionService.stop(this)
    }

    // The launcher shortcut and the Quick Settings tile (QuickStart) arrive here: REau opens and, if it is off, switches on.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        quickStart(intent)
    }

    private fun quickStart(intent: Intent?) {
        val projection = ProjectionService.projection.value
        val unlocked = phoneUnlocked()
        if (intent?.action == com.reau.app.service.QuickStart.ACTION && !unlocked) {
            // Opened over a lock screen: the app opens, nothing is switched on, and the action is not kept for when the phone is unlocked later.
            intent.action = null
            RideDiagnostics.log("ui", "the shortcut or the tile was used with the phone locked: the app opened, the projection was not switched on")
            return
        }
        if (!com.reau.app.service.QuickStart.switchesOn(intent?.action, projection, termsAcceptedNow(), unlocked)) return
        // Handled once: a rotation or a recreation of the activity must not switch it on again.
        intent?.action = null
        RideDiagnostics.log("ui", "the rider switched the projection on from the shortcut or the tile")
        start(card = false)
    }

    // When the rider left for Android Auto's settings (elapsed realtime), 0 if not: only for the log.
    private var leftForAaSettingsAtMs = 0L

    private fun openAndroidAutoSettings() {
        // Without Android Auto the button leads to its page in the store; whatever fails says so, never in silence.
        val installed = AndroidAuto.installedVersion(this) != null
        val opened = com.reau.app.aa.AndroidAutoStore.open(this, say = { RideDiagnostics.log("aa-server", it) })
        RideDiagnostics.log("aa-server", "rider opened Android Auto settings (installed: $installed) → $opened")
        if (opened != AndroidAuto.Opened.NOTHING) {
            leftForAaSettingsAtMs = android.os.SystemClock.elapsedRealtime()
        } else {
            android.widget.Toast.makeText(
                this, if (installed) R.string.aa_settings_failed else R.string.aa_store_none, android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onPause() {
        onScreen = false
        super.onPause()
    }

    override fun onResume() = com.reau.app.util.StartupTiming.timed("MainActivity.onResume") {
        super.onResume()
        resume()
    }

    private fun resume() {
        onScreen = true
        resumedAtMs = com.reau.app.util.MonotonicClock.nowMs()
        if (leftForAaSettingsAtMs != 0L) {
            val away = (android.os.SystemClock.elapsedRealtime() - leftForAaSettingsAtMs) / 1_000
            RideDiagnostics.log("aa-server", "rider back from Android Auto settings after $away s")
            leftForAaSettingsAtMs = 0L
            // The server may have been switched on there: dial now, not at the end of the wait (a refused dial costs nothing).
            AaDialNow.now()
        }
        // Read again each time: the rider may have just installed or updated Android Auto.
        androidAutoVersion = AndroidAuto.installedVersion(this)
        battery = com.reau.app.ui.BatteryWatch.read(this)
        vpnHoldsREau = com.reau.app.dash.NetworkBlock.vpnHolds(this)
        locationOff = com.reau.app.ui.LocationSwitch.read(this)
        readAlertLevel()
        permissionRows = PermissionRows.read(this)
        // The phone may have joined the dash for the first time while REau was behind another app: the notice before the first ride is due from then (KeepAliveNotice).
        // Off the main thread like the one at the opening: on a Xiaomi or realme phone `refresh` reads the keystore-backed pairing and the kept file, the disk (review of 26/11/2026, D1).
        val context = applicationContext
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.reau.app.data.KeepAliveNotice.refresh(context, keepAliveEligible) } }
        learnDashName()
        learnSunPlace(this, thenWeather = true)
        forgetRemovalFromRecents()
    }

    /**
     * The rider is back in the app: that the rider took it off the recent apps earlier in this ride no longer says the rider wants it closed, so the resume after
     * a kill is allowed again (25/11/2026: the mark kept "removed" for the rest of the ride and the resume stayed off with no word). Off the main thread.
     */
    private fun forgetRemovalFromRecents() {
        val context = applicationContext
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.reau.app.service.ProjectionResumeBook.open(context).reopened() } }
    }



    /**
     * Android lets REau read the Wi-Fi scan only while it is on screen, and a projection usually starts in
     * the background, when Android Auto connects. So the dash's full name is learned here, whenever the rider opens
     * REau near the bike, and is already known by then. Off the main thread: it reads the system's last scan.
     */
    private fun learnDashName() {
        // By the location permission alone, whatever the rider's switch "Usar a localização" says (LocationUse).
        scanAllowed = com.reau.app.service.PhonePlace.mayScan(this)
        if (!scanAllowed) return
        val wifi = DashWifiManager(this, lifecycleScope)
        thread(name = "dash-name") {
            // Opened here, not on the main thread: it reads the keystore-backed preferences.
            val pairing = DashPairing.open(this)
            DashName.Learner({ pairing.ssid }, { pairing.ssidPrefix }, wifi::findDashSsid, { pairing.ssid = it }).learn()?.let {
                RideDiagnostics.log("wifi", "the dash's whole name found by the search on screen")
                DashWifiSuggestions.sync(this)
                runOnUiThread { pairingVersion++ }
            }
        }
    }

    private fun start(card: Boolean) {
        fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val asks = StartAsks.permissions(
            Build.VERSION.SDK_INT, card, granted(StartAsks.NOTIFICATIONS), granted(StartAsks.FINE_LOCATION), DashPairing.open(this).ssid.isNotBlank(),
        )
        startingCard = card
        if (card) RideDiagnostics.log("ui", "the rider switched the card mode on")
        // Nothing to ask (all allowed or not needed): a later refusal opens the app's page once again.
        if (asks.isEmpty()) SettingsStore.open(this).startAppPageForget()
        when {
            asks.isNotEmpty() -> {
                askedStartAtMs = com.reau.app.util.MonotonicClock.nowMs()
                askBeforeStart.launch(asks.toTypedArray())
            }
            card -> ProjectionService.startCard(this)
            else -> ProjectionService.start(this)
        }
    }

}

/** The pairing as the settings screen shows it. */
private fun DashPairing.toUi() = PairingUi(ssid = ssid, ssidPrefix = ssidPrefix, password = password)

/** The ride log and the logcat copy switched on or off from the screen. */
private fun switchRideLog(on: Boolean) {
    if (on) {
        RideDiagnostics.switchOn("the rider switched the log on")
        com.reau.app.data.OwnLogcat.resume()
    } else {
        com.reau.app.data.OwnLogcat.pause()
        RideDiagnostics.switchOff("the rider switched it off")
    }
}

/** Ajustes' delete buttons: off the main thread, then how much went, for the screen's Snackbar. */
private fun deleteLogs(activity: MainActivity, withRecordings: Boolean, done: (files: Int, kb: Long) -> Unit) {
    thread(name = "delete-logs") {
        val cleared = com.reau.app.data.LogsCleanup.run(activity, withRecordings)
        activity.runOnUiThread { done(cleared.files, cleared.bytes / KB) }
    }
}

private const val KB = 1024

/** How often the screens' theme asks the sun again. */

/** The dash's name and password as the rider typed them; the pairing now. */
internal fun savePairing(pairingStore: DashPairing, typed: PairingUi): PairingUi {
    // Only what changed: each write is a round of the Keystore on this thread, and this runs at every letter typed in the name.
    if (typed.ssidPrefix != pairingStore.ssidPrefix) pairingStore.ssidPrefix = typed.ssidPrefix
    if (typed.password != pairingStore.password) pairingStore.password = typed.password
    // On a clean install the whole name may be typed when REau cannot see the Wi-Fi list.
    if (typed.ssid != pairingStore.ssid) pairingStore.ssid = typed.ssid
    return typed
}

/** The credits page's tap: the project's address to the clipboard (REau opens no browser). */
private fun copyAddress(context: android.content.Context, url: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("REau", url))
}

/** The rider forgot the dash: the pairing goes, and the bike Wi-Fi suggestion with it (off the main thread). */
private fun forgetDash(context: android.content.Context, pairingStore: DashPairing): DashPairing {
    RideDiagnostics.log("ui", "the rider forgot the dash")
    pairingStore.forget()
    // What that dash said (its identity among it) goes too: it is not the next dash's.
    com.reau.app.dash.DashReadings.clear()
    DashWifiSuggestions.syncSoon(context)
    return pairingStore
}

/**
 * The pages over the settings that hold no setting, and the bike's Wi-Fi (from the settings or the main screen). The
 * permissions page shows the rows read at the last onResume, read again whenever REau comes back to the front.
 */
private enum class SettingsPage { NONE, PERMISSIONS, ABOUT_DATA, CREDITS, TERMS, LOGS, WIFI, WEATHER, SERVER, TIPS, KEEP_ALIVE }

private val forgetScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

/** How long "Esquecer o painel" waits for the projection it switched off to stop before it clears the pairing anyway. */
private const val FORGET_WAIT_STOP_MS = 5_000L

/** The keys under which the main screen and the settings keep their place while another screen is over them. */
private const val MAIN_SCREEN = "main"
private const val SETTINGS_SCREEN = "settings"

@androidx.compose.runtime.Composable
private fun SettingsPageFromSettings(
    activity: MainActivity,
    page: SettingsPage,
    rows: List<com.reau.app.settings.AppPermissions.Row>,
    locationAllowed: Boolean,
    functions: com.reau.app.settings.AppPermissions.Functions,
    goTo: (SettingsPage) -> Unit,
    onBack: () -> Unit,
) {
    // The credits and the terms are opened from "O REau e os seus dados": back goes to it, not to the main screen.
    val back = if (page == SettingsPage.CREDITS || page == SettingsPage.TERMS) ({ goTo(SettingsPage.ABOUT_DATA) }) else onBack
    BackHandler(onBack = back)
    when (page) {
        SettingsPage.ABOUT_DATA -> com.reau.app.ui.AboutDataScreen(
            onBack, onOpenCredits = { goTo(SettingsPage.CREDITS) }, onOpenTerms = { goTo(SettingsPage.TERMS) },
        )
        SettingsPage.TIPS -> com.reau.app.ui.TipsScreen(onBack)
        SettingsPage.KEEP_ALIVE -> com.reau.app.ui.KeepAliveRoute(activity, back)
        SettingsPage.CREDITS -> com.reau.app.ui.CreditsScreen(back) { copyAddress(activity, it) }
        SettingsPage.TERMS -> com.reau.app.ui.TermsScreen(gate = false, onAccept = {}, onDecline = {}, onBack = back)
        SettingsPage.LOGS -> com.reau.app.ui.LogViewerScreen(RideDiagnostics.logDir(), onBack) {
            DiagnosticsExport.shareFrom(activity, withRecordings = false)
        }
        else -> PermissionsPage(activity, rows, locationAllowed, functions, goTo, onBack)
    }
}

@androidx.compose.runtime.Composable
private fun PermissionsPage(
    activity: MainActivity,
    rows: List<com.reau.app.settings.AppPermissions.Row>,
    locationAllowed: Boolean,
    functions: com.reau.app.settings.AppPermissions.Functions,
    goTo: (SettingsPage) -> Unit,
    onBack: () -> Unit,
) {
    com.reau.app.ui.PermissionsScreen(
        rows,
        com.reau.app.ui.PermissionsActions(
            onBack = onBack,
            onOpenAppSettings = { PermissionRows.openAppSettings(activity) },
            onAsk = { ask ->
                when (ask) {
                    is com.reau.app.settings.AppPermissions.Ask.Runtime -> activity.askRuntime(ask.permissions)
                    com.reau.app.settings.AppPermissions.Ask.Battery -> com.reau.app.ui.BatteryWatch.askExemption(activity)
                }
            },
            onAskAll = activity.askRuntime,
            onOpenKeepAlive = { goTo(SettingsPage.KEEP_ALIVE) },
        ),
        locationAllowed = locationAllowed,
        functions = functions,
    )
}

/** The permissions page's rows as Android has them now, and Android's page of REau's own permissions. */
private object PermissionRows {
    fun read(context: android.content.Context): List<com.reau.app.settings.AppPermissions.Row> {
        val requested = runCatching {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        }.getOrDefault(emptyList())
        val power = context.getSystemService(android.os.PowerManager::class.java)
        return com.reau.app.settings.AppPermissions.rows(
            requested = requested,
            granted = { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
            batteryExempt = runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) == true }.getOrDefault(false),
        )
    }

    fun openAppSettings(context: android.content.Context) {
        val uri = android.net.Uri.fromParts("package", context.packageName, null)
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** The Android Auto screen with the touch, the mask and the fit (which lives there now) of the settings. */
@androidx.compose.runtime.Composable
private fun AndroidAutoFromSettings(settings: REauSettings, save: (REauSettings) -> Unit, store: SettingsStore, onBack: () -> Unit) {
    // The screen Android Auto shows; the mask shows the fit in force on it, the fit opens on its tab
    val screen = com.reau.app.aa.AaScreens.watch.screen.collectAsStateWithLifecycle().value
    // The fit assistant opens by itself only on a phone that never saved a fit; asked once, as the screen opens.
    val assistantPending = remember { store.fitAssistantPending() }
    AndroidAutoScreen(
        onBack = onBack, touch = settings.phoneTouch, mask = maskOf(settings, save, screen),
        fit = com.reau.app.ui.AaScreenFit(settings, screen, com.reau.app.ui.FitAssistantAsk(assistantPending, store::fitAssistantSeen), save),
        stats = settings.overlay,
        button = settings.buttonLook,
    )
}

/** The dash's mask on the Android Auto screen follows its switch and the fit saved in the settings. */
private fun maskOf(settings: REauSettings, save: (REauSettings) -> Unit, screen: com.reau.app.aa.AaScreen) =
    AaScreenMask(settings.dashMask, settings.mappingFor(screen), com.reau.app.aa.AaVideo.WIDTH, com.reau.app.aa.AaVideo.HEIGHT, settings.frameSize) {
        save(settings.copy(dashMask = it))
    }
