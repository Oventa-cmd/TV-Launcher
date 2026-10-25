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
/** Gemeinsame Erinnerung von Overlay und Activity (gleicher Prozess). */
object LauncherMemory {
    @Volatile var focusedPkg: String? = null
    @Volatile var focusedLabel: String = ""
    /** Zeitpunkt der letzten Pfeiltaste (fuer den Navigations-Klick). */
    @Volatile var lastNavKey: Long = 0L
    /** Zeitpunkt der letzten Intro-Animation; Overlay und Activity spielen sie nur einmal. */
    @Volatile private var introAt: Long = 0L

    fun introRecent() = android.os.SystemClock.uptimeMillis() - introAt < 4000

    fun introRemaining(durationMs: Int): Long =
        (durationMs - (android.os.SystemClock.uptimeMillis() - introAt)).coerceAtLeast(0L)

    fun claimIntro(): Boolean {
        if (introRecent()) return false
        introAt = android.os.SystemClock.uptimeMillis()
        return true
    }
}

class LauncherState(
    private val ctx: Context,
    private val onLeave: () -> Unit = {},
    private val onReady: () -> Unit = {}
) {
    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())

    val apps = mutableStateOf(Apps.cache)
    val inputs = mutableStateOf<List<TvInput>>(emptyList())
    val visible = mutableStateOf(prefs.visible)
    val wallpaper = mutableStateOf(prefs.wallpaper)
    val showInputs = mutableStateOf(prefs.showInputs)
    val greeting = mutableStateOf(prefs.greeting)
    val userName = mutableStateOf(prefs.userName)
    val sounds = mutableStateOf(prefs.sounds)
    val animations = mutableStateOf(prefs.animations)
    val tick = mutableStateOf(0)

    init { Sounds.enabled = prefs.sounds }

    fun refreshPrefs() {
        visible.value = prefs.visible
        wallpaper.value = prefs.wallpaper
        showInputs.value = prefs.showInputs
        greeting.value = prefs.greeting
        userName.value = prefs.userName
        sounds.value = prefs.sounds
        animations.value = prefs.animations
        Sounds.enabled = prefs.sounds
        tick.value = tick.value + 1
    }

    fun setGreeting(b: Boolean) { greeting.value = b; prefs.greeting = b }

    fun setSounds(b: Boolean) { sounds.value = b; prefs.sounds = b; Sounds.enabled = b }

    fun setAnimations(b: Boolean) { animations.value = b; prefs.animations = b }

    fun reload() {
        Thread {
            val a = Apps.load(ctx)
            val i = Apps.inputs(ctx)
            main.post { apps.value = a; inputs.value = i }
        }.start()
    }

    fun ready() = onReady()

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
