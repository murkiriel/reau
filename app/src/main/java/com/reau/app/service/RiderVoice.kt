package com.reau.app.service

import com.reau.app.R
import com.reau.app.util.MonotonicClock
import java.util.Locale

/** What the voice may say to the rider in the headset: the situations that already have a notice of their own, and the app coming back by itself. */
enum class VoiceCue {
    /** The dash is gone or stuck, and the bike has to be switched off and on (the notice of the stuck dash). */
    DASH_GONE,

    /** The dash came back, said only after [DASH_GONE] was said. */
    DASH_BACK,

    /** The phone is hot (the notice of the hot phone). */
    PHONE_HOT,

    /** The phone's Wi-Fi radio is off, so the dash cannot be joined. */
    WIFI_OFF,

    /**
     * REau came back by itself after the phone's system ended it ([ProjectionResume]). 26/11/2026 (A8 of the Xiaomi and realme plan): a dead app says nothing, and with the phone in a
     * pocket the rider hears nothing either way, so the one thing worth a phrase is that the projection is up again.
     */
    APP_BACK,
}

/**
 * What to say and when, as a pure rule: the switch off says nothing, the same phrase is not said again within [REPEAT_MS], and the dash being
 * back is said only after its going was. Given what was said so far ([Memory]) and the cue, it answers with the [Decision] and the memory the
 * saying leaves; the caller keeps that memory only when the phrase was really said.
 */
object VoiceRules {
    /** The least between two sayings of the same phrase. */
    const val REPEAT_MS = 60_000L

    /** What was said so far: when each phrase was last said (on the monotonic clock) and whether the dash's going was said and its return not yet. */
    data class Memory(val saidAtMs: Map<VoiceCue, Long> = emptyMap(), val dashGoneSaid: Boolean = false)

    enum class Why { SAY, SWITCH_OFF, REPEATED, NO_GONE_BEFORE }

    data class Decision(val why: Why, val after: Memory) {
        val say: Boolean get() = why == Why.SAY
    }

    fun decide(memory: Memory, cue: VoiceCue, switchOn: Boolean, nowMs: Long): Decision {
        if (!switchOn) return Decision(Why.SWITCH_OFF, memory)
        if (cue == VoiceCue.DASH_BACK && !memory.dashGoneSaid) return Decision(Why.NO_GONE_BEFORE, memory)
        val last = memory.saidAtMs[cue]
        if (last != null && nowMs - last < REPEAT_MS) return Decision(Why.REPEATED, memory)
        val goneSaid = when (cue) {
            VoiceCue.DASH_GONE -> true
            VoiceCue.DASH_BACK -> false
            else -> memory.dashGoneSaid
        }
        return Decision(Why.SAY, Memory(memory.saidAtMs + (cue to nowMs), goneSaid))
    }
}

/** The words of each phrase, a string resource in each language of the app. */
object VoiceWords {
    fun of(cue: VoiceCue): Int = when (cue) {
        VoiceCue.DASH_GONE -> R.string.voice_dash_gone
        VoiceCue.DASH_BACK -> R.string.voice_dash_back
        VoiceCue.PHONE_HOT -> R.string.voice_phone_hot
        VoiceCue.WIFI_OFF -> R.string.voice_wifi_off
        VoiceCue.APP_BACK -> R.string.voice_app_back
    }
}

/** The text-to-speech engine, as small as the voice needs it (the real one is [AndroidSpeech]; a test puts a false one). */
interface SpeechEngine {
    /**
     * Says [text] in [locale]. [done] is called exactly once, on any thread: with true when it was said to the end, with false and the reason when it could
     * not be (the engine not ready, the language missing, an error). Never throws for those.
     */
    fun speak(text: String, locale: Locale, done: (said: Boolean, why: String?) -> Unit)

    /** Lets the engine go. */
    fun shutdown()
}

/** The audio focus around a phrase: the music is lowered while it is said and comes back after. */
interface FocusPort {
    /** Asks for the focus, transient and letting the others lower their sound; false when Android refuses (a call, say). */
    fun request(): Boolean

    /** Gives the focus back. */
    fun abandon()
}

