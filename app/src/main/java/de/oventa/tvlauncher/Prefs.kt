package de.oventa.tvlauncher

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Reihenfolge = Reihenfolge auf dem Startbildschirm. */
    var visible: List<String>
        get() = sp.getString("visible", null)?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_VISIBLE
        set(v) { sp.edit().putString("visible", v.joinToString(",")).apply() }

    var wallpaper: Int
        get() = sp.getInt("wallpaper", 0)
        set(v) { sp.edit().putInt("wallpaper", v).apply() }

    var showInputs: Boolean
        get() = sp.getBoolean("showInputs", true)
        set(v) { sp.edit().putBoolean("showInputs", v).apply() }

    /** Bis zu diesem Zeitpunkt leitet der Home-Dienst nicht um (um die Amazon-Startseite bewusst zu öffnen). */
    var bypassUntil: Long
        get() = sp.getLong("bypassUntil", 0L)
        set(v) { sp.edit().putLong("bypassUntil", v).apply() }
}
