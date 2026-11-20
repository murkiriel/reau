package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import com.reau.app.stream.VideoLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The experimental switch of the Qualcomm encoder's low-latency key ([com.reau.app.dash.video.VendorLowLatency]): off by default, stored on its own, known by
 * the start guard, said in the ride log, handed to the video loop and from it to the encoder, and shown in the settings in the three languages.
 * What the encoder does with it is VendorLowLatencyTest's.
 */
class EncoderLowLatencySwitchTest {
    private val on = REauSettings(experimentEncoderLowLatency = true)

    @Test
    fun `it is off by default`() {
        assertFalse(REauSettings().experimentEncoderLowLatency)
        assertFalse(VideoLoop.Settings().encoderLowLatency)
    }

    @Test
    fun `it is stored and read back on its own, and a phone that never stored it reads it off`() {
        val store = SettingsStore(KeystoreBackedPrefsTest.FakePrefs())
        assertFalse(store.load().experimentEncoderLowLatency)
        store.save(on)
        assertTrue(store.load().experimentEncoderLowLatency)
        assertEquals(on, store.load())
        store.save(REauSettings())
        assertFalse(store.load().experimentEncoderLowLatency)
        val old = KeystoreBackedPrefsTest.FakePrefs()
        old.edit().putInt("density", 200).apply()
        assertFalse(SettingsStore(old).load().experimentEncoderLowLatency)
    }

    @Test
    fun `sanitized keeps it, in a debug build and in a release`() {
        for (debug in listOf(true, false)) assertTrue("debug=$debug", on.sanitized(debug).experimentEncoderLowLatency)
    }

    @Test
    fun `the start guard knows it and switches it off`() {
        assertEquals(listOf("experimentEncoderLowLatency"), ExperimentsGuard.experimentsOn(on))
        assertEquals(REauSettings(), ExperimentsGuard.allOff(on))
    }

    @Test
    fun `the ride log says it, in the settings line and in the experiments line, and a change of it is told`() {
        assertTrue("experimentEncoderLowLatency=false" in SettingsInUse.line(REauSettings()))
        assertTrue("experimentEncoderLowLatency=true" in SettingsInUse.line(on))
        assertNull(SettingsInUse.experimentsLine(REauSettings()))
        val said = SettingsInUse.experimentsLine(on)!!
        assertTrue(said, "Qualcomm encoder asked not to hold frames" in said)
        assertEquals(listOf("experimentEncoderLowLatency: false -> true"), REauSettings().changesTo(on))
    }

    @Test
    fun `it restarts nothing, it counts from the next video`() {
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(REauSettings(), on))
    }

    @Test
    fun `it reaches the video loop alone, and off it does not`() {
        val loop = VideoLoop.Settings()
        assertFalse(loop.withRider(REauSettings()).encoderLowLatency)
        val s = loop.withRider(on)
        assertEquals(listOf(true, false, false, false, false), listOf(s.encoderLowLatency, s.colorMatrix, s.rtpPacing, s.adaptiveBitrate, s.encoderMuteWatch))
        assertFalse("the colour switch does not carry it", loop.withRider(REauSettings(experimentColorMatrix = true)).encoderLowLatency)
        assertFalse("and it does not carry the colour", s.colorMatrix)
    }

    @Test
    fun `the loop hands it to each encoder it builds, before the encoder is prepared`() {
        val loop = File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()
        val build = loop.substringAfter("private fun buildEncoder(").substringBefore("log(\"\${RideDiagnostics.ERROR}encoder prepare failed")
        assertTrue(build.contains("created.vendorLowLatency = settings.encoderLowLatency"))
        assertTrue("before prepare", build.indexOf("created.vendorLowLatency") in 0 until build.indexOf("created.prepare("))
    }

    @Test
    fun `the encoder carries the switch into its ask, and its format into the codec with the key only when the format says so`() {
        // MediaFormat is a stub in the JVM tests (setInteger does nothing), so the last links of the chain can only be read from the source.
        val encoder = File("src/main/java/com/reau/app/dash/video/DashEncoder.kt").readText()
        assertTrue("the switch is in the ask", encoder.contains("Ask(fpsHint, bitrateBps, iFrameIntervalSec, colorMatrix, vendorLowLatency, frame)"))
        assertTrue("a spec is made into a format with its own flag", encoder.contains("buildFormat(ask, spec.lowLatency, spec.bitrateMode, spec.color, spec.vendorLowLatency)"))
        assertTrue("the key is set from the flag of the format", encoder.contains("VendorLowLatency.keysFor(vendor).forEach { (k, v) -> setInteger(k, v) }"))
        assertTrue("the factory of the real codec gets the format of the spec", encoder.contains("startCodec(n, buildFormat(ask, spec), d)"))
    }

    @Test
    fun `the row has its title and line in the three languages, and the line says Experimental`() {
        val titles = mapOf(
            "values" to "Encoder low latency (Qualcomm)",
            "values-pt" to "Baixa latência do codificador (Qualcomm)",
            "values-es" to "Baja latencia del codificador (Qualcomm)",
        )
        for ((folder, title) in titles) {
            val xml = File("src/main/res/$folder/strings.xml").readText()
            assertTrue("$folder title", xml.contains("<string name=\"exp_encoder_low_latency_title\">$title</string>"))
            val sub = Regex("""<string name="exp_encoder_low_latency_sub">(.*?)</string>""").find(xml)?.groupValues?.get(1)
            assertTrue("$folder line", sub != null && sub.startsWith("Experimental") && "Qualcomm" in sub)
        }
        val screen = File("src/main/java/com/reau/app/ui/SettingsExperimental.kt").readText()
        assertTrue(Regex("""s\.experimentEncoderLowLatency,\s*\{ change\(s\.copy\(experimentEncoderLowLatency = it\)\) \}, "exp_encoder_low_latency"""").containsMatchIn(screen))
    }
}
