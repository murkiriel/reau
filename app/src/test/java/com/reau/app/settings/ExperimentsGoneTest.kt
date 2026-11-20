package com.reau.app.settings

import com.reau.app.data.SolarClock
import com.reau.app.service.DayNight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * The experimental switches went on 20/11/2026 (the logs of 10 to 20/11 showed none of them acting, and one making the freeze last longer): the
 * settings, the store, the ride log, the screen, the texts and the code behind them are gone, and each behaviour stayed as it was with the switch off.
 * "Anunciar como moto" (AnnounceMotorcycleTest) and the wake of Android Auto (AaWakeTest, always on) are the two that stayed, with no switch to speak of.
 * Their old keys lie in the phones' files and are ignored (AnnounceMotorcycleTest).
 */
class ExperimentsGoneTest {
    private val removed = listOf(
        "RtpPacing", "DebugDscp", "AdaptiveBitrate", "WifiBind", "StartGuard", "EncoderWatch", "FocusGap", "NightMemory", "WeatherAge", "HeatCeiling",
        "GpsSpeed", "RecalcInactive", "ColorMatrix", "ProtocolSix", "TolerantRx", "WakeAa", "EncoderLowLatency", "Motorcycle",
    ).map { "experiment$it" }

    private fun mainSources(): List<File> =
        listOf("main", "debug", "release").flatMap { File("src/$it").walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

    private fun text(path: String) = File("src/main/java/com/reau/app/$path").readText()

    // ── the settings, the store, the log ─────────────────────────────────────────────────────────────────

    @Test
    fun `the settings have no experimental field, and sanitized has nothing to say about one`() {
        val names = REauSettings::class.java.declaredFields.map { it.name }
        for (gone in removed) assertFalse(gone, gone in names)
        assertTrue("announceMotorcycle" in names)
        assertEquals(emptyList<String>(), names.filter { it.startsWith("experiment") })
        val sanitized = text("settings/REauSettings.kt").substringAfter("fun sanitized(").substringBefore("private fun FitParams.within")
        assertFalse(sanitized.contains("xperiment"))
        for (debug in listOf(true, false)) {
            val s = REauSettings().sanitized(debug)
            assertTrue("announceMotorcycle" in s.fieldsOf(s.toString()).keys)
            assertEquals(emptyList<String>(), s.fieldsOf(s.toString()).keys.filter { it.startsWith("experiment") })
        }
    }

    @Test
    fun `the store neither reads nor writes an experimental key`() {
        val store = text("settings/SettingsStore.kt")
        assertEquals("no constant names an experiment's key", emptySet<String>(), Regex("""KEY_EXPERIMENT_[A-Z0-9_]+""").findAll(store).map { it.value }.toSet())
        assertFalse(store.contains("getBoolean(KEY_EXPERIMENT"))
    }

    @Test
    fun `the ride log says no experiment - no line of them, in the settings line, the start of the app, the projection or the report`() {
        assertEquals(emptyList<String>(), SettingsInUse.LOGGED.filter { it.startsWith("experiment") })
        assertFalse("experiment" in SettingsInUse.line(REauSettings()))
        for (file in listOf("settings/SettingsInUse.kt", "REauApp.kt", "service/ProjectionService.kt", "util/DiagnosticsExport.kt", "util/MaskedZip.kt")) {
            assertFalse("$file still says connection experiments", text(file).contains("connection experiments") || text(file).contains("experimentsLine"))
        }
        assertFalse("the start guard is gone from the app's start and the projection's", text("REauApp.kt").contains("Guard") || text("service/ProjectionService.kt").contains("Guard"))
    }

    // ── the code ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no source names an experimental field, and the classes that served only them are gone`() {
        for (f in mainSources()) {
            val body = f.readText()
            assertFalse("${f.name} names an experimental field", Regex("""experiment[A-Z]""").containsMatchIn(body))
        }
        val files = mainSources().map { it.name }.toSet()
        for (gone in listOf(
            "RtpPacing.kt", "BitrateAdapter.kt", "ExperimentsGuard.kt", "EncoderMuteWatch.kt", "HeatCeiling.kt", "SpeedGps.kt", "RecalcHold.kt", "ColorMatrix.kt",
            "ProtocolAsk.kt", "RxQuiet.kt", "VendorLowLatency.kt", "ProfileSpeed.kt", "SettingsExperimental.kt",
        )) assertFalse(gone, gone in files)
        val all = mainSources().joinToString("\n") { it.readText() }
        for (gone in listOf("BindRetry", "ControlGap", "EXPERIMENT_TARGET_BPS", "EXPERIMENT_FLOOR_BPS", "effectiveStreamBitrateBps", "GpsOwner.SPEED", "priorityMarks")) {
            assertFalse(gone, all.contains(gone))
        }
    }

