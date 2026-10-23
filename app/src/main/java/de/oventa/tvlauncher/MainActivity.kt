package de.oventa.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    val apps = mutableStateOf<List<TvApp>>(emptyList())
    val inputs = mutableStateOf<List<TvInput>>(emptyList())
    val visible = mutableStateOf<List<String>>(emptyList())
    val wallpaper = mutableStateOf(0)
    val showInputs = mutableStateOf(true)
    val resumeTick = mutableStateOf(0)

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { reload() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        visible.value = prefs.visible
        wallpaper.value = prefs.wallpaper
        showInputs.value = prefs.showInputs
        reload()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, pkgReceiver, f, ContextCompat.RECEIVER_EXPORTED)
        setContent {
            LauncherScreen(
                activity = this,
                apps = apps.value,
                visible = visible.value,
                inputs = if (showInputs.value) inputs.value else emptyList(),
                wallpaper = wallpaper.value,
                showInputs = showInputs.value,
                resumeTick = resumeTick.value
            )
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.value = resumeTick.value + 1
    }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    fun reload() {
        Thread {
            val a = Apps.load(this)
            val i = Apps.inputs(this)
            runOnUiThread { apps.value = a; inputs.value = i }
        }.start()
    }

    fun toggleVisible(pkg: String) {
        val cur = visible.value
        val next = if (cur.contains(pkg)) cur - pkg else cur + pkg
        visible.value = next
        prefs.visible = next
    }

    fun move(pkg: String, delta: Int) {
        val cur = visible.value.toMutableList()
        val idx = cur.indexOf(pkg)
        val to = idx + delta
        if (idx < 0 || to < 0 || to >= cur.size) return
        cur.removeAt(idx); cur.add(to, pkg)
        visible.value = cur
        prefs.visible = cur
    }

    fun setWallpaper(i: Int) { wallpaper.value = i; prefs.wallpaper = i }

    fun setShowInputs(b: Boolean) { showInputs.value = b; prefs.showInputs = b }

    fun openFireSettings() {
        prefs.bypassUntil = System.currentTimeMillis() + 2 * 60 * 1000
        try {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    fun openAmazonHome() {
        prefs.bypassUntil = System.currentTimeMillis() + 2 * 60 * 1000
        val i = packageManager.getLaunchIntentForPackage(HomeRedirectService.AMAZON_LAUNCHER)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(HomeRedirectService.AMAZON_LAUNCHER)
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }
}
