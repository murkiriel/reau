package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
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
 * The debug switch that writes the last dash button's code on the dash's video is on the "Tela do Android Auto", under the stats text's;
 * on, the options for its X, Y and size appear under it, with a preview on the picture; off, none of that is there.
 */
@RunWith(AndroidJUnit4::class)
class ButtonOverlayScreenTest {
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
                button = s.buttonLook,
            )
        }
    }

    @After
    fun tearDown() = PhonePreview.hide()

    @Test
    fun theSwitchIsOnTheAndroidAutoScreenAndOffAtFirst() {
        compose.onNodeWithTag("button_overlay").performScrollTo().assertIsOff()
        compose.onNodeWithTag("button_steps").assertDoesNotExist()
        compose.onNodeWithTag("aa_screen_button").assertDoesNotExist()
        compose.onNodeWithTag("button_overlay").tapSwitch()
        assertEquals(true, settings.buttonOverlay)
        compose.infoSays("button_overlay", "laranja com borda preta", "X e Y")
    }

    @Test
    fun onTheOptionsOfPlaceAndSizeAppearUnderItWithAPreview() {
        settings = settings.copy(buttonOverlay = true, buttonX = 100, buttonY = 150, buttonSize = 20)
        compose.waitForIdle()
        compose.onNodeWithTag("aa_screen_button").assertIsDisplayed()
        compose.onNodeWithTag("button_x_value").performScrollTo().assertTextEquals("100 px")
        compose.onNodeWithTag("button_y_plus").performScrollTo().performClick()
        assertEquals(151, settings.buttonY)
        compose.onNodeWithTag("button_size_plus").performScrollTo().performClick()
        assertEquals(21, settings.buttonSize)
        compose.onNodeWithTag("button_x_minus").performScrollTo().performClick()
        assertEquals(99, settings.buttonX)
    }
}
