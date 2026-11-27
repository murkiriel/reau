package com.reau.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reau.app.R
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.SettingsGroup
import com.reau.app.ui.components.SwitchRow

/**
 * "Modo de bloqueio do REau (teste)": ONE switch for the whole function, off by default (the word "teste" turns its row red, [ExperimentalMark]). On, it shows the button
 * "Bloquear a tela" on the main screen, lights the screen again when it goes off, and shows REau over the lock screen when it lights. Read at each use, so it counts at once.
 */
@Composable
internal fun ScreenKeepGroup(s: REauSettings, change: (REauSettings) -> Unit) {
    val rows = listOf<@Composable () -> Unit>({
        SwitchRow(
            stringResource(R.string.lock_mode_title), s.lockMode, { change(s.copy(lockMode = it)) }, "lock_mode", now = true,
            sub = stringResource(R.string.lock_mode_sub),
            info = Info(stringResource(R.string.lock_mode_title), stringResource(R.string.lock_mode_info)),
        )
    })
    SettingsGroup(R.drawable.ic_ms_smartphone, stringResource(R.string.group_screen_keep), rows, tag = "group_screen_keep")
}
