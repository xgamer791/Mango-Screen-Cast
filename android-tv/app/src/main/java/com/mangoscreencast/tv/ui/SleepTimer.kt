package com.mangoscreencast.tv.ui

import android.content.Context
import android.content.res.Resources
import com.mangoscreencast.tv.R

/**
 * The gear panel's sleep timer, as the Hisense panel's: 15, 30 or 45 minutes, an
 * hour, an hour and a half, two or three hours, then the TV goes to sleep. The
 * timer the panel used to set lived in Google TV's launcher, which is off, so
 * Mango keeps it: the end time is saved here, and the gear service, which is
 * always running, puts the TV to sleep when it comes (GearKeyService.sleepTimer).
 */
internal object SleepTimer {
    /** The panel's choices, in minutes. */
    val CHOICES = listOf(15, 30, 45, 60, 90, 120, 180)

    private const val PREFS = "sleep_timer"
    private const val END = "end"

    /** The clock, replaced in tests. */
    internal var now: () -> Long = System::currentTimeMillis

    /** When the TV goes to sleep (wall clock, ms), or null with no timer. */
    fun end(context: Context): Long? =
        prefs(context).getLong(END, 0L).takeIf { it > now() }

    fun set(context: Context, minutes: Int) {
        prefs(context).edit().putLong(END, now() + minutes * 60_000L).apply()
        GearKeyService.sleepTimerChanged()
    }

    fun cancel(context: Context) {
        prefs(context).edit().remove(END).apply()
        GearKeyService.sleepTimerChanged()
    }

    /** "15 minutes", "1 hour", "1 hour 30 minutes", "3 hours". */
    fun name(resources: Resources, minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        val h = if (hours > 0) resources.getQuantityString(R.plurals.panel_hours, hours, hours) else null
        val m = if (rest > 0) resources.getQuantityString(R.plurals.panel_minutes, rest, rest) else null
        return listOfNotNull(h, m).joinToString(" ")
    }

    /** What's left, rounded up to the minute, as the tile shows it: "2 hours 59 minutes left". */
    fun left(context: Context): String? {
        val end = end(context) ?: return null
        val minutes = ((end - now() + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        return context.getString(R.string.panel_sleep_left, name(context.resources, minutes))
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
