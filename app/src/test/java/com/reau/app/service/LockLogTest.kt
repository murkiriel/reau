package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The state lines of the 0.3.99 log ([LockLog]): their exact text, and the cause deduced for the lock screen coming up or going away. */
class LockLogTest {
    private fun reading(
        display: Int? = 1,
        interactive: Boolean? = false,
        locked: Boolean? = true,
        secure: Boolean? = true,
        near: Boolean? = false,
        lock: Boolean = true,
    ) = LockLog.Reading(display, interactive, locked, secure, near, lock)

    @Test
    fun `the display line says the state by its name and the interactive state of the same instant`() {
        assertEquals("display state=OFF interactive=false", LockLog.displayLine(1, false))
        assertEquals("display state=DOZE_SUSPEND interactive=true", LockLog.displayLine(4, true))
        assertEquals("display state=ON_SUSPEND interactive=?", LockLog.displayLine(6, null))
        assertEquals("display state=state9 interactive=true", LockLog.displayLine(9, true))
    }

    @Test
    fun `the keyguard line says when, what the phone is and why, in one line of key=value`() {
        assertEquals(
            "keyguard locked=true at=312s secure=true interactive=false display=OFF sensor=far lock=on cause=power-key-or-timeout",
            LockLog.keyguardLine(true, 312, reading(), LockLog.CAUSE_POWER_OR_TIMEOUT),
        )
        assertEquals(
            "keyguard locked=false at=40s secure=? interactive=true display=ON sensor=unknown lock=off cause=user-present",
            LockLog.keyguardLine(false, 40, reading(display = 2, interactive = true, secure = null, near = null, lock = false), LockLog.CAUSE_USER_PRESENT),
        )
        assertEquals("a display that is not known", "display=?", Regex("""display=\S+""").find(LockLog.keyguardLine(true, 0, reading(display = null), "x"))!!.value)
    }

    @Test
    fun `a lock after the screen went off with the sensor not covered is the power button or the timeout`() {
        assertEquals(LockLog.CAUSE_POWER_OR_TIMEOUT, LockLog.keyguardCause(true, reading(near = false), 3_000L, null, null))
        assertEquals("the sensor not known is not a covered one", LockLog.CAUSE_POWER_OR_TIMEOUT, LockLog.keyguardCause(true, reading(near = null), 3_000L, null, null))
        assertEquals("the phone not interactive is a real sleep, the display read aside", LockLog.CAUSE_POWER_OR_TIMEOUT, LockLog.keyguardCause(true, reading(display = 2, interactive = false), null, null, null))
    }

    @Test
    fun `a lock with the sensor covered is said so, and a lock with nothing before it is unknown`() {
        assertEquals(LockLog.CAUSE_SENSOR_NEAR, LockLog.keyguardCause(true, reading(near = true), 1_000L, null, null))
        assertEquals("nothing says the screen was off", LockLog.CAUSE_UNKNOWN, LockLog.keyguardCause(true, reading(display = 2, interactive = true), null, null, null))
        assertEquals("a screen-off heard long ago is not the cause", LockLog.CAUSE_UNKNOWN, LockLog.keyguardCause(true, reading(display = 2, interactive = true), LockLog.CAUSE_WINDOW_MS + 1, null, null))
        assertEquals("the very edge counts", LockLog.CAUSE_POWER_OR_TIMEOUT, LockLog.keyguardCause(true, reading(display = 2, interactive = true), LockLog.CAUSE_WINDOW_MS, null, null))
    }

    @Test
    fun `the lock screen going away is the unlock or the dismiss that was just heard`() {
        val on = reading(display = 2, interactive = true, locked = false)
        assertEquals(LockLog.CAUSE_USER_PRESENT, LockLog.keyguardCause(false, on, null, 800L, null))
        assertEquals(LockLog.CAUSE_USER_PRESENT, LockLog.keyguardCause(false, on, null, LockLog.UNLOCK_WINDOW_MS, 100L))
        assertEquals(LockLog.CAUSE_DISMISSED, LockLog.keyguardCause(false, on, null, null, 2_000L))
        assertEquals("too old", LockLog.CAUSE_UNKNOWN, LockLog.keyguardCause(false, on, null, LockLog.UNLOCK_WINDOW_MS + 1, LockLog.UNLOCK_WINDOW_MS + 1))
        assertEquals(LockLog.CAUSE_UNKNOWN, LockLog.keyguardCause(false, on, null, null, null))
    }

