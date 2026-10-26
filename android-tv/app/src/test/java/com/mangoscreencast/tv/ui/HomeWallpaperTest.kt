package com.mangoscreencast.tv.ui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.widget.ImageView
import com.mangoscreencast.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration
import java.util.Calendar
import java.util.TimeZone

/** Halloween's wallpaper through October, then the tropical sunsets in turn, a quarter hour each, fading in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeWallpaperTest {
    private val chicago = TimeZone.getTimeZone("America/Chicago")

    private fun time(month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(chicago).apply { clear(); set(2026, month, day, hour, minute) }.timeInMillis

    @Test
    fun halloweenAllOctober() {
        for (t in listOf(time(Calendar.OCTOBER, 1, 0, 0), time(Calendar.OCTOBER, 25, 21, 0), time(Calendar.OCTOBER, 31, 23, 59)))
            assertEquals(R.drawable.home_wallpaper_halloween, HomeWallpaper.at(t, chicago))
    }

    @Test
    fun thenTheSunsetsTakeTurnsEachQuarterHour() {
        val start = time(Calendar.NOVEMBER, 1, 0, 0)
        val turns = (0 until 7).map { HomeWallpaper.at(start + it * HomeWallpaper.TURN_MS, chicago) }
        val first = HomeWallpaper.TROPICAL.indexOf(turns[0])
        assertTrue(first >= 0)
        for ((i, wallpaper) in turns.withIndex())
            assertEquals("quarter $i", HomeWallpaper.TROPICAL[(first + i) % 3], wallpaper)
        // Within a quarter hour, the same one.
        assertEquals(turns[0], HomeWallpaper.at(start + HomeWallpaper.TURN_MS - 1, chicago))
    }

    @Test
    fun theNextOneFadesInOverTheLast() {
        // Frames every 16 ms as the clock moves on, as on the TV.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val back = ImageView(activity)
        val front = ImageView(activity).apply { alpha = 0f }
        activity.setContentView(android.widget.FrameLayout(activity).apply { addView(back); addView(front) })
        var now = time(Calendar.NOVEMBER, 2, 9, 0)
        var changes = 0
        val wallpaper = HomeWallpaper(back, front, { changes++ }, { now })
        wallpaper.start()
        assertEquals(HomeWallpaper.at(now), shadowOf(back.drawable).createdFromResId)
        assertEquals(1, changes)

        // A quarter hour on: the next sunset comes in on the front view, from clear to whole.
        now += HomeWallpaper.TURN_MS
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HomeWallpaper.TURN_MS + 100))
        assertEquals(HomeWallpaper.at(now), shadowOf(front.drawable).createdFromResId)
        frames(HomeWallpaper.FADE_MS / 2)
        assertTrue("half faded in: ${front.alpha}", front.alpha > 0.2f && front.alpha < 0.8f)
        frames(HomeWallpaper.FADE_MS)
        // Faded in: it's the wallpaper now, and the front view is clear again for the next.
        assertEquals(HomeWallpaper.at(now), shadowOf(back.drawable).createdFromResId)
        assertEquals(0f, front.alpha)
        assertNull(front.drawable)
        assertEquals(2, changes)
        wallpaper.stop()
    }

    /** `ms` of frames, 16 ms apart. */
    private fun frames(ms: Long) = repeat((ms / 16).toInt() + 1) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
    }
}
