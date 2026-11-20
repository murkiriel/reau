package com.reau.app.devtools

import com.reau.app.dash.nav.DashManeuverGlyphs

/**
 * Debug builds only: the names of the maneuver arrows in Spanish, for the arrow test's label ([ArrowTrial.label]). English and Portuguese are
 * [DashManeuverGlyphs.describe] and [DashManeuverGlyphs.describePt]; Spanish lives here because only this debug tool shows it, and a release never loads it.
 * One entry for each byte that has an English name (ArrowTrialTest looks at that).
 */
internal object ArrowNamesEs {
    private const val LAST_EXIT = 19

    private val NAMES: Map<Int, String> = buildMap {
        put(0x00, "Llegada: el destino está justo delante")
        put(0x01, "Llegada: el destino está delante, a la izquierda")
        put(0x02, "Llegada: el destino está delante, a la derecha")
        put(0x03, "Unión en Y: la vía del motorista entra por la izquierda")
        put(0x04, "Unión en Y: la vía del motorista entra por la derecha")
        put(0x05, "Bifurcación en Y: tomar el ramal de la derecha")
        put(0x06, "Bifurcación en Y: tomar el ramal de la izquierda")
        put(0x07, "Una vía lateral sale a la derecha")
        put(0x08, "Una vía lateral sale a la izquierda")
        put(0x09, "Seguir recto")
        put(0x14, "Girar a la izquierda")
        put(0x15, "Girar a la derecha")
        put(0x16, "Curva cerrada a la izquierda")
        put(0x17, "Curva cerrada a la derecha")
        put(0x18, "Curva suave a la izquierda")
        put(0x19, "Curva suave a la derecha")
        put(0x1A, "Cambio de sentido por la derecha")
        put(0x1B, "Bifurcación en Y: seguir recto")
        put(0x1C, "Recalculando la ruta (brújula girando)")
        for (b in 0x1D..0x20) put(b, "Bifurcación en Y: seguir recto (otro dibujo)")
        put(0x21, "Unión en Y: entra por la izquierda (dibujada como la 0x03)")
        put(0x22, "Unión en Y: entra por la derecha (dibujada como la 0x04)")
        put(0x23, "Una vía lateral entra por la izquierda, en ángulo bajo")
        put(0x24, "Una vía lateral entra por la derecha, en ángulo bajo")
        put(0x25, "Una vía lateral entra por la izquierda, en ángulo abierto")
        put(0x26, "Una vía lateral entra por la derecha, en ángulo abierto")
        put(0x27, "Tomar el ramal de la derecha, curva suave")
        put(0x28, "Tomar el ramal de la izquierda, curva suave")
        put(0x29, "Salir a la derecha, más cerrado")
        put(0x2A, "Salir a la izquierda, más cerrado")
        put(0x2D, "Desviarse a la derecha")
        put(0x2E, "Desviarse a la izquierda")
        put(0x2F, "Tomar la salida de la derecha, más cerrada")
        put(0x30, "Tomar la salida de la izquierda, más cerrada")
        put(0x3B, "Seguir recto un buen tramo")
        put(0x3C, "Solo el marcador del destino")
        put(0x3D, "Cambio de sentido por la izquierda")
        put(0x3E, "Ferri")
        put(0x3F, "Paso a nivel")
        put(0x40, "El destino está cerca")
        put(0x42, "Sin señal (no es una maniobra)")
        for (n in 0..LAST_EXIT) {
            put(DashManeuverGlyphs.roundabout(n, clockwise = false), "Rotonda antihoraria, salida $n")
            put(DashManeuverGlyphs.roundabout(n, clockwise = true), "Rotonda horaria, salida $n")
        }
    }

    /** The Spanish name of the arrow [code], or null when it draws nothing or has no name yet (as [DashManeuverGlyphs.describe]). */
    fun name(code: Int): String? = if (DashManeuverGlyphs.isVisible(code)) NAMES[code] else null
}
