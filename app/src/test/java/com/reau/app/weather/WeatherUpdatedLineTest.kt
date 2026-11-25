package com.reau.app.weather

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ride log's line for a new forecast says what Open-Meteo gave (the weather code and the rain now) and what REau tells the dash from it
 * (the `06 08` icon, the temperature, the info ring's rain page), once per update and never the place (pendency 38: on 24/11/2026 the dash said
 * "cloudy" and the log could not say whether that was what the service had sent).
 */
class WeatherUpdatedLineTest {
    private val nowMs = LocalDateTime.parse("2026-10-26T12:32:00").toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun current(code: Int?, mm: Double?, temp: Double = 23.6) =
        CurrentWeather(temp, null, null, mm, code, null, null, null, null, null, null)

    private fun quarter(at: String, mm: Double) = MinutelyWeather("2026-10-26T$at", mm, null, null)

    private fun snapshot(current: CurrentWeather?, minutely: List<MinutelyWeather> = emptyList()) = WeatherKeeper.Snapshot(
        PointForecast(current, emptyList(), emptyList(), minutely, utcOffsetSeconds = 0), nowMs, monoAtMs = 1L,
    )

    private fun line(snapshot: WeatherKeeper.Snapshot) = WeatherUpdatedLine.of(snapshot, realQuarters = true, nowMs = nowMs, monoNowMs = 1L)

    @Test
    fun `a cloudy dry reading says the code, no rain and the cloudy icon sent`() {
        assertEquals(
            "weather updated: 0 hours, 0 days; current code=3 rain=0.0mm; dash icon=0x01 (CLOUDY) temp=24C; ring rain page=none",
            line(snapshot(current(3, 0.0))),
        )
    }

    @Test
    fun `the rain now reaches the log with the page the ring would draw`() {
        val minutely = listOf(quarter("12:30", 0.5), quarter("12:45", 0.5), quarter("13:00", 0.0))
        val text = line(snapshot(current(61, 1.24), minutely))
        assertTrue(text, "current code=61 rain=1.2mm" in text)
        assertTrue(text, "dash icon=0x03 (RAIN) temp=24C" in text)
        assertTrue(text, text.endsWith("ring rain page=RAIN_NOW(RAIN) until 13 min"))
    }

    @Test
    fun `rain on the way is the soon page with its minutes`() {
        val minutely = listOf(quarter("12:30", 0.0), quarter("12:45", 0.4))
        // The icon of rain that has not begun is not the current code's: drizzle now does not make it a drizzle to come.
        assertTrue(line(snapshot(current(53, 0.0), minutely)).endsWith("ring rain page=RAIN_SOON(RAIN) in 7 min"))
    }

    @Test
    fun `rain falling with a drizzle code is the drizzle icon`() {
        val minutely = listOf(quarter("12:30", 0.2), quarter("12:45", 0.0))
        assertTrue(line(snapshot(current(53, 0.2), minutely)).endsWith("ring rain page=RAIN_NOW(DRIZZLE) until 7 min"))
    }

    @Test
    fun `a place with no real quarters only counts the rain the hour makes likely`() {
        val minutely = listOf(quarter("12:30", 0.0), quarter("12:45", 0.4))
        val unlikely = PointForecast(
            current(3, 0.0), listOf(HourlyWeather("2026-10-26T12:00", 20.0, 0, 3, null)), emptyList(), minutely, utcOffsetSeconds = 0,
        )
        val snapshot = WeatherKeeper.Snapshot(unlikely, nowMs, monoAtMs = 1L)
        assertTrue(WeatherUpdatedLine.of(snapshot, realQuarters = false, nowMs = nowMs, monoNowMs = 1L).endsWith("ring rain page=none"))
        assertTrue(WeatherUpdatedLine.of(snapshot, realQuarters = true, nowMs = nowMs, monoNowMs = 1L).endsWith("RAIN_SOON(RAIN) in 7 min"))
    }

    @Test
    fun `a rain that the icon ignores is still told, as the amount and the code`() {
        // The icon follows the code only (the owner's choice of 25/11/2026): a dry code with rain in mm shows both in the log.
        val text = line(snapshot(current(3, 2.4)))
        assertTrue(text, "current code=3 rain=2.4mm" in text && "icon=0x01 (CLOUDY)" in text)
    }

    @Test
    fun `a code or a rain not said, and a code the table lacks, are said as they are`() {
        val none = line(snapshot(current(null, null)))
        assertTrue(none, "current code=not said rain=not said; dash icon=0xFF (UNKNOWN) temp=24C" in none)
        val strange = line(snapshot(current(123, 0.0)))
        assertTrue(strange, "current code=123" in strange && "icon=0xFF (UNKNOWN)" in strange)
    }

    @Test
    fun `with no current reading, or no usable temperature, the dash gets nothing and the line says so`() {
        assertTrue(line(snapshot(null)), "current code=not said rain=not said; dash nothing (no usable reading); ring rain page=" in line(snapshot(null)))
        assertTrue(line(snapshot(current(3, 0.0, Double.NaN))).contains("dash nothing (no usable reading)"))
    }

    @Test
    fun `with no local time the ring's page is said unknown`() {
        val noClock = WeatherKeeper.Snapshot(PointForecast(current(3, 0.0), emptyList(), emptyList()), nowMs, monoAtMs = 1L)
        assertTrue(line(noClock).endsWith("ring rain page=unknown (no local time)"))
    }

    @Test
    fun `the line has no coordinates and no place`() {
        val text = line(snapshot(current(61, 0.5), listOf(quarter("12:30", 0.5))))
        assertFalse(text, Regex("""-?\d+\.\d{3,}""").containsMatchIn(text))
        assertFalse(text, "lat" in text.lowercase() || "lng" in text.lowercase() || "lon" in text.lowercase())
    }

    private val source = File("src/main/java/com/reau/app/weather/WeatherKeeper.kt").readText()
    private val lineSource = File("src/main/java/com/reau/app/weather/WeatherUpdatedLine.kt").readText()

    @Test
    fun `the function that writes the line never reads the place`() {
        val body = lineSource.substringAfter("fun of(").substringBefore("\n    }\n")
        assertTrue("the function was found", "weather updated" in body)
        for (word in listOf("place", "lat", "lng", "Place")) assertFalse("$word in WeatherUpdatedLine.of", word in body.replace("realQuarters", ""))
    }

    @Test
    fun `an update says it once, with this line`() {
        assertEquals("one log line for an update", 1, Regex("""WeatherUpdatedLine\.of\(""").findAll(source).count())
        assertTrue("the line goes to the ride log", "RideDiagnostics.log(\"weather\", line)" in source)
        assertTrue("it tells whether the place has real quarters", "WeatherUpdatedLine.of(fresh, InfoRing.hasRealQuarters(place)," in source)
        assertFalse("no other line says an update", Regex(""""weather updated""").findAll(source).count() > 1)
    }
}
