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
 * pages in words: with no route in the ring's own time; guiding, 20 s of street and 5 s of a page; the street only within 500 m of the maneuver,
 * and 15 s more of street after passing one; the danger pages ahead of the others but never ahead of the street near the turn. The clock is the
 * test's own: nothing waits.
 */
class InfoTitleTest {
    private var now = 50_000L
    private val logs = mutableListOf<String>()
    private var title = InfoTitle(nowMs = { now }, log = { logs += it })
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

    /** A new title with the clock back at the start, for a test that must not carry what an earlier look left (a near maneuver, a hold). */
    private fun fresh() {
        now = START
        title = InfoTitle(nowMs = { now }, log = { logs += it })
    }

    /** The title [afterMs] after the first look; the first look (at 0) is the base, as every new set of pages starts. */
    private fun at(afterMs: Long, base: String = STREET, meters: Int? = FAR, shown: Boolean = true): String {
        now = START + afterMs
        return title.page(meters, shown) ?: base
    }

    @Test
    fun `guiding, the street stays 20 s and a page 5 s, and the pages go round`() {
        feed(rain, wind)
        assertEquals(STREET, at(0))
        assertEquals(STREET, at(STREET_MS - 1))
        assertEquals("a page after 20 s of street", "Chuva em 20 min", at(STREET_MS))
        assertEquals("Chuva em 20 min", at(ROUND - 1))
        assertEquals("the street again after 5 s of page", STREET, at(ROUND))
        assertEquals(STREET, at(ROUND + STREET_MS - 1))
        assertEquals("the next page", "Vento 45 km/h", at(ROUND + STREET_MS))
        assertEquals(STREET, at(2 * ROUND))
        assertEquals("round again to the first page", "Chuva em 20 min", at(2 * ROUND + STREET_MS))
        assertEquals(20_000L, InfoTitle.ROUTE_STREET_MS)
        assertEquals(5_000L, InfoTitle.ROUTE_PAGE_MS)
    }

    @Test
    fun `guiding, the 20 s and 5 s are the same whatever the ring's time the rider set`() {
        for (period in listOf(3_000L, 7_000L, 60_000L)) {
            fresh()
            feed(rain, periodMs = period)
            assertEquals(STREET, at(0))
            assertEquals("period $period", STREET, at(STREET_MS - 1))
            assertEquals("period $period", "Chuva em 20 min", at(STREET_MS))
            assertEquals("period $period", "Chuva em 20 min", at(ROUND - 1))
            assertEquals("period $period", STREET, at(ROUND))
        }
    }

