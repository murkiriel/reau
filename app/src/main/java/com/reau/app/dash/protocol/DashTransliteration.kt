package com.reau.app.dash.protocol

import android.icu.text.Transliterator
import com.reau.app.data.RideDiagnostics
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * What the dash cannot draw, written in the Latin alphabet before it is cut down to Latin-1 (decision of 25/11/2026, test on the dash of 26/11/2026). The dash reads one
 * byte a character (ISO-8859-1) and has no font for Devanagari, Arabic, Cyrillic or Chinese: the owner's street-name test showed no letter at all, so a street, a title,
 * a song or a contact in another script came out as a row of question marks. Now each RUN of characters past Latin-1 is transliterated by ICU
 * (`Any-Latin; Latin-ASCII`, API 24+, which the app's minSdk 29 has) and the letters Latin-1 already has are not touched: "Rua José" stays byte for byte, and "José चौक"
 * becomes "José cauka", not "Jose cauka". What the transliteration cannot do (an emoji, a mark) is left for [DashCommands.legible] to turn into a question mark, as before.
 *
 * The [Transliterator] is made once (making one is costly) and used under a lock (an instance is not for two threads at once). It is warmed up at the start of each dash
 * session on an IO coroutine, so the first title in another script does not make the thread that reads Android Auto pay for it (review of 26/11/2026, S1). A failure to make
 * it is not kept for ever: the next ask tries again, at most twice a session (S2). Any failure, including the class not being there (the JVM tests have no `android.icu`),
 * gives the old behaviour. The line in the ride log is written once per session, only when a run really changed, and never carries the text.
 *
 * Characters that draw nothing (zero-width joiners and non-joiners, direction marks, the byte-order mark, variation selectors) are dropped first: ICU leaves them in, and
 * past Latin-1 each one would become a question mark in the middle of a good word (review of 26/11/2026, C1).
 */
internal object DashTransliteration {
    /** The line of the ride log, once per session: it says that it happened and nothing of what was written. */
    const val LOG_LINE = "dash text transliterated to Latin for the dash (it has no font for it)"

    private const val LATIN1_LIMIT = 0x100
    private const val ID = "Any-Latin; Latin-ASCII"

    /** The first try and one more: a failure that was a passing one (ICU data still loading) gets a second chance in the same session, not a hundred. */
    private const val MAX_TRIES_PER_SESSION = 2

    /** Characters of category Cf and the variation selectors that draw nothing: ZWSP..RLM, the bidi embeddings, word joiner..isolates, BOM, VS1..VS16. */
    private val INVISIBLE = listOf(0x200B..0x200F, 0x202A..0x202E, 0x2060..0x2069, 0xFEFF..0xFEFF, 0xFE00..0xFE0F)

    /** The transliteration of one run of characters past Latin-1; null when it cannot be done. */
    internal fun interface Engine {
        fun toLatin(run: String): String?
    }

    /** For the tests, which have no ICU: the engine to use in place of ICU's, null for ICU's own. */
    @Volatile
    internal var engine: Engine? = null

    /** Makes ICU's engine (null when it cannot); the tests put their own to count the tries. */
    @Volatile
    internal var maker: () -> Engine? = { IcuEngine.make() }

    /** Where the one line goes; the tests take it in place of the ride log. */
    @Volatile
    internal var sink: (String) -> Unit = { RideDiagnostics.log("dash", it) }

    private val told = AtomicBoolean(false)
    private val tries = AtomicInteger(0)
    private val lock = Any()
    private var made: Engine? = null

    /**
     * A new dash session: the line is written again the first time a text is transliterated, a failed engine may be tried again, and (with a [scope]) the engine is made now
     * on an IO coroutine instead of by the first title. No thread of its own: the session's scope already has the IO pool.
     */
    fun newSession(scope: CoroutineScope? = null) {
        told.set(false)
        tries.set(0)
        scope?.launch(Dispatchers.IO) { if (engine == null) obtain() }
    }

    /**
     * [text] with each run of characters past Latin-1 in the Latin alphabet; the text itself when it has no such character (no work, no line). A run that cannot be
     * transliterated stays as it is. The log line is written the first time something really changed in the session.
     */
    fun toLatin(raw: String): String {
        val text = withoutInvisible(raw)
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
            // An emoji or a symbol comes back from ICU as it went in: that is not a transliteration, and must not use up the line of the session (C3).
            if (latin != null && latin != piece) changed = true
            out.append(latin ?: piece)
            from = to
        }
        if (changed && told.compareAndSet(false, true)) runCatching { sink(LOG_LINE) }
        return out.toString()
    }

    /** [text] without the characters that draw nothing; the same object when there are none (Latin-1 has none of these, so it never changes). */
    internal fun withoutInvisible(text: String): String {
        if (text.none { isInvisible(it) }) return text
        return buildString(text.length) { for (c in text) if (!isInvisible(c)) append(c) }
    }

    /** For the tests: the engine made is forgotten, so the next ask makes one again. */
    internal fun forgetEngine() = synchronized(lock) { made = null }

    private fun isInvisible(c: Char) = INVISIBLE.any { c.code in it }

    private fun latinOf(run: String): String? = runCatching { (engine ?: obtain())?.toLatin(run) }.getOrNull()

    /** The engine of ICU, made on the first ask (or by the warm-up); a failure is not kept: the next ask tries again, up to [MAX_TRIES_PER_SESSION] a session. */
    private fun obtain(): Engine? = synchronized(lock) {
        made ?: if (tries.get() >= MAX_TRIES_PER_SESSION) {
            null
        } else {
            tries.incrementAndGet()
            runCatching { maker() }.getOrNull().also { made = it }
        }
    }

    private object IcuEngine {
        fun make(): Engine? {
            val one = Transliterator.getInstance(ID) ?: return null
            return Engine { run -> synchronized(one) { one.transliterate(run) } }
        }
    }
}
