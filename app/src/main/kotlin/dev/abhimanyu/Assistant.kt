package dev.abhimanyu

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.abhimanyu.core.Command
import dev.abhimanyu.core.Lang
import dev.abhimanyu.core.Parsed
import dev.abhimanyu.core.Reply
import dev.abhimanyu.core.ReplyRenderer
import dev.abhimanyu.core.Transcript
import dev.abhimanyu.core.Understanding
import dev.abhimanyu.core.Vocabulary
import org.json.JSONObject
import org.vosk.Recognizer
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * The assistant's brain, shared by the app screen, the always-on listening service and control
 * mode, so it works the same whether the app is open or closed. It owns Gemma, the backup
 * recognizers, the actions, the voice and the tones, and tells listeners what phase it is in.
 */
object Assistant {

    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

    interface Listener {
        fun onPhase(phase: Phase) {}
        fun onLevel(level: Float) {}
        /** [heard] is your words, [caption] the reply, [timing] the speed breakdown (or null). */
        fun onResult(heard: String?, caption: String, ok: Boolean, timing: String?) {}
        fun onModelsChanged() {}
        /** A permission is missing; the screen (if open) shows its settings page. */
        fun onOpenSettings(intent: Intent) {}
    }

    lateinit var app: Context
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var models: ModelStore
        private set
    lateinit var gemma: GemmaEngine
        private set
    lateinit var speaker: Speaker
        private set
    lateinit var tones: Tones
        private set
    private lateinit var executor: ActionExecutor

