package com.reau.app.data

import com.reau.app.service.Projection
import com.reau.app.ui.BatteryUi
import com.reau.app.ui.MainLook
import com.reau.app.ui.MainLook.Warning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rule of [SystemClosedRide] is tested in SystemClosedRideTest; where the opening asks it, where the activity keeps and hands it to the screen, and the card
 * and the guide it points to need a Context, the activity and the composables, so those lines are read from the source, as the other "...IsWired" tests do.
 */
class SystemClosedRideIsWiredTest {
    private val main = File("src/main/java/com/reau/app")
    private val res = File("src/main/res")

    private fun source(path: String) = File(main, path).readText()

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `the exit history read at the opening hands the exits to the rule, inside a runCatching`() {
        val report = source("data/ExitHistory.kt").between("fun reportPrevious(", "\n    /**")
        assertTrue(report, "runCatching { SystemClosedRide.note(context, byExit.keys.toList(), System.currentTimeMillis()) }" in report)
        // The same call that tells the `exit` lines, at the app's opening and at a projection's start (ExitLineAtOpeningIsWiredTest).
        assertTrue(source("REauApp.kt").contains("com.reau.app.data.ExitHistory.reportPrevious(this)"))
        assertTrue(source("service/ProjectionService.kt").contains("ExitHistory.reportPrevious(this@ProjectionService)"))
    }

    @Test
    fun `noting an exit asks the rule, keeps the time, tells the screen and writes one line in the ride log`() {
        val note = source("data/SystemClosedRide.kt").between("fun note(", "/** The rider dismissed")
        assertTrue(note, "val exit = pick(exits, book.toldUpTo(), nowMs) ?: return" in note)
        assertTrue(note, "if (!book.note(exit.timestampMs)) return" in note)
        assertTrue(note, "told.value = book.pendingAt()" in note)
        assertTrue(note, "runCatching {" in note && "RideDiagnostics.log(\"ui\", logLine(exit, at))" in note)
        assertTrue(note.indexOf("if (!book.note(") < note.indexOf("RideDiagnostics.log"))
    }

    @Test
    fun `the ride log line says when, why, and that the rider is told, with nothing of the phone`() {
        val exit = PastExit(1_000L, "com.reau.app", 7, 13, 0, 125, 0, 0, "[UNKNOWN] o-kill(6)")
        val line = SystemClosedRide.logLine(exit, "08:38")
        assertEquals("the system closed REau during the last projection (at 08:38, reason other (13)): the rider is told", line)
        assertFalse("no pid, no process name, no description of the maker", Regex("""pid|com\.reau|o-kill""").containsMatchIn(line))
    }

    @Test
    fun `the activity loads what is kept, follows the flow, and hands the time to the main screen's state`() {
        val activity = source("MainActivity.kt")
        assertTrue(activity.between("override fun onCreate(", "setContent {").contains("com.reau.app.data.SystemClosedRide.load(applicationContext)"))
        assertTrue(activity.contains("val systemClosedAt = com.reau.app.data.SystemClosedRide.pendingAt.collectAsStateWithLifecycle().value"))
        // The flag of the one-time notice (KeepAliveNotice) follows it in the same chain (KeepAliveNoticeIsWiredTest).
        assertTrue(activity.contains("mainState(projection, settings, startedAt, pairing.ssid).withNetwork(networkOn).withSystemClosed(systemClosedAt)\n"))
    }

    @Test
    fun `the notice's two buttons dismiss it, and the one that opens the guide dismisses it too`() {
        val activity = source("MainActivity.kt")
        val open = activity.between("onOpenKeepAlive = {", "onDismissSystemClosed")
        assertTrue(open, "com.reau.app.data.SystemClosedRide.dismiss(this@MainActivity)" in open && "open()" in open)
        assertTrue(activity.contains(").withKeepAliveTaps(systemClosedAt) { page = SettingsPage.KEEP_ALIVE },"))
        // "Entendi" also answers the one-time notice for the phones of the two makers (KeepAliveNotice), so it does not come on top of the closing just told.
        val understood = activity.between("onDismissSystemClosed = {", "onDismissKeepAliveAdvice")
        assertTrue(understood, "com.reau.app.data.SystemClosedRide.dismiss(this@MainActivity)" in understood)
        val dismiss = source("data/SystemClosedRide.kt").between("fun dismiss(", "/** What is kept")
        assertTrue(dismiss, "Book.open(context).dismiss()" in dismiss && "told.value = null" in dismiss)
    }

