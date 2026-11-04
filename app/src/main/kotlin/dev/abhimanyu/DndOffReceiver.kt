package dev.abhimanyu

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Turns Do Not Disturb off when a timed "DND on for one hour" runs out. Not exported. */
class DndOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (prefs.dndOffAt == 0L) return // cancelled or turned off by hand since
        prefs.dndOffAt = 0L
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }
}
