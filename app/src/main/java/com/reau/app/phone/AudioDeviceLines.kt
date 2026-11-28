package com.reau.app.phone

/**
 * What the ride log may know of one audio device: its kind (AudioDeviceInfo's TYPE_* number), whether it is an output ([sink]) or an input, and what it
 * announces it can do. Never its name, its address (a MAC) or its id: the class has no field for them, so the log cannot carry them.
 */
data class AudioDeviceFacts(
    val type: Int,
    val sink: Boolean,
    val sampleRates: List<Int> = emptyList(),
    val channelCounts: List<Int> = emptyList(),
    val encodings: List<Int> = emptyList(),
)

/**
 * Where the sound goes now: the kinds of device the music goes to ([media]) and the call or the voice ([call]) (null when Android cannot be asked: the
 * first below Android 13, the second below Android 12), the audio [mode] and whether the headset link ([scoOn]) is on.
 */
data class AudioRoute(val media: List<Int>?, val call: List<Int>?, val mode: Int, val scoOn: Boolean)

/**
 * The `[audio]` lines about Bluetooth, for testing a helmet communicator or a headset: when an audio device of Bluetooth comes or goes, where the sound
 * goes at each change, and the headset link's state. Pure, so the JVM tests cover it; the Android side that feeds it is `AudioDeviceWatch`. Only kinds,
 * numbers and counts: never a device's name or address, never an id.
 */
object AudioDeviceLines {
    /** AudioDeviceInfo's TYPE_* numbers of the Bluetooth audio devices, with the words the log says them in. */
    private val BLUETOOTH = mapOf(
        7 to "SCO", 8 to "A2DP", 23 to "hearing aid", 26 to "BLE headset", 27 to "BLE speaker", 30 to "BLE broadcast",
    )

    /** The kinds the route line can meet that [CallAudioRoute.deviceName] does not know. */
    private val ROUTE_EXTRA = mapOf(24 to "BUILTIN_SPEAKER_SAFE", 30 to "BLE_BROADCAST")

    /** AudioFormat's ENCODING_* numbers that a Bluetooth link can announce. */
    private val ENCODINGS = mapOf(
        2 to "PCM16", 3 to "PCM8", 4 to "PCM_FLOAT", 9 to "MP3", 10 to "AAC_LC", 11 to "AAC_HE_V1", 12 to "AAC_HE_V2", 15 to "AAC_ELD",
        16 to "AAC_XHE", 20 to "OPUS", 21 to "PCM24", 22 to "PCM32",
    )

    /** The most numbers of one kind a line says; the others are counted. */
    private const val MOST_SHOWN = 6

    private const val SCO_DISCONNECTED = 0
    private const val SCO_CONNECTED = 1
    private const val SCO_CONNECTING = 2
    private const val SCO_ERROR = -1

    fun isBluetooth(type: Int): Boolean = type in BLUETOOTH

    /** The kind in words (`A2DP`, `SCO`...); a kind this list does not know is `type N`, never a name. */
    fun kindName(type: Int): String = BLUETOOTH[type] ?: "type $type"

    /** The kind and the direction: `A2DP out`, `SCO in`. */
    fun kindAndDirection(device: AudioDeviceFacts): String = "${kindName(device.type)} ${if (device.sink) "out" else "in"}"

    /** The kind for the route line (`BLUETOOTH_A2DP`...), as the call's line says it; any other is `type N`. */
    fun routeName(type: Int): String = ROUTE_EXTRA[type] ?: CallAudioRoute.deviceName(type)

    /** The kinds of one end of the route: `unknown` when Android could not be asked, `none` with no device, else the kinds apart by a slash. */
    fun routeEnd(types: List<Int>?): String = when {
        types == null -> "unknown"
        types.isEmpty() -> "none"
        else -> types.distinct().joinToString("/") { routeName(it) }
    }

    /** What one device announces, as `A2DP out (rates 44100/48000, channels 2, encodings PCM16/AAC_LC)`; nothing announced says so. */
    fun describe(device: AudioDeviceFacts): String {
        val parts = listOfNotNull(
            device.sampleRates.takeIf { it.isNotEmpty() }?.let { "rates " + shown(it.distinct().sorted().map { n -> n.toString() }) },
            device.channelCounts.takeIf { it.isNotEmpty() }?.let { "channels " + shown(it.distinct().sorted().map { n -> n.toString() }) },
            device.encodings.takeIf { it.isNotEmpty() }?.let { "encodings " + shown(it.distinct().sorted().map { n -> ENCODINGS[n] ?: "enc $n" }) },
        )
        return "${kindAndDirection(device)} (${if (parts.isEmpty()) "nothing announced" else parts.joinToString(", ")})"
    }

