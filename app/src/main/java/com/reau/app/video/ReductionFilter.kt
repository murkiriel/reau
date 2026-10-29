package com.reau.app.video

/**
 * How Android Auto's picture is shrunk to the dash's frame. [CURRENT] is what the compositor always does; the rider has
 * no choice, and the others exist only for the bench (androidTest `ReductionQualityBench`).
 */
data class ReductionFilter(val kernel: Kernel, val sharpen: Float = 0f) {
    enum class Kernel {
        /** The graphics chip's own two-by-two average (GL_LINEAR). */
        BILINEAR,

        /** Nine bilinear samples over a destination pixel's footprint, weighted 1-2-1: a soft area average. */
        SOFT,

        /** Catmull-Rom over sixteen source pixels. */
        CUBIC,

        /**
         * Sixteen bilinear samples (4×4) spread evenly over a destination pixel's footprint: very nearly the average of the whole area. For the tiny copy the
         * picture classifier reads (`CompositorGl.look`), which was trained on area averages; one bilinear fetch of a 12×12 block read only 2×2 of its pixels.
         */
        BOX,
    }

    /** True for the filter that needs nothing but the plain program. */
    val isCurrent: Boolean get() = kernel == Kernel.BILINEAR && sharpen == 0f

    companion object {
        val CURRENT = ReductionFilter(Kernel.BILINEAR)
    }
}
