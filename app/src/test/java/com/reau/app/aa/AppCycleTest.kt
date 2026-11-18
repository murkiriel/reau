package com.reau.app.aa

import com.reau.app.phone.CallState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * The joystick's "switch app" ([AppCycle]) with a made-up clock: the pause moves the clock and nothing really waits, so the screen the sequence
 * looks at, the taps it sends and the time of each are exactly the ones asserted.
 */
class AppCycleTest {
    @After
    fun tearDown() = AaScreens.watch.reset()

    /** Android Auto as the sequence sees it: the grid's button opens the grid [openDelayMs] after its finger goes down; an app tap leaves [afterApp]. */
    private class Bench(var openDelayMs: Long? = 400, var shown: AaScreen = AaScreen.NAV) {
        var clock = 0L
        val sent = mutableListOf<Pair<Long, AaCommand.Touch>>()
        val lines = mutableListOf<String>()
        var blockedWhy: String? = null
        var afterApp: (Int) -> AaScreen = { AaScreen.NAV }
        var onPause: suspend (Long) -> Unit = {}
        private var menuFrom: Long? = null

        val cycle = AppCycle(
            screen = { screenNow() },
            send = { command ->
                val touch = command as AaCommand.Touch
                sent += clock to touch
                if (touch.action == PhoneTouch.Action.DOWN) onDown(touch)
            },
            blocked = { blockedWhy },
            now = { clock },
            pause = { ms ->
                clock += ms
                onPause(clock)
            },
            log = { lines += it },
        )

        private fun onDown(touch: AaCommand.Touch) {
            if (touch.x == AaRail.GRID_X && touch.y == AaRail.GRID_Y) {
                menuFrom = openDelayMs?.let { clock + it }
            } else {
                val index = AppCycle.APPS.indexOf(touch.x to touch.y)
                assertTrue("a tap at (${touch.x}, ${touch.y}) is neither the grid's button nor an app", index >= 0)
                assertEquals("the app is tapped with the grid shown", AaScreen.MENU, screenNow())
                menuFrom = null
                shown = afterApp(index)
            }
        }

        private fun screenNow(): AaScreen = if (menuFrom?.let { clock >= it } == true) AaScreen.MENU else shown

        fun press(): Boolean = runBlocking { cycle.press() }

        /** The apps tapped so far, by position (1, 2 or 3), one per finger down. */
        fun appsTapped(): List<Int> = sent.filter { it.second.action == PhoneTouch.Action.DOWN }
            .mapNotNull { (_, t) -> AppCycle.APPS.indexOf(t.x to t.y).takeIf { it >= 0 }?.plus(1) }

        fun gridTaps(): List<Pair<Long, AaCommand.Touch>> = sent.filter { it.second.x == AaRail.GRID_X && it.second.y == AaRail.GRID_Y }

        fun rest(ms: Long) {
            clock += ms
        }
    }

    @Test
    fun `the grid already open, its button is not touched and the app is tapped after the grid settles`() {
        val bench = Bench(shown = AaScreen.MENU)
        assertTrue(bench.press())
        assertEquals(emptyList<Pair<Long, AaCommand.Touch>>(), bench.gridTaps())
        assertEquals(
            listOf(
                AppCycle.SETTLE_MS to AaCommand.Touch(PhoneTouch.Action.DOWN, 271, 50),
                AppCycle.SETTLE_MS + AppCycle.TAP_MS to AaCommand.Touch(PhoneTouch.Action.UP, 271, 50),
            ),
            bench.sent,
        )
        assertTrue(bench.lines.toString(), bench.lines.any { it.contains("already open") })
    }

    @Test
    fun `a grid that opens in 400 ms, the button, then the app only after the menu is seen and the grid settled`() {
        val bench = Bench(openDelayMs = 400)
        assertTrue(bench.press())
        // The finger of the button goes down at 0 and up at 80; the screen is looked at every 100 ms from 80: the menu is the screen from 400 on,
        // seen at 480; 250 ms later the app's finger goes down.
        val opened = AppCycle.TAP_MS + 4 * AppCycle.POLL_MS
        assertEquals(480L, opened)
        assertEquals(
            listOf(
                0L to AaCommand.Touch(PhoneTouch.Action.DOWN, 45, 430),
                80L to AaCommand.Touch(PhoneTouch.Action.UP, 45, 430),
                opened + 250 to AaCommand.Touch(PhoneTouch.Action.DOWN, 271, 50),
                opened + 250 + 80 to AaCommand.Touch(PhoneTouch.Action.UP, 271, 50),
            ),
            bench.sent,
        )
        assertTrue(bench.lines.toString(), bench.lines.any { it == "app cycle: grid open after 480 ms" })
        assertTrue(bench.lines.toString(), bench.lines.any { it == "app cycle: tapped app 1 of 3 at (271, 50)" })
    }

