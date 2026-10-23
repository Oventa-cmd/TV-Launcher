package de.oventa.tvlauncher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf

/**
 * Gemeinsamer Zustand fuer den Startbildschirm - genutzt von der Activity und vom
 * Overlay des Home-Dienstes. Einstellungen liegen in den SharedPreferences.
 */
class LauncherState(private val ctx: Context, private val onLeave: () -> Unit = {}) {
    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())

    val apps = mutableStateOf<List<TvApp>>(emptyList())
    val inputs = mutableStateOf<List<TvInput>>(emptyList())
    val visible = mutableStateOf(prefs.visible)
    val wallpaper = mutableStateOf(prefs.wallpaper)
    val showInputs = mutableStateOf(prefs.showInputs)
    val tick = mutableStateOf(0)

    fun refreshPrefs() {
        visible.value = prefs.visible
        wallpaper.value = prefs.wallpaper
        showInputs.value = prefs.showInputs
        tick.value = tick.value + 1
    }

    fun reload() {
        Thread {
            val a = Apps.load(ctx)
            val i = Apps.inputs(ctx)
            main.post { apps.value = a; inputs.value = i }
        }.start()
    }

    fun launch(pkg: String) { Apps.launch(ctx, pkg); onLeave() }

    fun openInput(id: String) { Apps.openInput(ctx, id); onLeave() }

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
            ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
        onLeave()
    }

    fun openAmazonHome() {
        prefs.bypassUntil = System.currentTimeMillis() + 2 * 60 * 1000
        val i = ctx.packageManager.getLaunchIntentForPackage(HomeRedirectService.AMAZON_LAUNCHER)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(HomeRedirectService.AMAZON_LAUNCHER)
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        onLeave()
    }
}
