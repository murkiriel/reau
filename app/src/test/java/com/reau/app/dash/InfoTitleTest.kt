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
 * pages in words: with no route in the ring's own time; guiding, 20 s of street and 7 s of a page; the street only within 500 m of the maneuver,
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
    fun `guiding, the street stays 20 s and a page 7 s, and the pages go round`() {
        feed(rain, wind)
        assertEquals(STREET, at(0))
        assertEquals(STREET, at(STREET_MS - 1))
        assertEquals("a page after 20 s of street", "Chuva em 20 min", at(STREET_MS))
        assertEquals("Chuva em 20 min", at(ROUND - 1))
        assertEquals("the street again after 7 s of page", STREET, at(ROUND))
        assertEquals(STREET, at(ROUND + STREET_MS - 1))
        assertEquals("the next page", "Vento 45 km/h", at(ROUND + STREET_MS))
        assertEquals(STREET, at(2 * ROUND))
        assertEquals("round again to the first page", "Chuva em 20 min", at(2 * ROUND + STREET_MS))
        assertEquals(20_000L, InfoTitle.ROUTE_STREET_MS)
        assertEquals(7_000L, InfoTitle.ROUTE_PAGE_MS)
    }

    @Test
    fun `guiding, the 20 s and 7 s are the same whatever the ring's time the rider set`() {
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
        assertEquals("the page stays the ring's 12 s, not the 7 s of the least", "Chuva em 20 min", at(23_999, base = "REau", meters = null))
        assertEquals("REau", at(24_000, base = "REau", meters = null))
        assertEquals("REau stays the ring's 12 s too", "REau", at(35_999, base = "REau", meters = null))
    }

    @Test
    fun `with the carousel off the title stays on the most urgent page and the street, 20 s and 7 s guiding`() {
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
        assertEquals("then the page, 7 s", "Chuva em 20 min", at(passed + 15_000 + STREET_MS, meters = 900))
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

    /** The page of the [n]th round (0 the first) while guiding: asked in the middle of its 5 s of information. */
    private fun guidingTurn(n: Long, meters: Int? = FAR) = at(n * ROUND + STREET_MS + 1, meters = meters)

    /** The page of the [n]th round with no route: asked in the middle of the ring's time of information. */
    private fun freeTurn(n: Long) = at(n * 2 * PERIOD + PERIOD + 1, base = "REau", meters = null)

    @Test
    fun `danger pages alternate five turns in all and then one of the others, and the street still comes between`() {
        feed(rain, thunder, wind, strongWind)
        assertEquals(STREET, at(0))
        assertEquals("a danger page, not the first of the list", "Trovoada", at(STREET_MS))
        assertEquals(STREET, at(ROUND))
        assertEquals("the other danger page", "Vento 75 km/h", at(ROUND + STREET_MS))
        assertEquals(STREET, at(2 * ROUND))
        assertEquals("round again", "Trovoada", at(2 * ROUND + STREET_MS))
        assertEquals("Vento 75 km/h", guidingTurn(3))
        assertEquals("Trovoada", guidingTurn(4))
        assertEquals("the sixth turn is the first of the others", "Chuva em 20 min", guidingTurn(5))
        assertEquals("the alternation goes on: the second danger starts the next five", "Vento 75 km/h", guidingTurn(6))
        assertEquals("Trovoada", guidingTurn(7))
        assertEquals("Vento 75 km/h", guidingTurn(8))
        assertEquals("Trovoada", guidingTurn(9))
        assertEquals("Vento 75 km/h", guidingTurn(10))
        assertEquals("and the next of the others", "Vento 45 km/h", guidingTurn(11))
        assertEquals("Trovoada", guidingTurn(12))
    }

    @Test
    fun `a turn of information is a turn of the cycle, with the street between them, with a route`() {
        feed(thunder, rain, wind)
        for (n in 0L..4L) assertEquals("turn $n", "Trovoada", guidingTurn(n))
        assertEquals("Chuva em 20 min", guidingTurn(5))
        for (n in 6L..10L) assertEquals("turn $n", "Trovoada", guidingTurn(n))
        assertEquals("Vento 45 km/h", guidingTurn(11))
        for (n in 12L..16L) assertEquals("turn $n", "Trovoada", guidingTurn(n))
        assertEquals("round again to the first of the others", "Chuva em 20 min", guidingTurn(17))
    }

    @Test
    fun `a turn of information is a turn of the cycle with no route too, in the ring's time`() {
        feed(thunder, strongWind, rain)
        val t = "Trovoada"
        val w = "Vento 75 km/h"
        assertEquals(listOf(t, w, t, w, t, "Chuva em 20 min", w, t), (0L..7L).map { freeTurn(it) })
    }

    @Test
    fun `a round that was never shown does not use up a turn of the cycle`() {
        feed(thunder, rain)
        assertEquals("Trovoada", guidingTurn(0))
        // Rounds 1 and 2 go by with the info hidden by the joystick: no turn is said, none is used.
        assertEquals(STREET, at(ROUND + STREET_MS + 1, shown = false))
        assertEquals(STREET, at(2 * ROUND + STREET_MS + 1, shown = false))
        // The info is back in round 3: the second turn of the cycle, not the fourth.
        for (n in 3L..6L) assertEquals("round $n", "Trovoada", guidingTurn(n))
        assertEquals("the sixth turn said is the normal one", "Chuva em 20 min", guidingTurn(7))
    }

    @Test
    fun `passing a maneuver starts the street over but not the cycle`() {
        feed(thunder, rain)
        assertEquals("Trovoada", guidingTurn(0))
        assertEquals("Trovoada", guidingTurn(1))
        // Near the maneuver, then past it: the street holds 15 s and the clock starts again.
        at(2 * ROUND, meters = 100)
        val past = 2 * ROUND + 1_000
        assertEquals(STREET, at(past, meters = 800))
        assertEquals("still the street while the hold lasts", STREET, at(past + 14_000, meters = 800))
        val firstPage = past + InfoTitle.AFTER_MANEUVER_HOLD_MS + STREET_MS
        assertEquals("the third turn of the same cycle", "Trovoada", at(firstPage, meters = 800))
        assertEquals(STREET, at(firstPage + PAGE_MS, meters = 800))
        assertEquals("Trovoada", at(firstPage + ROUND, meters = 800))
        assertEquals("Trovoada", at(firstPage + 2 * ROUND, meters = 800))
        assertEquals("the sixth turn said is the normal one", "Chuva em 20 min", at(firstPage + 3 * ROUND, meters = 800))
    }

    @Test
    fun `a new danger shows after the street and the count of five starts again`() {
        feed(thunder, rain)
        for (n in 0L..2L) assertEquals("Trovoada", guidingTurn(n))
        title.source = { TitleSource(listOf(thunder, strongWind, rain), true, PERIOD) }
        assertEquals("the new set starts with the street", STREET, at(3 * ROUND + 1))
        val t0 = 3 * ROUND + 1
        assertEquals("Trovoada", at(t0 + STREET_MS))
        assertEquals("Vento 75 km/h", at(t0 + ROUND + STREET_MS))
        assertEquals("Trovoada", at(t0 + 2 * ROUND + STREET_MS))
        assertEquals("Vento 75 km/h", at(t0 + 3 * ROUND + STREET_MS))
        assertEquals("Trovoada", at(t0 + 4 * ROUND + STREET_MS))
        assertEquals("the sixth turn since the new danger is the normal one", "Chuva em 20 min", at(t0 + 5 * ROUND + STREET_MS))
    }

    @Test
    fun `with the carousel off the danger is five turns and the most urgent of the others one, with a route or not`() {
        feed(thunder, strongWind, rain, wind, carousel = false)
        for (n in 0L..4L) assertEquals("turn $n", "Trovoada", guidingTurn(n))
        assertEquals("Chuva em 20 min", guidingTurn(5))
        assertEquals("Trovoada", guidingTurn(6))
        fresh()
        feed(thunder, strongWind, rain, wind, carousel = false)
        assertEquals(List(5) { "Trovoada" } + "Chuva em 20 min" + "Trovoada", (0L..6L).map { freeTurn(it) })
    }

    @Test
    fun `with no danger the pages go one after the other, as they always did`() {
        feed(rain, wind)
        assertEquals(listOf("Chuva em 20 min", "Vento 45 km/h", "Chuva em 20 min", "Vento 45 km/h"), (0L..3L).map { guidingTurn(it) })
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

    @Test
    fun `guiding, a page stays 7 s and not a millisecond less, 20 s of street then 7 s of page, in numbers`() {
        feed(rain)
        assertEquals(STREET, at(0))
        assertEquals(STREET, at(19_999))
        assertEquals("Chuva em 20 min", at(20_000))
        assertEquals("Chuva em 20 min", at(26_999))
        assertEquals("the 7 s are over", STREET, at(27_000))
        assertEquals(20_000L, InfoTitle.ROUTE_STREET_MS)
        assertEquals(7_000L, InfoTitle.ROUTE_PAGE_MS)
        assertEquals(com.reau.app.weather.RingOptions.MIN_SECONDS * 1_000L, InfoTitle.ROUTE_PAGE_MS)
    }

    @Test
    fun `the 500 m and the 15 s after the maneuver go on as they were with the 7 s page`() {
        feed(rain)
        assertEquals(STREET, at(0))
        assertEquals("500 m: the page's turn", "Chuva em 20 min", at(STREET_MS, meters = 500))
        assertEquals("499 m: the street only, even inside the page's 7 s", STREET, at(STREET_MS + 3_000, meters = 499))
        // Far again at 24 s: the street holds 15 s, then 20 s of street, then the 7 s of page.
        assertEquals(STREET, at(STREET_MS + 4_000, meters = 900))
        assertEquals(STREET, at(STREET_MS + 4_000 + 14_999, meters = 900))
        assertEquals(STREET, at(STREET_MS + 4_000 + 15_000 + STREET_MS - 1, meters = 900))
        assertEquals("Chuva em 20 min", at(STREET_MS + 4_000 + 15_000 + STREET_MS, meters = 900))
        assertEquals("Chuva em 20 min", at(STREET_MS + 4_000 + 15_000 + STREET_MS + 6_999, meters = 900))
        assertEquals(STREET, at(STREET_MS + 4_000 + 15_000 + STREET_MS + 7_000, meters = 900))
        assertEquals(15_000L, InfoTitle.AFTER_MANEUVER_HOLD_MS)
    }

    @Test
    fun `with no route a source that asks for less than 7 s is held at 7 s, whoever built it`() {
        feed(rain, wind, periodMs = 3_000)
        assertEquals("REau", at(0, base = "REau", meters = null))
        assertEquals("REau", at(6_999, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(7_000, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(13_999, base = "REau", meters = null))
        assertEquals("REau", at(14_000, base = "REau", meters = null))
        fresh()
        feed(rain, periodMs = 7_000)
        assertEquals("Chuva em 20 min", at(7_000, base = "REau", meters = null))
        assertEquals("REau", at(14_000, base = "REau", meters = null))
    }

    /** Every run of a page in the title, as its words and how long it lasted (ms), watched every 100 ms for [forMs]: [meters] null with no route. */
    private fun pageRuns(forMs: Long, meters: Int?): List<Pair<String, Long>> {
        val runs = mutableListOf<Pair<String, Long>>()
        var current: String? = null
        var since = 0L
        for (t in 0L..forMs step 100L) {
            now = START + t
            val said = title.page(meters, true)
            if (said != current) {
                current?.let { runs += it to (t - since) }
                current = said
                since = t
            }
        }
        return runs
    }

    @Test
    fun `the 5 + 1 rule, with a route or without, never stays on a page under 7 s, danger or normal`() {
        for (meters in listOf(FAR, null)) {
            fresh()
            feed(thunder, strongWind, rain, wind)
            val pages = pageRuns(10 * 60_000L, meters)
            assertTrue("route=$meters: ${pages.size} runs", pages.size >= 20)
            for ((text, ms) in pages) assertTrue("route=$meters: $text for $ms ms", ms >= 7_000L)
            // The rule itself goes on: the danger pages alternate five times, the sixth turn is the next of the others, and the alternation goes on.
            val danger = "Trovoada"
            val gust = "Vento 75 km/h"
            assertEquals(
                "route=$meters",
                listOf(danger, gust, danger, gust, danger, "Chuva em 20 min", gust, danger, gust, danger, gust, "Vento 45 km/h"),
                pages.map { it.first }.take(12),
            )
        }
    }

    @Test
    fun `the turn of the cycle is the round's, not each look's, looked at again and again inside a page it stays the page`() {
        feed(thunder, rain)
        now = START
        title.page(FAR, true)
        // A page's window, looked at every 250 ms (several times a second, as the card and the other senders do): one page, the first turn.
        for (t in STREET_MS until STREET_MS + PAGE_MS step 250L) {
            now = START + t
            assertEquals("t=$t", "Trovoada", title.page(FAR, true))
        }
        now = START + ROUND
        assertEquals(STREET, title.page(FAR, true) ?: STREET)
        // The next page window: the second turn of the cycle (a danger again), and the five turns of danger, the sixth the normal one.
        val said = (1..6).map { k ->
            now = START + k * ROUND + STREET_MS + 1_000
            val first = title.page(FAR, true)
            now += 500
            assertEquals("the same page when looked at again", first, title.page(FAR, true))
            first
        }
        assertEquals(listOf("Trovoada", "Trovoada", "Trovoada", "Trovoada", "Chuva em 20 min", "Trovoada"), said)
    }

    // The echo of a joystick tap holds the turns' clock: [echo] is a tap [atMs] after the first look that lasts [forMs] (the title is the echo, and says so with hold()); the title is then
    // asked again at its end. The page that was showing stays its whole time over the echo, with a route and without one.

    /** The title at [atMs] after the first look and every look until [forMs] later, as the card does second by second: the echo is the title, so only [InfoTitle.hold] is said. */
    private fun echo(atMs: Long, forMs: Long = 1_500L) {
        var t = atMs
        while (t < atMs + forMs) {
            now = START + t
            title.hold()
            t += 500
        }
    }

    @Test
    fun `with no route an echo in the middle of a page of 7 s leaves the page 7 s on the whole`() {
        feed(rain)
        assertEquals("REau", at(0, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(7_000, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(10_000, base = "REau", meters = null))
        echo(10_000)
        // 3 s of the page were seen; the echo's 1.5 s do not count, so 4 s more are left: until 15.5 s and not 14 s.
        assertEquals("the page is back", "Chuva em 20 min", at(11_500, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(15_499, base = "REau", meters = null))
        assertEquals("REau", at(15_500, base = "REau", meters = null))
        assertEquals("and the next round is as long as always: REau 7 s", "REau", at(22_499, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(22_500, base = "REau", meters = null))
    }

    @Test
    fun `with no route an echo over REau's turn lengthens that turn by the echo`() {
        feed(rain)
        assertEquals("REau", at(0, base = "REau", meters = null))
        echo(3_000)
        assertEquals("REau", at(4_500, base = "REau", meters = null))
        assertEquals("REau", at(8_499, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(8_500, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(15_499, base = "REau", meters = null))
        assertEquals("REau", at(15_500, base = "REau", meters = null))
    }

    @Test
    fun `an echo that is looked at late holds the clock until the title is asked again, as the dash saw the echo till then`() {
        feed(rain)
        at(0, base = "REau", meters = null)
        assertEquals("Chuva em 20 min", at(8_000, base = "REau", meters = null))
        now = START + 9_000
        title.hold()
        now = START + 10_000
        title.hold()
        assertEquals("2 s of the page seen (7 to 9 s), 2.5 s under the echo (the first look after it is at 11.5 s)", "Chuva em 20 min", at(11_500, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(16_499, base = "REau", meters = null))
        assertEquals("REau", at(16_500, base = "REau", meters = null))
    }

    @Test
    fun `guiding, an echo in the street does not eat the 20 s, and one in the page does not eat the 7 s`() {
        feed(rain)
        assertEquals(STREET, at(0))
        echo(10_000)
        assertEquals(STREET, at(11_500))
        assertEquals("the street's 20 s are 21.5 s on the clock", STREET, at(STREET_MS + 1_499))
        assertEquals("Chuva em 20 min", at(STREET_MS + 1_500))
        echo(STREET_MS + 4_000)
        assertEquals("Chuva em 20 min", at(STREET_MS + 5_500))
        assertEquals("the page's 7 s with its 1.5 s of echo", "Chuva em 20 min", at(STREET_MS + 1_500 + PAGE_MS + 1_499))
        assertEquals(STREET, at(STREET_MS + 1_500 + PAGE_MS + 1_500))
    }

    @Test
    fun `an echo that covers a whole page's turn does not use up a turn of the cycle`() {
        feed(thunder, rain)
        assertEquals("Trovoada", guidingTurn(0))
        val start = ROUND + STREET_MS - 500
        echo(start, forMs = 12_000)
        assertEquals("the look after the echo is the street: the page's turn is still to come", STREET, at(start + 12_000))
        // The echo that long moved the clock by 12 s: the second turn is as if nothing had happened, and so on to the sixth, which is the normal one.
        val moved = 12_000L
        for (n in 1L..4L) assertEquals("turn $n", "Trovoada", at(n * ROUND + STREET_MS + 1 + moved))
        assertEquals("the sixth turn said is the normal one", "Chuva em 20 min", at(5 * ROUND + STREET_MS + 1 + moved))
    }

    @Test
    fun `near the maneuver an echo changes nothing, the street only and the hold after it as long as ever`() {
        feed(rain)
        assertEquals(STREET, at(0, meters = 900))
        assertEquals("a page, far", "Chuva em 20 min", at(STREET_MS, meters = 900))
        assertEquals("near, the street", STREET, at(STREET_MS + 1_000, meters = 100))
        echo(STREET_MS + 2_000)
        assertEquals("the echo is gone, still near", STREET, at(STREET_MS + 3_500, meters = 100))
        // The maneuver is passed: the 15 s of street, and the 20 s again, then the page.
        val passed = STREET_MS + 4_000
        assertEquals(STREET, at(passed, meters = 900))
        assertEquals(STREET, at(passed + 14_999, meters = 900))
        assertEquals(STREET, at(passed + 15_000 + STREET_MS - 1, meters = 900))
        assertEquals("Chuva em 20 min", at(passed + 15_000 + STREET_MS, meters = 900))
    }

    @Test
    fun `an echo in the 15 s of street after a maneuver is not eaten from them`() {
        feed(rain)
        assertEquals(STREET, at(0, meters = 900))
        at(STREET_MS + 1_000, meters = 100)
        val passed = STREET_MS + 2_000
        assertEquals(STREET, at(passed, meters = 900))
        echo(passed + 5_000)
        assertEquals(STREET, at(passed + 6_500, meters = 900))
        val firstPage = passed + 1_500 + 15_000 + STREET_MS
        assertEquals("15 s and 20 s of street, the echo not counted", STREET, at(firstPage - 1, meters = 900))
        assertEquals("Chuva em 20 min", at(firstPage, meters = 900))
        assertEquals("Chuva em 20 min", at(firstPage + PAGE_MS - 1, meters = 900))
        assertEquals(STREET, at(firstPage + PAGE_MS, meters = 900))
    }

    @Test
    fun `an echo that sees the maneuver come near ends in the street only, and the 15 s start when the title is looked at again`() {
        feed(rain)
        assertEquals(STREET, at(0, meters = 900))
        assertEquals("Chuva em 20 min", at(STREET_MS, meters = 900))
        echo(STREET_MS + 1_000)
        assertEquals("under 500 m at the first look after the echo", STREET, at(STREET_MS + 2_500, meters = 450))
        assertEquals("far again", STREET, at(STREET_MS + 3_000, meters = 900))
        assertEquals(STREET, at(STREET_MS + 3_000 + 15_000 + STREET_MS - 1, meters = 900))
        assertEquals("Chuva em 20 min", at(STREET_MS + 3_000 + 15_000 + STREET_MS, meters = 900))
    }

    @Test
    fun `with echoes all through, every page is on 7 s to the 100 ms, with a route and without, and the turns of the cycle go in their order`() {
        for (meters in listOf(FAR, null)) {
            fresh()
            feed(thunder, strongWind, rain, wind)
            val runs = mutableListOf<Pair<String, Long>>()
            var current: String? = null
            var seen = 0L
            for (t in 0L..10 * 60_000L step 100L) {
                now = START + t
                // An echo of 1.5 s each 11.3 s, from the 5th second: it falls at every point of the base and of the page in turn.
                if (t >= 5_000 && (t - 5_000) % 11_300 < 1_500) {
                    title.hold()
                    continue
                }
                val said = title.page(meters, true)
                if (said != current) {
                    current?.let { runs += it to seen }
                    current = said
                    seen = 0
                }
                seen += 100
            }
            val pages = runs.dropLast(1)
            assertTrue("route=$meters: ${pages.size} runs", pages.size >= 15)
            for ((text, ms) in pages) assertEquals("route=$meters: $text", 7_000L, ms)
            assertEquals(
                "route=$meters",
                listOf("Trovoada", "Vento 75 km/h", "Trovoada", "Vento 75 km/h", "Trovoada", "Chuva em 20 min", "Vento 75 km/h", "Trovoada", "Vento 75 km/h", "Trovoada", "Vento 75 km/h", "Vento 45 km/h"),
                pages.map { it.first }.take(12),
            )
        }
    }

    @Test
    fun `off, an echo holds nothing and a switch on starts the clock clean`() {
        title.hold()
        feed(rain)
        assertEquals("REau", at(0, base = "REau", meters = null))
        assertEquals("Chuva em 20 min", at(7_000, base = "REau", meters = null))
    }

    private companion object {
        const val STREET = "Rua das Flores"
        const val PERIOD = 7_000L
        const val STREET_MS = 20_000L
        const val PAGE_MS = 7_000L
        const val ROUND = STREET_MS + PAGE_MS
        const val START = 50_000L
        const val FAR = 5_000
    }
}
