package com.reau.app.settings

/**
 * How often "Tela acesa com o celular bloqueado" turns the screen on again ([REauSettings.screenKeepMode]): at every screen-off, or by a timer
 * of [intervalSec] seconds counted from the moment the screen went off. [logName] is the short word the ride log says (`[screen-keep] on mode=off|n30|n60|n120|n180|n240`).
 */
enum class ScreenKeepMode(val logName: String, val intervalSec: Int?) {
    ON_SCREEN_OFF("off", null),
    EVERY_30_S("n30", 30),

    // Stored by name: the 60 s one keeps its name (and its saved value) though it now sits between 30 s and 2 min.
    EVERY_60_S("n60", 60),
    EVERY_120_S("n120", 120),
    EVERY_180_S("n180", 180),

    // The phone that kills REau about 300 s after the screen goes off (realme) leaves little room at 3 and 4 minutes: on purpose, to find the limit.
    EVERY_240_S("n240", 240),
}
