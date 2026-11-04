package dev.abhimanyu

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import dev.abhimanyu.core.Command
import dev.abhimanyu.core.Parsed
import dev.abhimanyu.core.Lang
import dev.abhimanyu.core.Reply
import dev.abhimanyu.core.ReplyLanguagePref
import dev.abhimanyu.core.ReplyRenderer
import dev.abhimanyu.core.Transcript
import dev.abhimanyu.core.Understanding
import java.util.concurrent.Executors

/**
 * Tap the mic and speak a Tier 0 command in English, Hindi or Hinglish. Gemma 4 E2B hears the
 * audio and picks a command (Vosk + rules are the optional backup); it runs through direct
 * Android APIs, with a spoken reply in your language, tones, and the edge glow.
 */
class MainActivity : Activity() {

    private enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    private lateinit var prefs: Prefs
    private lateinit var models: ModelStore
    private lateinit var gemma: GemmaEngine
    private lateinit var capture: SpeechCapture
    private lateinit var speaker: Speaker
    private lateinit var tones: Tones
    private lateinit var executor: ActionExecutor

    private lateinit var glow: GlowView
    private lateinit var status: TextView
    private lateinit var caption: TextView
    private lateinit var setupList: LinearLayout

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var lastCommand: Command? = null
    private var modelsLoading = false
    private var pendingImport: ModelStore.Kind? = null
    private var importing = false
    private val hideCaption = Runnable { caption.visibility = View.INVISIBLE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        models = ModelStore(this)
        gemma = GemmaEngine(this)
        capture = SpeechCapture(models)
        speaker = Speaker(this)
        tones = Tones(this)
        executor = ActionExecutor(this, prefs)

        glow = findViewById(R.id.glow)
        status = findViewById(R.id.status)
        caption = findViewById(R.id.caption)
        setupList = findViewById(R.id.setupList)

        val content = findViewById<View>(R.id.content)
        val pad = (20 * resources.displayMetrics.density).toInt()
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            content.setPadding(pad + bars.left, bars.top, pad + bars.right, bars.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.mic).setOnClickListener { onMicTapped() }
        loadModels()
    }

    override fun onResume() {
        super.onResume()
        // Speech voices load asynchronously; refresh once more shortly after.
        renderSetup()
        main.postDelayed({ renderSetup() }, 1500)
    }

    override fun onDestroy() {
        capture.requestStop()
        speaker.shutdown()
        worker.shutdownNow()
        Thread { gemma.close() }.start() // may wait for an in-flight command; keep it off the main thread
        super.onDestroy()
    }

    private fun loadModels() {
        if (modelsLoading) return
        modelsLoading = true
        renderSetup()
        worker.execute {
            gemma.load()                // main brain
            main.post { renderSetup() }
            models.loadAll()            // optional Vosk backup
            main.post { modelsLoading = false; renderSetup() }
        }
    }

    private fun gemmaReady() = gemma.status == GemmaEngine.Status.READY
    private fun voskReady() = ModelStore.Kind.entries.any { models.model(it) != null }

    // ---------------- the voice loop ----------------

