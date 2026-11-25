package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.InfoRingFeed
import com.reau.app.service.LiveTuning
import com.reau.app.service.ProjectionService
import com.reau.app.weather.RingData
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch "Informações no título (no lugar do anel)" (P31 Título, "infoTitle"): off by default until 21/11/2026, on by default since then (the owner's phone's settings made the defaults), kept, taken while running, named with letters
 * only, logged with the settings in use, in the info ring's group of the settings screen in the three languages, and the ring is not drawn with it on.
 */
class InfoTitleSettingsTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private fun string(folder: String, name: String) =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]

    @Test
    fun `it is on on a new install since 21-11-2026, with nothing stored and sanitized, so the ring is written in the title and not drawn`() {
        assertTrue(REauSettings().infoTitle)
        assertTrue(SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().infoTitle)
        assertTrue(REauSettings().sanitized().infoTitle)
        assertTrue("the ring is on with it", REauSettings().infoRing)
        assertFalse("and it is not drawn on the video", InfoRingFeed.ringDrawn(infoRing = REauSettings().infoRing, infoTitle = REauSettings().infoTitle))
        assertTrue("the ring as it was, with the title off", InfoRingFeed.ringDrawn(infoRing = true, infoTitle = REauSettings().copy(infoTitle = false).infoTitle))
    }

    @Test
    fun `the rider's choice is kept, apart from the ring's own switch`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(infoRing = true, infoTitle = true))
        SettingsStore(prefs).load().let {
            assertTrue(it.infoTitle)
            assertTrue(it.infoRing)
            assertTrue(it.sanitized().infoTitle)
        }
        SettingsStore(prefs).save(REauSettings(infoRing = true, infoTitle = false))
        SettingsStore(prefs).load().let {
            assertFalse(it.infoTitle)
            assertTrue(it.infoRing)
        }
        SettingsStore(prefs).save(REauSettings(infoRing = false, infoTitle = true))
        SettingsStore(prefs).load().let {
            assertTrue("kept for when the ring is switched on again", it.infoTitle)
            assertFalse(it.infoRing)
        }
    }

    @Test
    fun `a change is taken by the running projection at once, with the ring's extras`() {
        val before = REauSettings(infoRing = true, infoTitle = false)
        assertEquals(setOf(LiveTuning.Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(infoTitle = true)))
        assertEquals(setOf(LiveTuning.Step.DASH_EXTRAS), LiveTuning.steps(before.copy(infoTitle = true), before))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(before, before.copy()))
    }

    @Test
    fun `the settings line of the ride log says it, with a name of letters only`() {
        assertTrue(" infoRingInPlace=false infoTitle=true webView=" in SettingsInUse.line(REauSettings()))
        assertTrue(" infoRingInPlace=false infoTitle=false webView=" in SettingsInUse.line(REauSettings(infoTitle = false)))
        assertTrue(" infoTitle=true " in SettingsInUse.line(REauSettings(infoRing = true, infoTitle = true)) + " ")
        assertTrue("infoTitle".all { it.isLetter() })
        assertEquals(listOf("infoTitle: true -> false"), REauSettings().changesTo(REauSettings(infoTitle = false)))
        assertTrue(source("settings/SettingsStore.kt"), "private const val KEY_INFO_TITLE = \"infoTitle\"" in source("settings/SettingsStore.kt"))
    }

    @Test
    fun `the ring is not drawn with the option on, and is with it off`() {
        assertFalse(InfoRingFeed.ringDrawn(infoRing = true, infoTitle = true))
        assertTrue(InfoRingFeed.ringDrawn(infoRing = true, infoTitle = false))
        assertFalse("the ring's own switch off", InfoRingFeed.ringDrawn(infoRing = false, infoTitle = false))
        assertFalse(InfoRingFeed.ringDrawn(infoRing = false, infoTitle = true))
    }

    @Test
    fun `the ring's loop gives no frame and gives the GPS back with the option on`() {
        var gpsStops = 0
        val loop = InfoRingFeed.Loop(ticksPerLook = 1, stopGps = { gpsStops++ }, warn = {})
        var made = 0
        val make = {
            made++
            null
        }
        loop.step(InfoRingFeed.shown(InfoRingFeed.ringDrawn(infoRing = true, infoTitle = true), dashRingVisible = true), make)
        assertEquals("no frame is even made", 0, made)
        assertEquals(1, gpsStops)
        loop.step(InfoRingFeed.shown(InfoRingFeed.ringDrawn(infoRing = true, infoTitle = false), dashRingVisible = true), make)
        assertEquals("the ring as it was", 1, made)
        assertNull(loop.step(false, make))
    }

    @Test
    fun `the title needs no GPS, so the foreground service does not ask for the GPS card's use`() {
        val all = REauSettings(infoRing = true, infoTitle = false, useLocation = true, infoRingData = RingData.ALL)
        assertTrue(ProjectionService.foregroundNeeds(all, true, true).gps)
        assertFalse(ProjectionService.foregroundNeeds(all.copy(infoTitle = true), true, true).gps)
        assertFalse("and so with the new defaults (21/11/2026)", ProjectionService.foregroundNeeds(REauSettings(), true, true).gps)
    }

    @Test
    fun `the switch is the first row of the ring's choices, applied at once, with its texts in the three languages`() {
        val panel = source("ui/FitPanel.kt")
        assertTrue(panel, "stringResource(R.string.info_title_title), s.infoTitle, { change(s.copy(infoTitle = it)) }," in panel)
        assertTrue(panel, "\"info_title\", sub = stringResource(R.string.info_title_sub)," in panel)
        assertTrue(panel, "Info(stringResource(R.string.info_title_title), stringResource(R.string.info_title_info))" in panel)
        assertTrue("the ring's line says it is not drawn", "if (s.infoRing && s.infoTitle) {" in panel && "R.string.info_title_replaces_ring" in panel)
        assertEquals("Info in the title (instead of the ring)", string("values", "info_title_title"))
        assertEquals("Informações no título (no lugar do anel)", string("values-pt", "info_title_title"))
        assertEquals("Información en el título (en lugar del anillo)", string("values-es", "info_title_title"))
        for (folder in listOf("values", "values-pt", "values-es")) {
            for (name in listOf("info_title_sub", "info_title_info", "info_title_replaces_ring")) assertTrue("$folder $name", string(folder, name).isNotBlank())
            assertTrue("$folder: says the 500 m", "500 m" in string(folder, "info_title_info"))
        }
    }

    @Test
    fun `the running projection takes the option when it starts and at each change, and the ring's loop leaves the ring out`() {
        val live = source("service/LiveSides.kt")
        assertTrue(live, "session.infoTitle.enabled = setup.settings.infoRing && setup.settings.infoTitle" in live)
        assertTrue(live, "session.infoTitle.source = { tuned?.let(setup.infoTitle) }" in live)
        val retune = live.substringAfter("if (LiveTuning.Step.DASH_EXTRAS in steps) {")
            .substringBefore("if (LiveTuning.Step.DASH_EXTRAS in steps && settings.weatherWanted")
        assertTrue(retune, "session.infoTitle.enabled = settings.infoRing && settings.infoTitle" in retune)
        assertTrue(live, "InfoRingFeed.ringDrawn(tuned?.infoRing == true, tuned?.infoTitle == true)" in live)
        assertTrue(live, "InfoRingFeed.shown(ringWanted, session.infoVisible)" in live && "InfoRingFeed.pollMs(ringWanted)" in live)
        assertTrue(live, "infoTitle = { now -> InfoRingFeed.titleSource(context, now.useLocation, now.infoRingOptions) }," in live)
    }

    @Test
    fun `the card's title asks the info title after the echo, with the distance to the maneuver while guiding and none without a route`() {
        val session = source("dash/DashSession.kt")
        val asked = "val echoed = echoNow ?: infoTitle.page(n?.let { DashCommands.navMeters(it.primaryDist, it.primaryUnit) }, titlePagesShown)"
        assertTrue(session, asked in session)
        assertTrue("the echo's moment stops the info's clock", "val echoNow = echo.current()\n        if (echoNow != null) infoTitle.hold()\n" in session)
        assertTrue("the echo is asked first, and the street and REau stay behind it", "echoed ?: n.road.ifBlank { destinationName }" in session)
        assertTrue(session, "echoed ?: destinationName, projectionOn" in session)
    }

    @Test
    fun `the feed asks for the pages of the ring's options and for the phone's battery only`() {
        val feed = source("service/InfoRingFeed.kt")
        val title = feed.substringAfter("fun titleSource(").substringBefore("/** Whether the GPS card may be listened to")
        assertTrue(title, "InfoRing.titlePages(" in title && "options = options," in title)
        assertTrue(title, "TitleSource(" in title && "options.carousel, options.periodMs" in title)
        assertTrue(title, "phone = PhoneReadings(battery = PhoneReader.battery(context).takeIf { RingData.shown(options.data, RingData.BATTERY) })," in title)
        assertFalse("the signal and the GPS are not read for a title", "phone(" in title || "GpsWatch" in title || "PhoneReader.link" in title)
    }
}
