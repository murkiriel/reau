package com.reau.app.dash.nav

import com.reau.app.settings.DrivingSideMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The side of the road for the arrows Android Auto gives no direction to (20/11/2026): the rider's pick, else the mobile network's country, else the SIM's,
 * else right-hand traffic; read again every ten minutes; one log line per decision or change, with the side and the source and never the country.
 */
class DrivingSideSourceTest {
    private var now = 0L
    private var network: String? = null
    private var sim: String? = null
    private var mode = DrivingSideMode.AUTO
    private var networkReads = 0
    private var simReads = 0
    private val lines = mutableListOf<String>()

    private fun source(refreshMs: Long = DrivingSideSource.REFRESH_MS) = DrivingSideSource(
        clockMs = { now },
        networkCountry = { networkReads++; network },
        simCountry = { simReads++; sim },
        log = { lines += it },
        mode = { mode },
        refreshMs = refreshMs,
    )

    @Test
    fun `the mobile network's country comes first`() {
        network = "IN"
        sim = "BR"
        assertEquals(DrivingSide.LEFT_HAND, source().side())
        network = "br"
        sim = "GB"
        assertEquals("the SIM is not even asked for when the network answers", DrivingSide.RIGHT_HAND, source().side())
        assertEquals(0, simReads)
    }

    @Test
    fun `the SIM's country is used when the network has none`() {
        network = ""
        sim = "GB"
        assertEquals(DrivingSide.LEFT_HAND, source().side())
        assertEquals(listOf("driving side: LEFT_HAND (from the SIM card)"), lines)
        network = null
        sim = " in "
        assertEquals("blank around the code is not a country", DrivingSide.LEFT_HAND, source().refresh())
    }

    @Test
    fun `with neither country the side is right-hand traffic`() {
        network = ""
        sim = null
        assertEquals(DrivingSide.RIGHT_HAND, source().side())
        assertEquals(listOf("driving side: RIGHT_HAND (default, no mobile network country)"), lines)
    }

    @Test
    fun `a reader that fails is a country not known`() {
        val s = DrivingSideSource({ now }, { error("no telephony") }, { "IN" }, { lines += it })
        assertEquals("the SIM answers when the network reader throws", DrivingSide.LEFT_HAND, s.side())
        val none = DrivingSideSource({ now }, { error("no telephony") }, { error("no SIM") }, { lines += it })
        assertEquals(DrivingSide.RIGHT_HAND, none.side())
    }

    @Test
    fun `the country is read again after ten minutes and not before`() {
        network = "BR"
        val s = source()
        assertEquals(DrivingSide.RIGHT_HAND, s.side())
        network = "IN"
        now = DrivingSideSource.REFRESH_MS - 1
        assertEquals("not yet", DrivingSide.RIGHT_HAND, s.side())
        assertEquals(1, networkReads)
        now = DrivingSideSource.REFRESH_MS
        assertEquals("a border was crossed", DrivingSide.LEFT_HAND, s.side())
        assertEquals(2, networkReads)
        assertEquals(
            listOf("driving side: RIGHT_HAND (from the mobile network)", "driving side: LEFT_HAND (from the mobile network)"),
            lines,
        )
    }

    @Test
    fun `refresh reads at once, and says nothing when nothing changed`() {
        network = "IN"
        val s = source()
        s.refresh()
        s.refresh()
        s.side()
        assertEquals(1, lines.size)
        assertEquals(2, networkReads)
        network = "BR"
        assertEquals(DrivingSide.RIGHT_HAND, s.refresh())
        assertEquals(2, lines.size)
    }

    @Test
    fun `the log says the side and where it came from, never the country`() {
        for (country in listOf("IN", "GB", "BR", "us")) {
            lines.clear()
            network = country
            source().side()
            assertEquals(1, lines.size)
            assertFalse(lines.single(), Regex("""\b${country}\b""", RegexOption.IGNORE_CASE).containsMatchIn(lines.single()))
        }
        network = "IN"
        lines.clear()
        source().side()
        assertEquals(listOf("driving side: LEFT_HAND (from the mobile network)"), lines)
    }

    @Test
    fun `a side chosen in settings wins over the country and reads none`() {
        network = "IN"
        sim = "IN"
        mode = DrivingSideMode.RIGHT_HAND
        val s = source()
        assertEquals(DrivingSide.RIGHT_HAND, s.side())
        mode = DrivingSideMode.LEFT_HAND
        network = "BR"
        assertEquals(DrivingSide.LEFT_HAND, s.side())
        now = DrivingSideSource.REFRESH_MS * 5
        assertEquals(DrivingSide.LEFT_HAND, s.side())
        assertEquals("the country is not read in a chosen mode", 0, networkReads + simReads)
        assertEquals(
            listOf("driving side: RIGHT_HAND (chosen in settings)", "driving side: LEFT_HAND (chosen in settings)"),
            lines,
        )
    }

    @Test
    fun `changing the mode counts at the next ask, with no waiting`() {
        network = "IN"
        val s = source()
        assertEquals(DrivingSide.LEFT_HAND, s.side())
        mode = DrivingSideMode.RIGHT_HAND
        assertEquals("the next card already has the new side", DrivingSide.RIGHT_HAND, s.side())
        mode = DrivingSideMode.AUTO
        assertEquals("and back to automatic reads the country again", DrivingSide.LEFT_HAND, s.side())
        assertEquals(2, networkReads)
        assertTrue(lines.last(), lines.last().endsWith("(from the mobile network)"))
    }
}
