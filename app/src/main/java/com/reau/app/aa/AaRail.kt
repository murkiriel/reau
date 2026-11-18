package com.reau.app.aa

/**
 * Android Auto's side bar in the video (800×480, the 90 px at the LEFT edge in Android Auto 18; it was at the right in 17): its two shortcuts
 * (the last navigation app, the last media app) sit above the microphone and the grid's button, which are not shortcuts to an app. Measured
 * on Android Auto 18 at 180 dpi (17/11/2026): the shortcuts' centres are at (45, 198) and (45, 275), the microphone's at (45, 352) and the
 * button of the apps' grid (nine dots) at (45, 430).
 */
object AaRail {
    /** Which of the two shortcuts: the navigation app's (the upper one) or the media app's. */
    enum class Slot { NAVIGATION, MEDIA }

    /** The bar is the video's x from 0 up to (not including) this. */
    const val X_TO = 90
    const val Y_FROM = 150
    const val Y_SPLIT = 238
    const val Y_TO = 318

    /** The centre of the button that opens the apps' grid (the bar's lowest button). */
    const val GRID_X = 45
    const val GRID_Y = 430

    /** The shortcut a tap at ([x], [y]) of the video lands on; null when it lands on none. */
    fun slot(x: Int, y: Int): Slot? = when {
        x < 0 || x >= X_TO -> null
        y in Y_FROM until Y_SPLIT -> Slot.NAVIGATION
        y in Y_SPLIT until Y_TO -> Slot.MEDIA
        else -> null
    }
}
