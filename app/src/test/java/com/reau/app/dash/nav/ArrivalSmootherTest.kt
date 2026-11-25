package com.reau.app.dash.nav

import com.reau.app.dash.protocol.DashCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The time left and the arrival time on the dash no longer flicker by one minute (pendency 37, ride of 25/11/2026): Android Auto's
 * minute-rounded figures went 7, 8 for a second, 7 and 06:59, 07:00 (for 15 s of every minute), 06:59 on the dash.
 */
class ArrivalSmootherTest {
    private val smoother = ArrivalSmoother()
    private val minute = 60L

    /** A second of the ride's log: [from] to [to] seconds after 06:51:25, with the arrival text and the minutes the dash was sent. */
    private data class Run(val from: Int, val to: Int, val eta: String, val minutes: Int)

    /** What the dash was sent from 06:51:25 to 06:53:29 (the 0508 and 050B of the ride log), run by run; second 51 has no sample. */
    private val ride = listOf(
        Run(0, 12, "0700", 8),
        Run(13, 22, "0659", 7),
        Run(23, 23, "0659", 8),
        Run(24, 38, "0659", 7),
        Run(39, 47, "0700", 7),
        Run(48, 48, "0700", 8),
        Run(49, 50, "0700", 7),
        Run(52, 53, "0700", 7),
        Run(54, 98, "0659", 6),
        Run(99, 108, "0700", 6),
        Run(109, 109, "0700", 7),
        Run(110, 113, "0700", 6),
        Run(114, 118, "0659", 5),
        Run(119, 119, "0659", 6),
        Run(120, 124, "0659", 5),
    )

    private data class Sample(val second: Int, val eta: String, val minutes: Int)

    private val samples = ride.flatMap { run -> (run.from..run.to).map { Sample(it, run.eta, run.minutes) } }

    private fun ms(second: Int) = 10_000L + second * 1_000L

    private fun rises(values: List<Int>) = values.zipWithNext().count { (a, b) -> b > a }

    @Test
    fun `the ride of 25-11 flickered as the owner saw it`() {
        // The premise of the next test: what went to the dash had the up-and-down of one minute, in both figures.
        assertEquals("the time left went up by one minute 4 times", 4, rises(samples.map { it.minutes }))
        assertTrue("the arrival time changed 5 times", samples.zipWithNext().count { (a, b) -> a.eta != b.eta } == 5)
    }

    @Test
    fun `the same ride goes down a minute at a time with the arrival time still`() {
        val shownMinutes = mutableListOf<Int>()
        val shownEta = mutableListOf<String?>()
        for (s in samples) {
            // A held time comes as "HH:MM", a shown one as it was said ("0700" here): the dash reads the same digits of either.
            shownEta += smoother.arrival(s.eta, ms(s.second))?.replace(":", "")
            shownMinutes += (smoother.remaining(s.minutes * minute, ms(s.second)) / minute).toInt()
        }
        assertEquals("never up: $shownMinutes", 0, rises(shownMinutes))
        assertEquals("7 comes the moment 7 is said, and so on down", listOf(8, 7, 6, 5), shownMinutes.distinct())
        assertEquals("the arrival time does not change in two minutes: $shownEta", listOf("0700"), shownEta.distinct())
    }

    @Test
    fun `a minute less is shown at once, every minute of a normal route`() {
        for (left in 10 downTo 1) assertEquals(left * minute, smoother.remaining(left * minute, ms((10 - left) * 60)))
    }

    @Test
    fun `one minute up for a moment is not shown`() {
        assertEquals(7 * minute + 59, smoother.remaining(7 * minute + 59, ms(0)))
        assertEquals("8 min for a second stays 7", 7 * minute, smoother.remaining(8 * minute, ms(1)))
        assertEquals("8 min and 59 s is the same minute", 7 * minute, smoother.remaining(8 * minute + 59, ms(2)))
        assertEquals(7 * minute, smoother.remaining(7 * minute, ms(3)))
    }

    @Test
    fun `one minute up that lasts 30 seconds is shown, not before`() {
        smoother.remaining(7 * minute, ms(0))
        assertEquals(7 * minute, smoother.remaining(8 * minute, ms(1)))
        assertEquals("29.999 s later", 7 * minute, smoother.remaining(8 * minute, ms(1) + ArrivalSmoother.REMAINING_HOLD_MS - 1))
        assertEquals("30 s later", 8 * minute, smoother.remaining(8 * minute, ms(1) + ArrivalSmoother.REMAINING_HOLD_MS))
        assertEquals("and 7 goes back down at once", 7 * minute, smoother.remaining(7 * minute, ms(40)))
    }

