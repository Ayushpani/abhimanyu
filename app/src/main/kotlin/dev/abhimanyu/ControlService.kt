package dev.abhimanyu

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Control mode: Abhimanyu's accessibility service. For now it:
 *  - draws the ink over any app (and the lock screen) while the assistant is active, in a
 *    window that can't be touched or focused, so it never gets in the way,
 *  - locks the phone on request,
 *  - opens the Clock app for alarms and timers while Abhimanyu itself is closed (Android allows
 *    that only for apps with a bound accessibility service).
 *
 * It never reads notifications or message text. Reading and tapping buttons for taught tasks
 * (such as clocking in) comes next and will be limited to those tasks.
 */
class ControlService : AccessibilityService(), Assistant.Listener {

    companion object {
        @Volatile var instance: ControlService? = null
            private set
    }

    private var ink: InkView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Assistant.init(this)
        addOverlay()
        Assistant.listeners += this
        Assistant.notifyModels()
    }

    private fun addOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        val view = InkView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "Abhimanyu ink"
        }
        runCatching { wm.addView(view, lp) }.onSuccess { ink = view }
    }

    // The app screen draws its own ink; the overlay is for when you're elsewhere.
    private fun overlayActive() = !Assistant.activityVisible

    override fun onPhase(phase: Assistant.Phase) {
        val v = ink ?: return
        if (!overlayActive()) { v.setMode(InkView.Mode.IDLE); return }
        when (phase) {
            Assistant.Phase.LISTENING -> v.setMode(InkView.Mode.LISTENING)
            Assistant.Phase.THINKING -> v.setMode(InkView.Mode.THINKING)
            else -> Unit // DONE/FAILED are set by onResult and fade by themselves
        }
    }

    override fun onLevel(level: Float) {
        if (overlayActive()) ink?.setLevel(level)
    }

    override fun onResult(heard: String?, caption: String, ok: Boolean, timing: String?) {
        if (overlayActive()) ink?.setMode(if (ok) InkView.Mode.DONE else InkView.Mode.FAILED)
    }

    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    /** Starts an activity (e.g. the Clock app) even while Abhimanyu itself is closed. */
    fun launch(intent: Intent): Boolean = runCatching {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        Assistant.listeners -= this
        ink?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }
        ink = null
        if (instance === this) instance = null
        Assistant.notifyModels()
    }
}
