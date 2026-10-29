package com.reau.app.aa

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Plays Android Auto's sound on the phone itself (as well as in the browser): when REau announces the media and
 * guidance sinks, Android Auto stops playing them on the phone and sends them here, so REau plays them again, through the phone's
 * own output (the rider's headset). One lane for each stream, each with a queue ([AaAudioQueue]) and a thread of its own: the read
 * thread that calls [play] never waits for the speaker.
 *
 * Why the track is not played as media: with the sinks announced, Android Auto registers an audio policy that captures (loops back)
 * every player of usage MEDIA or GAME on the phone and sends it to REau (see [AaAudioRoute]). REau's own track played as media was
 * captured and sent back, round and round (old songs went on playing after the music was paused). The mix did not
 * honour `ALLOW_CAPTURE_BY_NONE`, so the phone's sound plays with a usage it does not match, and the route the system gave the track is
 * checked after a few chunks: if it went to the loop-back, the lane stops and says so, rather than howl.
 */
class AaAudioPlayer internal constructor(
    private val log: (String) -> Unit,
    private val speakerFor: (AaAudioFeed.Stream) -> PhoneSpeaker,
) {
    constructor(log: (String) -> Unit = {}) : this(log, ::audioTrackFor)

    /**
     * The speaker a lane plays on, as far as the lane needs it. Only the lane's own thread touches it once it plays, and that thread
     * lets it go when it ends: [release] only asks the lane to stop and waits a moment, so the track is never released while
     * a `write` is still inside it (a native crash on some phones: audit of 20/10/2026).
     */
    internal interface PhoneSpeaker {
        fun play()
        fun write(data: ByteArray)
        fun pause()
        fun flush()
        fun stop()
        fun release()

        /** The type of the output device the system routed the track to, null when not known yet. */
        val routedDeviceType: Int?
    }

    private class Lane(val queue: AaAudioQueue, val speaker: PhoneSpeaker, @Volatile var running: Boolean = true) {
        lateinit var thread: Thread
    }

    private val lock = Any()
    private val lanes = HashMap<AaAudioFeed.Stream, Lane>()
    private var released = false

    /** Queues [pcm] for its stream; the lane is made with the first sound. Never waits. */
    fun play(pcm: AaAudioFeed.Pcm) {
        val lane = synchronized(lock) { if (released) null else lanes.getOrPut(pcm.stream) { newLane(pcm.stream) } } ?: return
        if (lane.running) lane.queue.offer(pcm.data)
    }

    fun release() {
        val all = synchronized(lock) {
            released = true
            lanes.values.toList().also { lanes.clear() }
        }
        for (lane in all) {
            lane.running = false
            lane.queue.clear()
            // Pausing and emptying the track lets a `write` that is blocked on a full buffer come back; the lane's thread then sees
            // it is not running, and stops and releases the track itself.
            runCatching { lane.speaker.pause() }
            runCatching { lane.speaker.flush() }
        }
        for (lane in all) {
            lane.thread.join(JOIN_MS)
            if (lane.thread.isAlive) log("audio: a lane did not end in $JOIN_MS ms; it lets its speaker go when it does")
        }
    }

    private fun newLane(stream: AaAudioFeed.Stream): Lane {
        val lane = Lane(AaAudioQueue(AaAudioQueue.maxBytesFor(stream)), speakerFor(stream))
        lane.thread = Thread({ pump(stream, lane) }, "aa-audio-${stream.name.lowercase()}").apply { isDaemon = true; start() }
        log("audio: playing $stream on the phone, ${stream.sampleRate} Hz, ${stream.channels} channel(s)")
        return lane
    }

    /** The lane's thread: what the queue gives goes to the speaker, which may hold this thread, and nobody else. */
    private fun pump(stream: AaAudioFeed.Stream, lane: Lane) {
        try {
            lane.speaker.play()
            var written = 0
            while (lane.running) {
                val chunk = lane.queue.take(WAIT_MS)
                // Nothing written once the lane was asked to stop (a release is waiting for this thread to be out of the speaker).
                if (chunk != null && lane.running) playChunk(stream, lane, chunk, ++written)
            }
        } catch (e: IllegalStateException) {
            log("audio: the phone's speaker for $stream stopped: ${e.javaClass.simpleName}")
        } finally {
            // The only place the speaker is let go: no other thread can be inside it any more.
            runCatching { lane.speaker.stop() }
            runCatching { lane.speaker.release() }
        }
    }

    /** One chunk to the speaker; after a few, a look at where the system routed it (into Android Auto's capture, the lane stops). */
    private fun playChunk(stream: AaAudioFeed.Stream, lane: Lane, chunk: ByteArray, written: Int) {
        lane.speaker.write(chunk)
        if (AaAudioRoute.checkNow(written) && AaAudioRoute.isTheLoop(lane.speaker.routedDeviceType)) {
            log("audio: the phone's sound for $stream was routed into Android Auto's capture: not playing it on the phone")
            lane.running = false
            lane.queue.clear()
            lane.speaker.pause()
        }
    }

    private companion object {
        const val WAIT_MS = 200L
        const val JOIN_MS = 1_000L

        // False positive: AaAudioRoute.usageOf returns AudioAttributes.USAGE_UNKNOWN (0), but lint cannot see a constant behind a
        // function call, so it flags the setUsage argument. AaAudioRouteTest pins the value.
        @SuppressLint("WrongConstant")
        fun audioTrackFor(stream: AaAudioFeed.Stream): PhoneSpeaker {
            val media = stream == AaAudioFeed.Stream.MEDIA
            val attributes = AudioAttributes.Builder()
                .setUsage(AaAudioRoute.usageOf(stream))
                .setContentType(if (media) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                .build()
            val mask = if (stream.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val format = AudioFormat.Builder().setSampleRate(stream.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build()
            val wanted = AaAudioQueue.maxBytesFor(stream) * 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(wanted, AudioTrack.getMinBufferSize(stream.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            return object : PhoneSpeaker {
                override fun play() = track.play()
                override fun write(data: ByteArray) { track.write(data, 0, data.size) }
                override fun pause() = track.pause()
                override fun flush() = track.flush()
                override fun stop() = track.stop()
                override fun release() = track.release()
                override val routedDeviceType: Int? get() = track.routedDevice?.type
            }
        }
    }
}
