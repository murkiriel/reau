package com.reau.app.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import kotlin.math.ceil

/**
 * How [StatsOverlay]'s text is inked, one way for both places that show it: the video to the dash (a bitmap the
 * compositor lays over the frame) and the preview on the "Tela do Android Auto" (drawn on the screen's canvas, scaled
 * by [draw]'s `scale`): red letters with a black border, bold; the numbers, and under them, smaller, the label
 * ("app" or "painel") with the stream's mode and keyframe, and the frame size and speed.
 */
object StatsOverlayInk {
    private const val RED = 0xFFFF0000.toInt()

    /** The label's letters are this much of the numbers'. */
    private const val LABEL_SCALE = 0.7f

    /** The border's width: a seventh of the letters' height, never thinner than 2 px. */
    private fun border(size: Float) = maxOf(2f, size / 7f)

    private fun paint(size: Float, stroke: Boolean, fill: Int = RED) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = size
        if (stroke) {
            style = Paint.Style.STROKE
            strokeWidth = border(size) * 2 // half of it falls inside the letter, under the red
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        } else {
            color = fill
        }
    }

    /** The space around the letters, so the border is not cut. */
    private fun pad(size: Float) = ceil(border(size)) + 1f

    /** The numbers; the label with its extra; the detail when there is one: the last two smaller. */
    private fun lines(text: StatsText, size: Float): List<Pair<String, Float>> = listOfNotNull(
        text.numbers to size,
        (if (text.extra.isEmpty()) text.label else "${text.label}  ${text.extra}") to size * LABEL_SCALE,
        text.detail.takeIf { it.isNotEmpty() }?.let { it to size * LABEL_SCALE },
    )

    private fun lineHeight(size: Float): Float = paint(size, stroke = false).fontMetrics.let { it.descent - it.ascent }

    /** The box the text takes at [size] (frame pixels): its width and height, border included. */
    fun measure(text: StatsText, size: Int): Pair<Int, Int> = measureRows(lines(text, size.toFloat()), size.toFloat())

    /** Draws [text] with its box's top-left corner at ([left], [top]), everything multiplied by [scale]. */
    @Suppress("LongParameterList") // the parameters are the fields of what it builds (wire format / the data it carries)
    fun draw(canvas: Canvas, text: StatsText, size: Int, left: Float, top: Float, scale: Float = 1f) =
        drawRows(canvas, lines(text, size * scale), size * scale, left, top, RED)

    /** The box of [rows] (each a line and the height of its letters) around letters of [size]: width and height, border included. Shared with [ButtonOverlayInk]. */
    internal fun measureRows(rows: List<Pair<String, Float>>, size: Float): Pair<Int, Int> {
        val pad = pad(size)
        val width = rows.maxOf { (line, px) -> paint(px, stroke = false).measureText(line) }
        return ceil(width + 2 * pad).toInt() to ceil(rows.sumOf { (_, px) -> lineHeight(px).toDouble() } + 2 * pad).toInt()
    }

    /** Draws [rows] in [color] with a black border, the box's top-left corner at ([left], [top]); [size] is the height of the first row's letters. */
    @Suppress("LongParameterList") // the parameters are the fields of what it builds (wire format / the data it carries)
    internal fun drawRows(canvas: Canvas, rows: List<Pair<String, Float>>, size: Float, left: Float, top: Float, color: Int) {
        val pad = pad(size)
        var y = top + pad
        for ((line, px) in rows) {
            val fill = paint(px, stroke = false, fill = color)
            val baseline = y - fill.fontMetrics.ascent
            canvas.drawText(line, left + pad, baseline, paint(px, stroke = true))
            canvas.drawText(line, left + pad, baseline, fill)
            y += lineHeight(px)
        }
    }

    /** The text on a transparent bitmap of its box, for the compositor to lay over the dash's frame. */
    fun render(text: StatsText, size: Int): Bitmap {
        val (w, h) = measure(text, size)
        return createBitmap(w, h).also { draw(Canvas(it), text, size, 0f, 0f) }
    }
}
