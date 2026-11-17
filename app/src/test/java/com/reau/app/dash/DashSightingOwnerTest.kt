package com.reau.app.dash

import com.reau.app.service.BackgroundRelease
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-20 of the independent review of 26/10/2026, the part [com.reau.app.service.StateOwner] does not cover: the service's `onDestroy` lets go of its sides on a
 * thread of its own (BackgroundRelease), and Android may build the next instance meanwhile. The sides of the old one cleared the process-wide
 * [DashSighting.listener] with a plain `= null` after the new one had taken it, and the new projection stayed deaf to the sighting of the dash (its
 * retry then waited out its whole time instead of going at once) until the process died. The listener now goes back only when it is still the releasing
 * instance's own.
 *
 * The order of the two instances is made by latches, not by time: the old release thread waits until the new instance has taken the listener.
 */
class DashSightingOwnerTest {
    @After
    fun clean() {
        DashSighting.listener = null
    }

    /** Runs [release] as the old instance's release does, on its own thread, but only after [newTookIt] has been counted down. */
    private fun lateRelease(newTookIt: CountDownLatch, release: () -> Unit): Thread =
        BackgroundRelease.run("test-release", { throw AssertionError("the release failed: $it") }) {
            assertTrue("the new instance took the listener", newTookIt.await(5, TimeUnit.SECONDS))
            release()
        }

    @Test
    fun `a late plain clear by the old instance leaves the new projection deaf (what it did)`() {
        var told = 0
        val old: () -> Unit = { }
        val next: () -> Unit = { told++ }
        DashSighting.listener = old
        val newTookIt = CountDownLatch(1)
        val releasing = lateRelease(newTookIt) { DashSighting.listener = null }
        DashSighting.listener = next
        newTookIt.countDown()
        releasing.join(5_000)
        DashSighting.sighted()
        assertEquals("the sighting reaches nobody", 0, told)
        assertNull(DashSighting.listener)
    }

    @Test
    fun `a late release by the old instance leaves the listener of the new one`() {
        var told = 0
        val old: () -> Unit = { }
        val next: () -> Unit = { told++ }
        DashSighting.listener = old
        val newTookIt = CountDownLatch(1)
        val releasing = lateRelease(newTookIt) { DashSighting.release(old) }
        DashSighting.listener = next
        newTookIt.countDown()
        releasing.join(5_000)
        DashSighting.sighted()
        assertEquals("the new projection hears the sighting", 1, told)
        assertSame(next, DashSighting.listener)
    }

    @Test
    fun `the release of the instance that still holds the listener clears it`() {
        var told = 0
        val only: () -> Unit = { told++ }
        DashSighting.listener = only
        DashSighting.release(only)
        DashSighting.sighted()
        assertEquals(0, told)
        assertNull(DashSighting.listener)
    }

    @Test
    fun `a release when nobody holds the listener does nothing`() {
        DashSighting.release { }
        assertNull(DashSighting.listener)
    }

    @Test
    fun `the sides take and give back the sighting through their own listener, never a plain clear`() {
        val sides = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertEquals("one place takes it, with the instance's own listener", 1, Regex("""DashSighting\.listener = """).findAll(sides).count())
        assertTrue(sides.contains("DashSighting.listener = sightingListener"))
        assertTrue(sides.contains("private val sightingListener: () -> Unit = { report(ProjectionEvent.DashSighted) }"))
        val release = sides.substringAfter("override fun release() {").substringBefore("if (failed > 0)")
        assertTrue("the release gives back only its own", release.contains("\"sighting\" to { com.reau.app.dash.DashSighting.release(sightingListener) }"))
        assertTrue("no plain clear in the release", !release.contains("DashSighting.listener = null"))
    }
}
