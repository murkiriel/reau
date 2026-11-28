package com.reau.app.devtools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.reau.app.R
import com.reau.app.data.RideDiagnostics
import com.reau.app.devtools.FaultInjection.Fault
import com.reau.app.service.DashLink
import com.reau.app.service.Phase
import com.reau.app.service.ProjectionNotification
import com.reau.app.service.ProjectionService
import com.reau.app.ui.components.ActionsRow
import com.reau.app.ui.components.RowAction

/**
 * Debug builds only: the rows at the end of Ajustes → Depuração that fire each warning of the app, to check its sound, its vibration and its
 * channel on the phone, and that arm the faults of [FaultInjection]. The release build has a [DebugTriggers] with no row (`src/release`), and
 * nothing here is in it. The texts are in `src/debug/res` (pt, en, es, hi), so a release does not carry them; the rows are in five groups: test the
 * warnings, simulate failures, test the dash arrows, test a street name in Devanagari, fake the phone's brand (the choice, and the card shown again).
 */
object DebugTriggers {
    /** The temperature the "phone hot" notice is fired with: above the 44 degrees that sets it off by itself. */
    internal const val HOT_TEMP_C = 45

    /** The notice fired by each action of the first row; the log says which, so a check on the phone can be matched to it. */
    internal fun firedLine(what: String) = "debug trigger fired: $what (debug build only)"

    @Composable
    fun rows(): List<@Composable () -> Unit> {
        val context = LocalContext.current
        val faultLines = listOf(R.string.debug_fault_sub, R.string.debug_fault_decoder_what, R.string.debug_fault_keyframe_what)
            .map { stringResource(it) }.joinToString("\n")
        return listOf<@Composable () -> Unit>(
            {
                ActionsRow(
                    stringResource(R.string.debug_warn_title), listOf(
                        button(R.string.debug_warn_dash_stuck, "debug_fire_dash_stuck") { fire(context, "dash stuck") },
                        button(R.string.debug_warn_phone_hot, "debug_fire_phone_hot") { fire(context, "phone hot") },
                        button(R.string.debug_warn_no_dash, "debug_fire_no_dash") { fire(context, "no dash stop") },
                        button(R.string.debug_warn_channel, "debug_alert_channel") { openAlertChannel(context) },
                    ),
                    sub = stringResource(R.string.debug_warn_sub),
                    tag = "debug_fire",
                )
            },
            {
                ActionsRow(
                    stringResource(R.string.debug_fault_title), listOf(
                        button(R.string.debug_fault_decoder, "debug_fault_decoder") { arm(Fault.DECODER_STALL) },
                        button(R.string.debug_fault_keyframe, "debug_fault_focus") { arm(Fault.FOCUS_CYCLE) },
                    ),
                    // The group's line, then what each button does (the resources say it in the language of the app).
                    sub = faultLines,
                    tag = "debug_fault",
                )
            },
            { ArrowTestRow() },
            { StreetTestRow() },
            { BrandFakeRows.ChoiceRow() },
            { BrandFakeRows.CardAgainRow() },
        )
    }

    /** A button of these rows: its text from the debug resources, the tools' icon. */
    @Composable
    private fun button(@androidx.annotation.StringRes text: Int, tag: String, onClick: () -> Unit) =
        RowAction(stringResource(text), tag, R.drawable.ic_ms_construction, onClick = onClick)

    /** Whether the arrow test may run: a projection running with the dash connected. */
    internal fun arrowTestAvailable(phase: Phase, dash: DashLink): Boolean = phase == Phase.RUNNING && dash == DashLink.READY

