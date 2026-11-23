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
 * turning with the pages (the street 20 s and a page 7 s while guiding), the street alone within 500 m of the maneuver and for 15 s after it, the echo of a joystick tap in front of everything for its 1.5 s, and, with the
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

    /** The street field (05 01) of the nav packet now, the other sender of the same field of the dash. */
    private fun navTitle(): String? = DashDump.outgoingTlvs(session.navPacketNow()!!).firstOrNull { it.type == 0x05 && it.sub == 0x01 }
        ?.let { String(it.value, 0, it.value.size - 1, Charsets.ISO_8859_1) }

    private fun guide(meters: Int, road: String = STREET) =
        session.updateNavInfo(0x14, meters, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS, road = road)

    private fun on() {
        session.infoTitle.enabled = true
    }

    @Test
    fun `while guiding the street stays 20 s and the page 7 s, whatever the ring's time`() {
        on()
        guide(900)
        assertEquals(STREET, title())
        now += STREET_MS - 1
        assertEquals(STREET, title())
        now += 1
        assertEquals("Chuva em 20 min", title())
        now += PAGE_MS - 1
        assertEquals("Chuva em 20 min", title())
        now += 1
        assertEquals(STREET, title())
    }

    @Test
    fun `the nav packet says the same title as the card, the page and the echo too, never the street over them`() {
        // The ride of 23/11/2026: the card said the page and the nav packet, 80 to 130 ms apart, the street, every second: the dash flipped between the two.
        on()
        guide(900)
        assertEquals(STREET, title())
        assertEquals(STREET, navTitle())
        now += STREET_MS
        assertEquals("Chuva em 20 min", title())
        assertEquals("the page in the packet too", "Chuva em 20 min", navTitle())
        session.echoAction("Next song")
        assertEquals("Next song", title())
        assertEquals("the echo in the packet too", "Next song", navTitle())
        now += 1_500
        assertEquals("Chuva em 20 min", title())
        assertEquals("Chuva em 20 min", navTitle())
        now += PAGE_MS
        assertEquals(STREET, title())
        assertEquals(STREET, navTitle())
    }

    @Test
    fun `second by second for two rounds the two packets carry one title`() {
        on()
        guide(900)
        val seen = mutableSetOf<String>()
        for (second in 0 until 2 * (STREET_MS + PAGE_MS).toInt() / 1_000) {
            val card = title()
            assertEquals("second $second", card, navTitle())
            seen += card
            now += 1_000
        }
        assertEquals("the street and the page, both, were sent", setOf(STREET, "Chuva em 20 min"), seen)
    }

    @Test
    fun `a street of 26 letters is the same 19 letters in the card and in the nav packet, second by second`() {
        val long = "Avenida das Palmeiras Alta" // 26 letters; cut at 19
        val cut = long.take(19)
        assertEquals(19, DashCommands.ROAD_MAX)
        on()
        guide(900, road = long)
        for (second in 0 until (STREET_MS + PAGE_MS).toInt() / 1_000) {
            val card = title()
            val packet = navTitle()
            assertEquals("second $second", card, packet)
            assertEquals("second $second", true, card == cut || card == "Chuva em 20 min")
            now += 1_000
        }
        // The card with the option off too: the 19 letters, never the whole street.
        session.infoTitle.enabled = false
        assertEquals(cut, title())
        assertEquals(cut, navTitle())
    }

    @Test
    fun `with the option off the nav packet is the street, as it always was`() {
        guide(900)
        title()
        assertEquals(STREET, navTitle())
        now += STREET_MS
        title()
        assertEquals(STREET, navTitle())
    }

    @Test
    fun `a disconnection does not carry the last page to the next session's packet`() {
        on()
        guide(900)
        now += STREET_MS
        assertEquals("Chuva em 20 min", title())
        session.disconnect()
        guide(900)
        assertEquals(STREET, navTitle())
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
    fun `at 499 m the title is the street at the page's turn`() {
        on()
        guide(900)
        title()
        now += STREET_MS
        guide(499)
        assertEquals(STREET, title())
    }

    @Test
    fun `at 500 m the page is there at its turn`() {
        on()
        guide(900)
        title()
        now += STREET_MS
        guide(500)
        assertEquals("Chuva em 20 min", title())
    }

    @Test
    fun `after the maneuver the street holds 15 s and then starts its 20 s again`() {
        on()
        guide(900)
        title()
        now += STREET_MS
        guide(100)
        assertEquals(STREET, title())
        now += 1_000
        guide(900)
        assertEquals(STREET, title())
        now += 34_999
        assertEquals("15 s and 20 s of street", STREET, title())
        now += 1
        assertEquals("Chuva em 20 min", title())
    }

    /** The title at the first page's turn, the dash saying [value] [unit] to the maneuver (the first look is far, as on every new route). */
    private fun atTheTurn(value: Int, unit: Int): String {
        session.infoTitle = InfoTitle(nowMs = { now }, log = { logs += it })
        session.infoTitle.source = { TitleSource(listOf(rain), true, PERIOD) }
        on()
        session.updateNavInfo(0x14, 900, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS, road = STREET)
        title()
        now += STREET_MS
        session.updateNavInfo(0x14, value, unit, 32, DashCommands.NAV_UNIT_KM_TENTHS, road = STREET)
        return title()
    }

    @Test
    fun `the distance is read in metres whatever the unit the dash shows`() {
        // 0.4 km (km x 10 = 4) is 400 m: near. 0.5 km (5) is 500 m: far.
        assertEquals(STREET, atTheTurn(4, DashCommands.NAV_UNIT_KM_TENTHS))
        assertEquals("Chuva em 20 min", atTheTurn(5, DashCommands.NAV_UNIT_KM_TENTHS))
        // 540 feet is 165 m: near (feet are shown below 550). 0.3 mile is 483 m: near. 0.4 mile is 644 m: far.
        assertEquals(STREET, atTheTurn(540, DashCommands.NAV_UNIT_FEET))
        assertEquals(STREET, atTheTurn(3, DashCommands.NAV_UNIT_MILE_TENTHS))
        assertEquals("Chuva em 20 min", atTheTurn(4, DashCommands.NAV_UNIT_MILE_TENTHS))
    }

    @Test
    fun `a street the maneuver card does not name leaves REau to turn with the pages`() {
        on()
        guide(900, road = "")
        title()
        now += STREET_MS
        assertEquals("Chuva em 20 min", title())
        now += PAGE_MS
        assertEquals("REau", title())
    }

    @Test
    fun `the echo of a tap beats the street and the page for 1500 ms, then the turns go on`() {
        on()
        guide(900)
        title()
        now += STREET_MS
        assertEquals("Chuva em 20 min", title())
        session.echoAction("Next song")
        assertEquals("Next song", title())
        now += 1_499
        assertEquals("still there a millisecond before", "Next song", title())
        now += 1
        assertEquals("the page's turn goes on where it was", "Chuva em 20 min", title())
        // And at the street's turn, near or far, the echo is still first.
        now += PAGE_MS
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
        now += STREET_MS
        assertEquals("Chuva em 20 min", title())
        session.infoTitle.enabled = false
        assertEquals(STREET, title())
    }

    @Test
    fun `the joystick hiding the info takes the pages from the title and showing brings them back`() {
        on()
        guide(900)
        title()
        now += STREET_MS
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
        const val STREET_MS = 20_000L
        const val PAGE_MS = 7_000L
    }
}
