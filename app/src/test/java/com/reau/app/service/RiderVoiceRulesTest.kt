package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the voice says and when, as a pure rule: each trigger, the switch off, the minute between two sayings, and the dash being back only after its going. */
class RiderVoiceRulesTest {
    private val fresh = VoiceRules.Memory()

    @Test
    fun `each of the three situations is said when the switch is on`() {
        for (cue in listOf(VoiceCue.DASH_GONE, VoiceCue.PHONE_HOT, VoiceCue.WIFI_OFF)) {
            val decision = VoiceRules.decide(fresh, cue, switchOn = true, nowMs = 5_000L)
            assertTrue(cue.name, decision.say)
            assertEquals(cue.name, 5_000L, decision.after.saidAtMs.getValue(cue))
        }
    }

    @Test
    fun `with the switch off nothing is said and nothing is remembered`() {
        for (cue in VoiceCue.entries) {
            val decision = VoiceRules.decide(VoiceRules.Memory(dashGoneSaid = true), cue, switchOn = false, nowMs = 5_000L)
            assertFalse(cue.name, decision.say)
            assertEquals(VoiceRules.Why.SWITCH_OFF, decision.why)
            assertEquals(VoiceRules.Memory(dashGoneSaid = true), decision.after)
        }
    }

    @Test
    fun `the same phrase is not said again in under a minute, and is at the minute`() {
        val first = VoiceRules.decide(fresh, VoiceCue.PHONE_HOT, true, nowMs = 10_000L)
        assertTrue(first.say)
        val tooSoon = VoiceRules.decide(first.after, VoiceCue.PHONE_HOT, true, nowMs = 10_000L + 59_999L)
        assertFalse(tooSoon.say)
        assertEquals(VoiceRules.Why.REPEATED, tooSoon.why)
        assertEquals("a refused phrase leaves the memory as it was", first.after, tooSoon.after)
        assertTrue(VoiceRules.decide(first.after, VoiceCue.PHONE_HOT, true, nowMs = 10_000L + 60_000L).say)
    }

    @Test
    fun `each saying restarts the minute of its phrase`() {
        val first = VoiceRules.decide(fresh, VoiceCue.WIFI_OFF, true, nowMs = 0L)
        val second = VoiceRules.decide(first.after, VoiceCue.WIFI_OFF, true, nowMs = 61_000L)
        assertTrue(second.say)
        assertFalse("the minute counts from the last saying, not the first", VoiceRules.decide(second.after, VoiceCue.WIFI_OFF, true, nowMs = 62_000L).say)
        assertTrue(VoiceRules.decide(second.after, VoiceCue.WIFI_OFF, true, nowMs = 121_000L).say)
    }

    @Test
    fun `the minute is of 60 seconds`() {
        assertEquals(60_000L, VoiceRules.REPEAT_MS)
    }

    @Test
    fun `a phrase does not hold back another`() {
        val hot = VoiceRules.decide(fresh, VoiceCue.PHONE_HOT, true, nowMs = 1_000L).after
        assertTrue(VoiceRules.decide(hot, VoiceCue.WIFI_OFF, true, nowMs = 1_001L).say)
        assertTrue(VoiceRules.decide(hot, VoiceCue.DASH_GONE, true, nowMs = 1_001L).say)
    }

    @Test
    fun `the dash being back is said only after its going was said`() {
        val noGone = VoiceRules.decide(fresh, VoiceCue.DASH_BACK, true, nowMs = 1_000L)
        assertFalse(noGone.say)
        assertEquals(VoiceRules.Why.NO_GONE_BEFORE, noGone.why)
        val gone = VoiceRules.decide(fresh, VoiceCue.DASH_GONE, true, nowMs = 1_000L).after
        val back = VoiceRules.decide(gone, VoiceCue.DASH_BACK, true, nowMs = 2_000L)
        assertTrue(back.say)
    }

    @Test
    fun `a return is said once per going`() {
        val gone = VoiceRules.decide(fresh, VoiceCue.DASH_GONE, true, nowMs = 1_000L).after
        val back = VoiceRules.decide(gone, VoiceCue.DASH_BACK, true, nowMs = 2_000L).after
        // Back again with no new going: nothing to say, even after the minute.
        assertFalse(VoiceRules.decide(back, VoiceCue.DASH_BACK, true, nowMs = 200_000L).say)
        val goneAgain = VoiceRules.decide(back, VoiceCue.DASH_GONE, true, nowMs = 100_000L).after
        assertTrue("a new going is a new return, after the minute of the last", VoiceRules.decide(goneAgain, VoiceCue.DASH_BACK, true, nowMs = 200_000L).say)
    }

    @Test
    fun `a going that was held back by the minute does not allow a return`() {
        val gone = VoiceRules.decide(fresh, VoiceCue.DASH_GONE, true, nowMs = 1_000L).after
        val back = VoiceRules.decide(gone, VoiceCue.DASH_BACK, true, nowMs = 5_000L).after
        // The second going comes 10 s after the first: held back, so the rider did not hear it, and its return is not announced.
        val held = VoiceRules.decide(back, VoiceCue.DASH_GONE, true, nowMs = 11_000L)
        assertFalse(held.say)
        assertFalse(VoiceRules.decide(held.after, VoiceCue.DASH_BACK, true, nowMs = 70_000L).say)
    }

    @Test
    fun `a going said while the switch was off is not a going the rider heard`() {
        val off = VoiceRules.decide(fresh, VoiceCue.DASH_GONE, switchOn = false, nowMs = 1_000L).after
        assertFalse(VoiceRules.decide(off, VoiceCue.DASH_BACK, switchOn = true, nowMs = 2_000L).say)
    }

    @Test
    fun `the memory handed in is never changed`() {
        val before = VoiceRules.Memory(mapOf(VoiceCue.WIFI_OFF to 3L), dashGoneSaid = false)
        VoiceRules.decide(before, VoiceCue.DASH_GONE, true, nowMs = 90_000L)
        assertEquals(VoiceRules.Memory(mapOf(VoiceCue.WIFI_OFF to 3L), dashGoneSaid = false), before)
    }
}
