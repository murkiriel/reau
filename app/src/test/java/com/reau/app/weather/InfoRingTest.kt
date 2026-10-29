package com.reau.app.weather

import com.reau.app.service.DayNight
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the dash's info ring says: the pages the forecast gives rise to, their urgency, their words and the order they take turns in.
 * The place's clock is 2026-10-26 12:07 (offset 0), so the quarter ending 12:00 is the one just past and the next ends at 12:15.
 */
class InfoRingTest {
    private val words = RingWords(
        "em", "min", "até", "chovendo", "sem chuva", "trovoada", "UV", "IQAr",
        skyClear = "limpo", skyPartly = "nuvens", skyCloudy = "nublado", skyFog = "neblina", skyDrizzle = "garoa", skyRain = "chuva", skySnow = "neve",
    )
    private val now = LocalDateTime.parse("2026-10-26T12:07")
    private val nowMs = now.toEpochSecond(ZoneOffset.UTC) * 1000

    private fun current(code: Int? = 1, gusts: Double? = null, wind: Double? = 8.0, isDay: Boolean? = true, rainMm: Double? = 0.0) = CurrentWeather(
        24.0, null, null, rainMm, code, wind, null, gusts, null, null, isDay, timeIso = "2026-10-26T12:00",
    )

    /** A quarter ending at [end] (e.g. "12:15") that had [mm] of rain, with [code] and [gust]. */
    private fun q(end: String, mm: Double = 0.0, code: Int? = null, gust: Double? = null) = MinutelyWeather("2026-10-26T$end", mm, code, gust)

    private val dry = listOf(q("12:00"), q("12:15"), q("12:30"), q("12:45"), q("13:00"), q("13:15"), q("13:30"), q("13:45"))

    private fun day(sunrise: String? = null, sunset: String? = null, date: String = "2026-10-26") =
        DailyWeather(date, 31.0, 22.0, null, null, sunrise, sunset, null, null, null, null)

    private fun forecast(
        minutely: List<MinutelyWeather> = dry,
        current: CurrentWeather? = current(),
        hourly: List<HourlyWeather> = emptyList(),
        daily: List<DailyWeather> = listOf(day()),
        offset: Int? = 0,
    ) = PointForecast(current, hourly, daily, minutely, offset)

    private fun pages(
        f: PointForecast = forecast(),
        air: AirQuality? = null,
        real: Boolean = true,
        units: WeatherUnits = WeatherUnits(),
    ) = InfoRing.pages(f, air, now, real, units, words)

    private fun kinds(p: List<RingPage>) = p.map { it.kind }

    private fun line(page: RingPage) = page.line.joinToString(" ") { it.text }

    private fun page(kind: RingKind, p: List<RingPage>) = p.first { it.kind == kind }

    private fun hour(at: String, uv: Double? = null, probability: Int? = null) =
        HourlyWeather("2026-10-26T$at", 25.0, probability, null, uv)

    // The rain.

    @Test
    fun `a dry day says the sky with no rain and the day's temperatures`() {
        val p = pages()
        assertEquals(listOf(RingKind.SKY, RingKind.TEMPS), kinds(p))
        assertEquals(RingIcon.PARTLY_CLOUDY, p[0].icon)
        assertEquals("sem chuva", line(p[0]))
        assertEquals(RingIcon.THERMOMETER, p[1].icon)
        assertEquals("31/22°", line(p[1]))
        assertEquals(listOf(4, 4), p.map { it.level })
    }

    @Test
    fun `rain on the way within half an hour is level one, with the minutes of the earliest it can start`() {
        val p = pages(forecast(minutely = dry.map { if (it.timeIso.endsWith("12:45")) q("12:45", 0.4) else it }))
        val rain = page(RingKind.RAIN_SOON, p)
        assertEquals(1, rain.level)
        assertEquals(RingTone.RAIN, rain.tone)
        assertEquals(RingIcon.RAIN, rain.icon)
        // The quarter ending 12:45 began at 12:30: 23 minutes from 12:07.
        assertEquals("em 23 min", line(rain))
        assertEquals(listOf(false, true, false), rain.line.map { it.big })
    }

    @Test
    fun `rain between half an hour and an hour away is level two`() {
        val p = pages(forecast(minutely = dry.map { if (it.timeIso.endsWith("13:15")) q("13:15", 0.4) else it }))
        val rain = page(RingKind.RAIN_SOON, p)
        assertEquals(2, rain.level)
        assertEquals("em 53 min", line(rain))
    }

    @Test
    fun `rain more than an hour away is not said`() {
        val p = pages(forecast(minutely = dry.map { if (it.timeIso.endsWith("13:45")) q("13:45", 0.4) else it }))
        assertNull(p.firstOrNull { it.kind == RingKind.RAIN_SOON })
        assertEquals("sem chuva", line(page(RingKind.SKY, p)))
    }

    @Test
    fun `rain that began in the quarter now is said as soon, by half of what is left of it`() {
        val p = pages(forecast(minutely = dry.map { if (it.timeIso.endsWith("12:15")) q("12:15", 0.4) else it }))
        // Ends in 8 minutes: half, rounded up to a whole minute.
        assertEquals("em 4 min", line(page(RingKind.RAIN_SOON, p)))
    }

