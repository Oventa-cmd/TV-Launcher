package de.oventa.tvlauncher

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent

/**
 * App-Tasten der Fernbedienung umbelegen: Netflix/Prime Video -> StreamTV, YouTube -> SmartTube.
 *
 * Zwei Wege, weil Hersteller die App-Tasten unterschiedlich behandeln:
 * 1. Kommt der Tastencode beim Bedienungshilfe-Dienst an, wird er abgefangen (die Original-App startet gar nicht).
 *    Der Code wird automatisch gelernt: Oeffnet sich z. B. Netflix kurz nach einer unbekannten Taste,
 *    gilt diese Taste ab dann als Netflix-Taste.
 * 2. Sonst: Oeffnet sich die Original-App, ohne dass sie im Launcher gestartet wurde, wird sofort umgeleitet.
 */
object Remap {
    class Slot(val id: String, val label: String, val sources: Set<String>, val targets: List<String>, val targetName: String)

    private val SMARTTUBE = listOf("org.smarttube.stable", "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv")

    val slots = listOf(
        Slot("netflix", "Netflix-Taste", setOf("com.netflix.ninja", "com.netflix.mediaclient"), listOf(StreamTV.PKG), "StreamTV"),
        Slot("prime", "Prime-Video-Taste", setOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient", "com.amazon.avod"), listOf(StreamTV.PKG), "StreamTV"),
        Slot("youtube", "YouTube-Taste", setOf("com.google.android.youtube.tv", "com.google.android.youtube.tvkids"), SMARTTUBE, "SmartTube"),
    )

    /** Zuletzt im Launcher bewusst gestartete App (wird dann nicht umgeleitet). */
    @Volatile var ownLaunchPkg: String? = null
    @Volatile var ownLaunchAt = 0L
    fun markOwnLaunch(pkg: String) { ownLaunchPkg = pkg; ownLaunchAt = SystemClock.elapsedRealtime() }

    /** Letzte unbekannte Taste (fuer das automatische Anlernen). */
    @Volatile private var lastKey = 0
    @Volatile private var lastKeyAt = 0L
    @Volatile private var lastRedirectAt = 0L

    private val COMMON = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_UNKNOWN
    )

    fun enabled(c: Context, s: Slot) = Prefs(c).remapOn(s.id)

    fun target(c: Context, s: Slot): String? = s.targets.firstOrNull { p ->
        try { c.packageManager.getPackageInfo(p, 0); true } catch (_: Exception) { false }
    }

    /** true = Taste wurde abgefangen (nicht weitergeben). */
    fun onKey(c: Context, e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code in COMMON) return false
        val p = Prefs(c)
        val s = slots.firstOrNull { p.remapKey(it.id) == code && p.remapOn(it.id) }
        if (s == null) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) { lastKey = code; lastKeyAt = SystemClock.elapsedRealtime() }
            return false
        }
        val t = target(c, s) ?: return false
        if (e.action == KeyEvent.ACTION_UP) open(c, t)
        return true
    }

    /** Vordergrund-App hat gewechselt. true = umgeleitet. */
    fun onForeground(c: Context, pkg: String): Boolean {
        val s = slots.firstOrNull { pkg in it.sources } ?: return false
        if (!enabled(c, s)) return false
        val now = SystemClock.elapsedRealtime()
        if (ownLaunchPkg == pkg && now - ownLaunchAt < 5000) return false
        if (now - lastRedirectAt < 1500) return false
        val t = target(c, s) ?: return false
        // Taste automatisch anlernen, damit die Original-App beim naechsten Mal gar nicht erst startet
        if (lastKey != 0 && now - lastKeyAt < 2500) { Prefs(c).setRemapKey(s.id, lastKey); lastKey = 0 }
        open(c, t)
        return true
    }

    private fun open(c: Context, pkg: String) {
        lastRedirectAt = SystemClock.elapsedRealtime()
        markOwnLaunch(pkg)
        HomeRedirectService.instance?.hideOverlay()
        Apps.launch(c, pkg)
    }
}
