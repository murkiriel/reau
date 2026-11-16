package com.reau.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calls in [LiveSides] (which needs a device to run) that give the speed profile its ask for the GPS and take it back: kept in place here,
 * as [GpsStopsWithTheProjectionTest] keeps the ring's. The rules themselves run in [SpeedGpsTest] and [GpsOwnersTest].
 */
class SpeedGpsWiringTest {
    private val source = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()

    @Test
    fun `the speed profile's ask is made from the conditions of the settings and the route, with the permission checked`() {
        val step = source.substringAfter("speedGps.step(").substringBefore("val gpsSpeed =")
        assertTrue(step.contains("key = s?.experimentGpsSpeed == true"))
        assertTrue(step.contains("useLocation = s?.useLocation == true"))
        assertTrue(step.contains("speedProfile = s?.speedFpsProfile == true"))
        assertTrue(step.contains("routeActive = routeNow"))
        assertTrue(step.contains("Manifest.permission.ACCESS_FINE_LOCATION"))
        assertTrue(source.contains("val routeNow = g.active && g.distanceToManeuverMeters >= 0"))
    }

    @Test
    fun `the ask is the speed owner's`() {
        assertTrue(source.contains("SpeedGps(ask = { GpsWatch.want(appContext, GpsOwner.SPEED, it) }"))
    }

    @Test
    fun `the release of the projection ends the speed profile's ask before every owner lets go`() {
        val release = source.substringAfter("override fun release() {").substringBefore("override fun ", missingDelimiterValue = "")
        assertEquals(1, Regex("""\"speed gps\" to \{ speedGps\.stop\(\) \}""").findAll(release).count())
        assertTrue("the speed profile's ask ends first", release.indexOf("\"speed gps\"") in 0 until release.indexOf("\"gps\" to"))
    }

    @Test
    fun `the ring lets go only its own ask`() {
        assertEquals(1, Regex("""stopGps = \{ GpsWatch\.stop\(GpsOwner\.RING\) \}""").findAll(source).count())
        assertEquals("no other call gives up every owner but the projection's", 1, Regex("""GpsWatch\.stop\(\)""").findAll(source).count())
    }

    @Test
    fun `the info ring asks as the ring`() {
        val ring = File("src/main/java/com/reau/app/service/InfoRingFeed.kt").readText()
        assertTrue(ring.contains("GpsWatch.want(context, gpsWanted(options, useLocation))"))
    }

    @Test
    fun `the profile is told whether the switch has the GPS asked`() {
        assertTrue(source.contains("gpsAsked = speedGps.isAsking"))
    }
}
