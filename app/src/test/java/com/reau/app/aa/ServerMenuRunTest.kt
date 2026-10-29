package com.reau.app.aa

import com.reau.app.aa.ServerMenuRun.Outcome
import com.reau.app.aa.ServerMenuRun.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One walk through Android Auto's menu, against a pretend screen.
 */
class ServerMenuRunTest {
    private val aa = AndroidAuto.PACKAGE
    private val texts = ServerTexts.table()

    private val full = listOf("Ajuda e feedback", "Configurações do desenvolvedor", "Iniciar servidor da unidade principal", "Sair do modo de desenvolvedor")
    private val running = listOf("Ajuda e feedback", "Configurações do desenvolvedor", "Parar servidor da unid. princ.", "Sair do modo de desenvolvedor")
    private val half = listOf("Ajuda e feedback", "Enviar feedback")

    /** The walks of the older tests go back at once after "start": the hold after the touch has its own tests below. */
    private val noHold = ServerMenuRun.Timing(holdMs = 0, halfMenuMs = 300)

    /** Android Auto's settings with the ⋮ button (window 1) and, when [menu] is not null, the menu's list (window 2). */
    private fun screen(
        now: Long,
        menu: List<String>? = null,
        front: String? = aa,
        locked: Boolean = false,
        overflow: Boolean = true,
        changed: Long = 0,
        portUp: Boolean = false,
    ) =
        ServerMenuRun.Seen(
            nowMs = now,
            front = front,
            locked = locked,
            nodes = buildList {
                add(ServerMenuRun.Node(1, "Android Auto", null, clickable = false, ref = 0))
                if (overflow) add(ServerMenuRun.Node(1, null, "Mais opções", clickable = true, ref = 1))
                menu?.forEachIndexed { i, text -> add(ServerMenuRun.Node(2, text, null, clickable = true, ref = 10 + i)) }
            },
            lastChangeMs = changed,
            portUp = portUp,
        )

    private fun elsewhere(now: Long, front: String = ServerMenuRun.REAU) = ServerMenuRun.Seen(now, front, false, emptyList())

    /** Opens the settings and the menu: the run is left waiting for the list at [at]. */
    private fun toTheMenu(run: ServerMenuRun, at: Long = 0): Long {
        assertEquals(Step.OpenSettings, run.next(elsewhere(at)))
        val click = run.next(screen(at + 500))
        assertTrue("$click", click is Step.Click && click.what == "more options")
        return at + 500
    }

    /** Shows [menu] until the run answers something other than waiting; that answer and the time. */
    private fun showMenu(run: ServerMenuRun, menu: List<String>, from: Long, portUp: Boolean = false): Pair<Step, Long> {
        var now = from
        while (true) {
            now += 100
            val step = run.next(screen(now, menu, portUp = portUp))
            if (step != Step.Wait) return step to now
        }
    }

