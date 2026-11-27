package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What is said, and when, of the display and the lock screen ([ScreenTrace]): each change once, the first look only seeds, and the cause comes from what was heard before. */
class ScreenTraceTest {
    private val lines = mutableListOf<String>()
    private val trace = ScreenTrace { lines += it }

    private fun reading(display: Int? = 2, interactive: Boolean? = true, locked: Boolean? = false, near: Boolean? = null, lock: Boolean = false) =
        LockLog.Reading(display, interactive, locked, true, near, lock)

    @Test
    fun `nothing is said before a source begins, and the first look only remembers`() {
        trace.observe(reading(display = 1), 0L)
        assertTrue(lines.isEmpty())
        assertTrue(trace.begin("keeper", 1_000L))
        trace.observe(reading(display = 1, locked = true), 1_000L)
        assertTrue("the first look says nothing (the snapshot says where things began)", lines.isEmpty())
    }

    @Test
    fun `each change of the display is one line with the interactive state, and the same state again says nothing`() {
        trace.begin("keeper", 0L)
        trace.observe(reading(display = 2), 0L)
        trace.observe(reading(display = 2), 1_000L)
        trace.observe(reading(display = 1, interactive = false), 2_000L)
        trace.observe(reading(display = 1, interactive = false), 3_000L)
        trace.observe(reading(display = 3, interactive = true), 4_000L)
        trace.observe(reading(display = 2), 5_000L)
        assertEquals(listOf("display state=OFF interactive=false", "display state=DOZE interactive=true", "display state=ON interactive=true"), lines)
    }

    @Test
    fun `the lock screen coming up after the screen went off is one line with its cause, and counts`() {
        trace.begin("keeper", 10_000L)
        trace.observe(reading(display = 2, locked = false), 10_000L)
        trace.observe(reading(display = 1, interactive = false, locked = false, near = false), 100_000L)
        lines.clear()
        trace.observe(reading(display = 1, interactive = false, locked = true, near = false), 105_000L)
        assertEquals(listOf("keyguard locked=true at=95s secure=true interactive=false display=OFF sensor=far lock=off cause=power-key-or-timeout"), lines)
        assertEquals(1L, trace.keyguardLocks)
        trace.observe(reading(display = 1, interactive = false, locked = true, near = false), 110_000L)
        assertEquals("the same state again says nothing", 1, lines.size)
    }

    @Test
    fun `the screen off heard by the broadcast is the cause even when the display reads on again`() {
        trace.begin("keeper", 0L)
        trace.observe(reading(locked = false), 0L)
        trace.screenOff(5_000L)
        trace.observe(reading(display = 2, interactive = true, locked = true, near = true, lock = true), 6_000L)
        assertEquals("keyguard locked=true at=6s secure=true interactive=true display=ON sensor=near lock=on cause=screen-off-sensor-near", lines.single())
    }

    @Test
    fun `a lock screen with nothing before it is an unknown cause`() {
        trace.begin("keeper", 0L)
        trace.observe(reading(locked = false), 0L)
        trace.observe(reading(locked = true), 1_000L)
        assertTrue(lines.single(), lines.single().endsWith("cause=unknown"))
    }

    @Test
    fun `the lock screen going away is the unlock that was just heard`() {
        trace.begin("keeper", 0L)
        trace.observe(reading(display = 1, interactive = false, locked = true), 0L)
        trace.userPresent(20_000L)
        trace.observe(reading(display = 2, interactive = true, locked = false), 20_500L)
        val unlocked = lines.single { it.startsWith("keyguard") }
        assertTrue(unlocked, unlocked.startsWith("keyguard locked=false at=20s ") && unlocked.endsWith("cause=user-present"))
        assertEquals("an unlock is not a lock", 0L, trace.keyguardLocks)
        lines.clear()
        trace.observe(reading(display = 1, interactive = false, locked = true), 30_000L)
        trace.dismissed(40_000L)
        trace.observe(reading(display = 2, interactive = true, locked = false), 40_300L)
        assertTrue(lines.last(), lines.last().endsWith("cause=dismissed"))
        assertEquals(2, lines.count { it.startsWith("keyguard") })
        assertEquals(1L, trace.keyguardLocks)
    }

    @Test
    fun `a part the phone would not say changes nothing`() {
        trace.begin("keeper", 0L)
        trace.observe(reading(locked = false), 0L)
        trace.observe(LockLog.Reading(null, null, null, null, null, false), 1_000L)
        trace.observe(reading(locked = false), 2_000L)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `the clock starts with the first source and a second source does not restart it`() {
        assertTrue(trace.begin("keeper", 1_000L))
        assertFalse(trace.begin("lock", 50_000L))
        assertEquals(60L, trace.seconds(61_000L))
        assertEquals(0L, trace.seconds(500L))
        assertTrue(trace.has("lock") && trace.has("keeper"))
        assertFalse("the lock goes, the keeper stays", trace.end("lock"))
        assertTrue(trace.active)
        assertTrue("the last one goes", trace.end("keeper"))
        assertFalse(trace.active)
        assertFalse("ending what is not on", trace.end("keeper"))
        assertTrue("a new watch starts the clock again", trace.begin("keeper", 90_000L))
        assertEquals(1L, trace.seconds(91_000L))
    }

    @Test
    fun `a new watch forgets the old one's state, so its first look says nothing`() {
        trace.begin("keeper", 0L)
        trace.observe(reading(display = 2, locked = false), 0L)
        trace.end("keeper")
        trace.begin("keeper", 5_000L)
        trace.observe(reading(display = 1, locked = true), 5_000L)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `the wakes are counted`() {
        trace.wake()
        trace.wake()
        assertEquals(2L, trace.wakes)
    }
}
