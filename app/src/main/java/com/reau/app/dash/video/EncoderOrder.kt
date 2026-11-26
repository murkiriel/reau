package com.reau.app.dash.video

/**
 * Which AVC encoders to try for the dash's Baseline stream (526×300, or the picture a debug build chose), and in what order. Before, the first hardware
 * encoder in the list was the only one ever tried (three times, with fewer options each): one that does not take
 * 526×300, or not Baseline, left the dash with no picture and no other encoder was asked (audit of 20/10/2026). Pure, so
 * the JVM tests cover it; [DashEncoder] reads the phone's list and gives it the facts.
 */
internal object EncoderOrder {
    /**
     * One encoder of the phone's codec list. [sizeSupported] and [baselineSupported] are what its capabilities say; null
     * when they cannot be read (then it is still worth a try).
     */
    data class Candidate(
        val name: String,
        val hardware: Boolean,
        val sizeSupported: Boolean?,
        val baselineSupported: Boolean?,
    )

    /**
     * The names to try, in order: the hardware encoders first (a software one runs the CPU hot, which this project exists to
     * avoid), then the others, each group in the order the phone lists them. One that says outright it does not take the
     * size, or Baseline, is left out. An empty answer means "let the system pick" (the caller's last try).
     */
    fun names(candidates: List<Candidate>): List<String> =
        candidates
            .filter { it.sizeSupported != false && it.baselineSupported != false }
            .sortedBy { if (it.hardware) 0 else 1 }
            .map { it.name }

    /**
     * What the rider's [VideoEncoder] came to for this phone: the names to try in order and a short text for the ride log (null when the phone's own was asked
     * and nothing needs saying).
     */
    data class Pick(val names: List<String>, val note: String?)

    /**
     * Android's own software AVC encoders in the phone's list ([candidates] that take the size and Baseline and are not hardware): the ones of the Android
     * open source project first (`c2.android.avc.encoder`, then the older `OMX.google.h264.encoder`), found by what the list says and by the namespace
     * Android reserves for them, never by one fixed name. A vendor's software encoder is not Android's and stays out. Empty when the phone lists none.
     */
    fun softwareNames(candidates: List<Candidate>): List<String> =
        candidates
            .filter { it.sizeSupported != false && it.baselineSupported != false && !it.hardware }
            .filter { c -> ANDROID_SOFTWARE_PREFIXES.any { c.name.startsWith(it) } }
            .sortedBy { c -> ANDROID_SOFTWARE_PREFIXES.indexOfFirst { c.name.startsWith(it) } }
            .map { it.name }

    /**
     * The order for the rider's [preference] (26/11/2026), from [phone] (the order [names] gave) and [software] ([softwareNames]). [VideoEncoder.PHONE] is exactly
     * today's order, with no note. [VideoEncoder.SOFTWARE] puts Android's software encoder first and keeps the phone's own order after it, so an encoder that
     * fails to start still leaves the dash a picture; with no software encoder in the list it falls back to the phone's own and the note says so.
     */
    fun pick(preference: VideoEncoder, phone: List<String>, software: List<String>): Pick = when {
        preference == VideoEncoder.PHONE -> Pick(phone, null)
        software.isEmpty() -> Pick(phone, "software encoder asked, none in the phone's list: the phone's own is used")
        else -> Pick((software + phone).distinct(), "software encoder asked: ${software.first()} first")
    }

    // The namespaces of the encoders Android itself ships, in the order to prefer them (Codec2, then the older OpenMAX one).
    private val ANDROID_SOFTWARE_PREFIXES = listOf("c2.android.", "OMX.google.")
}
