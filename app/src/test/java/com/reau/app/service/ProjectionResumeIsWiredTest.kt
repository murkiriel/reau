package com.reau.app.service

import com.reau.app.aa.AaLinkState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the partial resume ([ProjectionResume]) is fed from in [ProjectionService]. The service needs Android, so those lines are read from the source, as the
 * other "...IsWired" tests do; what the rule decides is [ProjectionResumeTest]'s, what the mark keeps is [ProjectionResumeBookTest]'s. Here: Ligar writes the
 * mark; every way the projection ends by the rider's act or by itself erases it; a restart with no intent goes through the rule; the service is sticky only
 * while the projection is the rider's and running; a refused foreground ends the resume at once; and nothing in the manifest grew.
 */
class ProjectionResumeIsWiredTest {
    private val main = File("src/main/java/com/reau/app")
    private val service = File(main, "service/ProjectionService.kt").readText()

    /** The source without comments, so that a word in a KDoc is not read as code. */
    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("(?m)(^|\\s)//.*$"), "")

    private val body = code(service)

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    // ── Ligar writes the mark ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Ligar on the screen sets the sticky answer by the switch, and the worker writes the mark before the machine starts`() {
        val start = body.between("ACTION_START, ACTION_START_CARD -> if (goForeground()) {", "ACTION_STOP ->")
        assertTrue(start, "resumable = resumeSwitchOn()" in start)
        assertTrue(start, "resumedAttempt = 0" in start)
        val handle = body.between("private fun handle(", "buildSides(card = sidesFor)")
        val begin = "if (fresh && sidesFor != null && resumable) runCatching { resumeBook.begin(System.currentTimeMillis(), card = sidesFor) }"
        assertTrue(handle, begin in handle)
        val whole = body.between("private fun handle(", "private fun buildSides")
        assertTrue("the mark is written before the machine takes the start", whole.indexOf(begin) < whole.indexOf("controller.submit(event)"))
        assertEquals("one place writes a new mark", 1, Regex("resumeBook\\.begin\\(").findAll(body).count())
    }

    @Test
    fun `the switch is read at each Ligar and a failure reads as off`() {
        assertTrue(body, "private fun resumeSwitchOn(): Boolean = runCatching { SettingsStore.open(this).load().resumeAfterKill }.getOrDefault(false)" in body)
    }

    // ── every way the projection ends erases the mark ────────────────────────────────────────────────────

    @Test
    fun `the release of every stop erases the mark first, unless a Ligar came after that stop`() {
        val release = body.between("override fun release() {", "publish { started.value = null }")
        assertTrue(release, "if (ForegroundHandover.dropsForeground(command, lastStart)) forgetResume()" in release)
        val forget = body.between("private fun forgetResume() {", "private fun logResume")
        assertTrue(forget, "resumable = false" in forget && "resumedAttempt = 0" in forget)
        assertTrue(forget, "runCatching { resumeBook.erase() }" in forget)
        assertEquals("the mark is erased in one place", 1, Regex("resumeBook\\.erase\\(").findAll(body).count())
    }

    @Test
    fun `the rider's Desligar, button or notification, stops being sticky the moment it arrives`() {
        val stop = body.between("ACTION_STOP -> {", "ACTION_MARK ->")
        assertTrue(stop, "resumable = false" in stop)
        assertTrue(stop, stop.indexOf("resumable = false") < stop.indexOf("handle(startId, ProjectionEvent.Stop)"))
        val notification = File(main, "service/ProjectionNotification.kt").readText()
        assertTrue("the notification's button sends the same action", "setAction(ProjectionService.ACTION_STOP)" in notification)
        val activity = File(main, "MainActivity.kt").readText()
        assertEquals("the screen stops through the service's own stop() and nowhere else", 2, Regex("ProjectionService\\.stop\\(this\\)").findAll(activity).count())
        assertEquals("nothing else sends the stop action", 1, Regex("setAction\\(ACTION_STOP\\)").findAll(body).count())
    }

    private val dropped = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = DashLink.DROPPED, dashGoneAtMs = 0L)

    @Test
    fun `the machine ends in the release on the rider's stop, on the stop for the dash's absence and on a start that failed`() {
        val limit = 5 * 60_000L
        val byTheRider = ProjectionMachine.step(Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = DashLink.READY), ProjectionEvent.Stop)
        assertTrue(byTheRider.effects.toString(), ProjectionEffect.Release in byTheRider.effects)
        val whilePreparing = ProjectionMachine.step(Projection(phase = Phase.PREPARING), ProjectionEvent.Stop)
        assertTrue(ProjectionEffect.Release in whilePreparing.effects)
        val byItself = ProjectionMachine.step(dropped, ProjectionEvent.DashRetryDue, nowMs = limit, noDashStopMs = limit)
        assertTrue(byItself.effects.toString(), ProjectionEffect.Release in byItself.effects)
        val refused = ProjectionMachine.step(Projection(phase = Phase.PREPARING), ProjectionEvent.PrepareFailed("no sides"))
        assertTrue(ProjectionEffect.Release in refused.effects)
        val stillWaiting = ProjectionMachine.step(dropped, ProjectionEvent.DashRetryDue, nowMs = limit - 1, noDashStopMs = limit)
        assertFalse("before the limit the projection goes on, and the mark with it", ProjectionEffect.Release in stillWaiting.effects)
    }

    // ── a restart with no intent goes through the rule ───────────────────────────────────────────────────

    @Test
    fun `a restart with no intent asks the rule, and everything but a yes stops the service`() {
        assertTrue(body, "else -> if (intent != null || !resumeAfterRestart(startId)) stopSelf(startId)" in body)
        val restart = body.between("private fun resumeAfterRestart(startId: Int): Boolean {", "private fun resumeNow(")
        assertTrue(restart, "ProjectionResume.decide(resumeBook.read(), enabled, System.currentTimeMillis())" in restart)
        assertTrue(restart, "SettingsStore.open(this).load().resumeAfterKill" in restart)
        assertTrue("a no says why in the log", "is ProjectionResume.Verdict.Drop -> false.also { logResume(ProjectionResume.notResumedLine(verdict.why)) }" in restart)
        assertTrue("a no, and any failure, erases the mark", "if (!resumed) forgetResume()" in restart)
        assertEquals("the one place that resumes", 1, Regex("resumeNow\\(verdict, startId\\)").findAll(body).count())
    }

    @Test
    fun `a resume starts the projection as Ligar would, with the mark it has, and counts itself first`() {
        val now = body.between("private fun resumeNow(", "private fun forgetResume")
        assertTrue(now, now.indexOf("resumeBook.recordResume(System.currentTimeMillis(), verdict.resumedAtMs)") < now.indexOf("goForeground(restart = true)"))
        assertTrue(now, "handle(startId, if (verdict.card) ProjectionEvent.StartCard else ProjectionEvent.Start, sidesFor = verdict.card, fresh = false)" in now)
        assertTrue(now, "lastStart = startId" in now && "resumedAttempt = verdict.attempt" in now)
    }

    @Test
    fun `the resume's line goes to the log after the session has begun, and the notification says it was resumed`() {
        val prepare = body.between("override fun prepare(): String? {", "marks.reset()")
        assertTrue(prepare, prepare.indexOf("RideDiagnostics.start(\"projection\")") < prepare.indexOf("logResume(ProjectionResume.resumedLine(it, ProjectionResume.exemptionNote(batteryExemption())))"))
        assertTrue(body, "?: resumedAttempt.takeIf { it > 0 }?.let { getString(R.string.notif_resumed) }" in body)
        for (folder in listOf("values", "values-pt", "values-es", "values-hi")) {
            assertTrue(folder, "name=\"notif_resumed\"" in File("src/main/res/$folder/strings_extra.xml").readText())
        }
    }

    // ── sticky only while it runs ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the service answers sticky only through the rule, and the flag is set in two places only`() {
        assertEquals(1, Regex("return ProjectionResume\\.returnFor\\(resumable\\)").findAll(body).count())
        assertFalse("no literal answer", "START_STICKY" in body || "START_NOT_STICKY" in body || "START_REDELIVER_INTENT" in body)
        assertEquals("true after a resume's foreground, nowhere else", 1, Regex("(?m)^\\s+resumable = true").findAll(body).count())
        assertEquals("by the Ligar's switch", 1, Regex("resumable = resumeSwitchOn\\(\\)").findAll(body).count())
        assertEquals("false when Desligar arrives and when the mark is forgotten", 2, Regex("(?m)^\\s+resumable = false").findAll(body).count())
        // One answer for every command: the last answer of the service decides whether the system brings it back, so the mark's and the refit's commands, which come
        // while the projection runs, must not answer "not sticky" over it.
        val startCommand = body.between("private fun startCommand(intent: Intent?, startId: Int): Int {", "private fun resumeSwitchOn")
        assertTrue(startCommand, "ACTION_MARK" in startCommand && "ACTION_REFIT" in startCommand && "return ProjectionResume.returnFor(resumable)" in startCommand)
        assertEquals(1, Regex("return ").findAll(startCommand).count())
    }

    // ── a refused foreground never loops ─────────────────────────────────────────────────────────────────

    @Test
    fun `a foreground that Android refuses to a resume ends it, with the reason in the log and the mark erased`() {
        val now = body.between("private fun resumeNow(", "private fun forgetResume")
        val refused = now.between("if (!goForeground(restart = true)) {", "lastStart = startId")
        assertTrue(refused, "logResume(ProjectionResume.notResumedLine(" in refused && "return false" in refused)
        val restart = body.between("private fun resumeAfterRestart(startId: Int): Boolean {", "private fun resumeNow(")
        assertTrue("any failure is caught here, before it can crash the process", "} catch (e: Exception) {" in restart)
        val ladder = body.between("private fun goForeground(restart: Boolean = false): Boolean {", "publish { current.value = Projection(problem = why) }")
        assertTrue(ladder, "for (types in ProjectionResume.typesFor(restart, microphone = needs.microphone, location = needs.location)) {" in ladder)
        assertTrue(ladder, "} catch (e: IllegalStateException) {" in ladder && "} catch (e: SecurityException) {" in ladder)
        assertFalse("the ladder is asked through the rule only", "ForegroundTypes.ladder(" in body)
        assertTrue("the resume's notes do not warn about the Wi-Fi list", "(if (restart) ProjectionResume.foregroundNotes(acceptedTypes) else" in ladder)
    }

    @Test
    fun `every touch of the mark outside the restart's own try is inside a runCatching`() {
        for (call in listOf("resumeBook.begin(", "resumeBook.erase()", "resumeBook.taskRemoved(")) {
            val lines = body.lines().filter { call in it }
            assertEquals(call, 1, lines.size)
            assertTrue("$call is bare: ${lines[0]}", "runCatching { $call" in lines[0])
        }
        val look = body.between("val look = { why: String? ->", ".onFailure")
        assertTrue(look, look.trimStart().startsWith("runCatching {") && "resumeBook.beat(System.currentTimeMillis(), dashUp = ProjectionResume.dashUp(controller.state.dash))" in look)
        val restart = body.between("private fun resumeAfterRestart(startId: Int): Boolean {", "private fun forgetResume")
        assertTrue("read and the count are inside the try", restart.indexOf("val resumed = try {") < restart.indexOf("resumeBook.read()") &&
            restart.indexOf("resumeBook.read()") < restart.indexOf("} catch (e: Exception) {"))
    }

    @Test
    fun `no line of the resume's path reaches the log outside a runCatching`() {
        val path = body.between("private fun resumeSwitchOn", "override fun onTaskRemoved")
        assertEquals("the resume writes the log through logResume only", 1, Regex("RideDiagnostics\\.log\\(").findAll(path).count())
        assertTrue(path, "runCatching { RideDiagnostics.log(\"projection\", line) }" in path)
        assertFalse(path, Regex("(?<![A-Za-z])log\\(").containsMatchIn(path.replace("logResume(", "").replace("RideDiagnostics.log(", "")))
    }

    @Test
    fun `the task being removed is told to the mark and nothing else changes`() {
        val removed = body.between("override fun onTaskRemoved(rootIntent: Intent?) {", "override fun onDestroy()")
        assertTrue(removed, "runCatching { resumeBook.taskRemoved(System.currentTimeMillis()) }" in removed)
        assertTrue(removed, "super.onTaskRemoved(rootIntent)" in removed)
        assertFalse("it does not stop anything", "stopSelf" in removed || "stopForeground" in removed || "controller" in removed)
    }

    // ── the resume reaches the quick restart of Android Auto's server, and the rest of what the 25/11/2026 review asked for ──────────────

    @Test
    fun `a resume builds its sides as Ligar does, in a process whose first projection takes the quick restart of Android Auto's server`() {
        val now = body.between("private fun resumeNow(", "private fun forgetResume")
        assertTrue(now, "handle(startId, if (verdict.card) ProjectionEvent.StartCard else ProjectionEvent.Start, sidesFor = verdict.card, fresh = false)" in now)
        val handle = body.between("private fun handle(", "private fun buildSides")
        assertTrue(handle, "if (sidesFor != null && buildsSides(sidesFor, controller.state.phase)) buildSides(card = sidesFor)" in handle)
        val live = code(File(main, "service/LiveSides.kt").readText())
        val quick = live.between("quickUnresponsive = ", "),\n")
        assertTrue(quick, quick.trimStart().startsWith("com.reau.app.aa.FirstOfProcess.projection.take() &&"))
        assertTrue(quick, "com.reau.app.data.ExitReasons.leftAndroidAutoHanging(" in quick && "com.reau.app.data.ExitHistory.newest(context)" in quick)
    }

    @Test
    fun `the beat tells the mark whether the dash is connected, and a resume that is refused says the battery optimization too`() {
        assertEquals("the one call of the beat", 1, Regex("resumeBook\\.beat\\(").findAll(body).count())
        val refused = body.between("private fun resumeNow(", "private fun forgetResume").between("if (!goForeground(restart = true)) {", "return false")
        assertTrue(refused, "ProjectionResume.exemptionNote(batteryExemption())" in refused)
        val exemption = body.between("private fun batteryExemption(): Boolean? =", "private fun forgetResume")
        assertTrue(exemption, "isIgnoringBatteryOptimizations(packageName)" in exemption && "runCatching" in exemption)
    }

    @Test
    fun `the release ends the service with the id of the latest command of any kind, written for every command that arrives`() {
        assertTrue("latestCommand = ForegroundHandover.latestForStop(latestCommand, startId, takesForeground = intent == null || action in START_ACTIONS)" in
            body.between("val action = intent?.action", "noteTapDuringStop(action)"))
        val release = body.between("override fun release() {", "override fun startAndroidAuto()")
        assertTrue(release, "stopSelf(ForegroundHandover.stopId(command, lastStart, latestCommand))" in release)
        assertFalse("no bare stopSelf(command) left", "stopSelf(command)" in release)
        assertEquals("written in one place", 1, Regex("latestCommand = ForegroundHandover.latestForStop").findAll(body).count())
    }

    @Test
    fun `the rider opening the app again takes the removal from the recent apps away, off the main thread`() {
        val activity = code(File(main, "MainActivity.kt").readText())
        val reopen = activity.between("private fun forgetRemovalFromRecents() {", "private fun learnDashName")
        assertTrue(reopen, "Dispatchers.IO" in reopen && "ProjectionResumeBook.open(context).reopened()" in reopen && "runCatching" in reopen)
        assertTrue("called whenever the app comes to the front", "forgetRemovalFromRecents()" in activity.between("private fun resume() {", "private fun forgetRemovalFromRecents"))
    }

    // ── what did not grow ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the manifest has no new permission, no exact alarm, no boot receiver, and the service keeps its types and its task behavior`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (never in listOf("ACCESS_BACKGROUND_LOCATION", "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "RECEIVE_BOOT_COMPLETED", "CompanionDevice", "REQUEST_COMPANION", "stopWithTask")) {
            assertFalse("$never in the manifest", never in manifest)
        }
        assertTrue(manifest, "android:foregroundServiceType=\"connectedDevice|microphone|location\"" in manifest)
        val rule = File(main, "service/ProjectionResume.kt").readText()
        assertFalse("the rule has no Android Context and no clock of its own", "android.content" in rule || "currentTimeMillis" in code(rule) || "SystemClock" in rule)
        val book = File(main, "service/ProjectionResumeBook.kt").readText()
        assertFalse("the mark makes no thread", Regex("Thread|Executor|thread\\(|Handler").containsMatchIn(book))
    }
}
