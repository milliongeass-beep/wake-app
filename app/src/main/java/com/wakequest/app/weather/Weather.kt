package com.wakequest.app.weather

import com.wakequest.app.data.Store
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Free Open-Meteo API: no key, no location permission (the user types their city). */
object Weather {
    data class Report(
        val place: String,
        val now: Int,
        val high: Int,
        val low: Int,
        val rainChance: Int?,
        val code: Int,
        val unit: String,
    )

    /** Blocking — call off the main thread. */
    fun fetch(store: Store): Report {
        val city = store.weatherCity.trim()
        if (city.isEmpty()) throw IOException("no city set")
        val geo = store.cachedGeo(city) ?: run {
            val q = URLEncoder.encode(city, "UTF-8")
            val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json"))
            val r = json.optJSONArray("results")?.optJSONObject(0) ?: throw IOException("City not found")
            val v = "${r.getDouble("latitude")}|${r.getDouble("longitude")}|${r.optString("name", city)}"
            store.cacheGeo(city, v)
            v
        }
        val (lat, lon, name) = geo.split('|').let { Triple(it[0], it[1], it.getOrElse(2) { city }) }
        val unitParam = if (store.fahrenheit) "&temperature_unit=fahrenheit" else ""
        val json = JSONObject(
            get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=1$unitParam",
            ),
        )
        val cur = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val rain = daily.optJSONArray("precipitation_probability_max")
        return Report(
            place = name,
            now = cur.getDouble("temperature_2m").roundToInt(),
            high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            rainChance = if (rain == null || rain.isNull(0)) null else rain.optInt(0),
            code = cur.optInt("weather_code", 0),
            unit = if (store.fahrenheit) "°F" else "°C",
        )
    }

    fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "Clear sky"
        1, 2 -> "🌤️" to "Partly cloudy"
        3 -> "☁️" to "Overcast"
        45, 48 -> "🌫️" to "Fog"
        in 51..57 -> "🌦️" to "Drizzle"
        in 61..67 -> "🌧️" to "Rain"
        in 71..77 -> "❄️" to "Snow"
        in 80..82 -> "🌦️" to "Rain showers"
        85, 86 -> "🌨️" to "Snow showers"
        in 95..99 -> "⛈️" to "Thunderstorm"
        else -> "🌡️" to "Weather"
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
