package com.reau.app.dash.video

import com.reau.app.dash.video.EncoderOrder.Candidate
import com.reau.app.stream.VideoLoop
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The experimental test of the video's encoder (the owner's decision of 26/11/2026): the phone's own order, which is what the app has always done and the default, or Android's software
 * encoder first, found in the phone's list and not by one fixed name, with the phone's own order behind it, and a line in the log when the phone has none.
 */
class VideoEncoderChoiceTest {
    private fun c(name: String, hardware: Boolean = true, size: Boolean? = true, baseline: Boolean? = true) = Candidate(name, hardware, size, baseline)

    // A MediaTek phone as the pilots' (the hardware encoder first, then Android's software one), and a phone that lists a vendor software encoder but not Android's.
    private val mediaTek = listOf(c("c2.mtk.avc.encoder"), c("c2.android.avc.encoder", hardware = false), c("OMX.google.h264.encoder", hardware = false))
    private val vendorOnly = listOf(c("c2.mtk.avc.encoder"), c("c2.vendor.avc.sw.encoder", hardware = false))

    private fun pick(preference: VideoEncoder, candidates: List<Candidate>) =
        EncoderOrder.pick(preference, EncoderOrder.names(candidates), EncoderOrder.softwareNames(candidates))

    @Test
    fun `the default is the phone's own and it is exactly the order of today, with nothing to say`() {
        assertEquals(VideoEncoder.PHONE, VideoEncoder.entries.first())
        val pick = pick(VideoEncoder.PHONE, mediaTek)
        assertEquals(EncoderOrder.names(mediaTek), pick.names)
        assertEquals(listOf("c2.mtk.avc.encoder", "c2.android.avc.encoder", "OMX.google.h264.encoder"), pick.names)
        assertNull(pick.note)
    }

    @Test
    fun `the software choice puts Android's software encoder first and keeps the phone's own order behind it`() {
        val pick = pick(VideoEncoder.SOFTWARE, mediaTek)
        assertEquals(listOf("c2.android.avc.encoder", "OMX.google.h264.encoder", "c2.mtk.avc.encoder"), pick.names)
        assertEquals("software encoder asked: c2.android.avc.encoder first", pick.note)
    }

    @Test
    fun `an encoder that fails to start still leaves the next one, the software choice never drops the hardware one`() {
        assertTrue("c2.mtk.avc.encoder" in pick(VideoEncoder.SOFTWARE, mediaTek).names)
        assertEquals(mediaTek.map { it.name }.toSet(), pick(VideoEncoder.SOFTWARE, mediaTek).names.toSet())
        assertEquals("no encoder twice", pick(VideoEncoder.SOFTWARE, mediaTek).names.size, pick(VideoEncoder.SOFTWARE, mediaTek).names.distinct().size)
    }

    @Test
    fun `the software encoder is found by the list and not by one fixed name`() {
        val renamed = listOf(c("c2.qti.avc.encoder"), c("c2.android.h264.future.encoder", hardware = false))
        assertEquals(listOf("c2.android.h264.future.encoder"), EncoderOrder.softwareNames(renamed))
        assertEquals("c2.android.h264.future.encoder", pick(VideoEncoder.SOFTWARE, renamed).names.first())
    }

    @Test
    fun `with no software encoder of Android in the list it falls back to the phone's own and the log says so`() {
        val pick = pick(VideoEncoder.SOFTWARE, vendorOnly)
        assertEquals("a vendor's software encoder is not Android's", emptyList<String>(), EncoderOrder.softwareNames(vendorOnly))
        assertEquals(EncoderOrder.names(vendorOnly), pick.names)
        assertEquals("software encoder asked, none in the phone's list: the phone's own is used", pick.note)
        assertEquals("an empty list too", emptyList<String>(), pick(VideoEncoder.SOFTWARE, emptyList()).names)
    }

    @Test
    fun `a software encoder that does not take the picture or Baseline is not found, and a hardware one named like Android's is not software`() {
        assertEquals(emptyList<String>(), EncoderOrder.softwareNames(listOf(c("c2.android.avc.encoder", hardware = false, size = false))))
        assertEquals(emptyList<String>(), EncoderOrder.softwareNames(listOf(c("c2.android.avc.encoder", hardware = false, baseline = false))))
        assertEquals(emptyList<String>(), EncoderOrder.softwareNames(listOf(c("c2.android.avc.encoder", hardware = true))))
        assertEquals("one whose capabilities cannot be read is still worth a try", listOf("c2.android.avc.encoder"),
            EncoderOrder.softwareNames(listOf(c("c2.android.avc.encoder", hardware = false, size = null, baseline = null))))
    }

