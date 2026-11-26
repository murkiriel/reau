package com.reau.app.ui

import com.reau.app.R
import com.reau.app.service.Projection
import com.reau.app.ui.MainLook.Warning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one notice before the first ride on a Xiaomi or realme phone, and the page it leads to (26/11/2026, A3 of the Xiaomi and realme plan). When it is due is
 * KeepAliveNoticeTest's; what the buttons open is KeepAliveOpenTest's; here is what needs a Context or a composable, read from the source as the other
 * "...IsWired" tests do, and the card's rule and the texts, which need nothing.
 */
class KeepAliveNoticeIsWiredTest {
    private val main = File("src/main/java/com/reau/app")
    private val res = File("src/main/res")

    private fun source(path: String) = File(main, path).readText()

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    // -- the card ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the card shows only while due, and not on top of the card of the system having closed REau`() {
        assertEquals(emptyList<Warning>(), MainLook.keepAliveWarnings(due = false, systemClosedPending = false))
        assertEquals(listOf(Warning.KEEP_ALIVE_ADVICE), MainLook.keepAliveWarnings(due = true, systemClosedPending = false))
        assertEquals("that card leads to the same page: one card for one subject", emptyList<Warning>(), MainLook.keepAliveWarnings(due = true, systemClosedPending = true))
        assertEquals(emptyList<Warning>(), MainLook.keepAliveWarnings(due = false, systemClosedPending = true))
    }

    @Test
    fun `the card is the last of the list`() {
        val all = MainLook.warnings(Projection(), BatteryUi(saverOn = true, exempt = true), vpn = true, locationOff = true) +
            MainLook.alertWarnings(com.reau.app.service.AlertChannelState.Level.MUTED) + MainLook.systemClosedWarnings(pending = false) +
            MainLook.keepAliveWarnings(due = true, systemClosedPending = false)
        assertEquals(listOf(Warning.BATTERY_SAVER, Warning.VPN, Warning.LOCATION_OFF, Warning.ALERTS_MUTED, Warning.KEEP_ALIVE_ADVICE), all)
        assertEquals(Warning.KEEP_ALIVE_ADVICE, Warning.entries.last())
    }

    @Test
    fun `the main screen adds the card after the others, from the state's flag, and the card has its two buttons and its tag`() {
        val screen = source("ui/MainScreen.kt")
        assertTrue(screen.contains("MainLook.systemClosedWarnings(state.systemClosedAtMs != null) +\n                        MainLook.keepAliveWarnings(state.keepAliveDue, state.systemClosedAtMs != null)"))
        val parts = source("ui/MainParts.kt")
        assertTrue(parts.contains("Warning.KEEP_ALIVE_ADVICE -> KeepAliveAdviceWarning(extras)"))
        val card = source("ui/KeepAliveCard.kt").between("internal fun KeepAliveAdviceWarning(", "\n}\n")
        assertTrue(card, "stringResource(R.string.warn_keepalive_title), stringResource(R.string.warn_keepalive)," in card)
        assertTrue(card, "CardAction(stringResource(R.string.action_keepalive_guide), \"open_keepalive_advice\", extras.onOpenKeepAlive)" in card)
        assertTrue(card, "CardAction(stringResource(R.string.action_understood), \"dismiss_keepalive_advice\", extras.onDismissKeepAliveAdvice)" in card)
        assertTrue(card, "tag = \"keepalive_advice_warning\"" in card)
        assertFalse("the flag is off by default", MainUiState().keepAliveDue)
        assertTrue(MainUiState().withKeepAliveDue(true).keepAliveDue)
    }

    // -- the activity -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the activity reads whether it is due at the opening and at each return, follows the flow and hands the flag to the state`() {
        val activity = source("MainActivity.kt")
        assertTrue(activity.between("override fun onCreate(", "setContent {").contains("com.reau.app.data.KeepAliveNotice.refresh(applicationContext, keepAliveEligible)"))
        assertTrue(activity.between("private fun resume() {", "private fun forgetRemovalFromRecents").contains("com.reau.app.data.KeepAliveNotice.refresh(this, keepAliveEligible)"))
        assertTrue(activity.contains("val keepAliveDue = com.reau.app.data.KeepAliveNotice.isDue.collectAsStateWithLifecycle().value"))
        assertTrue(activity.contains(".withKeepAliveDue(keepAliveDue),"))
        assertTrue(
            "the phone's family decides",
            activity.contains("com.reau.app.ui.KeepAliveGuide.hasNotice(com.reau.app.ui.KeepAliveGuide.current())"),
        )
    }

    @Test
    fun `Entendi on either card answers the notice, and the steps button only goes to the page, which answers it`() {
        val activity = source("MainActivity.kt")
        val open = activity.between("onOpenKeepAlive = {", "onDismissSystemClosed")
        assertTrue(open, "if (systemClosedAt != null) com.reau.app.data.SystemClosedRide.dismiss(this@MainActivity)" in open && "open()" in open)
        // The taps are one function of the activity (so onCreate stays simple); the call site gives it the page to go to.
        assertTrue(activity.contains(").withKeepAliveTaps(systemClosedAt) { page = SettingsPage.KEEP_ALIVE },"))
        val closed = activity.between("onDismissSystemClosed = {", "onDismissKeepAliveAdvice")
        assertTrue(closed, "SystemClosedRide.dismiss(this@MainActivity)" in closed && "KeepAliveNotice.answered(this@MainActivity, keepAliveEligible," in closed)
        val advice = activity.between("onDismissKeepAliveAdvice = {", "},")
        assertTrue(advice, "KeepAliveNotice.answered(this@MainActivity, keepAliveEligible," in advice)
    }

    @Test
    fun `the page is routed with what it needs from the phone, and opening it answers the notice`() {
        val activity = source("MainActivity.kt")
        assertTrue(activity.contains("SettingsPage.KEEP_ALIVE -> com.reau.app.ui.KeepAliveRoute(activity, back)"))
        val route = source("ui/KeepAliveRoute.kt")
        assertTrue(route, "val exempt = remember { BatteryWatch.read(context).exempt }" in route)
        assertTrue(route, "KeepAliveChecklist.answer(context, item, checked)" in route && "answers = KeepAliveChecklist.answers(context)" in route)
        assertTrue(route, "KeepAliveOpenAndroid.openFor(context, brand)" in route)
        assertTrue(route, "onSeen = { KeepAliveNotice.answered(context, KeepAliveGuide.hasNotice(brand), \"opened the steps\") }" in route)
        val screen = source("ui/KeepAliveScreen.kt")
        assertTrue("onSeen once, as the page opens", "LaunchedEffect(Unit) { onSeen() }" in screen)
    }

    @Test
    fun `the page shows the list and the advice only for the two families, and the Autostart button only for Xiaomi`() {
        val screen = source("ui/KeepAliveScreen.kt")
        val ticks = screen.between("if (KeepAliveGuide.hasNotice(brand)) {", "keepalive_honest")
        assertTrue(ticks, "KeepAliveGuide.batteryAdvice(batteryExempt)" in ticks && "Checklist(brand, answers, onAnswer, shape)" in ticks)
        val button = screen.between("if (brand == KeepAliveGuide.Brand.XIAOMI) {", "keepalive_open_app_info")
        assertTrue(button, "CardAction(stringResource(R.string.keepalive_open_autostart), \"keepalive_open_autostart\", onOpenAutostart)" in button)
        assertTrue("one tick for each item of the family", "for (item in KeepAliveGuide.items(brand)) {" in screen)
        assertTrue("the answer is the rider's, ticked or not", "onValueChange = { onAnswer(item, it) }" in screen)
    }

    @Test
    fun `the advice by the battery exemption says one thing for a phone that is exempt and another for one that is not or that Android did not tell`() {
        assertEquals(R.string.keepalive_battery_exempt, KeepAliveGuide.batteryAdvice(true))
        assertEquals(R.string.keepalive_battery_not_exempt, KeepAliveGuide.batteryAdvice(false))
        assertEquals(R.string.keepalive_battery_not_exempt, KeepAliveGuide.batteryAdvice(null))
    }

    // -- the line at the start of each projection ---------------------------------------------------------------------------------

    @Test
    fun `the service writes the rider's line at the start of each projection, inside a runCatching and only for the two families`() {
        val prepare = source("service/ProjectionService.kt").between("override fun prepare(): String? {", "marks.reset()")
        assertTrue(prepare, "runCatching {\n                val brand = com.reau.app.ui.KeepAliveGuide.current()" in prepare)
        assertTrue(prepare, "com.reau.app.data.KeepAliveChecklist.logAtStart(this@ProjectionService, com.reau.app.ui.KeepAliveGuide.hasNotice(brand))" in prepare)
        assertTrue("after the settings line, so the reader finds both in the projection", prepare.indexOf("SettingsInUse.line(inUse)") < prepare.indexOf("KeepAliveChecklist.logAtStart"))
        val checklist = source("data/KeepAliveChecklist.kt")
        assertTrue(checklist.between("fun logAtStart(", "/** What is kept"), "if (!eligible) return" in checklist)
        assertTrue("and when a tick changes", "runCatching { RideDiagnostics.log(\"ui\", line(book.answers())) }" in checklist)
    }

    // -- the texts --------------------------------------------------------------------------------------------------------------------

    @Test
    fun `every new text is in the four languages, the notice says what it is about and the page says it cannot check what the rider ticked`() {
        val folders = listOf("values", "values-pt", "values-es", "values-hi")
        val names = listOf(
            "warn_keepalive_title", "warn_keepalive", "keepalive_open_autostart", "keepalive_battery_exempt", "keepalive_battery_not_exempt",
            "keepalive_check_title", "keepalive_check_hint", "keepalive_item_autostart", "keepalive_item_background", "keepalive_item_lock",
            "keepalive_item_battery", "keepalive_open_failed", "voice_app_back",
        )
        for (folder in folders) {
            val xml = File(res, "$folder/strings.xml").readText()
            for (name in names) assertTrue("$folder/$name", Regex("""<string name="$name">[^<]+</string>""").containsMatchIn(xml))
        }
        fun text(folder: String, name: String) = Regex("""<string name="$name">(.*?)</string>""").find(File(res, "$folder/strings.xml").readText())!!.groupValues[1]
        assertEquals("Mantenha o REau vivo neste celular", text("values-pt", "warn_keepalive_title"))
        assertTrue(text("values-pt", "keepalive_check_hint").contains("não consegue conferir"))
        assertTrue(text("values", "keepalive_check_hint").contains("cannot check"))
        assertTrue(text("values-es", "keepalive_check_hint").contains("no puede comprobarlo"))
        for (folder in folders) assertFalse("$folder: the two advices differ", text(folder, "keepalive_battery_exempt") == text(folder, "keepalive_battery_not_exempt"))
    }

    @Test
    fun `no new text names another app, a project or a phone model`() {
        val names = Regex("""Magisk|Shizuku|Waze|Gemini|Android Auto|MIUI|HyperOS|ColorOS|Galaxy|Pixel|DontKillMyApp""", RegexOption.IGNORE_CASE)
        for (folder in listOf("values", "values-pt", "values-es", "values-hi")) {
            val xml = File(res, "$folder/strings.xml").readText()
            for (name in listOf("warn_keepalive", "keepalive_battery_exempt", "keepalive_battery_not_exempt", "keepalive_check_hint", "keepalive_open_failed")) {
                val body = Regex("""<string name="$name">(.*?)</string>""").find(xml)!!.groupValues[1]
                assertFalse("$folder/$name: $body", names.containsMatchIn(body))
            }
        }
    }
}
