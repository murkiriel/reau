package com.reau.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.dash.DashConfig
import com.reau.app.dash.VideoChoice
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.LockedRow
import com.reau.app.ui.components.MsIcon
import com.reau.app.ui.components.SegmentedRow
import com.reau.app.ui.components.SliderRow
import com.reau.app.ui.components.SwitchRow
import com.reau.app.ui.components.hairline
import com.reau.app.ui.theme.REauSpace

// The stream's rate ceiling, bitrate, mode and keyframe are fixed, in two profiles (the four rows, under the Android
// Auto group of SettingsScreen.kt, are only shown). Each used to be a choice.

/** The two video profiles by name and one short line each, and nothing else: no number (those are behind [ProfileDetailsRow]). */
@Composable
internal fun ProfileNameRow(titleRes: Int, subRes: Int, tag: String) {
    LockedRow(stringResource(titleRes), stringResource(R.string.profile_fixed), tag, sub = stringResource(subRes))
}

/**
 * "Detalhes": a row that opens and closes the numbers of the fixed profiles ([StreamProfileRows]); it starts closed ([ProfileDetails.START_OPEN])
 * and is closed again at the next visit to the screen.
 */
@Composable
internal fun ProfileDetailsRow(s: REauSettings, change: (REauSettings) -> Unit) {
    var open by rememberSaveable { mutableStateOf(ProfileDetails.START_OPEN) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.heightIn(min = REauSpace.touch)
                .padding(horizontal = 16.dp, vertical = 14.dp).testTag("profile_details"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(R.string.profile_details_title), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.profile_details_sub), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MsIcon(
                if (open) R.drawable.ic_ms_expand_less else R.drawable.ic_ms_expand_more, 24.dp, MaterialTheme.colorScheme.onSurfaceVariant,
                description = stringResource(if (open) R.string.cd_collapse else R.string.cd_expand),
            )
        }
        if (ProfileDetails.rowsShown(open).isNotEmpty()) {
            HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
            StreamProfileRows(s, change)
        }
    }
}

/**
 * The stream's values (REauSettings.sanitized). A release build only shows them, locked: 4 fps, 400 kbps, the two profiles A and B. A debug build has the
 * three choices, independent of each other (plan 32): the pace ([VideoChoice.FPS_OPTIONS]), the bitrate ceiling (a slider, 400 to 1000 kbps by 100, with the
 * guide for the pace and picture beside it) and the picture size ([VideoChoice.FRAMES]), then the warning of a heavy mix and the reserve's switch. Nothing is forced
 * by the guide or the warning. The navigation profiles below choose between A and B in a release build only.
 */
@Composable
internal fun StreamProfileRows(s: REauSettings, change: (REauSettings) -> Unit) {
    if (com.reau.app.BuildConfig.DEBUG) {
        SegmentedRow(
            stringResource(R.string.fps_title),
            VideoChoice.FPS_OPTIONS.map { stringResource(R.string.pace_option, it) to "pace_$it" },
            VideoChoice.FPS_OPTIONS.indexOf(s.fpsCeiling).coerceAtLeast(0), { change(s.copy(fpsCeiling = VideoChoice.FPS_OPTIONS[it])) },
            sub = stringResource(R.string.pace_sub, DashConfig.RESERVE_CAP_FPS),
            now = true, tag = "fps",
        )
        SliderRow(
            stringResource(R.string.bitrate_cap_title), s.streamBitrateBps / KBPS,
            VideoChoice.CAP_MIN_KBPS, VideoChoice.CAP_MAX_KBPS, VideoChoice.CAP_STEP_KBPS,
            { stringResource(R.string.bitrate_value, it) }, { change(s.copy(streamBitrateBps = it * KBPS)) }, "bitrate",
            sub = stringResource(R.string.bitrate_cap_sub, s.recommendedKbps), now = true,
        )
        SegmentedRow(
            stringResource(R.string.frame_title),
            VideoChoice.FRAMES.map { stringResource(R.string.frame_option, it.width, it.height) to "frame_${it.id}" },
            VideoChoice.FRAMES.indexOfFirst { it == s.frameSize }.coerceAtLeast(0), { change(s.copy(videoFrame = VideoChoice.FRAMES[it].id)) },
            sub = stringResource(R.string.frame_sub), now = true, tag = "frame",
        )
        if (s.heavyLoad) HeavyLoadWarning(VideoChoice.loadMbPerSec(s.fpsCeiling, s.frameSize))
        // A debug build's switch to compare the choices in the reserve: the dash's reserve icon animates only there, where the cap would take every pace to 6 fps.
        SwitchRow(
            stringResource(R.string.reserve_cap_title, DashConfig.RESERVE_CAP_FPS), s.reserveCapOn, { change(s.copy(reserveCapOn = it)) }, "reserve_cap",
            sub = stringResource(R.string.reserve_cap_sub, DashConfig.RESERVE_CAP_FPS), now = true,
        )
    } else {
        LockedRow(
            stringResource(R.string.fps_title), "${s.fpsCeiling}", "fps",
            sub = stringResource(R.string.profiles_fps_sub, s.fpsCeiling, s.fpsCeiling / 2),
            info = Info(
                stringResource(R.string.fps_title),
                stringResource(R.string.profiles_info, s.fpsCeiling, s.streamBitrateBps / KBPS, s.fpsCeiling / 2, s.streamBitrateBps / 2 / KBPS),
            ),
        )
        LockedRow(
            stringResource(R.string.bitrate_title), stringResource(R.string.bitrate_value, s.streamBitrateBps / KBPS), "bitrate",
            sub = stringResource(R.string.profiles_bitrate_sub, s.streamBitrateBps / KBPS, s.streamBitrateBps / 2 / KBPS),
        )
    }
    LockedRow(stringResource(R.string.bitrate_mode_title), stringResource(R.string.bitrate_cbr), "bitrate_mode")
    LockedRow(stringResource(R.string.keyframe_title), "${s.keyframeSec} s", "keyframe")
}

/** The warning of a mix above the load the dash showed it holds: only a line, the mix is the rider's to keep (tag `load_warning`). */
@Composable
private fun HeavyLoadWarning(loadMbPerSec: Int) {
    Text(
        stringResource(R.string.load_warning, loadMbPerSec, VideoChoice.SAFE_LOAD_MB_PER_SEC),
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("load_warning"),
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
    )
}

private const val KBPS = 1000

/**
 * Fixed (REauSettings.sanitized): the profile by maneuver and the profile by speed are always on and, when they
 * disagree, the pessimistic one (profile B, the reduced) wins (fixed too, with no row). Shown, not to be touched.
 */
@Composable
internal fun NavProfileRows() {
    val on = stringResource(R.string.profiles_on)
    LockedRow(
        stringResource(R.string.maneuver_fps_title), on, "maneuver_fps_profile",
        info = Info(stringResource(R.string.maneuver_fps_title), stringResource(R.string.maneuver_profile_info)),
    )
    LockedRow(
        stringResource(R.string.speed_fps_title), on, "speed_fps_profile",
        info = Info(stringResource(R.string.speed_fps_title), stringResource(R.string.speed_profile_info)),
    )
}
