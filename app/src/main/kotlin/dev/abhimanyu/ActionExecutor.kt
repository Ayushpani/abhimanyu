package dev.abhimanyu

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import dev.abhimanyu.core.Adjust
import dev.abhimanyu.core.Command
import dev.abhimanyu.core.MediaAction
import dev.abhimanyu.core.Reply
import dev.abhimanyu.core.RingerMode
import dev.abhimanyu.core.Tier
import dev.abhimanyu.core.VolumeStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * Layer 1 of the design: direct Android APIs and intents, no screen tapping. Every action is
 * checked afterwards where Android lets us read the state back ("verify, don't assume").
 */
class ActionExecutor(private val context: Context, private val prefs: Prefs) {

    /** What happened, plus a settings screen to open if a permission is missing. */
    data class Outcome(val reply: Reply, val ok: Boolean, val openSettings: Intent? = null)

    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    fun execute(command: Command): Outcome {
        // Risk gate, Phase 1 edition: only Tier 0 exists so far. Anything higher is refused.
        if (command.tier != Tier.SAFE) return Outcome(Reply.CantDoYet, false)
        return try {
            when (command) {
                is Command.Flashlight -> flashlight(command.on)
                is Command.Volume -> volume(command.stream, command.adjust)
                is Command.Mute -> mute(command.stream, command.mute)
                is Command.Ringer -> ringer(command.mode)
                is Command.Dnd -> dnd(command.on, command.minutes)
                is Command.SetAlarm -> setAlarm(command)
                is Command.CancelAlarm -> cancelAlarm(command)
                is Command.SetTimer -> timer(command.seconds)
                Command.TellTime -> LocalTime.now().let { Outcome(Reply.TimeNow(it.hour, it.minute), true) }
                Command.TellDate -> Outcome(Reply.DateToday(LocalDate.now()), true)
                Command.TellBattery -> battery()
                is Command.Media -> media(command.action)
                is Command.Brightness -> brightness(command.adjust)
                is Command.ReplyLanguage -> { prefs.replyLanguage = command.pref; Outcome(Reply.ReplyLanguageSet(command.pref), true) }
                is Command.Captions -> { prefs.captions = command.on; Outcome(Reply.CaptionsSet(command.on), true) }
                Command.Help -> Outcome(Reply.Help, true)
                Command.Stop -> Outcome(Reply.Stopped, true)
                Command.LockPhone -> {
                    val control = ControlService.instance
                    if (control != null && control.lockScreen()) Outcome(Reply.Locked, true)
                    else Outcome(Reply.LockNeedsControlMode, false)
                }
                is Command.Unsupported -> Outcome(Reply.CantDoYet, false)
                // Taught tasks need fingerprint and control mode; the activity runs them, not Layer 1.
                is Command.RunTask, is Command.ScheduleTasks -> Outcome(Reply.CantDoYet, false)
                Command.Repeat -> Outcome(Reply.NothingToRepeat, false) // handled by the caller
            }
        } catch (e: SecurityException) {
            needsDnd()
        } catch (e: Exception) {
            Outcome(Reply.Failed, false)
        }
    }

    // ---------- flashlight ----------

    private fun flashlight(on: Boolean): Outcome {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return Outcome(Reply.Failed, false)
        cm.setTorchMode(id, on)
        return Outcome(Reply.Flashlight(on), true)
    }

    // ---------- volume and ringer ----------

    private fun streamId(s: VolumeStream) = when (s) {
        VolumeStream.MEDIA -> AudioManager.STREAM_MUSIC
        VolumeStream.RING -> AudioManager.STREAM_RING
        VolumeStream.ALARM -> AudioManager.STREAM_ALARM
    }

    private fun percentOf(stream: Int): Int {
        val max = audio.getStreamMaxVolume(stream)
        return if (max == 0) 0 else (audio.getStreamVolume(stream) * 100f / max).roundToInt()
    }

    private fun volume(s: VolumeStream, adjust: Adjust): Outcome {
        val stream = streamId(s)
        if (stream == AudioManager.STREAM_RING && !notifications.isNotificationPolicyAccessGranted &&
            audio.ringerMode == AudioManager.RINGER_MODE_SILENT
        ) return needsDnd()
        val max = audio.getStreamMaxVolume(stream)
        val min = audio.getStreamMinVolume(stream)
        val current = audio.getStreamVolume(stream)
        val step = maxOf(1, (max / 10f).roundToInt())
        val target = when (adjust) {
            Adjust.Up -> current + step
            Adjust.Down -> current - step
            is Adjust.To -> (adjust.percent * max / 100f).roundToInt()
        }.coerceIn(min, max)
        if (audio.isStreamMute(stream) && target > 0) audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
        audio.setStreamVolume(stream, target, 0)
        return Outcome(Reply.VolumeSet(s, percentOf(stream)), audio.getStreamVolume(stream) == target)
    }

