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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.ui.components.CardAction
import com.reau.app.ui.components.GoldButton
import com.reau.app.ui.components.REauTopBar
import com.reau.app.ui.components.hairline
import com.reau.app.ui.theme.REauMono

/**
 * "Manter o REau vivo": why the phone's system may close REau in the middle of a ride and the steps that lower the chance, by the phone's maker
 * ([KeepAliveGuide]), with the button that opens Android's page of REau's details. It is reached from the notice on the main screen (after the system closed
 * REau) and from the permissions page, near the battery line. [brand] is the phone's family, handed in so a test can show another.
 */
@Composable
fun KeepAliveScreen(onBack: () -> Unit, onOpenAppInfo: () -> Unit, brand: KeepAliveGuide.Brand = KeepAliveGuide.current()) {
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
                Text(
                    stringResource(R.string.keepalive_honest), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("keepalive_honest"),
                )
                GoldButton(
                    CardAction(stringResource(R.string.keepalive_open_app_info), "keepalive_open_app_info", onOpenAppInfo),
                    Modifier.fillMaxWidth().heightIn(min = 56.dp),
                )
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
