package de.oventa.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
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
        Sounds.init(this)
        LauncherMemory.menuKey.value = Prefs(this).menuKey
        state = LauncherState(this) { /* App gestartet: Overlay bleibt, bis sie im Vordergrund ist */ }
        state.reload()
        startLogWatcher()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.android.systemui") foreground = pkg
        if (pkg == packageName) return
        if (pkg != AMAZON_LAUNCHER) {
            // Eine andere App ist vorne (z. B. aus dem Overlay gestartet): Overlay weg
            if (pkg != "com.android.systemui") hideOverlay()
            return
        }
        if (System.currentTimeMillis() < Prefs(this).bypassUntil) return
        onHome()
    }

    /**
     * Hauptmenue-Taste (Standard: Guide). Alle anderen Tasten werden sofort unveraendert
     * durchgereicht. Im Anlern-Modus wird die naechste Taste gespeichert.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (LauncherMemory.learningKey.value) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) handler.post { LauncherMemory.learningKey.value = false }
                return true
            }
            if (code in NAV_CODES) return false
            if (event.action == KeyEvent.ACTION_UP) {
                Prefs(this).menuKey = code
                handler.post {
                    LauncherMemory.menuKey.value = code
                    LauncherMemory.learningKey.value = false
                    Sounds.select()
                }
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK && handleBack(event)) return true
        val menu = LauncherMemory.menuKey.value
        if (menu == 0 || code != menu) return false
        if (event.action == KeyEvent.ACTION_UP) handler.post {
            if (foreground == packageName) start(force = true) else { Prefs(this).bypassUntil = 0L; onHome() }
        }
        return true
    }

    // --- Zurueck lang druecken = Startbildschirm ---
    private var backDownAt = 0L
    private var backLongFired = false
    private var passBack = 0

    /**
     * Kurzer Druck: wird unveraendert als normales "Zurueck" weitergegeben.
     * Langer Druck (ab 0,6 s): Startbildschirm. Die Tastenwiederholung beim Halten wird verschluckt.
     */
    private fun handleBack(e: KeyEvent): Boolean {
        if (passBack > 0) { if (e.action == KeyEvent.ACTION_UP) passBack--; return false }
        if (!Prefs(this).longBackHome || foreground == packageName) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount == 0) {
                    backDownAt = SystemClock.uptimeMillis(); backLongFired = false
                    handler.postDelayed(longBack, LONG_BACK_MS)
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longBack)
                if (!backLongFired) {
                    // Kurzer Druck: normales Zurueck ausloesen; das kuenstliche Ereignis nicht erneut abfangen
                    passBack = 1
                    handler.postDelayed({ passBack = 0 }, 400)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                return true
            }
        }
        return true
    }

    private val longBack = Runnable {
        backLongFired = true
        Sounds.open()
        Prefs(this).bypassUntil = 0L
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
                setContent { LauncherScreen(state, isOverlay = true) }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE
            )
            try {
                LauncherMemory.overlayVisible = false
                state.refreshPrefs()
                wm.addView(v, lp)
                LauncherMemory.overlayVisible = true
                overlay = v
                owner.resume()
            } catch (_: Exception) { }
        }
    }

    fun hideOverlay() {
        handler.post {
            val v = overlay ?: return@post
            overlay = null
            LauncherMemory.overlayVisible = false
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
        private const val LONG_BACK_MS = 600L
        private val NAV_CODES = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_HOME
        )
        @Volatile var instance: HomeRedirectService? = null
    }
}
