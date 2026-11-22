package com.reau.app.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import com.reau.app.weather.RingFrame
import com.reau.app.weather.RingPage
import com.reau.app.weather.RingTone

/**
 * The dash's info ring drawn as a 120×120 picture ([InfoRingSpot.SIZE]) for the compositor to lay on the video: an opaque disk with a dark
 * outline, a coloured band round it when the page warns of something, one big icon and one line of words. Everything is solid colour, strokes
 * of about 4 px and no gradient: the dash gets it as H.264 at 4 frames a second. The icons are the weather screen's own (`WeatherGlyph`, same
 * pieces, same colours, [InfoRingGlyphs]) on a 24×24 grid scaled up ([InfoRingSpot.ICON_SIZE] / [InfoRingSpot.GRID]); in the day a thin darker
 * edge round each filled piece keeps the pale ones (the cloud) from washing out on the cream disk. Redraw only when the [RingFrame] changes.
 */
@Suppress("TooManyFunctions") // one small function per piece of an icon, each looked at in the tests
object InfoRingInk {
    private const val SCALE = InfoRingSpot.ICON_SIZE / InfoRingSpot.GRID

    /** Stroke width on the grid, as the weather screen's: 1.6 × 2.5 = 4 px. */
    private const val STROKE = 1.6f

    /** The edge round a filled piece in the day, on the grid: about 2 px; and how much darker than the piece it is. */
    private const val EDGE = 0.8f
    private const val EDGE_DARKER = 0.62f

    fun render(frame: RingFrame): Bitmap {
        val bitmap = createBitmap(InfoRingSpot.SIZE, InfoRingSpot.SIZE)
        val canvas = Canvas(bitmap)
        val look = InfoRingPalette.of(frame.night)
        val page = frame.page
        val center = InfoRingSpot.SIZE / 2f
        canvas.drawCircle(center, center, InfoRingSpot.RADIUS, fill(look.diskFor(page.tone)))
        val bandDash = InfoRingSpot.BAND_DASH.takeIf { frame.stale }
        val outlineDash = InfoRingSpot.OUTLINE_DASH.takeIf { frame.stale }
        look.bandFor(page.tone)?.let { band ->
            canvas.drawCircle(center, center, InfoRingSpot.BAND_RADIUS, ring(band, InfoRingSpot.BAND_WIDTH, bandDash))
        }
        canvas.drawCircle(center, center, InfoRingSpot.OUTLINE_RADIUS, ring(look.outline, InfoRingSpot.OUTLINE_WIDTH, outlineDash))
        val detail = page.detail
        if (detail != null) InfoRingDetailInk.draw(canvas, detail, frame.night, page.tone) else drawIcon(canvas, page, edges = !frame.night)
        drawLine(canvas, page, look.inkFor(page.tone))
        return bitmap
    }

