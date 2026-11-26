package com.reau.app.dash

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notice of the stuck dash (26/11/2026, the owner's field test): turning the ignition off and on quickly does not free the dash, it stays on the same channel refusing; waiting about
 * 20 seconds does (it starts from zero on another channel and REau joins it in 1.4 s). So the notification, the error line and the phrase in the headset all say to wait, in the four languages.
 */
class DashStuckWaitTextsTest {
    private val res = File("src/main/res")
    private val folders = listOf("values", "values-pt", "values-es", "values-hi")

    private fun text(folder: String, name: String): String =
        Regex("""<string name="$name">(.*?)</string>""").find(File(res, "$folder/strings.xml").readText())?.groupValues?.get(1) ?: error("no $name in $folder")

    @Test
    fun `the notification, the error line and the phrase of the stuck dash say to wait 20 seconds in the four languages`() {
        for (folder in folders) for (name in listOf("notif_text_dash_stuck", "dash_err_dash_stuck", "voice_dash_gone")) {
            val body = text(folder, name)
            assertTrue("$folder $name: $body", Regex("""(?<!\d)20(?!\d)""").containsMatchIn(body))
            assertFalse("$folder $name still says 30: $body", Regex("""(?<!\d)30(?!\d)""").containsMatchIn(body))
        }
    }

    @Test
    fun `the notification also says that a quick off and on does not clear it`() {
        assertTrue(text("values-pt", "notif_text_dash_stuck").contains("rápido não destrava"))
        assertTrue(text("values", "notif_text_dash_stuck").contains("quick off and on does not clear it"))
        assertTrue(text("values-es", "notif_text_dash_stuck").contains("rápido no lo destraba"))
    }
}
