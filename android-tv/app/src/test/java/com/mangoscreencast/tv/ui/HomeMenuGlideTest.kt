package com.mangoscreencast.tv.ui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

/** The wall glides aside as the menu opens and back as it closes, the menu's bar growing and shrinking behind it. */
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

    /** Where the menu's bar is drawn to, in px from the page's left: as far as it's shown. */
    private fun barEnd(menu: View) = menu.left + (menu.clipBounds?.right ?: menu.width)

    /** How far the open menu's bars stop short of the open wall: the room it keeps from them all the way. */
    private fun clearance(dp: Float) = (HomeMenu.WALL_OPEN_DP - 351f) * dp

    /**
     * Whether the bar, where it's grown past the icons (always there, beside the closed wall), keeps
     * the open menu's room from the wall.
     */
    private fun clear(menu: View, wall: View, dp: Float) =
        barEnd(menu) <= menu.left + HomeMenu.MENU_CLOSED_DP * dp + 1 || barEnd(menu) + clearance(dp) <= drawn(wall) + 1

    /**
     * Opening, the wall glides aside and the menu's bar grows into the room it leaves, never nearer
     * the wall than when it's open: they never meet. It starts gently and takes its time.
     */
    @Test
    fun opening_theBarGrowsBehindTheWallWithoutTouchingIt() {
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
        assertTrue("the bar grew before the wall moved (to ${barEnd(menu)} px)", barEnd(menu) <= menu.left + HomeMenu.MENU_CLOSED_DP * dp + 1)

        // Unhurried: it eases off, a small step in the first frame, and takes over a quarter second.
        assertTrue(HomeMenu.MOVE_MS >= 300)
        frames(16)
        val way = (HomeMenu.WALL_OPEN_DP - HomeMenu.WALL_CLOSED_DP) * dp
        assertTrue("snapped off: ${drawn(wall) - closed} of $way px in a frame", drawn(wall) - closed < way / 10)

        var frames = 0
        while (drawn(wall) < HomeMenu.WALL_OPEN_DP * dp - 0.5f && frames++ < 60) {
            assertTrue("the bar (to ${barEnd(menu)} px) ran into the wall (at ${drawn(wall)} px)", clear(menu, wall, dp))
            assertEquals("the name goes with it", drawn(wall), drawn(name), 1f)
            frames(16)
        }
        frames(32)
        assertEquals(HomeMenu.WALL_OPEN_DP * dp, drawn(wall), 1f)
        assertEquals(0f, wall.translationX)
        assertEquals("the bar's all shown", null, menu.clipBounds)
        assertEquals(351f * dp, menu.right.toFloat(), 1f)
    }

    /** Closing, the bar shrinks ahead of the wall coming back, and the menu ends as it was: icons only. */
    @Test
    fun closing_theBarShrinksAheadOfTheWall() {
        val root = home()
        val dp = root.resources.displayMetrics.density
        val wall = root.findViewById<View>(R.id.tile_wall)
        val menu = root.findViewById<ViewGroup>(R.id.menu)
        HomeMenu.show(root, open = true)
        frames(HomeMenu.MOVE_MS + 32)
        HomeMenu.show(root, open = false)
        relayout(root)
        assertEquals("the wall hasn't jumped", HomeMenu.WALL_OPEN_DP * dp, drawn(wall), 1f)
        var frames = 0
        while (drawn(wall) > HomeMenu.WALL_CLOSED_DP * dp + 0.5f && frames++ < 60) {
            assertTrue("the wall (at ${drawn(wall)} px) ran into the bar (to ${barEnd(menu)} px)", clear(menu, wall, dp))
            frames(16)
        }
        frames(32)
        relayout(root)
        assertEquals(HomeMenu.WALL_CLOSED_DP * dp, drawn(wall), 1f)
        assertEquals("icons only", HomeMenu.MENU_CLOSED_DP * dp, menu.width.toFloat(), 1f)
        assertTrue("the closed menu reaches over the wall", menu.right <= wall.left)
        for (i in 0 until menu.childCount) assertEquals("words still showing", 0, (menu.getChildAt(i) as TextView).currentTextColor)
    }

    @Test
    fun closingMidGlideTurnsBackFromWhereItIs() {
        val root = home()
        val dp = root.resources.displayMetrics.density
        val wall = root.findViewById<View>(R.id.tile_wall)
        val menu = root.findViewById<View>(R.id.menu)
        HomeMenu.show(root, open = true)
        frames(HomeMenu.MOVE_MS / 3)
        val midway = drawn(wall)
        HomeMenu.show(root, open = false)
        frames(16)
        assertEquals("no jump as it turns back", midway.toDouble(), drawn(wall).toDouble(), 30.0)
        assertTrue("the bar ran into the wall turning back", clear(menu, wall, dp))
        frames(HomeMenu.MOVE_MS + 32)
        assertEquals(HomeMenu.WALL_CLOSED_DP * dp, drawn(wall), 1f)
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
