package com.reau.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reau.app.BuildConfig
import com.reau.app.R
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.CardAction
import com.reau.app.ui.components.SettingsGroup
import com.reau.app.ui.components.SupportButton
import com.reau.app.ui.components.SwitchRow
import com.reau.app.update.ReleaseCheck
import com.reau.app.update.UpdateNoticeAndroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The last group of the settings: "Check for updates" (the version, the "Check now" button and what GitHub answered) and the switch of the daily notice. Nothing
 * is downloaded or installed; the check asks only when the rider taps, and, with the switch on, once a day by itself ([com.reau.app.update.UpdateNotice]).
 */
@Composable
internal fun UpdateGroup(settings: REauSettings, change: (REauSettings) -> Unit) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val check = {
        if (state != UpdateState.Checking) {
            state = UpdateState.Checking
            scope.launch {
                val result = withContext(Dispatchers.IO) { ReleaseCheck.fetch("REau/${BuildConfig.VERSION_NAME}") }
                state = UpdateState.Done(result)
            }
        }
    }
    SettingsGroup(
        R.drawable.ic_ms_sync, stringResource(R.string.group_update),
        listOf(
            { UpdateRow(state, check) { url -> runCatching { uri.openUri(url) } } },
            {
                SwitchRow(
                    stringResource(R.string.update_notice_title), settings.updateNotice,
                    {
                        change(settings.copy(updateNotice = it))
                        // The daily job follows the switch at once: scheduled when on, cancelled when off.
                        UpdateNoticeAndroid.sync(context, it)
                    },
                    "update_notice", sub = stringResource(R.string.update_notice_sub),
                )
            },
        ),
        tag = "group_update",
    )
}

@Composable
internal fun UpdateRow(state: UpdateState, onCheck: () -> Unit, onOpen: (String) -> Unit) {
    val current = BuildConfig.VERSION_NAME
    val latest = ((state as? UpdateState.Done)?.result as? ReleaseCheck.Result.Latest)
    val verdict = latest?.let { ReleaseCheck.verdict(current, it.tag) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.update_check_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            updateMessage(state, current, latest, verdict),
            Modifier.testTag("update_status"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The buttons sit in the middle of the card (28/10/2026), not at its left.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            SupportButton(CardAction(stringResource(R.string.update_button_check), "update_check", onCheck))
            if (latest != null && verdict == ReleaseCheck.Verdict.NEWER) {
                SupportButton(CardAction(stringResource(R.string.update_button_open), "update_open") { onOpen(latest.pageUrl) })
            }
        }
    }
}

@Composable
private fun updateMessage(state: UpdateState, current: String, latest: ReleaseCheck.Result.Latest?, verdict: ReleaseCheck.Verdict?): String =
    when (state) {
        UpdateState.Idle -> stringResource(R.string.update_sub_idle, current)
        UpdateState.Checking -> stringResource(R.string.update_checking)
        is UpdateState.Done -> when (val result = state.result) {
            is ReleaseCheck.Result.Latest -> when (verdict ?: ReleaseCheck.Verdict.UNKNOWN) {
                ReleaseCheck.Verdict.UP_TO_DATE -> stringResource(R.string.update_up_to_date, current)
                ReleaseCheck.Verdict.NEWER -> stringResource(R.string.update_newer, checkNotNull(latest).tag, current)
                ReleaseCheck.Verdict.DEV_BUILD -> stringResource(R.string.update_dev, current, result.tag)
                ReleaseCheck.Verdict.UNKNOWN -> stringResource(R.string.update_unknown, current, result.tag)
            }
            ReleaseCheck.Result.NoRelease -> stringResource(R.string.update_none)
            is ReleaseCheck.Result.Failed -> stringResource(
                when (result.why) {
                    ReleaseCheck.Why.OFFLINE -> R.string.update_offline
                    ReleaseCheck.Why.LIMITED -> R.string.update_limited
                    ReleaseCheck.Why.SERVER, ReleaseCheck.Why.BAD_ANSWER -> R.string.update_failed
                },
            )
        }
    }
