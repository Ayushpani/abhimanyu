package dev.abhimanyu.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.math.hypot
import kotlin.math.min

/**
 * One recorded tap. Steps are matched by what the button *is* (its id, label, description and
 * type), with its rough position only as a tie-breaker, so app updates that move things around
 * don't break a taught task.
 */
data class TaskStep(
    val packageName: String,
    val viewId: String? = null,
    val text: String? = null,
    val description: String? = null,
    val className: String? = null,
    /** Centre of the tapped element as a fraction of the screen (0..1). */
    val x: Float = 0.5f,
    val y: Float = 0.5f,
) {
    /** A short human name for the step, used in spoken errors ("I can't find Punch In"). */
    val label: String get() = text?.takeIf { it.isNotBlank() } ?: description?.takeIf { it.isNotBlank() }
        ?: viewId?.substringAfterLast('/')?.replace('_', ' ') ?: "the button at ${(x * 100).toInt()}%, ${(y * 100).toInt()}%"

    val hasIdentity: Boolean get() = !viewId.isNullOrBlank() || !text.isNullOrBlank() || !description.isNullOrBlank()
}

/** A task taught with "watch me", such as clocking in on Spine HR. */
data class AppTask(
    val id: String,
    val name: String,
    val packageName: String,
    val appLabel: String,
    val steps: List<TaskStep>,
    /** Text that should be on screen when the task worked, e.g. "Punched in successfully". */
    val successText: String? = null,
)

/** Run [taskId] at [hour]:[minute] on [days]. */
data class TaskSchedule(
    val taskId: String,
    val days: Set<DayOfWeek>,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
) {
    init {
        require(hour in 0..23 && minute in 0..59) { "bad time $hour:$minute" }
    }

    /** The next moment strictly after [now] this schedule fires, or null if it has no days. */
    fun nextFire(now: LocalDateTime): LocalDateTime? {
        if (days.isEmpty() || !enabled) return null
        for (offset in 0..7) {
            val day = now.toLocalDate().plusDays(offset.toLong())
            if (day.dayOfWeek !in days) continue
            val at = day.atTime(hour, minute)
            if (at.isAfter(now)) return at
        }
        return null
    }
}

/** A schedule as spoken ("clock in at 10 Monday to Friday"), before the task name is resolved. */
data class SpokenSchedule(val taskName: String, val days: Set<DayOfWeek>, val hour: Int, val minute: Int)

/** A plain snapshot of one on-screen element, so matching can be tested without Android. */
data class ScreenNode(
    val viewId: String?,
    val text: String?,
    val description: String?,
    val className: String?,
    val x: Float,
    val y: Float,
    val clickable: Boolean,
    val isPassword: Boolean = false,
)

/** Finds the on-screen element that best matches a recorded step. */
object StepMatcher {

    private fun norm(s: String?) = s?.lowercase()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    fun score(step: TaskStep, node: ScreenNode): Double {
        var score = 0.0
        var identity = false
        val sid = norm(step.viewId)
        val st = norm(step.text)
        val sd = norm(step.description)
        if (sid.isNotEmpty() && sid == norm(node.viewId)) { score += 3.0; identity = true }
        val nt = norm(node.text)
        if (st.isNotEmpty() && nt.isNotEmpty()) {
            if (st == nt) { score += 3.0; identity = true }
            else if (nt.contains(st) || st.contains(nt)) { score += 1.5; identity = true }
        }
        val nd = norm(node.description)
        if (sd.isNotEmpty() && nd.isNotEmpty()) {
            if (sd == nd) { score += 2.0; identity = true }
            else if (nd.contains(sd) || sd.contains(nd)) { score += 1.0; identity = true }
        }
        if (step.hasIdentity && !identity) return 0.0
        if (!step.className.isNullOrBlank() && step.className == node.className) score += 0.5
        val dist = hypot((step.x - node.x).toDouble(), (step.y - node.y).toDouble())
        score += 1.0 - min(dist / 0.3, 1.0)
        // Unlabelled buttons can only be found by position, and only if it is very close.
        if (!step.hasIdentity) return if (dist < 0.04) score + 2.0 else 0.0
        return score
    }

    /** The best match scoring at least [threshold], or null. */
    fun best(step: TaskStep, nodes: List<ScreenNode>, threshold: Double = 2.5): ScreenNode? =
        nodes.map { it to score(step, it) }.filter { it.second >= threshold }.maxByOrNull { it.second }?.first
}

/** Apps the assistant must never operate: payments, UPI, banking and photo galleries. */
object BlockedApps {
    val PACKAGES: Set<String> = setOf(
        // UPI and wallets
        "com.google.android.apps.nbu.paisa.user", "net.one97.paytm", "com.phonepe.app", "in.org.npci.upiapp",
        "com.mobikwik_new", "com.freecharge.android", "com.dreamplug.androidapp", "money.super.payments",
        // Banks
        "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", "com.snapwork.hdfc", "com.hdfcbank.payzapp",
        "com.csam.icici.bank.imobile", "com.icicibank.pockets", "com.axis.mobile", "com.msf.kbank.mobile",
        "com.kotak811mobilebankingapp.instantsavingsupiscanandpayrecharge", "com.bankofbaroda.mconnect",
        "com.infrasofttech.indianBank", "com.pnb.pnbone", "com.canarabank.mobility", "com.unionbankofindia.ubm",
        "com.yesbank", "com.idfcfirstbank.optimus", "com.fedbank.fedmobile", "com.indusind.indie",
        // Photos and galleries
        "com.google.android.apps.photos", "com.google.android.apps.photosgo", "com.sec.android.gallery3d",
        "com.miui.gallery", "com.coloros.gallery3d", "com.oneplus.gallery", "com.android.gallery3d",
    )

