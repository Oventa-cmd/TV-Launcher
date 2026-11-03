package de.oventa.tvlauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Verbindung zur eigenen IPTV-App StreamTV (de.oventa.streamtv):
 * - "Weiterschauen" lesen (ContentProvider content://de.oventa.streamtv.continue/items)
 * - per Deep Link direkt in eine Folge / Serie springen (streamtv://…)
 */
object StreamTV {
    const val PKG = "de.oventa.streamtv"
    private val URI = Uri.parse("content://de.oventa.streamtv.continue/items")

    data class Cont(
        val id: String, val show: String, val season: Int, val episode: Int, val title: String,
        val image: String?, val pos: Long, val dur: Long, val updated: Long, val tmdb: String, val movie: Boolean, val uri: String
    ) {
        val key get() = "stv:$id"
        val progress: Float? get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null
        val subtitle: String get() {
            val left = if (dur > 0) " · noch ${((dur - pos) / 60).coerceAtLeast(1)} Min." else ""
            return (if (movie) "Film" else "S$season · F$episode") + left
        }
    }

    val items = mutableStateOf<List<Cont>>(emptyList())
    val installed = mutableStateOf(false)

    fun isInstalled(c: Context): Boolean = try { c.packageManager.getPackageInfo(PKG, 0); true } catch (_: Exception) { false }

    /** Liste neu lesen (z. B. wenn der Startbildschirm wieder erscheint). */
    suspend fun refresh(c: Context) {
        val inst = isInstalled(c)
        val list = if (!inst) emptyList() else withContext(Dispatchers.IO) {
            try {
                c.contentResolver.query(URI, null, null, null, null)?.use { cur ->
                    fun s(n: String) = cur.getString(cur.getColumnIndexOrThrow(n)) ?: ""
                    fun l(n: String) = cur.getLong(cur.getColumnIndexOrThrow(n))
                    val out = ArrayList<Cont>()
                    while (cur.moveToNext()) out += Cont(
                        s("id"), s("show"), l("season").toInt(), l("episode").toInt(), s("title"),
                        s("image").ifBlank { null }, l("pos"), l("dur"), l("updated"), s("tmdb"), l("movie") == 1L, s("uri")
                    )
                    out
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }   // alte StreamTV-Version ohne Schnittstelle
        }
        installed.value = inst
        items.value = list
    }

    /** Deep-Link fuer einen TMDB-Titel; play = beim Anbieter suchen und direkt abspielen. */
    fun titleUri(type: String, id: Int, play: Boolean, next: Pair<Int, Int>? = null): String =
        "streamtv://title/$type/$id" + (if (play) "?play=1" + (next?.let { "&s=${it.first}&e=${it.second}" } ?: "") else "")

    fun open(c: Context, uri: String): Boolean = try {
        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }
}
