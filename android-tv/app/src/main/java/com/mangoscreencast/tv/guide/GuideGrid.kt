package com.mangoscreencast.tv.guide

import com.mangoscreencast.tv.guide.PlutoTv.Show
import java.util.Calendar
import java.util.TimeZone

/**
 * How the Prevue grid lays a channel's shows across its half hours.
 *
 * The grid shows three half hours from the one it's in now. Each half hour
 * goes to the first show that starts inside it, or else to the show on as it
 * starts, and a show that holds several half hours in a row is one box across
 * them. So a show starting at 11:45 takes the 11:30 box, and the show before
 * it, on until 11:45, ends at 11:30, as on Prevue: there it read
 * "(11:45) "A River Runs…" beside "Legends of the North".
 */
object GuideGrid {
    const val SLOT_MS = 30 * 60_000L
    const val SLOTS = 3

    /** One box in a channel's row: `show` (null for a gap in the listings) across half hours `first` to `last`. */
    data class Cell(
        val show: Show?,
        val first: Int,
        val last: Int,
        /** Arrows on the left: the show began before the grid's first half hour (2: over half an hour before). */
        val before: Int = 0,
        /** Arrows on the right: the show runs past the grid's last half hour (2: over half an hour past). */
        val after: Int = 0,
    )

    /** The start of the half hour `now` is in, on the TV's clock. */
    fun slotStart(now: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val offset = zone.getOffset(now)
        return now - Math.floorMod(now + offset, SLOT_MS)
    }

    /** `shows` (sorted by start) as the boxes of a row whose first half hour starts at `from`. */
    fun cells(shows: List<Show>, from: Long, slots: Int = SLOTS): List<Cell> {
        val end = from + slots * SLOT_MS
        val owners = (0 until slots).map { slot ->
            val opens = from + slot * SLOT_MS
            val closes = opens + SLOT_MS
            shows.firstOrNull { it.start in opens until closes } ?: shows.firstOrNull { it.start <= opens && it.stop > opens }
        }
        val cells = mutableListOf<Cell>()
        var slot = 0
        while (slot < slots) {
            val show = owners[slot]
            var last = slot
            while (last + 1 < slots && owners[last + 1] === show) last++
            val before = if (slot == 0 && show != null) arrows(from - show.start) else 0
            val after = if (last == slots - 1 && show != null) arrows(show.stop - end) else 0
            cells += Cell(show, slot, last, before, after)
            slot = last + 1
        }
        return cells
    }

    /** No arrow when the show is inside the grid, one when it runs up to half an hour beyond, two past that. */
    private fun arrows(beyond: Long) = when {
        beyond <= 0 -> 0
        beyond <= SLOT_MS -> 1
        else -> 2
    }

    /** "(6:40) " when `show` starts off the half hour, as Prevue gave such shows' start times; empty otherwise. */
    fun startNote(show: Show, zone: TimeZone = TimeZone.getDefault()): String {
        val at = Calendar.getInstance(zone).apply { timeInMillis = show.start }
        val minute = at.get(Calendar.MINUTE)
        if (minute % 30 == 0) return ""
        val hour = at.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        return "($hour:${minute.toString().padStart(2, '0')}) "
    }

    /** A show's name in the grid: a movie's in quotes, as Prevue had them. */
    fun title(show: Show) = if (show.film) "\"${show.title}\"" else show.title

    /** " (2009)" after a movie's name, unless Pluto already put the year in it ("A Star Is Born (2018)"). */
    fun year(show: Show) = show.year?.takeUnless { show.title.contains("($it)") }?.let { " ($it)" } ?: ""

    /** "10:30 AM": a half hour along the top of the grid, or a time in the box above it. */
    fun clock(time: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val at = Calendar.getInstance(zone).apply { timeInMillis = time }
        val hour = at.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val half = if (at.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        return "$hour:${at.get(Calendar.MINUTE).toString().padStart(2, '0')} $half"
    }

    /** "10:45:11": the running clock in the grid's corner. */
    fun seconds(time: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val at = Calendar.getInstance(zone).apply { timeInMillis = time }
        val hour = at.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "$hour:${two(at.get(Calendar.MINUTE))}:${two(at.get(Calendar.SECOND))}"
    }

    /** The show on `channel` at `time`, and the one after it. */
    fun onAt(shows: List<Show>, time: Long): Pair<Show?, Show?> {
        val now = shows.indexOfFirst { it.start <= time && it.stop > time }
        if (now < 0) return null to shows.firstOrNull { it.start > time }
        return shows[now] to shows.getOrNull(now + 1)
    }
}
