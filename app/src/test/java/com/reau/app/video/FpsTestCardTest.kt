package com.reau.app.video

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FPS test card (plan 32, 20/11/2026): what each frame says ([FpsTestCard.cells]), when the compositor makes a frame of it, and how it is wired in the compositor
 * and the loop (read from the source, as the other tests of this folder do: there is no OpenGL on the JVM).
 */
class FpsTestCardTest {
    private fun bits(text: String) = text.map { it == '1' }

    @Test
    fun `frame 0 is second 00, slot 1 of 6, even, all bits off`() {
        val c = FpsTestCard.cells(0, 6)
        assertEquals("0000", c.number)
        assertEquals("6 FPS", c.rate)
        assertEquals("00", c.seconds)
        assertEquals(1, c.slot)
        assertEquals("seg 00  quadro 1/6", c.secondsLine)
        assertTrue(c.even)
        assertEquals(bits("000000000000"), c.bits)
    }

    @Test
    fun `frame 6 is second 01, slot 1 of 6, and 000000000110 in binary`() {
        val c = FpsTestCard.cells(6, 6)
        assertEquals("0006", c.number)
        assertEquals("01", c.seconds)
        assertEquals(1, c.slot)
        assertEquals("seg 01  quadro 1/6", c.secondsLine)
        assertTrue(c.even)
        assertEquals(bits("000000000110"), c.bits)
        assertEquals("the most significant bit is first, 12 of them", FpsTestCard.BITS, c.bits.size)
    }

