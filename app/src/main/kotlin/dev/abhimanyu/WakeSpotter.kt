package dev.abhimanyu

import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Listens for "Abhimanyu" using a small offline Vosk model restricted to just the wake phrase.
 *
 * To save battery it only runs the recognizer while there is sound: a cheap loudness gate opens
 * on speech (with a short pre-roll so the start of the word isn't lost) and closes after quiet.
 * It is deliberately quick to fire; Gemma then checks whether a real request followed, so a
 * false wake ends silently.
 *
 * The best long-term ear is a dedicated "Hey Abhimanyu" model trained with openWakeWord; this
 * class is the stand-in until that exists.
 */
class WakeSpotter(private val models: ModelStore) {

    /** Why the spotter can or can't run, in words for the setup screen. */
    val status: String
    val ready: Boolean

    private val recognizer: Recognizer?
    private val target: String

    private val preRoll = ArrayDeque<ShortArray>()
    private var gateOpen = false
    private var quietMs = 0
    private var noiseDb = -60.0
    private var lastDetect = 0L

    init {
        // Prefer the Hindi model: "अभिमन्यु" is a common name there. Fall back to English.
        val choices = listOf(
            Triple(ModelStore.Kind.HINDI, "अभिमन्यु", "हे"),
            Triple(ModelStore.Kind.ENGLISH, "abhimanyu", "hey"),
        )
        var rec: Recognizer? = null
        var word = ""
        var why = "Import a backup speech model (Hindi preferred) to enable the wake word."
        for ((kind, name, hey) in choices) {
            val model = models.model(kind) ?: continue
            if (!models.hasWord(kind, name)) {
                why = "The ${kind.label} speech model doesn't know the word \"$name\"."
                continue
            }
            val phrases = buildList {
                if (models.hasWord(kind, hey)) add("$hey $name")
                add(name)
                add("[unk]")
            }
            rec = runCatching {
                Recognizer(model, SpeechCapture.SAMPLE_RATE.toFloat(), phrases.joinToString(",", "[", "]") { "\"$it\"" })
            }.getOrNull()
            if (rec != null) {
                word = name
                why = "Listening for \"Abhimanyu\" with the ${kind.label} model."
                break
            }
        }
        recognizer = rec
        target = word
        ready = rec != null
        status = why
    }

    /** Feeds one 100 ms frame. Returns true when the wake word was just heard. */
    fun feed(frame: ShortArray, n: Int): Boolean {
        val rec = recognizer ?: return false
        val db = dbfs(frame, n)
        val threshold = max(noiseDb + 9.0, -50.0)
        if (db > threshold) {
            quietMs = 0
            if (!gateOpen) {
                gateOpen = true
                for (f in preRoll) rec.acceptWaveForm(f, f.size)
                preRoll.clear()
            }
        } else {
            // Track the room's background level while it's quiet.
            noiseDb = if (noiseDb < -59.0) db else noiseDb * 0.97 + db * 0.03
            quietMs += n * 1000 / SpeechCapture.SAMPLE_RATE
        }

        if (!gateOpen) {
            preRoll.addLast(frame.copyOf(n))
            while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
            return false
        }

        val final = rec.acceptWaveForm(frame, n)
        val text = if (final) JSONObject(rec.result).optString("text") else JSONObject(rec.partialResult).optString("partial")
        if (target.isNotEmpty() && text.contains(target)) {
            rec.reset()
            gateOpen = false
            val now = System.currentTimeMillis()
            if (now - lastDetect < 1500) return false // one wake per utterance
            lastDetect = now
            return true
        }
        if (quietMs > GATE_CLOSE_MS) {
            gateOpen = false
            rec.reset()
        }
        return false
    }

    fun reset() {
        recognizer?.reset()
        gateOpen = false
        preRoll.clear()
    }

    fun close() = recognizer?.close()

    private fun dbfs(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        return 20 * log10(max(sqrt(sum / max(n, 1)) / 32768.0, 1e-6))
    }

    companion object {
        private const val PRE_ROLL_FRAMES = 6   // 600 ms
        private const val GATE_CLOSE_MS = 1200
    }
}
