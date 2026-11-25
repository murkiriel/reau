package com.reau.app.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * For the reports of Bluetooth trouble, the ride log says when an audio device of Bluetooth comes or goes (with what it announces it can do), where the
 * sound goes at each change and the headset link's state, and says each only once. Only kinds, numbers and counts: never a device's name, its address or
 * its id (the facts a line is made of have no field for them). The Android side that feeds these lines is read in [com.reau.app.service.AudioDeviceWatchIsWiredTest].
 */
class AudioDeviceLinesTest {
    private val speaker = AudioDeviceFacts(type = 2, sink = true)
    private val a2dp = AudioDeviceFacts(type = 8, sink = true, sampleRates = listOf(48000, 44100), channelCounts = listOf(2), encodings = listOf(2, 10))
    private val scoOut = AudioDeviceFacts(type = 7, sink = true, sampleRates = listOf(8000, 16000), channelCounts = listOf(1), encodings = listOf(2))
    private val scoIn = AudioDeviceFacts(type = 7, sink = false, sampleRates = listOf(16000), channelCounts = listOf(1))

    /** A log past its start (the first call about devices, with only the phone's own speaker). */
    private fun started(): AudioDeviceLines.Log = AudioDeviceLines.Log().also { it.devicesAdded(listOf(speaker)) }

    @Test
    fun `a device of Bluetooth that comes says its kind, what it announces and how many are there now`() {
        assertEquals(
            "bluetooth device added: A2DP out (rates 44100/48000, channels 2, encodings PCM16/AAC_LC) - now 1 bluetooth output(s), 0 input(s)",
            started().devicesAdded(listOf(a2dp)),
        )
    }

    @Test
    fun `a device that goes says its kind and how many are left`() {
        val log = started()
        log.devicesAdded(listOf(a2dp))
        assertEquals("bluetooth device removed: A2DP out - now 0 bluetooth output(s), 0 input(s)", log.devicesRemoved(listOf(a2dp)))
    }

    @Test
    fun `each kind of Bluetooth audio has its words, and the two directions of the headset link are told apart`() {
        val expected = mapOf(7 to "SCO", 8 to "A2DP", 23 to "hearing aid", 26 to "BLE headset", 27 to "BLE speaker", 30 to "BLE broadcast")
        expected.forEach { (type, words) ->
            assertTrue(type.toString(), AudioDeviceLines.isBluetooth(type))
            assertEquals("$words out (nothing announced)", AudioDeviceLines.describe(AudioDeviceFacts(type, sink = true)))
            assertEquals("$words in (nothing announced)", AudioDeviceLines.describe(AudioDeviceFacts(type, sink = false)))
        }
        assertEquals("SCO in", AudioDeviceLines.kindAndDirection(scoIn))
    }

    @Test
    fun `a kind the list does not know is a generic name with its number, never a name of the device`() {
        assertEquals("type 99", AudioDeviceLines.kindName(99))
        assertEquals("type 99 out (nothing announced)", AudioDeviceLines.describe(AudioDeviceFacts(99, sink = true)))
        assertEquals("type 99", AudioDeviceLines.routeName(99))
        assertEquals("BLUETOOTH_A2DP", AudioDeviceLines.routeName(8))
        assertEquals("BLE_BROADCAST", AudioDeviceLines.routeName(30))
        assertEquals("BUILTIN_SPEAKER_SAFE", AudioDeviceLines.routeName(24))
        assertFalse(AudioDeviceLines.isBluetooth(99))
    }

    @Test
    fun `the devices that are not Bluetooth are not told, coming or going`() {
        val log = started()
        val wired = AudioDeviceFacts(type = 3, sink = true)
        val usb = AudioDeviceFacts(type = 22, sink = true)
        assertNull(log.devicesAdded(listOf(wired, usb)))
        assertNull(log.devicesRemoved(listOf(wired, usb)))
    }

    @Test
    fun `the devices that Android lists when the callback is registered are one line, the start`() {
        val log = AudioDeviceLines.Log()
        assertEquals(
            "bluetooth audio at start: A2DP out (rates 44100/48000, channels 2, encodings PCM16/AAC_LC); " +
                "SCO out (rates 8000/16000, channels 1, encodings PCM16); SCO in (rates 16000, channels 1) - 2 bluetooth output(s), 1 input(s)",
            log.devicesAdded(listOf(speaker, a2dp, scoOut, scoIn)),
        )
        // The next call is not a start any more.
        assertTrue(log.devicesAdded(listOf(scoIn))!!.startsWith("bluetooth device added: SCO in"))
    }

    @Test
    fun `a start with no Bluetooth device says so once, and what comes later is told as coming`() {
        val log = AudioDeviceLines.Log()
        assertEquals("bluetooth audio at start: no device", log.devicesAdded(listOf(speaker)))
        assertNull(log.devicesAdded(listOf(speaker)))
        assertTrue(log.devicesAdded(listOf(a2dp))!!.startsWith("bluetooth device added: A2DP out"))
    }

    @Test
    fun `devices that come together in one call are one line`() {
        val line = started().devicesAdded(listOf(scoOut, scoIn))
        assertEquals(
            "bluetooth devices added: SCO out (rates 8000/16000, channels 1, encodings PCM16); SCO in (rates 16000, channels 1) - now 1 bluetooth output(s), 1 input(s)",
            line,
        )
    }

