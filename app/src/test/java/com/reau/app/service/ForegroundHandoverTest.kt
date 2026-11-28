package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cause of a network blocked in the background: the rider switched off and on within a second, the stop was still
 * being carried out (Android Auto's receiver takes a couple of seconds to stop) and its release took away the
 * foreground the new start had just taken. The projection went on as a plain service; in the background, with the
 * battery saver on, Android blocked its network (EPERM) and then killed it as "service (300)".
 */
class ForegroundHandoverTest {
    @Test
    fun `a stop with no start after it lets the foreground go`() {
        assertTrue(ForegroundHandover.dropsForeground(stopCommand = 5, lastStart = 4))
    }

    @Test
    fun `a start that came after the stop keeps the foreground`() {
        assertFalse(ForegroundHandover.dropsForeground(stopCommand = 5, lastStart = 6))
    }

    @Test
    fun `a stop that lets the foreground go ends the service with the latest command, a mark or a refit that came after it included`() {
        assertEquals("a mark after the Desligar: its id, or the service stays started and idle", 7, ForegroundHandover.stopId(stopCommand = 5, lastStart = 4, latestCommand = 7))
        assertEquals("nothing after the stop: its own id", 5, ForegroundHandover.stopId(stopCommand = 5, lastStart = 4, latestCommand = 5))
        assertEquals("never an older id than the stop's own", 5, ForegroundHandover.stopId(stopCommand = 5, lastStart = 4, latestCommand = 3))
    }

    @Test
    fun `a stop that a start came after ends with its own id, as before, the start decides`() {
        assertEquals(5, ForegroundHandover.stopId(stopCommand = 5, lastStart = 6, latestCommand = 8))
    }

    @Test
    fun `the importance of the process in words`() {
        assertEquals("125 (foreground service)", ForegroundHandover.importance(125))
        assertEquals("300 (service)", ForegroundHandover.importance(300))
        assertEquals("100 (foreground)", ForegroundHandover.importance(100))
        assertEquals("400 (cached)", ForegroundHandover.importance(400))
        assertEquals("230 (perceptible)", ForegroundHandover.importance(230))
        assertEquals("200 (visible)", ForegroundHandover.importance(200))
        assertEquals("777", ForegroundHandover.importance(777))
    }

    @Test
    fun `how the process stands when the app goes to the background`() {
        assertEquals(
            "process importance 125 (foreground service), projection in the foreground",
            ForegroundHandover.standing(importance = 125, projectionForeground = true, projectionOn = true),
        )
        assertEquals(
            "process importance 300 (service), projection NOT in the foreground: its network may be blocked",
            ForegroundHandover.standing(importance = 300, projectionForeground = false, projectionOn = true),
        )
        assertEquals(
            "process importance 400 (cached), projection off",
            ForegroundHandover.standing(importance = 400, projectionForeground = false, projectionOn = false),
        )
    }

    @Test
    fun `a Ligar or a restart does not become the id a Desligar stops the service with`() {
        assertEquals("a mark: its own id", 9, ForegroundHandover.latestForStop(latest = 7, startId = 9, takesForeground = false))
        assertEquals("a Ligar: the id found before it", 7, ForegroundHandover.latestForStop(latest = 7, startId = 9, takesForeground = true))
        // Desligar (5) released while the Ligar (6) is still taking the foreground (lastStart still 4): the stop must keep its own id, never the Ligar's.
        val latest = ForegroundHandover.latestForStop(latest = 5, startId = 6, takesForeground = true)
        assertEquals(5, ForegroundHandover.stopId(stopCommand = 5, lastStart = 4, latestCommand = latest))
    }
}
