// The info ring's pages as words for the title of the dash's gold band (P31 Título): the same pages ([InfoRing.titlePages]), a different ink.
package com.reau.app.weather

import java.util.Locale

/**
 * The templates of the title's words, in the rider's language (the phone's resources give them): `%1$s` and `%2$s` are filled by
 * [InfoRingTitle.text]. Each filled text has to fit what the dash's street field holds (19 characters, in Latin-1: a test looks at all
 * of them in the three languages, with the longest numbers a page can have).
 */
data class TitleWords(
    /** The rain is falling and no end is in sight; and falling until `%1$s` minutes from now (the minutes may come as "~20"). */
    val rainNow: String = "Raining now",
    val rainUntil: String = "Rain for %1\$s min",
    /** The rain starts in `%1$s` minutes. */
    val rainSoon: String = "Rain in %1\$s min",
    /** A storm is on now; and one starts in `%1$s` minutes. */
    val thunderNow: String = "Thunder",
    val thunderSoon: String = "Thunder in %1\$s min",
    /** The gust: `%1$s` the number and `%2$s` its unit. */
    val wind: String = "Wind %1\$s %2\$s",
    /** The day's low `%1$s` and high `%2$s`; and, with no day, the temperature now `%1$s`. */
    val temps: String = "Min %1\$s° Max %2\$s°",
    val tempsNow: String = "Now %1\$s°",
    /** The UV index `%1$s` by its level: high, very high, extreme. */
    val uvHigh: String = "UV %1\$s high",
    val uvVeryHigh: String = "UV %1\$s very high",
    val uvExtreme: String = "UV %1\$s extreme",
    /** The sun's set and rise at the place's clock time `%1$s` ("18:12"). */
    val sunset: String = "Sunset %1\$s",
    val sunrise: String = "Sunrise %1\$s",
    /** The US air quality index `%1$s`. */
    val air: String = "Air AQI %1\$s",
)

/**
 * The words of the info ring's pages for the title of the dash's gold band, in place of the ring ("Informações no título"). The pages and
 * the choice of them are the ring's ([InfoRing.titlePages]); only the ink changes. Pages that are not words stay out ([KINDS]): the phone's
 * battery, signal and GPS and the sky now, which the dash already shows.
 */
object InfoRingTitle {
    /** The pages the title says: every page of the weather but the sky now (the phone's three never come here either). */
    val KINDS: Set<RingKind> = setOf(
        RingKind.WIND_DANGER, RingKind.THUNDER, RingKind.WIND_ALERT, RingKind.RAIN_SOON, RingKind.RAIN_NOW, RingKind.WIND_ATTENTION,
        RingKind.SUN, RingKind.UV, RingKind.AIR, RingKind.TEMPS,
    )

    /** The words of [page] for the title, or null for a page the title does not say. */
    fun text(page: RingPage, words: TitleWords): String? {
        if (page.kind !in KINDS) return null
        return when (page.kind) {
            RingKind.RAIN_SOON -> fill(words.rainSoon, minutesOf(page))
            RingKind.RAIN_NOW -> if (page.line.size > 1) fill(words.rainUntil, minutesOf(page)) else words.rainNow
            RingKind.THUNDER -> if (page.line.size > 1) fill(words.thunderSoon, minutesOf(page)) else words.thunderNow
            RingKind.WIND_DANGER, RingKind.WIND_ALERT, RingKind.WIND_ATTENTION -> fill(words.wind, page.line.getOrNull(0)?.text, page.line.getOrNull(1)?.text)
            RingKind.UV -> fill(uvTemplate(page.tone, words), page.line.getOrNull(1)?.text)
            RingKind.AIR -> fill(words.air, page.line.getOrNull(1)?.text)
            RingKind.SUN -> fill(if (page.icon == RingIcon.SUNRISE) words.sunrise else words.sunset, page.extra.firstOrNull())
            else -> temps(page, words)
        }
    }

    /** The minutes of a page that counts them: "23", or "~20" where the forecast is only hourly. */
    private fun minutesOf(page: RingPage): String? = page.line.getOrNull(1)?.text

    private fun uvTemplate(tone: RingTone, words: TitleWords): String = when (tone) {
        RingTone.UV_EXTREME -> words.uvExtreme
        RingTone.UV_VERY_HIGH -> words.uvVeryHigh
        else -> words.uvHigh
    }

    /** The day's low and high ([RingPage.extra] has the high first, the low second); with no day, the temperature now (the page's line is "22°"). */
    private fun temps(page: RingPage, words: TitleWords): String? =
        if (page.extra.size == 2) fill(words.temps, page.extra[1], page.extra[0]) else fill(words.tempsNow, page.line.firstOrNull()?.text?.trimEnd('°'))

    /** [template] with its arguments in; null when one is missing (a page that does not have what its words need says nothing). */
    private fun fill(template: String, vararg args: String?): String? =
        if (args.any { it == null }) null else String.format(Locale.ROOT, template, *args)
}
