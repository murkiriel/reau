package com.reau.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.data.KeepAliveChecklist
import com.reau.app.ui.components.CardAction
import com.reau.app.ui.components.GoldButton
import com.reau.app.ui.components.REauTopBar
import com.reau.app.ui.components.hairline
import com.reau.app.ui.theme.REauMono

/**
 * "Manter o REau vivo": why the phone's system may close REau in the middle of a ride and the steps that lower the chance, by the phone's maker
 * ([KeepAliveGuide]), with the button that opens Android's page of REau's details. It is reached from the notice on the main screen (the one before the first
 * ride on a Xiaomi or realme phone, and the one after the system closed REau) and from the permissions page, near the battery line. [brand] is the phone's
 * family, handed in so a test can show another.
 *
 * Since 26/11/2026 (A3): the advice by [batteryExempt] (null reads as not exempt); on a family with its own steps, the ticks of what the rider did ([answers],
 * [onAnswer]: his word, which the page says REau cannot check); on Xiaomi, the button [onOpenAutostart] to the Autostart screen; and [onSeen], once, when the page
 * opens, so the notice that led here is not shown again.
 */
@Composable
fun KeepAliveScreen(
    onBack: () -> Unit,
    onOpenAppInfo: () -> Unit,
    brand: KeepAliveGuide.Brand = KeepAliveGuide.current(),
    batteryExempt: Boolean? = null,
    answers: Map<KeepAliveChecklist.Item, KeepAliveChecklist.Answer> = emptyMap(),
    onAnswer: (KeepAliveChecklist.Item, Boolean) -> Unit = { _, _ -> },
    onOpenAutostart: () -> Unit = {},
    onSeen: () -> Unit = {},
) {
    LaunchedEffect(Unit) { onSeen() }
    val shape = MaterialTheme.shapes.large
    AppTheme {
        Column(Modifier.fillMaxSize()) {
            REauTopBar(stringResource(R.string.keepalive_title), onBack, titleTag = "keepalive_title")
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.keepalive_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("keepalive_intro"),
                )
                Column(
                    Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, hairline(0.18f), shape)
                        .padding(16.dp).testTag("keepalive_steps_${brand.name.lowercase()}"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(KeepAliveGuide.title(brand)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    KeepAliveGuide.steps(brand).forEachIndexed { i, step -> Step(i + 1, stringResource(step)) }
                }
                if (KeepAliveGuide.hasNotice(brand)) {
                    Text(
                        stringResource(KeepAliveGuide.batteryAdvice(batteryExempt)), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("keepalive_battery_advice"),
                    )
                    Checklist(brand, answers, onAnswer, shape)
                }
                Text(
                    stringResource(R.string.keepalive_honest), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("keepalive_honest"),
                )
                if (brand == KeepAliveGuide.Brand.XIAOMI) {
                    GoldButton(
                        CardAction(stringResource(R.string.keepalive_open_autostart), "keepalive_open_autostart", onOpenAutostart),
                        Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    )
                }
                GoldButton(
                    CardAction(stringResource(R.string.keepalive_open_app_info), "keepalive_open_app_info", onOpenAppInfo),
                    Modifier.fillMaxWidth().heightIn(min = 56.dp),
                )
            }
        }
    }
}

/** What the rider says he did, one tick for each item of the family; the answers are his word (the hint says REau cannot check them). */
@Composable
private fun Checklist(
    brand: KeepAliveGuide.Brand,
    answers: Map<KeepAliveChecklist.Item, KeepAliveChecklist.Answer>,
    onAnswer: (KeepAliveChecklist.Item, Boolean) -> Unit,
    shape: androidx.compose.ui.graphics.Shape,
) {
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, hairline(0.18f), shape)
            .padding(16.dp).testTag("keepalive_checklist"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.keepalive_check_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.keepalive_check_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (item in KeepAliveGuide.items(brand)) {
            val ticked = answers[item] == KeepAliveChecklist.Answer.YES
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { onAnswer(item, it) }).testTag("keepalive_item_${item.key}"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = ticked, onCheckedChange = null)
                Text(stringResource(KeepAliveGuide.label(item)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** One numbered step. */
@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.fillMaxWidth().testTag("keepalive_step_$number"), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text("$number.", style = REauMono, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}
