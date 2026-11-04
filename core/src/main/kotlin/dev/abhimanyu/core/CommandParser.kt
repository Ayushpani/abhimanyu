package dev.abhimanyu.core

/** A parsed command plus the language it was spoken in. */
data class Parsed(val command: Command, val lang: Lang, val transcript: String)

/**
 * Rule-based parser for Tier 0 commands in English, Hindi and Hinglish.
 *
 * This is the Phase 1 path, and later the fallback when Gemma 4 is unloaded. It only ever
 * returns commands from the fixed catalog in [Command]; anything else is `null`.
 */
object CommandParser {

    private val ON = setOf("on", "chalu", "jalao", "start", "enable", "activate", "kholo")
    private val OFF = setOf("off", "band", "bujhao", "disable", "deactivate", "stop")
    private val CANCEL = setOf("cancel", "delete", "remove", "hatao", "dismiss", "band", "off", "clear")
    private val UP = setOf("up", "badhao", "increase", "raise", "louder", "zyada", "tez", "more", "higher", "brighter")
    private val DOWN = setOf("down", "kam", "decrease", "lower", "reduce", "quieter", "softer", "dheema", "ghatao", "less", "darker")
    private val MAX = setOf("max", "maximum", "full", "poora", "highest", "loudest")
    private val MIN = setOf("min", "minimum", "lowest")
    private val TIME_UNITS = setOf("second", "minute", "hour", "ghanta")
    private val CLOCK_FOLLOWERS = setOf("baje", "bajkar", "oclock", "am", "pm")
    private val MUSIC = setOf("music", "song", "gaana", "track", "video", "media", "audio", "podcast")
    private val STOP_WORDS = setOf("stop", "cancel", "ruko", "bas", "roko", "chup", "quiet", "enough")

    fun parse(raw: String): Parsed? {
        val t = Normalizer.normalize(raw)
        if (t.isEmpty()) return null
        val command = parseTokens(t) ?: return null
        return Parsed(command, LanguageDetector.detect(raw, t), raw.trim())
    }

    fun parseTokens(t: List<String>): Command? {
        val s = t.toSet()
        fun has(vararg w: String) = w.any { it in s }
        fun hasAny(set: Set<String>) = set.any { it in s }
        fun phrase(p: String): Boolean {
            val words = p.split(' ')
            return (0..t.size - words.size).any { i -> words.indices.all { t[i + it] == words[it] } }
        }

        // Reply language: "reply in English", "Hindi mein bolo", "match my language"
        if (has("reply", "bolo", "speak", "talk", "baat", "language", "bhasha")) {
            when {
                has("match", "same") -> return Command.ReplyLanguage(ReplyLanguagePref.MATCH)
                has("english") -> return Command.ReplyLanguage(ReplyLanguagePref.ENGLISH)
                has("hindi") -> return Command.ReplyLanguage(ReplyLanguagePref.HINDI)
            }
        }

        if (has("caption")) {
            if (hasAny(OFF) || has("hide", "hatao")) return Command.Captions(false)
            return Command.Captions(true)
        }

        // "stop", "ruko", "bas" on their own end whatever is happening.
        if (t.size <= 3 && hasAny(STOP_WORDS) && !hasAny(MUSIC) && !has("timer", "alarm", "torch", "flashlight")) {
            return Command.Stop
        }

        if (has("repeat", "dobara") || phrase("phir se") || phrase("do that again") || phrase("once more") ||
            (t.size <= 3 && has("again"))
        ) return Command.Repeat

        if (has("help", "madad") || phrase("what can you do") || phrase("kya kar sakte")) return Command.Help

        if (has("lock") && has("phone", "screen", "karo", "the", "my")) return Command.LockPhone

        if (has("torch", "flashlight") || phrase("flash light")) {
            return Command.Flashlight(on = !hasAny(OFF))
        }

        if (has("timer")) {
            val seconds = Durations.parseSeconds(t)
            if (seconds == null && hasAny(CANCEL + STOP_WORDS)) return Command.Unsupported("cancel timer")
            return seconds?.let { Command.SetTimer(it.coerceIn(1, 24 * 3600)) }
        }

        if ((has("alarm", "uthana") || phrase("wake me")) && !has("volume", "awaaz")) {
            val time = Clock.parse(t)
            if (hasAny(CANCEL) || (has("turn") && has("off"))) return Command.CancelAlarm(time)
            return time?.let { Command.SetAlarm(it) }
        }

        if (has("dnd", "pareshan") || phrase("do not disturb") || phrase("dont disturb") || has("disturb")) {
            val off = hasAny(OFF - "band") || (has("band") && !has("mat"))
            if (off && !has("pareshan")) return Command.Dnd(on = false)
            if (has("pareshan") && has("band") && !has("mat")) return Command.Dnd(on = false)
            val minutes = Durations.parseSeconds(t)?.let { (it + 59) / 60 }
            return Command.Dnd(on = true, minutes = minutes)
        }

        if (has("unmute") || (has("mute") && hasAny(OFF))) return Command.Mute(streamOf(s), mute = false)
        if (has("mute")) return Command.Mute(streamOf(s), mute = true)

        if (has("volume", "awaaz", "louder", "quieter") || (has("sound") && (hasAny(UP) || hasAny(DOWN)))) {
            val stream = streamOf(s)
            if (has("awaaz") && has("band") && !hasAny(UP)) return Command.Mute(stream, mute = true)
            val adjust = levelAdjust(t, s) ?: return null
            return Command.Volume(stream, adjust)
        }

        if (has("silent")) return Command.Ringer(RingerMode.SILENT)
        if (has("vibrate", "vibration")) return Command.Ringer(RingerMode.VIBRATE)
        if (has("ringer", "ring") || phrase("sound on") || phrase("normal mode")) return Command.Ringer(RingerMode.NORMAL)

        if (has("brightness", "roshni", "chamak") || (has("screen") && has("bright", "dim"))) {
            val adjust = when {
                has("low", "dim") -> Adjust.To(15)
                has("medium") || (has("half", "aadha")) -> Adjust.To(50)
                has("high") -> Adjust.To(100)
                else -> levelAdjust(t, s)
            } ?: return null
            return Command.Brightness(adjust)
        }

        // Media keys
        when {
            has("pause") || (hasAny(MUSIC) && (has("stop", "roko", "ruko") || has("band"))) ->
                return Command.Media(MediaAction.PAUSE)
            has("next", "skip", "agla") -> return Command.Media(MediaAction.NEXT)
            has("previous", "pichla") || phrase("last song") -> return Command.Media(MediaAction.PREVIOUS)
            has("play", "resume", "chalao") -> return Command.Media(MediaAction.PLAY)
        }

        if (has("battery", "charge", "charging")) return Command.TellBattery
        if (has("date", "tareekh") || phrase("what day") || phrase("kya din") || phrase("kaun sa din")) return Command.TellDate
        if (has("time", "samay") || (has("kitne") && has("baje"))) return Command.TellTime

        return null
    }

