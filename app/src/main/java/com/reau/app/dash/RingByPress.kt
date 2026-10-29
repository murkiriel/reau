package com.reau.app.dash

/**
 * The maneuver ring with the rider's switch off: the dash stops passing the map mode's buttons on while its ring is hidden, so the ring
 * follows the screen of Android Auto ([screen]: Waze and Google Maps show it, any other screen hides it) and the joystick. Any press the dash
 * sends in its map mode (the sides, down, up) shows it, and any press in its media mode hides it. The map mode's middle (0x18, which takes
 * the dash into its media mode and back) is a switch: with the ring shown it hides it; with the ring hidden it shows it again, but only on a
 * navigation screen (on the app menu or another app it stays hidden, and opening Waze from the menu shows it, by the screen). The codes are
 * the dash's ([com.reau.app.dash.protocol.DashButtonEvent]). Hidden until the first press or navigation screen.
 */
class RingByPress {
    @Volatile
    var shown: Boolean = false
        private set

    /** Whether the screen of Android Auto is a navigation one, as last said; null until a screen is known (an unknown one changes nothing). */
    @Volatile
    private var onNavigation: Boolean? = null

    /** Hidden again, as when the switch goes off. */
    fun reset() {
        shown = false
    }

    /**
     * The screen of Android Auto changed: [isNav] says whether it is Waze or Google Maps (the ring is shown) or something else (it is hidden);
     * null, a screen not known, changes nothing. True when the ring has to change because of it. The joystick goes on from there.
     */
    fun screen(isNav: Boolean?): Boolean {
        val now = isNav ?: return false
        onNavigation = now
        val changed = now != shown
        shown = now
        return changed
    }

    /** A press with [code]; true when the ring has to change because of it (the code says which mode the dash is in). */
    fun press(code: Int): Boolean {
        val now = when (code) {
            MIDDLE -> !shown && onNavigation == true
            in MAP_MODE -> true
            in MEDIA_MODE -> false
            else -> return false
        }
        val changed = now != shown
        shown = now
        return changed
    }

    private companion object {
        /** The map mode's middle: it switches the ring (see the class). */
        const val MIDDLE = 0x18

        /** Left 0x14, right 0x13, down 0x15, up (the dash's back) 0x12. */
        val MAP_MODE = setOf(0x13, 0x14, 0x15, 0x12)

        /** Play/pause 0x05, volume 0x06 and 0x07, next 0x09, previous 0x0A. */
        val MEDIA_MODE = setOf(0x05, 0x06, 0x07, 0x09, 0x0A)
    }
}
