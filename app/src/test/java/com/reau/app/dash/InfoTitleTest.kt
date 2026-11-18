package com.reau.app.dash

import com.reau.app.weather.RingIcon
import com.reau.app.weather.RingKind
import com.reau.app.weather.RingPage
import com.reau.app.weather.RingTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Informações no título" (P31 Título): the title of the dash's gold band turning between its base (the street, or "REau") and the info ring's
 * pages in words, in the ring's own time; the street only within 300 m of the maneuver; the danger pages ahead of the others but never ahead of
 * the street near the turn. The clock is the test's own: nothing waits.
 */
class InfoTitleTest {
    private var now = 50_000L
    private val logs = mutableListOf<String>()
    private val title = InfoTitle(nowMs = { now }, log = { logs += it })
    private var asked = 0

    private fun page(kind: RingKind, level: Int) = RingPage(kind, level, RingIcon.CLEAR, RingTone.NONE, emptyList())

    private val rain = TitleEntry(page(RingKind.RAIN_SOON, 2), "Chuva em 20 min")
    private val wind = TitleEntry(page(RingKind.WIND_ATTENTION, 2), "Vento 45 km/h")
    private val thunder = TitleEntry(page(RingKind.THUNDER, 1), "Trovoada")
    private val strongWind = TitleEntry(page(RingKind.WIND_DANGER, 1), "Vento 75 km/h")

    private fun feed(vararg entries: TitleEntry, carousel: Boolean = true, periodMs: Long = PERIOD) {
        title.source = {
            asked++
            TitleSource(entries.toList(), carousel, periodMs)
        }
        title.enabled = true
    }

    /** The title [afterMs] after the first look; the first look (at 0) is the base, as every new set of pages starts. */
    private fun at(afterMs: Long, base: String = STREET, meters: Int? = FAR, shown: Boolean = true): String {
        now = START + afterMs
        return title.page(meters, shown) ?: base
    }

    @Test
    fun `the title turns between the street and the pages, each for the ring's time`() {
        feed(rain, wind)
        assertEquals(STREET, at(0))
        assertEquals(STREET, at(PERIOD - 1))
        assertEquals("a page at the second period", "Chuva em 20 min", at(PERIOD))
        assertEquals("Chuva em 20 min", at(2 * PERIOD - 1))
        assertEquals("the street between two pages", STREET, at(2 * PERIOD))
        assertEquals("the next page", "Vento 45 km/h", at(3 * PERIOD))
        assertEquals(STREET, at(4 * PERIOD))
        assertEquals("round again to the first page", "Chuva em 20 min", at(5 * PERIOD))
    }

    @Test
    fun `the time on each is the ring's seconds, whatever the rider set`() {
        feed(rain, periodMs = 12_000)
        assertEquals(STREET, at(0))
        assertEquals(STREET, at(11_999))
        assertEquals("Chuva em 20 min", at(12_000))
        assertEquals(STREET, at(24_000))
    }

    @Test
    fun `with the carousel off the title stays on the most urgent page and the street`() {
        feed(rain, wind, carousel = false)
        assertEquals(STREET, at(0))
        assertEquals("Chuva em 20 min", at(PERIOD))
        assertEquals(STREET, at(2 * PERIOD))
        assertEquals("never the second", "Chuva em 20 min", at(3 * PERIOD))
        assertEquals("Chuva em 20 min", at(7 * PERIOD))
    }

    @Test
    fun `within 300 m of the maneuver the title is the street only, and 300 m is already far`() {
        feed(rain)
        assertEquals(STREET, at(0))
        assertEquals("a page's turn, far", "Chuva em 20 min", at(PERIOD, meters = 301))
        assertEquals("299 m: the street only", STREET, at(PERIOD, meters = 299))
        assertEquals("300 m: the turns are back", "Chuva em 20 min", at(PERIOD, meters = 300))
        assertEquals("0 m", STREET, at(PERIOD, meters = 0))
        assertEquals("a long way", "Chuva em 20 min", at(PERIOD, meters = 20_000))
        assertEquals(300, InfoTitle.NEAR_METERS)
    }

    @Test
    fun `danger pages go ahead of the other pages and alone, and the street still comes between`() {
        feed(rain, thunder, wind, strongWind)
        assertEquals(STREET, at(0))
        assertEquals("a danger page, not the first of the list", "Trovoada", at(PERIOD))
        assertEquals(STREET, at(2 * PERIOD))
        assertEquals("the other danger page", "Vento 75 km/h", at(3 * PERIOD))
        assertEquals(STREET, at(4 * PERIOD))
        assertEquals("round again", "Trovoada", at(5 * PERIOD))
        for (k in 0..9L) assertTrue("no ordinary page while there is a danger one", at(k * PERIOD) !in listOf("Chuva em 20 min", "Vento 45 km/h"))
    }

    @Test
    fun `a danger page is never ahead of the street near the maneuver`() {
        feed(thunder)
        assertEquals(STREET, at(0))
        assertEquals("Trovoada", at(PERIOD, meters = 500))
        assertEquals(STREET, at(PERIOD, meters = 299))
        assertEquals(STREET, at(3 * PERIOD, meters = 100))
        assertEquals("and back after the turn", "Trovoada", at(3 * PERIOD, meters = 800))
    }

