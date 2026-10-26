package com.mangoscreencast.tv.ui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import com.mangoscreencast.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/** The wall glides right as the menu opens and back as it closes, as it glides from row to row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class HomeMenuGlideTest {
    private fun home(): View {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = LayoutInflater.from(ContextThemeWrapper(activity, R.style.Theme_Mango)).inflate(R.layout.activity_home, null)
        activity.setContentView(root)
        HomeTiles.fill(root.findViewById(R.id.tile_grid), emptyList(), HomeTiles.Actions())
        frames(32)
        return root
    }

    /** Where `view` is drawn, in px from the page's left. */
    private fun drawn(view: View) = view.left + view.translationX

    @Test
    fun theWallGlidesRightAsTheMenuOpens() {
        val root = home()
        val dp = root.resources.displayMetrics.density
        val wall = root.findViewById<View>(R.id.tile_wall)
        val name = root.findViewById<View>(R.id.tile_name)
        val closed = drawn(wall)
        assertEquals(HomeMenu.WALL_CLOSED_DP * dp, closed, 1f)

        HomeMenu.show(root, open = true)
        frames(16)
        // Its place is the open one at once (the remote finds it there), but it's still drawn where it was.
        assertEquals(HomeMenu.WALL_OPEN_DP * dp, wall.left.toFloat(), 1f)
        assertEquals("the wall hasn't jumped", closed.toDouble(), drawn(wall).toDouble(), (12 * dp).toDouble())

        frames(TileWall.GLIDE_MS / 2)
        val half = drawn(wall)
        val way = HomeMenu.WALL_OPEN_DP * dp - closed
        // Quick at first, settling gently: more than half the way at half the time.
        assertTrue("past halfway at half the time: ${half - closed} of $way", half - closed > way / 2 && half < HomeMenu.WALL_OPEN_DP * dp)
        assertEquals("the name goes with it", drawn(wall), drawn(name), 1f)

        frames(TileWall.GLIDE_MS)
        assertEquals(HomeMenu.WALL_OPEN_DP * dp, drawn(wall), 1f)
        assertEquals(0f, wall.translationX)
    }

    @Test
    fun closingMidGlideTurnsBackFromWhereItIs() {
        val root = home()
        val wall = root.findViewById<View>(R.id.tile_wall)
        HomeMenu.show(root, open = true)
        frames(TileWall.GLIDE_MS / 2)
        val midway = drawn(wall)
        HomeMenu.show(root, open = false)
        frames(16)
        assertEquals("no jump as it turns back", midway.toDouble(), drawn(wall).toDouble(), 30.0)
        frames(TileWall.GLIDE_MS + 32)
        assertEquals(HomeMenu.WALL_CLOSED_DP * root.resources.displayMetrics.density, drawn(wall), 1f)
    }

    /** `ms` of frames, 16 ms apart. */
    private fun frames(ms: Long) = repeat((ms / 16).toInt() + 1) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
    }
}
