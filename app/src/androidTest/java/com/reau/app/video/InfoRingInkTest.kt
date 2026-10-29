package com.reau.app.video

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.weather.BatteryDetail
import com.reau.app.weather.GpsDetail
import com.reau.app.weather.LinkDetail
import com.reau.app.weather.LinkKind
import com.reau.app.weather.RingDetail
import com.reau.app.weather.RingFrame
import com.reau.app.weather.RingIcon
import com.reau.app.weather.RingKind
import com.reau.app.weather.RingPage
import com.reau.app.weather.RingText
import com.reau.app.weather.RingTone
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The info ring's picture, looked at pixel by pixel: the disk, the outline, the band, what is inside and what is not. */
@RunWith(AndroidJUnit4::class)
class InfoRingInkTest {
    private fun frame(
        icon: RingIcon = RingIcon.RAIN,
        tone: RingTone = RingTone.NONE,
        line: List<RingText> = listOf(RingText("em", false), RingText("12", true), RingText("min", false)),
        stale: Boolean = false,
        night: Boolean = false,
    ) = RingFrame(RingPage(RingKind.RAIN_SOON, 1, icon, tone, line), stale, night)

    /** The phone's cards are drawn from numbers, with no layers: tested on their own below. */
    private val phoneCards = setOf(RingIcon.BATTERY, RingIcon.LINK, RingIcon.GPS)

    private fun at(bitmap: Bitmap, dx: Float, dy: Float) = bitmap.getPixel((60 + dx).toInt(), (60 + dy).toInt())

    private fun near(a: Int, b: Int, tolerance: Int = 10) =
        kotlin.math.abs(Color.red(a) - Color.red(b)) <= tolerance && kotlin.math.abs(Color.green(a) - Color.green(b)) <= tolerance &&
            kotlin.math.abs(Color.blue(a) - Color.blue(b)) <= tolerance

    @Test
    fun theRingIsAHundredAndTwentySquareAndTransparentBeyondItsDisk() {
        val bitmap = InfoRingInk.render(frame())
        assertEquals(120, bitmap.width)
        assertEquals(120, bitmap.height)
        assertEquals("the corner", 0, Color.alpha(bitmap.getPixel(2, 2)))
        assertEquals(255, Color.alpha(at(bitmap, 40f, 0f)))
    }

    @Test
    fun theDiskIsTheDaysAndTheNightsAndTheOutlineIsAlwaysThere() {
        for (night in listOf(false, true)) {
            val look = InfoRingPalette.of(night)
            val bitmap = InfoRingInk.render(frame(night = night))
            assertTrue("disk, night=$night", near(look.disk, at(bitmap, -46f, 0f)))
            assertTrue("outline, night=$night", near(look.outline, at(bitmap, 58.5f, 0f), 30))
            assertTrue("no band: the disk between", near(look.disk, at(bitmap, 54f, 0f)))
        }
    }

    @Test
    fun theBandHasTheColourOfWhatItWarnsOf() {
        val look = InfoRingPalette.DAY
        assertTrue(near(look.rain, at(InfoRingInk.render(frame(tone = RingTone.RAIN)), 54f, 0f), 30))
        assertTrue(near(look.attention, at(InfoRingInk.render(frame(RingIcon.WIND, RingTone.ATTENTION)), 54f, 0f), 30))
        assertTrue(near(look.alert, at(InfoRingInk.render(frame(RingIcon.WIND, RingTone.ALERT)), 54f, 0f), 30))
        assertTrue(near(look.bandFor(RingTone.UV_HIGH)!!, at(InfoRingInk.render(frame(RingIcon.UV, RingTone.UV_HIGH)), 54f, 0f), 30))
        assertTrue(near(InfoRingPalette.NIGHT.rain, at(InfoRingInk.render(frame(tone = RingTone.RAIN, night = true)), 54f, 0f), 30))
    }

    @Test
    fun dangerFillsTheDiskRedAndLeavesNoBand() {
        for (night in listOf(false, true)) {
            val look = InfoRingPalette.of(night)
            val bitmap = InfoRingInk.render(frame(RingIcon.WIND, RingTone.DANGER, night = night))
            assertTrue("red disk, night=$night", near(look.danger, at(bitmap, -46f, 0f)))
            assertTrue("red where a band would be", near(look.danger, at(bitmap, 54f, 0f)))
        }
    }

