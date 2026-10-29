package com.reau.app.service

import com.reau.app.weather.GpsReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The GPS of the dash's info ring (A-02 of the independent evaluation: it stayed on after "Desligar" until the process died), with the
 * system replaced by a fake that counts the registrations it is given and takes back.
 */
class GpsWatcherTest {
    private var now = 1_000L
    private val watcher = GpsWatcher { now }
    private var registered = 0
    private var givenBack = 0
    private var refuse = false

    /** What [GpsWatch] gives: makes the registrations, returns how to take them back, or null when the system refuses. */
    private val start: () -> (() -> Unit)? = {
        if (refuse) {
            null
        } else {
            registered++
            { givenBack++ }
        }
    }

    private fun want(wanted: Boolean = true, permitted: Boolean = true) = watcher.want(wanted, { permitted }, start)

    private fun hear(used: Int = 7, visible: Int = 19) = watcher.satellitesSeen(used, visible)

    @Test
    fun `it listens when the ring wants the GPS and the permission is there, and asking again registers nothing more`() {
        want()
        want()
        want()
        assertEquals("one registration for any number of asks", 1, registered)
        assertEquals(0, givenBack)
    }

    @Test
    fun `the GPS is off after the stop of the projection, and what it heard is forgotten`() {
        want()
        hear()
        watcher.fixSeen(5f, 90f, 12f)
        assertEquals(GpsReading(7, 19, 5f, 90f, 12f), watcher.read())
        watcher.stop()
        assertEquals("the registrations were given back: the GPS is off", 1, givenBack)
        assertNull("nothing is read from a stopped GPS", watcher.read())
        want()
        assertEquals("the next projection registers again", 2, registered)
        assertNull("and starts from a clean sky, not from the last ride's fix", watcher.read())
        hear(3, 10)
        assertEquals(GpsReading(3, 10, null, null, null), watcher.read())
    }

    @Test
    fun `stopping twice, or before it ever listened, gives nothing back twice`() {
        watcher.stop()
        assertEquals(0, givenBack)
        want()
        watcher.stop()
        watcher.stop()
        assertEquals(1, givenBack)
    }

    @Test
    fun `the card or the switch going away turns the GPS off`() {
        want()
        want(wanted = false)
        assertEquals("not wanted any more", 1, givenBack)
        want()
        want(wanted = true, permitted = false)
        assertEquals("wanted but the permission went", 2, givenBack)
        want(wanted = true, permitted = false)
        assertEquals("a refusal again changes nothing", 2, givenBack)
        assertEquals("and nothing was registered without the permission", 2, registered)
    }

    @Test
    fun `without the permission it never starts`() {
        want(permitted = false)
        assertEquals(0, registered)
        assertNull(watcher.read())
    }

    @Test
    fun `a start the system refuses is no listening, and the next ask tries again`() {
        refuse = true
        want()
        assertEquals(0, registered)
        hear()
        assertNull("not listening: no reading", watcher.read())
        watcher.stop()
        assertEquals("nothing was taken, nothing is given back", 0, givenBack)
        refuse = false
        want()
        assertEquals(1, registered)
    }

    @Test
    fun `a reading needs satellites that are fresh, and the fix is dropped from it when it is old`() {
        want()
        assertNull("no satellite said yet", watcher.read())
        hear(8, 20)
        watcher.fixSeen(4f, 10f, 3f)
        assertEquals(GpsReading(8, 20, 4f, 10f, 3f), watcher.read(now + GpsWatcher.FRESH_MS))
        assertNull("the sky is old", watcher.read(now + GpsWatcher.FRESH_MS + 1))
        now += 6_000
        hear(9, 21)
        assertEquals("fresh sky, fix of 6 s", GpsReading(9, 21, 4f, 10f, 3f), watcher.read(now))
        assertEquals("fix right at the limit", GpsReading(9, 21, 4f, 10f, 3f), watcher.read(now + 4_000))
        assertEquals("the fix is older than the limit, the sky is not", GpsReading(9, 21, null, null, null), watcher.read(now + 4_001))
    }

    @Test
    fun `a fix with no accuracy, heading or speed says so`() {
        want()
        hear()
        watcher.fixSeen(null, null, null)
        assertEquals(GpsReading(7, 19, null, null, null), watcher.read())
    }

    @Test
    fun `the clock it is given dates what it hears`() {
        want()
        hear()
        now += GpsWatcher.FRESH_MS + 1
        assertNull(watcher.read())
        hear()
        assertEquals(GpsReading(7, 19, null, null, null), watcher.read())
    }
}
