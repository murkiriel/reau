package com.reau.app.dash.video

import android.media.MediaCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an output buffer of the encoder is, told from its flags and size: a frame, the SPS/PPS, or a sync frame. */
class DashEncoderOutputTest {
    private val config = MediaCodec.BUFFER_FLAG_CODEC_CONFIG
    private val key = MediaCodec.BUFFER_FLAG_KEY_FRAME

    @Test
    fun `a buffer with bytes and no config flag is a frame`() {
        assertTrue(DashEncoder.isFrame(hasBuffer = true, size = 1_200, flags = 0))
        assertTrue(DashEncoder.isFrame(true, 1, key))
    }

    @Test
    fun `the SPS and PPS buffer is no frame, with or without other flags`() {
        assertFalse(DashEncoder.isFrame(hasBuffer = true, size = 30, flags = config))
        assertFalse(DashEncoder.isFrame(true, 30, config or key))
    }

    @Test
    fun `an empty buffer, or none at all, is no frame`() {
        assertFalse(DashEncoder.isFrame(hasBuffer = true, size = 0, flags = 0))
        assertFalse(DashEncoder.isFrame(hasBuffer = false, size = 1_200, flags = 0))
    }

    @Test
    fun `only the key frame flag makes a sync frame`() {
        assertTrue(DashEncoder.isKeyFrame(key))
        assertTrue(DashEncoder.isKeyFrame(key or MediaCodec.BUFFER_FLAG_END_OF_STREAM))
        assertFalse(DashEncoder.isKeyFrame(0))
        assertFalse(DashEncoder.isKeyFrame(config))
    }
}