    @Test
    fun aStaleRingHasDashedContourAndBandAndAFreshOneHasNone() {
        fun gaps(bitmap: Bitmap, radius: Float): Int {
            var n = 0
            for (degrees in 0 until 360) {
                val a = Math.toRadians(degrees.toDouble())
                val p = bitmap.getPixel((60 + radius * Math.cos(a)).toInt().coerceIn(0, 119), (60 + radius * Math.sin(a)).toInt().coerceIn(0, 119))
                if (!near(p, InfoRingPalette.DAY.outline, 60) && radius > 55f) n++
                if (radius < 55f && near(p, InfoRingPalette.DAY.disk, 30)) n++
            }
            return n
        }
        val fresh = InfoRingInk.render(frame(tone = RingTone.RAIN))
        val stale = InfoRingInk.render(frame(tone = RingTone.RAIN, stale = true))
        assertEquals("no gap in a fresh contour", 0, gaps(fresh, 58.5f))
        assertEquals("no gap in a fresh band", 0, gaps(fresh, 54f))
        assertTrue("gaps in a stale contour: ${gaps(stale, 58.5f)}", gaps(stale, 58.5f) in 80..180)
        assertTrue("gaps in a stale band: ${gaps(stale, 54f)}", gaps(stale, 54f) in 80..180)
    }

    /** The design's rule: nothing of an icon or a line passes the content circle (radius 49); the ring between it and the band stays the disk's colour. */
    @Test
    fun noIconAndNoLineGoesBeyondTheContentCircle() {
        val longLine = listOf(RingText("chovendo", false), RingText("123", true), RingText("min", false))
        for (icon in RingIcon.entries - phoneCards) {
            for (night in listOf(false, true)) {
                val tone = if (icon == RingIcon.THUNDER || icon == RingIcon.WIND) RingTone.DANGER else RingTone.NONE
                val bitmap = InfoRingInk.render(frame(icon, tone, longLine, night = night))
                val disk = InfoRingPalette.of(night).diskFor(tone)
                var strays = 0
                for (y in 0 until 120) {
                    for (x in 0 until 120) {
                        val r = hypot(x + 0.5 - 60, y + 0.5 - 60)
                        if (r > InfoRingSpot.CONTENT_RADIUS + 1f && r < 50.5 && !near(bitmap.getPixel(x, y), disk, 12)) strays++
                    }
                }
                assertEquals("$icon night=$night draws past the content circle", 0, strays)
            }
        }
    }

    @Test
    fun everyIconDrawsSomethingInItsBoxAndEveryLineSomethingOnItsRow() {
        for (icon in RingIcon.entries - phoneCards) {
            val bitmap = InfoRingInk.render(frame(icon, RingTone.NONE, emptyList()))
            val disk = InfoRingPalette.DAY.disk
            var inked = 0
            for (y in 9 until 69) for (x in 30 until 90) if (!near(bitmap.getPixel(x, y), disk, 12)) inked++
            assertTrue("$icon inks $inked pixels in its box", inked > 150)
            var below = 0
            for (y in 74 until 100) for (x in 20 until 100) if (!near(bitmap.getPixel(x, y), disk, 12)) below++
            assertEquals("$icon with no line leaves the row empty", 0, below)
        }
        val withLine = InfoRingInk.render(frame())
        var written = 0
        for (y in 70 until 95) for (x in 20 until 100) if (!near(withLine.getPixel(x, y), InfoRingPalette.DAY.disk, 12)) written++
        assertTrue("the line writes $written pixels", written > 150)
    }

    @Test
    fun theLineIsCentredAndAnOverlongOneStaysInsideEightyPixels() {
        val bitmap = InfoRingInk.render(frame(line = listOf(RingText("chovendo muito forte agora", false), RingText("123", true))))
        var left = 120
        var right = 0
        for (y in 70 until 95) for (x in 0 until 120) {
            if (hypot(x + 0.5 - 60, y + 0.5 - 60) < 50 && !near(bitmap.getPixel(x, y), InfoRingPalette.DAY.disk, 12)) {
                left = minOf(left, x)
                right = maxOf(right, x)
            }
        }
        assertTrue("from $left to $right", right - left <= 82 && left >= 19 && right <= 101)
        assertTrue("centred", kotlin.math.abs((left + right) / 2 - 60) <= 3)
    }

