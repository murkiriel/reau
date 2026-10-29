package com.reau.app.service

import kotlinx.coroutines.flow.update
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.util.size
import androidx.core.view.doOnPreDraw
import com.reau.app.MainActivity
import com.reau.app.R
import com.reau.app.aa.AndroidAuto
import com.reau.app.aa.FrontWindow
import com.reau.app.aa.LineThrottle
import com.reau.app.aa.ReadGate
import com.reau.app.aa.ReadWatch
import com.reau.app.aa.ServerMenuRun
import com.reau.app.aa.ServerNotification
import com.reau.app.util.distinctByIdentity
import com.reau.app.data.RideDiagnostics

/**
 * REau's accessibility service, for one thing only (switching Android Auto's head unit server on or off): walking
 * Android Auto's settings menu to "Iniciar" or "Parar
 * servidor da unidade principal" ([ServerMenuRun] decides every step, this carries them out), and keeping the "Stop"
 * of that server's notification. Its configuration (res/xml/aa_server_accessibility.xml) lets it hear Android Auto
 * and the voice apps' call notifications (the ride log only, [voiceCall]); it reads no other app's windows
 * and touches nothing outside a walk the starter ([AaServerStarter]) asked for.
 *
 * While a walk goes on, an opaque cover (an accessibility overlay: no extra permission) hides Android Auto's screen,
 * takes the rider's touches so none lands on the menu by mistake, and offers "Cancelar". The clicks are actions on the
 * nodes, which the cover does not block.
 */
// Android's callbacks, the walk's steps and the cover: all one service's, around the same walk.
@Suppress("TooManyFunctions")
class AaServerAccessibilityService : AccessibilityService(), AaServerStarter.MenuDriver {
    /** The app whose menu is walked and how its screen opens: Android Auto, or a test's own screen. */
    internal class Target(val packageName: String, val open: () -> Intent)

    companion object {
        /** The running service, while Android keeps it connected. */
        @Volatile private var connected: AaServerAccessibilityService? = null

        /** The calls switch changed (MainActivity): the running service hears the voice apps or stops hearing them. */
        fun followCallsSwitch(answerCalls: Boolean) {
            val service = connected ?: return
            service.main.post { service.hear(answerCalls) }
        }

        /** Test-only: whether the running service's cover is up (main thread). */
        internal fun testOnlyCoverShown(): Boolean = connected?.cover?.shown == true

        /** Test-only: the apps the running service hears now, or null when it is not connected. */
        internal fun testOnlyHeard(): Set<String>? = connected?.serviceInfo?.packageNames?.toSet()

        /** Tests point it at their own screen. */
        @Volatile internal var target = Target(AndroidAuto.PACKAGE) { AndroidAuto.settingsIntent() }

        private const val TAG = "aa-server"
        private const val POLL_MS = 250L

        /** How long after "start" was touched the screen is read once, for the log. */
        private const val AFTER_CLICK_MS = 300L

        /** The cover stays this long after a walk, over the way back and Android Auto's "Iniciando servidor…". */
        private const val COVER_AFTER_MS = 1_000L

        /** Whatever happens, the cover goes after this long: more than the longest walk and the cover's tail ([ServerMenuRun.coverWatchdogMs]). */
        private val COVER_WATCHDOG_MS = ServerMenuRun.coverWatchdogMs(COVER_AFTER_MS)

        /** A screen is never read past this many nodes. */
        private const val MAX_NODES = 600

        /** After the hold, a little more before reading the screen again. */
        private const val READ_AGAIN_SLACK_MS = 50L
        private const val PROBE_VIEWS = 150
        private const val PROBE_DEPTH = 8
    }

    private val main = Handler(Looper.getMainLooper())

    // Every read of Android Auto's windows and nodes, and every touch on one, runs on this thread: with Android Auto's process
    // gone such a call does not answer for about 5 s, and on the main thread it froze REau's screen for that long (`Skipped 301
    // frames`, three times in nine stops). The main thread only decides; a read that does not answer in [ReadWatch.LIMIT_MS] ends the walk.
    // Made when the service connects and ended when it goes (the same instance may connect again).
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private var workerOpen = false
    private val screenReadQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var rereadWanted = false
    private val reads = ReadWatch()

