// The columns of a line of the hours and the days on the Clima screen, and the width they need: one place for the screen and its test.
package com.reau.app.ui

/**
 * A line is, from the left: the [Column.LABEL] (the hour or "Dom. 22/11", takes what is left), the [Column.PILL] (only with rain, just
 * before the icon, so the icon stays in the same place with or without it), the [Column.ICON], the [Column.CHANCE] of rain, the
 * [Column.FIRST] temperature (the hour's, or the day's high) and, in the days, the [Column.SECOND] (the low, flush right).
 *
 * The width, worked out for the owner's phone (432 dp wide, font scale 1.15; [neededDp] does it again, and a test holds it):
 *  - the line's room: 432 - 2 x 16 (the screen's padding) - 2 x 1 (the card's border) - 2 x 16 (the line's padding) = 366 dp;
 *  - a bodyMedium character is about 0.55 em: 14 sp x 1.15 x 0.55 = 8.9 dp, so "Dom. 22/11" (10 characters, Medium weight, +8%) is
 *    96 dp, and "100 %" and "29 °C" (5 characters) are 44.5 dp; the pill's labelSmall is 11 sp x 1.15 x 0.55 = 7 dp a character,
 *    so "0,6 mm" (6 characters) is 42 dp + 2 x 6 (padding) + 2 x 1 (border) = 56 dp;
 *  - the line: 96 label + 6 gap + 56 pill + 8 gap + 28 icon + 10 gap + 48 % + (4 + 46) high + (8 + 46) low = 355 dp, 11 dp to spare.
 * The old columns (56 dp for the % and 2 x 64 dp for the temperatures, 128 dp where 104 are enough) would have taken 21 dp more
 * than the line has. The temperatures are right-aligned in a 46 dp column, close to one another; a wider text (a 3-digit "100 °F") just
 * takes what it needs from the label's room, which is the only column that gives.
 */
internal object ForecastLayout {
    enum class Column { LABEL, PILL, ICON, CHANCE, FIRST, SECOND }

    /** The columns of a line, in the order they are drawn: the pill only with rain, the second temperature only in the days. */
    fun columns(hasPill: Boolean, hasSecond: Boolean): List<Column> = buildList {
        add(Column.LABEL)
        if (hasPill) add(Column.PILL)
        add(Column.ICON)
        add(Column.CHANCE)
        add(Column.FIRST)
        if (hasSecond) add(Column.SECOND)
    }

    // All in dp.
    const val SCREEN_PADDING = 16
    const val CARD_BORDER = 1
    const val LINE_PADDING = 16
    const val LABEL_GAP = 6
    const val PILL_PADDING = 6
    const val PILL_BORDER = 1
    const val PILL_TO_ICON = 8
    const val ICON = 28
    const val ICON_TO_CHANCE = 10
    const val CHANCE_MIN = 48
    const val TEMP_MIN = 46
    const val FIRST_GAP = 4
    const val SECOND_GAP = 8

    private const val EM_PER_CHARACTER = 0.55
    private const val MEDIUM_EXTRA = 1.08
    private const val BODY_SP = 14.0
    private const val PILL_SP = 11.0

    /** The room of a line on a screen [screenDp] wide. */
    fun roomDp(screenDp: Double): Double = screenDp - 2.0 * (SCREEN_PADDING + CARD_BORDER + LINE_PADDING)

    private fun textDp(characters: Int, sp: Double, fontScale: Double): Double = characters * EM_PER_CHARACTER * sp * fontScale

    /**
     * What a line needs at [fontScale]: a label of [labelCharacters] (Medium weight), a pill of [pillCharacters] (none: 0), the
     * "100 %" and two 5-character temperatures (or one, when [hasSecond] is false), each at least as wide as its column.
     */
    fun neededDp(fontScale: Double, labelCharacters: Int, pillCharacters: Int, hasSecond: Boolean): Double {
        var total = textDp(labelCharacters, BODY_SP, fontScale) * MEDIUM_EXTRA + LABEL_GAP
        if (pillCharacters > 0) {
            total += textDp(pillCharacters, PILL_SP, fontScale) + 2 * (PILL_PADDING + PILL_BORDER) + PILL_TO_ICON
        }
        total += ICON + ICON_TO_CHANCE + maxOf(CHANCE_MIN.toDouble(), textDp(5, BODY_SP, fontScale))
        val temp = maxOf(TEMP_MIN.toDouble(), textDp(5, BODY_SP, fontScale))
        total += FIRST_GAP + temp
        if (hasSecond) total += SECOND_GAP + temp
        return total
    }
}
