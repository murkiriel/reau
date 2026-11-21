package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.PlanSettings
import com.reau.app.video.PhonePreview
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The FPS test card's switch is on the "Tela do Android Auto" (a debug build's), after the stats and the button sections; on, the five rates appear under it
 * as segments (4, 6, 8, 10, 12) and a tap picks one; off, they are not there.
 */
@RunWith(AndroidJUnit4::class)
class FpsTestCardScreenTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(PlanSettings.PLAN)

    @Before
    fun setUp() {
        compose.setContent {
            val s = settings
            AndroidAutoScreen(
                onBack = {},
                mask = AaScreenMask(s.dashMask, s.mapping, com.reau.app.aa.AaVideo.WIDTH, com.reau.app.aa.AaVideo.HEIGHT) { settings = settings.copy(dashMask = it) },
                fit = AaScreenFit(s) { settings = it.sanitized() },
                stats = s.overlay,
            )
        }
    }

    @After
    fun tearDown() = PhonePreview.hide()

    @Test
    fun theSwitchIsOffAtFirstWithNoRatesAndTheInfoSaysWhatItDoes() {
        compose.onNodeWithTag("fps_test_card").performScrollTo().assertIsOff()
        compose.onNodeWithTag("fps_test_card_rate").assertDoesNotExist()
        compose.infoSays("fps_test_card", "quadro de teste", "quadro perdido", "build de debug")
    }

    @Test
    fun switchedOnTheFiveRatesAppearAndATapPicksOne() {
        compose.onNodeWithTag("fps_test_card").performScrollTo().tapSwitch()
        assertEquals(true, settings.fpsTestCard)
        assertEquals(6, settings.fpsTestCardFps)
        for (rate in listOf(4, 6, 8, 10, 12)) compose.onNodeWithTag("fps_test_card_rate_$rate").performScrollTo()
        compose.onNodeWithTag("fps_test_card_rate_12").performScrollTo().performClick()
        assertEquals(12, settings.fpsTestCardFps)
        compose.onNodeWithTag("fps_test_card_rate_4").performScrollTo().performClick()
        assertEquals(4, settings.fpsTestCardFps)
        compose.onNodeWithTag("fps_test_card").performScrollTo().tapSwitch()
        assertEquals(false, settings.fpsTestCard)
        compose.onNodeWithTag("fps_test_card_rate").assertDoesNotExist()
    }

    @Test
    fun theThreeSwitchesOverTheVideoCanBeOnTogether() {
        settings = settings.copy(statsOverlay = true, buttonOverlay = true)
        compose.waitForIdle()
        compose.onNodeWithTag("fps_test_card").performScrollTo().tapSwitch()
        assertEquals(listOf(true, true, true), listOf(settings.statsOverlay, settings.buttonOverlay, settings.fpsTestCard))
    }
}
