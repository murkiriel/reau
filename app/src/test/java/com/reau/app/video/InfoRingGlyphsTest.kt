package com.reau.app.video

import com.reau.app.ui.components.WeatherGlyphLayer
import com.reau.app.ui.components.weatherGlyphColor
import com.reau.app.ui.components.weatherGlyphLayers
import com.reau.app.weather.RingIcon
import com.reau.app.weather.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ring's icons are the weather screen's own, in the same pieces and colours, and the few more are in the same flat style. */
class InfoRingGlyphsTest {
    private fun same(icon: RingIcon, condition: WeatherCondition) {
        val tab = weatherGlyphLayers(condition).map { it.name }
        assertEquals("$icon is the weather screen's $condition", tab, InfoRingGlyphs.layers(icon).map { it.name })
    }

    @Test
    fun `the icons of the weather's conditions are built of the weather screen's own layers`() {
        same(RingIcon.CLEAR, WeatherCondition.CLEAR)
        same(RingIcon.PARTLY_CLOUDY, WeatherCondition.PARTLY_CLOUDY)
        same(RingIcon.CLOUDY, WeatherCondition.CLOUDY)
        same(RingIcon.FOG, WeatherCondition.FOG)
        same(RingIcon.DRIZZLE, WeatherCondition.DRIZZLE)
        same(RingIcon.RAIN, WeatherCondition.RAIN)
        same(RingIcon.SNOW, WeatherCondition.SNOW)
        same(RingIcon.THUNDER, WeatherCondition.THUNDERSTORM)
        same(RingIcon.UV, WeatherCondition.CLEAR)
    }

    @Test
    fun `the colours of the shared layers are the weather screen's`() {
        val pairs = mapOf(
            RingLayer.SUN to WeatherGlyphLayer.SUN, RingLayer.SUN_BEHIND to WeatherGlyphLayer.SUN, RingLayer.CLOUD to WeatherGlyphLayer.CLOUD,
            RingLayer.DARK_CLOUD to WeatherGlyphLayer.DARK_CLOUD, RingLayer.FOG to WeatherGlyphLayer.FOG, RingLayer.DRIZZLE to WeatherGlyphLayer.DRIZZLE,
            RingLayer.RAIN to WeatherGlyphLayer.RAIN, RingLayer.HEAVY_RAIN to WeatherGlyphLayer.RAIN, RingLayer.SNOW to WeatherGlyphLayer.SNOW,
            RingLayer.BOLT to WeatherGlyphLayer.BOLT, RingLayer.UNKNOWN to WeatherGlyphLayer.UNKNOWN,
        )
        for ((ring, tab) in pairs) assertEquals(ring.name, weatherGlyphColor(tab), InfoRingGlyphs.color(ring))
    }

    @Test
    fun `every icon has layers, and every layer a colour of its own that is not clear`() {
        val phoneCards = setOf(RingIcon.BATTERY, RingIcon.LINK, RingIcon.GPS) // drawn from numbers, with no layers
        for (icon in RingIcon.entries - phoneCards) assertTrue("$icon", InfoRingGlyphs.layers(icon).isNotEmpty())
        for (icon in phoneCards) assertTrue("$icon", InfoRingGlyphs.layers(icon).isEmpty())
        for (layer in RingLayer.entries) assertEquals("$layer opaque", 0xFF, (InfoRingGlyphs.color(layer) shr 24).toInt() and 0xFF)
    }

    @Test
    fun `the icons the weather screen has no piece for are made of pieces of their own`() {
        assertEquals(listOf(RingLayer.MOON), InfoRingGlyphs.layers(RingIcon.CLEAR_NIGHT))
        assertEquals(listOf(RingLayer.MOON_BEHIND, RingLayer.CLOUD), InfoRingGlyphs.layers(RingIcon.PARTLY_CLOUDY_NIGHT))
        assertEquals(listOf(RingLayer.DARK_CLOUD, RingLayer.HEAVY_RAIN), InfoRingGlyphs.layers(RingIcon.HEAVY_RAIN))
        assertEquals(listOf(RingLayer.WIND), InfoRingGlyphs.layers(RingIcon.WIND))
        assertEquals(listOf(RingLayer.DARK_CLOUD, RingLayer.DUST), InfoRingGlyphs.layers(RingIcon.AIR))
        assertEquals(listOf(RingLayer.HORIZON, RingLayer.HALF_SUN, RingLayer.ARROW_DOWN), InfoRingGlyphs.layers(RingIcon.SUNSET))
        assertEquals(listOf(RingLayer.HORIZON, RingLayer.HALF_SUN, RingLayer.ARROW_UP), InfoRingGlyphs.layers(RingIcon.SUNRISE))
        assertEquals(listOf(RingLayer.THERMOMETER), InfoRingGlyphs.layers(RingIcon.THERMOMETER))
        assertEquals(listOf(RingLayer.CLOUD, RingLayer.SLASH), InfoRingGlyphs.layers(RingIcon.NO_DATA))
    }

    @Test
    fun `on the red disk of a danger the dark cloud and the wind go pale`() {
        assertNotEquals(InfoRingGlyphs.color(RingLayer.DARK_CLOUD), InfoRingGlyphs.color(RingLayer.DARK_CLOUD, onDanger = true))
        assertEquals(InfoRingGlyphs.color(RingLayer.CLOUD), InfoRingGlyphs.color(RingLayer.DARK_CLOUD, onDanger = true))
        assertNotEquals(InfoRingGlyphs.color(RingLayer.WIND), InfoRingGlyphs.color(RingLayer.WIND, onDanger = true))
        assertEquals("the rest does not change", InfoRingGlyphs.color(RingLayer.RAIN), InfoRingGlyphs.color(RingLayer.RAIN, onDanger = true))
    }

    @Test
    fun `the sun at night is the moon, which has its own colour apart from the sun's`() {
        assertNotEquals(InfoRingGlyphs.color(RingLayer.SUN), InfoRingGlyphs.color(RingLayer.MOON))
    }

    @Test
    fun `a battery is green from half, amber down to 21, orange down to 11 and red at 10 or less`() {
        val green = 0xFF66BB6AL
        val amber = 0xFFFFB300L
        val orange = 0xFFFB8C00L
        val red = 0xFFEF5350L
        for ((percent, color) in listOf(100 to green, 50 to green, 49 to amber, 21 to amber, 20 to orange, 11 to orange, 10 to red, 0 to red)) {
            assertEquals("$percent%", color, InfoRingGlyphs.batteryColor(percent))
        }
    }

    @Test
    fun `a signal is green at three or four bars, amber at two, red at one, grey with none`() {
        assertEquals(InfoRingGlyphs.batteryColor(100), InfoRingGlyphs.barsColor(4))
        assertEquals(InfoRingGlyphs.batteryColor(100), InfoRingGlyphs.barsColor(3))
        assertEquals(InfoRingGlyphs.batteryColor(30), InfoRingGlyphs.barsColor(2))
        assertEquals(InfoRingGlyphs.batteryColor(0), InfoRingGlyphs.barsColor(1))
        assertEquals(0xFF9E9E9EL, InfoRingGlyphs.barsColor(0))
    }
}
