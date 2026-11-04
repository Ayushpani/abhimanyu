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
    /** Phrases (spaces removed) that count as the wake word for the chosen model. */
    private val targets: List<String>

    /** What the ear last heard, for the setup screen's live check. */
    @Volatile var lastHeard: String = ""
        private set

    private val preRoll = ArrayDeque<ShortArray>()
    private var gateOpen = false
    private var quietMs = 0
    private var noiseDb = -60.0
    private var lastDetect = 0L

    init {
        // Ways the name can appear in each model's vocabulary, best first. Small models often
        // lack the full name, so split spellings that sound the same are tried too.
        val choices = listOf(
            ModelStore.Kind.HINDI to listOf("अभिमन्यु", "अभिमन्यू", "अभि मन्यु", "अभी मन्यु", "अभि मन्यू", "अभी मनु"),
            ModelStore.Kind.ENGLISH to listOf("abhimanyu", "abhi manyu", "abhi man you", "abby man you", "abi man you"),
        )
        var rec: Recognizer? = null
        var chosen = emptyList<String>()
        var why = "Import the small Hindi (or Indian English) Vosk model to enable the wake word."
        val missing = mutableListOf<String>()
        for ((kind, spellings) in choices) {
            val model = models.model(kind) ?: continue
            val known = spellings.filter { p -> p.split(' ').all { models.hasWord(kind, it) } }
            if (known.isEmpty()) { missing += kind.label; continue }
            val hey = if (kind == ModelStore.Kind.HINDI) "हे" else "hey"
            val phrases = buildList {
                if (models.hasWord(kind, hey)) known.forEach { add("$hey $it") }
                addAll(known)
                add("[unk]")
            }
            rec = runCatching {
                Recognizer(model, SpeechCapture.SAMPLE_RATE.toFloat(), phrases.joinToString(",", "[", "]") { "\"$it\"" })
            }.getOrNull()
            if (rec != null) {
                chosen = known.map { it.replace(" ", "") }
                why = "Listening for \"${known.first()}\" with the ${kind.label} model."
                break
            }
        }
        if (rec == null && missing.isNotEmpty()) {
            why = "The ${missing.joinToString(" and ")} model doesn't know any spelling of \"Abhimanyu\". Try importing the other one."
        }
        recognizer = rec
        targets = chosen
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
        if (text.isNotBlank()) lastHeard = text
        val joined = text.replace(" ", "")
        if (targets.any { joined.contains(it) }) {
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
