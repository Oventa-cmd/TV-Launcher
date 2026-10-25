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

    /** Hintergrund automatisch wechseln, alle n Minuten (0 = aus). */
    var wallRotate: Int
        get() = sp.getInt("wallRotate", 15)
        set(v) { sp.edit().putInt("wallRotate", v).apply() }

    var weather: Boolean
        get() = sp.getBoolean("weather", true)
        set(v) { sp.edit().putBoolean("weather", v).apply() }

    var showInputs: Boolean
        get() = sp.getBoolean("showInputs", true)
        set(v) { sp.edit().putBoolean("showInputs", v).apply() }

    var greeting: Boolean
        get() = sp.getBoolean("greeting", true)
        set(v) { sp.edit().putBoolean("greeting", v).apply() }

    var userName: String
        get() = sp.getString("userName", "Arne") ?: "Arne"
        set(v) { sp.edit().putString("userName", v).apply() }

    var sounds: Boolean
        get() = sp.getBoolean("sounds", true)
        set(v) { sp.edit().putBoolean("sounds", v).apply() }

    var animations: Boolean
        get() = sp.getBoolean("animations", true)
        set(v) { sp.edit().putBoolean("animations", v).apply() }

    /** Zusaetzliche, angelernte Taste fuer den Startbildschirm (0 = aus). Guide ist auf Fire-TVs vom System belegt. */
    var menuKey: Int
        get() {
            // Einmalige Umstellung: Guide war frueher Standard, wird aber von Fire OS abgefangen
            if (!sp.getBoolean("guideMigrated", false)) {
                sp.edit().putBoolean("guideMigrated", true).apply()
                if (sp.getInt("menuKey", android.view.KeyEvent.KEYCODE_GUIDE) == android.view.KeyEvent.KEYCODE_GUIDE)
                    sp.edit().putInt("menuKey", 0).apply()
            }
            return sp.getInt("menuKey", 0)
        }
        set(v) { sp.edit().putInt("menuKey", v).apply() }

    /** Zurueck-Taste lang druecken = Startbildschirm. */
    var longBackHome: Boolean
        get() = sp.getBoolean("longBackHome", true)
        set(v) { sp.edit().putBoolean("longBackHome", v).apply() }

    /** Bis zu diesem Zeitpunkt leitet der Home-Dienst nicht um (um die Amazon-Startseite bewusst zu öffnen). */
    var bypassUntil: Long
        get() = sp.getLong("bypassUntil", 0L)
        set(v) { sp.edit().putLong("bypassUntil", v).apply() }
}
