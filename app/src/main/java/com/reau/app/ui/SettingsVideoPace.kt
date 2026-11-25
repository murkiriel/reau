package com.reau.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reau.app.R
import com.reau.app.settings.REauSettings
import com.reau.app.settings.VideoPace
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.SegmentedRow

/**
 * The video's pace (plan 32, 20/11/2026): REau (profile A at 6 fps, B at 3) or Original (4 and 2), as two segments, REau first (tags `video_pace`,
 * `video_pace_REAU`, `video_pace_ORIGINAL`). A normal setting in a debug and in a release build, taken at once with the projection on.
 */
@Composable
internal fun VideoPaceChoice(s: REauSettings, change: (REauSettings) -> Unit) {
    val names = mapOf(VideoPace.REAU to R.string.video_pace_reau, VideoPace.ORIGINAL to R.string.video_pace_original)
    SegmentedRow(
        stringResource(R.string.video_pace_title),
        VideoPace.entries.map { stringResource(names.getValue(it)) to "video_pace_${it.name}" },
        VideoPace.entries.indexOf(s.videoPace),
        { change(s.copy(videoPace = VideoPace.entries[it])) },
        sub = stringResource(R.string.video_pace_sub),
        now = true,
        info = Info(stringResource(R.string.video_pace_title), stringResource(R.string.video_pace_info)),
        tag = "video_pace",
    )
}
