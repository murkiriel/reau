package com.reau.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reau.app.R
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

/** The keyframe held near 48 kB by regulating the bitrate; on by default, read when the stream's encoder is built, so it counts from the next stream (no "now" chip). */
@Composable
internal fun KeyframeCapSwitch(s: REauSettings, change: (REauSettings) -> Unit) {
    SettingSwitch(R.string.keyframe_cap_title, R.string.keyframe_cap_sub, R.string.keyframe_cap, s.keyframeCap, "keyframe_cap") {
        change(s.copy(keyframeCap = it))
    }
}

/** Only what changed goes to the dash, not everything on timers; on by default, read when the session opens its sockets, so it counts from the next projection (no "now" chip). */
@Composable
internal fun SendOnChangeSwitch(s: REauSettings, change: (REauSettings) -> Unit) {
    SettingSwitch(R.string.send_on_change_title, R.string.send_on_change_sub, R.string.send_on_change, s.sendOnChange, "send_on_change") {
        change(s.copy(sendOnChange = it))
    }
}

/** 2 ms between any two packets to the dash (video and commands); on by default, read when the sockets open, so it counts from the next projection (no "now" chip). */
@Composable
internal fun PacePackets(s: REauSettings, change: (REauSettings) -> Unit) {
    SettingSwitch(R.string.pace_packets_title, R.string.pace_packets_sub, R.string.pace_packets, s.pacePackets, "pace_packets") {
        change(s.copy(pacePackets = it))
    }
}
