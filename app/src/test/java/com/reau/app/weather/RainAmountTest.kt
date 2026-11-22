package com.reau.app.weather

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How much rain is written and how long its bar is: the one rule of the Clima screen, the ring and the title. */
class RainAmountTest {
    private val pt = Locale.forLanguageTag("pt-BR")
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es")

    @Test
    fun `less than a tenth of a millimetre is no rain and nothing is written`() {
        assertNull(RainAmount.format(0.05, pt))
        assertNull(RainAmount.format(0.0, pt))
        assertNull(RainAmount.format(0.0999, pt))
        assertNull(RainAmount.format(null, pt))
        assertNull(RainAmount.format(Double.NaN, pt))
        assertNull(RainAmount.number(Double.POSITIVE_INFINITY, pt))
        assertFalse(RainAmount.isRain(0.09))
        assertTrue(RainAmount.isRain(0.1))
    }

    @Test
    fun `below ten it has one decimal, from ten none`() {
        assertEquals("0,1 mm", RainAmount.format(0.1, pt))
        assertEquals("0,4 mm", RainAmount.format(0.4, pt))
        assertEquals("8,2 mm", RainAmount.format(8.2, pt))
        assertEquals("8,3 mm", RainAmount.format(8.25, pt))
        assertEquals("9,9 mm", RainAmount.format(9.94, pt))
        assertEquals("10 mm", RainAmount.format(10.0, pt))
        assertEquals("10 mm", RainAmount.format(9.96, pt))
        assertEquals("14 mm", RainAmount.format(14.2, pt))
        assertEquals("31 mm", RainAmount.format(31.0, pt))
        assertEquals("120 mm", RainAmount.format(120.0, pt))
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
    fun `the same rule gives mm per hour`() {
        assertEquals("4,2 mm/h", RainAmount.formatPerHour(4.2, pt))
        assertEquals("14 mm/h", RainAmount.formatPerHour(14.0, pt))
        assertNull(RainAmount.formatPerHour(0.05, pt))
    }

    @Test
    fun `the ruler is empty with no rain, proportional, and full from its scale on`() {
        assertEquals(0f, RainAmount.fill(0.0, RainAmount.HOUR_FULL_MM), 0f)
        assertEquals(0f, RainAmount.fill(null, RainAmount.HOUR_FULL_MM), 0f)
        assertEquals(0f, RainAmount.fill(0.05, RainAmount.HOUR_FULL_MM), 0f)
        assertEquals(0.5f, RainAmount.fill(5.0, RainAmount.HOUR_FULL_MM), 1e-6f)
        assertEquals(1f, RainAmount.fill(10.0, RainAmount.HOUR_FULL_MM), 0f)
        assertEquals(1f, RainAmount.fill(55.0, RainAmount.HOUR_FULL_MM), 0f)
        assertEquals(0.5f, RainAmount.fill(20.0, RainAmount.DAY_FULL_MM), 1e-6f)
        assertEquals(1f, RainAmount.fill(40.0, RainAmount.DAY_FULL_MM), 0f)
        assertEquals(1f, RainAmount.fill(120.0, RainAmount.DAY_FULL_MM), 0f)
        assertEquals(10.0, RainAmount.HOUR_FULL_MM, 0.0)
        assertEquals(40.0, RainAmount.DAY_FULL_MM, 0.0)
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
        assertTrue(screen.contains("RainAmount.HOUR_FULL_MM"))
        assertTrue(screen.contains("RainAmount.DAY_FULL_MM"))
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
