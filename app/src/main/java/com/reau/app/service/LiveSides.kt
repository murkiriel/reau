package com.reau.app.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Network
import android.os.PowerManager
import android.os.StatFs
import androidx.core.net.toUri
import com.reau.app.R
import com.reau.app.aa.AaConfig
import com.reau.app.aa.AaLog
import com.reau.app.aa.AaLinkState
import com.reau.app.aa.AaReceiver
import com.reau.app.aa.JoystickBindings
import com.reau.app.aa.JoystickMap
import com.reau.app.phone.PhoneCalls
import com.reau.app.phone.PhoneVoice
import com.reau.app.aa.VoiceSource
import com.reau.app.phone.CallState
import com.reau.app.aa.AaCommand
import com.reau.app.aa.NavGuidance
import com.reau.app.card.CardPainter
import com.reau.app.dash.DashPairing
import com.reau.app.dash.DashPorts
import com.reau.app.dash.DashReadings
import com.reau.app.dash.DashSession
import com.reau.app.dash.DashSocket
import com.reau.app.dash.DashWifiManager
import com.reau.app.dash.DashWifiSuggestions
import com.reau.app.dash.nav.AaManeuvers
import com.reau.app.dash.nav.DrivingSide
import com.reau.app.dash.nav.DrivingSideSource
import com.reau.app.data.RideDiagnostics
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsStore
import com.reau.app.video.AaMargins
import com.reau.app.stream.VideoLoop
import com.reau.app.video.DashCompositor
import com.reau.app.video.DashGlass
import com.reau.app.video.FrameFit
import com.reau.app.video.PhonePreview
import com.reau.app.video.RectPx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

/**
 * The real sides of the projection, all inside the service:
 *
 *     Android Auto ──▶ AaReceiver (decoder) ──▶ DashCompositor ──▶ VideoLoop (encoder, RTP) ──▶ DashSession ──▶ dash
 *
 * The compositor is made once, with the sides, and lives as long as they do: Android Auto's decoder draws into it
 * whether or not a dash is there, and the video loop takes from it whether or not Android Auto is (it repeats the
 * last picture). So each side comes and goes on its own, as [ProjectionMachine] says.
 *
 * Both sides report to the machine through [report]: the receiver its [AaReceiver] states, the dash session its
 * states read by [ProjectionMachine.linkOf]. All the methods are called on the service's worker, one at a time.
 */
@Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
internal class LiveSides(
    context: Context,
    private val report: (ProjectionEvent) -> Unit,
    private val setup: Setup,
) : ProjectionSides {
    /**
     * What differs between the phone and a test: [dashSocket] makes the dash session's socket (the real one sends to
     * 192.168.1.1), [linkUp] gets onto the bike's Wi-Fi.
     */
    data class Setup(
        val aaConfig: AaConfig = AaConfig(),
        val aaOptions: AaReceiver.Options = AaReceiver.Options(),
        val video: VideoLoop.Settings = VideoLoop.Settings(),
        val dashSocket: (Network?) -> DashSocket = { DashSocket(it) },
        val linkUp: (Context, CoroutineScope) -> DashLinkUp,
        /** Which part of Android Auto's picture goes where on the dash; null keeps the compositor's own (fill the dome). */
        val mapping: FrameFit.Mapping? = null,
        val card: (() -> Bitmap)? = null,
        /** Whether no other app holds the dash's port; checked before every try to reach the dash. */
        val dashPortFree: () -> Boolean = { DashPorts.isFree(DashPorts.DASH_RX_PORT) },
        /** Day or night for Android Auto's night sensor, asked every [nightCheckMs] while the sides live. */
        val night: () -> Boolean = { false },
        /** Whether the phone has a working network, for the lost-signal icon while recalculating. */
        val online: () -> Boolean = { true },
        val nightCheckMs: Long = NIGHT_CHECK_MS,
        /** The phone's thermal status (PowerManager's), for the video loop's hot ceiling. */
        val thermalStatus: () -> Int = { VideoLoop.THERMAL_UNKNOWN },
        /** The call as the joystick sees it, and answering or ending it (behind a switch). */
        val calls: Calls = Calls(),
        /** The phone's volume for the joystick's volume actions; none in a test (the actions then do nothing). */
        val volume: com.reau.app.phone.PhoneVolume? = null,
        /** The rider's settings these sides were built from: what a change while running is told apart from. */
        val settings: REauSettings? = null,
        /** How long after the joystick Android Auto's blue focus is touched away (shorter in a test). */
        val focusIdleMs: Long = com.reau.app.aa.FocusClearer.IDLE_MS,
        /** What the dash's info ring says now (null: no ring); asked every second, behind the rider's switch. */
        val infoRing: (com.reau.app.weather.InfoRingRotation) -> com.reau.app.weather.RingFrame? = { null },
        /** What the title of the dash's gold band can say in place of the ring ("Informações no título"), from the settings now; null: nothing. */
        val infoTitle: (REauSettings) -> com.reau.app.dash.TitleSource? = { null },
        /** The phone's own state for the dash's heartbeat (behind the switch "Mandar o estado do celular ao painel"). */
        val phoneStatus: () -> com.reau.app.dash.protocol.DashCommands.PhoneStatus? = { null },
        /** The weather for the dash's heartbeat (behind "Clima no painel"), and the refresh of it; none in a test. */
        val weather: () -> com.reau.app.dash.protocol.DashCommands.Weather? = { null },
        val refreshWeather: suspend () -> Unit = {},
        /** Where what the dash says is kept for the screen "Dados da moto"; null in a test. */
        val readings: com.reau.app.dash.DashReadingSink? = null,
        /** Where the step of the two rings' turns ([com.reau.app.dash.RingCycle]) is kept from one projection to the next; null in a test. */
        val ringStep: com.reau.app.dash.RingStepMemory? = null,
        /** Where the joystick's zoom is kept from one projection to the next; null in a test. */
        val zoomMemory: com.reau.app.video.ZoomMemory? = null,
    ) {
        class Calls(
            val state: () -> CallState = { CallState.IDLE },
            val answer: () -> Unit = {},
            val end: () -> Unit = {},
            val volume: (Int) -> Unit = {},
        )

        companion object {
            /**
             * How many units of each stream the video recording keeps: about 15 min at the dash's 4 fps (2000, about
             * 10 min, once stopped a ride's recording 6 min before its end).
             */
            private const val RECORD_FRAMES = 3_000

            /** How often day and night are asked again: dusk moves a degree of the sun in about four minutes. */
            private const val NIGHT_CHECK_MS = 60_000L

            /**
             * On the phone, with the rider's saved settings ([SettingsStore]): the bike's Wi-Fi by [DashWifiManager],
             * the dash's name from [DashPairing], the optional recordings in the app's own internal folder (`video`,
             * `sessions`), which only a debug build's `adb shell run-as` reaches; a release shares them from the settings.
             */
            /** The rider's switch read at each press (it applies at once), the permissions and the call from [PhoneCalls]. */
            private fun phoneCalls(context: Context): Calls {
                val phone = PhoneCalls(context)
                val store = SettingsStore.open(context)
                return Calls(
                    state = { PhoneCalls.visibleState(store.load().answerCalls, phone.permitted(), phone.state()) },
                    answer = { phone.answer() },
                    end = { phone.end() },
                    volume = { phone.adjustCallVolume(it) },
                )
            }

            fun forPhone(context: Context): Setup {
                // The browser server is always off at the start of a run, whatever the last one left.
                com.reau.app.web.WebSwitch.atStart(SettingsStore.open(context)) { RideDiagnostics.log("projection", it) }
                val settings = SettingsStore.open(context).load()
                val folder: File? = context.filesDir // for privacy: internal storage (AppFolders)
                val recording = folder?.takeIf { settings.recordSessions }?.let {
                    // The ring is the build's (100 MB; in a debug build up to 768 MB), less when the phone has little free space.
                    val ring = com.reau.app.data.RecordingsSpace.ringBytes(runCatching { StatFs(it.path).availableBytes }.getOrDefault(0L))
                    AaReceiver.Recording(File(it, "sessions"), ring)
                }
                // Gemini (behind its switch): the voice from the headset or the phone.
                val voice: (() -> VoiceSource)? = { PhoneVoice(context) { RideDiagnostics.log("aa", it) } }
                    .takeIf { settings.gemini }
                return Setup(
                    dashSocket = { DashSocket(it) },
                    aaConfig = settings.aaConfig,
                    aaOptions = AaReceiver.Options(
                        // Android Auto 17.3 or older: it dials in, asked by a broadcast. With a newer one the receiver dials the
                        // developer server, which the rider switches on by hand.
                        callIn = if (com.reau.app.aa.AndroidAuto.dialsIn(com.reau.app.aa.AndroidAuto.installedVersion(context))) {
                            AaReceiver.CallIn { com.reau.app.aa.AaCallsIn.poke(context) { RideDiagnostics.log("aa", it) } }
                        } else {
                            null
                        },
                        recording = recording,
                        voice = voice,
                        // Only the first projection of this process: the exit Android keeps stays the newest until the
                        // next one, and a clean Desligar and Ligar a minute later was taken for a fresh install again.
                        quickUnresponsive = com.reau.app.aa.FirstOfProcess.projection.take() &&
                            com.reau.app.data.ExitReasons.leftAndroidAutoHanging(
                                com.reau.app.data.ExitHistory.newest(context), System.currentTimeMillis(),
                            ),
                    ),
                    video = VideoLoop.Settings(
                        recordFrames = if (settings.recordVideo && folder != null) RECORD_FRAMES else 0,
                        recordDir = folder?.let { File(it, "video") },
                        recordRolling = true,
                    ).withRider(settings),
                    linkUp = { ctx, scope -> phoneLinkUp(ctx, scope) },
                    mapping = settings.mappingFor(com.reau.app.aa.AaScreens.watch.screen.value),
                    online = { phoneOnline(context) },
                    // The theme read at each ask, so a change while running is followed.
                    night = { isNightNow(context, SettingsStore.open(context).load()) },
                    thermalStatus = { phoneThermalStatus(context) },
                    calls = phoneCalls(context),
                    volume = com.reau.app.phone.PhoneVolume(com.reau.app.phone.AudioVolume(context)),
                    settings = settings,
                    phoneStatus = PhoneStatusReader(context)::read,
                    weather = { com.reau.app.weather.WeatherKeeper.forDash() },
                    infoRing = { rotation ->
                        val now = SettingsStore.open(context).load()
                        InfoRingFeed.frame(context, rotation, now.useLocation, night = isNightNow(context, now), options = now.infoRingOptions)
                    },
                    infoTitle = { now -> InfoRingFeed.titleSource(context, now.useLocation, now.infoRingOptions) },
                    refreshWeather = { com.reau.app.weather.WeatherKeeper.refreshIfStale(context, respectBackoff = true) },
                    readings = com.reau.app.dash.DashReadings,
                    ringStep = PrefsRingStep(context),
                    zoomMemory = PrefsZoom(context),
                )
            }

            /** Day or night as the rider's map theme says: the place learned on the screen with "Usar a localização" on, else the time zone. */
            private fun isNightNow(context: Context, settings: REauSettings): Boolean =
                DayNight.isNightNow(settings.mapTheme, SunPlace.saved(context, settings.useLocation))

            private fun phoneThermalStatus(context: Context): Int =
                runCatching { context.getSystemService(PowerManager::class.java).currentThermalStatus }.getOrDefault(VideoLoop.THERMAL_UNKNOWN)

            /**
             * The card mode on the phone: as [forPhone], with the picture picked in the settings, or the
             * legibility card if none was picked or it cannot be read.
             */
            fun forCard(context: Context): Setup {
                val image = SettingsStore.open(context).load().cardImage
                return forPhone(context).copy(card = { cardPicture(context, image) })
            }

            private fun cardPicture(context: Context, image: String?): Bitmap {
                // The default card is named with the full edition's id; it is this app's own drawable, whichever edition runs.
                val uri = if (image == com.reau.app.settings.REauSettings.DEFAULT_CARD) {
                    "android.resource://${context.packageName}/drawable/grade_medicao"
                } else {
                    image
                }
                val picked = uri?.let { CardPainter.load(context, it.toUri()) }
                if (image != null && picked == null) {
                    RideDiagnostics.warn("projection", "the picked card picture could not be read, the legibility card goes: $image")
                }
                return picked?.let(CardPainter::picture) ?: CardPainter.legibility(context.getString(R.string.card_legibility_sample))
            }

            private fun phoneLinkUp(context: Context, scope: CoroutineScope): DashLinkUp {
                val pairing = DashPairing.open(context)
                val wifi = DashWifiManager(context, scope, pairing)
                return WifiDashLinkUp(
                    scope, wifi.state, { wifi.network },
                    { ssid, password, prefix -> wifi.connect(ssid, password, prefix) },
                    wifi::disconnect,
                    object : WifiDashLinkUp.Pairing {
                        override val ssid: String get() = pairing.ssid
                        override val ssidPrefix: String get() = pairing.ssidPrefix
                        override val password: String get() = pairing.password
                        override val joinedSsid: String get() = pairing.joinedSsid
                        override fun remember(ssid: String) {
                            pairing.ssid = ssid
                            DashWifiSuggestions.sync(context) // the suggestion follows the name learned
                        }

                        // The Wi-Fi list by the location permission alone, whatever the rider's switch "Usar a localização" says (LocationUse).
                        override fun discover(prefix: String): String? =
                            if (PhonePlace.mayScan(context)) wifi.findDashSsid(prefix) else null

                        override fun seenInScan(wanted: String, isPrefix: Boolean): Boolean =
                            PhonePlace.mayScan(context) && wifi.seenInLastScan(wanted, isPrefix)
                    },
                    // With the phone's Wi-Fi radio off no request for the dash's network is made (WifiDashLinkUp.start).
                    radioOn = { wifi.isWifiEnabled() },
                ).apply {
                    onBindResult = wifi::bindResult
                    wifi.onNetworkAvailable = { bikeNetworkAvailable() }
                    // A dash that stays in the scan and refuses every join (its Wi-Fi hung): the rider is told what clears it.
                    // The notice is a sound on the phone, which a helmet and a jacket may muffle: three beeps also go to the phone's output (the headset).
                    wifi.onDashStuck = { stuck ->
                        if (stuck) {
                            ProjectionNotification.showDashStuck(context)
                            com.reau.app.aa.StuckDashTone.play(scope, log = { RideDiagnostics.log("audio", it) })
                            // And, if the rider asked for it, said in words in the headset (RiderVoice: off by default).
                            RiderVoice.speak(VoiceCue.DASH_GONE)
                        } else {
                            ProjectionNotification.clearDashStuck(context)
                        }
                    }
                    // Android refusing the dash's network again and again: the rider is told what to look at.
                    wifi.onBindRefused = { refused ->
                        if (refused) ProjectionNotification.showBindRefused(context) else ProjectionNotification.clearBindRefused(context)
                    }
                    // A reconnection the manager has under way is joined, not replaced.
                    joining = WifiDashLinkUp.Joining({ wifi.reconnectUnderWay() }, log = { RideDiagnostics.log("wifi", it) })
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // When the joystick last went to Android Auto, to touch its blue focus away a minute later.
    private val focus = com.reau.app.aa.FocusClearer(setup.focusIdleMs)
    private val compositor = DashCompositor { RideDiagnostics.log("compositor", it) }

    // The stats text of the dash's video and of the preview: Android Auto's own numbers ("app") until the
    // dash is connected, then the stream's to it (labelled "dash"); it reaches the compositor and the preview screen.
    // Its few words come from the service's resources: the language the rider chose.
    private val statsFeed = com.reau.app.video.StatsFeed(
        com.reau.app.video.StatsWords(
            panel = context.getString(R.string.stats_dash), defaultMode = context.getString(R.string.stats_default_mode),
            frameUnit = context.getString(R.string.stats_frame_unit), keyframe = context.getString(R.string.stats_keyframe),
        ),
    ) {
        com.reau.app.video.StatsOverlay.latest = it
        compositor.setOverlayText(it)
    }
    private val receiver: AaReceiver?

    // The joystick's "switch map and music": the key for the destination is chosen at the tap, by the screen the watch settled on ([com.reau.app.aa.SwapMapMusic]).
    private val swapMapMusic = com.reau.app.aa.SwapMapMusic(
        screen = { com.reau.app.aa.AaScreens.watch.screen.value },
        app = { com.reau.app.aa.AaScreens.watch.app.value },
        log = { RideDiagnostics.log("dash", it) },
    )

    // The phone's real state in the heartbeat while the rider's switch is on; `tuned` is read at each beat.
    private val session = DashSession(
        scope, socketFactory = setup.dashSocket, linkProbe = com.reau.app.dash.PhoneLinkProbe(context)::read,
        phoneStatusProvider = {
            if (tuned?.dashPhoneStatus == true) setup.phoneStatus()?.let { PhoneStatusReader.withLocationRule(it, tuned?.useLocation == true) } else null
        },
        weatherProvider = { if (tuned?.dashWeather == true) setup.weather() else null },
        readings = setup.readings,
    )
    // The listener of the dash's sighting is this one's own, so the release gives back only what this instance took (a next one may hold it by then).
    private val sightingListener: () -> Unit = { report(ProjectionEvent.DashSighted) }

    // The same for the phone's Wi-Fi radio, as Android's broadcast tells it (SystemEvents): the radio on again tries the dash at once.
    private val radioListener: (Boolean) -> Unit = { on -> report(ProjectionEvent.WifiRadio(on)) }
    private val linkUp = setup.linkUp(context, scope).also { up ->
        up.onBikeNetwork {
            // The dash's Wi-Fi back as another Android network: the session's sockets are bound to the one that went, so the session is lost
            // first (NetworkSwitch) and the machine, told of the Wi-Fi as always, opens the next one on the new network.
            session.networkNow(up.bikeNetwork)
            report(ProjectionEvent.BikeNetworkAvailable)
        }
        com.reau.app.dash.DashSighting.listener = sightingListener
        WifiRadio.listener = radioListener
        up.onWifiRadio(radioListener)
        up.onFirstJoin { report(ProjectionEvent.DashFirstJoin(it)) }
        session.onBindResult = { network, refused -> up.onBindResult?.invoke(network, refused) }
    }
    // The album's picture for the dash's music card (behind its switch), converted once per picture.
    private val albumArt = com.reau.app.dash.AlbumArt()

    // The running video loop, behind the rule of who may put one there: a new pace comes from another thread than the panel's session (LoopSlot).
    private val loops = LoopSlot<VideoLoop> { it.stop() }
    private val appContext = context.applicationContext

    // The bike's speed for the stream's log, from Android Auto's route.
    private val navSpeed = com.reau.app.stream.NavSpeed()

    // What the one badge on the dash's video may say: the picture is stuck (watchAppVideo) or Android Auto is gone (watchWaiting). Waiting wins.
    @Volatile private var badgeStale = false
    @Volatile private var badgeWaiting = false

    private fun showBadge() {
        val text = when {
            badgeWaiting -> appContext.getString(R.string.waiting_badge)
            badgeStale -> appContext.getString(R.string.stale_badge)
            else -> null
        }
        compositor.setStaleBadge(text, leavesWithPicture = badgeWaiting)
    }

    // The distances to the dash in miles and feet or in metres: what the dash says it is set to, else the country of the phone's own locales (not the app's language); said in the log when it changes.
    private var navImperialSaid: Boolean? = null
    private fun navImperial(): Boolean {
        val panel = DashReadings.settings.value.distance
        val region = com.reau.app.dash.nav.NavUnits.phoneRegion(appContext)
        val imperial = com.reau.app.dash.nav.NavUnits.imperial(panel, region)
        if (imperial != navImperialSaid) {
            navImperialSaid = imperial
            RideDiagnostics.log("dash", com.reau.app.dash.nav.NavUnits.line(imperial, panel, region))
        }
        return imperial
    }

    // The side of the road for the roundabouts Android Auto gives no direction to: the rider's choice or, in automatic, the country of the mobile network
    // (else the SIM's), read when the projection starts and again every ten minutes while guidance goes on; the log says the side and where it came from, never the country.
    private val drivingSide = DrivingSideSource(
        clockMs = { android.os.SystemClock.elapsedRealtime() },
        networkCountry = { appContext.getSystemService(android.telephony.TelephonyManager::class.java)?.networkCountryIso },
        simCountry = { appContext.getSystemService(android.telephony.TelephonyManager::class.java)?.simCountryIso },
        log = { RideDiagnostics.log("dash", it) },
        mode = { tuned?.drivingSideMode ?: com.reau.app.settings.DrivingSideMode.AUTO },
    )

    // The latest guidance snapshot, for the maneuver distance (navSpeed above already reads the same feed).
    @Volatile private var guidanceNow: NavGuidance = NavGuidance.INACTIVE
    private val started: Boolean

    // What changes while running (retune): all on the service's worker thread, except
    // the pending fit, which the receiver's thread takes when Android Auto's new session is up.
    @Volatile private var tuned: REauSettings? = setup.settings
    @Volatile private var aaConfig: AaConfig = setup.aaConfig
    @Volatile private var videoSettings: VideoLoop.Settings = setup.video

    /**
     * The area the stream really runs in when it is not the one the settings ask for: no encoder took the 528x304 and the loop fell back to the dash's 526x300
     * ([VideoLoop.frame]). The fits are then made for it, as the dash's own are ([fitOf]); null while the stream runs in the area of the settings.
     */
    @Volatile private var fallbackArea: com.reau.app.dash.DashConfig.FrameSize? = null

    /** [settings] for the area the stream really runs in ([fallbackArea]): the area decides the fit. */
    private fun inStreamArea(settings: REauSettings): REauSettings = fallbackArea?.let { settings.copy(videoFrame = it.id) } ?: settings

    /** The fit [settings] ask for [screen], made for the area the stream really runs in. */
    private fun fitOf(settings: REauSettings, screen: com.reau.app.aa.AaScreen = com.reau.app.aa.AaScreens.watch.screen.value): FrameFit.Mapping =
        inStreamArea(settings).mappingFor(screen)
    @Volatile private var pendingMapping: FrameFit.Mapping? = null
    private val live = Live()

    /** A finger on the preview screen, scaled into the video and sent to Android Auto as a touch. */
    private val stroke = com.reau.app.aa.PhoneTouch.Stroke()
    private val touchTarget: (com.reau.app.aa.PhoneTouch, PhonePreview.Shown?) -> Unit = { touch, shown ->
        val (w, h) = com.reau.app.aa.AaVideo.WIDTH to com.reau.app.aa.AaVideo.HEIGHT
        val viewport = shown?.let { com.reau.app.video.CompositorMath.previewViewport(w, h, it.width, it.height) }
        val sent = viewport?.let { stroke.next(touch, it, w, h) }
        if (sent != null) receiver?.sendInput(AaCommand.Touch(sent.action, sent.x, sent.y))
    }

    /** What the preview screen shows goes to the compositor (or leaves it). */
    private val previewTarget: (PhonePreview.Shown?) -> Unit = { shown ->
        val surface = shown?.surface as? android.view.Surface
        if (shown == null || surface == null) compositor.clearPreview() else compositor.setPreview(surface, shown.width, shown.height)
    }

    // The joystick of the running projection, for a page changed while it runs. Declared BEFORE the init
    // block that sets it: Kotlin runs initializers in the order of the text, so below it the `= null` could run after
    // the init and clear the map, and a page changed while the projection runs would be dropped.
    @Volatile private var joystick: JoystickMap? = null

    // The joystick's legend on the dash's video ([com.reau.app.dash.ActionLegend]): 3 s, then the compositor takes it off. Declared before the init
    // block too, which sets the switch and shows it.
    private val legend: com.reau.app.dash.TimedShow<com.reau.app.dash.LegendCard> = com.reau.app.dash.TimedShow(
        com.reau.app.dash.ActionLegend.SHOW_MS,
        schedule = { delayMs, action ->
            val job = scope.launch {
                delay(delayMs)
                action()
            }
            ({ job.cancel() })
        },
        changed = ::legendChanged,
    )

    /** What the legend shows changed (a card came, or its time ran out): the compositor draws it or takes it off. */
    private fun legendChanged() {
        compositor.setLegend(legend.current())
    }

    // The volume card on the dash's video ([com.reau.app.dash.VolumeCards]): 2 s after the last step, then the compositor takes it off. Declared before the
    // init block too, which sets the switch and starts the watch.
    private val volumeCard: com.reau.app.dash.TimedShow<com.reau.app.dash.VolumeNotice> = com.reau.app.dash.TimedShow<com.reau.app.dash.VolumeNotice>(
        com.reau.app.dash.VolumeCards.SHOW_MS,
        schedule = { delayMs, action ->
            val job = scope.launch {
                delay(delayMs)
                action()
            }
            ({ job.cancel() })
        },
        changed = ::volumeCardChanged,
    ).also { it.enabled = false } // off until the settings say so: with none (a test) there is no card and the title keeps its echo

    /** What the volume card shows changed (a step came, or its time ran out): the compositor draws it or takes it off, at once even with the map still. */
    private fun volumeCardChanged() {
        compositor.setVolumeCard(volumeCard.current())
    }

    /** The system's volume broadcast, listened to while this projection runs (started in the init block, given back in [release]). */
    private val volumeWatch = VolumeWatch(context, ::volumeStepHeard)

    /** The phone's volume moved ([step]): the card says it, and the log says the level (no song, no name). Nothing when the card is off. */
    private fun volumeStepHeard(step: com.reau.app.dash.VolumeCards.Step) {
        val text = com.reau.app.dash.ActionEchoText.volumeCard(appContext, step.card)
        if (volumeCard.show(com.reau.app.dash.VolumeNotice(step.card, text))) RideDiagnostics.log("dash", step.logLine())
    }

    // The map's zoom from the joystick, over the fit last asked for (every remap goes through it); each change is kept and the next
    // projection starts from it.
    private val liveZoom = com.reau.app.video.LiveZoom(setup.zoomMemory)
    @Volatile private var lastFit: FrameFit.Mapping? = null

    // The web server and what it needs: declared before the init block for the same reason as above.
    @Volatile private var web: com.reau.app.web.WebBridge? = null
    private val webDelayer = com.reau.app.web.ExecutorDelayer()
    @Volatile private var audioPlayer: com.reau.app.aa.AaAudioPlayer? = null
    private val soundHeard = java.util.concurrent.ConcurrentHashMap.newKeySet<com.reau.app.aa.AaAudioFeed.Stream>()

    /** The phone's own speaker for Android Auto's sound, made with the first sound that is to play on it. */
    private fun phoneSpeaker(): com.reau.app.aa.AaAudioPlayer = audioPlayer ?: synchronized(this) {
        audioPlayer ?: com.reau.app.aa.AaAudioPlayer { RideDiagnostics.log("audio", it) }.also { audioPlayer = it }
    }
    private val webPage by lazy { com.reau.app.web.WebPage.load(appContext) }

    init {
        AaLog.sink = { RideDiagnostics.log("aa", it) }
        AaLog.phoneNow = { com.reau.app.data.PhoneNow.text() }
        val card = setup.card
        if (card != null) {
            // The card is drawn at the dash's own size and goes to it pixel for pixel, at the top left of the stream's area.
            started = compositor.start(DashGlass.FRAME_W, DashGlass.FRAME_H, setup.video.frame) && compositor.setMapping(WHOLE_FRAME)
            receiver = null
        } else {
            started = compositor.start(com.reau.app.aa.AaVideo.WIDTH, com.reau.app.aa.AaVideo.HEIGHT, setup.video.frame)
            val surface = compositor.inputSurface.takeIf { started }
            setup.mapping?.let {
                lastFit = it
                if (started && !compositor.setMapping(it)) RideDiagnostics.warn("projection", "the saved fit was refused, the dome is filled: $it")
            }
            drivingSide.refresh()
            // Behind the switch: Android Auto's guidance goes to the dash's own nav bubble.
            // The route also gives the speed for the stream's log.
            val noNetwork = NoNetworkWatch(clockMs = { android.os.SystemClock.elapsedRealtime() }, online = setup.online)
            val show: (com.reau.app.aa.NavGuidance) -> Unit = {
                guidanceNow = it
                navSpeed.onGuidance(it, android.os.SystemClock.elapsedRealtime())
                session.showGuidance(it, noNetwork = noNetwork.without(), imperial = navImperial(), drivingSide = drivingSide.side())
            }
            val guidance: (com.reau.app.aa.NavGuidance) -> Unit = show
            var options = if (setup.aaConfig.navigation) setup.aaOptions.copy(onNavigation = guidance) else setup.aaOptions
            // Behind their switches: the song and the ringing call go to the dash's own cards.
            if (setup.aaConfig.media) {
                options = options.copy(onNowPlaying = { session.showNowPlayingCard(it, if (tuned?.dashAlbumArt == true) albumArt else null) })
            }
            // Each change of the call also says where its sound goes (the phone, the speaker, a Bluetooth headset),
            // for testing a helmet communicator.
            if (setup.aaConfig.phone) {
                options = options.copy(onCaller = { caller ->
                    session.showCallerCard(caller, context.getString(R.string.caller_private))
                    RideDiagnostics.log("call", com.reau.app.phone.CallAudioRoute.describe(context))
                })
            }
            // The web server's sound: with the media and guidance sinks announced, Android Auto sends REau its sound;
            // it goes on the phone (both) and/or to the browser, as the mode says.
            // Always listening, since the switch can be turned on while running (a new session then announces the sinks): sound only comes
            // when they are announced, and the phone's speaker is only made with the first sound that is to play on it.
            // Android Auto's session open with no picture: the screen and the notification say so (the dash is black).
            options = options.copy(onVideoSilent = { silent -> report(ProjectionEvent.AndroidAutoVideo(silent)) })
            // The decoder draws into the compositor: when the compositor is the one stuck, the decoder's silence is not its own fault.
            options = options.copy(downstreamBusyMs = compositor::busyMs)
            options = options.copy(onAudio = { pcm ->
                if (soundHeard.add(pcm.stream)) RideDiagnostics.log("audio", "the first sound of ${pcm.stream} arrived: ${pcm.data.size} bytes")
                val now = tuned
                if (now?.soundOnPhoneViaReau == true) phoneSpeaker().play(pcm)
                if (now?.audioSinks == true) web?.audio(pcm)
            })
            receiver = surface?.let {
                AaReceiver(context, setup.aaConfig, it, { state ->
                    if (state == AaLinkState.ACTIVE) live.applyPendingFit()
                    report(ProjectionEvent.AndroidAutoChanged(state))
                    web?.androidAutoChanged(state == AaLinkState.ACTIVE)
                }, options)
            }
            receiver?.let { live.watchNight(it) }
            // The web server, behind its switch: Android Auto's picture and touch for a browser on the local network.
            if (started && receiver != null) {
                web = com.reau.app.web.WebBridge(
                    com.reau.app.web.WebBridge.Env(
                        page = { webPage },
                        video = { out ->
                            com.reau.app.web.WebVideoOutput(compositor, out) { RideDiagnostics.log("web", it) }
                        },
                        sendTouch = { receiver?.sendInput(AaCommand.Touch(it.action, it.x, it.y)) },
                        delayer = webDelayer,
                        log = { RideDiagnostics.log("web", it) },
                    ),
                ).also { bridge -> setup.settings?.let { bridge.apply(it.webView, it.webTouch, it.webAudio) } }
            }
            // The screen "Tela do Android Auto" shows the picture while this runs.
            if (started) PhonePreview.attach(previewTarget)
            // Behind the switch: a finger on that screen touches Android Auto.
            if (started && setup.aaConfig.touch) PhonePreview.attachTouch(touchTarget)
        }
        if (!started) RideDiagnostics.error("projection", "the compositor did not start: no picture, no video")
        setup.settings?.let {
            if (started) {
                compositor.setOverlay(it.overlay)
                compositor.setButtonOverlay(it.buttonLook)
            }
        }
        if (started && receiver != null) {
            scope.launch { watchAppVideo() }
            scope.launch { watchWaiting() }
            // The volume card listens only while this projection runs, and only in the real app (a test has no settings).
            if (setup.settings != null && !volumeWatch.start()) RideDiagnostics.warn("projection", "the volume card cannot listen to the phone's volume")
        }
        scope.launch { ProjectionMachine.dashLinks(session.state).collect { report(ProjectionEvent.DashChanged(it)) } }
        // The weather for the dash: looked at every minute, fetched at most every quarter of an hour, and after a failure with a growing wait (WeatherBackoff).
        scope.launch {
            while (isActive) {
                if (tuned?.weatherWanted == true) runCatching { setup.refreshWeather() }
                delay(WEATHER_POLL_MS)
            }
        }
        // The info ring on the dash's video, behind its switch and shown or hidden by the turns of the two rings ([DashSession.infoVisible]):
        // that is looked at twenty times a second (a press of the dash's down button moves the turns), what it says once a second; the
        // compositor draws it only when it changes.
        scope.launch {
            val rotation = com.reau.app.weather.InfoRingRotation()
            val ring = InfoRingFeed.Loop(RING_TICKS_PER_LOOK, stopGps = { GpsWatch.stop(GpsOwner.RING) }, warn = { RideDiagnostics.warn("ring", it) })
            var inPlace = false
            val posts = InfoRingFeed.Posts()
            while (isActive) {
                val place = tuned?.infoRingInPlace == true
                if (place != inPlace) {
                    inPlace = place
                    compositor.setInfoRingPlace(place)
                }
                // With "Informações no título" the pages are in the dash's title and the ring is not drawn (one or the other).
                val ringWanted = InfoRingFeed.ringDrawn(tuned?.infoRing == true, tuned?.infoTitle == true)
                val frame = ring.step(started && InfoRingFeed.shown(ringWanted, session.infoVisible)) { setup.infoRing(rotation) }
                if (posts.changed(frame)) compositor.setInfoRing(frame)
                delay(InfoRingFeed.pollMs(ringWanted))
            }
        }
        // The app menu's own fit, as Android Auto changes screen (the first value is the screen now)
        val follower = FitFollower({ tuned?.let(::inStreamArea) }, ::remap) { RideDiagnostics.log("projection", it) }
        if (setup.card == null) scope.launch { com.reau.app.aa.AaScreens.watch.screen.drop(1).collect { follower.on(it); session.ringFollowsScreen(it) } }
        // The dash's joystick drives Android Auto (keys and the wheel); every button is logged, known or not.
        // Gemini only where its microphone was given (the switch "Gemini pelo joystick").
        // The rider's joystick page, one tap one action; the calls on the same sides.
        val joystick = JoystickMap(
            bindings = JoystickBindings.decode(setup.settings?.joystick.orEmpty()),
            assistant = setup.aaOptions.voice != null,
            callState = setup.calls.state,
        ).also { this.joystick = it }
        if (setup.settings != null) {
            session.zoomLabel = joystick.bindings.zoomControls
            session.musicCardAllowed = joystick.bindings.mediaControls
            session.infoRingPress = joystick.bindings.infoRingControl
            session.actionEcho = setup.settings.actionEcho
            legend.enabled = setup.settings.joystickLegend
            volumeCard.enabled = setup.settings.volumeCard
            session.maneuverCircleEnabled = setup.settings.maneuverRing
            session.ringCycleOn = setup.settings.infoRing
            session.infoInPlace = setup.settings.infoRingInPlace
            session.infoTitle.source = { tuned?.let(setup.infoTitle) }
            session.infoTitle.enabled = setup.settings.infoRing && setup.settings.infoTitle
            session.ringStepMemory = setup.ringStep
            session.ringStep = setup.ringStep?.step ?: 0
            session.ringFollowsScreen(com.reau.app.aa.AaScreens.watch.screen.value)
            // What the picture looks like says the screen where the displays did not (a select that opened Waze, say).
            compositor.onLook = { look ->
                com.reau.app.aa.AaScreens.watch.look(look, android.os.SystemClock.elapsedRealtime(), com.reau.app.aa.AaApp.of(compositor.lookedScreen))
            }
        }
        // The music mode (Spotify in front and playing): fed by every change of the screen, the app and the media status, so the moment the music
        // stopped is exact; the joystick asks it at each press. Its switching on and off go to the ride log, with the reason.
        val music = com.reau.app.aa.MusicMode { RideDiagnostics.log("dash", it) }
        joystick.musicActive = music::active
        var musicExpiry: Job? = null
        scope.launch {
            combine(com.reau.app.aa.AaScreens.watch.screen, com.reau.app.aa.AaScreens.watch.app, com.reau.app.aa.AaMedia.watch.now) { screen, app, media ->
                music.update(screen, app, media, com.reau.app.util.MonotonicClock.nowMs())
            }.collect {
                // Spotify in front and playing: the music card goes whatever the page says, with the song (the buttons are the song's).
                session.musicModePlaying = music.playing(com.reau.app.util.MonotonicClock.nowMs())
                // The end of a pause is told to the log when it comes, not at the next press.
                musicExpiry?.cancel()
                music.expiresAtMs()?.let { at ->
                    musicExpiry = launch {
                        delay(at - com.reau.app.util.MonotonicClock.nowMs())
                        music.active(com.reau.app.util.MonotonicClock.nowMs())
                    }
                }
            }
        }
        // What the joystick asked for: the call to the phone, the rest to Android Auto.
        val send: (List<AaCommand>) -> Unit = { commands ->
            commands.forEach { command ->
                when (command) {
                    AaCommand.AnswerCall, AaCommand.EndCall -> callByKeys(command, receiver, setup.calls)
                    is AaCommand.CallVolume -> setup.calls.volume(command.step)
                    is AaCommand.PanelZoom -> panelZoom(command.step)
                    AaCommand.PanelZoomUndo -> panelZoomUndo()
                    is AaCommand.Volume -> volumeStep(command.step, context)
                    // The destination is chosen now, by the screen shown; the key then goes as any other (its echo is read right after, see [swapMapMusic]).
                    AaCommand.SwapMapMusic -> receiver?.sendInput(swapMapMusic.resolve())
                    // The song is about to change: the dash waits for its name to say it in the title ([DashSession.songChangeAsked]), before the key goes so that the metadata cannot come first.
                    is AaCommand.Key -> {
                        if (JoystickMap.isSongKey(command.keycode)) session.songChangeAsked()
                        receiver?.sendInput(command)
                    }
                    else -> receiver?.sendInput(command)
                }
            }
        }
        // Debug only: the maneuver ring going away or coming back is told on the dash's video, under the last button.
        session.onRingChange = { note ->
            ringNote = note
            showButton(com.reau.app.video.ButtonOverlay.latest ?: com.reau.app.video.ButtonText("--", "no button yet"))
        }
        // The music mode's side is held 250 ms ([JoystickMap.HELD_SIDE_MS]): a job waits for it and sends it unless the centre dropped it. One job at a
        // time, started by the press that holds a side.
        var heldJob: Job? = null
        @Suppress("LoopWithTooManyJumpStatements") // the held side's wait: nothing held ends it, a side not yet due waits again
        val releaseHeldSide: () -> Unit = {
            heldJob?.cancel()
            if (joystick.heldDueMs() != null) {
                heldJob = scope.launch {
                    while (true) {
                        val due = joystick.heldDueMs() ?: break
                        val wait = due - com.reau.app.util.MonotonicClock.nowMs()
                        if (wait > 0) {
                            delay(wait)
                        } else {
                            val press = joystick.release(com.reau.app.util.MonotonicClock.nowMs()) ?: continue
                            send(press.sent)
                            if (com.reau.app.aa.FocusClearer.toAndroidAuto(press.sent)) focus.inputSent(android.os.SystemClock.elapsedRealtime())
                            RideDiagnostics.log("dash", "button held side -> ${press.lines.joinToString("; ")}")
                        }
                    }
                }
            }
        }
        session.onButton = { event ->
            // On the dash's receive thread (DashSession): sent first, the log line after.
            val press = joystick.press(event, com.reau.app.util.MonotonicClock.nowMs())
            volumeEcho = null
            send(press.sent)
            // What the tap did, said on the dash after it was sent: its name in the title of the gold band (a volume step says the level it reached instead), and the legend of the mode when the mode is new.
            echoAndLegend(press, joystick, context, volumeEcho)
            if (com.reau.app.aa.FocusClearer.toAndroidAuto(press.sent)) focus.inputSent(android.os.SystemClock.elapsedRealtime())
            releaseHeldSide()
            val call = runCatching { joystick.callForLog().name }.getOrDefault("?")
            if (!JoystickMap.knowsCode(event)) com.reau.app.data.ProjectionSummary.current.unknownButton(event.code)
            RideDiagnostics.log("dash", JoystickMap.logLine(event::class.simpleName.orEmpty(), event.code, press.lines, call))
            // Debug only, behind its switch: the code stays on the dash's video until the next press.
            showButton(com.reau.app.video.ButtonOverlay.textFor(event, press.lines.firstOrNull() ?: "nothing"))
        }
        // Behind its switch, and only with Android Auto told of a touch screen.
        if (setup.card == null && setup.aaConfig.touch && setup.settings?.clearAaFocus == true) {
            val onNavigation = { com.reau.app.aa.AaScreens.watch.screen.value == com.reau.app.aa.AaScreen.NAV }
            clearFocusWhenIdle(scope, focus, setup.focusIdleMs, onNavigation) { receiver?.sendInput(it) }
        }
    }

    /**
     * The name of what [press] did goes to the title of the dash's gold band ([DashSession.echoAction]), and, when the tap is the first of a
     * mode, the legend of that mode to the video ([legend]). Each says a line in the diagnostic log when it shows, with no text of the rider's.
     */
    private fun echoAndLegend(press: JoystickMap.Press, map: JoystickMap, words: Context, volumeText: String? = null) {
        press.echo?.let { named ->
            // The swap's title is the destination ("Music" or "Map"), known only now that the tap chose it ([swapMapMusic]).
            val label = com.reau.app.aa.SwapMapMusic.echoFor(named, swapMapMusic.lastTarget)
            val text = volumeText ?: com.reau.app.dash.ActionEchoText.text(words, label)
            // A song change keeps its name in the title until the new song's name comes (up to the end of the wait).
            val taken = if (label.changesSong) session.echoSongAction(text) else session.echoAction(text)
            if (taken) RideDiagnostics.log("dash", "action echo: ${label.name}")
        }
        press.legend?.let { mode ->
            val card = com.reau.app.dash.ActionLegend.card(map.legend(mode)) { com.reau.app.dash.ActionEchoText.text(words, it) }
            if (legend.show(card)) {
                val acting = card.rows.count { it.text != null }
                RideDiagnostics.log("dash", "joystick legend: ${mode.name.lowercase()} mode, $acting of ${card.rows.size} directions act")
            }
        }
    }

    /** The title's words for the volume step just taken ("Volume 12/30"), set by [volumeStep] while the tap is sent and read right after by its echo. */
    @Volatile private var volumeEcho: String? = null

    /**
     * The joystick's volume action, one step: the phone's music volume, or the call's during a call ([com.reau.app.phone.PhoneVolume]), with no system panel. The
     * dash's status goes at once, so its volume bar moves now and not at the next second; the title says the level reached. The log says the stream and the levels.
     */
    private fun volumeStep(step: Int, words: Context) {
        val change = setup.volume?.step(step, setup.calls.state())
        if (change == null) {
            RideDiagnostics.log("projection", "joystick volume ${if (step > 0) "up" else "down"}: the phone did not take it")
            return
        }
        RideDiagnostics.log("projection", change.logLine(step))
        // With the volume card on, the card says the level and the title does not.
        volumeEcho = com.reau.app.dash.VolumeCards.titleEcho(volumeCard.enabled) { com.reau.app.dash.ActionEchoText.volume(words, change) }
        session.sendStatusNow()
    }

    /** What the maneuver ring last did (see [DashSession.onRingChange]); written under every text of the last button. */
    @Volatile private var ringNote = ""

    private fun showButton(text: com.reau.app.video.ButtonText) {
        val shown = text.copy(note = ringNote)
        com.reau.app.video.ButtonOverlay.latest = shown
        compositor.setButtonText(shown)
    }

    // This projection's number at AaDialNow: its end never clears the dial of a newer one.
    private var dialNumber = 0

    override fun startAndroidAuto() {
        val card = setup.card
        if (card != null) return showCard(card)
        val running = receiver ?: return
        dialNumber = AaDialNow.started { running.dialSoon() }
        // The first moments of the screen watch count from this projection's first picture, not from a look at the old one the compositor still holds:
        // the gate is shut and the watch forgotten before Android Auto's decoder can draw (in this order, and both before start).
        compositor.awaitNewPicture()
        com.reau.app.aa.AaScreens.projectionStarting()
        running.start()
    }

    override fun stopAndroidAuto() {
        val running = receiver ?: return
        com.reau.app.aa.AaScreens.projection(false)
        running.stop()
        AaDialNow.stopped(dialNumber)
    }

    /** The picture's fit changed in the settings; the compositor takes a new mapping while running. */
    override val bikeNetwork: Network? get() = linkUp.bikeNetwork

    override fun freezeRecording(mark: Int): Boolean = receiver?.freezeRecording(mark) ?: false

    override val dashVisible: Boolean get() = linkUp.dashVisible

    /**
     * Every video setting applies while running, with no switching off ([LiveTuning]).
     * Without the settings these sides were built from (a test's), only the fit.
     */
    override fun retune(settings: REauSettings) = live.retune(settings)

    override fun remap(mapping: FrameFit.Mapping) {
        if (setup.card != null || !started) return
        lastFit = mapping
        val shown = liveZoom.applyFor(com.reau.app.aa.AaScreens.watch.screen.value, mapping)
        if (!compositor.setMapping(shown)) RideDiagnostics.warn("projection", "the new fit was refused, the picture stays as it was: $shown")
    }

    /** The joystick's zoom is for the navigation apps: on the menu or another app a step is not taken (the log says so). */
    private fun zoomHere(what: String): Boolean {
        val screen = com.reau.app.aa.AaScreens.watch.screen.value
        if (com.reau.app.video.LiveZoom.appliesTo(screen)) return true
        RideDiagnostics.log("projection", "joystick zoom $what ignored: the screen is $screen, the zoom is for the navigation apps")
        return false
    }

    /** The step of the joystick's zoom taken back (a side that came with the press of the centre). */
    private fun panelZoomUndo() {
        if (!zoomHere("undo")) return
        val changed = liveZoom.undoLast()
        val why = "it came with a press of the centre"
        RideDiagnostics.log("projection", "joystick zoom ${liveZoom.percent}% (" + (if (changed) "the step taken back: $why" else "nothing to take back") + ")")
        if (changed) lastFit?.let(::remap)
    }

    /** The joystick's zoom, one step: the picture sent to the dash a step nearer or farther, its bottom edge kept. */
    private fun panelZoom(step: Int) {
        if (!zoomHere("step")) return
        val changed = liveZoom.step(step)
        RideDiagnostics.log("projection", "joystick zoom ${liveZoom.percent}%" + if (changed) "" else " (the end, no change)")
        if (changed) lastFit?.let(::remap)
    }

    /**
     * Draws the card once into the compositor's input, which keeps repeating it to the dash; then the picture side
     * counts as up. The compositor's input has no other producer in the card mode.
     */
    private fun showCard(card: () -> Bitmap) {
        val surface = compositor.inputSurface.takeIf { started } ?: return
        val picture = runCatching(card).getOrElse {
            RideDiagnostics.warn("projection", "the card could not be drawn: $it")
            return
        }
        val canvas = surface.lockCanvas(null)
        try {
            canvas.drawBitmap(picture, null, Rect(0, 0, DashGlass.FRAME_W, DashGlass.FRAME_H), null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        report(ProjectionEvent.AndroidAutoChanged(AaLinkState.ACTIVE))
    }

    override fun startDash() {
        // Another app holding the dash's port (another dash app, streaming): both would get the dash's packets. Not
        // even the bike's Wi-Fi is asked for; the machine tries again later.
        if (!setup.dashPortFree()) {
            RideDiagnostics.warn("projection", "the dash's port ${DashPorts.DASH_RX_PORT} is held by another app: not connecting")
            report(ProjectionEvent.DashChanged(DashLink.TAKEN))
            return
        }
        linkUp.start(
            { ssid, network -> session.connect(ssid, network) },
            { why ->
                RideDiagnostics.warn("projection", "no way to the dash: $why")
                report(ProjectionEvent.DashChanged(WifiDashLinkUp.linkAfterFailure(why)))
            },
        )
    }

    override fun stopDash() {
        session.disconnect()
        linkUp.stop()
    }

    /** The service refuses to start with no compositor (ProjectionService's prepare). */
    override val videoReady: Boolean get() = started

    override fun startVideo() {
        if (!started) {
            // No compositor, so no video can go out. Returning with the dash session left READY would leave the
            // machine reading "streaming" for good (no 4 Hz projection packet, no watchdog). The session is
            // closed instead: the machine sees the dash drop and says so, and tries again on its own pace.
            RideDiagnostics.log("projection", "video not started: no compositor; the dash session is closed so the projection does not read as streaming")
            session.disconnect()
            return
        }
        session.startStreaming()
        // Any loop left over is stopped before the new one is built (the slot never holds two).
        loops.start { live.newLoop() }
    }

    override fun stopVideo() = loops.stop()

    /** Every second, the pictures and bytes Android Auto sent the phone in it: the "app" numbers of the stats text. */
    private suspend fun kotlinx.coroutines.CoroutineScope.watchAppVideo() {
        var units = com.reau.app.aa.AaVideoMeter.units.get()
        var bytes = com.reau.app.aa.AaVideoMeter.bytes.get()
        var at = android.os.SystemClock.elapsedRealtime()
        statsFeed.app(null)
        val stale = com.reau.app.aa.StaleImage()
        while (isActive) {
            delay(com.reau.app.stream.StreamGovernor.STATS_MS)
            val nowUnits = com.reau.app.aa.AaVideoMeter.units.get()
            val nowBytes = com.reau.app.aa.AaVideoMeter.bytes.get()
            val now = android.os.SystemClock.elapsedRealtime()
            statsFeed.app(com.reau.app.video.StatsOverlay.reading(nowUnits - units, nowBytes - bytes, now - at))
            statsFeed.speed(navSpeed.displayKmhAt(now))
            // A picture that looks alive and is not: the bike moves on the route's speed and Android Auto sends nothing new.
            stale.check(now, nowUnits, navSpeed.kmhAt(now))?.let { isStale ->
                val staleFor = com.reau.app.aa.StaleImage.STALE_AFTER_MS / 1000
                RideDiagnostics.log(
                    "projection",
                    if (isStale) "Android Auto's picture is stuck: no new picture for $staleFor s with the bike moving"
                    else "Android Auto's picture is alive again",
                )
                badgeStale = isStale
                showBadge()
                report(ProjectionEvent.AndroidAutoImageStale(isStale))
            }
            units = nowUnits
            bytes = nowBytes
            at = now
        }
    }

    /**
     * A few times a second: Android Auto's session gone for 5 s while the dash is still showing the video puts the "waiting for Android Auto"
     * notice on it, and the first new picture takes it off ([com.reau.app.aa.WaitingForAndroidAuto]; the compositor drops it with that picture
     * and this loop only tells the log and the state). A shorter drop leaves the last picture on the dash, as before.
     */
    private suspend fun kotlinx.coroutines.CoroutineScope.watchWaiting() {
        val waiting = com.reau.app.aa.WaitingForAndroidAuto()
        while (isActive) {
            delay(WAITING_POLL_MS)
            val dashShowing = ProjectionMachine.linkOf(session.state.value) == DashLink.READY
            val change = waiting.check(
                android.os.SystemClock.elapsedRealtime(), receiver?.state == AaLinkState.ACTIVE, dashShowing, com.reau.app.aa.AaVideoMeter.units.get(),
            ) ?: continue
            RideDiagnostics.log(
                "projection",
                when {
                    change ->
                        "Android Auto's session has been gone for ${com.reau.app.aa.WaitingForAndroidAuto.AFTER_MS / 1000} s with the dash connected: " +
                            "the dash says it is waiting for Android Auto"
                    dashShowing -> "Android Auto's picture is back: the dash's waiting notice is off"
                    else -> "the dash is gone: the waiting notice is off"
                },
            )
            badgeWaiting = change
            showBadge()
        }
    }

    override fun release() {
        // Every step on its own: one that throws (the web server, the receiver) must not leave the compositor's EGL context and thread,
        // the dash session or the Wi-Fi watch held.
        val failed = ReleaseSteps.runAll(
            listOf<Pair<String, () -> Unit>>(
                "stats" to {
                    com.reau.app.video.StatsOverlay.latest =
                        com.reau.app.video.StatsText(com.reau.app.video.StatsOverlay.UNKNOWN, com.reau.app.video.StatsOverlay.APP)
                },
                "button" to { com.reau.app.video.ButtonOverlay.latest = null },
                // The info ring's GPS (a process-wide listener at 1 Hz): the ring's loop dies with the scope in its delay and never stops it
                // (26/10/2026 review: the GPS stayed on after Desligar until the process died).
                "gps" to { GpsWatch.stop() },
                "preview" to { PhonePreview.detach(previewTarget) },
                "touch" to { PhonePreview.detachTouch(touchTarget) },
                "web" to { web?.release() },
                "audio" to { audioPlayer?.release() },
                "delayer" to { webDelayer.shutdown() },
                "video" to { stopVideo() },
                "receiver" to { receiver?.stop() },
                "dash session" to { session.disconnect() },
                "link up" to { linkUp.stop() },
                "volume watch" to { volumeWatch.stop() },
                "sighting" to { com.reau.app.dash.DashSighting.release(sightingListener) },
                "wifi radio" to { WifiRadio.release(radioListener) },
                "image gap" to { com.reau.app.dash.ImageGap.clock.forget() },
                "scope" to { scope.cancel() },
                "compositor" to { compositor.release() },
            ),
        ) { name, e -> RideDiagnostics.warn("projection", "release: $name failed: ${e.javaClass.simpleName}") }
        if (failed > 0) RideDiagnostics.warn("projection", "release: $failed step(s) failed, the others were carried out")
    }


    // The thermal status the video loop's ceiling follows: Android's own (the battery's heat as a second source was tried behind a switch and never acted).
    private val thermalStatus: () -> Int = { setup.thermalStatus() }

    /** The bike's speed (from the route) and Wi-Fi now, for the stream's 5 s line. */
    private val linkSample: () -> com.reau.app.stream.LinkSample = {
        val wifi = DeviceStateReader.bikeWifi(appContext, linkUp.bikeNetwork)
        com.reau.app.stream.LinkSample(navSpeed.kmhAt(android.os.SystemClock.elapsedRealtime()), wifi?.dbm, wifi?.mbps)
    }

    /** The maneuver distance and the route's own speed now, for [com.reau.app.stream.StreamGovernor]'s
     *  live override — both null together without an active route (same feed as [linkSample]'s speed). */
    private val navSample: () -> com.reau.app.stream.StreamGovernor.NavSample = {
        val s = tuned
        val g = guidanceNow
        com.reau.app.stream.StreamGovernor.NavSample(
            maneuverProfileEnabled = s?.maneuverFpsProfile == true,
            speedProfileEnabled = s?.speedFpsProfile == true,
            distanceToManeuverMeters = g.distanceToManeuverMeters.takeIf { g.active && it >= 0 },
            // The route's own estimate (the GPS speed as a source was tried behind a switch and never ran with a route).
            speedKmh = navSpeed.kmhAt(android.os.SystemClock.elapsedRealtime()),
            mode = s?.navProfileMode ?: com.reau.app.dash.NavProfileMode.PESSIMISTA,
        )
    }

    /** The settings changed while running, and the video loop made with the latest of them. */
    private inner class Live {
        // The new sessions asked for by the settings wait for the burst of changes to end.
        private val sessionSettle = SessionSettle { delayMs, action ->
            val job = scope.launch {
                delay(delayMs)
                action()
            }
            ({ job.cancel() })
        }

        /** Asks day or night now and then for as long as the sides live; the receiver passes on only a change. */
        fun watchNight(receiver: AaReceiver) = scope.launch {
            while (isActive) {
                runCatching { receiver.setNight(setup.night()) }.onFailure { RideDiagnostics.warn("projection", "day/night not known: $it") }
                delay(setup.nightCheckMs)
            }
        }

        @Suppress("CyclomaticComplexMethod") // one branch per field or case of the format it reads or writes
        fun retune(settings: REauSettings) {
            val before = tuned
            if (setup.card != null || !started || before == null) return remap(fitOf(settings))
            tuned = settings
            val steps = LiveTuning.steps(before, settings)
            if (steps.isNotEmpty()) RideDiagnostics.log("projection", "settings changed while running: ${steps.joinToString()}")
            if (LiveTuning.Step.REFIT in steps) remap(fitOf(settings))
            // A burst of changes opens one session, [SessionSettle.IN_USE_MS] after the last, with its values.
            if (LiveTuning.Step.NEW_ANDROID_AUTO_SESSION in steps) {
                sessionSettle.offer {
                    RideDiagnostics.log("projection", "a new Android Auto session for the settings, after ${SessionSettle.inUseMs} ms with no other change")
                    newAndroidAutoSession(settings)
                }
            }
            if (LiveTuning.Step.NEW_STREAM in steps) {
                if (before.videoPace != settings.videoPace) RideDiagnostics.log("stream", settings.videoPace.logLine())
                newStream(settings)
            }
            // The side of the road is read at each guidance update from `tuned`, so the next card has it; the choice is said in the log now.
            if (before.drivingSideMode != settings.drivingSideMode) drivingSide.refresh()
            if (LiveTuning.Step.NIGHT_NOW in steps) receiver?.let { r -> scope.launch { runCatching { r.setNight(setup.night()) } } }
            if (LiveTuning.Step.JOYSTICK in steps) {
                val page = JoystickBindings.decode(settings.joystick)
                joystick?.bindings = page
                session.zoomLabel = page.zoomControls
                session.musicCardAllowed = page.mediaControls
                session.infoRingPress = page.infoRingControl
                session.actionEcho = settings.actionEcho
                legend.enabled = settings.joystickLegend
            }
            if (LiveTuning.Step.STATS_OVERLAY in steps) compositor.setOverlay(settings.overlay)
            if (LiveTuning.Step.BUTTON_OVERLAY in steps) compositor.setButtonOverlay(settings.buttonLook)
            // `tuned` (set above) is what the beat, the music card and the route read; the weather, just switched on, is fetched now.
            if (LiveTuning.Step.DASH_EXTRAS in steps) {
                session.maneuverCircleEnabled = settings.maneuverRing
                volumeCard.enabled = settings.volumeCard
                session.ringCycleOn = settings.infoRing
                session.infoInPlace = settings.infoRingInPlace
                session.infoTitle.enabled = settings.infoRing && settings.infoTitle
                session.ringFollowsScreen(com.reau.app.aa.AaScreens.watch.screen.value)
            }
            if (LiveTuning.Step.DASH_EXTRAS in steps && settings.weatherWanted && !before.weatherWanted) {
                scope.launch { runCatching { setup.refreshWeather() } }
            }
            if (LiveTuning.Step.WEB in steps) {
                web?.apply(settings.webView, settings.webTouch, settings.webAudio)
                // With the network server on the dash is not looked for; off, it is again.
                if (before.webView != settings.webView) report(ProjectionEvent.NetworkServer(settings.webView))
            }
        }

        /** What Android Auto is told when a session begins: a new session, told the new values; the fit waits for it. */
        fun newAndroidAutoSession(settings: REauSettings) {
            val config = LiveTuning.sessionConfig(aaConfig, settings)
            soundHeard.clear()
            aaConfig = config
            pendingMapping = fitOf(settings)
            receiver?.reconfigure(config)
        }

        /** Android Auto's new session is up: the compositor takes its picture's size and the fit made for it. */
        fun applyPendingFit() {
            val mapping = pendingMapping ?: return
            pendingMapping = null
            compositor.setSource(com.reau.app.aa.AaVideo.WIDTH, com.reau.app.aa.AaVideo.HEIGHT)
            remap(mapping)
        }

        /** The pace: the stream to the dash starts again with it (a keyframe first), the dash staying connected. */
        fun newStream(settings: REauSettings) {
            videoSettings = videoSettings.withRider(settings)
            // Only while a loop runs (the panel is there and streaming): a panel that dropped just now is not given a loop again, and a loop built while it dropped is stopped.
            loops.replace { newLoop() }
        }

        /**
         * A stream started in [used], which is not the area of the settings when no encoder took it (the loop fell back to 526x300): the picture is fitted for the area it
         * really runs in ([fitOf]), as a release build's is, and again for the settings' area when a later stream is back in it.
         */
        fun followFallback(used: com.reau.app.dash.DashConfig.FrameSize) {
            val fallback = used.takeIf { it != videoSettings.frame }
            if (fallback != fallbackArea) {
                fallbackArea = fallback
                val s = tuned
                if (s != null && setup.card == null && started) {
                    RideDiagnostics.log("projection", "the stream runs in ${used.id}: the picture is fitted for it")
                    remap(fitOf(s))
                }
            }
        }

        /**
         * A video loop started on the dash session, or null when it could not start (no encoder). A loop that did
         * not start would leave the session streaming projection packets with no video behind them, which only
         * the dash's first-ack watchdog would end 15 s later; the session is closed at once instead, so the machine
         * sees a dropped dash and tries again (a new session, a new encoder) on its own pace.
         */
        fun newLoop(): VideoLoop? {
            val dash = object : VideoLoop.Dash {
                override fun sendRtp(packet: ByteArray) = session.sendRtp(packet)
                override fun keyframeSent(bytes: Int, packets: Int) = session.keyframeSent(bytes, packets)
                override fun keyframeStarting() = session.keyframeStarting()
                override val firstFrameConfirmed: Boolean get() = session.firstFrameConfirmed
                override val decodedFrameCount: Long get() = session.decodedFrameCount
            }
            val fresh = VideoLoop(compositor, dash, videoSettings, thermalStatus, link = linkSample, navSample = navSample,
                lightScreen = { com.reau.app.stream.LightScreen.wanted(com.reau.app.aa.AaScreens.watch.screen.value) },
                stats = statsFeed,
                // The debug build's FPS test card, read from the live settings at each poll of the loop: on, off or another pace takes effect at once, with no new stream.
                fpsTest = { tuned?.fpsTestRate },
            )
            if (fresh.start()) {
                followFallback(fresh.frame)
                return fresh
            }
            RideDiagnostics.error("projection", "the video loop did not start; the dash session is closed to be tried again")
            session.disconnect()
            return null
        }
    }

    private companion object {
        val WHOLE_FRAME = RectPx(0, 0, DashGlass.FRAME_W, DashGlass.FRAME_H).let { FrameFit.Mapping(it, it) }
    }
}

/**
 * The quiet spell after the joystick is checked a few times per [idleMs] (at most every second); once it passed,
 * and the screen shown is the navigation's ([onNavigation]: the one the picture's net and the other readings agree on), the touch
 * ([com.reau.app.aa.FocusClearer.TAP]) goes to Android Auto through [send], one log line each time.
 */
private fun clearFocusWhenIdle(
    scope: CoroutineScope,
    focus: com.reau.app.aa.FocusClearer,
    idleMs: Long,
    onNavigation: () -> Boolean,
    send: (AaCommand) -> Unit,
) {
    val tick = (idleMs / FOCUS_CHECKS).coerceIn(FOCUS_TICK_MIN_MS, FOCUS_TICK_MAX_MS)
    scope.launch {
        while (isActive) {
            delay(tick)
            if (focus.tapDue(android.os.SystemClock.elapsedRealtime(), onNavigation())) {
                com.reau.app.aa.FocusClearer.TAP.forEach(send)
                RideDiagnostics.log("aa", "focus: a touch cancelled at once, ${idleMs / FOCUS_LOG_S} s after the joystick")
            }
        }
    }
}

/** How often the weather is looked at (the fetch itself waits [com.reau.app.weather.WeatherKeeper.REFRESH_MS]). */
private const val WEATHER_POLL_MS = 60_000L

/** How often the "waiting for Android Auto" notice is looked at: the 5 s are counted to within this. */
private const val WAITING_POLL_MS = 250L

/** The ring's switch and the dash's ring are looked at every [InfoRingFeed.POLL_MS]; what it says every [RING_TICKS_PER_LOOK] looks (a second): pages turn every few seconds (seven by default, five to thirty). */
private const val RING_TICKS_PER_LOOK = 20
private const val FOCUS_CHECKS = 4
private const val FOCUS_TICK_MIN_MS = 50L
private const val FOCUS_TICK_MAX_MS = 1_000L
private const val FOCUS_LOG_S = 1_000

/** Android Auto's song on the dash's own music card; none stops repeating it. */
private fun DashSession.showNowPlayingCard(playing: com.reau.app.aa.AaNowPlaying?, art: com.reau.app.dash.AlbumArt?) {
    if (playing == null) {
        clearNowPlaying()
    } else {
        updateNowPlaying(playing.song, playing.album, playing.artist, playing.playing, art?.forDash(playing.art))
    }
}

/** The ringing call on the dash's own call card (a call with no name and no number shows [privateName]); none clears it. */
private fun DashSession.showCallerCard(caller: com.reau.app.aa.AaCaller?, privateName: String) {
    if (caller == null) clearCaller() else showCaller(caller.name.ifEmpty { privateName }, caller.inCall)
}

/** Whether the phone's default network works (validated, with the internet): the lost-signal icon asks. */
private fun phoneOnline(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}.getOrDefault(true)

/** Android Auto's guidance on the dash's own nav bubble; none hides the bubble again. */
private fun DashSession.showGuidance(guidance: NavGuidance, noNetwork: Boolean, imperial: Boolean, drivingSide: DrivingSide) {
    val nav = AaManeuvers.toDash(guidance, noNetwork, imperial = imperial, drivingSide = drivingSide)
    // For the projection's resumo: the numbers Android Auto sent that no glyph answers (a rerouting shows the compass whatever the number is).
    if (guidance.active) {
        val summary = com.reau.app.data.ProjectionSummary.current
        summary.maneuver(0, guidance.maneuverType, guidance.rerouting || AaManeuvers.knowsType(guidance.maneuverType))
        summary.maneuver(1, guidance.nextManeuverType, AaManeuvers.knowsType(guidance.nextManeuverType))
    }
    if (nav == null) {
        clearNavInfo()
    } else {
        updateNavInfo(
            nav.maneuver, nav.primaryDist, nav.primaryUnit, nav.totalDist, nav.totalUnit, nav.eta, nav.road,
            nav.remainingSeconds, nav.next,
        )
    }
}

/**
 * A call answered or ended by the joystick goes to Android Auto as the car's phone key first (it answers the voice
 * apps' calls too, on its own call card), then to the system's Telecom as before (which answers the phone's).
 */
private fun callByKeys(command: AaCommand, running: AaReceiver?, calls: LiveSides.Setup.Calls) {
    val keys = com.reau.app.aa.AaCallKeys.toAndroidAuto(command, running?.state == AaLinkState.ACTIVE)
    keys.forEach { key -> running?.sendInput(key) }
    val what = if (command == AaCommand.AnswerCall) "answer" else "end"
    val how = keys.firstOrNull()?.let { "key ${(it as AaCommand.Key).keycode} to Android Auto, then Telecom" } ?: "no Android Auto session, Telecom only"
    RideDiagnostics.log("call", "$what: $how")
    if (command == AaCommand.AnswerCall) calls.answer() else calls.end()
}
