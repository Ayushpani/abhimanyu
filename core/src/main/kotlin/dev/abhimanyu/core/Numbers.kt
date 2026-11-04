package dev.abhimanyu.core

/**
 * Reads spoken numbers from normalized tokens: digits, English words ("twenty five"),
 * Hindi words ("pachchis", "दस" after normalization) and Indian fractions
 * ("saade chhe" = 6.5, "sava" +¼, "paune" -¼, "dedh" = 1.5, "dhai" = 2.5, "aadha" = ½).
 */
object Numbers {

    val ENGLISH_UNITS: Map<String, Int> = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
    )
    val ENGLISH_TENS: Map<String, Int> = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90, "hundred" to 100,
    )
    val HINDI: Map<String, Int> = mapOf(
        "ek" to 1, "teen" to 3, "tin" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5,
        "chhe" to 6, "che" to 6, "chhah" to 6, "chah" to 6, "chheh" to 6, "chhai" to 6,
        "saat" to 7, "sat" to 7, "aath" to 8, "ath" to 8, "nau" to 9, "das" to 10, "dus" to 10,
        "gyarah" to 11, "gyara" to 11, "barah" to 12, "bara" to 12, "terah" to 13, "tera" to 13,
        "chaudah" to 14, "chauda" to 14, "pandrah" to 15, "pandra" to 15, "solah" to 16, "sola" to 16,
        "satrah" to 17, "satra" to 17, "atharah" to 18, "athara" to 18, "unnis" to 19, "unees" to 19,
        "bees" to 20, "bis" to 20, "ikkis" to 21, "baais" to 22, "teis" to 23, "chaubis" to 24,
        "pachchis" to 25, "pachis" to 25, "tees" to 30, "tis" to 30, "paintis" to 35, "pentees" to 35,
        "chalis" to 40, "chalees" to 40, "paintalis" to 45, "pentalis" to 45, "pachaas" to 50,
        "pachas" to 50, "pachpan" to 55, "saath" to 60, "sattar" to 70, "assi" to 80, "nabbe" to 90,
        "sau" to 100,
    )

    /** Words that are numbers only when followed by a unit ("do minute", "an hour"). */
    private val AMBIGUOUS: Map<String, Double> = mapOf("do" to 2.0, "a" to 1.0, "an" to 1.0)

    private val FRACTION_WORDS: Map<String, Double> = mapOf(
        "dedh" to 1.5, "dhai" to 2.5, "aadha" to 0.5, "half" to 0.5,
    )
    private val MODIFIERS: Map<String, Double> = mapOf("saade" to 0.5, "sava" to 0.25, "paune" to -0.25)

    private val DIGITS = Regex("\\d+(\\.\\d+)?")

    /** A number found at a token index, and how many tokens it used. */
    data class Found(val value: Double, val length: Int)

    /**
     * Reads the number starting at [i]. Ambiguous words ("do", "a", "an") count only if the
     * next token is in [unitWords].
     */
    fun at(t: List<String>, i: Int, unitWords: Set<String> = emptySet()): Found? {
        val w = t.getOrNull(i) ?: return null
        MODIFIERS[w]?.let { delta ->
            val base = plain(t, i + 1, unitWords) ?: return null
            return Found(base.value + delta, base.length + 1)
        }
        FRACTION_WORDS[w]?.let { return Found(it, 1) }
        val base = plain(t, i, unitWords) ?: return null
        // "one and a half", "two and half"
        if (t.getOrNull(i + base.length) == "and") {
            val j = i + base.length + 1
            if (t.getOrNull(j) == "half") return Found(base.value + 0.5, base.length + 2)
            if (t.getOrNull(j) == "a" && t.getOrNull(j + 1) == "half") return Found(base.value + 0.5, base.length + 3)
        }
        return base
    }

    private fun plain(t: List<String>, i: Int, unitWords: Set<String>): Found? {
        val w = t.getOrNull(i) ?: return null
        if (DIGITS.matches(w)) return Found(w.toDouble(), 1)
        HINDI[w]?.let { return Found(it.toDouble(), 1) }
        AMBIGUOUS[w]?.let { v -> if (t.getOrNull(i + 1) in unitWords) return Found(v, 1) }
        ENGLISH_UNITS[w]?.let { return Found(it.toDouble(), 1) }
        ENGLISH_TENS[w]?.let { tens ->
            val unit = ENGLISH_UNITS[t.getOrNull(i + 1)]
            return if (tens < 100 && unit != null && unit in 1..9) Found((tens + unit).toDouble(), 2)
            else Found(tens.toDouble(), 1)
        }
        return null
    }

    /** The first number anywhere in [t], ignoring ambiguous words. */
    fun first(t: List<String>): Double? {
        for (i in t.indices) at(t, i)?.let { return it.value }
        return null
    }

    /** Every word this object understands, for building a recognizer vocabulary. */
    val allWords: Set<String>
        get() = ENGLISH_UNITS.keys + ENGLISH_TENS.keys + HINDI.keys + AMBIGUOUS.keys +
            FRACTION_WORDS.keys + MODIFIERS.keys + setOf("and")
}
