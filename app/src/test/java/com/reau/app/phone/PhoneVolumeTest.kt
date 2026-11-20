package com.reau.app.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The joystick's volume step on the phone's audio (P32 Joy), through a hand-made [VolumeDevice]: the stream it moves (the music's, or the call's while a call
 * goes on, by the joystick's call state or by the audio mode), what it reports (the levels before and after, the most), the ends of the scale, and a phone that
 * refuses.
 */
class PhoneVolumeTest {
    private class FakeDevice(
        var music: Int = 10, var call: Int = 4, val musicMax: Int = 30, val callMax: Int = 7, var callMode: Boolean = false, var refuses: Boolean = false,
    ) : VolumeDevice {
        val adjusted = mutableListOf<Pair<VolumeStream, Int>>()

        override fun level(stream: VolumeStream) = if (stream == VolumeStream.MUSIC) music else call

        override fun max(stream: VolumeStream) = if (stream == VolumeStream.MUSIC) musicMax else callMax

        override fun adjust(stream: VolumeStream, step: Int) {
            if (refuses) throw SecurityException("not allowed")
            adjusted += stream to step
            if (stream == VolumeStream.MUSIC) music = (music + step).coerceIn(0, musicMax) else call = (call + step).coerceIn(0, callMax)
        }

        override fun inCallMode() = callMode
    }

    // ── the stream ──

    @Test
    fun `with no call the music's volume moves`() {
        assertEquals(VolumeStream.MUSIC, PhoneVolume.streamFor(CallState.IDLE, callMode = false))
    }

    @Test
    fun `while the joystick sees a call going on the call's volume moves`() {
        assertEquals(VolumeStream.CALL, PhoneVolume.streamFor(CallState.IN_CALL, callMode = false))
    }

    @Test
    fun `a call the joystick does not see, a voice app's or one with the switch off, is told by the phone's audio mode`() {
        assertEquals(VolumeStream.CALL, PhoneVolume.streamFor(CallState.IDLE, callMode = true))
        assertEquals(VolumeStream.CALL, PhoneVolume.streamFor(CallState.IN_CALL, callMode = true))
    }

    @Test
    fun `while the phone only rings the music's volume moves, the ringer is neither stream`() {
        assertEquals(VolumeStream.MUSIC, PhoneVolume.streamFor(CallState.RINGING, callMode = false))
    }

    // ── the step ──

    @Test
    fun `a step up raises the music one step and says the level after it out of the most`() {
        val device = FakeDevice(music = 11)
        val change = PhoneVolume(device).step(1, CallState.IDLE)
        assertEquals(VolumeChange(VolumeStream.MUSIC, before = 11, after = 12, max = 30), change)
        assertEquals(listOf(VolumeStream.MUSIC to 1), device.adjusted)
    }

    @Test
    fun `a step down lowers it one step`() {
        val device = FakeDevice(music = 11)
        assertEquals(VolumeChange(VolumeStream.MUSIC, 11, 10, 30), PhoneVolume(device).step(-1, CallState.IDLE))
        assertEquals(listOf(VolumeStream.MUSIC to -1), device.adjusted)
    }

    @Test
    fun `any step above zero is up and any other is down, one step each`() {
        val device = FakeDevice(music = 10)
        PhoneVolume(device).step(5, CallState.IDLE)
        PhoneVolume(device).step(-5, CallState.IDLE)
        PhoneVolume(device).step(0, CallState.IDLE)
        assertEquals(listOf(VolumeStream.MUSIC to 1, VolumeStream.MUSIC to -1, VolumeStream.MUSIC to -1), device.adjusted)
    }

    @Test
    fun `on a call the step goes to the call's volume and the music's stays`() {
        val device = FakeDevice(music = 11, call = 4)
        val change = PhoneVolume(device).step(1, CallState.IN_CALL)
        assertEquals(VolumeChange(VolumeStream.CALL, before = 4, after = 5, max = 7), change)
        assertEquals(listOf(VolumeStream.CALL to 1), device.adjusted)
        assertEquals(11, device.music)
    }

    @Test
    fun `with the phone's audio mode on a call the step goes to the call's volume though the joystick sees none`() {
        val device = FakeDevice(callMode = true)
        assertEquals(VolumeStream.CALL, PhoneVolume(device).step(-1, CallState.IDLE)?.stream)
        assertEquals(3, device.call)
    }

    @Test
    fun `at the end of the scale nothing changes and the report says so`() {
        val top = FakeDevice(music = 30)
        val change = PhoneVolume(top).step(1, CallState.IDLE)!!
        assertEquals(30, change.after)
        assertEquals("joystick volume up: music 30 -> 30 of 30 (the end, no change)", change.logLine(1))
        val bottom = FakeDevice(music = 0)
        assertEquals(0, PhoneVolume(bottom).step(-1, CallState.IDLE)?.after)
    }

    @Test
    fun `a phone that refuses gives no change, and nothing is thrown at the dash's thread`() {
        assertNull(PhoneVolume(FakeDevice(refuses = true)).step(1, CallState.IDLE))
    }

    // ── the log ──

    @Test
    fun `the ride log's line has the direction, the stream and the levels, and no more`() {
        assertEquals("joystick volume up: music 11 -> 12 of 30", VolumeChange(VolumeStream.MUSIC, 11, 12, 30).logLine(1))
        assertEquals("joystick volume down: call 5 -> 4 of 7", VolumeChange(VolumeStream.CALL, 5, 4, 7).logLine(-1))
    }
}
