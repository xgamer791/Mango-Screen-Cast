package com.mangoscreencast.tv.guide

import com.mangoscreencast.tv.guide.GuideGrid.Cell
import com.mangoscreencast.tv.guide.PlutoTv.Show
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class GuideGridTest {
    private val central = TimeZone.getTimeZone("America/Chicago")
    private val minute = 60_000L

    /** 10:30 AM in San Antonio on 2026-10-27 (CDT, UTC-5). */
    private val half1030 = 1_793_115_000_000L

    private fun at(minutesFrom1030: Int) = half1030 + minutesFrom1030 * minute
    private fun show(title: String, from: Int, to: Int, film: Boolean = false) = Show(title, at(from), at(to), film)

    @Test
    fun `the grid starts at the half hour it's in`() {
        assertEquals("10:30 AM", GuideGrid.clock(half1030, central))
        assertEquals(half1030, GuideGrid.slotStart(at(15) + 11_000, central))
        assertEquals(half1030, GuideGrid.slotStart(half1030, central))
        assertEquals(at(30), GuideGrid.slotStart(at(30), central))
    }

    @Test
    fun `a show starting at 11_45 takes the 11_30 box from the one before it, as on Prevue`() {
        val legends = show("Legends of the North", -45, 75)
        val river = show("A River Runs Through It", 75, 210, film = true)

        assertEquals(
            listOf(Cell(legends, 0, 1, before = 2), Cell(river, 2, 2, after = 2)),
            GuideGrid.cells(listOf(legends, river), half1030),
        )
    }

    @Test
    fun `one arrow for up to half an hour beyond the grid, two past that`() {
        val headlines = show("Headline News", -20, 100)
        val posse = show("Posse", 0, 111)

        assertEquals(listOf(Cell(headlines, 0, 2, before = 1, after = 1)), GuideGrid.cells(listOf(headlines), half1030))
        assertEquals(listOf(Cell(posse, 0, 2, after = 1)), GuideGrid.cells(listOf(posse), half1030))
        assertEquals(
            listOf(Cell(show("x", -60, 200), 0, 2, before = 2, after = 2)),
            GuideGrid.cells(listOf(show("x", -60, 200)), half1030),
        )
    }

    @Test
    fun `half-hour shows get a box each, and a gap in the listings an empty one`() {
        val a = show("Paid Program", 0, 30)
        val b = show("Paid Program", 30, 60)

        assertEquals(
            listOf(Cell(a, 0, 0), Cell(b, 1, 1), Cell(null, 2, 2)),
            GuideGrid.cells(listOf(a, b), half1030),
        )
    }

    @Test
    fun `a short show starting a half hour keeps it, and the one after takes the next`() {
        val short = show("Short", 0, 10)
        val long = show("Long", 10, 90)

        assertEquals(
            listOf(Cell(short, 0, 0), Cell(long, 1, 2)),
            GuideGrid.cells(listOf(short, long), half1030),
        )
    }

    @Test
    fun `shows starting off the half hour have their start time, movies their quotes`() {
        assertEquals("(11:45) ", GuideGrid.startNote(show("x", 75, 90), central))
        assertEquals("(6:40) ", GuideGrid.startNote(Show("x", at(-230), at(0)), central))
        assertEquals("", GuideGrid.startNote(show("x", 30, 60), central))
        assertEquals("\"Posse\"", GuideGrid.title(show("Posse", 0, 111, film = true)))
        assertEquals("Headline News", GuideGrid.title(show("Headline News", 0, 30)))
        assertEquals(" (1993)", GuideGrid.year(Show("Posse", at(0), at(111), film = true, year = 1993)))
        assertEquals("", GuideGrid.year(Show("A Star Is Born (2018)", at(0), at(135), film = true, year = 2018)))
    }

    @Test
    fun `the corner clock runs with seconds, the half hours say AM or PM`() {
        assertEquals("10:45:11", GuideGrid.seconds(at(15) + 11_000, central))
        assertEquals("12:00 PM", GuideGrid.clock(at(90), central))
        assertEquals("12:30 AM", GuideGrid.clock(at(14 * 60), central))
    }

    @Test
    fun `what's on now and next`() {
        val a = show("A", -30, 30)
        val b = show("B", 30, 60)

        assertEquals(a to b, GuideGrid.onAt(listOf(a, b), at(10)))
        assertEquals(b to null, GuideGrid.onAt(listOf(a, b), at(45)))
        assertEquals(null to a, GuideGrid.onAt(listOf(a, b), at(-60)))
    }
}
