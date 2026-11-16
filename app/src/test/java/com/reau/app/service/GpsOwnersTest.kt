package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two owners of the GPS (the info ring's card and the experimental speed profile, [GpsOwner]): it is on while any asks, one letting go
 * keeps it on for the other, and the end of the projection lets both go. The system is the same fake as [GpsWatcherTest]'s: it counts the
 * registrations made and given back.
 */
class GpsOwnersTest {
    private val watcher = GpsWatcher { 1_000L }
    private var registered = 0
    private var givenBack = 0

    private val start: () -> (() -> Unit)? = {
        registered++
        { givenBack++ }
    }

    private fun want(owner: GpsOwner, wanted: Boolean = true, permitted: Boolean = true) = watcher.want(owner, wanted, { permitted }, start)

    @Test
    fun `the ring and the speed profile together register once, and each ask again registers nothing`() {
        want(GpsOwner.RING)
        want(GpsOwner.SPEED)
        want(GpsOwner.SPEED)
        want(GpsOwner.RING)
        assertEquals(1, registered)
        assertEquals(0, givenBack)
        assertTrue(watcher.asking(GpsOwner.RING))
        assertTrue(watcher.asking(GpsOwner.SPEED))
    }

    @Test
    fun `the speed profile letting go keeps the GPS on for the ring`() {
        want(GpsOwner.RING)
        want(GpsOwner.SPEED)
        watcher.satellitesSeen(7, 19)
        want(GpsOwner.SPEED, wanted = false)
        assertEquals("the ring still asks", 0, givenBack)
        assertFalse(watcher.asking(GpsOwner.SPEED))
        assertTrue(watcher.asking(GpsOwner.RING))
        assertNotNull("and what it heard is kept", watcher.read())
        want(GpsOwner.RING, wanted = false)
        assertEquals("the last one letting go turns it off", 1, givenBack)
        assertNull(watcher.read())
    }

    @Test
    fun `the ring letting go keeps the GPS on for the speed profile`() {
        want(GpsOwner.SPEED)
        want(GpsOwner.RING)
        watcher.release(GpsOwner.RING)
        assertEquals(0, givenBack)
        assertTrue(watcher.asking(GpsOwner.SPEED))
        watcher.release(GpsOwner.SPEED)
        assertEquals(1, givenBack)
        assertEquals(1, registered)
    }

    @Test
    fun `the speed profile alone turns the GPS on and off`() {
        want(GpsOwner.SPEED)
        assertEquals(1, registered)
        want(GpsOwner.SPEED, wanted = false)
        assertEquals(1, givenBack)
        want(GpsOwner.SPEED)
        assertEquals("a new route registers again", 2, registered)
    }

    @Test
    fun `an owner that lets go twice, or that never asked, gives nothing back`() {
        watcher.release(GpsOwner.SPEED)
        assertEquals(0, givenBack)
        want(GpsOwner.RING)
        watcher.release(GpsOwner.SPEED)
        watcher.release(GpsOwner.SPEED)
        assertEquals("the ring alone still asks", 0, givenBack)
        watcher.release(GpsOwner.RING)
        watcher.release(GpsOwner.RING)
        assertEquals(1, givenBack)
    }

    @Test
    fun `the permission going away lets that owner go and keeps the other`() {
        want(GpsOwner.RING)
        want(GpsOwner.SPEED)
        want(GpsOwner.SPEED, permitted = false)
        assertEquals(0, givenBack)
        assertFalse(watcher.asking(GpsOwner.SPEED))
        want(GpsOwner.RING, permitted = false)
        assertEquals(1, givenBack)
    }

    @Test
    fun `the stop of the projection lets both owners go at once`() {
        want(GpsOwner.RING)
        want(GpsOwner.SPEED)
        watcher.satellitesSeen(7, 19)
        watcher.stop()
        assertEquals("one set of registrations, given back once", 1, givenBack)
        assertFalse(watcher.asking(GpsOwner.RING))
        assertFalse(watcher.asking(GpsOwner.SPEED))
        assertNull(watcher.read())
        want(GpsOwner.SPEED)
        assertEquals("the next projection registers again", 2, registered)
    }

    @Test
    fun `a start the system refuses keeps the owner asking and the next ask tries again`() {
        var refuse = true
        val flaky: () -> (() -> Unit)? = { if (refuse) null else start() }
        watcher.want(GpsOwner.SPEED, true, { true }, flaky)
        assertEquals(0, registered)
        refuse = false
        watcher.want(GpsOwner.SPEED, true, { true }, flaky)
        assertEquals(1, registered)
        watcher.release(GpsOwner.SPEED)
        assertEquals(1, givenBack)
    }

    @Test
    fun `the ask without an owner is the ring's`() {
        watcher.want(true, { true }, start)
        assertTrue(watcher.asking(GpsOwner.RING))
        assertFalse(watcher.asking(GpsOwner.SPEED))
        watcher.release(GpsOwner.SPEED)
        assertEquals(0, givenBack)
    }
}
