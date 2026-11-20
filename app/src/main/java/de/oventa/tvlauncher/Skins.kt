package de.oventa.tvlauncher

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * Komplette Designs ("Skins") wie bei Kodi: Farben, Glas, Ecken, Fokus-Stil, Dock und Schrift.
 * Gleiche Namen wie in StreamTV.
 */
data class Skin(
    val id: String,
    val name: String,
    val accent: Color,
    /** Grundfarbe und Deckkraft (oben/unten) der Glasflaechen */
    val tint: Color, val glassTop: Float, val glassBottom: Float,
    /** Lichtkante: Farbe und Staerke (0 = keine Kante) */
    val edge: Color, val edgeAlpha: Float,
    /** Faktor fuer alle Eckenradien */
    val radius: Float,
    /** Hintergrund/Schrift fokussierter Knoepfe */
    val focusBg: Color, val focusFg: Color,
    /** Rahmen fokussierter Kacheln/Karten */
    val ring: Color,
    val zoom: Float,
    /** Leuchtender Schatten um fokussierte Kacheln */
    val glow: Boolean,
    /** Glas-Dock hinter den Apps */
    val dock: Boolean,
    /** Abdunkelung unten ueber dem Hintergrund */
    val dim: Float,
    val title: FontWeight,
    val panelTop: Color, val panelBottom: Color,
    /** Passender Hintergrund (Index in WALLPAPERS) */
    val wallpaper: Int
)

object Skins {
    val all = listOf(
        Skin(
            "glass", "Glas", Color(0xFF9CC8FF),
            Color.White, 0.15f, 0.05f, Color.White, 1f, 1f,
            Color.White, Color.Black, Color.White, 1.14f, false, true, 0.40f, FontWeight.Bold,
            Color(0xE6343440), Color(0xEB16161C), 8
        ),
        Skin(
            "cinema", "Kino", Color(0xFFE50914),
            Color(0xFF141414), 0.78f, 0.62f, Color.White, 0.12f, 0.35f,
            Color.White, Color.Black, Color.White, 1.08f, false, false, 0.70f, FontWeight.Black,
            Color(0xF5141414), Color(0xF50A0A0A), 10
        ),
        Skin(
            "minimal", "Minimal", Color.White,
            Color.White, 0.07f, 0.02f, Color.White, 0f, 0.6f,
            Color.White, Color.Black, Color.White.copy(alpha = 0.9f), 1.06f, false, false, 0.55f, FontWeight.Light,
            Color(0xF0101012), Color(0xF5060607), 11
        ),
        Skin(
            "neon", "Neon", Color(0xFF3DF5FF),
            Color(0xFF7A5CFF), 0.24f, 0.06f, Color(0xFF3DF5FF), 0.9f, 1.4f,
            Color(0xFF3DF5FF), Color.Black, Color(0xFF3DF5FF), 1.12f, true, true, 0.45f, FontWeight.Bold,
            Color(0xE61A1033), Color(0xEB0B0618), 12
        )
    )

    val current = mutableStateOf(all[0])

    fun load(ctx: Context) {
        val id = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("skin", "glass")
        current.value = all.firstOrNull { it.id == id } ?: all[0]
    }

    fun set(ctx: Context, s: Skin) {
        current.value = s
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString("skin", s.id).apply()
    }
}