    private fun onMicTapped() {
        when (state) {
            State.LISTENING -> { capture.requestStop(); return }
            State.THINKING -> return
            State.SPEAKING -> { speaker.stop(); finishTurn(null); return }
            State.IDLE -> Unit
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (importing) { status.text = "Wait for the import to finish…"; return }
        if (!gemmaReady() && !voskReady()) {
            val anyInstalled = gemma.isInstalled() || ModelStore.Kind.entries.any { models.isInstalled(it) }
            if (!anyInstalled) { respond(Reply.ModelsMissing, Lang.EN, ok = false, heard = null); return }
            status.text = if (gemma.status == GemmaEngine.Status.FAILED) gemma.detail else "Gemma is still loading…"
            loadModels()
            return
        }
        startListening()
    }

    private fun startListening() {
        state = State.LISTENING
        status.setText(R.string.listening)
        main.removeCallbacks(hideCaption)
        caption.visibility = View.INVISIBLE
        tones.listening()
        glow.setMode(GlowView.Mode.LISTENING)
        capture.restrictToCommands = prefs.restrictVocabulary
        worker.execute {
            Thread.sleep(150) // let the listening tone finish so it isn't recorded
            val useGemma = gemmaReady()
            val clip = try {
                capture.capture(useVosk = voskReady()) { level -> glow.setLevel(level) }
            } catch (e: Exception) {
                SpeechCapture.Result(ShortArray(0), emptyList())
            }
            main.post { onHeard(clip, useGemma) }
        }
    }

    private fun onHeard(clip: SpeechCapture.Result, useGemma: Boolean) {
        if (state != State.LISTENING) return
        state = State.THINKING
        status.setText(R.string.thinking)
        tones.heard()
        glow.setMode(GlowView.Mode.THINKING)

        worker.execute {
            // Main brain: Gemma hears the clip itself. Backup: Vosk transcripts + rules.
            val decision = (if (useGemma && clip.pcm.size > SpeechCapture.SAMPLE_RATE / 4) {
                runCatching { gemma.understand(GemmaEngine.wav(clip.pcm)) }.getOrNull()
                    ?.let { Understanding.fromGemma(it) }
            } else null) ?: fromBackup(clip.transcripts)
            main.post { onDecision(decision) }
            gemma.prepareNext()
        }
    }

    private fun fromBackup(transcripts: List<Transcript>): Understanding.Decision {
        val r = Understanding.choose(transcripts)
        return r.parsed?.let { Understanding.Decision.Run(it) }
            ?: Understanding.Decision.NotHeard(r.best?.text.orEmpty(), Understanding.guessLang(r.best))
    }

    private fun onDecision(decision: Understanding.Decision) {
        val parsed: Parsed = when (decision) {
            is Understanding.Decision.Run -> decision.parsed
            is Understanding.Decision.OutOfScope -> {
                respond(Reply.CantDoYet, decision.lang, ok = false, heard = decision.transcript)
                return
            }
            is Understanding.Decision.NotHeard -> {
                respond(Reply.NotUnderstood, decision.lang, ok = false, heard = decision.transcript)
                return
            }
        }

        var command = parsed.command
        if (command == Command.Repeat) {
            command = lastCommand ?: run {
                respond(Reply.NothingToRepeat, parsed.lang, ok = false, heard = parsed.transcript)
                return
            }
        }
        val toRun = command
        worker.execute {
            val outcome = executor.execute(toRun)
            main.post {
                if (outcome.ok && toRun !is Command.Stop) lastCommand = toRun
                respond(outcome.reply, parsed.lang, outcome.ok, parsed.transcript, outcome.openSettings)
            }
        }
    }

    private fun respond(reply: Reply, detected: Lang, ok: Boolean, heard: String?, openSettings: Intent? = null) {
        val lang = ReplyRenderer.resolveLang(detected, prefs.replyLanguage)
        val u = ReplyRenderer.render(reply, lang)
        state = State.SPEAKING
        status.text = u.caption
        if (prefs.captions) {
            caption.text = if (heard.isNullOrBlank()) u.caption else "“$heard”  →  ${u.caption}"
            caption.visibility = View.VISIBLE
        }
        if (ok) { tones.done(); glow.setMode(GlowView.Mode.DONE) } else { tones.failed(); glow.setMode(GlowView.Mode.FAILED) }
        // Speak just after the tone so they don't overlap.
        main.postDelayed({
            speaker.speak(u) { main.post { finishTurn(openSettings) } }
        }, 260)
    }

    private fun finishTurn(openSettings: Intent?) {
        if (state == State.IDLE) return
        state = State.IDLE
        status.setText(R.string.tap_to_speak)
        main.removeCallbacks(hideCaption)
        main.postDelayed(hideCaption, 4000)
        openSettings?.let { runCatching { startActivity(it) } }
        renderSetup()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderSetup()
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) onMicTapped()
    }

    // ---------------- setup checklist ----------------

    private fun renderSetup() {
        if (!::setupList.isInitialized) return
        setupList.removeAllViews()
        header("Setup")

        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        row("Microphone", if (mic) "Allowed" else "Needed to hear you", mic, "Allow") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        val gemmaDetail = when (gemma.status) {
            GemmaEngine.Status.NOT_INSTALLED ->
                "Download ${GemmaEngine.MODEL_FILE_NAME} (2.6 GB) in Chrome from Hugging Face " +
                    "(litert-community/gemma-4-E2B-it-litert-lm), then import it. " +
                    "Edge Gallery's copy is private to Edge Gallery, so it can't be reused."
            GemmaEngine.Status.LOADING -> gemma.detail.ifBlank { "Loading…" }
            GemmaEngine.Status.READY -> gemma.detail
            GemmaEngine.Status.FAILED -> gemma.detail
        }
        row(
            "Gemma 4 E2B (main brain)", gemmaDetail, gemmaReady(),
            if (gemma.isInstalled()) "Replace" else "Import",
        ) { pickGemma() }
        val en = speaker.hasEnglishVoice()
        val hi = speaker.hasHindiVoice()
        row(
            "Voices for replies",
            "English (India): ${if (en) "ready" else "missing"} · Hindi: ${if (hi) "ready" else "missing"}",
            en && hi, "Open",
        ) { openTtsSettings() }
        val dnd = getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        row("Do Not Disturb access", if (dnd) "Allowed" else "For silent mode and DND", dnd, "Allow") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        val write = Settings.System.canWrite(this)
        row("Modify system settings", if (write) "Allowed" else "For brightness", write, "Allow") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }

