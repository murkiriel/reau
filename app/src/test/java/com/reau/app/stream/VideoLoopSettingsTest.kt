package com.reau.app.stream

import com.reau.app.dash.BitrateMode
import com.reau.app.settings.REauSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the rider's settings decide of the stream, as the video loop receives it ([VideoLoop.Settings.withRider]): the bitrate is the rider's own and no
 * experimental switch is left to go from the settings to the loop (they went on 20/11/2026). A projection starts with it and a new stream takes it up again.
 */
class VideoLoopSettingsTest {
    private val loop = VideoLoop.Settings()

    @Test
    fun `the loop's settings have no experimental switch`() {
        val names = VideoLoop.Settings::class.java.declaredFields.map { it.name }
        for (gone in listOf("rtpPacing", "adaptiveBitrate", "encoderMuteWatch", "colorMatrix", "encoderLowLatency")) assertFalse(gone, gone in names)
    }

    @Test
    fun `the bitrate is the rider's`() {
        assertEquals(400_000, loop.withRider(REauSettings(streamBitrateBps = 400_000)).bitrateBps)
        assertEquals(700_000, loop.withRider(REauSettings(streamBitrateBps = 700_000)).bitrateBps)
    }

    @Test
    fun `the pace, the clock, the mode and the keyframe interval are the rider's`() {
        val s = loop.withRider(REauSettings(fpsCeiling = 3, clockSlowPercent = 2, bitrateMode = BitrateMode.VBR, keyframeSec = 2))
        assertEquals(3, s.ceilingFps)
        assertEquals(2, s.clockSlowPercent)
        assertEquals(BitrateMode.VBR, s.bitrateMode)
        assertEquals(2, s.keyframeSec)
        assertEquals(REauSettings.lightScreenFps(), s.lightScreenFps)
    }

    @Test
    fun `what is not the rider's is left as it was, the recording among it`() {
        val dir = File("somewhere")
        val s = VideoLoop.Settings(recordFrames = 600, recordDir = dir, recordRolling = true, windowMs = 1_234L).withRider(REauSettings())
        assertEquals(600, s.recordFrames)
        assertEquals(dir, s.recordDir)
        assertTrue(s.recordRolling)
        assertEquals(1_234L, s.windowMs)
    }
}
