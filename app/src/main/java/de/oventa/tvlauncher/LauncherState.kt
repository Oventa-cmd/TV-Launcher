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
    /** Anlern-Modus fuer die Hauptmenue-Taste (vom Dienst ausgewertet). */
    val learningKey = androidx.compose.runtime.mutableStateOf(false)
    /** Aktuell belegte Hauptmenue-Taste (Keycode, 0 = aus). */
    val menuKey = androidx.compose.runtime.mutableStateOf(0)

    fun keyName(code: Int): String = when (code) {
        0 -> "aus"
        android.view.KeyEvent.KEYCODE_GUIDE -> "Guide"
        else -> android.view.KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
            .lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }

    /** Waehrend das Uebergangs-Overlay sichtbar ist, spielt nur dieses die Intro. */
    @Volatile var overlayVisible = false

    fun introRecent() = overlayVisible || android.os.SystemClock.uptimeMillis() - introAt < 1500

    fun introRemaining(durationMs: Int): Long =
        (durationMs - (android.os.SystemClock.uptimeMillis() - introAt)).coerceAtLeast(0L)

    fun claimIntro(force: Boolean = false): Boolean {
        if (!force && introRecent()) return false
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
    val wallRotate = mutableStateOf(prefs.wallRotate)
    val weather = mutableStateOf(prefs.weather)
    val watchlistOn = mutableStateOf(prefs.watchlist)
    val greeting = mutableStateOf(prefs.greeting)
    val userName = mutableStateOf(prefs.userName)
    val sounds = mutableStateOf(prefs.sounds)
    val animations = mutableStateOf(prefs.animations)
    val tick = mutableStateOf(0)
    val longBackHome = mutableStateOf(prefs.longBackHome)
    val screensaverMin = mutableStateOf(Power.screensaverMinutes(ctx))
    val sleepMin = mutableStateOf(Power.sleepMinutes(ctx))
    val canWritePower: Boolean get() = Power.canWrite(ctx)

    init { Sounds.enabled = prefs.sounds; LauncherMemory.menuKey.value = prefs.menuKey }

    fun startLearningKey() { LauncherMemory.learningKey.value = true }
    fun disableMenuKey() { prefs.menuKey = 0; LauncherMemory.menuKey.value = 0; LauncherMemory.learningKey.value = false }
    val serviceActive: Boolean get() = HomeRedirectService.instance != null

    fun refreshPrefs() {
        visible.value = prefs.visible
        wallpaper.value = prefs.wallpaper
        wallRotate.value = prefs.wallRotate
        weather.value = prefs.weather
        watchlistOn.value = prefs.watchlist
        showInputs.value = prefs.showInputs
        greeting.value = prefs.greeting
        userName.value = prefs.userName
        sounds.value = prefs.sounds
        animations.value = prefs.animations
        longBackHome.value = prefs.longBackHome
        screensaverMin.value = Power.screensaverMinutes(ctx)
        sleepMin.value = Power.sleepMinutes(ctx)
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
    fun setWallRotate(m: Int) { wallRotate.value = m; prefs.wallRotate = m }
    fun setWeather(b: Boolean) { weather.value = b; prefs.weather = b }
    fun setWatchlistOn(b: Boolean) { watchlistOn.value = b; prefs.watchlist = b }
    fun openWatchlistLogin() {
        HomeRedirectService.instance?.hideOverlay()
        try { ctx.startActivity(Intent(ctx, WatchlistLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }
    fun isInstalled(pkg: String) = Apps.isInstalled(ctx, pkg)

    fun setLongBackHome(b: Boolean) { longBackHome.value = b; prefs.longBackHome = b }
    fun cycleScreensaver() {
        val next = Power.SAVER_STEPS[(Power.SAVER_STEPS.indexOf(screensaverMin.value) + 1).mod(Power.SAVER_STEPS.size)]
        if (Power.setScreensaverMinutes(ctx, next)) screensaverMin.value = Power.screensaverMinutes(ctx)
    }
    fun cycleSleep() {
        val next = Power.SLEEP_STEPS[(Power.SLEEP_STEPS.indexOf(sleepMin.value) + 1).mod(Power.SLEEP_STEPS.size)]
        if (Power.setSleepMinutes(ctx, next)) sleepMin.value = Power.sleepMinutes(ctx)
    }

    fun setShowInputs(b: Boolean) { showInputs.value = b; prefs.showInputs = b }

    /** Startet den aktiven Bildschirmschoner sofort (Vorschau). */
    fun startScreensaver() {
        try {
            ctx.startActivity(Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

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
