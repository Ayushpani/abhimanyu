package dev.abhimanyu.core

import java.time.LocalDateTime

/** Risk tiers from the design doc. The tier decides what proof an action needs. */
enum class Tier(val level: Int) {
    /** Wake word only. Works on the lock screen. */
    SAFE(0),
    /** Phone must be unlocked. */
    PERSONAL(1),
    /** Read-back plus fingerprint. */
    SENSITIVE(2),
    /** Never possible by voice. */
    BLOCKED(3),
}

/** Language of a spoken command, and of the reply. */
enum class Lang { EN, HI, HINGLISH }

enum class VolumeStream { MEDIA, RING, ALARM }
enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }
enum class RingerMode { SILENT, VIBRATE, NORMAL }
enum class Meridiem { AM, PM }

/** Which language replies use. MATCH follows the language of each command. */
enum class ReplyLanguagePref { MATCH, ENGLISH, HINDI }

/** A relative or absolute change to a 0-100 level such as volume or brightness. */
sealed interface Adjust {
    data object Up : Adjust
    data object Down : Adjust
    data class To(val percent: Int) : Adjust
}

/**
 * A clock time as spoken. [hour] is 0-23 when it is unambiguous (a meridiem was given,
 * or the hour is 0 or above 12); otherwise 1-12 and [nextOccurrence] picks AM or PM.
 */
data class AlarmTime(
    val hour: Int,
    val minute: Int,
    val meridiem: Meridiem? = null,
    val tomorrow: Boolean = false,
) {
    init {
        require(hour in 0..23) { "hour out of range: $hour" }
        require(minute in 0..59) { "minute out of range: $minute" }
    }

    private fun candidateHours(): List<Int> = when {
        hour == 0 || hour >= 13 -> listOf(hour)
        meridiem == Meridiem.AM -> listOf(hour % 12)
        meridiem == Meridiem.PM -> listOf(hour % 12 + 12)
        else -> listOf(hour % 12, hour % 12 + 12)
    }

    /** The first moment after [now] matching this time (on tomorrow's date if [tomorrow]). */
    fun nextOccurrence(now: LocalDateTime): LocalDateTime {
        val day = if (tomorrow) now.toLocalDate().plusDays(1) else now.toLocalDate()
        return candidateHours().map { h ->
            val at = day.atTime(h, minute)
            if (!tomorrow && !at.isAfter(now)) at.plusDays(1) else at
        }.min()
    }
}

/** Everything the assistant can be asked to do. Each command carries its fixed risk tier. */
sealed class Command(val tier: Tier) {
    data class Flashlight(val on: Boolean) : Command(Tier.SAFE)
    data class Volume(val stream: VolumeStream, val adjust: Adjust) : Command(Tier.SAFE)
    data class Mute(val stream: VolumeStream, val mute: Boolean) : Command(Tier.SAFE)
    data class Ringer(val mode: RingerMode) : Command(Tier.SAFE)
    data class Dnd(val on: Boolean, val minutes: Int? = null) : Command(Tier.SAFE)
    data class SetAlarm(val time: AlarmTime) : Command(Tier.SAFE)
    data class CancelAlarm(val time: AlarmTime?) : Command(Tier.SAFE)
    data class SetTimer(val seconds: Int) : Command(Tier.SAFE)
    data object TellTime : Command(Tier.SAFE)
    data object TellDate : Command(Tier.SAFE)
    data object TellBattery : Command(Tier.SAFE)
    data class Media(val action: MediaAction) : Command(Tier.SAFE)
    data class Brightness(val adjust: Adjust) : Command(Tier.SAFE)
    data class ReplyLanguage(val pref: ReplyLanguagePref) : Command(Tier.SAFE)
    data class Captions(val on: Boolean) : Command(Tier.SAFE)
    data object Help : Command(Tier.SAFE)
    data object Stop : Command(Tier.SAFE)
    data object Repeat : Command(Tier.SAFE)

    /** Needs the accessibility service, which arrives in Phase 4. */
    data object LockPhone : Command(Tier.SAFE)

    /** Understood, but not something this build can do yet. */
    data class Unsupported(val what: String) : Command(Tier.SAFE)
}
