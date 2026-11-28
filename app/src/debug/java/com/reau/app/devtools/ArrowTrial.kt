package com.reau.app.devtools

import com.reau.app.dash.nav.DashManeuverGlyphs
import com.reau.app.data.RideDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug builds only: the arrow test. The field roteiro has the rider photograph the dash, parked, with each arrow of [DashManeuverGlyphs.testSequence]
 * (the debug card that was meant for it is on no screen); this sends them. With the dash connected, [next] and [previous] step through the sequence and
 * the dash's session sends that byte in the maneuver bubble (05 02), by the path it sends the real arrow ([com.reau.app.dash.DashSession.navPacketNow]
 * and the route card), with or without guidance; [stop] gives the real arrow back. [label] is what the screen writes for the photo: the byte in hex and
 * its expected name. The release build has an [ArrowTrial] that answers none (`src/release`), so nothing in a release can change the arrow.
 *
 * The street-name test lives here too (it is the same kind of tool: what the dash draws in the title and the maneuver bubble). [sendStreet] puts [DEVANAGARI_STREET]
 * where the real street goes ([com.reau.app.dash.DashSession] reads [street]), and the one name goes as UTF-8 (the real streets go as Latin-1, which has no letter
 * for it): whoever tests sees whether the dash draws Devanagari. [stopStreet] gives the real street back.
 */
@Suppress("TooManyFunctions")
object ArrowTrial {
    private val current = MutableStateFlow<Int?>(null)

    /** The byte being sent now, or null when the test is off; what the screen shows. */
    val now: StateFlow<Int?> = current

    /** The byte for the dash's session to send in place of the real arrow; null: the real one. */
    fun byte(): Int? = current.value

    /** The first byte of the sequence when the test is off, else the one after the byte now (the last wraps to the first). */
    fun next() = go(DashManeuverGlyphs.nextCandidate(current.value ?: -1))

    /** The last byte of the sequence when the test is off, else the one before the byte now (the first wraps to the last). */
    fun previous() = go(DashManeuverGlyphs.previousCandidate(current.value ?: Int.MAX_VALUE))

    /** Ends the test: the dash gets the real arrow again at its next second. */
    fun stop() {
        if (current.value == null) return
        current.value = null
        RideDiagnostics.log("debug", "arrow test ended: the real arrow goes back (debug build only)")
    }

    /** "चाँदनी चौक" (Chandni Chowk), the street name of the test, as escapes so the source stays ASCII. */
    const val DEVANAGARI_STREET = "\u091A\u093E\u0901\u0926\u0928\u0940 \u091A\u094C\u0915"

    private val streetNow = MutableStateFlow<String?>(null)

    /** The name being sent as the street now, or null when the street test is off; what the screen shows. */
    val streetSent: StateFlow<String?> = streetNow

    /** The street for the dash's session to send in place of the real one; null: the real one. */
    fun street(): String? = streetNow.value

    /** Starts the street-name test: the dash gets [DEVANAGARI_STREET] in the title and the bubble's street until [stopStreet]. */
    fun sendStreet() {
        streetNow.value = DEVANAGARI_STREET
    }

    /** Ends the street-name test: the real street (or the title) goes back at the dash's next second. */
    fun stopStreet() {
        if (streetNow.value == null) return
        streetNow.value = null
        RideDiagnostics.log("debug", "street name test ended: the real street goes back (debug build only)")
    }

    /**
     * [text] as the dash's street field gets it when it is the name of the test: UTF-8, cut at [max] bytes (the field's size) on a letter's edge so no
     * letter is half sent; null for any other text (the real streets keep their Latin-1) and when the test is off.
     */
    fun streetBytes(text: String, max: Int): ByteArray? {
        if (text != streetNow.value) return null
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            if (out.size() + bytes.size > max) break
            out.write(bytes)
            i += Character.charCount(cp)
        }
        return out.toByteArray()
    }

    private fun go(code: Int) {
        current.value = code
        RideDiagnostics.log("debug", "arrow test: ${label(code)} (debug build only)")
    }

    /** What the screen and the log say of [code]: `0x2D  Bear off to the right  (Abrir à direita)`; a byte with no name says it has none yet. */
    fun label(code: Int): String {
        val en = DashManeuverGlyphs.describe(code)
        val pt = DashManeuverGlyphs.describePt(code)
        val name = when {
            en == null && code >= DashManeuverGlyphs.HIDDEN_BUBBLE -> "hides the bubble"
            en == null -> "no name yet: write down what the panel draws"
            else -> en
        }
        return "0x%02X  %s%s".format(code, name, if (pt != null) "  ($pt)" else "")
    }

    /** The two words of the label that the screen takes from its resources: a byte that hides the bubble, and one with no name yet. */
    data class LabelWords(val hidden: String, val unnamed: String)

    /**
     * What the screen writes for the photo, in the app's [language] (`pt`, `es`, anything else is English): the byte in hex and the name expected for it
     * (`0x2D  Abrir à direita`). The log keeps the two-language [label].
     */
    fun label(code: Int, language: String, words: LabelWords): String {
        val name = when (language) {
            "pt" -> DashManeuverGlyphs.describePt(code)
            "es" -> ArrowNamesEs.name(code)
            else -> DashManeuverGlyphs.describe(code)
        }
        val text = name ?: if (code >= DashManeuverGlyphs.HIDDEN_BUBBLE) words.hidden else words.unnamed
        return "0x%02X  %s".format(code, text)
    }
}
