package com.reau.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The columns of a line of the Clima screen: the pill sits just before the icon, and the day's name still has its room on the owner's phone. */
class ForecastLayoutTest {
    private val columns = ForecastLayout.Column.values().toList()

    @Test
    fun `the pill is just before the icon, and a dry line has none`() {
        assertEquals(
            listOf(ForecastLayout.Column.LABEL, ForecastLayout.Column.PILL, ForecastLayout.Column.ICON, ForecastLayout.Column.CHANCE,
                ForecastLayout.Column.FIRST, ForecastLayout.Column.SECOND),
            ForecastLayout.columns(hasPill = true, hasSecond = true),
        )
        assertEquals(
            listOf(ForecastLayout.Column.LABEL, ForecastLayout.Column.ICON, ForecastLayout.Column.CHANCE, ForecastLayout.Column.FIRST),
            ForecastLayout.columns(hasPill = false, hasSecond = false),
        )
        val withRain = ForecastLayout.columns(hasPill = true, hasSecond = false)
        assertEquals(withRain.indexOf(ForecastLayout.Column.ICON) - 1, withRain.indexOf(ForecastLayout.Column.PILL))
        assertEquals("the hour or the day's name stays first", ForecastLayout.Column.LABEL, withRain.first())
        for (hasPill in listOf(true, false)) for (hasSecond in listOf(true, false)) {
            val line = ForecastLayout.columns(hasPill, hasSecond)
            // Everything from the icon on is the same, with or without the pill: the icon stays in its column.
            assertEquals(columns.filter { it >= ForecastLayout.Column.ICON && (hasSecond || it != ForecastLayout.Column.SECOND) }, line.dropWhile { it != ForecastLayout.Column.ICON })
        }
    }

    @Test
    fun `on 432 dp at font scale 1_15 the day's name has its room, with the longest pill and the days' two temperatures`() {
        val room = ForecastLayout.roomDp(432.0)
        assertEquals(366.0, room, 0.0)
        // "Dom. 22/11" is 10 characters; "0,6 mm" and "120 mm" are 6.
        val days = ForecastLayout.neededDp(1.15, labelCharacters = 10, pillCharacters = 6, hasSecond = true)
        assertTrue("the days' line needs $days of $room", days <= room - 10.0)
        val hours = ForecastLayout.neededDp(1.15, labelCharacters = 5, pillCharacters = 6, hasSecond = false)
        assertTrue("the hours' line needs $hours of $room", hours <= room - 100.0)
        // Without the pill the line is shorter by the pill and its gap.
        assertTrue(ForecastLayout.neededDp(1.15, 10, 0, true) < days - 55.0)
    }

    @Test
    fun `the screen draws the columns this order says, and the pill never breaks or takes a colour of its own`() {
        val screen = java.io.File("src/main/java/com/reau/app/ui/WeatherScreen.kt").readText()
        assertTrue(screen.contains("ForecastLayout.columns(pill != null, second != null)"))
        assertTrue(screen.contains("background(status.rainChip, CircleShape)"))
        assertTrue(screen.contains("Text(pill.written, style = MaterialTheme.typography.labelSmall, color = status.rainText, maxLines = 1, softWrap = false)"))
        assertTrue("no ruler is left", !screen.contains("RainRuler"))
    }

    @Test
    fun `the pill's tag comes before the clearing of its semantics, which would clear it too`() {
        // A tag after clearAndSetSemantics is cleared with the rest (28/11/2026: WeatherScreenTest did not find the pill on the emulator).
        val screen = java.io.File("src/main/java/com/reau/app/ui/WeatherScreen.kt").readText()
        assertTrue(screen.contains("Modifier.testTag(tag).clearAndSetSemantics { contentDescription = described }"))
        assertTrue("no tag after the clearing", !Regex("""clearAndSetSemantics \{[^}]*\}\.testTag""").containsMatchIn(screen))
    }
}
