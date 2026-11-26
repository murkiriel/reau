package com.reau.app.dash.protocol

import android.icu.text.Transliterator
import com.reau.app.data.RideDiagnostics
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the dash cannot draw, written in the Latin alphabet before it is cut down to Latin-1 (decision of 25/11/2026, test on the dash of 26/11/2026). The dash reads one
 * byte a character (ISO-8859-1) and has no font for Devanagari, Arabic, Cyrillic or Chinese: the owner's street-name test showed no letter at all, so a street, a title,
 * a song or a contact in another script came out as a row of question marks. Now each RUN of characters past Latin-1 is transliterated by ICU
 * (`Any-Latin; Latin-ASCII`, API 24+, which the app's minSdk 29 has) and the letters Latin-1 already has are not touched: "Rua José" stays byte for byte, and "José चौक"
 * becomes "José cauka", not "Jose cauka". What the transliteration cannot do (an emoji, a mark) is left for [DashCommands.legible] to turn into a question mark, as before.
 *
 * The [Transliterator] is made once (making one is costly) and used under a lock (an instance is not for two threads at once). Any failure, including the class not being
 * there (the JVM tests have no `android.icu`), gives the old behaviour. The line in the ride log is written once per session and never carries the text.
 */
internal object DashTransliteration {
    /** The line of the ride log, once per session: it says that it happened and nothing of what was written. */
    const val LOG_LINE = "dash text transliterated to Latin for the dash (it has no font for it)"

    private const val LATIN1_LIMIT = 0x100
    private const val ID = "Any-Latin; Latin-ASCII"

    /** The transliteration of one run of characters past Latin-1; null when it cannot be done. */
    internal fun interface Engine {
        fun toLatin(run: String): String?
    }

    /** For the tests, which have no ICU: the engine to use in place of ICU's, null for ICU's own. */
    @Volatile
    internal var engine: Engine? = null

    /** Where the one line goes; the tests take it in place of the ride log. */
    @Volatile
    internal var sink: (String) -> Unit = { RideDiagnostics.log("dash", it) }

    private val told = AtomicBoolean(false)

    /** A new dash session: the line is written again the first time a text is transliterated. */
    fun newSession() {
        told.set(false)
    }

    /**
     * [text] with each run of characters past Latin-1 in the Latin alphabet; the text itself when it has no such character (no work, no line). A run that cannot be
     * transliterated stays as it is. The log line is written the first time something really changed in the session.
     */
    fun toLatin(text: String): String {
        if (text.all { it.code < LATIN1_LIMIT }) return text
        val out = StringBuilder(text.length * 2)
        var changed = false
        var from = 0
        while (from < text.length) {
            val outside = text[from].code >= LATIN1_LIMIT
            var to = from
            while (to < text.length && (text[to].code >= LATIN1_LIMIT) == outside) to++
            val piece = text.substring(from, to)
            val latin = if (outside) latinOf(piece) else null
            if (latin != null) changed = true
            out.append(latin ?: piece)
            from = to
        }
        if (changed && told.compareAndSet(false, true)) runCatching { sink(LOG_LINE) }
        return out.toString()
    }

    private fun latinOf(run: String): String? = runCatching { (engine ?: IcuEngine).toLatin(run) }.getOrNull()

    private object IcuEngine : Engine {
        private val transliterator: Transliterator? by lazy { runCatching { Transliterator.getInstance(ID) }.getOrNull() }
        private val lock = Any()

        override fun toLatin(run: String): String? {
            val one = transliterator ?: return null
            return synchronized(lock) { one.transliterate(run) }
        }
    }
}
