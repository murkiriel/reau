package com.reau.app.dash

import com.reau.app.aa.JoystickBindings.Control

/** One line of the legend: a direction of the joystick and the short name of what it does ([text]; null when it does nothing). */
data class LegendRow(val control: Control, val text: String?)

/** What the legend says: a line for each direction, in the order of [ActionLegend.ORDER]. */
data class LegendCard(val rows: List<LegendRow>)

/**
 * The joystick's legend: for [SHOW_MS] after the dash's mode changes (the map mode and the media mode, told by the codes of the taps), a small
 * card on the dash's video says what each direction does in the mode it is in now, by the rider's own page. Which taps raise it is
 * [com.reau.app.aa.JoystickMap]'s decision; the card is drawn by the compositor ([com.reau.app.video.LegendInk]); the time, and one legend taking
 * the place of another, are [TimedShow]'s.
 */
object ActionLegend {
    /** How long the card stays on the video. */
    const val SHOW_MS = 3_000L

    /** The directions as the legend lists them: up, down, left, right and the middle. */
    val ORDER: List<Control> = listOf(Control.UP, Control.DOWN, Control.LEFT, Control.RIGHT, Control.CENTER)

    /** The card of [rows] (a label for each direction, null for one that does nothing) in the words of [text]. */
    fun card(rows: List<Pair<Control, EchoLabel?>>, text: (EchoLabel) -> String): LegendCard =
        LegendCard(rows.map { (control, label) -> LegendRow(control, label?.let(text)) })
}