    private fun mute(s: VolumeStream, mute: Boolean): Outcome {
        val stream = streamId(s)
        audio.adjustStreamVolume(stream, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        return Outcome(Reply.Muted(s, mute), audio.isStreamMute(stream) == mute)
    }

    private fun ringer(mode: RingerMode): Outcome {
        // Android requires Do Not Disturb access to enter or leave silent mode.
        val touchesSilent = mode == RingerMode.SILENT || audio.ringerMode == AudioManager.RINGER_MODE_SILENT
        if (touchesSilent && !notifications.isNotificationPolicyAccessGranted) return needsDnd()
        val target = when (mode) {
            RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
            RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
        }
        audio.ringerMode = target
        return Outcome(Reply.RingerSet(mode), audio.ringerMode == target)
    }

    // ---------- Do Not Disturb ----------

    private fun needsDnd() = Outcome(
        Reply.NeedsPermission(Reply.Permission.DND_ACCESS), false,
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
    )

    private fun dnd(on: Boolean, minutes: Int?): Outcome {
        if (!notifications.isNotificationPolicyAccessGranted) return needsDnd()
        val alarms = context.getSystemService(AlarmManager::class.java)
        val offIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, DndOffReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.cancel(offIntent)
        prefs.dndOffAt = 0L
        val filter = if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        notifications.setInterruptionFilter(filter)
        if (on && minutes != null) {
            // Android's DND API has no duration, so schedule turning it off. A one-minute window
            // needs no exact-alarm permission.
            val at = System.currentTimeMillis() + minutes * 60_000L
            prefs.dndOffAt = at
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, offIntent)
        }
        return Outcome(Reply.DndSet(on, minutes), notifications.currentInterruptionFilter == filter)
    }

    // ---------- alarms and timers (Clock app intents, skipping its UI) ----------

    /**
     * Opens another app's screen (the Clock app for alarms and timers). With the app closed,
     * Android only allows this through control mode's accessibility service.
     */
    private fun launch(intent: Intent): Boolean {
        val control = ControlService.instance
        if (!Assistant.activityVisible && control != null) return control.launch(intent)
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun setAlarm(c: Command.SetAlarm): Outcome {
        val now = LocalDateTime.now()
        val at = c.time.nextOccurrence(now)
        val ok = launch(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, at.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, at.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Abhimanyu")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
        )
        // The Clock app always rings at the *next* matching time, so say honestly which day that is.
        val tomorrow = at.toLocalDate().isAfter(now.toLocalDate())
        return if (ok) Outcome(Reply.AlarmSet(at.hour, at.minute, tomorrow), true) else Outcome(Reply.Failed, false)
    }

    private fun cancelAlarm(c: Command.CancelAlarm): Outcome {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
        val t = c.time
        if (t == null) {
            intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        } else {
            val at = t.nextOccurrence(LocalDateTime.now())
            intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                .putExtra(AlarmClock.EXTRA_HOUR, at.hour % 12)
                .putExtra(AlarmClock.EXTRA_MINUTES, at.minute)
                .putExtra(AlarmClock.EXTRA_IS_PM, at.hour >= 12)
        }
        return if (launch(intent)) Outcome(Reply.AlarmCancelRequested, true) else Outcome(Reply.Failed, false)
    }

    private fun timer(seconds: Int): Outcome {
        val ok = launch(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Abhimanyu")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
        )
        return if (ok) Outcome(Reply.TimerSet(seconds), true) else Outcome(Reply.Failed, false)
    }

    // ---------- battery, media, brightness ----------

    private fun battery(): Outcome {
        val bm = context.getSystemService(BatteryManager::class.java)
        return Outcome(Reply.Battery(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), bm.isCharging), true)
    }

    private fun media(action: MediaAction): Outcome {
        val code = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        return Outcome(Reply.MediaDone(action), true)
    }

    private fun brightness(adjust: Adjust): Outcome {
        if (!Settings.System.canWrite(context)) {
            return Outcome(
                Reply.NeedsPermission(Reply.Permission.MODIFY_SETTINGS), false,
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        }
        val cr = context.contentResolver
        val current = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val currentPct = (current * 100f / 255f).roundToInt()
        val pct = when (adjust) {
            Adjust.Up -> currentPct + 15
            Adjust.Down -> currentPct - 15
            is Adjust.To -> adjust.percent
        }.coerceIn(1, 100)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (pct * 255f / 100f).roundToInt().coerceIn(1, 255))
        return Outcome(Reply.BrightnessSet(pct), true)
    }
}
