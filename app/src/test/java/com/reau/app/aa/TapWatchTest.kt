package com.reau.app.aa

import com.reau.app.aa.PhoneTouch.Action
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tap on the phone's screen in Android Auto's menu leaves the menu, as the joystick's select does. */
class TapWatchTest {
    @Test
    fun `a finger down and up in the same place at once is a tap`() {
        val watch = TapWatch()
        assertFalse(watch.feed(Action.DOWN, 400, 240, 1_000))
        assertTrue(watch.feed(Action.UP, 400, 240, 1_080))
    }

    @Test
    fun `a little shake of the finger is still a tap`() {
        val watch = TapWatch()
        watch.feed(Action.DOWN, 400, 240, 0)
        watch.feed(Action.MOVE, 415, 250, 40)
        assertTrue(watch.feed(Action.UP, 410, 245, 90))
    }

    @Test
    fun `a drag, a long press and a cancelled touch are no tap`() {
        val watch = TapWatch()
        watch.feed(Action.DOWN, 400, 240, 0)
        watch.feed(Action.MOVE, 500, 240, 50) // scrolling the menu
        assertFalse(watch.feed(Action.UP, 400, 240, 100)) // back where it began: still a drag
        watch.feed(Action.DOWN, 400, 240, 1_000)
        assertFalse("held too long", watch.feed(Action.UP, 400, 240, 2_000))
        watch.feed(Action.DOWN, 400, 240, 3_000)
        watch.feed(Action.CANCEL, 400, 240, 3_001) // the focus clearer's touch: down and cancelled at once
        assertFalse(watch.feed(Action.UP, 400, 240, 3_050))
    }

    @Test
    fun `a lift with no touch before it is no tap, and each tap is its own`() {
        val watch = TapWatch()
        assertFalse(watch.feed(Action.UP, 10, 10, 0))
        watch.feed(Action.DOWN, 10, 10, 100)
        assertTrue(watch.feed(Action.UP, 10, 10, 150))
        assertFalse("the second lift has no touch of its own", watch.feed(Action.UP, 10, 10, 200))
    }
}