        header("Backup speech (optional)")
        hint("Only used if Gemma isn't loaded. Small Vosk zips, about 40 MB each.")
        for (kind in ModelStore.Kind.entries) {
            val installed = models.isInstalled(kind)
            val detail = when {
                !installed -> "Not installed"
                models.model(kind) == null -> if (modelsLoading) "Loading…" else "Installed, not loaded"
                else -> "Installed"
            }
            row("${kind.label} (Vosk)", detail, installed && models.model(kind) != null, if (installed) "Replace" else "Import") {
                pickModel(kind)
            }
        }

        header("Preferences")
        val langLabel = when (prefs.replyLanguage) {
            ReplyLanguagePref.MATCH -> "Match the language I speak"
            ReplyLanguagePref.ENGLISH -> "Always English"
            ReplyLanguagePref.HINDI -> "Always Hindi"
        }
        row("Reply language", langLabel, null, "Change") {
            prefs.replyLanguage = ReplyLanguagePref.entries[(prefs.replyLanguage.ordinal + 1) % ReplyLanguagePref.entries.size]
            renderSetup()
        }
        toggle("Captions", "Show what I heard and replied", prefs.captions) { prefs.captions = it }
        toggle("Backup: command words only", "Limits Vosk to known commands; more accurate", prefs.restrictVocabulary) {
            prefs.restrictVocabulary = it
        }

        header("Try saying")
        hint(
            "“Flashlight on” · “Torch band karo” · “दस मिनट का टाइमर लगाओ”\n" +
                "“Kal subah saade chhe baje ka alarm” · “Volume 50 percent”\n" +
                "“DND on for one hour” · “Kitne baje hain?” · “Next song” · “Phir se”",
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(text: String) {
        setupList.addView(TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_dim))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = true
            setPadding(0, if (setupList.childCount == 0) 0 else dp(18), 0, dp(6))
        })
    }

    private fun hint(text: String) {
        setupList.addView(TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text))
            textSize = 14f
            setLineSpacing(0f, 1.3f)
        })
    }

    private fun textBlock(title: String, detail: String, done: Boolean?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = when (done) { true -> "✓  $title"; false -> "•  $title"; null -> title }
                setTextColor(getColor(if (done == true) R.color.ok else R.color.text))
                textSize = 16f
            })
            addView(TextView(context).apply {
                text = detail
                setTextColor(getColor(R.color.text_dim))
                textSize = 13f
            })
        }

    private fun row(title: String, detail: String, done: Boolean?, action: String, onClick: () -> Unit) {
        setupList.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(textBlock(title, detail, done))
            if (done != true || action == "Replace" || action == "Change" || action == "Open") {
                addView(Button(context).apply {
                    text = action
                    isAllCaps = false
                    setOnClickListener { onClick() }
                })
            }
        })
    }

    private fun toggle(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
        setupList.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(textBlock(title, detail, null))
            addView(Switch(context).apply {
                isChecked = value
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            })
        })
    }

    private fun openTtsSettings() {
        val intent = Intent("com.android.settings.TTS_SETTINGS")
        if (runCatching { startActivity(intent) }.isFailure) startActivity(Intent(Settings.ACTION_SETTINGS))
    }

    // ---------------- model import ----------------

    private fun pickGemma() {
        if (importing) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_IMPORT_GEMMA)
    }

    private fun importGemma(uri: Uri) {
        importing = true
        status.text = "Importing Gemma 4… 0%"
        renderSetup()
        worker.execute {
            val result = gemma.import(uri) { pct -> main.post { status.text = "Importing Gemma 4… $pct%" } }
            main.post {
                importing = false
                status.setText(R.string.tap_to_speak)
                renderSetup()
                AlertDialog.Builder(this)
                    .setTitle(if (result.ok) "Gemma 4 installed" else "Gemma 4 not installed")
                    .setMessage(
                        result.message + if (result.ok) "\n\nYou can now delete the downloaded file to free 2.6 GB." else "",
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun pickModel(kind: ModelStore.Kind) {
        pendingImport = kind
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_IMPORT_GEMMA) {
            if (resultCode == RESULT_OK && uri != null) importGemma(uri)
            return
        }
        val kind = pendingImport
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || kind == null || uri == null) return
        pendingImport = null
        status.text = "Installing ${kind.label} model…"
        modelsLoading = true
        renderSetup()
        worker.execute {
            val result = models.import(kind, uri)
            main.post {
                modelsLoading = false
                status.setText(R.string.tap_to_speak)
                renderSetup()
                AlertDialog.Builder(this)
                    .setTitle(result.message)
                    .setMessage(
                        if (result.sha256 != null) "SHA-256 of the file:\n\n${result.sha256}\n\nCompare it with the checksum on the download page."
                        else "Nothing was installed.",
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    companion object {
        private const val REQ_MIC = 1
        private const val REQ_IMPORT = 2
        private const val REQ_IMPORT_GEMMA = 3
    }
}
