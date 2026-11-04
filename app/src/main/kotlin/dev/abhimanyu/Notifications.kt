package dev.abhimanyu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** The listening notification (required for always-on listening) and the "tap to start" reminder. */
object Notifications {
    const val LISTENING_ID = 1
    const val REMINDER_ID = 2
    private const val CH_LISTENING = "listening"
    private const val CH_REMINDERS = "reminders"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_LISTENING, "Listening", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Abhimanyu listens for \"Hey Abhimanyu\". Pause or stop it here."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Asks you to tap once to start listening after the phone restarts."
            },
        )
    }

    private fun openApp(context: Context, startListening: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context, if (startListening) 11 else 10,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_START_LISTENING, startListening),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun serviceAction(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            context, code, Intent(context, ListenService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun listening(context: Context, paused: Boolean, detail: String): Notification {
        ensureChannels(context)
        val b = Notification.Builder(context, CH_LISTENING)
            .setSmallIcon(R.drawable.ic_stat_ink)
            .setContentTitle(if (paused) "Abhimanyu is paused" else "Listening for “Hey Abhimanyu”")
            .setContentText(detail)
            .setContentIntent(openApp(context, false))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (paused) {
            b.addAction(Notification.Action.Builder(null, "Resume", serviceAction(context, ListenService.ACTION_RESUME, 21)).build())
        } else {
            b.addAction(Notification.Action.Builder(null, "Pause", serviceAction(context, ListenService.ACTION_PAUSE, 22)).build())
        }
        b.addAction(Notification.Action.Builder(null, "Stop", serviceAction(context, ListenService.ACTION_STOP, 23)).build())
        return b.build()
    }

    /** Android won't let the mic start by itself after a restart, so ask for one tap. */
    fun tapToStart(context: Context, why: String) {
        ensureChannels(context)
        val n = Notification.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_ink)
            .setContentTitle("Tap to start listening")
            .setContentText(why)
            .setContentIntent(openApp(context, true))
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(REMINDER_ID, n) }
    }
}