    @Test
    fun theIconsHaveTheWeatherScreensColours() {
        fun count(bitmap: Bitmap, layer: RingLayer, onDanger: Boolean = false): Int {
            val color = InfoRingGlyphs.color(layer, onDanger).toInt()
            var n = 0
            for (y in 0 until 120) for (x in 0 until 120) if (near(bitmap.getPixel(x, y), color, 25)) n++
            return n
        }
        assertTrue(count(InfoRingInk.render(frame(RingIcon.RAIN)), RingLayer.RAIN) > 40)
        assertTrue(count(InfoRingInk.render(frame(RingIcon.HEAVY_RAIN)), RingLayer.RAIN) > 60)
        val bolts = count(InfoRingInk.render(frame(RingIcon.THUNDER, RingTone.DANGER)), RingLayer.BOLT)
        assertTrue("yellow pixels of the bolt: $bolts", bolts > 15)
        assertTrue(count(InfoRingInk.render(frame(RingIcon.CLEAR)), RingLayer.SUN) > 200)
        assertTrue(count(InfoRingInk.render(frame(RingIcon.CLOUDY, night = true)), RingLayer.CLOUD) > 300)
        assertTrue(count(InfoRingInk.render(frame(RingIcon.WIND)), RingLayer.WIND) > 120)
    }

    // The phone's cards.

    private fun cardFrame(icon: RingIcon, detail: RingDetail, word: String = "x", night: Boolean = false, tone: RingTone = RingTone.NONE) =
        RingFrame(RingPage(RingKind.BATTERY, 4, icon, tone, listOf(RingText(word, false)), detail), stale = false, night = night)

    private fun battery(percent: Int, charging: Boolean = false, saver: Boolean = false, night: Boolean = false) =
        InfoRingInk.render(cardFrame(RingIcon.BATTERY, BatteryDetail(percent, charging, saver), night = night))

    private fun countOf(bitmap: Bitmap, color: Int, tolerance: Int = 25): Int {
        var n = 0
        for (y in 0 until 120) for (x in 0 until 120) if (near(bitmap.getPixel(x, y), color, tolerance)) n++
        return n
    }

    private val green = InfoRingGlyphs.batteryColor(87).toInt()

    @Test
    fun theBatteryIsFilledToItsLevelInTheColourOfTheLevel() {
        for ((percent, color) in listOf(87 to 87, 40 to 40, 15 to 15, 6 to 6).map { (p, c) -> p to InfoRingGlyphs.batteryColor(c).toInt() }) {
            assertTrue("$percent% in its colour: ${countOf(battery(percent), color)}", countOf(battery(percent), color) > 15)
        }
        val half = battery(50)
        val full = countOf(battery(100), green)
        val halfGreen = countOf(half, green)
        assertTrue("half is about half of full: $halfGreen of $full", halfGreen in (full * 4 / 10)..(full * 6 / 10))
        assertTrue("an empty part shows", countOf(half, InfoRingPalette.DAY.run { 0xFFCFD8DC.toInt() }, 10) > 100)
        assertEquals("zero has no fill", 0, countOf(battery(0), green))
    }

    @Test
    fun theBoltShowsOnlyWhileChargingAndTheLeafOnlyWithTheSaver() {
        val bolt = InfoRingGlyphs.color(RingLayer.BOLT).toInt()
        val leaf = 0xFF1B5E20.toInt()
        assertTrue(countOf(battery(60, charging = true), bolt) > 15)
        assertEquals(0, countOf(battery(60), bolt))
        assertTrue(countOf(battery(60, saver = true), leaf, 10) > 15)
        assertEquals(0, countOf(battery(60), leaf, 10))
        val both = battery(60, charging = true, saver = true)
        assertTrue(countOf(both, bolt) > 15 && countOf(both, leaf, 10) > 15)
    }

    @Test
    fun thePercentageIsTheBiggestTextAndIsThere() {
        fun inkRows(bitmap: Bitmap): Int {
            var rows = 0
            for (y in 40 until 74) {
                var n = 0
                for (x in 20 until 100) if (near(bitmap.getPixel(x, y), InfoRingPalette.DAY.ink, 60)) n++
                if (n > 3) rows++
            }
            return rows
        }
        assertTrue("the digits are tall: ${inkRows(battery(87))} rows", inkRows(battery(87)) >= 20)
        assertTrue(inkRows(battery(100)) >= 20)
        assertTrue("night, in the pale ink", countOf(battery(87, night = true), InfoRingPalette.NIGHT.ink, 60) > 200)
    }

    private fun link(kind: LinkKind, bars: Int = 4, night: Boolean = false) =
        InfoRingInk.render(cardFrame(RingIcon.LINK, LinkDetail(kind, bars, "5G"), night = night, tone = RingTone.NONE))

