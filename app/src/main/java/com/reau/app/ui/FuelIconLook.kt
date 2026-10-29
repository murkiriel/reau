package com.reau.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import com.reau.app.ui.theme.Grafite
import com.reau.app.ui.theme.Ouro

/**
 * How the fuel pump beside the range looks on "Dados da moto". Off (or never said): the pump in the text colour (dark on the light
 * theme, light on the dark one). On: Ouro on a Grafite disc, 9:1 against the disc and, with the disc, 15:1 on the light theme's Marfim
 * (Ouro alone is 1.7:1 there: in the light theme it is only a fill, never an icon on its own) and 8:1 on the dark theme's Carvao; it blinks,
 * one second shown and one second hidden ([BLINK_MS]), whatever Android's animation setting says. Pure, so the JVM tests cover the colours.
 */
internal object FuelIconLook {
    const val BLINK_MS = 1_000

    /** [chip]: the disc behind the pump, none when off; [hidden]: the blink's dark second. */
    data class Look(val tint: Color, val chip: Color?, val hidden: Boolean)

    /** [warning]: the dash's low-fuel reminder (null: never said); [blinkShown]: the blink's lit second; [text]: the theme's text colour. */
    fun of(warning: Boolean?, blinkShown: Boolean, text: Color): Look =
        if (warning == true) Look(Ouro, Grafite, hidden = !blinkShown) else Look(text, null, hidden = false)
}

/** What the pump's box says to the tests, not to a screen reader: true in the blink's hidden second. */
internal val FuelBlinkHidden = SemanticsPropertyKey<Boolean>("FuelBlinkHidden")
internal var SemanticsPropertyReceiver.fuelBlinkHidden by FuelBlinkHidden