    @Test
    fun `rain falling now says until when it stops, or just that it rains`() {
        val stops = pages(forecast(minutely = dry.map { if (it.timeIso.endsWith("12:00")) q("12:00", 0.5) else it }))
        val now = page(RingKind.RAIN_NOW, stops)
        assertEquals(2, now.level)
        assertEquals("até 4 min", line(now))
        val goesOn = pages(forecast(minutely = dry.map { q(it.timeIso.substringAfter('T'), 0.5) }))
        assertEquals("chovendo", line(page(RingKind.RAIN_NOW, goesOn)))
    }

    @Test
    fun `a day with rain going on or coming has no sky page saying dry`() {
        val p = pages(forecast(minutely = dry.map { q(it.timeIso.substringAfter('T'), 0.5) }, current = current(code = 61)))
        assertEquals("rain on the sky card is said in a word, never a bare icon", "chuva", line(page(RingKind.SKY, p)))
        assertEquals(RingIcon.RAIN, page(RingKind.SKY, p).icon)
    }

    @Test
    fun `the sky card always has a word, rain due and a cloudy sky, each icon its own word, and a dry sky keeps saying so`() {
        // Rain due within the hour and a cloudy sky now: the card the rider saw as a bare cloud.
        val due = pages(forecast(minutely = listOf(q("12:15"), q("12:30"), q("12:45", 0.6), q("13:00", 0.6), q("13:15", 0.6)), current = current(code = 3)))
        assertTrue(RingKind.RAIN_SOON in kinds(due))
        assertEquals("nublado", line(page(RingKind.SKY, due)))
        val byCode = mapOf(0 to "limpo", 2 to "nuvens", 3 to "nublado", 45 to "neblina", 51 to "garoa", 61 to "chuva", 71 to "neve", 95 to "trovoada")
        for ((code, word) in byCode) {
            // No quarters of an hour at all: nothing says it is dry, so the card says the sky.
            val sky = page(RingKind.SKY, pages(forecast(minutely = emptyList(), current = current(code = code))))
            assertEquals("code $code", word, line(sky))
        }
        assertEquals("a night's clear sky is a clear sky", "limpo", line(page(RingKind.SKY, pages(forecast(minutely = emptyList(), current = current(code = 0, isDay = false))))))
        assertEquals("a dry hour still says it is dry", "sem chuva", line(page(RingKind.SKY, pages(forecast(current = current(code = 3))))))
    }

    @Test
    fun `the rain's icon follows how hard and what kind it is`() {
        fun icon(mm: Double, code: Int? = null) =
            page(RingKind.RAIN_SOON, pages(forecast(minutely = dry.map { if (it.timeIso.endsWith("12:45")) q("12:45", mm, code) else it }))).icon
        assertEquals(RingIcon.RAIN, icon(0.4))
        // 2.5 mm in a quarter is 10 mm/h, over the 7.6 that is heavy.
        assertEquals(RingIcon.HEAVY_RAIN, icon(2.5))
        assertEquals(RingIcon.RAIN, icon(1.9))
        assertEquals(RingIcon.DRIZZLE, icon(0.2, code = 53))
        assertEquals(RingIcon.SNOW, icon(0.4, code = 73))
    }

    @Test
    fun `where the quarters are only the hourly forecast spread, rain counts only if the hour is likely and the minutes are rounded to tens`() {
        val wet = dry.map { if (it.timeIso.endsWith("12:45")) q("12:45", 0.4) else it }
        val unlikely = pages(forecast(minutely = wet, hourly = listOf(hour("12:00", probability = 10))), real = false)
        assertNull(unlikely.firstOrNull { it.kind == RingKind.RAIN_SOON })
        val likely = pages(forecast(minutely = wet, hourly = listOf(hour("12:00", probability = 60))), real = false)
        assertEquals("em ~20 min", line(page(RingKind.RAIN_SOON, likely)))
        val unsaid = pages(forecast(minutely = wet), real = false)
        assertNotNull("no probability in the answer: the rain is taken as it is", unsaid.firstOrNull { it.kind == RingKind.RAIN_SOON })
        assertEquals("em ~10 min", line(page(RingKind.RAIN_SOON, pages(forecast(minutely = dry.map { if (it.timeIso.endsWith("12:15")) q("12:15", 0.4) else it }), real = false))))
    }

    @Test
    fun `no quarters at all and a dry reading say nothing of rain`() {
        val p = pages(forecast(minutely = emptyList()))
        assertEquals(listOf(RingKind.SKY, RingKind.TEMPS), kinds(p))
        assertEquals("no 'dry' without the forecast that says it: the sky in a word instead", "nuvens", line(p[0]))
    }

    // The wind.

