package com.reau.app.dash.video

/**
 * The line of the ride log about a stream's SPS (26/11/2026): the bytes as the encoder made them and as they go to the dash ([NalProcessor.spsForDash] rewrites the
 * constraint byte and the level of a Baseline one), and what the encoder says in them, the profile and the level. An SPS is a few dozen bytes of picture size and
 * coding tools, nothing of the rider's, so it is written whole; it is what tells one encoder's stream from another's. Pure text.
 */
internal object SpsReport {
    /** `encoder sps: profile=Baseline(66) level=4.1 constraints=0x80; original=67 42 80 29 …; sent=67 42 00 29 …` (the profile and level are the original's). */
    fun line(original: ByteArray, sent: ByteArray): String =
        "encoder sps: profile=${profile(original)} level=${level(original)} constraints=${constraints(original)}; original=${hex(original)}; sent=${hex(sent)}"

    /** The profile of the SPS NAL unit ([sps] starts with its header byte, 0x67): `Baseline(66)`, or `?` when it is too short to say. */
    fun profile(sps: ByteArray): String {
        val idc = sps.getOrNull(1)?.toInt()?.and(0xFF) ?: return "?"
        val name = when (idc) {
            66 -> "Baseline"
            77 -> "Main"
            88 -> "Extended"
            100 -> "High"
            else -> null
        }
        return if (name == null) "$idc" else "$name($idc)"
    }

    /** The level as `4.1` (level_idc 41; 9 is level 1b), or `?`. */
    fun level(sps: ByteArray): String {
        val idc = sps.getOrNull(3)?.toInt()?.and(0xFF) ?: return "?"
        return if (idc == LEVEL_1B) "1b" else "${idc / 10}.${idc % 10}"
    }

    private fun constraints(sps: ByteArray): String = sps.getOrNull(2)?.let { "0x%02X".format(it) } ?: "?"

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }

    private const val LEVEL_1B = 9
}
