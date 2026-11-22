package com.reau.app.video

import com.reau.app.dash.CallRole
import com.reau.app.dash.CallSpan

/** The name as it is drawn: [text] (cut with an ellipsis when even the smallest letters were too wide) at [letters] pixels. */
data class NameFit(val text: String, val letters: Float)

/**
 * Where everything of the call card goes ([CallCardInk] draws it, the compositor lays it): a pill at the top of the glass, centred, with a handset, a small line of hints
 * and the name below it. All in the dash's 526×300 frame pixels, no Android class here so the unit tests look at the numbers. The pill is 236×56, 28 px from the top:
 * the round glass is still wide enough there (see the test). It sits over the volume card's place (y 12 to 60), which wins while it shows ([OverlayRules]), and over the
 * joystick's legend, which is not drawn while there is a call.
 */
object CallCardLayout {
    const val WIDTH = 236
    const val HEIGHT = 56

    /** The pill's top-left corner on the frame: centred across, 28 px from the top. */
    const val X = DashGlass.FRAME_W / 2 - WIDTH / 2
    const val Y = 28

    const val RADIUS = 12f
    const val BORDER = 2f

    /** The handset's box and its place in the pill. */
    const val ICON = 24
    const val ICON_X = 12
    const val ICON_Y = 16

    /** The left edge of both lines, and how wide they may be: up to 224 px from the pill's left edge. */
    const val TEXT_X = 46
    const val TEXT_MAX_W = 178

    /** The small line (hints, time): the letters' height and its baseline. */
    const val HINT_LETTERS = 14f
    const val HINT_BASELINE = 21

    /** The name: the biggest letters, the smallest it is squeezed to, and its baseline. */
    const val NAME_LETTERS = 23f
    const val NAME_LETTERS_MIN = 19f
    const val NAME_BASELINE = 46

    /** What a name too long even in the smallest letters ends with. */
    const val ELLIPSIS = "…"

    /** The shapes that stand for the arrows and the dot in the small line (drawn, not typed: no font has to have them): their width and the gap to the word. */
    const val MARK_W = 8f
    const val MARK_GAP = 4f

    /** Whether [role]'s piece carries a shape (a triangle before the answer, a triangle after the decline, a dot before the end). */
    fun hasMark(role: CallRole): Boolean = role != CallRole.HINT

    /** The width of the small line made of [spans]: the words ([measure] gives a piece's width at the line's letters) and the shapes with their gaps. */
    fun lineWidth(spans: List<CallSpan>, measure: (String) -> Float): Float =
        spans.sumOf { (measure(it.text) + if (hasMark(it.role)) MARK_W + MARK_GAP else 0f).toDouble() }.toFloat()

    /** How much the small line is squeezed sideways to fit [TEXT_MAX_W]: 1 when it fits as it is. */
    fun lineScale(width: Float): Float = if (width <= TEXT_MAX_W || width <= 0f) 1f else TEXT_MAX_W / width

    /**
     * The name that fits [TEXT_MAX_W] ([widthOf] gives the width of a text at a size of letters): the biggest of [NAME_LETTERS] down to [NAME_LETTERS_MIN], a pixel at a time;
     * if even the smallest is too wide, the smallest with the end cut and an ellipsis in its place.
     */
    fun fitName(name: String, widthOf: (text: String, letters: Float) -> Float): NameFit {
        val letters = generateSequence(NAME_LETTERS) { it - 1f }.takeWhile { it >= NAME_LETTERS_MIN }.firstOrNull { widthOf(name, it) <= TEXT_MAX_W }
        return if (letters != null) NameFit(name, letters) else NameFit(cut(name, widthOf), NAME_LETTERS_MIN)
    }

    /** The longest start of [name] that fits [TEXT_MAX_W] at [NAME_LETTERS_MIN] with the ellipsis after it (no space before it); the ellipsis alone when none does. */
    private fun cut(name: String, widthOf: (text: String, letters: Float) -> Float): String =
        (name.length downTo 1).map { name.substring(0, it).trimEnd() + ELLIPSIS }.firstOrNull { widthOf(it, NAME_LETTERS_MIN) <= TEXT_MAX_W } ?: ELLIPSIS
}

/**
 * Which of the dash video's notices are drawn when more than one is on. The volume card (top, y 12 to 60) wins over the call card (y 28 to 84): while it shows
 * the call card is not drawn, and it is back when the volume card goes. The call card wins over the joystick's legend (the middle of the frame, from y 77): while there is a
 * call the legend is not drawn. Read at each frame, with what each notice holds now.
 */
object OverlayRules {
    data class Drawn(val volume: Boolean, val call: Boolean, val legend: Boolean)

    fun drawn(volume: Boolean, call: Boolean, legend: Boolean): Drawn = Drawn(volume = volume, call = call && !volume, legend = legend && !call)
}
