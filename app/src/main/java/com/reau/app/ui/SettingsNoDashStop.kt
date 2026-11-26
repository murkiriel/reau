package com.reau.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reau.app.R
import com.reau.app.dash.video.VideoEncoder
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.SegmentedRow

/** How long the projection waits for a dash that is gone before it turns itself off (5, 10, 20 minutes, or never). */
@Composable
internal fun NoDashStop(s: REauSettings, change: (REauSettings) -> Unit) {
    val options = REauSettings.NO_DASH_STOP_MINUTES.map { minutes ->
        val label = if (minutes == 0) stringResource(R.string.no_dash_stop_never) else stringResource(R.string.no_dash_stop_minutes, minutes)
        label to "no_dash_stop_$minutes"
    }
    SegmentedRow(
        stringResource(R.string.no_dash_stop_title), options, REauSettings.NO_DASH_STOP_MINUTES.indexOf(s.noDashStopMinutes),
        { change(s.copy(noDashStopMinutes = REauSettings.NO_DASH_STOP_MINUTES[it])) },
        sub = stringResource(if (s.noDashStopMinutes == 0) R.string.no_dash_stop_sub_never else R.string.no_dash_stop_sub),
        now = true, info = Info(stringResource(R.string.no_dash_stop_title), stringResource(R.string.no_dash_stop_info)), tag = "no_dash_stop",
    )
}

/** The projection comes back by itself when the phone's system ends REau in the middle of a ride; on by default. */
@Composable
internal fun ResumeAfterKill(s: REauSettings, change: (REauSettings) -> Unit) {
    SettingSwitch(
        R.string.resume_after_kill_title, R.string.resume_after_kill_sub, R.string.resume_after_kill, s.resumeAfterKill, "resume_after_kill", now = true,
    ) {
        change(s.copy(resumeAfterKill = it))
    }
}

/** 2 ms between any two packets to the dash (video and commands); on by default, read when the sockets open, so it counts from the next projection (no "now" chip). */
@Composable
internal fun PacePackets(s: REauSettings, change: (REauSettings) -> Unit) {
    SettingSwitch(R.string.pace_packets_title, R.string.pace_packets_sub, R.string.pace_packets, s.pacePackets, "pace_packets") {
        change(s.copy(pacePackets = it))
    }
}

/**
 * The experimental test of the video's encoder (26/11/2026): the phone's own or Android's software one, as two segments (tags `video_encoder`, `video_encoder_PHONE`,
 * `video_encoder_SOFTWARE`). Read when the video opens, so it counts from the next stream (no "now" chip).
 */
@Composable
internal fun VideoEncoderChoice(s: REauSettings, change: (REauSettings) -> Unit) {
    val names = mapOf(VideoEncoder.PHONE to R.string.video_encoder_phone, VideoEncoder.SOFTWARE to R.string.video_encoder_software)
    SegmentedRow(
        stringResource(R.string.video_encoder_title),
        VideoEncoder.entries.map { stringResource(names.getValue(it)) to "video_encoder_${it.name}" },
        VideoEncoder.entries.indexOf(s.videoEncoder),
        { change(s.copy(videoEncoder = VideoEncoder.entries[it])) },
        sub = stringResource(R.string.video_encoder_sub),
        info = Info(stringResource(R.string.video_encoder_title), stringResource(R.string.video_encoder_info)),
        tag = "video_encoder",
    )
}