    @Test
    fun `an odd frame is odd, and the slot walks 1 to 6 and starts over with the next second`() {
        assertFalse(FpsTestCard.cells(7, 6).even)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 1, 2), (0L..7L).map { FpsTestCard.cells(it, 6).slot })
        assertEquals(listOf(true, false, true, false, true, false, true, false), (0L..7L).map { FpsTestCard.cells(it, 6).even })
        assertEquals("02", FpsTestCard.cells(12, 6).seconds)
        assertEquals("01", FpsTestCard.cells(11, 6).seconds)
        assertEquals("00", FpsTestCard.cells(5, 6).seconds)
    }

    @Test
    fun `at 3 fps there are 3 slots and a second is 3 frames`() {
        val c = FpsTestCard.cells(4, 3)
        assertEquals("3 FPS", c.rate)
        assertEquals("01", c.seconds)
        assertEquals(2, c.slot)
        assertEquals("seg 01  quadro 2/3", c.secondsLine)
        assertEquals(listOf(1, 2, 3, 1, 2, 3), (0L..5L).map { FpsTestCard.cells(it, 3).slot })
    }

    @Test
    fun `every slot is inside 1 to the pace, at each pace of the test`() {
        for (fps in com.reau.app.settings.REauSettings.FPS_TEST_CARD_RATES) {
            val slots = (0L..200L).map { FpsTestCard.cells(it, fps).slot }
            assertTrue("$fps: $slots", slots.all { it in 1..fps })
            assertEquals(fps, FpsTestCard.cells(0, fps).fps)
            assertEquals("$fps FPS", FpsTestCard.cells(0, fps).rate)
        }
    }

    @Test
    fun `the binary is the frame's own number, most significant bit first`() {
        for (n in listOf(0L, 1L, 2L, 5L, 255L, 256L, 1000L, 4095L)) {
            val value = FpsTestCard.cells(n, 6).bits.fold(0L) { acc, bit -> acc * 2 + if (bit) 1 else 0 }
            assertEquals(n, value)
        }
        assertEquals(bits("100000000000"), FpsTestCard.cells(2048, 6).bits)
        assertEquals(bits("000000000001"), FpsTestCard.cells(1, 6).bits)
    }

    @Test
    fun `past 4095 the binary wraps and past 9999 the number does, the parity going on`() {
        val wrapped = FpsTestCard.cells(4096, 6)
        assertEquals(bits("000000000000"), wrapped.bits)
        assertEquals("4096", wrapped.number)
        assertEquals(bits("000000000001"), FpsTestCard.cells(4097, 6).bits)
        assertEquals("0000", FpsTestCard.cells(10_000, 6).number)
        assertEquals("0001", FpsTestCard.cells(10_001, 6).number)
        assertEquals("9999", FpsTestCard.cells(9_999, 6).number)
        assertTrue(FpsTestCard.cells(10_000, 6).even)
        assertFalse(FpsTestCard.cells(10_001, 6).even)
        // A long run keeps the layout: four digits, two digits of seconds.
        val long = FpsTestCard.cells(123_456_789L, 12)
        assertEquals(4, long.number.length)
        assertEquals(2, long.seconds.length)
        assertEquals(FpsTestCard.BITS, long.bits.size)
    }

    @Test
    fun `the seconds wrap at 100 and a pace under 1 or a negative count do not break it`() {
        assertEquals("00", FpsTestCard.cells(600, 6).seconds)
        assertEquals("99", FpsTestCard.cells(599, 6).seconds)
        assertEquals(1, FpsTestCard.cells(-5, 0).slot)
        assertEquals("0000", FpsTestCard.cells(-5, 0).number)
    }

    @Test
    fun `the log lines say the switch and, every 10 s, the frames delivered and the target`() {
        assertEquals("fps test card ON: the picture is the numbered card at 8 fps, counting from 0", FpsTestCard.switchLine(true, 8, 0))
        assertEquals("fps test card OFF after 123 frames delivered", FpsTestCard.switchLine(false, 0, 123))
        assertEquals(10_000L, FpsTestCard.SUMMARY_EVERY_MS)
        assertEquals("fps test card: 58 frames delivered in 10.0 s (5.8 fps), 180 in all, target 6 fps", FpsTestCard.summaryLine(58, 10_000, 180, 6))
    }

    @Test
    fun `with the card on every tick is a new frame, with Android Auto sending nothing and before the idle interval`() {
        val pace = com.reau.app.video.DashCompositor.Pace(activeMs = 167, idleMs = 334)
        // Android Auto quiet: without the card the tick waits, and repeats only at the idle interval (3 a second at 6 fps).
        assertEquals(TickAction.WAIT, CompositorRules.tickAction(newImage = false, testCard = false, sinceLastFrameMs = 167, pace = pace))
        assertEquals(TickAction.REPEAT, CompositorRules.tickAction(newImage = false, testCard = false, sinceLastFrameMs = 334, pace = pace))
        // With the card each tick draws a new one, never a wait and never a repeat.
        for (since in listOf(0L, 83L, 167L, 334L, 1000L)) {
            assertEquals(TickAction.DRAW, CompositorRules.tickAction(newImage = false, testCard = true, sinceLastFrameMs = since, pace = pace))
        }
        assertEquals(TickAction.DRAW, CompositorRules.tickAction(newImage = true, testCard = false, sinceLastFrameMs = 0, pace = pace))
    }

    private val compositor = File("src/main/java/com/reau/app/video/DashCompositor.kt").readText()

    @Test
    fun `the compositor draws the card in place of Android Auto's picture, the two texts over it, and nothing outside the ticks`() {
        val whole = compositor.substringAfter("private fun drawAndPost(")
        val frame = whole.substringBefore("val clock = CompositorRules.advance")
        val card = frame.substringAfter("if (testRate > 0) {").substringBefore("} else {")
        assertTrue(card, card.contains("gl.draw(null, viewport, null)"))
        assertFalse("Android Auto's picture is not drawn on the card", card.contains("window"))
        val order = listOf("gl.drawTestCard()", "gl.drawOverlay(", "gl.drawButton(").map { card.indexOf(it) }
        assertTrue("the card first, then the stats text, then the button text: $order", order.all { it >= 0 } && order == order.sorted())
        for (leftOut in listOf("drawRing", "drawLegend", "drawBadge")) assertFalse("$leftOut not on the card", card.contains(leftOut))
        assertTrue(compositor.substringAfter("private fun drawSoon()").substringBefore("val wait").contains("fpsTestRate > 0"))
        assertTrue(compositor.substringAfter("private fun redrawSoon()").substringBefore("burstPending = true").contains("fpsTestRate > 0"))
        assertTrue("the clock tells the rule whether the card is on", compositor.contains("CompositorRules.tickAction(newImage, fpsTestRate > 0, sinceLastFrameMs, pace)"))
        assertTrue("the card goes over Android Auto's picture: it is black under it", card.indexOf("gl.draw(null, viewport, null)") < card.indexOf("gl.drawTestCard()"))
        assertTrue("a frame of the card counts only when the encoder was handed it", whole.contains("if (testRate > 0 && posted) countTestFrame(testRate)"))
    }

    @Test
    fun `the card is drawn on one bitmap that is kept, not one for every frame`() {
        val ink = File("src/main/java/com/reau/app/video/FpsTestCardInk.kt").readText()
        val draw = ink.substringAfter("fun draw(").substringBefore("/** Lets go")
        assertEquals("one place makes the bitmap", 1, Regex("""createBitmap\(""").findAll(ink).count())
        assertTrue(draw, draw.contains("if (bmp == null || bmp.width != width || bmp.height != height)"))
        val set = File("src/main/java/com/reau/app/video/CompositorGl.kt").readText().substringAfter("fun setTestCard(").substringBefore("private fun initOverlay")
        assertTrue("the texture is written over, not made again, for the same size", set.contains("if (testW == bitmap.width && testH == bitmap.height) {\n            GLUtils.texSubImage2D("))
        assertTrue("and made again only when the size changes", set.contains("} else {\n            GLUtils.texImage2D("))
    }

    @Test
    fun `the count starts again when the card goes on and is the frames the encoder was handed`() {
        val apply = compositor.substringAfter("private fun applyTestRate(").substringBefore("/** A frame of the card went")
        assertTrue(apply, apply.contains("testDelivered = 0L"))
        val count = compositor.substringAfter("private fun countTestFrame(").substringBefore("private fun drawAndPost")
        assertTrue(count, count.contains("testDelivered++") && count.contains("FpsTestCard.summaryLine("))
    }

    @Test
    fun `the loop gives the card its pace from the settings, under the heat, and takes the burst away while it is on`() {
        val loop = File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()
        assertTrue(loop.contains("compositor.burstGapMs = if (testRate != null) 0L else burstGap(status, g.encoderFps)"))
        assertTrue(loop.contains("compositor.setFpsTest(if (g.testFps != null) g.fps else 0)"))
        assertTrue(loop.contains("ThermalCeiling.capFor(thermalStatus, asked)"))
        assertTrue("the card goes off with the stream", loop.substringAfter("fun stop()").substringBefore("private fun poll").contains("compositor.setFpsTest(0)"))
        val sides = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(sides.contains("fpsTest = { tuned?.fpsTestRate }"))
    }
}