    @Test
    fun `the walk opens the settings, the menu, touches start and goes back`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val t = toTheMenu(run)
        val (tap, at) = showMenu(run, full, t)
        assertEquals("Iniciar servidor da unidade principal", (tap as Step.Click).node.text)
        assertEquals("start", tap.what)
        // Back while Android Auto is in front, then REau is: done.
        assertEquals(Step.Back, run.next(screen(at + 700)))
        assertEquals(Step.Done(Outcome.TAPPED_START), run.next(elsewhere(at + 900)))
        assertEquals(Step.Done(Outcome.TAPPED_START), run.next(elsewhere(at + 1_000)))
        assertEquals(full, run.lastMenu)
    }

    @Test
    fun `the cover follows the walk, step by step`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        assertEquals("before anything", 0, run.coverStep)
        assertEquals(Step.OpenSettings, run.next(elsewhere(0)))
        assertEquals("opening Android Auto", 0, run.coverStep)
        assertEquals(Step.Wait, run.next(screen(300, overflow = false)))
        assertEquals("in Android Auto, before its menu", 1, run.coverStep)
        val t = 600L.also { assertTrue(run.next(screen(it)) is Step.Click) }
        assertEquals("the menu's list", 2, run.coverStep)
        val (tap, at) = showMenu(run, full, t)
        assertTrue(tap is Step.Click)
        assertEquals("start touched: the server is still coming up", 2, run.coverStep)
        run.next(screen(at + 700, portUp = true))
        assertEquals("the port answered and the hold is over: every step done", 3, run.coverStep)
        run.next(elsewhere(at + 900))
        assertEquals(3, run.coverStep)
    }

    @Test
    fun `the item touched is the one whose text is start, wherever it sits`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val shuffled = listOf("Sair do modo de desenvolvedor", "Iniciar servidor da unidade principal", "Configurações do desenvolvedor")
        val (tap, _) = showMenu(run, shuffled, toTheMenu(run))
        assertEquals(11, (tap as Step.Click).node.ref)
    }

    @Test
    fun `the list must hold still before it is read`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val t = toTheMenu(run)
        assertEquals(Step.Wait, run.next(screen(t + 100, half)))
        assertEquals(Step.Wait, run.next(screen(t + 300, full)))
        // 300 ms after the last change.
        assertEquals(Step.Wait, run.next(screen(t + 550, full)))
        assertTrue(run.next(screen(t + 600, full)) is Step.Click)
    }

    @Test
    fun `the overflow waits for the settings to stop changing`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        run.next(elsewhere(0))
        assertEquals(Step.Wait, run.next(screen(500, changed = 300)))
        assertEquals(Step.Wait, run.next(screen(650, changed = 300)))
        assertTrue(run.next(screen(700, changed = 300)) is Step.Click)
    }

    @Test
    fun `a changed menu touches nothing`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val (step, at) = showMenu(run, listOf("Ajuda e feedback", "Configurações do desenvolvedor", "Sair do modo de desenvolvedor"), toTheMenu(run))
        // The list closes with back, never with a touch on an item.
        assertEquals(Step.Back, step)
        assertEquals(Step.Done(Outcome.UNKNOWN_MENU), run.next(elsewhere(at + 100)))
    }

    @Test
    fun `the way back waits for the list to close before back`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val (_, at) = showMenu(run, full, toTheMenu(run))
        // The touched item's list is still drawn: a back now would be swallowed by its closing.
        assertEquals(Step.Wait, run.next(screen(at + 100, full)))
        assertEquals(Step.Wait, run.next(screen(at + 500, full)))
        assertEquals(Step.Back, run.next(screen(at + 600, full)))
    }

    @Test
    fun `the half menu is read again once, and the full one is then touched`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val (back, at) = showMenu(run, half, toTheMenu(run))
        assertEquals(Step.Back, back)
        // The list closes; after the pause the ⋮ is touched again.
        assertEquals(Step.Wait, run.next(screen(at + 200)))
        val again = run.next(screen(at + 600))
        assertTrue(again is Step.Click && again.what == "more options")
        val (tap, _) = showMenu(run, full, at + 600)
        assertEquals("start", (tap as Step.Click).what)
    }

    @Test
    fun `the help items twice mean developer mode off`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val (back, at) = showMenu(run, half, toTheMenu(run))
        assertEquals(Step.Back, back)
        assertTrue(run.next(screen(at + 600)) is Step.Click)
        // The list is left 600 ms to close, then back.
        val (leave, at2) = showMenu(run, half, at + 600)
        assertEquals(Step.Back, leave)
        assertEquals(Step.Done(Outcome.DEVELOPER_MODE_OFF), run.next(elsewhere(at2 + 100)))
    }

    @Test
    fun `a server marked running is restarted once, stop then a pause then start`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val (stop, at) = showMenu(run, running, toTheMenu(run))
        assertEquals("stop (restart)", (stop as Step.Click).what)
        assertEquals("Parar servidor da unid. princ.", stop.node.text)
        assertEquals(Step.Wait, run.next(screen(at + 1_000)))
        val again = run.next(screen(at + ServerMenuRun.RESTART_PAUSE_MS))
        assertTrue(again is Step.Click && again.what == "more options")
        val (start, _) = showMenu(run, full, at + ServerMenuRun.RESTART_PAUSE_MS)
        assertEquals("start", (start as Step.Click).what)
        assertTrue(run.restarted)
    }

    @Test
    fun `a server marked running whose port answers is not restarted, the walk leaves with it up`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val (step, at) = showMenu(run, running, toTheMenu(run), portUp = true)
        assertFalse("nothing is touched: $step", step is Step.Click)
        assertFalse(run.restarted)
        assertEquals(Step.Done(Outcome.ALREADY_UP), run.next(elsewhere(at + 300)))
        assertTrue(run.succeeded)
    }

    @Test
    fun `the port answering does not change what the stop walk does`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.STOP, texts, 0)
        val (tap, _) = showMenu(run, running, toTheMenu(run), portUp = true)
        assertEquals("stop", (tap as Step.Click).what)
    }

    @Test
    fun `a second stop in the same walk is not touched`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val (_, at) = showMenu(run, running, toTheMenu(run))
        run.next(screen(at + ServerMenuRun.RESTART_PAUSE_MS))
        val (step, at2) = showMenu(run, running, at + ServerMenuRun.RESTART_PAUSE_MS)
        assertFalse(step is Step.Click)
        assertEquals(Step.Done(Outcome.ALREADY_RUNNING), run.next(elsewhere(at2 + 100)))
    }

    @Test
    fun `without the restart, stop shown is already running`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, restartMarkedRunning = false, timing = noHold)
        val (step, at) = showMenu(run, running, toTheMenu(run))
        assertFalse(step is Step.Click)
        assertEquals(Step.Done(Outcome.ALREADY_RUNNING), run.next(elsewhere(at + 100)))
    }

    @Test
    fun `switching off touches stop`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.STOP, texts, 0)
        val (tap, at) = showMenu(run, running, toTheMenu(run))
        assertEquals("stop", (tap as Step.Click).what)
        assertEquals(Step.Done(Outcome.TAPPED_STOP), run.next(elsewhere(at + 100)))
    }

    @Test
    fun `switching off a stopped server touches nothing`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.STOP, texts, 0)
        val (step, at) = showMenu(run, full, toTheMenu(run))
        assertFalse(step is Step.Click)
        assertEquals(Step.Done(Outcome.ALREADY_STOPPED), run.next(elsewhere(at + 100)))
    }

    @Test
    fun `the phone locked stops the walk at once, with no other touch`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val t = toTheMenu(run)
        assertEquals(Step.Done(Outcome.LOCKED), run.next(screen(t + 100, full, locked = true)))
        assertEquals(Step.Done(Outcome.LOCKED), run.next(screen(t + 200, full)))
    }

    @Test
    fun `another app in front stops the walk at once`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val t = toTheMenu(run)
        assertEquals(Step.Done(Outcome.OTHER_APP), run.next(screen(t + 100, front = "com.whatsapp")))
    }

    @Test
    fun `another app in front before Android Auto opened is waited out`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        run.next(elsewhere(0))
        assertEquals(Step.Wait, run.next(elsewhere(300, front = "com.whatsapp")))
    }

    @Test
    fun `settings that never come are given up after 4 s, with no back`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        run.next(elsewhere(0))
        assertEquals(Step.Wait, run.next(elsewhere(3_900)))
        assertEquals(Step.Done(Outcome.SETTINGS_DID_NOT_OPEN), run.next(elsewhere(4_000)))
    }

    @Test
    fun `settings that could not be opened end the walk`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        run.next(elsewhere(0))
        run.openFailed()
        assertEquals(Step.Done(Outcome.SETTINGS_DID_NOT_OPEN), run.next(elsewhere(100)))
    }

    @Test
    fun `no overflow in 3 s is an unexpected screen, left with back`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        run.next(elsewhere(0))
        assertEquals(Step.Wait, run.next(screen(500, overflow = false)))
        assertEquals(Step.Back, run.next(screen(3_500, overflow = false)))
        assertEquals(Step.Done(Outcome.UNEXPECTED_SCREEN), run.next(elsewhere(3_600)))
    }

    @Test
    fun `a list that never shows gets one more touch, then the walk leaves`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val t = toTheMenu(run)
        assertEquals(Step.Wait, run.next(screen(t + 1_400)))
        val again = run.next(screen(t + 1_500))
        assertTrue(again is Step.Click && again.what == "more options")
        assertEquals(Step.Back, run.next(screen(t + 3_000)))
        assertEquals(Step.Done(Outcome.MENU_DID_NOT_OPEN), run.next(elsewhere(t + 3_100)))
    }

    @Test
    fun `an unreadable list is read once more`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val (back, at) = showMenu(run, listOf("Bluetooth"), toTheMenu(run))
        assertEquals(Step.Back, back)
        run.next(screen(at + 600))
        val (leave, at2) = showMenu(run, listOf("Bluetooth"), at + 600)
        assertFalse(leave is Step.Click)
        assertEquals(Step.Done(Outcome.MENU_DID_NOT_OPEN), run.next(elsewhere(at2 + 100)))
    }

    @Test
    fun `cancel ends the walk with no other step`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val t = toTheMenu(run)
        run.cancel()
        assertEquals(Step.Done(Outcome.CANCELLED), run.next(screen(t + 100, full)))
    }

    @Test
    fun `cancel on the way back keeps the touch made`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val (_, at) = showMenu(run, full, toTheMenu(run))
        run.cancel()
        assertEquals(Step.Done(Outcome.TAPPED_START), run.next(screen(at + 700)))
    }

    @Test
    fun `a refused click is not tried again`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val (_, at) = showMenu(run, full, toTheMenu(run))
        run.clickRefused(at)
        val steps = (1..10).map { run.next(screen(at + it * 300L, if (it < 2) full else null)) }
        assertTrue(steps.none { it is Step.Click })
        assertEquals(Step.Done(Outcome.CLICK_REFUSED), run.next(elsewhere(at + 4_000)))
    }

    @Test
    fun `the walk gives up after 10 s`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val t = toTheMenu(run)
        // A list that keeps changing is never read.
        var now = t
        while (now < 9_900) {
            now += 100
            assertFalse(run.next(screen(now, listOf("Bluetooth $now"))) is Step.Click)
        }
        run.next(screen(10_000, listOf("x")))
        assertEquals(Step.Done(Outcome.TIMED_OUT), run.next(elsewhere(10_200)))
    }

    @Test
    fun `Android Auto that does not go with back gives way to REau after three`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val (_, at) = showMenu(run, full, toTheMenu(run))
        assertEquals(Step.Back, run.next(screen(at + 600)))
        assertEquals(Step.Wait, run.next(screen(at + 900)))
        assertEquals(Step.Back, run.next(screen(at + 1_100)))
        assertEquals(Step.Back, run.next(screen(at + 1_600)))
        assertEquals(Step.OpenREau, run.next(screen(at + 2_100)))
        assertEquals(Step.Done(Outcome.TAPPED_START), run.next(screen(at + 2_200)))
    }

    /** Audit of 20/10/2026: the cover's watchdog was a fixed 16 s, shorter than the 16.5 s a restart's walk and cover tail can take. */
    @Test
    fun `the cover's watchdog outlasts the longest walk and the cover's tail`() {
        val tail = 1_000L
        assertEquals("a walk, a restart's extra time, its pause, the hold, the half-menu wait and the way back", 10_000L + 4_000L + 1_500L + 8_000L + 5_000L + 2_500L, ServerMenuRun.longestWalkMs())
        val watchdog = ServerMenuRun.coverWatchdogMs(tail)
        assertTrue("longer than the 16.5 s the old 16 s fell short of: $watchdog", watchdog > 16_500L)
        assertTrue(watchdog > ServerMenuRun.longestWalkMs() + tail)
        // It follows the timing: a walk given more time gets a watchdog that waits for it.
        val slow = ServerMenuRun.Timing(totalMs = 20_000)
        assertEquals(watchdog + 10_000L, ServerMenuRun.coverWatchdogMs(tail, slow))
    }

    // ── the hold after "start" ───────────────────────────────────────────────────────────────────────

    /** A walk with the real timing, taken to the touch on "start"; the run and the time of the touch. */
    private fun touchedStart(mode: ServerMenuRun.Mode = ServerMenuRun.Mode.START): Pair<ServerMenuRun, Long> {
        val run = ServerMenuRun(mode, texts, 0)
        val (tap, at) = showMenu(run, if (mode == ServerMenuRun.Mode.START) full else running, toTheMenu(run))
        assertTrue("$tap", tap is Step.Click)
        return run to at
    }

    @Test
    fun `after the touch on start it does not go back while the port is closed, for three seconds`() {
        val (run, at) = touchedStart()
        assertTrue(run.holding)
        for (later in listOf(110L, 500, 1_000, 2_000, 3_000)) assertEquals("at +$later ms", Step.Wait, run.next(screen(at + later)))
        assertTrue("still holding", run.holding)
    }

    @Test
    fun `the port answering at 2 s lets it go back 600 ms later, not before`() {
        val (run, at) = touchedStart()
        assertEquals(Step.Wait, run.next(screen(at + 2_000)))
        assertEquals(Step.Wait, run.next(screen(at + 2_200, portUp = true)))
        assertEquals(Step.Wait, run.next(screen(at + 2_700, portUp = true)))
        assertEquals(Step.Back, run.next(screen(at + 2_800, portUp = true)))
        assertEquals(Step.Done(Outcome.TAPPED_START), run.next(elsewhere(at + 3_000)))
    }

    @Test
    fun `a port that never answers is left after the hold, with start as the result`() {
        val (run, at) = touchedStart()
        assertEquals(Step.Wait, run.next(screen(at + 7_900)))
        assertEquals(Step.Back, run.next(screen(at + 8_000)))
        assertEquals(Step.Done(Outcome.TAPPED_START), run.next(elsewhere(at + 8_200)))
    }

    @Test
    fun `the phone locked, another app, or Cancelar while it holds end the walk at once and keep start as the result`() {
        val locked = touchedStart().let { (run, at) -> run.next(screen(at + 1_000, locked = true)) }
        assertEquals(Step.Done(Outcome.TAPPED_START), locked)
        val other = touchedStart().let { (run, at) -> run.next(elsewhere(at + 1_000, front = "com.other.app")) }
        assertEquals(Step.Done(Outcome.TAPPED_START), other)
        val cancelled = touchedStart().let { (run, at) -> run.cancel(); run.next(screen(at + 1_000)) }
        assertEquals(Step.Done(Outcome.TAPPED_START), cancelled)
    }

    @Test
    fun `the walk's own deadline does not change the result of a touch already made`() {
        val (run, at) = touchedStart()
        // Past the 10 s of the walk: still holding for the hold's own 8 s, and then start is the result, not TIMED_OUT.
        val late = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = ServerMenuRun.Timing(totalMs = 3_000))
        val (tap, tapAt) = showMenu(late, full, toTheMenu(late))
        assertTrue(tap is Step.Click)
        assertEquals(Step.Wait, late.next(screen(tapAt + 4_000)))
        assertEquals(Step.Back, late.next(screen(tapAt + 8_000)))
        assertEquals(Step.Done(Outcome.TAPPED_START), late.next(elsewhere(tapAt + 8_500)))
        assertTrue(run.holding && at > 0)
    }

    @Test
    fun `stop in the stop mode, and the stop of a restart, do not hold`() {
        val (stopRun, at) = touchedStart(ServerMenuRun.Mode.STOP)
        assertFalse(stopRun.holding)
        assertEquals(Step.Back, stopRun.next(screen(at + 700)))

        val restart = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val (click, t) = showMenu(restart, running, toTheMenu(restart))
        assertEquals("stop (restart)", (click as Step.Click).what)
        assertFalse("the stop of a restart is not a start", restart.holding)
        assertEquals(Step.Wait, restart.next(screen(t + 100)))
    }

    @Test
    fun `a restart holds only after its second touch, the one on start`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val (stop, at) = showMenu(run, running, toTheMenu(run))
        assertEquals("stop (restart)", (stop as Step.Click).what)
        assertFalse("the stop is not held", run.holding)
        assertEquals(Step.Wait, run.next(screen(at + 1_000)))
        val again = run.next(screen(at + ServerMenuRun.RESTART_PAUSE_MS))
        assertTrue(again is Step.Click && again.what == "more options")
        assertFalse(run.holding)
        val (start, at2) = showMenu(run, full, at + ServerMenuRun.RESTART_PAUSE_MS)
        assertEquals("start", (start as Step.Click).what)
        assertTrue("the touch on start is", run.holding)
        assertEquals(Step.Wait, run.next(screen(at2 + 2_000)))
    }

    @Test
    fun `the longest walk and the cover's watchdog grow by the hold`() {
        val hold = ServerMenuRun.Timing().holdMs
        assertEquals(8_000L, hold)
        assertEquals(10_000L + 4_000L + 1_500L + hold + 5_000L + 2_500L, ServerMenuRun.longestWalkMs())
        assertEquals(ServerMenuRun.longestWalkMs() + 1_000L + 1_000L, ServerMenuRun.coverWatchdogMs(1_000L))
        assertEquals(ServerMenuRun.longestWalkMs(ServerMenuRun.Timing(holdMs = 0)) + 8_000L, ServerMenuRun.longestWalkMs())
    }

    // ── the cover does not say "done" before it is true ───────────────────────────────────────────────

    @Test
    fun `every step is done only when the port answered after start`() {
        val (run, at) = touchedStart()
        assertEquals("waiting for the server", 2, run.coverStep)
        assertFalse(run.succeeded)
        run.next(screen(at + 2_000, portUp = true))
        run.next(screen(at + 2_700, portUp = true))
        assertEquals("the port answered: done", 3, run.coverStep)
        assertTrue(run.succeeded)
    }

    @Test
    fun `a start that the port never answered leaves the cover at the step that did not finish`() {
        val (run, at) = touchedStart()
        run.next(screen(at + 8_000))
        assertEquals(Step.Done(Outcome.TAPPED_START), run.next(elsewhere(at + 8_200)))
        assertEquals("not every step: the server did not answer", 2, run.coverStep)
        assertFalse(run.succeeded)
    }

    @Test
    fun `a menu without the developer items does not end with every step green`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val (back, at) = showMenu(run, half, toTheMenu(run))
        assertEquals(Step.Back, back)
        assertTrue(run.next(screen(at + 600)) is Step.Click)
        val (leave, at2) = showMenu(run, half, at + 600)
        assertEquals(Step.Back, leave)
        assertEquals(Step.Done(Outcome.DEVELOPER_MODE_OFF), run.next(elsewhere(at2 + 100)))
        assertEquals("the list was reached, and it is where it failed", 2, run.coverStep)
        assertFalse(run.succeeded)
    }

    @Test
    fun `a walk that fails before the menu stays at the opening step`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        assertEquals(Step.OpenSettings, run.next(elsewhere(0)))
        // Android Auto came to the front but never showed the ⋮: the walk gives up after its wait.
        var last: Step = Step.Wait
        var now = 0L
        while (last == Step.Wait && now < 10_000) {
            now += 500
            last = run.next(screen(now, overflow = false))
        }
        val done = if (last is Step.Done) last else run.next(elsewhere(now + 100))
        assertEquals(Step.Done(Outcome.UNEXPECTED_SCREEN), done)
        assertEquals("opening, then the menu not found: it did not get past step 1", 1, run.coverStep)
        assertFalse(run.succeeded)
    }

    @Test
    fun `stop touched or already stopped is a done walk, and the server already running is not`() {
        val (stopRun, at) = touchedStart(ServerMenuRun.Mode.STOP)
        assertTrue(stopRun.succeeded)
        assertEquals(3, stopRun.coverStep)
        assertEquals(Step.Back, stopRun.next(screen(at + 700)))

        val already = ServerMenuRun(ServerMenuRun.Mode.STOP, texts, 0)
        val (leave, t) = showMenu(already, full, toTheMenu(already))
        assertTrue("$leave", leave == Step.Back || leave is Step.Done)
        assertEquals(Outcome.ALREADY_STOPPED, (already.next(elsewhere(t + 1_000)) as Step.Done).outcome)
        assertTrue(already.succeeded)
        assertEquals(3, already.coverStep)

        val noRestart = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, restartMarkedRunning = false)
        val (back, at2) = showMenu(noRestart, running, toTheMenu(noRestart))
        assertTrue("$back", back == Step.Back || back is Step.Done)
        assertEquals(Outcome.ALREADY_RUNNING, (noRestart.next(elsewhere(at2 + 1_000)) as Step.Done).outcome)
        assertFalse(noRestart.succeeded)
        assertEquals(2, noRestart.coverStep)
    }

    // ── a half-built menu is read again before developer mode is taken as off ────────────────────────

    /** A real-timing walk to the first read of the half menu: it answers `Back` (read again); the run and the time. */
    private fun firstHalfRead(): Pair<ServerMenuRun, Long> {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = ServerMenuRun.Timing(holdMs = 0))
        val (step, at) = showMenu(run, half, toTheMenu(run))
        assertEquals("the half menu is read again, not given up", Step.Back, step)
        return run to at
    }

    /** Reads the half menu again after each pause, until [seconds] have gone by since the first read, or the walk answers. */
    private fun keepReadingHalf(run: ServerMenuRun, firstAt: Long, seconds: Long): Step {
        var now = firstAt
        var last: Step = Step.Back
        while (now - firstAt < seconds * 1_000 && last !is Step.Done) {
            now += 600
            val step = run.next(screen(now))
            last = step
            if (step is Step.Click) {
                val (again, at) = showMenu(run, half, now)
                last = again
                now = at
                if (again is Step.Back) continue
            }
        }
        return last
    }

    @Test
    fun `a half menu for one second and then the whole one gets the start touched`() {
        val (run, at) = firstHalfRead()
        // After the pause the ⋮ again, and this time Android Auto has built the whole menu.
        assertTrue(run.next(screen(at + 600)) is Step.Click)
        val (start, _) = showMenu(run, full, at + 600)
        assertEquals("start", (start as Step.Click).what)
    }

    @Test
    fun `a half menu for six seconds is taken as developer mode off, not before five`() {
        val (run, at) = firstHalfRead()
        val before = keepReadingHalf(run, at, seconds = 4)
        assertFalse("not given up before 5 s: $before", before is Step.Done && (before.outcome == Outcome.DEVELOPER_MODE_OFF))
        val after = keepReadingHalf(run, at, seconds = 7)
        val done = if (after is Step.Done) after else run.next(elsewhere(at + 20_000))
        assertEquals(Step.Done(Outcome.DEVELOPER_MODE_OFF), done)
        assertFalse(run.succeeded)
    }

    @Test
    fun `an unknown developer menu still leaves at once`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = ServerMenuRun.Timing(holdMs = 0))
        val odd = listOf("Ajuda e feedback", "Configurações do desenvolvedor", "Item que ninguém conhece")
        val (step, at) = showMenu(run, odd, toTheMenu(run))
        assertTrue("$step", step == Step.Back || step is Step.Done)
        assertEquals(Step.Done(Outcome.UNKNOWN_MENU), if (step is Step.Done) step else run.next(elsewhere(at + 100)))
    }

    @Test
    fun `the wait over a half menu does not use up the walk's own time`() {
        // The walk's time (10 s) is extended by the half-menu wait when it begins, so a read at 8 s is not a timeout.
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = ServerMenuRun.Timing(holdMs = 0, halfMenuMs = 5_000))
        val (step, at) = showMenu(run, half, toTheMenu(run))
        assertEquals(Step.Back, step)
        assertTrue(run.next(screen(at + 600)) is Step.Click)
        val (again, at2) = showMenu(run, half, at + 600)
        assertEquals("still reading, at ${at2}ms", Step.Back, again)
        assertTrue("well inside the extended time", at2 < 10_000 + 5_000)
    }

    /**
     * The hold after "start", a half-built menu read again and the way back all add to the
     * walk, and the cover must outlast the worst of them (the cover is not taken down in the middle of one). A second automatic
     * walk is a walk of its own with its own cover and watchdog (`acquire` arms it again).
     */
    @Test
    fun `the cover's watchdog outlasts the worst walk made of every wait together`() {
        val t = ServerMenuRun.Timing()
        // The walk's deadline (with the restart's and the half menu's extensions), a touch on start right at it, the hold, the way back.
        val worst = t.totalMs + ServerMenuRun.RESTART_EXTRA_MS + t.halfMenuMs + t.holdMs + t.leaveMs
        val tail = 1_000L
        assertTrue("${ServerMenuRun.coverWatchdogMs(tail)} must outlast $worst and the cover's tail", ServerMenuRun.coverWatchdogMs(tail) > worst + tail)
        // Every wait is in the sum: take any one away and the longest walk is shorter by exactly it.
        for ((name, less) in listOf("hold" to t.copy(holdMs = 0), "half menu" to t.copy(halfMenuMs = 0), "way back" to t.copy(leaveMs = 0))) {
            assertTrue(name, ServerMenuRun.longestWalkMs(less) < ServerMenuRun.longestWalkMs(t))
        }
    }

    // ── a visit: the settings in front, nothing touched ─────────────────────────────────────────────

    private fun visit() = ServerMenuRun(ServerMenuRun.Mode.VISIT, texts, 0)

    @Test
    fun `a visit opens the settings, touches nothing and goes back when the port answers`() {
        val run = visit()
        assertEquals(Step.OpenSettings, run.next(elsewhere(0)))
        assertEquals("Android Auto is in front: the visit begins", Step.Wait, run.next(screen(500)))
        assertTrue("it holds and the receiver dials", run.holding)
        assertEquals(Step.Wait, run.next(screen(1_500)))
        assertEquals(Step.Wait, run.next(screen(2_000, portUp = true)))
        assertEquals("600 ms after the port answered", Step.Back, run.next(screen(2_600, portUp = true)))
        assertEquals(Step.Done(Outcome.VISITED), run.next(elsewhere(2_800)))
        assertTrue("the port answered", run.succeeded)
    }

    @Test
    fun `a visit whose port never answers is left after four seconds, not eight`() {
        val run = visit()
        run.next(elsewhere(0))
        assertEquals(Step.Wait, run.next(screen(500)))
        assertEquals(Step.Wait, run.next(screen(4_400)))
        assertEquals(Step.Back, run.next(screen(4_500)))
        assertEquals(Step.Done(Outcome.VISITED), run.next(elsewhere(4_700)))
        assertEquals("the port did not answer", false, run.succeeded)
    }

    @Test
    fun `a visit never touches the menu or the overflow button`() {
        val run = visit()
        val steps = mutableListOf<Step>()
        steps += run.next(elsewhere(0))
        for (at in 500L..6_000L step 250) steps += run.next(screen(at, menu = full))
        assertTrue(steps.toString(), steps.none { it is Step.Click })
    }

    @Test
    fun `a visit is cut short by the phone locking, another app or Cancelar, with no touch and visited as the result`() {
        val locked = visit().also { it.next(elsewhere(0)); it.next(screen(500)) }.next(screen(1_000, locked = true))
        assertEquals(Step.Done(Outcome.VISITED), locked)
        val other = visit().also { it.next(elsewhere(0)); it.next(screen(500)) }.next(elsewhere(1_000, front = "com.other.app"))
        assertEquals(Step.Done(Outcome.VISITED), other)
        val cancelled = visit().also { it.next(elsewhere(0)); it.next(screen(500)); it.cancel() }.next(screen(1_000))
        assertEquals(Step.Done(Outcome.VISITED), cancelled)
    }

    // ── Android Auto gone while the walk reads its screen ───────────────────────────────────────────────

    @Test
    fun `a walk told that Android Auto is gone ends at once with no touch, in any phase`() {
        val opening = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        assertEquals(Step.OpenSettings, opening.next(elsewhere(0)))
        opening.gone()
        assertEquals(Step.Done(Outcome.AA_GONE), opening.next(screen(500)))

        val inTheMenu = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        toTheMenu(inTheMenu)
        inTheMenu.gone()
        assertEquals(Step.Done(Outcome.AA_GONE), inTheMenu.next(screen(900, menu = full)))
        assertEquals("and stays so", Step.Done(Outcome.AA_GONE), inTheMenu.next(screen(1_500, menu = full)))
    }

    @Test
    fun `a walk that already ended keeps its result when Android Auto is said gone later`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        val (back, at) = showMenu(run, half, toTheMenu(run))
        assertEquals(Step.Back, back)
        run.cancel()
        assertTrue(run.next(screen(at + 100)) is Step.Done)
        run.gone()
        assertEquals(Outcome.CANCELLED, (run.next(screen(at + 200)) as Step.Done).outcome)
    }

    @Test
    fun `Android Auto gone while start is held keeps the touch made`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val (tap, at) = showMenu(run, full, toTheMenu(run))
        assertEquals("start", (tap as Step.Click).what)
        assertEquals(Step.Wait, run.next(screen(at + 300)))
        assertEquals("the start was touched: the walk goes on as that", Outcome.TAPPED_START, run.gone())
        assertEquals(Step.Done(Outcome.TAPPED_START), run.next(screen(at + 400)))
    }

    /** 23/10/2026: right after "stop" the menu closes and no window of the phone's screen is active for a moment: the walk went on to "back" no more. */
    @Test
    fun `no window known in front on the way back does not end the walk, back goes on and REau is brought at the end`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.STOP, texts, 0)
        val (tap, at) = showMenu(run, running, toTheMenu(run))
        assertEquals("stop", (tap as Step.Click).what)
        fun nothing(now: Long) = ServerMenuRun.Seen(now, null, false, emptyList())
        assertEquals(Step.Back, run.next(nothing(at + 100)))
        assertEquals(Step.Wait, run.next(nothing(at + 300)))
        assertEquals("REau in front: the way back is done", Step.Done(Outcome.TAPPED_STOP), run.next(elsewhere(at + 700)))
        // and when nothing is ever known in front, REau is brought back at the end of the time given to the way back
        val other = ServerMenuRun(ServerMenuRun.Mode.STOP, texts, 0)
        val (_, at2) = showMenu(other, running, toTheMenu(other))
        assertEquals(Step.Back, other.next(nothing(at2 + 100)))
        assertEquals(Step.OpenREau, other.next(nothing(at2 + 2_600)))
    }

    @Test
    fun `Android Auto gone on the way back from stop keeps the touch made`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.STOP, texts, 0)
        val (tap, at) = showMenu(run, running, toTheMenu(run))
        assertEquals("stop", (tap as Step.Click).what)
        assertEquals(Outcome.TAPPED_STOP, run.gone())
        assertEquals(Step.Done(Outcome.TAPPED_STOP), run.next(screen(at + 100)))
    }

    @Test
    fun `Android Auto gone before any touch says so`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, timing = noHold)
        toTheMenu(run)
        assertEquals(Outcome.AA_GONE, run.gone())
        assertEquals(Outcome.AA_GONE, run.gone())
    }
}
