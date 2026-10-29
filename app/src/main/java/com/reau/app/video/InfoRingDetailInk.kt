package com.reau.app.video

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import com.reau.app.weather.BatteryDetail
import com.reau.app.weather.GpsDetail
import com.reau.app.weather.LinkDetail
import com.reau.app.weather.LinkKind
import com.reau.app.weather.RingDetail
import com.reau.app.weather.RingTone

/**
 * The pictures of the phone's cards on the info ring, drawn from numbers and not from layers: the battery (a battery filled to its level, a
 * bolt when charging, a leaf when the system's saver is on, the percentage big), the link (signal bars, Wi-Fi arcs, a satellite, a plane)
 * and the GPS (a compass arrow for the heading, bars for the signal). In the 120×120 box, inside the content circle, above the line of
 * words. The colours are the weather screen's flat tones ([InfoRingGlyphs]); the level says its colour, so a glance tells good from bad.
 */
@Suppress("TooManyFunctions") // one small function per piece of a picture
internal object InfoRingDetailInk {
    fun draw(canvas: Canvas, detail: RingDetail, night: Boolean, tone: RingTone) {
        val look = Look(night, tone == RingTone.DANGER)
        when (detail) {
            is BatteryDetail -> battery(canvas, detail, look)
            is LinkDetail -> link(canvas, detail, look)
            is GpsDetail -> gps(canvas, detail, look)
        }
    }

    /** What the pictures need to know of the disk they are on: the day or night, and whether the disk is the red of a danger. */
    private class Look(val night: Boolean, val onDanger: Boolean) {
        val edges: Boolean get() = !night
        val outline: Int get() = if (night) 0xFFB0BEC5.toInt() else 0xFF78909C.toInt()
        val empty: Int get() = if (night) 0xFF37474F.toInt() else 0xFFCFD8DC.toInt()
        val ink: Int get() = if (night || onDanger) 0xFFF3EDE0.toInt() else 0xFF17130E.toInt()
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float, radius: Float) =
        Path().apply { addRoundRect(RectF(l, t, r, b), radius, radius, Path.Direction.CW) }

    // ── the battery ────────────────────────────────────────────────────────────────────────────────────────────

    private fun battery(canvas: Canvas, b: BatteryDetail, look: Look) {
        val left = BODY_LEFT
        canvas.drawPath(rect(left, BODY_TOP, BODY_RIGHT, BODY_BOTTOM, 5f), InfoRingInk.fill(look.outline))
        canvas.drawPath(rect(left + WALL, BODY_TOP + WALL, BODY_RIGHT - WALL, BODY_BOTTOM - WALL, 3f), InfoRingInk.fill(look.empty))
        val inner = BODY_RIGHT - BODY_LEFT - 2 * WALL
        val filled = (inner * b.percent.coerceIn(0, PERCENT) / PERCENT.toFloat()).coerceAtLeast(if (b.percent > 0) 3f else 0f)
        if (filled > 0f) {
            val color = InfoRingGlyphs.batteryColor(b.percent).toInt()
            canvas.drawPath(rect(left + WALL, BODY_TOP + WALL, left + WALL + filled, BODY_BOTTOM - WALL, 3f), InfoRingInk.fill(color))
        }
        canvas.drawPath(rect(BODY_RIGHT + 1f, BODY_TOP + 6f, BODY_RIGHT + 5f, BODY_BOTTOM - 6f, 1.5f), InfoRingInk.fill(look.outline))
        val middle = (BODY_TOP + BODY_BOTTOM) / 2f
        val both = b.charging && b.saver
        if (b.charging) bolt(canvas, if (both) 52f else 57.5f, middle)
        if (b.saver) leaf(canvas, if (both) 68f else 57.5f, middle)
        percentage(canvas, b.percent, look)
    }