    @Test
    fun `the gust says its level at 40, 55 and 70 km per hour and nothing below`() {
        fun tone(g: Double) = InfoRing.windTone(g)
        assertNull(tone(39.9))
        assertEquals(RingTone.ATTENTION, tone(40.0))
        assertEquals(RingTone.ATTENTION, tone(54.9))
        assertEquals(RingTone.ALERT, tone(55.0))
        assertEquals(RingTone.DANGER, tone(70.0))
    }

    @Test
    fun `wind pages say the gust with its level, danger and alert first and attention with the rest`() {
        fun wind(g: Double) = pages(forecast(current = current(gusts = g)))
        assertNull(wind(39.0).firstOrNull { it.kind in setOf(RingKind.WIND_ATTENTION, RingKind.WIND_ALERT, RingKind.WIND_DANGER) })
        val attention = page(RingKind.WIND_ATTENTION, wind(45.4))
        assertEquals(2, attention.level)
        assertEquals(RingTone.ATTENTION, attention.tone)
        assertEquals("45 km/h", line(attention))
        assertEquals(1, page(RingKind.WIND_ALERT, wind(60.0)).level)
        val danger = wind(81.0)
        assertEquals(RingKind.WIND_DANGER, danger.first().kind)
        assertEquals(RingTone.DANGER, danger.first().tone)
        assertEquals(RingIcon.WIND, danger.first().icon)
    }

    @Test
    fun `the strongest gust of the next hour counts, a later one does not, and with no gust said the steady wind does`() {
        val soon = dry.map { if (it.timeIso.endsWith("12:45")) q("12:45", gust = 62.0) else it }
        assertEquals("62 km/h", line(page(RingKind.WIND_ALERT, pages(forecast(minutely = soon)))))
        val late = dry.map { if (it.timeIso.endsWith("13:45")) q("13:45", gust = 90.0) else it }
        assertNull(pages(forecast(minutely = late)).firstOrNull { it.kind == RingKind.WIND_DANGER })
        val steady = pages(forecast(current = current(gusts = null, wind = 43.0)))
        assertEquals("43 km/h", line(page(RingKind.WIND_ATTENTION, steady)))
    }

    @Test
    fun `the wind is written in miles per hour for a dash that counts in miles`() {
        val p = pages(forecast(current = current(gusts = 66.0)), units = WeatherUnits(miles = true))
        assertEquals("41 mph", line(page(RingKind.WIND_ALERT, p)))
        assertEquals(listOf(true, false), page(RingKind.WIND_ALERT, p).line.map { it.big })
    }

    // Storms.

    @Test
    fun `a storm now is level one danger with its word, one within the hour says the minutes`() {
        val now = page(RingKind.THUNDER, pages(forecast(current = current(code = 95))))
        assertEquals(1, now.level)
        assertEquals(RingTone.DANGER, now.tone)
        assertEquals(RingIcon.THUNDER, now.icon)
        assertEquals("trovoada", line(now))
        val soon = pages(forecast(minutely = dry.map { if (it.timeIso.endsWith("12:45")) q("12:45", code = 96) else it }))
        assertEquals("em 38 min", line(page(RingKind.THUNDER, soon)))
        val far = pages(forecast(minutely = dry.map { if (it.timeIso.endsWith("13:45")) q("13:45", code = 99) else it }))
        assertNull(far.firstOrNull { it.kind == RingKind.THUNDER })
    }

    // UV and air.

    @Test
    fun `the UV of this hour is said from 6, with the colour of its band`() {
        fun uv(v: Double) = pages(forecast(hourly = listOf(hour("12:00", uv = v)))).firstOrNull { it.kind == RingKind.UV }
        assertNull(uv(5.9))
        assertEquals(RingTone.UV_HIGH, uv(6.0)?.tone)
        assertEquals("UV 7", line(uv(7.4)!!))
        assertEquals(RingTone.UV_VERY_HIGH, uv(8.2)?.tone)
        assertEquals(RingTone.UV_EXTREME, uv(11.0)?.tone)
        assertEquals(3, uv(7.0)?.level)
        assertEquals(RingIcon.UV, uv(7.0)?.icon)
    }

    @Test
    fun `the UV of another hour does not count`() {
        assertNull(pages(forecast(hourly = listOf(hour("13:00", uv = 9.0)))).firstOrNull { it.kind == RingKind.UV })
    }

    @Test
    fun `the air is said above 100 with the colour of its band`() {
        fun air(aqi: Int?) = pages(air = aqi?.let { AirQuality(it, null, null, null) }).firstOrNull { it.kind == RingKind.AIR }
        assertNull(air(null))
        assertNull(air(100))
        assertEquals(RingTone.AIR_SENSITIVE, air(101)?.tone)
        assertEquals(RingTone.AIR_UNHEALTHY, air(160)?.tone)
        assertEquals(RingTone.AIR_VERY_UNHEALTHY, air(250)?.tone)
        assertEquals(RingTone.AIR_HAZARDOUS, air(350)?.tone)
        assertEquals("IQAr 160", line(air(160)!!))
        assertEquals(3, air(160)?.level)
    }

    // The sun.

