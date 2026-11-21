package com.reau.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The site describes the app of today, which is one app (`com.reau.app`): no Lite or Full edition to choose, no step that turns on an
 * Accessibility service, no "Try again" button (the owner's decision of 15/11/2026, in the three languages). What may still be said is
 * history that is true and useful, and each such phrase is in [allowed]: the migration note for whoever has the old Lite installed, and the
 * note that the videos were recorded with the old Full edition. A page that says anything else about them fails here.
 *
 * The videos and the screenshots are from before the single app (the owner chose to change only the text), so each old picture says so, and
 * each video has its subtitles in Portuguese, English and Spanish, with the same warning in the first 3 seconds. Read from the files, as
 * [SiteIntegrityTest] does.
 */
class SiteSingleAppTest {
    private val root = listOf(File(".."), File(".")).first { File(it, "gradle/wrapper").isDirectory }
    private val docs = File(root, "docs")
    private val languages = listOf("pt", "en", "es")
    private val videos = listOf("lite", "problemas", "uso")
    private fun pages(language: String) = File(docs, language).listFiles { f -> f.extension == "html" }!!.sortedBy { it.name }

    /** What a page may not say, by language: the old editions, the Accessibility step and the button that went with the service. */
    private val forbidden: Map<String, List<Regex>> = mapOf(
        "pt" to listOf(
            Regex("""\bLite\b"""), Regex("""\bCompleta\b"""), Regex("""acessibilidade""", RegexOption.IGNORE_CASE), Regex("""Tentar de novo"""),
        ),
        "en" to listOf(
            Regex("""\bLite\b"""), Regex("""\bFull edition\b"""), Regex("""accessibility""", RegexOption.IGNORE_CASE), Regex("""Try again"""),
        ),
        "es" to listOf(
            Regex("""\bLite\b"""), Regex("""\bCompleta\b"""), Regex("""accesibilidad""", RegexOption.IGNORE_CASE), Regex("""Intentar de nuevo|Reintentar"""),
        ),
    )

    /** The only phrases that may carry one of the forbidden words: history, never a step of today. */
    private val allowed: Map<String, List<String>> = mapOf(
        "pt" to listOf("antiga REau Lite", "Lite antiga", "a Lite tinha o pacote", "antiga edição Completa"),
        "en" to listOf("old REau Lite", "old Lite", "the Lite had the package", "old Full edition"),
        "es" to listOf("antigua REau Lite", "Lite antigua", "la Lite tenía el paquete", "antigua edición Completa"),
    )

    /** What the pages say next to a picture or a video of an earlier version of the app. */
    private val pictureNotice = mapOf(
        "pt" to "Imagem de uma versão anterior do app.",
        "en" to "Picture from an earlier version of the app.",
        "es" to "Imagen de una versión anterior de la app.",
    )

    /** The first cue of every subtitle file (the first 3 seconds): the video is from before the single app. */
    private val cueNotice = mapOf(
        "pt" to "Vídeo de uma versão anterior: o app de hoje não tem a Lite nem o passo da Acessibilidade",
        "en" to "Video from an earlier version: today's app has no Lite and no Accessibility step",
        "es" to "Video de una versión anterior: la app de hoy no tiene la Lite ni el paso de Accesibilidad",
    )

    private fun offenders(language: String, html: String): List<String> {
        var text = html
        for (phrase in allowed.getValue(language).sortedByDescending { it.length }) text = text.replace(phrase, "")
        return forbidden.getValue(language).flatMap { rule -> rule.findAll(text).map { it.value }.toList() }
    }

    @Test
    fun `no page offers the Lite or the Full edition, an Accessibility step or a Try again button`() {
        for (language in languages) for (page in pages(language)) {
            assertEquals("${page.relativeTo(docs)}", emptyList<String>(), offenders(language, page.readText()))
        }
        for (page in listOf(File(docs, "index.html"), File(docs, "404.html"))) for (language in languages) {
            assertEquals("${page.relativeTo(docs)} ($language)", emptyList<String>(), offenders(language, page.readText()))
        }
    }

    @Test
    fun `every allowed phrase is still said by some page of its language`() {
        for (language in languages) {
            val text = pages(language).joinToString("\n") { it.readText() }
            for (phrase in allowed.getValue(language)) assertTrue("$language: '$phrase' is in no page (take it off the list)", phrase in text)
        }
    }

    @Test
    fun `the rule catches an old step that is not on the list`() {
        val old = mapOf(
            "pt" to listOf("Escolha a edição Lite", "Ligue a Acessibilidade", "Toque em Tentar de novo", "edição Completa"),
            "en" to listOf("Choose the Lite edition", "Turn on Accessibility", "Tap Try again", "the Full edition"),
            "es" to listOf("Elige la edición Lite", "Activa Accesibilidad", "Toca Intentar de nuevo", "edición Completa"),
        )
        for (language in languages) {
            for (sample in old.getValue(language)) assertTrue("$language: $sample", offenders(language, sample).isNotEmpty())
            for (phrase in allowed.getValue(language)) assertEquals("$language: $phrase", emptyList<String>(), offenders(language, "<p>$phrase</p>"))
        }
    }

    @Test
    fun `each video has valid subtitles in the three languages and each starts with the warning`() {
        for (video in videos) {
            val byLanguage = languages.associateWith { cuesOf(File(docs, "videos/$video.$it.vtt")) }
            for (language in languages) {
                assertEquals("$video.$language.vtt", emptyList<String>(), problemsOf(byLanguage.getValue(language), cueNotice.getValue(language)))
            }
            // The English and Spanish subtitles follow the Portuguese ones cue by cue, with the same times.
            val times = byLanguage.getValue("pt").map { it.from to it.to }
            for (language in listOf("en", "es")) {
                assertEquals("$video.$language.vtt: the times of the Portuguese one", times, byLanguage.getValue(language).map { it.from to it.to })
            }
        }
    }

    @Test
    fun `each install page offers the three subtitle tracks of every video, its own language first`() {
        val said = mapOf(
            "pt" to "as legendas existem em português, em inglês e em espanhol",
            "en" to "subtitles are available in English, Portuguese and Spanish",
            "es" to "hay subtítulos en español, inglés y portugués",
        )
        for (language in languages) {
            val html = File(docs, "$language/install.html").readText()
            assertTrue("$language: says the three languages", said.getValue(language) in html)
            val tracks = Regex("""<track [^>]*>""").findAll(html).map { it.value }.toList()
            assertEquals("$language: tracks", videos.size * languages.size, tracks.size)
            for (video in videos) for (track in languages) {
                val found = tracks.count { "src=\"../videos/$video.$track.vtt\"" in it && "srclang=\"$track\"" in it }
                assertEquals("$language: $video.$track.vtt", 1, found)
            }
            val chosen = tracks.filter { it.endsWith(" default>") }
            assertEquals("$language: one default track per video", videos.size, chosen.size)
            for (track in chosen) assertTrue("$language: the default track is in $language: $track", "srclang=\"$language\"" in track)
        }
    }

    @Test
    fun `the script offers the Spanish track to the Spanish pages and gives no language another one`() {
        val js = File(docs, "assets/site.js").readText()
        assertTrue("the function is there", "function tracks(l){" in js)
        val body = js.substringAfter("function tracks(l){").substringBefore("function apply(")
        assertTrue("the function has its body", "textTracks" in body && "srclang" in body)
        assertFalse("tracks() must not change the language it was given", Regex("""\bl\s*=[^=]""").containsMatchIn(body))
    }

    @Test
    fun `the old pictures and the videos say they are from an earlier version, in the page's own language`() {
        for (language in languages) {
            val notice = pictureNotice.getValue(language)
            for ((page, count) in listOf("faq.html" to 9, "install.html" to 4)) {
                val html = File(docs, "$language/$page").readText()
                val said = Regex("""class="old">\s*([^<]*)<""").findAll(html).map { it.groupValues[1].trim() }.toList()
                assertEquals("$language/$page: how many notices", count, said.size)
                assertEquals("$language/$page: the words", List(count) { notice }, said)
            }
            val faq = File(docs, "$language/faq.html").readText()
            val figure = Regex("""lite_warn\.jpg.*?</figure>""", RegexOption.DOT_MATCHES_ALL).find(faq)?.value ?: error("$language: no lite_warn figure")
            assertTrue("$language: the picture of the old warning carries the notice", notice in figure)
        }
    }
}

private class SiteVttCue(val from: Long, val to: Long, val text: String)

private fun millis(time: String): Long {
    val parts = Regex("""(\d\d):(\d\d):(\d\d)\.(\d{3})""").matchEntire(time)?.groupValues ?: error("not a WebVTT time: $time")
    return ((parts[1].toLong() * 60 + parts[2].toLong()) * 60 + parts[3].toLong()) * 1000 + parts[4].toLong()
}

/** The cues of a WebVTT file, after checking its header and that each cue has its number (1, 2, 3...) and its times. */
private fun cuesOf(file: File): List<SiteVttCue> {
    assertTrue("${file.name} is there", file.isFile)
    val blocks = file.readText().replace("\r\n", "\n").trim().split("\n\n")
    assertEquals("${file.name}: header", "WEBVTT", blocks.first())
    return blocks.drop(1).mapIndexed { index, block ->
        val lines = block.split("\n")
        assertEquals("${file.name}: cue number", (index + 1).toString(), lines[0])
        val times = Regex("""(\S+) --> (\S+)""").matchEntire(lines[1])?.groupValues ?: error("${file.name}: no times in cue ${index + 1}")
        SiteVttCue(millis(times[1]), millis(times[2]), lines.drop(2).joinToString("\n"))
    }
}

/**
 * What is wrong with the cues of one subtitle file: the first is the warning, in the first 3 seconds (it may overlap the first spoken cue,
 * since the narration starts at 0), and the spoken ones go forward with no overlap and have text.
 */
private fun problemsOf(cues: List<SiteVttCue>, notice: String): List<String> {
    if (cues.size <= 3) return listOf("too few cues")
    val problems = mutableListOf<String>()
    if (cues.first().text != notice) problems += "the first cue is not the warning: ${cues.first().text}"
    if (cues.first().from != 0L || cues.first().to !in 1L..3000L) problems += "the warning is not within the first 3 s"
    val spoken = cues.drop(1)
    if (spoken.first().from != 0L) problems += "the narration does not start at 0"
    spoken.forEachIndexed { i, cue ->
        if (cue.text.isBlank()) problems += "cue ${i + 2} has no text"
        if (cue.to <= cue.from) problems += "cue ${i + 2} ends before it starts"
        if (i > 0 && cue.from < spoken[i - 1].to) problems += "cue ${i + 2} starts before the one before it ends"
    }
    return problems
}
