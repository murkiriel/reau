package com.reau.app.settings

import com.reau.app.dash.DashConfig
import com.reau.app.video.FrameFit

/**
 * How Android Auto's picture is fitted into the dash's dome. ("Fill the dome", the largest picture the dome's box holds, was
 * taken out on 25/10/2026; a phone that saved it reads [WHOLE_VISIBLE], the nearest, see [SettingsStore].)
 */
enum class Fit {
    /** Smaller, so that none of it is hidden by the round edge. */
    WHOLE_VISIBLE,

    /** A part of the centre, [REauSettings.cropPercent] of the content, as large as the dome allows. */
    CROP,
}

/** Where Android Auto's sound goes while the browser switch is on: the phone only, the browser only, or both. */
enum class WebAudioMode { PHONE, BROWSER, BOTH }

/**
 * The pace of the video to the dash (plan 32, 20/11/2026): the rider's only choice about it, an everyday setting in a debug and in a release build.
 * [fullFps] is profile A's pace and [reducedFps] profile B's, always its exact half; the bitrate ceiling (400 kbps), the picture (528x304) and the keyframe (1 s)
 * are the same in both. [lightScreenFps] is the pace on Android Auto's light screens, never above A's.
 */
enum class VideoPace(val fullFps: Int, val reducedFps: Int) {
    /** REau's own pace, the default: A at 6 fps and B at 3, the most the dash takes without its buttons or the reserve icon suffering. */
    REAU(DashConfig.PROFILE_FULL_FPS, DashConfig.PROFILE_REDUCED_FPS),

    /** The lighter pace: A at 4 fps and B at 2, easier on the dash and on the Wi-Fi. */
    ORIGINAL(DashConfig.ORIGINAL_FULL_FPS, DashConfig.ORIGINAL_REDUCED_FPS);

    /** The pace on the light screens of Android Auto: A's, so they are never above it. */
    val lightScreenFps: Int get() = fullFps

    /** The ride log's line when the rider picks this pace, e.g. "video pace: REAU (A 6 fps / B 3 fps)". */
    fun logLine(): String = "video pace: $name (A $fullFps fps / B $reducedFps fps)"
}

/**
 * Which side of the road the maneuvers are drawn for, where Android Auto does not say (a roundabout without a direction, a U-turn without a side).
 * A normal setting in a debug and in a release build, taken at once with the projection on.
 */
enum class DrivingSideMode {
    /** From the country of the mobile network (else the SIM's); right-hand traffic when neither is known. The default. */
    AUTO,

    /** Right-hand traffic, whatever the network says; the country is not read. */
    RIGHT_HAND,

    /** Left-hand traffic, whatever the network says; the country is not read. */
    LEFT_HAND,
}

/** Waze's map theme, through Android Auto's night sensor (service/DayNight.kt). */
enum class MapTheme {
    /** By the sun where the phone is. */
    AUTO,

    /** Always the day theme. */
    DAY,

    /** Always the night theme. */
    NIGHT,

}

/**
 * The values of a fit (the apps' are [REauSettings]' own fields; the app menu and the navigation may have their own). The
 * crop's place is the window's top left pixel in Android Auto's picture ([cropX], [cropY]); null, centred.
 */
data class FitParams(
    val fit: Fit,
    val cropPercent: Int,
    val cropX: Int?,
    val cropY: Int?,
    val moveX: Int,
    val moveY: Int,
    /** The picture nearer or farther, in % (100: as the fit gives it; [FrameFit.ZOOM]). */
    val zoom: Int = 100,
    /** Black bands over the dash's frame, in dash pixels, to hide what the rider does not want to see. */
    val cover: FrameFit.Cover = FrameFit.Cover.NONE,
)
