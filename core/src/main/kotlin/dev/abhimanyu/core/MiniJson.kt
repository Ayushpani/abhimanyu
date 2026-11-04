package dev.abhimanyu.core

/**
 * A tiny, strict JSON reader for the model's constrained output. Returns maps, lists, strings,
 * numbers (Double), booleans and null. Kept dependency-free so the core module stays plain Kotlin.
 */
object MiniJson {

    class ParseException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw ParseException("Trailing characters at ${p.i}")
        return v
    }

    /** Parses and returns the top-level object, or null if the text isn't a JSON object. */
    fun parseObject(text: String): Map<String, Any?>? {
        // Models sometimes wrap JSON in ``` fences; take the outermost {...}.
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        @Suppress("UNCHECKED_CAST")
        return runCatching { parse(text.substring(start, end + 1)) as? Map<String, Any?> }.getOrNull()
    }

    private class Parser(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun fail(msg: String): Nothing = throw ParseException("$msg at $i")

        fun value(): Any? {
            skipWs()
            if (atEnd()) fail("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("Unexpected '$c'")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Expected $word")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                if (atEnd() || s[i] != '"') fail("Expected key")
                val k = str()
                skipWs()
                if (atEnd() || s[i] != ':') fail("Expected ':'")
                i++
                out[k] = value()
                skipWs()
                if (atEnd()) fail("Unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out += value()
                skipWs()
                if (atEnd()) fail("Unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++ // opening quote
            while (true) {
                if (atEnd()) fail("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("Bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("Bad escape '$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(start, i).toDoubleOrNull() ?: fail("Bad number")
        }
    }
}
