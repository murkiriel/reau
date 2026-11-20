package com.reau.app.video

/** What the compositor does at a tick of its clock. */
enum class TickAction {
    /** A new picture arrived since the last frame: draw it. */
    DRAW,

    /** Nothing new, and the idle interval is up: send the last picture (or black) again. */
    REPEAT,

    /** Nothing new and not time to repeat yet. */
    WAIT,
}

/**
 * The compositor's arithmetic, kept away from OpenGL so it runs in a plain JVM test: what to do at a tick, which
 * part of the source texture to sample, where on the encoder's frame to draw, and the time on each frame.
 */
@Suppress("TooManyFunctions") // one small pure function per sum the compositor makes, each with its own test
object CompositorMath {

    /**
     * The tick: a new picture is drawn at once; with nothing new, the last one is repeated at the tick NEAREST
     * to [idleMs] after the previous frame, so the idle rate is the one asked for and not the next tick after it
     * (at 16 ticks a second, 250 ms falls between the 4th tick, 248 ms, and the 5th, 310 ms).
     *
     * [sinceLastFrameMs] is the nominal time since the last frame went out: a whole number of [activeMs] ticks.
     */
    fun decide(newImage: Boolean, sinceLastFrameMs: Long, activeMs: Long, idleMs: Long): TickAction = when {
        newImage -> TickAction.DRAW
        2 * sinceLastFrameMs + activeMs >= 2 * idleMs -> TickAction.REPEAT
        else -> TickAction.WAIT
    }

    /**
     * How long to wait before drawing a new picture at once, outside the tick (the rider's input is fresh), or null for the tick to
     * do it: with no new picture, no frame drawn yet, or the burst off ([gapMs] 0). A frame is never closer than [gapMs] to the last.
     */
    fun burstWaitMs(newImage: Boolean, lastFrameAtMs: Long, nowMs: Long, gapMs: Long): Long? {
        if (!newImage || gapMs <= 0 || lastFrameAtMs <= 0) return null
        return (lastFrameAtMs + gapMs - nowMs).coerceIn(0, gapMs)
    }

    /**
     * How long to wait before drawing again at once because the info ring changed: what is left of [gapMs] since the last frame, none when no
     * frame has gone yet. The gap keeps a ring that flickers on and off from flooding the dash with frames.
     */
    fun redrawWaitMs(lastFrameAtMs: Long, nowMs: Long, gapMs: Long = RING_REDRAW_GAP_MS): Long =
        if (lastFrameAtMs <= 0) 0L else (lastFrameAtMs + gapMs - nowMs).coerceIn(0L, gapMs)

    /** The least time between two frames drawn because the info ring changed: the same as the rider's input burst. */
    const val RING_REDRAW_GAP_MS = 80L

    /** How often the compositor's clock looks at the picture to read the screen from it: every 50 ms while somebody listens and there is a picture. */
    const val LOOK_POLL_MS = 50L

    /** With nobody listening (the card mode) or no picture yet there is nothing to read; the clock wakes less often (audit of 26/10/2026). */
    const val LOOK_IDLE_POLL_MS = 250L

    fun lookPollMs(listening: Boolean, hasImage: Boolean): Long = if (listening && hasImage) LOOK_POLL_MS else LOOK_IDLE_POLL_MS

    /**
     * The black bands of [cover] as rectangles of the [w]×[h] composition area in GL pixels (the origin at the bottom left), empty bands left out.
     */
    fun coverRects(cover: FrameFit.Cover, w: Int = DashGlass.FRAME_W, h: Int = DashGlass.FRAME_H): List<RectPx> = buildList {
        if (cover.top > 0) add(RectPx(0, h - cover.top, w, cover.top))
        if (cover.bottom > 0) add(RectPx(0, 0, w, cover.bottom))
        if (cover.left > 0) add(RectPx(0, 0, cover.left, h))
        if (cover.right > 0) add(RectPx(w - cover.right, 0, cover.right, h))
    }

    /** The edges of an overlay on a [areaW]×[areaH] output in OpenGL's -1..1 coordinates (y up). */
    data class Quad(val left: Float, val right: Float, val top: Float, val bottom: Float)

    /**
     * An overlay [box] (pixels from the top left of the output, its size the overlay's) on a [areaW]×[areaH] output (1:1: the pixel is the output's own, so a
     * debug build's 528×304 keeps every overlay at the pixel it has in the dash's 526×300 frame).
     */
    fun overlayQuad(box: RectPx, areaW: Int, areaH: Int): Quad = Quad(
        left = box.x.toFloat() / areaW * 2f - 1f,
        right = box.right.toFloat() / areaW * 2f - 1f,
        top = 1f - box.y.toFloat() / areaH * 2f,
        bottom = 1f - box.bottom.toFloat() / areaH * 2f,
    )

    /** The blurred background is drawn on a copy of the frame this many times smaller on each side. */
    const val BACKDROP_DIVISOR = 4

    /** How much of its light the blurred background keeps: a little darker than the picture, so the picture stands out from it. */
    const val BACKDROP_KEEP = 0.92f

