package com.reau.app.video

/**
 * Where everything of the volume card goes ([VolumeCardInk] draws it, the compositor lays it): a pill at the top of the glass, centred, with an icon, the
 * words ("Música 7/15") and a bar. All in the dash's 526×300 frame pixels (the compositor's output is 1:1 with the frame, as the legend and the ring
 * are laid), no Android class here so the unit tests look at the numbers. The pill is 154×48 at the top, where the round glass is still wide enough
 * (see the test) and the legend of the joystick, in the middle, does not reach.
 */
object VolumeCardLayout {
    const val WIDTH = 154
    const val HEIGHT = 48

    /** The pill's top-left corner on the frame: centred across, 12 px from the top. */
    const val X = DashGlass.FRAME_W / 2 - WIDTH / 2
    const val Y = 12

    const val RADIUS = 12f
    const val BORDER = 2f

    /** The icon's box and its place in the pill. */
    const val ICON = 22
    const val ICON_X = 12
    const val ICON_Y = 9

    /** The words: size, left edge and baseline in the pill, and the width they must fit before they are squeezed. */
    const val LETTERS = 21f
    const val TEXT_X = 42
    const val BASELINE = 27
    const val TEXT_MAX_W = 100

    /** The bar: the track's place and size and its corner radius; the fill is [fillWidth] of the width. */
    const val BAR_X = 12
    const val BAR_Y = 37
    const val BAR_W = 130
    const val BAR_H = 5
    const val BAR_RADIUS = 2f

    /** The track's alpha, against the fill's full colour. */
    const val TRACK_ALPHA = 0.24f

    /** How much the words are squeezed sideways to fit [TEXT_MAX_W]: 1 when they fit as they are. */
    fun textScale(width: Float): Float = if (width <= TEXT_MAX_W || width <= 0f) 1f else TEXT_MAX_W / width

    /** The width of the bar's fill for [level] of [max] steps: 0 at the bottom, the whole track at the top. */
    fun fillWidth(level: Int, max: Int): Int {
        if (max <= 0) return 0
        return (BAR_W.toLong() * level.coerceIn(0, max) / max).toInt()
    }
}
