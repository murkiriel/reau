package com.reau.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the "Bloquear a tela" button reaches the window and what it asks of Android. These are of the text of the source (the places need an Activity, a
 * DisplayManager and a window, as in the other "...IsWired" tests); what the mode decides is held by ScreenLockTest.
 */
class ScreenLockIsWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private fun code(path: String): String {
        val comment = Regex("""^\s*(\*|//|/\*)""")
        return source(path).lines().filterNot { comment.containsMatchIn(it) }.joinToString("\n")
    }

    private val mode = source("ui/ScreenLockMode.kt")

    @Test
    fun `the window keeps the screen on while the mode lasts and lets it go on the way out`() {
        val enter = mode.substringAfter("fun enter()").substringBefore("/** The activity left the front")
        assertTrue(enter, "keep.want(SOURCE)" in enter)
        assertTrue(enter, "bars(hidden = true)" in enter && "ScreenLock.available(" in enter)
        val exit = mode.substringAfter("fun exit(reason: ScreenLock.Exit)").substringBefore("/** The window's brightness")
        assertTrue(exit, "keep.release(SOURCE)" in exit && "bars(hidden = false)" in exit)
        assertTrue(exit, "back?.remove()" in exit && "activity.unregisterReceiver(it)" in exit)
    }

    @Test
    fun `Android is never asked to unlock, to show the app over a lock screen or to turn the screen on`() {
        for (file in listOf("ui/ScreenLockMode.kt", "ui/ScreenLockScreen.kt", "service/ScreenLock.kt", "ui/MainModeButtons.kt")) {
            val text = code(file)
            for (forbidden in listOf("requestDismissKeyguard", "DISMISS_KEYGUARD", "setShowWhenLocked", "setTurnScreenOn", "SHOW_WHEN_LOCKED", "TURN_SCREEN_ON", "KeyguardManager", "DevicePolicy", "AccessibilityService")) {
                assertFalse("$file: $forbidden", forbidden in text)
            }
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("no activity of its own", "ScreenKeepActivity" in manifest || "showWhenLocked" in manifest || "turnScreenOn" in manifest)
        assertEquals("WAKE_LOCK was already there", 1, Regex("""android\.permission\.WAKE_LOCK""").findAll(manifest).count())
        for (permission in listOf("SYSTEM_ALERT_WINDOW", "USE_FULL_SCREEN_INTENT", "BIND_DEVICE_ADMIN", "BIND_ACCESSIBILITY_SERVICE")) assertFalse(permission, permission in manifest)
    }

    @Test
    fun `the screen going off is read from the display's state, with the broadcast as a second source, and the listener is removed`() {
        val enter = mode.substringAfter("fun enter()").substringBefore("/** The activity left the front")
        assertTrue(enter, "watchTheDisplay()" in enter && "Intent.ACTION_SCREEN_OFF" in enter)
        val watch = mode.substringAfter("private fun watchTheDisplay()").substringBefore("private fun bars(")
        assertTrue(watch, "manager.registerDisplayListener(listener, main)" in watch && "displayId != Display.DEFAULT_DISPLAY" in watch)
        assertTrue(watch, "if (change.off) exit(ScreenLock.Exit.SCREENOFF)" in watch && "log(change.line)" in watch)
        assertTrue("unregisterDisplayListener(it)" in mode.substringAfter("fun exit(reason: ScreenLock.Exit)"))
    }

    @Test
    fun `leaving the front, the projection stopping and Back are each handled`() {
        assertTrue("exit(if (off) ScreenLock.Exit.SCREENOFF else ScreenLock.Exit.LEFT)" in mode.substringAfter("fun stopped()"))
        assertTrue("if (it.phase == Phase.STOPPED) exit(ScreenLock.Exit.PROJECTION)" in mode)
        val back = mode.substringAfter("override fun handleOnBackPressed()").substringBefore("}")
        assertTrue(back, "state.backIgnored()?.let(::log)" in back && "exit(" !in back)
        val main = source("MainActivity.kt")
        assertTrue("lockMode.stopped()" in main.substringAfter("override fun onStop()"))
        assertTrue("lockMode.attach()" in main.substringAfter("override fun onPostCreate"))
        assertTrue("onLockScreen = { lockMode.enter() }" in main)
    }

    @Test
    fun `the screen swallows every touch and only the thumb dragged to the end leaves`() {
        val screen = source("ui/ScreenLockScreen.kt")
        assertTrue("every touch is taken", "private fun Modifier.swallowTouches()" in screen && "Modifier.fillMaxSize().swallowTouches()" in screen)
        assertTrue("only a touch on the thumb drags", "ScreenLock.hitsThumb(" in screen && "if (onThumb) {" in screen)
        assertTrue("past 90 percent leaves, else back to the start", "if (onThumb && ScreenLock.unlocked(ScreenLock.progress(dragPx, travel))) onUnlock() else dragPx = 0f" in screen)
        assertTrue("onUnlock leaves by swipe", ") { mode.exit(ScreenLock.Exit.SWIPE) }" in screen && "SlideTrack(\n                    Modifier.padding(start = TRACK_MARGIN, end = TRACK_MARGIN, bottom = bottom)," in screen)
        for (animated in listOf("animate", "InfiniteTransition", "Animatable", "AnimatedVisibility", "Canvas", "Image(", "painterResource")) assertFalse(animated, animated in code("ui/ScreenLockScreen.kt"))
        assertTrue("the brightness goes to the minimum after 5 s and comes back", "DIM_AFTER_MS = 5_000L" in mode && "mode.dim(true)" in screen && "mode.dim(false)" in screen)
        for (text in listOf("R.string.lock_projecting", "R.string.lock_stop_first", "R.string.lock_slide", "R.string.lock_card_panel", "R.string.lock_card_projection", "R.string.lock_card_battery")) {
            assertTrue(text, text in screen)
        }
        assertTrue("the layer is on top of the screens, drawn only while the mode is on", "if (active) ScreenLockScreen(this@ScreenLockMode)" in mode && "activity.addContentView(" in mode)
    }

    @Test
    fun `the track is kept out of the gesture navigation's edges and its drag is excluded from the system's gestures`() {
        val screen = source("ui/ScreenLockScreen.kt")
        assertTrue("the whole track is excluded from the system gestures", ".systemGestureExclusion()" in screen.substringAfter("private fun SlideTrack("))
        assertTrue("a margin of 32 dp from each side", "private val TRACK_MARGIN = 32.dp" in screen && "Modifier.padding(start = TRACK_MARGIN, end = TRACK_MARGIN, bottom = bottom)" in screen)
        assertTrue("up from the bottom edge: at least 72 dp, or the gesture inset plus 24 dp", "private val TRACK_BOTTOM_MIN = 72.dp" in screen &&
            "maxOf(TRACK_BOTTOM_MIN, WindowInsets.systemGestures.asPaddingValues().calculateBottomPadding() + 24.dp)" in screen)
    }

    @Test
    fun `the button is under the bridge card only with the projection on, gold like Ligar and with the same component, and the wallpaper stays for the stopped projection`() {
        val buttons = source("ui/MainModeButtons.kt").substringAfter("internal fun ColumnScope.ModeButtons(")
        assertTrue(buttons, "if (ScreenLock.buttonShown(lockModeOn, p.phase, p.dash)) {" in buttons)
        assertTrue(buttons, "GoldBigButton(\n            R.drawable.ic_ms_lock, stringResource(R.string.action_lock_screen), extras.onLockScreen," in buttons)
        assertTrue(buttons, "Modifier.fillMaxWidth().height(56.dp).testTag(\"lock_screen\")" in buttons)
        assertTrue("a size smaller than Ligar's: 56 dp, 18 sp, a 26 dp icon", "iconSize = 26.dp, textSize = 18.sp" in buttons)
        assertTrue("the wallpaper only while stopped", "if (p.phase == Phase.STOPPED && !p.stopping) {" in buttons.substringBefore("ScreenLock.buttonShown") && "extras.onCard" in buttons)
        assertFalse("not a second gold one: the outlined stop is Desligar", "PrimaryPowerButton" in buttons)
        val bridge = source("ui/components/Bridge.kt")
        val gold = bridge.substringAfter("fun GoldBigButton(")
        assertTrue(gold, "textSize: TextUnit = 22.sp" in gold && "iconSize: Dp = 32.dp" in gold && "MsIcon(icon, iconSize, LocalContentColor.current)" in gold && "containerColor = colors.primary, contentColor = colors.onPrimary" in gold)
        assertTrue("Ligar is made of the same component", "GoldBigButton(R.drawable.ic_ms_power_settings_new," in bridge.substringAfter("fun PrimaryPowerButton("))
        assertTrue("under the bridge card", "ModeButtons(p, state.lockModeOn, extras)" in source("ui/MainScreen.kt"))
        assertTrue("val onLockScreen: () -> Unit = {}" in source("ui/UiState.kt"))
    }

    @Test
    fun `the lock screen is on absolute black, dim, and its card has an outline and no fill`() {
        val screen = source("ui/ScreenLockScreen.kt")
        assertTrue("the ground is #000000, a conscious exception to Aurum", "internal val LockBlack = Color(0xFF000000)" in screen && "color = LockBlack" in screen)
        assertTrue("the exception says why", "AMOLED" in screen.substringBefore("internal val LockBlack"))
        assertFalse("the screen's ground is not the theme's", "MaterialTheme.colorScheme.background" in code("ui/ScreenLockScreen.kt"))
        val card = screen.substringAfter("private fun LockCard()").substringBefore("/** The dash's side of the card")
        assertTrue(card, ".border(1.dp, LockOutline, RoundedCornerShape(20.dp))" in card)
        assertFalse("no fill on the card", ".background(" in card || "Surface(" in card || "containerColor" in card)
        for (white in listOf("Color.White", "0xFFFFFFFF", "Color(0xFFFFFF")) assertFalse(white, white in screen)
        assertTrue("dim inks: ivory at 70 % or less, soft gold at 60 % or less", "Marfim.copy(alpha = 0.70f)" in screen && "OuroSuave.copy(alpha = 0.60f)" in screen && "OuroSuave.copy(alpha = 0.15f)" in screen)
        assertTrue("the seal's dot is an outline", "Box(Modifier.size(8.dp).border(1.5.dp, green, CircleShape))" in screen)
    }

    @Test
    fun `the card's data comes from what the app already has, and the brightness comes back only by a touch on the thumb`() {
        val screen = source("ui/ScreenLockScreen.kt")
        assertTrue("the projection and when it began", "ProjectionService.projection.collectAsStateWithLifecycle()" in screen && "ProjectionService.startedAt.collectAsStateWithLifecycle()" in screen)
        assertTrue("the battery of the last device look", "PhoneNow.batteryPctNow()" in screen && "PhoneNow.batteryTempNow()" in screen)
        assertTrue("the device look feeds the percent", "batteryPct = it.batteryPct" in source("service/DeviceStateReader.kt"))
        assertTrue("the thumb's touch alone", "if (rule.touch(SystemClock.elapsedRealtime(), onTrack = true)) touches++" in screen && "onTouch()" in screen)
        val effect = screen.substringAfter("LaunchedEffect(touches) {").substringBefore("DisposableEffect")
        assertTrue(effect, "mode.dim(false)" in effect && "delay(rule.msUntilDim(SystemClock.elapsedRealtime()))" in effect && "mode.dim(true)" in effect)
        assertEquals("the swallowing of the rest of the screen touches nothing", 1, Regex("""touches\+\+""").findAll(screen).count())
    }

    @Test
    fun `the thumb reads how far the finger moved before it consumes the move, or it never leaves its place`() {
        val track = source("ui/ScreenLockScreen.kt").substringAfter("private fun SlideTrack(")
        val read = track.indexOf("val step = change.positionChangeIgnoreConsumed().x")
        val consumed = track.indexOf("change.consume()", startIndex = track.indexOf("awaitPointerEvent()"))
        assertTrue("the move is read", read >= 0)
        assertTrue("before the consume (26/11/2026: the thumb stood still on the owner's phone)", read in 0 until consumed)
        assertTrue("and that reading is what moves the thumb", "dx += step" in track)
    }
}
