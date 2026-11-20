package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
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
 * The stream's frame ceiling, bitrate, mode and keyframe are fixed (two profiles), shown and
 * not to be touched. Before, each was a choice, taken at once.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenVideoTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(PlanSettings.PLAN)

    @Before
    fun setUp() {
        compose.setContent { SettingsScreen(settings, SettingsActions({ settings = it }, {}, {}, {})) }
    }

    @Test
    fun theFixedVideoLineAndTheModeAndKeyframeAreShownAndLocked() {
        // They are behind "Detalhes" since 12/11/2026 (closed at first). Since 20/11/2026 the pace, the bitrate and the picture are one line to read, the same in every build.
        compose.onNodeWithTag("profile_details").performScrollTo().performClick()
        compose.onNodeWithTag("video_fixed").performScrollTo().assertIsDisplayed()
        for (tag in listOf("bitrate_mode", "keyframe")) {
            compose.onNodeWithTag("${tag}_locked").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("video_fixed").assertTextContains("Vídeo: 6 fps, 400 kbps, 528×304 (fixo)")
        compose.onNodeWithTag("keyframe_locked").assertTextContains("1 s", substring = true)
        for (tag in listOf("fps", "fps_plus", "fps_minus", "bitrate", "bitrate_slider", "reserve_cap", "flicker_warning", "frame", "bitrate_vbr", "bitrate_cbr", "keyframe_1", "keyframe_2", "keyframe_auto")) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
        for (n in listOf(4, 6, 8)) compose.onNodeWithTag("pace_$n").assertDoesNotExist()
    }

    @Test
    fun theProfilesShowTheirNameAndShortLineAndTheNumbersAreBehindDetailsClosedAtFirst() {
        for (tag in listOf("profile_a_locked", "profile_b_locked", "maneuver_fps_profile_locked", "speed_fps_profile_locked", "profile_details")) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("profile_a_locked").assertTextContains("Perfil A", substring = true)
        compose.onNodeWithTag("profile_a_locked").assertTextContains("mais fluida", substring = true)
        compose.onNodeWithTag("profile_b_locked").assertTextContains("Perfil B", substring = true)
        compose.onNodeWithTag("profile_b_locked").assertTextContains("mais leve", substring = true, ignoreCase = true)
        compose.onNodeWithTag("video_fixed").assertDoesNotExist()
        for (tag in listOf("bitrate_mode", "keyframe")) compose.onNodeWithTag("${tag}_locked").assertDoesNotExist()
        compose.onNodeWithTag("profile_details").assertTextContains("Detalhes", substring = true)
    }

    @Test
    fun detailsOpenAndCloseAndTheValuesInsideAreTheFixedOnes() {
        compose.onNodeWithTag("profile_details").performScrollTo().performClick()
        compose.onNodeWithTag("video_fixed").performScrollTo().assertIsDisplayed().assertTextContains("6 fps, 400 kbps, 528×304", substring = true)
        compose.onNodeWithTag("keyframe_locked").assertTextContains("1 s", substring = true)
        compose.onNodeWithTag("profile_details").performScrollTo().performClick()
        compose.onNodeWithTag("video_fixed").assertDoesNotExist()
        for (tag in listOf("bitrate_mode", "keyframe")) compose.onNodeWithTag("${tag}_locked").assertDoesNotExist()
    }

    @Test
    fun thereIsNoReductionChoiceAnyMore() {
        for (tag in listOf("reduction", "reduction_default", "reduction_soft", "reduction_soft_sharp", "reduction_cubic")) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
    }
}
