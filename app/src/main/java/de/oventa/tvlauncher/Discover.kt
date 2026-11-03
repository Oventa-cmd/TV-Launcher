package de.oventa.tvlauncher

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale

/**
 * Entdecken-Reiter fuer die Watchlist-Zeile (Daten von TMDB, Schluessel aus dem Watchlist-Konto).
 * Titel, die schon auf der Liste stehen, werden in der Oberflaeche ausgefiltert.
 */
object Discover {

    data class Tab(val id: String, val label: String)

    data class Pick(
        val type: String, val id: Int, val title: String,
        val backdrop: String?, val poster: String?, val overview: String,
        val year: String, val vote: Double, val reason: String? = null
    ) {
        val key get() = "$type:$id"
        val typeLabel get() = if (type == "tv") "Serie" else "Film"
        val info: String get() = listOfNotNull(
            typeLabel, year.ifBlank { null },
            if (vote > 0) "★ " + String.format(Locale.GERMANY, "%.1f", vote) else null
        ).joinToString(" · ")
    }

    /** Reihenfolge der Reiter. "list" = eigene Watchlist. */
    fun tabs(): List<Tab> = if (!Watchlist.connected.value) listOfNotNull(
        if (StreamTV.items.value.isNotEmpty()) Tab("stv", "Weiterschauen in StreamTV") else null
    ) else listOfNotNull(
        if (StreamTV.items.value.isNotEmpty()) Tab("stv", "Weiterschauen in StreamTV") else null,
        Tab("list", "Deine Watchlist"),
        Tab("top", "Top 10 diese Woche"),
        Tab("tvrecs", "Serien für dich"),
        Tab("movierecs", "Filme für dich"),
        becauseSeed()?.let { Tab("because", "Weil du „${it.title}“ magst") },
        Tab("new", "Neu bei Netflix & Prime"),
        Tab("best", "Bestbewertete Serien"),
        Tab("hidden", "Geheimtipps"),
    )

    val lists = mutableStateOf<Map<String, List<Pick>>>(emptyMap())
    val loading = mutableStateOf<Set<String>>(emptySet())
    private val loadedAt = HashMap<String, Long>()
    private val loadedSig = HashMap<String, String>()
    private val lock = Mutex()
    private const val TTL = 6 * 60 * 60 * 1000L

    fun clear() { lists.value = emptyMap(); loadedAt.clear(); loadedSig.clear() }

    private fun becauseSeed() = Watchlist.seeds.filter { it.weight > 0 }.maxByOrNull { it.weight }

    private fun seedSig() = Watchlist.seeds.joinToString("|") { "${it.type}:${it.id}:${it.weight}" }

    /** Laedt einen Reiter, falls noetig (gecacht, Empfehlungen neu bei geaenderter Liste). */
    suspend fun ensure(c: Context, tab: String) {
        if (tab == "list" || tab == "stv") return
        val sig = if (tab in setOf("tvrecs", "movierecs", "because")) seedSig() else ""
        val fresh = lists.value[tab] != null && System.currentTimeMillis() - (loadedAt[tab] ?: 0) < TTL && loadedSig[tab] == sig
        if (fresh || tab in loading.value) return
        lock.withLock {
            loading.value = loading.value + tab
            try {
                val res = withContext(Dispatchers.IO) {
                    val key = Watchlist.tmdbKey(c) ?: return@withContext null
                    try { load(key, tab) } catch (_: Exception) { null }
                }
                if (res != null) {
                    lists.value = lists.value + (tab to res)
                    loadedAt[tab] = System.currentTimeMillis(); loadedSig[tab] = sig
                }
            } finally { loading.value = loading.value - tab }
        }
    }

