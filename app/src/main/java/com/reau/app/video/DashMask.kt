package com.reau.app.video

/**
 * The dash's mask over Android Auto's picture on the phone: a switchable overlay that simulates how Android Auto
 * will look on the dash while the fit is adjusted on the phone. The "Tela do Android Auto" draws Android Auto's
 * whole frame ([CompositorMath.previewViewport]); the fit of the settings ([FrameFit.Mapping]) takes part of it to
 * the dash's 526×300 frame, of which the rider sees the round glass ([DashGlass]), with the dash's own manoeuvre
 * bubble (the dash's ring) drawn over the video. This works out where those fall on the phone's screen, so the screen
 * can dim what never reaches the glass and draw the ring where it covers the picture. No Android in it: tested on the
 * JVM.
 */
object DashMask {
    /** A circle in the preview screen's pixels. */
    data class Ring(val cx: Float, val cy: Float, val r: Float) {
        fun contains(x: Float, y: Float): Boolean = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
    }

    /** A rectangle in the preview screen's pixels. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

        /** The part of this box inside [other]; an empty box (right < left or bottom < top) when they do not meet. */
        fun meet(other: Box): Box = Box(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
    }

    /**
     * The dash's manoeuvre bubble in its frame: a circle of radius 68 at (79, 228), measured by homography on four photos of a real
     * dash (about 3 px of error), the gold outline included: the radius reaches the outer edge of that outline.
     */
    const val BUBBLE_X = 79f
    const val BUBBLE_Y = 228f
    const val BUBBLE_R = 68f

    /**
     * Where the dash falls on the preview screen: [picture] is Android Auto's whole frame as the preview draws it,
     * [frame] the dash's 526×300 frame, [content] the part of it that gets Android Auto's picture (the rest of the
     * frame goes black to the dash), [glass] the round glass and [bubble] the dash's ring.
     */
    data class Mask(
        val picture: Box,
        val frame: Box,
        val content: Box,
        val glass: Ring,
        val bubble: Ring,
        /** The black bands over the dash's frame ([FrameFit.Cover]), on the screen and within the picture. */
        val covers: List<Box> = emptyList(),
    ) {
        /** What of the picture the rider can see: inside the frame, the content and the glass. */
        val shownBox: Box get() = picture.meet(frame).meet(content)

        /** Whether the point ([x], [y]) of the screen reaches the dash's glass. */
        fun visible(x: Float, y: Float): Boolean = shownBox.contains(x, y) && glass.contains(x, y)

        /** Whether the point is under the dash's own bubble (seen, but covered by the ring). */
        fun underBubble(x: Float, y: Float): Boolean = visible(x, y) && bubble.contains(x, y)
    }

    private val RectPx.empty: Boolean get() = w <= 0 || h <= 0

    /**
     * The mask for [mapping] (the settings' fit, made for the composition [area]) with Android Auto's frame [sourceW]×[sourceH] drawn on a
     * [canvasW]×[canvasH] screen; null with no screen yet or an empty fit. The glass and the bubble stay at their pixels from the top left in any area.
     */
    @Suppress("LongParameterList") // the fit, the source's size, the screen's size and the area: all four are needed and none belongs with another
    fun of(
        mapping: FrameFit.Mapping, sourceW: Int, sourceH: Int, canvasW: Int, canvasH: Int,
        area: com.reau.app.dash.DashConfig.FrameSize = com.reau.app.dash.DashConfig.FULL_FRAME,
    ): Mask? {
        val view = CompositorMath.previewViewport(sourceW, sourceH, canvasW, canvasH)
        if (view.empty || mapping.dest.empty) return null // no screen yet, or an empty fit
        val kx = view.w.toFloat() / sourceW // screen pixels per Android Auto pixel
        val ky = view.h.toFloat() / sourceH
        val sx = mapping.source.w.toFloat() / mapping.dest.w // Android Auto pixels per dash pixel
        val sy = mapping.source.h.toFloat() / mapping.dest.h
        fun x(dashX: Float) = view.x + (mapping.source.x + (dashX - mapping.dest.x) * sx) * kx
        fun y(dashY: Float) = view.y + (mapping.source.y + (dashY - mapping.dest.y) * sy) * ky
        val perDashPixel = sx * kx
        val picture = Box(view.x.toFloat(), view.y.toFloat(), view.right.toFloat(), view.bottom.toFloat())
        val c = mapping.cover
        val frameW = area.width.toFloat()
        val frameH = area.height.toFloat()
        val covers = buildList {
            if (c.top > 0) add(Box(x(0f), y(0f), x(frameW), y(c.top.toFloat())))
            if (c.bottom > 0) add(Box(x(0f), y(frameH - c.bottom), x(frameW), y(frameH)))
            if (c.left > 0) add(Box(x(0f), y(0f), x(c.left.toFloat()), y(frameH)))
            if (c.right > 0) add(Box(x(frameW - c.right), y(0f), x(frameW), y(frameH)))
        }.map { it.meet(picture) }.filter { it.right > it.left && it.bottom > it.top }
        return Mask(
            picture = Box(view.x.toFloat(), view.y.toFloat(), view.right.toFloat(), view.bottom.toFloat()),
            frame = Box(x(0f), y(0f), x(frameW), y(frameH)),
            content = Box(x(mapping.dest.x.toFloat()), y(mapping.dest.y.toFloat()), x(mapping.dest.right.toFloat()), y(mapping.dest.bottom.toFloat())),
            glass = Ring(x(DashGlass.CENTER_X.toFloat()), y(DashGlass.CENTER_Y.toFloat()), DashGlass.RADIUS * perDashPixel),
            bubble = Ring(x(BUBBLE_X), y(BUBBLE_Y), BUBBLE_R * perDashPixel),
            covers = covers,
        )
    }
}
