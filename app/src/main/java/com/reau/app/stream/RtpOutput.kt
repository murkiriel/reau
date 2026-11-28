package com.reau.app.stream

import com.reau.app.dash.video.NalProcessor
import com.reau.app.dash.video.RtpPacketizer
import com.reau.app.dash.video.SpsReport

/**
 * The way out of the encoder, for one stream to the dash:
 *
 *     encoder buffer ──▶ KeyframeGate ──▶ NalProcessor ──▶ RtpPacketizer ──▶ [sendRtp]
 *
 * One per stream: a new stream gets a new RTP sequence and source id, and starts with its gate closed. It hears one
 * encoder at a time ([switchTo]); what another one still hands over is ignored, so an encoder swap never mixes two
 * encoders' frames. No Android in here: it runs in a plain JVM test with hand-made buffers.
 *
 * [tap], if given, is handed every unit after it was sent, with whether it is a keyframe and its frame's time: that
 * is where the recording ([H264Recorder]) listens.
 *
 * Every method may be called from any thread; the encoders' output threads call [onEncoded].
 */
@Suppress("LongParameterList") // optional hooks with defaults, each one listener of the sent stream; a holder class would only move them (26/11/2026)
internal class RtpOutput(
    private val tap: ((ByteArray, Boolean, Long) -> Unit)? = null,
    /** Told after each keyframe went out: its bytes and how many RTP packets it took (for the line written when the dash goes quiet). */
    private val onKeyframe: ((bytes: Int, packets: Int) -> Unit)? = null,
    /** Told just before a keyframe's packets go out (the dash session keeps its routine sends away from the burst). */
    private val onKeyframeStart: (() -> Unit)? = null,
    /** Told after each frame (not parameter sets) went out: its bytes, RTP packets, whether it is a keyframe and the longest gap between two of its packets, in ns (the fine tail of the ride log). */
    private val onFrame: ((bytes: Int, packets: Int, key: Boolean, maxGapNs: Long) -> Unit)? = null,
    /** The clock of the gaps between packets, in ns; the tests give theirs. */
    private val nanoTime: () -> Long = System::nanoTime,
    /** The clock of the shape of the stream ([StreamShape]), in ms (a separate clock: the one above is read once per packet and the tests count its reads); the tests give theirs. */
    private val clockMs: () -> Long = { System.nanoTime() / NS_PER_MS },
    /** The ride log, for the line about the stream's SPS and the line about its shape (26/11/2026); none, nothing is written. */
    private val log: ((String) -> Unit)? = null,
    private val sendRtp: (ByteArray) -> Unit,
) {

    /**
     * Frames (not parameter sets) and their bytes that went on to the dash, and the keyframes among them (to
     * measure the burst a keyframe is): how many, and the biggest one's bytes and RTP packets.
     */
    data class Counts(val frames: Int, val bytes: Long, val keyframes: Int = 0, val keyBytesMax: Int = 0, val keyPacketsMax: Int = 0)

    private val gate = KeyframeGate()

    // RTP packets made: a keyframe's own count is the difference across its NALs.
    private var packetsMade = 0

    // The longest gap between two packets of the buffer being sent (observed only; nothing is paced here).
    private var lastPacketNs = NO_PACKET
    private var frameGapNs = 0L

    private val packetizer = RtpPacketizer { packet ->
        watchGap()
        packetsMade++
        sendRtp(packet)
    }

    private fun watchGap() {
        val now = nanoTime()
        if (lastPacketNs != NO_PACKET && now - lastPacketNs > frameGapNs) frameGapNs = now - lastPacketNs
        lastPacketNs = now
    }

    // All NALs of one frame share the time that frame was stamped with, however late the encoder hands it back.
    private var framePtsMs = 0L
    private val nalProcessor = NalProcessor { nal, isKey ->
        packetizer.packetize(nal, endOfAU = true, wallClockMs = framePtsMs)
        // Only what really went out: a send that throws never gets here.
        tap?.invoke(nal, isKey, framePtsMs)
    }

    // What the frames looked like (the keyframes, the slices, the worst second) and the SPS last written to the log, both for the lines of 26/11/2026.
    private val shape = StreamShape()
    private var loggedSps: ByteArray? = null

    private var encoder: Any? = null
    private var frames = 0
    private var bytes = 0L
    private var keyframes = 0
    private var keyBytesMax = 0
    private var keyPacketsMax = 0

    /** Frames held back before a keyframe, so far. */
    val heldBack: Int @Synchronized get() = gate.dropped

    /** Keyframes not sent because no SPS/PPS had come before them. */
    val idrWithheld: Int @Synchronized get() = nalProcessor.withheldIdr

    // Set when a keyframe was withheld: what follows it has nothing to refer to, and waiting for the encoder's next keyframe
    // would leave the dash without a picture for its whole interval. It is a flag read by the video loop's poll, not a callback,
    // so the encoder's output thread never takes the loop's lock while it holds this one.
    private var keyframeWanted = false

    /** From now on only [encoder]'s buffers are heard, starting at its next keyframe. */
    @Synchronized
    fun switchTo(encoder: Any) {
        this.encoder = encoder
        keyframeWanted = false
        gate.close()
    }

    /** Whether a keyframe was withheld since the previous call, which asks for a new one; the answer starts again. */
    @Synchronized
    fun takeKeyframeWanted(): Boolean = keyframeWanted.also { keyframeWanted = false }

    /** The dash is at the start of a stream again: nothing more goes out until a keyframe. */
    @Synchronized
    fun holdUntilKeyframe() {
        gate.close()
    }

    /** One buffer of [from]'s output (Annex-B), with the time its frame was stamped with. */
    @Synchronized
    fun onEncoded(from: Any, annexB: ByteArray, isKey: Boolean, ptsMs: Long) {
        if (from !== encoder || !gate.admit(annexB, isKey)) return
        framePtsMs = ptsMs
        val withheldBefore = nalProcessor.withheldIdr
        val packetsBefore = packetsMade
        lastPacketNs = NO_PACKET
        frameGapNs = 0L
        if (isKey && !KeyframeGate.isParameterSetsOnly(annexB, isKey)) onKeyframeStart?.invoke()
        nalProcessor.process(annexB)
        logSps()
        if (nalProcessor.withheldIdr != withheldBefore) {
            // The keyframe stayed back, so what follows it has nothing to refer to: wait for the next one.
            gate.close()
            keyframeWanted = true
            return
        }
        if (!KeyframeGate.isParameterSetsOnly(annexB, isKey)) {
            frames++
            bytes += annexB.size
            if (isKey) {
                keyframes++
                keyBytesMax = maxOf(keyBytesMax, annexB.size)
                keyPacketsMax = maxOf(keyPacketsMax, packetsMade - packetsBefore)
                onKeyframe?.invoke(annexB.size, packetsMade - packetsBefore)
            }
            onFrame?.invoke(annexB.size, packetsMade - packetsBefore, isKey, frameGapNs)
            val now = clockMs()
            shape.add(now, annexB.size, packetsMade - packetsBefore, isKey, nalProcessor.lastSlices)
            shape.periodicLine(now)?.let { log?.invoke(it) }
        }
    }

    // The SPS the encoder made, written once for each encoder of the stream (a rebuilt one with the same SPS says nothing new).
    private fun logSps() {
        val original = nalProcessor.originalSps ?: return
        if (loggedSps?.contentEquals(original) == true) return
        loggedSps = original
        log?.invoke(SpsReport.line(original, nalProcessor.sentSps ?: original))
    }

    /** The line about the shape of the stream so far (keyframes, slices, the worst second), for the end of the stream; null before the first frame. */
    @Synchronized
    fun shapeLine(): String? = shape.line()

    /** Totals since the previous call, then starts counting again. */
    @Synchronized
    fun takeCounts(): Counts = Counts(frames, bytes, keyframes, keyBytesMax, keyPacketsMax).also {
        frames = 0
        bytes = 0L
        keyframes = 0
        keyBytesMax = 0
        keyPacketsMax = 0
    }

    private companion object {
        const val NO_PACKET = Long.MIN_VALUE
        const val NS_PER_MS = 1_000_000L
    }
}
