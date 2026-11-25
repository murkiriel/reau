package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
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
}
