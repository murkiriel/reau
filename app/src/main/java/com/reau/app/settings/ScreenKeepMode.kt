package com.reau.app.settings

/**
 * How often "Tela acesa com o celular bloqueado" turns the screen on again ([REauSettings.screenKeepMode]): at every screen-off, or by a timer
 * of [intervalSec] seconds counted from the moment the screen went off. [logName] is the short word the ride log says (`screen-keep on mode=...`).
 */
enum class ScreenKeepMode(val logName: String, val intervalSec: Int?) {
    ON_SCREEN_OFF("off", null),
    EVERY_60_S("n60", 60),
    EVERY_30_S("n30", 30),
}
