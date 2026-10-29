package com.reau.app.aa

/**
 * Whether the touches that go to Android Auto from the phone's screen make a tap: a finger that goes down and comes up within
 * [maxMs] and never leaves [slopPx] of where it went down (in the video's pixels). A tap in Android Auto's app menu picks something and
 * so leaves the menu, like the joystick's select does ([AaScreens.selectSent]): the launcher's display keeps its focus after it, so the
 * screen watch would take the menu for the screen long after (21/10/2026: back to Waze by touch, the menu's fit stayed 3 minutes).
 * A touch cancelled, or a finger that moves (the menu scrolled), is not a tap. Pure, with the clock passed in.
 */
internal class TapWatch(private val slopPx: Int = SLOP_PX, private val maxMs: Long = MAX_MS) {
    private var down = false
    private var downX = 0
    private var downY = 0
    private var downAt = 0L
    private var moved = false

    /** One touch of the finger; true when it is the lift that ends a tap. */
    @Synchronized
    fun feed(action: PhoneTouch.Action, x: Int, y: Int, nowMs: Long): Boolean {
        when (action) {
            PhoneTouch.Action.DOWN -> {
                down = true
                downX = x
                downY = y
                downAt = nowMs
                moved = false
            }
            PhoneTouch.Action.MOVE -> if (down && (kotlin.math.abs(x - downX) > slopPx || kotlin.math.abs(y - downY) > slopPx)) moved = true
            PhoneTouch.Action.CANCEL -> down = false
            PhoneTouch.Action.UP -> {
                val tap = down && !moved && nowMs - downAt <= maxMs && kotlin.math.abs(x - downX) <= slopPx && kotlin.math.abs(y - downY) <= slopPx
                down = false
                return tap
            }
        }
        return false
    }

    companion object {
        const val SLOP_PX = 30
        const val MAX_MS = 800L
    }
}
