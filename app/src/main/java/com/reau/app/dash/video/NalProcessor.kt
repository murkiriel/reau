package com.reau.app.dash.video

/**
 * Splits Annex-B H.264 output from MediaCodec into individual NAL units,
 * handles the dash-specific IDR bundling requirement, and filters NAL types
 * the dash rejects (SEI, AUD).
 *
 * Dash-specific rules (from better-dash analysis):
 *  - SPS (type 7) and PPS (type 8): cache, do NOT send raw
 *  - IDR (type 5): prepend cached SPS + PPS with Annex-B start codes, send bundle;
 *    with no SPS/PPS cached yet it is withheld (the dash cannot decode it), see [withheldIdr]
 *  - SEI (type 6) and AUD (type 9): discard
 *  - All other slices (types 1–4): send as-is
 */
class NalProcessor(private val onNal: (ByteArray, Boolean) -> Unit) {
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var loggedParams = false
    private var loggedWithheld = false
    private var idrCount = 0

    /** IDRs not sent because no SPS/PPS had been seen before them. */
    var withheldIdr = 0
        private set

    fun process(annexB: ByteArray) {
        for (nal in split(annexB)) {
            if (nal.isEmpty()) continue
            when (val type = nal[0].toInt() and 0x1F) {
                7    -> sps = spsForDash(nal)
                8    -> pps = nal
                5    -> emitIdr(nal)
                6, 9 -> Unit // SEI, AUD — discard
                else -> if (type in 1..4 || type in 10..12) onNal(nal, false)
            }
        }
    }

    private fun emitIdr(idr: ByteArray) {
        val s = sps; val p = pps
        if (!loggedParams && s != null && p != null) {
            loggedParams = true
            android.util.Log.i(TAG, "SPS=${s.hex()} PPS=${p.hex()}")
        }
        if (s == null || p == null) {
            withheldIdr++
            android.util.Log.w(TAG, "IDR with no SPS/PPS cached — withheld")
            if (!loggedWithheld) {
                loggedWithheld = true
                // Logged once, not per frame: if it happens it repeats at every IDR until the parameter sets arrive.
                com.reau.app.data.RideDiagnostics.log("stream", "IDR with no SPS/PPS cached — withheld, the dash cannot decode it")
            }
            return
        }
        val nal = s + START_CODE_4 + p + START_CODE_4 + idr
        if (++idrCount <= 3) android.util.Log.d(TAG, "emit IDR #$idrCount (${nal.size}B bundled)")
        onNal(nal, true)
    }

    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

    /** Split Annex-B stream on 4-byte (0x00000001) or 3-byte (0x000001) start codes. */
    private fun split(data: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        var start = -1
        var i = 0
        while (i < data.size) {
            val length = startCodeLength(data, i)
            if (length == 0) {
                i++
            } else {
                if (start >= 0) nals += data.copyOfRange(start, i)
                start = i + length
                i += length
            }
        }
        if (start in 0 until data.size) nals += data.copyOfRange(start, data.size)
        return nals
    }

    /** 4 or 3 when [data] has an Annex-B start code at [i] (00 00 00 01 or 00 00 01), else 0. */
    private fun startCodeLength(data: ByteArray, i: Int): Int = when {
        i + 2 >= data.size || data[i] != 0.toByte() || data[i + 1] != 0.toByte() -> 0
        data[i + 2] == 1.toByte() -> 3
        i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
        else -> 0
    }

    companion object {
        private val START_CODE_4 = byteArrayOf(0, 0, 0, 1)
        private const val TAG = "NalProcessor"
        private const val PROFILE_BASELINE = 0x42
        private const val LEVEL_4_1 = 0x29

        /**
         * The dash's firmware accepts one SPS shape (67 42 00 29…) before it will leave the loading state. MediaCodec emits
         * other constraint bytes (67 42 C0 29…) and, on some phones, another level (67 42 80 1F…, level 3.1; 0x1E, level 3.0):
         * for a Baseline SPS of level 4.1 or lower the constraint byte becomes 0x00 and the level 0x29. Neither touches
         * slice-header parsing, and a stream that fits a lower level fits 4.1 too, so this is safe. A higher level, or another
         * profile (Main, High: the dash rejects them whatever the bytes say), is left as it came.
         */
        internal fun spsForDash(sps: ByteArray): ByteArray {
            val baseline = sps.size >= 4 && (sps[0].toInt() and 0x1F) == 7 && (sps[1].toInt() and 0xFF) == PROFILE_BASELINE
            if (!baseline || (sps[3].toInt() and 0xFF) > LEVEL_4_1) return sps
            val out = sps.copyOf()
            out[2] = 0x00
            out[3] = LEVEL_4_1.toByte()
            return out
        }
    }
}
