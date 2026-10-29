package com.reau.app.weather

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line "Enviado ao painel" of the weather screen writes the temperature REau sent in the unit the dash shows (28/10/2026), not a fixed
 * "°C": what goes to the dash stays Celsius (`06 10`), only the words change.
 */
class WeatherSentLineTest {
    private fun template(folder: String): String =
        Regex("""<string name="weather_sent">(.*?)</string>""").find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]

    /** The line as the screen builds it: the template with the temperature (already in the unit) and the icon's name. */
    private fun line(folder: String, units: WeatherUnits, sentCelsius: Int, icon: String) =
        template(folder).replace("%1\$s", units.sentTemp(sentCelsius)).replace("%2\$s", icon)

    @Test
    fun `the temperature is written in the unit of the dash, Celsius or Fahrenheit`() {
        assertEquals("24 °C, icon: rain", line("values", WeatherUnits(fahrenheit = false), 24, "rain"))
        assertEquals("75 °F, icon: rain", line("values", WeatherUnits(fahrenheit = true), 24, "rain"))
        assertEquals("75 °F, ícone: chuva", line("values-pt", WeatherUnits(fahrenheit = true), 24, "chuva"))
        assertEquals("-4 °F, ícono: lluvia", line("values-es", WeatherUnits(fahrenheit = true), -20, "lluvia"))
    }

    @Test
    fun `no language writes the unit in the text, and all three take a text and a name`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val text = template(folder)
            assertFalse("$folder: a fixed unit in $text", "°C" in text || "°F" in text)
            assertTrue("$folder: $text", "%1\$s" in text && "%2\$s" in text && "%1\$d" !in text)
        }
    }
}
