package com.reau.app.weather

import com.reau.app.service.DayNight
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The info ring's pages as words for the title of the dash's gold band (P31 Título): the same pages and the same choice of them as the ring,
 * written in the three languages within what the dash's street field holds (19 characters, Latin-1), and with the pages that are not words
 * (the phone's signal and GPS, and the sky now) never among them; the phone's battery is one of them. The place's clock is 2026-10-26 12:07, offset 0.
 */
class InfoRingTitleTest {
    private val languages = mapOf("en" to "values", "pt" to "values-pt", "es" to "values-es")
    private val ringWords = RingWords(
        "em", "min", "até", "chovendo", "sem chuva", "trovoada", "UV", "IQAr",
        skyClear = "limpo", skyPartly = "nuvens", skyCloudy = "nublado", skyFog = "neblina", skyDrizzle = "garoa", skyRain = "chuva", skySnow = "neve",
    )
    private val now = LocalDateTime.parse("2026-10-26T12:07")
    private val nowMs = now.toEpochSecond(ZoneOffset.UTC) * 1000

    /** A place where the forecast has real quarters of an hour, so the minutes come exact (and a Brazilian one where they come as "~20"). */
    private val exactPlace = DayNight.Place(40.0, -100.0)
    private val roundedPlace = DayNight.Place(-23.5, -46.6)

    private fun strings(folder: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/$folder/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }

    /** The title words of a language, read from its strings.xml. */
    private fun words(folder: String): TitleWords {
        val s = strings(folder)
        fun t(name: String) = s.getValue("title_$name")
        return TitleWords(
            t("rain_now"), t("rain_until"), t("rain_soon"), t("thunder_now"), t("thunder_soon"), t("wind"), t("temps"), t("temps_now"),
            t("uv_high"), t("uv_very_high"), t("uv_extreme"), t("sunset"), t("sunrise"), t("air"), t("battery"), t("battery_charging"),
        )
    }

    private fun current(code: Int? = 1, gusts: Double? = null, rainMm: Double? = 0.0) = CurrentWeather(
        24.0, null, null, rainMm, code, 8.0, null, gusts, null, null, true, timeIso = "2026-10-26T12:00",
    )

    private fun q(end: String, mm: Double = 0.0, code: Int? = null) = MinutelyWeather("2026-10-26T$end", mm, code, null)

    private val ends = listOf("12:00", "12:15", "12:30", "12:45", "13:00", "13:15", "13:30", "13:45")
    private val dry = ends.map { q(it) }

    private fun day(sunrise: String? = null, sunset: String? = null) =
        DailyWeather("2026-10-26", 31.0, 22.0, null, null, sunrise, sunset, null, null, null, null)

    private fun forecast(
        minutely: List<MinutelyWeather> = dry,
        current: CurrentWeather? = current(),
        hourly: List<HourlyWeather> = emptyList(),
        daily: List<DailyWeather> = listOf(day()),
    ) = PointForecast(current, hourly, daily, minutely, 0)

    private fun titlePages(
        f: PointForecast,
        options: RingOptions = factory,
        place: DayNight.Place = exactPlace,
        units: WeatherUnits = WeatherUnits(),
        air: AirQuality? = null,
        phone: PhoneReadings = PhoneReadings(),
    ) = InfoRing.titlePages(WeatherKeeper.Snapshot(f, nowMs, air), nowMs, 0L, place, units, ringWords, options, phone)

    /** What the title would say of [f], most urgent first, in the language of [folder]. */
    private fun said(
        f: PointForecast,
        folder: String = "values-pt",
        options: RingOptions = factory,
        place: DayNight.Place = exactPlace,
        units: WeatherUnits = WeatherUnits(),
        air: AirQuality? = null,
        phone: PhoneReadings = PhoneReadings(),
    ): List<String> = titlePages(f, options, place, units, air, phone).mapNotNull { InfoRingTitle.text(it, words(folder)) }

    /** What a new install ticks: the rain, the wind and the storm (the ring's own default is every kind). */
    private val factory = RingOptions(data = RingData.DEFAULT)

    private fun everything() = RingOptions(data = RingData.ALL)

    private fun hour(uv: Double) = HourlyWeather("2026-10-26T12:00", 25.0, null, null, uv)

    // The words, page by page, in the three languages.

    @Test
    fun `rain on the way says in how many minutes, in each language`() {
        val soon = forecast(minutely = dry.map { if (it.timeIso.endsWith("12:45")) q("12:45", 0.4) else it })
        assertEquals(listOf("Chuva em 23 min"), said(soon, "values-pt"))
        assertEquals(listOf("Rain in 23 min"), said(soon, "values"))
        assertEquals(listOf("Lluvia en 23 min"), said(soon, "values-es"))
    }

    @Test
    fun `rain where the forecast is only hourly says the minutes rounded, with the tilde`() {
        val soon = forecast(minutely = dry.map { if (it.timeIso.endsWith("12:45")) q("12:45", 0.4) else it })
        val hourly = soon.copy(hourly = listOf(HourlyWeather("2026-10-26T12:00", 25.0, 80, null, null)))
        assertEquals(listOf("Chuva em ~20 min"), said(hourly, place = roundedPlace))
    }

    @Test
    fun `rain falling now says so, and until when if it stops`() {
        val endless = forecast(minutely = ends.map { q(it, 0.4) }, current = current(rainMm = 0.4))
        assertEquals(listOf("Chovendo agora"), said(endless, "values-pt"))
        assertEquals(listOf("Raining now"), said(endless, "values"))
        assertEquals(listOf("Lloviendo ahora"), said(endless, "values-es"))
        val stops = forecast(minutely = dry.map { if (it.timeIso.endsWith("12:00")) q("12:00", 0.4) else it }, current = current(rainMm = 0.4))
        assertEquals(listOf("Chuva até 4 min"), said(stops, "values-pt"))
        assertEquals(listOf("Rain for 4 min"), said(stops, "values"))
        assertEquals(listOf("Lluvia por 4 min"), said(stops, "values-es"))
    }

    @Test
    fun `a storm says it is on, or in how many minutes it comes`() {
        val on = forecast(current = current(code = 95))
        assertEquals(listOf("Trovoada"), said(on, "values-pt"))
        assertEquals(listOf("Thunder"), said(on, "values"))
        assertEquals(listOf("Tormenta"), said(on, "values-es"))
        val soon = forecast(minutely = dry.map { if (it.timeIso.endsWith("12:30")) q("12:30", 0.0, code = 95) else it })
        assertEquals(listOf("Trovoada em 23 min"), said(soon, "values-pt"))
        assertEquals(listOf("Thunder in 23 min"), said(soon, "values"))
        assertEquals(listOf("Tormenta en 23 min"), said(soon, "values-es"))
    }

    @Test
    fun `the wind says the gust and its unit at each of its three levels`() {
        for ((gust, number) in listOf(45.0 to "45", 60.0 to "60", 75.0 to "75")) {
            val f = forecast(current = current(gusts = gust))
            assertEquals(listOf("Vento $number km/h"), said(f, "values-pt"))
            assertEquals(listOf("Wind $number km/h"), said(f, "values"))
            assertEquals(listOf("Viento $number km/h"), said(f, "values-es"))
        }
        assertEquals(listOf("Wind 28 mph"), said(forecast(current = current(gusts = 45.0)), "values", units = WeatherUnits(miles = true)))
        assertEquals("below the first level there is no page", emptyList<String>(), said(forecast(current = current(gusts = 39.0))))
    }

    @Test
    fun `the day's low and high, or the temperature now when there is no day`() {
        val temps = RingOptions(data = RingData.TEMPS.bit)
        assertEquals(listOf("Min 22° Max 31°"), said(forecast(), "values-pt", temps))
        assertEquals(listOf("Min 22° Max 31°"), said(forecast(), "values", temps))
        assertEquals(listOf("Mín 22° Máx 31°"), said(forecast(), "values-es", temps))
        assertEquals(listOf("Min 72° Max 88°"), said(forecast(), "values", temps, units = WeatherUnits(fahrenheit = true)))
        assertEquals(listOf("Agora 24°"), said(forecast(daily = emptyList()), "values-pt", temps))
        assertEquals(listOf("Now 24°"), said(forecast(daily = emptyList()), "values", temps))
    }

    @Test
    fun `the UV says its level in words`() {
        val uv = RingOptions(data = RingData.UV.bit)
        assertEquals(listOf("UV 6 alto"), said(forecast(hourly = listOf(hour(6.4))), "values-pt", uv))
        assertEquals(listOf("UV 9 muito alto"), said(forecast(hourly = listOf(hour(9.0))), "values-pt", uv))
        assertEquals(listOf("UV 11 extremo"), said(forecast(hourly = listOf(hour(11.2))), "values-pt", uv))
        assertEquals(listOf("UV 9 very high"), said(forecast(hourly = listOf(hour(9.0))), "values", uv))
        assertEquals(listOf("UV 9 muy alto"), said(forecast(hourly = listOf(hour(9.0))), "values-es", uv))
        assertEquals("below high there is no page", emptyList<String>(), said(forecast(hourly = listOf(hour(5.9))), "values-pt", uv))
    }

    @Test
    fun `the sun's set and rise say the clock time of the place`() {
        val sun = RingOptions(data = RingData.SUN.bit)
        val setting = forecast(daily = listOf(day(sunset = "2026-10-26T12:50")))
        assertEquals(listOf("Pôr do sol 12:50"), said(setting, "values-pt", sun))
        assertEquals(listOf("Sunset 12:50"), said(setting, "values", sun))
        assertEquals(listOf("Puesta de sol 12:50"), said(setting, "values-es", sun))
        val rising = forecast(daily = listOf(day(sunrise = "2026-10-26T13:02")))
        assertEquals(listOf("Nascer do sol 13:02"), said(rising, "values-pt", sun))
        assertEquals(listOf("Sunrise 13:02"), said(rising, "values", sun))
        assertEquals(listOf("Amanecer 13:02"), said(rising, "values-es", sun))
        val faraway = forecast(daily = listOf(day(sunset = "2026-10-26T18:12")))
        assertEquals("more than an hour away is no page", emptyList<String>(), said(faraway, "values-pt", sun))
    }

    @Test
    fun `the air quality says its index, and only when the rider ticked it`() {
        val f = forecast()
        val air = AirQuality(154, null, null, null)
        assertEquals("not among the factory's pages", emptyList<String>(), said(f, air = air))
        val ticked = RingOptions(data = RingData.AIR.bit)
        assertEquals(listOf("Ar AQI 154"), said(f, "values-pt", ticked, air = air))
        assertEquals(listOf("Air AQI 154"), said(f, "values", ticked, air = air))
        assertEquals(listOf("Aire AQI 154"), said(f, "values-es", ticked, air = air))
        assertEquals("a good air is no page", emptyList<String>(), said(f, "values-pt", ticked, air = AirQuality(80, null, null, null)))
    }

    // The choice of the pages is the ring's.

    private fun busy() = forecast(
        current = current(gusts = 60.0),
        minutely = dry.map { if (it.timeIso.endsWith("12:45")) q("12:45", 0.4) else it },
        hourly = listOf(hour(9.0)),
        daily = listOf(day(sunset = "2026-10-26T12:50")),
    )

    @Test
    fun `the pages of the title are the ring's pages, in its order, less the ones that are not words`() {
        val ring = InfoRing.pages(busy(), AirQuality(154, null, null, null), now, true, WeatherUnits(), ringWords)
        val title = titlePages(busy(), everything(), air = AirQuality(154, null, null, null))
        assertEquals(ring.filter { it.kind in InfoRingTitle.KINDS }, title)
        assertTrue("the ring has the sky and the title does not", RingKind.SKY in ring.map { it.kind } && RingKind.SKY !in title.map { it.kind })
        assertEquals(title.map { it.level }, title.map { it.level }.sorted())
    }

    @Test
    fun `the data the rider ticked decide the title as they decide the ring, and the factory's three are rain, wind and storm`() {
        assertEquals(listOf(RingKind.WIND_ALERT, RingKind.RAIN_SOON), titlePages(busy()).map { it.kind })
        assertEquals(listOf(RingKind.RAIN_SOON), titlePages(busy(), RingOptions(data = RingData.RAIN.bit)).map { it.kind })
        assertEquals(listOf(RingKind.WIND_ALERT), titlePages(busy(), RingOptions(data = RingData.WIND.bit)).map { it.kind })
        assertEquals(emptyList<RingKind>(), titlePages(busy(), RingOptions(data = 0)).map { it.kind })
    }

    private fun phone(percent: Int, charging: Boolean = false) =
        PhoneReadings(BatteryDetail(percent, charging, saver = false), LinkReading(LinkKind.WIFI), GpsReading(5, 9, 4f, 90f, 10f))

    private val batteryOnly = RingOptions(data = RingData.BATTERY.bit)

    @Test
    fun `signal, GPS and the sky never come to the title, ticked or not`() {
        val kinds = titlePages(busy(), everything(), phone = phone(15)).map { it.kind }
        for (out in listOf(RingKind.LINK, RingKind.GPS, RingKind.SKY)) assertFalse("$out", out in kinds)
        assertEquals(setOf(RingKind.LINK, RingKind.GPS, RingKind.SKY), RingKind.entries.toSet() - InfoRingTitle.KINDS)
        val only = RingOptions(data = RingData.LINK.bit or RingData.GPS.bit or RingData.SKY.bit)
        assertEquals(emptyList<RingKind>(), titlePages(busy(), only, phone = phone(15)).map { it.kind })
        for (page in InfoRing.pages(busy(), null, now, true, WeatherUnits(), ringWords, phone(15)).filter { it.kind !in InfoRingTitle.KINDS }) {
            assertNull("${page.kind}", InfoRingTitle.text(page, words("values-pt")))
        }
    }

    @Test
    fun `the phone's battery says its level, in each language`() {
        assertEquals(listOf("Bateria 85%"), said(forecast(), "values-pt", batteryOnly, phone = phone(85)))
        assertEquals(listOf("Battery 85%"), said(forecast(), "values", batteryOnly, phone = phone(85)))
        assertEquals(listOf("Batería 85%"), said(forecast(), "values-es", batteryOnly, phone = phone(85)))
    }

    @Test
    fun `a charging phone says so, in each language`() {
        assertEquals(listOf("Carregando 85%"), said(forecast(), "values-pt", batteryOnly, phone = phone(85, charging = true)))
        assertEquals(listOf("Charging 85%"), said(forecast(), "values", batteryOnly, phone = phone(85, charging = true)))
        assertEquals(listOf("Cargando 85%"), said(forecast(), "values-es", batteryOnly, phone = phone(85, charging = true)))
    }

    @Test
    fun `the battery is a page only when the rider ticked it, and not without a reading`() {
        assertEquals("not among the factory's pages", emptyList<String>(), said(forecast(), phone = phone(85)))
        assertEquals("ticked but the phone gave no reading", emptyList<String>(), said(forecast(), "values-pt", batteryOnly))
    }

    @Test
    fun `the battery takes its turn among the weather pages by its urgency, as in the ring`() {
        val options = RingOptions(data = RingData.BATTERY.bit or RingData.TEMPS.bit)
        val normal = titlePages(forecast(), options, phone = phone(80))
        assertEquals(setOf(RingKind.TEMPS, RingKind.BATTERY), normal.map { it.kind }.toSet())
        val rotation = InfoRingRotation()
        val seen = (0 until 2).map { rotation.pick(normal, it * 7_000L)!!.kind }
        assertEquals("the battery is one of the turns", setOf(RingKind.TEMPS, RingKind.BATTERY), seen.toSet())
        val critical = titlePages(forecast(), options, phone = phone(5))
        assertEquals("a critical battery is a danger page, as in the ring", 1, critical.first { it.kind == RingKind.BATTERY }.level)
        assertEquals("the danger page takes the turns alone", listOf(RingKind.BATTERY), InfoRingRotation().turn(critical, 0L)!!.pages.map { it.kind })
        val ring = InfoRing.pages(forecast(), null, now, true, WeatherUnits(), ringWords, phone(5)).filter { it.kind in InfoRingTitle.KINDS && RingData.shown(options.data, RingData.of(it.kind)) }
        assertEquals(ring, critical)
        assertEquals("low is level 2", 2, titlePages(forecast(), options, phone = phone(15)).first { it.kind == RingKind.BATTERY }.level)
    }

    @Test
    fun `with no usable forecast the battery still says its level, as the ring's phone cards do`() {
        val pages = InfoRing.titlePages(null, nowMs, 0L, exactPlace, WeatherUnits(), ringWords, batteryOnly, phone(40))
        assertEquals(listOf("Battery 40%"), pages.mapNotNull { InfoRingTitle.text(it, words("values")) })
    }

    @Test
    fun `no usable forecast, no pages, and the phone is not even asked`() {
        assertEquals(emptyList<RingPage>(), InfoRing.titlePages(null, nowMs, 0L, exactPlace, WeatherUnits(), ringWords, everything()))
        val old = WeatherKeeper.Snapshot(busy(), nowMs - WeatherKeeper.USABLE_MS - 1)
        assertEquals(emptyList<RingPage>(), InfoRing.titlePages(old, nowMs, 0L, exactPlace, WeatherUnits(), ringWords, everything()))
    }

    // The room in the dash's street field.

    private fun page(kind: RingKind, icon: RingIcon, tone: RingTone, line: List<String>, extra: List<String> = emptyList()) =
        RingPage(kind, 2, icon, tone, line.map { RingText(it, false) }, extra = extra)

    /** Every page the title says, with the longest numbers it can have. */
    private fun worstCases(): List<RingPage> = listOf(
        page(RingKind.RAIN_SOON, RingIcon.RAIN, RingTone.RAIN, listOf("em", "~60", "min")),
        page(RingKind.RAIN_NOW, RingIcon.RAIN, RingTone.RAIN, listOf("até", "~60", "min")),
        page(RingKind.RAIN_NOW, RingIcon.RAIN, RingTone.RAIN, listOf("chovendo")),
        page(RingKind.THUNDER, RingIcon.THUNDER, RingTone.DANGER, listOf("em", "~60", "min")),
        page(RingKind.THUNDER, RingIcon.THUNDER, RingTone.DANGER, listOf("trovoada")),
        page(RingKind.WIND_DANGER, RingIcon.WIND, RingTone.DANGER, listOf("120", "km/h")),
        page(RingKind.WIND_ALERT, RingIcon.WIND, RingTone.ALERT, listOf("100", "mph")),
        page(RingKind.WIND_ATTENTION, RingIcon.WIND, RingTone.ATTENTION, listOf("44", "km/h")),
        page(RingKind.TEMPS, RingIcon.THERMOMETER, RingTone.NONE, listOf("-30/-40°"), extra = listOf("-30", "-40")),
        page(RingKind.TEMPS, RingIcon.THERMOMETER, RingTone.NONE, listOf("-30°")),
        page(RingKind.UV, RingIcon.UV, RingTone.UV_HIGH, listOf("UV", "7")),
        page(RingKind.UV, RingIcon.UV, RingTone.UV_VERY_HIGH, listOf("UV", "10")),
        page(RingKind.UV, RingIcon.UV, RingTone.UV_EXTREME, listOf("UV", "11")),
        page(RingKind.SUN, RingIcon.SUNSET, RingTone.NONE, listOf("em", "59", "min"), extra = listOf("23:59")),
        page(RingKind.SUN, RingIcon.SUNRISE, RingTone.NONE, listOf("em", "59", "min"), extra = listOf("23:59")),
        page(RingKind.AIR, RingIcon.AIR, RingTone.AIR_HAZARDOUS, listOf("IQAr", "500")),
        RingPage(RingKind.BATTERY, 4, RingIcon.BATTERY, RingTone.NONE, listOf(RingText("carregando", false)), BatteryDetail(100, charging = true, saver = false)),
        RingPage(RingKind.BATTERY, 4, RingIcon.BATTERY, RingTone.NONE, listOf(RingText("na bateria", false)), BatteryDetail(100, charging = false, saver = false)),
    )

    @Test
    fun `every text, with the longest numbers, fits the dash's street field in every language`() {
        val fits = com.reau.app.dash.protocol.DashCommands.ROAD_MAX
        assertEquals(19, fits)
        for ((language, folder) in languages) {
            val w = words(folder)
            for (page in worstCases()) {
                val text = InfoRingTitle.text(page, w)
                assertTrue("$language ${page.kind}: no text", text != null)
                assertTrue("$language ${page.kind} '$text' has ${text!!.length} characters, the field holds $fits", text.length <= fits)
            }
        }
    }

    @Test
    fun `every text reaches the dash whole, each letter its one Latin-1 byte`() {
        for ((language, folder) in languages) {
            val w = words(folder)
            for (page in worstCases()) {
                val text = InfoRingTitle.text(page, w)!!
                assertEquals("$language ${page.kind}: the dash would change a letter", text, com.reau.app.dash.protocol.DashCommands.legible(text))
                assertTrue("$language ${page.kind}: a letter outside Latin-1 in '$text'", text.all { it.code < 0x100 })
                assertEquals(text, String(com.reau.app.dash.protocol.DashCommands.latin1(text, 19), Charsets.ISO_8859_1))
                assertEquals("$language ${page.kind}: no space at the ends", text.trim(), text)
            }
        }
    }

    @Test
    fun `the templates are all in the three languages and no two say the same in a language`() {
        val names = snake(TitleWords::class.java.declaredFields.filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name })
        assertEquals(16, names.size)
        for ((language, folder) in languages) {
            val s = strings(folder)
            val values = names.map { name -> s["title_$name"] ?: error("$language: no title_$name") }
            assertEquals("$language: two templates say the same", values.size, values.distinct().size)
            val present = s.keys.filter { it.startsWith("title_") }.map { it.removePrefix("title_") }
            assertEquals("$language: a title_ text no template uses", names.sorted(), present.sorted())
        }
    }

    private fun snake(names: List<String>) = names.map { n -> n.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() } }

    @Test
    fun `a page that lacks what its words need says nothing and does not fail`() {
        val w = words("values-pt")
        assertNull(InfoRingTitle.text(page(RingKind.SUN, RingIcon.SUNSET, RingTone.NONE, listOf("em", "5", "min")), w))
        assertNull(InfoRingTitle.text(page(RingKind.RAIN_SOON, RingIcon.RAIN, RingTone.RAIN, listOf("em")), w))
        assertNull(InfoRingTitle.text(page(RingKind.WIND_ALERT, RingIcon.WIND, RingTone.ALERT, listOf("55")), w))
        assertNull("a battery page with no detail", InfoRingTitle.text(page(RingKind.BATTERY, RingIcon.BATTERY, RingTone.NONE, listOf("na bateria")), w))
    }
}
