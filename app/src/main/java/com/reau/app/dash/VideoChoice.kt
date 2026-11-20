package com.reau.app.dash

import com.reau.app.dash.DashConfig.FrameSize

/**
 * The choices of the video to the dash that a debug build offers (plan 32, 20/11/2026): the pace in frames a second and the bitrate ceiling. They are
 * mixed freely: neither sets the other. The picture is no choice any more: it is [MANUAL_FRAME] (measured on the bike: the dash does not stretch a
 * smaller picture, and changing the size with the video running freezes its decoder until the ignition is cycled). A release build runs none of them
 * and stays at [DashConfig.PROFILE_FULL_FPS], [DashConfig.PROFILE_FULL_BPS] and [DashConfig.FULL_FRAME]. Pure, so the JVM tests cover it.
 */
object VideoChoice {
    /** The paces offered, frames a second: 4, 6 and 8 (12 made the dash ignore the buttons, and 10 was never better than 8; the even steps of [DashConfig.snapStreamFps]). */
    val FPS_OPTIONS: List<Int> = listOf(4, 6, 8)
    const val DEFAULT_FPS = 6

    /** The bitrate ceilings offered, kbps: 400 to 1000 by 100 (200 kbps looked bad on the dash, so 400 is the floor). */
    const val CAP_MIN_KBPS = 400
    const val CAP_MAX_KBPS = 1000
    const val CAP_STEP_KBPS = 100
    const val DEFAULT_CAP_KBPS = 400
    val CAP_OPTIONS_KBPS: List<Int> = (CAP_MIN_KBPS..CAP_MAX_KBPS step CAP_STEP_KBPS).toList()

    /**
     * The picture of a debug build's stream, fixed: 528x304 is the dash's native 526x300 aligned to 16 (multiples of 16 on both sides, which the dash decodes
     * without cropping). The compositor always composes in [DashConfig.FULL_FRAME] and only places it on the way to the encoder's surface
     * ([com.reau.app.video.CompositorMath.toOutput]). If no encoder takes it the stream falls back to [DashConfig.FULL_FRAME].
     */
    val MANUAL_FRAME: FrameSize = FrameSize(528, 304)

    /** The pace above which the reserve icon of the dash may flicker (measured on the bike: 8 fps flickers it, 6 fps is smooth); the reserve itself caps at [DashConfig.RESERVE_CAP_FPS]. */
    fun mayFlickerReserveIcon(fps: Int): Boolean = fps > DashConfig.RESERVE_CAP_FPS

    private const val REFERENCE_FPS = 6
    private const val REFERENCE_KBPS = 400

    /** Macroblocks of [frame], 16x16 each, a partial one counted whole (526x300 is 33x19, the same as 528x304). */
    fun macroblocks(frame: FrameSize): Int = (frame.width + MB - 1) / MB * ((frame.height + MB - 1) / MB)

    /** The load of [fps] frames a second of [frame], in macroblocks a second: the dash decodes H.264 in software, and this is what it pays for. Only for the ride log. */
    fun loadMbPerSec(fps: Int, frame: FrameSize): Int = fps * macroblocks(frame)

    /**
     * The bitrate ceiling, in kbps, that this pace asks for: only a guide, nothing is forced. It keeps the bits per frame of the point the owner approved on
     * the bike, 6 fps / 400 kbps in [MANUAL_FRAME] (627 macroblocks): `400 x (fps / 6)`, rounded UP to a hundred and never under 400 (the floor of the slider) or over
     * its top. 8 fps gives 600, 7 gives 500, 4 and 6 give 400. The encoder uses 65% to 81% of the ceiling it is given (bench of 20/11/2026: 4 fps 260 of 400,
     * 6 fps 293 of 400, 8 fps 460 of 600), so what it really spends says nothing about what a pace needs: that is why the guide scales the approved point instead of the
     * measured use. Integer arithmetic, so a value that is exactly on a hundred is not pushed to the next one.
     */
    fun recommendedKbps(fps: Int): Int {
        val numerator = REFERENCE_KBPS.toLong() * fps
        val denominator = REFERENCE_FPS.toLong() * CAP_STEP_KBPS
        val hundreds = (numerator + denominator - 1) / denominator
        return (hundreds * CAP_STEP_KBPS).toInt().coerceIn(CAP_MIN_KBPS, CAP_MAX_KBPS)
    }

    /** [kbps] moved to the nearest offered ceiling (400..1000 by 100). */
    fun snapCapKbps(kbps: Int): Int =
        ((kbps + CAP_STEP_KBPS / 2) / CAP_STEP_KBPS * CAP_STEP_KBPS).coerceIn(CAP_MIN_KBPS, CAP_MAX_KBPS)

    /** The pace in the offered range, on its even steps; a value outside it (an old or damaged one, a stored 10 or 12) reads as the nearest end, an odd one as the even pace below it. */
    fun clampFps(fps: Int): Int = DashConfig.snapStreamFps(fps).coerceAtMost(FPS_OPTIONS.last())

    /** One mix of the two choices. */
    data class Choice(val fps: Int, val capKbps: Int)

    /**
     * What a stored profile id of the first plan-32 build means in the two choices: "4" is 4 fps / 400, "6" is 6 / 400, "8" and "8m" are 8 / 600 and "12" is
     * 8 / 800 (12 fps is gone: 8 is the nearest pace, with its bitrate). The picture those ids carried is gone too. An id that is not one of these reads as null.
     */
    fun fromLegacyProfile(id: String?): Choice? = when (id) {
        "4" -> Choice(4, 400)
        "6" -> Choice(6, 400)
        "8", "8m" -> Choice(8, 600)
        "12" -> Choice(FPS_OPTIONS.last(), 800)
        else -> null
    }

    /** The legacy profile a stored pace alone (`video_pace_fps`) stood for: the first profile of that pace; any other pace was "4". */
    fun fromLegacyPace(fps: Int): Choice = fromLegacyProfile(fps.toString()) ?: checkNotNull(fromLegacyProfile("4"))

    private const val MB = 16
}
