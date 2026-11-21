package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import com.reau.app.weather.RingIcon
import com.reau.app.weather.RingKind
import com.reau.app.weather.RingPage
import com.reau.app.weather.RingTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The info ring's pages in the title of the route card (P31 Título), as the dash reads it: while guiding and with no route, the street or "REau"
 * turning with the pages, the street alone near the maneuver, the echo of a joystick tap in front of everything for its 1.5 s, and, with the
 * option off, the card exactly as it was. One clock, the test's own, for the echo and for the turns.
 */
class DashSessionInfoTitleTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val session = DashSession(scope)
    private var now = 100_000L
    private val logs = mutableListOf<String>()

    private val rain = TitleEntry(RingPage(RingKind.RAIN_SOON, 2, RingIcon.RAIN, RingTone.RAIN, emptyList()), "Chuva em 20 min")

    init {
        session.echo = TimedShow(ActionEcho.SHOW_MS, nowMs = { now })
        session.infoTitle = InfoTitle(nowMs = { now }, log = { logs += it })
        session.infoTitle.source = { TitleSource(listOf(rain), true, PERIOD) }
    }

    @After
    fun tearDown() = scope.cancel()

    /** The title (05 01) of the route card now, as the dash reads it. */
    private fun title(card: ByteArray = session.routeCardNow()): String {
        var i = 17
        while (i + 4 <= card.size) {
            val len = ((card[i + 2].toInt() and 0xFF) shl 8) or (card[i + 3].toInt() and 0xFF)
            if ((card[i].toInt() and 0xFF) == 5 && (card[i + 1].toInt() and 0xFF) == 0x01) return String(card, i + 4, len - 1, Charsets.ISO_8859_1)
            i += 4 + len
        }
        error("no title in the route card")
    }

    private fun guide(meters: Int, road: String = STREET) =
        session.updateNavInfo(0x14, meters, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS, road = road)

    private fun on() {
        session.infoTitle.enabled = true
    }

    @Test
    fun `while guiding the street turns with the page, in the ring's time`() {
        on()
        guide(900)
        assertEquals(STREET, title())
        now += PERIOD - 1
        assertEquals(STREET, title())
        now += 1
        assertEquals("Chuva em 20 min", title())
        now += PERIOD
        assertEquals(STREET, title())
    }

    @Test
    fun `with no route REau turns with the page`() {
        on()
        assertEquals("REau", title())
        now += PERIOD
        assertEquals("Chuva em 20 min", title())
        now += PERIOD
        assertEquals("REau", title())
    }

    @Test
    fun `at 299 m the title is the street at the page's turn, and at 300 m the page is back`() {
        on()
        guide(900)
        title()
        now += PERIOD
        guide(299)
        assertEquals(STREET, title())
        guide(300)
        assertEquals("Chuva em 20 min", title())
    }

    @Test
    fun `the distance is read in metres whatever the unit the dash shows`() {
        on()
        // 0.2 km (km x 10 = 2) is 200 m: near. 0.3 km (3) is 300 m: far.
        session.updateNavInfo(0x14, 2, DashCommands.NAV_UNIT_KM_TENTHS, 32, DashCommands.NAV_UNIT_KM_TENTHS, road = STREET)
        title()
        now += PERIOD
        assertEquals(STREET, title())
        session.updateNavInfo(0x14, 3, DashCommands.NAV_UNIT_KM_TENTHS, 32, DashCommands.NAV_UNIT_KM_TENTHS, road = STREET)
        assertEquals("Chuva em 20 min", title())
        // 900 feet is 274 m: near. 0.2 mile (feet below 550 are feet; above, tenths of a mile) is 322 m: far.
        session.updateNavInfo(0x14, 900, DashCommands.NAV_UNIT_FEET, 32, DashCommands.NAV_UNIT_KM_TENTHS, road = STREET)
        assertEquals(STREET, title())
        session.updateNavInfo(0x14, 2, DashCommands.NAV_UNIT_MILE_TENTHS, 32, DashCommands.NAV_UNIT_KM_TENTHS, road = STREET)
        assertEquals("Chuva em 20 min", title())
    }

    @Test
    fun `a street the maneuver card does not name leaves REau to turn with the pages`() {
        on()
        guide(900, road = "")
        title()
        now += PERIOD
        assertEquals("Chuva em 20 min", title())
        now += PERIOD
        assertEquals("REau", title())
    }

    @Test
    fun `the echo of a tap beats the street and the page for 1500 ms, then the turns go on`() {
        on()
        guide(900)
        title()
        now += PERIOD
        assertEquals("Chuva em 20 min", title())
        session.echoAction("Next song")
        assertEquals("Next song", title())
        now += 1_499
        assertEquals("still there a millisecond before", "Next song", title())
        now += 1
        assertEquals("the page's turn goes on where it was", "Chuva em 20 min", title())
        // And at the street's turn, near or far, the echo is still first.
        now += PERIOD
        session.echoAction("Back")
        assertEquals("Back", title())
        guide(100)
        assertEquals("Back", title())
        now += 1_500
        assertEquals(STREET, title())
    }

    @Test
    fun `the echo beats the pages with no route too`() {
        on()
        now += PERIOD
        session.echoAction("Confirm")
        assertEquals("Confirm", title())
        now += 1_500
        assertEquals("Chuva em 20 min", title())
    }

    @Test
    fun `with the option off the card is the one there always was, byte for byte, with or without pages to say`() {
        guide(900)
        val bare = DashSession(scope)
        bare.updateNavInfo(0x14, 900, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS, road = STREET)
        val expected = bare.routeCardNow()
        for (k in 0..4) {
            assertArrayEquals("at ${k * PERIOD} ms", expected, session.routeCardNow())
            now += PERIOD
        }
        assertEquals(STREET, title(expected))
        session.clearNavInfo()
        bare.clearNavInfo()
        assertEquals("REau", title())
        assertEquals("REau", title(bare.routeCardNow()))
    }

    @Test
    fun `switching the option off while a page shows brings the street back at once`() {
        on()
        guide(900)
        title()
        now += PERIOD
        assertEquals("Chuva em 20 min", title())
        session.infoTitle.enabled = false
        assertEquals(STREET, title())
    }

    @Test
    fun `the joystick hiding the info takes the pages from the title and showing brings them back`() {
        on()
        guide(900)
        title()
        now += PERIOD
        session.ringStep = 1
        assertEquals(STREET, title())
        session.ringStep = 0
        assertEquals("Chuva em 20 min", title())
    }

    @Test
    fun `a page reaches the card as the Latin-1 bytes the dash reads`() {
        session.infoTitle.source = { TitleSource(listOf(TitleEntry(rain.page, "Pôr do sol 18:12")), true, PERIOD) }
        on()
        title()
        now += PERIOD
        assertEquals("Pôr do sol 18:12", title())
        assertEquals(true, session.routeCardNow().any { (it.toInt() and 0xFF) == 0xF4 })
    }

    @Test
    fun `the session starts with the option off and logs nothing about the title`() {
        assertEquals(false, DashSession(scope).infoTitle.enabled)
        assertEquals(emptyList<String>(), logs)
    }

    private companion object {
        const val STREET = "Rua das Flores"
        const val PERIOD = 7_000L
    }
}