    private suspend fun load(key: String, tab: String): List<Pick> {
        val today = LocalDate.now()
        return when (tab) {
            "top" -> (1..2).flatMap { p -> picks(get(key, "/trending/all/week", mapOf("page" to "$p")), null) }
            "tvrecs" -> recs(key, "tv")
            "movierecs" -> recs(key, "movie")
            "because" -> {
                val s = becauseSeed() ?: return emptyList()
                picks(get(key, "/${s.type}/${s.id}/recommendations"), s.type)
            }
            "new" -> {
                val since = today.minusDays(120).toString()
                val common = mapOf("watch_region" to "DE", "with_watch_providers" to "8|9", "sort_by" to "popularity.desc", "vote_count.gte" to "15")
                val tv = picks(get(key, "/discover/tv", common + ("first_air_date.gte" to since)), "tv")
                val mv = picks(get(key, "/discover/movie", common + ("primary_release_date.gte" to since)), "movie")
                interleave(tv, mv)
            }
            "best" -> (1..2).flatMap { p -> picks(get(key, "/tv/top_rated", mapOf("page" to "$p")), "tv") }
            "hidden" -> {
                // Stark bewertet, aber wenig bekannt
                val q = mapOf("sort_by" to "vote_average.desc", "vote_average.gte" to "7.8", "vote_count.gte" to "150", "vote_count.lte" to "1500")
                interleave(picks(get(key, "/discover/tv", q), "tv"), picks(get(key, "/discover/movie", q), "movie"))
            }
            else -> emptyList()
        }.filter { it.backdrop != null || it.poster != null }.distinctBy { it.key }
    }

    private fun interleave(a: List<Pick>, b: List<Pick>): List<Pick> {
        val out = ArrayList<Pick>()
        for (i in 0 until maxOf(a.size, b.size)) { a.getOrNull(i)?.let(out::add); b.getOrNull(i)?.let(out::add) }
        return out
    }

    /** Wie computeRecs() der Web-App, getrennt nach Serie/Film. */
    private suspend fun recs(key: String, want: String): List<Pick> = coroutineScope {
        val seeds = Watchlist.seeds
        val scores = HashMap<String, Triple<Pick, Double, MutableList<Pair<Double, String>>>>()
        val results = seeds.map { s ->
            async(Dispatchers.IO) { s to try { get(key, "/${s.type}/${s.id}/recommendations").optJSONArray("results") } catch (_: Exception) { null } }
        }.awaitAll()
        for ((s, arr) in results) {
            if (arr == null) continue
            for (i in 0 until minOf(arr.length(), 20)) {
                val o = arr.getJSONObject(i)
                val type = o.optString("media_type").ifBlank { s.type }
                if (type != want) continue
                val p = pick(o, type) ?: continue
                val sc = s.weight * (1 - i / 26.0) * (if (o.optInt("vote_count") > 50) 1.0 else 0.6)
                val e = scores.getOrPut(p.key) { Triple(p, 0.0, ArrayList()) }
                if (sc > 0) e.third += sc to s.title
                scores[p.key] = Triple(e.first, e.second + sc, e.third)
            }
        }
        scores.values.filter { it.second > 0.25 }.sortedByDescending { it.second }.take(40).map { (p, _, why) ->
            p.copy(reason = why.maxByOrNull { it.first }?.let { "Wegen „${it.second}“" })
        }
    }

