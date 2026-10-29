package com.reau.app.dash

/** Where the rider's last choice of [RingCycle] is kept between projections (the phone's preferences; none in a test). */
interface RingStepMemory {
    var step: Int
}

/**
 * The info ring's own switch on the joystick: the dash's down button in its map mode (0x15, [RING_PRESS]) shows the ring and hides it
 * again, while the info ring is on in the settings. It never touches the dash's maneuver ring, which keeps its own rules (its switch, the
 * screen and the joystick, [RingByPress]): a hidden maneuver ring makes the dash stop passing the map mode's buttons on, so a button that
 * hid it would take itself away.
 *
 * The choice is a step, 0 (shown) or 1 (hidden), kept from one projection to the next ([RingStepMemory]); a step saved by an older build
 * (it went to 2) counts as hidden.
 */
object RingCycle {
    const val STEPS = 2

    /**
     * The button that switches the info ring: the dash's down in its map mode (0x15, the default of that slot on the joystick page, where
     * the rider may give it another action; the joystick's up and centre are locked, as they are the dash's own). The middle button (0x18)
     * is not it: it puts the dash in its media mode, and with the music card up the dash sends it as the media mode's (0x05), so it could
     * not be told from a press in the media mode.
     */
    const val RING_PRESS = 0x15

    /** Whether a press of [code] switches the info ring. */
    fun advancesOn(code: Int): Boolean = code == RING_PRESS

    /** The step after [step]: shown goes to hidden and anything else to shown. */
    fun next(step: Int): Int = if (valid(step) == 0) 1 else 0

    /** The step kept, in range: 0 shown, 1 hidden (what is out of range, or was saved by an older build, is hidden). */
    fun valid(step: Int): Int = if (step == 0) 0 else 1

    /** Whether the info ring is on the video in [step]. */
    fun info(step: Int): Boolean = valid(step) == 0
}
