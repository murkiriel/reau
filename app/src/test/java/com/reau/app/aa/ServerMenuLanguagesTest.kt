package com.reau.app.aa

import com.reau.app.aa.ServerMenuRun.Outcome
import com.reau.app.aa.ServerMenuRun.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Developer mode off" in any language Android Auto speaks (audit of 20/10/2026): with the phone in German, French, Italian or
 * Japanese it was told as "the menu did not open", because the help items that tell the menu without the developer mode were only
 * known in Portuguese, English and Spanish. Android Auto gives its own words for the server's items and the developer menu in the
 * phone's language ([ServerTexts.load]), so a menu that opened, has items and has none of them is the one without developer mode.
 */
class ServerMenuLanguagesTest {
    private class Language(
        val name: String,
        val start: String,
        val stop: String,
        val developer: String,
        val quit: String,
        val more: String,
        val help: List<String>,
    ) {
        /** What Android Auto answers when asked for its string resources by name, in this language. */
        fun resources(): (String) -> String? = { res ->
            mapOf(
                ServerTexts.RES_START to start, ServerTexts.RES_STOP to stop, ServerTexts.RES_DEVELOPER_SETTINGS to developer,
                ServerTexts.RES_QUIT_DEVELOPER to quit, ServerTexts.RES_MORE_OPTIONS to more,
            )[res]
        }
    }

    private val languages = listOf(
        Language("de", "Head-Unit-Server starten", "Head-Unit-Server beenden", "Entwicklereinstellungen", "Entwicklermodus beenden", "Weitere Optionen", listOf("Hilfe und Feedback", "Feedback senden")),
        Language("fr", "Démarrer le serveur de l’unité principale", "Arrêter le serveur de l’unité principale", "Paramètres pour les développeurs", "Quitter le mode développeur", "Plus d’options", listOf("Aide et commentaires", "Envoyer des commentaires")),
        Language("it", "Avvia server dell’unità principale", "Arresta server dell’unità principale", "Impostazioni sviluppatore", "Esci dalla modalità sviluppatore", "Altre opzioni", listOf("Guida e feedback", "Invia feedback")),
        Language("ja", "ヘッドユニットサーバーを起動", "ヘッドユニットサーバーを停止", "デベロッパー設定", "デベロッパー モードを終了", "その他のオプション", listOf("ヘルプとフィードバック", "フィードバックを送信")),
        Language("pt", "Iniciar servidor da unidade principal", "Parar servidor da unid. princ.", "Configurações do desenvolvedor", "Sair do modo de desenvolvedor", "Mais opções", listOf("Ajuda e feedback", "Enviar feedback")),
        Language("en", "Start head unit server", "Stop head unit server", "Developer settings", "Quit developer mode", "More options", listOf("Help & feedback", "Send feedback")),
    )

    private val timing = ServerMenuRun.Timing(holdMs = 0, halfMenuMs = 300)

    private fun screen(now: Long, more: String, menu: List<String>? = null) = ServerMenuRun.Seen(
        nowMs = now, front = AndroidAuto.PACKAGE, locked = false,
        nodes = buildList {
            add(ServerMenuRun.Node(1, "Android Auto", null, clickable = false, ref = 0))
            add(ServerMenuRun.Node(1, null, more, clickable = true, ref = 1))
            menu?.forEachIndexed { i, text -> add(ServerMenuRun.Node(2, text, null, clickable = true, ref = 10 + i)) }
        },
        lastChangeMs = 0,
    )

    private fun elsewhere(now: Long) = ServerMenuRun.Seen(now, ServerMenuRun.REAU, false, emptyList())

    /** Shows [menu] until the run answers something other than waiting; that answer and the time. */
    private fun showMenu(run: ServerMenuRun, more: String, menu: List<String>, from: Long): Pair<Step, Long> {
        var now = from
        while (true) {
            now += 100
            val step = run.next(screen(now, more, menu))
            if (step != Step.Wait) return step to now
        }
    }

    /** Opens the settings and the menu: the run is left waiting for the list. */
    private fun toTheMenu(run: ServerMenuRun, more: String): Long {
        assertEquals(Step.OpenSettings, run.next(elsewhere(0)))
        val click = run.next(screen(500, more))
        assertTrue("$click", click is Step.Click && click.what == "more options")
        return 500
    }

    @Test
    fun `a menu with only the help items is developer mode off in every language`() {
        for (l in languages) {
            val run = ServerMenuRun(ServerMenuRun.Mode.START, ServerTexts.load(l.resources()), 0, timing = timing)
            val (back, at) = showMenu(run, l.more, l.help, toTheMenu(run, l.more))
            assertEquals(l.name, Step.Back, back)
            assertTrue(l.name, run.next(screen(at + 600, l.more)) is Step.Click)
            val (leave, at2) = showMenu(run, l.more, l.help, at + 600)
            assertEquals(l.name, Step.Back, leave)
            assertEquals(l.name, Step.Done(Outcome.DEVELOPER_MODE_OFF), run.next(elsewhere(at2 + 100)))
        }
    }

    @Test
    fun `the server's start item is found in every language`() {
        for (l in languages) {
            val run = ServerMenuRun(ServerMenuRun.Mode.START, ServerTexts.load(l.resources()), 0, timing = timing)
            val (tap, _) = showMenu(run, l.more, listOf(l.help[0], l.developer, l.start, l.quit), toTheMenu(run, l.more))
            assertTrue("${l.name}: $tap", tap is Step.Click && tap.what == "start")
        }
    }

    @Test
    fun `reading the menu does not depend on a table for the language`() {
        for (l in languages.filter { it.name in setOf("de", "fr", "it", "ja") }) {
            val texts = ServerTexts.load(l.resources())
            assertEquals(l.name, ServerTexts.Reading.DEVELOPER_MODE_OFF, texts.read(l.help))
            assertEquals(l.name, ServerTexts.Reading.START_SHOWN, texts.read(listOf(l.help[0], l.developer, l.start, l.quit)))
            assertEquals(l.name, ServerTexts.Reading.STOP_SHOWN, texts.read(listOf(l.help[0], l.developer, l.stop, l.quit)))
            assertEquals(l.name, ServerTexts.Reading.UNKNOWN_DEVELOPER_MENU, texts.read(listOf(l.help[0], l.developer, l.quit)))
        }
    }

    @Test
    fun `a long list of texts is not the menu without the developer mode`() {
        val german = languages.first { it.name == "de" }
        val texts = ServerTexts.load(german.resources())
        assertEquals(ServerTexts.Reading.DEVELOPER_MODE_OFF, texts.read(List(ServerTexts.MAX_PLAIN_MENU_ITEMS) { "Eintrag $it" }))
        assertEquals(ServerTexts.Reading.UNREADABLE, texts.read(List(ServerTexts.MAX_PLAIN_MENU_ITEMS + 1) { "Eintrag $it" }))
    }

    @Test
    fun `without Android Auto's words and without a table, a foreign menu is still nothing known`() {
        val table = ServerTexts.table()
        val german = languages.first { it.name == "de" }
        assertEquals(ServerTexts.Reading.UNREADABLE, table.read(german.help))
        assertEquals("no items at all", ServerTexts.Reading.UNREADABLE, ServerTexts.load(german.resources()).read(emptyList()))
        assertEquals("blank items", ServerTexts.Reading.UNREADABLE, ServerTexts.load(german.resources()).read(listOf(" ", "")))
    }
}
