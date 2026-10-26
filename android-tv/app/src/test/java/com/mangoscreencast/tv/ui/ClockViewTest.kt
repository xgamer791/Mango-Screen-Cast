package com.mangoscreencast.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class ClockViewTest {
    private val at = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 15, 24) }.time

    @Test
    fun twelveHourTimeIsLowerCaseAsOnTheRoku() = assertEquals("3:24 pm", ClockView.format(at, false, Locale.US))

    @Test
    fun twentyFourHourTimeWhenTheTvIsSetToIt() = assertEquals("15:24", ClockView.format(at, true, Locale.US))
}
