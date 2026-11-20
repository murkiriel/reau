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

    /** The id of the default profile, 4 fps ([STREAM_PROFILES]). */
    const val DEFAULT_PROFILE_ID = "4"

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
     * The two stream profiles, the only ones: profile 1 is 4 fps at 400 kbps, constant, a keyframe every
     * second; profile 2 is half of it, 2 fps at 200 kbps. The encoder is built once for profile 1; profile 2 only feeds it
     * fewer frames, so a change of profile never replaces the encoder.
     */
    const val PROFILE_FULL_FPS = 4
    const val PROFILE_FULL_BPS = 400_000
    const val PROFILE_REDUCED_FPS = 2

    /** The pace on the screens of Android Auto that are not the navigation's (its app menu and the other apps) in every build ([com.reau.app.settings.REauSettings.lightScreenFps]); the encoder stays as built. */
    const val LIGHT_SCREEN_FPS = 6
    const val PROFILE_REDUCED_BPS = 200_000
    const val PROFILE_KEYFRAME_SEC = 1

    /**
     * The experimental lower bitrate ([com.reau.app.settings.REauSettings.experimentAdaptiveBitrate]): the target of the normal stream, 200 kbps instead of the
     * profile's 400, and the least the live adjustment ([com.reau.app.stream.BitrateAdapter]) goes down to.
     */
    const val EXPERIMENT_TARGET_BPS = 200_000
    const val EXPERIMENT_FLOOR_BPS = 64_000

    /** The size of a picture the dash decodes, in pixels. */
    data class FrameSize(val width: Int, val height: Int)

    /**
     * The dash's frame, 526x300: what everything is composed in (the mask, the ring, the title, the overlays), and what the encoder is built for
     * unless the profile asks for a smaller picture.
     */
    val FULL_FRAME = FrameSize(526, 300)

    /**
     * The smaller picture of the 12 fps profile (plan 32, phase 2): 368x208, multiples of 16 and of nearly the same shape as [FULL_FRAME]. The dash
     * decodes H.264 in software, so fewer pixels per frame is what lets it take 12 of them a second; the dash stretches the picture over its whole
     * screen. Only the encoder's output has this size: the compositor still composes in [FULL_FRAME] and shrinks the result on the way out.
     */
    val REDUCED_FRAME = FrameSize(368, 208)

    /**
     * The medium picture of the 8 fps "8m" profile (plan 32): 448x256, 28x16 macroblocks, no crop, nearly the shape of [FULL_FRAME]. It is there to
     * tell, on the bike, whether the shake of the reserve icon comes from the load (macroblocks a second) or from the rate (frames a second), with
     * the same 8 fps as the full frame and 0.73 of its pixels. Handled as [REDUCED_FRAME] is: the compositor shrinks what it composed.
     */
    val MEDIUM_FRAME = FrameSize(448, 256)

    /**
     * One profile of the stream and what goes with it. [id] is its stable name: what the debug build's choice is stored by, since two profiles
     * may share a pace (8 fps has two pictures). Profile B is always the EXACT half of A, in the pace and in the bitrate (so 6 fps gives
     * 3, the one odd rate that [snapStreamFps] would not give). The encoder is built once for A's rate; B only feeds it fewer frames, at the
     * lower bitrate, set live. A profile here is a choice of the debug build ([PROFILE_IDS]); the release build stays at [DEFAULT_PROFILE_ID].
     * [frame] is the size the encoder is built for, A and B alike; it never changes during a stream.
     */
    data class StreamProfile(val id: String, val fullFps: Int, val fullBps: Int, val frame: FrameSize = FULL_FRAME) {
        val reducedFps: Int get() = fullFps / 2
        val reducedBps: Int get() = fullBps / 2
    }

    /**
     * The table of profiles, the only place that knows which bitrate and which picture go with which pace (plan 32, 19/11/2026): "4" is 4 fps at
     * 400 kbps (B: 2 at 200), "6" 6 fps at 400 kbps (B: 3 at 200), "8" 8 fps at 600 kbps (B: 4 at 300), "8m" the same 8 fps and bitrates in the
     * medium picture, and "12" 12 fps at 800 kbps in the smaller picture (B: 6 at 400). The first is the default and what a release build runs.
     * The ids are stored: they are never renamed or reused.
     */
    val STREAM_PROFILES = listOf(
        StreamProfile(DEFAULT_PROFILE_ID, PROFILE_FULL_FPS, PROFILE_FULL_BPS),
        StreamProfile("6", 6, 400_000),
        StreamProfile("8", 8, 600_000),
        StreamProfile("8m", 8, 600_000, MEDIUM_FRAME),
        StreamProfile("12", 12, 800_000, REDUCED_FRAME),
    )

    /** The ids of the profiles that can be chosen, in the order of [STREAM_PROFILES]. */
    val PROFILE_IDS: List<String> = STREAM_PROFILES.map { it.id }

    /**
     * What a stream falls back to when no encoder of the phone takes its smaller picture: the profile "8", 8 fps at the [FULL_FRAME] (so the
     * stream is never left without an image), see [com.reau.app.stream.VideoLoop.Settings.atFullFrame]. For "8m" that is the same pace on the
     * full frame; for "12" it is a slower pace.
     */
    const val FALLBACK_PROFILE_ID = "8"

    /** The profile of [id]; an id that is not in the table (an old or damaged value) reads as the default, "4". */
    fun profileById(id: String?): StreamProfile = STREAM_PROFILES.firstOrNull { it.id == id } ?: STREAM_PROFILES.first()

    /**
     * The first profile with the pace [fps] (so 8 is "8", the full frame, and not "8m"); a pace that is not in the table (an old or damaged
     * value) reads as the default, 4 fps. For the reserve's ceiling and for the choice stored before the profiles had ids.
     */
    fun streamProfile(fps: Int): StreamProfile = STREAM_PROFILES.firstOrNull { it.fullFps == fps } ?: STREAM_PROFILES.first()

    /**
     * The ceiling of the pace while the bike is in the reserve (decision D117, plan 32): profile A goes down to this pace, and its bitrate to
     * [RESERVE_CAP_BPS], whatever pace was chosen; a pace below it is left as it is. The light screens too.
     */
    const val RESERVE_CAP_FPS = 6

    /** The bitrate of the profile of [RESERVE_CAP_FPS]: the most profile A has in the reserve. */
    val RESERVE_CAP_BPS: Int get() = streamProfile(RESERVE_CAP_FPS).fullBps

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
