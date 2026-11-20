package com.reau.app.dash

/**
 * The limits of the video stream to the dash, as found on a real dash.
 * The rules that use them are in `com.reau.app.stream` (DashLogic, StreamGovernor).
 */
object DashConfig {
    /** Range of the stream rate. At 30 the dash went silent; 4 is the conservative rate it shows well. */
    const val MIN_STREAM_FPS = 4
    const val MAX_STREAM_FPS = 30
    const val STREAM_FPS_STEP = 2
    const val DEFAULT_STREAM_FPS = 4

    /** Hard ceiling for every bitrate path: the value validated on this panel; 1.2 Mbps made the dash hitch. */
    const val MAX_STREAM_BITRATE_BPS = 1_024_000
    const val DEFAULT_STREAM_BITRATE_BPS = MAX_STREAM_BITRATE_BPS

    /** The bitrate stops: 200..900 kbps by 100, then the ceiling. */
    val STREAM_BITRATE_STEPS = (200_000..900_000 step 100_000).toList() + MAX_STREAM_BITRATE_BPS

    /** Nearest stop; zero or negative reads as the ceiling. */
    fun snapStreamBitrate(bps: Int): Int =
        if (bps <= 0) MAX_STREAM_BITRATE_BPS else STREAM_BITRATE_STEPS.minBy { kotlin.math.abs(it - bps) }

    /** Clamps into [MIN_STREAM_FPS]..[MAX_STREAM_FPS] and rounds to an even value; an odd value rounds DOWN
     *  (7 → 6), the safer side. */
    fun snapStreamFps(fps: Int): Int {
        val clamped = fps.coerceIn(MIN_STREAM_FPS, MAX_STREAM_FPS)
        return clamped - (clamped - MIN_STREAM_FPS) % STREAM_FPS_STEP
    }

    /**
     * What every build runs, with nothing to choose (owner's decision of 20/11/2026, after the ride that showed the dash does not stretch a smaller picture,
     * freezes its decoder when the size changes with the video running, ignores the buttons at 12 fps and flickers the reserve icon at 8): profile A is
     * 6 fps at a 400 kbps ceiling, constant, a keyframe every second, in the [VIDEO_FRAME] picture. Profile B (the navigation's reduced tier) is half of A's
     * PACE, 3 fps, at the same ceiling: the bitrate is never halved by a tier, by a still picture or by the start ramp. The encoder is built once for A's
     * pace; B only feeds it fewer frames, so a change of tier never replaces the encoder. [PROFILE_FULL_FPS] is also the most any screen is paced at.
     */
    const val PROFILE_FULL_FPS = 6
    const val PROFILE_FULL_BPS = 400_000
    const val PROFILE_REDUCED_FPS = 3
    const val PROFILE_KEYFRAME_SEC = 1

    /** The pace on the screens of Android Auto that are not the navigation's (its app menu and the other apps) in every build ([com.reau.app.settings.REauSettings.lightScreenFps]); never above [PROFILE_FULL_FPS]. */
    const val LIGHT_SCREEN_FPS = PROFILE_FULL_FPS

    /** The size of a picture the dash decodes, in pixels. [id] is its stable text, "526x300", what the settings keep the picture as. */
    data class FrameSize(val width: Int, val height: Int) {
        val id: String get() = "${width}x$height"
    }

    /**
     * The dash's own frame, 526x300: the coordinates everything drawn over the picture keeps (the mask, the ring, the title, the overlays), and the picture
     * the stream falls back to when no encoder takes [VIDEO_FRAME] ([com.reau.app.stream.VideoLoop.Settings.atFullFrame]).
     */
    val FULL_FRAME = FrameSize(526, 300)

    /**
     * The picture of the stream, in every build and with no choice: 528x304 is the dash's 526x300 aligned to 16 (multiples of 16 on both sides, which the dash
     * decodes without cropping), composed directly, 1:1, anchored at the top left: the 2 columns and 4 rows more only extend the picture and its blurred
     * background. Measured on the bike: the dash does not stretch a smaller picture, and changing the size with the video running freezes its decoder.
     */
    val VIDEO_FRAME = FrameSize(528, 304)

    private const val MB = 16

    /** Macroblocks of [frame], 16x16 each, a partial one counted whole (526x300 is 33x19, the same as 528x304). */
    fun macroblocks(frame: FrameSize): Int = (frame.width + MB - 1) / MB * ((frame.height + MB - 1) / MB)

    /** The load of [fps] frames a second of [frame], in macroblocks a second: the dash decodes H.264 in software, and this is what it pays for. Only for the ride log. */
    fun loadMbPerSec(fps: Int, frame: FrameSize): Int = fps * macroblocks(frame)

    /**
     * "Automatic", as a stored setting may still say: the stream used to pick 1 or 2 s itself. Nothing picks any more (cut on 14/11/2026):
     * the settings turn it into 1 s when they are read, and the stream reads a 0 as 1 s too.
     */
    const val KEYFRAME_INTERVAL_AUTO = 0
    val KEYFRAME_INTERVAL_OPTIONS = listOf(KEYFRAME_INTERVAL_AUTO, 1, 2)

    /** Nearest offered value. */
    fun snapKeyframeIntervalSec(sec: Int): Int = KEYFRAME_INTERVAL_OPTIONS.minBy { kotlin.math.abs(it - sec) }
    const val DEFAULT_KEYFRAME_INTERVAL_SEC = KEYFRAME_INTERVAL_AUTO

    /** How slow the video's clock may be set to run, in percent (0 = on time). 5 is what an earlier frame loop did
     *  by accident (62 ms stamped, 65 ms real), when the dash showed no lag. */
    val STREAM_CLOCK_SLOW_OPTIONS = listOf(0, 3, 5, 8)
}
