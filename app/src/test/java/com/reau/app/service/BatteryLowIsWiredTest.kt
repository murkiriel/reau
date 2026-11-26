package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the low battery's notice (plan 35, step E, 26/11/2026) is fed from and what it shows. These are of the text of the source (the places need a Context, a notification
 * manager or the compositor's GL thread, as in the other "...IsWired" tests); the rule is held by BatteryLowWatchTest and the pill's timing by BatteryPillTest.
 */
class BatteryLowIsWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()
    private fun strings(dir: String) = File("src/main/res/$dir/strings.xml").readText()

    @Test
    fun `the service makes a watch per projection, offers it each look with the level and the charger, and tells the rider on the phone and the dash`() {
        val service = source("service/ProjectionService.kt")
        val look = service.substringAfter("private fun lookAtTheDevice()").substringBefore("private fun warnIfHot")
        assertTrue(look, "val low = BatteryLowWatch()" in look)
        assertTrue(look, "warnIfBatteryLow(low, state)" in look)
        val warn = service.substringAfter("private fun warnIfBatteryLow").substringBefore("/**")
        assertTrue(warn, "if (low.offer(pct, state.charging)) {" in warn && "low.pillDue(dashUp)" in warn)
        assertTrue(warn, "ProjectionNotification.showBatteryLow(this, pct)" in warn)
        assertTrue(warn, "val onDash = low.pillDue(dashUp) && sides?.showBatteryLow(pct) == true" in warn)
        assertTrue(warn, "} else if (low.pillDue(dashUp) && sides?.showBatteryLow(pct) == true) {" in warn)
        assertTrue("the ride log says the level", "RideDiagnostics.warn(\n                \"battery\"," in warn && "battery at \$pct% with no charger" in warn)
    }

    @Test
    fun `the notice is in the alert channel, opens the app, and is not posted when the notifications are off`() {
        val notification = source("service/ProjectionNotification.kt")
        val show = notification.substringAfter("fun showBatteryLow").substringBefore("/**")
        assertTrue(show, "if (!manager.areNotificationsEnabled()) return false" in show)
        assertTrue(show, "Notification.Builder(context, ALERT_CHANNEL_ID)" in show)
        assertTrue(show, "Intent(context, MainActivity::class.java)" in show && ".setContentIntent(open)" in show)
        assertTrue(show, "R.string.notif_text_battery_low, pct" in show)
        assertTrue("its own id", "private const val ID_BATTERY_LOW = 5309" in notification)
    }

    @Test
    fun `the notice's words are in the four languages, with the level`() {
        for (dir in listOf("values", "values-pt", "values-es", "values-hi")) {
            val text = strings(dir)
            assertTrue(dir, Regex("""name="notif_text_battery_low">[^<]*%1\${'$'}d%%""").containsMatchIn(text))
            assertTrue(dir, Regex("""name="notif_title_battery_low">[^<]+<""").containsMatchIn(text))
        }
    }

    @Test
    fun `the live sides put the pill between the call card's feed and the compositor, tell it the volume card, and release it`() {
        val live = source("service/LiveSides.kt")
        val pill = live.substringAfter("private val batteryPill = com.reau.app.dash.BatteryPill(").substringBefore("private fun callCardSwitch(")
        assertTrue(pill, "show = compositor::setCallCard" in pill)
        assertTrue(pill, "scope, batteryPill::callChanged," in pill)
        assertTrue(pill, "override fun showBatteryLow(pct: Int): Boolean = batteryPill.request(" in pill)
        assertTrue(pill, "R.string.battery_pill_level, pct" in pill && "R.string.battery_pill_hint" in pill)
        val volume = live.substringAfter("private fun volumeCardChanged() {").substringBefore("}\n")
        assertTrue(volume, "compositor.setVolumeCard(showing)" in volume && "batteryPill.volumeChanged(showing != null)" in volume)
        assertTrue("\"battery pill\" to { batteryPill.drop() }" in live)
        assertEquals("the compositor is given the call card's slot by the pill alone", 1, Regex("""compositor::setCallCard""").findAll(live).count())
        assertTrue("the sides' answer is false without video", "fun showBatteryLow(pct: Int): Boolean = false" in source("service/ProjectionService.kt"))
    }

    @Test
    fun `the call card's drawing is the pill's, red all over with a battery, and the handset for a call`() {
        val ink = source("video/CallCardInk.kt")
        assertTrue(ink, "val color = if (notice.battery) RED else colorOf(CallCards.isOver(notice.card))" in ink)
        assertTrue(ink, "if (notice.battery) BATTERY else VolumeCardInk.HANDSET" in ink)
    }

    @Test
    fun `the pill makes no thread of its own, the wait is the live sides' coroutine`() {
        val pill = source("dash/BatteryPill.kt")
        assertTrue("Thread(" !in pill && "Executor" !in pill && "launch" !in pill)
    }
}
