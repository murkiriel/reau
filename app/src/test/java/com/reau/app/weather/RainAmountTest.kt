package com.reau.app.weather

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How much rain is written and when a line has a pill: the one rule of the Clima screen's pills. */
class RainAmountTest {
    private val pt = Locale.forLanguageTag("pt-BR")
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es")

    @Test
    fun `less than a tenth of a millimetre is no rain and nothing is written`() {
        assertNull(RainAmount.pill(0.05, pt)?.written)
        assertNull(RainAmount.pill(0.0, pt)?.written)
        assertNull(RainAmount.pill(0.0999, pt)?.written)
        assertNull(RainAmount.pill(null, pt)?.written)
        assertNull(RainAmount.pill(Double.NaN, pt)?.written)
        assertNull(RainAmount.number(Double.POSITIVE_INFINITY, pt))
        assertFalse(RainAmount.isRain(0.09))
        assertTrue(RainAmount.isRain(0.1))
    }

    @Test
    fun `below ten it has one decimal, from ten none`() {
        assertEquals("0,1 mm", RainAmount.pill(0.1, pt)?.written)
        assertEquals("0,4 mm", RainAmount.pill(0.4, pt)?.written)
        assertEquals("8,2 mm", RainAmount.pill(8.2, pt)?.written)
        assertEquals("8,3 mm", RainAmount.pill(8.25, pt)?.written)
        assertEquals("9,9 mm", RainAmount.pill(9.94, pt)?.written)
        assertEquals("10 mm", RainAmount.pill(10.0, pt)?.written)
        assertEquals("10 mm", RainAmount.pill(9.96, pt)?.written)
        assertEquals("14 mm", RainAmount.pill(14.2, pt)?.written)
        assertEquals("31 mm", RainAmount.pill(31.0, pt)?.written)
        assertEquals("120 mm", RainAmount.pill(120.0, pt)?.written)
    }

    @Test
    fun `the decimal sign is the language's`() {
        assertEquals("4,2", RainAmount.number(4.2, pt))
        assertEquals("4,2", RainAmount.number(4.2, es))
        assertEquals("4.2", RainAmount.number(4.2, en))
        assertEquals("4.2", RainAmount.number(4.2, Locale.ROOT))
        assertEquals("14", RainAmount.number(14.0, pt))
    }

    @Test
    fun `the ring and the title never say the intensity in mm per hour`() {
        for (file in listOf("weather/InfoRing.kt", "weather/InfoRingTitle.kt", "service/InfoRingFeed.kt", "video/InfoRingInk.kt", "video/InfoRingLook.kt")) {
            val source = File("src/main/java/com/reau/app/$file").readText()
            // InfoRing.kt keeps 7.6 mm/h in a comment: the line that makes the icon heavy rain, which is no text on the ring.
            if (file != "weather/InfoRing.kt") assertFalse("$file: no mm/h", source.contains("mm/h"))
            assertFalse("$file: no rate", Regex("""\brate\b|rainRate|RATE_""").containsMatchIn(source))
        }
        for (folder in listOf("values", "values-pt", "values-es")) {
            val text = File("src/main/res/$folder/strings.xml").readText()
            assertFalse("$folder: no rain rate title", text.contains("title_rain_rate"))
            val info = Regex("""<string name="info_ring_data_rain_info">(.*)</string>""").find(text)!!.groupValues[1]
            assertFalse("$folder: the help says no intensity", listOf("how hard", "intensid", "intensity").any { info.contains(it) })
            // 22/11/2026: the help no longer quotes the 7.6 mm/h of the heavy-rain icon either; the dash never shows mm/h.
            assertFalse("$folder: the help says no mm/h", info.contains("mm/h"))
        }
    }

    @Test
    fun `a line with rain has a pill with the amount and a dry line has none`() {
        val rain = checkNotNull(RainAmount.pill(2.4, pt))
        assertEquals("2,4", rain.number)
        assertEquals("2,4 mm", rain.written)
        assertEquals("0,1 mm", RainAmount.pill(0.1, pt)?.written)
        assertEquals("12 mm", RainAmount.pill(12.0, pt)?.written)
        assertEquals("2.4 mm", RainAmount.pill(2.4, en)?.written)
        assertNull(RainAmount.pill(0.0, pt))
        assertNull(RainAmount.pill(0.09, pt))
        assertNull(RainAmount.pill(null, pt))
        assertNull(RainAmount.pill(Double.NaN, pt))
    }

    @Test
    fun `the request asks for the rain by the hour and the day, and the screen reads and describes them`() {
        val keeper = File("src/main/java/com/reau/app/weather/WeatherKeeper.kt").readText()
        assertTrue(keeper.contains("precipitation_probability,precipitation,weather_code,uv_index"))
        assertTrue(keeper.contains("precipitation_probability_max,precipitation_sum,"))
        val models = File("src/main/java/com/reau/app/weather/WeatherModels.kt").readText()
        assertTrue(models.contains("""o.optJSONArray("precipitation")"""))
        assertTrue(models.contains("""o.optJSONArray("precipitation_sum")"""))
        assertTrue("NaN and absent are null", models.contains("precip?.optDouble(i)?.takeUnless { it.isNaN() }"))
        assertTrue(models.contains("precipSum?.optDouble(i)?.takeUnless { it.isNaN() }"))
        // An older kept answer has neither: both fields default to null, so it reads as before.
        assertTrue(models.contains("val precipitationMm: Double? = null"))
        assertTrue(models.contains("val precipitationSumMm: Double? = null"))
        val screen = File("src/main/java/com/reau/app/ui/WeatherScreen.kt").readText()
        assertTrue(screen.contains("RainAmount.pill(rainMm"))
        assertTrue(screen.contains("weather_rain_amount_cd"))
    }

    @Test
    fun `the amount is spoken in words in the three languages`() {
        for ((folder, word) in mapOf("values" to "millimeters of rain", "values-pt" to "milímetros de chuva", "values-es" to "milímetros de lluvia")) {
            val text = File("src/main/res/$folder/strings.xml").readText()
            assertTrue(folder, text.contains("""<string name="weather_rain_amount_cd">%1${'$'}s $word</string>"""))
        }
    }
}