    /**
     * Whether a picture drawn in [dest] (rows counted from the top) leaves some of the [frameW]×[frameH] composition area bare: the border that is black
     * without the blurred background.
     */
    fun leavesBorder(dest: RectPx, frameW: Int = DashGlass.FRAME_W, frameH: Int = DashGlass.FRAME_H): Boolean =
        dest.x > 0 || dest.y > 0 || dest.right < frameW || dest.bottom < frameH

    /**
     * The part of [source] the blurred background is made from: the largest one of the frame's own shape, in the middle of [source] (the
     * background fills the whole frame, so it is taken without stretching the picture).
     */
    fun backdropSource(source: RectPx, areaW: Int = DashGlass.FRAME_W, areaH: Int = DashGlass.FRAME_H): RectPx {
        val frameW = areaW.toLong()
        val frameH = areaH.toLong()
        return if (source.w * frameH >= source.h * frameW) {
            val w = (source.h * frameW / frameH).toInt().coerceIn(1, source.w)
            RectPx(source.x + (source.w - w) / 2, source.y, w, source.h)
        } else {
            val h = (source.w * frameH / frameW).toInt().coerceIn(1, source.h)
            RectPx(source.x, source.y + (source.h - h) / 2, source.w, h)
        }
    }

    /**
     * The extra pass of the blurred background: the [window] of the source drawn into [small] (a framebuffer of a quarter the frame's side,
     * viewport in its own pixels), then that copy blurred, kept to [keep] of its light and stretched over [frame], the whole dash frame
     * (GL pixels), before the picture is drawn in its place.
     */
    data class Backdrop(val window: TexWindow, val small: RectPx, val frame: RectPx, val keep: Float)

    /**
     * The blurred background of a picture whose [source] (pixels of Android Auto's [sourceW]×[sourceH] frame) is drawn in [dest], or null
     * for none: with the setting off ([enabled] false), or with the picture covering the whole frame there is no border to fill and
     * nothing is drawn, nothing created.
     */
    @Suppress("ComplexCondition", "LongParameterList") // the four ways to draw nothing: the setting off, no border, no picture wide or high
    fun backdrop(
        enabled: Boolean, source: RectPx, dest: RectPx, sourceW: Int, sourceH: Int,
        areaW: Int = DashGlass.FRAME_W, areaH: Int = DashGlass.FRAME_H,
    ): Backdrop? {
        if (!enabled || !leavesBorder(dest, areaW, areaH) || sourceW <= 0 || sourceH <= 0) return null
        val d = BACKDROP_DIVISOR
        return Backdrop(
            window = texWindow(backdropSource(source, areaW, areaH), sourceW, sourceH),
            small = RectPx(0, 0, (areaW + d - 1) / d, (areaH + d - 1) / d),
            frame = RectPx(0, 0, areaW, areaH),
            keep = BACKDROP_KEEP,
        )
    }

    /**
     * A rectangle of the source picture in texture coordinates: `u = offsetU + s × scaleU`, `v = offsetV + t ×
     * scaleV`, for s and t running 0..1 across the drawn quad, left to right and BOTTOM to top.
     */
    data class TexWindow(val offsetU: Float, val offsetV: Float, val scaleU: Float, val scaleV: Float)

    /**
     * Where [source] (pixels, rows counted from the top) sits in a [frameW]×[frameH] picture, in texture
     * coordinates. Texture rows are counted from the bottom, hence the flip of the vertical offset.
     */
    fun texWindow(source: RectPx, frameW: Int, frameH: Int): TexWindow = TexWindow(
        offsetU = source.x.toFloat() / frameW,
        offsetV = (frameH - source.bottom).toFloat() / frameH,
        scaleU = source.w.toFloat() / frameW,
        scaleV = source.h.toFloat() / frameH,
    )

    /**
     * Where Android Auto's whole [sourceW]×[sourceH] picture goes on the phone's preview, a [canvasW]×[canvasH]
     * surface: as big as fits, centred, on black. Centred, so the same rectangle in
     * OpenGL coordinates. Nothing with no surface yet.
     */
    fun previewViewport(sourceW: Int, sourceH: Int, canvasW: Int, canvasH: Int): RectPx {
        if (minOf(sourceW, sourceH, canvasW, canvasH) <= 0) return RectPx(0, 0, 0, 0)
        val scale = minOf(canvasW.toDouble() / sourceW, canvasH.toDouble() / sourceH)
        val w = Math.round(sourceW * scale).toInt()
        val h = Math.round(sourceH * scale).toInt()
        return RectPx((canvasW - w) / 2, (canvasH - h) / 2, w, h)
    }

    /** [dest] (rows counted from the top of the dash's frame) as an OpenGL viewport: rows counted from the bottom. */
    fun viewport(dest: RectPx, frameH: Int = DashGlass.FRAME_H): RectPx = RectPx(dest.x, frameH - dest.bottom, dest.w, dest.h)

    /**
     * The time stamped on a frame: the video clock [ptsMs] in whole milliseconds, and always later than
     * [previousStampMs]. The encoder drops a frame whose time is not after the one before.
     */
    fun stamp(previousStampMs: Long, ptsMs: Double): Long = maxOf(previousStampMs + 1, ptsMs.toLong())
}