    @Test
    fun `the log tells the start, the grid, the app and the order`() {
        val bench = Bench(openDelayMs = 300)
        bench.press()
        assertEquals(
            listOf(
                "app cycle: start, app 1 of 3 next (screen=nav)",
                "app cycle: grid open after 380 ms",
                "app cycle: tapped app 1 of 3 at (271, 50)",
            ),
            bench.lines,
        )
    }

    @Test
    fun `a grid that never opens, no app is tapped, the press gives up and says why`() {
        val bench = Bench(openDelayMs = null)
        assertFalse(bench.press())
        assertEquals(listOf(0L, 80L), bench.sent.map { it.first })
        assertTrue("only the grid's button was touched", bench.sent.all { it.second.x == 45 && it.second.y == 430 })
        assertEquals(emptyList<Int>(), bench.appsTapped())
        assertTrue(bench.lines.toString(), bench.lines.any { it.startsWith("app cycle: gave up, the grid did not open within 1500 ms") && it.endsWith("no app tapped") })
        assertFalse("the walk is free again", bench.cycle.underWay)
    }

    @Test
    fun `a give-up does not move the walk, the next press that works taps the same app`() {
        val bench = Bench(openDelayMs = null)
        bench.press()
        bench.openDelayMs = 200
        bench.rest(10_000)
        assertTrue(bench.press())
        assertEquals(listOf(1), bench.appsTapped())
    }

    @Test
    fun `the wait for the grid is 1500 ms from the button's lift, opening at the last look counts, one ms later does not`() {
        // The lift is at 80, the screen is looked at 80, 180, ... 1580 (1500 ms after the lift).
        val atLimit = Bench(openDelayMs = 1_580)
        assertTrue(atLimit.press())
        val late = Bench(openDelayMs = 1_581)
        assertFalse(late.press())
        assertEquals(emptyList<Int>(), late.appsTapped())
    }

    @Test
    fun `four presses tap the apps 1, 2, 3, 1`() {
        val bench = Bench(openDelayMs = 400)
        // The third app (Spotify) is what the picture calls an app; the first two are maps.
        bench.afterApp = { if (it == 2) AaScreen.APPS else AaScreen.NAV }
        repeat(4) {
            assertTrue(bench.press())
            bench.rest(10_000)
        }
        assertEquals(listOf(1, 2, 3, 1), bench.appsTapped())
        assertEquals(
            listOf(AaCommand.Touch(PhoneTouch.Action.DOWN, 271, 50), AaCommand.Touch(PhoneTouch.Action.DOWN, 618, 50), AaCommand.Touch(PhoneTouch.Action.DOWN, 271, 195)),
            bench.sent.filter { it.second.action == PhoneTouch.Action.DOWN && it.second.x != 45 }.take(3).map { it.second },
        )
    }

    @Test
    fun `after the third app the walk goes back to the first by itself, whatever the screen says`() {
        val bench = Bench(openDelayMs = 400)
        // No screen ever says "apps" (the picture unsure, or the third app a map): only the walk's own count brings it back to the first.
        bench.afterApp = { AaScreen.NAV }
        repeat(5) {
            assertTrue(bench.press())
            bench.rest(10_000)
        }
        assertEquals(listOf(1, 2, 3, 1, 2), bench.appsTapped())
    }

    @Test
    fun `an app screen on the press takes the walk back to the first app`() {
        val bench = Bench(openDelayMs = 400)
        assertTrue(bench.press())
        assertTrue(bench.press())
        // Two apps tapped; the next would be the third. The rider went to some other app (the picture says apps), long after the last tap.
        bench.rest(30_000)
        bench.shown = AaScreen.APPS
        assertTrue(bench.press())
        assertEquals(listOf(1, 2, 1), bench.appsTapped())
        assertTrue(bench.lines.toString(), bench.lines.any { it == "app cycle: start, app 1 of 3 next (screen=apps)" })
    }