    @Test
    fun `with no route the title turns between REau and the pages`() {
        feed(rain)
        assertEquals("REau", at(0, base = "REau", meters = null))
        assertEquals("REau", at(PERIOD - 1, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(PERIOD, base = "REau", meters = null))
        assertEquals("REau", at(2 * PERIOD, base = "REau", meters = null))
    }

    @Test
    fun `with nothing to say the title is the base, always`() {
        feed()
        for (k in 0..4L) assertEquals(STREET, at(k * PERIOD))
        title.source = null
        for (k in 0..4L) assertEquals(STREET, at(k * PERIOD))
        title.source = { error("the weather failed") }
        for (k in 0..4L) assertEquals("a failing source leaves the title alone", STREET, at(k * PERIOD))
    }

    @Test
    fun `a set of pages that changes starts over with the street`() {
        feed(rain)
        at(0)
        assertEquals("Chuva em 20 min", at(PERIOD))
        title.source = { TitleSource(listOf(rain, wind), true, PERIOD) }
        assertEquals("the new set starts with the street", STREET, at(PERIOD + 1))
        assertEquals("Chuva em 20 min", at(2 * PERIOD + 1))
    }

    @Test
    fun `the words of a page changing do not restart the turns`() {
        feed(rain)
        at(0)
        title.source = { TitleSource(listOf(TitleEntry(page(RingKind.RAIN_SOON, 2), "Chuva em 18 min")), true, PERIOD) }
        assertEquals("Chuva em 18 min", at(PERIOD))
    }

    @Test
    fun `the joystick hiding the info takes the pages away and the street stays`() {
        feed(rain)
        at(0)
        assertEquals(STREET, at(PERIOD, shown = false))
        assertEquals("Chuva em 20 min", at(PERIOD, shown = true))
    }

    @Test
    fun `a text that is empty or longer than the street field is not said`() {
        feed(TitleEntry(page(RingKind.RAIN_SOON, 2), "Chuva em 20 minutos!!"), TitleEntry(page(RingKind.UV, 3), "  "), wind)
        assertEquals(STREET, at(0))
        assertEquals("only the one that fits", "Vento 45 km/h", at(PERIOD))
        assertEquals(19, com.reau.app.dash.protocol.DashCommands.ROAD_MAX)
        // 18 and 19 characters both fit the field.
        feed(TitleEntry(page(RingKind.RAIN_SOON, 2), "123456789012345678"), TitleEntry(page(RingKind.UV, 3), "1234567890123456789"))
        assertEquals(STREET, at(0))
        assertEquals("123456789012345678", at(PERIOD))
        assertEquals("1234567890123456789", at(3 * PERIOD))
    }

    @Test
    fun `off, the title is the base untouched and the source is never asked`() {
        title.source = {
            asked++
            TitleSource(listOf(rain), true, PERIOD)
        }
        for (k in 0..4L) {
            assertEquals(STREET, at(k * PERIOD))
            assertEquals("REau", at(k * PERIOD, base = "REau", meters = null))
            assertEquals(STREET, at(k * PERIOD, meters = 10))
        }
        assertEquals(0, asked)
        assertTrue("nothing in the log", logs.isEmpty())
    }

    @Test
    fun `switched off while it shows, the base is back at once`() {
        feed(rain)
        at(0)
        assertEquals("Chuva em 20 min", at(PERIOD))
        title.enabled = false
        assertEquals(STREET, at(PERIOD))
    }

    @Test
    fun `the log says the mode switched on and off, once each, with no street`() {
        title.enabled = false
        assertTrue("no change, no line", logs.isEmpty())
        feed(rain)
        feed(rain)
        assertEquals(1, logs.size)
        assertTrue(logs[0], logs[0].startsWith("info title on"))
        title.enabled = false
        title.enabled = false
        assertEquals(2, logs.size)
        assertTrue(logs[1], logs[1].startsWith("info title off"))
    }

    @Test
    fun `the log says when the title goes to the street only near the maneuver and when it turns again, never the street`() {
        feed(rain)
        logs.clear()
        at(PERIOD, base = "Rua Secreta", meters = 900)
        at(PERIOD, base = "Rua Secreta", meters = 450)
        assertTrue("far, no line", logs.isEmpty())
        at(PERIOD, base = "Rua Secreta", meters = 299)
        at(PERIOD, base = "Rua Secreta", meters = 120)
        at(PERIOD, base = "Rua Secreta", meters = 20)
        assertEquals("one line going near", 1, logs.size)
        assertTrue(logs[0], "street only" in logs[0])
        at(PERIOD, base = "Rua Secreta", meters = 300)
        at(PERIOD, base = "Rua Secreta", meters = 1_000)
        assertEquals("one line going far again", 2, logs.size)
        assertTrue(logs[1], "turn again" in logs[1])
        assertFalse(logs.toString(), logs.any { "Secreta" in it || "Rua" in it })
    }

    @Test
    fun `off, nearness is not logged`() {
        title.source = { TitleSource(listOf(rain), true, PERIOD) }
        at(0, meters = 100)
        at(0, meters = 1_000)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `without a route no nearness is logged`() {
        feed(rain)
        logs.clear()
        for (k in 0..4L) at(k * PERIOD, base = "REau", meters = null)
        assertTrue(logs.toString(), logs.isEmpty())
    }

    private companion object {
        const val STREET = "Rua das Flores"
        const val PERIOD = 7_000L
        const val START = 50_000L
        const val FAR = 5_000
    }
}
