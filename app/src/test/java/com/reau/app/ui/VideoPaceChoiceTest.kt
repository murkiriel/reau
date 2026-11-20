package com.reau.app.ui

import com.reau.app.settings.VideoPace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The video pace on the settings screen (plan 32, 20/11/2026): two segments, REau first and Original after, the same row the other choices use, at the top of
 * "Perfis de vídeo", with its (i) and the "NA HORA" chip, and the line of "Detalhes" that follows the pace. The screens are read from their source, as the other tests of this folder do (there is
 * no Compose test on the JVM); the click itself is SettingsScreenVideoTest's, on a device.
 */
class VideoPaceChoiceTest {
    private val src = File("src/main")
    private val choice = File(src, "java/com/reau/app/ui/SettingsVideoPace.kt").readText()
    private val block = choice.substringAfter("internal fun VideoPaceChoice(")
    private val screen = File(src, "java/com/reau/app/ui/SettingsScreen.kt").readText()
    private fun text(folder: String, name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(File(src, "res/$folder/strings.xml").readText())?.groupValues?.get(1)

    @Test
    fun `the row is a segmented one over the two paces, REau first, tagged by the pace and by its value`() {
        assertEquals(listOf(VideoPace.REAU, VideoPace.ORIGINAL), VideoPace.entries)
        assertTrue(block, block.contains("SegmentedRow("))
        assertTrue(block, block.contains("VideoPace.entries.map { stringResource(names.getValue(it)) to \"video_pace_\${it.name}\" }"))
        assertTrue("the chosen segment is the setting's", block.contains("VideoPace.entries.indexOf(s.videoPace)"))
        assertTrue("a tap picks a pace of the list", block.contains("change(s.copy(videoPace = VideoPace.entries[it]))"))
        assertTrue(block, block.contains("tag = \"video_pace\""))
        assertTrue("REau first, Original after", block.indexOf("VideoPace.REAU to R.string.video_pace_reau") in 0 until block.indexOf("VideoPace.ORIGINAL to R.string.video_pace_original"))
        assertEquals("the tags the tests and the device read", listOf("video_pace_REAU", "video_pace_ORIGINAL"), VideoPace.entries.map { "video_pace_${it.name}" })
    }

    @Test
    fun `it has the subtitle, the (i) with its three sentences and the chip of what takes effect at once`() {
        assertTrue(block, block.contains("sub = stringResource(R.string.video_pace_sub)"))
        assertTrue(block, block.contains("info = Info(stringResource(R.string.video_pace_title), stringResource(R.string.video_pace_info))"))
        assertTrue("the chip: the pace is taken with the projection on", block.contains("now = true"))
    }

    @Test
    fun `it is the first row of the video profiles group, before the profiles, and the group gets the change`() {
        val group = screen.substringAfter("private fun VideoProfilesGroup").substringBefore("tag = \"group_video_profiles\"")
        val order = listOf("VideoPaceChoice(s, change)", "\"profile_a\"", "\"profile_b\"", "NavProfileRows()", "ProfileDetailsRow(s)").map { group.indexOf(it) }
        assertTrue("all are there: $order", order.all { it >= 0 })
        assertEquals("in that order", order.sorted(), order)
        assertTrue(screen.contains("VideoProfilesGroup(settings, change)"))
        assertTrue(screen.contains("private fun VideoProfilesGroup(s: REauSettings, change: (REauSettings) -> Unit)"))
    }

    @Test
    fun `it is in the release build as in the debug one, not behind the flag`() {
        assertFalse(choice.contains("BuildConfig.DEBUG"))
        assertFalse(screen.substringAfter("private fun VideoProfilesGroup").substringBefore("tag = \"group_video_profiles\"").contains("BuildConfig.DEBUG"))
    }

    @Test
    fun `the details line follows the pace of the settings`() {
        val video = File(src, "java/com/reau/app/ui/SettingsVideo.kt").readText()
        assertTrue(video.contains("s.videoPace.fullFps, s.videoPace.reducedFps"))
        assertFalse("not the old fixed ceiling", video.contains("s.fpsCeiling"))
    }

    @Test
    fun `the title, the subtitle, the options and the info are in the three languages, as asked`() {
        val expected = mapOf(
            "values-pt" to listOf("Ritmo do vídeo", "REau: 6 fps no A e 3 no B. Original: 4 e 2, mais leve."),
            "values" to listOf("Video pace", "REau: 6 fps on A and 3 on B. Original: 4 and 2, lighter."),
            "values-es" to listOf("Ritmo del video", "REau: 6 fps en el A y 3 en el B. Original: 4 y 2, más liviano."),
        )
        for ((folder, want) in expected) {
            assertEquals(folder, want, listOf(text(folder, "video_pace_title"), text(folder, "video_pace_sub")))
            assertEquals(folder, "REau", text(folder, "video_pace_reau"))
            assertEquals(folder, "Original", text(folder, "video_pace_original"))
            val info = text(folder, "video_pace_info")!!
            assertTrue("$folder: $info", info.isNotBlank() && "6" in info && "3" in info && "4" in info && "2" in info)
        }
        assertTrue(text("values-es", "video_pace_info")!!.contains("tablero"))
        assertFalse(text("values-es", "video_pace_info")!!.contains("panel"))
    }

    @Test
    fun `the info says what A and B are, that REau is the default, that Original is lighter and that the change is at once`() {
        val pt = text("values-pt", "video_pace_info")!!
        for (part in listOf("perfil A", "o B", "O REau é o padrão", "Original é mais leve", "Wi-Fi", "A troca vale na hora")) assertTrue("$part in $pt", part in pt)
        val en = text("values", "video_pace_info")!!
        for (part in listOf("Profile A", "REau is the default", "Original is lighter", "Wi-Fi", "takes effect right away")) assertTrue("$part in $en", part in en)
        val es = text("values-es", "video_pace_info")!!
        for (part in listOf("perfil A", "REau es el predeterminado", "Original es más liviano", "Wi-Fi", "El cambio vale al instante")) assertTrue("$part in $es", part in es)
    }
}
