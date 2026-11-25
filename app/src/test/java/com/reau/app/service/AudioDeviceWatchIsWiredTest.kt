package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Bluetooth sound's lines for the ride log: what they say is [com.reau.app.phone.AudioDeviceLinesTest]. Where they are fed from needs Android (the
 * audio manager's callback, its listeners, two broadcasts) and the live sides, so those lines are read from the source, as the other "...IsWired" tests do:
 * the watch starts and stops with the projection, every line is written inside a `runCatching`, it only observes, it asks for no permission and it reads
 * nothing that names a device.
 */
class AudioDeviceWatchIsWiredTest {
    private val main = File("src/main/java/com/reau/app")
    private val live = File(main, "service/LiveSides.kt").readText()
    private val watch = File(main, "service/AudioDeviceWatch.kt").readText()
    private val lines = File(main, "phone/AudioDeviceLines.kt").readText()

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    /** The source without comments, so that a word in a KDoc (the rule says "never the name") is not read as code. */
    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("(?m)(^|\\s)//.*$"), "")

    @Test
    fun `the projection starts the watch in the real app only, and gives it back at the release`() {
        assertTrue(live, "private val audioDeviceWatch = AudioDeviceWatch(context)" in live)
        val start = live.between("scope.launch { watchWaiting() }", "scope.launch { ProjectionMachine.dashLinks")
        assertTrue(start, "if (setup.settings != null) audioDeviceWatch.start()" in start)
        assertTrue(live, "\"audio devices watch\" to { audioDeviceWatch.stop() }" in live)
    }

    @Test
    fun `the device callback is registered at the start on the main thread, and taken off at the stop with the rest`() {
        assertTrue(watch, "manager.registerAudioDeviceCallback(callback, main)" in watch)
        val stop = watch.between("fun stop() {", "private fun addScoAction")
        for (taken in listOf(
            "manager.unregisterAudioDeviceCallback(it)", "app.unregisterReceiver(it)", "it.stop()", "main.removeCallbacks(readRoute)", "lines = null", "audio = null",
        )) {
            assertTrue("$taken is not in stop()", taken in stop)
        }
        assertTrue(watch, "manager.removeOnModeChangedListener(mode)" in watch)
        assertTrue(watch, "manager.removeOnCommunicationDeviceChangedListener(communication)" in watch)
    }

    @Test
    fun `a second start does nothing and a stop that never started is safe`() {
        assertTrue(watch, "if (lines != null) return true" in watch)
        assertTrue(watch, "val manager = audio ?: return\n        audio = null" in watch)
    }

    @Test
    fun `every callback and the one place that writes a line are inside a runCatching`() {
        val body = code(watch)
        assertEquals("the only place that writes to the log is tell()", 1, Regex("RideDiagnostics\\.log\\(").findAll(body).count())
        assertTrue(body, "if (line != null) runCatching { RideDiagnostics.log(TAG, line) }" in body)
        val added = body.between("override fun onAudioDevicesAdded", "override fun onAudioDevicesRemoved")
        assertTrue(added, added.trimStart().substringAfter("{").trimStart().startsWith("runCatching {"))
        assertTrue(added, "tell(log.devicesAdded(" in added)
        val removed = body.between("override fun onAudioDevicesRemoved", "manager.registerAudioDeviceCallback")
        assertTrue(removed, removed.trimStart().substringAfter("{").trimStart().startsWith("runCatching {"))
        assertTrue(removed, "tell(log.devicesRemoved(" in removed)
        val receiver = body.between("override fun onReceive(c: Context, intent: Intent) {", "val filter = ")
        assertTrue(receiver, receiver.trimStart().startsWith("runCatching {"))
        assertTrue(receiver, "tell(AudioDeviceLines.NOISY_LINE)" in receiver)
        assertTrue(receiver, "tell(log.scoState(" in receiver)
        val route = body.between("private val readRoute = Runnable {", "fun start(): Boolean {")
        assertTrue(route, route.trimStart().startsWith("runCatching {"))
        assertTrue(route, "tell(log.route(routeNow(manager)))" in route)
        assertTrue(body, "changes = Changes(manager, ContextCompat.getMainExecutor(app)) { runCatching { askRoute() } }" in body)
    }

    @Test
    fun `a burst of events is one reading of the route, a moment after the last`() {
        val ask = code(watch).between("private fun askRoute() {", "private fun tell(")
        assertTrue(ask, "main.removeCallbacks(readRoute)" in ask)
        assertTrue(ask, "main.postDelayed(readRoute, ROUTE_SETTLE_MS)" in ask)
    }

    @Test
    fun `the watch reads the audio manager only, with the listeners and broadcasts that need no permission`() {
        val body = code(watch)
        for (used in listOf(
            "registerAudioDeviceCallback", "AudioManager.ACTION_AUDIO_BECOMING_NOISY", "AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED", "OnModeChangedListener",
            "OnCommunicationDeviceChangedListener", "manager.communicationDevice", "manager.mode", "manager.isBluetoothScoOn", "getAudioDevicesForAttributes",
        )) {
            assertTrue("$used is not used", used in body)
        }
        for (never in listOf("BluetoothAdapter", "BluetoothManager", "BluetoothA2dp", "BluetoothHeadset", "BluetoothDevice", "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN")) {
            assertFalse("$never is used: it needs a permission REau does not ask", never in body)
        }
    }

    @Test
    fun `the watch changes no route`() {
        val body = code(watch)
        for (changer in listOf(
            "setCommunicationDevice", "clearCommunicationDevice", "setMode", "startBluetoothSco", "stopBluetoothSco", "setBluetoothScoOn", "setSpeakerphoneOn",
            "setPreferredDevice", "setPreferredDeviceForStrategy", "requestAudioFocus",
        )) {
            assertFalse("$changer changes the sound's route: the watch only observes", changer in body)
        }
    }

    @Test
    fun `nothing in the watch or in the lines reads a name, an address or an id of a device`() {
        for (source in listOf(code(watch), code(lines))) {
            for (never in listOf("productName", "address", "Address", "getId", "\\.id\\b", "\\bid\\b", "getName", "\\.name\\b", "\\.alias")) {
                assertFalse("$never is read", Regex(never).containsMatchIn(source))
            }
        }
        // What is read of a device is these five things, and the facts take only them.
        val facts = code(watch).between("private fun factsOf(device: AudioDeviceInfo) = AudioDeviceFacts(", "\n)")
        assertEquals(
            listOf("type = device.type", "sink = device.isSink", "sampleRates = device.sampleRates.toList()", "channelCounts = device.channelCounts.toList()",
                "encodings = device.encodings.toList()"),
            facts.split(",").map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    @Test
    fun `the manifest has no Bluetooth permission`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest, "android.permission.BLUETOOTH" in manifest)
    }
}
