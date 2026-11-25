package com.reau.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.reau.app.data.RideDiagnostics
import com.reau.app.phone.AudioDeviceFacts
import com.reau.app.phone.AudioDeviceLines
import com.reau.app.phone.AudioRoute
import java.util.concurrent.Executor

/**
 * Tells the ride log (`[audio]` lines) about the Bluetooth sound while the projection runs, for the reports of Bluetooth trouble: an audio device of
 * Bluetooth (A2DP, the headset link SCO, BLE Audio: a headset, a helmet communicator) that comes or goes, with what it announces it can do, and where the
 * sound goes (the music, the call, the audio mode, the headset link) at each change. It only watches: it changes no route and asks for no permission (only
 * AudioManager's device callback, its mode, its communication device and two broadcasts, none of which needs one; no BluetoothAdapter, no BluetoothHeadset).
 * The lines are made by [AudioDeviceLines.Log] and only say kinds and numbers: what is read of a device here is its kind, its direction, its rates, its
 * channels and its encodings (see [factsOf]), never its name, its address or its id. Registered by [start] and given back by [stop]; everything runs on the
 * main thread, and a line can never end the projection: each one is written inside a `runCatching`.
 */
internal class AudioDeviceWatch(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var audio: AudioManager? = null
    @Volatile private var lines: AudioDeviceLines.Log? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var receiver: BroadcastReceiver? = null
    private var changes: Changes? = null

    /** Reads the route a moment after the last event, so that a burst of them (a headset joining changes the devices, the mode and the link) is one line. */
    private val readRoute = Runnable {
        runCatching {
            val manager = audio ?: return@runCatching
            val log = lines ?: return@runCatching
            tell(log.route(routeNow(manager)))
        }
    }

    /** Starts watching; a second start while it watches does nothing. False when Android would not say (nothing is logged then, the projection goes on). */
    @Synchronized
    fun start(): Boolean {
        if (lines != null) return true
        val manager = app.getSystemService(AudioManager::class.java) ?: return false
        val log = AudioDeviceLines.Log()
        audio = manager
        lines = log
        val registered = runCatching { register(manager, log) }
        if (registered.isFailure) {
            stop()
            runCatching { RideDiagnostics.warn(TAG, "the Bluetooth sound watch could not start: ${registered.exceptionOrNull()?.javaClass?.simpleName}") }
        }
        return registered.isSuccess
    }

    private fun register(manager: AudioManager, log: AudioDeviceLines.Log) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                runCatching {
                    tell(log.devicesAdded(addedDevices.map { factsOf(it) }))
                    askRoute()
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                runCatching {
                    tell(log.devicesRemoved(removedDevices.map { factsOf(it) }))
                    askRoute()
                }
            }
        }
        // The handler makes every callback come on the main thread, where the log's memory is only ever touched; the first call lists all the connected devices.
        manager.registerAudioDeviceCallback(callback, main)
        deviceCallback = callback
        val heard = object : BroadcastReceiver() {
            @Suppress("DEPRECATION") // the headset link's broadcast: the only word on it before Android 12's communication device
            override fun onReceive(c: Context, intent: Intent) {
                runCatching {
                    when (intent.action) {
                        AudioManager.ACTION_AUDIO_BECOMING_NOISY -> tell(AudioDeviceLines.NOISY_LINE)
                        AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> if (intent.hasExtra(AudioManager.EXTRA_SCO_AUDIO_STATE)) {
                            tell(log.scoState(intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, 0)))
                        }
                    }
                    askRoute()
                }
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        // From Android 12 the communication device's own listener says what the broadcast said, so the broadcast (deprecated since) is only listened to before.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) addScoAction(filter)
        ContextCompat.registerReceiver(app, heard, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = heard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            changes = Changes(manager, ContextCompat.getMainExecutor(app)) { runCatching { askRoute() } }
        }
        askRoute()
    }

    /** Stops watching and gives everything back. Safe to call when it never started. */
    @Synchronized
    fun stop() {
        val manager = audio ?: return
        audio = null
        lines = null
        main.removeCallbacks(readRoute)
        deviceCallback?.let { runCatching { manager.unregisterAudioDeviceCallback(it) } }
        deviceCallback = null
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) changes?.let { runCatching { it.stop() } }
        changes = null
    }

    @Suppress("DEPRECATION") // see the receiver: the broadcast is only listened to before Android 12
    private fun addScoAction(filter: IntentFilter) = filter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)

    private fun askRoute() {
        main.removeCallbacks(readRoute)
        main.postDelayed(readRoute, ROUTE_SETTLE_MS)
    }

    /** One line of the `audio` tag; null (nothing to say) writes nothing. */
    private fun tell(line: String?) {
        if (line != null) runCatching { RideDiagnostics.log(TAG, line) }
    }

    /** Where the sound goes now: the music's devices (Android 13 and up), the communication device (Android 12 and up), the mode and the headset link. */
    @Suppress("DEPRECATION") // isBluetoothScoOn: still the only plain answer below Android 14's communication device (as CallAudioRoute reads it)
    private fun routeNow(manager: AudioManager): AudioRoute {
        val media = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val music = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
                manager.getAudioDevicesForAttributes(music).map { it.type }
            }.getOrNull()
        } else {
            null
        }
        val call = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { listOfNotNull(manager.communicationDevice?.type) }.getOrNull() else null
        return AudioRoute(media, call, manager.mode, manager.isBluetoothScoOn)
    }

    /** The audio mode's and the communication device's own listeners (Android 12 and up), in a class of their own so that older phones never load them. */
    @RequiresApi(Build.VERSION_CODES.S)
    private class Changes(private val manager: AudioManager, executor: Executor, onChange: () -> Unit) {
        private val mode = AudioManager.OnModeChangedListener { onChange() }
        private val communication = AudioManager.OnCommunicationDeviceChangedListener { onChange() }

        init {
            manager.addOnModeChangedListener(executor, mode)
            manager.addOnCommunicationDeviceChangedListener(executor, communication)
        }

        fun stop() {
            runCatching { manager.removeOnModeChangedListener(mode) }
            runCatching { manager.removeOnCommunicationDeviceChangedListener(communication) }
        }
    }

    private companion object {
        const val TAG = "audio"

        /** How long after an event the route is read: events of one change arrive within some tens of milliseconds. */
        const val ROUTE_SETTLE_MS = 300L
    }
}

/** The only things the log gets of a device: kind, direction and what it announces. Nothing that names or locates it. */
private fun factsOf(device: AudioDeviceInfo) = AudioDeviceFacts(
    type = device.type,
    sink = device.isSink,
    sampleRates = device.sampleRates.toList(),
    channelCounts = device.channelCounts.toList(),
    encodings = device.encodings.toList(),
)