    // The package of the last window-state event heard, from the events: [lastWindowWasAa] never asks the screen.
    @Volatile private var frontPackage: String? = null
    private var run: ServerMenuRun? = null
    private var done: ((ServerMenuRun.Outcome) -> Unit)? = null
    private var begunMs = 0L
    @Volatile private var lastChangeMs = 0L

    // How often events make the service read the screen or step the walk (the rule is ReadGate); each has its own planned late read.
    @Volatile private var answerCallsNow = false
    private val screenGate = ReadGate()
    private val tickGate = ReadGate()
    private val readScreenLater = Runnable { readScreen() }
    private val tickLater = Runnable { tick() }

    // The nodes of the last look, by SeenNode.ref: what a Click step clicks.
    private val refs = mutableListOf<AccessibilityNodeInfo>()
    private val cover = Cover()

    // A cover put up at the rider's tap, before its walk (TapCover), and when it goes if no walk comes.
    private var held = false
    private val heldGone = Runnable { dropCover() }
    private val poll = object : Runnable {
        override fun run() {
            if (run == null) return
            tick()
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        workerThread = HandlerThread("aa-server-reads").also { it.start() }
        worker = Handler(workerThread.looper)
        workerOpen = true
        AaServerStarter.driver = this
        // The screen read again after inputs and every second while the projection runs
        com.reau.app.aa.AaScreens.rereader = rereader
        if (com.reau.app.aa.AaScreens.projecting) rereader.projection(true)
        log("accessibility service connected")
        connected = this
        AaServerStarter.serviceChanges.update { it + 1 }
        hear(com.reau.app.settings.SettingsStore.open(this).load().answerCalls)
        // The server card was up for want of this service: the walk is made again now, without the rider's touch.
        AaServerStarter.serviceConnected(applicationContext)
    }

    /** For privacy, the apps heard narrowed to [AccessibilityScope] for the calls switch. */
    private fun hear(answerCalls: Boolean) {
        answerCallsNow = answerCalls
        val info = serviceInfo ?: return
        val apps = AccessibilityScope.packages(answerCalls)
        info.packageNames = apps.toTypedArray()
        serviceInfo = info
        log("the accessibility service hears: ${apps.sorted().joinToString(", ")}")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        gone("unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        gone("destroyed")
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return notification(event)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) frontPackage = event.packageName?.toString()
        // The voice apps are heard for their call notifications only, never for their windows
        if (event.packageName?.toString() in com.reau.app.phone.CallNotice.VOICE_APPS) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            readScreenSoon()
        }
        // Which Android Auto window comes to the front while the server is awaited: the log tells a server never started from a late one.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName?.toString() == AndroidAuto.PACKAGE) {
            AaServerStarter.aaWindowResumed(event.className?.toString(), walking = run != null)
        }
        if (run == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && event.packageName?.toString() == target.packageName) {
            lastChangeMs = SystemClock.elapsedRealtime()
        }
        tickSoon()
    }

    /**
     * A window event: the screen is read when the projection follows it or a walk is on, and not more often than [ReadGate.MIN_GAP_MS]
     * (an event inside the gap is read when the gap ends); with nothing needing it, not at all.
     */
    private fun readScreenSoon() {
        val needed = com.reau.app.aa.AaScreens.projecting || run != null
        when (val verdict = screenGate.onEvent(needed, SystemClock.elapsedRealtime())) {
            ReadGate.Verdict.Skip -> Unit
            ReadGate.Verdict.Now -> readScreen()
            is ReadGate.Verdict.Later -> if (verdict.first) main.postDelayed(readScreenLater, verdict.afterMs)
        }
    }

    /** A content event of the walk: a step, but not more often than [ReadGate.MIN_GAP_MS] (the poll steps every [POLL_MS] anyway). */
    private fun tickSoon() {
        when (val verdict = tickGate.onEvent(needed = true, nowMs = SystemClock.elapsedRealtime())) {
            ReadGate.Verdict.Now -> tick()
            is ReadGate.Verdict.Later -> if (verdict.first) main.postDelayed(tickLater, verdict.afterMs)
            ReadGate.Verdict.Skip -> Unit
        }
    }

    override fun drive(run: ServerMenuRun, done: (ServerMenuRun.Outcome) -> Unit): Boolean {
        if (this.run != null) return false
        this.run = run
        this.done = done
        begunMs = SystemClock.elapsedRealtime()
        lastChangeMs = 0
        cover.acquire(com.reau.app.ui.ScreenText.cover(run.mode), run.mode, begunMs, "the walk began")
        // A cover already up from the rider's tap is the walk's now.
        dropCover()
        tick()
        main.postDelayed(poll, POLL_MS)
        return true
    }

    override fun holdCover(mode: ServerMenuRun.Mode, maxMs: Long) {
        if (held || run != null) return
        held = true
        cover.acquire(com.reau.app.ui.ScreenText.cover(mode), mode, SystemClock.elapsedRealtime(), "the rider's tap")
        main.postDelayed(heldGone, maxMs)
    }

    override fun lastWindowWasAa(): Boolean = frontPackage == AndroidAuto.PACKAGE

    override fun dropCover() {
        if (!held) return
        held = false
        main.removeCallbacks(heldGone)
        cover.release(0)
    }

    private fun gone(why: String) {
        if (connected === this) connected = null
        if (AaServerStarter.driver === this) AaServerStarter.driver = null
        AaServerStarter.serviceChanges.update { it + 1 }
        if (com.reau.app.aa.AaScreens.rereader === rereader) com.reau.app.aa.AaScreens.rereader = null
        main.removeCallbacks(rereadTick)
        main.removeCallbacks(readScreenLater)
        main.removeCallbacks(tickLater)
        screenGate.reset()
        tickGate.reset()
        if (run != null) end(ServerMenuRun.Outcome.CANCELLED)
        reads.reset()
        // The nodes still kept are given back on the worker (the only thread that touches them); then the thread ends.
        // The system unbinds and then destroys the service, and both come here: the thread ends with the first.
        if (workerOpen) {
            workerOpen = false
            worker.post { clearRefs() }
            workerThread.quitSafely()
        }
        cover.removeNow()
        log("accessibility service $why")
    }

    /**
     * One step of the walk. The screen is read on the worker; the answer comes back here ([applyStep]). A read that has not
     * answered in [ReadWatch.LIMIT_MS] means Android Auto's process is gone: the walk ends as [ServerMenuRun.Outcome.AA_GONE].
     */
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        tickGate.read(now)
        val walk = run ?: return
        cover.step(walk.coverStep)
        // Holding Android Auto in front after "start": the receiver dials at every tick (250 ms) instead of every 2 s.
        if (walk.holding) {
            AaServerStarter.dialNow()
            cover.text(com.reau.app.ui.ScreenText.coverWaiting())
        }
        if (reads.inFlight()) {
            if (reads.overdue(now)) {
                log("AaServerStarter: Android Auto did not answer a read of its screen in ${ReadWatch.LIMIT_MS} ms: it is gone")
                end(walk.gone())
            }
            return
        }
        val id = reads.start(now)
        // What only the main thread knows is taken here; the worker reads the screen.
        val portUp = AaServerStarter.portUp()
        val locked = locked()
        worker.post {
            val tookUpAt = SystemClock.elapsedRealtime()
            main.post { reads.begin(id, tookUpAt) }
            val seen = look(walk.target, portUp, locked)
            main.post { if (reads.finish(id) && run === walk) applyStep(walk, seen) }
        }
    }

    /** What the walk does with what the screen holds. */
    private fun applyStep(walk: ServerMenuRun, seen: ServerMenuRun.Seen) {
        when (val step = walk.next(seen)) {
            ServerMenuRun.Step.Wait -> Unit
            ServerMenuRun.Step.OpenSettings -> open(walk, seen)
            is ServerMenuRun.Step.Click -> click(walk, seen, step)
            ServerMenuRun.Step.Back -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                step(seen, "back")
            }
            ServerMenuRun.Step.OpenREau -> {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                step(seen, "REau brought back")
                tick()
            }
            is ServerMenuRun.Step.Done -> {
                log("AaServerStarter: the walk is over with ${seen.front ?: "no window"} in front (${step.outcome})")
                end(step.outcome)
            }
        }
    }

    /** The touch is made on the worker (it can wait on a dead process), and its answer is told here. */
    private fun click(walk: ServerMenuRun, seen: ServerMenuRun.Seen, step: ServerMenuRun.Step.Click) {
        worker.post {
            val clicked = refs.getOrNull(step.node.ref)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            main.post {
                step(seen, "clicked ${step.what}" + if (clicked) "" else " (refused)")
                if (!clicked && run === walk) walk.clickRefused(seen.nowMs)
                if (clicked && step.what == "start") logAfterStartClick(walk.target)
            }
        }
    }

    private fun open(walk: ServerMenuRun, seen: ServerMenuRun.Seen) {
        val refusal = try {
            startActivity(target.open())
            null
        } catch (e: ActivityNotFoundException) {
            e
        } catch (e: SecurityException) {
            e
        }
        if (refusal == null) return step(seen, "opened the settings")
        log("AaServerStarter: settings did not open (${refusal.javaClass.simpleName}: ${refusal.message})")
        walk.openFailed()
        tick()
    }

    private fun end(outcome: ServerMenuRun.Outcome) {
        val finished = done
        // Where the walk got to: every step green only when it did what it came for (a failed walk's ring stays gold).
        val lastStep = run?.coverStep ?: ALL_STEPS_DONE
        run = null
        done = null
        reads.reset()
        worker.post { clearRefs() }
        main.removeCallbacks(poll)
        cover.step(lastStep)
        cover.release(if (outcome == ServerMenuRun.Outcome.CANCELLED) 0 else COVER_AFTER_MS)
        finished?.invoke(outcome)
    }

    /** A moment after "start" was touched: is Android Auto in front, how many nodes does it show, and does it say starting or started. */
    private fun logAfterStartClick(packageName: String) {
        val clickedAt = SystemClock.elapsedRealtime()
        main.postDelayed({
            worker.post {
                val active = rootInActiveWindow
                val inFront = active?.packageName?.toString() == packageName
                active?.free()
                val texts = mutableListOf<String?>()
                for (window in windows.filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }) {
                    val root = window.root ?: continue
                    if (root.packageName?.toString() == packageName) collectTexts(root, texts)
                    root.free()
                }
                val took = SystemClock.elapsedRealtime() - clickedAt
                main.post { AaServerStarter.afterStartClick(inFront, took, texts, AaServerStarter.texts(this)) }
            }
        }, AFTER_CLICK_MS)
    }

    /** The texts and descriptions under [node], without touching the nodes the walk keeps for its clicks. */
    private fun collectTexts(node: AccessibilityNodeInfo, into: MutableList<String?>) {
        if (into.size >= MAX_NODES) return
        val text = node.text?.toString()?.takeIf { it.isNotBlank() } ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (text != null) into += text
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, into)
            child.free()
        }
    }

    /** Gives a node back to the system on Android before 13, which pooled them (13+ ignores it): ours, never one the walk keeps in [refs]. */
    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.free() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recycle()
    }

    /** The nodes the last look kept for its clicks are let go, and forgotten. */
    private fun clearRefs() {
        // One clickable ancestor stands in [refs] once for each of its descendants with a text: each node is given back once only
        // (a second recycle() throws "Already in the pool" before Android 13).
        refs.distinctByIdentity().forEach { it.free() }
        refs.clear()
    }

    private fun step(seen: ServerMenuRun.Seen, what: String) = log("AaServerStarter: +${seen.nowMs - begunMs} ms $what")

    /** What the screen holds: the window in front, and every node with a text or a description in [packageName]'s windows. */
    private fun look(packageName: String, portUp: Boolean, locked: Boolean): ServerMenuRun.Seen {
        clearRefs()
        val nodes = mutableListOf<ServerMenuRun.Node>()
        val active = rootInActiveWindow
        // Never the cover: it is this service's own window, and on a test's screen it would read as the menu's list.
        val onPhone = windows.filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        val roots = onPhone.mapNotNull { window -> window.root?.let { window.id to it } }
            .ifEmpty { listOfNotNull(active?.let { it.windowId to it }) }
        // In front: the window of the phone's own screen, not the system's active window (it may be one of Android Auto's virtual displays)
        val windowsOnPhone = onPhone.map { window ->
            val packageName = roots.firstOrNull { it.first == window.id }?.second?.packageName?.toString()
            FrontWindow.Win(packageName, window.isActive, window.isFocused)
        }
        val front = FrontWindow.of(windowsOnPhone, fallbackActive = active?.packageName?.toString())
        for ((window, root) in roots) {
            if (root.packageName?.toString() == packageName) collect(root, window, null, nodes)
        }
        val seen = ServerMenuRun.Seen(
            nowMs = SystemClock.elapsedRealtime(),
            front = front,
            locked = locked,
            nodes = nodes,
            lastChangeMs = lastChangeMs, // read here, when the screen is: an event during the read counts
            portUp = portUp,
        )
        // The roots are let go unless a click keeps one of them.
        roots.forEach { (_, root) -> if (refs.none { it === root }) root.free() }
        if (active != null && refs.none { it === active } && roots.none { it.second === active }) active.free()
        return seen
    }

    private fun collect(node: AccessibilityNodeInfo, window: Int, clickable: AccessibilityNodeInfo?, into: MutableList<ServerMenuRun.Node>) {
        if (into.size >= MAX_NODES) return
        val clickTarget = if (node.isClickable) node else clickable
        val text = node.text?.toString()
        val description = node.contentDescription?.toString()
        if (!text.isNullOrBlank() || !description.isNullOrBlank()) {
            into += ServerMenuRun.Node(window, text, description, clickable = clickTarget != null, ref = refs.size)
            refs += clickTarget ?: node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, window, clickTarget, into)
            if (refs.none { it === child }) child.free()
        }
    }

    private fun locked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true || getSystemService(PowerManager::class.java)?.isInteractive == false

    /** Android Auto's server notification; its one action, "Stop", is kept for the end of the projection. */
    private fun notification(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg != AndroidAuto.PACKAGE) return pkg?.let { voiceCall(it, event) } ?: Unit
        val notification = event.parcelableData as? Notification ?: return
        val action = notification.actions?.singleOrNull()
        val intent = action?.actionIntent
        val seen = ServerNotification.Seen(
            channel = notification.channelId,
            actions = notification.actions?.size ?: 0,
            creator = intent?.creatorPackage,
            isService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) intent?.isService else null,
            actionTitle = action?.title?.toString(),
            text = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        if (intent != null && ServerNotification.isServerStop(seen, AaServerStarter.texts(this))) {
            AaServerStarter.stopButton = intent
            log("AaServerStarter: the server's notification is up, its Stop kept")
        }
    }

    /**
     * A voice app's call notification, said in the ride log with the switch "Atender ligação pelo joystick" on: its
     * style, type and buttons, and whether Telecom sees a call; never its title or text (the caller). For measuring
     * these notifications on a phone before the joystick answers them.
     */
    private fun voiceCall(pkg: String, event: AccessibilityEvent) {
        val notification = event.parcelableData as? Notification ?: return
        if (!com.reau.app.phone.CallNotice.isCall(pkg, notification.category)) return
        // Kept in memory (set by hear, when the switch changes): the settings were read from the disk at every notification.
        if (!answerCallsNow) return
        val extras = notification.extras
        val phone = com.reau.app.phone.PhoneCalls(this)
        val notice = com.reau.app.phone.CallNotice(
            pkg = pkg,
            template = extras?.getString(com.reau.app.phone.CallNotice.EXTRA_TEMPLATE),
            callType = extras?.takeIf { it.containsKey(com.reau.app.phone.CallNotice.EXTRA_CALL_TYPE) }
                ?.getInt(com.reau.app.phone.CallNotice.EXTRA_CALL_TYPE),
            answer = extras?.containsKey(com.reau.app.phone.CallNotice.EXTRA_ANSWER_INTENT) == true,
            decline = extras?.containsKey(com.reau.app.phone.CallNotice.EXTRA_DECLINE_INTENT) == true,
            hangUp = extras?.containsKey(com.reau.app.phone.CallNotice.EXTRA_HANG_UP_INTENT) == true,
            actionTitles = notification.actions.orEmpty().map { it.title?.toString().orEmpty() },
            telecomInCall = phone.telecomInCall(),
        )
        RideDiagnostics.log("call", notice.line())
    }

    private fun log(line: String) = RideDiagnostics.log(TAG, line)

    // The last raw reading said in the log, so a ride logs each change of Android Auto's displays once.
    private var lastRaw = ""
    private val rawThrottle = LineThrottle()
    private val readAgain = Runnable { readScreen() }

    // Reads without a window event, on this service's main thread only.
    private val rereads = com.reau.app.aa.AaScreenRereads()
    private val rereadTick: Runnable = Runnable {
        if (rereads.due(SystemClock.elapsedRealtime())) readScreen(reread = true)
        planRereads()
    }
    private val rereader = object : com.reau.app.aa.AaScreens.Rereader {
        override fun inputSent() {
            main.post {
                rereads.inputSent(SystemClock.elapsedRealtime())
                planRereads()
            }
        }

        override fun projection(on: Boolean) {
            main.post {
                rereads.projection(on, SystemClock.elapsedRealtime())
                planRereads()
            }
        }
    }

    /** One pending re-read at a time, at the next time [rereads] asks for. */
    private fun planRereads() {
        main.removeCallbacks(rereadTick)
        val at = rereads.nextAt() ?: return
        main.postDelayed(rereadTick, (at - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    /**
     * Which of Android Auto's screens is shown, from the display whose window has focus
     * (each screen of Android Auto is a virtual display of its own). Android 11+ (windows of every display). The system
     * says a window change once: while a new screen waits to hold, it is read again after the hold.
     */
    private fun readScreen(reread: Boolean = false) {
        screenGate.read(SystemClock.elapsedRealtime())
        // One read waiting at a time: with Android Auto dead each takes 5 s, and one a second would pile up behind the walk's reads.
        if (reread) rereadWanted = true
        if (screenReadQueued.compareAndSet(false, true)) {
            worker.post {
                screenReadQueued.set(false)
                val again = rereadWanted
                rereadWanted = false
                readScreenNow(again)
            }
        }
    }

    /** [readScreen] on the worker thread (the windows of a dead Android Auto do not answer for 5 s). */
    private fun readScreenNow(reread: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val byDisplay = runCatching { windowsOnAllDisplays }.getOrNull() ?: return
        val names = getSystemService(android.hardware.display.DisplayManager::class.java)
        val displays = (0 until byDisplay.size).map { i ->
            val id = byDisplay.keyAt(i)
            val windows = byDisplay.valueAt(i)
            com.reau.app.aa.AaScreenRule.Display(id, names?.getDisplay(id)?.name.orEmpty(), windows.any { it.isFocused }, windows.any { it.isActive })
        }
        val raw = displays.filter { com.reau.app.aa.AaScreenRule.PROJECTED in it.name }
            .joinToString {
                val short = com.reau.app.aa.AaScreenRule.shortName(it.name)
                "${it.id} $short${if (it.focused) " focused" else ""}${if (it.active) " active" else ""}"
            }
        if (raw != lastRaw) {
            lastRaw = raw
            // At most one line a second, with the count of the changes it stands for (224 lines in a day, one every 1 to 5 s for 15 minutes).
            rawThrottle.offer(SystemClock.elapsedRealtime(), "displays: ${raw.ifEmpty { "none of Android Auto's" }}")
                ?.let { RideDiagnostics.log("aa-screen", it) }
        }
        if (com.reau.app.BuildConfig.DEBUG) probeWindows(byDisplay, names)
        val watch = com.reau.app.aa.AaScreens.watch
        watch.update(displays, SystemClock.elapsedRealtime(), reread)
        main.post {
            main.removeCallbacks(readAgain)
            if (watch.waiting) main.postDelayed(readAgain, watch.holdNowMs(SystemClock.elapsedRealtime()) + READ_AGAIN_SLACK_MS)
        }
    }

    private val probeDedupe = com.reau.app.aa.AaWindowProbe.Dedupe()

    /**
     * Debug build only: whether Android Auto's windows say which app is in front ([com.reau.app.aa.AaWindowProbe]). One line per window of
     * Android Auto, written when they change; on the worker thread, with the windows [readScreenNow] just read.
     */
    private fun probeWindows(byDisplay: android.util.SparseArray<List<AccessibilityWindowInfo>>, names: android.hardware.display.DisplayManager?) {
        val lines = (0 until byDisplay.size).flatMap { i ->
            val id = byDisplay.keyAt(i)
            val display = names?.getDisplay(id)?.name.orEmpty()
            byDisplay.valueAt(i).mapNotNull { probeLine(id, display, it) }
        }
        probeDedupe.offer(SystemClock.elapsedRealtime(), lines)?.lines()?.forEach { RideDiagnostics.log("aa-probe", it) }
    }

    /**
     * The line for [window] of display [id] ([display]'s name), or null when it is not one to look at: on Android Auto's own displays
     * (the ghost ones) a window of any app, to learn whether the app in front shows its package there (21/10/2026: the gearhead's
     * windows were empty and told nothing); elsewhere only those of Android Auto.
     */
    private fun probeLine(id: Int, display: String, window: AccessibilityWindowInfo): String? {
        val root = runCatching { window.root }.getOrNull() ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != com.reau.app.aa.AndroidAuto.PACKAGE && com.reau.app.aa.AaScreenRule.PROJECTED !in display) return null
        val title = window.title?.toString()
        val side = com.reau.app.aa.AaWindowProbe.isSideBar(display) || title?.let(com.reau.app.aa.AaWindowProbe::isSideBar) == true
        val state = com.reau.app.aa.AaWindowProbe.State(window.isFocused, window.isActive, window.layer, pkg)
        val name = "$id ${com.reau.app.aa.AaScreenRule.shortName(display)}"
        return com.reau.app.aa.AaWindowProbe.line(name, title, state, probeNodes(root), side)
    }

    /** The views of a window, breadth first, a bounded number of them. */
    private fun probeNodes(root: AccessibilityNodeInfo): List<com.reau.app.aa.AaWindowProbe.Node> {
        val out = mutableListOf<com.reau.app.aa.AaWindowProbe.Node>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && out.size < PROBE_VIEWS) {
            val (node, depth) = queue.removeFirst()
            out += probeNode(node)
            if (depth < PROBE_DEPTH) (0 until node.childCount).mapNotNullTo(queue) { c -> node.getChild(c)?.let { it to depth + 1 } }
        }
        return out
    }

    private fun probeNode(node: AccessibilityNodeInfo) = com.reau.app.aa.AaWindowProbe.Node(
        cls = node.className?.toString().orEmpty(),
        id = node.viewIdResourceName,
        desc = node.contentDescription?.toString()?.takeIf { it.isNotEmpty() },
        text = node.text?.toString()?.takeIf { it.isNotEmpty() },
        selected = node.isSelected,
        checked = node.isChecked,
        focused = node.isFocused,
    )

    /**
     * The opaque cover over the walk: counted, so a restart's two halves share one; it goes
     * [COVER_AFTER_MS] after the walk, and after [COVER_WATCHDOG_MS] whatever happens.
     */
    private inner class Cover {
        private var count = 0
        private var view: ServerCoverView? = null
        private val watchdog = Runnable { removeNow() }

        val shown: Boolean get() = view != null

        /**
         * Shown with [text] (switching on or off) for a walk in [mode]; a cover already up takes the new walk's text.
         * The log says how long after [sinceMs] ([since]: the walk's start, or the rider's tap) it was drawn.
         */
        fun acquire(text: Int, mode: ServerMenuRun.Mode, sinceMs: Long, since: String) {
            count++
            view?.setText(text) ?: show(text, mode, sinceMs, since)
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, COVER_WATCHDOG_MS)
        }

        /** Where the walk is ([ServerMenuRun.coverStep]). */
        fun step(step: Int) {
            view?.setStep(step)
        }

        /** What the cover says now (it said "turning on"; while it waits for the server it says so). */
        fun text(res: Int) {
            view?.setText(res)
        }

        fun release(afterMs: Long) {
            main.postDelayed({
                if (count > 0) count--
                if (count == 0) removeNow()
            }, afterMs)
        }

        fun removeNow() {
            count = 0
            main.removeCallbacks(watchdog)
            val shown = view ?: return
            view = null
            runCatching { getSystemService(WindowManager::class.java).removeView(shown) }
        }

        private fun show(cover: Int, mode: ServerMenuRun.Mode, sinceMs: Long, since: String) {
            val layout = ServerCoverView(this@AaServerAccessibilityService, mode) {
                log("AaServerStarter: the rider cancelled")
                if (run == null && held) {
                    AaServerStarter.cancelledBeforeTheWalk()
                    dropCover()
                }
                run?.cancel()
                tick()
            }.apply { setText(cover) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Never focusable: "back" must go to Android Auto, not to the cover. Touches are taken (no NOT_TOUCHABLE).
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            ).apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            try {
                getSystemService(WindowManager::class.java).addView(layout, params)
                view = layout
                // How long the cover took to appear, for the ride log.
                layout.doOnPreDraw { log("AaServerStarter: the cover drawn ${SystemClock.elapsedRealtime() - sinceMs} ms after $since") }
            } catch (e: WindowManager.BadTokenException) {
                log("AaServerStarter: the cover could not be shown (${e.message}); the walk goes on without it")
            }
        }
    }
}

/** The cover's three steps, all done. */
private const val ALL_STEPS_DONE = 3
