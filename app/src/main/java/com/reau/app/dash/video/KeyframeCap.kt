package com.reau.app.dash.video

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The regulator of the keyframe's size (the owner's decision of 28/11/2026): the dash decodes H.264 in software on a weak processor, so a big keyframe is the peak of its work
 * and of the traffic it takes, and the suspicion is that it is what stalls its network channel. A keyframe already made cannot be cut (the dash needs all of it), so the cap
 * is REGULATED: after each keyframe the bitrate the encoder is asked for is scaled by a [factor], so the next keyframes come out smaller.
 *
 * - A keyframe over [capBytes] (what goes to the dash: SPS, PPS and the picture): the wanted factor is multiplied by [OVERSHOOT_AIM] x cap / size (aiming a tenth under the cap),
 *   never under [FLOOR].
 * - [CALM_KEYFRAMES] keyframes in a row under [CALM_RATIO] of the cap: the factor climbs [RECOVER_STEP] per keyframe, up to 1.0. A keyframe between that and the cap holds it.
 * - Hysteresis: the codec is only asked for a new bitrate when the wanted factor is [MIN_CHANGE] away from the one asked last, or when it reaches 1.0 or [FLOOR].
 *   [onKeyframe] says when ([Change]); the factor in force is [factor].
 *
 * Off ([enabled] false) the factor is 1.0 and nothing is ever changed: the bitrate is exactly the one asked for, as before. A phone whose keyframes stay under the
 * cap never leaves 1.0. Pure: no codec here; [DashEncoder] feeds it and applies [effective].
 */
class KeyframeCap(val enabled: Boolean = true, private val capBytes: Int = CAP_BYTES, private val minChange: Double = MIN_CHANGE) {
    /** One change of the factor to be applied to the codec: the keyframe that caused it and whether it was over the cap (reduction) or the calm that recovers it. */
    class Change(val factor: Double, val keyframeBytes: Int, val overCap: Boolean, val capBytes: Int)

    private var wanted = 1.0
    private var calm = 0

    /** The factor the codec was last asked with (1.0 until a change): [effective] is the bitrate asked times it. Read by the loop for the `idrcap=` of the log line. */
    @Volatile var factor = 1.0
        private set

    /**
     * A keyframe of [bytes] (SPS, PPS and the picture, as [sentBytes] counts it) came out. Returns what to ask of the codec when the factor in force must change, else null.
     * Called on the encoder's output thread, one keyframe at a time.
     */
    @Synchronized
    fun onKeyframe(bytes: Int): Change? {
        if (!enabled || bytes <= 0) return null
        val over = bytes > capBytes
        when {
            over -> {
                wanted = max(FLOOR, wanted * OVERSHOOT_AIM * capBytes / bytes)
                calm = 0
            }
            bytes < capBytes * CALM_RATIO -> if (++calm >= CALM_KEYFRAMES) wanted = min(1.0, wanted + RECOVER_STEP)
            else -> calm = 0
        }
        val atEdge = (wanted == 1.0 || wanted == FLOOR) && factor != wanted
        if (!atEdge && abs(wanted - factor) < minChange) return null
        factor = wanted
        return Change(factor, bytes, over, capBytes)
    }

    /** The bitrate to ask the codec for when the others want [baseBps]: theirs times the factor in force; theirs exactly when off or at 1.0. */
    fun effective(baseBps: Int): Int = if (factor == 1.0) baseBps else max(1, (baseBps * factor).roundToInt())

    /** The factor in force as the ride log writes it: `idrcap=0.82`. */
    fun text(): String = String.format(java.util.Locale.US, "idrcap=%.2f", factor)

    companion object {
        /** The cap, in bytes of a keyframe that goes to the dash (48 kB: the owner's decision of 28/11/2026). */
        const val CAP_BYTES = 48_000

        /** The factor never goes under this: the picture would be worth nothing under 40 % of the bitrate the rider's profile asks for. */
        const val FLOOR = 0.4

        /** A reduction aims a tenth under the cap, so the next keyframe, with the wobble of the encoder, is not over it again. */
        const val OVERSHOOT_AIM = 0.9

        /** A keyframe under this share of the cap is a calm one. */
        const val CALM_RATIO = 0.75

        /** Calm keyframes in a row before the factor starts to recover (1 s apart: 3 s of calm). */
        const val CALM_KEYFRAMES = 3

        /** What the factor recovers at each calm keyframe after that. */
        const val RECOVER_STEP = 0.05

        /** The codec is not asked again for a change of the factor smaller than this (except at 1.0 or the floor). */
        const val MIN_CHANGE = 0.03

        /** The ride log's line for a [change] that asks the codec for [bps]: a keyframe over the cap, or the calm that gives the bitrate back. */
        fun changeLine(change: Change, bps: Int): String {
            val cause = if (change.overCap) {
                "IDR of ${change.keyframeBytes} B over the ${change.capBytes} B cap"
            } else {
                "IDR of ${change.keyframeBytes} B, calm, giving the bitrate back"
            }
            return String.format(java.util.Locale.US, "keyframe cap: %s -> bitrate %d kbps (idrcap=%.2f)", cause, bps / 1000, change.factor)
        }

        private const val NAL_SPS = 7
        private const val NAL_PPS = 8

        /**
         * What the dash is sent for the keyframe of the encoder's output buffer [idr]: the picture's own bytes plus the parameter sets the processor puts before it
         * ([NalProcessor]), [parameterSetBytes] being the size of the encoder's SPS/PPS buffer; an encoder that puts the parameter sets in front of the keyframe itself
         * already counts them.
         */
        fun sentBytes(idr: ByteArray, parameterSetBytes: Int): Int =
            if (firstNalType(idr).let { it == NAL_SPS || it == NAL_PPS }) idr.size else idr.size + parameterSetBytes

        /** Type of the NAL unit after the first start code (00 00 01 or 00 00 00 01), or -1. */
        private fun firstNalType(annexB: ByteArray): Int {
            var i = 0
            while (i < annexB.size && annexB[i].toInt() == 0) i++
            val startCode = i >= 2 && i + 1 < annexB.size && annexB[i].toInt() == 1
            return if (startCode) annexB[i + 1].toInt() and 0x1F else -1
        }
    }
}
