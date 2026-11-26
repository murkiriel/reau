package com.reau.app.settings

import com.reau.app.dash.video.VideoEncoder
import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import com.reau.app.stream.VideoLoop
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The choice "Codificador de vídeo (experimental)" ([REauSettings.videoEncoder]), the test of the encoder the owner decided on 26/11/2026: the phone's own by default, kept on its own key,
 * in the ride log's settings line, read when the video opens (nothing to restart for a change), on the settings screen in the four languages. What each choice does to the
 * encoders is [com.reau.app.dash.video.VideoEncoderChoiceTest]'s.
 */
class VideoEncoderSettingTest {
    private val software = REauSettings(videoEncoder = VideoEncoder.SOFTWARE)

    @Test
    fun `it is the phone's own by default, on a new install, with nothing stored and after sanitizing`() {
        assertEquals(VideoEncoder.PHONE, REauSettings().videoEncoder)
        assertEquals(VideoEncoder.PHONE, SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().videoEncoder)
        assertEquals(VideoEncoder.PHONE, REauSettings().sanitized().videoEncoder)
        assertEquals("sanitizing does not take the rider's choice away", VideoEncoder.SOFTWARE, software.sanitized().videoEncoder)
    }

    @Test
    fun `the choice is stored under its own key by name and read back, both ways`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(software)
        assertEquals("SOFTWARE", prefs.map["video_encoder"])
        assertEquals(VideoEncoder.SOFTWARE, SettingsStore(prefs).load().videoEncoder)
        SettingsStore(prefs).save(REauSettings())
        assertEquals("PHONE", prefs.map["video_encoder"])
        assertEquals(VideoEncoder.PHONE, SettingsStore(prefs).load().videoEncoder)
    }

    @Test
    fun `a phone that stored the other settings but never this one, or a name that is no encoder, reads the phone's own`() {
        val older = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putInt("density", 200).putBoolean("pace_packets", false).apply() }
        assertEquals(VideoEncoder.PHONE, SettingsStore(older).load().videoEncoder)
        val odd = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putString("video_encoder", "HARDWARE_PLUS").apply() }
        assertEquals(VideoEncoder.PHONE, SettingsStore(odd).load().videoEncoder)
    }

    @Test
    fun `it does not touch the key of the other choices`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(software)
        assertEquals(true, prefs.map["pace_packets"])
        assertEquals(true, prefs.map["resume_after_kill"])
        assertTrue(SettingsStore(prefs).load().pacePackets)
    }

    @Test
    fun `the ride log's settings line says it, and a change of it is told`() {
        assertTrue("videoEncoder=PHONE" in SettingsInUse.line(REauSettings()))
        assertTrue("videoEncoder=SOFTWARE" in SettingsInUse.line(software))
        assertEquals(listOf("videoEncoder: PHONE -> SOFTWARE"), REauSettings().changesTo(software))
        assertTrue("videoEncoder" in SettingsInUse.LOGGED)
    }

    @Test
    fun `a change of it restarts nothing, the stream reads it when it opens`() {
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(REauSettings(), software))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(software, REauSettings()))
    }

    @Test
    fun `a stream started with the rider's settings takes the choice to its encoder`() {
        assertEquals(VideoEncoder.PHONE, VideoLoop.Settings().videoEncoder)
        assertEquals(VideoEncoder.PHONE, VideoLoop.Settings().withRider(REauSettings()).videoEncoder)
        assertEquals(VideoEncoder.SOFTWARE, VideoLoop.Settings().withRider(software).videoEncoder)
        assertEquals("only the encoder changes: the pace, the ceiling and the picture are the same", VideoLoop.Settings().withRider(REauSettings()).copy(videoEncoder = VideoEncoder.SOFTWARE), VideoLoop.Settings().withRider(software))
    }

    @Test
    fun `its comment says the phone's own by default and that it counts from the next stream`() {
        val source = File("src/main/java/com/reau/app/settings/REauSettings.kt").readText()
        val doc = source.substringBefore("val videoEncoder: VideoEncoder = VideoEncoder.PHONE,").substringAfterLast("/**")
        assertTrue(doc, "The phone's own by default" in doc)
        assertTrue(doc, "next stream" in doc)
    }

    @Test
    fun `the choice is on the settings screen, after the packets one, with no now chip because it counts from the next stream`() {
        val screen = File("src/main/java/com/reau/app/ui/SettingsScreen.kt").readText()
        assertTrue(screen, screen.indexOf("{ PacePackets(s, change) },") in 0 until screen.indexOf("{ VideoEncoderChoice(s, change) },"))
        val row = File("src/main/java/com/reau/app/ui/SettingsNoDashStop.kt").readText().substringAfter("internal fun VideoEncoderChoice(")
        assertTrue(row, "VideoEncoder.entries.map { stringResource(names.getValue(it)) to \"video_encoder_\${it.name}\" }," in row)
        assertTrue(row, "{ change(s.copy(videoEncoder = VideoEncoder.entries[it])) }," in row)
        assertTrue(row, "tag = \"video_encoder\"," in row)
        assertTrue(row, "Info(stringResource(R.string.video_encoder_title), stringResource(R.string.video_encoder_info))" in row)
        assertFalse("it does not apply at once", "now = true" in row)
    }

    private fun string(folder: String, name: String) =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]

    @Test
    fun `the texts are in the four languages and the title says experimental`() {
        assertEquals("Video encoder (experimental)", string("values", "video_encoder_title"))
        assertEquals("Codificador de vídeo (experimental)", string("values-pt", "video_encoder_title"))
        assertEquals("Codificador de video (experimental)", string("values-es", "video_encoder_title"))
        assertTrue(string("values-hi", "video_encoder_title").endsWith("(प्रायोगिक)"))
        for (folder in listOf("values", "values-pt", "values-es", "values-hi")) {
            for (name in listOf("video_encoder_sub", "video_encoder_phone", "video_encoder_software", "video_encoder_info")) {
                assertTrue("$folder/$name", string(folder, name).isNotBlank())
            }
            assertFalse("$folder: the line under the title is not itself marked", string(folder, "video_encoder_sub").contains("xperiment"))
        }
    }

    @Test
    fun `the long text says the other encoder is Android's software one, that the phone's own stays without one, and that it counts from the next time the video opens`() {
        assertTrue(string("values", "video_encoder_info").let { "software encoder" in it && "if the phone has none, its own stays" in it && "next time the video opens" in it })
        assertTrue(string("values-pt", "video_encoder_info").let { "codificador de software do Android" in it && "se o celular não tiver um, fica o dele" in it && "próxima vez que o vídeo abrir" in it })
        assertTrue(string("values-es", "video_encoder_info").let { "codificador de software de Android" in it && "si el teléfono no tiene uno, se queda el suyo" in it && "próxima vez que se abra el video" in it })
    }
}
