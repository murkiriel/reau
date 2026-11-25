package com.reau.app.video

import com.reau.app.aa.JoystickBindings.Control

/**
 * Where everything of the joystick's legend goes ([LegendInk] draws it, the compositor lays it): a card of one line for each direction, a
 * marker (an arrow, or a dot for the middle) and the short name beside it, in the middle of the dash's 526×300 frame. All in frame pixels,
 * and no Android class here, so the unit tests look at the numbers. The card is about 270×146 at most, a quarter of the frame, and drawn
 * semi-transparent by the ink, so the map stays in view round and under it.
 */
object LegendLayout {
    /** The letters' height. */
    const val LETTERS = 20f

    /** One line's height, and the card's margin round the lines. */
    const val ROW_H = 26
    const val PAD_X = 12
    const val PAD_Y = 8

    /** The marker's box (an arrow or a dot) and the gap between it and the words. */
    const val MARKER = 14
    const val GAP = 10

    /** The card is never wider than this: a longer name is squeezed ([textScale]) and not cut. */
    const val MAX_W = 360

    /** What a direction that does nothing says. */
    const val NOTHING = "-"

    /** The card's corner radius. */
    const val RADIUS = 14f

    /** The left edge of the words. */
    const val TEXT_X = PAD_X + MARKER + GAP

    /** The card's size for [lines] lines whose widest words are [widest] pixels wide at [LETTERS]. */
    fun size(widest: Float, lines: Int): Pair<Int, Int> {
        val width = (TEXT_X + widest * textScale(widest) + PAD_X).toInt().coerceAtMost(MAX_W)
        return width to (2 * PAD_Y + lines * ROW_H)
    }

    /** How much the words are squeezed sideways so the card fits [MAX_W]: 1 when they fit as they are. */
    fun textScale(widest: Float): Float {
        val room = (MAX_W - TEXT_X - PAD_X).toFloat()
        return if (widest <= room || widest <= 0f) 1f else room / widest
    }

    /** The vertical middle of line [index] in the card. */
    fun rowCenterY(index: Int): Float = PAD_Y + index * ROW_H + ROW_H / 2f

    /** The marker's centre on every line. */
    const val MARKER_CENTER_X = PAD_X + MARKER / 2f

    /** The card's top-left corner for a box of [width]×[height]: in the middle of the frame, which the dash's round glass shows whole. */
    fun corner(width: Int, height: Int): Pair<Int, Int> = (DashGlass.FRAME_W - width) / 2 to (DashGlass.FRAME_H - height) / 2

    /**
     * The three corners of the arrow for [control] centred at ([cx], [cy]) in a box of [MARKER] pixels, the tip first; empty for the middle,
     * which is a dot.
     */
    fun arrow(control: Control, cx: Float, cy: Float): List<Pair<Float, Float>> {
        val h = MARKER / 2f
        return when (control) {
            Control.UP -> listOf(cx to cy - h, cx + h to cy + h, cx - h to cy + h)
            Control.DOWN -> listOf(cx to cy + h, cx - h to cy - h, cx + h to cy - h)
            Control.LEFT -> listOf(cx - h to cy, cx + h to cy - h, cx + h to cy + h)
            Control.RIGHT -> listOf(cx + h to cy, cx - h to cy - h, cx - h to cy + h)
            Control.CENTER -> emptyList()
        }
    }
}