    private val brain = Executors.newSingleThreadExecutor()
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile var phase = Phase.IDLE
        private set
    @Volatile var activityVisible = false
    @Volatile var modelsLoading = false
        private set
    /** Why the last command didn't go through Gemma normally (shown in setup), or null. */
    @Volatile var lastDiagnosis: String? = null
        private set
    private var lastCommand: Command? = null
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        prefs = Prefs(app)
        models = ModelStore(app)
        gemma = GemmaEngine(app)
        speaker = Speaker(app)
        tones = Tones(app)
        executor = ActionExecutor(app, prefs)
        loadModels()
    }

    fun loadModels() {
        if (modelsLoading) return
        modelsLoading = true
        notifyModels()
        loader.execute {
            gemma.load()
            notifyModels()
            models.loadAll()
            modelsLoading = false
            notifyModels()
        }
    }

    /** Runs long jobs (imports) off the main thread without blocking commands. */
    fun background(job: () -> Unit) = loader.execute(job)

    fun notifyModels() = main.post { listeners.forEach { it.onModelsChanged() } }

    fun gemmaReady() = gemma.status == GemmaEngine.Status.READY
    fun voskReady() = ModelStore.Kind.entries.any { models.model(it) != null }
    fun canUnderstand() = gemmaReady() || voskReady()

    private fun setPhase(p: Phase) {
        phase = p
        main.post { listeners.forEach { it.onPhase(p) } }
    }

    // ---------------- one turn ----------------

    /** Called when listening starts (wake word or mic button). */
    fun beginListening() {
        tones.listening()
        setPhase(Phase.LISTENING)
    }

    fun level(l: Float) = main.post { listeners.forEach { it.onLevel(l) } }

    /** Back to idle without doing anything (e.g. nothing was said after a wake word). */
    fun cancelListening() = setPhase(Phase.IDLE)

    fun stopSpeaking() {
        speaker.stop()
        setPhase(Phase.IDLE)
    }

    /**
     * Understands and acts on one recorded clip. [fromWake] is true when the wake word started
     * it: then silence or noise ends quietly instead of saying "say that again", because it was
     * probably a false wake.
     */
    fun process(pcm: ShortArray, transcripts: List<Transcript>, fromWake: Boolean) {
        setPhase(Phase.THINKING)
        tones.heard()
        val heardAt = SystemClock.elapsedRealtime()
        brain.execute {
            var listenMs: Long? = null
            var writeMs: Long? = null
            val useGemma = gemmaReady() && pcm.size > SpeechCapture.SAMPLE_RATE / 4
            // Why Gemma didn't give the answer, shown under the mic so problems can be diagnosed.
            var diagnosis: String? = when {
                !gemmaReady() -> "Gemma not loaded (${gemma.detail}); used backup"
                !useGemma -> "clip too short (${pcm.size * 1000 / SpeechCapture.SAMPLE_RATE} ms)"
                else -> null
            }
            val fromGemma = if (useGemma) {
                val answer = runCatching { gemma.understand(GemmaEngine.wav(pcm)) }
                answer.exceptionOrNull()?.let { diagnosis = "Gemma failed: ${gemma.lastError ?: it.message}; used backup" }
                answer.getOrNull()?.let { a ->
                    listenMs = a.listenMs
                    writeMs = a.writeMs
                    Understanding.fromGemma(a.text).also {
                        if (it == null) diagnosis = "Gemma replied: " + a.text.replace('\n', ' ').take(140)
                        else if (it !is Understanding.Decision.Run) diagnosis = "Gemma heard: " + a.text.replace('\n', ' ').take(140)
                    }
                }
            } else null
            lastDiagnosis = diagnosis
            val decision = fromGemma ?: fromBackup(if (transcripts.isEmpty()) transcribe(pcm) else transcripts)
            val understoodAt = SystemClock.elapsedRealtime()

            val parsed: Parsed = when (decision) {
                is Understanding.Decision.Run -> decision.parsed
                is Understanding.Decision.OutOfScope -> {
                    finish(Reply.CantDoYet, decision.lang, false, decision.transcript, diagnosis, null)
                    gemma.prepareNext()
                    return@execute
                }
                is Understanding.Decision.NotHeard -> {
                    if (fromWake && decision.transcript.isBlank()) {
                        tones.failed()
                        main.post { listeners.forEach { it.onResult(null, "", false, null) } }
                        setPhase(Phase.IDLE)
                    } else {
                        finish(Reply.NotUnderstood, decision.lang, false, decision.transcript, diagnosis, null)
                    }
                    gemma.prepareNext()
                    return@execute
                }
            }

            var command = parsed.command
            if (command == Command.Repeat) {
                command = lastCommand ?: run {
                    finish(Reply.NothingToRepeat, parsed.lang, false, parsed.transcript, null, null)
                    gemma.prepareNext()
                    return@execute
                }
            }
            val actStart = SystemClock.elapsedRealtime()
            val outcome = executor.execute(command)
            val actMs = SystemClock.elapsedRealtime() - actStart
            if (outcome.ok && command !is Command.Stop) lastCommand = command

            val timing = if (diagnosis != null) diagnosis else if (!prefs.showTimings) null else {
                fun s(ms: Long?) = if (ms == null) "–" else "%.1f s".format(ms / 1000f)
                val clip = pcm.size * 1000L / SpeechCapture.SAMPLE_RATE
                val total = SystemClock.elapsedRealtime() - heardAt
                if (fromGemma != null) "you spoke ${s(clip)} · Gemma hears ${s(listenMs)} · writes ${s(writeMs)} · acts ${s(actMs)} · total ${s(total)}"
                else "backup · understood ${s(understoodAt - heardAt)} · acts ${s(actMs)} · total ${s(total)}"
            }
            finish(outcome.reply, parsed.lang, outcome.ok, parsed.transcript, timing, outcome.openSettings)
            gemma.prepareNext()
        }
    }

    private fun finish(reply: Reply, detected: Lang, ok: Boolean, heard: String?, timing: String?, openSettings: Intent?) {
        val lang = ReplyRenderer.resolveLang(detected, prefs.replyLanguage)
        val u = ReplyRenderer.render(reply, lang)
        setPhase(Phase.SPEAKING)
        if (ok) tones.done() else tones.failed()
        main.post {
            listeners.forEach { it.onResult(heard, u.caption, ok, timing) }
            if (openSettings != null && activityVisible) listeners.forEach { it.onOpenSettings(openSettings) }
        }
        // Speak just after the short tone so they don't overlap.
        main.postDelayed({ speaker.speak(u) { setPhase(Phase.IDLE) } }, 120)
    }

    // ---------------- backup recognizers ----------------

    private fun fromBackup(transcripts: List<Transcript>): Understanding.Decision {
        val r = Understanding.choose(transcripts)
        return r.parsed?.let { Understanding.Decision.Run(it) }
            ?: Understanding.Decision.NotHeard(r.best?.text.orEmpty(), Understanding.guessLang(r.best))
    }

    /** Runs the Vosk backup over an already-recorded clip (used when the wake service recorded it). */
    private fun transcribe(pcm: ShortArray): List<Transcript> {
        val out = mutableListOf<Transcript>()
        for (kind in ModelStore.Kind.entries) {
            val model = models.model(kind) ?: continue
            val words = if (kind == ModelStore.Kind.ENGLISH) Vocabulary.english() else Vocabulary.hindi()
            runCatching {
                val rec = if (prefs.restrictVocabulary) Recognizer(model, SpeechCapture.SAMPLE_RATE.toFloat(), Vocabulary.grammarJson(words))
                else Recognizer(model, SpeechCapture.SAMPLE_RATE.toFloat())
                rec.setWords(true)
                val words2 = mutableListOf<String>()
                val confs = mutableListOf<Double>()
                fun collect(json: String) {
                    val arr = JSONObject(json).optJSONArray("result") ?: return
                    for (i in 0 until arr.length()) {
                        val w = arr.getJSONObject(i)
                        val word = w.optString("word")
                        if (word.isBlank() || word == "[unk]") continue
                        words2 += word; confs += w.optDouble("conf", 0.5)
                    }
                }
                var i = 0
                while (i < pcm.size) {
                    val n = minOf(1600, pcm.size - i)
                    if (rec.acceptWaveForm(pcm.copyOfRange(i, i + n), n)) collect(rec.result)
                    i += n
                }
                collect(rec.finalResult)
                rec.close()
                out += Transcript(words2.joinToString(" "), if (confs.isEmpty()) 0f else confs.average().toFloat(), kind.dirName)
            }
        }
        return out
    }
}
