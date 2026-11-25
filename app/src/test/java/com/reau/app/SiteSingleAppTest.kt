package com.reau.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The site describes the app of today, which is one app (`com.reau.app`): no Lite or Full edition to choose, no step that turns on an
 * Accessibility service, no "Try again" button (the owner's decision of 15/11/2026, in the three languages). What may still be said is
 * history that is true and useful, and each such phrase is in [allowed]: the migration note for whoever has the old Lite installed and the
 * statements that the Accessibility service is gone. A page that says anything else about them fails here.
 *
 * The three videos (installation, problems, everyday use) were remade for 0.3.0, one narrated file per language (`video.pt.mp4`, `.en.mp4`,
 * `.es.mp4`), so no picture or video says it is from an earlier version any more. Each narrated video has its own subtitles and the other two
 * languages' subtitles on the same timing, and each install page offers the video of its own language, with its own subtitles first and chosen.
 * Read from the files, as [SiteIntegrityTest] does.
 */
class SiteSingleAppTest {
    private val root = listOf(File(".."), File(".")).first { File(it, "gradle/wrapper").isDirectory }
    private val docs = File(root, "docs")
    private val languages = listOf("pt", "en", "es")
    private val videos = listOf("instalacao", "problemas", "uso")
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
        "pt" to listOf("antiga REau Lite", "Lite antiga", "a Lite tinha o pacote", "serviço de acessibilidade que as versões anteriores usavam", "configurações de Acessibilidade do Android"),
        "en" to listOf("old REau Lite", "old Lite", "the Lite had the package", "accessibility service that earlier versions used", "Android’s Accessibility settings"),
        "es" to listOf("antigua REau Lite", "Lite antigua", "la Lite tenía el paquete", "servicio de accesibilidad que usaban las versiones anteriores", "ajustes de Accesibilidad de Android"),
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
    fun `every video exists once per narration language, small enough, with a poster and subtitles in the three languages on the same timing`() {
        for (video in videos) for (narration in languages) {
            val name = "$video.$narration"
            val mp4 = File(docs, "videos/$name.mp4")
            assertTrue("$name.mp4 is there", mp4.isFile)
            assertTrue("$name.mp4 is under 6.5 MB (the repository carries nine of them): ${mp4.length()}", mp4.length() in 1_000_000L..6_500_000L)
            assertTrue("$name.jpg poster", File(docs, "videos/$name.jpg").isFile)
            val own = cuesOf(File(docs, "videos/$name.vtt"))
            assertEquals("$name.vtt", emptyList<String>(), problemsOf(own))
            for (sub in languages - narration) {
                val other = cuesOf(File(docs, "videos/$name.$sub.vtt"))
                assertEquals("$name.$sub.vtt: cue by cue the times of the narration", own.map { it.from to it.to }, other.map { it.from to it.to })
                assertTrue("$name.$sub.vtt: another language", other.map { it.text } != own.map { it.text })
            }
        }
    }

    @Test
    fun `each install page offers the video of its own language, with the poster, its subtitles chosen and the other two as options`() {
        val said = mapOf(
            "pt" to "Cada página tem a narração no seu idioma",
            "en" to "Each page has the narration in its own language",
            "es" to "Cada página tiene la narración en su propio idioma",
        )
        for (language in languages) {
            val html = File(docs, "$language/install.html").readText()
            assertTrue("$language: says the narration is in its language", said.getValue(language) in html)
            val players = Regex("""<video [^>]*>.*?</video>""", RegexOption.DOT_MATCHES_ALL).findAll(html).map { it.value }.toList()
            assertEquals("$language: players", videos.size, players.size)
            for ((index, video) in videos.withIndex()) {
                val player = players[index]
                val opening = player.substringBefore(">") + ">"
                for (attribute in listOf("controls", "playsinline", "preload=\"metadata\"", "poster=\"../videos/$video.$language.jpg\"", "aria-label=\"")) {
                    assertTrue("$language/$video: $attribute", attribute in opening)
                }
                assertTrue("$language/$video: the narrated file of its language", "<source src=\"../videos/$video.$language.mp4\" type=\"video/mp4\">" in player)
                val tracks = Regex("""<track [^>]*>""").findAll(player).map { it.value }.toList()
                assertEquals("$language/$video: tracks", languages.size, tracks.size)
                val own = tracks.first()
                assertTrue("$language/$video: its own subtitles first and chosen: $own", "src=\"../videos/$video.$language.vtt\"" in own && "srclang=\"$language\"" in own && own.endsWith(" default>") && "label=" in own)
                for (sub in languages - language) {
                    val found = tracks.count { "src=\"../videos/$video.$language.$sub.vtt\"" in it && "srclang=\"$sub\"" in it && "label=" in it && "default" !in it }
                    assertEquals("$language/$video: the $sub subtitles as an option", 1, found)
                }
            }
        }
    }

    @Test
    fun `the script swaps the narrated file, the poster and the subtitles when the language changes in the page`() {
        val js = File(docs, "assets/site.js").readText()
        assertTrue("the function is there", "function syncVideo(a,b){" in js)
        val body = js.substringAfter("function syncVideo(a,b){").substringBefore("function sync(a,b){")
        for (part in listOf("poster", "source,track", "a.load()", "currentTime", "textTracks")) assertTrue("syncVideo: $part", part in body)
        assertTrue("sync calls it for a video", "if(a.tagName==='VIDEO'){syncVideo(a,b);return;}" in js)
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
    fun `only the picture that was not redone says it is from an earlier version, and no video does`() {
        val notice = mapOf(
            "pt" to "Imagem de uma versão anterior do app.",
            "en" to "Picture from an earlier version of the app.",
            "es" to "Imagen de una versión anterior de la app.",
        )
        for (language in languages) {
            for (page in pages(language).filter { it.name != "faq.html" }) {
                val html = page.readText()
                assertFalse("${page.relativeTo(docs)}: a notice class", "class=\"old\"" in html)
                assertFalse("${page.relativeTo(docs)}: a notice text", notice.getValue(language) in html)
            }
            // The "Connect to device" request is the one picture with no new capture (Android's own dialog, not reachable on the emulator).
            val faq = File(docs, "$language/faq.html").readText()
            val said = Regex("""class="old">\s*([^<]*)<""").findAll(faq).map { it.groupValues[1].trim() }.toList()
            assertEquals("$language/faq.html: the notices", listOf(notice.getValue(language)), said)
            val figure = Regex("""<figure>(?:(?!</figure>).)*connect_dialog\.jpg.*?</figure>""", RegexOption.DOT_MATCHES_ALL).find(faq)?.value ?: error("$language: no connect_dialog figure")
            assertTrue("$language: the notice is on the connect_dialog picture", notice.getValue(language) in figure)
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

/** What is wrong with the cues of one subtitle file: they go forward with no overlap, from 0, and have text; there are enough of them. */
private fun problemsOf(cues: List<SiteVttCue>): List<String> {
    if (cues.size < 10) return listOf("too few cues")
    val problems = mutableListOf<String>()
    if (cues.first().from != 0L) problems += "the narration does not start at 0"
    cues.forEachIndexed { i, cue ->
        if (cue.text.isBlank()) problems += "cue ${i + 1} has no text"
        if (cue.to <= cue.from) problems += "cue ${i + 1} ends before it starts"
        if (i > 0 && cue.from < cues[i - 1].to) problems += "cue ${i + 1} starts before the one before it ends"
    }
    return problems
}
