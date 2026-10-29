package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.PlanSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The joystick page, mapping every control of the joystick, as the redesign draws it:
 * the dash's two modes in tabs, the map mode first; the cross with what each control does; a tap on a
 * control, then on its action, opens the list of actions. One tap only: no "held". In the map mode,
 * up (the dash's own back) and the centre (it puts the dash in the media mode) are locked; down is REau's info ring switch, and can be given another action.
 */
@RunWith(AndroidJUnit4::class)
class JoystickScreenTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(PlanSettings.PLAN)
    private var back = 0

    @Before
    fun setUp() {
        compose.setContent { JoystickScreen(settings, { settings = it }, { back++ }) }
    }

    private fun pad(control: String) = compose.onNodeWithTag("dpad_$control").performScrollTo()

    private fun media() = compose.onNodeWithTag("joystick_mode_media").performClick()

    @Test
    fun theMapModeComesFirstWithUpAndTheCentreLockedAndDownTheInfoRing() {
        compose.onNodeWithTag("joystick_mode_map").assertIsSelected()
        pad("up").assertContentDescriptionContains("Travado", substring = true)
        pad("up").assertTextContains("Sair da navegação?")
        pad("center").assertContentDescriptionContains("Travado", substring = true)
        pad("down").assertTextContains("Anel info.")
        pad("right").assertTextContains("Próximo item")
        pad("left").assertTextContains("Item anterior")
    }

    @Test
    fun aLockedControlSaysWhyAndOpensNoList() {
        pad("up").performClick()
        compose.onNodeWithTag("joystick_action_row").performScrollTo().assertTextContains("voltar do próprio painel", substring = true)
        compose.onNodeWithTag("joystick_action_row").assertTextContains("Sair da navegação?", substring = true)
        compose.onNodeWithTag("joystick_action_row").performClick()
        compose.onNodeWithTag("pick_sheet").assertDoesNotExist()
        pad("center").performClick()
        compose.onNodeWithTag("joystick_action_row").assertTextContains("controle de mídia", substring = true)
    }

    @Test
    fun theLockOnTheCardOfALockedControlHasALabelAndTheOpenOneHasNone() {
        pad("up").performClick()
        compose.onNodeWithTag("joystick_action_row").performScrollTo().assertContentDescriptionContains("Travado", substring = true)
        pad("center").performClick()
        compose.onNodeWithTag("joystick_action_row").assertContentDescriptionContains("Travado", substring = true)
        pad("left").performClick()
        compose.onNodeWithTag("joystick_action_row").assertContentDescriptionDoesNotContain("Travado")
        callTab()
        callPad("left").performClick()
        compose.onNodeWithTag("joystick_action_row").performScrollTo().assertContentDescriptionContains("Travado", substring = true)
    }

    private fun SemanticsNodeInteraction.assertContentDescriptionDoesNotContain(text: String) {
        val descriptions = fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        assertEquals("content descriptions with '$text'", emptyList<String>(), descriptions.filter { text in it })
    }

    @Test
    fun downInTheMapModeIsTheInfoRingAndTakesAnotherActionOnlyThere() {
        pad("down").performClick()
        compose.onNodeWithTag("joystick_action_row").performScrollTo().assertTextContains("anel de informações", substring = true)
        compose.onNodeWithTag("joystick_action_row").performClick()
        compose.onNodeWithTag("action_INFO_RING").performScrollTo().assertExists()
        compose.onNodeWithTag("action_BACK").performScrollTo().performClick()
        assertEquals("map.down.once=BACK", settings.joystick.split(';').first { it.startsWith("map.down") })
        pad("down").assertTextContains("Voltar")
        media()
        pad("down").performClick()
        compose.onNodeWithTag("joystick_action_row").performScrollTo().performClick()
        compose.onNodeWithTag("action_INFO_RING").assertDoesNotExist()
    }

    @Test
    fun eachControlOfTheMediaModeShowsItsActionOfNow() {
        media()
        pad("right").assertTextContains("Próximo item")
        pad("down").assertTextContains("Menu de apps")
        pad("center").assertTextContains("Confirmar")
    }

    @Test
    fun pickingAnActionKeepsItAndTheCrossSaysIt() {
        media()
        pad("center").performClick()
        compose.onNodeWithTag("joystick_action_row").performScrollTo().performClick()
        compose.onNodeWithTag("action_HOME").performScrollTo().performClick()
        assertEquals("media.center.once=HOME", settings.joystick)
        pad("center").assertTextContains("Menu de apps")
    }

    @Test
    fun theMediaTabSaysWhetherTheDashShowsTheSong() {
        // the test settings' page has no music control (like the new install's); they have the switch off, so it is turned on here
        settings = settings.copy(dashMusic = true)
        media()
        compose.onNodeWithTag("joystick_music").performScrollTo().assertTextContains("não mostra a música", substring = true)
        pad("up").performClick()
        compose.onNodeWithTag("joystick_action_row").performScrollTo().performClick()
        compose.onNodeWithTag("action_PLAY_PAUSE").performScrollTo().performClick()
        compose.onNodeWithTag("joystick_music").performScrollTo().assertTextContains("mostra a música que toca", substring = true)
        settings = settings.copy(dashMusic = false)
        compose.onNodeWithTag("joystick_music").performScrollTo().assertTextContains("desligado em Ajustes", substring = true)
    }

    @Test
    fun thereIsNoHeldNorTwice() {
        media()
        compose.onAllNodesWithText("segurando", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("2x", substring = true).assertCountEquals(0)
    }

    @Test
    fun theCallsAreExplainedOnTheirOwnTabOnlyNotUnderTheMapAndMediaButtons() {
        compose.onNodeWithTag("joystick_calls").assertDoesNotExist()
        media()
        compose.onNodeWithTag("joystick_calls").assertDoesNotExist()
        callTab()
        compose.onNodeWithTag("joystick_calls").assertDoesNotExist()
        callPad("left").assertTextContains("Atende")
    }

    @Test
    fun aNewInstallStartsWithTheOwnersPageAndTheZoomButtonsAreNamedWithoutAnExplanation() {
        settings = com.reau.app.settings.REauSettings()
        pad("left").assertTextContains("Afastar")
        pad("right").assertTextContains("Aproximar")
        pad("down").assertTextContains("Anel info.")
        compose.onAllNodesWithText("borda de baixo", substring = true).assertCountEquals(0)
        media()
        // the owner's page has no music control: the media tab says the dash shows no song
        compose.onNodeWithTag("joystick_music").performScrollTo().assertTextContains("não mostra a música", substring = true)
    }

    private fun callTab() = compose.onNodeWithTag("joystick_mode_call").performClick()

    private fun callPad(control: String) = compose.onNodeWithTag("call_dpad_$control").performScrollTo()

    @Test
    fun theCallTabReadsWhatEachControlDoesAndHasNothingToPick() {
        callTab()
        compose.onNodeWithTag("joystick_mode_call").assertIsSelected()
        callPad("left").assertTextContains("Atende")
        callPad("right").assertTextContains("Recusa")
        callPad("center").assertTextContains("Recusa")
        callPad("center").performClick()
        compose.onNodeWithTag("joystick_action_row").performScrollTo().assertTextContains("Fixo, não dá para trocar", substring = true)
        compose.onNodeWithTag("joystick_action_row").assertTextContains("Recusa a ligação", substring = true)
        compose.onNodeWithTag("joystick_action_row").performClick()
        compose.onNodeWithTag("pick_sheet").assertDoesNotExist()
        compose.onNodeWithTag("reset_joystick").assertDoesNotExist()
        assertEquals("the tab changes nothing", PlanSettings.PLAN.joystick, settings.joystick)
    }

    @Test
    fun theCallTabSaysWhatChangesOnACall() {
        callTab()
        compose.onNodeWithTag("joystick_call_in_call").performClick()
        callPad("center").assertTextContains("Desliga")
        callPad("up").assertTextContains("Volume +")
        callPad("down").assertTextContains("Volume −")
        callPad("left").assertTextContains("Sem toque")
        compose.onNodeWithTag("joystick_call_ringing").performClick()
        callPad("up").assertTextContains("Segue o modo")
    }

    @Test
    fun backToTheDefaultsGivesTheNewInstallsPageAndForgetsEveryOtherChoice() {
        settings = settings.copy(joystick = "media.center.once=HOME;map.right.once=NEXT_SONG")
        compose.onNodeWithTag("reset_joystick").performScrollTo().performClick()
        assertEquals(com.reau.app.settings.REauSettings.DEFAULT_JOYSTICK, settings.joystick)
    }

    @Test
    fun backLeavesThePage() {
        compose.onNodeWithTag("back").performClick()
        assertEquals(1, back)
    }
}