    private val NAME_HINTS = listOf("bank", "upi", "wallet", "gallery", "pay")

    fun isBlocked(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (packageName in PACKAGES) return true
        val segments = packageName.lowercase().split('.')
        return segments.any { it == "upi" || it.contains("bank") || it.contains("gallery") }
    }

    /** Extra check on an app's visible name (e.g. "XYZ Bank", "ABC Pay"). */
    fun looksBlocked(appLabel: String): Boolean {
        val l = appLabel.lowercase()
        return NAME_HINTS.any { Regex("\\b$it\\b").containsMatchIn(l) }
    }
}

/** Matches spoken task names ("clock in", "punch in karo") to saved tasks. */
object TaskNames {
    private fun tokens(s: String) = Normalizer.normalize(s.replace('-', ' ')).filter { it.length > 1 || it.any(Char::isDigit) }

    /** The saved name that [spoken] refers to, or null if none or more than one fits equally. */
    fun resolve(spoken: String, names: List<String>): String? {
        val st = tokens(spoken).toSet()
        if (st.isEmpty()) return null
        val scored = names.map { n ->
            val nt = tokens(n)
            val hit = nt.count { it in st }
            n to if (nt.isEmpty()) 0.0 else hit.toDouble() / nt.size
        }.filter { it.second >= 1.0 }
        if (scored.isEmpty()) return null
        // Prefer the longest fully-matched name ("clock in late" over "clock in").
        val best = scored.maxOf { tokens(it.first).size }
        val top = scored.filter { tokens(it.first).size == best }
        return top.singleOrNull()?.first
    }

    /** If a transcript names a saved task with a "do it" word, the task it means. */
    fun findInTranscript(transcript: String, names: List<String>): String? = resolve(transcript, names)
}

/** Minimal JSON writer to go with [MiniJson], for saving tasks and schedules. */
object JsonWriter {
    fun write(v: Any?): String = buildString { value(this, v) }

    private fun value(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> string(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    string(sb, k.toString())
                    sb.append(':')
                    value(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) { if (!first) sb.append(','); first = false; value(sb, e) }
                sb.append(']')
            }
            else -> string(sb, v.toString())
        }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }
}

/** Saves and loads tasks and schedules as JSON. */
object TaskCodec {
    private val DAY_CODES = mapOf(
        DayOfWeek.MONDAY to "mon", DayOfWeek.TUESDAY to "tue", DayOfWeek.WEDNESDAY to "wed", DayOfWeek.THURSDAY to "thu",
        DayOfWeek.FRIDAY to "fri", DayOfWeek.SATURDAY to "sat", DayOfWeek.SUNDAY to "sun",
    )
    private val CODE_DAYS = DAY_CODES.entries.associate { it.value to it.key }

    fun dayCode(d: DayOfWeek) = DAY_CODES.getValue(d)
    fun dayFromCode(c: String) = CODE_DAYS[c.lowercase().take(3)]

    fun encode(tasks: List<AppTask>, schedules: List<TaskSchedule>): String = JsonWriter.write(
        mapOf(
            "version" to 1,
            "tasks" to tasks.map { t ->
                mapOf(
                    "id" to t.id, "name" to t.name, "package" to t.packageName, "app" to t.appLabel,
                    "success" to t.successText,
                    "steps" to t.steps.map { s ->
                        mapOf(
                            "package" to s.packageName, "id" to s.viewId, "text" to s.text, "desc" to s.description,
                            "class" to s.className, "x" to s.x.toDouble(), "y" to s.y.toDouble(),
                        )
                    },
                )
            },
            "schedules" to schedules.map { s ->
                mapOf(
                    "task" to s.taskId, "days" to s.days.sorted().map { dayCode(it) },
                    "hour" to s.hour, "minute" to s.minute, "enabled" to s.enabled,
                )
            },
        ),
    )

    data class Decoded(val tasks: List<AppTask>, val schedules: List<TaskSchedule>)

    @Suppress("UNCHECKED_CAST")
    fun decode(json: String): Decoded {
        val root = runCatching { MiniJson.parse(json) as Map<String, Any?> }.getOrNull() ?: return Decoded(emptyList(), emptyList())
        val tasks = (root["tasks"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { t ->
            runCatching {
                AppTask(
                    id = t["id"] as String, name = t["name"] as String, packageName = t["package"] as String,
                    appLabel = (t["app"] as? String).orEmpty(), successText = t["success"] as? String,
                    steps = (t["steps"] as List<Map<String, Any?>>).map { s ->
                        TaskStep(
                            packageName = s["package"] as String, viewId = s["id"] as? String, text = s["text"] as? String,
                            description = s["desc"] as? String, className = s["class"] as? String,
                            x = (s["x"] as Double).toFloat(), y = (s["y"] as Double).toFloat(),
                        )
                    },
                )
            }.getOrNull()
        }
        val schedules = (root["schedules"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { s ->
            runCatching {
                TaskSchedule(
                    taskId = s["task"] as String,
                    days = (s["days"] as List<String>).mapNotNull { dayFromCode(it) }.toSet(),
                    hour = (s["hour"] as Double).toInt(), minute = (s["minute"] as Double).toInt(),
                    enabled = s["enabled"] as? Boolean ?: true,
                )
            }.getOrNull()
        }
        return Decoded(tasks, schedules)
    }
}
