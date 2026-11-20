package com.reau.app.ui

import com.reau.app.settings.REauSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android Auto DPI on the fit screen (plan 32, 20/11/2026): three choices, 180, 190 and 200, as segments (the same ones the other
 * choices of the app use), no counter. The screen is read from its source, as the other tests of this folder do (there is no Compose test on the JVM).
 */
class DensityChoiceTest {
    private val panel = File("src/main/java/com/reau/app/ui/FitPanel.kt").readText()
    private val block = panel.substringAfter("private fun DensityChoice(").substringBefore("\n}\n")

    @Test
    fun `the offered densities are the three of the settings, one segment each, tagged by its value`() {
        assertEquals(listOf(180, 190, 200), REauSettings.DENSITIES)
        assertTrue(block, block.contains("SegmentedRow("))
        assertTrue(block, block.contains("REauSettings.DENSITIES.map { it.toString() to \"density_\$it\" }"))
        assertTrue("the chosen segment is the setting's", block.contains("REauSettings.DENSITIES.indexOf(s.density)"))
        assertTrue("a tap picks a value of the list, not the index", block.contains("change(s.copy(density = REauSettings.DENSITIES[it]))"))
        assertTrue(block, block.contains("tag = \"density\""))
    }

    @Test
    fun `the block is shown above the tabs and no counter is left for the density`() {
        assertTrue(panel.indexOf("DensityChoice(s, change)") in 1 until panel.indexOf("TabRail("))
        assertFalse("no free counter", block.contains("FineStepper"))
        assertFalse("no free counter", panel.contains("REauSettings.DENSITY,") || panel.contains("STEP_DENSITY"))
    }

    @Test
    fun `the hint names the three values in every language`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val text = File("src/main/res/$folder/strings.xml").readText()
            val hint = Regex("""<string name="density_hint">(.*?)</string>""").find(text)?.groupValues?.get(1).orEmpty()
            for (value in REauSettings.DENSITIES) assertTrue("$folder: $hint", hint.contains(value.toString()))
            assertFalse("$folder: no range of the old counter", Regex("""\b(80|640)\b""").containsMatchIn(hint))
        }
    }
}
