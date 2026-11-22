package com.reau.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.reau.app.dash.VolumeCards

/**
 * Listens to the system's broadcast of a volume change ([VolumeCards.ACTION]) while the projection runs, and tells [onStep] each step the volume card
 * is for ([VolumeCards.decide]: the music's, the call's, the Bluetooth headset's call; a level that did not move is not one). The broadcast is sent
 * whoever changed the volume (the phone's buttons, a Bluetooth intercom, the joystick's own volume action, which moves the stream with no panel and
 * still changes it for the system), so one watch covers every way. The stream's most steps is asked of the [AudioManager] at each step. Registered
 * by [start] and given back by [stop]; the callback runs on the main thread.
 */
internal class VolumeWatch(context: Context, private val onStep: (VolumeCards.Step) -> Unit) {
    private val app = context.applicationContext
    private var receiver: BroadcastReceiver? = null

    /** Starts listening; a second start while it listens does nothing. False when the system refused the receiver (the card then never shows). */
    @Synchronized
    fun start(): Boolean {
        if (receiver != null) return true
        val audio = app.getSystemService(AudioManager::class.java) ?: return false
        val heard = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val stream = intent.getIntExtra(VolumeCards.EXTRA_STREAM_TYPE, -1)
                val value = intent.getIntExtra(VolumeCards.EXTRA_STREAM_VALUE, -1)
                val previous = intent.getIntExtra(VolumeCards.EXTRA_PREVIOUS_VALUE, -1)
                val max = runCatching { audio.getStreamMaxVolume(stream) }.getOrDefault(0)
                VolumeCards.decide(stream, value, previous, max)?.let(onStep)
            }
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(app, heard, IntentFilter(VolumeCards.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        if (registered) receiver = heard
        return registered
    }

    /** Stops listening. Safe to call when it never started. */
    @Synchronized
    fun stop() {
        val heard = receiver ?: return
        receiver = null
        runCatching { app.unregisterReceiver(heard) }
    }
}
