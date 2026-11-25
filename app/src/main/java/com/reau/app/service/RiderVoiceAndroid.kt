package com.reau.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The audio the voice uses: speech on the navigation guidance channel, the one that takes the headset the navigation voice takes. */
internal fun voiceAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/** The audio focus of a phrase: transient, and the others may lower their sound instead of stopping ([RiderVoice] gives it back after the phrase). */
internal class AndroidFocus(context: Context) : FocusPort {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    @Synchronized
    override fun request(): Boolean {
        val asked = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(voiceAttributes()).build()
        request = asked
        return audio.requestAudioFocus(asked) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Synchronized
    override fun abandon() {
        request?.let { audio.abandonAudioFocusRequest(it) }
        request = null
    }
}

/**
 * Android's [TextToSpeech], made at the first phrase. It takes a moment to start: the phrase asked meanwhile waits for it (one, the last), and is
 * said when the engine is ready, or reported as not said when it never is. A language the voice lacks is reported too. The engine's callbacks call
 * [SpeechEngine.speak]'s `done` outside this object's lock: the voice's own lock is taken inside it, and the two must never be taken the other way round.
 */
internal class AndroidSpeech(private val context: Context) : SpeechEngine {
    private class Waiting(val text: String, val locale: Locale, val done: (Boolean, String?) -> Unit)

    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var waiting: Waiting? = null
    private val ending = ConcurrentHashMap<String, (Boolean, String?) -> Unit>()

    override fun speak(text: String, locale: Locale, done: (said: Boolean, why: String?) -> Unit) {
        var refusal: String? = null
        var replaced: Waiting? = null
        var engine: TextToSpeech? = null
        synchronized(lock) {
            when {
                failed -> refusal = "the speech engine could not start"
                ready -> engine = tts
                else -> {
                    replaced = waiting
                    waiting = Waiting(text, locale, done)
                    if (tts == null) tts = TextToSpeech(context.applicationContext) { status -> initialised(status) }
                }
            }
            Unit
        }
        replaced?.done?.invoke(false, "another phrase came before the speech engine was ready")
        refusal?.let { done(false, it) }
        engine?.let { say(it, text, locale, done) }
    }

    /** The engine's answer to its start (on the main thread). */
    private fun initialised(status: Int) {
        var toSay: Waiting? = null
        var engine: TextToSpeech? = null
        var refusal: Waiting? = null
        synchronized(lock) {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                ready = true
                engine = tts
                toSay = waiting
            } else {
                failed = true
                refusal = waiting
            }
            waiting = null
            Unit
        }
        engine?.let { e ->
            e.setAudioAttributes(voiceAttributes())
            e.setOnUtteranceProgressListener(progress)
        }
        refusal?.done?.invoke(false, "the speech engine is not ready")
        toSay?.let { w -> engine?.let { say(it, w.text, w.locale, w.done) } }
    }

    private fun say(engine: TextToSpeech, text: String, locale: Locale, done: (Boolean, String?) -> Unit) {
        val language = engine.setLanguage(locale)
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            done(false, "the voice lacks the language ${locale.toLanguageTag()}")
            return
        }
        val id = UUID.randomUUID().toString()
        ending[id] = done
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            ending.remove(id)?.invoke(false, "the speech engine refused the phrase")
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { ending.remove(it)?.invoke(true, null) }
        }

        @Deprecated("Replaced by onError(String, Int)")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { ending.remove(it)?.invoke(false, "the speech engine failed while speaking") }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { ending.remove(it)?.invoke(false, "the speech engine failed while speaking (error $errorCode)") }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { ending.remove(it)?.invoke(false, "the phrase was cut") }
        }
    }

    override fun shutdown() {
        var engine: TextToSpeech? = null
        var abandoned: Waiting? = null
        synchronized(lock) {
            engine = tts
            abandoned = waiting
            tts = null
            waiting = null
            ready = false
            Unit
        }
        engine?.stop()
        engine?.shutdown()
        abandoned?.done?.invoke(false, "the projection ended")
        ending.keys.toList().forEach { id -> ending.remove(id)?.invoke(false, "the projection ended") }
    }
}
