package de.oventa.tvlauncher

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant

/**
 * Anbindung an die eigene Web-App "Als Nächstes" (Serien-Watchlist, Supabase).
 * Gespeichert wird nur das Refresh-Token, nie das Passwort.
 */
object Watchlist {
    private const val URL_BASE = "https://rdaivvzpgnsfecwkrgyg.supabase.co"
    private const val KEY = "sb_publishable_yYxzES9mAW8Y2ZB_92rKgg_a0mycrXo"
    private const val IMG = "https://image.tmdb.org/t/p/"

    data class Item(
        val type: String, val id: Int, val title: String,
        val backdrop: String?, val poster: String?, val logo: String?,
        val status: String, val position: Double,
        val next: Pair<Int, Int>?, val done: Int, val total: Int,
        val providers: List<String>,
        val raw: JSONObject
    ) {
        val key get() = "$type:$id"
        val subtitle: String get() = when {
            type == "movie" -> "Film"
            next != null -> "S${next.first} · F${next.second}"
            total > 0 -> "Alles gesehen"
            else -> "Serie"
        }
        val progress: Float get() = if (total > 0) done.toFloat() / total else 0f
    }

    val items = mutableStateOf<List<Item>>(emptyList())
    /** Alle Titel der Liste (auch Fertig/Abgebrochen), fuer "nur Neues" in den Entdecken-Reitern. */
    val allKeys = mutableStateOf<Set<String>>(emptySet())
    /** Fuer Empfehlungen: (Typ, ID, Titel, Gewicht) wie in der Web-App */
    @Volatile var seeds: List<Seed> = emptyList()
    @Volatile private var maxPlanned = 0.0
    data class Seed(val type: String, val id: Int, val title: String, val weight: Double, val updated: String)
    val connected = mutableStateOf(false)
    val email = mutableStateOf("")
    @Volatile private var lastLoad = 0L
    private val lock = Mutex()
    private var access: String? = null
    private var accessUntil = 0L
    private var userId: String? = null

    private fun sp(c: Context) = c.getSharedPreferences("watchlist", Context.MODE_PRIVATE)

    fun init(c: Context) {
        val p = sp(c)
        connected.value = p.getString("refresh", null) != null
        email.value = p.getString("email", "") ?: ""
        userId = p.getString("uid", null)
        if (items.value.isEmpty()) loadCache(c)
    }

    fun logout(c: Context) {
        sp(c).edit().clear().apply()
        access = null; userId = null; tmdbKeyCache = null; allKeys.value = emptySet(); seeds = emptyList()
        Discover.clear()
        connected.value = false; items.value = emptyList()
        File(c.cacheDir, "watchlist.json").delete()
    }

