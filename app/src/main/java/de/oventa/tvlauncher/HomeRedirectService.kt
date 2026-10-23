package de.oventa.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Sobald die Amazon-Startseite (com.amazon.tv.launcher) in den Vordergrund kommt,
 * wird dieser Launcher geoeffnet. Ausgewertet wird nur der Paketname des Fensters,
 * keine Bildschirminhalte (canRetrieveWindowContent=false).
 * Fire OS holt die Amazon-Startseite nach einem Home-Druck oft noch einmal nach vorne,
 * deshalb wird kurz danach nachgeprueft und bei Bedarf erneut umgeleitet.
 */
class HomeRedirectService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var foreground: String? = null
    private var lastStart = 0L

    private val recheck = Runnable {
        if (foreground == AMAZON_LAUNCHER) start(force = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        foreground = pkg
        if (pkg != AMAZON_LAUNCHER) return
        if (System.currentTimeMillis() < Prefs(this).bypassUntil) return
        start(force = false)
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, 400)
        handler.postDelayed(recheck, 1200)
        handler.postDelayed(recheck, 2500)
    }

    private fun start(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStart < 250) return
        lastStart = now
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try { startActivity(i) } catch (_: Exception) { }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
    }
}
