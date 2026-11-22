package com.reau.app.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.reau.app.dash.CallCards
import com.reau.app.dash.CallNotice
import com.reau.app.dash.CallRole
import com.reau.app.dash.CallSpan

/**
 * The call card drawn as a picture for the compositor to lay at the top of the dash's video ([CallCardLayout] has the numbers): a dark pill at 92% with a 2 px
 * border, a handset, a small line of hints in the joystick's colours and the name in bold white below it, all green (grey when the call is over, and "Missed call" in red for a call that went unanswered). The arrows and the dot
 * of the hints are shapes, not characters, so no font has to have them. Plain colours and big shapes, as the dash gets the video as H.264 at a few frames a second.
 * Drawn when the call changes and once a second on a call, not at each frame.
 */
object CallCardInk {
    private const val BACKGROUND = 0xEB14161A.toInt()
    private const val WORDS = 0xFFFFFFFF.toInt()
    private const val HINT = 0xFFC9CDD2.toInt()
    private const val GREEN = 0xFF5FC98A.toInt()
    private const val RED = 0xFFE0806E.toInt()
    private const val GREY = 0xFF9AA0A8.toInt()
    private const val SEMIBOLD = 600

    /** The colour of the border and the handset: green, grey when the call is over. */
    fun colorOf(over: Boolean): Int = if (over) GREY else GREEN

    private fun colorOf(role: CallRole): Int = when (role) {
        CallRole.HINT -> HINT
        CallRole.ANSWER -> GREEN
        CallRole.DECLINE, CallRole.END, CallRole.MISSED -> RED
    }

    fun render(notice: CallNotice): Bitmap {
        val color = colorOf(CallCards.isOver(notice.card))
        val bitmap = createBitmap(CallCardLayout.WIDTH, CallCardLayout.HEIGHT)
        val canvas = Canvas(bitmap)
        val w = CallCardLayout.WIDTH.toFloat()
        val h = CallCardLayout.HEIGHT.toFloat()
        val r = CallCardLayout.RADIUS
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = BACKGROUND })
        // The border is drawn inside the pill, the stroke's middle half its width in.
        val half = CallCardLayout.BORDER / 2f
        canvas.drawRoundRect(
            RectF(half, half, w - half, h - half), r - half, r - half,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = CallCardLayout.BORDER
            },
        )
        canvas.withTranslation(CallCardLayout.ICON_X.toFloat(), CallCardLayout.ICON_Y.toFloat()) {
            val factor = CallCardLayout.ICON / VolumeCardInk.GRID
            scale(factor, factor)
            drawPath(PathParser.createPathFromPathData(VolumeCardInk.HANDSET), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        drawHints(canvas, notice.spans)
        drawName(canvas, notice.card.name)
        return bitmap
    }

    /** The small line: each piece in its colour, the arrows and the dot as shapes, the whole line squeezed sideways when it is wider than the room. */
    private fun drawHints(canvas: Canvas, spans: List<CallSpan>) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, SEMIBOLD, false)
            textSize = CallCardLayout.HINT_LETTERS
        }
        val squeeze = CallCardLayout.lineScale(CallCardLayout.lineWidth(spans) { paint.measureText(it) })
        val baseline = CallCardLayout.HINT_BASELINE.toFloat()
        canvas.withTranslation(CallCardLayout.TEXT_X.toFloat(), 0f) {
            scale(squeeze, 1f)
            var x = 0f
            val middle = baseline - CallCardLayout.HINT_LETTERS * 0.36f
            for (span in spans) {
                paint.color = colorOf(span.role)
                // The shape goes before the word for the answer and the end, after it for the decline; each takes MARK_W and MARK_GAP, as lineWidth counts.
                if (span.role == CallRole.ANSWER || span.role == CallRole.END) {
                    drawMark(this, span.role, x, middle, paint)
                    x += CallCardLayout.MARK_W + CallCardLayout.MARK_GAP
                }
                drawText(span.text, x, baseline, paint)
                x += paint.measureText(span.text)
                if (span.role == CallRole.DECLINE) {
                    x += CallCardLayout.MARK_GAP
                    drawMark(this, span.role, x, middle, paint)
                    x += CallCardLayout.MARK_W
                }
            }
        }
    }

    /** The shape of [role]'s piece with its left edge at [x] and its middle at [middle]: a triangle pointing left for the answer and right for the decline, a dot for the end. */
    private fun drawMark(canvas: Canvas, role: CallRole, x: Float, middle: Float, paint: Paint) {
        val size = CallCardLayout.MARK_W
        val half = size * 0.6f
        when (role) {
            CallRole.ANSWER -> canvas.drawPath(triangle(PointF(x + size, middle - half), PointF(x, middle), PointF(x + size, middle + half)), paint)
            CallRole.DECLINE -> canvas.drawPath(triangle(PointF(x, middle - half), PointF(x + size, middle), PointF(x, middle + half)), paint)
            CallRole.END -> canvas.drawCircle(x + size / 2f, middle, size / 2f, paint)
            CallRole.HINT, CallRole.MISSED -> Unit
        }
    }

    private fun triangle(a: PointF, b: PointF, c: PointF) = Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(c.x, c.y)
        close()
    }

    /** The name, bold white: the biggest letters that fit, down to the smallest, and then cut with an ellipsis ([CallCardLayout.fitName]). */
    private fun drawName(canvas: Canvas, name: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            color = WORDS
        }
        val fit = CallCardLayout.fitName(name) { text, letters ->
            paint.textSize = letters
            paint.measureText(text)
        }
        paint.textSize = fit.letters
        canvas.drawText(fit.text, CallCardLayout.TEXT_X.toFloat(), CallCardLayout.NAME_BASELINE.toFloat(), paint)
    }
}