    /** Anmeldung mit E-Mail und Passwort (einmalig). Gibt Fehlermeldung oder null zurueck. */
    suspend fun login(c: Context, mail: String, pass: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("email", mail.trim()).put("password", pass).toString()
            val (code, txt) = http("POST", "$URL_BASE/auth/v1/token?grant_type=password", body, auth = false)
            if (code !in 200..299) return@withContext if (code == 400) "E-Mail oder Passwort falsch." else "Anmeldung fehlgeschlagen ($code)."
            storeSession(c, JSONObject(txt))
            sp(c).edit().putString("email", mail.trim()).apply()
            email.value = mail.trim()
            connected.value = true
            lastLoad = 0
            null
        } catch (e: Exception) { "Keine Verbindung zum Server." }
    }

    private fun storeSession(c: Context, j: JSONObject) {
        access = j.getString("access_token")
        accessUntil = System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 60) * 1000
        userId = j.optJSONObject("user")?.optString("id") ?: userId
        sp(c).edit().putString("refresh", j.getString("refresh_token")).putString("uid", userId).apply()
    }

    private fun ensureToken(c: Context): Boolean {
        if (access != null && System.currentTimeMillis() < accessUntil) return true
        val r = sp(c).getString("refresh", null) ?: return false
        val (code, txt) = http("POST", "$URL_BASE/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", r).toString(), auth = false)
        if (code in 200..299) { storeSession(c, JSONObject(txt)); return true }
        if (code == 400 || code == 401) { connected.value = false; sp(c).edit().remove("refresh").apply() }
        return false
    }

    /** Laedt die Liste neu, hoechstens einmal pro Minute (force = immer). */
    suspend fun refresh(c: Context, force: Boolean = false) {
        if (!connected.value) return
        if (!force && System.currentTimeMillis() - lastLoad < 60_000) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    if (!ensureToken(c)) return@withContext
                    val (code, txt) = http("GET", "$URL_BASE/rest/v1/items?select=*", null)
                    if (code !in 200..299) return@withContext
                    File(c.cacheDir, "watchlist.json").writeText(txt)
                    val arr = JSONArray(txt)
                    val list = parse(arr)
                    withContext(Dispatchers.Main) { items.value = list; allKeys.value = keysOf(arr) }
                    lastLoad = System.currentTimeMillis()
                } catch (_: Exception) { }
            }
        }
    }

    private fun loadCache(c: Context) {
        try {
            val arr = JSONArray(File(c.cacheDir, "watchlist.json").readText())
            items.value = parse(arr); allKeys.value = keysOf(arr)
        } catch (_: Exception) { }
    }

    private fun keysOf(a: JSONArray): Set<String> {
        val base = mapOf("done" to 0.8, "watching" to 1.0, "planned" to 0.5, "dropped" to -0.7)
        val sd = ArrayList<Seed>(); var mp = 0.0
        val keys = HashSet<String>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val t = o.optString("media_type"); val id = o.optInt("tmdb_id"); val st = o.optString("status")
            keys += "$t:$id"
            if (st == "planned") mp = maxOf(mp, o.optDouble("position", 0.0))
            val r = if (o.isNull("rating")) 0 else o.optInt("rating", 0)
            val w = if (r > 0) (r - 5) / 2.5 else base[st] ?: 0.4
            if (w != 0.0) sd += Seed(t, id, o.optString("title"), w, o.optString("updated_at"))
        }
        seeds = sd.sortedWith(compareByDescending<Seed> { kotlin.math.abs(it.weight) }.thenByDescending { it.updated }).take(16)
        maxPlanned = mp
        return keys
    }

    /** Am Gucken zuerst, dann die Warteschlange, jeweils in der eigenen Reihenfolge. */
    private fun parse(a: JSONArray): List<Item> {
        val out = ArrayList<Item>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("status") !in setOf("watching", "planned")) continue
            val meta = o.optJSONObject("meta") ?: JSONObject()
            val (next, done, total) = stats(meta, o.optJSONObject("progress") ?: JSONObject())
            val prov = meta.optJSONArray("prov")
            out += Item(
                type = o.getString("media_type"), id = o.getInt("tmdb_id"), title = o.optString("title"),
                backdrop = o.optString("backdrop_path").ifBlank { null }.takeIf { it != "null" },
                poster = o.optString("poster_path").ifBlank { null }.takeIf { it != "null" },
                logo = o.optString("logo_path").ifBlank { null }.takeIf { it != "null" },
                status = o.optString("status"), position = o.optDouble("position", 0.0),
                next = next, done = done, total = total,
                providers = (0 until (prov?.length() ?: 0)).map { prov!!.getJSONObject(it).optString("n") },
                raw = o
            )
        }
        val rank = mapOf("watching" to 0, "planned" to 1)
        return out
            // Fertig geguckte, aber noch laufende Serien ohne neue Folge nach hinten
            .sortedWith(compareBy<Item>({ rank[it.status] ?: 2 }, { if (it.type == "tv" && it.next == null && it.total > 0) 1 else 0 }, { it.position }))
            .take(12)
    }

    /** Gleiche Logik wie epStats() in der Web-App. */
    private fun stats(meta: JSONObject, progress: JSONObject): Triple<Pair<Int, Int>?, Int, Int> {
        val seasons = meta.optJSONArray("seasons") ?: return Triple(null, 0, 0)
        val aired = meta.optJSONArray("aired")
        var total = 0; var done = 0; var next: Pair<Int, Int>? = null
        for (i in 0 until seasons.length()) {
            val sc = seasons.getJSONArray(i); val s = sc.getInt(0); val c = sc.getInt(1)
            val n = if (aired == null) c else {
                val a0 = aired.getInt(0); val a1 = aired.getInt(1)
                when { s < a0 -> c; s == a0 -> minOf(c, a1); else -> 0 }
            }
            val w = progress.optJSONArray(s.toString())
            val seen = HashSet<Int>().apply { if (w != null) for (k in 0 until w.length()) add(w.getInt(k)) }
            for (e in 1..n) { total++; if (e in seen) done++ else if (next == null) next = s to e }
        }
        return Triple(next, done, total)
    }

    /** Naechste Folge abhaken (wie in der Web-App: Geplant -> Am Gucken, alles gesehen + beendet -> Fertig). */
    suspend fun markNext(c: Context, it: Item): Boolean {
        val nx = it.next ?: return false
        return withContext(Dispatchers.IO) {
            try {
                if (!ensureToken(c)) return@withContext false
                val o = JSONObject(it.raw.toString())
                val prog = o.optJSONObject("progress") ?: JSONObject()
                val arr = prog.optJSONArray(nx.first.toString()) ?: JSONArray()
                val set = (0 until arr.length()).map { k -> arr.getInt(k) }.toSortedSet().apply { add(nx.second) }
                prog.put(nx.first.toString(), JSONArray(set.toList()))
                // Ganze Zeile per Upsert zurueckschreiben (POST, kein PATCH noetig)
                val patch = o.put("progress", prog).put("updated_at", Instant.now().toString())
                val cur = items.value
                if (it.status == "planned") {
                    patch.put("status", "watching")
                    patch.put("position", (cur.filter { x -> x.status == "watching" }.minOfOrNull { x -> x.position } ?: 1.0) - 1)
                }
                val (_, d, t) = stats(o.optJSONObject("meta") ?: JSONObject(), prog)
                val ended = (o.optJSONObject("meta")?.optString("tv_status") ?: "") in setOf("Ended", "Canceled")
                if (t > 0 && d >= t && ended) patch.put("status", "done").put("position", -System.currentTimeMillis() / 1000.0)
                val (code, _) = http("UPSERT", "$URL_BASE/rest/v1/items?on_conflict=user_id,media_type,tmdb_id", JSONArray().put(patch).toString())
                if (code in 200..299) { lastLoad = 0; refresh(c, force = true); true } else false
            } catch (_: Exception) { false }
        }
    }

    // ---------- TMDB-Schluessel aus dem Konto (Tabelle settings) ----------
    @Volatile private var tmdbKeyCache: String? = null

    fun tmdbKey(c: Context): String? {
        tmdbKeyCache?.let { return it }
        sp(c).getString("tmdb", null)?.let { tmdbKeyCache = it; return it }
        if (!ensureToken(c)) return null
        val (code, txt) = http("GET", "$URL_BASE/rest/v1/settings?select=tmdb_key", null)
        if (code !in 200..299) return null
        val k = try { JSONArray(txt).optJSONObject(0)?.optString("tmdb_key")?.takeIf { it.isNotBlank() && it != "null" } } catch (_: Exception) { null }
        if (k != null) { tmdbKeyCache = k; sp(c).edit().putString("tmdb", k).apply() }
        return k
    }

    /** Neuen Titel als "Geplant" ans Ende der Warteschlange setzen (Zeile wie itemFromDetails() der Web-App). */
    suspend fun addPlanned(c: Context, type: String, d: JSONObject): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!ensureToken(c)) return@withContext false
            val now = Instant.now().toString()
            val row = JSONObject()
                .put("user_id", userId).put("media_type", type).put("tmdb_id", d.getInt("id"))
                .put("title", Discover.nameOf(d))
                .put("poster_path", d.optString("poster_path").takeIf { it.isNotBlank() && it != "null" } ?: JSONObject.NULL)
                .put("backdrop_path", d.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" } ?: JSONObject.NULL)
                .put("logo_path", Discover.pickLogo(d) ?: JSONObject.NULL)
                .put("status", "planned").put("position", maxPlanned + 1)
                .put("rating", JSONObject.NULL).put("note", JSONObject.NULL)
                .put("progress", JSONObject()).put("meta", Discover.metaOf(type, d))
                .put("added_at", now).put("updated_at", now)
            val (code, _) = http("UPSERT", "$URL_BASE/rest/v1/items?on_conflict=user_id,media_type,tmdb_id", JSONArray().put(row).toString())
            if (code in 200..299) { lastLoad = 0; refresh(c, force = true); true } else false
        } catch (_: Exception) { false }
    }

    private fun http(method: String, url: String, body: String?, auth: Boolean = true): Pair<Int, String> {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (method == "UPSERT") "POST" else method
            connectTimeout = 8000; readTimeout = 10000
            setRequestProperty("apikey", KEY)
            setRequestProperty("Content-Type", "application/json")
            if (auth && access != null) setRequestProperty("Authorization", "Bearer $access")
            if (method == "UPSERT") setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
        }
        try {
            if (body != null) { con.doOutput = true; con.outputStream.use { it.write(body.toByteArray()) } }
            val code = con.responseCode
            val txt = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText() ?: ""
            return code to txt
        } finally { con.disconnect() }
    }

    // ---------- Bilder (TMDB, mit Datei-Cache) ----------
    private val mem = object : android.util.LruCache<String, ImageBitmap>(24) {}

    suspend fun image(c: Context, path: String?, size: String): ImageBitmap? {
        if (path == null) return null
        val url = if (path.startsWith("http")) path else IMG + (if (path.endsWith(".svg")) "original" else size) + path
        mem.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val name = MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                val f = File(File(c.cacheDir, "img").apply { mkdirs() }, name)
                if (!f.exists()) {
                    val con = URL(url).openConnection() as HttpURLConnection
                    con.connectTimeout = 8000; con.readTimeout = 15000
                    try { if (con.responseCode != 200) return@withContext null; con.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } } } finally { con.disconnect() }
                }
                BitmapFactory.decodeFile(f.path)?.asImageBitmap()?.also { mem.put(url, it) }
            } catch (_: Exception) { null }
        }
    }

    /** Anbieter-Name aus TMDB -> installierte TV-App */
    val PROVIDER_APPS = listOf(
        "Netflix" to "com.netflix.ninja",
        "Amazon Prime Video" to "com.amazon.avod",
        "Prime Video" to "com.amazon.avod",
        "Disney Plus" to "com.disney.disneyplus",
        "Joyn" to "de.prosiebensat1digital.seventv",
        "RTL+" to "de.cbc.vp2gen.rtlplus",
        "WOW" to "de.sky.online",
        "Apple TV Plus" to "com.apple.atve.amazon.appletv",
        // Google TV / Android TV (z. B. TCL): andere Paketnamen
        "Amazon Prime Video" to "com.amazon.amazonvideo.livingroom",
        "Prime Video" to "com.amazon.amazonvideo.livingroom",
        "Apple TV Plus" to "com.apple.atve.androidtv.appletv",
        "Apple TV" to "com.apple.atve.androidtv.appletv",
        "Paramount Plus" to "com.cbs.ca",
    )
}