    @Test
    fun `any other screen keeps the walk where it was`() {
        for (screen in listOf(AaScreen.NAV, AaScreen.MENU, AaScreen.UNKNOWN)) {
            val bench = Bench(openDelayMs = 400)
            bench.afterApp = { AaScreen.NAV }
            assertTrue(bench.press())
            bench.rest(30_000)
            bench.shown = screen
            assertTrue(bench.press())
            assertEquals(screen.name, listOf(1, 2), bench.appsTapped())
        }
    }

    @Test
    fun `the apps screen right after a tap of the walk is its own doing, the walk goes on`() {
        // The watch says "apps" the moment an app is tapped, until the picture says the map; a second press within a few seconds goes to the next app.
        for ((gap, expected) in listOf(
            AppCycle.OWN_TAP_QUIET_MS - 1 to listOf(1, 2),
            AppCycle.OWN_TAP_QUIET_MS to listOf(1, 1),
        )) {
            val bench = Bench(openDelayMs = 400)
            bench.afterApp = { AaScreen.APPS }
            assertTrue(bench.press())
            val tappedAt = bench.clock
            bench.clock = tappedAt + gap
            assertTrue(bench.press())
            assertEquals("gap $gap", expected, bench.appsTapped())
        }
    }

    @Test
    fun `a press while a switch is under way is ignored, and says so`() {
        val bench = Bench(openDelayMs = 400)
        var second: Boolean? = null
        var underWayInside = false
        var asked = false
        bench.onPause = {
            if (!asked) {
                asked = true
                underWayInside = bench.cycle.underWay
                second = bench.cycle.press()
            }
        }
        assertTrue(bench.press())
        assertTrue(underWayInside)
        assertEquals(false, second)
        assertEquals("one switch only: one grid button, one app", listOf(1), bench.appsTapped())
        assertEquals(2, bench.gridTaps().size)
        assertEquals(1, bench.lines.count { it == "app cycle: ignored, the last switch is still under way" })
        // And it did not move the walk: once the first is over, the next press is the second app.
        bench.onPause = {}
        bench.rest(10_000)
        assertTrue(bench.press())
        assertEquals(listOf(1, 2), bench.appsTapped())
    }

    @Test
    fun `a new Android Auto session starts at the first app again`() {
        val bench = Bench(openDelayMs = 400)
        bench.press()
        bench.rest(10_000)
        bench.press()
        bench.rest(10_000)
        bench.cycle.restart()
        assertTrue(bench.press())
        assertEquals(listOf(1, 2, 1), bench.appsTapped())
    }

    @Test
    fun `the fingers are exact, down then up, 80 ms apart, at the same point, for the button and for each of the three apps`() {
        val bench = Bench(openDelayMs = 200)
        bench.afterApp = { AaScreen.NAV }
        repeat(3) {
            bench.press()
            bench.rest(10_000)
        }
        val fingers = bench.sent.chunked(2)
        assertEquals(6, fingers.size)
        val points = listOf(45 to 430, 271 to 50, 45 to 430, 618 to 50, 45 to 430, 271 to 195)
        for ((finger, point) in fingers.zip(points)) {
            val (down, up) = finger
            assertEquals(PhoneTouch.Action.DOWN, down.second.action)
            assertEquals(PhoneTouch.Action.UP, up.second.action)
            assertEquals(point, down.second.x to down.second.y)
            assertEquals(point, up.second.x to up.second.y)
            assertEquals(80L, up.first - down.first)
        }
    }

    @Test
    fun `nothing can be sent, no finger goes down, the press gives up with the reason`() {
        val bench = Bench(openDelayMs = 400)
        bench.blockedWhy = "Android Auto is not connected"
        assertFalse(bench.press())
        assertEquals(emptyList<Pair<Long, AaCommand.Touch>>(), bench.sent)
        assertEquals(0L, bench.clock)
        assertTrue(bench.lines.toString(), bench.lines.any { it == "app cycle: gave up, Android Auto is not connected; no app tapped" })
        // Not a walk step either.
        bench.blockedWhy = null
        assertTrue(bench.press())
        assertEquals(listOf(1), bench.appsTapped())
    }

