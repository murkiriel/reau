package com.reau.app.dash

import com.reau.app.dash.nav.AaManeuvers
import com.reau.app.dash.protocol.DashCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The time left and the arrival time the route card carries do not flicker by one minute (pendency 37, ride of 25/11/2026: the card said
 * 7 min, 8 min for a second, 7 min, and 06:59, 07:00, 06:59), from [DashSession.updateNavInfo] to the card's 05 08 and 05 0B.
 */
class DashSessionArrivalTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val session = DashSession(scope)

    @After
    fun tearDown() = scope.cancel()

    private fun guide(eta: String, minutes: Int, second: Int) = session.updateNavInfo(
        0x14, 3_000, DashCommands.NAV_UNIT_METERS, 92, DashCommands.NAV_UNIT_KM_TENTHS, etaHHMM = eta,
        remainingSeconds = minutes * 60L, nowMs = 10_000L + second * 1_000L,
    )

    /** The card's field of [sub] in type 5, as the dash reads it. */
    private fun field(sub: Int): String {
        val pkt = session.routeCardNow()
        var i = 17
        while (i + 4 <= pkt.size) {
            val len = ((pkt[i + 2].toInt() and 0xFF) shl 8) or (pkt[i + 3].toInt() and 0xFF)
            if ((pkt[i].toInt() and 0xFF) == 5 && (pkt[i + 1].toInt() and 0xFF) == sub) {
                return String(pkt, i + 4, len, Charsets.US_ASCII)
            }
            i += 4 + len
        }
        error("no 05 %02X in the card".format(sub))
    }

    @Test
    fun `the time left and the arrival time of the ride of 25-11 reach the card without the flicker`() {
        guide("07:00", 8, 0)
        assertEquals("0700", field(0x08))
        assertEquals("000800", field(0x0B))
        guide("06:59", 7, 13)
        assertEquals("the minute went down: shown", "000700", field(0x0B))
        assertEquals("the arrival time is not moved by 26 s of 06:59", "0700", field(0x08))
        guide("06:59", 8, 23)
        assertEquals("8 for one second: not shown", "000700", field(0x0B))
        guide("06:59", 7, 24)
        guide("07:00", 7, 39)
        guide("07:00", 7, 48)
        guide("07:00", 8, 48)
        assertEquals("000700", field(0x0B))
        assertEquals("0700", field(0x08))
        guide("06:59", 6, 54)
        assertEquals("a minute less: shown at once", "000600", field(0x0B))
        assertEquals("0700", field(0x08))
    }

    @Test
    fun `a big change goes through at once`() {
        guide("07:00", 8, 0)
        guide("07:10", 18, 1)
        assertEquals("001800", field(0x0B))
        assertEquals("0710", field(0x08))
    }

    @Test
    fun `the end of a guidance lets the next one start from its own figures`() {
        guide("07:00", 7, 0)
        session.clearNavInfo()
        guide("07:01", 8, 1)
        assertEquals("000800", field(0x0B))
        assertEquals("0701", field(0x08))
    }

    /** A guidance with no seconds from Android Auto (Waze): the session takes the time left from the arrival it shows, at the phone's [clock]. */
    private fun guideWaze(eta: String, second: Int) = session.updateNavInfo(
        0x14, 3_000, DashCommands.NAV_UNIT_METERS, 92, DashCommands.NAV_UNIT_KM_TENTHS, etaHHMM = eta,
        nowMs = 10_000L + second * 1_000L, clock = java.time.LocalTime.of(6, 51, 25).plusSeconds(second.toLong()),
    )

    private fun leftAt(second: Int, eta: String) =
        AaManeuvers.remainingFromEta(eta, java.time.LocalTime.of(6, 51, 25).plusSeconds(second.toLong()))!!

    @Test
    fun `with no seconds from Android Auto the time left follows the arrival time shown, not the flicker`() {
        // The arrival flickers as in the ride (07:00, 06:59 from 13 s to 38 s, 07:00 again); the time left taken from the raw text
        // went 9, 8, 9 (review of 26/11/2026, A1). From the arrival shown it only goes down, with the clock.
        assertNotEquals("the raw 06:59 is a minute less", leftAt(20, "07:00"), leftAt(20, "06:59"))
        var before = Long.MAX_VALUE
        for (second in 0..60) {
            val eta = if (second in 13..38) "06:59" else "07:00"
            guideWaze(eta, second)
            val shown = field(0x0B).toLong()
            assertEquals("the arrival time shown at $second s", "0700", field(0x08))
            assertEquals("the time left at $second s is that of 07:00", leftAt(second, "07:00") / 60, shown / 100)
            assertTrue("never up: $shown after $before", shown <= before)
            before = shown
        }
    }

    @Test
    fun `the seconds from Android Auto win over the arrival time even with the clock given`() {
        session.updateNavInfo(
            0x14, 3_000, DashCommands.NAV_UNIT_METERS, 92, DashCommands.NAV_UNIT_KM_TENTHS, etaHHMM = "07:00",
            remainingSeconds = 480L, nowMs = 10_000L, clock = java.time.LocalTime.of(6, 51, 25),
        )
        assertEquals("000800", field(0x0B))
    }

    @Test
    fun `the end of the dash session lets the next link start from its own figures`() {
        guide("07:00", 7, 0)
        session.disconnect()
        guide("07:01", 8, 1)
        assertEquals("000800", field(0x0B))
        assertEquals("0701", field(0x08))
    }
}
