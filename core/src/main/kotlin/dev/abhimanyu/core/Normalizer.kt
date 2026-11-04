package dev.abhimanyu.core

/**
 * Turns a raw transcript (English, Hindi in Devanagari, or Romanised Hinglish) into a list of
 * lowercase Roman tokens with one canonical spelling per word, so a single rule set can parse
 * all three languages. "टॉर्च बंद करो", "torch bandh kro" and "torch band karo" all become
 * [torch, band, karo].
 */
object Normalizer {

    /** Devanagari words → canonical Roman tokens (space-separated when one word maps to several). */
    val DEVANAGARI: Map<String, String> = mapOf(
        // devices and features
        "टॉर्च" to "torch", "टोर्च" to "torch", "टार्च" to "torch",
        "फ्लैशलाइट" to "flashlight", "फ़्लैशलाइट" to "flashlight", "फ्लैश" to "flash", "लाइट" to "light",
        "टाइमर" to "timer", "अलार्म" to "alarm",
        "वॉल्यूम" to "volume", "वॉल्युम" to "volume", "वोल्यूम" to "volume", "आवाज़" to "awaaz", "आवाज" to "awaaz",
        "म्यूट" to "mute", "अनम्यूट" to "unmute", "साइलेंट" to "silent", "वाइब्रेट" to "vibrate",
        "वाइब्रेशन" to "vibrate", "रिंग" to "ring", "रिंगर" to "ringer", "रिंगटोन" to "ringtone",
        "ब्राइटनेस" to "brightness", "रोशनी" to "roshni", "चमक" to "chamak", "स्क्रीन" to "screen",
        "बैटरी" to "battery", "चार्ज" to "charge", "चार्जिंग" to "charging",
        "गाना" to "gaana", "गाने" to "gaana", "म्यूज़िक" to "music", "म्यूजिक" to "music", "संगीत" to "music",
        "मीडिया" to "media", "वीडियो" to "video",
        "डीएनडी" to "dnd", "डू" to "do", "नॉट" to "not", "डिस्टर्ब" to "disturb", "परेशान" to "pareshan",
        "कैप्शन" to "caption", "कैप्शंस" to "caption",
        "फ़ोन" to "phone", "फोन" to "phone", "लॉक" to "lock",
        // verbs and states
        "चालू" to "chalu", "ऑन" to "on", "आन" to "on", "बंद" to "band", "बन्द" to "band", "ऑफ" to "off", "ऑफ़" to "off",
        "करो" to "karo", "करें" to "karo", "कर" to "karo", "करना" to "karo", "करिए" to "karo", "कीजिए" to "karo",
        "करदो" to "karo", "दीजिए" to "karo",
        "लगाओ" to "lagao", "लगा" to "lagao", "लगाना" to "lagao", "लगाएं" to "lagao", "सेट" to "set",
        "जलाओ" to "jalao", "जला" to "jalao", "बुझाओ" to "bujhao", "बुझा" to "bujhao",
        "बढ़ाओ" to "badhao", "बढ़ा" to "badhao", "बढाओ" to "badhao", "तेज़" to "tez", "तेज" to "tez",
        "ज़्यादा" to "zyada", "ज्यादा" to "zyada", "कम" to "kam", "घटाओ" to "ghatao", "धीमा" to "dheema",
        "धीरे" to "dheema", "पूरा" to "poora", "पूरी" to "poora", "पूरे" to "poora",
        "चलाओ" to "chalao", "चला" to "chalao", "बजाओ" to "chalao", "प्ले" to "play", "पॉज़" to "pause",
        "पॉज" to "pause", "रोको" to "roko", "रोक" to "roko", "रुको" to "ruko", "रुक" to "ruko", "बस" to "bas",
        "अगला" to "agla", "अगले" to "agla", "अगली" to "agla", "नेक्स्ट" to "next",
        "पिछला" to "pichla", "पिछले" to "pichla", "पिछली" to "pichla",
        "हटाओ" to "hatao", "हटा" to "hatao", "कैंसल" to "cancel", "मिटाओ" to "hatao",
        "उठाना" to "uthana", "उठा" to "uthana", "जगाना" to "uthana", "जगा" to "uthana",
        "बोलो" to "bolo", "बोलिए" to "bolo", "बोलना" to "bolo", "बताओ" to "batao", "बताइए" to "batao",
        "हिंदी" to "hindi", "हिन्दी" to "hindi", "अंग्रेज़ी" to "english", "अंग्रेजी" to "english", "इंग्लिश" to "english",
        "मदद" to "madad", "हेल्प" to "help", "फिर" to "phir", "फिरसे" to "phir se", "दोबारा" to "dobara",
        "मत" to "mat", "मुझे" to "mujhe",
        // time words
        "मिनट" to "minute", "मिनिट" to "minute", "सेकंड" to "second", "सेकेंड" to "second",
        "घंटा" to "ghanta", "घंटे" to "ghanta", "घंटों" to "ghanta",
        "बजे" to "baje", "बजकर" to "bajkar", "बजके" to "bajkar", "बज" to "baje",
        "सुबह" to "subah", "सवेरे" to "subah", "दोपहर" to "dopahar", "शाम" to "shaam", "रात" to "raat",
        "कल" to "kal", "आज" to "aaj", "अभी" to "abhi", "समय" to "samay", "टाइम" to "time",
        "तारीख" to "tareekh", "तारीख़" to "tareekh", "दिन" to "din", "कितने" to "kitne", "कितना" to "kitna",
        "कितनी" to "kitna", "क्या" to "kya", "प्रतिशत" to "percent", "परसेंट" to "percent", "फ़ीसदी" to "percent",
        // fractions
        "साढ़े" to "saade", "साढे" to "saade", "सवा" to "sava", "पौने" to "paune", "डेढ़" to "dedh", "डेढ" to "dedh",
        "ढाई" to "dhai", "आधा" to "aadha", "आधे" to "aadha", "आधी" to "aadha",
        // grammar
        "का" to "ka", "की" to "ki", "के" to "ke", "को" to "ko", "में" to "mein", "से" to "se", "है" to "hai",
        "हैं" to "hain", "हुए" to "hue", "और" to "aur", "पर" to "par", "लिए" to "liye",
    )

