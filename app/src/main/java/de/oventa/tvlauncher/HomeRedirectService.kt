package de.oventa.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Sobald die Amazon-Startseite (com.amazon.tv.launcher) in den Vordergrund kommt,
 * wird dieser Launcher geoeffnet. Ausgewertet wird nur der Paketname des Fensters,
 * keine Bildschirminhalte (canRetrieveWindowContent=false).
 * Fire OS holt die Amazon-Startseite nach einem Home-Druck oft noch einmal nach vorne,
 * deshalb wird kurz danach nachgeprueft und bei Bedarf erneut umgeleitet.
 *
 * Zusaetzlich wird die Home-Taste direkt abgefangen (onKeyEvent), bevor Fire OS sie verarbeitet.
 * Sonst blockiert Android nach jedem Home-Druck fremde App-Starts fuer ca. 5 Sekunden.
 * Kurz druecken = dieser Launcher, lang druecken (> 0,6 s) = Amazon-Startseite.
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

    private var homeDownAt = 0L

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null || event.keyCode != KeyEvent.KEYCODE_HOME) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) homeDownAt = SystemClock.elapsedRealtime()
            KeyEvent.ACTION_UP -> {
                val held = SystemClock.elapsedRealtime() - homeDownAt
                if (held > 600) {
                    Prefs(this).bypassUntil = System.currentTimeMillis() + 2 * 60 * 1000
                    performGlobalAction(GLOBAL_ACTION_HOME)
                } else {
                    start(force = true)
                }
            }
        }
        return true
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