    @Test
    fun `each behaviour is wired as it was with the switch off`() {
        val live = text("service/LiveSides.kt")
        assertEquals("the projection starts with the rider's settings and a new stream takes them up again", 2, Regex("""\.withRider\(settings\)""").findAll(live).count())
        assertTrue("sockets with the build's own marks, no gap", live.contains("dashSocket = { DashSocket(it) },"))
        assertTrue("weather: three hours", live.contains("weatherProvider = { if (tuned?.dashWeather == true) setup.weather() else null },"))
        assertTrue("thermal: Android's own", live.contains("private val thermalStatus: () -> Int = { setup.thermalStatus() }"))
        assertTrue("speed profile: the route's estimate", live.contains("speedKmh = navSample") || live.contains("speedKmh = navSpeed.kmhAt(android.os.SystemClock.elapsedRealtime()),"))
        assertTrue("guidance: each one goes straight to the dash", live.contains("val guidance: (com.reau.app.aa.NavGuidance) -> Unit = show"))
        assertTrue("day and night: every look starts as day", live.contains("DayNight.isNightNow(settings.mapTheme, SunPlace.saved(context, settings.useLocation))"))
        val session = text("dash/DashSession.kt")
        assertTrue("4 s of silence end the session", session.contains("rxSilenceFails(_state.value, lastRxMs, MonotonicClock.nowMs(), timing.rxWatchdogMs, heartbeatsSinceRx)"))
        assertTrue("a refused bind goes straight to the holder of the network", session.contains("socketFactory(network)"))
        val loop = text("stream/VideoLoop.kt")
        assertTrue(loop.contains("bitrateBps = rider.streamBitrateBps,"))
        assertTrue(loop.contains("created.prepare(fps, bitrateBps, encoderKeyframeSec, settings.bitrateMode)"))
        assertTrue("the packets go as they are made", text("stream/RtpOutput.kt").contains("packetsMade++\n        sendRtp(packet)"))
        assertTrue("the focus comes back after 100 ms", text("aa/AapTransport.kt").contains("val gapMs = FocusGap.DEFAULT_MS"))
        assertTrue("asked for 1.7 (1.2) only", text("aa/AaReceiver.kt").contains("AapTransport(context, protocolMinor = config.protocolMinor, versionRequests = config.versionRequests)"))
    }

    @Test
    fun `the debug build's mute-encoder fault went with the watch it tested, the other two stay`() {
        val debug = File("src/debug/java/com/reau/app/devtools/FaultInjection.kt").readText()
        assertTrue(debug.contains("enum class Fault { DECODER_STALL, FOCUS_CYCLE }"))
        assertFalse(debug.contains("ENCODER_MUTE") || debug.contains("muteEncoderOutput"))
        assertFalse(File("src/debug/java/com/reau/app/devtools/DebugTriggers.kt").readText().contains("ENCODER_MUTE"))
    }

    // ── the screen and the texts ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the settings screen has no experimental section, so none is left empty`() {
        val screen = text("ui/SettingsScreen.kt")
        for (group in listOf("ConnectionExperimentsGroup", "VideoExperimentsGroup", "DashExperimentsGroup")) assertFalse(group, screen.contains(group))
        assertFalse(File("src/main/java/com/reau/app/ui/SettingsExperimental.kt").exists())
        val ui = File("src/main/java/com/reau/app/ui").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(Regex("""group_(connection|video|dash)_experiments""").containsMatchIn(ui))
        assertFalse(Regex("""\"exp_[a-z_]+\"""").containsMatchIn(ui))
        // every group the screen draws has rows: none is given an empty list
        assertFalse(Regex("""SettingsGroup\([^\n]*(emptyList|listOf\(\))""").containsMatchIn(ui))
        assertTrue("the groups left, in the order of the screen",
            listOf("MapGroup", "OnDashGroup", "JoystickGroup", "VideoProfilesGroup", "CardGroup", "AndroidAutoGroup", "DebugGroup", "UpdateGroup")
                .map { screen.indexOf("$it(") }.let { at -> at.all { it >= 0 } && at == at.sorted() },
        )
    }

    @Test
    fun `no string of an experiment is left in any language, and the three still agree`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val xml = File("src/main/res/$folder/strings.xml").readText()
            assertFalse("$folder", Regex("""name="exp_""").containsMatchIn(xml))
            assertFalse("$folder", xml.contains("_experiments\""))
        }
    }

    // ── what the removed switches left behind, as it behaves ────────────────────────────────────────────

    private val saoPaulo = DayNight.Place(-23.55, -46.63)
    private val offset = -3 * 3_600_000L

    private fun utcMs(hour: Int, minute: Int = 0): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(2026, 9, 3, hour, minute, 0)
    }.timeInMillis

    @Test
    fun `day and night is asked with no memory, as before the switch - the band between them is day, a forced theme is its own`() {
        val dawn = (0 until 300).map { utcMs(7) + it * 60_000L }.first { SolarClock.elevationDeg(saoPaulo.lat, saoPaulo.lng, it) in -5.0..-1.0 }
        val midnight = utcMs(3)
        val noon = utcMs(15)
        for (ms in listOf(midnight, dawn, noon)) {
            val fresh = DayNight.Clock(com.reau.app.settings.MapTheme.AUTO, { saoPaulo }, { offset }, { ms }).isNight()
            assertEquals(fresh, DayNight.isNight(com.reau.app.settings.MapTheme.AUTO, ms, saoPaulo, offset, wasNight = false))
        }
        assertFalse("the band, whatever the look before said: day", DayNight.isNight(com.reau.app.settings.MapTheme.AUTO, dawn, saoPaulo, offset, wasNight = false))
        assertTrue(DayNight.isNightNow(com.reau.app.settings.MapTheme.NIGHT, saoPaulo))
        assertFalse(DayNight.isNightNow(com.reau.app.settings.MapTheme.DAY, saoPaulo))
        assertFalse(DayNight.isNightNow(com.reau.app.settings.MapTheme.AUTO, saoPaulo, nowMs = noon))
        assertFalse("the band at dawn is day for the look the projection makes", DayNight.isNightNow(com.reau.app.settings.MapTheme.AUTO, saoPaulo, nowMs = dawn))
        assertTrue("and a night in the dark is night", DayNight.isNightNow(com.reau.app.settings.MapTheme.AUTO, saoPaulo, nowMs = midnight))
    }
}
