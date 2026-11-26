// A foreground service of the "connected device" type holding a wake lock and a Wi-Fi lock (ProjectionLocks),
// startForeground() on every start request (Android kills the app when one goes unanswered), the low-importance
// notification channel (ProjectionNotification), and the clean fall: a refused foreground start costs the projection,
// never the app. The ladder of types is there for Gemini's microphone (ForegroundTypes).
package com.reau.app.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Network
import android.os.IBinder
import android.os.SystemClock
import com.reau.app.R
import com.reau.app.aa.AaLinkState
import com.reau.app.data.AppStandbyLog
import com.reau.app.data.CrashHandler
import com.reau.app.data.DeviceStateLog
import com.reau.app.data.ProjectionSummary
import com.reau.app.data.RideSummary
import com.reau.app.data.ExitHistory
import com.reau.app.data.RideDiagnostics
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsInUse
import com.reau.app.util.DiagnosticsExport
import com.reau.app.settings.SettingsStore
import com.reau.app.video.FrameFit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The two sides and the video, as the service drives them. The real ones are [LiveSides]. */
@Suppress("TooManyFunctions") // the six sides plus the optional ones with defaults; splitting them would scatter what LiveSides answers (26/11/2026)
interface ProjectionSides {
    fun startAndroidAuto()
    fun stopAndroidAuto()
    fun startDash()
    fun stopDash()
    fun startVideo()
    fun stopVideo()

    /** The service is going: everything the sides hold is let go. */
    fun release() = Unit

    /** A new fit for the picture while running (adjusted in the settings, looking at the dash). */
    fun remap(mapping: FrameFit.Mapping) = Unit

    /**
     * The rider's settings changed while running (every video setting applies at once,
     * [LiveTuning]). Without more to do, the new fit.
     */
    fun retune(settings: REauSettings) = remap(settings.mapping)

    /**
     * The rider's mark [mark]: the session recording of Android Auto, if one is being made, is copied to a name its ring does not
     * overwrite (`MarkFreezer`). Returns at once. False: nothing to keep.
     */
    fun freezeRecording(mark: Int): Boolean = false

    /**
     * The battery is at [pct] percent with no charger ([BatteryLowWatch]): the dash's video shows the pill of the low battery for a few seconds, after a call or the volume
     * card if one shows (26/11/2026). True when the pill was taken; false when these sides have no video to show it on.
     */
    fun showBatteryLow(pct: Int): Boolean = false

    /** The bike's Wi-Fi while the sides hold it, for the `device` line; null when none. */
    val bikeNetwork: Network? get() = null

    /** The last try for the dash found its Wi-Fi in the scan but was refused: it is there, stuck or busy. */
    val dashVisible: Boolean get() = false

    /**
     * Whether these sides can make video for the dash at all. False when the compositor
     * (OpenGL) did not start: then the projection is refused at its preparation, with `problem_compositor` as
     * the problem (the log says [COMPOSITOR_NOT_STARTED]), instead of running on to a dash session that would read as streaming with nothing to stream.
     */
    val videoReady: Boolean get() = true

    companion object {
        /** Why the sides have no compositor, for the log (the rider reads it in the rider's language: `problem_compositor`). */
        const val COMPOSITOR_NOT_STARTED = "the video compositor (OpenGL) did not start: no picture for the dash"
    }

    /** Nothing is started: the service, its locks and its machine run on their own. For tests. */
    object None : ProjectionSides {
        override fun startAndroidAuto() = Unit
        override fun stopAndroidAuto() = Unit
        override fun startDash() = Unit
        override fun stopDash() = Unit
        override fun startVideo() = Unit
        override fun stopVideo() = Unit
    }
}

/**
 * The projection, while the rider has it on: a foreground service that keeps the process, the CPU and the Wi-Fi
 * awake with the screen off, and runs [ProjectionMachine] through a [ProjectionController] on a thread of its own
 * (never the main thread: stopping Android Auto's receiver may wait a few seconds).
 *
 * [start] and [stop] are all the screen needs; [projection] is where things stand, for the screen and the
 * notification. Nothing starts by itself after a reboot. One thing comes back by itself: the projection the rider had on when the system ended the
 * process (not a force stop) is resumed once, as if Ligar had been tapped, within the limits of [ProjectionResume]; the service answers START_STICKY only
 * while the projection is the rider's and running, and every way the rider (or the dash's absence) turns it off erases the mark that lets it come back.
 */
@Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
class ProjectionService : Service() {
    companion object {
        const val ACTION_START = "com.reau.app.PROJECTION_START"
        const val ACTION_STOP = "com.reau.app.PROJECTION_STOP"
        const val ACTION_START_CARD = "com.reau.app.PROJECTION_START_CARD"
        const val ACTION_REFIT = "com.reau.app.PROJECTION_REFIT"
        const val ACTION_MARK = "com.reau.app.PROJECTION_MARK"
        private val START_ACTIONS = setOf(ACTION_START, ACTION_START_CARD)

        /** The tag of both locks, as `dumpsys power` and `dumpsys wifi` show it. */
        const val LOCK_TAG = "reau:projection"

        /** What a command found, for the log: a Desligar that begins, or a Ligar that comes while that stop is still carried out. */
        internal enum class TapNote { NONE, STOP_TAPPED, LIGAR_DURING_STOP }

        internal fun tapNote(action: String?, phase: Phase, stopTappedAtMs: Long): TapNote = when {
            action == ACTION_STOP && phase != Phase.STOPPED -> TapNote.STOP_TAPPED
            action == ACTION_START && stopTappedAtMs != 0L -> TapNote.LIGAR_DURING_STOP
            else -> TapNote.NONE
        }

        /**
         * The foreground types the projection asks for beyond the connected device: [microphone] for Gemini, [location] for the dash's Wi-Fi
         * list (Android gives an app no list while it is behind another one, unless its foreground service carries the location type).
         * [gps]: the info ring's GPS card would use that type too (only for the log's reason).
         */
        internal class ForegroundNeeds(val microphone: Boolean, val location: Boolean, val gps: Boolean = false)

        /**
         * Gemini on and the permission to record. The location type is asked for whenever the precise location permission is given, whatever the
         * ring, its GPS card or the rider's "Usar a localização": it is what lets REau read the dash's network in the Wi-Fi list with the app
         * behind another one, and it makes REau read no position (the GPS and the weather have their own switches).
         */
        internal fun foregroundNeeds(settings: REauSettings, recordAudioGranted: Boolean, fineLocationGranted: Boolean) = ForegroundNeeds(
            microphone = settings.gemini && recordAudioGranted,
            location = fineLocationGranted,
            // The title says no GPS card (the dash shows its own), so the ring in words listens to no GPS.
            gps = settings.infoRing && settings.useLocation &&
                com.reau.app.weather.RingData.shown(settings.infoRingData, com.reau.app.weather.RingData.GPS) && fineLocationGranted && !settings.infoTitle,
        )

        /**
         * The log lines of a foreground start that Android accepted, once per start: the types it accepted, why each extra was asked for, and the
         * warning when the location type was wanted and is not there (the Wi-Fi list will be empty behind another app). No network names.
         */
        internal fun foregroundNotes(accepted: Int, needs: ForegroundNeeds, fineLocationGranted: Boolean): List<String> = buildList {
            add("projection: foreground types accepted: ${ForegroundTypes.names(accepted)} (0x${Integer.toHexString(accepted)})")
            if (accepted and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) add("projection: foreground with the microphone, for Gemini")
            val location = accepted and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0
            if (location) {
                add("projection: foreground with the location, for the dash's Wi-Fi list" + if (needs.gps) " and the info ring's GPS" else "")
            } else if (!fineLocationGranted) {
                add("${RideDiagnostics.WARN}projection: no precise location permission: the Wi-Fi list will be empty while REau is behind another app")
            } else {
                add("${RideDiagnostics.WARN}projection: the location type was not accepted: the Wi-Fi list will be empty while REau is behind another app")
            }
        }

        /** A start builds its sides when the projection is stopped (a start on a running one keeps what it has). */
        internal fun buildsSides(sidesFor: Boolean?, phase: Phase): Boolean = sidesFor != null && phase == Phase.STOPPED

        /** A mark of the rider's goes in a projection that is on; a refit applies only to one that is running. */
        internal fun markApplies(phase: Phase): Boolean = phase != Phase.STOPPED

        /**
         * The rider's mark: its line in the ride log first, then the ask to the sides to keep a copy of the session recording (the ring of it
         * overwrites itself, about 11 min of a long ride). A failure of the copy costs the copy only: [log] says it.
         */
        internal fun keepMark(
            sides: ProjectionSides?, marks: RiderMarks, nowMs: Long, log: (String) -> Unit,
            note: (String) -> Unit = { RideDiagnostics.log("mark", it) },
        ) {
            note(marks.mark(nowMs))
            runCatching { sides?.freezeRecording(marks.count) }
                .onFailure { log("${RideDiagnostics.WARN}mark ${marks.count}: keeping the session recording failed: $it") }
        }

        internal fun refitApplies(phase: Phase): Boolean = phase == Phase.RUNNING

        /** A drop of Android Auto's side, for the summary. */
        internal fun isAaDrop(event: ProjectionEvent): Boolean = event is ProjectionEvent.AndroidAutoChanged && event.state == AaLinkState.DROPPED

        private const val STOP_WAIT_MS = 5_000L
        private const val MS_PER_MINUTE = 60_000L
        private const val NOTIFICATION_PACE_MS = 250L

        /** How often the phone's state is looked at, and how often a line is written with no relevant change. */
        private const val DEVICE_LOOK_MS = 5_000L
        private const val DEVICE_PERIOD_MS = 30_000L

        /** Test-only: the locks and the earlier runs' reports take this much longer, as they once did on the phone. */
        @Volatile internal var testOnlyHousekeepingMs = 0L

        private val current = MutableStateFlow(Projection())

        /** Which service instance may write [current] and [started]: an old one still releasing does not overwrite the new one's (A-20, [StateOwner]). */
        private val owner = StateOwner()

        /** Where the projection stands. */
        val projection: StateFlow<Projection> = current.asStateFlow()

        private val started = MutableStateFlow<Long?>(null)

        /**
         * When the projection now on started (SystemClock.elapsedRealtime), for the time on the main screen's Desligar;
         * null while it is off.
         */
        val startedAt: StateFlow<Long?> = started.asStateFlow()

        /** Whether the service holds the foreground now (the `app background` line says it). */
        @Volatile var inForeground = false
            private set

        /** The foreground types Android accepted for the service now in the foreground (`Service.foregroundServiceType`); 0 when it is not. */
        @Volatile var acceptedTypes = 0
            private set

        /** Builds the sides for a service; it gets the channel back to the machine. Tests replace it. */
        @Volatile internal var sidesFactory: (Context, (ProjectionEvent) -> Unit) -> ProjectionSides =
            { context, report -> LiveSides(context, report, LiveSides.Setup.forPhone(context)) }

        /** Builds the sides of the card mode: the card in place of Android Auto. Tests replace it. */
        @Volatile internal var cardSidesFactory: (Context, (ProjectionEvent) -> Unit) -> ProjectionSides =
            { context, report -> LiveSides(context, report, LiveSides.Setup.forCard(context)) }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProjectionService::class.java).setAction(ACTION_START))
        }

        /** Switches on the card mode: a still picture to the dash, no Android Auto. */
        fun startCard(context: Context) {
            context.startForegroundService(Intent(context, ProjectionService::class.java).setAction(ACTION_START_CARD))
        }

        /**
         * Always sent, even when [projection] still says stopped: a start asked for a moment ago may not have reached
         * the service yet, and this stop must come after it. A service that was not running stops again at once.
         */
        /**
         * The fit in the settings changed: a running projection takes it at once. Sent only while running,
         * so it never starts the service; a projection started later reads the settings anyway.
         */
        fun refit(context: Context) {
            if (current.value.phase != Phase.RUNNING) return
            try {
                context.startService(Intent(context, ProjectionService::class.java).setAction(ACTION_REFIT))
            } catch (e: IllegalStateException) {
                RideDiagnostics.log("projection", "refit not sent: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, ProjectionService::class.java).setAction(ACTION_STOP))
            } catch (e: IllegalStateException) {
                // Android refuses to start a service from the background: then there is none to stop.
                RideDiagnostics.log("projection", "stop not sent: ${e.message}")
            }
        }
    }

    // This instance's number for [owner]; the writes of the process-wide state go through it.
    private val instance = owner.newInstance()
    private fun publish(write: () -> Unit) = owner.publish(instance, write)

    private lateinit var worker: ScheduledExecutorService
    private lateinit var controller: ProjectionController

    // The voice in the rider's headset (RiderVoice: off unless the rider asked for it); made with the service, its engine only at the first phrase.
    private lateinit var voice: RiderVoice

    // The state the machine was in before the one it reports now, to tell what changed (StateAlerts); only the thread that reports reads and writes it.
    private var lastReported = Projection()

    // Whether the rider has been told, for this episode, that another app holds the dash's port (TakenNotice); the worker reads and writes it.
    private val taken = com.reau.app.dash.TakenNotice()

    // The sides of the kind last switched on, built at that start; only the worker reads and writes these three.
    private var sides: ProjectionSides? = null
    private var sidesForCard = false
    private var sidesBuilt = 0

    /** Why the sides of this start could not be built, until [Ports.prepare] tells it; the worker only. */
    private var sidesFailure: String? = null
    private val locks = ProjectionLocks(this)

    // The start id of the command the worker is carrying out; only the worker reads and writes it.
    private var command = 0

    // The start id of the last start that took the foreground; written on the main thread, read by the worker.
    @Volatile private var lastStart = 0

    // The start id of the latest command of any kind (Ligar, Desligar, mark, refit, a restart), for the release's stopSelf (ForegroundHandover.stopId); main thread writes, worker reads.
    @Volatile private var latestCommand = 0

    // The mark that lets the projection come back after the system ended the process (ProjectionResume); read on the main thread at a restart, written on the worker.
    private val resumeBook by lazy { ProjectionResumeBook.open(this) }

    // Whether the projection is the rider's and running with the resume switched on: the service then answers START_STICKY; written on the main thread, read by the worker.
    @Volatile private var resumable = false

    // Which resume of the window this projection is (1 to 3), 0 when the rider tapped Ligar: for the log line and the notification's sub text; main thread and worker.
    @Volatile private var resumedAttempt = 0

    // When the rider's Desligar reached the service, until its effects are done (0: none going on). The stop is carried
    // out on the one worker and may take seconds (the receiver's bye); a Ligar tapped meanwhile waits behind it, with
    // the screen already saying "parado". Only for the log, to see that wait.
    @Volatile private var stopTappedAtMs = 0L

    // Used from the worker only.
    private lateinit var pacer: HeadlinePacer

    // The rider's marks of this projection, from the notification's button.
    private val marks = RiderMarks()

    /** What the notification says after a mark (the service's context: the language the rider chose); null before the first. */
    private fun markNote(): String? = marks.markedAt?.let { getString(R.string.notif_mark_done, marks.count, it) }
        ?: resumedAttempt.takeIf { it > 0 }?.let { getString(R.string.notif_resumed) }

    // The phone's state looked at while the projection is on; only the worker reads and writes it.
    private var deviceLook: ScheduledFuture<*>? = null

    // What the system's memory signs call ([com.reau.app.data.PressureSignals]): a look at the phone on the worker, with the reason it is written for.
    private var pressureLook: ((String) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // Before Android 13 the chosen language is put on the context here (from 13 the system holds it).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.reau.app.settings.AppLanguages.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        RideDiagnostics.init(this)
        worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "projection") }
        pacer = HeadlinePacer(
            NOTIFICATION_PACE_MS,
            { delayMs, action ->
                val pending = worker.schedule(action, delayMs, TimeUnit.MILLISECONDS)
                ({ pending.cancel(false) })
            },
            { headline -> ProjectionNotification.show(this, headline, markNote(), started.value, card = controller.state.card) },
        )
        voice = RiderVoice(
            enabled = { SettingsStore.open(this).load().spokenAlerts },
            words = { cue -> getString(VoiceWords.of(cue)) },
            // The language of the app: the service's resources follow the rider's choice (attachBaseContext, and the system itself from Android 13).
            locale = { resources.configuration.locales[0] },
            engineFactory = { AndroidSpeech(this) },
            focus = AndroidFocus(this),
            log = { RideDiagnostics.log("audio", it) },
        )
        RiderVoice.install(voice)
        // A dash that keeps going quiet with its Wi-Fi up (SilentDash): the rider is told, in the alert channel, and the notice goes when it is talking again.
        com.reau.app.dash.SilentDash.shared.notice = { tell ->
            if (tell) {
                log("projection: the dash has gone quiet in session after session with its Wi-Fi up: the rider is told")
                ProjectionNotification.showDashSilent(this)
            } else {
                ProjectionNotification.clearDashSilent(this)
            }
        }
        // The settings are decoded once (the Keystore, the file) and kept; read here, on the worker, the tap on Ligar finds
        // them warm when goForeground() reads them on the main thread.
        worker.execute { runCatching { SettingsStore.open(this).load() } }
        controller = ProjectionController(
            Ports(), ::log,
            onNoDashStop = { minutes -> ProjectionNotification.showStoppedNoDash(this, minutes) },
            noDashStopMs = { SettingsStore.open(this).load().noDashStopMinutes * MS_PER_MINUTE },
            onChange = { state ->
                publish {
                    countDash(current.value.dash, state.dash)
                    current.value = state
                }
                tellTheRider(lastReported, state)
                lastReported = state
                // "Desligando" goes up at once: the stop blocks this worker, which the pacer's wait runs on. Not when a newer start
                // already holds the foreground (its notification is the one to keep).
                if (state.headline == Headline.STOPPING && ForegroundHandover.dropsForeground(command, lastStart)) {
                    pacer.showNow(Headline.STOPPING)
                } else {
                    pacer.offer(state.headline)
                }
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        com.reau.app.util.StartupTiming.timed("ProjectionService.onStartCommand") { startCommand(intent, startId) }

    private fun startCommand(intent: Intent?, startId: Int): Int {
        val action = intent?.action
        // Only a command that does not take the foreground (a Ligar or a restart with no intent does): with a Ligar's id, a Desligar released on the worker
        // while that Ligar is still taking the foreground would end the new projection (25/11/2026; stopSelf with an older id does nothing, which protected it).
        latestCommand = ForegroundHandover.latestForStop(latestCommand, startId, takesForeground = intent == null || action in START_ACTIONS)
        noteTapDuringStop(action)
        when (action) {
            ACTION_START, ACTION_START_CARD -> if (goForeground()) {
                lastStart = startId
                resumedAttempt = 0
                // The rider's own Ligar: a frame from now on is not the resume's.
                com.reau.app.data.ResumeClock.shared.disarm()
                resumable = resumeSwitchOn()
                val card = intent.action == ACTION_START_CARD
                handle(startId, if (card) ProjectionEvent.StartCard else ProjectionEvent.Start, sidesFor = card)
            } else {
                stopSelf(startId)
            }
            ACTION_STOP -> {
                // Not sticky from the moment the rider's Desligar arrives: a process killed during the stop is not brought back.
                resumable = false
                handle(startId, ProjectionEvent.Stop)
            }
            ACTION_MARK -> worker.execute {
                if (!markApplies(controller.state.phase)) {
                    stopSelf(startId)
                } else {
                    keepMark(sides, marks, System.currentTimeMillis(), ::log)
                    ProjectionNotification.show(this, controller.state.headline, markNote(), started.value, card = controller.state.card)
                }
            }
            ACTION_REFIT -> worker.execute {
                if (refitApplies(controller.state.phase)) {
                    val settings = SettingsStore.open(this).load()
                    log("projection: refit from the settings: ${settings.mapping}")
                    runCatching { sides?.retune(settings) }.onFailure { log("${RideDiagnostics.WARN}projection: refit failed: $it") }
                } else {
                    stopSelf(startId)
                }
            }
            // A restart by the system (no intent): the projection comes back only when ProjectionResume says so; any other command stays stopped.
            else -> if (intent != null || !resumeAfterRestart(startId)) stopSelf(startId)
        }
        return ProjectionResume.returnFor(resumable)
    }

    /** Whether the rider's switch for resuming is on (read at each Ligar; a failure reads as off). */
    private fun resumeSwitchOn(): Boolean = runCatching { SettingsStore.open(this).load().resumeAfterKill }.getOrDefault(false)

    /**
     * A restart by the system, with no intent: the process was ended with the projection on. If [ProjectionResume.decide] says yes, the projection starts
     * as Ligar would start it, the foreground with the connected device type alone; if not, or if anything goes wrong, the mark is erased and the service stops.
     * True when it was resumed.
     */
    @Suppress("TooGenericExceptionCaught") // whatever a resume throws ends here: a restart that crashes the process would be started again by the system
    private fun resumeAfterRestart(startId: Int): Boolean {
        val resumed = try {
            val enabled = SettingsStore.open(this).load().resumeAfterKill
            when (val verdict = ProjectionResume.decide(resumeBook.read(), enabled, System.currentTimeMillis())) {
                is ProjectionResume.Verdict.Drop -> false.also { logResume(ProjectionResume.notResumedLine(verdict.why)) }
                is ProjectionResume.Verdict.Resume -> resumeNow(verdict, startId)
            }
        } catch (e: Exception) {
            logResume(ProjectionResume.notResumedLine("${e.javaClass.simpleName} while resuming"))
            false
        }
        if (!resumed) forgetResume()
        return resumed
    }

    /** The resume of [verdict], counted before anything else is tried, so a process that dies again at once is stopped by the limit. */
    private fun resumeNow(verdict: ProjectionResume.Verdict.Resume, startId: Int): Boolean {
        resumeBook.recordResume(System.currentTimeMillis(), verdict.resumedAtMs)
        if (!goForeground(restart = true)) {
            val battery = ProjectionResume.exemptionNote(batteryExemption())
            logResume(ProjectionResume.notResumedLine("the foreground start was refused (the lines above say how; $battery)"))
            return false
        }
        lastStart = startId
        resumable = true
        resumedAttempt = verdict.attempt
        handle(startId, if (verdict.card) ProjectionEvent.StartCard else ProjectionEvent.Start, sidesFor = verdict.card, fresh = false)
        return true
    }

    /**
     * Whether REau is exempt from the battery optimization (what the rider granted with "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"); null when Android does not say.
     * It is the one exemption Android lists for a foreground start from the background that REau can have, so the resume's log line says it (25/11/2026): the next
     * log tells whether a refused or a late resume had it.
     */
    private fun batteryExemption(): Boolean? =
        runCatching { getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) }.getOrNull()

    /** The rider's projection is over (turned off, stopped for the dash's absence, or refused to start), or cannot be resumed: nothing is left to bring back. */
    private fun forgetResume() {
        resumable = false
        resumedAttempt = 0
        com.reau.app.data.ResumeClock.shared.disarm()
        runCatching { resumeBook.erase() }.onFailure { logResume("the resume mark could not be erased: ${it.javaClass.simpleName}") }
    }

    /**
     * A resume began: the dash session says, when the dash confirms the first frame, how long that took since the system ended the process and since this one started
     * ([com.reau.app.data.ResumeClock], plan 35, 25/11/2026). The exit is the newest one Android kept; on the worker (a binder call), once the session's log has begun.
     */
    private fun armResumeClock() {
        runCatching {
            val exit = ExitHistory.newest(this)
            com.reau.app.data.ResumeClock.shared.arm(
                nowMs = com.reau.app.util.MonotonicClock.nowMs(),
                processAgeMs = SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime(),
                exitAgeMs = exit?.let { System.currentTimeMillis() - it.timestampMs },
                exitName = exit?.let { com.reau.app.data.ExitReasons.reasonName(it.reason) },
            )
        }
    }

    private fun logResume(line: String) {
        runCatching { RideDiagnostics.log("projection", line) }
    }

    /**
     * The rider took the app off the recent apps. Android keeps the service going, and the projection with it; but a maker's manager may end the process
     * right then, and the restart that follows is not to bring the projection back against what the rider just did: the mark says so until the rider's next
     * Ligar ([ProjectionResume.decide]). Nothing else changes: the projection goes on, and it still stops where it always did.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { resumeBook.taskRemoved(System.currentTimeMillis()) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // The system stopping the service is a switch-off too. The stop, and what follows it, run off the main thread: waiting here for the
        // worker (up to 5 s) froze the screen whenever the system destroyed the service during a slow stop (audit of 26/10/2026).
        val stopping = runCatching { worker.submit { controller.submit(ProjectionEvent.Stop) } }
        val begun = SystemClock.elapsedRealtime()
        BackgroundRelease.run("projection-release", { log("${RideDiagnostics.WARN}projection: releasing in onDestroy failed: $it") }) {
            ReleaseSteps.runAll(
                listOf<Pair<String, () -> Unit>>(
                    "stop" to { stopping.getOrThrow().get(STOP_WAIT_MS, TimeUnit.MILLISECONDS) },
                    // The sides (compositor, codecs, Wi-Fi) are let go here too, off the main thread (BackgroundRelease): once the worker is
                    // gone nothing else touches them; a next start builds its own.
                    "voice" to {
                        com.reau.app.dash.SilentDash.shared.notice = null
                        RiderVoice.uninstall(voice)
                        voice.release()
                    },
                    "worker" to { worker.shutdownNow() },
                    "sides" to {
                        val leaving = sides
                        sides = null
                        leaving?.release()
                        log("projection: the sides were let go in ${SystemClock.elapsedRealtime() - begun} ms, off the main thread")
                    },
                    "locks" to { locks.release() },
                    // Also a stop left half-way ("Desligando"): the screen never stays on it once the service is gone. What went wrong with a
                    // start that was refused (no compositor, no sides) stays to be read: the service leaves at once, and the reason would go with it.
                    "state" to {
                        val reason = current.value.problem
                        publish { if (current.value != Projection(problem = reason)) current.value = Projection(problem = reason) }
                    },
                ),
            ) { name, e -> log("${RideDiagnostics.WARN}projection: in onDestroy, $name failed: ${e.javaClass.simpleName}") }
            log("projection: service gone, locks released")
        }
        inForeground = false
        acceptedTypes = 0
        super.onDestroy()
    }

    /** For the log: a Ligar that finds the last Desligar still being carried out (it waits for it), and when that Desligar began. */
    private fun noteTapDuringStop(action: String?) {
        val now = SystemClock.elapsedRealtime()
        when (tapNote(action, current.value.phase, stopTappedAtMs)) {
            TapNote.STOP_TAPPED -> stopTappedAtMs = now
            TapNote.LIGAR_DURING_STOP ->
                log("projection: Ligar tapped ${now - stopTappedAtMs} ms after Desligar, while that stop is still being carried out: the start waits behind it")
            TapNote.NONE -> Unit
        }
    }

    /**
     * The machine takes [event] on the worker. [commandId] is the start id of the command that brought it: the
     * service is stopped with it, so a stop never ends a service that has meanwhile been asked to start again.
     */
    private fun handle(commandId: Int, event: ProjectionEvent, sidesFor: Boolean? = null, fresh: Boolean = true) = worker.execute {
        command = commandId
        // The rider's Ligar leaves its mark here, on the worker, after any stop still being carried out (whose release erases the mark): a resume keeps the one it has.
        if (fresh && sidesFor != null && resumable) runCatching { resumeBook.begin(System.currentTimeMillis(), card = sidesFor) }
        if (sidesFor != null && buildsSides(sidesFor, controller.state.phase)) buildSides(card = sidesFor)
        controller.submit(event)
        if (event == ProjectionEvent.Stop && stopTappedAtMs != 0L) {
            log("projection: the stop was carried out ${SystemClock.elapsedRealtime() - stopTappedAtMs} ms after Desligar was tapped")
            stopTappedAtMs = 0L
        }
        if (controller.state.phase == Phase.STOPPED) stopSelf(commandId)
    }

    /**
     * [sidesOfKind], with a failure kept to be told: the worker swallows what a task throws, and the service would stay in the
     * foreground on "Preparando" with no sides and no word. [Ports.prepare] refuses the start with this text instead.
     */
    @Suppress("TooGenericExceptionCaught") // whatever a factory throws is the start's problem, said to the rider and the log
    private fun buildSides(card: Boolean) {
        sidesFailure = try {
            sidesOfKind(card)
            null
        } catch (e: Exception) {
            log("${RideDiagnostics.ERROR}projection: the sides could not be built: ${e.javaClass.simpleName}")
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Makes sure the sides are of the kind about to be switched on: the card's or Android Auto's. Sides of the other
     * kind are let go first; whatever they report afterwards no longer reaches the machine.
     */
    private fun sidesOfKind(card: Boolean) {
        if (sides != null && sidesForCard == card) return
        sides?.let { old -> runCatching { old.release() }.onFailure { log("projection: releasing the sides failed: $it") } }
        sides = null // a factory that throws must not leave the sides just released in place
        val built = ++sidesBuilt
        val report: (ProjectionEvent) -> Unit = { event ->
            countDrop(event) // for the summary
            // Sides may still speak after the service is gone (a receiver thread finishing): then nobody listens.
            try {
                worker.execute { if (built == sidesBuilt) controller.submit(event) }
            } catch (e: RejectedExecutionException) {
                log("projection: a report after the service went: $event (${e.message})")
            }
        }
        sides = (if (card) cardSidesFactory else sidesFactory)(this, report)
        sidesForCard = card
    }

    /**
     * Android expects startForeground() after every startForegroundService(). The types are tried richest first
     * ([ForegroundTypes]: the microphone too with Gemini); false if every one was refused. With [restart] (the system started the service again
     * with no intent) the connected device type alone is asked for ([ProjectionResume.typesFor]).
     */
    private fun goForeground(restart: Boolean = false): Boolean {
        var why = ""
        // Gemini switched on and the permission to record given: the microphone type. The precise location permission given: the location type,
        // for the dash's Wi-Fi list with the app behind another one (and the info ring's GPS), whatever the ring or "Usar a localização".
        val fineLocationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val needs = foregroundNeeds(
            SettingsStore.open(this).load(),
            recordAudioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            fineLocationGranted = fineLocationGranted,
        )
        for (types in ProjectionResume.typesFor(restart, microphone = needs.microphone, location = needs.location)) {
            val refusal = try {
                ProjectionNotification.createChannel(this)
                ProjectionNotification.refreshAlertChannel(this)
                startForeground(ProjectionNotification.ID, ProjectionNotification.build(this, Headline.PREPARING), types)
                inForeground = true
                worker.execute { pacer.alreadyShown(Headline.PREPARING) }
                // What Android really accepted, not what was asked for (minSdk 29 has foregroundServiceType).
                acceptedTypes = foregroundServiceType
                (if (restart) ProjectionResume.foregroundNotes(acceptedTypes) else foregroundNotes(acceptedTypes, needs, fineLocationGranted)).forEach(::log)
                return true
            } catch (e: IllegalStateException) {
                e
            } catch (e: SecurityException) {
                e
            }
            why = "${refusal.javaClass.simpleName}: ${refusal.message}"
            log("${RideDiagnostics.ERROR}projection: foreground start refused for types $types — $why")
        }
        publish { current.value = Projection(problem = why) }
        return false
    }

    @Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
    private inner class Ports : ProjectionController.Ports {
        override fun prepare(): String? {
            // One ride log per projection, in <external files>/diag, where `adb pull` reaches it.
            RideDiagnostics.start("projection")
            // After the session's own start, so the reader finds it inside the projection it belongs to.
            resumedAttempt.takeIf { it > 0 }?.let {
                logResume(ProjectionResume.resumedLine(it, ProjectionResume.exemptionNote(batteryExemption())))
                armResumeClock()
            }
            val inUse = SettingsStore.open(this@ProjectionService).load().sanitized()
            RideDiagnostics.log("settings", SettingsInUse.line(inUse))
            // What the rider says he did on phones of the two makers with their own steps (26/11/2026): his word, not a reading; nothing on any other phone.
            runCatching {
                val brand = com.reau.app.ui.KeepAliveGuide.current()
                com.reau.app.data.KeepAliveChecklist.logAtStart(this@ProjectionService, com.reau.app.ui.KeepAliveGuide.hasNotice(brand))
            }
            marks.reset()
            // A new summary for this projection, written when it is switched off.
            ProjectionSummary.current = RideSummary().apply { start(SystemClock.elapsedRealtime(), batteryPct = null) }
            publish { started.value = SystemClock.elapsedRealtime() }
            // Sides that could not be built, or with no compositor, cannot project; the start fails here, with the reason, and the
            // machine gives the service back (Release) instead of connecting a dash that would never get video.
            refusal()?.let { return it }
            // The projection is back by itself and not refused: said in the headset, if the rider has the voice on (26/11/2026; the voice writes its own line when it speaks).
            ProjectionResume.voiceCueFor(resumedAttempt)?.let { RiderVoice.speak(it) }
            // The earlier runs' reports and the locks (together once 0.4 to 0.8 s on a phone) follow Android Auto's side, so
            // the start is not slow. On this same worker, so they come before any stop asked for after this start.
            worker.execute { housekeeping() }
            return null
        }

        /** Why this start cannot go on, said to the rider; null when it can. */
        private fun refusal(): String? = when {
            sidesFailure != null -> sidesFailure.also {
                sidesFailure = null
                log("${RideDiagnostics.ERROR}projection: refused, the sides could not be built")
            }
            sides?.videoReady == false -> {
                log("${RideDiagnostics.ERROR}projection: refused, ${ProjectionSides.COMPOSITOR_NOT_STARTED}")
                getString(R.string.problem_compositor)
            }
            else -> null
        }

        /** What a start needs but Android Auto's first dial does not: the earlier runs' reports, the locks, the device look. */
        private fun housekeeping() {
            val begun = SystemClock.elapsedRealtime()
            if (testOnlyHousekeepingMs > 0) Thread.sleep(testOnlyHousekeepingMs)
            CrashHandler.reportPrevious(this@ProjectionService)
            ExitHistory.reportPrevious(this@ProjectionService)
            // the zip of the last share holds logs; it does not wait in the cache
            DiagnosticsExport.forgetZips(java.io.File(cacheDir, DiagnosticsExport.SHARED_DIR))
            val reported = SystemClock.elapsedRealtime()
            // Switched off before this came (Desligar right after Ligar): nothing to hold.
            if (controller.state.phase == Phase.STOPPED) return
            locks.acquire()
            val took = "earlier runs read in ${reported - begun} ms, locks in ${SystemClock.elapsedRealtime() - reported} ms"
            log("projection: foreground with wake and Wi-Fi locks ($took)")
            lookAtTheDevice()
        }

        override fun release() {
            // The rider's Desligar, the stop for the dash's absence, a refused start: nothing is left to resume. Not when a start came after this stop (it has, or will make, its own mark).
            if (ForegroundHandover.dropsForeground(command, lastStart)) forgetResume()
            publish { started.value = null }
            // The browser switch goes off with the projection and stays off until the rider turns it on again.
            // Not when a start came after this stop: that run is the rider's now (it turned the switch off itself when it began).
            runCatching {
                if (com.reau.app.web.WebSwitch.resetsAtStop(command, lastStart) &&
                    com.reau.app.web.WebSwitch.switchOffStored(com.reau.app.settings.SettingsStore.open(this@ProjectionService))
                ) {
                    log("projection: the browser switch went off with the projection")
                }
            }
            stopLookingAtTheDevice()
            ProjectionNotification.clearPhoneHot(this@ProjectionService)
            // The low-battery notice is about this ride: it goes with it (26/11/2026; a resume after a death by the system is the same ride and keeps it).
            ProjectionNotification.clearBatteryLow(this@ProjectionService)
            // The Wi-Fi notice goes with the projection, and the voice lets its engine and the audio focus go (a next projection starts clean).
            ProjectionNotification.clearWifiOff(this@ProjectionService)
            // The notices about the dash that stay for the rider to read go with the projection.
            com.reau.app.dash.SilentDash.shared.reset()
            if (taken.end()) ProjectionNotification.clearDashTaken(this@ProjectionService)
            voice.release()
            RideDiagnostics.log(
                "projection",
                ProjectionSummary.current.line(
                    SystemClock.elapsedRealtime(), marks.count, noImageMs = com.reau.app.dash.ImageGap.clock.totalMs(com.reau.app.util.MonotonicClock.nowMs()),
                ),
            )
            deviceLook = null
            locks.release()
            // A start that came while this stop was carried out has taken the foreground; it stays.
            if (ForegroundHandover.dropsForeground(command, lastStart)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                inForeground = false
                acceptedTypes = 0
            } else {
                log("projection: a start came after this stop: the foreground stays")
            }
            RideDiagnostics.stop("projection")
            // With the id of the latest command, not only the stop's own: a mark or a refit that came after it would leave the service started and idle (25/11/2026).
            // latestCommand is read BEFORE lastStart (26/11/2026): the main thread may handle a Ligar (lastStart rises) and then a refit (latestCommand rises) between the
            // two reads. Read in this order the worst pair is a new lastStart with an old latestCommand, whose stopSelf does nothing (the Ligar's service stays up); read the
            // other way round, the old lastStart with the refit's id would end the service of the new Ligar.
            val latest = latestCommand
            stopSelf(ForegroundHandover.stopId(command, lastStart, latest))
        }

        override fun startAndroidAuto() = sides?.startAndroidAuto() ?: Unit
        override fun stopAndroidAuto() = sides?.stopAndroidAuto() ?: Unit
        override fun startDash() = sides?.startDash() ?: Unit
        override fun stopDash() = sides?.stopDash() ?: Unit
        override fun startVideo() = sides?.startVideo() ?: Unit
        override fun stopVideo() = sides?.stopVideo() ?: Unit

        override fun dashVisible(): Boolean = sides?.dashVisible ?: false

        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            if (worker.isShutdown) return {}
            val pending = worker.schedule(action, delayMs, TimeUnit.MILLISECONDS)
            return { pending.cancel(false) }
        }
    }

    /**
     * The phone's state every [DEVICE_LOOK_MS]; [DeviceStateLog] writes the first look,
     * one every 30 s and every relevant change.
     */
    private fun lookAtTheDevice() {
        val reader = DeviceStateReader(this) { sides?.bikeNetwork }
        // After a sign of memory pressure the line comes at every look for a minute (PressureSignals.periodAt), then back to the 30 s.
        val signals = com.reau.app.data.MemoryPressure.signals
        val device = DeviceStateLog(DEVICE_PERIOD_MS, signals::periodAt) { RideDiagnostics.log("device", it) }
        // The standby bucket and the background restriction: one line at the first look of the projection, another only when one of them changes (26/11/2026).
        val standby = AppStandbyLog { RideDiagnostics.log("device", it) }
        // Armed for this projection: one warning when the battery passes 44 C, another only after it fell under 41 C (BatteryHeatWatch).
        val heat = BatteryHeatWatch()
        // Armed for this trip: one warning when the battery is at 10 % or under with no charger, never a second one (BatteryLowWatch, 26/11/2026); a projection resumed
        // after a death by the system is the same trip, and the mark says whether the warning was already given.
        val told = runCatching { resumeBook.batteryTold() }.getOrDefault(false)
        val low = BatteryLowWatch(alreadyTold = told)
        if (told) RideDiagnostics.log("battery", "the low-battery notice was given before the system closed REau: not given again in this ride")
        // Said once for this projection: the reader does not ask for the thermal headroom in this build (the lines have no headroom=).
        RideDiagnostics.log("device", com.reau.app.data.ThermalHeadroomNote.LINE)
        // Said once for this projection: the alert channel muted or blocked in Android (the main screen says it too).
        AlertChannelState.line(AlertChannelState.read(this))?.let { RideDiagnostics.warn("projection", it) }
        // [why] null: the look of every 5 s (the line only when its period is due or something relevant changed); else a memory sign's reason, and the line at once.
        val look = { why: String? ->
            runCatching {
                // The sign of life the resume's mark is renewed with, once a minute at most (ProjectionResumeBook).
                resumeBook.beat(System.currentTimeMillis(), dashUp = ProjectionResume.dashUp(controller.state.dash))
                val state = reader.read()
                standby.offer(reader.appStandby())
                // The same clock as the memory signs' (REauApp writes PressureSignals with MonotonicClock): with SystemClock.elapsedRealtime here the minute of
                // faster lines never came, the two readings being hours apart (found in the review of 26/11/2026).
                val nowMs = com.reau.app.util.MonotonicClock.nowMs()
                if (why == null) device.offer(nowMs, state) else device.force(nowMs, state, why)
                ProjectionSummary.current.device(state.thermal, state.batteryPct)
                warnIfHot(heat, state)
                warnIfBatteryLow(low, state)
            }
                .onFailure { log("${RideDiagnostics.WARN}projection: reading the device state failed: $it") }
        }
        deviceLook?.cancel(false)
        deviceLook = worker.scheduleWithFixedDelay({ look(null) }, 0, DEVICE_LOOK_MS, TimeUnit.MILLISECONDS)
        // A memory sign comes on the main thread: the look is handed to the worker, which is where the reader (and its state) lives.
        val onSign: (String) -> Unit = { why -> runCatching { worker.execute { look(why) } } }
        pressureLook = onSign
        signals.attach(onSign)
    }

    /** The projection no longer looks at the phone: the memory signs go back to writing their own line only. */
    private fun stopLookingAtTheDevice() {
        deviceLook?.cancel(false)
        pressureLook?.let { com.reau.app.data.MemoryPressure.signals.detach(it) }
        pressureLook = null
    }

    /** The rider's notice that the phone is hot ([BatteryHeatWatch]), and its line in the ride log; the notice goes when the phone has cooled. */
    private fun warnIfHot(heat: BatteryHeatWatch, state: com.reau.app.data.DeviceState) {
        val temp = state.batteryTempC
        val text = temp?.let { String.format(java.util.Locale.US, "%.1f", it) }
        when (heat.offer(temp)) {
            BatteryHeatWatch.Verdict.WARN -> {
                RideDiagnostics.warn(
                    "heat",
                    "battery at $text C (charging=${state.charging}, thermal=${state.thermal}): " +
                        "at or over ${BatteryHeatWatch.HOT_C} C, rider told the phone is hot",
                )
                ProjectionNotification.showPhoneHot(this, Math.round(temp ?: BatteryHeatWatch.HOT_C))
                RiderVoice.speak(VoiceCue.PHONE_HOT)
            }
            BatteryHeatWatch.Verdict.COOLED -> {
                RideDiagnostics.log("heat", "battery back under ${BatteryHeatWatch.COOL_C} C ($text C): a new warning is possible")
                ProjectionNotification.clearPhoneHot(this)
            }
            BatteryHeatWatch.Verdict.NONE -> Unit
        }
    }

    /**
     * The rider's notice that the battery is nearly out with no charger ([BatteryLowWatch]), once per trip, and its line in the ride log: the level, and
     * whether the notice was posted or the rider has the app's notifications off (26/11/2026; the ride of 25/11 went from 12 % to 4 % in 2 minutes).
     */
    private fun warnIfBatteryLow(low: BatteryLowWatch, state: com.reau.app.data.DeviceState) {
        val pct = state.batteryPct ?: return
        // The pill waits for the dash: drawn with no dash connected it would run its 5 s unseen (26/11/2026); the phone's notice goes at once.
        val dashUp = ProjectionResume.dashUp(controller.state.dash)
        if (low.offer(pct, state.charging)) {
            val shown = ProjectionNotification.showBatteryLow(this, pct)
            runCatching { resumeBook.recordBatteryTold() }
            val onDash = low.pillDue(dashUp) && sides?.showBatteryLow(pct) == true
            val pill = if (onDash) "asked" else if (low.pillPending) "waiting for the dash" else "none"
            RideDiagnostics.warn(
                "battery",
                "battery at $pct% with no charger (at or under ${BatteryLowWatch.LOW_PCT}%): rider told the phone may shut down " +
                    "(phone notice: ${if (shown) "shown" else "not shown, the app's notifications are off"}; dash pill: $pill)",
            )
        } else if (low.pillDue(dashUp) && sides?.showBatteryLow(pct) == true) {
            RideDiagnostics.log("battery", "battery pill asked on the dash at $pct% (it had waited for the dash)")
        }
    }

    /**
     * The notices a change of state asks for ([StateAlerts]): the phone's Wi-Fi being off is shown in the alert channel with a button to Android's Wi-Fi
     * panel, and said by the voice when the rider asked for it; the dash being back is said too (only after its going was, [VoiceRules]).
     */
    private fun tellTheRider(before: Projection, after: Projection) {
        for (alert in StateAlerts.between(before, after)) {
            when (alert) {
                StateAlerts.Alert.WIFI_OFF_SHOW -> {
                    log("projection: the phone's Wi-Fi is off: the rider is told, with the way to Android's Wi-Fi panel")
                    ProjectionNotification.showWifiOff(this)
                    RiderVoice.speak(VoiceCue.WIFI_OFF)
                }
                StateAlerts.Alert.WIFI_OFF_CLEAR -> ProjectionNotification.clearWifiOff(this)
                StateAlerts.Alert.DASH_BACK -> RiderVoice.speak(VoiceCue.DASH_BACK)
            }
        }
        // Another app holding the dash's port: told once for the episode, in the alert channel, and taken away when REau connects ([TakenNotice]).
        val running = after.phase == Phase.RUNNING
        when (taken.link(taken = running && after.dash == DashLink.TAKEN, ready = running && after.dash == DashLink.READY)) {
            com.reau.app.dash.TakenNotice.Edge.SHOW -> {
                log("projection: another app holds the dash's port: the rider is told once, in the alert channel")
                ProjectionNotification.showDashTaken(this)
            }
            com.reau.app.dash.TakenNotice.Edge.CLEAR -> ProjectionNotification.clearDashTaken(this)
            com.reau.app.dash.TakenNotice.Edge.NONE -> Unit
        }
    }

    /** A drop of Android Auto's side, for the summary. */
    private fun countDrop(event: ProjectionEvent) {
        if (isAaDrop(event)) ProjectionSummary.current.aaDropped()
    }

    /** The dash side went from [before] to [after], for the summary: a drop, a failed try, or nothing to count. */
    private fun countDash(before: DashLink, after: DashLink) {
        when (DashDrops.of(before, after)) {
            DashDrops.Kind.DROP -> ProjectionSummary.current.dashDropped(SystemClock.elapsedRealtime())
            DashDrops.Kind.FAILED_TRY -> ProjectionSummary.current.dashTryFailed()
            DashDrops.Kind.NONE -> Unit
        }
    }

    private fun log(line: String) = RideDiagnostics.log("projection", line)
}