    @Test
    fun `a device that goes more times than it came does not make the count go below zero`() {
        val log = started()
        assertEquals("bluetooth device removed: A2DP out - now 0 bluetooth output(s), 0 input(s)", log.devicesRemoved(listOf(a2dp)))
        log.devicesAdded(listOf(a2dp, a2dp))
        assertEquals("bluetooth device removed: A2DP out - now 1 bluetooth output(s), 0 input(s)", log.devicesRemoved(listOf(a2dp)))
    }

    @Test
    fun `the rates, channels and encodings are in order, without repeats, and the long ones are cut with a count`() {
        val many = AudioDeviceFacts(
            type = 8, sink = true, sampleRates = listOf(48000, 8000, 16000, 24000, 32000, 44100, 96000, 48000),
            channelCounts = listOf(2, 1, 2), encodings = listOf(10, 2, 99),
        )
        assertEquals("A2DP out (rates 8000/16000/24000/32000/44100/48000/+1, channels 1/2, encodings PCM16/AAC_LC/enc 99)", AudioDeviceLines.describe(many))
    }

    @Test
    fun `the route line says where the music and the call go, the mode and the headset link`() {
        assertEquals(
            "route: media -> BLUETOOTH_A2DP, call -> BUILTIN_EARPIECE, mode=NORMAL sco=false",
            AudioDeviceLines.routeLine(AudioRoute(media = listOf(8), call = listOf(1), mode = 0, scoOn = false)),
        )
        assertEquals(
            "route: media -> BLUETOOTH_SCO, call -> BLUETOOTH_SCO, mode=IN_COMMUNICATION sco=true",
            AudioDeviceLines.routeLine(AudioRoute(media = listOf(7), call = listOf(7), mode = 3, scoOn = true)),
        )
        assertTrue(AudioDeviceLines.routeLine(AudioRoute(listOf(2), listOf(2), mode = 1, scoOn = false)).contains("mode=RINGTONE"))
        assertTrue(AudioDeviceLines.routeLine(AudioRoute(listOf(2), listOf(2), mode = 2, scoOn = false)).contains("mode=IN_CALL"))
    }

    @Test
    fun `an end of the route that Android was not asked says unknown, one with no device none, and two devices are told with a slash`() {
        val line = AudioDeviceLines.routeLine(AudioRoute(media = null, call = emptyList(), mode = 0, scoOn = false))
        assertEquals("route: media -> unknown, call -> none, mode=NORMAL sco=false", line)
        assertEquals("BLUETOOTH_A2DP/BUILTIN_SPEAKER", AudioDeviceLines.routeEnd(listOf(8, 2, 8)))
        assertEquals("type 99", AudioDeviceLines.routeEnd(listOf(99)))
    }

    @Test
    fun `the same route is not written again, and a change of any part is`() {
        val log = AudioDeviceLines.Log()
        val route = AudioRoute(media = listOf(2), call = listOf(1), mode = 0, scoOn = false)
        assertNotNull(log.route(route))
        assertNull(log.route(route))
        assertNull(log.route(route.copy()))
        assertNotNull(log.route(route.copy(media = listOf(8))))
        assertNull(log.route(route.copy(media = listOf(8))))
        assertNotNull(log.route(route.copy(media = listOf(8), mode = 3)))
        assertNotNull(log.route(route.copy(media = listOf(8), mode = 3, scoOn = true)))
        assertNotNull(log.route(route.copy(media = listOf(8), call = listOf(7), mode = 3, scoOn = true)))
        // Back to an earlier route is a change, and is written.
        assertNotNull(log.route(route))
    }

    @Test
    fun `the headset link says its changes only, and not that it is off when nothing was going on`() {
        val log = AudioDeviceLines.Log()
        assertNull(log.scoState(0))
        assertNull(log.scoState(0))
        assertEquals("headset link (SCO): connecting", log.scoState(2))
        assertNull(log.scoState(2))
        assertEquals("headset link (SCO): connected", log.scoState(1))
        assertEquals("headset link (SCO): disconnected", log.scoState(0))
        assertEquals("headset link (SCO): error", log.scoState(-1))
        assertEquals("headset link (SCO): state 7", log.scoState(7))
        // A first state that is not "off" is told.
        assertEquals("headset link (SCO): connected", AudioDeviceLines.Log().scoState(1))
    }

    @Test
    fun `the warning that the sound is about to change has its line`() {
        assertTrue(AudioDeviceLines.NOISY_LINE.startsWith("audio becoming noisy"))
    }

    @Test
    fun `the facts of a device have no field for a name, an address or an id`() {
        val fields = AudioDeviceFacts::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet() // the compiler adds $stable
        assertEquals(setOf("type", "sink", "sampleRates", "channelCounts", "encodings"), fields)
    }

    @Test
    fun `no line carries a name, an address or a Bluetooth identifier`() {
        val log = AudioDeviceLines.Log()
        val lines = listOfNotNull(
            log.devicesAdded(listOf(speaker, a2dp, scoOut, scoIn)), log.devicesAdded(listOf(AudioDeviceFacts(26, sink = true))),
            log.devicesRemoved(listOf(a2dp)), log.route(AudioRoute(listOf(8), listOf(7), 3, true)), log.scoState(1), AudioDeviceLines.NOISY_LINE,
        )
        assertTrue(lines.size >= 6)
        lines.forEach { line ->
            assertFalse(line, Regex("[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}").containsMatchIn(line))
            assertFalse(line, line.any { it.code > 126 })
        }
    }
}