    @Test
    fun `the sunset is said in the last hour only`() {
        fun sun(set: String) = pages(forecast(daily = listOf(day(sunset = "2026-10-26T$set")))).firstOrNull { it.kind == RingKind.SUN }
        val near = sun("12:49")
        assertEquals(RingIcon.SUNSET, near?.icon)
        assertEquals("em 42 min", line(near!!))
        assertEquals(2, near.level)
        assertEquals(RingIcon.SUNSET, sun("13:07")?.icon)
        assertNull("61 minutes", sun("13:08"))
        assertNull("it already set", sun("12:00"))
    }

    @Test
    fun `the next sunrise is said in the last hour, even if it is tomorrow's`() {
        val daily = listOf(day(sunrise = "2026-10-26T05:40", sunset = "2026-10-26T18:30"), day(sunrise = "2026-10-27T05:39", date = "2026-10-27"))
        val at = LocalDateTime.parse("2026-10-27T04:50")
        val p = InfoRing.pages(forecast(daily = daily, minutely = emptyList()), null, at, true, WeatherUnits(), words)
        val sun = page(RingKind.SUN, p)
        assertEquals(RingIcon.SUNRISE, sun.icon)
        assertEquals("em 49 min", line(sun))
    }

    // The sky and the temperatures.

    @Test
    fun `the sky page has a night's moon and the temperatures follow the dash's unit`() {
        val night = pages(forecast(current = current(code = 0, isDay = false)))
        assertEquals(RingIcon.CLEAR_NIGHT, page(RingKind.SKY, night).icon)
        assertEquals(RingIcon.PARTLY_CLOUDY_NIGHT, page(RingKind.SKY, pages(forecast(current = current(code = 2, isDay = false)))).icon)
        assertEquals(RingIcon.CLEAR, page(RingKind.SKY, pages(forecast(current = current(code = 0)))).icon)
        assertEquals(RingIcon.CLOUDY, page(RingKind.SKY, pages(forecast(current = current(code = 3)))).icon)
        assertEquals(RingIcon.FOG, page(RingKind.SKY, pages(forecast(current = current(code = 45)))).icon)
        assertEquals("88/72°", line(page(RingKind.TEMPS, pages(units = WeatherUnits(fahrenheit = true)))))
    }

    @Test
    fun `a sky the table does not know is left out, and the temperatures are today's row or else the first`() {
        val p = pages(forecast(current = current(code = 123)))
        assertEquals(listOf(RingKind.TEMPS), kinds(p))
        val other = DailyWeather("2026-10-25", 40.0, 30.0, null, null, null, null, null, null, null, null)
        assertEquals("31/22°", line(page(RingKind.TEMPS, pages(forecast(daily = listOf(other, day()))))))
        assertEquals("40/30°", line(page(RingKind.TEMPS, pages(forecast(daily = listOf(other))))))
        assertEquals("24°", line(page(RingKind.TEMPS, pages(forecast(daily = emptyList())))))
    }

    @Test
    fun `no current reading gives no page at all`() {
        assertTrue(pages(forecast(current = null)).isEmpty())
    }

    @Test
    fun `the pages come most urgent first`() {
        val p = pages(
            forecast(
                current = current(gusts = 75.0),
                minutely = dry.map { if (it.timeIso.endsWith("12:45")) q("12:45", 0.4, gust = 75.0) else it },
                hourly = listOf(hour("12:00", uv = 9.0)),
                daily = listOf(day(sunset = "2026-10-26T12:49")),
            ),
            air = AirQuality(180, null, null, null),
        )
        assertEquals(
            listOf(RingKind.WIND_DANGER, RingKind.RAIN_SOON, RingKind.SUN, RingKind.UV, RingKind.AIR, RingKind.SKY, RingKind.TEMPS),
            kinds(p),
        )
        assertEquals(listOf(1, 1, 2, 3, 3, 4, 4), p.map { it.level })
    }

    // Where the quarters are real.

    @Test
    fun `real quarters are the model's in North America and Central Europe only`() {
        fun real(lat: Double, lng: Double) = InfoRing.hasRealQuarters(DayNight.Place(lat, lng))
        assertTrue(real(40.7, -74.0))
        assertTrue(real(48.1, 11.6))
        assertTrue(real(48.9, 2.3))
        assertEquals(false, real(-23.5, -46.6))
        assertEquals(false, real(25.2, 55.3))
        assertEquals(false, real(38.7, -9.1))
        assertEquals(false, InfoRing.hasRealQuarters(null))
    }

    // The frame: the age, the clock and the turns.

    private fun snapshot(f: PointForecast = forecast(), at: Long = nowMs) = WeatherKeeper.Snapshot(f, at, air = null)

    private fun frame(snapshot: WeatherKeeper.Snapshot?, wall: Long = nowMs, rotation: InfoRingRotation = InfoRingRotation(), at: Long = 0L, night: Boolean = false) =
        InfoRing.frame(snapshot, rotation, wall, 0L, DayNight.Place(-23.5, -46.6), night, WeatherUnits(), words, at)

    @Test
    fun `no weather, or weather older than three hours, is no ring`() {
        assertNull(frame(null))
        assertNotNull(frame(snapshot(), wall = nowMs + WeatherKeeper.USABLE_MS))
        assertNull(frame(snapshot(), wall = nowMs + WeatherKeeper.USABLE_MS + 1))
    }

