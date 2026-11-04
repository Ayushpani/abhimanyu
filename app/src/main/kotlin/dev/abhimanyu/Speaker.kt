package dev.abhimanyu

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.abhimanyu.core.Utterance
import java.util.Locale

/**
 * Spoken replies through Android's offline text-to-speech, with the Indian English voice for
 * English and the Hindi voice for Hindi and Hinglish. If the Hindi voice isn't installed, the
 * Roman fallback is spoken with the English voice instead of saying nothing.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false
    private var pendingDone: (() -> Unit)? = null

    private val enIn = Locale.forLanguageTag("en-IN")
    private val hiIn = Locale.forLanguageTag("hi-IN")

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish()
            override fun onError(utteranceId: String?, errorCode: Int) = finish()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish()
        })
        ready = true
    }

    private fun finish() {
        val cb = synchronized(this) { pendingDone.also { pendingDone = null } }
        cb?.invoke()
    }

    private fun usable(locale: Locale): Boolean =
        ready && tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE

    fun hasEnglishVoice() = usable(enIn) || usable(Locale.ENGLISH)
    fun hasHindiVoice() = usable(hiIn)

    fun speak(u: Utterance, onDone: () -> Unit = {}) {
        if (!ready) { onDone(); return }
        val wantHindi = u.locale == "hi-IN"
        val (locale, text) = when {
            wantHindi && usable(hiIn) -> hiIn to u.text
            wantHindi -> (if (usable(enIn)) enIn else Locale.ENGLISH) to u.fallback
            usable(enIn) -> enIn to u.text
            else -> Locale.ENGLISH to u.text
        }
        tts.setLanguage(locale)
        synchronized(this) { pendingDone = onDone }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "reply-${System.nanoTime()}")
        if (result != TextToSpeech.SUCCESS) finish()
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun isSpeaking() = ready && tts.isSpeaking

    fun shutdown() = tts.shutdown()
}