    @Test
    fun `with no route the time on each is the ring's seconds, whatever the rider set`() {
        feed(rain, wind)
        assertEquals("REau", at(0, base = "REau", meters = null))
        assertEquals("REau", at(PERIOD - 1, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(PERIOD, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(2 * PERIOD - 1, base = "REau", meters = null))
        assertEquals("REau between two pages", "REau", at(2 * PERIOD, base = "REau", meters = null))
        assertEquals("the next page", "Vento 45 km/h", at(3 * PERIOD, base = "REau", meters = null))
        assertEquals("REau", at(4 * PERIOD, base = "REau", meters = null))
        assertEquals("round again to the first page", "Chuva em 20 min", at(5 * PERIOD, base = "REau", meters = null))
        fresh()
        feed(rain, periodMs = 12_000)
        assertEquals("REau", at(0, base = "REau", meters = null))
        assertEquals("REau", at(11_999, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(12_000, base = "REau", meters = null))
        assertEquals("REau", at(24_000, base = "REau", meters = null))
    }

    @Test
    fun `with the carousel off the title stays on the most urgent page and the street, 20 s and 5 s guiding`() {
        feed(rain, wind, carousel = false)
        assertEquals(STREET, at(0))
        assertEquals("Chuva em 20 min", at(STREET_MS))
        assertEquals(STREET, at(ROUND))
        assertEquals(STREET, at(ROUND + STREET_MS - 1))
        assertEquals("never the second", "Chuva em 20 min", at(ROUND + STREET_MS))
        assertEquals("Chuva em 20 min", at(7 * ROUND + STREET_MS))
        assertEquals(STREET, at(7 * ROUND + STREET_MS + PAGE_MS))
    }

    @Test
    fun `within 500 m of the maneuver the title is the street only, and 500 m is already far`() {
        feed(rain)
        assertEquals(STREET, at(0))
        assertEquals("a page's turn, far", "Chuva em 20 min", at(STREET_MS, meters = 501))
        assertEquals("500 m: the turns are as before", "Chuva em 20 min", at(STREET_MS, meters = 500))
        assertEquals("499 m: the street only", STREET, at(STREET_MS, meters = 499))
        fresh()
        feed(rain)
        assertEquals(STREET, at(0))
        assertEquals("500 m from the start of the turn", "Chuva em 20 min", at(STREET_MS, meters = 500))
        fresh()
        feed(rain)
        assertEquals(STREET, at(0))
        assertEquals("499 m from the start of the turn", STREET, at(STREET_MS, meters = 499))
        fresh()
        feed(rain)
        assertEquals(STREET, at(0))
        assertEquals("0 m", STREET, at(STREET_MS, meters = 0))
        fresh()
        feed(rain)
        assertEquals(STREET, at(0))
        assertEquals("a long way", "Chuva em 20 min", at(STREET_MS, meters = 20_000))
        assertEquals(500, InfoTitle.NEAR_METERS)
    }

    @Test
    fun `passing a maneuver the street holds 15 s, then the turns start over from the street`() {
        feed(rain)
        assertEquals(STREET, at(0, meters = 900))
        assertEquals("a page, far", "Chuva em 20 min", at(STREET_MS, meters = 900))
        assertEquals("near, the street", STREET, at(STREET_MS + 1_000, meters = 100))
        // The maneuver is passed at 30 s: far again, but the page that was due is held back.
        val passed = 30_000L
        assertEquals(STREET, at(passed, meters = 900))
        assertEquals("15 s of street, and a little less", STREET, at(passed + 14_999, meters = 900))
        assertEquals("the hold is over, the street starts again for 20 s", STREET, at(passed + 15_000, meters = 900))
        assertEquals(STREET, at(passed + 15_000 + STREET_MS - 1, meters = 900))
        assertEquals("then the page, 5 s", "Chuva em 20 min", at(passed + 15_000 + STREET_MS, meters = 900))
        assertEquals("Chuva em 20 min", at(passed + 15_000 + ROUND - 1, meters = 900))
        assertEquals(STREET, at(passed + 15_000 + ROUND, meters = 900))
        assertEquals(15_000L, InfoTitle.AFTER_MANEUVER_HOLD_MS)
    }

    @Test
    fun `a page of the old turns is not back when the maneuver is passed, even if it was its time`() {
        feed(rain)
        assertEquals(STREET, at(0, meters = 900))
        // At 20 s a page is due; the rider is near, then far again at 21 s: the street holds, no page for 15 s and then 20 s of street.
        assertEquals(STREET, at(STREET_MS, meters = 100))
        assertEquals(STREET, at(STREET_MS + 1_000, meters = 900))
        assertEquals(STREET, at(STREET_MS + 1_000 + 34_999, meters = 900))
        assertEquals("Chuva em 20 min", at(STREET_MS + 1_000 + 35_000, meters = 900))
    }

    @Test
    fun `a set of pages that changes while the street holds does not cut the hold`() {
        feed(rain)
        at(0, meters = 900)
        at(STREET_MS, meters = 100)
        at(STREET_MS + 1_000, meters = 900)
        title.source = { TitleSource(listOf(rain, wind), true, PERIOD) }
        assertEquals(STREET, at(STREET_MS + 5_000, meters = 900))
        assertEquals("still the hold's street", STREET, at(46_000, meters = 900))
        assertEquals(STREET, at(STREET_MS + 1_000 + 34_999, meters = 900))
        assertEquals("Chuva em 20 min", at(STREET_MS + 1_000 + 35_000, meters = 900))
    }

    @Test
    fun `a route that ends near the maneuver leaves no hold, REau turns in the ring's time at once`() {
        feed(rain)
        assertEquals("REau", at(0, base = "REau", meters = null))
        assertEquals("near the maneuver", STREET, at(PERIOD, meters = 100))
        // The route is gone while near: the pages are back as with no route, in the ring's time, with no 15 s of street.
        assertEquals("REau", at(PERIOD + 1, base = "REau", meters = null))
        assertEquals("REau", at(2 * PERIOD, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(2 * PERIOD + 1, base = "REau", meters = null))
    }

    @Test
    fun `a route beginning or ending starts the turns over from the base`() {
        feed(rain)
        assertEquals("REau", at(0, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(PERIOD, base = "REau", meters = null))
        // A route comes: the street starts its 20 s, not the page of the ring's time that was running.
        assertEquals(STREET, at(PERIOD + 1, meters = 900))
        assertEquals(STREET, at(PERIOD + STREET_MS, meters = 900))
        assertEquals("Chuva em 20 min", at(PERIOD + 1 + STREET_MS, meters = 900))
        // The route ends: REau starts its own turn.
        assertEquals("REau", at(PERIOD + 1 + STREET_MS + 1, base = "REau", meters = null))
        assertEquals("REau", at(PERIOD + 1 + STREET_MS + PERIOD, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(PERIOD + 1 + STREET_MS + 1 + PERIOD, base = "REau", meters = null))
    }

    @Test
    fun `danger pages go ahead of the other pages and alone, and the street still comes between`() {
        feed(rain, thunder, wind, strongWind)
        assertEquals(STREET, at(0))
        assertEquals("a danger page, not the first of the list", "Trovoada", at(STREET_MS))
        assertEquals(STREET, at(ROUND))
        assertEquals("the other danger page", "Vento 75 km/h", at(ROUND + STREET_MS))
        assertEquals(STREET, at(2 * ROUND))
        assertEquals("round again", "Trovoada", at(2 * ROUND + STREET_MS))
        for (k in 0..9L) assertTrue("no ordinary page while there is a danger one", at(k * PAGE_MS) !in listOf("Chuva em 20 min", "Vento 45 km/h"))
    }

    @Test
    fun `a danger page is never ahead of the street near the maneuver`() {
        feed(thunder)
        assertEquals(STREET, at(0))
        assertEquals("Trovoada", at(STREET_MS, meters = 800))
        assertEquals(STREET, at(STREET_MS, meters = 499))
        assertEquals(STREET, at(3 * ROUND + STREET_MS, meters = 100))
        assertEquals("the street holds after the turn", STREET, at(3 * ROUND + STREET_MS + 1, meters = 800))
        assertEquals("and the page is back later", "Trovoada", at(3 * ROUND + STREET_MS + 1 + 35_000, meters = 800))
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
        assertEquals("Chuva em 20 min", at(STREET_MS))
        title.source = { TitleSource(listOf(rain, wind), true, PERIOD) }
        assertEquals("the new set starts with the street", STREET, at(STREET_MS + 1))
        assertEquals(STREET, at(2 * STREET_MS))
        assertEquals("Chuva em 20 min", at(2 * STREET_MS + 1))
    }

    @Test
    fun `the words of a page changing do not restart the turns`() {
        feed(rain)
        at(0)
        title.source = { TitleSource(listOf(TitleEntry(page(RingKind.RAIN_SOON, 2), "Chuva em 18 min")), true, PERIOD) }
        assertEquals("Chuva em 18 min", at(STREET_MS))
    }

    @Test
    fun `the joystick hiding the info takes the pages away and the street stays`() {
        feed(rain)
        at(0)
        assertEquals(STREET, at(STREET_MS, shown = false))
        assertEquals("Chuva em 20 min", at(STREET_MS, shown = true))
    }

    @Test
    fun `a text that is empty or longer than the street field is not said`() {
        feed(TitleEntry(page(RingKind.RAIN_SOON, 2), "Chuva em 20 minutos!!"), TitleEntry(page(RingKind.UV, 3), "  "), wind)
        assertEquals(STREET, at(0))
        assertEquals("only the one that fits", "Vento 45 km/h", at(STREET_MS))
        assertEquals(19, com.reau.app.dash.protocol.DashCommands.ROAD_MAX)
        // 18 and 19 characters both fit the field.
        feed(TitleEntry(page(RingKind.RAIN_SOON, 2), "123456789012345678"), TitleEntry(page(RingKind.UV, 3), "1234567890123456789"))
        assertEquals(STREET, at(0))
        assertEquals("123456789012345678", at(STREET_MS))
        assertEquals("1234567890123456789", at(ROUND + STREET_MS))
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
        assertEquals("Chuva em 20 min", at(STREET_MS))
        title.enabled = false
        assertEquals(STREET, at(STREET_MS))
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
        at(STREET_MS, base = "Rua Secreta", meters = 900)
        at(STREET_MS, base = "Rua Secreta", meters = 600)
        at(STREET_MS, base = "Rua Secreta", meters = 500)
        assertTrue("far, no line", logs.isEmpty())
        at(STREET_MS, base = "Rua Secreta", meters = 499)
        at(STREET_MS, base = "Rua Secreta", meters = 300)
        at(STREET_MS, base = "Rua Secreta", meters = 20)
        assertEquals("one line going near", 1, logs.size)
        assertTrue(logs[0], "street only" in logs[0])
        at(STREET_MS, base = "Rua Secreta", meters = 500)
        at(STREET_MS, base = "Rua Secreta", meters = 1_000)
        assertEquals("one line going far again", 2, logs.size)
        assertTrue(logs[1], "turn again" in logs[1])
        assertTrue("it says the street holds", "holds for 15 s" in logs[1])
        assertFalse(logs.toString(), logs.any { "Secreta" in it || "Rua" in it })
    }

    @Test
    fun `the route ending near the maneuver is logged as turning again, with no hold said`() {
        feed(rain)
        logs.clear()
        at(PERIOD, meters = 100)
        at(PERIOD, base = "REau", meters = null)
        assertEquals(2, logs.size)
        assertTrue(logs[1], "turn again" in logs[1])
        assertFalse(logs[1], "holds" in logs[1])
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
        const val STREET_MS = 20_000L
        const val PAGE_MS = 5_000L
        const val ROUND = STREET_MS + PAGE_MS
        const val START = 50_000L
        const val FAR = 5_000
    }
}