    @Test
    fun `a forecast older than half an hour is marked old`() {
        val halfHour = 30 * 60_000L
        assertEquals(false, frame(snapshot(), wall = nowMs + halfHour)?.stale)
        assertEquals(true, frame(snapshot(), wall = nowMs + halfHour + 1)?.stale)
        assertEquals(false, frame(snapshot(), wall = nowMs + 10 * 60_000L)?.stale)
    }

    @Test
    fun `the frame carries the map's night for the palette`() {
        assertEquals(true, frame(snapshot(), night = true)?.night)
        assertEquals(false, frame(snapshot(), night = false)?.night)
    }

    @Test
    fun `with no offset in the answer the clock is the reading's time moved on by the answer's age`() {
        val f = forecast(offset = null)
        val snap = WeatherKeeper.Snapshot(f, fetchedAtMs = nowMs, air = null)
        // The reading is valid for 12:00; the answer is 7 minutes old when the clock is 7 minutes after the fetch: 12:07 for the ring.
        assertEquals(now, InfoRing.localNow(snap, nowMs + 7 * 60_000L, 0L))
        assertNull(InfoRing.localNow(WeatherKeeper.Snapshot(forecast(offset = null, current = current().copy(timeIso = null)), nowMs, air = null), nowMs, 0L))
    }

    @Test
    fun `with an offset in the answer the clock is the phone's moved to the place`() {
        val f = forecast(offset = -10_800)
        // 15:07 UTC is 12:07 in a place three hours behind.
        val at = LocalDateTime.parse("2026-10-26T15:07").toEpochSecond(ZoneOffset.UTC) * 1000
        assertEquals(now, InfoRing.localNow(snapshot(f, at), at, 0L))
    }

    // The turns.

    private fun page(kind: RingKind, level: Int, text: String = "") = RingPage(kind, level, RingIcon.CLEAR, RingTone.NONE, listOf(RingText(text, true)))

    @Test
    fun `normal pages take turns seven seconds each, from the most urgent`() {
        val r = InfoRingRotation()
        val pages = listOf(page(RingKind.UV, 3), page(RingKind.SKY, 4), page(RingKind.TEMPS, 4))
        assertEquals(RingKind.UV, r.pick(pages, 1_000)?.kind)
        assertEquals(RingKind.UV, r.pick(pages, 7_999)?.kind)
        assertEquals(RingKind.SKY, r.pick(pages, 8_000)?.kind)
        assertEquals(RingKind.TEMPS, r.pick(pages, 15_000)?.kind)
        assertEquals(RingKind.UV, r.pick(pages, 22_000)?.kind)
    }

    @Test
    fun `danger pages alone take turns, as long as the carousel's time, and a new one shows at once`() {
        val r = InfoRingRotation()
        val normal = listOf(page(RingKind.SKY, 4), page(RingKind.TEMPS, 4))
        assertEquals(RingKind.SKY, r.pick(normal, 0)?.kind)
        val withOne = listOf(page(RingKind.WIND_DANGER, 1)) + normal
        assertEquals("only the danger, and it stays", RingKind.WIND_DANGER, r.pick(withOne, 3_000)?.kind)
        assertEquals(RingKind.WIND_DANGER, r.pick(withOne, 40_000)?.kind)
        val withTwo = listOf(page(RingKind.WIND_DANGER, 1), page(RingKind.THUNDER, 1)) + normal
        assertEquals("a new danger shows at once", RingKind.WIND_DANGER, r.pick(withTwo, 41_000)?.kind)
        assertEquals(RingKind.WIND_DANGER, r.pick(withTwo, 47_999)?.kind)
        assertEquals(RingKind.THUNDER, r.pick(withTwo, 48_000)?.kind)
        assertEquals(RingKind.WIND_DANGER, r.pick(withTwo, 55_000)?.kind)
    }

    @Test
    fun `when the danger is gone the others start again from the top`() {
        val r = InfoRingRotation()
        val normal = listOf(page(RingKind.SKY, 4), page(RingKind.TEMPS, 4))
        r.pick(listOf(page(RingKind.THUNDER, 1)) + normal, 0)
        assertEquals(RingKind.SKY, r.pick(normal, 2_000)?.kind)
        assertEquals(RingKind.TEMPS, r.pick(normal, 9_000)?.kind)
    }

    @Test
    fun `new words in a page already shown do not restart the turns`() {
        val r = InfoRingRotation()
        val first = listOf(page(RingKind.SKY, 4), page(RingKind.TEMPS, 4, "31/22°"))
        assertEquals(RingKind.SKY, r.pick(first, 0)?.kind)
        val second = listOf(page(RingKind.SKY, 4), page(RingKind.TEMPS, 4, "32/22°"))
        assertEquals("32/22°", r.pick(second, 7_500)?.line?.first()?.text)
    }

