package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The voice in the headset with a false speech engine and a false audio focus: the focus is asked for before a phrase and given back after it
 * (whatever came of it), the switch off says and asks nothing, a voice that is not ready or lacks the language costs a line in the log and nothing
 * else, and the engine is made at the first phrase and let go with the projection.
 */
class RiderVoiceTest {
    private val events = mutableListOf<String>()
    private val log = mutableListOf<String>()
    private var now = 1_000L
    private var switchOn = true
    private var focusGranted = true
    private var made = 0
    private var failToMake = false
    private var engineAnswersAtOnce: Pair<Boolean, String?>? = null
    private val engines = mutableListOf<FakeEngine>()

    /** Says nothing: keeps the phrase and its `done` until the test ends it. */
    private inner class FakeEngine : SpeechEngine {
        val spoken = mutableListOf<Pair<String, Locale>>()
        val pending = mutableListOf<(Boolean, String?) -> Unit>()
        var shutdowns = 0
        var finishAtOnce: Pair<Boolean, String?>? = null

        override fun speak(text: String, locale: Locale, done: (said: Boolean, why: String?) -> Unit) {
            events += "speak"
            spoken += text to locale
            val answer = finishAtOnce
            if (answer != null) done(answer.first, answer.second) else pending += done
        }

        override fun shutdown() {
            shutdowns++
            events += "shutdown"
        }
    }

    private val focus = object : FocusPort {
        var requests = 0
        var abandons = 0
        override fun request(): Boolean {
            requests++
            events += "focus"
            return focusGranted
        }

        override fun abandon() {
            abandons++
            events += "abandon"
        }
    }

    private fun voice(locale: Locale = Locale.forLanguageTag("pt-BR")) = RiderVoice(
        enabled = { switchOn },
        words = { cue -> "words of $cue" },
        locale = { locale },
        engineFactory = {
            made++
            if (failToMake) throw IllegalStateException("no engine")
            FakeEngine().also { it.finishAtOnce = engineAnswersAtOnce; engines += it }
        },
        focus = focus,
        clockMs = { now },
        log = { log += it },
    )

    @Test
    fun `the focus is asked for before the phrase and given back after it ends`() {
        val v = voice()
        v.say(VoiceCue.DASH_GONE)
        assertEquals("the focus is held while the phrase is said", listOf("focus", "speak"), events)
        engines.single().pending.single().invoke(true, null)
        assertEquals(listOf("focus", "speak", "abandon"), events)
        assertEquals(1 to 1, focus.requests to focus.abandons)
    }

    @Test
    fun `the phrase is the words of the cue in the language of the app`() {
        val v = voice(Locale.forLanguageTag("es"))
        v.say(VoiceCue.WIFI_OFF)
        assertEquals(listOf("words of WIFI_OFF" to Locale.forLanguageTag("es")), engines.single().spoken)
    }

    @Test
    fun `with the switch off nothing is asked of the audio or the engine, not even to make it`() {
        switchOn = false
        val v = voice()
        for (cue in VoiceCue.entries) v.say(cue)
        assertTrue("events $events", events.isEmpty())
        assertEquals(0, made)
        assertEquals(0, focus.requests)
        assertTrue("a silent switch writes nothing: $log", log.isEmpty())
    }

    @Test
    fun `the switch is read at each phrase`() {
        val v = voice()
        switchOn = false
        v.say(VoiceCue.PHONE_HOT)
        assertEquals(0, made)
        switchOn = true
        v.say(VoiceCue.PHONE_HOT)
        assertEquals(1, engines.single().spoken.size)
    }

    @Test
    fun `an engine that is not ready is a line in the log and the focus given back, no more`() {
        val v = voice()
        v.say(VoiceCue.DASH_GONE)
        engines.single().pending.single().invoke(false, "the speech engine is not ready")
        assertEquals(listOf("focus", "speak", "abandon"), events)
        assertTrue(log.toString(), log.any { "DASH_GONE not said" in it && "not ready" in it })
    }

    @Test
    fun `a voice that lacks the language of the app is a line in the log and the next phrase may try again`() {
        val v = voice()
        v.say(VoiceCue.WIFI_OFF)
        engines.single().pending.single().invoke(false, "the voice lacks the language pt-BR")
        assertTrue(log.toString(), log.any { "lacks the language" in it })
        // Not said, so the minute does not hold the phrase back: the voice may have been installed meanwhile.
        v.say(VoiceCue.WIFI_OFF)
        assertEquals(2, engines.single().spoken.size)
    }

