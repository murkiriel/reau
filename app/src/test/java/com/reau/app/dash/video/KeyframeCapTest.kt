package com.reau.app.dash.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The regulator of the keyframe's size ([KeyframeCap], the owner's decision of 28/11/2026: 48 000 bytes): what it does to the factor of the bitrate, keyframe by keyframe. */
class KeyframeCapTest {
    private val cap = 48_000

    @Test
    fun `a keyframe over the cap lowers the factor in proportion, aiming a tenth under the cap`() {
        val c = KeyframeCap()
        val change = c.onKeyframe(60_000)!!
        assertEquals(0.9 * 48_000 / 60_000, change.factor, 1e-9)
        assertEquals(0.72, c.factor, 1e-9)
        assertTrue(change.overCap)
        assertEquals(60_000, change.keyframeBytes)
        assertEquals(cap, change.capBytes)
        assertEquals("the bitrate asked is the others' times it", 288_000, c.effective(400_000))
    }

    @Test
    fun `a bigger keyframe lowers it more, and the next one over the cap lowers what is left`() {
        val c = KeyframeCap()
        c.onKeyframe(80_000)
        assertEquals(0.54, c.factor, 1e-9)
        c.onKeyframe(52_000)
        assertEquals(0.54 * 0.9 * 48_000 / 52_000, c.factor, 1e-9)
    }

    @Test
    fun `the factor never goes under the floor, and at the floor a keyframe still over the cap asks for nothing more`() {
        val c = KeyframeCap()
        assertEquals(0.4, c.onKeyframe(400_000)!!.factor, 1e-9)
        assertNull("already at the floor", c.onKeyframe(400_000))
        assertEquals(0.4, c.factor, 1e-9)
        assertEquals(160_000, c.effective(400_000))
    }

    @Test
    fun `a keyframe just under the cap changes nothing, and neither does one in the band between three quarters and the cap`() {
        val c = KeyframeCap()
        assertNull(c.onKeyframe(48_000))
        assertNull(c.onKeyframe(47_999))
        assertNull(c.onKeyframe(36_000))
        assertEquals(1.0, c.factor, 0.0)
        assertEquals(400_000, c.effective(400_000))
    }

    @Test
    fun `a phone whose keyframes stay under the cap never leaves 1`() {
        val c = KeyframeCap()
        for (size in listOf(12_000, 16_000, 11_500, 13_000, 15_900, 12_300)) assertNull(c.onKeyframe(size))
        assertEquals(1.0, c.factor, 0.0)
        for (bps in listOf(400_000, 300_000, 200_000)) assertEquals("the bitrate asked is the bitrate the codec gets", bps, c.effective(bps))
    }

    @Test
    fun `the factor recovers only after three calm keyframes in a row, five points at each one, up to 1`() {
        val c = KeyframeCap()
        c.onKeyframe(60_000) // 0.72
        assertNull(c.onKeyframe(20_000))
        assertNull(c.onKeyframe(20_000))
        assertEquals("two calm ones are not enough", 0.72, c.factor, 1e-9)
        assertEquals(0.77, c.onKeyframe(20_000)!!.factor, 1e-9)
        assertFalse(c.onKeyframe(20_000)!!.overCap)
        assertEquals(0.82, c.factor, 1e-9)
        repeat(3) { c.onKeyframe(20_000) }
        assertEquals(0.97, c.factor, 1e-9)
        assertEquals(1.0, c.onKeyframe(20_000)!!.factor, 0.0)
        assertNull("at 1 there is nothing left to give back", c.onKeyframe(20_000))
    }

    @Test
    fun `a keyframe that is not calm starts the count of the calm ones again`() {
        val c = KeyframeCap()
        c.onKeyframe(60_000) // 0.72
        c.onKeyframe(20_000)
        c.onKeyframe(20_000)
        assertNull("in the band: no recovery, and the count starts over", c.onKeyframe(40_000))
        assertNull(c.onKeyframe(20_000))
        assertNull(c.onKeyframe(20_000))
        assertEquals(0.72, c.factor, 1e-9)
        assertNotNull(c.onKeyframe(20_000))
    }

    @Test
    fun `an over-the-cap keyframe in the middle of a recovery starts the count again too`() {
        val c = KeyframeCap()
        c.onKeyframe(60_000) // 0.72
        repeat(3) { c.onKeyframe(20_000) } // 0.77
        c.onKeyframe(55_000)
        assertEquals(0.77 * 0.9 * 48_000 / 55_000, c.factor, 1e-9)
        assertNull(c.onKeyframe(20_000))
        assertNull(c.onKeyframe(20_000))
        assertNotNull(c.onKeyframe(20_000))
    }

    @Test
    fun `a keyframe exactly at three quarters of the cap is not calm`() {
        val c = KeyframeCap()
        c.onKeyframe(60_000)
        repeat(5) { assertNull(c.onKeyframe(36_000)) }
        assertEquals(0.72, c.factor, 1e-9)
        repeat(2) { assertNull(c.onKeyframe(35_999)) }
        assertNotNull(c.onKeyframe(35_999))
    }

