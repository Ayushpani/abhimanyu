package dev.abhimanyu

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.abhimanyu.core.Transcript
import dev.abhimanyu.core.Vocabulary
import org.json.JSONObject
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Push-to-talk capture. Records 16 kHz mono audio for Gemma, reports loudness for the glow, and
 * stops on its own when you stop speaking. If the optional Vosk backup models are installed, the
 * same frames also go to them, so there is a transcript even if Gemma is unloaded.
 *
 * Audio only ever lives in memory and is discarded after recognition.
 */
class SpeechCapture(private val models: ModelStore) {

    private val stopRequested = AtomicBoolean(false)
    @Volatile var restrictToCommands = true

    fun requestStop() = stopRequested.set(true)

    /** The recorded clip, plus backup transcripts if Vosk is installed. */
    class Result(val pcm: ShortArray, val transcripts: List<Transcript>)

    /** Blocks until done. Run on a background thread. */
    @SuppressLint("MissingPermission") // checked by the caller before starting
    fun capture(useVosk: Boolean, onLevel: (Float) -> Unit): Result {
        stopRequested.set(false)
        val recognizers = if (!useVosk) emptyList() else buildList {
            models.model(ModelStore.Kind.ENGLISH)?.let { add(Triple("en", it, Vocabulary.english())) }
            models.model(ModelStore.Kind.HINDI)?.let { add(Triple("hi", it, Vocabulary.hindi())) }
        }.map { (name, model, words) ->
            val rec = if (restrictToCommands) Recognizer(model, SAMPLE_RATE.toFloat(), Vocabulary.grammarJson(words))
            else Recognizer(model, SAMPLE_RATE.toFloat())
            rec.setWords(true)
            Pending(name, rec)
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, FRAME * 4 * 2),
        )
        val frame = ShortArray(FRAME)
        val pcm = ShortArray(SAMPLE_RATE * MAX_MS / 1000)
        var pcmLen = 0
        try {
            record.startRecording()
            var elapsedMs = 0
            var silentMs = 0
            var speechStarted = false
            var noiseDb = 0.0
            while (!stopRequested.get()) {
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) break
                elapsedMs += n * 1000 / SAMPLE_RATE
                val room = minOf(n, pcm.size - pcmLen)
                if (room > 0) { System.arraycopy(frame, 0, pcm, pcmLen, room); pcmLen += room }

                val db = dbfs(frame, n)
                onLevel(((db + 55.0) / 40.0).coerceIn(0.0, 1.0).toFloat())
                if (elapsedMs <= 300) noiseDb = min(noiseDb, db) // quietest early frame ≈ room noise
                val threshold = max(noiseDb + 8.0, -48.0)
                if (db > threshold) { speechStarted = true; silentMs = 0 } else silentMs += n * 1000 / SAMPLE_RATE

                for (p in recognizers) {
                    if (p.rec.acceptWaveForm(frame, n)) p.collect(p.rec.result)
                }

                if (speechStarted && silentMs >= END_SILENCE_MS) break
                if (!speechStarted && elapsedMs >= NO_SPEECH_MS) break
                if (elapsedMs >= MAX_MS) break
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            onLevel(0f)
        }
        val transcripts = recognizers.map { p ->
            p.collect(p.rec.finalResult)
            p.rec.close()
            Transcript(p.words.joinToString(" "), if (p.confs.isEmpty()) 0f else p.confs.average().toFloat(), p.name)
        }
        return Result(pcm.copyOf(pcmLen), transcripts)
    }

    private class Pending(val name: String, val rec: Recognizer) {
        val words = mutableListOf<String>()
        val confs = mutableListOf<Double>()
        fun collect(json: String) {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
            val arr = obj.optJSONArray("result")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val w = arr.getJSONObject(i)
                    val word = w.optString("word")
                    if (word.isBlank() || word == "[unk]") continue
                    words += word
                    confs += w.optDouble("conf", 0.5)
                }
            } else {
                obj.optString("text").split(' ').filter { it.isNotBlank() && it != "[unk]" }.forEach {
                    words += it; confs += 0.5
                }
            }
        }
    }

    private fun dbfs(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        val rms = sqrt(sum / max(n, 1)) / 32768.0
        return 20 * log10(max(rms, 1e-6))
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val FRAME = 1600            // 100 ms
        private const val END_SILENCE_MS = 900
        private const val NO_SPEECH_MS = 4000
        private const val MAX_MS = 8000
    }
}
