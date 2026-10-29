package com.reau.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calls that turn the GPS off live in [LiveSides], which needs a device to run; this keeps them in place (26/10/2026 review, A-02: the
 * GPS stayed on after "Desligar" until the process died; the first step of the fix is [GpsWatcherTest]'s, these are the callers).
 */
class GpsStopsWithTheProjectionTest {
    private val source = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()

    @Test
    fun `the release of the projection gives the GPS back`() {
        val release = source.substringAfter("override fun release() {").substringBefore("override fun ", missingDelimiterValue = "")
        assertTrue("release() is not where it was", release.contains("ReleaseSteps.runAll"))
        assertEquals("one step stops the GPS, named", 1, Regex("""\"gps\" to \{ GpsWatch\.stop\(\) \}""").findAll(release).count())
    }

    @Test
    fun `the ring's loop stops the GPS when the ring is not on the video`() {
        val loop = source.substringAfter("val on = started && InfoRingFeed.shown(").substringBefore("delay(InfoRingFeed.pollMs")
        val off = loop.substringAfter("if (!on) {").substringBefore("} else if")
        assertTrue("the branch of a ring that is off must stop the GPS: $off", off.contains("GpsWatch.stop()"))
    }
}
