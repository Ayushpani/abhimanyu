package dev.abhimanyu.core

/** One recognizer's transcript of the same audio, with its average word confidence (0-1). */
data class Transcript(val text: String, val confidence: Float, val source: String)

/**
 * Phase 1 runs two small offline recognizers (Indian English and Hindi) on the same audio.
 * This picks the transcript that parses to a known command, preferring the more confident one.
 */
object Understanding {

    data class Result(val parsed: Parsed?, val best: Transcript?)

    fun choose(transcripts: List<Transcript>): Result {
        val heard = transcripts.filter { it.text.isNotBlank() }
        val parsed = heard
            .mapNotNull { t -> CommandParser.parse(t.text)?.let { t to it } }
            .maxByOrNull { (t, _) -> t.confidence }
        if (parsed != null) return Result(parsed.second, parsed.first)
        return Result(null, heard.maxByOrNull { it.confidence })
    }

    /** Best guess at the language of something we couldn't parse, so "say that again" matches it. */
    fun guessLang(t: Transcript?): Lang =
        if (t == null) Lang.EN else LanguageDetector.detect(t.text, Normalizer.normalize(t.text))
}

/**
 * Words the parser understands, used to restrict the small Vosk models to the command
 * vocabulary. Restricting them makes recognition far more accurate for a fixed command set.
 */
object Vocabulary {
    private val EXTRA_ENGLISH = setOf(
        "flashlight", "torch", "flash", "light", "on", "off", "turn", "switch", "set", "a", "an", "the", "for",
        "to", "at", "in", "of", "my", "me", "it", "is", "and", "please", "what", "whats", "time", "date", "day",
        "today", "tomorrow", "battery", "charge", "charging", "volume", "media", "ring", "ringer", "ringtone",
        "alarm", "timer", "minute", "minutes", "second", "seconds", "hour", "hours", "up", "down", "mute", "unmute",
        "silent", "vibrate", "normal", "mode", "sound", "do", "not", "disturb", "dnd", "brightness", "screen",
        "bright", "dim", "low", "medium", "high", "max", "maximum", "min", "minimum", "full", "half", "quarter",
        "past", "percent", "am", "pm", "morning", "evening", "night", "afternoon", "oclock", "play", "pause",
        "resume", "next", "previous", "skip", "song", "music", "track", "stop", "cancel", "delete", "remove",
        "dismiss", "wake", "repeat", "again", "help", "lock", "phone", "reply", "speak", "english", "hindi",
        "language", "match", "same", "captions", "caption", "louder", "quieter", "increase", "decrease",
        "lower", "raise", "enable", "disable", "start", "can", "you", "that", "once", "more",
    )

    fun english(): List<String> =
        (EXTRA_ENGLISH + Numbers.allWords + Normalizer.ROMAN_VARIANTS.keys + Normalizer.ROMAN_VARIANTS.values)
            .filter { Regex("[a-z]+").matches(it) }
            .sorted()

    fun hindi(): List<String> =
        (Normalizer.DEVANAGARI.keys + Normalizer.DEVANAGARI_NUMBERS.keys + "दो").sorted()

    /** Vosk grammar JSON: one phrase per word plus "[unk]" for anything else. */
    fun grammarJson(words: List<String>): String =
        (words + "[unk]").joinToString(prefix = "[", postfix = "]") { "\"" + it.replace("\"", "") + "\"" }
}