    private fun streamOf(s: Set<String>): VolumeStream = when {
        "ring" in s || "ringer" in s || "ringtone" in s || "call" in s -> VolumeStream.RING
        "alarm" in s -> VolumeStream.ALARM
        else -> VolumeStream.MEDIA
    }

    private fun levelAdjust(t: List<String>, s: Set<String>): Adjust? {
        val n = Numbers.first(t)
        return when {
            n != null && n > 1.0 -> Adjust.To(n.toInt().coerceIn(0, 100))
            MAX.any { it in s } -> Adjust.To(100)
            "half" in s || "aadha" in s -> Adjust.To(50)
            MIN.any { it in s } -> Adjust.To(0)
            UP.any { it in s } -> Adjust.Up
            DOWN.any { it in s } || "low" in s -> Adjust.Down
            else -> null
        }
    }

    /** Durations like "10 minutes", "dedh ghanta", "an hour and a half", "1 hour 30 minutes". */
    object Durations {
        fun parseSeconds(t: List<String>): Int? {
            var total = 0.0
            var found = false
            var i = 0
            while (i < t.size) {
                val n = Numbers.at(t, i, TIME_UNITS)
                if (n == null) { i++; continue }
                var j = i + n.length
                while (t.getOrNull(j) in setOf("a", "an", "ka", "ke")) j++
                val unit = t.getOrNull(j)
                val mult = when (unit) {
                    "second" -> 1
                    "minute" -> 60
                    "hour", "ghanta" -> 3600
                    else -> null
                }
                if (mult == null) { i++; continue }
                var value = n.value
                // "an hour and a half"
                if (t.getOrNull(j + 1) == "and" && t.getOrNull(j + 2) == "a" && t.getOrNull(j + 3) == "half") {
                    value += 0.5; j += 3
                }
                total += value * mult
                found = true
                i = j + 1
            }
            return if (found && total >= 1) total.toInt() else null
        }
    }

    /** Clock times like "6:30 am", "six thirty", "half past six", "saade chhe baje", "kal subah 7 baje". */
    object Clock {
        private val AM = setOf("am", "morning", "subah")
        private val PM = setOf("pm", "evening", "afternoon", "shaam", "dopahar", "tonight")
        private val NIGHT = setOf("night", "raat")

