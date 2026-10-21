package dev.vr.panes

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The clock reads the time where the cameras are, not where the TV is: a window onto Tokyo shows Tokyo's evening.
 * [zone] comes with the weather; until it does (or if it never does) the TV's own zone stands in.
 */
object Clock {
    /** The time in the locale's own pattern without its day period, so no "PM" survives behind a narrow no-break space. */
    fun time(ctx: Context, zone: TimeZone?, now: Long = System.currentTimeMillis()): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (DateFormat.is24HourFormat(ctx)) "Hm" else "hm")
        return format(pattern.replace("a", "").trim(), zone, now)
    }

    /** "Saturday, Oct 19", the weather after it; led by [name] when that place's clock differs from the TV's. */
    fun line(name: String?, weather: Weather.Now?, now: Long = System.currentTimeMillis()): String {
        val zone = weather?.zone
        val away = zone != null && zone.getOffset(now) != TimeZone.getDefault().getOffset(now)
        return listOfNotNull(
            name?.takeIf { away },
            format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMd"), zone, now),
            weather?.let { "${it.tempF}° ${it.summary}".trim() },
        ).joinToString("  ·  ")
    }

    private fun format(pattern: String, zone: TimeZone?, now: Long) =
        SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = zone ?: TimeZone.getDefault() }.format(Date(now))
}
