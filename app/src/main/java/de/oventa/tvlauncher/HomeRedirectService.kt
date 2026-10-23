package de.oventa.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Home-Taste -> dieser Launcher.
 *
 * Fire OS haelt nach einem Home-Druck App-Starts fuer einige Sekunden zurueck.
 * Deshalb zeigt der Dienst den Startbildschirm sofort als Bedienungshilfe-Overlay
 * (fuer Overlays gilt die Sperre nicht). Sobald Fire OS den Start erlaubt,
 * uebernimmt die normale Activity und das Overlay verschwindet.
 *
 * Ausgewertet wird nur der Paketname des Vordergrundfensters, keine Bildschirminhalte
 * (canRetrieveWindowContent=false). Optional (READ_LOGS per adb) wird der Home-Druck
 * schon im Systemlog erkannt.
 */
class HomeRedirectService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var foreground: String? = null
    private var lastStart = 0L
    private var overlay: ComposeView? = null
    private val owner = OverlayOwner()
    private lateinit var state: LauncherState

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        state = LauncherState(this) { /* App gestartet: Overlay bleibt, bis sie im Vordergrund ist */ }
        state.reload()
        startLogWatcher()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        foreground = pkg
        if (pkg != AMAZON_LAUNCHER) {
            // Eine andere App ist vorne (z. B. aus dem Overlay gestartet): Overlay weg
            if (pkg != "com.android.systemui") hideOverlay()
            return
        }
        if (System.currentTimeMillis() < Prefs(this).bypassUntil) return
        onHome()
    }

    private fun onHome() {
        showOverlay()
        start(force = false)
    }

    private fun start(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStart < 250) return
        lastStart = now
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try { startActivity(i) } catch (_: Exception) { }
    }

    fun showOverlay() {
        handler.post {
            if (overlay != null) return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val v = ComposeView(this).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent { LauncherScreen(state) }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE
            )
            try {
                state.refreshPrefs()
                wm.addView(v, lp)
                overlay = v
                owner.resume()
            } catch (_: Exception) { }
        }
    }

    fun hideOverlay() {
        handler.post {
            val v = overlay ?: return@post
            overlay = null
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) { }
        }
    }

    @Volatile private var logThread: Thread? = null
    @Volatile private var logProc: Process? = null
    private var lastLogHome = 0L

    private fun startLogWatcher() {
        if (logThread != null) return
        logThread = Thread {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "brief", "-T", "1", "*:I"))
                logProc = p
                p.inputStream.bufferedReader().useLines { lines ->
                    for (l in lines) {
                        if (l.contains("KEYCODE_HOME") && l.contains("ACTION_DOWN")) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastLogHome < 700) continue
                            lastLogHome = now
                            if (System.currentTimeMillis() < Prefs(this).bypassUntil) continue
                            handler.post { onHome() }
                        }
                    }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; name = "home-log"; start() }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        try { logProc?.destroy() } catch (_: Exception) { }
        logThread = null
        hideOverlay()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Minimaler Lifecycle fuer die Compose-Ansicht im Overlay-Fenster. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val ssc = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
        init {
            ssc.performAttach()
            ssc.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
    }

    companion object {
        const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        @Volatile var instance: HomeRedirectService? = null
    }
}
