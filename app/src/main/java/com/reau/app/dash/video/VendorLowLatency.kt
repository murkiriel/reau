package com.reau.app.dash.video

/**
 * The Qualcomm encoder's own low-latency key, asked of the dash's encoder with the experimental switch
 * ([com.reau.app.settings.REauSettings.experimentEncoderLowLatency]): `vendor.qti-ext-enc-low-latency.enable` = 1 asks the encoder to hand each frame
 * back instead of holding it. It is the maker's extension, so it is asked only of a Qualcomm encoder (`c2.qti.` or `OMX.qcom.`); any other is asked
 * as always. Without the switch the format has no such key.
 *
 * A codec that refuses the format with it is asked again without it, still with the realtime keys ([DashEncoder.startNamed]); [ON], [FALLBACK] and
 * [NOT_QUALCOMM] are what the `encoder:` line of the ride log says of it.
 */
internal object VendorLowLatency {
    const val KEY = "vendor.qti-ext-enc-low-latency.enable"

    /** Said in the `encoder:` line when the key was asked with the format. */
    const val ON = "qcLowLatency=on"

    /** Said when the codec refused the format with the key and was asked again without it. */
    const val FALLBACK = "qcLowLatency=fallback"

    /** Said when the switch is on but the encoder is not a Qualcomm one: the key is not asked. */
    const val NOT_QUALCOMM = "qcLowLatency=skipped (not a Qualcomm encoder)"

    /** Whether the encoder [name] is Qualcomm's. An unknown name (the system's own pick) is not known to be. */
    fun isQualcomm(name: String?): Boolean = name != null && (name.startsWith("c2.qti.") || name.startsWith("OMX.qcom."))

    /** Whether the key is asked of the encoder [name]: the switch is [on] and the encoder is Qualcomm's. */
    fun asked(on: Boolean, name: String?): Boolean = on && isQualcomm(name)

    /** The key to set in the encoder's format when [asked]: none otherwise (the format is then as it always was). */
    fun keysFor(asked: Boolean): Map<String, Int> = if (asked) mapOf(KEY to 1) else emptyMap()

    /** What the `encoder:` line adds for the encoder [name]: nothing with the switch off. */
    fun tag(on: Boolean, name: String?): String = when {
        !on -> ""
        isQualcomm(name) -> " $ON"
        else -> " $NOT_QUALCOMM"
    }
}
