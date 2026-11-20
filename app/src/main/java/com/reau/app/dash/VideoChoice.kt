package com.reau.app.dash

import com.reau.app.dash.DashConfig.FrameSize

/**
 * The three independent choices of the video to the dash that a debug build offers (plan 32, 20/11/2026): the pace in frames a second, the
 * bitrate ceiling and the picture size. They are mixed freely: none of them sets another. A release build runs none of them and stays at
 * [DashConfig.PROFILE_FULL_FPS], [DashConfig.PROFILE_FULL_BPS] and [DashConfig.FULL_FRAME]. Pure, so the JVM tests cover it.
 */
object VideoChoice {
    /** The paces offered, frames a second: 4 to 12, by 2 (the even steps of [DashConfig.snapStreamFps]). */
    val FPS_OPTIONS: List<Int> = (4..12 step 2).toList()
    const val DEFAULT_FPS = 6

    /** The bitrate ceilings offered, kbps: 400 to 1000 by 100 (200 kbps looked bad on the dash, so 400 is the floor). */
    const val CAP_MIN_KBPS = 400
    const val CAP_MAX_KBPS = 1000
    const val CAP_STEP_KBPS = 100
    const val DEFAULT_CAP_KBPS = 400
    val CAP_OPTIONS_KBPS: List<Int> = (CAP_MIN_KBPS..CAP_MAX_KBPS step CAP_STEP_KBPS).toList()

    /**
     * The pictures offered, all multiples of 16 on both sides (the dash decodes without cropping them). 528x304 is the dash's native 526x300 aligned
     * to 16: a slight enlargement of what is composed; the others are smaller, down to 448x256. The compositor always composes in
     * [DashConfig.FULL_FRAME] and only scales on the way to the encoder's surface; the dash stretches the picture over its whole screen.
     */
    val FRAMES: List<FrameSize> = listOf(
        FrameSize(528, 304), FrameSize(512, 288), FrameSize(496, 288), FrameSize(480, 272), FrameSize(464, 272), FrameSize(448, 256),
    )
    val DEFAULT_FRAME: FrameSize = FRAMES.first()

    /** The load, in macroblocks a second, that the dash showed it holds: 6 fps in the full picture (627 macroblocks), the point measured on the bike with no trouble. */
    const val SAFE_LOAD_MB_PER_SEC = 3762

    /** The macroblocks of the full picture, the 6 fps / 400 kbps point that [recommendedKbps] keeps the bits per macroblock of. */
    private const val REFERENCE_MB = 627
    private const val REFERENCE_FPS = 6
    private const val REFERENCE_KBPS = 400

    /** Macroblocks of [frame], 16x16 each, a partial one counted whole (526x300 is 33x19, the same as 528x304). */
    fun macroblocks(frame: FrameSize): Int = (frame.width + MB - 1) / MB * ((frame.height + MB - 1) / MB)

    /** The load of [fps] frames a second of [frame], in macroblocks a second: the dash decodes H.264 in software, and this is what it pays for. */
    fun loadMbPerSec(fps: Int, frame: FrameSize): Int = fps * macroblocks(frame)

    /** Whether the combination asks for more than the dash showed it holds ([SAFE_LOAD_MB_PER_SEC]): allowed, but the rider is warned. */
    fun heavy(fps: Int, frame: FrameSize): Boolean = loadMbPerSec(fps, frame) > SAFE_LOAD_MB_PER_SEC

    /**
     * The bitrate ceiling, in kbps, that this pace and picture ask for: only a guide, nothing is forced. It keeps the bits per macroblock per frame
     * of the point the owner approved on the bike, 6 fps / 400 kbps in the full picture (627 macroblocks):
     * `400 x (fps / 6) x (macroblocks / 627)`, rounded UP to a hundred and never under 400 (the floor of the slider) or over its top.
     * 8 fps in 528x304 gives 600, 7 gives 500, 8 fps in 448x256 gives 400, and 6 fps gives 400 in any picture. The encoder uses 65% to 81% of the
     * ceiling it is given (bench of 20/11/2026: 4 fps 260 of 400, 6 fps 293 of 400, 8 fps 460 of 600), so what it really spends says nothing about
     * what a combination needs: that is why the guide scales the approved point instead of the measured use. Integer arithmetic, so a value that is
     * exactly on a hundred is not pushed to the next one.
     */
    fun recommendedKbps(fps: Int, frame: FrameSize): Int {
        val numerator = REFERENCE_KBPS.toLong() * fps * macroblocks(frame)
        val denominator = REFERENCE_FPS.toLong() * REFERENCE_MB * CAP_STEP_KBPS
        val hundreds = (numerator + denominator - 1) / denominator
        return (hundreds * CAP_STEP_KBPS).toInt().coerceIn(CAP_MIN_KBPS, CAP_MAX_KBPS)
    }

    /** [kbps] moved to the nearest offered ceiling (400..1000 by 100). */
    fun snapCapKbps(kbps: Int): Int =
        ((kbps + CAP_STEP_KBPS / 2) / CAP_STEP_KBPS * CAP_STEP_KBPS).coerceIn(CAP_MIN_KBPS, CAP_MAX_KBPS)

    /** The pace in the offered range, on its even steps; a value outside it (an old or damaged one) reads as the nearest end, an odd one as the even pace below it. */
    fun clampFps(fps: Int): Int = DashConfig.snapStreamFps(fps).coerceAtMost(FPS_OPTIONS.last())

    /** The offered picture with this id ("528x304"), or null; [DashConfig.FULL_FRAME] is not offered, it is the release build's. */
    fun offeredFrame(id: String?): FrameSize? = FRAMES.firstOrNull { it.id == id }

    /** One mix of the three choices. */
    data class Choice(val fps: Int, val capKbps: Int, val frame: FrameSize)

    /**
     * What a stored profile id of the first plan-32 build means in the three choices: "4" is 4 fps / 400 / 528x304, "6" is 6 / 400 / 528x304,
     * "8" is 8 / 600 / 528x304, "8m" is 8 / 600 / 448x256 and "12" is 12 / 800 / 448x256 (its pace and bitrate; its 368x208 picture is gone, 448x256 is the closest). An id that is
     * not one of these reads as null.
     */
    fun fromLegacyProfile(id: String?): Choice? = when (id) {
        "4" -> Choice(4, 400, DEFAULT_FRAME)
        "6" -> Choice(6, 400, DEFAULT_FRAME)
        "8" -> Choice(8, 600, DEFAULT_FRAME)
        "8m" -> Choice(8, 600, FRAMES.last())
        "12" -> Choice(12, 800, FRAMES.last())
        else -> null
    }

    /** The legacy profile a stored pace alone (`video_pace_fps`) stood for: the first profile of that pace; any other pace was "4". */
    fun fromLegacyPace(fps: Int): Choice = fromLegacyProfile(fps.toString()) ?: checkNotNull(fromLegacyProfile("4"))

    private const val MB = 16
}
