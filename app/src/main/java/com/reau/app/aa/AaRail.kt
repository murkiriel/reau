package com.reau.app.aa

/**
 * Android Auto's side bar in the video (800×480, the 90 px at the right): its two shortcuts (the last navigation app, the last media app) sit
 * above the microphone and the menu's button, which are not shortcuts to an app. Measured on the "Tela do Android Auto" of 22/10/2026
 * (180 dpi): the shortcuts' centres are at y 199 and 277, the microphone's at 353, the menu's at 431.
 */
object AaRail {
    /** Which of the two shortcuts: the navigation app's (the upper one) or the media app's. */
    enum class Slot { NAVIGATION, MEDIA }

    const val X_FROM = 700
    const val Y_FROM = 150
    const val Y_SPLIT = 238
    const val Y_TO = 318

    /** The shortcut a tap at ([x], [y]) of the video lands on; null when it lands on none. */
    fun slot(x: Int, y: Int): Slot? = when {
        x < X_FROM -> null
        y in Y_FROM until Y_SPLIT -> Slot.NAVIGATION
        y in Y_SPLIT until Y_TO -> Slot.MEDIA
        else -> null
    }
}
