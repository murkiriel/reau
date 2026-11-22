package com.reau.app.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.reau.app.dash.VolumeKind
import com.reau.app.dash.VolumeNotice

/**
 * The volume card drawn as a picture for the compositor to lay at the top of the dash's video ([VolumeCardLayout] has the numbers): a dark pill at 92%
 * with a 2 px border, an icon (a speaker for the music, a handset for a call, a speaker crossed out at level 0), the words in bold white and a bar,
 * all in the colour of the kind (amber for the music, green for a call). Icons are paths, not a font. Plain colours and big shapes, as the dash gets the
 * video as H.264 at a few frames a second. Drawn once when the volume changes, not at each frame.
 */
object VolumeCardInk {
    private const val BACKGROUND = 0xEB14161A.toInt()
    private const val WORDS = 0xFFFFFFFF.toInt()
    private const val MUSIC = 0xFFE0A446.toInt()
    private const val CALL = 0xFF5FC98A.toInt()

    /** Material's handset and speaker bodies on a 24-unit grid, scaled to the icon's box. */
    internal const val GRID = 24f
    internal const val HANDSET =
        "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20" +
            "c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z"
    private const val SPEAKER = "M3 9v6h4l5 5V4L7 9H3z"

    /** The colour of [kind]: the border, the icon and the bar's fill. */
    fun colorOf(kind: VolumeKind): Int = if (kind == VolumeKind.CALL) CALL else MUSIC

    fun render(notice: VolumeNotice): Bitmap {
        val card = notice.card
        val color = colorOf(card.kind)
        val bitmap = createBitmap(VolumeCardLayout.WIDTH, VolumeCardLayout.HEIGHT)
        val canvas = Canvas(bitmap)
        val w = VolumeCardLayout.WIDTH.toFloat()
        val h = VolumeCardLayout.HEIGHT.toFloat()
        val r = VolumeCardLayout.RADIUS
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = BACKGROUND })
        // The border is drawn inside the pill, the stroke's middle half its width in.
        val half = VolumeCardLayout.BORDER / 2f
        canvas.drawRoundRect(
            RectF(half, half, w - half, h - half), r - half, r - half,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = VolumeCardLayout.BORDER
            },
        )
        drawIcon(canvas, card.kind, card.muted, color)
        val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = VolumeCardLayout.LETTERS
            this.color = WORDS
        }
        words.textScaleX = VolumeCardLayout.textScale(words.measureText(notice.text))
        canvas.drawText(notice.text, VolumeCardLayout.TEXT_X.toFloat(), VolumeCardLayout.BASELINE.toFloat(), words)
        drawBar(canvas, card.level, card.max, color)
        return bitmap
    }

    private fun drawBar(canvas: Canvas, level: Int, max: Int, color: Int) {
        val x = VolumeCardLayout.BAR_X.toFloat()
        val y = VolumeCardLayout.BAR_Y.toFloat()
        val radius = VolumeCardLayout.BAR_RADIUS
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            alpha = (VolumeCardLayout.TRACK_ALPHA * 255).toInt()
        }
        canvas.drawRoundRect(RectF(x, y, x + VolumeCardLayout.BAR_W, y + VolumeCardLayout.BAR_H), radius, radius, track)
        val fill = VolumeCardLayout.fillWidth(level, max)
        if (fill > 0) {
            canvas.drawRoundRect(RectF(x, y, x + fill, y + VolumeCardLayout.BAR_H), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
    }

    /** The icon, drawn on the 24-unit grid scaled into its 22×22 box. */
    private fun drawIcon(canvas: Canvas, kind: VolumeKind, muted: Boolean, color: Int) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.withTranslation(VolumeCardLayout.ICON_X.toFloat(), VolumeCardLayout.ICON_Y.toFloat()) {
            val factor = VolumeCardLayout.ICON / GRID
            scale(factor, factor)
            if (kind == VolumeKind.CALL && !muted) {
                drawPath(PathParser.createPathFromPathData(HANDSET), fill)
            } else {
                drawPath(PathParser.createPathFromPathData(SPEAKER), fill)
                if (muted) {
                    // A cross where the sound waves would be.
                    val cross = Path().apply {
                        moveTo(15f, 9f)
                        lineTo(21f, 15f)
                        moveTo(21f, 9f)
                        lineTo(15f, 15f)
                    }
                    drawPath(cross, line)
                } else {
                    drawArc(RectF(6f, 7f, 16f, 17f), -45f, 90f, false, line)
                    drawArc(RectF(3.5f, 4.5f, 18.5f, 19.5f), -45f, 90f, false, line)
                }
            }
        }
    }
}
