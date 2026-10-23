package de.oventa.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Sobald die Amazon-Startseite (com.amazon.tv.launcher) in den Vordergrund kommt,
 * wird dieser Launcher geöffnet. Es werden keine Bildschirminhalte gelesen
 * (canRetrieveWindowContent=false), nur der Paketname des Fensters.
 */
class HomeRedirectService : AccessibilityService() {

    private var last = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != AMAZON_LAUNCHER) return
        if (System.currentTimeMillis() < Prefs(this).bypassUntil) return
        val now = SystemClock.elapsedRealtime()
        if (now - last < 800) return
        last = now
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try { startActivity(i) } catch (_: Exception) { }
    }

    override fun onInterrupt() {}

    companion object {
        const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
    }
}
