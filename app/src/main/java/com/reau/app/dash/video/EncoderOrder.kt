package com.reau.app.dash.video

/**
 * Which AVC encoders to try for the dash's 526×300 Baseline stream, and in what order. Before, the first hardware
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
}
