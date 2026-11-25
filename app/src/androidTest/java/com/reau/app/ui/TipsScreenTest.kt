package com.reau.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.R
import com.reau.app.service.Projection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The lightbulb in the main screen's bar opens the tips page, whose first card is the Waze zoom tip; back leaves it. */
@RunWith(AndroidJUnit4::class)
class TipsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun host(onTipsOpened: () -> Unit = {}) {
        compose.setContent {
            var inTips by remember { mutableStateOf(false) }
            if (inTips) {
                TipsScreen(onBack = { inTips = false })
            } else {
                MainScreen(MainUiState(Projection()), {}, {}, {}, MainExtras(onOpenTips = { inTips = true; onTipsOpened() }))
            }
        }
    }

    @Test
    fun theLightbulbOpensThePageAndTheWazeTipIsThere() {
        var opened = 0
        host { opened++ }
        compose.onNodeWithTag("open_tips").assertIsDisplayed().performClick()
        assertEquals(1, opened)
        compose.onNodeWithTag("tips_title").assertIsDisplayed().assertTextEquals(context.getString(R.string.tips_title))
        compose.onNodeWithTag("tip_waze_zoom").assertIsDisplayed()
        compose.onNodeWithTag("tip_waze_zoom_title").assertIsDisplayed().assertTextEquals(context.getString(R.string.tip_waze_zoom_title))
        compose.onNodeWithTag("tip_waze_zoom_text").assertIsDisplayed().assertTextEquals(context.getString(R.string.tip_waze_zoom_text))
    }

    @Test
    fun theVideoPaceTipIsRightAfterTheGeminiOne() {
        compose.setContent { TipsScreen(onBack = {}) }
        assertEquals(listOf("waze_zoom", "gemini_swap", "video_fps"), Tips.ALL.map { it.tag })
        compose.onNodeWithTag("tip_gemini_swap_title").performScrollTo().assertIsDisplayed().assertTextEquals(context.getString(R.string.tip_gemini_swap_title))
        compose.onNodeWithTag("tip_gemini_swap_text").assertIsDisplayed().assertTextEquals(context.getString(R.string.tip_gemini_swap_text))
        compose.onNodeWithTag("tip_video_fps_title").performScrollTo().assertIsDisplayed().assertTextEquals(context.getString(R.string.tip_video_fps_title))
        compose.onNodeWithTag("tip_video_fps_text").assertIsDisplayed().assertTextEquals(context.getString(R.string.tip_video_fps_text))
    }

    @Test
    fun everyTipOfTheListHasItsCard() {
        compose.setContent { TipsScreen(onBack = {}) }
        for (tip in Tips.ALL) compose.onNodeWithTag("tip_${tip.tag}").assertExists()
    }

    @Test
    fun backLeavesThePage() {
        host()
        compose.onNodeWithTag("open_tips").performClick()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("open_tips").assertIsDisplayed()
    }
}