    private fun picks(j: JSONObject, forceType: String?): List<Pick> {
        val arr = j.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            pick(o, forceType ?: o.optString("media_type"))
        }
    }

    private fun pick(o: JSONObject, type: String): Pick? {
        if (type != "tv" && type != "movie") return null
        fun str(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }
        return Pick(
            type = type, id = o.optInt("id"), title = nameOf(o),
            backdrop = str("backdrop_path"), poster = str("poster_path"),
            overview = o.optString("overview"),
            year = (str("release_date") ?: str("first_air_date") ?: "").take(4),
            vote = o.optDouble("vote_average", 0.0)
        )
    }

    /** Freie Suche nach Serien und Filmen. */
    suspend fun search(c: Context, query: String): List<Pick>? = withContext(Dispatchers.IO) {
        try {
            val key = Watchlist.tmdbKey(c) ?: return@withContext null
            (1..2).flatMap { p -> picks(get(key, "/search/multi", mapOf("query" to query, "include_adult" to "false", "page" to "$p")), null) }
                .filter { it.backdrop != null || it.poster != null }
                .distinctBy { it.key }
        } catch (_: Exception) { null }
    }

    // ---------- Details (fuer Detailansicht + Hinzufuegen) ----------
    suspend fun details(c: Context, type: String, id: Int): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val key = Watchlist.tmdbKey(c) ?: return@withContext null
            val d = get(key, "/$type/$id", mapOf("append_to_response" to "images,watch/providers", "include_image_language" to "de,en,null"))
            if (d.optString("overview").isBlank()) {
                try { d.put("overview", get(key, "/$type/$id", mapOf("language" to "en-US")).optString("overview")) } catch (_: Exception) { }
            }
            d
        } catch (_: Exception) { null }
    }

    fun nameOf(d: JSONObject): String =
        listOf("title", "name", "original_title", "original_name").map { d.optString(it) }.firstOrNull { it.isNotBlank() } ?: ""

    fun pickLogo(d: JSONObject): String? {
        val logos = d.optJSONObject("images")?.optJSONArray("logos") ?: return null
        fun rank(o: JSONObject) = when { o.isNull("iso_639_1") -> 1; o.optString("iso_639_1") == "de" -> 3; o.optString("iso_639_1") == "en" -> 2; else -> 0 }
        val best = (0 until logos.length()).map { logos.getJSONObject(it) }
            .sortedWith(compareByDescending<JSONObject> { rank(it) }.thenByDescending { it.optDouble("vote_average") })
            .firstOrNull() ?: return null
        return if (rank(best) > 0) best.optString("file_path") else null
    }

    fun providersDE(d: JSONObject): List<String> {
        val fr = d.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject("DE")?.optJSONArray("flatrate") ?: return emptyList()
        return (0 until fr.length()).map { fr.getJSONObject(it).optString("provider_name") }
    }

    fun genres(d: JSONObject): List<String> {
        val g = d.optJSONArray("genres") ?: return emptyList()
        return (0 until g.length()).map { g.getJSONObject(it).optString("name") }
    }

    /** meta wie itemFromDetails() der Web-App */
    fun metaOf(type: String, d: JSONObject): JSONObject {
        val meta = JSONObject()
            .put("year", ((d.optString("release_date").ifBlank { d.optString("first_air_date") })).take(4))
            .put("genres", JSONArray(genres(d).take(3)))
        val fr = d.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject("DE")?.optJSONArray("flatrate")
        val prov = JSONArray()
        if (fr != null) for (i in 0 until minOf(3, fr.length())) {
            val p = fr.getJSONObject(i); prov.put(JSONObject().put("n", p.optString("provider_name")).put("l", p.optString("logo_path")))
        }
        meta.put("prov", prov)
        if (type == "tv") {
            val seasons = JSONArray()
            d.optJSONArray("seasons")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    if (s.optInt("season_number") > 0 && s.optInt("episode_count") > 0) seasons.put(JSONArray().put(s.optInt("season_number")).put(s.optInt("episode_count")))
                }
            }
            meta.put("seasons", seasons)
            val last = d.optJSONObject("last_episode_to_air")
            meta.put("aired", if (last != null) JSONArray().put(last.optInt("season_number")).put(last.optInt("episode_number")) else JSONArray().put(0).put(0))
            meta.put("tv_status", d.optString("status").ifBlank { null } ?: JSONObject.NULL)
        } else {
            meta.put("runtime", if (d.optInt("runtime") > 0) d.optInt("runtime") else JSONObject.NULL)
        }
        return meta
    }

    private fun get(key: String, path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val p = LinkedHashMap<String, String>().apply { put("language", "de-DE"); putAll(params) }
        val bearer = key.startsWith("eyJ")
        if (!bearer) p["api_key"] = key
        val q = p.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val con = URL("https://api.themoviedb.org/3$path?$q").openConnection() as HttpURLConnection
        con.connectTimeout = 8000; con.readTimeout = 12000
        con.setRequestProperty("accept", "application/json")
        if (bearer) con.setRequestProperty("Authorization", "Bearer $key")
        try {
            if (con.responseCode != 200) throw IllegalStateException("TMDB ${con.responseCode}")
            return JSONObject(con.inputStream.bufferedReader().readText())
        } finally { con.disconnect() }
    }
}