/**
 * The voice in the rider's headset for the few notices that matter with the phone in a pocket: said by Android's text-to-speech on the navigation
 * guidance channel, so it takes the headset the navigation voice takes and lowers the music instead of stopping it. Off by default ([enabled] is the
 * rider's switch, read at each phrase); the notices themselves are shown whatever happens here. The engine is made at the first phrase
 * ([engineFactory]) and let go by [release] when the projection ends; an engine that is not ready, a language it lacks or a focus Android refuses
 * cost a line in the log and the phrase, nothing else. [VoiceRules] decides what is said.
 *
 * One phrase at a time: one asked while another is being said is dropped. A phrase whose end never comes is given up after [MAX_SPEECH_MS].
 */
@Suppress("LongParameterList") // the switch, the words, the language, the engine, the focus, the clock and the log: each is what a test replaces
class RiderVoice(
    private val enabled: () -> Boolean,
    private val words: (VoiceCue) -> String,
    private val locale: () -> Locale,
    private val engineFactory: () -> SpeechEngine,
    private val focus: FocusPort,
    private val clockMs: () -> Long = MonotonicClock::nowMs,
    private val log: (String) -> Unit = {},
) {
    private var memory = VoiceRules.Memory()
    private var engine: SpeechEngine? = null
    private var sayingSinceMs: Long? = null
    private var utterance = 0

    @Synchronized
    // A speech engine of any make may throw anything (the notice is still shown); each reason not to speak ends here, said in the log.
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun say(cue: VoiceCue) {
        val now = clockMs()
        if (sayingSinceMs?.let { now - it < MAX_SPEECH_MS } == true) {
            log("voice: $cue not said, another phrase is still being said")
            return
        }
        val decision = VoiceRules.decide(memory, cue, enabled(), now)
        if (!decision.say) {
            if (decision.why != VoiceRules.Why.SWITCH_OFF) log("voice: $cue not said (${decision.why})")
            return
        }
        if (!focus.request()) {
            log("voice: $cue not said, Android refused the audio focus")
            return
        }
        val mine = ++utterance
        sayingSinceMs = now
        try {
            val speech = engine ?: engineFactory().also { engine = it }
            speech.speak(words(cue), locale()) { said, why -> finished(mine, cue, decision.after, said, why) }
        } catch (e: Exception) {
            log("voice: $cue not said, the speech engine failed: ${e.javaClass.simpleName}")
            finished(mine, cue, decision.after, said = false, why = null)
        }
    }

    @Synchronized
    private fun finished(number: Int, cue: VoiceCue, after: VoiceRules.Memory, said: Boolean, why: String?) {
        // The end of a phrase already given up (or of one before a release) is not the end of the one being said now.
        if (number != utterance) return
        sayingSinceMs = null
        focus.abandon()
        if (said) {
            memory = after
        } else {
            log("voice: $cue not said, ${why ?: "the speech engine could not"}")
        }
        // One line for each phrase really said (26/11/2026), so the log of a ride tells that the rider was told, and not only that the app tried.
        if (said) log("voice: $cue said")
    }

    /** The projection ended: the engine and the focus are let go, and what was said is forgotten (the next projection starts clean). */
    @Synchronized
    @Suppress("TooGenericExceptionCaught") // as in say
    fun release() {
        utterance++
        sayingSinceMs = null
        memory = VoiceRules.Memory()
        try {
            engine?.shutdown()
        } catch (e: Exception) {
            log("voice: letting the speech engine go failed: ${e.javaClass.simpleName}")
        }
        engine = null
        focus.abandon()
    }

    companion object {
        /** The longest a phrase may take before its end is no longer waited for. */
        const val MAX_SPEECH_MS = 20_000L

        @Volatile private var installed: RiderVoice? = null

        /** The voice of the projection that runs, for the places that have no handle on the service (the Wi-Fi manager's callbacks). */
        fun install(voice: RiderVoice) {
            installed = voice
        }

        /** Takes the voice away, but only if it is still [mine] (a service going away late does not take the next one's). */
        fun uninstall(mine: RiderVoice) {
            if (installed === mine) installed = null
        }

        /** Says [cue] with the installed voice; nothing when there is none. */
        @Suppress("TooGenericExceptionCaught") // called from the callbacks of other components: it must not throw into them
        fun speak(cue: VoiceCue) {
            try {
                installed?.say(cue)
            } catch (e: Exception) {
                installed?.log("voice: $cue failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
