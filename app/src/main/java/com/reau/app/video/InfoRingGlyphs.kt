package com.reau.app.video

import com.reau.app.ui.components.WeatherGlyphLayer
import com.reau.app.ui.components.weatherGlyphColor
import com.reau.app.ui.components.weatherGlyphLayers
import com.reau.app.weather.RingIcon
import com.reau.app.weather.WeatherCondition

/**
 * The pieces the ring's icons are drawn from, back to front: the same as the weather screen's icons (`WeatherGlyph`: a sun with eight rays,
 * a cloud of three circles and a rounded base, slanted drops, flakes, a bolt, fog lines), and, in the same flat style, the few it has no
 * piece for: the moon, the wind, the haze of dirty air, the horizon of the sun's rise and set with its arrow, the thermometer and heavy rain.
 * Drawn on a 24-unit grid by [InfoRingInk].
 */
enum class RingLayer {
    SUN, SUN_BEHIND, MOON, MOON_BEHIND, CLOUD, DARK_CLOUD, FOG, DRIZZLE, RAIN, HEAVY_RAIN, SNOW, BOLT, UNKNOWN,
    WIND, DUST, HORIZON, HALF_SUN, ARROW_DOWN, ARROW_UP, THERMOMETER, SLASH,
}

object InfoRingGlyphs {
    /** The weather screen's condition behind each of the ring's icons that is one of its conditions; null for the ones it has none for. */
    private fun condition(icon: RingIcon): WeatherCondition? = when (icon) {
        RingIcon.CLEAR, RingIcon.UV -> WeatherCondition.CLEAR
        RingIcon.PARTLY_CLOUDY -> WeatherCondition.PARTLY_CLOUDY
        RingIcon.CLOUDY -> WeatherCondition.CLOUDY
        RingIcon.FOG -> WeatherCondition.FOG
        RingIcon.DRIZZLE -> WeatherCondition.DRIZZLE
        RingIcon.RAIN -> WeatherCondition.RAIN
        RingIcon.SNOW -> WeatherCondition.SNOW
        RingIcon.THUNDER -> WeatherCondition.THUNDERSTORM
        else -> null
    }

    private fun layer(l: WeatherGlyphLayer): RingLayer = when (l) {
        WeatherGlyphLayer.SUN -> RingLayer.SUN
        WeatherGlyphLayer.SUN_BEHIND -> RingLayer.SUN_BEHIND
        WeatherGlyphLayer.CLOUD -> RingLayer.CLOUD
        WeatherGlyphLayer.DARK_CLOUD -> RingLayer.DARK_CLOUD
        WeatherGlyphLayer.FOG -> RingLayer.FOG
        WeatherGlyphLayer.DRIZZLE -> RingLayer.DRIZZLE
        WeatherGlyphLayer.RAIN -> RingLayer.RAIN
        WeatherGlyphLayer.SNOW -> RingLayer.SNOW
        WeatherGlyphLayer.BOLT -> RingLayer.BOLT
        WeatherGlyphLayer.UNKNOWN -> RingLayer.UNKNOWN
    }

    /** The layers of [icon], back to front; the weather screen's own for its conditions. */
    fun layers(icon: RingIcon): List<RingLayer> = condition(icon)?.let { c -> weatherGlyphLayers(c).map(::layer) } ?: when (icon) {
        RingIcon.CLEAR_NIGHT -> listOf(RingLayer.MOON)
        RingIcon.PARTLY_CLOUDY_NIGHT -> listOf(RingLayer.MOON_BEHIND, RingLayer.CLOUD)
        RingIcon.HEAVY_RAIN -> listOf(RingLayer.DARK_CLOUD, RingLayer.HEAVY_RAIN)
        RingIcon.WIND -> listOf(RingLayer.WIND)
        RingIcon.AIR -> listOf(RingLayer.DARK_CLOUD, RingLayer.DUST)
        RingIcon.SUNSET -> listOf(RingLayer.HORIZON, RingLayer.HALF_SUN, RingLayer.ARROW_DOWN)
        RingIcon.SUNRISE -> listOf(RingLayer.HORIZON, RingLayer.HALF_SUN, RingLayer.ARROW_UP)
        RingIcon.THERMOMETER -> listOf(RingLayer.THERMOMETER)
        RingIcon.NO_DATA -> listOf(RingLayer.CLOUD, RingLayer.SLASH)
        // The phone's cards are drawn from numbers ([InfoRingDetailInk]), not from layers.
        RingIcon.BATTERY, RingIcon.LINK, RingIcon.GPS -> emptyList()
        else -> error("$icon has no layers")
    }

