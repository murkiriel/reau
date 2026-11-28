package com.reau.app.weather

import java.util.Locale

/** The ride log's line for a new forecast ([of]). */
internal object WeatherUpdatedLine {
    /**
     * The ride log's line for a new forecast, once per update: the weather code and the rain now that Open-Meteo said, and what REau
     * tells the dash from them (the `06 08` icon and the temperature of [WeatherKeeper.forDash], the rain page of the info ring). The ride of 24/11/2026 had
     * a dash saying "cloudy" and a log that could not say whether that was the code. Never the place: no coordinates, no city.
     */
    fun of(snapshot: WeatherKeeper.Snapshot, realQuarters: Boolean, nowMs: Long, monoNowMs: Long): String {
        val forecast = snapshot.forecast
        val current = forecast.current
        val code = current?.weatherCode?.toString() ?: "not said"
        val rain = current?.precipitationMm?.let { "%.1fmm".format(Locale.US, it) } ?: "not said"
        val sent = WeatherKeeper.forDash(snapshot, nowMs, monoNowMs)?.let {
            "icon=0x%02X (%s) temp=%dC".format(Locale.US, it.icon, WeatherCondition.forCode(current?.weatherCode), it.tempC)
        } ?: "nothing (no usable reading)"
        val page = InfoRing.localNow(snapshot, nowMs, monoNowMs)?.let { InfoRing.rainPageLine(forecast, it, realQuarters) } ?: "unknown (no local time)"
        return "weather updated: ${forecast.hourly.size} hours, ${forecast.daily.size} days; current code=$code rain=$rain; dash $sent; ring rain page=$page"
    }
}