    @Test
    fun `the codec's own namespace goes before the older one whatever order the phone lists them in`() {
        val old = listOf(c("OMX.google.h264.encoder", hardware = false), c("c2.android.avc.encoder", hardware = false))
        assertEquals(listOf("c2.android.avc.encoder", "OMX.google.h264.encoder"), EncoderOrder.softwareNames(old))
    }

    @Test
    fun `the encoder reads the rider's choice, the stream hands it over and a rebuilt encoder keeps it except after the software one failed in this session`() {
        val encoder = File("src/main/java/com/reau/app/dash/video/DashEncoder.kt").readText()
        assertTrue(encoder.contains("var preference: VideoEncoder = VideoEncoder.PHONE"))
        assertTrue(encoder.contains("val pick = encoderNames()"))
        assertTrue("the names tried are the pick's, the system's own pick last", encoder.contains("val names: List<String?> = pick.names + null"))
        assertTrue(encoder.contains("EncoderOrder.pick(preference, names(phone), softwareNames)"))
        assertTrue("the list of the phone is still looked up once per process, by the same key", encoder.contains("CodecNames.pick(\"dash encoders \${frame.width}x\${frame.height} baseline\")"))
        val loop = File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()
        // buildEncoder is the one place an encoder is made, for the start and for a rebuild alike.
        assertEquals(1, Regex("""created\.preference = settings\.videoEncoder""").findAll(loop).count())
        assertTrue(loop.contains("videoEncoder = rider.videoEncoder,"))
        assertTrue(loop.substringAfter("private fun buildEncoder(").substringBefore("companion object").contains("created.preference = settings.videoEncoder"))
    }

    @Test
    fun `the software encoder is asked for constant bitrate when the caller names none, and the rate the codec offers is what is sent`() {
        val encoder = File("src/main/java/com/reau/app/dash/video/DashEncoder.kt").readText()
        assertTrue(encoder.contains("val wanted = if (preference == VideoEncoder.SOFTWARE) bitrateMode ?: BitrateMode.CBR else bitrateMode"))
        assertTrue(encoder.contains("val rate = rateMode(wanted) { mode -> modeOffered(name, mode) }"))
        // The app's own settings always say CBR, and Android's software encoder offers it: asked CBR, sent CBR.
        assertEquals(DashEncoder.RateMode(2, "CBR"), DashEncoder.rateMode(com.reau.app.dash.BitrateMode.CBR) { true })
        // A hardware encoder that does not offer it (the pilots' MediaTek) is sent VBR, and the line says so.
        assertEquals(DashEncoder.RateMode(1, "VBR (CBR not offered)"), DashEncoder.rateMode(com.reau.app.dash.BitrateMode.CBR) { it != 2 })
    }

    @Test
    fun `a software encoder that opened and then failed is not asked for again in the session, the phone's own is`() {
        assertEquals(VideoEncoder.PHONE, VideoLoop.encoderAfterFailure(VideoEncoder.SOFTWARE, softwareRan = true))
        assertEquals("the phone's own stays", VideoEncoder.PHONE, VideoLoop.encoderAfterFailure(VideoEncoder.PHONE, softwareRan = false))
        assertEquals("the phone's own failing says nothing of the software one", VideoEncoder.PHONE, VideoLoop.encoderAfterFailure(VideoEncoder.PHONE, softwareRan = true))
        assertEquals("the software one asked, but the phone's own was the one running (no software in the list)", VideoEncoder.SOFTWARE,
            VideoLoop.encoderAfterFailure(VideoEncoder.SOFTWARE, softwareRan = false))
    }

    @Test
    fun `the loop drops the software choice only when the failed encoder makes the rebuild, and says so in the log`() {
        val loop = File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()
        val failed = loop.substringAfter("if (rebuildDue(failedPolls)) {").substringBefore("val timed = ")
        assertTrue(failed, failed.indexOf("stopAskingForSoftware()") in 0 until failed.indexOf("rebuild(g.rebuildAction())"))
        assertEquals("only the failure path drops it, not the governor's rebuild", 1, Regex("""stopAskingForSoftware\(\)""").findAll(loop.substringBefore("private fun stopAskingForSoftware")).count())
        val drop = loop.substringAfter("private fun stopAskingForSoftware() {").substringBefore("private fun logThermal")
        assertTrue(drop, "encoderAfterFailure(settings.videoEncoder, encoder?.softwareInUse == true)" in drop)
        assertTrue(drop, "settings = settings.copy(videoEncoder = next)" in drop)
        assertTrue(drop, "software encoder failed: the phone's own from now on" in drop)
        val encoder = File("src/main/java/com/reau/app/dash/video/DashEncoder.kt").readText()
        assertTrue("the encoder tells whether the one that opened is a software one", "softwareInUse = c.name in softwareNames" in encoder)
        assertTrue("the list it checks is the phone's list of software encoders", "softwareNames = names(software)" in encoder)
    }
}
