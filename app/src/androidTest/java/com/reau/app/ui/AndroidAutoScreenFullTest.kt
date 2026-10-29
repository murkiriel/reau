package com.reau.app.ui

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.settings.PlanSettings
import com.reau.app.video.PhonePreview
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "Tela do Android Auto" on its side (28/10/2026): the picture alone, full screen, with no top bar, no fit, no mask and no touch line;
 * upright it is the screen as before; Back leaves the full screen first and the screen after. The orientation is the configuration the
 * screen reads ([LocalConfiguration]); the real turn of the phone is for the owner to see (the activity no longer rebuilds on it).
 */
@RunWith(AndroidJUnit4::class)
class AndroidAutoScreenFullTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var orientation by mutableStateOf(Configuration.ORIENTATION_PORTRAIT)
    private var back = 0

    @After
    fun tearDown() = PhonePreview.hide()

    private fun show() {
        compose.setContent {
            val base = LocalConfiguration.current
            val config = Configuration(base).apply { orientation = this@AndroidAutoScreenFullTest.orientation }
            CompositionLocalProvider(LocalConfiguration provides config) {
                val s = PlanSettings.PLAN
                AndroidAutoScreen(
                    onBack = { back++ }, touch = true,
                    mask = AaScreenMask(true, s.mapping, com.reau.app.aa.AaVideo.WIDTH, com.reau.app.aa.AaVideo.HEIGHT) {},
                    fit = AaScreenFit(s) {},
                )
            }
        }
    }

    @Test
    fun onItsSideThereIsOnlyThePictureAndUprightTheScreenIsBack() {
        show()
        compose.onNodeWithTag("aa_screen_full").assertDoesNotExist()
        compose.onNodeWithTag("aa_screen_title").assertIsDisplayed()
        compose.onNodeWithTag("fit_tab_nav").assertExists()

        orientation = Configuration.ORIENTATION_LANDSCAPE
        compose.waitForIdle()
        compose.onNodeWithTag("aa_screen_full").assertIsDisplayed()
        compose.onNodeWithTag("aa_screen_picture").assertIsDisplayed()
        for (gone in listOf("aa_screen_title", "aa_screen_back", "aa_screen_mask_switch", "aa_screen_mask", "fit_tab_nav", "fit_tab_apps")) {
            compose.onNodeWithTag(gone).assertDoesNotExist()
        }

        orientation = Configuration.ORIENTATION_PORTRAIT
        compose.waitForIdle()
        compose.onNodeWithTag("aa_screen_full").assertDoesNotExist()
        compose.onNodeWithTag("aa_screen_title").assertIsDisplayed()
    }

    @Test
    fun theFullScreenFillsTheWindowAndTheSurfaceIsTheWholeOfIt() {
        show()
        orientation = Configuration.ORIENTATION_LANDSCAPE
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val picture = compose.onNodeWithTag("aa_screen_picture").fetchSemanticsNode().boundsInRoot
        assertTrue("the surface takes the window: $picture in $root", picture.width >= root.width * 0.9f && picture.height >= root.height * 0.9f)
    }

    @Test
    fun theFirstBackLeavesTheFullScreenAndTheNextLeavesTheScreen() {
        show()
        orientation = Configuration.ORIENTATION_LANDSCAPE
        compose.waitForIdle()
        compose.onNodeWithTag("aa_screen_full").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("aa_screen_full").assertDoesNotExist()
        assertEquals("the first Back stays on the screen", 0, back)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals("the next one leaves it", 1, back)
        // Turned upright and on its side again, the full screen comes back.
        orientation = Configuration.ORIENTATION_PORTRAIT
        compose.waitForIdle()
        orientation = Configuration.ORIENTATION_LANDSCAPE
        compose.waitForIdle()
        compose.onNodeWithTag("aa_screen_full").assertIsDisplayed()
    }
}
