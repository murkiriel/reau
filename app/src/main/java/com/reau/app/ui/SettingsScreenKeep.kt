package com.reau.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reau.app.R
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.SettingsGroup
import com.reau.app.ui.components.SwitchRow

/**
 * "Tela acesa com o celular bloqueado (teste)": the switch (on by default in the 0.3.9x test builds; the word "teste" turns its row red, [ExperimentalMark]) and,
 * only with it on, whether the screen that lights is REau's own over the lock screen (off by default). Both are read at each screen-off, so they count at once.
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
