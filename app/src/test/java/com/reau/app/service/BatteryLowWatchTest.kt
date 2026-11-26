package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 35, step E (26/11/2026): the rider is told once per projection when the battery is at 10 % or under with no charger (the ride of 25/11/2026 went from 12 % to 4 %
 * in about 2 minutes at 3.0 V and the system closed REau twice). The rule is [BatteryLowWatch]; where it is fed from and what it shows are in BatteryLowIsWiredTest.
 */
class BatteryLowWatchTest {
    private fun BatteryLowWatch.run(vararg looks: Pair<Int?, Boolean?>) = looks.map { offer(it.first, it.second) }

    @Test
    fun `the limit is 10 percent`() {
        assertEquals(10, BatteryLowWatch.LOW_PCT)
    }

    @Test
    fun `it comes at the first look at 10 or under with no charger, and not before`() {
        val watch = BatteryLowWatch()
        assertEquals(listOf(false, false, true), watch.run(12 to false, 11 to false, 10 to false))
    }

    @Test
    fun `it comes once, however the percentage wobbles around the limit`() {
        val watch = BatteryLowWatch()
        assertEquals(listOf(true, false, false, false, false), watch.run(10 to false, 11 to false, 10 to false, 9 to false, 4 to false))
    }

    @Test
    fun `a projection that starts already under the limit is told at its first look`() {
        assertTrue(BatteryLowWatch().offer(7, false))
    }

    @Test
    fun `a new projection is a new watch, armed`() {
        val first = BatteryLowWatch()
        assertTrue(first.offer(9, false))
        assertFalse(first.offer(8, false))
        assertTrue(BatteryLowWatch().offer(9, false))
    }

    @Test
    fun `with the charger in it says nothing, and it is still armed for the charger going out`() {
        val watch = BatteryLowWatch()
        assertEquals(listOf(false, false, false), watch.run(5 to true, 4 to true, 3 to true))
        assertTrue("the charger out at 3 %", watch.offer(3, false))
    }

    @Test
    fun `an unknown look says nothing and changes nothing`() {
        val watch = BatteryLowWatch()
        assertEquals(listOf(false, false, false, false), watch.run(null to false, -1 to false, 5 to null, null to null))
        assertTrue("the first known look still comes", watch.offer(5, false))
    }

    @Test
    fun `11 percent is not low, 10 is, 0 is`() {
        assertFalse(BatteryLowWatch().offer(11, false))
        assertTrue(BatteryLowWatch().offer(10, false))
        assertTrue(BatteryLowWatch().offer(0, false))
    }

    @Test
    fun `a limit given to the watch is the one it uses`() {
        val watch = BatteryLowWatch(lowPct = 5)
        assertEquals(listOf(false, true), watch.run(6 to false, 5 to false))
    }

    @Test
    fun `the dash pill waits for the dash and is asked once`() {
        val w = BatteryLowWatch()
        assertFalse("nothing pending before the notice", w.pillDue(dashUp = true))
        assertTrue(w.offer(9, false))
        assertTrue(w.pillPending)
        assertFalse("no dash: it waits", w.pillDue(dashUp = false))
        assertTrue("still pending", w.pillPending)
        assertTrue("the dash is up: asked", w.pillDue(dashUp = true))
        assertFalse("only once", w.pillDue(dashUp = true))
        assertFalse(w.pillPending)
    }

    @Test
    fun `a projection resumed after the system closed REau does not tell again what the ride's mark says was told`() {
        val resumed = BatteryLowWatch(alreadyTold = true)
        assertEquals("the same low battery, the same ride: nothing", listOf(false, false, false), resumed.run(9 to false, 7 to false, 4 to false))
        assertFalse("and no pill is left waiting", resumed.pillPending)
        assertTrue("a ride that was not told is told at its first look", BatteryLowWatch(alreadyTold = false).offer(9, false))
    }
}
