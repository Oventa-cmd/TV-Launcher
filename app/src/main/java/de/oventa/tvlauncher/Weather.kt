package de.oventa.tvlauncher

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** Aktuelle Temperatur fuer Aachen von Open-Meteo (kostenlos, ohne API-Schluessel, ohne Tracking). */
object Weather {
    private const val LAT = 50.7753
    private const val LON = 6.0839
    private const val REFRESH_MS = 20 * 60 * 1000L

    data class Now(val tempC: Int, val symbol: String)

    /** Geteilt zwischen Overlay und Activity, damit die Anzeige sofort da ist. */
    val current = mutableStateOf<Now?>(null)
    @Volatile private var lastFetch = 0L

    suspend fun refreshIfStale() {
        if (System.currentTimeMillis() - lastFetch < REFRESH_MS && current.value != null) return
        val result = withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON&current=temperature_2m,weather_code,is_day&timezone=Europe%2FBerlin")
                val con = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000; readTimeout = 6000
                }
                try {
                    if (con.responseCode != 200) return@withContext null
                    val cur = JSONObject(con.inputStream.bufferedReader().readText()).getJSONObject("current")
                    Now(
                        cur.getDouble("temperature_2m").roundToInt(),
                        symbol(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1)
                    )
                } finally { con.disconnect() }
            } catch (_: Exception) { null }
        }
        if (result != null) { current.value = result; lastFetch = System.currentTimeMillis() }
    }

    /** WMO-Wettercode -> Symbol */
    private fun symbol(code: Int, day: Boolean): String = when (code) {
        0 -> if (day) "☀" else "🌙"
        1, 2 -> if (day) "⛅" else "☁"
        3 -> "☁"
        45, 48 -> "🌫"
        in 51..67, in 80..82 -> "☂"
        in 71..77, 85, 86 -> "❄"
        in 95..99 -> "⚡"
        else -> "☁"
    }
}
