package dev.vr.panes

import org.json.JSONObject
import java.util.TimeZone
import kotlin.math.roundToInt

/** Current conditions from Open-Meteo (no key). Null on any failure; the caller just hides the line. */
object Weather {
    /** [zone] is the place's own time zone, which Open-Meteo answers with at no extra cost. */
    class Now(val tempF: Int, val summary: String, val zone: TimeZone?)

    fun fetch(lat: Double, lon: Double): Now? {
        val j = Net.text("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code&temperature_unit=fahrenheit&timezone=auto") ?: return null
        val o = JSONObject(j)
        val c = o.optJSONObject("current") ?: return null
        // getTimeZone falls back to GMT on an unknown id; only a real match is worth showing.
        val zone = o.optString("timezone").takeIf { it in TimeZone.getAvailableIDs() }?.let(TimeZone::getTimeZone)
        return Now(c.getDouble("temperature_2m").roundToInt(), describe(c.getInt("weather_code")), zone)
    }

    // WMO 4677 codes as Open-Meteo documents them.
    private fun describe(code: Int) = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> ""
    }
}