    @Test
    fun theSignalBarsLightByTheSignalInItsColour() {
        assertTrue(countOf(link(LinkKind.CELLULAR, 4), InfoRingGlyphs.barsColor(4).toInt()) > 250)
        val two = countOf(link(LinkKind.CELLULAR, 2), InfoRingGlyphs.barsColor(2).toInt())
        assertTrue("two bars in amber: $two", two > 60)
        assertTrue(countOf(link(LinkKind.CELLULAR, 1), InfoRingGlyphs.barsColor(1).toInt()) > 20)
        assertEquals("no bar lit with no signal", 0, countOf(link(LinkKind.CELLULAR, 0), InfoRingGlyphs.barsColor(4).toInt()))
    }

    @Test
    fun theWifiHasArcsTheSatelliteItsPanelsAndThePlaneIsAmber() {
        val blue = InfoRingGlyphs.color(RingLayer.RAIN).toInt()
        assertTrue(countOf(link(LinkKind.WIFI, 4), blue) > 150)
        assertTrue("a weak Wi-Fi has fewer arcs lit", countOf(link(LinkKind.WIFI, 1), blue) < countOf(link(LinkKind.WIFI, 4), blue))
        assertTrue("the satellite's panels", countOf(link(LinkKind.STARLINK), blue) > 100)
        assertTrue(countOf(link(LinkKind.SATELLITE), blue) > 100)
        assertTrue("the plane", countOf(link(LinkKind.AIRPLANE), 0xFFFFB300.toInt()) > 250)
        assertEquals("no network has no blue", 0, countOf(link(LinkKind.OFFLINE), blue))
    }

    private fun gps(bars: Int, bearing: Int?, night: Boolean = false) =
        InfoRingInk.render(cardFrame(RingIcon.GPS, GpsDetail(bars, 9, bearing, 5), night = night))

    @Test
    fun theGpsHasACompassArrowForTheHeadingAndBarsForTheSignal() {
        val blue = InfoRingGlyphs.color(RingLayer.RAIN).toInt()
        assertTrue("the arrow", countOf(gps(4, 90), blue) > 40)
        assertEquals("no heading, no arrow", 0, countOf(gps(4, null), blue))
        assertTrue(countOf(gps(4, 0), InfoRingGlyphs.barsColor(4).toInt()) > 150)
        assertEquals(0, countOf(gps(0, 45), InfoRingGlyphs.barsColor(4).toInt()))
        // The arrow points where it says: its tip, ten pixels from the compass's centre.
        fun tipBlue(bearing: Int): Boolean {
            val a = Math.toRadians(bearing.toDouble())
            val x = (44 + 4 * Math.sin(a)).roundToInt()
            val y = (31 - 4 * Math.cos(a)).roundToInt()
            return near(gps(4, bearing).getPixel(x, y), blue, 40)
        }
        assertTrue("north", tipBlue(0))
        assertTrue("east", tipBlue(90))
        assertTrue("south", tipBlue(180))
        assertTrue("west", tipBlue(270))
    }

    /** The design's rule for every card of the phone, day and night: nothing past the content circle. */
    @Test
    fun noCardOfThePhoneGoesBeyondTheContentCircle() {
        val frames = buildList {
            for (night in listOf(false, true)) {
                for (percent in listOf(0, 7, 42, 100)) for (state in listOf(false to false, true to false, false to true, true to true)) {
                    add(cardFrame(RingIcon.BATTERY, BatteryDetail(percent, state.first, state.second), "carregando", night))
                }
                for (kind in LinkKind.entries) for (bars in listOf(0, 2, 4)) add(cardFrame(RingIcon.LINK, LinkDetail(kind, bars, "5G"), "5G", night))
                for (bars in listOf(0, 3, 4)) for (bearing in listOf(null, 0, 45, 135, 225, 315)) {
                    add(cardFrame(RingIcon.GPS, GpsDetail(bars, 12, bearing, 4), "NNE 12 sat", night))
                }
            }
        }
        for (frame in frames) {
            val bitmap = InfoRingInk.render(frame)
            val disk = InfoRingPalette.of(frame.night).diskFor(frame.page.tone)
            var strays = 0
            for (y in 0 until 120) for (x in 0 until 120) {
                val r = hypot(x + 0.5 - 60, y + 0.5 - 60)
                if (r > InfoRingSpot.CONTENT_RADIUS + 1f && r < 50.5 && !near(bitmap.getPixel(x, y), disk, 12)) strays++
            }
            assertEquals("${frame.page.detail} night=${frame.night} draws past the content circle", 0, strays)
        }
    }
}
