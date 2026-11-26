package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 35, step E (26/11/2026): the rider is told once per projection when the battery is at 10 % or under with no charger (the ride of 25/11/2026 went from 12 % to 4 %
 * in about 2 minutes at 3.0 V and the system closed REau twice). The rule is [BatteryLowWatch]; where it is fed from and what it shows are in BatteryLowIsWiredTest.
 * The dash's pill is another decision of the same watch (the owner of 26/11/2026: it alternates, a minute on and a minute out, while the battery is low with no charger):
 * when it starts, is kept and stops, and why.
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

    // ── the dash's pill: it alternates for as long as the battery is low with no charger (the owner of 26/11/2026) ──

    private fun BatteryLowWatch.look(pct: Int?, charging: Boolean?, dashUp: Boolean = true): BatteryLowWatch.PillStep {
        offer(pct, charging)
        return pillStep(dashUp)
    }

    private val start = BatteryLowWatch.PillStep.START
    private val keep = BatteryLowWatch.PillStep.KEEP
    private val none = BatteryLowWatch.PillStep.NONE

    @Test
    fun `the pill starts at the look that is low with no charger, is kept while that holds, and says nothing before`() {
        val w = BatteryLowWatch()
        assertEquals(none, w.look(12, false))
        assertEquals(none, w.look(11, false))
        assertEquals(start, w.look(10, false))
        assertEquals(keep, w.look(9, false))
        assertEquals(keep, w.look(9, false))
        assertTrue(w.lowNow)
    }

    @Test
    fun `the pill follows the level, above the limit it stops, back under it starts again`() {
        val w = BatteryLowWatch()
        assertEquals(start, w.look(10, false))
        assertEquals("above 10: it stops", BatteryLowWatch.PillStep.STOP_LEVEL, w.look(11, false))
        assertEquals("under again: it starts again", start, w.look(10, false))
    }

    @Test
    fun `the pill stops when the charger comes in, and the reason is the charger`() {
        val w = BatteryLowWatch()
        w.look(8, false)
        val step = w.look(8, true)
        assertEquals(BatteryLowWatch.PillStep.STOP_CHARGER, step)
        assertTrue(step.stops)
        assertEquals("charger in", step.reason)
        assertEquals("nothing more while it stays in", none, w.look(7, true))
    }

    @Test
    fun `the pill stops when the battery goes above the limit, with the charger out`() {
        val w = BatteryLowWatch()
        w.look(9, false)
        assertEquals(BatteryLowWatch.PillStep.STOP_LEVEL, w.look(15, false))
        assertEquals(none, w.look(16, false))
        assertFalse(w.lowNow)
    }

    @Test
    fun `the charger and the level above together say the charger`() {
        val w = BatteryLowWatch()
        w.look(9, false)
        assertEquals(BatteryLowWatch.PillStep.STOP_CHARGER, w.look(30, true))
    }

    @Test
    fun `after a stop, a fall again with no charger starts it again, and the notice is not repeated`() {
        val w = BatteryLowWatch()
        assertTrue("the notice, once", w.offer(10, false))
        assertEquals(start, w.pillStep(true))
        assertFalse(w.offer(10, true))
        assertEquals(BatteryLowWatch.PillStep.STOP_CHARGER, w.pillStep(true))
        assertFalse("the charger out at 9 %: no second notice", w.offer(9, false))
        assertEquals("but the pill alternates again", start, w.pillStep(true))
        assertFalse(w.offer(20, false))
        assertEquals(BatteryLowWatch.PillStep.STOP_LEVEL, w.pillStep(true))
        assertFalse(w.offer(10, false))
        assertEquals(start, w.pillStep(true))
    }

    @Test
    fun `a look that does not know changes nothing for the pill`() {
        val w = BatteryLowWatch()
        w.look(9, false)
        assertEquals("a null level", keep, w.look(null, false))
        assertEquals("a negative level", keep, w.look(-1, false))
        assertEquals("an unknown charger", keep, w.look(9, null))
        assertEquals("nothing known at all", keep, w.look(null, null))
        assertEquals(BatteryLowWatch.PillStep.STOP_CHARGER, w.look(9, true))
        assertEquals("the charger is still in: an unknown look does not turn it low", none, w.look(null, null))
        assertEquals("an unknown level with the charger out is not known to be low", none, w.look(null, false))
    }

    @Test
    fun `a charger that is unknown with a level above the limit stops it, the level is known`() {
        val w = BatteryLowWatch()
        w.look(9, false)
        assertEquals(BatteryLowWatch.PillStep.STOP_LEVEL, w.look(50, null))
    }

    @Test
    fun `with no dash the pill waits, and starts when the dash is up, still low`() {
        val w = BatteryLowWatch()
        assertEquals(none, w.look(9, false, dashUp = false))
        assertEquals(none, w.look(8, false, dashUp = false))
        assertTrue(w.lowNow)
        assertEquals(start, w.look(8, false, dashUp = true))
    }

    @Test
    fun `the dash going stops the pill, and the dash coming back starts it again while still low`() {
        val w = BatteryLowWatch()
        assertEquals(start, w.look(9, false))
        val gone = w.look(9, false, dashUp = false)
        assertEquals(BatteryLowWatch.PillStep.STOP_DASH, gone)
        assertTrue(gone.stops)
        assertEquals(none, w.look(9, false, dashUp = false))
        assertEquals("the cycle starts again", start, w.look(9, false, dashUp = true))
    }

    @Test
    fun `the dash going and the battery coming up at the same look says the battery`() {
        val w = BatteryLowWatch()
        w.look(9, false)
        assertEquals(BatteryLowWatch.PillStep.STOP_LEVEL, w.look(20, false, dashUp = false))
    }

    @Test
    fun `the end of the projection takes the pill off if it was alternating, and says it only once`() {
        val w = BatteryLowWatch()
        assertEquals("not alternating: nothing to take off", none, w.pillEnds())
        w.look(9, false)
        val end = w.pillEnds()
        assertEquals(BatteryLowWatch.PillStep.STOP_END, end)
        assertEquals("projection over", end.reason)
        assertEquals(none, w.pillEnds())
    }

    @Test
    fun `only the starts and the keeps ask for the pill, only the stops take it off`() {
        for (step in BatteryLowWatch.PillStep.entries) {
            assertEquals(step.name, step == start || step == keep, step.asks)
            assertEquals(step.name, step.name.startsWith("STOP"), step.stops)
        }
    }

    @Test
    fun `a projection resumed after the system closed REau does not tell again what the ride's mark says was told`() {
        val resumed = BatteryLowWatch(alreadyTold = true)
        assertEquals("the same low battery, the same ride: nothing", listOf(false, false, false), resumed.run(9 to false, 7 to false, 4 to false))
        assertEquals("but the pill is not the notice: the rider must see it on the dash after the resume", start, resumed.pillStep(true))
        assertEquals("and it is kept, without a notice", keep, resumed.look(4, false))
        assertTrue("a ride that was not told is told at its first look", BatteryLowWatch(alreadyTold = false).offer(9, false))
    }
}
