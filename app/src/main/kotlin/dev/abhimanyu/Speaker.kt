package dev.abhimanyu

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dev.abhimanyu.core.Utterance
import java.util.Locale

/**
 * Spoken replies through Android's offline text-to-speech, with the Indian English voice for
 * English and the Hindi voice for Hindi and Hinglish. If the Hindi voice isn't installed, the
 * Roman fallback is spoken with the English voice instead of saying nothing.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val prefs = Prefs(context)

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

    /** Offline voices for a language ("en" or "hi"). Network voices are left out: they would
     *  send your replies to a server, even though Abhimanyu itself has no internet access. */
    fun offlineVoices(lang: String): List<Voice> {
        if (!ready) return emptyList()
        val want = if (lang == "hi") hiIn else enIn
        return runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { !it.isNetworkConnectionRequired && it.locale.language == want.language && it.locale.country == want.country }
            .filter { KEY_NOT_INSTALLED !in it.features.orEmpty() }
            .sortedBy { it.name }
    }

    /** A guess at the voice's gender from Google's voice names; null if unknown. */
    fun genderOf(v: Voice): String? {
        val code = v.name.substringAfter("-x-", "").take(3)
        return when (code) {
            in MALE -> "male"
            in FEMALE -> "female"
            else -> null
        }
    }

    /** The voice to use: the one picked in Preferences, else a male one, else the default. */
    private fun voiceFor(lang: String): Voice? {
        val picked = if (lang == "hi") prefs.voiceHindi else prefs.voiceEnglish
        val voices = offlineVoices(lang)
        return voices.firstOrNull { it.name == picked } ?: voices.firstOrNull { genderOf(it) == "male" }
    }

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
        voiceFor(if (locale == hiIn) "hi" else "en")?.let { runCatching { tts.setVoice(it) } }
        synchronized(this) { pendingDone = onDone }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "reply-${System.nanoTime()}")
        if (result != TextToSpeech.SUCCESS) finish()
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun isSpeaking() = ready && tts.isSpeaking

    fun shutdown() = tts.shutdown()

    /** Plays a short sample in [voice], for the voice picker. */
    fun sample(voice: Voice, hindi: Boolean) {
        if (!ready) return
        tts.setLanguage(if (hindi) hiIn else enIn)
        runCatching { tts.setVoice(voice) }
        val text = if (hindi) "नमस्ते, मैं अभिमन्यु हूँ। टॉर्च चालू कर दी।" else "Hello, I'm Abhimanyu. Flashlight on."
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "sample")
    }

    companion object {
        private const val KEY_NOT_INSTALLED = "notInstalled"
        // Google's offline Indian voices by their name code. Google doesn't publish genders,
        // so these are best guesses; the picker lets you listen and choose.
        private val MALE = setOf("end", "ene", "hic", "hie")
        private val FEMALE = setOf("ena", "enc", "hia", "hid")
    }
}
