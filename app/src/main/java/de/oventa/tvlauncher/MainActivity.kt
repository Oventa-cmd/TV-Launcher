package de.oventa.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var state: LauncherState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { state.reload() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sounds.init(this)
        state = LauncherState(this, onReady = { HomeRedirectService.instance?.hideOverlay() })
        state.reload()
        state.autoDisableStockOnce()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, pkgReceiver, f, ContextCompat.RECEIVER_EXPORTED)
        setContent { LauncherScreen(state) }
        takeConfig(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeConfig(intent)
    }

    /**
     * Zugangsdaten vom PC (gleiches Format wie bei StreamTV):
     * adb shell am start -n de.oventa.tvlauncher/.MainActivity --es config <Base64 von "WATCHLIST_EMAIL=…\nWATCHLIST_PASSWORT=…">
     * Das Passwort wird nicht gespeichert, nur die Sitzung.
     */
    private fun takeConfig(i: Intent?) {
        val b64 = i?.getStringExtra("config") ?: return
        i.removeExtra("config")
        val text = try { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { return }
        val cfg = HashMap<String, String>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith("#")) continue
            val m = Regex("^([A-Za-z_]+)\\s*=\\s*(.*)$").find(t) ?: continue
            cfg[m.groupValues[1].uppercase()] = m.groupValues[2].trim()
        }
        val mail = cfg["WATCHLIST_EMAIL"].orEmpty()
        val pass = cfg["WATCHLIST_PASSWORT"].orEmpty()
        if (mail.isBlank() || pass.isBlank()) return
        scope.launch {
            val err = Watchlist.login(this@MainActivity, mail, pass)
            if (err == null) { Discover.clear(); state.refreshPrefs() }
            Toast.makeText(this@MainActivity, err ?: "Watchlist angemeldet: $mail", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        state.refreshPrefs()
        // Das Uebergangs-Overlay des Home-Dienstes wird entfernt, sobald dieser Bildschirm fertig ist (state.ready)
    }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) { }
        scope.cancel()
        super.onDestroy()
    }
}
