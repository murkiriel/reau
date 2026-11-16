package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The line the ride log gets when the switch has the GPS asked and it has no fix yet; the speed stays the route's. */
class ProfileSpeedNoFixTest {
    private val said = mutableListOf<String>()
    private val speed = ProfileSpeed { said += it }
    private val line = "speed profile: GPS key on, no GPS fix yet; route speed used"

    @Test
    fun `with the switch on, the GPS asked and no fix the line is written once and the route's speed is used`() {
        assertEquals(60.0, speed.kmh(on = true, routeActive = true, gpsMps = null, routeKmh = 60.0, gpsAsked = true)!!, 0.0)
        assertEquals(61.0, speed.kmh(on = true, routeActive = true, gpsMps = null, routeKmh = 61.0, gpsAsked = true)!!, 0.0)
        speed.kmh(on = true, routeActive = true, gpsMps = null, routeKmh = 62.0, gpsAsked = true)
        assertEquals(listOf(line), said)
        assertEquals("the text is the one the reader looks for", line, ProfileSpeed.NO_FIX_LINE)
    }

    @Test
    fun `the first fix says the source changed, and a fix that goes says the way back without the no-fix line`() {
        speed.kmh(true, true, null, 50.0, gpsAsked = true)
        speed.kmh(true, true, 20f, 50.0, gpsAsked = true)
        speed.kmh(true, true, null, 50.0, gpsAsked = true)
        speed.kmh(true, true, null, 50.0, gpsAsked = true)
        assertEquals(3, said.size)
        assertEquals(line, said[0])
        assertEquals("speed profile: reads the GPS speed (72 km/h; the route says 50 km/h)", said[1])
        assertEquals("speed profile: back to the route's estimate (no GPS speed, or no route)", said[2])
    }

    @Test
    fun `it is written again after the switch is turned off and on`() {
        speed.kmh(true, true, null, 50.0, gpsAsked = true)
        speed.kmh(false, true, null, 50.0, gpsAsked = false)
        speed.kmh(true, true, null, 50.0, gpsAsked = true)
        assertEquals(listOf(line, line), said)
    }

    @Test
    fun `it is written again after the ask went and came back`() {
        speed.kmh(true, true, null, 50.0, gpsAsked = true)
        speed.kmh(true, false, null, 50.0, gpsAsked = false)
        speed.kmh(true, true, null, 50.0, gpsAsked = true)
        assertEquals(listOf(line, line), said)
    }

    @Test
    fun `with the GPS not asked it says nothing, as before`() {
        speed.kmh(true, true, null, 50.0)
        speed.kmh(true, true, null, 50.0, gpsAsked = false)
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun `with the switch off it says nothing even if the GPS is asked by the ring`() {
        assertEquals(40.0, speed.kmh(on = false, routeActive = true, gpsMps = null, routeKmh = 40.0, gpsAsked = true)!!, 0.0)
        assertNull(speed.kmh(on = false, routeActive = true, gpsMps = null, routeKmh = null, gpsAsked = true))
        assertEquals(emptyList<String>(), said)
    }
}