    private fun snapshot(window: LockLog.WindowReading? = LockLog.WindowReading(0.01f, true), battery: LockLog.BatteryReading? = LockLog.BatteryReading(87, false, 31.5f)) =
        LockLog.Snapshot(312, reading(display = 2, interactive = true, locked = false, near = false), window, true, "running", "ready", battery)

    @Test
    fun `the snapshot has everything the owner asked for, in one line`() {
        assertEquals(
            "snapshot at=312s display=ON interactive=true keyguard=false secure=true lock=on brightness=0.01 keep-on=true prox-held=true sensor=far " +
                "phase=running dash=ready battery=87% charging=false temp=31.5C",
            LockLog.snapshotLine(snapshot()),
        )
    }

    @Test
    fun `a part the snapshot does not know is a dash, and the window's own brightness is default`() {
        val line = LockLog.snapshotLine(snapshot(window = null, battery = null))
        assertTrue(line, "brightness=- keep-on=- " in line && "battery=- charging=? temp=-" in line)
        assertTrue(LockLog.snapshotLine(snapshot(window = LockLog.WindowReading(-1f, false))).contains("brightness=default keep-on=false"))
        assertTrue(LockLog.snapshotLine(snapshot(battery = LockLog.BatteryReading(null, true, null))).endsWith("battery=- charging=true temp=-"))
        val unread = LockLog.Snapshot(0, LockLog.Reading(null, null, null, null, null, false), null, false, "stopped", "off", null)
        assertEquals(
            "snapshot at=0s display=? interactive=? keyguard=? secure=? lock=off brightness=- keep-on=- prox-held=false sensor=unknown phase=stopped dash=off battery=- charging=? temp=-",
            LockLog.snapshotLine(unread),
        )
    }

    @Test
    fun `the battery comes from the sticky broadcast's numbers, and what it did not have is null`() {
        val battery = LockLog.BatteryReading.from(level = 87, scale = 100, plugged = 0, tempTenths = 315)
        assertEquals(87, battery.percent)
        assertEquals(false, battery.charging)
        assertEquals(31.5f, battery.tempC!!, 0.001f)
        assertEquals("a scale that is not 100", 50, LockLog.BatteryReading.from(5, 10, 2, 200).percent)
        assertEquals("a charger of any kind", true, LockLog.BatteryReading.from(5, 10, 2, 200).charging)
        val none = LockLog.BatteryReading.from(level = -1, scale = -1, plugged = -1, tempTenths = Int.MIN_VALUE)
        assertNull(none.percent)
        assertNull(none.charging)
        assertNull(none.tempC)
        assertNull("no scale", LockLog.BatteryReading.from(50, 0, 0, 0).percent)
    }

    @Test
    fun `a failure says what failed and the exception's name only, as a warning`() {
        assertEquals("W: wake lock acquire failed: SecurityException", LockLog.warnLine("wake lock acquire", SecurityException("the message has the owner's secrets")))
        assertEquals("W: keyguard manager unavailable", LockLog.unavailableLine("keyguard manager"))
    }

    @Test
    fun `the dismiss of the lock screen is said at each step, and only an error is a warning`() {
        assertEquals("lockui dismiss asked", LockLog.dismissLine(LockLog.DISMISS_ASKED))
        assertEquals("lockui dismiss succeeded", LockLog.dismissLine(LockLog.DISMISS_SUCCEEDED))
        assertEquals("lockui dismiss cancelled", LockLog.dismissLine(LockLog.DISMISS_CANCELLED))
        assertEquals("W: lockui dismiss error", LockLog.dismissLine(LockLog.DISMISS_ERROR))
        assertFalse(LockLog.dismissLine(LockLog.DISMISS_CANCELLED).startsWith("W: "))
    }

    @Test
    fun `the lines sit under their own tag and the snapshot is every minute`() {
        assertEquals("screen-watch", LockLog.TAG)
        assertEquals(60_000L, LockLog.SNAPSHOT_MS)
    }
}
