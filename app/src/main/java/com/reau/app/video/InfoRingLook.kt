package com.reau.app.video

import com.reau.app.weather.RingTone

/**
 * Where the dash's info ring sits on the 526×300 frame, and its measures, from the info ring's design (all in frame pixels, the
 * ring's own origin at the corner of its 120×120 box). The dash draws nothing on the right, so it is the mirror of the dash's own
 * manoeuvre bubble ([DashMask.BUBBLE_X]) on the left: a disk of radius 60 centred at (441, 233).
 */
object InfoRingSpot {
    const val SIZE = 120
    const val RADIUS = 60f
    const val CENTER_X = DashGlass.FRAME_W - 85
    const val CENTER_Y = 233

    /** The box's top-left corner on the frame. */
    const val X = CENTER_X - SIZE / 2
    const val Y = CENTER_Y - SIZE / 2

    /** The box's left edge when the ring sits in the place of the dash's manoeuvre bubble, on the left ([DashMask.BUBBLE_X]); the same row. */
    const val PLACE_X = DashMask.BUBBLE_X.toInt() - SIZE / 2

    /** Dark outline, always there: it parts the disk from any map. 3 px, drawn at radius 58.5 (it fills 57 to 60). */
    const val OUTLINE_WIDTH = 3f
    const val OUTLINE_RADIUS = 58.5f

    /** The state band, only when there is something to warn of: 6 px at radius 54 (it fills 51 to 57). */
    const val BAND_WIDTH = 6f
    const val BAND_RADIUS = 54f

    /** Nothing is drawn beyond this radius from the centre but the outline and the band. */
    const val CONTENT_RADIUS = 49f

    /** The big picture's box, 60×60, centred at (60, 39); the icons are drawn on a 24×24 grid scaled to it. */
    const val ICON_SIZE = 60f
    const val ICON_CENTER_X = 60f
    const val ICON_CENTER_Y = 39f
    const val GRID = 24f

    /** The one line of words: baseline row, widest, and the sizes of a number and of a word. */
    const val LINE_BASELINE = 88f
    const val LINE_MAX_WIDTH = 80f
    const val NUMBER_SIZE = 20f
    const val WORD_SIZE = 16f
    const val LINE_GAP = 4f

    /** The percentage of the battery card, the biggest text of the ring. */
    const val HUGE_SIZE = 32f

    /** A stale ring's contour and band are dashed: 16 dashes, 65% dash and 35% gap, round the circle. */
    val OUTLINE_DASH = floatArrayOf(14.93f, 8.04f)
    val BAND_DASH = floatArrayOf(13.79f, 7.42f)

    /** The line's text size, shrunk the same for all its pieces when they are wider than [LINE_MAX_WIDTH] together. */
    fun lineScale(widths: List<Float>): Float {
        val total = widths.sum() + LINE_GAP * (widths.size - 1).coerceAtLeast(0)
        return if (total <= LINE_MAX_WIDTH || total <= 0f) 1f else LINE_MAX_WIDTH / total
    }
}

/** The ring's colours, day and night (`0xAARRGGBB`), from the design. */
@Suppress("LongParameterList") // one colour of the design a parameter, filled in once for the day and once for the night
class InfoRingPalette private constructor(
    val disk: Int,
    val ink: Int,
    val outline: Int,
    val rain: Int,
    val attention: Int,
    val alert: Int,
    val danger: Int,
) {
    /** The ink on a disk of this [tone]: white on the red danger disk, the ring's ink on any other. */
    fun inkFor(tone: RingTone): Int = if (tone == RingTone.DANGER) WHITE else ink

    /** The disk's fill: the red one for danger, the plain one otherwise. */
    fun diskFor(tone: RingTone): Int = if (tone == RingTone.DANGER) danger else disk

    /** The state band's colour, or null when the tone has none ([RingTone.NONE]) or fills the disk instead ([RingTone.DANGER]). */
    fun bandFor(tone: RingTone): Int? = when (tone) {
        RingTone.NONE, RingTone.DANGER -> null
        RingTone.RAIN -> rain
        RingTone.ATTENTION -> attention
        RingTone.ALERT -> alert
        RingTone.UV_HIGH, RingTone.AIR_SENSITIVE -> BAND_ORANGE
        RingTone.UV_VERY_HIGH, RingTone.AIR_UNHEALTHY -> BAND_RED
        RingTone.UV_EXTREME, RingTone.AIR_VERY_UNHEALTHY -> BAND_VIOLET
        RingTone.AIR_HAZARDOUS -> BAND_MAROON
    }

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()

        // The WHO's UV bands and the EPA's air bands, the same colours day and night (only the last of the EPA's is its own).
        private const val BAND_ORANGE = 0xFFE36A00.toInt()
        private const val BAND_RED = 0xFFD0102B.toInt()
        private const val BAND_VIOLET = 0xFF7B3FB5.toInt()
        private const val BAND_MAROON = 0xFF7E0023.toInt()

        val DAY = InfoRingPalette(
            disk = 0xFFF3EDE0.toInt(), ink = 0xFF17130E.toInt(), outline = 0xFF17130E.toInt(), rain = 0xFF1F6FD6.toInt(),
            attention = 0xFFE0A000.toInt(), alert = 0xFFE35A00.toInt(), danger = 0xFFC8102E.toInt(),
        )
        val NIGHT = InfoRingPalette(
            disk = 0xFF121314.toInt(), ink = 0xFFF3EDE0.toInt(), outline = 0xFFBDB4A2.toInt(), rain = 0xFF4C9BFF.toInt(),
            attention = 0xFFFFC23D.toInt(), alert = 0xFFFF7A1F.toInt(), danger = 0xFFE0283E.toInt(),
        )

        fun of(night: Boolean): InfoRingPalette = if (night) NIGHT else DAY
    }
}