        fun parse(t: List<String>): AlarmTime? {
            val s = t.toSet()
            val tomorrow = "tomorrow" in s || "kal" in s
            val (h, m) = findTime(t) ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            val meridiem: Meridiem? = when {
                h >= 13 || h == 0 -> null
                AM.any { it in s } -> Meridiem.AM
                PM.any { it in s } -> Meridiem.PM
                NIGHT.any { it in s } -> when (h) {
                    12, in 1..5 -> Meridiem.AM
                    else -> Meridiem.PM
                }
                else -> null
            }
            return AlarmTime(h, m, meridiem, tomorrow)
        }

        private fun findTime(t: List<String>): Pair<Int, Int>? {
            t.firstOrNull { Regex("\\d{1,2}:\\d{2}").matches(it) }?.let {
                val (a, b) = it.split(':'); return a.toInt() to b.toInt()
            }
            for (i in t.indices) {
                // "half past six", "quarter past six", "quarter to seven"
                if (t[i] == "half" && t.getOrNull(i + 1) == "past") {
                    Numbers.at(t, i + 2)?.let { return it.value.toInt() to 30 }
                }
                if (t[i] == "quarter" && t.getOrNull(i + 1) == "past") {
                    Numbers.at(t, i + 2)?.let { return it.value.toInt() to 15 }
                }
                if (t[i] == "quarter" && t.getOrNull(i + 1) == "to") {
                    Numbers.at(t, i + 2)?.let { return ((it.value.toInt() + 11) % 12).let { h -> if (h == 0) 12 else h } to 45 }
                }
                val n = Numbers.at(t, i, CLOCK_FOLLOWERS + TIME_UNITS) ?: continue
                // Skip numbers that are clearly durations ("in 10 minutes").
                val next = t.getOrNull(i + n.length)
                if (next in TIME_UNITS) continue
                val v = n.value
                var hour = v.toInt()
                var minute = Math.round((v - hour) * 60).toInt()
                if (minute < 0) { hour -= 1; minute += 60 }
                var j = i + n.length
                if (t.getOrNull(j) == "bajkar" || t.getOrNull(j) == "baje") j++
                // a minutes number right after: "six thirty", "chhe bajkar das minute"
                if (minute == 0) {
                    val mins = Numbers.at(t, j, TIME_UNITS)
                    if (mins != null && mins.value >= 0 && mins.value < 60 && mins.value == Math.floor(mins.value)) {
                        val after = t.getOrNull(j + mins.length)
                        if (after == null || after == "minute" || after in AM || after in PM || after in NIGHT ||
                            after in setOf("ka", "ke", "par", "pe", "baje", "oclock", "tomorrow", "kal")
                        ) {
                            minute = mins.value.toInt()
                        }
                    }
                }
                return hour to minute
            }
            return null
        }
    }
}

/** Decides whether a transcript was English, Hindi or Hinglish. */
object LanguageDetector {
    /** English loanwords that, in a Devanagari transcript, mark it as Hinglish rather than Hindi. */
    // Only words that have an everyday Hindi alternative count: "टाइमर" or "अलार्म" are simply
    // Hindi now, but "ऑन" (vs "चालू") or "वॉल्यूम" (vs "आवाज़") signal mixing.
    private val LOANWORDS = setOf(
        "on", "off", "volume", "set", "play", "pause", "next", "mute", "percent", "cancel", "help",
        "time", "brightness",
    )

    /** Hindi words that, in a Roman transcript, mark it as Hinglish rather than English. */
    private val HINDI_MARKERS = setOf(
        "karo", "band", "chalu", "lagao", "chalao", "jalao", "bujhao", "kitne", "kitna", "baje", "bajkar",
        "hai", "hain", "ka", "ki", "ke", "ko", "mein", "se", "ghanta", "subah", "shaam", "raat", "dopahar",
        "kal", "aaj", "abhi", "badhao", "kam", "zyada", "tez", "ruko", "roko", "gaana", "agla", "pichla",
        "phir", "kholo", "tareekh", "din", "mat", "pareshan", "awaaz", "roshni", "chamak", "saade", "sava",
        "paune", "dedh", "dhai", "aadha", "hatao", "batao", "bolo", "kya", "samay", "madad", "uthana",
        "dobara", "poora", "dheema", "ghatao", "das", "dus", "pachas", "chhe", "saat", "aath", "nau",
    )

    fun detect(raw: String, tokens: List<String>): Lang {
        if (Normalizer.containsDevanagari(raw)) {
            return if (tokens.any { it in LOANWORDS }) Lang.HINGLISH else Lang.HI
        }
        return if (tokens.any { it in HINDI_MARKERS }) Lang.HINGLISH else Lang.EN
    }
}
