package com.reau.app.dash

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * The fixed words REau writes in the dash's own text fields (the title of the gold band, the caller's card, the street's field): the echo of a
 * tap, the volume level, the info ring's pages in words, "Private". The dash reads those fields as Latin-1 and nobody knows yet whether it has a
 * font for Devanagari, so with the app in Hindi these words go in English ([context]); the owner tests the font and then takes the language out of
 * [ENGLISH_FOR]. Names that come from Android Auto (a street, a song, a contact) are not REau's words and go as they came.
 *
 * What REau draws itself on the video (the info ring, the joystick's legend, the volume and call cards, the badges) is rendered by Android, which has the
 * font, and keeps the app's language: those use the app's context, not this.
 */
object ProtocolText {
    /** The languages whose fixed words go to the dash's text fields in English. */
    internal val ENGLISH_FOR = setOf("hi")

    /** Whether the fixed words of the dash's text fields are English for the language [tag] ("hi", "pt-BR"...). */
    fun inEnglish(tag: String): Boolean = tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT) in ENGLISH_FOR

    /**
     * [base] as it is, or, when its language is one of [ENGLISH_FOR], a context of the same app whose resources are English. The app is one APK with its
     * languages in it, not a bundle that downloads them, so the Play Core warning does not apply (as in [com.reau.app.settings.AppLanguages.wrap]).
     */
    @android.annotation.SuppressLint("AppBundleLocaleChanges")
    fun context(base: Context): Context {
        val configuration = base.resources.configuration
        if (!inEnglish(configuration.locales[0].language)) return base
        val english = Configuration(configuration)
        english.setLocales(LocaleList(Locale.ENGLISH))
        return base.createConfigurationContext(english)
    }
}
