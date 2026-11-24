package com.reau.app.ui

import com.reau.app.service.AlertChannelState.Level
import com.reau.app.ui.AlertRoute.Route
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the main screen's alert card does (24/11/2026): after a clean install on Android 14 the card said "blocked" before REau had asked for the
 * notification permission, and its button (the channel's page) turned nothing on, fifteen times. Now: no card before the question; with the whole
 * app blocked the button asks for the permission, or opens the app's page when Android will not show its window; the channel's page only for a
 * channel that is blocked or muted inside an allowed app.
 */
class AlertRouteTest {
    private val android14 = 34
    private val android12 = 32

    private fun route(
        level: Level,
        appEnabled: Boolean = true,
        asked: Boolean = true,
        canShowWindow: Boolean = false,
        sdk: Int = android14,
    ) = AlertRoute.route(level, appEnabled, asked, canShowWindow, sdk)

    @Test
    fun `loud alerts show no card whatever else is true`() {
        for (enabled in listOf(true, false)) for (asked in listOf(true, false)) for (window in listOf(true, false)) {
            assertEquals(Route.NONE, route(Level.LOUD, enabled, asked, window))
        }
    }

    @Test
    fun `a clean install on Android 13 or later shows no card before the permission was asked`() {
        assertEquals(Route.NONE, route(Level.BLOCKED, appEnabled = false, asked = false))
        assertEquals(Route.NONE, route(Level.BLOCKED, appEnabled = false, asked = false, canShowWindow = true))
        assertEquals(Route.NONE, route(Level.BLOCKED, appEnabled = false, asked = false, sdk = 33))
    }

    @Test
    fun `the app blocked after the question asks for the permission while Android still shows its window`() {
        assertEquals(Route.ASK_PERMISSION, route(Level.BLOCKED, appEnabled = false, canShowWindow = true))
        assertEquals(Route.ASK_PERMISSION, route(Level.BLOCKED, appEnabled = false, canShowWindow = true, sdk = 33))
    }

    @Test
    fun `the app blocked for good opens the page of the app, not of the channel`() {
        assertEquals(Route.APP_PAGE, route(Level.BLOCKED, appEnabled = false, canShowWindow = false))
    }

    @Test
    fun `before Android 13 there is no permission to ask, so the app blocked opens the app's page, asked or not`() {
        assertEquals(Route.APP_PAGE, route(Level.BLOCKED, appEnabled = false, asked = false, sdk = android12))
        assertEquals(Route.APP_PAGE, route(Level.BLOCKED, appEnabled = false, asked = true, canShowWindow = true, sdk = android12))
        assertEquals(Route.APP_PAGE, route(Level.BLOCKED, appEnabled = false, sdk = 29))
    }

    @Test
    fun `an allowed app with a blocked or muted channel opens the channel's page`() {
        for (level in listOf(Level.BLOCKED, Level.MUTED)) for (asked in listOf(true, false)) for (sdk in listOf(android12, android14)) {
            assertEquals(Route.CHANNEL_PAGE, route(level, appEnabled = true, asked = asked, canShowWindow = true, sdk = sdk))
        }
    }

    @Test
    fun `a muted channel in an allowed app is the channel's page even if the permission was never asked`() {
        assertEquals(Route.CHANNEL_PAGE, route(Level.MUTED, asked = false))
    }

    @Test
    fun `after the window the app's page opens only when the app is still blocked and no person answered`() {
        // Allowed: nothing more to do.
        assertFalse(AlertRoute.openAppPageAfter(granted = true, enabledNow = true, elapsedMs = 20))
        assertFalse(AlertRoute.openAppPageAfter(granted = false, enabledNow = true, elapsedMs = 20))
        // Refused after a person read the window: the rider chose, no page is thrown at them.
        assertFalse(AlertRoute.openAppPageAfter(granted = false, enabledNow = false, elapsedMs = 2_500))
        // Refused at once: Android showed no window (denied for good), so the page is the way.
        assertTrue(AlertRoute.openAppPageAfter(granted = false, enabledNow = false, elapsedMs = 30))
        // Allowed yet still blocked: the switch is off in Android's own settings.
        assertTrue(AlertRoute.openAppPageAfter(granted = true, enabledNow = false, elapsedMs = 2_500))
    }

    @Test
    fun `the window is taken as not shown below 400 ms`() {
        assertEquals(400L, AlertRoute.WINDOW_MIN_MS)
        assertTrue(AlertRoute.openAppPageAfter(granted = false, enabledNow = false, elapsedMs = 399))
        assertFalse(AlertRoute.openAppPageAfter(granted = false, enabledNow = false, elapsedMs = 400))
    }

    @Test
    fun `the screen hides the card when the route is none and the button's words follow the route`() {
        val main = File("src/main/java/com/reau/app/MainActivity.kt").readText()
        assertTrue(main.contains("val level = if (route == com.reau.app.ui.AlertRoute.Route.NONE) com.reau.app.service.AlertChannelState.Level.LOUD else read"))
        assertTrue(main.contains("alertRoute = alertRoute,"))
        val parts = File("src/main/java/com/reau/app/ui/MainParts.kt").readText()
        assertTrue(parts.contains("if (route == AlertRoute.Route.ASK_PERMISSION) R.string.action_allow_alerts else R.string.action_open_alert_settings"))
        assertTrue(parts.contains("CardAction(stringResource(alertActionLabel(route)), \"open_alert_settings\", onOpenAlertSettings)"))
        val settings = File("src/main/java/com/reau/app/ui/AlertSettings.kt").readText()
        assertTrue(settings.contains("AlertRoute.Route.ASK_PERMISSION -> ask()"))
        assertTrue(settings.contains("AlertRoute.Route.APP_PAGE -> openPage(activity, channel = false)"))
        assertTrue(settings.contains("AlertRoute.Route.CHANNEL_PAGE -> openPage(activity, channel = true)"))
    }

    @Test
    fun `the button's words exist in the three languages and differ from the settings' ones`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val xml = File("src/main/res/$folder/strings.xml").readText()
            val allow = Regex("""<string name="action_allow_alerts">(.*?)</string>""").find(xml)?.groupValues?.get(1)
            val open = Regex("""<string name="action_open_alert_settings">(.*?)</string>""").find(xml)?.groupValues?.get(1)
            assertTrue(folder, !allow.isNullOrBlank() && allow != open)
        }
        assertEquals("Permitir os avisos", Regex("""<string name="action_allow_alerts">(.*?)</string>""").find(File("src/main/res/values-pt/strings.xml").readText())?.groupValues?.get(1))
    }
}