    private fun shown(items: List<String>): String =
        items.take(MOST_SHOWN).joinToString("/") + if (items.size > MOST_SHOWN) "/+${items.size - MOST_SHOWN}" else ""

    /** The state of the headset link in words; a state this list does not know is `state N`. */
    fun scoName(state: Int): String = when (state) {
        SCO_DISCONNECTED -> "disconnected"
        SCO_CONNECTED -> "connected"
        SCO_CONNECTING -> "connecting"
        SCO_ERROR -> "error"
        else -> "state $state"
    }

    /** The line for a route: `route: media -> BLUETOOTH_A2DP, call -> BUILTIN_EARPIECE, mode=NORMAL sco=false`. */
    fun routeLine(route: AudioRoute): String =
        "route: media -> ${routeEnd(route.media)}, call -> ${routeEnd(route.call)}, mode=${CallAudioRoute.modeName(route.mode)} sco=${route.scoOn}"

    /** The line when Android warns that the sound is about to move to a noisier output. */
    const val NOISY_LINE = "audio becoming noisy: Android warns the output is changing (a headset or a Bluetooth link went away)"

    /**
     * What the log of one projection remembers, so that it writes changes and not the same thing again: the Bluetooth devices now present (as counts per
     * kind and direction), the last route and the last state of the headset link. The first call about devices is the start (Android lists all the
     * connected ones in one call when the callback is registered), told in one line. A new one is made for each projection. Not thread-safe: one thread
     * (the main one) drives it.
     */
    class Log {
        private var devicesHeard = false
        private val present = mutableMapOf<Pair<Int, Boolean>, Int>()
        private var lastRoute: String? = null
        private var lastSco: Int? = null

        private fun counts(): String {
            val out = present.filterKeys { it.second }.values.sum()
            val inn = present.filterKeys { !it.second }.values.sum()
            return "$out bluetooth output(s), $inn input(s)"
        }

        /** The line for devices that Android says came ([devices]: one callback, so a burst is one line); null when none is a Bluetooth one. */
        fun devicesAdded(devices: List<AudioDeviceFacts>): String? {
            val first = !devicesHeard
            devicesHeard = true
            val bluetooth = devices.filter { isBluetooth(it.type) }
            bluetooth.forEach { device ->
                val key = device.type to device.sink
                present[key] = (present[key] ?: 0) + 1
            }
            val listed = bluetooth.joinToString("; ") { describe(it) }
            return when {
                first && bluetooth.isEmpty() -> "bluetooth audio at start: no device"
                first -> "bluetooth audio at start: $listed - ${counts()}"
                bluetooth.isEmpty() -> null
                bluetooth.size == 1 -> "bluetooth device added: $listed - now ${counts()}"
                else -> "bluetooth devices added: $listed - now ${counts()}"
            }
        }

        /** The line for devices that Android says went; null when none is a Bluetooth one. */
        fun devicesRemoved(devices: List<AudioDeviceFacts>): String? {
            val bluetooth = devices.filter { isBluetooth(it.type) }
            if (bluetooth.isEmpty()) return null
            bluetooth.forEach { device ->
                val key = device.type to device.sink
                val left = (present[key] ?: 0) - 1
                if (left > 0) present[key] = left else present.remove(key)
            }
            val what = if (bluetooth.size == 1) "bluetooth device removed: " else "bluetooth devices removed: "
            return what + bluetooth.joinToString("; ") { kindAndDirection(it) } + " - now " + counts()
        }

        /** The line for [route], or null when it is the one already written. */
        fun route(route: AudioRoute): String? = routeLine(route).takeIf { it != lastRoute }?.also { lastRoute = it }

        /**
         * The line for the headset link's [state], or null when it did not change. The first state, when it is "disconnected" (what it is when
         * nothing is going on), says nothing.
         */
        fun scoState(state: Int): String? {
            val before = lastSco
            lastSco = state
            if (state == before || before == null && state == SCO_DISCONNECTED) return null
            return "headset link (SCO): ${scoName(state)}"
        }
    }
}
