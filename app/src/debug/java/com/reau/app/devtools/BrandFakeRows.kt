package com.reau.app.devtools

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.reau.app.R
import com.reau.app.data.KeepAliveNotice
import com.reau.app.data.RideDiagnostics
import com.reau.app.devtools.BrandFake.Choice
import com.reau.app.ui.KeepAliveGuide
import com.reau.app.ui.components.ActionsRow
import com.reau.app.ui.components.RowAction
import com.reau.app.ui.components.SegmentedRow

/**
 * Debug builds only (26/11/2026): the two rows of Ajustes > Depuracao for [BrandFake], kept out of [DebugTriggers] (which is at the limit of functions of a file). The first
 * picks the brand the app reads (the real one, Xiaomi, realme); the second makes the "keep REau alive" card due again by forgetting the answer to the notice. Both end by asking
 * [KeepAliveNotice] to look again, so the main screen shows or hides the card at once. The release build has none of it (`src/release` has no rows).
 */
internal object BrandFakeRows {
    /** The line of the log when the answer to the notice is forgotten, so the log of a test says the card was brought back by hand. */
    internal const val AGAIN_LINE = "keepalive: the notice's answer was forgotten (debug)"

    /** What the notice reads now: the fake or the real brand, through [KeepAliveGuide.current] as the activity does. */
    private fun eligible(): Boolean = KeepAliveGuide.hasNotice(KeepAliveGuide.current())

    /** The choice was made: the brand is read again by everything, and the card is judged again with the other rules as they are. */
    internal fun pick(context: Context, choice: Choice) {
        BrandFake.choose(choice)
        KeepAliveNotice.refresh(context, eligible())
    }

    /** Forgets the answer to the notice, so the card is due again (if the brand has one, the phone joined the dash, and the other rules hold). */
    internal fun showCardAgain(context: Context) {
        KeepAliveNotice.Book.open(context).forget()
        RideDiagnostics.log("debug", AGAIN_LINE)
        KeepAliveNotice.refresh(context, eligible())
    }

    @Composable
    fun ChoiceRow() {
        val context = LocalContext.current
        val choice by BrandFake.choice.collectAsState()
        val options = listOf(
            stringResource(R.string.debug_brand_real) to "debug_brand_real",
            stringResource(R.string.debug_brand_xiaomi) to "debug_brand_xiaomi",
            stringResource(R.string.debug_brand_realme) to "debug_brand_realme",
        )
        SegmentedRow(
            stringResource(R.string.debug_brand_title), options, choice.ordinal, { pick(context, Choice.entries[it]) },
            sub = stringResource(R.string.debug_brand_sub), tag = "debug_brand",
        )
    }

    @Composable
    fun CardAgainRow() {
        val context = LocalContext.current
        ActionsRow(
            stringResource(R.string.debug_brand_again_title),
            listOf(RowAction(stringResource(R.string.debug_brand_again), "debug_brand_again", R.drawable.ic_ms_construction) { showCardAgain(context) }),
            sub = stringResource(R.string.debug_brand_again_sub), tag = "debug_brand_card",
        )
    }
}
