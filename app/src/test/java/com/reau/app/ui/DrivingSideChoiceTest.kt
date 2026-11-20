package com.reau.app.ui

import com.reau.app.settings.DrivingSideMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The driving side on the settings screen (20/11/2026): three segments, Automatic, Right-hand and Left-hand, the same row the other choices use, in "No painel",
 * with its (i) and the "NA HORA" chip. Read from the source, as the other tests of this folder do; the click itself is for a device.
 */
class DrivingSideChoiceTest {
    private val src = File("src/main")
    private val choice = File(src, "java/com/reau/app/ui/SettingsDrivingSide.kt").readText()
    private val block = choice.substringAfter("internal fun DrivingSideChoice(")
    private val screen = File(src, "java/com/reau/app/ui/SettingsScreen.kt").readText()
    private fun text(folder: String, name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(File(src, "res/$folder/strings.xml").readText())?.groupValues?.get(1)

    @Test
    fun `the row is a segmented one over the three modes, automatic first, tagged by the row and by its value`() {
        assertEquals(listOf(DrivingSideMode.AUTO, DrivingSideMode.RIGHT_HAND, DrivingSideMode.LEFT_HAND), DrivingSideMode.entries)
        assertTrue(block, block.contains("SegmentedRow("))
        assertTrue(block, block.contains("DrivingSideMode.entries.map { stringResource(names.getValue(it)) to \"driving_side_\${it.name}\" }"))
        assertTrue(block, block.contains("DrivingSideMode.entries.indexOf(s.drivingSideMode)"))
        assertTrue(block, block.contains("change(s.copy(drivingSideMode = DrivingSideMode.entries[it]))"))
        assertTrue(block, block.contains("tag = \"driving_side\""))
        assertEquals(
            listOf("driving_side_AUTO", "driving_side_RIGHT_HAND", "driving_side_LEFT_HAND"), DrivingSideMode.entries.map { "driving_side_${it.name}" },
        )
    }

    @Test
    fun `it has the subtitle, the (i) and the chip of what takes effect at once`() {
        assertTrue(block, block.contains("sub = stringResource(R.string.driving_side_sub)"))
        assertTrue(block, block.contains("info = Info(stringResource(R.string.driving_side_title), stringResource(R.string.driving_side_info))"))
        assertTrue(block, block.contains("now = true"))
    }

    @Test
    fun `it is in the dash group, in a debug build as in a release one, and the group gets the change`() {
        val group = screen.substringAfter("private fun OnDashGroup").substringBefore("tag = \"group_on_dash\"")
        assertTrue(group, group.contains("DrivingSideChoice(s, change)"))
        assertFalse(choice.contains("BuildConfig.DEBUG"))
        assertFalse(group.contains("BuildConfig.DEBUG"))
    }

    @Test
    fun `the title, the options and the info are in the three languages`() {
        val expected = mapOf(
            "values-pt" to listOf("Lado da via", "Automático", "Mão direita", "Mão inglesa"),
            "values" to listOf("Driving side", "Automatic", "Right-hand", "Left-hand"),
            "values-es" to listOf("Lado de circulación", "Automático", "Por la derecha", "Por la izquierda"),
        )
        for ((folder, want) in expected) {
            val got = listOf("driving_side_title", "driving_side_auto", "driving_side_right", "driving_side_left").map { text(folder, it) }
            assertEquals(folder, want, got)
            assertTrue(folder, !text(folder, "driving_side_sub").isNullOrBlank() && !text(folder, "driving_side_info").isNullOrBlank())
        }
    }

    @Test
    fun `the info says the automatic uses the network's country, what the side changes and what the manual pick is for`() {
        val pt = text("values-pt", "driving_side_info")!!
        for (part in listOf("país da rede de celular", "setas que o Android Auto não define", "escolha manual")) assertTrue("$part in $pt", part in pt)
        val en = text("values", "driving_side_info")!!
        for (part in listOf("country of the mobile network", "arrows that Android Auto does not define", "by hand")) assertTrue("$part in $en", part in en)
        val es = text("values-es", "driving_side_info")!!
        for (part in listOf("país de la red móvil", "flechas que Android Auto no define", "a mano")) assertTrue("$part in $es", part in es)
    }

    @Test
    fun `no title or line of the row says experimental or test, so it is not marked red`() {
        for (name in listOf("driving_side_title", "driving_side_sub", "driving_side_auto", "driving_side_right", "driving_side_left")) {
            assertFalse(name, ExperimentalMark.marks(text("values-pt", name)))
        }
    }
}