    @Test
    fun `the guide's page is routed, with the back button, and its button opens the app's details page`() {
        val activity = source("MainActivity.kt")
        assertTrue(activity.contains("TIPS, KEEP_ALIVE }"))
        assertTrue(activity.contains("SettingsPage.KEEP_ALIVE -> com.reau.app.ui.KeepAliveRoute(activity, back)"))
        // The route owns the page's buttons now (the app's details by the chain that logs and tells when it fails: KeepAliveOpen).
        val route = source("ui/KeepAliveRoute.kt")
        assertTrue(route, "KeepAliveOpenAndroid.openFor(context, KeepAliveGuide.Brand.OTHER)" in route && "KeepAliveScreen(\n        back," in route)
        // The permissions page reaches it too, near the battery line.
        assertTrue(activity.contains("onOpenKeepAlive = { goTo(SettingsPage.KEEP_ALIVE) },"))
        val way = source("ui/PermissionsScreen.kt")
        assertTrue(way.contains("Kind.SPECIAL -> Way(R.string.permissions_keep_alive, \"permissions_keep_alive\", actions.onOpenKeepAlive)"))
        assertTrue("the battery line is in the special group", source("settings/AppPermissions.kt").contains("Explained(BATTERY, Kind.SPECIAL"))
    }

    @Test
    fun `the main screen shows the card last, only while a time is pending, with its two buttons and its tag`() {
        assertTrue(source("ui/MainScreen.kt").contains("MainLook.alertWarnings(state.alertLevel) +\n                        MainLook.systemClosedWarnings(state.systemClosedAtMs != null)"))
        val parts = source("ui/MainParts.kt")
        assertTrue(parts.contains("Warning.SYSTEM_CLOSED -> SystemClosedWarning(state.systemClosedAtMs, extras)"))
        // The card itself is in KeepAliveCard.kt since 26/11/2026 (MainParts.kt had reached the most functions detekt allows).
        val card = source("ui/KeepAliveCard.kt").between("internal fun SystemClosedWarning(", "/**")
        assertTrue(card, "SystemClosedRide.stamp(it, System.currentTimeMillis())" in card)
        assertTrue(card, "stringResource(R.string.warn_system_closed, at)" in card)
        assertTrue(card, "CardAction(stringResource(R.string.action_keepalive_guide), \"open_keepalive\", extras.onOpenKeepAlive)" in card)
        assertTrue(card, "CardAction(stringResource(R.string.action_understood), \"dismiss_system_closed\", extras.onDismissSystemClosed)" in card)
        assertTrue(card, "tag = \"system_closed_warning\"" in card)
    }

    @Test
    fun `the card is the last warning of the list, and only when asked for`() {
        val fine = BatteryUi(saverOn = false, exempt = true)
        assertEquals(emptyList<Warning>(), MainLook.warnings(Projection(), fine))
        assertEquals(emptyList<Warning>(), MainLook.systemClosedWarnings(pending = false))
        assertEquals(listOf(Warning.SYSTEM_CLOSED), MainLook.systemClosedWarnings(pending = true))
        // After the alert cards too (MainScreen adds it last).
        val all = MainLook.warnings(Projection(), BatteryUi(saverOn = true, exempt = true), vpn = true, locationOff = true) +
            MainLook.alertWarnings(com.reau.app.service.AlertChannelState.Level.MUTED) + MainLook.systemClosedWarnings(pending = true)
        assertEquals(listOf(Warning.BATTERY_SAVER, Warning.VPN, Warning.LOCATION_OFF, Warning.ALERTS_MUTED, Warning.SYSTEM_CLOSED), all)
    }

    @Test
    fun `the notice's texts are in the four languages, say it is the phone's and not the dash's, and take the time`() {
        val words = mapOf(
            "values" to ("not from the dash" to "closed REau"),
            "values-pt" to ("não do painel" to "fechou o REau"),
            "values-es" to ("no del tablero" to "cerró REau"),
            "values-hi" to ("डैश की ओर से नहीं" to "REau को बंद कर दिया"),
        )
        for ((folder, pair) in words) {
            val xml = File(res, "$folder/strings.xml").readText()
            val text = Regex("""<string name="warn_system_closed">(.*)</string>""").find(xml)?.groupValues?.get(1)
            assertTrue("$folder has no warn_system_closed", !text.isNullOrBlank())
            assertTrue("$folder: $text", pair.first in text!! && pair.second in text)
            assertEquals("$folder: the time", 1, Regex("""%1\${'$'}s""").findAll(text).count())
            for (name in listOf("warn_system_closed_title", "action_keepalive_guide")) {
                assertTrue("$folder/$name", Regex("""<string name="$name">[^<]+</string>""").containsMatchIn(xml))
            }
        }
        val pt = Regex("""<string name="warn_system_closed_title">(.*)</string>""").find(File(res, "values-pt/strings.xml").readText())!!.groupValues[1]
        assertEquals("O celular fechou o REau", pt)
    }
}
