package com.reau.app.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.dash.LegendCard

/**
 * The joystick's legend drawn as a picture for the compositor to lay over the dash's video ([LegendLayout] has the numbers): a dark card at 70%
 * (the map shows through), a line for each direction with an amber arrow (a dot for the middle) and the short name in bold white, 20 px tall, as
 * the stuck-picture badge's letters ([StaleBadgeInk]); a direction that does nothing says "-" dimmed. Plain colours and big shapes, as the dash
 * gets the video as H.264 at 4 frames a second. Drawn once when the card is shown, not at each frame.
 */
object LegendInk {
    private const val CARD = 0xB3000000.toInt()
    private const val WORDS = 0xFFFFFFFF.toInt()
    private const val WORDS_DIM = 0x99FFFFFF.toInt()
    private const val MARKER = 0xFFFFB300.toInt()
    private const val DOT = 0.4f

    fun render(card: LegendCard): Bitmap {
        val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = LegendLayout.LETTERS
        }
        val texts = card.rows.map { it.text ?: LegendLayout.NOTHING }
        val widest = texts.maxOfOrNull { words.measureText(it) } ?: 0f
        val (width, height) = LegendLayout.size(widest, card.rows.size)
        words.textScaleX = LegendLayout.textScale(widest)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()), LegendLayout.RADIUS, LegendLayout.RADIUS,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD },
        )
        val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MARKER }
        card.rows.forEachIndexed { i, row ->
            val cy = LegendLayout.rowCenterY(i)
            drawMarker(canvas, row.control, cy, marker)
            words.color = if (row.text == null) WORDS_DIM else WORDS
            canvas.drawText(texts[i], LegendLayout.TEXT_X.toFloat(), cy - (words.ascent() + words.descent()) / 2f, words)
        }
        return bitmap
    }

    private fun drawMarker(canvas: Canvas, control: Control, cy: Float, ink: Paint) {
        val cx = LegendLayout.MARKER_CENTER_X
        val corners = LegendLayout.arrow(control, cx, cy)
        if (corners.isEmpty()) {
            canvas.drawCircle(cx, cy, LegendLayout.MARKER * DOT + 1f, ink)
            return
        }
        val path = Path()
        corners.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        path.close()
        canvas.drawPath(path, ink)
    }
}
