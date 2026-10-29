package com.reau.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The terms of use: their version, the text in the three languages (assets/terms/) and what a screen needs to draw it.
 * The same text is the file TERMS_OF_USE.pt-br.md (and TERMS_OF_USE.md, TERMS_OF_USE.es.md) at the root of the repository (TermsTest holds them together). A new
 * version asks the rider to accept again: raise [VERSION] together with the "Versão N" and "Version N" lines of the texts.
 */
object Terms {
    const val VERSION = 3

    const val ASSET_PT = "terms/termos.pt.md"
    const val ASSET_EN = "terms/terms.en.md"
    const val ASSET_ES = "terms/terminos.es.md"

    /** The tabs of the terms screen, in order: the index [tabOf] gives. */
    const val TAB_PT = 0
    const val TAB_EN = 1
    const val TAB_ES = 2

    /** A piece of the text, as the screen draws it. */
    sealed interface Block {
        data class Title(val text: String) : Block
        data class Heading(val text: String) : Block
        data class Paragraph(val text: String) : Block
        data class Bullet(val text: String) : Block
        data class Quote(val text: String) : Block
    }

    fun needsAcceptance(acceptedVersion: Int): Boolean = acceptedVersion < VERSION

    /** Whether the screen opens in Portuguese: the app speaks Portuguese. */
    fun opensInPortuguese(appLanguage: String): Boolean = appLanguage.startsWith("pt", ignoreCase = true)

    /** The tab the screen opens on: the text in the language the app speaks (Portuguese for pt, Spanish for es, English for anything else). */
    fun tabOf(appLanguage: String): Int = when {
        opensInPortuguese(appLanguage) -> TAB_PT
        appLanguage.startsWith("es", ignoreCase = true) -> TAB_ES
        else -> TAB_EN
    }

    /** The asset's text: `# ` a title, `## ` a heading, `- ` a bullet, `> ` a quote; lines up to a blank one are one paragraph. */
    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        fun endParagraph() {
            if (paragraph.isNotEmpty()) blocks += Block.Paragraph(paragraph.toString())
            paragraph.clear()
        }
        for (raw in markdown.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() || line == "---" -> endParagraph()
                line.startsWith("## ") -> { endParagraph(); blocks += Block.Heading(line.removePrefix("## ")) }
                line.startsWith("# ") -> { endParagraph(); blocks += Block.Title(line.removePrefix("# ")) }
                line.startsWith("- ") -> { endParagraph(); blocks += Block.Bullet(line.removePrefix("- ")) }
                line.startsWith("> ") -> { endParagraph(); blocks += Block.Quote(line.removePrefix("> ")) }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line)
                }
            }
        }
        endParagraph()
        return blocks
    }

    fun load(context: Context, asset: String): List<Block> =
        parse(context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() })
}

/** The version of the terms the rider accepted, kept on the phone (0 until one was). */
class TermsStore(private val prefs: SharedPreferences) {
    fun accepted(): Int = prefs.getInt(KEY, 0)

    fun accept(version: Int = Terms.VERSION) {
        prefs.edit { putInt(KEY, version) }
    }

    companion object {
        private const val NAME = "reau_terms"
        private const val KEY = "accepted_version"

        fun open(context: Context) = TermsStore((context.applicationContext ?: context).getSharedPreferences(NAME, Context.MODE_PRIVATE))
    }
}
