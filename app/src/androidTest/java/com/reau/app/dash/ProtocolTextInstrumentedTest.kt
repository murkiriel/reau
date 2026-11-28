package com.reau.app.dash

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** [ProtocolText] on the real resources: with the app in Hindi the fixed words of the dash's fields are English; in the other languages they are the app's. */
@RunWith(AndroidJUnit4::class)
class ProtocolTextInstrumentedTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    private fun inLanguage(tag: String) = target.createConfigurationContext(Configuration(target.resources.configuration).also { it.setLocales(LocaleList(Locale.forLanguageTag(tag))) })

    @Test
    fun hindiFixedWordsOfTheFieldsAreEnglishAndTheVideoOnesAreHindi() {
        val hindi = inLanguage("hi")
        val english = inLanguage("en")
        val inField = ProtocolText.context(hindi)
        assertEquals(english.getString(R.string.echo_confirm), inField.getString(R.string.echo_confirm))
        assertEquals(english.getString(R.string.caller_private), inField.getString(R.string.caller_private))
        assertNotEquals("the app's own Hindi is not the English", english.getString(R.string.echo_confirm), hindi.getString(R.string.echo_confirm))
    }

    @Test
    fun otherLanguagesKeepTheirOwnContext() {
        for (tag in listOf("pt-BR", "es", "en")) {
            val context = inLanguage(tag)
            assertSame(tag, context, ProtocolText.context(context))
        }
    }
}
