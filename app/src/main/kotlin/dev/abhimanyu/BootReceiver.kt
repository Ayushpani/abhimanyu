package dev.abhimanyu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a restart, Android doesn't allow the mic to start by itself, so post a one-tap reminder. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs(context).alwaysListening) return
        Notifications.tapToStart(context, "The phone restarted. One tap and Abhimanyu listens again.")
    }
}