    @Test
    fun `a rise that stopped does not count its time when it starts again`() {
        smoother.remaining(7 * minute, ms(0))
        smoother.remaining(8 * minute, ms(1))
        smoother.remaining(7 * minute, ms(2))
        assertEquals("a new rise, 20 s after the first one began", 7 * minute, smoother.remaining(8 * minute, ms(21)))
        assertEquals("31 s after the first one, 10 s after this one", 7 * minute, smoother.remaining(8 * minute, ms(31)))
    }

    @Test
    fun `a rise of two minutes or more is a recalculation and is shown at once`() {
        smoother.remaining(7 * minute, ms(0))
        assertEquals(9 * minute, smoother.remaining(9 * minute, ms(1)))
        assertEquals(12 * minute + 30, smoother.remaining(12 * minute + 30, ms(2)))
    }

    @Test
    fun `the seconds go through as they came when the minute is shown`() {
        assertEquals(1_501L, smoother.remaining(1_501L, ms(0)))
        assertEquals(1_501L, smoother.remaining(1_501L, ms(1)))
        assertEquals(1_480L, smoother.remaining(1_480L, ms(2)))
    }

    @Test
    fun `an unknown time left is left unknown and does not disturb what was shown`() {
        smoother.remaining(7 * minute, ms(0))
        assertEquals(DashCommands.REMAINING_OMIT, smoother.remaining(DashCommands.REMAINING_OMIT, ms(1)))
        assertEquals("still 7 after the unknown: one more is held", 7 * minute, smoother.remaining(8 * minute, ms(2)))
        assertEquals(6 * minute, smoother.remaining(6 * minute, ms(3)))
    }

    @Test
    fun `the arrival time that is the same or went by a minute for less than 75 seconds stays`() {
        assertEquals("06:59", smoother.arrival("06:59", ms(0)))
        assertEquals("06:59", smoother.arrival("06:59", ms(1)))
        assertEquals("one minute later for 15 s: 06:59 goes", "06:59", smoother.arrival("07:00", ms(2)))
        assertEquals("06:59", smoother.arrival("07:00", ms(16)))
        assertEquals("06:59", smoother.arrival("06:59", ms(17)))
    }

    @Test
    fun `the arrival time that went by a minute and stayed is shown after 75 seconds, as it was said`() {
        smoother.arrival("06:59", ms(0))
        assertEquals("06:59", smoother.arrival("07:00", ms(1)))
        assertEquals("74.999 s", "06:59", smoother.arrival("07:00", ms(1) + ArrivalSmoother.ARRIVAL_HOLD_MS - 1))
        assertEquals("75 s", "07:00", smoother.arrival("07:00", ms(1) + ArrivalSmoother.ARRIVAL_HOLD_MS))
        assertEquals("it is the new one now", "07:00", smoother.arrival("07:00", ms(100)))
    }

    @Test
    fun `an arrival time that went back and forth does not add up its short stays`() {
        smoother.arrival("06:59", ms(0))
        for (cycle in 0 until 4) {
            val start = 10 + cycle * 60
            assertEquals("06:59", smoother.arrival("07:00", ms(start)))
            assertEquals("06:59", smoother.arrival("07:00", ms(start + 40)))
            assertEquals("06:59", smoother.arrival("06:59", ms(start + 41)))
        }
    }

    @Test
    fun `an arrival time two minutes away is a real change and shown at once, either way`() {
        smoother.arrival("06:59", ms(0))
        assertEquals("07:01", smoother.arrival("07:01", ms(1)))
        assertEquals("06:58", smoother.arrival("06:58", ms(2)))
        assertEquals("with the 12-hour text as it was said", "7:30 PM", smoother.arrival("7:30 PM", ms(3)))
    }

    @Test
    fun `the arrival time goes round midnight by the shorter way`() {
        smoother.arrival("23:59", ms(0))
        assertEquals("a minute across midnight is a minute: held", "23:59", smoother.arrival("00:00", ms(1)))
        assertEquals("two minutes across midnight are two: shown", "00:01", smoother.arrival("00:01", ms(2)))
    }

    @Test
    fun `a text that is no time goes as it is and changes nothing`() {
        smoother.arrival("06:59", ms(0))
        assertEquals("", smoother.arrival("", ms(1)))
        assertEquals(null, smoother.arrival(null, ms(2)))
        assertEquals("--", smoother.arrival("--", ms(3)))
        assertEquals("06:59", smoother.arrival("07:00", ms(4)))
    }

    @Test
    fun `a new guidance starts from its own figures`() {
        smoother.remaining(7 * minute, ms(0))
        smoother.remaining(8 * minute, ms(1))
        smoother.arrival("06:59", ms(0))
        smoother.arrival("07:00", ms(1))
        smoother.reset()
        assertEquals("one minute more is the first value now", 8 * minute, smoother.remaining(8 * minute, ms(2)))
        assertEquals("07:00", smoother.arrival("07:00", ms(2)))
    }
}
