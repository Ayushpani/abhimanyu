package dev.abhimanyu

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import dev.abhimanyu.core.GemmaProtocol
import java.io.File

/**
 * Gemma 4 E2B through LiteRT-LM: the main brain. It hears the audio clip directly and answers
 * with one constrained JSON object (transcript, language, tool), which [GemmaProtocol] validates.
 *
 * Each command gets a fresh conversation, so nothing from one command (or from anything the
 * assistant reads later) can leak into the next. The next conversation is pre-built in the
 * background so the system prompt is already processed when you speak.
 */
class GemmaEngine(private val context: Context) {

    enum class Status { NOT_INSTALLED, LOADING, READY, FAILED }

    @Volatile var status: Status = Status.NOT_INSTALLED
        private set
    @Volatile var detail: String = ""
        private set

    val modelFile = File(context.filesDir, "models/$MODEL_FILE_NAME")

    private var engine: Engine? = null
    private var warm: Conversation? = null
    private var useResponseFormat = true
    private var useThinkingOff = true

    fun isInstalled(): Boolean = modelFile.isFile && modelFile.length() > 500L * 1024 * 1024

    init {
        if (isInstalled()) status = Status.LOADING
    }

    /** Loads the model, GPU first, CPU if the GPU fails. Takes several seconds; background only. */
    @Synchronized
    fun load() {
        if (engine != null) return
        if (!isInstalled()) { status = Status.NOT_INSTALLED; return }
        status = Status.LOADING
        detail = "Loading on GPU…"
        val attempts = listOf("GPU" to { Backend.GPU() }, "CPU" to { Backend.CPU() })
        var lastError: Throwable? = null
        for ((name, backend) in attempts) {
            try {
                val e = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = backend(),
                        audioBackend = Backend.CPU(), // the audio encoder must run on CPU
                        maxNumTokens = 2048,
                        cacheDir = context.cacheDir.absolutePath,
                    ),
                )
                e.initialize()
                engine = e
                detail = "Ready on $name"
                break
            } catch (t: Throwable) {
                lastError = t
                detail = "Loading on CPU…"
            }
        }
        if (engine == null) {
            status = Status.FAILED
            val msg = lastError?.message.orEmpty()
            detail = if ("tensor not found" in msg.lowercase() || "compiled_model" in msg) {
                "This model file is built for a different chip. Import $MODEL_FILE_NAME (2.59 GB), " +
                    "not a Tensor G5/G6, NPU, Qualcomm, Intel or web file."
            } else {
                "Couldn't load: ${msg.ifBlank { "unknown error" }}"
            }
            return
        }
        status = Status.READY
        prepareNext()
    }

    private fun newConversation(): Conversation {
        val e = engine ?: error("Engine not loaded")
        fun config(thinkingOff: Boolean) = ConversationConfig(
            systemInstruction = Contents.of(GemmaProtocol.SYSTEM_PROMPT),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            automaticToolCalling = false,
            maxOutputToken = 256,
            thinkingConfig = if (thinkingOff) ThinkingConfig(enableThinking = false) else null,
            enableResponseFormat = useResponseFormat,
            prefillPrefaceOnInit = true,
        )
        return try {
            e.createConversation(config(useThinkingOff))
        } catch (t: Throwable) {
            // Older model files may not accept a thinking or response-format config; retry plainly.
            useThinkingOff = false
            try {
                e.createConversation(config(false))
            } catch (t2: Throwable) {
                useResponseFormat = false
                e.createConversation(config(false))
            }
        }
    }

    /** Builds the next conversation ahead of time. Call after each command, off the main thread. */
    @Synchronized
    fun prepareNext() {
        if (engine == null || warm != null) return
        warm = runCatching { newConversation() }.getOrNull()
    }

    /**
     * Sends one WAV clip and returns Gemma's raw reply text (expected to be a JSON object).
     * Blocks for about a second or two; run off the main thread.
     */
    @Synchronized
    fun understand(wav: ByteArray): String {
        val conversation = warm ?: newConversation()
        warm = null
        try {
            val contents = Contents.of(Content.AudioBytes(wav), Content.Text(GemmaProtocol.USER_PROMPT))
            val reply = if (useResponseFormat) {
                try {
                    conversation.sendMessage(contents, responseFormat = ResponseFormat.json(GemmaProtocol.SCHEMA))
                } catch (e: IllegalArgumentException) {
                    // Constrained decoding unavailable: rely on the prompt, the parser still validates.
                    useResponseFormat = false
                    conversation.sendMessage(contents)
                }
            } else {
                conversation.sendMessage(contents)
            }
            return reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        } finally {
            runCatching { conversation.close() }
        }
    }

    @Synchronized
    fun close() {
        runCatching { warm?.close() }
        warm = null
        runCatching { engine?.close() }
        engine = null
    }

    // ---------------- import ----------------

    data class ImportResult(val ok: Boolean, val message: String)

    /**
     * Copies a .litertlm file picked with the system file picker into private storage.
     * (Edge Gallery's own copy lives in its private folder, which Android doesn't let other apps read.)
     */
    fun import(uri: Uri, onProgress: (Int) -> Unit): ImportResult {
        val resolver = context.contentResolver
        var name = ""
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: ""
                size = if (c.isNull(1)) -1L else c.getLong(1)
            }
        }
        if (!name.endsWith(".litertlm", ignoreCase = true)) {
            return ImportResult(false, "Pick the .litertlm file (for example $MODEL_FILE_NAME).")
        }
        wrongVariant(name)?.let { return ImportResult(false, it) }
        val dir = modelFile.parentFile!!.apply { mkdirs() }
        val free = StatFs(dir.absolutePath).availableBytes
        if (size > 0 && free < size + 200L * 1024 * 1024) {
            return ImportResult(false, "Not enough free space: need ${size / 1_000_000} MB, have ${free / 1_000_000} MB.")
        }

        synchronized(this) {
            val previousFailed = status == Status.FAILED
            runCatching { warm?.close() }
            warm = null
            runCatching { engine?.close() }
            engine = null
            status = Status.LOADING
            detail = "Importing…"
            // A model that never loaded is useless; free its space before copying the new one.
            if (previousFailed) modelFile.delete()
        }
        val tmp = File(dir, "$MODEL_FILE_NAME.part")
        try {
            val input = resolver.openInputStream(uri) ?: return ImportResult(false, "Couldn't open the file.")
            input.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    var lastPct = -1
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (size > 0) {
                            val pct = (copied * 100 / size).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            if (!tmp.renameTo(modelFile)) {
                modelFile.delete()
                if (!tmp.renameTo(modelFile)) return ImportResult(false, "Couldn't save the model.")
            }
        } catch (e: Exception) {
            tmp.delete()
            status = if (isInstalled()) Status.LOADING else Status.NOT_INSTALLED
            return ImportResult(false, "Import failed: ${e.message}")
        }
        load()
        return if (status == Status.READY) ImportResult(true, "Gemma 4 is ready. $detail.")
        else ImportResult(false, detail)
    }

    companion object {
        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"

        /**
         * Hugging Face also hosts chip-specific builds of Gemma 4 E2B. Those compiled for an NPU
         * (Tensor G5/G6, Qualcomm, Intel) or for the web don't load on the Pixel 10a's Tensor G4,
         * which runs the general build on its GPU. Returns why a file name is wrong, or null.
         */
        fun wrongVariant(fileName: String): String? {
            val n = fileName.lowercase()
            val bad = listOf("tensor_g", "_npu", "npu_", "qualcomm", "intel", "-web", "_web")
            if (bad.none { it in n }) return null
            return "\"$fileName\" is built for a different chip and won't run on the Pixel 10a (Tensor G4). " +
                "Download $MODEL_FILE_NAME (2.59 GB) from the same Hugging Face page instead."
        }

        /** 16 kHz mono 16-bit PCM → WAV bytes, the format LiteRT-LM's audio input expects. */
        fun wav(pcm: ShortArray, sampleRate: Int = 16000): ByteArray {
            val dataLen = pcm.size * 2
            val out = ByteArray(44 + dataLen)
            fun putInt(at: Int, v: Int) { for (i in 0..3) out[at + i] = (v shr (8 * i) and 0xff).toByte() }
            fun putShort(at: Int, v: Int) { out[at] = (v and 0xff).toByte(); out[at + 1] = (v shr 8 and 0xff).toByte() }
            "RIFF".forEachIndexed { i, c -> out[i] = c.code.toByte() }
            putInt(4, 36 + dataLen)
            "WAVEfmt ".forEachIndexed { i, c -> out[8 + i] = c.code.toByte() }
            putInt(16, 16)            // PCM chunk size
            putShort(20, 1)           // PCM format
            putShort(22, 1)           // mono
            putInt(24, sampleRate)
            putInt(28, sampleRate * 2) // byte rate
            putShort(32, 2)           // block align
            putShort(34, 16)          // bits per sample
            "data".forEachIndexed { i, c -> out[36 + i] = c.code.toByte() }
            putInt(40, dataLen)
            for (i in pcm.indices) putShort(44 + i * 2, pcm[i].toInt())
            return out
        }
    }
}
