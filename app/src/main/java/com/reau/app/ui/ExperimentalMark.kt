package com.reau.app.ui

/**
 * Every setting that is "experimental" (in Hindi "prayogik", written as \\u escapes in the pattern) or "de teste" has its letters in red, to say it is not yet the app's standard
 * and may be removed. A row is marked by what its own title and line say: writing "Experimental" or "(teste)" in the
 * text is all it takes for a new experiment to turn red.
 */
object ExperimentalMark {
    /**
     * "experimental", "teste" and the Hindi "prayogik" (26/11/2026: the options of 0.4.0 not yet tried on the bike are marked in four languages). A plain `\b` cannot
     * be used: the Devanagari vowel signs are combining marks (\p{M}), not word characters, so `\b` would cut that word in the middle of a syllable or
     * miss it next to a sign. The word must instead stand between characters that are not a letter, a mark, a digit or an underscore, which keeps "Testes"
     * (plural, a section's line) unmarked as before.
     */
    private val WORDS = Regex(
        """(?<![\p{L}\p{M}\p{N}_])(experimental|teste|\u092A\u094D\u0930\u093E\u092F\u094B\u0917\u093F\u0915)(?![\p{L}\p{M}\p{N}_])""",
        RegexOption.IGNORE_CASE,
    )

    /** Whether any of [texts] (a row's title, its line) says experimental or test. */
    fun marks(vararg texts: String?): Boolean = texts.any { it != null && WORDS.containsMatchIn(it) }
}
