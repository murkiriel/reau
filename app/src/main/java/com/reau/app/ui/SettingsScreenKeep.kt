package com.reau.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reau.app.R
import com.reau.app.settings.REauSettings
import com.reau.app.settings.ScreenKeepMode
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.SegmentedRow
import com.reau.app.ui.components.SettingsGroup
import com.reau.app.ui.components.SwitchRow

/**
 * "Tela acesa com o celular bloqueado (teste)": the switch (off by default; the word "teste" turns its row red, [ExperimentalMark]) and, only with it on, when the
 * screen is lit and whether the screen that lights is REau's own over the lock screen. All three are read at each screen-off, so they count at once.
 */
@Composable
internal fun ScreenKeepGroup(s: REauSettings, change: (REauSettings) -> Unit) {
    val rows = buildList<@Composable () -> Unit> {
        add {
            SwitchRow(
                stringResource(R.string.screen_keep_title), s.screenKeep, { change(s.copy(screenKeep = it)) }, "screen_keep", now = true,
                sub = stringResource(R.string.screen_keep_sub),
                info = Info(stringResource(R.string.screen_keep_title), stringResource(R.string.screen_keep_info)),
            )
        }
        if (s.screenKeep) {
            add { ScreenKeepModeChoice(s, change) }
            add {
                SwitchRow(
                    stringResource(R.string.screen_keep_lock_ui_title), s.screenKeepLockUi, { change(s.copy(screenKeepLockUi = it)) }, "screen_keep_lock_ui",
                    now = true, sub = stringResource(R.string.screen_keep_lock_ui_sub),
                )
            }
        }
    }
    SettingsGroup(R.drawable.ic_ms_smartphone, stringResource(R.string.group_screen_keep), rows, tag = "group_screen_keep")
}

/** Every screen-off, or every 60 or 30 seconds since the screen went off. */
@Composable
private fun ScreenKeepModeChoice(s: REauSettings, change: (REauSettings) -> Unit) {
    val names = mapOf(
        ScreenKeepMode.ON_SCREEN_OFF to R.string.screen_keep_mode_off,
        ScreenKeepMode.EVERY_30_S to R.string.screen_keep_mode_30,
        ScreenKeepMode.EVERY_60_S to R.string.screen_keep_mode_60,
        ScreenKeepMode.EVERY_120_S to R.string.screen_keep_mode_120,
        ScreenKeepMode.EVERY_180_S to R.string.screen_keep_mode_180,
        ScreenKeepMode.EVERY_240_S to R.string.screen_keep_mode_240,
    )
    SegmentedRow(
        stringResource(R.string.screen_keep_mode_title),
        ScreenKeepMode.entries.map { stringResource(names.getValue(it)) to "screen_keep_mode_${it.logName}" },
        ScreenKeepMode.entries.indexOf(s.screenKeepMode), { change(s.copy(screenKeepMode = ScreenKeepMode.entries[it])) },
        sub = stringResource(R.string.screen_keep_mode_sub), now = true, tag = "screen_keep_mode",
    )
}
