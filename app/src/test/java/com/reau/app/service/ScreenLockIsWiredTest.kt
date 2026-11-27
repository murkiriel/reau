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
        assertTrue(enter, "activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)" in enter)
        assertTrue(enter, "bars(hidden = true)" in enter && "ScreenLock.available(" in enter)
        val exit = mode.substringAfter("fun exit(reason: ScreenLock.Exit)").substringBefore("/** The window's brightness")
        assertTrue(exit, "activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)" in exit && "bars(hidden = false)" in exit)
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
        assertTrue("onUnlock leaves by swipe", "SlideTrack { mode.exit(ScreenLock.Exit.SWIPE) }" in screen)
        for (animated in listOf("animate", "InfiniteTransition", "Animatable", "AnimatedVisibility", "Canvas", "Image(", "painterResource")) assertFalse(animated, animated in code("ui/ScreenLockScreen.kt"))
        assertTrue("the brightness goes to the minimum after 5 s and comes back", "DIM_AFTER_MS = 5_000L" in mode && "mode.dim(true)" in screen && "mode.dim(false)" in screen)
        for (text in listOf("R.string.lock_projecting", "R.string.lock_stop_first", "R.string.lock_slide", "R.drawable.ic_ms_lock")) assertTrue(text, text in screen)
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
    fun `the button is on the main screen only with the projection on, outlined`() {
        val buttons = source("ui/MainModeButtons.kt").substringAfter("internal fun ColumnScope.ModeButtons(")
        assertTrue(buttons, "if (ScreenLock.available(p.phase != Phase.STOPPED)) {" in buttons)
        assertTrue(buttons, "GhostButton(" in buttons && "R.string.action_lock_screen" in buttons && "extras.onLockScreen" in buttons)
        assertFalse("not the gold button", "PrimaryPowerButton" in buttons)
        assertTrue("under the bridge card", "ModeButtons(p, extras)" in source("ui/MainScreen.kt"))
        assertTrue("val onLockScreen: () -> Unit = {}" in source("ui/UiState.kt"))
    }

    @Test
    fun `the automatic function is gone, and with it its setting, its service and its wake lock`() {
        for (gone in listOf("service/ScreenKeeper.kt", "service/ScreenKeepPolicy.kt", "service/ScreenKeepFront.kt", "settings/ScreenKeepMode.kt", "ui/SettingsScreenKeep.kt", "ui/LockOverHost.kt", "ui/ManualLock.kt")) {
            assertFalse(gone, File("src/main/java/com/reau/app/$gone").exists())
        }
        val everything = File("src/main/java/com/reau/app").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (word in listOf("screenKeep", "ScreenKeeper", "SCREEN_DIM_WAKE_LOCK", "ACQUIRE_CAUSES_WAKEUP", "reau:screen-keep", "LockOverHost")) assertFalse(word, word in everything)
        val strings = File("src/main/res/values-pt/strings.xml").readText()
        assertFalse("screen_keep_" in strings || "group_screen_keep" in strings)
    }
}
