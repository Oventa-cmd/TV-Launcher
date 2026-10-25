package de.oventa.tvlauncher

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Zeiten fuer Bildschirmschoner und Ausschalten.
 *  - screen_off_timeout (System): ohne Eingabe startet danach der Bildschirmschoner.
 *  - sleep_timeout (Secure): ohne Eingabe geht der TV danach aus (-1 = nie).
 * Beide brauchen einmalig per adb gewaehrte Rechte (WRITE_SETTINGS, WRITE_SECURE_SETTINGS).
 */
object Power {
    val SAVER_STEPS = listOf(5, 10, 15, 30, 60)
    /** -1 = nie */
    val SLEEP_STEPS = listOf(30, 60, 120, 240, 480, -1)

    fun canWrite(ctx: Context): Boolean =
        Settings.System.canWrite(ctx) &&
            ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun screensaverMinutes(ctx: Context): Int =
        (Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 600_000) / 60_000).coerceAtLeast(1)

    fun sleepMinutes(ctx: Context): Int {
        val ms = try { Settings.Secure.getInt(ctx.contentResolver, "sleep_timeout", -1) } catch (_: Exception) { -1 }
        return if (ms <= 0) -1 else ms / 60_000
    }

    fun setScreensaverMinutes(ctx: Context, min: Int): Boolean = try {
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, min * 60_000)
        // Ausschalten nie vor dem Bildschirmschoner
        val s = sleepMinutes(ctx)
        if (s in 1 until min) setSleepMinutes(ctx, min)
        true
    } catch (_: Exception) { false }

    fun setSleepMinutes(ctx: Context, min: Int): Boolean = try {
        Settings.Secure.putInt(ctx.contentResolver, "sleep_timeout", if (min < 0) -1 else min * 60_000)
        true
    } catch (_: Exception) { false }

    fun label(min: Int): String = when {
        min < 0 -> "nie"
        min < 60 -> "$min min"
        min == 60 -> "1 Std."
        else -> "${min / 60} Std."
    }
}
