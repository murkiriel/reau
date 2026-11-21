package com.reau.app.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale

/**
 * Draws the FPS test card ([FpsTestCard]) on ONE bitmap that is kept and drawn on again for every frame (no bitmap per frame): black, the number of the
 * frame at the top left, the pace and the second beside it, the even/odd square at the top right, one cell per frame of the second (the current one green), and
 * the number in binary at the bottom. The layout is made on the dash's own 526x300 frame and scaled to the bitmap (528x304, or the 526x300 after the fall back).
 * Only the compositor's GL thread uses it.
 */
internal class FpsTestCardInk {
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null

    private val fill = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val rect = RectF()

    /** The card of [cells] on the bitmap of [width]x[height] (made again only when the size changes); the same bitmap every time. */
    fun draw(cells: FpsTestCard.Cells, width: Int, height: Int): Bitmap {
        var bmp = bitmap
        if (bmp == null || bmp.width != width || bmp.height != height) {
            bmp?.recycle()
            bmp = createBitmap(width, height)
            bitmap = bmp
            canvas = Canvas(bmp)
        }
        val c = checkNotNull(canvas)
        c.withScale(width / REF_W, height / REF_H) {
            drawColor(Color.BLACK)
            header(this, cells)
            slots(this, cells)
            binary(this, cells)
        }
        return bmp
    }

    /** Lets go of the bitmap (the card is off); the next [draw] makes it again. */
    fun release() {
        bitmap?.recycle()
        bitmap = null
        canvas = null
    }

    private fun header(c: Canvas, cells: FpsTestCard.Cells) {
        c.label(cells.number, NUMBER_X, NUMBER_BASE, NUMBER_SIZE, Color.WHITE)
        c.label(cells.rate, INFO_X, RATE_BASE, INFO_SIZE, YELLOW)
        c.label(cells.secondsLine, INFO_X, SECONDS_BASE, INFO_SIZE, CYAN)
        fill.color = if (cells.even) Color.WHITE else DARK
        rect.set(SQUARE_X, SQUARE_Y, SQUARE_X + SQUARE_SIDE, SQUARE_Y + SQUARE_SIDE)
        c.drawRect(rect, fill)
        text.textAlign = Paint.Align.RIGHT
        c.label("PAR/IMPAR", SQUARE_X + SQUARE_SIDE + 4f, SQUARE_LABEL_BASE, SMALL_SIZE, GREY)
        text.textAlign = Paint.Align.LEFT
    }

    /** One cell per frame of the second, of equal width; the cell of the frame is green with a black number, the others dark with a grey one. */
    private fun slots(c: Canvas, cells: FpsTestCard.Cells) {
        val cellW = STRIP_W / cells.fps
        for (i in 1..cells.fps) {
            val left = STRIP_X + (i - 1) * cellW
            val now = i == cells.slot
            fill.color = if (now) GREEN else DARK
            rect.set(left, SLOT_Y, left + cellW, SLOT_Y + SLOT_H)
            c.drawRect(rect, fill)
            if (!now) {
                // A thin edge between the dark cells, so they read as cells.
                fill.color = EDGE
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = 1f
                c.drawRect(rect, fill)
                fill.style = Paint.Style.FILL
            }
            text.textAlign = Paint.Align.CENTER
            c.label(i.toString(), left + cellW / 2f, SLOT_Y + SLOT_H / 2f + SLOT_SIZE * HALF_CAP, SLOT_SIZE, if (now) Color.BLACK else GREY_DIM)
            text.textAlign = Paint.Align.LEFT
        }
    }

    /** The binary number, the most significant bit at the left: a white cell is a 1, a dark one a 0. */
    private fun binary(c: Canvas, cells: FpsTestCard.Cells) {
        val cellW = STRIP_W / FpsTestCard.BITS
        cells.bits.forEachIndexed { i, one ->
            val left = STRIP_X + i * cellW
            fill.color = if (one) Color.WHITE else DARK
            rect.set(left + 1f, BITS_Y, left + cellW - 1f, BITS_Y + BITS_H)
            c.drawRect(rect, fill)
        }
        c.label("binario do quadro (${FpsTestCard.BITS} bits)", STRIP_X, BITS_LABEL_BASE, SMALL_SIZE, GREY)
    }

    private fun Canvas.label(line: String, x: Float, baseline: Float, size: Float, color: Int) {
        text.textSize = size
        text.color = color
        drawText(line, x, baseline, text)
    }

    private companion object {
        // The layout, in pixels of the dash's 526x300 frame.
        const val REF_W = 526f
        const val REF_H = 300f
        const val NUMBER_X = 10f
        const val NUMBER_BASE = 66f
        const val NUMBER_SIZE = 76f
        const val INFO_X = 190f
        const val RATE_BASE = 32f
        const val SECONDS_BASE = 63f
        const val INFO_SIZE = 26f
        const val SQUARE_X = 456f
        const val SQUARE_Y = 6f
        const val SQUARE_SIDE = 63f
        const val SQUARE_LABEL_BASE = 82f
        const val SMALL_SIZE = 12f
        const val STRIP_X = 10f
        const val STRIP_W = 505f
        const val SLOT_Y = 100f
        const val SLOT_H = 90f
        const val SLOT_SIZE = 30f
        const val BITS_Y = 215f
        const val BITS_H = 56f
        const val BITS_LABEL_BASE = 289f

        /** Half the height of a digit as a share of the text size, to put a number in the middle of its cell. */
        const val HALF_CAP = 0.36f

        val GREEN = Color.rgb(0, 255, 0)
        val YELLOW = Color.rgb(255, 196, 0)
        val CYAN = Color.rgb(0, 208, 255)
        val DARK = Color.rgb(24, 24, 24)
        val EDGE = Color.rgb(60, 60, 60)
        val GREY = Color.rgb(130, 130, 130)
        val GREY_DIM = Color.rgb(90, 90, 90)
    }
}
