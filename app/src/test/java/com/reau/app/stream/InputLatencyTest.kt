package com.reau.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The time from a joystick touch to the video on the wire, one touch at a time (the log line that says what the rider feels of the phone's side). */
class InputLatencyTest {
    private fun ms(n: Long) = n * 1_000_000L

    @Test
    fun `a touch is followed to its first new picture, its draw and its first packet`() {
        val clock = InputLatencyClock()
        clock.keySent(ms(1_000))
        clock.pictureArrived(ms(1_100))
        clock.pictureDrawn(ms(1_160))
        assertTrue(clock.waitingForPacket)
        assertEquals(InputLatencyClock.Measurement(aaMs = 100, drawnMs = 160, wireMs = 175), clock.packetSent(ms(1_175)))
        assertFalse("and it is over", clock.waitingForPacket)
        assertNull("the next packet is none of its business", clock.packetSent(ms(1_200)))
    }

    @Test
    fun `the stages come in order, so a draw or a packet before the picture counts for nothing`() {
        val clock = InputLatencyClock()
        clock.keySent(ms(0))
        clock.pictureDrawn(ms(10))
        assertFalse(clock.waitingForPacket)
        assertNull(clock.packetSent(ms(20)))
        clock.pictureArrived(ms(30))
        assertNull("still no draw", clock.packetSent(ms(40)))
        clock.pictureDrawn(ms(50))
        assertEquals(InputLatencyClock.Measurement(30, 50, 60), clock.packetSent(ms(60)))
    }

    @Test
    fun `only the first picture, draw and packet count, and a second touch waits for the first`() {
        val clock = InputLatencyClock()
        clock.keySent(ms(0))
        clock.keySent(ms(50)) // while one is followed: ignored
        clock.pictureArrived(ms(100))
        clock.pictureArrived(ms(130)) // a later picture does not move the first
        clock.pictureDrawn(ms(160))
        clock.pictureDrawn(ms(300))
        assertEquals(InputLatencyClock.Measurement(100, 160, 170), clock.packetSent(ms(170)))
    }

    @Test
    fun `a touch that moves nothing is dropped after the timeout, and the next one is followed`() {
        val clock = InputLatencyClock(timeoutNs = ms(1_500))
        clock.keySent(ms(0))
        clock.pictureArrived(ms(2_000)) // too late: that picture is not an answer to the touch
        assertFalse(clock.waitingForPacket)
        clock.keySent(ms(2_100))
        clock.pictureArrived(ms(2_200))
        clock.pictureDrawn(ms(2_260))
        assertEquals(InputLatencyClock.Measurement(100, 160, 180), clock.packetSent(ms(2_280)))
    }

    @Test
    fun `nothing is measured without a touch`() {
        val clock = InputLatencyClock()
        clock.pictureArrived(ms(10))
        clock.pictureDrawn(ms(20))
        assertNull(clock.packetSent(ms(30)))
    }

    @Test
    fun `the line says the three times, the screen and the pace`() {
        assertEquals(
            "joystick: Android Auto answered +100 ms, drawn +160 ms, on the wire +175 ms after the key (screen MENU, 8 fps)",
            InputLatency.line(InputLatencyClock.Measurement(100, 160, 175), "MENU", 8),
        )
    }
}