    @Test
    fun `with no page nothing is picked, and the next page shows at once`() {
        val r = InfoRingRotation()
        assertNull(r.pick(emptyList(), 0))
        assertEquals(RingKind.SKY, r.pick(listOf(page(RingKind.SKY, 4), page(RingKind.TEMPS, 4)), 100_000)?.kind)
    }

    // What the rider chose: the data, the carousel and its time.

    private fun busy() = forecast(
        current = current(gusts = 60.0),
        minutely = dry.map { if (it.timeIso.endsWith("12:45")) q("12:45", 0.4) else it },
        hourly = listOf(hour("12:00", uv = 9.0)),
    )

    private fun kindsShown(options: RingOptions, snap: WeatherKeeper.Snapshot = snapshot(busy())): List<RingKind> {
        // One look per turn of the shortest carousel (five seconds), to see every page that comes round.
        val r = InfoRingRotation()
        return (0 until 12).mapNotNull { frame0(snap, r, it * 5_000L, options)?.page?.kind }.distinct()
    }

    private fun frame0(snap: WeatherKeeper.Snapshot, r: InfoRingRotation, at: Long, options: RingOptions) =
        InfoRing.frame(snap, r, nowMs, 0L, DayNight.Place(-23.5, -46.6), false, WeatherUnits(), words, at, options)

    @Test
    fun `a kind of data the rider unticked is never said, the others are`() {
        val all = kindsShown(RingOptions(seconds = 5))
        assertTrue(RingKind.RAIN_SOON in all && RingKind.WIND_ALERT in all)
        val noRain = kindsShown(RingOptions(data = RingData.ALL and RingData.RAIN.bit.inv(), seconds = 5))
        assertTrue(RingKind.RAIN_SOON !in noRain && RingKind.WIND_ALERT in noRain)
        val noWind = kindsShown(RingOptions(data = RingData.ALL and RingData.WIND.bit.inv(), seconds = 5))
        assertTrue(RingKind.WIND_ALERT !in noWind && RingKind.RAIN_SOON in noWind)
    }

    @Test
    fun `with every kind unticked there is no ring, and with one only that kind`() {
        assertNull(frame0(snapshot(busy()), InfoRingRotation(), 0L, RingOptions(data = 0)))
        assertEquals(listOf(RingKind.TEMPS), kindsShown(RingOptions(data = RingData.TEMPS.bit)))
        assertEquals(listOf(RingKind.UV), kindsShown(RingOptions(data = RingData.UV.bit)))
    }

    @Test
    fun `every page belongs to one kind of data and every kind has a page`() {
        assertEquals(RingKind.entries.map { RingData.of(it) }.toSet(), RingData.entries.toSet())
        assertEquals(RingData.ALL, RingData.entries.sumOf { it.bit })
        assertEquals(RingData.entries.size, RingData.entries.map { it.bit }.toSet().size)
        assertTrue(RingData.shown(RingData.ALL, RingData.AIR))
        assertEquals(false, RingData.shown(RingData.ALL and RingData.AIR.bit.inv(), RingData.AIR))
        assertEquals(RingData.WIND, RingData.of(RingKind.WIND_DANGER))
        assertEquals(RingData.RAIN, RingData.of(RingKind.RAIN_NOW))
        assertEquals(RingData.STORM, RingData.of(RingKind.THUNDER))
    }

    @Test
    fun `with the carousel off the ring stays on the most urgent page, however long it goes`() {
        val r = InfoRingRotation()
        val options = RingOptions(carousel = false, seconds = 5)
        val first = frame0(snapshot(busy()), r, 0L, options)?.page?.kind
        assertEquals(RingKind.WIND_ALERT, first)
        for (at in listOf(5_000L, 6_000L, 12_000L, 100_000L)) assertEquals(first, frame0(snapshot(busy()), r, at, options)?.page?.kind)
        assertEquals("on, it turns", true, kindsShown(RingOptions(seconds = 5)).size > 1)
    }

    @Test
    fun `the carousel turns after the time the rider set`() {
        val pages = listOf(page(RingKind.UV, 3), page(RingKind.SKY, 4))
        val r = InfoRingRotation()
        assertEquals(RingKind.UV, r.pick(pages, 0, true, 10_000)?.kind)
        assertEquals(RingKind.UV, r.pick(pages, 9_999, true, 10_000)?.kind)
        assertEquals(RingKind.SKY, r.pick(pages, 10_000, true, 10_000)?.kind)
        assertEquals(RingKind.UV, r.pick(pages, 20_000, true, 10_000)?.kind)
    }

    @Test
    fun `the carousel's time is five to thirty seconds, whatever was asked`() {
        assertEquals(7_000L, RingOptions().periodMs)
        assertEquals(5_000L, RingOptions(seconds = 1).periodMs)
        assertEquals(30_000L, RingOptions(seconds = 99).periodMs)
        assertEquals(12_000L, RingOptions(seconds = 12).periodMs)
        assertEquals(5..30, RingOptions.SECONDS)
        assertEquals(7, RingOptions.DEFAULT_SECONDS)
    }

    // The phone's cards: the battery, the link, the GPS.

