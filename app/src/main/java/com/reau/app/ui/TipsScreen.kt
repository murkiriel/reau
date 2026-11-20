package com.reau.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.ui.components.REauTopBar
import com.reau.app.ui.components.RoundIcon
import com.reau.app.ui.components.hairline

/** One tip for the rider: a [tag] (the card's `tip_<tag>`), its [title] and [text], and an [icon] when it has one. */
class Tip(val tag: String, @StringRes val title: Int, @StringRes val text: Int, @DrawableRes val icon: Int? = null)

/** The tips, declared in one place: the next one is one more line here and its two texts in each language. */
object Tips {
    val ALL: List<Tip> = listOf(
        Tip("waze_zoom", R.string.tip_waze_zoom_title, R.string.tip_waze_zoom_text, R.drawable.ic_ms_map),
        Tip("video_fps", R.string.tip_video_fps_title, R.string.tip_video_fps_text, R.drawable.ic_ms_speed),
    )
}

/**
 * "Dicas": recommendations for using REau, one card for each tip of [Tips.ALL], with the lightbulb button of the main
 * screen's bar as the way in.
 */
@Composable
fun TipsScreen(onBack: () -> Unit) {
    AppTheme {
        Column(Modifier.fillMaxSize()) {
            REauTopBar(stringResource(R.string.tips_title), onBack, titleTag = "tips_title")
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 24.dp).testTag("tips_list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (tip in Tips.ALL) TipCard(tip)
            }
        }
    }
}

/** A tip's card: the icon (if any) in a circle, the title and the text, on the same surface as the other pages' cards. */
@Composable
private fun TipCard(tip: Tip) {
    val shape = MaterialTheme.shapes.large
    Row(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, hairline(0.18f), shape)
            .padding(16.dp).testTag("tip_${tip.tag}"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        tip.icon?.let { RoundIcon(it) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(tip.title), color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp),
                modifier = Modifier.testTag("tip_${tip.tag}_title"),
            )
            Text(
                stringResource(tip.text), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.5.sp, lineHeight = 23.sp),
                modifier = Modifier.testTag("tip_${tip.tag}_text"),
            )
        }
    }
}
