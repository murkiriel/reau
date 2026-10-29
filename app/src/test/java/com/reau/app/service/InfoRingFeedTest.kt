package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** The info ring is on the video only with its switch on and while the dash's own maneuver ring is on the glass. */
class InfoRingFeedTest {
    @Test
    fun `it shows only with the switch on and the dash's ring shown`() {
        assertEquals(true, InfoRingFeed.shown(switchOn = true, dashRingVisible = true))
        assertEquals(false, InfoRingFeed.shown(switchOn = true, dashRingVisible = false))
        assertEquals(false, InfoRingFeed.shown(switchOn = false, dashRingVisible = true))
        assertEquals(false, InfoRingFeed.shown(switchOn = false, dashRingVisible = false))
    }

    private fun frame(level: Int = 1) = com.reau.app.weather.RingFrame(
        com.reau.app.weather.RingPage(
            com.reau.app.weather.RingKind.SKY, level, com.reau.app.weather.RingIcon.CLEAR, com.reau.app.weather.RingTone.NONE, emptyList(),
        ),
        stale = false, night = false,
    )

    @Test
    fun `the loop looks twenty times a second with the switch on and twice with it off`() {
        assertEquals(50L, InfoRingFeed.pollMs(switchOn = true))
        assertEquals("nothing can show the ring with the switch off", 500L, InfoRingFeed.pollMs(switchOn = false))
    }

    @Test
    fun `the compositor is told of a frame only when it changes`() {
        val posts = InfoRingFeed.Posts()
        assertEquals("the first one is always told, even none", true, posts.changed(null))
        assertEquals("none again", false, posts.changed(null))
        assertEquals(true, posts.changed(frame(1)))
        assertEquals("the same frame, twenty times a second", false, posts.changed(frame(1)))
        assertEquals(false, posts.changed(frame(1)))
        assertEquals("a different page", true, posts.changed(frame(2)))
        assertEquals("taken off", true, posts.changed(null))
        assertEquals("and put back", true, posts.changed(frame(2)))
    }

    @Test
    fun `the GPS is wanted only with its card ticked and the location switch on`() {
        val all = com.reau.app.weather.RingOptions()
        val noGps = com.reau.app.weather.RingOptions(data = com.reau.app.weather.RingData.ALL and com.reau.app.weather.RingData.GPS.bit.inv())
        assertEquals(true, InfoRingFeed.gpsWanted(all, useLocation = true))
        assertEquals("'Usar a localização' off", false, InfoRingFeed.gpsWanted(all, useLocation = false))
        assertEquals("the card unticked", false, InfoRingFeed.gpsWanted(noGps, useLocation = true))
        assertEquals(false, InfoRingFeed.gpsWanted(noGps, useLocation = false))
    }
}