    private val ptWords = words.copy(
        charging = "carregando", onBattery = "bateria", saver = "economia", airplane = "avião", offline = "sem rede", mobile = "móvel",
        satellite = "satélite", satellites = "sat", noGps = "sem GPS",
        compass = listOf("N", "NNE", "NE", "ENE", "L", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO"),
    )

    private fun phonePages(phone: PhoneReadings) = InfoRing.phonePages(phone, ptWords)

    private fun battery(percent: Int, charging: Boolean = false, saver: Boolean = false) =
        phonePages(PhoneReadings(battery = BatteryDetail(percent, charging, saver))).single()

    @Test
    fun `the phone has no card until it says something, and then the battery, the link and the GPS come in that order`() {
        assertTrue(phonePages(PhoneReadings()).isEmpty())
        val all = phonePages(
            PhoneReadings(BatteryDetail(80, false, false), LinkReading(LinkKind.WIFI), GpsReading(9, 20, 4f, 90f, 10f)),
        )
        assertEquals(listOf(RingKind.BATTERY, RingKind.LINK, RingKind.GPS), kinds(all))
        assertEquals(listOf(RingIcon.BATTERY, RingIcon.LINK, RingIcon.GPS), all.map { it.icon })
    }

    @Test
    fun `the battery card carries the numbers it is drawn from and says charging, saver or battery`() {
        assertEquals(BatteryDetail(87, true, false), battery(87, charging = true).detail)
        assertEquals("carregando", line(battery(87, charging = true)))
        assertEquals("economia", line(battery(87, saver = true)))
        assertEquals("bateria", line(battery(87)))
        assertEquals("charging comes first, with the saver on too", "carregando", line(battery(87, charging = true, saver = true)))
        assertEquals("kept as it is for the picture", BatteryDetail(87, true, true), battery(87, charging = true, saver = true).detail)
    }

    @Test
    fun `a battery that runs low is a warning from 20, a stronger one from 10, and charging is never one`() {
        assertEquals(listOf(4, RingTone.NONE), battery(21).let { listOf(it.level, it.tone) })
        assertEquals(listOf(2, RingTone.ATTENTION), battery(20).let { listOf(it.level, it.tone) })
        assertEquals(listOf(2, RingTone.ATTENTION), battery(11).let { listOf(it.level, it.tone) })
        assertEquals(listOf(1, RingTone.ALERT), battery(10).let { listOf(it.level, it.tone) })
        assertEquals(listOf(1, RingTone.ALERT), battery(0).let { listOf(it.level, it.tone) })
        assertEquals(listOf(4, RingTone.NONE), battery(5, charging = true).let { listOf(it.level, it.tone) })
    }

    private fun link(r: LinkReading) = phonePages(PhoneReadings(link = r)).single()

    @Test
    fun `the link card names what carries the data`() {
        assertEquals("avião", line(link(LinkReading(LinkKind.AIRPLANE))))
        assertEquals("sem rede", line(link(LinkReading(LinkKind.OFFLINE))))
        assertEquals("Wi-Fi", line(link(LinkReading(LinkKind.WIFI, bars = 3))))
        assertEquals("Starlink", line(link(LinkReading(LinkKind.STARLINK, bars = 4))))
        assertEquals("satélite", line(link(LinkReading(LinkKind.SATELLITE))))
        assertEquals("5G Vivo", line(link(LinkReading(LinkKind.CELLULAR, 3, "5G", "Vivo"))))
        assertEquals(listOf(true, false), link(LinkReading(LinkKind.CELLULAR, 3, "5G", "Vivo")).line.map { it.big })
        assertEquals("4G", line(link(LinkReading(LinkKind.CELLULAR, 2, "4G", null))))
        assertEquals("8 letters at most", "Operador", line(link(LinkReading(LinkKind.CELLULAR, 2, null, "Operadora Nacional"))))
        assertEquals("móvel", line(link(LinkReading(LinkKind.CELLULAR, 1, null, null))))
    }

    @Test
    fun `the link card is a warning only with no network, and its bars are held between 0 and 4`() {
        for (kind in listOf(LinkKind.AIRPLANE, LinkKind.OFFLINE)) {
            assertEquals(listOf(2, RingTone.ATTENTION), link(LinkReading(kind)).let { listOf(it.level, it.tone) })
        }
        for (kind in listOf(LinkKind.WIFI, LinkKind.STARLINK, LinkKind.SATELLITE, LinkKind.CELLULAR)) {
            assertEquals(listOf(4, RingTone.NONE), link(LinkReading(kind, 2)).let { listOf(it.level, it.tone) })
        }
        assertEquals(LinkDetail(LinkKind.CELLULAR, 4, "5G"), link(LinkReading(LinkKind.CELLULAR, 9, "5G")).detail)
        assertEquals(0, (link(LinkReading(LinkKind.WIFI, -3)).detail as LinkDetail).bars)
    }

    @Test
    fun `the GPS signal in bars follows the error of the fix, and none with no satellite in it`() {
        fun bars(error: Float?, used: Int = 8) = InfoRing.gpsBars(GpsReading(used, 20, error, null, null))
        assertEquals(4, bars(3f))
        assertEquals(4, bars(5f))
        assertEquals(3, bars(5.1f))
        assertEquals(3, bars(12f))
        assertEquals(2, bars(12.1f))
        assertEquals(2, bars(25f))
        assertEquals(1, bars(25.1f))
        assertEquals(1, bars(300f))
        assertEquals("a fix that does not say its error", 2, bars(null))
        assertEquals("no satellite in the fix", 0, bars(3f, used = 0))
    }

    @Test
    fun `the heading counts only while the phone moves, whole degrees from 0 to 359`() {
        fun bearing(deg: Float?, speed: Float?) = InfoRing.gpsBearing(GpsReading(8, 20, 5f, deg, speed))
        assertEquals(null, bearing(90f, 0.99f))
        assertEquals(90, bearing(90f, 1.0f))
        assertEquals(null, bearing(null, 10f))
        assertEquals(null, bearing(90f, null))
        assertEquals(0, bearing(359.6f, 10f))
        assertEquals(350, bearing(-10f, 10f))
        assertEquals(45, bearing(44.6f, 10f))
    }

    private fun gps(r: GpsReading) = phonePages(PhoneReadings(gps = r)).single()

    @Test
    fun `the GPS card says the point of the compass and the satellites, a dash while standing still, and no GPS with no fix`() {
        val moving = gps(GpsReading(12, 24, 4f, 45f, 8f))
        assertEquals("NE 12 sat", line(moving))
        assertEquals(listOf(true, false), moving.line.map { it.big })
        assertEquals(GpsDetail(4, 12, 45, 4), moving.detail)
        assertEquals(listOf(4, RingTone.NONE), listOf(moving.level, moving.tone))
        assertEquals("— 9 sat", line(gps(GpsReading(9, 20, 6f, 90f, 0.2f))))
        assertEquals("O 7 sat", line(gps(GpsReading(7, 20, 10f, 270f, 5f))))
        val none = gps(GpsReading(0, 5, null, null, null))
        assertEquals("sem GPS", line(none))
        assertEquals(listOf(3, RingTone.ATTENTION), listOf(none.level, none.tone))
    }

    @Test
    fun `the phone's cards join the weather's, the urgent ones first`() {
        val p = InfoRing.pages(
            forecast(), null, now, true, WeatherUnits(), words,
            PhoneReadings(BatteryDetail(8, false, false), LinkReading(LinkKind.OFFLINE), GpsReading(9, 20, 4f, 10f, 9f)),
        )
        assertEquals(RingKind.BATTERY, p.first().kind)
        assertEquals(1, p.first().level)
        assertEquals(listOf(RingKind.BATTERY, RingKind.LINK, RingKind.SKY, RingKind.TEMPS, RingKind.GPS), kinds(p))
    }

    @Test
    fun `the phone's cards stand with no forecast at all`() {
        val phone = PhoneReadings(battery = BatteryDetail(60, true, false))
        val frame = InfoRing.frame(null, InfoRingRotation(), nowMs, 0L, null, false, WeatherUnits(), words, 0L, RingOptions(), phone)
        assertEquals(RingKind.BATTERY, frame?.page?.kind)
        assertEquals("no forecast, no stale mark", false, frame?.stale)
        assertNull("nothing from the phone and no forecast", InfoRing.frame(null, InfoRingRotation(), nowMs, 0L, null, false, WeatherUnits(), words, 0L))
    }

    @Test
    fun `a forecast too old leaves the phone's cards`() {
        val old = snapshot(at = nowMs - WeatherKeeper.USABLE_MS - 1)
        val phone = PhoneReadings(battery = BatteryDetail(60, false, false), link = LinkReading(LinkKind.WIFI))
        val shown = (0 until 4).mapNotNull { InfoRing.frame(old, InfoRingRotation(), nowMs, 0L, null, false, WeatherUnits(), words, it * 7_000L, RingOptions(), phone)?.page?.kind }
        assertTrue(shown.isNotEmpty() && shown.all { it == RingKind.BATTERY || it == RingKind.LINK })
    }

    @Test
    fun `an unticked card of the phone is never said`() {
        val phone = PhoneReadings(battery = BatteryDetail(8, false, false))
        val without = RingOptions(data = RingData.ALL and RingData.BATTERY.bit.inv())
        val frame = InfoRing.frame(null, InfoRingRotation(), nowMs, 0L, null, false, WeatherUnits(), words, 0L, without, phone)
        assertNull(frame)
    }

    @Test
    fun `each card of the phone belongs to its own kind of data`() {
        assertEquals(RingData.BATTERY, RingData.of(RingKind.BATTERY))
        assertEquals(RingData.LINK, RingData.of(RingKind.LINK))
        assertEquals(RingData.GPS, RingData.of(RingKind.GPS))
        assertEquals(2047, RingData.ALL)
        assertEquals(255, RingData.BEFORE_PHONE_CARDS)
        assertEquals(listOf(256, 512, 1024), listOf(RingData.BATTERY, RingData.LINK, RingData.GPS).map { it.bit })
    }
}