    /**
     * The arrow test: with the dash connected, Previous and Next send the arrows of the test sequence, one at a time, to the maneuver bubble; the byte
     * and its expected name are written here for whoever photographs the panel; Stop (or leaving this screen, or the dash going) gives the real arrow back.
     * Without the dash connected the row exists as a line of text and nothing to press.
     */
    @Composable
    private fun ArrowTestRow() {
        val projection by ProjectionService.projection.collectAsState()
        val sent by ArrowTrial.now.collectAsState()
        val available = arrowTestAvailable(projection.phase, projection.dash)
        DisposableEffect(Unit) { onDispose { ArrowTrial.stop() } }
        LaunchedEffect(available) { if (!available) ArrowTrial.stop() }
        if (!available) {
            ActionsRow(stringResource(R.string.debug_arrow_title), emptyList(), sub = stringResource(R.string.debug_arrow_sub_off), tag = "debug_arrow_off")
            return
        }
        val words = ArrowTrial.LabelWords(stringResource(R.string.debug_arrow_hidden), stringResource(R.string.debug_arrow_unnamed))
        val language = LocalConfiguration.current.locales[0].language
        ActionsRow(
            stringResource(R.string.debug_arrow_title), listOf(
                button(R.string.debug_arrow_previous, "debug_arrow_previous") { ArrowTrial.previous() },
                button(R.string.debug_arrow_next, "debug_arrow_next") { ArrowTrial.next() },
                button(R.string.debug_arrow_stop, "debug_arrow_stop") { ArrowTrial.stop() },
            ),
            sub = sent?.let { stringResource(R.string.debug_arrow_sending, ArrowTrial.label(it, language, words)) }
                ?: stringResource(R.string.debug_arrow_sub_idle),
            tag = "debug_arrow",
        )
    }

    /**
     * The street-name test: with the dash connected, Send puts the Devanagari name "चाँदनी चौक" where the real street goes (the title of the gold band and the
     * maneuver bubble's street, as UTF-8) so whoever tests sees whether the dash draws it; Stop (or leaving this screen, or the dash going) gives the real
     * street back. Without the dash connected the row is a line of text and nothing to press. The name is [ArrowTrial.DEVANAGARI_STREET].
     */
    @Composable
    private fun StreetTestRow() {
        val projection by ProjectionService.projection.collectAsState()
        val sent by ArrowTrial.streetSent.collectAsState()
        val context = LocalContext.current
        val available = arrowTestAvailable(projection.phase, projection.dash)
        DisposableEffect(Unit) { onDispose { ArrowTrial.stopStreet() } }
        LaunchedEffect(available) { if (!available) ArrowTrial.stopStreet() }
        if (!available) {
            ActionsRow(stringResource(R.string.debug_street_title), emptyList(), sub = stringResource(R.string.debug_street_sub_off), tag = "debug_street_off")
            return
        }
        ActionsRow(
            stringResource(R.string.debug_street_title), listOf(
                button(R.string.debug_street_send, "debug_street_send") { fire(context, STREET_TEST) },
                button(R.string.debug_street_stop, "debug_street_stop") { ArrowTrial.stopStreet() },
            ),
            sub = stringResource(if (sent == null) R.string.debug_street_sub_idle else R.string.debug_street_sub_sending),
            tag = "debug_street",
        )
    }

    /** The name of the street-name test in [fire] and in the log's line. */
    internal const val STREET_TEST = "street in Devanagari"

    private fun arm(fault: Fault) = FaultInjection.arm(fault)

    /** The three real notices [fire] posts; a JVM test puts its own in their place, since posting one needs a phone. */
    internal interface Notices {
        fun dashStuck(context: Context)
        fun phoneHot(context: Context, tempC: Int)
        fun stoppedNoDash(context: Context, minutes: Int)
    }

    private object RealNotices : Notices {
        override fun dashStuck(context: Context) = ProjectionNotification.showDashStuck(context)
        override fun phoneHot(context: Context, tempC: Int) = ProjectionNotification.showPhoneHot(context, tempC)
        override fun stoppedNoDash(context: Context, minutes: Int) = ProjectionNotification.showStoppedNoDash(context, minutes)
    }

    /** Posts the notice named [what] (through [notices]); false for a name that is none of them, which posts nothing and writes no line. */
    internal fun fire(context: Context, what: String, notices: Notices = RealNotices): Boolean {
        when (what) {
            "dash stuck" -> notices.dashStuck(context)
            "phone hot" -> notices.phoneHot(context, HOT_TEMP_C)
            "no dash stop" -> notices.stoppedNoDash(context, 1)
            STREET_TEST -> ArrowTrial.sendStreet()
            else -> return false
        }
        RideDiagnostics.log("debug", firedLine(what))
        return true
    }

    private fun openAlertChannel(context: Context) {
        ProjectionNotification.createAlertChannel(context)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, ProjectionNotification.ALERT_CHANNEL_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { RideDiagnostics.log("debug", "the alert channel's page did not open: ${it.javaClass.simpleName}") }
    }
}