    @Test
    fun `an engine that fails at once is the same, the focus given back and the reason in the log`() {
        engineAnswersAtOnce = false to "the speech engine refused the phrase"
        val v = voice()
        v.say(VoiceCue.PHONE_HOT)
        assertEquals(listOf("focus", "speak", "abandon"), events)
        assertTrue(log.toString(), log.any { "PHONE_HOT not said" in it && "refused the phrase" in it })
    }

    @Test
    fun `an engine that cannot even be made costs the phrase only`() {
        failToMake = true
        val v = voice()
        v.say(VoiceCue.PHONE_HOT)
        assertEquals(listOf("focus", "abandon"), events)
        assertTrue(log.toString(), log.any { "IllegalStateException" in it })
    }

    @Test
    fun `a focus Android refuses, a call for one, keeps the phrase back`() {
        focusGranted = false
        val v = voice()
        v.say(VoiceCue.DASH_GONE)
        assertEquals(0, made)
        assertEquals(listOf("focus"), events)
        assertTrue(log.toString(), log.any { "refused the audio focus" in it })
    }

    @Test
    fun `the same phrase is not said again within the minute, and is after it`() {
        val v = voice()
        v.say(VoiceCue.PHONE_HOT)
        engines.single().pending.removeAt(0).invoke(true, null)
        now += 59_999
        v.say(VoiceCue.PHONE_HOT)
        assertEquals(1, engines.single().spoken.size)
        assertTrue(log.toString(), log.any { "PHONE_HOT not said (REPEATED)" in it })
        now += 1
        v.say(VoiceCue.PHONE_HOT)
        assertEquals(2, engines.single().spoken.size)
    }

    @Test
    fun `the dash being back is said only after its going was said`() {
        val v = voice()
        v.say(VoiceCue.DASH_BACK)
        assertEquals(0, made)
        v.say(VoiceCue.DASH_GONE)
        engines.single().pending.removeAt(0).invoke(true, null)
        now += 5_000
        v.say(VoiceCue.DASH_BACK)
        assertEquals(listOf("words of DASH_GONE", "words of DASH_BACK"), engines.single().spoken.map { it.first })
    }

    @Test
    fun `a going that could not be said does not make its return worth saying`() {
        val v = voice()
        v.say(VoiceCue.DASH_GONE)
        engines.single().pending.removeAt(0).invoke(false, "the speech engine is not ready")
        now += 5_000
        v.say(VoiceCue.DASH_BACK)
        assertEquals(1, engines.single().spoken.size)
    }

    @Test
    fun `one phrase at a time, and one whose end never comes is given up after a while`() {
        val v = voice()
        v.say(VoiceCue.DASH_GONE)
        now += 1_000
        v.say(VoiceCue.WIFI_OFF)
        assertEquals("the second waits for no one: it is dropped", 1, engines.single().spoken.size)
        assertEquals(1, focus.requests)
        val lost = engines.single().pending.removeAt(0)
        now += RiderVoice.MAX_SPEECH_MS
        v.say(VoiceCue.WIFI_OFF)
        assertEquals(2, engines.single().spoken.size)
        // The end of the phrase given up comes late: it is none of the new phrase's business.
        val abandonsBefore = focus.abandons
        lost.invoke(true, null)
        assertEquals(abandonsBefore, focus.abandons)
        assertFalse(events.last() == "abandon")
    }

    @Test
    fun `the engine is made once, at the first phrase, and let go with the projection`() {
        val v = voice()
        assertEquals(0, made)
        v.say(VoiceCue.WIFI_OFF)
        engines.single().pending.removeAt(0).invoke(true, null)
        now += 120_000
        v.say(VoiceCue.PHONE_HOT)
        assertEquals("the same engine", 1, made)
        engines.single().pending.removeAt(0).invoke(true, null)
        events.clear()
        v.release()
        assertEquals(1, engines.single().shutdowns)
        assertEquals("the focus is let go too", listOf("shutdown", "abandon"), events)
    }

    @Test
    fun `the next projection starts clean, with a new engine and nothing remembered`() {
        val v = voice()
        v.say(VoiceCue.DASH_GONE)
        engines.single().pending.removeAt(0).invoke(true, null)
        v.release()
        now += 1_000
        v.say(VoiceCue.DASH_BACK)
        assertEquals("no going was said in this projection", 1, made)
        v.say(VoiceCue.DASH_GONE)
        assertEquals(2, made)
        assertEquals("the minute does not carry over", 1, engines.last().spoken.size)
    }

    @Test
    fun `releasing a voice that never spoke does nothing but give the focus back`() {
        val v = voice()
        v.release()
        assertEquals(0, made)
        assertEquals(listOf("abandon"), events)
    }
}
