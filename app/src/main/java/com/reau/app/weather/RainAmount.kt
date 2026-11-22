// How much rain is written, and how long its bar is: one rule for the Clima screen, the info ring and the dash's title.
package com.reau.app.weather

import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The forecast's rain in millimetres ([HourlyWeather.precipitationMm] for an hour, [DailyWeather.precipitationSumMm] for a day, mm/h
 * for the ring and the title). Below [MIN_MM] it is no rain: nothing is written and no bar is drawn. Below [WHOLE_FROM_MM] it has one
 * decimal ("0,4", "8,2"), from there on none ("14"), with the decimal sign of the language the app shows.
 */
object RainAmount {
    /** Less than this is dry (the forecast's own noise: a tenth of a millimetre is what a rain gauge can tell). */
    const val MIN_MM = 0.1

    /** From this many mm the decimal goes: "10", not "10,3". */
    const val WHOLE_FROM_MM = 10.0

    /** The unit of the ring's and the title's intensity, as the phone and the dash write it in every language. */
    const val PER_HOUR_UNIT = "mm/h"

    /** The bar of an hour is full at this many mm, and the bar of a day at [DAY_FULL_MM]; above that it stays full. */
    const val HOUR_FULL_MM = 10.0
    const val DAY_FULL_MM = 40.0

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

    /** "0,4 mm"; null when it is no rain. */
    fun format(mm: Double?, locale: Locale): String? = number(mm, locale)?.let { "$it mm" }

    /** "4,2 mm/h"; null when it is no rain. */
    fun formatPerHour(mmPerHour: Double?, locale: Locale): String? = number(mmPerHour, locale)?.let { "$it $PER_HOUR_UNIT" }

    /** How much of its bar the amount fills, 0.0 to 1.0: [mm] over [fullMm], full beyond it; 0 for no rain. */
    fun fill(mm: Double?, fullMm: Double): Float = if (!isRain(mm) || fullMm <= 0.0) 0f else (checkNotNull(mm) / fullMm).coerceIn(0.0, 1.0).toFloat()
}