    /** The percentage, the biggest thing of the card: the number 32 px, its "%" smaller, the pair centred. */
    private fun percentage(canvas: Canvas, percent: Int, look: Look) {
        fun paint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = look.ink
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size
        }
        val number = paint(InfoRingSpot.HUGE_SIZE)
        val sign = paint(InfoRingSpot.WORD_SIZE)
        val digits = percent.coerceIn(0, PERCENT).toString()
        val width = number.measureText(digits) + sign.measureText("%") + SIGN_GAP
        val x = (InfoRingSpot.SIZE - width) / 2f
        canvas.drawText(digits, x, PERCENT_BASELINE, number)
        canvas.drawText("%", x + number.measureText(digits) + SIGN_GAP, PERCENT_BASELINE, sign)
    }

    private fun bolt(canvas: Canvas, cx: Float, cy: Float) {
        val path = Path().apply {
            moveTo(cx + 1.5f, cy - 9f)
            lineTo(cx - 5f, cy + 1f)
            lineTo(cx - 0.5f, cy + 1f)
            lineTo(cx - 1.5f, cy + 9f)
            lineTo(cx + 5f, cy - 2f)
            lineTo(cx + 0.5f, cy - 2f)
            close()
        }
        InfoRingInk.solid(canvas, path, InfoRingGlyphs.color(RingLayer.BOLT).toInt(), edges = true)
    }

    /** A leaf, the sign of saving: a pointed oval slanted up to the right, with its stem. */
    private fun leaf(canvas: Canvas, cx: Float, cy: Float) {
        val path = Path().apply {
            moveTo(cx - 5f, cy + 5f)
            quadTo(cx - 6f, cy - 5f, cx + 5f, cy - 6f)
            quadTo(cx + 6f, cy + 5f, cx - 5f, cy + 5f)
            close()
        }
        canvas.drawPath(path, InfoRingInk.fill(LEAF))
        canvas.drawLine(cx - 5f, cy + 5f, cx + 2f, cy - 2f, InfoRingInk.stroke(0xFFE8F5E9.toInt(), 1.2f))
    }

    // ── the link ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun link(canvas: Canvas, l: LinkDetail, look: Look) {
        when (l.kind) {
            LinkKind.CELLULAR -> bars(canvas, l.bars, look, LINK_BARS)
            LinkKind.WIFI -> arcs(canvas, l.bars, look, slashed = false)
            LinkKind.OFFLINE -> arcs(canvas, 0, look, slashed = true)
            LinkKind.STARLINK, LinkKind.SATELLITE -> satellite(canvas, look)
            LinkKind.AIRPLANE -> plane(canvas, look)
        }
    }

    /** Signal bars: where they stand (the left edge and the baseline), how tall each is and how wide and far apart. */
    private class BarsShape(val left: Float, val base: Float, val heights: List<Float>, val width: Float, val gap: Float)

    /** Four bars rising to the right from ([left], [base]); the first [lit] ones in the colour of the signal, the rest empty. */
    private fun bars(canvas: Canvas, lit: Int, look: Look, shape: BarsShape) {
        val color = InfoRingGlyphs.barsColor(lit).toInt()
        shape.heights.forEachIndexed { i, h ->
            val x = shape.left + i * (shape.width + shape.gap)
            canvas.drawPath(rect(x, shape.base - h, x + shape.width, shape.base, 2f), InfoRingInk.fill(if (i < lit) color else look.empty))
        }
    }

    /** Wi-Fi: a dot and three arcs, lit by the signal ([bars] 0 to 4: none lit with none, and the line says "no network" when [slashed]). */
    private fun arcs(canvas: Canvas, bars: Int, look: Look, slashed: Boolean) {
        val blue = InfoRingGlyphs.color(RingLayer.RAIN).toInt()
        val litCount = if (slashed) 0 else (bars * 3 + 3) / 4
        val cx = 60f
        val cy = 42f
        InfoRingInk.let {
            canvas.drawCircle(cx, cy, 3.4f, it.fill(if (slashed) look.empty else blue))
            for ((i, r) in listOf(11f, 19f, 27f).withIndex()) {
                val paint = it.stroke(if (i < litCount || (bars == 0 && !slashed)) blue else look.empty, 4f)
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -135f, 90f, false, paint)
            }
            if (slashed) canvas.drawLine(34f, 20f, 86f, 44f, it.stroke(InfoRingGlyphs.color(RingLayer.SLASH).toInt(), 3.5f))
        }
    }

    /** A satellite: a slanted body between two panels, for the Starlink and the phone's own satellite link. */
    private fun satellite(canvas: Canvas, look: Look) {
        val blue = InfoRingGlyphs.color(RingLayer.RAIN).toInt()
        val grey = InfoRingGlyphs.color(RingLayer.CLOUD).toInt()
        canvas.withRotation(-20f, 60f, 33f) {
            InfoRingInk.solid(canvas, rect(38f, 27f, 50f, 38f, 2f), blue, look.edges)
            InfoRingInk.solid(canvas, rect(70f, 27f, 82f, 38f, 2f), blue, look.edges)
            InfoRingInk.solid(canvas, rect(52f, 25f, 68f, 41f, 4f), grey, look.edges)
            canvas.drawLine(60f, 41f, 60f, 47f, InfoRingInk.stroke(grey, 3f))
        }
    }

    /** An airplane seen from above, in the amber of a warning. */
    private fun plane(canvas: Canvas, look: Look) {
        val path = Path().apply {
            moveTo(12f, 2f); lineTo(13.6f, 9f); lineTo(22f, 14f); lineTo(22f, 16f); lineTo(13.6f, 13.6f)
            lineTo(13.2f, 19f); lineTo(15.6f, 20.8f); lineTo(15.6f, 22f); lineTo(12f, 21f)
            lineTo(8.4f, 22f); lineTo(8.4f, 20.8f); lineTo(10.8f, 19f); lineTo(10.4f, 13.6f)
            lineTo(2f, 16f); lineTo(2f, 14f); lineTo(10.4f, 9f); close()
        }
        canvas.withTranslation(35f, 8f) { canvas.withScale(PLANE_SCALE, PLANE_SCALE) { InfoRingInk.solid(canvas, path, AMBER, look.edges) } }
    }

    // ── the GPS ────────────────────────────────────────────────────────────────────────────────────────────────

    private fun gps(canvas: Canvas, g: GpsDetail, look: Look) {
        val cx = 44f
        val cy = 31f
        canvas.drawCircle(cx, cy, COMPASS_R, InfoRingInk.stroke(look.outline, 2.5f))
        val north = Path().apply { moveTo(cx, cy - COMPASS_R - 1f); lineTo(cx - 3f, cy - COMPASS_R + 4f); lineTo(cx + 3f, cy - COMPASS_R + 4f); close() }
        canvas.drawPath(north, InfoRingInk.fill(look.outline))
        val bearing = g.bearingDeg
        if (bearing == null) {
            canvas.drawCircle(cx, cy, 2.6f, InfoRingInk.fill(look.outline))
        } else {
            canvas.withRotation(bearing.toFloat(), cx, cy) {
                val arrow = Path().apply { moveTo(cx, cy - 10f); lineTo(cx + 6f, cy + 8f); lineTo(cx, cy + 4f); lineTo(cx - 6f, cy + 8f); close() }
                InfoRingInk.solid(canvas, arrow, InfoRingGlyphs.color(RingLayer.RAIN).toInt(), look.edges)
            }
        }
        bars(canvas, g.bars, look, GPS_BARS)
    }

    private const val PERCENT = 100
    private const val BODY_LEFT = 35f
    private const val BODY_RIGHT = 80f
    private const val BODY_TOP = 19f
    private const val BODY_BOTTOM = 41f
    private const val WALL = 2.5f
    private const val PERCENT_BASELINE = 69f
    private const val SIGN_GAP = 1.5f
    private const val BARS_BASE = 42f
    private const val COMPASS_R = 14f
    private const val PLANE_SCALE = 2.5f
    private const val LEAF = 0xFF1B5E20.toInt()
    private const val AMBER = 0xFFFFB300.toInt()
    private val LINK_BARS = BarsShape(36f, BARS_BASE, listOf(8f, 12f, 17f, 23f), 9f, 4f)
    private val GPS_BARS = BarsShape(66f, BARS_BASE, listOf(5f, 8f, 11f, 14f), 5f, 3f)
}