    /**
     * The colour of [layer] (`0xAARRGGBB`): the weather screen's for the layers it has, the same mid tones for the others. On the red disk of
     * a danger ([onDanger]) the dark cloud and the wind go pale so they read against it.
     */
    fun color(layer: RingLayer, onDanger: Boolean = false): Long = when (layer) {
        RingLayer.SUN, RingLayer.SUN_BEHIND -> weatherGlyphColor(WeatherGlyphLayer.SUN)
        RingLayer.CLOUD -> weatherGlyphColor(WeatherGlyphLayer.CLOUD)
        RingLayer.DARK_CLOUD -> weatherGlyphColor(if (onDanger) WeatherGlyphLayer.CLOUD else WeatherGlyphLayer.DARK_CLOUD)
        RingLayer.FOG -> weatherGlyphColor(WeatherGlyphLayer.FOG)
        RingLayer.DRIZZLE -> weatherGlyphColor(WeatherGlyphLayer.DRIZZLE)
        RingLayer.RAIN, RingLayer.HEAVY_RAIN -> weatherGlyphColor(WeatherGlyphLayer.RAIN)
        RingLayer.SNOW -> weatherGlyphColor(WeatherGlyphLayer.SNOW)
        RingLayer.BOLT -> weatherGlyphColor(WeatherGlyphLayer.BOLT)
        RingLayer.UNKNOWN -> weatherGlyphColor(WeatherGlyphLayer.UNKNOWN)
        RingLayer.MOON, RingLayer.MOON_BEHIND -> MOON
        RingLayer.WIND -> if (onDanger) WIND_PALE else WIND
        RingLayer.DUST -> DUST
        RingLayer.HORIZON -> weatherGlyphColor(WeatherGlyphLayer.FOG)
        RingLayer.HALF_SUN -> weatherGlyphColor(WeatherGlyphLayer.SUN)
        RingLayer.ARROW_DOWN, RingLayer.ARROW_UP -> ARROW
        RingLayer.THERMOMETER -> weatherGlyphColor(WeatherGlyphLayer.CLOUD)
        RingLayer.SLASH -> SLASH
    }

    /** The colour of a battery at [percent]: green from 50, amber to 21, orange to 11, red at 10 or less (the weather screen's flat tones). */
    fun batteryColor(percent: Int): Long = when {
        percent >= BATTERY_GOOD -> GOOD
        percent > BATTERY_LOW -> AMBER
        percent > BATTERY_CRITICAL -> ORANGE
        else -> RED
    }

    /** The colour of a signal of [bars] (0 to 4): green at 3 or 4, amber at 2, red at 1, grey with none. */
    fun barsColor(bars: Int): Long = when {
        bars >= 3 -> GOOD
        bars == 2 -> AMBER
        bars == 1 -> RED
        else -> NONE
    }

    /** The thermometer's mercury, in the same flat tone family. */
    const val MERCURY = 0xFFEF5350L

    private const val BATTERY_GOOD = 50
    private const val BATTERY_LOW = 20
    private const val BATTERY_CRITICAL = 10
    private const val GOOD = 0xFF66BB6AL
    private const val AMBER = 0xFFFFB300L
    private const val ORANGE = 0xFFFB8C00L
    private const val RED = 0xFFEF5350L
    private const val NONE = 0xFF9E9E9EL
    private const val MOON = 0xFFFFE082L
    private const val WIND = 0xFF4DB6ACL
    private const val WIND_PALE = 0xFFECEFF1L
    private const val DUST = 0xFFFFA726L
    private const val ARROW = 0xFFFB8C00L
    private const val SLASH = 0xFF546E7AL
}
