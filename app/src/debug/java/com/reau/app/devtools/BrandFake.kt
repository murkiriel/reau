package com.reau.app.devtools

import com.reau.app.data.RideDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug builds only: the phone's brand, faked (26/11/2026). The owner's phone is a Motorola, so the "Mantenha o REau vivo neste celular" card and the page with the
 * Xiaomi steps and the Autostart button never showed for him. With a faked brand, [com.reau.app.ui.KeepAliveGuide.current] answers that family, and everything that asks
 * it (the card's eligibility, the page's steps and ticks, the button, the ride log's line of the ticks) follows, as a Xiaomi or realme rider would see it. The other rules
 * of the card are not touched (the phone joined the dash, not answered yet, or the system closed REau after the answer), and the button on a Motorola faked as Xiaomi
 * fails on the two screens of the maker and falls to the app's details, which shows the chain at work. The release build has a [BrandFake] that answers none
 * (`src/release`), so in a release the brand is always the real one. In memory only: a restart of the app gives the real brand back.
 */
object BrandFake {
    /** What the row offers: the real brand (the default) or one of the two families that have steps of their own; [maker] is the name the guide reads as the maker. */
    enum class Choice(val maker: String?) { REAL(null), XIAOMI("xiaomi"), REALME("realme") }

    private val chosen = MutableStateFlow(Choice.REAL)

    /** The choice now; what the row shows. */
    val choice: StateFlow<Choice> = chosen

    /** The maker to read in place of the phone's own, or null for the real one. */
    fun maker(): String? = chosen.value.maker

    /** The line the log gets while a brand is faked, so a test's log is never taken for a real one. */
    internal fun line(maker: String) = "keepalive: brand faked as $maker (debug)"

    /** The line when the fake ends. */
    internal const val ENDED_LINE = "keepalive: brand fake ended, the real brand is back (debug)"

    /** Writes [line] when a brand is faked now; nothing otherwise. Called at the start of each projection and when the page opens, and by [choose]. */
    fun logWhenActive() {
        maker()?.let { RideDiagnostics.log("debug", line(it)) }
    }

    /** Picks [next] and says so in the log (the fake line, or the end of it). */
    fun choose(next: Choice) {
        chosen.value = next
        if (next == Choice.REAL) RideDiagnostics.log("debug", ENDED_LINE) else logWhenActive()
    }

    /** Back to the real brand without a line (tests). */
    internal fun reset() {
        chosen.value = Choice.REAL
    }
}
