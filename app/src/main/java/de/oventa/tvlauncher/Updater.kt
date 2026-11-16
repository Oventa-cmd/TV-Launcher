package de.oventa.tvlauncher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Selbst-Update des Launchers: sucht automatisch nach neuen GitHub-Releases
 * und installiert das APK per PackageInstaller (wie bei StreamTV).
 */
object Updater {
    private const val API = "https://api.github.com/repos/Oventa-cmd/TV-Launcher/releases/latest"
    private const val EVERY_MS = 6 * 60 * 60 * 1000L   // automatisch alle 6 Stunden

    /** Neueste Version auf GitHub ("" = unbekannt) */
    val latest = mutableStateOf("")
    /** Status-Text für die Einstellungen (null = nichts Besonderes) */
    val status = mutableStateOf<String?>(null)
    /** Download-Fortschritt in Prozent (-1 = kein Download) */
    val progress = mutableStateOf(-1)
    val checking = mutableStateOf(false)
    @Volatile private var apkUrl = ""
    @Volatile private var busy = false
    @Volatile private var lastCheck = 0L

    private fun sp(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Automatisch nach Updates suchen (Einstellung, Standard: an) */
    val auto = mutableStateOf<Boolean?>(null)
    fun autoEnabled(c: Context): Boolean = auto.value ?: sp(c).getBoolean("autoUpdate", true).also { auto.value = it }
    fun setAutoEnabled(c: Context, on: Boolean) { auto.value = on; sp(c).edit().putBoolean("autoUpdate", on).apply() }

    fun installed(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    private fun runOf(v: String): Int = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(v)?.groupValues?.get(3)?.toIntOrNull() ?: 0

    fun available(c: Context): Boolean {
        val cur = runOf(installed(c))
        return cur > 0 && apkUrl.isNotEmpty() && runOf(latest.value) > cur
    }

    /** Beim Start/Zurückkehren: höchstens alle 6 h prüfen, bei neuer Version einmal Hinweis zeigen. */
    suspend fun autoCheck(c: Context) {
        if (!autoEnabled(c)) return
        if (lastCheck != 0L && System.currentTimeMillis() - lastCheck < EVERY_MS) return
        check(c)
        if (available(c)) {
            val ver = latest.value.removePrefix("v")
            if (sp(c).getString("updPrompted", "") != ver) {
                sp(c).edit().putString("updPrompted", ver).apply()
                Toast.makeText(c.applicationContext, "Launcher-Update $ver verfügbar – Einstellungen → Updates", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Neueste Version von GitHub holen. */
    suspend fun check(c: Context, manual: Boolean = false) {
        if (checking.value) return
        checking.value = true
        if (manual) status.value = null
        val err = withContext(Dispatchers.IO) {
            try {
                val con = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "TV-Launcher-Updater")
                }
                try {
                    when (con.responseCode) {
                        200 -> {}
                        404 -> return@withContext "Releases nicht erreichbar (Repo öffentlich?)"
                        403 -> return@withContext "GitHub-Limit erreicht – später erneut"
                        else -> return@withContext "GitHub antwortet nicht (${con.responseCode})"
                    }
                    val j = JSONObject(con.inputStream.bufferedReader().readText())
                    val assets = j.optJSONArray("assets")
                    var url = ""
                    if (assets != null) for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", true)) { url = a.optString("browser_download_url"); break }
                    }
                    apkUrl = url
                    withContext(Dispatchers.Main) { latest.value = j.optString("tag_name") }
                    if (url.isEmpty()) "Im Release ist kein APK" else null
                } finally { con.disconnect() }
            } catch (e: Exception) { e.message ?: "Keine Verbindung" }
        }
        lastCheck = System.currentTimeMillis()
        checking.value = false
        if (err != null) status.value = err
        else if (manual && !available(c)) status.value = "Launcher ist aktuell"
    }

    private fun allowed(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    /** Update laden und installieren (fragt beim ersten Mal nach der Berechtigung). */
    fun install(c: Context) {
        if (busy || apkUrl.isEmpty()) return
        if (!allowed(c)) {
            status.value = "Einmalig erlauben: „Unbekannte Apps installieren“ für TV-Launcher, dann erneut drücken"
            val tries = mutableListOf<Intent>()
            if (Build.VERSION.SDK_INT >= 26) tries += Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName))
            tries += Intent(Settings.ACTION_SECURITY_SETTINGS)
            for (i in tries) {
                try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); break } catch (_: Exception) { }
            }
            return
        }
        busy = true
        status.value = null
        progress.value = 0
        val app = c.applicationContext
        val url = apkUrl
        Thread {
            try { download(app, url) } catch (e: Exception) {
                Log.w("TVLauncher", "Update fehlgeschlagen", e)
                finished(e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "TV-Launcher-Updater")
            val code = c.responseCode
            when {
                code in 300..399 -> {
                    val loc = c.getHeaderField("Location") ?: throw IOException("Weiterleitung ohne Ziel")
                    c.disconnect(); u = URL(u, loc)
                }
                code == 200 -> return c
                else -> { c.disconnect(); throw IOException("Download fehlgeschlagen (HTTP $code)") }
            }
        }
        throw IOException("Zu viele Weiterleitungen")
    }

    private fun download(ctx: Context, url: String) {
        val conn = open(url)
        val total = conn.contentLength.toLong()
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        // Ab Android 12 ohne Rückfrage, sobald der Launcher selbst als Installer eingetragen ist
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        if (Build.VERSION.SDK_INT >= 26) params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        val id = pi.createSession(params)
        val session = pi.openSession(id)
        try {
            session.openWrite("TV-Launcher.apk", 0, if (total > 0) total else -1).use { out ->
                conn.inputStream.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != last) { last = pct; android.os.Handler(android.os.Looper.getMainLooper()).post { progress.value = pct.coerceAtLeast(0) } }
                    }
                }
                session.fsync(out)
            }
            conn.disconnect()
            val intent = Intent(ctx, UpdateReceiver::class.java)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        } catch (e: Exception) {
            session.abandon(); throw e
        } finally { session.close() }
    }

    /** msg = null → ok/Bestätigung, sonst Fehlertext */
    fun finished(msg: String?) {
        busy = false
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            progress.value = -1
            status.value = msg
        }
    }
}

/** Rückmeldung des Systeminstallers. Bei Erfolg startet der neue Launcher von selbst neu. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) try { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                Updater.finished("Bitte am Fernseher bestätigen …")
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.finished(null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.finished("Installation abgebrochen")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Updater.finished("Signatur passt nicht – einmalig per ADB neu installieren")
            PackageInstaller.STATUS_FAILURE_STORAGE -> Updater.finished("Zu wenig Speicher")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> Updater.finished("Vom System blockiert (unbekannte Apps erlauben?)")
            else -> Updater.finished(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation fehlgeschlagen")
        }
    }
}
