package com.reau.app.data

import com.reau.app.data.KeepAliveChecklist.Answer
import com.reau.app.data.KeepAliveChecklist.Item
import com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs
import com.reau.app.ui.KeepAliveGuide
import com.reau.app.ui.KeepAliveGuide.Brand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the rider says he did (26/11/2026, A3): the ticks of the page, kept on the phone, and the line `keepalive: rider says ...` of the ride log. It is his word
 * and not a reading, so an item he never touched is a question mark and not a "no".
 */
class KeepAliveChecklistTest {
    private val prefs = FakePrefs()
    private fun book() = KeepAliveChecklist.Book(prefs)

    // -- the line -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the line is the one of the plan, every item in the same order, ticked is yes, unticked is no, untouched is a question mark`() {
        val answers = mapOf(Item.AUTOSTART to Answer.YES, Item.BACKGROUND to Answer.NO, Item.LOCK to Answer.YES)
        assertEquals("keepalive: rider says autostart=yes background=no lock=yes battery=?", KeepAliveChecklist.line(answers))
    }

    @Test
    fun `with nothing answered the line says so with question marks`() {
        assertEquals("keepalive: rider says autostart=? background=? lock=? battery=?", KeepAliveChecklist.line(emptyMap()))
    }

    @Test
    fun `the line has the same words for the same answers whatever the order they were given in`() {
        val one = KeepAliveChecklist.line(mapOf(Item.BATTERY to Answer.YES, Item.AUTOSTART to Answer.NO))
        val two = KeepAliveChecklist.line(linkedMapOf(Item.AUTOSTART to Answer.NO, Item.BATTERY to Answer.YES))
        assertEquals(one, two)
        assertEquals("keepalive: rider says autostart=no background=? lock=? battery=yes", one)
    }

    @Test
    fun `the words of the line are the keys the reader looks for`() {
        assertEquals(listOf("autostart", "background", "lock", "battery"), Item.entries.map { it.key })
        assertEquals(listOf("yes", "no", "?"), Answer.entries.map { it.word })
    }

    // -- what is kept -------------------------------------------------------------------------------------------------------------

    @Test
    fun `nothing touched is unknown for every item`() {
        assertEquals(Item.entries.associateWith { Answer.UNKNOWN }, book().answers())
        assertTrue(prefs.map.isEmpty())
    }

    @Test
    fun `a tick is yes, a tick taken back is no, and the others stay unknown`() {
        book().set(Item.AUTOSTART, true)
        book().set(Item.LOCK, true)
        book().set(Item.LOCK, false)
        val answers = KeepAliveChecklist.Book(prefs).answers()
        assertEquals(Answer.YES, answers[Item.AUTOSTART])
        assertEquals("unticked after ticked is a no, which he said", Answer.NO, answers[Item.LOCK])
        assertEquals(Answer.UNKNOWN, answers[Item.BACKGROUND])
        assertEquals(Answer.UNKNOWN, answers[Item.BATTERY])
        assertEquals("keepalive: rider says autostart=yes background=? lock=no battery=?", KeepAliveChecklist.line(answers))
    }

    // -- what the family offers -------------------------------------------------------------------------------------------------

    @Test
    fun `Xiaomi offers autostart, battery and the padlock, and realme the autostart, the background activity and the padlock`() {
        assertEquals(listOf(Item.AUTOSTART, Item.BATTERY, Item.LOCK), KeepAliveGuide.items(Brand.XIAOMI))
        assertEquals(listOf(Item.AUTOSTART, Item.BACKGROUND, Item.LOCK), KeepAliveGuide.items(Brand.OPPO))
        assertEquals("another maker has no list", emptyList<Item>(), KeepAliveGuide.items(Brand.OTHER))
    }

    @Test
    fun `every item has words of its own, and the ones a family does not offer stay question marks in its line`() {
        assertEquals(Item.entries.size, Item.entries.map { KeepAliveGuide.label(it) }.toSet().size)
        for (brand in listOf(Brand.XIAOMI, Brand.OPPO)) {
            for (item in KeepAliveGuide.items(brand)) book().set(item, true)
            val line = KeepAliveChecklist.line(book().answers())
            val notOffered = Item.entries - KeepAliveGuide.items(brand).toSet()
            for (item in notOffered) assertTrue("$brand: ${item.key} in $line", "${item.key}=?" in line)
            for (item in KeepAliveGuide.items(brand)) assertTrue("$brand: ${item.key} in $line", "${item.key}=yes" in line)
            prefs.map.clear()
        }
    }

    @Test
    fun `the words of the ticks are the ones the plan names, in Portuguese`() {
        val pt = java.io.File("src/main/res/values-pt/strings.xml").readText()
        fun text(name: String) = Regex("""<string name="$name">(.*?)</string>""").find(pt)!!.groupValues[1]
        assertEquals("Inicialização automática ligada", text("keepalive_item_autostart"))
        assertEquals("Atividade em segundo plano liberada", text("keepalive_item_background"))
        assertTrue(text("keepalive_item_lock").contains("Cadeado"))
        assertTrue(text("keepalive_item_battery").contains("Sem restrições"))
        assertFalse(text("keepalive_check_hint").isBlank())
    }
}
