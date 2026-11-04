package dev.abhimanyu

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import dev.abhimanyu.core.Lang
import dev.abhimanyu.core.Reply
import dev.abhimanyu.core.ReplyLanguagePref
import dev.abhimanyu.core.ReplyRenderer

/**
 * The app screen: setup, preferences and a mic button. The brain itself lives in [Assistant] and
 * keeps working through [ListenService] after this screen is closed.
 */
class MainActivity : Activity(), Assistant.Listener {

    private lateinit var prefs: Prefs
    private lateinit var capture: SpeechCapture

    private lateinit var ink: InkView
    private lateinit var status: TextView
    private lateinit var caption: TextView
    private lateinit var footer: TextView
    private lateinit var setupList: LinearLayout

    private val main = Handler(Looper.getMainLooper())
    private var pendingImport: ModelStore.Kind? = null
    private var importing = false
    @Volatile private var ownCapture = false
    private val hideCaption = Runnable { caption.visibility = View.INVISIBLE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        setContentView(R.layout.activity_main)

        Assistant.init(this)
        prefs = Assistant.prefs
        capture = SpeechCapture(Assistant.models)

        ink = findViewById(R.id.ink)
        status = findViewById(R.id.status)
        caption = findViewById(R.id.caption)
        footer = findViewById(R.id.footer)
        setupList = findViewById(R.id.setupList)

        val content = findViewById<View>(R.id.content)
        val pad = (20 * resources.displayMetrics.density).toInt()
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            content.setPadding(pad + bars.left, bars.top, pad + bars.right, bars.bottom)
            insets
        }

