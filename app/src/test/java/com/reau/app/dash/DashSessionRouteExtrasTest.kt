package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route card always carries the real time left and the maneuver after the next (28/10/2026: the switch "Rota real no painel" is gone,
 * nobody can go back to the captured template's figures).
 */
class DashSessionRouteExtrasTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val session = DashSession(scope)

    @After
    fun tearDown() = scope.cancel()

    private data class Tlv(val type: Int, val sub: Int, val value: ByteArray)

    private fun tlvs(pkt: ByteArray): List<Tlv> {
        val out = mutableListOf<Tlv>()
        var i = 17
        while (i + 4 <= pkt.size) {
            val len = ((pkt[i + 2].toInt() and 0xFF) shl 8) or (pkt[i + 3].toInt() and 0xFF)
            out += Tlv(pkt[i].toInt() and 0xFF, pkt[i + 1].toInt() and 0xFF, pkt.copyOfRange(i + 4, minOf(i + 4 + len, pkt.size)))
            i += 4 + len
        }
        return out
    }

    private fun guide(meters: Int, remaining: Long = 900L, next: Int = 0x14) = session.updateNavInfo(
        0x14, meters, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS,
        remainingSeconds = remaining, nextManeuver = next,
    )

    private fun Tlv.byte() = value.single().toInt() and 0xFF

    @Test
    fun `the card always has the real time left and the next maneuver, close to the turn, with nothing to switch on`() {
        guide(80)
        val t = tlvs(session.routeCardNow())
        assertEquals("001500", String(t.single { it.type == 5 && it.sub == 0x0B }.value, Charsets.US_ASCII))
        assertEquals(0x20, t.single { it.type == 5 && it.sub == 0x55 }.byte())
        assertEquals(0x14, t.single { it.type == 5 && it.sub == 0x03 }.byte())
    }

    @Test
    fun `the dash session takes no switch for them`() {
        val params = DashSession::class.java.declaredMethods.filter { it.name == "updateNavInfo" }.flatMap { it.parameterTypes.toList() }
        assertTrue("updateNavInfo takes no Boolean any more: $params", params.none { it == Boolean::class.javaPrimitiveType })
    }

    @Test
    fun `far from the turn the next maneuver is none`() {
        guide(2_000)
        assertEquals(DashCommands.NEXT_MANEUVER_NONE, tlvs(session.routeCardNow()).single { it.type == 5 && it.sub == 0x03 }.byte())
    }

    @Test
    fun `with no time left known the two fields are out`() {
        guide(80, remaining = DashCommands.REMAINING_OMIT)
        val t = tlvs(session.routeCardNow())
        assertTrue(t.none { it.type == 5 && it.sub == 0x0B })
        assertTrue(t.none { it.type == 5 && it.sub == 0x55 })
    }
}