    internal fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    private fun ring(color: Int, width: Float, dash: FloatArray?) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        if (dash != null) pathEffect = DashPathEffect(dash, 0f)
    }

    internal fun stroke(color: Int, width: Float = STROKE) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** The line: its pieces side by side, numbers big, words small, the whole centred on the baseline; shrunk together if too wide. */
    private fun drawLine(canvas: Canvas, page: RingPage, ink: Int) {
        if (page.line.isEmpty()) return
        val paints = page.line.map { piece ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.LEFT
                textSize = if (piece.big) InfoRingSpot.NUMBER_SIZE else InfoRingSpot.WORD_SIZE
            }
        }
        val widths = page.line.mapIndexed { i, piece -> paints[i].measureText(piece.text) }
        val scale = InfoRingSpot.lineScale(widths)
        val gap = InfoRingSpot.LINE_GAP * scale
        var x = (InfoRingSpot.SIZE - (widths.sum() * scale + gap * (widths.size - 1))) / 2f
        page.line.forEachIndexed { i, piece ->
            paints[i].textSize *= scale
            canvas.drawText(piece.text, x, InfoRingSpot.LINE_BASELINE, paints[i])
            x += widths[i] * scale + gap
        }
    }

    private fun drawIcon(canvas: Canvas, page: RingPage, edges: Boolean) {
        val left = InfoRingSpot.ICON_CENTER_X - InfoRingSpot.ICON_SIZE / 2f
        val top = InfoRingSpot.ICON_CENTER_Y - InfoRingSpot.ICON_SIZE / 2f
        val onDanger = page.tone == RingTone.DANGER
        canvas.withTranslation(left, top) {
            canvas.withScale(SCALE, SCALE) {
                for (layer in InfoRingGlyphs.layers(page.icon)) drawLayer(canvas, layer, InfoRingGlyphs.color(layer, onDanger).toInt(), edges)
            }
        }
    }

    /** A filled [path] in [color], with its darker edge in the day. */
    internal fun solid(canvas: Canvas, path: Path, color: Int, edges: Boolean) {
        canvas.drawPath(path, fill(color))
        if (edges) canvas.drawPath(path, stroke(darker(color), EDGE).apply { strokeJoin = Paint.Join.ROUND })
    }

    internal fun darker(color: Int): Int {
        val r = (((color shr 16) and 0xFF) * EDGE_DARKER).toInt()
        val g = (((color shr 8) and 0xFF) * EDGE_DARKER).toInt()
        val b = ((color and 0xFF) * EDGE_DARKER).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun drawLayer(canvas: Canvas, layer: RingLayer, color: Int, edges: Boolean) {
        when (layer) {
            RingLayer.SUN -> sun(canvas, SUN_FULL, color, edges)
            RingLayer.SUN_BEHIND -> sun(canvas, SUN_BEHIND, color, edges)
            RingLayer.MOON -> solid(canvas, moon(12f, 12f, 1f), color, edges)
            RingLayer.MOON_BEHIND -> solid(canvas, moon(9f, 8.5f, 0.72f), color, edges)
            RingLayer.CLOUD, RingLayer.DARK_CLOUD -> solid(canvas, cloud(), color, edges)
            RingLayer.FOG -> for (y in listOf(19.5f, 22f)) canvas.drawLine(5f, y, 19f, y, stroke(color))
            RingLayer.DRIZZLE -> for (x in listOf(9f, 15f)) drop(canvas, x, 20.5f, 1f, color)
            RingLayer.RAIN -> for (x in listOf(8f, 12f, 16f)) drop(canvas, x, 20.5f, 1f, color)
            RingLayer.HEAVY_RAIN -> for (x in listOf(6.5f, 10.2f, 13.9f, 17.6f)) drop(canvas, x, 20.4f, 1.3f, color)
            RingLayer.SNOW -> for (x in listOf(8.5f, 15.5f)) flake(canvas, x, 20.5f, 2.2f, color)
            RingLayer.BOLT -> solid(canvas, bolt(), color, edges)
            RingLayer.UNKNOWN -> canvas.drawCircle(12f, 12f, 8f, stroke(color, 2f))
            RingLayer.WIND -> wind(canvas, color)
            RingLayer.DUST -> dust(canvas, color)
            RingLayer.HORIZON -> canvas.drawLine(3f, 18.5f, 21f, 18.5f, stroke(color))
            RingLayer.HALF_SUN -> halfSun(canvas, color, edges)
            RingLayer.ARROW_DOWN -> arrow(canvas, color, down = true)
            RingLayer.ARROW_UP -> arrow(canvas, color, down = false)
            RingLayer.THERMOMETER -> thermometer(canvas, color, edges)
            RingLayer.SLASH -> canvas.drawLine(4.5f, 4.5f, 19.5f, 19.5f, stroke(color, 2f))
        }
    }

    // The pieces, on the 24×24 grid, with the weather screen's own geometry where it has the piece.

    /** A sun on the grid: its centre, the radius of its disk and how far its rays reach. */
    private class SunShape(val cx: Float, val cy: Float, val radius: Float, val rayEnd: Float)

    private val SUN_FULL = SunShape(12f, 12f, 4.6f, 8.5f)
    private val SUN_BEHIND = SunShape(9f, 8.5f, 3.4f, 6.4f)

    private fun sun(canvas: Canvas, shape: SunShape, color: Int, edges: Boolean) {
        val cx = shape.cx
        val cy = shape.cy
        val radius = shape.radius
        val rayEnd = shape.rayEnd
        solid(canvas, Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }, color, edges)
        val ray = stroke(color)
        for (i in 0 until RAYS) {
            val a = Math.toRadians(i * (DEGREES / RAYS).toDouble())
            val cos = Math.cos(a).toFloat()
            val sin = Math.sin(a).toFloat()
            val start = radius + RAY_GAP
            canvas.drawLine(cx + cos * start, cy + sin * start, cx + cos * rayEnd, cy + sin * rayEnd, ray)
        }
    }

    /** The cloud of the weather screen: three circles and a rounded base, joined as one outline. */
    private fun cloud(): Path {
        val path = Path().apply { addCircle(8f, 13f, 3.8f, Path.Direction.CW) }
        for (part in listOf(
            Path().apply { addCircle(12.5f, 10.5f, 5f, Path.Direction.CW) },
            Path().apply { addCircle(17f, 13.2f, 3.6f, Path.Direction.CW) },
            Path().apply { addRoundRect(RectF(4.2f, 12f, 20.8f, 16.8f), 2.4f, 2.4f, Path.Direction.CW) },
        )) {
            path.op(part, Path.Op.UNION)
        }
        return path
    }

    /** A crescent: a disk of [s] times radius 7.2 at ([cx], [cy]) less a disk moved up and to the right. */
    private fun moon(cx: Float, cy: Float, s: Float): Path {
        val path = Path().apply { addCircle(cx, cy, 7.2f * s, Path.Direction.CW) }
        path.op(Path().apply { addCircle(cx + 3.4f * s, cy - 2.6f * s, 6f * s, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        return path
    }

    /** A drop: the weather screen's slanted stroke; [s] longer for heavy rain. */
    private fun drop(canvas: Canvas, x: Float, y: Float, s: Float, color: Int) =
        canvas.drawLine(x + 0.8f * s, y - 2f * s, x - 0.8f * s, y + 1.6f * s, stroke(color))

    private fun flake(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val paint = stroke(color, 1.2f)
        for (i in 0 until 3) {
            val a = Math.toRadians(i * 60.0)
            val dx = Math.cos(a).toFloat() * r
            val dy = Math.sin(a).toFloat() * r
            canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, paint)
        }
    }

    private fun bolt() = Path().apply {
        moveTo(13f, 13f)
        lineTo(9.5f, 19f)
        lineTo(12f, 19f)
        lineTo(10.5f, 23.5f)
        lineTo(15f, 17f)
        lineTo(12.5f, 17f)
        lineTo(14.5f, 13f)
        close()
    }

    /** Wind: two lines that curl at the end and a short one, round-capped, in the weather screen's stroke. */
    private fun wind(canvas: Canvas, color: Int) {
        val paint = stroke(color)
        canvas.withTranslation(0f, 2f) {
            canvas.drawPath(
                Path().apply {
                    moveTo(3f, 8f)
                    lineTo(13f, 8f)
                    arcTo(RectF(10f, 2f, 16f, 8f), 90f, -270f)
                },
                paint,
            )
            canvas.drawPath(
                Path().apply {
                    moveTo(3f, 12f)
                    lineTo(18f, 12f)
                    arcTo(RectF(15f, 12f, 21f, 18f), -90f, 270f)
                },
                paint,
            )
            canvas.drawLine(3f, 16f, 10f, 16f, paint)
        }
    }

    /** Haze under the cloud: grains of dust where the rain would fall. */
    private fun dust(canvas: Canvas, color: Int) {
        val paint = fill(color)
        for ((x, y) in listOf(8f to 19.4f, 12f to 20.2f, 16f to 19.4f, 10f to 22.4f, 14f to 22.4f)) canvas.drawCircle(x, y, 1.15f, paint)
    }

    /** The sun half way up the horizon line, with its rays above. */
    private fun halfSun(canvas: Canvas, color: Int, edges: Boolean) {
        val body = Path().apply { arcTo(RectF(6.6f, 13.1f, 17.4f, 23.9f), 180f, 180f); close() }
        solid(canvas, body, color, edges)
        val ray = stroke(color)
        for (i in 1 until HALF_RAYS) {
            val a = Math.toRadians(180.0 + i * (DEGREES / 2 / HALF_RAYS))
            val cos = Math.cos(a).toFloat()
            val sin = Math.sin(a).toFloat()
            canvas.drawLine(12f + cos * 7f, 18.5f + sin * 7f, 12f + cos * 9.4f, 18.5f + sin * 9.4f, ray)
        }
    }

    private fun arrow(canvas: Canvas, color: Int, down: Boolean) {
        val paint = stroke(color)
        canvas.drawLine(12f, 1.6f, 12f, 6.2f, paint)
        if (down) {
            canvas.drawLine(9.6f, 4f, 12f, 6.4f, paint)
            canvas.drawLine(14.4f, 4f, 12f, 6.4f, paint)
        } else {
            canvas.drawLine(9.6f, 4f, 12f, 1.6f, paint)
            canvas.drawLine(14.4f, 4f, 12f, 1.6f, paint)
        }
    }

    private fun thermometer(canvas: Canvas, color: Int, edges: Boolean) {
        val tube = Path().apply { addRoundRect(RectF(9.2f, 2.2f, 14.8f, 16.8f), 2.8f, 2.8f, Path.Direction.CW) }
        tube.op(Path().apply { addCircle(12f, 18f, 4.4f, Path.Direction.CW) }, Path.Op.UNION)
        solid(canvas, tube, color, edges)
        val mercury = InfoRingGlyphs.MERCURY.toInt()
        canvas.drawCircle(12f, 18f, 2.5f, fill(mercury))
        canvas.drawLine(12f, 18f, 12f, 8f, stroke(mercury, 2f))
    }

    private const val RAYS = 8
    private const val HALF_RAYS = 6
    private const val DEGREES = 360f
    private const val RAY_GAP = 1.4f
}
