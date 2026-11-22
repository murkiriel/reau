// How much rain is written on the Clima screen: one rule for its pills.
package com.reau.app.weather

import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The forecast's rain in millimetres ([HourlyWeather.precipitationMm] for an hour, [DailyWeather.precipitationSumMm] for a day). Below
 * [MIN_MM] it is no rain: nothing is written and no pill is drawn. Below [WHOLE_FROM_MM] it has one decimal ("0,4", "8,2"), from there on
 * none ("14"), with the decimal sign of the language the app shows. The ring and the title never say the intensity (mm/h).
 */
object RainAmount {
    /** Less than this is dry (the forecast's own noise: a tenth of a millimetre is what a rain gauge can tell). */
    const val MIN_MM = 0.1

    /** From this many mm the decimal goes: "10", not "10,3". */
    const val WHOLE_FROM_MM = 10.0

    private const val TENTH = 10.0

    /** Whether [mm] is rain at all (null is not). */
    fun isRain(mm: Double?): Boolean = mm != null && mm.isFinite() && mm >= MIN_MM

    /** The amount as a number in [locale]'s decimal sign ("0,4", "8,2", "14"); null when it is no rain. */
    fun number(mm: Double?, locale: Locale): String? {
        if (mm == null || !isRain(mm)) return null
        val tenths = (mm * TENTH).roundToInt()
        if (tenths >= (WHOLE_FROM_MM * TENTH).toInt()) return mm.roundToInt().toString()
        val sign = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return "${tenths / TENTH.toInt()}$sign${tenths % TENTH.toInt()}"
    }

    /** What a forecast line's pill says: [number] ("2,4"), which the screen reader speaks in words, and [written] ("2,4 mm"). */
    data class Pill(val number: String) {
        val written: String get() = "$number mm"
    }

    /** The pill of a line with [mm] of rain in [locale]; null (no pill at all) when it is no rain. */
    fun pill(mm: Double?, locale: Locale): Pill? = number(mm, locale)?.let(::Pill)
}
