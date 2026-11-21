package com.reau.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reau.app.R
import com.reau.app.settings.DrivingSideMode
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.SegmentedRow

/**
 * The side of the road (20/11/2026): Automatic (the country of the mobile network), Right-hand or Left-hand, as three segments (tags `driving_side`,
 * `driving_side_AUTO`, `driving_side_RIGHT_HAND`, `driving_side_LEFT_HAND`). It only changes the arrows Android Auto gives no direction or side to; a
 * normal setting in a debug and in a release build, taken at once with the projection on.
 */
@Composable
internal fun DrivingSideChoice(s: REauSettings, change: (REauSettings) -> Unit) {
    val names = mapOf(
        DrivingSideMode.AUTO to R.string.driving_side_auto,
        DrivingSideMode.RIGHT_HAND to R.string.driving_side_right,
        DrivingSideMode.LEFT_HAND to R.string.driving_side_left,
    )
    SegmentedRow(
        stringResource(R.string.driving_side_title),
        DrivingSideMode.entries.map { stringResource(names.getValue(it)) to "driving_side_${it.name}" },
        DrivingSideMode.entries.indexOf(s.drivingSideMode),
        { change(s.copy(drivingSideMode = DrivingSideMode.entries[it])) },
        sub = stringResource(R.string.driving_side_sub),
        now = true,
        info = Info(stringResource(R.string.driving_side_title), stringResource(R.string.driving_side_info)),
        tag = "driving_side",
    )
}