        val mic = findViewById<ImageButton>(R.id.mic)
        mic.setOnClickListener { onMicTapped() }
        ink.anchorTo(mic)
        handleStartIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStartIntent(intent)
    }

    /** Opening the app (or the "tap to start" reminder) restarts always-on listening if it's on. */
    private fun handleStartIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_LISTENING, false) == true) prefs.alwaysListening = true
        if (prefs.alwaysListening && !ListenService.running) startListeningService()
    }

    override fun onResume() {
        super.onResume()
        Assistant.activityVisible = true
        Assistant.listeners += this
        renderSetup()
        // Speech voices load asynchronously; refresh once more shortly after.
        main.postDelayed({ renderSetup() }, 1500)
    }

    override fun onPause() {
        Assistant.activityVisible = false
        Assistant.listeners -= this
        super.onPause()
    }

    // ---------------- the voice loop ----------------

    private fun onMicTapped() {
        when (Assistant.phase) {
            Assistant.Phase.LISTENING -> {
                if (ownCapture) capture.requestStop() else ListenService.finishCapture()
                return
            }
            Assistant.Phase.THINKING -> return
            Assistant.Phase.SPEAKING -> { Assistant.stopSpeaking(); return }
            Assistant.Phase.IDLE -> Unit
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (importing) { status.text = "Wait for the import to finish…"; return }
        if (!Assistant.canUnderstand()) {
            val g = Assistant.gemma
            val anyInstalled = g.isInstalled() || ModelStore.Kind.entries.any { Assistant.models.isInstalled(it) }
            if (!anyInstalled) {
                val u = ReplyRenderer.render(Reply.ModelsMissing, Lang.EN)
                status.text = u.caption
                Assistant.speaker.speak(u)
                return
            }
            status.text = if (g.status == GemmaEngine.Status.FAILED) g.detail else "Gemma is still loading…"
            Assistant.loadModels()
            return
        }
        // The listening service already holds the microphone: ask it to listen now.
        if (ListenService.running && !ListenService.paused) {
            ListenService.send(this, ListenService.ACTION_LISTEN_NOW)
            return
        }
        ownCapture = true
        Assistant.beginListening()
        capture.restrictToCommands = prefs.restrictVocabulary
        Thread {
            Thread.sleep(90) // let the short listening tone finish so it isn't recorded
            val clip = try {
                capture.capture(useVosk = Assistant.voskReady()) { level -> Assistant.level(level) }
            } catch (e: Exception) {
                SpeechCapture.Result(ShortArray(0), emptyList())
            }
            ownCapture = false
            main.post { Assistant.process(clip.pcm, clip.transcripts, fromWake = false) }
        }.start()
    }

    // ---------------- Assistant.Listener ----------------

    override fun onPhase(phase: Assistant.Phase) {
        when (phase) {
            Assistant.Phase.IDLE -> {
                status.setText(R.string.tap_to_speak)
                main.removeCallbacks(hideCaption)
                main.postDelayed(hideCaption, 4000)
                renderSetup()
            }
            Assistant.Phase.LISTENING -> {
                status.setText(R.string.listening)
                main.removeCallbacks(hideCaption)
                caption.visibility = View.INVISIBLE
                ink.setMode(InkView.Mode.LISTENING)
            }
            Assistant.Phase.THINKING -> {
                status.setText(R.string.thinking)
                ink.setMode(InkView.Mode.THINKING)
            }
            Assistant.Phase.SPEAKING -> Unit
        }
    }

    override fun onLevel(level: Float) = ink.setLevel(level)

    override fun onResult(heard: String?, caption: String, ok: Boolean, timing: String?) {
        ink.setMode(if (ok) InkView.Mode.DONE else InkView.Mode.FAILED)
        if (caption.isBlank()) return // a false wake, ended quietly
        status.text = caption
        if (prefs.captions) {
            this.caption.text = if (heard.isNullOrBlank()) caption else "“$heard”  →  $caption"
            this.caption.visibility = View.VISIBLE
        }
        if (timing != null) footer.text = timing
    }

    override fun onModelsChanged() = renderSetup()

    override fun onOpenSettings(intent: Intent) {
        main.postDelayed({ runCatching { startActivity(intent) } }, 1500)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderSetup()
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (requestCode == REQ_MIC && granted) onMicTapped()
        if (requestCode == REQ_NOTIFY && prefs.alwaysListening) startListeningService()
    }

    // ---------------- always-on listening ----------------

    private fun startListeningService() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationAsked
        ) {
            notificationAsked = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        ListenService.send(this, ListenService.ACTION_START)
        main.postDelayed({ renderSetup() }, 600)
    }
    private var notificationAsked = false

    private fun controlModeOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(this, ControlService::class.java).flattenToString()
        return ControlService.instance != null || enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun batteryUnrestricted() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    // ---------------- setup checklist ----------------

    private fun renderSetup() {
        if (!::setupList.isInitialized) return
        val models = Assistant.models
        val gemma = Assistant.gemma
        setupList.removeAllViews()
        header("Setup")

        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        row("Microphone", if (mic) "Allowed" else "Needed to hear you", mic, "Allow") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        val gemmaDetail = when (gemma.status) {
            GemmaEngine.Status.NOT_INSTALLED ->
                "Download ${GemmaEngine.MODEL_FILE_NAME} (2.59 GB) in Chrome from Hugging Face " +
                    "(litert-community/gemma-4-E2B-it-litert-lm), then import it. " +
                    "Edge Gallery's copy is private to Edge Gallery, so it can't be reused."
            GemmaEngine.Status.LOADING -> gemma.detail.ifBlank { "Loading…" }
            GemmaEngine.Status.READY -> gemma.detail
            GemmaEngine.Status.FAILED -> gemma.detail
        }
        row(
            "Gemma 4 E2B (main brain)", gemmaDetail, Assistant.gemmaReady(),
            if (gemma.isInstalled()) "Replace" else "Import",
        ) { pickGemma() }
        val en = Assistant.speaker.hasEnglishVoice()
        val hi = Assistant.speaker.hasHindiVoice()
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

        header("Always listening")
        toggle(
            "Listen for “Hey Abhimanyu”",
            when {
                !prefs.alwaysListening -> "Off. Only the mic button works."
                ListenService.paused -> "Paused from the notification"
                ListenService.running -> "On, even with the app closed"
                else -> "Starting…"
            },
            prefs.alwaysListening,
        ) { on ->
            prefs.alwaysListening = on
            if (on) startListeningService() else ListenService.send(this, ListenService.ACTION_STOP)
            main.postDelayed({ renderSetup() }, 600)
        }
        val wakeReady = ListenService.running && ListenService.wakeStatus.startsWith("Listening")
        row(
            "Wake word ear", if (ListenService.running) ListenService.wakeStatus else
                "Uses a small Vosk speech model (Hindi preferred, 40 MB) only to catch the word “Abhimanyu”; Gemma does the rest.",
            wakeReady, if (models.isInstalled(ModelStore.Kind.HINDI)) "Replace" else "Import",
        ) { pickModel(ModelStore.Kind.HINDI) }
        val control = controlModeOn()
        row(
            "Control mode",
            if (control) "On: ink over other apps, lock phone, timers while closed"
            else "Settings → Accessibility → Abhimanyu. If it's greyed out: App info → ⋮ → Allow restricted settings.",
            control, "Open",
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        val battery = batteryUnrestricted()
        row("Battery: unrestricted", if (battery) "Android won't stop listening to save power" else "So Android doesn't stop listening", battery, "Allow") {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }

        header("Backup speech (optional)")
        hint("Only used if Gemma isn't loaded. The Hindi one also serves as the wake word ear.")
        for (kind in ModelStore.Kind.entries) {
            val installed = models.isInstalled(kind)
            val detail = when {
                !installed -> "Not installed"
                models.model(kind) == null -> if (Assistant.modelsLoading) "Loading…" else "Installed, not loaded"
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
        toggle("Show timings", "Under the mic: how long each step took", prefs.showTimings) {
            prefs.showTimings = it
            if (!it) footer.text = "fully offline"
        }
        toggle("Backup: command words only", "Limits Vosk to known commands; more accurate", prefs.restrictVocabulary) {
            prefs.restrictVocabulary = it
        }

        header("Try saying")
        hint(
            "“Hey Abhimanyu, torch band karo” · “दस मिनट का टाइमर लगाओ”\n" +
                "“Kal subah saade chhe baje ka alarm” · “Volume 50 percent”\n" +
                "“DND on for one hour” · “Kitne baje hain?” · “Lock the phone”",
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(text: String) {
        setupList.addView(TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.ink_soft))
            textSize = 12f
            typeface = Typeface.create(resources.getFont(R.font.hind), 600, false)
            isAllCaps = true
            letterSpacing = 0.1f
            setPadding(0, if (setupList.childCount == 0) 0 else dp(22), 0, dp(4))
        })
    }

    private fun hint(text: String) {
        setupList.addView(TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.ink))
            textSize = 14f
            setLineSpacing(0f, 1.3f)
        })
    }

    private fun textBlock(title: String, detail: String, done: Boolean?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                // A done item gets the vermilion tick, the only colour on the screen.
                text = when (done) {
                    true -> SpannableString("✓  $title").apply {
                        setSpan(ForegroundColorSpan(getColor(R.color.seal)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    false -> SpannableString("·  $title").apply {
                        setSpan(ForegroundColorSpan(getColor(R.color.ink_soft)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    null -> title
                }
                setTextColor(getColor(R.color.ink))
                typeface = Typeface.create(resources.getFont(R.font.hind), 500, false)
                textSize = 16f
            })
            addView(TextView(context).apply {
                text = detail
                setTextColor(getColor(R.color.ink_soft))
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
                    background = getDrawable(R.drawable.ink_button)
                    setTextColor(getColor(R.color.ink))
                    textSize = 14f
                    minHeight = dp(36)
                    minimumHeight = dp(36)
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(14), 0, dp(14), 0)
                    stateListAnimator = null
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(36),
                    ).apply { marginStart = dp(12) }
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
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList(states, intArrayOf(getColor(R.color.ink), getColor(R.color.paper)))
                trackTintList = ColorStateList(states, intArrayOf(getColor(R.color.ink_soft), getColor(R.color.hairline)))
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
        Assistant.background {
            val result = Assistant.gemma.import(uri) { pct -> main.post { status.text = "Importing Gemma 4… $pct%" } }
            Assistant.notifyModels()
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
        renderSetup()
        Assistant.background {
            val result = Assistant.models.import(kind, uri)
            Assistant.notifyModels()
            main.post {
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
        const val EXTRA_START_LISTENING = "start_listening"
        private const val REQ_MIC = 1
        private const val REQ_IMPORT = 2
        private const val REQ_IMPORT_GEMMA = 3
        private const val REQ_NOTIFY = 4
    }
}
