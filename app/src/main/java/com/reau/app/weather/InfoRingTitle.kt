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
    /** The phone's battery level `%1$s` (the `%%` is the percent sign); and the same while it charges. */
    val battery: String = "Battery %1\$s%%",
    val batteryCharging: String = "Charging %1\$s%%",
    /** The rain's intensity `%1$s` in mm/h ("4,2"; the decimal sign is the language's), for the rain with no minutes to say. */
    val rainRate: String = "Rain %1\$s mm/h",
)

/**
 * The words of the info ring's pages for the title of the dash's gold band, in place of the ring ("Informações no título"). The pages and
 * the choice of them are the ring's ([InfoRing.titlePages]); only the ink changes. Pages that are not words stay out ([KINDS]): the phone's
 * signal and GPS and the sky now, which the dash already shows. The phone's battery is in (the dash does not show the phone's).
 */
object InfoRingTitle {
    /** What the dash's street field holds ([com.reau.app.dash.protocol.DashCommands.ROAD_MAX], which a test holds this equal to). */
    const val MAX_CHARS = 19

    /** The pages the title says: every page of the weather but the sky now, and the phone's battery (its signal and GPS never come here). */
    val KINDS: Set<RingKind> = setOf(
        RingKind.WIND_DANGER, RingKind.THUNDER, RingKind.WIND_ALERT, RingKind.RAIN_SOON, RingKind.RAIN_NOW, RingKind.WIND_ATTENTION,
        RingKind.SUN, RingKind.UV, RingKind.AIR, RingKind.TEMPS, RingKind.BATTERY,
    )

    /** The words of [page] for the title, or null for a page the title does not say. */
    fun text(page: RingPage, words: TitleWords): String? {
        if (page.kind !in KINDS) return null
        return when (page.kind) {
            RingKind.RAIN_SOON -> rainSoon(page, words)
            RingKind.RAIN_NOW -> rainNow(page, words)
            RingKind.THUNDER -> if (page.line.size > 1) fill(words.thunderSoon, minutesOf(page)) else words.thunderNow
            RingKind.WIND_DANGER, RingKind.WIND_ALERT, RingKind.WIND_ATTENTION -> fill(words.wind, page.line.getOrNull(0)?.text, page.line.getOrNull(1)?.text)
            RingKind.UV -> fill(uvTemplate(page.tone, words), page.line.getOrNull(1)?.text)
            RingKind.AIR -> fill(words.air, page.line.getOrNull(1)?.text)
            RingKind.SUN -> fill(if (page.icon == RingIcon.SUNRISE) words.sunrise else words.sunset, page.extra.firstOrNull())
            RingKind.BATTERY -> battery(page, words)
            else -> temps(page, words)
        }
    }

    /**
     * Rain falling now: with the end known, until when ("Chuva até 20 min"), however hard it falls (the minutes first; the intensity is the
     * info ring's); with no end in sight, how hard ("Chuva 4,2 mm/h"), and with no intensity either, the words as before ("Chovendo agora").
     */
    private fun rainNow(page: RingPage, words: TitleWords): String? {
        if (page.line.size > 1) return fill(words.rainUntil, minutesOf(page))
        val rate = page.rate ?: return words.rainNow
        return fill(words.rainRate, rate)
    }

    /** Rain on the way: in how many minutes ("Chuva em 20 min"; "~20" where the forecast is only hourly), however hard it will fall. */
    private fun rainSoon(page: RingPage, words: TitleWords): String? = fill(words.rainSoon, minutesOf(page))

    /** The minutes of a page that counts them: "23", or "~20" where the forecast is only hourly. */
    private fun minutesOf(page: RingPage): String? = page.line.getOrNull(1)?.text

    private fun uvTemplate(tone: RingTone, words: TitleWords): String = when (tone) {
        RingTone.UV_EXTREME -> words.uvExtreme
        RingTone.UV_VERY_HIGH -> words.uvVeryHigh
        else -> words.uvHigh
    }

    /** The battery's level, or "charging" with it when the phone is plugged in; null for a page with no battery detail. */
    private fun battery(page: RingPage, words: TitleWords): String? =
        (page.detail as? BatteryDetail)?.let { fill(if (it.charging) words.batteryCharging else words.battery, it.percent.toString()) }

    /** The day's low and high ([RingPage.extra] has the high first, the low second); with no day, the temperature now (the page's line is "22°"). */
    private fun temps(page: RingPage, words: TitleWords): String? =
        if (page.extra.size == 2) fill(words.temps, page.extra[1], page.extra[0]) else fill(words.tempsNow, page.line.firstOrNull()?.text?.trimEnd('°'))

    /** [template] with its arguments in; null when one is missing (a page that does not have what its words need says nothing). */
    private fun fill(template: String, vararg args: String?): String? =
        if (args.any { it == null }) null else String.format(Locale.ROOT, template, *args)
}
