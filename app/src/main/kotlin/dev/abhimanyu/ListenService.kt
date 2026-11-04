package dev.abhimanyu

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Always-on listening. A microphone foreground service (with its notification and the green mic
 * dot, as Android requires) that keeps running after the app is closed or swiped away:
 *
 *  1. listens for "Abhimanyu" with [WakeSpotter],
 *  2. on the wake word (or the app's mic button), records the command until you pause,
 *  3. hands the clip to [Assistant], which understands it with Gemma and acts.
 *
 * Pause releases the microphone completely. Listening also pauses itself during phone calls.
 */
class ListenService : Service() {

    companion object {
        const val ACTION_START = "dev.abhimanyu.START"
        const val ACTION_PAUSE = "dev.abhimanyu.PAUSE"
        const val ACTION_RESUME = "dev.abhimanyu.RESUME"
        const val ACTION_STOP = "dev.abhimanyu.STOP"
        const val ACTION_LISTEN_NOW = "dev.abhimanyu.LISTEN_NOW"

        @Volatile var running = false
            private set
        @Volatile var paused = false
            private set
        @Volatile var wakeStatus: String = "Not started"
            private set
        @Volatile private var currentSpotter: WakeSpotter? = null
        /** What the wake word ear last heard (for the live check on the setup screen). */
        val earHeard: String get() = currentSpotter?.lastHeard.orEmpty()

        @Volatile private var listenNow = false
        @Volatile private var stopCapture = false

        fun send(context: Context, action: String) {
            val i = Intent(context, ListenService::class.java).setAction(action)
            if (action == ACTION_START) context.startForegroundService(i) else context.startService(i)
        }

        /** Ends the current command recording early (mic button tapped while listening). */
        fun finishCapture() { stopCapture = true }
    }

    private var thread: Thread? = null
    @Volatile private var loop = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Assistant.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                Assistant.prefs.alwaysListening = false
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> { paused = true; updateNotification() }
            ACTION_RESUME -> { paused = false; updateNotification() }
            ACTION_LISTEN_NOW -> { if (running && !paused) listenNow = true }
            else -> Unit
        }
        if (!running) {
            if (!goForeground()) return START_NOT_STICKY
            running = true
            startLoop()
        }
        return START_STICKY
    }

    private fun detailText(): String = when {
        paused -> "Microphone off. Tap Resume to listen again."
        !Assistant.gemmaReady() -> "Gemma is loading. " + wakeStatus
        else -> wakeStatus
    }

    private fun goForeground(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Notifications.tapToStart(this, "Abhimanyu needs microphone permission to listen.")
            stopSelf()
            return false
        }
        return try {
            startForeground(
                Notifications.LISTENING_ID, Notifications.listening(this, paused, detailText()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            true
        } catch (e: Exception) {
            // Android only lets the mic service start while the app is on screen (e.g. after a
            // restart or when the system restarts us in the background).
            Notifications.tapToStart(this, "Tap once and Abhimanyu listens again.")
            stopSelf()
            false
        }
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(Notifications.LISTENING_ID, Notifications.listening(this, paused, detailText()))
        }
    }

    private fun shutdown() {
        loop = false
        running = false
        paused = false
        thread?.interrupt()
        thread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        loop = false
        running = false
        super.onDestroy()
    }

    // ---------------- the listening loop ----------------

    private fun startLoop() {
        loop = true
        thread = Thread({
            try {
                runLoop()
            } catch (e: InterruptedException) {
                // stopping
            } catch (e: Throwable) {
                // Never take the whole app down from the listening thread; ask for a restart.
                running = false
                Notifications.tapToStart(this, "Listening stopped unexpectedly. Tap to start again.")
                stopSelf()
            }
        }, "abhimanyu-listen").apply { start() }
    }

    @SuppressLint("MissingPermission") // checked in goForeground()
    private fun runLoop() {
        val audio = getSystemService(AudioManager::class.java)
        var spotter: WakeSpotter? = null
        var spotterBuiltForModels = -1
        while (loop) {
            // Paused, or a call is using the mic: release the microphone entirely.
            if (paused || audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
                Thread.sleep(500)
                continue
            }
            // (Re)build the spotter when the speech models change.
            val modelsKey = ModelStore.Kind.entries.count { Assistant.models.model(it) != null }
            if (spotter == null || modelsKey != spotterBuiltForModels) {
                spotter?.close()
                spotter = WakeSpotter(Assistant.models)
                currentSpotter = spotter
                spotterBuiltForModels = modelsKey
                wakeStatus = spotter.status
                updateNotification()
            }

            val minBuf = AudioRecord.getMinBufferSize(SpeechCapture.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SpeechCapture.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, FRAME * 8),
                )
            }.getOrNull()
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                Thread.sleep(2000)
                continue
            }
            try {
                record.startRecording()
                listenUntilInterrupted(record, spotter, audio)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Thread.sleep(1000)
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
        spotter?.close()
    }

    /** Reads the mic until paused, a call starts or the models change. */
    private fun listenUntilInterrupted(record: AudioRecord, spotter: WakeSpotter, audio: AudioManager) {
        val frame = ShortArray(FRAME)
        val modelsKey = ModelStore.Kind.entries.count { Assistant.models.model(it) != null }
        var checks = 0
        while (loop && !paused) {
            val n = record.read(frame, 0, frame.size)
            if (n <= 0) { Thread.sleep(50); continue }
            if (++checks % 10 == 0) { // once a second
                if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) return
                if (ModelStore.Kind.entries.count { Assistant.models.model(it) != null } != modelsKey) return
            }
            // While the assistant is thinking or speaking, keep the mic open but don't spot,
            // so its own voice can't wake it.
            if (Assistant.phase != Assistant.Phase.IDLE) { spotter.reset(); continue }

            val manual = listenNow
            val woke = !manual && spotter.ready && spotter.feed(frame, n)
            if (!manual && !woke) continue
            listenNow = false
            if (!Assistant.canUnderstand()) {
                Assistant.tones.failed()
                continue
            }
            Assistant.beginListening()
            val pcm = recordCommand(record, frame)
            spotter.reset()
            if (pcm == null) { Assistant.cancelListening(); Assistant.tones.failed(); continue }
            Assistant.process(pcm, emptyList(), fromWake = woke)
        }
    }

    /**
     * Records the command after the wake word: waits up to 4 s for you to start, stops after
     * 0.7 s of silence or 8 s. Returns null if nothing was said.
     */
    private fun recordCommand(record: AudioRecord, frame: ShortArray): ShortArray? {
        stopCapture = false
        val pcm = ShortArray(SpeechCapture.SAMPLE_RATE * MAX_MS / 1000)
        var len = 0
        var elapsed = 0
        var silent = 0
        var speech = false
        var noise = 0.0
        while (loop && !paused && !stopCapture) {
            val n = record.read(frame, 0, frame.size)
            if (n <= 0) break
            val room = min(n, pcm.size - len)
            if (room > 0) { System.arraycopy(frame, 0, pcm, len, room); len += room }
            val ms = n * 1000 / SpeechCapture.SAMPLE_RATE
            elapsed += ms
            val db = dbfs(frame, n)
            Assistant.level(((db + 55.0) / 40.0).coerceIn(0.0, 1.0).toFloat())
            if (elapsed <= 200) noise = min(noise, db)
            if (db > max(noise + 8.0, -48.0)) { speech = true; silent = 0 } else silent += ms
            if (speech && silent >= END_SILENCE_MS) break
            if (!speech && elapsed >= NO_SPEECH_MS) break
            if (elapsed >= MAX_MS) break
        }
        Assistant.level(0f)
        return if (speech || stopCapture) pcm.copyOf(len) else null
    }

    private fun dbfs(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        return 20 * log10(max(sqrt(sum / max(n, 1)) / 32768.0, 1e-6))
    }

    private val FRAME = 1600 // 100 ms at 16 kHz
    private val END_SILENCE_MS = 700
    private val NO_SPEECH_MS = 4000
    private val MAX_MS = 8000
}
