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
    fun theWallGlidesRightAsTheMenuOpensClearOfItsBar() {
        val root = home()
        val dp = root.resources.displayMetrics.density
        val wall = root.findViewById<View>(R.id.tile_wall)
        val name = root.findViewById<View>(R.id.tile_name)
        val menu = root.findViewById<View>(R.id.menu)
        val closed = drawn(wall)
        assertEquals(HomeMenu.WALL_CLOSED_DP * dp, closed, 1f)

        HomeMenu.show(root, open = true)
        relayout(root)
        // Its place is the open one at once (the remote finds it there), but it's still drawn where it was.
        assertEquals(HomeMenu.WALL_OPEN_DP * dp, wall.left.toFloat(), 1f)
        assertEquals("the wall hasn't jumped", closed, drawn(wall), 1f)

        // Off fast: past the end of the menu's white bar within half its glide.
        val barEnd = menu.left + HomeMenu.MENU_OPEN_DP * dp
        frames(HomeMenu.OPEN_MS / 2)
        assertTrue("clear of the bar (${drawn(wall)} px, bar ends at $barEnd px)", drawn(wall) > barEnd)
        assertTrue("still settling", drawn(wall) < HomeMenu.WALL_OPEN_DP * dp)
        assertEquals("the name goes with it", drawn(wall), drawn(name), 1f)

        // Faster than its glide from row to row: there well before that would be.
        frames(HomeMenu.OPEN_MS)
        assertTrue(HomeMenu.OPEN_MS + 2 * 16 < TileWall.GLIDE_MS)
        assertEquals(HomeMenu.WALL_OPEN_DP * dp, drawn(wall), 1f)
        assertEquals(0f, wall.translationX)
    }

    /** Closing, it glides back on the row-to-row curve. */
    @Test
    fun theWallGlidesBackAsTheMenuCloses() {
        val root = home()
        val dp = root.resources.displayMetrics.density
        val wall = root.findViewById<View>(R.id.tile_wall)
        HomeMenu.show(root, open = true)
        frames(HomeMenu.OPEN_MS + 32)
        HomeMenu.show(root, open = false)
        relayout(root)
        assertEquals("the wall hasn't jumped", HomeMenu.WALL_OPEN_DP * dp, drawn(wall), 1f)
        frames(TileWall.GLIDE_MS / 2)
        val way = (HomeMenu.WALL_OPEN_DP - HomeMenu.WALL_CLOSED_DP) * dp
        val gone = HomeMenu.WALL_OPEN_DP * dp - drawn(wall)
        assertTrue("past halfway at half the time: $gone of $way", gone > way / 2 && gone < way)
        frames(TileWall.GLIDE_MS)
        assertEquals(HomeMenu.WALL_CLOSED_DP * dp, drawn(wall), 1f)
    }

    @Test
    fun closingMidGlideTurnsBackFromWhereItIs() {
        val root = home()
        val wall = root.findViewById<View>(R.id.tile_wall)
        HomeMenu.show(root, open = true)
        frames(HomeMenu.OPEN_MS / 3)
        val midway = drawn(wall)
        HomeMenu.show(root, open = false)
        frames(16)
        assertEquals("no jump as it turns back", midway.toDouble(), drawn(wall).toDouble(), 30.0)
        frames(TileWall.GLIDE_MS + 32)
        assertEquals(HomeMenu.WALL_CLOSED_DP * root.resources.displayMetrics.density, drawn(wall), 1f)
    }

    /** The page laid out again now, as the next frame would. */
    private fun relayout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.width, root.height)
    }

    /** `ms` of frames, 16 ms apart. */
    private fun frames(ms: Long) = repeat((ms / 16).toInt() + 1) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
    }
}
