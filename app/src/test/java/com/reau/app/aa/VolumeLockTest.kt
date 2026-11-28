package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.dash.VolumeCards
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.phone.CallState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joystick's volume lock ([VolumeLock], P32): after a tap of a volume action only the volume actions are taken for 5 s, any other tap is dropped with a line
 * of the ride log and does not renew it, every volume tap renews it, the volume card ends with it, and a call, or the switch off, always takes the joystick
 * back. The wiring in the live sides is read from the source (the last test).
 */
class VolumeLockTest {
    private val page = JoystickBindings.DEFAULT
        .with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), Action.VOLUME_UP)
        .with(Slot(Mode.MEDIA, Control.DOWN, Gesture.ONCE), Action.VOLUME_DOWN)
    private val volumeUp = DashButtonEvent.VolumeUp(0x06)
    private val volumeDown = DashButtonEvent.VolumeDown(0x07)
    private val mapBack = DashButtonEvent.JoystickBack(0x12)
    private val mapZoomIn = DashButtonEvent.ZoomIn(0x14)
    private val mapDown = DashButtonEvent.Unknown(0x15)
    private val mapCenter = DashButtonEvent.JoystickIn(0x18)
    private val nextTrack = DashButtonEvent.NextTrack(0x09)
    private val callLeft = DashButtonEvent.Unknown(0x02)
    private var call = CallState.IDLE

    private fun map(enabled: Boolean = true) = JoystickMap(page, callState = { call }).also { it.volumeLock.enabled = enabled }

    @Test
    fun `the lock is five seconds, in one place`() {
        assertEquals(5_000L, VolumeLock.VOLUME_LOCK_MS)
        val lock = VolumeLock().apply { enabled = true }
        lock.renew(1_000)
        assertEquals(6_000L, lock.untilMs)
        assertEquals(5_000L, lock.leftMs(1_000))
    }

    @Test
    fun `a volume tap locks the joystick, and any other tap is dropped with nothing sent, no echo, no legend and a line of the log`() {
        val joystick = map()
        assertEquals(listOf<AaCommand>(AaCommand.Volume(1)), joystick.press(volumeUp, 0).sent)
        assertEquals(5_000L, joystick.volumeLock.leftMs(0))
        val back = joystick.press(mapBack, 2_300)
        assertEquals(emptyList<AaCommand>(), back.sent)
        assertEquals(listOf("volume lock: map up ignored (2.7 s left)"), back.lines)
        assertNull(back.echo)
        assertNull(back.legend)
        for ((event, name) in listOf(mapZoomIn to "map left", mapCenter to "map center", nextTrack to "media right")) {
            val dropped = joystick.press(event, 2_400)
            assertEquals(name, emptyList<AaCommand>(), dropped.sent)
            assertTrue(dropped.lines.toString(), dropped.lines.single().startsWith("volume lock: $name ignored ("))
            assertNull(dropped.echo)
        }
        assertEquals("a code that is no button too", listOf("volume lock: code 0x21 ignored (2.5 s left)"), joystick.press(DashButtonEvent.Unknown(0x21), 2_500).lines)
    }

    @Test
    fun `without the lock the same taps go through (the map's left sends its step)`() {
        assertTrue(map().press(mapZoomIn, 0).sent.isNotEmpty())
    }

    @Test
    fun `a dropped tap does not renew the lock, so it ends five seconds after the last volume tap`() {
        val joystick = map()
        joystick.press(volumeUp, 0)
        assertTrue(joystick.press(mapZoomIn, 4_999).sent.isEmpty())
        assertEquals("the dropped tap did not move the end", 1L, joystick.volumeLock.leftMs(4_999))
        assertTrue("five seconds after the volume tap the joystick is the page's again", joystick.press(mapZoomIn, 5_000).sent.isNotEmpty())
    }

    @Test
    fun `each volume tap, up or down, renews the five seconds`() {
        val joystick = map()
        joystick.press(volumeUp, 0)
        assertEquals(listOf<AaCommand>(AaCommand.Volume(-1)), joystick.press(volumeDown, 4_000).sent)
        assertEquals(5_000L, joystick.volumeLock.leftMs(4_000))
        assertTrue("still locked at 8.9 s", joystick.press(mapZoomIn, 8_900).sent.isEmpty())
        assertEquals(listOf<AaCommand>(AaCommand.Volume(1)), joystick.press(volumeUp, 8_950).sent)
        assertTrue("and at 13.9 s", joystick.press(mapZoomIn, 13_900).sent.isEmpty())
        assertTrue("free at 13.95 s", joystick.press(mapZoomIn, 13_950).sent.isNotEmpty())
    }

    @Test
    fun `the card ends exactly when the lock does, and a step from outside never shortens it nor takes less than two seconds`() {
        val joystick = map()
        joystick.press(volumeUp, 1_000)
        val until = joystick.volumeLock.untilMs
        // The step of the joystick reaches the card a few milliseconds later: its time is what is left of the lock, so card and lock end at the same instant.
        for (shownAt in listOf(1_000L, 1_020L, 1_300L)) {
            assertEquals(until, shownAt + VolumeCards.showMs(joystick.volumeLock.leftMs(shownAt)))
        }
        assertEquals("a step from outside in the middle of the lock keeps the card until the lock's end", until, 3_000L + VolumeCards.showMs(joystick.volumeLock.leftMs(3_000)))
        assertEquals("late in the lock it still has its two seconds", 5_800L + 2_000L, 5_800L + VolumeCards.showMs(joystick.volumeLock.leftMs(5_800)))
        assertEquals("with no lock, two seconds", 2_000L, VolumeCards.showMs(0))
        assertEquals(2_000L, VolumeCards.showMs(VolumeLock().leftMs(10)))
    }

    @Test
    fun `a volume step from outside (the phone's buttons, an intercom) does not lock the joystick`() {
        val joystick = map()
        // Nothing of the joystick: the broadcast only shows a card ([VolumeCards.showMs] with no lock), it never renews the lock.
        assertEquals(0L, joystick.volumeLock.leftMs(100))
        assertEquals(2_000L, VolumeCards.showMs(joystick.volumeLock.leftMs(100)))
        assertTrue(joystick.press(mapZoomIn, 100).sent.isNotEmpty())
    }

    @Test
    fun `a call ringing during the lock ends it and the call's keys work, and the volume still goes on without locking`() {
        val joystick = map()
        joystick.press(volumeUp, 0)
        call = CallState.RINGING
        assertEquals(listOf<AaCommand>(AaCommand.AnswerCall), joystick.press(callLeft, 1_000).sent)
        assertEquals("the lock is over", 0L, joystick.volumeLock.leftMs(1_001))
        assertEquals("a volume tap goes through and locks nothing while it rings", listOf<AaCommand>(AaCommand.Volume(-1)), joystick.press(volumeDown, 3_000).sent)
        assertEquals(0L, joystick.volumeLock.leftMs(3_000))
        // Not the map's up (0x12 is the dash's yes to "Exit navigation?" and sends nothing to Android Auto, so there is no key to wait for) and not a side (the call's own
        // controls while it rings): the map's down is the page's own, the info ring's switch, which sends nothing but acts, and the lock does not drop it.
        assertEquals("the other taps are the page's", listOf("map down once -> info ring"), joystick.press(mapDown, 3_100).lines)
    }

    @Test
    fun `on a call the volume tap changes the volume and locks nothing, and the call's own keys work after it`() {
        call = CallState.IN_CALL
        val joystick = map()
        assertEquals(listOf<AaCommand>(AaCommand.Volume(1)), joystick.press(volumeUp, 0).sent)
        assertEquals(0L, joystick.volumeLock.leftMs(0))
        assertEquals(listOf<AaCommand>(AaCommand.EndCall), joystick.press(DashButtonEvent.Unknown(0x16), 1_000).sent)
    }

    @Test
    fun `with the switch off there is no lock at all, and turning it off lifts a lock at once`() {
        val off = map(enabled = false)
        off.press(volumeUp, 0)
        assertEquals(0L, off.volumeLock.leftMs(0))
        assertTrue(off.press(mapZoomIn, 100).sent.isNotEmpty())
        val on = map()
        on.press(volumeUp, 0)
        assertTrue(on.press(mapZoomIn, 100).sent.isEmpty())
        on.volumeLock.enabled = false
        assertEquals(0L, on.volumeLock.leftMs(200))
        assertTrue(on.press(mapZoomIn, 5_000).sent.isNotEmpty())
        on.volumeLock.enabled = true
        assertEquals("turned on again, nothing is locked until a volume tap", 0L, on.volumeLock.leftMs(5_001))
    }

    @Test
    fun `the live sides turn the lock on with the switch, at the start and at each change, and a step of the call's stream lifts it`() {
        val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live, "joystick.volumeLock.enabled = setup.settings.volumeCard" in live)
        assertTrue(live, "joystick?.volumeLock?.enabled = settings.volumeCard" in live)
        assertTrue(live, "if (change.stream == com.reau.app.phone.VolumeStream.CALL) joystick?.volumeLock?.clear()" in live)
        assertTrue(live, "com.reau.app.dash.VolumeCards.showMs(left)" in live)
        assertTrue(live, "joystick?.volumeLock?.leftMs(com.reau.app.util.MonotonicClock.nowMs())" in live)
        val map = File("src/main/java/com/reau/app/aa/JoystickMap.kt").readText()
        assertTrue(map, "droppedByLock(event, nowMs)?.let { return it }" in map)
        assertTrue(map, "volumeLock.renew(nowMs)" in map)
    }
}
