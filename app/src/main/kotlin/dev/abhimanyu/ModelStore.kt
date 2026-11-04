package dev.abhimanyu

import android.content.Context
import android.net.Uri
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Speech models live in the app's private storage. The app has no internet permission, so the
 * user downloads the Vosk zips in a browser and imports them with the system file picker.
 */
class ModelStore(private val context: Context) {

    enum class Kind(val dirName: String, val label: String) {
        ENGLISH("vosk-en-in", "Indian English"),
        HINDI("vosk-hi", "Hindi"),
    }

    private val loaded = mutableMapOf<Kind, Model>()

    private fun dir(kind: Kind) = File(context.filesDir, "models/${kind.dirName}")

    fun isInstalled(kind: Kind): Boolean = File(dir(kind), "am").isDirectory && File(dir(kind), "conf").isDirectory

    @Synchronized
    fun model(kind: Kind): Model? = loaded[kind]

    /** Loads every installed model. Slow (a second or two each), so call off the main thread. */
    @Synchronized
    fun loadAll() {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        for (kind in Kind.entries) {
            if (loaded[kind] == null && isInstalled(kind)) {
                runCatching { Model(dir(kind).absolutePath) }.onSuccess { loaded[kind] = it }
            }
        }
    }

    data class ImportResult(val ok: Boolean, val sha256: String?, val message: String)

    /**
     * Unzips a Vosk model zip into private storage and returns its SHA-256 so it can be compared
     * with the published checksum. Rejects zips that try to write outside the target folder.
     */
    fun import(kind: Kind, uri: Uri): ImportResult {
        val staging = File(context.cacheDir, "import-${kind.dirName}")
        staging.deleteRecursively()
        staging.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return ImportResult(false, null, "Couldn't open the file.")
            ZipInputStream(DigestInputStream(input.buffered(), digest)).use { zip ->
                val stagingPath = staging.canonicalPath + File.separator
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(staging, entry.name)
                    if (!out.canonicalPath.startsWith(stagingPath)) {
                        return ImportResult(false, null, "Rejected: the zip tried to write outside its folder.")
                    }
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
                // Drain the rest so the hash covers the whole file.
                val buf = ByteArray(64 * 1024)
                while (zip.read(buf) >= 0) Unit
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }

            // Vosk zips contain one top-level folder; find the one holding am/ and conf/.
            val root = staging.walkTopDown().maxDepth(3).firstOrNull {
                File(it, "am").isDirectory && File(it, "conf").isDirectory
            } ?: return ImportResult(false, hash, "That zip doesn't look like a Vosk model.")

            synchronized(this) {
                loaded.remove(kind)?.close()
                val target = dir(kind)
                target.deleteRecursively()
                target.parentFile?.mkdirs()
                if (!root.renameTo(target)) {
                    root.copyRecursively(target, overwrite = true)
                }
            }
            loadAll()
            return ImportResult(true, hash, "${kind.label} model installed.")
        } catch (e: Exception) {
            return ImportResult(false, null, "Import failed: ${e.message}")
        } finally {
            staging.deleteRecursively()
        }
    }
}
