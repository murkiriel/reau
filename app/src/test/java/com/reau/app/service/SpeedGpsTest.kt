package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The speed profile's ask for the GPS: the pure rule, the 10 s retention after the route, and the lines it writes in the ride log. */
class SpeedGpsTest {
    private val asks = mutableListOf<Boolean>()
    private val said = mutableListOf<String>()
    private val gps = SpeedGps(ask = { asks += it }, log = { said += it })

    private fun look(
        at: Long,
        key: Boolean = true,
        location: Boolean = true,
        profile: Boolean = true,
        route: Boolean = true,
        permitted: Boolean = true,
    ) = gps.step(at, key, location, profile, route, permitted)

    @Test
    fun `the rule is true only with all five true`() {
        assertTrue(speedGpsWanted(true, true, true, true, true))
        assertFalse("switch off", speedGpsWanted(false, true, true, true, true))
        assertFalse("location off", speedGpsWanted(true, false, true, true, true))
        assertFalse("speed profile off", speedGpsWanted(true, true, false, true, true))
        assertFalse("no route", speedGpsWanted(true, true, true, false, true))
        assertFalse("no permission", speedGpsWanted(true, true, true, true, false))
        assertFalse(speedGpsWanted(false, false, false, false, false))
    }

    @Test
    fun `with the switch off the GPS is never asked and nothing is written, whatever the rest says`() {
        for (t in 0L..30L) look(t * 1_000, key = false)
        look(31_000, key = false, route = false)
        gps.stop()
        assertEquals(emptyList<Boolean>(), asks)
        assertEquals(emptyList<String>(), said)
        assertFalse(gps.isAsking)
    }

    @Test
    fun `a route with everything on asks for the GPS at each look and says so once`() {
        assertTrue(look(0))
        assertTrue(look(1_000))
        assertTrue(look(2_000))
        assertEquals(listOf(true, true, true), asks)
        assertEquals(listOf("gps: on for the speed profile (route)"), said)
        assertTrue(gps.isAsking)
    }

    @Test
    fun `no route no ask`() {
        assertFalse(look(0, route = false))
        assertEquals(emptyList<Boolean>(), asks)
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun `the ask stays 10 s after the route ends and goes then`() {
        look(0)
        assertTrue("the route ended", look(1_000, route = false))
        assertTrue(look(9_999, route = false))
        assertEquals("still held 9.999 s after the last look with the route", listOf("gps: on for the speed profile (route)"), said)
        assertFalse("10 s after the last look with the route", look(10_000, route = false))
        assertEquals(false, asks.last())
        assertEquals(listOf("gps: on for the speed profile (route)", "gps: off for the speed profile (route ended)"), said)
        assertFalse(gps.isAsking)
    }

    @Test
    fun `a route that flickers in under 10 s does not switch the GPS off and on`() {
        look(0)
        for (t in 1..8) {
            look(t * 1_000L, route = false)
            look(t * 1_000L + 500, route = true)
        }
        look(9_000, route = false)
        assertTrue(look(15_000, route = false))
        assertEquals("one on in all that time", 1, said.size)
        assertFalse("never asked to let go", asks.contains(false))
    }

    @Test
    fun `the retention counts from the last look with the route, not from the first without it`() {
        look(0)
        look(5_000)
        assertTrue(look(14_999, route = false))
        assertFalse(look(15_000, route = false))
    }

    @Test
    fun `a route that comes back after the GPS went asks again and says so again`() {
        look(0)
        look(20_000, route = false)
        assertEquals(2, said.size)
        assertTrue(look(21_000))
        assertEquals(3, said.size)
        assertEquals("gps: on for the speed profile (route)", said.last())
    }

    @Test
    fun `without a route before, the retention does not ask`() {
        assertFalse(look(0, route = false))
        assertFalse(look(5_000, route = false))
        assertEquals(emptyList<Boolean>(), asks)
    }

    @Test
    fun `the switch turned off during a route lets the GPS go at once, with its reason`() {
        look(0)
        assertFalse(look(1_000, key = false))
        assertEquals(listOf("gps: on for the speed profile (route)", "gps: off for the speed profile (key off)"), said)
        assertEquals(false, asks.last())
    }

    @Test
    fun `the location, the profile and the permission going away let the GPS go at once, held route or not`() {
        look(0)
        assertFalse(look(1_000, location = false))
        assertEquals("gps: off for the speed profile (location off)", said.last())
        look(2_000)
        assertFalse(look(3_000, profile = false))
        assertEquals("gps: off for the speed profile (speed profile off)", said.last())
        look(4_000)
        assertFalse(look(5_000, permitted = false))
        assertEquals("gps: off for the speed profile (no location permission)", said.last())
    }

    @Test
    fun `a held route is not carried over a switch that went off and came back`() {
        look(0)
        look(1_000, key = false)
        assertFalse("the route is over, the old retention is gone", look(2_000, route = false))
    }

    @Test
    fun `the end of the projection lets the GPS go and a later look asks for nothing`() {
        look(0)
        gps.stop()
        assertEquals(listOf("gps: on for the speed profile (route)", "gps: off for the speed profile (projection stopped)"), said)
        assertEquals(false, asks.last())
        val n = asks.size
        assertFalse(look(1_000))
        assertEquals(n, asks.size)
        gps.stop()
        assertEquals("said once", 2, said.size)
    }

    @Test
    fun `the end of the projection with no ask in force says nothing`() {
        gps.stop()
        assertEquals(emptyList<String>(), said)
        assertEquals(emptyList<Boolean>(), asks)
    }

    @Test
    fun `the retention is 10 s`() {
        assertEquals(10_000L, SpeedGps.HOLD_MS)
    }
}