    /** Devanagari numbers → digits. */
    val DEVANAGARI_NUMBERS: Map<String, Int> = mapOf(
        "एक" to 1, "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5, "छह" to 6, "छः" to 6,
        "छे" to 6, "छै" to 6, "सात" to 7, "आठ" to 8, "नौ" to 9, "दस" to 10, "ग्यारह" to 11, "बारह" to 12,
        "तेरह" to 13, "चौदह" to 14, "पंद्रह" to 15, "पन्द्रह" to 15, "सोलह" to 16, "सत्रह" to 17,
        "अठारह" to 18, "उन्नीस" to 19, "बीस" to 20, "पच्चीस" to 25, "तीस" to 30, "पैंतीस" to 35,
        "चालीस" to 40, "पैंतालीस" to 45, "पचास" to 50, "पचपन" to 55, "साठ" to 60, "सत्तर" to 70,
        "अस्सी" to 80, "नब्बे" to 90, "सौ" to 100,
        // "दो" (2) is ambiguous with the verb "do" (give); it is mapped to "do" and resolved by context.
    )

    /** Common Roman spellings → one canonical spelling. */
    val ROMAN_VARIANTS: Map<String, String> = mapOf(
        "chaalu" to "chalu", "bandh" to "band", "bund" to "band",
        "kro" to "karo", "kar" to "karo", "kardo" to "karo", "karde" to "karo", "kariye" to "karo", "karna" to "karo",
        "laga" to "lagao", "lagado" to "lagao", "lagaa" to "lagao", "lagana" to "lagao", "lagaao" to "lagao",
        "chala" to "chalao", "chalaao" to "chalao", "bajao" to "chalao", "bajaao" to "chalao",
        "awaz" to "awaaz", "aawaz" to "awaaz", "aawaaz" to "awaaz", "avaaz" to "awaaz",
        "jyada" to "zyada", "jada" to "zyada", "ziada" to "zyada", "zyaada" to "zyada",
        "badha" to "badhao", "badhaao" to "badhao", "tej" to "tez",
        "ghante" to "ghanta", "ghanton" to "ghanta",
        "minutes" to "minute", "mins" to "minute", "min" to "minute", "minat" to "minute", "minit" to "minute",
        "seconds" to "second", "secs" to "second", "sec" to "second",
        "hours" to "hour", "hrs" to "hour", "hr" to "hour",
        "baj" to "baje", "bajke" to "bajkar", "bajkr" to "bajkar",
        "subha" to "subah", "subeh" to "subah", "sham" to "shaam", "raath" to "raat",
        "sade" to "saade", "saadhe" to "saade", "sadhe" to "saade", "savaa" to "sava", "sawa" to "sava",
        "pone" to "paune", "derh" to "dedh", "dhaai" to "dhai", "adhai" to "dhai", "adha" to "aadha",
        "aadhe" to "aadha", "aadhi" to "aadha",
        "rok" to "roko", "ruk" to "ruko", "rukjao" to "ruko",
        "agle" to "agla", "agli" to "agla", "pichle" to "pichla", "pichli" to "pichla",
        "hata" to "hatao", "hatado" to "hatao", "jala" to "jalao", "bujha" to "bujhao",
        "gana" to "gaana", "gaane" to "gaana", "gane" to "gaana",
        "bataao" to "batao", "bol" to "bolo", "bolna" to "bolo",
        "angrezi" to "english", "angreji" to "english",
        "tarikh" to "tareekh", "tareek" to "tareekh",
        "captions" to "caption", "subtitles" to "caption",
        "whats" to "what", "o'clock" to "oclock", "percentage" to "percent",
        "torchlight" to "torch", "flashlight's" to "flashlight",
    )

    private val DEVANAGARI_DIGITS = "०१२३४५६७८९"
    private val DEVANAGARI_RANGE = 'ऀ'..'ॿ'

    fun containsDevanagari(s: String): Boolean = s.any { it in DEVANAGARI_RANGE }

    fun normalize(raw: String): List<String> {
        var s = raw.lowercase()
        s = buildString { for (ch in s) { val d = DEVANAGARI_DIGITS.indexOf(ch); append(if (d >= 0) ('0' + d) else ch) } }
        s = s.replace(Regex("(\\d+)\\s*%"), "$1 percent")
        s = s.replace(Regex("\\b(\\d{1,2})[.:](\\d{2})\\b"), "$1:$2")
        s = s.replace(Regex("\\ba\\.?\\s?m\\.?(?=\\s|$)"), "am").replace(Regex("\\bp\\.?\\s?m\\.?(?=\\s|$)"), "pm")
        s = s.replace("o'clock", "oclock").replace("o clock", "oclock")
        s = s.replace("'", "")
        s = s.replace(Regex("[^\\p{L}\\p{M}\\p{N}:\\s]"), " ")
        val out = ArrayList<String>()
        for (word in s.split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            val num = DEVANAGARI_NUMBERS[word]
            val mapped = when {
                num != null -> num.toString()
                word == "दो" -> "do"
                DEVANAGARI.containsKey(word) -> DEVANAGARI.getValue(word)
                else -> ROMAN_VARIANTS[word] ?: word
            }
            out.addAll(mapped.split(' '))
        }
        return out
    }
}
