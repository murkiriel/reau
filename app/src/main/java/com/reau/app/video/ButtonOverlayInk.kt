package com.reau.app.video

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap

/**
 * How [ButtonOverlay]'s text is inked, one way for both places that show it: the video to the dash (a bitmap the compositor lays over
 * the frame) and the preview on the "Tela do Android Auto" (drawn on the screen's canvas, scaled by `scale`): orange letters with a black
 * border, bold, like [StatsOverlayInk] (which does the drawing), the code and its name first and, smaller, what REau did with the press.
 */
object ButtonOverlayInk {
    const val ORANGE = 0xFFFF8C00.toInt()

    /** The detail's letters are this much of the first line's. */
    private const val DETAIL_SCALE = 0.7f

    private fun rows(text: ButtonText, size: Float): List<Pair<String, Float>> =
        listOf(text.first to size) + (text.detailLines + listOfNotNull(text.note.takeIf { it.isNotEmpty() })).map { it to size * DETAIL_SCALE }

    /** The box the text takes at [size] (frame pixels): its width and height, border included. */
    fun measure(text: ButtonText, size: Int): Pair<Int, Int> = StatsOverlayInk.measureRows(rows(text, size.toFloat()), size.toFloat())

    /** Draws [text] with its box's top-left corner at ([left], [top]), everything multiplied by [scale]. */
    @Suppress("LongParameterList") // the parameters are the fields of what it builds (wire format / the data it carries)
    fun draw(canvas: Canvas, text: ButtonText, size: Int, left: Float, top: Float, scale: Float = 1f) =
        StatsOverlayInk.drawRows(canvas, rows(text, size * scale), size * scale, left, top, ORANGE)

    /** The text on a transparent bitmap of its box, for the compositor to lay over the dash's frame. */
    fun render(text: ButtonText, size: Int): Bitmap {
        val (w, h) = measure(text, size)
        return createBitmap(w, h).also { draw(Canvas(it), text, size, 0f, 0f) }
    }
}
