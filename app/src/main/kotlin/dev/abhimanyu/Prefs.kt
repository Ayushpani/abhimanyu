package dev.abhimanyu

import android.content.Context
import dev.abhimanyu.core.ReplyLanguagePref

/** Small, non-secret preferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var replyLanguage: ReplyLanguagePref
        get() = runCatching { ReplyLanguagePref.valueOf(sp.getString("reply_lang", null) ?: "MATCH") }
            .getOrDefault(ReplyLanguagePref.MATCH)
        set(v) = sp.edit().putString("reply_lang", v.name).apply()

    var captions: Boolean
        get() = sp.getBoolean("captions", true)
        set(v) = sp.edit().putBoolean("captions", v).apply()

    /** Restrict the small recognizers to command words. More accurate; turn off to debug. */
    var restrictVocabulary: Boolean
        get() = sp.getBoolean("restrict_vocab", true)
        set(v) = sp.edit().putBoolean("restrict_vocab", v).apply()

    var showTimings: Boolean
        get() = sp.getBoolean("show_timings", true)
        set(v) = sp.edit().putBoolean("show_timings", v).apply()

    /** Keep listening for the wake word in the background, even with the app closed. */
    var alwaysListening: Boolean
        get() = sp.getBoolean("always_listening", false)
        set(v) = sp.edit().putBoolean("always_listening", v).apply()

    /** Gemma 4 multi-token prediction. Faster, but new; off by default. */
    var fastDecoding: Boolean
        get() = sp.getBoolean("fast_decoding", false)
        set(v) = sp.edit().putBoolean("fast_decoding", v).apply()

    /** Chosen offline TTS voice names, or null for automatic (prefers a male voice). */
    var voiceEnglish: String?
        get() = sp.getString("voice_en", null)
        set(v) = sp.edit().putString("voice_en", v).apply()
    var voiceHindi: String?
        get() = sp.getString("voice_hi", null)
        set(v) = sp.edit().putString("voice_hi", v).apply()

    var dndOffAt: Long
        get() = sp.getLong("dnd_off_at", 0L)
        set(v) = sp.edit().putLong("dnd_off_at", v).apply()
}