    @Test
    fun `the session ends while the grid settles, the app is not tapped`() {
        val bench = Bench(openDelayMs = 400)
        bench.onPause = { now -> if (now > 480) bench.blockedWhy = "Android Auto is not connected" }
        assertFalse(bench.press())
        assertEquals(emptyList<Int>(), bench.appsTapped())
        assertEquals("the grid's button only", 2, bench.sent.size)
    }

    @Test
    fun `the reason nothing can be sent, no session, no touch screen, a call ringing or under way, in that order`() {
        assertNull(AppCycle.blockedReason(androidAutoActive = true, touchAnnounced = true, call = CallState.IDLE))
        assertEquals("a call is ringing or under way", AppCycle.blockedReason(true, true, CallState.RINGING))
        assertEquals("a call is ringing or under way", AppCycle.blockedReason(true, true, CallState.IN_CALL))
        assertEquals("Android Auto is not connected", AppCycle.blockedReason(false, true, CallState.IDLE))
        assertEquals("Android Auto was not told of a touch screen", AppCycle.blockedReason(true, false, CallState.IDLE))
        assertEquals("Android Auto is not connected", AppCycle.blockedReason(false, false, CallState.IN_CALL))
        assertEquals("Android Auto was not told of a touch screen", AppCycle.blockedReason(true, false, CallState.RINGING))
    }

    @Test
    fun `a call ringing or under way, no finger goes down and the log says why`() {
        for (call in listOf(CallState.RINGING, CallState.IN_CALL)) {
            val bench = Bench(openDelayMs = 400)
            bench.blockedWhy = AppCycle.blockedReason(true, true, call)
            assertFalse("$call", bench.press())
            assertEquals("$call", emptyList<Pair<Long, AaCommand.Touch>>(), bench.sent)
            assertTrue(bench.lines.toString(), bench.lines.any { it == "app cycle: gave up, a call is ringing or under way; no app tapped" })
        }
    }

    @Test
    fun `a call that comes while the grid settles, the grid's button was touched, the app is not`() {
        val bench = Bench(openDelayMs = 400)
        bench.onPause = { now -> if (now > 480) bench.blockedWhy = AppCycle.blockedReason(true, true, CallState.RINGING) }
        assertFalse(bench.press())
        assertEquals(emptyList<Int>(), bench.appsTapped())
        assertEquals(2, bench.sent.size)
        assertTrue(bench.lines.toString(), bench.lines.any { it.contains("a call is ringing or under way") })
    }

    @Test
    fun `a switch cancelled between the finger down and up still lifts the finger`() {
        val bench = Bench(openDelayMs = 400)
        bench.onPause = { throw CancellationException("the projection ended") }
        val outcome = runBlocking { runCatching { bench.cycle.press() } }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertEquals(listOf(PhoneTouch.Action.DOWN, PhoneTouch.Action.UP), bench.sent.map { it.second.action })
        assertFalse("and the walk is free again", bench.cycle.underWay)
    }

    @Test
    fun `the grid button is the side bar's lowest one, from the rail`() {
        assertEquals(45, AaRail.GRID_X)
        assertEquals(430, AaRail.GRID_Y)
        assertNull("it is not a shortcut of an app", AaRail.slot(AaRail.GRID_X, AaRail.GRID_Y))
        assertEquals(listOf(271 to 50, 618 to 50, 271 to 195), AppCycle.APPS)
        assertEquals(3, AppCycle.COUNT)
    }

    @Test
    fun `the taps of the walk, fed to the screen watch as the receiver feeds them, take the screen from the menu to the apps`() {
        val watch = AaScreens.watch
        watch.reset()
        watch.homeSent()
        assertEquals(AaScreen.MENU, watch.screen.value)
        var clock = 1_000L
        val taps = TapWatch()
        val cycle = AppCycle(
            screen = { watch.screen.value },
            send = { command ->
                val t = command as AaCommand.Touch
                if (taps.feed(t.action, t.x, t.y, clock)) AaScreens.tapSent(t.x, t.y, nowMs = clock)
            },
            blocked = { null },
            now = { clock },
            pause = { clock += it },
            log = {},
        )
        assertTrue(runBlocking { cycle.press() })
        assertEquals("the app tapped in the menu is a select: the screen is the apps' while the picture says which", AaScreen.APPS, watch.screen.value)
    }
}