    @Test
    fun `the codec is not asked for a change smaller than the hysteresis, and what was held back is not lost`() {
        val c = KeyframeCap(minChange = 0.25)
        assertNull("0.81 is 19 points from 1.0: held back", c.onKeyframe(53_333))
        assertEquals("the codec still has 1.0", 1.0, c.factor, 0.0)
        assertEquals(400_000, c.effective(400_000))
        val asked = c.onKeyframe(53_333)!!
        assertEquals("the next one counts from the 0.81 it held back", 0.9 * 48_000 / 53_333 * (0.9 * 48_000 / 53_333), asked.factor, 1e-9)
        assertEquals(asked.factor, c.factor, 0.0)
    }

    @Test
    fun `reaching 1 is asked even when it is a step under the hysteresis`() {
        val c = KeyframeCap(minChange = 0.32)
        assertNull(c.onKeyframe(53_333)) // held at 1.0
        val low = c.onKeyframe(53_333)!! // 0.656 asked
        assertEquals(0.656, low.factor, 1e-3)
        repeat(2) { assertNull(c.onKeyframe(20_000)) }
        assertNull("0.706 is 5 points from 0.656", c.onKeyframe(20_000))
        repeat(5) { assertNull("each 5 points is held back", c.onKeyframe(20_000)) }
        assertEquals("the step to 1.0 is told whatever its size", 1.0, c.onKeyframe(20_000)!!.factor, 0.0)
    }

    @Test
    fun `reaching the floor is asked even when it is a step under the hysteresis`() {
        val c = KeyframeCap(minChange = 0.5)
        assertEquals(0.432, c.onKeyframe(100_000)!!.factor, 1e-9) // 56.8 points from 1.0: asked
        val d = KeyframeCap(minChange = 0.6)
        assertNull("0.432 is 56.8 points from 1.0: held back", d.onKeyframe(100_000))
        assertEquals("0.432 x 0.432 is under the floor: the floor is asked", 0.4, d.onKeyframe(100_000)!!.factor, 1e-9)
    }

    @Test
    fun `off, the factor is always 1 and nothing is ever asked`() {
        val c = KeyframeCap(enabled = false)
        assertFalse(c.enabled)
        assertNull(c.onKeyframe(200_000))
        assertNull(c.onKeyframe(48_001))
        assertEquals(1.0, c.factor, 0.0)
        assertEquals(400_000, c.effective(400_000))
        assertEquals(333_333, c.effective(333_333))
    }

    @Test
    fun `a keyframe with no bytes is ignored`() {
        val c = KeyframeCap()
        c.onKeyframe(60_000) // 0.72
        repeat(5) { assertNull(c.onKeyframe(0)) }
        repeat(5) { assertNull(c.onKeyframe(-5)) }
        assertEquals("zero bytes are no calm keyframes: nothing is given back", 0.72, c.factor, 1e-9)
    }

    @Test
    fun `the others' changes of bitrate compose with the factor, whenever they come`() {
        val c = KeyframeCap()
        assertEquals(400_000, c.effective(400_000))
        c.onKeyframe(60_000) // 0.72
        assertEquals("the profile goes from 400 to 300 kbps while the cap holds 0.72", 216_000, c.effective(300_000))
        assertEquals(144_000, c.effective(200_000))
        repeat(6) { c.onKeyframe(20_000) } // 3 calm then 4 steps of 5 points: 0.92
        assertEquals(0.92, c.factor, 1e-9)
        assertEquals(276_000, c.effective(300_000))
    }

    @Test
    fun `the cap counts the bytes that go to the dash, the parameter sets in front of the keyframe`() {
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        assertEquals("the keyframe's buffer and the SPS/PPS the processor puts before it", 8 + 25, KeyframeCap.sentBytes(idr, 25))
        val withParameterSets = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 3)
        assertEquals("an encoder that prepends its own parameter sets already counts them", 8, KeyframeCap.sentBytes(withParameterSets, 25))
        val threeByteStart = byteArrayOf(0, 0, 1, 0x68, 9, 9)
        assertEquals(6, KeyframeCap.sentBytes(threeByteStart, 25))
        assertEquals("nothing known of the parameter sets yet", 8, KeyframeCap.sentBytes(idr, 0))
    }

    @Test
    fun `the log says the keyframe, the cap, the bitrate asked and the factor, and what the line of the stream carries`() {
        val c = KeyframeCap()
        val over = c.onKeyframe(60_000)!!
        assertEquals("keyframe cap: IDR of 60000 B over the 48000 B cap -> bitrate 288 kbps (idrcap=0.72)", KeyframeCap.changeLine(over, c.effective(400_000)))
        assertEquals("idrcap=0.72", c.text())
        repeat(2) { c.onKeyframe(20_000) }
        val back = c.onKeyframe(20_000)!!
        assertEquals("keyframe cap: IDR of 20000 B, calm, giving the bitrate back -> bitrate 308 kbps (idrcap=0.77)", KeyframeCap.changeLine(back, c.effective(400_000)))
    }

    @Test
    fun `the cap is 48 kB, the floor 40 percent`() {
        assertEquals(48_000, KeyframeCap.CAP_BYTES)
        assertEquals(0.4, KeyframeCap.FLOOR, 0.0)
        assertEquals(0.9, KeyframeCap.OVERSHOOT_AIM, 0.0)
        assertEquals(0.75, KeyframeCap.CALM_RATIO, 0.0)
        assertEquals(3, KeyframeCap.CALM_KEYFRAMES)
        assertEquals(0.05, KeyframeCap.RECOVER_STEP, 0.0)
        assertEquals(0.03, KeyframeCap.MIN_CHANGE, 0.0)
    }
}
