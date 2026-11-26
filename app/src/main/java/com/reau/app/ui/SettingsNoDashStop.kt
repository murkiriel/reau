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

/** The projection comes back by itself when the phone's system ends REau in the middle of a ride; on by default. */
@Composable
internal fun ResumeAfterKill(s: REauSettings, change: (REauSettings) -> Unit) {
    SettingSwitch(
        R.string.resume_after_kill_title, R.string.resume_after_kill_sub, R.string.resume_after_kill, s.resumeAfterKill, "resume_after_kill", now = true,
    ) {
        change(s.copy(resumeAfterKill = it))
    }
}
