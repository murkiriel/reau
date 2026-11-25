package com.reau.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reau.app.R
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.SettingsGroup
import com.reau.app.ui.components.SwitchRow

/**
 * "Android Auto": what the head unit tells Android Auto about itself. For now one row, "Anunciar como moto" (on by default, in every build): it is told
 * once, when an Android Auto session begins, so a change counts from the next one (no "NA HORA" chip). It was a debug build's experiment until 20/11/2026.
 */
@Composable
internal fun AndroidAutoGroup(s: REauSettings, change: (REauSettings) -> Unit) {
    SettingsGroup(R.drawable.ic_ms_two_wheeler, stringResource(R.string.group_aa_head_unit), listOf(
        {
            SwitchRow(
                stringResource(R.string.announce_motorcycle_title), s.announceMotorcycle, { change(s.copy(announceMotorcycle = it)) }, "announce_motorcycle",
                sub = stringResource(R.string.announce_motorcycle_sub),
            )
        },
    ), tag = "group_aa_head_unit")
}
